package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class PlannedCoverageGuardTest {
    private EvidenceCorpus corpus(String first) {
        return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("bid","p","BID","bid.pdf",1,"sha","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,first,"PAGE","PDFBOX_NATIVE",""), new Page(2,"附件扫描件","PAGE","PDFBOX_NATIVE","[OCR_DEFERRED][OCR未完成] 未纳入当前计划")),cn.bidcheck.compat.Java8Api.listOf())));
    }
    private Rule rule(String predicate,String field,String expected) {
        return new Rule("r","检查要求",predicate,"",field,"=",expected,"",cn.bidcheck.compat.Java8Api.mapOf(),"原条款","",cn.bidcheck.compat.Java8Api.listOf());
    }
    private VerificationTypes.Fact fact(EvidenceCorpus corpus,String kind,String field,String value,Map<String,Object> attributes) {
        return new VerificationTypes.Fact("f",kind,"",field,value,value,"","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0).id()),attributes);
    }
    @Test void unrecognizedAppendixCannotProveFullDocumentConsistency() {
        EvidenceCorpus corpus=corpus("项目编号：A001");
        cn.bidcheck.verification.engine.VerificationTypes.Decision decision=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(fact(corpus,"FIELD","projectNumber","A001",cn.bidcheck.compat.Java8Api.mapOf())),corpus,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(rule("SAME_FIELD","projectNumber","A001"));
        assertEquals("UNDETERMINED",decision.status());
        assertTrue(decision.gaps().stream().anyMatch(g->g.code().equals("OCR_PLAN_COVERAGE_REQUIRED")));
        assertEquals("PASS",decision.proof().get("decisionBeforeCoverageGuard"));
    }
    @Test void positiveMaterialEvidenceStillProvesItsLocalSubmissionObligation() {
        EvidenceCorpus corpus=corpus("承诺书\n本公司承诺履行义务，提交材料正文。");
        cn.bidcheck.verification.engine.VerificationTypes.Decision decision=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(fact(corpus,"MATERIAL","","承诺书",cn.bidcheck.compat.Java8Api.mapOf("contentPresent", true))),corpus,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(rule("MATERIAL","","承诺书"));
        assertEquals("PASS",decision.status());
    }
    @Test void filledBareRoleConfigurationDoesNotRequireUnrelatedImageAppendices(){
        EvidenceCorpus corpus=corpus("本项目专职安全员：张甲 身份证号码：110101199001010011");
        cn.bidcheck.verification.engine.VerificationTypes.Fact person=new VerificationTypes.Fact("person","PERSON_ROLE","专职安全员","role","张甲","张甲","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf());
        cn.bidcheck.verification.engine.VerificationTypes.Rule rule=new Rule("count","岗位配置","PERSON_COUNT","专职安全员","personCount","","1","人",cn.bidcheck.compat.Java8Api.mapOf(),"专职安全员1人","",cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.VerificationTypes.Decision decision=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(person),corpus,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(rule);
        assertEquals("PASS",decision.status(),decision.toString());assertEquals("ROLE_CONFIGURATION_ONLY",decision.proof().get("proofScope"));assertEquals(false,decision.proof().get("exactCountProven"));
    }
    @Test void staffingShortageUpperBoundAndExactCountStillNeedFullCoverage(){
        EvidenceCorpus corpus=corpus("本项目专职安全员完整名单：张甲。\n本项目专职安全员：张甲 身份证号码：110101199001010011");
        cn.bidcheck.verification.engine.VerificationTypes.Fact person=new VerificationTypes.Fact("person","PERSON_ROLE","专职安全员","role","张甲","张甲","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf());
        for(String[] input:cn.bidcheck.compat.Java8Api.listOf(new String[]{"","2","专职安全员2人"}, new String[]{"<=","1","专职安全员不超过1人"}, new String[]{"=","1","专职安全员恰好1人"})){
            cn.bidcheck.verification.engine.VerificationTypes.Rule rule=new Rule("count","岗位配置","PERSON_COUNT","专职安全员","personCount",input[0],input[1],"人",cn.bidcheck.compat.Java8Api.mapOf(),input[2],"",cn.bidcheck.compat.Java8Api.listOf());
            cn.bidcheck.verification.engine.VerificationTypes.Decision decision=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(person),corpus,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(rule);
            assertEquals("UNDETERMINED",decision.status(),decision.toString());assertTrue(decision.gaps().stream().anyMatch(g->g.code().equals("OCR_PLAN_COVERAGE_REQUIRED")),decision.toString());
        }
    }
    @Test void missingMaterialRemainsUnknownWithDeferredImages() {
        EvidenceCorpus corpus=corpus("商务响应正文");
        cn.bidcheck.verification.engine.VerificationTypes.Decision decision=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),corpus,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(rule("MATERIAL","","审计报告"));
        assertEquals("UNDETERMINED",decision.status());
    }
    @Test void sectionRangeCannotBeCompleteWithUnreadImageInside() {
        EvidenceCorpus corpus=corpus("1 技术方案\n"+cn.bidcheck.compat.Java8Api.repeat("技术措施与资源配置说明。", 35));
        assertFalse(SectionCoverage.locate("技术方案",corpus).complete());
    }
    @Test void incompleteSectionCannotBorrowCompleteSameNamedSectionFromAnotherFile() {
        String text="1 技术方案\n"+cn.bidcheck.compat.Java8Api.repeat("技术措施与资源配置说明。", 35);
        Document complete=new Document("complete","p","BID","a.pdf",1,"shaA","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","PDFBOX_NATIVE","")),cn.bidcheck.compat.Java8Api.listOf());
        Document partial=new Document("partial","p","BID","b.pdf",1,"shaB","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","PDFBOX_NATIVE","[OCR_DEFERRED][OCR未完成]")),cn.bidcheck.compat.Java8Api.listOf());
        EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(complete, partial));
        String source=corpus.all.stream().filter(s->s.evidence().documentId().equals("partial")).findFirst().orElseThrow(java.util.NoSuchElementException::new).id();
        cn.bidcheck.verification.engine.VerificationTypes.Fact section=new VerificationTypes.Fact("f","SECTION","","","技术方案","技术方案","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(source),cn.bidcheck.compat.Java8Api.mapOf());
        assertFalse(SectionCoverage.covered(section,corpus,corpus.all));
    }
    @Test void unlocatedWordImageStopsFullSectionCoverageClaim() {
        String text="1 技术方案\n"+cn.bidcheck.compat.Java8Api.repeat("技术措施与资源配置说明。", 35);
        Document doc=new Document("word","p","BID","a.docx",1,"sha","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PARAGRAPH","DOCX_NATIVE","")),cn.bidcheck.compat.Java8Api.listOf("[OCR未完成] Word 文件有未定位到正文块的图像"));
        assertFalse(SectionCoverage.locate("技术方案",new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(doc))).complete());
    }
    @Test void twoProvenConflictingValuesKeepNegativeDecisionDespiteDeferredAppendix() {
        EvidenceCorpus corpus=corpus("项目编号：A001\n项目编号：A002");
        cn.bidcheck.verification.engine.VerificationTypes.Fact a=fact(corpus,"FIELD","projectNumber","A001",cn.bidcheck.compat.Java8Api.mapOf());
        cn.bidcheck.verification.engine.VerificationTypes.Fact b=new VerificationTypes.Fact("g","FIELD","","projectNumber","A002","A002","","","CURRENT_PROJECT",a.sourceIds(),cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals("FAIL",new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(a, b),corpus,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(rule("SAME_FIELD","projectNumber","A001")).status());
    }
}
