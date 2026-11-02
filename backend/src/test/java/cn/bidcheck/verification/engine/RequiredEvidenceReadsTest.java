package cn.bidcheck.verification.engine;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
class RequiredEvidenceReadsTest {
    @Test void acceptsOnlyPendingFrozenSourceAndReadOperation(){
        var host=EvidenceInvestigationTargetContractTest.host();var source=host.corpus.all.get(0);
        Map<String,Object> action=linked("tool","read_table_record","targetId","A","sourceId",source.id(),"documentId",source.evidence().documentId(),"documentHash",source.documentHash(),"documentVersion",source.documentVersion(),"page",source.evidence().page(),"locationType",source.evidence().locationType());
        java.util.function.Function<Map<String,Object>,List<Map<String,Object>>> select=value->RequiredEvidenceReads.actions(List.of(new Decision("A","待核","UNKNOWN","",List.of(),List.of(gap("NEED_TABLE","需读表")),Map.of("children",List.of(Map.of("proof",Map.of("requiredEvidenceActions",List.of(value))))))),Set.of("A"),host.corpus);
        assertEquals(1,select.apply(action).size());
        for(var change:Map.of("tool","submit_candidate_fact","targetId","B","documentHash","stale","documentVersion",2,"page",999,"locationType","BLOCK","sourceId","missing").entrySet()){
            var bad=new LinkedHashMap<>(action);bad.put(change.getKey(),change.getValue());assertTrue(select.apply(bad).isEmpty(),change.getKey());
        }
        assertTrue(RequiredEvidenceReads.actions(List.of(new Decision("A","完成","PASS","",List.of(),List.of(),Map.of("requiredEvidenceActions",List.of(action)))),Set.of("A"),host.corpus).isEmpty());
    }
}
