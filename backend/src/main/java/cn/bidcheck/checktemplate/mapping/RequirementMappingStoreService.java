package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import cn.bidcheck.verification.external.ExternalVerificationAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persists source coverage and non-executable template drafts for the active tender. */
@Service
public class RequirementMappingStoreService {
    public static final String SCHEMA = "requirement-mapping-snapshot-v1";
    public static final String VERSION = DocumentClauseTreeBuilder.VERSION + "|requirement-mapper-2";
    private static final String KIND = "requirement_mapping";
    private static final int SUMMARY_ITEM_LIMIT = 30;

    private final Store store;
    private final TransactionTemplate tx;
    private final RequirementMappingService mapper;
    private final DocumentClauseTreeBuilder treeBuilder = new DocumentClauseTreeBuilder();

    @Autowired
    public RequirementMappingStoreService(Store store, PlatformTransactionManager transactionManager,
                                          ExternalVerificationAdapter externalAdapter) {
        this.store = store;
        this.tx = new TransactionTemplate(transactionManager);
        this.mapper = new RequirementMappingService(externalAdapter == null
                ? cn.bidcheck.compat.Java8Api.mapOf() : externalAdapter.enabledSourceTypes());
    }

    public RequirementMappingSnapshot analyze(Document document) {
        if (document == null) throw new IllegalArgumentException("document is required");
        RequirementMappingResult result = mapper.map(treeBuilder.build(document));
        Map<CoverageItem.Status, Long> counts = new EnumMap<>(CoverageItem.Status.class);
        for (CoverageItem.Status status : CoverageItem.Status.values()) counts.put(status, 0L);
        for (CoverageItem item : result.coverage()) counts.merge(item.status(), 1L, Long::sum);
        long checking = result.coverage().size() - counts.get(CoverageItem.Status.NON_CHECKING);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "READY");
        summary.put("checkingFragments", checking);
        summary.put("mapped", counts.get(CoverageItem.Status.MAPPED));
        summary.put("needsConfirmation", counts.get(CoverageItem.Status.NEEDS_CONFIRMATION));
        summary.put("externalRequired", counts.get(CoverageItem.Status.EXTERNAL_REQUIRED));
        summary.put("unsupported", counts.get(CoverageItem.Status.UNSUPPORTED));
        summary.put("nonChecking", counts.get(CoverageItem.Status.NON_CHECKING));
        summary.put("draftCount", result.drafts().size());
        summary.put("clauseCount", result.diagnostics().clauseCount());
        summary.put("fragmentCount", result.diagnostics().fragmentCount());
        summary.put("localOnly", result.diagnostics().localOnly());
        return new RequirementMappingSnapshot(SCHEMA, VERSION, document.projectId(), document.id(),
                document.version(), document.sha256(), Instant.now().toString(), "DRAFT_ONLY", false,
                summary, result.drafts(), result.coverage(), result.diagnostics());
    }

    /** Caller may already hold the project transaction/lock. */
    public void persist(RequirementMappingSnapshot snapshot) {
        if (snapshot == null) return;
        store.save(KIND, id(snapshot.tenderDocumentId()), snapshot.projectId(), snapshot.tenderDocumentId(), snapshot);
    }

    public Map<String, Object> refresh(String projectId, String requestedDocumentId, boolean includeDetails) {
        String documentId = selectTender(projectId, requestedDocumentId);
        Document document = store.get("document", documentId, Document.class);
        requireTender(projectId, document);
        RequirementMappingSnapshot snapshot = analyze(document);
        tx.executeWithoutResult(status -> {
            Map<String, Object> project = store.map(store.lock("project", projectId).body);
            String activeTender = text(project.get("activeTenderId"));
            if (!cn.bidcheck.compat.Java8Api.isBlank(activeTender) && !activeTender.equals(documentId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前招标文件已变更，请刷新后重试");
            }
            persist(snapshot);
        });
        return response(snapshot, includeDetails);
    }

    public Map<String, Object> current(String projectId, boolean includeDetails) {
        String documentId = selectTender(projectId, "");
        Stored stored = store.mapper().get(id(documentId));
        if (stored == null || !KIND.equals(stored.kind) || !projectId.equals(stored.projectId)) {
            return cn.bidcheck.compat.Java8Api.mapOf("status", "NOT_GENERATED", "projectId", projectId, "tenderDocumentId", documentId, "executionState", "DRAFT_ONLY", "affectsBusinessConclusion", false);
        }
        RequirementMappingSnapshot snapshot = store.decode(stored.body, RequirementMappingSnapshot.class);
        Document active = store.get("document", documentId, Document.class);
        if (!VERSION.equals(snapshot.mappingVersion())
                || active.version() != snapshot.tenderDocumentVersion()
                || !active.sha256().equals(snapshot.tenderDocumentHash())) {
            return cn.bidcheck.compat.Java8Api.mapOf("status", "STALE", "projectId", projectId, "tenderDocumentId", documentId, "executionState", "DRAFT_ONLY", "affectsBusinessConclusion", false, "reason", "条款整理规则或当前招标文件已变化，请重新整理条款模板覆盖");
        }
        return response(snapshot, includeDetails);
    }

    public Map<String, Object> currentSummary(String projectId) {
        Map<String, Object> full = current(projectId, false);
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : cn.bidcheck.compat.Java8Api.listOf("status", "projectId", "tenderDocumentId", "tenderDocumentVersion", "tenderDocumentHash", "affectsBusinessConclusion", "summary", "reviewItemCount", "reason")) {
            if (full.containsKey(key)) compact.put(key, full.get(key));
        }
        return compact;
    }

    private Map<String, Object> response(RequirementMappingSnapshot snapshot, boolean includeDetails) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "READY");
        response.put("schema", snapshot.schema());
        response.put("mappingVersion", snapshot.mappingVersion());
        response.put("projectId", snapshot.projectId());
        response.put("tenderDocumentId", snapshot.tenderDocumentId());
        response.put("tenderDocumentVersion", snapshot.tenderDocumentVersion());
        response.put("tenderDocumentHash", snapshot.tenderDocumentHash());
        response.put("generatedAt", snapshot.generatedAt());
        response.put("executionState", snapshot.executionState());
        response.put("affectsBusinessConclusion", snapshot.affectsBusinessConclusion());
        response.put("summary", snapshot.summary());
        response.put("reviewItems", reviewItems(snapshot.coverage()));
        long reviewCount = snapshot.coverage().stream().filter(item -> reviewStatus(item.status())).count();
        response.put("reviewItemCount", reviewCount);
        response.put("reviewItemsTruncated", reviewCount > SUMMARY_ITEM_LIMIT);
        if (includeDetails) {
            response.put("drafts", snapshot.drafts());
            response.put("coverage", snapshot.coverage());
            response.put("diagnostics", snapshot.diagnostics());
        }
        return response;
    }

    private List<Map<String, Object>> reviewItems(List<CoverageItem> coverage) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (CoverageItem item : coverage) {
            if (!reviewStatus(item.status())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fragmentId", item.fragmentId());
            row.put("status", item.status().name());
            row.put("text", item.text());
            row.put("explanation", item.explanation());
            row.put("templateIds", item.templateIds());
            row.put("documentId", item.sourceRef().documentId());
            row.put("location", item.sourceRef().location());
            row.put("locationType", item.sourceRef().locationType());
            items.add(row);
            if (items.size() == SUMMARY_ITEM_LIMIT) break;
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(items);
    }

    private static boolean reviewStatus(CoverageItem.Status status) {
        return status == CoverageItem.Status.NEEDS_CONFIRMATION
                || status == CoverageItem.Status.EXTERNAL_REQUIRED
                || status == CoverageItem.Status.UNSUPPORTED;
    }

    private String selectTender(String projectId, String requestedDocumentId) {
        Map<String, Object> project = store.getMap("project", projectId);
        String active = text(project.get("activeTenderId"));
        String requested = text(requestedDocumentId);
        String selected = cn.bidcheck.compat.Java8Api.isBlank(requested) ? active : requested;
        if (cn.bidcheck.compat.Java8Api.isBlank(selected)) throw new ResponseStatusException(HttpStatus.CONFLICT, "请先提取当前招标文件的检查项");
        if (!cn.bidcheck.compat.Java8Api.isBlank(active) && !active.equals(selected)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能刷新当前招标文件的条款映射");
        }
        return selected;
    }

    private static void requireTender(String projectId, Document document) {
        if (!projectId.equals(document.projectId()) || !"TENDER".equals(document.role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选文件不是当前项目的招标文件");
        }
    }

    private static String id(String documentId) { return "requirement-mapping:" + documentId; }
    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
}
