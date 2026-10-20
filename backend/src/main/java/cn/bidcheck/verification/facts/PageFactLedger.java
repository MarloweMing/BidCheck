package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.document.PersonCredentialFacts;
import cn.bidcheck.consistency.PersonCredentialConsistency;
import cn.bidcheck.engine.FactExtractor;
import cn.bidcheck.persistence.*;
import java.time.Instant;
import java.util.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;

/**
 * Incremental source ledger. Immutable SQL facts are separate from mutable, page-local current pointers.
 * Adding a page never changes another page's identity. The caller owns the project fence/transaction.
 */
final class PageFactLedger {
    static final String VERSION="page-facts-3-person-information-policy";
    private final Store store;
    private final FactRepository core;
    PageFactLedger(Store store,FactRepository core){this.store=store;this.core=core;}

    Map<String,Object> persistPageFacts(Document doc,Page page,String parserIdentity){
        Document single=single(doc,page);
        List<Fact> facts=new ArrayList<>(new FactExtractor().extract(single));
        facts.addAll(PersonCredentialFacts.analyze(single,page).facts());
        return persistSuppliedPageFacts(doc,page,facts,parserIdentity);
    }

    Map<String,Object> persistSuppliedPageFacts(Document doc,Page page,List<Fact> facts,String parserIdentity){
        requireDocumentPage(doc,page);FactLedgerService.Scope scope=scope(single(doc,page),parserIdentity);
        if(facts==null||facts.size()>2000)throw new IllegalArgumentException("PAGE_FACT_LIMIT");
        List<String> ids=new ArrayList<>();List<Map<String,Object>> rejected=new ArrayList<>();List<Fact> acceptedFacts=new ArrayList<>();int reused=0;
        for(Fact original:facts){interrupted();try{
            if(original==null||original.evidence()==null)throw new IllegalArgumentException("PAGE_FACT_SOURCE_MISSING");
            Evidence ev=original.evidence();
            if(!doc.id().equals(ev.documentId())||ev.page()!=page.number()||!Objects.equals(ev.locationType(),page.locationType()))throw new IllegalArgumentException("PAGE_FACT_WRONG_PAGE");
            ev=FactLedgerService.nativeEvidence(ev,page);
            Map<String,Object> source=FactLedgerService.source(scope,of("id","P1","projectId",doc.projectId(),"documentHash",doc.sha256(),"documentVersion",doc.version(),"parserFingerprint",FactLedgerService.parserFingerprint(page),"evidence",ev));
            Map<String,Object> attrs=of("entityType",Objects.toString(original.entityType(),""),"nativeEntityType",Objects.toString(original.entityType(),""),"nativeEntityKey",Objects.toString(original.entityKey(),""));
            Map<String,Object> fact=FactLedgerService.fact(of("kind","FIELD","subject",original.entityKey(),"field",original.field(),"rawValue",original.rawValue(),"value",original.normalizedValue(),"unit","","basis","","scope","UNRESOLVED","sourceIds",List.of("P1"),"attributes",attrs),Map.of("P1",source));
            Saved saved=save(scope,fact,List.of(source),List.of(),"PAGE_FACT","DETERMINISTIC_PAGE_EXTRACTOR","");
            ids.add(saved.id());acceptedFacts.add(new Fact(original.entityType(),original.entityKey(),original.field(),original.rawValue(),original.normalizedValue(),ev));if(saved.reused())reused++;
        }catch(IllegalArgumentException invalid){rejected.add(of("index",ids.size()+rejected.size()+1,"code",code(invalid)));}}
        String parent=parent(doc,scope.engine),id=pageId(parent,page);
        Map<String,Object> index=of("id",id,"projectId",doc.projectId(),"documentId",doc.id(),"documentVersion",doc.version(),"documentHash",doc.sha256(),"engineIdentity",scope.engine,"parserIdentity",parserIdentity,"page",page.number(),"locationType",page.locationType(),"parserFingerprint",FactLedgerService.parserFingerprint(page),"occurrenceIds",ids.stream().distinct().toList(),"rejected",rejected,"updatedAt",Instant.now().toString());
        store.save("page_fact_index",id,doc.projectId(),parent,index);
        return of("ledgerVersion",VERSION,"page",page.number(),"accepted",ids.stream().distinct().count(),"reused",reused,"rejected",rejected,"occurrenceIds",ids.stream().distinct().toList(),"acceptedDomainFacts",List.copyOf(acceptedFacts),"summary",of("occurrences",ids.stream().distinct().count(),"rejected",rejected.size()),"provisional",true);
    }

