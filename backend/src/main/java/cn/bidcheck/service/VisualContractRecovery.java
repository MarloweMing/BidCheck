package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import cn.bidcheck.verification.engine.RecoveryTargets;
import cn.bidcheck.verification.source.VisualObservationPolicy;
import java.util.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

/** Route a source-issued signing-date observation to the declared row owning that original.
 * This receipt grants neither fact admission nor a business verdict. */
final class VisualContractRecovery {
    private VisualContractRecovery() {}

    static Map<String,Object> bind(Finding before,List<Map<String,Object>> added,Map<String,Object> context) {
        Map<String,Object> atomic=atomic(before);
        Map<String,Map<String,Object>> reads=RecoveryTargets.readDependencies(map(atomic.get("plan")).get("rules"));
        List<Map<String,Object>> requests=maps(atomic.get("visualRequests"));
        Set<String> relevantParents=new TreeSet<>();
        Map<String,Object> work=new TreeMap<>();
        for(Map<String,Object> obs:added){
            String owner=text(obs,"obligationId");
            if(owner.isEmpty())continue; // Never give a generic observation the authority of another on the same page.
            if(!"CLEAR".equals(text(obs,"readability")) || !VisualObservationPolicy.validSourceBounds(obs))return Collections.emptyMap();
            Map<String,Object> source=map(obs.get("source"));
            int page=number(obs.get("page"));Set<String> refs=refs(obs.get("requestedSourceIds"));
            if(refs.isEmpty() || page<=0 || page!=number(source.get("page"))
                || !text(obs,"documentId").equals(text(source,"documentId")) || text(obs,"imageHash").isEmpty()
                || !text(obs,"imageHash").equals(text(source,"renderedSha256")) || !"FIELD".equals(text(obs,"requestedTask")) || !"FIELD".equals(text(obs,"type"))
                || !signingField(text(obs,"label"))
                || refs(obs.get("requestedFields")).stream().noneMatch(VisualContractRecovery::signingField))return Collections.emptyMap();
            boolean requested=requests.stream().anyMatch(req->"CONTRACT_SIGNING_DATE_ORIGINAL_REQUIRED".equals(text(req,"reason"))
                && owner.equals(text(req,"obligationId"))
                && page==number(req.getOrDefault("page",req.get("location"))) && text(source,"documentId").equals(text(req,"documentId"))
                && refs(req.get("sourceIds")).equals(refs) && refs(req.get("fields")).stream().anyMatch(VisualContractRecovery::signingField));
            if(!requested)return Collections.emptyMap();
            List<String> matches=new ArrayList<>();
            for(Map.Entry<String,Map<String,Object>> e:reads.entrySet()){
                String target=e.getKey(),parent=text(e.getValue(),"parentId");
                Map<String,Object> row=row(e.getValue());
                if((owner.equals(parent)||owner.equals(target)) && !row.isEmpty()
                    && text(source,"documentId").equals(text(row,"documentId"))
                    && !text(source,"documentHash").isEmpty() && text(source,"documentHash").equals(text(row,"documentHash"))
                    && number(source.get("documentVersion"))>0 && number(source.get("documentVersion"))==number(row.get("documentVersion"))
                    && "PAGE".equals(text(obs,"locationType")) && number(row.get("proofPageStart"))>=1
                    && number(row.get("proofPageStart"))<=page && page<=number(row.get("proofPageEnd")))matches.add(target);
            }
            if(matches.size()!=1 || !VisualObservationPolicy.assessBoundContractSigningLine(obs).valueUsable())return Collections.emptyMap();
            String target=matches.get(0),parent=text(reads.get(target),"parentId");
            relevantParents.add(parent);
            List<Map<String,Object>> entries=new ArrayList<>(maps(work.get(target)));
            entries.add(fields("parentId",parent,"field","signedDate","page",page,"documentId",text(source,"documentId"),
                "documentHash",text(source,"documentHash"),"documentVersion",number(source.get("documentVersion")),
                "requestedSourceIds",new ArrayList<>(refs),"observationId",text(obs,"id")));
            work.put(target,entries);
        }
        if(work.isEmpty())return Collections.emptyMap();
        context.put("recoveryTargetObligationIds",new ArrayList<>(work.keySet()));
        context.put("recoveryDependencyRevision",RecoveryInputs.dependencyKey(context));
        Map<String,Object> scope=fields("version","visual-contract-row-recovery-1","byTarget",work,"parents",new ArrayList<>(relevantParents),
            "businessProof",false);
        context.put("visualContractRecoveryScope",scope);
        return scope;
    }

