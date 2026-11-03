package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryEvidenceDependenciesTest {
    @Test void incompleteFactWindowCannotBecomePassButDoesNotEraseEstablishedRisk(){
        Decision pass=new Decision("r","要求","PASS","已满足",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("sourceVerified", true));
        cn.bidcheck.verification.engine.VerificationTypes.Decision guarded=VerificationEngine.lookupCoverageGuard(pass,true,cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("phase", "SELECTED_SOURCES")));
        assertEquals("UNDETERMINED",guarded.status());assertEquals("EVIDENCE_LOOKUP_INCOMPLETE",guarded.gaps().get(0).code());
        assertEquals(pass,VerificationEngine.lookupCoverageGuard(pass,false,cn.bidcheck.compat.Java8Api.listOf()));
        Decision risk=new Decision("r","要求","FAIL","明确反证",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("sourceVerified", true));
        assertEquals("FAIL",VerificationEngine.lookupCoverageGuard(risk,true,cn.bidcheck.compat.Java8Api.listOf()).status());
    }
    @Test void targetedReadCannotAdvanceTheRevisionOfAnUnvisitedObligation(){
        Rule first=new Rule("first","报价","NUMBER","甲公司","totalPrice","<=","100","元",cn.bidcheck.compat.Java8Api.mapOf(),"不超过100元","",cn.bidcheck.compat.Java8Api.listOf());
        Rule second=new Rule("second","资质","CERTIFICATE","甲公司","","","","",cn.bidcheck.compat.Java8Api.mapOf("category", "工程设计资质"),"工程设计资质","",cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.RuleEvaluator evaluator=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.mapOf());
        Map<String,Object> context=cn.bidcheck.compat.Java8Api.mapOf("ordinaryRecoveryObservedRevision", 9L, "previousAtomicCheck", cn.bidcheck.compat.Java8Api.mapOf("recoveryEvidenceDependencies", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("obligationId", "second", "observedRevision", 3L, "sourceIds", cn.bidcheck.compat.Java8Api.listOf("source-old")))));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> deps=VerificationEngine.evidenceDependencies(cn.bidcheck.compat.Java8Api.listOf(first, second),cn.bidcheck.compat.Java8Api.listOf(first),cn.bidcheck.compat.Java8Api.listOf(),context,"model",evaluator);
        assertEquals(9L,deps.get(0).get("observedRevision"));assertEquals(3L,deps.get(1).get("observedRevision"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("source-old"),deps.get(1).get("sourceIds"));assertEquals(false,deps.get(1).get("businessProof"));
        assertNotEquals(deps.get(0).get("observationKey"),deps.get(1).get("observationKey"));
    }
}
