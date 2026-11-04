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
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(FocusedCheckContext.class);
    public static final String VERSION="focused-context-4-scoped-supplement-layout-audit";
    private final Store store;private final FactLedgerService ledger;private final EvidenceLayoutService layouts;private final EvidenceOperationRepository evidenceOperations;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.verification.source.VisualVerificationService visuals;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private InferenceConfirmationService inferenceConfirmations;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.verification.external.ExternalVerificationAdapter externalAdapter;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.storage.ArtifactStorage artifactStorage;
    @org.springframework.beans.factory.annotation.Value("${bidcheck.agent.inference-normalization-min-confidence:0.9}")
    private String inferenceNormalizationConfidence="0.9";
    public String identity(){return hash(VERSION+ledger.identity()+layouts.identity()+"|inference-min="+inferenceNormalizationConfidence);}
    public FocusedCheckContext(Store store,FactLedgerService ledger,EvidenceLayoutService layouts,PlatformTransactionManager manager){
        this.store=store;this.ledger=ledger;this.layouts=layouts;this.evidenceOperations=new EvidenceOperationRepository(store);this.tx=new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);this.tx.setTimeout(25);
    }
    public Function<Checkpoint,Map<String,Object>> prepare(TaskRow task,String runId,Document bid,Document tender,
            String engineIdentity,List<Map<String,Object>> processingOccurrences,BooleanSupplier permit){
        String project=task.projectId,taskId=task.id,owner=task.leaseOwner;int attempt=task.attempt;
        List<Document> documents=cn.bidcheck.compat.Java8Api.listOf(bid);
        // Observation storage is independent of business-rule/requirement compiler versions.
        // Current proof verification still revalidates every reused observation against frozen source.
        String factIdentity=hash("focused-observation-ledger-1"+ledger.identity()+layouts.observationIdentity());
        cn.bidcheck.verification.facts.FactLedgerService.PreparedManifest manifest=ledger.prepareManifest(project,factIdentity,documents);
        Runnable fence=()->{
            if(Thread.currentThread().isInterrupted())throw new CancellationException("检查已中断");
            TaskRow current=store.mapper().lockTask(taskId);
            if(current==null||!"RUNNING".equals(current.status)||!project.equals(current.projectId)||!Objects.equals(owner,current.leaseOwner)
                ||attempt!=current.attempt||(owner!=null&&(current.leaseUntil==null||current.leaseUntil<System.currentTimeMillis())))throw new TaskLeaseLostException(taskId);
            Map<String,Object> run=store.map(store.lock("run",runId).body);
            if(!taskId.equals(run.get("taskId"))||!"RUNNING".equals(run.get("status")))throw new TaskLeaseLostException(taskId);
        };
        Consumer<Runnable> write=action->DbTransactionRetry.executeWithoutResult(tx,"ATOMIC_EVIDENCE_PUBLISH",cn.bidcheck.compat.Java8Api.mapOf("taskId", taskId, "runId", runId),s->{fence.run();action.run();fence.run();});
        Map<String,Object> base=new LinkedHashMap<>();
        String reviewMode=CheckReviewMode.of(store.getMap("run",runId));base.put("reviewMode",reviewMode);
        if(externalAdapter!=null){
            base.put("providerConfigurationKnown",!"CONFIGURATION_INVALID".equals(externalAdapter.identity()));
            base.put("providerSources",externalAdapter.providerSources());
        }
        base.put("focusedCheck",true);base.put("unlimitedModelCalls",true);base.put("modelAttemptPermit",permit);
        base.put("inferenceNormalizationMinConfidence",inferenceNormalizationConfidence);
        base.put("allowModelRuleCompilation",false);
        base.put("sourceRunId",runId);base.put("evaluationStage","BID_SUBMISSION");base.put("documentSetComplete",false);
        base.put("tenderDocuments",tender==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf(tender));
        base.put("requirementProjectId",project);
        base.put("requirementDependencyEngineIdentity",engineIdentity);
        base.put("_requirementDependencySnapshot",(Supplier<Map<String,Object>>)(()->requirementDependencySnapshot(project,runId,bid,tender,engineIdentity)));
        base.put("requirementArtifactLookup",(Function<Map<String,Object>,Map<String,Object>>)request->{
            if(!validRequirementArtifactScope(request,project,tender))return cn.bidcheck.compat.Java8Api.mapOf();
            String id=stableId("requirement-artifact:"+project+":"+text(request,"artifactId"));
            Stored row=store.mapper().get(id);
            if(row==null||!"requirement_artifact".equals(row.kind)||!project.equals(row.projectId))return cn.bidcheck.compat.Java8Api.mapOf();
            Map<String,Object> artifact=store.map(row.body);
            if(!validRequirementArtifactScope(artifact,project,tender))return cn.bidcheck.compat.Java8Api.mapOf();
            for(String key:cn.bidcheck.compat.Java8Api.listOf("artifactId", "compilerPolicyVersion", "modelIdentity", "inputFingerprint"))
                if(!Objects.equals(request.get(key),artifact.get(key)))return cn.bidcheck.compat.Java8Api.mapOf();
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
        if(dates.get("BID_DEADLINE") instanceof Map<?,?>){base.putAll(stringMap(((Map<?,?>) (dates.get("BID_DEADLINE")))));}
        base.put("layoutIdentity",layouts.identity());base.put("processingOccurrences",cn.bidcheck.compat.Java8Api.listCopyOf(processingOccurrences));
        if(inferenceConfirmations!=null)base.put("confirmedInferenceLookup",(Supplier<List<Map<String,Object>>>)(()->inferenceConfirmations.sharedConfirmedForRun(runId,project,bid,tender)));
        if(inferenceConfirmations!=null)base.put("inferenceDecisionsLookup",(Supplier<List<Map<String,Object>>>)(()->inferenceConfirmations.decisionsForRun(runId,project,bid,tender)));
        base.put("sharedFactIdentity",factIdentity);
        base.put("sharedFactLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->ledger.lookup(manifest,request));
        base.put("evidenceOperationLookup",(Function<Map<String,Object>,Map<String,Object>>)request->evidenceOperations.completed(project,text(request,"operationKey")).orElse(cn.bidcheck.compat.Java8Api.mapOf()));
        base.put("evidenceOperationRepository",evidenceOperations);
        // Reuse full layout if it exists; otherwise adapt only the selected OCR pages, without another document pass.
        Map<String,LayoutEvidenceIndex> pageLayouts=new ConcurrentHashMap<>();
        Map<String,Map<String,Object>> pageAudits=new ConcurrentHashMap<>();
        String layoutId=stableId("layout:"+bid.id()+":"+layouts.cacheIdentity(bid));Stored saved=store.mapper().get(layoutId);
        LayoutEvidenceIndex existing=new LayoutEvidenceIndex();
        boolean cached=saved!=null&&"verification_layout".equals(saved.kind)&&project.equals(saved.projectId)&&"COMPLETE".equals(store.map(saved.body).get("status"));
        if(cached)existing.add(bid.id(),store.map(saved.body));
        Function<Integer,Map<String,Object>> nativePages=layouts.nativePdfLayouts(bid,()->{if(artifactStorage==null)throw new java.io.IOException("NATIVE_TABLE_STORAGE_UNAVAILABLE");return artifactStorage.materialize(bid.id(),bid.sha256());});
        Function<Integer,Map<String,Object>> scanPages=layouts.documentPageLayouts(bid,()->{if(artifactStorage==null)throw new java.io.IOException("PAGE_LAYOUT_STORAGE_UNAVAILABLE");return artifactStorage.materialize(bid.id(),bid.sha256());},true);
        base.put("layoutAuditLookup",(Function<Map<String,Object>,Map<String,Object>>)request->pageAudits.getOrDefault(text(request,"locationType")+":"+request.get("location"),cn.bidcheck.compat.Java8Api.mapOf()));
        base.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->{
            if(!bid.id().equals(text(request,"documentId")))return cn.bidcheck.compat.Java8Api.listOf();
            List<Map<String,Object>> preserved=cached?existing.apply(request):cn.bidcheck.compat.Java8Api.listOf();
            if(preserved.stream().anyMatch(r->"TABLE_CELL".equals(r.get("kind"))))return preserved;
            int location=request.get("location") instanceof Number?((Number) (request.get("location"))).intValue():-1;if(location<1)return cn.bidcheck.compat.Java8Api.listOf();
            String locationKey=text(request,"locationType")+":"+location;
            boolean allowScan=Boolean.TRUE.equals(request.get("allowScan"));
            LayoutEvidenceIndex completed=pageLayouts.get(locationKey+":SCAN");
            if(completed!=null&&completed.apply(request).stream().anyMatch(r->"TABLE_CELL".equals(r.get("kind"))))return completed.apply(request);
            String key=locationKey+(allowScan?":SCAN":":NATIVE");
            LayoutEvidenceIndex index=pageLayouts.computeIfAbsent(key,k->{LayoutEvidenceIndex value=new LayoutEvidenceIndex();
                Map<String,Object> transcribed=layouts.transcribedLayout(bid,cn.bidcheck.compat.Java8Api.setOf(location));List<Map<String,Object>> spans=new ArrayList<>(CheckResolution.maps(transcribed.get("spans")));
                if(spans.stream().noneMatch(r->"TABLE_CELL".equals(r.get("kind")))&&"PAGE".equals(text(request,"locationType"))){Map<String,Object> nativeLayout=(allowScan?scanPages:nativePages).apply(location);spans.addAll(CheckResolution.maps(nativeLayout.get("spans")));
                    Map<String,Object> audit=new LinkedHashMap<>();for(String field:cn.bidcheck.compat.Java8Api.listOf("pageLayoutAudit", "nativeTableAudit", "structureStatus", "diagnosticCode", "errorCode", "limitations"))if(nativeLayout.containsKey(field))audit.put(field,nativeLayout.get(field));
                    audit.put("allowScan",allowScan);audit.put("documentId",bid.id());audit.put("page",location);audit.put("returnedCells",spans.stream().filter(r->"TABLE_CELL".equals(r.get("kind"))).count());pageAudits.put(locationKey,audit);
                    LOG.info("bidcheck.page_layout runId={} documentId={} page={} cells={} allowScan={} status={} diagnosis={}",runId,bid.id(),location,audit.get("returnedCells"),allowScan,nativeLayout.get("structureStatus"),nativeLayout.get("diagnosticCode"));}
                Map<String,Object> merged=new LinkedHashMap<>(transcribed);merged.put("spans",spans);value.add(bid.id(),merged);return value;});
            List<Map<String,Object>> selected=index.apply(request);return selected.isEmpty()?preserved:selected;
        });
        write.accept(()->{Map<String,Object> run=store.getMap("run",runId);run.put("atomicCheckVersion",engineIdentity);
            run.put("atomicReferenceDates",dates);run.put("atomicFactIdentity",factIdentity);store.save("run",runId,project,bid.id(),run);});
        List<Map<String,Object>> supplementManifest=CheckResolution.maps(store.getMap("run",runId).get("supplementManifest"));
        Map<String,Function<Map<String,Object>,List<Map<String,Object>>>> supplementLayouts=new ConcurrentHashMap<>();
        Map<String,Map<String,Object>> supplementAudits=new ConcurrentHashMap<>();
        return cp->{
            Map<String,Object> context=new LinkedHashMap<>(base);String parent=stableId(runId+":atomic-steps:"+cp.id());
            List<Document> supplements=new ArrayList<>();
            for(Map<String,Object> item:supplementManifest){
                if(!cp.id().equals(text(item,"checkpointId"))&&!(item.get("affectedCheckpointIds") instanceof List<?>&&((List<?>) (item.get("affectedCheckpointIds"))).contains(cp.id())))continue;
                Document extra=store.get("document",text(item,"documentId"),Document.class);
                if(!project.equals(extra.projectId())||!"SUPPLEMENT".equals(extra.role())||!extra.sha256().equals(text(item,"sha256"))||extra.version()!=number(item.get("version"),-1))throw new IllegalStateException("SUPPLEMENT_FROZEN_SOURCE_MISMATCH");
                supplements.add(extra);
            }
            List<Document> scopedDocuments=new ArrayList<>(documents);scopedDocuments.addAll(supplements);
            cn.bidcheck.verification.facts.FactLedgerService.PreparedManifest itemManifest=supplements.isEmpty()?manifest:ledger.prepareManifest(project,factIdentity,scopedDocuments);
            context.put("supplementDocuments",cn.bidcheck.compat.Java8Api.listCopyOf(supplements));
            context.put("sharedFactLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->ledger.lookup(itemManifest,request));
            if(!supplements.isEmpty()){
                @SuppressWarnings("unchecked") Function<Map<String,Object>,List<Map<String,Object>>> originalLookup=(Function<Map<String,Object>,List<Map<String,Object>>>)base.get("layoutLookup");
                @SuppressWarnings("unchecked") Function<Map<String,Object>,Map<String,Object>> originalAudit=(Function<Map<String,Object>,Map<String,Object>>)base.get("layoutAuditLookup");
                context.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->{
                    if(bid.id().equals(text(request,"documentId")))return originalLookup.apply(request);
                    Document extra=supplements.stream().filter(d->d.id().equals(text(request,"documentId"))).findFirst().orElse(null);
                    return extra==null?cn.bidcheck.compat.Java8Api.listOf():supplementLayouts.computeIfAbsent(extra.id(),id->supplementLayout(extra,supplementAudits)).apply(request);
                });
                context.put("layoutAuditLookup",(Function<Map<String,Object>,Map<String,Object>>)request->bid.id().equals(text(request,"documentId"))?originalAudit.apply(request):supplementAudits.getOrDefault(text(request,"documentId")+":"+request.get("location"),cn.bidcheck.compat.Java8Api.mapOf()));
            }
            if(cn.bidcheck.verification.engine.SourceRequirementInventory.aggregateCheckpoint(cp)){
                Map<String,Object> dependency=requirementDependencySnapshot(project,runId,bid,tender,engineIdentity);
                context.put("_requirementDependencySnapshot",(Supplier<Map<String,Object>>)(()->dependency));
                context.put("currentRequirementFindings",dependency.getOrDefault("findings",cn.bidcheck.compat.Java8Api.listOf()));
                context.put("requirementDependencyInputsVerified",dependency.getOrDefault("verified",false));
                context.put("requirementDependencyRevision",dependency.getOrDefault("revision",""));
            }
            context.put("inferenceCheckpointId",cp.id());
            if(inferenceConfirmations!=null)context.put("inferencePublisher",(Consumer<Map<String,Object>>)claim->write.accept(()->inferenceConfirmations.capture(runId,cp.id(),bid,tender,claim)));
            context.put("ordinaryRecoveryObservedRevision",OrdinaryRecoveryDependencies.currentRevision(store,project,runId));
            String factRevision=ledger.lookupRevision(itemManifest);
            if(!cn.bidcheck.compat.Java8Api.isBlank(factRevision))context.put("sharedFactRevision",factRevision);
            Map<String,Object> currentRun=store.getMap("run",runId);
            List<Map<String,Object>> prior=new ArrayList<>();String previousRun=text(currentRun,"parentRunId");
            if(!cn.bidcheck.compat.Java8Api.isBlank(previousRun)){
                Map<String,Object> previous=store.getMap("run",previousRun);
                if(project.equals(text(previous,"projectId"))&&bid.id().equals(text(previous,"documentId"))&&reviewMode.equals(CheckReviewMode.of(previous)))
                    for(Map<String,Object> step:store.maps("check_atomic_step",project,stableId(previousRun+":atomic-steps:"+cp.id()))){Map<String,Object> inherited=new LinkedHashMap<>(step);inherited.put("paidInPriorRevision",true);prior.add(inherited);}
            }
            prior.addAll(store.maps("check_atomic_step",project,parent));context.put("priorSteps",prior);
            context.put("visualObservations",cn.bidcheck.compat.Java8Api.toList(store.maps("check_visual_observation",project,runId).stream()
                .filter(v->cp.id().equals(text(v,"checkpointId"))&&bid.id().equals(text(v,"documentId")))
                .filter(v->visuals!=null&&visuals.identity().equals(text(v,"observationIdentity")))
                .filter(v->{Map<String,Object> source=CheckResolution.map(v.get("source"));return bid.sha256().equals(text(source,"documentHash"))
                    &&source.get("documentVersion") instanceof Number&&((Number) (source.get("documentVersion"))).intValue()==bid.version();})));
            context.put("externalRecords",cn.bidcheck.compat.Java8Api.toList(store.maps("check_external_record",project,runId).stream()
                .filter(v->cp.id().equals(text(v,"checkpointId"))).map(v->CheckResolution.map(v.get("record")))
                .filter(v->"TRUSTED_CONNECTOR".equals(text(v,"provenanceStatus")))));
            context.put("stepObserver",(Consumer<Map<String,Object>>)step->{
                Map<String,Object> value=new LinkedHashMap<>(step);value.put("checkpointId",cp.id());value.put("runId",runId);value.put("savedAt",now());
                String id=stableId(parent+":"+text(step,"id")+":"+text(step,"status"));Store.PreparedRecord prepared=store.prepareRecord("check_atomic_step",id,project,parent,value);
                write.accept(()->store.savePrepared(prepared));
            });
            context.put("sharedFactPublisher",(Consumer<Map<String,Object>>)batch->{
                long started=System.nanoTime();
                // Split persistence, never truncate facts. A partial batch must not claim full extraction coverage.
                List<Map<String,Object>> batches=publicationBatches(batch);
                java.util.List<cn.bidcheck.verification.facts.FactLedgerService.PreparedBatch> prepared=cn.bidcheck.compat.Java8Api.toList(batches.stream().map(part->ledger.prepareBatch(itemManifest,runId,cp.id(),part)));
                long preparedAt=System.nanoTime();
                // A committed publication marker covers the ledger write and dependency
                // notification in one transaction. Only skip identical validated reuse.
                List<String> receiptIds=new ArrayList<>();
                for(int i=0;i<prepared.size();i++)receiptIds.add(stableId(parent+":"+text(batch,"operationIdentity")+":"+i));
                boolean mayReuse="REUSED".equals(text(batch,"origin"))&&maps(batch.get("fieldCoverage")).isEmpty();
                Map<String,Map<String,Object>> previous=mayReuse?store.mapsByIds("check_atomic_publication",project,receiptIds):Collections.emptyMap();
                int skipped=0;
                for(int part=0;part<prepared.size();part++){
                    cn.bidcheck.verification.facts.FactLedgerService.PreparedBatch value=prepared.get(part);int index=part;
                    Map<String,Object> marker=previous.get(receiptIds.get(part));
                    if(alreadyCommittedAdoption(marker,runId,cp.id(),value)){skipped++;continue;}
                    write.accept(()->{store.lock("project",project);Map<String,Object> receipt=ledger.publishPrepared(value);
                        OrdinaryRecoveryDependencies.publishChanges(store,project,runId,cp.id(),receipt);
                        store.save("check_atomic_publication",stableId(parent+":"+text(batch,"operationIdentity")+":"+index),project,parent,
                            fields("runId",runId,"checkpointId",cp.id(),"part",index,"parts",prepared.size(),"batchDigest",value.digest(),"receipt",receipt));});
                }
                if(skipped>0)LOG.info("bidcheck.fact_adoption_publication_reused runId={} checkpointId={} exactParts={} totalParts={}",runId,cp.id(),skipped,prepared.size());
                List<Map<String,Object>> coverage=maps(batch.get("fieldCoverage"));Map<String,Object> partition=CheckResolution.map(batch.get("observationPartition"));
                boolean complete="EXTRACTED".equals(batch.get("origin"))&&prepared.size()==1&&number(partition.get("parts"),1)==1&&!coverage.isEmpty()
                    &&coverage.stream().allMatch(row->Boolean.TRUE.equals(row.get("sourceScopeComplete"))&&cn.bidcheck.compat.Java8Api.setOf("EXTRACTED", "READ_NOT_FOUND").contains(text(row,"status")));
                if(complete&&batch.get("evidenceOperationDescriptor") instanceof Map<?,?>){Map<String,Object> descriptor=CheckResolution.map(batch.get("evidenceOperationDescriptor"));String key=text(batch,"evidenceOperationKey");
                    write.accept(()->evidenceOperations.publishCompleted(project,key,descriptor,fields("facts",batch.getOrDefault("facts",cn.bidcheck.compat.Java8Api.listOf()),"sources",batch.getOrDefault("sources",cn.bidcheck.compat.Java8Api.listOf()),"fieldCoverage",coverage)));
                }
                LOG.info("bidcheck.fact_publish_fenced runId={} checkpointId={} origin={} parts={} prepareMs={} transactionMs={} durationMs={}",runId,cp.id(),text(batch,"origin"),prepared.size(),(preparedAt-started)/1_000_000,(System.nanoTime()-preparedAt)/1_000_000,(System.nanoTime()-started)/1_000_000);
            });
            List<Map<String,Object>> preparations=maps(currentRun.get("retrievalPreparation"));
            Map<String,Object> index=preparations.isEmpty()?Collections.emptyMap():preparations.get(preparations.size()-1);
            if(factRevision!=null&&!factRevision.isEmpty()&&"INDEX_READY".equals(text(index,"mode"))&&Boolean.TRUE.equals(index.get("denseCoverageComplete"))
                    &&!text(index,"scopeFingerprint").isEmpty()){
                context.put("agentAdmissionKey",hash(store.encode(fields("run",runId,"checkpoint",cp,"engine",engineIdentity,
                    "bid",itemManifest.identity(),"tender",tender==null?"":tender.sha256(),"facts",factRevision,"index",index.get("scopeFingerprint"),
                    "projectFacts",context.get("ordinaryRecoveryObservedRevision"),"visual",context.get("visualObservations"),
                    "external",context.get("externalRecords"),"layout",layoutId,"layoutComplete",cached,"policy",inferenceNormalizationConfidence))));
            }
            return context;
        };
    }
    private Function<Map<String,Object>,List<Map<String,Object>>> supplementLayout(Document doc,Map<String,Map<String,Object>> audits){
        EvidenceLayoutService.NativePdfSource source=()->{if(artifactStorage==null)throw new java.io.IOException("PAGE_LAYOUT_STORAGE_UNAVAILABLE");return artifactStorage.materialize(doc.id(),doc.sha256());};
        java.util.function.Function<java.lang.Integer, java.util.Map<java.lang.String, java.lang.Object>> local=layouts.nativePdfLayouts(doc,source);java.util.function.Function<java.lang.Integer, java.util.Map<java.lang.String, java.lang.Object>> scanned=layouts.documentPageLayouts(doc,source,true);
        Map<String,LayoutEvidenceIndex> cache=new ConcurrentHashMap<>();
        return request->{
            int location=number(request.get("location"),-1);if(location<1||!doc.id().equals(text(request,"documentId")))return cn.bidcheck.compat.Java8Api.listOf();
            boolean allowScan=Boolean.TRUE.equals(request.get("allowScan"));String key=location+(allowScan?":SCAN":":LOCAL");
            LayoutEvidenceIndex ready=cache.get(location+":SCAN");if(ready!=null&&ready.apply(request).stream().anyMatch(r->"TABLE_CELL".equals(r.get("kind"))))return ready.apply(request);
            return cache.computeIfAbsent(key,k->{
                Map<String,Object> value="PAGE".equals(text(request,"locationType"))?(allowScan?scanned:local).apply(location):layouts.transcribedLayout(doc,cn.bidcheck.compat.Java8Api.setOf(location));
                Map<String,Object> audit=new LinkedHashMap<>();for(String field:cn.bidcheck.compat.Java8Api.listOf("pageLayoutAudit", "nativeTableAudit", "structureStatus", "diagnosticCode", "errorCode"))if(value.containsKey(field))audit.put(field,value.get(field));
                audits.put(doc.id()+":"+location,audit);LayoutEvidenceIndex index=new LayoutEvidenceIndex();index.add(doc.id(),value);return index;
            }).apply(request);
        };
    }
    static boolean validRequirementArtifactScope(Map<String,Object> value,String project,Document tender){
        if(tender==null||!"TENDER".equals(tender.role())||!project.equals(tender.projectId())||!project.equals(text(value,"projectId"))
            ||!tender.id().equals(text(value,"documentId"))||!tender.sha256().equals(text(value,"documentHash")))return false;
        if(!(value.get("documentVersion") instanceof Number)||((Number) (value.get("documentVersion"))).doubleValue()!=tender.version())return false;
        for(String key:cn.bidcheck.compat.Java8Api.listOf("artifactId", "compilerPolicyVersion", "modelIdentity", "inputFingerprint"))if(cn.bidcheck.compat.Java8Api.isBlank(text(value,key)))return false;
        return true;
    }
    static boolean alreadyCommittedAdoption(Map<String,Object> marker,String runId,String checkpointId,FactLedgerService.PreparedBatch value){
        if(marker==null||!runId.equals(text(marker,"runId"))||!checkpointId.equals(text(marker,"checkpointId"))
                ||!value.digest().equals(text(marker,"batchDigest"))||value.inputCount()==0||value.rejected()
                ||! (marker.get("receipt") instanceof Map))return false;
        Map<String,Object> receipt=CheckResolution.map(marker.get("receipt"));
        return receipt.get("accepted") instanceof Number&&((Number)receipt.get("accepted")).intValue()==value.inputCount()
            &&receipt.get("rejected") instanceof Collection&&((Collection<?>)receipt.get("rejected")).isEmpty();
    }
    private List<Map<String,Object>> publicationBatches(Map<String,Object> batch){
        List<Map<String,Object>> facts=maps(batch.get("facts")),sources=maps(batch.get("sources"));
        if(facts.size()<=10&&sources.size()<=32&&store.encode(batch).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=128*1024)return cn.bidcheck.compat.Java8Api.listOf(batch);
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
            Map<String,Object> value=new LinkedHashMap<>(batch);value.put("sources",cn.bidcheck.compat.Java8Api.listCopyOf(sourcePart));value.remove("extractionCoverage");
            value.put("fieldCoverage",restrictedFieldCoverage(batch,cn.bidcheck.compat.Java8Api.listOf(),sourcePart));
            value.put("operationIdentity",hash(text(batch,"operationIdentity")+store.encode(sourcePart)));out.add(value);
        }
        return out;
    }
    private Map<String,Object> publicationPart(Map<String,Object> batch,List<Map<String,Object>> facts,List<Map<String,Object>> sources){
        Map<String,Object> part=new LinkedHashMap<>(batch);part.put("facts",cn.bidcheck.compat.Java8Api.listCopyOf(facts));part.remove("extractionCoverage");
        Set<String> ids=new HashSet<>();for(Map<String,Object> fact:facts)if(fact.get("sourceIds") instanceof List<?>)((List<?>) (fact.get("sourceIds"))).forEach(id->ids.add(String.valueOf(id)));
        List<Map<String,Object>> selected=cn.bidcheck.compat.Java8Api.toList(sources.stream().filter(source->ids.contains(text(source,"id"))));part.put("sources",selected);
        part.put("fieldCoverage",restrictedFieldCoverage(batch,facts,selected));
        part.put("operationIdentity",hash(text(batch,"operationIdentity")+store.encode(facts)));part.put("coveragePolicy","PARTIAL_BATCH_NO_FULL_COVERAGE_CLAIM");return part;
    }
    private static List<Map<String,Object>> restrictedFieldCoverage(Map<String,Object> batch,List<Map<String,Object>> facts,List<Map<String,Object>> sources){
        Set<String> availableSources=new HashSet<>(),availableFacts=new HashSet<>();sources.forEach(s->availableSources.add(text(s,"id")));facts.forEach(f->availableFacts.add(text(f,"id")));
        List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> raw:maps(batch.get("fieldCoverage"))){
            List<String> refs=raw.get("sourceIds") instanceof List<?>?cn.bidcheck.compat.Java8Api.toList(((List<?>) (raw.get("sourceIds"))).stream().map(String::valueOf)):cn.bidcheck.compat.Java8Api.listOf();
            List<String> ids=raw.get("factIds") instanceof List<?>?cn.bidcheck.compat.Java8Api.toList(((List<?>) (raw.get("factIds"))).stream().map(String::valueOf)):cn.bidcheck.compat.Java8Api.listOf();
            List<String> scoped=cn.bidcheck.compat.Java8Api.toList(refs.stream().filter(availableSources::contains)),kept=cn.bidcheck.compat.Java8Api.toList(ids.stream().filter(availableFacts::contains));
            if(scoped.isEmpty()||("EXTRACTED".equals(text(raw,"status"))&&kept.isEmpty()))continue;
            Map<String,Object> value=new LinkedHashMap<>(raw);value.put("sourceIds",scoped);value.put("factIds",kept);
            if(!availableSources.containsAll(refs)||!availableFacts.containsAll(ids))value.put("sourceScopeComplete",false);
            result.add(value);
        }return result;
    }
    /** Only explicit, unique event dates in the frozen tender. Conflicting notices remain unresolved. */
    static Map<String,Object> referenceDates(Document tender){
        if(tender==null)return cn.bidcheck.compat.Java8Api.mapOf();Map<String,Object> result=new LinkedHashMap<>();
        Map<String,Pattern> labels=cn.bidcheck.compat.Java8Api.mapOf("BID_DEADLINE", Pattern.compile("(?:投标(?:文件(?:递交的?)?)?截止(?:时间|日期)?|文件递交截止(?:时间|日期)?|递交截止(?:时间|日期)?)"), "OPENING_DATE", Pattern.compile("开标(?:时间|日期)"));
        Pattern date=Pattern.compile("(20\\d{2})\\s*[年/.-]\\s*(\\d{1,2})\\s*[月/.-]\\s*(\\d{1,2})\\s*日?");
        for(java.util.Map.Entry<java.lang.String, java.util.regex.Pattern> event:labels.entrySet()){
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
    private Map<String,Object> requirementDependencySnapshot(String project,String runId,Document bid,Document tender,String engineIdentity){
        Map<String,Object> currentRun=store.getMap("run",runId);
        boolean frozen=project.equals(text(currentRun,"projectId"))&&bid.id().equals(text(currentRun,"documentId"))
                &&tender!=null&&tender.id().equals(text(currentRun,"tenderDocumentId"))
                &&bid.version()==number(currentRun.get("documentVersion"),-1)&&engineIdentity.equals(text(currentRun,"atomicCheckVersion"));
        List<Checkpoint> snapshot=store.list("snapshot",project,runId,Checkpoint.class);
        Map<String,Checkpoint> frozenCheckpoints=new LinkedHashMap<>();snapshot.forEach(c->frozenCheckpoints.put(c.id(),c));
        List<Map<String,Object>> identities=tender==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf(fields("id",bid.id(),"version",bid.version(),"sha256",bid.sha256()), fields("id",tender.id(),"version",tender.version(),"sha256",tender.sha256()));
        List<Finding> findings=frozen?cn.bidcheck.compat.Java8Api.toList(store.list("finding",project,runId,Finding.class).stream()
                .filter(f->runId.equals(f.runId())&&f.checkpoint().equals(frozenCheckpoints.get(f.checkpoint().id())))
                .filter(f->new HashSet<>(maps(CheckResolution.atomic(f).get("documentIdentities"))).equals(new HashSet<>(identities)))):cn.bidcheck.compat.Java8Api.listOf();
        String revision=hash(store.encode(cn.bidcheck.compat.Java8Api.toList(findings.stream().map(f->fields("checkpointId",f.checkpoint().id(),"evaluation",f.evaluation(),"gaps",CheckResolution.atomic(f).getOrDefault("gaps",cn.bidcheck.compat.Java8Api.listOf()),"complete",CheckResolution.atomic(f).get("complete"))))));
        return fields("sourceRunId",runId,"projectId",project,"documentIdentities",identities,"engineIdentity",engineIdentity,"verified",frozen,"findings",findings,"checkpoints",snapshot,"revision",revision);
    }
    private static List<Map<String,Object>> maps(Object raw){if(!(raw instanceof List<?>))return cn.bidcheck.compat.Java8Api.listOf();return cn.bidcheck.compat.Java8Api.toList(((List<?>) (raw)).stream().filter(Map.class::isInstance).map(v->stringMap((Map<?,?>)v)));}
    private static int number(Object raw,int fallback){
        if(raw instanceof Number)return ((Number) (raw)).intValue();
        try{return raw==null?fallback:Integer.parseInt(String.valueOf(raw));}catch(NumberFormatException ignored){return fallback;}
    }
    private static Map<String,Object> stringMap(Map<?,?> source){Map<String,Object> value=new LinkedHashMap<>();source.forEach((k,v)->value.put(String.valueOf(k),v));return value;}
}
