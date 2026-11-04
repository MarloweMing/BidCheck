package cn.bidcheck.verification.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import static org.junit.jupiter.api.Assertions.*;

/** Only invented contract text. Real saved audit examples are replayed separately. */
class ContractFieldSubmissionTest {
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

    static void rejected(Map<String,Object> response, String code) {
        assertTrue(ProofVerifier.strings(response.get("acceptedFactIds")).isEmpty(), response.toString());
        assertTrue(response.toString().contains(code), response.toString());
    }

    @Test void canonicalAndDocumentedAliasesAcceptGroundedIdentityWithoutClosingScore() throws Exception {
        for (String field : Arrays.asList("contractName", "CONTRACT_NAME", "合同名称",
                "contractNumber", "CONTRACT_NUMBER", "合同编号")) {
            Fixture fixture = fixture();
            boolean name = field.equals("contractName") || field.equals("CONTRACT_NAME") || field.equals("合同名称");
            Map<String,Object> response = fixture.submit(fixture.candidate(field, name ? NAME : NUMBER));
            assertEquals(1, ProofVerifier.strings(response.get("acceptedFactIds")).size(), response.toString());
            Fact accepted = fixture.host.facts.values().stream()
                .filter(fact -> "CONTRACT".equals(fact.kind())).findFirst().get();
            assertEquals(name ? NAME : NUMBER, accepted.attributes().get(name ? "contractName" : "contractNumber"));
            assertFalse(accepted.attributes().containsKey("amount"));
            fixture.host.recompute();
            Decision score = fixture.host.evaluator.decisions.get(fixture.parent.id());
            assertEquals("UNDETERMINED", score.status());
            assertEquals(false, score.proof().get("scoreProduced"));
            assertTrue(RuleEvaluator.maps(score.proof().get("contractChecks")).stream()
                .noneMatch(check -> Boolean.TRUE.equals(check.get("counted"))));
            assertFalse(ProofVerifier.strings(fixture.host.recompute().get("completedTargetObligationIds"))
                .contains(fixture.target));
        }
    }

    @Test void toolAndTargetContractsExplainCanonicalIdentityFields() throws Exception {
        String tools = EvidenceInvestigation.tools().toString();
        assertTrue(tools.contains("contractName"));
        assertTrue(tools.contains("contractNumber"));
        assertTrue(tools.contains("CONTRACT_NAME"));
        assertTrue(tools.contains("CONTRACT_NUMBER"));
        Fixture fixture = fixture();
        String contracts = AgentInvestigationScope.contracts(SourcePerformanceScore.evidenceRules(fixture.parent)).toString();
        assertTrue(contracts.contains("contractName"));
        assertTrue(contracts.contains("contractNumber"));
        assertTrue(contracts.contains("signedDate"));
        assertTrue(contracts.contains("amountBasis"));
    }

    @Test void separatelyVerifiedFieldsJoinTheIdentityWithoutInventingMissingProof() throws Exception {
        String body = completeBody()
            .replace("合成0装饰装修工程施工合同", NAME).replace("CID-0", NUMBER);
        Fixture fixture = new Fixture(body, "履约条款");
        assertEquals(1, ProofVerifier.strings(fixture.submit(fixture.candidate("contractName", NAME))
            .get("acceptedFactIds")).size());
        List<Map<String,Object>> attributes = Arrays.asList(
            linked("contractName", NAME, "contractNumber", NUMBER, "party", COMPANY),
            linked("contractName", NAME, "contractNumber", NUMBER, "signedDate", "2023-01-01"),
            linked("contractName", NAME, "contractNumber", NUMBER, "amount", "200", "amountBasis", "合同金额", "unit", "万元"));
        String[] fields = {"party", "signedDate", "amount"};
        String[] values = {COMPANY, "2023年1月1日", "200"};
        for (int i = 0; i < attributes.size(); i++) {
            Map<String,Object> candidate = fixture.candidate(fields[i], values[i]);
            candidate.put("attributes", attributes.get(i));
            Map<String,Object> receipt = fixture.submit(candidate);
            assertEquals(1, ProofVerifier.strings(receipt.get("acceptedFactIds")).size(), receipt.toString());
            fixture.host.recompute();
            Decision score = fixture.host.evaluator.decisions.get(fixture.parent.id());
            if (i < attributes.size() - 1) assertTrue(RuleEvaluator.maps(score.proof().get("contractChecks")).stream()
                .noneMatch(check -> Boolean.TRUE.equals(check.get("counted"))), score.map().toString());
        }
        Decision score = fixture.host.evaluator.decisions.get(fixture.parent.id());
        assertEquals(1L, RuleEvaluator.maps(score.proof().get("contractChecks")).stream()
            .filter(check -> Boolean.TRUE.equals(check.get("counted"))).count(), score.map().toString());
        assertEquals(1, score.proof().get("deduplicatedContracts"));
    }

