package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.ocr.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.util.Units;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileParserRoutingTest {
    @TempDir Path temporary;

    @Test void nativeTextAndNativeTablesNotifyWithoutCallingOcr() throws Exception {
        Path source = temporary.resolve("native.txt");
        cn.bidcheck.compat.Java8Api.writeString(source, "施工方案正文\f车辆信息表\n车牌\t车辆识别代号\t数量\n琼A12345\tLSVA12345678901234\t1");
        cn.bidcheck.document.FileParserPdfOcrTest.SingleClient single = new FileParserPdfOcrTest.SingleClient();
        java.util.ArrayList<cn.bidcheck.domain.Domain.Page> pages = new ArrayList<Page>(); java.util.ArrayList<cn.bidcheck.document.PageClassifier.Decision> routes = new ArrayList<PageClassifier.Decision>();
        Thread caller = Thread.currentThread();
        cn.bidcheck.document.ExtractionResult result = new FileParser(single).parsePlanned(source, "native.txt", null, ExtractionPlan.full(), new OcrUnitObserver() {
            public void onPage(Page page, PageClassifier.Decision decision) {
                assertSame(caller, Thread.currentThread()); pages.add(page); routes.add(decision);
            }
        });
        assertEquals(result.document().pages(), pages);
        assertEquals(0, single.calls.get());
        assertTrue(routes.stream().allMatch(r -> r.parserStrategy().equals("NATIVE_PARSE")));
        assertEquals("TABLE_PAGE", routes.get(1).pageType());
        assertEquals("VEHICLE_TABLE_V1", routes.get(1).schema());
        assertTrue(routes.get(1).requiresCrossPageContext());
    }

    @Test void pdfPagesAreDeliveredWhileLaterBatchIsStillRunningAndOcrRefinesUnknownTypes() throws Exception {
        Path source = pdf("incremental.pdf", 21, cn.bidcheck.compat.Java8Api.setOf(1));
        java.util.concurrent.atomic.AtomicBoolean nativeDelivered = new AtomicBoolean(); java.util.concurrent.CountDownLatch firstBatchDelivered = new CountDownLatch(1);
        java.util.TreeMap<java.lang.Integer, cn.bidcheck.domain.Domain.Page> pages = new TreeMap<Integer, Page>(); java.util.TreeMap<java.lang.Integer, cn.bidcheck.document.PageClassifier.Decision> routes = new TreeMap<Integer, PageClassifier.Decision>();
        java.util.HashSet<java.lang.Integer> initiallyUnknown = new HashSet<Integer>(); Thread caller = Thread.currentThread();
        cn.bidcheck.document.FileParserPdfOcrTest.PdfClient client = new FileParserPdfOcrTest.PdfClient((bytes, numbers) -> {
            assertTrue(nativeDelivered.get(), "Native pages must be usable before cloud recognition finishes");
            if (numbers.get(0) == 12) FileParserPdfOcrTest.await(firstBatchDelivered);
            Map<Integer, OcrResult> recognized = new LinkedHashMap<>();
            for (int page : numbers) recognized.put(page, FileParserPdfOcrTest.text(page == 2 ? "注册资格证书 编号：AB12345"
                    : page == 3 ? "<table><tr><td>姓名</td><td>身份证号</td></tr><tr><td>张三</td><td>123456</td></tr></table>"
                    : "Source transcription for page " + page));
            return new PdfOcrClient.BatchResult(recognized, cn.bidcheck.compat.Java8Api.mapOf(), cn.bidcheck.compat.Java8Api.mapOf());
        });
        cn.bidcheck.document.ExtractionResult result = new FileParser(new FileParserPdfOcrTest.SingleClient(), client, 100, 4)
                .parsePlanned(source, "incremental.pdf", null, ExtractionPlan.full(), new OcrUnitObserver() {
                    public void onClassified(String type, int position, PageClassifier.Decision decision) {
                        assertSame(caller, Thread.currentThread());
                        if (decision.pageType().equals("UNKNOWN")) initiallyUnknown.add(position);
                    }
                    public void onPage(Page page, PageClassifier.Decision decision) {
                        assertSame(caller, Thread.currentThread());
                        assertNull(pages.put(page.number(), page), "A page must not be delivered twice");
                        routes.put(page.number(), decision);
                        if (page.number() == 1) nativeDelivered.set(true);
                        if (page.number() == 11) firstBatchDelivered.countDown();
                    }
                });
        assertEquals(result.document().pages(), new ArrayList<>(pages.values()));
        assertTrue(initiallyUnknown.containsAll(cn.bidcheck.compat.Java8Api.listOf(2, 3)));
        assertEquals("CERTIFICATE", routes.get(2).pageType());
        assertEquals("INDEPENDENT_VISUAL", routes.get(2).parserStrategy());
        assertEquals("PERSON_TABLE_V1", routes.get(3).schema());
        assertTrue(routes.get(3).requiresCrossPageContext());
        assertEquals("OCR_MICROBATCH", routes.get(4).parserStrategy());
        assertTrue(pages.get(2).layout().frames().isEmpty(), "PDF OCR must not invent pixel boxes");
        assertEquals(21, result.report().get("completedPageNotifications"));
        java.util.List<?> map = (List<?>) result.report().get("nativeMap");
        assertEquals("CERTIFICATE", ((Map<?, ?>) map.get(1)).get("pageType"));
    }

    @Test void deferredPagesAlsoNotifyAndSuccessfulFallbackHasOriginalImageSource() throws Exception {
        Path source = pdf("selected.pdf", 3, cn.bidcheck.compat.Java8Api.setOf(1));
        cn.bidcheck.document.ExtractionPlan plan = ExtractionPlan.selected(FileParserPdfOcrTest.hash(Files.readAllBytes(source)),
                cn.bidcheck.compat.Java8Api.listOf(OcrSelection.position("PAGE", 2, "test")), 1, "selected");
        cn.bidcheck.document.FileParserPdfOcrTest.PdfClient client = new FileParserPdfOcrTest.PdfClient((bytes, numbers) -> new PdfOcrClient.BatchResult(cn.bidcheck.compat.Java8Api.mapOf(), cn.bidcheck.compat.Java8Api.mapOf(2, "FAILED"), cn.bidcheck.compat.Java8Api.mapOf()));
        java.util.TreeMap<java.lang.Integer, cn.bidcheck.domain.Domain.Page> pages = new TreeMap<Integer, Page>();
        cn.bidcheck.document.ExtractionResult result = new FileParser(new FileParserPdfOcrTest.SingleClient("居民身份证 公民身份号码：123456"), client, 10, 4)
                .parsePlanned(source, "selected.pdf", null, plan, new OcrUnitObserver() {
                    public void onPage(Page page, PageClassifier.Decision decision) {
                        pages.put(page.number(), page);
                        if (page.number() == 2) assertEquals("ID_CARD", decision.pageType());
                    }
                });
        assertEquals(result.document().pages(), new ArrayList<>(pages.values()));
        assertTrue(pages.get(3).warning().contains("OCR_DEFERRED"));
        cn.bidcheck.ocr.OcrLayout.Frame frame = pages.get(2).layout().frames().get(0);
        assertEquals("PDF_RENDER", frame.sourceKind()); assertEquals(2, frame.sourceIndex());
    }

    @Test void nativePagePersistenceFailureStopsBeforeAnyPdfModelRequest() throws Exception {
        Path source = pdf("fenced.pdf", 3, cn.bidcheck.compat.Java8Api.setOf(1));
        IOException fence = new IOException("source page lease no longer valid");
        cn.bidcheck.document.FileParserPdfOcrTest.PdfClient client = new FileParserPdfOcrTest.PdfClient((bytes, numbers) -> FileParserPdfOcrTest.success(numbers));
        assertSame(fence, assertThrows(IOException.class, () -> new FileParser(new FileParserPdfOcrTest.SingleClient(), client, 10, 4)
                .parsePlanned(source, "fenced.pdf", null, ExtractionPlan.full(), new OcrUnitObserver() {
                    public void onPage(Page page, PageClassifier.Decision decision) throws IOException { throw fence; }
                })));
        assertTrue(client.requests.isEmpty());
    }

    @Test void imageOcrPdfPathNotifiesLaterPageBeforeSlowFirstPageFinishes() throws Exception {
        Path source = pdf("out-of-order.pdf", 2, cn.bidcheck.compat.Java8Api.setOf());
        java.util.concurrent.CountDownLatch laterDelivered = new CountDownLatch(1); java.util.TreeMap<java.lang.Integer, cn.bidcheck.domain.Domain.Page> pages = new TreeMap<Integer, Page>();
        cn.bidcheck.document.FileParserPdfOcrTest.SingleClient single = new FileParserPdfOcrTest.SingleClient();
        OcrClient client = new OcrClient() {
            public boolean enabled() { return true; }
            public String modelName() { return "out-of-order"; }
            public String identity() { return "out-of-order-1"; }
            public OcrResult recognize(byte[] bytes, String mime) throws IOException {
                java.awt.image.BufferedImage rendered = ImageIO.read(new ByteArrayInputStream(bytes));
                int width = rendered.getWidth(); rendered.flush();
                if (width == 303) FileParserPdfOcrTest.await(laterDelivered);
                return single.recognize(bytes, mime);
            }
        };
        cn.bidcheck.document.ExtractionResult result = new FileParser(client, 10, 2).parsePlanned(source, "out-of-order.pdf", null, ExtractionPlan.full(), new OcrUnitObserver() {
            public void onPage(Page page, PageClassifier.Decision decision) {
                assertNull(pages.put(page.number(), page));
                if (page.number() == 2) laterDelivered.countDown();
            }
        });
        assertEquals(result.document().pages(), new ArrayList<>(pages.values()));
        assertEquals(2, single.calls.get());
    }

    @Test void wordPlanningDoesNotPublishPreviewAndFinalBlockContainsEveryImageSource() throws Exception {
        Path source = temporary.resolve("images.docx"); byte[] image = image();
        try (org.apache.poi.xwpf.usermodel.XWPFDocument word = new XWPFDocument()) {
            word.createParagraph().createRun().setText("原生说明");
            org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph = word.createParagraph();
            for (int i = 0; i < 2; i++) paragraph.createRun().addPicture(new ByteArrayInputStream(image), XWPFDocument.PICTURE_TYPE_PNG,
                    "image" + i + ".png", Units.toEMU(32), Units.toEMU(32));
            try (java.io.OutputStream output = Files.newOutputStream(source)) { word.write(output); }
        }
        cn.bidcheck.document.ExtractionPlan plan = ExtractionPlan.selected(FileParserPdfOcrTest.hash(Files.readAllBytes(source)),
                cn.bidcheck.compat.Java8Api.listOf(OcrSelection.position("BLOCK", 2, "images")), 2, "test");
        java.util.ArrayList<cn.bidcheck.domain.Domain.Page> pages = new ArrayList<Page>();
        cn.bidcheck.document.ExtractionResult result = new FileParser(new FileParserPdfOcrTest.SingleClient("资格证书 编号：TEST123"))
                .parsePlanned(source, "images.docx", null, plan, new OcrUnitObserver() {
                    public void onPage(Page page, PageClassifier.Decision decision) { pages.add(page); }
                });
        assertEquals(result.document().pages(), pages);
        assertEquals(2, pages.size());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2), cn.bidcheck.compat.Java8Api.toList(pages.get(1).layout().frames().stream().map(OcrLayout.Frame::sourceIndex)));
        assertTrue(pages.get(1).layout().frames().stream().allMatch(f -> f.sourceKind().equals("WORD_EMBEDDED")));
    }

    @Test void failedUploadedImageRemainsAnExplicitIncompletePage() throws Exception {
        Path source = temporary.resolve("failed.png"); Files.write(source, image());
        OcrClient failed = new OcrClient() {
            public boolean enabled() { return true; }
            public String modelName() { return "offline"; }
            public String identity() { return "offline-failed-image"; }
            public OcrResult recognize(byte[] bytes, String mime) throws IOException { throw new IOException("recognition failed"); }
        };
        java.util.ArrayList<cn.bidcheck.domain.Domain.Page> pages = new ArrayList<Page>();
        cn.bidcheck.document.ExtractionResult result = new FileParser(failed).parsePlanned(source, "failed.png", null, ExtractionPlan.full(), new OcrUnitObserver() {
            public void onPage(Page page, PageClassifier.Decision decision) { pages.add(page); }
        });
        assertEquals(result.document().pages(), pages);
        assertEquals(1, pages.size()); assertTrue(pages.get(0).warning().contains("OCR未完成"));
    }

    @Test void classificationKeepsGeometryAndDoesNotTreatOneTabAsAComplexTable() {
        cn.bidcheck.document.PageClassifier.Decision table = new PageClassifier.Decision("TABLE_PAGE", "INDEPENDENT_VISUAL", "TABLE_V1", "HIGH", true, "MEDIUM", true);
        assertEquals(table, PageClassifier.afterRecognition(table, "表格中未能恢复的普通文字", 1));
        assertEquals("SCAN_TEXT_PAGE", PageClassifier.nativeOnly("姓名\t张三", 1).pageType());
        assertEquals("UNKNOWN", PageClassifier.nativeOnly("", 1).pageType());
        assertEquals("OCR_MICROBATCH", PageClassifier.nativeOnly("", 1).parserStrategy());
        assertTrue(PageClassifier.usableNativeText("封面"));
        assertTrue(PageClassifier.usableNativeText("项目名称：正常原生正文"));
        assertFalse(PageClassifier.usableNativeText("\uFFFD\uFFFD\uE123\uE124"));
    }

    private Path pdf(String name, int count, Set<Integer> nativePages) throws IOException {
        cn.bidcheck.document.FileParserPdfOcrTest helper = new FileParserPdfOcrTest(); helper.temporary = temporary; return helper.pdf(name, count, nativePages);
    }
    private static byte[] image() throws IOException {
        java.awt.image.BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics(); graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 32, 32); graphics.dispose();
        try (java.io.ByteArrayOutputStream bytes = new ByteArrayOutputStream()) { ImageIO.write(image, "png", bytes); return bytes.toByteArray(); }
        finally { image.flush(); }
    }
}
