package cn.bidcheck.checktemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Frozen evidence snapshot. Evaluators are pure readers and cannot issue requests. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class EvidenceView {
        private final String snapshotId;
        private final long revision;
        private final List<Observation> observations;
        private final Set<String> closedNeedKeys;
        private final Set<String> failedNeedKeys;
        private final Map<String, String> processingFailures;
        public String snapshotId() { return snapshotId; }
        public long revision() { return revision; }
        public List<Observation> observations() { return observations; }
        public Set<String> closedNeedKeys() { return closedNeedKeys; }
        public Set<String> failedNeedKeys() { return failedNeedKeys; }
        public Map<String, String> processingFailures() { return processingFailures; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; EvidenceView that = (EvidenceView) other; return java.util.Objects.equals(this.snapshotId, that.snapshotId) && java.util.Objects.equals(this.revision, that.revision) && java.util.Objects.equals(this.observations, that.observations) && java.util.Objects.equals(this.closedNeedKeys, that.closedNeedKeys) && java.util.Objects.equals(this.failedNeedKeys, that.failedNeedKeys) && java.util.Objects.equals(this.processingFailures, that.processingFailures); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.snapshotId); hash = 31 * hash + java.util.Objects.hashCode(this.revision); hash = 31 * hash + java.util.Objects.hashCode(this.observations); hash = 31 * hash + java.util.Objects.hashCode(this.closedNeedKeys); hash = 31 * hash + java.util.Objects.hashCode(this.failedNeedKeys); hash = 31 * hash + java.util.Objects.hashCode(this.processingFailures); return hash; }
        @Override public String toString() { return "EvidenceView[" + "snapshotId=" + snapshotId + ", revision=" + revision + ", observations=" + observations + ", closedNeedKeys=" + closedNeedKeys + ", failedNeedKeys=" + failedNeedKeys + ", processingFailures=" + processingFailures + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public EvidenceView(@com.fasterxml.jackson.annotation.JsonProperty("snapshotId") String snapshotId, @com.fasterxml.jackson.annotation.JsonProperty("revision") long revision, @com.fasterxml.jackson.annotation.JsonProperty("observations") List<Observation> observations, @com.fasterxml.jackson.annotation.JsonProperty("closedNeedKeys") Set<String> closedNeedKeys, @com.fasterxml.jackson.annotation.JsonProperty("failedNeedKeys") Set<String> failedNeedKeys, @com.fasterxml.jackson.annotation.JsonProperty("processingFailures") Map<String, String> processingFailures) {
        snapshotId = snapshotId == null ? "" : snapshotId.trim();
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
        observations = observations == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(observations);
        closedNeedKeys = closedNeedKeys == null ? cn.bidcheck.compat.Java8Api.setOf() : cn.bidcheck.compat.Java8Api.setCopyOf(closedNeedKeys);
        failedNeedKeys = failedNeedKeys == null ? cn.bidcheck.compat.Java8Api.setOf() : cn.bidcheck.compat.Java8Api.setCopyOf(failedNeedKeys);
        processingFailures = processingFailures == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(processingFailures);

            this.snapshotId = snapshotId; this.revision = revision; this.observations = observations; this.closedNeedKeys = closedNeedKeys; this.failedNeedKeys = failedNeedKeys; this.processingFailures = processingFailures;
        }

    public static EvidenceView empty() { return new EvidenceView("", 0, cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.setOf(), cn.bidcheck.compat.Java8Api.setOf(), cn.bidcheck.compat.Java8Api.mapOf()); }

    public boolean isClosed(EvidenceNeed need) { return closedNeedKeys.contains(need.key()); }
    public boolean failed(EvidenceNeed need) { return failedNeedKeys.contains(need.key()) || processingFailures.containsKey(need.key()); }

    public <T extends Observation> List<T> observations(Class<T> type) {
        return cn.bidcheck.compat.Java8Api.toList(observations.stream().filter(type::isInstance).map(type::cast));
    }

    public Set<String> evidenceIds(List<? extends Observation> values) {
        return values.stream().flatMap(value -> value.sources().stream())
                .map(source -> source.documentId() + ":" + source.documentVersion() + ":" + source.locationType() + ":" + source.location())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
