package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Source-owned inventory nodes. A heading or a review event is never a missing bidder document. */
final class SourceObligationDirectory {
    static final String VERSION="source-obligation-directory-1";
    static final String DOCUMENT="DOCUMENT_OBLIGATION", DECLARATION="DECLARATION", EXTERNAL="EXTERNAL_FACT",
            EVENT="EVALUATION_EVENT", HEADING="STRUCTURAL_HEADING", REFERENCE="SOURCE_REFERENCE", OPEN="OPEN_LEGAL_SCOPE";
    private static final Pattern PREFIX=Pattern.compile("^(?:[★*]+|[（(](?:[0-9]{1,3}|[一二三四五六七八九十]+)[）)]|[0-9]{1,2}(?:[.．][0-9]{1,3})+(?:\\s+|(?=[\\p{IsHan}]))|[一二三四五六七八九十]+[、.．])+");
    private SourceObligationDirectory() {}

    static String type(String text){
        String value=canonical(text);
        // An inline citation can start with a chapter name (for example
        // 第二章“投标人须知”第1.7款); that does not make it a structural heading.
        if(value.matches("(?s).*(?:根据本章第[0-9]+(?:[.．][0-9]+)*款|第[0-9]+(?:[.．][0-9]+)*款规定的任何一种情形|见(?:投标人须知|评标办法)前附表).*"))return REFERENCE;
        if(value.matches("(?:第[一二三四五六七八九十百0-9]+[章分册].*|初步评审(?:标准)?|投标文件格式|评审程序|评审标准|评标方法|投标人不得存在的情形|投标人不得存在下列情形之一[:：]?|投标人有以下情形之一的[，,]评标委员会应当否决其投标[：:]?)"))return HEADING;
        if(value.matches("(?s).*(?:现场依据投标情况进行判定|评标过程中|不按评标委员会要求澄清|不接受修正价格|经投标人书面确认).*"))return EVENT;
        if(value.matches("(?s).*(?:有串通投标|弄虚作假、行贿|以他人名义投标|其他投标报价|招标人不能接受的条件).*"))return EXTERNAL;
        if(value.matches("^(?:为招标人不具有独立法人资格的附属机构.*|为本招标项目(?:前期准备提供|的监理人|的代建人|提供招标代理服务).*|与本招标项目的(?:监理人|代建人|招标代理机构).*|被责令停业的|被暂停或取消投标资格的|财产被接管或冻结的|在最近三年内有骗取中标.*)$"))return EXTERNAL;
        if(value.matches("(?s).*(?:法律[、，,]?法规.*其他(?:实质性内容|情形)|法律规定的其他).*"))return OPEN;
        if(value.contains("承诺")&&!value.contains("现场"))return DECLARATION;
        return DOCUMENT;
    }

    /** Only layout/numbering and a closed vocabulary of modal synonyms are normalized.
     * All subjects, numbers, scope, exceptions, negation and alternatives remain in the key. */
    static String canonical(String raw){
        String value=cn.bidcheck.compat.Java8Api.strip(Objects.toString(raw,""));
        value=cn.bidcheck.compat.Java8Api.strip(PREFIX.matcher(value).replaceFirst(""));
        // Word table captions are layout, but the business caption (for example personnel/issuer)
        // remains part of the semantic key. Only the three exact review-section labels are omitted.
        value=value.replaceFirst("^(?:形式评审|资格评审|响应评审)\\s*\\t\\s*","");
        value=value.replaceFirst("^([\\p{IsHan}（）()]{1,20})\\s*\\t\\s*","$1：");
        value=clean(value).replace('（','(').replace('）',')').replace('；',';').replace('，',',').replace('：',':').replace('．','.');
        value=value.replaceAll("^[★*]+","").replaceAll("[。;]+$","");
        value=value.replace("须提交","须提供").replace("需提交","须提供").replace("需提供","须提供");
        return value;
    }
    static String id(String type,String quote,List<Map<String,Object>> sources){
        String scope=sources.stream().map(s->str(s,"documentId")+"|"+str(s,"documentHash")+"|"+str(s,"documentVersion"))
                .distinct().sorted().reduce("",(a,b)->a+"|"+b);
        return "OBL-"+identity(VERSION+"|"+scope+"|"+type+"|"+canonical(quote)).substring(0,24);
    }
    static Map<String,Object> node(String name,List<Map<String,Object>> sources,String type){
        return linked("canonicalObligationId",id(type,name,sources),"nodeType",type,"name",name,"sources",sources,
                "businessCheckRequired",!cn.bidcheck.compat.Java8Api.setOf(HEADING, REFERENCE).contains(type));
    }
    static boolean sameMeaning(String a,String b){String left=canonical(a),right=canonical(b);return !cn.bidcheck.compat.Java8Api.isBlank(left)&&left.equals(right);}

