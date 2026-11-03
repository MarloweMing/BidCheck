package cn.bidcheck.checktemplate.evidence;

import java.time.Duration;

/** Explicit retry and lease boundary; no unbounded retry is permitted. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class EvidenceRetryPolicy {
        private final int maxAttempts;
        private final Duration initialBackoff;
        private final Duration maximumBackoff;
        private final Duration leaseDuration;
        private final Duration pollInterval;
        public int maxAttempts() { return maxAttempts; }
        public Duration initialBackoff() { return initialBackoff; }
        public Duration maximumBackoff() { return maximumBackoff; }
        public Duration leaseDuration() { return leaseDuration; }
        public Duration pollInterval() { return pollInterval; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; EvidenceRetryPolicy that = (EvidenceRetryPolicy) other; return java.util.Objects.equals(this.maxAttempts, that.maxAttempts) && java.util.Objects.equals(this.initialBackoff, that.initialBackoff) && java.util.Objects.equals(this.maximumBackoff, that.maximumBackoff) && java.util.Objects.equals(this.leaseDuration, that.leaseDuration) && java.util.Objects.equals(this.pollInterval, that.pollInterval); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.maxAttempts); hash = 31 * hash + java.util.Objects.hashCode(this.initialBackoff); hash = 31 * hash + java.util.Objects.hashCode(this.maximumBackoff); hash = 31 * hash + java.util.Objects.hashCode(this.leaseDuration); hash = 31 * hash + java.util.Objects.hashCode(this.pollInterval); return hash; }
        @Override public String toString() { return "EvidenceRetryPolicy[" + "maxAttempts=" + maxAttempts + ", initialBackoff=" + initialBackoff + ", maximumBackoff=" + maximumBackoff + ", leaseDuration=" + leaseDuration + ", pollInterval=" + pollInterval + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public EvidenceRetryPolicy(@com.fasterxml.jackson.annotation.JsonProperty("maxAttempts") int maxAttempts, @com.fasterxml.jackson.annotation.JsonProperty("initialBackoff") Duration initialBackoff, @com.fasterxml.jackson.annotation.JsonProperty("maximumBackoff") Duration maximumBackoff, @com.fasterxml.jackson.annotation.JsonProperty("leaseDuration") Duration leaseDuration, @com.fasterxml.jackson.annotation.JsonProperty("pollInterval") Duration pollInterval) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        initialBackoff = positive(initialBackoff, "initialBackoff");
        maximumBackoff = positive(maximumBackoff, "maximumBackoff");
        leaseDuration = positive(leaseDuration, "leaseDuration");
        pollInterval = positive(pollInterval, "pollInterval");
        if (maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maximumBackoff must not be less than initialBackoff");
        }

            this.maxAttempts = maxAttempts; this.initialBackoff = initialBackoff; this.maximumBackoff = maximumBackoff; this.leaseDuration = leaseDuration; this.pollInterval = pollInterval;
        }

    public static EvidenceRetryPolicy defaults() {
        return new EvidenceRetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(5),
                Duration.ofMinutes(2), Duration.ofMillis(100));
    }

    public Duration backoffAfter(int completedAttempt) {
        long multiplier = 1L << Math.min(30, Math.max(0, completedAttempt - 1));
        long millis;
        try {
            millis = Math.multiplyExact(initialBackoff.toMillis(), multiplier);
        } catch (ArithmeticException overflow) {
            millis = maximumBackoff.toMillis();
        }
        return Duration.ofMillis(Math.min(maximumBackoff.toMillis(), millis));
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