    static Map<String,Object> continuation(Finding before,Finding after,Map<String,Object> scope,Map<String,Object> nextPlan,
            Map<String,Object> existing,List<Map<String,Object>> diagnostics,String policy) {
        if(scope.isEmpty() || !"TARGETED_EVIDENCE".equals(text(nextPlan,"strategy")) || !ACTIVE.contains(text(nextPlan,"status"))
            || RecoveryTransition.hasProcessingFailure(after) || !diagnostics.isEmpty())return existing;
        Object oldRules=map(atomic(before).get("plan")).get("rules"),rules=map(atomic(after).get("plan")).get("rules");
        if(!Objects.equals(oldRules,rules))return existing; // a changed rule set is a separate, executable input
        Map<String,Map<String,Object>> reads=RecoveryTargets.readDependencies(rules);
        Set<String> affected=map(scope.get("byTarget")).keySet();
        if(affected.isEmpty() || !reads.keySet().containsAll(affected))return existing;
        // Compare each executable leaf on its own. Never let an umbrella scoring id expand
        // to every child, even when another independent requirement really did gain evidence.
        Set<String> parents=new HashSet<>();
        reads.values().forEach(read->parents.add(text(read,"parentId")));
        Set<String> candidates=new LinkedHashSet<>(reads.keySet());
        for(Map<String,Object> condition:maps(atomic(after).get("conditions"))){
            String id=text(condition,"id");
            if(!id.isEmpty()&&!parents.contains(id))candidates.add(id);
        }
        Set<String> allowed=new TreeSet<>();
        List<String> candidateList=new ArrayList<>(candidates);
        Map<String,Set<String>> old=evidence(before,candidateList,policy), fresh=evidence(after,candidateList,policy);
        for(String target:candidates){
            Set<String> newKeys=new HashSet<>(fresh.getOrDefault(target,Collections.emptySet()));
            newKeys.removeAll(old.getOrDefault(target,Collections.emptySet()));
            // The signed date was just fed to the program. It is not new *text work* for that row.
            // Independent accepted evidence can still wake a sibling, or an explicitly triggered target.
            boolean declaredRow=!row(reads.getOrDefault(target,Collections.emptyMap())).isEmpty();
            // A subject/social read can inherit the parent proof in its compact finding.
            // Only target-relevant canonical facts can wake such a sibling.
            if(!declaredRow && reads.containsKey(target))newKeys.removeIf(key->!key.startsWith("FACT:"));
            if(!affected.contains(target) && !newKeys.isEmpty())allowed.add(target);
            if(affected.contains(target) && newNonDateProof(before,after,reads.get(target)))allowed.add(target);
            if(hasNewRead(before,after,target))allowed.add(target);
        }
        for(String target:refs(existing.get("dependencyTargetObligationIds"))){
            Map<String,Object> byTarget=map(map(existing.get("dependencyTrigger")).get("byTarget"));
            if(!refs(map(byTarget.get(target)).get("semanticEvidenceKeys")).isEmpty())allowed.add(target);
        }
        List<String> pending=RecoveryInputs.pendingTargets(after,allowed);
        if(pending.isEmpty()){
            nextPlan.putAll(fields("status","BLOCKED_NO_PROGRESS","strategy","BLOCKED",
                "reason","指定合同原图字段已由程序重算，其他申报行没有新增可执行输入。",
                "nextAction","保留未闭合的合同条件，收到该行的新原件、事实或规则后继续核验。",
                "visualFieldFollowUp","NO_NEW_TEXT_INPUT"));
            return Collections.emptyMap();
        }
        Map<String,Object> continuation=new LinkedHashMap<>(existing);
        continuation.put("dependencyTargetObligationIds",pending);
        return continuation;
    }

    private static Map<String,Set<String>> evidence(Finding finding,List<String> targets,String policy){
        Map<String,Set<String>> result=new HashMap<>();
        for(Map<String,Object> input:RecoverySemanticGate.inputs(finding,fields("recoveryTargetObligationIds",targets),policy))
            result.put(text(input,"targetId"),refs(input.get("evidenceKeys")));
        return result;
    }
    private static boolean hasNewRead(Finding before,Finding after,String target) {
        Set<String> prior=new HashSet<>(), current=new HashSet<>();
        for(Map<String,Object> r:PendingTableWork.pending(before,target))prior.add(RecoveryEvidenceIdentity.canonical(r));
        for(Map<String,Object> r:PendingTableWork.pending(after,target))current.add(RecoveryEvidenceIdentity.canonical(r));
        current.removeAll(prior);return !current.isEmpty();
    }
    private static boolean newNonDateProof(Finding before,Finding after,Map<String,Object> read) {
        Set<String> prior=rowEvidence(before,read),current=rowEvidence(after,read);
        current.removeAll(prior);return !current.isEmpty();
    }
    private static Set<String> rowEvidence(Finding finding,Map<String,Object> read){
        Set<String> values=new HashSet<>();String inventory=text(row(read),"inventoryIdentity");
        for(Map<String,Object> condition:maps(atomic(finding).get("conditions"))){
            if(!text(read,"parentId").equals(text(condition,"id")))continue;
            for(Map<String,Object> check:maps(map(condition.get("proof")).get("contractChecks")))
                if(inventory.equals(text(map(check.get("declaredRow")),"inventoryIdentity")))
                    for(String field:Arrays.asList("amountEvidence","partyWorkRoleProof","partySideEvidence","historicalAppointmentEvidence"))
                        collectReferences(check.get(field),field,values);
        }
        return values;
    }
    private static void collectReferences(Object node,String field,Set<String> ids){
        if(node instanceof Collection<?>){for(Object value:(Collection<?>)node)collectReferences(value,field,ids);return;}
        if(!(node instanceof Map<?,?>))return;
        Map<String,Object> map=map(node);
        for(String key:Arrays.asList("sourceIds","factIds"))for(String id:refs(map.get(key)))ids.add(field+"|"+key+"|"+id);
        for(Object value:map.values())collectReferences(value,field,ids);
    }
    private static boolean signingField(String field){return cn.bidcheck.compat.Java8Api.setOf("签订日期","合同签订日期","合同签订时间","签订时间","signedDate").contains(field);}
    private static Map<String,Object> row(Map<String,Object> read){return map(map(map(read.get("rule")).get("parameters")).get("declaredPerformanceRow"));}
    private static Set<String> refs(Object values){Set<String> out=new TreeSet<>();if(values instanceof Collection<?>)
        for(Object value:(Collection<?>)values)if(value instanceof String&&!((String)value).trim().isEmpty())out.add((String)value);return out;}
    private static int number(Object value){return value instanceof Number?((Number)value).intValue():-1;}
}
