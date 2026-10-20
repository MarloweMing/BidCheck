package cn.bidcheck.service;

import cn.bidcheck.consistency.ConsistencyDerivedConclusion;
import cn.bidcheck.consistency.ConsistencyDoubtReviewer;
import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual source recomputation -> published finding boundary, without models or a database. */
class CheckDerivedConsistencyIntegrationTest {
    private record Fixture(Document document, Map<String,Object> report, Map<String,Object> group) {}
    private Page page(int number,String text){return new Page(number,text,"PAGE","OCR","");}
    private Document document(Page...pages){return new Document("bid","project","BID","synthetic.pdf",1,"source-sha","",List.of(pages),List.of());}
    private Map<String,Object> occurrence(Document d,int page,String quote,String field,String value,String id){
        Page p=d.pages().stream().filter(v->v.number()==page).findFirst().orElseThrow();
        return BidService.fields("id",id,"field",field,"value",value,"scope","CURRENT_PROJECT","subject","UNRESOLVED",
            "sourceOffset",p.text().indexOf(quote),"evidence",new Evidence(d.id(),d.name(),page,"PAGE",quote,"p"+page,0));
    }
    private Fixture fixture(Document d,String field,List<Map<String,Object>> occurrences){
        Map<String,Object> group=BidService.fields("id","group","findingId","finding","field",field,"fieldLabel",field,
            "status","EVIDENCE_INSUFFICIENT","title","缺少比较依据","occurrenceIds",occurrences.stream().map(v->v.get("id")).toList(),
            "evidences",occurrences.stream().map(v->v.get("evidence")).toList(),"comparisonContext",Map.of("scope","UNRESOLVED"),
            "targetedReview",Map.of("policyVersion",ConsistencyDoubtReviewer.VERSION,"status","LOCAL_RELATIONS_VERIFIED"));
        return new Fixture(d,BidService.fields("documentId",d.id(),"documentHash",d.sha256(),"occurrences",occurrences,"groups",List.of(group)),group);
    }
    private Fixture projects(){
        String name="2026年合成数据中心装修工程";
        Document d=document(page(1,"（"+name+"）\n投标文件（商务部分）"),page(9,"合同条款偏离表\n项目名称："+name),page(488,"技术偏离表\n项目名称："+name));
        return fixture(d,"projectName",List.of(occurrence(d,9,"项目名称："+name,"projectName",name,"a"),occurrence(d,488,"项目名称："+name,"projectName",name,"b")));
    }
    private Evaluation recompute(Fixture f){return ConsistencyDerivedConclusion.recompute(f.document(),f.report()).get("group");}
    private Finding pending(String title,Map<String,Object> assessment){
        Checkpoint cp=new Checkpoint("checkpoint",title,"全文信息检查","MEDIUM","FULL_TEXT_REVIEW","fullTextConsistency","REVIEW","核对同口径已识别记录","",true,true,true,null);
        return new Finding("finding","run",cp,new Evaluation("UNDETERMINED","原始扫描未能确定比较对象","",List.of(),0,"CONSISTENCY_RULE_SCAN"),assessment);
    }
    @Test void sourceRecomputedEvaluationIsPublishedAndRawAndHumanRecordsArePreserved(){
        Fixture f=projects(); Evaluation derived=recompute(f);assertEquals("PASS",derived.status());
        Map<String,Object> human=Map.of("status","DISAGREED","note","人工意见仍然有效","reviewId","manual-1");
        Finding original=pending("项目名称缺少比较依据",Map.of("humanReview",human,"manualRecords",List.of(human),"auditMarker","preserve"));
        Finding published=BidService.reviewedConsistencyFinding(original,f.group(),derived);
        assertEquals(derived,published.evaluation());assertEquals("PASS",published.evaluation().status());
        assertEquals("项目名称已识别记录一致",published.checkpoint().title());
        assertEquals(original.id(),published.id());assertEquals(original.runId(),published.runId());
        assertEquals(original.checkpoint().id(),published.checkpoint().id());assertEquals(original.checkpoint().expected(),published.checkpoint().expected());
        assertEquals(original.evaluation(),published.assessment().get("originalConsistencyEvaluation"));
        assertEquals(human,published.assessment().get("humanReview"));assertEquals(List.of(human),published.assessment().get("manualRecords"));
        assertEquals("preserve",published.assessment().get("auditMarker"));
        Map<String,Object> proof=CheckResolution.map(published.assessment().get("derivedConclusion"));
        assertEquals("OBSERVED_OCCURRENCES",proof.get("proofScope"));assertEquals(false,proof.get("documentCoverageComplete"));
        assertEquals(List.of("a","b"),proof.get("originalOccurrenceIds"));assertEquals(List.of("a","b"),proof.get("resolvedOccurrenceIds"));
        assertEquals("UNDETERMINED",original.evaluation().status());assertEquals("项目名称缺少比较依据",original.checkpoint().title());
        assertEquals("EVIDENCE_INSUFFICIENT",f.group().get("status"));assertFalse(original.assessment().containsKey("derivedConclusion"));
    }
    @Test void metadataOrModelSuggestionAloneCannotPublishPass(){
        Fixture f=projects();recompute(f);
        f.group().put("targetedReview",Map.of("policyVersion",ConsistencyDoubtReviewer.VERSION,"status","MODEL_REVIEWED","recommendation",Map.of("status","PASS")));
        Finding original=pending("项目名称缺少比较依据",Map.of());
        Finding published=BidService.reviewedConsistencyFinding(original,f.group());
        assertEquals(original.evaluation(),published.evaluation());assertEquals(original.checkpoint(),published.checkpoint());
        assertFalse(published.assessment().containsKey("derivedConclusion"));
    }
    @Test void oneUnresolvedOriginalOccurrenceStillPreventsParentPass(){
        Document d=document(page(1,"建筑业企业资质证书\n企业名称：甲公司\n证书编号：ABC12345"),page(2,"证书编号：DEF67890"));
        Fixture f=fixture(d,"certificateNumber",List.of(occurrence(d,1,"证书编号：ABC12345","certificateNumber","ABC12345","a"),occurrence(d,2,"证书编号：DEF67890","certificateNumber","DEF67890","b")));
        Evaluation derived=recompute(f);assertEquals("UNDETERMINED",derived.status());
        Finding original=pending("证书编号缺少比较依据",Map.of());Finding published=BidService.reviewedConsistencyFinding(original,f.group(),derived);
        assertEquals(derived,published.evaluation());assertEquals(original.checkpoint().title(),published.checkpoint().title());
        assertEquals(List.of("b"),CheckResolution.map(published.assessment().get("derivedConclusion")).get("unresolvedOccurrenceIds"));
        assertEquals(original.evaluation(),published.assessment().get("originalConsistencyEvaluation"));
    }
    @Test void sourceValidatedConflictPublishesRiskRatherThanObservedAgreement(){
        Document d=document(page(1,"建筑业企业资质证书\n企业名称：甲公司\n证书编号：ABC12345"),page(2,"建筑业企业资质证书\n企业名称：乙公司\n证书编号：ABC12345"));
        Fixture f=fixture(d,"certificateNumber",List.of(occurrence(d,1,"证书编号：ABC12345","certificateNumber","ABC12345","a"),occurrence(d,2,"证书编号：ABC12345","certificateNumber","ABC12345","b")));
        Evaluation derived=recompute(f);assertEquals("WARNING",derived.status());
        Finding original=pending("证书编号存在疑似差异",Map.of());Finding published=BidService.reviewedConsistencyFinding(original,f.group(),derived);
        assertEquals("WARNING",published.evaluation().status());assertEquals(original.checkpoint().title(),published.checkpoint().title());
        assertEquals(original.evaluation(),published.assessment().get("originalConsistencyEvaluation"));
    }
    @Test void actualRecomputeMustBelongToTheOriginalFindingAndCurrentPolicy(){
        Fixture f=projects();Evaluation derived=recompute(f);Finding original=pending("项目名称缺少比较依据",Map.of());
        f.group().put("findingId","another-finding");assertSame(original,BidService.reviewedConsistencyFinding(original,f.group(),derived));
        f.group().put("findingId",original.id());
        Map<String,Object> oldProof=new LinkedHashMap<>(CheckResolution.map(f.group().get("derivedConclusion")));oldProof.put("policyVersion","obsolete-policy");f.group().put("derivedConclusion",oldProof);
        assertEquals(original.evaluation(),BidService.reviewedConsistencyFinding(original,f.group(),derived).evaluation());
    }
    @Test void repeatedPublicationKeepsFirstRawEvaluationAndUnrelatedTitle(){
        Fixture f=projects();Evaluation derived=recompute(f);Finding original=pending("已识别项目记录核对",Map.of());
        Finding first=BidService.reviewedConsistencyFinding(original,f.group(),derived);
        Finding second=BidService.reviewedConsistencyFinding(first,f.group(),recompute(f));
        assertEquals(original.evaluation(),second.assessment().get("originalConsistencyEvaluation"));
        assertEquals(original.checkpoint().title(),second.checkpoint().title());
        assertEquals(first.evaluation(),second.evaluation());
    }
    @Test void localRecomputationClosesRecoveryPendingWithoutReusingOldConclusion(){
        Fixture f=projects();Evaluation derived=recompute(f);Finding base=pending("项目名称缺少比较依据",Map.of());
        Finding original=new Finding(base.id(),base.runId(),base.checkpoint(),new Evaluation("UNDETERMINED","等待重新核验","",List.of(),0,"RECOVERY_PENDING"),
            Map.of("inheritedFrom",Map.of("pending",true,"runId","parent","previousEvaluation",Map.of("status","PASS")),"manualRecords",List.of(Map.of("id","r1","status","REJECTED"))));
        Finding published=BidService.reviewedConsistencyFinding(original,f.group(),derived);
        Map<String,Object> inherited=CheckResolution.map(published.assessment().get("inheritedFrom"));
        assertEquals(false,inherited.get("pending"));assertEquals(true,inherited.get("rechecked"));assertEquals(false,inherited.get("reusedConclusion"));
        assertEquals("parent",inherited.get("runId"));assertEquals(Map.of("status","PASS"),inherited.get("previousEvaluation"));
        assertEquals(derived,published.evaluation());assertEquals(original.evaluation(),published.assessment().get("originalConsistencyEvaluation"));
        assertEquals(original.assessment().get("manualRecords"),published.assessment().get("manualRecords"));
        assertEquals(true,CheckResolution.map(published.assessment().get("consistencyRecoveryExecution")).get("recomputed"));
    }
}
