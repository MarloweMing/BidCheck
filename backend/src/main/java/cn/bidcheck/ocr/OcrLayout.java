package cn.bidcheck.ocr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Source-bound OCR layout candidates. COMPLETE describes coverage, never factual or identity verification. */
public record OcrLayout(String schemaVersion, String status, String coordinateSystem,
                        List<Frame> frames, List<TextBox> textBoxes, List<Table> tables,
                        List<String> readingOrder, List<String> warnings) {
    public static final String VERSION = "ocr-layout-1";
    public static final String COORDINATES = "PIXEL_TOP_LEFT";
    public static final int MAX_BOXES = 1500, MAX_CELLS = 3000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public OcrLayout {
        frames = copy(frames); textBoxes = copy(textBoxes); tables = copy(tables);
        readingOrder = copy(readingOrder); warnings = copy(warnings);
    }
    public record Box(double x, double y, double width, double height) { }
    /** Coordinates refer to this actual submitted image. A crop maps to its parent's pixel rectangle.
     * Independent Word images have independent frames; sourceIndex is their 1-based block image index. */
    public record Frame(String id, int width, int height, String imageHash, String sourceKind, int sourceIndex,
                        Integer parentWidth, Integer parentHeight, Box crop) { }
    public record TextBox(String id, String frameId, String text, Box bbox, Double confidence, String source, Box originalBbox) {
        public TextBox(String id, String frameId, String text, Box bbox, Double confidence) { this(id, frameId, text, bbox, confidence, null, null); }
        public TextBox(String id, String frameId, String text, Box bbox, Double confidence, String source) { this(id, frameId, text, bbox, confidence, source, null); }
    }
    public record Cell(String id, int row, int column, int rowSpan, int columnSpan, String text, Box bbox,
                       List<String> blockIds, List<String> headerCellIds, boolean header) {
        public Cell { blockIds = copy(blockIds); headerCellIds = copy(headerCellIds); }
    }
    public record Table(String id, String frameId, int rows, int columns, List<Cell> cells) {
        public Table { cells = copy(cells); }
    }

    public Map<String, Object> toMap() { return MAPPER.convertValue(this, new TypeReference<>() { }); }

    public static OcrLayout unavailable(String reason) {
        return new OcrLayout(VERSION, "UNAVAILABLE", COORDINATES, List.of(), List.of(), List.of(), List.of(), List.of(reason));
    }

    /** Fail closed on forged text, broken references, invalid geometry or excessive output.
     * Whitespace differences are tolerated; punctuation, numbers and characters are never corrected. */
    public OcrLayout validated(String transcription) {
        require(VERSION.equals(schemaVersion) && COORDINATES.equals(coordinateSystem), "布局版本或坐标系无效");
        require(Set.of("COMPLETE", "PARTIAL", "UNAVAILABLE").contains(status), "布局状态无效");
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
            require(b.source() == null || Set.of("NATIVE_OCR", "RAPIDOCR", "CONSENSUS_OCR").contains(b.source()), "文字框来源无效");
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
                frames.stream().map(f -> new Frame(prefix+f.id(), f.width(), f.height(), f.imageHash(), kind, index, f.parentWidth(), f.parentHeight(), f.crop())).toList(),
                textBoxes.stream().map(b -> new TextBox(prefix+b.id(), prefix+b.frameId(), b.text(), b.bbox(), b.confidence(), b.source(), b.originalBbox())).toList(),
                tables.stream().map(t -> new Table(prefix+t.id(), prefix+t.frameId(), t.rows(), t.columns(), t.cells().stream()
                        .map(c -> new Cell(prefix+c.id(), c.row(), c.column(), c.rowSpan(), c.columnSpan(), c.text(), c.bbox(),
                                c.blockIds().stream().map(id -> prefix+id).toList(), c.headerCellIds().stream().map(id -> prefix+id).toList(), c.header())).toList())).toList(),
                readingOrder.stream().map(id -> prefix+id).toList(), warnings);
    }

    public OcrLayout crop(int parentWidth, int parentHeight, Box crop) {
        if (status.equals("UNAVAILABLE")) return this;
        return new OcrLayout(schemaVersion, "PARTIAL", coordinateSystem, frames.stream()
                .map(f -> new Frame(f.id(), f.width(), f.height(), f.imageHash(), "IMAGE_CROP", f.sourceIndex(), parentWidth, parentHeight, crop)).toList(),
                textBoxes, tables, readingOrder, List.of("独立裁图坐标；边界可能重复，未重建跨裁图表格或读取顺序。"));
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
        try { return new OcrLayout(VERSION, partial ? "PARTIAL" : "COMPLETE", COORDINATES, frames, boxes, tables, order, notes.stream().limit(32).toList()).validated(transcription); }
        catch (IllegalArgumentException invalid) { return unavailable("合并布局超过预算或无法安全对齐；保留转录，不推断图像间关系。"); }
    }

    private static <T> List<T> copy(List<T> value) { return value == null ? List.of() : List.copyOf(value); }
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
        List<Cell> starts = cells.stream().sorted(Comparator.comparingInt(c -> row ? c.row() : c.column())).toList();
        List<Cell> ends = cells.stream().sorted(Comparator.comparingInt(c -> row ? c.row()+c.rowSpan() : c.column()+c.columnSpan())).toList();
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
