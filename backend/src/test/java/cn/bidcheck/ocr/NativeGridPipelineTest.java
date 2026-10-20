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
import java.util.HexFormat;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in process integration: measured local pixels plus synthetic native OCR; no cloud client. */
class NativeGridPipelineTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private Path python;
    private byte[] image;
    private OcrResult nativeResult;
    private final AtomicInteger baseCalls = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        String configured = System.getProperty("bidcheck.test.python", "");
        assumeTrue(!configured.isBlank(), "Set bidcheck.test.python to opt into local worker integration.");
        python = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(python), "Configured test Python must exist.");
        image = gridImage();
        var frame = new OcrLayout.Frame("native-page", 500, 320, hash(image), "PDF_PAGE", 1, null, null, null);
        List<OcrLayout.TextBox> boxes = List.of(
            box("native-name-header", "姓名", 50, 65, 40),
            box("native-month-header", "月份", 280, 65, 40),
            box("native-name", "合成甲", 50, 190, 60),
            box("native-month", "2025-01", 280, 190, 85));
        String text = "姓名 月份\n合成甲 2025-01";
        var layout = new OcrLayout(OcrLayout.VERSION, "COMPLETE", OcrLayout.COORDINATES,
            List.of(frame), boxes, List.of(), boxes.stream().map(OcrLayout.TextBox::id).toList(), List.of()).validated(text);
        nativeResult = new OcrResult(text, "synthetic-native", "mock-advanced", false, layout);
    }

    @AfterEach void clearInterrupt() { Thread.interrupted(); }

    @Test void actualImageWorkerProducesServerOwnedCellsHeadersAndStableReferences() throws Exception {
        Path worker = realWorker();
        try (var grids = client(worker, 20)) {
            var pipeline = new StructuredOcrClient(base(), fixed(nativeResult), grids);
            var first = pipeline.recognize(image, "image/png");
            assertEquals("COMPLETE", first.layout().status(), first.layout().warnings().toString());
            assertBoundTable(first);
            assertEquals(0, baseCalls.get());
            var second = pipeline.recognize(image, "image/png");
            assertEquals(first.layout().tables(), second.layout().tables(), "Cached grids must preserve server IDs and references.");
            assertEquals(first.text(), second.text());
        }
    }

    @Test void advancedFailureFallsBackToBaseWithoutLeakingProviderDetails() throws Exception {
        Path worker = fakeWorker("raise AssertionError('worker must not start')\n");
        try (var grids = client(worker, 2)) {
            var pipeline = new StructuredOcrClient(base(), ocr(() -> { throw new IOException("secret raw provider details"); }), grids);
            var result = pipeline.recognize(image, "image/png");
            assertEquals("普通转录保留", result.text());
            assertEquals(1, baseCalls.get());
            assertEquals("UNAVAILABLE", result.layout().status());
            assertTrue(result.layout().warnings().get(0).contains("[LAYOUT_NATIVE_OCR_INVALID_RESPONSE]"));
            assertFalse(result.layout().warnings().toString().contains("secret"));
        }
    }

    @Test void alreadyInterruptedCallDoesNotStartOcrOrFallBack() throws Exception {
        try (var grids = client(fakeWorker("raise AssertionError('must not start')\n"), 2)) {
            var pipeline = new StructuredOcrClient(base(), ocr(() -> { fail("No OCR after interruption."); return null; }), grids);
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> pipeline.recognize(image, "image/png"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, baseCalls.get());
        }
    }

    @Test void advancedInterruptionIsNotConvertedToSuccessfulFallback() throws Exception {
        try (var grids = client(fakeWorker("raise AssertionError('must not start')\n"), 2)) {
            var pipeline = new StructuredOcrClient(base(), ocr(() -> {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("cancelled");
            }), grids);
            assertThrows(InterruptedIOException.class, () -> pipeline.recognize(image, "image/png"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, baseCalls.get());
        }
    }

    @Test void runningWorkerCancellationEscapesPipelineAndReleasesCapacity() throws Exception {
        Path worker = fakeWorker("from pathlib import Path\nimport time\nPath(__file__).with_suffix('.started').write_text('started')\ntime.sleep(30)\n");
        Path started = worker.resolveSibling("worker.started");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        try (var grids = client(worker, 10)) {
            var pipeline = new StructuredOcrClient(base(), fixed(nativeResult), grids);
            Thread caller = new Thread(() -> {
                try { pipeline.recognize(image, "image/png"); }
                catch (Throwable e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
            }, "native-grid-cancel-test");
            caller.start();
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (!Files.exists(started) && caller.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
                assertTrue(Files.exists(started), "Wait for the real child process before cancelling.");
                caller.interrupt();
                caller.join(3000);
                assertFalse(caller.isAlive(), "Cancellation must stop waiting on the worker.");
                assertInstanceOf(InterruptedIOException.class, failure.get());
                assertTrue(interrupted.get());
                assertEquals(0, baseCalls.get());
                // The same slot remains usable after cancellation, rather than CAPACITY_TIMEOUT.
                Files.writeString(worker, validResponseScript("[]"), StandardCharsets.UTF_8);
                assertBoundTable(pipeline.recognize(image, "image/png"));
            } finally { caller.interrupt(); caller.join(3000); }
        }
    }

    @Test void workerTimeoutPreservesNativeTextAndBoxesWithControlledDiagnostic() throws Exception {
        try (var grids = client(fakeWorker("import time\ntime.sleep(10)\n"), 1)) {
            var pipeline = new StructuredOcrClient(base(), fixed(nativeResult), grids);
            var result = assertTimeout(Duration.ofSeconds(5), () -> pipeline.recognize(image, "image/png"));
            assertEquals("PARTIAL", result.layout().status());
            assertEquals(nativeResult.text(), result.text());
            assertEquals(nativeResult.layout().textBoxes(), result.layout().textBoxes());
            assertTrue(result.layout().tables().isEmpty());
            assertTrue(result.layout().warnings().get(0).contains("[LAYOUT_GRID_TIMEOUT]"));
            assertEquals(0, baseCalls.get());
        }
    }

    @Test void oversizedWorkerOutputIsBoundedAndNeverReturnedAsOcrText() throws Exception {
        try (var grids = client(fakeWorker("import sys\nsys.stdout.write('X' * (1024 * 1024 + 1))\nsys.stdout.flush()\n"), 3)) {
            var result = new StructuredOcrClient(base(), fixed(nativeResult), grids).recognize(image, "image/png");
            assertEquals("PARTIAL", result.layout().status());
            assertEquals(nativeResult.text(), result.text());
            assertEquals(nativeResult.layout().textBoxes(), result.layout().textBoxes());
            assertTrue(result.layout().warnings().get(0).startsWith("[LAYOUT_GRID_"));
            assertTrue(result.layout().warnings().get(0).length() < 200);
        }
    }

    @Test void corruptedCachedImageHashIsRejectedAndWorkerRunsAgain() throws Exception {
        Path worker = fakeWorker("from pathlib import Path\np = Path(__file__).with_suffix('.count')\np.write_text(str(int(p.read_text()) + 1) if p.exists() else '1')\n" + validResponseScript("[]"));
        try (var grids = client(worker, 3)) {
            var pipeline = new StructuredOcrClient(base(), fixed(nativeResult), grids);
            var first = pipeline.recognize(image, "image/png");
            assertBoundTable(first);
            assertEquals("1", Files.readString(worker.resolveSibling("worker.count")));
            Path cached;
            try (var files = Files.list(temp.resolve("data/pixel-grid-cache"))) {
                var entries = files.filter(p -> p.toString().endsWith(".json")).toList();
                assertEquals(1, entries.size()); cached = entries.get(0);
            }
            var bad = (ObjectNode) json.readTree(Files.readAllBytes(cached));
            bad.put("imageHash", "0".repeat(64));
            ((ObjectNode) bad.at("/gridCandidates/0/cells/0")).putArray("box").add(0).add(0).add(499).add(319);
            Files.write(cached, json.writeValueAsBytes(bad));
            var second = pipeline.recognize(image, "image/png");
            assertEquals("2", Files.readString(worker.resolveSibling("worker.count")), "Corrupt cache cannot bypass source validation.");
            assertBoundTable(second);
            assertEquals(first.layout().tables(), second.layout().tables());
        }
    }

    @Test void workerGeometryWarningIsPartialButKeepsValidatedBoxesAndTable() throws Exception {
        try (var grids = client(fakeWorker(validResponseScript("['GRID_GEOMETRY_AMBIGUOUS']")), 3)) {
            var result = new StructuredOcrClient(base(), fixed(nativeResult), grids).recognize(image, "image/png");
            assertEquals("PARTIAL", result.layout().status());
            assertBoundTable(result);
            assertEquals(nativeResult.layout().textBoxes(), result.layout().textBoxes());
            assertTrue(result.layout().warnings().stream().anyMatch(w -> w.contains("[GRID_GEOMETRY_AMBIGUOUS]")));
        }
    }

    private void assertBoundTable(OcrResult result) {
        assertEquals(1, result.layout().tables().size(), result.layout().warnings().toString());
        var table = result.layout().tables().get(0);
        assertEquals(2, table.rows()); assertEquals(2, table.columns()); assertEquals(4, table.cells().size());
        var nameHeader = cell(table, 0, 0); var monthHeader = cell(table, 0, 1);
        var name = cell(table, 1, 0); var month = cell(table, 1, 1);
        assertTrue(nameHeader.header()); assertTrue(monthHeader.header());
        assertFalse(name.header()); assertFalse(month.header());
        assertEquals("合成甲", name.text()); assertEquals("2025-01", month.text());
        assertEquals(List.of("native-name"), name.blockIds()); assertEquals(List.of("native-month"), month.blockIds());
        assertEquals(List.of(nameHeader.id()), name.headerCellIds()); assertEquals(List.of(monthHeader.id()), month.headerCellIds());
        assertEquals("native-page", table.frameId());
        assertFalse(table.id().isBlank()); assertEquals(4, table.cells().stream().map(OcrLayout.Cell::id).distinct().count());
        assertEquals(result.layout(), result.layout().validated(result.text()));
    }

    private static OcrLayout.Cell cell(OcrLayout.Table table, int row, int column) {
        return table.cells().stream().filter(c -> c.row() == row && c.column() == column).findFirst().orElseThrow();
    }
    private static OcrLayout.TextBox box(String id, String text, double x, double y, double width) {
        return new OcrLayout.TextBox(id, "native-page", text, new OcrLayout.Box(x, y, width, 22), .99, "NATIVE_OCR");
    }
    private PixelGridClient client(Path worker, int timeout) {
        return new PixelGridClient(true, python.toString(), worker.toString(), temp.resolve("data").toString(), 1, timeout);
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
    private Path fakeWorker(String script) throws IOException {
        Path worker = temp.resolve("worker.py"); Files.writeString(worker, script, StandardCharsets.UTF_8); return worker;
    }
    private static Path realWorker() {
        for (Path path : List.of(Path.of("../scripts/structured_ocr/worker.py"), Path.of("scripts/structured_ocr/worker.py")))
            if (Files.isRegularFile(path)) return path.toAbsolutePath().normalize();
        fail("Cannot locate repository local OCR worker from test working directory."); return null;
    }
    private static String validResponseScript(String warnings) {
        return """
            import base64, hashlib, json, struct, sys
            request = json.loads(sys.stdin.readline())
            image = base64.b64decode(request['imageBase64'])
            width, height = struct.unpack('>II', image[16:24])
            xs, ys = [20, 250, 480], [20, 140, 300]
            cells = [{'row': r, 'column': c, 'rowSpan': 1, 'columnSpan': 1,
                      'box': [xs[c], ys[r], xs[c+1], ys[r+1]], 'header': False}
                     for r in range(2) for c in range(2)]
            result = {'id': request['id'], 'status': 'OK', 'schemaVersion': 'local-ocr-grid-1',
                      'coordinateSystem': 'PIXEL_TOP_LEFT', 'width': width, 'height': height,
                      'imageHash': hashlib.sha256(image).hexdigest(), 'warnings': WARNINGS,
                      'gridCandidates': [{'frameId': 'f1', 'coordinateSpace': 'PIXEL_XYXY',
                                          'rows': 2, 'columns': 2, 'cells': cells}]}
            print(json.dumps(result), flush=True)
            """.replace("WARNINGS", warnings);
    }
    private static byte[] gridImage() throws IOException {
        BufferedImage image = new BufferedImage(500, 320, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 500, 320);
            graphics.setColor(Color.BLACK); graphics.setStroke(new BasicStroke(3));
            for (int x : new int[]{20, 250, 480}) graphics.drawLine(x, 20, x, 300);
            for (int y : new int[]{20, 140, 300}) graphics.drawLine(20, y, 480, y);
            graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, 16));
            graphics.drawString("姓名", 50, 83); graphics.drawString("月份", 280, 83);
            graphics.drawString("合成甲", 50, 208); graphics.drawString("2025-01", 280, 208);
        } finally { graphics.dispose(); }
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
