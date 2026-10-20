package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;

/** Business decisions and visual transcription review are independent, frozen dimensions. */
public final class OcrReviewPolicy {
    public static final String VERSION="evidence-review-v4-declared-document-role";
    private OcrReviewPolicy() {}
    public record Decision(Evaluation evaluation,Map<String,Object> review,List<Map<String,String>> adjustments) {}

    public static boolean hasOcr(Document document) {
        return document != null && document.pages() != null
            && document.pages().stream().anyMatch(p -> p.parser() != null && p.parser().contains("OCR"));
    }
    public static Evaluation apply(Evaluation evaluation, Checkpoint rule, Document bid, Document tender) {
        return assess(evaluation,rule,bid,tender).evaluation();
    }
    public static Decision assess(Evaluation evaluation,Checkpoint rule,Document bid,Document tender) {
        return assess(evaluation,rule,bid,tender,Set.of("BID"));
    }

    /**
     * Applies the visual/source guard using the document roles declared by the selected check
     * template. Ordinary checks keep the historical BID requirement through the overload above.
     * A tender-only relationship check may therefore publish a result from tender evidence, but
     * only when that evidence has a non-empty quote and resolves to an actual stored location.
     */
    public static Decision assess(Evaluation evaluation,Checkpoint rule,Document bid,Document tender,
                                  Collection<String> requiredProofRoles) {
        Objects.requireNonNull(evaluation,"evaluation");
        LinkedHashSet<String> proofRoles=new LinkedHashSet<>();
        if(requiredProofRoles!=null)for(String role:requiredProofRoles){
            String normalized=safe(role).strip().toUpperCase(Locale.ROOT);
            if(Set.of("BID","TENDER").contains(normalized))proofRoles.add(normalized);
        }
        if(proofRoles.isEmpty())proofRoles.add("BID");
        List<Evidence> cited=new ArrayList<>();
        if(rule.source()!=null) cited.add(rule.source());
        if(evaluation.evidences()!=null) cited.addAll(evaluation.evidences());
        Map<String,Document> documents=new LinkedHashMap<>();
        if(bid!=null) documents.put(bid.id(),bid);
        if(tender!=null) documents.put(tender.id(),tender);
        Map<String,Map<String,Object>> locations=new LinkedHashMap<>();
        boolean incomplete=false,deferred=false,failed=false;
        Set<String> locatedProofRoles=new LinkedHashSet<>();
        for(Evidence evidence:cited) {
            if(evidence==null) continue;
            Document document=documents.get(evidence.documentId());
            if(document==null || document.pages()==null) continue;
            Page page=document.pages().stream().filter(p->p.number()==evidence.page()
                && Objects.equals(p.locationType(),evidence.locationType())).findFirst().orElse(null);
            if(page==null) continue;
            String documentRole=safe(document.role()).strip().toUpperCase(Locale.ROOT);
            if(proofRoles.contains(documentRole) && evidence.quote()!=null && !evidence.quote().isBlank())
                locatedProofRoles.add(documentRole);
            boolean pageIncomplete=EngineText.ocrIncomplete(page.warning());
            incomplete|=pageIncomplete; deferred|=EngineText.ocrDeferred(page.warning()); failed|=EngineText.ocrFailed(page.warning());
            if(!pageIncomplete && !safe(page.parser()).contains("OCR")) continue;
            String label=("BID".equals(document.role())?"投标文件":"招标文件")+"第 "+page.number()
                +("PAGE".equals(page.locationType())?" 页":" 个内容位置");
            locations.putIfAbsent(document.id()+":"+page.locationType()+":"+page.number(),
                Map.of("documentId",document.id(),"page",page.number(),"locationType",safe(page.locationType()),"label",label,"incomplete",pageIncomplete,"deferred",EngineText.ocrDeferred(page.warning())));
        }
        boolean signature="SIGNATURE_SEAL".equals(rule.type());
        boolean required=signature||!locations.isEmpty();
        boolean missingMaterial="MATERIAL_EXISTENCE".equals(rule.type()) && "FAIL".equals(evaluation.status());
        boolean exhaustive=exhaustive(rule) && ("PASS".equals(evaluation.status())
            || "FAIL".equals(evaluation.status()) && !"ENTITY_FIELD_CONSISTENCY".equals(rule.type()));
        boolean deferredScope=EngineText.deferred(bid) && (missingMaterial || exhaustive);
        String scopeReason="本项需要的完整检查范围仍有图像待按需补识别，现有证据不能证明材料缺失、全文一致或全部对象满足要求。";
        String reviewReason=signature?"文字不能证明签字盖章有效，请查看对应原件。":deferredScope?scopeReason:deferred
            ?"本项引用位置包含按检查计划延期识别的图像，需补识别或核对原件；延期不表示服务失败。"+(failed?"另有引用图像识别未完成。":""):incomplete
            ?"本项引用位置存在未完成的图像识别，需要补识别或核对原件。":required
            ?"本项引用了已返回的 OCR 文字；业务判断基于这些文字，原图中的名称、数字和日期仍需核对。":"";
        Map<String,Object> review=new LinkedHashMap<>();
        review.put("required",required);review.put("incomplete",incomplete);
        review.put("deferred",deferred);review.put("failed",failed);review.put("coverageIncomplete",deferredScope);
        review.put("requiredProofDocumentRoles",List.copyOf(proofRoles));
        review.put("locatedProofDocumentRoles",List.copyOf(locatedProofRoles));
        review.put("locations",List.copyOf(locations.values()));review.put("reason",reviewReason);
        List<Map<String,String>> adjustments=new ArrayList<>();
        Evaluation finalResult=evaluation;
        boolean active=rule.enabled()&&rule.confirmed()&&!"NOT_APPLICABLE".equals(evaluation.status());
        if(active && signature && !"UNDETERMINED".equals(evaluation.status())) {
            adjustments.add(Map.of("code","VISUAL_SIGNATURE_REQUIRED","reason","签章有效性尚未经过原图核验。"));
            finalResult=change(evaluation,"UNDETERMINED","签字或盖章需要图像核验，文字提及不能证明已经签章");
        } else if(active && deferredScope) {
            adjustments.add(Map.of("code","OCR_COVERAGE_DEFERRED","reason",scopeReason));
            finalResult=change(evaluation,"UNDETERMINED",scopeReason);
        } else if(active && incomplete && Set.of("PASS","FAIL").contains(evaluation.status())) {
            adjustments.add(Map.of("code",deferred&&!failed?"OCR_SOURCE_DEFERRED":"OCR_SOURCE_INCOMPLETE","reason",deferred&&!failed?"本项引用的图像位置待按检查计划补识别。":"本项引用的图像位置尚未识别完整。"));
            finalResult=change(evaluation,"PASS".equals(evaluation.status())?"UNDETERMINED":"WARNING",evaluation.reason());
        } else if(active && "PASS".equals(evaluation.status()) && !locatedProofRoles.containsAll(proofRoles)) {
            String roles=String.join("、",proofRoles.stream().map(role->"TENDER".equals(role)?"招标文件":"投标文件").toList());
            adjustments.add(Map.of("code","EVIDENCE_MISSING","reason","未关联可追溯的"+roles+"原文，不能确认通过。"));
            finalResult=change(evaluation,"UNDETERMINED","未获得可追溯的"+roles+"证据，需补充证据后判断");
        }
        return new Decision(finalResult,Collections.unmodifiableMap(review),List.copyOf(adjustments));
    }
    private static boolean exhaustive(Checkpoint rule) {
        if("ENTITY_FIELD_CONSISTENCY".equals(rule.type())) return true;
        if(!"SEMANTIC_RESPONSE".equals(rule.type())) return false;
        String text=safe(rule.title())+" "+safe(rule.field())+" "+safe(rule.expected());
        return text.matches("(?s).*(?:(?:全文|全篇|各处|前后).*(?:一致|相符|统一)|(?:全部|所有|每[名位个]|全体).*(?:人员|员工|岗位|成员|车辆|证书)|(?:人员|员工|岗位|成员|车辆|证书).*(?:全部|所有|全员|均|数量|人数|总数)|全员|人数|总数|去重计分|计数|(?:不少于|至少|不超过|至多)\\s*[0-9一二三四五六七八九十]+\\s*(?:人|名|位|辆|份)).*");
    }
    private static Evaluation change(Evaluation value,String status,String reason) {
        return new Evaluation(status,reason,value.suggestion(),value.evidences()==null?List.of():value.evidences(),0,safe(value.method())+"/EVIDENCE_REVIEW_V3");
    }
    private static String safe(String value) { return value==null?"":value; }
}
