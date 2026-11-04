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
class ContractIdentityLinkTest {
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
            corpus = corpus("本项目投标函\n项目名称：合成中心装修工程\n本项目施工单位：" + COMPANY
                + "\n投标人：" + COMPANY,
                "项目名称：合成中心装修工程\n" + list("1 " + NAME + " 签订时间：2023年1月1日 金额：200万元"), "①" + NAME, body, continuation);
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
                read(4);
                read(5);
            }
        }

        void read(int page) {
            assertEquals("COMPLETED", host.call("read_section", linked(
                "targetObligationId", target, "sourceId", source(corpus, page).id())).get("status"));
        }

        Map<String,Object> candidate(String field, String rawValue) {
            return linked("kind", "CONTRACT", "field", field, "rawValue", rawValue,
                "sourceIds", Collections.singletonList(source(corpus, 4).id()));
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

    @Test void independentEmptyAttributeSubmissionsStayInOneRowWithoutScoringIdentity() throws Exception {
        for (boolean reverse : Arrays.asList(false, true)) {
            Fixture fixture = fixture();
            Fact first = submitIdentity(fixture, reverse ? "contractNumber" : "contractName", reverse ? NUMBER : NAME);
            Fact second = submitIdentity(fixture, reverse ? "contractName" : "contractNumber", reverse ? NAME : NUMBER);
            Decision decision = fixture.host.evaluator.decisions.get(fixture.parent.id());
            Map<String,Object> check = joinedCheck(decision, first, second);
            assertNotNull(check.get("declaredRow"));
            assertEquals(false, check.get("counted"));
            assertEquals(0, decision.proof().get("deduplicatedContracts"));
            assertEquals("UNDETERMINED", decision.status());
            assertEquals(2, fixture.host.facts.values().stream().filter(f -> f.kind().equals("CONTRACT")).count());
        }
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

    @Test void splitIdentitiesSurviveSharedStorageSnapshotAndLocalScore() throws Exception {
        Fixture fixture = fixture();
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
        com.fasterxml.jackson.databind.JsonNode check = restoredCheck(roundTrip(fixture), name, number);
        assertTrue(check.has("declaredRow"));
        assertFalse(check.path("counted").asBoolean());
        assertTrue(check.path("missing").size() > 0);
    }

    private static Fact submitField(Fixture fixture, String field, String raw, Map<String,Object> attributes) {
        Map<String,Object> candidate = fixture.candidate(field, raw);
        candidate.put("attributes", attributes);
        Map<String,Object> receipt = fixture.submit(candidate);
        List<String> accepted = ProofVerifier.strings(receipt.get("acceptedFactIds"));
        assertEquals(1, accepted.size(), receipt.toString());
        return fixture.host.facts.get(accepted.get(0));
    }

    @Test void separateGroundedBusinessFieldsCanCountOnlyAfterIdentityConnection() throws Exception {
        Fixture fixture = new Fixture(completeBody(), "本合同其他说明。");
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
        assertEquals(0, fixture.host.evaluator.decisions.get(fixture.parent.id()).proof().get("deduplicatedContracts"));
        submitField(fixture, "party", COMPANY, linked("contractNumber", NUMBER, "party", COMPANY));
        submitField(fixture, "signedDate", "2023年1月1日", linked("contractNumber", NUMBER, "signedDate", "2023-01-01"));
        submitField(fixture, "amount", "200万元", linked("contractNumber", NUMBER,
            "amount", "200", "unit", "万元", "amountBasis", "合同金额"));
        Decision decision = fixture.host.evaluator.decisions.get(fixture.parent.id());
        Map<String,Object> check = joinedCheck(decision, name, number);
        assertEquals(true, check.get("counted"), decision.map().toString());
        assertEquals(1, decision.proof().get("deduplicatedContracts"));
        assertEquals(0, new java.math.BigDecimal("2").compareTo((java.math.BigDecimal)decision.proof().get("scoreLower")));
        assertTrue(restoredCheck(roundTrip(fixture), name, number).path("counted").asBoolean());
    }
    private static void assertNotJoined(Decision decision, Fact name, Fact number) {
        for (Map<String,Object> check : RuleEvaluator.maps(decision.proof().get("contractChecks")))
            assertFalse(ProofVerifier.strings(check.get("factIds")).containsAll(
                Arrays.asList(name.id(), number.id())), decision.map().toString());
        assertEquals(0, decision.proof().get("deduplicatedContracts"));
    }

    private static Fact identityFact(String id, String field, String value, Source source) {
        return new Fact(id, "CONTRACT", "", field, value, value, "", "", "HISTORICAL_PROJECT",
            Collections.singletonList(source.id()), linked(field, value));
    }

    private static Decision evaluatePair(EvidenceCorpus corpus, Rule rule, Fact name, Fact number) {
        Fact company = new Fact("company", "RELATION", "施工单位", "名称", COMPANY, COMPANY,
            "", "", "CURRENT_PROJECT", Collections.singletonList(source(corpus, 1).id()), linked("object", COMPANY));
        return SourcePerformanceScore.evaluate(rule, new RuleEvaluator(
            Arrays.asList(company, name, number), corpus, dates()));
    }

    @Test void samePageOrAdjacentPageWithoutCommonIdentityAnchorCannotConnect() throws Exception {
        Fixture fixture = new Fixture("合同名称：" + NAME, "合同编号：" + NUMBER);
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Map<String,Object> candidate = fixture.candidate("contractNumber", NUMBER);
        candidate.put("sourceIds", Collections.singletonList(source(fixture.corpus, 5).id()));
        candidate.put("attributes", Collections.emptyMap());
        Map<String,Object> receipt = fixture.submit(candidate);
        List<String> accepted = ProofVerifier.strings(receipt.get("acceptedFactIds"));
        assertEquals(1, accepted.size(), receipt.toString());
        Fact number = fixture.host.facts.get(accepted.get(0));
        assertNotJoined(fixture.host.evaluator.decisions.get(fixture.parent.id()), name, number);
    }

    @Test void secondPrintedContractOrNumberInThePacketPreventsAutomaticConnection() throws Exception {
        for (String contradiction : Arrays.asList(
                "另一个车站装饰装修工程施工合同",
                "| 合同编号 | SYN-2025-002 |",
                "合同名称：另一标段装饰装修工程施工合同\n合同编号：SYN-2025-002")) {
            Fixture fixture = new Fixture(identityBody(), contradiction);
            Fact name = submitIdentity(fixture, "contractName", NAME);
            Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
            assertNotJoined(fixture.host.evaluator.decisions.get(fixture.parent.id()), name, number);
        }
    }

    @Test void duplicateDeclarationsDoNotSupplyAnExclusiveRecordBinding() {
        EvidenceCorpus corpus = corpus("本项目投标文件\n本项目施工单位：" + COMPANY,
            list("1 " + NAME + " 签订时间：2023年1月1日 金额：200万元\n"
                + "2 " + NAME + " 签订时间：2023年1月1日 金额：200万元"),
            "①" + NAME, identityBody(), "其他合同条款。");
        Rule parent = withRows(scoringRule(), corpus);
        assertEquals(2, DeclaredPerformanceRows.read(parent, "", corpus, Collections.emptyList()).size());
        Fact name = identityFact("name", "contractName", NAME, source(corpus, 4));
        Fact number = identityFact("number", "contractNumber", NUMBER, source(corpus, 4));
        assertNotJoined(evaluatePair(corpus, parent, name, number), name, number);
    }

    @Test void oldVersionOrAnotherDocumentCannotSupplyTheCommonAnchor() throws Exception {
        Fixture fixture = fixture();
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
        Document old = fixture.corpus.documents.get(0);
        for (boolean anotherDocument : Arrays.asList(false, true)) {
            Document replacement = new Document(anotherDocument ? "other-bid" : old.id(),
                old.projectId(), old.role(), old.name(), anotherDocument ? old.version() : 2,
                anotherDocument ? old.sha256() : "new-hash", old.createdAt(), old.pages(), old.warnings());
            EvidenceCorpus changed = new EvidenceCorpus(Collections.singletonList(replacement));
            Decision decision = evaluatePair(changed, withRows(scoringRule(), changed), name, number);
            assertNotJoined(decision, name, number);
        }
    }

    @Test void purchaseOrdersAndTemplateIdentityCannotEstablishThisConnection() {
        for (String qualifier : Arrays.asList("订单编号：PO12345", "填写示例")) {
            EvidenceCorpus corpus = corpus("本项目投标文件\n本项目施工单位：" + COMPANY,
                list("1 " + NAME + " 签订时间：2023年1月1日 金额：200万元"),
                "①" + NAME, identityBody() + "\n" + qualifier, "其他条款。");
            Fact name = identityFact("name", "contractName", NAME, source(corpus, 4));
            Fact number = identityFact("number", "contractNumber", NUMBER, source(corpus, 4));
            assertNotJoined(evaluatePair(corpus, withRows(scoringRule(), corpus), name, number), name, number);
        }
    }

    @Test void aNewContradictorySourceReopensThePreviouslyLinkedRecord() throws Exception {
        Fixture fixture = fixture();
        Fact name = submitIdentity(fixture, "contractName", NAME);
        Fact number = submitIdentity(fixture, "contractNumber", NUMBER);
        joinedCheck(fixture.host.evaluator.decisions.get(fixture.parent.id()), name, number);
        Document old = fixture.corpus.documents.get(0);
        List<Page> pages = new ArrayList<>(old.pages());
        pages.set(4, new Page(5, "合同编号：SYN-2025-002", "PAGE", "TEXT", ""));
        EvidenceCorpus changed = new EvidenceCorpus(Collections.singletonList(new Document(old.id(),
            old.projectId(), old.role(), old.name(), old.version(), old.sha256(), old.createdAt(), pages, old.warnings())));
        assertNotJoined(evaluatePair(changed, withRows(scoringRule(), changed), name, number), name, number);
    }
    @Test void proseMentionCannotReplaceAPrintedContractTitleAnchor() {
        EvidenceCorpus corpus = corpus("本项目投标文件\n本项目施工单位：" + COMPANY,
            list("1 " + NAME + " 签订时间：2023年1月1日 金额：200万元"),
            "①" + NAME, "有关" + NAME + "另行讨论。\n合同编号：" + NUMBER, "其他条款。");
        Fact name = identityFact("name", "contractName", NAME, source(corpus, 4));
        Fact number = identityFact("number", "contractNumber", NUMBER, source(corpus, 4));
        assertNotJoined(evaluatePair(corpus, withRows(scoringRule(), corpus), name, number), name, number);
    }
    @Test void twoIdentitiesInsideOneSourceCannotBeJoinedBySharingThatSource() {
        for (String contradiction : Arrays.asList(
                "\n合同编号：SYN-2025-002",
                "\n另一标段装饰装修工程施工合同\n合同编号：SYN-2025-002")) {
            EvidenceCorpus corpus = corpus("本项目投标文件\n本项目施工单位：" + COMPANY,
                list("1 " + NAME + " 签订时间：2023年1月1日 金额：200万元"),
                "①" + NAME, identityBody() + contradiction, "其他条款。");
            Fact name = identityFact("name", "contractName", NAME, source(corpus, 4));
            Fact number = identityFact("number", "contractNumber", NUMBER, source(corpus, 4));
            assertNotJoined(evaluatePair(corpus, withRows(scoringRule(), corpus), name, number), name, number);
        }
    }
}
