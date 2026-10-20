package cn.bidcheck.checktemplate;

import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Fact;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import cn.bidcheck.service.BidService;
import cn.bidcheck.verification.evidence.EvidenceOperationRepository;
import cn.bidcheck.verification.external.ExternalVerificationAdapter;
import java.io.InterruptedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Executes explicitly configured external template evidence requests outside the evaluator.
 *
 * <p>The endpoint and credentials remain administrator-owned by {@link ExternalVerificationAdapter}.
 * A template may select only an enabled adapter type and a frozen date. Responses are persisted per
 * run and converted to source-bound facts; uploaded declarations and unauthenticated records are
 * never admitted. The fixed evaluator remains deterministic and performs no network I/O.</p>
 */
@Service
public final class TemplateExternalEvidenceService {
    public static final String VERSION = "template-external-evidence-2";
    public static final String RECORD_KIND = "template_external_query";
    private static final Set<String> GENERIC_ORGANIZATION = Set.of(
            "投标人", "供应商", "响应人", "投标单位", "响应单位", "BIDDER", "ORGANIZATION");
    private static final Set<String> RISK_FIELDS = Set.of(
            "dishonestjudgmentdebtor", "majortaxviolation", "administrativepenalty", "abnormaloperation");
    private static final Set<String> STATUS_FIELDS = Set.of("creditstatus", "queryresult");

    private final Store store;
    private final ExternalVerificationAdapter adapter;
    private final EvidenceOperationRepository evidenceOperations;

    public TemplateExternalEvidenceService(Store store, ExternalVerificationAdapter adapter) {
        this.store = Objects.requireNonNull(store, "store");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.evidenceOperations = new EvidenceOperationRepository(store);
    }

    public String identity() { return BidService.hash(VERSION + "|" + adapter.identity()); }

    /** Returns trusted connector facts, or an empty list when the request is not safely executable. */
    public List<Fact> resolve(String projectId, String runId, CheckSpec spec, List<Fact> bidFacts) {
        if (spec == null || !"EXTERNAL_CHECK".equals(spec.templateId())) return List.of();
        Map<String, Object> parameters = spec.parameters();
        String adapterId = text(parameters.get("adapterId"));
        String source = text(parameters.get("source"));
        String queryObject = text(parameters.get("queryObject"));
        String asOfDate = text(parameters.get("asOfDate"));
        String stage = text(parameters.get("evaluationStage"));
        if (adapterId.isBlank() || source.isBlank() || queryObject.isBlank() || asOfDate.isBlank()
                || stage.isBlank() || !Set.of("BID_OPENING", "TENDER_DEADLINE", "EVALUATION").contains(stage)
                || !adapter.enabledTypes().contains(adapterId)
                || !adapterId.equals(adapter.enabledSourceTypes().get(source))) return List.of();
        try { LocalDate.parse(asOfDate); } catch (RuntimeException invalid) { return List.of(); }
        String subject = resolveSubject(spec, queryObject, bidFacts);
        if (subject.isBlank()) return List.of();

        Map<String, Object> query = Map.of("type", adapterId, "subject", subject,
                "evaluationStage", stage, "referenceDate", asOfDate);
        String legacyRecordId = BidService.stableId(VERSION + "|" + runId + "|" + store.encode(query)
                + "|" + adapter.identity());
        Map<String, Object> operationInput = new LinkedHashMap<>(query);
        operationInput.put("source", source);
        Map<String, Object> descriptor = EvidenceOperationRepository.descriptor(projectId,
                "EXTERNAL_SOURCE_QUERY", List.of(), List.of(Map.of("id", source)), operationInput,
                "", adapter.identity(), "TRUSTED_CONNECTOR_RECORD", VERSION);
        String operationKey = EvidenceOperationRepository.key(descriptor);
        Map<String, Object> response = cachedOrQuery(operationKey, legacyRecordId, projectId, runId,
                query, descriptor);
        return trustedFacts(spec, source, queryObject, adapterId, asOfDate, stage, subject, response);
    }

