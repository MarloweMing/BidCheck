package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.SourceRequirementInventory;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.fields;

class RequirementFailureOwnershipTest {
    private Map<String,Object> gap(){return fields("code","AGENT_INVESTIGATION_FAILED","description","子项模型协议错误",
        "requiredInput","原子字段","resumeTrigger","MODEL_PROTOCOL_RECOVERED");}
    private Finding parent(Map<String,Object> proof,Map<String,Object> extra){
        Checkpoint cp=new Checkpoint("parent","形式完整性","形式","HIGH","SEMANTIC_RESPONSE","","","形式要求","",true,true,true,null);
        Map<String,Object> atomic=fields("proof",proof,"gaps",Arrays.asList(gap()),"complete",false);atomic.putAll(extra);
        return new Finding("fp","r",cp,new Evaluation("UNDETERMINED","保留子项诊断","",Collections.emptyList(),0,"RULE"),fields("atomicCheck",atomic));
    }
    private Map<String,Object> proof(){return fields("executor",SourceRequirementInventory.VERSION,"dependencyCheckpointIds",Arrays.asList("child"),
        "dependencyGapOwnership",fields("version",SourceRequirementInventory.FAILURE_OWNERSHIP_VERSION,"authority","CURRENT_SERVER_PROOF",
            "ownerCheckpointId","parent","runId","r","businessProof",false,"origins",Arrays.asList(fields("sourceCheckpointId","child",
                "sourceFindingId","fc","sourceRunId","r","gap",gap()))),
        "children",Arrays.asList(fields("id","child","gaps",Arrays.asList(gap()),"proof",fields("sourceCheckpointId","child","sourceFindingId","fc","runId","r"))));}
    @Test void inheritedFailureIsRetainedButOnlyTheSourceChildOwnsItsRetry(){
        Finding parent=parent(proof(),Collections.emptyMap());
        assertFalse(RecoveryTransition.hasProcessingFailure(parent));
        assertEquals(Arrays.asList("child"),RequirementFailureOwnership.failedDependencies(parent));
        assertEquals("WAIT_REQUIREMENT_DEPENDENCIES",CheckResolution.plan(parent).get("strategy"));
        assertEquals("AGENT_INVESTIGATION_FAILED",CheckResolution.maps(CheckResolution.atomic(parent).get("gaps")).get(0).get("code"));
    }
    @Test void missingVolumeStillHasItsIndependentUploadRoute(){
        Finding parent=parent(proof(),fields("gaps",Arrays.asList(gap(),fields("code","BID_VOLUME_NOT_UPLOADED"))));
        assertEquals("WAIT_VOLUME_UPLOAD",CheckResolution.plan(parent).get("strategy"));
        assertFalse(RecoveryTransition.hasProcessingFailure(parent));
    }
    @Test void anIndependentModelFailureCannotBeHiddenByTheDependencyReceipt(){
        Finding parent=parent(proof(),fields("modelDiagnostics",fields("unresolvedFailures",1,"failures",Arrays.asList(fields("errorCode","MODEL_TIMEOUT","retryable",true)))));
        assertTrue(RecoveryTransition.hasProcessingFailure(parent));
        assertNotEquals("WAIT_REQUIREMENT_DEPENDENCIES",CheckResolution.plan(parent).get("strategy"));
    }
    @Test void sameDiagnosticInAnIndependentLocalConditionStillBelongsToParent(){
        Finding parent=parent(proof(),fields("conditions",Arrays.asList(fields("id","local","gaps",Arrays.asList(gap()),"proof",Collections.emptyMap()))));
        assertTrue(RecoveryTransition.hasProcessingFailure(parent));
    }
    @Test void missingStaleOrUnlinkedReceiptDoesNotSuppressAnError(){
        assertTrue(RecoveryTransition.hasProcessingFailure(parent(Collections.emptyMap(),Collections.emptyMap())));
        Map<String,Object> wrong=proof();((Map<String,Object>)wrong.get("dependencyGapOwnership")).put("runId","old");
        assertTrue(RecoveryTransition.hasProcessingFailure(parent(wrong,Collections.emptyMap())));
        wrong=proof();wrong.put("children",Collections.emptyList());
        assertTrue(RecoveryTransition.hasProcessingFailure(parent(wrong,Collections.emptyMap())));
        wrong=proof();wrong.put("dependencyCheckpointIds",Arrays.asList("other"));
        assertTrue(RecoveryTransition.hasProcessingFailure(parent(wrong,Collections.emptyMap())));
    }
    @Test void conditionLevelInventoryReceiptIsAlsoRecognized(){
        Map<String,Object> proof=proof();
        Finding parent=parent(Collections.emptyMap(),fields("conditions",Arrays.asList(fields("gaps",Arrays.asList(gap()),"proof",proof))));
        assertFalse(RecoveryTransition.hasProcessingFailure(parent));
        assertEquals("WAIT_REQUIREMENT_DEPENDENCIES",CheckResolution.plan(parent).get("strategy"));
    }
}
