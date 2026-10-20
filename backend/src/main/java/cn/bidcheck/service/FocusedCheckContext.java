package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import cn.bidcheck.verification.LayoutEvidenceIndex;
import cn.bidcheck.verification.facts.FactLedgerService;
import cn.bidcheck.verification.source.EvidenceLayoutService;
import cn.bidcheck.verification.evidence.EvidenceOperationRepository;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.bidcheck.service.BidService.*;

/** Ordinary CHECK adapter context. All worker writes are fenced independently of task progress. */
@Service
public final class FocusedCheckContext {
    public static final String VERSION="focused-context-3-prior-graph-candidates";
    private final Store store;private final FactLedgerService ledger;private final EvidenceLayoutService layouts;private final EvidenceOperationRepository evidenceOperations;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.verification.source.VisualVerificationService visuals;
    public String identity(){return hash(VERSION+ledger.identity()+layouts.identity());}
    public FocusedCheckContext(Store store,FactLedgerService ledger,EvidenceLayoutService layouts,PlatformTransactionManager manager){
        this.store=store;this.ledger=ledger;this.layouts=layouts;this.evidenceOperations=new EvidenceOperationRepository(store);this.tx=new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);this.tx.setTimeout(25);
    }
    public Function<Checkpoint,Map<String,Object>> prepare(TaskRow task,String runId,Document bid,Document tender,
            String engineIdentity,List<Map<String,Object>> processingOccurrences,BooleanSupplier permit){
        String project=task.projectId,taskId=task.id,owner=task.leaseOwner;int attempt=task.attempt;
        List<Document> documents=List.of(bid);
        // Observation storage is independent of business-rule/requirement compiler versions.
        // Current proof verification still revalidates every reused observation against frozen source.
        String factIdentity=hash("focused-observation-ledger-1"+ledger.identity()+layouts.identity());
        var manifest=ledger.prepareManifest(project,factIdentity,documents);
        Runnable fence=()->{
            if(Thread.currentThread().isInterrupted())throw new CancellationException("检查已中断");
            TaskRow current=store.mapper().lockTask(taskId);
            if(current==null||!"RUNNING".equals(current.status)||!project.equals(current.projectId)||!Objects.equals(owner,current.leaseOwner)
                ||attempt!=current.attempt||(owner!=null&&(current.leaseUntil==null||current.leaseUntil<System.currentTimeMillis())))throw new TaskLeaseLostException(taskId);
            Map<String,Object> run=store.map(store.lock("run",runId).body);
            if(!taskId.equals(run.get("taskId"))||!"RUNNING".equals(run.get("status")))throw new TaskLeaseLostException(taskId);
        };
        Consumer<Runnable> write=action->tx.executeWithoutResult(s->{fence.run();action.run();fence.run();});
        Map<String,Object> base=new LinkedHashMap<>();
        base.put("focusedCheck",true);base.put("unlimitedModelCalls",true);base.put("modelAttemptPermit",permit);
        base.put("allowModelRuleCompilation",false);
        base.put("sourceRunId",runId);base.put("evaluationStage","BID_SUBMISSION");base.put("documentSetComplete",false);
        base.put("tenderDocuments",tender==null?List.of():List.of(tender));
        base.put("requirementProjectId",project);
        base.put("requirementArtifactLookup",(Function<Map<String,Object>,Map<String,Object>>)request->{
            if(!validRequirementArtifactScope(request,project,tender))return Map.of();
            String id=stableId("requirement-artifact:"+project+":"+text(request,"artifactId"));
            Stored row=store.mapper().get(id);
            if(row==null||!"requirement_artifact".equals(row.kind)||!project.equals(row.projectId))return Map.of();
            Map<String,Object> artifact=store.map(row.body);
            if(!validRequirementArtifactScope(artifact,project,tender))return Map.of();
            for(String key:List.of("artifactId","compilerPolicyVersion","modelIdentity","inputFingerprint"))
                if(!Objects.equals(request.get(key),artifact.get(key)))return Map.of();
            return artifact;
        });
        base.put("requirementArtifactPublisher",(Consumer<Map<String,Object>>)artifact->{
            if(!validRequirementArtifactScope(artifact,project,tender))throw new IllegalArgumentException("REQUIREMENT_ARTIFACT_SCOPE_INVALID");
            String id=stableId("requirement-artifact:"+project+":"+text(artifact,"artifactId"));
            Map<String,Object> body=new LinkedHashMap<>(artifact);body.put("publishedAt",now());
            Store.PreparedRecord prepared=store.prepareRecord("requirement_artifact",id,project,tender.id(),body);
            write.accept(()->store.savePrepared(prepared));
        });
        Map<String,Object> dates=referenceDates(tender);base.put("referenceDates",dates);
        if(dates.get("BID_DEADLINE") instanceof Map<?,?> deadline){base.putAll(stringMap(deadline));}
        base.put("layoutIdentity",layouts.identity());base.put("processingOccurrences",List.copyOf(processingOccurrences));
        base.put("sharedFactIdentity",factIdentity);
        base.put("sharedFactLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->ledger.lookup(manifest,request));
        base.put("evidenceOperationLookup",(Function<Map<String,Object>,Map<String,Object>>)request->evidenceOperations.completed(project,text(request,"operationKey")).orElse(Map.of()));
        base.put("evidenceOperationRepository",evidenceOperations);
        // Reuse full layout if it exists; otherwise adapt only the selected OCR pages, without another document pass.
        Map<String,LayoutEvidenceIndex> pageLayouts=new ConcurrentHashMap<>();
        String layoutId=stableId("layout:"+bid.id()+":"+layouts.cacheIdentity(bid));Stored saved=store.mapper().get(layoutId);
        LayoutEvidenceIndex existing=new LayoutEvidenceIndex();
        boolean cached=saved!=null&&"verification_layout".equals(saved.kind)&&project.equals(saved.projectId)&&"COMPLETE".equals(store.map(saved.body).get("status"));
        if(cached)existing.add(bid.id(),store.map(saved.body));
        base.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->{
            if(!bid.id().equals(text(request,"documentId")))return List.of();
            if(cached)return existing.apply(request);
            int location=request.get("location") instanceof Number n?n.intValue():-1;if(location<1)return List.of();
            String key=text(request,"locationType")+":"+location;
            LayoutEvidenceIndex index=pageLayouts.computeIfAbsent(key,k->{LayoutEvidenceIndex value=new LayoutEvidenceIndex();
                value.add(bid.id(),layouts.transcribedLayout(bid,new HashSet<>(List.of(Math.max(1,location-1),location,location+1))));return value;});
            return index.apply(request);
        });
        write.accept(()->{Map<String,Object> run=store.getMap("run",runId);run.put("atomicCheckVersion",engineIdentity);
            run.put("atomicReferenceDates",dates);run.put("atomicFactIdentity",factIdentity);store.save("run",runId,project,bid.id(),run);});
        return cp->{
            Map<String,Object> context=new LinkedHashMap<>(base);String parent=stableId(runId+":atomic-steps:"+cp.id());
            List<Map<String,Object>> prior=new ArrayList<>();String previousRun=text(store.getMap("run",runId),"parentRunId");
            if(!previousRun.isBlank()){
                Map<String,Object> previous=store.getMap("run",previousRun);
                if(project.equals(text(previous,"projectId"))&&bid.id().equals(text(previous,"documentId")))
                    for(Map<String,Object> step:store.maps("check_atomic_step",project,stableId(previousRun+":atomic-steps:"+cp.id()))){Map<String,Object> inherited=new LinkedHashMap<>(step);inherited.put("paidInPriorRevision",true);prior.add(inherited);}
            }
            prior.addAll(store.maps("check_atomic_step",project,parent));context.put("priorSteps",prior);
            context.put("visualObservations",store.maps("check_visual_observation",project,runId).stream()
                .filter(v->cp.id().equals(text(v,"checkpointId"))&&bid.id().equals(text(v,"documentId")))
                .filter(v->visuals!=null&&visuals.identity().equals(text(v,"observationIdentity")))
                .filter(v->{Map<String,Object> source=CheckResolution.map(v.get("source"));return bid.sha256().equals(text(source,"documentHash"))
                    &&source.get("documentVersion") instanceof Number version&&version.intValue()==bid.version();}).toList());
            context.put("externalRecords",store.maps("check_external_record",project,runId).stream()
                .filter(v->cp.id().equals(text(v,"checkpointId"))).map(v->CheckResolution.map(v.get("record")))
                .filter(v->"TRUSTED_CONNECTOR".equals(text(v,"provenanceStatus"))).toList());
            context.put("stepObserver",(Consumer<Map<String,Object>>)step->{
                Map<String,Object> value=new LinkedHashMap<>(step);value.put("checkpointId",cp.id());value.put("runId",runId);value.put("savedAt",now());
                String id=stableId(parent+":"+text(step,"id")+":"+text(step,"status"));Store.PreparedRecord prepared=store.prepareRecord("check_atomic_step",id,project,parent,value);
                write.accept(()->store.savePrepared(prepared));
            });
            context.put("sharedFactPublisher",(Consumer<Map<String,Object>>)batch->{
                // Split persistence, never truncate facts. A partial batch must not claim full extraction coverage.
                List<Map<String,Object>> batches=publicationBatches(batch);
                var prepared=batches.stream().map(part->ledger.prepareBatch(manifest,runId,cp.id(),part)).toList();
                for(int part=0;part<prepared.size();part++){
                    var value=prepared.get(part);int index=part;
                    write.accept(()->{store.lock("project",project);Map<String,Object> receipt=ledger.publishPrepared(value);
                        store.save("check_atomic_publication",stableId(parent+":"+text(batch,"operationIdentity")+":"+index),project,parent,
                            fields("runId",runId,"checkpointId",cp.id(),"part",index,"parts",prepared.size(),"batchDigest",value.digest(),"receipt",receipt));});
                }
                List<Map<String,Object>> coverage=maps(batch.get("fieldCoverage"));Map<String,Object> partition=CheckResolution.map(batch.get("observationPartition"));
                boolean complete="EXTRACTED".equals(batch.get("origin"))&&prepared.size()==1&&number(partition.get("parts"),1)==1&&!coverage.isEmpty()
                    &&coverage.stream().allMatch(row->Boolean.TRUE.equals(row.get("sourceScopeComplete"))&&Set.of("EXTRACTED","READ_NOT_FOUND").contains(text(row,"status")));
                if(complete&&batch.get("evidenceOperationDescriptor") instanceof Map<?,?>){Map<String,Object> descriptor=CheckResolution.map(batch.get("evidenceOperationDescriptor"));String key=text(batch,"evidenceOperationKey");
                    write.accept(()->evidenceOperations.publishCompleted(project,key,descriptor,fields("facts",batch.getOrDefault("facts",List.of()),"sources",batch.getOrDefault("sources",List.of()),"fieldCoverage",coverage)));
                }
            });
            return context;
        };
    }
    static boolean validRequirementArtifactScope(Map<String,Object> value,String project,Document tender){
        if(tender==null||!"TENDER".equals(tender.role())||!project.equals(tender.projectId())||!project.equals(text(value,"projectId"))
            ||!tender.id().equals(text(value,"documentId"))||!tender.sha256().equals(text(value,"documentHash")))return false;
        if(!(value.get("documentVersion") instanceof Number version)||version.doubleValue()!=tender.version())return false;
        for(String key:List.of("artifactId","compilerPolicyVersion","modelIdentity","inputFingerprint"))if(text(value,key).isBlank())return false;
        return true;
    }
    private List<Map<String,Object>> publicationBatches(Map<String,Object> batch){
        List<Map<String,Object>> facts=maps(batch.get("facts")),sources=maps(batch.get("sources"));
        if(facts.size()<=10&&sources.size()<=32&&store.encode(batch).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=128*1024)return List.of(batch);
        List<Map<String,Object>> out=new ArrayList<>(),part=new ArrayList<>();
        for(Map<String,Object> fact:facts){
            List<Map<String,Object>> candidate=new ArrayList<>(part);candidate.add(fact);Map<String,Object> value=publicationPart(batch,candidate,sources);
            if(!part.isEmpty()&&(candidate.size()>10||maps(value.get("sources")).size()>32||store.encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>128*1024)){
                out.add(publicationPart(batch,part,sources));part=new ArrayList<>();
            }
            part.add(fact);
        }
        if(!part.isEmpty())out.add(publicationPart(batch,part,sources));
        // An inspected field with no fact is still an observation; do not silently lose its receipt.
        if(facts.isEmpty())for(int start=0;start<sources.size();start+=16){
            List<Map<String,Object>> sourcePart=sources.subList(start,Math.min(start+16,sources.size()));
            Map<String,Object> value=new LinkedHashMap<>(batch);value.put("sources",List.copyOf(sourcePart));value.remove("extractionCoverage");
            value.put("fieldCoverage",restrictedFieldCoverage(batch,List.of(),sourcePart));
            value.put("operationIdentity",hash(text(batch,"operationIdentity")+store.encode(sourcePart)));out.add(value);
        }
        return out;
    }
    private Map<String,Object> publicationPart(Map<String,Object> batch,List<Map<String,Object>> facts,List<Map<String,Object>> sources){
        Map<String,Object> part=new LinkedHashMap<>(batch);part.put("facts",List.copyOf(facts));part.remove("extractionCoverage");
        Set<String> ids=new HashSet<>();for(Map<String,Object> fact:facts)if(fact.get("sourceIds") instanceof List<?> refs)refs.forEach(id->ids.add(String.valueOf(id)));
        List<Map<String,Object>> selected=sources.stream().filter(source->ids.contains(text(source,"id"))).toList();part.put("sources",selected);
        part.put("fieldCoverage",restrictedFieldCoverage(batch,facts,selected));
        part.put("operationIdentity",hash(text(batch,"operationIdentity")+store.encode(facts)));part.put("coveragePolicy","PARTIAL_BATCH_NO_FULL_COVERAGE_CLAIM");return part;
    }
    private static List<Map<String,Object>> restrictedFieldCoverage(Map<String,Object> batch,List<Map<String,Object>> facts,List<Map<String,Object>> sources){
        Set<String> availableSources=new HashSet<>(),availableFacts=new HashSet<>();sources.forEach(s->availableSources.add(text(s,"id")));facts.forEach(f->availableFacts.add(text(f,"id")));
        List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> raw:maps(batch.get("fieldCoverage"))){
            List<String> refs=raw.get("sourceIds") instanceof List<?> rows?rows.stream().map(String::valueOf).toList():List.of();
            List<String> ids=raw.get("factIds") instanceof List<?> rows?rows.stream().map(String::valueOf).toList():List.of();
            List<String> scoped=refs.stream().filter(availableSources::contains).toList(),kept=ids.stream().filter(availableFacts::contains).toList();
            if(scoped.isEmpty()||("EXTRACTED".equals(text(raw,"status"))&&kept.isEmpty()))continue;
            Map<String,Object> value=new LinkedHashMap<>(raw);value.put("sourceIds",scoped);value.put("factIds",kept);
            if(!availableSources.containsAll(refs)||!availableFacts.containsAll(ids))value.put("sourceScopeComplete",false);
            result.add(value);
        }return result;
    }
    /** Only explicit, unique event dates in the frozen tender. Conflicting notices remain unresolved. */
    static Map<String,Object> referenceDates(Document tender){
        if(tender==null)return Map.of();Map<String,Object> result=new LinkedHashMap<>();
        Map<String,Pattern> labels=Map.of("BID_DEADLINE",Pattern.compile("(?:投标(?:文件(?:递交的?)?)?截止(?:时间|日期)?|文件递交截止(?:时间|日期)?|递交截止(?:时间|日期)?)"),
            "OPENING_DATE",Pattern.compile("开标(?:时间|日期)"));
        Pattern date=Pattern.compile("(20\\d{2})\\s*[年/.-]\\s*(\\d{1,2})\\s*[月/.-]\\s*(\\d{1,2})\\s*日?");
        for(var event:labels.entrySet()){
            Map<LocalDate,Map<String,Object>> matches=new LinkedHashMap<>();
            for(Page page:tender.pages()){
                String source=Objects.toString(page.text(),"");Matcher lines=Pattern.compile("[^\\r\\n]+").matcher(source);
                List<int[]> offsets=new ArrayList<>();while(lines.find())offsets.add(new int[]{lines.start(),lines.end()});
                for(int i=0;i<offsets.size();i++){
                    int[] pos=offsets.get(i);String line=source.substring(pos[0],pos[1]);Matcher label=event.getValue().matcher(line);if(!label.find())continue;
                    if(line.substring(0,label.start()).matches("(?s).*(?:原定|原计划|此前|暂定|预计|拟定|示例|举例|例如)[\\s：:]*$"))continue;
                    // Relative notices do not establish a calendar date. A later unrelated field is not a continuation.
                    String value=line.substring(label.end());int quoteEnd=pos[1];
                    if(value.matches("[\\s：:为是|]*")&&i+1<offsets.size()){int[] next=offsets.get(i+1);value=source.substring(next[0],next[1]);quoteEnd=next[1];}
                    // Accept only a directly assigned event date; relative deadlines or notice dates are not that event.
                    String assigned=value.replaceFirst("^[\\s：:|]*(?:(?:定于|确定为|调整为|延期至|变更为|为|是)[\\s：:|]*)?","");
                    Matcher direct=date.matcher(assigned);if(!direct.lookingAt())continue;
                    String tail=assigned.substring(direct.end());
                    if(tail.matches("(?s)^\\s*(?:发布|发出|通知|印发|公告|之前|之后|前|后).*"))continue;
                    Matcher boundary=Pattern.compile("[；;。]|(?:合同|工期|答疑|澄清|保证金|购买|发售|开标|投标截止|递交截止).{0,8}(?:时间|日期|起算)").matcher(assigned);
                    if(boundary.find())assigned=assigned.substring(0,boundary.start());
                    if(assigned.matches("(?s).*(?:暂定|预计|拟定|待定|另行通知|以.{0,32}(?:通知|公告).{0,8}为准).*"))continue;
                    Matcher dates=date.matcher(assigned);
                    while(dates.find()){try{
                        LocalDate day=LocalDate.of(Integer.parseInt(dates.group(1)),Integer.parseInt(dates.group(2)),Integer.parseInt(dates.group(3)));
                        String quote=source.substring(pos[0],quoteEnd);
                        Map<String,Object> evidence=fields("documentId",tender.id(),"documentName",tender.name(),"page",page.number(),"locationType",page.locationType(),"quote",quote,
                            "anchor","p"+page.number()+"-offset"+pos[0],"confirmation","SOURCE_UNAMBIGUOUS","confirmed",true,"event",event.getKey());
                        matches.putIfAbsent(day,fields("referenceDate",day.toString(),"referenceDateSource",tender.id(),"referenceDateEvidence",evidence));
                    }catch(DateTimeException ignored){}}
                }
            }
            if(matches.size()==1)result.put(event.getKey(),matches.values().iterator().next());
        }
        return result;
    }
    private static List<Map<String,Object>> maps(Object raw){if(!(raw instanceof List<?> list))return List.of();return list.stream().filter(Map.class::isInstance).map(v->stringMap((Map<?,?>)v)).toList();}
    private static int number(Object raw,int fallback){
        if(raw instanceof Number value)return value.intValue();
        try{return raw==null?fallback:Integer.parseInt(String.valueOf(raw));}catch(NumberFormatException ignored){return fallback;}
    }
    private static Map<String,Object> stringMap(Map<?,?> source){Map<String,Object> value=new LinkedHashMap<>();source.forEach((k,v)->value.put(String.valueOf(k),v));return value;}
}
