package cn.bidcheck.verification.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Invented document pages only. Raw production receipts are not unit-test fixtures. */
class MixedContractSourceProjectionTest {
    static final String NAME = "甲中心装饰装修工程施工合同";
    static final String NUMBER = "SYN-2025-001";

    static final String COMPANY = "合成建设有限公司";

    static Source source(EvidenceCorpus corpus, int page) {
        return corpus.all.stream().filter(item -> item.evidence().page() == page).findFirst().get();
    }

    static EvidenceCorpus corpus(String... texts) {
        List<Page> pages = new ArrayList<>();
        for (int index = 0; index < texts.length; index++)
            pages.add(new Page(index + 1, texts[index], "PAGE", "TEXT", ""));
        return new EvidenceCorpus(Collections.singletonList(new Document("synthetic-bid", "p", "BID",
            "synthetic.pdf", 1, "synthetic-hash", "", pages, Collections.emptyList())));
    }

    static String list(String rows) {
        return "投标文件\n业绩清单\n附表6-4 近年完成的类似项目情况表\n序号 合同名称 签订时间 金额（万元）\n" + rows;
    }

    static Rule scoringRule() {
        String requirement = "施工业绩情况（商务）自2020年1月1日至今金额在100万元含以上装饰装修工程施工项目合同或EPC合同担任施工部分。每有1个得2分，最高得6分。"
            + "证明材料：填写附表6-4。需提交合同关键页扫描件，关键页包括合同名称、合同金额及签字盖章页等；"
            + "如提交框架合同，按框架合同金额。如框架合同无签约金额，需提交汇总金额不低于上述限额的项下订单，并列表汇总（格式自拟），"
            + "如提供EPC项目合同，则以施工部分金额为准。时间以合同签订日期为准。（如为联合体投标，则须为施工单位业绩）。";
        Rule rule = SourcePerformanceScore.compile(requirement);
        assertNotNull(rule);
        return rule;
    }

    static Rule withRows(Rule parent, EvidenceCorpus corpus) {
        Map<String,Object> parameters = new LinkedHashMap<>(parent.parameters());
        parameters.put("declaredPerformanceReadRows", DeclaredPerformanceRows.read(parent, "", corpus, Collections.emptyList()));
        return new Rule(parent.id(), parent.description(), parent.predicate(), parent.subject(), parent.field(),
            parent.operator(), parent.expected(), parent.unit(), parameters, parent.sourceQuote(), parent.logic(), parent.children());
    }

    static Map<String,Object> dates() {
        return linked("evaluationStage", "BID_SUBMISSION", "referenceDate", "2025-08-05", "referenceDateSourceVerified", true,
            "referenceDateEvidence", linked("documentId", "synthetic-tender", "quote", "投标截止时间：2025年8月5日",
                "confirmation", "SOURCE_UNAMBIGUOUS", "confirmed", true));
    }

    static Checkpoint checkpoint() {
        return new Checkpoint("synthetic-contract", "合同业绩", "响应", "MEDIUM", "SEMANTIC_RESPONSE",
            "", "", "合成合同逐项核验", "", true, true, true, null);
    }

    static String completeBody() {
        return identityBody() + "\n合同金额：200万元\n合同签订日期：2023年1月1日\n甲方（盖章）：建设单位\n乙方（盖章）：" + COMPANY;
    }
    static final class Fixture {
        final EvidenceCorpus corpus;
        final EvidenceInvestigation.Host host;
        final Rule parent;
        final String target;

        Fixture(String body, String continuation) throws Exception {
            this(body, continuation, true);
        }

