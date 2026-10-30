package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import cn.bidcheck.verification.engine.RecoveryDependencyGate;
import java.util.*;
import java.util.function.Function;
import static cn.bidcheck.service.BidService.*;

/** Local-only consumption of group observations. A consumed delivery never supplies a verdict. */
final class AgentGroupRecovery {
    static final String VERSION="agent-group-local-recovery-1";
    static final String CONSUMED="AGENT_GROUP_RECOVERY_CONSUMED";
    record Outcome(Finding finding,boolean attempted,boolean published,Map<String,Object> context,
                   Map<String,Object> consumedStep,Map<String,Object> audit) {}
    private AgentGroupRecovery() {}

    static Outcome execute(Finding before,AgentBusinessGroupReuse.Membership group,List<Finding> members,
                           Function<String,List<Map<String,Object>>> steps,Map<String,Object> base,String policy,
                           Function<Map<String,Object>,Finding> localEvaluator){
        if(group==null)return new Outcome(before,false,false,base,Map.of(),Map.of());
        String target=before.checkpoint().id();Map<String,Object> context=RecoveryInputs.context(before,"RECOMPUTE_SHARED_EVIDENCE",base);
        String input=CheckResolution.actionFingerprint(before,policy,"","RECOMPUTE_SHARED_EVIDENCE");
        List<Map<String,Object>> prior=CheckResolution.maps(context.get("priorSteps"));Set<String> consumed=new HashSet<>();
        for(Map<String,Object> step:prior)if(CONSUMED.equals(text(step,"type"))&&"COMPLETED".equals(text(step,"status"))
                &&target.equals(text(step,"checkpointId"))&&group.groupId().equals(text(step,"reuseGroupId"))){
            Map<String,Object> output=CheckResolution.map(step.get("output"));
            if(input.equals(text(output,"targetInputFingerprint")))consumed.addAll(stringList(output.get("receiptFingerprints")));
        }
        Map<String,AgentBusinessGroupReuse.EvidenceReceipt> eligible=new TreeMap<>();int available=0;
        for(Finding member:members){
            if(!before.runId().equals(member.runId())||!group.checkpointIds().contains(member.checkpoint().id())||target.equals(member.checkpoint().id()))continue;
            var receipt=AgentBusinessGroupReuse.evidenceReceipt(group,target,member,steps.apply(member.checkpoint().id()));
            if(!receipt.reusable())continue;available++;
            if(!consumed.contains(receipt.fingerprint()))eligible.putIfAbsent(receipt.fingerprint(),receipt);
        }
        Map<String,Object> audit=fields("version",VERSION,"groupId",group.groupId(),"availableReceiptCount",available,
            "consumedReceiptCount",consumed.size(),"newReceiptCount",eligible.size(),"localOnly",true,"newModelCalls",0,
            "businessDecisionCopied",false,"targetInputFingerprint",input);
        if(eligible.isEmpty())return new Outcome(before,false,false,base,Map.of(),audit);
        Set<String> sourceIds=new LinkedHashSet<>(stringList(context.get("recoveryChangedSourceIds")));
        for(var receipt:eligible.values()){
            AgentBusinessGroupReuse.attach(receipt,context);sourceIds.addAll(receipt.sourceIds());
        }
        // A crash after receipt publication but before consumption still permits local replay.
        // The semantic trigger tracks content; delivery IDs never authorize another model request.
        context.put("recoveryChangedSourceIds",List.copyOf(sourceIds));
        Map<String,Object> trigger=new LinkedHashMap<>(CheckResolution.map(context.get("recoveryDependencyTrigger")));
        Set<String> semanticKeys=new TreeSet<>(stringList(trigger.get("semanticEvidenceKeys")));
        eligible.keySet().forEach(key->semanticKeys.add("AGENT_GROUP_RECEIPT:"+key));
        trigger.put("semanticEvidenceKeys",List.copyOf(semanticKeys));trigger.put("sourceIds",List.copyOf(sourceIds));
        context.put("recoveryDependencyTrigger",trigger);context.put("recoveryDependencyRevision",RecoveryInputs.dependencyKey(context));
        context.put("recoveryMode","EVIDENCE_RECOMPUTE");context.put("recoveryModelAllowed",false);
        context.put("modelAttemptPermit",(java.util.function.BooleanSupplier)()->false);
        context.put("agentGroupRecoveryLocalOnly",true);context.put("agentGroupRecoveryReuse",audit);
        Finding candidate=before;String noop="";long started=System.nanoTime();
        try{candidate=Objects.requireNonNull(localEvaluator.apply(context));}
        catch(RecoveryDependencyGate.NoDependencyChange unchanged){noop=unchanged.reasonCode();}
        boolean publish=completeBusinessProgress(before,candidate);
        audit.put("localRecomputed",true);audit.put("businessProofProgress",publish);audit.put("sourceCount",sourceIds.size());
        audit.put("noopReason",noop);audit.put("published",publish);audit.put("durationMs",(System.nanoTime()-started)/1_000_000);
        Map<String,Object> consumption=fields("id",stableId(VERSION+":"+group.groupId()+":"+target+":"+input+":"+eligible.keySet()),
            "type",CONSUMED,"status","COMPLETED","checkpointId",target,"reuseGroupId",group.groupId(),
            "output",fields("targetInputFingerprint",input,"receiptFingerprints",List.copyOf(eligible.keySet()),
                "businessProofProgress",publish,"localOnly",true,"newModelCalls",0,"beforeOutcome",CheckResolution.outcomeFingerprint(before),
                "publishedOutcome",CheckResolution.outcomeFingerprint(publish?candidate:before)));
        // Failed/partial local execution cannot silently erase the old Agent technical failure.
        // The caller publishes this marker with the chosen finding in the same transaction.
        return new Outcome(publish?candidate:before,true,publish,context,consumption,audit);
    }

