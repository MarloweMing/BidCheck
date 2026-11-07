package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import java.util.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

/** Identity of the unfinished work for one target. Evidence publication IDs never authorize a model call. */
final class RecoveryGapKeys {
    private RecoveryGapKeys() {}

    static Map<String,Object> workReceipt(Finding finding,String target,Map<String,Object> previous){
        Map<String,Object> receipt=new LinkedHashMap<>(previous);
        Set<String> reads=new TreeSet<>(strings(receipt.get("readSourceIds")));
        Map<String,Object> atomic=atomic(finding);
        Map<String,Object> audit=map(atomic.get("agentInvestigation"));
        if("CURRENT_SERVER_PROOF".equals(text(audit,"targetReceiptAuthority"))){
            Map<String,Object> host=map(audit.get("serverTargetInputConsumption"));
            for(Map<String,Object> row:maps(host.get("targets")))if(target.equals(text(row,"targetObligationId")))
                reads.addAll(strings(map(row.get("readCoverage")).get("readSourceIds")));
            for(Map<String,Object> rejection:maps(atomic.get("rejectedFacts"))){
                if(target.equals(text(rejection,"targetObligationId"))||strings(rejection.get("affectedTargetObligationIds")).contains(target)){
                    receipt.put("lastRejection",fields("code",text(rejection,"code"),"fieldPath",text(rejection,"fieldPath")));
                }
            }
        }
        receipt.put("readSourceIds",new ArrayList<>(reads));
        receipt.put("pendingTableReads",PendingTableWork.pending(finding,target));
        return receipt;
    }

    static String key(Finding finding,String target,Map<String,Object> work){
        Map<String,Object> atomic=atomic(finding),rule=Collections.emptyMap();
        for(Map<String,Object> dependency:maps(atomic.get("recoveryEvidenceDependencies")))
            if(target.equals(text(dependency,"obligationId"))){rule=map(dependency.get("target"));break;}
        if(rule.isEmpty())rule=findRule(maps(map(atomic.get("plan")).get("rules")),target);
        String inventory=text(map(map(rule.get("parameters")).get("declaredPerformanceRow")),"inventoryIdentity");
        Set<String> codes=new TreeSet<>(),missing=new TreeSet<>(),versions=new TreeSet<>();
        List<Map<String,Object>> conditions=new ArrayList<>(maps(atomic.get("conditions")));
        conditions.addAll(maps(atomic.get("obligations")));
        for(Map<String,Object> condition:conditions){
            boolean isTarget=target.equals(text(condition,"id"));
            Map<String,Object> proof=map(condition.get("proof"));
            boolean relevant=isTarget;
            for(Map<String,Object> check:maps(proof.get("contractChecks"))){
                String row=text(map(check.get("declaredRow")),"inventoryIdentity");
                if(inventory.isEmpty()?!isTarget:!inventory.equals(row))continue;
                relevant=true;missing.addAll(strings(check.get("missing")));
            }
            if(relevant){
                maps(condition.get("gaps")).forEach(g->codes.add(text(g,"code")));
                versions.add(text(proof,"ruleVersion"));
            }
        }
        codes.remove("");versions.remove("");
        // Older persisted jobs sometimes have only an unscoped checkpoint gap and no rule.
        // There is no per-target comparison to make in that case; retain the legacy admission gate.
        if(codes.isEmpty()&&missing.isEmpty()
                &&(rule.isEmpty()||maps(map(atomic.get("plan")).get("rules")).isEmpty()))return "";
        Map<String,Object> descriptor=fields("target",target,"rules",RecoveryInputs.ruleInput(rule),
            "targetExpected",rule.get("targetExpected"),"ruleVersions",versions,
            "engine",text(atomic,"version"),"gaps",codes,"missing",missing,
            "reads",new TreeSet<>(strings(work.get("readSourceIds"))),"rejection",map(work.get("lastRejection")));
        if(!maps(work.get("pendingTableReads")).isEmpty())descriptor.put("pendingTableReads",work.get("pendingTableReads"));
        return hash(RecoveryEvidenceIdentity.canonical(descriptor));
    }

