package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;

import java.util.List;

/**
 * A source-faithful node in the tender requirement tree.
 *
 * <p>The node stores only context declared at this location. {@link RequirementMappingService}
 * resolves inherited section, list, table and business scope without copying parent prose into
 * the child requirement.</p>
 */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class ClauseNode {
        private final String id;
        private final Kind kind;
        private final String text;
        private final SourceRef sourceRef;
        private final LocalContext localContext;
        private final List<ClauseNode> children;
        public String id() { return id; }
        public Kind kind() { return kind; }
        public String text() { return text; }
        public SourceRef sourceRef() { return sourceRef; }
        public LocalContext localContext() { return localContext; }
        public List<ClauseNode> children() { return children; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ClauseNode that = (ClauseNode) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.kind, that.kind) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.sourceRef, that.sourceRef) && java.util.Objects.equals(this.localContext, that.localContext) && java.util.Objects.equals(this.children, that.children); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.kind); hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.sourceRef); hash = 31 * hash + java.util.Objects.hashCode(this.localContext); hash = 31 * hash + java.util.Objects.hashCode(this.children); return hash; }
        @Override public String toString() { return "ClauseNode[" + "id=" + id + ", kind=" + kind + ", text=" + text + ", sourceRef=" + sourceRef + ", localContext=" + localContext + ", children=" + children + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public ClauseNode(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("kind") Kind kind, @com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("sourceRef") SourceRef sourceRef, @com.fasterxml.jackson.annotation.JsonProperty("localContext") LocalContext localContext, @com.fasterxml.jackson.annotation.JsonProperty("children") List<ClauseNode> children) {
        if (id == null || cn.bidcheck.compat.Java8Api.isBlank(id)) throw new IllegalArgumentException("clause id is required");
        id = id.trim();
        if (kind == null) throw new IllegalArgumentException("clause kind is required");
        text = text == null ? "" : text.trim();
        if (sourceRef == null) throw new IllegalArgumentException("sourceRef is required");
        localContext = localContext == null ? LocalContext.empty() : localContext;
        children = children == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(children);

            this.id = id; this.kind = kind; this.text = text; this.sourceRef = sourceRef; this.localContext = localContext; this.children = children;
        }

    public enum Kind {
        DOCUMENT,
        SECTION,
        PARAGRAPH,
        LIST_ITEM,
        TABLE,
        TABLE_ROW,
        TABLE_CELL
    }

    /** Local declarations. Blank values mean "inherit from the nearest ancestor". */
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class LocalContext {
        private final String sectionTitle;
        private final String listMarker;
        private final TableContext table;
        private final String projectScope;
        private final String lotScope;
        private final String subjectRole;
        private final String documentRole;
        private final Boundary boundary;
        private final List<String> crossReferences;
        public String sectionTitle() { return sectionTitle; }
        public String listMarker() { return listMarker; }
        public TableContext table() { return table; }
        public String projectScope() { return projectScope; }
        public String lotScope() { return lotScope; }
        public String subjectRole() { return subjectRole; }
        public String documentRole() { return documentRole; }
        public Boundary boundary() { return boundary; }
        public List<String> crossReferences() { return crossReferences; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; LocalContext that = (LocalContext) other; return java.util.Objects.equals(this.sectionTitle, that.sectionTitle) && java.util.Objects.equals(this.listMarker, that.listMarker) && java.util.Objects.equals(this.table, that.table) && java.util.Objects.equals(this.projectScope, that.projectScope) && java.util.Objects.equals(this.lotScope, that.lotScope) && java.util.Objects.equals(this.subjectRole, that.subjectRole) && java.util.Objects.equals(this.documentRole, that.documentRole) && java.util.Objects.equals(this.boundary, that.boundary) && java.util.Objects.equals(this.crossReferences, that.crossReferences); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.sectionTitle); hash = 31 * hash + java.util.Objects.hashCode(this.listMarker); hash = 31 * hash + java.util.Objects.hashCode(this.table); hash = 31 * hash + java.util.Objects.hashCode(this.projectScope); hash = 31 * hash + java.util.Objects.hashCode(this.lotScope); hash = 31 * hash + java.util.Objects.hashCode(this.subjectRole); hash = 31 * hash + java.util.Objects.hashCode(this.documentRole); hash = 31 * hash + java.util.Objects.hashCode(this.boundary); hash = 31 * hash + java.util.Objects.hashCode(this.crossReferences); return hash; }
        @Override public String toString() { return "LocalContext[" + "sectionTitle=" + sectionTitle + ", listMarker=" + listMarker + ", table=" + table + ", projectScope=" + projectScope + ", lotScope=" + lotScope + ", subjectRole=" + subjectRole + ", documentRole=" + documentRole + ", boundary=" + boundary + ", crossReferences=" + crossReferences + "]"; }


        @com.fasterxml.jackson.annotation.JsonCreator
        public LocalContext(@com.fasterxml.jackson.annotation.JsonProperty("sectionTitle") String sectionTitle, @com.fasterxml.jackson.annotation.JsonProperty("listMarker") String listMarker, @com.fasterxml.jackson.annotation.JsonProperty("table") TableContext table, @com.fasterxml.jackson.annotation.JsonProperty("projectScope") String projectScope, @com.fasterxml.jackson.annotation.JsonProperty("lotScope") String lotScope, @com.fasterxml.jackson.annotation.JsonProperty("subjectRole") String subjectRole, @com.fasterxml.jackson.annotation.JsonProperty("documentRole") String documentRole, @com.fasterxml.jackson.annotation.JsonProperty("boundary") Boundary boundary, @com.fasterxml.jackson.annotation.JsonProperty("crossReferences") List<String> crossReferences) {
            sectionTitle = text(sectionTitle);
            listMarker = text(listMarker);
            projectScope = text(projectScope);
            lotScope = text(lotScope);
            subjectRole = text(subjectRole);
            documentRole = text(documentRole);
            boundary = boundary == null ? Boundary.UNSPECIFIED : boundary;
            crossReferences = crossReferences == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.toList(crossReferences.stream()
                    .filter(value -> value != null && !cn.bidcheck.compat.Java8Api.isBlank(value)).map(String::trim).distinct());

            this.sectionTitle = sectionTitle; this.listMarker = listMarker; this.table = table; this.projectScope = projectScope; this.lotScope = lotScope; this.subjectRole = subjectRole; this.documentRole = documentRole; this.boundary = boundary; this.crossReferences = crossReferences;
        }

        public static LocalContext empty() {
            return new LocalContext("", "", null, "", "", "", "", Boundary.UNSPECIFIED, cn.bidcheck.compat.Java8Api.listOf());
        }
    }

    /** Coordinates and headers are kept even when a row/cell becomes an independent requirement. */
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class TableContext {
        private final String tableId;
        private final String title;
        private final List<String> headers;
        private final Integer rowIndex;
        private final Integer columnIndex;
        private final Integer rowSpan;
        private final Integer columnSpan;
        public String tableId() { return tableId; }
        public String title() { return title; }
        public List<String> headers() { return headers; }
        public Integer rowIndex() { return rowIndex; }
        public Integer columnIndex() { return columnIndex; }
        public Integer rowSpan() { return rowSpan; }
        public Integer columnSpan() { return columnSpan; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; TableContext that = (TableContext) other; return java.util.Objects.equals(this.tableId, that.tableId) && java.util.Objects.equals(this.title, that.title) && java.util.Objects.equals(this.headers, that.headers) && java.util.Objects.equals(this.rowIndex, that.rowIndex) && java.util.Objects.equals(this.columnIndex, that.columnIndex) && java.util.Objects.equals(this.rowSpan, that.rowSpan) && java.util.Objects.equals(this.columnSpan, that.columnSpan); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.tableId); hash = 31 * hash + java.util.Objects.hashCode(this.title); hash = 31 * hash + java.util.Objects.hashCode(this.headers); hash = 31 * hash + java.util.Objects.hashCode(this.rowIndex); hash = 31 * hash + java.util.Objects.hashCode(this.columnIndex); hash = 31 * hash + java.util.Objects.hashCode(this.rowSpan); hash = 31 * hash + java.util.Objects.hashCode(this.columnSpan); return hash; }
        @Override public String toString() { return "TableContext[" + "tableId=" + tableId + ", title=" + title + ", headers=" + headers + ", rowIndex=" + rowIndex + ", columnIndex=" + columnIndex + ", rowSpan=" + rowSpan + ", columnSpan=" + columnSpan + "]"; }


        @com.fasterxml.jackson.annotation.JsonCreator
        public TableContext(@com.fasterxml.jackson.annotation.JsonProperty("tableId") String tableId, @com.fasterxml.jackson.annotation.JsonProperty("title") String title, @com.fasterxml.jackson.annotation.JsonProperty("headers") List<String> headers, @com.fasterxml.jackson.annotation.JsonProperty("rowIndex") Integer rowIndex, @com.fasterxml.jackson.annotation.JsonProperty("columnIndex") Integer columnIndex, @com.fasterxml.jackson.annotation.JsonProperty("rowSpan") Integer rowSpan, @com.fasterxml.jackson.annotation.JsonProperty("columnSpan") Integer columnSpan) {
            tableId = text(tableId);
            title = text(title);
            headers = headers == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.toList(headers.stream()
                    .map(ClauseNode::text).filter(value -> !cn.bidcheck.compat.Java8Api.isBlank(value)));
            nonNegative(rowIndex, "rowIndex");
            nonNegative(columnIndex, "columnIndex");
            positive(rowSpan, "rowSpan");
            positive(columnSpan, "columnSpan");

            this.tableId = tableId; this.title = title; this.headers = headers; this.rowIndex = rowIndex; this.columnIndex = columnIndex; this.rowSpan = rowSpan; this.columnSpan = columnSpan;
        }

        private static void nonNegative(Integer value, String name) {
            if (value != null && value < 0) throw new IllegalArgumentException(name + " must be non-negative");
        }

        private static void positive(Integer value, String name) {
            if (value != null && value < 1) throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public enum Boundary { UNSPECIFIED, CURRENT_TENDER, HISTORICAL_MATERIAL, EXTERNAL_DOCUMENT }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
