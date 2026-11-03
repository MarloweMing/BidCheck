package cn.bidcheck.compat.http;

import cn.bidcheck.compat.concurrent.Flow;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java 8 transport for the application's existing bounded response subscribers.
 * Each request has one wall-clock deadline, including upload and body reads.
 * Redirects are disabled so bearer credentials cannot follow a provider redirect.
 */
public final class HttpClient {
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ThreadFactory DAEMON_THREADS = work -> {
        Thread thread = new Thread(work, "bidcheck-http-" + THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(DAEMON_THREADS);
    private static final ExecutorService CLOSERS = Executors.newCachedThreadPool(DAEMON_THREADS);
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, DAEMON_THREADS);
    static { DEADLINES.setRemoveOnCancelPolicy(true); }

    private final Duration connectTimeout;

    private HttpClient(Builder builder) { connectTimeout = builder.connectTimeout; }
    public static Builder newBuilder() { return new Builder(); }
    public static HttpClient newHttpClient() { return newBuilder().build(); }

    public enum Redirect { NEVER }

    public static final class Builder {
        private Duration connectTimeout = Duration.ofSeconds(10);

        public Builder connectTimeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("Positive connect timeout required");
            }
            connectTimeout = timeout;
            return this;
        }

        public Builder followRedirects(Redirect policy) {
            Objects.requireNonNull(policy, "policy");
            return this;
        }

        public HttpClient build() { return new HttpClient(this); }
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        Exchange<T> exchange = new Exchange<>();
        exchange.result.whenComplete((response, error) -> {
            ScheduledFuture<?> deadline = exchange.deadline;
            if (deadline != null) deadline.cancel(false);
            if (exchange.result.isCancelled() || error != null) exchange.stop();
        });
        Duration timeout = request.timeout().orElse(Duration.ofSeconds(60));
        exchange.deadline = DEADLINES.schedule(() -> {
            exchange.result.completeExceptionally(new HttpTimeoutException("HTTP request timed out"));
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        try {
            exchange.worker = WORKERS.submit(() -> execute(request, handler, exchange, timeout));
            if (exchange.result.isDone()) exchange.stop();
        } catch (RuntimeException unavailable) {
            exchange.result.completeExceptionally(new IOException("HTTP transport unavailable"));
        }
        return exchange.result;
    }

    private <T> void execute(HttpRequest request, HttpResponse.BodyHandler<T> handler, Exchange<T> exchange, Duration timeout) {
        HttpResponse.BodySubscriber<T> subscriber = null;
        HttpURLConnection connection = null;
        try {
            if (exchange.result.isDone()) return;
            connection = (HttpURLConnection) request.uri().toURL().openConnection();
            exchange.connection = connection;
            if (exchange.result.isDone()) return;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(millis(connectTimeout.compareTo(timeout) < 0 ? connectTimeout : timeout));
            connection.setReadTimeout(millis(timeout));
            connection.setRequestMethod(request.method());
            connection.setUseCaches(false);
            for (Map.Entry<String, List<String>> entry : request.headers().map().entrySet()) {
                for (String value : entry.getValue()) connection.addRequestProperty(entry.getKey(), value);
            }
            byte[] payload = request.body();
            if (payload.length > 0 || "POST".equals(request.method()) || "PUT".equals(request.method())) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
            }
            if (exchange.result.isDone()) return;
            final int status = connection.getResponseCode();
            final HttpHeaders headers = new HttpHeaders(connection.getHeaderFields());
            subscriber = Objects.requireNonNull(handler.apply(new HttpResponse.ResponseInfo() {
                @Override public int statusCode() { return status; }
                @Override public HttpHeaders headers() { return headers; }
            }), "body subscriber");
            HttpResponse.BodySubscriber<T> responseBody = subscriber;
            responseBody.getBody().whenComplete((body, error) -> {
                if (error != null) exchange.result.completeExceptionally(error);
                else exchange.result.complete(new HttpResponse<>(status, headers, body));
            });
            Subscription subscription = new Subscription(exchange);
            subscriber.onSubscribe(subscription);
            InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (body == null) {
                if (!exchange.result.isDone()) subscriber.onComplete();
                return;
            }
            try (InputStream input = body) {
                byte[] buffer = new byte[8192];
                while (!exchange.result.isDone() && subscription.take()) {
                    int count = input.read(buffer);
                    if (count < 0) { subscriber.onComplete(); break; }
                    if (count > 0) subscriber.onNext(Collections.singletonList(ByteBuffer.wrap(Arrays.copyOf(buffer, count))));
                }
            }
        } catch (Throwable failure) {
            Throwable safe = failure instanceof SocketTimeoutException ? new HttpTimeoutException("HTTP request timed out") : failure;
            if (subscriber != null && !exchange.result.isDone()) {
                try { subscriber.onError(safe); }
                catch (Throwable ignored) { /* The transport future still receives the original failure. */ }
            }
            exchange.result.completeExceptionally(safe);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static int millis(Duration timeout) {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
    }

    private static final class Exchange<T> {
        final CompletableFuture<HttpResponse<T>> result = new CompletableFuture<>();
        volatile HttpURLConnection connection;
        volatile Future<?> worker;
        volatile ScheduledFuture<?> deadline;
        volatile boolean stopped;

        void stop() {
            stopped = true;
            Future<?> running = worker;
            if (running != null) running.cancel(true);
            HttpURLConnection open = connection;
            // Some URLConnection implementations hold a monitor while blocked on I/O.
            // Close separately so cancellation and the deadline thread always return promptly.
            if (open != null) CLOSERS.execute(open::disconnect);
        }
    }

    private static final class Subscription implements Flow.Subscription {
        private final Exchange<?> exchange;
        private long demand;
        private boolean cancelled;

        Subscription(Exchange<?> exchange) { this.exchange = exchange; }

        @Override public synchronized void request(long count) {
            if (count <= 0) {
                exchange.result.completeExceptionally(new IllegalArgumentException("Positive response demand required"));
                cancel();
                return;
            }
            demand = demand > Long.MAX_VALUE - count ? Long.MAX_VALUE : demand + count;
            notifyAll();
        }

        @Override public synchronized void cancel() {
            cancelled = true;
            notifyAll();
            HttpURLConnection open = exchange.connection;
            if (open != null) CLOSERS.execute(open::disconnect);
        }

        synchronized boolean take() throws InterruptedException {
            while (demand == 0 && !cancelled && !exchange.stopped && !exchange.result.isDone()) wait();
            if (cancelled || exchange.stopped || exchange.result.isDone()) return false;
            if (demand != Long.MAX_VALUE) demand--;
            return true;
        }
    }
}
