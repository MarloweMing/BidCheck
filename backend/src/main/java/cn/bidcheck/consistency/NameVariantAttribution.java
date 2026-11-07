package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evaluation;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;

/** A source-specific reading question does not establish which printed name is correct. */
public final class NameVariantAttribution {
    public static final String CODE = "OCR_ONLY_SINGLE_CHARACTER_NAME_DIFFERENCE";
    private NameVariantAttribution() {}

    public static void apply(Document document, Map<String,Object> report) {
        Map<String,Map<String,Object>> occurrences = new LinkedHashMap<>();
        for (Map<String,Object> occurrence : ConsistencyRunSupport.maps(report.get("occurrences")))
            occurrences.put(text(occurrence,"id"), occurrence);
        for (Map<String,Object> group : ConsistencyRunSupport.maps(report.get("groups")))
            attribute(document, group, occurrences);
    }

    private static void attribute(Document document, Map<String,Object> group,
            Map<String,Map<String,Object>> occurrences) {
        for (Map<String,Object> child : ConsistencyRunSupport.maps(group.get("organizationComparisons")))
            attribute(document, child, occurrences);
        if (!"personName".equals(text(group,"field"))) return;
        List<Map<String,Object>> sides = new ArrayList<>();
        for (Object id : list(group.get("occurrenceIds"))) {
            Map<String,Object> occurrence = occurrences.get(Objects.toString(id,""));
            if (occurrence == null || !"personName".equals(text(occurrence,"field"))) return;
            sides.add(occurrence);
        }
        Map<String,List<Map<String,Object>>> variants = new LinkedHashMap<>();
        for (Map<String,Object> side : sides)
            variants.computeIfAbsent(text(side,"value"), ignored -> new ArrayList<>()).add(side);
        if (variants.size() != 2) return;
        List<String> names = new ArrayList<>(variants.keySet());
        if (!singleCharacterDifference(names.get(0), names.get(1))) return;
        List<String> imageNames = new ArrayList<>();
        Set<Integer> imagePages = new TreeSet<>();
        Set<String> imageMethods = new LinkedHashSet<>();
        for (String name : names) {
            if (nativeSupport(document, name)) continue;
            boolean imageOnly = true;
            Set<Integer> pages = new TreeSet<>();
            Set<String> methods = new LinkedHashSet<>();
            for (Map<String,Object> side : variants.get(name)) {
                Page page = originalPage(document, side);
                if (page == null || !imageReading(page, name)) { imageOnly = false; break; }
                pages.add(page.number());
                String parser = Objects.toString(page.parser(), "").toUpperCase(Locale.ROOT);
                if (parser.contains("OCR")) methods.add("OCR 转录");
                if (parser.contains("VISUAL")) methods.add("视觉识别");
            }
            if (imageOnly && !pages.isEmpty()) {
                imageNames.add(name); imagePages.addAll(pages); imageMethods.addAll(methods);
            }
        }
        if (imageNames.isEmpty()) return;
        String pages = imagePages.toString().replace("[", "").replace("]", "");
        String question = "请核对第 " + pages + " 页原图中的姓名。当前两种读法为“" + names.get(0)
            + "”和“" + names.get(1) + "”，其中“" + String.join("、", imageNames)
            + "”仅有图片识别来源（" + String.join("、", imageMethods) + "），需核对图片原文。";
        Map<String,Object> receipt = new LinkedHashMap<>();
        receipt.put("code", CODE);
        receipt.put("values", names);
        receipt.put("imageOnlyValues", imageNames);
        receipt.put("imagePages", new ArrayList<>(imagePages));
        receipt.put("imageReadingMethods", new ArrayList<>(imageMethods));
        receipt.put("question", question);
        receipt.put("reason", question);
        receipt.put("nextStep", question);
        receipt.put("sourceRule", "ONE_CHARACTER_AND_IMAGE_ONLY_WITHOUT_NATIVE_SUPPORT");
        group.putIfAbsent("originalDifferenceReason", group.get("reason"));
        group.put("nameVariantAttribution", receipt);
        if ("法定代表人".equals(text(group,"subject"))) group.put("title","法定代表人姓名需核对");
        group.put("reason", question);
    }

    public static Evaluation evaluation(Map<String,Object> group, Evaluation current) {
        Map<String,Object> receipt = ConsistencyRunSupport.map(group.get("nameVariantAttribution"));
        if (!CODE.equals(text(receipt,"code")) || text(receipt,"question").isEmpty()
                || !Arrays.asList("WARNING","UNDETERMINED","NEEDS_CONFIRMATION").contains(current.status())) return current;
        return new Evaluation("NEEDS_CONFIRMATION", text(receipt,"question"), text(receipt,"question"),
            current.evidences(), current.confidence(), "OCR_NAME_VARIANT_CONFIRMATION");
    }

    private static boolean singleCharacterDifference(String first, String second) {
        int[] a = first.codePoints().toArray(), b = second.codePoints().toArray();
        if (a.length < 2 || a.length != b.length) return false;
        int differences = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) differences++;
        return differences == 1;
    }

    private static boolean nativeSupport(Document document, String value) {
        for (Page page : document.pages()) if (nativeText(page).replaceAll("\\s+", "").contains(value)) return true;
        return false;
    }

    private static boolean imageReading(Page page, String value) {
        String parser = Objects.toString(page.parser(), "").toUpperCase(Locale.ROOT);
        if (!parser.contains("OCR") && !parser.contains("VISUAL")) return false;
        String original = Objects.toString(page.text(), "");
        return original.replaceAll("\\s+", "").contains(value)
            && !nativeText(page).replaceAll("\\s+", "").contains(value);
    }

    private static String nativeText(Page page) {
        String original = Objects.toString(page.text(), "");
        String parser = Objects.toString(page.parser(), "").toUpperCase(Locale.ROOT);
        if (!parser.contains("OCR") && !parser.contains("VISUAL")) return original;
        int boundary = original.indexOf("【PDF识别文字】");
        int other = original.indexOf("【OCR识别文字】");
        if (other >= 0 && (boundary < 0 || other < boundary)) boundary = other;
        return boundary < 0 ? "" : original.substring(0,boundary);
    }

    private static Page originalPage(Document document, Map<String,Object> occurrence) {
        Object value = occurrence.get("evidence");
        Evidence evidence;
        if (value instanceof Evidence) evidence = (Evidence)value;
        else {
            Map<String,Object> source = ConsistencyRunSupport.map(value);
            if (!(source.get("page") instanceof Number)) return null;
            evidence = new Evidence(text(source,"documentId"),text(source,"documentName"),
                ((Number)source.get("page")).intValue(),text(source,"locationType"),text(source,"quote"),text(source,"anchor"),0);
        }
        if (!document.id().equals(evidence.documentId()) || evidence.quote() == null) return null;
        for (Page page : document.pages()) if (page.number() == evidence.page()
                && Objects.equals(page.locationType(),evidence.locationType()) && page.text() != null
                && page.text().contains(evidence.quote())) return page;
        return null;
    }

    private static List<?> list(Object value) { return value instanceof List<?> ? (List<?>)value : Collections.emptyList(); }
    private static String text(Map<String,Object> value, String key) { return Objects.toString(value.get(key),""); }
}
