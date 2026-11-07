package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import java.util.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

/** Technical recovery and business proof are separate outcomes of the same attempt. */
final class RecoveryTransition {
    private RecoveryTransition() {}

    static Map<String,Object> afterAttempt(Finding before,Finding after,String executedStrategy,
            Map<String,Object> nextPlan,boolean businessProgress,boolean requirementsRepaired,
            boolean recomputed,List<Map<String,Object>> diagnostics) {
        boolean visualFailure=diagnostics.stream().anyMatch(d->cn.bidcheck.compat.Java8Api.setOf("VISUAL_RETRY_PENDING", "VISUAL_ENGINE_FAILURE", "VISUAL_SOURCE_UNAVAILABLE").contains(text(d,"code")));
        boolean externalFailure=diagnostics.stream().anyMatch(d->ExternalCheckState.systemFailure(text(d,"code")));
        boolean complete=!unresolved(after);
        boolean observedFailure=hasProcessingFailure(after)||visualFailure||externalFailure||"FAILED".equals(text(nextPlan,"status"));
        boolean failedNow=!complete&&observedFailure;
        boolean recovered=recomputed&&hasProcessingFailure(before)&&!observedFailure;
        String nextStrategy=text(nextPlan,"strategy"),status=text(nextPlan,"status");
        boolean changedStrategy=!executedStrategy.equals(nextStrategy);
        // Compare the same capability: visual requests appearing only in one strategy's
        // fingerprint are not evidence that a business input changed.
        boolean changedInputs=!RecoveryInputs.inputKey(before,executedStrategy).equals(RecoveryInputs.inputKey(after,executedStrategy))
            ||!RecoveryInputs.targets(before,executedStrategy).equals(RecoveryInputs.targets(after,executedStrategy));
        // Completed business obligations may expose another page or pending node of the
        // same strategy. The scheduler still rejects an already attempted input fingerprint.
        boolean newSameStrategyWork=businessProgress&&changedInputs;
        // A distinct capability may be tried initially. The durable per-target gate below
        // still vetoes returning to a consumed investigation after observation-only changes.
        boolean newStrategyWork=!failedNow&&changedStrategy&&CheckResolution.ACTIVE.contains(status)
            &&cn.bidcheck.compat.Java8Api.setOf("VERIFY_SOURCE_PAGES", "QUERY_EXTERNAL", "RESTORE_REQUIREMENT_SOURCE", "RECOMPILE_REQUIREMENT", "REPAIR_REQUIREMENT", "RETRY_FAILED_STEP", "TARGETED_EVIDENCE", "SPLIT_FAILED_REQUEST", "RECOMPUTE_SHARED_EVIDENCE").contains(nextStrategy);
        if(complete)status="SUCCEEDED";
        else if(!newStrategyWork&&(!(businessProgress||requirementsRepaired||recovered)||!changedStrategy&&!newSameStrategyWork))
            status=failedNow?"FAILED":cn.bidcheck.compat.Java8Api.setOf("WAITING_USER", "WAITING_EXTERNAL").contains(status)?status:"BLOCKED_NO_PROGRESS";
        Map<String,Object> outcome=fields("status",status,"executedStrategy",executedStrategy,"nextStrategy",nextStrategy,
            "technicalRecovered",recovered,"processingFailed",failedNow,"businessComplete",complete,"inputsChanged",changedInputs,
            "newStrategyWork",newStrategyWork,
            "executionState",complete?"SUCCEEDED":failedNow?"FAILED":recomputed?"SUCCEEDED":"NO_NEW_INPUT",
            "temporaryVisualFailure",visualFailure,"externalProcessingFailure",externalFailure);
        if("FAILED".equals(status))outcome.put("stopReason",diagnostics.isEmpty()?"补查后的处理步骤仍有失败；已保留完成的证明和具体失败记录。":text(diagnostics.stream().filter(d->ExternalCheckState.systemFailure(text(d,"code"))).findFirst().orElse(diagnostics.get(0)),"description"));
        else if("BLOCKED_NO_PROGRESS".equals(status))outcome.put("stopReason",recovered
            ?"失败请求已恢复；剩余业务证据尚未闭合，已保留本轮取得的事实。"
            :diagnostics.isEmpty()?"本次补查没有关闭剩余业务缺口，已保留证据与处理记录。":text(diagnostics.get(0),"description"));
        return outcome;
    }

