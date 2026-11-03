package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.function.Function;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Finalizes host-owned progress receipts without changing a fact rejection into acceptance. */
final class AgentTargetWorkReceipts {
    static final String VERSION="target-work-receipt-1-exact-submit-scope";
    private AgentTargetWorkReceipts() {}

    /**
     * INFERRED candidates and argument validation can return before the ordinary fact validator.
     * They still need the same single-target work receipt. Navigation/recompute may publish a
     * global snapshot, but a submit rejection must never carry other targets' work identities.
     * Returns a compact audit; mutates only receipt metadata, except corrupt host receipts fail closed.
     */
    static Map<String,Object> complete(String tool,Map<String,Object> args,Map<String,Object> result,
            List<Rule> rules,AgentTargetWorkflow workflow,Function<Rule,String> entity,
            Function<Rule,String> revision) {
        String requested=str(args,"targetObligationId");
        boolean submit="submit_candidate_fact".equals(tool);
        Rule target=rules.stream().filter(r->r.id().equals(requested)).findFirst().orElse(null);
        List<String> beforeTargets=targets(result.get("semanticWorkItems"));
        String action;
        if(!submit){
            if(!result.containsKey("semanticWorkItems"))result.put("semanticWorkItems",cn.bidcheck.compat.Java8Api.toList(rules.stream()
                .map(r->workflow.state(r,entity.apply(r),revision.apply(r)))));
            action="GLOBAL_HOST_SNAPSHOT";
        }else if(target==null){
            // There is no legitimate target to invent. The worker normally rejects this before dispatch.
            result.put("semanticWorkItems",cn.bidcheck.compat.Java8Api.listOf());
            action="INVALID_CALL_TARGET_NO_WORK_IDENTITY";
        }else if(result.containsKey("semanticWorkItems")){
            Object supplied=result.get("semanticWorkItems");
            if(!validSingleTarget(supplied,target.id())){
                String originalStatus=str(result,"status"),originalError=str(result,"error");
                result.put("status","FAILED");result.put("error","HOST_TARGET_RECEIPT_INVALID");
                result.put("receiptOriginalStatus",originalStatus);result.put("receiptOriginalError",originalError);
                result.put("semanticWorkItems",cn.bidcheck.compat.Java8Api.listOf());
                result.put("receiptFailureReason","Explicit host submit receipt must contain exactly the called target with valid work and evidence revisions.");
                action="EXPLICIT_RECEIPT_REJECTED";
            }else action="EXPLICIT_SINGLE_TARGET_RECEIPT_PRESERVED";
        }else{
            String version=revision.apply(target),subject=entity.apply(target);
            boolean rejected="REJECTED".equals(str(result,"status"));
            List<Map<String,Object>> issues=new ArrayList<>(RuleEvaluator.maps(result.get("rejections")));
            if(rejected&&issues.isEmpty())issues.add(linked("code",cn.bidcheck.compat.Java8Api.isBlank(str(result,"error"))?"FACT_VALIDATION_REJECTED":str(result,"error")));
            Map<String,Object> work=rejected?workflow.observe(target,subject,version,issues):workflow.state(target,subject,version);
            result.put("semanticWorkItems",cn.bidcheck.compat.Java8Api.listOf(work));
            if(rejected){
                result.put("affectedTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf(target.id()));
                result.putIfAbsent("rejectionEvidenceRevision",version);
                result.putIfAbsent("businessProof",false);
            }
            action=rejected?"EARLY_REJECTION_SCOPED_AND_OBSERVED":"TARGET_STATE_ATTACHED";
        }
        List<Map<String,Object>> after=RuleEvaluator.maps(result.get("semanticWorkItems"));
        return linked("version",VERSION,"tool",tool,"requestedTargetObligationId",requested,
            "action",action,"priorTargetObligationIds",beforeTargets,
            "returnedTargetObligationIds",targets(after),"status",result.getOrDefault("status",""),
            "error",result.getOrDefault("error",""),"receiptOriginalError",result.getOrDefault("receiptOriginalError",""),
            "workItems",cn.bidcheck.compat.Java8Api.toList(after.stream().map(w->linked("workKey",w.get("workKey"),"targetObligationId",w.get("targetObligationId"),
                "evidenceRevision",w.get("evidenceRevision"),"failureFamily",w.get("failureFamily"),
                "terminalForRevision",w.get("terminalForRevision"),"targetExhaustedForRevision",w.get("targetExhaustedForRevision")))),
            "businessDecisionChanged",false,"boundary","Work receipt completion is a protocol repair, not proof that a business condition is satisfied.");
    }

    private static boolean validSingleTarget(Object raw,String target){
        if(!(raw instanceof List<?>)||((List<?>) (raw)).size()!=1||!(((List<?>) (raw)).get(0) instanceof Map<?,?>))return false;
        return target.equals(((Map<?,?>) (((List<?>) (raw)).get(0))).get("targetObligationId"))&&validRevision(((Map<?,?>) (((List<?>) (raw)).get(0))).get("workKey"))&&validRevision(((Map<?,?>) (((List<?>) (raw)).get(0))).get("evidenceRevision"));
    }
    private static boolean validRevision(Object raw){return raw instanceof String&&!cn.bidcheck.compat.Java8Api.isBlank(((String) (raw)))&&((String) (raw)).length()<=256;}
    private static List<String> targets(Object raw){return cn.bidcheck.compat.Java8Api.toList(RuleEvaluator.maps(raw).stream().map(w->str(w,"targetObligationId")));}
}
