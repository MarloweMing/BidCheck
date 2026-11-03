package cn.bidcheck.document;
import cn.bidcheck.compat.HexFormat;

import cn.bidcheck.domain.Domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Source-local, deterministic transcription of explicit personnel and credential fields.
 * This class does not decide whether two records describe the same person. */
public final class PersonCredentialFacts {
    public static final String VERSION = "person-credential-source-2-layout-text";
    /** Downstream filtering only; never part of the OCR preparation/cache identity. */
    public static final String CONSISTENCY_FILTER_VERSION = "person-credential-consistency-filter-1";
    private static final int MAX_PAGE = 100_000, MAX_RECORD = 10_000, MAX_RECORDS = 300;
    private static final Set<String> NUMBERS = cn.bidcheck.compat.Java8Api.setOf("idNumber", "drivingLicenseNumber", "drivingFileNumber", "passportNumber", "certificateNumber");
    private static final LinkedHashMap<String,String> LABELS = new LinkedHashMap<>();
    private static final Pattern LABEL, TITLE;
    private static final Pattern TABLE = Pattern.compile("(?is)<table\\b[^>]*>.*?</table\\s*>");
    private static final Pattern ROW = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>");
    private static final Pattern CELL = Pattern.compile("(?is)<(td|th)\\b([^>]*)>(.*?)</\\1\\s*>");
    static {
        labels("personName", "姓名", "持证人姓名", "持证人", "人员姓名", "Name", "Full Name");
        labels("idNumber", "公民身份号码", "公民身份证号码", "身份证号码", "身份证号", "身份号码", "Identity Card Number", "ID Number", "ID No.", "ID No");
        labels("drivingLicenseNumber", "驾驶证号码", "驾驶证编号", "驾驶证号", "Driving License Number", "Driving Licence Number", "Driver License Number", "License No.", "Licence No.");
        labels("drivingFileNumber", "驾驶证档案编号");
        labels("archiveNumber", "档案编号", "档案号码", "档案号", "File Number", "File No.");
        labels("passportNumber", "护照号码", "护照编号", "护照号", "Passport Number", "Passport No.");
        labels("certificateNumber", "资格证书编号", "资格证编号", "证书编号", "证书号码", "证书号", "Certificate Number", "Certificate No.");
        labels("certificateName", "资格证书名称", "证书名称", "证书类别", "证件类型", "证件类别", "Certificate Type", "Credential Type");
        labels("genericNumber", "证件号码", "证件编号", "证件号", "证号", "编号");
        labels("gender", "性别", "Gender", "Sex");
        labels("birthDate", "出生日期", "出生年月", "出生", "Date of Birth", "DOB");
        labels("vehicleClass", "准驾车型", "准驾车类");
        labels("classification", "Vehicle Class", "Class");
        labels("validFrom", "有效起始日期", "有效期起", "有效起始", "有效自", "Valid From");
        labels("validUntil", "有效截止日期", "有效期截止日期", "有效期截止", "有效期至", "有效期止", "有效截至", "有效至", "Valid Until", "Expiry Date", "Date of Expiry");
        labels("validPeriod", "有效期限", "有效期", "Validity");
        labels("address", "住址", "居住地址", "Address");
        labels("issuingAuthority", "签发机关", "发证机关", "颁发机构", "Issuing Authority");
        labels("role", "岗位名称", "人员岗位", "岗位", "职务", "Role", "Position");
        labels("professionalTitle", "专业技术职称", "技术职称", "职称", "Professional Title");
        labels("age", "年龄"); labels("education", "学历", "文化程度");
        labels("professionalMajor", "所学专业", "专业"); labels("graduationSchool", "毕业院校", "毕业学校");
        labels("ethnicGroup", "民族"); labels("nationality", "国籍", "Nationality");
        labels("firstIssueDate", "初次领证日期", "初次领证", "First Issue Date");
        labels("certificateLevel", "证书等级", "资格等级");
        labels("phone", "联系电话", "手机号", "手机号码", "Phone");
        labels("employer", "工作单位", "受聘单位", "聘用单位", "Employer");
        labels("socialSecurityNumber", "社会保障号码", "社会保障号");
        String alternatives = LABELS.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .map(PersonCredentialFacts::labelPattern).reduce((a,b) -> a + "|" + b).orElseThrow(java.util.NoSuchElementException::new);
        LABEL = Pattern.compile("(?iu)(?<![A-Za-z])(" + alternatives + ")(?![A-Za-z])(?:\\*\\*|__)?(?:[ \\t]*[:：][ \\t]*|[ \\t]+|(?=[\\r\\n])|(?=[\\p{IsHan}0-9]))");
        TITLE = Pattern.compile("(?imu)^[ \\t]*(?:#{1,6}[ \\t]+)?(?:\\*\\*|__)?(中华人民共和国机动车驾驶证|机动车驾驶证|驾驶证|中华人民共和国居民身份证|居民身份证|身份证|中华人民共和国护照|护照|Driving Licen[cs]e|Driver'?s Licen[cs]e|(?:National )?Identity Card|Passport|[\\p{IsHan}]{0,18}(?:资格证书|执业证书|注册证书|职称证书|合格证书)|人员(?:名单|一览表|情况表|配备表))(?!号码|编号|号|名称|类别|[ \\t]+(?:Number|No\\.?))");
    }
    private PersonCredentialFacts() { }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Occurrence {
        private final String id;
        private final String recordKey;
        private final String personName;
        private final String credentialType;
        private final String field;
        private final String rawValue;
        private final String value;
        private final Evidence evidence;
        private final Map<String,String> context;
        public String id() { return id; }
        public String recordKey() { return recordKey; }
        public String personName() { return personName; }
        public String credentialType() { return credentialType; }
        public String field() { return field; }
        public String rawValue() { return rawValue; }
        public String value() { return value; }
        public Evidence evidence() { return evidence; }
        public Map<String,String> context() { return context; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Occurrence that = (Occurrence) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.recordKey, that.recordKey) && java.util.Objects.equals(this.personName, that.personName) && java.util.Objects.equals(this.credentialType, that.credentialType) && java.util.Objects.equals(this.field, that.field) && java.util.Objects.equals(this.rawValue, that.rawValue) && java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.evidence, that.evidence) && java.util.Objects.equals(this.context, that.context); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.recordKey); hash = 31 * hash + java.util.Objects.hashCode(this.personName); hash = 31 * hash + java.util.Objects.hashCode(this.credentialType); hash = 31 * hash + java.util.Objects.hashCode(this.field); hash = 31 * hash + java.util.Objects.hashCode(this.rawValue); hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.evidence); hash = 31 * hash + java.util.Objects.hashCode(this.context); return hash; }
        @Override public String toString() { return "Occurrence[" + "id=" + id + ", recordKey=" + recordKey + ", personName=" + personName + ", credentialType=" + credentialType + ", field=" + field + ", rawValue=" + rawValue + ", value=" + value + ", evidence=" + evidence + ", context=" + context + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Occurrence(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("recordKey") String recordKey, @com.fasterxml.jackson.annotation.JsonProperty("personName") String personName, @com.fasterxml.jackson.annotation.JsonProperty("credentialType") String credentialType, @com.fasterxml.jackson.annotation.JsonProperty("field") String field, @com.fasterxml.jackson.annotation.JsonProperty("rawValue") String rawValue, @com.fasterxml.jackson.annotation.JsonProperty("value") String value, @com.fasterxml.jackson.annotation.JsonProperty("evidence") Evidence evidence, @com.fasterxml.jackson.annotation.JsonProperty("context") Map<String,String> context) { context = cn.bidcheck.compat.Java8Api.mapCopyOf(context);
            this.id = id; this.recordKey = recordKey; this.personName = personName; this.credentialType = credentialType; this.field = field; this.rawValue = rawValue; this.value = value; this.evidence = evidence; this.context = context;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Result {
        private final int page;
        private final String locationType;
        private final boolean candidate;
        private final boolean complete;
        private final List<Occurrence> occurrences;
        private final List<Map<String,Object>> issues;
        private final List<Fact> facts;
        public int page() { return page; }
        public String locationType() { return locationType; }
        public boolean candidate() { return candidate; }
        public boolean complete() { return complete; }
        public List<Occurrence> occurrences() { return occurrences; }
        public List<Map<String,Object>> issues() { return issues; }
        public List<Fact> facts() { return facts; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Result that = (Result) other; return java.util.Objects.equals(this.page, that.page) && java.util.Objects.equals(this.locationType, that.locationType) && java.util.Objects.equals(this.candidate, that.candidate) && java.util.Objects.equals(this.complete, that.complete) && java.util.Objects.equals(this.occurrences, that.occurrences) && java.util.Objects.equals(this.issues, that.issues) && java.util.Objects.equals(this.facts, that.facts); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.page); hash = 31 * hash + java.util.Objects.hashCode(this.locationType); hash = 31 * hash + java.util.Objects.hashCode(this.candidate); hash = 31 * hash + java.util.Objects.hashCode(this.complete); hash = 31 * hash + java.util.Objects.hashCode(this.occurrences); hash = 31 * hash + java.util.Objects.hashCode(this.issues); hash = 31 * hash + java.util.Objects.hashCode(this.facts); return hash; }
        @Override public String toString() { return "Result[" + "page=" + page + ", locationType=" + locationType + ", candidate=" + candidate + ", complete=" + complete + ", occurrences=" + occurrences + ", issues=" + issues + ", facts=" + facts + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Result(@com.fasterxml.jackson.annotation.JsonProperty("page") int page, @com.fasterxml.jackson.annotation.JsonProperty("locationType") String locationType, @com.fasterxml.jackson.annotation.JsonProperty("candidate") boolean candidate, @com.fasterxml.jackson.annotation.JsonProperty("complete") boolean complete, @com.fasterxml.jackson.annotation.JsonProperty("occurrences") List<Occurrence> occurrences, @com.fasterxml.jackson.annotation.JsonProperty("issues") List<Map<String,Object>> issues, @com.fasterxml.jackson.annotation.JsonProperty("facts") List<Fact> facts) { occurrences = cn.bidcheck.compat.Java8Api.listCopyOf(occurrences); issues = cn.bidcheck.compat.Java8Api.listCopyOf(issues); facts = cn.bidcheck.compat.Java8Api.listCopyOf(facts);
            this.page = page; this.locationType = locationType; this.candidate = candidate; this.complete = complete; this.occurrences = occurrences; this.issues = issues; this.facts = facts;
        }
    }

    public static Result analyze(Document document, Page page) {
        return analyze(document,page,false);
    }

    /** Consistency-only interpretation; the OCR extraction contract and VERSION remain unchanged. */
    public static Result analyzeForConsistency(Document document, Page page) {
        return analyze(document,page,true);
    }

    private static Result analyze(Document document, Page page, boolean tableRelations) {
        String text = page == null ? "" : Objects.toString(page.text(), "");
        boolean candidate = candidate(text) || tableRelations && relationalCandidate(text);
        if (page == null) return new Result(0, "PAGE", false, false, cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf());
        List<Map<String,Object>> issues = new ArrayList<>();
        if (!candidate) return result(page, false, true, cn.bidcheck.compat.Java8Api.listOf(), issues);
        if (document == null || document.pages() == null || document.pages().stream().noneMatch(p -> p.number() == page.number()
                && Objects.equals(p.locationType(), page.locationType()) && Objects.equals(p.text(), page.text()))) {
            issue(issues, page, "SOURCE_NOT_BOUND", "页面未绑定当前文档版本，未提取人员证件事实。");
            return result(page, true, false, cn.bidcheck.compat.Java8Api.listOf(), issues);
        }
        if (text.length() > MAX_PAGE) {
            issue(issues, page, "PERSON_PAGE_LIMIT", "人员材料超过结构解析长度限制，需进一步识别。");
            return result(page, true, false, cn.bidcheck.compat.Java8Api.listOf(), issues);
        }
        if (Objects.toString(page.warning(), "").matches("(?s).*(?:\\[OCR未完成\\]|\\[OCR_DEFERRED\\]).*"))
            issue(issues, page, "SOURCE_INCOMPLETE", "人员材料页面识别未完成，事实覆盖未完成。");
        List<RawRecord> records = new ArrayList<>(); char[] prose = text.toCharArray();
        TableRelationIndex relationIndex = tableRelations ? TableRelationIndex.analyze(document,page) : null;
        Matcher tables = TABLE.matcher(text); int tableCount = 0;
        while (tables.find()) {
            tableCount++;
            if (tableRelations) parseRelatedHtml(text, tables.start(), tables.end(), relationIndex, records, issues, page);
            else parseHtml(text, tables.start(), tables.end(), records, issues, page);
            Arrays.fill(prose, tables.start(), tables.end(), ' ');
        }
        if (count(Pattern.compile("(?i)<table\\b"), text) != tableCount) {
            issue(issues, page, "PERSON_TABLE_INCOMPLETE", "人员表格未闭合或嵌套，未猜测行列归属。");
            // Do not interpret detached table cells as a plain-text person record.
            int tag = new String(prose).toLowerCase(Locale.ROOT).indexOf("<table");
            if (tag >= 0) Arrays.fill(prose, tag, prose.length, ' ');
        }
        parseMarkdown(text, prose, records, issues, page);
        parsePlain(text, new String(prose), records, issues, page);
        if (records.size() > MAX_RECORDS) {
            issue(issues, page, "PERSON_RECORD_LIMIT", "人员记录数量超过单页处理限制，未完成全部提取。");
            records = records.subList(0, MAX_RECORDS);
        }
        List<Occurrence> occurrences = new ArrayList<>();
        for (RawRecord record : records) {
            RawRecord selected = tableRelations ? filterConsistencyNoise(document,page,record,issues) : record;
            if (!selected.fields.isEmpty()) publish(document, page, selected, occurrences, issues);
        }
        if (occurrences.isEmpty()) issue(issues, page, "PERSON_FIELDS_UNRESOLVED", "发现人员或证件材料，但尚未提取到可定位的明确字段。");
        return result(page, true, issues.isEmpty(), occurrences, issues);
    }

    private static Result result(Page page, boolean candidate, boolean complete, List<Occurrence> occurrences, List<Map<String,Object>> issues) {
        List<Fact> facts = cn.bidcheck.compat.Java8Api.toList(occurrences.stream().map(o -> new Fact("PERSON_CREDENTIAL", o.recordKey(), o.field(), o.rawValue(), o.value(), o.evidence())));
        return new Result(page.number(), page.locationType(), candidate, complete, occurrences, issues, facts);
    }
    private static boolean candidate(String text) {
        List<String> lines=cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(text).map(cn.bidcheck.compat.Java8Api::strip).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)&&!s.matches("[0-9]{1,5}|[目录\\s]+")));
        if(lines.size()>=3&&lines.stream().allMatch(s->s.matches(".*(?:\\.{4,}|…{2,}|·{4,})[ \\t]*[0-9]{1,5}"))) return false;
        return TITLE.matcher(text).find() || Pattern.compile("(?iu)身份证|公民身份号码|驾驶证|护照|资格证书|执业证书|职称证书|人员名单|人员一览表|人员配备表|Driv(?:ing|er'?s?) Licen[cs]e|Identity Card|Passport|\\bID (?:Number|No)|\\bCertificate (?:Number|Type)").matcher(text).find()
                || Pattern.compile("(?m)(?:^|[|>\\t *])姓[ \\t\\u3000]*名(?:\\*\\*)?[ \\t]*[:：|<\\t]").matcher(text).find();
    }

    private static void parsePlain(String original, String text, List<RawRecord> out, List<Map<String,Object>> issues, Page page) {
        Pattern unidentified = Pattern.compile("(?m)(?:^|[;；])[ \\t]*([\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9 _.-]{0,30})[ \\t]*[:：]");
        Matcher unknown = unidentified.matcher(text);
        while (unknown.find()) if (!knownLabel(unknown.group(1)))
            issue(issues,page,"PERSON_FIELD_LABEL_UNSUPPORTED","人员材料含尚未识别的字段标签，明确字段已保留，其余字段覆盖待补充。");
        List<Token> tokens = new ArrayList<>(); Matcher matcher = LABEL.matcher(text);
        while (matcher.find()) tokens.add(new Token(matcher.start(), matcher.end(), field(matcher.group(1)), matcher.group(1)));
        List<Title> titles = new ArrayList<>(); Matcher tm = TITLE.matcher(text);
        while (tm.find()) titles.add(new Title(tm.start(), tm.end(), tm.group(1), type(tm.group(1))));
        List<Integer> breaks = new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(0)); for (Title title : titles) if (title.start > 0) breaks.add(title.start);
        breaks.add(text.length()); breaks = cn.bidcheck.compat.Java8Api.toList(breaks.stream().distinct().sorted());
        for (int b = 0; b + 1 < breaks.size(); b++) {
            int start = breaks.get(b), end = breaks.get(b + 1);
            List<Token> part = cn.bidcheck.compat.Java8Api.toList(tokens.stream().filter(t -> t.start >= start && t.start < end));
            if (part.isEmpty()) continue;
            Title title = titles.stream().filter(t -> t.start == start).findFirst().orElse(null);
            String kind = title == null ? infer(cn.bidcheck.compat.Java8Api.toList(part.stream().map(Token::field))) : title.type;
            List<RawField> pending = new ArrayList<>(); int recordStart = title == null ? part.get(0).start : title.start;
            boolean hasName = false;
            for (int n = 0; n < part.size(); n++) {
                Token token = part.get(n);
                if (token.field.equals("personName") && hasName) {
                    addTitle(original,title,kind,recordStart,pending);
                    out.add(new RawRecord(recordStart, token.start, kind, "TEXT", new ArrayList<>(pending)));
                    pending.clear(); recordStart = token.start; hasName = false;
                }
                int valueEnd = n + 1 < part.size() ? part.get(n + 1).start : end;
                int firstNewline = text.indexOf('\n', token.end);
                int valueStart = token.end;
                while (valueStart < valueEnd && Character.isWhitespace(text.charAt(valueStart))) valueStart++;
                // A value may be on the immediately following line; free prose after that line is never consumed.
                firstNewline = text.indexOf('\n', valueStart);
                if (firstNewline >= 0 && firstNewline < valueEnd) valueEnd = firstNewline;
                Matcher boundary = unidentified.matcher(text.substring(valueStart,valueEnd));
                while (boundary.find()) if (boundary.start() > 0 || text.charAt(valueStart) == ';' || text.charAt(valueStart) == '；') {
                    valueEnd = valueStart + boundary.start(); break;
                }
                while (valueEnd > valueStart && (Character.isWhitespace(text.charAt(valueEnd-1)) || "；;，,".indexOf(text.charAt(valueEnd-1)) >= 0)) valueEnd--;
                if (valueStart >= valueEnd) {
                    if(n+1<part.size()&&part.get(n+1).field.equals(token.field)
                            &&!text.substring(token.start,part.get(n+1).start).contains("\n"))continue;
                    issue(issues, page, "PERSON_FIELD_EMPTY", "人员或证件字段标签存在，但值缺失，未补写。"); continue;
                }
                // Presentation wrappers are not part of the field. Trim only a complete pair while
                // retaining character offsets into the unchanged original transcription.
                int[] unwrapped=unwrap(text,valueStart,valueEnd);valueStart=unwrapped[0];valueEnd=unwrapped[1];
                if(valueStart>=valueEnd){issue(issues,page,"PERSON_FIELD_EMPTY","人员字段只有排版标记，未补写字段值。");continue;}
                String raw = original.substring(valueStart, valueEnd);
                if (raw.length() > 300 || raw.contains("<") || raw.contains("|") || raw.contains("\uFFFD")) {
                    issue(issues, page, "PERSON_FIELD_UNRESOLVED", "人员字段格式无法可靠解析，未猜测字段值。"); continue;
                }
                pending.add(new RawField(token.field, raw, visible(raw), valueStart, valueEnd));
                if (token.field.equals("personName")) hasName = true;
            }
            addTitle(original,title,kind,recordStart,pending);
            if (!pending.isEmpty()) out.add(new RawRecord(recordStart, end, kind, "TEXT", pending));
        }
    }
    private static void addTitle(String original, Title title, String kind, int recordStart, List<RawField> fields) {
        if (title != null && title.start == recordStart && kind.equals("PERSON_CERTIFICATE")
                && fields.stream().noneMatch(f -> f.field.equals("certificateName")))
            fields.add(new RawField("certificateName", original.substring(title.start,title.end), title.raw, title.start, title.end));
    }

    private static void parseHtml(String original, int start, int end, List<RawRecord> out, List<Map<String,Object>> issues, Page page) {
        String html = original.substring(start, end);
        if (!candidate(html)) return;
        if (count(Pattern.compile("(?i)<table\\b"), html) != 1 || Pattern.compile("(?i)\\b(?:rowspan|colspan)\\s*=").matcher(html).find()) {
            issue(issues, page, "PERSON_TABLE_SPAN_UNSUPPORTED", "人员表格存在合并单元格或嵌套结构，需补充结构识别，未猜测人员归属。"); return;
        }
        List<TableRow> rows = new ArrayList<>(); Matcher row = ROW.matcher(html); int previous = html.indexOf('>') + 1;
        while (row.find()) {
            if (!cn.bidcheck.compat.Java8Api.isBlank(html.substring(previous, row.start()).replaceAll("(?is)</?(?:thead|tbody|tfoot)\\b[^>]*>", ""))) {
                issue(issues, page, "PERSON_TABLE_INCOMPLETE", "人员表格含无法定位的行或内容，未猜测行列。"); return;
            }
            List<RawField> cells = new ArrayList<>(); Matcher cell = CELL.matcher(row.group(1)); int at = 0;
            while (cell.find()) {
                if (!cn.bidcheck.compat.Java8Api.isBlank(row.group(1).substring(at, cell.start())) || cell.group(3).contains("<") && !cell.group(3).matches("(?is)(?:[^<]|<br\\s*/?>)*")) {
                    issue(issues, page, "PERSON_TABLE_CELL_UNSUPPORTED", "人员表格单元格格式无法安全解析，需补充识别。"); return;
                }
                int cs = start + row.start(1) + cell.start(3), ce = start + row.start(1) + cell.end(3);
                cells.add(new RawField("", original.substring(cs, ce), visible(cell.group(3)), cs, ce)); at = cell.end();
            }
            if (!cn.bidcheck.compat.Java8Api.isBlank(row.group(1).substring(at)) || cells.isEmpty()) {
                issue(issues, page, "PERSON_TABLE_INCOMPLETE", "人员表格单元格未闭合，未猜测字段归属。"); return;
            }
            rows.add(new TableRow(start + row.start(), start + row.end(), cells)); previous = row.end();
        }
        String remainder = html.substring(previous).replaceAll("(?is)</?(?:thead|tbody|tfoot)\\b[^>]*>|</table\\s*>", "");
        if (!cn.bidcheck.compat.Java8Api.isBlank(remainder)) { issue(issues, page, "PERSON_TABLE_INCOMPLETE", "人员表格末尾不完整，未猜测字段归属。"); return; }
        parseRows(rows, "HTML", out, issues, page);
    }

    private static void parseRelatedHtml(String original,int start,int end,TableRelationIndex index,List<RawRecord> out,List<Map<String,Object>> issues,Page page) {
        if(!candidate(original.substring(start,end))&&!relationalCandidate(original.substring(start,end)))return;
        TableRelationIndex.Table table=index.tables().stream().filter(t->t.start()==start&&t.end()==end).findFirst().orElse(null);
        if(table==null){issue(issues,page,"PERSON_TABLE_GRID","人员表格的合并单元格、闭合或行列结构未通过验证，未猜测人员归属。");return;}
        if(table.structure().equals("KEY_VALUE_PAIRS")){
            parseRelatedKeyValue(table,out,issues,page);return;
        }
        if(table.headerRows()==0){
            // Preserve explicit two-column key/value credential tables. Spanned tables without a
            // complete header cannot use this fallback because it would discard their structure.
            if(table.cells().stream().allMatch(c->c.rowSpan()==1&&c.columnSpan()==1))parseHtml(original,start,end,out,issues,page);
            else issue(issues,page,"PERSON_TABLE_HEADER_UNRESOLVED","合并人员表格缺少可验证表头，未推测列含义。");
            return;
        }
        if(table.rows().isEmpty()){issue(issues,page,"PERSON_TABLE_NO_DATA","人员表格只有表头，尚无可核对记录。");return;}
        Set<String> emittedSourceRecords=new HashSet<>();
        for(TableRelationIndex.Row row:table.rows()) {
            if(!row.status().equals("RESOLVED")){issue(issues,page,"PERSON_TABLE_AMBIGUOUS","人员数据行跨多个字段合并或混入表头，该行关系待核。");continue;}
            List<RawField> values=new ArrayList<>();Set<String> fields=new HashSet<>();boolean duplicate=false;
            for(TableRelationIndex.Association cell:row.associations()) {
                String field=relationField(cell.label().replaceAll("[：:]$",""));
                if(cn.bidcheck.compat.Java8Api.isBlank(field)){
                    if(!cn.bidcheck.compat.Java8Api.setOf("序号", "序列", "序", "备注", "No.", "No").contains(cell.label()))issue(issues,page,"PERSON_TABLE_FIELDS_UNSUPPORTED","人员表格存在尚未识别含义的列，明确字段已保留。");
                    continue;
                }
                if(!fields.add(field)){duplicate=true;break;}
                if(cn.bidcheck.compat.Java8Api.isBlank(cell.value())){issue(issues,page,"PERSON_FIELD_EMPTY","人员表格明确字段为空，未沿用前一行或前一页信息。");continue;}
                if(cell.value().length()>300){issue(issues,page,"PERSON_FIELD_UNRESOLVED","人员表格字段超过解析限制，未猜测值。");continue;}
                values.add(new RawField(field,cell.rawValue(),cell.value(),cell.source().start(),cell.source().end(),cell));
            }
            if(duplicate){issue(issues,page,"PERSON_TABLE_DUPLICATE_HEADER","人员表格出现重复字段列，未混同不同列的人员信息。");continue;}
            if(!fields.contains("personName")&&fields.stream().noneMatch(NUMBERS::contains)){issue(issues,page,"PERSON_TABLE_HEADER_UNRESOLVED","表头中缺少明确姓名或证件标识字段。");continue;}
            String sourceKey=values.stream().map(v->v.field+":"+v.start+":"+v.end).reduce((a,b)->a+"|"+b).orElse("");
            if(!values.isEmpty()&&emittedSourceRecords.add(sourceKey))out.add(new RawRecord(row.start(),row.end(),infer(fields),"HTML_RELATION",values));
        }
    }

    private static boolean relationalCandidate(String text){
        return Pattern.compile("(?is)<t[dh]\\b[^>]*>\\s*(?:姓\\s*名|人员姓名|公民身份号码|身份证(?:号|号码))\\s*</t[dh]\\s*>").matcher(text).find();
    }
    private static String relationField(String label){
        switch (label.replaceAll("[\\s\\p{Z}]+","")) {case "在本项目中担任职务":case "拟任职务":case "担任职务":case "本项目职务":return "role";case "初领日期":return "firstIssueDate";case "有效期开始日期":return "validFrom";case "有效期结束日期":return "validUntil";default:return field(label);}
    }
    private static void parseRelatedKeyValue(TableRelationIndex.Table table,List<RawRecord> out,List<Map<String,Object>> issues,Page page){
        List<RawField> fields=new ArrayList<>();int start=-1,end=0;
        for(TableRelationIndex.Row row:table.rows()){
            if(!row.status().equals("RESOLVED")){issue(issues,page,"PERSON_TABLE_AMBIGUOUS","人员键值表存在无法定位的标签和值，已保留明确字段。");continue;}
            for(TableRelationIndex.Association association:row.associations()){
                String field=relationField(association.label());if(cn.bidcheck.compat.Java8Api.isBlank(field))continue;
                if(field.equals("personName")&&fields.stream().anyMatch(f->f.field.equals("personName"))){
                    out.add(new RawRecord(start,end,infer(cn.bidcheck.compat.Java8Api.toList(fields.stream().map(RawField::field))),"HTML_RELATION",fields));fields=new ArrayList<>();start=-1;
                }
                if(cn.bidcheck.compat.Java8Api.isBlank(association.value())){issue(issues,page,"PERSON_FIELD_EMPTY","证件键值表字段为空，未补写值。");continue;}
                if(association.value().length()>300){issue(issues,page,"PERSON_FIELD_UNRESOLVED","证件键值表字段超过解析限制，需回查原文。");continue;}
                if(start<0)start=row.start();end=row.end();fields.add(new RawField(field,association.rawValue(),association.value(),association.source().start(),association.source().end(),association));
            }
        }
        if(!fields.isEmpty())out.add(new RawRecord(start,end,infer(cn.bidcheck.compat.Java8Api.toList(fields.stream().map(RawField::field))),"HTML_RELATION",fields));
    }

    private static void parseMarkdown(String original, char[] prose, List<RawRecord> out, List<Map<String,Object>> issues, Page page) {
        String text = new String(prose); Matcher lines = Pattern.compile("(?m)^.*$").matcher(text); List<TableRow> rows = new ArrayList<>();
        while (lines.find()) {
            String line = lines.group();
            if (!line.contains("|") && !line.contains("\t")) { flushRows(rows, out, issues, page); continue; }
            char delimiter = line.contains("|") ? '|' : '\t'; List<RawField> cells = new ArrayList<>();
            int left = 0, right = line.length();
            while (left < right && line.charAt(left) == ' ') left++;
            while (right > left && (line.charAt(right - 1) == ' ' || line.charAt(right - 1) == '\r')) right--;
            if (delimiter == '|' && left < right && line.charAt(left) == '|') left++;
            if (delimiter == '|' && right > left && line.charAt(right - 1) == '|') right--;
            int cellStart = left;
            for (int n = left; n <= right; n++) if (n == right || line.charAt(n) == delimiter) {
                int s = cellStart, e = n; while (s < e && Character.isWhitespace(line.charAt(s))) s++;
                while (e > s && Character.isWhitespace(line.charAt(e - 1))) e--;
                int[] unwrapped=unwrap(line,s,e);s=unwrapped[0];e=unwrapped[1];
                int offset = lines.start(); cells.add(new RawField("", original.substring(offset+s,offset+e), visible(line.substring(s,e)),offset+s,offset+e)); cellStart=n+1;
            }
            if (cells.size() < 2) { flushRows(rows, out, issues, page); continue; }
            if (!cells.stream().allMatch(c -> c.value.matches(":?-{2,}:?"))) rows.add(new TableRow(lines.start(), lines.end(), cells));
            Arrays.fill(prose, lines.start(), lines.end(), ' ');
        }
        flushRows(rows, out, issues, page);
    }
    private static void flushRows(List<TableRow> rows, List<RawRecord> out, List<Map<String,Object>> issues, Page page) {
        if (!rows.isEmpty()) parseRows(new ArrayList<>(rows), "DELIMITED", out, issues, page);
        rows.clear();
    }
    private static void parseRows(List<TableRow> rows, String format, List<RawRecord> out, List<Map<String,Object>> issues, Page page) {
        if (rows.isEmpty()) return;
        if(rows.size()>2&&rows.get(0).cells.size()==2&&cn.bidcheck.compat.Java8Api.setOf("字段", "项目", "信息项", "属性", "Field").contains(rows.get(0).cells.get(0).value)
                &&cn.bidcheck.compat.Java8Api.setOf("内容", "信息", "值", "说明", "Value", "Details").contains(rows.get(0).cells.get(1).value)) {
            parseRows(rows.subList(1,rows.size()),format,out,issues,page);return;
        }
        boolean keyValue = rows.size() >= 2 && rows.get(0).cells.size() == 2
                && !cn.bidcheck.compat.Java8Api.isBlank(field(rows.get(0).cells.get(0).value)) && cn.bidcheck.compat.Java8Api.isBlank(field(rows.get(0).cells.get(1).value))
                && rows.stream().filter(r -> r.cells.size() == 2 && !cn.bidcheck.compat.Java8Api.isBlank(field(r.cells.get(0).value))).count() >= 2;
        if (keyValue) { parseKeyValueRows(rows,format,out,issues,page); return; }
        List<String> fields = cn.bidcheck.compat.Java8Api.toList(rows.get(0).cells.stream().map(c -> field(c.value.replaceAll("[：:]$", ""))));
        boolean personnel = fields.contains("personName") || fields.stream().anyMatch(NUMBERS::contains);
        if (!personnel) { issue(issues, page, "PERSON_TABLE_HEADER_UNRESOLVED", "人员材料中的表头无法明确识别，需补充结构解析。"); return; }
        if (rows.size() == 1) { issue(issues, page, "PERSON_TABLE_NO_DATA", "人员表格只有表头，尚无可核对记录。"); return; }
        Set<String> seen = new HashSet<>();
        for (int column = 0; column < fields.size(); column++) if (cn.bidcheck.compat.Java8Api.isBlank(fields.get(column))
                && !cn.bidcheck.compat.Java8Api.setOf("序号", "序列", "序", "备注", "No.", "No").contains(rows.get(0).cells.get(column).value))
            issue(issues, page, "PERSON_TABLE_FIELDS_UNSUPPORTED", "人员表格存在尚未识别含义的列，已提取明确字段，其余列覆盖待补充。");
        for (String field : fields) if (!cn.bidcheck.compat.Java8Api.isBlank(field) && !seen.add(field)) {
            issue(issues, page, "PERSON_TABLE_DUPLICATE_HEADER", "人员表格存在重复字段列，无法确定同一人的字段归属。"); return;
        }
        String kind = infer(fields);
        for (int n = 1; n < rows.size(); n++) {
            TableRow row = rows.get(n);
            if (row.cells.size() != fields.size()) { issue(issues, page, "PERSON_TABLE_COLUMNS_MISMATCH", "人员表格行列数量不一致，该行未提取。"); continue; }
            List<RawField> values = new ArrayList<>();
            for (int c = 0; c < fields.size(); c++) {
                String field = fields.get(c); RawField cell = row.cells.get(c);
                if (cn.bidcheck.compat.Java8Api.isBlank(field)) continue;
                if (cn.bidcheck.compat.Java8Api.isBlank(cell.value)) { issue(issues, page, "PERSON_FIELD_EMPTY", "人员表格明确字段为空，未沿用前一行人员信息。"); continue; }
                if (cell.value.length() > 300) { issue(issues, page, "PERSON_FIELD_UNRESOLVED", "人员表格字段超过解析限制，未猜测值。"); continue; }
                values.add(new RawField(field, cell.raw, cell.value, cell.start, cell.end));
            }
            if (!values.isEmpty()) out.add(new RawRecord(row.start, row.end, kind, format, values));
        }
    }
    private static void parseKeyValueRows(List<TableRow> rows, String format, List<RawRecord> out, List<Map<String,Object>> issues, Page page) {
        List<RawField> values = new ArrayList<>(); int start = rows.get(0).start, end = start;
        for (TableRow row : rows) {
            if (row.cells.size() != 2) { issue(issues,page,"PERSON_TABLE_COLUMNS_MISMATCH","证件键值表行列数量不一致，该行未提取。"); continue; }
            String field = field(row.cells.get(0).value); RawField cell = row.cells.get(1);
            if (cn.bidcheck.compat.Java8Api.isBlank(field)) { issue(issues,page,"PERSON_TABLE_FIELDS_UNSUPPORTED","证件键值表含尚未识别的字段，覆盖待补充。"); continue; }
            if (field.equals("personName") && values.stream().anyMatch(v -> v.field.equals("personName"))) {
                out.add(new RawRecord(start,end,infer(cn.bidcheck.compat.Java8Api.toList(values.stream().map(RawField::field))),format,values));
                values=new ArrayList<>();start=row.start;
            }
            end=row.end;
            if (cn.bidcheck.compat.Java8Api.isBlank(cell.value)) {issue(issues,page,"PERSON_FIELD_EMPTY","证件键值表明确字段为空，未补写字段值。");continue;}
            if (cell.value.length()>300) {issue(issues,page,"PERSON_FIELD_UNRESOLVED","证件键值表字段超过解析限制，未猜测值。");continue;}
            values.add(new RawField(field,cell.raw,cell.value,cell.start,cell.end));
        }
        if (!values.isEmpty()) out.add(new RawRecord(start,end,infer(cn.bidcheck.compat.Java8Api.toList(values.stream().map(RawField::field))),format,values));
    }

    private static void publish(Document document, Page page, RawRecord record, List<Occurrence> out, List<Map<String,Object>> outputIssues) {
        List<Map<String,Object>> issues = new ArrayList<>();
        String name = single(record.fields, "personName"), role = single(record.fields, "role");
        String credentialName = single(record.fields, "certificateName");
        String kind = record.type;
        if (!cn.bidcheck.compat.Java8Api.isBlank(credentialName) && !type(credentialName).equals("UNKNOWN")) kind = type(credentialName);
        if (kind.equals("UNKNOWN")) kind = infer(cn.bidcheck.compat.Java8Api.toList(record.fields.stream().map(RawField::field)));
        List<RawField> resolved = new ArrayList<>();
        for (RawField raw : record.fields) {
            String field = raw.field.equals("genericNumber") ? numberField(kind) : raw.field;
            if(field.equals("classification")&&(kind.equals("DRIVING_LICENSE")||record.fields.stream().anyMatch(f->cn.bidcheck.compat.Java8Api.setOf("drivingLicenseNumber", "drivingFileNumber", "vehicleClass").contains(f.field))))
                field="vehicleClass";
            if(field.equals("archiveNumber")&&(kind.equals("DRIVING_LICENSE")||record.fields.stream().anyMatch(f->cn.bidcheck.compat.Java8Api.setOf("drivingLicenseNumber", "drivingFileNumber", "vehicleClass").contains(f.field))))
                field="drivingFileNumber";
            if (cn.bidcheck.compat.Java8Api.isBlank(field)) { issue(issues, page, "CREDENTIAL_TYPE_UNKNOWN", "发现证号或编号，但证件类别不明确，未混同不同证件的号码。"); continue; }
            resolved.add(new RawField(field, raw.raw, raw.value, raw.start, raw.end, raw.association));
        }
        boolean ambiguousRecord = resolved.stream().filter(f -> NUMBERS.contains(f.field)).map(RawField::field).distinct()
                .anyMatch(f -> resolved.stream().filter(v -> v.field.equals(f)).map(RawField::value).distinct().count() > 1);
        if (ambiguousRecord) issue(issues, page, "PERSON_RECORD_AMBIGUOUS", "同一记录范围出现多个不同的同类证号，已保留原文但未猜测人员归属。");
        if (cn.bidcheck.compat.Java8Api.isBlank(name)) issue(issues, page, "PERSON_NAME_MISSING", "人员记录缺少明确姓名，已保留字段但未关联到其他人员。");
        else if (!usableName(name)) issue(issues, page, "PERSON_NAME_AMBIGUOUS", "姓名字段疑似包含多人、掩码或无法确定的内容，未用于确定身份。");
        if (kind.equals("UNKNOWN")) issue(issues, page, "CREDENTIAL_TYPE_UNKNOWN", "人员材料缺少明确证件类型，需复核字段类别。");
        if (!kind.equals("PERSON_ROSTER") && resolved.stream().noneMatch(f -> NUMBERS.contains(f.field) && !f.field.equals("drivingFileNumber")))
            issue(issues, page, "CREDENTIAL_NUMBER_MISSING", "证件记录缺少明确证件号码，覆盖尚未完成。");
        if (kind.equals("PERSON_CERTIFICATE") && cn.bidcheck.compat.Java8Api.isBlank(credentialName))
            issue(issues, page, "CERTIFICATE_NAME_MISSING", "证书未明确名称或类别，不能与其他类型证书混同比较。");
        if (record.end - record.start > MAX_RECORD) {
            issue(outputIssues, page, "PERSON_RECORD_LIMIT", "人员记录范围过长，需核对记录边界。"); return;
        }
        String recordKey = document.id() + ":v" + document.version() + ":" + document.sha256() + ":" + page.locationType()
                + ":p" + page.number() + ":person-record:" + record.start;
        TableRelationIndex.Association recordRelation=record.fields.stream().map(RawField::association).filter(Objects::nonNull).findFirst().orElse(null);
        if(recordRelation!=null)recordKey+=":table-row:"+recordRelation.row();
        Map<String,String> base = new LinkedHashMap<>();
        base.put("recordStart", Integer.toString(record.start)); base.put("recordEnd", Integer.toString(record.end));
        base.put("sourceFormat", record.format); base.put("role", role); base.put("credentialName", credentialName);
        base.put("personNameStatus", cn.bidcheck.compat.Java8Api.isBlank(name) ? "MISSING" : usableName(name) && !ambiguousRecord ? "EXPLICIT" : "AMBIGUOUS");
        base.put("sourceVersion", Integer.toString(document.version())); base.put("sourceHash", Objects.toString(document.sha256(), ""));
        base.put("documentId", document.id()); base.put("documentHash", Objects.toString(document.sha256(), ""));
        base.put("documentVersion", Integer.toString(document.version()));
        base.put("parserVersion", VERSION);
        Map<RawField,String> statuses = new IdentityHashMap<>();
        for (RawField raw : resolved) {
            if (!NUMBERS.contains(raw.field)) continue;
            String status = numberStatus(raw.field, raw.value); statuses.put(raw,status);
            if (status.equals("MASKED")) issue(issues, page, "CREDENTIAL_NUMBER_MASKED", "证件号码含掩码，已保留原文，不能据此确认完整号码一致。");
            else if (status.equals("INVALID")) issue(issues, page, "CREDENTIAL_NUMBER_FORMAT", "证件号码格式可疑，已保留原文，需回查原页识别结果。");
        }
        boolean complete = issues.isEmpty() && !ambiguousRecord && usableName(name)
                && statuses.values().stream().allMatch("VALID"::equals);
        for (RawField raw : resolved) {
            String value = normalize(raw.field,raw.value);
            Map<String,String> context = new LinkedHashMap<>(base);
            if(raw.association!=null){
                TableRelationIndex.Association association=raw.association;
                context.put("tableRelationVersion",TableRelationIndex.VERSION);context.put("tableId",association.tableId());
                context.put("tableRow",Integer.toString(association.row()));context.put("tableColumn",Integer.toString(association.column()));
                context.put("tableCellId",association.cellId());context.put("tableHeaderPath",String.join("\n",association.headerPath()));
                context.put("tableInherited",Boolean.toString(association.inherited()));context.put("tableAssociationStatus",association.status());
                context.put("tableSourceStart",Integer.toString(association.source().start()));context.put("tableSourceEnd",Integer.toString(association.source().end()));
                context.put("tableHeaderSources",association.headerSources().stream().map(s->s.start()+":"+s.end()).reduce((a,b)->a+";"+b).orElse(""));
            }
            context.put("recordComplete", Boolean.toString(complete)); context.put("numberMasked", Boolean.toString("MASKED".equals(statuses.get(raw))));
            if (statuses.containsKey(raw)) { context.put("numberStatus",statuses.get(raw)); context.put("numberValidation","FORMAT_ONLY"); }
            String quote = cn.bidcheck.compat.Java8Api.strip(page.text().substring(record.start, record.end));
            String anchor = "person-record:" + record.start + ":field:" + raw.field + ":chars:" + raw.start + "-" + raw.end;
            Evidence evidence = new Evidence(document.id(), document.name(), page.number(), page.locationType(), quote, anchor,
                    Objects.toString(page.parser(), "").toUpperCase(Locale.ROOT).contains("OCR") ? 0d : 1d);
            String id = digest(recordKey + "|" + raw.field + "|" + raw.start + "|" + raw.end);
            out.add(new Occurrence(id, recordKey, usableName(name) && !ambiguousRecord ? name : "", kind, raw.field, raw.raw, value, evidence, context));
        }
        for (Map<String,Object> item : issues) issue(outputIssues,page,item.get("code").toString(),item.get("reason").toString());
    }

    /** Reject only explicit non-values. Missing, masked and merely unfamiliar values stay visible.
     * The source document and legacy transcription remain untouched; every rejection is auditable. */
    private static RawRecord filterConsistencyNoise(Document document,Page page,RawRecord record,List<Map<String,Object>> issues) {
        if(record.end-record.start>MAX_RECORD)return record;
        List<RawField> kept=new ArrayList<>();
        for(RawField raw:record.fields) {
            String reason=consistencyNoiseReason(page,record,raw);
            if(cn.bidcheck.compat.Java8Api.isBlank(reason)){kept.add(raw);continue;}
            String recordKey=document.id()+":v"+document.version()+":"+document.sha256()+":"+page.locationType()
                    +":p"+page.number()+":person-record:"+record.start;
            TableRelationIndex.Association relation=record.fields.stream().map(RawField::association).filter(Objects::nonNull).findFirst().orElse(null);
            if(relation!=null)recordKey+=":table-row:"+relation.row();
            String oldId=digest(recordKey+"|"+raw.field+"|"+raw.start+"|"+raw.end);
            String anchor="person-record:"+record.start+":field:"+raw.field+":chars:"+raw.start+"-"+raw.end;
            Evidence evidence=new Evidence(document.id(),document.name(),page.number(),page.locationType(),
                    cn.bidcheck.compat.Java8Api.strip(page.text().substring(record.start,record.end)),anchor,
                    Objects.toString(page.parser(),"").toUpperCase(Locale.ROOT).contains("OCR")?0d:1d);
            Map<String,Object> rejection=new LinkedHashMap<>();
            rejection.put("code","PERSON_EXTRACTION_NOISE_REJECTED");rejection.put("category","EXTRACTION_NOISE");
            rejection.put("policyVersion",CONSISTENCY_FILTER_VERSION);rejection.put("rejectionId",digest(CONSISTENCY_FILTER_VERSION+"|"+oldId));
            rejection.put("oldOccurrenceId",oldId);rejection.put("field",raw.field);rejection.put("reasonCode",reason);
            rejection.put("reason",(java.util.Objects.equals((reason), "IMAGE_AGE_ESTIMATE") ? "头像年龄估计或图像分析说明不是原文登记年龄，已从一致性事实中剔除。" : (java.util.Objects.equals((reason), "PERSON_NAME_DESCRIPTION") ? "证书说明、签名提示或图像描述不是明确姓名，已从一致性事实中剔除。" : (java.util.Objects.equals((reason), "ROLE_DESCRIPTION_OR_HEADER") ? "表头片段、岗位职责说明或图像描述不是明确人员职务，已从一致性事实中剔除。" : "字段标签后接的是标题或排版片段，未将其作为人员字段值。"))));
            rejection.put("requiresReview",false);rejection.put("documentId",document.id());
            rejection.put("documentVersion",document.version());rejection.put("documentHash",Objects.toString(document.sha256(),""));
            rejection.put("page",page.number());rejection.put("locationType",page.locationType());
            rejection.put("recordStart",record.start);rejection.put("recordEnd",record.end);
            rejection.put("start",raw.start);rejection.put("end",raw.end);rejection.put("rawValue",raw.raw);
            rejection.put("sourceFormat",record.format);rejection.put("evidence",evidence);
            issues.add(Collections.unmodifiableMap(rejection));
        }
        return new RawRecord(record.start,record.end,record.type,record.format,kept);
    }

    private static String consistencyNoiseReason(Page page,RawRecord record,RawField raw) {
        String value=cn.bidcheck.compat.Java8Api.strip(raw.value);
        if(raw.field.equals("age")&&Pattern.compile("估计|估算|推断|面部|发际线|皮肤|背景色|^(?:区间|段)[：:]|^精确估算").matcher(value).find())
            return "IMAGE_AGE_ESTIMATE";
        if(raw.field.equals("personName")) {
            if(value.matches(">\\s*[-—–]")
                    ||value.matches("(?:信息|签名|印章)[：:。*（(].*")
                    ||cn.bidcheck.compat.Java8Api.setOf("信息", "签名", "印章").contains(value)&&record.format.equals("TEXT")
                        &&Pattern.compile("(?:姓名|持证人)(?:\\*\\*|__)?$").matcher(page.text().substring(Math.max(0,raw.start-20),raw.start)).find()
                    ||Pattern.compile("照片|头像|证件照|免冠|^通过国家统一组织的考试|^(?:已|已经)通过.*考核|^具备.*执业能力").matcher(value).find())
                return "PERSON_NAME_DESCRIPTION";
        }
        if(raw.field.equals("role")) {
            if(value.matches("(?is)(?:Detection\\s+Patterns|patterns\\s*[—–-]).*")
                    ||Pattern.compile("^(?:职责|的具体职责|因显示空间限制|培训等证书|配备具有相应)").matcher(value).find()
                    ||record.format.equals("TEXT")&&cn.bidcheck.compat.Java8Api.setOf("发包人及", "号").contains(value))
                return "ROLE_DESCRIPTION_OR_HEADER";
        }
        // These are exact label continuations from flat text, never a guess from an unknown value.
        if(record.format.equals("TEXT")) {
            if(raw.field.equals("education")&&value.matches("(?:证书(?:查询网址.*)?|业资格\\s*限)"))return "FIELD_LABEL_FRAGMENT";
            if(raw.field.equals("professionalMajor")&&value.matches("(?:主要工作经历|类别[：:]?)"))return "FIELD_LABEL_FRAGMENT";
            if(raw.field.equals("professionalTitle")&&value.matches("(?:证书|审批专用章|评审信息查询(?:[（(].*[）)])?)"))return "FIELD_LABEL_FRAGMENT";
        }
        return "";
    }
    private static String single(List<RawField> fields, String field) {
        List<String> values = cn.bidcheck.compat.Java8Api.toList(fields.stream().filter(f -> f.field.equals(field)).map(f->normalize(field,f.value)).distinct());
        return values.size() == 1 ? values.get(0) : "";
    }
    private static boolean usableName(String value) {
        return !cn.bidcheck.compat.Java8Api.isBlank(value) && value.length() <= 80 && !Pattern.compile("[*/＊×，,、;；|\\d]|(?:未知|不详|未提供)").matcher(value).find()
                && (value.matches("[\\p{IsHan}·•]{2,30}") || value.matches("[A-Za-z][A-Za-z .'’-]{1,79}"));
    }
    private static String numberStatus(String field, String value) {
        String v = normalize(field,value);
        if (Pattern.compile("[*＊×•●_]|X{2,}|x{2,}|…|\\.\\.\\.").matcher(v).find()) return "MASKED";
        if (field.equals("idNumber")) {
            if (!v.matches("(?:[0-9]{15}|[0-9]{17}[0-9X])")) return "INVALID";
            String birth = v.length() == 18 ? v.substring(6,14) : "19"+v.substring(6,12);
            try { LocalDate.parse(birth.substring(0,4)+"-"+birth.substring(4,6)+"-"+birth.substring(6,8)); }
            catch (RuntimeException ignored) { return "INVALID"; }
            return "VALID";
        }
        return v.matches("[A-Z0-9][A-Z0-9()（）/.-]{1,79}") ? "VALID" : "INVALID";
    }
    private static String normalize(String field, String value) {
        if (NUMBERS.contains(field)) return value.replaceAll("[\\s\\p{Z}]+", "").toUpperCase(Locale.ROOT);
        if(field.equals("personName")&&cn.bidcheck.compat.Java8Api.strip(value).matches("[\\p{IsHan}·•](?:[\\s\\p{Z}]+[\\p{IsHan}·•]){1,29}")) {
            String compact=value.replaceAll("[\\s\\p{Z}]+","");if(compact.matches("[\\p{IsHan}·•]{2,30}"))return compact;
        }
        if (field.equals("gender")) switch (value.toLowerCase(Locale.ROOT)) {case "male":case "m":return "男";case "female":case "f":return "女";default:return value;}
        return cn.bidcheck.compat.Java8Api.strip(value.replaceAll("[\\s\\p{Z}]+", " "));
    }
    private static String type(String title) {
        String value = title.toLowerCase(Locale.ROOT);
        if (value.contains("驾驶") || value.contains("driving") || value.contains("driver")) return "DRIVING_LICENSE";
        if (value.contains("身份证") || value.contains("identity card")) return "ID_CARD";
        if (value.contains("护照") || value.contains("passport")) return "PASSPORT";
        if (value.contains("证书") || value.contains("certificate")) return "PERSON_CERTIFICATE";
        if (value.contains("人员")) return "PERSON_ROSTER";
        return "UNKNOWN";
    }
    private static String infer(Collection<String> fields) {
        if ((fields.contains("role") || fields.contains("professionalTitle")) && fields.contains("personName")) return "PERSON_ROSTER";
        if (fields.contains("drivingLicenseNumber") || fields.contains("vehicleClass") || fields.contains("drivingFileNumber")) return "DRIVING_LICENSE";
        if (fields.contains("passportNumber")) return "PASSPORT";
        if (fields.contains("idNumber")) return "ID_CARD";
        if (fields.contains("certificateNumber") || fields.contains("certificateName")) return "PERSON_CERTIFICATE";
        return "UNKNOWN";
    }
    private static String numberField(String kind) {
        switch (kind) {case "ID_CARD":return "idNumber";case "DRIVING_LICENSE":return "drivingLicenseNumber";case "PASSPORT":return "passportNumber";case "PERSON_CERTIFICATE":return "certificateNumber";default:return "";}
    }
    private static void labels(String field, String... labels) { for (String label : labels) LABELS.put(label.toLowerCase(Locale.ROOT),field); }
    private static String field(String label) {
        String value=cn.bidcheck.compat.Java8Api.strip(cn.bidcheck.compat.Java8Api.strip(label).replaceAll("^(?:\\*\\*|__)|(?:\\*\\*|__)$","")).toLowerCase(Locale.ROOT);
        if(value.matches("[\\p{IsHan}\\s\\p{Z}]+"))value=value.replaceAll("[\\s\\p{Z}]+","");
        return LABELS.getOrDefault(value, "");
    }
    private static String labelPattern(String label) {
        if(!label.matches("[\\p{IsHan}]+"))return Pattern.quote(label);
        return label.codePoints().mapToObj(c->Pattern.quote(new String(Character.toChars(c)))).reduce((a,b)->a+"[ \\t\\u3000]*"+b).orElseThrow(java.util.NoSuchElementException::new);
    }
    private static boolean knownLabel(String value) {
        if(!cn.bidcheck.compat.Java8Api.isBlank(field(value)))return true;
        String text=value+":";Matcher labels=LABEL.matcher(text);int end=0,count=0;Set<String> fields=new HashSet<>();
        while(labels.find()){if(!cn.bidcheck.compat.Java8Api.isBlank(text.substring(end,labels.start())))return false;fields.add(field(labels.group(1)));count++;end=labels.end();}
        return count>=2&&fields.size()==1&&!fields.contains("")&&cn.bidcheck.compat.Java8Api.isBlank(text.substring(end));
    }
    private static int[] unwrap(String text,int start,int end) {
        for(String marker:cn.bidcheck.compat.Java8Api.listOf("**", "__", "`")) if(end-start>=marker.length()*2&&text.startsWith(marker,start)&&text.substring(start,end).endsWith(marker)) {
            start+=marker.length();end-=marker.length();while(start<end&&Character.isWhitespace(text.charAt(start)))start++;while(end>start&&Character.isWhitespace(text.charAt(end-1)))end--;break;
        }
        return new int[]{start,end};
    }
    private static String visible(String value) {
        return cn.bidcheck.compat.Java8Api.strip(value.replaceAll("(?i)<br\\s*/?>", " ").replace("&nbsp;", " ").replace("&#160;", " ")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replaceAll("[\\s\\p{Z}]+", " "));
    }
    private static int count(Pattern pattern, String value) { return (int)cn.bidcheck.compat.Java8Extras.matchCount(pattern.matcher(value)); }
    private static void issue(List<Map<String,Object>> issues, Page page, String code, String reason) {
        if (issues.stream().noneMatch(i -> code.equals(i.get("code")))) issues.add(cn.bidcheck.compat.Java8Api.mapOf("code", code, "page", page.number(), "locationType", page.locationType(), "reason", reason));
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Token {
        private final int start;
        private final int end;
        private final String field;
        private final String label;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Token(@com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("field") String field, @com.fasterxml.jackson.annotation.JsonProperty("label") String label) { this.start = start; this.end = end; this.field = field; this.label = label; }
        public int start() { return start; }
        public int end() { return end; }
        public String field() { return field; }
        public String label() { return label; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Token that = (Token) other; return java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.field, that.field) && java.util.Objects.equals(this.label, that.label); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.field); hash = 31 * hash + java.util.Objects.hashCode(this.label); return hash; }
        @Override public String toString() { return "Token[" + "start=" + start + ", end=" + end + ", field=" + field + ", label=" + label + "]"; }
 }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Title {
        private final int start;
        private final int end;
        private final String raw;
        private final String type;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Title(@com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("raw") String raw, @com.fasterxml.jackson.annotation.JsonProperty("type") String type) { this.start = start; this.end = end; this.raw = raw; this.type = type; }
        public int start() { return start; }
        public int end() { return end; }
        public String raw() { return raw; }
        public String type() { return type; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Title that = (Title) other; return java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.raw, that.raw) && java.util.Objects.equals(this.type, that.type); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.raw); hash = 31 * hash + java.util.Objects.hashCode(this.type); return hash; }
        @Override public String toString() { return "Title[" + "start=" + start + ", end=" + end + ", raw=" + raw + ", type=" + type + "]"; }
 }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class RawField {
        private final String field;
        private final String raw;
        private final String value;
        private final int start;
        private final int end;
        private final TableRelationIndex.Association association;
        @com.fasterxml.jackson.annotation.JsonCreator
        private RawField(@com.fasterxml.jackson.annotation.JsonProperty("field") String field, @com.fasterxml.jackson.annotation.JsonProperty("raw") String raw, @com.fasterxml.jackson.annotation.JsonProperty("value") String value, @com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("association") TableRelationIndex.Association association) { this.field = field; this.raw = raw; this.value = value; this.start = start; this.end = end; this.association = association; }
        public String field() { return field; }
        public String raw() { return raw; }
        public String value() { return value; }
        public int start() { return start; }
        public int end() { return end; }
        public TableRelationIndex.Association association() { return association; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; RawField that = (RawField) other; return java.util.Objects.equals(this.field, that.field) && java.util.Objects.equals(this.raw, that.raw) && java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.association, that.association); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.field); hash = 31 * hash + java.util.Objects.hashCode(this.raw); hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.association); return hash; }
        @Override public String toString() { return "RawField[" + "field=" + field + ", raw=" + raw + ", value=" + value + ", start=" + start + ", end=" + end + ", association=" + association + "]"; }

        RawField(String field,String raw,String value,int start,int end){this(field,raw,value,start,end,null);}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class RawRecord {
        private final int start;
        private final int end;
        private final String type;
        private final String format;
        private final List<RawField> fields;
        @com.fasterxml.jackson.annotation.JsonCreator
        private RawRecord(@com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("type") String type, @com.fasterxml.jackson.annotation.JsonProperty("format") String format, @com.fasterxml.jackson.annotation.JsonProperty("fields") List<RawField> fields) { this.start = start; this.end = end; this.type = type; this.format = format; this.fields = fields; }
        public int start() { return start; }
        public int end() { return end; }
        public String type() { return type; }
        public String format() { return format; }
        public List<RawField> fields() { return fields; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; RawRecord that = (RawRecord) other; return java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.type, that.type) && java.util.Objects.equals(this.format, that.format) && java.util.Objects.equals(this.fields, that.fields); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.type); hash = 31 * hash + java.util.Objects.hashCode(this.format); hash = 31 * hash + java.util.Objects.hashCode(this.fields); return hash; }
        @Override public String toString() { return "RawRecord[" + "start=" + start + ", end=" + end + ", type=" + type + ", format=" + format + ", fields=" + fields + "]"; }
 }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class TableRow {
        private final int start;
        private final int end;
        private final List<RawField> cells;
        @com.fasterxml.jackson.annotation.JsonCreator
        private TableRow(@com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("cells") List<RawField> cells) { this.start = start; this.end = end; this.cells = cells; }
        public int start() { return start; }
        public int end() { return end; }
        public List<RawField> cells() { return cells; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; TableRow that = (TableRow) other; return java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.cells, that.cells); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.cells); return hash; }
        @Override public String toString() { return "TableRow[" + "start=" + start + ", end=" + end + ", cells=" + cells + "]"; }
 }
}
