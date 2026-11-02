package cn.bidcheck.ocr;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** Process-wide provider admission. Pool identity never contains raw credentials or document data. */
public final class OcrRequestGovernor {
    private static final ConcurrentHashMap<String, OcrRequestGovernor> GROUPS = new ConcurrentHashMap<>();
    private static final long MAX_SHARED_COOLDOWN_MS = 60_000;
    private final Map<String, CompletableFuture<Object>> flights = new HashMap<>();
    private int limit, queueLimit, active, queued, followers;
    private long cooldownUntil;
    private int requestsPerMinute,burst,circuitThreshold,circuitOpenMillis,consecutiveFailures;
    private double tokens;
    private long tokenUpdated=System.nanoTime(),circuitUntil,healthEpoch;
    private boolean halfOpen;
    private final LongAdder circuitRejected=new LongAdder();
    private final LongAdder requests = new LongAdder(), attempts = new LongAdder(), successes = new LongAdder(),
            failures = new LongAdder(), cacheHits = new LongAdder(), sharedWaits = new LongAdder(), retries = new LongAdder(),
            rateLimited = new LongAdder(), timeouts = new LongAdder(), rejected = new LongAdder(),
            cancelled = new LongAdder(), queueNanos = new LongAdder(), networkNanos = new LongAdder();

    private OcrRequestGovernor(int concurrency, OcrRequestSettings settings) {
        limit=concurrency;queueLimit=settings.queueCapacity();requestsPerMinute=settings.requestsPerMinute();burst=settings.burst();tokens=burst;
        circuitThreshold=settings.circuitFailures();circuitOpenMillis=settings.circuitOpenMillis();
    }
    static OcrRequestGovernor forService(URI endpoint, String model, String key, int concurrency, OcrRequestSettings settings) {
        String group = hash((endpoint == null ? "disabled" : endpoint.normalize().toASCIIString()) + "\n" + model + "\n" + hash(key));
        OcrRequestGovernor governor = GROUPS.computeIfAbsent(group, ignored -> new OcrRequestGovernor(concurrency, settings));
        // Conflicting client settings must never create independent pools or raise an existing bound.
        synchronized (governor) {
            governor.limit=Math.min(governor.limit,concurrency);governor.queueLimit=Math.min(governor.queueLimit,settings.queueCapacity());
            if(settings.requestsPerMinute()>0)governor.requestsPerMinute=governor.requestsPerMinute==0?settings.requestsPerMinute():Math.min(governor.requestsPerMinute,settings.requestsPerMinute());
            governor.burst=Math.min(governor.burst,settings.burst());governor.tokens=Math.min(governor.tokens,governor.burst);
            governor.circuitThreshold=Math.min(governor.circuitThreshold,settings.circuitFailures());governor.circuitOpenMillis=Math.max(governor.circuitOpenMillis,settings.circuitOpenMillis());
        }
        return governor;
    }

    @FunctionalInterface interface Work<T> { T run() throws IOException; }
    record Shared<T>(T value, boolean reused) { }

    /** Duplicate waiters wait on the result, never on a network permit. Failed results are not cached. */
    <T> Shared<T> singleFlight(String key, Duration timeout, Work<T> work) throws IOException {
        interrupted();
        CompletableFuture<Object> future;
        boolean leader;
        synchronized (this) {
            future = flights.get(key); leader = future == null;
            if (leader) {
                if (flights.size() >= limit + queueLimit) { rejected.increment(); throw OcrFailure.of("QUEUE_FULL", true); }
                future = new CompletableFuture<>(); flights.put(key, future); requests.increment();
            } else {
                if (followers >= queueLimit) { rejected.increment(); throw OcrFailure.of("QUEUE_FULL", true); }
                followers++; sharedWaits.increment();
            }
        }
        if (!leader) {
            try {
                @SuppressWarnings("unchecked") T value = (T) future.get(timeout.multipliedBy(5).toNanos(), TimeUnit.NANOSECONDS);
                interrupted(); return new Shared<>(value, true);
            } catch (InterruptedException stopped) { cancelled.increment(); throw interruptedFailure(); }
            catch (TimeoutException expired) { timeouts.increment(); throw OcrFailure.of("QUEUE_TIMEOUT", true); }
            catch (ExecutionException failed) {
                if (failed.getCause() instanceof InterruptedIOException) throw OcrFailure.of("SHARED_REQUEST_CANCELLED", true);
                if (failed.getCause() instanceof IOException safe) throw safe;
                throw OcrFailure.of("INVALID_RESPONSE", false);
            } finally { synchronized (this) { followers--; } }
        }
        try {
            T result = work.run(); interrupted(); successes.increment(); future.complete(result); return new Shared<>(result, false);
        } catch (IOException | RuntimeException | Error failed) {
            failures.increment(); if (failed instanceof InterruptedIOException) cancelled.increment();
            // Adapters sanitize parsing errors before they can reach another waiter.
            future.completeExceptionally(failed instanceof IOException ? failed : OcrFailure.of("INVALID_RESPONSE", false)); throw failed;
        } finally { synchronized (this) { flights.remove(key, future); notifyAll(); } }
    }

