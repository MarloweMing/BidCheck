package cn.bidcheck.checktemplate.evidence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates one local extraction flight for every stable evidence key.
 *
 * <p>In-process callers share a future and cross-process callers share the repository lease. The
 * task/result model contains no prompt or model fallback: only a {@link LocalEvidenceExtractor}
 * can execute.  Negative evidence is publishable only when the extractor closes the exact scope.</p>
 */
public final class EvidenceNeedCoordinator implements AutoCloseable {
    private final EvidenceTaskRepository repository;
    private final EvidenceRetryPolicy retryPolicy;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Executor worker;
    private final String ownerId;
    private final boolean ownsExecutors;
    private final ConcurrentHashMap<String, CompletableFuture<EvidenceTask>> flights = new ConcurrentHashMap<>();

    public EvidenceNeedCoordinator(EvidenceTaskRepository repository) {
        this(repository, EvidenceRetryPolicy.defaults());
    }

    public EvidenceNeedCoordinator(EvidenceTaskRepository repository, EvidenceRetryPolicy retryPolicy) {
        this(repository, retryPolicy, Clock.systemUTC(), defaultScheduler(), defaultWorker(),
                "evidence-node-" + UUID.randomUUID(), true);
    }

    /** Constructor for managed runtimes/tests; injected executors remain owned by the caller. */
    public EvidenceNeedCoordinator(EvidenceTaskRepository repository, EvidenceRetryPolicy retryPolicy,
                                   Clock clock, ScheduledExecutorService scheduler, Executor worker,
                                   String ownerId) {
        this(repository, retryPolicy, clock, scheduler, worker, ownerId, false);
    }

