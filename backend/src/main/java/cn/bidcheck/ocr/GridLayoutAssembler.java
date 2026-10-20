package cn.bidcheck.ocr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static cn.bidcheck.ocr.OcrLayout.*;

/** Joins independently validated text boxes to explicitly declared grids. No model-provided text or references are accepted. */
public final class GridLayoutAssembler {
    public static final String VERSION="grid-layout-assembler-2-headers";
    public static final String PIXEL_XYXY="PIXEL_XYXY", NORMALIZED_1000_XYXY="NORMALIZED_1000_XYXY";
    private static final Set<String> HEADER_LABELS=Set.of("序号","姓名","人员姓名","身份证号","身份证号码","公民身份号码","参保人姓名","缴费单位","参保单位","单位名称","缴费月份","缴费年月","月份","缴费所属期","所属年月","证书状态","证书形式","证号","企业名称","法人代表","行政相对人代码","注册地址","经济类型","许可范围","许可机关","核发日期","前一次有效期","延期有效期","证书名称","证书类别","证书编号","注册编号","证书等级","级别","专业","岗位","职务","拟任岗位","有效期","注册有效期","使用有效期");
    public record Region(int row,int column,int rowSpan,int columnSpan,List<Double> box,boolean header) {
        public Region { box=box==null?List.of():List.copyOf(box); }
    }
    public record Grid(String frameId,String coordinateSpace,int rows,int columns,List<Region> cells) {
        public Grid { cells=cells==null?List.of():List.copyOf(cells); }
    }
    public record Diagnostic(String code,int gridIndex,String frameId) {}
    public record Assembly(OcrLayout layout,List<Diagnostic> diagnostics) {
        public Assembly { diagnostics=List.copyOf(diagnostics); }
    }
    private record Positioned(Region region,Box box) {}
    private record Prepared(int index,Grid grid,Frame frame,List<Positioned> cells,Box extent) {}

    /** Invalid grids affect their tables only. An invalid text source is a caller error and is never repaired or guessed. */
    public Assembly assemble(OcrLayout textLayout,String transcription,List<Grid> grids) {
        Objects.requireNonNull(textLayout,"textLayout");
        OcrLayout base=new OcrLayout(textLayout.schemaVersion(),textLayout.status(),textLayout.coordinateSystem(),textLayout.frames(),textLayout.textBoxes(),List.of(),textLayout.readingOrder(),textLayout.warnings()).validated(transcription);
        List<Diagnostic> diagnostics=new ArrayList<>();
        if(!textLayout.tables().isEmpty())diagnostics.add(new Diagnostic("INPUT_TABLES_IGNORED",-1,""));
        if(grids==null){grids=List.of();diagnostics.add(new Diagnostic("GRID_RESPONSE_MISSING",-1,""));}
        if("UNAVAILABLE".equals(base.status()))return new Assembly(base,List.of(new Diagnostic("TEXT_BOXES_UNAVAILABLE",-1,"")));
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
        require(Set.of(PIXEL_XYXY,NORMALIZED_1000_XYXY).contains(Objects.toString(grid.coordinateSpace(),"")),"GRID_COORDINATE_SPACE_UNSUPPORTED");
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
        double left=cells.stream().mapToDouble(c->c.box.x()).min().orElseThrow(),top=cells.stream().mapToDouble(c->c.box.y()).min().orElseThrow();
        double right=cells.stream().mapToDouble(c->c.box.x()+c.box.width()).max().orElseThrow(),bottom=cells.stream().mapToDouble(c->c.box.y()+c.box.height()).max().orElseThrow();
        return new Prepared(index,grid,frame,List.copyOf(cells),new Box(left,top,right-left,bottom-top));
    }

    private Table join(Prepared grid,OcrLayout text) {
        String tableId="ga_t_"+hash(VERSION+"|"+grid.frame.id()+"|"+grid.frame.imageHash()+"|"+grid.grid.rows()+":"+grid.grid.columns()+grid.cells.stream().map(c->List.of(c.region.row(),c.region.column(),c.region.rowSpan(),c.region.columnSpan(),c.region.header(),c.box.x(),c.box.y(),c.box.width(),c.box.height())).toList()).substring(0,32);
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
            String value=String.join("\n",words.stream().map(TextBox::text).toList());require(value.length()<=10000&&words.size()<=100,"GRID_CELL_TEXT_BUDGET");
            String id="ga_c_"+hash(tableId+":"+region.row()+":"+region.column()+":"+region.rowSpan()+":"+region.columnSpan()).substring(0,32);
            cells.add(new Cell(id,region.row(),region.column(),region.rowSpan(),region.columnSpan(),value,position.box,words.stream().map(TextBox::id).toList(),List.of(),region.header()));
        }
        cells=identifyHeaderRows(cells);
        List<Cell> linked=new ArrayList<>();
        for(Cell cell:cells){List<String> headers=cells.stream().filter(h->h.header()&&!h.text().isBlank()&&!h.id().equals(cell.id())&&covers(h,cell)).map(Cell::id).toList();require(headers.size()<=100,"GRID_HEADER_BUDGET");linked.add(new Cell(cell.id(),cell.row(),cell.column(),cell.rowSpan(),cell.columnSpan(),cell.text(),cell.bbox(),cell.blockIds(),headers,cell.header()));}
        return new Table(tableId,grid.frame.id(),grid.grid.rows(),grid.grid.columns(),linked);
    }
    /** Only two distinct exact field labels in an early row establish a header row. A single keyword or fuzzy match never does. */
    private static List<Cell> identifyHeaderRows(List<Cell> cells){
        Set<Integer> rows=new HashSet<>();
        for(int row=0;row<2;row++){final int at=row;List<Cell> labels=cells.stream().filter(c->c.row()==at&&HEADER_LABELS.contains(c.text().replaceAll("[\\s\\p{Z}]+", ""))).toList();if(labels.stream().map(Cell::column).distinct().count()>=2&&labels.stream().map(c->c.text().replaceAll("[\\s\\p{Z}]+", "")).distinct().count()>=2)rows.add(row);}
        return cells.stream().map(c->new Cell(c.id(),c.row(),c.column(),c.rowSpan(),c.columnSpan(),c.text(),c.bbox(),c.blockIds(),c.headerCellIds(),c.header()||rows.contains(c.row()))).toList();
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