        Fixture(String body, String continuation, boolean readBodies) throws Exception {
            this(body, continuation, readBodies, identityBody());
        }
        Fixture(String body, String continuation, boolean readBodies, String external) throws Exception {
            this(body, continuation, readBodies, external, null);
        }
        Fixture(String body, String continuation, boolean readBodies, String external, Document additional) throws Exception {
            EvidenceCorpus primary = corpus("本项目投标函\n项目名称：合成中心装修工程\n本项目施工单位：" + COMPANY
                + "\n投标人：" + COMPANY,
                external, "项目名称：合成中心装修工程\n" + list("1 " + NAME + " 签订时间：2023年1月1日 金额：200万元"), "①" + NAME, body, continuation);
            List<Document> documents = new ArrayList<>(primary.documents);
            if (additional != null) documents.add(additional);
            corpus = new EvidenceCorpus(documents);
            parent = withRows(scoringRule(), corpus);
            List<Rule> targets = SourcePerformanceScore.evidenceRules(parent);
            target = targets.stream().filter(rule -> "CONTRACT".equals(rule.predicate()))
                .findFirst().get().id();
            Map<String,Object> context = dates();
            host = new EvidenceInvestigation.Host(checkpoint(),
                new Plan("ALL", Collections.singletonList(parent), "SYNTHETIC", Collections.emptyList()),
                targets, corpus, new LinkedHashMap<>(), context,
                new SharedFactReuse(corpus, context, "synthetic", "synthetic"),
                new RunSession(ModelGateway.disabled(), context, "synthetic"), null,
                new ArrayList<>(), Collections.emptySet());
            host.start();
            for (Source item : corpus.all) host.disclosed.put(item.id(), item);
            assertEquals("COMPLETED", host.call("read_section", linked("targetObligationId",
                parent.id() + "-READ-SUBJECT", "sourceId", source(corpus, 1).id())).get("status"));
            Map<String,Object> relation = host.call("submit_candidate_fact", linked("targetObligationId",
                parent.id() + "-READ-SUBJECT", "fact", linked("kind", "RELATION", "subject", "施工单位",
                    "field", "：", "rawValue", COMPANY, "scope", "CURRENT_PROJECT",
                    "sourceIds", Collections.singletonList(source(corpus, 1).id()),
                    "attributes", linked("object", COMPANY))));
            assertTrue(RuleEvaluator.maps(relation.get("rejections")).isEmpty(), relation.toString());
            if (readBodies) {
                read(5);
                read(6);
            }
        }

        void read(int page) {
            assertEquals("COMPLETED", host.call("read_section", linked(
                "targetObligationId", target, "sourceId", source(corpus, page).id())).get("status"));
        }

        Map<String,Object> candidate(String field, String rawValue) {
            return linked("kind", "CONTRACT", "field", field, "rawValue", rawValue,
                "sourceIds", Collections.singletonList(source(corpus, 5).id()));
        }

        Map<String,Object> submit(Map<String,Object> candidate) {
            return host.call("submit_candidate_fact", linked(
                "targetObligationId", target, "fact", candidate));
        }
    }

    static String identityBody() {
        return "合同名称：" + NAME + "\n合同编号：" + NUMBER
            + "\n乙方：" + COMPANY + "\n本页仅载明合同身份，其他条款见另页。";
    }

    static Fixture fixture() throws Exception {
        return new Fixture(identityBody(), "工程范围与履行地点待核对，未载明签订日期和合同金额。");
    }

    private static Fact submitIdentity(Fixture fixture, String field, String value) {
        Map<String,Object> candidate = fixture.candidate(field, value);
        candidate.put("attributes", Collections.emptyMap());
        Map<String,Object> receipt = fixture.submit(candidate);
        List<String> ids = ProofVerifier.strings(receipt.get("acceptedFactIds"));
        assertEquals(1, ids.size(), receipt.toString());
        Fact accepted = fixture.host.facts.get(ids.get(0));
        assertEquals(Collections.singletonMap(field, value), accepted.attributes());
        return accepted;
    }

    private static Map<String,Object> joinedCheck(Decision decision, Fact name, Fact number) {
        return RuleEvaluator.maps(decision.proof().get("contractChecks")).stream()
            .filter(check -> ProofVerifier.strings(check.get("factIds")).containsAll(
                Arrays.asList(name.id(), number.id())))
            .findFirst().orElseThrow(() -> new AssertionError(
                "Complementary source-bound identities must be one contract check: " + decision.map()));
    }

