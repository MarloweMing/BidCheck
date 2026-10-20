package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;

import java.util.List;

/** One ledger row per source fragment; no source fragment disappears silently. */
public record CoverageItem(
        String fragmentId,
        String clauseId,
        String text,
        SourceRef sourceRef,
        SourceContext sourceContext,
        Status status,
        String draftId,
        List<String> templateIds,
        String reasonCode,
        String explanation) {

    public CoverageItem {
        fragmentId = required(fragmentId, "fragmentId");
        clauseId = required(clauseId, "clauseId");
        text = text == null ? "" : text.trim();
        if (sourceRef == null) throw new IllegalArgumentException("sourceRef is required");
        if (sourceContext == null) throw new IllegalArgumentException("sourceContext is required");
        if (status == null) throw new IllegalArgumentException("status is required");
        draftId = draftId == null ? "" : draftId.trim();
        templateIds = templateIds == null ? List.of() : templateIds.stream().distinct().toList();
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        explanation = explanation == null ? "" : explanation.trim();
    }

    public enum Status {
        MAPPED,
        NEEDS_CONFIRMATION,
        NON_CHECKING,
        EXTERNAL_REQUIRED,
        UNSUPPORTED
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
