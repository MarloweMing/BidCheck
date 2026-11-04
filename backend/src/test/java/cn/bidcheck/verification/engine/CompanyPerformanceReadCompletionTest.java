package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class CompanyPerformanceReadCompletionTest {
    private static final String BUILDER = "合成北方建设有限公司";
    private static final String DESIGNER = "合成南方设计有限公司";

    static Rule rule() {
        String quote = "施工业绩情况（商务）自2020年1月1日至今金额在100万元含以上装饰装修工程施工项目合同或EPC合同中担任施工部分。每有1个得2分，最高得6分。"
            + "证明材料：填写附表6-4。需提交合同关键页扫描件，关键页包括合同名称、合同金额及签字盖章页等；"
            + "如提交框架合同，按框架合同金额。如框架合同无签约金额，需提交汇总金额不低于上述限额的项下订单，并列表汇总（格式自拟），"
            + "如提供EPC项目合同，则以施工部分金额为准。时间以合同签订日期为准。（如为联合体投标，则须为施工单位业绩）。";
        Rule rule = SourcePerformanceScore.compile(quote);
        assertNotNull(rule);
        assertNull(SourceScoringRules.issue(rule));
        return rule;
    }

    static String agreement(String allocation) {
        return "联合体协议书\n" + BUILDER + "、" + DESIGNER + "[所有成员单位名称]自愿组成："
            + BUILDER + "和" + DESIGNER + "联合体，共同投标合成北区装修工程/标段。\n" + allocation;
    }

    static EvidenceCorpus corpus(String... text) {
        List<Page> pages = new ArrayList<>();
        pages.add(new Page(1, "商务投标文件封面\n项目名称：合成北区装修工程\n投标文件\n投标人名称：" + BUILDER,
            "PAGE", "TEXT", ""));
        for (int i = 0; i < text.length; i++) pages.add(new Page(i + 2, text[i], "PAGE", "TEXT", ""));
        Document document = new Document("synthetic-company-bid", "synthetic-project", "BID",
            "synthetic-company.pdf", 1, "synthetic-company-hash", "", pages, Collections.emptyList());
        return new EvidenceCorpus(Collections.singletonList(document));
    }

    static Rule read(Rule rule) { return SourcePerformanceScore.evidenceRules(rule).get(0); }

    static RuleEvaluator evaluator(Rule rule, EvidenceCorpus corpus) {
        List<Fact> facts = EnterpriseSubjectBindings.extract(Collections.singletonList(read(rule)), corpus.all);
        return new RuleEvaluator(facts, corpus, linked("evaluationStage", "BID_SUBMISSION"));
    }

    static Decision projection(Rule rule, RuleEvaluator evaluator) {
        Decision parent = evaluator.evaluate(rule);
        return SourcePerformanceScore.readDecision(read(rule), parent, evaluator);
    }

    @Test void uniqueSourceBoundConstructorClosesOnlySubjectAndNeverAwardsContracts() {
        Rule rule = rule();
        EvidenceCorpus corpus = corpus(agreement(BUILDER + "负责施工部分。\n" + DESIGNER + "负责设计部分。"));
        RuleEvaluator evaluator = evaluator(rule, corpus);
        assertEquals(1, evaluator.facts.size());
        Decision subject = projection(rule, evaluator);
        assertEquals("PASS", subject.status(), subject.map().toString());
        assertEquals("EVIDENCE_READ_ONLY", subject.proof().get("decisionScope"));
        assertEquals(false, subject.proof().get("businessProof"));
        assertEquals(false, subject.proof().get("scoreProduced"));
        assertFalse(subject.evidences().isEmpty());
        assertEquals("UNDETERMINED", evaluator.decisions.get(rule.id()).status());
        assertEquals(false, evaluator.decisions.get(rule.id()).proof().get("scoreProduced"));
        Map<String,Object> receipt = AgentInvestigationScope.targetReceipt(SourceScoringRules.evidenceRules(rule),
            new Plan("ALL", Collections.singletonList(rule), "SYNTHETIC", Collections.emptyList()), evaluator);
        assertTrue(ProofVerifier.strings(receipt.get("completedTargetObligationIds")).contains(read(rule).id()));
        assertTrue(ProofVerifier.strings(receipt.get("remainingTargetObligationIds")).contains(rule.id() + "-READ-CONTRACTS"));
    }

    @Test void parentTargetStringWithoutAcceptedSourceBindingDoesNotClose() {
        Rule rule = rule();
        RuleEvaluator evaluator = new RuleEvaluator(Collections.emptyList(),
            corpus(agreement(BUILDER + "负责施工部分。")), linked("evaluationStage", "BID_SUBMISSION"));
        Decision original = evaluator.evaluate(rule);
        Map<String,Object> proof = new LinkedHashMap<>(original.proof());
        proof.put("target", BUILDER);
        Decision forged = new Decision(original.id(), original.description(), original.status(), original.reason(),
            original.evidences(), original.gaps(), proof);
        assertFalse(AgentInvestigationScope.closed(SourcePerformanceScore.readDecision(read(rule), forged, evaluator)));
    }

    @Test void membershipWithoutAllocationAndTwoConstructorsRemainUnresolved() {
        Rule rule = rule();
        for (String allocation : Arrays.asList("本项目施工方待定。",
                BUILDER + "负责施工部分。\n" + DESIGNER + "负责施工部分。")) {
            RuleEvaluator evaluator = evaluator(rule, corpus(agreement(allocation)));
            assertFalse(AgentInvestigationScope.closed(projection(rule, evaluator)), allocation);
        }
    }

    @Test void secondCurrentAllocationIsCounterevidenceEvenWithoutAnAcceptedFact() {
        Rule rule = rule();
        EvidenceCorpus corpus = corpus(agreement(BUILDER + "负责施工部分。"),
            agreement(DESIGNER + "负责施工部分。"));
        List<Fact> firstPageOnly = EnterpriseSubjectBindings.extract(Collections.singletonList(read(rule)),
            Collections.singletonList(corpus.all.get(1)));
        assertEquals(1, firstPageOnly.size());
        RuleEvaluator evaluator = new RuleEvaluator(firstPageOnly, corpus, linked("evaluationStage", "BID_SUBMISSION"));
        assertFalse(AgentInvestigationScope.closed(projection(rule, evaluator)));
    }

    @Test void historicalOrUngroundedCompanyClaimCannotBecomeCurrentAllocation() {
        Rule rule = rule();
        EvidenceCorpus corpus = corpus(agreement(BUILDER + "负责施工部分。"));
        Fact nativeFact = evaluator(rule, corpus).facts.get(0);
        Fact historical = new Fact(nativeFact.id(), nativeFact.kind(), nativeFact.subject(), nativeFact.field(),
            nativeFact.rawValue(), nativeFact.value(), "", "", "HISTORICAL_PROJECT", nativeFact.sourceIds(), nativeFact.attributes());
        assertFalse(AgentInvestigationScope.closed(projection(rule,
            new RuleEvaluator(Collections.singletonList(historical), corpus, linked("evaluationStage", "BID_SUBMISSION")))));
        EvidenceCorpus declaration = corpus("业绩申报表\n施工单位：" + BUILDER + "\n历史合同甲，100万元。");
        Fact claim = new Fact("claim", "RELATION", "施工单位", "名称", BUILDER, BUILDER, "", "", "CURRENT_PROJECT",
            Collections.singletonList(declaration.all.get(1).id()), linked("object", BUILDER));
        assertFalse(AgentInvestigationScope.closed(projection(rule,
            new RuleEvaluator(Collections.singletonList(claim), declaration, linked("evaluationStage", "BID_SUBMISSION")))));
    }

    @Test void unreadCounterevidenceAndTruncatedLookupStillBlockSubjectCompletion() {
        Rule rule = rule();
        EvidenceCorpus corpus = corpus(agreement(BUILDER + "负责施工部分。"));
        RuleEvaluator unread = evaluator(rule, corpus);
        unread.incompleteSourceReview.add(read(rule).id());
        assertFalse(AgentInvestigationScope.closed(projection(rule, unread)));
        RuleEvaluator truncated = evaluator(rule, corpus);
        truncated.context.put("_recoveryCoverageDependencies", linked(read(rule).id(),
            linked("obligationId", read(rule).id(), "gapCode", "EVIDENCE_LOOKUP_INCOMPLETE")));
        assertFalse(AgentInvestigationScope.closed(projection(rule, truncated)));
    }
}
