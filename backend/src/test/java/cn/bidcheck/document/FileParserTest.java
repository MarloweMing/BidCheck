package cn.bidcheck.document;

import cn.bidcheck.ocr.OcrClient;
import cn.bidcheck.ocr.OcrResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FileParserTest {
    @TempDir Path temporary;
    private final FileParser parser = new FileParser();

    @Test void keepsRealPdfPagesSeparateAndRendersRequestedPage() throws Exception {
        Path path = temporary.resolve("evidence.pdf");
        try (PDDocument pdf = new PDDocument()) {
            addTextPage(pdf, "First evidence: A-001");
            addTextPage(pdf, "Second evidence: B-002");
            pdf.save(path.toFile());
        }
        cn.bidcheck.domain.Domain.ParsedDocument parsed = parser.parse(path, "evidence.PDF");
        assertEquals(2, parsed.pages().size());
        assertEquals(1, parsed.pages().get(0).number());
        assertEquals(2, parsed.pages().get(1).number());
        assertEquals("PAGE", parsed.pages().get(1).locationType());
        assertTrue(parsed.pages().get(0).text().contains("A-001"));
        assertFalse(parsed.pages().get(0).text().contains("B-002"));
        assertTrue(parsed.pages().get(1).text().contains("B-002"));
        assertTrue(parsed.warnings().isEmpty());
        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(parser.renderPdfPage(path, 2)));
        assertNotNull(preview);
        assertTrue(preview.getWidth() > 100);
        assertTrue(preview.getWidth() <= 2400 && preview.getHeight() <= 2400);
        assertThrows(IOException.class, () -> parser.renderPdfPage(path, 3));
    }

    @Test void warnsOnScannedAndMixedImagePdfPages() throws Exception {
        Path path = temporary.resolve("scanned.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage scanned = new PDPage();
            pdf.addPage(scanned);
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image = LosslessFactory.createFromImage(pdf, new BufferedImage(30, 30, BufferedImage.TYPE_INT_RGB));
            try (PDPageContentStream content = new PDPageContentStream(pdf, scanned)) { content.drawImage(image, 30, 50); }
            addTextPage(pdf, "Native text beside an image");
            try (PDPageContentStream content = new PDPageContentStream(pdf, pdf.getPage(1),
                    PDPageContentStream.AppendMode.APPEND, true)) { content.drawImage(image, 30, 50); }
            pdf.save(path.toFile());
        }
        cn.bidcheck.domain.Domain.ParsedDocument parsed = parser.parse(path, "scanned.pdf");
        assertTrue(cn.bidcheck.compat.Java8Api.isBlank(parsed.pages().get(0).text()));
        assertTrue(parsed.pages().get(0).warning().contains("OCR"));
        assertTrue(parsed.pages().get(1).warning().contains("图像"));
        assertEquals(2, parsed.warnings().size());
    }

    @Test void extractsWordParagraphsAndTableHeaderMappingsWithoutInventingPages() throws Exception {
        Path path = temporary.resolve("vehicles.docx");
        try (XWPFDocument word = new XWPFDocument()) {
            word.createParagraph().createRun().setText("项目名称：车辆租赁项目");
            XWPFTable table = word.createTable(3, 3);
            table.getRow(0).getCell(0).setText("车牌号");
            table.getRow(0).getCell(1).setText("VIN");
            table.getRow(0).getCell(2).setText("所有人");
            table.getRow(1).getCell(0).setText("粤A12345");
            table.getRow(1).getCell(1).setText("LSGJA52U2BS000001");
            table.getRow(1).getCell(2).setText("甲公司");
            table.getRow(2).getCell(0).setText("粤B67890");
            table.getRow(2).getCell(1).setText("LSGJA52U2BS000002");
            table.getRow(2).getCell(2).setText("乙公司");
            try (java.io.OutputStream stream = Files.newOutputStream(path)) { word.write(stream); }
        }
        cn.bidcheck.domain.Domain.ParsedDocument parsed = parser.parse(path, "vehicles.docx");
        assertEquals(4, parsed.pages().size());
        assertEquals("PARAGRAPH", parsed.pages().get(0).locationType());
        cn.bidcheck.domain.Domain.Page row = parsed.pages().get(2);
        assertEquals("BLOCK", row.locationType());
        assertEquals(3, row.number());
        assertTrue(row.text().contains("粤A12345\tLSGJA52U2BS000001\t甲公司"));
        assertTrue(row.text().contains("车牌号：粤A12345"));
        assertTrue(row.text().contains("VIN：LSGJA52U2BS000001"));
        assertFalse(row.text().contains("粤B67890"));
        assertTrue(parsed.pages().stream().noneMatch(page -> page.locationType().equals("PAGE")));
    }

    @Test void preservesUtf8FormFeedPagesAndRejectsMalformedEncoding() throws Exception {
        Path path = temporary.resolve("bid.txt");
        cn.bidcheck.compat.Java8Api.writeString(path, "\uFEFF投标有效期：90天\r\n证据一\f车牌号：粤A12345\f", StandardCharsets.UTF_8);
        cn.bidcheck.domain.Domain.ParsedDocument parsed = parser.parse(path, "bid.txt");
        assertEquals(3, parsed.pages().size());
        assertEquals("投标有效期：90天\n证据一", parsed.pages().get(0).text());
        assertEquals("车牌号：粤A12345", parsed.pages().get(1).text());
        assertEquals(3, parsed.pages().get(2).number());
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(parsed.pages().get(2).warning()));
        Files.write(path, new byte[]{(byte) 0xc3, 0x28});
        assertTrue(assertThrows(IOException.class, () -> parser.parse(path, "bid.txt")).getMessage().contains("UTF-8"));
    }

    @Test void mapsPersonnelTableHeadersForSourceBackedProfileExtraction() throws Exception {
        Path path = temporary.resolve("people.docx");
        try (XWPFDocument word = new XWPFDocument()) {
            word.createParagraph().createRun().setText("人员基本情况");
            XWPFTable table = word.createTable(2, 3);
            table.getRow(0).getCell(0).setText("姓名");
            table.getRow(0).getCell(1).setText("职务");
            table.getRow(0).getCell(2).setText("证书编号");
            table.getRow(1).getCell(0).setText("成员甲");
            table.getRow(1).getCell(1).setText("技术负责人");
            table.getRow(1).getCell(2).setText("DEMO-CERT-01");
            try (java.io.OutputStream stream = Files.newOutputStream(path)) { word.write(stream); }
        }
        cn.bidcheck.domain.Domain.ParsedDocument parsed = parser.parse(path, "people.docx");
        assertTrue(parsed.pages().get(2).text().contains("姓名：成员甲"));
        assertTrue(parsed.pages().get(2).text().contains("职务：技术负责人"));
        cn.bidcheck.domain.Domain.Document document = new cn.bidcheck.domain.Domain.Document("id", "project", "BID", "people.docx", 1,
                "hash", "now", parsed.pages(), parsed.warnings());
        java.util.Map<java.lang.String, java.lang.Object> result = new ProfileExtractor().extract(document);
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> groups = (java.util.List<java.util.Map<String, Object>>) result.get("groups");
        java.util.Map<java.lang.String, java.lang.Object> people = groups.stream().filter(group -> group.get("title").equals("人员信息")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals(3, ((java.util.List<?>) people.get("items")).size());
    }

    @Test void rejectsEncryptedDamagedOversizedAndUnsupportedDocumentsClearly() throws Exception {
        Path encrypted = temporary.resolve("protected.pdf");
        try (PDDocument pdf = new PDDocument()) {
            addTextPage(pdf, "Protected");
            pdf.protect(new StandardProtectionPolicy("owner", "password", new AccessPermission()));
            pdf.save(encrypted.toFile());
        }
        assertTrue(assertThrows(IOException.class, () -> parser.parse(encrypted, "protected.pdf")).getMessage().contains("加密"));
        Path bad = temporary.resolve("broken.pdf");
        cn.bidcheck.compat.Java8Api.writeString(bad, "This is not a PDF");
        assertTrue(assertThrows(IOException.class, () -> parser.parse(bad, "broken.pdf")).getMessage().contains("损坏"));
        assertTrue(assertThrows(IOException.class, () -> parser.parse(bad, "file.exe")).getMessage().contains("格式"));
        Path pages = temporary.resolve("many.txt");
        cn.bidcheck.compat.Java8Api.writeString(pages, cn.bidcheck.compat.Java8Api.repeat("x\f", 500) + "x");
        assertTrue(assertThrows(IOException.class, () -> parser.parse(pages, "many.txt")).getMessage().contains("500"));
        cn.bidcheck.compat.Java8Api.writeString(pages, cn.bidcheck.compat.Java8Api.repeat("x", FileParser.MAX_CHARACTERS + 1));
        assertTrue(assertThrows(IOException.class, () -> parser.parse(pages, "many.txt")).getMessage().contains("200 万"));
    }

    @Test void rejectsSuspiciousDocxCompressionBeforeOpeningWordPackage() throws Exception {
        Path path = temporary.resolve("bomb.docx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(cn.bidcheck.compat.Java8Api.repeat("a", 2 * 1024 * 1024).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertTrue(assertThrows(IOException.class, () -> parser.parse(path, "bomb.docx")).getMessage().contains("压缩"));
    }

    @Test void limitsPdfGlyphAccumulationBeforePdfBoxBuildsAnUnboundedPage() throws Exception {
        Path path = temporary.resolve("glyph-limit.pdf");
        try (PDDocument pdf = new PDDocument()) {
            addTextPage(pdf, cn.bidcheck.compat.Java8Api.repeat("A", 50_001));
            pdf.save(path.toFile());
        }
        assertTrue(assertThrows(IOException.class, () -> parser.parse(path, "glyph-limit.pdf")).getMessage().contains("单页"));
    }

    @Test void rejectsPdfStreamsThatExpandBeyondTheProcessingBudget() throws Exception {
        Path path = temporary.resolve("compressed-limit.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            page.setContents(new org.apache.pdfbox.pdmodel.common.PDStream(pdf,
                    new ByteArrayInputStream(cn.bidcheck.compat.Java8Api.repeat(" ", 8 * 1024 * 1024 + 1).getBytes(StandardCharsets.US_ASCII)),
                    org.apache.pdfbox.cos.COSName.FLATE_DECODE));
            pdf.save(path.toFile());
        }
        assertTrue(assertThrows(IOException.class, () -> parser.parse(path, "compressed-limit.pdf")).getMessage().contains("解压"));
    }

    @Test void rejectsOversizedPdfImagesBeforeAllocatingTheirDecodedRaster() throws Exception {
        Path path = temporary.resolve("image-limit.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image = LosslessFactory.createFromImage(pdf, new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
            image.getCOSObject().setInt(org.apache.pdfbox.cos.COSName.WIDTH, 5000);
            image.getCOSObject().setInt(org.apache.pdfbox.cos.COSName.HEIGHT, 5000);
            try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) { stream.drawImage(image, 20, 20, 200, 200); }
            pdf.save(path.toFile());
        }
        assertThrows(IOException.class, () -> parser.renderPdfPage(path, 1));
    }

    @Test void rejectsWordXmlBlockBombBeforeBuildingPoiObjects() throws Exception {
        Path path = temporary.resolve("blocks.docx");
        StringBuilder xml = new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        for (int i = 0; i < 20_001; i++) xml.append("<w:p><w:r><w:t>record-").append(i).append("</w:t></w:r></w:p>");
        xml.append("</w:body></w:document>");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertTrue(assertThrows(IOException.class, () -> parser.parse(path, "blocks.docx")).getMessage().contains("内容块"));
    }

    @Test void ocrUsesTruePdfPagesPreservesNativeTextAndReportsModelAndProgress() throws Exception {
        Path path = temporary.resolve("ocr.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage scanned = new PDPage();
            pdf.addPage(scanned);
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image = LosslessFactory.createFromImage(pdf, new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB));
            try (PDPageContentStream content = new PDPageContentStream(pdf, scanned)) { content.drawImage(image, 30, 50); }
            addTextPage(pdf, "Native project name");
            try (PDPageContentStream content = new PDPageContentStream(pdf, pdf.getPage(1), PDPageContentStream.AppendMode.APPEND, true)) {
                content.drawImage(image, 30, 50);
            }
            addTextPage(pdf, "No image: native evidence only");
            pdf.save(path.toFile());
        }
        FakeOcr ocr = new FakeOcr((bytes, mime) -> "车牌号：测试A12345\n车辆识别代号：DEMO-VIN-0001");
        List<Integer> progress = new ArrayList<>();
        cn.bidcheck.domain.Domain.ParsedDocument parsed = new FileParser(ocr).parse(path, "ocr.pdf", (percent, stage) -> progress.add(percent));
        assertEquals(2, ocr.calls);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("PDFBOX_OCR", "PDFBOX_NATIVE_WITH_OCR", "PDFBOX_NATIVE"), cn.bidcheck.compat.Java8Api.toList(parsed.pages().stream().map(p -> p.parser())));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.toList(parsed.pages().stream().map(p -> p.number())));
        assertTrue(parsed.pages().get(1).text().contains("【原生文字】\nNative project name"));
        assertTrue(parsed.pages().get(1).text().contains("【OCR识别文字】"));
        assertTrue(parsed.pages().get(0).warning().contains("[OCR已识别]"));
        assertTrue(parsed.pages().get(0).warning().contains("qwen-vl-ocr-test"));
        assertTrue(parsed.pages().get(0).warning().contains("识别文字可能有误，请对照原图核实；签字盖章未核验"));
        assertTrue(cn.bidcheck.compat.Java8Api.isBlank(parsed.pages().get(2).warning()));
        assertEquals(0, progress.get(0));
        assertEquals(100, progress.get(progress.size() - 1));
        for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) >= progress.get(i - 1));
    }

    @Test void ocrWordImagesRemainInTheirContentBlocksAndPreviewUsesTheSameMapping() throws Exception {
        Path path = temporary.resolve("images.docx");
        byte[] first = imageBytes("png", 0x224488), second = imageBytes("png", 0xcc6633);
        try (XWPFDocument word = new XWPFDocument()) {
            word.createParagraph().createRun().setText("原生项目概况");
            org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph = word.createParagraph();
            paragraph.createRun().setText("证照原件");
            addPicture(paragraph, first);
            addPicture(paragraph, second);
            org.apache.poi.xwpf.usermodel.XWPFTable table = word.createTable(2, 2);
            table.getRow(0).getCell(0).setText("名称");
            table.getRow(0).getCell(1).setText("材料");
            table.getRow(1).getCell(0).setText("附件");
            addPicture(table.getRow(1).getCell(1).getParagraphs().get(0), first);
            try (java.io.OutputStream out = Files.newOutputStream(path)) { word.write(out); }
        }
        FakeOcr ocr = new FakeOcr((bytes, mime) -> "证书编号：DEMO-CERT-001");
        FileParser enabled = new FileParser(ocr);
        cn.bidcheck.domain.Domain.ParsedDocument parsed = enabled.parse(path, "images.docx");
        assertEquals(4, parsed.pages().size());
        assertEquals(2, ocr.calls, "Duplicate embedded image should reuse recognition within this parse");
        assertEquals("BLOCK", parsed.pages().get(1).locationType());
        assertEquals("WORD_NATIVE_WITH_OCR", parsed.pages().get(1).parser());
        assertTrue(parsed.pages().get(1).text().contains("【图片 1 OCR识别文字】"));
        assertTrue(parsed.pages().get(1).text().contains("【图片 2 OCR识别文字】"));
        assertTrue(parsed.pages().get(3).text().contains("证书编号：DEMO-CERT-001"));
        assertEquals(cn.bidcheck.compat.Java8Api.toList(parser.parse(path, "images.docx").pages().stream().map(p -> p.number())), cn.bidcheck.compat.Java8Api.toList(parsed.pages().stream().map(p -> p.number())));
        BufferedImage contact = ImageIO.read(new ByteArrayInputStream(enabled.renderWordImage(path, 2)));
        assertNotNull(contact);
        assertTrue((long) contact.getWidth() * contact.getHeight() <= 4_000_000);
        BufferedImage single = ImageIO.read(new ByteArrayInputStream(enabled.renderWordImage(path, 2, 2)));
        assertEquals(0xcc6633, single.getRGB(10, 10) & 0xffffff);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(enabled.renderWordImage(path, 4))));
        assertEquals(2, ocr.calls, "Preview must never call the external OCR provider");
        assertThrows(IOException.class, () -> enabled.renderWordImage(path, 1));
        assertThrows(IOException.class, () -> enabled.renderWordImage(path, 2, 3));
    }

    @Test void mixedWordOcrFailuresKeepSuccessfulTextAndIncompleteMarkerWithoutProviderSecrets() throws Exception {
        Path path = temporary.resolve("partial.docx");
        try (XWPFDocument word = new XWPFDocument()) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph = word.createParagraph();
            addPicture(paragraph, imageBytes("png", 0x123456));
            addPicture(paragraph, imageBytes("png", 0x987654));
            try (java.io.OutputStream out = Files.newOutputStream(path)) { word.write(out); }
        }
        int[] requests = {0};
        FakeOcr ocr = new FakeOcr((bytes, mime) -> {
            if (++requests[0] == 2) throw new IOException("secret-token and private provider response");
            return "企业名称：样例企业";
        });
        cn.bidcheck.domain.Domain.Page page = new FileParser(ocr).parse(path, "partial.docx").pages().get(0);
        assertEquals("WORD_NATIVE_WITH_OCR", page.parser());
        assertTrue(page.text().contains("企业名称：样例企业"));
        assertTrue(page.warning().contains("[OCR已识别]"));
        assertTrue(page.warning().contains("[OCR未完成]"));
        assertTrue(page.warning().contains("OCR失败"));
        assertFalse(page.warning().contains("secret-token"));
    }

    @Test void ocrBudgetPreservesEveryWordImageLocationAndWarnsInsteadOfSilentlySkipping() throws Exception {
        Path path = temporary.resolve("image-budget.docx");
        try (XWPFDocument word = new XWPFDocument()) {
            for (int i = 0; i < 102; i++) addPicture(word.createParagraph(), imageBytes("png", 0x335500 + i));
            try (java.io.OutputStream out = Files.newOutputStream(path)) { word.write(out); }
        }
        FakeOcr ocr = new FakeOcr((image, mime) -> "证件原文");
        cn.bidcheck.domain.Domain.ParsedDocument result = new FileParser(ocr).parse(path, "image-budget.docx");
        assertEquals(102, result.pages().size());
        assertEquals(100, ocr.calls);
        assertEquals(100, result.pages().stream().filter(page -> page.parser().contains("OCR")).count());
        for (cn.bidcheck.domain.Domain.Page page : result.pages().subList(100, 102)) {
            assertTrue(cn.bidcheck.compat.Java8Api.isBlank(page.text()));
            assertTrue(page.warning().contains("[OCR未完成]"));
            assertTrue(page.warning().contains("超限"));
            assertTrue(page.warning().contains("100"));
        }
    }

    @Test void parsesPngAndJpegWithImageLocationAndSafePreview() throws Exception {
        FakeOcr ocr = new FakeOcr((bytes, mime) -> "统一社会信用代码：DEMO-ONLY");
        for (String extension : cn.bidcheck.compat.Java8Api.listOf("png", "jpg", "jpeg")) {
            Path path = temporary.resolve("source." + extension);
            Files.write(path, imageBytes(extension.equals("png") ? "png" : "jpeg", 0x223344));
            cn.bidcheck.domain.Domain.ParsedDocument result = new FileParser(ocr).parse(path, path.getFileName().toString());
            assertEquals(1, result.pages().size());
            assertEquals("IMAGE", result.pages().get(0).locationType());
            assertEquals("IMAGE_OCR", result.pages().get(0).parser());
            assertTrue(result.pages().get(0).text().contains("DEMO-ONLY"));
            assertNotNull(ImageIO.read(new ByteArrayInputStream(parser.renderImage(path))));
            cn.bidcheck.domain.Domain.ParsedDocument disabled = parser.parse(path, path.getFileName().toString());
            assertTrue(cn.bidcheck.compat.Java8Api.isBlank(disabled.pages().get(0).text()));
            assertTrue(disabled.pages().get(0).warning().contains("[OCR未完成]"));
            assertTrue(disabled.pages().get(0).warning().contains("未启用"));
        }
    }

    @Test void rejectsImageDimensionsBeforeDecodingOrCallingOcr() throws Exception {
        Path path = temporary.resolve("oversized.png");
        byte[] bytes = imageBytes("png", 0x102030);
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(bytes);
        header.putInt(16, 5000);
        header.putInt(20, 5000);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, 12, 17);
        header.putInt(29, (int) crc.getValue());
        Files.write(path, bytes);
        FakeOcr ocr = new FakeOcr((image, mime) -> "Must not run");
        assertTrue(assertThrows(IOException.class, () -> new FileParser(ocr).parse(path, "oversized.png")).getMessage().contains("像素"));
        assertThrows(IOException.class, () -> parser.renderImage(path));
        assertEquals(0, ocr.calls);
    }

    @Test void incompleteOcrAndCancellationAreNeverReportedAsSuccessfulRecognition() throws Exception {
        Path path = temporary.resolve("source.png");
        Files.write(path, imageBytes("png", 0xabcdef));
        for (String output : cn.bidcheck.compat.Java8Api.listOf("", " ", cn.bidcheck.compat.Java8Api.repeat("x", 50_001))) {
            cn.bidcheck.domain.Domain.ParsedDocument result = new FileParser(new FakeOcr((bytes, mime) -> output)).parse(path, "source.png");
            assertEquals("IMAGE", result.pages().get(0).parser());
            assertTrue(result.pages().get(0).warning().contains("[OCR未完成]"));
            assertTrue(cn.bidcheck.compat.Java8Api.isBlank(result.pages().get(0).text()));
        }
        InterruptedIOException interruption = new InterruptedIOException("test cancellation");
        FileParser interrupted = new FileParser(new FakeOcr((bytes, mime) -> { throw interruption; }));
        assertSame(interruption, assertThrows(InterruptedIOException.class, () -> interrupted.parse(path, "source.png")));
        RuntimeException leaseLost = new IllegalStateException("lease lost");
        FakeOcr unused = new FakeOcr((bytes, mime) -> "Must not run");
        assertSame(leaseLost, assertThrows(IllegalStateException.class,
                () -> new FileParser(unused).parse(path, "source.png", (percent, stage) -> { throw leaseLost; })));
        assertEquals(0, unused.calls);
    }

    @Test void noisyImageIsCompressedWithinProviderTransportLimit() throws Exception {
        Path path = temporary.resolve("noise.png");
        BufferedImage noise = new BufferedImage(1500, 1500, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(19);
        for (int y = 0; y < noise.getHeight(); y++) for (int x = 0; x < noise.getWidth(); x++) noise.setRGB(x, y, random.nextInt(1 << 24));
        ImageIO.write(noise, "png", path.toFile());
        noise.flush();
        assertTrue(Files.size(path) > 6 * 1024 * 1024);
        FakeOcr ocr = new FakeOcr((bytes, mime) -> {
            assertEquals("image/jpeg", mime);
            assertTrue(bytes.length <= 6 * 1024 * 1024);
            return "可用转录";
        });
        assertEquals("IMAGE_OCR", new FileParser(ocr).parse(path, "noise.png").pages().get(0).parser());
        assertEquals(1, ocr.calls);
    }

    @Test void permitsLargePdfPageCountsWhileRetainingTextAndHardPdfLimits() throws Exception {
        Path path = temporary.resolve("large-document.pdf");
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 0; i < 581; i++) pdf.addPage(new PDPage());
            pdf.save(path.toFile());
            assertEquals(581, parser.parse(path, "large-document.pdf").pages().size());
            for (int i = 581; i < 1001; i++) pdf.addPage(new PDPage());
            pdf.save(path.toFile());
        }
        assertTrue(assertThrows(IOException.class, () -> parser.parse(path, "large-document.pdf")).getMessage().contains("1000"));
        assertEquals(DocumentLimits.MAX_FILE_BYTES, FileParser.MAX_INPUT_BYTES);
        Path oversized = temporary.resolve("oversized.pdf");
        try (java.nio.channels.SeekableByteChannel file = Files.newByteChannel(oversized,
                java.util.EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE))) {
            file.position(DocumentLimits.MAX_FILE_BYTES);
            file.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        assertEquals(DocumentLimits.MAX_FILE_BYTES + 1, Files.size(oversized));
        assertTrue(assertThrows(IOException.class, () -> parser.parse(oversized, "oversized.pdf")).getMessage().contains(Integer.toString(DocumentLimits.MAX_FILE_MB)));
    }

    @Test void permitsAnExplicitBoundedOcrBudgetBeyondTheConservativeDefault() throws Exception {
        Path path = temporary.resolve("configured-budget.docx");
        try (XWPFDocument word = new XWPFDocument()) {
            for (int i = 0; i < 102; i++) addPicture(word.createParagraph(), imageBytes("png", 0x123600 + i));
            try (java.io.OutputStream out = Files.newOutputStream(path)) { word.write(out); }
        }
        FakeOcr ocr = new FakeOcr((bytes, mime) -> "证书原文");
        cn.bidcheck.domain.Domain.ParsedDocument result = new FileParser(ocr, 600).parse(path, "configured-budget.docx");
        assertEquals(102, ocr.calls);
        assertEquals(102, result.pages().stream().filter(p -> p.parser().contains("OCR")).count());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("超限")));
        assertThrows(IllegalArgumentException.class, () -> new FileParser(ocr, 0));
        assertThrows(IllegalArgumentException.class, () -> new FileParser(ocr, 1001));
    }

    @Test void allowsVerifiedLargeJpegScansButRejectsForgedJpegDimensions() throws Exception {
        Path path = temporary.resolve("large-jpeg.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            BufferedImage scan = new BufferedImage(4200, 4200, BufferedImage.TYPE_INT_RGB);
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image = JPEGFactory.createFromImage(pdf, scan);
            scan.flush();
            try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) { stream.drawImage(image, 20, 20, 500, 700); }
            pdf.save(path.toFile());
            BufferedImage preview = ImageIO.read(new ByteArrayInputStream(parser.renderPdfPage(path, 1)));
            assertNotNull(preview);
            assertTrue((long) preview.getWidth() * preview.getHeight() <= 4_000_000);
            image.getCOSObject().setInt(org.apache.pdfbox.cos.COSName.WIDTH, 4201);
            pdf.save(path.toFile());
        }
        assertThrows(IOException.class, () -> parser.renderPdfPage(path, 1));
    }

    @Test void toleratesOnlyIdenticalBibliographyNamespacesInCustomMetadataWithoutMutatingOriginal() throws Exception {
        String ns = "http://schemas.openxmlformats.org/officeDocument/2006/bibliography";
        String duplicate = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><b:Sources xmlns:b=\"" + ns + "\" xmlns:b=\"" + ns + "\" Version=\"6\"/>";
        Path original = wordWithXmlPart("bibliography.docx", "customXml/item2.xml", duplicate);
        byte[] before = Files.readAllBytes(original);
        cn.bidcheck.domain.Domain.ParsedDocument parsed = parser.parse(original, "bibliography.docx");
        assertTrue(parsed.pages().get(0).text().contains("项目名称：元数据兼容测试"));
        assertArrayEquals(before, Files.readAllBytes(original));
        Path conflicting = wordWithXmlPart("conflicting.docx", "customXml/item2.xml", duplicate.replace(" Version=", " xmlns:b=\"urn:conflicting\" Version="));
        assertThrows(IOException.class, () -> parser.parse(conflicting, "conflicting.docx"));
        Path body = wordWithXmlPart("body.docx", "word/document.xml", duplicate);
        assertThrows(IOException.class, () -> parser.parse(body, "body.docx"));
        String dtd = "<!DOCTYPE b:Sources [<!ENTITY bad SYSTEM \"https://example.invalid/private\">]>" + duplicate.substring(duplicate.indexOf("<b:Sources"));
        Path unsafe = wordWithXmlPart("dtd.docx", "customXml/item2.xml", dtd);
        assertThrows(IOException.class, () -> parser.parse(unsafe, "dtd.docx"));
    }

    @Test void pdfOcrRunsTwoRequestsInParallelButKeepsPageOrderAndCallbacksOnCaller() throws Exception {
        Path path = coloredPdf("parallel.pdf", 0xcc0000, 0x00cc00, 0x0000cc);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maximum = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<java.lang.String> completions = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        FakeOcr ocr = new FakeOcr((bytes, mime) -> {
            int running = active.incrementAndGet();
            maximum.accumulateAndGet(running, Math::max);
            String color = Integer.toHexString(ImageIO.read(new ByteArrayInputStream(bytes)).getRGB(1, 1) & 0xffffff);
            started.countDown();
            try {
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS), "Two requests must start before either completes");
                if (color.equals("cc0000")) Thread.sleep(150);
                completions.add(color);
                return "图像编号：" + color;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("test interrupted");
            } finally { active.decrementAndGet(); }
        });
        Thread caller = Thread.currentThread();
        cn.bidcheck.domain.Domain.ParsedDocument result = new FileParser(ocr).parse(path, "parallel.pdf", (percent, stage) -> assertSame(caller, Thread.currentThread()));
        assertEquals(2, maximum.get());
        assertEquals(3, ocr.calls);
        assertEquals("cc00", completions.peek(), "Second-page recognition should be able to finish first");
        assertEquals("【OCR识别文字】\n图像编号：cc0000", result.pages().get(0).text());
        assertEquals("【OCR识别文字】\n图像编号：cc00", result.pages().get(1).text());
        assertEquals("【OCR识别文字】\n图像编号：cc", result.pages().get(2).text());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.toList(result.pages().stream().map(p -> p.number())));
    }

    @Test void losingPdfLeaseCancelsBothRequestsAndDoesNotInvokeCallbacksFromWorkers() throws Exception {
        Path path = coloredPdf("lease.pdf", 0xaa1100, 0x11aa00, 0x0011aa);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(2);
        FakeOcr ocr = new FakeOcr((bytes, mime) -> {
            started.countDown();
            try {
                new java.util.concurrent.CountDownLatch(1).await(20, java.util.concurrent.TimeUnit.SECONDS);
                throw new IOException("test request unexpectedly completed");
            } catch (InterruptedException e) {
                cancelled.countDown();
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("cancelled");
            }
        });
        RuntimeException lost = new IllegalStateException("lease lost");
        Thread caller = Thread.currentThread();
        RuntimeException actual = assertThrows(IllegalStateException.class, () -> new FileParser(ocr).parse(path, "lease.pdf", (percent, stage) -> {
            assertSame(caller, Thread.currentThread());
            if (stage.equals("已提交第 2 页图像识别请求")) {
                try { assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                throw lost;
            }
        }));
        assertSame(lost, actual);
        assertTrue(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(2, ocr.calls, "A third page must not be submitted after the fence fails");
    }

    @Test void pdfWorkerInterruptionIsPropagatedAndCancelsOtherPendingWork() throws Exception {
        Path path = coloredPdf("interrupted.pdf", 0xaa0000, 0x00aa00);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch secondCancelled = new java.util.concurrent.CountDownLatch(1);
        InterruptedIOException cancellation = new InterruptedIOException("provider interrupted");
        FakeOcr ocr = new FakeOcr((bytes, mime) -> {
            int color = ImageIO.read(new ByteArrayInputStream(bytes)).getRGB(1, 1) & 0xffffff;
            started.countDown();
            try {
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
                if (color == 0xaa0000) throw cancellation;
                new java.util.concurrent.CountDownLatch(1).await(20, java.util.concurrent.TimeUnit.SECONDS);
                return "must not complete";
            } catch (InterruptedException e) {
                secondCancelled.countDown();
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("other request cancelled");
            }
        });
        assertSame(cancellation, assertThrows(InterruptedIOException.class, () -> new FileParser(ocr).parse(path, "interrupted.pdf")));
        assertTrue(secondCancelled.await(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test void configurablePdfConcurrencyReachesEightButNeverExceedsTheBoundary() throws Exception {
        int[] colors = java.util.stream.IntStream.rangeClosed(1, 17).map(i -> i * 0x070707).toArray();
        Path path = coloredPdf("eight-workers.pdf", colors);
        java.util.concurrent.CountDownLatch firstBatch = new java.util.concurrent.CountDownLatch(8);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maximum = new java.util.concurrent.atomic.AtomicInteger();
        FakeOcr ocr = new FakeOcr((bytes, mime) -> {
            int call = calls.incrementAndGet();
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (call <= 8) {
                    firstBatch.countDown();
                    assertTrue(firstBatch.await(10, java.util.concurrent.TimeUnit.SECONDS), "All eight configured slots should be usable");
                }
                Thread.sleep(40);
                return "图像编号：" + Integer.toHexString(ImageIO.read(new ByteArrayInputStream(bytes)).getRGB(1, 1) & 0xffffff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("test interrupted");
            } finally { active.decrementAndGet(); }
        });
        Thread caller = Thread.currentThread();
        cn.bidcheck.domain.Domain.ParsedDocument result = new FileParser(ocr, 600, 8).parse(path, "eight-workers.pdf", (percent, stage) -> assertSame(caller, Thread.currentThread()));
        assertEquals(8, maximum.get());
        assertEquals(17, ocr.calls);
        assertEquals(17, result.pages().size());
        for (int i = 0; i < colors.length; i++) {
            assertEquals(i + 1, result.pages().get(i).number());
            assertEquals("【OCR识别文字】\n图像编号：" + Integer.toHexString(colors[i]), result.pages().get(i).text());
        }
    }

    @Test void singlePdfWorkerRemainsSerialAndIllegalConcurrencyIsRejected() throws Exception {
        Path path = coloredPdf("one-worker.pdf", 0xdd0000, 0x00dd00, 0x0000dd);
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maximum = new java.util.concurrent.atomic.AtomicInteger();
        FakeOcr ocr = new FakeOcr((bytes, mime) -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { return "已识别"; }
            finally { active.decrementAndGet(); }
        });
        assertEquals(3, new FileParser(ocr, 100, 1).parse(path, "one-worker.pdf").pages().size());
        assertEquals(1, maximum.get());
        for (int invalid : new int[]{Integer.MIN_VALUE, -1, 0, 9, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new FileParser(ocr, 100, invalid));
        }
    }

    @Test void pageNumberOnlyTriggersTwoBoundedCropsWithSourceLabels() throws Exception {
        Path path = temporary.resolve("sparse.png");
        Files.write(path, imageBytes("png", 0xffffff));
        java.util.concurrent.atomic.AtomicInteger number = new java.util.concurrent.atomic.AtomicInteger();
        FakeOcr client = new FakeOcr((bytes, mime) -> {
            int call = number.incrementAndGet();
            if (call > 1) assertTrue(ImageIO.read(new ByteArrayInputStream(bytes)).getHeight() < 24);
            switch (call) {case 1:return "158";case 2:return "合同联系人：测试甲方";case 3:return "签署日期：2025年1月1日";default:throw new AssertionError("unbounded retry");}
        });
        cn.bidcheck.domain.Domain.ParsedDocument parsed = new FileParser(client, 1).parse(path, "sparse.png");
        assertEquals(3, client.calls, "One image position permits at most two crop requests after a page-number-only result");
        assertTrue(parsed.pages().get(0).text().contains("【上半幅 OCR，边界有重叠】"));
        assertTrue(parsed.pages().get(0).text().contains("【下半幅 OCR，边界有重叠】"));
        assertTrue(parsed.pages().get(0).text().contains("合同联系人：测试甲方"));
        assertTrue(parsed.pages().get(0).warning().contains("不能直接合计"));
        assertFalse(parsed.pages().get(0).warning().contains("[OCR未完成]"));
    }

    @Test void pageNumberOnlyIsIncompleteWhenBothCropsAlsoHaveNoBody() throws Exception {
        Path path = temporary.resolve("page-number.png");
        Files.write(path, imageBytes("png", 0xffffff));
        FakeOcr client = new FakeOcr((bytes, mime) -> "第 ４０４ 页 / 共 ５８１ 页");
        cn.bidcheck.domain.Domain.ParsedDocument parsed = new FileParser(client, 1).parse(path, "page-number.png");
        assertEquals(3, client.calls);
        assertTrue(parsed.pages().get(0).warning().contains("[OCR未完成]"));
        assertFalse(parsed.pages().get(0).warning().contains("[OCR已识别]"));
    }

    @Test void recoveredCropIsRetainedButFailedOtherCropRemainsIncomplete() throws Exception {
        Path path = temporary.resolve("partial-crop.png");
        Files.write(path, imageBytes("png", 0xffffff));
        java.util.concurrent.atomic.AtomicInteger number = new java.util.concurrent.atomic.AtomicInteger();
        FakeOcr client = new FakeOcr((bytes, mime) -> { switch (number.incrementAndGet()) {case 1:return "404";case 2:return "采购订单：测试项目";case 3:throw new IOException("private provider failure");default:throw new AssertionError("unbounded retry");} });
        cn.bidcheck.domain.Domain.ParsedDocument parsed = new FileParser(client).parse(path, "partial-crop.png");
        assertEquals(3, client.calls);
        assertTrue(parsed.pages().get(0).text().contains("采购订单：测试项目"));
        assertTrue(parsed.pages().get(0).warning().contains("[OCR未完成]"));
        assertFalse(parsed.pages().get(0).warning().contains("private"));
    }

    @Test void sparseCropCancellationPropagatesWithoutTryingSecondCrop() throws Exception {
        Path path = temporary.resolve("cancel-crop.png");
        Files.write(path, imageBytes("png", 0xffffff));
        java.util.concurrent.atomic.AtomicInteger number = new java.util.concurrent.atomic.AtomicInteger();
        java.io.InterruptedIOException cancelled = new InterruptedIOException("cancel");
        FakeOcr client = new FakeOcr((bytes, mime) -> {
            if (number.incrementAndGet() == 1) return "12";
            throw cancelled;
        });
        assertSame(cancelled, assertThrows(InterruptedIOException.class, () -> new FileParser(client).parse(path, "cancel-crop.png")));
        assertEquals(2, client.calls);
    }

    @Test void shortHeadingDoesNotTriggerPaidCropRequests() throws Exception {
        Path path = temporary.resolve("heading.png");
        Files.write(path, imageBytes("png", 0xffffff));
        FakeOcr client = new FakeOcr((bytes, mime) -> "合同");
        assertTrue(new FileParser(client).parse(path, "heading.png").pages().get(0).text().contains("合同"));
        assertEquals(1, client.calls);
    }

    private Path coloredPdf(String name, int... colors) throws IOException {
        Path path = temporary.resolve(name);
        try (PDDocument pdf = new PDDocument()) {
            for (int color : colors) {
                PDPage page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(100, 100));
                pdf.addPage(page);
                BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) image.setRGB(x, y, color);
                org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject embedded = LosslessFactory.createFromImage(pdf, image);
                image.flush();
                try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) { stream.drawImage(embedded, 0, 0, 100, 100); }
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    @Test void nativeTextBoxChoiceIsExtractedOnceWithoutTreatingEmptyVmlAsAnImage() throws Exception {
        String textbox="<w:txbxContent><w:p><w:r><w:t>合成人员身份证复印件贴于此处</w:t></w:r></w:p></w:txbxContent>";
        String body="<w:p><w:r><w:t>原生说明前半段</w:t></w:r><w:r><mc:AlternateContent>"
                +"<mc:Choice Requires=\"wps\"><w:drawing><wps:wsp><wps:txbx>"+textbox+"</wps:txbx></wps:wsp></w:drawing></mc:Choice>"
                +"<mc:Fallback><w:pict><v:shape><v:imagedata o:title=\"\"/><v:textbox>"+textbox+"</v:textbox></v:shape></w:pict></mc:Fallback>"
                +"</mc:AlternateContent></w:r><w:r><w:t>原生说明后半段</w:t></w:r></w:p>";
        Path path=wordWithXmlPart("textbox-choice.docx","word/document.xml",wordMarkup(body));
        FakeOcr client=new FakeOcr((bytes,mime)->{throw new AssertionError("Native textbox must not invoke OCR");});
        cn.bidcheck.domain.Domain.ParsedDocument parsed=new FileParser(client).parse(path,"textbox-choice.docx");
        assertEquals(1,parsed.pages().size());assertEquals(0,client.calls);assertTrue(parsed.warnings().isEmpty());
        cn.bidcheck.domain.Domain.Page page=parsed.pages().get(0);assertEquals("POI_NATIVE",page.parser());assertEquals("PARAGRAPH",page.locationType());
        assertTrue(page.text().contains("原生说明前半段原生说明后半段"));assertTrue(page.text().contains("【文本框原文】"));
        assertEquals(1,page.text().split("合成人员身份证复印件贴于此处",-1).length-1);
    }

    @Test void vmlTextPathsAndTableTextBoxesRetainNativeBlockLocations() throws Exception {
        String body="<w:tbl><w:tr><w:tc><w:p><w:r><w:t>资格核对说明</w:t></w:r><w:r><w:pict><v:shape><v:imagedata o:title=\"\"/>"
                +"<v:textbox><w:txbxContent><w:p><w:r><w:t>注册资格需要核对主体</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p></w:tc></w:tr></w:tbl>"
                +"<w:p><w:r><w:pict><v:shape><v:imagedata o:title=\"\"/><v:textpath string=\"合成下载水印 2024-01-01\"/></v:shape></w:pict></w:r></w:p>";
        Path path=wordWithXmlPart("native-shapes.docx","word/document.xml",wordMarkup(body));
        cn.bidcheck.domain.Domain.ParsedDocument parsed=parser.parse(path,"native-shapes.docx");
        assertEquals(2,parsed.pages().size());assertTrue(parsed.warnings().isEmpty());
        assertEquals("BLOCK",parsed.pages().get(0).locationType());assertTrue(parsed.pages().get(0).text().contains("资格核对说明"));
        assertTrue(parsed.pages().get(0).text().contains("注册资格需要核对主体"));
        assertEquals(2,parsed.pages().get(1).number());assertTrue(parsed.pages().get(1).text().contains("【艺术字/水印原文】\n合成下载水印 2024-01-01"));
        assertTrue(parsed.pages().stream().noneMatch(page->page.parser().contains("OCR")));
    }

    @Test void emptyNativeShapeHasNoOcrWorkButRealVmlReferencesRemainUnresolved() throws Exception {
        String empty="<w:p><w:r><w:t>正常正文</w:t></w:r><w:r><w:pict><v:shape><v:imagedata o:title=\"\"/>"
                +"<v:textbox><w:txbxContent><w:p><w:r><w:t> </w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>";
        String references="<w:p><w:r><w:pict><v:shape><v:imagedata r:id=\"missingImage\"/></v:shape></w:pict></w:r></w:p>"
                +"<w:p><w:r><w:pict><v:shape><v:imagedata src=\"https://example.invalid/image.png\"/></v:shape></w:pict></w:r></w:p>"
                +"<w:p><w:r><w:pict><v:shape><v:imagedata o:relid=\"legacyImage\"/></v:shape></w:pict></w:r></w:p>";
        Path path=wordWithXmlPart("real-vml-reference.docx","word/document.xml",wordMarkup(empty+references));
        FakeOcr client=new FakeOcr((bytes,mime)->{throw new AssertionError("Unresolved links must never be fetched");});
        cn.bidcheck.domain.Domain.ParsedDocument parsed=new FileParser(client).parse(path,"real-vml-reference.docx");
        assertEquals(4,parsed.pages().size());assertTrue(parsed.pages().get(0).text().startsWith("正常正文"));assertTrue(cn.bidcheck.compat.Java8Api.isBlank(parsed.pages().get(0).warning()));
        assertEquals(3,parsed.warnings().size());assertEquals(0,client.calls);
        assertTrue(parsed.pages().subList(1,4).stream().allMatch(page->page.warning().contains("[OCR未完成]")));
    }

    @Test void textBoxImagesAndTrackedRevisionsStillRetainIncompleteWarnings() throws Exception {
        String body="<w:p><w:r><w:pict><v:shape><v:textbox><w:txbxContent><w:p><w:r><w:t>原生框内说明</w:t></w:r>"
                +"<w:ins w:id=\"1\"><w:r><w:t>待接受新增条款</w:t></w:r></w:ins>"
                +"<w:del w:id=\"2\"><w:r><w:delText>被删除的旧条款</w:delText></w:r></w:del>"
                +"<w:r><w:drawing><a:blip r:embed=\"missingBoxImage\"/></w:drawing></w:r>"
                +"</w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>";
        Path path=wordWithXmlPart("revised-box.docx","word/document.xml",wordMarkup(body));
        cn.bidcheck.domain.Domain.Page page=parser.parse(path,"revised-box.docx").pages().get(0);
        assertTrue(page.text().contains("原生框内说明"));assertTrue(page.text().contains("待接受新增条款"));
        assertFalse(page.text().contains("被删除的旧条款"));assertTrue(page.warning().contains("修订"));assertTrue(page.warning().contains("[OCR未完成]"));
    }

    @Test void unsupportedAlternateChoiceUsesItsFallbackOnceOrStaysIncomplete() throws Exception {
        String body="<w:p><w:r><mc:AlternateContent><mc:Choice Requires=\"unknown\"><w:pict><v:shape><v:textpath string=\"不支持的新形式\"/></v:shape></w:pict></mc:Choice>"
                +"<mc:Fallback><w:pict><v:shape><v:textpath string=\"兼容形式原文\"/></v:shape></w:pict></mc:Fallback></mc:AlternateContent></w:r></w:p>"
                +"<w:p><w:r><mc:AlternateContent><mc:Choice Requires=\"unknown\"><w:pict><v:shape><v:textpath string=\"不能确认的内容\"/></v:shape></w:pict></mc:Choice></mc:AlternateContent></w:r></w:p>";
        Path path=wordWithXmlPart("fallback-shape.docx","word/document.xml",wordMarkup(body));
        cn.bidcheck.domain.Domain.ParsedDocument parsed=parser.parse(path,"fallback-shape.docx");
        assertEquals(2,parsed.pages().size());assertTrue(parsed.pages().get(0).text().contains("兼容形式原文"));
        assertFalse(parsed.pages().get(0).text().contains("不支持的新形式"));assertTrue(cn.bidcheck.compat.Java8Api.isBlank(parsed.pages().get(0).warning()));
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(parsed.pages().get(1).warning()));
    }

    @Test void emptyLegacyShapeSlotsPreserveHistoricalImageTargetsWithoutFalseOcrWarnings() throws Exception {
        Path path=temporary.resolve("stable-shape-image-slots.docx");
        try (XWPFDocument word=new XWPFDocument(); java.io.OutputStream output=Files.newOutputStream(path)) {
            word.createParagraph(); // Ordinary blank paragraphs were never locations and remain omitted.
            word.createParagraph().getCTP().addNewR().addNewPict().set(org.apache.xmlbeans.XmlObject.Factory.parse(
                    "<xml-fragment xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\">"
                            +"<v:shape><v:imagedata o:title=\"\"/></v:shape></xml-fragment>"));
            addPicture(word.createParagraph(), imageBytes("png",0x123456));
            word.createTable(1,1).getRow(0).getCell(0).getParagraphs().get(0).getCTP().addNewR().addNewPict()
                    .set(org.apache.xmlbeans.XmlObject.Factory.parse("<xml-fragment xmlns:v=\"urn:schemas-microsoft-com:vml\" "
                            +"xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                            +"<v:shape><v:textbox><w:txbxContent/></v:textbox></v:shape></xml-fragment>"));
            addPicture(word.createParagraph(), imageBytes("png",0x987654));
            word.write(output);
        }
        FakeOcr client=new FakeOcr((image,mime)->"合成图片正文");
        FileParser enabled=new FileParser(client);
        cn.bidcheck.domain.Domain.ParsedDocument parsed=enabled.parse(path,"stable-shape-image-slots.docx");
        assertEquals(4,parsed.pages().size());assertEquals(2,client.calls);
        for (int index : cn.bidcheck.compat.Java8Api.listOf(0, 2)) {
            assertTrue(cn.bidcheck.compat.Java8Api.isBlank(parsed.pages().get(index).text()));assertTrue(cn.bidcheck.compat.Java8Api.isBlank(parsed.pages().get(index).warning()));
            assertEquals("POI_NATIVE",parsed.pages().get(index).parser());
        }
        assertEquals("PARAGRAPH",parsed.pages().get(0).locationType());assertEquals("BLOCK",parsed.pages().get(2).locationType());
        assertTrue(parsed.warnings().stream().noneMatch(warning->warning.contains("[OCR未完成]")));
        BufferedImage first=ImageIO.read(new ByteArrayInputStream(enabled.renderWordImage(path,2)));
        BufferedImage second=ImageIO.read(new ByteArrayInputStream(enabled.renderWordImage(path,4)));
        try { assertEquals(0x123456,first.getRGB(12,12)&0xffffff);assertEquals(0x987654,second.getRGB(12,12)&0xffffff); }
        finally { first.flush();second.flush(); }
        assertThrows(IOException.class,()->enabled.renderWordImage(path,1));
        assertThrows(IOException.class,()->enabled.renderWordImage(path,3));
        assertEquals(2,client.calls); // Preview never invokes OCR.
    }

    private static String wordMarkup(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                +"xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                +"xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\" "
                +"xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:unknown=\"urn:unsupported-feature\"><w:body>"+body+"<w:sectPr/></w:body></w:document>";
    }

    private Path wordWithXmlPart(String name, String part, String xml) throws Exception {
        Path base = temporary.resolve("base-" + name), result = temporary.resolve(name);
        try (XWPFDocument word = new XWPFDocument(); java.io.OutputStream out = Files.newOutputStream(base)) {
            word.createParagraph().createRun().setText("项目名称：元数据兼容测试");
            word.write(out);
        }
        try (ZipFile original = new ZipFile(base.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(result))) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = original.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (entry.getName().equals(part)) continue;
                output.putNextEntry(new ZipEntry(entry.getName()));
                try (java.io.InputStream input = original.getInputStream(entry)) { cn.bidcheck.compat.Java8Api.transferTo(input, output); }
                output.closeEntry();
            }
            output.putNextEntry(new ZipEntry(part));
            output.write(xml.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return result;
    }

    private static byte[] imageBytes(String format, int rgb) throws IOException {
        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, rgb);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, format, bytes));
            return bytes.toByteArray();
        } finally { image.flush(); }
    }

    private static void addPicture(org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph, byte[] bytes) throws Exception {
        paragraph.createRun().addPicture(new ByteArrayInputStream(bytes), XWPFDocument.PICTURE_TYPE_PNG, "source.png", 240_000, 240_000);
    }

    @FunctionalInterface private interface Recognition { String recognize(byte[] image, String mime) throws IOException; }
    private static final class FakeOcr implements OcrClient {
        private final Recognition recognition;
        private int calls;
        private FakeOcr(Recognition recognition) { this.recognition = recognition; }
        @Override public boolean enabled() { return true; }
        @Override public String modelName() { return "qwen-vl-ocr-test"; }
        @Override public String identity() { return "test-only"; }
        @Override public OcrResult recognize(byte[] bytes, String mime) throws IOException {
            synchronized (this) { calls++; }
            assertTrue(bytes.length <= 6 * 1024 * 1024);
            assertTrue(mime.equals("image/png") || mime.equals("image/jpeg"));
            assertNotNull(ImageIO.read(new ByteArrayInputStream(bytes)));
            return new OcrResult(recognition.recognize(bytes, mime), "TEST", modelName(), false);
        }
    }

    private static void addTextPage(PDDocument pdf, String text) throws IOException {
        PDPage page = new PDPage();
        pdf.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(30, 750);
            content.showText(text);
            content.endText();
        }
    }
}
