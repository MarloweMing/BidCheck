package cn.bidcheck.ocr;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Only loopback stubs and synthetic images. Exercises actual HTTP admission and retry behavior. */
class OcrRequestGovernorTest {
    @TempDir Path data;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger(), active = new AtomicInteger(), peak = new AtomicInteger();
    private final List<Long> arrivals = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService handlers, callers;
    private String url;
    private byte[] image;
    private volatile Responder responder;
    private volatile CountDownLatch release;
    private final OcrRequestSettings quick = new OcrRequestSettings(16, 3, 1, 2);

    @FunctionalInterface interface Responder { void respond(HttpExchange exchange, int call, JsonNode request) throws Exception; }

    @BeforeEach void setup() throws Exception {
        image = image(1); callers = Executors.newFixedThreadPool(8); handlers = Executors.newFixedThreadPool(8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(handlers);
        responder = (exchange, call, request) -> success(exchange, request);
        server.createContext("/ocr", exchange -> {
            int call = calls.incrementAndGet(); arrivals.add(System.nanoTime()); peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { responder.respond(exchange, call, json.readTree(exchange.getRequestBody())); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (Exception disconnected) { /* Cancellation closes the local response stream. */ }
            finally { active.decrementAndGet(); exchange.close(); }
        });
        server.start(); url = "http://127.0.0.1:" + server.getAddress().getPort() + "/ocr";
    }
    @AfterEach void close() throws Exception {
        Thread.interrupted(); if (release != null) release.countDown(); callers.shutdownNow(); server.stop(0); handlers.shutdownNow();
        callers.awaitTermination(2, TimeUnit.SECONDS); handlers.awaitTermination(2, TimeUnit.SECONDS);
    }
    private DashScopeOcrClient text(String model, String key, int limit, Duration timeout, OcrRequestSettings settings) {
        return new DashScopeOcrClient(true, url, key, model, data.resolve("text"), timeout, true, limit, settings);
    }
    private DashScopeOcrClient text(String model, int limit) { return text(model, "synthetic-secret", limit, Duration.ofSeconds(3), quick); }
    private DashScopeAdvancedOcrClient advanced(String model, int limit) {
        return new DashScopeAdvancedOcrClient(true, url, "synthetic-secret", model, data.resolve("advanced"), Duration.ofSeconds(3), true, limit, quick);
    }
    private void success(HttpExchange exchange, JsonNode request) throws IOException {
        String task = request.at("/parameters/ocr_options/task").asText();
        Object content = "advanced_recognition".equals(task)
                ? Map.of("ocr_result", Map.of("words_info", List.of(Map.of("text", "项目甲", "rotate_rect", List.of(50, 30, 80, 20, 0)))))
                : Map.of("text", task.isBlank() ? json.writeValueAsString(Map.of("status", "COMPLETE", "textBoxes", List.of(Map.of(
                    "id", "b1", "frameId", "image", "text", "项目甲", "bbox", Map.of("x", 10, "y", 20, "width", 80, "height", 20))),
                    "tables", List.of(), "readingOrder", List.of("b1"))) : "项目甲");
        respond(exchange, 200, json.writeValueAsString(Map.of("output", Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", List.of(content))))))));
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    private static byte[] image(int color) throws IOException {
        BufferedImage value = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB); value.setRGB(0, 0, color);
        var out = new ByteArrayOutputStream(); ImageIO.write(value, "png", out); value.flush(); return out.toByteArray();
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Expected asynchronous state before bounded timeout");
    }
    private static long counter(String name) { return OcrRequestGovernor.summary().getOrDefault(name, 0L); }

    @Test void allThreeAdaptersShareServiceCapacityDespiteDifferentConfiguredIntegers() throws Exception {
        var text = text("shared", 1); var advanced = advanced("shared", 3);
        var layout = new DashScopeLayoutClient(true, url, "synthetic-secret", "shared", data.resolve("layout"), 4, Duration.ofSeconds(3), true, quick);
        release = new CountDownLatch(1);
        responder = (exchange, call, request) -> { release.await(3, TimeUnit.SECONDS); success(exchange, request); };
        var first = callers.submit(() -> text.recognize(image, "image/png")); await(() -> calls.get() == 1);
        var second = callers.submit(() -> advanced.recognize(image, "image/png"));
        var third = callers.submit(() -> layout.recognize(image, "image/png", "项目甲"));
        await(() -> counter("queued") >= 2); assertEquals(1, calls.get()); release.countDown();
        assertNotNull(first.get(3, TimeUnit.SECONDS)); assertNotNull(second.get(3, TimeUnit.SECONDS)); assertNotNull(third.get(3, TimeUnit.SECONDS));
        assertEquals(3, calls.get()); assertEquals(1, peak.get());
    }

    @Test void differentCredentialsAndModelsHaveIndependentAdmissionGroups() throws Exception {
        var first = text("one", "key-one", 1, Duration.ofSeconds(3), quick);
        var otherKey = text("one", "key-two", 1, Duration.ofSeconds(3), quick);
        var otherModel = text("two", "key-one", 1, Duration.ofSeconds(3), quick);
        release = new CountDownLatch(1);
        responder = (exchange, call, request) -> { release.await(3, TimeUnit.SECONDS); success(exchange, request); };
        var a = callers.submit(() -> first.recognize(image, "image/png"));
        var b = callers.submit(() -> otherKey.recognize(image(2), "image/png"));
        var c = callers.submit(() -> otherModel.recognize(image(3), "image/png"));
        await(() -> calls.get() == 3); assertEquals(3, peak.get()); release.countDown();
        a.get(3, TimeUnit.SECONDS); b.get(3, TimeUnit.SECONDS); c.get(3, TimeUnit.SECONDS);
    }

    @Test void duplicateImageWaiterDoesNotOccupyAnAvailableNetworkSlot() throws Exception {
        var client = text("duplicate", 2); release = new CountDownLatch(1);
        responder = (exchange, call, request) -> { if (call == 1) release.await(3, TimeUnit.SECONDS); success(exchange, request); };
        long shared = counter("sharedWaits");
        var first = callers.submit(() -> client.recognize(image, "image/png")); await(() -> calls.get() == 1);
        var duplicate = callers.submit(() -> client.recognize(image, "image/png")); await(() -> counter("sharedWaits") > shared);
        var other = callers.submit(() -> client.recognize(image(2), "image/png"));
        assertFalse(other.get(2, TimeUnit.SECONDS).cached()); assertEquals(2, calls.get()); assertFalse(duplicate.isDone());
        release.countDown(); assertFalse(first.get(2, TimeUnit.SECONDS).cached()); assertTrue(duplicate.get(2, TimeUnit.SECONDS).cached());
        assertEquals(2, calls.get());
    }

    @Test void fullQueueRejectsBeforeSendingAndRecoversAfterActiveRequestFinishes() throws Exception {
        var client = text("queue", "synthetic-secret", 1, Duration.ofSeconds(3), new OcrRequestSettings(1, 3, 1, 2));
        release = new CountDownLatch(1); responder = (exchange, call, request) -> { release.await(3, TimeUnit.SECONDS); success(exchange, request); };
        var first = callers.submit(() -> client.recognize(image, "image/png")); await(() -> calls.get() == 1);
        var second = callers.submit(() -> client.recognize(image(2), "image/png")); await(() -> counter("queued") >= 1);
        OcrFailure failed = assertThrows(OcrFailure.class, () -> client.recognize(image(3), "image/png"));
        assertEquals("QUEUE_FULL", failed.code()); assertTrue(failed.temporary()); assertEquals(1, calls.get());
        release.countDown(); first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS);
        assertNotNull(client.recognize(image(3), "image/png")); assertEquals(3, calls.get());
    }

    @Test void onlySpecifiedTemporaryStatusesRetryAndSuccessfulRetryIsCached() throws Exception {
        for (int status : new int[]{429, 408, 502, 503, 504}) {
            int before = calls.get(); var client = text("retry-" + status, 1);
            responder = (exchange, call, request) -> { if (call - before <= 2) respond(exchange, status, "synthetic-secret private document"); else success(exchange, request); };
            assertFalse(client.recognize(image, "image/png").cached()); assertEquals(before + 3, calls.get());
            assertTrue(client.recognize(image, "image/png").cached()); assertEquals(before + 3, calls.get());
        }
    }

    @Test void nonRetryableStatusesAndMalformedSuccessfulRepliesAreNeverRetried() throws Exception {
        for (int status : new int[]{302, 400, 401, 403, 404, 500}) {
            int before = calls.get(); responder = (exchange, call, request) -> respond(exchange, status, "synthetic-secret private document");
            OcrFailure failed = assertThrows(OcrFailure.class, () -> text("hard-" + status, 1).recognize(image, "image/png"));
            assertFalse(failed.temporary()); assertEquals(before + 1, calls.get()); assertNull(failed.getCause()); assertFalse(failed.getMessage().contains("synthetic-secret"));
        }
        int before = calls.get(); responder = (exchange, call, request) -> respond(exchange, 200, "synthetic-secret malformed JSON");
        IOException failed = assertThrows(IOException.class, () -> text("malformed", 1).recognize(image, "image/png"));
        assertEquals(before + 1, calls.get()); assertNull(failed.getCause()); assertFalse(failed.getMessage().contains("synthetic-secret"));
    }

    @Test void retryAfterAppliesToOtherPagesSharingServiceAndBackoffHoldsNoPermit() throws Exception {
        var client = text("rate", 1); long previousLimited = counter("rateLimited");
        responder = (exchange, call, request) -> {
            if (call == 1) { exchange.getResponseHeaders().set("Retry-After", "1"); respond(exchange, 429, "busy"); }
            else success(exchange, request);
        };
        var first = callers.submit(() -> client.recognize(image, "image/png"));
        await(() -> counter("rateLimited") > previousLimited && counter("active") == 0);
        var other = callers.submit(() -> client.recognize(image(2), "image/png"));
        Thread.sleep(100); assertEquals(1, calls.get()); assertEquals(0, counter("active"));
        first.get(4, TimeUnit.SECONDS); other.get(4, TimeUnit.SECONDS); assertEquals(3, calls.get());
        assertTrue(Duration.ofNanos(arrivals.get(1) - arrivals.get(0)).toMillis() >= 900);
    }

    @Test void retryAfterLongerThanTotalBudgetFailsWithoutSleepingOrSendingAnotherRequest() throws Exception {
        responder = (exchange, call, request) -> { exchange.getResponseHeaders().set("Retry-After", "999999999"); respond(exchange, 429, "busy"); };
        var client = text("long-hint", "synthetic-secret", 1, Duration.ofMillis(200), quick);
        long started = System.nanoTime(); OcrFailure failure = assertThrows(OcrFailure.class, () -> client.recognize(image, "image/png"));
        assertEquals(429, failure.httpStatus()); assertTrue(failure.temporary()); assertEquals(1, calls.get());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1500);
        assertTrue(counter("maxCooldownMillis") > 0 && counter("maxCooldownMillis") <= 60000);
    }

    @Test void unexpectedLeaderErrorCompletesDuplicateWaitersWithoutTheirFullTimeout() throws Exception {
        var governor = OcrRequestGovernor.forService(java.net.URI.create(url), "leader-error", "synthetic-secret", 1, quick);
        release = new CountDownLatch(1); var entered = new CountDownLatch(1); long shared = counter("sharedWaits");
        var leader = callers.submit(() -> governor.singleFlight("same", Duration.ofSeconds(3), () -> {
            entered.countDown(); try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            throw new AssertionError("synthetic-secret must not reach followers");
        }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var follower = callers.submit(() -> governor.singleFlight("same", Duration.ofSeconds(3), () -> "unused"));
        await(() -> counter("sharedWaits") > shared); release.countDown();
        assertInstanceOf(AssertionError.class, assertThrows(ExecutionException.class, () -> leader.get(1, TimeUnit.SECONDS)).getCause());
        Throwable failed = assertThrows(ExecutionException.class, () -> follower.get(1, TimeUnit.SECONDS)).getCause();
        assertInstanceOf(OcrFailure.class, failed); assertFalse(failed.getMessage().contains("synthetic-secret"));
        assertEquals(0, counter("inFlightImages"));
    }

    @Test void transportTimeoutRetriesAtMostThreeTimesWithinTotalBudget() throws Exception {
        responder = (exchange, call, request) -> { Thread.sleep(800); success(exchange, request); };
        var client = text("timeout", "synthetic-secret", 1, Duration.ofMillis(150), quick);
        long started = System.nanoTime(); OcrFailure failed = assertThrows(OcrFailure.class, () -> client.recognize(image, "image/png"));
        assertEquals("TIMEOUT", failed.code()); assertTrue(failed.temporary()); assertTrue(calls.get() >= 2 && calls.get() <= 3);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1500); assertEquals(0, counter("active"));
    }

    @Test void interruptDuringRetryBackoffPreservesSignalAndPreventsFurtherAttempts() throws Exception {
        responder = (exchange, call, request) -> respond(exchange, 503, "busy");
        var client = text("cancel-retry", "synthetic-secret", 1, Duration.ofSeconds(3), new OcrRequestSettings(16, 3, 1000, 1000));
        long previousRetries = counter("retries"); var error = new AtomicReference<Throwable>(); var preserved = new AtomicBoolean();
        Thread request = new Thread(() -> { try { client.recognize(image, "image/png"); } catch (Throwable failed) { error.set(failed); preserved.set(Thread.currentThread().isInterrupted()); } });
        request.start();
        try { await(() -> counter("retries") > previousRetries); request.interrupt(); request.join(1500);
            assertFalse(request.isAlive()); assertInstanceOf(InterruptedIOException.class, error.get()); assertTrue(preserved.get());
            assertEquals(1, calls.get()); assertEquals(0, counter("active"));
        } finally { request.interrupt(); request.join(1500); }
    }

    @Test void cancellingDuplicateWaiterDoesNotCancelLeaderOrConsumeAnotherCall() throws Exception {
        var client = text("cancel-follower", 1); release = new CountDownLatch(1);
        responder = (exchange, call, request) -> { release.await(3, TimeUnit.SECONDS); success(exchange, request); };
        var leader = callers.submit(() -> client.recognize(image, "image/png")); await(() -> calls.get() == 1);
        long previousShared = counter("sharedWaits"); var error = new AtomicReference<Throwable>(); var preserved = new AtomicBoolean();
        Thread follower = new Thread(() -> { try { client.recognize(image, "image/png"); } catch (Throwable failed) { error.set(failed); preserved.set(Thread.currentThread().isInterrupted()); } });
        follower.start();
        try { await(() -> counter("sharedWaits") > previousShared); follower.interrupt(); follower.join(1500);
            assertInstanceOf(InterruptedIOException.class, error.get()); assertTrue(preserved.get()); assertFalse(leader.isDone());
            release.countDown(); assertFalse(leader.get(2, TimeUnit.SECONDS).cached()); assertEquals(1, calls.get());
        } finally { follower.interrupt(); follower.join(1500); }
    }

    @Test void exhaustedTransientAdvancedFailureCannotTriggerImmediatePlainOcrFallback() throws Exception {
        Path python = Files.writeString(data.resolve("dummy-runtime"), "never executed");
        Path worker = Files.writeString(data.resolve("dummy-worker.py"), "never executed");
        var fallbackCalls = new AtomicInteger();
        OcrClient fallback = new OcrClient() {
            public boolean enabled() { return true; } public String modelName() { return "fallback"; } public String identity() { return "fallback"; }
            public OcrResult recognize(byte[] image, String mime) { fallbackCalls.incrementAndGet(); return new OcrResult("项目甲", "local", "fallback", false); }
        };
        try (var grid = new PixelGridClient(true, python.toString(), worker.toString(), data.toString(), 1, 1)) {
            responder = (exchange, call, request) -> respond(exchange, 503, "busy");
            var pipeline = new StructuredOcrClient(fallback, advanced("advanced-temporary", 1), grid);
            OcrFailure failed = assertThrows(OcrFailure.class, () -> pipeline.recognize(image, "image/png"));
            assertTrue(failed.temporary()); assertEquals(3, calls.get()); assertEquals(0, fallbackCalls.get());
            responder = (exchange, call, request) -> respond(exchange, 200, "{}");
            var unsupported = new StructuredOcrClient(fallback, advanced("advanced-unsupported", 1), grid);
            assertEquals("项目甲", unsupported.recognize(image, "image/png").text()); assertEquals(1, fallbackCalls.get());
        }
        String summary = OcrRequestGovernor.summary().toString();
        assertFalse(summary.contains("synthetic-secret")); assertFalse(summary.contains("项目甲")); assertFalse(summary.contains(url));
        assertEquals(0, counter("active")); assertEquals(0, counter("queued")); assertEquals(0, counter("sharedWaiting"));
    }
}