    private Map<String, Object> cachedOrQuery(String operationKey, String legacyId, String projectId,
                                               String runId, Map<String, Object> query,
                                               Map<String, Object> descriptor) {
        Map<String, Object> durable = evidenceOperations.completed(projectId, operationKey).orElse(null);
        if (durable != null) return durable;
        Stored legacy = store.mapper().get(legacyId);
        if (legacy != null) {
            Map<String, Object> response = validStored(legacy, projectId, runId, query);
            publishDurableCompleted(projectId, operationKey, descriptor, response);
            return response;
        }
        try (EvidenceOperationRepository.Lease ignored = evidenceOperations.coordinate(projectId, operationKey)) {
            durable = evidenceOperations.completed(projectId, operationKey).orElse(null);
            if (durable != null) return durable;
            legacy = store.mapper().get(legacyId);
            if (legacy != null) {
                Map<String, Object> response = validStored(legacy, projectId, runId, query);
                publishDurableCompleted(projectId, operationKey, descriptor, response);
                return response;
            }
            Map<String, Object> response = adapter.query(query);
            // A transport/configuration failure is an attempt, not an immutable evidence snapshot.
            // Keeping it under the deterministic query id would poison every later retry.
            if (!"COMPLETED".equals(text(response.get("status")))) return new LinkedHashMap<>(response);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("schemaVersion", VERSION); body.put("id", legacyId); body.put("projectId", projectId);
            body.put("sourceRunId", runId); body.put("adapterIdentity", adapter.identity());
            body.put("query", query); body.put("response", response);
            store.insertImmutableIfAbsent(RECORD_KIND, legacyId, projectId, runId, body);
            publishDurableCompleted(projectId, operationKey, descriptor, response);
            Map<String, Object> immutable = new LinkedHashMap<>(response);
            return immutable;
        } catch (InterruptedIOException interrupted) {
            throw new IllegalStateException("TEMPLATE_EXTERNAL_QUERY_INTERRUPTED", interrupted);
        }
    }

    /** Only a completed, connector-authenticated observation may enter the shared evidence cache. */
    private void publishDurableCompleted(String projectId, String operationKey,
                                         Map<String, Object> descriptor, Map<String, Object> response) {
        if (!"COMPLETED".equals(text(response.get("status")))) return;
        Map<String, Object> record = map(response.get("record"));
        if (!"TRUSTED_CONNECTOR".equals(text(record.get("provenanceStatus")))) return;
        evidenceOperations.publishCompleted(projectId, operationKey, descriptor, response);
    }

    private Map<String, Object> validStored(Stored row, String projectId, String runId,
                                             Map<String, Object> query) {
        if (!RECORD_KIND.equals(row.kind) || !projectId.equals(row.projectId) || !runId.equals(row.parentId))
            throw new IllegalStateException("TEMPLATE_EXTERNAL_RECORD_SCOPE_MISMATCH");
        Map<String, Object> body = store.map(row.body);
        if (!VERSION.equals(text(body.get("schemaVersion")))
                || !adapter.identity().equals(text(body.get("adapterIdentity")))
                || !query.equals(map(body.get("query"))))
            throw new IllegalStateException("TEMPLATE_EXTERNAL_RECORD_IDENTITY_MISMATCH");
        return map(body.get("response"));
    }

    private List<Fact> trustedFacts(CheckSpec spec, String expectedSource, String queryObject,
                                    String adapterId, String asOfDate, String expectedStage, String actualSubject,
                                    Map<String, Object> response) {
        if (!"COMPLETED".equals(text(response.get("status")))) return List.of();
        Map<String, Object> record = map(response.get("record"));
        if (!"TRUSTED_CONNECTOR".equals(text(record.get("provenanceStatus")))) return List.of();
        if (!adapterId.equals(text(record.get("type"))) || !actualSubject.equals(text(record.get("subject")))
                || !asOfDate.equals(text(record.get("effectiveAt")))
                || !expectedStage.equals(text(record.get("evaluationStage")))
                || !Boolean.TRUE.equals(record.get("coverageComplete"))
                || text(record.get("rawSnapshotHash")).isBlank()
                || !sourceMatches(expectedSource, text(record.get("source")))) return List.of();
        Map<String, Object> result = map(record.get("result"));
        if (!"VERIFIED".equals(text(result.get("status")))) return List.of();

        Set<String> required = new LinkedHashSet<>(strings(spec.parameters().get("requiredFields")));
        List<Fact> facts = new ArrayList<>();
        for (Map<String, Object> item : maps(result.get("facts"))) {
            String field = text(item.get("field"));
            if (!required.contains(field)) continue;
            String raw = text(item.get("rawValue"));
            String quote = text(item.get("sourceQuote"));
            if (raw.isBlank() || quote.isBlank() || !quote.contains(raw)) continue;
            String normalized = externalState(field, item.get("value"));
            if (normalized.isBlank() || "UNRESOLVED".equals(normalized)) continue;
            String anchor = "provenance:TRUSTED_CONNECTOR|adapter:" + adapterId + "|asOf:" + asOfDate
                    + "|stage:" + expectedStage
                    + "|subjectHash:" + BidService.hash(actualSubject)
                    + "|rawSnapshotHash:" + text(record.get("rawSnapshotHash"));
            Evidence evidence = new Evidence("external:" + BidService.hash(adapterId + "|" + actualSubject),
                    text(record.get("source")), 0, "TRUSTED_CONNECTOR", quote, anchor, 1d);
            facts.add(new Fact("TRUSTED_CONNECTOR", queryObject, field, raw, normalized, evidence));
        }
        return List.copyOf(facts);
    }

