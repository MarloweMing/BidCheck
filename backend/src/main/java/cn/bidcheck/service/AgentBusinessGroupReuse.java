package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evaluation;
import cn.bidcheck.domain.Domain.Finding;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Conservative execution index for Agent evidence investigations.
 *
 * <p>The frozen-tender identity proves that the obligation, subject, lot, review purpose and
 * restrictions are the same.  Only the evidence investigation is shared.  Every checkpoint is
 * compiled and recomputed independently, so a reused observation can never copy a business
 * decision.</p>
 */
public final class AgentBusinessGroupReuse {
    public static final String VERSION="agent-business-group-reuse-3-semantic-source-receipt";
    static final String FACT_SNAPSHOT="GROUNDED_FACT_SNAPSHOT";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> RECEIPT_METADATA=Set.of("factId","factIds","runId","sourceRunId","batchId",
        "receiptId","snapshotId","executionId","attemptId","reusedFromBatchId","reusedObservation",
        "observedInCurrentRun","groupEvidenceReceipt","agentGroupEvidenceReuse","reusedFromCheckpointId");

    public record Membership(String groupId,String leaderCheckpointId,List<String> checkpointIds,
                             Map<String,Object> identityDimensions) {
        public Membership {
            checkpointIds=List.copyOf(checkpointIds);
            identityDimensions=Collections.unmodifiableMap(new LinkedHashMap<>(identityDimensions));
        }
        public boolean leader(String checkpointId){return leaderCheckpointId.equals(checkpointId);}
        public Map<String,Object> audit(String checkpointId){
            return BidService.fields("version",VERSION,"groupId",groupId,"leaderCheckpointId",leaderCheckpointId,
                "checkpointId",checkpointId,"checkpointIds",checkpointIds,"identityDimensions",identityDimensions,
                "reuseScope","EVIDENCE_INVESTIGATION_ONLY","businessDecisionCopied",false,
                "boundary","仅复用冻结原文证明为同一业务义务的查证事实；每个检查项仍用自己的规则树重新计算结论。");
        }
    }

    public record Plan(Map<String,Membership> memberships,List<Membership> groups) {
        public Plan {
            memberships=Collections.unmodifiableMap(new LinkedHashMap<>(memberships));
            groups=List.copyOf(groups);
        }
        public Membership membership(String checkpointId){return memberships.get(checkpointId);}
        public int reusableGroupCount(){return groups.size();}
        public int reusedCheckpointCount(){return groups.stream().mapToInt(group->group.checkpointIds().size()-1).sum();}
    }

    /**
     * Source-scoped observation receipt fanned out from a completed group leader.
     *
     * <p>The receipt contains no status, condition decision or proof flag.  The follower resolves
     * every source against its current frozen corpus and runs ProofVerifier again before the fact
     * can participate in its independently compiled rule tree.</p>
     */
    public record EvidenceReceipt(Map<String,Object> step,List<String> sourceIds,int factCount,
                                  String planFingerprint,String evidenceRevision,String fingerprint) {
        public EvidenceReceipt {
            step=Collections.unmodifiableMap(new LinkedHashMap<>(step));
            sourceIds=List.copyOf(sourceIds);
        }
        public boolean reusable(){return !step.isEmpty()&&!sourceIds.isEmpty();}
        public Map<String,Object> audit(){
            return BidService.fields("receiptAvailable",reusable(),"receiptFingerprint",fingerprint,
                "validatedSourceCount",sourceIds.size(),"replayedFactCount",factCount,
                "sourcePlanFingerprint",planFingerprint,"sourceEvidenceRevision",evidenceRevision,
                "businessDecisionCopied",false,"sourceRevisionRevalidated",true,
                "boundary","仅扇出带冻结来源元数据的事实快照；接收项重新解析自己的规则树，并再次校验来源版本和事实原文，不复用首项结论。");
        }
    }

    private AgentBusinessGroupReuse() { }