    private static boolean completeBusinessProgress(Finding before,Finding candidate){
        if(!before.runId().equals(candidate.runId())||!before.checkpoint().equals(candidate.checkpoint())
                ||CheckResolution.unresolved(candidate)||!Set.of("PASS","FAIL","NOT_APPLICABLE").contains(candidate.evaluation().status()))return false;
        Map<String,Object> atomic=CheckResolution.atomic(candidate);
        if(!Boolean.TRUE.equals(atomic.get("complete"))||!CheckResolution.maps(atomic.get("gaps")).isEmpty()
                ||candidate.evaluation().evidences()==null||candidate.evaluation().evidences().isEmpty()
                ||!Boolean.TRUE.equals(CheckResolution.progress(before,candidate).get("progressMade")))return false;
        return CheckResolution.maps(atomic.get("conditions")).stream().anyMatch(condition->"REQUIREMENT".equals(text(condition,"conditionRole"))
            &&Set.of("PASS","FAIL","NOT_APPLICABLE").contains(text(condition,"status"))
            &&CheckResolution.maps(condition.get("gaps")).isEmpty()
            &&condition.get("evidences") instanceof Collection<?> evidence&&!evidence.isEmpty());
    }

    /** Carry only genuinely new semantic evidence into the old missing-target strategy. */
    static void carryChangedInputs(Outcome outcome,Map<String,Object> context){
        if(outcome==null||!outcome.attempted())return;
        Set<String> sources=new LinkedHashSet<>(stringList(context.get("recoveryChangedSourceIds")));sources.addAll(stringList(outcome.context().get("recoveryChangedSourceIds")));
        context.put("recoveryChangedSourceIds",List.copyOf(sources));
        Map<String,Object> trigger=new LinkedHashMap<>(CheckResolution.map(context.get("recoveryDependencyTrigger")));
        Set<String> keys=new TreeSet<>(stringList(trigger.get("semanticEvidenceKeys")));
        keys.addAll(stringList(CheckResolution.map(outcome.context().get("recoveryDependencyTrigger")).get("semanticEvidenceKeys")));
        trigger.put("semanticEvidenceKeys",List.copyOf(keys));trigger.put("sourceIds",List.copyOf(sources));context.put("recoveryDependencyTrigger",trigger);
        context.put("recoveryDependencyRevision",RecoveryInputs.dependencyKey(context));
    }
    private static List<String> stringList(Object value){return value instanceof Collection<?> rows?rows.stream()
        .filter(Objects::nonNull).map(Object::toString).filter(s->!s.isBlank()).toList():List.of();}
}
