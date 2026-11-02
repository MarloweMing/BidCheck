package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A submitted company-performance list is a current document containing historical contracts.
 * Its document range must not turn those contracts or their people into current-project facts. */
final class PerformanceInventoryProof {
    private static final Pattern SECTION=Pattern.compile("(?m)^\\s*([0-9]+(?:\\.[0-9]+)+)\\s*业绩证明文件\\s*$");
    private static final Pattern ROW=Pattern.compile("(?m)^[ \\t]*([1-9][0-9]{0,2})[ \\t]+");
    private static final Pattern NAV=Pattern.compile("^(.+?)\\s*[.．…·]{3,}\\s*([0-9]+)\\s*$");
    private record Table(Document document,Page page,String body,Map<String,Object> scope){}
    record Match(List<Evidence> evidence,List<Map<String,Object>> records,List<Map<String,Object>> candidates){
        Match(List<Evidence> evidence,List<Map<String,Object>> records){this(evidence,records,List.of());}
        boolean present(){return !records.isEmpty();}
    }
    private final List<Table> tables=new ArrayList<>();
    private final EvidenceCorpus corpus;
    PerformanceInventoryProof(String form,RuleEvaluator evaluator){
        corpus=evaluator.corpus;
        for(Document document:evaluator.corpus.documents){if(!"BID".equals(document.role()))continue;
            DocumentScopeProof.Resolver resolver=DocumentScopeProof.forDocument(document);
            for(Page page:document.pages()){
                String text=Objects.toString(page.text(),"");Matcher heading=Pattern.compile("(?m)^\\s*"+Pattern.quote(form)+"[ \\t]*(近年[^\\r\\n]{0,65}类似项目情况表|[^\\r\\n]{0,25}业绩清单)[ \\t]*$").matcher(text);
                if(!heading.find()||heading.find()||text.contains("填写示例")||text.contains("参考模板")||navigation(text))continue;
                heading.reset();heading.find();String title=heading.group().strip();
                Evidence headingEvidence=new Evidence(document.id(),document.name(),page.number(),page.locationType(),title,"chars:"+heading.start()+"-"+heading.end(),1);
                // A numbered formal performance section must agree with its navigation/body
                // boundaries even when the surrounding text has not yet entered history.
                Map<String,Object> scope=SECTION.matcher(nativeText(text)).find()?mappedScope(document,page):resolver.currentEvidence(headingEvidence);
                if(!Boolean.TRUE.equals(scope.get("verifiedCurrent")))scope=mappedScope(document,page);
                if(scope.isEmpty()||!Boolean.TRUE.equals(scope.get("verifiedCurrent")))continue;
                String body=text.substring(heading.end());if(!body.contains("合同名称")||!body.matches("(?s).*(?:签订时间|签订日期).*")||!body.contains("金额"))continue;
                tables.add(new Table(document,page,body,scope));
            }
        }
    }
    Match match(String name,Set<String> numbers,boolean uniqueName){
        return match(name,numbers,uniqueName,Set.of());
    }
    Match match(String name,Set<String> numbers,boolean uniqueName,Set<String> contractDocuments){
        String wanted=normalized(name);if(wanted.length()<8)return new Match(List.of(),List.of());
        List<Map<String,Object>> records=new ArrayList<>();List<Evidence> evidence=new ArrayList<>();
        for(Table table:tables)if(contractDocuments.isEmpty()||contractDocuments.contains(table.document().id()))for(Map<String,Object> row:matchingRows(table.body(),wanted)){
            String quote=str(row,"quote");boolean numbered=numbers.stream().anyMatch(number->Pattern.compile("(?<![A-Za-z0-9/-])"+Pattern.quote(number)+"(?![A-Za-z0-9/-])").matcher(quote).find());
            // A name shared by two distinct contracts is not a unique link without its number.
            if(!uniqueName&&!numbered)continue;
            Map<String,Object> record=new LinkedHashMap<>(row);record.putAll(linked("documentId",table.document().id(),"documentHash",table.document().sha256(),"documentVersion",table.document().version(),"page",table.page().number(),"scope",table.scope(),"contractName",name,"contractNumberMatched",numbered,"linkScope","LIST_ROW_TO_CONTRACT_ONLY","issuerBindingRequiredSeparately",true));records.add(record);
            evidence.add(new Evidence(table.document().id(),table.document().name(),table.page().number(),table.page().locationType(),quote,"performance-row:"+row.get("row"),1));
        }
        // Duplicate or competing submitted records stay ambiguous; do not silently pick a row.
        return records.size()==1?new Match(List.copyOf(evidence),List.copyOf(records)):new Match(List.of(),List.of(),similarCandidates(name,contractDocuments));
    }
    /** Similar titles need an independent signing date/amount and the formal proof page range.
     * A user-confirmed row relation authorizes the row join, never its financial qualification. */
    Match matchWithEvidence(String name,Set<String> numbers,boolean uniqueName,Set<String> documents,List<Source> contractSources,List<Fact> facts){
        Match exact=match(name,numbers,uniqueName,documents);if(exact.present()||exact.candidates().isEmpty())return exact;
        List<Map<String,Object>> eligible=new ArrayList<>(),candidates=new ArrayList<>();
        Set<String> signed=new HashSet<>();Set<java.math.BigDecimal> totals=new TreeSet<>();for(Fact fact:facts)if(fact.kind().equals("CONTRACT")){
            if(!str(fact.attributes(),"signedDate").isBlank())signed.add(str(fact.attributes(),"signedDate"));
            if(str(fact.attributes(),"amountBasis").matches(".*(?:合同金额|签约金额|合同总价|合同价款).*")){var amount=RuleEvaluator.normalize(ProofVerifier.decimal(fact.attributes().get("amount")),str(fact.attributes(),"unit"));if(amount!=null)totals.add(amount);}
        }
        for(Map<String,Object> initial:exact.candidates()){
            Map<String,Object> candidate=new LinkedHashMap<>(initial);String quote=str(candidate,"quote"),rowName=str(candidate,"contractName");String documentId=str(candidate,"documentId");int page=((Number)candidate.get("page")).intValue();
            Table table=tables.stream().filter(t->t.document().id().equals(documentId)&&t.page().number()==page).findFirst().orElse(null);if(table==null)continue;
            Map<String,Object> row=rowMetadata(quote);candidate.putAll(row);List<String> rowSources=sourceIds(documentId,page,quote);candidate.put("sourceIds",rowSources);
            boolean confirmed=facts.stream().anyMatch(f->InferenceClaims.humanConfirmed(f)&&f.kind().equals("RELATION")&&f.field().equals("CONTRACT_RECORD_MATCH")&&f.subject().equals(name)&&str(f.attributes(),"object").equals(rowName)&&str(f.attributes(),"documentId").equals(documentId)&&str(f.attributes(),"inventoryPage").equals(Integer.toString(page))&&str(f.attributes(),"inventoryRow").equals(str(candidate,"row"))&&f.sourceIds().containsAll(rowSources)&&contractSources.stream().anyMatch(s->f.sourceIds().contains(s.id())));
            List<String> rowDates=ProofVerifier.strings(row.get("signedDates"));Set<java.math.BigDecimal> rowAmounts=new TreeSet<>();for(String amount:ProofVerifier.strings(row.get("listedAmounts"))){var value=ProofVerifier.decimal(amount);if(value!=null)rowAmounts.add(value);}
            boolean dateMatch=signed.size()==1&&rowDates.size()==1&&signed.contains(rowDates.get(0));boolean amountMatch=totals.size()==1&&rowAmounts.size()==1&&totals.equals(rowAmounts);
            int[] range=proofRange(table);boolean inRange=range[0]>0&&!contractSources.isEmpty()&&contractSources.stream().allMatch(s->s.evidence().documentId().equals(documentId)&&s.evidence().page()>=range[0]&&s.evidence().page()<=range[1]);
            boolean numbered=numbers.stream().anyMatch(n->Pattern.compile("(?<![A-Za-z0-9/-])"+Pattern.quote(n)+"(?![A-Za-z0-9/-])").matcher(quote).find());
            boolean identityMatch=confirmed||uniqueName&&inRange&&(numbered||dateMatch&&amountMatch&&((Number)candidate.get("nameSimilarity")).doubleValue()>=0.90);
            candidate.putAll(linked("signedDateMatched",dateMatch,"contractTotalMatched",amountMatch,"contractNumberMatched",numbered,"proofPageRange",List.of(range[0],range[1]),"contractSourcesWithinRange",inRange,"humanConfirmed",confirmed,"relationProven",identityMatch,"countsAsPerformance",false));
            if(identityMatch&&!rowSources.isEmpty()){candidate.putAll(linked("confirmationRequired",false,"matchMethod",confirmed?"HUMAN_CONFIRMED_CITED_INVENTORY_ROW":"SIMILAR_NAME_WITH_PAGE_RANGE_AND_INDEPENDENT_CONTRACT_FIELDS","linkScope","LIST_ROW_TO_CONTRACT_ONLY","issuerBindingRequiredSeparately",true));eligible.add(candidate);}
            candidates.add(candidate);
        }
        if(eligible.size()!=1)return new Match(List.of(),List.of(),List.copyOf(candidates));
        Map<String,Object> row=eligible.get(0);String id=str(row,"documentId");int page=((Number)row.get("page")).intValue();Table table=tables.stream().filter(t->t.document().id().equals(id)&&t.page().number()==page).findFirst().orElseThrow();
        return new Match(List.of(new Evidence(id,table.document().name(),page,table.page().locationType(),str(row,"quote"),"performance-row:"+row.get("row"),1)),List.of(row),List.of());
    }
    private List<String> sourceIds(String document,int page,String quote){
        var original=corpus.pages.get(EvidenceCorpus.pageKey(document,page,"PAGE"));if(original==null)return List.of();int start=original.text().indexOf(quote);if(start<0||original.text().indexOf(quote,start+1)>=0)return List.of();int end=start+quote.length();List<String> ids=new ArrayList<>();BitSet covered=new BitSet(quote.length());
        for(Source source:corpus.all){if(!source.evidence().documentId().equals(document)||source.evidence().page()!=page)continue;Matcher range=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(source.evidence().anchor());if(!range.matches())continue;int left=Integer.parseInt(range.group(1)),right=Integer.parseInt(range.group(2));if(left>=end||right<=start||right>original.text().length()||!original.text().substring(left,right).equals(source.evidence().quote()))continue;ids.add(source.id());covered.set(Math.max(start,left)-start,Math.min(end,right)-start);}
        return covered.cardinality()==quote.length()?List.copyOf(ids):List.of();
    }
    private static Map<String,Object> rowMetadata(String quote){Set<String> dates=new LinkedHashSet<>(),amounts=new LinkedHashSet<>();for(String label:List.of("签订时间","签订日期"))cn.bidcheck.verification.source.DateFieldValues.read(quote,label,1).forEach(d->dates.add(d.toString()));Matcher m=Pattern.compile("(?:合同金额|金额)[:：]\\s*([0-9][0-9,.]*)(万元|元)").matcher(quote);while(m.find()){var n=RuleEvaluator.normalize(ProofVerifier.decimal(m.group(1)),m.group(2));if(n!=null)amounts.add(n.toPlainString());}return linked("signedDates",List.copyOf(dates),"listedAmounts",List.copyOf(amounts));}
    private static int[] proofRange(Table table){
        if(!(table.scope().get("navigation") instanceof Map<?,?> navigation))return new int[]{-1,-1};Object begin=navigation.get("proofPage");if(!(begin instanceof Number number))return new int[]{-1,-1};int first=number.intValue(),last=table.document().pages().stream().mapToInt(Page::number).max().orElse(first);
        String section=str(table.scope(),"sectionTitle").replaceFirst("\\s.*$","");if(section.isBlank())return new int[]{-1,-1};int depth=section.split("\\.").length;
        for(Page page:table.document().pages().stream().filter(p->p.number()<=10).toList())for(String line:nativeText(page.text()).lines().toList()){Matcher nav=NAV.matcher(line.strip());if(!nav.matches())continue;String heading=nav.group(1);Matcher path=Pattern.compile("^([0-9]+(?:\\.[0-9]+)+)\\s*.*").matcher(heading);if(!path.matches())continue;int target=Integer.parseInt(nav.group(2));if(target>first&&path.group(1).split("\\.").length<=depth&&!path.group(1).equals(section))last=Math.min(last,target-1);}
        return new int[]{first,last};
    }
    /** Similarity proposes a row for review; it never links historical people, borrows an
     * amount basis, or contributes a counted contract. A document boundary is mandatory. */
    private List<Map<String,Object>> similarCandidates(String name,Set<String> documents){
        if(documents.isEmpty())return List.of();List<Map<String,Object>> candidates=new ArrayList<>();
        for(Table table:tables)if(documents.contains(table.document().id()))for(String line:table.body().split("\\R")){
            Matcher row=ROW.matcher(line),title=Pattern.compile("合同名称[:：]\\s*(.+?)(?=\\s+(?:合同编号|签订日期|签订时间|合同金额|金额)[:：]|[；;]|$)").matcher(line);
            if(!row.find()||!title.find())continue;String candidate=title.group(1).strip();double score=nameSimilarity(name,candidate);if(score<0.85||normalized(name).equals(normalized(candidate)))continue;
            candidates.add(linked("documentId",table.document().id(),"documentHash",table.document().sha256(),"documentVersion",table.document().version(),"page",table.page().number(),"row",row.group(1),"quote",line,"contractName",candidate,"nameSimilarity",score,"scope",table.scope(),"linkScope","SAME_DOCUMENT_SUBMITTED_LIST_CANDIDATE_ONLY","confirmationRequired",true,"countsAsPerformance",false,"doesNotChangeHistoricalScope",true));
        }return candidates.size()<=8?List.copyOf(candidates):List.of();
    }
    static double nameSimilarity(String left,String right){String a=normalized(left),b=normalized(right);if(a.length()<8||b.length()<8||a.length()>300||b.length()>300)return 0;int[] previous=new int[b.length()+1];for(int i=0;i<previous.length;i++)previous[i]=i;for(int i=1;i<=a.length();i++){int[] current=new int[b.length()+1];current[0]=i;for(int j=1;j<=b.length();j++)current[j]=Math.min(Math.min(previous[j]+1,current[j-1]+1),previous[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));previous=current;}return 1d-(double)previous[b.length()]/Math.max(a.length(),b.length());}
    private static List<Map<String,Object>> matchingRows(String body,String wanted){
        record Token(String value,int start,int end){}List<Token> tokens=new ArrayList<>();Matcher word=Pattern.compile("\\S+").matcher(body);
        while(word.find()){String value=normalized(word.group());if(!value.isBlank())tokens.add(new Token(value,word.start(),word.end()));}
        List<Map<String,Object>> found=new ArrayList<>();Set<String> spans=new HashSet<>();
        for(int start=0;start<tokens.size();start++){
            Token first=tokens.get(start);if(first.value().length()<2||!wanted.startsWith(first.value()))continue;
            String remaining=wanted;int end=-1;
            for(int i=start;i<tokens.size()&&i<start+70;i++){
                Token token=tokens.get(i);if(token.value().length()<2||!remaining.startsWith(token.value()))continue;
                remaining=remaining.substring(token.value().length());if(remaining.isEmpty()){end=token.end();break;}
            }
            if(end<0)continue;int from=body.lastIndexOf('\n',first.start())+1,to=body.indexOf('\n',end);if(to<0)to=body.length();String quote=body.substring(from,to);
            Matcher rows=ROW.matcher(quote);if(!rows.find())continue;String row=rows.group(1);if(rows.find())continue;
            Set<String> dates=new LinkedHashSet<>();for(String label:List.of("签订时间","签订日期"))cn.bidcheck.verification.source.DateFieldValues.read(quote,label,1).forEach(date->dates.add(date.toString()));
            Set<String> amounts=new LinkedHashSet<>();Matcher amount=Pattern.compile("(?:合同金额|金额)[:：]\\s*([0-9][0-9,.]*)(万元|元)").matcher(quote);while(amount.find()){java.math.BigDecimal n=RuleEvaluator.normalize(ProofVerifier.decimal(amount.group(1)),amount.group(2));if(n!=null)amounts.add(n.toPlainString());}
            if(spans.add(from+":"+to))found.add(linked("row",row,"quote",quote,"matchMethod","COMPLETE_NAME_ORDERED_WHOLE_TOKENS_IN_ONE_NUMBERED_RECORD","signedDates",List.copyOf(dates),"listedAmounts",List.copyOf(amounts)));
        }return found;
    }
    private static Map<String,Object> mappedScope(Document document,Page page){
        if(!"PAGE".equals(page.locationType())||!printed(page))return Map.of();String nativePage=nativeText(page.text());Matcher section=SECTION.matcher(nativePage);
        if(!section.find())return Map.of();String path=section.group(1),sectionTitle=section.group().strip();if(section.find()||!nativePage.matches("(?s).*[（(]1[）)]\\s*业绩清单.*"))return Map.of();
        List<Page> covers=document.pages().stream().filter(p->"PAGE".equals(p.locationType())).sorted(Comparator.comparingInt(Page::number)).limit(3).filter(p->printed(p)&&nativeText(p.text()).contains("投标文件")&&!navigation(nativeText(p.text()))&&nativeText(p.text()).matches("(?s).*(?:项目编号|招标项目编号)[:：][^\\r\\n]+.*")&&nativeText(p.text()).matches("(?s).*投标人名称[:：]\\s*[（(]盖章[）)][^\\r\\n]+.*")).toList();
        if(covers.size()!=1)return Map.of();Page cover=covers.get(0);List<Map<String,Object>> mappings=new ArrayList<>();
        for(Page nav:document.pages().stream().filter(p->"PAGE".equals(p.locationType())).sorted(Comparator.comparingInt(Page::number)).limit(10).toList()){
            if(!navigation(nativeText(nav.text()))||!printed(nav))continue;String current="";Integer list=null,proof=null;
            for(String line:nativeText(nav.text()).split("\\R")){
                Matcher entry=NAV.matcher(line.strip());if(!entry.matches())continue;String title=entry.group(1).strip();int target=Integer.parseInt(entry.group(2));
                if(title.matches("[0-9]+(?:\\.[0-9]+)+\\s*.*")){
                    if(current.equals(path)&&list!=null&&proof!=null)mappings.add(linked("navigationPage",nav.number(),"listPage",list,"proofPage",proof));
                    current=title.replaceFirst("\\s.*$","");list=null;proof=null;
                    if(!title.replaceAll("\\s+","").equals(path+"业绩证明文件")||target!=page.number())current="";
                }else if(current.equals(path)){
                    if(title.matches("[（(]1[）)]\\s*业绩清单"))list=list==null?target:-1;
                    if(title.matches("[（(]2[）)]\\s*证明文件"))proof=proof==null?target:-1;
                }
            }
            if(current.equals(path)&&list!=null&&proof!=null)mappings.add(linked("navigationPage",nav.number(),"listPage",list,"proofPage",proof));
        }
        if(mappings.size()!=1)return Map.of();Map<String,Object> map=mappings.get(0);int first=((Number)map.get("listPage")).intValue(),last=((Number)map.get("proofPage")).intValue();if(first!=page.number()||last<=first||last-first>8)return Map.of();
        for(int n=first;n<=last;n++){final int number=n;List<Page> found=document.pages().stream().filter(p->p.number()==number&&"PAGE".equals(p.locationType())).toList();if(found.size()!=1||!printed(found.get(0)))return Map.of();if(n==last&&!nativeText(found.get(0).text()).matches("(?s)^\\s*[（(]2[）)]\\s*证明文件\\s*.*"))return Map.of();}
        return linked("verifiedCurrent",true,"status","CURRENT_SUBMITTED_PERFORMANCE_LIST","derivedBy","COVER_UNIQUE_NAVIGATION_BODY_AND_NEXT_SIBLING","coverPage",cover.number(),"coverQuote",nativeText(cover.text()),"navigation",map,"sectionTitle",sectionTitle,"listStart",first,"listEnd",last-1,"proofScope","ONLY_SUBMITTED_LIST_NOT_HISTORICAL_CONTRACT_OR_PERSON_SCOPE");
    }
    private static String normalized(String value){return Objects.toString(value,"").replaceAll("[^\\p{L}\\p{N}]","");}
    private static String nativeText(String value){String text=Objects.toString(value,"");int at=text.indexOf("【PDF识别文字】");return at<0?text:text.substring(0,at);}
    private static boolean printed(Page page){List<String> lines=nativeText(page.text()).lines().map(String::strip).filter(s->!s.isBlank()).toList();return !lines.isEmpty()&&lines.get(lines.size()-1).equals(Integer.toString(page.number()));}
    private static boolean navigation(String text){return clean(text).startsWith("目录")||text.lines().filter(line->line.matches(".*[.．…·]{3,}\\s*[0-9]+\\s*")).count()>=2;}
}
