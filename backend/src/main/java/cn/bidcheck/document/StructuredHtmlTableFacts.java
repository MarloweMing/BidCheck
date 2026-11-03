package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;

/**
 * A deliberately small parser for the explicit social-insurance grid returned by PDF OCR.
 * It validates the supplied structure, not its visual accuracy or the authenticity of a payment.
 * It never upgrades OCR HTML to native geometry, a person identity, or continuous-month evidence.
 */
public final class StructuredHtmlTableFacts {
    public static final String VERSION = "structured-html-insurance-1";
    public static final String SCHEMA = "SOCIAL_INSURANCE_TABLE_V1";
    public static final int MAX_TABLE_CHARACTERS = 10_000;
    private static final int COLUMNS = 7, MAX_ROWS = 200, MAX_CELLS = 1000;
    private static final Pattern TABLE_HINT = Pattern.compile("(?is)<table\\b");
    private static final Pattern TABLE_OPEN = Pattern.compile("(?is)<table\\b[^<>]*>");
    private static final Pattern TABLE_CLOSE = Pattern.compile("(?is)</table\\s*>");
    private static final Pattern TAG = Pattern.compile("(?is)<(/?)([a-z][a-z0-9]*)([^<>]*)>");
    private static final Pattern ATTRIBUTE = Pattern.compile("\\s+([A-Za-z_:][-A-Za-z0-9_:]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");
    private static final List<String> FIELDS = cn.bidcheck.compat.Java8Api.listOf("insuranceSequence", "insuredPersonName", "socialSecurityNumber", "insuranceType", "contributionStart", "contributionEnd", "paidMonths");
    private StructuredHtmlTableFacts() { }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Result {
        private final boolean complete;
        private final String schema;
        private final List<Fact> facts;
        private final List<String> issues;
        public boolean complete() { return complete; }
        public String schema() { return schema; }
        public List<Fact> facts() { return facts; }
        public List<String> issues() { return issues; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Result that = (Result) other; return java.util.Objects.equals(this.complete, that.complete) && java.util.Objects.equals(this.schema, that.schema) && java.util.Objects.equals(this.facts, that.facts) && java.util.Objects.equals(this.issues, that.issues); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.complete); hash = 31 * hash + java.util.Objects.hashCode(this.schema); hash = 31 * hash + java.util.Objects.hashCode(this.facts); hash = 31 * hash + java.util.Objects.hashCode(this.issues); return hash; }
        @Override public String toString() { return "Result[" + "complete=" + complete + ", schema=" + schema + ", facts=" + facts + ", issues=" + issues + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Result(@com.fasterxml.jackson.annotation.JsonProperty("complete") boolean complete, @com.fasterxml.jackson.annotation.JsonProperty("schema") String schema, @com.fasterxml.jackson.annotation.JsonProperty("facts") List<Fact> facts, @com.fasterxml.jackson.annotation.JsonProperty("issues") List<String> issues) { facts = cn.bidcheck.compat.Java8Api.listCopyOf(facts); issues = cn.bidcheck.compat.Java8Api.listCopyOf(issues);
            this.complete = complete; this.schema = schema; this.facts = facts; this.issues = issues;
        }
    }

