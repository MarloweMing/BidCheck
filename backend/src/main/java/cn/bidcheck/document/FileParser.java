package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.ParsedDocument;
import cn.bidcheck.ocr.OcrClient;
import cn.bidcheck.ocr.OcrBatch;
import cn.bidcheck.ocr.OcrBatchPolicy;
import cn.bidcheck.ocr.PdfOcrClient;
import cn.bidcheck.ocr.OcrEvidenceGuard;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Source-located native text and optional OCR. Word locations never claim rendered page numbers. */
public class FileParser {
    public static final String POLICY_VERSION = "source-located-pdf-batch-8-pdf-text-recovery";
    public static final String PDF_RECOVERY_POLICY = "pdf-transcription-recovery-1";
    public static final int MAX_PAGES = 500;
    public static final int MAX_PDF_PAGES = 1_000;
    public static final int MAX_CHARACTERS = 2_000_000;
    public static final long MAX_INPUT_BYTES = DocumentLimits.MAX_FILE_BYTES;
    private static final int MAX_WORD_BLOCKS = 20_000;
    private static final long MAX_ZIP_ENTRY_BYTES = 32L * 1024 * 1024;
    private static final long MAX_ZIP_TOTAL_BYTES = 80L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 2_000;
    private static final int MAX_PDF_GLYPHS_PER_PAGE = 50_000;
    private static final int MAX_PDF_OPERATORS_PER_PAGE = 200_000;
    private static final long MAX_PDF_STREAM_BYTES = 8L * 1024 * 1024;
    private static final long MAX_PDF_TOTAL_STREAM_BYTES = 64L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 16_000_000L;
    private static final long MAX_PDF_JPEG_PIXELS = 24_000_000L;
    private static final int MAX_OCR_IMAGES = 100;
    private static final String OCR_SUCCESS_WARNING = "识别文字可能有误，请对照原图核实；签字盖章未核验";
    private final OcrClient ocr;
    private final int maxOcrImages;
    private final int ocrConcurrency;
    private final long ocrBytesBudget;
    private final long ocrPixelsBudget;
    private final PdfOcrClient pdfOcr;
    private final int maxPdfOcrPages;
    private final long pdfBytesBudget, pdfBatchBytes;

    public FileParser() { this(OcrClient.disabled()); }
    public FileParser(OcrClient ocr) { this(ocr, MAX_OCR_IMAGES); }
    public FileParser(OcrClient ocr, int maxOcrImages) { this(ocr, maxOcrImages, 2); }
    public FileParser(OcrClient ocr, int maxOcrImages, int concurrency) {
        this(ocr, maxOcrImages, concurrency, batchPolicy(ocr).enabled() ? 64L * 1024 * 1024 : 24L * 1024 * 1024,
                batchPolicy(ocr).enabled() ? 64_000_000L : 12_000_000L);
    }
    private static OcrBatchPolicy batchPolicy(OcrClient client) {
        return Objects.requireNonNullElse(Objects.requireNonNull(client).batchPolicy(), OcrBatchPolicy.single());
    }
    FileParser(OcrClient ocr, int maxOcrImages, int concurrency, long bytesBudget, long pixelsBudget) {
        this(ocr, PdfOcrClient.disabled(), maxOcrImages, concurrency, bytesBudget, pixelsBudget,
                maxOcrImages, 64L * 1024 * 1024, 16L * 1024 * 1024);
    }
    public FileParser(OcrClient ocr, PdfOcrClient pdfOcr, int maxOcrImages, int concurrency) {
        this(ocr, pdfOcr, maxOcrImages, concurrency, maxOcrImages, 64L * 1024 * 1024, 16L * 1024 * 1024);
    }
    public FileParser(OcrClient ocr, PdfOcrClient pdfOcr, int maxOcrImages, int concurrency,
                      int maxPdfOcrPages, long pdfBytesBudget, long pdfBatchBytes) {
        this(ocr, pdfOcr, maxOcrImages, concurrency,
                batchPolicy(ocr).enabled() ? 64L * 1024 * 1024 : 24L * 1024 * 1024,
                batchPolicy(ocr).enabled() ? 64_000_000L : 12_000_000L,
                maxPdfOcrPages, pdfBytesBudget, pdfBatchBytes);
    }
    private FileParser(OcrClient ocr, PdfOcrClient pdfOcr, int maxOcrImages, int concurrency,
                       long bytesBudget, long pixelsBudget, int maxPdfOcrPages, long pdfBytesBudget, long pdfBatchBytes) {
        this.ocr = Objects.requireNonNull(ocr);
        this.pdfOcr = Objects.requireNonNull(pdfOcr);
        if (maxPdfOcrPages < 1 || maxPdfOcrPages > MAX_PDF_PAGES || pdfBytesBudget < 2 || pdfBatchBytes < 1
                || pdfBatchBytes > pdfBytesBudget / 3 || pdfBatchBytes > 100_000_000L)
            throw new IllegalArgumentException("PDF OCR 页数或并行字节预算无效。");
        this.maxPdfOcrPages = maxPdfOcrPages; this.pdfBytesBudget = pdfBytesBudget; this.pdfBatchBytes = pdfBatchBytes;
        if (maxOcrImages < 1 || maxOcrImages > MAX_PDF_PAGES) throw new IllegalArgumentException("OCR 图片预算必须在 1 至 1000 之间。");
        if (concurrency < 1 || concurrency > 8) throw new IllegalArgumentException("OCR 并发数必须在 1 至 8 之间。");
        this.maxOcrImages = maxOcrImages;
        this.ocrConcurrency = concurrency;
        if (bytesBudget < 1 || pixelsBudget < 1) throw new IllegalArgumentException("OCR 内存预算必须大于零。");
        this.ocrBytesBudget = bytesBudget;
        this.ocrPixelsBudget = pixelsBudget;
    }

    @FunctionalInterface
    public interface ProgressListener { void update(int percent, String stage); }

    static {
        // Never relax safeguards another application component has already installed.
        ZipSecureFile.setMinInflateRatio(Math.max(0.01d, ZipSecureFile.getMinInflateRatio()));
        ZipSecureFile.setMaxEntrySize(Math.min(MAX_ZIP_ENTRY_BYTES, ZipSecureFile.getMaxEntrySize()));
        ZipSecureFile.setMaxTextSize(Math.min(MAX_CHARACTERS, ZipSecureFile.getMaxTextSize()));
    }

    public ParsedDocument parse(Path path, String originalFilename) throws IOException {
        return parse(path, originalFilename, (percent, stage) -> { });
    }

    public ParsedDocument parse(Path path, String originalFilename, ProgressListener listener) throws IOException {
        return parse(path, originalFilename, listener, ExtractionPlan.full());
    }

    public ParsedDocument parse(Path path, String originalFilename, ProgressListener listener, ExtractionPlan plan) throws IOException {
        return parsePlanned(path, originalFilename, listener, plan).document();
    }

    /** Builds the complete native location map before any selected OCR; never renumbers omitted images. */
    public ExtractionResult parsePlanned(Path path, String originalFilename, ProgressListener listener, ExtractionPlan plan) throws IOException {
        return parsePlanned(path, originalFilename, listener, plan, OcrUnitObserver.noop());
    }