    public static Plan plan(List<Checkpoint> checkpoints,Document tender,Predicate<Checkpoint> eligible){
        Objects.requireNonNull(eligible,"eligible");
        if(checkpoints==null||checkpoints.isEmpty()||tender==null)return new Plan(Map.of(),List.of());
        BusinessObligationIdentity identity=new BusinessObligationIdentity(tender);
        Map<String,List<Candidate>> candidates=new LinkedHashMap<>();
        for(int index=0;index<checkpoints.size();index++){
            Checkpoint checkpoint=checkpoints.get(index);
            if(checkpoint==null||!eligible.test(checkpoint))continue;
            Finding placeholder=new Finding("execution-group:"+checkpoint.id(),"",checkpoint,
                new Evaluation("UNDETERMINED","","",List.of(),0,"EXECUTION_GROUP_IDENTITY"),Map.of());
            BusinessObligationIdentity.Signature signature=identity.signature(placeholder);
            if(!signature.eligible())continue;
            candidates.computeIfAbsent(signature.key(),ignored->new ArrayList<>())
                .add(new Candidate(index,checkpoint,signature));
        }
        Map<String,Membership> memberships=new LinkedHashMap<>();List<Membership> groups=new ArrayList<>();
        for(List<Candidate> equivalent:candidates.values()){
            if(equivalent.size()<2)continue;
            equivalent.sort(Comparator.comparingInt(Candidate::index));
            Candidate leader=equivalent.get(0);List<String> ids=equivalent.stream().map(row->row.checkpoint().id()).toList();
            String groupId=BidService.stableId(VERSION+":"+leader.signature().key());
            Membership group=new Membership(groupId,leader.checkpoint().id(),ids,leader.signature().dimensions());
            groups.add(group);ids.forEach(id->memberships.put(id,group));
        }
        return new Plan(memberships,groups);
    }

    /** Build a fact/source receipt from another actual member's latest append-only snapshot. */
    public static EvidenceReceipt evidenceReceipt(Membership membership,String followerCheckpointId,Finding leader,
                                                   List<Map<String,Object>> leaderSteps){
        if(membership==null||leader==null||leader.checkpoint()==null||leaderSteps==null
            ||!membership.checkpointIds().contains(membership.leaderCheckpointId())
            ||!membership.checkpointIds().contains(followerCheckpointId)
            ||!membership.checkpointIds().contains(leader.checkpoint().id())
            ||Objects.equals(followerCheckpointId,leader.checkpoint().id()))return emptyReceipt();
        Map<String,Object> snapshot=Map.of();
        for(Map<String,Object> step:leaderSteps)if(validSnapshotStep(step)
            &&(text(step,"checkpointId").isBlank()||leader.checkpoint().id().equals(text(step,"checkpointId"))))snapshot=step;
        if(snapshot.isEmpty())return emptyReceipt();
        Map<String,Object> rawOutput=map(snapshot.get("output"));
        LinkedHashMap<String,Map<String,Object>> sources=new LinkedHashMap<>();
        Set<String> ambiguousSources=new HashSet<>();
        for(Map<String,Object> raw:maps(rawOutput.get("sources"))){
            Map<String,Object> source=frozenSource(raw);String id=text(source,"id");
            if(!id.isBlank()){Map<String,Object> previous=sources.putIfAbsent(id,source);
                if(previous!=null&&!canonical(previous).equals(canonical(source)))ambiguousSources.add(id);}
        }
        ambiguousSources.forEach(sources::remove);
        LinkedHashSet<String> referenced=new LinkedHashSet<>(strings(rawOutput.get("sourceIds")));
        List<Map<String,Object>> facts=new ArrayList<>();
        for(Map<String,Object> raw:maps(rawOutput.get("facts"))){
            List<String> refs=strings(raw.get("sourceIds"));
            if(refs.isEmpty()||!sources.keySet().containsAll(refs))continue;
            facts.add(Collections.unmodifiableMap(new LinkedHashMap<>(raw)));referenced.addAll(refs);
        }
        referenced.retainAll(sources.keySet());
        List<String> sourceIds=List.copyOf(referenced);List<Map<String,Object>> selectedSources=sourceIds.stream().map(sources::get).toList();
        if(sourceIds.isEmpty())return emptyReceipt();
        Map<String,Object> output=new LinkedHashMap<>();
        output.put("facts",List.copyOf(facts));output.put("sourceIds",sourceIds);output.put("sources",selectedSources);
        output.put("businessProof",false);output.put("groupEvidenceReceipt",true);
        Map<String,Object> atomic=CheckResolution.atomic(leader),agent=CheckResolution.map(atomic.get("agentInvestigation"));
        String plan=text(agent,"serverPlanFingerprint"),revision=text(agent,"targetEvidenceRevision");
        String fingerprint=semanticFingerprint(snapshot,sources,sourceIds,facts);
        Map<String,Object> replay=new LinkedHashMap<>();
        replay.put("id",BidService.stableId("agent-group-evidence:"+membership.groupId()+":"+followerCheckpointId+":"+fingerprint));
        replay.put("executionId",BidService.stableId("agent-group-evidence-execution:"+membership.groupId()+":"+followerCheckpointId+":"+fingerprint));
        replay.put("attemptId","");replay.put("type",FACT_SNAPSHOT);replay.put("status","COMPLETED");
        for(String field:List.of("schemaVersion","engineVersion","modelIdentity"))replay.put(field,snapshot.getOrDefault(field,""));
        replay.put("inputFingerprint",fingerprint);replay.put("output",Collections.unmodifiableMap(output));
        replay.put("agentGroupEvidenceReuse",true);replay.put("reuseGroupId",membership.groupId());
        replay.put("reusedFromCheckpointId",leader.checkpoint().id());replay.put("checkpointId",followerCheckpointId);
        replay.put("businessDecisionCopied",false);
        return new EvidenceReceipt(replay,sourceIds,facts.size(),plan,revision,fingerprint);
    }