    static void retainUnconsumed(Finding finding,Map<String,Object> job,Map<String,Object> gate){
        if(!"TARGETED_EVIDENCE".equals(text(job,"strategy"))||!Boolean.TRUE.equals(gate.get("allowed")))return;
        Set<String> consumed=new HashSet<>(strings(job.get("consumedGapKeys")));
        if(consumed.isEmpty())return;
        Set<String> blocked=new TreeSet<>(strings(gate.get("blockedTargetIds")));
        List<String> allowed=new ArrayList<>();boolean skipped=false;
        for(Map<String,Object> input:maps(gate.get("inputs"))){
            String target=text(input,"targetId");
            if(blocked.contains(target))continue;
            String key=key(finding,target,workReceipt(finding,target,map(map(job.get("gapWorkByTarget")).get(target))));
            if(consumed.contains(key)){blocked.add(target);skipped=true;}else allowed.add(target);
        }
        if(!skipped)return;
        gate.putAll(fields("allowed",!allowed.isEmpty(),"blockedTargetIds",new ArrayList<>(blocked),
            "eligibleTargetIds",allowed,"reasonCode",allowed.isEmpty()?"GAP_KEY_ALREADY_INVESTIGATED":"PARTIAL_TARGET_PROGRESS",
            "description","只调查尚未使用过的目标缺口。"));
    }

    /** Written in the same transaction as RUNNING, before a model or tool is invoked.
     * Each target has its own input. A provider failure may still retry that technical step. */
    static void beginTechnicalRetry(Map<String,Object> job,Finding before,Map<String,Object> gate){
        if(!"RETRY_FAILED_STEP".equals(text(job,"strategy")))return;
        Map<String,Object> history=new TreeMap<>(map(job.get("gapWorkByTarget")));
        Map<String,Object> keys=new TreeMap<>();
        Set<String> consumed=new TreeSet<>(strings(job.get("consumedGapKeys")));
        Set<String> blocked=new HashSet<>(strings(gate.get("blockedTargetIds")));
        for(Map<String,Object> input:maps(gate.get("inputs"))){
            String target=text(input,"targetId");
            if(target.isEmpty()||blocked.contains(target))continue;
            Map<String,Object> work=workReceipt(before,target,map(history.get(target)));
            String key=key(before,target,work);
            // A legacy finding with no executable target or comparable gap must keep
            // its existing routing. Never persist the semantic gate's checkpoint sentinel
            // as a scoped target for a later worker.
            if(key.isEmpty() && "__CHECKPOINT__".equals(target))continue;
            history.put(target,work);
            keys.put(target,key);
            if(!key.isEmpty())consumed.add(key);
        }
        job.put("retryGapKeysBefore",keys);job.put("gapWorkByTarget",history);
        job.put("consumedGapKeys",new ArrayList<>(consumed));
    }

    /** A read or rejection generated by the retry describes work already executed. To
     * distinguish that from a changed rule/gap, compare once on the same operational
     * receipt. Previously unseen, concrete source/field cursors remain executable. */
    static boolean onlyRetryReceiptChanged(Finding before,Finding after,String target,Map<String,Object> work){
        String beforeKey=key(before,target,work);
        if(beforeKey.isEmpty() || !beforeKey.equals(key(after,target,work)))return false;
        Set<String> added=concreteActions(after,target);added.removeAll(concreteActions(before,target));
        return added.isEmpty();
    }

    static Set<String> openRetryTargets(Finding before,Finding after,Set<String> retried) {
        Set<String> targets=new TreeSet<>(retried);
        Map<String,Object> audit=map(atomic(after).get("agentInvestigation"));
        if(!"CURRENT_SERVER_PROOF".equals(text(audit,"targetReceiptAuthority")))return targets;
        Map<String,Object> prior=map(atomic(before).get("agentInvestigation"));
        Set<String> remaining=new HashSet<>(strings(audit.get("remainingTargetObligationIds")));
        // A newly exposed child in the retry's server-issued receipt is a different target.
        // An uninvestigated sibling that was already pending is not automatically added.
        if("CURRENT_SERVER_PROOF".equals(text(prior,"targetReceiptAuthority"))){
            Set<String> opened=new TreeSet<>(remaining);
            opened.removeAll(strings(prior.get("remainingTargetObligationIds")));
            targets.addAll(opened);
        }
        for(String completed:strings(audit.get("completedTargetObligationIds")))
            if(!remaining.contains(completed))targets.remove(completed);
        targets.removeAll(strings(audit.get("pendingConfirmationTargetObligationIds")));
        return targets;
    }

