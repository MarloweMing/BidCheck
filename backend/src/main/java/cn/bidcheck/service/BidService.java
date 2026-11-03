package cn.bidcheck.service;
import cn.bidcheck.compat.HexFormat;

import cn.bidcheck.document.DocumentLimits;
import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.document.FileParser;
import cn.bidcheck.document.ExtractionPlan;
import cn.bidcheck.document.ProfileExtractor;
import cn.bidcheck.document.PersonCredentialFacts;
import cn.bidcheck.engine.*;
import cn.bidcheck.consistency.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.semantic.SemanticChecker;
import cn.bidcheck.semantic.SemanticAssessment;
import cn.bidcheck.storage.ArtifactStorage;
import cn.bidcheck.ocr.OcrClient;
import cn.bidcheck.ocr.PdfOcrClient;
import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BidService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(BidService.class);
    public static final String RULE_VERSION="deterministic-1.0-requirements-aligned";
    public static final int MAX_DOCUMENTS_PER_PROJECT=100;
    public static final List<String> CHECK_CATEGORIES=cn.bidcheck.compat.Java8Api.listOf("形式检查", "资格检查", "响应检查", "全文信息检查", "效期检查");
    public static final Set<String> STATUSES=cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "WARNING", "UNDETERMINED", "NOT_APPLICABLE");
    public final Store store;
    private final ArtifactStorage storage;
    private final TransactionTemplate tx;
    private final SemanticChecker semantic;
    private final CheckpointExecutor checkpointExecutor;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private FocusedCheckEvaluator focusedChecks;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private FocusedCheckContext focusedContext;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.verification.agent.AgentInvestigator reviewInvestigator;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.checktemplate.LegacyTemplateBridge templateChecks;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.checktemplate.TemplateEvidenceAssembler templateEvidence;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.checktemplate.TemplateExternalEvidenceService templateExternalEvidence;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.checktemplate.CheckSpecificationStoreService checkSpecifications;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.checktemplate.incremental.TemplateDependencyGraphService templateDependencyGraphs;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.verification.graph.FactLedgerEvidenceGraphBridge evidenceGraphs;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.checktemplate.mapping.RequirementMappingStoreService requirementMappings;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<cn.bidcheck.verification.VerificationService> verification;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.beans.factory.ObjectProvider<InferenceConfirmationService> inferenceConfirmations;
    private final FileParser parser;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.bidcheck.verification.facts.FactLedgerService factLedger;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.bidcheck.verification.source.VisualVerificationService processingVisual;
    @Value("${bidcheck.processing.max-visual-calls:128}")
    private int processingMaxVisualCalls=128;
    @Value("${bidcheck.processing.full-visual-hard-limit:2048}")
    private int processingFullVisualHardLimit=2048;
    @Value("${bidcheck.processing.automatic-visual-enabled:false}")
    private boolean automaticVisualProcessingEnabled;
    @Value("${bidcheck.check-mode:RULES_RETRIEVAL}")
    private String configuredCheckMode="RULES_RETRIEVAL";
    @Value("${bidcheck.template-route-enabled:true}")
    private boolean templateRouteEnabled=true;
    public String checkMode() {
        if(!cn.bidcheck.compat.Java8Api.setOf("RULES_RETRIEVAL", "FULL_MODEL").contains(configuredCheckMode)) throw new IllegalStateException("Unknown check mode");
        return configuredCheckMode;
    }
    /** Compatibility field: -1 means attempts are recorded without a run-wide cap. */
    public int onDemandAttemptBudget() {return -1;}
    public String onDemandPolicyVersion() {return cn.bidcheck.semantic.OnDemandPolicy.VERSION;}
    public String focusedCheckIdentity() {return focusedChecks==null?"UNAVAILABLE":hash(focusedChecks.identity()+FocusedCheckContext.VERSION+AgentBusinessGroupReuse.VERSION);}
    public boolean agentReviewAvailable(){return "RULES_RETRIEVAL".equals(checkMode())&&focusedChecks!=null&&focusedContext!=null&&reviewInvestigator!=null&&reviewInvestigator.enabled();}
    private void requireReviewMode(String mode){if(CheckReviewMode.AGENT.equals(mode)&&!agentReviewAvailable())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Agent 主审尚未配置完成：请启用 SDK、模型服务和主审执行器。本请求未降级为标准检查。");}
    public boolean templateRouteEnabled(){return templateRouteEnabled&&templateChecks!=null&&templateEvidence!=null;}
    /**
     * The evaluator identity is deliberately independent from the rollout switch. A run freezes the
     * switch separately, so disabling templates affects newly created runs without invalidating work
     * that was already queued with the template route enabled.
     */
    public String templateCheckIdentity() {return templateChecks==null||templateEvidence==null?"UNAVAILABLE":hash(templateChecks.identity()+"|"+cn.bidcheck.checktemplate.TemplateEvidenceAssembler.VERSION
        +"|external="+(templateExternalEvidence==null?"UNAVAILABLE":templateExternalEvidence.identity()));}

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ConsistencyModelScanner consistencyScanner;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ConsistencyDoubtReviewer consistencyDoubtReviewer;
    private final OcrClient ocr;
    private final int ocrMaxImages;
    private final int ocrConcurrency;
    private final PdfOcrClient pdfOcr;
    private final int pdfMaxPages;
    private final long pdfBytesBudget, pdfBatchBytes;
    private final CheckpointExtractor extractor=new CheckpointExtractor();
    private final FactExtractor factExtractor=new FactExtractor();
    private final CheckEngine engine=new CheckEngine();
    private final java.util.concurrent.Semaphore previewCapacity=new java.util.concurrent.Semaphore(2);
    private final Object[] projectLocks=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    private Object projectLock(String id) { return projectLocks[Math.floorMod(id.hashCode(),projectLocks.length)]; }
    public BidService(Store store,PlatformTransactionManager manager,SemanticChecker semantic,ArtifactStorage storage,OcrClient ocr) {
        this(store,manager,semantic,storage,ocr,100,2);
    }
    public BidService(Store store,PlatformTransactionManager manager,SemanticChecker semantic,ArtifactStorage storage,OcrClient ocr,
                      @Value("${bidcheck.ocr.max-images:100}") int maxImages,
                      @Value("${bidcheck.ocr.concurrency:4}") int concurrency) {
        this(store,manager,semantic,storage,ocr,maxImages,concurrency,null);
    }
    public BidService(Store store,PlatformTransactionManager manager,SemanticChecker semantic,ArtifactStorage storage,OcrClient ocr,
                      @Value("${bidcheck.ocr.max-images:100}") int maxImages,
                      @Value("${bidcheck.ocr.concurrency:4}") int concurrency,CheckpointExecutor checkpointExecutor) {
        this(store,manager,semantic,storage,ocr,maxImages,concurrency,checkpointExecutor,PdfOcrClient.disabled(),maxImages,67108864L,16777216L);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public BidService(Store store,PlatformTransactionManager manager,SemanticChecker semantic,ArtifactStorage storage,OcrClient ocr,
                      @Value("${bidcheck.ocr.max-images:100}") int maxImages,
                      @Value("${bidcheck.ocr.concurrency:4}") int concurrency,CheckpointExecutor checkpointExecutor,PdfOcrClient pdfOcr,
                      @Value("${bidcheck.ocr.pdf.max-pages:1000}") int pdfMaxPages,
                      @Value("${bidcheck.ocr.pdf.memory-bytes:67108864}") long pdfBytesBudget,
                      @Value("${bidcheck.ocr.pdf.max-batch-bytes:16777216}") long pdfBatchBytes) {
        this.semantic=semantic;
        this.checkpointExecutor=checkpointExecutor;
        this.ocr=ocr; this.pdfOcr=pdfOcr; this.pdfMaxPages=pdfMaxPages; this.pdfBytesBudget=pdfBytesBudget; this.pdfBatchBytes=pdfBatchBytes;
        this.parser=new FileParser(ocr,pdfOcr,maxImages,concurrency,pdfMaxPages,pdfBytesBudget,pdfBatchBytes); this.ocrMaxImages=maxImages; this.ocrConcurrency=concurrency;
        this.store=store; this.tx=new TransactionTemplate(manager); this.storage=storage;
    }
    public static String now() { return Instant.now().toString(); }
    public boolean semanticEnabled() { return semantic.enabled(); }
    public String semanticModel() { return semantic.enabled()?semantic.modelName():""; }
    public String storageMode() { return storage.mode(); }
    public boolean ocrEnabled() { return ocr.enabled()||pdfOcr.enabled(); }
    public boolean ocrLayoutEnabled() { return ocr instanceof cn.bidcheck.ocr.StructuredOcrClient && ((cn.bidcheck.ocr.StructuredOcrClient) (ocr)).layoutEnabled(); }
    public String ocrLayoutModel() { return ocr instanceof cn.bidcheck.ocr.StructuredOcrClient ? ((cn.bidcheck.ocr.StructuredOcrClient) (ocr)).layoutModelName() : ""; }
    public String ocrModel() { return pdfOcr.enabled()?pdfOcr.modelName()+"（PDF）；单页补识别："+(ocr.enabled()?ocr.modelName():"未启用"):ocr.enabled()?ocr.modelName():""; }
    public boolean pdfOcrEnabled() { return pdfOcr.enabled(); }
    public String pdfOcrModel() { return pdfOcr.enabled()?pdfOcr.modelName():""; }
    public int pdfOcrBatchPages() { return pdfOcr.enabled()?pdfOcr.batchPages():0; }
    public int pdfOcrConcurrency() { return pdfOcr.enabled()?pdfOcr.concurrency():0; }
    public int pdfOcrMaxPages() { return pdfMaxPages; }
    private String activeOcrIdentity() { return pdfOcr.enabled()?hash("PDF:"+pdfOcr.identity()+"|SINGLE:"+ocr.identity()):ocr.identity(); }
    public int ocrMaxImages() { return ocrMaxImages; }
    public int ocrConcurrency() { return ocrConcurrency; }
    public int ocrBatchMaxPages() { cn.bidcheck.ocr.OcrBatchPolicy policy=ocr.batchPolicy();return policy!=null&&policy.enabled()?policy.maxPages():1; }
    public int checkConcurrency() { return checkpointExecutor==null?1:checkpointExecutor.parallelism(); }
    public boolean automaticVisualProcessingEnabled() { return automaticVisualProcessingEnabled; }
    public String documentProcessingMode() { return automaticVisualProcessingEnabled?"STRUCTURED_VISUAL":"TEXT_ONLY"; }
    public String ocrPlanningMode() { return automaticVisualProcessingEnabled?"FULL_DOCUMENT_FACTS":"FULL_DOCUMENT_TEXT"; }
    public String ocrPreparationIdentity() { return hash(RULE_VERSION+"|"+FileParser.POLICY_VERSION+"|"+OcrUnitJournal.VERSION+"|"+ExtractionPlan.VERSION+"|"+processingIdentity()+"|"+activeOcrIdentity()+"|"+ocrMaxImages+"|"+ocrConcurrency+"|"+ExtractionPlan.DEFAULT_SELECTED_IMAGES
            +(pdfOcr.enabled()?"|PDF:"+pdfOcr.identity()+"|"+pdfOcr.batchPages()+"|"+pdfOcr.concurrency()+"|"+pdfMaxPages+"|"+pdfBytesBudget+"|"+pdfBatchBytes:"")); }
    public String processingIdentity(){return hash(DocumentProcessingSession.VERSION+"|"+PersonCredentialFacts.VERSION+"|"+PersonCredentialConsistency.PREPARATION_VERSION+"|"+IncrementalCheckEngine.VERSION+"|"+RULE_VERSION+"|"+FileParser.POLICY_VERSION+"|"+activeOcrIdentity()
        +(automaticVisualProcessingEnabled?"|"+(processingVisual==null?"DISABLED":processingVisual.identity())+"|"+processingMaxVisualCalls+"|"+processingFullVisualHardLimit:"|TEXT_ONLY_V1"));}
    private ConsistencyModelScanner consistencyScanner() {
        return consistencyScanner==null?new ConsistencyModelScanner(cn.bidcheck.verification.engine.ModelGateway.disabled()):consistencyScanner;
    }
    private String consistencyIdentity() { return hash(CheckEngine.VERSION+"|"+ConsistencyEngine.VERSION+"|"+PersonCredentialFacts.CONSISTENCY_FILTER_VERSION+"|"+ExpandedConsistencyEngine.VERSION+"|"+PersonCredentialFacts.VERSION+"|"+PersonCredentialConsistency.VERSION+"|"+ConsistencyCatalog.VERSION+"|"+ConsistencyPublication.VERSION+"|"+ConsistencyDoubtReviewer.VERSION+"|"+ConsistencyDerivedConclusion.identity()+"|"+ConsistencyDoubtReviewer.MAX_GROUPS+"|"+ConsistencyDoubtReviewer.MAX_MODEL_ATTEMPTS+"|"+ConsistencyDoubtReviewer.ADMISSION_MILLIS+"|"+checkMode()+"|"+onDemandPolicyVersion()+"|"+onDemandAttemptBudget()+"|"+("FULL_MODEL".equals(checkMode())?consistencyScanner().identity():"MODEL_SCAN_NOT_REQUESTED")); }
    private synchronized cn.bidcheck.verification.facts.FactLedgerService factLedger() {
        if(factLedger==null)factLedger=new cn.bidcheck.verification.facts.FactLedgerService(store);
        return factLedger;
    }
    private List<Checkpoint> confirmedChecks(String project) {
        return cn.bidcheck.compat.Java8Api.toList(activeCheckpoints(project,store.getMap("project",project)).stream().filter(c->c.enabled()&&c.confirmed()));
    }
    private static ExtractionPlan initialPlan(String role,String name,List<Checkpoint> checks) {
        // A blank native text layer cannot prove a scan contains no personnel/credential evidence.
        // Full-document facts require every image position to be considered, within parser budgets.
        return ExtractionPlan.full();
    }
    /** Caller holds the project row lock; every producer includes unfinished version reservations. */
    private int nextDocumentVersion(String project,String role) {
        int completed=store.documentSummaries(project).stream().filter(d->role.equals(d.get("role"))).mapToInt(d->((Number)d.get("version")).intValue()).max().orElse(0);
        int uploaded=store.maps("upload",project,null).stream().filter(d->role.equals(d.get("role"))).mapToInt(d->((Number)d.get("version")).intValue()).max().orElse(0);
        int reserved=store.maps("ocr_preparation",project,null).stream().filter(d->role.equals(d.get("role"))).mapToInt(d->((Number)d.get("version")).intValue()).max().orElse(0);
        return Math.max(completed,Math.max(uploaded,reserved))+1;
    }
    public static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    public static String text(Map<String,?> map,String key) { Object v=map.get(key); return v==null?"":v.toString().trim(); }
    public static Map<String,Object> fields(Object... pairs) {
        Map<String,Object> m=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2) m.put((String)pairs[i],pairs[i+1]); return m;
    }
    public Map<String,Object> createProject(Map<String,Object> body) {
        String name=text(body,"name"), code=body.containsKey("projectNumber")?text(body,"projectNumber"):text(body,"code");
        if(cn.bidcheck.compat.Java8Api.isBlank(name)||name.length()>160||code.length()>100) throw bad("请填写项目名称（不超过160字），编号不超过100字");
        List<String> manual=new ArrayList<>();if(!cn.bidcheck.compat.Java8Api.isBlank(code))manual.add("projectNumber");if(!cn.bidcheck.compat.Java8Api.isBlank(text(body,"openingTime")))manual.add("openingTime");
        String id=Store.id(); Map<String,Object> p=fields("id",id,"name",name,"code",code,"projectNumber",code,"openingTime",ProjectInformation.openingTime(text(body,"openingTime")),"manualFields",manual,"fieldSources",cn.bidcheck.compat.Java8Api.mapOf(),"createdAt",now());
        store.save("project",id,id,"",p); return p;
    }
    public Map<String,Object> updateProject(String id,Map<String,Object> body) {
        return tx.execute(s->{
            Map<String,Object> p=store.map(store.lock("project",id).body);
            p.putIfAbsent("projectNumber",text(p,"code"));
            Map<String,Object> before=new LinkedHashMap<>(p);
            Set<String> manual=ProjectInformation.manualFields(p);
            for(String field:ProjectInformation.FIELDS) {
                if(!body.containsKey(field)&&!(field.equals("projectNumber")&&body.containsKey("code"))) continue;
                String value=text(body,field.equals("projectNumber")&&!body.containsKey(field)?"code":field);
                if(field.equals("name")&&(cn.bidcheck.compat.Java8Api.isBlank(value)||value.length()>160)) throw bad("项目名称不能为空或超过160字");
                if(field.equals("projectNumber")&&value.length()>100) throw bad("项目编号不能超过100字");
                if(field.equals("openingTime")) value=ProjectInformation.openingTime(value);
                p.put(field,value);manual.add(field);
            }
            p.put("code",text(p,"projectNumber"));p.put("manualFields",cn.bidcheck.compat.Java8Api.listCopyOf(manual));p.put("updatedAt",now());
            store.save("project",id,id,"",p);
            String audit=Store.id();store.save("audit",audit,id,id,fields("id",audit,"action","PROJECT_UPDATED","before",before,"after",p,"createdAt",now()));
            return p;
        });
    }
    public List<Map<String,Object>> projects() {
        return cn.bidcheck.compat.Java8Api.toList(store.maps("project",null,null).stream().map(p->{
            String id=text(p,"id"); Stored run=store.mapper().latest("run",id,null);
            p.put("documentCount",store.mapper().count("document",id,null));
            p.put("latestStatus",run==null?"NEW":store.map(run.body).get("status")); return p;
        }));
    }
    public Map<String,Object> detail(String project) {
        return detail(project,true);
    }
    public Map<String,Object> detail(String project,boolean includeContent) {
        Map<String,Object> p=store.getMap("project",project);
        List<Checkpoint> checks=activeCheckpoints(project,p);
        p.put("checkpointRulesOutdated",checkpointRulesOutdated(p,checks));
        return fields("project",p,"documents",includeContent?store.list("document",project,null,Document.class):store.documentSummaries(project),
            "checkpoints",checks,"runs",store.maps("run",project,null),"tasks",cn.bidcheck.compat.Java8Api.toList(store.mapper().projectTasks(project).stream().map(this::publicTask)),
            "requirementMapping",requirementMappingSummary(project,p));
    }
    private Map<String,Object> requirementMappingSummary(String project,Map<String,Object> value) {
        if(requirementMappings==null||cn.bidcheck.compat.Java8Api.isBlank(text(value,"activeTenderId")))return fields("status","NOT_GENERATED","projectId",project,"affectsBusinessConclusion",false);
        return requirementMappings.currentSummary(project);
    }
    private static boolean checkpointRulesOutdated(Map<String,Object> project,List<Checkpoint> checks) {
        String tender=text(project,"activeTenderId");
        if(project.get("checkpointRuleVersions") instanceof Map<?,?>&&RULE_VERSION.equals(((Map<?,?>) (project.get("checkpointRuleVersions"))).get(tender)))return false;
        return !cn.bidcheck.compat.Java8Api.isBlank(text(project,"requestedTenderId"))||!cn.bidcheck.compat.Java8Api.isBlank(text(project,"checkpointRuleVersion"))||checks.stream().anyMatch(c->c.source()!=null);
    }
    public void requireCurrentCheckpointRules(Map<String,Object> project,List<Checkpoint> checks) {
        if(checkpointRulesOutdated(project,checks))throw bad("提取规则已更新，请重新提取并确认检查项；已保存的人工调整和历史报告会保留");
    }
    public void requireCurrentCheckpointRules(Map<String,Object> project,List<Checkpoint> checks,String tenderId) {
        Map<String,Object> selected=new LinkedHashMap<>(project);selected.put("activeTenderId",tenderId);
        requireCurrentCheckpointRules(selected,checks);
    }
    public Map<String,Object> publicTask(TaskRow t) {
        String documentId=text(store.map(t.inputJson),"documentId");
        Map<String,Object> result=fields("id",t.id,"projectId",t.projectId,"documentId",documentId,"type",t.taskType,"status",t.status,"stage",t.stage,"progress",t.progress,"attempt",t.attempt,"error",t.error,"createdAt",t.createdAt,"updatedAt",t.updatedAt);
        if("PARSE".equals(t.taskType)&&!cn.bidcheck.compat.Java8Api.isBlank(documentId)) {
            Stored row=store.mapper().get("upload:"+documentId);
            if(row!=null) {Map<String,Object> upload=store.map(row.body);result.put("fileName",text(upload,"name"));result.put("role",text(upload,"role"));}
        }
        Stored ocrProgress=store.mapper().get("ocr-task-progress:"+t.id);
        if(ocrProgress!=null&&"ocr_task_progress".equals(ocrProgress.kind)&&t.projectId.equals(ocrProgress.projectId))result.put("ocrProgress",store.map(ocrProgress.body));
        Stored processing=store.mapper().get("processing-task:"+t.id);
        if(processing!=null&&"processing_task_summary".equals(processing.kind)&&t.projectId.equals(processing.projectId))result.put("processingProgress",store.map(processing.body));
        return result;
    }
    private List<Checkpoint> activeCheckpoints(String project,Map<String,Object> p) {
        String tender=text(p,"activeTenderId");
        return store.list("checkpoint",project,cn.bidcheck.compat.Java8Api.isBlank(tender)?null:tender,Checkpoint.class);
    }
    public TaskRow task(String id) {
        TaskRow task=store.mapper().task(id); if(task==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"任务不存在"); return task;
    }
    private TaskRow newTask(String project,String type,Map<String,Object> input) {
        TaskRow t=new TaskRow(); t.id=Store.id(); t.projectId=project; t.taskType=type; t.inputJson=store.encode(input);
        t.status="PENDING"; t.stage="等待处理"; t.progress=0; t.attempt=0; t.createdAt=now(); t.updatedAt=t.createdAt;
        store.mapper().insertTask(t); return t;
    }
    /** Must be called inside the caller's manifest/snapshot transaction. */
    public TaskRow queueVerification(String project,String runId) { return newTask(project,"VERIFY",fields("runId",runId)); }
    public void verificationProgress(TaskRow task,int percent,String stage) { progress(task,percent,stage); }
    public void verificationFinished(TaskRow task,String stage) { finish(task,stage); }
    public TaskRow migrateProjectFacts(String project) {
        return tx.execute(s->{store.lock("project",project);
            for(TaskRow existing:store.mapper().projectTasks(project))if("FACT_MIGRATE".equals(existing.taskType)&&cn.bidcheck.compat.Java8Api.setOf("PENDING", "RUNNING").contains(existing.status))return existing;
            return newTask(project,"FACT_MIGRATE",fields("migrationVersion","RELATIONAL_V3"));
        });
    }
    public List<Map<String,Object>> factMigrations(String project) {store.require("project",project);return store.maps("fact_migration",project,null);}
    /** Continue structured materials from durable page results; never resend OCR for completed text. */
    public TaskRow continueProcessing(String documentId){
        Document doc=document(documentId);return tx.execute(s->{store.lock("project",doc.projectId());
            for(TaskRow task:store.mapper().projectTasks(doc.projectId()))if("PROCESS".equals(task.taskType)&&cn.bidcheck.compat.Java8Api.setOf("PENDING", "RUNNING").contains(task.status)
                &&documentId.equals(text(store.map(task.inputJson),"documentId")))return task;
            return newTask(doc.projectId(),"PROCESS",fields("documentId",documentId,"processingIdentity",processingIdentity(),"checks",confirmedChecks(doc.projectId())));
        });
    }
    public Map<String,Object> ocrPlan(String documentId) {
        Stored saved=store.mapper().get("ocr-plan:"+documentId);
        return saved==null?cn.bidcheck.compat.Java8Api.mapOf():store.map(saved.body);
    }
    /** Metadata only: safe to poll without loading recognition text or geometry. */
    public Map<String,Object> ocrProgress(String documentId) {
        Stored metadata=store.mapper().get(documentId);
        if(metadata==null||!"document".equals(metadata.kind))metadata=store.mapper().get("upload:"+documentId);
        if(metadata==null)metadata=store.mapper().get("ocr-preparation:"+documentId);
        if(metadata==null||!cn.bidcheck.compat.Java8Api.setOf("document", "upload", "ocr_preparation").contains(metadata.kind))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文档不存在");
        String project=metadata.projectId;
        Stored summary=store.mapper().get("ocr-progress:"+documentId);
        if(summary==null||!"ocr_progress".equals(summary.kind)||!project.equals(summary.projectId))return cn.bidcheck.compat.Java8Api.mapOf("documentId", documentId, "units", cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> result=new LinkedHashMap<>(store.map(summary.body));String scope=text(result,"scope");
        result.put("units",cn.bidcheck.compat.Java8Api.toList(store.maps("ocr_unit_status",project,documentId).stream().filter(unit->scope.equals(text(unit,"scope")))
            .sorted(Comparator.comparing((Map<String,Object> unit)->text(unit,"locationType")).thenComparingInt(unit->((Number)unit.get("position")).intValue()).thenComparingInt(unit->((Number)unit.get("imageIndex")).intValue()))));
        result.put("batches",cn.bidcheck.compat.Java8Api.toList(store.maps("ocr_batch_status",project,documentId).stream().filter(batch->scope.equals(text(batch,"scope")))));
        result.put("batchEvents",cn.bidcheck.compat.Java8Api.toList(store.maps("ocr_batch_event",project,documentId).stream().filter(batch->scope.equals(text(batch,"scope")))));
        return result;
    }
    private OcrUnitJournal ocrJournal(TaskRow task,String documentId,int version,String sourceHash,ExtractionPlan plan,String baseId) {
        return new OcrUnitJournal(store,tx,()->progress(task,task.progress,task.stage),task.projectId,documentId,version,sourceHash,
            plan.sourceBoundHash(sourceHash),hash(FileParser.POLICY_VERSION+"|"+OcrUnitJournal.VERSION+"|"+activeOcrIdentity()),task.id,baseId);
    }
    private DocumentProcessingSession processingSession(TaskRow task,Document metadata,ExtractionPlan plan,String base,List<Checkpoint> checks){
        List<Checkpoint> scoped="BID".equals(metadata.role())?checks:cn.bidcheck.compat.Java8Api.listOf();
        return new DocumentProcessingSession(store,tx,()->progress(task,task.progress,task.stage),ocrJournal(task,metadata.id(),metadata.version(),metadata.sha256(),plan,base),
            factLedger(),metadata,task.id,processingIdentity(),scoped,automaticVisualProcessingEnabled);
    }
    /** Available while the upload is still parsing; does not publish an incomplete document as a completed version. */
    public Map<String,Object> processing(String documentId){
        Stored meta=processingMetadata(documentId);Stored saved=store.mapper().get(DocumentProcessingSession.summaryId(documentId));
        if(saved==null||!meta.projectId.equals(saved.projectId))return fields("documentId",documentId,"status","NOT_STARTED","pages",cn.bidcheck.compat.Java8Api.listOf(),"checks",cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> out=new LinkedHashMap<>(store.map(saved.body));String scope=text(out,"scope");
        out.put("configurationCurrent",processingIdentity().equals(text(out,"identity")));
        out.put("pages",cn.bidcheck.compat.Java8Api.toList(store.maps("processing_page",meta.projectId,documentId).stream().filter(p->scope.equals(p.get("scope"))).map(row->{
            Page page=store.decode(store.encode(row.get("page")),Page.class);return fields("page",page.number(),"locationType",page.locationType(),"parser",page.parser(),"warning",page.warning(),"route",row.get("route"));
        }).sorted(Comparator.comparingInt(p->((Number)p.get("page")).intValue()))));
        out.put("checks",cn.bidcheck.compat.Java8Api.toList(store.maps("incremental_check",meta.projectId,documentId).stream().filter(r->scope.equals(r.get("scope"))
            &&(!out.containsKey("checkScope")||Objects.equals(out.get("checkScope"),r.get("checkScope"))))));
        out.put("windows",cn.bidcheck.compat.Java8Api.toList(store.maps("processing_window",meta.projectId,documentId).stream().filter(r->scope.equals(r.get("scope"))).map(r->fields("pages",r.get("pages"),"status",r.get("status"),"relations",r.get("relations"),"factResult",r.get("factResult")))));
        return out;
    }
    public Map<String,Object> processingFacts(String documentId){
        Stored meta=processingMetadata(documentId);Stored saved=store.mapper().get(DocumentProcessingSession.summaryId(documentId));
        if(saved==null||!meta.projectId.equals(saved.projectId))return cn.bidcheck.compat.Java8Api.mapOf("documentId", documentId, "occurrences", cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> summary=store.map(saved.body);String scope=text(summary,"scope");
        List<Page> pages=cn.bidcheck.compat.Java8Api.toList(store.maps("processing_page",meta.projectId,documentId).stream().filter(p->scope.equals(p.get("scope")))
            .map(p->store.decode(store.encode(p.get("page")),Page.class)));
        Document d=new Document(documentId,meta.projectId,text(summary,"role"),text(summary,"documentName"),((Number)summary.get("documentVersion")).intValue(),text(summary,"documentHash"),"",pages,cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> view=new LinkedHashMap<>(factLedger().documentView(d,text(summary,"identity")));
        view.put("configurationCurrent",processingIdentity().equals(text(summary,"identity")));
        view.put("parsingComplete",Boolean.TRUE.equals(summary.get("parsingComplete")));
        return view;
    }
    private Stored processingMetadata(String id){
        Stored row=store.mapper().get(id);if(row==null||!"document".equals(row.kind))row=store.mapper().get("upload:"+id);
        if(row==null)row=store.mapper().get("ocr-preparation:"+id);
        if(row==null||!cn.bidcheck.compat.Java8Api.setOf("document", "upload", "ocr_preparation").contains(row.kind))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文档不存在");return row;
    }
    private List<Checkpoint> readCheckpoints(Object value) {
        if(value==null)return cn.bidcheck.compat.Java8Api.listOf();
        return cn.bidcheck.compat.Java8Api.listOf(store.decode(store.encode(value),Checkpoint[].class));
    }
    private void saveOcrPlan(Document document,ExtractionPlan plan,Map<String,Object> report,String baseId) {
        Map<String,Object> saved=new LinkedHashMap<>(report);saved.put("documentId",document.id());saved.put("documentVersion",document.version());
        saved.put("projectId",document.projectId());saved.put("documentHash",document.sha256());saved.put("requestedPlanHash",plan.hash());
        saved.put("ocrIdentity",activeOcrIdentity());saved.put("baseDocumentId",baseId);saved.put("policyVersion",RULE_VERSION);
        saved.put("preparationIdentity",ocrPreparationIdentity());
        store.save("ocr_plan","ocr-plan:"+document.id(),document.projectId(),document.id(),saved);
    }
    /** Materialize a new immutable evidence version before any run result or fact snapshot is written. */
    public Document preparePlannedDocument(TaskRow task,Document source,List<Checkpoint> frozenChecks) throws IOException {
        if(!task.projectId.equals(source.projectId()))throw bad("识别资料不属于当前项目");
        boolean deferred=source.pages().stream().anyMatch(p->p.warning()!=null&&p.warning().contains("[OCR_DEFERRED]"))||source.warnings().stream().anyMatch(w->w.contains("[OCR_DEFERRED]"));
        if(!deferred||!source.name().toLowerCase(Locale.ROOT).matches(".*\\.(pdf|docx)$"))return source;
        ExtractionPlan plan=initialPlan(source.role(),source.name(),frozenChecks);Map<String,Object> prior=ocrPlan(source.id());
        if(plan.hash().equals(text(prior,"requestedPlanHash"))&&ocrPreparationIdentity().equals(text(prior,"preparationIdentity")))return source;
        String runId=text(store.map(task.inputJson),"runId");
        String id=stableId("planned-ocr:"+task.projectId+":"+runId+":"+source.id()+":"+plan.hash()+":"+activeOcrIdentity());
        Stored published=store.mapper().get(id);
        if(published!=null&&"document".equals(published.kind))return document(id);
        Map<String,Object> reservation=tx.execute(s->{
            progress(task,2,"补齐全文未识别位置");store.lock("project",task.projectId);
            Stored previous=store.mapper().get("ocr-preparation:"+id);if(previous!=null)return store.map(previous.body);
            Map<String,Object> value=fields("documentId",id,"projectId",task.projectId,"role",source.role(),"version",nextDocumentVersion(task.projectId,source.role()),"createdAt",now(),"baseDocumentId",source.id(),"planHash",plan.hash(),"status","PENDING");
            store.save("ocr_preparation","ocr-preparation:"+id,task.projectId,runId,value);return value;
        });
        Document preparing=new Document(id,source.projectId(),source.role(),source.name(),((Number)reservation.get("version")).intValue(),source.sha256(),text(reservation,"createdAt"),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.service.DocumentProcessingSession processing=processingSession(task,preparing,plan,source.id(),frozenChecks);
        long[] last={0};cn.bidcheck.document.ExtractionResult extraction=parser.parsePlanned(path(source.id()),source.name(),(percent,stage)->{
            long tick=System.nanoTime();if(percent>=100||tick-last[0]>=1_000_000_000L){progress(task,2,"全文识别："+stage);last[0]=tick;}
        },plan,processing);
        // Keep file identity durable and immutable for download, preview and subsequent revisions.
        try{storage.materialize(id,source.sha256());}
        catch(IOException missing){Path staged=storage.stagePath(id);Files.copy(path(source.id()),staged,StandardCopyOption.REPLACE_EXISTING);storage.commit(id);storage.materialize(id,source.sha256());}
        Document prepared=new Document(id,source.projectId(),source.role(),source.name(),((Number)reservation.get("version")).intValue(),source.sha256(),text(reservation,"createdAt"),extraction.document().pages(),extraction.document().warnings());
        processing.finish(prepared,path(source.id()),processingVisual,Math.max(1,processingMaxVisualCalls),true,processingFullVisualHardLimit);
        tx.executeWithoutResult(s->{
            progress(task,3,"检查计划所需识别已结束");store.lock("project",task.projectId);
            Stored existing=store.mapper().get(id);if(existing==null){store.save("document",id,task.projectId,"",prepared);saveOcrPlan(prepared,plan,extraction.report(),source.id());replaceFacts(prepared,factExtractor.extract(prepared));}
            Map<String,Object> value=new LinkedHashMap<>(reservation);value.put("status","COMPLETED");store.save("ocr_preparation","ocr-preparation:"+id,task.projectId,runId,value);
        });
        return document(id);
    }
    public TaskRow upload(String project,String role,MultipartFile file) throws IOException {
        if(!cn.bidcheck.compat.Java8Api.setOf("TENDER", "BID").contains(role)) throw bad("文件类型必须为招标文件或投标文件");
        synchronized(projectLock(project)) { return uploadLocked(project,role,file); }
    }
    /** Separate proof attachment bound to one frozen check and its explicit dependencies. */
    public Map<String,Object> uploadSupplement(String runId,String checkpointId,String claimId,MultipartFile file,String note) throws IOException {
        Map<String,Object> run=store.getMap("run",runId);String project=text(run,"projectId");
        synchronized(projectLock(project)){
            run=store.getMap("run",runId);
            if(!cn.bidcheck.compat.Java8Api.setOf("COMPLETED", "FAILED").contains(text(run,"status")))throw bad("请待当前检查结束后补充材料");
            if(store.list("snapshot",project,runId,Checkpoint.class).stream().noneMatch(c->c.id().equals(checkpointId)))throw bad("补充材料必须关联当前检查事项");
            if(note==null||cn.bidcheck.compat.Java8Api.isBlank(note)||note.length()>3000)throw bad("请填写补充材料的证明用途");
            if(claimId!=null&&!cn.bidcheck.compat.Java8Api.isBlank(claimId)){
                Map<String,Object> claim=store.getMap("inference_claim",claimId);
                if(!project.equals(text(claim,"projectId"))||!checkpointId.equals(text(claim,"checkpointId"))||!text(run,"documentId").equals(text(claim,"documentId")))throw bad("确认记录与补充事项不一致");
            }
            TaskRow parsed=uploadLocked(project,"SUPPLEMENT",file);String documentId=text(store.map(parsed.inputJson),"documentId");
            Stored documentRow=store.mapper().get(documentId);Map<String,Object> metadata=documentRow!=null&&"document".equals(documentRow.kind)?store.map(documentRow.body):store.getMap("upload","upload:"+documentId);
            String id=stableId("supplement:"+runId+":"+checkpointId+":"+Objects.toString(claimId,"")+":"+documentId);
            if(store.mapper().get(id)==null){Map<String,Object> attachment=fields("attachmentId",id,"documentId",documentId,"taskId",parsed.id,"status","PENDING","checkpointId",checkpointId,"claimId",Objects.toString(claimId,""),"originRunId",runId,"projectId",project,
                "name",metadata.get("name"),"sha256",metadata.get("sha256"),"version",metadata.get("version"),"note",cn.bidcheck.compat.Java8Api.strip(note),"createdAt",now(),"updatedAt",now(),"recomputeStatus","WAITING_PARSE");
                store.save("supplement_attachment",id,project,runId,attachment);
                LOG.info("bidcheck.supplement_attachment runId={} checkpointId={} claimId={} attachmentId={} documentId={} action=UPLOADED taskId={}",runId,checkpointId,claimId,id,documentId,parsed.id);
            }
            if(documentRow!=null&&"document".equals(documentRow.kind))completeSupplements(documentId,project);
            else if("FAILED".equals(parsed.status))updateSupplementParse(documentId,project,"FAILED","已有附件解析失败，请重试解析任务");
            return store.getMap("supplement_attachment",id);
        }
    }
    private void updateSupplementParse(String documentId,String project,String status,String error){
        for(Map<String,Object> old:store.maps("supplement_attachment",project,null))if(documentId.equals(text(old,"documentId"))&&!"READY".equals(text(old,"status"))){Map<String,Object> next=new LinkedHashMap<>(old);next.put("status",status);next.put("updatedAt",now());
            next.put("error",error==null?"":error);if("FAILED".equals(status))next.put("recomputeStatus","PARSE_FAILED");
            store.save("supplement_attachment",text(next,"attachmentId"),project,text(next,"originRunId"),next);
            LOG.info("bidcheck.supplement_attachment runId={} checkpointId={} attachmentId={} documentId={} action={} error={}",next.get("originRunId"),next.get("checkpointId"),next.get("attachmentId"),documentId,status,error==null?"":error);
        }
    }
    private void completeSupplements(String documentId,String project){
        List<Map<String,Object>> attachments=cn.bidcheck.compat.Java8Api.toList(store.maps("supplement_attachment",project,null).stream().filter(v->documentId.equals(text(v,"documentId"))));
        for(Map<String,Object> old:attachments){Map<String,Object> value=new LinkedHashMap<>(old);value.put("status","READY");value.put("error","");value.put("updatedAt",now());store.save("supplement_attachment",text(value,"attachmentId"),project,text(value,"originRunId"),value);}
        for(Map<String,Object> old:attachments){String id=text(old,"attachmentId"),origin=text(old,"originRunId");Map<String,Object> value=store.getMap("supplement_attachment",id);if("QUEUED".equals(text(value,"recomputeStatus"))&&!cn.bidcheck.compat.Java8Api.isBlank(text(value,"recomputeRunId")))continue;
            try{Map<String,Object> next=recoverRun(project,origin);value.put("recomputeStatus","QUEUED");value.put("recomputeRunId",next.get("id"));}
            catch(RuntimeException failure){value.put("recomputeStatus","SAVED_REQUIRES_RECHECK");value.put("error",failure instanceof ResponseStatusException?Objects.toString(((ResponseStatusException) (failure)).getReason(),"重算创建失败"):"补证解析完成，创建重算任务失败，请重新发起补查");}
            value.put("updatedAt",now());store.save("supplement_attachment",id,project,origin,value);
            LOG.info("bidcheck.supplement_attachment runId={} checkpointId={} attachmentId={} documentId={} action=PARSE_COMPLETE recomputeStatus={} recomputeRunId={}",origin,value.get("checkpointId"),id,documentId,value.get("recomputeStatus"),value.get("recomputeRunId"));
        }
    }
    private TaskRow uploadLocked(String project,String role,MultipartFile file) throws IOException {
        store.require("project",project);
        if(!cn.bidcheck.compat.Java8Api.setOf("TENDER", "BID", "SUPPLEMENT").contains(role)) throw bad("文件类型不支持");
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("file").replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","");
        if(name.length()>200) name=name.substring(name.length()-200);
        if(!name.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|docx|txt|md|png|jpg|jpeg)$")) throw bad("支持 PDF、DOCX、TXT、Markdown、PNG 和 JPEG 文件");
        if(file.isEmpty()||file.getSize()>DocumentLimits.MAX_FILE_BYTES) throw bad("文件不能为空，且不能超过"+DocumentLimits.MAX_FILE_MB+"MB");
        String id=Store.id(); Path path=storage.stagePath(id); MessageDigest digest=sha();
        try(InputStream in=new DigestInputStream(file.getInputStream(),digest); OutputStream out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)) {
            long copied=DocumentLimits.copy(in,out,DocumentLimits.MAX_FILE_BYTES);
            if(copied!=file.getSize()) throw new IOException("上传文件长度不一致，请重新上传。");
        }
        catch(IOException e) { storage.discardStaging(id); throw e; }
        String hash=HexFormat.of().formatHex(digest.digest()); String finalName=name;
        List<Checkpoint> planChecks=confirmedChecks(project);
        String planHash=initialPlan(role,finalName,planChecks).hash();
        List<Map<String,Object>> completed=store.documentSummaries(project);
        List<Map<String,Object>> uploads=store.maps("upload",project,null);
        Optional<Map<String,Object>> duplicate=completed.stream().filter(d->{Map<String,Object> receipt=ocrPlan(text(d,"id"));return role.equals(d.get("role"))&&hash.equals(d.get("sha256"))&&planHash.equals(text(receipt,"requestedPlanHash"))&&ocrPreparationIdentity().equals(text(receipt,"preparationIdentity"));}).reduce((a,b)->b);
        Optional<Map<String,Object>> pending=uploads.stream().filter(u->role.equals(u.get("role"))&&hash.equals(u.get("sha256"))&&planHash.equals(text(u,"ocrPlanHash"))&&ocrPreparationIdentity().equals(text(u,"preparationIdentity"))).reduce((a,b)->b);
        if(duplicate.isPresent()||pending.isPresent()) {
            storage.discardStaging(id);
            if(pending.isPresent()&&!cn.bidcheck.compat.Java8Api.isBlank(text(pending.get(),"taskId"))) return task(text(pending.get(),"taskId"));
            return completedDuplicate(project,text(duplicate.orElseThrow(java.util.NoSuchElementException::new),"id"));
        }
        if(uploads.size()>=MAX_DOCUMENTS_PER_PROJECT) {storage.discardStaging(id);throw bad("每个项目最多上传100份文件（含历史版本），请创建新项目后继续");}
        try { storage.commit(id); }
        catch(IOException failure) {
            try { storage.discardStaging(id); } catch(IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        // File bytes are durable before the transaction queues a persistent parse task.
        return tx.execute(s->{
            store.lock("project",project);
            List<Map<String,Object>> current=store.maps("upload",project,null);
            Optional<Map<String,Object>> duplicateNow=current.stream().filter(u->role.equals(u.get("role"))&&hash.equals(u.get("sha256"))&&planHash.equals(text(u,"ocrPlanHash"))&&ocrPreparationIdentity().equals(text(u,"preparationIdentity"))).findFirst();
            if(duplicateNow.isPresent()) return task(text(duplicateNow.get(),"taskId"));
            Map<String,Object> metadata=fields("id",id,"projectId",project,"role",role,"name",finalName,"sha256",hash,
                "version",nextDocumentVersion(project,role),"createdAt",now(),"ocrIdentity",activeOcrIdentity());
            metadata.put("ocrPlanHash",planHash);metadata.put("ocrPlanCheckpoints",planChecks);metadata.put("preparationIdentity",ocrPreparationIdentity());
            TaskRow task=newTask(project,"PARSE",fields("documentId",id));
            metadata.put("taskId",task.id); store.save("upload","upload:"+id,project,"",metadata);
            if("TENDER".equals(role)) {Map<String,Object> p=store.getMap("project",project);p.put("requestedInformationTenderId",id);store.save("project",project,project,"",p);}
            return task;
        });
    }
    /** Re-recognition creates a new immutable document version; existing reports retain their evidence. */
    public TaskRow recognizeAgain(String documentId) throws IOException {
        return recognizeAgain(documentId,true);
    }
    public TaskRow recognizeForChecks(String documentId) throws IOException {
        return recognizeAgain(documentId,false);
    }
    public TaskRow recognizeRemaining(String documentId) throws IOException {
        Document source=document(documentId);
        if(source.warnings().stream().noneMatch(w->w.contains("[OCR未完成]"))&&source.pages().stream().noneMatch(p->p.warning()!=null&&p.warning().contains("[OCR未完成]")))
            throw bad("当前文档没有已登记的未完成识别位置");
        return recognizeAgain(documentId,true);
    }
    private TaskRow recognizeAgain(String documentId,boolean full) throws IOException {
        if(!ocrEnabled()) throw bad("OCR尚未启用，请配置百炼OCR服务后重试");
        Document source=document(documentId);
        List<Checkpoint> planChecks=full?cn.bidcheck.compat.Java8Api.listOf():confirmedChecks(source.projectId());
        if(!full&&planChecks.isEmpty())throw bad("请先确认并启用检查项，再按检查计划识别");
        ExtractionPlan requestedPlan=full?ExtractionPlan.full():initialPlan(source.role(),source.name(),planChecks);
        String requestKey="ocr-request:"+stableId(documentId+":"+requestedPlan.hash()+":"+ocrPreparationIdentity());
        if(!source.name().toLowerCase(Locale.ROOT).matches(".*\\.(pdf|docx|png|jpg|jpeg)$")) throw bad("此文件无需OCR，请选择PDF、Word或图片");
        synchronized(projectLock(source.projectId())) {
            Stored previous=store.mapper().get(requestKey);
            if(previous!=null) {
                TaskRow active=task(text(store.map(previous.body),"taskId"));
                if(cn.bidcheck.compat.Java8Api.setOf("PENDING", "RUNNING").contains(active.status)) return active;
            }
            if(store.mapper().count("upload",source.projectId(),null)>=MAX_DOCUMENTS_PER_PROJECT)throw bad("每个项目最多上传100份文件（含历史版本），请创建新项目后继续");
            String id=Store.id(); Path staged=storage.stagePath(id);
            try { Files.copy(path(documentId),staged); storage.commit(id); }
            catch(IOException failure) { try { storage.discardStaging(id); } catch(IOException ignored) {} throw failure; }
            return tx.execute(s->{
                store.lock("project",source.projectId());
                Stored queued=store.mapper().get(requestKey);
                if(queued!=null) {
                    TaskRow active=task(text(store.map(queued.body),"taskId"));
                    if(cn.bidcheck.compat.Java8Api.setOf("PENDING", "RUNNING").contains(active.status)) return active;
                }
                TaskRow task=newTask(source.projectId(),"PARSE",fields("documentId",id));
                store.save("upload","upload:"+id,source.projectId(),"",fields("id",id,"projectId",source.projectId(),"role",source.role(),"name",source.name(),
                    "sha256",source.sha256(),"version",nextDocumentVersion(source.projectId(),source.role()),"createdAt",now(),"taskId",task.id,"reprocessOf",documentId,"requireOcr",full,"ocrIdentity",activeOcrIdentity(),"ocrPlanHash",requestedPlan.hash(),"ocrPlanCheckpoints",planChecks,"preparationIdentity",ocrPreparationIdentity()));
                store.save("ocr_request",requestKey,source.projectId(),documentId,fields("taskId",task.id,"documentId",id));
                if("TENDER".equals(source.role())) {Map<String,Object> p=store.getMap("project",source.projectId());p.put("requestedInformationTenderId",id);store.save("project",source.projectId(),source.projectId(),"",p);}
                return task;
            });
        }
    }
    private TaskRow completedDuplicate(String project,String documentId) {
        return tx.execute(s->{
            // Insert the reused task already terminal; a poller must never claim a synthetic PARSE task.
            TaskRow reused=new TaskRow(); reused.id=Store.id(); reused.projectId=project; reused.taskType="PARSE";
            reused.inputJson=store.encode(fields("documentId",documentId)); reused.status="SUCCEEDED";
            reused.progress=100; reused.stage="相同文件已解析，已复用原版本"; reused.createdAt=now(); reused.updatedAt=reused.createdAt;
            store.mapper().insertTask(reused); return reused;
        });
    }
    public Path path(String id) throws IOException {
        if(id==null||!id.matches("[a-fA-F0-9-]{36}")) throw bad("文件编号无效");
        Stored stored=store.mapper().get(id);
        Map<String,Object> metadata=stored!=null&&"document".equals(stored.kind)?store.map(stored.body):store.getMap("upload","upload:"+id);
        return storage.materialize(id,text(metadata,"sha256"));
    }
    Path stagePath(String id) throws IOException { return storage.stagePath(id); }
    void commitFile(String id) throws IOException { storage.commit(id); }
    Path committedPath(String id,String hash) throws IOException { return storage.materialize(id,hash); }
    public Document document(String id) { return store.get("document",id,Document.class); }
    public List<Fact> facts(String id) {
        Document document=document(id);
        Stored version=store.mapper().get("facts-version:"+id);
        if(version!=null&&RULE_VERSION.equals(text(store.map(version.body),"version"))&&"RELATIONAL_V3".equals(text(store.map(version.body),"storage"))) return withProcessingFacts(document,factLedger().nativeFacts(document,RULE_VERSION));
        List<Fact> fresh=factExtractor.extract(document);
        return tx.execute(s->{
            store.lock("document",id);
            Stored current=store.mapper().get("facts-version:"+id);
            if(current==null||!RULE_VERSION.equals(text(store.map(current.body),"version"))||!"RELATIONAL_V3".equals(text(store.map(current.body),"storage"))) replaceFacts(document,fresh);
            return withProcessingFacts(document,factLedger().nativeFacts(document,RULE_VERSION));
        });
    }
    private List<Fact> withProcessingFacts(Document document,List<Fact> nativeFacts){
        Stored saved=store.mapper().get(DocumentProcessingSession.summaryId(document.id()));
        if(saved==null||!document.projectId().equals(saved.projectId))return nativeFacts;
        Map<String,Object> summary=store.map(saved.body);
        if(!processingIdentity().equals(text(summary,"identity"))||!document.sha256().equals(text(summary,"documentHash"))
            ||!Objects.equals(document.version(),((Number)summary.getOrDefault("documentVersion",0)).intValue()))return nativeFacts;
        List<Fact> derived=factLedger().documentFacts(document,text(summary,"identity"));
        return IncrementalCheckEngine.distinctEvidence(cn.bidcheck.compat.Java8Api.toList(java.util.stream.Stream.concat(derived.stream(),nativeFacts.stream())));
    }
    public List<Map<String,Object>> processingOccurrences(List<Document> documents){
        List<Map<String,Object>> out=new ArrayList<>();for(Document doc:documents){
            Stored saved=store.mapper().get(DocumentProcessingSession.summaryId(doc.id()));if(saved==null||!doc.projectId().equals(saved.projectId))continue;
            Map<String,Object> summary=store.map(saved.body);if(!processingIdentity().equals(text(summary,"identity"))||!doc.sha256().equals(text(summary,"documentHash")))continue;
            Object occurrences=factLedger().documentView(doc,text(summary,"identity")).get("occurrences");
            if(occurrences instanceof List<?>)for(Object item:((List<?>) (occurrences)))if(item instanceof Map<?,?>){out.add(store.map(store.encode(item)));if(out.size()>=10000)return cn.bidcheck.compat.Java8Api.listCopyOf(out);}
        }return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }
    private void replaceFacts(Document document,List<Fact> facts) {
        String id=document.id();factLedger().persistNativeFacts(document,facts,RULE_VERSION);
        store.save("fact_index","facts-version:"+id,document.projectId(),id,fields("version",RULE_VERSION,"sha256",document.sha256(),"storage","RELATIONAL_V3"));
    }
    public Map<String,Object> profile(String id) { return new ProfileExtractor().extract(document(id)); }
    public byte[] pageImage(String id,int page) throws IOException {
        Document d=document(id);
        if(page<1||page>d.pages().size()) throw bad("页码不在文件范围内");
        if(!previewCapacity.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"原页预览繁忙，请稍后重试");
        try {
            String name=d.name().toLowerCase(Locale.ROOT);
            if(name.endsWith(".pdf")) return parser.renderPdfPage(path(id),page);
            if(name.matches(".*\\.(png|jpg|jpeg)$")) return parser.renderImage(path(id));
            if(name.endsWith(".docx")) return parser.renderWordImage(path(id),page);
            throw bad("此位置没有可预览的原图，请查看原文文本");
        }
        finally { previewCapacity.release(); }
    }
    public TaskRow extract(String project,String documentId) {
        Document d=document(documentId); scoped(d,project,"TENDER");
        if(d.pages().stream().allMatch(p->p.text()==null||cn.bidcheck.compat.Java8Api.isBlank(p.text()))) throw bad("文件没有可读取文本，请先提供文字版文件或完成 OCR");
        return tx.execute(s->{
            Map<String,Object> selected=store.map(store.lock("project",project).body);
            selected.put("requestedTenderId",documentId);selected.put("requestedInformationTenderId",documentId);store.save("project",project,project,"",selected);
            return newTask(project,"EXTRACT",fields("documentId",documentId));
        });
    }
    public Checkpoint updateCheckpoint(String id,Checkpoint input) {
        Stored stored=store.require("checkpoint",id); Checkpoint previous=store.decode(stored.body,Checkpoint.class);
        validate(input);
        Checkpoint updated=new Checkpoint(id,input.title(),input.category(),input.severity(),input.type(),input.field(),input.operator(),
            input.expected(),input.unit(),input.required(),input.confirmed(),input.enabled(),previous.source());
        tx.executeWithoutResult(s->{
            store.lock("project",stored.projectId);
            Checkpoint current=store.get("checkpoint",id,Checkpoint.class);
            store.save("checkpoint",id,stored.projectId,stored.parentId,updated);
            String audit=Store.id(); store.save("audit",audit,stored.projectId,id,fields("id",audit,"action","CHECKPOINT_UPDATED","before",current,"after",updated,"createdAt",now()));});
        return updated;
    }
    /** Atomically updates the user-visible checkpoint and its optional template draft. */
    public Map<String,Object> updateCheckpointConfiguration(String id,Map<String,Object> request) {
        if(!(request.get("checkpoint") instanceof Map<?,?>)||!(request.get("expectedCheckpoint") instanceof Map<?,?>))throw bad("检查项配置请求不完整");
        Checkpoint input,expected;try{
            // Invoke Store's codec methods instead of dereferencing its public ObjectMapper field.
            // Spring may wrap the repository in a transaction proxy; method dispatch remains safe
            // while direct field access can observe the proxy's uninitialised field.
            input=store.decode(store.encode(request.get("checkpoint")),Checkpoint.class);
            expected=store.decode(store.encode(request.get("expectedCheckpoint")),Checkpoint.class);
        }catch(RuntimeException invalid){throw bad("检查项配置无法读取");}
        Stored stored=store.require("checkpoint",id);validate(input);
        return tx.execute(ignored->{
            store.lock("project",stored.projectId);Checkpoint current=store.get("checkpoint",id,Checkpoint.class);
            if(!current.equals(expected))throw cn.bidcheck.checktemplate.TemplateApiException.conflict("CHECKPOINT_REVISION_CHANGED","检查项已被其他修改更新，请刷新后重试",-1);
            Checkpoint updated=new Checkpoint(id,input.title(),input.category(),input.severity(),input.type(),input.field(),input.operator(),input.expected(),input.unit(),input.required(),input.confirmed(),input.enabled(),current.source());
            store.save("checkpoint",id,stored.projectId,stored.parentId,updated);String audit=Store.id();store.save("audit",audit,stored.projectId,id,fields("id",audit,"action","CHECKPOINT_CONFIGURATION_UPDATED","before",current,"after",updated,"createdAt",now()));
            Map<String,Object> spec=null;if(request.get("spec") instanceof Map<?,?>&&!((Map<?,?>) (request.get("spec"))).isEmpty()){
                if(checkSpecifications==null)throw bad("检查规格服务不可用");@SuppressWarnings("unchecked") Map<String,Object> specRequest=(Map<String,Object>)((Map<?,?>) (request.get("spec")));spec=checkSpecifications.putSpec(id,specRequest);
            }
            return fields("checkpoint",updated,"spec",spec);
        });
    }
    public Checkpoint createCheckpoint(String project,Map<String,Object> body) {
        return tx.execute(s->{
            Map<String,Object> p=store.map(store.lock("project",project).body);
            String tender=text(p,"activeTenderId");
            if(cn.bidcheck.compat.Java8Api.isBlank(tender)) throw bad("请先选择并解析招标文件，再添加检查项");
            scoped(document(tender),project,"TENDER");
            String id=Store.id();
            String category=text(body,"category");if(!CHECK_CATEGORIES.contains(category)) throw bad("请选择形式、资格、响应、全文信息或效期检查");
            Checkpoint c=new Checkpoint(id,text(body,"title"),category,defaultText(body,"severity","MEDIUM"),defaultText(body,"type","SEMANTIC_RESPONSE"),defaultText(body,"field","manualRequirement"),defaultText(body,"operator","RESPONDS"),text(body,"expected"),text(body,"unit"),!Boolean.FALSE.equals(body.get("required")),Boolean.TRUE.equals(body.get("confirmed")),!Boolean.FALSE.equals(body.get("enabled")),null);
            if(cn.bidcheck.compat.Java8Api.isBlank(c.expected())) throw bad("请填写检查要求");
            validate(c);store.save("checkpoint",id,project,tender,c);
            String audit=Store.id();store.save("audit",audit,project,id,fields("id",audit,"action","CHECKPOINT_CREATED","after",c,"createdAt",now()));
            return c;
        });
    }
    private static String defaultText(Map<String,Object> body,String field,String fallback) {String value=text(body,field);return cn.bidcheck.compat.Java8Api.isBlank(value)?fallback:value;}
    public Map<String,Object> deleteCheckpoint(String id) {
        Stored row=store.require("checkpoint",id);
        return tx.execute(s->{
            store.lock("project",row.projectId);
            Checkpoint current=store.get("checkpoint",id,Checkpoint.class);
            // A tombstone prevents extraction from resurrecting the deleted item; snapshots retain it.
            store.save("deleted_checkpoint",id,row.projectId,row.parentId,current);
            String audit=Store.id();store.save("audit",audit,row.projectId,id,fields("id",audit,"action","CHECKPOINT_DELETED","before",current,"createdAt",now()));
            return fields("id",id,"deleted",true);
        });
    }
    private void validate(Checkpoint c) {
        if(c.title()==null||cn.bidcheck.compat.Java8Api.isBlank(c.title())||c.title().length()>300) throw bad("检查项标题不能为空或超过300字");
        if(c.type()==null||!cn.bidcheck.compat.Java8Api.setOf("VALUE_COMPARE", "ENTITY_FIELD_CONSISTENCY", "MATERIAL_EXISTENCE", "DATE_VALIDITY", "SEMANTIC_RESPONSE", "SIGNATURE_SEAL", "FULL_TEXT_REVIEW").contains(c.type())) throw bad("不支持的检查类型");
        if(c.severity()==null||!cn.bidcheck.compat.Java8Api.setOf("HIGH", "MEDIUM", "LOW").contains(c.severity())) throw bad("风险等级无效");
        if(c.expected()!=null&&c.expected().length()>4000) throw bad("期望值过长");
        if(c.field()==null||c.operator()==null||c.category()==null||c.category().length()>100||c.field().length()>100||c.operator().length()>40||c.unit()==null||c.unit().length()>100) throw bad("检查项字段、分类、运算符或单位无效");
        if("VALUE_COMPARE".equals(c.type())&&c.enabled()) {
            if(!cn.bidcheck.compat.Java8Api.setOf("bidValidity", "duration", "warranty", "totalPrice").contains(c.field()) || !cn.bidcheck.compat.Java8Api.setOf(">=", "<=", "=", ">", "<").contains(c.operator())) throw bad("数值检查的字段或运算符无效");
            if(c.expected()==null||!c.expected().matches("[0-9]{1,24}(\\.[0-9]{1,6})?")) throw bad("期望值需为非负数，最多24位整数及6位小数");
        }
        if("DATE_VALIDITY".equals(c.type())&&c.enabled()&&c.confirmed()) {
            try { java.time.LocalDate.parse(c.expected()); } catch(Exception e) { throw bad("请将截止日期填写为 YYYY-MM-DD"); }
        }
    }
    public Map<String,Object> startRun(String project,String documentId) {
        return startRun(project,documentId,false);
    }
    public Map<String,Object> startRun(String project,String documentId,boolean force) {
        return startRun(project,documentId,force,"",null,"");
    }
    public Map<String,Object> startRun(String project,String documentId,boolean force,String requestedCheckSetId,Integer requestedManifestRevision,String idempotencyKey) {
        return startRun(project,documentId,force,requestedCheckSetId,requestedManifestRevision,idempotencyKey,CheckReviewMode.FIXED);
    }
    public Map<String,Object> startRun(String project,String documentId,boolean force,String requestedCheckSetId,Integer requestedManifestRevision,String idempotencyKey,String requestedReviewMode) {
        return startRun(project,documentId,force,requestedCheckSetId,requestedManifestRevision,idempotencyKey,requestedReviewMode,null);
    }
    public Map<String,Object> startRun(String project,String documentId,boolean force,String requestedCheckSetId,Integer requestedManifestRevision,String idempotencyKey,String requestedReviewMode,List<String> requestedCheckpointIds) {
        boolean explicitTargeted=requestedCheckpointIds!=null;
        Set<String> selectedIds=new TreeSet<>();
        if(explicitTargeted){
            if(requestedCheckpointIds.isEmpty()||requestedCheckpointIds.size()>1000||requestedCheckpointIds.stream().anyMatch(v->v==null||cn.bidcheck.compat.Java8Api.isBlank(v)))throw bad("定向检查必须提供非空的检查项编号列表");
            requestedCheckpointIds.stream().map(cn.bidcheck.compat.Java8Api::strip).forEach(selectedIds::add);
            if(requestedCheckSetId!=null&&!cn.bidcheck.compat.Java8Api.isBlank(requestedCheckSetId)||requestedManifestRevision!=null)throw bad("定向检查不能同时指定模板检查集；检查项将单独冻结");
        }
        String reviewMode=CheckReviewMode.normalize(requestedReviewMode);requireReviewMode(reviewMode);
        boolean useTemplates=CheckReviewMode.FIXED.equals(reviewMode)&&templateRouteEnabled();
        Document d=document(documentId); scoped(d,project,"BID");
        return tx.execute(s->{
            Map<String,Object> projectState=store.map(store.lock("project",project).body);
            String informationTender=text(projectState,"informationTenderId");
            if(!cn.bidcheck.compat.Java8Api.isBlank(informationTender)&&!informationTender.equals(text(projectState,"activeTenderId")))throw bad("招标文件已更新，请先重新提取并确认对应检查项");
            List<Checkpoint> enabledChecks=cn.bidcheck.compat.Java8Api.toList(activeCheckpoints(project,projectState).stream().filter(Checkpoint::enabled));
            if(explicitTargeted){Set<String> confirmed=enabledChecks.stream().filter(Checkpoint::confirmed).map(Checkpoint::id).collect(java.util.stream.Collectors.toSet());
                List<String> invalid=cn.bidcheck.compat.Java8Api.toList(selectedIds.stream().filter(id->!confirmed.contains(id)));if(!invalid.isEmpty())throw bad("定向检查项不存在、未启用或未确认："+String.join(",",invalid));}
            List<Checkpoint> originalChecks=explicitTargeted?cn.bidcheck.compat.Java8Api.toList(enabledChecks.stream().filter(c->selectedIds.contains(c.id()))):enabledChecks;
            requireCurrentCheckpointRules(projectState,originalChecks);
            List<Checkpoint> checks=cn.bidcheck.compat.Java8Api.toList(originalChecks.stream().map(c->withProjectDate(c,projectState)));
            if(checks.isEmpty()) throw bad("请先从招标文件提取并确认检查项");
            if(checks.stream().anyMatch(c->!c.confirmed())) throw bad("仍有启用的检查项未确认，请确认或停用后再开始检查");
            originalChecks.forEach(this::validate);
            Map<String,Object> information=fields("name",text(projectState,"name"),"projectNumber",text(projectState,"projectNumber"),"code",text(projectState,"projectNumber"),"openingTime",text(projectState,"openingTime"),"manualFields",projectState.getOrDefault("manualFields",cn.bidcheck.compat.Java8Api.listOf()));
            String tenderId=text(projectState,"activeTenderId");Optional<Map<String,Object>> selectedSet=checkSpecifications==null||explicitTargeted?Optional.empty():checkSpecifications.confirmedSetForRun(project,tenderId,requestedCheckSetId,requestedManifestRevision);
            if((requestedCheckSetId!=null&&!cn.bidcheck.compat.Java8Api.isBlank(requestedCheckSetId)||requestedManifestRevision!=null)&&checkSpecifications==null)throw bad("检查集服务不可用，无法按已确认版本开始检查");
            selectedSet.ifPresent(value->checkSpecifications.verifyRunCheckpointManifest(value,originalChecks));
            String checkSetId=selectedSet.map(value->text(value,"checkSetId")).orElse("");Integer manifestRevision=selectedSet.map(value->((Number)value.get("manifestRevision")).intValue()).orElse(null);
            String templateSpecIdentity=selectedSet.map(value->text(value,"templateSpecIdentity")).orElseGet(()->checkSpecifications==null?"UNAVAILABLE":checkSpecifications.confirmedSpecsIdentity(project,checks));
            List<String> configuredTemplateIds=selectedSet.map(value->stringList(value.get("templateCheckpointIds"))).orElseGet(()->cn.bidcheck.compat.Java8Api.toList(checks.stream().filter(c->currentTemplateRoute(project,c)).map(Checkpoint::id)));
            List<String> templateRouteIds=useTemplates?configuredTemplateIds:cn.bidcheck.compat.Java8Api.listOf();
            String normalizedRequest=store.encode(fields("documentId",d.id(),"documentVersion",d.version(),"documentHash",d.sha256(),"force",force,"checkSetId",checkSetId,"manifestRevision",manifestRevision==null?-1:manifestRevision,"reviewMode",reviewMode,"templateRouteEnabled",useTemplates,"templateCheckpointIds",templateRouteIds,"explicitTargetedRun",explicitTargeted,"targetedCheckpointIds",cn.bidcheck.compat.Java8Api.listCopyOf(selectedIds)));
            String requestDigest=hash(normalizedRequest),apiRequestId=runApiRequestId(project,idempotencyKey);
            Map<String,Object> prior=idempotencyKey==null||cn.bidcheck.compat.Java8Api.isBlank(idempotencyKey)?null:priorRunRequest(apiRequestId,requestDigest);
            if(prior!=null)return store.getMap("run",text(prior,"resourceId"));
            cn.bidcheck.service.InferenceConfirmationSnapshot.Snapshot confirmations=InferenceConfirmationSnapshot.from(store.maps("inference_decided",project,d.id()),d,cn.bidcheck.compat.Java8Api.isBlank(tenderId)?null:document(tenderId));
            String fingerprint=hash(d.id()+"\n"+d.sha256()+store.encode(checks)+store.encode(information)+RULE_VERSION+semantic.identity()+ocrPreparationIdentity()+consistencyIdentity()+focusedCheckIdentity()+templateCheckIdentity()+"|reviewMode="+reviewMode+"|templateRoute="+useTemplates+store.encode(templateRouteIds)+templateSpecIdentity+"|checkSet="+checkSetId+"@"+(manifestRevision==null?"legacy":manifestRevision)+resolutionPolicyIdentity()+"|confirmations="+confirmations.digest()+"|explicitTargeted="+explicitTargeted+"|targetIds="+String.join(",",selectedIds));
            Optional<Map<String,Object>> existing=store.maps("run",project,null).stream().filter(r->fingerprint.equals(r.get("fingerprint"))&&!"FAILED".equals(r.get("status"))&&(!force||cn.bidcheck.compat.Java8Api.setOf("PENDING", "RUNNING").contains(r.get("status")))).reduce((first,last)->last);
            if(existing.isPresent()) {persistRunRequest(project,apiRequestId,idempotencyKey,requestDigest,text(existing.get(),"id"));return existing.get();}
            String id=Store.id(); TaskRow task=newTask(project,"CHECK",fields("runId",id,"documentId",documentId));
            Map<String,Object> run=fields("id",id,"projectId",project,"documentId",documentId,"tenderDocumentId",tenderId,"status","PENDING","stage","等待检查","progress",0,"createdAt",now(),"taskId",task.id,"fingerprint",fingerprint,"ruleVersion",RULE_VERSION,"documentVersion",d.version(),"semanticConfiguration",semantic.identity(),"model",semanticModel());
            if(explicitTargeted){run.put("explicitTargetedRun",true);run.put("targetedCheckpointIds",cn.bidcheck.compat.Java8Api.listCopyOf(selectedIds));run.put("consistencyScope","TARGETED_ITEMS_NO_ADDITIONAL_FINDINGS");LOG.info("bidcheck.targeted_run runId={} projectId={} checkpointCount={} checkpointIds={} manifest=FROZEN_PROJECT_CHECKPOINTS",id,project,checks.size(),selectedIds);}
            if(!cn.bidcheck.compat.Java8Api.isBlank(checkSetId)){run.put("checkSetId",checkSetId);run.put("manifestRevision",manifestRevision);run.put("runRequestDigest",requestDigest);}
            run.put("inferenceConfirmationDigest",confirmations.digest());
            run.put("inferenceConfirmationRecords",confirmations.records());
            run.put("checkConcurrency",checkConcurrency());
            run.put("ocrPreparationIdentity",ocrPreparationIdentity());
            run.put("consistencyIdentity",consistencyIdentity());
            run.put("focusedCheckIdentity",focusedCheckIdentity());
            run.put("reviewMode",reviewMode);run.put("reviewModeVersion",CheckReviewMode.VERSION);
            run.put("consistencyReviewMode","RULES_RETRIEVAL");
            run.put("templateRouteEnabled",useTemplates);
            run.put("templateCheckIdentity",templateCheckIdentity());
            run.put("templateSpecIdentity",templateSpecIdentity);
            run.put("resolutionPolicyIdentity",resolutionPolicyIdentity());
            run.put("onDemandCheckpointCount",checks.stream().filter(c->!templateRouteIds.contains(c.id())&&("SEMANTIC_RESPONSE".equals(c.type())||focusedChecks!=null&&focusedChecks.supports(c,reviewMode))).count());
            run.put("checkMode",checkMode());run.put("onDemandPolicyVersion",onDemandPolicyVersion());
            run.put("onDemandAttemptBudget",onDemandAttemptBudget());run.put("onDemandAttemptsUnlimited",true);run.put("onDemandAttemptsReserved",0);
            run.put("consistencyReviewPolicyVersion",ConsistencyDoubtReviewer.VERSION);
            run.put("consistencyReviewAttemptBudget",ConsistencyDoubtReviewer.MAX_MODEL_ATTEMPTS);
            run.put("consistencyReviewAttemptsUnlimited",true);
            run.put("consistencyReviewAttemptsReserved",0);
            run.put("projectInformation",information);
            store.save("run",id,project,documentId,run);
            store.insertNew("snapshot",project,id,checks,c->stableId(id+c.id()));
            if(checkSpecifications!=null){Map<String,Object> templateSnapshot=selectedSet.isPresent()
                ?checkSpecifications.snapshotForRun(project,id,checks,checkSetId,manifestRevision,cn.bidcheck.compat.Java8Api.listOf(tenderId, d.id()),templateRouteIds)
                :checkSpecifications.snapshotForRun(project,id,checks,templateRouteIds);
                run.put("templateSnapshotId",templateSnapshot.get("id"));run.put("templateSnapshotDigest",templateSnapshot.get("contentDigest"));run.put("templateRouting",templateSnapshot.get("routing"));store.save("run",id,project,documentId,run);}
            persistRunRequest(project,apiRequestId,idempotencyKey,requestDigest,id);
            return run;
        });
    }

    private String runApiRequestId(String project,String key){if(key==null||cn.bidcheck.compat.Java8Api.isBlank(key))return "";if(key.length()>160||!key.matches("[A-Za-z0-9._:-]+"))throw bad("幂等键格式无效或长度超过限制");return stableId("api-request:START_RUN:"+project+":"+key);}
    private Map<String,Object> priorRunRequest(String id,String digest){Stored row=store.mapper().get(id);if(row==null)return null;if(!"api_request".equals(row.kind))throw new IllegalStateException("IDEMPOTENCY_RECORD_SCOPE_MISMATCH");Map<String,Object> saved=store.map(row.body);if(!digest.equals(text(saved,"requestDigest")))throw cn.bidcheck.checktemplate.TemplateApiException.conflict("IDEMPOTENCY_CONFLICT","同一幂等键已用于不同的检查请求",-1);return saved;}
    private void persistRunRequest(String project,String id,String key,String digest,String runId){if(id==null||cn.bidcheck.compat.Java8Api.isBlank(id))return;Map<String,Object> value=fields("schemaVersion","api-request-v1","projectId",project,"operation","START_RUN","idempotencyKey",key,"requestDigest",digest,"resourceId",runId,"committedAt",now());store.insertImmutableIfAbsent("api_request",id,project,project,value);}
    public String resolutionPolicyIdentity(){return hash(CheckResolution.VERSION+RULE_VERSION+"|reviewMode="+CheckReviewMode.VERSION+"|agentGroup="+AgentBusinessGroupReuse.VERSION+"|ocrReview="+cn.bidcheck.engine.OcrReviewPolicy.VERSION+"|"+semantic.identity()+focusedCheckIdentity()+templateCheckIdentity()+consistencyIdentity()+onDemandPolicyVersion()
        +(focusedContext==null?"":focusedContext.identity())+(verification==null?"":verification.getObject().checkSourceIdentity()));}

    private boolean currentTemplateRoute(String projectId,Checkpoint checkpoint){
        boolean confirmedSpec=checkSpecifications!=null&&checkSpecifications.findConfirmedSpec(projectId,checkpoint.id()).isPresent();
        // The compatibility bridge needs the exact tender source to produce a valid frozen spec.
        // Keep source-less manual checks on the legacy route instead of emitting INVALID_CHECK_SPEC.
        boolean sourceBoundLegacy=checkpoint.source()!=null&&templateChecks.supports(checkpoint);
        return templateRouteEnabled()&&(confirmedSpec||sourceBoundLegacy);
    }
    private boolean frozenTemplateRoute(String projectId,String runId,Checkpoint checkpoint){
        Map<String,Object> run=store.getMap("run",runId);boolean enabled=run.containsKey("templateRouteEnabled")?Boolean.TRUE.equals(run.get("templateRouteEnabled")):run.containsKey("templateCheckIdentity");
        if(CheckReviewMode.agent(run))return false;
        if(!enabled)return false;
        Map<String,Object> routing=CheckResolution.map(run.get("templateRouting"));
        List<String> frozenIds=stringList(routing.get("templateCheckpointIds"));
        if(!frozenIds.isEmpty()||routing.containsKey("templateCheckpointIds"))return frozenIds.contains(checkpoint.id());
        // Read-only compatibility for runs created before routing was frozen explicitly.
        return templateChecks!=null&&(checkSpecifications!=null&&checkSpecifications.findSnapshotSpec(projectId,runId,checkpoint.id()).isPresent()||templateChecks.supports(checkpoint));
    }
    private boolean frozenExplicitTemplateRoute(String runId,Checkpoint checkpoint){
        Map<String,Object> routing=CheckResolution.map(store.getMap("run",runId).get("templateRouting"));
        List<String> explicit=stringList(routing.get("explicitTemplateCheckpointIds"));
        if(routing.containsKey("explicitTemplateCheckpointIds"))return explicit.contains(checkpoint.id());
        return false;
    }
    private static List<String> stringList(Object value){
        if(!(value instanceof Collection<?>))return cn.bidcheck.compat.Java8Api.listOf();
        return cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().filter(Objects::nonNull).map(Object::toString));
    }
    private boolean reusableResult(Finding finding,Map<String,Object> parent,String currentTemplateSpecIdentity){
        if(CheckResolution.unresolved(finding))return false;
        Map<String,Object> a=CheckResolution.map(finding.assessment());
        if(a.containsKey("consistencyGroupId"))return consistencyIdentity().equals(text(parent,"consistencyIdentity"));
        if(a.containsKey("templateTrace"))return Boolean.TRUE.equals(parent.get("templateRouteEnabled"))
            &&templateCheckIdentity().equals(text(parent,"templateCheckIdentity"))
            &&Objects.equals(currentTemplateSpecIdentity,text(parent,"templateSpecIdentity"));
        if(!CheckResolution.atomic(finding).isEmpty())return focusedCheckIdentity().equals(text(parent,"focusedCheckIdentity"))
            &&resolutionPolicyIdentity().equals(text(parent,"resolutionPolicyIdentity"));
        if(a.containsKey("model")||a.containsKey("semanticPolicyVersion"))return semantic.identity().equals(text(parent,"semanticConfiguration"));
        return RULE_VERSION.equals(text(parent,"ruleVersion"));
    }
    /** A recovery is an immutable child assessment, never an edit to a completed report. */
    public Map<String,Object> recoverRun(String project,String parentRunId){
        return tx.execute(s->{
            store.lock("project",project);
            Map<String,Object> parent=store.getMap("run",parentRunId);
            if(!project.equals(text(parent,"projectId")))throw bad("检查记录不属于当前项目");
            String reviewMode=CheckReviewMode.of(parent);requireReviewMode(reviewMode);
            if(!cn.bidcheck.compat.Java8Api.setOf("COMPLETED", "FAILED").contains(text(parent,"status")))throw bad("当前检查仍在执行，请等待本轮结束");
            List<Checkpoint> checks=store.list("snapshot",project,parentRunId,Checkpoint.class);
            boolean parentTemplateRoute=Boolean.TRUE.equals(parent.get("templateRouteEnabled"));
            if(parentTemplateRoute&&!templateCheckIdentity().equals(text(parent,"templateCheckIdentity")))throw bad("固定检查模板或判定器已升级，请发起完整新检查；历史报告保持原样");
            String parentTemplateSpecIdentity=text(parent,"templateSpecIdentity");final String frozenTemplateSpecIdentity=cn.bidcheck.compat.Java8Api.isBlank(parentTemplateSpecIdentity)?"UNAVAILABLE":parentTemplateSpecIdentity;
            Document bid=document(text(parent,"documentId"));scoped(bid,project,"BID");
            String tenderId=text(parent,"tenderDocumentId");Document tender=cn.bidcheck.compat.Java8Api.isBlank(tenderId)?null:document(tenderId);if(tender!=null)scoped(tender,project,"TENDER");
            List<Map<String,Object>> inferenceDecisions=store.maps("inference_decided",project,bid.id());
            cn.bidcheck.service.InferenceConfirmationSnapshot.Snapshot confirmations=InferenceConfirmationSnapshot.from(inferenceDecisions,bid,tender);
            boolean confirmationChanged=!cn.bidcheck.compat.Java8Api.isBlank(confirmations.digest())&&!confirmations.digest().equals(text(parent,"inferenceConfirmationDigest"));
            List<Finding> previous=store.list("finding",project,parentRunId,Finding.class);
            cn.bidcheck.service.SupplementAttachmentSnapshot.Snapshot supplements=SupplementAttachmentSnapshot.from(CheckResolution.maps(parent.get("supplementManifest")),store.maps("supplement_attachment",project,parentRunId),previous);
            boolean supplementChanged=!supplements.digest().equals(text(parent,"supplementDigest"));
            boolean supplementOnly=supplementChanged&&!confirmationChanged;
            String policy=resolutionPolicyIdentity(),requestId=stableId("check-recovery:"+parentRunId+":"+policy+":"+text(parent,"templateSnapshotDigest")+":confirmations="+confirmations.digest()+":supplements="+supplements.digest());
            Stored requested=store.mapper().get(requestId);
            if(requested!=null){if(!"check_recovery_request".equals(requested.kind)||!project.equals(requested.projectId)||!parentRunId.equals(requested.parentId))throw new IllegalStateException("RECOVERY_IDEMPOTENCY_SCOPE_MISMATCH");return store.getMap("run",text(store.map(requested.body),"runId"));}
            Set<String> confirmationAffected=new LinkedHashSet<>();
            if(confirmationChanged){
                confirmationAffected.addAll(confirmations.checkpointIds());
                confirmationAffected.addAll(InferenceConfirmationImpact.affected(previous,CheckResolution.maps(parent.get("inferenceConfirmationRecords")),confirmations.records(),inferenceDecisions));
                LOG.info("bidcheck.inference_recompute parentRunId={} confirmationRecords={} affectedCheckpointIds={}",parentRunId,confirmations.records().size(),confirmationAffected);
            }
            if(supplementChanged){confirmationAffected.addAll(supplements.affectedCheckpointIds());LOG.info("bidcheck.supplement_recompute parentRunId={} manifestDigest={} attachmentCount={} affectedCheckpointIds={} scope=BOUND_ITEM_AND_EXPLICIT_DEPENDENCIES",parentRunId,supplements.digest(),supplements.manifest().size(),supplements.affectedCheckpointIds());}
            Map<String,Object> consistency=consistencyReport(parentRunId);
            if(confirmationChanged){
                List<Map<String,Object>> cited=cn.bidcheck.compat.Java8Api.toList(inferenceDecisions.stream().filter(c->"CONFIRMED".equals(text(c,"status"))).flatMap(c->CheckResolution.maps(c.get("evidences")).stream()));
                for(Map<String,Object> group:ConsistencyRunSupport.maps(consistency.get("groups")))if(CheckResolution.maps(group.get("evidences")).stream().anyMatch(e->cited.stream().anyMatch(c->text(e,"documentId").equals(text(c,"documentId"))&&Objects.equals(e.get("page"),c.get("page"))&&text(e,"locationType").equals(text(c,"locationType")))))
                    previous.stream().filter(f->f.id().equals(text(group,"findingId"))).map(f->f.checkpoint().id()).forEach(confirmationAffected::add);
            }
            Set<String> confirmedTargets=cn.bidcheck.compat.Java8Api.setCopyOf(confirmationAffected);
            Set<String> found=previous.stream().map(f->f.checkpoint().id()).collect(java.util.stream.Collectors.toSet());
            boolean missing=checks.stream().anyMatch(c->!found.contains(c.id()));
            Map<String,Object> available=CheckResolution.summary(parent,previous,store.maps("check_resolution",project,parentRunId),policy);
            if(!missing&&!confirmationChanged&&!supplementChanged&&!Boolean.TRUE.equals(available.get("canRecover")))throw bad(text(available,"blockReason"));
            String id=Store.id();TaskRow task=newTask(project,"CHECK",fields("runId",id,"documentId",bid.id()));
            List<String> templateRouteIds=stringList(CheckResolution.map(parent.get("templateRouting")).get("templateCheckpointIds"));
            Map<String,Object> run=new LinkedHashMap<>(parent);
            for(String key:cn.bidcheck.compat.Java8Api.listOf("finishedAt", "error", "retrievalPreparation", "atomicReferenceDates", "atomicFactIdentity", "resolutionSummary"))run.remove(key);
            run.putAll(fields("id",id,"taskId",task.id,"parentRunId",parentRunId,"recoveryOnly",true,"createdAt",now(),"status","PENDING","stage","等待补查未完成事项","progress",0,
                "fingerprint",hash(parentRunId+policy+frozenTemplateSpecIdentity+"|reviewMode="+reviewMode+"|templateSnapshot="+text(parent,"templateSnapshotDigest")+"|confirmations="+confirmations.digest()+"|supplements="+supplements.digest()),"reviewMode",reviewMode,"reviewModeVersion",CheckReviewMode.VERSION,"ruleVersion",RULE_VERSION,"semanticConfiguration",semantic.identity(),"model",semanticModel(),
                "checkMode",checkMode(),"onDemandPolicyVersion",onDemandPolicyVersion(),"ocrPreparationIdentity",ocrPreparationIdentity(),"ocrPreparationComplete",true,
                "focusedCheckIdentity",focusedCheckIdentity(),"templateRouteEnabled",parentTemplateRoute,"templateCheckIdentity",parent.getOrDefault("templateCheckIdentity",templateCheckIdentity()),"templateSpecIdentity",frozenTemplateSpecIdentity,"consistencyIdentity",consistencyIdentity(),"resolutionPolicyIdentity",policy,
                "onDemandAttemptsUnlimited",true,"onDemandAttemptsReserved",0,"consistencyReviewAttemptsReserved",0,"visualCallCount",0));
            List<String> targets=new ArrayList<>();
            run.put("inferenceConfirmationDigest",confirmations.digest());
            run.put("inferenceConfirmationRecords",confirmations.records());
            run.put("supplementManifest",supplements.manifest());run.put("supplementDigest",supplements.digest());run.put("supplementOnlyRecovery",supplementOnly);
            for(Finding f:previous){
                boolean pending=confirmedTargets.contains(f.checkpoint().id())||!supplementOnly&&!reusableResult(f,parent,frozenTemplateSpecIdentity);if(pending)targets.add(f.checkpoint().id());
                Map<String,Object> a=pending?new LinkedHashMap<>():new LinkedHashMap<>(CheckResolution.map(f.assessment()));a.remove("resolutionJob");
                if(pending){
                    copyConsistencyLocation(a,CheckResolution.map(f.assessment()));
                    List<Map<String,Object>> matching=cn.bidcheck.compat.Java8Api.toList(ConsistencyRunSupport.maps(consistency.get("groups")).stream().filter(g->f.id().equals(text(g,"findingId"))));
                    if(matching.size()==1)copyConsistencyGroupLocation(a,matching.get(0));
                }
                a.put("inheritedFrom",fields("runId",parentRunId,"findingId",f.id(),"pending",pending,"ruleVersion",parent.get("ruleVersion"),
                    "semanticConfiguration",parent.get("semanticConfiguration"),"focusedCheckIdentity",parent.get("focusedCheckIdentity"),"reusedConclusion",!pending,"previousEvaluation",f.evaluation()));
                Evaluation published=f.evaluation();
                if(pending){published=new Evaluation("UNDETERMINED","本项等待按当前规则重新核验；旧结论仅保留在来源批次。","继续已保存的补查任务。",f.evaluation().evidences(),0,"RECOVERY_PENDING");
                    a.put("adjustments",cn.bidcheck.compat.Java8Api.listOf(fields("code","REQUIREMENT_RECHECK_PENDING","description","旧结论未作为本批次新结论使用。")));}
                Finding copy=new Finding(stableId(id+":"+f.checkpoint().id()),id,f.checkpoint(),published,FocusedCheckEvaluator.reconcileAssessment(published,a));
                store.save("finding",copy.id(),project,id,copy);
                if(pending)store.save("check_resolution",CheckResolution.jobId(id,f.checkpoint().id()),project,id,
                    fields("id",CheckResolution.jobId(id,f.checkpoint().id()),"runId",id,"checkpointId",f.checkpoint().id(),"status","QUEUED","strategy","REEVALUATE_CHANGED_POLICY",
                        "parentRunId",parentRunId,"reason","按当前规则继续未完成义务，复用冻结原文和已有证明。","nextAction","补查本项缺口","attempts",0,"updatedAt",now()));
            }
            checks.stream().filter(c->!found.contains(c.id())&&(!supplementOnly||confirmedTargets.contains(c.id()))).forEach(c->targets.add(c.id()));run.put("recoveryCheckpointIds",targets);
            run.put("inheritedResolvedCount",previous.stream().filter(f->!confirmedTargets.contains(f.checkpoint().id())&&(supplementOnly||reusableResult(f,parent,frozenTemplateSpecIdentity))).count());
            Map<String,Map<String,Object>> priorReviews=new LinkedHashMap<>();
            CheckResolution.maps(parent.get("priorReviewRecords")).forEach(v->priorReviews.put(text(v,"id"),v));
            store.maps("review",project,null).stream().filter(v->parentRunId.equals(v.get("runId"))).forEach(v->priorReviews.put(text(v,"id"),v));
            run.put("priorReviewRecords",cn.bidcheck.compat.Java8Api.listCopyOf(priorReviews.values()));
            store.save("run",id,project,bid.id(),run);store.insertNew("snapshot",project,id,checks,c->stableId(id+c.id()));
            if(checkSpecifications!=null){Map<String,Object> templateSnapshot=cn.bidcheck.compat.Java8Api.isBlank(text(parent,"templateSnapshotId"))
                ?checkSpecifications.snapshotForRun(project,id,checks,templateRouteIds)
                :checkSpecifications.copySnapshotForRecovery(project,parentRunId,id);
                run.put("templateSnapshotId",templateSnapshot.get("id"));run.put("templateSnapshotDigest",templateSnapshot.get("contentDigest"));run.put("templateRouting",templateSnapshot.get("routing"));store.save("run",id,project,bid.id(),run);}
            if(!consistency.isEmpty()){
                Map<String,Object> copy=new LinkedHashMap<>(consistency);copy.put("sourceRunId",parentRunId);copy.put("recoveryFrozenScope",true);
                copy.remove("reviewSnapshotComplete");copy.remove("doubtReview");
                List<Map<String,Object>> groups=new ArrayList<>();
                for(Map<String,Object> old:ConsistencyRunSupport.maps(copy.get("groups"))){
                    Map<String,Object> group=new LinkedHashMap<>(old);String previousId=text(old,"findingId");
                    previous.stream().filter(f->f.id().equals(previousId)).findFirst().ifPresent(f->{group.put("findingId",stableId(id+":"+f.checkpoint().id()));
                        if(confirmedTargets.contains(f.checkpoint().id())||!supplementOnly&&!reusableResult(f,parent,frozenTemplateSpecIdentity)){group.remove("targetedReview");group.remove("recoveryRetained");}else group.put("recoveryRetained",true);});groups.add(group);
                }
                copy.put("groups",groups);store.save("consistency_report",ConsistencyRunSupport.reportId(id),project,id,copy);
            }
            store.insertImmutableIfAbsent("check_recovery_request",requestId,project,parentRunId,fields("runId",id,"policyIdentity",policy,"parentTemplateSnapshotDigest",text(parent,"templateSnapshotDigest"),"createdAt",now()));
            return run;
        });
    }
    private static void copyConsistencyLocation(Map<String,Object> target,Map<String,Object> source){
        for(String key:cn.bidcheck.compat.Java8Api.listOf("consistencyGroupId", "categoryId", "relationType", "ruleId", "comparisonContext"))
            if(source.containsKey(key))target.put(key,source.get(key));
    }
    private static void copyConsistencyGroupLocation(Map<String,Object> target,Map<String,Object> group){
        if(cn.bidcheck.compat.Java8Api.isBlank(text(group,"id")))return;
        target.put("consistencyGroupId",group.get("id"));copyConsistencyLocation(target,group);
    }
    /** Review receipts describe executed work; a model recommendation never replaces a business conclusion. */
    static Finding reviewedConsistencyFinding(Finding finding,Map<String,Object> group){return reviewedConsistencyFinding(finding,group,null);}
    static Finding reviewedConsistencyFinding(Finding finding,Map<String,Object> group,Evaluation derived){
        if(!finding.id().equals(text(group,"findingId"))||cn.bidcheck.compat.Java8Api.isBlank(text(group,"id")))return finding;
        Map<String,Object> assessment=new LinkedHashMap<>(CheckResolution.map(finding.assessment()));
        copyConsistencyGroupLocation(assessment,group);
        Map<String,Object> review=CheckResolution.map(group.get("targetedReview"));assessment.put("consistencyReview",review);
        Map<String,Object> inherited=new LinkedHashMap<>(CheckResolution.map(assessment.get("inheritedFrom")));
        boolean recoveryPending=Boolean.TRUE.equals(inherited.get("pending"))||"RECOVERY_PENDING".equals(finding.evaluation().method());
        boolean received=ConsistencyDoubtReviewer.VERSION.equals(text(review,"policyVersion"))&&cn.bidcheck.compat.Java8Api.setOf("RULE_CONFIRMED", "LOCAL_RELATIONS_VERIFIED", "EVIDENCE_AUGMENTED", "MODEL_REVIEWED", "EVIDENCE_INSUFFICIENT", "MODEL_UNAVAILABLE", "ERROR").contains(text(review,"status"));
        Evaluation evaluation=finding.evaluation();
        if(recoveryPending&&received){
            inherited.putAll(fields("pending",false,"rechecked",true,"reusedConclusion",false));assessment.put("inheritedFrom",inherited);
            boolean failed=cn.bidcheck.compat.Java8Api.setOf("ERROR", "MODEL_UNAVAILABLE").contains(text(review,"status"));
            List<Map<String,Object>> adjustments=new ArrayList<>(cn.bidcheck.compat.Java8Api.toList(CheckResolution.maps(assessment.get("adjustments")).stream().filter(v->!"REQUIREMENT_RECHECK_PENDING".equals(text(v,"code")))));
            if(failed)adjustments.add(fields("code","MODEL_CONSISTENCY_REVIEW_FAILED","description","本组定向复核尚未取得完整结果，已保存失败收据。"));
            assessment.put("adjustments",adjustments);
            assessment.put("consistencyRecoveryExecution",fields("status",failed?"FAILED":"FINISHED","reviewStatus",review.get("status"),"receiptSaved",true));
            evaluation=new Evaluation("UNDETERMINED",failed?"本组补查已执行但未取得完整复核结果，具体原因及已完成证据已保存。":"本组定向复核已执行；补充证据和建议已保存，尚未形成确定业务结论。",
                "查看本组比较依据、剩余缺口和复核记录。",evaluation.evidences(),0,"CONSISTENCY_RECOVERY_REVIEW");
        }
        Checkpoint checkpoint=finding.checkpoint();
        if(derived!=null&&"CONSISTENCY_DERIVED_OBSERVED_OCCURRENCES".equals(derived.method())){
            Map<String,Object> proof=CheckResolution.map(group.get("derivedConclusion"));
            if(ConsistencyDerivedConclusion.VERSION.equals(text(proof,"policyVersion"))&&derived.status().equals(text(proof,"status"))){
                assessment.putIfAbsent("originalConsistencyEvaluation",finding.evaluation());
                assessment.put("derivedConclusion",proof);evaluation=derived;
                if(recoveryPending){inherited.putAll(fields("pending",false,"rechecked",true,"reusedConclusion",false));assessment.put("inheritedFrom",inherited);}
                assessment.put("consistencyRecoveryExecution",fields("status","FINISHED","reviewStatus",review.get("status"),"receiptSaved",true,"recomputed",true));
                {
                    String originalTitle=text(assessment,"originalConsistencyTitle");
                    if(cn.bidcheck.compat.Java8Api.isBlank(originalTitle))originalTitle=checkpoint.title().contains("已识别记录一致")&&!cn.bidcheck.compat.Java8Api.isBlank(text(group,"title"))?text(group,"title"):checkpoint.title();
                    assessment.putIfAbsent("originalConsistencyTitle",originalTitle);
                    String title="PASS".equals(derived.status())?originalTitle.replace("缺少比较依据","已识别记录一致").replace("存在疑似差异","已识别记录一致"):originalTitle;
                    checkpoint=new Checkpoint(checkpoint.id(),title,checkpoint.category(),checkpoint.severity(),checkpoint.type(),checkpoint.field(),checkpoint.operator(),checkpoint.expected(),checkpoint.unit(),checkpoint.required(),checkpoint.confirmed(),checkpoint.enabled(),checkpoint.source());
                }
            }
        }
        return new Finding(finding.id(),finding.runId(),checkpoint,evaluation,FocusedCheckEvaluator.reconcileAssessment(evaluation,assessment));
    }
    private Checkpoint withProjectDate(Checkpoint c,Map<String,Object> project) {
        String opening=text(project,"openingTime");
        if(!project.containsKey("openingTime"))return c;
        boolean signing="FULL_TEXT_REVIEW".equals(c.type())&&"signingAfterOpening".equals(c.field());
        boolean certificate=("FULL_TEXT_REVIEW".equals(c.type())&&"certificateCoverage".equals(c.field())||"DATE_VALIDITY".equals(c.type()))
            &&((c.expected()==null||cn.bidcheck.compat.Java8Api.isBlank(c.expected()))||ProjectInformation.usesOpeningDate(c));
        if(!signing&&!certificate)return c;
        String expected="DATE_VALIDITY".equals(c.type())&&!cn.bidcheck.compat.Java8Api.isBlank(opening)?opening.substring(0,10):opening;
        return new Checkpoint(c.id(),c.title(),c.category(),c.severity(),c.type(),c.field(),c.operator(),expected,c.unit(),c.required(),c.confirmed(),c.enabled(),c.source());
    }
    public Map<String,Object> run(String id) { return run(id,true); }
    public Map<String,Object> run(String id,boolean includeContent) {
        Map<String,Object> r=store.getMap("run",id); String project=text(r,"projectId");
        List<Finding> findings=store.list("finding",project,id,Finding.class);
        List<Map<String,Object>> resolutionJobs=store.maps("check_resolution",project,id);
        Map<String,Map<String,Object>> jobsByCheckpoint=new HashMap<>();
        for(Map<String,Object> job:resolutionJobs){Map<String,Object> display=new LinkedHashMap<>(job);
            if("FAILED".equals(r.get("status"))&&CheckResolution.ACTIVE.contains(text(display,"status"))){display.put("status","FAILED");display.put("stopReason","本轮执行中断，可恢复已保存的步骤。");}
            jobsByCheckpoint.put(text(job,"checkpointId"),display);}
        findings=cn.bidcheck.compat.Java8Api.toList(findings.stream().map(f->CheckResolution.attach(f,jobsByCheckpoint.get(f.checkpoint().id()))));
        Map<String,Integer> order=new LinkedHashMap<>();
        for(Checkpoint checkpoint:store.list("snapshot",project,id,Checkpoint.class))order.putIfAbsent(checkpoint.id(),order.size());
        findings=new ArrayList<>(findings);
        findings.sort(Comparator.comparingInt((Finding f)->order.getOrDefault(f.checkpoint().id(),Integer.MAX_VALUE)).thenComparing(Finding::id));
        List<Map<String,Object>> summaries=store.documentSummaries(project);
        String bidId=text(r,"documentId"),tenderId=text(r,"tenderDocumentId");
        Set<String> legacyTenderIds=new HashSet<>();
        if(cn.bidcheck.compat.Java8Api.isBlank(tenderId)) for(Finding finding:findings) if(finding.checkpoint().source()!=null) legacyTenderIds.add(finding.checkpoint().source().documentId());
        List<Document> scope=new ArrayList<>();
        for(Map<String,Object> summary:summaries) {
            String documentId=text(summary,"id"),role=text(summary,"role");
            if(project.equals(text(summary,"projectId")) && (("BID".equals(role)&&bidId.equals(documentId))
                    ||("TENDER".equals(role)&&(tenderId.equals(documentId)||(cn.bidcheck.compat.Java8Api.isBlank(tenderId)&&legacyTenderIds.contains(documentId)))))) scope.add(document(documentId));
        }
        // Use this run's frozen tender version, never the project's newer active document.
        // Legacy runs with ambiguous tender versions retain the conservative grouping path.
        List<Document> groupingTenders=cn.bidcheck.compat.Java8Api.toList(scope.stream().filter(d->"TENDER".equals(d.role())));
        Document groupingTender=groupingTenders.size()==1?groupingTenders.get(0):null;
        Set<String> savedCheckpoints=findings.stream().map(f->f.checkpoint().id()).collect(java.util.stream.Collectors.toSet());
        int missingRecords=(int)order.keySet().stream().filter(cp->!savedCheckpoints.contains(cp)).count();
        Map<String,Object> publishedResultVersion=publishedTemplateResultVersion(id,"current");
        return fields("run",r,"results",findings,"publishedResultVersion",publishedResultVersion,"resolutionSummary",CheckResolution.summary(r,findings,resolutionJobs,resolutionPolicyIdentity(),missingRecords),"inheritedReviews",r.getOrDefault("priorReviewRecords",cn.bidcheck.compat.Java8Api.listOf()),"reviews",cn.bidcheck.compat.Java8Api.toList(store.maps("review",project,null).stream().filter(v->id.equals(v.get("runId"))).sorted(Comparator.comparing(v->Instant.parse(text(v,"createdAt"))))),"documents",includeContent?store.list("document",project,null,Document.class):summaries,
            "ocrContext",reportOcrContext(findings,scope,r),"consistency",consistencyReport(id),"businessGroups",BusinessObligationGroups.summarize(findings,groupingTender),
            "summary",cn.bidcheck.semantic.OnDemandSummary.summarize(r,findings,r.get("onDemandCheckpointCount") instanceof Number?((Number) (r.get("onDemandCheckpointCount"))).intValue():
                (int)store.list("snapshot",project,id,Checkpoint.class).stream().filter(c->"SEMANTIC_RESPONSE".equals(c.type())).count()));
    }

    /**
     * Resolves an immutable published template-result version without replacing the source run.
     * "source" returns no overlay, while "current" follows the run-scoped immutable head.
     */
    public Map<String,Object> publishedTemplateResultVersion(String runId,String selector) {
        Map<String,Object> run=store.getMap("run",runId);String projectId=text(run,"projectId");
        String requested=selector==null?"source":selector.trim();
        if(cn.bidcheck.compat.Java8Api.isBlank(requested)||"source".equalsIgnoreCase(requested))return cn.bidcheck.compat.Java8Api.mapOf();
        String versionId=requested;
        if("current".equalsIgnoreCase(requested)){
            Stored head=store.mapper().get(cn.bidcheck.checktemplate.incremental.TemplateIncrementalPublicationService.headId(runId));
            if(head==null)return cn.bidcheck.compat.Java8Api.mapOf();
            if(!cn.bidcheck.checktemplate.incremental.TemplateIncrementalPublicationService.RESULT_HEAD_KIND.equals(head.kind)
                ||!projectId.equals(head.projectId)||!runId.equals(head.parentId))throw new IllegalStateException("RESULT_VERSION_HEAD_SCOPE_MISMATCH");
            versionId=text(store.map(head.body),"currentVersionId");if(cn.bidcheck.compat.Java8Api.isBlank(versionId))return cn.bidcheck.compat.Java8Api.mapOf();
        }
        Stored version=store.mapper().get(versionId);
        if(version==null||!cn.bidcheck.checktemplate.incremental.TemplateIncrementalPublicationService.RESULT_VERSION_KIND.equals(version.kind))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"结果版本不存在");
        if(!projectId.equals(version.projectId)||!runId.equals(version.parentId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"结果版本不属于该检查");
        return store.map(version.body);
    }
    private Map<String,Object> reportOcrContext(List<Finding> findings,List<Document> documents,Map<String,Object> run) {
        Map<String,Object> context=new LinkedHashMap<>(cn.bidcheck.engine.OcrPresentation.context(findings,documents));
        List<Map<String,Object>> coverage=ConsistencyRunSupport.maps(context.get("documents"));
        for(Map<String,Object> item:coverage) for(Map<String,Object> snapshot:ConsistencyRunSupport.maps(run.get("processingCoverage"))) {
            if(text(item,"documentId").equals(text(snapshot,"documentId"))) item.put("processing",snapshot);
        }
        context.put("documents",coverage);
        List<Map<String,Object>> attempts=ConsistencyRunSupport.maps(run.get("retrievalPreparation"));
        if(!attempts.isEmpty()) context.put("retrieval",attempts.get(attempts.size()-1));
        return context;
    }
    private Map<String,Object> processingCoverageSnapshot(Document document) {
        Stored row=store.mapper().get(DocumentProcessingSession.summaryId(document.id()));
        if(row==null||!document.projectId().equals(row.projectId)) return cn.bidcheck.compat.Java8Api.mapOf();
        Map<String,Object> saved=store.map(row.body);
        if(!document.id().equals(text(saved,"documentId"))||!document.sha256().equals(text(saved,"documentHash"))
            ||!(saved.get("documentVersion") instanceof Number)||((Number) (saved.get("documentVersion"))).intValue()!=document.version()) return cn.bidcheck.compat.Java8Api.mapOf();
        Map<String,Object> snapshot=new LinkedHashMap<>();
        for(String key:cn.bidcheck.compat.Java8Api.listOf("documentId", "documentHash", "documentVersion", "identity", "scope", "checkScope", "version", "processingMode", "visualAnalysisStatus", "automaticVisualRequested", "status", "parsingComplete", "processedPages", "incompletePages", "visualCalls", "contextWindows", "contextPending", "contextReviewRequired", "contextCompleted", "contextNoFacts", "contextNoFactPages", "contextNoFactPageCount", "contextLimitedPages", "contextLimitedPageCount", "contextCoverageLimitations", "pageReviewRequired", "visualBudget", "visualDeferredForOcr", "visualDeferralReason", "updatedAt"))
            if(saved.containsKey(key)) snapshot.put(key,saved.get(key));
        return snapshot;
    }
    private Map<String,Object> consistencyReport(String runId) {
        Stored report=store.mapper().get(ConsistencyRunSupport.reportId(runId));
        if(report==null||!"consistency_report".equals(report.kind))return cn.bidcheck.compat.Java8Api.mapOf();
        Map<String,Object> value=store.map(report.body);
        if(Boolean.TRUE.equals(value.get("reviewSnapshotComplete")))return value;
        // During a long review, persist only the changed group, not the entire occurrence corpus.
        Map<String,Map<String,Object>> deltas=new HashMap<>();Map<String,Object> latest=cn.bidcheck.compat.Java8Api.mapOf();
        for(Map<String,Object> delta:store.maps("consistency_review_group",report.projectId,runId)){
            if(!Objects.equals(value.get("documentId"),delta.get("documentId"))||!Objects.equals(value.get("documentHash"),delta.get("documentHash")))continue;
            deltas.put(text(delta,"groupId"),delta);
            if(text(delta,"updatedAt").compareTo(text(latest,"updatedAt"))>0)latest=delta;
        }
        if(!deltas.isEmpty()){
            List<Map<String,Object>> groups=new ArrayList<>();
            for(Map<String,Object> original:CheckResolution.maps(value.get("groups"))){Map<String,Object> group=new LinkedHashMap<>(original);
                Map<String,Object> delta=deltas.get(text(group,"id"));if(delta!=null){group.put("targetedReview",delta.get("targetedReview"));if(delta.containsKey("derivedConclusion"))group.put("derivedConclusion",delta.get("derivedConclusion"));}groups.add(group);}
            value.put("groups",groups);if(latest.containsKey("doubtReview"))value.put("doubtReview",latest.get("doubtReview"));
        }
        return value;
    }
    public Map<String,Object> review(String resultId,Map<String,Object> body) {
        Stored row=store.require("finding",resultId); Finding finding=store.decode(row.body,Finding.class);
        String status=text(body,"status"),note=text(body,"note"),reviewer=text(body,"reviewer");
        if(!STATUSES.contains(status)||cn.bidcheck.compat.Java8Api.isBlank(note)||note.length()>3000||reviewer.length()>80) throw bad("请选择有效结论，并填写复核说明（不超过3000字）");
        if(cn.bidcheck.compat.Java8Api.isBlank(reviewer)) reviewer="本机复核员";
        String id=Store.id(); Map<String,Object> review=fields("id",id,"resultId",resultId,"runId",finding.runId(),"status",status,"note",note,"reviewer",reviewer,"createdAt",now());
        store.save("review",id,row.projectId,resultId,review); return review;
    }
    public TaskRow retry(String id) {
        return tx.execute(s->{
            TaskRow task=task(id);
            if(!"FAILED".equals(task.status)) throw bad("仅失败任务可以重试");
            if(store.mapper().retryTask(id,now())!=1) throw bad("任务状态已改变或已达到3次尝试，请刷新后重试");
            TaskRow reset=task(id);
            if("CHECK".equals(reset.taskType)) updateRun(reset,"PENDING",0,"等待重试",null);
            if("VERIFY".equals(reset.taskType) && verification!=null) verification.getObject().taskState(reset,"PENDING",null);
            return reset;
        });
    }
    public void exhaust(TaskRow task) {
        tx.executeWithoutResult(s->{
            if(store.mapper().failExhaustedTask(task.id,now())==1) {
                if("PARSE".equals(task.taskType))updateSupplementParse(text(store.map(task.inputJson),"documentId"),task.projectId,"FAILED","解析任务多次中断，已达到重试上限，请查看任务记录");
                if("CHECK".equals(task.taskType)) updateRun(task,"FAILED",task.progress,"处理失败","任务多次中断，已达到3次执行上限，请重新发起检查");
                if("VERIFY".equals(task.taskType) && verification!=null) verification.getObject().taskState(task,"FAILED","任务多次中断，请重试未完成步骤");
            }
        });
    }
    private void scoped(Document d,String project,String role) {
        store.require("project",project);
        if(!project.equals(d.projectId())||!role.equals(d.role())) throw bad("该文件不属于当前项目或类型不符");
    }
    public void execute(TaskRow task) throws Exception {
        Map<String,Object> input=store.map(task.inputJson);
        switch (task.taskType) {case "PARSE":{String documentId=text(input,"documentId");updateSupplementParse(documentId,task.projectId,"PARSING",null);try{parse(task,documentId);}catch(Exception failure){updateSupplementParse(documentId,task.projectId,"FAILED",failure.getClass().getSimpleName()+": "+Objects.toString(failure.getMessage(),"解析失败"));throw failure;}} break;case "EXTRACT":extractTask(task,text(input,"documentId")); break;case "CHECK":checkTask(task,text(input,"runId"),text(input,"documentId")); break;case "VERIFY":verification.getObject().execute(task); break;case "FACT_MIGRATE":migrateFactsTask(task); break;case "PROCESS":{
                if(!processingIdentity().equals(text(input,"processingIdentity")))throw bad("处理配置已变化，请重新发起继续处理");
                Document doc=document(text(input,"documentId"));if(!doc.projectId().equals(task.projectId))throw bad("资料不属于当前项目");
                progress(task,10,automaticVisualProcessingEnabled?"继续跨页理解与增量检查，复用已保存文字":"整理已保存文字与页码，更新文字处理记录");
                cn.bidcheck.service.DocumentProcessingSession session=processingSession(task,doc,ExtractionPlan.full(),"",readCheckpoints(input.get("checks")));
                session.finish(doc,path(doc.id()),processingVisual,Math.max(1,processingMaxVisualCalls),true,processingFullVisualHardLimit);
                tx.executeWithoutResult(s->{progress(task,95,"保存继续处理结果");finish(task,automaticVisualProcessingEnabled?"增量处理结束，未完成材料及疑点请查看处理记录":"文字处理结束；图片和签章核验未请求，请在正式检查中核对全文上下文");});
            } break;default:throw new IllegalStateException("未知任务类型");}
    }
    private void migrateFactsTask(TaskRow task) {
        String key="fact-migration:"+task.id;Stored prior=store.mapper().get(key);
        Map<String,Object> state=prior==null?fields("taskId",task.id,"projectId",task.projectId,"storage","RELATIONAL_V3","nativeDocuments",0,"scanned",0,"migrated",0,"alreadyPresent",0,"rejected",0,"errors",cn.bidcheck.compat.Java8Api.mapOf(),"cursor","","status","RUNNING"):store.map(prior.body);
        List<String> ids;
        if(state.get("documentIds") instanceof List<?>)ids=cn.bidcheck.compat.Java8Api.toList(((List<?>) (state.get("documentIds"))).stream().map(Object::toString));
        else {ids=cn.bidcheck.compat.Java8Api.toList(store.documentSummaries(task.projectId).stream().map(d->text(d,"id")));if(ids.size()>2000)throw bad("迁移资料超过单次范围，请按项目分批处理");state.put("documentIds",ids);}
        int already=((Number)state.get("nativeDocuments")).intValue();
        for(int index=already;index<ids.size();index++){
            String id=ids.get(index);int done=index+1;
            tx.executeWithoutResult(s->{progress(task,Math.min(70,done*70/Math.max(1,ids.size())),"迁移原生事实 "+done+" / "+ids.size());store.lock("project",task.projectId);
                facts(id);state.put("nativeDocuments",done);store.save("fact_migration",key,task.projectId,task.id,state);
            });
        }
        boolean more=true;
        while(more){
            Map<String,Object> batch=tx.execute(s->{progress(task,75,"校验并迁移历史来源事实");store.lock("project",task.projectId);
                Map<String,Object> result=factLedger().migrateLegacyOccurrences(task.projectId,100,text(state,"cursor"));
                for(String counter:cn.bidcheck.compat.Java8Api.listOf("scanned", "migrated", "alreadyPresent", "rejected"))state.put(counter,((Number)state.get(counter)).intValue()+((Number)result.get(counter)).intValue());
                Map<String,Object> errors=new LinkedHashMap<>();if(state.get("errors") instanceof Map<?,?>)((Map<?,?>) (state.get("errors"))).forEach((code,count)->errors.put(code.toString(),count));
                if(result.get("errors") instanceof Map<?,?>)((Map<?,?>) (result.get("errors"))).forEach((code,count)->errors.merge(code.toString(),count,(a,b)->((Number)a).intValue()+((Number)b).intValue()));
                state.put("errors",errors);state.put("cursor",result.get("nextCursor"));state.put("hasMore",result.get("hasMore"));state.put("adoptedForRun",false);
                store.save("fact_migration",key,task.projectId,task.id,state);return result;
            });
            more=Boolean.TRUE.equals(batch.get("hasMore"));
        }
        tx.executeWithoutResult(s->{progress(task,95,"核对事实迁移记录");state.put("status","COMPLETED");state.put("finishedAt",now());store.save("fact_migration",key,task.projectId,task.id,state);finish(task,"事实关系表迁移完成，拒绝旧记录 "+state.get("rejected")+" 条");});
    }
    private void parse(TaskRow task,String id) throws Exception {
        Map<String,Object> u=store.getMap("upload","upload:"+id);
        if(Boolean.TRUE.equals(u.get("requireOcr"))&&(!ocrEnabled()||!activeOcrIdentity().equals(text(u,"ocrIdentity")))) throw bad("OCR配置已变更，请重新发起识别");
        if(u.containsKey("ocrPlanHash")&&!activeOcrIdentity().equals(text(u,"ocrIdentity")))throw bad("OCR配置已变更，请重新发起解析，避免混用识别范围");
        if(u.containsKey("preparationIdentity")&&!ocrPreparationIdentity().equals(text(u,"preparationIdentity")))throw bad("识别策略或预算已变更，请重新发起解析");
        progress(task,10,"解析文档结构");
        // Parser callbacks fence final writes, while the worker independently renews/checks leases.
        // Throttle UI progress writes so thousands of Word blocks do not cause thousands of DB round trips.
        long[] lastUpdate={0L};
        List<Checkpoint> frozenChecks=readCheckpoints(u.get("ocrPlanCheckpoints"));
        ExtractionPlan plan=Boolean.TRUE.equals(u.get("requireOcr"))?ExtractionPlan.full():initialPlan(text(u,"role"),text(u,"name"),frozenChecks);
        if(u.containsKey("ocrPlanHash")&&!plan.hash().equals(text(u,"ocrPlanHash")))throw bad("识别计划版本已变更，请重新上传或发起识别");
        Document preparing=new Document(id,task.projectId,text(u,"role"),text(u,"name"),((Number)u.get("version")).intValue(),text(u,"sha256"),text(u,"createdAt"),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.service.DocumentProcessingSession processing=processingSession(task,preparing,plan,text(u,"reprocessOf"),frozenChecks);
        cn.bidcheck.document.ExtractionResult extraction=parser.parsePlanned(path(id),text(u,"name"),(percent,stage)->{
            long tick=System.nanoTime();
            if(percent>=100 || tick-lastUpdate[0]>=1_000_000_000L) {
                progress(task,10+Math.max(0,Math.min(100,percent))/2,stage);lastUpdate[0]=tick;
            }
        },plan,processing);
        ParsedDocument parsed=extraction.document();
        progress(task,65,"提取可追溯事实");
        Document d=new Document(id,task.projectId,text(u,"role"),text(u,"name"),((Number)u.get("version")).intValue(),text(u,"sha256"),text(u,"createdAt"),parsed.pages(),parsed.warnings());
        progress(task,65,automaticVisualProcessingEnabled?"关联跨页材料并更新增量检查":"整理已提取文字、表格与页码");
        processing.finish(d,path(id),processingVisual,Math.max(1,processingMaxVisualCalls),plan.mode()==ExtractionPlan.Mode.FULL,processingFullVisualHardLimit);
        List<Fact> facts=factExtractor.extract(d);
        tx.executeWithoutResult(s->{
            Map<String,Object> project=store.map(store.lock("project",task.projectId).body);
            store.save("document",id,task.projectId,"",d);
            saveOcrPlan(d,plan,extraction.report(),"");
            replaceFacts(d,facts);
            if("TENDER".equals(d.role())&&(!project.containsKey("requestedInformationTenderId")||id.equals(text(project,"requestedInformationTenderId")))) {
                ProjectInformation.apply(d,project);store.save("project",task.projectId,task.projectId,"",project);
            }
            finish(task,"文件解析完成"+(parsed.warnings().isEmpty()?"":"，部分内容需人工核实"));
        });
        completeSupplements(id,task.projectId);
    }
    private void extractTask(TaskRow task,String id) {
        Document d=document(id); scoped(d,task.projectId,"TENDER"); progress(task,20,"提取招标检查项");
        List<Checkpoint> candidates=extractor.extract(d);
        cn.bidcheck.checktemplate.mapping.RequirementMappingSnapshot mapping=null;String mappingWarning="";
        if(requirementMappings!=null)try{mapping=requirementMappings.analyze(d);}catch(RuntimeException failure){mappingWarning="条款模板覆盖分析未完成，可在检查项页面重试";}
        cn.bidcheck.checktemplate.mapping.RequirementMappingSnapshot preparedMapping=mapping;java.lang.String preparedMappingWarning=mappingWarning;
        tx.executeWithoutResult(s->{
            progress(task,80,"保存候选检查项");
            Map<String,Object> project=store.map(store.lock("project",task.projectId).body);
            if(project.containsKey("requestedTenderId")&&!id.equals(text(project,"requestedTenderId"))) { finish(task,"已被更新的招标文件选择替代"); return; }
            project.put("activeTenderId",id);project.put("checkpointRuleVersion",RULE_VERSION);
            project.put("checkpointExtractionVersion",CheckpointExtractor.VERSION);
            Map<String,Object> versions=new LinkedHashMap<>();
            if(project.get("checkpointRuleVersions") instanceof Map<?,?>)((Map<?,?>) (project.get("checkpointRuleVersions"))).forEach((key,value)->versions.put(key.toString(),value));
            versions.put(id,RULE_VERSION);project.put("checkpointRuleVersions",versions);
            cn.bidcheck.service.CheckpointReconciler.Outcome outcome=new CheckpointReconciler(store).reconcile(task.projectId,id,candidates,RULE_VERSION);
            if(checkSpecifications!=null)checkSpecifications.ensureLegacyDrafts(task.projectId,id,activeCheckpoints(task.projectId,project));
            if(preparedMapping!=null){requirementMappings.persist(preparedMapping);project.remove("requirementMappingWarning");}
            else if(!cn.bidcheck.compat.Java8Api.isBlank(preparedMappingWarning))project.put("requirementMappingWarning",preparedMappingWarning);
            project.put("checkpointExtractionWarnings",outcome.warnings());
            ProjectInformation.apply(d,project);store.save("project",task.projectId,task.projectId,"",project);
            finish(task,"当前共"+outcome.count()+"个检查项；新增或规则变更项请人工确认，已保存的调整保留");
        });
    }
    private void checkTask(TaskRow task,String runId,String documentId) throws IOException {
        long checkStarted=System.nanoTime();
        LOG.info("bidcheck.check_run taskId={} runId={} attempt={} state=STARTED",task.id,runId,task.attempt);
        Map<String,Object> frozen=store.getMap("run",runId);
        String reviewMode=CheckReviewMode.of(frozen);requireReviewMode(reviewMode);
        if(!CheckReviewMode.VERSION.equals(text(frozen,"reviewModeVersion")))throw bad("检查编排模式已升级，请重新发起检查，避免旧队列按新策略继续执行");
        if(!RULE_VERSION.equals(text(frozen,"ruleVersion"))) throw new IllegalArgumentException("检查引擎已升级，请重新发起检查，历史结果保持原样");
        if(frozen.containsKey("semanticConfiguration")&&!semantic.identity().equals(frozen.get("semanticConfiguration"))) throw new IllegalArgumentException("语义服务配置已变更，请新建检查任务，避免混用不同模型的结论");
        if(!ocrPreparationIdentity().equals(text(frozen,"ocrPreparationIdentity")))throw bad("OCR配置、识别计划或预算已变更，请新建检查任务");
        if(!checkMode().equals(text(frozen,"checkMode"))||!onDemandPolicyVersion().equals(text(frozen,"onDemandPolicyVersion")))throw bad("检查模式或疑点策略已变更，请新建检查，历史快照保持原样");
        if(!consistencyIdentity().equals(text(frozen,"consistencyIdentity")))throw bad("全文一致性检查配置已变更，请重新发起检查");
        if(!focusedCheckIdentity().equals(text(frozen,"focusedCheckIdentity")))throw bad("人员、证书及报价检查已升级，请新建检查，历史结果保持原样");
        if(Boolean.TRUE.equals(frozen.get("templateRouteEnabled"))&&frozen.containsKey("templateCheckIdentity")&&!templateCheckIdentity().equals(text(frozen,"templateCheckIdentity")))throw bad("固定检查模板或判定器已升级，请新建检查，历史结果保持原样");
        if(frozen.containsKey("resolutionPolicyIdentity")&&!resolutionPolicyIdentity().equals(text(frozen,"resolutionPolicyIdentity")))throw bad("补查规则或原图服务配置已变化，请新建检查，避免混用不同版本的证据");
        List<Checkpoint> checkpoints=store.list("snapshot",task.projectId,runId,Checkpoint.class);
        Document original=document(text(frozen,"documentId"));scoped(original,task.projectId,"BID");
        long ocrStarted=System.nanoTime();
        Document d=Boolean.TRUE.equals(frozen.get("ocrPreparationComplete"))?original:preparePlannedDocument(task,original,checkpoints);
        checkPhase(task,runId,"OCR_PREPARATION",ocrStarted,d.pages().size());
        tx.executeWithoutResult(s->{progress(task,4,"检查计划所需识别已结束");Map<String,Object> current=store.getMap("run",runId);
            current.putIfAbsent("inputDocumentId",documentId);current.put("documentId",d.id());current.put("documentVersion",d.version());current.put("ocrPreparationComplete",true);current.put("ocrPlan",ocrPlan(d.id()));store.save("run",runId,task.projectId,d.id(),current);});
        String tenderId=text(frozen,"tenderDocumentId");
        Document tender=cn.bidcheck.compat.Java8Api.isBlank(tenderId)?null:document(tenderId);
        tx.executeWithoutResult(s->{
            progress(task,4,"记录本次文档处理覆盖范围");
            Map<String,Object> current=store.getMap("run",runId);
            if(!current.containsKey("processingCoverage")) {
                List<Map<String,Object>> snapshots=new ArrayList<>();
                snapshots.add(processingCoverageSnapshot(d));if(tender!=null)snapshots.add(processingCoverageSnapshot(tender));
                current.put("processingCoverage",cn.bidcheck.compat.Java8Api.toList(snapshots.stream().filter(v->!v.isEmpty())));
                store.save("run",runId,task.projectId,d.id(),current);
            }
        });
        List<Fact> facts=facts(d.id());
        tx.executeWithoutResult(s->{progress(task,5,"准备检查规则与事实"); updateRun(task,"RUNNING",5,task.stage,null);});
        // A check set whose every item is bound to a frozen fixed template has no dependency on
        // the legacy full-text consistency pipeline.  Running that pipeline used to rescan the
        // complete document, publish thousands of unrelated facts, prepare the vector index and
        // review derived doubts before the eight template items could execute.  Besides adding
        // minutes to a template-only run, those generated findings were outside the confirmed
        // check-set manifest.  Mixed/legacy runs retain the existing conservative baseline.
        boolean templateOnly=allCheckpointsUseFrozenTemplates(task.projectId,runId,checkpoints);
        boolean explicitlyTargeted=Boolean.TRUE.equals(frozen.get("explicitTargetedRun"));
        long consistencyStarted=System.nanoTime();
        Map<String,Object> consistency=templateOnly||explicitlyTargeted?cn.bidcheck.compat.Java8Api.mapOf():prepareConsistency(task,runId,d,tender,checkpoints);
        checkPhase(task,runId,explicitlyTargeted?"CONSISTENCY_SKIPPED_EXPLICIT_TARGETED_SCOPE":templateOnly?"CONSISTENCY_SKIPPED_TEMPLATE_ONLY":"CONSISTENCY_SCAN",consistencyStarted,ConsistencyRunSupport.maps(consistency.get("groups")).size());
        if(templateOnly)tx.executeWithoutResult(s->{String stage="固定模板按证据依赖执行，复用已保存文字与事实";
            progress(task,5,stage);updateRun(task,"RUNNING",5,stage,null);});
        if(!templateOnly&&("RULES_RETRIEVAL".equals(checkMode())||semantic.enabled()) && (checkpoints.stream().anyMatch(c->"SEMANTIC_RESPONSE".equals(c.type()))
            || "RULES_RETRIEVAL".equals(checkMode())&&!ConsistencyRunSupport.maps(consistency.get("groups")).isEmpty())) {
            List<Map<String,Object>> preparations=new ArrayList<>();
            for(int attempt=1;attempt<=("RULES_RETRIEVAL".equals(checkMode())?1:3);attempt++) {
                String stage="准备全文检索索引（第 "+attempt+" 次，已完成片段可复用）";
                tx.executeWithoutResult(s->{progress(task,5,stage);updateRun(task,"RUNNING",5,stage,null);});
                long started=System.nanoTime();
                cn.bidcheck.retrieval.RetrievalResult preparation=semantic.prepareRetrieval(d,metrics->{
                    String detail=stage+"："+Objects.toString(metrics.get("completedChunks"),"0")+" / "+Objects.toString(metrics.get("chunks"),"?")+" 段";
                    tx.executeWithoutResult(s->{progress(task,5,detail);updateRun(task,"RUNNING",5,detail,null);});
                });
                Map<String,Object> entry=new LinkedHashMap<>(preparation.diagnostics());
                entry.put("attempt",attempt);entry.put("mode",preparation.mode());entry.put("elapsedMillis",(System.nanoTime()-started)/1_000_000);
                LOG.info("bidcheck.check_phase taskId={} runId={} phase=RETRIEVAL_PREPARATION attempt={} mode={} durationMs={}",task.id,runId,attempt,preparation.mode(),entry.get("elapsedMillis"));
                preparations.add(entry);
                tx.executeWithoutResult(s->{
                    progress(task,5,stage);
                    Map<String,Object> run=store.getMap("run",runId);run.put("retrievalPreparation",cn.bidcheck.compat.Java8Api.listCopyOf(preparations));
                    store.save("run",runId,task.projectId,d.id(),run);
                });
                if(!Boolean.TRUE.equals(preparation.diagnostics().get("retryableFailure"))
                    &&!cn.bidcheck.compat.Java8Api.setOf("RETRIEVAL_TIMEOUT", "EMBEDDING_TIMEOUT").contains(text(preparation.diagnostics(),"failureCode"))) break;
            }
        }
        OnDemandModelBudget modelBudget=new OnDemandModelBudget(store,tx,task,runId);
        long consistencyReviewStarted=System.nanoTime();
        if("RULES_RETRIEVAL".equals(checkMode())&&!consistency.isEmpty()) {
            ConsistencyDoubtReviewer reviewer=consistencyDoubtReviewer==null
                ?new ConsistencyDoubtReviewer(new cn.bidcheck.retrieval.HybridRetriever(),semantic):consistencyDoubtReviewer;
            java.util.function.Consumer<Map<String,Object>> persistReview=group->{
                String groupId=text(group,"id");
                // Revalidate against this immutable document and every original group record. A
                // stored model receipt or a coverage boolean cannot itself authorize a conclusion.
                Evaluation derived=ConsistencyDerivedConclusion.recompute(d,fields("documentId",d.id(),"documentHash",d.sha256(),
                    "occurrences",consistency.getOrDefault("occurrences",cn.bidcheck.compat.Java8Api.listOf()),"groups",cn.bidcheck.compat.Java8Api.listOf(group),"confirmedEvidenceRelations",consistency.getOrDefault("confirmedEvidenceRelations",cn.bidcheck.compat.Java8Api.listOf()))).get(groupId);
                Map<String,Object> delta=fields("groupId",groupId,"documentId",d.id(),"documentHash",d.sha256(),"updatedAt",now(),
                    "targetedReview",group.get("targetedReview"),"derivedConclusion",group.getOrDefault("derivedConclusion",cn.bidcheck.compat.Java8Api.mapOf()),"doubtReview",consistency.getOrDefault("doubtReview",cn.bidcheck.compat.Java8Api.mapOf()));
                Store.PreparedRecord preparedGroup=store.prepareRecord("consistency_review_group",stableId(runId+":consistency-review:"+groupId),task.projectId,runId,delta);
                tx.executeWithoutResult(s->{
                String stage="全文一致性疑点复核："+text(group,"title");
                progress(task,5,stage);updateRun(task,"RUNNING",5,stage,null);
                String findingId=text(group,"findingId");
                if(!cn.bidcheck.compat.Java8Api.isBlank(findingId)) {
                    Finding originalFinding=store.get("finding",findingId,Finding.class);
                    Finding reviewed=reviewedConsistencyFinding(originalFinding,group,derived);
                    store.save("finding",findingId,task.projectId,runId,reviewed);
                    if(reviewed.assessment().containsKey("consistencyRecoveryExecution"))recordResolution(task,runId,reviewed);
                }
                store.savePrepared(preparedGroup);
                });
            };
            // Older interrupted recoveries can already have a receipt but lack the group's finding metadata.
            // Reconcile that saved receipt without repeating retrieval or model work.
            for(Map<String,Object> group:ConsistencyRunSupport.maps(consistency.get("groups"))){
                Map<String,Object> review=CheckResolution.map(group.get("targetedReview"));String findingId=text(group,"findingId");
                if(!ConsistencyDoubtReviewer.VERSION.equals(text(review,"policyVersion"))||"IN_PROGRESS".equals(text(review,"status"))||cn.bidcheck.compat.Java8Api.isBlank(findingId))continue;
                Finding saved=store.get("finding",findingId,Finding.class);
                Map<String,Object> savedAssessment=CheckResolution.map(saved.assessment());
                if("RECOVERY_PENDING".equals(saved.evaluation().method())||Boolean.TRUE.equals(CheckResolution.map(savedAssessment.get("inheritedFrom")).get("pending"))
                    ||!savedAssessment.containsKey("consistencyGroupId"))persistReview.accept(group);
            }
            String priorAuditRun=org.slf4j.MDC.get("verification.sourceRunId");
            try{org.slf4j.MDC.put("verification.sourceRunId",runId);reviewer.review(d,consistency,modelBudget::reserveConsistency,persistReview);}
            finally{if(priorAuditRun==null)org.slf4j.MDC.remove("verification.sourceRunId");else org.slf4j.MDC.put("verification.sourceRunId",priorAuditRun);}
            consistency.put("reviewSnapshotComplete",true);
            Store.PreparedRecord finalReviewReport=store.prepareRecord("consistency_report",ConsistencyRunSupport.reportId(runId),task.projectId,runId,consistency);
            tx.executeWithoutResult(s->{progress(task,5,"全文一致性定向复核记录已保存");
                store.savePrepared(finalReviewReport);});
        }
        checkPhase(task,runId,"CONSISTENCY_REVIEW",consistencyReviewStarted,ConsistencyRunSupport.maps(consistency.get("groups")).size());
        tx.executeWithoutResult(s->{progress(task,5,"开始执行检查项：规则先行，相关疑点按需核验");updateRun(task,"RUNNING",5,task.stage,null);});
        long checkpointsStarted=System.nanoTime();
        // Snapshot existing results on the task thread. Worker threads only evaluate immutable
        // input; progress, lease fencing and result persistence stay on the original task thread.
        boolean[] existing=new boolean[checkpoints.size()];
        for(int i=0;i<checkpoints.size();i++) {
            Stored previous=store.mapper().get(stableId(runId+":"+checkpoints.get(i).id()));
            Stored job=store.mapper().get(CheckResolution.jobId(runId,checkpoints.get(i).id()));
            // A queued recovery must reach the engine even when an older undetermined finding exists.
            existing[i]=previous!=null&&!(job!=null&&"REEVALUATE_CHANGED_POLICY".equals(text(store.map(job.body),"strategy"))
                &&CheckResolution.ACTIVE.contains(text(store.map(job.body),"status")));
        }
        boolean focused="RULES_RETRIEVAL".equals(checkMode())&&focusedChecks!=null&&focusedContext!=null&&checkpoints.stream().anyMatch(c->focusedChecks.supports(c,reviewMode)&&!frozenTemplateRoute(task.projectId,runId,c));
        List<Map<String,Object>> atomicOccurrences=processingOccurrences(cn.bidcheck.compat.Java8Api.listOf(d));
        cn.bidcheck.verification.graph.FactLedgerEvidenceGraphBridge.PriorEvidenceReuse priorGraph=priorEvidenceGraph(task.projectId,runId,d);
        if(!priorGraph.occurrences().isEmpty()){
            LinkedHashMap<String,Map<String,Object>> combined=new LinkedHashMap<>();
            // Graph-selected relations are placed first so the bounded local-candidate adapter sees
            // them before unrelated document-wide occurrences. Every one is revalidated again by
            // SharedFactReuse/ProofVerifier against the selected current source window.
            for(Map<String,Object> occurrence:priorGraph.occurrences())combined.put(text(occurrence,"occurrenceId"),occurrence);
            for(Map<String,Object> occurrence:atomicOccurrences)combined.putIfAbsent(text(occurrence,"occurrenceId"),occurrence);
            atomicOccurrences=cn.bidcheck.compat.Java8Api.listCopyOf(combined.values());
        }
        Map<String,Object> graphAudit=priorGraph.audit();
        tx.executeWithoutResult(s->{Map<String,Object> current=store.getMap("run",runId);current.put("priorEvidenceGraphReuse",graphAudit);store.save("run",runId,task.projectId,d.id(),current);});
        java.util.function.Function<Checkpoint,Map<String,Object>> atomicContexts=focused
            ?focusedContext.prepare(task,runId,d,tender,focusedChecks.identity(),atomicOccurrences,modelBudget::reserve):cp->cn.bidcheck.compat.Java8Api.mapOf();
        AgentBusinessGroupReuse.Plan agentReuse=CheckReviewMode.AGENT.equals(reviewMode)
            ?AgentBusinessGroupReuse.plan(checkpoints,tender,cp->focused&&focusedChecks.supports(cp,reviewMode)
                &&!frozenTemplateRoute(task.projectId,runId,cp)):new AgentBusinessGroupReuse.Plan(cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.listOf());
        Map<String,CompletableFuture<Finding>> agentGroupLeaders=new ConcurrentHashMap<>();
        agentReuse.groups().forEach(group->agentGroupLeaders.put(group.groupId(),new CompletableFuture<>()));
        if(agentReuse.reusableGroupCount()>0)LOG.info("bidcheck.agent_group_plan runId={} groups={} followerCheckpoints={} policy={}",runId,
            agentReuse.reusableGroupCount(),agentReuse.reusedCheckpointCount(),AgentBusinessGroupReuse.VERSION);
        java.util.function.IntFunction<Finding> evaluate=i->{
            Checkpoint checkpoint=checkpoints.get(i);AgentBusinessGroupReuse.Membership membership=agentReuse.membership(checkpoint.id());
            CompletableFuture<Finding> leaderFuture=membership==null?null:agentGroupLeaders.get(membership.groupId());
            if(existing[i]){
                if(membership!=null&&membership.leader(checkpoint.id())){
                    Finding saved=store.get("finding",stableId(runId+":"+checkpoint.id()),Finding.class);leaderFuture.complete(saved);
                }
                return null;
            }
            Finding leader=null;
            if(membership!=null&&!membership.leader(checkpoint.id()))leader=awaitAgentGroupLeader(leaderFuture,runId,checkpoint.id());
            Map<String,Object> atomicContext=focused&&focusedChecks.supports(checkpoint,reviewMode)&&!frozenTemplateRoute(task.projectId,runId,checkpoint)
                ?new LinkedHashMap<>(atomicContexts.apply(checkpoint)):new LinkedHashMap<>();
            if(leader!=null){
                // The ledger alone does not identify which frozen source windows belong to this
                // equivalent obligation. Fan out the leader's source-scoped snapshot, then let
                // RecoveryReplay resolve and verify every source/fact against the follower's
                // current corpus before independently recomputing its own rule tree.
                List<Map<String,Object>> leaderSteps=store.maps("check_atomic_step",task.projectId,
                    stableId(runId+":atomic-steps:"+membership.leaderCheckpointId()));
                AgentBusinessGroupReuse.EvidenceReceipt evidenceReceipt=AgentBusinessGroupReuse.evidenceReceipt(
                    membership,checkpoint.id(),leader,leaderSteps);
                boolean receiptApplied=AgentBusinessGroupReuse.attach(evidenceReceipt,atomicContext);
                // TARGETED keeps requirement generation independent for the follower.  Setting
                // EVIDENCE_RECOMPUTE here would require an old follower plan and could turn a
                // first execution into RECOVERY_PLAN_MISSING.
                atomicContext.put("recoveryStrategy","RECOMPUTE_SHARED_EVIDENCE");atomicContext.put("recoveryMode","TARGETED");
                atomicContext.put("recoveryModelAllowed",false);
                Map<String,Object> reuse=new LinkedHashMap<>(membership.audit(checkpoint.id()));
                Map<String,Object> sourceModel=CheckResolution.map(CheckResolution.map(leader.assessment()).get("model"));
                reuse.put("sourceFindingId",leader.id());
                reuse.putAll(evidenceReceipt.audit());reuse.put("receiptApplied",receiptApplied);
                reuse.put("sourceAgentAttemptCount",CheckResolution.maps(sourceModel.get("agentAttempts")).size());
                reuse.put("newAgentInvestigation",false);atomicContext.put("agentEvidenceReuse",reuse);
                LOG.info("bidcheck.agent_group_reuse runId={} groupId={} leaderCheckpointId={} checkpointId={} sourceAgentAttempts={} receiptApplied={} validatedSources={} replayedFacts={} newAgentInvestigation=false",
                    runId,membership.groupId(),membership.leaderCheckpointId(),checkpoint.id(),reuse.get("sourceAgentAttemptCount"),receiptApplied,
                    reuse.get("validatedSourceCount"),reuse.get("replayedFactCount"));
            }
            try{
                Finding result=evaluateFinding(runId,checkpoint,d,tender,facts,consistency,modelBudget::reserve,atomicContext);
                if(membership!=null&&membership.leader(checkpoint.id()))leaderFuture.complete(result);
                return result;
            }catch(RuntimeException|Error failure){
                if(membership!=null&&membership.leader(checkpoint.id()))leaderFuture.completeExceptionally(failure);
                throw failure;
            }
        };
        int[] completed={0};
        java.util.function.BiConsumer<Integer,Finding> persist=(i,result)->{
            int done=completed[0]+1;
            int p=5+done*90/Math.max(1,checkpoints.size()); String stage="已检查 "+done+" / "+checkpoints.size();
            DbTransactionRetry.executeWithoutResult(tx,"CHECK_RESULT_PUBLISH",cn.bidcheck.compat.Java8Api.mapOf("taskId", task.id, "runId", runId, "checkpointId", checkpoints.get(i).id()),s->{
                progress(task,p,stage);
                if(result!=null) {Finding published=withPreviousReference(result);store.save("finding",published.id(),task.projectId,runId,published);recordResolution(task,runId,published);}
                updateRun(task,"RUNNING",p,task.stage,null);
            });
            completed[0]=done;
            LOG.info("bidcheck.checkpoint_publish taskId={} runId={} checkpointId={} completed={} total={} reused={}",task.id,runId,checkpoints.get(i).id(),done,checkpoints.size(),result==null);
        };
        if(checkpointExecutor==null) {
            for(int i=0;i<checkpoints.size();i++) persist.accept(i,evaluate.apply(i));
        } else checkpointExecutor.forEachCompleted(checkpoints.size(),evaluate,persist);
        checkPhase(task,runId,"CHECKPOINTS",checkpointsStarted,completed[0]);
        long resolutionStarted=System.nanoTime();
        continueResolution(task,runId,d,tender,facts,consistency,modelBudget,atomicContexts);
        checkPhase(task,runId,"RESOLUTION",resolutionStarted,store.maps("check_resolution",task.projectId,runId).size());
        if(focused)recomputeRequirementAggregates(task,runId,d,tender,facts,consistency,atomicContexts);
        publishTemplateDependencyGraph(task.projectId,runId);
        publishProjectEvidenceGraph(task.projectId,runId,d);
        tx.executeWithoutResult(s->{
            List<Finding> finalFindings=store.list("finding",task.projectId,runId,Finding.class);
            long unresolved=finalFindings.stream().filter(CheckResolution::unresolved).count();
            String stage=unresolved>0?"本轮执行结束，仍有 "+unresolved+" 条原始事项未形成完整结论":"本轮执行结束";
            // Keep the same task -> run lock order as concurrent evidence writers.
            finish(task,stage);updateRun(task,"COMPLETED",100,stage,null);
        });
        LOG.info("bidcheck.check_run taskId={} runId={} attempt={} state=COMPLETED checkpointCount={} durationMs={}",task.id,runId,task.attempt,checkpoints.size(),(System.nanoTime()-checkStarted)/1_000_000);
    }
    private static void checkPhase(TaskRow task,String runId,String phase,long started,int count){
        LOG.info("bidcheck.check_phase taskId={} runId={} phase={} state=COMPLETED count={} durationMs={}",task.id,runId,phase,count,(System.nanoTime()-started)/1_000_000);
    }
    private static Finding awaitAgentGroupLeader(CompletableFuture<Finding> leader,String runId,String checkpointId){
        if(leader==null)throw new IllegalStateException("AGENT_GROUP_LEADER_MISSING");
        try{return leader.get();}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new CancellationException("Agent 业务组复用等待已中断");}
        catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof RuntimeException)throw ((RuntimeException) (cause));if(cause instanceof Error)throw ((Error) (cause));
            throw new IllegalStateException("Agent 业务组首项执行失败: "+runId+":"+checkpointId,cause);}
    }
    /** Finish source-dependency summaries after children/recovery publish their final proofs. */
    private void recomputeRequirementAggregates(TaskRow task,String runId,Document bid,Document tender,List<Fact> facts,
            Map<String,Object> consistency,java.util.function.Function<Checkpoint,Map<String,Object>> contexts){
        long started=System.nanoTime();int recomputed=0;
        for(Finding before:store.list("finding",task.projectId,runId,Finding.class)){
            if(!cn.bidcheck.verification.engine.SourceRequirementInventory.aggregateCheckpoint(before.checkpoint())
                    ||frozenTemplateRoute(task.projectId,runId,before.checkpoint()))continue;
            Map<String,Object> context=RecoveryInputs.context(before,"QUERY_EXTERNAL",contexts.apply(before.checkpoint()));
            context.put("recoveryMode","EVIDENCE_RECOMPUTE");context.put("recoveryModelAllowed",false);
            context.put("recoveryStrategy","RECOMPUTE_REQUIREMENT_DEPENDENCIES");context.remove("recoveryTargetObligationIds");
            Finding after=evaluateFinding(runId,before.checkpoint(),bid,tender,facts,consistency,()->false,context);
            Map<String,Object> assessment=new LinkedHashMap<>(after.assessment());
            assessment.put("requirementDependencyRecomputation",fields("version",cn.bidcheck.verification.engine.SourceRequirementInventory.VERSION,
                    "revision",context.getOrDefault("requirementDependencyRevision",""),"newModelCalls",0,"sourceRunId",runId));
            Finding published=withPreviousReference(new Finding(after.id(),after.runId(),after.checkpoint(),after.evaluation(),assessment));
            DbTransactionRetry.executeWithoutResult(tx,"REQUIREMENT_DEPENDENCY_PUBLISH",cn.bidcheck.compat.Java8Api.mapOf("taskId", task.id, "runId", runId, "checkpointId", before.checkpoint().id()),s->{
                progress(task,97,"汇总关联事项的最终结果");store.save("finding",published.id(),task.projectId,runId,published);recordResolution(task,runId,published);
            });
            recomputed++;
            LOG.info("bidcheck.requirement_dependency runId={} checkpointId={} state=RECOMPUTED before={} after={} revision={} newModelCalls=0",
                    runId,before.checkpoint().id(),before.evaluation().status(),published.evaluation().status(),context.getOrDefault("requirementDependencyRevision",""));
        }
        checkPhase(task,runId,"REQUIREMENT_DEPENDENCIES",started,recomputed);
    }
    /** True only for an explicit, non-empty run snapshot fully covered by fixed templates. */
    boolean allCheckpointsUseFrozenTemplates(String projectId,String runId,List<Checkpoint> checkpoints){
        if(checkSpecifications==null||checkpoints==null||checkpoints.isEmpty())return false;
        Map<String,Object> run=store.getMap("run",runId),routing=CheckResolution.map(run.get("templateRouting"));
        if(CheckReviewMode.agent(run))return false;
        // Runs created before routing became an immutable part of the run contract keep the legacy
        // pipeline. Do not infer eligibility from today's bridge support or a mutable catalog.
        if(!Boolean.TRUE.equals(run.get("templateRouteEnabled"))||!routing.containsKey("templateCheckpointIds"))return false;
        Set<String> routed=new HashSet<>(stringList(routing.get("templateCheckpointIds")));
        return checkpoints.stream().allMatch(checkpoint->routed.contains(checkpoint.id())
            &&checkSpecifications.findSnapshotSpec(projectId,runId,checkpoint.id()).isPresent());
    }
    private void recordResolution(TaskRow task,String runId,Finding finding){
        OrdinaryRecoveryDependencies.bind(store,task.projectId,runId,finding);
        String id=CheckResolution.jobId(runId,finding.checkpoint().id());Stored saved=store.mapper().get(id);
        if(saved==null&&!CheckResolution.unresolved(finding))return;
        Map<String,Object> job=saved==null?new LinkedHashMap<>():store.map(saved.body);
        job.putAll(CheckResolution.plan(finding));job.put("id",id);job.put("runId",runId);job.put("checkpointId",finding.checkpoint().id());
        job.putIfAbsent("attempts",0);job.put("updatedAt",now());job.put("outcomeFingerprint",CheckResolution.outcomeFingerprint(finding));
        job.put("policyIdentity",resolutionPolicyIdentity());job.put("newEvidenceCount",0);
        job.put("evidenceNeeds",CheckResolution.maps(CheckResolution.atomic(finding).get("gaps")));
        if(CheckReviewMode.agent(store.getMap("run",runId))&&CheckReviewMode.eligible(finding.checkpoint())
                &&"WAIT_AGENT_INPUT_CHANGE".equals(text(job,"strategy"))){
            // Waiting for changed input is valid only after an explicit no-progress receipt.
            // Visual work, external queries and retryable technical failures keep the strategy
            // chosen by CheckResolution.plan and must remain executable.
            job.put("reviewMode",CheckReviewMode.AGENT);
            job.put("requiredRequirementRevisions",cn.bidcheck.verification.engine.RequirementRepairPolicy.versions());
        }
        if(!CheckResolution.ACTIVE.contains(text(job,"status"))&&!"SUCCEEDED".equals(text(job,"status")))job.put("stopReason",job.get("reason"));else job.remove("stopReason");
        store.save("check_resolution",id,task.projectId,runId,job);
    }
    private Finding withPreviousReference(Finding finding){
        Stored stored=store.mapper().get(finding.id());if(stored==null)return finding;
        Finding previous=store.decode(stored.body,Finding.class);Object reference=CheckResolution.map(previous.assessment()).get("inheritedFrom");
        if(reference==null)return finding;Map<String,Object> a=new LinkedHashMap<>(CheckResolution.map(finding.assessment()));
        Map<String,Object> completedReference=new LinkedHashMap<>(CheckResolution.map(reference));completedReference.put("pending",false);completedReference.put("rechecked",true);completedReference.put("reusedConclusion",false);
        a.put("inheritedFrom",completedReference);
        return new Finding(finding.id(),finding.runId(),finding.checkpoint(),finding.evaluation(),a);
    }
    /** Each distinct strategy/input is attempted once; progress can schedule a different missing-field strategy. */
    private void continueResolution(TaskRow task,String runId,Document bid,Document tender,List<Fact> facts,Map<String,Object> consistency,
            OnDemandModelBudget budget,java.util.function.Function<Checkpoint,Map<String,Object>> contexts){
        boolean agentOwned=CheckReviewMode.agent(store.getMap("run",runId));
        List<Checkpoint> recoveryCheckpoints=store.list("snapshot",task.projectId,runId,Checkpoint.class);
        if(recoveryCheckpoints.isEmpty())recoveryCheckpoints=cn.bidcheck.compat.Java8Api.toList(store.list("finding",task.projectId,runId,Finding.class).stream().map(Finding::checkpoint));
        AgentBusinessGroupReuse.Plan recoveryGroups=agentOwned&&focusedChecks!=null
            ?AgentBusinessGroupReuse.plan(recoveryCheckpoints,tender,cp->focusedChecks.supports(cp,CheckReviewMode.AGENT)&&!frozenTemplateRoute(task.projectId,runId,cp))
            :new AgentBusinessGroupReuse.Plan(cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.listOf());
        tx.executeWithoutResult(s->{progress(task,96,"整理未完成义务与补查任务");
            for(Finding f:store.list("finding",task.projectId,runId,Finding.class))if(CheckResolution.unresolved(f)
                &&store.mapper().get(CheckResolution.jobId(runId,f.checkpoint().id()))==null)recordResolution(task,runId,f);});
        while(true){
            tx.executeWithoutResult(s->{progress(task,96,"核对新增证据影响的检查条件");OrdinaryRecoveryDependencies.schedule(store,task.projectId,runId);});
            List<Map<String,Object>> queued=cn.bidcheck.compat.Java8Api.toList(store.maps("check_resolution",task.projectId,runId).stream().filter(j->CheckResolution.ACTIVE.contains(text(j,"status"))));
            if(queued.isEmpty())break;
            for(Map<String,Object> pending:queued){
                String checkpointId=text(pending,"checkpointId"),jobId=text(pending,"id");
                Finding before=store.get("finding",stableId(runId+":"+checkpointId),Finding.class);
                String strategy=text(pending,"strategy");
                AgentBusinessGroupReuse.Membership recoveryGroup=recoveryGroups.membership(checkpointId);
                AgentGroupRecovery.Outcome groupRecovery=null;
                if(recoveryGroup!=null){
                    List<Finding> members=new ArrayList<>();for(String memberId:recoveryGroup.checkpointIds()){
                        Stored savedMember=store.mapper().get(stableId(runId+":"+memberId));if(savedMember!=null)members.add(store.decode(savedMember.body,Finding.class));
                    }
                    groupRecovery=AgentGroupRecovery.execute(before,recoveryGroup,members,
                        memberId->store.maps("check_atomic_step",task.projectId,stableId(runId+":atomic-steps:"+memberId)),
                        contexts.apply(before.checkpoint()),resolutionPolicyIdentity(),
                        local->evaluateFinding(runId,before.checkpoint(),bid,tender,facts,consistency,()->false,local));
                    AgentGroupRecovery.Outcome reused=groupRecovery;
                    if(reused.attempted())tx.executeWithoutResult(s->{
                        progress(task,96,"复用同事项证据，完成本地核验");
                        if(reused.published()){
                            Finding published=withPreviousReference(reused.finding());store.save("finding",published.id(),task.projectId,runId,published);recordResolution(task,runId,published);
                        }
                        // Consumption and a proved conclusion commit together; a restart cannot
                        // lose a conclusion after recording its evidence as already consumed.
                        String parent=stableId(runId+":atomic-steps:"+checkpointId);Map<String,Object> marker=new LinkedHashMap<>(reused.consumedStep());
                        marker.put("runId",runId);marker.put("savedAt",now());
                        store.save("check_atomic_step",stableId(parent+":"+text(marker,"id")+":"+text(marker,"status")),task.projectId,parent,marker);
                    });
                    LOG.info("bidcheck.agent_group_recovery_reuse requestId={} runId={} checkpointId={} groupId={} availableReceipts={} consumedReceipts={} newReceipts={} recomputed={} published={} durationMs={} localOnly=true",
                        task.id,runId,checkpointId,recoveryGroup.groupId(),reused.audit().get("availableReceiptCount"),reused.audit().get("consumedReceiptCount"),
                        reused.audit().get("newReceiptCount"),reused.attempted(),reused.published(),reused.audit().getOrDefault("durationMs",0));
                    if(reused.published())continue;
                }
                if(agentOwned&&CheckReviewMode.eligible(before.checkpoint())&&!CheckReviewMode.changedEvidence(pending)
                        &&(groupRecovery==null||!groupRecovery.attempted())
                        &&("WAIT_AGENT_INPUT_CHANGE".equals(strategy)||Boolean.TRUE.equals(pending.get("requiresInputChange")))){
                    tx.executeWithoutResult(s->{Map<String,Object> job=new LinkedHashMap<>(pending);job.putAll(fields("status","BLOCKED_NO_PROGRESS","strategy","WAIT_AGENT_INPUT_CHANGE",
                        "requiredRequirementRevisions",cn.bidcheck.verification.engine.RequirementRepairPolicy.versions(),
                        "stopReason","本项由 Agent 管理调查；没有新的相关证据，未进入旧补查循环。","updatedAt",now()));store.save("check_resolution",jobId,task.projectId,runId,job);});
                    LOG.info("bidcheck.agent_review_recovery_gate runId={} checkpointId={} allowed=false reason=NO_NEW_RELEVANT_EVIDENCE",runId,checkpointId);continue;
                }
                // Generated consistency groups are recovered by their object-aware reviewer above.
                if(CheckResolution.map(before.assessment()).containsKey("consistencyGroupId")||!cn.bidcheck.compat.Java8Api.setOf("VERIFY_SOURCE_PAGES", "QUERY_EXTERNAL", "RESTORE_REQUIREMENT_SOURCE", "RECOMPILE_REQUIREMENT", "REPAIR_REQUIREMENT", "RETRY_FAILED_STEP", "TARGETED_EVIDENCE", "RECOMPUTE_SHARED_EVIDENCE", "SPLIT_FAILED_REQUEST").contains(strategy)){
                    tx.executeWithoutResult(s->{progress(task,96,"保存补查结果");Map<String,Object> job=new LinkedHashMap<>(pending);
                        job.put("status","BLOCKED_NO_PROGRESS");job.put("stopReason","本轮可用策略已执行；保留具体证据缺口，等待规则或证据变化。");job.put("updatedAt",now());store.save("check_resolution",jobId,task.projectId,runId,job);});continue;
                }
                Map<String,Object> initialContext=RecoveryInputs.context(before,strategy,contexts.apply(before.checkpoint()));
                if(!RecoveryInputs.applyDependencyTrigger(initialContext,pending,before)){
                    tx.executeWithoutResult(s->{progress(task,96,"受影响条件已完成，保留其他事项结论");Map<String,Object> job=new LinkedHashMap<>(pending);
                        job.put("status","BLOCKED_NO_PROGRESS");job.put("stopReason","新增证据影响的条件已经完成，未重复调查整项其他缺口。");job.put("updatedAt",now());
                        job.remove("dependencyTargetObligationIds");job.remove("dependencyTrigger");store.save("check_resolution",jobId,task.projectId,runId,job);});continue;
                }
                AgentGroupRecovery.carryChangedInputs(groupRecovery,initialContext);
                List<Map<String,Object>> semanticHistory=store.maps("check_resolution_attempt",task.projectId,jobId);
                Map<String,Object> semanticGate=RecoverySemanticGate.inspect(before,strategy,initialContext,semanticHistory,resolutionPolicyIdentity());
                if(Boolean.FALSE.equals(semanticGate.get("allowed"))){
                    tx.executeWithoutResult(s->{progress(task,96,"停止缺少新相关证据的重复调查");Map<String,Object> job=new LinkedHashMap<>(pending);
                        job.putAll(fields("status","BLOCKED_NO_PROGRESS","semanticGate",semanticGate,"stopReason",text(semanticGate,"description"),"updatedAt",now()));
                        store.save("check_resolution",jobId,task.projectId,runId,job);});
                    LOG.info("bidcheck.recovery_semantic_gate runId={} checkpointId={} strategy={} allowed=false reason={} blockedTargets={}",runId,checkpointId,strategy,semanticGate.get("reasonCode"),semanticGate.get("blockedTargetIds"));continue;
                }
                RecoverySemanticGate.applyTargets(initialContext,semanticGate);
                String input=CheckResolution.actionFingerprint(before,resolutionPolicyIdentity(),bid.sha256(),strategy,initialContext);
                String attemptId=stableId(jobId+":"+input);Stored prior=store.mapper().get(attemptId);
                if(prior!=null&&cn.bidcheck.compat.Java8Api.setOf("FINISHED", "NO_PROGRESS").contains(text(store.map(prior.body),"status"))){
                    tx.executeWithoutResult(s->{progress(task,96,"停止无新增证据的重复补查");Map<String,Object> job=new LinkedHashMap<>(pending);
                        job.put("status","BLOCKED_NO_PROGRESS");job.put("stopReason","相同策略和证据已经执行，未再次重复请求。");job.put("updatedAt",now());store.save("check_resolution",jobId,task.projectId,runId,job);});continue;
                }
                long recoveryStarted=System.nanoTime();
                tx.executeWithoutResult(s->{progress(task,96,"首轮已完成，定向补查："+before.checkpoint().title());updateRun(task,"RUNNING",96,task.stage,null);
                    Map<String,Object> job=new LinkedHashMap<>(pending);job.put("status","RUNNING");job.put("inputFingerprint",input);job.put("updatedAt",now());
                    job.put("attempts",((Number)job.getOrDefault("attempts",0)).intValue()+1);store.save("check_resolution",jobId,task.projectId,runId,job);
                    store.save("check_resolution_attempt",attemptId,task.projectId,jobId,fields("id",attemptId,"strategy",strategy,"inputFingerprint",input,"status","RUNNING","startedAt",now()));});
                Map<String,Object> context=initialContext;
                List<Map<String,Object>> diagnostics=new ArrayList<>();
                List<Map<String,Object>> newObservations=new ArrayList<>();
                List<Map<String,Object>> newExternalRecords=new ArrayList<>();
                Finding externalAfter=before;
                if("VERIFY_SOURCE_PAGES".equals(strategy)&&verification!=null){
                    List<Map<String,Object>> previousObservations=CheckResolution.maps(context.get("visualObservations"));
                    List<Map<String,Object>> observations=verification.getObject().collectCheckVisual(task,runId,before.checkpoint(),RecoveryInputs.visualRequest(before),cn.bidcheck.compat.Java8Api.listOf(bid),diagnostics,budget::reserve);
                    tx.executeWithoutResult(s->{progress(task,96,"保存指定页面观察");for(Map<String,Object> observation:observations){String observationId=stableId(runId+":check-observation:"+checkpointId+":"+store.encode(observation));
                        Map<String,Object> value=new LinkedHashMap<>(observation);value.put("runId",runId);value.put("checkpointId",checkpointId);
                        store.save("check_visual_observation",observationId,task.projectId,runId,value);}});
                    context=RecoveryInputs.context(before,strategy,contexts.apply(before.checkpoint()));
                    // Recompute only when the frozen, identity-checked context actually gained observations.
                    // An empty/failed call with old observations in storage is not new evidence.
                    newObservations=RecoveryInputs.changedObservations(previousObservations,context.get("visualObservations"));
                    RecoveryInputs.changedInputs(context,newObservations);
                }
                if("QUERY_EXTERNAL".equals(strategy)&&verification!=null){
                    List<Map<String,Object>> previousRecords=CheckResolution.maps(context.get("externalRecords"));
                    List<Map<String,Object>> records=verification.getObject().collectCheckExternal(task,runId,RecoveryInputs.externalRequest(before),store.getMap("run",runId),diagnostics,()->true);
                    ExternalCheckRecovery.Outcome closure=ExternalCheckRecovery.execute(previousRecords,records,
                        trusted->tx.executeWithoutResult(s->{progress(task,96,"保存外部来源事实");for(Map<String,Object> record:trusted){String recordId=stableId(runId+":check-external:"+checkpointId+":"+store.encode(record));
                            store.save("check_external_record",recordId,task.projectId,runId,fields("runId",runId,"checkpointId",checkpointId,"record",record,"savedAt",now()));}}),
                        ()->RecoveryInputs.context(before,strategy,contexts.apply(before.checkpoint())),
                        refreshed->{tx.executeWithoutResult(s->{progress(task,96,"正在用外部来源事实核验："+before.checkpoint().title());updateRun(task,"RUNNING",96,task.stage,null);});
                            return evaluateFinding(runId,before.checkpoint(),bid,tender,facts,consistency,budget::reserve,refreshed);},before);
                    context=closure.context();newExternalRecords=closure.newRecords();externalAfter=closure.finding();
                }
                Finding after;
                if("VERIFY_SOURCE_PAGES".equals(strategy)&&newObservations.isEmpty()){
                    after=before;diagnostics.add(fields("code","NO_NEW_VALID_OBSERVATION","description","指定原图未新增有效观察，保留现有证明，未重复进行文本提取。"));
                }
                else if("QUERY_EXTERNAL".equals(strategy)&&newExternalRecords.isEmpty()){
                    after=before;diagnostics.add(fields("code","NO_NEW_TRUSTED_EXTERNAL_RECORD","description","指定外部来源未返回可验证记录，保留现有证明；服务失败或未命中不等于投标不符合。"));
                }
                else if("QUERY_EXTERNAL".equals(strategy))after=externalAfter;
                else {
                    tx.executeWithoutResult(s->{progress(task,96,"正在用补充证据核验："+before.checkpoint().title());
                        updateRun(task,"RUNNING",96,task.stage,null);});
                    try{after=evaluateFinding(runId,before.checkpoint(),bid,tender,facts,consistency,budget::reserve,context);}
                    catch(cn.bidcheck.verification.engine.RecoveryDependencyGate.NoDependencyChange unchanged){
                        after=before;context.put("recoveryNoopReason",unchanged.reasonCode());
                        diagnostics.add(fields("code",unchanged.reasonCode(),"description","规则和所依赖输入未变化；保留已完成证明，未重复查询事实或执行提取。",
                            "planRevision",unchanged.receipt().get("planRevision"),"inputRevision",unchanged.receipt().get("inputRevision"),"businessProof",false));
                    }
                }
                boolean businessRecomputed=after!=before;
                if("QUERY_EXTERNAL".equals(strategy)&&newExternalRecords.isEmpty())after=ExternalCheckRecovery.attachDiagnostics(after,diagnostics);
                String outcome=CheckResolution.outcomeFingerprint(after);Map<String,Object> proofProgress=CheckResolution.progress(before,after);
                boolean progressMade=Boolean.TRUE.equals(proofProgress.get("progressMade"));
                Map<String,Object> recoveryInput=fields("mode",context.getOrDefault("recoveryMode","TARGETED"),"targetObligationIds",context.getOrDefault("recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf()),
                    "changedLocations",context.getOrDefault("recoveryChangedLocations",cn.bidcheck.compat.Java8Api.listOf()),"newObservationCount",newObservations.size(),
                    "newExternalRecordCount",newExternalRecords.size(),"recomputed",businessRecomputed,"newModelCalls",!businessRecomputed?0:CheckResolution.map(CheckResolution.atomic(after).get("metrics")).getOrDefault("modelCalls",0),
                    "dependencyRevision",context.getOrDefault("recoveryDependencyRevision",""),"noopReason",context.getOrDefault("recoveryNoopReason",""));
                Finding preserved=CheckResolution.protectCompletedProof(before,after);
                Finding published=withPreviousReference(preserved);
                Map<String,Object> requirementsProgress=RecoveryInputs.requirementProgress(before,preserved);
                boolean requirementsRepaired=cn.bidcheck.verification.engine.RequirementRepairPolicy.requirementWork(strategy)&&Boolean.TRUE.equals(requirementsProgress.get("progressMade"));
                Map<String,Object> nextPlan=CheckResolution.plan(published);
                Map<String,Object> dependencyContinuation=RecoveryInputs.dependencyContinuation(pending,published,nextPlan);
                Map<String,Object> transition=RecoveryTransition.afterAttempt(before,published,strategy,nextPlan,progressMade,requirementsRepaired,businessRecomputed,diagnostics);
                List<Map<String,Object>> semanticConsumedInputs=RecoverySemanticGate.inputs(published,context,resolutionPolicyIdentity());
                List<Map<String,Object>> nextHistory=new ArrayList<>(semanticHistory);
                nextHistory.add(fields("strategy",strategy,"status","FINISHED","semanticConsumedInputs",semanticConsumedInputs));
                Map<String,Object> nextGateContext=RecoveryInputs.context(published,text(nextPlan,"strategy"),context);
                if(dependencyContinuation.containsKey("dependencyTargetObligationIds"))nextGateContext.put("recoveryTargetObligationIds",dependencyContinuation.get("dependencyTargetObligationIds"));
                Map<String,Object> nextSemanticGate=RecoverySemanticGate.inspect(published,text(nextPlan,"strategy"),nextGateContext,nextHistory,resolutionPolicyIdentity());
                RecoveryTransition.applySemanticGate(transition,nextSemanticGate);
                Map<String,Object> failedOutcome=preserved==after?cn.bidcheck.compat.Java8Api.mapOf():fields("evaluation",after.evaluation(),"assessment",after.assessment());
                tx.executeWithoutResult(s->{progress(task,96,"保存定向补查结果");store.save("finding",published.id(),task.projectId,runId,published);
                    OrdinaryRecoveryDependencies.bind(store,task.projectId,runId,published);
                    Map<String,Object> job=store.getMap("check_resolution",jobId);job.putAll(nextPlan);job.put("updatedAt",now());
                    job.remove("dependencyTargetObligationIds");job.remove("dependencyTrigger");job.putAll(dependencyContinuation);
                    job.put("newEvidenceCount",Math.max(0,(published.evaluation().evidences()==null?0:published.evaluation().evidences().size())-(before.evaluation().evidences()==null?0:before.evaluation().evidences().size())));
                    job.put("outcomeFingerprint",outcome);job.put("diagnostics",diagnostics);job.put("proofProgress",proofProgress);job.put("requirementsProgress",requirementsProgress);job.put("recoveryInput",recoveryInput);
                    job.put("status",transition.get("status"));job.put("lastAttemptOutcome",transition);
                    job.put("semanticGate",nextSemanticGate);
                    job.remove("stopReason");job.remove("retryNotBeforeEpochMillis");
                    if(transition.containsKey("stopReason"))job.put("stopReason",transition.get("stopReason"));
                    if(Boolean.TRUE.equals(transition.get("temporaryVisualFailure"))){long retryAt=store.maps("verification_visual_page_status",task.projectId,runId).stream()
                            .filter(v->"RETRY_PENDING".equals(text(v,"status"))).map(v->v.get("retryNotBeforeEpochMillis")).filter(Number.class::isInstance)
                            .mapToLong(v->((Number)v).longValue()).max().orElse(System.currentTimeMillis()+30000);job.put("retryNotBeforeEpochMillis",retryAt);}
                    else if(nextPlan.get("retryNotBeforeEpochMillis") instanceof Number)job.put("retryNotBeforeEpochMillis",((Number) (nextPlan.get("retryNotBeforeEpochMillis"))).longValue());
                    store.save("check_resolution",jobId,task.projectId,runId,job);
                    Map<String,Object> attempt=store.getMap("check_resolution_attempt",attemptId);
                    attempt.putAll(fields("outcomeFingerprint",outcome,"status",progressMade||requirementsRepaired||Boolean.TRUE.equals(transition.get("technicalRecovered"))?"FINISHED":"NO_PROGRESS","finishedAt",now(),"outcome",transition,
                        "elapsedMillis",(System.nanoTime()-recoveryStarted)/1_000_000,"proofProgress",proofProgress,"requirementsProgress",requirementsProgress,"recoveryInput",recoveryInput,"businessComplete",!CheckResolution.unresolved(published),"diagnostics",diagnostics));
                    attempt.put("semanticConsumedInputs",semanticConsumedInputs);
                    if(!failedOutcome.isEmpty())attempt.put("unpublishedFailedOutcome",failedOutcome);
                    store.save("check_resolution_attempt",attemptId,task.projectId,jobId,attempt);
                });
                LOG.info("bidcheck.resolution taskId={} runId={} checkpointId={} strategy={} durationMs={} progressMade={} requirementsRepaired={} businessComplete={} recomputed={} newModelCalls={} technicalRecovered={} processingFailed={} nextStrategy={} resolutionStatus={}",
                    task.id,runId,checkpointId,strategy,(System.nanoTime()-recoveryStarted)/1_000_000,progressMade,requirementsRepaired,!CheckResolution.unresolved(published),recoveryInput.get("recomputed"),recoveryInput.get("newModelCalls"),transition.get("technicalRecovered"),transition.get("processingFailed"),transition.get("nextStrategy"),transition.get("status"));
                LOG.info("bidcheck.recovery_dependencies runId={} checkpointId={} strategy={} targets={} revision={} noopReason={} gapCodes={}",runId,checkpointId,strategy,
                    recoveryInput.get("targetObligationIds"),recoveryInput.get("dependencyRevision"),recoveryInput.get("noopReason"),cn.bidcheck.compat.Java8Api.toList(CheckResolution.maps(CheckResolution.atomic(before).get("gaps")).stream().map(g->text(g,"code")).filter(c->c.matches("[A-Z0-9_]{1,100}")).distinct().sorted()));
                LOG.info("bidcheck.recovery_semantic_gate runId={} checkpointId={} strategy={} allowed={} reason={} eligibleTargets={} blockedTargets={}",runId,checkpointId,text(nextPlan,"strategy"),nextSemanticGate.get("allowed"),nextSemanticGate.get("reasonCode"),nextSemanticGate.get("eligibleTargetIds"),nextSemanticGate.get("blockedTargetIds"));
            }
        }
    }
    private Map<String,Object> prepareConsistency(TaskRow task,String runId,Document doc,Document tender,List<Checkpoint> checks) {
        Optional<Checkpoint> selected=checks.stream().filter(ConsistencyRunSupport::enabled).findFirst();
        Map<String,Object> previous=consistencyReport(runId);
        if(!previous.isEmpty()) {
            boolean frozenRecovery=Boolean.TRUE.equals(store.getMap("run",runId).get("recoveryOnly"))&&Boolean.TRUE.equals(previous.get("recoveryFrozenScope"));
            if(!doc.id().equals(text(previous,"documentId"))||!doc.sha256().equals(text(previous,"documentHash"))||!frozenRecovery&&!consistencyIdentity().equals(text(previous,"engineIdentity")))
                throw bad("全文扫描快照与当前检查版本不匹配，请重新发起检查");
            previous.put("confirmedEvidenceRelations",confirmedConsistencyRelations(runId,doc,tender));return previous;
        }
        List<Map<String,Object>> requirementHints=cn.bidcheck.compat.Java8Api.toList(checks.stream().filter(c->c.confirmed()&&c.enabled())
                .map(c->fields("title",c.title(),"field",c.field(),"expected",Objects.toString(c.expected(),""))));
        Map<String,Object> report;
        if("RULES_RETRIEVAL".equals(checkMode())) {
            tx.executeWithoutResult(s->{progress(task,5,"本地全文规则扫描：按主体、项目及金额口径核对");updateRun(task,"RUNNING",5,task.stage,null);});
            report=new LinkedHashMap<>(new ConsistencyEngine().scan(doc,cn.bidcheck.compat.Java8Api.listOf()));
            Map<String,Object> coverage=new LinkedHashMap<>(ConsistencyRunSupport.map(report.get("coverage")));
            Map<String,Object> ruleScan=new LinkedHashMap<>();
            for(String key:cn.bidcheck.compat.Java8Api.listOf("pagesTotal", "pagesScanned", "pagesUnparsed", "pagesSkipped", "status"))
                if(coverage.containsKey(key))ruleScan.put(key,coverage.get(key));
            ruleScan.put("scope","ALL_DOCUMENT_TEXT");ruleScan.put("policyVersion",ConsistencyEngine.VERSION);
            coverage.put("ruleScan",ruleScan);coverage.put("checkMode","RULES_RETRIEVAL");
            coverage.put("model",fields("enabled",false,"requested",false,"status","NOT_REQUESTED","scope","FULL_DOCUMENT_MODEL","complete",false,
                "reason","本次执行全文本地规则和相关证据检索，复杂疑点按需调用模型；未请求全文生成式抽取。"));
            report.put("coverage",coverage);
        } else if(selected.isPresent()) {
            cn.bidcheck.consistency.ConsistencyModelScanner.Result model=consistencyScanner().scan(doc,requirementHints,stage->tx.executeWithoutResult(s->{progress(task,5,stage);updateRun(task,"RUNNING",5,stage,null);}),consistencyCache(task,runId));
            report=new LinkedHashMap<>(new ConsistencyEngine().scan(doc,model.facts()));
            Map<String,Object> coverage=new LinkedHashMap<>(ConsistencyRunSupport.map(report.get("coverage")));
            coverage.put("model",model.coverage());report.put("coverage",coverage);
        } else {
            // Personnel credentials are a baseline source-consistency check even without a tender-specific rule.
            report=new LinkedHashMap<>(new PersonCredentialConsistency().scan(doc));
            Map<String,Object> coverage=new LinkedHashMap<>(ConsistencyRunSupport.map(report.get("coverage")));
            coverage.put("model",fields("enabled",false,"status","NOT_REQUESTED","reason","人员及证件规则核对已执行；本次未请求额外全文模型扫描。"));
            report.put("coverage",coverage);
        }
        report.put("confirmedEvidenceRelations",confirmedConsistencyRelations(runId,doc,tender));
        Map<String,Object> finalCoverage=new LinkedHashMap<>(ConsistencyRunSupport.map(report.get("coverage")));
        finalCoverage.put("checkMode",checkMode());report.put("coverage",finalCoverage);
        List<Map<String,Object>> groups=new ArrayList<>(); List<Finding> findings=new ArrayList<>();
        Checkpoint parent=selected.orElseGet(()->"RULES_RETRIEVAL".equals(checkMode())
            ?new Checkpoint("system-full-text-rules","全文规则一致性","全文信息检查","HIGH","FULL_TEXT_REVIEW","fullTextConsistency","REVIEW","按同一主体、项目、时点和口径核对可用原文","",true,true,true,null)
            :new Checkpoint("system-person-credentials","人员及证件一致性","人员及证件检查","HIGH",
            "FULL_TEXT_REVIEW","personCredentialConsistency","REVIEW","比较同一人员及其同类证件的可用字段，提取不确定项仅保留说明","",true,true,true,null));
        for(Map<String,Object> original:ConsistencyRunSupport.maps(report.get("groups"))) {
            Map<String,Object> group=new LinkedHashMap<>(original);
            String checkpointId=ConsistencyRunSupport.id(parent.id()+":"+text(group,"id"));
            Checkpoint checkpoint=new Checkpoint(checkpointId,text(group,"title"),"全文信息检查",parent.severity(),
                    "FULL_TEXT_REVIEW","fullTextConsistency","REVIEW","同一主体、项目及口径的信息应一致","",true,true,true,parent.source());
            boolean insufficient="EVIDENCE_INSUFFICIENT".equals(text(group,"status"));
            Evaluation raw=new Evaluation(insufficient?"UNDETERMINED":"WARNING",text(group,"reason"),
                    insufficient?"补充或核对比较所需的对象、单位和适用条件；证据不足不表示材料不存在。":"对照以下各处原文，确认是否属于同一对象和范围；合理差异可单独记录复核说明。",
                    ConsistencyRunSupport.evidences(store,group.get("evidences")),"CONFLICT".equals(text(group,"status"))?1:0.6,"FULL_TEXT_CONSISTENCY");
            OcrReviewPolicy.Decision decision=OcrReviewPolicy.assess(raw,checkpoint,doc,tender);
            String findingId=stableId(runId+":"+checkpointId);
            group.put("findingId",findingId);group.put("evaluationStatus",decision.evaluation().status());
            if("CONFLICT".equals(text(group,"status"))&&"UNDETERMINED".equals(decision.evaluation().status())) group.put("status","SUSPECTED");
            group.put("reason",decision.evaluation().reason());groups.add(group);
            findings.add(new Finding(findingId,runId,checkpoint,decision.evaluation(),fields("consistencyGroupId",text(group,"id"),
                    "categoryId",text(group,"categoryId"),"relationType",text(group,"relationType"),"ruleId",text(group,"ruleId"),
                    "comparisonContext",ConsistencyRunSupport.map(group.get("comparisonContext")),
                    "policyVersion","full-text-consistency-v2","rawEvaluation",raw,"ocrReview",decision.review(),"adjustments",decision.adjustments())));
        }
        report.put("groups",groups);
        if((!selected.isPresent())) {
            Map<String,Object> coverage=ConsistencyRunSupport.map(report.get("coverage"));
            Map<String,Object> persons=ConsistencyRunSupport.map(coverage.get("personCredentials"));
            if(persons.isEmpty())persons=coverage; // Legacy person-only reports retain their original shape.
            boolean personReview=PersonCredentialConsistency.requiresReview(persons);
            boolean rulesReview="RULES_RETRIEVAL".equals(checkMode())&&"PARTIAL".equals(coverage.get("status"));
            if(personReview||rulesReview) {
                String method=personReview?"PERSON_CREDENTIAL_COVERAGE":"FULL_TEXT_RULE_COVERAGE";
                String id=stableId(runId+":"+(personReview?"system-person-credential-coverage":"system-full-text-rule-coverage"));
                Evaluation coverageResult=new Evaluation("UNDETERMINED",rulesReview
                    ?"全文规则检查存在尚未读取、提取受限或来源未核实的位置；已扫描文字不等于全部信息已核实。"
                    :"人员及证件检查存在尚未完成的扫描或来源核验，详情见处理提示。",
                    "查看尚未读取、未完成比较或未通过来源核验的位置，完成处理后可重新检查。",cn.bidcheck.compat.Java8Api.listOf(),0,method);
                findings.add(new Finding(id,runId,parent,coverageResult,fields("personCredentialCoverage",persons,
                    "fullTextRuleCoverage",coverage,"systemCheck",true)));
            }
        }
        new ConsistencyPublication(store,factLedger(),tx).publish(task,runId,doc,consistencyIdentity(),report,findings,
            stage->{progress(task,5,stage);updateRun(task,"RUNNING",5,stage,null);});
        return report;
    }
    private List<Map<String,Object>> confirmedConsistencyRelations(String runId,Document bid,Document tender){
        if(inferenceConfirmations==null||inferenceConfirmations.getIfAvailable()==null)return cn.bidcheck.compat.Java8Api.listOf();
        List<Map<String,Object>> stored=inferenceConfirmations.getObject().sharedConfirmedForRun(runId,bid.projectId(),bid,tender);
        List<Document> documents=tender==null?cn.bidcheck.compat.Java8Api.listOf(bid):cn.bidcheck.compat.Java8Api.listOf(bid, tender);
        List<Map<String,Object>> validated=cn.bidcheck.verification.engine.ConfirmedInferenceEvidence.validated(stored,documents);
        LOG.info("bidcheck.consistency_confirmation runId={} frozenRecords={} validatedRelations={} mode=SOURCE_SCOPED_NO_CONFLICT_OVERRIDE",runId,stored.size(),validated.size());return validated;
    }
    private ConsistencyModelScanner.BatchCache consistencyCache(TaskRow task,String runId) {
        String counterId=stableId("consistency-budget:"+runId);
        return new ConsistencyModelScanner.BatchCache() {
            private String entryId(String key){return stableId("consistency-batch:"+runId+":"+key);}
            public com.fasterxml.jackson.databind.JsonNode get(String key) {
                Stored row=store.mapper().get(entryId(key));
                if(row==null)return null;
                if(!"consistency_batch".equals(row.kind)||!task.projectId.equals(row.projectId)||!runId.equals(row.parentId))throw bad("全文扫描缓存范围不匹配");
                return store.decode(store.encode(store.getMap("consistency_batch",row.id).get("response")),com.fasterxml.jackson.databind.JsonNode.class);
            }
            public boolean reserve(String key,int maximum) {
                return Boolean.TRUE.equals(tx.execute(s->{
                    progress(task,5,"记录全文扫描调用");store.lock("project",task.projectId);
                    Stored row=store.mapper().get(counterId);
                    int calls=row==null?0:((Number)store.map(row.body).get("calls")).intValue();
                    // Compatibility storage keeps the historical kind/name, but this row is now an
                    // audit counter only. The source-first packet splitter, request timeout and task
                    // lease bound execution; a document is never left incomplete because an
                    // arbitrary total-call quota was reached.
                    store.save("consistency_budget",counterId,task.projectId,runId,fields(
                        "calls",Math.incrementExact(calls),"maxCalls",-1,"unlimited",true,
                        "legacySuggestedMaximum",maximum));
                    return true;
                }));
            }
            public void save(String key,com.fasterxml.jackson.databind.JsonNode response) {
                tx.executeWithoutResult(s->{
                    progress(task,5,"保存已完成的全文扫描片段");
                    store.save("consistency_batch",entryId(key),task.projectId,runId,fields("response",response));
                });
            }
        };
    }
    private Finding evaluateFinding(String runId,Checkpoint c,Document d,Document tender,List<Fact> facts,Map<String,Object> consistency,java.util.function.BooleanSupplier reserveAttempt,Map<String,Object> atomicContext) {
        long started=System.nanoTime();
        LOG.info("bidcheck.checkpoint runId={} checkpointId={} state=STARTED recovery={}",runId,c.id(),atomicContext.containsKey("recoveryMode"));
        if(CheckReviewMode.agent(store.getMap("run",runId))&&CheckReviewMode.eligible(c)&&!CheckReviewMode.AGENT.equals(atomicContext.get("reviewMode")))
            throw new IllegalStateException("AGENT_REVIEW_CONTEXT_MISSING: Agent 主审上下文缺失，未降级为标准检查");
        SemanticAssessment assessment;
        if(frozenTemplateRoute(d.projectId(),runId,c)) {
            Optional<cn.bidcheck.checktemplate.CheckSpec> frozenSpec=checkSpecifications==null?Optional.empty():checkSpecifications.findSnapshotSpec(d.projectId(),runId,c.id());
            if(frozenExplicitTemplateRoute(runId,c)&&(!frozenSpec.isPresent())) {
                Evaluation failed=new Evaluation("UNDETERMINED","已冻结的检查规格缺失或不可读，本项没有回退到其他判定路径。","修复检查规格快照后重新发起检查。",cn.bidcheck.compat.Java8Api.listOf(),0,"TEMPLATE_SNAPSHOT_INVALID");
                assessment=new SemanticAssessment(failed,failed,fields("templateTrace",fields("route","TEMPLATE","checkpointId",c.id(),"compileRequests",0,"failure","TEMPLATE_SNAPSHOT_INVALID"),
                    "adjustments",cn.bidcheck.compat.Java8Api.listOf(fields("code","TEMPLATE_SNAPSHOT_INVALID","reason","固定模板快照缺失或损坏。"))));
            } else {
                cn.bidcheck.checktemplate.CheckSpec executableSpec=frozenSpec.orElseGet(()->templateChecks.toSpec(c,tender));
                List<Fact> executableFacts=facts;
                if(templateExternalEvidence!=null&&"EXTERNAL_CHECK".equals(executableSpec.templateId())){
                    List<Fact> externalFacts=templateExternalEvidence.resolve(d.projectId(),runId,executableSpec,facts);
                    if(!externalFacts.isEmpty())executableFacts=IncrementalCheckEngine.distinctEvidence(
                        cn.bidcheck.compat.Java8Api.toList(java.util.stream.Stream.concat(facts.stream(),externalFacts.stream())));
                }
                cn.bidcheck.checktemplate.TemplateEvidenceAssembler.Assembly evidence=templateEvidence.assemble(executableSpec,c,d,tender,executableFacts);
                cn.bidcheck.checktemplate.LegacyTemplateBridge.BridgeResult result=templateChecks.evaluate(executableSpec,evidence.evidenceView(),evidence.evidences());
                Map<String,Object> audit=new LinkedHashMap<>(result.assessment());
                // Keep the typed preliminary projection in-process so the final OCR/evidence guards can
                // reconcile it before serialization. The bridge's map remains available in templateTrace.
                audit.put("templateResult",result.execution().projection());
                audit.put("templateEvidence",evidence.diagnostics());
                if(templateDependencyGraphs!=null)audit.put("templateDependencyGraph",templateDependencyGraphs.fragment(result.execution()));
                audit.put("checkSpec",fields("id",result.spec().id(),"revision",result.spec().revision(),"category",result.spec().category().name(),
                    "purpose",result.spec().purpose().name(),"verificationMode",result.spec().verificationMode().name(),"templateId",result.spec().templateId(),"templateVersion",result.spec().templateVersion()));
                assessment=new SemanticAssessment(result.evaluation(),result.evaluation(),audit);
            }
        }
        else if(ConsistencyRunSupport.enabled(c)&&!consistency.isEmpty()) {
            Evaluation summary=ConsistencyRunSupport.summary(consistency,store);
            assessment=new SemanticAssessment(summary,summary,fields("consistencyReportId",ConsistencyRunSupport.reportId(runId),"coverage",consistency.get("coverage")));
        }
        else if(CheckReviewMode.AGENT.equals(atomicContext.get("reviewMode"))&&"FULL_TEXT_REVIEW".equals(c.type())
                &&cn.bidcheck.compat.Java8Api.setOf("amountAccuracy", "missingFields", "signatureDate", "signingAfterOpening", "typos", "certificateCoverage").contains(c.field())) {
            // These document-wide checks already have bounded program executors. Agent owns the
            // orchestration but delegates the scan to deterministic code; asking the model to
            // rediscover the same anomaly detector only adds cost and cannot improve coverage.
            Evaluation deterministic=engine.evaluate(c,d,facts,tender);
            assessment=new SemanticAssessment(deterministic,deterministic,fields("agentDelegation",fields(
                "mode",CheckReviewMode.AGENT,"executor","DETERMINISTIC_FULL_TEXT_REVIEW","modelCalls",0,
                "reason","该全文检查已有程序扫描器，由 Agent 主审流程直接调用并保留原文证据。")));
        }
        else if(!CheckReviewMode.AGENT.equals(atomicContext.get("reviewMode"))&&!consistency.isEmpty()&&"ENTITY_FIELD_CONSISTENCY".equals(c.type())&&cn.bidcheck.compat.Java8Api.setOf("companyName", "personName").contains(c.field())
                &&(c.expected()==null||cn.bidcheck.compat.Java8Api.isBlank(c.expected()))) {
            // The legacy per-field cards must use the same current-project scope as the full-text report.
            List<Map<String,Object>> groups=cn.bidcheck.compat.Java8Api.toList(ConsistencyRunSupport.maps(consistency.get("groups")).stream().filter(g->c.field().equals(g.get("field"))));
            List<Evidence> evidence=cn.bidcheck.compat.Java8Api.toList(groups.stream().flatMap(g->ConsistencyRunSupport.evidences(store,g.get("evidences")).stream()).distinct());
            boolean conflict=groups.stream().anyMatch(g->"CONFLICT".equals(g.get("status")));
            Evaluation scoped=new Evaluation(groups.isEmpty()?"UNDETERMINED":conflict?"FAIL":"WARNING",
                    groups.isEmpty()?"当前项目可对齐范围内未发现该字段冲突；未识别或范围不明的信息仍需核对。":groups.stream().map(g->text(g,"reason")).distinct().collect(java.util.stream.Collectors.joining("；")),
                    "查看全文一致性中的同字段原文与范围，历史业绩和范围不明的人员不与本次投标直接合并。",evidence,groups.isEmpty()?0:0.8,"FULL_TEXT_SCOPED_IDENTITY");
            assessment=new SemanticAssessment(scoped,scoped,fields("consistencyReportId",ConsistencyRunSupport.reportId(runId),"coverage",consistency.get("coverage")));
        }
        else if(!atomicContext.isEmpty()&&focusedChecks!=null&&focusedChecks.supports(c,CheckReviewMode.normalize(atomicContext.get("reviewMode")))) {
            FocusedCheckEvaluator.Result result=focusedChecks.assess(c,d,tender,atomicContext);
            assessment=new SemanticAssessment(result.evaluation(),result.evaluation(),result.assessment());
        }
        else if("SEMANTIC_RESPONSE".equals(c.type())) {
            // A free-form model PASS/FAIL is not a business decision. If this checkpoint has not yet
            // been mapped to a fixed template or a focused atomic context, expose that system gap
            // directly. Do not spend a model call producing a conclusion candidate that cannot close it.
            assessment=atomicContextUnavailable(c);
        }
        else {
            Evaluation deterministic=engine.evaluate(c,d,facts,tender);
            assessment=new SemanticAssessment(deterministic,deterministic,cn.bidcheck.compat.Java8Api.mapOf());
        }
        List<Map<String,Object>> scopedSupplements=cn.bidcheck.compat.Java8Api.toList(CheckResolution.maps(store.getMap("run",runId).get("supplementManifest")).stream()
            .filter(row->c.id().equals(text(row,"checkpointId"))||stringList(row.get("affectedCheckpointIds")).contains(c.id())));
        if(!scopedSupplements.isEmpty()&&(!assessment.audit().containsKey("atomicCheck"))){
            // Legacy/template scanners accept a single BID document. They must not claim that an
            // independent attachment was checked simply because the original BID still passes.
            Evaluation pending=new Evaluation("UNDETERMINED","补充材料已解析；本项当前判定路径尚未完成对独立补证的核验。","请查看本项补证及处理记录；原结论仅作历史参考。",assessment.evaluation().evidences(),0,"SUPPLEMENT_ANALYSIS_REQUIRED");
            Map<String,Object> audit=new LinkedHashMap<>(assessment.audit());audit.put("preSupplementEvaluation",assessment.evaluation());audit.put("supplementProcessing",fields("status","EVALUATOR_NOT_CONNECTED","attachmentIds",cn.bidcheck.compat.Java8Api.toList(scopedSupplements.stream().map(r->text(r,"attachmentId"))),"checked",false));
            audit.put("publicationGuard",fields("code","SUPPLEMENT_EVALUATOR_UNAVAILABLE","reason","独立补证尚未进入本项现有判定器，未据此形成结论。"));assessment=new SemanticAssessment(pending,assessment.rawEvaluation(),audit);
        }
        Evaluation beforeOcr=assessment.evaluation();
        OcrReviewPolicy.Decision decision=OcrReviewPolicy.assess(beforeOcr,c,d,tender,
            TemplateResultFinalizer.requiredProofDocumentRoles(assessment.audit()));
        Evaluation e=decision.evaluation();
        Map<String,Object> trace=new LinkedHashMap<>(assessment.audit());
        List<Object> adjustments=new ArrayList<>();
        if(trace.get("guards") instanceof List<?>) adjustments.addAll(((List<?>) (trace.get("guards"))));
        adjustments.addAll(decision.adjustments());
        if(("FAIL".equals(e.status())||"WARNING".equals(e.status()))&&(e.evidences()==null||e.evidences().isEmpty())&&c.source()==null) {
            adjustments.add(cn.bidcheck.compat.Java8Api.mapOf("code", "EVIDENCE_MISSING", "reason", "未获得可追溯证据。"));
            e=new Evaluation("UNDETERMINED","未获得可追溯证据，需人工复核",e.suggestion(),cn.bidcheck.compat.Java8Api.listOf(),0,"EVIDENCE_GUARD");
        }
        if(trace.containsKey("policyVersion")) trace.put("semanticPolicyVersion",trace.get("policyVersion"));
        trace.put("policyVersion","evidence-review-v3");
        trace.put("rawEvaluation",assessment.rawEvaluation());trace.put("preOcrEvaluation",beforeOcr);
        trace.put("adjustments",adjustments);trace.put("ocrReview",decision.review());
        trace.put("elapsedMillis",(System.nanoTime()-started)/1_000_000);
        Finding finding=new Finding(stableId(runId+":"+c.id()),runId,c,e,FocusedCheckEvaluator.reconcileAssessment(e,trace));
        LOG.info("bidcheck.checkpoint runId={} checkpointId={} state=COMPLETED method={} businessState={} evidenceCount={} durationMs={}",runId,c.id(),e.method(),
            text(CheckResolution.map(finding.assessment().get("businessPresentation")),"state"),e.evidences()==null?0:e.evidences().size(),trace.get("elapsedMillis"));
        return finding;
    }
    /** Explicit production boundary for a semantic checkpoint without executable atomic context. */
    static SemanticAssessment atomicContextUnavailable(Checkpoint checkpoint){
        Evaluation pending=new Evaluation("UNDETERMINED","本检查项尚未建立可执行的原子规则与证据上下文，系统未调用旧版模型生成结论。",
            "先完成检查项映射及证据字段绑定，再由程序规则自动重算。",cn.bidcheck.compat.Java8Api.listOf(),0,"ATOMIC_CONTEXT_UNAVAILABLE");
        Map<String,Object> audit=fields("modelOutputRole","NOT_REQUESTED","legacySemanticFallback",false,
            "checkpointId",checkpoint==null?"":checkpoint.id(),"publicationGuard",fields("code","ATOMIC_CONTEXT_REQUIRED",
                "reason","只有固定模板或来源已校验的原子事实可以进入程序结论计算。"));
        return new SemanticAssessment(pending,pending,Collections.unmodifiableMap(audit));
    }
    private void publishTemplateDependencyGraph(String projectId,String runId){if(templateDependencyGraphs==null)return;List<cn.bidcheck.checktemplate.incremental.TemplateDependencyGraph.Fragment> fragments=new ArrayList<>();List<Finding> templateFindings=new ArrayList<>();
        for(Finding finding:store.list("finding",projectId,runId,Finding.class)){Object value=CheckResolution.map(finding.assessment()).get("templateDependencyGraph");if(value==null)continue;
            try{fragments.add(store.decode(store.encode(value),cn.bidcheck.checktemplate.incremental.TemplateDependencyGraph.Fragment.class));templateFindings.add(finding);}catch(RuntimeException ignored){/* old or incomplete findings are not template dependency fragments */}}
        templateFindings.sort(Comparator.comparing(finding->finding.checkpoint().id()));String sourceResultDigest=hash(templateFindings.stream().map(store::encode).collect(java.util.stream.Collectors.joining("\n")));
        templateDependencyGraphs.publish(projectId,runId,fragments,sourceResultDigest);}
    /** Builds the durable project evidence graph from every fact ledger used by this completed pass. */
    private void publishProjectEvidenceGraph(String projectId,String runId,Document bid){if(evidenceGraphs==null)return;
        Map<String,Object> run=store.getMap("run",runId);List<String> identities=new ArrayList<>();identities.add(consistencyIdentity());String atomic=text(run,"atomicFactIdentity");if(!cn.bidcheck.compat.Java8Api.isBlank(atomic))identities.add(atomic);
        String previousRevision="",parentRun=text(run,"parentRunId");if(!cn.bidcheck.compat.Java8Api.isBlank(parentRun))try{previousRevision=text(CheckResolution.map(store.getMap("run",parentRun).get("evidenceGraph")),"revisionId");}catch(RuntimeException ignored){/* a missing historical run is not a graph identity */}
        // A normal rerun may reuse the latest stable graph without having a recovery parent. Keep
        // that revision as the comparison base so the newly published graph reports only actual
        // dependency changes instead of invalidating every graph-backed need.
        if(cn.bidcheck.compat.Java8Api.isBlank(previousRevision)){Map<String,Object> reused=CheckResolution.map(run.get("priorEvidenceGraphReuse"));
            if("REVALIDATED".equals(reused.get("status")))previousRevision=text(reused,"revisionId");}
        cn.bidcheck.verification.graph.FactLedgerEvidenceGraphBridge.RefreshResult refreshed=evidenceGraphs.refreshCombined(projectId,runId,identities,cn.bidcheck.compat.Java8Api.listOf(bid),previousRevision,cn.bidcheck.compat.Java8Api.listOf());cn.bidcheck.verification.graph.EvidenceGraph.BuildResult graph=refreshed.graph();
        Map<String,Object> dependency=templateDependencyGraphs==null?cn.bidcheck.compat.Java8Api.mapOf():templateDependencyGraphs.get(runId);
        Set<String> changedNeeds=cn.bidcheck.verification.graph.EvidenceGraphDependencyAdapter.affectedNeedKeys(dependency,graph.changedDependencyKeys());Set<String> checkpoints=cn.bidcheck.compat.Java8Api.setOf();
        if(templateDependencyGraphs!=null&&!dependency.isEmpty()&&dependency.get("revision") instanceof Number&&!changedNeeds.isEmpty())checkpoints=templateDependencyGraphs.plan(runId,((Number) (dependency.get("revision"))).longValue(),changedNeeds).checkpointIds();
        String bindingId=stableId("evidence-graph-binding:"+runId);Map<String,Object> binding=fields("id",bindingId,"projectId",projectId,"runId",runId,
            "revisionId",graph.snapshot().revision().id(),"contentDigest",graph.snapshot().revision().contentDigest(),"manifestDigest",graph.snapshot().revision().manifestDigest(),
            "occurrenceDigest",graph.snapshot().revision().occurrenceDigest(),"builderVersion",graph.snapshot().revision().builderVersion(),"sourceLedgerIdentities",cn.bidcheck.compat.Java8Api.toList(identities.stream().distinct().sorted()),
            "reused",graph.reused(),"previousRevisionId",graph.previousRevisionId(),"changedGraphDependencyKeys",graph.changedDependencyKeys(),"changedEvidenceNeedKeys",changedNeeds,"affectedCheckpointIds",checkpoints,
            "diagnostics",graph.snapshot().diagnostics(),"updatedAt",now());
        Set<String> finalCheckpoints=checkpoints;tx.executeWithoutResult(s->{store.lock("project",projectId);Map<String,Object> current=store.getMap("run",runId);store.save("evidence_graph_binding",bindingId,projectId,runId,binding);
            current.put("evidenceGraph",fields("revisionId",graph.snapshot().revision().id(),"contentDigest",graph.snapshot().revision().contentDigest(),"entityCount",graph.snapshot().entities().size(),"relationCount",graph.snapshot().relations().size(),
                "supportedRelationCount",graph.snapshot().relations().stream().filter(edge->edge.status()==cn.bidcheck.verification.graph.EvidenceGraph.RelationStatus.SUPPORTED).count(),"reused",graph.reused(),"changedNeedCount",changedNeeds.size(),"affectedCheckpointCount",finalCheckpoints.size(),"bindingId",bindingId));
            store.save("run",runId,projectId,bid.id(),current);});}

    /** Selects the latest completed, same-document graph revision and reopens only its supported
     * current-project source occurrences. A recovery parent is preferred when present. */
    private cn.bidcheck.verification.graph.FactLedgerEvidenceGraphBridge.PriorEvidenceReuse priorEvidenceGraph(String projectId,String runId,Document bid){
        if(evidenceGraphs==null)return new cn.bidcheck.verification.graph.FactLedgerEvidenceGraphBridge.PriorEvidenceReuse(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("status", "NOT_REUSED", "reason", "GRAPH_SERVICE_UNAVAILABLE", "acceptedOccurrenceCount", 0, "businessDecisionReused", false));
        Map<String,Object> current=store.getMap("run",runId);List<Map<String,Object>> candidates=new ArrayList<>();
        String parent=text(current,"parentRunId");if(!cn.bidcheck.compat.Java8Api.isBlank(parent))try{candidates.add(store.getMap("run",parent));}catch(RuntimeException ignored){/* fall through to other completed runs */}
        store.maps("run",projectId,null).stream().filter(run->!runId.equals(text(run,"id"))&&"COMPLETED".equals(text(run,"status")))
            .filter(run->bid.id().equals(text(run,"documentId"))&&run.get("documentVersion") instanceof Number&&bid.version()==((Number) (run.get("documentVersion"))).intValue())
            .sorted(Comparator.comparing((Map<String,Object> run)->text(run,"createdAt")).reversed()).forEach(candidates::add);
        Set<String> seen=new HashSet<>();
        for(Map<String,Object> candidate:candidates){String candidateRun=text(candidate,"id");if(!seen.add(candidateRun))continue;
            String revision=text(CheckResolution.map(candidate.get("evidenceGraph")),"revisionId");if(cn.bidcheck.compat.Java8Api.isBlank(revision))continue;
            cn.bidcheck.verification.graph.FactLedgerEvidenceGraphBridge.PriorEvidenceReuse reuse=evidenceGraphs.reusableCurrentProjectOccurrences(projectId,revision,cn.bidcheck.compat.Java8Api.listOf(bid));
            if("REVALIDATED".equals(reuse.audit().get("status"))){Map<String,Object> audit=new LinkedHashMap<>(reuse.audit());audit.put("sourceRunId",candidateRun);
                return new cn.bidcheck.verification.graph.FactLedgerEvidenceGraphBridge.PriorEvidenceReuse(reuse.occurrences(),audit);}
        }
        return new cn.bidcheck.verification.graph.FactLedgerEvidenceGraphBridge.PriorEvidenceReuse(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("status", "NOT_REUSED", "reason", "NO_STABLE_SAME_DOCUMENT_GRAPH", "acceptedOccurrenceCount", 0, "candidateEdgesAccepted", 0, "historicalEdgesAccepted", 0, "unresolvedEdgesAccepted", 0, "businessDecisionReused", false));
    }
    public Map<String,Object> templateDependencyGraph(String runId){Map<String,Object> run=store.getMap("run",runId);String projectId=text(run,"projectId");if(cn.bidcheck.compat.Java8Api.isBlank(projectId)||templateDependencyGraphs==null)return cn.bidcheck.compat.Java8Api.mapOf();return templateDependencyGraphs.get(runId);}

    /**
     * Re-evaluates only checkpoints that were frozen on the template route for a completed run.
     * The returned findings are candidates: this method deliberately performs no finding/run write.
     * {@link cn.bidcheck.checktemplate.incremental.TemplateIncrementalRecalculationService} owns the
     * publication fence and stores an explicit immutable recalculation receipt instead of changing
     * the historical report in place.
     */
    public Map<String,Finding> evaluateFrozenTemplateCandidates(String runId,Collection<String> checkpointIds){
        Map<String,Object> run=store.getMap("run",runId);String projectId=text(run,"projectId");
        if(!"COMPLETED".equals(text(run,"status")))throw bad("只有已完成的检查记录可以创建增量重算记录");
        if(!Boolean.TRUE.equals(run.get("templateRouteEnabled")))throw bad("本次检查没有启用固定模板执行路径");
        if(!templateCheckIdentity().equals(text(run,"templateCheckIdentity")))throw cn.bidcheck.checktemplate.TemplateApiException.conflict(
            "TEMPLATE_ENGINE_CHANGED","固定模板判定器已升级，请发起新的完整检查；历史结果保持原样",-1);
        if(run.containsKey("resolutionPolicyIdentity")&&!resolutionPolicyIdentity().equals(text(run,"resolutionPolicyIdentity")))throw cn.bidcheck.checktemplate.TemplateApiException.conflict(
            "RESULT_POLICY_CHANGED","结果保护或证据复核策略已升级，请发起新的完整检查；历史结果保持原样",-1);
        Document bid=document(text(run,"documentId"));scoped(bid,projectId,"BID");String tenderId=text(run,"tenderDocumentId");
        Document tender=cn.bidcheck.compat.Java8Api.isBlank(tenderId)?null:document(tenderId);if(tender!=null)scoped(tender,projectId,"TENDER");
        List<Fact> facts=facts(bid.id());Map<String,Checkpoint> frozen=new LinkedHashMap<>();
        for(Checkpoint checkpoint:store.list("snapshot",projectId,runId,Checkpoint.class))frozen.put(checkpoint.id(),checkpoint);
        List<String> targets=checkpointIds==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.toList(checkpointIds.stream().filter(Objects::nonNull).map(String::trim).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).distinct().sorted());
        Map<String,Finding> candidates=new LinkedHashMap<>();
        for(String checkpointId:targets){Checkpoint checkpoint=frozen.get(checkpointId);
            if(checkpoint==null)throw bad("依赖图引用了不属于本次检查快照的检查项："+checkpointId);
            if(!frozenTemplateRoute(projectId,runId,checkpoint))throw bad("依赖图引用了非模板检查项："+checkpointId);
            candidates.put(checkpointId,evaluateFinding(runId,checkpoint,bid,tender,facts,cn.bidcheck.compat.Java8Api.mapOf(),()->false,cn.bidcheck.compat.Java8Api.mapOf()));
        }
        return Collections.unmodifiableMap(candidates);
    }

    /** Stable identity for the current local evidence that a template candidate can consume. */
    public String templateEvidenceSnapshotDigest(String runId){
        Map<String,Object> run=store.getMap("run",runId);String projectId=text(run,"projectId");Document bid=document(text(run,"documentId"));scoped(bid,projectId,"BID");
        String tenderId=text(run,"tenderDocumentId");Document tender=cn.bidcheck.compat.Java8Api.isBlank(tenderId)?null:document(tenderId);if(tender!=null)scoped(tender,projectId,"TENDER");
        List<String> factSnapshot=cn.bidcheck.compat.Java8Api.toList(facts(bid.id()).stream().map(store::encode).sorted());Map<String,Object> evidence=new LinkedHashMap<>();
        evidence.put("bid",templateDocumentIdentity(bid));evidence.put("bidProcessing",processingCoverageSnapshot(bid));evidence.put("facts",factSnapshot);
        evidence.put("tender",tender==null?cn.bidcheck.compat.Java8Api.mapOf():templateDocumentIdentity(tender));evidence.put("tenderProcessing",tender==null?cn.bidcheck.compat.Java8Api.mapOf():processingCoverageSnapshot(tender));
        evidence.put("externalRecords",cn.bidcheck.compat.Java8Api.toList(store.maps(cn.bidcheck.checktemplate.TemplateExternalEvidenceService.RECORD_KIND,projectId,runId).stream().map(store::encode).sorted()));
        evidence.put("factRuleVersion",RULE_VERSION);evidence.put("templateEvidenceVersion",cn.bidcheck.checktemplate.TemplateEvidenceAssembler.VERSION);
        return hash(store.encode(evidence));
    }
    private Map<String,Object> templateDocumentIdentity(Document document){return fields("documentId",document.id(),"documentVersion",document.version(),"documentHash",document.sha256(),
        "contentDigest",hash(store.encode(document.pages())),"warningsDigest",hash(store.encode(document.warnings())));}
    public void fail(TaskRow task,String error) {
        task.status="FAILED"; task.error=error; task.stage="处理失败"; task.updatedAt=now();
        tx.executeWithoutResult(s->{persistTask(task); if("CHECK".equals(task.taskType)) updateRun(task,"FAILED",task.progress,task.stage,error);
            if("VERIFY".equals(task.taskType) && verification!=null) verification.getObject().taskState(task,"FAILED",error);});
    }
    private void updateRun(TaskRow task,String status,int progress,String stage,String error) {
        String id=text(store.map(task.inputJson),"runId"); Map<String,Object> run=store.getMap("run",id);
        run.put("status",status); run.put("progress",progress); run.put("stage",stage); run.put("error",error);
        if(cn.bidcheck.compat.Java8Api.setOf("COMPLETED", "FAILED").contains(status)) run.put("finishedAt",now()); else run.remove("finishedAt");
        if("FAILED".equals(status))for(Map<String,Object> pending:store.maps("check_resolution",task.projectId,id))if(CheckResolution.ACTIVE.contains(text(pending,"status"))){
            pending.put("status","FAILED");pending.put("stopReason","本轮执行中断，已完成证据保留，可继续未完成步骤。");pending.put("updatedAt",now());
            store.save("check_resolution",text(pending,"id"),task.projectId,id,pending);
        }
        store.save("run",id,task.projectId,text(run,"documentId"),run);
    }
    private void persistTask(TaskRow task) {
        if(store.mapper().updateTask(task)==0 && task.leaseOwner!=null) {
            TaskRow current=store.mapper().task(task.id);
            boolean unchanged=current!=null&&"RUNNING".equals(task.status)&&"RUNNING".equals(current.status)
                &&task.leaseOwner.equals(current.leaseOwner)&&current.leaseUntil!=null&&current.leaseUntil>=System.currentTimeMillis()
                &&task.progress==current.progress&&Objects.equals(task.stage,current.stage)&&Objects.equals(task.error,current.error)&&Objects.equals(task.updatedAt,current.updatedAt);
            if(!unchanged) throw new TaskLeaseLostException(task.id);
        }
    }
    private void progress(TaskRow task,int percent,String stage) { task.status="RUNNING"; task.progress=percent; task.stage=stage; task.updatedAt=now(); persistTask(task); }
    private void finish(TaskRow task,String stage) { task.status="SUCCEEDED"; task.progress=100; task.stage=stage; task.error=null; task.updatedAt=now(); persistTask(task); }
    private static MessageDigest sha() { try { return MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    public static String hash(String text) { return HexFormat.of().formatHex(sha().digest(text.getBytes(StandardCharsets.UTF_8))); }
    public static String stableId(String text) { return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString(); }
}