    void cacheHit() { cacheHits.increment(); }

    /** Safe counters only: no endpoints, credentials, cache keys, prompts, or source text. */
    public static Map<String, Long> summary() {
        Map<String, Long> total = new LinkedHashMap<>();
        for (OcrRequestGovernor g : GROUPS.values()) synchronized (g) {
            add(total, "requests", g.requests.sum()); add(total, "attempts", g.attempts.sum());
            add(total, "successes", g.successes.sum()); add(total, "failures", g.failures.sum());
            add(total, "cacheHits", g.cacheHits.sum()); add(total, "sharedWaits", g.sharedWaits.sum());
            add(total, "retries", g.retries.sum()); add(total, "rateLimited", g.rateLimited.sum());
            add(total, "timeouts", g.timeouts.sum()); add(total, "rejected", g.rejected.sum()); add(total, "cancelled", g.cancelled.sum());
            add(total, "queueMillis", TimeUnit.NANOSECONDS.toMillis(g.queueNanos.sum()));
            add(total, "networkMillis", TimeUnit.NANOSECONDS.toMillis(g.networkNanos.sum()));
            add(total, "active", g.active); add(total, "queued", g.queued); add(total, "sharedWaiting", g.followers); add(total, "inFlightImages", g.flights.size());
            add(total,"circuitRejected",g.circuitRejected.sum());add(total,"openCircuits",g.circuitUntil>System.nanoTime()||g.halfOpen?1:0);
            total.merge("maxCooldownMillis", Math.max(0, TimeUnit.NANOSECONDS.toMillis(g.cooldownUntil - System.nanoTime())), Math::max);
        }
        return Collections.unmodifiableMap(total);
    }
    private static void add(Map<String, Long> total, String key, long value) { total.merge(key, value, Long::sum); }

    HttpResponse<byte[]> send(HttpClient client, URI endpoint, String key, byte[] payload, int responseLimit,
                              Duration timeout, OcrRequestSettings settings) throws IOException {
        return send(client, endpoint, key, payload, responseLimit, timeout, settings, null);
    }

    /** One budget follows a complete batch tree, including retries inside each node. */
    static final class RequestBudget {
        private int remaining;
        private final long deadline;
        private OcrFailure stopped;
        RequestBudget(int maxAttempts, Duration totalTimeout) {
            remaining = maxAttempts; deadline = System.nanoTime() + totalTimeout.toNanos();
        }
        synchronized void check() throws OcrFailure {
            if (stopped != null) throw stopped;
            if (remaining <= 0 || System.nanoTime() >= deadline) throw OcrFailure.of("BATCH_BUDGET_EXHAUSTED", true);
        }
        synchronized void stop(OcrFailure failure) { if (stopped == null) stopped = failure; }
        synchronized void take() throws OcrFailure { check(); remaining--; }
        long deadline() { return deadline; }
    }

