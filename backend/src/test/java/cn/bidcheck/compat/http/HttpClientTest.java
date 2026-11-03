package cn.bidcheck.compat.http;

import cn.bidcheck.compat.concurrent.Flow;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HttpClientTest {
    private HttpServer server;
    private ExecutorService handlers;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stopServer() {
        server.stop(0);
        handlers.shutdownNow();
    }

    @Test void preservesPostBodyErrorStatusAndHeaders() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        server.createContext("/post", exchange -> {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = exchange.getRequestBody().read(buffer)) >= 0) body.write(buffer, 0, read);
            received.set(new String(body.toByteArray(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Retry-After", "7");
            reply(exchange, 429, "busy");
        });
        HttpRequest request = request("/post", Duration.ofSeconds(3))
                .header("Authorization", "Bearer test")
                .POST(HttpRequest.BodyPublishers.ofString("{\"内容\":\"正文\"}")).build();
        HttpResponse<byte[]> response = client.sendAsync(request, ignored -> new LimitedBody(1024)).get(5, TimeUnit.SECONDS);
        assertEquals(429, response.statusCode());
        assertEquals("7", response.headers().firstValue("retry-after").orElse(""));
        assertEquals("busy", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("{\"内容\":\"正文\"}", received.get());
    }

    @Test void neverFollowsRedirectsWithCredentials() throws Exception {
        AtomicInteger followed = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/target");
            reply(exchange, 302, "redirect");
        });
        server.createContext("/target", exchange -> { followed.incrementAndGet(); reply(exchange, 200, "secret"); });
        HttpResponse<byte[]> response = client.sendAsync(request("/redirect", Duration.ofSeconds(3))
                .header("Authorization", "Bearer test").build(), ignored -> new LimitedBody(1024)).get(5, TimeUnit.SECONDS);
        assertEquals(302, response.statusCode());
        assertEquals(0, followed.get());
    }

    @Test void subscriberRejectsOversizedBodyWithoutAcceptingPartialData() throws Exception {
        server.createContext("/large", exchange -> reply(exchange, 200, String.join("", java.util.Collections.nCopies(100000, "x"))));
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request("/large", Duration.ofSeconds(3)).build(), ignored -> new LimitedBody(32));
        ExecutionException failed = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
        assertEquals("RESPONSE_LIMIT", failed.getCause().getMessage());
    }

    @Test void timeoutCoversSlowBodyAsWellAsResponseHeaders() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 5);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        try {
            CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request("/slow", Duration.ofMillis(150)).build(), ignored -> new LimitedBody(1024));
            ExecutionException failed = assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
            assertTrue(failed.getCause() instanceof HttpTimeoutException);
        } finally { release.countDown(); }
    }

    @Test void cancellationAndInterruptedWaitersReturnPromptly() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/waiting", exchange -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request("/waiting", Duration.ofSeconds(4)).build(), ignored -> new LimitedBody(1024));
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try { pending.get(); }
            catch (InterruptedException interrupted) { waiterFailure.set(interrupted); Thread.currentThread().interrupt(); }
            catch (Exception unexpected) { waiterFailure.set(unexpected); }
            finally { pending.cancel(true); }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            waiter.start();
            waiter.interrupt();
            waiter.join(2000);
            assertFalse(waiter.isAlive());
            assertTrue(waiterFailure.get() instanceof InterruptedException);
            assertTrue(pending.isCancelled());
            assertThrows(CancellationException.class, pending::join);
        } finally { release.countDown(); }
    }

    private HttpRequest.Builder request(String path, Duration timeout) { return HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout); }
    private static void reply(HttpExchange exchange, int status, String value) throws IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try { exchange.getResponseBody().write(body); }
        finally { exchange.close(); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("RESPONSE_LIMIT"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
