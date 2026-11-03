package cn.bidcheck.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Conservative local routing hints. A hint never authorizes omitting an unknown image. */
public final class PageClassifier {
    public static final String VERSION = "page-routing-3-person-credentials";
    private PageClassifier() { }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Decision {
        private final String pageType;
        private final String parserStrategy;
        private final String schema;
        private final String priority;
        private final boolean requiresCrossPageContext;
        private final String resolutionBucket;
        private final boolean independent;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Decision(@com.fasterxml.jackson.annotation.JsonProperty("pageType") String pageType, @com.fasterxml.jackson.annotation.JsonProperty("parserStrategy") String parserStrategy, @com.fasterxml.jackson.annotation.JsonProperty("schema") String schema, @com.fasterxml.jackson.annotation.JsonProperty("priority") String priority, @com.fasterxml.jackson.annotation.JsonProperty("requiresCrossPageContext") boolean requiresCrossPageContext, @com.fasterxml.jackson.annotation.JsonProperty("resolutionBucket") String resolutionBucket, @com.fasterxml.jackson.annotation.JsonProperty("independent") boolean independent) { this.pageType = pageType; this.parserStrategy = parserStrategy; this.schema = schema; this.priority = priority; this.requiresCrossPageContext = requiresCrossPageContext; this.resolutionBucket = resolutionBucket; this.independent = independent; }
        public String pageType() { return pageType; }
        public String parserStrategy() { return parserStrategy; }
        public String schema() { return schema; }
        public String priority() { return priority; }
        public boolean requiresCrossPageContext() { return requiresCrossPageContext; }
        public String resolutionBucket() { return resolutionBucket; }
        public boolean independent() { return independent; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Decision that = (Decision) other; return java.util.Objects.equals(this.pageType, that.pageType) && java.util.Objects.equals(this.parserStrategy, that.parserStrategy) && java.util.Objects.equals(this.schema, that.schema) && java.util.Objects.equals(this.priority, that.priority) && java.util.Objects.equals(this.requiresCrossPageContext, that.requiresCrossPageContext) && java.util.Objects.equals(this.resolutionBucket, that.resolutionBucket) && java.util.Objects.equals(this.independent, that.independent); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.pageType); hash = 31 * hash + java.util.Objects.hashCode(this.parserStrategy); hash = 31 * hash + java.util.Objects.hashCode(this.schema); hash = 31 * hash + java.util.Objects.hashCode(this.priority); hash = 31 * hash + java.util.Objects.hashCode(this.requiresCrossPageContext); hash = 31 * hash + java.util.Objects.hashCode(this.resolutionBucket); hash = 31 * hash + java.util.Objects.hashCode(this.independent); return hash; }
        @Override public String toString() { return "Decision[" + "pageType=" + pageType + ", parserStrategy=" + parserStrategy + ", schema=" + schema + ", priority=" + priority + ", requiresCrossPageContext=" + requiresCrossPageContext + ", resolutionBucket=" + resolutionBucket + ", independent=" + independent + "]"; }
 }

    public static Decision classify(String nativeText, int imageCount, byte[] image) throws IOException {
        String text = nativeText == null ? "" : cn.bidcheck.compat.Java8Api.strip(nativeText);
        Thumbnail pixels = image == null ? new Thumbnail(0, 0, 0, 0, 0, 0) : thumbnail(image);
        String type;
        boolean context = false, independent = false;
        if (table(text)) { type = "TABLE_PAGE"; context = true; independent = true; }
        else if (contains(text, "机动车驾驶证", "驾驶证", "准驾车型") || containsIgnoreCase(text, "driving license", "driving licence", "driver's license", "driver license")) { type = "DRIVING_LICENSE"; independent = true; }
        else if (contains(text, "机动车行驶证", "行驶证", "车辆识别代号", "发动机号码")) { type = "VEHICLE_LICENSE"; independent = true; }
        else if (contains(text, "身份证", "公民身份号码") || containsIgnoreCase(text, "identity card", "national id card")) { type = "ID_CARD"; independent = true; }
        else if (contains(text, "护照") || containsIgnoreCase(text, "passport")) { type = "PASSPORT"; independent = true; }
        else if (contains(text, "资格证书", "资质证书", "营业执照", "认证证书", "职称证书", "安全生产许可证", "登记证书", "注册证书", "执业资格证")) { type = "CERTIFICATE"; independent = true; }
        else if (contains(text, "签字盖章", "签署页", "签字或盖章", "法定代表人签字", "单位公章") || pixels.redRatio > .015 && pixels.darkRatio < .45) { type = "SIGNATURE_SEAL"; independent = true; }
        else if (contains(text, "合同编号", "合同协议", "甲方", "乙方", "合同续页")) { type = "CONTRACT"; context = true; }
        else if (pixels.darkRatio < .55 && pixels.horizontalLines >= 2 && pixels.verticalLines >= 2) { type = "TABLE_PAGE"; context = true; independent = true; }
        else if (image == null && imageCount == 0 && usableNativeText(text)) type = "TEXT_PAGE";
        else if (contains(text, "投标文件封面", "技术标封面", "商务标封面") && text.length() < 250) type = "COVER";
        else if (cn.bidcheck.compat.Java8Api.isBlank(text) && pixels.colorRatio > .75 && pixels.darkRatio > .7) type = "DECORATION";
        else if (!cn.bidcheck.compat.Java8Api.isBlank(text) || pixels.darkRatio > .005 && pixels.darkRatio < .55 && pixels.textRows >= 3) type = "SCAN_TEXT_PAGE";
        else type = "UNKNOWN";
        String schema;
switch (type) {case "VEHICLE_LICENSE":schema = "VEHICLE_LICENSE_V1"; break;case "DRIVING_LICENSE":schema = "DRIVING_LICENSE_V1"; break;case "ID_CARD":schema = "ID_CARD_V1"; break;case "PASSPORT":schema = "PASSPORT_V1"; break;case "CERTIFICATE":schema = "CERTIFICATE_V1"; break;case "SIGNATURE_SEAL":schema = "SIGNATURE_SEAL_V1"; break;case "TABLE_PAGE":schema = contains(text, "车牌", "车辆", "车架号", "车辆识别代号") ? "VEHICLE_TABLE_V1"
                    : contains(text, "人员", "姓名", "身份证号", "驾驶证号", "护照号", "参保", "缴费")
                    || containsIgnoreCase(text,"name", "id number", "license", "licence", "passport") ? "PERSON_TABLE_V1" : "TABLE_V1"; break;case "CONTRACT":schema = "CONTRACT_V1"; break;default:schema = "DOCUMENT_TEXT_V1"; break;}
        String strategy = image == null && imageCount == 0 && usableNativeText(text) ? "NATIVE_PARSE"
                : independent ? "INDEPENDENT_VISUAL" : context ? "OCR_WITH_CONTEXT_CANDIDATE" : "OCR_MICROBATCH";
        String priority = independent ? "HIGH" : type.equals("COVER") || type.equals("DECORATION") ? "LOW" : "NORMAL";
        long area = (long) pixels.width * pixels.height;
        String bucket = area == 0 ? (imageCount > 0 || !usableNativeText(text) ? "UNRENDERED" : "NATIVE")
                : area <= 500_000 ? "SMALL" : area <= 2_000_000 ? "MEDIUM" : "LARGE";
        return new Decision(type, strategy, schema, priority, context, bucket, independent);
    }

    /** OCR is a first pass: an empty native text layer must not leave certificates and tables UNKNOWN. */
    public static Decision afterRecognition(Decision original, String recognizedText, int imageCount) {
        Decision learned = nativeOnly(recognizedText, Math.max(1, imageCount));
        // Geometry can reveal a table or seal whose text alone cannot. Keep that signal until a
        // stronger textual class is available; a generic OCR paragraph must not erase it.
        Decision result = material(learned) || original == null || !material(original) ? learned : original;
        String resolution = original == null ? learned.resolutionBucket() : original.resolutionBucket();
        return new Decision(result.pageType(), result.parserStrategy(), result.schema(), result.priority(),
                result.requiresCrossPageContext(), resolution, result.independent());
    }

    private static boolean material(Decision decision) {
        return decision.independent() || decision.requiresCrossPageContext();
    }

    private static boolean table(String text) {
        if (contains(text, "车辆信息表", "车辆一览表", "车辆情况表", "人员一览表", "人员情况表", "人员配备表", "人员名单",
                "报价明细表", "报价表", "证书清单", "社保缴费明细", "参保人员明细", "续表")) return true;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("<table") && lower.contains("<tr")) return true;
        // A single tab/pipe can be ordinary prose; require actual rows for an unlabeled table.
        return cn.bidcheck.compat.Java8Extras.lines(text).filter(line -> line.chars().filter(c -> c == '\t').count() >= 2
                || line.chars().filter(c -> c == '|').count() >= 3).limit(2).count() >= 2;
    }

    public static Decision nativeOnly(String nativeText, int imageCount) {
        try { return classify(nativeText, imageCount, null); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Sparse titles are valid native text; only blank or demonstrably broken text needs recovery. */
    public static boolean usableNativeText(String text) {
        if (text == null || cn.bidcheck.compat.Java8Api.isBlank(text)) return false;
        long replacement = text.chars().filter(c -> c == '\uFFFD' || c >= 0xE000 && c <= 0xF8FF).count();
        return replacement == 0 || replacement * 20 < text.codePointCount(0, text.length());
    }

    private static boolean contains(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
    private static boolean containsIgnoreCase(String value, String... words) {
        return contains(value.toLowerCase(Locale.ROOT), words);
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Thumbnail {
        private final int width;
        private final int height;
        private final double darkRatio;
        private final double redRatio;
        private final double colorRatio;
        private final int textRows;
        private final int horizontalLines;
        private final int verticalLines;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Thumbnail(@com.fasterxml.jackson.annotation.JsonProperty("width") int width, @com.fasterxml.jackson.annotation.JsonProperty("height") int height, @com.fasterxml.jackson.annotation.JsonProperty("darkRatio") double darkRatio, @com.fasterxml.jackson.annotation.JsonProperty("redRatio") double redRatio, @com.fasterxml.jackson.annotation.JsonProperty("colorRatio") double colorRatio, @com.fasterxml.jackson.annotation.JsonProperty("textRows") int textRows, @com.fasterxml.jackson.annotation.JsonProperty("horizontalLines") int horizontalLines, @com.fasterxml.jackson.annotation.JsonProperty("verticalLines") int verticalLines) { this.width = width; this.height = height; this.darkRatio = darkRatio; this.redRatio = redRatio; this.colorRatio = colorRatio; this.textRows = textRows; this.horizontalLines = horizontalLines; this.verticalLines = verticalLines; }
        public int width() { return width; }
        public int height() { return height; }
        public double darkRatio() { return darkRatio; }
        public double redRatio() { return redRatio; }
        public double colorRatio() { return colorRatio; }
        public int textRows() { return textRows; }
        public int horizontalLines() { return horizontalLines; }
        public int verticalLines() { return verticalLines; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Thumbnail that = (Thumbnail) other; return java.util.Objects.equals(this.width, that.width) && java.util.Objects.equals(this.height, that.height) && java.util.Objects.equals(this.darkRatio, that.darkRatio) && java.util.Objects.equals(this.redRatio, that.redRatio) && java.util.Objects.equals(this.colorRatio, that.colorRatio) && java.util.Objects.equals(this.textRows, that.textRows) && java.util.Objects.equals(this.horizontalLines, that.horizontalLines) && java.util.Objects.equals(this.verticalLines, that.verticalLines); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.width); hash = 31 * hash + java.util.Objects.hashCode(this.height); hash = 31 * hash + java.util.Objects.hashCode(this.darkRatio); hash = 31 * hash + java.util.Objects.hashCode(this.redRatio); hash = 31 * hash + java.util.Objects.hashCode(this.colorRatio); hash = 31 * hash + java.util.Objects.hashCode(this.textRows); hash = 31 * hash + java.util.Objects.hashCode(this.horizontalLines); hash = 31 * hash + java.util.Objects.hashCode(this.verticalLines); return hash; }
        @Override public String toString() { return "Thumbnail[" + "width=" + width + ", height=" + height + ", darkRatio=" + darkRatio + ", redRatio=" + redRatio + ", colorRatio=" + colorRatio + ", textRows=" + textRows + ", horizontalLines=" + horizontalLines + ", verticalLines=" + verticalLines + "]"; }

        Thumbnail(int width, int height, double darkRatio, double redRatio, int horizontalLines, int verticalLines) {
            this(width, height, darkRatio, redRatio, 0, 0, horizontalLines, verticalLines);
        }
    }
    private static Thumbnail thumbnail(byte[] bytes) throws IOException {
        try (javax.imageio.stream.MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unrecognized classification image");
            javax.imageio.ImageReader reader = readers.next(); BufferedImage image = null;
            try {
                reader.setInput(input, true, true);
                if (!cn.bidcheck.compat.Java8Api.setOf("png", "jpeg", "jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw new IOException("Unsupported classification image");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 16_000_000) throw new IOException("Classification image budget exceeded");
                javax.imageio.ImageReadParam parameters = reader.getDefaultReadParam();
                int step = Math.max(1, (int) Math.ceil(Math.max(width, height) / 192d));
                parameters.setSourceSubsampling(step, step, 0, 0); image = reader.read(0, parameters);
                int w = image.getWidth(), h = image.getHeight(), dark = 0, red = 0, color = 0, horizontal = 0, vertical = 0, textRows = 0;
                int[] columns = new int[w];
                for (int y = 0; y < h; y++) {
                    int row = 0, transitions = 0; boolean last = false;
                    for (int x = 0; x < w; x++) {
                        int rgb = image.getRGB(x, y), r = rgb >>> 16 & 255, g = rgb >>> 8 & 255, b = rgb & 255;
                        boolean ink = (r * 3 + g * 6 + b) / 10 < 150;
                        if (ink) { dark++; row++; columns[x]++; }
                        if (ink != last) transitions++; last = ink;
                        if (r > 100 && r > g * 1.45 && r > b * 1.45) red++;
                        if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 35) color++;
                    }
                    if (row > w * .7) horizontal++;
                    if (transitions >= 6 && row > 1 && row < w * .65) textRows++;
                }
                for (int count : columns) if (count > h * .7) vertical++;
                return new Thumbnail(width, height, (double) dark / (w * h), (double) red / (w * h), (double) color / (w * h), textRows, horizontal, vertical);
            } finally { if (image != null) image.flush(); reader.dispose(); }
        }
    }
}
