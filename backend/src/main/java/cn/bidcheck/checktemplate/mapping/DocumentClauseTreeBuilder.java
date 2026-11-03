package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import cn.bidcheck.compat.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a source-bound clause tree from the text already persisted for a tender document. */
final class DocumentClauseTreeBuilder {
    static final String VERSION = "document-clause-tree-3";

    private static final Pattern HEADING = Pattern.compile(
            "^(?:#{1,6}\\s*|第[一二三四五六七八九十百0-9]+[章节部分]).{0,80}$");
    private static final Pattern LIST = Pattern.compile(
            "^\\s*((?:[0-9]+(?:\\.[0-9]+)+(?:[.)、])?|[0-9]+[.)、]|"
                    + "[（(][0-9一二三四五六七八九十]+[）)]|[一二三四五六七八九十]+[、.]|[-*•·]))\\s*(.+)$");
    private static final Pattern LOT = Pattern.compile("(第?[一二三四五六七八九十百0-9]+标段|第?[一二三四五六七八九十百0-9]+包|包件\\s*[一二三四五六七八九十百0-9]+)");
    private static final Pattern TABLE_LINE = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?(?:\\s*:?-{2,}:?\\s*\\|)+\\s*$");

    List<ClauseNode> build(Document document) {
        if (document == null) throw new IllegalArgumentException("document is required");
        if (!"TENDER".equals(document.role())) throw new IllegalArgumentException("only tender documents can be mapped");
        if (document.pages() == null || document.pages().isEmpty()) return cn.bidcheck.compat.Java8Api.listOf();

        List<MutableNode> documentChildren = new ArrayList<>();
        Deque<SectionFrame> sections = new ArrayDeque<>();
        ChecklistFrame checklist = null;
        TableCarry tableCarry = null;
        int previousPage = Integer.MIN_VALUE;

        for (Page page : document.pages()) {
            List<Block> blocks = blocks(page);
            boolean adjacentPage = previousPage != Integer.MIN_VALUE && page.number() == previousPage + 1;
            if (!adjacentPage) {
                checklist = null;
                tableCarry = null;
            }
            for (int index = 0; index < blocks.size(); index++) {
                Block block = blocks.get(index);
                boolean firstOnPage = index == 0;
                boolean lastOnPage = index + 1 == blocks.size();
                switch (block.kind()) {case HEADING:{
                        checklist = null;
                        tableCarry = null;
                        while (!sections.isEmpty() && sections.peekLast().level() >= block.headingLevel()) {
                            sections.removeLast();
                        }
                        MutableNode heading = node(document, page, block.line(), ClauseNode.Kind.SECTION,
                                stripMarkdown(block.line().trimmed()), block.marker(), null, block.sequence());
                        children(documentChildren, sections).add(heading);
                        sections.addLast(new SectionFrame(block.headingLevel(), heading));
                        if (checklistLead(heading.text)) checklist = new ChecklistFrame(heading, 0);
                    } break;case LIST_ITEM:{
                        tableCarry = null;
                        MutableNode item = node(document, page, block.line(), ClauseNode.Kind.LIST_ITEM,
                                "", block.marker(), null, block.sequence());
                        if (checklist != null && (checklist.listLevel() == 0
                                || block.headingLevel() > checklist.listLevel())) {
                            checklist.node().children.add(item);
                        } else {
                            checklist = null;
                            children(documentChildren, sections).add(item);
                        }
                        if (checklistLead(item.text)) {
                            checklist = new ChecklistFrame(item, block.headingLevel());
                        }
                        if (!lastOnPage && blocks.get(index + 1).kind() != BlockKind.LIST_ITEM) checklist = null;
                    } break;case PARAGRAPH:{
                        checklist = null;
                        tableCarry = null;
                        MutableNode paragraph = node(document, page, block.line(), ClauseNode.Kind.PARAGRAPH,
                                "", "", null, block.sequence());
                        children(documentChildren, sections).add(paragraph);
                        if (checklistLead(paragraph.text)) checklist = new ChecklistFrame(paragraph, 0);
                    } break;case TABLE:{
                        checklist = null;
                        String parentIdentity = parentIdentity(sections);
                        boolean continuation = firstOnPage && adjacentPage && tableCarry != null
                                && tableCarry.pageNumber() == previousPage
                                && tableCarry.parentIdentity().equals(parentIdentity)
                                && compatible(tableCarry.headers(), block.tableHeaders(), block.tableHasSeparator());
                        List<String> headers = continuation && !block.tableHasSeparator()
                                ? tableCarry.headers() : block.tableHeaders();
                        String tableId = continuation ? tableCarry.tableId()
                                : document.id() + ":page:" + page.number() + ":table:" + block.sequence();
                        String title = continuation ? tableCarry.title() : currentSectionTitle(sections);
                        MutableNode table = table(document, page, block, tableId, title, headers);
                        children(documentChildren, sections).add(table);
                        tableCarry = lastOnPage
                                ? new TableCarry(tableId, title, headers, parentIdentity, page.number()) : null;
                    } break;}
            }
            if (blocks.isEmpty()) {
                checklist = null;
                tableCarry = null;
            } else {
                BlockKind lastKind = blocks.get(blocks.size() - 1).kind();
                if (lastKind != BlockKind.LIST_ITEM && lastKind != BlockKind.HEADING
                        && !(lastKind == BlockKind.PARAGRAPH && checklist != null)) checklist = null;
                if (lastKind != BlockKind.TABLE) tableCarry = null;
            }
            previousPage = page.number();
        }

        Page first = document.pages().get(0);
        SourceRef rootSource = source(document, first, "", 0, 0);
        ClauseNode.LocalContext rootContext = new ClauseNode.LocalContext(
                "", "", null, "", "", "投标人", "TENDER",
                ClauseNode.Boundary.CURRENT_TENDER, cn.bidcheck.compat.Java8Api.listOf());
        ClauseNode root = new ClauseNode(document.id() + ":document", ClauseNode.Kind.DOCUMENT, "",
                rootSource, rootContext, cn.bidcheck.compat.Java8Api.toList(documentChildren.stream().map(MutableNode::freeze)));
        return cn.bidcheck.compat.Java8Api.listOf(root);
    }

