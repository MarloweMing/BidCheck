package cn.bidcheck.verification.engine;

import java.util.*;
import cn.bidcheck.verification.source.VisualObservationPolicy;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** An unsuccessful but completed image observation is not an unexecuted request.
 * Only an exact requested mark on a complete, current page may supply a negative result. */
final class VisualMarkReview {
    private VisualMarkReview() {}

    static String outcome(Map<String,Object> observation) {
        String status = str(observation, "readability");
        if (!"NOT_OBSERVED".equals(status)) return status;
        boolean explicitAbsence = explicitlyEmptyMark(observation);
        // A completed negative attempt is not proof of absence when the actual image is
        // unclear, incomplete or subject to an unexplained restriction.
        boolean uncertain = VisualObservationPolicy.assess(observation).constraints().stream()
            .anyMatch(constraint -> {
                boolean blocksNegative = cn.bidcheck.compat.Java8Api.setOf(
                    VisualObservationPolicy.Kind.VALUE_UNREADABLE,
                    VisualObservationPolicy.Kind.LABEL_UNPROVEN,
                    VisualObservationPolicy.Kind.UNCLASSIFIED_RESTRICTION).contains(constraint.kind());
                return blocksNegative && !(explicitAbsence
                    && emptyMarkDisclaimer(constraint, str(observation, "label")));
            });
        if (uncertain || ProofVerifier.strings(observation.get("modelLimitations")).stream()
                .anyMatch(note -> note.matches(
                    "(?s).*(?:裁剪|裁断|范围不全|画面不全|超出(?:图片|图像|画面|送检)).*"))) {
            return "UNREADABLE";
        }
        return status;
    }

    /** Applies only to a typed, empty negative result. It cannot admit a positive field. */
    private static boolean explicitlyEmptyMark(Map<String,Object> observation) {
        if (!cn.bidcheck.compat.Java8Api.setOf("SIGNATURE", "SEAL").contains(str(observation, "type"))
                || !str(observation, "value").isEmpty()
                || !str(observation, "observedValue").isEmpty()) return false;
        List<String> notes = ProofVerifier.strings(observation.get("modelLimitations"));
        String text = String.join("\n", notes);
        // Even an otherwise explicit blank statement cannot explain away physical uncertainty.
        if (text.matches("(?s).*(?:模糊|不清|不详|未完全清晰|遮挡|裁切|裁剪|裁断"
                + "|范围不全|画面不全|分辨率|反光|褪色|无法辨|不能辨|难以辨"
                + "|超出(?:图片|图像|画面|送检)).*")) return false;
        return text.matches("(?s).*(?:未(?:观察到|见)(?:任何|实际|可见)?(?:手写)?"
                + "(?:签名|签字|印章|公章|签章)|无任何手写(?:内容|签名|签字)"
                + "|(?:签字|签名|盖章)(?:处|栏|位置)[^。；;\\n]{0,12}空白).*");
    }

    private static boolean emptyMarkDisclaimer(VisualObservationPolicy.Constraint constraint,
            String label) {
        String note = constraint.message();
        if (constraint.kind() == VisualObservationPolicy.Kind.VALUE_UNREADABLE) {
            // An unfilled printed line is different from a mark that exists but is unreadable.
            String slot = "['‘’“”]?[（(]?(?:签字|签名|盖章|签章|印章)[）)]?"
                + "[：:]?[_＿—\\s-]*['‘’“”]?";
            String field = "(?:签字|签名|盖章|签章|印章)(?:栏|位置|字段)";
            return note.matches("(?:" + slot + "|" + field + ")(?:为|是)印刷占位符"
                + "[，,]?非实际(?:签名|签字|印章|签章)(?:痕迹)?");
        }
        if (constraint.kind() != VisualObservationPolicy.Kind.UNCLASSIFIED_RESTRICTION
                || label.isEmpty()) return false;
        String named = "['‘’“”]?" + java.util.regex.Pattern.quote(label) + "['‘’“”]?";
        // With explicit absence, inability to identify a signer is not image unreadability.
        // Any additional or unknown restriction fails this exact, closed language pattern.
        return note.matches("无法确认(?:是否由" + named + "签署(?:或他人代签)?|"
            + named + "是否(?:已)?签署|是否(?:已)?签署(?:或由谁签署)?)");
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
