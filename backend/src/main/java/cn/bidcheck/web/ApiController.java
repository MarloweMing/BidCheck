package cn.bidcheck.web;

import cn.bidcheck.document.DocumentLimits;
import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.TaskRow;
import cn.bidcheck.service.*;
import cn.bidcheck.retrieval.HybridRetriever;
import cn.bidcheck.verification.engine.VerificationModelClient;
import cn.bidcheck.verification.source.VisualVerificationService;
import cn.bidcheck.verification.graph.EvidenceGraphReadService;
import cn.bidcheck.checktemplate.CheckSpecificationStoreService;
import cn.bidcheck.checktemplate.TemplateApiException;
import cn.bidcheck.checktemplate.incremental.TemplateIncrementalRecalculationService;
import cn.bidcheck.checktemplate.incremental.TemplateIncrementalPublicationService;
import cn.bidcheck.checktemplate.mapping.RequirementMappingStoreService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final BidService service;
    private final ReportService reports;
    private final DemoService demos;
    private final HybridRetriever retrieval;
    private VerificationModelClient verificationModels;
    private VisualVerificationService visualModels;
    @Autowired(required=false)
    private cn.bidcheck.verification.agent.AgentInvestigator evidenceInvestigator;
    @Autowired(required=false)
    private EvidenceGraphReadService evidenceGraphReads;
    @Autowired(required=false)
    private CheckSpecificationStoreService checkSpecifications;
    @Autowired(required=false)
    private RequirementMappingStoreService requirementMappings;
    @Autowired(required=false)
    private TemplateIncrementalRecalculationService templateRecalculations;
    @Autowired(required=false)
    private TemplateIncrementalPublicationService templateIncrementalPublications;
    public ApiController(BidService service,ReportService reports,DemoService demos,HybridRetriever retrieval) { this.service=service; this.reports=reports; this.demos=demos;this.retrieval=retrieval; }
    @Autowired(required=false)
    void configureVerificationModels(VerificationModelClient verificationModels) { this.verificationModels=verificationModels; }
    @Autowired(required=false)
    void configureVisualModels(VisualVerificationService visualModels) { this.visualModels=visualModels; }
    @Autowired(required=false)
    void configureEvidenceGraphReads(EvidenceGraphReadService evidenceGraphReads) { this.evidenceGraphReads=evidenceGraphReads; }
    @GetMapping("/health") public Map<String,Object> health() { return Map.of("status","UP","version","0.1.0"); }
    @GetMapping("/processing-metrics") public Object processingMetrics() {
        return Map.of("scope","CURRENT_PROCESS","ocr",cn.bidcheck.ocr.OcrRequestGovernor.summary(),"ocrBatches",cn.bidcheck.ocr.OcrBatchMetrics.summary(),
            "pdfOcr",cn.bidcheck.ocr.QwenPdfOcrClient.summary(),
            "boundary","当前进程累计调度计数与耗时，不代表单份文档费用或识别准确率；重启后重新累计");
    }
    @GetMapping("/config") public Map<String,Object> config() { return BidService.fields("appName","BidCheck 标书检查","version","0.1.0","storageMode",service.storageMode(),"ocrEnabled",service.ocrEnabled(),"ocrModel",service.ocrModel(),"ocrMaxImages",service.ocrMaxImages(),"ocrConcurrency",service.ocrConcurrency(),"ocrBatchMaxPages",service.ocrBatchMaxPages(),"formats",List.of("pdf","docx","txt","md","png","jpg","jpeg"),"semanticEnabled",service.semanticEnabled(),"semanticModel",service.semanticModel(),"maxFileSizeMb",DocumentLimits.MAX_FILE_MB,
        "pdfOcrEnabled",service.pdfOcrEnabled(),"pdfOcrModel",service.pdfOcrModel(),"pdfOcrBatchPages",service.pdfOcrBatchPages(),"pdfOcrConcurrency",service.pdfOcrConcurrency(),"pdfOcrMaxPages",service.pdfOcrMaxPages(),
        "pageRoutingEnabled",true,"contextWindowPages",3,"contextOverlapPages",1,"incrementalChecksEnabled",true,"processingFactStorage","RELATIONAL_PAGE_FACTS",
        "verificationEnabled",verificationModels!=null&&verificationModels.enabled(),"semanticExtractionModel",verificationModels==null?"":verificationModels.extractionModelName(),
        "agentInvestigationEnabled",evidenceInvestigator!=null&&evidenceInvestigator.enabled(),"agentInvestigationDomains",List.of("PERSONNEL","SIGNING_AUTHORITY"),
        "agentReviewEnabled",service.agentReviewAvailable(),"defaultReviewMode","FIXED_WORKFLOW","reviewModes",List.of("FIXED_WORKFLOW","AGENT_LED"),
        "semanticVerificationModel",verificationModels==null?"":verificationModels.primaryModelName(),"semanticRoutingPolicy",verificationModels==null?"":verificationModels.routingPolicy(),
        "automaticVisualProcessingEnabled",service.automaticVisualProcessingEnabled(),"documentProcessingMode",service.documentProcessingMode(),
        "visualEnabled",visualModels!=null&&visualModels.enabled(),"visualModel",visualModels==null?"":visualModels.modelName(),
        "visualCompactStructuredOutputEnabled",visualModels!=null&&visualModels.compactStructuredOutputEnabled(),
        "visualSourceSpanOutputEnabled",visualModels!=null&&visualModels.sourceSpanOutputEnabled(),
        "visualStructuredWireVersion",visualModels==null?"":visualModels.structuredWireVersion(),
        "visualStructuredRoutingPolicy",visualModels==null?"":visualModels.structuredRoutingPolicy(),
        "maxDocumentsPerProject",BidService.MAX_DOCUMENTS_PER_PROJECT,"checkCategories",BidService.CHECK_CATEGORIES,
        "checkMode",service.checkMode(),"onDemandAttemptBudget",service.onDemandAttemptBudget(),"onDemandAttemptsUnlimited",true,"onDemandPolicyVersion",service.onDemandPolicyVersion(),
        "focusedCheckIdentity",service.focusedCheckIdentity(),"focusedCheckDomains",List.of("PERSONNEL_CONSORTIUM","CERTIFICATE_VALIDITY","PRICE_BASIS"),
        "templateRouteEnabled",service.templateRouteEnabled(),"templateCheckIdentity",service.templateCheckIdentity(),"templateCatalogCount",checkSpecifications==null?0:checkSpecifications.templates().size(),"templateSpecSchema",CheckSpecificationStoreService.SPEC_SCHEMA,"templateSnapshotSchema",CheckSpecificationStoreService.SNAPSHOT_SCHEMA,
        "checkConcurrency",service.checkConcurrency(),"ocrPlanningMode",service.ocrPlanningMode(),"factStorage","RELATIONAL_V3","ocrLayoutEnabled",service.ocrLayoutEnabled(),"ocrLayoutModel",service.ocrLayoutModel(),"ragEnabled",retrieval.enabled(),"ragEmbeddingModel",retrieval.enabled()?retrieval.embeddingModelName():"","ragRerankModel",retrieval.enabled()?retrieval.rerankModelName():"",
        "capabilities",List.of("PDF/DOCX 原生解析",service.ocrEnabled()?"扫描页与图片OCR识别":"OCR可配置启用","规则抽取与确认",retrieval.enabled()?"关键词与向量混合检索、语义重排":"关键词检索","跨页事实比对","人员、单位、证书、联合体和项目关系追溯","证据定位与人工复核","持久化任务与报告导出"),
        "limitations",List.of(service.ocrEnabled()?"待识别的页面和图片会发送至百炼；OCR文字需对照原图核实，不能证明签字盖章有效":"OCR尚未启用，扫描页与图片需人工核实",service.semanticEnabled()?"语义模型只提取有原文来源的候选事实；业务结论由程序规则计算，未映射条款会保留为系统缺口":"复杂技术响应需人工复核，尚未配置语义模型","本版为单用户工作台，多人生产环境仍需身份认证与访问权限")); }
    @GetMapping("/projects") public Object projects() { return service.projects(); }
    @PostMapping("/projects/{id}/facts/migrate") public Object migrateFacts(@PathVariable String id) { return publicTask(service.migrateProjectFacts(id)); }
    @GetMapping("/projects/{id}/facts/migrations") public Object factMigrations(@PathVariable String id) { return service.factMigrations(id); }
    @PostMapping("/projects") public Object create(@RequestBody Map<String,Object> body) { return service.createProject(body); }
    @PutMapping("/projects/{id}") public Object update(@PathVariable String id,@RequestBody Map<String,Object> body) { return service.updateProject(id,body); }
    @GetMapping("/projects/{id}") public Object project(@PathVariable String id,@RequestParam(defaultValue="true") boolean includeContent) { return service.detail(id,includeContent); }
    @PostMapping("/projects/{id}/documents") public Object upload(@PathVariable String id,@RequestParam String role,@RequestParam MultipartFile file) throws IOException { return publicTask(service.upload(id,role,file)); }
    @GetMapping("/tasks/{id}") public Object task(@PathVariable String id) { return publicTask(service.task(id)); }
    @PostMapping("/tasks/{id}/retry") public Object retry(@PathVariable String id) { return publicTask(service.retry(id)); }
    @PostMapping("/projects/{id}/checkpoints/extract") public Object extract(@PathVariable String id,@RequestBody Map<String,Object> body) { return publicTask(service.extract(id,BidService.text(body,"documentId"))); }
    @GetMapping("/projects/{id}/requirement-mapping") public Object requirementMapping(@PathVariable String id,@RequestParam(defaultValue="false") boolean includeDetails) { return requirementMappings().current(id,includeDetails); }
    @PostMapping("/projects/{id}/requirement-mapping/refresh") public Object refreshRequirementMapping(@PathVariable String id,@RequestBody(required=false) Map<String,Object> body,@RequestParam(defaultValue="false") boolean includeDetails) { return requirementMappings().refresh(id,body==null?"":BidService.text(body,"documentId"),includeDetails); }
    @PutMapping("/checkpoints/{id}") public Object checkpoint(@PathVariable String id,@RequestBody Checkpoint checkpoint) { return service.updateCheckpoint(id,checkpoint); }
    @PutMapping("/checkpoints/{id}/configuration") public Object checkpointConfiguration(@PathVariable String id,@RequestBody Map<String,Object> body) { return service.updateCheckpointConfiguration(id,body); }
    @PostMapping("/projects/{id}/checkpoints") public Object addCheckpoint(@PathVariable String id,@RequestBody Map<String,Object> body) { return service.createCheckpoint(id,body); }
    @DeleteMapping("/checkpoints/{id}") public Object deleteCheckpoint(@PathVariable String id) { return service.deleteCheckpoint(id); }
    @GetMapping("/check-templates") public Object checkTemplates() { return specifications().templates(); }
    @GetMapping("/checkpoints/{id}/spec") public Object checkSpec(@PathVariable String id) { return specifications().getSpec(id); }
    @PutMapping("/checkpoints/{id}/spec") public Object checkSpec(@PathVariable String id,@RequestBody Map<String,Object> body) { return specifications().putSpec(id,body); }
    @GetMapping("/projects/{projectId}/check-sets/{setId}") public Object checkSet(@PathVariable String projectId,@PathVariable String setId) { return specifications().getCheckSet(projectId,setId); }
    @PutMapping("/projects/{projectId}/check-sets/{setId}") public Object checkSet(@PathVariable String projectId,@PathVariable String setId,@RequestBody Map<String,Object> body) { return specifications().putCheckSet(projectId,setId,body); }
    @PostMapping("/projects/{projectId}/check-sets/{setId}/confirm") public Object confirmCheckSet(@PathVariable String projectId,@PathVariable String setId,@RequestBody Map<String,Object> body) { return specifications().confirmCheckSet(projectId,setId,body); }
    @PostMapping("/projects/{id}/runs") public Object run(@PathVariable String id,@RequestBody Map<String,Object> body) {
        Integer manifest=body.get("manifestRevision") instanceof Number number?number.intValue():null;
        return service.startRun(id,BidService.text(body,"documentId"),Boolean.TRUE.equals(body.get("force")),BidService.text(body,"checkSetId"),manifest,BidService.text(body,"idempotencyKey"),BidService.text(body,"reviewMode"));
    }
    @GetMapping("/runs/{id}") public Object run(@PathVariable String id,@RequestParam(defaultValue="true") boolean includeContent) { return service.run(id,includeContent); }
    @GetMapping("/runs/{id}/evidence-graph") public Object evidenceGraph(@PathVariable String id,
            @RequestParam(name="predicate",required=false) List<String> predicates) {
        return evidenceGraphReads().graph(id,predicates);
    }
    @GetMapping("/runs/{id}/evidence-graph/proof") public Object evidenceGraphProof(@PathVariable String id,
            @RequestParam String from,@RequestParam String to,
            @RequestParam(required=false) List<String> predicates,
            @RequestParam(defaultValue="4") int maxDepth) {
        return evidenceGraphReads().proof(id,from,to,predicates,maxDepth);
    }
    @GetMapping("/runs/{id}/dependency-graph") public Object dependencyGraph(@PathVariable String id) { return service.templateDependencyGraph(id); }
    @PostMapping("/runs/{id}/dependency-recalculations/plan") public Object dependencyRecalculationPlan(@PathVariable String id,@RequestBody Map<String,Object> body) { return recalculations().plan(id,requiredRevision(body),strings(body.get("changedNeedKeys"))); }
    @PostMapping("/runs/{id}/dependency-recalculations") public Object dependencyRecalculation(@PathVariable String id,@RequestBody Map<String,Object> body) { return recalculations().recalculate(id,requiredRevision(body),strings(body.get("changedNeedKeys")),BidService.text(body,"expectedSourceResultDigest")); }
    @GetMapping("/runs/{id}/dependency-recalculations") public Object dependencyRecalculations(@PathVariable String id) { return recalculations().list(id); }
    @GetMapping("/runs/{id}/dependency-recalculations/{recordId}") public Object dependencyRecalculation(@PathVariable String id,@PathVariable String recordId) { return recalculations().get(id,recordId); }
    @GetMapping("/runs/{id}/dependency-recalculations/{recordId}/decision") public Object dependencyRecalculationDecision(@PathVariable String id,@PathVariable String recordId) { return incrementalPublications().decision(id,recordId); }
    @PostMapping("/runs/{id}/dependency-recalculations/{recordId}/decision") public Object decideDependencyRecalculation(@PathVariable String id,@PathVariable String recordId,@RequestBody Map<String,Object> body) { return incrementalPublications().decide(id,recordId,body); }
    @GetMapping("/runs/{id}/result-versions") public Object resultVersions(@PathVariable String id) { return incrementalPublications().versions(id); }
    @GetMapping("/runs/{id}/result-versions/current") public Object currentResultVersion(@PathVariable String id) { return incrementalPublications().currentVersion(id); }
    @GetMapping("/runs/{id}/result-versions/{versionId}") public Object resultVersion(@PathVariable String id,@PathVariable String versionId) { return incrementalPublications().version(id,versionId); }
    @PostMapping("/projects/{projectId}/runs/{runId}/recover") public Object recover(@PathVariable String projectId,@PathVariable String runId) { return service.recoverRun(projectId,runId); }
    @PostMapping("/results/{id}/review") public Object review(@PathVariable String id,@RequestBody Map<String,Object> body) { return service.review(id,body); }
    @GetMapping("/documents/{id}") public Object document(@PathVariable String id,@RequestParam(defaultValue="false") boolean includeLayout) {
        Document doc=service.document(id);if(includeLayout)return doc;
        // Large OCR geometry is loaded for a selected page; normal source previews remain bounded to text.
        return new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),doc.version(),doc.sha256(),doc.createdAt(),doc.pages().stream().map(p->new Page(p.number(),p.text(),p.locationType(),p.parser(),p.warning())).toList(),doc.warnings());
    }
    @GetMapping("/documents/{id}/profile") public Object profile(@PathVariable String id) { return service.profile(id); }
    @GetMapping("/documents/{id}/ocr-plan") public Object ocrPlan(@PathVariable String id) { service.document(id);return service.ocrPlan(id); }
    @GetMapping("/documents/{id}/ocr-progress") public Object ocrProgress(@PathVariable String id) { return service.ocrProgress(id); }
    @GetMapping("/documents/{id}/facts") public Object facts(@PathVariable String id) { return service.facts(id); }
    @GetMapping("/documents/{id}/processing") public Object processing(@PathVariable String id) { return service.processing(id); }
    @GetMapping("/documents/{id}/processing-facts") public Object processingFacts(@PathVariable String id) { return service.processingFacts(id); }
    @PostMapping("/documents/{id}/processing/continue") public Object continueProcessing(@PathVariable String id) { return publicTask(service.continueProcessing(id)); }
    @PostMapping("/documents/{id}/ocr") public Object recognize(@PathVariable String id) throws IOException { return publicTask(service.recognizeAgain(id)); }
    @PostMapping("/documents/{id}/ocr-plan") public Object recognizePlan(@PathVariable String id) throws IOException { return publicTask(service.recognizeForChecks(id)); }
    @PostMapping("/documents/{id}/ocr-continue") public Object recognizeRemaining(@PathVariable String id) throws IOException { return publicTask(service.recognizeRemaining(id)); }
    @GetMapping("/documents/{id}/raw") public ResponseEntity<FileSystemResource> file(@PathVariable String id) throws IOException {
        Document d=service.document(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(d.name(),StandardCharsets.UTF_8).build().toString())
            .body(new FileSystemResource(service.path(id)));
    }
    @GetMapping(value="/documents/{id}/pages/{page}/image",produces=MediaType.IMAGE_PNG_VALUE)
    public byte[] page(@PathVariable String id,@PathVariable int page) throws IOException { return service.pageImage(id,page); }
    @GetMapping("/runs/{id}/report") public ResponseEntity<byte[]> report(@PathVariable String id,@RequestParam(defaultValue="html") String format,
                                                                           @RequestParam(defaultValue="source") String resultVersion) {
        ReportService.Report report=reports.generate(id,format,resultVersion);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(report.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=BidCheck-"+id+"."+format).body(report.bytes());
    }
    @PostMapping("/demo") public Object demo() throws IOException { return demos.create(); }
    @ExceptionHandler(TemplateApiException.class)
    public ResponseEntity<Map<String,Object>> templateError(TemplateApiException failure) {
        Map<String,Object> body=new LinkedHashMap<>();body.put("code",failure.code());body.put("message",failure.getMessage());body.put("retryable",failure.retryable());body.put("fieldErrors",failure.fieldErrors());
        if(failure.currentRevision()!=null)body.put("currentRevision",failure.currentRevision());return ResponseEntity.status(failure.status()).body(body);
    }
    private CheckSpecificationStoreService specifications() {
        if(checkSpecifications==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模板检查规格服务尚未启用");return checkSpecifications;
    }
    private RequirementMappingStoreService requirementMappings() {
        if(requirementMappings==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"条款模板覆盖服务尚未启用");return requirementMappings;
    }
    private TemplateIncrementalRecalculationService recalculations() {
        if(templateRecalculations==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模板增量重算服务尚未启用");return templateRecalculations;
    }
    private TemplateIncrementalPublicationService incrementalPublications() {
        if(templateIncrementalPublications==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"增量候选审核发布服务尚未启用");return templateIncrementalPublications;
    }
    private EvidenceGraphReadService evidenceGraphReads() {
        if(evidenceGraphReads==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"证据图查询服务尚未启用");return evidenceGraphReads;
    }
    private static long requiredRevision(Map<String,Object> body){Object value=body.get("expectedRevision");if(!(value instanceof Number number)||number.longValue()<1)
        throw TemplateApiException.invalid("增量重算请求无效",List.of(Map.of("field","expectedRevision","message","必须提供正整数依赖图版本")));return number.longValue();}
    private static List<String> strings(Object value){if(!(value instanceof Collection<?> collection))return List.of();return collection.stream().filter(Objects::nonNull).map(Object::toString).toList();}
    private Map<String,Object> publicTask(TaskRow t) { return service.publicTask(t); }
}