    private List<Block> blocks(Page page) {
        String text = page.text() == null ? "" : page.text();
        List<Line> lines = lines(text);
        List<Block> result = new ArrayList<>();
        int sequence = 0;
        for (int index = 0; index < lines.size();) {
            Line line = lines.get(index);
            if (cn.bidcheck.compat.Java8Api.isBlank(line.trimmed())) { index++; continue; }
            if (TABLE_LINE.matcher(line.trimmed()).matches()) {
                int end = index;
                List<Line> tableLines = new ArrayList<>();
                while (end < lines.size() && TABLE_LINE.matcher(lines.get(end).trimmed()).matches()) {
                    tableLines.add(lines.get(end));
                    end++;
                }
                List<Line> content = cn.bidcheck.compat.Java8Api.toList(tableLines.stream()
                        .filter(value -> !TABLE_SEPARATOR.matcher(value.trimmed()).matches()));
                boolean separator = tableLines.stream()
                        .anyMatch(value -> TABLE_SEPARATOR.matcher(value.trimmed()).matches());
                List<String> headers = content.isEmpty() ? cn.bidcheck.compat.Java8Api.listOf() : cells(content.get(0).trimmed());
                if (!content.isEmpty()) {
                    Line span = join(text, tableLines);
                    result.add(new Block(BlockKind.TABLE, span, "", 0, sequence++, content, headers, separator));
                }
                index = end;
                continue;
            }
            if (heading(line.trimmed())) {
                result.add(new Block(BlockKind.HEADING, line, "", headingLevel(line.trimmed()),
                        sequence++, cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(), false));
                index++;
                continue;
            }
            Matcher marker = LIST.matcher(line.raw());
            if (marker.matches()) {
                String body = marker.group(2).trim();
                int bodyOffset = Math.max(0, line.raw().indexOf(marker.group(2)));
                Line bodyLine = new Line(body, line.start() + bodyOffset,
                        line.start() + bodyOffset + body.length());
                result.add(new Block(BlockKind.LIST_ITEM, bodyLine, marker.group(1), listLevel(marker.group(1)),
                        sequence++, cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(), false));
                index++;
                continue;
            }

            int end = index + 1;
            while (end < lines.size() && !cn.bidcheck.compat.Java8Api.isBlank(lines.get(end).trimmed())
                    && !heading(lines.get(end).trimmed())
                    && !LIST.matcher(lines.get(end).raw()).matches()
                    && !TABLE_LINE.matcher(lines.get(end).trimmed()).matches()) end++;
            result.add(new Block(BlockKind.PARAGRAPH, join(text, lines.subList(index, end)), "", 0,
                    sequence++, cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(), false));
            index = end;
        }
        return result;
    }

