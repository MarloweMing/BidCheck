package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import cn.bidcheck.verification.facts.FieldCoverage;
import cn.bidcheck.verification.engine.PersonnelRoles;
import cn.bidcheck.verification.engine.RequirementRepairPolicy;
import java.util.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

/** Durable reverse dependencies for ordinary checks. A change schedules work, never a verdict. */
public final class OrdinaryRecoveryDependencies {
    public static final String VERSION="ordinary-recovery-dependencies-4-typed-semantic-progress";
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(OrdinaryRecoveryDependencies.class);
    private OrdinaryRecoveryDependencies() {}

    public static long currentRevision(Store store,String project,String run){
        Stored saved=store.mapper().get(clockId(run));
        return saved!=null&&"ordinary_recovery_clock".equals(saved.kind)&&project.equals(saved.projectId)?number(store.map(saved.body).get("revision")):0;
    }
    /** Called inside the fenced evidence-publication transaction after validated canonical insertion. */
    public static void publishChanges(Store store,String project,String run,String producer,Map<String,Object> receipt){
        Map<String,Map<String,Object>> candidates=new LinkedHashMap<>(),novel=new LinkedHashMap<>();int equivalent=0;
        for(Map<String,Object> change:maps(receipt.get("changedEvidence")))if(usable(change)){
            String key=RecoveryEvidenceIdentity.key(change);
            if(candidates.putIfAbsent(key,change)!=null)equivalent++;
        }
        Set<String> seen=store.mapsByIds("ordinary_recovery_evidence_seen",project,cn.bidcheck.compat.Java8Api.toList(candidates.keySet().stream().map(key->stableId(run+":ordinary-evidence-seen:"+key)))).keySet();
        for(java.util.Map.Entry<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>> candidate:candidates.entrySet()){
            if(seen.contains(stableId(run+":ordinary-evidence-seen:"+candidate.getKey()))){equivalent++;continue;}
            Map<String,Object> value=new LinkedHashMap<>(candidate.getValue());value.put("semanticEvidenceKey",candidate.getKey());novel.put(candidate.getKey(),value);
        }
        List<Map<String,Object>> changes=cn.bidcheck.compat.Java8Api.listCopyOf(novel.values());
        if(equivalent>0)LOG.info("bidcheck.recovery_equivalent_evidence runId={} checkpointId={} suppressed={} newEvidence={}",run,producer,equivalent,changes.size());
        if(changes.isEmpty())return;
        String id=stableId(run+":ordinary-change:"+cn.bidcheck.compat.Java8Api.toList(novel.keySet().stream().sorted()));
        if(store.mapper().get(id)!=null)return;
        long revision=currentRevision(store,project,run)+1;
        store.save("ordinary_recovery_clock",clockId(run),project,run,fields("revision",revision,"runId",run,"version",VERSION));
        store.save("ordinary_recovery_change",id,project,run,fields("id",id,"runId",run,"revision",revision,"producerCheckpointId",producer,"changes",changes,"version",VERSION));
        for(java.util.Map.Entry<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>> item:novel.entrySet())store.save("ordinary_recovery_evidence_seen",stableId(run+":ordinary-evidence-seen:"+item.getKey()),project,run,
            fields("semanticEvidenceKey",item.getKey(),"firstRevision",revision,"firstItemId",text(item.getValue(),"itemId"),"version",VERSION));
        LOG.info("bidcheck.recovery_change runId={} checkpointId={} revision={} items={}",run,producer,revision,changes.size());
    }
    /** Records per-rule read revisions; an unaffected node keeps its own prior revision. */
    static void bind(Store store,String project,String run,Finding finding){
        String setId=stableId(run+":ordinary-binding-set:"+finding.checkpoint().id());Set<String> current=new LinkedHashSet<>();
        Map<String,Object> previousSet=stored(store,setId);
        for(Map<String,Object> dependency:maps(atomic(finding).get("recoveryEvidenceDependencies"))){
            // A previously completed condition must still be revalidated when genuinely new
            // counterevidence arrives. Only the later model escalation filters closed targets.
            String obligation=text(dependency,"obligationId");if(cn.bidcheck.compat.Java8Api.isBlank(obligation)||cn.bidcheck.compat.Java8Api.isBlank(text(dependency,"observationKey"))||map(dependency.get("target")).isEmpty())continue;
            String id=stableId(run+":ordinary-binding:"+finding.checkpoint().id()+":"+obligation);
            current.add(id);
            Map<String,Object> previous=stored(store,id),value=new LinkedHashMap<>(dependency);
            value.putAll(fields("id",id,"version",VERSION,"runId",run,"checkpointId",finding.checkpoint().id(),"findingId",finding.id(),
                "observedRevision",number(dependency.get("observedRevision")),"scheduledRevision",number(previous.get("scheduledRevision")),"active",true));
            value.put("scheduledEvidenceKeys",previous.getOrDefault("scheduledEvidenceKeys",cn.bidcheck.compat.Java8Api.listOf()));
            store.save("ordinary_recovery_binding",id,project,run,value);
        }
        if(previousSet.get("bindingIds") instanceof Collection<?>)for(Object raw:((Collection<?>) (previousSet.get("bindingIds")))){String id=String.valueOf(raw);if(current.contains(id))continue;
            Map<String,Object> obsolete=stored(store,id);if(obsolete.isEmpty())continue;obsolete.put("active",false);store.save("ordinary_recovery_binding",id,project,run,obsolete);
        }
        if(!current.isEmpty()||!previousSet.isEmpty())store.save("ordinary_recovery_binding_set",setId,project,run,fields("checkpointId",finding.checkpoint().id(),"bindingIds",cn.bidcheck.compat.Java8Api.listCopyOf(current),"version",VERSION));
    }

