package cn.bidcheck.document.word;

import com.aspose.words.*;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import static cn.bidcheck.document.word.WordRenderSupport.*;

/** Isolated entry point. It never starts Spring, accepts network URLs, or writes a source document. */
public final class WordRenderWorker {
    private WordRenderWorker(){}
    @SuppressWarnings("unchecked") public static void main(String[] ignored)throws Exception{
        Map<String,Object> request=JSON.readValue(cn.bidcheck.compat.Java8Api.readNBytes(System.in, 65537),Map.class);Path output=java.nio.file.Paths.get(str(request,"outputDirectory"));Map<String,Object> result;
        try{result=run(request,output);}catch(Throwable failure){String code=failure instanceof OutOfMemoryError?"MEMORY_LIMIT":(failure instanceof IOException||failure instanceof WordAbort)&&failure.getMessage()!=null&&failure.getMessage().matches("[A-Z_]{1,70}")?failure.getMessage():"ERROR";result=failure(code);}
        try(OutputStream out=new LimitedOutput(Files.newOutputStream(output.resolve("result.json")),MAX_RESULT_BYTES)){JSON.writeValue(out,result);}
    }
    static Map<String,Object> run(Map<String,Object> request,Path output)throws Exception{
        Path license=java.nio.file.Paths.get(str(request,"licensePath"));if(!Files.isRegularFile(license)||Files.size(license)>2_000_000)return failure("LICENSE_INVALID");
        byte[] licenseBytes=Files.readAllBytes(license);if(!hash(licenseBytes).equals(str(request,"licenseHash")))return failure("LICENSE_INVALID");
        try{validateXml(licenseBytes,false);new License().setLicense(new ByteArrayInputStream(licenseBytes));}catch(Exception invalid){return failure("LICENSE_INVALID");}
        if(!WordsCompatibility.identity().equals(str(request,"rendererIdentity")))return failure("WORD_SDK_IDENTITY_MISMATCH");
        Path staged=output.resolve("source.docx");String sourceHash;
        try(InputStream in=Files.newInputStream(java.nio.file.Paths.get(str(request,"original")));OutputStream out=Files.newOutputStream(staged)){sourceHash=digest(in,out,cn.bidcheck.document.DocumentLimits.MAX_FILE_BYTES);}
        if(!sourceHash.equals(str(request,"sourceSha256")))throw new IOException("SOURCE_HASH_MISMATCH");
        Path normalized=output.resolve("normalized-source.docx");
        try{
            cn.bidcheck.document.word.WordDocxStagingNormalizer.Result repair=WordDocxStagingNormalizer.normalize(staged,normalized);
            if(!repair.sourceSha256().equals(sourceHash))throw new IOException("SOURCE_HASH_MISMATCH");
            if(WordsCompatibility.legacyPreflightRequired())validateLegacyDocx(normalized);else validateDocx(normalized);
            java.util.Map<java.lang.String, java.lang.Object> result=renderLicensed(normalized,output,request,sourceHash);
            result.put("rendererIdentity",WordsCompatibility.identity());
            result.put("sourceNormalization",map("policy",WordDocxStagingNormalizer.POLICY,"changedParts",repair.normalizedParts(),"originalSha256",sourceHash,"renderInputSha256",repair.normalizedSha256()));
            return result;
        }finally{Files.deleteIfExists(staged);Files.deleteIfExists(normalized);}
    }
    static Map<String,Object> renderLicensed(Path source,Path output,Map<String,Object> request,String sourceHash)throws Exception{
        long deadline=System.nanoTime()+((Number)request.get("timeoutSeconds")).longValue()*1_000_000_000L;int maximum=((Number)request.get("maxPages")).intValue();
        Set<String> warnings=new LinkedHashSet<>();IWarningCallback warning=info->{if(info.getWarningType()==WarningType.FONT_SUBSTITUTION)warnings.add("FONT_SUBSTITUTION");else if(info.getWarningType()!=WarningType.HINT)warnings.add("LAYOUT_WARNING");};
        Path temp=Files.createDirectories(output.resolve("temp"));
        LoadOptions options=new LoadOptions();options.setLoadFormat(LoadFormat.DOCX);options.setUpdateDirtyFields(false);options.setWarningCallback(warning);
        WordsCompatibility.configureLoad(options,temp,()->checkUnchecked(deadline));
        options.setResourceLoadingCallback(args->{warnings.add("RESOURCE_BLOCKED");return ResourceLoadingAction.SKIP;});
        FontSettings fonts=new FontSettings();String folder=str(request,"fontFolder");if(!cn.bidcheck.compat.Java8Api.isBlank(folder))fonts.setFontsFolder(folder,true);options.setFontSettings(fonts);
        Document document;try(InputStream input=Files.newInputStream(source)){document=new Document(input,options);}
        document.setWarningCallback(warning);document.setResourceLoadingCallback(args->{warnings.add("RESOURCE_BLOCKED");return ResourceLoadingAction.SKIP;});document.setFontSettings(fonts);document.setAttachedTemplate("");WordsCompatibility.configureDocument(document,()->checkUnchecked(deadline));document.removeMacros();document.removeExternalSchemaReferences();
        if(document.hasRevisions())return failure("REVISIONS_PRESENT");
        // Preserve original field results; page layout must not refresh INCLUDETEXT, DATE, DDE or linked fields.
        for(Field field:document.getRange().getFields())field.isLocked(true);
        LayoutCollector collector=new LayoutCollector(document);document.updatePageLayout();check(deadline);
        int count=document.getPageCount();if(count<1||count>maximum)throw new IOException("WORD_PAGE_LIMIT");
        List<Map<String,Object>> pages=new ArrayList<>();for(int i=0;i<count;i++){PageInfo p=document.getPageInfo(i);double width=p.getWidthInPoints(),height=p.getHeightInPoints();if(!Double.isFinite(width)||!Double.isFinite(height)||width<1||height<1||width>20000||height>20000)throw new IOException("WORD_PAGE_SIZE_LIMIT");pages.add(map("renderedPage",i+1,"page",i+1,"locationType","RENDERED_PAGE","physicalPage",null,"printedPage",null,"width",width,"height",height,"coordinateSpace","RENDERED_PAGE_POINTS_TOP_LEFT"));}
        List<Map<String,Object>> spans=structure(document,collector,sourceHash,deadline);List<Map<String,Object>> rendered=renderedSpans(document,sourceHash,deadline,pages);
        if(!warnings.isEmpty())return degraded(warnings);
        PdfSaveOptions pdf=new PdfSaveOptions();pdf.setUpdateFields(false);pdf.setPreserveFormFields(false);pdf.setExportDocumentStructure(true);WordsCompatibility.configureSave(pdf,temp,()->checkUnchecked(deadline));
        try(OutputStream stream=new LimitedOutput(Files.newOutputStream(output.resolve("rendered.pdf")),MAX_PDF_BYTES)){document.save(stream,pdf);}
        if(!warnings.isEmpty()){Files.deleteIfExists(output.resolve("rendered.pdf"));return degraded(warnings);}
        String derived=hash(output.resolve("rendered.pdf"),MAX_PDF_BYTES);collector.setDocument(null);
        return map("status","COMPLETE","errorCode","","usableForEvidence",true,"rendererVersion",BuildVersionInfo.getVersion(),"sourceSha256",sourceHash,"derivedSha256",derived,"pageCount",count,"pages",pages,"spans",spans,"renderedSpans",rendered,"limitations",cn.bidcheck.compat.Java8Api.listOf("页号为此字体配置及渲染器产生的派生页，不能当作原始印刷页号。", "节点页范围来自 Aspose 布局收集器；节点锚点不是段落或跨页单元格的完整边界。", "修订、字体替代及外部资源缺失会阻止派生内容进入正式证据；原 Word 内容块仍独立保留。", "保留原字段结果；不运行宏、外链、OLE 内容或自动更新日期字段。"));
    }
    private static Map<String,Object> degraded(Set<String> warnings){String code=warnings.contains("RESOURCE_BLOCKED")?"RESOURCE_BLOCKED":warnings.contains("FONT_SUBSTITUTION")?"FONT_SUBSTITUTION":"LAYOUT_WARNING";Map<String,Object> result=failure(code);result.put("warningCodes",cn.bidcheck.compat.Java8Api.listCopyOf(warnings));return result;}
    private static void check(long deadline)throws IOException{interrupted();if(System.nanoTime()>deadline)throw new IOException("TIMEOUT");}
    private static void checkUnchecked(long deadline){try{check(deadline);}catch(IOException e){throw new WordAbort(e.getMessage());}}
    private static final class WordAbort extends RuntimeException{WordAbort(String code){super(code);}}
    static List<Map<String,Object>> structure(Document document,LayoutCollector collector,String hash,long deadline)throws Exception{
        List<Map<String,Object>> spans=new ArrayList<>();walk(document,"document",spans,collector,new LayoutEnumerator(document),hash,deadline,new int[]{0},0);return spans;
    }
    private static void walk(Node node,String path,List<Map<String,Object>> spans,LayoutCollector collector,LayoutEnumerator enumerator,String hash,long deadline,int[] textBudget,int depth)throws Exception{
        check(deadline);if(depth>80)throw new IOException("WORD_NODE_DEPTH_LIMIT");int type=node.getNodeType();
        if(cn.bidcheck.compat.Java8Api.setOf(NodeType.PARAGRAPH, NodeType.CELL, NodeType.SHAPE, NodeType.ROW, NodeType.TABLE, NodeType.FOOTNOTE, NodeType.HEADER_FOOTER).contains(type)){
            String kind;
switch (type) {case NodeType.CELL:kind = "TABLE_CELL"; break;case NodeType.ROW:kind = "TABLE_ROW"; break;case NodeType.TABLE:kind = "TABLE"; break;case NodeType.SHAPE:kind = "SHAPE"; break;case NodeType.FOOTNOTE:kind = "FOOTNOTE"; break;case NodeType.HEADER_FOOTER:kind = "HEADER_FOOTER"; break;default:kind = "PARAGRAPH"; break;}
            String text=cn.bidcheck.compat.Java8Api.setOf(NodeType.ROW, NodeType.TABLE, NodeType.HEADER_FOOTER).contains(type)?"":cleanText(node.getText());if((textBudget[0]+=text.length())>MAX_TEXT||spans.size()>=MAX_SPANS)throw new IOException("WORD_LAYOUT_LIMIT");
            int start=collector.getStartPageIndex(node),end=collector.getEndPageIndex(node);Map<String,Object> span=map("id",WordRenderSupport.hash(hash+POLICY+path),"kind",kind,"nodePath",path,"documentBlock",null,"physicalPage",null,"printedPage",null,"startRenderedPage",start>0?start:null,"endRenderedPage",end>0?end:null,"text",text,"bbox",null,"source","ASPOSE_WORDS_NATIVE","coordinateSpace",null);
            Object entity=cn.bidcheck.compat.Java8Api.setOf(NodeType.PARAGRAPH, NodeType.SHAPE).contains(type)&&node.getAncestor(NodeType.HEADER_FOOTER)==null?collector.getEntity(node):null;if(entity!=null){enumerator.setCurrent(entity);span.put("layoutAnchor",map("renderedPage",enumerator.getPageIndex(),"bbox",rectangle(enumerator.getRectangle()),"kind",LayoutEntityType.toString(enumerator.getType()),"coordinateSpace","RENDERED_PAGE_POINTS_TOP_LEFT"));}
            if(node instanceof Cell){Row row=((Cell) (node)).getParentRow();Table table=row.getParentTable();span.put("row",table.getRows().indexOf(row)+1);span.put("cellIndex",row.getCells().indexOf(((Cell) (node)))+1);span.put("tableNodePath",parentPath(parentPath(path)));span.put("horizontalMerge",CellMerge.toString(((Cell) (node)).getCellFormat().getHorizontalMerge()));span.put("verticalMerge",CellMerge.toString(((Cell) (node)).getCellFormat().getVerticalMerge()));span.put("column",null);span.put("rowSpan",null);span.put("columnSpan",null);span.put("gridMapping","USE_OOXML_GRID_FOR_MERGED_COLUMNS");}
            if(node instanceof Shape){span.put("hasImage",((Shape) (node)).hasImage());span.put("isInline",((Shape) (node)).isInline());}
            spans.add(span);
        }
        if(node instanceof CompositeNode<?>){Map<Integer,Integer> counts=new HashMap<>();for(Object raw:((CompositeNode<?>) (node)).getChildNodes(NodeType.ANY,false)){Node child=(Node)raw;int index=counts.merge(child.getNodeType(),1,Integer::sum);walk(child,path+"/"+Node.nodeTypeToString(child.getNodeType())+"["+index+"]",spans,collector,enumerator,hash,deadline,textBudget,depth+1);}}
    }
    private static String parentPath(String s){int i=s.lastIndexOf('/');return i<0?s:s.substring(0,i);}
    static String cleanText(String value){return cn.bidcheck.compat.Java8Api.strip(value.replace('\r','\n').replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]",""));}
    static List<Map<String,Object>> renderedSpans(Document document,String sourceHash,long deadline,List<Map<String,Object>> pages)throws Exception{List<Map<String,Object>> rendered=new ArrayList<>();LayoutEnumerator enumerator=new LayoutEnumerator(document);int pageIndex=0;do{enumerate(enumerator,rendered,sourceHash,"page"+(++pageIndex),0,deadline,pages);}while(enumerator.moveNext());return rendered;}
    private static void enumerate(LayoutEnumerator e,List<Map<String,Object>> out,String hash,String path,int depth,long deadline,List<Map<String,Object>> pages)throws Exception{
        check(deadline);if(depth>80||out.size()>=MAX_SPANS)throw new IOException("WORD_LAYOUT_LIMIT");
        if(e.getType()==LayoutEntityType.SPAN){String text=e.getText();if(text!=null&&!cn.bidcheck.compat.Java8Api.isBlank(text)){int page=e.getPageIndex();List<Double> bbox=rectangle(e.getRectangle());if(page<1||page>pages.size()||bbox==null)throw new IOException("WORD_LAYOUT_COORDINATES_INVALID");Map<String,Object> size=pages.get(page-1);if(bbox.get(0)<-.5||bbox.get(1)<-.5||bbox.get(2)>((Number)size.get("width")).doubleValue()+.5||bbox.get(3)>((Number)size.get("height")).doubleValue()+.5)throw new IOException("WORD_LAYOUT_COORDINATES_INVALID");out.add(map("id",WordRenderSupport.hash(hash+POLICY+"layout"+path),"kind","RENDERED_TEXT_SPAN","renderedPage",page,"locationType","RENDERED_PAGE","printedPage",null,"text",text,"bbox",bbox,"coordinateSpace","RENDERED_PAGE_POINTS_TOP_LEFT","nodePath",null,"source","ASPOSE_LAYOUT_ENUMERATOR"));}}
        if(e.moveFirstChild()){int i=0;do{enumerate(e,out,hash,path+"/"+(++i),depth+1,deadline,pages);}while(e.moveNext());e.moveParent();}
    }
    private static List<Double> rectangle(Rectangle2D.Float r){if(r==null||!Double.isFinite(r.x)||!Double.isFinite(r.y)||!Double.isFinite(r.width)||!Double.isFinite(r.height)||r.width<0||r.height<0)return null;return cn.bidcheck.compat.Java8Api.listOf((double)r.x, (double)r.y, (double)r.x+r.width, (double)r.y+r.height);}
}
