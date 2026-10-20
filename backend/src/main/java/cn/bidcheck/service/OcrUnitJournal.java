package cn.bidcheck.service;

import cn.bidcheck.document.OcrUnitObserver;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import java.io.IOException;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable parsing work, scoped to an immutable document revision; never a business verdict. */
final class OcrUnitJournal implements OcrUnitObserver {
    static final String VERSION="ocr-unit-journal-1";
    private static final Set<String> STATUSES=Set.of("RUNNING","SUCCEEDED","PARTIAL_SUCCEEDED","FAILED","DEFERRED");
    private final Store store;
    private final TransactionTemplate tx;
    private final Runnable fence;
    private final String project,document,sourceHash,planHash,identity,scope,taskId,baseDocument;
    private final int version;
    private final Map<String,Map<String,Object>> states=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> batches=new LinkedHashMap<>();

    OcrUnitJournal(Store store,TransactionTemplate tx,Runnable fence,String project,String document,int version,
                   String sourceHash,String planHash,String identity,String taskId) {
        this(store,tx,fence,project,document,version,sourceHash,planHash,identity,taskId,"");
    }
    OcrUnitJournal(Store store,TransactionTemplate tx,Runnable fence,String project,String document,int version,
                   String sourceHash,String planHash,String identity,String taskId,String baseDocument) {
        this.store=store;this.tx=tx;this.fence=fence;this.project=project;this.document=document;this.version=version;
        this.sourceHash=sourceHash;this.planHash=planHash;this.identity=identity;this.taskId=taskId;this.baseDocument=Objects.requireNonNullElse(baseDocument,"");
        if(project==null||project.isBlank()||document==null||document.isBlank()||version<1||sourceHash==null||!sourceHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("识别工作记录缺少文档版本或原件指纹。");
        this.scope=BidService.hash(VERSION+"|"+project+"|"+document+"|"+version+"|"+sourceHash+"|"+planHash+"|"+identity);
        for(Map<String,Object> row:store.maps("ocr_unit_status",project,document))if(scope.equals(row.get("scope")))states.put(location(row),row);
        for(Map<String,Object> row:store.maps("ocr_batch_status",project,document))if(scope.equals(row.get("scope")))batches.put(BidService.text(row,"batchId"),row);
    }

    @Override public Optional<Result> restore(String type,int position,int index,String imageHash) throws IOException {
        validateLocation(type,position,index);
        String location=key(type,position,index);
        Optional<Result> result=readResult(resultId(location),document,scope,imageHash);
        if(result.isEmpty()&&!baseDocument.isBlank()) {
            Stored summary=store.mapper().get("ocr-progress:"+baseDocument);
            if(summary!=null&&"ocr_progress".equals(summary.kind)&&project.equals(summary.projectId)&&baseDocument.equals(summary.parentId)) {
                String priorScope=BidService.text(store.map(summary.body),"scope");
                result=readResult(unitId("result",priorScope,location),baseDocument,priorScope,imageHash);
            }
        }
        // A database/lease failure is not a corrupt-cache miss and must stop the parser.
        if(result.isPresent())tx.executeWithoutResult(s->fence.run());
        return result;
    }
    private Optional<Result> readResult(String id,String expectedDocument,String expectedScope,String imageHash) {
        Stored saved=store.mapper().get(id);
        if(saved==null||!"ocr_unit_result".equals(saved.kind)||!project.equals(saved.projectId)||!expectedDocument.equals(saved.parentId))return Optional.empty();
        try {
            Map<String,Object> row=store.map(saved.body);
            if(!expectedScope.equals(row.get("scope"))||!sourceHash.equals(row.get("sourceHash"))||!identity.equals(row.get("parserIdentity"))
                ||!project.equals(row.get("projectId"))||!expectedDocument.equals(row.get("documentId"))
                ||!imageHash.equals(row.get("imageHash"))||!"SUCCEEDED".equals(row.get("status")))return Optional.empty();
            Result result=store.decode(store.encode(row.get("result")),Result.class);
            if(!BidService.hash(store.encode(result)).equals(row.get("payloadHash"))||!validResult(result))return Optional.empty();
            return Optional.of(result);
        } catch(RuntimeException invalid) {
            return Optional.empty();
        }
    }

    @Override public void onState(Event event) throws IOException {
        validateLocation(event.locationType(),event.position(),event.imageIndex());
        if(!STATUSES.contains(event.status()))throw new IOException("识别工作状态无效。");
        String location=key(event.locationType(),event.position(),event.imageIndex());
        Map<String,Object> prior=states.get(location);
        int attempts=prior==null?0:((Number)prior.getOrDefault("attempts",0)).intValue();
        if("RUNNING".equals(event.status()))attempts++;
        Map<String,Object> state=BidService.fields("scope",scope,"journalVersion",VERSION,"projectId",project,"documentId",document,
            "documentVersion",version,"sourceHash",sourceHash,"planHash",planHash,"parserIdentity",identity,"taskId",taskId,
            "locationType",event.locationType(),"position",event.position(),"imageIndex",event.imageIndex(),"imageHash",event.imageHash(),
            "status",event.status(),"attempts",attempts,"elapsedMillis",Math.max(0,event.elapsedMillis()),"cached",event.cached(),
            "width",event.width(),"height",event.height(),"imageBytes",event.imageBytes(),"updatedAt",BidService.now(),
            "errorCode",switch(event.status()){case "FAILED"->"OCR_UNIT_FAILED";case "PARTIAL_SUCCEEDED"->"OCR_UNIT_PARTIAL";case "DEFERRED"->"PLAN_DEFERRED";default->"";});
        Result result=new Result(event.text(),event.warning(),event.layout());
        if("SUCCEEDED".equals(event.status())&&(!validResult(result)||event.imageHash()==null||!event.imageHash().matches("[a-f0-9]{64}")
            ||event.width()<1||event.height()<1))throw new IOException("识别工作结果未通过持久化校验。");
        Map<String,Map<String,Object>> next=new LinkedHashMap<>(states);next.put(location,state);
        Map<String,Object> summary=summary(next);
        tx.executeWithoutResult(s->{
            fence.run();
            store.save("ocr_unit_status",statusId(location),project,document,state);
            if("SUCCEEDED".equals(event.status())||"PARTIAL_SUCCEEDED".equals(event.status())) {
                Map<String,Object> payload=new LinkedHashMap<>(state);payload.put("result",result);payload.put("payloadHash",BidService.hash(store.encode(result)));
                store.save("ocr_unit_result",resultId(location),project,document,payload);
            }
            store.save("ocr_progress","ocr-progress:"+document,project,document,summary);
            store.save("ocr_task_progress","ocr-task-progress:"+taskId,project,taskId,summary);
        });
        states.clear();states.putAll(next);
    }

    private Map<String,Object> summary(Map<String,Map<String,Object>> values) {
        Map<String,Long> counts=new LinkedHashMap<>();for(String status:List.of("RUNNING","SUCCEEDED","PARTIAL_SUCCEEDED","FAILED","DEFERRED"))counts.put(status,0L);
        long elapsed=0,cached=0;for(Map<String,Object> row:values.values()) {
            counts.computeIfPresent(BidService.text(row,"status"),(k,v)->v+1);
            elapsed+=((Number)row.getOrDefault("elapsedMillis",0)).longValue();if(Boolean.TRUE.equals(row.get("cached")))cached++;
        }
        Map<String,Long> batchCounts=new LinkedHashMap<>();
        batches.values().forEach(b->batchCounts.merge(BidService.text(b,"status"),1L,Long::sum));
        return BidService.fields("documentId",document,"documentVersion",version,"scope",scope,"unitCount",values.size(),"counts",counts,
            "cachedUnitCount",cached,"totalUnitElapsedMillis",elapsed,"batchCount",batches.size(),"batchCounts",batchCounts,
            "updatedAt",BidService.now(),"boundary","逐位置处理状态；识别成功不等于业务检查通过");
    }

    @Override public void onBatch(cn.bidcheck.ocr.OcrBatch.Event event)throws IOException {
        if(event.id()==null||!event.id().matches("[A-Za-z0-9_:.-]{1,100}")||event.inputIds().isEmpty()||event.inputIds().size()>16
                ||!Set.of("CREATED","BATCHING","RUNNING","SPLITTING","SPLIT_COMPLETED","RECOVERED","ITEM_REJECTED","SUCCEEDED","PARTIAL_FAILED","FAILED","CANCELLED","CACHED").contains(event.status())
                ||event.inputIds().stream().anyMatch(id->id==null||!id.matches("[A-Za-z0-9_:.-]{1,100}"))
                ||event.inputHash()==null||!event.inputHash().matches("[a-f0-9]{64}"))throw new IOException("OCR 批次工作记录无效。");
        Map<String,Object> row=BidService.fields("batchId",event.id(),"parentBatchId",event.parentId(),"scope",scope,"projectId",project,
            "documentId",document,"documentVersion",version,"sourceHash",sourceHash,"taskId",taskId,"traceId",taskId,
            "inputIds",event.inputIds(),"model",safeMetadata(event.model()),"policy",safeMetadata(event.policy()),"inputHash",event.inputHash(),
            "pixels",Math.max(0,event.pixels()),"estimatedTokens",Math.max(0,event.estimatedTokens()),"status",event.status(),
            "elapsedMillis",Math.max(0,event.elapsedMillis()),"errorCode",safeMetadata(event.errorCode()),"updatedAt",BidService.now());
        boolean diagnostic="ITEM_REJECTED".equals(event.status());
        Map<String,Object> old=diagnostic?batches.get(event.id()):batches.put(event.id(),row);
        row.put("startedAt",old==null?row.get("updatedAt"):old.getOrDefault("startedAt",old.get("updatedAt")));
        try {
            Map<String,Object> summary=summary(states);
            tx.executeWithoutResult(s->{fence.run();
                if(!diagnostic)store.save("ocr_batch_status",BidService.hash("ocr-batch:"+scope+":"+event.id()),project,document,row);
                store.save("ocr_batch_event",BidService.hash("ocr-batch-event:"+scope+":"+event.id()+":"+event.status()+":"+event.inputIds()+":"+event.errorCode()+":"+event.elapsedMillis()),project,document,row);
                store.save("ocr_progress","ocr-progress:"+document,project,document,summary);
                store.save("ocr_task_progress","ocr-task-progress:"+taskId,project,taskId,summary);});
            cn.bidcheck.ocr.OcrBatchMetrics.record(event);
        }catch(RuntimeException failure){if(!diagnostic){if(old==null)batches.remove(event.id());else batches.put(event.id(),old);}throw failure;}
    }
    private static String safeMetadata(String value){return value!=null&&value.matches("[A-Za-z0-9_:./-]{0,200}")?value:"";}
    private static boolean validResult(Result result) {
        if(result==null||result.text()==null||result.text().isBlank()||result.text().length()>50_000||result.text().indexOf('\0')>=0
            ||result.warning()==null||result.warning().contains("[OCR未完成]"))return false;
        try { if(result.layout()!=null)result.layout().validated(result.text());return true; }
        catch(RuntimeException invalid){return false;}
    }
    // qc_record IDs are limited to 64 characters; include the namespace in the full digest.
    private static String unitId(String kind,String scope,String location){return BidService.hash("ocr-unit-"+kind+":"+scope+":"+location);}
    private String resultId(String location){return unitId("result",scope,location);}
    private String statusId(String location){return unitId("status",scope,location);}
    private static String location(Map<String,Object> row){return key(BidService.text(row,"locationType"),((Number)row.get("position")).intValue(),((Number)row.get("imageIndex")).intValue());}
    private static String key(String type,int position,int index){return type+":"+position+":"+index;}
    private static void validateLocation(String type,int position,int index)throws IOException {
        if(!Set.of("PAGE","PARAGRAPH","BLOCK","IMAGE").contains(type)||position<1||position>20_000||index<1||index>1000)
            throw new IOException("识别工作来源位置无效。");
    }
}
