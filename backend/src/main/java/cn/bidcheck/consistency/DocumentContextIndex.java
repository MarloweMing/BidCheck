package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** One source-bound section index per scan. The document default is explicitly distinguished from evidence. */
public final class DocumentContextIndex {
    public static final String VERSION = "document-context-3-explicit-performance-tables";
    private static final Pattern HISTORICAL = Pattern.compile("历史项目|历史业绩|历史合同|过往项目|过往合同|类似项目业绩|类似项目合同|类似业绩|业绩证明|业绩材料|业绩情况|业绩合同|合同业绩|已完成项目|已完成合同|合同复印件|合同协议书|作为.{0,20}负责人的业绩|作为(?:专职)?安全员的业绩|(?:企业|公司|主要|项目负责人|设计负责人)业绩");
    private static final Pattern CURRENT = Pattern.compile("投标函|投标文件封面|投标人基本情况|投标人有效资质证明|本项目|本次投标|本次采购|拟投入.*人员|拟派项目组人员|开标一览表|投标报价表|技术方案|售后方案|商务响应|技术响应|授权委托书|项目管理机构|人员配置|产品配置|承诺书|联合体协议|法定代表人.{0,8}身份证明|项目总计|全部标段");
    private static final Pattern TEMPLATE = Pattern.compile("示例|填写说明|参考模板|示范文本|范例");
    private static final Pattern LOT = Pattern.compile("(?:第?([一二三四五六七八九十百0-9]+)\\s*(?:标段|标包|包件)|(?:标段|标包|包件|包号)\\s*[:：]?\\s*([一二三四五六七八九十百0-9]+))");
    private static final Pattern PERSONNEL_SUPPORT = Pattern.compile("^(?:有效)?人员资格证明(?:文件|材料)$");
    private static final Pattern PERSONNEL_ROLE = Pattern.compile("^(?:项目负责人|项目经理|技术负责人|施工企业主要负责人|施工部分负责人|设计部分负责人|专职安全员|项目组成员|项目主要人员)$");
    private static final Pattern PERFORMANCE_COLUMNS = Pattern.compile("^(?:序号|编号|项次)\\s+.*(?:合同名称|项目名称|合同编号).*(?:签订时间|签署日期|主要内容|金额|负责人).*");
    private final Map<String,List<Assignment>> assignments;

    private DocumentContextIndex(Map<String,List<Assignment>> assignments) { this.assignments=Map.copyOf(assignments); }

    public record Assignment(String status,String scope,String lot,String sectionId,List<String> sectionPath,
                             String reason,int sourcePage,int sourceLine,boolean crossPage,List<Evidence> evidences,boolean boundary) {
        public Assignment { sectionPath=List.copyOf(sectionPath);evidences=List.copyOf(evidences); }
        public Map<String,Object> asMap() {
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("version",VERSION);result.put("status",status);result.put("scope",scope);result.put("lot",lot);
            result.put("sectionId",sectionId);result.put("sectionPath",sectionPath);result.put("reason",reason);
            result.put("sourcePage",sourcePage);result.put("sourceLine",sourceLine);result.put("crossPage",crossPage);result.put("evidences",evidences);
            return result;
        }
    }
    private record Section(int level,boolean structural,String text,String scope,String lot,String id,Evidence evidence,int line,String reason) { }

