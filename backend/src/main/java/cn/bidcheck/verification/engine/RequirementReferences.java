package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Expands numbered tender references before interpreting bidder obligations. */
final class RequirementReferences {
    static final String VERSION="tender-reference-expansion-2-typed-dependency";
    private static final Pattern REFERENCE=Pattern.compile("(?:符合|按照|依据|参照|参见|详见|执行|见)([^。；;\\n]{0,55}?)([0-9]+(?:[.．][0-9]+){1,5})(?:条款?|款|项|(?=初步评审))");
    private static final Pattern CHAPTER=Pattern.compile("^\\s*第([一二三四五六七八九十百0-9]+)章(?:\\s|[、：:]|[^0-9]).*$");
    private static final Pattern CLAUSE=Pattern.compile("^\\s*([0-9]+(?:[.．][0-9]+)+)(?![0-9.．])(?:\\s|[、：:])");
    record Expansion(RequirementSourceContext.Context context,List<String> issues,List<Map<String,Object>> references) {}
    private record Ref(String number,String chapter,String type,String quote) {}
    private record Hit(List<Map<String,Object>> sources,String text) {}
    private RequirementReferences() {}

    static Expansion expand(Checkpoint cp,String obligation,RequirementSourceContext.Context context,Map<String,Object> input){
        List<Ref> requested=references(obligation);if(requested.isEmpty())return new Expansion(context,List.of(),List.of());
        List<Document> documents=new ArrayList<>();
        if(input.get("tenderDocuments") instanceof List<?> list)for(Object item:list)if(item instanceof Document d&&"TENDER".equals(d.role())&&cp.source()!=null&&d.id().equals(cp.source().documentId()))documents.add(d);
        documents=documents.stream().collect(java.util.stream.Collectors.toMap(Document::id,d->d,(a,b)->a,LinkedHashMap::new)).values().stream().toList();
        if(documents.size()!=1||context.identity().isEmpty())return new Expansion(context,List.of("引用条款缺少同一冻结招标文档的来源定位"),List.of());
        Document doc=documents.get(0);List<Map<String,Object>> sources=new ArrayList<>(context.sources()),audit=new ArrayList<>();List<String> issues=new ArrayList<>();Set<String> visited=new HashSet<>();
        expand(doc,requested,sources,audit,issues,visited,new HashSet<>(),0);
        String extra=sources.stream().filter(s->"REFERENCE_CLAUSE".equals(s.get("kind"))).map(s->str(s,"sourceQuote")).distinct().collect(java.util.stream.Collectors.joining("\n"));
        Map<String,Object> identity=new LinkedHashMap<>(context.identity());identity.put("referenceVersion",VERSION);identity.put("referenceSourcesHash",VerificationTypes.identity(sources.toString()));identity.put("referenceIssues",List.copyOf(issues));
        identity.put("referenceDependencies",audit.stream().filter(a->"SUMMARY_OF".equals(a.get("referenceType"))).toList());
        return new Expansion(new RequirementSourceContext.Context(context.bindingSource()+(extra.isBlank()?"":"\n"+extra),identity,List.copyOf(sources)),List.copyOf(issues),List.copyOf(audit));
    }
    private static void expand(Document doc,List<Ref> requested,List<Map<String,Object>> sources,List<Map<String,Object>> audit,List<String> issues,Set<String> visited,Set<String> active,int depth){
        for(Ref ref:requested){String key=ref.chapter()+":"+ref.number()+":"+ref.type();
            if(active.contains(key)){issues.add("引用条款出现循环："+ref.number());continue;}
            if(visited.contains(key))continue;
            if(depth>4||visited.size()>=16){issues.add("引用链尚未完整展开："+ref.number());continue;}
            List<Hit> hits=locate(doc,ref);if(hits.isEmpty()){issues.add("未定位到引用条款正文："+ref.number());continue;}
            if(hits.size()>1){
                List<Hit> special=hits.stream().filter(h->!amendment(h,ref).isEmpty()).toList();
                if(hits.size()!=2||special.size()!=1){issues.add("引用条款正文存在多个候选："+ref.number());continue;}
                if(!amendment(special.get(0),ref).equals("本条款增加以下规定"))hits=special;
            }
            active.add(key);visited.add(key);List<Ref> next=new ArrayList<>();
            for(Hit hit:hits){for(Map<String,Object> raw:hit.sources()){
                    Map<String,Object> source=new LinkedHashMap<>(raw);source.put("referenceType",ref.type());
                    if("SUMMARY_OF".equals(ref.type()))source.put("kind","REFERENCE_DEPENDENCY");
                    if(sources.stream().noneMatch(s->s.equals(source)))sources.add(source);
                }
                if(!"SUMMARY_OF".equals(ref.type()))next.addAll(references(hit.text()));}
            audit.add(linked("clauseNumber",ref.number(),"chapter",ref.chapter(),"referenceType",ref.type(),"referenceQuote",ref.quote(),
                    "status","SUMMARY_OF".equals(ref.type())?"DEPENDENCY_SOURCE_RESOLVED":"SOURCE_RESOLVED",
                    "sourceCount",hits.stream().mapToInt(h->h.sources().size()).sum(),
                    "dependencyId",VerificationTypes.identity(doc.id()+"|"+doc.version()+"|"+doc.sha256()+"|"+ref.chapter()+"|"+ref.number()),
                    "specialProvisionPolicy",hits.size()==2?"EXPLICIT_ADDITION":"EXPLICIT_SOURCE"));
            expand(doc,next,sources,audit,issues,visited,active,depth+1);active.remove(key);
        }
    }
    private static String amendment(Hit hit,Ref ref){
        String body=hit.text().replaceFirst("^\\s*"+Pattern.quote(ref.number())+"(?:[\\t ]|[、：:])+","");
        // A numbered front-table row may have a separate short caption before its text cell.
        // Recognize only that cell boundary; quoted or conditional amendment wording is not an override.
        String[] cells=hit.text().split("\\t",3);
        if(cells.length==3&&cells[0].strip().equals(ref.number())&&cells[1].strip().length()<=80
            &&!cells[1].matches("(?s).*(?:[。；;：:]|如果|如收到|如为|若|当|示例|范本|引用|收到|通知|标注).*"))body=cells[2];
        Matcher marker=Pattern.compile("^\\s*(本条款增加以下规定|本条款修改为|本条款替换为)(?=[：:，,。\\s]|$)").matcher(body);
        return marker.find()?marker.group(1):"";
    }
    static String requirementText(String obligation,RequirementSourceContext.Context context){
        LinkedHashSet<String> parts=new LinkedHashSet<>();parts.add(obligation);for(Map<String,Object> s:context.sources())if(compilationSource(s)&&!obligation.contains(str(s,"sourceQuote")))parts.add(str(s,"sourceQuote"));return String.join("\n",parts);
    }
    static List<String> issues(RequirementSourceContext.Context context){return ProofVerifier.strings(context.identity().get("referenceIssues"));}
    static List<Map<String,Object>> dependencies(RequirementSourceContext.Context context){
        if(context==null||!(context.identity().get("referenceDependencies") instanceof List<?> list))return List.of();
        List<Map<String,Object>> out=new ArrayList<>();for(Object item:list)if(item instanceof Map<?,?> row){Map<String,Object> copy=new LinkedHashMap<>();row.forEach((k,v)->copy.put(String.valueOf(k),v));out.add(copy);}return List.copyOf(out);
    }
    private static boolean compilationSource(Map<String,Object> source){return Set.of("REFERENCE_CLAUSE","SCOPED_PROOF","SCOPED_SCORING_CONTINUATION","SCOPED_BOOK_CONDITION").contains(source.get("kind"));}
    static boolean sourceBound(String quote,String obligation,RequirementSourceContext.Context context){return obligation.contains(quote)||context.sources().stream().filter(RequirementReferences::compilationSource).anyMatch(s->str(s,"sourceQuote").contains(quote));}
    static boolean referenceOnly(Rule r){return Set.of("SECTION","MATERIAL").contains(r.predicate())&&!references(r.sourceQuote()).isEmpty()&&r.sourceQuote().matches("(?s)^.*(?:符合|按照|依据|参照|参见|详见|执行|见)[^。；;\\n]{0,100}[0-9]+(?:[.．][0-9]+)+(?:条款?|款|项|初步评审要求)[规定要求\\s。]*$");}
    private static List<Ref> references(String text){List<Ref> out=new ArrayList<>();Matcher m=REFERENCE.matcher(text);while(m.find()){
        Matcher chapter=Pattern.compile("第([一二三四五六七八九十百0-9]+)章").matcher(m.group(1));
        String tail=text.substring(m.end(),Math.min(text.length(),m.end()+24));
        String type=tail.matches("^(?:初步评审|评审标准|评审要求|评审办法).*" )?"SUMMARY_OF":"REQUIRES_CLAUSE";
        String quote=m.group();if("SUMMARY_OF".equals(type)){Matcher label=Pattern.compile("^(?:初步评审|评审标准|评审要求|评审办法)(?:要求)?").matcher(tail);if(label.find())quote+=label.group();}
        Ref ref=new Ref(m.group(2).replace('．','.'),chapter.find()?chapter.group(1):"",type,quote);if(!out.contains(ref))out.add(ref);
    }return out;}
    private static List<Hit> locate(Document doc,Ref ref){List<Hit> out=new ArrayList<>();List<Page> pages=doc.pages();String chapter="";Set<String> seenText=new HashSet<>();
        for(int i=0;i<pages.size();i++){Page page=pages.get(i);String text=page.text();Matcher heading=CHAPTER.matcher(text.strip());
            if(heading.matches()&&!text.strip().matches(".*\\s[0-9]+$")&&!adjacentChapter(pages,i))chapter=heading.group(1);
            if(!ref.chapter().isBlank()&&!ref.chapter().equals(chapter))continue;
            Matcher start=Pattern.compile("(?m)^\\s*"+Pattern.quote(ref.number())+"(?![0-9.．])(?:[\\t ]|[、：:])").matcher(text);while(start.find()){
            int at=start.start();StringBuilder body=new StringBuilder();List<Map<String,Object>> sources=new ArrayList<>();boolean finished=false;
            for(int j=i;j<pages.size()&&j<i+40;j++){Page p=pages.get(j);String raw=p.text();int from=j==i?at:0,end=raw.length();
                Matcher boundary=Pattern.compile("(?m)^\\s*([0-9]+(?:[.．][0-9]+)+)(?![0-9.．])(?:\\s|[、：:])|^\\s*第[一二三四五六七八九十百0-9]+章").matcher(raw);
                while(boundary.find()){if(j==i&&boundary.start()<=at)continue;String number=boundary.group(1);if(number!=null&&number.replace('．','.').startsWith(ref.number()+"."))continue;end=boundary.start();finished=true;break;}
                if(end>from){String quote=raw.substring(from,end);if(body.length()+quote.length()>16000)return List.of();body.append(quote).append('\n');sources.add(linked("kind","REFERENCE_CLAUSE","documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",doc.version(),"locationType",p.locationType(),"location",p.number(),"start",from,"end",end,"sourceQuote",quote,"clauseNumber",ref.number(),"chapter",ref.chapter()));}
                if(finished||j==pages.size()-1)break;
                // A table row is already a complete explicitly numbered provision.
                if(j==i&&raw.contains("\t")&&raw.contains("本条款")){finished=true;break;}
                if(j==i&&raw.length()>100&&!Pattern.compile("^\\s*"+Pattern.quote(ref.number())+"\\s*$").matcher(raw).matches()&&j+1<pages.size()&&CLAUSE.matcher(pages.get(j+1).text()).find()){finished=true;break;}
            }
            if(!finished&&i+40<pages.size())return List.of();String key=clean(body.toString());if(!key.isBlank()&&seenText.add(key))out.add(new Hit(List.copyOf(sources),body.toString()));
            }
        }return out;
    }
    private static boolean adjacentChapter(List<Page> pages,int at){return at>0&&CHAPTER.matcher(pages.get(at-1).text().strip()).matches()||at+1<pages.size()&&CHAPTER.matcher(pages.get(at+1).text().strip()).matches();}
}
