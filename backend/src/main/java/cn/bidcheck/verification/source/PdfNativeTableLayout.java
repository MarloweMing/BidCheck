package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.awt.geom.Point2D;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.*;
import static cn.bidcheck.verification.source.SourceSafety.map;

/** Local single-page PDF geometry. Closed native lines own cells; exact stored text owns values.
 * No OCR, inferred grid, cross-cell concatenation, or full-document text pass is performed. */
final class PdfNativeTableLayout {
    static final String VERSION="pdf-native-cell-layout-1";
    private static final double EPS=1.0;
    private static final Set<String> LABELS=cn.bidcheck.compat.Java8Api.setOf("姓名", "人员姓名", "年龄", "学历", "职称", "身份证号", "身份证号码", "公民身份号码", "拟在本合同任职", "拟任岗位", "岗位", "职务", "工作单位", "聘用单位", "单位名称", "证书编号", "注册编号", "专业", "注册专业", "证书名称", "证书类别", "等级", "级别", "有效期", "注册有效期", "缴费月份", "缴费单位", "参保单位");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Line{
        private final double x1;
        private final double y1;
        private final double x2;
        private final double y2;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Line(@com.fasterxml.jackson.annotation.JsonProperty("x1") double x1, @com.fasterxml.jackson.annotation.JsonProperty("y1") double y1, @com.fasterxml.jackson.annotation.JsonProperty("x2") double x2, @com.fasterxml.jackson.annotation.JsonProperty("y2") double y2) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; }
        public double x1() { return x1; }
        public double y1() { return y1; }
        public double x2() { return x2; }
        public double y2() { return y2; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Line that = (Line) other; return java.util.Objects.equals(this.x1, that.x1) && java.util.Objects.equals(this.y1, that.y1) && java.util.Objects.equals(this.x2, that.x2) && java.util.Objects.equals(this.y2, that.y2); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.x1); hash = 31 * hash + java.util.Objects.hashCode(this.y1); hash = 31 * hash + java.util.Objects.hashCode(this.x2); hash = 31 * hash + java.util.Objects.hashCode(this.y2); return hash; }
        @Override public String toString() { return "Line[" + "x1=" + x1 + ", y1=" + y1 + ", x2=" + x2 + ", y2=" + y2 + "]"; }
