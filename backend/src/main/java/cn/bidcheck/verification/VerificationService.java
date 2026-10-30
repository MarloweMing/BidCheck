package cn.bidcheck.verification;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.consistency.PersonCredentialConsistency;
import cn.bidcheck.consistency.ConsistencyRunSupport;
import cn.bidcheck.document.PersonCredentialFacts;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.*;
import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import cn.bidcheck.verification.engine.VerificationEngine;
import cn.bidcheck.verification.engine.PricingInputValidator;
import cn.bidcheck.verification.facts.FactLedgerService;
import cn.bidcheck.verification.source.EvidenceLayoutService;
import cn.bidcheck.verification.source.VisualVerificationService;
import cn.bidcheck.verification.external.ExternalVerificationAdapter;
import cn.bidcheck.verification.evidence.EvidenceOperationRepository;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static cn.bidcheck.service.BidService.*;

/** Immutable manifests and findings; the existing durable task queue owns execution leases. */
@Service
public class VerificationService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(VerificationService.class);
    public static final String VERSION="automation-1.7-person-credential-information";
    private static final Set<String> STAGES=Set.of("PRE_SUBMISSION","POST_SUBMISSION","EVALUATION");
    private static final Set<String> EXTERNAL_KINDS=Set.of("REGISTRATION","CREDIT","BLACKLIST","RESTRICTED_SUPPLIER","SUBMISSION","PAYMENT","BIDDER_ROSTER","PRIOR_SERVICE","OTHER");
    private final BidService bids;
    private final Store store;
    private final VerificationEngine engine;
    private final EvidenceLayoutService layouts;
    private final VisualVerificationService visuals;
    private final ExternalVerificationAdapter externalAdapter;
    private final CheckpointExecutor executor;
    private final FactLedgerService factLedger;
    private final EvidenceOperationRepository evidenceOperations;
    private final String factIdentity;
    private final TransactionTemplate tx;
    private final String configurationIdentity;
    private final boolean visualEnabled;
    private final int maxVisualCalls;
    private final ClarificationGuard clarifications=new ClarificationGuard();
    private final PricingVerification pricing=new PricingVerification();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.verification.graph.FactLedgerEvidenceGraphBridge evidenceGraphs;

    public VerificationService(BidService bids,VerificationEngine engine,EvidenceLayoutService layouts,
            VisualVerificationService visuals,ExternalVerificationAdapter externalAdapter,CheckpointExecutor executor,FactLedgerService factLedger,PlatformTransactionManager manager,
            @Value("${bidcheck.semantic.url:}") String modelUrl,@Value("${bidcheck.semantic.model:}") String model,
            @Value("${bidcheck.visual.enabled:false}") boolean visualEnabled,
            @Value("${bidcheck.visual.model:qwen3-vl-plus}") String visualModel,
            @Value("${bidcheck.verification.max-visual-calls:12}") int maxVisualCalls,
            @Value("${bidcheck.verification.max-model-calls:300}") int maxModelCalls) {
        this.bids=bids;this.store=bids.store;this.engine=engine;this.layouts=layouts;this.visuals=visuals;this.externalAdapter=externalAdapter;this.executor=executor;
        this.factLedger=factLedger;this.evidenceOperations=new EvidenceOperationRepository(this.store);this.factIdentity=hash("verification-observation-ledger-1|"+factLedger.identity()+"|"+layouts.identity()+"|"+PersonCredentialFacts.VERSION+"|"+PersonCredentialConsistency.VERSION);
        this.tx=new TransactionTemplate(manager);this.visualEnabled=visualEnabled;this.maxVisualCalls=Math.max(0,Math.min(100,maxVisualCalls));
        // maxModelCalls remains a constructor/configuration compatibility input. It is deliberately
        // excluded from execution and identity: production fact extraction must finish all packets.
        configurationIdentity=hash(VERSION+"|"+modelUrl+"|"+model+"|"+engine.identity()+"|"+visuals.identity()+"|"+externalAdapter.identity()+"|"+clarifications.identity()+"|"+pricing.identity()+"|"+factIdentity+"|"+maxVisualCalls+"|MODEL_CALLS_UNLIMITED|"+bids.ocrPreparationIdentity());
    }
    public Map<String,Object> configuration() {
        return fields("version",VERSION,"visualEnabled",visualEnabled,"maxVisualCalls",maxVisualCalls,"maxModelCalls",-1,"modelCallsUnlimited",true,
            "externalMode",externalAdapter.enabledTypes().isEmpty()?"SOURCE_RECORD_IMPORT":"CONFIGURED_CONNECTORS","externalConfigured",!externalAdapter.enabledTypes().isEmpty(),"externalLiveConnected",false,"externalTypes",externalAdapter.enabledTypes(),
            "factLayerVersion",factLedger.identity(),"wordRendering",layouts.wordConfiguration(),"capabilities",List.of("资料集合与不可变修订","逐条件核验及证明路径","跨检查项事实复用","实体身份与事实冲突","结构化表格与跨页证明","Word渲染页面映射","定向补证","原图局部核验","来源记录导入","补资料自动续跑"));
    }
    public String checkSourceIdentity(){return hash(VERSION+"|CHECK|"+visualEnabled+"|"+visuals.identity()+"|"+layouts.identity()+"|"+factLedger.identity()+"|EXTERNAL|"+externalAdapter.identity());}
    public List<Map<String,Object>> list(String project) { store.require("project",project);return store.maps("verification_run",project,null); }
    public Map<String,Object> start(String project,Map<String,Object> body) {
        return tx.execute(s->{store.lock("project",project);return startLocked(project,body,null);});
    }
    private Map<String,Object> startLocked(String project,Map<String,Object> body,Map<String,Object> parent) {
        Map<String,Object> projectState=store.getMap("project",project);
        String sourceId=parent==null?text(body,"sourceRunId"):text(parent,"sourceRunId");
        Map<String,Object> source=sourceId.isBlank()?Map.of():store.getMap("run",sourceId);
        if(!source.isEmpty()&&!project.equals(text(source,"projectId"))) throw bad("历史检查不属于本项目");
        List<String> ids=body.containsKey("documentIds")?strings(body.get("documentIds")):parent!=null?strings(parent.get("documentIds")):List.of();
        if(body.containsKey("documentIds")&&ids.isEmpty()) throw bad("核验资料集合不能为空");
        if(ids.isEmpty()&&!source.isEmpty()) ids=List.of(text(source,"documentId"));
        if(ids.isEmpty()) {
            List<Map<String,Object>> docs=store.documentSummaries(project).stream().filter(d->"BID".equals(d.get("role"))).toList();
            if(!docs.isEmpty()) ids=List.of(text(docs.get(docs.size()-1),"id"));
        }
        if(ids.isEmpty()||ids.size()>24||new HashSet<>(ids).size()!=ids.size()) throw bad("请选择1至24份不同的核验资料");
        List<Document> documents=new ArrayList<>();
        for(String id:ids) { Document d=bids.document(id); if(!project.equals(d.projectId())) throw bad("资料不属于本项目");documents.add(d); }
        if(documents.stream().noneMatch(d->"BID".equals(d.role()))) throw bad("至少需要一份投标文件");
        if(documents.stream().mapToLong(d->d.pages().size()).sum()>20000) throw bad("资料集合过大，请按项目分批核验");
        String tenderId=value(body,parent,"tenderDocumentId");
        if(tenderId.isBlank()) tenderId=text(source,"tenderDocumentId");
        if(tenderId.isBlank()) tenderId=text(projectState,"activeTenderId");
        String frozenTender=parent!=null?text(parent,"tenderDocumentId"):text(source,"tenderDocumentId");
        if(!frozenTender.isBlank()&&!frozenTender.equals(tenderId)) throw bad("历史检查的主招标依据已冻结；补充澄清请加入资料集合，更换主招标文件需重新确认检查项");
        Document tender=bids.document(tenderId);
        if(!project.equals(tender.projectId())||!"TENDER".equals(tender.role())) throw bad("请选择本项目招标文件");
        String stage=value(body,parent,"evaluationStage");if(stage.isBlank()) stage="PRE_SUBMISSION";
        if(!STAGES.contains(stage)) throw bad("核验阶段无效");
        List<Checkpoint> checks=parent!=null?store.list("verification_check",project,text(parent,"id"),Checkpoint.class)
            :sourceId.isBlank()?store.list("checkpoint",project,tenderId,Checkpoint.class):store.list("snapshot",project,sourceId,Checkpoint.class);
        if(parent==null&&sourceId.isBlank())bids.requireCurrentCheckpointRules(projectState,checks,tenderId);
        checks=checks.stream().filter(Checkpoint::enabled).toList();
        if(checks.isEmpty()||checks.stream().anyMatch(c->!c.confirmed())) throw bad("请先确认启用的招标检查项");
        if(checks.size()>500) throw bad("单次最多核验500个检查项");
        Set<String> allowed=new LinkedHashSet<>(ids);allowed.add(tenderId);
        List<Document> supplemental=documents.stream().filter(d->"TENDER".equals(d.role())&&!tender.id().equals(d.id())).toList();
        List<Map<String,Object>> changes=new ArrayList<>();for(Checkpoint cp:checks) {Map<String,Object> change=new LinkedHashMap<>(clarifications.assess(cp,supplemental));change.put("title",cp.title());changes.add(change);}
        List<Map<String,Object>> accepted=new ArrayList<>(parent==null?List.of():maps(parent.get("acceptedCheckpointOverrides")));
        List<Map<String,Object>> overrides=maps(body.get("acceptedCheckpointOverrides"));
        if(body.containsKey("acceptedCheckpointOverrides")&&overrides.isEmpty()) throw bad("请提供需要确认的规则修订");
        Set<String> changedIds=new HashSet<>();List<Checkpoint> revisedChecks=new ArrayList<>(checks);
        for(Map<String,Object> override:overrides) {
            String checkpointId=required(override,"checkpointId",64);if(!changedIds.add(checkpointId)||!Boolean.TRUE.equals(override.get("confirmed"))) throw bad("规则修订须逐项明确确认且不能重复");
            Checkpoint cp=checks.stream().filter(c->c.id().equals(checkpointId)).findFirst().orElseThrow(()->bad("规则修订不属于本次检查"));
            Map<String,Object> change=changes.stream().filter(c->checkpointId.equals(c.get("checkpointId"))).findFirst().orElseThrow();
            if(Boolean.TRUE.equals(change.get("scanIncomplete"))) throw bad("澄清文件扫描范围尚未完整，请按明确项目范围拆分资料后再确认规则");
            Set<String> candidateIds=new HashSet<>(maps(change.get("candidates")).stream().map(c->text(c,"id")).toList());
            if(candidateIds.isEmpty()||!candidateIds.equals(new HashSet<>(strings(override.get("candidateIds"))))) throw bad("澄清候选范围已变化，请刷新后逐条确认");
            Map<String,Object> origin=map(override.get("source"));String sourceDocumentId=required(origin,"documentId",64),quote=required(origin,"quote",4000);
            Document sourceDocument=supplemental.stream().filter(d->d.id().equals(sourceDocumentId)).findFirst().orElseThrow(()->bad("新规则依据必须是本次选入的招标澄清文件"));
            int location=number(origin.get("page"),0);Page sourcePage=sourceDocument.pages().stream().filter(p->p.number()==location&&p.text().contains(quote)).findFirst().orElseThrow(()->bad("新规则引用未对应所选澄清原文"));
            String expected=required(override,"expected",8000);Evidence evidence=new Evidence(sourceDocument.id(),sourceDocument.name(),location,sourcePage.locationType(),quote,"confirmed-amendment:"+hash(quote).substring(0,16),1);
            Checkpoint revision=new Checkpoint(cp.id(),cp.title(),cp.category(),cp.severity(),"SEMANTIC_RESPONSE",cp.field(),cp.operator(),expected,cp.unit(),cp.required(),true,cp.enabled(),evidence);
            revisedChecks.set(revisedChecks.indexOf(cp),revision);
            accepted.add(fields("checkpointId",cp.id(),"beforeExpected",cp.expected(),"afterExpected",expected,"source",evidence,"candidateIds",candidateIds.stream().sorted().toList(),"confirmation","USER_CONFIRMED_RULE_REVISION"));
        }
        checks=List.copyOf(revisedChecks);
        for(Checkpoint cp:checks) if(cp.source()!=null&&!allowed.contains(cp.source().documentId())) throw bad("本次资料集合必须保留已确认规则的来源文件");
        for(Map<String,Object> change:changes) {
            Set<String> confirmed=new HashSet<>();accepted.stream().filter(a->Objects.equals(a.get("checkpointId"),change.get("checkpointId"))).forEach(a->confirmed.addAll(strings(a.get("candidateIds"))));
            if(!Boolean.TRUE.equals(change.get("scanIncomplete"))&&!maps(change.get("candidates")).isEmpty()&&maps(change.get("candidates")).stream().allMatch(c->confirmed.contains(text(c,"id")))) change.put("requiresConfirmation",false);
        }
        String date=value(body,parent,"referenceDate"),dateSource=value(body,parent,"referenceDateSourceDocumentId");
        Map<String,Object> dateEvidence=referenceDate(project,date,dateSource,allowed);
        List<Map<String,Object>> external=store.maps("verification_external",project,null).stream()
            .filter(r->allowed.contains(text(r,"documentId"))).toList();
        List<Map<String,Object>> manifest=new ArrayList<>();
        for(String id:allowed) {Document d=bids.document(id);manifest.add(fields("documentId",id,"role",d.role(),"name",d.name(),"version",d.version(),"sha256",d.sha256()));}
        Map<String,Object> pricingData=body.containsKey("pricingData")?PricingInputValidator.validate(body.get("pricingData"),allowed.stream().map(bids::document).toList()):parent==null?Map.of():map(parent.get("pricingData"));
        if(!pricingData.isEmpty()) {
            // Normalize domain Evidence values before validating inherited source scope.
            Map<String,Object> encoded=store.map(store.encode(pricingData));List<Map<String,Object>> priceSources=new ArrayList<>();priceSources.add(map(encoded.get("rosterEvidence")));
            for(Map<String,Object> entry:maps(encoded.get("entries"))) priceSources.add(map(entry.get("evidence")));
            if(priceSources.stream().anyMatch(s->!allowed.contains(text(s,"documentId")))) throw bad("价格输入引用的原始文件必须保留在本次资料集合中；需要移除时请先清空价格输入");
        }
        String parentId=parent==null?"":text(parent,"id");
        String fingerprint=hash(store.encode(manifest)+store.encode(checks)+date+dateSource+stage+store.encode(external)+store.encode(accepted)+store.encode(pricingData)+configurationIdentity+parentId);
        for(Map<String,Object> r:list(project)) if(fingerprint.equals(r.get("fingerprint"))&&!"FAILED".equals(r.get("status"))) return r;
        String id=Store.id();int revision=parent==null?1:number(parent.get("revision"),1)+1;
        TaskRow task=bids.queueVerification(project,id);
        Map<String,Object> run=fields("id",id,"projectId",project,"sourceRunId",sourceId,"parentRunId",parentId,
            "rootRunId",parent==null?id:text(parent,"rootRunId"),"revision",revision,"documentIds",ids,"tenderDocumentId",tenderId,
            "referenceDate",date,"referenceDateSourceDocumentId",dateSource,"referenceDateEvidence",dateEvidence,"evaluationStage",stage,
            "manifest",manifest,"analysisRevision",hash(store.encode(manifest)+store.encode(checks)+date+dateSource+stage+store.encode(pricingData)+configurationIdentity),"externalRecords",external,"pricingData",pricingData,
            "requirementChanges",changes,"acceptedCheckpointOverrides",accepted,
            "status","PENDING","workflowState","RUNNING","stage","等待自动核验","progress",0,"createdAt",now(),
            "taskId",task.id,"checkCount",checks.size(),"ruleVersion",VERSION,"configurationIdentity",configurationIdentity,"factIdentity",factIdentity,
            "fingerprint",fingerprint,"visualCallCount",0,"externalCallCount",0,"modelCallCount",0);
        run.put("ocrPreparationIdentity",bids.ocrPreparationIdentity());
        store.save("verification_run",id,project,parentId,run);
        store.insertNew("verification_check",project,id,checks,c->stableId(id+":rule:"+c.id()));
        if(parent!=null&&configurationIdentity.equals(parent.get("configurationIdentity"))) {
            for(Checkpoint cp:checks) for(Map<String,Object> step:changedIds.contains(cp.id())?List.<Map<String,Object>>of():store.maps("verification_step",project,stableId(parentId+":steps:"+cp.id()))) {
                if(!"COMPILE".equals(step.get("type"))||!"COMPLETED".equals(step.get("status"))) continue;
                Map<String,Object> inherited=new LinkedHashMap<>(step);inherited.put("paidInPriorRevision",true);inherited.put("originRunId",parentId);inherited.put("runId",id);
                store.save("verification_step",stableId(id+":"+cp.id()+":step:"+text(step,"id")),project,stableId(id+":steps:"+cp.id()),inherited);
            }
        }
        return run;
    }
    private Map<String,Object> referenceDate(String project,String date,String documentId,Set<String> allowed) {
        if(date.isBlank()) {if(!documentId.isBlank()) throw bad("请同时提供核验日期");return Map.of();}
        try {LocalDate.parse(date);} catch(Exception e) {throw bad("核验日期必须是有效的YYYY-MM-DD日期");}
        if(documentId.isBlank()||!allowed.contains(documentId)) throw bad("指定截止日必须引用本次资料集合中的招标或澄清文件");
        Document d=bids.document(documentId);
        if(!project.equals(d.projectId())||!"TENDER".equals(d.role())) throw bad("日期依据必须是本项目招标或澄清文件");
        LocalDate target=LocalDate.parse(date);
        Pattern dates=Pattern.compile("(20\\d{2})[年/.-](\\d{1,2})[月/.-](\\d{1,2})日?");
        Pattern deadlineLabel=Pattern.compile("(?:投标(?:文件递交的)?截止(?:时间|日期)?|文件递交截止(?:时间|日期)?|递交截止(?:时间|日期)?)");
        for(Page p:d.pages()) {
            String[] lines=Optional.ofNullable(p.text()).orElse("").split("\\R");
            for(int i=0;i<lines.length;i++) {
                String line=lines[i];Matcher label=deadlineLabel.matcher(line);if(!label.find()) continue;
                String value=line.substring(label.end()).strip(),quote=line;
                if(value.matches("[\\s：:为是]*")&&i+1<lines.length) {value=lines[i+1].strip();quote=line+"\n"+lines[i+1];}
                // A following field is not a continuation of the deadline value.
                Matcher otherField=Pattern.compile("(?:合同|工期|开标|答疑|澄清|保证金|购买|发售).{0,10}(?:时间|日期|起算)|[；;。]").matcher(value);
                if(otherField.find()) value=value.substring(0,otherField.start());
                Matcher m=dates.matcher(value);Set<LocalDate> candidates=new LinkedHashSet<>();
                while(m.find()) try {if(m.start()<=50)candidates.add(LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))));}catch(DateTimeException ignored) {}
                if(candidates.size()==1&&candidates.contains(target)) {
                        return fields("documentId",d.id(),"documentName",d.name(),"page",p.number(),"locationType",p.locationType(),"quote",quote,"anchor","p"+p.number()+"-l"+(i+1),"confirmation","USER_SELECTED_VERSION","confirmed",true,
                            "ref",new Evidence(d.id(),d.name(),p.number(),p.locationType(),quote,"p"+p.number()+"-l"+(i+1),1));
                }
            }
        }
        throw bad("所选依据没有可定位的对应投标截止日期；请补充最终澄清文件");
    }
    public Map<String,Object> revise(String runId,Map<String,Object> body) {
        Map<String,Object> previous=store.getMap("verification_run",runId);String project=text(previous,"projectId");
        return tx.execute(s->{store.lock("project",project);Map<String,Object> parent=store.getMap("verification_run",runId);
            if(!runId.equals(text(latestRevision(project,text(parent,"rootRunId")),"id"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"已有较新修订，请从最新版本继续");
            if(!Set.of("COMPLETED","FAILED").contains(text(parent,"status"))) throw bad("当前核验尚在执行，请完成后再创建修订");
            if(body.containsKey("expectedRevision")&&number(body.get("expectedRevision"),-1)!=number(parent.get("revision"),1)) throw new ResponseStatusException(HttpStatus.CONFLICT,"版本已变化，请刷新");
            return startLocked(project,body,parent);
        });
    }
    public Map<String,Object> detail(String id) {
        Map<String,Object> run=store.getMap("verification_run",id);String project=text(run,"projectId");
        List<Map<String,Object>> results=store.maps("verification_result",project,id);
        Set<String> scope=new HashSet<>(strings(run.get("documentIds")));scope.addAll(evidenceDocumentIds(run));scope.add(text(run,"tenderDocumentId"));
        Stored personnel=store.mapper().get(stableId(id+":person-credentials"));
        return fields("run",run,"results",results,"summary",summary(results,number(run.get("checkCount"),0)+number(run.get("systemCheckCount"),0)),
            "personCredentialConsistency",personnel==null?Map.of():store.map(personnel.body),
            "documents",store.documentSummaries(project).stream().filter(d->scope.contains(text(d,"id"))).toList(),
            "inputs",store.maps("verification_input",project,null).stream().filter(i->text(run,"rootRunId").equals(text(i,"rootRunId"))).toList(),
            "externalRecords",run.getOrDefault("externalRecords",List.of()),"externalQueries",store.maps("verification_query",project,id));
    }
    public Map<String,Object> proof(String runId,String checkpointId) {
        Map<String,Object> run=store.getMap("verification_run",runId);
        return store.getMap("verification_result",stableId(runId+":result:"+checkpointId));
    }
    public Map<String,Object> facts(String runId) {
        Map<String,Object> view=new LinkedHashMap<>(factsForReport(runId));
        List<Map<String,Object>> occurrences=maps(view.get("occurrences"));
        // The browser paginates the complete ledger; conflicts must never point beyond a silent server-side cutoff.
        view.put("truncated",false);view.put("totalOccurrences",occurrences.size());view.put("returnedOccurrences",occurrences.size());
        return view;
    }
    public Map<String,Object> factsForReport(String runId) {
        Map<String,Object> run=store.getMap("verification_run",runId);String frozenIdentity=text(run,"factIdentity");
        if(frozenIdentity.isBlank()) return fields("runId",runId,"available",false,"reason","本次历史核验未建立统一事实库；历史结论保持原样","summary",fields("occurrences",0,"entities",0,"relations",0,"conflicts",0,"sharedOccurrences",0),"occurrences",List.of(),"entities",List.of(),"relations",List.of(),"conflicts",List.of());
        List<Document> docs=evidenceDocumentIds(run).stream().map(bids::document).filter(d->"BID".equals(d.role())).toList();
        Map<String,Object> view=new LinkedHashMap<>(factLedger.view(text(run,"projectId"),runId,frozenIdentity,docs));
        Stored personnel=store.mapper().get(stableId(runId+":person-credentials"));
        if(personnel!=null) {
            Map<String,Object> report=store.map(personnel.body);
            view.put("personCredentialCoverage",report.get("coverage"));
            view.put("personCredentialGroups",report.get("groups"));
            view.put("personCredentialInformationalGroups",report.getOrDefault("informationalGroups",List.of()));
            Map<String,List<String>> ids=new HashMap<>();
            for(Map<String,Object> occurrence:maps(view.get("occurrences"))) {
                String original=text(map(occurrence.get("attributes")),"id");
                if(!original.isBlank())ids.computeIfAbsent(original,k->new ArrayList<>()).add(text(occurrence,"occurrenceId"));
            }
            List<Map<String,Object>> conflicts=new ArrayList<>(maps(view.get("conflicts")));
            for(Map<String,Object> group:maps(report.get("groups"))) {
                List<String> linked=strings(group.get("occurrenceIds")).stream().flatMap(id->ids.getOrDefault(id,List.of()).stream()).distinct().toList();
                conflicts.add(fields("id",text(group,"id"),"status","REVIEW_REQUIRED","field",text(group,"fieldLabel"),"reason",text(group,"reason"),
                    "sourceRefs",group.getOrDefault("evidences",List.of()),"occurrenceIds",linked,"identityStrength","CANDIDATE","personCredentialStatus",group.get("status")));
            }
            Map<String,Object> summary=new LinkedHashMap<>(map(view.get("summary")));summary.put("conflicts",conflicts.size());
            view.put("summary",summary);view.put("stats",summary);view.put("conflicts",conflicts);
        }
        view.put("available",true);view.put("runId",runId);
        return view;
    }
    public void taskState(TaskRow task,String status,String error) {
        String id=text(store.map(task.inputJson),"runId");
        Map<String,Object> run=store.getMap("verification_run",id);run.put("status",status);run.put("error",error);
        if("FAILED".equals(status)) {run.put("finishedAt",now());run.put("workflowState","ERROR");}
        store.save("verification_run",id,task.projectId,text(run,"parentRunId"),run);
    }
    public void execute(TaskRow task) throws Exception {
        String runId=text(store.map(task.inputJson),"runId");Map<String,Object> run=store.getMap("verification_run",runId);
        if(!configurationIdentity.equals(run.get("configurationIdentity"))) throw bad("模型或核验配置已变化，请新建修订，避免混用结果");
        if(!bids.ocrPreparationIdentity().equals(text(run,"ocrPreparationIdentity")))throw bad("OCR配置或识别预算已变化，请新建修订");
        List<Checkpoint> checks=store.list("verification_check",task.projectId,runId,Checkpoint.class);
        change(task,runId,1,"准备全文识别与人员证件事实");
        List<Document> all=new ArrayList<>();
        for(String id:evidenceDocumentIds(run)) {
            Document source=bids.document(id);
            all.add(Boolean.TRUE.equals(run.get("ocrPreparationComplete"))||!"BID".equals(source.role())?source:bids.preparePlannedDocument(task,source,checks));
        }
        if(!Boolean.TRUE.equals(run.get("ocrPreparationComplete"))) {
            List<Map<String,Object>> prepared=all.stream().map(d->fields("documentId",d.id(),"role",d.role(),"name",d.name(),"version",d.version(),"sha256",d.sha256(),"ocrPlan",bids.ocrPlan(d.id()))).toList();
            fenced(task,()->{Map<String,Object> current=store.getMap("verification_run",runId);
                current.put("evidenceDocumentIds",all.stream().map(Document::id).toList());current.put("preparedManifest",prepared);current.put("ocrPreparationComplete",true);
                store.save("verification_run",runId,task.projectId,text(current,"parentRunId"),current);run.putAll(current);
            });
        }
        List<Document> bidDocs=all.stream().filter(d->"BID".equals(d.role())).toList();Document tender=bids.document(text(run,"tenderDocumentId"));
        preparePersonCredentials(task,runId,bidDocs);
        change(task,runId,2,"准备资料集合和证据位置");
        List<Map<String,Object>> layoutRefs=new ArrayList<>();
        List<Map<String,Object>> documentIntegrity=new ArrayList<>();
        LayoutEvidenceIndex layoutIndex=new LayoutEvidenceIndex();
        List<Document> layoutDocuments=new ArrayList<>(all);if(layoutDocuments.stream().noneMatch(d->d.id().equals(tender.id()))) layoutDocuments.add(tender);
        for(Document doc:layoutDocuments) {
            String layoutId=stableId("layout:"+doc.id()+":"+layouts.cacheIdentity(doc));
            Stored previousLayout=store.mapper().get(layoutId);
            if(previousLayout==null||!"COMPLETE".equals(store.map(previousLayout.body).get("status"))) {
                Map<String,Object> layout;
                try {layout=layouts.layout(doc,bids.path(doc.id()));}
                catch(IOException ex) {layout=fields("documentId",doc.id(),"status","UNAVAILABLE","reason","原件布局不可用，保留原文定位","pages",List.of());}
                Map<String,Object> saved=layout;fenced(task,()->store.save("verification_layout",layoutId,task.projectId,doc.id(),saved));
            }
            layoutRefs.add(fields("documentId",doc.id(),"layoutId",layoutId));
            Map<String,Object> storedLayout=store.getMap("verification_layout",layoutId);layoutIndex.add(doc.id(),storedLayout);
            if(storedLayout.containsKey("digitalSignatures")) documentIntegrity.add(fields("documentId",doc.id(),"documentName",doc.name(),"documentHash",doc.sha256(),"inspection",storedLayout.get("digitalSignatures")));
        }
        fenced(task,()->{Map<String,Object> current=store.getMap("verification_run",runId);current.put("layoutRefs",layoutRefs);current.put("documentIntegrity",documentIntegrity);store.save("verification_run",runId,task.projectId,text(current,"parentRunId"),current);});
        Map<String,Object> base=fields("sourceRunId",text(run,"sourceRunId"),"referenceDate",text(run,"referenceDate"),
            "referenceDateSource",text(run,"referenceDateSourceDocumentId"),"referenceDateEvidence",run.get("referenceDateEvidence"),
            "evaluationStage",run.get("evaluationStage"),"externalRecords",run.get("externalRecords"),"analysisRevision",run.get("analysisRevision"),
            "tenderDocuments",all.stream().filter(d->"TENDER".equals(d.role())).toList(),"documentSetComplete",false,"visualObservations",List.of(),"unlimitedModelCalls",true);
        base.put("allowModelRuleCompilation",false);
        base.put("layoutLookup",layoutIndex);
        base.put("layoutIdentity",layouts.identity());
        base.put("processingOccurrences",bids.processingOccurrences(bidDocs));
        base.put("pricingData",run.getOrDefault("pricingData",Map.of()));
        base.put("sharedFactIdentity",factIdentity);
        base.put("sharedFactLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->factLedger.lookup(task.projectId,factIdentity,bidDocs,request));
        base.put("evidenceOperationLookup",(Function<Map<String,Object>,Map<String,Object>>)request->evidenceOperations.completed(task.projectId,text(request,"operationKey")).orElse(Map.of()));
        base.put("evidenceOperationRepository",evidenceOperations);
        // This callback is a durable pre-dispatch audit and lease fence, not a quota. Every request
        // that is still needed is admitted; a stopped/stolen task still fails before a paid call.
        base.put("modelAttemptPermit",(BooleanSupplier)()->Boolean.TRUE.equals(tx.execute(s->{
            fence(task);Map<String,Object> current=store.getMap("verification_run",runId);int used=number(current.get("modelCallCount"),0);
            current.put("modelCallCount",Math.incrementExact(used));store.save("verification_run",runId,task.projectId,text(current,"parentRunId"),current);return true;
        })));
        // Completed checkpoint outputs survive process death. Engine steps also persist between calls.
        boolean[] existing=new boolean[checks.size()];
        for(int i=0;i<checks.size();i++) existing[i]=store.mapper().get(stableId(runId+":result:"+checks.get(i).id()))!=null;
        executor.forEachOrdered(checks.size(),index->{
            if(existing[index]) return null;Checkpoint cp=checks.get(index);Map<String,Object> context=new LinkedHashMap<>(base);
            context.put("sharedFactPublisher",(Consumer<Map<String,Object>>)batch->fenced(task,()->{
                store.lock("project",task.projectId);
                Map<String,Object> indexed=factLedger.publish(task.projectId,runId,cp.id(),factIdentity,bidDocs,batch);
                // Publish only a fully source-validated extraction receipt. UNREAD/FAILED and
                // multipart partial commits remain in the fact ledger but never become cache hits.
                List<Map<String,Object>> coverage=maps(batch.get("fieldCoverage"));Map<String,Object> partition=map(batch.get("observationPartition"));
                boolean completeOperation="EXTRACTED".equals(batch.get("origin"))&&number(partition.get("parts"),1)==1&&!coverage.isEmpty()
                    &&coverage.stream().allMatch(row->Boolean.TRUE.equals(row.get("sourceScopeComplete"))&&Set.of("EXTRACTED","READ_NOT_FOUND").contains(text(row,"status")));
                if(completeOperation&&batch.get("evidenceOperationDescriptor") instanceof Map<?,?>){
                    Map<String,Object> descriptor=map(batch.get("evidenceOperationDescriptor"));String operationKey=text(batch,"evidenceOperationKey");
                    evidenceOperations.publishCompleted(task.projectId,operationKey,descriptor,fields("facts",batch.getOrDefault("facts",List.of()),"sources",batch.getOrDefault("sources",List.of()),"fieldCoverage",coverage,"indexed",indexed));
                }
                String eventId=stableId(runId+":fact-event:"+cp.id()+":"+hash(store.encode(batch)));
                store.save("verification_fact_event",eventId,task.projectId,runId,fields("checkpointId",cp.id(),"result",indexed));
            }));
            String stepParent=stableId(runId+":steps:"+cp.id());
            context.put("priorSteps",store.maps("verification_step",task.projectId,stepParent));
            context.put("stepObserver",(Consumer<Map<String,Object>>)step->{
                String key=text(step,"id");if(key.isBlank()) key=hash(store.encode(step));
                String id=stableId(runId+":"+cp.id()+":step:"+key);Map<String,Object> saved=new LinkedHashMap<>(step);
                saved.put("checkpointId",cp.id());saved.put("runId",runId);saved.put("savedAt",now());
                fenced(task,()->store.save("verification_step",id,task.projectId,stepParent,saved));
            });
            Map<String,Object> amendment=maps(run.get("requirementChanges")).stream().filter(c->cp.id().equals(c.get("checkpointId"))&&Boolean.TRUE.equals(c.get("requiresConfirmation"))).findFirst().orElse(null);
            Map<String,Object> result=amendment==null?pricing.evaluate(cp,context).orElseGet(()->engine.evaluate(cp,bidDocs,tender,context)):fields("businessDecision","UNDETERMINED","complete",false,"workflowState","NEEDS_REVIEW","decisionBasis","RULE_POLICY","obligations",List.of(),
                "gaps",List.of(diagnostic("RULE_AMENDMENT_UNCONFIRMED","选入的招标澄清可能改变该项要求，需确认新规则后自动续跑","确认澄清后的完整规则及来源","REQUIREMENT_CONFIRMED")),
                "evidences",maps(amendment.get("candidates")).stream().map(c->c.get("evidence")).toList(),"reason","澄清变更尚未确认，保留旧规则与新原文，不沿用旧门槛判为符合");
            List<Map<String,Object>> diagnostics=new ArrayList<>();
            List<Map<String,Object>> observations=collectVisual(task,runId,cp,result,bidDocs,diagnostics);
            List<Map<String,Object>> externalObservations=collectExternal(task,runId,result,run,diagnostics);
            if(!observations.isEmpty()||!externalObservations.isEmpty()) {
                context.put("visualObservations",observations);List<Map<String,Object>> records=new ArrayList<>(maps(run.get("externalRecords")));records.addAll(externalObservations);context.put("externalRecords",records);
                context.put("priorSteps",store.maps("verification_step",task.projectId,stepParent));result=engine.evaluate(cp,bidDocs,tender,context);
            }
            Map<String,Object> normalized=new LinkedHashMap<>(result);normalized.put("id",stableId(runId+":result:"+cp.id()));normalized.put("runId",runId);
            normalized.put("checkpointId",cp.id());normalized.put("title",cp.title());normalized.put("checkpoint",cp);
            normalized.put("visualObservations",observations);normalized.put("externalObservations",externalObservations);normalized.put("createdAt",now());
            normalized.put("automationDiagnostics",diagnostics);
            normalized.put("externalQueryDiagnostics",diagnostics.stream().filter(d->text(d,"code").startsWith("EXTERNAL_")).toList());
            if(!Boolean.TRUE.equals(normalized.get("complete"))&&!diagnostics.isEmpty()) {
                List<Map<String,Object>> gaps=new ArrayList<>(maps(normalized.get("gaps")));gaps.addAll(diagnostics.stream().filter(d->!Set.of("EXTERNAL_RECORD_RECEIVED","EXTERNAL_RECORD_NOT_FOUND","EXTERNAL_RECORD_INCONCLUSIVE").contains(text(d,"code"))).toList());normalized.put("gaps",gaps);
                if(diagnostics.stream().anyMatch(d->text(d,"code").contains("BUDGET_EXHAUSTED"))) normalized.put("workflowState","BUDGET_EXHAUSTED");
                else if(diagnostics.stream().anyMatch(d->ExternalCheckState.systemFailure(text(d,"code"))||text(d,"code").contains("ENGINE_FAILURE")||text(d,"code").equals("VISUAL_NOT_CONFIGURED"))) normalized.put("workflowState","ENGINE_FAILURE");
            }
            if(!STATUSES.contains(text(normalized,"businessDecision"))) throw new IllegalStateException("核验引擎返回了无效结论");
            if("UNDETERMINED".equals(normalized.get("businessDecision"))) normalized.put("complete",false);
            return normalized;
        },(index,result)->fenced(task,()->{
            bids.verificationProgress(task,5+(index+1)*90/Math.max(1,checks.size()),"已核验 "+(index+1)+" / "+checks.size());
            if(result!=null) {
                store.save("verification_result",text(result,"id"),task.projectId,runId,result);
                // Persist the verified fact graph in the same fenced transaction as its conclusion.
                Map<String,Object> encoded=store.map(store.encode(result));
                for(String kind:List.of("facts","relations")) for(Map<String,Object> item:maps(encoded.get(kind))) {
                    Map<String,Object> snapshot=new LinkedHashMap<>(item);snapshot.put("runId",runId);snapshot.put("checkpointId",result.get("checkpointId"));snapshot.put("analysisRevision",run.get("analysisRevision"));
                    store.save("verification_"+kind,stableId(runId+":"+result.get("checkpointId")+":"+kind+":"+text(item,"id")),task.projectId,runId,snapshot);
                }
                store.save("verification_plan",stableId(runId+":plan:"+result.get("checkpointId")),task.projectId,runId,fields("checkpointId",result.get("checkpointId"),"analysisRevision",run.get("analysisRevision"),"plan",encoded.get("plan")));
            }
            Map<String,Object> current=store.getMap("verification_run",runId);current.put("progress",task.progress);current.put("stage",task.stage);
            store.save("verification_run",runId,task.projectId,text(current,"parentRunId"),current);
        }));
        fenced(task,()->{
            List<Map<String,Object>> results=store.maps("verification_result",task.projectId,runId);
            store.lock("project",task.projectId);
            Map<String,Object> factSnapshot=factLedger.snapshot(task.projectId,runId,factIdentity,bidDocs);
            Map<String,Object> current=store.getMap("verification_run",runId);Map<String,Object> sum=summary(results,checks.size()+number(current.get("systemCheckCount"),0));
            Map<String,Object> factSummary=new LinkedHashMap<>(map(factSnapshot.getOrDefault("summary",factSnapshot.getOrDefault("stats",Map.of()))));
            Stored personnel=store.mapper().get(stableId(runId+":person-credentials"));
            if(personnel!=null) {
                Map<String,Object> report=store.map(personnel.body);
                factSummary.put("conflicts",number(factSummary.get("conflicts"),0)+maps(report.get("groups")).size());
                report.put("ledgerSnapshotId",factSnapshot.get("id"));report.put("ledgerFrozen",true);report.put("ledgerSummary",factSummary);
                store.save("verification_person_credentials",personnel.id,task.projectId,runId,report);
            }
            current.put("factSummary",factSummary);
            publishEvidenceGraph(task.projectId,runId,bidDocs,current);
            current.put("status","COMPLETED");current.put("progress",100);current.put("stage","本轮核验处理结束");current.put("finishedAt",now());current.put("summary",sum);
            current.put("workflowState",aggregateWorkflow(results));
            store.save("verification_run",runId,task.projectId,text(current,"parentRunId"),current);bids.verificationFinished(task,"自动核验处理完成，业务结论请查看逐项结果");
        });
    }

    /** Publishes the same immutable, source-grounded relationship graph used by ordinary checks. */
    private void publishEvidenceGraph(String projectId,String runId,List<Document> manifest,Map<String,Object> run) {
        if(evidenceGraphs==null)return;
        String previousRevision="",parentRunId=text(run,"parentRunId");
        if(!parentRunId.isBlank())try{
            previousRevision=text(map(store.getMap("verification_run",parentRunId).get("evidenceGraph")),"revisionId");
        }catch(RuntimeException ignored){/* a missing parent cannot supply a reusable graph revision */}
        var refreshed=evidenceGraphs.refresh(projectId,runId,factIdentity,manifest,previousRevision,List.of());
        var graph=refreshed.graph();String bindingId=stableId("evidence-graph-binding:"+runId);
        Map<String,Object> binding=fields("id",bindingId,"projectId",projectId,"runId",runId,
            "revisionId",graph.snapshot().revision().id(),"contentDigest",graph.snapshot().revision().contentDigest(),
            "manifestDigest",graph.snapshot().revision().manifestDigest(),"occurrenceDigest",graph.snapshot().revision().occurrenceDigest(),
            "builderVersion",graph.snapshot().revision().builderVersion(),"sourceLedgerIdentities",List.of(factIdentity),
            "reused",graph.reused(),"previousRevisionId",graph.previousRevisionId(),
            "changedGraphDependencyKeys",graph.changedDependencyKeys(),"diagnostics",graph.snapshot().diagnostics(),"updatedAt",now());
        store.save("evidence_graph_binding",bindingId,projectId,runId,binding);
        run.put("evidenceGraph",fields("revisionId",graph.snapshot().revision().id(),
            "contentDigest",graph.snapshot().revision().contentDigest(),"entityCount",graph.snapshot().entities().size(),
            "relationCount",graph.snapshot().relations().size(),"supportedRelationCount",graph.snapshot().relations().stream()
                .filter(edge->edge.status()==cn.bidcheck.verification.graph.EvidenceGraph.RelationStatus.SUPPORTED).count(),
            "reused",graph.reused(),"bindingId",bindingId));
    }
    /** Independent baseline check. Frozen source occurrences are published before any rule can reuse them. */
    private void preparePersonCredentials(TaskRow task,String runId,List<Document> documents) {
        String reportId=stableId(runId+":person-credentials");
        if(store.mapper().get(reportId)!=null)return;
        Map<String,Object> report=new LinkedHashMap<>(new PersonCredentialConsistency().scan(documents));
        Map<String,Object> coverage=map(report.get("coverage"));
        List<Map<String,Object>> results=new ArrayList<>();
        for(Map<String,Object> group:maps(report.get("groups"))) {
            String checkpointId="system-person-credential-"+text(group,"id");
            Checkpoint checkpoint=new Checkpoint(checkpointId,text(group,"title"),"人员及证件检查","HIGH","FULL_TEXT_REVIEW","personCredentialConsistency","REVIEW","核对同一人员及同类证件信息","",true,true,true,null);
            results.add(fields("id",stableId(runId+":result:"+checkpointId),"runId",runId,"checkpointId",checkpointId,"checkpoint",checkpoint,
                "title",checkpoint.title(),"businessDecision","UNDETERMINED","complete",false,"workflowState","NEEDS_REVIEW","decisionBasis","SOURCE_GROUNDED_RULES",
                "reason",text(group,"reason"),"gaps",List.of(diagnostic("PERSON_CREDENTIAL_"+text(group,"status"),text(group,"reason"),"核对各处原文及人员关联","MANUAL_REVIEW")),
                "evidences",group.getOrDefault("evidences",List.of()),"obligations",List.of(),"systemCheck",true,"personCredentialGroup",group,"createdAt",now()));
        }
        if(PersonCredentialConsistency.requiresReview(coverage)) {
            String checkpointId="system-person-credential-coverage";
            Checkpoint checkpoint=new Checkpoint(checkpointId,"人员及证件扫描存在未完成项","人员及证件检查","HIGH","FULL_TEXT_REVIEW","personCredentialConsistency","REVIEW","扫描及来源核验未完成项须处理","",true,true,true,null);
            results.add(fields("id",stableId(runId+":result:"+checkpointId),"runId",runId,"checkpointId",checkpointId,"checkpoint",checkpoint,
                "title",checkpoint.title(),"businessDecision","UNDETERMINED","complete",false,"workflowState","NEEDS_REVIEW","decisionBasis","SOURCE_GROUNDED_RULES",
                "reason","存在未完成的扫描、比较或来源核验，详情见处理提示。","gaps",List.of(diagnostic("PERSON_CREDENTIAL_COVERAGE_PARTIAL","人员及证件检查存在技术处理未完成项","完成相关页面的识别或来源核验后重新检查","MANUAL_REVIEW")),
                "evidences",List.of(),"obligations",List.of(),"systemCheck",true,"personCredentialCoverage",coverage,"createdAt",now()));
        }
        fenced(task,()->{
            store.lock("project",task.projectId);
            ConsistencyRunSupport.publish(store,factLedger,runId,documents,factIdentity,report,false);
            for(Map<String,Object> result:results)store.save("verification_result",text(result,"id"),task.projectId,runId,result);
            store.save("verification_person_credentials",reportId,task.projectId,runId,report);
            Map<String,Object> current=store.getMap("verification_run",runId);current.put("systemCheckCount",results.size());
            current.put("personCredentialCoverage",coverage);store.save("verification_run",runId,task.projectId,text(current,"parentRunId"),current);
        });
    }
    private static List<String> evidenceDocumentIds(Map<String,Object> run) {
        return run.containsKey("evidenceDocumentIds")?strings(run.get("evidenceDocumentIds")):strings(run.get("documentIds"));
    }
    private static final class VisualGroup {
        final String id,operationKey;final Map<String,Object> operationDescriptor;final Document document;final java.nio.file.Path original;final List<Integer> pages;final Map<String,Object> request;
        final List<Map<String,Object>> bindings=new ArrayList<>();final Map<Integer,Map<String,Object>> completed=new TreeMap<>();
        final Set<Integer> splitPages=new HashSet<>();final Set<String> limitations=new LinkedHashSet<>();
        final Set<Integer> deferred=new HashSet<>();
        VisualGroup(String id,String operationKey,Map<String,Object> operationDescriptor,Document document,java.nio.file.Path original,List<Integer> pages,Map<String,Object> request){this.id=id;this.operationKey=operationKey;this.operationDescriptor=operationDescriptor;this.document=document;this.original=original;this.pages=pages;this.request=request;}
    }
    private record VisualSubmission(VisualGroup group,List<Integer> pages,boolean split){}
    private List<Map<String,Object>> collectVisual(TaskRow task,String runId,Checkpoint cp,Map<String,Object> result,List<Document> documents,List<Map<String,Object>> diagnostics) {
        return collectVisual(task,runId,cp,result,documents,diagnostics,false,()->true);
    }
    /** Shared page execution only. Ordinary CHECK keeps its own run, lease and unlimited call ledger. */
    public List<Map<String,Object>> collectCheckVisual(TaskRow task,String runId,Checkpoint cp,Map<String,Object> result,List<Document> documents,
            List<Map<String,Object>> diagnostics,BooleanSupplier permit){
        return collectVisual(task,runId,cp,result,documents,diagnostics,true,permit);
    }
    private void visualWrite(TaskRow task,String runId,boolean checkRun,Runnable action){
        if(!checkRun){DbTransactionRetry.executeWithoutResult(tx,"VERIFY_VISUAL_PUBLISH",Map.of("taskId",task.id,"runId",runId),s->{fence(task);action.run();});return;}
        DbTransactionRetry.executeWithoutResult(tx,"CHECK_VISUAL_PUBLISH",Map.of("taskId",task.id,"runId",runId),s->{
            fence(task);TaskRow current=store.mapper().task(task.id);Map<String,Object> run=store.map(store.lock("run",runId).body);
            if(current.attempt!=task.attempt||!task.projectId.equals(text(run,"projectId"))||!task.id.equals(text(run,"taskId"))||!"RUNNING".equals(text(run,"status")))throw new TaskLeaseLostException(task.id);
            action.run();fence(task);
        });
    }
    private List<Map<String,Object>> collectVisual(TaskRow task,String runId,Checkpoint cp,Map<String,Object> result,List<Document> documents,List<Map<String,Object>> diagnostics,boolean checkRun,BooleanSupplier checkPermit) {
        List<Map<String,Object>> requests=maps(result.get("visualRequests"));
        if(!visualEnabled){if(!requests.isEmpty())diagnostics.add(diagnostic("VISUAL_NOT_CONFIGURED","原图核验服务尚未启用；已有文本结论保留","启用已配置的原图核验服务","SERVICE_AVAILABLE"));return List.of();}
        Map<String,VisualGroup> groups=new LinkedHashMap<>();Map<String,java.nio.file.Path> originals=new HashMap<>();
        for(Map<String,Object> request:requests){
            String docId=text(request,"documentId");Document doc=documents.stream().filter(d->d.id().equals(docId)).findFirst().orElse(null);
            int page=number(request.getOrDefault("page",request.get("location")),0);
            if(doc==null||doc.pages()==null||doc.pages().stream().noneMatch(p->p.number()==page))continue;
            Map<String,Object> prepared=cn.bidcheck.verification.source.VisualPageWindows.withRegion(doc,page,request);
            for(List<Integer> window:cn.bidcheck.verification.source.VisualPageWindows.forPage(doc,page,prepared)){
                List<Map<String,Object>> sourcePages=doc.pages().stream().filter(p->window.contains(p.number())).map(p->fields("documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",doc.version(),"page",p.number(),"locationType",p.locationType(),"contentHash",hash(p.number()+"\n"+p.parser()+"\n"+p.text()+"\n"+(p.layout()==null?"":p.layout().toMap())))).toList();
                // Visual output depends on the normalized image/field request, not on the checkpoint
                // that happened to request it. Keeping obligation ids and question text out of the
                // durable key lets later checks reuse the same source-grounded observation.
                Map<String,Object> visualRequest=fields("requestFingerprint",visuals.requestIdentity(doc,window,prepared));
                Map<String,Object> descriptor=EvidenceOperationRepository.descriptor(task.projectId,"VISUAL_OBSERVATION",List.of(fields("documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",doc.version())),sourcePages,visualRequest,"",visuals.identity(),visuals.structuredWireVersion(),cn.bidcheck.verification.source.VisualObservationPolicy.VERSION);
                String operationKey=EvidenceOperationRepository.key(descriptor),id=stableId("visual-window:"+operationKey);VisualGroup group=groups.get(id);
                if(group==null){
                    java.nio.file.Path original=originals.get(docId);
                    if(original==null)try{original=bids.path(docId);originals.put(docId,original);}
                    catch(IOException unavailable){diagnostics.add(diagnostic("VISUAL_SOURCE_UNAVAILABLE","原件读取未完成，无法核验图像","恢复原件访问后重试","SERVICE_AVAILABLE"));continue;}
                    group=new VisualGroup(id,operationKey,descriptor,doc,original,window,prepared);groups.put(id,group);
                }
                group.bindings.add(request);
            }
        }
        Map<String,VisualSubmission> submissions=new LinkedHashMap<>();
        List<cn.bidcheck.verification.source.VisualWorkCoordinator.Job> jobs=new ArrayList<>();
        List<EvidenceOperationRepository.Lease> visualFlights=new ArrayList<>();
        List<VisualGroup> orderedGroups=groups.values().stream().sorted(Comparator.comparing(group->group.operationKey)).toList();
        for(VisualGroup group:orderedGroups){
            loadVisualGroupState(task,group);
            if(group.completed.size()==group.pages.size())continue;
            boolean hasRunnablePage=group.pages.stream().anyMatch(page->!group.completed.containsKey(page)&&!group.deferred.contains(page));
            if(!hasRunnablePage){if(!group.deferred.isEmpty())diagnostics.add(diagnostic("VISUAL_RETRY_PENDING","原图服务冷却中，已保留完成页面；未完成位置将在下次继续时重试","待服务冷却结束后继续","SERVICE_AVAILABLE"));continue;}
            try{
                // Hold the stable operation lease through provider execution and the durable write.
                // A concurrent run waits here, then re-reads the completed operation before deciding
                // whether any provider request remains.
                EvidenceOperationRepository.Lease flight=evidenceOperations.coordinate(task.projectId,group.operationKey);
                loadVisualGroupState(task,group);
                if(group.completed.size()==group.pages.size()){flight.close();continue;}
                hasRunnablePage=group.pages.stream().anyMatch(page->!group.completed.containsKey(page)&&!group.deferred.contains(page));
                if(!hasRunnablePage){flight.close();continue;}
                visualFlights.add(flight);
            }catch(java.io.InterruptedIOException interrupted){Thread.currentThread().interrupt();diagnostics.add(diagnostic("VISUAL_SINGLE_FLIGHT_INTERRUPTED","等待相同原图证据操作时已中断，未重复调用模型","继续运行后重试","SERVICE_AVAILABLE"));continue;}
            if(!group.deferred.isEmpty())diagnostics.add(diagnostic("VISUAL_RETRY_PENDING","原图服务冷却中，已保留完成页面；未完成位置将在下次继续时重试","待服务冷却结束后继续","SERVICE_AVAILABLE"));
            if(group.completed.isEmpty()&&group.deferred.isEmpty())jobs.add(visualJob(group,group.pages,false,runId,submissions));
            else for(int page:group.pages)if(!group.completed.containsKey(page)&&!group.deferred.contains(page)){group.splitPages.add(page);jobs.add(visualJob(group,List.of(page),true,runId,submissions));}
        }
        boolean[] exhausted={false};
        BooleanSupplier reserve=()->{
            if(checkRun){
                if(!checkPermit.getAsBoolean())return false;
                visualWrite(task,runId,true,()->{Map<String,Object> run=store.getMap("run",runId);run.put("visualCallCount",number(run.get("visualCallCount"),0)+1);store.save("run",runId,task.projectId,text(run,"documentId"),run);});
                return true;
            }
            if(exhausted[0])return false;
            boolean allowed=Boolean.TRUE.equals(tx.execute(s->{fence(task);Map<String,Object> current=store.getMap("verification_run",runId);int used=number(current.get("visualCallCount"),0);
                if(used>=maxVisualCalls)return false;current.put("visualCallCount",used+1);store.save("verification_run",runId,task.projectId,text(current,"parentRunId"),current);return true;}));
            if(!allowed){exhausted[0]=true;diagnostics.add(diagnostic("VISUAL_BUDGET_EXHAUSTED","本轮原图调用额度已用尽，剩余原图步骤未执行","调整原图预算后建立修订","BUDGET_CHANGED"));}
            return allowed;
        };
        try{cn.bidcheck.verification.source.VisualWorkCoordinator.run(jobs,visuals.parallelism(),reserve,()->!exhausted[0],(job,response)->{
            VisualSubmission submission=submissions.get(job.id());VisualGroup group=submission.group();
            // All storage, lease checks, budget callbacks and result bindings remain on this caller thread.
            Map<String,Object> trace=new LinkedHashMap<>(response);trace.put("runId",runId);trace.put("checkpointId",cp.id());trace.put("windowId",group.id);
            trace.put("inputPages",submission.pages());trace.put("split",submission.split());trace.put("documentId",group.document.id());trace.put("recordedAt",now());
            visualWrite(task,runId,checkRun,()->store.save("verification_visual_call",job.id(),task.projectId,runId,trace));
            group.limitations.addAll(strings(response.get("limitations")));
            for(Map<String,Object> page:maps(response.get("pageResults"))){
                if(!validVisualPage(group.document,submission.pages(),page))continue;
                int location=number(page.get("page"),0);Map<String,Object> saved=new LinkedHashMap<>(page);
                saved.put("inputPages",submission.pages());saved.put("callId",job.id());saved.put("identity",visuals.identity());saved.put("windowId",group.id);
                group.completed.put(location,saved);visualWrite(task,runId,checkRun,()->store.save("verification_visual_page",visualPageId(group,location),task.projectId,runId,saved));
                Map<String,Object> state=visualPageState(group,location,"COMPLETED",response);
                visualWrite(task,runId,checkRun,()->store.save("verification_visual_page_status",visualPageStatusId(group,location),task.projectId,runId,state));
            }
            if("RETRY_PENDING".equals(response.get("status")))for(int page:submission.pages())if(!group.completed.containsKey(page)){
                group.deferred.add(page);Map<String,Object> state=visualPageState(group,page,"RETRY_PENDING",response);
                visualWrite(task,runId,checkRun,()->store.save("verification_visual_page_status",visualPageStatusId(group,page),task.projectId,runId,state));
            }
            List<cn.bidcheck.verification.source.VisualWorkCoordinator.Job> next=new ArrayList<>();
            if(group.completed.size()==group.pages.size()){
                Map<String,Object> saved=visualGroupResponse(group);visualWrite(task,runId,checkRun,()->{store.save("verification_visual",group.id,task.projectId,runId,saved);evidenceOperations.publishCompleted(task.projectId,group.operationKey,group.operationDescriptor,saved);});
            }else if(!exhausted[0]&&!submission.split()&&submission.pages().size()>1&&VisualVerificationService.splitRetryable(response)){
                for(int page:submission.pages())if(!group.completed.containsKey(page)&&group.splitPages.add(page))next.add(visualJob(group,List.of(page),true,runId,submissions));
            }
            if(next.isEmpty()&&group.completed.size()<group.pages.size()&&!"VISUAL_BUDGET_EXHAUSTED".equals(text(response,"errorCode")))
                diagnostics.add("RETRY_PENDING".equals(response.get("status"))
                    ?diagnostic("VISUAL_RETRY_PENDING","原图服务暂时限流或不可用，已保留完成页面；未完成位置暂缓","待服务冷却结束后继续","SERVICE_AVAILABLE")
                    :diagnostic("VISUAL_ENGINE_FAILURE","部分原图位置未取得可用观察，不表示投标缺材料","恢复原图服务或人工核对未完成位置","SERVICE_AVAILABLE"));
            return next;
        });}finally{for(int i=visualFlights.size()-1;i>=0;i--)visualFlights.get(i).close();}
        Map<String,Map<String,Object>> observations=new LinkedHashMap<>();
        for(VisualGroup group:groups.values())for(Map<String,Object> request:group.bindings)for(Map<String,Object> page:group.completed.values())for(Map<String,Object> observation:maps(page.get("observations"))){
            int observedPage=number(observation.get("page"),0);Map<String,Object> source=map(observation.get("source"));Document doc=group.document;
            if(!validVisualSource(doc,observedPage,source)||!validVisualObservation(observation,source))continue;
            Page located=doc.pages().stream().filter(p->p.number()==observedPage).findFirst().orElse(null);if(located==null)continue;
            String observationId=VisualVerificationService.observationKey(observation),binding=observationId+":"+text(request,"obligationId");
            Map<String,Object> existing=observations.get(binding);
            if(existing!=null){LinkedHashSet<String> notes=new LinkedHashSet<>(strings(existing.get("modelLimitations")));notes.addAll(strings(observation.get("modelLimitations")));existing.put("modelLimitations",List.copyOf(notes));continue;}
            List<String> sourceIds=requests.stream().filter(r->doc.id().equals(text(r,"documentId"))&&text(request,"obligationId").equals(text(r,"obligationId"))
                &&observedPage==number(r.getOrDefault("page",r.get("location")),0)).flatMap(r->strings(r.get("sourceIds")).stream()).distinct().toList();
            Map<String,Object> o=new LinkedHashMap<>(observation);o.put("id",observationId);o.put("documentId",doc.id());o.put("page",observedPage);o.put("location",observedPage);
            o.put("observationIdentity",visuals.identity());
            o.put("obligationId",text(request,"obligationId"));o.put("checkpointId",cp.id());o.put("requestedSourceIds",sourceIds);
            o.put("requestedTask",request.getOrDefault("task","FIELD"));o.put("requestedFields",request.getOrDefault("fields",List.of()));o.put("locationType",located.locationType());
            o.put("source",source);o.put("imageHash",source.getOrDefault("renderedSha256",""));o.put("taskType",request.getOrDefault("task","FIELD"));
            o.put("observedValue",o.getOrDefault("value",""));o.put("limitations",List.copyOf(group.limitations));observations.put(binding,o);
        }
        return new ArrayList<>(observations.values());
    }
    private void loadVisualGroupState(TaskRow task,VisualGroup group){
        evidenceOperations.completed(task.projectId,group.operationKey).ifPresent(previous->{
            if(VisualVerificationService.cacheable(previous))for(Map<String,Object> page:maps(previous.get("pageResults")))if(validVisualPage(group.document,group.pages,page))group.completed.put(number(page.get("page"),0),page);
            group.limitations.addAll(strings(previous.get("limitations")));
        });
        Stored cached=store.mapper().get(group.id);
        if(cached!=null&&"verification_visual".equals(cached.kind)&&task.projectId.equals(cached.projectId)){
            Map<String,Object> previous=store.map(cached.body);
            if(VisualVerificationService.cacheable(previous))for(Map<String,Object> page:maps(previous.get("pageResults")))if(validVisualPage(group.document,group.pages,page))group.completed.put(number(page.get("page"),0),page);
            group.limitations.addAll(strings(previous.get("limitations")));
        }
        for(int page:group.pages)if(!group.completed.containsKey(page)){
            Stored stored=store.mapper().get(visualPageId(group,page));
            if(stored!=null&&"verification_visual_page".equals(stored.kind)&&task.projectId.equals(stored.projectId)){
                Map<String,Object> previous=store.map(stored.body);if(validVisualPage(group.document,group.pages,previous))group.completed.put(page,previous);
            }
            Stored pending=store.mapper().get(visualPageStatusId(group,page));
            if(!group.completed.containsKey(page)&&pending!=null&&"verification_visual_page_status".equals(pending.kind)&&task.projectId.equals(pending.projectId)){
                Map<String,Object> state=store.map(pending.body);Object until=state.get("retryNotBeforeEpochMillis");
                if("RETRY_PENDING".equals(state.get("status"))&&group.id.equals(state.get("windowId"))&&group.document.sha256().equals(state.get("documentHash"))
                    &&group.document.version()==number(state.get("documentVersion"),-1)&&until instanceof Number n&&n.longValue()>System.currentTimeMillis())group.deferred.add(page);
            }
        }
    }
    private cn.bidcheck.verification.source.VisualWorkCoordinator.Job visualJob(VisualGroup group,List<Integer> pages,boolean split,String runId,Map<String,VisualSubmission> submissions){
        String id=Store.id();submissions.put(id,new VisualSubmission(group,pages,split));
        return new cn.bidcheck.verification.source.VisualWorkCoordinator.Job(id,permit->visuals.verifyWindow(group.document,group.original,pages,group.request,permit,group.operationKey));
    }
    private static String visualPageId(VisualGroup group,int page){return stableId(group.id+":page:"+page);}
    private static String visualPageStatusId(VisualGroup group,int page){return stableId(group.id+":page-status:"+page);}
    private Map<String,Object> visualPageState(VisualGroup group,int page,String status,Map<String,Object> response){
        return fields("status",status,"page",page,"windowId",group.id,"documentId",group.document.id(),"documentHash",group.document.sha256(),
            "documentVersion",group.document.version(),"identity",visuals.identity(),"errorCode","COMPLETED".equals(status)?"":response.get("errorCode"),
            "retryNotBeforeEpochMillis","COMPLETED".equals(status)?0:response.getOrDefault("retryNotBeforeEpochMillis",System.currentTimeMillis()),"recordedAt",now());
    }
    private Map<String,Object> visualGroupResponse(VisualGroup group){
        List<Map<String,Object>> pages=new ArrayList<>(group.completed.values());List<Map<String,Object>> observations=pages.stream().flatMap(p->maps(p.get("observations")).stream()).toList();
        return fields("status",observations.isEmpty()?"UNDETERMINED":"OBSERVED","errorCode",observations.isEmpty()?"VISUAL_NO_GROUNDED_OBSERVATIONS":"",
            "pageResults",pages,"observations",observations,"inputPages",group.pages,"limitations",List.copyOf(group.limitations),
            "identity",visuals.identity(),"proofLevel","VISUAL_OBSERVATION_ONLY","authenticityVerified",false);
    }
    private static boolean validVisualPage(Document doc,List<Integer> requested,Map<String,Object> page){
        int location=number(page.get("page"),0);Map<String,Object> source=map(page.get("source"));
        if(!"COMPLETED".equals(page.get("status"))||!requested.contains(location)||!validVisualSource(doc,location,source))return false;
        for(Map<String,Object> observation:maps(page.get("observations")))if(number(observation.get("page"),0)!=location
            ||!source.equals(map(observation.get("source")))||!validVisualObservation(observation,source))return false;
        return page.get("observations") instanceof List<?>;
    }
    private static boolean validVisualSource(Document doc,int page,Map<String,Object> source){
        return doc.id().equals(text(source,"documentId"))&&doc.projectId().equals(text(source,"projectId"))&&doc.sha256().equals(text(source,"documentHash"))
            &&doc.version()==number(source.get("documentVersion"),-1)&&page==number(source.get("page"),-1)
            &&doc.pages().stream().anyMatch(p->p.number()==page)&&text(source,"renderedSha256").matches("[a-f0-9]{64}")
            &&number(source.get("renderedWidth"),0)>0&&number(source.get("renderedHeight"),0)>0;
    }
    private static boolean validVisualObservation(Map<String,Object> observation,Map<String,Object> source){
        Object raw=observation.get("sourceBbox");
        if(raw==null)return Set.of("UNREADABLE","NOT_OBSERVED").contains(text(observation,"readability"))&&text(observation,"value").isBlank();
        if(!(raw instanceof List<?> box)||box.size()!=4)return false;double[] b=new double[4];
        for(int i=0;i<4;i++){if(!(box.get(i) instanceof Number n)||!Double.isFinite(n.doubleValue()))return false;b[i]=n.doubleValue();}
        return b[0]>=0&&b[1]>=0&&b[0]<b[2]&&b[1]<b[3]&&b[2]<=number(source.get("renderedWidth"),0)&&b[3]<=number(source.get("renderedHeight"),0);
    }

    private List<Map<String,Object>> collectExternal(TaskRow task,String runId,Map<String,Object> result,Map<String,Object> run,List<Map<String,Object>> diagnostics) {
        return collectExternal(task,runId,result,run,diagnostics,false,()->true);
    }
    /** Ordinary CHECK uses the same stable external evidence operation as standalone verification,
     * while fencing its own run record and recomputing the requesting rule in BidService. */
    public List<Map<String,Object>> collectCheckExternal(TaskRow task,String runId,Map<String,Object> result,Map<String,Object> run,List<Map<String,Object>> diagnostics,BooleanSupplier permit){
        return collectExternal(task,runId,result,run,diagnostics,true,permit==null?()->true:permit);
    }
    private List<Map<String,Object>> collectExternal(TaskRow task,String runId,Map<String,Object> result,Map<String,Object> run,List<Map<String,Object>> diagnostics,boolean checkRun,BooleanSupplier checkPermit) {
        List<Map<String,Object>> records=new ArrayList<>();
        for(Map<String,Object> request:maps(result.get("externalRequests"))) {
            String type=text(request,"type"),subject=text(request,"subject"),stage=text(request,"evaluationStage"),date=text(request,"referenceDate");
            if(stage.isBlank())stage=text(run,"evaluationStage");if(date.isBlank())date=text(run,"referenceDate");
            if("WAIT_STAGE".equals(text(result,"workflowState"))||!text(run,"evaluationStage").isBlank()&&!stage.isBlank()
                    &&!sameExternalStage(stage,text(run,"evaluationStage"))){
                externalDiagnostic(diagnostics,request,"EXTERNAL_WAIT_STAGE","条款指定的核验阶段尚未到达，未执行外部查询。",false,"STAGE_CHANGED","");continue;
            }
            if(subject.isBlank()||subject.length()>200||type.isBlank()||stage.isBlank()||date.isBlank()){
                externalDiagnostic(diagnostics,request,"EXTERNAL_QUERY_SCOPE_INCOMPLETE","外部请求缺少有效主体、来源类型、阶段或日期，系统尚未执行查询。",false,"QUERY_SCOPE_CHANGED","");continue;
            }
            if(!externalAdapter.enabledTypes().contains(type)){
                String code="CONFIGURATION_INVALID".equals(externalAdapter.identity())?"EXTERNAL_CONFIGURATION_INVALID":"EXTERNAL_CONNECTOR_NOT_CONFIGURED";
                externalDiagnostic(diagnostics,request,code,"条款指定的数据来源未正确接入，系统没有执行查询；不表示来源无记录。",false,"CONNECTOR_CONFIGURATION_CHANGED","");continue;
            }
            Map<String,Object> query=fields("type",type,"subject",subject,"evaluationStage",stage,"referenceDate",date);
            // Scope participates in the durable operation key and must be confirmed by
            // the provider; a previous same-subject lookup is not an all-fields receipt.
            for(String key:List.of("requiredProvider","requirementScopeFingerprint","requiredConditionFields"))
                if(request.containsKey(key))query.put(key,request.get(key));
            Map<String,Object> descriptor=EvidenceOperationRepository.descriptor(task.projectId,"EXTERNAL_SOURCE_QUERY",List.of(),List.of(),query,"",externalAdapter.identity(),"TRUSTED_CONNECTOR_RECORD","external-source-query-1");
            String operationKey=EvidenceOperationRepository.key(descriptor),id=stableId("external-query:"+operationKey);
            try(EvidenceOperationRepository.Lease ignored=evidenceOperations.coordinate(task.projectId,operationKey)) {
                // The persistent operation is deliberately re-read after acquiring the stable-key
                // lease. Concurrent runs therefore make one connector call and all followers consume
                // the validated record written by its owner.
                Map<String,Object> response=evidenceOperations.completed(task.projectId,operationKey).orElse(null);Stored cached=store.mapper().get(id);
                if(response==null&&cached!=null&&"verification_query".equals(cached.kind)&&task.projectId.equals(cached.projectId)) {
                    Map<String,Object> candidate=store.map(cached.body);
                    // Persist failures for diagnosis, but never freeze them as successful evidence.
                    // Once the connector recovers, the same business scope must be queried again.
                    if("TRUSTED_CONNECTOR".equals(map(candidate.get("record")).get("provenanceStatus")))response=candidate;
                }
                boolean reused=response!=null;
                if(response==null) {
                    Boolean reserved;if(checkRun){reserved=checkPermit.getAsBoolean();if(Boolean.TRUE.equals(reserved))visualWrite(task,runId,true,()->{Map<String,Object> current=store.getMap("run",runId);current.put("externalCallCount",number(current.get("externalCallCount"),0)+1);store.save("run",runId,task.projectId,text(current,"documentId"),current);});}
                    else reserved=tx.execute(s->{fence(task);Map<String,Object> current=store.getMap("verification_run",runId);int used=number(current.get("externalCallCount"),0);
                        current.put("externalCallCount",used+1);store.save("verification_run",runId,task.projectId,text(current,"parentRunId"),current);return true;});
                    if(!Boolean.TRUE.equals(reserved)) {externalDiagnostic(diagnostics,request,"EXTERNAL_QUERY_NOT_EXECUTED","运行许可已失效，查询尚未执行。",false,"RUN_RESUMED","");break;}
                    response=externalAdapter.query(query);Map<String,Object> evidenceResult=new LinkedHashMap<>(response);Map<String,Object> saved=new LinkedHashMap<>(response);saved.put("runId",runId);saved.put("query",query);
                    Map<String,Object> record=map(response.get("record"));visualWrite(task,runId,checkRun,()->{store.save("verification_query",id,task.projectId,runId,saved);
                        if("TRUSTED_CONNECTOR".equals(record.get("provenanceStatus")))evidenceOperations.publishCompleted(task.projectId,operationKey,descriptor,evidenceResult);
                    });
                }
                Map<String,Object> record=map(response.get("record"));
                if("TRUSTED_CONNECTOR".equals(record.get("provenanceStatus"))){
                    records.add(record);String sourceStatus=text(map(record.get("result")),"status");
                    String code=switch(sourceStatus){case "NOT_FOUND"->"EXTERNAL_RECORD_NOT_FOUND";case "INCONCLUSIVE"->"EXTERNAL_RECORD_INCONCLUSIVE";default->"EXTERNAL_RECORD_RECEIVED";};
                    externalDiagnostic(diagnostics,request,code,reused?"已复用同主体、同阶段和同范围的可信来源记录，仍需按业务条件重新计算。":"已取得指定来源记录，仍需按主体、范围和业务条件重新计算。",!reused&&!Boolean.FALSE.equals(response.get("queryExecuted")),"EXTERNAL_RECORD_CHANGED","");
                    diagnostics.get(diagnostics.size()-1).put("recordReused",reused);
                }else {
                    String providerCode=text(response,"errorCode");
                    String code=Set.of("EXTERNAL_CONNECTOR_NOT_CONFIGURED","EXTERNAL_CONFIGURATION_INVALID","EXTERNAL_REQUEST_INVALID").contains(providerCode)?providerCode:"EXTERNAL_ENGINE_FAILURE";
                    externalDiagnostic(diagnostics,request,code,"外部查询未取得通过来源校验的记录，不能解释为查无记录。",Boolean.TRUE.equals(response.get("queryExecuted")),"SERVICE_AVAILABLE",providerCode);
                }
            }catch(java.io.InterruptedIOException interrupted){Thread.currentThread().interrupt();externalDiagnostic(diagnostics,request,"EXTERNAL_SINGLE_FLIGHT_INTERRUPTED","等待相同外部证据查询时已中断，未重复调用来源服务。",false,"SERVICE_AVAILABLE","");break;}
        }
        return records;
    }
    private static boolean sameExternalStage(String left,String right){return canonicalExternalStage(left).equals(canonicalExternalStage(right));}
    private static String canonicalExternalStage(String stage){return switch(stage){case "EVALUATION"->"BID_EVALUATION";case "PRE_SUBMISSION"->"BID_SUBMISSION";default->stage;};}
    private static void externalDiagnostic(List<Map<String,Object>> diagnostics,Map<String,Object> request,String code,String description,boolean executed,String trigger,String providerCode){
        Map<String,Object> value=diagnostic(code,description,"系统按指定来源及查询范围处理；不要求补交投标材料。",trigger);
        value.put("queryExecuted",executed);value.put("businessComplete",false);value.put("owner","EXTERNAL_WAIT_STAGE".equals(code)?"EXTERNAL":"SYSTEM");
        for(String key:List.of("obligationId","type","evaluationStage","referenceDate","requiredProvider","requirementScopeFingerprint"))value.put(key,text(request,key));
        if(!providerCode.isBlank())value.put("providerErrorCode",providerCode);diagnostics.add(value);
        LOG.info("external_query_outcome type={} obligation={} code={} executed={} providerCode={} scope={}",text(request,"type"),text(request,"obligationId"),code,executed,providerCode,text(request,"requirementScopeFingerprint"));
    }
    private static Map<String,Object> diagnostic(String code,String description,String requiredInput,String resumeTrigger) {return fields("code",code,"description",description,"requiredInput",requiredInput,"resumeTrigger",resumeTrigger);}
    private static String aggregateWorkflow(List<Map<String,Object>> results) {
        if(results.stream().allMatch(r->Boolean.TRUE.equals(r.get("complete")))) return "COMPLETED";
        for(String state:List.of("ERROR","ENGINE_FAILURE","BUDGET_EXHAUSTED","NEEDS_REVIEW","WAIT_STAGE","WAIT_INPUT")) if(results.stream().anyMatch(r->state.equals(r.get("workflowState")))) return state;
        return "NEEDS_REVIEW";
    }
    private void change(TaskRow task,String runId,int percent,String stage) { fenced(task,()->{
        bids.verificationProgress(task,percent,stage);Map<String,Object> current=store.getMap("verification_run",runId);
        current.put("status","RUNNING");current.put("progress",percent);current.put("stage",stage);store.save("verification_run",runId,task.projectId,text(current,"parentRunId"),current);
    }); }
    private void fenced(TaskRow task,Runnable action) {tx.executeWithoutResult(s->{fence(task);action.run();});}
    private void fence(TaskRow task) {
        TaskRow current=store.mapper().lockTask(task.id);
        if(current==null||!"RUNNING".equals(current.status)||!Objects.equals(task.leaseOwner,current.leaseOwner)
            ||(task.leaseOwner!=null&&(current.leaseUntil==null||current.leaseUntil<System.currentTimeMillis()))) throw new TaskLeaseLostException(task.id);
    }
    @Transactional(rollbackFor=IOException.class)
    public Map<String,Object> uploadInput(String project,String runId,String role,String purpose,MultipartFile file) throws IOException {
        Map<String,Object> run=store.getMap("verification_run",runId);
        if(!project.equals(text(run,"projectId"))) throw bad("核验不属于本项目");
        if(!Set.of("QUOTATION","CLARIFICATION","ATTACHMENT","OTHER").contains(purpose)) throw bad("资料用途无效");
        if("CLARIFICATION".equals(purpose)&&!"TENDER".equals(role)) throw bad("澄清文件应选择招标资料");
        TaskRow parse=bids.upload(project,role,file);String requestId=Store.id();
        Map<String,Object> request=fields("id",requestId,"runId",runId,"rootRunId",run.get("rootRunId"),"projectId",project,"purpose",purpose,"role",role,
            "taskId",parse.id,"documentId",text(store.map(parse.inputJson),"documentId"),"status","WAIT_PARSE","createdAt",now());
        store.save("verification_input",requestId,project,runId,request);
        Map<String,Object> response=bids.publicTask(parse);response.put("inputRequestId",requestId);return response;
    }
    @Scheduled(fixedDelayString="${bidcheck.verification.input-poll-ms:2000}")
    public void resumeInputs() {
        for(Map<String,Object> input:store.maps("verification_input",null,null)) {
            if(!"WAIT_PARSE".equals(input.get("status"))) continue;
            try {
                TaskRow parse=bids.task(text(input,"taskId"));
                if("FAILED".equals(parse.status)) {input.put("status","PARSE_FAILED");store.save("verification_input",text(input,"id"),text(input,"projectId"),text(input,"runId"),input);continue;}
                if(!"SUCCEEDED".equals(parse.status)) continue;
                tx.executeWithoutResult(s->{
                    String project=text(input,"projectId");store.lock("project",project);
                    Map<String,Object> current=store.getMap("verification_input",text(input,"id"));if(!"WAIT_PARSE".equals(current.get("status"))) return;
                    Map<String,Object> parent=latestRevision(project,text(input,"rootRunId"));
                    if(!Set.of("COMPLETED","FAILED").contains(text(parent,"status"))) return;
                    List<String> ids=new ArrayList<>(strings(parent.get("documentIds")));String docId=text(current,"documentId");if(!ids.contains(docId)) ids.add(docId);
                    Map<String,Object> next=startLocked(project,fields("documentIds",ids),parent);
                    current.put("status","RESUMED");current.put("resumedRunId",next.get("id"));current.put("finishedAt",now());
                    store.save("verification_input",text(current,"id"),project,text(current,"runId"),current);
                });
            } catch(ResponseStatusException invalid) {
                input.put("status","INPUT_REJECTED");input.put("error",invalid.getReason());store.save("verification_input",text(input,"id"),text(input,"projectId"),text(input,"runId"),input);
            } catch(RuntimeException transientFailure) { /* DB outages leave durable WAIT_PARSE for the next scheduled delivery. */ }
        }
    }
    private Map<String,Object> latestRevision(String project,String root) {
        return list(project).stream().filter(r->root.equals(text(r,"rootRunId"))).max(Comparator.comparingInt(r->number(r.get("revision"),1))).orElseThrow(()->bad("原核验版本不存在"));
    }
    public Map<String,Object> external(String project,Map<String,Object> body) {
        String runId=required(body,"runId",64),kind=required(body,"kind",60),subject=required(body,"subject",200),source=required(body,"source",500);
        Map<String,Object> run=store.getMap("verification_run",runId);if(!project.equals(text(run,"projectId"))) throw bad("核验不属于本项目");
        if(!EXTERNAL_KINDS.contains(kind)) throw bad("外部记录类型无效");
        if(body.containsKey("result")||body.containsKey("verified")||body.containsKey("businessDecision")) throw bad("不能直接提交通过结论，请提供可追溯的原始记录");
        String docId=required(body,"documentId",64),quote=required(body,"quote",4000);Document doc=bids.document(docId);
        if(!project.equals(doc.projectId())) throw bad("外部记录来源不属于本项目");
        int page=number(body.get("page"),0);Page location=doc.pages().stream().filter(p->p.number()==page).findFirst().orElseThrow(()->bad("来源位置无效"));
        if(location.text()==null||!location.text().contains(quote)) throw bad("引用内容没有出现在指定原始记录中");
        String queried=required(body,"queriedAt",40),effective=required(body,"effectiveAt",40);
        try {Instant.parse(queried);LocalDate.parse(effective);} catch(Exception e) {throw bad("查询时间使用ISO时间，适用日期使用YYYY-MM-DD");}
        String id=Store.id();Map<String,Object> record=fields("id",id,"projectId",project,"type",kind,"kind",kind,"subject",subject,"source",source,
            "issuer",text(body,"issuer"),"queriedAt",queried,"effectiveAt",effective,"coverage",text(body,"coverage"),"documentId",docId,"page",page,
            "quote",quote,"rawSnapshotHash",doc.sha256(),"provenanceStatus","PROVIDED_NOT_AUTHENTICATED","evaluationStage",run.get("evaluationStage"),"createdAt",now());
        return tx.execute(s->{store.lock("project",project);Map<String,Object> parent=latestRevision(project,text(run,"rootRunId"));
            if(!Set.of("COMPLETED","FAILED").contains(text(parent,"status"))) throw bad("当前核验仍在运行，请完成后导入记录");
            store.save("verification_external",id,project,docId,record);List<String> ids=new ArrayList<>(strings(parent.get("documentIds")));if(!ids.contains(docId)) ids.add(docId);
            Map<String,Object> revision=startLocked(project,fields("documentIds",ids),parent);return fields("record",record,"run",revision);
        });
    }
    public Map<String,Object> layout(String documentId) throws IOException {
        Document doc=bids.document(documentId);String id=stableId("layout:"+doc.id()+":"+doc.sha256()+":1");Stored cached=store.mapper().get(id);
        if(cached!=null) return store.map(cached.body);Map<String,Object> result=layouts.layout(doc,bids.path(doc.id()));store.save("verification_layout",id,doc.projectId(),doc.id(),result);return result;
    }
    public static Map<String,Object> summary(List<Map<String,Object>> results,int total) {
        Map<String,Long> statuses=new LinkedHashMap<>();STATUSES.stream().sorted().forEach(s->statuses.put(s,0L));
        int automatic=0,quality=0,qualityComplete=0,waiting=0,errors=0;
        for(Map<String,Object> r:results) {
            String status=text(r,"businessDecision");statuses.merge(status,1L,Long::sum);
            boolean pre="SEMANTIC_PREASSESSMENT".equals(r.get("decisionBasis"));if(pre) {quality++;if(Boolean.TRUE.equals(r.get("complete"))) qualityComplete++;}
            else if(Boolean.TRUE.equals(r.get("complete"))) automatic++;
            if(!Boolean.TRUE.equals(r.get("complete"))) waiting++;if(Set.of("ERROR","ENGINE_FAILURE").contains(text(r,"workflowState"))) errors++;
        }
        return fields("total",total,"processed",results.size(),"automaticComplete",automatic,"qualityCount",quality,"qualityComplete",qualityComplete,
            "waiting",waiting,"errors",errors,"statuses",statuses,"objectiveCount",total-quality);
    }
    private static String required(Map<String,Object> body,String key,int length) {String v=text(body,key);if(v.isBlank()||v.length()>length) throw bad("字段 "+key+" 不能为空或长度超限");return v;}
    private static String value(Map<String,Object> body,Map<String,Object> fallback,String key) {return body.containsKey(key)?text(body,key):fallback==null?"":text(fallback,key);}
    public static int number(Object value,int fallback) {try {return value instanceof Number n?n.intValue():Integer.parseInt(Objects.toString(value,""));}catch(Exception e){return fallback;}}
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value) {return value instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();}
    public static List<Map<String,Object>> maps(Object value) {return value instanceof List<?> l?l.stream().filter(v->v instanceof Map<?,?>).map(VerificationService::map).toList():List.of();}
    public static List<String> strings(Object value) {if(!(value instanceof List<?> l)) return List.of();return l.stream().map(v->Objects.toString(v,"")).toList();}
}