    public static Result analyze(Document document, Page page) {
        if (page != null && page.text() != null && !TABLE_HINT.matcher(page.text()).find())
            return new Result(false, "", cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf());
        if (document == null || page == null || page.text() == null || document.pages() == null
                || document.pages().stream().noneMatch(p -> p.number() == page.number() && Objects.equals(p.locationType(), page.locationType())
                && Objects.equals(p.text(), page.text()))) return rejected("HTML_TABLE_SOURCE_INVALID");
        if (page.warning() != null && (page.warning().contains("[OCR未完成]") || page.warning().contains("[OCR_DEFERRED]")))
            return rejected("HTML_TABLE_SOURCE_INCOMPLETE");
        if (page.text().length() > 50_000) return rejected("HTML_TABLE_LIMIT");
        try {
            Matcher open = TABLE_OPEN.matcher(page.text()), close = TABLE_CLOSE.matcher(page.text());
            if (!open.find()) return rejected("HTML_TABLE_NOT_PRESENT");
            int start = open.start();
            if (open.find() || !close.find()) return rejected("HTML_TABLE_INCOMPLETE");
            int end = close.end();
            if (close.find() || end <= start) return rejected("HTML_TABLE_INCOMPLETE");
            String html = page.text().substring(start, end);
            if (html.length() > MAX_TABLE_CHARACTERS) return rejected("HTML_TABLE_LIMIT");
            List<List<Cell>> rows = parse(html);
            Cell[][] grid = grid(rows);
            int headerRows = headers(grid);
            if (rows.size() <= headerRows) return rejected("HTML_TABLE_NO_DATA");
            List<Fact> facts = new ArrayList<>(); LinkedHashSet<String> issues = new LinkedHashSet<>();
            for (int r = headerRows; r < grid.length; r++) {
                Cell sequence = grid[r][0], name = grid[r][1], number = grid[r][2];
                if (sequence.row != name.row || name.row != number.row || sequence.rowSpan != name.rowSpan
                        || name.rowSpan != number.rowSpan || sequence.row < headerRows)
                    throw invalid("HTML_TABLE_IDENTITY_SPAN_MISMATCH");
                for (int c = 0; c < COLUMNS; c++) {
                    Cell cell = grid[r][c];
                    if (cell.columnSpan != 1 || c >= 3 && (cell.row != r || cell.rowSpan != 1))
                        throw invalid("HTML_TABLE_DATA_SPAN_UNSUPPORTED");
                }
                boolean anonymous = cn.bidcheck.compat.Java8Api.isBlank(sequence.value) && cn.bidcheck.compat.Java8Api.isBlank(name.value) && cn.bidcheck.compat.Java8Api.isBlank(number.value);
                if (anonymous) issues.add("存在身份信息为空的缴费记录，未关联到任何人员，需人工核对。");
                else if (cn.bidcheck.compat.Java8Api.isBlank(sequence.value) || cn.bidcheck.compat.Java8Api.isBlank(name.value) || cn.bidcheck.compat.Java8Api.isBlank(number.value))
                    throw invalid("HTML_TABLE_PARTIAL_IDENTITY");
                if (!anonymous && !sequence.value.matches("[0-9]{1,5}")) throw invalid("HTML_TABLE_SEQUENCE_UNSUPPORTED");
                // Labels constrain the interpretation. Dates and counts remain exactly what the source
                // states; they are not expanded into months, attributed to a role, or treated as a total.
                if (cn.bidcheck.compat.Java8Api.isBlank(grid[r][3].value) || cn.bidcheck.compat.Java8Api.isBlank(grid[r][6].value)) throw invalid("HTML_TABLE_REQUIRED_VALUE_MISSING");
                if (!grid[r][6].value.matches("[0-9]{1,4}")) throw invalid("HTML_TABLE_MONTH_VALUE_UNSUPPORTED");
                for (Cell cell : rows.get(r)) {
                    if (cn.bidcheck.compat.Java8Api.isBlank(cell.value)) continue;
                    if (cell.value.length() > 200 || cell.raw.length() > 500) throw invalid("HTML_TABLE_VALUE_LIMIT");
                    // One occurrence for each physical source cell. Rowspan inheritance is validated
                    // by the grid above, but must never multiply a name into several extracted facts.
                    String rowKey = document.id() + ":v" + document.version() + ":" + document.sha256() + ":" + page.locationType()
                            + ":p" + page.number() + ":table" + start + ":identity" + (sequence.row + 1) + ":row" + (r + 1);
                    String anchor = "html-table:" + start + ":r" + (cell.row + 1) + ":c" + (cell.column + 1)
                            + ":chars:" + (start + cell.start) + "-" + (start + cell.end);
                    Evidence evidence = new Evidence(document.id(), document.name(), page.number(), page.locationType(), html, anchor,
                            page.parser() != null && page.parser().contains("OCR") ? 0d : 1d);
                    facts.add(new Fact("INSURANCE_RECORD", rowKey, FIELDS.get(cell.column), cell.raw, cell.value, evidence));
                }
            }
            if (facts.isEmpty()) return rejected("HTML_TABLE_NO_DATA");
            return new Result(true, SCHEMA, facts, new ArrayList<>(issues));
        } catch (Invalid invalid) { return rejected(invalid.getMessage()); }
    }

