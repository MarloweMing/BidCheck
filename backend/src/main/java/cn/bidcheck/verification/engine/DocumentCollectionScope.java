package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.clean;

/** Narrow, server-derived scope proof for a bounded commitment collection, never a personnel relation. */
final class DocumentCollectionScope {
    private record Navigation(String label,String title,int page,Page source,String quote,boolean root) { }
    private record Proof(int first,int last,String project,String projectNumber,List<Map<String,Object>> anchors) { }
    private final Map<Integer,Proof> approved=new HashMap<>();
    private static final Pattern NAVIGATION=Pattern.compile("^((?:[0-9]+(?:\\.[0-9]+)*[.、]?|[（(][0-9]+[）)]))\\s*([^\\n]{2,65}?)\\s*[.．…·]{3,}\\s*([0-9]+)\\s*$");
    private static final Pattern HISTORICAL=Pattern.compile("(?m)^\\s*(?:[0-9.、（）()一二三四五六七八九十]*\\s*)?(?:历史项目|类似项目业绩|已完成项目|工作经历|工作履历|主要工作经历|合同协议书|填写示例|填写样例|仅供参考|示范文本)(?:\\s|[:：]|$)");
    private static final Pattern PROJECT_LABEL=Pattern.compile("(?:项目名称|工程名称)[:：]([^\\n。；|]{6,200})");

    DocumentCollectionScope(Document document){
        if(document==null||!"BID".equals(document.role())||document.pages()==null)return;
        String project=coverProject(document),number=coverNumber(document),bidder=coverBidder(document);
        if(project.isBlank()||number.isBlank()||bidder.isBlank())return;
        Map<Integer,Page> pages=new HashMap<>();Set<Integer> duplicate=new HashSet<>();
        for(Page p:document.pages())if("PAGE".equals(p.locationType())&&pages.putIfAbsent(p.number(),p)!=null)duplicate.add(p.number());
        List<Navigation> nav=navigation(document);
        for(Navigation entry:nav){
            if(!entry.root()||!entry.title().equals("承诺书"))continue;
            Page first=pages.get(entry.page());if(first==null||duplicate.contains(first.number())||!mapped(entry,first))continue;
            int end=nav.stream().filter(n->n.root()&&n.page()>entry.page()).mapToInt(Navigation::page).min().orElse(entry.page()+21);
            // Unbounded inheritance is prohibited. A second independently mapped same-level section is required.
            Navigation next=nav.stream().filter(n->n.root()&&n.page()==end).findFirst().orElse(null);
            if(next==null||end-entry.page()>24||!mapped(next,pages.get(end)))continue;
            List<Navigation> children=nav.stream().filter(n->!n.root()&&n.page()>=entry.page()&&n.page()<end&&n.title().contains("承诺")).toList();
            if(children.size()<2||children.stream().anyMatch(n->!mapped(n,pages.get(n.page()))))continue;
            List<Map<String,Object>> anchors=new ArrayList<>();anchors.add(anchor(document.pages().get(0),"COVER_PROJECT_NUMBER_BIDDER",project+" / "+number+" / "+bidder));
            anchors.add(anchor(entry.source(),"NAVIGATION",entry.quote()));anchors.add(anchor(first,"SECTION_BODY",first.text().lines().limit(3).reduce("",(a,b)->a+"\n"+b)));
            Page declaration=null;
            for(int p=entry.page()-3;p<entry.page();p++){
                Page candidate=pages.get(p);if(candidate==null||!printedMatches(candidate)||unsafe(candidate.text(),project,number))continue;
                Navigation preceding=nav.stream().filter(n->n.root()&&n.page()==candidate.number()).findFirst().orElse(null);
                String body=clean(candidate.text());
                if(preceding!=null&&mapped(preceding,candidate)&&body.contains("我方参加"+project+"的投标")){declaration=candidate;break;}
            }
            if(declaration==null)continue;
            anchors.add(anchor(declaration,"CURRENT_BID_DECLARATION",project));
            Page corroboration=null;int signed=0;boolean invalid=false;
            for(int p=entry.page();p<end;p++){
                Page page=pages.get(p);if(page==null||duplicate.contains(p)||!printedMatches(page)||unsafe(page.text(),project,number)){invalid=true;break;}
                String body=clean(page.text());
                if(body.matches("(?s).*(?:根据|按照)[（(]"+Pattern.quote(project)+"[）)].*")&&body.contains("招标项目编号")&&body.contains(number))corroboration=page;
                if(body.contains("投标人(盖章):"+bidder)||body.contains("投标人（盖章）："+bidder)||body.contains("投标人（盖章）:"+bidder)||body.contains("投标人(盖章)："+bidder))signed++;
                // Children define the allowed document intervals; a continuation cannot silently grow forever.
                int childStart=children.stream().filter(n->n.page()<=page.number()).mapToInt(Navigation::page).max().orElse(-1);
                if(childStart<0||page.number()-childStart>3){invalid=true;break;}
            }
            if(invalid||corroboration==null||signed<2)continue;
            anchors.add(anchor(corroboration,"PROJECT_AND_NUMBER_CORROBORATION",project+" / "+number));
            anchors.add(anchor(pages.get(end),"NEXT_SECTION_BOUNDARY",next.label()+next.title()));
            for(Navigation child:children)anchors.add(anchor(pages.get(child.page()),"MAPPED_CHILD_TITLE",child.label()+child.title()));
            Proof proof=new Proof(entry.page(),end-1,project,number,List.copyOf(anchors));
            for(int p=entry.page();p<end;p++)approved.put(p,proof);
        }
    }

