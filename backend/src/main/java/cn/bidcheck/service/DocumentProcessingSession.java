package cn.bidcheck.service;

import cn.bidcheck.document.*;
import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.consistency.PersonCredentialConsistency;
import cn.bidcheck.engine.FactExtractor;
import cn.bidcheck.persistence.*;
import cn.bidcheck.verification.facts.FactLedgerService;
import cn.bidcheck.verification.source.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.bidcheck.service.BidService.*;

/** One fenced, restartable processing session. OCR workers never write business state. */
final class DocumentProcessingSession implements OcrUnitObserver {
    static final String VERSION="document-processing-4";
    private final Store store;
    private final TransactionTemplate tx;
    private final Runnable fence;
    private final OcrUnitObserver journal;
    private final FactLedgerService ledger;
    private final Document metadata;
    private final String taskId,identity,scope,checkScope;
    private final boolean automaticVisualRequested;
    private final List<Checkpoint> checks;
    private final Map<String,Page> pages=new TreeMap<>();
    private final Map<String,PageClassifier.Decision> routes=new LinkedHashMap<>();
    private final Map<String,List<Fact>> facts=new LinkedHashMap<>();
    private final Map<String,List<Fact>> contextFacts=new LinkedHashMap<>();
    private final Map<String,Set<String>> pageOccurrenceIds=new LinkedHashMap<>();
    private final Map<String,List<String>> pageIssues=new LinkedHashMap<>();
    private final Map<String,PersonCredentialFacts.Result> personPages=new LinkedHashMap<>();
    private Map<String,Object> personComparison=Map.of();
    private final Map<String,Map<String,Object>> results=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> windows=new LinkedHashMap<>();
    private final IncrementalCheckEngine engine=new IncrementalCheckEngine();
    private final FactExtractor extractor=new FactExtractor();
    private long started=System.currentTimeMillis();
    private Set<String> activeWindows;
    private long firstResultAt;
    private int calls;
    private int visualPlannedJobs,visualRequiredCalls,visualCallLimit,visualHardLimit;
    private boolean fullVisualPlan;
    private boolean visualDeferredForOcr;
    private boolean complete;

    DocumentProcessingSession(Store store,TransactionTemplate tx,Runnable fence,OcrUnitObserver journal,FactLedgerService ledger,
                              Document metadata,String taskId,String identity,List<Checkpoint> checks) {
        this(store,tx,fence,journal,ledger,metadata,taskId,identity,checks,true);
    }
    DocumentProcessingSession(Store store,TransactionTemplate tx,Runnable fence,OcrUnitObserver journal,FactLedgerService ledger,
                              Document metadata,String taskId,String identity,List<Checkpoint> checks,boolean automaticVisualRequested) {
        this.store=store;this.tx=tx;this.fence=fence;this.journal=journal;this.ledger=ledger;this.metadata=metadata;
        this.taskId=taskId;this.identity=identity;this.automaticVisualRequested=automaticVisualRequested;
        this.checks=checks.stream().filter(c->c.enabled()&&c.confirmed()).sorted(Comparator.comparingInt(IncrementalCheckEngine::priority)).toList();
        scope=hash(VERSION+"|"+StructuredHtmlTableFacts.VERSION+"|"+PersonCredentialFacts.VERSION+"|"+PersonCredentialConsistency.VERSION+"|"+metadata.projectId()+"|"+metadata.id()+"|"+metadata.version()+"|"+metadata.sha256()+"|"+identity+(automaticVisualRequested?"":"|TEXT_ONLY_V1"));
        checkScope=hash(scope+"|"+store.encode(this.checks));
        for(Map<String,Object> row:store.maps("processing_page",metadata.projectId(),metadata.id()))if(scope.equals(row.get("scope"))) {
            Page page=store.decode(store.encode(row.get("page")),Page.class);
            pages.put(key(page),page);routes.put(key(page),store.decode(store.encode(row.get("route")),PageClassifier.Decision.class));
            StructuredHtmlTableFacts.Result table=StructuredHtmlTableFacts.analyze(document(List.of(page)),page);
            personPages.put(key(page),PersonCredentialFacts.analyze(document(List.of(page)),page));
            facts.put(key(page),pageFacts(page,table));
            Map<String,Object> indexed=map(row.get("factResult"));
            personPages.put(key(page),personLedgerCoverage(personPages.get(key(page)),indexed));
            retainAcceptedPersonFacts(key(page));
            pageOccurrenceIds.put(key(page),strings(indexed.get("occurrenceIds")));
            pageIssues.put(key(page),reviewIssues(page,table,indexed));
        }
        for(Map<String,Object> row:store.maps("incremental_check",metadata.projectId(),metadata.id()))if(checkScope.equals(row.get("checkScope")))results.put(text(row,"checkpointId"),row);
        for(Map<String,Object> row:store.maps("processing_window",metadata.projectId(),metadata.id()))if(scope.equals(row.get("scope")))windows.put(text(row,"windowKey"),row);
        Stored previous=store.mapper().get(summaryId(metadata.id()));
        if(previous!=null&&scope.equals(store.map(previous.body).get("scope"))){Map<String,Object> value=store.map(previous.body);calls=taskId.equals(value.get("taskId"))?number(value.get("visualCalls")):0;firstResultAt=checkScope.equals(value.get("checkScope"))?longNumber(value.get("firstResultAtEpochMillis")):0;long saved=longNumber(value.get("startedAtEpochMillis"));if(saved>0)started=saved;}
        refreshPersonComparison();
        // Source processing survives checkpoint changes; only the checkpoint decisions are recalculated.
        if(!pages.isEmpty()&&this.checks.stream().anyMatch(c->!results.containsKey(c.id())))tx.executeWithoutResult(s->{
            fence.run();store.lock("project",metadata.projectId());contextFacts.put("restored",ledger.documentFacts(current(),identity));
            for(Checkpoint cp:this.checks)if(!results.containsKey(cp.id()))saveResult(engine.evaluate(cp,current(),allFacts(),false));saveSummary();
        });
    }
    @Override public Optional<Result> restore(String type,int position,int imageIndex,String imageHash)throws IOException{return journal.restore(type,position,imageIndex,imageHash);}
    @Override public void onState(Event event)throws IOException{journal.onState(event);}
    @Override public void onBatch(cn.bidcheck.ocr.OcrBatch.Event event)throws IOException{journal.onBatch(event);}
    @Override public void onClassified(String type,int position,PageClassifier.Decision decision)throws IOException {
        journal.onClassified(type,position,decision);
        tx.executeWithoutResult(s->{fence.run();store.save("processing_route",hash("processing-route:"+scope+":"+type+":"+position),metadata.projectId(),metadata.id(),
            fields("scope",scope,"documentId",metadata.id(),"documentVersion",metadata.version(),"page",position,"locationType",type,"route",decision,"status","CLASSIFIED"));});
    }

