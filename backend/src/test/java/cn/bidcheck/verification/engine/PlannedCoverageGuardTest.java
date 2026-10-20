package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class PlannedCoverageGuardTest {
    private EvidenceCorpus corpus(String first) {
        return new EvidenceCorpus(List.of(new Document("bid","p","BID","bid.pdf",1,"sha","",List.of(
            new Page(1,first,"PAGE","PDFBOX_NATIVE",""),
            new Page(2,"附件扫描件","PAGE","PDFBOX_NATIVE","[OCR_DEFERRED][OCR未完成] 未纳入当前计划")),List.of())));
    }
    private Rule rule(String predicate,String field,String expected) {
        return new Rule("r","检查要求",predicate,"",field,"=",expected,"",Map.of(),"原条款","",List.of());
    }
    private VerificationTypes.Fact fact(EvidenceCorpus corpus,String kind,String field,String value,Map<String,Object> attributes) {
        return new VerificationTypes.Fact("f",kind,"",field,value,value,"","","CURRENT_PROJECT",List.of(corpus.all.get(0).id()),attributes);
    }
    @Test void unrecognizedAppendixCannotProveFullDocumentConsistency() {
        EvidenceCorpus corpus=corpus("项目编号：A001");
        var decision=new RuleEvaluator(List.of(fact(corpus,"FIELD","projectNumber","A001",Map.of())),corpus,Map.of()).evaluate(rule("SAME_FIELD","projectNumber","A001"));
        assertEquals("UNDETERMINED",decision.status());
        assertTrue(decision.gaps().stream().anyMatch(g->g.code().equals("OCR_PLAN_COVERAGE_REQUIRED")));
        assertEquals("PASS",decision.proof().get("decisionBeforeCoverageGuard"));
    }
    @Test void positiveMaterialEvidenceStillProvesItsLocalSubmissionObligation() {
        EvidenceCorpus corpus=corpus("承诺书\n本公司承诺履行义务，提交材料正文。");
        var decision=new RuleEvaluator(List.of(fact(corpus,"MATERIAL","","承诺书",Map.of("contentPresent",true))),corpus,Map.of()).evaluate(rule("MATERIAL","","承诺书"));
        assertEquals("PASS",decision.status());
    }
    @Test void filledBareRoleConfigurationDoesNotRequireUnrelatedImageAppendices(){
        EvidenceCorpus corpus=corpus("本项目专职安全员：张甲 身份证号码：110101199001010011");
        var person=new VerificationTypes.Fact("person","PERSON_ROLE","专职安全员","role","张甲","张甲","","","CURRENT_PROJECT",List.of(corpus.all.get(0).id()),Map.of());
        var rule=new Rule("count","岗位配置","PERSON_COUNT","专职安全员","personCount","","1","人",Map.of(),"专职安全员1人","",List.of());
        var decision=new RuleEvaluator(List.of(person),corpus,Map.of()).evaluate(rule);
        assertEquals("PASS",decision.status(),decision.toString());assertEquals("ROLE_CONFIGURATION_ONLY",decision.proof().get("proofScope"));assertEquals(false,decision.proof().get("exactCountProven"));
    }
    @Test void staffingShortageUpperBoundAndExactCountStillNeedFullCoverage(){
        EvidenceCorpus corpus=corpus("本项目专职安全员完整名单：张甲。\n本项目专职安全员：张甲 身份证号码：110101199001010011");
        var person=new VerificationTypes.Fact("person","PERSON_ROLE","专职安全员","role","张甲","张甲","","","CURRENT_PROJECT",List.of(corpus.all.get(0).id()),Map.of());
        for(String[] input:List.of(new String[]{"","2","专职安全员2人"},new String[]{"<=","1","专职安全员不超过1人"},new String[]{"=","1","专职安全员恰好1人"})){
            var rule=new Rule("count","岗位配置","PERSON_COUNT","专职安全员","personCount",input[0],input[1],"人",Map.of(),input[2],"",List.of());
            var decision=new RuleEvaluator(List.of(person),corpus,Map.of()).evaluate(rule);
            assertEquals("UNDETERMINED",decision.status(),decision.toString());assertTrue(decision.gaps().stream().anyMatch(g->g.code().equals("OCR_PLAN_COVERAGE_REQUIRED")),decision.toString());
        }
    }
    @Test void missingMaterialRemainsUnknownWithDeferredImages() {
        EvidenceCorpus corpus=corpus("商务响应正文");
        var decision=new RuleEvaluator(List.of(),corpus,Map.of()).evaluate(rule("MATERIAL","","审计报告"));
        assertEquals("UNDETERMINED",decision.status());
    }
    @Test void sectionRangeCannotBeCompleteWithUnreadImageInside() {
        EvidenceCorpus corpus=corpus("1 技术方案\n"+"技术措施与资源配置说明。".repeat(35));
        assertFalse(SectionCoverage.locate("技术方案",corpus).complete());
    }
    @Test void incompleteSectionCannotBorrowCompleteSameNamedSectionFromAnotherFile() {
        String text="1 技术方案\n"+"技术措施与资源配置说明。".repeat(35);
        Document complete=new Document("complete","p","BID","a.pdf",1,"shaA","",List.of(new Page(1,text,"PAGE","PDFBOX_NATIVE","")),List.of());
        Document partial=new Document("partial","p","BID","b.pdf",1,"shaB","",List.of(new Page(1,text,"PAGE","PDFBOX_NATIVE","[OCR_DEFERRED][OCR未完成]")),List.of());
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(complete,partial));
        String source=corpus.all.stream().filter(s->s.evidence().documentId().equals("partial")).findFirst().orElseThrow().id();
        var section=new VerificationTypes.Fact("f","SECTION","","","技术方案","技术方案","","","CURRENT_PROJECT",List.of(source),Map.of());
        assertFalse(SectionCoverage.covered(section,corpus,corpus.all));
    }
    @Test void unlocatedWordImageStopsFullSectionCoverageClaim() {
        String text="1 技术方案\n"+"技术措施与资源配置说明。".repeat(35);
        Document doc=new Document("word","p","BID","a.docx",1,"sha","",List.of(new Page(1,text,"PARAGRAPH","DOCX_NATIVE","")),List.of("[OCR未完成] Word 文件有未定位到正文块的图像"));
        assertFalse(SectionCoverage.locate("技术方案",new EvidenceCorpus(List.of(doc))).complete());
    }
    @Test void twoProvenConflictingValuesKeepNegativeDecisionDespiteDeferredAppendix() {
        EvidenceCorpus corpus=corpus("项目编号：A001\n项目编号：A002");
        var a=fact(corpus,"FIELD","projectNumber","A001",Map.of());
        var b=new VerificationTypes.Fact("g","FIELD","","projectNumber","A002","A002","","","CURRENT_PROJECT",a.sourceIds(),Map.of());
        assertEquals("FAIL",new RuleEvaluator(List.of(a,b),corpus,Map.of()).evaluate(rule("SAME_FIELD","projectNumber","A001")).status());
    }
}