    private static Map<String,Object> roundTrip(Fixture fixture) {
        List<Fact> contracts = new ArrayList<>();
        fixture.host.facts.values().stream().filter(f -> f.kind().equals("CONTRACT")).forEach(contracts::add);
        List<Map<String,Object>> batches = new ArrayList<>();
        Map<String,Object> publishContext = linked("sharedFactPublisher",
            (java.util.function.Consumer<Map<String,Object>>) batches::add);
        List<Rule> rules = SourcePerformanceScore.evidenceRules(fixture.parent);
        new SharedFactReuse(fixture.corpus, publishContext, "synthetic", "synthetic")
            .publish(contracts, fixture.corpus.all, rules, Collections.emptyList(), false, "MODEL");
        List<Map<String,Object>> entries = new ArrayList<>();
        for (Map<String,Object> batch : batches)
            for (Map<String,Object> fact : RuleEvaluator.maps(batch.get("facts")))
                entries.add(linked("fact", fact, "sources", batch.get("sources"),
                    "engineVersion", batch.get("engineVersion"), "operationIdentity", batch.get("operationIdentity")));
        Map<String,Object> lookupContext = linked("sharedFactLookup",
            (java.util.function.Function<Map<String,Object>,Object>) ignored -> entries);
        List<Fact> reused = new SharedFactReuse(fixture.corpus, lookupContext, "synthetic", "synthetic")
            .lookup(rules, fixture.corpus.all, Collections.emptyList());
        assertEquals(contracts.size(), reused.size());
        for (Fact original : contracts) {
            Fact restored = reused.stream().filter(f -> f.id().equals(original.id())).findFirst().get();
            assertEquals(original.attributes(), restored.attributes());
            assertEquals(original.sourceIds(), restored.sourceIds());
            assertEquals(original.rawValue(), restored.rawValue());
        }
        List<Fact> retained = new ArrayList<>(reused);
        fixture.host.facts.values().stream().filter(f -> !f.kind().equals("CONTRACT")).forEach(retained::add);
        ModelGateway noModel = ModelGateway.disabled();
        Map<String,Object> snapshot = linked("type", RecoveryReplay.FACT_SNAPSHOT, "status", "COMPLETED",
            "schemaVersion", VERSION, "modelIdentity", noModel.identity(),
            "output", RecoveryReplay.snapshot(fixture.corpus, retained));
        Map<String,Object> recovery = dates();
        recovery.putAll(linked("recoveryMode", "RECOMPUTE_ONLY", "recoveryModelAllowed", false,
            "recoveryTargetObligationIds", Collections.singletonList(fixture.target),
            "previousAtomicCheck", linked("plan", new Plan("ALL", Collections.singletonList(fixture.parent),
                "SYNTHETIC", Collections.emptyList())), "priorSteps", Collections.singletonList(snapshot)));
        Checkpoint checkpoint = new Checkpoint("synthetic-roundtrip", "合同业绩", "商务", "HIGH",
            "SEMANTIC_RESPONSE", "", "", fixture.parent.sourceQuote(), "", true, true, true, null);
        Document tender = new Document("synthetic-tender", "p", "TENDER", "synthetic-tender.pdf", 1,
            "tender-hash", "", Collections.singletonList(new Page(1, "投标截止时间：2025年8月5日",
                "PAGE", "TEXT", "")), Collections.emptyList());
        recovery.put("referenceDateEvidence", linked("documentId", "synthetic-tender", "page", 1,
            "locationType", "PAGE", "quote", "投标截止时间：2025年8月5日",
            "confirmation", "SOURCE_UNAMBIGUOUS", "confirmed", true));
        Map<String,Object> result = new VerificationEngine(noModel, null).evaluate(
            checkpoint, fixture.corpus.documents, tender, recovery);
        assertEquals(0, ((Map<?,?>)result.get("metrics")).get("modelCalls"));
        return result;
    }

