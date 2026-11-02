package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.SharedFactReuseTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedFactAdoptionAuditTest {
    @Test void processingRejectionsKeepSourceMappingAndNarrowProofReasonsAndMatchTheCounter(){
        var corpus=new EvidenceCorpus(List.of(doc("d","p",1,"报价：80元\n折扣：80元")));var helper=new SharedFactReuseTest();
        var malformed=helper.processingOccurrence(corpus,corpus.all.get(0),"报价：80元");var malformedFact=RuleEvaluator.maps(List.of(malformed.get("fact"))).get(0);malformedFact.put("sourceIds",List.of());malformed.put("fact",malformedFact);
        var changed=helper.processingOccurrence(corpus,corpus.all.get(0),"报价：80元");var source=RuleEvaluator.maps(changed.get("sources")).get(0);source.put("documentHash","changed");changed.put("sources",List.of(source));
        var ungrounded=helper.processingOccurrence(corpus,corpus.all.get(0),"折扣：80元");List<Map<String,Object>> audit=new ArrayList<>();
        var reuse=new SharedFactReuse(corpus,Map.of("processingOccurrences",List.of(malformed,changed,ungrounded),"sharedFactAdoptionAudit",(Consumer<Map<String,Object>>)audit::add),"model","engine");
        assertTrue(reuse.lookup(List.of(rule()),corpus.all,List.of()).isEmpty());assertEquals(3,reuse.rejectedFacts);
        var event=audit.get(0);assertEquals(3,event.get("processingRejectedCount"));assertEquals(3L,event.get("rejectedInThisLookup"));var rows=RuleEvaluator.maps(event.get("processingCandidates"));assertEquals(3,rows.size());
        assertEquals("PROCESSING_SOURCE_REFERENCE_INVALID",rows.get(0).get("reason"));assertEquals("PROCESSING_SOURCE_HASH_MISMATCH",RuleEvaluator.maps(rows.get(1).get("failures")).get(0).get("code"));assertEquals("PROCESSING_NARROW_PROOF_REJECTED",rows.get(2).get("reason"));assertFalse(RuleEvaluator.maps(rows.get(2).get("failures")).isEmpty());assertFalse(event.toString().contains("报价：80元"));
    }
    @Test void everyCandidateHasAReasonAndRepeatedReadsPointToTheOriginalDetailedAudit(){
        var corpus=new EvidenceCorpus(List.of(doc("d","p",1,"报价：80元","报价：70元")));
        Source first=corpus.all.get(0),other=corpus.all.get(1);
        var good=entry(corpus,number(first,"80"));
        var old=entry(corpus,number(first,"80"));old.put("engineVersion","old");
        var unrequestedFact=number(first,"80");unrequestedFact.put("kind","CERTIFICATE");
        var empty=number(first,"80");empty.put("sourceIds",List.of());
        var badManifest=entry(corpus,number(first,"80"));var source=source(corpus,first);source.put("documentHash","other-revision");badManifest.put("sources",List.of(source));
        var entries=List.of(good,good,old,entry(corpus,unrequestedFact),entry(corpus,empty),badManifest,entry(corpus,number(first,"90")),entry(corpus,number(other,"70")));
        List<Map<String,Object>> audit=new ArrayList<>();Map<String,Object> context=Map.of("sharedFactLookup",(Function<Map<String,Object>,?>)query->entries,"sharedFactAdoptionAudit",(Consumer<Map<String,Object>>)audit::add);
        var reuse=new SharedFactReuse(corpus,context,"model","engine");
        assertEquals(1,reuse.lookup(List.of(rule()),List.of(first),List.of(),"AFTER_FLIGHT").size());
        assertEquals(3,reuse.rejectedFacts);
        var event=audit.get(0);assertEquals("AFTER_FLIGHT",event.get("lookupPhase"));assertEquals("CANONICAL_SOURCE",event.get("lookupMode"));
        assertEquals(Map.of("ACCEPTED",1L,"DUPLICATE",1L,"SKIPPED",3L,"REJECTED",3L),event.get("outcomeCounts"));
        var rows=RuleEvaluator.maps(event.get("candidates"));assertEquals(8,rows.size());
        assertEquals("ENGINE_OR_OBSERVATION_POLICY_MISMATCH",rows.get(2).get("reason"));
        assertEquals("FACT_KIND_NOT_REQUESTED",rows.get(3).get("reason"));
        assertEquals("SOURCE_REFERENCE_EMPTY",rows.get(4).get("reason"));
        assertEquals("SOURCE_DOCUMENT_HASH_MISMATCH",RuleEvaluator.maps(rows.get(5).get("failures")).get(0).get("code"));
        assertEquals("RAW_VALUE_UNGROUNDED",RuleEvaluator.maps(rows.get(6).get("failures")).get(0).get("code"));
        assertEquals("SOURCE_NOT_SELECTED",rows.get(7).get("reason"));
        assertTrue(rows.stream().allMatch(row->row.containsKey("candidateId")&&row.containsKey("operationIdentity")&&row.containsKey("sourceIds")));
        assertFalse(event.toString().contains("报价：80元"));assertFalse(event.toString().contains("rawValue"));
        reuse.lookup(List.of(rule()),List.of(first),List.of(),"AFTER_FLIGHT");
        var repeated=audit.get(1);assertEquals(true,repeated.get("repeated"));assertEquals(event.get("auditId"),repeated.get("sameAsAuditId"));assertTrue(RuleEvaluator.maps(repeated.get("candidates")).isEmpty());assertEquals(event.get("acceptedFactIds"),repeated.get("acceptedFactIds"));
    }
    @Test void unrelatedSubjectIsSkippedWithItsLookupSelectorsInsteadOfCountedAsARejectedFact(){
        var corpus=new EvidenceCorpus(List.of(doc("d","p",1,"项目名称：北方中心办公楼装修工程\n投标文件\n本项目专职安全员：李明")));Source s=corpus.all.get(0);
        var fact=linked("id","safety-person","kind","PERSON_ROLE","subject","专职安全员","field","姓名","rawValue","李明","value","李明","unit","","basis","","scope","CURRENT_PROJECT","sourceIds",List.of(s.id()),"attributes",Map.of());
        List<Map<String,Object>> audit=new ArrayList<>();var reuse=new SharedFactReuse(corpus,Map.of("sharedFactLookup",(Function<Map<String,Object>,?>)q->List.of(entry(corpus,fact)),"sharedFactAdoptionAudit",(Consumer<Map<String,Object>>)audit::add),"model","engine");
        Rule target=new Rule("leader","负责人证书","CERTIFICATE","项目负责人","资格证书","","","",Map.of(),"项目负责人资格证书","",List.of());
        assertTrue(reuse.preloadSubjects(List.of(target),List.of()).isEmpty());assertEquals(0,reuse.rejectedFacts);
        var event=audit.get(0);assertEquals("CANONICAL_SUBJECT",event.get("lookupMode"));assertFalse(ProofVerifier.strings(event.get("subjects")).isEmpty());assertEquals("SUBJECT_NOT_REQUESTED",RuleEvaluator.maps(event.get("candidates")).get(0).get("reason"));
    }
}