    private static List<List<Cell>> parse(String html) {
        List<List<Cell>> rows = new ArrayList<>(); Deque<String> stack = new ArrayDeque<>();
        Matcher tags = TAG.matcher(html); int at = 0, cells = 0; Cell pending = null;
        while (tags.find()) {
            String between = html.substring(at, tags.start());
            if (pending == null && !cn.bidcheck.compat.Java8Api.isBlank(between) || between.contains("<") || between.contains(">")) throw invalid("HTML_TABLE_INCOMPLETE");
            String tag = tags.group(2).toLowerCase(Locale.ROOT), tail = tags.group(3);
            boolean closing = !tags.group(1).isEmpty();
            if (tag.equals("br") && !closing && pending != null && tail.matches("\\s*/?\\s*")) { at = tags.end(); continue; }
            if (!cn.bidcheck.compat.Java8Api.setOf("table", "thead", "tbody", "tfoot", "tr", "td", "th").contains(tag)) throw invalid("HTML_TABLE_TAG_UNSUPPORTED");
            if (closing) {
                if (!cn.bidcheck.compat.Java8Api.isBlank(tail) || stack.isEmpty() || !stack.pop().equals(tag)) throw invalid("HTML_TABLE_INCOMPLETE");
                if (tag.equals("td") || tag.equals("th")) {
                    if (pending == null) throw invalid("HTML_TABLE_INCOMPLETE");
                    pending.end = tags.start(); pending.raw = cn.bidcheck.compat.Java8Api.strip(html.substring(pending.start, pending.end));
                    pending.value = visible(pending.raw); rows.get(rows.size() - 1).add(pending); pending = null;
                }
                if (tag.equals("tr") && rows.get(rows.size() - 1).isEmpty()) throw invalid("HTML_TABLE_EMPTY_ROW");
            } else {
                Map<String, String> attributes = attributes(tail);
                String parent = stack.peek();
                switch (tag) {case "table":{ if (parent != null || !rows.isEmpty()) throw invalid("HTML_TABLE_NESTED"); } break;case "thead":case "tbody":case "tfoot":{ if (!"table".equals(parent)) throw invalid("HTML_TABLE_INCOMPLETE"); } break;case "tr":{
                        if (!cn.bidcheck.compat.Java8Api.setOf("table", "thead", "tbody", "tfoot").contains(Objects.toString(parent, ""))) throw invalid("HTML_TABLE_INCOMPLETE");
                        if (rows.size() >= MAX_ROWS) throw invalid("HTML_TABLE_LIMIT"); rows.add(new ArrayList<>());
                    } break;case "td":case "th":{
                        if (!"tr".equals(parent) || pending != null) throw invalid("HTML_TABLE_INCOMPLETE");
                        if (++cells > MAX_CELLS) throw invalid("HTML_TABLE_LIMIT");
                        pending = new Cell(rows.size() - 1, span(attributes, "rowspan", MAX_ROWS), span(attributes, "colspan", COLUMNS), tags.end());
                    } break;}
                if (!cn.bidcheck.compat.Java8Api.setOf("td", "th").contains(tag) && (attributes.containsKey("rowspan") || attributes.containsKey("colspan"))) throw invalid("HTML_TABLE_ATTRIBUTE_INVALID");
                stack.push(tag);
            }
            at = tags.end();
        }
        if (!stack.isEmpty() || pending != null || !cn.bidcheck.compat.Java8Api.isBlank(html.substring(at)) || rows.isEmpty()) throw invalid("HTML_TABLE_INCOMPLETE");
        return rows;
    }

    private static Map<String, String> attributes(String text) {
        Map<String, String> result = new HashMap<>(); Matcher matcher = ATTRIBUTE.matcher(text); int at = 0;
        while (matcher.find()) {
            if (matcher.start() != at) throw invalid("HTML_TABLE_ATTRIBUTE_INVALID");
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = Objects.toString(matcher.group(2), Objects.toString(matcher.group(3), Objects.toString(matcher.group(4), "")));
            if (result.putIfAbsent(key, value) != null) throw invalid("HTML_TABLE_ATTRIBUTE_INVALID");
            at = matcher.end();
        }
        if (!cn.bidcheck.compat.Java8Api.isBlank(text.substring(at))) throw invalid("HTML_TABLE_ATTRIBUTE_INVALID");
        return result;
    }

