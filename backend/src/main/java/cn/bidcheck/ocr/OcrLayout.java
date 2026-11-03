package cn.bidcheck.ocr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Source-bound OCR layout candidates. COMPLETE describes coverage, never factual or identity verification. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class OcrLayout {
        private final String schemaVersion;
        private final String status;
        private final String coordinateSystem;
        private final List<Frame> frames;
        private final List<TextBox> textBoxes;
        private final List<Table> tables;
        private final List<String> readingOrder;
        private final List<String> warnings;
        public String schemaVersion() { return schemaVersion; }
        public String status() { return status; }
        public String coordinateSystem() { return coordinateSystem; }
        public List<Frame> frames() { return frames; }
        public List<TextBox> textBoxes() { return textBoxes; }
        public List<Table> tables() { return tables; }
        public List<String> readingOrder() { return readingOrder; }
        public List<String> warnings() { return warnings; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; OcrLayout that = (OcrLayout) other; return java.util.Objects.equals(this.schemaVersion, that.schemaVersion) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.coordinateSystem, that.coordinateSystem) && java.util.Objects.equals(this.frames, that.frames) && java.util.Objects.equals(this.textBoxes, that.textBoxes) && java.util.Objects.equals(this.tables, that.tables) && java.util.Objects.equals(this.readingOrder, that.readingOrder) && java.util.Objects.equals(this.warnings, that.warnings); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.schemaVersion); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.coordinateSystem); hash = 31 * hash + java.util.Objects.hashCode(this.frames); hash = 31 * hash + java.util.Objects.hashCode(this.textBoxes); hash = 31 * hash + java.util.Objects.hashCode(this.tables); hash = 31 * hash + java.util.Objects.hashCode(this.readingOrder); hash = 31 * hash + java.util.Objects.hashCode(this.warnings); return hash; }
        @Override public String toString() { return "OcrLayout[" + "schemaVersion=" + schemaVersion + ", status=" + status + ", coordinateSystem=" + coordinateSystem + ", frames=" + frames + ", textBoxes=" + textBoxes + ", tables=" + tables + ", readingOrder=" + readingOrder + ", warnings=" + warnings + "]"; }

    public static final String VERSION = "ocr-layout-1";
    public static final String COORDINATES = "PIXEL_TOP_LEFT";
    public static final int MAX_BOXES = 1500, MAX_CELLS = 3000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @com.fasterxml.jackson.annotation.JsonCreator
        public OcrLayout(@com.fasterxml.jackson.annotation.JsonProperty("schemaVersion") String schemaVersion, @com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("coordinateSystem") String coordinateSystem, @com.fasterxml.jackson.annotation.JsonProperty("frames") List<Frame> frames, @com.fasterxml.jackson.annotation.JsonProperty("textBoxes") List<TextBox> textBoxes, @com.fasterxml.jackson.annotation.JsonProperty("tables") List<Table> tables, @com.fasterxml.jackson.annotation.JsonProperty("readingOrder") List<String> readingOrder, @com.fasterxml.jackson.annotation.JsonProperty("warnings") List<String> warnings) {
        frames = copy(frames); textBoxes = copy(textBoxes); tables = copy(tables);
        readingOrder = copy(readingOrder); warnings = copy(warnings);

            this.schemaVersion = schemaVersion; this.status = status; this.coordinateSystem = coordinateSystem; this.frames = frames; this.textBoxes = textBoxes; this.tables = tables; this.readingOrder = readingOrder; this.warnings = warnings;
        }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Box {
        private final double x;
        private final double y;
        private final double width;
        private final double height;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Box(@com.fasterxml.jackson.annotation.JsonProperty("x") double x, @com.fasterxml.jackson.annotation.JsonProperty("y") double y, @com.fasterxml.jackson.annotation.JsonProperty("width") double width, @com.fasterxml.jackson.annotation.JsonProperty("height") double height) { this.x = x; this.y = y; this.width = width; this.height = height; }
        public double x() { return x; }
        public double y() { return y; }
        public double width() { return width; }
        public double height() { return height; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Box that = (Box) other; return java.util.Objects.equals(this.x, that.x) && java.util.Objects.equals(this.y, that.y) && java.util.Objects.equals(this.width, that.width) && java.util.Objects.equals(this.height, that.height); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.x); hash = 31 * hash + java.util.Objects.hashCode(this.y); hash = 31 * hash + java.util.Objects.hashCode(this.width); hash = 31 * hash + java.util.Objects.hashCode(this.height); return hash; }
        @Override public String toString() { return "Box[" + "x=" + x + ", y=" + y + ", width=" + width + ", height=" + height + "]"; }
 }
    /** Coordinates refer to this actual submitted image. A crop maps to its parent's pixel rectangle.
     * Independent Word images have independent frames; sourceIndex is their 1-based block image index. */
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Frame {
        private final String id;
        private final int width;
        private final int height;
        private final String imageHash;
        private final String sourceKind;
        private final int sourceIndex;
        private final Integer parentWidth;
        private final Integer parentHeight;
        private final Box crop;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Frame(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("width") int width, @com.fasterxml.jackson.annotation.JsonProperty("height") int height, @com.fasterxml.jackson.annotation.JsonProperty("imageHash") String imageHash, @com.fasterxml.jackson.annotation.JsonProperty("sourceKind") String sourceKind, @com.fasterxml.jackson.annotation.JsonProperty("sourceIndex") int sourceIndex, @com.fasterxml.jackson.annotation.JsonProperty("parentWidth") Integer parentWidth, @com.fasterxml.jackson.annotation.JsonProperty("parentHeight") Integer parentHeight, @com.fasterxml.jackson.annotation.JsonProperty("crop") Box crop) { this.id = id; this.width = width; this.height = height; this.imageHash = imageHash; this.sourceKind = sourceKind; this.sourceIndex = sourceIndex; this.parentWidth = parentWidth; this.parentHeight = parentHeight; this.crop = crop; }
        public String id() { return id; }
        public int width() { return width; }
        public int height() { return height; }
        public String imageHash() { return imageHash; }
        public String sourceKind() { return sourceKind; }
        public int sourceIndex() { return sourceIndex; }
        public Integer parentWidth() { return parentWidth; }
        public Integer parentHeight() { return parentHeight; }
        public Box crop() { return crop; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Frame that = (Frame) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.width, that.width) && java.util.Objects.equals(this.height, that.height) && java.util.Objects.equals(this.imageHash, that.imageHash) && java.util.Objects.equals(this.sourceKind, that.sourceKind) && java.util.Objects.equals(this.sourceIndex, that.sourceIndex) && java.util.Objects.equals(this.parentWidth, that.parentWidth) && java.util.Objects.equals(this.parentHeight, that.parentHeight) && java.util.Objects.equals(this.crop, that.crop); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.width); hash = 31 * hash + java.util.Objects.hashCode(this.height); hash = 31 * hash + java.util.Objects.hashCode(this.imageHash); hash = 31 * hash + java.util.Objects.hashCode(this.sourceKind); hash = 31 * hash + java.util.Objects.hashCode(this.sourceIndex); hash = 31 * hash + java.util.Objects.hashCode(this.parentWidth); hash = 31 * hash + java.util.Objects.hashCode(this.parentHeight); hash = 31 * hash + java.util.Objects.hashCode(this.crop); return hash; }
        @Override public String toString() { return "Frame[" + "id=" + id + ", width=" + width + ", height=" + height + ", imageHash=" + imageHash + ", sourceKind=" + sourceKind + ", sourceIndex=" + sourceIndex + ", parentWidth=" + parentWidth + ", parentHeight=" + parentHeight + ", crop=" + crop + "]"; }
 }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class TextBox {
        private final String id;
        private final String frameId;
        private final String text;
        private final Box bbox;
        private final Double confidence;
        private final String source;
        private final Box originalBbox;
        @com.fasterxml.jackson.annotation.JsonCreator
        public TextBox(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("frameId") String frameId, @com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("bbox") Box bbox, @com.fasterxml.jackson.annotation.JsonProperty("confidence") Double confidence, @com.fasterxml.jackson.annotation.JsonProperty("source") String source, @com.fasterxml.jackson.annotation.JsonProperty("originalBbox") Box originalBbox) { this.id = id; this.frameId = frameId; this.text = text; this.bbox = bbox; this.confidence = confidence; this.source = source; this.originalBbox = originalBbox; }
        public String id() { return id; }
        public String frameId() { return frameId; }
        public String text() { return text; }
        public Box bbox() { return bbox; }
        public Double confidence() { return confidence; }
        public String source() { return source; }
        public Box originalBbox() { return originalBbox; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; TextBox that = (TextBox) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.frameId, that.frameId) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.bbox, that.bbox) && java.util.Objects.equals(this.confidence, that.confidence) && java.util.Objects.equals(this.source, that.source) && java.util.Objects.equals(this.originalBbox, that.originalBbox); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.frameId); hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.bbox); hash = 31 * hash + java.util.Objects.hashCode(this.confidence); hash = 31 * hash + java.util.Objects.hashCode(this.source); hash = 31 * hash + java.util.Objects.hashCode(this.originalBbox); return hash; }
        @Override public String toString() { return "TextBox[" + "id=" + id + ", frameId=" + frameId + ", text=" + text + ", bbox=" + bbox + ", confidence=" + confidence + ", source=" + source + ", originalBbox=" + originalBbox + "]"; }

        public TextBox(String id, String frameId, String text, Box bbox, Double confidence) { this(id, frameId, text, bbox, confidence, null, null); }
        public TextBox(String id, String frameId, String text, Box bbox, Double confidence, String source) { this(id, frameId, text, bbox, confidence, source, null); }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Cell {
        private final String id;
        private final int row;
        private final int column;
        private final int rowSpan;
        private final int columnSpan;
        private final String text;
        private final Box bbox;
        private final List<String> blockIds;
        private final List<String> headerCellIds;
        private final boolean header;
        public String id() { return id; }
        public int row() { return row; }
        public int column() { return column; }
        public int rowSpan() { return rowSpan; }
        public int columnSpan() { return columnSpan; }
        public String text() { return text; }
        public Box bbox() { return bbox; }
        public List<String> blockIds() { return blockIds; }
        public List<String> headerCellIds() { return headerCellIds; }
        public boolean header() { return header; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Cell that = (Cell) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.row, that.row) && java.util.Objects.equals(this.column, that.column) && java.util.Objects.equals(this.rowSpan, that.rowSpan) && java.util.Objects.equals(this.columnSpan, that.columnSpan) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.bbox, that.bbox) && java.util.Objects.equals(this.blockIds, that.blockIds) && java.util.Objects.equals(this.headerCellIds, that.headerCellIds) && java.util.Objects.equals(this.header, that.header); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.row); hash = 31 * hash + java.util.Objects.hashCode(this.column); hash = 31 * hash + java.util.Objects.hashCode(this.rowSpan); hash = 31 * hash + java.util.Objects.hashCode(this.columnSpan); hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.bbox); hash = 31 * hash + java.util.Objects.hashCode(this.blockIds); hash = 31 * hash + java.util.Objects.hashCode(this.headerCellIds); hash = 31 * hash + java.util.Objects.hashCode(this.header); return hash; }
        @Override public String toString() { return "Cell[" + "id=" + id + ", row=" + row + ", column=" + column + ", rowSpan=" + rowSpan + ", columnSpan=" + columnSpan + ", text=" + text + ", bbox=" + bbox + ", blockIds=" + blockIds + ", headerCellIds=" + headerCellIds + ", header=" + header + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Cell(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("row") int row, @com.fasterxml.jackson.annotation.JsonProperty("column") int column, @com.fasterxml.jackson.annotation.JsonProperty("rowSpan") int rowSpan, @com.fasterxml.jackson.annotation.JsonProperty("columnSpan") int columnSpan, @com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("bbox") Box bbox, @com.fasterxml.jackson.annotation.JsonProperty("blockIds") List<String> blockIds, @com.fasterxml.jackson.annotation.JsonProperty("headerCellIds") List<String> headerCellIds, @com.fasterxml.jackson.annotation.JsonProperty("header") boolean header) { blockIds = copy(blockIds); headerCellIds = copy(headerCellIds);
            this.id = id; this.row = row; this.column = column; this.rowSpan = rowSpan; this.columnSpan = columnSpan; this.text = text; this.bbox = bbox; this.blockIds = blockIds; this.headerCellIds = headerCellIds; this.header = header;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Table {
        private final String id;
        private final String frameId;
        private final int rows;
        private final int columns;
        private final List<Cell> cells;
        public String id() { return id; }
        public String frameId() { return frameId; }
        public int rows() { return rows; }
        public int columns() { return columns; }
        public List<Cell> cells() { return cells; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Table that = (Table) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.frameId, that.frameId) && java.util.Objects.equals(this.rows, that.rows) && java.util.Objects.equals(this.columns, that.columns) && java.util.Objects.equals(this.cells, that.cells); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.frameId); hash = 31 * hash + java.util.Objects.hashCode(this.rows); hash = 31 * hash + java.util.Objects.hashCode(this.columns); hash = 31 * hash + java.util.Objects.hashCode(this.cells); return hash; }
        @Override public String toString() { return "Table[" + "id=" + id + ", frameId=" + frameId + ", rows=" + rows + ", columns=" + columns + ", cells=" + cells + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Table(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("frameId") String frameId, @com.fasterxml.jackson.annotation.JsonProperty("rows") int rows, @com.fasterxml.jackson.annotation.JsonProperty("columns") int columns, @com.fasterxml.jackson.annotation.JsonProperty("cells") List<Cell> cells) { cells = copy(cells);
            this.id = id; this.frameId = frameId; this.rows = rows; this.columns = columns; this.cells = cells;
        }
    }

    public Map<String, Object> toMap() { return MAPPER.convertValue(this, new TypeReference<Map<String, Object>>() { }); }

    public static OcrLayout unavailable(String reason) {
        return new OcrLayout(VERSION, "UNAVAILABLE", COORDINATES, cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(reason));
    }

    /** Fail closed on forged text, broken references, invalid geometry or excessive output.
     * Whitespace differences are tolerated; punctuation, numbers and characters are never corrected. */
    public OcrLayout validated(String transcription) {
        require(VERSION.equals(schemaVersion) && COORDINATES.equals(coordinateSystem), "布局版本或坐标系无效");
        require(cn.bidcheck.compat.Java8Api.setOf("COMPLETE", "PARTIAL", "UNAVAILABLE").contains(status), "布局状态无效");
        require(frames.size() <= 32 && textBoxes.size() <= MAX_BOXES && tables.size() <= 40
                && readingOrder.size() <= MAX_BOXES && warnings.size() <= 32, "布局预算超限");
        warnings.forEach(w -> require(w != null && w.length() <= 500, "布局提示超限"));
        if (status.equals("UNAVAILABLE")) {
            require(frames.isEmpty() && textBoxes.isEmpty() && tables.isEmpty() && readingOrder.isEmpty(), "不可用布局含数据");
            return this;
        }
        require(transcription != null && transcription.length() <= 200_000 && !frames.isEmpty() && !textBoxes.isEmpty(), "布局缺少转录来源");
        String source = compact(transcription);
        Map<String, Frame> byFrame = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (Frame f : frames) {
            id(f.id(), ids);
            require(f.width() > 0 && f.height() > 0 && f.width() <= 32768 && f.height() <= 32768
                    && (long) f.width() * f.height() <= 16_000_000, "图像尺寸无效");
            require(f.imageHash() != null && f.imageHash().matches("[0-9a-f]{64}"), "图像指纹无效");
            require(f.sourceKind() != null && f.sourceKind().matches("[A-Z_]{1,40}") && f.sourceIndex() >= 0, "图像来源无效");
            require((f.parentWidth() == null) == (f.parentHeight() == null) && (f.crop() == null) == (f.parentWidth() == null), "裁图来源不完整");
            if (f.crop() != null) {
                require(f.parentWidth() > 0 && f.parentWidth() <= 32768 && f.parentHeight() > 0 && f.parentHeight() <= 32768, "裁图来源尺寸无效");
                box(f.crop(), f.parentWidth(), f.parentHeight());
            }
            byFrame.put(f.id(), f);
        }
        Map<String, TextBox> byBlock = new HashMap<>();
        int characters = 0, cellCount = 0;
        for (TextBox b : textBoxes) {
            id(b.id(), ids);
            Frame f = byFrame.get(b.frameId()); require(f != null, "文字框缺少图像来源");
            box(b.bbox(), f.width(), f.height());
            require(b.text() != null && !compact(b.text()).isEmpty() && b.text().length() <= 10000 && b.text().indexOf('\0') < 0
                    && source.contains(compact(b.text())), "文字框未对齐原始转录");
            characters += b.text().length(); require(characters <= 200_000, "文字框预算超限");
            require(b.confidence() == null || Double.isFinite(b.confidence()) && b.confidence() >= 0 && b.confidence() <= 1, "置信度无效");
            require(b.source() == null || cn.bidcheck.compat.Java8Api.setOf("NATIVE_OCR", "RAPIDOCR", "CONSENSUS_OCR").contains(b.source()), "文字框来源无效");
            require((b.originalBbox() != null) == "CONSENSUS_OCR".equals(b.source()), "文字框几何仲裁来源不完整");
            if (b.originalBbox() != null) box(b.originalBbox(), f.width(), f.height());
            byBlock.put(b.id(), b);
        }
        require(new HashSet<>(readingOrder).size() == readingOrder.size() && new HashSet<>(readingOrder).equals(byBlock.keySet()), "读取顺序引用无效");
        int sourceOffset = 0;
        for (String blockId : readingOrder) {
            String text = compact(byBlock.get(blockId).text());
            int found = source.indexOf(text, sourceOffset);
            require(found >= 0, "读取顺序或重复文字未对应独立转录位置");
            sourceOffset = found + text.length();
        }
        Set<String> usedBlocks = new HashSet<>();
        for (Table t : tables) {
            id(t.id(), ids); Frame f = byFrame.get(t.frameId()); require(f != null, "表格缺少图像来源");
            require(t.rows() > 0 && t.rows() <= 500 && t.columns() > 0 && t.columns() <= 100
                    && (long) t.rows() * t.columns() <= 10000 && !t.cells().isEmpty(), "表格范围无效");
            cellCount += t.cells().size(); require(cellCount <= MAX_CELLS, "单元格预算超限");
            Map<String, Cell> cells = new HashMap<>();
            Set<Integer> occupied = new HashSet<>();
            for (Cell c : t.cells()) {
                id(c.id(), ids); cells.put(c.id(), c);
                require(c.row() >= 0 && c.column() >= 0 && c.rowSpan() > 0 && c.columnSpan() > 0
                        && (long)c.row() + c.rowSpan() <= t.rows() && (long)c.column() + c.columnSpan() <= t.columns(), "单元格位置无效");
                box(c.bbox(), f.width(), f.height());
                for (int r = c.row(); r < c.row() + c.rowSpan(); r++)
                    for (int col = c.column(); col < c.column() + c.columnSpan(); col++) require(occupied.add(r * t.columns() + col), "单元格相互覆盖");
                require(c.blockIds().size() <= 100 && c.headerCellIds().size() <= 100 && new HashSet<>(c.blockIds()).size() == c.blockIds().size(), "单元格引用超限或重复");
                StringBuilder joined = new StringBuilder();
                for (String blockId : c.blockIds()) {
                    TextBox b = byBlock.get(blockId);
                    require(b != null && t.frameId().equals(b.frameId()) && contains(c.bbox(), b.bbox()) && usedBlocks.add(blockId), "单元格文字框来源或位置无效");
                    joined.append(compact(b.text()));
                }
                require(c.text() != null && c.text().length() <= 10000 && compact(c.text()).equals(joined.toString()), "单元格文字未对应真实文字框");
            }
            geometry(t.cells(), true, t.rows());
            geometry(t.cells(), false, t.columns());
            for (Cell c : t.cells()) {
                Set<String> headers = new HashSet<>();
                for (String headerId : c.headerCellIds()) {
                    Cell h = cells.get(headerId);
                    require(h != null && h.header() && !compact(h.text()).isEmpty() && !h.id().equals(c.id()) && headers.add(headerId), "表头必须引用本表真实表头单元格");
                    boolean above = h.row() + h.rowSpan() <= c.row() && h.column() <= c.column() && h.column() + h.columnSpan() >= c.column() + c.columnSpan();
                    boolean left = h.column() + h.columnSpan() <= c.column() && h.row() <= c.row() && h.row() + h.rowSpan() >= c.row() + c.rowSpan();
                    require(above || left, "表头与单元格的行列覆盖不一致");
                }
            }
        }
        StringBuilder ordered = new StringBuilder(); readingOrder.forEach(id -> ordered.append(compact(byBlock.get(id).text())));
        if (status.equals("COMPLETE") && !ordered.toString().equals(source)) {
            List<String> notes = new ArrayList<>(warnings);
            if (notes.size() < 32) notes.add("布局仅对齐部分转录；未覆盖文字没有位置，不代表原件缺失。");
            return new OcrLayout(schemaVersion, "PARTIAL", coordinateSystem, frames, textBoxes, tables, readingOrder, notes);
        }
        return this;
    }

    /** Prefix every reference before combining independent images; no inferred geometry between them. */
    public OcrLayout source(String prefix, String kind, int index) {
        if (status.equals("UNAVAILABLE")) return this;
        require(prefix != null && prefix.matches("[A-Za-z0-9_-]{0,30}"), "布局来源前缀无效");
        return new OcrLayout(schemaVersion, status, coordinateSystem,
                cn.bidcheck.compat.Java8Api.toList(frames.stream().map(f -> new Frame(prefix+f.id(), f.width(), f.height(), f.imageHash(), kind, index, f.parentWidth(), f.parentHeight(), f.crop()))),
                cn.bidcheck.compat.Java8Api.toList(textBoxes.stream().map(b -> new TextBox(prefix+b.id(), prefix+b.frameId(), b.text(), b.bbox(), b.confidence(), b.source(), b.originalBbox()))),
                cn.bidcheck.compat.Java8Api.toList(tables.stream().map(t -> new Table(prefix+t.id(), prefix+t.frameId(), t.rows(), t.columns(), cn.bidcheck.compat.Java8Api.toList(t.cells().stream()
                        .map(c -> new Cell(prefix+c.id(), c.row(), c.column(), c.rowSpan(), c.columnSpan(), c.text(), c.bbox(),
                                cn.bidcheck.compat.Java8Api.toList(c.blockIds().stream().map(id -> prefix+id)), cn.bidcheck.compat.Java8Api.toList(c.headerCellIds().stream().map(id -> prefix+id)), c.header())))))),
                cn.bidcheck.compat.Java8Api.toList(readingOrder.stream().map(id -> prefix+id)), warnings);
    }

    public OcrLayout crop(int parentWidth, int parentHeight, Box crop) {
        if (status.equals("UNAVAILABLE")) return this;
        return new OcrLayout(schemaVersion, "PARTIAL", coordinateSystem, cn.bidcheck.compat.Java8Api.toList(frames.stream()
                .map(f -> new Frame(f.id(), f.width(), f.height(), f.imageHash(), "IMAGE_CROP", f.sourceIndex(), parentWidth, parentHeight, crop))),
                textBoxes, tables, readingOrder, cn.bidcheck.compat.Java8Api.listOf("独立裁图坐标；边界可能重复，未重建跨裁图表格或读取顺序。"));
    }

    public static OcrLayout merge(List<OcrLayout> layouts, boolean partial, String transcription) {
        if (layouts == null || layouts.isEmpty()) return unavailable("未获得图像结构；转录文字仍可用。");
        List<Frame> frames = new ArrayList<>(); List<TextBox> boxes = new ArrayList<>(); List<Table> tables = new ArrayList<>();
        List<String> order = new ArrayList<>(); LinkedHashSet<String> notes = new LinkedHashSet<>();
        for (OcrLayout layout : layouts) {
            if (layout == null || !"COMPLETE".equals(layout.status())) partial = true;
            if (layout == null) continue;
            frames.addAll(layout.frames()); boxes.addAll(layout.textBoxes()); tables.addAll(layout.tables()); order.addAll(layout.readingOrder()); notes.addAll(layout.warnings());
        }
        if (boxes.isEmpty()) return unavailable("未获得图像结构；转录文字仍可用。");
        try { return new OcrLayout(VERSION, partial ? "PARTIAL" : "COMPLETE", COORDINATES, frames, boxes, tables, order, cn.bidcheck.compat.Java8Api.toList(notes.stream().limit(32))).validated(transcription); }
        catch (IllegalArgumentException invalid) { return unavailable("合并布局超过预算或无法安全对齐；保留转录，不推断图像间关系。"); }
    }

    private static <T> List<T> copy(List<T> value) { return value == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(value); }
    private static String compact(String value) { return value.replaceAll("[\\s\\p{Z}]+", ""); }
    private static void id(String value, Set<String> ids) { require(value != null && value.matches("[A-Za-z0-9_-]{1,120}") && ids.add(value), "布局ID无效或重复"); }
    private static void box(Box b, int width, int height) {
        require(b != null && Double.isFinite(b.x()) && Double.isFinite(b.y()) && Double.isFinite(b.width()) && Double.isFinite(b.height())
                && b.x() >= 0 && b.y() >= 0 && b.width() > 0 && b.height() > 0 && b.x()+b.width() <= width && b.y()+b.height() <= height, "坐标越出真实图像");
    }
    private static boolean contains(Box outer, Box inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y() && inner.x()+inner.width() <= outer.x()+outer.width() && inner.y()+inner.height() <= outer.y()+outer.height();
    }
    /** Logical bands must have a common geometric intersection. Disjoint logical bands cannot
     * reverse/overlap their geometric order. Spans are bounded by the table's 10000-slot budget. */
    private static void geometry(List<Cell> cells, boolean row, int bands) {
        double[] lower = new double[bands], upper = new double[bands];
        Arrays.fill(lower, Double.NEGATIVE_INFINITY); Arrays.fill(upper, Double.POSITIVE_INFINITY);
        for (Cell c : cells) {
            int start = row ? c.row() : c.column(), span = row ? c.rowSpan() : c.columnSpan();
            double lo = row ? c.bbox().y() : c.bbox().x(), hi = lo + (row ? c.bbox().height() : c.bbox().width());
            for (int i = start; i < start + span; i++) { lower[i] = Math.max(lower[i], lo); upper[i] = Math.min(upper[i], hi); }
        }
        for (int i = 0; i < bands; i++) require(lower[i] < upper[i], "表格同一行列的几何范围不相交");
        List<Cell> starts = cn.bidcheck.compat.Java8Api.toList(cells.stream().sorted(Comparator.comparingInt(c -> row ? c.row() : c.column())));
        List<Cell> ends = cn.bidcheck.compat.Java8Api.toList(cells.stream().sorted(Comparator.comparingInt(c -> row ? c.row()+c.rowSpan() : c.column()+c.columnSpan())));
        int endIndex = 0; double completedEdge = Double.NEGATIVE_INFINITY;
        for (Cell current : starts) {
            int start = row ? current.row() : current.column();
            while (endIndex < ends.size()) {
                Cell previous = ends.get(endIndex);
                int end = row ? previous.row()+previous.rowSpan() : previous.column()+previous.columnSpan();
                if (end > start) break;
                completedEdge = Math.max(completedEdge, row ? previous.bbox().y()+previous.bbox().height() : previous.bbox().x()+previous.bbox().width());
                endIndex++;
            }
            require(completedEdge <= (row ? current.bbox().y() : current.bbox().x()), "表格行列编号与几何顺序不一致");
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
