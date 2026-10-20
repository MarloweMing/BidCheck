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
public record ClauseNode(
        String id,
        Kind kind,
        String text,
        SourceRef sourceRef,
        LocalContext localContext,
        List<ClauseNode> children) {

    public ClauseNode {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("clause id is required");
        id = id.trim();
        if (kind == null) throw new IllegalArgumentException("clause kind is required");
        text = text == null ? "" : text.trim();
        if (sourceRef == null) throw new IllegalArgumentException("sourceRef is required");
        localContext = localContext == null ? LocalContext.empty() : localContext;
        children = children == null ? List.of() : List.copyOf(children);
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
    public record LocalContext(
            String sectionTitle,
            String listMarker,
            TableContext table,
            String projectScope,
            String lotScope,
            String subjectRole,
            String documentRole,
            Boundary boundary,
            List<String> crossReferences) {

        public LocalContext {
            sectionTitle = text(sectionTitle);
            listMarker = text(listMarker);
            projectScope = text(projectScope);
            lotScope = text(lotScope);
            subjectRole = text(subjectRole);
            documentRole = text(documentRole);
            boundary = boundary == null ? Boundary.UNSPECIFIED : boundary;
            crossReferences = crossReferences == null ? List.of() : crossReferences.stream()
                    .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        }

        public static LocalContext empty() {
            return new LocalContext("", "", null, "", "", "", "", Boundary.UNSPECIFIED, List.of());
        }
    }

    /** Coordinates and headers are kept even when a row/cell becomes an independent requirement. */
    public record TableContext(
            String tableId,
            String title,
            List<String> headers,
            Integer rowIndex,
            Integer columnIndex,
            Integer rowSpan,
            Integer columnSpan) {

        public TableContext {
            tableId = text(tableId);
            title = text(title);
            headers = headers == null ? List.of() : headers.stream()
                    .map(ClauseNode::text).filter(value -> !value.isBlank()).toList();
            nonNegative(rowIndex, "rowIndex");
            nonNegative(columnIndex, "columnIndex");
            positive(rowSpan, "rowSpan");
            positive(columnSpan, "columnSpan");
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
