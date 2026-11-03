package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.ocr.OcrLayout;
import java.util.*;
import java.util.regex.*;

/** Context is selected from source text/validated layout, never from a model's claimed relationship. */
public final class VisualPageWindows {
    public static final String VERSION="visual-context-window-4-source-relations";
    private static final Pattern CONTRACT=Pattern.compile("(?:合同|协议)(?:编号|号码|号)\\s*[:：]\\s*([A-Za-z0-9][A-Za-z0-9/_-]{3,79})(?![A-Za-z0-9/_-])");
    private static final Pattern STRONG_ID=Pattern.compile("(?<![A-Z0-9])(?:[A-HJ-NPR-Z0-9]{17}|[0-9A-HJ-NPQRTUWXY]{18})(?![A-Z0-9])");
    private static final Pattern CARD_FACE=Pattern.compile("(?m)^\\s*([\\p{IsHan}A-Za-z0-9·]{2,24}(?:身份证|驾驶证|行驶证|资格证书))\\s*[（(]?\\s*(正面|反面)\\s*[)）]?\\s*$");
    private static final Pattern CLAUSE=Pattern.compile("^第([一二三四五六七八九十百零〇两0-9]{1,5})条(?:[\\s:：、.．]|[\\p{IsHan}]).{0,100}$");
    private static final Pattern MATERIAL_BOUNDARY=Pattern.compile("^(?:(?:附件|附录)\\s*[一二三四五六七八九十A-Z0-9]+(?:[\\s:：、.．].*)?|(?:.*(?:身份证|营业执照|驾驶证|行驶证|资格证书|资质证书|授权委托书))(?:[（(](?:正面|反面)[)）])?)$");
    private VisualPageWindows() {}

