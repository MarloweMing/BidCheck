package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Source-backed profile extraction: explicit labels and narrowly scoped source excerpts only. */
public class ProfileExtractor {
    private static final String PROJECT = "项目基本信息";
    private static final String COMPANY = "投标企业基本信息";
    private static final String PEOPLE = "人员信息";
    private static final String PERFORMANCE = "业绩信息";
    private static final String LICENSE = "企业证照";
    private static final String COPYRIGHT = "著作权信息";
    private static final List<String> GROUPS = List.of(PROJECT, COMPANY, PEOPLE, PERFORMANCE, LICENSE, COPYRIGHT);
    private static final Map<String, Field> FIELDS = fields();
    private static final String ALIASES = FIELDS.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).collect(Collectors.joining("|"));
    private static final Pattern PAIR = Pattern.compile("(?<![\\p{L}\\p{N}])(" + ALIASES
            + ")(?:\\s*([（(][^）)\\n]{1,12}[）)]))?\\s*[:：]\\s*");
    private static final Pattern LABEL = Pattern.compile("^(" + ALIASES
            + ")(?:\\s*([（(][^）)\\n]{1,12}[）)]))?\\s*[:：]?$");
    private static final Set<String> TABLE_METADATA = Set.of("序号", "编号", "性别", "年龄", "学历", "备注");
    private static final Set<String> PERFORMANCE_CONTEXT = Set.of("项目名称", "项目编号", "项目金额", "金额", "合同金额", "合同编号", "合同名称",
            "合同日期", "合同签订日期", "签订合同时间", "签订日期", "验收日期", "完成日期", "工期", "合同工期", "服务期限", "采购人", "业主单位",
            "联系人", "业主联系人", "合同甲方联系人", "合同甲方联系人联系电话", "联系方式", "联系电话", "联系手机", "电话", "项目经理", "项目负责人");
    private static final Pattern REQUIREMENT = Pattern.compile("^(应当|必须|须[为有具提填按在]|应[为有具提填按在]|需[要具提填按在]|不得|要求|至少|不少于|不低于|提供|填写|详见|见附件|见下表|见投标|见招标).*");
    private static final int MAX_ITEMS = 600;

    public Map<String, Object> extract(Document document) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        GROUPS.forEach(title -> groups.put(title, new ArrayList<>()));
        List<String> warnings = new ArrayList<>(document.warnings() == null ? List.of() : document.warnings());
        Set<String> seen = new LinkedHashSet<>();
        String section = PROJECT;
        List<PerformanceRecord> records = new ArrayList<>();
        PerformanceRecord currentRecord = null;
        List<Column> headers = List.of();
        int itemCount = 0;
        if (document.pages() != null) {
            for (Page page : document.pages()) {
                String[] lines = (page.text() == null ? "" : page.text()).split("\\R", -1);
                boolean tableDataSeen = false;
                for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                    // Keep empty leading/trailing table cells so columns never shift between fields.
                    String line = lines[lineIndex].contains("\t") ? lines[lineIndex] : lines[lineIndex].strip();
                    if (line.isBlank()) continue;
                    List<Column> rowHeaders = tableHeaders(line);
                    if (!rowHeaders.isEmpty()) {
                        headers = rowHeaders;
                        if (isPerformanceTable(headers)) section = PERFORMANCE;
                        continue;
                    }
                    String heading = headingGroup(line);
                    if (heading != null) {
                        if (!section.equals(heading)) currentRecord = null;
                        section = heading;
                        headers = List.of();
                        continue;
                    }
                    String recordTitle = PERFORMANCE.equals(section) ? performanceTitle(line) : null;
                    if (recordTitle != null && itemCount < MAX_ITEMS) {
                        currentRecord = newRecord(records, recordTitle);
                        currentRecord.headingIdentity = true;
                        Map<String, Object> identity = item("项目名称", recordTitle, evidence(document, page, lineIndex, line, 0.93d));
                        currentRecord.add(identity);
                        groups.get(PERFORMANCE).add(identity);
                        itemCount++;
                        continue;
                    }
                    List<Pair> pairs = pairs(line);
                    boolean tableRow = false;
                    boolean possibleWordRow = lineIndex == 0 && "BLOCK".equals(page.locationType())
                            && !line.contains(":") && !line.contains("：");
                    if (pairs.isEmpty() && !headers.isEmpty() && (line.contains("\t") || possibleWordRow)) {
                        pairs = tablePairs(line, headers);
                        tableRow = !pairs.isEmpty();
                        tableDataSeen = true;
                        // Each data row is a separate record, including rows whose project name is blank.
                        // A Word block may have lost edge tabs during normalization; in that case only
                        // its following labelled fields are safe to use, never shifted column values.
                        if (PERFORMANCE.equals(section)) currentRecord = null;
                    }
                    if (!line.contains("\t") && !(tableDataSeen && (possibleWordRow || isDerivedTableLine(line, headers)))) headers = List.of();
                    boolean extracted = false;
                    for (Pair pair : pairs) {
                        Field field = FIELDS.get(pair.alias());
                        String group = contextGroup(field, pair.alias(), section);
                        String value = cleanValue(pair.value());
                        String label = contextLabel(field, pair.alias(), group) + pair.unit();
                        boolean identity = PERFORMANCE.equals(group) && Set.of("项目名称", "合同名称").contains(field.label());
                        if (!isValue(value)) {
                            if (identity && !tableRow) currentRecord = null;
                            continue;
                        }
                        if (itemCount >= MAX_ITEMS) break;
                        if (PERFORMANCE.equals(group)) {
                            if (currentRecord == null) currentRecord = newRecord(records, identity ? value : null);
                            else if (identity && currentRecord.hasLabel(label)) {
                                if (!(currentRecord.headingIdentity && currentRecord.title.equals(value)))
                                    currentRecord = newRecord(records, value);
                            }
                            if (identity) {
                                if (!currentRecord.named || field.label().equals("项目名称")) currentRecord.title = value;
                                currentRecord.named = true;
                                currentRecord.headingIdentity = false;
                            }
                            Map<String, Object> entry = item(label, value, evidence(document, page, lineIndex, line, 0.96d));
                            if (!currentRecord.add(entry)) continue;
                            groups.get(group).add(entry);
                            itemCount++;
                            extracted = true;
                            continue;
                        }
                        String key = group + "\u0000" + label + "\u0000" + value;
                        if (!seen.add(key)) continue;
                        groups.get(group).add(item(label, value, evidence(document, page, lineIndex, line, 0.96d)));
                        itemCount++;
                        extracted = true;
                    }
                    // Some source documents use prose rather than label/value forms. Keep it verbatim.
                    if (!extracted && pairs.isEmpty() && !line.contains("\t") && excerptMatches(section, line) && itemCount < MAX_ITEMS) {
                        String key = section + "\u0000摘录\u0000" + line;
                        if (PERFORMANCE.equals(section) || seen.add(key)) {
                            Map<String, Object> entry = item("原文摘录", bounded(line, 1500), evidence(document, page, lineIndex, line, 0.85d));
                            if (PERFORMANCE.equals(section)) {
                                if (currentRecord == null) currentRecord = newRecord(records, null);
                                if (!currentRecord.add(entry)) continue;
                            }
                            groups.get(section).add(entry);
                            itemCount++;
                        }
                    }
                    // Word's parser emits both the original table row and derived label/value lines.
                    // Use the original row as evidence and consume only exact parser-generated echoes.
                    if (tableRow) lineIndex = skipTableEchoes(lines, lineIndex, line, headers);
                }
            }
        }
        if (itemCount == 0) warnings.add("未找到可直接摘录的核心信息；空白字段不表示材料不存在，请查看原文确认。");
        if (itemCount >= MAX_ITEMS) warnings.add("核心信息超过 600 条，当前仅展示前 600 条，请结合原文核对。");
        if (records.stream().anyMatch(record -> !record.named)) warnings.add("部分业绩未识别到项目名称，已单独保留为待确认记录，请对照原文核对归属。");
        List<Map<String, Object>> resultGroups = new ArrayList<>();
        groups.forEach((title, items) -> {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("title", title);
            group.put("items", List.copyOf(items));
            if (PERFORMANCE.equals(title)) group.put("records", records.stream().filter(record -> !record.items.isEmpty())
                    .map(record -> Map.<String, Object>of("id", record.id, "title", record.title, "items", List.copyOf(record.items))).toList());
            resultGroups.add(group);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", List.copyOf(resultGroups));
        result.put("warnings", List.copyOf(new LinkedHashSet<>(warnings)));
        return result;
    }

    private static List<Pair> pairs(String line) {
        String[] columns = line.split("\\t", -1);
        if (columns.length > 1 && columns.length % 2 == 0) {
            boolean keyValueRow = true;
            for (int i = 0; i < columns.length; i += 2) keyValueRow &= column(columns[i]) != null;
            if (keyValueRow) {
                List<Pair> result = new ArrayList<>();
                for (int i = 0; i < columns.length; i += 2) {
                    Column label = column(columns[i]);
                    result.add(new Pair(label.alias(), label.unit(), columns[i + 1]));
                }
                return result;
            }
        }
        record Start(String alias, String unit, int start, int valueStart) { }
        List<Start> starts = new ArrayList<>();
        Matcher matcher = PAIR.matcher(line);
        while (matcher.find()) starts.add(new Start(matcher.group(1), matcher.group(2) == null ? "" : matcher.group(2), matcher.start(), matcher.end()));
        List<Pair> result = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            Start start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1).start() : line.length();
            result.add(new Pair(start.alias(), start.unit(), line.substring(start.valueStart(), end)));
        }
        return result;
    }

    private static List<Column> tableHeaders(String line) {
        String[] columns = line.split("\\t", -1);
        if (columns.length < 2) return List.of();
        List<Column> result = new ArrayList<>();
        int recognized = 0;
        for (String value : columns) {
            Column label = column(value);
            if (label != null) { recognized++; result.add(label); }
            else if (value.isBlank() || TABLE_METADATA.contains(value.strip())) result.add(new Column("", "", value.strip()));
            else return List.of();
        }
        return recognized >= 2 ? List.copyOf(result) : List.of();
    }

    private static Column column(String text) {
        Matcher matcher = LABEL.matcher(text.strip());
        return matcher.matches() ? new Column(matcher.group(1), matcher.group(2) == null ? "" : matcher.group(2), text.strip()) : null;
    }

    private static boolean isPerformanceTable(List<Column> headers) {
        return headers.stream().anyMatch(header -> Set.of("项目名称", "合同名称", "业绩名称", "业绩项目名称").contains(header.alias()))
                && headers.stream().anyMatch(header -> !header.alias().isEmpty() && PERFORMANCE.equals(FIELDS.get(header.alias()).group()));
    }

    private static List<Pair> tablePairs(String line, List<Column> headers) {
        String[] columns = line.split("\\t", -1);
        if (columns.length != headers.size()) return List.of();
        List<Pair> result = new ArrayList<>();
        for (int i = 0; i < columns.length; i++) {
            Column header = headers.get(i);
            if (!header.alias().isEmpty()) result.add(new Pair(header.alias(), header.unit(), columns[i]));
        }
        return result;
    }

    private static int skipTableEchoes(String[] lines, int lineIndex, String line, List<Column> headers) {
        String[] values = line.split("\\t", -1);
        for (int i = 0; i < headers.size() && lineIndex + 1 < lines.length; i++) {
            if (headers.get(i).source().isBlank() || values[i].isBlank()) continue;
            String expected = headers.get(i).source() + "：" + values[i];
            if (!lines[lineIndex + 1].strip().equals(expected.strip())) break;
            lineIndex++;
        }
        return lineIndex;
    }

    private static boolean isDerivedTableLine(String line, List<Column> headers) {
        return headers.stream().anyMatch(header -> !header.source().isBlank() && line.startsWith(header.source() + "："));
    }

    private static String contextGroup(Field field, String alias, String section) {
        if (PERFORMANCE.equals(section) && PERFORMANCE_CONTEXT.contains(alias)) return PERFORMANCE;
        if (COPYRIGHT.equals(section) && Set.of("证书名称", "证书编号", "登记号", "发证日期", "发证机关").contains(alias)) return COPYRIGHT;
        if (PEOPLE.equals(section) && Set.of("证书名称", "证书编号", "资格证书", "资质证书", "有效期", "发证日期", "有效期至").contains(alias)) return PEOPLE;
        return field.group();
    }

    private static String contextLabel(Field field, String alias, String group) {
        if (PERFORMANCE.equals(group) && Set.of("项目经理", "项目负责人").contains(alias)) return alias;
        return field.label();
    }

    private static String performanceTitle(String line) {
        if (line.length() > 180 || line.contains("\t") || line.contains(":") || line.contains("：")) return null;
        String title = line.replaceFirst("^[（(]?[0-9一二三四五六七八九十]+[）)、.．]\\s*", "").strip();
        if (title.equals(line) || !isValue(title) || !title.matches(".*(项目|工程|服务|采购|系统|平台|合同).*")) return null;
        if (title.matches(".*[；;。！？].*")) return null;
        return title;
    }

    private static PerformanceRecord newRecord(List<PerformanceRecord> records, String title) {
        PerformanceRecord record = new PerformanceRecord(records.size() + 1, title);
        records.add(record);
        return record;
    }

    private static String headingGroup(String line) {
        if (line.length() > 70 || line.contains("\t") || line.contains("：") || line.contains(":")) return null;
        String title = line.replaceFirst("^[第\\s一二三四五六七八九十百0-9、.．（）()章节部分-]+", "").strip();
        if (title.matches(".*(人员一览表|人员基本情况|人员信息|人员简历|人员配备|项目团队|项目负责人简历|项目经理简历|拟派人员|技术人员|主要人员).*")) return PEOPLE;
        if (title.matches(".*(类似项目业绩|同类项目业绩|项目业绩|业绩一览表|业绩表|业绩信息|业绩情况|业绩实力|合同业绩|主要业绩|历史业绩|业绩证明).*")) return PERFORMANCE;
        if (title.matches(".*(著作权|软件登记证书).*")) return COPYRIGHT;
        if (title.matches(".*(企业证照|企业资质|企业证书|资质证明|证照信息|资格证明材料|资质证书一览表).*")) return LICENSE;
        if (title.matches(".*(企业基本信息|企业基本情况|投标人基本情况|投标人信息表|供应商基本情况|公司基本信息|公司简介|工商登记信息).*")) return COMPANY;
        if (title.matches(".*(项目基本信息|项目概况|招标项目概况|项目简介|投标函).*")) return PROJECT;
        return null;
    }

    private static boolean excerptMatches(String section, String line) {
        if (line.length() < 8 || line.length() > 3000 || line.matches(".*(应当|必须|应具备|需提供|不得|至少|不少于|不低于|要求提供).*")) return false;
        return switch (section) {
            case PEOPLE -> line.matches(".*(担任|持有|姓名|职务|执业证|资格证|证书编号).*" );
            case PERFORMANCE -> line.matches(".*(合同|项目|金额|验收).*" ) && line.matches(".*[0-9].*");
            case LICENSE -> line.matches(".*(营业执照|资质证书|许可证|认证证书|资格证书).*" ) && line.matches(".*[0-9].*");
            case COPYRIGHT -> line.matches(".*(著作权|软件登记|软著登).*" ) && line.matches(".*[0-9].*");
            default -> false;
        };
    }

    private static String cleanValue(String value) {
        return value.strip().replaceAll("^[|;；]+|[|;；]+$", "").strip();
    }

    private static boolean isValue(String value) {
        if (value.isBlank() || value.length() > 3000 || REQUIREMENT.matcher(value).matches()) return false;
        if (value.matches("^[\\s_—\\-./\\\\□☐（）()【】\\[\\]]+$") || Set.of("待填写", "待补充", "未填写", "空", "暂无", "不详", "无", "不适用").contains(value)) return false;
        return true;
    }

    private static Evidence evidence(Document document, Page page, int lineIndex, String line, double confidence) {
        return new Evidence(document.id(), document.name(), page.number(), page.locationType(), bounded(line, 1500),
                "p" + page.number() + "-l" + (lineIndex + 1),
                page.parser() != null && page.parser().contains("OCR") ? 0d : confidence);
    }

    private static Map<String, Object> item(String label, String value, Evidence evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("value", value);
        result.put("evidence", evidence);
        return result;
    }

    private static String bounded(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }

    private static Map<String, Field> fields() {
        Map<String, Field> fields = new LinkedHashMap<>();
        add(fields, PROJECT, "项目名称", "招标项目名称", "采购项目名称", "项目名称");
        add(fields, PROJECT, "项目编号", "招标项目编号", "采购项目编号", "招标编号", "采购编号", "项目编号");
        add(fields, PROJECT, "开标时间", "开标日期", "开标时间");
        add(fields, PROJECT, "投标截止时间", "投标截止时间", "投标截止日期", "响应截止时间");
        add(fields, PROJECT, "预算金额", "项目预算", "预算金额", "采购预算", "招标预算");
        add(fields, PROJECT, "最高限价", "最高限价", "最高投标限价", "控制价");
        add(fields, PROJECT, "工期", "计划工期", "合同工期", "工期");
        add(fields, PROJECT, "服务期限", "服务期限", "服务期", "履约期限");
        add(fields, PROJECT, "采购人", "采购人", "招标人");
        add(fields, COMPANY, "企业名称", "投标人名称", "投标企业名称", "投标单位名称", "投标单位", "供应商名称", "公司名称", "企业名称", "单位名称");
        add(fields, COMPANY, "企业地址", "投标人地址", "注册地址", "注册住所", "企业地址", "公司地址", "办公地址", "通讯地址", "住所");
        add(fields, COMPANY, "注册资本", "注册资本", "注册资金", "实缴资本");
        add(fields, COMPANY, "成立日期", "成立日期", "成立时间", "设立日期");
        add(fields, COMPANY, "经营范围", "经营范围", "业务范围");
        add(fields, COMPANY, "邮政编码", "邮政编码", "邮编");
        add(fields, COMPANY, "企业网址", "企业网址", "公司网址", "网址");
        add(fields, COMPANY, "企业类型", "企业类型", "公司类型", "经济类型", "企业性质");
        add(fields, COMPANY, "统一社会信用代码", "统一社会信用代码", "社会信用代码", "信用代码");
        add(fields, COMPANY, "法定代表人", "法定代表人", "法人代表");
        add(fields, COMPANY, "开户银行", "开户银行", "开户行", "基本账户开户银行");
        add(fields, COMPANY, "银行账号", "银行账号", "银行帐号", "开户账号", "账号", "帐号", "基本存款账户账号");
        add(fields, COMPANY, "股权情况", "股权结构", "股权信息", "股权情况", "股东信息", "主要股东", "控股股东");
        add(fields, COMPANY, "联系方式", "联系方式", "联系电话", "联系手机", "电话", "合同甲方联系人联系电话");
        add(fields, COMPANY, "联系人", "联系人", "业主联系人", "合同甲方联系人");
        add(fields, PEOPLE, "姓名", "人员姓名", "姓名");
        add(fields, PEOPLE, "职务", "担任职务", "岗位", "职务");
        add(fields, PEOPLE, "项目负责人", "项目负责人", "项目经理", "技术负责人");
        add(fields, PEOPLE, "职称", "专业技术职称", "职称");
        add(fields, PEOPLE, "专业", "所学专业", "专业");
        add(fields, PEOPLE, "人员证书", "执业资格", "职业资格", "执业证书", "资格证书", "人员证书");
        add(fields, PERFORMANCE, "合同名称", "合同名称");
        add(fields, PERFORMANCE, "项目名称", "业绩项目名称", "业绩名称");
        add(fields, PERFORMANCE, "合同编号", "合同编号");
        add(fields, PERFORMANCE, "合同金额", "合同金额", "项目金额", "金额");
        add(fields, PERFORMANCE, "开工时间", "开工时间", "开工日期");
        add(fields, PERFORMANCE, "合同日期", "合同日期", "合同签订日期", "签订合同时间", "签订日期");
        add(fields, PERFORMANCE, "验收日期", "验收日期", "完成日期");
        add(fields, PERFORMANCE, "业主单位", "业主单位", "建设单位");
        add(fields, LICENSE, "证书名称", "证书名称", "资质证书", "企业证书");
        add(fields, LICENSE, "证书编号", "证书编号", "资质证书编号", "许可证编号", "营业执照编号", "注册号");
        add(fields, LICENSE, "证书等级", "资质等级", "证书等级");
        add(fields, LICENSE, "发证日期", "发证日期", "颁发日期");
        add(fields, LICENSE, "有效期", "有效期", "有效期至", "证书有效期", "证书到期日期");
        add(fields, LICENSE, "发证机关", "发证机关", "颁发机构", "登记机关");
        add(fields, COPYRIGHT, "软件名称", "软件名称", "软件全称", "作品名称");
        add(fields, COPYRIGHT, "软件版本", "软件版本", "版本号");
        add(fields, COPYRIGHT, "著作权人", "著作权人", "软件著作权人");
        add(fields, COPYRIGHT, "登记号", "著作权登记号", "软件著作权登记号", "登记号");
        add(fields, COPYRIGHT, "开发完成日期", "开发完成日期");
        add(fields, COPYRIGHT, "首次发表日期", "首次发表日期");
        add(fields, COPYRIGHT, "权利取得方式", "权利取得方式");
        add(fields, COPYRIGHT, "权利范围", "权利范围");
        return Map.copyOf(fields);
    }

    private static void add(Map<String, Field> fields, String group, String label, String... aliases) {
        for (String alias : aliases) fields.put(alias, new Field(group, label));
    }

    private record Field(String group, String label) { }
    private record Pair(String alias, String unit, String value) { }
    private record Column(String alias, String unit, String source) { }

    private static final class PerformanceRecord {
        private final String id;
        private String title;
        private boolean named;
        private boolean headingIdentity;
        private final List<Map<String, Object>> items = new ArrayList<>();
        private final Set<String> seen = new LinkedHashSet<>();

        private PerformanceRecord(int number, String title) {
            this.id = "performance-" + number;
            this.title = title == null ? "待确认业绩 " + number : title;
            this.named = title != null;
        }

        private boolean hasLabel(String label) { return items.stream().anyMatch(item -> item.get("label").equals(label)); }

        private boolean add(Map<String, Object> entry) {
            if (!seen.add(entry.get("label") + "\u0000" + entry.get("value"))) return false;
            items.add(entry);
            return true;
        }
    }
}