    private static com.fasterxml.jackson.databind.JsonNode restoredCheck(
            Map<String,Object> result, Fact name, Fact number) {
        for (com.fasterxml.jackson.databind.JsonNode checks : new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(result).findValues("contractChecks"))
            for (com.fasterxml.jackson.databind.JsonNode check : checks) {
                List<String> ids = new ArrayList<>();
                check.path("factIds").forEach(id -> ids.add(id.asText()));
                if (ids.containsAll(Arrays.asList(name.id(), number.id()))) return check;
            }
        throw new AssertionError("Restored local score lost the single linked record");
    }

    private static Fact submitField(Fixture fixture, String field, String raw, Map<String,Object> attributes) {
        Map<String,Object> candidate = fixture.candidate(field, raw);
        candidate.put("attributes", attributes);
        Map<String,Object> receipt = fixture.submit(candidate);
        List<String> accepted = ProofVerifier.strings(receipt.get("acceptedFactIds"));
        assertEquals(1, accepted.size(), receipt.toString());
        return fixture.host.facts.get(accepted.get(0));
    }

    private static Fact external(Fixture fixture, String field, String raw, Map<String,Object> attributes) {
        return external(fixture, source(fixture.corpus, 2), field, raw, attributes);
    }
    private static Fact external(Fixture fixture, Source source, String field, String raw, Map<String,Object> attributes) {
        Map<String,Object> candidate = linked("kind", "CONTRACT", "field", field, "rawValue", raw,
            "value", raw, "scope", "HISTORICAL_PROJECT", "sourceIds",
            Collections.singletonList(source.id()), "attributes", attributes);
        ProofVerifier.Verified verified = new ProofVerifier().verify(
            new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(linked(
                "facts", Collections.singletonList(candidate))),
            Collections.singletonList(source));
        assertTrue(verified.rejected().isEmpty(), verified.rejected().toString());
        Fact fact = SharedFactReuse.canonical(verified.facts().get(0));
        fixture.host.facts.put(fact.id(), fact);
        fixture.host.recompute();
        return fact;
    }

    private static void businessFields(Fixture fixture) {
        submitField(fixture, "party", COMPANY, linked("contractNumber", NUMBER, "party", COMPANY));
        submitField(fixture, "signedDate", "2023年1月1日",
            linked("contractNumber", NUMBER, "signedDate", "2023-01-01"));
        submitField(fixture, "amount", "200万元", linked("contractNumber", NUMBER,
            "amount", "200", "unit", "万元", "amountBasis", "合同金额"));
    }

    private static Map<String,Object> rowCheck(Fixture fixture, Fact name, Fact number) {
        Decision decision = fixture.host.evaluator.decisions.get(fixture.parent.id());
        Map<String,Object> check = joinedCheck(decision, name, number);
        assertNotNull(check.get("declaredRow"), decision.map().toString());
        for (String id : ProofVerifier.strings(check.get("sourceIds")))
            assertTrue(DeclaredPerformanceRows.contains((Map<String,Object>)check.get("declaredRow"),
                fixture.corpus.byId.get(id)), "External sources must not enter row business evidence");
        return check;
    }

    @Test void externalDuplicateIdentityCannotEraseLocalRowThroughSharedSnapshot() throws Exception {
        Fixture fixture = fixture();
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
        Fact outsideName = external(fixture, "contractName", NAME, linked("contractName", NAME));
        Fact outsideNumber = external(fixture, "contractNumber", NUMBER, linked("contractNumber", NUMBER));
        Map<String,Object> check = rowCheck(fixture, name, number);
        assertEquals(false, check.get("counted"));
        assertFalse(ProofVerifier.strings(check.get("factIds")).contains(outsideName.id()));
        assertFalse(ProofVerifier.strings(check.get("factIds")).contains(outsideNumber.id()));
        assertTrue(fixture.host.evaluator.decisions.get(fixture.parent.id()).proof().toString().contains(outsideName.id()));
        assertTrue(fixture.host.evaluator.decisions.get(fixture.parent.id()).proof().toString().contains(outsideNumber.id()));
        com.fasterxml.jackson.databind.JsonNode restored = restoredCheck(roundTrip(fixture), name, number);
        assertTrue(restored.has("declaredRow"));
        assertFalse(restored.path("counted").asBoolean());
        assertEquals(0, fixture.host.evaluator.decisions.get(fixture.parent.id()).proof().get("deduplicatedContracts"));
    }

