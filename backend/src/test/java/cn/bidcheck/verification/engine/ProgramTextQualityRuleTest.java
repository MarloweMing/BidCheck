package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import org.junit.jupiter.api.Test;

import java.util.*;

import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ProgramTextQualityRuleTest {
    @Test void configuredTypoIsAProgramFailureAndCleanClosedScopePasses() {
        Rule rule=textRule(cn.bidcheck.compat.Java8Api.mapOf("textScope", "全文", "anomalyTypes", cn.bidcheck.compat.Java8Api.listOf("TYPO"), "typoDictionary", cn.bidcheck.compat.Java8Api.listOf("项日=>项目")));
        Decision found=evaluate(rule,document("投标函\n我公司参加本项日投标，并按要求提交全部资料。"));
        assertEquals("FAIL",found.status());
        assertEquals("PROGRAM_TEXT_SCAN",found.proof().get("decisionBasis"));
        assertTrue(found.proof().toString().contains("TEXT_ANOMALY"));

        Decision clear=evaluate(rule,document("投标函\n我公司参加本项目投标，并按要求提交全部资料。"));
        assertEquals("PASS",clear.status());
        assertEquals(false,clear.proof().get("coverageComplete") instanceof Boolean&&!((Boolean) (clear.proof().get("coverageComplete"))));
    }

    @Test void missingTypoDictionaryAndIncompleteOcrCannotBecomeCleanPass() {
        Rule missing=textRule(cn.bidcheck.compat.Java8Api.mapOf("textScope", "全文", "anomalyTypes", cn.bidcheck.compat.Java8Api.listOf("TYPO")));
        Decision noDetector=evaluate(missing,document("投标函\n正文内容完整。"));
        assertEquals("UNDETERMINED",noDetector.status());
        assertEquals("TEXT_ANOMALY_DETECTOR_REQUIRED",noDetector.gaps().get(0).code());

        Rule fixed=textRule(cn.bidcheck.compat.Java8Api.mapOf("textScope", "全文", "anomalyTypes", cn.bidcheck.compat.Java8Api.listOf("OCR_SUSPECT")));
        Document incomplete=document("投标函\n正文内容完整。","[OCR_DEFERRED][OCR未完成]");
        Decision notClosed=evaluate(fixed,incomplete);
        assertEquals("UNDETERMINED",notClosed.status());
        assertEquals("TEXT_SCAN_COVERAGE_REQUIRED",notClosed.gaps().get(0).code());
    }

    @Test void historicalAndNavigationTextAreNeverUsedAsCurrentProjectAnomalies() {
        Document history=document(cn.bidcheck.compat.Java8Api.repeat("历史项目\n项日名称：旧工程\n合同协议书及历史说明。", 8));
        Rule rule=textRule(cn.bidcheck.compat.Java8Api.mapOf("textScope", "全文", "anomalyTypes", cn.bidcheck.compat.Java8Api.listOf("TYPO"), "typoDictionary", cn.bidcheck.compat.Java8Api.listOf("项日=>项目")));
        Decision result=evaluate(rule,history);
        assertEquals("UNDETERMINED",result.status());
        assertEquals("TEXT_SCAN_SCOPE_REQUIRED",result.gaps().get(0).code());
    }

    @Test void qualityAdvisoryComputesBodyAndDimensionCoverageWithoutModelScore() {
        Rule rule=qualityRule(cn.bidcheck.compat.Java8Api.listOf("建设目标", "现状分析"));
        Decision result=evaluate(rule,document(qualityText(true)));
        assertEquals("PASS",result.status());
        assertEquals("PROGRAM_STRUCTURAL_COVERAGE",result.proof().get("decisionBasis"));
        assertEquals(false,result.proof().get("scoreProduced"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.proof().get("missingDimensions"));
    }

    @Test void closedMaterialSectionWithMissingDimensionFailsButOutlineStaysUnknown() {
        Rule rule=qualityRule(cn.bidcheck.compat.Java8Api.listOf("建设目标", "现状分析"));
        Decision missing=evaluate(rule,document(qualityText(false)));
        assertEquals("FAIL",missing.status(),()->missing.map().toString());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("现状分析"),missing.proof().get("missingDimensions"));

        Decision outline=evaluate(rule,document("第一章 技术方案\n1. 建设目标\n2. 现状分析\n第二章 其他材料"));
        assertEquals("UNDETERMINED",outline.status());
        assertEquals("QUALITY_BODY_COVERAGE_REQUIRED",outline.gaps().get(0).code());
    }

    @Test void legacyModelQualityFactCanNoLongerProduceAConclusion() {
        Document document=document(qualityText(true));EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(document));
        Source source=corpus.all.get(0);Fact modelFact=new Fact("model","QUALITY","quality-rule","","优秀","优秀","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(source.id()),cn.bidcheck.compat.Java8Api.mapOf("ruleId", "quality-rule", "band", "优秀", "score", 100, "strengths", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("text", "好"))));
        Rule legacy=new Rule("quality-rule","质量评分","QUALITY","","quality","","","",cn.bidcheck.compat.Java8Api.mapOf("sections", cn.bidcheck.compat.Java8Api.listOf("技术方案"), "rubric", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("label", "优秀", "min", 90, "max", 100))),"质量评分","",cn.bidcheck.compat.Java8Api.listOf());
        Decision result=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(modelFact),corpus,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(legacy);
        assertEquals("UNDETERMINED",result.status());
        assertEquals("QUALITY_PROGRAM_RULE_REQUIRED",result.gaps().get(0).code());
    }

    private static Decision evaluate(Rule rule,Document document){return new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(document)),cn.bidcheck.compat.Java8Api.mapOf()).evaluate(rule);}
    private static Rule textRule(Map<String,Object> parameters){return new Rule("text-rule","文字异常检查","TEXT_ANOMALY","","textAnomaly","","","",parameters,"不得出现文字异常","",cn.bidcheck.compat.Java8Api.listOf());}
    private static Rule qualityRule(List<String> dimensions){return new Rule("quality-rule","技术方案维度覆盖","QUALITY_ADVISORY","","quality","","","",cn.bidcheck.compat.Java8Api.mapOf("materialType", "技术方案", "dimensions", dimensions),"技术方案应覆盖建设目标、现状分析","",cn.bidcheck.compat.Java8Api.listOf());}
    private static String qualityText(boolean includeSecond){return "第一章 技术方案\n1.1 建设目标\n本方案结合项目建设任务，明确总体目标、阶段目标、交付边界、责任分工和验收标准，并说明实施路径及风险控制措施，形成可执行的建设安排。各阶段设置量化里程碑、成果确认机制和责任到人要求，确保方案能够落地、跟踪和验收。\n"+(includeSecond?"1.2 现状分析\n通过现场资料、既有系统、业务流程和人员配置开展现状调查，识别关键矛盾、约束条件、接口关系及改造优先级，并形成对应处置方案。\n":"")+"第二章 项目团队\n项目团队按专业配置并明确职责。";}
    private static Document document(String text){return document(text,"");}
    private static Document document(String text,String warning){return new Document("bid","p","BID","投标文件",1,"sha","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","PDFBOX_NATIVE",warning)),cn.bidcheck.compat.Java8Api.listOf());}
}