    @Test void missingIdentityGuidanceNamesTheAttributesNotTheCompanySubject() throws Exception {
        Fixture fixture = fixture();
        Map<String,Object> response = fixture.submit(fixture.candidate("contract", NAME));
        rejected(response, "CONTRACT_IDENTITY_UNPROVEN");
        assertFalse(response.get("recoveryActions").toString().contains("RESOLVE_SUBJECT_FIRST"));
        assertTrue(response.get("correctionHints").toString().contains("/fact/attributes"));
        assertTrue(response.get("correctionHints").toString().contains("contractName"));
        assertTrue(response.get("correctionHints").toString().contains("contractNumber"));
    }

    @Test void rewrittenQuoteRemainsRejected() throws Exception {
        Fixture fixture = fixture();
        rejected(fixture.submit(fixture.candidate("contractName", "甲中心装饰装修集中采购施工总合同")),
            "RAW_VALUE_UNGROUNDED");
    }

    @Test void wrongExistingIdentityIsNeverOverwrittenByNormalization() throws Exception {
        Fixture fixture = fixture();
        Map<String,Object> candidate = fixture.candidate("contractName", NAME);
        candidate.put("attributes", linked("contractName", "其他合同"));
        Map<String,Object> response = fixture.submit(candidate);
        assertTrue(ProofVerifier.strings(response.get("acceptedFactIds")).isEmpty(), response.toString());
        assertEquals("其他合同", ((Map<?,?>)candidate.get("attributes")).get("contractName"));
    }

    @Test void conflictingGroundedNumberCannotReplaceTheRequestedNumber() throws Exception {
        Fixture fixture = new Fixture(identityBody() + "\n另一合同编号：OTHER-2025-002", "后续条款");
        Map<String,Object> candidate = fixture.candidate("contractNumber", NUMBER);
        candidate.put("attributes", linked("contractNumber", "OTHER-2025-002"));
        rejected(fixture.submit(candidate), "CONTRACT_IDENTITY_CONFLICT");
    }

    @Test void conflictingGroundedNameCannotReplaceTheRequestedName() throws Exception {
        Fixture fixture = new Fixture(identityBody() + "\n合同名称：乙中心施工合同", "后续条款");
        Map<String,Object> candidate = fixture.candidate("contractName", NAME);
        candidate.put("attributes", linked("contractName", "乙中心施工合同"));
        rejected(fixture.submit(candidate), "CONTRACT_IDENTITY_CONFLICT");
    }

    @Test void explicitMatchingIdentityIsNotRejectedBecausePageAlsoReferencesAnotherContract() throws Exception {
        Fixture fixture = new Fixture(identityBody() + "\n关联合同编号：OTHER-2025-002", "后续条款");
        Map<String,Object> candidate = fixture.candidate("contractName", NAME);
        candidate.put("attributes", linked("contractName", NAME, "contractNumber", NUMBER));
        Map<String,Object> response = fixture.submit(candidate);
        assertEquals(1, ProofVerifier.strings(response.get("acceptedFactIds")).size(), response.toString());
    }

    @Test void referencesToAContractAreNotAutomaticallyPromotedToItsTitle() throws Exception {
        Fixture fixture = new Fixture(identityBody() + "\n本合同\n详见合同\n本协议\n本合同约定其他事项", "后续条款");
        for (String reference : Arrays.asList("本合同", "详见合同", "本协议", "本合同约定其他事项"))
            rejected(fixture.submit(fixture.candidate("contractName", reference)), "CONTRACT_IDENTITY_UNPROVEN");
    }

