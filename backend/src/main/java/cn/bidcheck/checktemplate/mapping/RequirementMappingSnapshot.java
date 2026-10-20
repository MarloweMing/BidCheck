package cn.bidcheck.checktemplate.mapping;

import java.util.List;
import java.util.Map;

/** Persisted mapping draft. It is an inventory artifact and is never an executable CheckSpec. */
public record RequirementMappingSnapshot(
        String schema,
        String mappingVersion,
        String projectId,
        String tenderDocumentId,
        int tenderDocumentVersion,
        String tenderDocumentHash,
        String generatedAt,
        String executionState,
        boolean affectsBusinessConclusion,
        Map<String, Object> summary,
        List<RequirementDraft> drafts,
        List<CoverageItem> coverage,
        RequirementMappingResult.Diagnostics diagnostics) {

    public RequirementMappingSnapshot {
        summary = summary == null ? Map.of() : Map.copyOf(summary);
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
        coverage = coverage == null ? List.of() : List.copyOf(coverage);
    }
}