    private static Set<String> concreteActions(Finding finding,String target){
        Set<String> actions=new TreeSet<>();
        for(Map<String,Object> cursor:PendingTableWork.pending(finding,target))actions.add(RecoveryEvidenceIdentity.canonical(cursor));
        Map<String,Object> audit=map(atomic(finding).get("agentInvestigation"));
        Map<String,Object> receipt=map(audit.get("serverTargetInputConsumption"));
        if(!"CURRENT_SERVER_PROOF".equals(text(audit,"targetReceiptAuthority")) || !"CURRENT_SERVER_PROOF".equals(text(receipt,"authority"))
            || !"server-target-input-consumption-1".equals(text(receipt,"version")) || !Boolean.FALSE.equals(receipt.get("businessProof"))
            || text(audit,"requestId").isEmpty() || !text(audit,"requestId").equals(text(receipt,"requestId"))
            || text(audit,"serverPlanRevision").isEmpty() || !text(audit,"serverPlanRevision").equals(text(receipt,"serverPlanRevision")))return actions;
        for(Map<String,Object> row:maps(receipt.get("targets"))){
            if(!target.equals(text(row,"targetObligationId")) || text(row,"evidenceRevision").isEmpty()
                || !text(row,"evidenceRevision").equals(map(audit.get("targetEvidenceRevisions")).get(target)))continue;
            Map<String,Object> plan=map(row.get("contractFieldWorkPlan"));
            for(Map<String,Object> action:maps(plan.get("remainingActions")))
                if(target.equals(text(action,"targetObligationId")) && !text(action,"field").isEmpty()
                    && !strings(action.get("sourceIds")).isEmpty())actions.add(RecoveryEvidenceIdentity.canonical(action));
        }
        return actions;
    }

    static void persist(Map<String,Object> job,Finding before,Finding after,String strategy,
            Map<String,Object> gate,boolean investigationRan){
        if(!"TARGETED_EVIDENCE".equals(strategy)&&!"RECOMPUTE_SHARED_EVIDENCE".equals(strategy))return;
        Map<String,Object> history=new TreeMap<>(map(job.get("gapWorkByTarget")));
        Set<String> consumed=new TreeSet<>(strings(job.get("consumedGapKeys")));
        Set<String> blocked=new HashSet<>(strings(gate.get("blockedTargetIds")));
        List<Map<String,Object>> allowed=new ArrayList<>(maps(gate.get("inputs")));
        allowed.removeIf(input->blocked.contains(text(input,"targetId")));
        Map<String,Object> audit=map(atomic(after).get("agentInvestigation"));
        Set<String> visited=new HashSet<>();
        for(Map<String,Object> entry:maps(audit.get("targetProgressLedger")))
            if(Boolean.TRUE.equals(entry.get("visited"))&&!text(audit,"requestId").isEmpty()
                    &&text(audit,"requestId").equals(text(entry,"noNewFactRequestId")))visited.add(text(entry,"targetObligationId"));
        for(Map<String,Object> input:allowed){
            String target=text(input,"targetId");
            Map<String,Object> prior=workReceipt(before,target,map(history.get(target)));
            if(investigationRan&&"TARGETED_EVIDENCE".equals(strategy)&&(allowed.size()==1||visited.contains(target))){
                String key=key(before,target,prior);if(!key.isEmpty())consumed.add(key);
            }
            history.put(target,workReceipt(after,target,prior));
        }
        job.put("gapWorkByTarget",history);job.put("consumedGapKeys",new ArrayList<>(consumed));
    }

    private static Map<String,Object> findRule(List<Map<String,Object>> rules,String target){
        for(Map<String,Object> rule:rules){
            if(target.equals(text(rule,"id")))return rule;
            Map<String,Object> child=findRule(maps(rule.get("children")),target);
            if(!child.isEmpty())return child;
        }
        return Collections.emptyMap();
    }

    static List<String> strings(Object value){
        List<String> result=new ArrayList<>();
        if(value instanceof Collection<?>)for(Object entry:(Collection<?>)value)
            if(entry instanceof String&&!((String)entry).isEmpty())result.add((String)entry);
        return result;
    }
}