    @Test void orderNumberCannotBeInferredAsTheParentAgreementNumber() throws Exception {
        Fixture fixture = new Fixture("协议：[PO-10001]\n订单号：PO-10001\n" + NAME, "后续条款");
        rejected(fixture.submit(fixture.candidate("contractNumber", "PO-10001")), "CONTRACT_IDENTITY_UNPROVEN");
    }

    @Test void titleSuffixCannotDropLotYearOrBatchIdentity() throws Exception {
        for (String suffix : Arrays.asList("-一标段", "—2024年度", "-第二批次", "-施工第一阶段")) {
            Fixture fixture = new Fixture("# " + NAME + suffix + "\n合同编号：" + NUMBER, "后续条款");
            rejected(fixture.submit(fixture.candidate("contractName", NAME)), "CONTRACT_IDENTITY_UNPROVEN");
        }
        Fixture companySuffix = new Fixture("# " + NAME + "-" + COMPANY + "\n合同编号：" + NUMBER, "后续条款");
        assertEquals(1, ProofVerifier.strings(companySuffix.submit(companySuffix.candidate("contractName", NAME))
            .get("acceptedFactIds")).size());
    }

    @Test void navigationHandleCannotNormalizeUnreadSource() throws Exception {
        Fixture fixture = new Fixture(identityBody(), "后续条款", false);
        rejected(fixture.submit(fixture.candidate("contractName", NAME)), "CONTRACT_IDENTITY_UNPROVEN");
        fixture.read(4);
        assertEquals(1, ProofVerifier.strings(fixture.submit(fixture.candidate("contractName", NAME))
            .get("acceptedFactIds")).size());
    }

    @Test void arbitraryGroundedCompanyTextIsNotAnImplicitContractIdentity() throws Exception {
        Fixture fixture = fixture();
        rejected(fixture.submit(fixture.candidate("contractName", COMPANY)),
            "CONTRACT_IDENTITY_UNPROVEN");
        rejected(fixture.submit(fixture.candidate("contractNumber", "2025")), "CONTRACT_IDENTITY_UNPROVEN");
    }

    @Test void multipleContractsOnOnePageCannotSupplyAnImplicitIdentity() throws Exception {
        Fixture fixture = new Fixture(identityBody() + "\n合同名称：乙中心施工合同\n合同编号：OTHER-2025-002", "后续条款");
        rejected(fixture.submit(fixture.candidate("contractName", NAME)), "CONTRACT_IDENTITY_UNPROVEN");
    }

    @Test void anotherContractSourceCannotBeAttachedToAnIdentity() throws Exception {
        Fixture fixture = new Fixture(identityBody(), "合同名称：乙中心施工合同\n合同编号：OTHER-2025-002");
        Map<String,Object> candidate = fixture.candidate("contractName", NAME);
        candidate.put("sourceIds", Arrays.asList(source(fixture.corpus, 4).id(), source(fixture.corpus, 5).id()));
        assertTrue(ProofVerifier.strings(fixture.submit(candidate).get("acceptedFactIds")).isEmpty());
    }

    @Test void identityNormalizationCannotBypassAmountTaxUnitOrDateChecks() throws Exception {
        Fixture fixture = new Fixture(identityBody() + "\n不含税合同总价：100元。\n合同签订日期：2024年1月1日。", "后续条款");
        List<Map<String,Object>> invalid = Arrays.asList(
            linked("amount", "999", "amountBasis", "合同总价", "unit", "元"),
            linked("amount", "100", "amountBasis", "合同总价", "unit", "万元"),
            linked("amount", "100", "amountBasis", "合同总价", "unit", "元", "taxBasis", "含税"),
            linked("signedDate", "2025-01-01"));
        String[] codes = {"CONTRACT_AMOUNT_BASIS_UNPROVEN", "CONTRACT_AMOUNT_UNIT_UNPROVEN",
            "CONTRACT_TAX_BASIS_UNPROVEN", "CONTRACT_DATE_UNGROUNDED"};
        for (int i = 0; i < invalid.size(); i++) {
            Map<String,Object> candidate = fixture.candidate("contractName", NAME);
            candidate.put("attributes", invalid.get(i));
            rejected(fixture.submit(candidate), codes[i]);
        }
    }
}
