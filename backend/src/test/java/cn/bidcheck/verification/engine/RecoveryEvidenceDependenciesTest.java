package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryEvidenceDependenciesTest {
    @Test void incompleteFactWindowCannotBecomePassButDoesNotEraseEstablishedRisk(){
        Decision pass=new Decision("r","要求","PASS","已满足",List.of(),List.of(),Map.of("sourceVerified",true));
        var guarded=VerificationEngine.lookupCoverageGuard(pass,true,List.of(Map.of("phase","SELECTED_SOURCES")));
        assertEquals("UNDETERMINED",guarded.status());assertEquals("EVIDENCE_LOOKUP_INCOMPLETE",guarded.gaps().get(0).code());
        assertEquals(pass,VerificationEngine.lookupCoverageGuard(pass,false,List.of()));
        Decision risk=new Decision("r","要求","FAIL","明确反证",List.of(),List.of(),Map.of("sourceVerified",true));
        assertEquals("FAIL",VerificationEngine.lookupCoverageGuard(risk,true,List.of()).status());
    }
    @Test void targetedReadCannotAdvanceTheRevisionOfAnUnvisitedObligation(){
        Rule first=new Rule("first","报价","NUMBER","甲公司","totalPrice","<=","100","元",Map.of(),"不超过100元","",List.of());
        Rule second=new Rule("second","资质","CERTIFICATE","甲公司","","","","",Map.of("category","工程设计资质"),"工程设计资质","",List.of());
        var evaluator=new RuleEvaluator(List.of(),new EvidenceCorpus(List.of()),Map.of());
        Map<String,Object> context=Map.of("ordinaryRecoveryObservedRevision",9L,"previousAtomicCheck",Map.of("recoveryEvidenceDependencies",List.of(Map.of("obligationId","second","observedRevision",3L,"sourceIds",List.of("source-old")))));
        var deps=VerificationEngine.evidenceDependencies(List.of(first,second),List.of(first),List.of(),context,"model",evaluator);
        assertEquals(9L,deps.get(0).get("observedRevision"));assertEquals(3L,deps.get(1).get("observedRevision"));
        assertEquals(List.of("source-old"),deps.get(1).get("sourceIds"));assertEquals(false,deps.get(1).get("businessProof"));
        assertNotEquals(deps.get(0).get("observationKey"),deps.get(1).get("observationKey"));
    }
}
