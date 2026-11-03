package cn.bidcheck.service;
import cn.bidcheck.compat.HexFormat;

import cn.bidcheck.document.*;
import cn.bidcheck.ocr.*;
import cn.bidcheck.persistence.Store;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual local HTTP transport + parser + committed H2 journal, with no external OCR service. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:microbatch-workflow;DB_CLOSE_DELAY=-1", "bidcheck.tasks.poll-ms=600000"})
class MicrobatchWorkflowIntegrationTest {
    @Autowired Store store;
    @Autowired PlatformTransactionManager manager;
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    @Test void actualMultiImageHttpPersistsPartialProgressRetriesOnlyMissingAndRestoresNextVersion() throws Exception {
        Path source = fixture();
        String sourceHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        String project = Store.id(), firstDocument = Store.id(), secondDocument = Store.id();
        java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode> requests = new CopyOnWriteArrayList<JsonNode>();
        java.util.concurrent.CopyOnWriteArrayList<java.lang.Integer> sizes = new CopyOnWriteArrayList<Integer>();
        java.util.concurrent.atomic.AtomicInteger calls = new AtomicInteger(); java.util.concurrent.CountDownLatch durableSeven = new CountDownLatch(7);
        java.util.concurrent.atomic.AtomicReference<java.lang.Throwable> serverFailure = new AtomicReference<Throwable>();
        com.sun.net.httpserver.HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.ExecutorService threads = Executors.newFixedThreadPool(2); server.setExecutor(threads);
        server.createContext("/ocr", exchange -> {
            try {
                JsonNode request = json.readTree(exchange.getRequestBody()); requests.add(request);
                java.util.ArrayList<com.fasterxml.jackson.databind.node.ObjectNode> items = new ArrayList<ObjectNode>(); int images = 0;
                for (JsonNode content : request.path("input").path("messages").get(0).path("content")) {
                    if (content.has("image")) images++;
                    String text = content.path("text").asText();
                    if (text.startsWith("IMAGE_META ")) {
                        ObjectNode item = (ObjectNode) json.readTree(text.substring(11));
                        item.put("text", "合成扫描原文：" + item.path("id").asText()); items.add(item);
                    }
                }
                sizes.add(images); assertEquals(images, items.size());
                int number = calls.incrementAndGet();
                if (number == 1) {
                    assertEquals(8, images, "The first actual HTTP body must contain eight independent images");
                    items.remove(7);
                } else if (number == 2) {
                    assertEquals(1, images); assertEquals("BLOCK:8:1", items.get(0).path("id").asText());
                    assertTrue(durableSeven.await(10, TimeUnit.SECONDS), "Seven pages must commit while the eighth HTTP request is pending");
                    assertEquals(7, store.maps("ocr_unit_status", project, firstDocument).stream().filter(row -> "SUCCEEDED".equals(row.get("status"))).count());
                } else fail("A successful page was unnecessarily sent again");
                String body = json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("items", items));
                byte[] bytes = json.writeValueAsBytes(cn.bidcheck.compat.Java8Api.mapOf("output", cn.bidcheck.compat.Java8Api.mapOf("choices", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("finish_reason", "stop", "message", cn.bidcheck.compat.Java8Api.mapOf("role", "assistant", "content", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("text", body))))))));
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) {
                serverFailure.compareAndSet(null, failure);
                byte[] error = "{}".getBytes(StandardCharsets.UTF_8);
                try { exchange.sendResponseHeaders(500, error.length); exchange.getResponseBody().write(error); } catch (IOException ignored) { }
            } finally { exchange.close(); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/ocr";
        try {
            cn.bidcheck.ocr.StructuredOcrClient first = client(url, "first-cache");
            try {
                Thread caller = Thread.currentThread();
                String identity = FileParser.POLICY_VERSION + "|" + first.identity();
                cn.bidcheck.document.ExtractionPlan plan = ExtractionPlan.full(); String planHash = plan.sourceBoundHash(sourceHash);
                cn.bidcheck.service.OcrUnitJournal journal = new OcrUnitJournal(store, new TransactionTemplate(manager), () -> assertSame(caller, Thread.currentThread()),
                        project, firstDocument, 1, sourceHash, planHash, identity, Store.id());
                cn.bidcheck.document.OcrUnitObserver observer = new OcrUnitObserver() {
                    public Optional<Result> restore(String type, int position, int imageIndex, String imageHash) throws IOException {
                        assertSame(caller, Thread.currentThread()); return journal.restore(type, position, imageIndex, imageHash);
                    }
                    public void onState(Event event) throws IOException {
                        assertSame(caller, Thread.currentThread()); journal.onState(event);
                        if (event.status().equals("SUCCEEDED")) durableSeven.countDown();
                    }
                    public void onBatch(OcrBatch.Event event) throws IOException { assertSame(caller, Thread.currentThread()); journal.onBatch(event); }
                };
                cn.bidcheck.document.ExtractionResult result = new FileParser(first, 100, 2).parsePlanned(source, "eight.docx", null, plan, observer);
                if (serverFailure.get() != null) throw new AssertionError("Local transport assertion failed", serverFailure.get());
                assertEquals(cn.bidcheck.compat.Java8Api.listOf(8, 1), sizes); assertEquals(2, calls.get()); assertEquals(true, result.report().get("ocrCoverageComplete"));
                assertEquals(8, result.document().pages().size());
                for (int i = 0; i < 8; i++) {
                    cn.bidcheck.domain.Domain.Page page = result.document().pages().get(i); assertEquals(i + 1, page.number()); assertEquals("BLOCK", page.locationType());
                    assertTrue(page.text().contains("BLOCK:" + (i + 1) + ":1"));
                }
                java.util.List<java.util.Map<java.lang.String, java.lang.Object>> states = store.maps("ocr_unit_status", project, firstDocument); assertEquals(8, states.size());
                assertTrue(states.stream().allMatch(row -> "SUCCEEDED".equals(row.get("status"))));
                assertTrue(states.stream().allMatch(row -> ((String) row.get("imageHash")).matches("[a-f0-9]{64}")));
                assertTrue(states.stream().noneMatch(row -> row.containsKey("text") || row.containsKey("result")));
                java.util.List<java.util.Map<java.lang.String, java.lang.Object>> batches = store.maps("ocr_batch_status", project, firstDocument); assertEquals(2, batches.size());
                assertTrue(batches.stream().anyMatch(row -> ((List<?>) row.get("inputIds")).size() == 8));
                assertTrue(batches.stream().anyMatch(row -> ((List<?>) row.get("inputIds")).equals(cn.bidcheck.compat.Java8Api.listOf("BLOCK:8:1"))));
                assertTrue(batches.stream().allMatch(row -> sourceHash.equals(row.get("sourceHash")) && firstDocument.equals(row.get("documentId"))));

                // A different empty adapter cache proves that reuse comes from the version-bound durable journal.
                cn.bidcheck.ocr.StructuredOcrClient next = client(url, "second-empty-cache");
                try {
                    assertEquals(first.identity(), next.identity());
                    cn.bidcheck.service.OcrUnitJournal restored = new OcrUnitJournal(store, new TransactionTemplate(manager), () -> assertSame(caller, Thread.currentThread()),
                            project, secondDocument, 2, sourceHash, planHash, identity, Store.id(), firstDocument);
                    cn.bidcheck.document.ExtractionResult resumed = new FileParser(next, 2, 2).parsePlanned(source, "eight.docx", null, plan, restored);
                    assertEquals(2, calls.get(), "All eight prior successes must restore before spending the two-new-image budget");
                    assertEquals(true, resumed.report().get("ocrCoverageComplete"));
                    assertEquals(8, ((Map<?, ?>) resumed.report().get("ocrPipeline")).get("restoredImages"));
                    java.util.Map<java.lang.String, java.lang.Object> progress = store.getMap("ocr_progress", "ocr-progress:" + secondDocument);
                    assertEquals(8, ((Number) progress.get("cachedUnitCount")).intValue());
                    assertEquals(8, store.maps("ocr_unit_result", project, secondDocument).size());
                } finally { next.close(); }
            } finally { first.close(); }
        } finally { durableSeven.countDown(); server.stop(0); threads.shutdownNow(); }
    }

