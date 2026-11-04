package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Invented text only. Saved bid payloads are a separate diagnostic. */
class ProgramScoreBodyRoutingTest {
    private static final List<String> ITEMS = Arrays.asList("建设目标", "现状分析", "设计理念", "设计重难点", "解决方案");
    private static final String TEXT = "本项目针对现有办公空间逐项安排施工配合、信息核对和交付检验。"
        + "人员在每个阶段记录输入条件及完成情况，并根据验收意见逐项调整。"
        + "各项设计内容采用明确的实施顺序、责任安排和验证方法，保证能够复核其实际完成情况。";

    static Rule rule() {
        String quote = "设计方案（技术）\t设计方案完整性：对本项目的建设目标、现状分析、设计理念、设计重难点、解决方案5项内容上述内容齐全得1分，每缺少一项扣0.2分，扣完为止。";
        Checkpoint checkpoint = new Checkpoint("synthetic-score", "设计完整性", "响应", "MEDIUM", "SEMANTIC_RESPONSE",
            "scoring", "RESPONDS", quote, "", true, true, true,
            new Evidence("tender", "synthetic tender", 1, "BLOCK", quote, "", 1));
        RequirementSourceContext.Context context = new RequirementSourceContext.Context(quote,
            linked("verification", "SYNTHETIC"), Collections.singletonList(linked("kind", "OBLIGATION", "sourceQuote", quote)));
        List<Rule> rules = SourceScoringRules.compile(checkpoint, quote, context, Collections.emptyMap());
        assertEquals(1, rules.size());
        assertNull(SourceScoringRules.issue(rules.get(0)));
        return rules.get(0);
    }

    static EvidenceCorpus corpus(String text) {
        return new EvidenceCorpus(Collections.singletonList(new Document("bid", "p", "BID", "synthetic.pdf", 1, "hash", "",
            Collections.singletonList(new Page(1, text, "PAGE", "TEXT", "")), Collections.emptyList())));
    }

    static String body(int count) {
        String[] numbers = {"一", "二", "三", "四", "五"};
        StringBuilder text = new StringBuilder("本项目投标文件\n4. 技术建议书\n4.1 设计方案\n");
        for (int i = 0; i < count; i++) text.append("第").append(numbers[i]).append("章、").append(ITEMS.get(i)).append('\n').append(TEXT).append('\n');
        return text.append("4.2 项目团队\n后续团队内容不属于本设计方案。\n").toString();
    }

    @Test void programScoreUsesSectionReadsAndRetainsBusinessParent() {
        Rule parent = rule();
        List<Rule> reads = SourceScoringRules.evidenceRules(parent);
        assertEquals(1, reads.size());
        assertEquals("SECTION", reads.get(0).predicate());
        assertEquals("设计方案", reads.get(0).expected());
        assertEquals(Collections.singleton("SECTION"), AgentInvestigationScope.kinds(reads.get(0)));
        assertFalse(AgentInvestigationScope.kinds(parent).contains("CONTRACT"));
        assertEquals(parent.id(), SourceScoringRules.readParents(Collections.singletonList(parent), reads).get(reads.get(0).id()));
    }