    HttpResponse<byte[]> send(HttpClient client, URI endpoint, String key, byte[] payload, int responseLimit,
                              Duration timeout, OcrRequestSettings settings, RequestBudget batchBudget) throws IOException {
        return sendAudited(client,endpoint,key,payload,responseLimit,timeout,settings,batchBudget,null);
    }
    @FunctionalInterface interface ResponseObserver { void accept(int attempt,int status,byte[] response); }
    @FunctionalInterface interface FailureObserver { void accept(int attempt,String phase,String code,Integer status,long durationMillis); }
    HttpResponse<byte[]> sendAudited(HttpClient client, URI endpoint, String key, byte[] payload, int responseLimit,
                              Duration timeout, OcrRequestSettings settings, RequestBudget batchBudget,ResponseObserver observer) throws IOException {
        return sendAudited(client,endpoint,key,payload,responseLimit,timeout,settings,batchBudget,observer,null);
    }
    HttpResponse<byte[]> sendAudited(HttpClient client, URI endpoint, String key, byte[] payload, int responseLimit,
                              Duration timeout, OcrRequestSettings settings, RequestBudget batchBudget,ResponseObserver observer,FailureObserver failures) throws IOException {
        long outerDeadline = batchBudget == null ? Long.MAX_VALUE : batchBudget.deadline();
        long queueDeadline = Math.min(outerDeadline, System.nanoTime() + timeout.multipliedBy(2).toNanos());
        long networkDeadline = 0;
        OcrFailure last = null;
        for (int attempt = 1; attempt <= settings.retryAttempts(); attempt++) {
            interrupted();
            if (batchBudget != null) batchBudget.check();
            long admissionStarted=System.nanoTime();Admission admission;
            try{admission=acquire(networkDeadline == 0 ? queueDeadline : networkDeadline);}
            catch(OcrFailure failed){observeFailure(failures,attempt,"ADMISSION",failed,admissionStarted);throw failed;}
            if (networkDeadline == 0) networkDeadline = Math.min(outerDeadline, System.nanoTime() + timeout.multipliedBy(3).toNanos());
            HttpResponse<byte[]> response = null;
            long delay = 0;
            long started = System.nanoTime();
            boolean sent = false;
            try {
                long budget = Math.min(timeout.toNanos(), remaining(networkDeadline));
                if (batchBudget != null) batchBudget.take();
                attempts.increment();
                sent = true;
                response = exchange(client, endpoint, key, payload, responseLimit, Duration.ofNanos(budget));
                if(observer!=null)try{observer.accept(attempt,response.statusCode(),response.body());}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(OcrRequestGovernor.class).warn("bidcheck.page_layout_response_audit status=OBSERVER_FAILED attempt={}",attempt);}
                if (response.statusCode() == 200) { recordHealth(admission,true,false);return response; }
                last = OcrFailure.http(response.statusCode());
                recordHealth(admission,false,response.statusCode()>=500);
                delay = retryDelay(response, attempt, settings);
                // Publish cooldown before releasing the permit, including on the last attempt.
                // An untrusted Retry-After may stop this request but cannot freeze all future work indefinitely.
                if (response.statusCode() == 429 || response.statusCode() == 503) synchronized (this) {
                    cooldownUntil = Math.max(cooldownUntil, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(delay, MAX_SHARED_COOLDOWN_MS)));
                    notifyAll();
                }
                if (response.statusCode() == 429) rateLimited.increment();
            } catch (OcrFailure failed) { observeFailure(failures,attempt,sent?"TRANSPORT":"BEFORE_SEND",failed,started);last = failed;delay=retryDelay(null,attempt,settings);if(sent)recordHealth(admission,false,Set.of("TIMEOUT","TRANSPORT_ERROR").contains(failed.code()));if ("TIMEOUT".equals(failed.code())) timeouts.increment(); }
            finally { networkNanos.add(System.nanoTime() - started); release(admission); }
            if (!last.temporary() || "BATCH_BUDGET_EXHAUSTED".equals(last.code()) || attempt == settings.retryAttempts()) throw last;
            // Retry-After is never shortened to fit the budget; refuse this retry instead.
            if (TimeUnit.MILLISECONDS.toNanos(delay) >= networkDeadline - System.nanoTime()) throw last;
            retries.increment();
            try { TimeUnit.MILLISECONDS.sleep(delay); }
            catch (InterruptedException stopped) { throw interruptedFailure(); }
        }
        throw last;
    }
    private static void observeFailure(FailureObserver observer,int attempt,String phase,OcrFailure failure,long started){
        if(observer!=null)try{observer.accept(attempt,phase,failure.code(),failure.httpStatus(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));}
        catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(OcrRequestGovernor.class).warn("bidcheck.page_layout_failure_audit status=OBSERVER_FAILED attempt={}",attempt);}
    }

    private record Admission(long epoch,boolean probe) { }
    private synchronized Admission acquire(long deadline) throws IOException {
        interrupted();
        checkCircuit();
        if (deadline <= System.nanoTime()) { timeouts.increment(); throw OcrFailure.of("QUEUE_TIMEOUT", true); }
        if (active < limit && cooldownUntil <= System.nanoTime() && queued == 0 && tokenWait()==0) return admit();
        if (queued >= queueLimit) { rejected.increment(); throw OcrFailure.of("QUEUE_FULL", true); }
        queued++; long started = System.nanoTime();
        try {
            while (active >= limit || cooldownUntil > System.nanoTime() || tokenWait()>0) {
                checkCircuit();
                long left = deadline - System.nanoTime();
                if (left <= 0) { timeouts.increment(); throw OcrFailure.of("QUEUE_TIMEOUT", true); }
                long cooldown = cooldownUntil - System.nanoTime();
                long token=tokenWait();long wait=cooldown>0?Math.min(left,cooldown):left;if(token>0)wait=Math.min(wait,token);
                try { TimeUnit.NANOSECONDS.timedWait(this, Math.max(1,wait)); }
                catch (InterruptedException stopped) { throw interruptedFailure(); }
            }
            if (deadline <= System.nanoTime()) throw OcrFailure.of("QUEUE_TIMEOUT", true);
            interrupted();checkCircuit();return admit();
        } finally { queued--; queueNanos.add(System.nanoTime() - started); }
    }
    private synchronized void release(Admission admission) {
        active--;
        // A cancelled probe releases only its own slot; old requests cannot clear a newer probe.
        if(admission.probe()&&admission.epoch()==healthEpoch)halfOpen=false;
        notifyAll();
    }
    private void checkCircuit()throws OcrFailure {
        if(circuitUntil>System.nanoTime()||halfOpen){circuitRejected.increment();throw OcrFailure.of("CIRCUIT_OPEN",true);}
    }
    private Admission admit(){if(requestsPerMinute>0)tokens=Math.max(0,tokens-1);active++;boolean probe=consecutiveFailures>=circuitThreshold;if(probe)halfOpen=true;return new Admission(healthEpoch,probe);}
    private long tokenWait(){
        if(requestsPerMinute==0)return 0;
        long now=System.nanoTime();tokens=Math.min(burst,tokens+(now-tokenUpdated)*(requestsPerMinute/60_000_000_000d));tokenUpdated=now;
        return tokens>=1?0:(long)Math.ceil((1-tokens)*60_000_000_000d/requestsPerMinute);
    }
    private synchronized void recordHealth(Admission admission,boolean success,boolean serviceFailure){
        // In-flight replies admitted before the circuit opened cannot heal or reopen a later epoch.
        if(admission.epoch()!=healthEpoch)return;
        if(success||admission.probe()&&!serviceFailure){consecutiveFailures=0;circuitUntil=0;halfOpen=false;if(admission.probe())healthEpoch++;}
        else if(serviceFailure&&++consecutiveFailures>=circuitThreshold){circuitUntil=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(circuitOpenMillis);halfOpen=false;healthEpoch++;}
        notifyAll();
    }

    private static HttpResponse<byte[]> exchange(HttpClient client, URI endpoint, String key, byte[] payload, int limit, Duration timeout) throws IOException {
        var body = new DashScopeOcrClient.LimitedBodySubscriber(limit);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            pending = client.sendAsync(request, ignored -> body);
            return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException stopped) { throw interruptedFailure(); }
        catch (TimeoutException expired) { throw OcrFailure.of("TIMEOUT", true); }
        catch (ExecutionException failed) {
            if (body.limitExceeded()) throw OcrFailure.of("RESPONSE_LIMIT", false);
            Throwable cause = failed.getCause();
            boolean timeoutFailure = cause instanceof HttpTimeoutException;
            throw OcrFailure.of(timeoutFailure ? "TIMEOUT" : "TRANSPORT_ERROR", timeoutFailure);
        } catch (IllegalArgumentException invalid) { throw OcrFailure.of("INVALID_REQUEST", false); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); body.cancel(); }
    }

    private static long retryDelay(HttpResponse<?> response, int attempt, OcrRequestSettings settings) {
        long base = Math.min(settings.retryMaxDelayMs(), (long) settings.retryBaseMs() << (attempt - 1));
        long delay = Math.min(settings.retryMaxDelayMs(), base + ThreadLocalRandom.current().nextLong(Math.max(1, base / 4)));
        if (response != null) {
            String value = response.headers().firstValue("Retry-After").orElse("").strip();
            try { if (value.matches("[0-9]+")) delay = Math.max(delay, value.length() > 9 ? Long.MAX_VALUE : Long.parseLong(value) * 1000);
                else if (!value.isEmpty()) delay = Math.max(delay, Math.max(0, Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()));
            } catch (RuntimeException malformed) { /* Ignore malformed hints, retaining bounded backoff. */ }
        }
        return delay;
    }
    private static long remaining(long deadline) throws OcrFailure {
        long remaining = deadline - System.nanoTime(); if (remaining <= 0) throw OcrFailure.of("TIMEOUT", true); return remaining;
    }
    private static void interrupted() throws InterruptedIOException { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("OCR 识别已中断。"); }
    private static InterruptedIOException interruptedFailure() { Thread.currentThread().interrupt(); return new InterruptedIOException("OCR 识别已中断。"); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
