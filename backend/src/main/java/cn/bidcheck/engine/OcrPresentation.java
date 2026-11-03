package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;

/** Read-only clarification of document coverage; never changes frozen decisions or raw evidence. */
public final class OcrPresentation {
    private OcrPresentation() {}
    private static final List<String> OLD_PREFIXES = cn.bidcheck.compat.Java8Api.listOf("部分图像OCR未完成，现有文字不能证明全部内容已经核验。", "本次检查使用了OCR识别文字，需对照原图复核。");
    private static final String OLD_SUGGESTION = "请核对原图中的名称、数字、日期及标识；OCR文字不能证明签字盖章有效。";
    private static final java.util.regex.Pattern WARNING_LOCATION = java.util.regex.Pattern.compile("^第\\s*([0-9]{1,9})\\s*(页|个内容块|块|段|张图像)");

    public static Map<String,Object> context(List<Finding> findings, List<Document> documents) {
        Map<String,Document> indexed = new LinkedHashMap<>();
        for (Document document : documents) if (document != null) indexed.putIfAbsent(document.id(), document);
        List<Map<String,Object>> coverage = new ArrayList<>();
        for (Document document : indexed.values()) coverage.add(coverage(document));
        Map<String,Object> displays = new LinkedHashMap<>();
        for (Finding finding : findings) {
            Evaluation evaluation = finding.evaluation();
            String original = safe(evaluation.reason()), reason = original, suggestion = safe(evaluation.suggestion());
            boolean guarded = safe(evaluation.method()).contains("OCR_REVIEW");
            boolean legacy = safe(evaluation.method()).endsWith("/OCR_REVIEW") && suggestion.startsWith(OLD_SUGGESTION);
            if (legacy) for (String prefix : OLD_PREFIXES) if (reason.startsWith(prefix)) {
                reason = reason.substring(prefix.length());
                if (suggestion.startsWith(OLD_SUGGESTION)) suggestion = suggestion.substring(OLD_SUGGESTION.length());
                break;
            }
            boolean clarified = !reason.equals(original);
            List<Evidence> sources = new ArrayList<>();
            if (finding.checkpoint().source() != null) sources.add(finding.checkpoint().source());
            if (evaluation.evidences() != null) sources.addAll(evaluation.evidences());
            Set<String> incomplete = new LinkedHashSet<>(), recognized = new LinkedHashSet<>(), deferred = new LinkedHashSet<>();
            for (Evidence source : sources) {
                if (source == null) continue;
                Document document = indexed.get(source.documentId());
                if (document == null || document.pages() == null) continue;
                document.pages().stream().filter(page -> page.number() == source.page()
                    && Objects.equals(page.locationType(), source.locationType())).findFirst().ifPresent(page -> {
                        if (EngineText.ocrDeferred(page.warning())) deferred.add(label(document,page));
                        if (EngineText.ocrFailed(page.warning())) incomplete.add(label(document,page));
                        if (safe(page.parser()).contains("OCR")) recognized.add(label(document,page));
                    });
            }
            String note = "";
            if (!deferred.isEmpty()) note = "本项引用位置有待按需补识别的图像：" + String.join("、", deferred) + "。这是检查计划的延期范围，不表示识别服务失败；不能据此确认材料缺失或全文已核验。";
            if (!incomplete.isEmpty()) note += "本项引用位置有未完成的图像识别：" + String.join("、", incomplete) + "。请查看这些位置的原件或补识别；不能由现有文字判断全部内容。";
            else if (deferred.isEmpty() && !recognized.isEmpty()) note = "本项引用包含 OCR 文字：" + String.join("、", recognized) + "。这些引用位置没有 OCR 未完成标记，文字准确性仍需对照原图；签字盖章未核验。";
            else if (deferred.isEmpty() && guarded) note = "当前引用未定位到 OCR 未完成的位置。该结果保留文档级 OCR 复核策略的保守状态，不代表本条图像识别失败，也不代表全文或语义核验通过。";
            if (finding.assessment()!=null && finding.assessment().get("ocrReview") instanceof Map<?,?>)
                note = ((Map<?,?>) (finding.assessment().get("ocrReview"))).get("reason") instanceof String ? ((String) (((Map<?,?>) (finding.assessment().get("ocrReview"))).get("reason"))) : "";
            Map<String,Object> display = new LinkedHashMap<>();
            display.put("reason",reason); display.put("suggestion",suggestion); display.put("note",note);
            display.put("legacyClarified",clarified); display.put("originalReason",original);
            display.put("originalSuggestion",safe(evaluation.suggestion()));
            displays.put(finding.id(),display);
        }
        return cn.bidcheck.compat.Java8Api.mapOf("documents", cn.bidcheck.compat.Java8Api.listCopyOf(coverage), "findings", displays);
    }