    @Test void mixedNumberingIncludesActualChaptersAndStopsAtNumericSibling() {
        EvidenceCorpus corpus = corpus(body(5));
        SectionCoverage.Extent extent = SectionCoverage.locate("设计方案", corpus);
        assertTrue(extent.complete(), extent.reason());
        Decision decision = new RuleEvaluator(Collections.emptyList(), corpus, Collections.emptyMap()).evaluate(rule());
        assertEquals("PASS", decision.status(), decision.toString());
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) decision.proof().get("score")));
        assertEquals(true, decision.proof().get("fullScore"));
    }

    @Test void siblingOrAnotherVolumeCannotSupplyMissingDimensions() {
        String text = body(3) + "第五章、解决方案\n" + TEXT + "\n第四章、设计重难点\n" + TEXT;
        Decision partial = new RuleEvaluator(Collections.emptyList(), corpus(text), Collections.emptyMap()).evaluate(rule());
        assertEquals("PASS", partial.status(), partial.toString());
        assertEquals(0, new BigDecimal("0.6").compareTo((BigDecimal) partial.proof().get("score")));
        assertEquals(false, partial.proof().get("fullScore"));
        Document first = corpus(body(3)).documents.get(0);
        Document other = new Document("other", "p", "BID", "another-volume.pdf", 1, "other-hash", "",
            Collections.singletonList(new Page(1, "本项目投标文件\n4.1 设计方案\n第四章、设计重难点\n" + TEXT
                + "\n第五章、解决方案\n" + TEXT, "PAGE", "TEXT", "")), Collections.emptyList());
        Decision split = new RuleEvaluator(Collections.emptyList(), new EvidenceCorpus(Arrays.asList(first, other)), Collections.emptyMap()).evaluate(rule());
        assertNotEquals(true, split.proof().get("fullScore"));
    }

    @Test void ordinaryChineseSiblingStillEndsTheSection() {
        String text = "本项目投标文件\n第一章 设计方案\n1.1 建设目标\n" + TEXT
            + "\n第二章 其他方案\n2.1 现状分析\n" + TEXT;
        Decision result = new RuleEvaluator(Collections.emptyList(), corpus(text), Collections.emptyMap()).evaluate(rule());
        assertEquals(Collections.singletonList("建设目标"), result.proof().get("coveredDimensions"));
    }

    @Test void headingsAndPartialOcrNeverCreateScore() {
        String outline = "本项目投标文件\n4.1 设计方案\n第一章 建设目标\n第二章 现状分析\n第三章 设计理念\n第四章 设计重难点\n第五章 解决方案\n4.2 项目团队";
        RuleEvaluator evaluator = new RuleEvaluator(Collections.emptyList(), corpus(outline), Collections.emptyMap());
        assertEquals("UNDETERMINED", evaluator.evaluate(rule()).status());
        Document partial = new Document("bid", "p", "BID", "synthetic.pdf", 1, "hash", "",
            Collections.singletonList(new Page(1, body(5), "PAGE", "TEXT", "[OCR未完成]missing")),
            Collections.singletonList("[OCR未完成]missing"));
        Decision result = new RuleEvaluator(Collections.emptyList(), new EvidenceCorpus(Collections.singletonList(partial)), Collections.emptyMap()).evaluate(rule());
        assertEquals("UNDETERMINED", result.status());
        assertEquals(false, result.proof().get("scoreProduced"));
    }

    @Test void forgedReadCannotAttachToParentAndContractModeRemainsContract() {
        Rule parent = rule();
        Rule read = SourceScoringRules.evidenceRules(parent).get(0);
        Rule forged = new Rule("forged", read.description(), read.predicate(), "", read.field(), "", read.expected(), "",
            read.parameters(), read.sourceQuote(), "", Collections.emptyList());
        assertFalse(SourceScoringRules.readParents(Collections.singletonList(parent), Collections.singletonList(forged)).containsKey("forged"));
        Rule ordinary = new Rule("contract-score", "合同评分", "SCORE_COUNT", "", "", "", "", "", Collections.emptyMap(), "", "", Collections.emptyList());
        assertEquals(Collections.singleton("CONTRACT"), AgentInvestigationScope.kinds(ordinary));
    }

    @Test void samePageBoundedScanKeepsOriginalSourcesAndRecordsTraceableRanges() {
        EvidenceCorpus corpus = corpus(body(3) + "第五章、解决方案\n" + TEXT);
        Map<String,Source> originals = new LinkedHashMap<>(corpus.byId);
        EvidenceCorpus.ProgramScan scan = corpus.qualityAdvisoryScan(rule());
        assertEquals(ITEMS.subList(0, 3), scan.covered());
        assertEquals(originals, corpus.byId);
        originals.forEach((id, source) -> assertSame(source, corpus.byId.get(id)));
        assertTrue(corpus.all.stream().anyMatch(s -> s.evidence().quote().contains("第五章、解决方案")));
        assertFalse(scan.facts().isEmpty());
        for (cn.bidcheck.verification.engine.VerificationTypes.Fact fact : scan.facts()) {
            Object value = fact.attributes().get("sourceView");
            assertTrue(value instanceof Map, fact.attributes().toString());
            Map<?,?> view = (Map<?,?>) value;
            Source original = corpus.byId.get(fact.sourceIds().get(0));
            assertEquals(original.id(), view.get("sourceId"));
            assertEquals(original.documentHash(), view.get("documentHash"));
            assertEquals(original.evidence().page(), view.get("page"));
            String[] bounds = view.get("anchor").toString().substring(6).split("-");
            String page = corpus.documents.get(0).pages().get(0).text();
            String bounded = page.substring(Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1]));
            assertFalse(bounded.contains("4.2 项目团队"));
            assertFalse(bounded.contains("第五章、解决方案"));
        }
    }

    @Test void explicitNextVolumeStopsQualityScanOnTheSamePage() {
        String text = body(3).replace("4.2 项目团队\n后续团队内容不属于本设计方案。\n", "")
            + "第二分册 报价文件\n第四章、设计重难点\n" + TEXT + "\n第五章、解决方案\n" + TEXT;
        Decision result = new RuleEvaluator(Collections.emptyList(), corpus(text), Collections.emptyMap()).evaluate(rule());
        assertEquals(ITEMS.subList(0, 3), result.proof().get("coveredDimensions"));
        assertEquals(0, new BigDecimal("0.6").compareTo((BigDecimal) result.proof().get("score")));
    }

    @Test void numericTopLevelDoesNotAbsorbAnUnrelatedChinesePeer() {
        String text = "本项目投标文件\n1 设计方案\n1.1 建设目标\n" + TEXT
            + "\n第二章 其他方案\n2.1 现状分析\n" + TEXT;
        Decision result = new RuleEvaluator(Collections.emptyList(), corpus(text), Collections.emptyMap()).evaluate(rule());
        assertEquals(Collections.singletonList("建设目标"), result.proof().get("coveredDimensions"));
    }

    @Test void bodyReadClosesAcquisitionButCannotAwardQualityOrCrossPeerBoundary() {
        Rule objective = rule();
        String continuation = "根据方案详细内容进行评分： / A:内容齐全且针对性强得5分。 / B:存在缺项非A档且基本可行得3分。 / C:内容较弱得1分。";
        Checkpoint checkpoint = new Checkpoint("synthetic-quality", "质量", "响应", "MEDIUM", "SEMANTIC_RESPONSE",
            "scoring", "RESPONDS", objective.sourceQuote(), "", true, true, true,
            new Evidence("tender", "synthetic tender", 1, "BLOCK", objective.sourceQuote(), "", 1));
        RequirementSourceContext.Context context = new RequirementSourceContext.Context(objective.sourceQuote(),
            linked("verification", "SYNTHETIC"), Arrays.asList(
                linked("kind", "OBLIGATION", "sourceQuote", objective.sourceQuote()),
                linked("kind", "SCOPED_SCORING_CONTINUATION", "sourceQuote", continuation)));
        List<Rule> roots = SourceScoringRules.compile(checkpoint, objective.sourceQuote() + "\n" + continuation, context, Collections.emptyMap());
        assertEquals(2, roots.size());
        Rule quality = roots.get(1);
        assertNull(SourceScoringRules.issue(quality));
        RuleEvaluator evaluator = new RuleEvaluator(Collections.emptyList(), corpus(body(3)
            + "第四章、设计重难点\n" + TEXT + "\n第五章、解决方案\n" + TEXT), Collections.emptyMap());
        Decision parent = evaluator.evaluate(quality);
        assertEquals("UNDETERMINED", parent.status());
        assertEquals("NO", parent.proof().get("topTierEligible"));
        assertEquals(false, parent.proof().get("qualityGradeProduced"));
        List<Rule> reads = SourceScoringRules.evidenceRules(quality);
        assertEquals("SECTION", reads.get(0).predicate());
        Plan plan = new Plan("ALL", Collections.singletonList(quality), "SYNTHETIC", Collections.emptyList());
        Map<String,Object> receipt = AgentInvestigationScope.targetReceipt(reads, plan, evaluator);
        assertTrue(ProofVerifier.strings(receipt.get("completedTargetObligationIds")).contains(reads.get(0).id()), receipt.toString());
        assertEquals("UNDETERMINED", evaluator.decisions.get(quality.id()).status());
    }

    @Test void restartedChildNumbersNeverAbsorbAnotherOuterPeerOrParent() {
        for (String boundary : Arrays.asList("4.2 项目团队", "5.1 其他材料", "5 其他材料")) {
            String text = "本项目投标文件\n4.1 设计方案\n第一章 建设目标\n" + TEXT
                + "\n1.1 实施步骤\n" + TEXT + "\n第一节 技术安排\n" + TEXT
                + "\n" + boundary + "\n第二章 现状分析\n" + TEXT;
            Decision result = new RuleEvaluator(Collections.emptyList(), corpus(text), Collections.emptyMap()).evaluate(rule());
            assertEquals(Collections.singletonList("建设目标"), result.proof().get("coveredDimensions"), boundary);
        }
    }
}
