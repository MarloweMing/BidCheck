package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.ocr.OcrLayout;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.bidcheck.verification.source.SourceSafety.map;

/** Maps validated OCR geometry to source spans without inventing PDF/Word positions or cross-image tables. */
final class StructuredLayoutAdapter {
    private StructuredLayoutAdapter(){}
    static List<Map<String,Object>> spans(Document document,Page page,List<String> limitations){
        if(page.layout()==null)return List.of();
        OcrLayout layout;
        try{layout=page.layout().validated(page.text());}
        catch(IllegalArgumentException invalid){limitations.add("位置 "+page.number()+" 的OCR结构未通过转录和位置校验，保留文字，不使用表格关系。");return List.of();}
        if("UNAVAILABLE".equals(layout.status()))return List.of();
        if(!"COMPLETE".equals(layout.status()))limitations.add("位置 "+page.number()+" 的OCR结构包含补充识别或未能定位的部分；已定位内容仍可追溯，缺少框不表示缺少材料。");
        Map<String,OcrLayout.Frame> frames=new HashMap<>();layout.frames().forEach(frame->frames.put(frame.id(),frame));
        Map<String,Integer> order=new HashMap<>();for(int i=0;i<layout.readingOrder().size();i++)order.put(layout.readingOrder().get(i),i);
        Map<String,Map<String,Object>> fragments=sourceFragments(layout,page.text());
        List<Map<String,Object>> result=new ArrayList<>();
        for(OcrLayout.TextBox box:layout.textBoxes()){
            Map<String,Object> span=base(document,page,frames.get(box.frameId()),box.id(),"OCR_TEXT_BOX",box.text(),box.bbox(),layout.status());
            span.put("readingOrder",order.get(box.id()));span.put("confidence",box.confidence());span.put("ocrSource",box.source());
            span.put("sourceFragments",List.of(fragments.get(box.id())));
            if(box.originalBbox()!=null){var b=box.originalBbox();span.put("originalOcrBox",List.of(b.x(),b.y(),b.x()+b.width(),b.y()+b.height()));span.put("positionRefined",true);}
            result.add(span);
        }
        for(OcrLayout.Table table:layout.tables()){
            OcrLayout.Frame frame=frames.get(table.frameId());Map<String,OcrLayout.Cell> cells=new HashMap<>();table.cells().forEach(c->cells.put(c.id(),c));
            String tableId=id(document,page,frame.id(),"table:"+table.id());
            for(OcrLayout.Cell cell:table.cells()){
                Map<String,Object> span=base(document,page,frame,cell.id(),"TABLE_CELL",cell.text(),cell.bbox(),layout.status());
                span.put("cellId",span.get("id"));span.put("tableId",tableId);span.put("row",cell.row()+1);span.put("column",cell.column()+1);
                span.put("rowSpan",cell.rowSpan());span.put("columnSpan",cell.columnSpan());span.put("header",cell.header());
                boolean leftHeader=!cell.headerCellIds().isEmpty()&&cell.headerCellIds().stream().map(cells::get).allMatch(h->h.row()<=cell.row()&&h.row()+h.rowSpan()>=cell.row()+cell.rowSpan()&&h.column()+h.columnSpan()==cell.column());
                span.put("tableType",leftHeader?"KEY_VALUE":"COLUMN_RECORD");
                span.put("headerCellIds",cell.headerCellIds().stream().map(h->id(document,page,frame.id(),h)).toList());
                String header=String.join(" / ",cell.headerCellIds().stream().map(cells::get).map(OcrLayout.Cell::text).toList());
                span.put("headerText",header);span.put("columnHeader",header);
                span.put("blockIds",cell.blockIds().stream().map(b->id(document,page,frame.id(),b)).toList());
                span.put("sourceFragments",cell.blockIds().stream().map(fragments::get).toList());
                // These are structured OCR candidates, not native OOXML cells or proof of document authenticity.
                span.put("structureTrust","OCR_CANDIDATE");span.put("mergeUnresolved",false);result.add(span);
            }
        }
        return result;
    }
    /** Bind every OCR occurrence to its exact immutable transcript offsets. Whitespace may
     * differ in the image model's spelling, but the quoted fragments are never rewritten. */
    private static Map<String,Map<String,Object>> sourceFragments(OcrLayout layout,String original){
        StringBuilder compact=new StringBuilder();List<Integer> offsets=new ArrayList<>();
        for(int i=0;i<original.length();i++)if(!Character.isWhitespace(original.charAt(i))&&!Character.isSpaceChar(original.charAt(i))){compact.append(original.charAt(i));offsets.add(i);}
        Map<String,OcrLayout.TextBox> boxes=new HashMap<>();layout.textBoxes().forEach(b->boxes.put(b.id(),b));
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();int cursor=0;
        for(String id:layout.readingOrder()){
            String value=boxes.get(id).text().replaceAll("[\\s\\p{Z}]+","");int at=compact.indexOf(value,cursor);
            if(at<0||value.isEmpty())throw new IllegalArgumentException("STRUCTURED_SOURCE_OFFSETS_INVALID");
            int start=offsets.get(at),end=offsets.get(at+value.length()-1)+1;cursor=at+value.length();
            result.put(id,map("start",start,"end",end,"text",original.substring(start,end)));
        }return result;
    }
    private static Map<String,Object> base(Document doc,Page page,OcrLayout.Frame frame,String localId,String kind,String text,OcrLayout.Box box,String status){
        String id=id(doc,page,frame.id(),localId);boolean physical="PAGE".equals(page.locationType());
        return map("id",id,"spanId",id,"kind",kind,"documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",doc.version(),
            "physicalPage",physical?page.number():null,"documentBlock",page.number(),"locationType",page.locationType(),"location",page.number(),"printedPage",null,
            "text",text,"bbox",List.of(box.x(),box.y(),box.x()+box.width(),box.y()+box.height()),"coordinateSpace","OCR_IMAGE_PIXELS_TOP_LEFT",
            "source","OCR_STRUCTURED","frameId",id(doc,page,frame.id(),"frame"),"imageHash",frame.imageHash(),"imageWidth",frame.width(),"imageHeight",frame.height(),
            "imageSourceKind",frame.sourceKind(),"imageSourceIndex",frame.sourceIndex(),"parentWidth",frame.parentWidth(),"parentHeight",frame.parentHeight(),"crop",frame.crop(),"layoutStatus",status);
    }
    private static String id(Document doc,Page page,String frame,String local){return SourceSafety.digest(("structured-layout-1:"+doc.id()+":"+doc.sha256()+":"+doc.version()+":"+page.locationType()+":"+page.number()+":"+frame+":"+local).getBytes(StandardCharsets.UTF_8));}
}
