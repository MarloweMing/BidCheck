package cn.bidcheck.verification.engine;

import java.util.*;
import cn.bidcheck.verification.source.VisualObservationPolicy;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** An unsuccessful but completed image observation is not an unexecuted request.
 * Only an exact requested mark on a complete, current page may supply a negative result. */
final class VisualMarkReview {
    private VisualMarkReview() {}

    static String outcome(Map<String,Object> observation){
        String status=str(observation,"readability");if(!"NOT_OBSERVED".equals(status))return status;
        // A provider can report NOT_OBSERVED because the image is blurred or truncated.
        // Such a receipt completes the attempt but never proves an empty signing position.
        boolean uncertain=VisualObservationPolicy.assess(observation).constraints().stream().anyMatch(c->cn.bidcheck.compat.Java8Api.setOf(VisualObservationPolicy.Kind.VALUE_UNREADABLE, VisualObservationPolicy.Kind.LABEL_UNPROVEN, VisualObservationPolicy.Kind.UNCLASSIFIED_RESTRICTION).contains(c.kind()));
        if(uncertain||ProofVerifier.strings(observation.get("modelLimitations")).stream().anyMatch(s->s.matches("(?s).*(?:裁剪|裁断|范围不全|画面不全|超出(?:图片|图像|画面|送检)).*")))return "UNREADABLE";
        return status;
    }

    static List<String> reviewedSubjects(Map<String,Object> observation,String type,List<String> subjects,List<Source> relevant){
        String status=str(observation,"readability");
        if(!cn.bidcheck.compat.Java8Api.setOf("NOT_OBSERVED", "UNREADABLE", "PARTIAL", "CLEAR").contains(status)||!type.equals(str(observation,"type")))return cn.bidcheck.compat.Java8Api.listOf();
        if(cn.bidcheck.compat.Java8Api.setOf("NOT_OBSERVED", "UNREADABLE").contains(status)&&(!cn.bidcheck.compat.Java8Api.isBlank(str(observation,"value"))||!cn.bidcheck.compat.Java8Api.isBlank(str(observation,"observedValue"))))return cn.bidcheck.compat.Java8Api.listOf();
        if(!(type+"_VISIBILITY").equals(str(observation,"requestedTask")))return cn.bidcheck.compat.Java8Api.listOf();
        List<String> requested=ProofVerifier.strings(observation.get("requestedFields"));
        List<String> sourceIds=ProofVerifier.strings(observation.get("requestedSourceIds"));
        if(sourceIds.isEmpty()||relevant.stream().noneMatch(s->sourceIds.contains(s.id())
                &&s.evidence().documentId().equals(str(observation,"documentId"))
                &&s.evidence().page()==RunSession.number(observation.get("location"),-1)))return cn.bidcheck.compat.Java8Api.listOf();
        Map<String,Object> source=map(observation.get("source"));
        if(!Boolean.FALSE.equals(source.get("roiApplied")))return cn.bidcheck.compat.Java8Api.listOf();
        double width=number(source.get("renderedWidth")),height=number(source.get("renderedHeight"));
        Map<String,Object> transform=map(source.get("transform"));
        if(width<=0||height<=0||number(transform.get("cropX"))!=0||number(transform.get("cropY"))!=0
                ||number(transform.get("cropWidth"))!=width||number(transform.get("cropHeight"))!=height)return cn.bidcheck.compat.Java8Api.listOf();
        // Readability failure is never used to infer absence. Existing bounds, if supplied, remain checked.
        if(observation.containsKey("sourceBbox")&&!VisualObservationPolicy.validSourceBounds(observation))return cn.bidcheck.compat.Java8Api.listOf();
        return cn.bidcheck.compat.Java8Api.toList(subjects.stream().filter(requested::contains));
    }
    private static double number(Object value){return value instanceof Number&&Double.isFinite(((Number) (value)).doubleValue())?((Number) (value)).doubleValue():Double.NaN;}
    private static Map<String,Object> map(Object value){Map<String,Object> result=new LinkedHashMap<>();if(value instanceof Map<?,?>)((Map<?,?>) (value)).forEach((k,v)->result.put(String.valueOf(k),v));return result;}
}