    @Override public void onPage(Page page,PageClassifier.Decision route)throws IOException {
        String key=key(page);Page old=pages.get(key);
        StructuredHtmlTableFacts.Result table=StructuredHtmlTableFacts.analyze(document(List.of(page)),page);
        boolean parsedTable=!incomplete(page)&&table.complete()&&!table.facts().isEmpty();
        PageClassifier.Decision effectiveRoute=parsedTable?tableRoute(page,route,table.schema()):route;
        if(Objects.equals(page,old)&&Objects.equals(routes.get(key),effectiveRoute)){tx.executeWithoutResult(s->{fence.run();saveSummary();});return;}
        personPages.put(key,PersonCredentialFacts.analyze(document(List.of(page)),page));
        List<Fact> before=facts.getOrDefault(key,List.of()),after=pageFacts(page,table);
        pages.put(key,page);routes.put(key,effectiveRoute);facts.put(key,after);
        Document partial=current();
        tx.executeWithoutResult(s->{
            fence.run();store.lock("project",metadata.projectId());Map<String,Object> indexed=new LinkedHashMap<>(ledger.persistSuppliedPageFacts(partial,page,after,identity));
            // Only personnel uses this receipt on restart; do not duplicate long unrelated table quotes here.
            List<?> accepted=indexed.get("acceptedDomainFacts") instanceof List<?> list?list:List.of();
            indexed.put("acceptedDomainFacts",accepted.stream().filter(value->value instanceof Fact f&&"PERSON_CREDENTIAL".equals(f.entityType())||value instanceof Map<?,?> m&&"PERSON_CREDENTIAL".equals(m.get("entityType"))).toList());
            pageOccurrenceIds.put(key,strings(indexed.get("occurrenceIds")));
            personPages.put(key,personLedgerCoverage(personPages.get(key),indexed));retainAcceptedPersonFacts(key);refreshPersonComparison();
            List<Fact> safeAfter=facts.getOrDefault(key,List.of()),all=allFacts();List<Map<String,Object>> changed=new ArrayList<>();
            for(Checkpoint cp:checks)if(!results.containsKey(cp.id())||engine.affected(cp,page,before,safeAfter)
                ||"MATERIAL_EXISTENCE".equals(cp.type())&&old!=null&&engine.affected(cp,old,before,safeAfter))changed.add(engine.evaluate(cp,partial,all,false));
            List<String> issues=reviewIssues(page,table,indexed);pageIssues.put(key,issues);
            indexed.put("structuredTable",fields("version",StructuredHtmlTableFacts.VERSION,"attempted",hasHtmlTable(page),"complete",table.complete(),"schema",table.schema(),"factCount",table.facts().size(),"used",parsedTable,"issues",table.issues(),"visualStillRequired",parsedTable&&!"STRUCTURED_TABLE_PARSE".equals(effectiveRoute.parserStrategy())));
            indexed.put("sourceRoute",route);indexed.put("pageReviewRequired",!issues.isEmpty());indexed.put("issues",issues);
            PersonCredentialFacts.Result person=personPages.get(key);
            indexed.put("personCredentials",fields("version",PersonCredentialFacts.VERSION,"candidate",person.candidate(),"complete",person.complete(),"factCount",person.facts().size(),"issues",person.issues()));
            store.save("processing_page",pageId(scope,page),metadata.projectId(),metadata.id(),fields("scope",scope,"documentId",metadata.id(),"documentVersion",metadata.version(),"sourceHash",metadata.sha256(),"page",page,"route",effectiveRoute,"factResult",indexed,"reviewRequired",!issues.isEmpty(),"status",issues.isEmpty()?"COMPLETED":"REVIEW_REQUIRED","updatedAt",now()));
            store.save("processing_route",hash("processing-route:"+scope+":"+page.locationType()+":"+page.number()),metadata.projectId(),metadata.id(),
                fields("scope",scope,"documentId",metadata.id(),"documentVersion",metadata.version(),"page",page.number(),"locationType",page.locationType(),"route",effectiveRoute,"sourceRoute",route,"status",parsedTable?"PARSED":"CLASSIFIED"));
            for(Map<String,Object> value:changed)saveResult(value);
            saveSummary();
        });
    }
    /** After the final parse callback, run only routed structured materials, in bounded parallel windows. */
    void finish(Document document,Path original,VisualVerificationService visual,int maxCalls)throws IOException {
        finish(document,original,visual,maxCalls,false,maxCalls);
    }
    /** A full plan derives its bounded attempt budget from actual pending batches and repairs. */
    void finish(Document document,Path original,VisualVerificationService visual,int maxCalls,boolean fullCoverage,int hardMaxCalls)throws IOException {
        fullVisualPlan=automaticVisualRequested&&fullCoverage;visualDeferredForOcr=false;
        for(Page page:document.pages())if(!Objects.equals(pages.get(key(page)),page))onPage(page,PageClassifier.nativeOnly(page.text(),page.parser()!=null&&page.parser().contains("OCR")?1:0));
        if(!automaticVisualRequested){
            // Text coverage is independent of optional image/field/signature verification.
            // Do not manufacture pending jobs, successful windows, or image observations.
            activeWindows=Set.of();visualPlannedJobs=0;visualRequiredCalls=0;visualCallLimit=0;visualHardLimit=0;
            completeProcessing();return;
        }
        boolean waitForDocumentOcr=fullCoverage&&pages.values().stream().anyMatch(DocumentProcessingSession::incomplete);
        activeWindows=new HashSet<>();Map<String,List<Integer>> submitted=new LinkedHashMap<>();List<VisualWorkCoordinator.Job> jobs=new ArrayList<>();
        if("BID".equals(metadata.role()))for(List<Integer> window:visualWindows(document,visual,fullCoverage)) {
            List<Page> members=document.pages().stream().filter(p->window.contains(p.number())).toList();
            if(members.stream().noneMatch(this::structured))continue;
            String windowKey=windowKey(document,visual,window);
            activeWindows.add(windowKey);for(int p:window)activeWindows.add(windowKey+":"+p);
            Map<String,Object> prior=windows.get(windowKey);
            if(prior!=null&&Set.of("SUCCEEDED","NO_FACTS","RECOVERED","REVIEW_REQUIRED").contains(text(prior,"status")))continue;
            if(waitForDocumentOcr){
                Set<Integer> good=completedPages(prior);
                for(int p:window)good.addAll(completedPages(windows.get(windowKey+":"+p)));
                if(good.containsAll(window))saveRecoveredWindow(windowKey,window,prior,document);
                else deferWindowForOcr(windowKey,window,document);
                continue;
            }
            if(members.stream().anyMatch(DocumentProcessingSession::incomplete)) {
                saveWindow(windowKey,window,"WAITING_OCR",Map.of(),document);continue;
            }
            if(visual==null||!visual.enabled()){saveWindow(windowKey,window,"VISUAL_NOT_CONFIGURED",Map.of(),document);continue;}
            Set<Integer> good=completedPages(prior);
            for(int p:window)good.addAll(completedPages(windows.get(windowKey+":"+p)));
            if(good.containsAll(window)){saveRecoveredWindow(windowKey,window,prior,document);continue;}
            if(prior!=null&&longNumber(map(prior.get("response")).get("retryNotBeforeEpochMillis"))>System.currentTimeMillis())continue;
            if(!good.isEmpty()||window.stream().anyMatch(p->windows.containsKey(windowKey+":"+p))){
                for(int p:window)if(!good.contains(p)){
                    String splitKey=windowKey+":"+p;Map<String,Object> saved=windows.get(splitKey);
                    if(saved!=null&&longNumber(map(saved.get("response")).get("retryNotBeforeEpochMillis"))>System.currentTimeMillis())continue;
                    submitted.put(splitKey,List.of(p));jobs.add(visualJob(splitKey,visual,document,original,List.of(p)));
                }
            }else{submitted.put(windowKey,window);jobs.add(visualJob(windowKey,visual,document,original,window));}
        }
        visualPlannedJobs=jobs.size();
        visualRequiredCalls=calls+jobs.stream().mapToInt(job->{int size=submitted.get(job.id()).size();return size>1?2+2*size:2;}).sum();
        visualHardLimit=Math.max(0,Math.min(4096,hardMaxCalls));
        visualCallLimit=fullCoverage?Math.min(visualHardLimit,Math.max(maxCalls,visualRequiredCalls)):Math.max(0,maxCalls);
        Map<String,String> canonicalJobs=new HashMap<>();Set<String> repairedSingles=new HashSet<>();
        tx.executeWithoutResult(s->{fence.run();saveSummary();});
        if(!jobs.isEmpty())VisualWorkCoordinator.run(jobs,visual.parallelism(),()->Boolean.TRUE.equals(tx.execute(s->{
            fence.run();if(calls>=visualCallLimit)return false;calls++;saveSummary();return true;
        })),()->calls<visualCallLimit,(job,response)->{
            String canonical=canonicalJobs.getOrDefault(job.id(),job.id());
            List<Integer> window=submitted.get(canonical);
            saveWindow(canonical,window,"",response,document);
            if(window.size()>1&&VisualVerificationService.splitRetryable(response)) {
                Set<Integer> good=new HashSet<>();for(Map<String,Object> p:maps(response.get("pageResults")))if("COMPLETED".equals(p.get("status")))good.add(number(p.get("page")));
                List<VisualWorkCoordinator.Job> split=new ArrayList<>();
                for(int p:window)if(!good.contains(p)){String id=canonical+":"+p;submitted.put(id,List.of(p));split.add(visualJob(id,visual,document,original,List.of(p)));}
                return split;
            }
            if(fullCoverage&&window.size()==1&&VisualVerificationService.singleRetryable(response)&&repairedSingles.add(canonical)){
                String retry=canonical+"#repair";canonicalJobs.put(retry,canonical);
                return List.of(visualJob(retry,visual,document,original,window));
            }
            return List.of();
        });
        // Capacity exhaustion is visible for every unscheduled window, never reported as missing material.
        for(var pending:submitted.entrySet())if(!windows.containsKey(pending.getKey()))saveWindow(pending.getKey(),pending.getValue(),"BUDGET_EXHAUSTED",Map.of(),document);
        for(var parent:new ArrayList<>(windows.entrySet())){
            if(!activeWindows.contains(parent.getKey()))continue;
            List<Integer> window=integers(parent.getValue().get("pages"));if(window.size()<2)continue;
            Set<Integer> good=completedPages(parent.getValue());for(int p:window)good.addAll(completedPages(windows.get(parent.getKey()+":"+p)));
            if(good.containsAll(window)&&!Set.of("SUCCEEDED","NO_FACTS","REVIEW_REQUIRED","RECOVERED").contains(text(parent.getValue(),"status")))
                saveRecoveredWindow(parent.getKey(),window,parent.getValue(),document);
        }
        completeProcessing();
    }
    private void completeProcessing(){
        complete=true;
        refreshPersonComparison();
        tx.executeWithoutResult(s->{fence.run();store.lock("project",metadata.projectId());
            List<Fact> finalFacts=ledger.documentFacts(current(),identity);
            contextFacts.clear();contextFacts.put("final",finalFacts);
            for(Checkpoint cp:checks)saveResult(engine.evaluate(cp,current(),allFacts(),true));saveSummary();
        });
    }
    private void saveRecoveredWindow(String id,List<Integer> window,Map<String,Object> previous,Document doc){
        saveWindow(id,window,"RECOVERED",previous==null?Map.of():map(previous.get("response")),doc);
    }
    /** Deferral is scheduling metadata, never a new fact receipt or an overwrite of partial success. */
    private void deferWindowForOcr(String id,List<Integer> window,Document doc){
        tx.executeWithoutResult(s->{fence.run();store.lock("project",metadata.projectId());
            Map<String,Object> prior=windows.get(id);
            Map<String,Object> row=prior==null?fields("scope",scope,"windowKey",id,"documentId",metadata.id(),"documentVersion",metadata.version(),
                "pages",window,"reviewRequired",false,"noFactPages",List.of(),"noFactPageCount",0,"relations",VisualPageWindows.relations(doc,window),"response",Map.of(),"factResult",Map.of()):new LinkedHashMap<>(prior);
            row.put("status","WAITING_OCR");row.put("deferralReason","DOCUMENT_OCR_INCOMPLETE");row.put("updatedAt",now());
            visualDeferredForOcr=true;windows.put(id,row);
            store.save("processing_window",hash("processing-window:"+id),metadata.projectId(),metadata.id(),row);saveSummary();
        });
    }
    private void saveWindow(String id,List<Integer> window,String status,Map<String,Object> response,Document doc) {
        tx.executeWithoutResult(s->{fence.run();store.lock("project",metadata.projectId());
            List<Page> members=doc.pages().stream().filter(p->window.contains(p.number())).toList();
            Map<String,Object> previous=windows.get(id);
            boolean independent=independentWindow(doc,window);
            Map<String,Object> receiptResponse=new LinkedHashMap<>(response);receiptResponse.put("independentPages",independent);
            Map<String,Object> batch=map(response.get("factBatch"));
            Map<String,Object> indexed;
            if("RECOVERED".equals(status)){
                // The old partial response cannot overwrite the repaired children's page ledger indexes.
                List<Map<String,Object>> receipts=new ArrayList<>();Map<String,Object> parent=windows.get(id);
                if(parent!=null)receipts.add(map(parent.get("factResult")));
                for(int page:window){Map<String,Object> child=windows.get(id+":"+page);if(child!=null)receipts.add(map(child.get("factResult")));}
                indexed=mergeReceipts(receipts);
            }else{
                Map<String,Object> retained=previous==null?Map.of():map(previous.get("factResult"));
                Map<String,Object> fresh=maps(batch.get("facts")).isEmpty()?Map.of():persistContextBatch(doc,members,batch,independent);
                indexed=retained.isEmpty()?fresh:fresh.isEmpty()?retained:mergeReceipts(List.of(retained,fresh));
                // A retry failure cannot erase already validated per-page completion evidence.
                if(previous!=null){
                    Map<Integer,Map<String,Object>> completed=new LinkedHashMap<>();
                    for(Map<String,Object> page:maps(map(previous.get("response")).get("pageResults")))if("COMPLETED".equals(page.get("status")))completed.put(number(page.get("page")),page);
                    for(Map<String,Object> page:maps(response.get("pageResults")))if(!completed.containsKey(number(page.get("page")))||"COMPLETED".equals(page.get("status")))completed.put(number(page.get("page")),page);
                    receiptResponse.put("pageResults",List.copyOf(completed.values()));
                }
            }
            List<Fact> accepted=indexed.containsKey("acceptedDomainFacts")?List.of(store.decode(store.encode(indexed.get("acceptedDomainFacts")),Fact[].class)):List.of();
            // A valid envelope is not evidence that every page yielded a usable fact.
            Set<Integer> noFactPages=new TreeSet<>();
            for(Map<String,Object> page:maps(receiptResponse.get("pageResults")))if("COMPLETED".equals(page.get("status"))&&window.contains(number(page.get("page"))))noFactPages.add(number(page.get("page")));
            noFactPages.removeAll(integers(indexed.get("acceptedSourcePages")));
            Set<Integer> limitedPages=new TreeSet<>();Set<String> coverageLimitations=new LinkedHashSet<>();
            collectCoverageLimits(response,window,limitedPages,coverageLimitations);
            if(previous!=null)collectCoverageLimits(previous,window,limitedPages,coverageLimitations);
            if("RECOVERED".equals(status))for(int page:window){Map<String,Object> child=windows.get(id+":"+page);if(child!=null)collectCoverageLimits(child,window,limitedPages,coverageLimitations);}
            boolean reviewRequired=!limitedPages.isEmpty()||!coverageLimitations.isEmpty()||previous!=null&&Boolean.TRUE.equals(previous.get("reviewRequired"))||!maps(batch.get("rejected")).isEmpty()||!maps(indexed.get("rejected")).isEmpty()||number(response.get("rejectedPageRecords"))>0;
            reviewRequired|=number(indexed.get("accepted"))>0&&maps(batch.get("facts")).isEmpty()&&VisualVerificationService.cacheable(response);
            if("RECOVERED".equals(status))for(int page:window){
                Map<String,Object> child=windows.get(id+":"+page);if(child==null)continue;
                reviewRequired|=Boolean.TRUE.equals(child.get("reviewRequired"))||Set.of("REVIEW_REQUIRED","NO_FACTS").contains(text(child,"status"));
                noFactPages.removeAll(completedPages(child));noFactPages.addAll(integers(child.get("noFactPages")));
                if("NO_FACTS".equals(text(child,"status")))noFactPages.addAll(integers(child.get("pages")));
            }
            // All-empty successful windows retain NO_FACTS; mixed windows must not certify all pages.
            reviewRequired|=!noFactPages.isEmpty()&&(number(indexed.get("accepted"))>0||"RECOVERED".equals(status));
            String resolvedStatus=windowStatus(status,response,reviewRequired);
            List<Fact> before=contextFacts.getOrDefault(id,List.of());contextFacts.put(id,accepted);
            for(Checkpoint cp:checks)if(members.stream().anyMatch(p->engine.affected(cp,p,before,accepted)))saveResult(engine.evaluate(cp,current(),allFacts(),false));
            Map<String,Object> row=fields("scope",scope,"windowKey",id,"documentId",metadata.id(),"documentVersion",metadata.version(),
                "pages",window,"status",resolvedStatus,"independentPages",independent,"reviewRequired",reviewRequired,"coverageLimitedPages",List.copyOf(limitedPages),"coverageLimitations",List.copyOf(coverageLimitations),"noFactPages",List.copyOf(noFactPages),"noFactPageCount",noFactPages.size(),"relations",VisualPageWindows.relations(doc,window),"response",receiptResponse,"factResult",indexed,"updatedAt",now());
            windows.put(id,row);store.save("processing_window",hash("processing-window:"+id),metadata.projectId(),metadata.id(),row);saveSummary();
        });
    }
    /** Coverage limits are separate from field validity: keep accepted facts while preserving review scope. */
    private static void collectCoverageLimits(Map<String,Object> response,List<Integer> window,Set<Integer> pages,Set<String> codes){
        for(int page:integers(response.get("coverageLimitedPages")))if(window.contains(page))pages.add(page);
        for(String code:strings(response.get("coverageLimitations")))if(code.matches("[A-Z0-9_]{1,80}"))codes.add(code);
        for(Map<String,Object> page:maps(response.get("pageResults"))){
            int number=number(page.get("page"));if(!window.contains(number))continue;
            List<String> limitations=strings(page.get("coverageLimitations")).stream().filter(code->code.matches("[A-Z0-9_]{1,80}")).toList();
            if(!limitations.isEmpty()){pages.add(number);codes.addAll(limitations);}
        }
        if(!codes.isEmpty()&&pages.isEmpty())pages.addAll(window);
    }