boolean horizontal(){return Math.abs(y1-y2)<EPS;}double start(){return horizontal()?Math.min(x1,x2):Math.min(y1,y2);}double end(){return horizontal()?Math.max(x1,x2):Math.max(y1,y2);}double axis(){return horizontal()?(y1+y2)/2:(x1+x2)/2;}}
    private static final class Glyph {final String text;final double x,y,right,bottom,baseline;int start=-1,end=-1;Glyph(String t,double x,double y,double r,double b,double base){text=t;this.x=x;this.y=y;right=r;bottom=b;baseline=base;}}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Cell{
        private final int row;
        private final int column;
        private final int rowSpan;
        private final int columnSpan;
        private final double x;
        private final double y;
        private final double right;
        private final double bottom;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Cell(@com.fasterxml.jackson.annotation.JsonProperty("row") int row, @com.fasterxml.jackson.annotation.JsonProperty("column") int column, @com.fasterxml.jackson.annotation.JsonProperty("rowSpan") int rowSpan, @com.fasterxml.jackson.annotation.JsonProperty("columnSpan") int columnSpan, @com.fasterxml.jackson.annotation.JsonProperty("x") double x, @com.fasterxml.jackson.annotation.JsonProperty("y") double y, @com.fasterxml.jackson.annotation.JsonProperty("right") double right, @com.fasterxml.jackson.annotation.JsonProperty("bottom") double bottom) { this.row = row; this.column = column; this.rowSpan = rowSpan; this.columnSpan = columnSpan; this.x = x; this.y = y; this.right = right; this.bottom = bottom; }
        public int row() { return row; }
        public int column() { return column; }
        public int rowSpan() { return rowSpan; }
        public int columnSpan() { return columnSpan; }
        public double x() { return x; }
        public double y() { return y; }
        public double right() { return right; }
        public double bottom() { return bottom; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Cell that = (Cell) other; return java.util.Objects.equals(this.row, that.row) && java.util.Objects.equals(this.column, that.column) && java.util.Objects.equals(this.rowSpan, that.rowSpan) && java.util.Objects.equals(this.columnSpan, that.columnSpan) && java.util.Objects.equals(this.x, that.x) && java.util.Objects.equals(this.y, that.y) && java.util.Objects.equals(this.right, that.right) && java.util.Objects.equals(this.bottom, that.bottom); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.row); hash = 31 * hash + java.util.Objects.hashCode(this.column); hash = 31 * hash + java.util.Objects.hashCode(this.rowSpan); hash = 31 * hash + java.util.Objects.hashCode(this.columnSpan); hash = 31 * hash + java.util.Objects.hashCode(this.x); hash = 31 * hash + java.util.Objects.hashCode(this.y); hash = 31 * hash + java.util.Objects.hashCode(this.right); hash = 31 * hash + java.util.Objects.hashCode(this.bottom); return hash; }
        @Override public String toString() { return "Cell[" + "row=" + row + ", column=" + column + ", rowSpan=" + rowSpan + ", columnSpan=" + columnSpan + ", x=" + x + ", y=" + y + ", right=" + right + ", bottom=" + bottom + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Aligned{
        private final List<Glyph> glyphs;
        private final boolean complete;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Aligned(@com.fasterxml.jackson.annotation.JsonProperty("glyphs") List<Glyph> glyphs, @com.fasterxml.jackson.annotation.JsonProperty("complete") boolean complete) { this.glyphs = glyphs; this.complete = complete; }
        public List<Glyph> glyphs() { return glyphs; }
        public boolean complete() { return complete; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Aligned that = (Aligned) other; return java.util.Objects.equals(this.glyphs, that.glyphs) && java.util.Objects.equals(this.complete, that.complete); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.glyphs); hash = 31 * hash + java.util.Objects.hashCode(this.complete); return hash; }
        @Override public String toString() { return "Aligned[" + "glyphs=" + glyphs + ", complete=" + complete + "]"; }
}
    static Map<String,Object> extract(Document document,Page stored,Path path)throws IOException{
        long started=System.nanoTime();List<Map<String,Object>> spans=new ArrayList<>();
        try(PDDocument pdf=Loader.loadPDF(path.toFile(),IOUtils.createTempFileOnlyStreamCache())){
            SourceSafety.pdfPage(pdf,stored.number());
            if(stored.number()<1||stored.number()>pdf.getNumberOfPages())return unavailable(document,stored,"PAGE_OUTSIDE_DOCUMENT");
            PDPage page=pdf.getPage(stored.number()-1);if(Math.floorMod(page.getRotation(),360)!=0)return unavailable(document,stored,"NATIVE_TABLE_ROTATION_UNSUPPORTED");
            org.apache.pdfbox.pdmodel.common.PDRectangle crop=page.getCropBox();double width=crop.getWidth(),height=crop.getHeight();
            GridReader lines=new GridReader(page);lines.processPage(page);
            GlyphReader text=new GlyphReader();text.setStartPage(stored.number());text.setEndPage(stored.number());text.setSortByPosition(true);text.getText(pdf);
            Aligned aligned=align(text.glyphs,stored.text());if(!aligned.complete())return unavailable(document,stored,"NATIVE_TABLE_SOURCE_ALIGNMENT_REQUIRED");
            List<Line> display=cn.bidcheck.compat.Java8Api.toList(lines.lines.stream().map(l->new Line(l.x1-crop.getLowerLeftX(),height-(l.y1-crop.getLowerLeftY()),l.x2-crop.getLowerLeftX(),height-(l.y2-crop.getLowerLeftY()))));
            int table=0;for(List<Line> group:components(display)){
                List<Cell> cells=grid(group);if(cells.isEmpty())continue;String tableId=id(document,stored,"table:"+(++table)+cells);
                List<Map<String,Object>> values=new ArrayList<>();boolean invalid=false;
                for(Cell cell:cells){List<Glyph> members=cn.bidcheck.compat.Java8Api.toList(aligned.glyphs().stream().filter(g->g.x>=cell.x-EPS&&g.right<=cell.right+EPS&&g.y>=cell.y-EPS&&g.bottom<=cell.bottom+EPS).sorted(Comparator.comparingInt(g->g.start)));
                    // A glyph intersecting a ruling is not assigned by proximity to either cell.
                    if(aligned.glyphs().stream().anyMatch(g->intersects(cell,g)&&!members.contains(g))){invalid=true;break;}
                    List<Map<String,Object>> fragments=fragments(members,stored.text());String value=String.join("\n",cn.bidcheck.compat.Java8Api.toList(fragments.stream().map(f->f.get("text").toString())));
                    String cellId=id(document,stored,tableId+":"+cell.row+":"+cell.column);String compact=clean(value);boolean header=LABELS.contains(compact);
                    values.add(map("id",cellId,"cellId",cellId,"spanId",cellId,"kind","TABLE_CELL","source","PDFBOX_NATIVE_TABLE","documentId",document.id(),"documentHash",document.sha256(),"documentVersion",document.version(),
                        "physicalPage",stored.number(),"documentBlock",stored.number(),"locationType","PAGE","location",stored.number(),"tableId",tableId,"frameId",id(document,stored,"frame"),"row",cell.row,"column",cell.column,"rowSpan",cell.rowSpan,"columnSpan",cell.columnSpan,
                        "text",value,"bbox",cn.bidcheck.compat.Java8Api.listOf(cell.x, cell.y, cell.right, cell.bottom),"coordinateSpace","PDF_DISPLAY_POINTS_TOP_LEFT","pageWidth",width,"pageHeight",height,"header",header,"sourceFragments",fragments,"mergeUnresolved",false));
                }
                if(invalid)continue;
                boolean horizontalName=values.stream().anyMatch(v->cn.bidcheck.compat.Java8Api.setOf("姓名", "人员姓名").contains(clean(v.get("text").toString()))&&values.stream().anyMatch(o->adjacentLeft(v,o)&&!Boolean.TRUE.equals(o.get("header"))&&!cn.bidcheck.compat.Java8Api.isBlank(o.get("text").toString())));
                for(Map<String,Object> value:values){List<Map<String,Object>> headers=cn.bidcheck.compat.Java8Api.toList(values.stream().filter(h->Boolean.TRUE.equals(h.get("header"))&&h!=value&&(horizontalName?adjacentLeft(h,value):coversAbove(h,value))));
                    value.put("tableType",horizontalName?"KEY_VALUE":"COLUMN_RECORD");value.put("headerCellIds",cn.bidcheck.compat.Java8Api.toList(headers.stream().map(h->h.get("cellId"))));value.put("headerText",String.join(" / ",cn.bidcheck.compat.Java8Api.toList(headers.stream().map(h->h.get("text").toString()))));
                }
                spans.addAll(values);
            }
            return map("status","COMPLETE","version",VERSION,"documentId",document.id(),"documentHash",document.sha256(),"documentVersion",document.version(),"spans",spans,"limitations",spans.isEmpty()?cn.bidcheck.compat.Java8Api.listOf("未发现可与当前原文严格对应的原生封闭表格；不推断缺失材料。"):cn.bidcheck.compat.Java8Api.listOf(),"nativeTableAudit",map("page",stored.number(),"processedPages",1,"glyphCount",aligned.glyphs().size(),"cellCount",spans.size(),"cloudCalls",0,"elapsedMillis",(System.nanoTime()-started)/1_000_000));
        }
    }
    static Map<String,Object> unavailable(Document document,Page page,String code){return map("status","COMPLETE","version",VERSION,"documentId",document.id(),"documentHash",document.sha256(),"documentVersion",document.version(),"spans",cn.bidcheck.compat.Java8Api.listOf(),"limitations",cn.bidcheck.compat.Java8Api.listOf(code),"nativeTableAudit",map("page",page.number(),"processedPages",1,"cellCount",0,"cloudCalls",0,"reasonCode",code));}
    private static int n(Map<String,Object> v,String key){return ((Number)v.get(key)).intValue();}
    private static boolean adjacentLeft(Map<String,Object> h,Map<String,Object> v){return n(h,"column")+n(h,"columnSpan")==n(v,"column")&&n(h,"row")<=n(v,"row")&&n(h,"row")+n(h,"rowSpan")>=n(v,"row")+n(v,"rowSpan");}
    private static boolean coversAbove(Map<String,Object> h,Map<String,Object> v){return n(h,"row")+n(h,"rowSpan")<=n(v,"row")&&n(h,"column")<=n(v,"column")&&n(h,"column")+n(h,"columnSpan")>=n(v,"column")+n(v,"columnSpan");}
    private static String id(Document d,Page p,String key){return SourceSafety.digest((VERSION+":"+d.id()+":"+d.sha256()+":"+d.version()+":"+p.number()+":"+key).getBytes(StandardCharsets.UTF_8));}
    private static String clean(String text){return text.replaceAll("[\\s\\p{Z}]+","");}
    private static boolean whitespace(char c){return Character.isWhitespace(c)||Character.isSpaceChar(c);}
    private static Aligned align(List<Glyph> input,String original){
        List<Glyph> sorted=new ArrayList<>(input);sorted.sort(Comparator.comparingDouble((Glyph g)->g.baseline).thenComparingDouble(g->g.x));List<Glyph> ordered=new ArrayList<>();
        for(int at=0;at<sorted.size();){int end=at+1;double y=sorted.get(at).baseline;while(end<sorted.size()&&sorted.get(end).baseline-y<2.0)end++;List<Glyph> line=new ArrayList<>(sorted.subList(at,end));line.sort(Comparator.comparingDouble(g->g.x));ordered.addAll(line);at=end;}
        List<Integer> offsets=new ArrayList<>();StringBuilder literal=new StringBuilder();for(int i=0;i<original.length();i++)if(!whitespace(original.charAt(i))){literal.append(original.charAt(i));offsets.add(i);}
        String joined=String.join("",cn.bidcheck.compat.Java8Api.toList(ordered.stream().map(g->clean(g.text))));
        if(!joined.contentEquals(literal))return new Aligned(cn.bidcheck.compat.Java8Api.listOf(),false);
        int at=0;for(Glyph glyph:ordered){int size=clean(glyph.text).length();if(size==0)continue;glyph.start=offsets.get(at);glyph.end=offsets.get(at+size-1)+1;at+=size;}
        return new Aligned(cn.bidcheck.compat.Java8Api.toList(ordered.stream().filter(g->g.start>=0)),true);
    }
    private static List<Map<String,Object>> fragments(List<Glyph> glyphs,String original){List<Map<String,Object>> result=new ArrayList<>();int start=-1,end=-1;for(Glyph g:glyphs){if(start<0){start=g.start;end=g.end;continue;}if(g.start>=end&&clean(original.substring(end,g.start)).isEmpty())end=g.end;else{result.add(map("start",start,"end",end,"text",original.substring(start,end)));start=g.start;end=g.end;}}if(start>=0)result.add(map("start",start,"end",end,"text",original.substring(start,end)));return result;}
    private static boolean intersects(Cell c,Glyph g){return Math.min(c.right,g.right)-Math.max(c.x,g.x)>EPS&&Math.min(c.bottom,g.bottom)-Math.max(c.y,g.y)>EPS;}
    private static List<List<Line>> components(List<Line> lines){int[] parents=new int[lines.size()];for(int i=0;i<parents.length;i++)parents[i]=i;for(int i=0;i<lines.size();i++)for(int j=i+1;j<lines.size();j++){Line a=lines.get(i),b=lines.get(j);if(Math.max(Math.min(a.x1,a.x2),Math.min(b.x1,b.x2))<=Math.min(Math.max(a.x1,a.x2),Math.max(b.x1,b.x2))+EPS&&Math.max(Math.min(a.y1,a.y2),Math.min(b.y1,b.y2))<=Math.min(Math.max(a.y1,a.y2),Math.max(b.y1,b.y2))+EPS)parents[root(parents,i)]=root(parents,j);}
        Map<Integer,List<Line>> groups=new LinkedHashMap<>();for(int i=0;i<lines.size();i++)groups.computeIfAbsent(root(parents,i),k->new ArrayList<>()).add(lines.get(i));return new ArrayList<>(groups.values());}
    private static int root(int[] p,int n){while(p[n]!=n){p[n]=p[p[n]];n=p[n];}return n;}
    private static List<Double> axes(List<Line> lines,boolean horizontal){List<Double> values=cn.bidcheck.compat.Java8Api.toList(lines.stream().filter(l->l.horizontal()==horizontal).map(Line::axis).sorted());List<Double> result=new ArrayList<>();for(double value:values)if(result.isEmpty()||value-result.get(result.size()-1)>EPS)result.add(value);return result;}
    private static int edge(List<Line> lines,boolean horizontal,double axis,double from,double to){List<Line> matching=cn.bidcheck.compat.Java8Api.toList(lines.stream().filter(l->l.horizontal()==horizontal&&Math.abs(l.axis()-axis)<=EPS&&l.end()>from&&l.start()<to).sorted(Comparator.comparingDouble(Line::start)));double covered=0,last=from;for(Line l:matching){double start=Math.max(from,l.start()),end=Math.min(to,l.end());if(end>last){covered+=Math.max(0,end-Math.max(last,start));last=end;}}double ratio=covered/(to-from);return ratio>.90?1:ratio<.08?0:-1;}
    private static List<Cell> grid(List<Line> lines){List<Double> xs=axes(lines,false),ys=axes(lines,true);int columns=xs.size()-1,rows=ys.size()-1;if(columns<2||rows<1||columns>60||rows>200||rows*columns>3000)return cn.bidcheck.compat.Java8Api.listOf();int[] p=new int[rows*columns];for(int i=0;i<p.length;i++)p[i]=i;
        for(int r=0;r<rows;r++)for(int c=0;c<columns;c++){int top=edge(lines,true,ys.get(r),xs.get(c),xs.get(c+1)),bottom=edge(lines,true,ys.get(r+1),xs.get(c),xs.get(c+1)),left=edge(lines,false,xs.get(c),ys.get(r),ys.get(r+1)),right=edge(lines,false,xs.get(c+1),ys.get(r),ys.get(r+1));if(top<0||bottom<0||left<0||right<0||r==0&&top!=1||r==rows-1&&bottom!=1||c==0&&left!=1||c==columns-1&&right!=1)return cn.bidcheck.compat.Java8Api.listOf();if(c+1<columns&&right==0)p[root(p,r*columns+c)]=root(p,r*columns+c+1);if(r+1<rows&&bottom==0)p[root(p,r*columns+c)]=root(p,(r+1)*columns+c);}
        Map<Integer,List<Integer>> groups=new LinkedHashMap<>();for(int i=0;i<p.length;i++)groups.computeIfAbsent(root(p,i),k->new ArrayList<>()).add(i);List<Cell> cells=new ArrayList<>();for(List<Integer> group:groups.values()){int r0=group.stream().mapToInt(i->i/columns).min().orElseThrow(java.util.NoSuchElementException::new),r1=group.stream().mapToInt(i->i/columns).max().orElseThrow(java.util.NoSuchElementException::new),c0=group.stream().mapToInt(i->i%columns).min().orElseThrow(java.util.NoSuchElementException::new),c1=group.stream().mapToInt(i->i%columns).max().orElseThrow(java.util.NoSuchElementException::new);if(group.size()!=(r1-r0+1)*(c1-c0+1))return cn.bidcheck.compat.Java8Api.listOf();cells.add(new Cell(r0+1,c0+1,r1-r0+1,c1-c0+1,xs.get(c0),ys.get(r0),xs.get(c1+1),ys.get(r1+1)));}return cells;}
    private static final class GlyphReader extends PDFTextStripper {final List<Glyph> glyphs=new ArrayList<>();int operators;final long deadline=System.nanoTime()+8_000_000_000L;GlyphReader()throws IOException{}
        @Override protected void processOperator(org.apache.pdfbox.contentstream.operator.Operator op,List<org.apache.pdfbox.cos.COSBase> args)throws IOException{SourceSafety.interrupted();if(++operators>100000||System.nanoTime()>deadline)throw new IOException("NATIVE_TABLE_PROCESSING_LIMIT");super.processOperator(op,args);}
        @Override protected void processTextPosition(TextPosition t){SourceSafety.interrupted();if(glyphs.size()>=50000)throw new IllegalStateException("NATIVE_TABLE_GLYPH_LIMIT");if(!cn.bidcheck.compat.Java8Api.isBlank(clean(t.getUnicode())))glyphs.add(new Glyph(t.getUnicode(),t.getXDirAdj(),t.getYDirAdj()-t.getHeightDir(),t.getXDirAdj()+t.getWidthDirAdj(),t.getYDirAdj(),t.getYDirAdj()));super.processTextPosition(t);}}
    private static final class GridReader extends PDFGraphicsStreamEngine {
        final List<Line> lines=new ArrayList<>(),path=new ArrayList<>();Point2D current=new Point2D.Double(),begin=current;int operators,pdfOperators;final long deadline=System.nanoTime()+8_000_000_000L;GridReader(PDPage page){super(page);}
        @Override protected void processOperator(org.apache.pdfbox.contentstream.operator.Operator op,List<org.apache.pdfbox.cos.COSBase> args)throws IOException{SourceSafety.interrupted();if(++pdfOperators>100000||System.nanoTime()>deadline)throw new IOException("NATIVE_TABLE_PROCESSING_LIMIT");super.processOperator(op,args);}
        @Override public void appendRectangle(Point2D a,Point2D b,Point2D c,Point2D d){add(a,b);add(b,c);add(c,d);add(d,a);current=a;begin=a;}
        private void add(Point2D a,Point2D b){if(++operators>20000)throw new IllegalStateException("NATIVE_TABLE_PATH_LIMIT");if(Math.abs(a.getX()-b.getX())<EPS||Math.abs(a.getY()-b.getY())<EPS)path.add(new Line(a.getX(),a.getY(),b.getX(),b.getY()));}
        private void flush(boolean filled){if(lines.size()+path.size()>2000)throw new IllegalStateException("NATIVE_TABLE_LINE_LIMIT");if(!filled||path.size()<=4)for(Line line:path)if(line.end()-line.start()>2)lines.add(line);path.clear();}
        @Override public void moveTo(float x,float y){current=new Point2D.Double(x,y);begin=current;}
        @Override public void lineTo(float x,float y){Point2D next=new Point2D.Double(x,y);add(current,next);current=next;}
        @Override public void curveTo(float x1,float y1,float x2,float y2,float x3,float y3){current=new Point2D.Double(x3,y3);}
        @Override public Point2D getCurrentPoint(){return current;}
        @Override public void closePath(){add(current,begin);current=begin;}
        @Override public void endPath(){path.clear();}
        @Override public void strokePath(){flush(false);}
        @Override public void fillPath(int windingRule){flush(true);}
        @Override public void fillAndStrokePath(int windingRule){flush(false);}
        @Override public void clip(int windingRule){}
        @Override public void drawImage(PDImage image){}
        @Override public void shadingFill(COSName name){}
    }
}
