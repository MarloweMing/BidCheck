package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Server-derived document scope. A pronoun in a copied document never establishes the current project. */
final class DocumentSubjectScope {
    private static final Pattern LABEL=Pattern.compile("(?:项目名称|工程名称)\\s*[:：]\\s*([^\\n|;；。]{6,200})");
    private static final Pattern LETTER=Pattern.compile("(?:关于贵公司|参加贵公司|参加贵单位)\\s*([^\\n。；]{8,220}?)的(?:招标|采购)文件");
    private static final Pattern HISTORY=Pattern.compile("(?m)^[\\t ]*(?:[一二三四五六七八九十0-9.、（）()\\s]*)(?:主要工作经历|工作经历|工作履历|参加过的类似项目|历史项目|已完成项目|类似项目业绩|合同协议书)");
    private static final Pattern NAME=Pattern.compile("姓\\s*名\\s*[:：]?\\s*([\\p{IsHan}·]{2,12})(?=\\s|[|<]|$)");
    private static final Pattern CURRENT=Pattern.compile("本项目|本次投标|本次招标|拟派|拟投入|本工程|联合体协议书");
    record Context(String status,String projectName,String anchor,List<String> appointmentBlocks) {
        Context { appointmentBlocks=List.copyOf(appointmentBlocks); }
        static Context unspecified(){return new Context("UNSPECIFIED","","",List.of());}
        Map<String,Object> audit(){return linked("status",status,"projectName",projectName,"anchor",anchor,"appointmentBlockCount",appointmentBlocks.size(),"derivedBy","SERVER_DOCUMENT_SCOPE");}
        boolean blocked(){return Set.of("OTHER_PROJECT","HISTORICAL_DOCUMENT","UNRESOLVED_DOCUMENT").contains(status);}
    }
    private record Range(int start,int end) {}
    private record PageScope(String status,String project,String anchor,List<Range> ranges) {}
    private final Map<String,PageScope> pages=new HashMap<>();