    private StructuredOcrClient client(String url, String cache) throws Exception {
        // Reuse the existing package-private loopback-only test seam; production URL validation stays unchanged.
        java.lang.reflect.Constructor<cn.bidcheck.ocr.DashScopeOcrClient> constructor = DashScopeOcrClient.class.getDeclaredConstructor(boolean.class, String.class, String.class, String.class,
                Path.class, Duration.class, boolean.class, int.class, OcrRequestSettings.class, OcrBatchPolicy.class);
        constructor.setAccessible(true);
        cn.bidcheck.ocr.DashScopeOcrClient transcription = constructor.newInstance(true, url, "local-test-secret", "qwen-vl-ocr-2025-11-20", temporary.resolve(cache),
                Duration.ofSeconds(15), true, 2, new OcrRequestSettings(16, 3, 1, 2, 0, 8, 100, 100), OcrBatchPolicy.defaults());
        return new StructuredOcrClient(transcription, OcrClient.disabled(), new PixelGridClient(false, "", temporary.resolve(cache).toString(), 1, 1));
    }
    private Path fixture() throws Exception {
        Path source = temporary.resolve("eight.docx");
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document = new XWPFDocument()) {
            for (int index = 1; index <= 8; index++) {
                java.awt.image.BufferedImage image = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB); java.awt.Graphics2D graphics = image.createGraphics();
                graphics.setColor(new java.awt.Color(index * 0x030303)); graphics.fillRect(0, 0, 80, 80); graphics.dispose();
                byte[] bytes; try (java.io.ByteArrayOutputStream output = new ByteArrayOutputStream()) { ImageIO.write(image, "png", output); bytes = output.toByteArray(); } image.flush();
                org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph = document.createParagraph(); paragraph.createRun().setText("普通扫描正文");
                paragraph.createRun().addPicture(new ByteArrayInputStream(bytes), XWPFDocument.PICTURE_TYPE_PNG, "scan.png", 800000, 800000);
            }
            try (java.io.OutputStream output = Files.newOutputStream(source)) { document.write(output); }
        }
        return source;
    }
}