    public static DocumentContextIndex build(Document document) {
        Objects.requireNonNull(document,"document");
        Map<String,List<Assignment>> indexed=new LinkedHashMap<>();
        List<Section> stack=new ArrayList<>();
        String defaultScope="BID".equals(document.role())?"CURRENT_PROJECT":"UNRESOLVED";
        String lastLot="";Page previous=null;boolean barrier=false;
        for(Page page:document.pages()==null?List.<Page>of():document.pages()) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("上下文扫描已取消");
            if(previous!=null&&(!Objects.equals(previous.locationType(),page.locationType())||page.number()!=previous.number()+1)) {
                stack.clear();lastLot="";barrier=true;
            }
            String text=Objects.toString(page.text(),"");String[] lines=text.split("\\R",-1);
            List<Assignment> values=new ArrayList<>(lines.length);
            boolean insideTable=false,ocrSupplement=false,insideNativePerformanceTable=false;
            if(text.isBlank()){stack.clear();lastLot="";barrier=true;}
            for(int at=0;at<lines.length;at++) {
                String line=lines[at],clean=line.strip().replaceFirst("[:：]$","").stripTrailing();
                if(clean.equals("【PDF识别文字】"))ocrSupplement=true;
                boolean tableLine=insideTable||TableFactSupport.html(line);
                if(line.matches("(?is).*<table\\b.*"))insideTable=true;
                if(line.matches("(?is).*</table\\s*>.*"))insideTable=false;
                String semantic=clean.replaceFirst("^#{1,6}\\s*","").strip();
                // Markdown in appended OCR is transcription formatting, not evidence of document chapter depth.
                int level=ocrSupplement?ocrNumberedPeerLevel(clean,stack):headingLevel(clean);String classified=classify(semantic);
                // A visible performance header supplies a page-local table boundary. Wrapped native
                // cells containing “合同” cannot replace its explicit historical parent chapter.
                // This protection ends at a real numbered heading or at the physical page boundary.
                if(!ocrSupplement&&!tableLine) {
                    if(PERFORMANCE_COLUMNS.matcher(semantic).matches()&&nearest(stack,s->s.scope.equals("HISTORICAL_PROJECT"))!=null)
                        insideNativePerformanceTable=true;
                    else if(level>0&&isHeading(clean))insideNativePerformanceTable=false;
                }
                boolean nativePerformanceCell=insideNativePerformanceTable&&!ocrSupplement&&level==0;
                boolean navigation=semantic.matches(".*(?:\\.{3,}|…{2,}|·{3,}).*\\d+\\s*$");
                boolean generatedHeading=ocrSupplement&&semantic.matches("(?i).*(?:图像.{0,8}分析|图片.{0,8}(?:提取|描述|分析)|二维码|QR\\s*(?:Code|码)|信息抽取(?:结果|报告)|文档信息抽取|建议|注意事项|结论|总结|示例|example|recommendation|conclusion|notes?|placeholder|output format).*");
                boolean heading=!tableLine&&!nativePerformanceCell&&!navigation&&!generatedHeading&&isHeading(clean)&&(level>0||!classified.isBlank()||!lots(clean).isBlank()||!ocrSupplement&&clean.contains("合同"));
                boolean boundary=false;
                if(heading) {
                    boundary=true;
                    boolean lotOnlyHeading=classified.isBlank()&&!lots(clean).isBlank();
                    // A list entry beneath an explicit performance heading is not a new top-level
                    // project. Accept only numbered project/contract captions; peer current or
                    // unknown chapter headings still close the prior section normally.
                    if(classified.isBlank()&&projectCaption(semantic)) {
                        Section historical=nearest(stack,s->s.scope.equals("HISTORICAL_PROJECT")&&classify(headingText(s.text)).equals("HISTORICAL_PROJECT"));
                        if(historical!=null)level=historical.level+1;
                    }
                    // Personnel qualification packets often restart their list at 1 after a
                    // decimal section number. Preserve that source hierarchy without claiming
                    // that a certificate or former employer belongs to the current project.
                    if(!ocrSupplement&&PERSONNEL_ROLE.matcher(headingText(clean)).matches()) {
                        Section packet=nearest(stack,s->PERSONNEL_SUPPORT.matcher(headingText(s.text)).matches());
                        if(packet!=null)level=packet.level+1;
                    } else if(!ocrSupplement&&semantic.matches("^[（(][一二三四五六七八九十百0-9]+[）)]\\s*(?:情况表|证明文件(?:扫描件)?|资格证明(?:文件|材料)?)$")) {
                        Section role=nearest(stack,s->PERSONNEL_ROLE.matcher(headingText(s.text)).matches());
                        if(role!=null)level=role.level+1;
                    }
                    boolean structural=level>0;
                    // A lot label supplies lot evidence, not project evidence. An unnumbered lot can
                    // remain inside an explicitly classified project section without inventing one.
                    if(level==0&&lotOnlyHeading)for(int i=stack.size()-1;i>=0;i--) {
                        Section candidate=stack.get(i);
                        if(!candidate.scope.equals("UNRESOLVED")&&!classify(candidate.text.replaceFirst("^#{1,6}\\s*","")).isBlank()) {level=candidate.level+1;break;}
                    }
                    // Unnumbered attachments inside an explicitly numbered historical/template section cannot escape their parent.
                    if(level==0) {
                        Section numberedParent=null;
                        for(int i=stack.size()-1;i>=0;i--)if(stack.get(i).structural){numberedParent=stack.get(i);break;}
                        level=numberedParent!=null&&protectedScope(numberedParent.scope)?numberedParent.level+1:stack.isEmpty()?100:stack.get(stack.size()-1).level;
                    }
                    while(!stack.isEmpty()&&stack.get(stack.size()-1).level>=level)stack.remove(stack.size()-1);
                    trimIncompatibleNumberedAncestors(clean,stack);
                    Section parent=stack.isEmpty()?null:stack.get(stack.size()-1);
                    String scope=classified;
                    String reason="依据章节标题及父章节确定归属";
                    if(parent!=null&&protectedScope(parent.scope)&&!protectedScope(scope)) {
                        scope=parent.scope;reason="继承历史或模板父章节，内部投标函及字段标签不改变所属项目";
                    } else if(scope.isBlank()) {
                        scope=parent!=null?parent.scope:clean.contains("合同")?"UNRESOLVED":defaultScope;
                        if(parent==null&&lotOnlyHeading){scope="UNRESOLVED";reason="仅有标段来源，缺少明确的项目归属父章节";}
                        if(parent==null&&level<100&&lots(clean).isBlank()) {scope="UNRESOLVED";reason="同级或上级章节切换，标题未说明项目范围";}
                    }
                    if(scope.equals("UNRESOLVED")&&parent!=null)reason="所属章节已定位，但父章节未明确事实适用项目";
                    String lot=lots(clean);
                    if(clean.matches(".*(?:本项目汇总|项目总计|全部标段).*")){lot="";lastLot="";}
                    else if(lot.isBlank())lot=parent!=null&&!parent.lot.isBlank()?parent.lot:!lastLot.isBlank()?"UNRESOLVED":"";
                    if(!lot.isBlank())lastLot=lot;
                    Evidence source=new Evidence(document.id(),document.name(),page.number(),page.locationType(),line,"p"+page.number()+"-l"+(at+1),0);
                    String id="section-"+UUID.nameUUIDFromBytes((document.id()+"|"+document.version()+"|"+page.locationType()+"|"+page.number()+"|"+(at+1)).getBytes(StandardCharsets.UTF_8));
                    stack.add(new Section(level,structural,clean,scope,lot,id,source,at+1,reason));barrier=false;
                }
                if(stack.isEmpty()) {
                    values.add(new Assignment(barrier||defaultScope.equals("UNRESOLVED")?"UNRESOLVED":"DOCUMENT_DEFAULT",barrier?"UNRESOLVED":defaultScope,"","",List.of(),
                            barrier?"缺页、空白页或位置类型改变后，尚无新的明确章节依据":"仅依据投标文档角色；尚未定位到明确章节标题",0,0,false,List.of(),boundary));
                } else {
                    Section section=stack.get(stack.size()-1);
                    List<Evidence> refs=stack.stream().map(Section::evidence).limit(8).toList();
                    boolean crossPage=section.evidence.page()!=page.number();
                    String status=section.scope.equals("UNRESOLVED")?"UNRESOLVED":boundary?"EXPLICIT":"INHERITED";
                    values.add(new Assignment(status,section.scope,section.lot,section.id,stack.stream().map(Section::text).toList(),section.reason,
                            section.evidence.page(),section.line,crossPage,refs,boundary));
                }
            }
            indexed.put(key(page),List.copyOf(values));previous=page;
        }
        return new DocumentContextIndex(indexed);
    }

    public Assignment at(Page page,int oneBasedLine) {
        List<Assignment> lines=assignments.getOrDefault(key(page),List.of());
        if(oneBasedLine<1||oneBasedLine>lines.size())return new Assignment("UNRESOLVED","UNRESOLVED","","",List.of(),"原文位置无法绑定",0,0,false,List.of(),true);
        return lines.get(oneBasedLine-1);
    }
    private static String key(Page page){return page.locationType()+"|"+page.number();}
    private static Section nearest(List<Section> stack,java.util.function.Predicate<Section> matches){for(int i=stack.size()-1;i>=0;i--)if(matches.test(stack.get(i)))return stack.get(i);return null;}
    private static void trimIncompatibleNumberedAncestors(String text,List<Section> stack) {
        Matcher heading=Pattern.compile("^(\\d+(?:\\.\\d+)+)\\s+.+").matcher(text);
        if(!heading.matches())return;
        String number=heading.group(1);
        while(!stack.isEmpty()) {
            Section parent=stack.get(stack.size()-1);
            Matcher numberedParent=Pattern.compile("^(\\d+(?:\\.\\d+)*)(?:[.、．]\\s*|\\s+).+").matcher(parent.text);
            if(!parent.structural||!numberedParent.matches()||number.startsWith(numberedParent.group(1)+"."))break;
            stack.remove(stack.size()-1);
        }
    }
    /** A printed decimal peer can end its native chapter; arbitrary OCR Markdown cannot. */
    private static int ocrNumberedPeerLevel(String text,List<Section> stack) {
        Matcher heading=Pattern.compile("^(\\d+(?:\\.\\d+)+)\\s+[\\p{IsHan}A-Za-z].*").matcher(text);
        if(!heading.matches())return 0;
        String number=heading.group(1),parent=number.substring(0,number.lastIndexOf('.'));
        for(int i=stack.size()-1;i>=0;i--) {
            Section candidate=stack.get(i);
            if(!candidate.structural)continue;
            Matcher previous=Pattern.compile("^(\\d+(?:\\.\\d+)+)\\s+.+").matcher(candidate.text);
            if(previous.matches()) {
                String priorNumber=previous.group(1);
                if(parent.equals(priorNumber.substring(0,priorNumber.lastIndexOf('.'))))return candidate.level;
            }
        }
        return 0;
    }
    private static String headingText(String value){return value.replaceFirst("^#{1,6}\\s*","").replaceFirst("^(?:\\d+(?:\\.\\d+)*[.、．]?|[一二三四五六七八九十百]+[、．.]|[（(][一二三四五六七八九十百0-9]+[）)])\\s*","").strip();}
    private static boolean projectCaption(String text){return text.matches("^\\d+[.、．]\\s*[^:：=|｜<>。；;]{2,95}(?:合同|工程|项目|订单)(?:[（(][^）)]{1,30}[）)])?")
            &&!text.matches(".*(?:应当|应提供|须提供|投标人须|不得|按照|说明|要求|条款|负责人的情况).*");}
    private static boolean protectedScope(String scope){return scope.equals("HISTORICAL_PROJECT")||scope.equals("TEMPLATE");}
    private static String classify(String text){if(text.matches(".*(?:评审索引表|^目录$).*"))return "TEMPLATE";if(TEMPLATE.matcher(text).find())return "TEMPLATE";if(HISTORICAL.matcher(text).find())return "HISTORICAL_PROJECT";if(CURRENT.matcher(text).find())return "CURRENT_PROJECT";return "";}
    private static boolean isHeading(String text){
        String plain=text.replaceFirst("^#{1,6}\\s*","").strip();
        if(plain.matches("(?i)^(?:[0-9]+(?:\\.[0-9]+)*\\s+)?(?:扫描全能王|CamScanner)(?:\\s+(?:19|20)[0-9]{2})?$"))return false;
        // Flattened native table headers and split value annotations are not chapter boundaries.
        if(PERFORMANCE_COLUMNS.matcher(plain).matches())return false;
        if(plain.chars().filter(c->c=='('||c=='（').count()!=plain.chars().filter(c->c==')'||c=='）').count())return false;
        if(plain.matches("[+-]?[0-9]+(?:\\.[0-9]+)?\\s*[%％‰]"))return false;
        if(plain.matches("[0-9]+(?:\\.[0-9]+)?(?:\\s*[、,，]\\s*[0-9]+(?:\\.[0-9]+)?)+"))return false;
        if(plain.matches("(?i)^[0-9]+(?:\\.[0-9]+)?\\s*(?:mm|cm|m|㎡|m²|m³|毫米|厘米|米)(?:\\s|以内|以上|以下|至|[、,，*×]).*"))return false;
        if(plain.matches("(?i)^[0-9]+(?:\\.[0-9]+)?\\s*[*×x]\\s*[0-9].*"))return false;
        if(plain.matches("(?i)^[0-9]+(?:\\.[0-9]+)?(?:\\s*[、,，]\\s*[0-9]+(?:\\.[0-9]+)?)+\\s*(?:吋|寸|英寸|毫米|厘米|米|mm|cm|m|各|共).*"))return false;
        if(plain.matches("(?:人民币|[￥¥])?\\s*[0-9,]+(?:\\.[0-9]+)?\\s*(?:亿元|万元|元|万)?"))return false;
        if(plain.matches("(?:19|20)[0-9]{2}\\s*(?:年|[./-])\\s*[0-9]{1,2}\\s*(?:月|[./-])\\s*[0-9]{1,2}\\s*日?"))return false;
        if(plain.matches(".*[0-9]+[、,][0-9]+[、,][0-9]+.*"))return false;
        return !text.isBlank()&&text.length()<=100&&!text.matches(".*[:：=|｜\\t<>。；;，,].*");
    }
    private static int headingLevel(String text) {
        Matcher markdown=Pattern.compile("^(#{1,6})\\s+.+").matcher(text);if(markdown.matches())return markdown.group(1).length();
        if(text.matches("^第[一二三四五六七八九十百0-9]+[章节篇].*"))return text.contains("节")?2:1;
        if(text.matches("^[一二三四五六七八九十百]+[、．.].*"))return 2;
        if(text.matches("^[（(][一二三四五六七八九十百0-9]+[）)].*"))return 4;
        Matcher numbered=Pattern.compile("^(\\d+(?:\\.\\d+)+)\\s*[^0-9\\s.].*").matcher(text);
        if(numbered.matches())return 2+(int)numbered.group(1).chars().filter(c->c=='.').count();
        if(text.matches("^\\d+[.、．]\\s*\\S+.*"))return 2;
        return 0;
    }
    private static String lots(String text){Set<String> values=new LinkedHashSet<>();Matcher m=LOT.matcher(text);while(m.find())values.add("LOT-"+number(m.group(1)!=null?m.group(1):m.group(2)));return values.size()>1?"UNRESOLVED":values.stream().findFirst().orElse("");}
    private static String number(String text){if(text.matches("[0-9]+"))try{return Integer.toString(Integer.parseInt(text));}catch(NumberFormatException ignored){return text;}int number=0,digit=0;for(char c:text.toCharArray()){int n="零一二三四五六七八九".indexOf(c);if(n>=0)digit=n;else if(c=='十'){number+=(digit==0?1:digit)*10;digit=0;}else if(c=='百'){number+=(digit==0?1:digit)*100;digit=0;}else return text;}return Integer.toString(number+digit);}
}
