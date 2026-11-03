package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Document-scoped compiler artifacts. Failed business validation is diagnostic reuse, never proof. */
final class RequirementArtifact {
    static final String VERSION="requirement-artifact-1";
    private static final ReentrantLock[] LOCKS=new ReentrantLock[128];
    static {for(int i=0;i<LOCKS.length;i++)LOCKS[i]=new ReentrantLock(true);}
    final RunSession session;final Map<String,Object> request;final ReentrantLock lock;
    RequirementArtifact(RunSession session,Map<String,Object> input,RequirementSourceContext.Context source){
        this(session,input,source,false);
    }
    RequirementArtifact(RunSession session,Map<String,Object> input,RequirementSourceContext.Context source,boolean serverOwned){
        this.session=session;Map<String,Object> semantic=new LinkedHashMap<>(input);semantic.remove("title");
        String policy=CompilationRecovery.VERSION+(serverOwned?":"+DeterministicRequirementCompiler.VERSION:"");
        String fingerprint=serverOwned?identity(policy+canonical(semantic)):session.fingerprint("COMPILE",semantic);
        request=new LinkedHashMap<>(source.identity());request.put("schemaVersion",VERSION);request.put("compilerPolicyVersion",policy);
        request.put("modelIdentity",serverOwned?"SERVER_OWNED":session.model.identity());request.put("inputFingerprint",fingerprint);
        request.put("artifactId",identity(canonical(request)));lock=LOCKS[(request.get("artifactId").hashCode()&Integer.MAX_VALUE)%LOCKS.length];
    }
    boolean enabled(){return request.containsKey("documentHash")&&session.context.get("requirementArtifactLookup") instanceof Function<?,?>&&session.context.get("requirementArtifactPublisher") instanceof Consumer<?>;}
    long now(){return session.context.get("requirementNowEpochMillis") instanceof Number?((Number) (session.context.get("requirementNowEpochMillis"))).longValue():System.currentTimeMillis();}
    @SuppressWarnings("unchecked") Map<String,Object> lookup(){
        if(!enabled())return cn.bidcheck.compat.Java8Api.mapOf();Object value=((Function<Map<String,Object>,Map<String,Object>>)session.context.get("requirementArtifactLookup")).apply(cn.bidcheck.compat.Java8Api.mapCopyOf(request));
        if(!(value instanceof Map<?,?>))return cn.bidcheck.compat.Java8Api.mapOf();Map<String,Object> saved=new LinkedHashMap<>();((Map<?,?>) (value)).forEach((k,v)->saved.put(String.valueOf(k),v));
        if(request.entrySet().stream().anyMatch(e->!Objects.equals(e.getValue(),saved.get(e.getKey()))))return cn.bidcheck.compat.Java8Api.mapOf();
        if(!cn.bidcheck.compat.Java8Api.setOf("VALIDATED", "UNRESOLVED", "REPAIR_RETRY_PENDING").contains(str(saved,"state"))||!(saved.get("rawResponse") instanceof Map<?,?>))return cn.bidcheck.compat.Java8Api.mapOf();
        if("SERVER_OWNED".equals(request.get("modelIdentity"))&&!identity(canonical(saved.get("rawResponse"))).equals(str(saved,"responseDigest")))return cn.bidcheck.compat.Java8Api.mapOf();
        return saved;
    }
    @SuppressWarnings("unchecked") void publish(JsonNode raw,Plan plan,boolean repairFailed,int repairs){
        if(!enabled())return;Map<String,Object> artifact=new LinkedHashMap<>(request);artifact.put("state",repairFailed?"REPAIR_RETRY_PENDING":CompilationRecovery.diagnostics(plan).isEmpty()?"VALIDATED":"UNRESOLVED");
        artifact.put("rawResponse",session.json.convertValue(raw,Map.class));artifact.put("validationAudit",session.compilationAudit);artifact.put("repairAttempts",repairs);artifact.put("createdAtEpochMillis",now());
        artifact.put("responseDigest",identity(canonical(artifact.get("rawResponse"))));
        artifact.put("deterministicCompilation",plan.requirementScope().getOrDefault("deterministicCompilation",cn.bidcheck.compat.Java8Api.mapOf()));
        if(repairFailed)artifact.put("retryNotBeforeEpochMillis",now()+60_000L);
        ((Consumer<Map<String,Object>>)session.context.get("requirementArtifactPublisher")).accept(cn.bidcheck.compat.Java8Api.mapCopyOf(artifact));
    }
    void auditReuse(Plan plan,Map<String,Object> saved){
        Map<String,Object> audit=new LinkedHashMap<>(CompilationRecovery.audit(plan,0,cn.bidcheck.compat.Java8Api.listOf(linked("stage","SHARED_ARTIFACT_REUSED","state",saved.get("state")))));
        audit.put("artifactId",request.get("artifactId"));audit.put("cacheState",saved.get("state"));
        if(saved.get("retryNotBeforeEpochMillis") instanceof Number)audit.put("retryNotBeforeEpochMillis",((Number) (saved.get("retryNotBeforeEpochMillis"))).longValue());
        audit.put("nextAction","UNRESOLVED".equals(saved.get("state"))?"INPUT_OR_COMPILER_CHANGED":"RETRY_FAILED_COMPILATION_NODE");session.compilationAudit=audit;
    }
    Plan identify(Plan plan){
        if(!request.containsKey("documentHash"))return plan;Map<String,Object> scope=new LinkedHashMap<>(plan.requirementScope());scope.put("artifactId",request.get("artifactId"));
        return new Plan(plan.logic(),cn.bidcheck.compat.Java8Api.toList(plan.rules().stream().map(r->identify(r,""))),plan.method(),plan.issues(),plan.policyNotes(),scope);
    }
    private Rule identify(Rule rule,String branch){
        Map<String,Object> parameters=new LinkedHashMap<>(rule.parameters());parameters.remove("obligationIdentity");
        Map<String,Object> semantic=new LinkedHashMap<>(parameters);for(String key:cn.bidcheck.compat.Java8Api.listOf("requirementBindings", "requirementSourceContext", "compilationIssue", "focusedScopeIssue", "requirementContextIssue", "originalPredicate"))semantic.remove(key);
        String originalPredicate=str(parameters,"originalPredicate"),businessPredicate=rule.predicate().equals("UNSUPPORTED")&&PREDICATES.contains(originalPredicate)?originalPredicate:rule.predicate();
        String own=identity(canonical(cn.bidcheck.compat.Java8Api.listOf(request.get("projectId"), request.get("documentId"), request.get("documentHash"), request.get("documentVersion"), request.get("locationType"), request.get("location"), rule.sourceQuote(), branch, businessPredicate, rule.subject(), rule.field(), rule.operator(), rule.expected(), rule.unit(), semantic)));
        parameters.put("obligationIdentity",own);
        String next=cn.bidcheck.compat.Java8Api.setOf("ANY", "CONDITIONAL", "FOR_EACH").contains(rule.logic())?branch+"|"+own:branch;
        return new Rule(rule.id(),rule.description(),rule.predicate(),rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),parameters,rule.sourceQuote(),rule.logic(),cn.bidcheck.compat.Java8Api.toList(rule.children().stream().map(c->identify(c,next))));
    }
    private static String canonical(Object value){try{return new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("REQUIREMENT_ARTIFACT_INVALID",e);}}
}