    /** Attach and durably publish the receipt once for this follower. */
    @SuppressWarnings("unchecked")
    public static boolean attach(EvidenceReceipt receipt,Map<String,Object> context){
        if(receipt==null||!receipt.reusable()||context==null)return false;
        List<Map<String,Object>> prior=new ArrayList<>(maps(context.get("priorSteps")));String id=text(receipt.step(),"id");
        boolean present=prior.stream().anyMatch(step->id.equals(text(step,"id"))
            ||Boolean.TRUE.equals(step.get("agentGroupEvidenceReuse"))
                &&text(receipt.step(),"reuseGroupId").equals(text(step,"reuseGroupId"))
                &&text(receipt.step(),"checkpointId").equals(text(step,"checkpointId"))
                &&receipt.fingerprint().equals(text(step,"inputFingerprint")));
        // A durable receipt already delivered to this checkpoint is not an evidence revision.
        // In particular, do not repopulate changedSourceIds after a consumer has drained it.
        if(present)return false;
        Object observer=context.get("stepObserver");
        if(observer instanceof Consumer<?> listener)((Consumer<Map<String,Object>>)listener).accept(receipt.step());
        prior.add(receipt.step());
        LinkedHashSet<String> changed=new LinkedHashSet<>(strings(context.get("recoveryChangedSourceIds")));changed.addAll(receipt.sourceIds());
        context.put("priorSteps",List.copyOf(prior));context.put("recoveryChangedSourceIds",List.copyOf(changed));
        context.put("agentGroupEvidenceReceiptFingerprint",receipt.fingerprint());
        context.put("agentGroupSourceEvidenceRevision",receipt.evidenceRevision());
        context.put("agentGroupSourcePlanFingerprint",receipt.planFingerprint());
        return true;
    }

