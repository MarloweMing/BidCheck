package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.*;

/** Presentation consumes the engine's bound page receipts, never a gap code alone. */
final class TrustedVisualReview {
    private TrustedVisualReview() {}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Result {
        private final List<Map<String,Object>> observations;
        private final boolean unreadableOnly;
        @com.fasterxml.jackson.annotation.JsonCreator
        Result(@com.fasterxml.jackson.annotation.JsonProperty("observations") List<Map<String,Object>> observations, @com.fasterxml.jackson.annotation.JsonProperty("unreadableOnly") boolean unreadableOnly) { this.observations = observations; this.unreadableOnly = unreadableOnly; }
        public List<Map<String,Object>> observations() { return observations; }
        public boolean unreadableOnly() { return unreadableOnly; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Result that = (Result) other; return java.util.Objects.equals(this.observations, that.observations) && java.util.Objects.equals(this.unreadableOnly, that.unreadableOnly); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.observations); hash = 31 * hash + java.util.Objects.hashCode(this.unreadableOnly); return hash; }
        @Override public String toString() { return "Result[" + "observations=" + observations + ", unreadableOnly=" + unreadableOnly + "]"; }

        boolean reviewed(){return !observations.isEmpty();}
        boolean negative(){return observations.stream().anyMatch(v->Boolean.TRUE.equals(v.get("negativeConclusion")));}
    }
    static Result inspect(Map<String,Object> assessment){
        Map<String,Object> atomic=map(assessment.get("atomicCheck"));List<Map<String,Object>> observations=new ArrayList<>();
        List<Map<String,Object>> conditions=maps(atomic.get("conditions"));boolean only=!conditions.isEmpty();
        for(Map<String,Object> condition:conditions){
            if(cn.bidcheck.compat.Java8Api.setOf("PASS", "NOT_APPLICABLE").contains(text(condition,"status")))continue;
            if(!Boolean.TRUE.equals(condition.get("evaluated"))||!reviewedNode(condition,text(condition,"id"),maps(atomic.get("documentIdentities")),observations))only=false;
        }
        List<Map<String,Object>> distinct=cn.bidcheck.compat.Java8Api.toList(observations.stream().distinct());
        only=only&&distinct.stream().anyMatch(v->cn.bidcheck.compat.Java8Api.setOf("UNREADABLE", "PARTIAL").contains(text(v,"outcome")))
            &&maps(atomic.get("gaps")).stream().allMatch(g->"VISUAL_MARK_UNREADABLE".equals(text(g,"code")))
            &&maps(assessment.get("guards")).isEmpty()&&maps(assessment.get("adjustments")).isEmpty()
            &&maps(atomic.get("externalRequests")).isEmpty()
            &&maps(atomic.get("fieldCoverage")).stream().noneMatch(row->cn.bidcheck.compat.Java8Api.setOf("UNREAD", "FAILED").contains(text(row,"status")))
            &&!cn.bidcheck.compat.Java8Api.setOf("UNRESOLVED", "VALIDATION_FAILED", "REPAIR_RETRY_PENDING").contains(text(map(atomic.get("compilationAudit")),"state"))
            &&!cn.bidcheck.compat.Java8Api.setOf("ENGINE_FAILURE", "ERROR", "PROCESSING_FAILED", "WAIT_STAGE").contains(text(atomic,"workflowState"))
            &&!Boolean.TRUE.equals(map(assessment.get("ocrReview")).get("incomplete"))
            &&!"RULE_UNSUPPORTED".equals(text(map(assessment.get("model")),"decision"))
            &&!"UNAVAILABLE".equals(text(map(assessment.get("model")),"state"))
            &&!Boolean.FALSE.equals(map(map(assessment.get("coverage")).get("completeness")).get("inventoryResolved"))
            &&!Boolean.TRUE.equals(map(map(assessment.get("coverage")).get("completeness")).get("coreRequirementDeferred"))
            &&!(Boolean.TRUE.equals(map(map(assessment.get("coverage")).get("semanticGap")).get("validated"))
                &&!cn.bidcheck.compat.Java8Api.isBlank(text(map(map(assessment.get("coverage")).get("semanticGap")),"kind")))
            &&maps(map(map(assessment.get("coverage")).get("completeness")).get("itemResults")).stream().noneMatch(row->"REQUIREMENT_UNRESOLVED".equals(text(row,"status")))
            &&pendingRequests(atomic,distinct).isEmpty();
        return new Result(distinct,only);
    }
    private static boolean reviewedNode(Map<String,Object> node,String obligation,List<Map<String,Object>> identities,List<Map<String,Object>> out){
        if(cn.bidcheck.compat.Java8Api.setOf("PASS", "NOT_APPLICABLE").contains(text(node,"status")))return true;
        Map<String,Object> proof=map(node.get("proof"));
        if("VISUAL_OBSERVATION".equals(text(proof,"decisionBasis"))){
            if(!cn.bidcheck.compat.Java8Api.setOf("NOT_OBSERVED", "UNREADABLE_OR_PARTIAL_REVIEW").contains(text(proof,"observationOutcome"))
                ||!Boolean.FALSE.equals(proof.get("automaticRejection"))||!Boolean.FALSE.equals(proof.get("authenticityAssessed")))return false;
            List<Map<String,Object>> observations=maps(proof.get("observations"));if(observations.isEmpty())return false;
            if(observations.stream().anyMatch(v->!valid(v,obligation,node.get("evidences"),identities)))return false;
            boolean negative="FAIL".equals(text(node,"status"))&&"NOT_OBSERVED".equals(text(proof,"observationOutcome"))
                &&!strings(proof.get("missingVisibleSubjects")).isEmpty()
                &&strings(proof.get("missingVisibleSubjects")).stream().allMatch(subject->observations.stream().anyMatch(o->"NOT_OBSERVED".equals(text(o,"outcome"))&&strings(o.get("subjects")).contains(subject)));
            for(Map<String,Object> observation:observations){Map<String,Object> classified=new LinkedHashMap<>(observation);
                classified.put("negativeConclusion",negative&&"NOT_OBSERVED".equals(text(observation,"outcome"))
                    &&strings(observation.get("subjects")).stream().anyMatch(strings(proof.get("missingVisibleSubjects"))::contains));out.add(classified);}
            return !"NOT_OBSERVED".equals(text(proof,"observationOutcome"))||negative;
        }
        Map<String,Object> signatures=map(proof.get("signatureCoverage"));
        if(!signatures.isEmpty())return reviewedNode(signatures,obligation,identities,out);
        List<Map<String,Object>> children=maps(proof.get("children"));if(children.isEmpty())return false;
        boolean complete=true;for(Map<String,Object> child:children)if(!reviewedNode(child,obligation,identities,out))complete=false;
        return complete;
    }
    private static boolean valid(Map<String,Object> value,String obligation,Object evidence,List<Map<String,Object>> identities){
        if(cn.bidcheck.compat.Java8Api.isBlank(obligation)||!obligation.equals(text(value,"obligationId"))||!Boolean.TRUE.equals(value.get("sourceBindingVerified"))
            ||!"CURRENT_FULL_PAGE".equals(text(value,"coverage"))||!cn.bidcheck.compat.Java8Api.setOf("SEAL", "SIGNATURE").contains(text(value,"type"))
            ||!cn.bidcheck.compat.Java8Api.setOf("NOT_OBSERVED", "UNREADABLE", "PARTIAL").contains(text(value,"outcome"))
            ||cn.bidcheck.compat.Java8Api.isBlank(text(value,"observationId"))||cn.bidcheck.compat.Java8Api.isBlank(text(value,"documentId"))||cn.bidcheck.compat.Java8Api.isBlank(text(value,"imageHash"))||cn.bidcheck.compat.Java8Api.isBlank(text(value,"documentHash"))
            ||number(value.get("documentVersion"))<1||number(value.get("location"))<1||strings(value.get("subjects")).isEmpty()||strings(value.get("requestedSourceIds")).isEmpty())return false;
        if(identities.stream().noneMatch(d->text(d,"id").equals(text(value,"documentId"))&&number(d.get("version"))==number(value.get("documentVersion"))&&text(d,"sha256").equals(text(value,"documentHash"))))return false;
        if(!(evidence instanceof Collection<?>))return false;
        return ((Collection<?>) (evidence)).stream().anyMatch(raw->{Map<String,Object> e=evidence(raw);return text(value,"documentId").equals(text(e,"documentId"))
            &&number(value.get("location"))==number(e.get("page"))&&"PAGE".equals(text(e,"locationType"))
            &&("image:"+text(value,"imageHash")).equals(text(e,"anchor"))&&!cn.bidcheck.compat.Java8Api.isBlank(text(e,"quote"));});
    }
    static List<Map<String,Object>> pendingRequests(Map<String,Object> atomic,List<Map<String,Object>> observed){
        return cn.bidcheck.compat.Java8Api.toList(maps(atomic.get("visualRequests")).stream().filter(request->!covered(request,observed)));
    }
    private static boolean covered(Map<String,Object> request,List<Map<String,Object>> observed){
        List<String> fields=strings(request.get("fields")),sources=strings(request.get("sourceIds"));
        if(fields.isEmpty()||sources.isEmpty()||!"PAGE".equals(text(request,"locationType")))return false;
        return fields.stream().allMatch(field->observed.stream().anyMatch(o->text(request,"obligationId").equals(text(o,"obligationId"))
            &&text(request,"documentId").equals(text(o,"documentId"))&&number(request.get("location"))==number(o.get("location"))
            &&text(request,"task").equals(text(o,"type")+"_VISIBILITY")&&strings(o.get("subjects")).contains(field)
            &&strings(o.get("requestedSourceIds")).containsAll(sources)));
    }
    private static Map<String,Object> evidence(Object raw){
        if(raw instanceof Evidence)return cn.bidcheck.compat.Java8Api.mapOf("documentId", ((Evidence) (raw)).documentId(), "page", ((Evidence) (raw)).page(), "locationType", ((Evidence) (raw)).locationType(), "quote", ((Evidence) (raw)).quote(), "anchor", ((Evidence) (raw)).anchor());
        return map(raw);
    }
    static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?>))return cn.bidcheck.compat.Java8Api.mapOf();Map<String,Object> out=new LinkedHashMap<>();((Map<?,?>) (value)).forEach((k,v)->out.put(String.valueOf(k),v));return out;}
    static List<Map<String,Object>> maps(Object value){return value instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().map(TrustedVisualReview::map)):cn.bidcheck.compat.Java8Api.listOf();}
    static String text(Map<String,Object> value,String key){return Objects.toString(value.get(key),"");}
    static List<String> strings(Object value){return value instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().filter(String.class::isInstance).map(String.class::cast).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s))):cn.bidcheck.compat.Java8Api.listOf();}
    static int number(Object value){return value instanceof Number?((Number) (value)).intValue():-1;}
}