    private static int span(Map<String, String> attributes, String name, int limit) {
        String value = attributes.getOrDefault(name, "1");
        if (!value.matches("[1-9][0-9]{0,2}")) throw invalid("HTML_TABLE_SPAN_INVALID");
        int size = Integer.parseInt(value); if (size > limit) throw invalid("HTML_TABLE_SPAN_INVALID"); return size;
    }

    private static Cell[][] grid(List<List<Cell>> rows) {
        Cell[][] grid = new Cell[rows.size()][COLUMNS];
        for (int row = 0; row < rows.size(); row++) {
            int column = 0;
            for (Cell cell : rows.get(row)) {
                while (column < COLUMNS && grid[row][column] != null) column++;
                if (column + cell.columnSpan > COLUMNS || row + cell.rowSpan > rows.size()) throw invalid("HTML_TABLE_SPAN_OUTSIDE");
                cell.column = column;
                for (int r = row; r < row + cell.rowSpan; r++) for (int c = column; c < column + cell.columnSpan; c++) {
                    if (grid[r][c] != null) throw invalid("HTML_TABLE_SPAN_COLLISION"); grid[r][c] = cell;
                }
                column += cell.columnSpan;
            }
            if (Arrays.stream(grid[row]).anyMatch(Objects::isNull)) throw invalid("HTML_TABLE_COLUMN_COUNT");
        }
        return grid;
    }

    private static int headers(Cell[][] grid) {
        List<String> one = cn.bidcheck.compat.Java8Api.listOf("序号", "姓名", "社会保障号码", "险种", "起始年月", "截止年月", "本单位实际缴费月数");
        boolean single = true;
        for (int c = 0; c < COLUMNS; c++) single &= grid[0][c].value.equals(one.get(c)) && grid[0][c].rowSpan == 1 && grid[0][c].columnSpan == 1;
        if (single) return 1;
        if (grid.length < 3) throw invalid("HTML_TABLE_UNSUPPORTED_SCHEMA");
        for (int c : new int[]{0, 1, 2, 3, 6}) {
            Cell cell = grid[0][c];
            if (!cell.value.equals(one.get(c)) || cell.rowSpan != 2 || cell.columnSpan != 1 || grid[1][c] != cell)
                throw invalid("HTML_TABLE_UNSUPPORTED_SCHEMA");
        }
        Cell group = grid[0][4];
        if (group != grid[0][5] || group.rowSpan != 1 || group.columnSpan != 2 || !group.value.equals("缴费情况"))
            throw invalid("HTML_TABLE_UNSUPPORTED_SCHEMA");
        for (int c = 4; c <= 5; c++) {
            Cell child = grid[1][c];
            if (child.row != 1 || child.rowSpan != 1 || child.columnSpan != 1 || !child.value.equals(one.get(c)))
                throw invalid("HTML_TABLE_UNSUPPORTED_SCHEMA");
        }
        return 2;
    }

    private static String visible(String raw) {
        String value = raw.replaceAll("(?i)<br\\s*/?>", " ");
        if (value.indexOf('<') >= 0 || value.indexOf('>') >= 0 || value.indexOf('\0') >= 0) throw invalid("HTML_TABLE_CELL_MARKUP_UNSUPPORTED");
        // Decode only bounded, explicitly supported text escapes. Nothing is executed or fetched.
        value = value.replace("&nbsp;", " ").replace("&#160;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
        return cn.bidcheck.compat.Java8Api.strip(value.replaceAll("[\\s\\p{Z}]+", " "));
    }
    private static Result rejected(String issue) { return new Result(false, SCHEMA, cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(issue)); }
    private static Invalid invalid(String issue) { return new Invalid(issue); }
    private static final class Invalid extends RuntimeException { Invalid(String issue) { super(issue); } }
    private static final class Cell {
        final int row, rowSpan, columnSpan, start; int column, end; String raw = "", value = "";
        Cell(int row, int rowSpan, int columnSpan, int start) { this.row = row; this.rowSpan = rowSpan; this.columnSpan = columnSpan; this.start = start; }
    }
}
