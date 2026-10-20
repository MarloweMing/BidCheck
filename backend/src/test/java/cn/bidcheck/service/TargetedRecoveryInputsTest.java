package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.fields;

class TargetedRecoveryInputsTest {
    private Finding finding(Map<String,Object> atomic){
        Checkpoint cp=new Checkpoint("cp","设计负责人资格要求","资格检查","HIGH","SEMANTIC_RESPONSE","person","EXISTS","查询截图与证书信息一致","",true,true,true,null);
        return new Finding("finding","run",cp,new Evaluation("UNDETERMINED","字段对应未完成","",List.of(),0,"FOCUSED_ATOMIC_RULES"),Map.of("atomicCheck",atomic));
    }
    private Map<String,Object> condition(String id,String status){return fields("id",id,"conditionRole","REQUIREMENT","status",status,"gaps",List.of());}
    private Map<String,Object> rule(String id,String expected){return fields("id",id,"description","不同说明不改规则","predicate","SAME_FIELD","subject","设计负责人","field","注册编号","operator","EQ","expected",expected,"unit","","sourceQuote","查询截图与证明文件信息一致","parameters",Map.of(),"children",List.of());}
    @Test void declaredRepairQueuesLocalRecomputationInsteadOfImages(){
        Finding before=finding(fields("gaps",List.of(Map.of("code","REQUIREMENT_COMPARISON_TARGET_UNRESOLVED")),"compilationAudit",Map.of("state","VALIDATED","repairAttempts",0),
            "recoveryActions",List.of(fields("strategy","REPAIR_REQUIREMENT","obligationId","R5","reasonCode","REQUIREMENT_COMPARISON_TARGET_UNRESOLVED")),
            "visualRequests",List.of(Map.of("page",149,"obligationId","R5")),"conditions",List.of(condition("R1","PASS"),condition("R5","UNDETERMINED"))));
        assertEquals("REPAIR_REQUIREMENT",CheckResolution.plan(before).get("strategy"));assertEquals("QUEUED",CheckResolution.plan(before).get("status"));
        Map<String,Object> context=RecoveryInputs.context(before,"REPAIR_REQUIREMENT",Map.of("priorSteps",List.of()));
        assertEquals("RECOMPUTE_ONLY",context.get("recoveryMode"));assertEquals(false,context.get("recoveryModelAllowed"));
        assertEquals(List.of("R5"),context.get("recoveryTargetObligationIds"));assertEquals(CheckResolution.atomic(before),context.get("previousAtomicCheck"));
    }
    @Test void exhaustedCompilerOrUnsupportedCapabilityIsNotQueuedAgain(){
        Map<String,Object> atomic=fields("compilationAudit",Map.of("state","UNRESOLVED","repairAttempts",1),"recoveryActions",List.of(fields("strategy","REPAIR_REQUIREMENT","reasonCode","RULE_VALIDATION_FAILED")));
        assertEquals("BLOCKED",CheckResolution.plan(finding(atomic)).get("strategy"));
        atomic.put("compilationAudit",Map.of("state","UNRESOLVED","repairAttempts",0));
        atomic.put("recoveryActions",List.of(fields("strategy","REPAIR_REQUIREMENT","reasonCode","CAPABILITY_UNRESOLVED")));
        assertEquals("BLOCKED",CheckResolution.plan(finding(atomic)).get("strategy"));
    }
    @Test void compilationCooldownCannotFallThroughToImagesOrLocalRepair(){
        Finding before=finding(fields("compilationAudit",fields("state","REPAIR_RETRY_PENDING","repairAttempts",0,"retryNotBeforeEpochMillis",Long.MAX_VALUE),
            "recoveryActions",List.of(Map.of("strategy","REPAIR_REQUIREMENT","reasonCode","RULE_VALIDATION_FAILED")),"visualRequests",List.of(Map.of("page",149))));
        assertEquals("BLOCKED",CheckResolution.plan(before).get("strategy"));assertEquals("FAILED",CheckResolution.plan(before).get("status"));
        assertEquals(Long.MAX_VALUE,CheckResolution.plan(before).get("retryNotBeforeEpochMillis"));
    }
    @Test void pageRecoveryTargetsPendingNodesAndExcludesCompletedObservations(){
        Finding before=finding(fields("conditions",List.of(condition("R1","PASS"),condition("R5","UNDETERMINED")),
            "visualRequests",List.of(Map.of("page",149,"obligationId","R1"),Map.of("page",150,"obligationId","R5"))));
        Map<String,Object> context=RecoveryInputs.context(before,"VERIFY_SOURCE_PAGES",Map.of());
        assertEquals(List.of("R5"),context.get("recoveryTargetObligationIds"));assertEquals("RECOMPUTE_ONLY",context.get("recoveryMode"));
        assertEquals(List.of(Map.of("page",150,"obligationId","R5")),RecoveryInputs.visualRequest(before).get("visualRequests"));
    }
    private Map<String,Object> observation(String id,String value){return fields("id",id,"savedAt",id,"documentId","bid","page",150,"locationType","PAGE","obligationId","R5","type","FIELD","value",value,
        "source",fields("documentId","bid","documentHash","hash","documentVersion",1,"page",150,"renderedSha256","imagehash"));}
    @Test void newReceiptIdsDoNotMakeOldObservationsNewEvidence(){
        Map<String,Object> before=observation("one","证书编号：A123");Map<String,Object> same=observation("two","证书编号：A123");
        assertTrue(RecoveryInputs.changedObservations(List.of(before),List.of(same)).isEmpty());
        Map<String,Object> changed=observation("three","证书编号：A124");List<Map<String,Object>> added=RecoveryInputs.changedObservations(List.of(before),List.of(same,changed,changed));
        assertEquals(List.of(changed),added);Map<String,Object> context=new LinkedHashMap<>();RecoveryInputs.changedInputs(context,added);
        assertEquals(List.of("R5"),context.get("recoveryTargetObligationIds"));assertEquals(1,context.get("recoveryNewObservationCount"));
        Map<String,Object> location=CheckResolution.maps(context.get("recoveryChangedLocations")).get(0);assertEquals(150,location.get("location"));assertEquals("hash",location.get("documentHash"));
    }
    @Test void actualRuleChangeUnlocksRecoveryButDescriptionIdsAndFailureMessagesDoNot(){
        Map<String,Object> a=rule("R1","A123"),b=rule("random-new-id","A123"),changed=rule("R1","A124");
        b.put("description","另一种错误说明");
        Finding first=finding(Map.of("plan",Map.of("logic","ALL","rules",List.of(a))));
        Finding equal=finding(Map.of("plan",Map.of("logic","ALL","rules",List.of(b))));
        Finding other=finding(Map.of("plan",Map.of("logic","ALL","rules",List.of(changed))));
        assertEquals(CheckResolution.actionFingerprint(first,"p","h","REPAIR_REQUIREMENT"),CheckResolution.actionFingerprint(equal,"p","h","REPAIR_REQUIREMENT"));
        assertNotEquals(CheckResolution.actionFingerprint(first,"p","h","REPAIR_REQUIREMENT"),CheckResolution.actionFingerprint(other,"p","h","REPAIR_REQUIREMENT"));
    }
    @Test void originalImagePositionChangeUnlocksTheImageAction(){
        Finding before=finding(Map.of("visualRequests",List.of(Map.of("documentId","bid","page",149,"task","FIELD","fields",List.of("编号")))));
        Finding after=finding(Map.of("visualRequests",List.of(Map.of("documentId","bid","page",150,"task","FIELD","fields",List.of("编号")))));
        assertNotEquals(CheckResolution.actionFingerprint(before,"p","h","VERIFY_SOURCE_PAGES"),CheckResolution.actionFingerprint(after,"p","h","VERIFY_SOURCE_PAGES"));
    }
    @Test void validatedNodeRepairCanAdvanceRoutingWithoutCountingAsBusinessProof(){
        Map<String,Object> invalid=rule("R1","");invalid.put("predicate","UNSUPPORTED");
        Finding before=finding(Map.of("plan",Map.of("logic","ALL","rules",List.of(invalid))));
        Finding after=finding(Map.of("plan",Map.of("logic","ALL","rules",List.of(rule("R1","")))));
        Map<String,Object> progress=RecoveryInputs.requirementProgress(before,after);
        assertEquals(true,progress.get("progressMade"));assertEquals(List.of("R1"),progress.get("repairedObligationIds"));assertEquals(false,progress.get("businessProof"));
        assertEquals(false,CheckResolution.progress(before,after).get("progressMade"));
        Map<String,Object> lost=rule("R2","");
        assertEquals(false,RecoveryInputs.requirementProgress(before,finding(Map.of("plan",Map.of("rules",List.of(lost))))).get("progressMade"));
        Map<String,Object> changed=rule("R1","");changed.put("sourceQuote","另一条不同义务");
        assertEquals(false,RecoveryInputs.requirementProgress(before,finding(Map.of("plan",Map.of("rules",List.of(changed))))).get("progressMade"));
    }
    @Test void fieldMappingGapsDoNotUseStaleImageRequestsButIndependentVisualObligationsRemain(){
        for(String code:List.of("COMPARISON_FIELD_MAPPING_REQUIRED","COMPARISON_RECORD_IDENTITY_UNPROVEN","REGISTRATION_CERTIFICATE_REQUIRED")){
            Map<String,Object> atomic=fields("gaps",List.of(Map.of("code",code)),"visualRequests",List.of(Map.of("page",149,"obligationId","R5")));
            assertEquals("TARGETED_EVIDENCE",CheckResolution.plan(finding(atomic)).get("strategy"),code);
            atomic.put("gaps",List.of(Map.of("code",code),Map.of("code","VISUAL_OBSERVATION_REQUIRED")));
            assertEquals("VERIFY_SOURCE_PAGES",CheckResolution.plan(finding(atomic)).get("strategy"),code);
        }
    }
    @Test void countAndPriceRequirementGapsAreNotEvidenceOrImageWork(){
        for(String code:List.of("PRICE_REQUIREMENT_DIMENSIONS_REQUIRED","PERSON_COUNT_COMPARISON_REQUIRED","PERSON_COUNT_ROLE_REQUIRED")){
            Finding finding=finding(fields("gaps",List.of(Map.of("code",code)),"visualRequests",List.of(Map.of("page",1)),
                "recoveryActions",List.of(fields("strategy","REPAIR_REQUIREMENT","reasonCode",code,"obligationId","R1"))));
            assertEquals("REPAIR_REQUIREMENT",CheckResolution.plan(finding).get("strategy"),code);
        }
    }
}