    /** A document plan contains independent materials and overlapping, source-related windows. */
    public static List<List<Integer>> plan(Document doc){
        if(doc==null||doc.pages()==null)return cn.bidcheck.compat.Java8Api.listOf();
        List<Page> ordered=cn.bidcheck.compat.Java8Api.toList(doc.pages().stream().sorted(Comparator.comparingInt(Page::number)));
        if(!SourceSafety.extension(doc).equals("pdf"))return cn.bidcheck.compat.Java8Api.toList(ordered.stream().map(p->cn.bidcheck.compat.Java8Api.listOf(p.number())));
        Map<Integer,Page> byNumber=new TreeMap<>();ordered.forEach(p->byNumber.put(p.number(),p));Set<Integer> linked=linkedPages(byNumber);
        List<List<Integer>> windows=new ArrayList<>();
        for(int first=0;first<ordered.size();){
            int last=first;while(last+1<ordered.size()&&ordered.get(last+1).number()==ordered.get(last).number()+1&&linked.contains(ordered.get(last+1).number()))last++;
            for(int start=first;start<=last;start+=2){int end=Math.min(last,start+2);windows.add(cn.bidcheck.compat.Java8Api.toList(ordered.subList(start,end+1).stream().map(Page::number)));if(end==last)break;}
            first=last+1;
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(windows);
    }

    /** Reading relationships do not establish that two records belong to the same person or vehicle. */
    public static List<Map<String,Object>> relations(Document doc,List<Integer> input){
        if(doc==null||doc.pages()==null||input==null||input.size()<2||!valid(doc,input))return cn.bidcheck.compat.Java8Api.listOf();
        Map<Integer,Page> pages=new TreeMap<>();doc.pages().forEach(p->pages.put(p.number(),p));
        Map<Integer,Map<String,Object>> links=links(pages);List<Map<String,Object>> result=new ArrayList<>();
        for(int i=1;i<input.size();i++){
            Map<String,Object> relation=new LinkedHashMap<>(links.get(input.get(i)));
            relation.put("documentId",doc.id());relation.put("documentHash",doc.sha256());relation.put("documentVersion",doc.version());
            relation.put("contextVersion",VERSION);relation.put("identityEstablished",false);
            relation.put("relationId",SourceSafety.digest((doc.projectId()+"\n"+doc.id()+"\n"+doc.sha256()+"\n"+doc.version()+"\n"+VERSION+"\n"+relation.get("type")+"\n"+relation.get("basis")
                +"\n"+input.get(i-1)+"\n"+opening(pages.get(input.get(i-1)))+"\n"+input.get(i)+"\n"+opening(pages.get(input.get(i)))+"\n"+relation.getOrDefault("tableHeaders",cn.bidcheck.compat.Java8Api.listOf())).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            result.add(Collections.unmodifiableMap(relation));
        }return cn.bidcheck.compat.Java8Api.listCopyOf(result);
    }

    /** Use existing source-validated full-page geometry before paying to submit the whole page again. */
    public static Map<String,Object> withRegion(Document doc,int page,Map<String,Object> request){
        if(request.containsKey("region")||!"FIELD".equals(request.get("task"))||!SourceSafety.extension(doc).equals("pdf")||!(request.get("fields") instanceof List<?>)||((List<?>) (request.get("fields"))).isEmpty())return request;
        Page location=doc.pages().stream().filter(p->p.number()==page).findFirst().orElse(null);if(location==null||location.layout()==null)return request;
        try {
            OcrLayout layout=location.layout().validated(location.text());
            // A crop/embedded image has a different coordinate frame; do not pretend it covers this page.
            if(layout.frames().size()!=1)return request;OcrLayout.Frame frame=layout.frames().get(0);
            if(!"PDF_RENDER".equals(frame.sourceKind())||frame.sourceIndex()!=page||frame.crop()!=null)return request;
            Set<String> matched=new HashSet<>();List<OcrLayout.TextBox> boxes=new ArrayList<>();
            for(OcrLayout.TextBox box:layout.textBoxes())for(Object field:((List<?>) (request.get("fields"))))if(field instanceof String&&!cn.bidcheck.compat.Java8Api.isBlank(((String) (field)))&&box.text().contains(((String) (field)))){matched.add(((String) (field)));boxes.add(box);}
            if(matched.size()!=((List<?>) (request.get("fields"))).size()||boxes.isEmpty())return request;
            double x=boxes.stream().mapToDouble(b->b.bbox().x()).min().orElse(0),y=boxes.stream().mapToDouble(b->b.bbox().y()).min().orElse(0);
            double right=boxes.stream().mapToDouble(b->b.bbox().x()+b.bbox().width()).max().orElse(frame.width()),bottom=boxes.stream().mapToDouble(b->b.bbox().y()+b.bbox().height()).max().orElse(frame.height());
            double margin=Math.max(12,Math.max(frame.width(),frame.height())*.03);x=Math.max(0,x-margin);y=Math.max(0,y-margin);right=Math.min(frame.width(),right+margin);bottom=Math.min(frame.height(),bottom+margin);
            if((right-x)*(bottom-y)>.8*frame.width()*frame.height())return request;
            Map<String,Object> cropped=new LinkedHashMap<>(request);cropped.put("region",cn.bidcheck.compat.Java8Api.listOf(x/frame.width(), y/frame.height(), right/frame.width(), bottom/frame.height()));
            cropped.put("roiBasis","VALIDATED_FULL_PAGE_LAYOUT");cropped.put("roiFrame",cn.bidcheck.compat.Java8Api.mapOf("width", frame.width(), "height", frame.height(), "imageHash", frame.imageHash()));return cropped;
        }catch(IllegalArgumentException ignored){return request;}
    }

    /** Three pages per window, one shared page. A page may occur in two windows, retaining both inputs. */
    public static List<List<Integer>> forPage(Document doc,int page,Map<String,Object> request) {
        if(doc==null||doc.pages()==null||doc.pages().stream().noneMatch(p->p.number()==page))return cn.bidcheck.compat.Java8Api.listOf();
        if(!SourceSafety.extension(doc).equals("pdf")||request.containsKey("region")||request.containsKey("imageIndex"))return cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(page));
        Map<Integer,Page> pages=new TreeMap<>();for(Page p:doc.pages())if("PAGE".equals(p.locationType()))pages.put(p.number(),p);
        if(!pages.containsKey(page))return cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(page));
        Set<Integer> linked=linkedPages(pages);
        int first=page,last=page;
        while(first>1&&linked.contains(first))first--;
        while(linked.contains(last+1))last++;
        if(first==last)return cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(page));
        List<List<Integer>> out=new ArrayList<>();
        for(int start=first;start<=last;start+=2){
            int end=Math.min(last,start+2);if(start<=page&&page<=end){List<Integer> window=new ArrayList<>();for(int p=start;p<=end;p++)window.add(p);out.add(cn.bidcheck.compat.Java8Api.listCopyOf(window));}
            if(end==last)break;
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }

    /** Reject arbitrary caller-supplied adjacent pages too; all adjacent pairs need source-grounded linkage. */
    static boolean valid(Document doc,List<Integer> input) {
        if(input==null||input.isEmpty()||input.size()>3||new HashSet<>(input).size()!=input.size())return false;
        Map<Integer,Page> pages=new TreeMap<>();for(Page p:doc.pages())pages.put(p.number(),p);Set<Integer> linked=linkedPages(pages);
        for(int i=0;i<input.size();i++){
            Integer n=input.get(i);if(n==null||!pages.containsKey(n))return false;
            if(i>0&&(n!=input.get(i-1)+1||!SourceSafety.extension(doc).equals("pdf")||!linked.contains(n)))return false;
        }
        return true;
    }
    static boolean validIndependent(Document doc,List<Integer> input){
        if(doc==null||doc.pages()==null||!SourceSafety.extension(doc).equals("pdf")||input==null||input.isEmpty()||input.size()>3||new HashSet<>(input).size()!=input.size())return false;
        Set<Integer> source=new HashSet<>();for(Page page:doc.pages())if("PAGE".equals(page.locationType()))source.add(page.number());
        return input.stream().allMatch(p->p!=null&&p>0&&source.contains(p));
    }

    /** A source-text chain only supplies reading context; it never establishes a business entity identity. */
    private static Set<Integer> linkedPages(Map<Integer,Page> pages){
        return links(pages).keySet();
    }
    private static Map<Integer,Map<String,Object>> links(Map<Integer,Page> pages){
        Map<Integer,Map<String,Object>> linked=new LinkedHashMap<>();Page previous=null;String contractId=null,contractTitle=null,tableTitle=null;Set<String> tableHeaders=cn.bidcheck.compat.Java8Api.setOf();
        for(Page page:pages.values()){
            Set<String> ids=matches(CONTRACT,page.text());
            boolean adjacent=previous!=null&&page.number()==previous.number()+1&&"PAGE".equals(previous.locationType())&&"PAGE".equals(page.locationType());
            boolean continuation=adjacent&&contractId!=null&&ids.isEmpty()&&contractContinues(previous,page,contractTitle);
            boolean table=adjacent&&namedTableContinues(previous,page,tableTitle,tableHeaders);
            String kind=adjacent?relatedType(previous,page):"";
            if(table)kind="TABLE_CONTINUATION";else if(continuation)kind="CONTRACT_CONTINUATION";
            if(!kind.isEmpty()){
                Map<String,Object> link=new LinkedHashMap<>();link.put("type",kind);link.put("fromPage",previous.number());link.put("toPage",page.number());
                link.put("basis",table?(headers(page).isEmpty()?"EXPLICIT_CONTINUATION_INHERITED_HEADERS":"EXPLICIT_CONTINUATION_MATCHING_HEADERS"):continuation?"ANCHORED_CONTRACT_CONTINUATION":kind.equals("CARD_FACES")?"MATCHED_NAMED_FRONT_BACK":kind.equals("CONTRACT_CONTINUATION")?"MATCHING_CONTRACT_NUMBER":"MATCHING_RECORD_AND_HEADERS");
                if(table)link.put("tableHeaders",headers(previous).isEmpty()?cn.bidcheck.compat.Java8Api.listCopyOf(tableHeaders):cn.bidcheck.compat.Java8Api.listCopyOf(headers(previous)));
                link.put("sourceQuotes",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", previous.number(), "quote", opening(previous)), cn.bidcheck.compat.Java8Api.mapOf("page", page.number(), "quote", opening(page))));linked.put(page.number(),link);
            }
            if(ids.size()==1){String title=namedTitle(page,false);contractId=ids.iterator().next();contractTitle=title;}
            else if(!continuation){contractId=null;contractTitle=null;}
            String nextTitle=namedTitle(page,true);if(nextTitle!=null)tableTitle=nextTitle;else if(!table)tableTitle=null;
            Set<String> currentHeaders=headers(page);if(!currentHeaders.isEmpty())tableHeaders=currentHeaders;else if(!table)tableHeaders=cn.bidcheck.compat.Java8Api.setOf();
            previous=page;
        }
        return linked;
    }
    private static String opening(Page page){String value=Objects.toString(page.text(),"");return value.substring(0,Math.min(1200,value.length()));}
    private static boolean namedTableContinues(Page a,Page b,String anchoredTitle,Set<String> anchoredHeaders){
        if(!matches(CONTRACT,a.text()).isEmpty()||!matches(CONTRACT,b.text()).isEmpty()||newMaterialBoundary(b))return false;
        String title=namedTitle(a,true);if(title==null)title=anchoredTitle;
        String next=namedTitle(b,true);if(title==null||next!=null&&!title.equals(next)||!hasContinuation(b))return false;
        Set<String> first=headers(a),following=headers(b);if(first.isEmpty())first=anchoredHeaders;
        if(first.size()!=1)return false;if(first.equals(following))return true;
        String schema=first.iterator().next();
        return following.isEmpty()&&!schema.matches("^[0-9]+:[0-9]+:.*")&&hasDataColumns(b,schema.split("\\|",-1).length);
    }
    private static boolean contractContinues(Page a,Page b,String title){
        if(newMaterialBoundary(b)||namedTitle(b,true)!=null||head(b).stream().anyMatch(s->s.matches("^(?:甲方|乙方|买方|卖方|签订日期|签约日期)\\s*[:：].*")))return false;
        String next=namedTitle(b,false);if(next!=null&&!next.equals(title))return false;
        if(title!=null&&title.equals(next))return true;
        // A numbered clause at the start of the next page must follow the previous page's last clause.
        List<Integer> before=clauseNumbers(a),after=clauseNumbers(b);if(before.isEmpty()||after.isEmpty())return false;
        boolean clauseAtStart=head(b).stream().filter(s->!s.matches("(?:接上页|续页|第[0-9一二三四五六七八九十]+页(?:共[0-9一二三四五六七八九十]+页)?)")).findFirst().map(s->CLAUSE.matcher(s).matches()).orElse(false);
        return clauseAtStart&&after.get(0)==before.get(before.size()-1)+1;
    }
    private static List<String> head(Page page){return cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(Objects.toString(page.text(),"")).map(cn.bidcheck.compat.Java8Api::strip).map(s->s.replaceFirst("^#{1,6}\\s+","").replaceAll("^\\*\\*|\\*\\*$","").replaceAll("(?is)<[^>]{1,300}>","")).filter(s->!s.isEmpty()).limit(6));}
    private static boolean newMaterialBoundary(Page page){return head(page).stream().anyMatch(s->MATERIAL_BOUNDARY.matcher(s).matches());}
    private static boolean hasContinuation(Page page){return head(page).stream().anyMatch(s->s.matches("(?:续表|接上表)")||s.matches(".*[（(](?:续表|续)[)）]")||s.matches("(?:续表|续)[：:].*"));}
    private static String namedTitle(Page page,boolean table){
        Set<String> titles=new HashSet<>();for(String line:head(page)){
            if(CLAUSE.matcher(line).matches())continue;
            String cleaned=line.replaceAll("[（(](?:续表?|接上页)[)）]","").replaceAll("^(?:续表|续)[：:]\\s*","").replaceAll("\\s+","");
            if(cleaned.length()>=4&&cleaned.length()<=64&&cleaned.matches(table?"[\\p{IsHan}A-Za-z0-9·—_-]+表":"[\\p{IsHan}A-Za-z0-9·—_-]+(?:合同|协议)"))titles.add(cleaned);
        }return titles.size()==1?titles.iterator().next():null;
    }
    private static List<Integer> clauseNumbers(Page page){
        List<Integer> numbers=new ArrayList<>();for(String line:cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(Objects.toString(page.text(),"")).map(cn.bidcheck.compat.Java8Api::strip))){
            Matcher m=CLAUSE.matcher(line);if(m.matches()){int n=clauseNumber(m.group(1));if(n>0){if(!numbers.isEmpty()&&n<=numbers.get(numbers.size()-1))return cn.bidcheck.compat.Java8Api.listOf();numbers.add(n);}}
        }return numbers;
    }
    private static int clauseNumber(String text){
        if(text.matches("[0-9]+")){try{return Integer.parseInt(text);}catch(NumberFormatException invalid){return -1;}}
        int sum=0,digit=0;for(char c:text.toCharArray()){if(c=='十'||c=='百'){sum+=(digit==0?1:digit)*(c=='十'?10:100);digit=0;}else{int value="零一二三四五六七八九".indexOf(c);if(c=='〇')value=0;if(c=='两')value=2;if(value<0)return -1;digit=value;}}return sum+digit;
    }