    static void applySemanticGate(Map<String,Object> outcome,Map<String,Object> gate){
        outcome.put("semanticGate",gate);
        if(Boolean.FALSE.equals(gate.get("allowed"))&&!Boolean.TRUE.equals(outcome.get("businessComplete"))
                &&ACTIVE.contains(text(outcome,"status"))){
            outcome.put("status","BLOCKED_NO_PROGRESS");outcome.put("newStrategyWork",false);
            outcome.put("stopReason",text(gate,"description"));outcome.put("reasonCode",text(gate,"reasonCode"));
        }
    }

    static boolean hasProcessingFailure(Finding finding) {
        if(maps(map(finding.assessment()).get("externalQueryDiagnostics")).stream().anyMatch(d->ExternalCheckState.systemFailure(text(d,"code"))))return true;
        Map<String,Object> retained=map(map(finding.assessment()).get("recoveryFailure"));
        // The frozen earlier proof may contain an earlier request failure. A new retention
        // receipt explicitly classifies the latest attempt, so that old error cannot reappear.
        if(Boolean.TRUE.equals(retained.get("retainedCompletedProof"))&&retained.get("processingFailed") instanceof Boolean)return ((Boolean) (retained.get("processingFailed")));
        Map<String,Object> atomic=atomic(finding),model=map(atomic.get("modelDiagnostics"));
        if("FAILED".equals(text(map(atomic.get("agentInvestigation")),"status")))return true;
        if(CheckResolution.retryable(model))return true;
        if(model.get("unresolvedFailures") instanceof Number&&((Number) (model.get("unresolvedFailures"))).longValue()>0)return true;
        if(maps(model.get("failures")).stream().anyMatch(f->!Boolean.TRUE.equals(f.get("recoveredByRetry"))&&!Boolean.TRUE.equals(f.get("transportRecovered"))))return true;
        if(cn.bidcheck.compat.Java8Api.setOf("ENGINE_FAILURE", "ERROR", "PROCESSING_FAILED").contains(text(atomic,"workflowState")))return true;
        if("REPAIR_RETRY_PENDING".equals(text(map(atomic.get("compilationAudit")),"state")))return true;
        if(maps(atomic.get("fieldCoverage")).stream().anyMatch(RecoveryTransition::fieldProcessingFailure))return true;
        if(RequirementFailureOwnership.localGaps(finding).stream().anyMatch(g->{String code=text(g,"code");return code.startsWith("MODEL_")||code.startsWith("HTTP_")
            ||cn.bidcheck.compat.Java8Api.setOf("AGENT_INVESTIGATION_FAILED", "AGENT_INVESTIGATION_STALLED", "REQUEST_TIMEOUT", "OUTPUT_SCHEMA_INVALID", "OUTPUT_JSON_INVALID", "FACT_EXTRACTION_UNAVAILABLE", "ENGINE_FAILURE").contains(code);}))return true;
        if(!Boolean.TRUE.equals(retained.get("retainedCompletedProof")))return false;
        // Retaining an earlier proof says nothing about whether the new request failed.
        // Older receipts are classified from their actual request/plan, not the retention flag.
        return "FAILED".equals(text(map(retained.get("latestPlan")),"status"))
            ||CheckResolution.retryable(retained.get("modelDiagnostics"))
            ||"REPAIR_RETRY_PENDING".equals(text(map(retained.get("compilationAudit")),"state"));
    }

    static boolean fieldProcessingFailure(Map<String,Object> row) {
        String code=text(row,"errorCode");if(cn.bidcheck.compat.Java8Api.isBlank(code))code=text(row,"reason");
        return "FAILED".equals(text(row,"status"))&&!"FIELD_VALIDATION_FAILED".equals(code);
    }
}
