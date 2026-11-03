package cn.bidcheck.verification.engine;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
class RequiredEvidenceReadsTest {
    @Test void acceptsOnlyPendingFrozenSourceAndReadOperation(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=EvidenceInvestigationTargetContractTest.host();cn.bidcheck.verification.engine.VerificationTypes.Source source=host.corpus.all.get(0);
        Map<String,Object> action=linked("tool","read_table_record","targetId","A","sourceId",source.id(),"documentId",source.evidence().documentId(),"documentHash",source.documentHash(),"documentVersion",source.documentVersion(),"page",source.evidence().page(),"locationType",source.evidence().locationType());
        java.util.function.Function<Map<String,Object>,List<Map<String,Object>>> select=value->RequiredEvidenceReads.actions(cn.bidcheck.compat.Java8Api.listOf(new Decision("A","待核","UNKNOWN","",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(gap("NEED_TABLE","需读表")),cn.bidcheck.compat.Java8Api.mapOf("children", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("proof", cn.bidcheck.compat.Java8Api.mapOf("requiredEvidenceActions", cn.bidcheck.compat.Java8Api.listOf(value))))))),cn.bidcheck.compat.Java8Api.setOf("A"),host.corpus);
        assertEquals(1,select.apply(action).size());
        for(java.util.Map.Entry<java.lang.String, ?> change:cn.bidcheck.compat.Java8Api.mapOf("tool", "submit_candidate_fact", "targetId", "B", "documentHash", "stale", "documentVersion", 2, "page", 999, "locationType", "BLOCK", "sourceId", "missing").entrySet()){
            java.util.LinkedHashMap<java.lang.String, java.lang.Object> bad=new LinkedHashMap<>(action);bad.put(change.getKey(),change.getValue());assertTrue(select.apply(bad).isEmpty(),change.getKey());
        }
        assertTrue(RequiredEvidenceReads.actions(cn.bidcheck.compat.Java8Api.listOf(new Decision("A","完成","PASS","",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("requiredEvidenceActions", cn.bidcheck.compat.Java8Api.listOf(action)))),cn.bidcheck.compat.Java8Api.setOf("A"),host.corpus).isEmpty());
    }
}
