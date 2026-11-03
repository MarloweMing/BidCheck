package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.fields;

class TargetedRecoveryInputsTest {
    private Finding finding(Map<String,Object> atomic){
        Checkpoint cp=new Checkpoint("cp","设计负责人资格要求","资格检查","HIGH","SEMANTIC_RESPONSE","person","EXISTS","查询截图与证书信息一致","",true,true,true,null);
        return new Finding("finding","run",cp,new Evaluation("UNDETERMINED","字段对应未完成","",cn.bidcheck.compat.Java8Api.listOf(),0,"FOCUSED_ATOMIC_RULES"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic));
    }
    private Map<String,Object> condition(String id,String status){return fields("id",id,"conditionRole","REQUIREMENT","status",status,"gaps",cn.bidcheck.compat.Java8Api.listOf());}
    private Map<String,Object> rule(String id,String expected){return fields("id",id,"description","不同说明不改规则","predicate","SAME_FIELD","subject","设计负责人","field","注册编号","operator","EQ","expected",expected,"unit","","sourceQuote","查询截图与证明文件信息一致","parameters",cn.bidcheck.compat.Java8Api.mapOf(),"children",cn.bidcheck.compat.Java8Api.listOf());}
    @Test void declaredRepairQueuesDeterministicRecompilationInsteadOfOldPlanReplay(){
        Finding before=finding(fields("gaps",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "REQUIREMENT_COMPARISON_TARGET_UNRESOLVED")),"compilationAudit",cn.bidcheck.compat.Java8Api.mapOf("state", "VALIDATED", "repairAttempts", 0),
            "recoveryActions",cn.bidcheck.compat.Java8Api.listOf(fields("strategy","REPAIR_REQUIREMENT","obligationId","R5","reasonCode","REQUIREMENT_COMPARISON_TARGET_UNRESOLVED")),
            "visualRequests",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 149, "obligationId", "R5")),"conditions",cn.bidcheck.compat.Java8Api.listOf(condition("R1","PASS"), condition("R5","UNDETERMINED"))));
        assertEquals("REPAIR_REQUIREMENT",CheckResolution.plan(before).get("strategy"));assertEquals("QUEUED",CheckResolution.plan(before).get("status"));
        Map<String,Object> context=RecoveryInputs.context(before,"REPAIR_REQUIREMENT",cn.bidcheck.compat.Java8Api.mapOf("priorSteps", cn.bidcheck.compat.Java8Api.listOf()));
        assertEquals("RECOMPILE_REQUIREMENT",context.get("recoveryMode"));assertEquals(false,context.get("recoveryModelAllowed"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("R5"),context.get("recoveryTargetObligationIds"));assertEquals(CheckResolution.atomic(before),context.get("previousAtomicCheck"));
    }
    @Test void priorRepairAttemptsDoNotBlockAChangedCompilerAndUnsupportedCapabilityRoutesToVersionGate(){
        Map<String,Object> atomic=fields("compilationAudit",cn.bidcheck.compat.Java8Api.mapOf("state", "UNRESOLVED", "repairAttempts", 1),"recoveryActions",cn.bidcheck.compat.Java8Api.listOf(fields("strategy","REPAIR_REQUIREMENT","reasonCode","RULE_VALIDATION_FAILED")));
        assertEquals("REPAIR_REQUIREMENT",CheckResolution.plan(finding(atomic)).get("strategy"));
        atomic.put("compilationAudit",cn.bidcheck.compat.Java8Api.mapOf("state", "UNRESOLVED", "repairAttempts", 0));
        atomic.put("recoveryActions",cn.bidcheck.compat.Java8Api.listOf(fields("strategy","REPAIR_REQUIREMENT","reasonCode","CAPABILITY_UNRESOLVED")));
        assertEquals("REPAIR_REQUIREMENT",CheckResolution.plan(finding(atomic)).get("strategy"));
    }
    @Test void compilationCooldownCannotFallThroughToImagesOrLocalRepair(){
        Finding before=finding(fields("compilationAudit",fields("state","REPAIR_RETRY_PENDING","repairAttempts",0,"retryNotBeforeEpochMillis",Long.MAX_VALUE),
            "recoveryActions",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("strategy", "REPAIR_REQUIREMENT", "reasonCode", "RULE_VALIDATION_FAILED")),"visualRequests",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 149))));
        assertEquals("BLOCKED",CheckResolution.plan(before).get("strategy"));assertEquals("FAILED",CheckResolution.plan(before).get("status"));
        assertEquals(Long.MAX_VALUE,CheckResolution.plan(before).get("retryNotBeforeEpochMillis"));
    }
    @Test void pageRecoveryTargetsPendingNodesAndExcludesCompletedObservations(){
        Finding before=finding(fields("conditions",cn.bidcheck.compat.Java8Api.listOf(condition("R1","PASS"), condition("R5","UNDETERMINED")),
            "visualRequests",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 149, "obligationId", "R1"), cn.bidcheck.compat.Java8Api.mapOf("page", 150, "obligationId", "R5"))));
        Map<String,Object> context=RecoveryInputs.context(before,"VERIFY_SOURCE_PAGES",cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("R5"),context.get("recoveryTargetObligationIds"));assertEquals("EVIDENCE_RECOMPUTE",context.get("recoveryMode"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 150, "obligationId", "R5")),RecoveryInputs.visualRequest(before).get("visualRequests"));
    }
    private Map<String,Object> observation(String id,String value){return fields("id",id,"savedAt",id,"documentId","bid","page",150,"locationType","PAGE","obligationId","R5","type","FIELD","value",value,
        "source",fields("documentId","bid","documentHash","hash","documentVersion",1,"page",150,"renderedSha256","imagehash"));}
    @Test void newReceiptIdsDoNotMakeOldObservationsNewEvidence(){
        Map<String,Object> before=observation("one","证书编号：A123");Map<String,Object> same=observation("two","证书编号：A123");
        assertTrue(RecoveryInputs.changedObservations(cn.bidcheck.compat.Java8Api.listOf(before),cn.bidcheck.compat.Java8Api.listOf(same)).isEmpty());
        Map<String,Object> changed=observation("three","证书编号：A124");List<Map<String,Object>> added=RecoveryInputs.changedObservations(cn.bidcheck.compat.Java8Api.listOf(before),cn.bidcheck.compat.Java8Api.listOf(same, changed, changed));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(changed),added);Map<String,Object> context=new LinkedHashMap<>();RecoveryInputs.changedInputs(context,added);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("R5"),context.get("recoveryTargetObligationIds"));assertEquals(1,context.get("recoveryNewObservationCount"));
        Map<String,Object> location=CheckResolution.maps(context.get("recoveryChangedLocations")).get(0);assertEquals(150,location.get("location"));assertEquals("hash",location.get("documentHash"));
    }
    @Test void actualRuleChangeUnlocksRecoveryButDescriptionIdsAndFailureMessagesDoNot(){
        Map<String,Object> a=rule("R1","A123"),b=rule("random-new-id","A123"),changed=rule("R1","A124");
        b.put("description","另一种错误说明");
        Finding first=finding(cn.bidcheck.compat.Java8Api.mapOf("plan", cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(a))));
        Finding equal=finding(cn.bidcheck.compat.Java8Api.mapOf("plan", cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(b))));
        Finding other=finding(cn.bidcheck.compat.Java8Api.mapOf("plan", cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(changed))));
        assertEquals(CheckResolution.actionFingerprint(first,"p","h","REPAIR_REQUIREMENT"),CheckResolution.actionFingerprint(equal,"p","h","REPAIR_REQUIREMENT"));
        assertNotEquals(CheckResolution.actionFingerprint(first,"p","h","REPAIR_REQUIREMENT"),CheckResolution.actionFingerprint(other,"p","h","REPAIR_REQUIREMENT"));
    }
    @Test void originalImagePositionChangeUnlocksTheImageAction(){
        Finding before=finding(cn.bidcheck.compat.Java8Api.mapOf("visualRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "bid", "page", 149, "task", "FIELD", "fields", cn.bidcheck.compat.Java8Api.listOf("编号")))));
        Finding after=finding(cn.bidcheck.compat.Java8Api.mapOf("visualRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "bid", "page", 150, "task", "FIELD", "fields", cn.bidcheck.compat.Java8Api.listOf("编号")))));
        assertNotEquals(CheckResolution.actionFingerprint(before,"p","h","VERIFY_SOURCE_PAGES"),CheckResolution.actionFingerprint(after,"p","h","VERIFY_SOURCE_PAGES"));
    }
    @Test void validatedNodeRepairCanAdvanceRoutingWithoutCountingAsBusinessProof(){
        Map<String,Object> invalid=rule("R1","");invalid.put("predicate","UNSUPPORTED");
        Finding before=finding(cn.bidcheck.compat.Java8Api.mapOf("plan", cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(invalid))));
        Finding after=finding(cn.bidcheck.compat.Java8Api.mapOf("plan", cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(rule("R1","")))));
        Map<String,Object> progress=RecoveryInputs.requirementProgress(before,after);
        assertEquals(true,progress.get("progressMade"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("R1"),progress.get("repairedObligationIds"));assertEquals(false,progress.get("businessProof"));
        assertEquals(false,CheckResolution.progress(before,after).get("progressMade"));
        Map<String,Object> lost=rule("R2","");
        assertEquals(false,RecoveryInputs.requirementProgress(before,finding(cn.bidcheck.compat.Java8Api.mapOf("plan", cn.bidcheck.compat.Java8Api.mapOf("rules", cn.bidcheck.compat.Java8Api.listOf(lost))))).get("progressMade"));
        Map<String,Object> changed=rule("R1","");changed.put("sourceQuote","另一条不同义务");
        assertEquals(false,RecoveryInputs.requirementProgress(before,finding(cn.bidcheck.compat.Java8Api.mapOf("plan", cn.bidcheck.compat.Java8Api.mapOf("rules", cn.bidcheck.compat.Java8Api.listOf(changed))))).get("progressMade"));
    }
    @Test void fieldMappingGapsDoNotUseStaleImageRequestsButIndependentVisualObligationsRemain(){
        for(String code:cn.bidcheck.compat.Java8Api.listOf("COMPARISON_FIELD_MAPPING_REQUIRED", "COMPARISON_RECORD_IDENTITY_UNPROVEN", "REGISTRATION_CERTIFICATE_REQUIRED")){
            Map<String,Object> atomic=fields("gaps",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", code)),"visualRequests",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 149, "obligationId", "R5")));
            assertEquals("TARGETED_EVIDENCE",CheckResolution.plan(finding(atomic)).get("strategy"),code);
            atomic.put("gaps",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", code), cn.bidcheck.compat.Java8Api.mapOf("code", "VISUAL_OBSERVATION_REQUIRED")));
            assertEquals("VERIFY_SOURCE_PAGES",CheckResolution.plan(finding(atomic)).get("strategy"),code);
        }
    }
    @Test void countAndPriceRequirementGapsAreNotEvidenceOrImageWork(){
        for(String code:cn.bidcheck.compat.Java8Api.listOf("PRICE_REQUIREMENT_DIMENSIONS_REQUIRED", "PERSON_COUNT_COMPARISON_REQUIRED", "PERSON_COUNT_ROLE_REQUIRED")){
            Finding finding=finding(fields("gaps",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", code)),"visualRequests",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1)),
                "recoveryActions",cn.bidcheck.compat.Java8Api.listOf(fields("strategy","REPAIR_REQUIREMENT","reasonCode",code,"obligationId","R1"))));
            assertEquals("REPAIR_REQUIREMENT",CheckResolution.plan(finding).get("strategy"),code);
        }
    }
    @Test void globalRevisionsCannotUnlockAnEarlierAttemptButApplicableSemanticChangesCan(){
        Finding finding=finding(cn.bidcheck.compat.Java8Api.mapOf("plan", cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(rule("R1","A123")))));
        Map<String,Object> first=fields("sharedFactRevision","facts-1","visualObservations",cn.bidcheck.compat.Java8Api.listOf(observation("receipt-one","A123")));
        Map<String,Object> same=fields("sharedFactRevision","facts-1","visualObservations",cn.bidcheck.compat.Java8Api.listOf(observation("receipt-two","A123")));
        Map<String,Object> changed=fields("sharedFactRevision","facts-2","visualObservations",cn.bidcheck.compat.Java8Api.listOf(observation("receipt-two","A123")));
        assertEquals(CheckResolution.actionFingerprint(finding,"policy","document","TARGETED_EVIDENCE",first),CheckResolution.actionFingerprint(finding,"policy","document","TARGETED_EVIDENCE",same));
        assertEquals(CheckResolution.actionFingerprint(finding,"policy","document","TARGETED_EVIDENCE",first),CheckResolution.actionFingerprint(finding,"policy","document","TARGETED_EVIDENCE",changed));
        changed.put("recoveryDependencyTrigger",fields("semanticEvidenceKeys",cn.bidcheck.compat.Java8Api.listOf("new-value-on-frozen-page"),"revision",2,"eventIds",cn.bidcheck.compat.Java8Api.listOf("receipt-1")));
        changed.put("recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("R1"));
        assertNotEquals(CheckResolution.actionFingerprint(finding,"policy","document","TARGETED_EVIDENCE",first),CheckResolution.actionFingerprint(finding,"policy","document","TARGETED_EVIDENCE",changed));
        String actual=CheckResolution.actionFingerprint(finding,"policy","document","TARGETED_EVIDENCE",changed);
        changed.put("sharedFactRevision","unrelated-999");changed.put("recoveryDependencyTrigger",fields("semanticEvidenceKeys",cn.bidcheck.compat.Java8Api.listOf("new-value-on-frozen-page"),"revision",999,"eventIds",cn.bidcheck.compat.Java8Api.listOf("receipt-2")));
        assertEquals(actual,CheckResolution.actionFingerprint(finding,"policy","document","TARGETED_EVIDENCE",changed));
    }
    @Test void frozenPersonnelTargetsClosedByLocalReuseDoNotEscalateOtherOldGaps(){
        for(List<String> ids:cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf("D49da4c734dd7c16f1fe7-ROLE-5"), cn.bidcheck.compat.Java8Api.listOf("D68837233c15fdac3e078-ROLE-2", "D68837233c15fdac3e078-ROLE-5"))){
            List<Map<String,Object>> conditions=new ArrayList<>(cn.bidcheck.compat.Java8Api.toList(ids.stream().map(id->condition(id,"PASS"))));conditions.add(condition("UNRELATED-SOCIAL","UNDETERMINED"));
            Finding after=finding(fields("conditions",conditions,"gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","SOCIAL_EMPLOYER_REQUIRED"))));
            Map<String,Object> job=fields("strategy","RECOMPUTE_SHARED_EVIDENCE","dependencyTargetObligationIds",ids,
                "dependencyTrigger",fields("semanticEvidenceKeys",cn.bidcheck.compat.Java8Api.listOf("new-role-fact"),"sourceIds",cn.bidcheck.compat.Java8Api.listOf("S22")));
            Map<String,Object> next=fields("status","QUEUED","strategy","TARGETED_EVIDENCE");
            assertTrue(RecoveryInputs.dependencyContinuation(job,after,next).isEmpty());assertEquals("BLOCKED_NO_PROGRESS",next.get("status"));
            Map<String,Object> context=RecoveryInputs.context(after,"RECOMPUTE_SHARED_EVIDENCE",cn.bidcheck.compat.Java8Api.mapOf());
            assertTrue(RecoveryInputs.applyDependencyTrigger(context,job,after));assertEquals(ids,context.get("recoveryTargetObligationIds"));
            assertEquals(false,context.get("recoveryModelAllowed"));assertEquals("EVIDENCE_RECOMPUTE",context.get("recoveryMode"));
            job.put("strategy","TARGETED_EVIDENCE");assertFalse(RecoveryInputs.applyDependencyTrigger(context,job,after));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),context.get("recoveryTargetObligationIds"));
        }
    }
    @Test void unresolvedDependencyKeepsExactTargetsForNextUntriedStrategyOnlyWithNewEvidence(){
        Finding after=finding(fields("conditions",cn.bidcheck.compat.Java8Api.listOf(condition("ROLE","PASS"), condition("CERT","UNDETERMINED"), condition("UNRELATED","UNDETERMINED"))));
        Map<String,Object> job=fields("strategy","RECOMPUTE_SHARED_EVIDENCE","dependencyTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("ROLE", "CERT"),
            "dependencyTrigger",fields("semanticEvidenceKeys",cn.bidcheck.compat.Java8Api.listOf("new-certificate"),"sourceIds",cn.bidcheck.compat.Java8Api.listOf("S22")));
        Map<String,Object> next=fields("status","QUEUED","strategy","TARGETED_EVIDENCE");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("CERT"),RecoveryInputs.dependencyContinuation(job,after,next).get("dependencyTargetObligationIds"));
        assertEquals("QUEUED",next.get("status"));job.put("dependencyTrigger",cn.bidcheck.compat.Java8Api.mapOf("revision", 999));
        assertTrue(RecoveryInputs.dependencyContinuation(job,after,next).isEmpty());assertEquals("BLOCKED_NO_PROGRESS",next.get("status"));
    }
    @Test void anExplicitUnresolvedDecisionOverridesAStaleClosedProjection(){
        Finding after=finding(fields("conditions",cn.bidcheck.compat.Java8Api.listOf(condition("CERT","PASS")),"obligations",cn.bidcheck.compat.Java8Api.listOf(condition("CERT","UNDETERMINED"))));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("CERT"),RecoveryInputs.pendingTargets(after,cn.bidcheck.compat.Java8Api.listOf("CERT")));
    }
    @Test void externalChangeTargetsOnlyTheMatchingSubjectAndStage(){
        Finding before=finding(fields("externalRequests",cn.bidcheck.compat.Java8Api.listOf(fields("obligationId","R1","type","CREDIT","subject","甲公司","evaluationStage","BID"), fields("obligationId","R2","type","CREDIT","subject","乙公司","evaluationStage","BID"), fields("obligationId","R3","type","CREDIT","subject","甲公司","evaluationStage","AWARD"))));
        Map<String,Object> context=fields("recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("R1", "R2", "R3"));
        RecoveryInputs.changedExternalInputs(context,before,cn.bidcheck.compat.Java8Api.listOf(fields("type","CREDIT","subject","甲公司","evaluationStage","BID")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("R1"),context.get("recoveryTargetObligationIds"));assertEquals(1,context.get("recoveryNewExternalRecordCount"));
    }
    @Test void chapterDependencyIsRequirementWorkAndNeverAnImageOrModelSearch(){
        Finding before=finding(fields("gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","DEPENDENCY_REFERENCE_PENDING")),"visualRequests",cn.bidcheck.compat.Java8Api.listOf(fields("page",1)),
            "recoveryActions",cn.bidcheck.compat.Java8Api.listOf(fields("strategy","REPAIR_REQUIREMENT","reasonCode","DEPENDENCY_REFERENCE_PENDING","obligationId","R1"))));
        assertEquals("REPAIR_REQUIREMENT",CheckResolution.plan(before).get("strategy"));
        assertEquals("REQUIREMENT",cn.bidcheck.verification.engine.RecoveryFailureRouting.owner("DEPENDENCY_REFERENCE_PENDING"));
    }
    @Test void unresolvedSourceHasAnExecutableRestoreRouteAndEmptyTreeRecoveryIsProgress(){
        Finding before=finding(fields("plan",fields("method","SCOPE_UNRESOLVED","logic","ALL","rules",cn.bidcheck.compat.Java8Api.listOf(),"issues",cn.bidcheck.compat.Java8Api.listOf("来源不匹配")),
            "compilationAudit",cn.bidcheck.compat.Java8Api.mapOf("state", "UNRESOLVED"),"recoveryActions",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("strategy", "RESTORE_REQUIREMENT_SOURCE"))));
        assertEquals("RESTORE_REQUIREMENT_SOURCE",CheckResolution.plan(before).get("strategy"));
        Map<String,Object> context=RecoveryInputs.context(before,"RESTORE_REQUIREMENT_SOURCE",cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals("RESTORE_REQUIREMENT_SOURCE",context.get("recoveryMode"));assertEquals(false,context.get("recoveryModelAllowed"));
        assertFalse(context.containsKey("recoveryTargetObligationIds"),"a formerly empty tree has no old ids to constrain restored nodes");
        Finding after=finding(fields("plan",fields("logic","ALL","rules",cn.bidcheck.compat.Java8Api.listOf(rule("R1","")),"issues",cn.bidcheck.compat.Java8Api.listOf(),"requirementScope",cn.bidcheck.compat.Java8Api.mapOf("requirementRepair", fields("state","PUBLISHED","sourceRestored",true,"originalNodesPreserved",true)))));
        assertEquals(true,RecoveryInputs.requirementProgress(before,after).get("progressMade"));
        assertEquals(true,RecoveryInputs.requirementProgress(before,after).get("emptyTreeRestored"));
        assertEquals(false,RecoveryInputs.requirementProgress(before,after).get("businessProof"));
    }
    @Test void compilerSourceAndFieldMappingChangesInvalidateRepairFingerprints(){
        Finding before=finding(cn.bidcheck.compat.Java8Api.mapOf("plan", cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(rule("R1","A123")))));
        Map<String,Object> first=RecoveryInputs.context(before,"REPAIR_REQUIREMENT",cn.bidcheck.compat.Java8Api.mapOf());
        String original=CheckResolution.actionFingerprint(before,"policy","doc","REPAIR_REQUIREMENT",first);
        for(String key:cn.bidcheck.compat.Java8Api.listOf("requirementCompilerRevision", "requirementMappingRevision", "requirementSourcePackageRevision")){
            Map<String,Object> changed=new LinkedHashMap<>(first);changed.put(key,"updated-revision");
            assertNotEquals(original,CheckResolution.actionFingerprint(before,"policy","doc","REPAIR_REQUIREMENT",changed),key);
        }
    }
    @Test void sourceBindingWithoutExecutableBusinessLeafDoesNotBecomeRequirementsRepaired(){
        Finding before=finding(fields("plan",fields("method","SCOPE_UNRESOLVED","logic","ALL","rules",cn.bidcheck.compat.Java8Api.listOf(),"issues",cn.bidcheck.compat.Java8Api.listOf("来源不匹配"))));
        for(String state:cn.bidcheck.compat.Java8Api.listOf("PUBLISHED", "SOURCE_RESTORED_ONLY"))for(String predicate:cn.bidcheck.compat.Java8Api.listOf("UNSUPPORTED", "INPUT", "REFERENCE_TIME", "NOT_APPLICABLE")){
            Map<String,Object> node=new LinkedHashMap<>(rule("R1",""));node.put("predicate",predicate);
            Finding after=finding(fields("plan",fields("logic","ALL","rules",cn.bidcheck.compat.Java8Api.listOf(node),"issues",cn.bidcheck.compat.Java8Api.listOf(),"requirementScope",cn.bidcheck.compat.Java8Api.mapOf("requirementRepair", fields("state",state,"sourceRestored",true,"originalNodesPreserved",true)))));
            Map<String,Object> progress=RecoveryInputs.requirementProgress(before,after);assertEquals(true,progress.get("sourceBindingProgress"));assertEquals(false,progress.get("progressMade"),state+":"+predicate);assertEquals(false,progress.get("ruleCompilationProgress"));assertEquals(false,progress.get("emptyTreeRestored"));
        }
    }
}