    static boolean sourceCurrent(Map<String,Object> source,List<Document> documents){
        return documents.stream().anyMatch(d->d.id().equals(str(source,"documentId"))
                &&(cn.bidcheck.compat.Java8Api.isBlank(str(source,"documentHash"))||d.sha256().equals(str(source,"documentHash")))
                &&(!(source.get("documentVersion") instanceof Number)||d.version()==((Number) (source.get("documentVersion"))).intValue())
                &&d.pages().stream().anyMatch(p->p.number()==RunSession.number(source.get("location"),-1)
                    &&p.locationType().equals(str(source,"locationType"))&&p.text().contains(str(source,"sourceQuote"))));
    }
    static boolean checkpointSourceCurrent(Checkpoint cp,List<Document> documents){
        if(cp==null||cp.source()==null||cp.source().quote()==null||cn.bidcheck.compat.Java8Api.isBlank(cp.source().quote()))return false;
        Evidence s=cp.source();return documents.stream().filter(d->d.id().equals(s.documentId())).flatMap(d->d.pages().stream())
                .anyMatch(p->p.number()==s.page()&&p.locationType().equals(s.locationType())&&p.text().contains(s.quote()));
    }
    static Map<String,Object> source(Document doc,Page page){return linked("documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",doc.version(),
            "locationType",page.locationType(),"location",page.number(),"start",0,"end",page.text().length(),"sourceQuote",page.text());}

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Expansion {
        private final List<Map<String,Object>> rows;
        private final List<Map<String,Object>> edges;
        private final List<String> issues;
        @com.fasterxml.jackson.annotation.JsonCreator
        Expansion(@com.fasterxml.jackson.annotation.JsonProperty("rows") List<Map<String,Object>> rows, @com.fasterxml.jackson.annotation.JsonProperty("edges") List<Map<String,Object>> edges, @com.fasterxml.jackson.annotation.JsonProperty("issues") List<String> issues) { this.rows = rows; this.edges = edges; this.issues = issues; }
        public List<Map<String,Object>> rows() { return rows; }
        public List<Map<String,Object>> edges() { return edges; }
        public List<String> issues() { return issues; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Expansion that = (Expansion) other; return java.util.Objects.equals(this.rows, that.rows) && java.util.Objects.equals(this.edges, that.edges) && java.util.Objects.equals(this.issues, that.issues); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.rows); hash = 31 * hash + java.util.Objects.hashCode(this.edges); hash = 31 * hash + java.util.Objects.hashCode(this.issues); return hash; }
        @Override public String toString() { return "Expansion[" + "rows=" + rows + ", edges=" + edges + ", issues=" + issues + "]"; }
}
    private static final Pattern CLAUSE_REFERENCE=Pattern.compile("(?:第([一二三四五六七八九十百0-9]+)章[^。；;]{0,30}?)?(?:本章)?第([0-9]+(?:[.．][0-9]+)+)\\s*款");
    private static final Pattern CHAPTER=Pattern.compile("^第([一二三四五六七八九十百0-9]+)章[^。；;]{0,40}$");
    private static final Pattern NUMBER=Pattern.compile("^\\s*([0-9]+(?:[.．][0-9]+)+)(?![.．0-9])(?:\\s|(?=[\\p{IsHan}]))");

    /** Explicit numbered references retain both edge sources and target spans; ambiguous targets remain gaps. */
    static Expansion expand(Document doc,List<Map<String,Object>> rows){
        List<Map<String,Object>> out=new ArrayList<>(),edges=new ArrayList<>();List<String> issues=new ArrayList<>();
        expand(doc,rows,out,edges,issues,new LinkedHashSet<>(),new HashSet<>(),0);
        return new Expansion(cn.bidcheck.compat.Java8Api.listCopyOf(out),cn.bidcheck.compat.Java8Api.listCopyOf(edges),cn.bidcheck.compat.Java8Api.listCopyOf(issues));
    }
    private static void expand(Document doc,List<Map<String,Object>> rows,List<Map<String,Object>> out,List<Map<String,Object>> edges,List<String> issues,
                               Set<String> active,Set<String> emitted,int depth){
        for(Map<String,Object> row:rows){
            String quote=str(row,"sourceQuote");Matcher ref=CLAUSE_REFERENCE.matcher(quote);
            if(!REFERENCE.equals(type(quote))||!ref.find()){
                String key=str(row,"locationType")+":"+row.get("location")+":"+quote;if(emitted.add(key))out.add(row);continue;
            }
            String chapter=ref.group(1)==null?chapterAt(doc,RunSession.number(row.get("location"),-1)):ref.group(1);
            String number=ref.group(2).replace('．','.');String key=chapter+":"+number;
            if(active.contains(key)||depth>=8){issues.add("要求引用存在循环或未闭合："+key);out.add(row);continue;}
            List<List<Map<String,Object>>> candidates=locate(doc,chapter,number);
            // A standards heading explicitly referring to its front table and that table form one source graph.
            if(candidates.size()==2){
                List<List<Map<String,Object>>> tables=cn.bidcheck.compat.Java8Api.toList(candidates.stream().filter(c->!c.isEmpty()&&str(c.get(0),"sourceQuote").contains("\t")));
                boolean linked=candidates.stream().anyMatch(c->c.stream().anyMatch(s->str(s,"sourceQuote").contains("见评标办法前附表")));
                if(tables.size()==1&&linked)candidates=tables;
            }
            if(candidates.size()!=1){issues.add("引用业务义务尚未唯一定位："+key);out.add(row);continue;}
            List<Map<String,Object>> targets=candidates.get(0);edges.add(linked("referenceSource",row,"targetSources",targets,"referenceKey",key,"mappingPolicy","EXPLICIT_NUMBERED_SOURCE"));
            active.add(key);expand(doc,targets,out,edges,issues,active,emitted,depth+1);active.remove(key);
        }
    }
    private static String chapterAt(Document doc,int location){
        String chapter="";List<Page> pages=doc.pages();for(int i=0;i<pages.size();i++){
            Page p=pages.get(i);if(p.number()>location)break;Matcher m=CHAPTER.matcher(cn.bidcheck.compat.Java8Api.strip(p.text()));
            if(m.matches()&&!p.text().matches(".*\\t[0-9]+$")
                    &&!(i>0&&CHAPTER.matcher(cn.bidcheck.compat.Java8Api.strip(pages.get(i-1).text())).matches())
                    &&!(i+1<pages.size()&&CHAPTER.matcher(cn.bidcheck.compat.Java8Api.strip(pages.get(i+1).text())).matches()))chapter=m.group(1);
        }return chapter;
    }
    private static List<List<Map<String,Object>>> locate(Document doc,String chapter,String number){
        List<List<Map<String,Object>>> hits=new ArrayList<>();List<Page> pages=doc.pages();
        for(int i=0;i<pages.size();i++){
            Page page=pages.get(i);Matcher start=NUMBER.matcher(page.text());
            if(!start.find()||!number.equals(start.group(1).replace('．','.'))||(!cn.bidcheck.compat.Java8Api.isBlank(chapter)&&!chapter.equals(chapterAt(doc,page.number()))))continue;
            List<Map<String,Object>> span=new ArrayList<>();boolean closed=false;
            for(int j=i;j<pages.size();j++){
                Page current=pages.get(j);Matcher next=NUMBER.matcher(current.text());
                if(j>i&&(CHAPTER.matcher(cn.bidcheck.compat.Java8Api.strip(current.text())).matches()||next.find()&&!next.group(1).replace('．','.').startsWith(number+"."))){closed=true;break;}
                span.add(source(doc,current));if(j==pages.size()-1)closed=true;
            }
            if(closed&&!span.isEmpty())hits.add(span);
        }return hits;
    }
}
