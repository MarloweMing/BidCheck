package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.clean;

/** A fully bounded, source-mapped single commitment. It never resets personnel or following-page scope. */
final class StandaloneCommitmentScope {
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Navigation {
        private final String label;
        private final String title;
        private final int page;
        private final Page source;
        private final String quote;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Navigation(@com.fasterxml.jackson.annotation.JsonProperty("label") String label, @com.fasterxml.jackson.annotation.JsonProperty("title") String title, @com.fasterxml.jackson.annotation.JsonProperty("page") int page, @com.fasterxml.jackson.annotation.JsonProperty("source") Page source, @com.fasterxml.jackson.annotation.JsonProperty("quote") String quote) { this.label = label; this.title = title; this.page = page; this.source = source; this.quote = quote; }
        public String label() { return label; }
        public String title() { return title; }
        public int page() { return page; }
        public Page source() { return source; }
        public String quote() { return quote; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Navigation that = (Navigation) other; return java.util.Objects.equals(this.label, that.label) && java.util.Objects.equals(this.title, that.title) && java.util.Objects.equals(this.page, that.page) && java.util.Objects.equals(this.source, that.source) && java.util.Objects.equals(this.quote, that.quote); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.label); hash = 31 * hash + java.util.Objects.hashCode(this.title); hash = 31 * hash + java.util.Objects.hashCode(this.page); hash = 31 * hash + java.util.Objects.hashCode(this.source); hash = 31 * hash + java.util.Objects.hashCode(this.quote); return hash; }
        @Override public String toString() { return "Navigation[" + "label=" + label + ", title=" + title + ", page=" + page + ", source=" + source + ", quote=" + quote + "]"; }
}
    private static final Pattern NAV=Pattern.compile("^([0-9]+(?:\\.[0-9]+)*[.、]?|[（(][0-9]+[）)])\\s*([^\\n]{2,65}?)\\s*[.．…·]{3,}\\s*([0-9]+)\\s*$");
    private static final Pattern DATE=Pattern.compile("日期\\s*[:：]\\s*(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final Pattern ISSUER=Pattern.compile("(?m)^投标人[（(][^）)\\n]{0,24}[）)]\\s*[:：]\\s*([^\\n]+)$");
    private final Map<Integer,Map<String,Object>> proofs=new HashMap<>();

    StandaloneCommitmentScope(Document doc){
        if(doc==null||!"BID".equals(doc.role())||doc.pages()==null)return;
        Page cover=null;String project="",number="",bidder="";
        for(Page page:cn.bidcheck.compat.Java8Api.toList(doc.pages().stream().limit(3))){
            String text=Objects.toString(page.text(),"");if(!text.contains("投标文件")||text.matches("(?s).*[.．…·]{3,}.*"))continue;
            for(String line:text.split("\\R"))if(cn.bidcheck.compat.Java8Api.strip(line).matches("[（(].{8,200}(?:工程|项目)[）)]")){project=clean(line).replaceAll("^[（(]|[）)]$","");cover=page;break;}
            if(cover!=null){number=one(Pattern.compile("招标项目编号[:：]([A-Za-z0-9-]+)"),clean(text));bidder=one(Pattern.compile("(?m)^投标人名称[:：][（(]盖章[）)]([^\\n]+)$"),text);break;}
        }
        bidder=clean(bidder);if(cover==null||cn.bidcheck.compat.Java8Api.isBlank(project)||cn.bidcheck.compat.Java8Api.isBlank(number)||cn.bidcheck.compat.Java8Api.isBlank(bidder)||bidder.matches("(?s).*(?:填写|示例|XX|××|__).*") )return;
        Map<Integer,Page> pages=new HashMap<>();Set<Integer> duplicate=new HashSet<>();
        for(Page page:doc.pages())if("PAGE".equals(page.locationType())&&pages.putIfAbsent(page.number(),page)!=null)duplicate.add(page.number());
        List<Navigation> navigation=navigation(doc),letters=cn.bidcheck.compat.Java8Api.toList(navigation.stream().filter(n->n.label().matches("[0-9]+[.、]?")&&n.title().equals("投标函")));
        if(letters.size()!=1)return;Navigation letterNav=letters.get(0);Page letter=pages.get(letterNav.page());
        if(duplicate.contains(letterNav.page())||!mapped(letterNav,letter)||unsafe(letter.text(),project,number))return;
        String letterText=clean(letter.text()),recipient=recipient(letter.text()),date=date(letter.text());
        if(!letterText.matches("(?s).*已仔细研究了[（(]"+Pattern.quote(project)+"[）)][（(]招标编号[:：]"+Pattern.quote(number)+"[）)].*")
                ||!bidder.equals(clean(one(ISSUER,letter.text())))||cn.bidcheck.compat.Java8Api.isBlank(recipient)||cn.bidcheck.compat.Java8Api.isBlank(date))return;
        for(Navigation entry:navigation){
            boolean nested=entry.label().matches("[（(][0-9]+[）)]");
            if(!nested&&!entry.label().matches("[0-9]+(?:\\.[0-9]+)+")||!entry.title().matches(".{2,45}承诺(?:书|函)"))continue;
            Navigation parent=nested?nestedParent(entry,navigation):null;
            if(nested&&(parent==null||duplicate.contains(parent.page())||!mapped(parent,pages.get(parent.page()))||unsafeAncestor(parent,navigation)))continue;
            List<Navigation> siblings=nested?nestedSiblings(parent,navigation):navigation;
            if(siblings.stream().filter(n->n.label().equals(entry.label())||n.title().equals(entry.title())).count()!=1)continue;
            if(!nested&&unsafeAncestor(entry,navigation))continue;
            Page page=pages.get(entry.page());if(duplicate.contains(entry.page())||!mapped(entry,page)||unsafe(page.text(),project,number))continue;
            // The final child may be closed by the next parent, but its prior siblings never inherit this proof.
            if(nested&&(siblings.isEmpty()||siblings.get(siblings.size()-1)!=entry))continue;
            String boundaryLabel=nested?parent.label():entry.label();int dot=boundaryLabel.lastIndexOf('.');String nextLabel=boundaryLabel.substring(0,dot+1)+(Integer.parseInt(boundaryLabel.substring(dot+1))+1);
            List<Navigation> nextEntries=cn.bidcheck.compat.Java8Api.toList(navigation.stream().filter(n->n.label().equals(nextLabel)));
            if(nextEntries.size()!=1)continue;Navigation next=nextEntries.get(0);Page nextPage=pages.get(next.page());
            if(next.page()!=entry.page()+1||duplicate.contains(next.page())||!mapped(next,nextPage))continue;
            List<String> lines=cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(page.text()).map(cn.bidcheck.compat.Java8Api::strip).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)));
            if(lines.size()<5||!clean(lines.get(1)).equals(entry.title())||!recipient.equals(recipient(page.text()))
                    ||!date.equals(date(page.text()))||!bidder.equals(clean(one(ISSUER,page.text())))
                    ||!clean(page.text()).matches("(?s).*(?:我方承诺|我公司承诺).+"))continue;
            List<Map<String,Object>> anchors=new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(anchor(cover,"CURRENT_COVER",cover.text()), anchor(entry.source(),"UNIQUE_NUMBERED_NAVIGATION",entry.quote()), anchor(letter,"CURRENT_BID_LETTER_PROJECT_ISSUER_RECIPIENT_DATE",letter.text()), anchor(page,"COMPLETE_SINGLE_COMMITMENT",page.text()), anchor(next.source(),"NEXT_SIBLING_NAVIGATION",next.quote()), anchor(nextPage,"NEXT_SIBLING_BODY_BOUNDARY",nativeText(nextPage.text()))));
            if(nested){anchors.add(anchor(parent.source(),"UNIQUE_PARENT_NAVIGATION",parent.quote()));anchors.add(anchor(pages.get(parent.page()),"MAPPED_PARENT_BODY_BOUNDARY",nativeText(pages.get(parent.page()).text())));}
            proofs.put(page.number(),cn.bidcheck.compat.Java8Api.mapOf("status", "CURRENT_BID_SECTION", "projectName", project, "projectNumber", number, "sectionStart", page.number(), "sectionEnd", page.number(), "anchors", anchors, "derivedBy", "SERVER_SINGLE_COMMITMENT_SCOPE", "boundary", "仅证明此页当前承诺文书范围；不证明实际签章、承诺履行、联合体各方资格或人员归属"));
        }
    }
    private static Navigation nestedParent(Navigation entry,List<Navigation> navigation){
        int index=navigation.indexOf(entry);Navigation parent=null;
        for(int i=index-1;i>=0;i--)if(!navigation.get(i).label().matches("[（(][0-9]+[）)]")){parent=navigation.get(i);break;}
        if(parent==null||!parent.label().matches("[0-9]+(?:\\.[0-9]+)+")||parent.page()>entry.page()||!parent.title().contains(entry.title())
                ||parent.title().matches("(?s).*(?:历史|业绩|已完成|参考|示例|样例|模板|履历|工作经历).*"))return null;
        Navigation selected=parent;if(navigation.stream().filter(n->n.label().equals(selected.label())).count()!=1)return null;
        return parent;
    }
    private static List<Navigation> nestedSiblings(Navigation parent,List<Navigation> navigation){
        List<Navigation> result=new ArrayList<>();for(int i=navigation.indexOf(parent)+1;i<navigation.size();i++){
            Navigation value=navigation.get(i);if(!value.label().matches("[（(][0-9]+[）)]"))break;result.add(value);
        }return result;
    }
    Map<String,Object> currentEvidence(Page page,String quote){
        if(!"PAGE".equals(page.locationType())||cn.bidcheck.compat.Java8Api.isBlank(quote)||!Objects.toString(page.text(),"").contains(quote))return cn.bidcheck.compat.Java8Api.mapOf();
        return proofs.getOrDefault(page.number(),cn.bidcheck.compat.Java8Api.mapOf());
    }
    private static List<Navigation> navigation(Document doc){
        List<Navigation> out=new ArrayList<>();for(Page page:cn.bidcheck.compat.Java8Api.toList(doc.pages().stream().limit(10))){
            String text=Objects.toString(page.text(),"");if(!text.contains("目录")&&cn.bidcheck.compat.Java8Extras.lines(text).filter(s->s.matches(".*[.．…·]{3,}.*")).count()<2)continue;
            for(String line:text.split("\\R")){Matcher m=NAV.matcher(cn.bidcheck.compat.Java8Api.strip(line));if(m.matches())out.add(new Navigation(m.group(1),clean(m.group(2)),Integer.parseInt(m.group(3)),page,line));}
        }return out;
    }
    private static boolean unsafeAncestor(Navigation entry,List<Navigation> navigation){
        String label=entry.label();while(label.contains(".")){
            label=label.substring(0,label.lastIndexOf('.'));String ancestor=label;
            List<Navigation> parents=cn.bidcheck.compat.Java8Api.toList(navigation.stream().filter(n->n.label().replaceFirst("[.、]$","").equals(ancestor)));
            // A child cannot discard an explicit historical/reference parent just because its
            // own title, issuer and date look like a current commitment. Missing parents do
            // not supply a positive proof; ambiguous or adverse existing parents veto it.
            if(parents.stream().anyMatch(n->n.page()>entry.page()||n.title().matches("(?s).*(?:历史|业绩|已完成|参考|示例|样例|模板|履历|工作经历).*")))return true;
            int firstChild=navigation.stream().filter(n->n.label().matches("[0-9]+(?:\\.[0-9]+)+")&&n.label().startsWith(ancestor+".")).mapToInt(Navigation::page).min().orElse(entry.page());
            // A simple ordinal can restart inside a numbered subsection (e.g. a personnel list).
            // Only headings before the first fully qualified child can be its parent; an adverse
            // same-number heading still vetoes above regardless of this structural distinction.
            if(parents.stream().filter(n->n.page()<=firstChild).count()>1)return true;
        }return false;
    }
    private static String nativeText(String text){int marker=text.indexOf("【PDF识别文字】");return marker<0?text:text.substring(0,marker);}
    private static boolean mapped(Navigation entry,Page page){
        if(page==null)return false;List<String> lines=cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(nativeText(Objects.toString(page.text(),""))).map(cn.bidcheck.compat.Java8Api::strip).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)));
        return lines.size()>=2&&clean(lines.get(0)).equals(clean(entry.label()+entry.title()))&&lines.get(lines.size()-1).equals(Integer.toString(page.number()));
    }
    private static String one(Pattern pattern,String text){Matcher m=pattern.matcher(text);if(!m.find())return "";String value=m.group(1);return m.find()?"":value;}
    private static String recipient(String text){
        List<String> lines=cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(text).map(cn.bidcheck.compat.Java8Api::strip).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)).limit(6));List<String> names=new ArrayList<>();
        List<String> addressed=cn.bidcheck.compat.Java8Api.toList(lines.stream().filter(s->s.startsWith("致：")||s.startsWith("致:")));
        // A bare recipient belongs immediately below the two mapped document titles.
        // Do not mistake a later wrapped line ending with “招标编号：” for a second addressee.
        List<String> candidates=addressed.isEmpty()?(lines.size()>2?cn.bidcheck.compat.Java8Api.listOf(lines.get(2)):cn.bidcheck.compat.Java8Api.listOf()):addressed;
        for(String line:candidates)if(line.matches("(?:致[:：])?[（(]?[\\p{IsHan}A-Za-z0-9（）()·]{6,90}[）)]?[:：]")){
            String name=clean(line).replaceFirst("^致[:：]","").replaceAll("^[（(]|[）)][:：]$|[:：]$","");names.add(name);
        }return names.size()==1?names.get(0):"";
    }
    private static String date(String text){Matcher m=DATE.matcher(text);if(!m.find())return "";String date=m.group(1)+"-"+Integer.parseInt(m.group(2))+"-"+Integer.parseInt(m.group(3));return m.find()?"":date;}
    private static boolean unsafe(String text,String project,String number){
        if(text.matches("(?s).*(?:填写示例|填写样例|示范文本|仅供参考|非本次|不适用于本项目|不适用于本次(?:投标|招标|采购)|(?:本承诺|本文件|本文书).{0,12}仅(?:适)?用于(?:历史|其他|以往)项目).*")||Pattern.compile("(?m)^\\s*(?:[0-9.、（）()一二三四五六七八九十]*\\s*)?(?:历史项目|类似项目业绩|已完成项目|工作经历|合同协议书)(?:\\s|[:：]|$)").matcher(text).find())return true;
        Matcher named=Pattern.compile("(?:项目名称|工程名称)\\s*[:：]\\s*([^\\n。；|]{6,200})").matcher(text);
        while(named.find())if(!clean(named.group(1)).replaceAll("^[（(]|[）)]$","").equals(project))return true;
        Matcher other=Pattern.compile("(?:关于贵公司|参加贵公司|参加贵单位)\\s*([^\\n。；]{8,220}?)的(?:招标|采购)文件").matcher(text);
        while(other.find())if(!clean(other.group(1)).equals(project))return true;
        Matcher ids=Pattern.compile("招标(?:项目)?编号[:：]([A-Za-z0-9-]+)").matcher(clean(text));while(ids.find())if(!ids.group(1).equals(number))return true;
        return false;
    }
    private static Map<String,Object> anchor(Page page,String kind,String quote){return cn.bidcheck.compat.Java8Api.mapOf("page", page.number(), "locationType", page.locationType(), "kind", kind, "quote", quote);}
}
