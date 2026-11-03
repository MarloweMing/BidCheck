package cn.bidcheck.document;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.*;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileParserPdfAnnotationsTest {
    @TempDir Path temporary;

    @Test void packetRebindsAnnotationPageAndKeepsStampAppearanceWithoutChangingSourceObjects() throws Exception {
        try (PDDocument source = new PDDocument()) {
            PDPage first = new PDPage(new PDRectangle(100, 100)), other = new PDPage(new PDRectangle(100, 100));
            source.addPage(first); source.addPage(other);
            PDAnnotationLink link = link(first, 0); org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination destination = new PDPageFitDestination(); destination.setPage(other); link.setDestination(destination);
            PDAnnotationRubberStamp stamp = new PDAnnotationRubberStamp(); stamp.setRectangle(new PDRectangle(50, 50, 25, 25)); stamp.setPage(first);
            org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream appearance = appearance(source, java.awt.Color.RED); org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary dictionary = new PDAppearanceDictionary(); dictionary.setNormalAppearance(appearance); stamp.setAppearance(dictionary);
            first.setAnnotations(cn.bidcheck.compat.Java8Api.listOf(link, stamp));
            // An unselected page contains a large stream; neither /P nor an internal link may pull it into the packet.
            org.apache.pdfbox.pdmodel.common.PDStream hidden = new org.apache.pdfbox.pdmodel.common.PDStream(source);
            byte[] random = new byte[1_000_000]; new Random(42).nextBytes(random);
            try (OutputStream out = hidden.createOutputStream()) { out.write(random); } other.setContents(hidden);
            byte[] originalAppearance;
            try (InputStream in = appearance.getCOSObject().createRawInputStream()) { originalAppearance = cn.bidcheck.compat.Java8Api.readAllBytes(in); }
            byte[] packet = FileParser.pdfPacket(source, cn.bidcheck.compat.Java8Api.listOf(1), 100_000);
            assertTrue(packet.length < 10_000);
            assertSame(first.getCOSObject(), link.getPage().getCOSObject()); assertFalse(link.getCOSObject().containsKey(COSName.AP));
            try (InputStream in = appearance.getCOSObject().createRawInputStream()) { assertArrayEquals(originalAppearance, cn.bidcheck.compat.Java8Api.readAllBytes(in)); }
            assertSame(other.getCOSObject(), ((PDPageFitDestination) link.getDestination()).getPage().getCOSObject());
            try (PDDocument copied = Loader.loadPDF(packet)) {
                assertEquals(1, copied.getNumberOfPages()); java.util.List<org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation> annotations = copied.getPage(0).getAnnotations(); assertEquals(2, annotations.size());
                for (org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation annotation : annotations) assertSame(copied.getPage(0).getCOSObject(), annotation.getPage().getCOSObject());
                assertEquals("Stamp", annotations.get(1).getSubtype());
                try (InputStream in = annotations.get(1).getNormalAppearanceStream().getCOSObject().createRawInputStream()) { assertArrayEquals(originalAppearance, cn.bidcheck.compat.Java8Api.readAllBytes(in)); }
            }
        }
    }

    @Test void invisibleNavigationIsSkippedButVisibleBorderAndExistingAppearanceRemainVisible() throws Exception {
        Path path = temporary.resolve("annotations.pdf");
        try (PDDocument source = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(100, 100)); source.addPage(page);
            PDAnnotationLink invisible = link(page, 0), visible = link(page, 2), withAppearance = link(page, 0);
            invisible.setRectangle(new PDRectangle(0, 0, 100, 100));
            visible.setColor(new PDColor(new float[]{1, 0, 0}, PDDeviceRGB.INSTANCE)); visible.setRectangle(new PDRectangle(10, 10, 25, 25));
            withAppearance.setRectangle(new PDRectangle(55, 10, 25, 25));
            PDAppearanceDictionary blue = new PDAppearanceDictionary(); blue.setNormalAppearance(appearance(source, java.awt.Color.BLUE)); withAppearance.setAppearance(blue);
            PDAnnotationRubberStamp stamp = new PDAnnotationRubberStamp(); stamp.setRectangle(new PDRectangle(35, 55, 25, 25)); stamp.setPage(page);
            PDAppearanceDictionary green = new PDAppearanceDictionary(); green.setNormalAppearance(appearance(source, java.awt.Color.GREEN)); stamp.setAppearance(green);
            page.setAnnotations(cn.bidcheck.compat.Java8Api.listOf(invisible, visible, withAppearance, stamp)); source.save(path.toFile());
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(new FileParser().renderPdfPage(path, 1)));
        try {
            assertTrue(pixels(image, 0) > 20, "Visible red link border must survive");
            assertTrue(pixels(image, 1) > 100, "Existing green stamp appearance must survive");
            assertTrue(pixels(image, 2) > 100, "Zero-border link with explicit blue AP must survive");
        } finally { image.flush(); }
        try (PDDocument source = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            renderSameDocument(source, 1);
            assertFalse(source.getPage(0).getAnnotations().get(0).getCOSObject().containsKey(COSName.AP));
            assertFalse(source.getPage(0).getAnnotations().get(1).getCOSObject().containsKey(COSName.AP), "Generated link appearance must not mutate source");
        }
    }

    @Test void authorizedRealPages150And151StaySmallAndRenderAfterPacketCloses() throws Exception {
        String configured = System.getenv("BIDCHECK_REAL_PDF_FIXTURE");
        Assumptions.assumeTrue(configured != null && !cn.bidcheck.compat.Java8Api.isBlank(configured), "Optional authorized local PDF fixture");
        Path path = java.nio.file.Paths.get(configured); Assumptions.assumeTrue(Files.isRegularFile(path));
        try (PDDocument source = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            assertEquals(581, source.getNumberOfPages());
            assertEquals(2, source.getPage(149).getAnnotations().size()); assertEquals(1, source.getPage(150).getAnnotations().size());
            byte[] packet = FileParser.pdfPacket(source, cn.bidcheck.compat.Java8Api.listOf(150, 151, 153, 154, 155, 156, 157, 158, 159, 160), 16L * 1024 * 1024);
            assertTrue(packet.length < 4_000_000, "Annotation page backlinks must not serialize the 148 MB source document");
            try (PDDocument copied = Loader.loadPDF(packet)) {
                assertEquals(10, copied.getNumberOfPages());
                assertEquals(2, copied.getPage(0).getAnnotations().size()); assertEquals(1, copied.getPage(1).getAnnotations().size());
                for (int i = 0; i < 2; i++) for (org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation annotation : copied.getPage(i).getAnnotations())
                    assertSame(copied.getPage(i).getCOSObject(), annotation.getPage().getCOSObject());
            }
            for (int p : cn.bidcheck.compat.Java8Api.listOf(150, 151)) {
                assertTrue(renderSameDocument(source, p).length > 100_000);
                for (org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation annotation : source.getPage(p - 1).getAnnotations()) assertFalse(annotation.getCOSObject().containsKey(COSName.AP));
            }
        }
    }

    private static PDAnnotationLink link(PDPage page, float width) {
        PDAnnotationLink link = new PDAnnotationLink(); link.setPage(page); link.setRectangle(new PDRectangle(10, 10, 20, 20));
        PDBorderStyleDictionary style = new PDBorderStyleDictionary(); style.setWidth(width); link.setBorderStyle(style); return link;
    }
    private static PDAppearanceStream appearance(PDDocument owner, java.awt.Color color) throws IOException {
        PDAppearanceStream appearance = new PDAppearanceStream(owner); appearance.setBBox(new PDRectangle(25, 25));
        try (PDAppearanceContentStream stream = new PDAppearanceContentStream(appearance)) { stream.setNonStrokingColor(color); stream.addRect(0, 0, 25, 25); stream.fill(); }
        return appearance;
    }
    private static byte[] renderSameDocument(PDDocument source, int page) throws Exception {
        java.lang.reflect.Method method = FileParser.class.getDeclaredMethod("renderPdfPage", PDDocument.class, int.class, boolean.class); method.setAccessible(true);
        try { return (byte[]) method.invoke(null, source, page, true); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }
    private static int pixels(BufferedImage image, int channel) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int value = image.getRGB(x, y), r = value >> 16 & 255, g = value >> 8 & 255, b = value & 255;
            if (channel == 0 && r > 180 && g < 80 && b < 80 || channel == 1 && g > 180 && r < 80 && b < 80 || channel == 2 && b > 180 && r < 80 && g < 80) count++;
        }
        return count;
    }
}