    DocumentSubjectScope(Document document) {
        String current=coverProject(document),state="",anchor="",named="";int age=0;Map<Integer,Set<String>> appointments=appointmentNavigation(document);
        for(Page page:document.pages()){
            String text=Objects.toString(page.text(),"");String prefix=beforeHistory(text);
            String explicit=outerProject(prefix);boolean foreign=!explicit.isBlank()&&!current.isBlank()&&!sameProject(current,explicit);
            // Only a named current project can reset a foreign subdocument. Generic 本项目 or a form heading cannot.
            if(!explicit.isBlank()){
                state=foreign?"OTHER_PROJECT":current.isBlank()?"UNRESOLVED_DOCUMENT":"CURRENT_PROJECT";
                named=explicit;anchor=page.locationType()+":"+page.number()+":outer-project";age=0;
            }else if(!current.isBlank()&&currentForm(page,text,appointments)){
                state="CURRENT_BID_SECTION";named=current;anchor=page.locationType()+":"+page.number()+":cover-navigation-section-form-printed-page";age=0;
            }else if(historyHeading(text)){
                state="HISTORICAL_DOCUMENT";named="";anchor=page.locationType()+":"+page.number()+":history-heading";age=0;
            }else if(Set.of("OTHER_PROJECT","HISTORICAL_DOCUMENT","UNRESOLVED_DOCUMENT").contains(state)){
                // Retain the fact that scope has changed, but stop asserting a particular project after two pages.
                // A stale context is unresolved, not silently restored to the outer bid document.
                if(++age>2)state="UNRESOLVED_DOCUMENT";
            }else if(Set.of("CURRENT_PROJECT","CURRENT_BID_SECTION").contains(state)){
                state="CURRENT_DOCUMENT";
            }
            String status=state.isBlank()?(current.isBlank()?"UNSPECIFIED":"CURRENT_DOCUMENT"):state;
            pages.put(key(page),new PageScope(status,named.isBlank()?current:named,anchor,appointmentRanges(text)));
        }
    }
    Context context(Page page,int start,int end){
        PageScope scope=pages.get(key(page));if(scope==null)return Context.unspecified();String text=Objects.toString(page.text(),"");List<String> blocks=new ArrayList<>();
        for(Range range:scope.ranges()){int a=Math.max(start,range.start()),b=Math.min(end,range.end());if(a<b)blocks.add(text.substring(a,b));}
        return new Context(scope.status(),scope.project(),scope.anchor(),blocks);
    }
    private static String key(Page p){return p.locationType()+":"+p.number();}
    private static Map<Integer,Set<String>> appointmentNavigation(Document document){
        Map<Integer,Set<String>> out=new HashMap<>();Pattern entry=Pattern.compile("(?m)^[\\t ]*\\d+[.、]\\s*(项目负责人|施工部分负责人|设计部分负责人|施工企业主要负责人|专职安全员)\\s*[.．…·]{3,}\\s*(\\d+)\\s*$");
        for(Page page:document.pages().stream().limit(10).toList()){
            String text=Objects.toString(page.text(),"");if(!text.contains("目录")&&text.lines().filter(s->s.matches(".*\\.{3,}.*")).count()<2)continue;
            Matcher m=entry.matcher(text);while(m.find())out.computeIfAbsent(Integer.parseInt(m.group(2)),x->new HashSet<>()).add(m.group(1));
        }return out;
    }
    private static boolean currentForm(Page page,String text,Map<Integer,Set<String>> navigation){
        if(!"PAGE".equals(page.locationType())||!text.contains("拟在本合同任职"))return false;
        // A navigation page number is not a physical page number. Require the original footer to agree,
        // plus the numbered bid section heading and the matching appointment form. A copied bare form
        // or a reset page number inside a historical attachment cannot satisfy this boundary.
        List<String> lines=text.lines().map(String::strip).filter(s->!s.isBlank()).toList();
        if(lines.isEmpty()||!lines.get(lines.size()-1).equals(Integer.toString(page.number())))return false;
        String prefix=beforeHistory(text);for(String role:navigation.getOrDefault(page.number(),Set.of()))
            if(prefix.substring(0,Math.min(180,prefix.length())).matches("(?s).*(?:^|\\n)\\d+[.、]"+Pattern.quote(role)+"\\s*\\n.*")
                &&prefix.matches("(?s).*附表[^\\n]{0,15}"+Pattern.quote(role)+"情况表.*")&&prefix.matches("(?s).*拟在本合同任职\\s*"+Pattern.quote(role)+".*"))return true;
        return false;
    }
    private static String beforeHistory(String text){Matcher m=HISTORY.matcher(text);return m.find()?text.substring(0,m.start()):text;}
    private static boolean historyHeading(String text){String stripped=text.replaceFirst("(?s)^\\s*\\d+\\s*(?:【PDF识别文字】)?\\s*","");Matcher m=HISTORY.matcher(stripped);return m.find()&&m.start()<120&&!stripped.substring(0,m.start()).contains("拟在本合同任职");}
    private static List<Range> appointmentRanges(String text){
        // Work history is a distinct region even when the current appointment is in the same source chunk.
        Matcher history=HISTORY.matcher(text);int end=history.find()?history.start():text.length();
        return end>0?List.of(new Range(0,end)):List.of();
    }
    private static String outerProject(String text){
        Matcher letter=LETTER.matcher(text);if(letter.find())return normalizeProject(letter.group(1));
        Matcher label=LABEL.matcher(text);if(label.find())return normalizeProject(label.group(1));
        // An actual consortium agreement names the tender it joins. This exact project name,
        // unlike the pronoun 本项目, can close a preceding historical attachment's scope.
        String prefix=text.stripLeading();
        if(prefix.matches("(?s)^(?:[0-9一二三四五六七八九十.、\\s]*)联合体协议书\\s*.*")&&!historyHeading(prefix)){
            Matcher joint=Pattern.compile("共同投标\\s*([^。；]{8,220}?)(?:/标段|[。；])").matcher(prefix);
            if(joint.find())return normalizeProject(joint.group(1));
        }
        if(text.contains("投标文件")&&!text.matches("(?s).*\\.{3,}.*"))for(String line:text.split("\\n")){
            String value=line.strip();if(value.matches("[（(].{8,200}(?:工程|项目)[）)]"))return normalizeProject(value);
        }return "";
    }
    private static String coverProject(Document document){
        for(Page page:document.pages().stream().limit(3).toList()){
            String text=Objects.toString(page.text(),"");if(!text.contains("投标文件")||text.matches("(?s).*\\.{3,}.*"))continue;
            String named=outerProject(text);if(!named.isBlank())return named;
            for(String line:text.split("\\n")){String s=line.strip();if(s.matches("[（(].{8,200}(?:工程|项目)[）)]"))return normalizeProject(s);}
        }return "";
    }
    private static String normalizeProject(String text){return clean(text).replaceAll("^[（(]|[）)]$","").replaceFirst("^招标项目名称","");}
    private static boolean sameProject(String a,String b){return normalizeProject(a).equals(normalizeProject(b));}

