package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Server-derived heading/row context. Original requirement text is never rewritten. */
final class RequirementSourceContext {
    static final String VERSION="requirement-source-context-2-versioned-source-spans";
    private static final Pattern ROLE_HEADING=Pattern.compile("^[\\s★*]*(?:[0-9]+(?:[.．][0-9]+)*[、.．)]?\\s*)?("+RequirementContextBindings.ROLES+")(?:资格要求|资质要求|要求)\\s*[:：]?\\s*$");
    private static final Pattern BOUNDARY=Pattern.compile("^(?:第[一二三四五六七八九十百0-9]+[章节部分]|[0-9]+(?:[.．][0-9]+)+\\s*[^0-9]|[一二三四五六七八九十]+[、.．])|.*(?:第[^\\s]{1,12}标段|历史项目|示例|如果|否则|如为|如有|如组成).*");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Context{
        private final String bindingSource;
        private final Map<String,Object> identity;
        private final List<Map<String,Object>> sources;
        @com.fasterxml.jackson.annotation.JsonCreator
        Context(@com.fasterxml.jackson.annotation.JsonProperty("bindingSource") String bindingSource, @com.fasterxml.jackson.annotation.JsonProperty("identity") Map<String,Object> identity, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<Map<String,Object>> sources) { this.bindingSource = bindingSource; this.identity = identity; this.sources = sources; }
        public String bindingSource() { return bindingSource; }
        public Map<String,Object> identity() { return identity; }
        public List<Map<String,Object>> sources() { return sources; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Context that = (Context) other; return java.util.Objects.equals(this.bindingSource, that.bindingSource) && java.util.Objects.equals(this.identity, that.identity) && java.util.Objects.equals(this.sources, that.sources); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.bindingSource); hash = 31 * hash + java.util.Objects.hashCode(this.identity); hash = 31 * hash + java.util.Objects.hashCode(this.sources); return hash; }
        @Override public String toString() { return "Context[" + "bindingSource=" + bindingSource + ", identity=" + identity + ", sources=" + sources + "]"; }

