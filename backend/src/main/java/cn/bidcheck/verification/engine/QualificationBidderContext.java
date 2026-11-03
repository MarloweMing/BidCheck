package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A repeated neutral tax clause may inherit only a source-proven bidder qualification parent. */
final class QualificationBidderContext {
    private static final Pattern VAT=Pattern.compile("(?:本项目须)?提供增值税专用发票。提供一般纳税人证明文件或可开具增值税专用发票承诺书[（(]格式可参考第[一二三四五六七八九十0-9]+章中《增值税专用发票承诺书》[）)][。；;]?");
    private static final Pattern PARENT=Pattern.compile("^[★*\\s]*(?:[0-9]+[、.．]\\s*)?(投标人)资格要求\\s*[:：]?$");
    private static final Set<String> SUBHEADINGS=cn.bidcheck.compat.Java8Api.setOf("资质要求", "人员要求", "注册要求", "经营状况", "信誉要求", "其他要求");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Binding {
        private final String heading;
        private final List<Map<String,Object>> sources;
        @com.fasterxml.jackson.annotation.JsonCreator
        Binding(@com.fasterxml.jackson.annotation.JsonProperty("heading") String heading, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<Map<String,Object>> sources) { this.heading = heading; this.sources = sources; }
        public String heading() { return heading; }
        public List<Map<String,Object>> sources() { return sources; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Binding that = (Binding) other; return java.util.Objects.equals(this.heading, that.heading) && java.util.Objects.equals(this.sources, that.sources); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.heading); hash = 31 * hash + java.util.Objects.hashCode(this.sources); return hash; }
        @Override public String toString() { return "Binding[" + "heading=" + heading + ", sources=" + sources + "]"; }
}
    private QualificationBidderContext() {}

    static boolean applies(Checkpoint cp,String original){RequirementScope.Selection scope=RequirementScope.resolve(cp,original);return scope.resolved()&&neutralSource(scope.obligation());}
    static boolean neutralSource(String source){return !cn.bidcheck.compat.Java8Api.isBlank(neutralClause(source));}

    static Binding resolve(Checkpoint cp,Document doc,int current,String original){
        RequirementScope.Selection scope=RequirementScope.resolve(cp,original);
        if(!scope.resolved())return null;
        String clause=neutralClause(scope.obligation());if(cn.bidcheck.compat.Java8Api.isBlank(clause))return null;
        List<Binding> candidates=new ArrayList<>();List<Integer> locations=new ArrayList<>();int exactParagraphs=0;
        for(int i=0;i<doc.pages().size();i++){
            Page body=doc.pages().get(i);
            // Whole paragraphs only: a matching substring in another actor's clause is not an antecedent.
            if(body.text().contains("\t")||!clause.equals(neutralClause(body.text())))continue;
            exactParagraphs++;
            Binding binding=direct(doc,i);if(binding!=null){candidates.add(binding);locations.add(i);}
        }
        if(candidates.size()!=1||exactParagraphs!=1)return null;
        Binding parent=candidates.get(0);
        if(locations.get(0)==current)return parent;
        int first=Math.min(locations.get(0),current),last=Math.max(locations.get(0),current);
        for(int i=first+1;i<last;i++)if(cn.bidcheck.compat.Java8Extras.lines(doc.pages().get(i).text()).anyMatch(QualificationBidderContext::scopeBoundary))return null;
        // A duplicated requirement is not generally transferable between sections. This narrow
        // path requires an actual qualification-assessment row, under the same frozen table header.
        Map<String,Object> table=qualificationRow(doc,current);
        if(table.isEmpty())return null;
        List<Map<String,Object>> sources=new ArrayList<>(parent.sources());sources.add(table);
        return new Binding(parent.heading(),cn.bidcheck.compat.Java8Api.listCopyOf(sources));
    }

    private static Binding direct(Document doc,int at){
        List<Page> pages=doc.pages();
        if(at<2||!plain(pages.get(at-1).text()).equals("其他要求"))return null;
        for(int i=at-2;i>=Math.max(0,at-40);i--){
            String raw=pages.get(i).text(),text=cn.bidcheck.compat.Java8Api.strip(raw);Matcher parent=PARENT.matcher(text);
            if(parent.matches()){
                Map<String,Object> heading=source(doc,pages.get(i),"QUALIFICATION_SECTION_SUBJECT");heading.put("subject",parent.group(1));
                return new Binding(text,cn.bidcheck.compat.Java8Api.listOf(heading, source(doc,pages.get(at-1),"QUALIFICATION_SUBSECTION"), source(doc,pages.get(at),"EXACT_QUALIFICATION_ANTECEDENT")));
            }
            // Nested numbered certificate/person requirements may contain other actors; they
            // do not change the parent. A standalone new section, lot or example does.
            if(text.matches("(?s).*(?:第[^\\s]{1,12}标段|历史项目|历史业绩|填写示例|参考示例).*"))return null;
            String normalized=plain(text);
            if(text.matches("[^。；;，,\\t\\r\\n]{1,40}")&&!SUBHEADINGS.contains(normalized))return null;
        }
        return null;
    }

    private static Map<String,Object> qualificationRow(Document doc,int at){
        List<Page> pages=doc.pages();Page current=pages.get(at);
        if(!current.text().startsWith("其他要求\t"))return cn.bidcheck.compat.Java8Api.mapOf();
        int qualification=-1;
        for(int i=at-1;i>=Math.max(0,at-30);i--){String text=pages.get(i).text();
            if(!text.contains("\t"))return cn.bidcheck.compat.Java8Api.mapOf();String first=cn.bidcheck.compat.Java8Api.strip(text.substring(0,text.indexOf('\t')));
            if(first.equals("资格评审")){qualification=i;break;}
            if(first.matches(".*(?:形式评审|响应评审|详细评审).*"))return cn.bidcheck.compat.Java8Api.mapOf();
        }
        if(qualification<0)return cn.bidcheck.compat.Java8Api.mapOf();
        for(int i=qualification-1;i>=Math.max(0,qualification-30);i--){String text=pages.get(i).text();
            if(text.equals("条款号\t评审内容\t评审因素\t评审标准")){
                Map<String,Object> row=source(doc,pages.get(qualification),"QUALIFICATION_ASSESSMENT_ROW");row.put("tableHeader",source(doc,pages.get(i),"QUALIFICATION_ASSESSMENT_HEADER"));return row;
            }
            if(!text.contains("\t"))return cn.bidcheck.compat.Java8Api.mapOf();
        }
        return cn.bidcheck.compat.Java8Api.mapOf();
    }
    private static String neutralClause(String text){String compact=text.replaceAll("\\s+","").replaceFirst("^其他要求[:：]?","");return VAT.matcher(compact).matches()?compact.replaceFirst("[。；;]$",""):"";}
    private static boolean scopeBoundary(String line){
        String text=cn.bidcheck.compat.Java8Api.strip(line).replaceFirst("^[#★*]+\\s*","");
        // Only a standalone section marker, not ordinary requirements referring to a lot,
        // a historical contract or the sample format, blocks cross-section inheritance.
        return text.matches("第[一二三四五六七八九十百0-9]+标段(?:\\s*[:：、－—-]\\s*[^。；;，,\\t]{1,35}|\\s*(?:投标人资格要求|资格要求|资格评审|评审标准))?")
                ||text.matches("(?:历史项目|历史业绩|参考示例|填写示例|示例|样例)(?:\\s*[:：、－—-]\\s*[^。；;，,\\t]{1,35})?");
    }
    private static String plain(String text){return cn.bidcheck.compat.Java8Api.strip(text).replaceFirst("[:：]$","");}
    private static Map<String,Object> source(Document doc,Page page,String kind){return linked("kind",kind,"documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",doc.version(),"locationType",page.locationType(),"location",page.number(),"start",0,"end",page.text().length(),"sourceQuote",page.text());}
}
