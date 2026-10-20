package cn.bidcheck.document;

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
    private static final Set<String> NUMBERS = Set.of("idNumber", "drivingLicenseNumber", "drivingFileNumber", "passportNumber", "certificateNumber");
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
                .map(PersonCredentialFacts::labelPattern).reduce((a,b) -> a + "|" + b).orElseThrow();
        LABEL = Pattern.compile("(?iu)(?<![A-Za-z])(" + alternatives + ")(?![A-Za-z])(?:\\*\\*|__)?(?:[ \\t]*[:：][ \\t]*|[ \\t]+|(?=[\\r\\n])|(?=[\\p{IsHan}0-9]))");
        TITLE = Pattern.compile("(?imu)^[ \\t]*(?:#{1,6}[ \\t]+)?(?:\\*\\*|__)?(中华人民共和国机动车驾驶证|机动车驾驶证|驾驶证|中华人民共和国居民身份证|居民身份证|身份证|中华人民共和国护照|护照|Driving Licen[cs]e|Driver'?s Licen[cs]e|(?:National )?Identity Card|Passport|[\\p{IsHan}]{0,18}(?:资格证书|执业证书|注册证书|职称证书|合格证书)|人员(?:名单|一览表|情况表|配备表))(?!号码|编号|号|名称|类别|[ \\t]+(?:Number|No\\.?))");
    }
    private PersonCredentialFacts() { }
    public record Occurrence(String id, String recordKey, String personName, String credentialType, String field,
                             String rawValue, String value, Evidence evidence, Map<String,String> context) {
        public Occurrence { context = Map.copyOf(context); }
    }
    public record Result(int page, String locationType, boolean candidate, boolean complete, List<Occurrence> occurrences,
                         List<Map<String,Object>> issues, List<Fact> facts) {
        public Result { occurrences = List.copyOf(occurrences); issues = List.copyOf(issues); facts = List.copyOf(facts); }
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
        if (page == null) return new Result(0, "PAGE", false, false, List.of(), List.of(), List.of());
        List<Map<String,Object>> issues = new ArrayList<>();
        if (!candidate) return result(page, false, true, List.of(), issues);
        if (document == null || document.pages() == null || document.pages().stream().noneMatch(p -> p.number() == page.number()
                && Objects.equals(p.locationType(), page.locationType()) && Objects.equals(p.text(), page.text()))) {
            issue(issues, page, "SOURCE_NOT_BOUND", "页面未绑定当前文档版本，未提取人员证件事实。");
            return result(page, true, false, List.of(), issues);
        }
        if (text.length() > MAX_PAGE) {
            issue(issues, page, "PERSON_PAGE_LIMIT", "人员材料超过结构解析长度限制，需进一步识别。");
            return result(page, true, false, List.of(), issues);
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
        List<Fact> facts = occurrences.stream().map(o -> new Fact("PERSON_CREDENTIAL", o.recordKey(), o.field(), o.rawValue(), o.value(), o.evidence())).toList();
        return new Result(page.number(), page.locationType(), candidate, complete, occurrences, issues, facts);
    }
    private static boolean candidate(String text) {
        List<String> lines=text.lines().map(String::strip).filter(s->!s.isBlank()&&!s.matches("[0-9]{1,5}|[目录\\s]+")).toList();
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
        List<Integer> breaks = new ArrayList<>(List.of(0)); for (Title title : titles) if (title.start > 0) breaks.add(title.start);
        breaks.add(text.length()); breaks = breaks.stream().distinct().sorted().toList();
        for (int b = 0; b + 1 < breaks.size(); b++) {
            int start = breaks.get(b), end = breaks.get(b + 1);
            List<Token> part = tokens.stream().filter(t -> t.start >= start && t.start < end).toList();
            if (part.isEmpty()) continue;
            Title title = titles.stream().filter(t -> t.start == start).findFirst().orElse(null);
            String kind = title == null ? infer(part.stream().map(Token::field).toList()) : title.type;
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
            if (!html.substring(previous, row.start()).replaceAll("(?is)</?(?:thead|tbody|tfoot)\\b[^>]*>", "").isBlank()) {
                issue(issues, page, "PERSON_TABLE_INCOMPLETE", "人员表格含无法定位的行或内容，未猜测行列。"); return;
            }
            List<RawField> cells = new ArrayList<>(); Matcher cell = CELL.matcher(row.group(1)); int at = 0;
            while (cell.find()) {
                if (!row.group(1).substring(at, cell.start()).isBlank() || cell.group(3).contains("<") && !cell.group(3).matches("(?is)(?:[^<]|<br\\s*/?>)*")) {
                    issue(issues, page, "PERSON_TABLE_CELL_UNSUPPORTED", "人员表格单元格格式无法安全解析，需补充识别。"); return;
                }
                int cs = start + row.start(1) + cell.start(3), ce = start + row.start(1) + cell.end(3);
                cells.add(new RawField("", original.substring(cs, ce), visible(cell.group(3)), cs, ce)); at = cell.end();
            }
            if (!row.group(1).substring(at).isBlank() || cells.isEmpty()) {
                issue(issues, page, "PERSON_TABLE_INCOMPLETE", "人员表格单元格未闭合，未猜测字段归属。"); return;
            }
            rows.add(new TableRow(start + row.start(), start + row.end(), cells)); previous = row.end();
        }
        String remainder = html.substring(previous).replaceAll("(?is)</?(?:thead|tbody|tfoot)\\b[^>]*>|</table\\s*>", "");
        if (!remainder.isBlank()) { issue(issues, page, "PERSON_TABLE_INCOMPLETE", "人员表格末尾不完整，未猜测字段归属。"); return; }
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
                if(field.isBlank()){
                    if(!Set.of("序号","序列","序","备注","No.","No").contains(cell.label()))issue(issues,page,"PERSON_TABLE_FIELDS_UNSUPPORTED","人员表格存在尚未识别含义的列，明确字段已保留。");
                    continue;
                }
                if(!fields.add(field)){duplicate=true;break;}
                if(cell.value().isBlank()){issue(issues,page,"PERSON_FIELD_EMPTY","人员表格明确字段为空，未沿用前一行或前一页信息。");continue;}
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
        return switch(label.replaceAll("[\\s\\p{Z}]+","")){
            case "在本项目中担任职务","拟任职务","担任职务","本项目职务" -> "role";
            case "初领日期" -> "firstIssueDate";
            case "有效期开始日期" -> "validFrom";
            case "有效期结束日期" -> "validUntil";
            default -> field(label);
        };
    }
    private static void parseRelatedKeyValue(TableRelationIndex.Table table,List<RawRecord> out,List<Map<String,Object>> issues,Page page){
        List<RawField> fields=new ArrayList<>();int start=-1,end=0;
        for(TableRelationIndex.Row row:table.rows()){
            if(!row.status().equals("RESOLVED")){issue(issues,page,"PERSON_TABLE_AMBIGUOUS","人员键值表存在无法定位的标签和值，已保留明确字段。");continue;}
            for(TableRelationIndex.Association association:row.associations()){
                String field=relationField(association.label());if(field.isBlank())continue;
                if(field.equals("personName")&&fields.stream().anyMatch(f->f.field.equals("personName"))){
                    out.add(new RawRecord(start,end,infer(fields.stream().map(RawField::field).toList()),"HTML_RELATION",fields));fields=new ArrayList<>();start=-1;
                }
                if(association.value().isBlank()){issue(issues,page,"PERSON_FIELD_EMPTY","证件键值表字段为空，未补写值。");continue;}
                if(association.value().length()>300){issue(issues,page,"PERSON_FIELD_UNRESOLVED","证件键值表字段超过解析限制，需回查原文。");continue;}
                if(start<0)start=row.start();end=row.end();fields.add(new RawField(field,association.rawValue(),association.value(),association.source().start(),association.source().end(),association));
            }
        }
        if(!fields.isEmpty())out.add(new RawRecord(start,end,infer(fields.stream().map(RawField::field).toList()),"HTML_RELATION",fields));
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
        if(rows.size()>2&&rows.get(0).cells.size()==2&&Set.of("字段","项目","信息项","属性","Field").contains(rows.get(0).cells.get(0).value)
                &&Set.of("内容","信息","值","说明","Value","Details").contains(rows.get(0).cells.get(1).value)) {
            parseRows(rows.subList(1,rows.size()),format,out,issues,page);return;
        }
        boolean keyValue = rows.size() >= 2 && rows.get(0).cells.size() == 2
                && !field(rows.get(0).cells.get(0).value).isBlank() && field(rows.get(0).cells.get(1).value).isBlank()
                && rows.stream().filter(r -> r.cells.size() == 2 && !field(r.cells.get(0).value).isBlank()).count() >= 2;
        if (keyValue) { parseKeyValueRows(rows,format,out,issues,page); return; }
        List<String> fields = rows.get(0).cells.stream().map(c -> field(c.value.replaceAll("[：:]$", ""))).toList();
        boolean personnel = fields.contains("personName") || fields.stream().anyMatch(NUMBERS::contains);
        if (!personnel) { issue(issues, page, "PERSON_TABLE_HEADER_UNRESOLVED", "人员材料中的表头无法明确识别，需补充结构解析。"); return; }
        if (rows.size() == 1) { issue(issues, page, "PERSON_TABLE_NO_DATA", "人员表格只有表头，尚无可核对记录。"); return; }
        Set<String> seen = new HashSet<>();
        for (int column = 0; column < fields.size(); column++) if (fields.get(column).isBlank()
                && !Set.of("序号", "序列", "序", "备注", "No.", "No").contains(rows.get(0).cells.get(column).value))
            issue(issues, page, "PERSON_TABLE_FIELDS_UNSUPPORTED", "人员表格存在尚未识别含义的列，已提取明确字段，其余列覆盖待补充。");
        for (String field : fields) if (!field.isBlank() && !seen.add(field)) {
            issue(issues, page, "PERSON_TABLE_DUPLICATE_HEADER", "人员表格存在重复字段列，无法确定同一人的字段归属。"); return;
        }
        String kind = infer(fields);
        for (int n = 1; n < rows.size(); n++) {
            TableRow row = rows.get(n);
            if (row.cells.size() != fields.size()) { issue(issues, page, "PERSON_TABLE_COLUMNS_MISMATCH", "人员表格行列数量不一致，该行未提取。"); continue; }
            List<RawField> values = new ArrayList<>();
            for (int c = 0; c < fields.size(); c++) {
                String field = fields.get(c); RawField cell = row.cells.get(c);
                if (field.isBlank()) continue;
                if (cell.value.isBlank()) { issue(issues, page, "PERSON_FIELD_EMPTY", "人员表格明确字段为空，未沿用前一行人员信息。"); continue; }
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
            if (field.isBlank()) { issue(issues,page,"PERSON_TABLE_FIELDS_UNSUPPORTED","证件键值表含尚未识别的字段，覆盖待补充。"); continue; }
            if (field.equals("personName") && values.stream().anyMatch(v -> v.field.equals("personName"))) {
                out.add(new RawRecord(start,end,infer(values.stream().map(RawField::field).toList()),format,values));
                values=new ArrayList<>();start=row.start;
            }
            end=row.end;
            if (cell.value.isBlank()) {issue(issues,page,"PERSON_FIELD_EMPTY","证件键值表明确字段为空，未补写字段值。");continue;}
            if (cell.value.length()>300) {issue(issues,page,"PERSON_FIELD_UNRESOLVED","证件键值表字段超过解析限制，未猜测值。");continue;}
            values.add(new RawField(field,cell.raw,cell.value,cell.start,cell.end));
        }
        if (!values.isEmpty()) out.add(new RawRecord(start,end,infer(values.stream().map(RawField::field).toList()),format,values));
    }

    private static void publish(Document document, Page page, RawRecord record, List<Occurrence> out, List<Map<String,Object>> outputIssues) {
        List<Map<String,Object>> issues = new ArrayList<>();
        String name = single(record.fields, "personName"), role = single(record.fields, "role");
        String credentialName = single(record.fields, "certificateName");
        String kind = record.type;
        if (!credentialName.isBlank() && !type(credentialName).equals("UNKNOWN")) kind = type(credentialName);
        if (kind.equals("UNKNOWN")) kind = infer(record.fields.stream().map(RawField::field).toList());
        List<RawField> resolved = new ArrayList<>();
        for (RawField raw : record.fields) {
            String field = raw.field.equals("genericNumber") ? numberField(kind) : raw.field;
            if(field.equals("classification")&&(kind.equals("DRIVING_LICENSE")||record.fields.stream().anyMatch(f->Set.of("drivingLicenseNumber","drivingFileNumber","vehicleClass").contains(f.field))))
                field="vehicleClass";
            if(field.equals("archiveNumber")&&(kind.equals("DRIVING_LICENSE")||record.fields.stream().anyMatch(f->Set.of("drivingLicenseNumber","drivingFileNumber","vehicleClass").contains(f.field))))
                field="drivingFileNumber";
            if (field.isBlank()) { issue(issues, page, "CREDENTIAL_TYPE_UNKNOWN", "发现证号或编号，但证件类别不明确，未混同不同证件的号码。"); continue; }
            resolved.add(new RawField(field, raw.raw, raw.value, raw.start, raw.end, raw.association));
        }
        boolean ambiguousRecord = resolved.stream().filter(f -> NUMBERS.contains(f.field)).map(RawField::field).distinct()
                .anyMatch(f -> resolved.stream().filter(v -> v.field.equals(f)).map(RawField::value).distinct().count() > 1);
        if (ambiguousRecord) issue(issues, page, "PERSON_RECORD_AMBIGUOUS", "同一记录范围出现多个不同的同类证号，已保留原文但未猜测人员归属。");
        if (name.isBlank()) issue(issues, page, "PERSON_NAME_MISSING", "人员记录缺少明确姓名，已保留字段但未关联到其他人员。");
        else if (!usableName(name)) issue(issues, page, "PERSON_NAME_AMBIGUOUS", "姓名字段疑似包含多人、掩码或无法确定的内容，未用于确定身份。");
        if (kind.equals("UNKNOWN")) issue(issues, page, "CREDENTIAL_TYPE_UNKNOWN", "人员材料缺少明确证件类型，需复核字段类别。");
        if (!kind.equals("PERSON_ROSTER") && resolved.stream().noneMatch(f -> NUMBERS.contains(f.field) && !f.field.equals("drivingFileNumber")))
            issue(issues, page, "CREDENTIAL_NUMBER_MISSING", "证件记录缺少明确证件号码，覆盖尚未完成。");
        if (kind.equals("PERSON_CERTIFICATE") && credentialName.isBlank())
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
        base.put("personNameStatus", name.isBlank() ? "MISSING" : usableName(name) && !ambiguousRecord ? "EXPLICIT" : "AMBIGUOUS");
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
            String quote = page.text().substring(record.start, record.end).strip();
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
            if(reason.isBlank()){kept.add(raw);continue;}
            String recordKey=document.id()+":v"+document.version()+":"+document.sha256()+":"+page.locationType()
                    +":p"+page.number()+":person-record:"+record.start;
            TableRelationIndex.Association relation=record.fields.stream().map(RawField::association).filter(Objects::nonNull).findFirst().orElse(null);
            if(relation!=null)recordKey+=":table-row:"+relation.row();
            String oldId=digest(recordKey+"|"+raw.field+"|"+raw.start+"|"+raw.end);
            String anchor="person-record:"+record.start+":field:"+raw.field+":chars:"+raw.start+"-"+raw.end;
            Evidence evidence=new Evidence(document.id(),document.name(),page.number(),page.locationType(),
                    page.text().substring(record.start,record.end).strip(),anchor,
                    Objects.toString(page.parser(),"").toUpperCase(Locale.ROOT).contains("OCR")?0d:1d);
            Map<String,Object> rejection=new LinkedHashMap<>();
            rejection.put("code","PERSON_EXTRACTION_NOISE_REJECTED");rejection.put("category","EXTRACTION_NOISE");
            rejection.put("policyVersion",CONSISTENCY_FILTER_VERSION);rejection.put("rejectionId",digest(CONSISTENCY_FILTER_VERSION+"|"+oldId));
            rejection.put("oldOccurrenceId",oldId);rejection.put("field",raw.field);rejection.put("reasonCode",reason);
            rejection.put("reason",switch(reason){
                case "IMAGE_AGE_ESTIMATE" -> "头像年龄估计或图像分析说明不是原文登记年龄，已从一致性事实中剔除。";
                case "PERSON_NAME_DESCRIPTION" -> "证书说明、签名提示或图像描述不是明确姓名，已从一致性事实中剔除。";
                case "ROLE_DESCRIPTION_OR_HEADER" -> "表头片段、岗位职责说明或图像描述不是明确人员职务，已从一致性事实中剔除。";
                default -> "字段标签后接的是标题或排版片段，未将其作为人员字段值。";
            });
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
        String value=raw.value.strip();
        if(raw.field.equals("age")&&Pattern.compile("估计|估算|推断|面部|发际线|皮肤|背景色|^(?:区间|段)[：:]|^精确估算").matcher(value).find())
            return "IMAGE_AGE_ESTIMATE";
        if(raw.field.equals("personName")) {
            if(value.matches(">\\s*[-—–]")
                    ||value.matches("(?:信息|签名|印章)[：:。*（(].*")
                    ||Set.of("信息","签名","印章").contains(value)&&record.format.equals("TEXT")
                        &&Pattern.compile("(?:姓名|持证人)(?:\\*\\*|__)?$").matcher(page.text().substring(Math.max(0,raw.start-20),raw.start)).find()
                    ||Pattern.compile("照片|头像|证件照|免冠|^通过国家统一组织的考试|^(?:已|已经)通过.*考核|^具备.*执业能力").matcher(value).find())
                return "PERSON_NAME_DESCRIPTION";
        }
        if(raw.field.equals("role")) {
            if(value.matches("(?is)(?:Detection\\s+Patterns|patterns\\s*[—–-]).*")
                    ||Pattern.compile("^(?:职责|的具体职责|因显示空间限制|培训等证书|配备具有相应)").matcher(value).find()
                    ||record.format.equals("TEXT")&&Set.of("发包人及","号").contains(value))
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
        List<String> values = fields.stream().filter(f -> f.field.equals(field)).map(f->normalize(field,f.value)).distinct().toList();
        return values.size() == 1 ? values.get(0) : "";
    }
    private static boolean usableName(String value) {
        return !value.isBlank() && value.length() <= 80 && !Pattern.compile("[*/＊×，,、;；|\\d]|(?:未知|不详|未提供)").matcher(value).find()
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
        if(field.equals("personName")&&value.strip().matches("[\\p{IsHan}·•](?:[\\s\\p{Z}]+[\\p{IsHan}·•]){1,29}")) {
            String compact=value.replaceAll("[\\s\\p{Z}]+","");if(compact.matches("[\\p{IsHan}·•]{2,30}"))return compact;
        }
        if (field.equals("gender")) return switch(value.toLowerCase(Locale.ROOT)) { case "male","m" -> "男"; case "female","f" -> "女"; default -> value; };
        return value.replaceAll("[\\s\\p{Z}]+", " ").strip();
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
        return switch(kind) { case "ID_CARD" -> "idNumber"; case "DRIVING_LICENSE" -> "drivingLicenseNumber"; case "PASSPORT" -> "passportNumber"; case "PERSON_CERTIFICATE" -> "certificateNumber"; default -> ""; };
    }
    private static void labels(String field, String... labels) { for (String label : labels) LABELS.put(label.toLowerCase(Locale.ROOT),field); }
    private static String field(String label) {
        String value=label.strip().replaceAll("^(?:\\*\\*|__)|(?:\\*\\*|__)$","").strip().toLowerCase(Locale.ROOT);
        if(value.matches("[\\p{IsHan}\\s\\p{Z}]+"))value=value.replaceAll("[\\s\\p{Z}]+","");
        return LABELS.getOrDefault(value, "");
    }
    private static String labelPattern(String label) {
        if(!label.matches("[\\p{IsHan}]+"))return Pattern.quote(label);
        return label.codePoints().mapToObj(c->Pattern.quote(new String(Character.toChars(c)))).reduce((a,b)->a+"[ \\t\\u3000]*"+b).orElseThrow();
    }
    private static boolean knownLabel(String value) {
        if(!field(value).isBlank())return true;
        String text=value+":";Matcher labels=LABEL.matcher(text);int end=0,count=0;Set<String> fields=new HashSet<>();
        while(labels.find()){if(!text.substring(end,labels.start()).isBlank())return false;fields.add(field(labels.group(1)));count++;end=labels.end();}
        return count>=2&&fields.size()==1&&!fields.contains("")&&text.substring(end).isBlank();
    }
    private static int[] unwrap(String text,int start,int end) {
        for(String marker:List.of("**","__","`")) if(end-start>=marker.length()*2&&text.startsWith(marker,start)&&text.substring(start,end).endsWith(marker)) {
            start+=marker.length();end-=marker.length();while(start<end&&Character.isWhitespace(text.charAt(start)))start++;while(end>start&&Character.isWhitespace(text.charAt(end-1)))end--;break;
        }
        return new int[]{start,end};
    }
    private static String visible(String value) {
        return value.replaceAll("(?i)<br\\s*/?>", " ").replace("&nbsp;", " ").replace("&#160;", " ")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replaceAll("[\\s\\p{Z}]+", " ").strip();
    }
    private static int count(Pattern pattern, String value) { return (int)pattern.matcher(value).results().count(); }
    private static void issue(List<Map<String,Object>> issues, Page page, String code, String reason) {
        if (issues.stream().noneMatch(i -> code.equals(i.get("code")))) issues.add(Map.of("code",code,"page",page.number(),"locationType",page.locationType(),"reason",reason));
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private record Token(int start, int end, String field, String label) { }
    private record Title(int start, int end, String raw, String type) { }
    private record RawField(String field, String raw, String value, int start, int end, TableRelationIndex.Association association) {
        RawField(String field,String raw,String value,int start,int end){this(field,raw,value,start,end,null);}
    }
    private record RawRecord(int start, int end, String type, String format, List<RawField> fields) { }
    private record TableRow(int start, int end, List<RawField> cells) { }
}