    private static String relatedType(Page a,Page b) {
        if(a==null||b==null||b.number()!=a.number()+1||!"PAGE".equals(a.locationType())||!"PAGE".equals(b.locationType()))return "";
        Matcher front=CARD_FACE.matcher(Objects.toString(a.text(),"")),back=CARD_FACE.matcher(Objects.toString(b.text(),""));
        if(front.find()&&back.find()&&front.group(1).equals(back.group(1))&&!front.group(2).equals(back.group(2))){
            if(!front.find()&&!back.find())return "CARD_FACES";
        }
        if(newMaterialBoundary(b))return "";
        Set<String> x=matches(CONTRACT,a.text()),y=matches(CONTRACT,b.text());
        // Multiple different contracts on a page are an ambiguous material boundary.
        if(x.size()==1&&x.equals(y))return "CONTRACT_CONTINUATION";
        if(!x.isEmpty()||!y.isEmpty())return "";
        Set<String> ids=matches(STRONG_ID,a.text()),other=matches(STRONG_ID,b.text());ids.retainAll(other);
        if(ids.isEmpty())return "";
        Set<String> headers=headers(a),next=headers(b);headers.retainAll(next);
        return !headers.isEmpty()?"TABLE_CONTINUATION":"";
    }
    private static Set<String> matches(Pattern pattern,String text) {
        Set<String> out=new HashSet<>();Matcher m=pattern.matcher(Objects.toString(text,""));
        while(m.find())out.add(m.groupCount()>0?m.group(1):m.group());return out;
    }
    private static Set<String> headers(Page page) {
        Set<String> out=new HashSet<>();if(page.layout()!=null)try {
            OcrLayout layout=page.layout().validated(page.text());
            for(OcrLayout.Table table:layout.tables()) {
                List<OcrLayout.Cell> cells=cn.bidcheck.compat.Java8Api.toList(table.cells().stream().filter(OcrLayout.Cell::header).sorted(Comparator.comparingInt(OcrLayout.Cell::row).thenComparingInt(OcrLayout.Cell::column)));
                if(cells.size()<2)continue;
                out.add(cells.stream().map(c->c.row()+":"+c.column()+":"+c.columnSpan()+":"+c.text().replaceAll("\\s+","")).reduce((a,b)->a+"|"+b).orElse(""));
            }
        }catch(IllegalArgumentException ignored){return cn.bidcheck.compat.Java8Api.setOf();/* Invalid layout cannot establish a context relationship. */}
        // PDF-mode OCR has no geometry. Only explicit delimited source headers may bridge its pages.
        if(out.isEmpty())out.addAll(textHeaders(page));
        return out;
    }
    static Set<String> textHeaders(Page page){
        Set<String> out=new LinkedHashSet<>();List<String> lines=tableLines(page);
        for(int i=0;i<Math.min(12,lines.size());i++){
            String row=cn.bidcheck.compat.Java8Api.strip(lines.get(i));if(!(row.contains("|")||row.contains("｜")||row.contains("\t")))continue;
            List<String> cells=tableCells(row);
            if(cells.size()<3||cells.size()>20||cells.stream().anyMatch(s->s.length()>40))continue;
            long labels=cells.stream().filter(s->s.matches("(?:序号|编号|姓名|人员姓名|身份证号(?:码)?|公民身份号码|车牌号(?:码)?|号牌号码|车辆识别代号|车架号|VIN|车型|车辆类型|车辆型号|所有人|持有人|证书名称|证书编号|资格名称|资格等级|有效期|合同编号|合同名称|合同金额|项目名称|项目编号|数量|单位|规格|型号|单价|金额|备注|岗位|职务|专业|注册编号|注册号|服务内容|签订日期)" )).count();
            if(labels<3)continue;
            boolean hasRow=false;for(int j=i+1;j<Math.min(lines.size(),i+8);j++){
                String candidate=cn.bidcheck.compat.Java8Api.strip(lines.get(j));if(candidate.matches("[|｜:：\\-—\\s]+"))continue;
                if((candidate.contains("|")||candidate.contains("｜")||candidate.contains("\t"))&&!cn.bidcheck.compat.Java8Api.isBlank(candidate)){hasRow=true;break;}
            }
            if(hasRow)out.add(String.join("|",cells).replaceAll("\\s+",""));
        }return out;
    }
    private static boolean hasDataColumns(Page page,int columns){
        if(columns<3||columns>20)return false;
        return tableLines(page).stream().filter(line->line.contains("|")||line.contains("｜")||line.contains("\t")).map(VisualPageWindows::tableCells)
            .anyMatch(cells->cells.size()==columns&&cells.stream().anyMatch(cell->cell.matches("[0-9]{1,5}|[A-HJ-NPR-Z0-9]{17}|[0-9]{17}[0-9Xx]")));
    }
    private static List<String> tableCells(String row){return cn.bidcheck.compat.Java8Api.toList(Arrays.stream(row.replace('｜','|').split("[|\\t]",-1)).map(cn.bidcheck.compat.Java8Api::strip).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)));}
    private static List<String> tableLines(Page page){
        String original=Objects.toString(page.text(),"");
        // This is only classification, never a geometry parser. Original HTML is retained as evidence.
        String content=original.substring(0,Math.min(original.length(),16000)).replaceAll("(?is)<(?:tr|/tr)\\b[^>]*>","\n")
            .replaceAll("(?is)<(?:th|td)\\b[^>]*>","|").replaceAll("(?is)</(?:th|td)\\s*>","").replaceAll("(?is)<[^>]{1,300}>","").replace("&nbsp;"," ");
        return cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(content).limit(40));
    }
}
