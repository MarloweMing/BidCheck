package cn.bidcheck.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import cn.bidcheck.compat.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Measured JVM pixels plus synthetic native OCR; no cloud or secondary runtime. */
class NativeGridPipelineTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private byte[] image;
    private OcrResult nativeResult;
    private final AtomicInteger baseCalls = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        image = gridImage();
        cn.bidcheck.ocr.OcrLayout.Frame frame = new OcrLayout.Frame("native-page", 500, 320, hash(image), "PDF_PAGE", 1, null, null, null);
        List<OcrLayout.TextBox> boxes = cn.bidcheck.compat.Java8Api.listOf(box("native-name-header", "姓名", 50, 65, 40), box("native-month-header", "月份", 280, 65, 40), box("native-name", "合成甲", 50, 190, 60), box("native-month", "2025-01", 280, 190, 85));
        String text = "姓名 月份\n合成甲 2025-01";
        cn.bidcheck.ocr.OcrLayout layout = new OcrLayout(OcrLayout.VERSION, "COMPLETE", OcrLayout.COORDINATES,
            cn.bidcheck.compat.Java8Api.listOf(frame), boxes, cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.toList(boxes.stream().map(OcrLayout.TextBox::id)), cn.bidcheck.compat.Java8Api.listOf()).validated(text);
        nativeResult = new OcrResult(text, "synthetic-native", "mock-advanced", false, layout);
    }

    @AfterEach void clearInterrupt() { Thread.interrupted(); }

    @Test void actualImageWorkerProducesServerOwnedCellsHeadersAndStableReferences() throws Exception {
        PixelGridClient.Engine worker = new JavaLocalOcrEngine("");
        try (cn.bidcheck.ocr.PixelGridClient grids = client(worker, 20)) {
            cn.bidcheck.ocr.StructuredOcrClient pipeline = new StructuredOcrClient(base(), fixed(nativeResult), grids);
            cn.bidcheck.ocr.OcrResult first = pipeline.recognize(image, "image/png");
            assertEquals("COMPLETE", first.layout().status(), first.layout().warnings().toString());
            assertBoundTable(first);
            assertEquals(0, baseCalls.get());
            cn.bidcheck.ocr.OcrResult second = pipeline.recognize(image, "image/png");
            assertEquals(first.layout().tables(), second.layout().tables(), "Cached grids must preserve server IDs and references.");
            assertEquals(first.text(), second.text());
        }
    }

    @Test void advancedFailureFallsBackToBaseWithoutLeakingProviderDetails() throws Exception {
        PixelGridClient.Engine worker = forbiddenEngine();
        try (cn.bidcheck.ocr.PixelGridClient grids = client(worker, 2)) {
            cn.bidcheck.ocr.StructuredOcrClient pipeline = new StructuredOcrClient(base(), ocr(() -> { throw new IOException("secret raw provider details"); }), grids);
            cn.bidcheck.ocr.OcrResult result = pipeline.recognize(image, "image/png");
            assertEquals("普通转录保留", result.text());
            assertEquals(1, baseCalls.get());
            assertEquals("UNAVAILABLE", result.layout().status());
            assertTrue(result.layout().warnings().get(0).contains("[LAYOUT_NATIVE_OCR_INVALID_RESPONSE]"));
            assertFalse(result.layout().warnings().toString().contains("secret"));
        }
    }

    @Test void alreadyInterruptedCallDoesNotStartOcrOrFallBack() throws Exception {
        try (cn.bidcheck.ocr.PixelGridClient grids = client(forbiddenEngine(), 2)) {
            cn.bidcheck.ocr.StructuredOcrClient pipeline = new StructuredOcrClient(base(), ocr(() -> { fail("No OCR after interruption."); return null; }), grids);
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> pipeline.recognize(image, "image/png"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, baseCalls.get());
        }
    }

    @Test void advancedInterruptionIsNotConvertedToSuccessfulFallback() throws Exception {
        try (cn.bidcheck.ocr.PixelGridClient grids = client(forbiddenEngine(), 2)) {
            cn.bidcheck.ocr.StructuredOcrClient pipeline = new StructuredOcrClient(base(), ocr(() -> {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("cancelled");
            }), grids);
            assertThrows(InterruptedIOException.class, () -> pipeline.recognize(image, "image/png"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, baseCalls.get());
        }
    }

    @Test void runningWorkerCancellationEscapesPipelineAndReleasesCapacity() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        PixelGridClient.Engine actual = new JavaLocalOcrEngine("");
        PixelGridClient.Engine worker = (bytes, frame, withText, regions, deadline) -> {
            if (firstCall.getAndSet(false)) { started.countDown(); pause(30_000); }
            return actual.process(bytes, frame, withText, regions, deadline);
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        try (cn.bidcheck.ocr.PixelGridClient grids = client(worker, 10)) {
            cn.bidcheck.ocr.StructuredOcrClient pipeline = new StructuredOcrClient(base(), fixed(nativeResult), grids);
            Thread caller = new Thread(() -> {
                try { pipeline.recognize(image, "image/png"); }
                catch (Throwable e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
            }, "native-grid-cancel-test");
            caller.start();
            try {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "Wait for the engine before cancelling.");
                caller.interrupt();
                caller.join(3000);
                assertFalse(caller.isAlive(), "Cancellation must stop waiting on the worker.");
                assertInstanceOf(InterruptedIOException.class, failure.get());
                assertTrue(interrupted.get());
                assertEquals(0, baseCalls.get());
                // The same slot remains usable after cancellation, rather than CAPACITY_TIMEOUT.
                assertBoundTable(pipeline.recognize(image, "image/png"));
            } finally { caller.interrupt(); caller.join(3000); }
        }
    }

    @Test void workerTimeoutPreservesNativeTextAndBoxesWithControlledDiagnostic() throws Exception {
        try (cn.bidcheck.ocr.PixelGridClient grids = client((bytes, frame, withText, regions, deadline) -> { pause(10_000); return null; }, 1)) {
            cn.bidcheck.ocr.StructuredOcrClient pipeline = new StructuredOcrClient(base(), fixed(nativeResult), grids);
            cn.bidcheck.ocr.OcrResult result = assertTimeout(Duration.ofSeconds(5), () -> pipeline.recognize(image, "image/png"));
            assertEquals("PARTIAL", result.layout().status());
            assertEquals(nativeResult.text(), result.text());
            assertEquals(nativeResult.layout().textBoxes(), result.layout().textBoxes());
            assertTrue(result.layout().tables().isEmpty());
            assertTrue(result.layout().warnings().get(0).contains("[LAYOUT_GRID_TIMEOUT]"));
            assertEquals(0, baseCalls.get());
        }
    }

    @Test void oversizedWorkerOutputIsBoundedAndNeverReturnedAsOcrText() throws Exception {
        try (cn.bidcheck.ocr.PixelGridClient grids = client((bytes, frame, withText, regions, deadline) -> json.createObjectNode().put("oversized", cn.bidcheck.compat.Java8Api.repeat("X", 1024*1024+1)), 3)) {
            cn.bidcheck.ocr.OcrResult result = new StructuredOcrClient(base(), fixed(nativeResult), grids).recognize(image, "image/png");
            assertEquals("PARTIAL", result.layout().status());
            assertEquals(nativeResult.text(), result.text());
            assertEquals(nativeResult.layout().textBoxes(), result.layout().textBoxes());
            assertTrue(result.layout().warnings().get(0).startsWith("[LAYOUT_GRID_"));
            assertTrue(result.layout().warnings().get(0).length() < 200);
        }
    }

    @Test void corruptedCachedImageHashIsRejectedAndWorkerRunsAgain() throws Exception {
        AtomicInteger engineCalls = new AtomicInteger();
        PixelGridClient.Engine actual = new JavaLocalOcrEngine("");
        PixelGridClient.Engine worker = (bytes, frame, withText, regions, deadline) -> { engineCalls.incrementAndGet(); return actual.process(bytes, frame, withText, regions, deadline); };
        try (cn.bidcheck.ocr.PixelGridClient grids = client(worker, 3)) {
            cn.bidcheck.ocr.StructuredOcrClient pipeline = new StructuredOcrClient(base(), fixed(nativeResult), grids);
            cn.bidcheck.ocr.OcrResult first = pipeline.recognize(image, "image/png");
            assertBoundTable(first);
            assertEquals(1, engineCalls.get());
            Path cached;
            try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(temp.resolve("data/pixel-grid-cache"))) {
                java.util.List<java.nio.file.Path> entries = cn.bidcheck.compat.Java8Api.toList(files.filter(p -> p.toString().endsWith(".json")));
                assertEquals(1, entries.size()); cached = entries.get(0);
            }
            com.fasterxml.jackson.databind.node.ObjectNode bad = (ObjectNode) json.readTree(Files.readAllBytes(cached));
            bad.put("imageHash", cn.bidcheck.compat.Java8Api.repeat("0", 64));
            ((ObjectNode) bad.at("/gridCandidates/0/cells/0")).putArray("box").add(0).add(0).add(499).add(319);
            Files.write(cached, json.writeValueAsBytes(bad));
            cn.bidcheck.ocr.OcrResult second = pipeline.recognize(image, "image/png");
            assertEquals(2, engineCalls.get(), "Corrupt cache cannot bypass source validation.");
            assertBoundTable(second);
            assertEquals(first.layout().tables(), second.layout().tables());
        }
    }

    @Test void workerGeometryWarningIsPartialButKeepsValidatedBoxesAndTable() throws Exception {
        try (cn.bidcheck.ocr.PixelGridClient grids = client((bytes, frame, withText, regions, deadline) -> { ObjectNode response = (ObjectNode)new JavaLocalOcrEngine("").process(bytes, frame, withText, regions, deadline); response.putArray("warnings").add("GRID_GEOMETRY_AMBIGUOUS"); return response; }, 3)) {
            cn.bidcheck.ocr.OcrResult result = new StructuredOcrClient(base(), fixed(nativeResult), grids).recognize(image, "image/png");
            assertEquals("PARTIAL", result.layout().status());
            assertBoundTable(result);
            assertEquals(nativeResult.layout().textBoxes(), result.layout().textBoxes());
            assertTrue(result.layout().warnings().stream().anyMatch(w -> w.contains("[GRID_GEOMETRY_AMBIGUOUS]")));
        }
    }

    private void assertBoundTable(OcrResult result) {
        assertEquals(1, result.layout().tables().size(), result.layout().warnings().toString());
        cn.bidcheck.ocr.OcrLayout.Table table = result.layout().tables().get(0);
        assertEquals(2, table.rows()); assertEquals(2, table.columns()); assertEquals(4, table.cells().size());
        cn.bidcheck.ocr.OcrLayout.Cell nameHeader = cell(table, 0, 0); cn.bidcheck.ocr.OcrLayout.Cell monthHeader = cell(table, 0, 1);
        cn.bidcheck.ocr.OcrLayout.Cell name = cell(table, 1, 0); cn.bidcheck.ocr.OcrLayout.Cell month = cell(table, 1, 1);
        assertTrue(nameHeader.header()); assertTrue(monthHeader.header());
        assertFalse(name.header()); assertFalse(month.header());
        assertEquals("合成甲", name.text()); assertEquals("2025-01", month.text());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("native-name"), name.blockIds()); assertEquals(cn.bidcheck.compat.Java8Api.listOf("native-month"), month.blockIds());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(nameHeader.id()), name.headerCellIds()); assertEquals(cn.bidcheck.compat.Java8Api.listOf(monthHeader.id()), month.headerCellIds());
        assertEquals("native-page", table.frameId());
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(table.id())); assertEquals(4, table.cells().stream().map(OcrLayout.Cell::id).distinct().count());
        assertEquals(result.layout(), result.layout().validated(result.text()));
    }

    private static OcrLayout.Cell cell(OcrLayout.Table table, int row, int column) {
        return table.cells().stream().filter(c -> c.row() == row && c.column() == column).findFirst().orElseThrow(java.util.NoSuchElementException::new);
    }
    private static OcrLayout.TextBox box(String id, String text, double x, double y, double width) {
        return new OcrLayout.TextBox(id, "native-page", text, new OcrLayout.Box(x, y, width, 22), .99, "NATIVE_OCR");
    }
    private PixelGridClient client(PixelGridClient.Engine worker, int timeout) {
        return new PixelGridClient(true, worker, temp.resolve("data").toString(), 1, timeout);
    }
    private OcrClient base() { return ocr(() -> { baseCalls.incrementAndGet(); return new OcrResult("普通转录保留", "mock-base", "base", false); }); }
    private static OcrClient fixed(OcrResult value) { return ocr(() -> value); }
    @FunctionalInterface interface Response { OcrResult get() throws IOException; }
    private static OcrClient ocr(Response response) {
        return new OcrClient() {
            public boolean enabled() { return true; }
            public String modelName() { return "synthetic"; }
            public String identity() { return "synthetic-no-network"; }
            public OcrResult recognize(byte[] bytes, String mime) throws IOException { return response.get(); }
        };
    }
    private static PixelGridClient.Engine forbiddenEngine() { return (bytes, frame, withText, regions, deadline) -> { throw new AssertionError("Engine must not run"); }; }
    private static void pause(long millis) throws InterruptedIOException { try { Thread.sleep(millis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new InterruptedIOException("cancelled"); } }
    private static byte[] gridImage() throws IOException {
        BufferedImage image = new BufferedImage(500, 320, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 500, 320);
            graphics.setColor(Color.BLACK); graphics.setStroke(new BasicStroke(3));
            for (int x : new int[]{20, 250, 480}) graphics.drawLine(x, 20, x, 300);
            for (int y : new int[]{20, 140, 300}) graphics.drawLine(20, y, 480, y);
            graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, 16));
            graphics.drawString("姓名", 50, 83); graphics.drawString("月份", 280, 83);
            graphics.drawString("合成甲", 50, 208); graphics.drawString("2025-01", 280, 208);
        } finally { graphics.dispose(); }
        java.io.ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