    @Test void localBusinessFieldsCountOnceDespiteExternalIdentityDuplicates() throws Exception {
        Fixture fixture = new Fixture(completeBody(), "本合同其他条款。");
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
        external(fixture, "contractName", NAME, linked("contractName", NAME));
        external(fixture, "contractNumber", NUMBER, linked("contractNumber", NUMBER));
        businessFields(fixture);
        assertEquals(true, rowCheck(fixture, name, number).get("counted"));
        assertEquals(1, fixture.host.evaluator.decisions.get(fixture.parent.id()).proof().get("deduplicatedContracts"));
        assertTrue(restoredCheck(roundTrip(fixture), name, number).path("counted").asBoolean());
    }

    @Test void externalAmountAndDateCannotFillTheLocalMissingFields() throws Exception {
        Fixture fixture = new Fixture(identityBody(), "本合同其他条款。", true, completeBody());
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
        external(fixture, "signedDate", "2023年1月1日",
            linked("contractNumber", NUMBER, "signedDate", "2023-01-01"));
        Fact outsideAmount = external(fixture, "amount", "200万元",
            linked("contractNumber", NUMBER, "amount", "200", "unit", "万元", "amountBasis", "合同金额"));
        Map<String,Object> check = rowCheck(fixture, name, number);
        assertEquals(false, check.get("counted"));
        assertFalse(ProofVerifier.strings(check.get("factIds")).contains(outsideAmount.id()));
        assertTrue(check.get("missing").toString().contains("签订日期"), check.toString());
        assertTrue(check.get("missing").toString().contains("金额"), check.toString());
        assertTrue(RuleEvaluator.maps(check.get("amountObservations")).isEmpty());
    }

    @Test void externalSameRecordSigningDateAndSameBasisAmountRemainCounterevidence() throws Exception {
        for (boolean wrongDate : Arrays.asList(false, true)) {
            String externalBody = completeBody().replace(wrongDate ? "2023年1月1日" : "200万元",
                wrongDate ? "2024年1月1日" : "300万元");
            Fixture fixture = new Fixture(completeBody(), "其他条款。", true, externalBody);
            Fact name = submitIdentity(fixture, "contractName", NAME);
            Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
            businessFields(fixture);
            Fact counter = wrongDate
                ? external(fixture, "signedDate", "2024年1月1日",
                    linked("contractNumber", NUMBER, "signedDate", "2024-01-01"))
                : external(fixture, "amount", "300万元", linked("contractNumber", NUMBER,
                    "amount", "300", "unit", "万元", "amountBasis", "合同金额"));
            Map<String,Object> check = rowCheck(fixture, name, number);
            assertEquals(false, check.get("counted"), check.toString());
            assertFalse(RuleEvaluator.maps(check.get("externalConflicts")).isEmpty(), check.toString());
            assertTrue(check.get("externalConflicts").toString().contains(counter.id()));
            assertEquals(0, fixture.host.evaluator.decisions.get(fixture.parent.id()).proof().get("deduplicatedContracts"));
            assertFalse(restoredCheck(roundTrip(fixture), name, number).path("counted").asBoolean());
        }
    }

