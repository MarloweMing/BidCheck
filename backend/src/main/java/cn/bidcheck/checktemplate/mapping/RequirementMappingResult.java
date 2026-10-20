package cn.bidcheck.checktemplate.mapping;

import java.util.List;
import java.util.Map;

/** Complete local mapping result and its coverage ledger. */
public record RequirementMappingResult(
        List<RequirementDraft> drafts,
        List<CoverageItem> coverage,
        Diagnostics diagnostics) {

    public RequirementMappingResult {
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
        coverage = coverage == null ? List.of() : List.copyOf(coverage);
        diagnostics = diagnostics == null ? Diagnostics.localOnly(coverage) : diagnostics;
    }

    public record Diagnostics(
            boolean localOnly,
            int classifierCalls,
            int clauseCount,
            int fragmentCount,
            Map<CoverageItem.Status, Long> coverageCounts) {

        public Diagnostics {
            if (classifierCalls < 0 || clauseCount < 0 || fragmentCount < 0) {
                throw new IllegalArgumentException("diagnostic counters must be non-negative");
            }
            coverageCounts = coverageCounts == null ? Map.of() : Map.copyOf(coverageCounts);
        }

        static Diagnostics localOnly(List<CoverageItem> coverage) {
            List<CoverageItem> safe = coverage == null ? List.of() : coverage;
            return new Diagnostics(true, 0, 0, safe.size(), safe.stream().collect(
                    java.util.stream.Collectors.groupingBy(CoverageItem::status, java.util.stream.Collectors.counting())));
        }
    }
}