    /** Transport recovery must not erase rejected facts; partial windows still need their failed pages retried. */
    private static String windowStatus(String requested,Map<String,Object> response,boolean reviewRequired){
        if("RECOVERED".equals(requested))return reviewRequired?"REVIEW_REQUIRED":"RECOVERED";
        if(VisualVerificationService.cacheable(response))return reviewRequired?"REVIEW_REQUIRED":maps(map(response.get("factBatch")).get("facts")).isEmpty()?"NO_FACTS":"SUCCEEDED";
        if(!requested.isBlank())return requested;
        String code=text(response,"errorCode");return code.isBlank()?"REVIEW_REQUIRED":code;
    }
    /** Independent pages share transport only; keep the ledger's contiguous/source-window contract intact. */
    private Map<String,Object> persistContextBatch(Document doc,List<Page> members,Map<String,Object> batch,boolean independent){
        if(!independent||members.size()==1)return ledger.persistContextFacts(doc,members,identity,batch);
        List<Map<String,Object>> sources=maps(batch.get("sources")),inputFacts=maps(batch.get("facts")),rejected=new ArrayList<>();
        Set<Map<String,Object>> assigned=new HashSet<>();Set<String> ids=new LinkedHashSet<>();Set<Integer> acceptedPages=new TreeSet<>();List<Object> acceptedDomain=new ArrayList<>();int reused=0;
        for(Page page:members){
            List<Map<String,Object>> ownSources=sources.stream().filter(source->number(map(source.get("evidence")).get("page"))==page.number()).toList();
            Set<String> ownIds=new HashSet<>();ownSources.forEach(source->ownIds.add(text(source,"id")));
            List<Map<String,Object>> ownFacts=inputFacts.stream().filter(fact->{Set<String> refs=strings(fact.get("sourceIds"));return !refs.isEmpty()&&ownIds.containsAll(refs);}).toList();assigned.addAll(ownFacts);
            // Legal empty observations and failed pages must not clear a previously saved page index.
            if(ownFacts.isEmpty())continue;
            Map<String,Object> own=new LinkedHashMap<>(batch);own.put("sources",ownSources);own.put("facts",ownFacts);own.put("inputPages",List.of(page.number()));own.put("contextRelations",List.of());
            Map<String,Object> receipt=ledger.persistContextFacts(doc,List.of(page),identity,own);
            ids.addAll(strings(receipt.get("occurrenceIds")));acceptedPages.addAll(integers(receipt.get("acceptedSourcePages")));rejected.addAll(maps(receipt.get("rejected")));reused+=number(receipt.get("reused"));
            if(receipt.get("acceptedDomainFacts") instanceof List<?> values)acceptedDomain.addAll(values);
        }
        for(Map<String,Object> fact:inputFacts)if(!assigned.contains(fact))rejected.add(fields("factId",text(fact,"id"),"code","INDEPENDENT_FACT_SOURCE_NOT_SINGLE_PAGE"));
        return fields("accepted",ids.size(),"reused",reused,"rejected",rejected,"occurrenceIds",List.copyOf(ids),"acceptedDomainFacts",acceptedDomain,"acceptedSourcePages",List.copyOf(acceptedPages),"provisional",true);
    }
    private static Map<String,Object> mergeReceipts(List<Map<String,Object>> receipts){
        Set<String> ids=new LinkedHashSet<>();Set<Integer> pages=new TreeSet<>();Set<Object> domainFacts=new LinkedHashSet<>();List<Map<String,Object>> rejected=new ArrayList<>();int reused=0;
        for(Map<String,Object> receipt:receipts){
            ids.addAll(strings(receipt.get("occurrenceIds")));pages.addAll(integers(receipt.get("acceptedSourcePages")));rejected.addAll(maps(receipt.get("rejected")));reused+=number(receipt.get("reused"));
            if(receipt.get("acceptedDomainFacts") instanceof List<?> values)domainFacts.addAll(values);
        }
        return fields("accepted",ids.size(),"reused",reused,"rejected",rejected,"occurrenceIds",List.copyOf(ids),"acceptedDomainFacts",List.copyOf(domainFacts),"acceptedSourcePages",List.copyOf(pages),"provisional",true);
    }
    private boolean structured(Page page){
        if(pdfTextRecoveryRequiresVisual(page))return true;
        PageClassifier.Decision route=routes.get(key(page));if(route==null)return false;
        if("STRUCTURED_TABLE_PARSE".equals(route.parserStrategy()))return false;
        // Vector handwriting/seals need a visual observation even when a PDF has a good text layer.
        if("SIGNATURE_SEAL".equals(route.pageType())&&metadata.name().toLowerCase(Locale.ROOT).endsWith(".pdf"))return true;
        if("NATIVE_PARSE".equals(route.parserStrategy()))return false;
        return Set.of("TABLE_PAGE","VEHICLE_LICENSE","DRIVING_LICENSE","PASSPORT","ID_CARD","CERTIFICATE","CONTRACT","SIGNATURE_SEAL").contains(route.pageType());
    }
    private boolean pdfTextRecoveryRequiresVisual(Page page){
        // Plain recovery completes transcription only. Preserve its explicit layout gap in the
        // full visual plan without turning every ordinary PDF layout warning into a new request.
        return fullVisualPlan&&"PAGE".equals(page.locationType())&&metadata.name().toLowerCase(Locale.ROOT).endsWith(".pdf")
            &&Objects.toString(page.warning(),"").contains("[LAYOUT_PDF_TEXT_RECOVERY]");
    }
    private List<Fact> pageFacts(Page page,StructuredHtmlTableFacts.Result table){
        List<Fact> combined=new ArrayList<>(extractor.extract(document(List.of(page))));
        if(!incomplete(page)&&table.complete()&&!table.facts().isEmpty())combined.addAll(table.facts());
        PersonCredentialFacts.Result person=personPages.get(key(page));
        if(person!=null)combined.addAll(person.facts());
        return List.copyOf(combined);
    }
    private static PageClassifier.Decision tableRoute(Page page,PageClassifier.Decision source,String schema){
        Set<String> additional=Set.of("VEHICLE_LICENSE","DRIVING_LICENSE","PASSPORT","ID_CARD","CERTIFICATE","CONTRACT","SIGNATURE_SEAL");
        if(additional.contains(source.pageType()))return source;
        // The helper covers the table cells only. Explicit material outside the table retains its own visual task.
        String outside=Objects.toString(page.text(),"").replaceFirst("(?is)<table\\b[^<>]*>.*?</table\\s*>","");
        PageClassifier.Decision other=PageClassifier.nativeOnly(outside,1);
        if(additional.contains(other.pageType()))return new PageClassifier.Decision(other.pageType(),other.parserStrategy(),other.schema(),"HIGH",other.requiresCrossPageContext(),source.resolutionBucket(),other.independent());
        if(!"TABLE_PAGE".equals(source.pageType()))return source;
        return new PageClassifier.Decision("TABLE_PAGE","STRUCTURED_TABLE_PARSE",schema,"HIGH",false,source.resolutionBucket(),true);
    }
    /** Completed independent tables need no second model pass, even beside a visual candidate. */
    private String windowKey(Document document,VisualVerificationService visual,List<Integer> window){
        List<Page> members=document.pages().stream().filter(p->window.contains(p.number())).toList();
        return hash(scope+"|"+(visual==null?"DISABLED":visual.identity())+"|"+window+"|"+store.encode(members)+"|"+store.encode(VisualPageWindows.relations(document,window)));
    }
    private List<List<Integer>> visualWindows(Document document,VisualVerificationService visual,boolean independentBatches){
        Set<Integer> parsed=new HashSet<>();for(Page page:document.pages()){
            PageClassifier.Decision route=routes.get(key(page));if(route!=null&&"STRUCTURED_TABLE_PARSE".equals(route.parserStrategy())&&!pdfTextRecoveryRequiresVisual(page))parsed.add(page.number());
        }
        Set<List<Integer>> result=new LinkedHashSet<>();for(List<Integer> planned:VisualPageWindows.plan(document)){
            List<Integer> group=new ArrayList<>();for(int page:planned){
                if(parsed.contains(page)){if(!group.isEmpty()){result.add(List.copyOf(group));group.clear();}}
                else group.add(page);
            }if(!group.isEmpty())result.add(List.copyOf(group));
        }
        if(!independentBatches)return List.copyOf(result);
        Map<Integer,Page> byPage=new HashMap<>();document.pages().forEach(p->byPage.put(p.number(),p));
        List<List<Integer>> batched=new ArrayList<>();Map<String,List<Integer>> buckets=new LinkedHashMap<>();
        for(List<Integer> window:result){
            Page page=byPage.get(window.get(0));PageClassifier.Decision route=routes.get(key(page));
            Map<String,Object> prior=windows.get(windowKey(document,visual,window));
            boolean retained=prior!=null&&Set.of("SUCCEEDED","NO_FACTS","RECOVERED","REVIEW_REQUIRED").contains(text(prior,"status"));
            // Dense tables keep their full page payload but do not share one response budget.
            // Source-related multi-page windows never enter the independent singleton bucket.
            boolean denseCompactPage=visual!=null&&(visual.compactStructuredOutputEnabled()||visual.sourceSpanOutputEnabled())
                &&(route!=null&&"TABLE_PAGE".equals(route.pageType())||hasHtmlTable(page)||pdfTextRecoveryRequiresVisual(page));
            boolean eligible=window.size()==1&&"PAGE".equals(page.locationType())&&metadata.name().toLowerCase(Locale.ROOT).endsWith(".pdf")
                &&!retained&&structured(page)&&!incomplete(page)&&route!=null&&!"SIGNATURE_SEAL".equals(route.pageType())
                &&!denseCompactPage&&Objects.toString(page.text(),"").length()<=6000;
            if(!eligible){batched.add(window);continue;}
            List<Integer> bucket=buckets.computeIfAbsent(route.pageType(),ignored->new ArrayList<>());bucket.add(page.number());
            if(bucket.size()==3){batched.add(List.copyOf(bucket));bucket.clear();}
        }
        buckets.values().stream().filter(b->!b.isEmpty()).forEach(b->batched.add(List.copyOf(b)));
        return List.copyOf(batched);
    }
    private static boolean hasHtmlTable(Page page){return java.util.regex.Pattern.compile("(?is)<table\\b").matcher(Objects.toString(page.text(),"")).find();}
    private static List<String> reviewIssues(Page page,StructuredHtmlTableFacts.Result table,Map<String,Object> indexed){
        Set<String> issues=new LinkedHashSet<>();if(hasHtmlTable(page))for(String issue:table.issues()){
            if("HTML_TABLE_NOT_PRESENT".equals(issue))continue;
            issues.add(switch(issue){
            case "存在身份信息为空的缴费记录，未关联到任何人员，需人工核对。" -> issue;
            case "HTML_TABLE_INCOMPLETE" -> "表格结构不完整，需核对原页。";
            case "HTML_TABLE_UNSUPPORTED_SCHEMA" -> "表头无法完整确认，需人工核对。";
            case "HTML_TABLE_IDENTITY_SPAN_MISMATCH","HTML_TABLE_DATA_SPAN_UNSUPPORTED","HTML_TABLE_SPAN_INVALID","HTML_TABLE_SPAN_OUTSIDE","HTML_TABLE_SPAN_COLLISION","HTML_TABLE_COLUMN_COUNT" -> "合并单元格或列的归属不明确，需人工核对。";
            case "HTML_TABLE_SOURCE_INCOMPLETE" -> "表格所在页面尚未识别完成，需补充核对。";
            case "HTML_TABLE_SOURCE_INVALID" -> "表格来源无法确认，需核对原页。";
            case "HTML_TABLE_LIMIT","HTML_TABLE_VALUE_LIMIT" -> "表格超出直接解析范围，需进一步核对。";
            case "HTML_TABLE_PARTIAL_IDENTITY" -> "部分身份字段为空，未补齐人员信息，需人工核对。";
            default -> "表格存在无法确定的字段，需核对原页。";
        });}
        if(!maps(indexed.get("rejected")).isEmpty())issues.add("部分字段未通过来源核验，需核对原页。");
        return List.copyOf(issues);
    }
    private VisualWorkCoordinator.Job visualJob(String id,VisualVerificationService visual,Document doc,Path original,List<Integer> window){
        boolean signing=window.size()==1&&doc.pages().stream().filter(p->p.number()==window.get(0)).map(p->routes.get(key(p)))
            .anyMatch(r->r!=null&&"SIGNATURE_SEAL".equals(r.pageType()));
        boolean independent=independentWindow(doc,window);
        return new VisualWorkCoordinator.Job(id,permit->signing?visual.verifyWindow(doc,original,window,Map.of("task","LAYOUT"),permit,scope):independent?visual.extractIndependentPages(doc,original,window,permit,scope):visual.extractContext(doc,original,window,permit,scope));
    }
    private static boolean independentWindow(Document doc,List<Integer> window){return window.size()>1&&VisualPageWindows.relations(doc,window).isEmpty();}
    static boolean incomplete(Page page){return Objects.toString(page.warning(),"").contains("[OCR未完成]")||Objects.toString(page.warning(),"").contains("[OCR_DEFERRED]");}
    private void saveResult(Map<String,Object> value){
        value.put("scope",scope);value.put("checkScope",checkScope);value.put("documentId",metadata.id());value.put("documentVersion",metadata.version());
        results.put(text(value,"checkpointId"),value);store.save("incremental_check",hash("incremental:"+checkScope+":"+text(value,"checkpointId")),metadata.projectId(),metadata.id(),value);
        if(firstResultAt==0&&Set.of("EVIDENCE_FOUND","REVIEW_REQUIRED").contains(text(value,"state")))firstResultAt=System.currentTimeMillis();
    }
    private void saveSummary(){
        Map<String,Long> types=new TreeMap<>(),states=new TreeMap<>();routes.values().forEach(r->types.merge(r.pageType(),1L,Long::sum));results.values().forEach(r->states.merge(text(r,"state"),1L,Long::sum));
        long unfinished=pages.values().stream().filter(DocumentProcessingSession::incomplete).count();
        List<Map<String,Object>> currentWindows=windows.entrySet().stream().filter(e->activeWindows==null||activeWindows.contains(e.getKey())).map(Map.Entry::getValue).toList();
        long pending=currentWindows.stream().filter(w->!Set.of("SUCCEEDED","NO_FACTS","RECOVERED","REVIEW_REQUIRED").contains(text(w,"status"))).count();
        long review=currentWindows.stream().filter(w->Boolean.TRUE.equals(w.get("reviewRequired"))||"REVIEW_REQUIRED".equals(text(w,"status"))).count();
        long noFacts=currentWindows.stream().filter(w->"NO_FACTS".equals(text(w,"status"))||!integers(w.get("noFactPages")).isEmpty()).count();
        List<Integer> noFactPages=currentWindows.stream().flatMap(w->integers(w.get("noFactPages")).stream()).distinct().sorted().toList();
        List<Integer> limitedPages=currentWindows.stream().flatMap(w->integers(w.get("coverageLimitedPages")).stream()).distinct().sorted().toList();
        List<String> coverageLimitations=currentWindows.stream().flatMap(w->strings(w.get("coverageLimitations")).stream()).distinct().sorted().toList();
        long pageReview=pageIssues.values().stream().filter(issues->!issues.isEmpty()).count();
        Map<String,Object> personCoverage=map(personComparison.get("coverage"));
        List<Map<String,Object>> personGroups=maps(personComparison.get("groups"));
        boolean personReview=!personGroups.isEmpty()||PersonCredentialConsistency.requiresReview(personCoverage);
        Set<String> occurrenceIds=new HashSet<>();pageOccurrenceIds.values().forEach(occurrenceIds::addAll);currentWindows.forEach(w->occurrenceIds.addAll(strings(map(w.get("factResult")).get("occurrenceIds"))));
        Map<String,Object> summary=fields("scope",scope,"checkScope",checkScope,"version",VERSION,"identity",identity,"documentId",metadata.id(),"documentName",metadata.name(),"role",metadata.role(),"documentVersion",metadata.version(),"documentHash",metadata.sha256(),"taskId",taskId,
            "processingMode",automaticVisualRequested?"STRUCTURED_VISUAL":"TEXT_ONLY","visualAnalysisStatus",automaticVisualRequested?"REQUESTED":"NOT_REQUESTED","automaticVisualRequested",automaticVisualRequested,
            "status",complete?(unfinished>0||pending>0?"PARTIAL_FAILED":review>0||noFacts>0||pageReview>0||personReview?"REVIEW_REQUIRED":"COMPLETED"):"PROCESSING","parsingComplete",complete,"processedPages",pages.size(),"incompletePages",unfinished,
            "pageTypes",types,"factCount",occurrenceIds.size(),"checkCount",checks.size(),"checkStates",states,"visualCalls",calls,"contextWindows",currentWindows.size(),"contextPending",pending,"contextReviewRequired",review,
            "pageReviewRequired",pageReview,"pageReviewIssues",pages.values().stream().filter(p->!pageIssues.getOrDefault(key(p),List.of()).isEmpty()).sorted(Comparator.comparingInt(Page::number)).limit(5)
                .map(p->fields("page",p.number(),"issues",pageIssues.get(key(p)).stream().limit(3).toList())).toList(),
            "contextCompleted",currentWindows.stream().filter(w->Set.of("SUCCEEDED","RECOVERED").contains(text(w,"status"))).count(),"contextNoFacts",noFacts,"contextNoFactPageCount",noFactPages.size(),"contextNoFactPages",noFactPages,
            "contextLimitedPages",limitedPages,"contextLimitedPageCount",limitedPages.size(),"contextCoverageLimitations",coverageLimitations,
            "visualDeferredForOcr",visualDeferredForOcr,"visualDeferralReason",visualDeferredForOcr?"DOCUMENT_OCR_INCOMPLETE":"",
            "visualBudget",fields("fullPlan",fullVisualPlan,"plannedJobs",visualPlannedJobs,"requiredCalls",visualRequiredCalls,"callLimit",visualCallLimit,"hardLimit",visualHardLimit,"limited",fullVisualPlan&&visualRequiredCalls>visualCallLimit),
            "personCredentialCoverage",personCoverage,"personCredentialGroupCount",personGroups.size(),
            "personCredentialGroups",personGroups.stream().limit(5).map(g->fields("id",g.get("id"),"status",g.get("status"),"fieldLabel",g.get("fieldLabel"),"reason",g.get("reason"),"sourcePages",integers(g.get("sourcePages")).stream().limit(20).toList())).toList(),
            "startedAtEpochMillis",started,"firstResultAtEpochMillis",firstResultAt,"firstResultMillis",firstResultAt==0?0:Math.max(0,firstResultAt-started),"updatedAt",now(),
            "preview",results.values().stream().sorted(Comparator.comparingInt(r->"REVIEW_REQUIRED".equals(r.get("state"))?0:1)).limit(5)
                .map(r->fields("checkpointId",r.get("checkpointId"),"title",r.get("title"),"severity",r.get("severity"),"state",r.get("state"),"reason",r.get("reason"),"sourcePages",integers(r.get("sourcePages")).stream().limit(20).toList())).toList(),
            "boundary","解析中的增量预检；未读范围不判缺失或全文通过，完整业务结论以正式检查为准。");
        store.save("processing_summary",summaryId(metadata.id()),metadata.projectId(),metadata.id(),summary);
        store.save("processing_task_summary","processing-task:"+taskId,metadata.projectId(),taskId,summary);
    }
    Document current(){return document(pages.values().stream().sorted(Comparator.comparingInt(Page::number)).toList());}
    /** Reuses validated page parses; no database-wide scan on each incremental callback. */
    private void refreshPersonComparison(){
        personComparison=new PersonCredentialConsistency().scan(current(),new ArrayList<>(personPages.values()));
        if(firstResultAt==0&&!maps(personComparison.get("groups")).isEmpty())firstResultAt=System.currentTimeMillis();
    }
    private PersonCredentialFacts.Result personLedgerCoverage(PersonCredentialFacts.Result result,Map<String,Object> indexed){
        if(result==null||!result.candidate())return result;
        List<Fact> accepted=indexed.containsKey("acceptedDomainFacts")?List.of(store.decode(store.encode(indexed.get("acceptedDomainFacts")),Fact[].class)):List.of();
        Set<String> keys=new HashSet<>();for(Fact fact:accepted)if("PERSON_CREDENTIAL".equals(fact.entityType()))keys.add(personFactKey(fact.entityKey(),fact.field(),fact.rawValue(),fact.normalizedValue(),fact.evidence()));
        List<PersonCredentialFacts.Occurrence> occurrences=result.occurrences().stream().filter(o->keys.contains(personFactKey(o.recordKey(),o.field(),o.rawValue(),o.value(),o.evidence()))).toList();
        List<Fact> facts=result.facts().stream().filter(f->keys.contains(personFactKey(f.entityKey(),f.field(),f.rawValue(),f.normalizedValue(),f.evidence()))).toList();
        boolean rejected=occurrences.size()!=result.occurrences().size()||facts.size()!=result.facts().size()||!maps(indexed.get("rejected")).isEmpty();
        List<Map<String,Object>> issues=new ArrayList<>(result.issues());if(rejected)issues.add(fields("code","PAGE_FACT_SOURCE_REJECTED","page",result.page(),"reason","本页部分字段未通过事实库来源核验，未参与人员证件比较，需核对原页。"));
        return new PersonCredentialFacts.Result(result.page(),result.locationType(),true,result.complete()&&!rejected,occurrences,issues,facts);
    }
    private void retainAcceptedPersonFacts(String key){
        PersonCredentialFacts.Result accepted=personPages.get(key);if(accepted==null)return;
        List<Fact> safe=new ArrayList<>(facts.getOrDefault(key,List.of()).stream().filter(f->!"PERSON_CREDENTIAL".equals(f.entityType())).toList());safe.addAll(accepted.facts());facts.put(key,List.copyOf(safe));
    }
    private static String personFactKey(String record,String field,String raw,String value,Evidence evidence){
        return record+"\u0000"+field+"\u0000"+raw+"\u0000"+value+"\u0000"+evidence.documentId()+"\u0000"+evidence.documentName()+"\u0000"+evidence.locationType()+":"+evidence.page()+"\u0000"+evidence.anchor()+"\u0000"+evidence.quote();
    }
    private Document document(List<Page> list){return new Document(metadata.id(),metadata.projectId(),metadata.role(),metadata.name(),metadata.version(),metadata.sha256(),metadata.createdAt(),list,
        complete?List.of():List.of("[OCR未完成] 文件仍在处理，未读取范围不能判定缺失或全部通过。"));}
    private List<Fact> allFacts(){return IncrementalCheckEngine.distinctEvidence(java.util.stream.Stream.concat(contextFacts.values().stream(),facts.values().stream()).flatMap(Collection::stream).toList());}
    private static Set<Integer> completedPages(Map<String,Object> row){Set<Integer> result=new HashSet<>();if(row!=null)for(Map<String,Object> p:maps(map(row.get("response")).get("pageResults")))if("COMPLETED".equals(p.get("status")))result.add(number(p.get("page")));return result;}
    private static List<Integer> integers(Object o){return o instanceof List<?> l?l.stream().filter(v->v instanceof Number).map(DocumentProcessingSession::number).toList():List.of();}
    private static Set<String> strings(Object o){return o instanceof List<?> l?new LinkedHashSet<>(l.stream().filter(v->v instanceof String).map(Object::toString).toList()):Set.of();}
    static String summaryId(String doc){return "processing:"+doc;}
    static String key(Page p){return p.locationType()+":"+p.number();}
    private static String pageId(String scope,Page p){return hash("processing-page:"+scope+":"+key(p));}
    private static int number(Object o){return o instanceof Number n?n.intValue():0;}
    private static long longNumber(Object o){return o instanceof Number n?n.longValue():0;}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object o){return o instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();}
    private static List<Map<String,Object>> maps(Object o){return o instanceof List<?> l?l.stream().filter(v->v instanceof Map).map(DocumentProcessingSession::map).toList():List.of();}
}
