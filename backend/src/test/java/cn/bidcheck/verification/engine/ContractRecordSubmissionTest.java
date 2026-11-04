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
class ContractRecordSubmissionTest {
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

    private Map<String,Object> crossPageCandidate(Fixture fixture) {
        Map<String,Object> candidate = fixture.candidate("contractName", NAME);
        candidate.put("attributes", linked("contractName", NAME, "contractNumber", NUMBER,
            "party", COMPANY, "signedDate", "2023-01-01",
            "amount", "200", "amountBasis", "合同金额", "unit", "万元"));
        candidate.put("sourceIds", Arrays.asList(
            source(fixture.corpus, 4).id(),
            source(fixture.corpus, 5).id()));
        return candidate;
    }

    private String continuation() {
        return "合同金额：200万元\n合同签订日期：2023年1月1日\n乙方（盖章）：" + COMPANY;
    }

    @Test void actualReadContinuationInOneServerRowCanProveItsOwnFields() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(), continuation());
        Map<String,Object> receipt = fixture.submit(crossPageCandidate(fixture));
        assertEquals(1, ProofVerifier.strings(receipt.get("acceptedFactIds")).size(), receipt.toString());
        Fact fact = fixture.host.facts.values().stream().filter(item -> item.kind().equals("CONTRACT"))
            .findFirst().get();
        assertEquals(2, fact.sourceIds().size());
        assertEquals("200", fact.attributes().get("amount"));
    }

    @Test void unreadContinuationCannotBeBorrowedFromDisclosedNavigation() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(), continuation(), false);
        fixture.read(4);
        assertTrue(ProofVerifier.strings(fixture.submit(crossPageCandidate(fixture))
            .get("acceptedFactIds")).isEmpty());
    }

    @Test void otherContractNumberInsideSameRangePreventsAssociation() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(),
            "合同编号：OTHER-2025-009\n" + continuation());
        assertTrue(ProofVerifier.strings(fixture.submit(crossPageCandidate(fixture))
            .get("acceptedFactIds")).isEmpty());
    }

    @Test void otherExplicitContractNameInsideSameRangePreventsAssociation() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(),
            "合同名称：另一中心施工合同\n" + continuation());
        assertTrue(ProofVerifier.strings(fixture.submit(crossPageCandidate(fixture))
            .get("acceptedFactIds")).isEmpty());
    }

    @Test void orderPageCannotBecomeMotherContractPriceByRangeAlone() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(),
            "订单编号：PO-SYN-009\n" + continuation());
        assertTrue(ProofVerifier.strings(fixture.submit(crossPageCandidate(fixture))
            .get("acceptedFactIds")).isEmpty());
    }

    @Test void modelReportedPacketCannotAuthorizeStandaloneVerifier() {
        EvidenceCorpus corpus = corpus(
            identityBody(), continuation());
        Map<String,Object> candidate = linked("kind", "CONTRACT", "field", "contractName",
            "rawValue", NAME, "value", NAME, "sourceIds", Arrays.asList(
                source(corpus, 1).id(),
                source(corpus, 2).id()),
            "attributes", linked("contractName", NAME, "contractNumber", NUMBER,
                "packetVerified", true, "documentHash", "synthetic-hash",
                "proofPageStart", 1, "proofPageEnd", 2));
        ProofVerifier.Verified result = new ProofVerifier(corpus.byId).verify(
            new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(linked("facts", Collections.singletonList(candidate))),
            corpus.all);
        assertTrue(result.facts().isEmpty());
        assertEquals("CONTRACT_PAGE_LINK_UNPROVEN", result.rejected().get(0).get("code"));
    }

    @Test void titleDashTypographyCanMatchWithoutChangingYearsOrNumbers() {
        String rawName = "合成中心2023—2024年装饰装修工程施工合同";
        String suppliedName = "合成中心2023-2024年装饰装修工程施工合同";
        EvidenceCorpus corpus = corpus(
            "合同名称：" + rawName + "\n合同编号：" + NUMBER);
        Map<String,Object> candidate = linked("kind", "CONTRACT", "field", "contractName",
            "rawValue", rawName, "value", rawName,
            "sourceIds", Collections.singletonList(source(corpus, 1).id()),
            "attributes", linked("contractName", suppliedName, "contractNumber", NUMBER));
        ProofVerifier.Verified result = new ProofVerifier(corpus.byId).verify(
            new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(linked("facts", Collections.singletonList(candidate))),
            corpus.all);
        assertEquals(1, result.facts().size(), result.rejected().toString());
    }

    @Test void titleDifferentYearAndLotStillConflict() {
        for (String wrong : Arrays.asList("甲2023-2025年施工合同", "甲2023-2024年施工合同-二标段")) {
            Fact fact = new Fact("synthetic", "CONTRACT", "", "contractName",
                "甲2023—2024年施工合同-一标段", "", "", "", "UNRESOLVED",
                Collections.singletonList("synthetic-source"), linked("contractName", wrong));
            assertEquals("CONTRACT_IDENTITY_CONFLICT", ContractCandidateFields.conflict(fact));
        }
    }

    @Test void contractNumberDashTypographyIsNeverNormalized() {
        Fact fact = new Fact("synthetic", "CONTRACT", "", "contractNumber",
            "SYN—001", "", "", "", "UNRESOLVED",
            Collections.singletonList("synthetic-source"), linked("contractNumber", "SYN-001"));
        assertEquals("CONTRACT_IDENTITY_CONFLICT", ContractCandidateFields.conflict(fact));
    }

    @Test void explicitAtomicModeKeepsGoodIdentityAndRejectsBadDate() throws Exception {
        Fixture fixture = fixture();
        Map<String,Object> original = fixture.candidate("contractName", NAME);
        original.put("attributes", linked("contractName", NAME, "contractNumber", NUMBER,
            "signedDate", "2099-01-01"));
        Map<String,Object> name = linked("field", "contractName", "rawValue", NAME,
            "sourceIds", original.get("sourceIds"), "attributes", linked("contractName", NAME));
        Map<String,Object> date = linked("field", "signedDate", "rawValue", "2099年1月1日",
            "sourceIds", original.get("sourceIds"),
            "attributes", linked("contractName", NAME, "signedDate", "2099-01-01"));
        Map<String,Object> receipt = fixture.host.call("submit_candidate_fact", linked(
            "targetObligationId", fixture.target, "fact", original,
            "submissionMode", "CONTRACT_FIELDS", "contractFields", Arrays.asList(name, date)));
        assertEquals(1, ProofVerifier.strings(receipt.get("acceptedFactIds")).size(), receipt.toString());
        assertEquals(true, receipt.get("partialAdoption"));
        assertEquals(2, RuleEvaluator.maps(receipt.get("rejections")).size(), receipt.toString());
        assertTrue(receipt.toString().contains("CONTRACT_DATE_UNGROUNDED"));
        assertTrue(receipt.toString().contains("RAW_VALUE_UNGROUNDED"));
        Fact fact = fixture.host.facts.values().stream().filter(item -> item.kind().equals("CONTRACT"))
            .findFirst().get();
        assertEquals(NAME, fact.rawValue());
        assertEquals(original.get("sourceIds"), fact.sourceIds());
        assertFalse(fact.attributes().containsKey("signedDate"));
        fixture.host.recompute();
        assertTrue(RuleEvaluator.maps(fixture.host.evaluator.decisions.get(fixture.parent.id())
            .proof().get("contractChecks")).stream().noneMatch(check -> Boolean.TRUE.equals(check.get("counted"))));
    }

    @Test void atomicModeCannotSmuggleAnUnrelatedAttributeOrUnknownKind() throws Exception {
        Fixture fixture = fixture();
        Map<String,Object> original = fixture.candidate("contractName", NAME);
        original.put("rawValue", "并不存在的合同原文");
        Map<String,Object> atom = linked("field", "contractName", "rawValue", NAME,
            "sourceIds", original.get("sourceIds"),
            "attributes", linked("contractName", NAME, "amount", "999999", "signedDate", "2099-01-01"));
        Map<String,Object> receipt = fixture.host.call("submit_candidate_fact", linked(
            "targetObligationId", fixture.target, "fact", original,
            "submissionMode", "CONTRACT_FIELDS", "contractFields", Collections.singletonList(atom)));
        assertTrue(ProofVerifier.strings(receipt.get("acceptedFactIds")).isEmpty());
        assertTrue(receipt.toString().contains("CONTRACT_ATOMIC_FIELDS_INVALID"), receipt.toString());
    }

    @Test void schemaAndCorrectionGuideExposeExplicitAtomicMode() throws Exception {
        assertTrue(EvidenceInvestigation.tools().toString().contains("CONTRACT_FIELDS"));
        assertTrue(EvidenceInvestigation.tools().toString().contains("contractFields"));
        Fixture fixture = fixture();
        Map<String,Object> candidate = fixture.candidate("contractName", NAME);
        candidate.put("attributes", linked("signedDate", "2099-01-01"));
        Map<String,Object> receipt = fixture.submit(candidate);
        assertTrue(receipt.get("correctionHints").toString().contains("CONTRACT_FIELDS"));
        assertTrue(ProofVerifier.strings(receipt.get("acceptedFactIds")).isEmpty());
    }


    @Test void acceptedContinuationSurvivesSharedPublishAndCurrentSourceRevalidation() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(), continuation());
        fixture.submit(crossPageCandidate(fixture));
        Fact accepted = fixture.host.facts.values().stream().filter(item -> item.kind().equals("CONTRACT"))
            .findFirst().get();
        java.util.List<Map<String,Object>> batches = new java.util.ArrayList<>();
        Map<String,Object> context = linked("sharedFactPublisher",
            (java.util.function.Consumer<Map<String,Object>>) batches::add);
        SharedFactReuse writer = new SharedFactReuse(fixture.corpus, context, "synthetic", "synthetic");
        java.util.List<Rule> rules = SourcePerformanceScore.evidenceRules(fixture.parent);
        writer.publish(Collections.singletonList(accepted), fixture.corpus.all, rules,
            Collections.emptyList(), false, "MODEL");
        assertEquals(1, batches.size(), "Host acceptance must survive the durable verification boundary");
        java.util.List<Map<String,Object>> entries = new java.util.ArrayList<>();
        for (Map<String,Object> batch : batches)
            for (Map<String,Object> fact : RuleEvaluator.maps(batch.get("facts")))
                entries.add(linked("fact", fact, "sources", batch.get("sources"),
                    "engineVersion", batch.get("engineVersion"), "operationIdentity", batch.get("operationIdentity")));
        Map<String,Object> readContext = linked("sharedFactLookup",
            (java.util.function.Function<Map<String,Object>,Object>) ignored -> entries);
        SharedFactReuse reader = new SharedFactReuse(fixture.corpus, readContext, "synthetic", "synthetic");
        java.util.List<Fact> reused = reader.lookup(rules, fixture.corpus.all, Collections.emptyList());
        assertEquals(1, reused.size(), "source-bound shared fact must be reusable");
        assertEquals(accepted.sourceIds(), reused.get(0).sourceIds());

        ModelGateway noModel = ModelGateway.disabled();
        java.util.List<Fact> retained = new java.util.ArrayList<>(reused);
        fixture.host.facts.values().stream().filter(fact -> !fact.kind().equals("CONTRACT"))
            .forEach(retained::add);
        Map<String,Object> snapshot = linked("type", RecoveryReplay.FACT_SNAPSHOT, "status", "COMPLETED",
            "schemaVersion", VERSION, "modelIdentity", noModel.identity(),
            "output", RecoveryReplay.snapshot(fixture.corpus, retained));
        Map<String,Object> recovery = dates();
        recovery.putAll(linked("recoveryMode", "RECOMPUTE_ONLY", "recoveryModelAllowed", false,
            "recoveryTargetObligationIds", Collections.singletonList(fixture.target),
            "previousAtomicCheck", linked("plan", new Plan("ALL", Collections.singletonList(fixture.parent),
                "SYNTHETIC", Collections.emptyList())), "priorSteps", Collections.singletonList(snapshot)));
        cn.bidcheck.domain.Domain.Checkpoint checkpoint = new cn.bidcheck.domain.Domain.Checkpoint(
            "synthetic-roundtrip", "合同业绩", "商务", "HIGH", "SEMANTIC_RESPONSE", "", "",
            fixture.parent.sourceQuote(), "", true, true, true, null);
        cn.bidcheck.domain.Domain.Document tender = new cn.bidcheck.domain.Domain.Document(
            "synthetic-tender", "p", "TENDER", "synthetic-tender.pdf", 1, "tender-hash", "",
            Collections.singletonList(new cn.bidcheck.domain.Domain.Page(1, "投标截止时间：2025年8月5日", "PAGE", "TEXT", "")), Collections.emptyList());
        recovery.put("referenceDateEvidence", linked("documentId", "synthetic-tender", "page", 1,
            "locationType", "PAGE", "quote", "投标截止时间：2025年8月5日", "confirmation", "SOURCE_UNAMBIGUOUS", "confirmed", true));
        Map<String,Object> recomputed = new VerificationEngine(noModel, null).evaluate(
            checkpoint, fixture.corpus.documents, tender, recovery);
        boolean consumed = false;
        for (com.fasterxml.jackson.databind.JsonNode checks : new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(recomputed).findValues("contractChecks")) {
            for (com.fasterxml.jackson.databind.JsonNode check : checks)
                for (com.fasterxml.jackson.databind.JsonNode id : check.path("factIds"))
                    if (reused.get(0).id().equals(id.asText())) consumed = true;
        }
        assertTrue(consumed, "Published, reused, and locally revalidated contract must reach contractChecks " + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(recomputed).findValues("reason") + " checks=" + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(recomputed).findValues("contractChecks"));
        assertEquals(0, ((Map<?,?>)recomputed.get("metrics")).get("modelCalls"));

    }


    @Test void staleVersionAndAnotherDocumentCannotAuthorizeContinuation() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(), continuation());
        Map<String,Object> candidate = crossPageCandidate(fixture);
        Fact fact = new Fact("synthetic", "CONTRACT", "", "contractName", NAME, NAME, "", "",
            "UNRESOLVED", ProofVerifier.strings(candidate.get("sourceIds")),
            (Map<String,Object>) candidate.get("attributes"));
        java.util.List<Source> refs = Arrays.asList(
            source(fixture.corpus, 4),
            source(fixture.corpus, 5));
        Map<String,Object> originalRow = (Map<String,Object>) SourcePerformanceScore.evidenceRules(fixture.parent)
            .stream().filter(rule -> rule.id().equals(fixture.target)).findFirst().get()
            .parameters().get("declaredPerformanceRow");
        for (String key : Arrays.asList("documentVersion", "documentHash", "documentId")) {
            Map<String,Object> row = new java.util.LinkedHashMap<>(originalRow);
            row.put(key, key.equals("documentVersion") ? 999 : "different");
            assertFalse(new ContractRecordBindings(row, fixture.corpus.byId,
                new java.util.LinkedHashSet<>(fact.sourceIds())).matches(fact, refs));
        }
    }

    @Test void atomicAmountsKeepUnitTaxAndBasisFailuresSeparate() throws Exception {
        Fixture fixture = new Fixture(
            identityBody() + "\n不含税合同总价：100元。", "续页");
        Map<String,Object> original = fixture.candidate("contractName", NAME);
        original.put("attributes", linked("contractName", NAME, "amount", "999", "amountBasis", "合同总价", "unit", "元"));
        Map<String,Object> valid = linked("field", "amount", "rawValue", "100",
            "sourceIds", original.get("sourceIds"),
            "attributes", linked("contractName", NAME, "amount", "100", "amountBasis", "合同总价", "unit", "元", "taxBasis", "不含税"));
        Map<String,Object> invalid = linked("field", "amount", "rawValue", "100",
            "sourceIds", original.get("sourceIds"),
            "attributes", linked("contractName", NAME, "amount", "100", "amountBasis", "合同总价", "unit", "万元"));
        Map<String,Object> receipt = fixture.host.call("submit_candidate_fact", linked(
            "targetObligationId", fixture.target, "fact", original, "submissionMode", "CONTRACT_FIELDS",
            "contractFields", Arrays.asList(valid, invalid)));
        assertEquals(1, ProofVerifier.strings(receipt.get("acceptedFactIds")).size(), receipt.toString());
        assertEquals(true, receipt.get("partialAdoption"));
        assertTrue(receipt.toString().contains("CONTRACT_AMOUNT_UNIT_UNPROVEN"));
        Fact fact = fixture.host.facts.values().stream().filter(item -> item.kind().equals("CONTRACT"))
            .findFirst().get();
        assertEquals("元", fact.attributes().get("unit"));
        assertEquals("不含税", fact.attributes().get("taxBasis"));
        assertEquals("100", fact.rawValue());
    }

    @Test void atomicModePreservesTheOriginalScopeValidation() throws Exception {
        Fixture fixture = new Fixture(
            "历史工程合同\n" + identityBody(), "历史工程履约材料");
        Map<String,Object> original = fixture.candidate("contractName", NAME);
        original.put("scope", "INVALID_SCOPE");
        Map<String,Object> atom = linked("field", "contractName", "rawValue", NAME,
            "sourceIds", original.get("sourceIds"), "attributes", linked("contractName", NAME));
        Map<String,Object> receipt = fixture.host.call("submit_candidate_fact", linked(
            "targetObligationId", fixture.target, "fact", original, "submissionMode", "CONTRACT_FIELDS",
            "contractFields", Collections.singletonList(atom)));
        assertTrue(ProofVerifier.strings(receipt.get("acceptedFactIds")).isEmpty());
        assertTrue(receipt.toString().contains("SCOPE_INVALID"));
    }


    @Test void separatorTypographyAndGenericContractSuffixDoNotBlockProvenOriginalTitle() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(), continuation());
        Map<String,Object> candidate = crossPageCandidate(fixture);
        Fact fact = new Fact("synthetic", "CONTRACT", "", "contractName", NAME, NAME, "", "",
            "UNRESOLVED", ProofVerifier.strings(candidate.get("sourceIds")),
            (Map<String,Object>) candidate.get("attributes"));
        Map<String,Object> row = new java.util.LinkedHashMap<>((Map<String,Object>)
            SourcePerformanceScore.evidenceRules(fixture.parent).stream()
                .filter(rule -> rule.id().equals(fixture.target)).findFirst().get()
                .parameters().get("declaredPerformanceRow"));
        row.remove("contractName");
        row.put("separatorTitle", NAME.substring(0, NAME.length() - 2));
        assertTrue(new ContractRecordBindings(row, fixture.corpus.byId,
            new java.util.LinkedHashSet<>(fact.sourceIds())).matches(fact,
                Arrays.asList(source(fixture.corpus, 4),
                    source(fixture.corpus, 5))));
        assertTrue(ContractCandidateFields.sameTitle("甲中心艺术装置（设计）", "甲中心艺术装置(设计)"));
        assertFalse(ContractCandidateFields.sameTitle("甲中心艺术装置（设计）", "甲中心艺术装置(施工)"));
    }


    @Test void atomicReceiptUsesThePostAdoptionTargetRevisionAndAllRejections() throws Exception {
        Fixture fixture = fixture();
        Map<String,Object> original = fixture.candidate("contractName", NAME);
        original.put("attributes", linked("contractName", NAME, "signedDate", "2099-01-01"));
        Map<String,Object> identity = linked("field", "contractName", "rawValue", NAME,
            "sourceIds", original.get("sourceIds"), "attributes", linked("contractName", NAME));
        Map<String,Object> badNumber = linked("field", "contractNumber", "rawValue", NUMBER,
            "sourceIds", original.get("sourceIds"), "attributes", linked("contractNumber", "OTHER-001"));
        Map<String,Object> receipt = fixture.host.call("submit_candidate_fact", linked(
            "targetObligationId", fixture.target, "fact", original, "submissionMode", "CONTRACT_FIELDS",
            "contractFields", Arrays.asList(identity, badNumber)));
        Map<String,Object> revisions = (Map<String,Object>) receipt.get("targetEvidenceRevisions");
        java.util.List<Map<String,Object>> work = RuleEvaluator.maps(receipt.get("semanticWorkItems"));
        assertEquals(1, work.size(), receipt.toString());
        assertEquals(fixture.target, work.get(0).get("targetObligationId"));
        assertEquals(revisions.get(fixture.target), work.get(0).get("evidenceRevision"));
        assertEquals(revisions.get(fixture.target), receipt.get("rejectionEvidenceRevision"));
        assertEquals(false, work.get(0).get("targetExhaustedForRevision"));
        assertEquals(true, receipt.get("partialAdoption"));
        assertTrue(receipt.get("correctionHints").toString().contains("CONTRACT_ATOMIC_FIELDS_INVALID"));
        assertTrue(receipt.get("correctionHints").toString().contains("CONTRACT_DATE_UNGROUNDED"));
        assertEquals(2, RuleEvaluator.maps(receipt.get("rejections")).size());
    }


    @Test void printedTextAfterSeparatedContractNumberIsNotPartOfTheIdentifier() throws Exception {
        String body = identityBody().replace(
            "合同编号：" + NUMBER, "合同编号：" + NUMBER + "  合成水印");
        Fixture fixture = new Fixture(body, continuation());
        Map<String,Object> receipt = fixture.submit(crossPageCandidate(fixture));
        assertEquals(1, ProofVerifier.strings(receipt.get("acceptedFactIds")).size(), receipt.toString());
    }


    @Test void tableContractNumberCannotHideASecondIdentityInsideThePacket() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(),
            "| 合同编号 | OTHER-2025-009 |\n" + continuation());
        assertTrue(ProofVerifier.strings(fixture.submit(crossPageCandidate(fixture))
            .get("acceptedFactIds")).isEmpty());
    }

    @Test void independentUnlabelledContractTitlePreventsJoiningItsBody() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(),
            "# 乙中心装饰装修工程施工合同\n" + continuation());
        assertTrue(ProofVerifier.strings(fixture.submit(crossPageCandidate(fixture))
            .get("acceptedFactIds")).isEmpty());
    }

    @Test void purchaseOrderWithoutDashStillCannotLendItsAmountToMotherContract() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(),
            "订单编号：PO2025009\n" + continuation());
        assertTrue(ProofVerifier.strings(fixture.submit(crossPageCandidate(fixture))
            .get("acceptedFactIds")).isEmpty());
    }


    @Test void localEngineRevalidationRetainsTheAcceptedCrossPageFact() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(), continuation());
        fixture.submit(crossPageCandidate(fixture));
        ModelGateway noModel = ModelGateway.disabled();
        Map<String,Object> step = linked("type", RecoveryReplay.FACT_SNAPSHOT, "status", "COMPLETED",
            "schemaVersion", VERSION, "modelIdentity", noModel.identity(),
            "output", RecoveryReplay.snapshot(fixture.corpus, fixture.host.facts.values()));
        Plan plan = new Plan("ALL", Collections.singletonList(fixture.parent), "SYNTHETIC", Collections.emptyList());
        Map<String,Object> context = dates();
        context.putAll(linked("recoveryMode", "RECOMPUTE_ONLY", "recoveryModelAllowed", false,
            "recoveryTargetObligationIds", Collections.singletonList(fixture.target),
            "previousAtomicCheck", linked("plan", plan), "priorSteps", Collections.singletonList(step)));
        cn.bidcheck.domain.Domain.Checkpoint checkpoint = new cn.bidcheck.domain.Domain.Checkpoint(
            "synthetic-local-contract", "合同业绩", "商务", "HIGH", "SEMANTIC_RESPONSE", "", "",
            fixture.parent.sourceQuote(), "", true, true, true, null);
        Map<String,Object> result = new VerificationEngine(noModel, null).evaluate(
            checkpoint, fixture.corpus.documents, null, context);
        java.util.List<Map<String,Object>> snapshots = RuleEvaluator.maps(result.get("steps"));
        boolean found = false;
        for (Map<String,Object> saved : snapshots) if (RecoveryReplay.FACT_SNAPSHOT.equals(saved.get("type"))) {
            Map<String,Object> output = (Map<String,Object>) saved.get("output");
            for (Map<String,Object> fact : RuleEvaluator.maps(output.get("facts")))
                if ("CONTRACT".equals(fact.get("kind")) && ProofVerifier.strings(fact.get("sourceIds")).size() == 2)
                    found = true;
        }
        assertTrue(found, result.toString());
        assertEquals(0, ((Map<?,?>)result.get("metrics")).get("modelCalls"));
    }


    @Test void acceptedCrossPageFactIsConsumedByThePerformanceEvaluator() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(), continuation());
        Map<String,Object> receipt = fixture.submit(crossPageCandidate(fixture));
        String factId = ProofVerifier.strings(receipt.get("acceptedFactIds")).get(0);
        fixture.host.recompute();
        Decision score = fixture.host.evaluator.decisions.get(fixture.parent.id());
        assertTrue(RuleEvaluator.maps(score.proof().get("contractChecks")).stream()
            .anyMatch(check -> ProofVerifier.strings(check.get("factIds")).contains(factId)), score.map().toString());
    }


    @Test void crossPageStartDateCannotBecomeContractSigningDate() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(), "合同金额：200万元\n计划开工日期：2023年1月1日\n乙方：" + COMPANY);
        Map<String,Object> receipt = fixture.submit(crossPageCandidate(fixture));
        assertTrue(ProofVerifier.strings(receipt.get("acceptedFactIds")).isEmpty(), receipt.toString());
        assertTrue(receipt.toString().contains("CONTRACT_SIGNING_DATE_ROLE_UNPROVEN"));
    }

    @Test void atomicSigningDateRequiresItsOwnSigningRoleEvenWhenTheDateExists() throws Exception {
        Fixture fixture = new Fixture(
            identityBody() + "\n申报日期：2023年1月1日\n出生日期：2023年1月1日", "续页");
        Map<String,Object> original = fixture.candidate("contractName", NAME);
        Map<String,Object> atom = linked("field", "signedDate", "rawValue", "2023年1月1日",
            "sourceIds", original.get("sourceIds"),
            "attributes", linked("contractName", NAME, "signedDate", "2023-01-01"));
        Map<String,Object> receipt = fixture.host.call("submit_candidate_fact", linked(
            "targetObligationId", fixture.target, "fact", original, "submissionMode", "CONTRACT_FIELDS",
            "contractFields", Collections.singletonList(atom)));
        assertTrue(receipt.toString().contains("CONTRACT_SIGNING_DATE_ROLE_UNPROVEN"));
        assertTrue(fixture.host.facts.values().stream().filter(fact -> fact.kind().equals("CONTRACT"))
            .noneMatch(fact -> fact.attributes().containsKey("signedDate")));
    }

    @Test void explicitSigningLabelAndStampedSignatureFooterRemainValid() throws Exception {
        for (String line : Arrays.asList("合同签署日期：2023年1月1日", "乙方（盖章）：" + COMPANY + "\n时间：2023年1月1日")) {
            Fixture fixture = new Fixture(
                identityBody(), "合同金额：200万元\n" + line);
            assertEquals(1, ProofVerifier.strings(fixture.submit(crossPageCandidate(fixture))
                .get("acceptedFactIds")).size());
        }
    }

    @Test void stampedFooterSurvivesHostSharedReuseAndLocalScoring() throws Exception {
        Fixture fixture = new Fixture(
            identityBody(), "合同金额：200万元\n乙方（盖章）：" + COMPANY + "\n时间：2023年1月1日");
        fixture.submit(crossPageCandidate(fixture));
        Fact accepted = fixture.host.facts.values().stream().filter(item -> item.kind().equals("CONTRACT"))
            .findFirst().get();
        java.util.List<Map<String,Object>> batches = new java.util.ArrayList<>();
        Map<String,Object> context = linked("sharedFactPublisher",
            (java.util.function.Consumer<Map<String,Object>>) batches::add);
        SharedFactReuse writer = new SharedFactReuse(fixture.corpus, context, "synthetic", "synthetic");
        java.util.List<Rule> rules = SourcePerformanceScore.evidenceRules(fixture.parent);
        writer.publish(Collections.singletonList(accepted), fixture.corpus.all, rules,
            Collections.emptyList(), false, "MODEL");
        assertEquals(1, batches.size(), "Host acceptance must survive the durable verification boundary");
        java.util.List<Map<String,Object>> entries = new java.util.ArrayList<>();
        for (Map<String,Object> batch : batches)
            for (Map<String,Object> fact : RuleEvaluator.maps(batch.get("facts")))
                entries.add(linked("fact", fact, "sources", batch.get("sources"),
                    "engineVersion", batch.get("engineVersion"), "operationIdentity", batch.get("operationIdentity")));
        Map<String,Object> readContext = linked("sharedFactLookup",
            (java.util.function.Function<Map<String,Object>,Object>) ignored -> entries);
        SharedFactReuse reader = new SharedFactReuse(fixture.corpus, readContext, "synthetic", "synthetic");
        java.util.List<Fact> reused = reader.lookup(rules, fixture.corpus.all, Collections.emptyList());
        assertEquals(1, reused.size(), "source-bound shared fact must be reusable");
        assertEquals(accepted.sourceIds(), reused.get(0).sourceIds());

        ModelGateway noModel = ModelGateway.disabled();
        java.util.List<Fact> retained = new java.util.ArrayList<>(reused);
        fixture.host.facts.values().stream().filter(fact -> !fact.kind().equals("CONTRACT"))
            .forEach(retained::add);
        Map<String,Object> snapshot = linked("type", RecoveryReplay.FACT_SNAPSHOT, "status", "COMPLETED",
            "schemaVersion", VERSION, "modelIdentity", noModel.identity(),
            "output", RecoveryReplay.snapshot(fixture.corpus, retained));
        Map<String,Object> recovery = dates();
        recovery.putAll(linked("recoveryMode", "RECOMPUTE_ONLY", "recoveryModelAllowed", false,
            "recoveryTargetObligationIds", Collections.singletonList(fixture.target),
            "previousAtomicCheck", linked("plan", new Plan("ALL", Collections.singletonList(fixture.parent),
                "SYNTHETIC", Collections.emptyList())), "priorSteps", Collections.singletonList(snapshot)));
        cn.bidcheck.domain.Domain.Checkpoint checkpoint = new cn.bidcheck.domain.Domain.Checkpoint(
            "synthetic-roundtrip", "合同业绩", "商务", "HIGH", "SEMANTIC_RESPONSE", "", "",
            fixture.parent.sourceQuote(), "", true, true, true, null);
        cn.bidcheck.domain.Domain.Document tender = new cn.bidcheck.domain.Domain.Document(
            "synthetic-tender", "p", "TENDER", "synthetic-tender.pdf", 1, "tender-hash", "",
            Collections.singletonList(new cn.bidcheck.domain.Domain.Page(1, "投标截止时间：2025年8月5日", "PAGE", "TEXT", "")), Collections.emptyList());
        recovery.put("referenceDateEvidence", linked("documentId", "synthetic-tender", "page", 1,
            "locationType", "PAGE", "quote", "投标截止时间：2025年8月5日", "confirmation", "SOURCE_UNAMBIGUOUS", "confirmed", true));
        Map<String,Object> recomputed = new VerificationEngine(noModel, null).evaluate(
            checkpoint, fixture.corpus.documents, tender, recovery);
        boolean consumed = false;
        for (com.fasterxml.jackson.databind.JsonNode checks : new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(recomputed).findValues("contractChecks")) {
            for (com.fasterxml.jackson.databind.JsonNode check : checks)
                for (com.fasterxml.jackson.databind.JsonNode id : check.path("factIds"))
                    if (reused.get(0).id().equals(id.asText())) consumed = true;
        }
        assertTrue(consumed, "Published, reused, and locally revalidated contract must reach contractChecks " + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(recomputed).findValues("reason") + " checks=" + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(recomputed).findValues("contractChecks"));
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(recomputed).findValues("contractChecks").toString().contains("签订日期标签与日期的对应关系"), "A source-bound stamped signing footer must retain its date role through scoring");
        assertEquals(0, ((Map<?,?>)recomputed.get("metrics")).get("modelCalls"));

    }
    @Test void startTimeAndUnstampedTimeCannotBorrowASigningRole() throws Exception {
        for (String footer : Arrays.asList(
                "乙方（盖章）：" + COMPANY + "\n计划开工时间：2023年1月1日",
                "乙方：" + COMPANY + "\n时间：2023年1月1日")) {
            Fixture fixture = new Fixture(identityBody(), "合同金额：200万元\n" + footer);
            Map<String,Object> receipt = fixture.submit(crossPageCandidate(fixture));
            assertTrue(ProofVerifier.strings(receipt.get("acceptedFactIds")).isEmpty(), receipt.toString());
            assertTrue(receipt.toString().contains("CONTRACT_SIGNING_DATE_ROLE_UNPROVEN"));
        }
    }
}