    @Test void externalDesignFeeAndDifferentTaxBasisNeitherSupplyNorConflictWithContractPrice() throws Exception {
        for (String basis : Arrays.asList("设计费", "不含税合同金额", "不含增值税合同金额")) {
            Fixture fixture = new Fixture(completeBody(), "其他条款。", true,
                identityBody() + "\n" + basis + "：10万元");
            Fact name = submitIdentity(fixture, "contractName", NAME);
            Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
            businessFields(fixture);
            external(fixture, "amount", "10万元", linked("contractNumber", NUMBER,
                "amount", "10", "unit", "万元", "amountBasis", basis));
            assertEquals(true, rowCheck(fixture, name, number).get("counted"));
            assertEquals(1, fixture.host.evaluator.decisions.get(fixture.parent.id()).proof().get("deduplicatedContracts"));
        }
    }

    @Test void mixedSourceFactIsNotTruncatedIntoAnAllowedLocalAmount() throws Exception {
        Fixture fixture = new Fixture(identityBody(), "其他条款。", true, completeBody());
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
        Fact mixed = new Fact("mixed-price", "CONTRACT", "", "amount", "200万元", "200万元", "", "",
            "HISTORICAL_PROJECT", Arrays.asList(source(fixture.corpus, 2).id(), source(fixture.corpus, 5).id()),
            linked("contractNumber", NUMBER, "amount", "200", "unit", "万元", "amountBasis", "合同金额"));
        assertNull(new ProofVerifier().issue(mixed, fixture.corpus.byId));
        fixture.host.facts.put(mixed.id(), mixed);
        fixture.host.recompute();
        Map<String,Object> check = rowCheck(fixture, name, number);
        assertFalse(ProofVerifier.strings(check.get("factIds")).contains(mixed.id()));
        assertTrue(RuleEvaluator.maps(check.get("amountObservations")).isEmpty());
        assertEquals(2, mixed.sourceIds().size());
        assertEquals(false, check.get("counted"));
    }
    @Test void externalNameOnlyFactWithTheSamePrintedNumberRetainsIdentityCounterevidence() throws Exception {
        String other = "乙车站装饰装修工程施工合同";
        Fixture fixture = new Fixture(completeBody(), "其他条款。", true, identityBody().replace(NAME, other));
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
        businessFields(fixture);
        Fact counter = external(fixture, "contractName", other, linked("contractName", other));
        Map<String,Object> check = rowCheck(fixture, name, number);
        assertEquals(false, check.get("counted"));
        assertTrue(check.get("externalConflicts").toString().contains(counter.id()), check.toString());
    }