    /** Small-window structured results, each field still grounded in its own original page(s). */
    Map<String,Object> persistContextFacts(Document doc,List<Page> window,String parserIdentity,Map<String,Object> batch){
        if(window==null||window.isEmpty()||window.size()>4||window.stream().map(p->p.locationType()+":"+p.number()).distinct().count()!=window.size())throw new IllegalArgumentException("CONTEXT_WINDOW_LIMIT");
        for(Page page:window)requireDocumentPage(doc,page);
        FactLedgerService.Scope scope=scope(new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),doc.version(),doc.sha256(),doc.createdAt(),window,List.of()),parserIdentity);
        List<Page> ordered=window.stream().sorted(Comparator.comparingInt(Page::number)).toList();
        for(int i=1;i<ordered.size();i++)if(!Objects.equals(ordered.get(i-1).locationType(),ordered.get(i).locationType())||ordered.get(i).number()!=ordered.get(i-1).number()+1)throw new IllegalArgumentException("CONTEXT_WINDOW_NOT_CONTIGUOUS");
        if(batch==null||canonical(batch).length()>2_000_000)throw new IllegalArgumentException("CONTEXT_FACT_BATCH_LIMIT");
        List<Map<String,Object>> rawSources=strictMaps(batch.get("sources")),rawFacts=strictMaps(batch.get("facts"));
        if(rawSources.size()>128||rawFacts.size()>120)throw new IllegalArgumentException("CONTEXT_FACT_BATCH_LIMIT");
        Map<String,Map<String,Object>> sources=new LinkedHashMap<>();List<Map<String,Object>> rejected=new ArrayList<>();
        Set<String> invalidIds=new HashSet<>();
        for(Map<String,Object> raw:rawSources){String id=text(raw,"id");try{
            if(id.isBlank()||id.length()>200||sources.containsKey(id)||invalidIds.contains(id))throw new IllegalArgumentException("CONTEXT_SOURCE_ID_INVALID");
            Map<String,Object> source=FactLedgerService.source(scope,raw),ev=map(source.get("evidence"));
            if(window.stream().noneMatch(p->p.number()==integer(ev.get("page"),-1)&&p.locationType().equals(text(ev,"locationType"))))throw new IllegalArgumentException("CONTEXT_SOURCE_OUTSIDE_WINDOW");
            sources.put(id,source);
        }catch(IllegalArgumentException invalid){sources.remove(id);invalidIds.add(id);rejected.add(of("sourceId",id,"code",code(invalid)));}}
        List<String> ids=new ArrayList<>();int reused=0;List<Map<String,Object>> layout=maps(batch.get("layoutCells"));
        if(layout.size()>300)throw new IllegalArgumentException("CONTEXT_LAYOUT_LIMIT");
        for(Map<String,Object> raw:rawFacts){interrupted();try{
            Map<String,Object> fact=FactLedgerService.fact(raw,sources);
            List<Map<String,Object>> refs=strings(fact.get("sourceIds")).stream().map(sources::get).toList();
            Saved saved=save(scope,fact,refs,layout,"CONTEXT_FACT","SOURCE_GROUNDED_CONTEXT",text(batch,"layoutIdentity"));
            ids.add(saved.id());if(saved.reused())reused++;
        }catch(IllegalArgumentException invalid){rejected.add(of("index",ids.size()+rejected.size()+1,"code",code(invalid)));}}
        String parent=parent(doc,scope.engine);
        String id=dbKey("page-fact-window",canonical(List.of(parent,ordered.stream().map(p->p.locationType()+":"+p.number()).toList())));
        Map<String,Object> index=of("id",id,"projectId",doc.projectId(),"documentId",doc.id(),"documentVersion",doc.version(),"documentHash",doc.sha256(),"engineIdentity",scope.engine,"pageFingerprints",ordered.stream().map(p->of("page",p.number(),"locationType",p.locationType(),"parserFingerprint",FactLedgerService.parserFingerprint(p))).toList(),"operationIdentity",text(batch,"operationIdentity"),"engineVersion",text(batch,"engineVersion"),"occurrenceIds",ids.stream().distinct().toList(),"rejected",rejected,"updatedAt",Instant.now().toString());
        store.save("page_fact_window",id,doc.projectId(),parent,index);
        List<Map<String,Object>> accepted=ids.stream().distinct().map(core::get).filter(o->scope.project.equals(text(o,"projectId"))&&scope.engine.equals(text(o,"engineIdentity"))&&maps(o.get("sources")).stream().allMatch(s->FactLedgerService.validSource(scope,s))).toList();
        List<Integer> acceptedPages=accepted.stream().flatMap(o->maps(o.get("sources")).stream()).map(s->integer(map(s.get("evidence")).get("page"),-1)).filter(p->p>0).distinct().sorted().toList();
        return of("accepted",ids.stream().distinct().count(),"reused",reused,"rejected",rejected,"occurrenceIds",ids.stream().distinct().toList(),"acceptedDomainFacts",domainFacts(doc,accepted),"acceptedSourcePages",acceptedPages,"provisional",true);
    }

    Map<String,Object> documentView(Document doc,String parserIdentity){
        FactLedgerService.Scope scope=scope(doc,parserIdentity);String parent=parent(doc,scope.engine);
        Set<String> ids=new LinkedHashSet<>();List<Map<String,Object>> pageStates=new ArrayList<>();
        for(Map<String,Object> index:store.maps("page_fact_index",doc.projectId(),parent)){
            if(!validIndex(index,doc,scope))continue;
            String key=FactLedgerService.Scope.pageKey(doc.id(),text(index,"locationType"),integer(index.get("page"),-1));
            if(!text(index,"parserFingerprint").equals(scope.pageFingerprints.get(key)))continue;
            ids.addAll(strings(index.get("occurrenceIds")));pageStates.add(of("page",index.get("page"),"locationType",index.get("locationType"),"occurrences",strings(index.get("occurrenceIds")).size(),"rejected",maps(index.get("rejected")).size()));
        }
        for(Map<String,Object> index:store.maps("page_fact_window",doc.projectId(),parent)){
            if(!validIndex(index,doc,scope))continue;
            List<Map<String,Object>> fingerprints=maps(index.get("pageFingerprints"));
            if(fingerprints.isEmpty()||fingerprints.stream().anyMatch(p->!text(p,"parserFingerprint").equals(scope.pageFingerprints.get(FactLedgerService.Scope.pageKey(doc.id(),text(p,"locationType"),integer(p.get("page"),-1))))))continue;
            ids.addAll(strings(index.get("occurrenceIds")));
        }
        List<Map<String,Object>> occurrences=new ArrayList<>();Map<String,Map<String,Object>> entities=new LinkedHashMap<>();Set<String> evidence=new HashSet<>();Map<String,List<Map<String,Object>>> comparable=new LinkedHashMap<>();
        for(String id:ids){interrupted();Map<String,Object> occurrence=core.get(id);List<Map<String,Object>> sources=maps(occurrence.get("sources"));
            if(!scope.project.equals(text(occurrence,"projectId"))||!scope.engine.equals(text(occurrence,"engineIdentity"))||sources.isEmpty()||sources.stream().anyMatch(s->!FactLedgerService.validSource(scope,s)))continue;
            Map<String,Object> fact=map(occurrence.get("fact")),entity=map(occurrence.get("entity")),item=new LinkedHashMap<>(occurrence);
            List<Map<String,Object>> refs=sources.stream().map(s->map(s.get("evidence"))).toList();
            item.put("entityId",entity.get("id"));item.put("identityStrength",entity.get("identityStrength"));item.put("entityType",entity.get("type"));item.put("sourceRefs",refs);
            for(String field:List.of("kind","subject","field","rawValue","value","unit","basis","scope"))item.put(field,fact.get(field));
            // Missing geometry is explicit; original OCR/page layouts are the authority, never synthetic boxes.
            item.put("geometryAvailable",false);occurrences.add(item);sources.forEach(s->evidence.add(text(s,"sourceFingerprint")));
            Map<String,Object> aggregate=entities.computeIfAbsent(text(entity,"id"),k->{Map<String,Object> e=new LinkedHashMap<>(entity);e.put("occurrenceIds",new ArrayList<String>());e.put("sourceRefs",new ArrayList<Map<String,Object>>());return e;});
            append(aggregate,"occurrenceIds",id);refs.forEach(r->append(aggregate,"sourceRefs",r));
            String conflictKey=EntityIdentity.conflictKey(fact,entity,sources);
            // Within this one document version a VIN match is sufficient to flag different stated plate/engine values for review.
            // It is not a claim that dates/ownership are equivalent, and never yields an automatic FAIL.
            if("VEHICLE".equals(text(entity,"type"))&&"STRONG".equals(text(entity,"identityStrength"))&&Set.of("plateNumber","engineNumber","owner").contains(text(fact,"field")))conflictKey=canonical(List.of(entity.get("id"),fact.get("field"),fact.get("unit")));
            if(!conflictKey.isBlank())comparable.computeIfAbsent(conflictKey,k->new ArrayList<>()).add(item);
        }
        List<Map<String,Object>> conflicts=new ArrayList<>();
        for(var group:comparable.entrySet()){
            List<Map<String,Object>> variants=group.getValue();if(variants.stream().map(v->clean(text(v,"value")).toUpperCase(Locale.ROOT)).distinct().count()<2)continue;
            boolean vehicle="VEHICLE".equals(text(variants.get(0),"entityType"));
            conflicts.add(of("id",dbKey("page-fact-conflict",parent+group.getKey()),"status","REVIEW_REQUIRED","riskLevel",vehicle?"HIGH":"MEDIUM","entityId",variants.get(0).get("entityId"),"field",variants.get(0).get("field"),"reason",vehicle?"相同车架号对应不同字段值，需核对两处原文及材料时点；未自动判定不合格":"相同强身份及可比口径出现不同值，需核对原文","occurrenceIds",variants.stream().map(v->v.get("occurrenceId")).distinct().toList(),"sourceRefs",variants.stream().flatMap(v->maps(v.get("sourceRefs")).stream()).distinct().toList(),"provisional",true));
        }
        Map<String,Object> personnel=personnelView(doc,occurrences,pageStates);
        for(Map<String,Object> group:maps(personnel.get("groups")))if(Set.of("CONFLICT","SUSPECTED").contains(text(group,"status")))
            conflicts.add(of("id",group.get("id"),"status","REVIEW_REQUIRED","comparisonStatus",group.get("status"),"riskLevel","HIGH","field",group.get("field"),"fieldLabel",group.get("fieldLabel"),"reason",group.get("reason"),"occurrenceIds",group.get("occurrenceIds"),"sourceRefs",group.get("evidences"),"sourcePages",group.get("sourcePages"),"identityResolution",group.get("identityResolution"),"provisional",true));
        pageStates.sort(Comparator.comparingInt(p->integer(p.get("page"),0)));
        Map<String,Object> summary=of("pages",pageStates.size(),"occurrences",occurrences.size(),"entities",entities.size(),"evidence",evidence.size(),"conflicts",conflicts.size(),"rejected",pageStates.stream().mapToInt(p->integer(p.get("rejected"),0)).sum(),"personCredentialOccurrences",maps(personnel.get("occurrences")).size(),"personCredentialGroups",maps(personnel.get("groups")).size(),"personCredentialInformationalGroups",maps(personnel.get("informationalGroups")).size());
        return of("projectId",doc.projectId(),"documentId",doc.id(),"documentVersion",doc.version(),"documentHash",doc.sha256(),"engineIdentity",scope.engine,"parserIdentity",parserIdentity,"ledgerVersion",VERSION,"summary",summary,"stats",summary,"pages",pageStates,"occurrences",occurrences,"entities",new ArrayList<>(entities.values()),"conflicts",conflicts,
            "personCredentialOccurrences",personnel.get("occurrences"),"personCredentialGroups",personnel.get("groups"),"personCredentialInformationalGroups",personnel.get("informationalGroups"),"personCredentialCoverage",personnel.get("coverage"),
            "provisional",true,"limitations",List.of("仅反映已完成页面的有效来源；未发现冲突不代表整份文件通过。","人员同名仅作疑似关联；人员证件字段使用原记录和独立身份线索比较，不凭证号变化切断关联。","车辆使用原文中明确绑定的完整车架号关联；未提供可靠坐标的证据仅保留原页与原文，不生成边框。"));
    }

    /** Comparison may use only current, persisted page occurrences, never facts merely reparsed from an old scope. */
    private static Map<String,Object> personnelView(Document doc,List<Map<String,Object>> occurrences,List<Map<String,Object>> pageStates){
        Map<String,String> persisted=new HashMap<>();
        for(Map<String,Object> occurrence:occurrences){Map<String,Object> fact=map(occurrence.get("fact")),attrs=map(fact.get("attributes"));
            if(!"PERSON_CREDENTIAL".equals(text(attrs,"nativeEntityType")))continue;
            for(Map<String,Object> source:maps(occurrence.get("sources")))persisted.put(personFactKey(text(attrs,"nativeEntityKey"),text(fact,"field"),text(fact,"rawValue"),map(source.get("evidence"))),text(occurrence,"occurrenceId"));
        }
        Map<String,Map<String,Object>> indexes=new HashMap<>();for(Map<String,Object> p:pageStates)indexes.put(text(p,"locationType")+":"+integer(p.get("page"),-1),p);
        List<PersonCredentialFacts.Result> current=new ArrayList<>();
        for(Page page:doc.pages()){
            PersonCredentialFacts.Result parsed=PersonCredentialFacts.analyze(doc,page);
            List<PersonCredentialFacts.Occurrence> accepted=parsed.occurrences().stream().filter(o->persisted.containsKey(personFactKey(o.recordKey(),o.field(),o.rawValue(),evidenceMap(o.evidence())))).toList();
            List<Fact> facts=parsed.facts().stream().filter(f->persisted.containsKey(personFactKey(f.entityKey(),f.field(),f.rawValue(),evidenceMap(f.evidence())))).toList();
            List<Map<String,Object>> issues=new ArrayList<>(parsed.issues());boolean complete=parsed.complete();
            Map<String,Object> index=indexes.get(page.locationType()+":"+page.number());
            if(parsed.candidate()&&(index==null||accepted.size()!=parsed.occurrences().size())){complete=false;issues.add(of("code","PAGE_FACTS_NOT_PERSISTED","page",page.number(),"reason","本页人员证件字段尚未完整进入当前版本事实库，需重新识别或继续处理。"));}
            if(parsed.candidate()&&index!=null&&integer(index.get("rejected"),0)>0){complete=false;issues.add(of("code","PAGE_FACT_SOURCE_REJECTED","page",page.number(),"reason","本页部分字段未通过事实库来源核验，需核对原页。"));}
            current.add(new PersonCredentialFacts.Result(page.number(),page.locationType(),parsed.candidate(),complete,accepted,List.copyOf(issues),facts));
        }
        return new PersonCredentialConsistency().scan(doc,current);
    }
    private static String personFactKey(String record,String field,String raw,Map<String,Object> ev){return canonical(List.of(record,field,raw,ev.getOrDefault("page",0),text(ev,"locationType"),text(ev,"quote"),text(ev,"anchor")));}
    private static Map<String,Object> evidenceMap(Evidence e){return of("page",e.page(),"locationType",e.locationType(),"quote",e.quote(),"anchor",e.anchor());}

    List<Fact> documentFacts(Document doc,String parserIdentity){
        return domainFacts(doc,maps(documentView(doc,parserIdentity).get("occurrences")));
    }
    private static List<Fact> domainFacts(Document doc,List<Map<String,Object>> occurrences){
        List<Fact> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        Map<String,Page> pages=new HashMap<>();for(Page page:doc.pages())pages.put(page.locationType()+":"+page.number(),page);
        for(Map<String,Object> occurrence:occurrences){
            Map<String,Object> fact=map(occurrence.get("fact")),attrs=map(fact.get("attributes")),entity=map(occurrence.get("entity"));
            String type=text(attrs,"nativeEntityType"),key=text(attrs,"nativeEntityKey"),field=text(fact,"field"),raw=text(fact,"rawValue"),value=text(fact,"value");
            if(type.isBlank()){
                // Generic visual labels must not silently become numeric/role/project requirements.
                // Only explicit vehicle fields with a verified common VIN are admitted to deterministic comparisons.
                if(!"VEHICLE".equals(text(entity,"type"))||!"STRONG".equals(text(entity,"identityStrength")))continue;
                field=VehicleTableIdentity.canonicalField(field);
                if(field.isBlank())continue;String vin=maps(entity.get("identifiers")).stream().filter(i->"VIN".equals(i.get("field"))).map(i->text(i,"value")).findFirst().orElse("");
                if(!vin.matches("[A-HJ-NPR-Z0-9]{17}"))continue;type="VEHICLE";key="VIN:"+vin;
                value=clean(raw).toUpperCase(Locale.ROOT);
                if("plateNumber".equals(field)&&!value.matches("[\\u4e00-\\u9fff][A-Z][A-Z0-9·・]{5,7}"))continue;
                if("vin".equals(field)&&!vin.equals(value))continue;
                if("engineNumber".equals(field)&&!value.matches("[A-Z0-9-]{4,24}"))continue;
            }
            for(Map<String,Object> source:maps(occurrence.get("sources"))){Map<String,Object> evidence=map(source.get("evidence"));
                if(!clean(text(evidence,"quote")).contains(clean(raw)))continue;
                String location=text(evidence,"locationType")+":"+integer(evidence.get("page"),0),quote=text(evidence,"quote");Page sourcePage=pages.get(location);
                int quoteAt=sourcePage==null?-1:sourcePage.text().indexOf(quote),rawAt=quote.indexOf(raw);
                String position=quoteAt>=0&&sourcePage.text().indexOf(quote,quoteAt+1)<0&&rawAt>=0&&quote.indexOf(raw,rawAt+1)<0?"chars:"+(quoteAt+rawAt)+"-"+(quoteAt+rawAt+raw.length()):"unlocalized:"+hash(field+":"+value);
                String anchor=doc.id()+":"+location+":"+position;
                Evidence ev=new Evidence(doc.id(),doc.name(),integer(evidence.get("page"),0),text(evidence,"locationType"),text(evidence,"quote"),anchor,((Number)evidence.getOrDefault("confidence",0)).doubleValue());
                Fact result=new Fact(type,key,field,raw,value,ev);
                String identity=canonical(List.of(type,key,field,value,anchor));if(seen.add(identity))out.add(result);
            }
        }
        return List.copyOf(out);
    }

    private Saved save(FactLedgerService.Scope scope,Map<String,Object> fact,List<Map<String,Object>> refs,List<Map<String,Object>> layout,String domain,String method,String layoutIdentity){
        Map<String,Object> attributes=map(fact.get("attributes"));attributes.remove("structureProof");fact.put("attributes",attributes);
        if("VEHICLE".equals(text(attributes,"entityType"))){String field=VehicleTableIdentity.canonicalField(text(fact,"field"));if(!field.isBlank()){attributes.put("sourceField",fact.get("field"));fact.put("field",field);}}
        Map<String,Object> entity=EntityIdentity.resolve(fact,refs,layout);
        if(!"STRONG".equals(text(entity,"identityStrength"))){
            Optional<VehicleTableIdentity.Binding> bound=VehicleTableIdentity.resolve(scope,fact,refs);
            if(bound.isPresent()){entity=bound.get().entity();refs=bound.get().sources();fact.put("sourceIds",refs.stream().map(s->text(s,"id")).toList());}
        }
        Map<String,Object> structure=map(entity.get("structureProof"));
        if(!maps(structure.get("records")).isEmpty())attributes.put("structureProof",structure);
        Map<String,Object> semantic=new LinkedHashMap<>();for(String key:List.of("kind","subject","field","rawValue","value","unit","basis","scope"))semantic.put(key,fact.get(key));
        // Ignore model-local IDs and window metadata. Preserve attribution/temporal semantics and each original source position.
        Map<String,Object> semanticAttributes=new LinkedHashMap<>(attributes);
        for(String key:List.of("nativeOrder","windowId","contextWindowPages","contextRelations","contextRelationIds","modelFactId","structureProof","cellIds","nativeEntityKey","nativeEntityType","sourceBbox","boundsProof","localization","sourceField"))semanticAttributes.remove(key);
        semantic.put("attributes",semanticAttributes);semantic.put("entity",List.of(entity.get("id"),entity.get("type")));semantic.put("sources",refs.stream().map(s->sourcePosition(scope,s,text(fact,"rawValue"))).sorted().toList());
        String id=dbKey("page-occurrence",canonical(List.of(scope.project,scope.engine,semantic)));
        Map<String,Object> previous=core.get(id);
        if(previous.isEmpty())core.insert(of("id",id,"occurrenceId",id,"projectId",scope.project,"engineIdentity",scope.engine,"fact",fact,"sources",refs,"entity",entity,"layoutIdentity",layoutIdentity,"createdAt",Instant.now().toString(),"ledgerVersion",VERSION,"sourceDomain",domain,"extractionMethod",method));
        else if(!scope.project.equals(text(previous,"projectId"))||!scope.engine.equals(text(previous,"engineIdentity"))||maps(previous.get("sources")).isEmpty()||maps(previous.get("sources")).stream().anyMatch(s->!FactLedgerService.validSource(scope,s)))throw new IllegalArgumentException("PAGE_FACT_EXISTING_SCOPE_INVALID");
        return new Saved(id,!previous.isEmpty());
    }
    private static String sourcePosition(FactLedgerService.Scope scope,Map<String,Object> source,String raw){
        Map<String,Object> e=map(source.get("evidence"));Page page=scope.pages.get(FactLedgerService.Scope.pageKey(text(e,"documentId"),text(e,"locationType"),integer(e.get("page"),-1)));
        String quote=text(e,"quote"),content=page.text();int quoteAt=content.indexOf(quote),rawAt=quote.indexOf(raw);
        if(!raw.isBlank()&&quoteAt>=0&&content.indexOf(quote,quoteAt+1)<0&&rawAt>=0&&quote.indexOf(raw,rawAt+1)<0)
            return canonical(List.of(text(e,"documentId"),source.get("documentVersion"),source.get("documentHash"),source.get("parserFingerprint"),e.get("locationType"),e.get("page"),quoteAt+rawAt,raw.length()));
        return text(source,"sourceFingerprint");
    }
    private static Document single(Document doc,Page page){Objects.requireNonNull(doc);Objects.requireNonNull(page);return new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),doc.version(),doc.sha256(),doc.createdAt(),List.of(page),List.of());}
    private static FactLedgerService.Scope scope(Document doc,String identity){if(identity==null||identity.isBlank()||identity.length()>2000)throw new IllegalArgumentException("PAGE_FACT_IDENTITY_INVALID");return new FactLedgerService.Scope(doc.projectId(),VERSION+":"+hash(identity+":"+PersonCredentialFacts.VERSION+":"+PersonCredentialConsistency.VERSION),List.of(doc));}
    private static void requireDocumentPage(Document doc,Page page){Objects.requireNonNull(page);Objects.requireNonNull(doc);if(doc.pages()==null)throw new IllegalArgumentException("PAGE_FACT_NOT_CURRENT");List<Page> matches=doc.pages().stream().filter(p->p.number()==page.number()&&Objects.equals(p.locationType(),page.locationType())).toList();if(matches.size()!=1||!FactLedgerService.parserFingerprint(matches.get(0)).equals(FactLedgerService.parserFingerprint(page)))throw new IllegalArgumentException("PAGE_FACT_NOT_CURRENT");}
    private static String parent(Document doc,String engine){return dbKey("page-facts-doc",canonical(List.of(doc.projectId(),engine,doc.id(),doc.sha256(),doc.version())));}
    private static String pageId(String parent,Page page){return dbKey("page-facts-index",canonical(List.of(parent,page.locationType(),page.number())));}
    private static boolean validIndex(Map<String,Object> index,Document doc,FactLedgerService.Scope scope){return doc.projectId().equals(text(index,"projectId"))&&doc.id().equals(text(index,"documentId"))&&doc.sha256().equals(text(index,"documentHash"))&&doc.version()==integer(index.get("documentVersion"),-1)&&scope.engine.equals(text(index,"engineIdentity"));}
    private static String code(IllegalArgumentException invalid){String code=Objects.toString(invalid.getMessage(),"");return code.matches("[A-Z][A-Z0-9_]{1,80}")?code:"PAGE_FACT_INVALID";}
    private static List<Map<String,Object>> strictMaps(Object value){if(!(value instanceof List<?> l)||l.stream().anyMatch(v->!(v instanceof Map)))throw new IllegalArgumentException("CONTEXT_FACT_BATCH_SHAPE");return maps(value);}
    @SuppressWarnings("unchecked") private static void append(Map<String,Object> value,String key,Object item){List<Object> list=(List<Object>)value.get(key);if(!list.contains(item))list.add(item);}
    private record Saved(String id,boolean reused){}
}
