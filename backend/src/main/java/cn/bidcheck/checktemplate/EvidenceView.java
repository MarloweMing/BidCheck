package cn.bidcheck.checktemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Frozen evidence snapshot. Evaluators are pure readers and cannot issue requests. */
public record EvidenceView(
        String snapshotId,
        long revision,
        List<Observation> observations,
        Set<String> closedNeedKeys,
        Set<String> failedNeedKeys,
        Map<String, String> processingFailures) {
    public EvidenceView {
        snapshotId = snapshotId == null ? "" : snapshotId.trim();
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
        observations = observations == null ? List.of() : List.copyOf(observations);
        closedNeedKeys = closedNeedKeys == null ? Set.of() : Set.copyOf(closedNeedKeys);
        failedNeedKeys = failedNeedKeys == null ? Set.of() : Set.copyOf(failedNeedKeys);
        processingFailures = processingFailures == null ? Map.of() : Map.copyOf(processingFailures);
    }

    public static EvidenceView empty() { return new EvidenceView("", 0, List.of(), Set.of(), Set.of(), Map.of()); }

    public boolean isClosed(EvidenceNeed need) { return closedNeedKeys.contains(need.key()); }
    public boolean failed(EvidenceNeed need) { return failedNeedKeys.contains(need.key()) || processingFailures.containsKey(need.key()); }

    public <T extends Observation> List<T> observations(Class<T> type) {
        return observations.stream().filter(type::isInstance).map(type::cast).toList();
    }

    public Set<String> evidenceIds(List<? extends Observation> values) {
        return values.stream().flatMap(value -> value.sources().stream())
                .map(source -> source.documentId() + ":" + source.documentVersion() + ":" + source.locationType() + ":" + source.location())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
