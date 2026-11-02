package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.retrieval.*;
import cn.bidcheck.verification.source.VisualObservationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Source IDs include immutable document identity and exact character range. Word blocks remain Word blocks. */
final class EvidenceCorpus {
    private static final Logger LOG=LoggerFactory.getLogger(EvidenceCorpus.class);
    private static final Pattern RETRIEVAL_ANCHOR=Pattern.compile("p\\d+-c(\\d+)"),SOURCE_RANGE=Pattern.compile("chars:(\\d+)-(\\d+)");
    private static final Pattern INCOMPLETE=Pattern.compile("(?:OCR未完成|OCR_DEFERRED|OCR_FAILED|识别未完成|解析未完成|页数超限|内容截断)",Pattern.CASE_INSENSITIVE);
    private static final Pattern OCR_ANOMALY=Pattern.compile("\uFFFD|锟斤拷|烫烫烫|(?:\\?){4,}|[\\uE000-\\uF8FF]");
    final List<Document> documents;final List<Source> all=new ArrayList<>();final Map<String,Source> byId=new CertificateRecordBindings.SourceIndex();
    final Map<String,Page> pages=new HashMap<>();
    final Map<String,DocumentSubjectScope> documentScopes=new HashMap<>();
    final List<Map<String,Object>> visualAdmission=new ArrayList<>();
    EvidenceCorpus(List<Document> documents){this.documents=List.copyOf(documents);for(Document doc:documents){DocumentSubjectScope subjectScope=new DocumentSubjectScope(doc);documentScopes.put(doc.id(),subjectScope);boolean historical=false;for(Page page:doc.pages()){
        String text=Objects.toString(page.text(),""),opening=text.substring(0,Math.min(180,text.length()));
        ((CertificateRecordBindings.SourceIndex)byId).registerPage(doc.id(),page.number(),page.locationType(),text);
        if(opening.matches("(?s).*(?:类似项目业绩|历史项目|已完成项目|合同协议书).*"))historical=true;
        if(opening.matches("(?s).*(?:拟投入本项目|本项目人员配置|投标函|联合体协议书|施工组织设计|设计方案).*"))historical=false;
        boolean index=opening.replaceAll("\\s+","").matches("(?s).*(?:^|[一二三四五六七八九十0-9、.])(?:目录|评审索引表).* ")||text.lines().filter(x->x.matches(".*(?:\\.{3,}|…{2,}|·{3,})\\s*\\d+\\s*")).count()>=2||clean(opening).startsWith("目录");
        pages.put(pageKey(doc.id(),page.number(),page.locationType()),page);String parserFingerprint=SharedFactReuse.parserFingerprint(page);
        for(int start=0;start<text.length();){int end=Math.min(text.length(),start+1400);if(end<text.length()){int nl=text.lastIndexOf('\n',end);if(nl>start+900)end=nl+1;}
            String quote=text.substring(start,end);String id="S"+identity(doc.id()+doc.sha256()+doc.version()+page.number()+page.locationType()+parserFingerprint+start+end).substring(0,20);
            Evidence evidence=new Evidence(doc.id(),doc.name(),page.number(),page.locationType(),quote,"chars:"+start+"-"+end,page.parser()!=null&&page.parser().contains("OCR")?0:1);
            DocumentSubjectScope.Context sourceScope=subjectScope.context(page,start,end);
            Source source=new Source(id,evidence,opening,index,historical&&!Set.of("CURRENT_PROJECT","CURRENT_BID_SECTION","CURRENT_BID_ATTACHMENT").contains(sourceScope.status()),doc.sha256(),doc.version(),sourceScope);all.add(source);byId.put(id,source);if(end==text.length())break;start=Math.max(start+1,end-160);
        }
    }}}
    static String pageKey(String id,int page,String type){return id+":"+type+":"+page;}
    void addVisualObservations(Object observations){for(Map<String,Object> o:RuleEvaluator.maps(observations)){
        if(!"FIELD".equals(str(o,"type")))continue;
        var assessment=VisualObservationPolicy.assess(o);visualAdmission.add(linked("observationId",str(o,"id"),"assessment",assessment.audit()));
        if(!assessment.valueUsable()||str(o,"value").isBlank()||!(o.get("source") instanceof Map<?,?> origin)||!VisualObservationPolicy.validSourceBounds(o))continue;
        List<?> box=(List<?>)o.get("sourceBbox");
        Document doc=documents.stream().filter(d->d.id().equals(str(o,"documentId"))&&d.sha256().equals(origin.get("documentHash"))).findFirst().orElse(null);if(doc==null)continue;int location=RunSession.number(o.get("location"),RunSession.number(o.get("page"),-1));String type=str(o,"locationType");Page page=doc.pages().stream().filter(p->p.number()==location&&p.locationType().equals(type)).findFirst().orElse(null);if(page==null)continue;
        if(origin.containsKey("documentVersion")&&RunSession.number(origin.get("documentVersion"),-1)!=doc.version()||origin.containsKey("documentId")&&!doc.id().equals(origin.get("documentId"))||origin.containsKey("page")&&RunSession.number(origin.get("page"),-1)!=location)continue;
        String image=str(o,"imageHash");if(image.isBlank()||!image.equals(origin.get("renderedSha256")))continue;String quote=str(o,"label")+"："+str(o,"value");String id="V"+identity(doc.sha256()+location+image+quote).substring(0,20);
        // A clear transcription is not an appointment or certificate-owner edge. Preserve the server
        // document boundary, but do not synthesize a row by concatenating independent visual fields.
        DocumentSubjectScope.Context outer=documentScopes.get(doc.id()).context(page,0,Objects.toString(page.text(),"").length());
        DocumentSubjectScope.Context scope=new DocumentSubjectScope.Context(outer.blocked()?outer.status():"VISUAL_FIELD_ONLY",outer.projectName(),outer.anchor(),List.of());
        Source source=new Source(id,new Evidence(doc.id(),doc.name(),location,type,quote,"image:"+image+":"+box,0),"经定位的原图字段观察；只证明文字，不证明主体关系",false,outer.blocked(),doc.sha256(),doc.version(),scope);if(!byId.containsKey(id)){all.add(source);byId.put(id,source);}
    }}
    List<Source> select(List<Rule> rules,List<Fact> facts,List<Source> prior,int round,HybridRetriever retriever,RunSession session)throws InterruptedIOException{
        return select(rules,facts,prior,round,retriever,session,false,rules);
    }
    List<Source> selectForAgent(List<Rule> rules,List<Fact> facts,HybridRetriever retriever,RunSession session)throws InterruptedIOException{
        return selectForAgent(rules,rules,facts,retriever,session);
    }
    List<Source> selectForAgent(List<Rule> searchRules,List<Rule> targetRules,List<Fact> facts,HybridRetriever retriever,RunSession session)throws InterruptedIOException{
        return select(searchRules,facts,List.of(),0,retriever,session,true,targetRules);
    }
    private List<Source> select(List<Rule> rules,List<Fact> facts,List<Source> prior,int round,HybridRetriever retriever,RunSession session,boolean byPage,List<Rule> anchorRules)throws InterruptedIOException{
        LinkedHashSet<Source> chosen=new LinkedHashSet<>();Map<Source,Integer> ranking=new HashMap<>();Map<Source,Integer> hitRanks=new HashMap<>();Map<String,Integer> pageRanks=new HashMap<>();Set<String> materialBodyPages=new LinkedHashSet<>();
        for(Rule r:rules){List<String> sections=r.predicate().equals("SECTION")?List.of(r.expected()):SourceScoringRules.programOnly(r)||Set.of("QUALITY","QUALITY_ADVISORY").contains(r.predicate())?qualitySections(r):r.predicate().equals("TEXT_ANOMALY")?List.of(str(r.parameters(),"textScope")):List.of();for(String section:sections)if(!wholeScope(section))chosen.addAll(SectionCoverage.locate(section,this).sources());}
        for(Rule rule:rules)if(Set.of("MATERIAL","SECTION").contains(rule.predicate())){
            String title=rule.expected().isBlank()?rule.field():rule.expected();List<Source> body=materialBodyAnchors(title);chosen.addAll(body);
            // At most four independent body pages reserve space; directories remain locators.
            for(Source source:body){String key=pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType());if(materialBodyPages.size()<4||materialBodyPages.contains(key))materialBodyPages.add(key);}
        }
        for(Rule rule:rules){String query=ruleQuery(rule);
            query+=" "+String.join(" ",resolvedNames(List.of(rule),facts));
            List<String> keys=keywords(query);for(Source s:all){int score=0;String q=s.evidence().quote();for(String k:keys)if(q.contains(k))score+=k.length();if(!s.index())score+=2;if(Set.of("PERSON_ROLE","PERSON_COUNT").contains(rule.predicate())&&!s.historical())score+=2;if(score>3)ranking.merge(s,score,Integer::sum);}
        }
        Set<Source> retrievedSources=new LinkedHashSet<>();int retrievalHits=0,unmappedHits=0;
        if(retriever!=null&&retriever.enabled()){
            String query=rules.stream().map(EvidenceCorpus::ruleQuery).reduce("",(a,b)->a+"\n"+b);
            query+="\n"+String.join(" ",resolvedNames(rules,facts));
            for(Document doc:documents){
                RetrievalResult result=retriever.search(doc,query,16);Set<Source> mapped=new LinkedHashSet<>();int missed=0;
                int rank=0;for(Evidence hit:result.hits()){rank++;
                    List<Source> matches=mapRetrievalHit(hit);if(matches.isEmpty())missed++;
                    for(Source source:matches){hitRanks.merge(source,rank,Math::min);pageRanks.merge(pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType()),rank,Math::min);mapped.add(source);}
                }
                retrievedSources.addAll(mapped);retrievalHits+=result.hits().size();unmappedHits+=missed;
                session.record("RETRIEVE_"+round+"_"+doc.id(),Map.of("query",query),linked("mode",result.mode(),"diagnostics",result.diagnostics(),
                    "documentId",doc.id(),"query",query,"round",round,"returnedHits",result.hits().size(),"mappedSourceCount",mapped.size(),"unmappedHitCount",missed));
                LOG.info("bidcheck.atomic_retrieval scope={} runId={} checkpointId={} round={} documentId={} mode={} hits={} mappedSources={} unmappedHits={} failureCode={}",
                    session.scope,str(session.context,"sourceRunId"),str(session.context,"checkpointId"),round,doc.id(),result.mode(),result.hits().size(),mapped.size(),missed,str(result.diagnostics(),"failureCode"));
            }
        }
        // Verified facts anchor the next read; high-ranked hybrid hits keep their rank.
        // Overlapping chunks on one page never accumulate an artificial retrieval bonus.
        Set<String> pinned=new LinkedHashSet<>();Set<String> pinnedPages=new LinkedHashSet<>();
        for(Fact fact:facts)if(Set.of("PERSON_ROLE","RELATION").contains(fact.kind())&&"CURRENT_PROJECT".equals(fact.scope())&&AgentInvestigationScope.relevant(fact,anchorRules,facts,this))for(String id:fact.sourceIds()){
            Source source=byId.get(id);if(source==null)continue;String key=pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType());
            if(pinnedPages.contains(key)||pinnedPages.size()<2){pinnedPages.add(key);pinned.add(id);}
        }
        if(round>0){for(Source source:prior){int i=all.indexOf(source);if(i>=0)for(int j=Math.max(0,i-round);j<=Math.min(all.size()-1,i+round);j++)if(all.get(j).evidence().documentId().equals(source.evidence().documentId()))ranking.merge(all.get(j),10,Integer::sum);}}
        Set<Source> candidates=new LinkedHashSet<>(chosen);candidates.addAll(ranking.keySet());candidates.addAll(retrievedSources);
        for(String id:pinned)if(byId.containsKey(id))candidates.add(byId.get(id));
        List<Source> ordered=candidates.stream().sorted(Comparator.<Source,Boolean>comparing(s->!materialBodyPages.isEmpty()&&s.index())
            .thenComparing(s->pageRanks.getOrDefault(pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()),Integer.MAX_VALUE)!=1)
            .thenComparing(s->!pinned.contains(s.id()))
            .thenComparing(s->!materialBodyPages.contains(pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType())))
            .thenComparingInt(s->pageRanks.getOrDefault(pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()),Integer.MAX_VALUE))
            .thenComparingInt(s->hitRanks.getOrDefault(s,Integer.MAX_VALUE))
            .thenComparing(Comparator.comparingInt((Source s)->ranking.getOrDefault(s,0)).reversed()).thenComparing(Source::id)).toList();
        List<Source> selected=new ArrayList<>();Set<String> selectedPages=new LinkedHashSet<>();List<Map<String,Object>> candidateDetails=new ArrayList<>();int chars=0,position=0;
        for(Source source:ordered){position++;int rank=hitRanks.getOrDefault(source,0),pageRank=pageRanks.getOrDefault(pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType()),0);
            String key=pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType());
            // Agent search selects page representatives; expansion owns its character budget.
            // A long high-ranked page must not consume the old 24-chunk selection allowance.
            String exclusion=byPage?(selectedPages.contains(key)?"PAGE_REPRESENTED":selectedPages.size()>=8?"PAGE_LIMIT":""):
                selected.size()>=24?"SOURCE_LIMIT":chars+source.evidence().quote().length()>32_000?"CHAR_LIMIT":"";
            if(exclusion.isEmpty()){selected.add(source);selectedPages.add(key);chars+=source.evidence().quote().length();}
            candidateDetails.add(linked("sourceId",source.id(),"documentId",source.evidence().documentId(),"page",source.evidence().page(),"locationType",source.evidence().locationType(),
                "position",position,"retrievalRank",rank,"pageRetrievalRank",pageRank,"keywordScore",ranking.getOrDefault(source,0),"retrievalScore",pageRank==0?0:1.0/(60+pageRank),
                "pinned",pinned.contains(source.id()),"materialBodyAnchor",materialBodyPages.contains(key),"navigationOnly",source.index(),"selected",exclusion.isEmpty(),"exclusionReason",exclusion));
        }
        String route=retriever==null?"RETRIEVER_NOT_AVAILABLE":retriever.enabled()?"HYBRID_REQUESTED":"VECTOR_DISABLED_LOCAL_SELECTION";
        long selectedRetrieved=selected.stream().filter(retrievedSources::contains).count();
        session.record("RETRIEVAL_SELECTION_"+round,Map.of("round",round),linked("route",route,"returnedHits",retrievalHits,"mappedSourceCount",retrievedSources.size(),
            "unmappedHitCount",unmappedHits,"selectedSourceCount",selected.size(),"selectedRetrievedSourceCount",selectedRetrieved,"selectedChars",chars,"queryContext",rules.stream().map(EvidenceCorpus::ruleQuery).toList(),"candidateDetails",candidateDetails,"selectionUnit",byPage?"PAGE":"SOURCE","selectedPages",selectedPages.size(),"materialBodyAnchorPages",materialBodyPages,"rankingPolicy","BODY_BEFORE_DIRECTORY_THEN_HYBRID_TOP_HIT_RELEVANT_ANCHORS_AND_PAGE_RANK"));
        LOG.info("bidcheck.source_selection scope={} runId={} checkpointId={} round={} route={} rules={} hits={} mappedSources={} selectedRetrievedSources={} selectedSources={} selectedChars={}",
            session.scope,str(session.context,"sourceRunId"),str(session.context,"checkpointId"),round,route,rules.size(),retrievalHits,retrievedSources.size(),selectedRetrieved,selected.size(),chars);
        return selected;
    }
    /** A retrieval anchor is an exact original heading plus substantive body, never a PASS receipt. */
    List<Source> materialBodyAnchors(String title){
        if(title==null||title.length()<2||title.length()>80)return List.of();var extent=SectionCoverage.locate(title,this);if(!extent.sources().isEmpty())return extent.sources().stream().filter(s->!s.index()).toList();
        List<Source> result=new ArrayList<>();for(Document document:documents){if(!document.role().equals("BID"))continue;for(Page page:document.pages()){
            List<Source> original=all.stream().filter(s->s.evidence().documentId().equals(document.id())&&s.evidence().page()==page.number()&&s.evidence().locationType().equals(page.locationType())&&!s.id().startsWith("V")).toList();if(original.isEmpty()||original.stream().anyMatch(Source::index))continue;
            Matcher line=Pattern.compile("(?m)^.*$").matcher(page.text());while(line.find()){String heading=line.group().strip().replaceFirst("^#{1,6}\\s*","");if(!SectionCoverage.strip(heading).equals(title)||heading.matches(".*(?:详见|见附件|拟提供|承诺提供|索引|目录|清单|\\.{3,}|…{2,}).*"))continue;
                int start=line.end(),end=page.text().length();Matcher next=Pattern.compile("(?m)^.*$").matcher(page.text());next.region(start,end);while(next.find())if(SectionCoverage.level(next.group().strip())>0){end=next.start();break;}
                String body=page.text().substring(start,end);if(clean(body).length()<80||body.matches("(?s)^\\s*[（(]?填写说明.*"))continue;
                int headingStart=line.start(),bodyEnd=end;result.addAll(original.stream().filter(s->{int[] range=SectionCoverage.range(s);return range[0]<bodyEnd&&range[1]>headingStart;}).toList());break;
            }
        }}return result.stream().distinct().toList();
    }
    /** Retriever and proof corpus use different overlap/newline boundaries. Map the verified range,
     * not just the first 60 characters, which could otherwise drop the useful end of a hit. */
    List<Source> mapRetrievalHit(Evidence hit){
        if(hit==null||hit.quote()==null||hit.quote().isBlank())return List.of();
        Page page=pages.get(pageKey(hit.documentId(),hit.page(),hit.locationType()));if(page==null)return List.of();
        String text=Objects.toString(page.text(),"");Matcher anchor=RETRIEVAL_ANCHOR.matcher(Objects.toString(hit.anchor(),""));
        int start=-1;if(anchor.matches())try{start=Integer.parseInt(anchor.group(1));}catch(NumberFormatException ignored){}
        if(start<0||start>text.length()||!text.startsWith(hit.quote(),start))return List.of();
        int hitStart=start,hitEnd=start+hit.quote().length();
        return all.stream().filter(source->source.evidence().documentId().equals(hit.documentId())&&source.evidence().page()==hit.page()
            &&source.evidence().locationType().equals(hit.locationType())).filter(source->{
                Matcher range=SOURCE_RANGE.matcher(source.evidence().anchor());if(!range.matches())return false;
                int from=Integer.parseInt(range.group(1)),to=Integer.parseInt(range.group(2));return Math.max(from,hitStart)<Math.min(to,hitEnd);
            }).toList();
    }
    static List<String> keywords(String text){LinkedHashSet<String> words=new LinkedHashSet<>();Matcher m=Pattern.compile("[A-Za-z0-9_-]{3,}|[\\p{IsHan}]{2,12}").matcher(text);while(m.find()){String w=m.group();words.add(w);if(w.length()>5)for(int i=0;i+3<=w.length();i+=2)words.add(w.substring(i,i+3));}return words.stream().limit(80).toList();}
    private static String ruleQuery(Rule rule){
        String aliases=rule.field().equals("creditCode")?"统一社会信用代码 纳税人识别号 营业执照 企业名称 单位名称":"";
        return rule.subject()+" "+rule.field()+" "+aliases+" "+rule.expected()+" "+rule.description()+" "+str(rule.parameters(),"employer")+" "+str(rule.parameters(),"lot")+" "+str(rule.parameters(),"category")+" "+str(rule.parameters(),"totalField")+" "+String.join(" ",ProofVerifier.strings(rule.parameters().get("componentFields")))+" "+str(rule.parameters(),"leftRole")+" "+str(rule.parameters(),"rightRole")+" "+str(rule.parameters(),"textScope")+" "+str(rule.parameters(),"materialType")+" "+String.join(" ",ProofVerifier.strings(rule.parameters().get("dimensions")))+" "+String.join(" ",PersonnelRoles.queryTerms(rule.subject()));
    }
    private List<String> resolvedNames(List<Rule> rules,List<Fact> facts){
        Set<String> names=new LinkedHashSet<>();
        for(Rule rule:rules)for(String selector:List.of(rule.subject(),str(rule.parameters(),"employer")))
            if(!selector.isBlank())names.addAll(DomainBindings.subjects(selector,facts,this));
        return names.stream().limit(8).toList();
    }
    List<Map<String,Object>> inputs(List<Source> sources){return sources.stream().map(s->linked("id",s.id(),"documentId",s.evidence().documentId(),"locationType",s.evidence().locationType(),"location",s.evidence().page(),"quote",s.evidence().quote(),"opening",s.opening(),"navigationOnly",s.index(),"historicalContext",s.historical(),"documentScope",s.scopeContext().audit())).toList();}
    List<Fact> nativeFacts(List<Rule> rules,List<Source> selected){List<Fact> result=new ArrayList<>(EnterpriseSubjectBindings.extract(rules,all));result.addAll(SocialInsuranceEvidenceRecords.extract(rules,this,selected));for(Rule r:rules){
        if(r.predicate().equals("TEXT_ANOMALY")){result.addAll(textAnomalyScan(r).facts());continue;}
        if(r.predicate().equals("QUALITY_ADVISORY")){result.addAll(qualityAdvisoryScan(r).facts());continue;}
        result.addAll(NativeCertificateFacts.extract(r,this,selected));
        List<Fact> identifiers=NativeCompanyCodeFacts.extract(r,this,selected);result.addAll(identifiers);
        if(r.predicate().equals("SAME_FIELD")&&Set.of("creditCode","统一社会信用代码","纳税人识别号").contains(r.field()))LOG.info("bidcheck.native_identifier ruleId={} field={} selectedSources={} facts={} bindings={}",r.id(),r.field(),selected.size(),identifiers.size(),identifiers.stream().map(f->linked("factId",f.id(),"subject",f.subject(),"sourceIds",f.sourceIds(),"binding",str(f.attributes(),"bindingMethod"))).toList());
        for(Source s:selected){if(s.index())continue;String text=s.evidence().quote();
            if(s.id().startsWith("V")&&s.evidence().anchor().startsWith("image:")){
                int split=text.indexOf('：');if(split>0){String label=text.substring(0,split),value=text.substring(split+1);
                    result.add(new Fact("N"+identity(s.id()+"literal-visual-field-v1").substring(0,20),"FIELD","",label,value,value,"","","UNRESOLVED",List.of(s.id()),Map.of("proofLevel","VISIBLE_FIELD_ONLY","relationVerified",false)));}
                continue;
            }
            result.addAll(NativeCertificateFacts.extract(r,s));
            for(String selector:AgentRuleSubjects.selectors(r))if(PersonnelRoles.personRole(selector))result.addAll(DocumentSubjectScope.nativeAppointments(s,selector));
            if(Set.of("SECTION","MATERIAL").contains(r.predicate())){String name=!r.expected().isBlank()?r.expected():r.field();if(name.length()>80||name.length()<2||!r.subject().isBlank())continue;
                int at=text.indexOf(name);if(at<0)continue;int line=text.lastIndexOf('\n',at)+1,end=text.indexOf('\n',at);if(end<0)end=text.length();String heading=text.substring(line,end).strip();String body=text.substring(end);
                if(heading.length()>name.length()+30||heading.matches(".*(?:\\.{3,}|…{2,}|详见|见附件|承诺提供|拟提供).*"))continue;
                // A bidder-authored index/checklist is only a locator. Text that follows it may
                // belong to the next section and must never be promoted to the requested material.
                if(heading.matches(".*(?:索引|目录|清单|对应页码|材料列表|附件列表|内容提要).*"))continue;
                if(clean(body).length()<40||body.matches("(?s)^\\s*[（(]?填写说明.*"))continue;
                result.add(new Fact("N"+identity(r.id()+s.id()).substring(0,16),r.predicate(),"",r.field(),name,name,"","",s.historical()?"HISTORICAL_PROJECT":"CURRENT_PROJECT",List.of(s.id()),Map.of("contentPresent",true,"fullSectionRead",false)));
            }
            if(r.predicate().equals("DATE_RELATION")){
                for(String side:List.of("left","right")){String role=str(r.parameters(),side+"Role"),documentRole=str(r.parameters(),side+"DocumentRole");if(role.isBlank()||"TENDER".equals(documentRole))continue;
                    Matcher date=Pattern.compile(Pattern.quote(role)+"\\s*(?:[:：]|为|是)?\\s*((?:20)?[0-9]{2}[\u5e74./-][01]?[0-9][\u6708./-][0-3]?[0-9]日?)").matcher(text);int occurrence=0;
                    while(date.find()){String value=date.group(1);result.add(new Fact("N"+identity(r.id()+s.id()+role+(++occurrence)+value).substring(0,16),"FIELD",r.subject(),role,value,value,"","",s.historical()?"HISTORICAL_PROJECT":"CURRENT_PROJECT",List.of(s.id()),Map.of("date",value,"dateRole",role,"documentRole","BID","relationKey",str(r.parameters(),"relationKey"))));}
                }
            }
            if(r.predicate().equals("SAME_FIELD")&&!r.field().isBlank()){
                if(Set.of("creditCode","统一社会信用代码","纳税人识别号").contains(r.field()))continue;
                String label=switch(r.field()){case "projectName"->"项目名称";case "projectNumber"->"(?:项目编号|招标编号)";default->Pattern.quote(r.field());};
                Matcher m=Pattern.compile(label+"\\s*[:：]\\s*([^\\n|\\t;；]{1,160})").matcher(text);while(m.find())result.add(new Fact("N"+identity(s.id()+m.start()).substring(0,16),"FIELD",r.subject(),r.field(),m.group(1).strip(),m.group(1).strip(),"","",s.historical()?"HISTORICAL_PROJECT":"CURRENT_PROJECT",List.of(s.id()),Map.of()));
            }
        }
    }return result;}

    /** Program-owned scan receipt.  Model output can never manufacture one of these facts. */
    record ProgramScan(List<Fact> facts,boolean scopeLocated,boolean coverageComplete,List<String> covered,
                       List<String> missing,List<String> sourceIds,String reason){
        ProgramScan{facts=List.copyOf(facts);covered=List.copyOf(covered);missing=List.copyOf(missing);sourceIds=List.copyOf(sourceIds);}
    }
    private record ScopeSources(List<Source> sources,boolean located,boolean complete,String reason){
        ScopeSources{sources=List.copyOf(sources);}
    }
    private record TypoRule(String wrong,String correct){}

    ProgramScan textAnomalyScan(Rule rule){
        String scope=str(rule.parameters(),"textScope");ScopeSources range=scopedSources(scope);
        LinkedHashSet<String> requested=new LinkedHashSet<>();for(String type:ProofVerifier.strings(rule.parameters().get("anomalyTypes")))requested.add(type.strip().toUpperCase(Locale.ROOT));
        if(requested.isEmpty())requested.addAll(List.of("TYPO","TRUNCATED","OCR_SUSPECT"));
        List<TypoRule> dictionary=typoRules(rule.parameters().get("typoDictionary"));LinkedHashSet<String> missing=new LinkedHashSet<>();
        for(String type:requested)if(!Set.of("TYPO","TRUNCATED","OCR_SUSPECT").contains(type)||(type.equals("TYPO")&&dictionary.isEmpty()))missing.add(type);
        LinkedHashMap<String,Fact> found=new LinkedHashMap<>();
        for(Source source:range.sources()){
            String text=source.evidence().quote();
            if(requested.contains("TYPO"))for(TypoRule entry:dictionary){int at=text.indexOf(entry.wrong());if(at<0)continue;String key=source.id()+"|TYPO|"+entry.wrong()+"|"+at;
                found.putIfAbsent(key,programFact(rule,source,"TEXT_ANOMALY","TYPO",entry.wrong(),"TYPO",Map.of("detector","DICTIONARY","correct",entry.correct(),"textScope",scope,"offset",at)));}
            if(requested.contains("TRUNCATED"))for(String marker:List.of("[内容截断]","[TEXT_TRUNCATED]")){int at=text.indexOf(marker);if(at<0)continue;String key=source.id()+"|TRUNCATED|"+at;
                found.putIfAbsent(key,programFact(rule,source,"TEXT_ANOMALY","TRUNCATED",marker,"TRUNCATED",Map.of("detector","TERMINAL_MARKER","textScope",scope,"offset",at)));}
            if(requested.contains("OCR_SUSPECT")){Matcher matcher=OCR_ANOMALY.matcher(text);while(matcher.find()){String raw=matcher.group();String key=source.id()+"|OCR_SUSPECT|"+matcher.start()+"|"+raw;
                found.putIfAbsent(key,programFact(rule,source,"TEXT_ANOMALY","OCR_SUSPECT",raw,"OCR_SUSPECT",Map.of("detector","CHARACTER_PATTERN","textScope",scope,"offset",matcher.start())));}}
        }
        boolean complete=range.complete()&&missing.isEmpty();List<String> ids=range.sources().stream().map(Source::id).distinct().toList();
        if(complete&&!ids.isEmpty()&&found.isEmpty()){
            Source source=range.sources().get(0);String fingerprint=identity(String.join("|",ids)+requested+dictionary);
            Fact receipt=programFact(rule,source,"TEXT_ANOMALY","SCAN_RECEIPT","范围内未发现已配置异常","CLEAR",Map.of("detector","PROGRAM_SCAN","textScope",scope,"anomalyTypes",List.copyOf(requested),"sourceCount",ids.size(),"coverageFingerprint",fingerprint));
            found.putIfAbsent("receipt",receipt);
        }
        return new ProgramScan(new ArrayList<>(found.values()),range.located(),complete,List.copyOf(requested),List.copyOf(missing),ids,range.reason());
    }

    ProgramScan qualityAdvisoryScan(Rule rule){
        String material=str(rule.parameters(),"materialType");if(material.isBlank()){List<String> sections=qualitySections(rule);if(!sections.isEmpty())material=sections.get(0);}
        final String materialType=material;
        List<String> dimensions=ProofVerifier.strings(rule.parameters().get("dimensions")).stream().filter(value->!value.isBlank()).distinct().toList();
        ScopeSources range=scopedSources(materialType);List<Fact> found=new ArrayList<>();LinkedHashSet<String> covered=new LinkedHashSet<>();
        if(range.located()&&!range.sources().isEmpty()){
            Source body=range.sources().stream().filter(source->contains(source.evidence().quote(),materialType)).findFirst().orElse(range.sources().get(0));
            String raw=contains(body.evidence().quote(),materialType)?materialType:body.evidence().quote().substring(0,Math.min(80,body.evidence().quote().length()));
            found.add(programFact(rule,body,"QUALITY_ADVISORY","MATERIAL_BODY",raw,"PRESENT",Map.of("materialType",materialType,"bodyPresent",substantiveBody(range.sources(),materialType),"sourceCount",range.sources().size())));
            for(String dimension:dimensions){Source source=dimensionSource(range.sources(),dimension,dimensions);if(source==null)continue;covered.add(dimension);
                found.add(programFact(rule,source,"QUALITY_ADVISORY","DIMENSION",dimension,dimension,Map.of("materialType",materialType,"dimension",dimension,"coverage","SUBSTANTIVE_BODY")));}
        }
        List<String> missing=dimensions.stream().filter(value->!covered.contains(value)).toList();boolean body=substantiveBody(range.sources(),materialType);
        boolean complete=range.complete()&&body&&!dimensions.isEmpty();String reason=!range.located()?range.reason():!range.complete()?range.reason():!body?"材料范围内只有标题或提纲，未形成可核正文":dimensions.isEmpty()?"质量维度未配置":"材料正文及维度已按程序扫描";
        return new ProgramScan(found,range.located(),complete,List.copyOf(covered),missing,range.sources().stream().map(Source::id).distinct().toList(),reason);
    }

    private ScopeSources scopedSources(String scope){
        List<Source> current=currentSources();if(wholeScope(scope))return new ScopeSources(current,!current.isEmpty(),!current.isEmpty()&&documentsComplete(current),current.isEmpty()?"未发现当前项目正文来源":"当前项目非目录正文已完整扫描");
        if(scope==null||scope.isBlank())return new ScopeSources(List.of(),false,false,"检查范围未明确");
        ScopeSources best=null;
        for(Document document:documents){EvidenceCorpus local=new EvidenceCorpus(List.of(document));SectionCoverage.Extent extent=SectionCoverage.locate(scope,local);if(extent.sources().isEmpty())continue;
            List<Source> mapped=extent.sources().stream().map(Source::id).map(byId::get).filter(Objects::nonNull).filter(this::currentSource).toList();if(mapped.isEmpty())continue;
            ScopeSources candidate=new ScopeSources(mapped,true,extent.complete()&&documentsComplete(mapped),extent.reason());if(candidate.complete())return candidate;if(best==null)best=candidate;
        }
        if(best!=null)return best;
        List<Source> named=current.stream().filter(source->contains(source.opening(),scope)||contains(source.evidence().quote(),scope)).toList();
        if(named.isEmpty())return new ScopeSources(List.of(),false,false,"尚未在当前项目正文定位检查范围“"+scope+"”");
        Set<String> namedDocs=named.stream().map(source->source.evidence().documentId()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Source> documentScoped=current.stream().filter(source->namedDocs.contains(source.evidence().documentId())&&documents.stream().anyMatch(document->document.id().equals(source.evidence().documentId())&&contains(document.name(),scope))).toList();
        if(!documentScoped.isEmpty())return new ScopeSources(documentScoped,true,documentsComplete(documentScoped),"以明确文件名定位检查范围");
        return new ScopeSources(named,true,false,"已命中范围名称，但未形成可闭合的正文边界");
    }

    private List<Source> currentSources(){return all.stream().filter(this::currentSource).toList();}
    private boolean currentSource(Source source){return !source.id().startsWith("V")&&!source.index()&&!source.historical()&&!source.scopeContext().blocked();}
    private boolean documentsComplete(List<Source> sources){Set<String> ids=new HashSet<>(sources.stream().map(source->source.evidence().documentId()).toList());if(ids.isEmpty())return false;
        for(Document document:documents)if(ids.contains(document.id())){
            if(document.pages()==null||document.pages().isEmpty())return false;
            if(document.warnings()!=null&&document.warnings().stream().filter(Objects::nonNull).anyMatch(warning->INCOMPLETE.matcher(warning).find()))return false;
            for(Page page:document.pages()){String warning=Objects.toString(page.warning(),""),text=Objects.toString(page.text(),"");if(INCOMPLETE.matcher(warning).find()||Pattern.compile("\\[(?:OCR未完成|OCR_DEFERRED|OCR_FAILED|识别未完成|解析未完成|页数超限|内容截断)\\]",Pattern.CASE_INSENSITIVE).matcher(text).find())return false;}
        }return true;
    }
    private static boolean wholeScope(String scope){return scope==null||scope.isBlank()||List.of("全文","全部","整份","全部内容").stream().anyMatch(scope::contains);}
    private static List<String> qualitySections(Rule rule){List<String> sections=ProofVerifier.strings(rule.parameters().get("sections"));if(!sections.isEmpty())return sections;String material=str(rule.parameters(),"materialType");return material.isBlank()?List.of():List.of(material);}
    private static List<TypoRule> typoRules(Object value){if(!(value instanceof Collection<?> values))return List.of();List<TypoRule> result=new ArrayList<>();for(Object item:values){String[] parts=Objects.toString(item,"").strip().split("(?:=>|->|→)",2);if(parts.length!=2)continue;String wrong=parts[0].strip(),correct=parts[1].strip();if(!wrong.isBlank()&&!correct.isBlank()&&!wrong.equals(correct)&&wrong.length()<=40&&correct.length()<=40)result.add(new TypoRule(wrong,correct));}return List.copyOf(result);}
    private static boolean substantiveBody(List<Source> sources,String material){String text=sources.stream().map(source->source.evidence().quote()).collect(java.util.stream.Collectors.joining("\n"));String body=clean(text).replace(clean(material),"");return body.length()>=80;}
    private static Source dimensionSource(List<Source> sources,String dimension,List<String> dimensions){for(Source source:sources){String text=source.evidence().quote();for(String line:text.split("\\R")){if(!contains(line,dimension))continue;long listed=dimensions.stream().filter(value->contains(line,value)).count();String remainder=clean(line).replace(clean(dimension),"");if(listed>1&&remainder.length()<35)continue;if(remainder.length()>=24)return source;}
            int at=text.indexOf(dimension);if(at>=0){int end=Math.min(text.length(),at+dimension.length()+360);String following=text.substring(at+dimension.length(),end);String[] lines=following.split("\\R",-1);StringBuilder body=new StringBuilder();for(int i=0;i<Math.min(lines.length,7);i++){String line=lines[i].strip();if(i>0&&SectionCoverage.level(line)>0)break;body.append(line);}if(clean(body.toString()).length()>=30)return source;}}
        return null;}
    private static Fact programFact(Rule rule,Source source,String kind,String field,String raw,String value,Map<String,Object> attributes){Map<String,Object> audit=new LinkedHashMap<>(attributes);audit.put("derivedBy","SERVER_PROGRAM_RULE");audit.put("ruleId",rule.id());return new Fact("N"+identity(rule.id()+source.id()+kind+field+raw+attributes).substring(0,20),kind,rule.subject(),field,raw,value,"","PROGRAM_RULE","CURRENT_PROJECT",List.of(source.id()),Map.copyOf(audit));}
    private static boolean contains(String text,String expected){return !clean(expected).isBlank()&&clean(text).contains(clean(expected));}
}