    /** Called under the run's task fence. Novel matching evidence revalidates even a completed proof. */
    static int schedule(Store store,String project,String run){
        int changedRequirements=scheduleRequirementChanges(store,project,run);
        List<Map<String,Object>> events=store.maps("ordinary_recovery_change",project,run);if(events.isEmpty())return changedRequirements;
        Map<String,List<Map<String,Object>>> byCheckpoint=new LinkedHashMap<>();
        for(Map<String,Object> binding:store.maps("ordinary_recovery_binding",project,run))if(!Boolean.FALSE.equals(binding.get("active")))byCheckpoint.computeIfAbsent(text(binding,"checkpointId"),ignored->new ArrayList<>()).add(binding);
        Map<String,Map<String,Object>> jobs=new LinkedHashMap<>();
        for(Map<String,Object> job:store.maps("check_resolution",project,run))jobs.put(text(job,"checkpointId"),job);
        // First-pass completed checks have dependency bindings but deliberately no recovery
        // job. They remain watchable: create a job only if a later matching event exists.
        for(String checkpoint:byCheckpoint.keySet())if(!jobs.containsKey(checkpoint)){
            Stored row=store.mapper().get(stableId(run+":"+checkpoint));if(row==null)continue;
            Finding completed=store.decode(row.body,Finding.class);if(unresolved(completed))continue;
            Map<String,Object> candidate=new LinkedHashMap<>(CheckResolution.plan(completed));
            candidate.putAll(fields("id",CheckResolution.jobId(run,checkpoint),"runId",run,"checkpointId",checkpoint,"attempts",0));jobs.put(checkpoint,candidate);
        }
        int scheduled=changedRequirements;
        for(Map<String,Object> job:jobs.values()){
            boolean failedJob="FAILED".equals(text(job,"status"));
            if(!failedJob&&!cn.bidcheck.compat.Java8Api.setOf("BLOCKED_NO_PROGRESS", "SUCCEEDED").contains(text(job,"status")))continue;
            String checkpoint=text(job,"checkpointId");List<Map<String,Object>> bindings=byCheckpoint.getOrDefault(checkpoint,cn.bidcheck.compat.Java8Api.listOf());
            Stored row=store.mapper().get(stableId(run+":"+checkpoint));if(row==null)continue;
            Finding finding=store.decode(row.body,Finding.class);
            // Only a controlled stalled investigation can resume from a fact event. Generic
            // failed services still require their existing recovery path, not an evidence retry.
            if(failedJob&&!CheckResolution.agentStalledWithRemainingTargets(finding))continue;
            Set<String> targets=new TreeSet<>(),eventIds=new TreeSet<>(),sourceIds=new TreeSet<>(),evidenceKeys=new TreeSet<>();long revision=0;
            for(Map<String,Object> binding:bindings){
                Set<String> seen=new TreeSet<>(stringList(binding.get("scheduledEvidenceKeys")));
                long floor=Math.max(number(binding.get("observedRevision")),number(binding.get("scheduledRevision"))),latest=floor;
                for(Map<String,Object> event:events){long eventRevision=number(event.get("revision"));
                    if(eventRevision<=floor||checkpoint.equals(text(event,"producerCheckpointId")))continue;
                    List<Map<String,Object>> matching=cn.bidcheck.compat.Java8Api.toList(maps(event.get("changes")).stream().filter(change->matches(binding,change)&&(!"SUCCEEDED".equals(text(job,"status"))||possibleConflict(binding,change))));if(matching.isEmpty())continue;
                    boolean novel=false;
                    for(Map<String,Object> change:matching){String key=RecoveryEvidenceIdentity.key(change);if(!seen.add(key))continue;novel=true;evidenceKeys.add(key);
                        for(Map<String,Object> source:maps(change.get("sources")))if(!cn.bidcheck.compat.Java8Api.isBlank(text(source,"id")))sourceIds.add(text(source,"id"));
                        if(map(change.get("fact")).get("sourceIds") instanceof Collection<?>)((Collection<?>) (map(change.get("fact")).get("sourceIds"))).forEach(ref->{if(!cn.bidcheck.compat.Java8Api.isBlank(Objects.toString(ref,"")))sourceIds.add(String.valueOf(ref));});}
                    latest=Math.max(latest,eventRevision);if(!novel)continue;
                    targets.add(text(binding,"obligationId"));eventIds.add(text(event,"id"));revision=Math.max(revision,eventRevision);
                }
                if(latest>floor){binding.put("scheduledRevision",latest);binding.put("scheduledEvidenceKeys",cn.bidcheck.compat.Java8Api.listCopyOf(seen));store.save("ordinary_recovery_binding",text(binding,"id"),project,run,binding);}
            }
            if(targets.isEmpty())continue;
            if(failedJob)job.put("resumeReasonCode","NEW_RELEVANT_EVIDENCE_AFTER_AGENT_STALL");
            job.putAll(fields("status","QUEUED","strategy","RECOMPUTE_SHARED_EVIDENCE","dependencyTargetObligationIds",cn.bidcheck.compat.Java8Api.listCopyOf(targets),
                "dependencyTrigger",fields("version",VERSION,"revision",revision,"eventIds",cn.bidcheck.compat.Java8Api.listCopyOf(eventIds),"sourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(sourceIds),"semanticEvidenceKeys",cn.bidcheck.compat.Java8Api.listCopyOf(evidenceKeys),"businessProof",false),
                "reason",unresolved(finding)?"其他事项新增了本项所需的字段或关系证据。":"其他事项新增了本项的相关事实，需重新核验已完成证明是否仍成立。",
                "nextAction","只重算受影响的条件，复用已完成证明。","updatedAt",now()));
            job.remove("stopReason");store.save("check_resolution",text(job,"id"),project,run,job);scheduled++;
            LOG.info("bidcheck.recovery_dependency_schedule runId={} checkpointId={} revision={} targets={} changes={} strategy=RECOMPUTE_SHARED_EVIDENCE semanticInputs={} modelAllowed=false resumeReason={}",run,checkpoint,revision,targets,eventIds.size(),evidenceKeys.size(),failedJob?"NEW_RELEVANT_EVIDENCE_AFTER_AGENT_STALL":"NEW_RELEVANT_EVIDENCE");
        }
        return scheduled;
    }
    /** A compiler/source/mapping update reopens a structural job once, independently of fact publications. */
    private static int scheduleRequirementChanges(Store store,String project,String run){
        int scheduled=0;Map<String,Object> current=RequirementRepairPolicy.versions();
        for(Map<String,Object> job:store.maps("check_resolution",project,run)){
            if(!"BLOCKED_NO_PROGRESS".equals(text(job,"status"))||current.equals(map(job.get("requiredRequirementRevisions"))))continue;
            Stored row=store.mapper().get(stableId(run+":"+text(job,"checkpointId")));if(row==null)continue;
            Finding finding=store.decode(row.body,Finding.class);if(!unresolved(finding))continue;
            Map<String,Object> proposed=CheckResolution.plan(finding);String strategy=text(proposed,"strategy");
            if(!RequirementRepairPolicy.requirementWork(strategy))continue;
            Map<String,Object> prior=map(map(map(atomic(finding).get("plan")).get("requirementScope")).get("requirementRevisions"));
            if(current.entrySet().stream().allMatch(e->Objects.equals(e.getValue(),prior.get(e.getKey()))))continue;
            job.putAll(proposed);job.putAll(fields("status","QUEUED","requiredRequirementRevisions",current,
                "reason","要求来源、编译器或字段映射版本已更新。","nextAction","重新生成失败规则并保留已核验独立证明。","updatedAt",now()));
            job.remove("stopReason");store.save("check_resolution",text(job,"id"),project,run,job);scheduled++;
            LOG.info("bidcheck.recovery_requirement_schedule runId={} checkpointId={} strategy={} compilerRevision={} mappingRevision={}",run,text(job,"checkpointId"),strategy,current.get("requirementCompilerRevision"),current.get("requirementMappingRevision"));
        }
        return scheduled;
    }
    private static boolean possibleConflict(Map<String,Object> binding,Map<String,Object> change){
        Map<String,Object> fact=map(change.get("fact"));
        if(fact.isEmpty()||"MATERIAL".equals(text(fact,"kind")))return false;
        if(!binding.containsKey("observedValues")){
            // Legacy proofs have no comparison values. Revalidate only an explicitly bound scalar field.
            Map<String,Object> target=map(binding.get("target"));
            return "FIELD".equals(text(fact,"kind"))&&!text(target,"subject").isEmpty()&&text(target,"subject").equals(text(fact,"subject"))&&text(target,"field").equals(text(fact,"field"));
        }
        for(Map<String,Object> prior:maps(binding.get("observedValues"))){
            if(text(prior,"subject").isEmpty()||text(fact,"value").isEmpty()||text(prior,"value").equals(text(fact,"value")))continue;
            if(Arrays.asList("subject","field","unit","scope","basis","kind").stream().allMatch(k->text(prior,k).equals(text(fact,k)))
                &&text(prior,"lot").equals(text(map(fact.get("attributes")),"lot")))return true;
        }
        return false;
    }
    static boolean matches(Map<String,Object> binding,Map<String,Object> change){
        if(!usable(change))return false;
        if("FIELD_OBSERVATION".equals(text(change,"recordType")))return !cn.bidcheck.compat.Java8Api.isBlank(text(binding,"observationKey"))&&text(binding,"observationKey").equals(text(change,"observationKey"))
            &&(cn.bidcheck.compat.Java8Api.isBlank(text(binding,"modelIdentity"))||text(binding,"modelIdentity").equals(text(change,"modelIdentity")));
        Map<String,Object> target=map(binding.get("target")),fact=map(change.get("fact"));
        if(target.isEmpty()||fact.isEmpty())return false;
        // RuleEvaluator.months only evaluates CURRENT_PROJECT records. A known historical
        // payroll record cannot alter this obligation, even when its person name matches.
        if ("MONTHS".equals(text(target, "predicate")) && "MONTHS".equals(text(fact, "kind"))
                && "HISTORICAL_PROJECT".equals(text(fact, "scope"))) return false;
        if(FieldCoverage.matches(target,fact)||relationshipPrerequisite(binding,target,fact))return true;
        // The stored role-to-entity binding is a scheduling dependency, never a fresh proof.
        // The complete subject relationship is validated again by the executor before verdicts.
        if(binding.get("resolvedSubjects") instanceof Collection<?>)for(Object value:((Collection<?>) (binding.get("resolvedSubjects")))){
            String subject=Objects.toString(value,"");if(cn.bidcheck.compat.Java8Api.isBlank(subject))continue;
            Map<String,Object> resolved=new LinkedHashMap<>(target);resolved.put("subject",subject);
            if(FieldCoverage.matches(resolved,fact))return true;
        }
        return false;
    }
    private static boolean relationshipPrerequisite(Map<String,Object> binding,Map<String,Object> target,Map<String,Object> fact){
        if(!cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "RELATION").contains(text(fact,"kind"))||!"CURRENT_PROJECT".equals(text(fact,"scope")))return false;
        Set<String> selectors=new HashSet<>();selectors.add(text(target,"subject"));selectors.add(text(map(target.get("parameters")),"employer"));
        if(binding.get("resolvedSubjects") instanceof Collection<?>)((Collection<?>) (binding.get("resolvedSubjects"))).forEach(v->selectors.add(Objects.toString(v,"")));selectors.remove("");
        Set<String> endpoints=new HashSet<>(cn.bidcheck.compat.Java8Api.listOf(text(fact,"subject"), text(fact,"value"), text(map(fact.get("attributes")),"object")));endpoints.remove("");
        return selectors.stream().anyMatch(selector->endpoints.stream().anyMatch(endpoint->selector.equals(endpoint)||PersonnelRoles.sameObservationRole(selector,endpoint)));
    }
    private static boolean usable(Map<String,Object> change){return !cn.bidcheck.compat.Java8Api.isBlank(text(change,"itemId"))&&!maps(change.get("sources")).isEmpty()
        &&cn.bidcheck.compat.Java8Api.setOf("FACT", "FIELD_OBSERVATION").contains(text(change,"recordType"))
        &&("FACT".equals(text(change,"recordType"))?!map(change.get("fact")).isEmpty():!cn.bidcheck.compat.Java8Api.isBlank(text(change,"observationKey")));}
    private static String clockId(String run){return stableId(run+":ordinary-recovery-clock");}
    private static Map<String,Object> stored(Store store,String id){Stored row=store.mapper().get(id);return row==null?cn.bidcheck.compat.Java8Api.mapOf():store.map(row.body);}
    private static long number(Object value){return value instanceof Number?((Number) (value)).longValue():0;}
    private static List<String> stringList(Object value){return value instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().map(v->Objects.toString(v,"")).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v))):cn.bidcheck.compat.Java8Api.listOf();}
}
