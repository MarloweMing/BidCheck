package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Server-derived heading/row context. Original requirement text is never rewritten. */
final class RequirementSourceContext {
    static final String VERSION="requirement-source-context-1";
    private static final Pattern ROLE_HEADING=Pattern.compile("^[\\s★*]*(?:[0-9]+(?:[.．][0-9]+)*[、.．)]?\\s*)?("+RequirementContextBindings.ROLES+")(?:资格要求|资质要求|要求)\\s*[:：]?\\s*$");
    private static final Pattern BOUNDARY=Pattern.compile("^(?:第[一二三四五六七八九十百0-9]+[章节部分]|[0-9]+(?:[.．][0-9]+)+\\s*[^0-9]|[一二三四五六七八九十]+[、.．])|.*(?:第[^\\s]{1,12}标段|历史项目|示例|如果|否则|如为|如有|如组成).*");
    record Context(String bindingSource,Map<String,Object> identity,List<Map<String,Object>> sources){
        static Context empty(String source){return new Context(source,Map.of(),List.of());}
        Map<String,Object> map(){return linked("version",VERSION,"identity",identity,"sources",sources);}
    }
    static Context resolve(Checkpoint cp,String original,Map<String,Object> context){
        if(cp.source()==null||original.isBlank()||!original.equals(cp.source().quote()))return Context.empty(original);
        List<Document> docs=new ArrayList<>();Object input=context.get("tenderDocuments");
        if(input instanceof List<?> list)for(Object item:list)if(item instanceof Document d)docs.add(d);
        List<Document> matching=docs.stream().filter(d->d.id().equals(cp.source().documentId())&&"TENDER".equals(d.role()))
            .filter(d->str(context,"requirementProjectId").isBlank()||d.projectId().equals(str(context,"requirementProjectId"))).toList();
        if(matching.size()!=1)return Context.empty(original);Document doc=matching.get(0);
        List<Page> pages=doc.pages();int at=-1;for(int i=0;i<pages.size();i++)if(pages.get(i).number()==cp.source().page()&&pages.get(i).locationType().equals(cp.source().locationType())){if(at>=0)return Context.empty(original);at=i;}
        if(at<0)return Context.empty(original);Page page=pages.get(at);int offset=RequirementContextBindings.uniqueOffset(page.text(),original);
        if(offset<0)return Context.empty(original);
        Map<String,Object> identity=linked("projectId",doc.projectId(),"documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",doc.version(),"locationType",page.locationType(),"location",page.number(),"start",offset,"end",offset+original.length(),"sourceHash",VerificationTypes.identity(original));
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
            for(int i=lines.size()-1;i>=0;i--){Map<String,Object> line=lines.get(i);String text=str(line,"text").strip();if(text.isBlank())continue;
                Matcher role=ROLE_HEADING.matcher(text);
                if(role.matches()){
                    Page p=(Page)line.get("page");inherited=source(doc,p,str(line,"text"),((Number)line.get("offset")).intValue(),"SECTION_SUBJECT");inherited.put("subject",role.group(1));heading=str(line,"text");break;
                }
                if(BOUNDARY.matcher(text).find()||text.contains("|"))break;
            }
        }
        if(inherited!=null)sources.add(inherited);
        // A synthetic prefix is used only by the local binder, with its original evidence retained above.
        return new Context(heading.isBlank()?original:heading+"\n"+original,identity,List.copyOf(sources));
    }
    private static Map<String,Object> source(Document d,Page p,String quote,int start,String kind){return linked("kind",kind,"documentId",d.id(),"documentHash",d.sha256(),"documentVersion",d.version(),"locationType",p.locationType(),"location",p.number(),"start",start,"end",start+quote.length(),"sourceQuote",quote);}
    private static String lineAt(String text,int offset){int start=text.lastIndexOf('\n',Math.max(0,offset-1))+1,end=text.indexOf('\n',offset);return text.substring(start,end<0?text.length():end);}
    private record RowSubject(String subject,String header,int headerStart){}
    private static RowSubject tableSubject(String text,int offset,String quote){
        if(quote.contains("\n"))return null;String line=lineAt(text,offset);if(!line.contains("|"))return null;
        String[] rows=text.substring(0,offset).split("\\R");String[] header=null;String headerText="";
        for(int i=rows.length-2;i>=0;i--){String row=rows[i];if(!row.contains("|"))break;
            if(row.matches("[\\s|:\\-]+")){if(i>0){headerText=rows[i-1];header=headerText.split("\\|",-1);}break;}}
        if(header==null)return null;String[] values=line.split("\\|",-1);if(header.length!=values.length)return null;
        List<String> found=new ArrayList<>();for(int i=0;i<header.length;i++)if(Set.of("主体","适用主体","适用对象","单位","岗位").contains(header[i].strip())){
            String value=values[i].strip();if(value.matches("(?:"+RequirementContextBindings.ROLES+")"))found.add(value);}
        int headerStart=text.lastIndexOf(headerText,offset);return found.size()==1&&headerStart>=0?new RowSubject(found.get(0),headerText,headerStart):null;
    }
}
