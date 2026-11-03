package cn.bidcheck.ocr;
import cn.bidcheck.compat.HexFormat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static cn.bidcheck.ocr.OcrLayout.*;

/** Joins independently validated text boxes to explicitly declared grids. No model-provided text or references are accepted. */
public final class GridLayoutAssembler {
    public static final String VERSION="grid-layout-assembler-2-headers";
    public static final String PIXEL_XYXY="PIXEL_XYXY", NORMALIZED_1000_XYXY="NORMALIZED_1000_XYXY";
    private static final Set<String> HEADER_LABELS=cn.bidcheck.compat.Java8Api.setOf("序号", "姓名", "人员姓名", "身份证号", "身份证号码", "公民身份号码", "参保人姓名", "缴费单位", "参保单位", "单位名称", "缴费月份", "缴费年月", "月份", "缴费所属期", "所属年月", "证书状态", "证书形式", "证号", "企业名称", "法人代表", "行政相对人代码", "注册地址", "经济类型", "许可范围", "许可机关", "核发日期", "前一次有效期", "延期有效期", "证书名称", "证书类别", "证书编号", "注册编号", "证书等级", "级别", "专业", "岗位", "职务", "拟任岗位", "有效期", "注册有效期", "使用有效期");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Region {
        private final int row;
        private final int column;
        private final int rowSpan;
        private final int columnSpan;
        private final List<Double> box;
        private final boolean header;
        public int row() { return row; }
        public int column() { return column; }
        public int rowSpan() { return rowSpan; }
        public int columnSpan() { return columnSpan; }
        public List<Double> box() { return box; }
        public boolean header() { return header; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Region that = (Region) other; return java.util.Objects.equals(this.row, that.row) && java.util.Objects.equals(this.column, that.column) && java.util.Objects.equals(this.rowSpan, that.rowSpan) && java.util.Objects.equals(this.columnSpan, that.columnSpan) && java.util.Objects.equals(this.box, that.box) && java.util.Objects.equals(this.header, that.header); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.row); hash = 31 * hash + java.util.Objects.hashCode(this.column); hash = 31 * hash + java.util.Objects.hashCode(this.rowSpan); hash = 31 * hash + java.util.Objects.hashCode(this.columnSpan); hash = 31 * hash + java.util.Objects.hashCode(this.box); hash = 31 * hash + java.util.Objects.hashCode(this.header); return hash; }
        @Override public String toString() { return "Region[" + "row=" + row + ", column=" + column + ", rowSpan=" + rowSpan + ", columnSpan=" + columnSpan + ", box=" + box + ", header=" + header + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Region(@com.fasterxml.jackson.annotation.JsonProperty("row") int row, @com.fasterxml.jackson.annotation.JsonProperty("column") int column, @com.fasterxml.jackson.annotation.JsonProperty("rowSpan") int rowSpan, @com.fasterxml.jackson.annotation.JsonProperty("columnSpan") int columnSpan, @com.fasterxml.jackson.annotation.JsonProperty("box") List<Double> box, @com.fasterxml.jackson.annotation.JsonProperty("header") boolean header) { box=box==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listCopyOf(box);
            this.row = row; this.column = column; this.rowSpan = rowSpan; this.columnSpan = columnSpan; this.box = box; this.header = header;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Grid {
        private final String frameId;
        private final String coordinateSpace;
        private final int rows;
        private final int columns;
        private final List<Region> cells;
        public String frameId() { return frameId; }
        public String coordinateSpace() { return coordinateSpace; }
        public int rows() { return rows; }
        public int columns() { return columns; }
        public List<Region> cells() { return cells; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Grid that = (Grid) other; return java.util.Objects.equals(this.frameId, that.frameId) && java.util.Objects.equals(this.coordinateSpace, that.coordinateSpace) && java.util.Objects.equals(this.rows, that.rows) && java.util.Objects.equals(this.columns, that.columns) && java.util.Objects.equals(this.cells, that.cells); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.frameId); hash = 31 * hash + java.util.Objects.hashCode(this.coordinateSpace); hash = 31 * hash + java.util.Objects.hashCode(this.rows); hash = 31 * hash + java.util.Objects.hashCode(this.columns); hash = 31 * hash + java.util.Objects.hashCode(this.cells); return hash; }
        @Override public String toString() { return "Grid[" + "frameId=" + frameId + ", coordinateSpace=" + coordinateSpace + ", rows=" + rows + ", columns=" + columns + ", cells=" + cells + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Grid(@com.fasterxml.jackson.annotation.JsonProperty("frameId") String frameId, @com.fasterxml.jackson.annotation.JsonProperty("coordinateSpace") String coordinateSpace, @com.fasterxml.jackson.annotation.JsonProperty("rows") int rows, @com.fasterxml.jackson.annotation.JsonProperty("columns") int columns, @com.fasterxml.jackson.annotation.JsonProperty("cells") List<Region> cells) { cells=cells==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listCopyOf(cells);
            this.frameId = frameId; this.coordinateSpace = coordinateSpace; this.rows = rows; this.columns = columns; this.cells = cells;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Diagnostic {
        private final String code;
        private final int gridIndex;
        private final String frameId;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Diagnostic(@com.fasterxml.jackson.annotation.JsonProperty("code") String code, @com.fasterxml.jackson.annotation.JsonProperty("gridIndex") int gridIndex, @com.fasterxml.jackson.annotation.JsonProperty("frameId") String frameId) { this.code = code; this.gridIndex = gridIndex; this.frameId = frameId; }
        public String code() { return code; }
        public int gridIndex() { return gridIndex; }
        public String frameId() { return frameId; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Diagnostic that = (Diagnostic) other; return java.util.Objects.equals(this.code, that.code) && java.util.Objects.equals(this.gridIndex, that.gridIndex) && java.util.Objects.equals(this.frameId, that.frameId); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.code); hash = 31 * hash + java.util.Objects.hashCode(this.gridIndex); hash = 31 * hash + java.util.Objects.hashCode(this.frameId); return hash; }
        @Override public String toString() { return "Diagnostic[" + "code=" + code + ", gridIndex=" + gridIndex + ", frameId=" + frameId + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Assembly {
        private final OcrLayout layout;
        private final List<Diagnostic> diagnostics;
        public OcrLayout layout() { return layout; }
        public List<Diagnostic> diagnostics() { return diagnostics; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Assembly that = (Assembly) other; return java.util.Objects.equals(this.layout, that.layout) && java.util.Objects.equals(this.diagnostics, that.diagnostics); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.layout); hash = 31 * hash + java.util.Objects.hashCode(this.diagnostics); return hash; }
        @Override public String toString() { return "Assembly[" + "layout=" + layout + ", diagnostics=" + diagnostics + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Assembly(@com.fasterxml.jackson.annotation.JsonProperty("layout") OcrLayout layout, @com.fasterxml.jackson.annotation.JsonProperty("diagnostics") List<Diagnostic> diagnostics) { diagnostics=cn.bidcheck.compat.Java8Api.listCopyOf(diagnostics);
            this.layout = layout; this.diagnostics = diagnostics;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Positioned {
        private final Region region;
        private final Box box;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Positioned(@com.fasterxml.jackson.annotation.JsonProperty("region") Region region, @com.fasterxml.jackson.annotation.JsonProperty("box") Box box) { this.region = region; this.box = box; }
        public Region region() { return region; }
        public Box box() { return box; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Positioned that = (Positioned) other; return java.util.Objects.equals(this.region, that.region) && java.util.Objects.equals(this.box, that.box); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.region); hash = 31 * hash + java.util.Objects.hashCode(this.box); return hash; }
        @Override public String toString() { return "Positioned[" + "region=" + region + ", box=" + box + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Prepared {
        private final int index;
        private final Grid grid;
        private final Frame frame;
        private final List<Positioned> cells;
        private final Box extent;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Prepared(@com.fasterxml.jackson.annotation.JsonProperty("index") int index, @com.fasterxml.jackson.annotation.JsonProperty("grid") Grid grid, @com.fasterxml.jackson.annotation.JsonProperty("frame") Frame frame, @com.fasterxml.jackson.annotation.JsonProperty("cells") List<Positioned> cells, @com.fasterxml.jackson.annotation.JsonProperty("extent") Box extent) { this.index = index; this.grid = grid; this.frame = frame; this.cells = cells; this.extent = extent; }
        public int index() { return index; }
        public Grid grid() { return grid; }
        public Frame frame() { return frame; }
        public List<Positioned> cells() { return cells; }
        public Box extent() { return extent; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Prepared that = (Prepared) other; return java.util.Objects.equals(this.index, that.index) && java.util.Objects.equals(this.grid, that.grid) && java.util.Objects.equals(this.frame, that.frame) && java.util.Objects.equals(this.cells, that.cells) && java.util.Objects.equals(this.extent, that.extent); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.index); hash = 31 * hash + java.util.Objects.hashCode(this.grid); hash = 31 * hash + java.util.Objects.hashCode(this.frame); hash = 31 * hash + java.util.Objects.hashCode(this.cells); hash = 31 * hash + java.util.Objects.hashCode(this.extent); return hash; }
        @Override public String toString() { return "Prepared[" + "index=" + index + ", grid=" + grid + ", frame=" + frame + ", cells=" + cells + ", extent=" + extent + "]"; }
}

    /** Invalid grids affect their tables only. An invalid text source is a caller error and is never repaired or guessed. */
    public Assembly assemble(OcrLayout textLayout,String transcription,List<Grid> grids) {
        Objects.requireNonNull(textLayout,"textLayout");
        OcrLayout base=new OcrLayout(textLayout.schemaVersion(),textLayout.status(),textLayout.coordinateSystem(),textLayout.frames(),textLayout.textBoxes(),cn.bidcheck.compat.Java8Api.listOf(),textLayout.readingOrder(),textLayout.warnings()).validated(transcription);
        List<Diagnostic> diagnostics=new ArrayList<>();
        if(!textLayout.tables().isEmpty())diagnostics.add(new Diagnostic("INPUT_TABLES_IGNORED",-1,""));
        if(grids==null){grids=cn.bidcheck.compat.Java8Api.listOf();diagnostics.add(new Diagnostic("GRID_RESPONSE_MISSING",-1,""));}
        if("UNAVAILABLE".equals(base.status()))return new Assembly(base,cn.bidcheck.compat.Java8Api.listOf(new Diagnostic("TEXT_BOXES_UNAVAILABLE",-1,"")));
        Map<String,Frame> frames=new HashMap<>();base.frames().forEach(f->frames.put(f.id(),f));
        List<Prepared> prepared=new ArrayList<>();
        int count=Math.min(40,grids.size());if(grids.size()>40)diagnostics.add(new Diagnostic("GRID_COUNT_LIMIT",40,""));
        for(int index=0;index<count;index++){
            Grid grid=grids.get(index);
            try{prepared.add(prepare(index,grid,frames));}
            catch(GridFailure invalid){diagnostics.add(new Diagnostic(invalid.code,index,grid==null?"":Objects.toString(grid.frameId(),"")));}
        }
        // Reject both contenders: taking the first overlapping table would silently choose one interpretation.
        Set<Integer> overlapping=new HashSet<>();
        for(int i=0;i<prepared.size();i++)for(int j=i+1;j<prepared.size();j++){
            Prepared a=prepared.get(i),b=prepared.get(j);
            if(a.frame.id().equals(b.frame.id())&&intersects(a.extent,b.extent)){overlapping.add(a.index);overlapping.add(b.index);}
        }
        List<Table> tables=new ArrayList<>();int cells=0;
        for(Prepared grid:prepared){
            if(overlapping.contains(grid.index)){diagnostics.add(new Diagnostic("GRID_TABLES_OVERLAP",grid.index,grid.frame.id()));continue;}
            try{
                require(cells+grid.cells.size()<=OcrLayout.MAX_CELLS,"GRID_CELL_BUDGET");
                Table table=join(grid,base);
                List<Table> proposed=new ArrayList<>(tables);proposed.add(table);
                // OcrLayout independently enforces row/column geometry, source references, text order and header coverage.
                new OcrLayout(OcrLayout.VERSION,base.status(),OcrLayout.COORDINATES,base.frames(),base.textBoxes(),proposed,base.readingOrder(),base.warnings()).validated(transcription);
                tables.add(table);cells+=table.cells().size();
            }catch(GridFailure invalid){diagnostics.add(new Diagnostic(invalid.code,grid.index,grid.frame.id()));}
            catch(IllegalArgumentException invalid){diagnostics.add(new Diagnostic("GRID_LAYOUT_VALIDATION_FAILED",grid.index,grid.frame.id()));}
        }
        List<String> warnings=new ArrayList<>(base.warnings());
        for(Diagnostic d:diagnostics)if(warnings.size()<32)warnings.add("表格结构未采用（"+d.code()+"）；已校验文字框保留，不据此认定材料缺失。");
        OcrLayout result=new OcrLayout(OcrLayout.VERSION,diagnostics.isEmpty()?base.status():"PARTIAL",OcrLayout.COORDINATES,base.frames(),base.textBoxes(),tables,base.readingOrder(),warnings).validated(transcription);
        return new Assembly(result,diagnostics);
    }

    private Prepared prepare(int index,Grid grid,Map<String,Frame> frames) {
        require(grid!=null,"GRID_INVALID");
        require(cn.bidcheck.compat.Java8Api.setOf(PIXEL_XYXY, NORMALIZED_1000_XYXY).contains(Objects.toString(grid.coordinateSpace(),"")),"GRID_COORDINATE_SPACE_UNSUPPORTED");
        Frame frame=frames.get(grid.frameId());require(frame!=null,"GRID_FRAME_UNKNOWN");
        require(grid.rows()>0&&grid.rows()<=500&&grid.columns()>0&&grid.columns()<=100&&(long)grid.rows()*grid.columns()<=10000,"GRID_DIMENSIONS_INVALID");
        require(!grid.cells().isEmpty()&&grid.cells().size()<=OcrLayout.MAX_CELLS,"GRID_CELL_BUDGET");
        Set<Integer> occupied=new HashSet<>();List<Positioned> cells=new ArrayList<>();
        for(Region region:grid.cells()){
            require(region!=null&&region.row()>=0&&region.column()>=0&&region.rowSpan()>0&&region.columnSpan()>0&&(long)region.row()+region.rowSpan()<=grid.rows()&&(long)region.column()+region.columnSpan()<=grid.columns(),"GRID_LOGICAL_RANGE_INVALID");
            Box box=pixels(region.box(),grid.coordinateSpace(),frame);
            for(int row=region.row();row<region.row()+region.rowSpan();row++)for(int column=region.column();column<region.column()+region.columnSpan();column++)require(occupied.add(row*grid.columns()+column),"GRID_LOGICAL_OVERLAP");
            for(Positioned previous:cells)require(!intersects(previous.box,box),"GRID_CELL_GEOMETRY_OVERLAP");
            cells.add(new Positioned(region,box));
        }
        require(occupied.size()==grid.rows()*grid.columns(),"GRID_LOGICAL_COVERAGE_INCOMPLETE");
        cells.sort(Comparator.comparingInt((Positioned c)->c.region.row()).thenComparingInt(c->c.region.column()));
        double left=cells.stream().mapToDouble(c->c.box.x()).min().orElseThrow(java.util.NoSuchElementException::new),top=cells.stream().mapToDouble(c->c.box.y()).min().orElseThrow(java.util.NoSuchElementException::new);
        double right=cells.stream().mapToDouble(c->c.box.x()+c.box.width()).max().orElseThrow(java.util.NoSuchElementException::new),bottom=cells.stream().mapToDouble(c->c.box.y()+c.box.height()).max().orElseThrow(java.util.NoSuchElementException::new);
        return new Prepared(index,grid,frame,cn.bidcheck.compat.Java8Api.listCopyOf(cells),new Box(left,top,right-left,bottom-top));
    }

    private Table join(Prepared grid,OcrLayout text) {
        String tableId="ga_t_"+hash(VERSION+"|"+grid.frame.id()+"|"+grid.frame.imageHash()+"|"+grid.grid.rows()+":"+grid.grid.columns()+cn.bidcheck.compat.Java8Api.toList(grid.cells.stream().map(c->cn.bidcheck.compat.Java8Api.listOf(c.region.row(), c.region.column(), c.region.rowSpan(), c.region.columnSpan(), c.region.header(), c.box.x(), c.box.y(), c.box.width(), c.box.height())))).substring(0,32);
        Map<String,TextBox> boxes=new HashMap<>();text.textBoxes().forEach(b->boxes.put(b.id(),b));
        List<List<TextBox>> assigned=new ArrayList<>();grid.cells.forEach(c->assigned.add(new ArrayList<>()));int assignedCount=0;
        for(String id:text.readingOrder()){
            TextBox box=boxes.get(id);if(!grid.frame.id().equals(box.frameId())||!intersects(grid.extent,box.bbox()))continue;
            int owner=-1;for(int i=0;i<grid.cells.size();i++)if(contains(grid.cells.get(i).box,box.bbox())){require(owner<0,"GRID_TEXT_OWNERSHIP_AMBIGUOUS");owner=i;}
            require(owner>=0,"GRID_TEXT_CROSSES_CELL_BOUNDARY");assigned.get(owner).add(box);assignedCount++;
        }
        require(assignedCount>0,"GRID_WITHOUT_SOURCE_TEXT");
        List<Cell> cells=new ArrayList<>();
        for(int i=0;i<grid.cells.size();i++){
            Positioned position=grid.cells.get(i);Region region=position.region;List<TextBox> words=assigned.get(i);
            String value=String.join("\n",cn.bidcheck.compat.Java8Api.toList(words.stream().map(TextBox::text)));require(value.length()<=10000&&words.size()<=100,"GRID_CELL_TEXT_BUDGET");
            String id="ga_c_"+hash(tableId+":"+region.row()+":"+region.column()+":"+region.rowSpan()+":"+region.columnSpan()).substring(0,32);
            cells.add(new Cell(id,region.row(),region.column(),region.rowSpan(),region.columnSpan(),value,position.box,cn.bidcheck.compat.Java8Api.toList(words.stream().map(TextBox::id)),cn.bidcheck.compat.Java8Api.listOf(),region.header()));
        }
        cells=identifyHeaderRows(cells);
        List<Cell> linked=new ArrayList<>();
        for(Cell cell:cells){List<String> headers=cn.bidcheck.compat.Java8Api.toList(cells.stream().filter(h->h.header()&&!cn.bidcheck.compat.Java8Api.isBlank(h.text())&&!h.id().equals(cell.id())&&covers(h,cell)).map(Cell::id));require(headers.size()<=100,"GRID_HEADER_BUDGET");linked.add(new Cell(cell.id(),cell.row(),cell.column(),cell.rowSpan(),cell.columnSpan(),cell.text(),cell.bbox(),cell.blockIds(),headers,cell.header()));}
        return new Table(tableId,grid.frame.id(),grid.grid.rows(),grid.grid.columns(),linked);
    }
    /** Only two distinct exact field labels in an early row establish a header row. A single keyword or fuzzy match never does. */
    private static List<Cell> identifyHeaderRows(List<Cell> cells){
        Set<Integer> rows=new HashSet<>();
        for(int row=0;row<2;row++){final int at=row;List<Cell> labels=cn.bidcheck.compat.Java8Api.toList(cells.stream().filter(c->c.row()==at&&HEADER_LABELS.contains(c.text().replaceAll("[\\s\\p{Z}]+", ""))));if(labels.stream().map(Cell::column).distinct().count()>=2&&labels.stream().map(c->c.text().replaceAll("[\\s\\p{Z}]+", "")).distinct().count()>=2)rows.add(row);}
        return cn.bidcheck.compat.Java8Api.toList(cells.stream().map(c->new Cell(c.id(),c.row(),c.column(),c.rowSpan(),c.columnSpan(),c.text(),c.bbox(),c.blockIds(),c.headerCellIds(),c.header()||rows.contains(c.row()))));
    }
    private static boolean covers(Cell h,Cell c){return h.row()+h.rowSpan()<=c.row()&&h.column()<=c.column()&&h.column()+h.columnSpan()>=c.column()+c.columnSpan()||h.column()+h.columnSpan()<=c.column()&&h.row()<=c.row()&&h.row()+h.rowSpan()>=c.row()+c.rowSpan();}
    private static Box pixels(List<Double> coords,String space,Frame frame){
        require(coords.size()==4&&coords.stream().allMatch(v->v!=null&&Double.isFinite(v)),"GRID_COORDINATES_INVALID");
        double x1=coords.get(0),y1=coords.get(1),x2=coords.get(2),y2=coords.get(3),maxX=space.equals(NORMALIZED_1000_XYXY)?1000:frame.width(),maxY=space.equals(NORMALIZED_1000_XYXY)?1000:frame.height();
        require(x1>=0&&y1>=0&&x2>x1&&y2>y1&&x2<=maxX&&y2<=maxY,"GRID_COORDINATES_OUT_OF_BOUNDS");
        if(space.equals(NORMALIZED_1000_XYXY)){x1=x1*frame.width()/1000;x2=x2*frame.width()/1000;y1=y1*frame.height()/1000;y2=y2*frame.height()/1000;}
        return new Box(x1,y1,x2-x1,y2-y1);
    }
    private static boolean contains(Box outer,Box inner){return inner.x()>=outer.x()&&inner.y()>=outer.y()&&inner.x()+inner.width()<=outer.x()+outer.width()&&inner.y()+inner.height()<=outer.y()+outer.height();}
    private static boolean intersects(Box a,Box b){return Math.min(a.x()+a.width(),b.x()+b.width())>Math.max(a.x(),b.x())&&Math.min(a.y()+a.height(),b.y()+b.height())>Math.max(a.y(),b.y());}
    private static String hash(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void require(boolean valid,String code){if(!valid)throw new GridFailure(code);}
    private static final class GridFailure extends RuntimeException {final String code;GridFailure(String code){super(code);this.code=code;}}
}
