package cn.bidcheck.verification.engine;

import cn.bidcheck.consistency.ConsistencyDerivedConclusion;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmedConsistencyRelationTest {
    Document document(){return new Document("bid","project","BID","bid.pdf",1,"source-hash","",List.of(new Page(1,"项目名称：北方办公楼装修工程\n投标文件（商务部分）","PAGE","TEXT",""),new Page(2,"本项目人员配置\n项目负责人：李明","PAGE","TEXT",""),new Page(3,"本项目人员配置\n项目负责人：李明","PAGE","TEXT",""),new Page(4,"本项目人员配置\n项目负责人：李明","PAGE","TEXT","")),List.of());}
    Map<String,Object> occurrence(Document d,int page,String id){Page p=d.pages().stream().filter(x->x.number()==page).findFirst().orElseThrow();return linked("id",id,"field","personName","value","李明","subject","项目负责人","scope","CURRENT_PROJECT","sourceOffset",0,"evidence",new Evidence(d.id(),d.name(),page,"PAGE",p.text(),"chars:0-"+p.text().length(),1));}
    List<Map<String,Object>> confirmed(Document d){var c=new EvidenceCorpus(List.of(d));var selected=c.all.stream().filter(s->Set.of(2,3).contains(s.evidence().page())).toList();var citations=selected.stream().map(s->linked("sourceId",s.id(),"documentId",d.id(),"documentHash",d.sha256(),"documentVersion",d.version(),"page",s.evidence().page(),"locationType",s.evidence().locationType(),"quote",s.evidence().quote())).toList();var proposed=linked("kind","RELATION","subject","李明","field","同一人","rawValue","李明","value","李明","scope","CURRENT_PROJECT","sourceIds",selected.stream().map(Source::id).toList(),"attributes",Map.of("object","李明"));var record=linked("id","claim","status","CONFIRMED","actor","reviewer","decidedAt","2026-11-02","confirmationLedgerId","ledger-claim","revision","v1","targetId","identity","targetFingerprint","target-hash","proposedFact",proposed,"evidences",citations);return ConfirmedInferenceEvidence.validated(List.of(record),List.of(d));}
    Map<String,Object> report(Document d,List<Map<String,Object>> occurrences,List<Map<String,Object>> relations){return linked("documentId",d.id(),"documentHash",d.sha256(),"occurrences",occurrences,"confirmedEvidenceRelations",relations,"groups",List.of(linked("id","group","field","personName","status","EVIDENCE_INSUFFICIENT","occurrenceIds",occurrences.stream().map(o->o.get("id")).toList())));}
    @Test void confirmedExactPairIsUsedWithoutApprovingAnotherSameNamePage(){
        var d=document();var relation=confirmed(d);assertEquals(1,relation.size());assertTrue(ConfirmedInferenceEvidence.isValidated(relation.get(0)));var pair=report(d,List.of(occurrence(d,2,"a"),occurrence(d,3,"b")),relation);assertEquals("PASS",ConsistencyDerivedConclusion.recompute(d,pair).get("group").status());assertEquals("EVIDENCE_INSUFFICIENT",RuleEvaluator.maps(pair.get("groups")).get(0).get("status"));
        var additional=report(d,List.of(occurrence(d,2,"a"),occurrence(d,3,"b"),occurrence(d,4,"c")),relation);assertEquals("UNDETERMINED",ConsistencyDerivedConclusion.recompute(d,additional).get("group").status());
    }
    @Test void tamperedReceiptAndHistoryCannotUseAnOtherwiseConfirmedEdge(){
        var d=document();var relation=new LinkedHashMap<>(confirmed(d).get(0));relation.put("subject","王强");assertFalse(ConfirmedInferenceEvidence.isValidated(relation));assertEquals("UNDETERMINED",ConsistencyDerivedConclusion.recompute(d,report(d,List.of(occurrence(d,2,"a")),List.of(relation))).get("group").status());
        var old=occurrence(d,2,"history");old.put("scope","HISTORICAL_PROJECT");assertEquals("UNDETERMINED",ConsistencyDerivedConclusion.recompute(d,report(d,List.of(old),confirmed(d))).get("group").status());
    }
    @Test void originalWarningIsNotOverwrittenByConfirmedIdentity(){var d=document();var report=report(d,List.of(occurrence(d,2,"a"),occurrence(d,3,"b")),confirmed(d));var group=cn.bidcheck.consistency.ConsistencyRunSupport.maps(report.get("groups")).get(0);group.put("status","WARNING");assertTrue(ConsistencyDerivedConclusion.recompute(d,report).isEmpty());assertEquals("WARNING",group.get("status"));}
}