    private MutableNode table(Document document, Page page, Block block, String tableId, String title,
                              List<String> headers) {
        List<MutableNode> rows = new ArrayList<>();
        int rowIndex = 0;
        for (Line line : block.tableLines()) {
            int start = line.start() + Math.max(0, line.raw().indexOf(line.trimmed()));
            Line rowLine = new Line(line.trimmed(), start, line.end());
            ClauseNode.TableContext rowTable = new ClauseNode.TableContext(
                    tableId, title, headers, rowIndex, null, 1, null);
            rows.add(node(document, page, rowLine, ClauseNode.Kind.TABLE_ROW,
                    "", "", rowTable, block.sequence() * 1_000 + rowIndex));
            rowIndex++;
        }
        Line span = block.line();
        String value = exactValue(span);
        int start = exactStart(span), end = start + value.length();
        SourceRef tableSource = source(document, page, value, start, end);
        ClauseNode.TableContext table = new ClauseNode.TableContext(tableId, title, headers,
                null, null, null, null);
        return new MutableNode(document.id() + ":page:" + page.number() + ":table-node:"
                + block.sequence() + ":" + start, ClauseNode.Kind.TABLE, "", tableSource,
                context("", "", table, title), rows);
    }

    private MutableNode node(Document document, Page page, Line line, ClauseNode.Kind kind, String section,
                             String marker, ClauseNode.TableContext table, int sequence) {
        String value = exactValue(line);
        int start = exactStart(line);
        int end = Math.min(line.end(), start + value.length());
        SourceRef source = source(document, page, value, start, end);
        ClauseNode.LocalContext context = context(section, marker, table, value);
        String id = document.id() + ":page:" + page.number() + ":node:" + sequence + ":" + start;
        return new MutableNode(id, kind, value, source, context, new ArrayList<>());
    }

    private ClauseNode.LocalContext context(String section, String marker, ClauseNode.TableContext table,
                                            String text) {
        String combined = (section + " " + text).trim();
        Matcher lot = LOT.matcher(combined);
        String lotScope = lot.find() ? lot.group(1) : "";
        String subject = containsAny(combined, "联合体各方", "联合体成员") ? "CONSORTIUM_MEMBER"
                : containsAny(combined, "联合体牵头人", "牵头人") ? "CONSORTIUM_LEADER"
                : containsAny(combined, "投标人", "供应商", "承包人") ? "BIDDER" : "";
        ClauseNode.Boundary boundary = containsAny(combined, "历史业绩", "类似项目", "业绩证明")
                ? ClauseNode.Boundary.HISTORICAL_MATERIAL : ClauseNode.Boundary.UNSPECIFIED;
        return new ClauseNode.LocalContext(section, marker, table, "", lotScope, subject, "",
                boundary, crossReferences(combined));
    }

    private SourceRef source(Document document, Page page, String quote, int start, int end) {
        String parser = page.parser() == null || cn.bidcheck.compat.Java8Api.isBlank(page.parser()) ? "UNKNOWN" : page.parser().trim();
        String pageText = page.text() == null ? "" : page.text();
        return new SourceRef(document.id(), document.version(), document.sha256(),
                sha256(parser + "|" + VERSION), sha256(pageText + "|" + start + "|" + end + "|" + quote),
                page.locationType() == null || cn.bidcheck.compat.Java8Api.isBlank(page.locationType()) ? "PAGE" : page.locationType(),
                page.number(), quote, start, end);
    }

    private static List<MutableNode> children(List<MutableNode> roots, Deque<SectionFrame> sections) {
        return sections.isEmpty() ? roots : sections.peekLast().node().children;
    }

