package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;

import java.util.List;
import java.util.Map;

/** Fully resolved context carried by every mapped fragment. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class SourceContext {
        private final List<String> sectionPath;
        private final List<String> clausePath;
        private final List<String> listPath;
        private final String tableId;
        private final String tableTitle;
        private final List<String> tableHeaders;
        private final Integer tableRow;
        private final Integer tableColumn;
        private final Integer rowSpan;
        private final Integer columnSpan;
        private final String projectScope;
        private final String lotScope;
        private final String subjectRole;
        private final String documentRole;
        private final ClauseNode.Boundary boundary;
        private final List<String> crossReferences;
        private final Map<String, List<SourceRef>> contextSources;
        private final String previousClauseId;
        private final String nextClauseId;
        public List<String> sectionPath() { return sectionPath; }
        public List<String> clausePath() { return clausePath; }
        public List<String> listPath() { return listPath; }
        public String tableId() { return tableId; }
        public String tableTitle() { return tableTitle; }
        public List<String> tableHeaders() { return tableHeaders; }
        public Integer tableRow() { return tableRow; }
        public Integer tableColumn() { return tableColumn; }
        public Integer rowSpan() { return rowSpan; }
        public Integer columnSpan() { return columnSpan; }
        public String projectScope() { return projectScope; }
        public String lotScope() { return lotScope; }
        public String subjectRole() { return subjectRole; }
        public String documentRole() { return documentRole; }
        public ClauseNode.Boundary boundary() { return boundary; }
        public List<String> crossReferences() { return crossReferences; }
        public Map<String, List<SourceRef>> contextSources() { return contextSources; }
        public String previousClauseId() { return previousClauseId; }
        public String nextClauseId() { return nextClauseId; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; SourceContext that = (SourceContext) other; return java.util.Objects.equals(this.sectionPath, that.sectionPath) && java.util.Objects.equals(this.clausePath, that.clausePath) && java.util.Objects.equals(this.listPath, that.listPath) && java.util.Objects.equals(this.tableId, that.tableId) && java.util.Objects.equals(this.tableTitle, that.tableTitle) && java.util.Objects.equals(this.tableHeaders, that.tableHeaders) && java.util.Objects.equals(this.tableRow, that.tableRow) && java.util.Objects.equals(this.tableColumn, that.tableColumn) && java.util.Objects.equals(this.rowSpan, that.rowSpan) && java.util.Objects.equals(this.columnSpan, that.columnSpan) && java.util.Objects.equals(this.projectScope, that.projectScope) && java.util.Objects.equals(this.lotScope, that.lotScope) && java.util.Objects.equals(this.subjectRole, that.subjectRole) && java.util.Objects.equals(this.documentRole, that.documentRole) && java.util.Objects.equals(this.boundary, that.boundary) && java.util.Objects.equals(this.crossReferences, that.crossReferences) && java.util.Objects.equals(this.contextSources, that.contextSources) && java.util.Objects.equals(this.previousClauseId, that.previousClauseId) && java.util.Objects.equals(this.nextClauseId, that.nextClauseId); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.sectionPath); hash = 31 * hash + java.util.Objects.hashCode(this.clausePath); hash = 31 * hash + java.util.Objects.hashCode(this.listPath); hash = 31 * hash + java.util.Objects.hashCode(this.tableId); hash = 31 * hash + java.util.Objects.hashCode(this.tableTitle); hash = 31 * hash + java.util.Objects.hashCode(this.tableHeaders); hash = 31 * hash + java.util.Objects.hashCode(this.tableRow); hash = 31 * hash + java.util.Objects.hashCode(this.tableColumn); hash = 31 * hash + java.util.Objects.hashCode(this.rowSpan); hash = 31 * hash + java.util.Objects.hashCode(this.columnSpan); hash = 31 * hash + java.util.Objects.hashCode(this.projectScope); hash = 31 * hash + java.util.Objects.hashCode(this.lotScope); hash = 31 * hash + java.util.Objects.hashCode(this.subjectRole); hash = 31 * hash + java.util.Objects.hashCode(this.documentRole); hash = 31 * hash + java.util.Objects.hashCode(this.boundary); hash = 31 * hash + java.util.Objects.hashCode(this.crossReferences); hash = 31 * hash + java.util.Objects.hashCode(this.contextSources); hash = 31 * hash + java.util.Objects.hashCode(this.previousClauseId); hash = 31 * hash + java.util.Objects.hashCode(this.nextClauseId); return hash; }
        @Override public String toString() { return "SourceContext[" + "sectionPath=" + sectionPath + ", clausePath=" + clausePath + ", listPath=" + listPath + ", tableId=" + tableId + ", tableTitle=" + tableTitle + ", tableHeaders=" + tableHeaders + ", tableRow=" + tableRow + ", tableColumn=" + tableColumn + ", rowSpan=" + rowSpan + ", columnSpan=" + columnSpan + ", projectScope=" + projectScope + ", lotScope=" + lotScope + ", subjectRole=" + subjectRole + ", documentRole=" + documentRole + ", boundary=" + boundary + ", crossReferences=" + crossReferences + ", contextSources=" + contextSources + ", previousClauseId=" + previousClauseId + ", nextClauseId=" + nextClauseId + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public SourceContext(@com.fasterxml.jackson.annotation.JsonProperty("sectionPath") List<String> sectionPath, @com.fasterxml.jackson.annotation.JsonProperty("clausePath") List<String> clausePath, @com.fasterxml.jackson.annotation.JsonProperty("listPath") List<String> listPath, @com.fasterxml.jackson.annotation.JsonProperty("tableId") String tableId, @com.fasterxml.jackson.annotation.JsonProperty("tableTitle") String tableTitle, @com.fasterxml.jackson.annotation.JsonProperty("tableHeaders") List<String> tableHeaders, @com.fasterxml.jackson.annotation.JsonProperty("tableRow") Integer tableRow, @com.fasterxml.jackson.annotation.JsonProperty("tableColumn") Integer tableColumn, @com.fasterxml.jackson.annotation.JsonProperty("rowSpan") Integer rowSpan, @com.fasterxml.jackson.annotation.JsonProperty("columnSpan") Integer columnSpan, @com.fasterxml.jackson.annotation.JsonProperty("projectScope") String projectScope, @com.fasterxml.jackson.annotation.JsonProperty("lotScope") String lotScope, @com.fasterxml.jackson.annotation.JsonProperty("subjectRole") String subjectRole, @com.fasterxml.jackson.annotation.JsonProperty("documentRole") String documentRole, @com.fasterxml.jackson.annotation.JsonProperty("boundary") ClauseNode.Boundary boundary, @com.fasterxml.jackson.annotation.JsonProperty("crossReferences") List<String> crossReferences, @com.fasterxml.jackson.annotation.JsonProperty("contextSources") Map<String, List<SourceRef>> contextSources, @com.fasterxml.jackson.annotation.JsonProperty("previousClauseId") String previousClauseId, @com.fasterxml.jackson.annotation.JsonProperty("nextClauseId") String nextClauseId) {
        sectionPath = copy(sectionPath);
        clausePath = copy(clausePath);
        listPath = copy(listPath);
        tableId = text(tableId);
        tableTitle = text(tableTitle);
        tableHeaders = copy(tableHeaders);
        projectScope = text(projectScope);
        lotScope = text(lotScope);
        subjectRole = text(subjectRole);
        documentRole = text(documentRole);
        boundary = boundary == null ? ClauseNode.Boundary.UNSPECIFIED : boundary;
        crossReferences = copy(crossReferences);
        contextSources = contextSources == null ? cn.bidcheck.compat.Java8Api.mapOf() : contextSources.entrySet().stream()
                .collect(cn.bidcheck.compat.Java8Api.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue() == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(entry.getValue())));
        previousClauseId = text(previousClauseId);
        nextClauseId = text(nextClauseId);

            this.sectionPath = sectionPath; this.clausePath = clausePath; this.listPath = listPath; this.tableId = tableId; this.tableTitle = tableTitle; this.tableHeaders = tableHeaders; this.tableRow = tableRow; this.tableColumn = tableColumn; this.rowSpan = rowSpan; this.columnSpan = columnSpan; this.projectScope = projectScope; this.lotScope = lotScope; this.subjectRole = subjectRole; this.documentRole = documentRole; this.boundary = boundary; this.crossReferences = crossReferences; this.contextSources = contextSources; this.previousClauseId = previousClauseId; this.nextClauseId = nextClauseId;
        }

    static SourceContext root() {
        return new SourceContext(cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(), "", "", cn.bidcheck.compat.Java8Api.listOf(), null, null,
                null, null, "", "", "", "", ClauseNode.Boundary.UNSPECIFIED, cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.mapOf(), "", "");
    }

    private static List<String> copy(List<String> values) {
        return values == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.toList(values.stream()
                .filter(value -> value != null && !cn.bidcheck.compat.Java8Api.isBlank(value)).map(String::trim).distinct());
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
