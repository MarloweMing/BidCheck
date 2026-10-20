package cn.bidcheck.checktemplate.evidence;

import java.time.Duration;

/** Explicit retry and lease boundary; no unbounded retry is permitted. */
public record EvidenceRetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        Duration maximumBackoff,
        Duration leaseDuration,
        Duration pollInterval) {
    public EvidenceRetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        initialBackoff = positive(initialBackoff, "initialBackoff");
        maximumBackoff = positive(maximumBackoff, "maximumBackoff");
        leaseDuration = positive(leaseDuration, "leaseDuration");
        pollInterval = positive(pollInterval, "pollInterval");
        if (maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maximumBackoff must not be less than initialBackoff");
        }
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