    Map<String,Object> currentEvidence(Page page,String quote){
        Proof proof=approved.get(page.number());if(proof==null||!"PAGE".equals(page.locationType()))return Map.of();
        // A result is only a document-range claim. It cannot bind staff to an employer, role or consortium.
        return Map.of("status","CURRENT_BID_SECTION","projectName",proof.project(),"projectNumber",proof.projectNumber(),
            "sectionStart",proof.first(),"sectionEnd",proof.last(),"anchors",proof.anchors(),"derivedBy","SERVER_COLLECTION_SECTION_SCOPE",
            "boundary","仅证明当前承诺文书范围；不证明签章真实性、承诺是否履行或人员任职关系");
    }
    private static List<Navigation> navigation(Document doc){
        List<Navigation> result=new ArrayList<>();
        for(Page page:doc.pages().stream().limit(10).toList()){
            String text=Objects.toString(page.text(),"");if(!text.contains("目录")&&text.lines().filter(line->line.matches(".*[.．…·]{3,}.*")).count()<2)continue;
            for(String line:text.split("\\R")){Matcher m=NAVIGATION.matcher(line.strip());if(m.matches()){
                String label=clean(m.group(1)),title=title(m.group(2));boolean root=label.matches("[0-9]+[.、]?");
                result.add(new Navigation(label,title,Integer.parseInt(m.group(3)),page,line,root));
            }}
        }return result;
    }
    private static boolean mapped(Navigation nav,Page page){
        if(page==null||!printedMatches(page))return false;
        String expected=title(nav.label()+nav.title());
        return page.text().lines().limit(6).map(DocumentCollectionScope::title).anyMatch(expected::equals);
    }
    private static boolean printedMatches(Page page){
        List<String> lines=Objects.toString(page.text(),"").lines().map(String::strip).filter(s->!s.isBlank()).toList();
        return !lines.isEmpty()&&lines.get(lines.size()-1).equals(Integer.toString(page.number()));
    }
    private static boolean unsafe(String value,String project,String number){
        String text=Objects.toString(value,"");if(HISTORICAL.matcher(text).find())return true;
        Matcher labels=PROJECT_LABEL.matcher(text);while(labels.find())if(!normalizeProject(labels.group(1)).equals(project))return true;
        Matcher letters=Pattern.compile("(?:关于贵公司|参加贵公司|参加贵单位)\\s*([^\\n。；]{8,220}?)的(?:招标|采购)文件").matcher(text);
        while(letters.find())if(!normalizeProject(letters.group(1)).equals(project))return true;
        Matcher ids=Pattern.compile("招标项目编号[:：][（(]?([A-Za-z0-9-]+)").matcher(clean(text));while(ids.find())if(!ids.group(1).equals(number))return true;
        return false;
    }
    private static String coverProject(Document doc){
        for(Page page:doc.pages().stream().limit(3).toList()){
            String text=Objects.toString(page.text(),"");if(!text.contains("投标文件")||text.matches("(?s).*[.．…·]{3,}.*"))continue;
            for(String line:text.split("\\R"))if(line.strip().matches("[（(].{8,200}(?:工程|项目)[）)]"))return normalizeProject(line);
        }return "";
    }
    private static String coverNumber(Document doc){for(Page page:doc.pages().stream().limit(3).toList())if(Objects.toString(page.text(),"").contains("投标文件")){
        Matcher m=Pattern.compile("招标项目编号[:：][（(]?([A-Za-z0-9-]+)").matcher(clean(page.text()));if(m.find())return m.group(1);
    }return "";}
    private static String coverBidder(Document doc){for(Page page:doc.pages().stream().limit(3).toList())if(Objects.toString(page.text(),"").contains("投标文件")){
        Matcher m=Pattern.compile("(?m)^投标人名称[:：][（(]盖章[）)]([^\\n]+)$").matcher(page.text());if(m.find())return clean(m.group(1));
    }return "";}
    private static String normalizeProject(String text){return clean(text).replaceAll("^[（(]|[）)]$","");}
    private static String title(String text){return clean(text).replace("★","").replace("（","(").replace("）",")");}
    private static Map<String,Object> anchor(Page page,String kind,String quote){return Map.of("page",page.number(),"locationType",page.locationType(),"kind",kind,"quote",quote);}
}