    private static String resolveSubject(CheckSpec spec, String queryObject, List<Fact> bidFacts) {
        for (String key : List.of("querySubject", "subjectName", "name")) {
            String value = spec.subjectSelector().constraints().getOrDefault(key, "").strip();
            if (!value.isBlank()) return value;
        }
        if (!GENERIC_ORGANIZATION.contains(queryObject.toUpperCase(Locale.ROOT))
                && !GENERIC_ORGANIZATION.contains(queryObject)) return queryObject;
        Set<String> names = new LinkedHashSet<>();
        if (bidFacts != null) for (Fact fact : bidFacts) {
            if (fact == null || !"companyName".equals(fact.field()) || fact.evidence() == null) continue;
            if (!Set.of("BIDDER", "ORGANIZATION").contains(text(fact.entityKey()).toUpperCase(Locale.ROOT))) continue;
            String value = text(fact.normalizedValue());
            if (value.isBlank() || "UNRESOLVED".equalsIgnoreCase(value)) value = text(fact.rawValue());
            if (!value.isBlank()) names.add(value);
        }
        return names.size() == 1 ? names.iterator().next() : "";
    }

    private static String externalState(String field, Object value) {
        String token = text(value).strip();
        String normalized = token.toUpperCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        String normalizedField = field.toLowerCase(Locale.ROOT);
        if (RISK_FIELDS.contains(normalizedField)) {
            if (Set.of("FALSE", "0", "NO", "CLEAR", "NOTFOUND", "NONE", "NORMAL", "正常", "否", "无", "未列入", "未发现").contains(normalized))
                return "CLEAR";
            if (Set.of("TRUE", "1", "YES", "RISK", "HIT", "FOUND", "ACTIVE", "ABNORMAL", "异常", "是", "有", "已列入", "列入").contains(normalized))
                return "RISK";
            return "UNRESOLVED";
        }
        // Generic connector fields have no declared comparison semantics. Only the two status
        // fields produced by automatic mapping accept explicit status vocabulary; arbitrary
        // values (including a boolean whose meaning is unknown) cannot become an automatic pass.
        if (STATUS_FIELDS.contains(normalizedField)) {
            if (Set.of("CLEAR", "PASS", "NORMAL", "GOOD", "COMPLIANT", "NORISK", "正常", "良好", "合规", "无异常", "未发现风险").contains(normalized))
                return "CLEAR";
            if (Set.of("RISK", "HIT", "FAIL", "ABNORMAL", "BAD", "NONCOMPLIANT", "异常", "不良", "不合规", "发现风险").contains(normalized))
                return "RISK";
        }
        return "UNRESOLVED";
    }

    private static boolean sourceMatches(String expected, String actual) {
        String left = compact(expected), right = compact(actual);
        return !left.isBlank() && left.equals(right);
    }
    private static String compact(String value) { return text(value).replaceAll("[\\s\\p{Z}]+", "").toLowerCase(Locale.ROOT); }
    private static String text(Object value) { return Objects.toString(value, "").strip(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }
    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().filter(Objects::nonNull).map(Object::toString).map(String::strip)
                .filter(item -> !item.isBlank()).toList();
    }
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().filter(Map.class::isInstance).map(TemplateExternalEvidenceService::map).toList();
    }
}
