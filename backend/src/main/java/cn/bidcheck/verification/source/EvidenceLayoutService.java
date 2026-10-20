package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.document.word.WordRenderingService;
import cn.bidcheck.verification.facts.FactLedgerService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.text.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.w3c.dom.*;
import static cn.bidcheck.verification.source.SourceSafety.map;

/** Immutable-source sidecar. Word locations are OOXML positions, never imaginary rendered pages. */
@Service
public class EvidenceLayoutService {
    private static final String VERSION="source-layout-2-structured";
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int MAX_SPANS=50_000,MAX_CHARS=2_000_000;
    private static final Semaphore CAPACITY=new Semaphore(1,true);
    private final WordRenderingService wordRenderer;
    public EvidenceLayoutService(){this(null);}
    @Autowired public EvidenceLayoutService(WordRenderingService wordRenderer){this.wordRenderer=wordRenderer;}
    public String identity(){return VERSION+":"+(wordRenderer==null?"WORD_RENDERER_ABSENT":wordRenderer.identity());}
    /** Already transcribed page structures only. Does not render, OCR, or rescan the original PDF. */
    public Map<String,Object> transcribedLayout(Document doc,Set<Integer> locations){
        List<Map<String,Object>> spans=new ArrayList<>();List<String> limitations=new ArrayList<>();
        for(Page page:doc.pages())if(locations.contains(page.number()))spans.addAll(StructuredLayoutAdapter.spans(doc,page,limitations));
        return map("status","COMPLETE","version",VERSION,"documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",doc.version(),"spans",spans,"limitations",limitations);
    }
    public String cacheIdentity(Document doc){return SourceSafety.digest((identity()+":"+doc.id()+":"+doc.sha256()+":"+doc.version()+":"+doc.pages().stream().map(FactLedgerService::parserFingerprint).toList()).getBytes(StandardCharsets.UTF_8));}
    public Map<String,Object> wordConfiguration(){return wordRenderer==null?map("enabled",false,"licensed",false,"status","DISABLED"):wordRenderer.configuration();}
    public Map<String,Object> layout(Document doc,Path original){
        boolean permit=false;
        try{
            if(!CAPACITY.tryAcquire(15,TimeUnit.SECONDS))return error("LAYOUT_BUSY");permit=true;
            String hash=SourceSafety.verify(doc,original);List<Map<String,Object>> spans=new ArrayList<>(),pages=new ArrayList<>();List<String> limits=new ArrayList<>();
            switch(SourceSafety.extension(doc)){
                case "pdf" -> pdf(doc,original,hash,spans,pages,limits);
                case "docx" -> word(doc,original,hash,spans,limits);
                default -> {for(Page p:doc.pages())spans.add(map("id",id(hash,"block:"+p.number()),"kind","PARAGRAPH","documentBlock",p.number(),"physicalPage",null,"printedPage",null,"text",p.text(),"bbox",null,"coordinateSpace",null,"source","PARSED_TEXT_NO_LAYOUT"));limits.add("此格式未提供真实布局坐标。");}
            }
            List<Integer> omittedStructureLocations=new ArrayList<>();
            long characters=spans.stream().mapToLong(s->Objects.toString(s.get("text"),"").length()).sum();
            for(Page source:doc.pages()){
                List<Map<String,Object>> structured=StructuredLayoutAdapter.spans(doc,source,limits);
                long added=structured.stream().mapToLong(s->Objects.toString(s.get("text"),"").length()).sum();
                // Keep the existing transcription/native layout and complete per-page relations when the sidecar budget is full.
                if(!structured.isEmpty()&&(spans.size()+structured.size()>MAX_SPANS||characters+added>MAX_CHARS)){
                    omittedStructureLocations.add(source.number());continue;
                }
                spans.addAll(structured);
                characters+=added;
            }
            Map<String,Object> result=map("status","COMPLETE","version",VERSION,"documentId",doc.id(),"documentHash",hash,"pages",pages,"spans",spans,"limitations",limits);
            result.put("structureBudget",map("status",omittedStructureLocations.isEmpty()?"WITHIN_LIMIT":"PARTIAL","omittedLocations",omittedStructureLocations));
            if(!omittedStructureLocations.isEmpty())limits.add("结构索引达到容量限制，部分位置未加入本次索引；逐页保存的原始结构仍可查看，不能据此判定缺少材料。");
            for(Map<String,Object> span:spans){span.put("documentId",doc.id());span.put("documentHash",hash);span.put("documentVersion",doc.version());}
            if("docx".equals(SourceSafety.extension(doc))&&wordRenderer!=null){
                Map<String,Object> rendering=wordRenderer.layout(doc,original);result.put("wordRendering",rendering);
                // Rendering pages are a separate namespace; original block and OOXML evidence remains unchanged.
                if(Boolean.TRUE.equals(rendering.get("usableForEvidence"))){
                    Map<Integer,List<Map<String,Object>>> mapping=new HashMap<>();
                    for(Map<String,Object> rendered:asMaps(rendering.get("spans")))if(rendered.get("documentBlock") instanceof Number block)mapping.computeIfAbsent(block.intValue(),k->new ArrayList<>()).add(rendered);
                    for(Map<String,Object> span:spans)if(span.get("documentBlock") instanceof Number block&&mapping.containsKey(block.intValue()))span.put("renderedMapping",mapping.get(block.intValue()));
                }else limits.add("Word整页渲染尚不可用，保留原生段落与表格证据；状态："+rendering.get("status"));
            }
            if("pdf".equals(SourceSafety.extension(doc)))result.put("digitalSignatures",new DigitalSignatureInspector().inspect(doc,original));
            return result;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException("布局提取已中断");}
        catch(CancellationException e){throw e;}
        catch(IOException|RuntimeException e){return error(e instanceof IOException?e.getMessage():"LAYOUT_INVALID_SOURCE");}
        finally{if(permit)CAPACITY.release();}
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> asMaps(Object raw){return raw instanceof List<?> rows?rows.stream().filter(Map.class::isInstance).map(x->(Map<String,Object>)x).toList():List.of();}
    private static Map<String,Object> error(String code){return map("status","ERROR","version",VERSION,"errorCode",code!=null&&code.matches("[A-Z0-9_]{1,80}")?code:"LAYOUT_EXTRACTION_FAILED","spans",List.of(),"pages",List.of(),"limitations",List.of("布局提取未完成，不能据此认定原件缺少内容。"));}
    private static String id(String hash,String location){return SourceSafety.digest((VERSION+":"+hash+":"+location).getBytes(StandardCharsets.UTF_8));}
    private static void pdf(Document doc,Path original,String hash,List<Map<String,Object>> spans,List<Map<String,Object>> pages,List<String> limits)throws IOException{
        try(PDDocument pdf=Loader.loadPDF(original.toFile(),IOUtils.createTempFileOnlyStreamCache())){
            SourceSafety.pdf(pdf);PositionStripper stripper=new PositionStripper(hash,spans,pages);stripper.setSortByPosition(true);stripper.writeText(pdf,Writer.nullWriter());
            for(Page page:doc.pages())if(page.parser()!=null&&page.parser().contains("OCR")){
                String text=page.text();int offset=text.indexOf("【OCR识别文字】");if(offset>=0)text=text.substring(offset+"【OCR识别文字】".length()).strip();
                else if(!page.parser().equals("PDFBOX_OCR"))continue;
                if(!text.isBlank()){if(spans.size()>=MAX_SPANS)throw new IOException("LAYOUT_SPAN_LIMIT");spans.add(map("id",id(hash,"ocr:"+page.number()),"kind","OCR_TEXT","physicalPage",page.number(),"documentBlock",page.number(),"printedPage",null,"text",text,"bbox",null,"coordinateSpace",null,"source","OCR_WITHOUT_BOXES"));}
            }
        }
        limits.add("PDF框由原生TextPosition字形度量提取，表示文字位置，不代表手写签字或印章边界。OCR未提供定位框时bbox为null。");
        limits.add("printedPage仅为页脚文字候选，未验证整个分册的印刷页映射；PDF原生无语义表格时不虚构单元格。");
    }
    private static final class PositionStripper extends PDFTextStripper{
        final String hash;final List<Map<String,Object>> spans,pages;int number,index,characters,glyphs,operators;double width,height;int rotation;Map<String,Object> page;final long deadline=System.nanoTime()+45_000_000_000L;
        PositionStripper(String hash,List<Map<String,Object>> spans,List<Map<String,Object>> pages)throws IOException{this.hash=hash;this.spans=spans;this.pages=pages;}
        @Override protected void startPage(PDPage p)throws IOException{
            SourceSafety.interrupted();super.startPage(p);number++;index=0;glyphs=0;operators=0;rotation=Math.floorMod(p.getRotation(),360);boolean swap=rotation==90||rotation==270;
            width=swap?p.getCropBox().getHeight():p.getCropBox().getWidth();height=swap?p.getCropBox().getWidth():p.getCropBox().getHeight();
            double scale=Math.min(1.5,Math.min(2400/Math.max(width,height),Math.sqrt(4_000_000/(width*height))));
            int pixelWidth=Math.max(1,(int)Math.floor(width*(float)scale)),pixelHeight=Math.max(1,(int)Math.floor(height*(float)scale));
            page=map("physicalPage",number,"width",width,"height",height,"rotation",rotation,"coordinateSpace","PDF_DISPLAY_POINTS_TOP_LEFT","printedPage",null,
                "renderMapping",map("renderer","FileParser.renderPdfPage","scale",(double)(float)scale,"pixelWidth",pixelWidth,"pixelHeight",pixelHeight,"cropBox",List.of(p.getCropBox().getLowerLeftX(),p.getCropBox().getLowerLeftY(),p.getCropBox().getWidth(),p.getCropBox().getHeight())));
            pages.add(page);
        }
        @Override protected void processTextPosition(TextPosition text){if(++glyphs>50_000)throw new LayoutLimit();SourceSafety.interrupted();super.processTextPosition(text);}
        @Override protected void processOperator(org.apache.pdfbox.contentstream.operator.Operator operator,List<org.apache.pdfbox.cos.COSBase> arguments)throws IOException{
            SourceSafety.interrupted();if(++operators>250_000||System.nanoTime()>deadline)throw new IOException("PDF_LAYOUT_PROCESSING_LIMIT");super.processOperator(operator,arguments);
        }
        @Override protected void writeString(String text,List<TextPosition> positions)throws IOException{
            if(text.isBlank()||positions.isEmpty())return;characters+=text.length();if(characters>MAX_CHARS||spans.size()>=MAX_SPANS)throw new IOException("LAYOUT_TEXT_LIMIT");
            double x0=Double.POSITIVE_INFINITY,y0=x0,x1=-x0,y1=-x0;
            for(TextPosition t:positions){double a=Math.toRadians(rotation-t.getDir()),c=Math.cos(a),s=Math.sin(a),w=t.getWidthDirAdj(),h=t.getHeightDir();
                for(double[] q:List.of(new double[]{0,0},new double[]{w,0},new double[]{0,h},new double[]{w,h})){
                    double x=t.getX()+q[0]*c+q[1]*s,y=t.getY()+q[0]*s-q[1]*c;x0=Math.min(x0,x);x1=Math.max(x1,x);y0=Math.min(y0,y);y1=Math.max(y1,y);}
            }
            if(!Double.isFinite(x0)||!Double.isFinite(y0)||!Double.isFinite(x1)||!Double.isFinite(y1))throw new IOException("PDF_COORDINATES_INVALID");
            x0=Math.max(0,x0);y0=Math.max(0,y0);x1=Math.min(width,x1);y1=Math.min(height,y1);
            String spanId=id(hash,"pdf:"+number+":"+(++index));List<Double> box=x1>x0&&y1>y0?List.of(x0,y0,x1,y1):null;
            spans.add(map("id",spanId,"kind","TEXT_SPAN","physicalPage",number,"documentBlock",number,"printedPage",null,"text",text,"bbox",box,"coordinateSpace","PDF_DISPLAY_POINTS_TOP_LEFT","source","PDFBOX_TEXT_POSITION"));
            if(box!=null&&y0>height*.85&&text.strip().matches("(?:第\\s*)?[0-9]{1,5}(?:\\s*页)?"))page.put("printedPage",map("label",text.strip(),"sourceSpanId",spanId,"status","CANDIDATE_NOT_VERIFIED"));
        }
    }
    private static final class LayoutLimit extends RuntimeException{}
    private static void word(Document doc,Path original,String hash,List<Map<String,Object>> spans,List<String> limits)throws IOException{
        WordWalk walk=new WordWalk(doc,hash,spans);for(var part:SourceSafety.wordXml(original).entrySet())walk.walk(SourceSafety.xml(part.getValue()).getDocumentElement(),part.getKey(),"",0,null);
        limits.add("Word原生位置是OOXML段落/表格位置，physicalPage、bbox与printedPage为null；许可渲染与页面映射单独提供，不能把内容块号当物理页码。");
        limits.add("Word旧解析块只在原生内容唯一精确对应时映射；无法确认时documentBlock为null。修订文本保留来源标记，不自动当作最终正文。");
    }
    private static final class WordWalk{
        final String hash;final List<Map<String,Object>> spans;final Map<String,List<Integer>> blocks=new HashMap<>();int chars;
        WordWalk(Document doc,String hash,List<Map<String,Object>> spans){this.hash=hash;this.spans=spans;for(Page p:doc.pages()){
            String t=p.text().split("【OCR识别文字】",2)[0].replace("【原生文字】","").strip();blocks.computeIfAbsent(norm(t),k->new ArrayList<>()).add(p.number());}}
        void walk(Element element,String part,String path,int depth,Map<String,Object> cell)throws IOException{
            SourceSafety.interrupted();if(depth>40)throw new IOException("WORD_DEPTH_LIMIT");String name=element.getLocalName();
            if(W.equals(element.getNamespaceURI())&&"tbl".equals(name)){table(element,part,path,depth);return;}
            if(W.equals(element.getNamespaceURI())&&"p".equals(name)){
                String text=text(element);Map<String,Object> span=base(part,path,"PARAGRAPH",text);span.put("tableCell",cell);span.put("revision",ancestorRevision(element));add(span);
                NodeList blips=element.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main","blip");
                for(int i=0;i<blips.getLength();i++){Element image=(Element)blips.item(i);String embed=image.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","embed");
                    Map<String,Object> im=base(part,path+"/image["+(i+1)+"]","EMBEDDED_IMAGE","");im.put("relationshipId",embed);im.put("paragraphImageIndex",i+1);im.put("imageIndex",cell==null?i+1:null);im.put("documentBlock",cell==null?span.get("documentBlock"):null);im.put("tableCell",cell);add(im);}
                return;
            }
            Map<String,Integer> count=new HashMap<>();for(Element child:children(element)){String q=child.getTagName();int n=count.merge(q,1,Integer::sum);walk(child,part,path+"/"+q+"["+n+"]",depth+1,cell);}
        }
        void table(Element table,String part,String path,int depth)throws IOException{
            int row=0;Map<Integer,Map<String,Object>> vertical=new HashMap<>();
            for(Element tr:direct(table,"tr")){row++;int column=1;Element rowPr=first(tr,"trPr"),before=rowPr==null?null:first(rowPr,"gridBefore");
                if(before!=null)try{int skipped=Integer.parseInt(before.getAttributeNS(W,"val"));if(skipped<0||skipped>1000)throw new IOException("WORD_GRID_LIMIT");column+=skipped;}catch(NumberFormatException invalid){throw new IOException("WORD_GRID_INVALID");}
                Map<Integer,Map<String,Object>> next=new HashMap<>();int cellIndex=0;
                for(Element tc:direct(tr,"tc")){cellIndex++;Element pr=first(tc,"tcPr"),grid=pr==null?null:first(pr,"gridSpan"),merge=pr==null?null:first(pr,"vMerge");int colSpan=1;
                    try{if(grid!=null)colSpan=Integer.parseInt(grid.getAttributeNS(W,"val"));}catch(NumberFormatException e){throw new IOException("WORD_GRID_INVALID");}
                    if(colSpan<1||colSpan>1000||column>1000)throw new IOException("WORD_GRID_LIMIT");
                    String cp=path+"/w:tr["+row+"]/w:tc["+cellIndex+"]";String value=String.join("\n",direct(tc,"p").stream().map(EvidenceLayoutService::text).toList());
                    Map<String,Object> cell=base(part,cp,"TABLE_CELL",value);cell.putAll(map("tableId",id(hash,part+path),"row",row,"column",column,"columnSpan",colSpan,"rowSpan",1,"mergeContinuationOf",null));
                    if(merge!=null){boolean continuation=!"restart".equals(merge.getAttributeNS(W,"val"));Map<String,Object> origin=vertical.get(column);
                        if(continuation&&origin!=null&&Objects.equals(origin.get("columnSpan"),colSpan)){cell.put("mergeContinuationOf",origin.get("id"));origin.put("rowSpan",(int)origin.get("rowSpan")+1);next.put(column,origin);}
                        else if(!continuation)next.put(column,cell);else cell.put("mergeUnresolved",true);}
                    add(cell);walk(tc,part,cp,depth+1,map("id",cell.get("id"),"row",row,"column",column,"columnSpan",colSpan));column+=colSpan;
                }vertical=next;
            }
        }
        Map<String,Object> base(String part,String path,String kind,String text){List<Integer> ids=blocks.getOrDefault(norm(text),List.of());Integer block=!text.isBlank()&&ids.size()==1?ids.get(0):null;
            return map("id",id(hash,part+path+kind),"kind",kind,"ooxml",map("part",part,"path",path),"physicalPage",null,"printedPage",null,"documentBlock",block,"text",text,"bbox",null,"coordinateSpace",null,"source","OOXML_NATIVE");}
        void add(Map<String,Object> item)throws IOException{chars+=((String)item.get("text")).length();if(chars>MAX_CHARS||spans.size()>=MAX_SPANS)throw new IOException("LAYOUT_TEXT_LIMIT");spans.add(item);}
    }
    private static String ancestorRevision(Element e){for(Node p=e.getParentNode();p instanceof Element;p=p.getParentNode()){if("del".equals(p.getLocalName()))return "DELETED";if("ins".equals(p.getLocalName()))return "INSERTED";}if(e.getElementsByTagNameNS(W,"del").getLength()>0||e.getElementsByTagNameNS(W,"ins").getLength()>0)return "CONTAINS_REVISIONS";return "NONE";}
    private static List<Element> children(Element e){List<Element> result=new ArrayList<>();for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element c)result.add(c);return result;}
    private static List<Element> direct(Element e,String name){return children(e).stream().filter(x->W.equals(x.getNamespaceURI())&&name.equals(x.getLocalName())).toList();}
    private static Element first(Element e,String name){return direct(e,name).stream().findFirst().orElse(null);}
    private static String text(Element e){StringBuilder b=new StringBuilder();collect(e,b,0);return b.toString().strip();}
    private static void collect(Element e,StringBuilder b,int depth){if(depth>40||b.length()>100_000)throw new LayoutLimit();if(W.equals(e.getNamespaceURI())&&Set.of("t","delText").contains(e.getLocalName())){b.append(e.getTextContent());return;}if(W.equals(e.getNamespaceURI())&&Set.of("tab","br","cr").contains(e.getLocalName()))b.append(' ');for(Element child:children(e))collect(child,b,depth+1);}
    private static String norm(String s){return s.replaceAll("\\s+","");}
}
