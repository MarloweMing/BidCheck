package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.service.BidService.fields;
import static cn.bidcheck.service.CheckResolution.atomic;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryFanoutTest {
    @Test void partyOnlyFactOutsideRowPagesDoesNotWakeRow(){
        assertFalse(OrdinaryRecoveryDependencies.matches(binding(),party(40)));
        Map<String,Object> unknown=party(40);unknown.put("sources",Collections.singletonList(fields("id","source")));
        assertTrue(OrdinaryRecoveryDependencies.matches(binding(),unknown));
    }

    @Test void partyOnlyFactInsideRowPagesStillWakesRow(){
        assertTrue(OrdinaryRecoveryDependencies.matches(binding(),party(12)));
    }

    @Test void triggerKeysAreScopedPerTarget(){
        Map<String,Object> trigger=fields("semanticEvidenceKeys",Arrays.asList("first","second"),
            "byTarget",fields("A",fields("semanticEvidenceKeys",Collections.singletonList("first")),
                "B",fields("semanticEvidenceKeys",Collections.singletonList("second"))));
        Map<String,Object> ctx=fields("recoveryTargetObligationIds",Arrays.asList("A","B"),"recoveryDependencyTrigger",trigger);
        List<Map<String,Object>> inputs=RecoverySemanticGate.inputs(finding("签订日期"),ctx,"test");
        assertEquals(Collections.singletonList("FACT:first"),inputs.get(0).get("evidenceKeys"));
        assertEquals(Collections.singletonList("FACT:second"),inputs.get(1).get("evidenceKeys"));
        trigger.remove("byTarget");
        assertEquals(Arrays.asList("FACT:first","FACT:second"),RecoverySemanticGate.inputs(finding("签订日期"),ctx,"test").get(0).get("evidenceKeys"));
    }

    @Test void unchangedGapAfterRecomputeDoesNotScheduleModel(){
        Finding before=finding("签订日期"),after=finding("签订日期");Map<String,Object> next=plan();
        RecoveryInputs.dependencyContinuation(job(),before,after,next);
        assertEquals("BLOCKED_NO_PROGRESS",next.get("status"));assertEquals("BLOCKED",next.get("strategy"));
    }

    @Test void changedGapAfterRecomputeSchedulesModel(){
        Finding before=finding("签订日期"),after=finding("签署日期冲突");Map<String,Object> next=plan();
        Map<String,Object> pending=job();
        Map<String,Object> continuation=RecoveryInputs.dependencyContinuation(pending,before,after,next);
        assertEquals("QUEUED",next.get("status"));assertEquals("TARGETED_EVIDENCE",next.get("strategy"));
        assertEquals(Collections.singletonList("A"),continuation.get("dependencyTargetObligationIds"));
        Map<String,Object> gate=fields("inputs",Collections.singletonList(fields("targetId","A")));
        RecoveryGapKeys.persist(pending,after,after,"TARGETED_EVIDENCE",gate,true);
        next=plan();
        RecoveryInputs.dependencyContinuation(pending,before,after,next);
        assertEquals("BLOCKED_NO_PROGRESS",next.get("status"),"Consumed gap cannot be investigated again on restart");
    }

    @Test void unchangedGapAfterTechnicalRetryDoesNotScheduleModel(){
        Finding before=finding("签订日期"),after=finding("签订日期");
        Map<String,Object> replay=new LinkedHashMap<>(atomic(after));
        replay.put("facts",Collections.singletonList(fields("id","new-verified-party","kind","CONTRACT",
            "field","party","sourceIds",Collections.singletonList("original"))));
        after=atomicFinding(after,replay);
        Map<String,Object> pending=retry(before,"A");
        String expected=RecoveryGapKeys.key(before,"A",RecoveryGapKeys.workReceipt(before,"A",Collections.emptyMap()));
        assertEquals(Collections.singletonList(expected),pending.get("consumedGapKeys"));
        assertEquals(fields("A",expected),pending.get("retryGapKeysBefore"));
        RecoveryGapKeys.beginTechnicalRetry(pending,before,gate("A"));
        assertEquals(Collections.singletonList(expected),pending.get("consumedGapKeys"),"the durable start is idempotent");
        Map<String,Object> next=plan();RecoveryInputs.dependencyContinuation(pending,before,after,next);
        assertEquals("BLOCKED_NO_PROGRESS",next.get("status"));assertEquals("BLOCKED",next.get("strategy"));
        assertEquals("技术重试已完成原调查，本项还缺的内容没有变化",next.get("reason"));
        assertEquals("BLOCKED",RecoveryTransition.afterAttempt(before,after,"RETRY_FAILED_STEP",next,false,false,true,
            Collections.emptyList()).get("nextStrategy"));
    }

    @Test void changedGapAfterTechnicalRetrySchedulesModel(){
        Finding before=twoRows("签订日期","人员任职"),after=twoRows("签订日期","人员任职材料存在明确冲突");
        Map<String,Object> pending=retry(before,"A","B"),next=plan();
        Map<String,Object> scope=RecoveryInputs.dependencyContinuation(pending,before,after,next);
        assertEquals("QUEUED",next.get("status"));assertEquals("TARGETED_EVIDENCE",next.get("strategy"));
        assertEquals(Collections.singletonList("B"),scope.get("dependencyTargetObligationIds"));
        String changedKey=RecoveryGapKeys.key(after,"B",RecoveryGapKeys.workReceipt(after,"B",Collections.emptyMap()));
        List<String> seen=new ArrayList<>(RecoveryGapKeys.strings(pending.get("consumedGapKeys")));seen.add(changedKey);
        pending.put("consumedGapKeys",seen);next=plan();
        RecoveryInputs.dependencyContinuation(pending,before,after,next);
        assertEquals("BLOCKED_NO_PROGRESS",next.get("status"),"an already investigated changed gap is not dispatched twice");
    }

    @Test void retryKeepsFailureAndNonTextRoutesAndDoesNotChargeBlockedSiblings(){
        Finding before=twoRows("签订日期","人员任职");
        Map<String,Object> job=fields("strategy","RETRY_FAILED_STEP","consumedGapKeys",Collections.singletonList("older"));
        Map<String,Object> gate=gate("A","B");gate.put("blockedTargetIds",Collections.singletonList("B"));
        RecoveryGapKeys.beginTechnicalRetry(job,before,gate);
        assertEquals(Collections.singleton("A"),cn.bidcheck.service.CheckResolution.map(job.get("retryGapKeysBefore")).keySet());
        assertTrue(RecoveryGapKeys.strings(job.get("consumedGapKeys")).contains("older"));
        for(String strategy:Arrays.asList("RETRY_FAILED_STEP","WAIT_REQUIREMENT_CRITERIA","VERIFY_SOURCE_PAGES","NONE")){
            Map<String,Object> current=fields("status",strategy.equals("NONE")?"SUCCEEDED":"QUEUED","strategy",strategy);
            RecoveryInputs.dependencyContinuation(job,before,before,current);
            assertEquals(strategy,current.get("strategy"));
        }
        Map<String,Object> raw=new LinkedHashMap<>(atomic(before));raw.put("agentInvestigation",fields("status","FAILED","errorCode","PROVIDER_RATE_LIMIT"));
        Map<String,Object> current=plan();
        RecoveryInputs.dependencyContinuation(job,before,atomicFinding(before,raw),current);
        assertNotEquals("BLOCKED_NO_PROGRESS",current.get("status"),"an unresolved technical failure is still handled by the failure policy");
    }

    @Test void concreteUnreadSourceIsNewInputButANewRejectionReceiptWithNoRemainingActionIsNot(){
        Finding plain=finding("签订日期");Map<String,Object> row=fields("targetObligationId","A","evidenceRevision","e",
            "pendingReadActions",Collections.emptyList());
        Map<String,Object> host=fields("version","server-target-input-consumption-1","authority","CURRENT_SERVER_PROOF","businessProof",false,
            "requestId","req","serverPlanRevision","plan","targets",Collections.singletonList(row));
        Map<String,Object> raw=new LinkedHashMap<>(atomic(plain));raw.put("agentInvestigation",fields("status","NO_PROGRESS",
            "targetReceiptAuthority","CURRENT_SERVER_PROOF","requestId","req","serverPlanRevision","plan",
            "remainingTargetObligationIds",Collections.singletonList("A"),"targetEvidenceRevisions",fields("A","e"),"serverTargetInputConsumption",host));
        raw.put("rejectedFacts",Collections.singletonList(fields("targetObligationId","A","code","RAW_VALUE_UNGROUNDED","fieldPath","/fact/rawValue")));
        Finding after=atomicFinding(plain,raw);Map<String,Object> job=retry(plain,"A"),next=plan();
        RecoveryInputs.dependencyContinuation(job,plain,after,next);
        assertEquals("BLOCKED_NO_PROGRESS",next.get("status"));
        row.put("pendingReadActions",Collections.singletonList(fields("action","READ_SECTION_CONTINUATION","sourceId","new-page",
            "documentHash","hash","documentVersion",1,"page",157,"unreadSourceIds",Collections.singletonList("new-page"))));
        next=plan();Map<String,Object> resume=RecoveryInputs.dependencyContinuation(job,plain,after,next);
        assertEquals("QUEUED",next.get("status"));assertEquals(Collections.singletonList("A"),resume.get("dependencyTargetObligationIds"));
    }

    private static Map<String,Object> gate(String... targets){
        List<Map<String,Object>> inputs=new ArrayList<>();
        for(String target:targets)inputs.add(fields("targetId",target));
        return fields("inputs",inputs);
    }

    private Map<String,Object> retry(Finding before,String... targets){
        Map<String,Object> job=fields("strategy","RETRY_FAILED_STEP");
        RecoveryGapKeys.beginTechnicalRetry(job,before,gate(targets));return job;
    }

    private Finding twoRows(String a,String b){
        Finding first=finding(a);Map<String,Object> body=new LinkedHashMap<>(atomic(first));
        Map<String,Object> second=fields("id","B","status","UNDETERMINED","gaps",Collections.singletonList(fields("code",b)));
        List<Map<String,Object>> conditions=new ArrayList<>(CheckResolution.maps(body.get("conditions")));conditions.add(second);
        body.put("conditions",conditions);return atomicFinding(first,body);
    }

    private static Finding atomicFinding(Finding f,Map<String,Object> atomic){
        return new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),fields("atomicCheck",atomic));
    }

    private Map<String,Object> binding(){
        Map<String,Object> row=fields("documentId","bid","documentHash","frozen","documentVersion",1,
            "proofPageStart",10,"proofPageEnd",20,"separatorTitle","装饰合同甲","inventoryIdentity","row-1");
        Map<String,Object> target=fields("predicate","CONTRACT","parameters",fields("declaredPerformanceRow",row));
        String key=cn.bidcheck.verification.facts.FieldCoverage.key(target);
        return fields("target",target,"obligationId","A","observationKey",key,"rowNumberEvidence",fields(
            "version","server-declared-row-number-1","authority","CURRENT_SERVER_VERIFIED_ROW","obligationId","A",
            "observationKey",key,"row",row,"complete",true,"inputs",Collections.emptyList(),"businessProof",false));
    }
    private Map<String,Object> party(int page){
        return fields("itemId","fact","recordType","FACT","fact",fields("kind","CONTRACT","field","party","value","合成采购单位",
            "attributes",fields("contractNumber","CN-12345"),"sourceIds",Collections.singletonList("source")),
            "sources",Collections.singletonList(fields("id","source","documentId","bid","documentHash","frozen","documentVersion",1,"page",page)));
    }
    private Finding finding(String missing){
        Checkpoint cp=new Checkpoint("cp","申报合同业绩","商务","HIGH","SEMANTIC_RESPONSE","业绩","EXISTS","已签约合同","",true,true,true,null);
        Map<String,Object> row=fields("inventoryIdentity","row-1");
        Map<String,Object> condition=fields("id","A","status","UNDETERMINED","gaps",Collections.singletonList(fields("code","PERFORMANCE_PROOF_COVERAGE_REQUIRED")),
            "proof",fields("ruleVersion","v1","contractChecks",Collections.singletonList(fields("declaredRow",row,"missing",Collections.singletonList(missing)))));
        Map<String,Object> atomic=fields("conditions",Collections.singletonList(condition),"recoveryEvidenceDependencies",Collections.singletonList(binding()));
        return new Finding("finding","run",cp,new Evaluation("UNDETERMINED","需核对合同","",Collections.emptyList(),0,"TEST"),fields("atomicCheck",atomic));
    }
    private Map<String,Object> job(){
        return fields("strategy","RECOMPUTE_SHARED_EVIDENCE","dependencyTargetObligationIds",Collections.singletonList("A"),
            "dependencyTrigger",fields("semanticEvidenceKeys",Collections.singletonList("new-party"),
                "byTarget",fields("A",fields("semanticEvidenceKeys",Collections.singletonList("new-party")))));
    }
    private Map<String,Object> plan(){return fields("status","QUEUED","strategy","TARGETED_EVIDENCE");}
}