    /** Audit IDs and receipt wrappers describe a delivery, never a change to the observation.
     * Sources include content/parser versions; business values, identity and layout bindings stay
     * in the hash so a corrected certificate/price/source still wakes the receiving rule tree. */
    private static String semanticFingerprint(Map<String,Object> snapshot,Map<String,Map<String,Object>> sources,
                                              List<String> sourceIds,List<Map<String,Object>> facts){
        Map<String,Object> sourceIdentities=new HashMap<>();
        sources.forEach((id,source)->{Map<String,Object> frozen=new TreeMap<>(source);frozen.remove("id");frozen.put("documentVersion",text(source,"documentVersion"));
            sourceIdentities.put(id,ordered(frozen,sourceIdentities,""));});
        Set<String> canonicalSources=new TreeSet<>();for(String id:sourceIds)canonicalSources.add(canonical(sourceIdentities.get(id)));
        Set<String> canonicalFacts=new TreeSet<>();
        for(Map<String,Object> fact:facts){Map<String,Object> semantic=new TreeMap<>();
            for(String key:List.of("kind","subject","field","fieldLabel","categoryId","rawValue","value","unit","basis","scope"))semantic.put(key,text(fact,key));
            semantic.put("sourceIds",ordered(fact.get("sourceIds"),sourceIdentities,"sourceIds"));
            semantic.put("attributes",ordered(map(fact.get("attributes")),sourceIdentities,"attributes"));
            canonicalFacts.add(canonical(semantic));
        }
        Map<String,Object> descriptor=new TreeMap<>();descriptor.put("version",VERSION);
        for(String key:List.of("schemaVersion","engineVersion","modelIdentity"))descriptor.put(key,text(snapshot,key));
        descriptor.put("sources",canonicalSources);descriptor.put("facts",canonicalFacts);
        return BidService.stableId(canonical(descriptor));
    }
    private static Object ordered(Object value,Map<String,Object> sources,String field){
        if(value instanceof Map<?,?> values){Map<String,Object> out=new TreeMap<>();
            values.forEach((key,item)->{String name=key.toString();if(!RECEIPT_METADATA.contains(name))out.put(name,ordered(item,sources,name));});return out;}
        if(value instanceof Collection<?> values){List<Object> out=new ArrayList<>();
            for(Object item:values)out.add("sourceIds".equals(field)?sources.getOrDefault(Objects.toString(item,""),item):ordered(item,sources,field));
            if(Set.of("sourceIds","cellIds","months").contains(field)||value instanceof Set<?>){Map<String,Object> unique=new TreeMap<>();
                for(Object item:out)unique.put(JSON.valueToTree(item).toString(),item);return new ArrayList<>(unique.values());}
            return out;}
        return value;
    }
    private static String canonical(Object value){return JSON.valueToTree(ordered(value,Map.of(),"")).toString();}

    private static EvidenceReceipt emptyReceipt(){return new EvidenceReceipt(Map.of(),List.of(),0,"","","");}
    private static boolean validSnapshotStep(Map<String,Object> step){
        return FACT_SNAPSHOT.equals(text(step,"type"))&&"COMPLETED".equals(text(step,"status"))
            &&!text(step,"schemaVersion").isBlank()&&!text(step,"modelIdentity").isBlank()&&step.get("output") instanceof Map<?,?>;
    }
    private static Map<String,Object> frozenSource(Map<String,Object> raw){
        List<String> fields=List.of("id","projectId","documentId","documentHash","documentVersion","parserFingerprint","sourceFingerprint");
        Map<String,Object> source=new LinkedHashMap<>();for(String field:fields)if(raw.containsKey(field))source.put(field,raw.get(field));
        if(fields.stream().anyMatch(field->!source.containsKey(field))||List.of("id","projectId","documentId","documentHash","documentVersion","sourceFingerprint")
            .stream().anyMatch(field->text(source,field).isBlank()))return Map.of();
        return Collections.unmodifiableMap(source);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?> row?(Map<String,Object>)row:Map.of();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return value instanceof Collection<?> rows?rows.stream().filter(Map.class::isInstance).map(row->(Map<String,Object>)row).toList():List.of();}
    private static List<String> strings(Object value){return value instanceof Collection<?> rows?rows.stream().filter(Objects::nonNull)
        .map(Object::toString).filter(item->!item.isBlank()).distinct().toList():List.of();}
    private static String text(Map<String,Object> value,String key){return Objects.toString(value.get(key),"");}

    private record Candidate(int index,Checkpoint checkpoint,BusinessObligationIdentity.Signature signature) { }
}
