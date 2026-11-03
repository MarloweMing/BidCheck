package cn.bidcheck.document;
import cn.bidcheck.compat.HexFormat;

import cn.bidcheck.ocr.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileParserPipelineTest {
    @TempDir Path temporary;
    private static final int FIRST = 0xaa1100, SECOND = 0x00aa11, THIRD = 0x1100aa;

    @Test void wordRunsAcrossBlocksReclaimsCompletionsAndKeepsAllSourceLocations() throws Exception {
        Path file = word("parallel.docx", new int[][]{{FIRST}, {SECOND, THIRD}, {SECOND}});
        java.util.concurrent.CountDownLatch thirdStarted = new CountDownLatch(1);
        cn.bidcheck.document.FileParserPipelineTest.Client client = new Client((bytes, color) -> {
            if (color == THIRD) thirdStarted.countDown();
            if (color == FIRST) await(thirdStarted, "The third image must start while the first image is still running");
        });
        cn.bidcheck.document.FileParserPipelineTest.Journal observer = new Journal();
        Thread caller = Thread.currentThread();
        cn.bidcheck.document.ExtractionResult result = new FileParser(client, 10, 2).parsePlanned(file, "parallel.docx",
                (percent, stage) -> assertSame(caller, Thread.currentThread()), ExtractionPlan.full(), observer);
        assertEquals(3, client.calls.get());
        assertEquals(2, client.maximum.get());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.toList(result.document().pages().stream().map(p -> p.number())));
        java.util.List<cn.bidcheck.domain.Domain.Page> pages = result.document().pages();
        assertTrue(pages.stream().allMatch(p -> p.locationType().equals("BLOCK")));
        assertTrue(pages.get(0).text().contains(Integer.toHexString(FIRST)));
        assertTrue(pages.get(1).text().indexOf(Integer.toHexString(SECOND)) < pages.get(1).text().indexOf(Integer.toHexString(THIRD)));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2), cn.bidcheck.compat.Java8Api.toList(pages.get(1).layout().frames().stream().map(OcrLayout.Frame::sourceIndex)));
        assertTrue(pages.get(1).layout().frames().stream().allMatch(f -> f.sourceKind().equals("WORD_EMBEDDED")));
        assertEquals(2, observer.successes().get(0).position(), "Durable completion must not wait for source block 1");
        assertTrue(observer.successes().stream().anyMatch(e -> e.position() == 3 && e.cached()));
        assertTrue(observer.events.stream().allMatch(e -> e.locationType().equals("BLOCK")));
    }

    @Test void pdfSlowFirstPageDoesNotLeaveFreedWorkerIdleAndCompletionPersistsBeforeAssembly() throws Exception {
        Path file = pdf("parallel.pdf", FIRST, SECOND, THIRD);
        java.util.concurrent.CountDownLatch thirdStarted = new CountDownLatch(1);
        cn.bidcheck.document.FileParserPipelineTest.Client client = new Client((bytes, color) -> {
            if (color == THIRD) thirdStarted.countDown();
            if (color == FIRST) await(thirdStarted, "PDF third page must fill the free slot before page 1 finishes");
        });
        cn.bidcheck.document.FileParserPipelineTest.Journal observer = new Journal();
        cn.bidcheck.document.ExtractionResult result = new FileParser(client, 10, 2).parsePlanned(file, "parallel.pdf", null, ExtractionPlan.full(), observer);
        assertEquals(3, client.calls.get());
        assertEquals(2, observer.successes().get(0).position());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.toList(result.document().pages().stream().map(p -> p.number())));
        for (int index = 0; index < 3; index++) {
            cn.bidcheck.ocr.OcrLayout.Frame frame = result.document().pages().get(index).layout().frames().get(0);
            assertEquals("PDF_RENDER", frame.sourceKind());
            assertEquals(index + 1, frame.sourceIndex());
        }
    }

    @Test void continuationRestoresTwoSuccessesBeforeSpendingBudgetOnTheNextTwo() throws Exception {
        Path file = word("continue.docx", new int[][]{{FIRST}, {SECOND}, {THIRD}, {0xabcdef}});
        cn.bidcheck.document.FileParserPipelineTest.Client client = new Client((bytes, color) -> { });
        cn.bidcheck.document.FileParserPipelineTest.Journal journal = new Journal();
        cn.bidcheck.document.FileParser parser = new FileParser(client, 2, 2);
        cn.bidcheck.document.ExtractionResult first = parser.parsePlanned(file, "continue.docx", null, ExtractionPlan.full(), journal);
        assertEquals(2, client.calls.get());
        assertEquals(2, first.report().get("deferredImageCount"));
        assertEquals(false, first.report().get("ocrCoverageComplete"));
        assertEquals(4, first.document().pages().size());
        assertTrue(first.document().pages().get(2).warning().contains("[OCR_DEFERRED]"));
        cn.bidcheck.document.ExtractionResult second = parser.parsePlanned(file, "continue.docx", null, ExtractionPlan.full(), journal);
        assertEquals(4, client.calls.get(), "Restored successes do not spend this run's two new-image slots");
        assertEquals(true, second.report().get("ocrCoverageComplete"));
        assertEquals(2, ((Map<?, ?>) second.report().get("ocrPipeline")).get("restoredImages"));
        assertTrue(second.document().pages().stream().allMatch(p -> !cn.bidcheck.compat.Java8Api.isBlank(p.text())));
        parser.parsePlanned(file, "continue.docx", null, ExtractionPlan.full(), journal);
        assertEquals(4, client.calls.get());
    }

    @Test void identicalImagesReuseOneRemoteCallWithoutConsumingTheDistinctImageBudget() throws Exception {
        Path file = word("duplicates.docx", new int[][]{{FIRST, FIRST}, {FIRST}, {SECOND}});
        cn.bidcheck.document.FileParserPipelineTest.Client client = new Client((bytes, color) -> { });
        cn.bidcheck.document.FileParserPipelineTest.Journal journal = new Journal();
        cn.bidcheck.document.ExtractionResult result = new FileParser(client, 2, 2).parsePlanned(file, "duplicates.docx", null, ExtractionPlan.full(), journal);
        assertEquals(2, client.calls.get());
        assertEquals(true, result.report().get("ocrCoverageComplete"));
        assertEquals(4, journal.successes().size());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2), cn.bidcheck.compat.Java8Api.toList(result.document().pages().get(0).layout().frames().stream().map(OcrLayout.Frame::sourceIndex)));
        assertEquals(4L, result.report().get("recognizedImageCount"));
    }

    @Test void observerWriteFailurePropagatesUnchangedAndCancelsOtherWorkers() throws Exception {
        Path file = word("lease.docx", new int[][]{{FIRST}, {SECOND}, {THIRD}});
        java.util.concurrent.CountDownLatch started = new CountDownLatch(1); java.util.concurrent.CountDownLatch cancelled = new CountDownLatch(1);
        cn.bidcheck.document.FileParserPipelineTest.Client client = new Client((bytes, color) -> {
            if (color == FIRST) {
                started.countDown();
                try { new CountDownLatch(1).await(15, TimeUnit.SECONDS); }
                catch (InterruptedException e) { cancelled.countDown(); Thread.currentThread().interrupt(); throw new InterruptedIOException("cancelled"); }
            } else await(started, "Other worker has started");
        });
        java.io.IOException failure = new IOException("journal write rejected");
        cn.bidcheck.document.OcrUnitObserver observer = new OcrUnitObserver() {
            public void onState(Event event) throws IOException { if (event.status().equals("SUCCEEDED")) throw failure; }
        };
        assertSame(failure, assertThrows(IOException.class, () -> new FileParser(client, 10, 2)
                .parsePlanned(file, "lease.docx", null, ExtractionPlan.full(), observer)));
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        assertEquals(2, client.calls.get(), "No third remote request after the durable fence fails");
    }

    @Test void observerRestoreAndRunningFailuresRemainFatalRatherThanBecomingOrdinaryOcrWarnings() throws Exception {
        Path file = word("restore-failure.docx", new int[][]{{FIRST}});
        cn.bidcheck.document.FileParserPipelineTest.Client client = new Client((bytes, color) -> { });
        IOException readFailure = new IOException("journal read failed");
        cn.bidcheck.document.OcrUnitObserver readObserver = new OcrUnitObserver() {
            public Optional<Result> restore(String type, int position, int index, String hash) throws IOException { throw readFailure; }
        };
        assertSame(readFailure, assertThrows(IOException.class, () -> new FileParser(client).parsePlanned(file, "restore-failure.docx", null, ExtractionPlan.full(), readObserver)));
        IllegalStateException lost = new IllegalStateException("lease lost");
        cn.bidcheck.document.OcrUnitObserver writeObserver = new OcrUnitObserver() { public void onState(Event event) { throw lost; } };
        assertSame(lost, assertThrows(IllegalStateException.class, () -> new FileParser(client).parsePlanned(file, "restore-failure.docx", null, ExtractionPlan.full(), writeObserver)));
        assertEquals(0, client.calls.get());
    }

    @Test void malformedPersistedTextOrWrongFrameHashCannotBypassRecognition() throws Exception {
        Path file = word("forged.docx", new int[][]{{FIRST}});
        cn.bidcheck.document.FileParserPipelineTest.Client client = new Client((bytes, color) -> { });
        for (boolean forgedGeometry : cn.bidcheck.compat.Java8Api.listOf(false, true)) {
            cn.bidcheck.document.OcrUnitObserver observer = new OcrUnitObserver() {
                public Optional<Result> restore(String type, int position, int index, String hash) {
                    String text = forgedGeometry ? "被错误绑定的正文" : "\u0000";
                    OcrLayout layout = forgedGeometry ? layout(text, cn.bidcheck.compat.Java8Api.repeat("0", 64), 40, 60) : OcrLayout.unavailable("旧缓存");
                    return Optional.of(new Result(text, "[OCR已识别]", layout));
                }
            };
            cn.bidcheck.document.ExtractionResult result = new FileParser(client).parsePlanned(file, "forged.docx", null, ExtractionPlan.full(), observer);
            assertTrue(result.document().pages().get(0).text().contains(Integer.toHexString(FIRST)));
            assertFalse(result.document().pages().get(0).text().contains("被错误绑定"));
        }
        assertEquals(2, client.calls.get());
    }

    @Test void oneDocumentSharesByteAndPixelBackpressureAcrossWordBlocks() throws Exception {
        Path file = word("memory.docx", new int[][]{{FIRST}, {SECOND}, {THIRD}});
        long maximumBytes = Math.max(image(FIRST).length, Math.max(image(SECOND).length, image(THIRD).length));
        for (boolean constrainPixels : cn.bidcheck.compat.Java8Api.listOf(false, true)) {
            cn.bidcheck.document.FileParserPipelineTest.Client client = new Client((bytes, color) -> {
                try { Thread.sleep(20); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            });
            long bytesBudget = constrainPixels ? 1_000_000 : maximumBytes + 1;
            long pixelsBudget = constrainPixels ? 2_400 : 100_000;
            cn.bidcheck.document.ExtractionResult result = new FileParser(client, 10, 8, bytesBudget, pixelsBudget)
                    .parsePlanned(file, "memory.docx", null, ExtractionPlan.full(), new Journal());
            assertEquals(3, client.calls.get());
            assertEquals(1, client.maximum.get());
            java.util.Map<?, ?> metrics = (Map<?, ?>) result.report().get("ocrPipeline");
            assertTrue(((Long) metrics.get("peakActiveBytes")).longValue() <= bytesBudget);
            assertTrue(((Long) metrics.get("peakActivePixels")).longValue() <= pixelsBudget);
            assertEquals(true, result.report().get("ocrCoverageComplete"));
        }
    }

    @Test void unknownBlankPdfIsClassifiedConservativelyAndStillRecognized() throws Exception {
        Path file = temporary.resolve("unknown.pdf");
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = new PDDocument()) { pdf.addPage(new PDPage(new PDRectangle(100, 100))); pdf.save(file.toFile()); }
        cn.bidcheck.document.FileParserPipelineTest.Client client = new Client((bytes, color) -> { });
        cn.bidcheck.document.ExtractionResult result = new FileParser(client).parsePlanned(file, "unknown.pdf", null, ExtractionPlan.full(), new Journal());
        assertEquals(1, client.calls.get());
        java.util.Map<?, ?> location = (Map<?, ?>) ((List<?>) result.report().get("nativeMap")).get(0);
        assertEquals("UNKNOWN_PAGE", location.get("pageClass"));
        assertEquals(1, result.document().pages().get(0).number());
    }

    private final class Journal implements OcrUnitObserver {
        final Thread caller = Thread.currentThread();
        final List<Event> events = new ArrayList<>();
        final Map<String, Result> stored = new HashMap<>();
        public Optional<Result> restore(String type, int position, int index, String hash) {
            assertSame(caller, Thread.currentThread()); return Optional.ofNullable(stored.get(type + ":" + position + ":" + index + ":" + hash));
        }
        public void onState(Event event) {
            assertSame(caller, Thread.currentThread()); events.add(event);
            if (event.status().equals("SUCCEEDED")) stored.put(event.locationType() + ":" + event.position() + ":" + event.imageIndex() + ":" + event.imageHash(), new Result(event.text(), event.warning(), event.layout()));
        }
        List<Event> successes() { return cn.bidcheck.compat.Java8Api.toList(events.stream().filter(e -> e.status().equals("SUCCEEDED"))); }
    }

    @FunctionalInterface private interface Behavior { void run(byte[] bytes, int color) throws IOException; }
    private static final class Client implements OcrClient {
        final Behavior behavior; final AtomicInteger calls = new AtomicInteger(), active = new AtomicInteger(), maximum = new AtomicInteger();
        Client(Behavior behavior) { this.behavior = behavior; }
        public boolean enabled() { return true; } public String modelName() { return "offline-pipeline-test"; } public String identity() { return "offline-pipeline-1"; }
        public OcrResult recognize(byte[] bytes, String mime) throws IOException {
            calls.incrementAndGet(); maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                java.awt.image.BufferedImage bitmap = ImageIO.read(new ByteArrayInputStream(bytes));
                int color = bitmap.getRGB(1, 1) & 0xffffff, width = bitmap.getWidth(), height = bitmap.getHeight(); bitmap.flush();
                behavior.run(bytes, color);
                String text = "图像原文：" + Integer.toHexString(color);
                return new OcrResult(text, "mock", modelName(), false, layout(text, hash(bytes), width, height));
            } finally { active.decrementAndGet(); }
        }
    }
    private static OcrLayout layout(String text, String hash, int width, int height) {
        return new OcrLayout(OcrLayout.VERSION, "COMPLETE", OcrLayout.COORDINATES,
                cn.bidcheck.compat.Java8Api.listOf(new OcrLayout.Frame("image", width, height, hash, "SUBMITTED_IMAGE", 0, null, null, null)),
                cn.bidcheck.compat.Java8Api.listOf(new OcrLayout.TextBox("b1", "image", text, new OcrLayout.Box(1, 1, 10, 10), null)), cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf("b1"), cn.bidcheck.compat.Java8Api.listOf());
    }
    private static String hash(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IOException(failure); }
    }
    private static void await(CountDownLatch latch, String message) throws IOException {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), message); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
    }
    private Path word(String name, int[][] colors) throws Exception {
        Path file = temporary.resolve(name);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument word = new XWPFDocument()) {
            for (int[] block : colors) {
                org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph = word.createParagraph(); paragraph.createRun().setText("原生内容块");
                for (int color : block) paragraph.createRun().addPicture(new ByteArrayInputStream(image(color)), XWPFDocument.PICTURE_TYPE_PNG, "source.png", 400_000, 600_000);
            }
            try (java.io.OutputStream output = Files.newOutputStream(file)) { word.write(output); }
        }
        return file;
    }
    private Path pdf(String name, int... colors) throws IOException {
        Path file = temporary.resolve(name);
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = new PDDocument()) {
            for (int color : colors) {
                org.apache.pdfbox.pdmodel.PDPage page = new PDPage(new PDRectangle(100, 100)); pdf.addPage(page);
                java.awt.image.BufferedImage bitmap = ImageIO.read(new ByteArrayInputStream(image(color))); org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject picture = LosslessFactory.createFromImage(pdf, bitmap); bitmap.flush();
                try (org.apache.pdfbox.pdmodel.PDPageContentStream stream = new PDPageContentStream(pdf, page)) { stream.drawImage(picture, 0, 0, 100, 100); }
            }
            pdf.save(file.toFile());
        }
        return file;
    }
    private static byte[] image(int color) throws IOException {
        java.awt.image.BufferedImage bitmap = new BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 60; y++) for (int x = 0; x < 40; x++) bitmap.setRGB(x, y, color);
        try (java.io.ByteArrayOutputStream out = new ByteArrayOutputStream()) { ImageIO.write(bitmap, "png", out); return out.toByteArray(); }
        finally { bitmap.flush(); }
    }
}