    private EvidenceNeedCoordinator(EvidenceTaskRepository repository, EvidenceRetryPolicy retryPolicy,
                                    Clock clock, ScheduledExecutorService scheduler, Executor worker,
                                    String ownerId, boolean ownsExecutors) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.worker = Objects.requireNonNull(worker, "worker");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        this.ownerId = ownerId.trim();
        this.ownsExecutors = ownsExecutors;
    }

    /**
     * Subscribe to a bounded evidence need. Concurrent requests with the same key observe the same
     * durable task and extraction attempt, while every subscriber receives its own read receipt.
     */
    public CompletableFuture<EvidenceReadReceipt> request(EvidenceNeedKey key, String subscriberId,
                                                           LocalEvidenceExtractor extractor) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(extractor, "extractor");
        String subscriber = required(subscriberId, "subscriberId");
        verifyExtractorIdentity(key, extractor);

        EvidenceTask subscribed = subscribe(key, subscriber);
        if (subscribed.status().terminal()) {
            return CompletableFuture.completedFuture(EvidenceReadReceipt.from(subscribed, subscriber, clock.instant()));
        }

        CompletableFuture<EvidenceTask> flight = flight(subscribed.taskId(), extractor);
        return flight.thenApply(task -> EvidenceReadReceipt.from(task, subscriber, clock.instant()));
    }

    public java.util.Optional<EvidenceTask> task(EvidenceNeedKey key) {
        Objects.requireNonNull(key, "key");
        return repository.find(key.id());
    }

    private EvidenceTask subscribe(EvidenceNeedKey key, String subscriber) {
        while (true) {
            Instant now = clock.instant();
            EvidenceTask current = repository.createOrGet(key, retryPolicy.maxAttempts(), now);
            EvidenceTask replacement = current.subscribe(subscriber, now);
            if (replacement == current) return current;
            if (repository.replace(replacement, current.revision())) return replacement;
        }
    }

    private CompletableFuture<EvidenceTask> flight(String taskId, LocalEvidenceExtractor extractor) {
        CompletableFuture<EvidenceTask> candidate = new CompletableFuture<>();
        CompletableFuture<EvidenceTask> existing = flights.putIfAbsent(taskId, candidate);
        if (existing != null) return existing;
        candidate.whenComplete((ignored, failure) -> flights.remove(taskId, candidate));
        scheduleDrive(taskId, extractor, candidate, Duration.ZERO);
        return candidate;
    }

    private void scheduleDrive(String taskId, LocalEvidenceExtractor extractor,
                               CompletableFuture<EvidenceTask> future, Duration delay) {
        if (future.isDone()) return;
        long millis = Math.max(0, delay.toMillis());
        try {
            scheduler.schedule(() -> drive(taskId, extractor, future), millis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulingFailure) {
            future.completeExceptionally(schedulingFailure);
        }
    }

    private void drive(String taskId, LocalEvidenceExtractor extractor,
                       CompletableFuture<EvidenceTask> future) {
        if (future.isDone()) return;
        EvidenceTask current = repository.find(taskId).orElse(null);
        if (current == null) {
            future.completeExceptionally(new IllegalStateException("evidence task disappeared: " + taskId));
            return;
        }
        if (current.status().terminal()) {
            future.complete(current);
            return;
        }

        Instant now = clock.instant();
        if (!current.canAcquireAt(now)) {
            Instant readyAt = current.status() == EvidenceTaskStatus.RUNNING
                    ? current.lease().expiresAt() : current.nextAttemptAt();
            Duration untilReady = Duration.between(now, readyAt);
            Duration delay = minimumPositive(untilReady, retryPolicy.pollInterval());
            scheduleDrive(taskId, extractor, future, delay);
            return;
        }

        if (current.attempts() >= current.maxAttempts()) {
            EvidenceTaskFailure failure = new EvidenceTaskFailure(
                    "EVIDENCE_ATTEMPTS_EXHAUSTED",
                    "证据任务已达到最大尝试次数，未发布不完整结果", true,
                    Math.max(1, current.attempts()));
            EvidenceTask failed = current.fail(current.status() == EvidenceTaskStatus.RUNNING
                    ? current.lease().token() : null, failure, now);
            if (repository.replace(failed, current.revision())) future.complete(failed);
            else scheduleDrive(taskId, extractor, future, Duration.ZERO);
            return;
        }

        String token = UUID.randomUUID().toString();
        EvidenceTask leased;
        try {
            leased = current.acquire(ownerId, token, now, retryPolicy.leaseDuration());
        } catch (RuntimeException raced) {
            scheduleDrive(taskId, extractor, future, Duration.ZERO);
            return;
        }
        if (!repository.replace(leased, current.revision())) {
            scheduleDrive(taskId, extractor, future, Duration.ZERO);
            return;
        }

        // A dead worker becomes replaceable after the durable lease expires. A late worker can no
        // longer publish because completion uses revision CAS plus the lease token.
        scheduleDrive(taskId, extractor, future, retryPolicy.leaseDuration());
        try {
            worker.execute(() -> extract(leased, extractor, future));
        } catch (RuntimeException rejected) {
            publishFailure(leased, token, false, "EVIDENCE_WORKER_REJECTED",
                    safeMessage(rejected), extractor, future);
        }
    }

    private void extract(EvidenceTask leased, LocalEvidenceExtractor extractor,
                         CompletableFuture<EvidenceTask> future) {
        String token = leased.lease().token();
        try {
            EvidenceExtractionContext context = new EvidenceExtractionContext(
                    leased.key(), leased.attempts(), token, leased.lease().acquiredAt());
            EvidenceExtractionResult result = extractor.extract(context);
            if (result == null) {
                throw EvidenceExtractionException.permanent(
                        "EMPTY_EXTRACTOR_RESULT", "本地证据提取器返回了空结果");
            }
            EvidenceTask current = repository.find(leased.taskId()).orElse(null);
            if (current == null || !current.ownsLease(token)) {
                scheduleDrive(leased.taskId(), extractor, future, Duration.ZERO);
                return;
            }
            EvidenceTask completed = current.complete(token, result, clock.instant());
            if (repository.replace(completed, current.revision())) future.complete(completed);
            else scheduleDrive(leased.taskId(), extractor, future, Duration.ZERO);
        } catch (EvidenceExtractionException expected) {
            publishFailure(leased, token, expected.retryable(), expected.code(),
                    expected.getMessage(), extractor, future);
        } catch (Throwable unexpected) {
            publishFailure(leased, token, false, "EXTRACTOR_RUNTIME_ERROR",
                    safeMessage(unexpected), extractor, future);
        }
    }

    private void publishFailure(EvidenceTask leased, String token, boolean retryable,
                                String code, String message, LocalEvidenceExtractor extractor,
                                CompletableFuture<EvidenceTask> future) {
        EvidenceTask current = repository.find(leased.taskId()).orElse(null);
        if (current == null || !current.ownsLease(token)) {
            scheduleDrive(leased.taskId(), extractor, future, Duration.ZERO);
            return;
        }
        Instant now = clock.instant();
        EvidenceTaskFailure failure = new EvidenceTaskFailure(code, message, retryable, current.attempts());
        EvidenceTask replacement;
        if (retryable && current.attempts() < current.maxAttempts()) {
            Instant retryAt = now.plus(retryPolicy.backoffAfter(current.attempts()));
            replacement = current.waitForRetry(token, failure, retryAt, now);
        } else {
            replacement = current.fail(token, failure, now);
        }
        if (!repository.replace(replacement, current.revision())) {
            scheduleDrive(leased.taskId(), extractor, future, Duration.ZERO);
            return;
        }
        if (replacement.status().terminal()) future.complete(replacement);
        else scheduleDrive(leased.taskId(), extractor, future,
                Duration.between(now, replacement.nextAttemptAt()));
    }

    private static Duration minimumPositive(Duration first, Duration second) {
        Duration positiveFirst = first.isNegative() || first.isZero() ? Duration.ofMillis(1) : first;
        return positiveFirst.compareTo(second) < 0 ? positiveFirst : second;
    }

    private static void verifyExtractorIdentity(EvidenceNeedKey key, LocalEvidenceExtractor extractor) {
        if (!key.extractorId().equals(required(extractor.id(), "extractor.id"))
                || !key.extractorVersion().equals(required(extractor.version(), "extractor.version"))) {
            throw new IllegalArgumentException("extractor identity/version does not match evidence key");
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? "" : failure.getMessage();
        if (message == null || message.isBlank()) message = failure == null ? "" : failure.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static ScheduledExecutorService defaultScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, daemonFactory("evidence-scheduler-"));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ExecutorService defaultWorker() {
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        return Executors.newFixedThreadPool(threads, daemonFactory("evidence-worker-"));
    }

    private static ThreadFactory daemonFactory(String prefix) {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        if (!ownsExecutors) return;
        scheduler.shutdownNow();
        if (worker instanceof ExecutorService service) service.shutdownNow();
    }
}