    public ExtractionResult parsePlanned(Path path, String originalFilename, ProgressListener listener, ExtractionPlan plan,
                                         OcrUnitObserver observer) throws IOException {
        validateFile(path);
        Objects.requireNonNull(plan, "plan");
        String sourceHash = fileSha256(path);
        if (!plan.sourceSha256().isEmpty() && !sourceHash.equals(plan.sourceSha256()))
            throw new DocumentParseException("OCR 计划与原文件指纹不一致，请重新生成计划。");
        String filename = originalFilename == null ? "" : originalFilename.toLowerCase(Locale.ROOT);
        boolean pdfMode = filename.endsWith(".pdf") && pdfOcr.enabled();
        ParseSession session = new ParseSession(ocr, listener, pdfMode ? maxPdfOcrPages : maxOcrImages, plan, sourceHash, Objects.requireNonNull(observer));
        try {
            session.progress(0, "正在读取文档");
            ParsedDocument result;
            if (filename.endsWith(".pdf")) result = pdfMode ? parsePdfBatches(path, session) : session.planned() ? parsePdfPlanned(path, session) : parsePdf(path, session);
            else if (filename.endsWith(".docx")) result = parseWord(path, session);
            else if (filename.endsWith(".txt") || filename.endsWith(".md")) result = parseText(path, session);
            else if (filename.endsWith(".png") || filename.endsWith(".jpg") || filename.endsWith(".jpeg")) result = parseImage(path, session);
            else throw new DocumentParseException("暂不支持此文件格式，请上传 PDF、DOCX、PNG、JPG、UTF-8 编码的 TXT 或 MD 文件。");
            session.progress(100, "文档解析完成");
            // A source-bound selection must not silently describe a different on-disk revision.
            if (!sourceHash.equals(fileSha256(path))) throw new DocumentParseException("解析期间原文件发生变化，请重新上传。");
            return new ExtractionResult(result, session.report(result));
        } catch (ProgressAborted e) {
            throw e.original;
        } catch (ObserverAborted e) {
            if (e.original instanceof IOException failure) throw failure;
            throw (RuntimeException) e.original;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (PdfInterrupted e) {
            throw interrupted();
        } catch (DocumentParseException e) {
            throw e;
        } catch (PdfBudgetExceeded e) {
            throw new DocumentParseException("PDF 单页文字或绘制指令过多，无法安全解析，请拆分或重新导出。");
        } catch (InvalidPasswordException | EncryptedDocumentException e) {
            throw new DocumentParseException("文件已加密，请先解除密码保护后重新上传。", e);
        } catch (CharacterCodingException e) {
            throw new DocumentParseException("文本文件不是有效的 UTF-8 编码，请转换为 UTF-8 后重新上传。", e);
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("文件损坏、格式与扩展名不符，或包含无法安全解析的内容，请重新导出后上传。", e);
        }
    }

    /** Renders a real, one-based PDF page. Word files intentionally have no image-page substitute. */
    public byte[] renderPdfPage(Path path, int page) throws IOException {
        validateFile(path);
        try (PDDocument document = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            validatePdf(document);
            if (page < 1 || page > document.getNumberOfPages()) {
                throw new DocumentParseException("PDF 页码超出范围。");
            }
            return renderPdfPage(document, page, false);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (PdfInterrupted e) {
            throw interrupted();
        } catch (DocumentParseException e) {
            throw e;
        } catch (InvalidPasswordException e) {
            throw new DocumentParseException("PDF 已加密，请先解除密码保护。", e);
        } catch (PdfBudgetExceeded e) {
            throw new DocumentParseException("PDF 图像或绘制结构超过安全预览限制，请降低扫描分辨率或重新导出。");
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("PDF 文件损坏或无法渲染，请重新导出后上传。", e);
        }
    }

    private static byte[] renderPdfPage(PDDocument document, int page, boolean forOcr) throws IOException {
        checkInterrupted();
        PDPage sourcePage = document.getPage(page - 1);
        new PdfStreamBudget().checkPage(sourcePage);
        var box = sourcePage.getCropBox();
        double width = box.getWidth(), height = box.getHeight();
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            throw new DocumentParseException("PDF 页面尺寸无效，无法预览。");
        }
        double scale = Math.min(forOcr ? 3d : 1.5d, Math.min(2400d / Math.max(width, height),
                Math.sqrt(4_000_000d / (width * height))));
        PDFRenderer renderer = new SafePdfRenderer(document);
        renderer.setSubsamplingAllowed(true);
        BufferedImage image = renderer.renderImage(page - 1, (float) scale, ImageType.RGB);
        try { return png(image); } finally { image.flush(); }
    }

    private ParsedDocument parsePdf(Path path, ParseSession session) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache());
             OcrPipeline pipeline = new OcrPipeline(session, ocrConcurrency, ocrBytesBudget, ocrPixelsBudget)) {
            validatePdf(document);
            List<Page> pages = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            ArrayDeque<PendingPdfPage> pending = new ArrayDeque<>();
            int remaining = MAX_CHARACTERS;
            PdfStreamBudget budget = new PdfStreamBudget();
            for (int number = 1; number <= document.getNumberOfPages(); number++) {
                pipeline.tick();
                // Backpressure also bounds native pages queued behind a slow OCR request.
                while (pending.size() >= 32) {
                    remaining = appendPdfPage(pending.removeFirst(), pipeline, pages, warnings, remaining, document.getNumberOfPages());
                }
                session.progress((number - 1) * 100 / document.getNumberOfPages(), "正在解析第 " + number + " 页");
                budget.checkPage(document.getPage(number - 1));
                NativePdfStripper stripper = new NativePdfStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(number);
                stripper.setEndPage(number);
                stripper.setLineSeparator("\n");
                stripper.setPageStart("");
                stripper.setPageEnd("");
                BoundedWriter writer = new BoundedWriter(remaining);
                stripper.writeText(document, writer);
                String text = normalize(writer.toString());
                // Reserve native text immediately, including pages whose OCR result has not arrived yet.
                remaining -= text.length();
                session.nativeLocation(number, "PAGE", text, stripper.imageCount, !PageClassifier.usableNativeText(text) || stripper.hasImage ? 1 : 0);
                Future<OcrOutcome> recognition = CompletableFuture.completedFuture(null);
                if (!PageClassifier.usableNativeText(text) || stripper.hasImage) {
                    int page = number;
                    recognition = pipeline.submit("PAGE", number, 1, () -> renderPdfPage(document, page, true), "第 " + number + " 页图像");
                } else session.pageCompleted(completedImagePdfPage(number, text, null));
                pending.addLast(new PendingPdfPage(number, text, recognition));
                while (!pending.isEmpty() && pending.peekFirst().recognition.isDone()) {
                    remaining = appendPdfPage(pending.removeFirst(), pipeline, pages, warnings, remaining, document.getNumberOfPages());
                }
            }
            while (!pending.isEmpty()) remaining = appendPdfPage(pending.removeFirst(), pipeline, pages, warnings, remaining, document.getNumberOfPages());
            pipeline.finish();
            return new ParsedDocument(List.copyOf(pages), List.copyOf(warnings));
        }
    }

    private record PendingPdfPage(int number, String nativeText, Future<OcrOutcome> recognition) { }

    /** PDF packets contain only selected original pages; PDFBox and durable callbacks never cross workers. */
    private ParsedDocument parsePdfBatches(Path path, ParseSession session) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            validatePdf(document);
            int remaining = MAX_CHARACTERS;
            PdfStreamBudget budget = new PdfStreamBudget();
            for (int number = 1; number <= document.getNumberOfPages(); number++) {
                session.progress((number - 1) * 40 / document.getNumberOfPages(), "正在建立原生文档地图：第 " + number + " 页");
                budget.checkPage(document.getPage(number - 1));
                NativePdfStripper stripper = new NativePdfStripper();
                stripper.setSortByPosition(true); stripper.setStartPage(number); stripper.setEndPage(number);
                stripper.setLineSeparator("\n"); stripper.setPageStart(""); stripper.setPageEnd("");
                BoundedWriter writer = new BoundedWriter(remaining); stripper.writeText(document, writer);
                String text = normalize(writer.toString()); remaining -= text.length();
                session.nativeLocation(number, "PAGE", text, stripper.imageCount, !PageClassifier.usableNativeText(text) || stripper.hasImage ? 1 : 0);
            }
            session.resolve();
            try (PdfPipeline pipeline = new PdfPipeline(document, session)) { pipeline.run(); }
            List<Page> pages = new ArrayList<>(); List<String> warnings = new ArrayList<>();
            for (var location : session.nativeMap.values()) {
                int number = location.position();
                OcrOutcome outcome = session.outcomes.get(ExtractionPlan.unitKey("PAGE", number, 1));
                Page page = completedPdfPage(location, outcome);
                remaining -= page.text().length() - location.text().length();
                if (remaining < 0) throw tooMuchText();
                session.progress(40 + number * 60 / document.getNumberOfPages(), "已解析第 " + number + " 页");
                if (!page.warning().isBlank()) warnings.add(page.warning());
                pages.add(page);
            }
            return new ParsedDocument(List.copyOf(pages), List.copyOf(warnings));
        }
    }

    private static Page completedPdfPage(ExtractionPlan.NativeLocation location, OcrOutcome outcome) {
        int number = location.position();
        String text = location.text(), parser = "PDFBOX_NATIVE", warning = "";
        if (outcome != null) {
            warning = "第 " + number + " 页" + outcome.warning;
            if (outcome.success) {
                parser = outcome.warning.contains("[PDF_OCR]") ? "PDFBOX_PDF_OCR" : "PDFBOX_SINGLE_OCR";
                text = mergePdfText(text, outcome.text);
            }
        }
        var layout = outcome == null ? null : outcome.layout.source("p" + number + "_", "PDF_RENDER", number);
        return new Page(number, text, "PAGE", parser, warning, layout);
    }

    private static String mergePdfText(String nativeText, String recognized) {
        if (nativeText.isBlank()) return recognized;
        if (recognized.isBlank()) return nativeText;
        // PDF document_parsing includes native text. Preserve its table/figure order, adding only
        // native lines that are absent; never remove repeated table rows within provider output.
        Set<String> present = recognized.lines().map(String::strip).filter(s -> !s.isBlank()).collect(Collectors.toSet());
        String missing = nativeText.lines().map(String::strip).filter(s -> !s.isBlank() && !present.contains(s)).collect(Collectors.joining("\n"));
        return missing.isBlank() ? recognized : missing + "\n\n【PDF识别文字】\n" + recognized;
    }

    private final class PdfPipeline implements AutoCloseable {
        private final OcrClient recovery = java.util.Objects.requireNonNull(ocr.pdfRecoveryClient());
        private final String recoveryIdentity = PDF_RECOVERY_POLICY + ":" + recovery.identity();
        private final PDDocument document;
        private final ParseSession session;
        private final int concurrency = Math.max(1, Math.min(5, pdfOcr.concurrency()));
        private final int batchPages = Math.max(1, Math.min(10, pdfOcr.batchPages()));
        private final ExecutorService executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "bidcheck-pdf-ocr"); thread.setDaemon(true); return thread;
        });
        private final java.util.concurrent.CompletionService<PdfResponse> completions = new java.util.concurrent.ExecutorCompletionService<>(executor);
        private final Map<Future<PdfResponse>, PdfWork> active = new HashMap<>();
        private final Map<Integer, Unit> units = new HashMap<>();
        private final ArrayDeque<List<Integer>> packets = new ArrayDeque<>();
        private final ArrayDeque<Integer> fallback = new ArrayDeque<>();
        private final Map<Integer, PdfWork> recoveryParents = new HashMap<>();
        private final Map<Integer, String> recoveryReasons = new HashMap<>();
        private final Map<Integer, String> qualityRejected = new java.util.TreeMap<>();
        private final Map<String, Integer> recoveryRemaining = new HashMap<>();
        private final Set<String> recoveryFailed = new HashSet<>();
        private String serviceDeferredCode = "";
        private long heldBytes;
        private int pdfRequests, singleRequests, pdfSucceeded, singleSucceeded, restored, completed;
        private final java.util.concurrent.atomic.AtomicInteger singleApiCalls = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger singleRetryCalls = new java.util.concurrent.atomic.AtomicInteger();
        private long peakBytes;
        private int peakConcurrency;
        private final List<Map<String, Object>> providerMetrics = new ArrayList<>();

        private PdfPipeline(PDDocument document, ParseSession session) { this.document = document; this.session = session; }

        private void run() throws IOException {
            List<Integer> selected = new ArrayList<>();
            for (var location : session.nativeMap.values()) {
                checkInterrupted();
                if (location.ocrUnits() == 0) { session.pageCompleted(completedPdfPage(location, null)); continue; }
                int page = location.position();
                Unit unit = pdfUnit(page); units.put(page, unit);
                session.routes.put(ExtractionPlan.unitKey("PAGE", page, 1), PageClassifier.classify(location.text(), location.imageCount(), null));
                OcrOutcome deferred = session.deferred("PAGE", page, 1);
                if (deferred != null) { publish(unit, deferred, 0); continue; }
                OcrOutcome saved = restore(unit);
                if (saved != null) { restored++; session.restoredImages++; publish(units.get(page).cachedCopy(), saved, 0); continue; }
                OcrOutcome unavailable = session.reserveImage("第 " + page + " 页 PDF", true);
                if (unavailable != null) { publish(unit, unavailable, 0); continue; }
                selected.add(page);
            }
            // Preserve source identity and ordering within a priority. Independent PDF transcription
            // packets can bring known certificate/table evidence forward without joining its facts.
            selected.sort(java.util.Comparator.comparingInt(page -> switch (session.routes.get(ExtractionPlan.unitKey("PAGE", page, 1)).priority()) {
                case "HIGH" -> 0;
                case "LOW" -> 2;
                default -> 1;
            }));
            for (int start = 0; start < selected.size(); start += batchPages)
                packets.addLast(List.copyOf(selected.subList(start, Math.min(selected.size(), start + batchPages))));
            while (!packets.isEmpty() || !fallback.isEmpty() || !active.isEmpty()) {
                checkInterrupted(); heartbeat();
                boolean added = false;
                while (active.size() < concurrency && (!fallback.isEmpty() || !packets.isEmpty())) {
                    if (!fallback.isEmpty()) {
                        // Reserve bounded image memory before rendering, including a serialization copy.
                        long needed = Math.min(ocrBytesBudget, 12L * 1024 * 1024);
                        if (!active.isEmpty() && heldBytes + needed > pdfBytesBudget) break;
                        int page = fallback.removeFirst();
                        if (!recovery.enabled()) { publish(units.get(page), ocrFailed(), 0); continue; }
                        PreparedImage image;
                        try { image = prepareImage(renderPdfPage(document, page, true)); }
                        catch (InterruptedIOException stopped) { throw stopped; }
                        catch (PdfInterrupted stopped) { throw interrupted(); }
                        catch (IOException | PdfBudgetExceeded invalid) { publish(units.get(page), ocrFailed(), 0); continue; }
                        long cost = image.bytes.length * 2L;
                        if (cost > pdfBytesBudget || image.bytes.length > ocrBytesBudget || image.pixels() > ocrPixelsBudget) {
                            publish(units.get(page), new OcrOutcome("", "[OCR_DEFERRED][OCR未完成] 单页补识别超过图像资源预算。", false), 0); continue;
                        }
                        // Above reservation covers PNG/JPEG input limits, so this never queues unbounded rendered pages.
                        while (!active.isEmpty() && heldBytes + cost > pdfBytesBudget) collect();
                        Unit previous = units.get(page);
                        Unit unit = new Unit("PAGE", page, 1, previous.hash, image.width, image.height, image.bytes.length, false);
                        units.put(page, unit);
                        // Keep physical table/seal hints even when plain transcription has no table markup.
                        var location = session.nativeMap.get(ExtractionPlan.locationKey("PAGE", page));
                        session.routes.put(ExtractionPlan.unitKey("PAGE", page, 1),
                                PageClassifier.classify(location.text(), location.imageCount(), image.bytes));
                        PdfWork work = work(List.of(page), true, cost, sha256(image.bytes));
                        begin(work);
                        Future<PdfResponse> future = completions.submit(() -> {
                            checkInterrupted();
                            OcrClient counted = new OcrClient() {
                                public boolean enabled() { return recovery.enabled(); }
                                public String identity() { return recovery.identity(); }
                                public String modelName() { return recovery.modelName(); }
                                public cn.bidcheck.ocr.OcrResult recognize(byte[] bytes, String mime) throws IOException {
                                    for (int attempt=0;attempt<2;attempt++) {
                                        checkInterrupted();singleApiCalls.incrementAndGet();
                                        if(attempt>0)singleRetryCalls.incrementAndGet();
                                        try{return recovery.recognize(bytes,mime);}
                                        catch(InterruptedIOException cancelled){throw cancelled;}
                                        catch(IOException failed){
                                            if(attempt>0||!transientOcrFailure(failed))throw failed;
                                            try{TimeUnit.MILLISECONDS.sleep(200);}
                                            catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw interrupted();}
                                        }
                                    }
                                    throw new IOException("单页补识别重试已结束。");
                                }
                            };
                            ParseSession worker = new ParseSession(counted, null, 1);
                            OcrOutcome result = worker.result(counted.recognize(image.bytes, OcrPipeline.mime(image.bytes)), image.bytes, "第 " + page + " 页");
                            if (result.success && !"COMPLETE".equals(result.layout.status()))
                                result = new OcrOutcome(result.text, result.warning + "[LAYOUT_PDF_TEXT_RECOVERY] 单页补识别已恢复文字，图像结构仍需核验。", true, result.layout);
                            return new PdfResponse(null, result);
                        });
                        active.put(future, work); singleRequests++;
                    } else {
                        List<Integer> pages = packets.peekFirst();
                        long available = (pdfBytesBudget - heldBytes) / 3;
                        if (available < 1) break;
                        byte[] pdf;
                        try { pdf = packet(pages, Math.min(pdfBatchBytes, available)); }
                        catch (PdfPacketLimit tooLarge) {
                            if (!active.isEmpty() && available < pdfBatchBytes) break;
                            packets.removeFirst();
                            if (pages.size() == 1) fallback.addLast(pages.get(0));
                            else {
                                int half = pages.size() / 2;
                                packets.addFirst(List.copyOf(pages.subList(half, pages.size())));
                                packets.addFirst(List.copyOf(pages.subList(0, half)));
                            }
                            continue;
                        }
                        packets.removeFirst();
                        // Charge buffer growth plus its final copy before admitting another packet.
                        PdfWork work = work(pages, false, pdf.length * 3L, sha256(pdf));
                        begin(work);
                        Future<PdfResponse> future = completions.submit(() -> {
                            checkInterrupted();
                            return new PdfResponse(pdfOcr.recognizePdf(pdf, pages), null);
                        });
                        active.put(future, work); pdfRequests++;
                    }
                    added = true;
                    peakConcurrency = Math.max(peakConcurrency, active.size());
                    session.peakOcrConcurrency = Math.max(session.peakOcrConcurrency, active.size());
                }
                if (!active.isEmpty()) collect();
                else if (!added && (!packets.isEmpty() || !fallback.isEmpty())) throw new IOException("PDF OCR 资源调度无法继续。");
            }
            session.pdfReport = Map.ofEntries(Map.entry("enabled", true), Map.entry("model", safeModelName(pdfOcr.modelName())),
                    Map.entry("batchPages", batchPages), Map.entry("concurrency", concurrency), Map.entry("submittedBatches", pdfRequests),
                    Map.entry("singlePageFallbackCalls", singleApiCalls.get()), Map.entry("singlePageFallbackUnits", singleRequests), Map.entry("pdfRecognizedPages", pdfSucceeded),
                    Map.entry("singlePageRetryCalls", singleRetryCalls.get()),
                    Map.entry("singlePageRecoveryPolicy", PDF_RECOVERY_POLICY),
                    Map.entry("singlePageRecoveryModel", safeModelName(recovery.modelName())),
                    Map.entry("singlePageRecoveryIdentity", sha256(recoveryIdentity.getBytes(StandardCharsets.UTF_8))),
                    Map.entry("singleRecognizedPages", singleSucceeded), Map.entry("restoredPages", restored),
                    Map.entry("nativeOnlyPages", session.nativeMap.values().stream().filter(l -> l.ocrUnits() == 0).count()),
                    Map.entry("traversedPages", document.getNumberOfPages()), Map.entry("peakActiveBytes", peakBytes),
                    Map.entry("peakConcurrency", peakConcurrency), Map.entry("maxBytes", pdfBytesBudget), Map.entry("providerMetrics", List.copyOf(providerMetrics)),
                    Map.entry("qualityRejectedPages", qualityRejected.entrySet().stream().map(e -> Map.of("page", e.getKey(), "code", e.getValue())).toList()));
        }

        private PdfWork work(List<Integer> pages, boolean single, long bytes, String packetHash) {
            String hash = sha256((session.sourceHash + "|" + pdfOcr.identity() + "|" + pages + "|" + packetHash + "|" + single
                    + (single ? "|fallback:" + recoveryIdentity : "")).getBytes(StandardCharsets.UTF_8));
            PdfWork parent = single ? recoveryParents.get(pages.get(0)) : null;
            return new PdfWork(pages, single, bytes, "pdf_" + hash.substring(0, 32), parent == null ? "" : parent.id, hash, System.nanoTime());
        }
        private void begin(PdfWork work) throws IOException {
            heartbeat();
            for (int page : work.pages) session.observe(units.get(page), "RUNNING", null, 0);
            batch(work, "RUNNING", "");
            heldBytes += work.bytes; peakBytes = Math.max(peakBytes, heldBytes);
            session.peakOcrBytes = Math.max(session.peakOcrBytes, heldBytes);
        }
        private void collect() throws IOException {
            Future<PdfResponse> future;
            try { future = completions.poll(1, TimeUnit.SECONDS); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw interrupted(); }
            if (future == null) { heartbeat(); return; }
            PdfWork work = active.remove(future);
            if (work == null) return;
            PdfResponse response = null;
            Throwable requestFailure = null;
            try { response = future.get(); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw interrupted(); }
            catch (java.util.concurrent.CancellationException stopped) { throw interrupted(); }
            catch (ExecutionException failure) {
                if (failure.getCause() instanceof InterruptedIOException stopped) throw stopped;
                if (failure.getCause() instanceof java.util.concurrent.CancellationException || failure.getCause() instanceof InterruptedException) throw interrupted();
                if (failure.getCause() instanceof Error error) throw error;
                requestFailure = failure.getCause();
                // Only a completed non-cancellation request may enter single-page recovery.
            }
            heldBytes -= work.bytes;
            heartbeat();
            if (serviceFailure(requestFailure)) {
                serviceDeferredCode = safeFailureCode(requestFailure);
                for (int page : work.pages) publish(units.get(page), serviceDeferred(), elapsed(work));
                batch(work, "FAILED", serviceDeferredCode);
                deferQueued();
                return;
            }
            if (work.single) {
                OcrOutcome result = response == null || response.single == null ? ocrFailed() : response.single;
                if (result.success) {
                    singleSucceeded++;
                    String reason = recoveryReasons.getOrDefault(work.pages.get(0), "PDF_PAGE_RECOVERY_REQUIRED");
                    result = new OcrOutcome(result.text, "[PDF单页补识别] 原PDF结果触发 " + reason + "，已用该原页图像补识别。" + result.warning, true, result.layout);
                }
                if (result.warning.contains("[OCR_GENERATED_")) qualityRejected.put(work.pages.get(0), "SINGLE_OCR_GENERATED_CONTENT");
                publish(units.get(work.pages.get(0)), result, elapsed(work));
                batch(work, result.success ? "SUCCEEDED" : "FAILED", result.success ? "" : "SINGLE_PAGE_FAILED");
            } else {
                PdfOcrClient.BatchResult result = response == null ? null : response.pdf;
                // Unknown or conflicting page identities cannot be attributed to any original page.
                boolean trusted = result != null && work.pages.containsAll(result.pages().keySet()) && work.pages.containsAll(result.errors().keySet());
                int failed = 0;
                for (int page : work.pages) {
                    OcrOutcome outcome = trusted && !result.errors().containsKey(page) ? pdfOutcome(result.pages().get(page)) : null;
                    if (outcome == null) {
                        String qualityCode = trusted && result.pages().get(page) != null ? OcrEvidenceGuard.code(result.pages().get(page).text()) : "";
                        if (!qualityCode.isEmpty()) qualityRejected.put(page, qualityCode);
                        recoveryReasons.put(page, qualityCode.isEmpty() ? "PDF_PAGE_RECOVERY_REQUIRED" : qualityCode);
                        if (serviceDeferredCode.isEmpty()) { fallback.addLast(page); recoveryParents.put(page, work); }
                        else publish(units.get(page), serviceDeferred(), elapsed(work));
                        failed++;
                    }
                    else { pdfSucceeded++; publish(units.get(page), outcome, elapsed(work)); }
                }
                if (trusted) providerMetrics.add(safePdfMetrics(result.metrics()));
                if (failed > 0 && serviceDeferredCode.isEmpty()) recoveryRemaining.put(work.id, failed);
                batch(work, failed == 0 ? "SUCCEEDED" : "PARTIAL_FAILED", failed == 0 ? "" : "PDF_PAGE_RECOVERY_REQUIRED");
            }
        }
        private void publish(Unit unit, OcrOutcome outcome, long elapsed) throws IOException {
            heartbeat();
            session.completed("PAGE", unit.position, 1, outcome);
            session.observe(unit, outcome.success ? (outcome.warning.contains("[OCR未完成]") ? "PARTIAL_SUCCEEDED" : "SUCCEEDED")
                    : outcome.warning.contains("[OCR_DEFERRED]") ? "DEFERRED" : "FAILED", outcome, elapsed);
            session.pageCompleted(completedPdfPage(session.nativeMap.get(ExtractionPlan.locationKey("PAGE", unit.position)), outcome));
            PdfWork parent = recoveryParents.remove(unit.position);
            if (parent != null) {
                if (!outcome.success || outcome.warning.contains("[OCR未完成]")) recoveryFailed.add(parent.id);
                int left = recoveryRemaining.computeIfPresent(parent.id, (id, count) -> count - 1);
                if (left == 0) {
                    recoveryRemaining.remove(parent.id);
                    boolean failed = recoveryFailed.remove(parent.id);
                    batch(parent, failed ? "PARTIAL_FAILED" : "RECOVERED", failed ? "SINGLE_PAGE_RECOVERY_INCOMPLETE" : "");
                }
            }
            completed++;
        }
        private OcrOutcome serviceDeferred() {
            return new OcrOutcome("", "[OCR_DEFERRED][OCR未完成] PDF识别服务暂不可用（" + serviceDeferredCode + "），未追加单页请求；请恢复服务后继续识别。", false);
        }
        private void deferQueued() throws IOException {
            while (!packets.isEmpty()) for (int page : packets.removeFirst()) publish(units.get(page), serviceDeferred(), 0);
            while (!fallback.isEmpty()) publish(units.get(fallback.removeFirst()), serviceDeferred(), 0);
        }
        private void heartbeat() throws InterruptedIOException {
            session.progress(40 + Math.min(59, completed * 60 / Math.max(1, document.getNumberOfPages())), "正在识别 PDF：已保存 " + completed + " 页");
        }
        private void batch(PdfWork work, String status, String error) throws InterruptedIOException {
            session.observeBatch(new OcrBatch.Event(work.id, work.parentId, status, work.pages.stream().map(p -> "PAGE:" + p + ":1").toList(),
                    safeModelName(work.single ? recovery.modelName() : pdfOcr.modelName()), "PDF_SOURCE_PAGES_V1", work.hash, 0, 0, elapsed(work), error));
        }
        private Unit pdfUnit(int page) throws IOException {
            var box = document.getPage(page - 1).getCropBox();
            double width = box.getWidth(), height = box.getHeight();
            if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) throw new IOException("PDF 页面尺寸无效。");
            double scale = Math.min(3d, Math.min(2400d / Math.max(width, height), Math.sqrt(4_000_000d / (width * height))));
            int w = Math.max(1, (int) Math.floor(width * (float) scale)), h = Math.max(1, (int) Math.floor(height * (float) scale));
            if (Math.floorMod(document.getPage(page - 1).getRotation(), 180) != 0) { int swap = w; w = h; h = swap; }
            String hash = sha256(("PDF_PAGE_V2|" + session.sourceHash + "|" + page + "|" + pdfOcr.identity() + "|fallback:" + recoveryIdentity).getBytes(StandardCharsets.UTF_8));
            return new Unit("PAGE", page, 1, hash, w, h, 0, false);
        }
        private OcrOutcome restore(Unit unit) throws IOException {
            var candidate = session.restoreCandidate(unit);
            if (candidate == null || candidate.isEmpty()) return null;
            var result = candidate.get();
            if (result.layout() == null) return null;
            if (result.layout().frames().isEmpty()) return ParseSession.validateRestored(result, unit);
            // A previous single-image recovery has real image coordinates. Re-render only this
            // saved candidate to validate that geometry; fresh PDF packets are never rasterized.
            PreparedImage image = prepareImage(renderPdfPage(document, unit.position, true));
            Unit imageUnit = new Unit("PAGE", unit.position, 1, image.hash, image.width, image.height, image.bytes.length, true);
            OcrOutcome restored = ParseSession.validateRestored(result, imageUnit);
            if (restored != null) units.put(unit.position, new Unit("PAGE", unit.position, 1, unit.hash, image.width, image.height, image.bytes.length, true));
            return restored;
        }
        private byte[] packet(List<Integer> pages, long limit) throws IOException {
            return pdfPacket(document, pages, limit);
        }
        @Override public void close() {
            for (Future<PdfResponse> future : active.keySet()) future.cancel(true);
            active.clear(); packets.clear(); fallback.clear(); executor.shutdownNow();
        }
    }

    private OcrOutcome pdfOutcome(cn.bidcheck.ocr.OcrResult result) {
        String text = result == null || result.text() == null ? "" : normalize(result.text());
        if (text.isBlank() || text.length() > 50_000 || text.indexOf('\0') >= 0 || pageNumberOnly(text) || !OcrEvidenceGuard.code(text).isEmpty()) return null;
        // Document coordinates differ from rendered-image coordinates; text has page identity,
        // but never fabricate pixel boxes from provider PDF layout or relative page numbers.
        var layout = cn.bidcheck.ocr.OcrLayout.unavailable("PDF 原页转录已绑定；没有经过图像来源校验的像素坐标。");
        return new OcrOutcome(text, "[PDF_OCR][OCR已识别] 模型：" + safeModelName(pdfOcr.modelName()) + "；" + OCR_SUCCESS_WARNING + "。" + layoutWarning(layout), true, layout);
    }
    private static Map<String, Object> safePdfMetrics(Map<String, Object> metrics) {
        var safe = new java.util.LinkedHashMap<String, Object>();
        for (String key : List.of("elapsedMillis", "requestCount", "cachedPages", "inputTokens", "outputTokens", "totalTokens", "figureCount", "pages", "cached", "cacheHit",
                "httpRequests", "policyRequests", "uploadRequests", "ocrRequests", "retries", "policyMillis", "uploadMillis", "ocrMillis", "httpStatus", "resultPages", "errorPages")) {
            Object value = metrics.get(key);
            if (value instanceof Number || value instanceof Boolean) safe.put(key, value);
        }
        if (metrics.get("usage") instanceof Map<?, ?> usage) {
            var tokens = new java.util.LinkedHashMap<String, Object>();
            for (String key : List.of("input_tokens", "output_tokens", "total_tokens", "image_tokens", "text_tokens"))
                if (usage.get(key) instanceof Number value) tokens.put(key, value);
            if (!tokens.isEmpty()) safe.put("usage", Map.copyOf(tokens));
        }
        return Map.copyOf(safe);
    }
    private static boolean serviceFailure(Throwable failure) {
        if (!(failure instanceof cn.bidcheck.ocr.OcrFailure safe)) return false;
        Integer status = safe.httpStatus();
        if (status != null) return status == 401 || status == 402 || status == 403 || status == 404 || status == 429 || status >= 500;
        // Local admission expiry affects this packet only; recovery remains bounded by the scheduler.
        if ("QUEUE_TIMEOUT".equals(safe.code())) return false;
        String code = safe.code();
        return code != null && (code.contains("TRANSPORT") && !safe.temporary() || code.contains("QUEUE") || code.contains("BUSY") || code.contains("CIRCUIT")
                || code.contains("QUOTA") || code.contains("AUTH") || code.contains("CREDENTIAL") || code.contains("UPLOAD_HOST") || code.contains("UPLOAD_POLICY"));
    }
    private static boolean transientOcrFailure(IOException failure){
        if(failure instanceof cn.bidcheck.ocr.OcrFailure safe)return safe.temporary();
        return failure instanceof java.net.SocketException||failure instanceof java.net.http.HttpTimeoutException||failure instanceof java.io.EOFException;
    }
    private static String safeFailureCode(Throwable failure) {
        String code = failure instanceof cn.bidcheck.ocr.OcrFailure safe ? safe.code() : "PDF_SERVICE_UNAVAILABLE";
        return code != null && code.matches("[A-Z0-9_]{1,80}") ? code : "PDF_SERVICE_UNAVAILABLE";
    }
    private record PdfWork(List<Integer> pages, boolean single, long bytes, String id, String parentId, String hash, long started) { }
    private record PdfResponse(PdfOcrClient.BatchResult pdf, OcrOutcome single) { }
    private static long elapsed(PdfWork work) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - work.started); }

    /** Package-private for offline packet/source-isolation regression tests. */
    static byte[] pdfPacket(PDDocument document, List<Integer> pages, long limit) throws IOException {
        try (PDDocument packet = new PDDocument(); PdfPacketOutput output = new PdfPacketOutput(limit)) {
            Map<org.apache.pdfbox.cos.COSDictionary, org.apache.pdfbox.cos.COSDictionary> pageCopies = new IdentityHashMap<>();
            List<PDPage> originals = new ArrayList<>(), importedPages = new ArrayList<>();
            for (int page : pages) {
                checkInterrupted();
                PDPage original = document.getPage(page - 1), imported = packet.importPage(original);
                imported.setResources(original.getResources());
                // importPage shallow-copies /Annots. Never mutate the source array or annotations.
                imported.getCOSObject().removeItem(COSName.ANNOTS);
                pageCopies.put(original.getCOSObject(), imported.getCOSObject());
                originals.add(original); importedPages.add(imported);
            }
            PacketAnnotationCloner cloner = new PacketAnnotationCloner(packet, pageCopies);
            for (int index = 0; index < originals.size(); index++) {
                PDPage original = originals.get(index), imported = importedPages.get(index);
                org.apache.pdfbox.cos.COSArray annotations = new org.apache.pdfbox.cos.COSArray();
                for (var annotation : original.getAnnotations()) {
                    var detached = new org.apache.pdfbox.cos.COSDictionary(annotation.getCOSObject());
                    detached.setItem(COSName.P, original.getCOSObject());
                    // Deep-clone appearance streams and nested annotation objects into the packet;
                    // /P and internal destinations resolve only to this packet's selected pages.
                    annotations.add(cloner.cloneForNewDocument(detached));
                }
                if (annotations.size() > 0) imported.getCOSObject().setItem(COSName.ANNOTS, annotations);
            }
            packet.save(output); checkInterrupted(); return output.toByteArray();
        }
    }

    private static final class PacketAnnotationCloner extends org.apache.pdfbox.multipdf.PDFCloneUtility {
        private final Map<org.apache.pdfbox.cos.COSDictionary, org.apache.pdfbox.cos.COSDictionary> pageCopies;
        private PacketAnnotationCloner(PDDocument destination, Map<org.apache.pdfbox.cos.COSDictionary, org.apache.pdfbox.cos.COSDictionary> pageCopies) {
            super(destination); this.pageCopies = pageCopies;
        }
        @Override @SuppressWarnings("unchecked") public <T extends COSBase> T cloneForNewDocument(T original) throws IOException {
            if (original instanceof org.apache.pdfbox.cos.COSDictionary dictionary) {
                if (COSName.PAGE.equals(dictionary.getCOSName(COSName.TYPE)))
                    return (T) (pageCopies.containsKey(dictionary) ? pageCopies.get(dictionary) : org.apache.pdfbox.cos.COSNull.NULL);
                if (COSName.PAGES.equals(dictionary.getCOSName(COSName.TYPE))) return (T) org.apache.pdfbox.cos.COSNull.NULL;
                if (COSName.ANNOT.equals(dictionary.getCOSName(COSName.TYPE))
                        && dictionary.getDictionaryObject(COSName.P) instanceof org.apache.pdfbox.cos.COSDictionary page
                        && !pageCopies.containsKey(page)) return (T) org.apache.pdfbox.cos.COSNull.NULL;
            }
            return super.cloneForNewDocument(original);
        }
    }
    private static final class PdfPacketLimit extends RuntimeException { }
    private static final class PdfPacketOutput extends ByteArrayOutputStream {
        private final long limit;
        private PdfPacketOutput(long limit) { super((int) Math.min(8192, limit)); this.limit = limit; }
        @Override public synchronized void write(int value) { if (count + 1L > limit) throw new PdfPacketLimit(); super.write(value); }
        @Override public synchronized void write(byte[] value, int offset, int length) {
            if (count + (long) length > limit) throw new PdfPacketLimit(); super.write(value, offset, length);
        }
    }

    private ParsedDocument parsePdfPlanned(Path path, ParseSession session) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache());
             OcrPipeline pipeline = new OcrPipeline(session, ocrConcurrency, ocrBytesBudget, ocrPixelsBudget)) {
            validatePdf(document);
            int remaining = MAX_CHARACTERS;
            PdfStreamBudget budget = new PdfStreamBudget();
            for (int number = 1; number <= document.getNumberOfPages(); number++) {
                session.progress((number - 1) * 40 / document.getNumberOfPages(), "正在建立原生文档地图：第 " + number + " 页");
                budget.checkPage(document.getPage(number - 1));
                NativePdfStripper stripper = new NativePdfStripper();
                stripper.setSortByPosition(true); stripper.setStartPage(number); stripper.setEndPage(number);
                stripper.setLineSeparator("\n"); stripper.setPageStart(""); stripper.setPageEnd("");
                BoundedWriter writer = new BoundedWriter(remaining);
                stripper.writeText(document, writer);
                String text = normalize(writer.toString()); remaining -= text.length();
                session.nativeLocation(number, "PAGE", text, stripper.imageCount, !PageClassifier.usableNativeText(text) || stripper.hasImage ? 1 : 0);
            }
            session.resolve();
            List<Page> pages = new ArrayList<>(); List<String> warnings = new ArrayList<>();
            ArrayDeque<PendingPdfPage> pending = new ArrayDeque<>();
            for (ExtractionPlan.NativeLocation location : session.nativeMap.values()) {
                pipeline.tick();
                while (pending.size() >= 32)
                    remaining = appendPdfPage(pending.removeFirst(), pipeline, pages, warnings, remaining, document.getNumberOfPages());
                session.workProgress((location.position() - 1) * 100 / document.getNumberOfPages(), "正在按计划处理第 " + location.position() + " 页");
                Future<OcrOutcome> recognition = CompletableFuture.completedFuture(null);
                if (location.ocrUnits() > 0) {
                    int page = location.position();
                    recognition = pipeline.submit("PAGE", page, 1, () -> renderPdfPage(document, page, true), "第 " + page + " 页图像");
                } else session.pageCompleted(completedImagePdfPage(location.position(), location.text(), null));
                pending.addLast(new PendingPdfPage(location.position(), location.text(), recognition));
                while (!pending.isEmpty() && pending.peekFirst().recognition.isDone())
                    remaining = appendPdfPage(pending.removeFirst(), pipeline, pages, warnings, remaining, document.getNumberOfPages());
            }
            while (!pending.isEmpty()) remaining = appendPdfPage(pending.removeFirst(), pipeline, pages, warnings, remaining, document.getNumberOfPages());
            pipeline.finish();
            return new ParsedDocument(List.copyOf(pages), List.copyOf(warnings));
        }
    }

    private static int appendPdfPage(PendingPdfPage pending, OcrPipeline pipeline, List<Page> pages,
                                     List<String> warnings, int remaining, int totalPages) throws IOException {
        OcrOutcome outcome = pipeline.await(pending.recognition, "第 " + pending.number + " 页");
        Page page = completedImagePdfPage(pending.number, pending.nativeText, outcome);
        remaining -= page.text().length() - pending.nativeText.length();
        if (remaining < 0) throw tooMuchText();
        // The listener performs the lease fence before any result is appended, and always runs on the caller thread.
        pipeline.session.workProgress(pending.number * 100 / totalPages, "已解析第 " + pending.number + " 页");
        if (!page.warning().isBlank()) warnings.add(page.warning());
        pages.add(page);
        pipeline.session.pageCompleted(page);
        return remaining;
    }

    private static Page completedImagePdfPage(int number, String nativeText, OcrOutcome outcome) {
        String text = nativeText, warning = "", parser = "PDFBOX_NATIVE";
        if (outcome != null) {
            warning = "第 " + number + " 页" + outcome.warning;
            if (outcome.success) {
                parser = text.isBlank() ? "PDFBOX_OCR" : "PDFBOX_NATIVE_WITH_OCR";
                text = withOcr(text, outcome.text, "OCR识别文字");
            }
        }
        var layout = outcome == null ? null : outcome.layout.source("p" + number + "_", "PDF_RENDER", number);
        return new Page(number, text, "PAGE", parser, warning, layout);
    }

    /** Immutable prepared images cross workers; every source, progress and persistence mutation stays on the caller. */
    private static final class OcrPipeline implements AutoCloseable {
        private final ParseSession session;
        private final ExecutorService executor;
        private final LinkedBlockingQueue<Completion> events = new LinkedBlockingQueue<>(256);
        private final int concurrency;
        private final long bytesBudget, pixelsBudget;
        private final OcrBatchPolicy policy;
        private long heldBytes, heldPixels;
        private volatile boolean closed;
        private final Map<String, CompletableFuture<OcrOutcome>> cache = new java.util.LinkedHashMap<>(32, .75f, true);
        private final Map<String, Work> activeByHash = new HashMap<>();
        private final Map<BatchKey, Group> groups = new java.util.LinkedHashMap<>();
        private final Set<Batch> pending = new HashSet<>();

        private OcrPipeline(ParseSession session, int concurrency, long bytesBudget, long pixelsBudget) {
            this.session = session; this.concurrency = concurrency;
            this.bytesBudget = bytesBudget; this.pixelsBudget = pixelsBudget;
            this.policy = batchPolicy(session.client);
            this.executor = Executors.newFixedThreadPool(concurrency, runnable -> {
                Thread thread = new Thread(runnable, "bidcheck-document-ocr"); thread.setDaemon(true); return thread;
            });
        }

        private Future<OcrOutcome> submit(String type, int position, int imageIndex, ImageSource source, String location) throws IOException {
            tick();
            String unitKey = ExtractionPlan.unitKey(type, position, imageIndex);
            var nativeLocation = session.nativeMap.get(ExtractionPlan.locationKey(type, position));
            String nativeText = nativeLocation == null ? "" : nativeLocation.text();
            int imageCount = nativeLocation == null ? 1 : nativeLocation.imageCount();
            session.routes.put(unitKey, PageClassifier.classify(nativeText, imageCount, null));
            OcrOutcome deferred = session.deferred(type, position, imageIndex);
            if (deferred != null) return finished(new Unit(type, position, imageIndex, "", 0, 0, 0, false), deferred, 0);
            if (!session.client.enabled() && session.observer == OcrUnitObserver.noop())
                return finished(new Unit(type, position, imageIndex, "", 0, 0, 0, false), session.reserveImage(location), 0);
            PreparedImage image;
            try { image = prepareImage(source.read()); }
            catch (InterruptedIOException stopped) { throw stopped; }
            catch (PdfInterrupted stopped) { throw interrupted(); }
            catch (IOException | PdfBudgetExceeded invalid) {
                return finished(new Unit(type, position, imageIndex, "", 0, 0, 0, false), ocrFailed(), 0);
            }
            tick();
            PageClassifier.Decision route = PageClassifier.classify(nativeText, imageCount, image.bytes);
            session.routes.put(unitKey, route);
            Unit unit = new Unit(type, position, imageIndex, image.hash, image.width, image.height, image.bytes.length, false);
            OcrOutcome restored = session.restore(unit);
            if (restored != null) { session.restoredImages++; return finished(unit.cachedCopy(), restored, 0); }
            // This cache stores full-image transcription/geometry only, never schema-specific facts.
            // A repeated original image therefore keeps the existing one-recognition guarantee.
            String reuseKey = image.hash;
            CompletableFuture<OcrOutcome> duplicate = cache.get(reuseKey);
            if (duplicate != null) {
                session.deduplicatedImages++;
                if (duplicate.isDone()) return finished(unit.cachedCopy(), duplicate.join(), 0);
                activeByHash.get(reuseKey).units.add(unit.cachedCopy());
                session.observe(unit.cachedCopy(), "RUNNING", null, 0);
                return duplicate;
            }
            int tokens = (int) ((image.pixels() + 1023) / 1024) + 2 + OcrBatch.ITEM_METADATA_ESTIMATED_TOKENS;
            if (image.bytes.length > bytesBudget || image.pixels() > pixelsBudget || image.bytes.length > policy.maxBytes()
                    || image.pixels() > policy.maxPixels() || OcrBatch.PROMPT_ESTIMATED_TOKENS + tokens > policy.maxEstimatedTokens())
                return finished(unit, new OcrOutcome("", "[OCR_DEFERRED][OCR未完成] 图像超过本次请求或并行资源预算，请调整预算或分辨率后继续识别。", false), 0);
            OcrOutcome unavailable = session.reserveImage(location);
            if (unavailable != null) return finished(unit, unavailable, 0);
            // Both batching buffers and executing requests hold the same document-wide allowance.
            while (heldBytes + image.bytes.length > bytesBudget || heldPixels + image.pixels() > pixelsBudget) {
                flushAll();
                collect(true, location);
            }
            BatchKey key = new BatchKey(route.pageType(), route.schema(), route.resolutionBucket(), route.requiresCrossPageContext(), route.independent());
            Group group = groups.get(key);
            if (group != null && (group.bytes + image.bytes.length > policy.maxBytes() || group.pixels + image.pixels() > policy.maxPixels()
                    || group.tokens + tokens > policy.maxEstimatedTokens())) { flush(group); group = null; }
            if (group == null) { group = new Group(key); groups.put(key, group); }
            Work work = new Work(unitKey, reuseKey, location, image, route, tokens, unit);
            group.work.add(work); group.bytes += image.bytes.length; group.pixels += image.pixels(); group.tokens += tokens;
            activeByHash.put(reuseKey, work); cache.put(reuseKey, work.result);
            heldBytes += image.bytes.length; heldPixels += image.pixels();
            session.peakOcrBytes = Math.max(session.peakOcrBytes, heldBytes); session.peakOcrPixels = Math.max(session.peakOcrPixels, heldPixels);
            int limit = policy.enabled() && !route.independent() ? policy.maxPages() : 1;
            if (group.work.size() >= limit || policy.maxWaitMillis() == 0) flush(group);
            else tick();
            return work.result;
        }

        private void tick() throws IOException {
            while (collect(false, "已完成图像")) { }
            long now = System.nanoTime();
            for (Group group : List.copyOf(groups.values())) {
                if (TimeUnit.NANOSECONDS.toMillis(now - group.created) >= policy.maxWaitMillis()) flush(group);
            }
        }
        private void flushAll() throws IOException { for (Group group : List.copyOf(groups.values())) flush(group); }
        private void flush(Group group) throws IOException {
            if (group.work.isEmpty() || groups.get(group.key) != group) return;
            while (pending.size() >= concurrency) collect(true, "批次处理");
            groups.remove(group.key);
            Batch batch = new Batch(List.copyOf(group.work), group.bytes, group.pixels);
            for (Work work : batch.work) {
                session.progress(session.percent, "正在识别" + work.location);
                for (Unit unit : work.units) session.observe(unit, "RUNNING", null, 0);
            }
            pending.add(batch);
            session.submittedBatches++; session.peakBatchPages = Math.max(session.peakBatchPages, batch.work.size());
            session.peakOcrConcurrency = Math.max(session.peakOcrConcurrency, pending.size());
            session.peakBatchBytes = Math.max(session.peakBatchBytes, group.bytes);
            session.peakBatchPixels = Math.max(session.peakBatchPixels, group.pixels);
            session.peakBatchTokens = Math.max(session.peakBatchTokens, group.tokens);
            batch.future = executor.submit(() -> runBatch(batch));
            for (Work work : batch.work) session.progress(session.percent, "已提交" + work.location + "识别请求");
        }

        private void runBatch(Batch batch) {
            Throwable failure = null;
            Map<String, Work> byId = new HashMap<>();
            for (Work work : batch.work) byId.put(work.id, work);
            Set<String> emitted = java.util.concurrent.ConcurrentHashMap.newKeySet();
            Map<String, String> lastErrors = new java.util.concurrent.ConcurrentHashMap<>();
            try {
                List<OcrBatch.Input> inputs = batch.work.stream().map(work -> new OcrBatch.Input(work.id, work.image.bytes,
                        mime(work.image.bytes), work.image.width, work.image.height, work.route.pageType(), work.route.schema())).toList();
                if (batch.work.size() == 1 && (batch.work.get(0).route.independent() || !policy.enabled())) {
                    Work work = batch.work.get(0); OcrBatch.Input input = inputs.get(0);
                    OcrOutcome outcome = new ParseSession(session.client, null, 1).result(session.client.recognize(input.image(), input.mimeType()), input.image(), work.location);
                    emitted.add(work.id); enqueue(new Completion(work, outcome, null, null, null));
                    return;
                }
                session.client.recognizeBatch(inputs, new OcrBatch.Listener() {
                    public void accept(OcrBatch.Item item) throws IOException {
                        checkInterrupted();
                        if (item == null || !byId.containsKey(item.id()) || emitted.contains(item.id())) return;
                        Work work = byId.get(item.id());
                        if (item.result() == null && item.retryable()) { lastErrors.put(item.id(), safeCode(item.errorCode())); return; }
                        OcrOutcome outcome = item.result() == null ? failed(item.errorCode())
                                : new ParseSession(session.client, null, 1).result(item.result(), work.image.bytes, work.location);
                        if (emitted.add(item.id())) enqueue(new Completion(work, outcome, null, null, null));
                    }
                    public void onBatch(OcrBatch.Event event) throws IOException { enqueue(new Completion(null, null, null, null, event)); }
                });
            } catch (Throwable stopped) { failure = stopped; }
            finally {
                // Final missing/error items are isolated. Successful items have already crossed to the caller.
                for (Work work : batch.work) if (emitted.add(work.id) && !(failure instanceof InterruptedIOException) && !(failure instanceof Error)) {
                    enqueueFinal(new Completion(work, failed(lastErrors.getOrDefault(work.id, "OCR_BATCH_ITEM_MISSING")), null, null, null));
                }
                enqueueFinal(new Completion(null, null, batch, failure, null));
            }
        }
        private void enqueue(Completion event) throws InterruptedIOException {
            try { while (!closed) { if (events.offer(event, 100, TimeUnit.MILLISECONDS)) return; checkInterrupted(); } }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw interrupted(); }
        }
        private void enqueueFinal(Completion event) {
            boolean interrupted = Thread.interrupted();
            try {
                while (!closed) try { if (events.offer(event, 100, TimeUnit.MILLISECONDS)) return; }
                catch (InterruptedException stopped) { interrupted = true; }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        private boolean collect(boolean wait, String location) throws IOException {
            Completion event;
            for (;;) {
                checkInterrupted();
                try { event = wait ? events.poll(5, TimeUnit.SECONDS) : events.poll(); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw interrupted(); }
                if (event != null || !wait) break;
                session.progress(session.percent, "正在识别" + location + "，等待结果");
            }
            if (event == null) return false;
            if (event.metadata != null) session.observeBatch(event.metadata);
            if (event.work != null && !event.work.result.isDone()) {
                Work work = event.work;
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - work.created);
                for (Unit unit : work.units) publish(unit, event.outcome, elapsed);
                work.result.complete(event.outcome);
                activeByHash.remove(work.reuseKey);
            }
            if (event.batch != null) {
                pending.remove(event.batch); heldBytes -= event.batch.bytes; heldPixels -= event.batch.pixels;
                for (Work work : event.batch.work) work.image = null;
                if (event.failure instanceof InterruptedIOException stopped) throw stopped;
                if (event.failure instanceof Error error) throw error;
            }
            return true;
        }
        private Future<OcrOutcome> finished(Unit unit, OcrOutcome outcome, long elapsed) throws IOException {
            publish(unit, outcome, elapsed); return CompletableFuture.completedFuture(outcome);
        }
        private void publish(Unit unit, OcrOutcome outcome, long elapsed) throws IOException {
            session.completed(unit.type, unit.position, unit.index, outcome);
            String state = outcome.warning.contains("[OCR_DEFERRED]") ? "DEFERRED" : !outcome.success ? "FAILED"
                    : outcome.warning.contains("[OCR未完成]") ? "PARTIAL_SUCCEEDED" : "SUCCEEDED";
            session.observe(unit, state, outcome, elapsed);
            if (unit.type.equals("PAGE")) {
                var location = session.nativeMap.get(ExtractionPlan.locationKey(unit.type, unit.position));
                session.pageCompleted(completedImagePdfPage(unit.position, location.text(), outcome));
            }
        }
        private OcrOutcome await(Future<OcrOutcome> result, String location) throws IOException {
            if (!result.isDone()) flushAll();
            while (!result.isDone()) collect(true, location);
            tick();
            try { return result.get(); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw interrupted(); }
            catch (ExecutionException failure) { throw new IOException("OCR result unavailable", failure.getCause()); }
        }
        private void finish() throws IOException { flushAll(); while (!pending.isEmpty()) collect(true, "尾批处理"); tick(); }
        @Override public void close() {
            closed = true;
            for (Batch batch : pending) if (batch.future != null && !batch.future.isDone()) batch.future.cancel(true);
            executor.shutdownNow();
        }
        private static String mime(byte[] bytes) { return bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 ? "image/jpeg" : "image/png"; }
        private static String safeCode(String code) { return code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "OCR_UNIT_FAILED"; }
        private static OcrOutcome failed(String code) { return new OcrOutcome("", "[OCR未完成][" + safeCode(code) + "] OCR失败：图像识别未完成，请对照原图核对。", false); }
        private record BatchKey(String type, String schema, String resolution, boolean context, boolean independent) { }
        private static final class Group {
            final BatchKey key; final long created = System.nanoTime(); final List<Work> work = new ArrayList<>();
            long bytes, pixels; int tokens = OcrBatch.PROMPT_ESTIMATED_TOKENS;
            Group(BatchKey key) { this.key = key; }
        }
        private static final class Work {
            final String id, reuseKey, location; final PageClassifier.Decision route; final int tokens;
            final long created = System.nanoTime(); final List<Unit> units = new ArrayList<>();
            final CompletableFuture<OcrOutcome> result = new CompletableFuture<>();
            PreparedImage image;
            Work(String id, String reuseKey, String location, PreparedImage image, PageClassifier.Decision route, int tokens, Unit unit) {
                this.id = id; this.reuseKey = reuseKey; this.location = location; this.image = image; this.route = route; this.tokens = tokens; units.add(unit);
            }
        }
        private static final class Batch {
            final List<Work> work; final long bytes, pixels; Future<?> future;
            Batch(List<Work> work, long bytes, long pixels) { this.work = work; this.bytes = bytes; this.pixels = pixels; }
        }
        private record Completion(Work work, OcrOutcome outcome, Batch batch, Throwable failure, OcrBatch.Event metadata) { }
    }

    private record Unit(String type, int position, int index, String hash, int width, int height, long bytes, boolean cached) {
        Unit cachedCopy() { return new Unit(type, position, index, hash, width, height, bytes, true); }
    }
    private record PreparedImage(byte[] bytes, String hash, int width, int height) {
        long pixels() { return (long) width * height; }
    }
    private static PreparedImage prepareImage(byte[] input) throws IOException {
        byte[] bytes = input.length > 6 * 1024 * 1024 ? jpegForOcr(input) : input;
        try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Invalid OCR image");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 32768 || height > 32768 || (long) width * height > MAX_IMAGE_PIXELS)
                    throw new IOException("Invalid OCR dimensions");
                return new PreparedImage(bytes, sha256(bytes), width, height);
            } finally { reader.dispose(); }
        }
    }

    private ParsedDocument parseWord(Path path, ParseSession session) throws IOException {
        checkZipSafety(path);
        try (InputStream input = Files.newInputStream(path); XWPFDocument document = new XWPFDocument(input);
             OcrPipeline pipeline = new OcrPipeline(session, ocrConcurrency, ocrBytesBudget, ocrPixelsBudget)) {
            if (session.planned()) {
                session.mapping = true;
                appendWord(document, new WordBlocks(session, true));
                session.mapping = false;
                session.resolve();
            }
            WordBlocks output = new WordBlocks(session, false, pipeline);
            appendWord(document, output);
            output.finish();
            pipeline.finish();
            if (document.getAllPictures().stream().anyMatch(p -> !output.pictureParts.contains(p.getPackagePart().getPartName().getName()))) {
                output.warnings.add("[OCR未完成] Word 文件有未定位到正文块的图像，请对照原文件核对文本框等内容。");
            }
            if (!document.getFootnotes().isEmpty() || !document.getEndnotes().isEmpty()) {
                output.warnings.add("Word 文件含脚注或尾注，当前未纳入原生正文解析，请人工核对该部分内容。");
            }
            if (output.pages.isEmpty()) {
                String warning = "[OCR未完成] Word 文件未提取到可用文字，请对照原文件确认。";
                output.warnings.add(warning);
                Page page = new Page(1, "", "BLOCK", "POI_NATIVE", warning);
                output.pages.add(page);
                session.nativeLocation(1, "BLOCK", "", 0, 0);
                session.pageCompleted(page);
            }
            return new ParsedDocument(List.copyOf(output.pages), List.copyOf(output.warnings));
        }
    }

    private void appendWord(XWPFDocument document, WordBlocks output) throws IOException {
        output.totalBlocks = countBlocks(document.getBodyElements());
        for (var header : document.getHeaderList()) output.totalBlocks += countBlocks(header.getBodyElements());
        for (var footer : document.getFooterList()) output.totalBlocks += countBlocks(footer.getBodyElements());
        appendBody(document.getBodyElements(), output, 0);
        for (var header : document.getHeaderList()) appendBody(header.getBodyElements(), output, 0);
        for (var footer : document.getFooterList()) appendBody(footer.getBodyElements(), output, 0);
    }

    private static int countBlocks(List<IBodyElement> elements) {
        int count = 0;
        for (IBodyElement element : elements) {
            if (element instanceof XWPFTable table) {
                for (XWPFTableRow row : table.getRows()) {
                    count++;
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFTable nested : cell.getTables()) count += countBlocks(List.of(nested));
                    }
                }
            } else count++;
        }
        return count;
    }

    private static List<XWPFPicture> pictures(XWPFParagraph paragraph) {
        return paragraph.getRuns().stream().flatMap(run -> run.getEmbeddedPictures().stream()).toList();
    }

    private void appendBody(List<IBodyElement> elements, WordBlocks output, int depth) throws IOException {
        if (depth > 20) throw new DocumentParseException("Word 文档嵌套层级过深，无法安全解析。");
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                List<XWPFPicture> images = pictures(paragraph);
                WordParagraph content = wordParagraph(paragraph, images);
                String warning = "";
                if (content.incomplete()) {
                    warning = "包含文本框或修订内容，当前正文文本可能不完整，请人工核对。";
                }
                if (content.unresolvedImage()) warning += "[OCR未完成] 包含外链或旧式图像，无法定位识别，请对照原文件核对。";
                output.add(content.text(), images.isEmpty() ? "PARAGRAPH" : "BLOCK", warning, images, content.keepPosition());
            } else if (element instanceof XWPFTable table) {
                appendTable(table, output, depth + 1);
            } else if (element instanceof XWPFSDT control) {
                output.add(control.getContent().getText(), "BLOCK", "", List.of());
            } else {
                output.warnings.add("Word 文件包含未支持的内容块，请人工核对原文件。");
            }
        }
    }

    private void appendTable(XWPFTable table, WordBlocks output, int depth) throws IOException {
        if (depth > 20) throw new DocumentParseException("Word 文档表格嵌套层级过深，无法安全解析。");
        List<String> headers = List.of();
        for (int rowIndex = 0; rowIndex < table.getRows().size(); rowIndex++) {
            XWPFTableRow row = table.getRow(rowIndex);
            List<String> values = new ArrayList<>();
            List<XWPFPicture> images = new ArrayList<>();
            boolean nested = false;
            boolean incomplete = false;
            boolean unresolvedImage = false;
            boolean keepPosition = false;
            for (XWPFTableCell cell : row.getTableCells()) {
                // Keep cell paragraphs together; nested tables get their own blocks below.
                List<String> cellText = new ArrayList<>();
                for (XWPFParagraph paragraph : cell.getParagraphs()) {
                    List<XWPFPicture> paragraphImages = pictures(paragraph);
                    WordParagraph content = wordParagraph(paragraph, paragraphImages);
                    if (!content.text().isBlank()) cellText.add(content.text());
                    images.addAll(paragraphImages);
                    incomplete |= content.incomplete();
                    unresolvedImage |= content.unresolvedImage();
                    keepPosition |= content.keepPosition();
                }
                values.add(String.join(" / ", cellText));
                nested |= !cell.getTables().isEmpty();
            }
            if (rowIndex == 0 && looksLikeHeader(values, row.isRepeatHeader())) headers = List.copyOf(values);
            StringBuilder text = new StringBuilder(String.join("\t", values));
            if (rowIndex > 0 && headers.size() == values.size()) {
                for (int column = 0; column < headers.size(); column++) {
                    if (!headers.get(column).isBlank() && !values.get(column).isBlank()) {
                        text.append('\n').append(headers.get(column)).append('：').append(values.get(column));
                    }
                }
            }
            String warning = "";
            if (incomplete) warning += "包含文本框或修订内容，当前表格文本可能不完整，请人工核对。";
            if (unresolvedImage) warning += "[OCR未完成] 包含外链或旧式图像，无法定位识别，请对照原文件核对。";
            output.add(text.toString(), "BLOCK", warning, images, keepPosition);
            if (nested) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFTable nestedTable : cell.getTables()) appendTable(nestedTable, output, depth + 1);
                }
            }
        }
    }

    private static boolean looksLikeHeader(List<String> values, boolean explicitlyMarked) {
        if (explicitlyMarked) return true;
        long labels = values.stream().filter(value -> value.length() <= 40)
                .map(value -> value.replaceAll("[（(][^）)]*[）)]", "").replaceAll("\\s+", ""))
                .filter(value -> value.matches("(?i)(序号|编号|车牌号?码?|车牌|VIN|车辆识别代号|车辆识别代码|识别代号|发动机号?码?|所有人|车辆类型|登记日期|项目名称|项目编号|项目金额|合同名称|合同编号|合同金额|合同日期|签订日期|名称|金额|期限|日期|响应|要求|数量|单位|规格|型号|参数|内容|材料|字段|值|姓名|性别|年龄|职务|岗位|职称|学历|专业|证书名称|证书编号|资格证书|有效期|发证日期|企业名称|公司名称|注册地址|注册资本|成立日期|统一社会信用代码|开户银行|股权结构|邮编|网址|企业类型)")).count();
        return values.size() >= 2 && labels >= 2 && values.stream().noneMatch(value -> value.matches(".*[0-9]{3,}.*"));
    }

    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String VML_NS = "urn:schemas-microsoft-com:vml";
    private static final String MC_NS = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private record WordParagraph(String text, boolean incomplete, boolean unresolvedImage, boolean keepPosition) { }
    private static final class WordExtras {
        private final List<String> text = new ArrayList<>();
        private boolean incomplete, unresolvedImage;
        private int characters;
        private void add(String label, String value) throws IOException {
            value = normalize(value);
            if (value.isBlank()) return;
            characters += value.length() + label.length() + 2;
            if (characters > MAX_CHARACTERS) throw tooMuchText();
            text.add(label + "\n" + value);
        }
    }

    private static WordParagraph wordParagraph(XWPFParagraph paragraph, List<XWPFPicture> pictures) throws IOException {
        Set<String> handledImages = new HashSet<>();
        for (XWPFPicture image : pictures) {
            var fill = image.getCTPicture().getBlipFill();
            if (fill != null && fill.getBlip() != null) handledImages.add(fill.getBlip().getEmbed());
        }
        WordExtras extra = new WordExtras();
        inspectWordNode(paragraph.getCTP().getDomNode(), extra, handledImages, false, 0);
        String nativeText = normalize(paragraph.getText());
        // POI already exposes some plain VML boxes; supplement only the ones its paragraph API omitted.
        String supplemental = extra.text.stream().filter(value -> !nativeText.contains(value.substring(value.indexOf('\n') + 1)))
                .collect(Collectors.joining("\n\n"));
        String text = nativeText + (nativeText.isBlank() || supplemental.isBlank() ? "" : "\n\n") + supplemental;
        return new WordParagraph(text, extra.incomplete, extra.unresolvedImage, legacyWordPlaceholder(paragraph.getCTP().getDomNode(), 0));
    }

    // Earlier parser versions retained these blocks through a warning, even when their native content was empty.
    // Keep their slots (including unselected compatibility branches) so historical image links retain their targets.
    private static boolean legacyWordPlaceholder(Node node, int depth) throws IOException {
        if (depth > 80) throw new DocumentParseException("Word 附加文本层级过深，无法安全解析。");
        if (isNode(node, WORD_NS, "txbxContent") || isNode(node, VML_NS, "imagedata")) return true;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
            if (legacyWordPlaceholder(child, depth + 1)) return true;
        return false;
    }

    /** Read existing OOXML nodes only: never fetch external references or decode Office gfxdata archives. */
    private static void inspectWordNode(Node node, WordExtras extra, Set<String> handledImages, boolean inTextBox, int depth) throws IOException {
        if (depth > 80) throw new DocumentParseException("Word 附加文本层级过深，无法安全解析。");
        if (isNode(node, MC_NS, "AlternateContent")) {
            Node branch = alternateBranch(node);
            if (branch == null) extra.incomplete = true;
            else inspectWordNode(branch, extra, handledImages, inTextBox, depth + 1);
            return;
        }
        if (isNode(node, WORD_NS, "del") || isNode(node, WORD_NS, "ins")
                || isNode(node, WORD_NS, "moveFrom") || isNode(node, WORD_NS, "moveTo")) extra.incomplete = true;
        if (isNode(node, WORD_NS, "txbxContent")) {
            if (!inTextBox) {
                StringBuilder text = new StringBuilder();
                appendTextBoxText(node, text, 0);
                extra.add("【文本框原文】", text.toString());
            }
            inTextBox = true;
        }
        if (isNode(node, VML_NS, "textpath")) extra.add("【艺术字/水印原文】", attribute(node, "", "string"));
        if (isNode(node, VML_NS, "imagedata")) {
            // Word emits empty imagedata tags inside native text shapes; these have no image to OCR.
            for (String name : List.of("id", "embed", "link")) if (!attribute(node, REL_NS, name).isBlank()) extra.unresolvedImage = true;
            if (!attribute(node, "", "src").isBlank() || !attribute(node, "urn:schemas-microsoft-com:office:office", "relid").isBlank()) extra.unresolvedImage = true;
        }
        if (isNode(node, DRAWING_NS, "blip")) {
            String embed = attribute(node, REL_NS, "embed");
            if (!attribute(node, REL_NS, "link").isBlank() || (!embed.isBlank() && !handledImages.contains(embed))) extra.unresolvedImage = true;
        }
        if (inTextBox && (isNode(node, WORD_NS, "object") || "http://schemas.openxmlformats.org/officeDocument/2006/math".equals(node.getNamespaceURI()))) extra.incomplete = true;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) inspectWordNode(child, extra, handledImages, inTextBox, depth + 1);
    }

    private static void appendTextBoxText(Node node, StringBuilder text, int depth) throws IOException {
        if (depth > 80) throw new DocumentParseException("Word 文本框层级过深，无法安全解析。");
        if (isNode(node, MC_NS, "AlternateContent")) {
            Node branch = alternateBranch(node);
            if (branch != null) appendTextBoxText(branch, text, depth + 1);
            return;
        }
        if (isNode(node, WORD_NS, "del") || isNode(node, WORD_NS, "moveFrom")) return;
        if (isNode(node, WORD_NS, "t")) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
                if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) text.append(child.getNodeValue());
        } else if (isNode(node, WORD_NS, "tab")) text.append('\t');
        else if (isNode(node, WORD_NS, "br") || isNode(node, WORD_NS, "cr")) text.append('\n');
        else for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) appendTextBoxText(child, text, depth + 1);
        if (isNode(node, WORD_NS, "p")) text.append('\n');
        if (text.length() > MAX_CHARACTERS) throw tooMuchText();
    }

    private static Node alternateBranch(Node alternate) {
        Node fallback = null;
        for (Node branch = alternate.getFirstChild(); branch != null; branch = branch.getNextSibling()) {
            if (isNode(branch, MC_NS, "Fallback")) fallback = branch;
            if (!isNode(branch, MC_NS, "Choice")) continue;
            String requires = attribute(branch, "", "Requires");
            if (requires.isBlank()) continue;
            boolean supported = true;
            for (String prefix : requires.strip().split("\\s+")) {
                String namespace = namespaceFor(branch, prefix);
                if (!Set.of("http://schemas.microsoft.com/office/word/2010/wordprocessingShape", WORD_NS, DRAWING_NS).contains(namespace)) supported = false;
            }
            if (supported) return branch;
        }
        return fallback;
    }

    private static String namespaceFor(Node node, String prefix) {
        for (Node current = node; current != null; current = current.getParentNode()) {
            if (current.getAttributes() == null) continue;
            Node declaration = current.getAttributes().getNamedItem("xmlns:" + prefix);
            if (declaration != null) return declaration.getNodeValue();
        }
        return "";
    }
    private static boolean isNode(Node node, String namespace, String local) {
        return namespace.equals(node.getNamespaceURI()) && local.equals(node.getLocalName());
    }
    private static String attribute(Node node, String namespace, String local) {
        if (node.getAttributes() == null) return "";
        Node value = namespace.isEmpty() ? node.getAttributes().getNamedItem(local) : node.getAttributes().getNamedItemNS(namespace, local);
        return value == null ? "" : value.getNodeValue();
    }

    private ParsedDocument parseText(Path path, ParseSession session) throws IOException {
        StringBuilder rawText = new StringBuilder();
        int pageCount = 1;
        try (var reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                checkInterrupted();
                if ((long) rawText.length() + read > MAX_CHARACTERS + 1L) throw tooMuchText();
                for (int i = 0; i < read; i++) if (buffer[i] == '\f' && ++pageCount > MAX_PAGES) throw tooManyPages();
                rawText.append(buffer, 0, read);
            }
        }
        String text = rawText.toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.indexOf('\0') >= 0) throw new DocumentParseException("文本包含二进制内容，请上传有效的 UTF-8 文本文件。");
        if (text.length() > MAX_CHARACTERS) throw tooMuchText();
        String[] parts = text.split("\f", -1);
        if (parts.length > MAX_PAGES) throw tooManyPages();
        List<Page> pages = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            session.progress(i * 100 / parts.length, "正在解析文本第 " + (i + 1) + " 页");
            String content = normalize(parts[i]);
            String warning = content.isBlank() ? "第 " + (i + 1) + " 个文本分页没有内容。" : "";
            if (!warning.isBlank()) warnings.add(warning);
            Page page = new Page(i + 1, content, "PAGE", "UTF8_TEXT", warning);
            pages.add(page);
            session.nativeLocation(i + 1, "PAGE", content, 0, 0);
            session.pageCompleted(page);
        }
        if (session.planned()) session.resolve();
        return new ParsedDocument(List.copyOf(pages), List.copyOf(warnings));
    }

    private ParsedDocument parseImage(Path path, ParseSession session) throws IOException {
        // Validate and bound image decoding even when OCR is not configured.
        byte[] image = renderImage(path);
        session.nativeLocation(1, "IMAGE", "", 1, 1);
        if (session.planned()) session.resolve();
        OcrOutcome outcome;
        try (OcrPipeline pipeline = new OcrPipeline(session, ocrConcurrency, ocrBytesBudget, ocrPixelsBudget)) {
            outcome = pipeline.await(pipeline.submit("IMAGE", 1, 1, () -> image, "上传图像"), "上传图像");
            pipeline.finish();
        }
        String text = outcome.success ? withOcr("", outcome.text, "OCR识别文字") : "";
        if (text.length() > MAX_CHARACTERS) throw tooMuchText();
        String warning = "第 1 张图像" + outcome.warning;
        Page page = new Page(1, text, "IMAGE", outcome.success ? "IMAGE_OCR" : "IMAGE", warning,
                outcome.layout.source("", "UPLOADED_IMAGE", 1));
        session.pageCompleted(page);
        return new ParsedDocument(List.of(page), List.of(warning));
    }

    /** Safe source preview for standalone PNG/JPEG uploads; always returns a PNG. */
    public byte[] renderImage(Path path) throws IOException {
        validateFile(path);
        try (ImageInputStream input = new FileImageInputStream(path.toFile())) {
            BufferedImage image = readSafeImage(input);
            try { return png(image); } finally { image.flush(); }
        }
    }

    /** All embedded images in a Word content block, in their source order. No OCR is invoked. */
    public byte[] renderWordImage(Path path, int block) throws IOException {
        return renderWordImage(path, block, 0);
    }

    /** A one-based image index selects one image; zero produces a bounded contact sheet. */
    public byte[] renderWordImage(Path path, int block, int imageIndex) throws IOException {
        validateFile(path);
        if (block < 1 || imageIndex < 0) throw new DocumentParseException("Word 图片内容块或图片序号无效。");
        checkZipSafety(path);
        try (InputStream input = Files.newInputStream(path); XWPFDocument document = new XWPFDocument(input)) {
            WordBlocks output = new WordBlocks(new ParseSession(OcrClient.disabled(), null, MAX_OCR_IMAGES), true);
            appendWord(document, output);
            List<XWPFPicture> images = output.blockPictures.get(block);
            if (images == null || images.isEmpty() || imageIndex > images.size()) throw new DocumentParseException("此 Word 内容块没有对应图片，或图片序号超出范围。");
            if (imageIndex > 0) return normalizedPicture(images.get(imageIndex - 1));
            if (images.size() == 1) return normalizedPicture(images.get(0));
            if (images.size() > MAX_OCR_IMAGES) throw new DocumentParseException("此内容块图片超过 100 张，请按图片序号分别预览。");
            // Decode one image at a time into a bounded grid, rather than retaining all decoded rasters.
            int columns = Math.min(4, (int) Math.ceil(Math.sqrt(images.size())));
            int rows = (images.size() + columns - 1) / columns;
            int cellWidth = Math.min(900, 2400 / columns);
            int cellHeight = Math.min(900, 2400 / rows);
            double scale = Math.min(1d, Math.sqrt(4_000_000d / ((long) columns * cellWidth * rows * cellHeight)));
            cellWidth = Math.max(1, (int) (cellWidth * scale));
            cellHeight = Math.max(1, (int) (cellHeight * scale));
            BufferedImage sheet = new BufferedImage(columns * cellWidth, rows * cellHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = sheet.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                for (int i = 0; i < images.size(); i++) {
                    checkInterrupted();
                    try (ImageInputStream source = new MemoryCacheImageInputStream(new ByteArrayInputStream(pictureBytes(images.get(i))))) {
                        BufferedImage image = readSafeImage(source);
                        try {
                            int x = (i % columns) * cellWidth, y = (i / columns) * cellHeight;
                            double fit = Math.min((cellWidth - 8d) / image.getWidth(), (cellHeight - 24d) / image.getHeight());
                            graphics.setColor(Color.BLACK);
                            graphics.drawString("Image " + (i + 1), x + 4, y + 16);
                            graphics.drawImage(image, x + 4, y + 22, Math.max(1, (int) (image.getWidth() * fit)), Math.max(1, (int) (image.getHeight() * fit)), null);
                        } finally { image.flush(); }
                    }
                }
                return png(sheet);
            } finally { graphics.dispose(); sheet.flush(); }
        } catch (InterruptedIOException | DocumentParseException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("Word 图像损坏或无法安全预览，请对照原文件。", e);
        }
    }

    private static byte[] pictureBytes(XWPFPicture picture) throws IOException {
        var data = picture.getPictureData();
        if (data == null) throw new DocumentParseException("图片引用无效或位于外部链接，不能读取。");
        try (InputStream input = data.getPackagePart().getInputStream()) {
            byte[] bytes = input.readNBytes((int) MAX_ZIP_ENTRY_BYTES + 1);
            if (bytes.length > MAX_ZIP_ENTRY_BYTES) throw new DocumentParseException("Word 图片文件过大，无法安全解析。");
            return bytes;
        }
    }

    private static byte[] normalizedPicture(XWPFPicture picture) throws IOException {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(pictureBytes(picture)))) {
            BufferedImage image = readSafeImage(input);
            try { return png(image); } finally { image.flush(); }
        }
    }

    private static BufferedImage readSafeImage(ImageInputStream input) throws IOException {
        checkInterrupted();
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) throw new DocumentParseException("图像损坏或格式不受支持，仅支持 PNG、JPG 图片。");
        var reader = readers.next();
        try {
            String format = reader.getFormatName().toLowerCase(Locale.ROOT);
            if (!Set.of("png", "jpeg", "jpg").contains(format)) throw new DocumentParseException("嵌入图像格式不受支持，仅支持 PNG、JPG 图片。");
            reader.setInput(input, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > 32_768 || height > 32_768 || (long) width * height > MAX_IMAGE_PIXELS) {
                throw new DocumentParseException("图像像素超过安全限制（1600 万像素），请降低分辨率后上传。");
            }
            double scale = Math.min(1d, Math.min(2400d / Math.max(width, height), Math.sqrt(4_000_000d / ((long) width * height))));
            var parameters = reader.getDefaultReadParam();
            int sample = Math.max(1, (int) Math.floor(1d / scale));
            parameters.setSourceSubsampling(sample, sample, 0, 0);
            BufferedImage source = reader.read(0, parameters);
            if (source == null) throw new DocumentParseException("图像损坏，无法读取。");
            try {
                BufferedImage bounded = new BufferedImage(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = bounded.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, bounded.getWidth(), bounded.getHeight());
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(source, 0, 0, bounded.getWidth(), bounded.getHeight(), null);
                } finally { graphics.dispose(); }
                return bounded;
            } finally { source.flush(); }
        } catch (InterruptedIOException | DocumentParseException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("图像损坏或无法安全解码，请重新导出 PNG 或 JPG 图片。", e);
        } finally { reader.dispose(); }
    }

    private static byte[] png(BufferedImage image) throws IOException {
        checkInterrupted();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "PNG", output)) throw new DocumentParseException("无法生成图像预览。");
            return output.toByteArray();
        }
    }

    private static String withOcr(String nativeText, String recognized, String label) {
        return (nativeText.isBlank() ? "" : "【原生文字】\n" + nativeText + "\n\n") + "【" + label + "】\n" + recognized;
    }

    private static void validateFile(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) throw new DocumentParseException("文件不存在或不可读取。");
        long size = Files.size(path);
        if (size == 0) throw new DocumentParseException("文件为空，请上传有效文档。");
        if (size > MAX_INPUT_BYTES) throw new DocumentParseException("文件超过 " + DocumentLimits.MAX_FILE_MB + " MB 限制，请拆分后上传。");
    }

    private static String fileSha256(Path path) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; int read; long bytes = 0;
                while ((read = input.read(buffer)) != -1) {
                    checkInterrupted(); bytes += read;
                    if (bytes > MAX_INPUT_BYTES) throw new DocumentParseException("文件超过解析大小限制。");
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void validatePdf(PDDocument document) throws IOException {
        if (document.isEncrypted()) throw new DocumentParseException("PDF 已加密，请先解除密码保护后重新上传。");
        if (document.getNumberOfPages() < 1) throw new DocumentParseException("PDF 没有可解析的页面。");
        if (document.getNumberOfPages() > MAX_PDF_PAGES) throw new DocumentParseException("PDF 超过 1000 页限制，请拆分后上传。");
    }

    private static void checkZipSafety(Path path) throws IOException {
        byte[] signature;
        try (InputStream input = Files.newInputStream(path)) { signature = input.readNBytes(8); }
        if (signature.length >= 4 && (signature[0] & 0xff) == 0xd0 && (signature[1] & 0xff) == 0xcf) {
            throw new DocumentParseException("此文件可能是加密 Word 或旧版 DOC，请解除保护并另存为 DOCX 后上传。");
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.size() > MAX_ZIP_ENTRIES) throw new DocumentParseException("DOCX 内部文件数量过多，无法安全解析。");
            if (zip.getEntry("[Content_Types].xml") == null || zip.getEntry("word/document.xml") == null) {
                throw new DocumentParseException("文件不是有效的 DOCX 文档，请确认格式后重新上传。");
            }
            long total = 0;
            int[] xmlBudget = {0, 0};
            Set<String> entryNames = new HashSet<>();
            byte[] buffer = new byte[8192];
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkInterrupted();
                ZipEntry entry = entries.nextElement();
                if (!entryNames.add(entry.getName())) throw new DocumentParseException("DOCX 包含重复的内部文件，已拒绝解析。");
                if (entry.isDirectory()) continue;
                long expanded = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkInterrupted();
                        expanded += read;
                        total += read;
                        if (expanded > MAX_ZIP_ENTRY_BYTES || total > MAX_ZIP_TOTAL_BYTES) {
                            throw new DocumentParseException("DOCX 解压内容超过安全限制，请精简文档后上传。");
                        }
                        if (expanded > 1024 * 1024 && entry.getCompressedSize() >= 0
                                && entry.getCompressedSize() / (double) expanded < 0.01d) {
                            throw new DocumentParseException("DOCX 压缩比例异常，已停止解析以防止压缩炸弹。");
                        }
                    }
                }
                if (entry.getName().endsWith(".xml")) {
                    if (expanded > 8L * 1024 * 1024) throw new DocumentParseException("DOCX 单个 XML 内容过大，请精简后上传。");
                    try (InputStream input = zip.getInputStream(entry)) {
                        if (entry.getName().matches("customXml/item[1-9][0-9]*\\.xml")) {
                            checkWordXml(bibliographyValidationView(input), xmlBudget);
                        } else checkWordXml(input, xmlBudget);
                    }
                }
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    /** Word may export identical duplicate bibliography namespace declarations in unused custom metadata. */
    private static InputStream bibliographyValidationView(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(8 * 1024 * 1024 + 1);
        if (bytes.length > 8 * 1024 * 1024) throw new DocumentParseException("DOCX 单个 XML 内容过大，请精简后上传。");
        // ISO-8859-1 provides a byte-preserving view: only known ASCII attribute bytes are removed.
        String xml = new String(bytes, StandardCharsets.ISO_8859_1);
        var root = java.util.regex.Pattern.compile("(?s)\\A(?:\u00ef\u00bb\u00bf)?\\s*(?:<\\?xml[^?]*\\?>\\s*)?(<b:Sources\\b[^<>]*>)").matcher(xml);
        if (root.find()) {
            String tag = root.group(1);
            var declaration = java.util.regex.Pattern.compile("\\s+xmlns:b\\s*=\\s*(['\"])http://schemas\\.openxmlformats\\.org/officeDocument/2006/bibliography\\1").matcher(tag);
            long total = java.util.regex.Pattern.compile("\\s+xmlns:b\\s*=").matcher(tag).results().count();
            long identical = declaration.results().count();
            if (total > 1 && total == identical) {
                declaration.reset();
                StringBuilder deduplicated = new StringBuilder();
                boolean first = true;
                while (declaration.find()) {
                    declaration.appendReplacement(deduplicated, first ? java.util.regex.Matcher.quoteReplacement(declaration.group()) : "");
                    first = false;
                }
                declaration.appendTail(deduplicated);
                xml = xml.substring(0, root.start(1)) + deduplicated + xml.substring(root.end(1));
                bytes = xml.getBytes(StandardCharsets.ISO_8859_1);
            }
        }
        // All DTD, external entity, depth and element-budget checks still run on this validation-only view.
        return new ByteArrayInputStream(bytes);
    }

    private static DocumentParseException tooMuchText() {
        return new DocumentParseException("文档文字超过 200 万字符限制，请拆分后上传。");
    }

    private static DocumentParseException tooManyPages() {
        return new DocumentParseException("文档超过 500 页限制，请拆分后上传。");
    }

    private static final class WordBlocks {
        private final List<Page> pages = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Set<String> pictureParts = new HashSet<>();
        private final Map<Integer, List<XWPFPicture>> blockPictures = new HashMap<>();
        private final ParseSession session;
        private final boolean previewOnly;
        private final OcrPipeline pipeline;
        private final ArrayDeque<PendingWordBlock> pending = new ArrayDeque<>();
        private int blockCount;
        private int characters;
        private int visitedBlocks;
        private int totalBlocks = 1;

        private WordBlocks(ParseSession session, boolean previewOnly) {
            this(session, previewOnly, null);
        }
        private WordBlocks(ParseSession session, boolean previewOnly, OcrPipeline pipeline) {
            this.session = session;
            this.previewOnly = previewOnly;
            this.pipeline = pipeline;
        }

        private void add(String value, String location, String warning, List<XWPFPicture> images) throws IOException {
            add(value, location, warning, images, false);
        }

        private void add(String value, String location, String warning, List<XWPFPicture> images, boolean keepPosition) throws IOException {
            if (pipeline != null) pipeline.tick();
            session.workProgress(Math.min(99, visitedBlocks++ * 100 / Math.max(1, totalBlocks)), "正在解析 Word 内容块");
            String text = normalize(value);
            if (text.isBlank() && warning.isBlank() && images.isEmpty() && !keepPosition) return;
            if (blockCount >= MAX_WORD_BLOCKS) throw new DocumentParseException("Word 内容块超过 20000 个，请拆分后上传。");
            while (pending.size() >= 32) append(pending.removeFirst());
            int number = ++blockCount;
            session.nativeLocation(number, location, text, images.size(), images.size());
            if (previewOnly && !images.isEmpty()) blockPictures.put(number, List.copyOf(images));
            if (!warning.isBlank() && !images.isEmpty() && !warning.contains("[OCR未完成]")) warning = "[OCR未完成] 部分内容未纳入识别；" + warning;
            characters += text.length();
            if (characters > MAX_CHARACTERS) throw tooMuchText();
            List<Future<OcrOutcome>> recognitions = new ArrayList<>();
            for (int i = 0; i < images.size(); i++) {
                XWPFPicture picture = images.get(i);
                var data = picture.getPictureData();
                if (data != null) pictureParts.add(data.getPackagePart().getPartName().getName());
                if (previewOnly) continue;
                recognitions.add(pipeline.submit(location, number, i + 1, () -> normalizedPicture(picture), "Word 第 " + number + " 个内容块图片 " + (i + 1)));
            }
            pending.addLast(new PendingWordBlock(number, text, location, warning, List.copyOf(recognitions)));
            while (!pending.isEmpty() && pending.peekFirst().recognitions.stream().allMatch(Future::isDone)) append(pending.removeFirst());
        }

        private void finish() throws IOException { while (!pending.isEmpty()) append(pending.removeFirst()); }

        private void append(PendingWordBlock block) throws IOException {
            int number = block.number;
            String text = block.text, parser = "POI_NATIVE";
            StringBuilder recognized = new StringBuilder(), imageWarnings = new StringBuilder(block.warning);
            List<cn.bidcheck.ocr.OcrLayout> imageLayouts = new ArrayList<>();
            for (int i = 0; i < block.recognitions.size(); i++) {
                OcrOutcome outcome = pipeline.await(block.recognitions.get(i), "Word 第 " + number + " 个内容块图片 " + (i + 1));
                imageLayouts.add(outcome.layout.source("i" + (i + 1) + "_", "WORD_EMBEDDED", i + 1));
                if (!imageWarnings.isEmpty()) imageWarnings.append('\n');
                imageWarnings.append("图片 ").append(i + 1).append('：').append(outcome.warning);
                if (outcome.success) {
                    parser = "WORD_NATIVE_WITH_OCR";
                    if (!recognized.isEmpty()) recognized.append("\n\n");
                    if ((long) characters + recognized.length() + outcome.text.length() + 80 > MAX_CHARACTERS) throw tooMuchText();
                    recognized.append("【图片 ").append(i + 1).append(" OCR识别文字】\n").append(outcome.text);
                }
            }
            if (!recognized.isEmpty()) text = (text.isBlank() ? "" : "【原生文字】\n" + text + "\n\n") + recognized;
            String warning = imageWarnings.toString();
            characters += text.length() - block.text.length();
            if (characters > MAX_CHARACTERS) throw tooMuchText();
            String locatedWarning = warning.isBlank() ? "" : "第 " + number + " 个内容块" + warning;
            if (!locatedWarning.isBlank()) warnings.add(locatedWarning);
            cn.bidcheck.ocr.OcrLayout layout = block.recognitions.isEmpty() || previewOnly ? null
                    : cn.bidcheck.ocr.OcrLayout.merge(imageLayouts, !block.text.isBlank(), text);
            Page page = new Page(number, text, block.location, parser, locatedWarning, layout);
            pages.add(page);
            if (!previewOnly) session.pageCompleted(page);
            session.workProgress(Math.min(99, visitedBlocks * 100 / Math.max(1, totalBlocks)), "已解析 Word 第 " + number + " 个内容块");
        }
        private record PendingWordBlock(int number, String text, String location, String warning, List<Future<OcrOutcome>> recognitions) { }
    }

    @FunctionalInterface private interface ImageSource { byte[] read() throws IOException; }
    private record OcrOutcome(String text, String warning, boolean success, cn.bidcheck.ocr.OcrLayout layout) {
        private OcrOutcome(String text, String warning, boolean success) {
            this(text, warning, success, cn.bidcheck.ocr.OcrLayout.unavailable("未获得可用图像结构，请对照原图。"));
        }
    }

    /** Request-scoped state: a shared FileParser can safely parse separate documents concurrently. */
    private static final class ParseSession {
        private final OcrClient client;
        private final ProgressListener listener;
        private final int maximumImages;
        private final ExtractionPlan plan;
        private final String sourceHash;
        private final OcrUnitObserver observer;
        private final Map<String, ExtractionPlan.NativeLocation> nativeMap = new java.util.LinkedHashMap<>();
        private final Map<String, OcrOutcome> outcomes = new HashMap<>();
        private final Map<String, PageClassifier.Decision> routes = new java.util.LinkedHashMap<>();
        private final Set<String> completedPages = new HashSet<>();
        private final Map<String, PageClassifier.Decision> completedRoutes = new HashMap<>();
        private int completedCharacters;
        private ExtractionPlan.Resolved resolved;
        private boolean mapping;
        private int imageCount;
        private int percent;
        private long peakOcrBytes, peakOcrPixels;
        private int peakOcrConcurrency, restoredImages, deduplicatedImages, ocrCharacters;
        private int submittedBatches, peakBatchPages, peakBatchTokens;
        private long peakBatchBytes, peakBatchPixels;
        private Map<String, Object> pdfReport;

        private ParseSession(OcrClient client, ProgressListener listener, int maximumImages) {
            this(client, listener, maximumImages, ExtractionPlan.full(), "", OcrUnitObserver.noop());
        }

        private ParseSession(OcrClient client, ProgressListener listener, int maximumImages, ExtractionPlan plan, String sourceHash, OcrUnitObserver observer) {
            this.client = client;
            this.maximumImages = plan.mode() == ExtractionPlan.Mode.FULL ? Math.min(maximumImages, plan.maxSelectedImages()) : maximumImages;
            this.listener = listener == null ? (percent, stage) -> { } : listener;
            this.plan = plan;
            this.sourceHash = sourceHash;
            this.observer = observer;
        }

        private boolean planned() { return plan.mode() != ExtractionPlan.Mode.FULL; }
        private void nativeLocation(int position, String type, String text, int images, int units) throws InterruptedIOException {
            if (nativeMap.putIfAbsent(ExtractionPlan.locationKey(type, position), new ExtractionPlan.NativeLocation(position, type, text, images, units)) == null) {
                var route = PageClassifier.nativeOnly(text, images);
                routes.put(ExtractionPlan.unitKey(type, position, 1), route);
                checkInterrupted();
                try { observer.onClassified(type, position, route); }
                catch (IOException | RuntimeException failure) { throw new ObserverAborted(failure); }
                checkInterrupted();
            }
        }
        private void resolve() throws InterruptedIOException {
            resolved = plan.resolve(List.copyOf(nativeMap.values()), maximumImages);
            progress(40, "原生文档地图已完成，已确定本次识别与延期位置");
        }
        private void workProgress(int value, String stage) throws InterruptedIOException {
            progress(planned() ? (mapping ? value * 40 / 100 : 40 + value * 60 / 100) : value, stage);
        }
        private ExtractionPlan.Decision decision(String type, int position, int image) {
            if (!planned()) return new ExtractionPlan.Decision(true, "FULL_EXTRACTION", List.of("按完整识别策略处理"), List.of());
            if (resolved == null) throw new IllegalStateException("Native map must precede OCR selection");
            ExtractionPlan.Decision value = resolved.decisions().get(ExtractionPlan.unitKey(type, position, image));
            if (value == null) throw new IllegalStateException("OCR unit absent from native map");
            return value;
        }
        private OcrOutcome deferred(String type, int position, int image) throws InterruptedIOException {
            checkInterrupted();
            ExtractionPlan.Decision decision = decision(type, position, image);
            return decision.selected() ? null : new OcrOutcome("", "[OCR_DEFERRED][OCR未完成] " + String.join("；", decision.reasons()) + "（" + decision.code() + "）；图像内容尚未识别，不能据此判定材料缺失。", false);
        }
        private void completed(String type, int position, int image, OcrOutcome outcome) throws IOException {
            if (outcome != null) {
                if (outcomes.put(ExtractionPlan.unitKey(type, position, image), outcome) == null) {
                    ocrCharacters += outcome.text.length();
                    if (ocrCharacters > MAX_CHARACTERS) throw tooMuchText();
                }
                if (outcome.success) {
                    String key = ExtractionPlan.unitKey(type, position, image);
                    var location = nativeMap.get(ExtractionPlan.locationKey(type, position));
                    routes.put(key, PageClassifier.afterRecognition(routes.get(key),
                            (location == null ? "" : location.text()) + "\n" + outcome.text,
                            location == null ? 1 : location.imageCount()));
                }
            }
        }
        private void pageCompleted(Page page) throws IOException {
            checkInterrupted();
            String locationKey = ExtractionPlan.locationKey(page.locationType(), page.number());
            if (completedPages.contains(locationKey)) return;
            completedCharacters += page.text().length();
            if (completedCharacters > MAX_CHARACTERS) throw tooMuchText();
            var location = nativeMap.get(locationKey);
            var route = routes.getOrDefault(ExtractionPlan.unitKey(page.locationType(), page.number(), 1),
                    PageClassifier.nativeOnly(page.text(), location == null ? 0 : location.imageCount()));
            // A Word block can contain several independently recognized materials. Retain the
            // highest-priority material route; every image still has its own routing in the report.
            if (location != null) for (int image = 2; image <= location.ocrUnits(); image++) {
                var candidate = routes.get(ExtractionPlan.unitKey(page.locationType(), page.number(), image));
                if (candidate != null && !route.priority().equals("HIGH") && candidate.priority().equals("HIGH")) route = candidate;
            }
            try { observer.onPage(page, route); }
            catch (IOException | RuntimeException failure) { throw new ObserverAborted(failure); }
            completedPages.add(locationKey);
            completedRoutes.put(locationKey, route);
            checkInterrupted();
        }
        private OcrOutcome restore(Unit unit) {
            var candidate = restoreCandidate(unit);
            return candidate == null || candidate.isEmpty() ? null : validateRestored(candidate.get(), unit);
        }
        private java.util.Optional<OcrUnitObserver.Result> restoreCandidate(Unit unit) {
            java.util.Optional<OcrUnitObserver.Result> candidate;
            try { candidate = observer.restore(unit.type, unit.position, unit.index, unit.hash); }
            catch (IOException | RuntimeException failure) { throw new ObserverAborted(failure); }
            return candidate;
        }
        private static OcrOutcome validateRestored(OcrUnitObserver.Result result, Unit unit) {
            // Persisted content is still untrusted input: check shape, transcription and source
            // geometry before it can bypass a fresh recognition call.
            try {
                String text = result.text(), warning = result.warning();
                if (text == null || text.isBlank() || text.length() > 50_000 || text.indexOf('\0') >= 0 || pageNumberOnly(text) || !OcrEvidenceGuard.code(text).isEmpty()
                        || warning == null || warning.length() > 4_000 || warning.indexOf('\0') >= 0 || warning.contains("[OCR未完成]")
                        || warning.contains("[OCR_DEFERRED]") || result.layout() == null) return null;
                var layout = result.layout().validated(text);
                for (var frame : layout.frames()) {
                    if (frame.crop() == null) {
                        if (!unit.hash.equals(frame.imageHash()) || unit.width != frame.width() || unit.height != frame.height()) return null;
                    } else if (frame.parentWidth() == null || frame.parentWidth() != unit.width || frame.parentHeight() != unit.height) return null;
                }
                return new OcrOutcome(text, warning, true, layout);
            } catch (IllegalArgumentException | NullPointerException invalid) { return null; }
        }
        private void observe(Unit unit, String state, OcrOutcome outcome, long elapsed) throws InterruptedIOException {
            checkInterrupted();
            try {
                observer.onState(new OcrUnitObserver.Event(unit.type, unit.position, unit.index, state, unit.hash,
                        outcome == null ? "" : outcome.text, outcome == null ? null : outcome.layout,
                        outcome == null ? "" : outcome.warning, elapsed, unit.cached, unit.width, unit.height, unit.bytes));
            } catch (IOException | RuntimeException failure) { throw new ObserverAborted(failure); }
            checkInterrupted();
        }
        private void observeBatch(OcrBatch.Event event) throws InterruptedIOException {
            checkInterrupted();
            try { observer.onBatch(event); }
            catch (IOException | RuntimeException failure) { throw new ObserverAborted(failure); }
            checkInterrupted();
        }
        private Map<String, Object> report(ParsedDocument document) {
            var report = new java.util.LinkedHashMap<String, Object>(plan.describe(document.pages()));
            report.put("actualSourceSha256", sourceHash);
            report.put("sourceBoundPlanHash", plan.sourceBoundHash(sourceHash));
            report.put("parserImageBudget", maximumImages);
            report.put("parserPolicyVersion", POLICY_VERSION);
            report.put("pageRoutingVersion", PageClassifier.VERSION);
            report.put("completedPageNotifications", completedPages.size());
            if (pdfReport != null) report.put("pdfOcrScheduler", pdfReport);
            report.put("ocrPipeline", Map.of("submittedImages", imageCount, "restoredImages", restoredImages,
                    "deduplicatedImages", deduplicatedImages, "peakActiveBytes", peakOcrBytes,
                    "peakActivePixels", peakOcrPixels, "peakConcurrency", peakOcrConcurrency, "maximumPendingLocations", 32));
            report.put("ocrBatchScheduler", Map.of("enabled", batchPolicy(client).enabled(), "submittedBatches", submittedBatches,
                    "peakBatchPages", peakBatchPages, "peakBatchBytes", peakBatchBytes, "peakBatchPixels", peakBatchPixels,
                    "peakBatchEstimatedTokens", peakBatchTokens, "maxPages", batchPolicy(client).maxPages(),
                    "maxWaitMillis", batchPolicy(client).maxWaitMillis(), "completionQueueCapacity", 256));
            List<Map<String, Object>> locations = new ArrayList<>(), selected = new ArrayList<>(), deferred = new ArrayList<>();
            long recognized = 0, failed = 0;
            for (var location : nativeMap.values()) {
                String pageClass = location.imageCount() > 0 ? (location.text().isBlank() ? "SCAN_PAGE" : "MIXED_PAGE")
                        : location.text().isBlank() ? "UNKNOWN_PAGE" : "TEXT_PAGE";
                var locationReport = new java.util.LinkedHashMap<String, Object>();
                locationReport.put("position", location.position()); locationReport.put("locationType", location.locationType());
                locationReport.put("nativeCharacters", location.text().length()); locationReport.put("imageCount", location.imageCount());
                locationReport.put("ocrUnits", location.ocrUnits()); locationReport.put("pageClass", pageClass);
                routeFields(locationReport, completedRoutes.getOrDefault(ExtractionPlan.locationKey(location.locationType(), location.position()),
                        routes.getOrDefault(ExtractionPlan.unitKey(location.locationType(), location.position(), 1), PageClassifier.nativeOnly(location.text(), location.imageCount()))));
                locations.add(locationReport);
                for (int index = 1; index <= location.ocrUnits(); index++) {
                    var decision = decision(location.locationType(), location.position(), index);
                    OcrOutcome outcome = outcomes.get(ExtractionPlan.unitKey(location.locationType(), location.position(), index));
                    var unit = new java.util.LinkedHashMap<String, Object>();
                    unit.put("position", location.position()); unit.put("locationType", location.locationType()); unit.put("imageIndex", index);
                    routeFields(unit, routes.getOrDefault(ExtractionPlan.unitKey(location.locationType(), location.position(), index), PageClassifier.nativeOnly(location.text(), location.imageCount())));
                    unit.put("code", decision.code()); unit.put("reasons", decision.reasons()); unit.put("checkpointIds", decision.checkpointIds());
                    boolean isDeferred = !decision.selected() || outcome != null && outcome.warning.contains("[OCR_DEFERRED]");
                    String state = isDeferred ? "DEFERRED" : outcome == null ? "NOT_COMPLETED" : outcome.success ? (outcome.warning.contains("[OCR未完成]") ? "PARTIAL" : "RECOGNIZED") : "FAILED";
                    unit.put("outcome", state);
                    if (!isDeferred) { selected.add(unit); if (outcome != null && outcome.success) recognized++; if (!state.equals("RECOGNIZED")) failed++; }
                    else deferred.add(unit);
                }
            }
            report.put("nativeMap", List.copyOf(locations));
            report.put("selectedPositions", List.copyOf(selected)); report.put("deferredPositions", List.copyOf(deferred));
            report.put("selectedImageCount", selected.size()); report.put("deferredImageCount", deferred.size());
            report.put("recognizedImageCount", recognized); report.put("failedImageCount", failed);
            var unresolved = resolved == null ? List.of() : resolved.unresolvedSelections();
            report.put("unresolvedSelections", unresolved); report.put("allPositionsTraversed", true);
            report.put("ocrCoverageComplete", deferred.isEmpty() && failed == 0 && unresolved.isEmpty()
                    && document.warnings().stream().noneMatch(w -> w.contains("[OCR未完成]")));
            return report;
        }

        private static void routeFields(Map<String, Object> output, PageClassifier.Decision route) {
            output.put("pageType", route.pageType()); output.put("parserStrategy", route.parserStrategy()); output.put("schema", route.schema());
            output.put("priority", route.priority()); output.put("requiresCrossPageContext", route.requiresCrossPageContext());
            output.put("resolutionBucket", route.resolutionBucket()); output.put("independent", route.independent());
        }

        private void progress(int value, String stage) throws InterruptedIOException {
            checkInterrupted();
            percent = Math.max(percent, Math.max(0, Math.min(100, value)));
            try { listener.update(percent, stage); }
            catch (RuntimeException e) { throw new ProgressAborted(e); }
            checkInterrupted();
        }

        private OcrOutcome result(cn.bidcheck.ocr.OcrResult result, byte[] image, String location) throws IOException {
            checkInterrupted();
            String text = result == null ? "" : normalize(result.text());
            if (text.isBlank()) return new OcrOutcome("", "[OCR未完成] OCR未识别到可用文字，请对照原图确认。", false);
            if (text.length() > 50_000 || text.indexOf('\0') >= 0) return new OcrOutcome("", "[OCR未完成] OCR输出超限或格式异常，请对照原图确认。", false);
            String evidenceCode = OcrEvidenceGuard.code(text);
            if (!evidenceCode.isEmpty()) return generatedEvidence(evidenceCode);
            if (pageNumberOnly(text)) return recognizeSparseImage(image, text, location);
            var layout = validatedLayout(result, text);
            return new OcrOutcome(text, "[OCR已识别] 模型：" + safeModelName(client.modelName()) + "；" + OCR_SUCCESS_WARNING + "。" + layoutWarning(layout), true, layout);
        }

        /** A page number does not establish that a scanned page has no body. At most two extra calls;
         * crops retain overlap as separately labelled excerpts, never a reconstructed table or total. */
        private OcrOutcome recognizeSparseImage(byte[] bytes, String original, String location) throws IOException {
            StringBuilder recovered = new StringBuilder();
            List<cn.bidcheck.ocr.OcrLayout> layouts = new ArrayList<>();
            int missing = 0;
            try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                BufferedImage image = readSafeImage(input);
                try {
                    int height = image.getHeight(), middle = height / 2, overlap = Math.max(1, height / 20);
                    if (height < 4) return sparseIncomplete();
                    for (int half = 0; half < 2; half++) {
                        checkInterrupted();
                        progress(percent, "正在分区补识别" + location + "（" + (half + 1) + "/2）");
                        int top = half == 0 ? 0 : Math.max(0, middle - overlap);
                        int bottom = half == 0 ? Math.min(height, middle + overlap) : height;
                        BufferedImage crop = image.getSubimage(0, top, image.getWidth(), bottom - top);
                        try {
                            byte[] encoded = png(crop);
                            String mime = "image/png";
                            if (encoded.length > 6 * 1024 * 1024) { encoded = jpegForOcr(encoded); mime = "image/jpeg"; }
                            var response = client.recognize(encoded, mime);
                            checkInterrupted();
                            String text = response == null ? "" : normalize(response.text());
                            String evidenceCode = OcrEvidenceGuard.code(text);
                            if (!evidenceCode.isEmpty()) return generatedEvidence(evidenceCode);
                            if (text.isBlank() || pageNumberOnly(text) || text.length() > 50_000 || text.indexOf('\0') >= 0) missing++;
                            else {
                                if ((long) original.length() + recovered.length() + text.length() + 160 > 50_000) return sparseIncomplete();
                                if (!recovered.isEmpty()) recovered.append("\n\n");
                                recovered.append(half == 0 ? "【上半幅 OCR，边界有重叠】\n" : "【下半幅 OCR，边界有重叠】\n").append(text);
                                layouts.add(validatedLayout(response, text).crop(image.getWidth(), height,
                                        new cn.bidcheck.ocr.OcrLayout.Box(0, top, image.getWidth(), bottom - top))
                                        .source("crop" + (half + 1) + "_", "IMAGE_CROP", half + 1));
                            }
                        } catch (InterruptedIOException interrupted) { throw interrupted; }
                        catch (IOException | RuntimeException failure) { missing++; }
                        finally { crop.flush(); }
                    }
                } finally { image.flush(); }
            }
            if (recovered.isEmpty()) return sparseIncomplete();
            String warning = "[OCR已识别] 整页仅识别到页码，已补识别上下分区；边界可能重复，不能直接合计或恢复表格关系。模型："
                    + safeModelName(client.modelName()) + "；" + OCR_SUCCESS_WARNING + "。";
            if (missing > 0) warning += "[OCR未完成] " + missing + " 个分区未识别到正文，请核对原图。";
            String text = "【整页初次识别】\n" + original + "\n\n" + recovered;
            var layout = cn.bidcheck.ocr.OcrLayout.merge(layouts, true, text);
            return new OcrOutcome(text, warning + layoutWarning(layout), true, layout);
        }

        private static OcrOutcome sparseIncomplete() {
            return new OcrOutcome("", "[OCR未完成] 仅识别到页码，分区补识别仍未获得完整正文，请对照原图核实。", false);
        }

        private static OcrOutcome generatedEvidence(String code) {
            return new OcrOutcome("", "[OCR未完成][" + code + "] 识别结果包含疑似模型生成说明，未作为原文使用，请对照原页核实或重新识别。", false);
        }

        private OcrOutcome reserveImage(String location) throws InterruptedIOException {
            return reserveImage(location, client.enabled());
        }
        private OcrOutcome reserveImage(String location, boolean enabled) throws InterruptedIOException {
            progress(percent, "正在处理" + location);
            if (!enabled) return new OcrOutcome("", "[OCR未完成] 图像识别未启用，请对照原图核对。", false);
            if (imageCount >= maximumImages) return new OcrOutcome("", "[OCR_DEFERRED][OCR未完成] 本次图像数量超限，最多新识别 " + maximumImages + " 张图像；已保留此位置，可继续识别。", false);
            imageCount++;
            return null;
        }
    }

    private static cn.bidcheck.ocr.OcrLayout validatedLayout(cn.bidcheck.ocr.OcrResult result, String text) {
        if (result != null && result.layout() != null) try { return result.layout().validated(text); }
        catch (IllegalArgumentException invalid) { /* Preserve useful text even if an adapter supplies invalid geometry. */ }
        return cn.bidcheck.ocr.OcrLayout.unavailable("未获得通过来源校验的图像结构；转录文字仍可用。");
    }

    private static String layoutWarning(cn.bidcheck.ocr.OcrLayout layout) {
        if ("COMPLETE".equals(layout.status())) return "";
        String diagnostic = layout.warnings().stream().map(note -> {
            var code = java.util.regex.Pattern.compile("\\[LAYOUT_[A-Z0-9_]{1,48}\\]").matcher(note);
            return code.find() ? code.group() : "";
        }).filter(code -> !code.isEmpty()).distinct().limit(3).collect(Collectors.joining(" "));
        return "[OCR结构未完成]" + (diagnostic.isEmpty() ? "" : " " + diagnostic) + " 转录已保留，部分文字或表格关系没有可靠布局，请对照原图。";
    }

    private static boolean pageNumberOnly(String text) {
        String compact = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}\\p{Punct}—－·]", "");
        return compact.matches("(?:第)?[0-9]{1,6}(?:页)?(?:(?:共)?[0-9]{1,6}页?)?");
    }

    private static OcrOutcome ocrFailed() {
        return new OcrOutcome("", "[OCR未完成] OCR失败：图像无法安全读取或识别服务未完成，请对照原图核对。", false);
    }

    private static String safeModelName(String value) {
        String model = value == null ? "未知" : value.replaceAll("[\\p{Cntrl}\\[\\]]", "").strip();
        return model.isBlank() ? "未知" : model.substring(0, Math.min(120, model.length()));
    }

    private static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static byte[] jpegForOcr(byte[] png) throws IOException {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            BufferedImage image = readSafeImage(input);
            var writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) { image.flush(); throw new DocumentParseException("无法压缩 OCR 图像。"); }
            var writer = writers.next();
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                 var output = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(output);
                var parameters = writer.getDefaultWriteParam();
                parameters.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(0.9f);
                writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
                output.flush();
                if (bytes.size() > 6 * 1024 * 1024) throw new DocumentParseException("OCR 图像压缩后仍超过安全限制。");
                return bytes.toByteArray();
            } finally { writer.dispose(); image.flush(); }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw interrupted();
    }
    private static InterruptedIOException interrupted() { return new InterruptedIOException("文档解析已中断。"); }
    private static final class PdfInterrupted extends RuntimeException { }
    private static final class ProgressAborted extends RuntimeException {
        private final RuntimeException original;
        private ProgressAborted(RuntimeException original) { this.original = original; }
    }
    private static final class ObserverAborted extends RuntimeException {
        private final Exception original;
        private ObserverAborted(Exception original) { this.original = original; }
    }

    private static final class NativePdfStripper extends PDFTextStripper {
        private boolean hasImage;
        private int imageCount;
        private int glyphs;
        private int operators;
        private int formDepth;
        private NativePdfStripper() throws IOException { super(); }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new PdfInterrupted();
            if (++operators > MAX_PDF_OPERATORS_PER_PAGE) throw new PdfBudgetExceeded();
            if ("BI".equals(operator.getName())) { hasImage = true; imageCount++; }
            if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName name
                    && getResources() != null && getResources().getXObject(name) instanceof PDImageXObject) {
                hasImage = true;
                imageCount++;
            }
            super.processOperator(operator, operands);
        }

        @Override protected void processTextPosition(TextPosition text) {
            glyphs += Math.max(1, text.getUnicode() == null ? 0 : text.getUnicode().length());
            if (glyphs > MAX_PDF_GLYPHS_PER_PAGE) throw new PdfBudgetExceeded();
            super.processTextPosition(text);
        }

        @Override public void showForm(PDFormXObject form) throws IOException {
            if (++formDepth > 20) throw new PdfBudgetExceeded();
            try { super.showForm(form); } finally { formDepth--; }
        }
    }

    private static void checkWordXml(InputStream input, int[] budget) throws IOException {
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new javax.xml.stream.XMLStreamException("External entities disabled"); });
            var reader = factory.createXMLStreamReader(input);
            int depth = 0;
            try {
                while (reader.hasNext()) {
                    checkInterrupted();
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) throw new DocumentParseException("DOCX 不允许包含 DTD 或外部实体。");
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (++depth > 64 || ++budget[0] > 200_000) throw new DocumentParseException("DOCX 结构过于复杂，请拆分后上传。");
                        if (Set.of("p", "tr").contains(reader.getLocalName()) && ++budget[1] > MAX_WORD_BLOCKS) throw new DocumentParseException("Word 内容块过多，请拆分后上传。");
                    } else if (event == XMLStreamConstants.END_ELEMENT) depth--;
                }
            } finally { reader.close(); }
        } catch (InterruptedIOException | DocumentParseException e) { throw e; }
        catch (Exception e) { throw new DocumentParseException("DOCX XML 内容损坏或包含不安全结构。"); }
    }

    private static final class PdfStreamBudget {
        private final Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        private long total;
        private void checkPage(PDPage page) throws IOException {
            var streams = page.getContentStreams();
            while (streams.hasNext()) checkStream(streams.next());
            checkResources(page.getResources(), 0);
        }
        private void checkStream(PDStream stream) throws IOException {
            if (!visited.add(stream.getCOSObject())) return;
            long size = 0;
            List<COSName> filters = stream.getFilters();
            if (filters != null && filters.size() > 4) throw new DocumentParseException("PDF 压缩过滤链过长，无法安全解析。");
            // The usual Flate content stream is checked incrementally before PDFBox can cache its decoded bytes.
            // Other supported filters use the document's disk-backed scratch cache and the same processing limit.
            InputStream decoded;
            if (filters == null || filters.isEmpty()) decoded = stream.getCOSObject().createRawInputStream();
            else if (filters.size() == 1 && Set.of("FlateDecode", "Fl").contains(filters.get(0).getName())) {
                decoded = new java.util.zip.InflaterInputStream(stream.getCOSObject().createRawInputStream());
            } else decoded = stream.createInputStream();
            try (InputStream input = decoded) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkInterrupted();
                    size += read;
                    total += read;
                    if (size > MAX_PDF_STREAM_BYTES || total > MAX_PDF_TOTAL_STREAM_BYTES) throw new DocumentParseException("PDF 解压内容超过安全限制，请拆分或重新导出。");
                }
            }
        }
        private void checkResources(PDResources resources, int depth) throws IOException {
            if (resources == null || !visited.add(resources.getCOSObject())) return;
            if (depth > 20 || visited.size() > 10_000) throw new DocumentParseException("PDF 资源嵌套过深或数量过多。");
            for (COSName name : resources.getXObjectNames()) {
                var object = resources.getXObject(name);
                if (object instanceof PDFormXObject form) {
                    checkStream(form.getContentStream());
                    checkResources(form.getResources(), depth + 1);
                }
            }
        }
    }

    private static final class SafePdfRenderer extends PDFRenderer {
        private SafePdfRenderer(PDDocument document) { super(document); }
        @Override protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new PageDrawer(parameters) {
                private long imagePixels;
                private int operators;
                private int formDepth;
                @Override public void showAnnotation(org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation annotation) throws IOException {
                    if (annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link
                            && !annotation.getCOSObject().containsKey(COSName.AP)) {
                        if (invisibleNavigationLink(link)) return;
                        // Keep visible link borders. Generate only their missing appearance on an
                        // isolated memory document; the parsed source and its disk streams stay untouched.
                        try (PDDocument appearanceOwner = new PDDocument()) {
                            var copy = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink(
                                    new org.apache.pdfbox.cos.COSDictionary(annotation.getCOSObject()));
                            copy.constructAppearances(appearanceOwner);
                            if (copy.getNormalAppearanceStream() == null) throw new IOException("PDF 链接外观无法安全生成。");
                            super.showAnnotation(copy);
                        }
                        return;
                    }
                    super.showAnnotation(annotation);
                }
                @Override public void drawImage(PDImage image) throws IOException {
                    long pixels = (long) image.getWidth() * image.getHeight();
                    imagePixels += pixels;
                    // PDFBox can swallow operator IOExceptions and render a misleading blank page. Abort outside that recovery path.
                    if (pixels < 1 || pixels > MAX_PDF_JPEG_PIXELS || imagePixels > 32_000_000L) throw new PdfBudgetExceeded();
                    if (pixels > MAX_IMAGE_PIXELS) validateLargePdfJpeg(image);
                    super.drawImage(image);
                }
                @Override protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new PdfInterrupted();
                    if (++operators > MAX_PDF_OPERATORS_PER_PAGE) throw new PdfBudgetExceeded();
                    super.processOperator(operator, operands);
                }
                @Override public void showForm(PDFormXObject form) throws IOException {
                    if (++formDepth > 20) throw new PdfBudgetExceeded();
                    try { super.showForm(form); } finally { formDepth--; }
                }
            };
        }
    }

    private static boolean invisibleNavigationLink(org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link) {
        var style = link.getBorderStyle();
        if (style != null) return style.getWidth() == 0;
        var border = link.getBorder();
        return border != null && border.size() >= 3 && border.getObject(2) instanceof org.apache.pdfbox.cos.COSNumber width && width.floatValue() == 0;
    }

    /** Permit common high-resolution JPEG scans, while retaining the stricter bound for all other rasters. */
    private static void validateLargePdfJpeg(PDImage image) throws IOException {
        if (!(image instanceof PDImageXObject jpeg) || image.getBitsPerComponent() != 8
                || jpeg.getCOSObject().containsKey(COSName.SMASK) || jpeg.getCOSObject().containsKey(COSName.MASK)) {
            throw new PdfBudgetExceeded();
        }
        COSBase color = jpeg.getCOSObject().getDictionaryObject(COSName.COLORSPACE);
        if (!(color instanceof COSName name) || !Set.of("DeviceRGB", "DeviceGray").contains(name.getName())) throw new PdfBudgetExceeded();
        List<COSName> filters = jpeg.getStream().getFilters();
        if (filters == null || filters.size() != 1 || !Set.of("DCTDecode", "DCT").contains(filters.get(0).getName())) throw new PdfBudgetExceeded();
        // Inspect the actual JPEG header before PDFBox allocates a raster. A forged PDF /Width is insufficient.
        byte[] header;
        try (InputStream raw = jpeg.getCOSObject().createRawInputStream()) { header = raw.readNBytes(1024 * 1024); }
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(header))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new PdfBudgetExceeded();
            var reader = readers.next();
            try {
                if (!Set.of("jpeg", "jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw new PdfBudgetExceeded();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width != image.getWidth() || height != image.getHeight() || width > 8192 || height > 8192
                        || width < 1 || height < 1 || (long) width * height > MAX_PDF_JPEG_PIXELS) throw new PdfBudgetExceeded();
            } finally { reader.dispose(); }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // PDFBox recovers from IOExceptions inside image operators; do not let a rejected scan look blank but valid.
            throw new PdfBudgetExceeded();
        }
    }

    private static final class PdfBudgetExceeded extends RuntimeException { }

    private static final class BoundedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int maximum;
        private BoundedWriter(int maximum) { this.maximum = maximum; }
        @Override public void write(char[] value, int offset, int length) throws IOException {
            if ((long) text.length() + length > maximum) throw tooMuchText();
            text.append(value, offset, length);
        }
        @Override public void flush() { }
        @Override public void close() { }
        public int length() { return text.length(); }
        @Override public String toString() { return text.toString(); }
    }

    private static final class DocumentParseException extends IOException {
        private DocumentParseException(String message) { super(message); }
        private DocumentParseException(String message, Throwable cause) { super(message, cause); }
    }
}
