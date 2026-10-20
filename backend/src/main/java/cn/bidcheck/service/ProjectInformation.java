package cn.bidcheck.service;

import cn.bidcheck.document.ProfileExtractor;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Checkpoint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Project fields retain their source and explicit user corrections across document versions. */
final class ProjectInformation {
    static final List<String> FIELDS = List.of("name", "projectNumber", "openingTime");
    private static final Map<String,String> LABELS = Map.of("项目名称", "name", "项目编号", "projectNumber", "开标时间", "openingTime");
    private static final Pattern DATE = Pattern.compile("^(\\d{4})[-./年](\\d{1,2})[-./月](\\d{1,2})日?(?:[T\\s]*(\\d{1,2})[:：时](\\d{1,2})分?(?::\\d{2})?)?(?:\\s*[（(]北京时间[）)])?$");
    private ProjectInformation() {}

    static String openingTime(String raw) {
        if (raw == null || raw.isBlank()) return "";
        Matcher m = DATE.matcher(raw.strip());
        if (!m.matches()) throw BidService.bad("开标时间请填写有效日期或时间，例如 2026-10-13 09:30");
        try {
            LocalDate date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            if (m.group(4) == null) return date.toString();
            return LocalDateTime.of(date, java.time.LocalTime.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        } catch (java.time.DateTimeException ex) { throw BidService.bad("开标日期或时间无效，请核对后保存"); }
    }

    static Set<String> manualFields(Map<String,Object> project) {
        Set<String> result = new LinkedHashSet<>();
        if (project.get("manualFields") instanceof Collection<?> items) for (Object item : items) if (FIELDS.contains(item)) result.add(item.toString());
        return result;
    }

    static boolean usesOpeningDate(Checkpoint checkpoint) {
        if(checkpoint.source()==null || checkpoint.source().quote()==null)return false;
        String quote=checkpoint.source().quote();
        // An independently stated bid deadline, or a user's changed rule date, must keep its own basis.
        if(quote.matches("(?s).*(?:投标(?:文件)?(?:递交)?截止|递交截止).*"))return false;
        Matcher matcher=Pattern.compile("开标(?:时间|日期)\\s*(?:为|是)?\\s*[:：]?\\s*(20\\d{2}[-./年]\\d{1,2}[-./月]\\d{1,2}日?)").matcher(quote);
        if(!matcher.find())return false;
        try {return openingTime(matcher.group(1)).equals(checkpoint.expected());}catch(RuntimeException ignored){return false;}
    }

    static void apply(Document tender, Map<String,Object> project) {
        Map<String,LinkedHashMap<String,Object>> candidates = new LinkedHashMap<>();
        Map<String,Object> profile = new ProfileExtractor().extract(tender);
        if (profile.get("groups") instanceof List<?> groups) for (Object rawGroup : groups) {
            if (!(rawGroup instanceof Map<?,?> group) || !"项目基本信息".equals(group.get("title"))) continue;
            if (group.get("items") instanceof List<?> items) for (Object rawItem : items) {
                if (!(rawItem instanceof Map<?,?> item)) continue;
                String key = LABELS.get(String.valueOf(item.get("label")));
                if (key == null || item.get("value") == null) continue;
                String value = item.get("value").toString().strip();
                if (value.isBlank() || value.length() > (key.equals("name") ? 160 : 100)) continue;
                if (key.equals("openingTime")) { try { value = openingTime(value); } catch (RuntimeException ignored) { continue; } }
                candidates.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).putIfAbsent(value, item.get("evidence"));
            }
        }
        Set<String> manual = manualFields(project);
        Map<String,Object> sources = new LinkedHashMap<>();
        Map<String,String> warnings = new LinkedHashMap<>();
        for (String field : FIELDS) {
            LinkedHashMap<String,Object> values = candidates.getOrDefault(field, new LinkedHashMap<>());
            if (values.size() == 1) sources.put(field, values.values().iterator().next());
            if (manual.contains(field)) continue;
            if (values.size() == 1) project.put(field, values.keySet().iterator().next());
            else {
                if (!field.equals("name")) project.put(field, "");
                warnings.put(field, values.isEmpty() ? "未提取到，请补录或核对" : "原文存在多个值，请核对并填写");
            }
        }
        project.put("code", BidService.text(project,"projectNumber"));
        project.put("manualFields", List.copyOf(manual));
        project.put("fieldSources", sources);
        project.put("fieldWarnings", warnings);
        project.put("informationTenderId", tender.id());
    }
}
