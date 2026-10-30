package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.identity;

/**
 * A cheap dependency receipt evaluated before corpus lookup. It describes inputs, never proof.
 * An unchanged requirement repair is not a reason to reread all evidence. Unknown fact revisions
 * remain conservative for executable rules; source-bound unsupported nodes need no fact query.
 */
public final class RecoveryDependencyGate {
    public static final String VERSION="recovery-dependencies-3-validation-identity";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final TypeReference<Map<String,Object>> OBJECT=new TypeReference<>(){};
    private static final Set<String> DIAGNOSTIC_PARAMETERS=Set.of("obligationIdentity","ruleId","parentRuleId",
        "compilationIssue","focusedScopeIssue","requirementContextIssue","repairReason");
    private RecoveryDependencyGate() {}

    /** Called after local compilation, before any shared-fact lookup, text extraction or image work. */
    public static Map<String,Object> begin(Map<String,Object> context,Object rawPlan) {
        Map<String,Object> plan=object(rawPlan),receipt=snapshot(context,plan);
        Map<String,Object> previous=object(context.get("previousAtomicCheck"));
        Map<String,Object> prior=object(previous.get("recoveryDependencies"));
        if(!RequirementRepairPolicy.requirementWork(text(context,"recoveryStrategy"))||prior.isEmpty()
            ||!VERSION.equals(text(prior,"version")))return receipt;
        boolean samePlan=text(prior,"planRevision").equals(text(receipt,"planRevision"));
        boolean structural=structuralTargetsOnly(previous,context);
        // Another clause publishing facts cannot repair an unchanged unsupported predicate.
        // Executable rules still require the exact known revision they actually observed.
        String revisionField=structural?"structuralInputRevision":"inputRevision";
        boolean sameInputs=!text(prior,revisionField).isBlank()&&text(prior,revisionField).equals(text(receipt,revisionField));
        if(samePlan&&sameInputs&&(structural||!text(context,"sharedFactRevision").isBlank()))
            throw new NoDependencyChange(receipt,"REQUIREMENT_AND_INPUTS_UNCHANGED");
        return receipt;
    }

    public static Map<String,Object> snapshot(Map<String,Object> context,Object rawPlan) {
        Map<String,Object> plan=object(rawPlan),rules=new TreeMap<>();
        indexRules(rows(plan.get("rules")),rules);
        Map<String,Object> meaning=new TreeMap<>();meaning.put("logic",plan.getOrDefault("logic",""));
        meaning.put("rules",rows(plan.get("rules")).stream().map(RecoveryDependencyGate::ruleMeaning).toList());
        // Validation issue prose is not a business rule; its disappearance alone cannot prove work.
        Map<String,Object> inputs=new TreeMap<>();
        for(String key:List.of("recoverySourceRevision","recoveryValidationIdentity","sharedFactRevision",
            "relationshipRevision","referenceDate","referenceDateEvidence","referenceDates","evaluationStage",
            "applicabilityInputs","projectInfo","tenderConstants","requirementSourcePackageRevision",
            "requirementRepairVersion","requirementCompilerRevision","requirementSourceRepairRevision","requirementMappingRevision","requirementValidationRevision"))if(context.containsKey(key))inputs.put(key,semantic(context.get(key)));
        inputs.put("requirementRevisions",semantic(object(plan.get("requirementScope")).getOrDefault("requirementRevisions",RequirementRepairPolicy.versions())));
        inputs.put("visualObservations",rows(context.get("visualObservations")).stream().map(RecoveryDependencyGate::observationMeaning).map(RecoveryDependencyGate::canonical).sorted().distinct().toList());
        inputs.put("externalRecords",rows(context.get("externalRecords")).stream().map(RecoveryDependencyGate::externalMeaning).map(RecoveryDependencyGate::canonical).sorted().distinct().toList());
        if(context.get("tenderDocuments") instanceof Collection<?> documents)inputs.put("tenderDocuments",documents.stream().map(RecoveryDependencyGate::documentIdentity).map(RecoveryDependencyGate::canonical).sorted().toList());
        Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("version",VERSION);receipt.put("planRevision",identity(canonical(meaning)));
        receipt.put("inputRevision",identity(canonical(inputs)));receipt.put("ruleRevisions",rules);
        Map<String,Object> structuralInputs=new TreeMap<>(inputs);structuralInputs.remove("sharedFactRevision");
        receipt.put("structuralInputRevision",identity(canonical(structuralInputs)));
        receipt.put("sourceRevision",Objects.toString(context.get("recoverySourceRevision"),""));
        receipt.put("validationIdentity",Objects.toString(context.get("recoveryValidationIdentity"),""));
        receipt.put("factRevisionKnown",!text(context,"sharedFactRevision").isBlank());
        receipt.put("businessProof",false);return receipt;
    }

    public static final class NoDependencyChange extends RuntimeException {
        private final Map<String,Object> receipt;
        private final String reasonCode;
        private NoDependencyChange(Map<String,Object> receipt,String reasonCode) {
            super(reasonCode,null,false,false);this.receipt=Collections.unmodifiableMap(new LinkedHashMap<>(receipt));this.reasonCode=reasonCode;
        }
        public Map<String,Object> receipt(){return receipt;}
        public String reasonCode(){return reasonCode;}
    }

