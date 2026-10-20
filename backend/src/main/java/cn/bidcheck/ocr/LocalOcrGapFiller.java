package cn.bidcheck.ocr;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static cn.bidcheck.ocr.OcrLayout.*;
import static cn.bidcheck.ocr.GridLayoutAssembler.*;

/** Supplements only wholly empty, unambiguous grid cells using source-bound local OCR.
 * This is additional transcription, not independent verification of native OCR or business facts. */
public final class LocalOcrGapFiller {
    public static final String VERSION = "local-ocr-empty-cell-fill-2-consensus";
    private record GridCells(List<Box> cells, Box extent) { }
    private record Arbitration(List<TextBox> boxes, Set<String> localIds, int count) { }

    /** A rejected local source/grid or no eligible addition leaves the native result unchanged. */
    public OcrResult fill(OcrResult nativeResult, OcrLayout localLayout, List<Grid> grids) {
        Objects.requireNonNull(nativeResult, "nativeResult");
        try {
            if (nativeResult.layout() == null || localLayout == null || grids == null || grids.isEmpty() || grids.size() > 40
                    || !Set.of("DASHSCOPE", "DASHSCOPE+RAPIDOCR").contains(Objects.toString(nativeResult.provider(), ""))) return nativeResult;
            OcrLayout nativeLayout = nativeResult.layout().validated(nativeResult.text());
            String localText = orderedText(localLayout); localLayout = localLayout.validated(localText);
            if (nativeLayout.frames().size() != 1 || localLayout.frames().size() != 1 || nativeLayout.textBoxes().isEmpty()
                    || nativeLayout.warnings().size() >= 32) return nativeResult;
            Frame frame = nativeLayout.frames().get(0), localFrame = localLayout.frames().get(0);
            if (frame.width() != localFrame.width() || frame.height() != localFrame.height() || !frame.imageHash().equals(localFrame.imageHash())) return nativeResult;
            List<GridCells> validGrids = prepare(grids, frame, nativeLayout, nativeResult.text());
            Set<Integer> competing = new HashSet<>();
            for (int i=0;i<validGrids.size();i++) for (int j=i+1;j<validGrids.size();j++)
                if (intersects(validGrids.get(i).extent,validGrids.get(j).extent)) { competing.add(i); competing.add(j); }
            Map<String,TextBox> localById = new HashMap<>(); localLayout.textBoxes().forEach(b->localById.put(b.id(),b));
            // Competing/duplicate local detections are both rejected; taking the first would choose a reading.
            Set<String> overlapping = new HashSet<>(); List<TextBox> localBoxes = localLayout.textBoxes();
            for (int i=0;i<localBoxes.size();i++) for (int j=i+1;j<localBoxes.size();j++)
                if (intersects(localBoxes.get(i).bbox(),localBoxes.get(j).bbox())) { overlapping.add(localBoxes.get(i).id()); overlapping.add(localBoxes.get(j).id()); }
            Arbitration arbitration = "CELL_CROP_MAPPED".equals(localFrame.sourceKind())
                    ? arbitrate(nativeLayout.textBoxes(),localBoxes,validGrids,competing,overlapping)
                    : new Arbitration(nativeLayout.textBoxes(),Set.of(),0);
            List<Box> emptyCells = new ArrayList<>();
            for (int i=0;i<validGrids.size();i++) if (!competing.contains(i)) for (Box cell:validGrids.get(i).cells)
                // Filling cannot exploit space freed by arbitration: original occupied cells remain protected.
                if (nativeLayout.textBoxes().stream().noneMatch(b->intersects(cell,b.bbox()))) emptyCells.add(cell);
            List<TextBox> additions = new ArrayList<>(); Set<String> used = new HashSet<>();
            for (String id:localLayout.readingOrder()) {
                TextBox local = localById.get(id);
                if (!used.add(id) || arbitration.localIds.contains(id) || overlapping.contains(id) || local.confidence() == null || local.confidence() < .8
                        || local.source() != null && !"RAPIDOCR".equals(local.source())) continue;
                if (nativeLayout.textBoxes().stream().anyMatch(b->intersects(b.bbox(),local.bbox()))) continue;
                if (emptyCells.stream().filter(cell->contains(cell,local.bbox())).count() != 1) continue;
                String newId="local_"+digest(VERSION+"|"+frame.imageHash()+"|"+local.id()+"|"+local.text()+"|"+local.bbox()).substring(0,40);
                additions.add(new TextBox(newId,frame.id(),local.text(),local.bbox(),local.confidence(),"RAPIDOCR"));
            }
            if (additions.isEmpty() && arbitration.count==0 || nativeLayout.textBoxes().size()+additions.size()>MAX_BOXES) return nativeResult;
            StringBuilder text = new StringBuilder(nativeResult.text()); for (TextBox box:additions) text.append('\n').append(box.text());
            if (text.length()>50000) return nativeResult;
            List<TextBox> boxes = new ArrayList<>(arbitration.boxes); boxes.addAll(additions);
            List<String> order = new ArrayList<>(nativeLayout.readingOrder()); additions.forEach(b->order.add(b.id()));
            List<String> notes = new ArrayList<>(nativeLayout.warnings()); notes.add("本地 RapidOCR 仅补充原生空白格，或在独立单格裁图全文一致且位置匹配时调整跨格框；原生文字未改写，原框保留。属于补充转录与几何核对，不是独立核验，仍需对照原图。");
            OcrLayout result = new OcrLayout(OcrLayout.VERSION,nativeLayout.status(),COORDINATES,nativeLayout.frames(),boxes,nativeLayout.tables(),order,notes).validated(text.toString());
            return new OcrResult(text.toString(),"DASHSCOPE+RAPIDOCR",nativeResult.model(),false,result);
        } catch (IllegalArgumentException | NullPointerException invalidSource) { return nativeResult; }
    }
    private static Arbitration arbitrate(List<TextBox> nativeBoxes,List<TextBox> localBoxes,List<GridCells> grids,Set<Integer> competing,Set<String> overlapping) {
        Map<String,TextBox> proposed=new HashMap<>();Map<String,Integer> owners=new HashMap<>();
        for(TextBox original:nativeBoxes) {
            if("CONSENSUS_OCR".equals(original.source())||"RAPIDOCR".equals(original.source()))continue;
            List<GridCells> crossed=new ArrayList<>();
            for(int i=0;i<grids.size();i++)if(!competing.contains(i)){
                GridCells grid=grids.get(i);
                if(contains(grid.extent,original.bbox())&&grid.cells.stream().noneMatch(c->contains(c,original.bbox()))
                        &&grid.cells.stream().filter(c->intersects(c,original.bbox())).count()>1)crossed.add(grid);
            }
            if(crossed.size()!=1)continue;GridCells grid=crossed.get(0);List<TextBox> matches=new ArrayList<>();
            for(TextBox local:localBoxes) {
                if(overlapping.contains(local.id())||local.confidence()==null||local.confidence()<.8
                        ||local.source()!=null&&!"RAPIDOCR".equals(local.source())||!compact(original.text()).equals(compact(local.text())))continue;
                if(grid.cells.stream().filter(c->contains(c,local.bbox())).count()!=1||!significant(original.bbox(),local.bbox()))continue;
                if(nativeBoxes.stream().anyMatch(other->!other.id().equals(original.id())&&intersects(other.bbox(),local.bbox())))continue;
                matches.add(local);
            }
            // Uniqueness is spatial within this actual grid, not a ban on dates repeated elsewhere in a document.
            if(matches.size()==1){TextBox match=matches.get(0);proposed.put(original.id(),match);owners.merge(match.id(),1,Integer::sum);}
        }
        List<TextBox> result=new ArrayList<>();Set<String> used=new HashSet<>();int count=0;
        for(TextBox original:nativeBoxes){TextBox match=proposed.get(original.id());
            if(match!=null&&owners.get(match.id())==1){result.add(new TextBox(original.id(),original.frameId(),original.text(),match.bbox(),original.confidence(),"CONSENSUS_OCR",original.bbox()));used.add(match.id());count++;}
            else result.add(original);
        }
        return new Arbitration(result,used,count);
    }
    private static String compact(String text){return text.replaceAll("[\\s\\p{Z}]+","");}
    private static boolean significant(Box a,Box b){double overlap=Math.max(0,Math.min(a.x()+a.width(),b.x()+b.width())-Math.max(a.x(),b.x()))*Math.max(0,Math.min(a.y()+a.height(),b.y()+b.height())-Math.max(a.y(),b.y()));double aa=a.width()*a.height(),bb=b.width()*b.height();return overlap/Math.min(aa,bb)>=.8&&overlap/Math.max(aa,bb)>=.5;}
    private static String orderedText(OcrLayout layout) {
        require(layout.textBoxes().size()<=MAX_BOXES&&layout.readingOrder().size()<=MAX_BOXES);
        Map<String,TextBox> boxes=new HashMap<>();for(TextBox b:layout.textBoxes())if(boxes.putIfAbsent(b.id(),b)!=null)throw new IllegalArgumentException();
        StringBuilder text=new StringBuilder();for(String id:layout.readingOrder()){String value=Objects.requireNonNull(boxes.get(id)).text();require(value!=null&&value.length()<=10000&&text.length()+value.length()+1<=50000);if(!text.isEmpty())text.append('\n');text.append(value);}return text.toString();
    }
    private static List<GridCells> prepare(List<Grid> grids,Frame frame,OcrLayout nativeLayout,String text) {
        List<GridCells> output=new ArrayList<>();int totalCells=0,index=0;
        for(Grid grid:grids) {
            require(grid!=null && frame.id().equals(grid.frameId()) && Set.of(PIXEL_XYXY,NORMALIZED_1000_XYXY).contains(Objects.toString(grid.coordinateSpace(),"")));
            require(grid.rows()>0&&grid.rows()<=500&&grid.columns()>0&&grid.columns()<=100&&(long)grid.rows()*grid.columns()<=10000);
            require(!grid.cells().isEmpty()&&(totalCells+=grid.cells().size())<=MAX_CELLS);
            List<Box> boxes=new ArrayList<>();List<Cell> cells=new ArrayList<>();Set<Integer> slots=new HashSet<>();int cellIndex=0;
            for(Region region:grid.cells()) {
                require(region!=null&&region.row()>=0&&region.column()>=0&&region.rowSpan()>0&&region.columnSpan()>0
                        &&(long)region.row()+region.rowSpan()<=grid.rows()&&(long)region.column()+region.columnSpan()<=grid.columns());
                Box box=pixels(region.box(),grid.coordinateSpace(),frame);
                require(boxes.stream().noneMatch(previous->intersects(previous,box)));boxes.add(box);
                for(int row=region.row();row<region.row()+region.rowSpan();row++)for(int col=region.column();col<region.column()+region.columnSpan();col++)require(slots.add(row*grid.columns()+col));
                cells.add(new Cell("check_c_"+(cellIndex++),region.row(),region.column(),region.rowSpan(),region.columnSpan(),"",box,List.of(),List.of(),false));
            }
            require(slots.size()==grid.rows()*grid.columns());
            // Independently reuse strict logical/physical row-column validation; no synthetic text or cell facts are published.
            new OcrLayout(OcrLayout.VERSION,nativeLayout.status(),COORDINATES,nativeLayout.frames(),nativeLayout.textBoxes(),
                    List.of(new Table("check_t_"+(index++),frame.id(),grid.rows(),grid.columns(),cells)),nativeLayout.readingOrder(),nativeLayout.warnings()).validated(text);
            double x=boxes.stream().mapToDouble(Box::x).min().orElseThrow(),y=boxes.stream().mapToDouble(Box::y).min().orElseThrow();
            double right=boxes.stream().mapToDouble(b->b.x()+b.width()).max().orElseThrow(),bottom=boxes.stream().mapToDouble(b->b.y()+b.height()).max().orElseThrow();
            output.add(new GridCells(boxes,new Box(x,y,right-x,bottom-y)));
        }
        return output;
    }
    private static Box pixels(List<Double> values,String space,Frame frame) {
        require(values.size()==4&&values.stream().allMatch(v->v!=null&&Double.isFinite(v)));
        double x=values.get(0),y=values.get(1),right=values.get(2),bottom=values.get(3),width=space.equals(NORMALIZED_1000_XYXY)?1000:frame.width(),height=space.equals(NORMALIZED_1000_XYXY)?1000:frame.height();
        require(x>=0&&y>=0&&right>x&&bottom>y&&right<=width&&bottom<=height);
        if(space.equals(NORMALIZED_1000_XYXY)){x=x*frame.width()/1000;right=right*frame.width()/1000;y=y*frame.height()/1000;bottom=bottom*frame.height()/1000;}
        return new Box(x,y,right-x,bottom-y);
    }
    private static boolean contains(Box a,Box b){return b.x()>=a.x()&&b.y()>=a.y()&&b.x()+b.width()<=a.x()+a.width()&&b.y()+b.height()<=a.y()+a.height();}
    private static boolean intersects(Box a,Box b){return Math.min(a.x()+a.width(),b.x()+b.width())>Math.max(a.x(),b.x())&&Math.min(a.y()+a.height(),b.y()+b.height())>Math.max(a.y(),b.y());}
    private static void require(boolean condition){if(!condition)throw new IllegalArgumentException("Local OCR source or grid is not safely bound");}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