    static boolean appointment(Source source,Fact fact){
        if(source.index()||source.scopeContext().blocked())return false;
        // A page-level historical flag cannot override a server-confirmed current form; unspecified legacy
        // sources retain the old conservative behavior. Scope never comes from model attributes.
        if(source.historical()&&!Set.of("CURRENT_PROJECT","CURRENT_BID_SECTION").contains(source.scopeContext().status()))return false;
        String role=DomainBindings.sourceRole(fact),person=fact.kind().equals("PERSON_ROLE")?fact.value():DomainBindings.roleTarget(fact,role);
        if(role.isBlank()||person.isBlank())return false;
        for(String block:localBlocks(source))if(boundAppointment(block,role,person,fact.field()))return true;
        return false;
    }
    static List<Fact> nativeAppointments(Source source,String requestedRole){
        List<Fact> facts=new ArrayList<>();if(source.index()||source.scopeContext().blocked())return facts;
        for(String block:localBlocks(source)){
            String text=block.replaceAll("(?i)<[^>]+>"," ");Matcher names=NAME.matcher(text);
            while(names.find())for(String role:PersonnelRoles.labels(requestedRole)){
                String person=names.group(1);Fact fact=new Fact("N"+identity(source.id()+role+person+"appointment-form-v1").substring(0,20),"PERSON_ROLE",role,"姓名",person,person,"","","CURRENT_PROJECT",List.of(source.id()),Map.of("documentScope",source.scopeContext().audit(),"bindingMethod","EXPLICIT_CURRENT_APPOINTMENT_FIELD"));
                if(appointment(source,fact)&&new ProofVerifier().issue(fact,Map.of(source.id(),source))==null)facts.add(fact);
            }
        }return facts.stream().distinct().toList();
    }
    static List<String> localBlocks(Source source){
        String quote=source.evidence().quote();Context context=source.scopeContext();
        if(context.status().equals("UNSPECIFIED")&&context.appointmentBlocks().isEmpty())return List.of(beforeHistory(quote));
        List<String> blocks=new ArrayList<>();for(String block:context.appointmentBlocks()){
            // Cached processing facts may cite a narrower original excerpt. Never widen that excerpt.
            if(quote.contains(block))blocks.add(block);else if(block.contains(quote))blocks.add(quote);
        }return blocks;
    }
    private static boolean boundAppointment(String text,String role,String person,String field){
        if(text.matches("(?s).*(?:填写示例|示范文本|仅供参考).*"))return false;
        String plain=text.replaceAll("(?i)<[^>]+>"," ");
        for(String line:plain.split("[。；;\\n]"))if(ProofVerifier.contains(line,role)&&ProofVerifier.contains(line,person)
            &&line.matches(".*(?:不担任|不再担任|未担任|不得担任|不是|并非|不作为|非本项目|非本次).*"))return false;
        // A single-person form binds its 姓名 field to its current appointment field before any work history.
        Matcher names=NAME.matcher(plain);while(names.find()){
            String found=names.group(1);int start=names.start(),end=plain.length();Matcher next=NAME.matcher(plain);if(next.find(names.end()))end=next.start();
            String record=plain.substring(start,end);if(found.equals(person)&&record.matches("(?s).*拟在本合同任职\\s*[:：]?\\s*"+Pattern.quote(role)+"(?:\\s|[|<。；;]|$).*"))return true;
        }
        // For a roster, the current-project header governs rows, but each row must bind the two endpoints.
        if(CURRENT.matcher(clean(plain)).find()&&text.contains("<tr")){
            Matcher rows=Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(text);while(rows.find()){
                String row=rows.group(1).replaceAll("<[^>]+>"," ");if(ProofVerifier.contains(row,role)&&ProofVerifier.contains(row,person)&&row.length()<700)return true;
            }
        }
        for(String sentence:plain.split("[。；;\\n]"))if(CURRENT.matcher(sentence).find()&&ProofVerifier.contains(sentence,role)&&ProofVerifier.contains(sentence,person)){
            if(Set.of(":","：").contains(field)&&!clean(sentence).contains(clean(role)+field+clean(person)))continue;
            return true;
        }
        if(plain.matches("(?s)^\\s*(?:本项目人员配置|本项目人员安排|拟投入本项目人员)[^\\n]*\\n.*"))
            for(String line:plain.split("\\n"))if(clean(line).contains(clean(role)+"："+clean(person))||clean(line).contains(clean(role)+":"+clean(person)))return true;
        if(!PersonnelRoles.personRole(role)&&plain.matches("(?s)^\\s*本项目(?:授权书|联合体协议书|投标函|投标文件|基本情况表)[^\\n]*\\n.*")){
            String company=Pattern.quote(clean(person)),companyRole=Pattern.quote(clean(role));
            Pattern relation=Pattern.compile(companyRole+"[:：]"+company+"(?=$|[，,。；;])|"+company+"(?:角色|身份|作为|担任)(?:为|是)?"+companyRole+"(?=$|[，,。；;])");
            for(String line:plain.split("\\n"))if(relation.matcher(clean(line)).find())return true;
        }
        // A separately headed consortium agreement can bind member roles, but cannot establish employment.
        return !PersonnelRoles.personRole(role)&&plain.contains("联合体协议书")&&ProofVerifier.near(plain,role,person);
    }
}