        static Context empty(String source){return new Context(source,cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.listOf());}
        Map<String,Object> map(){return linked("version",VERSION,"identity",identity,"sources",sources,
                "sourceMappingVersion",RequirementSourceRepair.VERSION,"sourceSpanPolicy","EACH_LOCATION_VERBATIM_NO_CROSS_BLOCK_QUOTES");}
    }
    static Context resolve(Checkpoint cp,String original,Map<String,Object> context){
        if(cp.source()==null||cn.bidcheck.compat.Java8Api.isBlank(original)||!original.equals(cp.source().quote()))return Context.empty(original);
        List<Document> docs=new ArrayList<>();Object input=context.get("tenderDocuments");
        if(input instanceof List<?>)for(Object item:((List<?>) (input)))if(item instanceof Document)docs.add(((Document) (item)));
        List<Document> matching=cn.bidcheck.compat.Java8Api.toList(docs.stream().filter(d->d.id().equals(cp.source().documentId())&&"TENDER".equals(d.role()))
            .filter(d->cn.bidcheck.compat.Java8Api.isBlank(str(context,"requirementProjectId"))||d.projectId().equals(str(context,"requirementProjectId"))));
        if(matching.size()!=1)return Context.empty(original);Document doc=matching.get(0);
        List<Page> pages=doc.pages();int at=-1;for(int i=0;i<pages.size();i++)if(pages.get(i).number()==cp.source().page()&&pages.get(i).locationType().equals(cp.source().locationType())){if(at>=0)return Context.empty(original);at=i;}
        if(at<0)return Context.empty(original);Page page=pages.get(at);int offset=RequirementContextBindings.uniqueOffset(page.text(),original);
        if(offset<0)return Context.empty(original);
        Map<String,Object> identity=linked("projectId",doc.projectId(),"documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",doc.version(),"locationType",page.locationType(),"location",page.number(),"start",offset,"end",offset+original.length(),"sourceHash",VerificationTypes.identity(original));
        if("scoring".equals(cp.field())){
            identity.put("scoringSourceVersion",cn.bidcheck.engine.ScoringSourceSegments.VERSION);
            List<String> repeated=cn.bidcheck.engine.ScoringSourceSegments.repeatedPlanItems(original);
            if(!repeated.isEmpty())identity.put("sourceWarnings",cn.bidcheck.compat.Java8Api.listOf(linked("code","REPEATED_SOURCE_LIST_ITEMS","items",repeated,
                    "message","招标原文清单存在重复项，保留原文及声明数量，不自动删项或重新解释评分总数")));
        }
        List<Map<String,Object>> sources=new ArrayList<>();sources.add(source(doc,page,original,offset,"OBLIGATION"));
        String heading="";Map<String,Object> inherited=null;
        // A table's subject is inherited only from its own row under an explicit subject column.
        RowSubject rowSubject=tableSubject(page.text(),offset,original);
        if(rowSubject!=null){
            String line=lineAt(page.text(),offset);inherited=source(doc,page,line,page.text().indexOf(line),"TABLE_ROW_SUBJECT");
            inherited.put("subject",rowSubject.subject());inherited.put("tableHeader",source(doc,page,rowSubject.header(),rowSubject.headerStart(),"TABLE_HEADER"));heading=rowSubject.subject();
        }else{
            List<Map<String,Object>> lines=new ArrayList<>();
            for(int i=0;i<=at;i++){Page p=pages.get(i);int limit=i==at?offset:p.text().length();Matcher matcher=Pattern.compile("[^\\r\\n]+").matcher(p.text().substring(0,limit));
                while(matcher.find())lines.add(linked("page",p,"text",matcher.group(),"offset",matcher.start()));}
            for(int i=lines.size()-1;i>=0;i--){Map<String,Object> line=lines.get(i);String text=cn.bidcheck.compat.Java8Api.strip(str(line,"text"));if(cn.bidcheck.compat.Java8Api.isBlank(text))continue;
                Matcher role=ROLE_HEADING.matcher(text);
                if(role.matches()){
                    Page p=(Page)line.get("page");inherited=source(doc,p,str(line,"text"),((Number)line.get("offset")).intValue(),"SECTION_SUBJECT");inherited.put("subject",role.group(1));heading=str(line,"text");break;
                }
                if(BOUNDARY.matcher(text).find()||text.contains("|"))break;
            }
        }
        if(QualificationBidderContext.applies(cp,original)){
            // Neutral invoice clauses need a closed qualification scope; the generic nearby
            // heading walk must not lend a bidder across an unrelated unnumbered section.
            heading="";
            QualificationBidderContext.Binding qualification=QualificationBidderContext.resolve(cp,doc,at,original);
            if(qualification!=null){heading=qualification.heading();sources.addAll(qualification.sources());}
        }else if(inherited!=null)sources.add(inherited);
        // A synthetic prefix is used only by the local binder, with its original evidence retained above.
        return new Context(cn.bidcheck.compat.Java8Api.isBlank(heading)?original:heading+"\n"+original,identity,cn.bidcheck.compat.Java8Api.listCopyOf(sources));
    }
    private static Map<String,Object> source(Document d,Page p,String quote,int start,String kind){return linked("kind",kind,"documentId",d.id(),"documentHash",d.sha256(),"documentVersion",d.version(),"locationType",p.locationType(),"location",p.number(),"start",start,"end",start+quote.length(),"sourceQuote",quote);}
    private static String lineAt(String text,int offset){int start=text.lastIndexOf('\n',Math.max(0,offset-1))+1,end=text.indexOf('\n',offset);return text.substring(start,end<0?text.length():end);}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class RowSubject{
        private final String subject;
        private final String header;
        private final int headerStart;
        @com.fasterxml.jackson.annotation.JsonCreator
        private RowSubject(@com.fasterxml.jackson.annotation.JsonProperty("subject") String subject, @com.fasterxml.jackson.annotation.JsonProperty("header") String header, @com.fasterxml.jackson.annotation.JsonProperty("headerStart") int headerStart) { this.subject = subject; this.header = header; this.headerStart = headerStart; }
        public String subject() { return subject; }
        public String header() { return header; }
        public int headerStart() { return headerStart; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; RowSubject that = (RowSubject) other; return java.util.Objects.equals(this.subject, that.subject) && java.util.Objects.equals(this.header, that.header) && java.util.Objects.equals(this.headerStart, that.headerStart); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.subject); hash = 31 * hash + java.util.Objects.hashCode(this.header); hash = 31 * hash + java.util.Objects.hashCode(this.headerStart); return hash; }
        @Override public String toString() { return "RowSubject[" + "subject=" + subject + ", header=" + header + ", headerStart=" + headerStart + "]"; }
}
    private static RowSubject tableSubject(String text,int offset,String quote){
        if(quote.contains("\n"))return null;String line=lineAt(text,offset);if(!line.contains("|"))return null;
        String[] rows=text.substring(0,offset).split("\\R");String[] header=null;String headerText="";
        for(int i=rows.length-2;i>=0;i--){String row=rows[i];if(!row.contains("|"))break;
            if(row.matches("[\\s|:\\-]+")){if(i>0){headerText=rows[i-1];header=headerText.split("\\|",-1);}break;}}
        if(header==null)return null;String[] values=line.split("\\|",-1);if(header.length!=values.length)return null;
        List<String> found=new ArrayList<>();for(int i=0;i<header.length;i++)if(cn.bidcheck.compat.Java8Api.setOf("主体", "适用主体", "适用对象", "单位", "岗位").contains(cn.bidcheck.compat.Java8Api.strip(header[i]))){
            String value=cn.bidcheck.compat.Java8Api.strip(values[i]);if(value.matches("(?:"+RequirementContextBindings.ROLES+")"))found.add(value);}
        int headerStart=text.lastIndexOf(headerText,offset);return found.size()==1&&headerStart>=0?new RowSubject(found.get(0),headerText,headerStart):null;
    }
}
