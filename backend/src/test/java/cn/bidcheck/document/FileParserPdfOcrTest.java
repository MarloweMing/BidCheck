package cn.bidcheck.document;
import cn.bidcheck.compat.HexFormat;

import cn.bidcheck.ocr.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileParserPdfOcrTest {
    @TempDir Path temporary;
    private static final String GENERATED = "此格式严格遵循 Markdown 语法，符合信息抽取专家角色要求。";

    @Test void all581OriginalPagesUse59BoundedConcurrentPacketsAndFinalSingleton() throws Exception {
        Path source = pdf("581.pdf", 581, cn.bidcheck.compat.Java8Api.setOf());
        CountDownLatch firstFive = new CountDownLatch(5);
        PdfClient client = new PdfClient((bytes, pages) -> {
            firstFive.countDown(); await(firstFive);
            return success(pages);
        });
        Journal journal = new Journal();
        cn.bidcheck.document.ExtractionResult result = new FileParser(new SingleClient(), client, 1000, 4).parsePlanned(source, "581.pdf", null, ExtractionPlan.full(), journal);
        assertEquals(59, client.requests.size());
        assertTrue(client.requests.contains(cn.bidcheck.compat.Java8Api.listOf(581)));
        assertEquals(5, client.peak.get());
        assertEquals(581, result.document().pages().size());
        assertEquals(581, journal.successes().size());
        assertEquals(581, client.requests.stream().flatMap(List::stream).distinct().count());
        assertEquals("PDF body source 581", result.document().pages().get(580).text());
        assertEquals(true, result.report().get("ocrCoverageComplete"));
        java.util.Map<?, ?> metrics = (Map<?, ?>) result.report().get("pdfOcrScheduler");
        assertEquals(59, metrics.get("submittedBatches"));
        assertEquals(0, metrics.get("singlePageFallbackCalls"));
        assertTrue(((Long) metrics.get("peakActiveBytes")).longValue() <= ((Long) metrics.get("maxBytes")).longValue());
        assertEquals(581, metrics.get("traversedPages"));
    }

    @Test void onlyMissingBlankAndExplicitlyFailedPagesRecoverThroughSingleImageApi() throws Exception {
        Path source = pdf("partial.pdf", 6, cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client = new PdfClient((bytes, pages) -> new PdfOcrClient.BatchResult(
                cn.bidcheck.compat.Java8Api.mapOf(1, text("PDF page one"), 2, text(" "), 3, text("3"), 5, text("PDF page five")), cn.bidcheck.compat.Java8Api.mapOf(6, "PAGE_FAILED"), cn.bidcheck.compat.Java8Api.mapOf()));
        SingleClient single = new SingleClient(); Journal journal = new Journal();
        FileParser parser = new FileParser(single, client, 10, 4);
        cn.bidcheck.document.ExtractionResult result = parser.parsePlanned(source, "partial.pdf", null, ExtractionPlan.full(), journal);
        assertEquals(4, single.calls.get()); assertEquals(1, client.requests.size());
        assertTrue(journal.batches.stream().anyMatch(e -> e.status().equals("RECOVERED")));
        assertEquals(4, journal.batches.stream().filter(e -> e.status().equals("SUCCEEDED") && !e.parentId().isEmpty()).count());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(2, 3, 4, 6), cn.bidcheck.compat.Java8Api.toList(result.document().pages().stream().filter(p -> p.parser().equals("PDFBOX_SINGLE_OCR")).map(p -> p.number())));
        for (int number : cn.bidcheck.compat.Java8Api.listOf(2, 3, 4, 6)) {
            cn.bidcheck.ocr.OcrLayout.Frame frame = result.document().pages().get(number - 1).layout().frames().get(0);
            assertEquals(number, frame.sourceIndex()); assertEquals("PDF_RENDER", frame.sourceKind());
        }
        parser.parsePlanned(source, "partial.pdf", null, ExtractionPlan.full(), journal);
        assertEquals(1, client.requests.size(), "All PDF and single-image successes restore with their source identities");
        assertEquals(4, single.calls.get());
    }

    @Test void selectedOriginalPagesAreTheOnlyPagesInUploadedPdfAndNativeMapComesFirst() throws Exception {
        Path source = pdf("selected.pdf", 23, cn.bidcheck.compat.Java8Api.setOf());
        AtomicBoolean mapped = new AtomicBoolean();
        PdfClient client = new PdfClient((bytes, pages) -> { assertTrue(mapped.get()); return success(pages); });
        cn.bidcheck.document.ExtractionPlan plan = ExtractionPlan.selected(hash(Files.readAllBytes(source)), cn.bidcheck.compat.Java8Api.listOf(OcrSelection.position("PAGE", 3, "chosen"), OcrSelection.position("PAGE", 11, "chosen"), OcrSelection.position("PAGE", 23, "chosen")), 3, "selected");
        cn.bidcheck.document.ExtractionResult result = new FileParser(new SingleClient(), client, 100, 4).parsePlanned(source, "selected.pdf",
                (percent, stage) -> { if (stage.contains("已确定")) mapped.set(true); }, plan);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(3, 11, 23)), client.requests);
        assertEquals(23, result.document().pages().size()); assertEquals(20, result.report().get("deferredImageCount"));
        assertEquals(23, ((List<?>) result.report().get("nativeMap")).size());
        assertTrue(result.document().pages().get(22).text().contains("source 23"));
    }

    @Test void structuredPdfRecoveryUsesPlainTextKeepsLayoutGapAndRestoresWithoutAdvancedOcr() throws Exception {
        Path source = pdf("plain-recovery.pdf", 1, cn.bidcheck.compat.Java8Api.setOf());
        PdfClient pdf = missingPdf();
        PlainClient plain = new PlainClient("plain-v1", "Source business evidence");
        cn.bidcheck.ocr.StructuredOcrClient structured = structured(plain); Journal journal = new Journal();
        try {
            assertTrue(structured.layoutEnabled(), "The normal structured path would call advanced OCR");
            cn.bidcheck.document.FileParser parser = new FileParser(structured, pdf, 10, 4);
            cn.bidcheck.document.ExtractionResult result = parser.parsePlanned(source, "plain-recovery.pdf", null, ExtractionPlan.full(), journal);
            assertEquals(1, plain.calls.get());
            cn.bidcheck.domain.Domain.Page page = result.document().pages().get(0);
            assertEquals("Source business evidence", page.text());
            assertEquals("UNAVAILABLE", page.layout().status());
            assertTrue(page.warning().contains("[OCR结构未完成]"));
            assertTrue(page.warning().contains("[LAYOUT_PDF_TEXT_RECOVERY]"));
            assertFalse(page.warning().contains("[OCR未完成]"));
            assertEquals(true, result.report().get("ocrCoverageComplete"));
            assertEquals(1, journal.successes().size());
            assertEquals("plain-v1", journal.batches.stream().filter(e -> !e.parentId().isEmpty()).findFirst().orElseThrow(java.util.NoSuchElementException::new).model());
            cn.bidcheck.document.ExtractionResult restored = parser.parsePlanned(source, "plain-recovery.pdf", null, ExtractionPlan.full(), journal);
            assertEquals(1, plain.calls.get()); assertEquals(1, pdf.requests.size());
            assertTrue(restored.document().pages().get(0).warning().contains("[LAYOUT_PDF_TEXT_RECOVERY]"));
            assertEquals("UNAVAILABLE", restored.document().pages().get(0).layout().status());
        } finally { structured.close(); }
    }

    @Test void generatedPlainPdfRecoveryIsRejectedBeforePersistenceAndDoesNotClaimTextCoverage() throws Exception {
        Path source = pdf("plain-generated.pdf", 1, cn.bidcheck.compat.Java8Api.setOf());
        PlainClient plain = new PlainClient("plain-v1", GENERATED); cn.bidcheck.ocr.StructuredOcrClient structured = structured(plain);
        Journal journal = new Journal();
        try {
            cn.bidcheck.document.ExtractionResult result = new FileParser(structured, missingPdf(), 10, 4)
                    .parsePlanned(source, "plain-generated.pdf", null, ExtractionPlan.full(), journal);
            assertEquals(1, plain.calls.get()); assertEquals(0, journal.successes().size());
            assertEquals("", result.document().pages().get(0).text());
            assertTrue(result.document().pages().get(0).warning().contains("OCR_GENERATED_META_NARRATIVE"));
            assertFalse(result.document().pages().get(0).warning().contains("LAYOUT_PDF_TEXT_RECOVERY"));
            assertEquals(false, result.report().get("ocrCoverageComplete"));
            assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "code", "SINGLE_OCR_GENERATED_CONTENT")),
                    ((Map<?, ?>) result.report().get("pdfOcrScheduler")).get("qualityRejectedPages"));
        } finally { structured.close(); }
    }

    @Test void actualRecoveryIdentityIsolatesJournalAndFallbackBatchEvenIfWrapperIdentityIsUnchanged() throws Exception {
        Path source = pdf("recovery-identity.pdf", 1, cn.bidcheck.compat.Java8Api.setOf());
        PlainClient first = new PlainClient("plain-v1", "first source transcript");
        PlainClient second = new PlainClient("plain-v2", "second source transcript");
        PdfClient pdf = missingPdf(); Journal journal = new Journal();
        cn.bidcheck.document.FileParser one = new FileParser(recoveryWrapper(first), pdf, 10, 4);
        cn.bidcheck.document.FileParser two = new FileParser(recoveryWrapper(second), pdf, 10, 4);
        one.parsePlanned(source, "recovery-identity.pdf", null, ExtractionPlan.full(), journal);
        String originalUnit = journal.successes().get(0).imageHash();
        String originalBatch = journal.batches.stream().filter(e -> !e.parentId().isEmpty()).findFirst().orElseThrow(java.util.NoSuchElementException::new).inputHash();
        int boundary = journal.batches.size();
        cn.bidcheck.document.ExtractionResult changed = two.parsePlanned(source, "recovery-identity.pdf", null, ExtractionPlan.full(), journal);
        assertEquals("second source transcript", changed.document().pages().get(0).text());
        assertEquals(1, first.calls.get()); assertEquals(1, second.calls.get()); assertEquals(2, pdf.requests.size());
        assertNotEquals(originalUnit, journal.successes().get(1).imageHash());
        assertNotEquals(originalBatch, journal.batches.subList(boundary, journal.batches.size()).stream()
                .filter(e -> !e.parentId().isEmpty()).findFirst().orElseThrow(java.util.NoSuchElementException::new).inputHash());
        assertEquals(2, journal.saved.size(), "Prior valid receipts remain stored under their original identities");
        two.parsePlanned(source, "recovery-identity.pdf", null, ExtractionPlan.full(), journal);
        assertEquals(1, second.calls.get()); assertEquals(2, pdf.requests.size());
    }

    @Test void legacyAdvancedPageReceiptCannotMasqueradeAsNewPlainRecoveryReceipt() throws Exception {
        Path source = pdf("legacy-receipt.pdf", 1, cn.bidcheck.compat.Java8Api.setOf()); PdfClient pdf = missingPdf();
        PlainClient plain = new PlainClient("plain-v1", "new ordinary transcript");
        OcrClient wrapper = recoveryWrapper(plain); Journal journal = new Journal();
        String legacyHash = hash(("PDF_PAGE_V1|" + hash(Files.readAllBytes(source)) + "|1|" + pdf.identity()
                + "|fallback:" + wrapper.identity()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        cn.bidcheck.document.OcrUnitObserver.Result prior = new OcrUnitObserver.Result("historical advanced transcript", "[OCR已识别]", OcrLayout.unavailable("old layout gap"));
        journal.saved.put("1:" + legacyHash, prior);
        cn.bidcheck.document.ExtractionResult result = new FileParser(wrapper, pdf, 10, 4).parsePlanned(source, "legacy-receipt.pdf", null, ExtractionPlan.full(), journal);
        assertEquals("new ordinary transcript", result.document().pages().get(0).text());
        assertEquals(1, plain.calls.get()); assertSame(prior, journal.saved.get("1:" + legacyHash));
        assertEquals(2, journal.saved.size());
    }

    @Test void plainRecoveryRetainsPhysicalTableRouteWithoutTableWordsOrMarkup() throws Exception {
        Path blank = pdf("grid-original.pdf", 1, cn.bidcheck.compat.Java8Api.setOf()), source = temporary.resolve("grid-recovery.pdf");
        try (PDDocument document = Loader.loadPDF(blank.toFile())) {
            try (org.apache.pdfbox.pdmodel.PDPageContentStream stream = new PDPageContentStream(document, document.getPage(0))) {
                stream.setLineWidth(2);
                for (int coordinate : cn.bidcheck.compat.Java8Api.listOf(10, 50, 90)) {
                    stream.moveTo(5, coordinate); stream.lineTo(96, coordinate);
                    stream.moveTo(coordinate, 5); stream.lineTo(coordinate, 95);
                }
                stream.stroke();
            }
            document.save(source.toFile());
        }
        cn.bidcheck.document.ExtractionResult result = new FileParser(recoveryWrapper(new PlainClient("plain-v1", "Alpha Beta Gamma")), missingPdf(), 10, 4)
                .parsePlanned(source, "grid-recovery.pdf", null, ExtractionPlan.full());
        java.util.Map<?, ?> position = (Map<?, ?>) ((List<?>) result.report().get("selectedPositions")).get(0);
        assertEquals("TABLE_PAGE", position.get("pageType"));
        assertEquals(true, position.get("independent"));
        assertEquals("Alpha Beta Gamma", result.document().pages().get(0).text());
        assertTrue(result.document().pages().get(0).warning().contains("LAYOUT_PDF_TEXT_RECOVERY"));
    }

    @Test void pureNativePagesTraverseWithoutCloud() throws Exception {
        Path source = pdf("native.pdf", 3, cn.bidcheck.compat.Java8Api.setOf(1, 3));
        PdfClient client = new PdfClient((bytes, pages) -> success(pages));
        cn.bidcheck.document.ExtractionResult result = new FileParser(new SingleClient(), client, 10, 4).parsePlanned(source, "native.pdf", null, ExtractionPlan.full());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(2)), client.requests);
        assertEquals("PDFBOX_NATIVE", result.document().pages().get(0).parser());
        assertEquals(2L, ((Map<?, ?>) result.report().get("pdfOcrScheduler")).get("nativeOnlyPages"));
        assertEquals(3, result.document().pages().size());
    }

    @Test void mixedPageKeepsPdfFigureTextWithoutDuplicatingTheSameNativeLine() throws Exception {
        Path source = pdf("mixed.pdf", 1, cn.bidcheck.compat.Java8Api.setOf(1));
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            java.awt.image.BufferedImage bitmap = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject embedded = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, bitmap);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream stream = new PDPageContentStream(document, document.getPage(0), PDPageContentStream.AppendMode.APPEND, true)) {
                stream.drawImage(embedded, 0, 0, 10, 10);
            }
            bitmap.flush(); document.save(temporary.resolve("mixed-ready.pdf").toFile());
        }
        PdfClient client = new PdfClient((bytes, pages) -> new PdfOcrClient.BatchResult(cn.bidcheck.compat.Java8Api.mapOf(1, text("Native body page 1\nfigure OCR text")), cn.bidcheck.compat.Java8Api.mapOf(), cn.bidcheck.compat.Java8Api.mapOf()));
        cn.bidcheck.domain.Domain.ParsedDocument result = new FileParser(new SingleClient(), client, 10, 4).parse(temporary.resolve("mixed-ready.pdf"), "mixed.pdf");
        assertEquals("Native body page 1\nfigure OCR text", result.pages().get(0).text());
    }

    @Test void byteBackpressureReducesActiveBatchesAndOversizePacketsSplitWithoutLosingPages() throws Exception {
        Path source = pdf("bytes.pdf", 31, cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client = new PdfClient((bytes, pages) -> {
            assertTrue(bytes.length <= 2_000);
            try { Thread.sleep(25); } catch (InterruptedException stopped) { throw new InterruptedIOException(); }
            return success(pages);
        });
        cn.bidcheck.document.ExtractionResult result = new FileParser(new SingleClient(), client, 100, 4, 100, 6_000, 2_000)
                .parsePlanned(source, "bytes.pdf", null, ExtractionPlan.full());
        java.util.Map<?, ?> metrics = (Map<?, ?>) result.report().get("pdfOcrScheduler");
        assertTrue(((Long) metrics.get("peakActiveBytes")).longValue() <= 6_000);
        assertTrue(((Integer) metrics.get("peakConcurrency")).intValue() < 5);
        assertEquals(31, result.document().pages().size()); assertEquals(true, result.report().get("ocrCoverageComplete"));
        assertEquals(31, client.requests.stream().flatMap(List::stream).distinct().count());
    }

    @Test void continuationDoesNotSpendNewPageBudgetOnRestoredPdfSuccesses() throws Exception {
        Path source = pdf("resume.pdf", 5, cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client = new PdfClient((bytes, pages) -> success(pages));
        Journal journal = new Journal(); FileParser parser = new FileParser(new SingleClient(), client, 2, 2);
        cn.bidcheck.document.ExtractionResult first = parser.parsePlanned(source, "resume.pdf", null, ExtractionPlan.full(), journal);
        assertEquals(3, first.report().get("deferredImageCount"));
        parser.parsePlanned(source, "resume.pdf", null, ExtractionPlan.full(), journal);
        cn.bidcheck.document.ExtractionResult third = parser.parsePlanned(source, "resume.pdf", null, ExtractionPlan.full(), journal);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2), cn.bidcheck.compat.Java8Api.listOf(3, 4), cn.bidcheck.compat.Java8Api.listOf(5)), client.requests);
        assertEquals(true, third.report().get("ocrCoverageComplete"));
        assertEquals(4, ((Map<?, ?>) third.report().get("pdfOcrScheduler")).get("restoredPages"));
        assertTrue(journal.successes().stream().allMatch(e -> e.width() > 0 && e.height() > 0 && e.imageHash().matches("[0-9a-f]{64}")));
    }

    @Test void unexpectedSourcePageCannotBeAttachedToRequestedPage() throws Exception {
        Path source = pdf("wrong.pdf", 2, cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client = new PdfClient((bytes, pages) -> new PdfOcrClient.BatchResult(cn.bidcheck.compat.Java8Api.mapOf(1, text("incorrect batch"), 999, text("foreign page")), cn.bidcheck.compat.Java8Api.mapOf(), cn.bidcheck.compat.Java8Api.mapOf()));
        SingleClient single = new SingleClient();
        cn.bidcheck.domain.Domain.ParsedDocument result = new FileParser(single, client, 10, 2).parse(source, "wrong.pdf");
        assertEquals(2, single.calls.get());
        assertTrue(result.pages().stream().allMatch(p -> p.text().contains("single image body")));
    }

    @Test void durableRunningFenceFailsBeforeAnyCloudRequest() throws Exception {
        Path source = pdf("fence.pdf", 12, cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client = new PdfClient((bytes, pages) -> success(pages));
        IOException lost = new IOException("journal fence failed");
        OcrUnitObserver observer = new OcrUnitObserver() { public void onState(Event e) throws IOException { if (e.status().equals("RUNNING")) throw lost; } };
        assertSame(lost, assertThrows(IOException.class, () -> new FileParser(new SingleClient(), client, 100, 4)
                .parsePlanned(source, "fence.pdf", null, ExtractionPlan.full(), observer)));
        assertEquals(0, client.requests.size());
    }

    @Test void durableCompletionFailureCancelsOtherRequestsAndNeverStartsFallback() throws Exception {
        Path source = pdf("cancel.pdf", 60, cn.bidcheck.compat.Java8Api.setOf());
        CountDownLatch started = new CountDownLatch(5), cancellable = new CountDownLatch(4), cancelled = new CountDownLatch(4);
        PdfClient client = new PdfClient((bytes, pages) -> {
            started.countDown(); await(started);
            if (pages.get(0) == 1) { await(cancellable); return success(pages); }
            // Count cancellation only after every peer has entered the interruption handler.
            try { cancellable.countDown(); new CountDownLatch(1).await(30, TimeUnit.SECONDS); throw new AssertionError("request was not cancelled"); }
            catch (InterruptedException stopped) { cancelled.countDown(); Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
        });
        SingleClient single = new SingleClient(); IOException lost = new IOException("save failed");
        OcrUnitObserver observer = new OcrUnitObserver() { public void onState(Event e) throws IOException { if (e.status().equals("SUCCEEDED")) throw lost; } };
        assertSame(lost, assertThrows(IOException.class, () -> new FileParser(single, client, 100, 4)
                .parsePlanned(source, "cancel.pdf", null, ExtractionPlan.full(), observer)));
        assertTrue(cancelled.await(5, TimeUnit.SECONDS)); assertEquals(5, client.requests.size()); assertEquals(0, single.calls.get());
    }

    @Test void cancelledPdfRequestNeverTurnsIntoRecovery() throws Exception {
        Path source = pdf("interrupted.pdf", 1, cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client = new PdfClient((bytes, pages) -> { throw new InterruptedIOException("cancelled"); });
        SingleClient single = new SingleClient();
        assertThrows(InterruptedIOException.class, () -> new FileParser(single, client, 10, 4).parse(source, "interrupted.pdf"));
        assertEquals(0, single.calls.get());
    }

    @Test void authenticationRateLimitAndServiceErrorsDoNotAmplifyIntoPerPageCallsOrLaterPackets() throws Exception {
        Path source = pdf("service-error.pdf", 60, cn.bidcheck.compat.Java8Api.setOf());
        for (int status : cn.bidcheck.compat.Java8Api.listOf(401, 403, 429, 503)) {
            PdfClient client = new PdfClient((bytes, pages) -> { throw new ServiceFailure(status); });
            SingleClient single = new SingleClient(); Journal journal = new Journal();
            cn.bidcheck.document.ExtractionResult result = new FileParser(single, client, 100, 4).parsePlanned(source, "service-error.pdf", null, ExtractionPlan.full(), journal);
            assertEquals(0, single.calls.get()); assertTrue(client.requests.size() <= 5);
            assertEquals(60, result.report().get("deferredImageCount"));
            assertTrue(result.document().pages().stream().allMatch(p -> p.warning().contains("HTTP_" + status)));
            assertTrue(journal.batches.stream().filter(e -> e.status().equals("FAILED")).allMatch(e -> e.errorCode().equals("HTTP_" + status)));
        }
    }
    @Test void oneTransientTransportFailureRecoversOnlyItsBatchAndDoesNotDeferLaterPages() throws Exception {
        Path source=pdf("transient-packet.pdf",60,cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client=new PdfClient((bytes,pages)->{
            if(pages.contains(1))throw new TransientFailure();
            return success(pages);
        });
        SingleClient single=new SingleClient();Journal journal=new Journal();
        cn.bidcheck.document.ExtractionResult result=new FileParser(single,client,100,4).parsePlanned(source,"transient-packet.pdf",null,ExtractionPlan.full(),journal);
        assertEquals(6,client.requests.size());assertEquals(10,single.calls.get());
        assertEquals(true,result.report().get("ocrCoverageComplete"));
        assertEquals(60,journal.successes().size());
        assertEquals(0,result.report().get("deferredImageCount"));
        assertTrue(result.document().pages().get(59).text().contains("source 60"));
    }
    @Test void failedSinglePageGetsOneBoundedTransientRepairWithoutRepeatingGoodPdfPages() throws Exception {
        Path source=pdf("single-transient.pdf",2,cn.bidcheck.compat.Java8Api.setOf());AtomicInteger calls=new AtomicInteger();
        PdfClient client=new PdfClient((bytes,pages)->new PdfOcrClient.BatchResult(cn.bidcheck.compat.Java8Api.mapOf(1, text("good first source")),cn.bidcheck.compat.Java8Api.mapOf(2, "missing"),cn.bidcheck.compat.Java8Api.mapOf()));
        OcrClient single=new OcrClient(){
            public boolean enabled(){return true;}public String identity(){return "retry-single";}public String modelName(){return identity();}
            public OcrResult recognize(byte[] bytes,String mime)throws IOException{if(calls.incrementAndGet()==1)throw new TransientFailure();return text("recovered second source");}
        };
        cn.bidcheck.document.ExtractionResult result=new FileParser(single,client,10,2).parsePlanned(source,"single-transient.pdf",null,ExtractionPlan.full());
        assertEquals(2,calls.get());assertEquals(1,client.requests.size());
        assertEquals("good first source",result.document().pages().get(0).text());
        assertTrue(result.document().pages().get(1).text().contains("recovered second source"));
        assertEquals(1,((Map<?,?>)result.report().get("pdfOcrScheduler")).get("singlePageRetryCalls"));
    }

    @Test void queueTimeoutRecoversOnlyItsPacketAndContinuesLaterPackets() throws Exception {
        Path source=pdf("queue-packet.pdf",60,cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client=new PdfClient((bytes,pages)->{if(pages.contains(1))throw new QueueTimeout();return success(pages);});
        SingleClient single=new SingleClient();Journal journal=new Journal();
        cn.bidcheck.document.ExtractionResult result=new FileParser(single,client,100,4).parsePlanned(source,"queue-packet.pdf",null,ExtractionPlan.full(),journal);
        assertEquals(6,client.requests.size());assertEquals(10,single.calls.get());
        assertEquals(60,journal.successes().size());assertEquals(0,result.report().get("deferredImageCount"));
        assertEquals(true,result.report().get("ocrCoverageComplete"));
        assertTrue(result.document().pages().get(59).text().contains("source 60"));
        assertTrue(journal.batches.stream().anyMatch(e->e.status().equals("RECOVERED")));
    }

    @Test void repeatedSinglePageQueueTimeoutStopsAfterOneRepairWithoutDeferringOtherPages() throws Exception {
        Path source=pdf("queue-single.pdf",12,cn.bidcheck.compat.Java8Api.setOf());AtomicInteger calls=new AtomicInteger();Journal journal=new Journal();
        PdfClient client=new PdfClient((bytes,pages)->{
            java.util.HashMap<java.lang.Integer, cn.bidcheck.ocr.OcrResult> good=new HashMap<>(success(pages).pages());good.remove(1);
            return new PdfOcrClient.BatchResult(good,pages.contains(1)?cn.bidcheck.compat.Java8Api.mapOf(1, "missing"):cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.mapOf());
        });
        OcrClient single=new OcrClient(){
            public boolean enabled(){return true;}public String identity(){return "queue-single";}public String modelName(){return identity();}
            public OcrResult recognize(byte[] bytes,String mime)throws IOException{calls.incrementAndGet();throw new QueueTimeout();}
        };
        cn.bidcheck.document.ExtractionResult result=new FileParser(single,client,100,4).parsePlanned(source,"queue-single.pdf",null,ExtractionPlan.full(),journal);
        assertEquals(2,calls.get());assertEquals(2,client.requests.size());assertEquals(11,journal.successes().size());
        assertEquals(1,((Map<?,?>)result.report().get("pdfOcrScheduler")).get("singlePageRetryCalls"));
        assertEquals(0,result.report().get("deferredImageCount"));assertEquals(false,result.report().get("ocrCoverageComplete"));
        assertTrue(result.document().pages().get(0).warning().contains("[OCR未完成]"));
        assertTrue(result.document().pages().get(11).text().contains("source 12"));
    }

    @Test void serviceFailureKeepsSuccessfulInFlightPagesAndDefersOnlyUnfinishedWork() throws Exception {
        Path source = pdf("partial-service.pdf", 60, cn.bidcheck.compat.Java8Api.setOf());
        CountDownLatch started = new CountDownLatch(5);
        PdfClient client = new PdfClient((bytes, pages) -> {
            started.countDown(); await(started);
            if (pages.get(0) == 1) throw new ServiceFailure(503);
            return success(pages);
        });
        SingleClient single = new SingleClient();
        cn.bidcheck.document.ExtractionResult result = new FileParser(single, client, 100, 4).parsePlanned(source, "partial-service.pdf", null, ExtractionPlan.full());
        assertEquals(0, single.calls.get());
        assertTrue((long) result.report().get("recognizedImageCount") >= 40);
        assertTrue(result.document().pages().get(0).warning().contains("HTTP_503"));
        assertTrue(result.document().pages().get(10).text().contains("source 11"));
    }

    @Test void nativeOnlyPlanAndChangedSourceHashNeverSendAnyPdf() throws Exception {
        Path source = pdf("none.pdf", 5, cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client = new PdfClient((bytes, pages) -> success(pages));
        FileParser parser = new FileParser(new SingleClient(), client, 100, 4);
        cn.bidcheck.document.ExtractionResult result = parser.parsePlanned(source, "none.pdf", null, ExtractionPlan.nativeOnly());
        assertEquals(5, result.document().pages().size());
        assertThrows(IOException.class, () -> parser.parsePlanned(source, "none.pdf", null,
                ExtractionPlan.selected(cn.bidcheck.compat.Java8Api.repeat("0", 64), cn.bidcheck.compat.Java8Api.listOf(OcrSelection.position("PAGE", 1, "chosen")), 1, "chosen")));
        assertEquals(0, client.requests.size());
    }

    @Test void generatedPdfNarrativeRecoversOnlyItsOriginalPageAndReportsSafeReason() throws Exception {
        Path source = pdf("generated.pdf", 2, cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client = new PdfClient((bytes, pages) -> new PdfOcrClient.BatchResult(cn.bidcheck.compat.Java8Api.mapOf(1, text(GENERATED), 2, text("safe source text")), cn.bidcheck.compat.Java8Api.mapOf(), cn.bidcheck.compat.Java8Api.mapOf()));
        SingleClient single = new SingleClient();
        cn.bidcheck.document.ExtractionResult result = new FileParser(single, client, 10, 4).parsePlanned(source, "generated.pdf", null, ExtractionPlan.full());
        assertEquals(1, single.calls.get());
        assertEquals("single image body", result.document().pages().get(0).text());
        assertEquals("safe source text", result.document().pages().get(1).text());
        assertTrue(result.document().pages().get(0).warning().contains("OCR_GENERATED_META_NARRATIVE"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "code", "OCR_GENERATED_META_NARRATIVE")), ((Map<?, ?>) result.report().get("pdfOcrScheduler")).get("qualityRejectedPages"));
        assertEquals(true, result.report().get("ocrCoverageComplete"));
    }

    @Test void generatedSingleRecoveryFailsWholePageInsteadOfDeletingTheNarrativeAndClaimingSuccess() throws Exception {
        Path source = pdf("bad-recovery.pdf", 1, cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client = new PdfClient((bytes, pages) -> new PdfOcrClient.BatchResult(cn.bidcheck.compat.Java8Api.mapOf(1, text(GENERATED)), cn.bidcheck.compat.Java8Api.mapOf(), cn.bidcheck.compat.Java8Api.mapOf()));
        SingleClient single = new SingleClient(GENERATED); Journal journal = new Journal();
        cn.bidcheck.document.ExtractionResult result = new FileParser(single, client, 10, 4).parsePlanned(source, "bad-recovery.pdf", null, ExtractionPlan.full(), journal);
        assertEquals(1, single.calls.get()); assertEquals("", result.document().pages().get(0).text());
        assertEquals(false, result.report().get("ocrCoverageComplete"));
        assertTrue(result.document().pages().get(0).warning().contains("OCR_GENERATED_META_NARRATIVE"));
        assertEquals(0, journal.successes().size());
    }

    @Test void generatedPersistedResultCannotBypassTheEvidenceGuard() throws Exception {
        Path source = pdf("bad-cache.pdf", 1, cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client = new PdfClient((bytes, pages) -> success(pages));
        OcrUnitObserver observer = new OcrUnitObserver() {
            public Optional<Result> restore(String type, int page, int index, String hash) {
                return Optional.of(new Result(GENERATED, "[OCR已识别]", OcrLayout.unavailable("no geometry")));
            }
        };
        cn.bidcheck.document.ExtractionResult result = new FileParser(new SingleClient(), client, 10, 4).parsePlanned(source, "bad-cache.pdf", null, ExtractionPlan.full(), observer);
        assertEquals(1, client.requests.size()); assertEquals("PDF body source 1", result.document().pages().get(0).text());
    }

    @Test void generatedSparseCropFailsThePageAndStopsFurtherCropCalls() throws Exception {
        Path source = pdf("bad-crop.pdf", 1, cn.bidcheck.compat.Java8Api.setOf());
        PdfClient client = new PdfClient((bytes, pages) -> new PdfOcrClient.BatchResult(cn.bidcheck.compat.Java8Api.mapOf(), cn.bidcheck.compat.Java8Api.mapOf(1, "missing"), cn.bidcheck.compat.Java8Api.mapOf()));
        AtomicInteger calls = new AtomicInteger();
        OcrClient single = new OcrClient() {
            public boolean enabled() { return true; } public String modelName() { return "sparse-stub"; } public String identity() { return modelName(); }
            public OcrResult recognize(byte[] bytes, String mime) { return text(calls.incrementAndGet() == 1 ? "1" : GENERATED); }
        };
        cn.bidcheck.document.ExtractionResult result = new FileParser(single, client, 10, 4).parsePlanned(source, "bad-crop.pdf", null, ExtractionPlan.full());
        assertEquals(2, calls.get()); assertEquals("", result.document().pages().get(0).text());
        assertEquals(false, result.report().get("ocrCoverageComplete"));
        assertEquals(2, ((Map<?, ?>) result.report().get("pdfOcrScheduler")).get("singlePageFallbackCalls"));
    }

    @FunctionalInterface interface PdfBehavior { PdfOcrClient.BatchResult call(byte[] bytes, List<Integer> pages) throws IOException; }
    private static PdfClient missingPdf() {
        return new PdfClient((bytes, pages) -> new PdfOcrClient.BatchResult(cn.bidcheck.compat.Java8Api.mapOf(), cn.bidcheck.compat.Java8Api.mapOf(), cn.bidcheck.compat.Java8Api.mapOf()));
    }
    private static OcrClient recoveryWrapper(OcrClient plain) {
        return new OcrClient() {
            public boolean enabled() { return true; } public String identity() { return "unchanged-structured-wrapper"; }
            public String modelName() { return "advanced-wrapper"; }
            public OcrClient pdfRecoveryClient() { return plain; }
            public OcrResult recognize(byte[] bytes, String mime) { throw new AssertionError("PDF fallback must use the recovery client"); }
        };
    }
    private StructuredOcrClient structured(OcrClient plain) throws IOException {
        return new StructuredOcrClient(plain, recoveryWrapper(plain),
                new PixelGridClient(true, temporary.resolve("models").toString(), temporary.toString(), 1, 1));
    }
    static final class PlainClient implements OcrClient {
        final AtomicInteger calls = new AtomicInteger(); final String id, response;
        PlainClient(String id, String response) { this.id = id; this.response = response; }
        public boolean enabled() { return true; } public String identity() { return id; } public String modelName() { return id; }
        public OcrResult recognize(byte[] bytes, String mime) { calls.incrementAndGet(); return new OcrResult(response, "offline", id, false); }
    }
    static final class ServiceFailure extends OcrFailure {
        ServiceFailure(int status) { super("safe service failure", "HTTP_" + status, status, status == 429 || status >= 500); }
    }
    static final class TransientFailure extends OcrFailure {
        TransientFailure(){super("safe transient transport","PDF_TRANSPORT_ERROR",null,true);}
    }
    static final class QueueTimeout extends OcrFailure {
        QueueTimeout(){super("safe admission timeout","QUEUE_TIMEOUT",null,true);}
    }
    static final class PdfClient implements PdfOcrClient {
        final PdfBehavior behavior; final List<List<Integer>> requests = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        PdfClient(PdfBehavior behavior) { this.behavior = behavior; }
        public boolean enabled() { return true; } public String identity() { return "pdf-offline-1"; } public String modelName() { return "qwen3.5-ocr-stub"; }
        public BatchResult recognizePdf(byte[] bytes, List<Integer> pages) throws IOException {
            requests.add(cn.bidcheck.compat.Java8Api.listCopyOf(pages)); peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try (PDDocument packet = Loader.loadPDF(bytes)) {
                assertEquals(pages.size(), packet.getNumberOfPages()); assertTrue(pages.size() <= 10);
                for (int i = 0; i < pages.size(); i++) assertEquals(pages.get(i).intValue(), packet.getPage(i).getCOSObject().getInt(COSName.getPDFName("OriginalTestPage")));
                return behavior.call(bytes, pages);
            } finally { active.decrementAndGet(); }
        }
    }
    static final class SingleClient implements OcrClient {
        final AtomicInteger calls = new AtomicInteger();
        final String responseText;
        SingleClient() { this("single image body"); }
        SingleClient(String text) { this.responseText = text; }
        public boolean enabled() { return true; } public String identity() { return "single-offline-1"; } public String modelName() { return "single-stub"; }
        public OcrResult recognize(byte[] bytes, String mime) throws IOException {
            calls.incrementAndGet(); java.awt.image.BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            String text = responseText;
            cn.bidcheck.ocr.OcrLayout layout = new OcrLayout(OcrLayout.VERSION, "COMPLETE", OcrLayout.COORDINATES,
                    cn.bidcheck.compat.Java8Api.listOf(new OcrLayout.Frame("image", image.getWidth(), image.getHeight(), hash(bytes), "SUBMITTED_IMAGE", 0, null, null, null)),
                    cn.bidcheck.compat.Java8Api.listOf(new OcrLayout.TextBox("text", "image", text, new OcrLayout.Box(1, 1, 10, 10), null)), cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf("text"), cn.bidcheck.compat.Java8Api.listOf());
            image.flush(); return new OcrResult(text, "stub", modelName(), false, layout);
        }
        public void recognizeBatch(List<OcrBatch.Input> inputs, OcrBatch.Listener listener) { fail("PDF fallback must never call the image-batch API"); }
    }
    static final class Journal implements OcrUnitObserver {
        final Thread caller = Thread.currentThread(); final List<Event> events = new ArrayList<>(); final Map<String, Result> saved = new HashMap<>();
        final List<OcrBatch.Event> batches = new ArrayList<>();
        public Optional<Result> restore(String type, int page, int index, String hash) { assertSame(caller, Thread.currentThread()); return Optional.ofNullable(saved.get(page + ":" + hash)); }
        public void onState(Event event) {
            assertSame(caller, Thread.currentThread()); events.add(event);
            if (event.status().equals("SUCCEEDED")) saved.put(event.position() + ":" + event.imageHash(), new Result(event.text(), event.warning(), event.layout()));
        }
        public void onBatch(OcrBatch.Event event) { assertSame(caller, Thread.currentThread()); assertTrue(event.inputIds().size() <= 10); batches.add(event); }
        List<Event> successes() { return cn.bidcheck.compat.Java8Api.toList(events.stream().filter(e -> e.status().equals("SUCCEEDED"))); }
    }
    static PdfOcrClient.BatchResult success(List<Integer> pages) {
        Map<Integer, OcrResult> result = new LinkedHashMap<>(); for (int p : pages) result.put(p, text("PDF body source " + p));
        return new PdfOcrClient.BatchResult(result, cn.bidcheck.compat.Java8Api.mapOf(), cn.bidcheck.compat.Java8Api.mapOf());
    }
    static OcrResult text(String text) { return new OcrResult(text, "stub", "pdf-model", false); }
    static String hash(byte[] value) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception failure) { throw new IOException(failure); }
    }
    static void await(CountDownLatch latch) throws IOException {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
    }
    Path pdf(String name, int count, Set<Integer> nativePages) throws IOException {
        Path path = temporary.resolve(name);
        try (PDDocument document = new PDDocument()) {
            for (int n = 1; n <= count; n++) {
                PDPage page = new PDPage(new PDRectangle(100 + n % 7, 100)); document.addPage(page);
                page.getCOSObject().setInt(COSName.getPDFName("OriginalTestPage"), n);
                if (nativePages.contains(n)) try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    stream.newLineAtOffset(5, 60); stream.showText("Native body page " + n); stream.endText();
                }
            }
            document.save(path.toFile());
        }
        return path;
    }
}
