package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.BidService;
import cn.bidcheck.verification.facts.FactLedgerService;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.bidcheck.consistency.ConsistencyRunSupport.*;

/** Bounded durable batches, followed by an atomically published immutable report. */
public final class ConsistencyPublication {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ConsistencyPublication.class);
    public static final String VERSION="consistency-publication-4-compact-records";
    public static final int TRANSACTION_TIMEOUT_SECONDS=10;
    public static final int FINAL_TRANSACTION_TIMEOUT_SECONDS=30;
    public static final String TRANSACTION_POLICY_VERSION="bounded-publication-transactions-1";
    public static final String DIAGNOSTIC_KIND="consistency_publication_diag";
    public static final int MAX_TRANSACTION_ATTEMPTS=3;
    public static final int MAX_BATCH_FACTS=10,MAX_BATCH_SOURCES=32,TARGET_BATCH_BYTES=128*1024;
    public static final int REUSE_MAX_BATCH_FACTS=40,REUSE_MAX_BATCH_SOURCES=128;
    public static final String REUSE_PLAN_POLICY_VERSION="positive-immutable-preload-1";
    private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL_JSON=new com.fasterxml.jackson.databind.ObjectMapper()
        .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final Store store;private final FactLedgerService ledger;private final TransactionTemplate tx,finalTx;
    private static final class PublicationPlan {
        final List<FactLedgerService.PreparedBatch> batches;final int maxFacts,maxSources;final String mode;
        PublicationPlan(List<FactLedgerService.PreparedBatch> batches,int maxFacts,int maxSources,String mode){this.batches=batches;this.maxFacts=maxFacts;this.maxSources=maxSources;this.mode=mode;}
    }
    public ConsistencyPublication(Store store,FactLedgerService ledger,TransactionTemplate template) {
        this.store=store;this.ledger=ledger;this.tx=new TransactionTemplate(Objects.requireNonNull(template.getTransactionManager()));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        finalTx=new TransactionTemplate(template.getTransactionManager());finalTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        finalTx.setTimeout(FINAL_TRANSACTION_TIMEOUT_SECONDS);
    }
    public void publish(TaskRow task,String runId,Document document,String engine,Map<String,Object> report,
                        List<Finding> findings,Consumer<String> progress) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("CONSISTENCY_PUBLICATION_REQUIRES_NO_TRANSACTION");
        checkInterrupted();List<Document> documents=cn.bidcheck.compat.Java8Api.listOf(document);
        cn.bidcheck.verification.facts.FactLedgerService.PreparedManifest manifest=ledger.prepareManifest(task.projectId,engine,documents);
        PublicationPlan reusePlan=preparePlan(manifest,runId,documents,report,REUSE_MAX_BATCH_FACTS,REUSE_MAX_BATCH_SOURCES,"IMMUTABLE_REUSE");validatePrepared(reusePlan);
        FactLedgerService.PublicationReuse preload=ledger.preloadPublicationReuse(manifest,reusePlan.batches);
        List<Stored> priorReceipts=store.mapper().list("consistency_publication_batch",task.projectId,runId);
        PublicationPlan coldPlan=null,plan;String selectionReason;
        if(priorReceipts.isEmpty()){
            if(preload.allReusable()){plan=reusePlan;selectionReason="ALL_IMMUTABLE_OCCURRENCES_REUSED";}
            else{coldPlan=preparePlan(manifest,runId,documents,report,MAX_BATCH_FACTS,MAX_BATCH_SOURCES,"COLD_OR_PARTIAL");validatePrepared(coldPlan);plan=coldPlan;selectionReason="COLD_OR_PARTIAL_FALLBACK";}
        }else{
            coldPlan=preparePlan(manifest,runId,documents,report,MAX_BATCH_FACTS,MAX_BATCH_SOURCES,"COLD_OR_PARTIAL");validatePrepared(coldPlan);
            String reuseDigest=planDigest(runId,engine,manifest,reusePlan),coldDigest=planDigest(runId,engine,manifest,coldPlan);
            boolean resumeReuse=receiptsMatch(priorReceipts,task,runId,reusePlan,reuseDigest),resumeCold=receiptsMatch(priorReceipts,task,runId,coldPlan,coldDigest);
            if(!resumeReuse&&!resumeCold)throw new IllegalStateException("CONSISTENCY_BATCH_PLAN_MISMATCH");
            if(resumeReuse&&!resumeCold){plan=reusePlan;selectionReason="RESUME_REUSE_PLAN";}
            else if(resumeCold&&!resumeReuse){plan=coldPlan;selectionReason="RESUME_COLD_PLAN";}
            else if(preload.allReusable()){plan=reusePlan;selectionReason="RESUME_EQUIVALENT_REUSE_PLAN";}
            else{plan=coldPlan;selectionReason="RESUME_EQUIVALENT_COLD_PLAN";}
        }
        java.util.List<cn.bidcheck.verification.facts.FactLedgerService.PreparedBatch> batches=plan.batches;
        String planDigest=planDigest(runId,engine,manifest,plan);
        LOG.info("bidcheck.consistency_publication_plan runId={} mode={} reason={} batches={} facts={} maxBatchFacts={} maxBatchSources={} reuseRequested={} reuseConfirmed={} preloadMs={}",runId,plan.mode,selectionReason,batches.size(),batches.stream().mapToInt(FactLedgerService.PreparedBatch::inputCount).sum(),plan.maxFacts,plan.maxSources,preload.requested(),preload.reusable(),preload.durationMs());
        List<Map<String,Object>> receipts=new ArrayList<>(),timings=new ArrayList<>();int total=batches.stream().mapToInt(FactLedgerService.PreparedBatch::inputCount).sum(),saved=0;
        try {
        for(int index=0;index<batches.size();index++) {
            checkInterrupted();cn.bidcheck.verification.facts.FactLedgerService.PreparedBatch batch=batches.get(index);int unit=index,completed=saved+batch.inputCount();
            Map<String,Object> receipt=publishBatch(unit,batch.inputCount(),timings,()->{
                fence(task);store.lock("project",task.projectId);validateRun(task,runId,document,engine);
                String receiptId=receiptId(runId,unit);Stored prior=store.mapper().get(receiptId);Map<String,Object> value;
                if(prior!=null) {
                    if(!"consistency_publication_batch".equals(prior.kind)||!task.projectId.equals(prior.projectId)||!runId.equals(prior.parentId))throw new IllegalStateException("CONSISTENCY_BATCH_SCOPE_MISMATCH");
                    value=store.map(prior.body);validateReceipt(value,planDigest,batch.digest(),batch.inputCount());
                } else {
                    Map<String,Object> result=ledger.publishPrepared(batch);
                    if(!maps(result.get("rejected")).isEmpty()||!Objects.equals(result.get("accepted"),batch.inputCount()))throw new IllegalStateException("一致性事实未通过原文验证，不能发布报告");
                    value=values("id",receiptId,"policyVersion",VERSION,"planDigest",planDigest,"batchDigest",batch.digest(),"inputCount",batch.inputCount(),"result",result);
                    store.save("consistency_publication_batch",receiptId,task.projectId,runId,value);
                }
                fence(task);progress.accept("全文事实已保存 "+completed+" / "+total+" 条（批次 "+(unit+1)+" / "+batches.size()+"）");
                return value;
            });
            receipts.add(Objects.requireNonNull(receipt));saved=completed;
        }
        tx.executeWithoutResult(status->{fence(task);progress.accept("全文事实已保存，正在核对完整性（"+total+" 条）");});
        // This phase may hydrate thousands of immutable records; it must never hold the task row.
        checkInterrupted();cn.bidcheck.verification.facts.FactLedgerService.PreparedSnapshot prepared=ledger.prepareSnapshot(manifest,runId,cn.bidcheck.compat.Java8Api.toList(receipts.stream().map(r->map(r.get("result")))));
        tx.executeWithoutResult(status->{fence(task);progress.accept("事实完整性已核对，正在准备检查报告");});
        List<String> receiptDigests=cn.bidcheck.compat.Java8Api.toList(receipts.stream().map(this::digest));
        Map<String,Object> publishedReport=new LinkedHashMap<>(report);
        attachPublication(publishedReport,runId,documents,engine,prepared.metadata(),true);
        publishedReport.put("publication",values("policyVersion",VERSION,"planDigest",planDigest,"batchCount",batches.size(),"factsSaved",total,"complete",true,
            "maxBatchFacts",plan.maxFacts,"maxBatchSources",plan.maxSources,"targetBatchBytes",TARGET_BATCH_BYTES,"oversizedSingleFactPolicy","ISOLATE_AND_VALIDATE",
            "batchPlanMode",plan.mode,"batchPlanSelectionReason",selectionReason,"reusePlanPolicyVersion",REUSE_PLAN_POLICY_VERSION,
            "reusePreload",values("requested",preload.requested(),"reusable",preload.reusable(),"loaded",preload.loaded(),"cacheHits",preload.cacheHits(),"invalid",preload.invalid(),"allReusable",preload.allReusable(),"durationMs",preload.durationMs()),
            "batchTransactionTimeoutSeconds",TRANSACTION_TIMEOUT_SECONDS,"finalTransactionTimeoutSeconds",FINAL_TRANSACTION_TIMEOUT_SECONDS,
            "transactionPolicyVersion",TRANSACTION_POLICY_VERSION,"maxTransactionAttempts",MAX_TRANSACTION_ATTEMPTS,"batchTransactionTimings",timings));
        cn.bidcheck.persistence.Store.PreparedRecord preparedReport=store.prepareRecord("consistency_report",reportId(runId),task.projectId,runId,publishedReport);
        java.util.List<cn.bidcheck.persistence.Store.PreparedRecord> preparedFindings=cn.bidcheck.compat.Java8Api.toList(findings.stream().map(finding->store.prepareRecord("finding",finding.id(),task.projectId,runId,finding)));
        checkInterrupted();
        finalTx.executeWithoutResult(status->{
            fence(task);store.lock("project",task.projectId);validateRun(task,runId,document,engine);
            Map<String,Stored> currentReceipts=new HashMap<>();
            for(Stored row:store.mapper().list("consistency_publication_batch",task.projectId,runId)) {
                if(!"consistency_publication_batch".equals(row.kind)||!task.projectId.equals(row.projectId)||!runId.equals(row.parentId)
                        ||currentReceipts.putIfAbsent(row.id,row)!=null)throw new IllegalStateException("CONSISTENCY_BATCH_SCOPE_MISMATCH");
            }
            Set<String> expectedReceiptIds=new HashSet<>();
            for(int index=0;index<batches.size();index++)expectedReceiptIds.add(receiptId(runId,index));
            if(!currentReceipts.keySet().equals(expectedReceiptIds))throw new IllegalStateException("CONSISTENCY_BATCH_SET_CHANGED");
            for(int index=0;index<batches.size();index++) {
                Stored row=currentReceipts.get(receiptId(runId,index));
                Map<String,Object> current=store.map(row.body);validateReceipt(current,planDigest,batches.get(index).digest(),batches.get(index).inputCount());
                if(!digest(current).equals(receiptDigests.get(index)))throw new IllegalStateException("CONSISTENCY_BATCH_CHANGED");
            }
            ledger.freezePreparedSnapshot(prepared);
            for(cn.bidcheck.persistence.Store.PreparedRecord finding:preparedFindings)store.savePrepared(finding);
            store.savePrepared(preparedReport);
            fence(task);progress.accept("全文一致性扫描已保存");
        });
        report.putAll(publishedReport);
        } catch(RuntimeException failure) {
            // This runs only after TransactionTemplate has completed rollback. A diagnostic cannot publish
            // a partial business report or hide the original failure if ownership/storage is unavailable.
            if(!timings.isEmpty())try {
                tx.executeWithoutResult(status->{
                    fence(task);store.lock("project",task.projectId);validateRun(task,runId,document,engine);
                    store.save(DIAGNOSTIC_KIND,id("consistency-publication-diagnostics:"+runId),task.projectId,runId,
                        values("transactionPolicyVersion",TRANSACTION_POLICY_VERSION,"publicationPolicyVersion",VERSION,"runId",runId,
                            "documentId",document.id(),"documentHash",document.sha256(),"planDigest",planDigest,
                            "status","FAILED","plannedBatchCount",batches.size(),"committedBatchCount",receipts.size(),
                            "totalFacts",total,"committedInputFacts",receipts.stream().mapToInt(r->((Number)r.get("inputCount")).intValue()).sum(),
                            "maxTransactionAttempts",MAX_TRANSACTION_ATTEMPTS,"batchTransactionTimings",timings,
                            "failureClass",failure.getClass().getName()));
                    fence(task);
                });
            } catch(RuntimeException diagnosticFailure) {if(diagnosticFailure!=failure)failure.addSuppressed(diagnosticFailure);}
            throw failure;
        }
    }
    private PublicationPlan preparePlan(FactLedgerService.PreparedManifest manifest,String runId,List<Document> documents,Map<String,Object> report,int maxFacts,int maxSources,String mode){
        List<FactLedgerService.PreparedBatch> batches=cn.bidcheck.compat.Java8Api.toList(publicationBatches(store,runId,documents,report,maxFacts,maxSources,TARGET_BATCH_BYTES).stream()
            .map(batch->ledger.prepareBatch(manifest,runId,"full-text-consistency",batch)));
        return new PublicationPlan(batches,maxFacts,maxSources,mode);
    }
    private static void validatePrepared(PublicationPlan plan){
        if(plan.batches.stream().anyMatch(batch->batch.rejected()||batch.acceptedCount()!=batch.inputCount()))throw new IllegalStateException("一致性事实未通过原文验证，不能发布报告");
    }
    private String planDigest(String runId,String engine,FactLedgerService.PreparedManifest manifest,PublicationPlan plan){
        return digest(values("version",VERSION,"runId",runId,"engine",engine,"manifest",manifest.identity(),
            "batches",cn.bidcheck.compat.Java8Api.toList(plan.batches.stream().map(FactLedgerService.PreparedBatch::digest))));
    }
    /** Existing receipts are a durable plan selector after a partial run; new reuse observations
     * must never change the batch plan underneath already committed usage/origin rows. */
    private boolean receiptsMatch(List<Stored> rows,TaskRow task,String runId,PublicationPlan plan,String planDigest){
        if(rows.isEmpty()||rows.size()>plan.batches.size())return false;Map<String,Integer> indexes=new HashMap<>();
        for(int index=0;index<plan.batches.size();index++)indexes.put(receiptId(runId,index),index);
        for(Stored row:rows){Integer index=indexes.get(row.id);if(index==null||!"consistency_publication_batch".equals(row.kind)||!task.projectId.equals(row.projectId)||!runId.equals(row.parentId))return false;
            Map<String,Object> receipt=store.map(row.body);FactLedgerService.PreparedBatch batch=plan.batches.get(index);if(!receiptMatches(receipt,planDigest,batch.digest(),batch.inputCount()))return false;
        }return true;
    }
    /** Each retry enters a new transaction after the previous execute() has rolled back/released locks. */
    private Map<String,Object> publishBatch(int index,int inputCount,List<Map<String,Object>> timings,Supplier<Map<String,Object>> action) {
        List<Map<String,Object>> attempts=new ArrayList<>();
        Map<String,Object> timing=values("batch",index+1,"inputCount",inputCount,"attempts",attempts,"retryCount",0,"committed",false,"transactionElapsedMillis",0L);
        timings.add(timing);long totalNanos=0;
        for(int attempt=1;attempt<=MAX_TRANSACTION_ATTEMPTS;attempt++) {
            checkInterrupted();long started=System.nanoTime();
            try {
                Map<String,Object> receipt=Objects.requireNonNull(tx.execute(status->action.get()));
                long elapsed=System.nanoTime()-started;totalNanos+=elapsed;
                attempts.add(values("attempt",attempt,"outcome","COMMITTED","elapsedMillis",elapsed/1_000_000L));
                timing.put("transactionElapsedMillis",totalNanos/1_000_000L);timing.put("retryCount",attempt-1);timing.put("committed",true);
                return receipt;
            } catch(RuntimeException failure) {
                long elapsed=System.nanoTime()-started;totalNanos+=elapsed;
                boolean retry=attempt<MAX_TRANSACTION_ATTEMPTS&&retryable(failure)&&!Thread.currentThread().isInterrupted();
                long delay=retry?attempt*100L:0;
                attempts.add(values("attempt",attempt,"outcome","ROLLED_BACK_OR_UNCONFIRMED","elapsedMillis",elapsed/1_000_000L,
                    "failureClass",failure.getClass().getName(),"transientCauseClass",transientCause(failure),"retryScheduled",retry,"retryDelayMillis",delay));
                timing.put("transactionElapsedMillis",totalNanos/1_000_000L);timing.put("retryCount",attempt-1);
                if(!retry)throw failure;
                checkInterrupted();
                try {Thread.sleep(delay);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("一致性发布重试已中断");}
            }
        }
        throw new IllegalStateException("CONSISTENCY_BATCH_ATTEMPTS_EXHAUSTED");
    }
    private static boolean retryable(Throwable failure) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());boolean transientFailure=false;
        for(Throwable cause=failure;cause!=null&&seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof CancellationException||cause instanceof InterruptedException||cause instanceof TaskLeaseLostException
                ||cause instanceof IllegalArgumentException||cause instanceof IllegalStateException)return false;
            if(cause instanceof TransactionTimedOutException||cause instanceof QueryTimeoutException||cause instanceof CannotAcquireLockException)transientFailure=true;
        }
        return transientFailure;
    }
    private static String transientCause(Throwable failure) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable cause=failure;cause!=null&&seen.add(cause);cause=cause.getCause())
            if(cause instanceof TransactionTimedOutException||cause instanceof QueryTimeoutException||cause instanceof CannotAcquireLockException)return cause.getClass().getName();
        return "";
    }
    private static String receiptId(String runId,int unit){return id("consistency-publication:"+runId+":"+unit);}
    private static void validateReceipt(Map<String,Object> receipt,String plan,String batch,int count) {
        if(!receiptMatches(receipt,plan,batch,count))throw new IllegalStateException("CONSISTENCY_BATCH_PLAN_MISMATCH");
    }
    private static boolean receiptMatches(Map<String,Object> receipt,String plan,String batch,int count) {
        Map<String,Object> result=map(receipt.get("result"));
        return VERSION.equals(receipt.get("policyVersion"))&&plan.equals(receipt.get("planDigest"))&&batch.equals(receipt.get("batchDigest"))
            &&receipt.get("inputCount") instanceof Number&&((Number) (receipt.get("inputCount"))).intValue()==count
            &&result.get("accepted") instanceof Number&&((Number) (result.get("accepted"))).intValue()==count&&maps(result.get("rejected")).isEmpty();
    }
    private void fence(TaskRow task) {
        checkInterrupted();TaskRow current=store.mapper().lockTask(task.id);
        if(current==null||!"RUNNING".equals(current.status)||!task.projectId.equals(current.projectId)||task.attempt!=current.attempt
            ||!Objects.equals(task.leaseOwner,current.leaseOwner)||(task.leaseOwner!=null&&(current.leaseUntil==null||current.leaseUntil<System.currentTimeMillis())))
            throw new TaskLeaseLostException(task.id);
    }
    private void validateRun(TaskRow task,String runId,Document document,String engine) {
        Map<String,Object> run=store.getMap("run",runId);
        if(!task.id.equals(run.get("taskId"))||!"RUNNING".equals(run.get("status"))||!task.projectId.equals(run.get("projectId"))
            ||!document.id().equals(run.get("documentId"))||!(run.get("documentVersion") instanceof Number)||((Number) (run.get("documentVersion"))).intValue()!=document.version()
            ||!engine.equals(run.get("consistencyIdentity")))throw new IllegalStateException("CONSISTENCY_PUBLICATION_RUN_CHANGED");
    }
    private String digest(Object value) {
        try{return BidService.hash(CANONICAL_JSON.writeValueAsString(CANONICAL_JSON.readValue(store.encode(value),Object.class)));}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalArgumentException("CONSISTENCY_PUBLICATION_INVALID",failure);}
    }
    private static void checkInterrupted(){if(Thread.currentThread().isInterrupted())throw new CancellationException("一致性发布已中断");}
}
