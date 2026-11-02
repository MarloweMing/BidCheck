package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.persistence.*;
import java.util.*;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static cn.bidcheck.service.BidService.*;

/** Human decisions are durable evidence assertions; they never directly overwrite business verdicts. */
@Service
public class InferenceConfirmationService {
    private static final Logger log=LoggerFactory.getLogger(InferenceConfirmationService.class);
    private final Store store;private final ObjectProvider<BidService> bids;private final TransactionTemplate tx;
    public InferenceConfirmationService(Store store,ObjectProvider<BidService> bids,PlatformTransactionManager manager){this.store=store;this.bids=bids;this.tx=new TransactionTemplate(manager);}
    public void capture(String runId,String checkpointId,Document bid,Document tender,Map<String,Object> candidate){
        String id=stableId("inference:"+bid.projectId()+":"+runId+":"+text(candidate,"id"));
        Stored old=store.mapper().get(id);if(old!=null){log.info("bidcheck.inference_confirmation runId={} checkpointId={} claimId={} action=DUPLICATE_SKIPPED",runId,checkpointId,id);return;}
        Map<String,Object> value=new LinkedHashMap<>(candidate);value.put("id",id);value.put("engineClaimId",candidate.get("id"));value.put("runId",runId);value.put("checkpointId",checkpointId);
        value.put("projectId",bid.projectId());value.put("documentId",bid.id());value.put("documentHash",bid.sha256());value.put("documentVersion",bid.version());
        value.put("checkpointFingerprint",checkpointFingerprint(bid.projectId(),runId,checkpointId));
        value.put("tenderDocumentId",tender==null?"":tender.id());value.put("tenderHash",tender==null?"":tender.sha256());
        value.put("createdAt",now());value.put("status","NEEDS_CONFIRMATION");value.put("revision",0);
        store.save("inference_claim",id,bid.projectId(),runId,value);
        log.info("bidcheck.inference_confirmation runId={} checkpointId={} claimId={} engineClaimId={} origin={} action=CREATED revision=0 sourceCount={}",runId,checkpointId,id,text(candidate,"id"),text(candidate,"origin"),CheckResolution.maps(candidate.get("evidences")).size());
    }
    private static int number(Object value,int fallback){return value instanceof Number n&&n.doubleValue()==n.intValue()?n.intValue():fallback;}
    private String checkpointFingerprint(String project,String runId,String checkpointId){return store.list("snapshot",project,runId,Checkpoint.class).stream()
        .filter(c->checkpointId.equals(c.id())).map(c->stableId(store.encode(c))).findFirst().orElse("");}
    private boolean eligible(Map<String,Object> claim,Map<String,Object> run){
        if(!text(run,"projectId").equals(text(claim,"projectId"))||!text(run,"documentId").equals(text(claim,"documentId"))
            ||!text(run,"tenderDocumentId").equals(text(claim,"tenderDocumentId")))return false;
        if(text(run,"id").equals(text(claim,"runId")))return true;
        String fingerprint=text(claim,"checkpointFingerprint");
        return !fingerprint.isBlank()&&fingerprint.equals(checkpointFingerprint(text(run,"projectId"),text(run,"id"),text(claim,"checkpointId")));
    }
    public List<Map<String,Object>> list(String runId){Map<String,Object> run=store.getMap("run",runId);Map<String,Map<String,Object>> unique=new LinkedHashMap<>();
        List<Map<String,Object>> attachments=store.maps("supplement_attachment",text(run,"projectId"),null);
        store.maps("inference_claim",text(run,"projectId"),null).stream().filter(c->eligible(c,run))
            .sorted(Comparator.comparingInt(c->"NEEDS_CONFIRMATION".equals(text(c,"status"))?runId.equals(text(c,"runId"))?1:2:0))
            .forEach(c->unique.putIfAbsent(text(c,"engineClaimId"),c));
        return unique.values().stream().map(c->{Map<String,Object> visible=new LinkedHashMap<>(c);visible.put("originRunId",c.get("runId"));visible.put("runId",runId);
            visible.put("attachments",attachments.stream().filter(a->text(c,"id").equals(text(a,"claimId"))&&text(c,"checkpointId").equals(text(a,"checkpointId"))).toList());return visible;}).toList();}
    public Map<String,Object> exportRecords(String runId){Map<String,Object> run=store.getMap("run",runId);List<Map<String,Object>> claims=list(runId);Set<String> ids=new HashSet<>();claims.forEach(c->ids.add(text(c,"id")));
        return fields("runId",runId,"claims",claims,"history",store.maps("inference_decision",text(run,"projectId"),null).stream().filter(d->ids.contains(text(d,"claimId"))).toList());}
    /** Uploads a scoped attachment; it never turns an upload into a human confirmation. */
    public Map<String,Object> appendAttachment(String runId,String id,MultipartFile file,String note,int expectedRevision) throws IOException {
        Map<String,Object> run=store.getMap("run",runId),claim=store.getMap("inference_claim",id);
        if(!eligible(claim,run)||!text(run,"projectId").equals(text(claim,"projectId")))throw bad("补充材料不属于当前检查事项");
        if(!Set.of("COMPLETED","FAILED").contains(text(run,"status")))throw bad("请待本轮检查结束后补充材料");
        if(number(claim.get("revision"),0)!=expectedRevision)throw bad("确认记录已变化，请刷新后补充材料");
        if(note==null||note.isBlank()||note.length()>3000)throw bad("请说明补充材料用于证明的内容");
        if(file==null||file.isEmpty())throw bad("请选择需要补充的材料文件");
        Map<String,Object> result=bids.getObject().uploadSupplement(runId,text(claim,"checkpointId"),id,file,note.strip());
        log.info("bidcheck.inference_confirmation runId={} checkpointId={} claimId={} revision={} action=SUPPLEMENT_QUEUED attachmentId={} taskId={} recomputeStatus={}",runId,text(claim,"checkpointId"),id,expectedRevision,result.get("attachmentId"),result.get("taskId"),result.get("recomputeStatus"));return result;
    }
    public List<Map<String,Object>> confirmed(String project,Document bid,Document tender){
        return decisions(project,bid,tender).stream().filter(r->"CONFIRMED".equals(text(r,"status"))).toList();
    }
    public List<Map<String,Object>> confirmedForRun(String runId,String project,Document bid,Document tender){
        return decisionsForRun(runId,project,bid,tender).stream().filter(c->"CONFIRMED".equals(text(c,"status"))).toList();
    }
    /** Trusted overlay ledger. Ordinary extracted/model facts never gain this capability from JSON attributes. */
    public List<Map<String,Object>> sharedConfirmedForRun(String runId,String project,Document bid,Document tender){
        List<Map<String,Object>> frozen=confirmedForRun(runId,project,bid,tender),accepted=new ArrayList<>();
        Map<String,Document> documents=new HashMap<>();documents.put(bid.id(),bid);if(tender!=null)documents.put(tender.id(),tender);
        for(Map<String,Object> claim:frozen){Stored stored=store.mapper().get(ledgerKey(claim));String reason="LEDGER_RECORD_MISSING";
            if(stored!=null&&"inference_fact_ledger".equals(stored.kind)&&project.equals(stored.projectId)){
                Map<String,Object> ledger=store.map(stored.body);
                if(!Objects.equals(ledger.get("claimDigest"),claimDigest(claim)))reason="CONFIRMATION_CONTENT_CHANGED";
                else if(!sourceVersionsValid(claim,documents))reason="SOURCE_VERSION_OR_QUOTE_CHANGED";
                else{Map<String,Object> value=new LinkedHashMap<>(claim);value.put("confirmationLedgerId",stored.id);value.put("confirmationLedgerRevision",ledger.get("revision"));accepted.add(value);continue;}
            }
            log.info("bidcheck.confirmed_fact_lookup runId={} claimId={} revision={} accepted=false reason={}",runId,text(claim,"id"),claim.get("revision"),reason);
        }
        log.info("bidcheck.confirmed_fact_lookup runId={} phase=FROZEN_CONFIRMATION_LEDGER supplied={} accepted={}",runId,frozen.size(),accepted.size());return List.copyOf(accepted);
    }
    private static String ledgerKey(Map<String,Object> claim){return stableId("inference-fact-ledger:"+text(claim,"id")+":"+number(claim.get("revision"),-1));}
    private String claimDigest(Map<String,Object> claim){return stableId(store.encode(fields("id",claim.get("id"),"revision",claim.get("revision"),"status",claim.get("status"),"proposedFact",claim.get("proposedFact"),"evidences",claim.get("evidences"),"documentHash",claim.get("documentHash"),"documentVersion",claim.get("documentVersion"),"targetFingerprint",claim.get("targetFingerprint"))));}
    private boolean sourceVersionsValid(Map<String,Object> claim){return sourceVersionsValid(claim,new HashMap<>());}
    private boolean sourceVersionsValid(Map<String,Object> claim,Map<String,Document> documents){
        List<Map<String,Object>> sources=CheckResolution.maps(claim.get("evidences"));if(sources.isEmpty())return false;
        try{for(Map<String,Object> source:sources){String documentId=text(source,"documentId"),quote=text(source,"quote");if(quote.isBlank())return false;
            Document doc=documents.computeIfAbsent(documentId,id->bids.getObject().document(id));
            if(!text(claim,"projectId").equals(doc.projectId())||!doc.sha256().equals(text(source,"documentHash"))||doc.version()!=number(source.get("documentVersion"),-1))return false;
            if(doc.pages().stream().noneMatch(p->p.number()==number(source.get("page"),-1)&&p.locationType().equals(text(source,"locationType"))&&p.text()!=null&&p.text().contains(quote)))return false;
        }return true;}catch(RuntimeException ex){return false;}
    }
    private void publishConfirmedLedger(Map<String,Object> claim){
        if(!sourceVersionsValid(claim)){log.warn("bidcheck.confirmed_fact_publish claimId={} revision={} accepted=false reason=SOURCE_VERSION_OR_QUOTE_UNVERIFIED",text(claim,"id"),claim.get("revision"));return;}
        String id=ledgerKey(claim);Map<String,Object> ledger=fields("id",id,"claimId",claim.get("id"),"engineClaimId",claim.get("engineClaimId"),"revision",claim.get("revision"),"checkpointId",claim.get("checkpointId"),"claimDigest",claimDigest(claim),"documentId",claim.get("documentId"),"documentHash",claim.get("documentHash"),"documentVersion",claim.get("documentVersion"),"proposedFact",claim.get("proposedFact"),"evidences",claim.get("evidences"),"confirmedAt",claim.get("decidedAt"),"origin","HUMAN_CONFIRMED_SOURCE_RELATION");
        store.insertImmutableIfAbsent("inference_fact_ledger",id,text(claim,"projectId"),text(claim,"documentId"),ledger);
        log.info("bidcheck.confirmed_fact_publish claimId={} revision={} ledgerId={} accepted=true sourceCount={} origin=HUMAN_CONFIRMED_SOURCE_RELATION",text(claim,"id"),claim.get("revision"),id,CheckResolution.maps(claim.get("evidences")).size());
    }
    public List<Map<String,Object>> decisionsForRun(String runId,String project,Document bid,Document tender){
        Map<String,Object> run=store.getMap("run",runId);if(!project.equals(text(run,"projectId")))return List.of();
        List<Map<String,Object>> versions=CheckResolution.maps(run.get("inferenceConfirmationRecords"));if(versions.isEmpty())return List.of();
        Map<String,Integer> frozen=new HashMap<>();versions.forEach(v->frozen.put(text(v,"id"),number(v.get("revision"),-1)));
        return decisions(project,bid,tender).stream().filter(c->eligible(c,run)&&frozen.getOrDefault(text(c,"id"),-1)==number(c.get("revision"),-2)).toList();
    }
    public List<Map<String,Object>> decisions(String project,Document bid,Document tender){
        return store.maps("inference_claim",project,null).stream().filter(r->Set.of("CONFIRMED","REJECTED").contains(text(r,"status"))&&bid.id().equals(text(r,"documentId"))
            &&bid.sha256().equals(text(r,"documentHash"))&&number(r.get("documentVersion"),-1)==bid.version()
            &&Objects.equals(tender==null?"":tender.id(),text(r,"tenderDocumentId"))
            &&(tender==null?text(r,"tenderHash").isBlank():tender.sha256().equals(text(r,"tenderHash")))).toList();
    }
    public Map<String,Object> decide(String runId,String id,Map<String,Object> body){
        String decision=text(body,"decision"),reviewer=text(body,"reviewer").trim(),note=text(body,"note").trim();
        if(!Set.of("CONFIRMED","REJECTED").contains(decision)||reviewer.isBlank()||reviewer.length()>80||note.isBlank()||note.length()>3000)throw bad("请选择确认或否定，并填写确认人和依据说明");
        Map<String,Object> saved=tx.execute(status->{
            Map<String,Object> run=store.getMap("run",runId);String project=text(run,"projectId");store.lock("project",project);
            if(!Set.of("COMPLETED","FAILED").contains(text(run,"status")))throw bad("当前检查仍在执行，请结束后确认，以保持本次证据快照稳定");
            Stored row=store.lock("inference_claim",id);if(!project.equals(row.projectId))throw bad("推断不属于当前检查");
            Map<String,Object> claim=store.map(row.body);if(!eligible(claim,run))throw bad("推断与当前检查条款或文件版本不一致");int revision=number(claim.get("revision"),0);
            if(revision!=number(body.get("expectedRevision"),-1))throw bad("确认记录已变化，请刷新后重试");
            if(!"NEEDS_CONFIRMATION".equals(text(claim,"status")))throw bad("该推断已经处理，请刷新查看记录");
            if(store.mapper().get(stableId("inference-decided:"+project+":"+text(claim,"engineClaimId")))!=null)throw bad("相同证据的这一推断已经处理，请刷新查看已有决定，不能通过旧卡覆盖");
            Document bid=bids.getObject().document(text(claim,"documentId"));
            if(!bid.sha256().equals(text(claim,"documentHash"))||bid.version()!=number(claim.get("documentVersion"),-1))throw bad("投标文件已变化，请重新检查");
            String tenderId=text(claim,"tenderDocumentId");if(!tenderId.isBlank()&&!bids.getObject().document(tenderId).sha256().equals(text(claim,"tenderHash")))throw bad("招标文件已变化，请重新检查");
            // The UI may only decide a stored, server-validated assertion. It cannot submit replacement fact values.
            claim.put("status",decision);claim.put("revision",revision+1);claim.put("actor",reviewer);claim.put("decidedAt",now());claim.put("note",note);
            store.save("inference_claim",id,project,row.parentId,claim);
            String auditId=Store.id();store.save("inference_decision",auditId,project,runId,fields("id",auditId,"claimId",id,"originRunId",row.parentId,"runId",runId,"revision",revision+1,"decision",decision,"actor",reviewer,"decidedAt",claim.get("decidedAt"),"note",note,"authentication","LOCAL_REVIEWER_DECLARED"));
            if("CONFIRMED".equals(decision)){store.save("confirmed_inference",stableId("confirmed-inference:"+project+":"+text(claim,"engineClaimId")),project,bid.id(),claim);publishConfirmedLedger(claim);}
            store.save("inference_decided",stableId("inference-decided:"+project+":"+text(claim,"engineClaimId")),project,bid.id(),claim);
            log.info("bidcheck.inference_confirmation runId={} checkpointId={} claimId={} action={} revision={} documentVersion={}",runId,text(claim,"checkpointId"),id,decision,revision+1,bid.version());
            return claim;
        });
        Map<String,Object> result=fields("candidate",saved,"recomputeStatus","NOT_REQUIRED");
        if(Set.of("CONFIRMED","REJECTED").contains(decision)){
            try{Map<String,Object> next=bids.getObject().recoverRun(text(saved,"projectId"),runId);result.put("run",next);result.put("recomputeStatus","QUEUED");}
            catch(org.springframework.web.server.ResponseStatusException ex){result.put("recomputeStatus","SAVED_REQUIRES_RECHECK");result.put("reason",ex.getReason());}
            catch(RuntimeException ex){result.put("recomputeStatus","SAVED_REQUIRES_RECHECK");result.put("reason","确认已保存，创建重算任务失败；请从当前检查重新发起补查。");}
        }log.info("bidcheck.inference_confirmation runId={} checkpointId={} claimId={} action=RECOMPUTE_REQUEST revision={} recomputeStatus={}",runId,text(saved,"checkpointId"),id,saved.get("revision"),result.get("recomputeStatus"));return result;
    }
}