    public static Map<String,Object> coverage(Document document) {
        List<Page> pages = document.pages() == null ? cn.bidcheck.compat.Java8Api.listOf() : document.pages();
        List<Map<String,Object>> incomplete = new ArrayList<>();
        List<Map<String,Object>> deferred = new ArrayList<>(), failed = new ArrayList<>();
        Map<String,Map<String,Object>> layoutLocations = new LinkedHashMap<>();
        Set<String> attachedLayoutWarnings = new HashSet<>();
        Set<String> attachedWarnings = new HashSet<>();
        long ocr = 0;
        for (Page page : pages) {
            if (safe(page.parser()).contains("OCR")) ocr++;
            if (layoutIncomplete(page.warning())) {
                layoutLocations.put(page.locationType()+":"+page.number(),location(document,page,page.warning()));
                attachedLayoutWarnings.add(page.warning());
            }
            if (EngineText.ocrIncomplete(page.warning())) {
                Map<String,Object> location = cn.bidcheck.compat.Java8Api.mapOf("documentId", document.id(), "page", page.number(), "locationType", safe(page.locationType()), "label", label(document,page), "warning", page.warning());
                incomplete.add(location);
                if (EngineText.ocrDeferred(page.warning())) deferred.add(location);
                if (EngineText.ocrFailed(page.warning())) failed.add(location);
                attachedWarnings.add(page.warning());
            }
        }
        Set<String> unlocatedLayout = new LinkedHashSet<>();
        for (String warning : document.warnings() == null ? cn.bidcheck.compat.Java8Api.<String>listOf() : document.warnings()) {
            if (!layoutIncomplete(warning) || attachedLayoutWarnings.contains(warning)) continue;
            java.util.regex.Matcher match = WARNING_LOCATION.matcher(warning);
            List<Page> matching = match.find() ? cn.bidcheck.compat.Java8Api.toList(pages.stream().filter(p -> p.number() == Integer.parseInt(match.group(1))
                && (java.util.Objects.equals((match.group(2)), "页") ? "PAGE".equals(p.locationType()) : (java.util.Objects.equals((match.group(2)), "段") ? "PARAGRAPH".equals(p.locationType()) : (java.util.Objects.equals((match.group(2)), "张图像") ? "IMAGE".equals(p.locationType()) : cn.bidcheck.compat.Java8Api.setOf("BLOCK", "PARAGRAPH").contains(safe(p.locationType()))))))) : cn.bidcheck.compat.Java8Api.listOf();
            if (matching.size() == 1) {
                Page page = matching.get(0);
                layoutLocations.putIfAbsent(page.locationType()+":"+page.number(),location(document,page,warning));
            } else unlocatedLayout.add(warning);
        }
        long layoutIncompleteCount = layoutLocations.size() + unlocatedLayout.size();
        List<String> unlocatedWarnings = document.warnings() == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.toList(document.warnings().stream().filter(w -> EngineText.ocrIncomplete(w) && !attachedWarnings.contains(w)).distinct());
        long unlocated = unlocatedWarnings.size();
        long deferredCount = deferred.size() + unlocatedWarnings.stream().filter(EngineText::ocrDeferred).count();
        long failedCount = failed.size() + unlocatedWarnings.stream().filter(EngineText::ocrFailed).count();
        boolean pdf = !pages.isEmpty() && pages.stream().allMatch(page -> "PAGE".equals(page.locationType()));
        String unit = pdf ? "页" : "个内容位置";
        long totalIncomplete = incomplete.size() + unlocated;
        String notice = deferredCount > 0 ? role(document) + " V" + document.version() + "：共 " + pages.size() + unit
            + "，按检查计划已识别 " + ocr + " 处，" + deferredCount + " 处待按需补识别；另有 " + failedCount + " 处识别未完成。计划延期不表示识别服务失败，未读取范围不能作为材料缺失或全文通过的证明。"
            : role(document) + " V" + document.version() + "：共 " + pages.size() + unit + "，" + ocr + unit
            + "含 OCR 文字；解析记录标记未完成 " + totalIncomplete + " 处。" + (unlocated > 0 ? "其中 " + unlocated + " 处未能定位到具体位置。" : "")
            + "识别返回不等于文字准确或语义符合。";
        if (layoutIncompleteCount > 0) notice += "另有 " + layoutIncompleteCount + " 处文字或表格布局待复核；转录文字已保留，表格关系和位置尚未完整核实。";
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("documentId",document.id()); value.put("name",document.name()); value.put("role",document.role()); value.put("version",document.version());
        value.put("totalLocations",pages.size()); value.put("ocrLocations",ocr); value.put("incompleteCount",totalIncomplete);
        value.put("notice",notice); value.put("incompleteLocations",incomplete);
        value.put("deferredCount",deferredCount); value.put("failedCount",failedCount);
        value.put("deferredLocations",deferred); value.put("failedLocations",failed);
        value.put("unlocatedIncompleteCount",unlocated); value.put("ocrCoverageComplete",totalIncomplete == 0);
        value.put("structureIncompleteCount",layoutIncompleteCount);
        value.put("structureIncompleteLocations",cn.bidcheck.compat.Java8Api.listCopyOf(layoutLocations.values()));
        value.put("unlocatedStructureWarnings",cn.bidcheck.compat.Java8Api.listCopyOf(unlocatedLayout));
        value.put("unlocatedStructureCount",(long)unlocatedLayout.size());
        value.put("structureCoverageComplete",layoutIncompleteCount == 0);
        return value;
    }
    private static boolean layoutIncomplete(String warning) { return safe(warning).contains("[OCR结构未完成]"); }
    private static Map<String,Object> location(Document document,Page page,String warning) {
        return cn.bidcheck.compat.Java8Api.mapOf("documentId", document.id(), "page", page.number(), "locationType", safe(page.locationType()), "label", label(document,page), "warning", warning);
    }
    private static String role(Document document) { return "TENDER".equals(document.role()) ? "招标文件" : "BID".equals(document.role()) ? "投标文件" : "文件"; }
    private static String label(Document document,Page page) { return role(document) + "第 " + page.number() + (java.util.Objects.equals((safe(page.locationType())), "PAGE") ? " 页" : (java.util.Objects.equals((safe(page.locationType())), "PARAGRAPH") ? " 段" : (java.util.Objects.equals((safe(page.locationType())), "IMAGE") ? " 张图像" : " 个内容块"))); }
    private static String safe(String value) { return value == null ? "" : value; }
}
