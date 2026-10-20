package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;

import java.util.List;
import java.util.Map;

/** Fully resolved context carried by every mapped fragment. */
public record SourceContext(
        List<String> sectionPath,
        List<String> clausePath,
        List<String> listPath,
        String tableId,
        String tableTitle,
        List<String> tableHeaders,
        Integer tableRow,
        Integer tableColumn,
        Integer rowSpan,
        Integer columnSpan,
        String projectScope,
        String lotScope,
        String subjectRole,
        String documentRole,
        ClauseNode.Boundary boundary,
        List<String> crossReferences,
        Map<String, List<SourceRef>> contextSources,
        String previousClauseId,
        String nextClauseId) {

    public SourceContext {
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
        contextSources = contextSources == null ? Map.of() : contextSources.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> entry.getValue() == null ? List.of() : List.copyOf(entry.getValue())));
        previousClauseId = text(previousClauseId);
        nextClauseId = text(nextClauseId);
    }

    static SourceContext root() {
        return new SourceContext(List.of(), List.of(), List.of(), "", "", List.of(), null, null,
                null, null, "", "", "", "", ClauseNode.Boundary.UNSPECIFIED, List.of(), Map.of(), "", "");
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