    private static String parentIdentity(Deque<SectionFrame> sections) {
        return sections.isEmpty() ? "DOCUMENT" : sections.peekLast().node().id;
    }

    private static String currentSectionTitle(Deque<SectionFrame> sections) {
        return sections.isEmpty() ? "" : sections.peekLast().node().text;
    }

    private static boolean compatible(List<String> previous, List<String> current, boolean currentHasSeparator) {
        if (previous.isEmpty()) return !current.isEmpty();
        if (!currentHasSeparator) return true;
        return previous.equals(current);
    }

    private static boolean checklistLead(String value) {
        return containsAny(value, "包括以下", "应包括", "由以下", "下列", "组成", "清单");
    }

    private static List<Line> lines(String text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index <= text.length(); index++) {
            if (index == text.length() || text.charAt(index) == '\n') {
                int end = index;
                if (end > start && text.charAt(end - 1) == '\r') end--;
                lines.add(new Line(text.substring(start, end), start, end));
                start = index + 1;
            }
        }
        return lines;
    }

    private static Line join(String text, List<Line> lines) {
        int start = lines.get(0).start(), end = lines.get(lines.size() - 1).end();
        return new Line(text.substring(start, end), start, end);
    }

    private static List<String> cells(String line) {
        String value = cn.bidcheck.compat.Java8Api.strip(line);
        if (value.startsWith("|")) value = value.substring(1);
        if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
        return cn.bidcheck.compat.Java8Api.toList(Pattern.compile("\\s*\\|\\s*").splitAsStream(value).map(String::trim)
                .filter(item -> !cn.bidcheck.compat.Java8Api.isBlank(item)));
    }

    private static boolean heading(String value) {
        String text = stripMarkdown(value);
        if (cn.bidcheck.compat.Java8Api.isBlank(text) || text.length() > 90) return false;
        if (HEADING.matcher(value).matches()) return true;
        Matcher enumerated = LIST.matcher(value);
        if (enumerated.matches()) return sectionCaption(enumerated.group(2).trim());
        return sectionCaption(text);
    }

    private static int headingLevel(String value) {
        String stripped = cn.bidcheck.compat.Java8Api.stripLeading(value);
        if (stripped.startsWith("#")) {
            int count = 0;
            while (count < stripped.length() && stripped.charAt(count) == '#') count++;
            return Math.max(1, Math.min(6, count));
        }
        String text = stripMarkdown(value);
        if (text.matches("^第[一二三四五六七八九十百0-9]+(?:章|部分).*$")) return 1;
        if (text.matches("^第[一二三四五六七八九十百0-9]+节.*$")) return 2;
        if (text.matches("^[一二三四五六七八九十百]+[、.].*$")) return 2;
        if (text.matches("^[（(][一二三四五六七八九十百0-9]+[）)].*$")) return 4;
        if (text.matches("^[0-9]+[、.].*$")) return 3;
        return 3;
    }

    private static int listLevel(String marker) {
        String value = marker == null ? "" : marker.trim();
        if (value.matches("[0-9]+(?:\\.[0-9]+)+[.)、]?")) {
            return (int) value.chars().filter(character -> character == '.').count() + 1;
        }
        if (value.matches("[（(].*[）)]")) return 2;
        if (value.matches("[-*•·]")) return 3;
        return 1;
    }

    private static boolean sectionCaption(String value) {
        return value.length() <= 45 && value.matches(".*(?:要求|须知|评审标准|评审办法|资格条件|响应文件格式|投标文件组成|审查标准|评分标准|材料清单|投标格式|合同条款|技术规范)$");
    }

    private static String stripMarkdown(String value) {
        return value.replaceFirst("^#{1,6}\\s*", "").trim();
    }

    private static List<String> crossReferences(String text) {
        List<String> refs = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?:见|按照|依据|参照)(第[一二三四五六七八九十百0-9]+[章节条]|附件\\s*[一二三四五六七八九十百0-9]+)").matcher(text);
        while (matcher.find()) refs.add(matcher.group(1));
        return cn.bidcheck.compat.Java8Api.toList(refs.stream().distinct());
    }

    private static boolean containsAny(String value, String... candidates) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) if (lower.contains(candidate.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static int exactStart(Line line) {
        String value = exactValue(line);
        return line.start() + Math.max(0, line.raw().indexOf(value));
    }

    private static String exactValue(Line line) {
        return line.trimmed();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private enum BlockKind { HEADING, LIST_ITEM, PARAGRAPH, TABLE }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Block {
        private final BlockKind kind;
        private final Line line;
        private final String marker;
        private final int headingLevel;
        private final int sequence;
        private final List<Line> tableLines;
        private final List<String> tableHeaders;
        private final boolean tableHasSeparator;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Block(@com.fasterxml.jackson.annotation.JsonProperty("kind") BlockKind kind, @com.fasterxml.jackson.annotation.JsonProperty("line") Line line, @com.fasterxml.jackson.annotation.JsonProperty("marker") String marker, @com.fasterxml.jackson.annotation.JsonProperty("headingLevel") int headingLevel, @com.fasterxml.jackson.annotation.JsonProperty("sequence") int sequence, @com.fasterxml.jackson.annotation.JsonProperty("tableLines") List<Line> tableLines, @com.fasterxml.jackson.annotation.JsonProperty("tableHeaders") List<String> tableHeaders, @com.fasterxml.jackson.annotation.JsonProperty("tableHasSeparator") boolean tableHasSeparator) { this.kind = kind; this.line = line; this.marker = marker; this.headingLevel = headingLevel; this.sequence = sequence; this.tableLines = tableLines; this.tableHeaders = tableHeaders; this.tableHasSeparator = tableHasSeparator; }
        public BlockKind kind() { return kind; }
        public Line line() { return line; }
        public String marker() { return marker; }
        public int headingLevel() { return headingLevel; }
        public int sequence() { return sequence; }
        public List<Line> tableLines() { return tableLines; }
        public List<String> tableHeaders() { return tableHeaders; }
        public boolean tableHasSeparator() { return tableHasSeparator; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Block that = (Block) other; return java.util.Objects.equals(this.kind, that.kind) && java.util.Objects.equals(this.line, that.line) && java.util.Objects.equals(this.marker, that.marker) && java.util.Objects.equals(this.headingLevel, that.headingLevel) && java.util.Objects.equals(this.sequence, that.sequence) && java.util.Objects.equals(this.tableLines, that.tableLines) && java.util.Objects.equals(this.tableHeaders, that.tableHeaders) && java.util.Objects.equals(this.tableHasSeparator, that.tableHasSeparator); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.kind); hash = 31 * hash + java.util.Objects.hashCode(this.line); hash = 31 * hash + java.util.Objects.hashCode(this.marker); hash = 31 * hash + java.util.Objects.hashCode(this.headingLevel); hash = 31 * hash + java.util.Objects.hashCode(this.sequence); hash = 31 * hash + java.util.Objects.hashCode(this.tableLines); hash = 31 * hash + java.util.Objects.hashCode(this.tableHeaders); hash = 31 * hash + java.util.Objects.hashCode(this.tableHasSeparator); return hash; }
        @Override public String toString() { return "Block[" + "kind=" + kind + ", line=" + line + ", marker=" + marker + ", headingLevel=" + headingLevel + ", sequence=" + sequence + ", tableLines=" + tableLines + ", tableHeaders=" + tableHeaders + ", tableHasSeparator=" + tableHasSeparator + "]"; }
 }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class SectionFrame {
        private final int level;
        private final MutableNode node;
        @com.fasterxml.jackson.annotation.JsonCreator
        private SectionFrame(@com.fasterxml.jackson.annotation.JsonProperty("level") int level, @com.fasterxml.jackson.annotation.JsonProperty("node") MutableNode node) { this.level = level; this.node = node; }
        public int level() { return level; }
        public MutableNode node() { return node; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; SectionFrame that = (SectionFrame) other; return java.util.Objects.equals(this.level, that.level) && java.util.Objects.equals(this.node, that.node); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.level); hash = 31 * hash + java.util.Objects.hashCode(this.node); return hash; }
        @Override public String toString() { return "SectionFrame[" + "level=" + level + ", node=" + node + "]"; }
 }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class ChecklistFrame {
        private final MutableNode node;
        private final int listLevel;
        @com.fasterxml.jackson.annotation.JsonCreator
        private ChecklistFrame(@com.fasterxml.jackson.annotation.JsonProperty("node") MutableNode node, @com.fasterxml.jackson.annotation.JsonProperty("listLevel") int listLevel) { this.node = node; this.listLevel = listLevel; }
        public MutableNode node() { return node; }
        public int listLevel() { return listLevel; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ChecklistFrame that = (ChecklistFrame) other; return java.util.Objects.equals(this.node, that.node) && java.util.Objects.equals(this.listLevel, that.listLevel); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.node); hash = 31 * hash + java.util.Objects.hashCode(this.listLevel); return hash; }
        @Override public String toString() { return "ChecklistFrame[" + "node=" + node + ", listLevel=" + listLevel + "]"; }
 }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class TableCarry {
        private final String tableId;
        private final String title;
        private final List<String> headers;
        private final String parentIdentity;
        private final int pageNumber;
        @com.fasterxml.jackson.annotation.JsonCreator
        private TableCarry(@com.fasterxml.jackson.annotation.JsonProperty("tableId") String tableId, @com.fasterxml.jackson.annotation.JsonProperty("title") String title, @com.fasterxml.jackson.annotation.JsonProperty("headers") List<String> headers, @com.fasterxml.jackson.annotation.JsonProperty("parentIdentity") String parentIdentity, @com.fasterxml.jackson.annotation.JsonProperty("pageNumber") int pageNumber) { this.tableId = tableId; this.title = title; this.headers = headers; this.parentIdentity = parentIdentity; this.pageNumber = pageNumber; }
        public String tableId() { return tableId; }
        public String title() { return title; }
        public List<String> headers() { return headers; }
        public String parentIdentity() { return parentIdentity; }
        public int pageNumber() { return pageNumber; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; TableCarry that = (TableCarry) other; return java.util.Objects.equals(this.tableId, that.tableId) && java.util.Objects.equals(this.title, that.title) && java.util.Objects.equals(this.headers, that.headers) && java.util.Objects.equals(this.parentIdentity, that.parentIdentity) && java.util.Objects.equals(this.pageNumber, that.pageNumber); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.tableId); hash = 31 * hash + java.util.Objects.hashCode(this.title); hash = 31 * hash + java.util.Objects.hashCode(this.headers); hash = 31 * hash + java.util.Objects.hashCode(this.parentIdentity); hash = 31 * hash + java.util.Objects.hashCode(this.pageNumber); return hash; }
        @Override public String toString() { return "TableCarry[" + "tableId=" + tableId + ", title=" + title + ", headers=" + headers + ", parentIdentity=" + parentIdentity + ", pageNumber=" + pageNumber + "]"; }
 }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Line {
        private final String raw;
        private final int start;
        private final int end;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Line(@com.fasterxml.jackson.annotation.JsonProperty("raw") String raw, @com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end) { this.raw = raw; this.start = start; this.end = end; }
        public String raw() { return raw; }
        public int start() { return start; }
        public int end() { return end; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Line that = (Line) other; return java.util.Objects.equals(this.raw, that.raw) && java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.raw); hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); return hash; }
        @Override public String toString() { return "Line[" + "raw=" + raw + ", start=" + start + ", end=" + end + "]"; }

        String trimmed() { return raw == null ? "" : raw.trim(); }
    }

    private static final class MutableNode {
        private final String id;
        private final ClauseNode.Kind kind;
        private final String text;
        private final SourceRef source;
        private final ClauseNode.LocalContext context;
        private final List<MutableNode> children;

        private MutableNode(String id, ClauseNode.Kind kind, String text, SourceRef source,
                            ClauseNode.LocalContext context, List<MutableNode> children) {
            this.id = id;
            this.kind = kind;
            this.text = text;
            this.source = source;
            this.context = context;
            this.children = children;
        }

        private ClauseNode freeze() {
            return new ClauseNode(id, kind, text, source, context,
                    cn.bidcheck.compat.Java8Api.toList(children.stream().map(MutableNode::freeze)));
        }
    }
}
