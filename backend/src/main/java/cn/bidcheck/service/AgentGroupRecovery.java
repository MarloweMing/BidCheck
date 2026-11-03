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
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Outcome {
        private final Finding finding;
        private final boolean attempted;
        private final boolean published;
        private final Map<String,Object> context;
        private final Map<String,Object> consumedStep;
        private final Map<String,Object> audit;
        @com.fasterxml.jackson.annotation.JsonCreator
        Outcome(@com.fasterxml.jackson.annotation.JsonProperty("finding") Finding finding, @com.fasterxml.jackson.annotation.JsonProperty("attempted") boolean attempted, @com.fasterxml.jackson.annotation.JsonProperty("published") boolean published, @com.fasterxml.jackson.annotation.JsonProperty("context") Map<String,Object> context, @com.fasterxml.jackson.annotation.JsonProperty("consumedStep") Map<String,Object> consumedStep, @com.fasterxml.jackson.annotation.JsonProperty("audit") Map<String,Object> audit) { this.finding = finding; this.attempted = attempted; this.published = published; this.context = context; this.consumedStep = consumedStep; this.audit = audit; }
        public Finding finding() { return finding; }
        public boolean attempted() { return attempted; }
        public boolean published() { return published; }
        public Map<String,Object> context() { return context; }
        public Map<String,Object> consumedStep() { return consumedStep; }
        public Map<String,Object> audit() { return audit; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Outcome that = (Outcome) other; return java.util.Objects.equals(this.finding, that.finding) && java.util.Objects.equals(this.attempted, that.attempted) && java.util.Objects.equals(this.published, that.published) && java.util.Objects.equals(this.context, that.context) && java.util.Objects.equals(this.consumedStep, that.consumedStep) && java.util.Objects.equals(this.audit, that.audit); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.finding); hash = 31 * hash + java.util.Objects.hashCode(this.attempted); hash = 31 * hash + java.util.Objects.hashCode(this.published); hash = 31 * hash + java.util.Objects.hashCode(this.context); hash = 31 * hash + java.util.Objects.hashCode(this.consumedStep); hash = 31 * hash + java.util.Objects.hashCode(this.audit); return hash; }
        @Override public String toString() { return "Outcome[" + "finding=" + finding + ", attempted=" + attempted + ", published=" + published + ", context=" + context + ", consumedStep=" + consumedStep + ", audit=" + audit + "]"; }
}
    private AgentGroupRecovery() {}

    static Outcome execute(Finding before,AgentBusinessGroupReuse.Membership group,List<Finding> members,
                           Function<String,List<Map<String,Object>>> steps,Map<String,Object> base,String policy,
                           Function<Map<String,Object>,Finding> localEvaluator){
        if(group==null)return new Outcome(before,false,false,base,cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.mapOf());
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
            cn.bidcheck.service.AgentBusinessGroupReuse.EvidenceReceipt receipt=AgentBusinessGroupReuse.evidenceReceipt(group,target,member,steps.apply(member.checkpoint().id()));
            if(!receipt.reusable())continue;available++;
            if(!consumed.contains(receipt.fingerprint()))eligible.putIfAbsent(receipt.fingerprint(),receipt);
        }
        Map<String,Object> audit=fields("version",VERSION,"groupId",group.groupId(),"availableReceiptCount",available,
            "consumedReceiptCount",consumed.size(),"newReceiptCount",eligible.size(),"localOnly",true,"newModelCalls",0,
            "businessDecisionCopied",false,"targetInputFingerprint",input);
        if(eligible.isEmpty())return new Outcome(before,false,false,base,cn.bidcheck.compat.Java8Api.mapOf(),audit);
        Set<String> sourceIds=new LinkedHashSet<>(stringList(context.get("recoveryChangedSourceIds")));
        for(cn.bidcheck.service.AgentBusinessGroupReuse.EvidenceReceipt receipt:eligible.values()){
            AgentBusinessGroupReuse.attach(receipt,context);sourceIds.addAll(receipt.sourceIds());
        }
        // A crash after receipt publication but before consumption still permits local replay.
        // The semantic trigger tracks content; delivery IDs never authorize another model request.
        context.put("recoveryChangedSourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(sourceIds));
        Map<String,Object> trigger=new LinkedHashMap<>(CheckResolution.map(context.get("recoveryDependencyTrigger")));
        Set<String> semanticKeys=new TreeSet<>(stringList(trigger.get("semanticEvidenceKeys")));
        eligible.keySet().forEach(key->semanticKeys.add("AGENT_GROUP_RECEIPT:"+key));
        trigger.put("semanticEvidenceKeys",cn.bidcheck.compat.Java8Api.listCopyOf(semanticKeys));trigger.put("sourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(sourceIds));
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
            "output",fields("targetInputFingerprint",input,"receiptFingerprints",cn.bidcheck.compat.Java8Api.listCopyOf(eligible.keySet()),
                "businessProofProgress",publish,"localOnly",true,"newModelCalls",0,"beforeOutcome",CheckResolution.outcomeFingerprint(before),
                "publishedOutcome",CheckResolution.outcomeFingerprint(publish?candidate:before)));
        // Failed/partial local execution cannot silently erase the old Agent technical failure.
        // The caller publishes this marker with the chosen finding in the same transaction.
        return new Outcome(publish?candidate:before,true,publish,context,consumption,audit);
    }

    private static boolean completeBusinessProgress(Finding before,Finding candidate){
        if(!before.runId().equals(candidate.runId())||!before.checkpoint().equals(candidate.checkpoint())
                ||CheckResolution.unresolved(candidate)||!cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(candidate.evaluation().status()))return false;
        Map<String,Object> atomic=CheckResolution.atomic(candidate);
        if(!Boolean.TRUE.equals(atomic.get("complete"))||!CheckResolution.maps(atomic.get("gaps")).isEmpty()
                ||candidate.evaluation().evidences()==null||candidate.evaluation().evidences().isEmpty()
                ||!Boolean.TRUE.equals(CheckResolution.progress(before,candidate).get("progressMade")))return false;
        return CheckResolution.maps(atomic.get("conditions")).stream().anyMatch(condition->"REQUIREMENT".equals(text(condition,"conditionRole"))
            &&cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(text(condition,"status"))
            &&CheckResolution.maps(condition.get("gaps")).isEmpty()
            &&condition.get("evidences") instanceof Collection<?>&&!((Collection<?>) (condition.get("evidences"))).isEmpty());
    }

    /** Carry only genuinely new semantic evidence into the old missing-target strategy. */
    static void carryChangedInputs(Outcome outcome,Map<String,Object> context){
        if(outcome==null||!outcome.attempted())return;
        Set<String> sources=new LinkedHashSet<>(stringList(context.get("recoveryChangedSourceIds")));sources.addAll(stringList(outcome.context().get("recoveryChangedSourceIds")));
        context.put("recoveryChangedSourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(sources));
        Map<String,Object> trigger=new LinkedHashMap<>(CheckResolution.map(context.get("recoveryDependencyTrigger")));
        Set<String> keys=new TreeSet<>(stringList(trigger.get("semanticEvidenceKeys")));
        keys.addAll(stringList(CheckResolution.map(outcome.context().get("recoveryDependencyTrigger")).get("semanticEvidenceKeys")));
        trigger.put("semanticEvidenceKeys",cn.bidcheck.compat.Java8Api.listCopyOf(keys));trigger.put("sourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(sources));context.put("recoveryDependencyTrigger",trigger);
        context.put("recoveryDependencyRevision",RecoveryInputs.dependencyKey(context));
    }
    private static List<String> stringList(Object value){return value instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream()
        .filter(Objects::nonNull).map(Object::toString).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s))):cn.bidcheck.compat.Java8Api.listOf();}
}