    @Test void externalPurchaseOrderPriceCannotBecomeMotherContractCounterevidence() throws Exception {
        Fixture fixture = new Fixture(completeBody(), "其他条款。", true,
            identityBody() + "\n订单编号：PO12345\n合同金额：300万元");
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
        businessFields(fixture);
        external(fixture, "amount", "300万元",
            linked("contractNumber", NUMBER, "amount", "300", "unit", "万元", "amountBasis", "合同金额"));
        Map<String,Object> check = rowCheck(fixture, name, number);
        assertEquals(true, check.get("counted"), check.toString());
        assertTrue(RuleEvaluator.maps(check.get("externalConflicts")).isEmpty());
    }
    @Test void repeatedDeclaredOccurrencesOfOneContractNeverDoubleTheParentScore() {
        EvidenceCorpus corpus = corpus("本项目投标文件\n本项目施工单位：" + COMPANY,
            list("1 " + NAME + " 签订时间：2023年1月1日 金额：200万元\n"
                + "2 " + NAME + " 签订时间：2023年1月1日 金额：200万元"),
            "①" + NAME, completeBody(), "②" + NAME, completeBody());
        Rule compiled = scoringRule();
        assertNotNull(compiled);
        Rule parent = withRows(compiled, corpus);
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact("company", "RELATION", "施工单位", "名称", COMPANY, COMPANY, "", "",
            "CURRENT_PROJECT", Collections.singletonList(source(corpus, 1).id()), linked("object", COMPANY)));
        for (int page : Arrays.asList(4, 6)) {
            List<Map<String,Object>> values = Arrays.asList(
                linked("field", "contractName", "raw", NAME, "attributes", linked("contractName", NAME)),
                linked("field", "contractNumber", "raw", NUMBER, "attributes", linked("contractNumber", NUMBER)),
                linked("field", "party", "raw", COMPANY, "attributes", linked("contractNumber", NUMBER, "party", COMPANY)),
                linked("field", "signedDate", "raw", "2023年1月1日", "attributes",
                    linked("contractNumber", NUMBER, "signedDate", "2023-01-01")),
                linked("field", "amount", "raw", "200万元", "attributes",
                    linked("contractNumber", NUMBER, "amount", "200", "unit", "万元", "amountBasis", "合同金额")));
            for (Map<String,Object> value : values) {
                Fact fact = new Fact("p" + page + "-" + value.get("field"), "CONTRACT", "",
                    str(value, "field"), str(value, "raw"), str(value, "raw"), "", "", "HISTORICAL_PROJECT",
                    Collections.singletonList(source(corpus, page).id()), (Map<String,Object>)value.get("attributes"));
                assertNull(new ProofVerifier().issue(fact, corpus.byId));
                facts.add(fact);
            }
        }
        Decision decision = SourcePerformanceScore.evaluate(parent, new RuleEvaluator(facts, corpus, dates()));
        assertTrue(((Number)decision.proof().get("deduplicatedContracts")).intValue() <= 1, decision.map().toString());
        assertTrue(((java.math.BigDecimal)decision.proof().get("scoreLower")).compareTo(new java.math.BigDecimal("2")) <= 0);
        assertEquals(2, RuleEvaluator.maps(decision.proof().get("contractChecks")).size());
        for (Map<String,Object> check : RuleEvaluator.maps(decision.proof().get("contractChecks")))
            assertNotNull(check.get("declaredRow"), decision.map().toString());
    }

    @Test void anotherNumberAndStaleExternalFactDoNotFabricateASameRecordConflict() throws Exception {
        Fixture fixture = new Fixture(completeBody(), "其他条款。", true,
            identityBody().replace(NUMBER, "OTHER-2025-002") + "\n合同金额：300万元");
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
        businessFields(fixture);
        external(fixture, "amount", "300万元", linked("contractNumber", "OTHER-2025-002",
            "amount", "300", "unit", "万元", "amountBasis", "合同金额"));
        fixture.host.facts.put("stale", new Fact("stale", "CONTRACT", "", "signedDate", "2024年1月1日",
            "2024年1月1日", "", "", "HISTORICAL_PROJECT", Collections.singletonList("old-version-source"),
            linked("contractNumber", NUMBER, "signedDate", "2024-01-01")));
        fixture.host.recompute();
        assertEquals(true, rowCheck(fixture, name, number).get("counted"));
        assertEquals(1, fixture.host.evaluator.decisions.get(fixture.parent.id()).proof().get("deduplicatedContracts"));
    }
    @Test void externalContractorIdentityIsNotDiscardedButBuyerRoleIsNotConfusedWithIt() throws Exception {
        String other = "合成另一建设有限公司";
        for (boolean buyer : Arrays.asList(false, true)) {
            String outside = buyer ? identityBody() + "\n甲方：" + other
                : identityBody().replace("乙方：" + COMPANY, "乙方：" + other);
            Fixture fixture = new Fixture(completeBody(), "其他条款。", true, outside);
            Fact name = submitIdentity(fixture, "contractName", NAME);
            Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
            businessFields(fixture);
            Fact counter = external(fixture, "party", other,
                linked("contractNumber", NUMBER, "party", other));
            Map<String,Object> check = rowCheck(fixture, name, number);
            assertEquals(buyer, check.get("counted"), check.toString());
            if (!buyer) assertTrue(check.get("externalConflicts").toString().contains(counter.id()));
        }
    }

    private static Document secondBid(String body) {
        return new Document("second-current-bid", "p", "BID", "second-synthetic.pdf", 2,
            "second-current-hash", "", Collections.singletonList(new Page(1, body, "PAGE", "TEXT", "")),
            Collections.emptyList());
    }

    private static Source secondSource(Fixture fixture) {
        return fixture.corpus.all.stream()
            .filter(source -> source.evidence().documentId().equals("second-current-bid"))
            .findFirst().get();
    }

    @Test void anotherCurrentBidDocumentRetainsSameNumberDateAndAmountCounterevidence() throws Exception {
        for (boolean date : Arrays.asList(false, true)) {
            String outside = completeBody().replace(date ? "2023年1月1日" : "200万元",
                date ? "2024年1月1日" : "300万元");
            Fixture fixture = new Fixture(completeBody(), "其他条款。", true, identityBody(), secondBid(outside));
            Fact name = submitIdentity(fixture, "contractName", NAME);
            Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
            businessFields(fixture);
            Fact counter = date
                ? external(fixture, secondSource(fixture), "signedDate", "2024年1月1日",
                    linked("contractNumber", NUMBER, "signedDate", "2024-01-01"))
                : external(fixture, secondSource(fixture), "amount", "300万元",
                    linked("contractNumber", NUMBER, "amount", "300", "unit", "万元", "amountBasis", "合同金额"));
            Map<String,Object> check = rowCheck(fixture, name, number);
            assertEquals(false, check.get("counted"), check.toString());
            assertTrue(check.get("externalConflicts").toString().contains(counter.id()), check.toString());
            assertFalse(ProofVerifier.strings(check.get("factIds")).contains(counter.id()));
            assertFalse(restoredCheck(roundTrip(fixture), name, number).path("counted").asBoolean());
        }
    }

    @Test void anotherCurrentBidDocumentCannotSupplyMissingLocalBusinessFields() throws Exception {
        Fixture fixture = new Fixture(identityBody(), "其他条款。", true, identityBody(), secondBid(completeBody()));
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
        external(fixture, secondSource(fixture), "signedDate", "2023年1月1日",
            linked("contractNumber", NUMBER, "signedDate", "2023-01-01"));
        external(fixture, secondSource(fixture), "amount", "200万元",
            linked("contractNumber", NUMBER, "amount", "200", "unit", "万元", "amountBasis", "合同金额"));
        Map<String,Object> check = rowCheck(fixture, name, number);
        assertEquals(false, check.get("counted"));
        assertTrue(check.get("missing").toString().contains("签订日期"));
        assertTrue(check.get("missing").toString().contains("金额"));
        assertTrue(RuleEvaluator.maps(check.get("amountObservations")).isEmpty());
        assertFalse(restoredCheck(roundTrip(fixture), name, number).path("counted").asBoolean());
    }

    @Test void externalDocumentUsesItsOwnCurrentVersionAndNeverCreatesAFalseConflict() throws Exception {
        for (String versionCase : Arrays.asList("current", "stale-hash", "stale-version")) {
            boolean stale = !versionCase.equals("current");
            String outside = stale ? completeBody().replace("200万元", "300万元") : completeBody();
            Fixture fixture = new Fixture(completeBody(), "其他条款。", true, identityBody(), secondBid(outside));
            Fact name = submitIdentity(fixture, "contractName", NAME);
            Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
            businessFields(fixture);
            Source source = secondSource(fixture);
            if (stale) {
                source = new Source(source.id(), source.evidence(), source.opening(), source.index(),
                    source.historical(), versionCase.equals("stale-hash") ? "stale-hash" : source.documentHash(),
                    versionCase.equals("stale-version") ? 1 : source.documentVersion(), source.scopeContext());
                fixture.corpus.byId.put(source.id(), source);
            }
            external(fixture, source, "amount", stale ? "300万元" : "200万元",
                linked("contractNumber", NUMBER, "amount", stale ? "300" : "200",
                    "unit", "万元", "amountBasis", "合同金额"));
            assertEquals(true, rowCheck(fixture, name, number).get("counted"));
        }
    }
}
