package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.agent.SyntheticContractModelBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ContractRecordSubmissionTest.*;

/** Invented documents; exercises serialized provider tools through SDK to the actual Host. */
class ContractToolSchemaRoundTripTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void actualProviderSchemaExplainsTheExistingAtomicProtocolWithoutDroppingLegacyFact() throws Exception {
        Fixture f = fixture();
        List<Map<String,Object>> requests = new ArrayList<>();
        Map<String,Object> start = f.host.start();
        Map<String,Object> originalTool = ((List<?>) start.get("tools")).stream().map(ContractToolSchemaRoundTripTest::object)
            .filter(tool -> "submit_candidate_fact".equals(tool.get("name"))).findFirst().get();
        String originalWire = JSON.writeValueAsString(originalTool);
        SyntheticContractModelBridge.run(start, request -> {
            requests.add(wire(request));
            return response();
        }, f.host::call);
        assertFalse(requests.isEmpty());
        Map<String,Object> function = submitTool(requests.get(0));
        int addedChars = JSON.writeValueAsString(function).length() - originalWire.length();
        assertTrue(addedChars < 4096, "One compact example must not inflate every tool context");
        assertEquals(originalWire, JSON.writeValueAsString(originalTool));
        System.out.println("GUIDANCE_SCHEMA_ADDED_CHARS=" + addedChars);
        Map<String,Object> schema = object(function.get("parameters"));
        Map<String,Object> props = object(schema.get("properties"));
        assertTrue(ProofVerifier.strings(schema.get("required")).contains("fact"));
        assertTrue(ProofVerifier.strings(object(props.get("submissionMode")).get("enum")).contains("CONTRACT_FIELDS"));
        Map<String,Object> atom = object(object(props.get("contractFields")).get("items"));
        Map<String,Object> atomProps = object(atom.get("properties"));
        assertTrue(ProofVerifier.strings(object(atomProps.get("field")).get("enum")).containsAll(Arrays.asList("amount", "signedDate", "contractNumber")));
        assertTrue(String.valueOf(object(atomProps.get("sourceIds")).get("description")).contains("本字段"));
        assertFalse(RuleEvaluator.maps(schema.get("examples")).isEmpty());
        String system = String.valueOf(RuleEvaluator.maps(requests.get(0).get("messages")).get(0).get("content"));
        assertTrue(system.contains("CONTRACT_FIELDS"));
        assertTrue(system.contains("封面"));
        assertTrue(system.contains("submissionExample"));
    }

    @Test void modelUsesTheOfferedExampleWithItsOwnReadFieldSourcesAndHostAcceptsThoseFields() throws Exception {
        Fixture f = splitFixture();
        List<Map<String,Object>> submitted = new ArrayList<>();
        List<Map<String,Object>> receipts = new ArrayList<>();
        runExample(f, false, submitted, receipts);
        assertEquals(1, submitted.size());
        assertEquals("CONTRACT_FIELDS", submitted.get(0).get("submissionMode"));
        Map<String,Object> receipt = receipts.get(0);
        List<Map<String,Object>> fields = RuleEvaluator.maps(receipt.get("fieldResults"));
        assertEquals(2, fields.size(), receipt.toString());
        for (Map<String,Object> field : fields)
            assertFalse(ProofVerifier.strings(field.get("acceptedFactIds")).isEmpty(), field.toString());
        assertEquals(false, receipt.get("businessProof"));
        assertTrue(f.host.facts.values().stream().anyMatch(fact -> "amount".equals(fact.field()) && "200".equals(fact.attributes().get("amount"))));
        assertTrue(f.host.facts.values().stream().anyMatch(fact -> "signedDate".equals(fact.field()) && "2023-01-01".equals(fact.attributes().get("signedDate"))));
        for (Map<String,Object> field : RuleEvaluator.maps(submitted.get(0).get("contractFields")))
            assertTrue(ProofVerifier.strings(field.get("sourceIds")).contains(source(f.corpus, 5).id()));
    }

    @Test void anIdentityPageCannotCarryBodyAmountOrSigningDateEvenWithTheExample() throws Exception {
        Fixture f = splitFixture();
        List<Map<String,Object>> submitted = new ArrayList<>();
        List<Map<String,Object>> receipts = new ArrayList<>();
        runExample(f, true, submitted, receipts);
        assertEquals(1, receipts.size());
        for (Map<String,Object> field : RuleEvaluator.maps(receipts.get(0).get("fieldResults"))) {
            assertTrue(ProofVerifier.strings(field.get("acceptedFactIds")).isEmpty(), field.toString());
            assertFalse(RuleEvaluator.maps(field.get("rejections")).isEmpty());
        }
        assertFalse(f.host.facts.values().stream().anyMatch(fact -> "amount".equals(fact.field()) || "signedDate".equals(fact.field())));
        assertEquals(false, receipts.get(0).get("businessProof"));
    }

    @Test void ordinarySingleFieldModelSubmissionRemainsAcceptedWithoutAtomicMode() throws Exception {
        Fixture f = splitFixture();
        AtomicInteger turn = new AtomicInteger();
        List<Map<String,Object>> receipts = new ArrayList<>();
        SyntheticContractModelBridge.run(f.host.start(), request -> {
            int index = turn.incrementAndGet();
            if (index == 1) return call("read", "read_section", linked("targetObligationId", f.target, "sourceId", source(f.corpus, 4).id()));
            if (index == 2) return call("submit", "submit_candidate_fact", linked("targetObligationId", f.target,
                "fact", f.candidate("contractNumber", NUMBER)));
            return response();
        }, (name, arguments) -> {
            Map<String,Object> result = f.host.call(name, arguments);
            if ("submit_candidate_fact".equals(name)) receipts.add(result);
            return result;
        });
        assertEquals(1, receipts.size());
        assertFalse(ProofVerifier.strings(receipts.get(0).get("acceptedFactIds")).isEmpty(), receipts.toString());
    }

    @Test void legacyBooleanPropertySchemasStillReachTheRunnerWithoutGuidanceCrashing() throws Exception {
        for (String property : Arrays.asList("fact", "field", "rawValue", "sourceIds", "attributes")) {
            Map<String,Object> atomProperties = linked("field", linked("type", "string"),
                "rawValue", linked("type", "string"), "sourceIds", linked("type", "array"),
                "attributes", linked("type", "object"));
            Map<String,Object> properties = linked("fact", linked("type", "object"),
                "submissionMode", linked("type", "string", "enum", Collections.singletonList("CONTRACT_FIELDS")),
                "contractFields", linked("type", "array", "items", linked("type", "object", "properties", atomProperties)));
            if ("fact".equals(property)) properties.put("fact", true);
            else atomProperties.put(property, true);
            Map<String,Object> tool = linked("name", "submit_candidate_fact", "description", "旧版合法布尔子schema",
                "parameters", linked("type", "object", "properties", properties));
            String before = JSON.writeValueAsString(tool);
            AtomicInteger calls = new AtomicInteger();
            Map<String,Object> result = SyntheticContractModelBridge.run(linked("type", "start",
                "protocol", "bidcheck-agent-1", "requestId", "synthetic-legacy-schema", "context", linked(),
                "tools", Collections.singletonList(tool)), request -> {
                    calls.incrementAndGet();
                    assertEquals(JSON.readTree(before), JSON.valueToTree(submitTool(request)));
                    return response();
                }, (name, args) -> { throw new AssertionError("No model tool call requested"); });
            assertTrue(calls.get() > 0, property + ": " + result);
            assertNotEquals("FAILED", result.get("status"), result.toString());
            assertEquals(before, JSON.writeValueAsString(tool));
        }
    }

    private void runExample(Fixture f, boolean identityOnly, List<Map<String,Object>> submitted,
            List<Map<String,Object>> receipts) throws Exception {
        AtomicInteger turn = new AtomicInteger();
        List<Map<String,Object>> examples = new ArrayList<>();
        Map<String,Object> outcome = SyntheticContractModelBridge.run(f.host.start(), request -> {
            int index = turn.incrementAndGet();
            if (index == 1) {
                examples.addAll(RuleEvaluator.maps(object(submitTool(wire(request)).get("parameters")).get("examples")));
                assertFalse(examples.isEmpty(), "Actual provider tool must include a usable atomic submission example");
                return call("read-identity", "read_section", linked("targetObligationId", f.target, "sourceId", source(f.corpus, 4).id()));
            }
            if (index == 2) return call("read-body", "read_section", linked("targetObligationId", f.target, "sourceId", source(f.corpus, 5).id()));
            if (index == 3) {
                Map<String,Object> args = wire(examples.get(0));
                args.put("targetObligationId", f.target);
                Map<String,Object> anchor = object(args.get("fact"));
                anchor.put("rawValue", NUMBER);
                anchor.put("sourceIds", Collections.singletonList(source(f.corpus, 4).id()));
                anchor.put("attributes", linked("contractNumber", NUMBER));
                for (Object rawField : (List<?>) args.get("contractFields")) {
                    Map<String,Object> field = object(rawField);
                    field.put("sourceIds", identityOnly ? Collections.singletonList(source(f.corpus, 4).id())
                        : Arrays.asList(source(f.corpus, 4).id(), source(f.corpus, 5).id()));
                    object(field.get("attributes")).put("contractNumber", NUMBER);
                }
                return call("submit-fields", "submit_candidate_fact", args);
            }
            return response();
        }, (name, arguments) -> {
            Map<String,Object> result = f.host.call(name, arguments);
            if ("submit_candidate_fact".equals(name)) { submitted.add(arguments); receipts.add(result); }
            return result;
        });
        assertFalse("FAILED".equals(outcome.get("status")), outcome.toString());
        assertEquals(1, receipts.size(), outcome.toString());
    }

    private Fixture splitFixture() throws Exception {
        return new Fixture(identityBody(), "合同编号：" + NUMBER + "\n合同金额：200万元\n合同签订日期：2023年1月1日\n乙方：" + COMPANY, false);
    }

    private static Map<String,Object> submitTool(Map<String,Object> request) {
        return RuleEvaluator.maps(request.get("tools")).stream().map(tool -> object(tool.get("function")))
            .filter(tool -> "submit_candidate_fact".equals(tool.get("name"))).findFirst().get();
    }
    private static Map<String,Object> wire(Map<String,Object> value) throws Exception {
        return JSON.readValue(JSON.writeValueAsBytes(value), Map.class);
    }
    private static Map<String,Object> call(String id, String name, Map<String,Object> args) throws Exception {
        return linked("choices", Collections.singletonList(linked("message", linked("role", "assistant",
            "content", "", "tool_calls", Collections.singletonList(linked("id", id, "type", "function",
                "function", linked("name", name, "arguments", JSON.writeValueAsString(args))))))));
    }
    private static Map<String,Object> response() {
        return linked("choices", Collections.singletonList(linked("message", linked("role", "assistant", "content", "", "tool_calls", Collections.emptyList()))));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        return value instanceof Map<?,?> ? (Map<String,Object>) value : Collections.emptyMap();
    }
}