    private static boolean structuralTargetsOnly(Map<String,Object> previous,Map<String,Object> context) {
        Map<String,Object> plan=object(previous.get("plan"));Map<String,Map<String,Object>> rules=new HashMap<>();collect(rows(plan.get("rules")),rules);
        if(rules.isEmpty()&&RequirementRepairPolicy.requirementWork(text(context,"recoveryStrategy")))return true;
        Set<String> targets=strings(context.get("recoveryTargetObligationIds"));if(targets.isEmpty())return false;
        for(String id:targets){Map<String,Object> rule=rules.get(id);if(rule==null)return false;
            if(!"UNSUPPORTED".equals(text(rule,"predicate")))return false;
            if(text(rule,"sourceQuote").isBlank())return false;
            // A mixed subtree may contain valuable executable work; leave it to normal dispatch.
            if(rows(rule.get("children")).stream().anyMatch(RecoveryDependencyGate::containsExecutable))return false;
        }
        return true;
    }
    private static boolean containsExecutable(Map<String,Object> rule){return !Set.of("UNSUPPORTED","GROUP").contains(text(rule,"predicate"))||rows(rule.get("children")).stream().anyMatch(RecoveryDependencyGate::containsExecutable);}
    private static void collect(List<Map<String,Object>> rows,Map<String,Map<String,Object>> out){for(Map<String,Object> row:rows){out.put(text(row,"id"),row);collect(rows(row.get("children")),out);}}
    private static void indexRules(List<Map<String,Object>> rows,Map<String,Object> out){for(Map<String,Object> row:rows){String id=text(row,"id");if(!id.isBlank())out.put(id,identity(canonical(ruleMeaning(row))));indexRules(rows(row.get("children")),out);}}
    private static Object ruleMeaning(Map<String,Object> rule){
        Map<String,Object> out=select(rule,List.of("predicate","subject","field","operator","expected","unit","sourceQuote","logic"));
        Map<String,Object> parameters=new TreeMap<>(object(rule.get("parameters")));DIAGNOSTIC_PARAMETERS.forEach(parameters::remove);
        out.put("parameters",semantic(parameters));out.put("children",rows(rule.get("children")).stream().map(RecoveryDependencyGate::ruleMeaning).toList());return out;
    }
    private static Object documentIdentity(Object value){
        if(value instanceof Document doc)return Map.of("id",doc.id(),"version",doc.version(),"sha256",doc.sha256());
        return select(object(value),List.of("id","documentId","version","documentVersion","sha256","documentHash","parserFingerprint"));
    }
    private static Object observationMeaning(Map<String,Object> row){return semantic(select(row,List.of("documentId","locationType","location","page","obligationId","type","label","subject","field","value","observedValue","readability","source","sourceBbox","observationIdentity","requestedTask","requestedFields","imageHash")));}
    private static Object externalMeaning(Map<String,Object> row){return semantic(select(row,List.of("type","subject","source","issuer","effectiveAt","evaluationStage","coverage","coverageComplete","rawSnapshotHash","result","provenanceStatus")));}
    private static Object semantic(Object value){
        if(value instanceof Map<?,?> map){Map<String,Object> out=new TreeMap<>();map.forEach((k,v)->{String key=String.valueOf(k);if(!Set.of("savedAt","updatedAt","createdAt","queriedAt","receiptId","attemptId","executionId","description","reason").contains(key))out.put(key,semantic(v));});return out;}
        if(value instanceof Collection<?> all)return all.stream().map(RecoveryDependencyGate::semantic).toList();return value;
    }
    private static Map<String,Object> select(Map<String,Object> source,List<String> keys){Map<String,Object> out=new TreeMap<>();for(String key:keys)if(source.containsKey(key))out.put(key,source.get(key));return out;}
    private static Map<String,Object> object(Object value){if(value==null)return Map.of();if(value instanceof Map<?,?> map){Map<String,Object> out=new LinkedHashMap<>();map.forEach((k,v)->out.put(String.valueOf(k),v));return out;}return JSON.convertValue(value,OBJECT);}
    private static List<Map<String,Object>> rows(Object value){return value instanceof Collection<?> values?values.stream().map(RecoveryDependencyGate::object).toList():List.of();}
    private static Set<String> strings(Object value){Set<String> out=new TreeSet<>();if(value instanceof Collection<?> all)all.forEach(v->out.add(String.valueOf(v)));return out;}
    private static String text(Map<String,Object> value,String key){return Objects.toString(value.get(key),"");}
    private static String canonical(Object value){if(value instanceof Map<?,?> map){Map<String,String> sorted=new TreeMap<>();map.forEach((k,v)->sorted.put(String.valueOf(k),canonical(v)));return sorted.toString();}if(value instanceof Collection<?> all)return all.stream().map(RecoveryDependencyGate::canonical).toList().toString();return Objects.toString(value,"");}
}
