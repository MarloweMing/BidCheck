package cn.bidcheck.document;

import cn.bidcheck.ocr.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileParserBatchTest {
    @TempDir Path temporary;

    @Test void seventeenImagesUseActualEightInputRequestsInParallelAndFlushTail() throws Exception {
        Path file = word("seventeen.docx", 17, 80, cn.bidcheck.compat.Java8Api.listOf());
        java.util.concurrent.CountDownLatch twoBatches = new CountDownLatch(2);
        cn.bidcheck.document.FileParserBatchTest.BatchClient client = new BatchClient(OcrBatchPolicy.defaults(), (inputs, listener) -> {
            if (inputs.size() == 8) { twoBatches.countDown(); await(twoBatches); }
            for (cn.bidcheck.ocr.OcrBatch.Input input : inputs) listener.accept(success(input));
        });
        cn.bidcheck.document.ExtractionResult result = new FileParser(client, 100, 2).parsePlanned(file, "seventeen.docx", null, ExtractionPlan.full());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 8, 8), cn.bidcheck.compat.Java8Api.toList(client.sizes.stream().sorted()));
        assertEquals(0, client.singleCalls.get(), "A real multi-input API must be invoked, not a loop over single requests");
        assertEquals(2, client.peak.get());
        assertEquals(17, result.document().pages().size());
        for (int index = 0; index < 17; index++) {
            assertEquals(index + 1, result.document().pages().get(index).number());
            assertTrue(result.document().pages().get(index).text().contains("BLOCK:" + (index + 1) + ":1"));
        }
        Map<?, ?> stats = (Map<?, ?>) result.report().get("ocrBatchScheduler");
        assertEquals(3, stats.get("submittedBatches")); assertEquals(8, stats.get("peakBatchPages"));
    }

    @Test void missingBatchCapabilityKeepsTheExistingSingleImageAdapterUsable() throws Exception {
        Path file = word("legacy.docx", 2, 80, cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.document.FileParserBatchTest.BatchClient client = new BatchClient(null, (inputs, listener) -> fail("Unadvertised batch capability must not be called"));
        cn.bidcheck.document.ExtractionResult result = new FileParser(client).parsePlanned(file, "legacy.docx", null, ExtractionPlan.full());
        assertEquals(2, client.singleCalls.get()); assertTrue(client.sizes.isEmpty());
        assertEquals(true, result.report().get("ocrCoverageComplete"));
        assertEquals(false, ((Map<?, ?>) result.report().get("ocrBatchScheduler")).get("enabled"));
    }

    @Test void configuredSixteenPageLimitIsHonoredWithinResourceBudgets() throws Exception {
        Path file = word("sixteen.docx", 16, 80, cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.ocr.OcrBatchPolicy policy = new OcrBatchPolicy(true, 16, 2000, 16L * 1024 * 1024, 16_000_000, 20000);
        cn.bidcheck.document.FileParserBatchTest.BatchClient client = new BatchClient(policy, FileParserBatchTest::complete);
        cn.bidcheck.document.ExtractionResult result = new FileParser(client).parsePlanned(file, "sixteen.docx", null, ExtractionPlan.full());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(16), client.sizes);
        assertEquals(0, client.singleCalls.get());
        assertEquals(true, result.report().get("ocrCoverageComplete"));
        assertEquals(16, ((Map<?, ?>) result.report().get("ocrBatchScheduler")).get("maxPages"));
    }

    @Test void eightInputPartialBatchPersistsSuccessWhileFailedPageIsStillRetrying() throws Exception {
        Path file = word("partial.docx", 8, 80, cn.bidcheck.compat.Java8Api.listOf());
        java.util.concurrent.CountDownLatch durable = new CountDownLatch(1); java.util.ArrayList<cn.bidcheck.document.OcrUnitObserver.Event> events = new ArrayList<OcrUnitObserver.Event>();
        Thread caller = Thread.currentThread();
        cn.bidcheck.document.FileParserBatchTest.BatchClient client = new BatchClient(OcrBatchPolicy.defaults(), (inputs, listener) -> {
            listener.onBatch(new OcrBatch.Event("test-batch", "", "RUNNING", cn.bidcheck.compat.Java8Api.toList(inputs.stream().map(OcrBatch.Input::id)), "offline", "test", cn.bidcheck.compat.Java8Api.repeat("1", 64), 51200, 80, 0, ""));
            for (int i = 0; i < 7; i++) listener.accept(success(inputs.get(i)));
            await(durable);
            listener.accept(OcrBatch.Item.failed(inputs.get(7).id(), "OCR_TIMEOUT", true));
        });
        java.util.ArrayList<cn.bidcheck.ocr.OcrBatch.Event> batchEvents = new ArrayList<OcrBatch.Event>();
        cn.bidcheck.document.OcrUnitObserver observer = new OcrUnitObserver() {
            public void onState(Event event) {
                assertSame(caller, Thread.currentThread()); events.add(event);
                if (event.status().equals("SUCCEEDED")) durable.countDown();
            }
            public void onBatch(OcrBatch.Event event) { assertSame(caller, Thread.currentThread()); batchEvents.add(event); }
        };
        cn.bidcheck.document.ExtractionResult result = new FileParser(client, 100, 2).parsePlanned(file, "partial.docx", null, ExtractionPlan.full(), observer);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(8), client.sizes); assertEquals(1, batchEvents.size());
        assertEquals(7, events.stream().filter(e -> e.status().equals("SUCCEEDED")).count());
        assertEquals(1, events.stream().filter(e -> e.status().equals("FAILED")).count());
        assertEquals(7L, result.report().get("recognizedImageCount")); assertEquals(1L, result.report().get("failedImageCount"));
        assertTrue(result.document().pages().get(7).warning().contains("OCR_TIMEOUT"));
    }

    @Test void retryableItemCanBecomeSuccessfulWithoutPublishingAnEarlyTerminalFailure() throws Exception {
        Path file = word("retry.docx", 2, 80, cn.bidcheck.compat.Java8Api.listOf());
        java.util.ArrayList<java.lang.String> statuses = new ArrayList<String>();
        cn.bidcheck.document.FileParserBatchTest.BatchClient client = new BatchClient(OcrBatchPolicy.defaults(), (inputs, listener) -> {
            listener.accept(OcrBatch.Item.failed(inputs.get(0).id(), "OCR_TIMEOUT", true));
            for (cn.bidcheck.ocr.OcrBatch.Input input : inputs) { listener.accept(success(input)); listener.accept(success(input)); }
        });
        cn.bidcheck.document.OcrUnitObserver observer = new OcrUnitObserver() { public void onState(Event event) { statuses.add(event.status()); } };
        cn.bidcheck.document.ExtractionResult result = new FileParser(client).parsePlanned(file, "retry.docx", null, ExtractionPlan.full(), observer);
        assertEquals(2, statuses.stream().filter("SUCCEEDED"::equals).count()); assertFalse(statuses.contains("FAILED"));
        assertEquals(true, result.report().get("ocrCoverageComplete"));
    }

    @Test void independentCertificatesAndTablesKeepSingleImageStructuredRecognition() throws Exception {
        Path file = word("routing.docx", 4, 80, cn.bidcheck.compat.Java8Api.listOf("资格证书原件", "车辆信息表\t车辆识别代号列表", "普通扫描正文", "普通扫描正文"));
        cn.bidcheck.document.FileParserBatchTest.BatchClient client = new BatchClient(OcrBatchPolicy.defaults(), FileParserBatchTest::complete);
        cn.bidcheck.document.ExtractionResult result = new FileParser(client).parsePlanned(file, "routing.docx", null, ExtractionPlan.full());
        assertEquals(2, client.singleCalls.get()); assertEquals(cn.bidcheck.compat.Java8Api.listOf(2), client.sizes);
        java.util.List<?> map = (List<?>) result.report().get("nativeMap");
        assertEquals("CERTIFICATE", ((Map<?, ?>) map.get(0)).get("pageType"));
        assertEquals("TABLE_PAGE", ((Map<?, ?>) map.get(1)).get("pageType"));
        assertEquals(true, ((Map<?, ?>) map.get(1)).get("requiresCrossPageContext"));
        assertEquals("HIGH", ((Map<?, ?>) map.get(0)).get("priority"));
        assertEquals("INDEPENDENT_VISUAL", ((Map<?, ?>) map.get(0)).get("parserStrategy"));
        assertEquals("SCAN_TEXT_PAGE", ((Map<?, ?>) map.get(2)).get("pageType"));
    }

    @Test void differentResolutionBucketsDoNotEnterTheSameRequest() throws Exception {
        Path file = temporary.resolve("resolution.docx");
        try (org.apache.poi.xwpf.usermodel.XWPFDocument word = new XWPFDocument()) {
            add(word, "普通扫描正文", image(1, 80)); add(word, "普通扫描正文", image(2, 800));
            try (java.io.OutputStream output = Files.newOutputStream(file)) { word.write(output); }
        }
        cn.bidcheck.document.FileParserBatchTest.BatchClient client = new BatchClient(OcrBatchPolicy.defaults(), FileParserBatchTest::complete);
        new FileParser(client).parse(file, "resolution.docx");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 1), client.sizes);
    }

    @Test void bytesPixelsAndTokensEachIndependentlySplitRequestsBeforeTheirLimit() throws Exception {
        Path file = word("limits.docx", 7, 400, cn.bidcheck.compat.Java8Api.listOf());
        long bytes = 0; for (int i = 1; i <= 7; i++) bytes = Math.max(bytes, image(i, 400).length);
        java.util.List<cn.bidcheck.ocr.OcrBatchPolicy> policies = cn.bidcheck.compat.Java8Api.listOf(new OcrBatchPolicy(true, 8, 300, Math.max(1024, bytes * 2 + 8), 16_000_000, 20000), new OcrBatchPolicy(true, 8, 300, 16L * 1024 * 1024, 400_000, 20000), new OcrBatchPolicy(true, 8, 300, 16L * 1024 * 1024, 16_000_000, 1150));
        for (cn.bidcheck.ocr.OcrBatchPolicy policy : policies) {
            cn.bidcheck.document.FileParserBatchTest.BatchClient client = new BatchClient(policy, (inputs, listener) -> {
                assertTrue(inputs.stream().mapToLong(i -> i.image().length).sum() <= policy.maxBytes());
                assertTrue(inputs.stream().mapToLong(OcrBatch.Input::pixels).sum() <= policy.maxPixels());
                assertTrue(OcrBatch.estimatedRequestTokens(inputs) <= policy.maxEstimatedTokens());
                complete(inputs, listener);
            });
            cn.bidcheck.document.ExtractionResult result = new FileParser(client).parsePlanned(file, "limits.docx", null, ExtractionPlan.full());
            assertEquals(7, client.sizes.stream().mapToInt(Integer::intValue).sum());
            assertTrue(client.sizes.stream().allMatch(size -> size <= 3)); assertEquals(true, result.report().get("ocrCoverageComplete"));
        }
    }

    @Test void evenOneImageOverTheCompletePromptBudgetIsDeferredWithoutSendingOrSpendingImageBudget() throws Exception {
        Path file = word("token-deferred.docx", 2, 80, cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.ocr.OcrBatchPolicy policy = new OcrBatchPolicy(true, 8, 300, 16L * 1024 * 1024, 16_000_000, 512);
        cn.bidcheck.document.FileParserBatchTest.BatchClient client = new BatchClient(policy, FileParserBatchTest::complete);
        cn.bidcheck.document.ExtractionResult result = new FileParser(client).parsePlanned(file, "token-deferred.docx", null, ExtractionPlan.full());
        assertTrue(client.sizes.isEmpty()); assertEquals(0, client.singleCalls.get());
        assertEquals(2, result.report().get("deferredImageCount")); assertEquals(0L, result.report().get("failedImageCount"));
        assertEquals(0, ((Map<?, ?>) result.report().get("ocrPipeline")).get("submittedImages"));
    }

    @Test void documentMemoryPressureFlushesWaitingTailWithoutDeadlockOrDroppingUnits() throws Exception {
        Path file = word("backpressure.docx", 5, 400, cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.document.FileParserBatchTest.BatchClient client = new BatchClient(OcrBatchPolicy.defaults(), FileParserBatchTest::complete);
        cn.bidcheck.document.ExtractionResult result = new FileParser(client, 100, 2, 16L * 1024 * 1024, 320_000)
                .parsePlanned(file, "backpressure.docx", null, ExtractionPlan.full());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2, 2), cn.bidcheck.compat.Java8Api.toList(client.sizes.stream().sorted()));
        assertEquals(5L, result.report().get("recognizedImageCount"));
        assertTrue(((Long) ((Map<?, ?>) result.report().get("ocrPipeline")).get("peakActivePixels")).longValue() <= 320_000);
    }

    @Test void maxWaitDeadlineFlushesAtNextCallerSchedulingPointWithoutWaitingForEight() throws Exception {
        Path file = word("wait.docx", 2, 80, cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.document.FileParserBatchTest.BatchClient client = new BatchClient(OcrBatchPolicy.defaults(), FileParserBatchTest::complete);
        java.util.concurrent.atomic.AtomicInteger blocks = new AtomicInteger();
        new FileParser(client).parse(file, "wait.docx", (percent, stage) -> {
            if (stage.equals("正在解析 Word 内容块") && blocks.incrementAndGet() == 2) {
                try { Thread.sleep(350); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException(stopped); }
            }
        });
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 1), client.sizes);
    }

    @Test void batchMetadataLeaseFailureRemainsFatalAndCancelsProviderWork() throws Exception {
        Path file = word("fence.docx", 8, 80, cn.bidcheck.compat.Java8Api.listOf());
        java.util.concurrent.CountDownLatch cancelled = new CountDownLatch(1); IOException fence = new IOException("durable batch fence rejected");
        cn.bidcheck.document.FileParserBatchTest.BatchClient client = new BatchClient(OcrBatchPolicy.defaults(), (inputs, listener) -> {
            listener.onBatch(new OcrBatch.Event("batch", "", "RUNNING", cn.bidcheck.compat.Java8Api.toList(inputs.stream().map(OcrBatch.Input::id)), "offline", "test", cn.bidcheck.compat.Java8Api.repeat("1", 64), 51200, 80, 0, ""));
            try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
            catch (InterruptedException stopped) { cancelled.countDown(); Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
        });
        cn.bidcheck.document.OcrUnitObserver observer = new OcrUnitObserver() { public void onBatch(OcrBatch.Event event) throws IOException { throw fence; } };
        assertSame(fence, assertThrows(IOException.class, () -> new FileParser(client).parsePlanned(file, "fence.docx", null, ExtractionPlan.full(), observer)));
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
    }

    @Test void localClassifierUsesNativeAndLowResolutionGridSignalsWithoutSkippingUnknown() throws Exception {
        assertEquals("NATIVE_PARSE", PageClassifier.nativeOnly("普通技术方案正文", 0).parserStrategy());
        assertTrue(PageClassifier.nativeOnly("居民身份证", 1).independent());
        assertEquals("CONTRACT", PageClassifier.nativeOnly("合同编号：123；甲方", 1).pageType());
        java.awt.image.BufferedImage blank = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB); java.awt.Graphics2D graphics = blank.createGraphics();
        graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 100, 100); graphics.dispose();
        byte[] empty = encode(blank);
        assertEquals("UNKNOWN", PageClassifier.classify("", 1, empty).pageType());
        assertEquals("OCR_MICROBATCH", PageClassifier.classify("", 1, empty).parserStrategy());
        graphics = blank.createGraphics(); graphics.setColor(java.awt.Color.BLACK);
        for (int at : cn.bidcheck.compat.Java8Api.listOf(10, 50, 90)) { graphics.drawLine(at, 0, at, 99); graphics.drawLine(0, at, 99, at); }
        graphics.dispose();
        cn.bidcheck.document.PageClassifier.Decision table = PageClassifier.classify("", 1, encode(blank)); blank.flush();
        assertEquals("TABLE_PAGE", table.pageType()); assertTrue(table.independent()); assertTrue(table.requiresCrossPageContext());
    }

    private interface Behavior { void run(List<OcrBatch.Input> inputs, OcrBatch.Listener listener) throws IOException; }
    private static class BatchClient implements OcrClient {
        final OcrBatchPolicy policy; final Behavior behavior;
        final List<Integer> sizes = new CopyOnWriteArrayList<>(); final AtomicInteger singleCalls = new AtomicInteger(), active = new AtomicInteger(), peak = new AtomicInteger();
        BatchClient(OcrBatchPolicy policy, Behavior behavior) { this.policy = policy; this.behavior = behavior; }
        public boolean enabled() { return true; } public String modelName() { return "offline-batch"; } public String identity() { return "offline-batch-1"; }
        public OcrBatchPolicy batchPolicy() { return policy; }
        public OcrResult recognize(byte[] bytes, String mime) { singleCalls.incrementAndGet(); return new OcrResult("独立材料原文", "test", modelName(), false); }
        public void recognizeBatch(List<OcrBatch.Input> inputs, OcrBatch.Listener listener) throws IOException {
            sizes.add(inputs.size()); peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { behavior.run(inputs, listener); } finally { active.decrementAndGet(); }
        }
    }
    private static OcrBatch.Item success(OcrBatch.Input input) { return OcrBatch.Item.success(input.id(), new OcrResult("已识别原文 " + input.id(), "test", "offline-batch", false)); }
    private static void complete(List<OcrBatch.Input> inputs, OcrBatch.Listener listener) throws IOException { for (cn.bidcheck.ocr.OcrBatch.Input input : inputs) listener.accept(success(input)); }
    private static void await(CountDownLatch latch) throws IOException {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
    }
    private Path word(String name, int count, int size, List<String> nativeText) throws Exception {
        Path file = temporary.resolve(name);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument word = new XWPFDocument()) {
            for (int i = 1; i <= count; i++) add(word, nativeText.isEmpty() ? "普通扫描正文" : nativeText.get(i - 1), image(i, size));
            try (java.io.OutputStream output = Files.newOutputStream(file)) { word.write(output); }
        }
        return file;
    }
    private static void add(XWPFDocument word, String text, byte[] bytes) throws Exception {
        org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph = word.createParagraph(); paragraph.createRun().setText(text);
        paragraph.createRun().addPicture(new ByteArrayInputStream(bytes), XWPFDocument.PICTURE_TYPE_PNG, "scan.png", 800_000, 800_000);
    }
    private static byte[] image(int index, int size) throws IOException {
        java.awt.image.BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB); java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(index * 0x030303)); graphics.fillRect(0, 0, size, size); graphics.dispose();
        try { return encode(image); } finally { image.flush(); }
    }
    private static byte[] encode(BufferedImage image) throws IOException { try (java.io.ByteArrayOutputStream bytes = new ByteArrayOutputStream()) { ImageIO.write(image, "png", bytes); return bytes.toByteArray(); } }
}
