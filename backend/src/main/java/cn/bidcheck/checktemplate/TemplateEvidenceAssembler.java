package cn.bidcheck.checktemplate;

import cn.bidcheck.document.PersonCredentialFacts;
import cn.bidcheck.checktemplate.evidence.EvidenceExtractionContext;
import cn.bidcheck.checktemplate.evidence.EvidenceExtractionResult;
import cn.bidcheck.checktemplate.evidence.EvidenceNeedCoordinator;
import cn.bidcheck.checktemplate.evidence.EvidenceNeedKey;
import cn.bidcheck.checktemplate.evidence.EvidenceReadReceipt;
import cn.bidcheck.checktemplate.evidence.EvidenceScope;
import cn.bidcheck.checktemplate.evidence.EvidenceTaskStatus;
import cn.bidcheck.checktemplate.evidence.LocalEvidenceExtractor;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Fact;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.engine.FactExtractor;
import cn.bidcheck.verification.engine.DocumentScopeProof;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the existing, source-bound local extraction into the closed observation types used by
 * the fixed templates.  This class is intentionally a pure local adapter: it never invokes a
 * model, never compiles a free-form requirement and never treats display text as executable code.
 */
@Component
public final class TemplateEvidenceAssembler {
    public static final String VERSION = "template-evidence-assembler-10";
    private static final String SINGLE_FLIGHT_EXTRACTOR = "template-local-evidence";
    private static final int INPUT_SNAPSHOT_CACHE_LIMIT = 32;
    private final EvidenceNeedCoordinator evidenceCoordinator;
    private final CheckTemplateRegistry templateRegistry;
    private final LinkedHashMap<SnapshotCacheKey, InputSnapshot> inputSnapshotCache =
            new LinkedHashMap<>(INPUT_SNAPSHOT_CACHE_LIMIT, 0.75f, true);

    /**
     * Pipeline terminal markers, deliberately narrower than ordinary document vocabulary.  Words
     * such as “截断” or “处理失败” can occur in a bidder's prose and are not proof that OCR failed.
     */
    private static final Pattern INCOMPLETE = Pattern.compile(
            "\\[?(?:OCR未完成|OCR_DEFERRED|OCR_FAILED|识别未完成|解析未完成|页数超限)\\]?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HISTORY = Pattern.compile(
            "历史业绩|历史项目|类似(?:项目)?业绩|业绩(?:合同|证明|材料|情况|汇总)|主要工作经历|工作履历|"
                    + "参加过的类似项目|已完(?:工|成)项目|合同复印件|合同协议书|合同总价|合同价款|竣工结算|竣工验收");
    private static final Pattern CURRENT = Pattern.compile(
            "本项目|本工程|本次投标|本次招标|当前项目|拟投入|拟派|拟在本合同任职|项目管理机构|"
                    + "投标函|开标一览表|投标报价表|投标人资格证明|投标人基本情况");
    private static final Pattern INDEX_ENTRY = Pattern.compile(
            "(?m)^.{0,160}(?:\\.{3,}|…{2,}|·{3,})[ \\t]*\\d{1,5}[ \\t]*$");
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(\\d{4})[年./\\-](\\d{1,2})[月./\\-](\\d{1,2})日?(?!\\d)");
    private static final Pattern DURATION_VALUE = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(工作日|日历日|天|日|个月|月|年)");
    private static final Pattern OBVIOUS_TEXT_ANOMALY = Pattern.compile(
            "\\uFFFD|\\[OCR_(?:FAILED|DEFERRED)\\]|\\[OCR未完成\\]|(?:^|[\\s，。；：])[_＿—-]{3,}(?:$|[\\s，。；：])");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)^(?:[_＿—-]+|待填|未填|空白|N/?A|NULL|NONE|XX+|某某|\\[.*?\\]|【.*?】)$");
    private static final Pattern LOT = Pattern.compile(
            "第?([A-Za-z0-9一二三四五六七八九十_-]{1,24})(?:标段|标包|包件)|(?:标段|标包|包件|包号)[ \\t]*[:：=]?[ \\t]*([A-Za-z0-9一二三四五六七八九十_-]{1,24})");
    private static final Set<String> CERTIFICATE_NUMBER_FIELDS = Set.of(
            "certificateNumber", "idNumber", "drivingLicenseNumber", "passportNumber");
    private static final Set<String> TRUSTED_SEAL_ENTITIES = Set.of(
            "SIGNATURE_SEAL", "VISUAL_SEAL", "VISUAL_OBSERVATION");
    private static final Set<String> TRUSTED_SEAL_FIELDS = Set.of(
            "seal", "sealObserved", "visualSeal", "observationType");
    /**
     * The tender composition list names logical volumes while bidders commonly print a shorter
     * title on the actual cover/index.  Keep this vocabulary deliberately closed: a fuzzy match
     * here could turn a missing mandatory volume into an automatic pass.
     */
    private static final Map<String, MaterialAliasProfile> MATERIAL_ALIAS_PROFILES = Map.of(
            normalize("第一分册 商务投标文件"), new MaterialAliasProfile(MaterialAliasKind.BUSINESS_VOLUME,
                    List.of("第一分册 商务投标文件", "第一分册 商务文件", "商务投标文件", "投标文件（商务部分）")),
            normalize("商务技术评审索引表"), new MaterialAliasProfile(MaterialAliasKind.BUSINESS_INDEX,
                    List.of("商务技术评审索引表", "商务评审索引表")),
            normalize("第二分册 技术投标文件"), new MaterialAliasProfile(MaterialAliasKind.TECHNICAL_VOLUME,
                    List.of("第二分册 技术投标文件", "第二分册 技术文件", "技术投标文件", "投标文件（技术部分）")),
            normalize("技术投标索引表"), new MaterialAliasProfile(MaterialAliasKind.TECHNICAL_INDEX,
                    List.of("技术投标索引表", "技术评审索引表")),
            normalize("技术建议书"), new MaterialAliasProfile(MaterialAliasKind.TECHNICAL_PROPOSAL,
                    List.of("技术建议书")));

    public TemplateEvidenceAssembler() {
        this.evidenceCoordinator = null;
        this.templateRegistry = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TemplateEvidenceAssembler(org.springframework.beans.factory.ObjectProvider<EvidenceNeedCoordinator> coordinator,
                                     CheckTemplateRegistry templateRegistry) {
        this(coordinator.getIfAvailable(), templateRegistry);
    }

    TemplateEvidenceAssembler(EvidenceNeedCoordinator coordinator, CheckTemplateRegistry templateRegistry) {
        this.evidenceCoordinator = coordinator;
        this.templateRegistry = Objects.requireNonNull(templateRegistry, "templateRegistry");
    }

    public Assembly assemble(CheckSpec spec, Checkpoint checkpoint, Document bid, Document tender,
                             List<Fact> inputFacts) {
        if (evidenceCoordinator != null && templateRegistry != null) {
            return assembleSingleFlight(spec, checkpoint, bid, tender, inputFacts);
        }
        return assembleLocal(spec, checkpoint, bid, tender, inputFacts);
    }

    private Assembly assembleLocal(CheckSpec spec, Checkpoint checkpoint, Document bid, Document tender,
                                   List<Fact> inputFacts) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(bid, "bid");
        List<Fact> facts = inputFacts == null ? List.of() : inputFacts.stream()
                .filter(Objects::nonNull)
                .filter(fact -> fact.evidence() != null && (bid.id().equals(fact.evidence().documentId())
                        || trustedConnectorFact(fact)))
                .toList();
        Context context = new Context(spec, checkpoint, bid, tender, facts);

        switch (spec.templateId()) {
            case "MATERIAL_PRESENT" -> materialPresent(context);
            case "COMMITMENT_WITH_SEAL" -> commitmentWithSeal(context);
            case "MATERIAL_ALTERNATIVE" -> materialAlternative(context);
            case "CERTIFICATE_VALIDITY" -> certificateValidity(context);
            case "PRICE_COMPARE" -> priceCompare(context);
            case "PERSONNEL_ROSTER" -> personnelRoster(context);
            case "SECTION_CHECKLIST" -> sectionChecklist(context);
            case "ENTITY_FIELD_MATCH" -> entityFieldMatch(context);
            case "PERSONNEL_CREDENTIAL" -> personnelCredential(context);
            case "CONSORTIUM_RESPONSIBILITY" -> consortiumResponsibility(context);
            case "PRICE_RECONCILE" -> priceReconcile(context);
            case "DURATION_COMPARE" -> durationCompare(context);
            case "CROSS_DOCUMENT_CONSISTENCY" -> crossDocumentConsistency(context);
            case "FORM_FIELD_COMPLETENESS" -> formFieldCompleteness(context);
            case "DATE_RELATION" -> dateRelation(context);
            case "TEXT_ANOMALY" -> textAnomaly(context);
            case "OBJECTIVE_SCORING" -> objectiveScoring(context);
            case "QUALITY_ADVISORY" -> qualityAdvisory(context);
            case "EXTERNAL_CHECK" -> externalCheck(context);
            default -> context.note("UNSUPPORTED_TEMPLATE", "证据装配层未注册该固定模板");
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("version", VERSION);
        diagnostics.put("templateId", spec.templateId());
        diagnostics.put("templateVersion", spec.templateVersion());
        diagnostics.put("documentId", bid.id());
        diagnostics.put("documentVersion", bid.version());
        diagnostics.put("localOnly", true);
        diagnostics.put("modelCalls", 0);
        diagnostics.put("compilerFallbacks", 0);
        diagnostics.put("documentCoverageComplete", context.documentComplete);
        diagnostics.put("observationCount", context.observations.size());
        diagnostics.put("closedNeedKeys", List.copyOf(context.closed));
        diagnostics.put("counters", Map.copyOf(context.counters));
        diagnostics.put("notes", List.copyOf(context.notes));

        EvidenceView view = new EvidenceView(
                "template-evidence:" + digest(spec.id() + ":" + spec.revision() + ":" + bid.id() + ":"
                        + bid.version() + ":" + Objects.toString(bid.sha256(), "")),
                bid.version(), List.copyOf(context.observations), Set.copyOf(context.closed),
                Set.copyOf(context.failed), Map.copyOf(context.processingFailures));
        return new Assembly(view, List.copyOf(context.domainEvidence.values()), Map.copyOf(diagnostics));
    }

    private Assembly assembleSingleFlight(CheckSpec spec, Checkpoint checkpoint, Document bid, Document tender,
                                          List<Fact> inputFacts) {
        CheckTemplate template = templateRegistry.find(spec.templateId(), spec.templateVersion()).orElse(null);
        if (template == null) return assembleLocal(spec, checkpoint, bid, tender, inputFacts);
        List<EvidenceNeed> needs = template.evidenceNeeds(spec);
        if (needs.isEmpty()) return assembleLocal(spec, checkpoint, bid, tender, inputFacts);

        AssemblyMemo memo = new AssemblyMemo(() -> assembleLocal(spec, checkpoint, bid, tender, inputFacts));
        SnapshotLookup snapshotLookup = snapshotFor(bid, inputFacts);
        InputSnapshot inputSnapshot = snapshotLookup.snapshot();
        SnapshotLookup tenderSnapshotLookup = tender == null ? null : snapshotFor(tender, List.of());
        List<NeedFlight> flights = new ArrayList<>();
        List<Map<String, Object>> queryAudit = new ArrayList<>();
        for (EvidenceNeed need : needs) {
            EvidenceQuery query = evidenceQuery(spec, need, bid, tender);
            EvidenceNeedKey stableKey = stableNeedKey(spec, need, query, bid, tender, inputSnapshot,
                    tenderSnapshotLookup == null ? null : tenderSnapshotLookup.snapshot());
            queryAudit.add(query.audit(need.key()));
            LocalEvidenceExtractor extractor = new LocalEvidenceExtractor() {
                @Override public String id() { return SINGLE_FLIGHT_EXTRACTOR; }
                @Override public String version() { return VERSION; }
                @Override public EvidenceExtractionResult extract(EvidenceExtractionContext ignored) {
                    Assembly local = memo.get();
                    List<Observation> observations = observationsForNeed(spec, need, local.evidenceView().observations());
                    EvidenceScope scope = evidenceScope(bid, tender, need, query,
                            local.evidenceView().closedNeedKeys().contains(need.key()));
                    if (!observations.isEmpty()) return EvidenceExtractionResult.positive(observations, scope);
                    if (scope.closed()) return EvidenceExtractionResult.notFound(scope, "闭合范围内未定位到该证据");
                    return EvidenceExtractionResult.partial(List.of(), scope, "证据范围或对象集合尚未闭合");
                }
            };
            CompletableFuture<NeedReceipt> future;
            try {
                future = evidenceCoordinator.request(stableKey, spec.id() + ":" + need.key(), extractor)
                        .handle((receipt, failure) -> new NeedReceipt(need, receipt, failure));
            } catch (RuntimeException failure) {
                future = CompletableFuture.completedFuture(new NeedReceipt(need, null, failure));
            }
            flights.add(new NeedFlight(need, stableKey, future));
        }

        List<NeedReceipt> receipts = flights.stream().map(flight -> flight.future.join()).toList();
        LinkedHashMap<String, Observation> observations = new LinkedHashMap<>();
        LinkedHashSet<String> closed = new LinkedHashSet<>(), failed = new LinkedHashSet<>();
        LinkedHashMap<String, String> failures = new LinkedHashMap<>();
        List<Map<String, Object>> receiptAudit = new ArrayList<>();
        for (NeedReceipt value : receipts) {
            EvidenceReadReceipt receipt = value.receipt;
            if (value.failure != null || receipt == null) {
                failed.add(value.need.key());
                failures.put(value.need.key(), safeFailure(value.failure));
                receiptAudit.add(Map.of("needKey", value.need.key(), "status", "COORDINATOR_FAILED",
                        "message", safeFailure(value.failure)));
                continue;
            }
            EvidenceExtractionResult result = receipt.result();
            if (result != null) result.observations().forEach(observation -> observations.putIfAbsent(observation.id(), observation));
            if (result != null && result.scope().closed()
                    && (receipt.status() == EvidenceTaskStatus.SUCCEEDED
                    || receipt.status() == EvidenceTaskStatus.NOT_FOUND_IN_CLOSED_SCOPE)) {
                closed.add(value.need.key());
            }
            if (receipt.status() == EvidenceTaskStatus.FAILED) {
                failed.add(value.need.key());
                String message = receipt.failure() == null ? "本地证据任务失败" : receipt.failure().message();
                failures.put(value.need.key(), message);
            }
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("needKey", value.need.key());
            audit.put("taskId", receipt.taskId());
            audit.put("receiptId", receipt.receiptId());
            audit.put("taskRevision", receipt.taskRevision());
            audit.put("status", receipt.status().name());
            audit.put("attempts", receipt.attempts());
            audit.put("sharedTask", receipt.sharedTask());
            if (result != null) {
                audit.put("scopeClosed", result.scope().closed());
                audit.put("observationCount", result.observations().size());
            }
            receiptAudit.add(Map.copyOf(audit));
        }

        // Always project domain evidence from the persisted observations.  On the first request the
        // extractor also has a local Assembly in memory, while a later subscriber reads only the
        // immutable single-flight receipt.  Merging the local-only evidence here made otherwise
        // identical evaluations differ merely because one caller won the extraction race.
        List<Evidence> evidences = domainEvidence(observations.values(), bid, tender);
        Assembly local = memo.peek();
        String receiptRevision = receipts.stream().filter(value -> value.receipt != null)
                .map(value -> value.receipt.taskId() + ":" + value.receipt.taskRevision())
                .sorted().collect(java.util.stream.Collectors.joining("|"));
        EvidenceView view = new EvidenceView("template-evidence-flight:" + digest(receiptRevision), bid.version(),
                List.copyOf(observations.values()), Set.copyOf(closed), Set.copyOf(failed), Map.copyOf(failures));
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (local != null) diagnostics.putAll(local.diagnostics());
        diagnostics.put("version", VERSION);
        diagnostics.put("singleFlight", true);
        diagnostics.put("localOnly", true);
        diagnostics.put("modelCalls", 0);
        diagnostics.put("localScanExecuted", local != null);
        diagnostics.put("inputSnapshotCacheHit", snapshotLookup.cacheHit());
        if (tenderSnapshotLookup != null) diagnostics.put("tenderSnapshotCacheHit", tenderSnapshotLookup.cacheHit());
        diagnostics.put("needCount", needs.size());
        diagnostics.put("evidenceQueries", List.copyOf(queryAudit));
        diagnostics.put("sharedReceiptCount", receipts.stream().filter(value -> value.receipt != null && value.receipt.sharedTask()).count());
        diagnostics.put("receipts", List.copyOf(receiptAudit));
        diagnostics.put("closedNeedKeys", List.copyOf(closed));
        diagnostics.put("failedNeedKeys", List.copyOf(failed));
        return new Assembly(view, evidences, Map.copyOf(diagnostics));
    }

    private static EvidenceNeedKey stableNeedKey(CheckSpec spec, EvidenceNeed need, EvidenceQuery query,
                                                 Document bid, Document tender,
                                                 InputSnapshot inputSnapshot, InputSnapshot tenderSnapshot) {
        List<EvidenceNeedKey.DocumentVersion> documents = new ArrayList<>();
        if (query.documentRoles().contains("BID")) documents.add(new EvidenceNeedKey.DocumentVersion(
                bid.id(), bid.version(), inputSnapshot.contentHash(), inputSnapshot.parserFingerprint()));
        if (query.documentRoles().contains("TENDER") && tender != null && tenderSnapshot != null)
            documents.add(new EvidenceNeedKey.DocumentVersion(
                tender.id(), tender.version(), tenderSnapshot.contentHash(), tenderSnapshot.parserFingerprint()));
        // External records travel in the fact snapshot associated with the bid.  Keep that snapshot
        // in the key even though the query has no local page role.
        if (documents.isEmpty()) documents.add(new EvidenceNeedKey.DocumentVersion(
                bid.id(), bid.version(), inputSnapshot.contentHash(), inputSnapshot.parserFingerprint()));
        SubjectSelector selector = need.subjectSelector();
        String subject = selector.entityType() + "|" + selector.role() + "|" + selector.scope() + "|"
                + selector.constraints().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining(";"));
        LinkedHashMap<String, String> dimensions = new LinkedHashMap<>();
        need.constraints().forEach((key, value) -> { if (value != null && !value.isBlank()) dimensions.put(key, value); });
        dimensions.put("scopeClosureRequired", Boolean.toString(need.scopeClosureRequired()));
        dimensions.put("factSnapshot", inputSnapshot.factSnapshot());
        putDimension(dimensions, "query.documentRoles", String.join("|", query.documentRoles()));
        putDimension(dimensions, "query.sections", String.join("|", query.sectionTerms()));
        putDimension(dimensions, "query.instance", query.instanceKey());
        putDimension(dimensions, "query.roles", String.join("|", query.fieldRoles()));
        // Only extraction-affecting parameters belong in this key. Evaluation-only values such as
        // a comparison threshold, or sealLocation on the independent material need, must not split
        // an otherwise identical task and defeat cross-spec single-flight reuse.
        extractionParameters(need.kind()).stream().sorted().forEach(key -> {
            Object value = spec.parameters().get(key);
            if (value != null && !String.valueOf(value).isBlank())
                dimensions.put("extract." + key, canonicalValue(value));
        });
        String field = need.requiredFields().stream().sorted().collect(java.util.stream.Collectors.joining(","));
        if (field.isBlank()) field = need.kind().name();
        return new EvidenceNeedKey(List.copyOf(documents), subject, need.kind().name(), field,
                Map.copyOf(dimensions), SINGLE_FLIGHT_EXTRACTOR, VERSION);
    }

    private static void putDimension(Map<String, String> dimensions, String key, String value) {
        if (value != null && !value.isBlank()) dimensions.put(key, value.strip());
    }

    private static Set<String> extractionParameters(TemplateTypes.NeedKind kind) {
        return switch (kind) {
            case MATERIAL -> Set.of("requireBody", "requiredCommitmentPoints", "requiredMemberRoles",
                    "requiredResponsibilities", "leadRole", "memberPrefix", "requiredItems",
                    "formType", "requiredFields", "expectedSubject", "textScope", "anomalyTypes",
                    "typoDictionary", "dimensions", "source", "queryObject", "asOfDate", "adapterId",
                    "evaluationStage");
            case SEAL -> Set.of("materialType", "sealLocation");
            case CERTIFICATE -> Set.of("certificateType", "leftRole", "rightRole", "relationKey");
            case PRICE -> Set.of("field", "unit", "startEvent", "calendarBasis", "lot", "priceLayer",
                    "taxBasis", "criterion", "aggregation", "maxScore", "scoreUnit", "deduplicateBySubject");
            case PERSONNEL, ROSTER_SCOPE -> Set.of("role", "consortiumRole", "employerKey");
        };
    }

    /**
     * Compiles evaluator dependency keys into an auditable local query.  The query is also part of
     * the single-flight identity, so evidence read for one document role/section/instance cannot be
     * replayed into a superficially similar need with a different boundary.
     */
    private static EvidenceQuery evidenceQuery(CheckSpec spec, EvidenceNeed need,
                                               Document bid, Document tender) {
        LinkedHashSet<String> documentRoles = new LinkedHashSet<>();
        LinkedHashSet<String> sectionTerms = new LinkedHashSet<>();
        LinkedHashSet<String> fieldRoles = new LinkedHashSet<>(need.requiredFields());
        String instance = need.kind().name();
        switch (spec.templateId()) {
            case "DURATION_COMPARE" -> {
                documentRoles.add("BID");
                sectionTerms.addAll(fieldLabels(need.constraints().getOrDefault("field", "duration")));
                sectionTerms.add(need.constraints().getOrDefault("startEvent", ""));
                fieldRoles.addAll(List.of("amount", "unit", "startEvent", "calendarBasis"));
                instance = "lot:" + need.constraints().getOrDefault("lot", "CURRENT_PROJECT");
            }
            case "CROSS_DOCUMENT_CONSISTENCY" -> {
                String field = need.constraints().getOrDefault("field", "");
                documentRoles.add("BID");
                if (!bidInternalConsistencyField(field) && tender != null
                        && "TENDER".equalsIgnoreCase(tender.role())) documentRoles.add("TENDER");
                sectionTerms.addAll(fieldLabels(field));
                fieldRoles.addAll(List.of("value", "documentCoverage"));
                instance = bidInternalConsistencyField(field) ? "logical-occurrence" : "document-role";
            }
            case "FORM_FIELD_COMPLETENESS" -> {
                documentRoles.add("BID");
                sectionTerms.add(need.constraints().getOrDefault("formType", ""));
                fieldRoles.addAll(splitConstraint(need.constraints().getOrDefault("requiredFields", "")));
                instance = "same-page-form-instance";
            }
            case "DATE_RELATION" -> {
                String role = need.constraints().getOrDefault("dateRole", "");
                documentRoles.add(tenderDateRole(role) ? "TENDER" : "BID");
                sectionTerms.addAll(dateRoleLabels(role));
                fieldRoles.add(role);
                instance = need.constraints().getOrDefault("relationKey", "CURRENT_PROJECT") + ":" + role;
            }
            case "TEXT_ANOMALY" -> {
                documentRoles.add("BID");
                String scope = need.constraints().getOrDefault("textScope", "");
                if (!wholeDocumentScope(scope)) sectionTerms.add(scope);
                fieldRoles.addAll(splitConstraint(need.constraints().getOrDefault("anomalyTypes", "")));
                instance = wholeDocumentScope(scope) ? "whole-document" : "section:" + scope;
            }
            case "OBJECTIVE_SCORING" -> {
                String criterion = need.constraints().getOrDefault("criterion", "");
                ObjectiveRule rule = objectiveRule(spec, criterion);
                documentRoles.add("BID");
                if (rule != null) documentRoles.add("TENDER");
                sectionTerms.add(rule == null ? criterion : rule.materialType());
                if (rule == null) {
                    fieldRoles.addAll(List.of("scoreItem", "amount"));
                    instance = "criterion:" + criterion;
                } else {
                    fieldRoles.addAll(List.of("certificateNumber", "holder", "validFrom", "validUntil",
                            "consortiumRole", "scoreItem", "amount"));
                    instance = String.join(":", "binary-material", rule.partyRole(), rule.materialType(),
                            rule.score().stripTrailingZeros().toPlainString());
                }
            }
            case "QUALITY_ADVISORY" -> {
                documentRoles.add("BID");
                sectionTerms.add(need.constraints().getOrDefault("materialType", ""));
                fieldRoles.addAll(qualityDimensions(spec));
                instance = "material:" + need.constraints().getOrDefault("materialType", "");
            }
            case "EXTERNAL_CHECK" -> {
                documentRoles.add("EXTERNAL");
                sectionTerms.add(need.constraints().getOrDefault("source", ""));
                fieldRoles.addAll(need.requiredFields());
                instance = String.join(":", need.constraints().getOrDefault("source", ""),
                        need.constraints().getOrDefault("queryObject", ""),
                        need.constraints().getOrDefault("asOfDate", ""),
                        need.constraints().getOrDefault("adapterId", ""));
            }
            default -> documentRoles.add("BID");
        }
        sectionTerms.removeIf(String::isBlank);
        fieldRoles.removeIf(String::isBlank);
        return new EvidenceQuery(List.copyOf(documentRoles), List.copyOf(sectionTerms), instance,
                List.copyOf(fieldRoles));
    }

    private static List<String> splitConstraint(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split("\\|"))
                .map(String::strip).filter(part -> !part.isBlank()).distinct().toList();
    }

    private static boolean bidInternalConsistencyField(String field) {
        return "companyName".equals(field) || "creditCode".equals(field);
    }

    private synchronized SnapshotLookup snapshotFor(Document bid, List<Fact> inputFacts) {
        SnapshotCacheKey key = new SnapshotCacheKey(bid, inputFacts);
        InputSnapshot cached = inputSnapshotCache.get(key);
        if (cached != null) return new SnapshotLookup(cached, true);
        InputSnapshot created = inputSnapshot(bid, inputFacts);
        inputSnapshotCache.put(key, created);
        while (inputSnapshotCache.size() > INPUT_SNAPSHOT_CACHE_LIMIT) {
            java.util.Iterator<SnapshotCacheKey> iterator = inputSnapshotCache.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        return new SnapshotLookup(created, false);
    }

    private static InputSnapshot inputSnapshot(Document bid, List<Fact> inputFacts) {
        // The stored file hash/version alone is not an extraction snapshot. Targeted OCR recovery
        // and fact backfill can change the pages/facts while those outer fields remain unchanged.
        // Include every local input consumed by this assembler so a terminal PARTIAL/NOT_FOUND
        // task can never hide evidence that arrived in a later recovery pass. Hash the potentially
        // large page stream once per assembly, rather than concatenating/re-hashing it per need.
        MessageDigest content = sha256();
        digestPart(content, bid.sha256());
        MessageDigest parser = sha256();
        for (Page page : sortedPages(bid)) {
            digestPart(content, page.number());
            digestPart(content, page.locationType());
            digestPart(content, page.parser());
            digestPart(content, page.warning());
            digestPart(content, page.text());
            digestPart(content, page.layout());
            digestPart(parser, page.number());
            digestPart(parser, page.locationType());
            digestPart(parser, page.parser());
            digestPart(parser, page.warning());
        }
        if (bid.warnings() != null) bid.warnings().stream().filter(Objects::nonNull)
                .map(String::strip).sorted().forEach(value -> digestPart(content, value));
        return new InputSnapshot(HexFormat.of().formatHex(content.digest()),
                HexFormat.of().formatHex(parser.digest()), factSnapshot(bid, inputFacts));
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static void digestPart(MessageDigest digest, Object value) {
        byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) 0);
    }

    private static String factSnapshot(Document bid, List<Fact> inputFacts) {
        if (inputFacts == null || inputFacts.isEmpty()) return digest("");
        String canonical = inputFacts.stream().filter(Objects::nonNull)
                .filter(fact -> fact.evidence() != null && (bid.id().equals(fact.evidence().documentId())
                        || trustedConnectorFact(fact)))
                .map(fact -> {
                    Evidence evidence = fact.evidence();
                    return Objects.toString(fact.entityType(), "") + "\u001f"
                            + Objects.toString(fact.entityKey(), "") + "\u001f"
                            + Objects.toString(fact.field(), "") + "\u001f"
                            + Objects.toString(fact.rawValue(), "") + "\u001f"
                            + Objects.toString(fact.normalizedValue(), "") + "\u001f"
                            + Objects.toString(evidence.documentId(), "") + "\u001f"
                            + Objects.toString(evidence.documentName(), "") + "\u001f"
                            + evidence.page() + "\u001f" + Objects.toString(evidence.locationType(), "") + "\u001f"
                            + Objects.toString(evidence.quote(), "") + "\u001f"
                            + Objects.toString(evidence.anchor(), "") + "\u001f"
                            + Double.toString(evidence.confidence());
                }).sorted().collect(java.util.stream.Collectors.joining("\u001e"));
        return digest(canonical);
    }

    private static String canonicalValue(Object value) {
        if (value instanceof Collection<?> collection) return collection.stream().map(String::valueOf)
                .map(String::strip).sorted().collect(java.util.stream.Collectors.joining("|"));
        return String.valueOf(value).strip();
    }

    private static List<Observation> observationsForNeed(CheckSpec spec, EvidenceNeed need,
                                                         List<Observation> values) {
        return values.stream().filter(value -> switch (need.kind()) {
            case MATERIAL -> value instanceof Observation.MaterialObservation material
                    && materialMatchesNeed(spec, need, material);
            case SEAL -> value instanceof Observation.SealObservation;
            case CERTIFICATE -> value instanceof Observation.CertificateObservation certificate
                    && certificateMatchesNeed(spec, need, certificate);
            case PRICE -> value instanceof Observation.PriceObservation price
                    && priceMatchesNeed(spec, need, price);
            case PERSONNEL, ROSTER_SCOPE -> value instanceof Observation.PersonnelObservation person
                    && (need.constraints().getOrDefault("role", "").isBlank()
                    || person.roles().stream().anyMatch(role -> sameRole(need.constraints().get("role"), role)));
        }).toList();
    }

    private static boolean materialMatchesNeed(CheckSpec spec, EvidenceNeed need,
                                               Observation.MaterialObservation observation) {
        String expected = switch (spec.templateId()) {
            case "CROSS_DOCUMENT_CONSISTENCY" -> "FIELD:" + need.constraints().getOrDefault("field", "");
            case "FORM_FIELD_COMPLETENESS" -> need.constraints().getOrDefault("formType", "");
            case "TEXT_ANOMALY" -> "TEXT:" + need.constraints().getOrDefault("textScope", "");
            case "EXTERNAL_CHECK" -> "EXTERNAL:" + need.constraints().getOrDefault("source", "");
            default -> need.constraints().getOrDefault("materialType", "");
        };
        return !expected.isBlank() && materialMatches(observation, expected);
    }

    private static boolean certificateMatchesNeed(CheckSpec spec, EvidenceNeed need,
                                                  Observation.CertificateObservation observation) {
        String expected = "DATE_RELATION".equals(spec.templateId())
                ? "DATE:" + need.constraints().getOrDefault("dateRole", "")
                : need.constraints().getOrDefault("certificateType", "");
        if (!expected.isBlank() && !certificateTypeMatches(expected, observation.certificateType())) return false;
        String relationKey = need.constraints().getOrDefault("relationKey", "");
        return relationKey.isBlank() || same(relationKey, observation.holderKey());
    }

    private static boolean priceMatchesNeed(CheckSpec spec, EvidenceNeed need,
                                            Observation.PriceObservation observation) {
        if ("OBJECTIVE_SCORING".equals(spec.templateId())) {
            return "SCORE".equalsIgnoreCase(observation.currency())
                    && same(need.constraints().getOrDefault("criterion", ""), observation.field());
        }
        if ("DURATION_COMPARE".equals(spec.templateId())) {
            return "DURATION".equalsIgnoreCase(observation.currency())
                    && same(need.constraints().getOrDefault("field", ""), observation.field());
        }
        String field = need.constraints().getOrDefault("field", "");
        return field.isBlank() || same(field, observation.field());
    }

    private static boolean materialMatches(Observation.MaterialObservation observation, String expected) {
        return same(observation.materialType(), expected)
                || observation.aliases().stream().anyMatch(alias -> same(alias, expected));
    }

    private static EvidenceScope evidenceScope(Document bid, Document tender, EvidenceNeed need,
                                               EvidenceQuery query, boolean locallyClosed) {
        List<Document> documents = new ArrayList<>();
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (String role : query.documentRoles()) {
            if ("EXTERNAL".equals(role)) continue;
            Document document = "BID".equals(role) ? bid : "TENDER".equals(role) ? tender : null;
            if (document == null || !role.equalsIgnoreCase(document.role())) missing.add("document-role:" + role);
            else documents.add(document);
        }
        if (query.documentRoles().contains("EXTERNAL")) missing.add("external-source:" + query.instanceKey());
        int expected = documents.stream().mapToInt(document -> document.pages() == null ? 0 : document.pages().size()).sum()
                + missing.size();
        if (locallyClosed && missing.isEmpty() && documents.stream().allMatch(TemplateEvidenceAssembler::complete))
            return EvidenceScope.closed(expected, "已闭合该证据请求的文档角色、章节与对象范围");
        int scanned = 0;
        for (Document document : documents) for (Page page : sortedPages(document)) {
            if (pageIncomplete(page)) missing.add(document.role() + ":page:" + page.number());
            else scanned++;
        }
        if (expected == scanned && missing.isEmpty()) {
            expected++;
            missing.add("scope-boundary:" + need.kind().name().toLowerCase(Locale.ROOT));
        }
        return EvidenceScope.partial(scanned, expected, Set.copyOf(missing), "文档页面或对象集合尚未闭合");
    }

    private static List<Evidence> domainEvidence(Collection<Observation> observations, Document bid, Document tender) {
        LinkedHashMap<String, Evidence> result = new LinkedHashMap<>();
        for (Observation observation : observations) for (SourceRef source : observation.sources()) {
            String name = bid.id().equals(source.documentId()) ? bid.name()
                    : tender != null && tender.id().equals(source.documentId()) ? tender.name() : source.documentId();
            Evidence evidence = new Evidence(source.documentId(), name, source.location(), source.locationType(),
                    source.quote(), "singleflight:" + source.sourceHash(), observation.confidence());
            result.putIfAbsent(evidenceKey(evidence), evidence);
        }
        return List.copyOf(result.values());
    }

    private static String safeFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null) current = current.getCause();
        String value = current == null ? "本地证据协调器未返回结果" : Objects.toString(current.getMessage(), current.getClass().getSimpleName());
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private void sectionChecklist(Context context) {
        boolean requireBody = bool(context.spec.parameters(), "requireBody", true);
        for (String item : strings(context.spec.parameters(), "requiredItems")) {
            MaterialScan scan = scanMaterial(context, item, Set.of(), requireBody);
            scan.observations.forEach(context::add);
            closeMaterialNeed(context, "item-" + safe(item), scan);
        }
    }

    private void entityFieldMatch(Context context) {
        personnelRoster(context);
        boolean rosterClosed = context.closed.contains(context.spec.id() + ":roster-scope");
        if (rosterClosed) {
            context.closed.add(context.spec.id() + ":entity-fields");
            context.closed.add(context.spec.id() + ":entity-scope");
        }
        String left = string(context.spec.parameters(), "leftField");
        String right = string(context.spec.parameters(), "rightField");
        if (left.startsWith("certificate.") || right.startsWith("certificate.")) {
            certificateValidity(context);
            if (rosterClosed && context.documentComplete)
                context.closed.add(context.spec.id() + ":certificate-fields");
        }
    }

    private void personnelCredential(Context context) {
        personnelRoster(context);
        boolean rosterClosed = context.closed.contains(context.spec.id() + ":roster-scope");
        if (rosterClosed) {
            context.closed.add(context.spec.id() + ":credential-person");
            context.closed.add(context.spec.id() + ":credential-scope");
        }
        certificateValidity(context);
        if (rosterClosed && context.documentComplete)
            context.closed.add(context.spec.id() + ":credential-certificate");
    }

    private void consortiumResponsibility(Context context) {
        String materialType = string(context.spec.parameters(), "materialType");
        if (materialType.isBlank()) materialType = "联合体协议书";
        LinkedHashSet<String> points = new LinkedHashSet<>();
        points.addAll(strings(context.spec.parameters(), "requiredMemberRoles"));
        points.addAll(strings(context.spec.parameters(), "requiredResponsibilities"));
        String lead = string(context.spec.parameters(), "leadRole");
        if (!lead.isBlank()) points.add(lead);
        String prefix = string(context.spec.parameters(), "memberPrefix");
        if (prefix.isBlank()) prefix = "成员:";
        MaterialScan scan = scanMaterial(context, materialType, points, true);
        Observation.MaterialObservation material = aggregateCommitment(context, materialType, scan);
        if (material != null) {
            LinkedHashSet<String> explicit = new LinkedHashSet<>(material.commitmentPoints());
            for (Observation.MaterialObservation observation : scan.observations) {
                for (SourceRef source : observation.sources()) {
                    for (String member : labelledValues(source.quote(), prefix)) explicit.add(prefix + member);
                }
            }
            context.add(new Observation.MaterialObservation(material.id(), material.materialType(), material.aliases(),
                    material.subjectKey(), material.currentScope(), material.bodyPresent(), material.titleOnly(),
                    Set.copyOf(explicit), material.state(), material.sources(), material.confidence()));
        } else scan.observations.forEach(context::add);
        closeMaterialNeed(context, "consortium-agreement", scan);
    }

    private void priceReconcile(Context context) {
        List<String> fields = new ArrayList<>();
        fields.add(string(context.spec.parameters(), "totalField"));
        fields.addAll(strings(context.spec.parameters(), "componentFields"));
        for (String field : fields) {
            PriceScan scan = scanPrice(context, field);
            if (context.documentComplete && !scan.unresolved)
                context.closed.add(context.spec.id() + ":price-" + safe(field));
            else if (scan.unresolved)
                context.note("PRICE_SCOPE_NOT_CLOSED", "报价字段“" + field + "”存在无法绑定或口径不完整的候选值");
        }
    }

    private void durationCompare(Context context) {
        Map<String, Object> parameters = context.spec.parameters();
        String field = string(parameters, "field");
        String requestedUnit = string(parameters, "unit");
        String requestedStart = string(parameters, "startEvent");
        String requestedCalendar = string(parameters, "calendarBasis");
        String lot = string(parameters, "lot");
        boolean unresolved = false;
        int matched = 0;
        for (Fact fact : factsFor(context, context.bid)) {
            if (!field.equals(fact.field())) continue;
            Page page = page(context.bid, fact.evidence());
            if (page == null) { unresolved = true; context.increment("unboundDurationSources"); continue; }
            String sourceContext = evidenceContext(page.text(), fact.evidence().quote(), field);
            String observedLot = lots(String.join("\n", Objects.toString(fact.rawValue(), ""),
                    Objects.toString(fact.evidence().quote(), ""), sourceContext));
            boolean current = currentEvidence(context, fact.evidence(), page, false);
            boolean explicitWrongLot = !lot.isBlank() && !observedLot.isBlank() && !same(lot, observedLot);
            // A historical project or another explicit lot is outside the requested collection.
            // Count it as rejected evidence, but never let it keep the current-project range open.
            if (!current) {
                context.increment("rejectedDurationScopeValues");
                continue;
            }
            if (explicitWrongLot) context.increment("rejectedDurationScopeValues");
            else matched++;
            DurationValue duration = durationValue(fact);
            if (duration == null) {
                if (!explicitWrongLot) unresolved = true;
                context.increment("unparsedDurations");
                continue;
            }
            // A numeric value alone is not a duration observation: PriceObservation requires a
            // real unit, and copying the unit requested by the rule would turn an unknown basis
            // into apparently supporting evidence.
            if (duration.unit.isBlank() || duration.calendarBasis.isBlank()) {
                if (!explicitWrongLot) unresolved = true;
                context.increment("unboundDurationBasis");
                continue;
            }
            String observedStart = durationStartEvent(String.join("\n",
                    Objects.toString(fact.rawValue(), ""), Objects.toString(fact.evidence().quote(), ""),
                    sourceContext));
            boolean compatible = knownRequired(requestedUnit, duration.unit,
                            TemplateEvidenceAssembler::durationUnitEquivalent)
                    && knownRequired(requestedCalendar, duration.calendarBasis,
                            TemplateEvidenceAssembler::same)
                    && knownRequired(requestedStart, observedStart, TemplateEvidenceAssembler::same)
                    && (lot.isBlank() || !observedLot.isBlank() && same(lot, observedLot));
            TemplateTypes.ObservationState state = compatible && current
                    ? TemplateTypes.ObservationState.SUPPORTED : TemplateTypes.ObservationState.UNRESOLVED;
            if (!compatible && !explicitWrongLot) unresolved = true;
            String subject = subjectKey(context.spec.subjectSelector());
            List<SourceRef> sourceRefs = refs(context, List.of(fact.evidence()));
            context.add(new Observation.PriceObservation(
                    stableObservationId("duration", subject,
                            String.join("|", field, observedLot, observedStart, duration.calendarBasis, duration.unit,
                                    duration.amount.toPlainString()), sourceRefs),
                    field, subject, observedLot, observedStart, duration.calendarBasis, "DURATION", duration.unit,
                    duration.amount, current, state, sourceRefs,
                    observationConfidence(page, List.of(fact.evidence()))));
        }
        if (matched == 0 && !unresolved && durationAcceptance(context, field, requestedUnit,
                requestedStart, requestedCalendar, lot)) matched = 1;
        if (context.documentComplete && matched > 0 && !unresolved)
            context.closed.add(context.spec.id() + ":duration");
        if (matched == 0) context.note("DURATION_VALUE_NOT_LOCATED", "尚未定位到字段“" + field + "”的明确期限值");
        else if (unresolved) context.note("DURATION_BASIS_NOT_ALIGNED",
                "已定位期限数值，但起算事件、标段、单位或日历口径未能从来源原文中完整绑定");
    }

    /**
     * “满足/响应招标要求” is a binding response only when the frozen requirement already carries
     * the numeric value and every requested basis.  The amount comes from the requirement binding,
     * while the bid page proves acceptance; historical numeric contracts are never consulted.
     */
    private boolean durationAcceptance(Context context, String field, String unit, String startEvent,
                                       String calendarBasis, String lot) {
        if (!context.spec.confirmed() || field.isBlank() || unit.isBlank()
                || calendarBasis.isBlank()) return false;
        BigDecimal expected;
        try { expected = new BigDecimal(string(context.spec.parameters(), "expected")); }
        catch (NumberFormatException invalid) { return false; }
        List<SourceRef> requirementSources = context.spec.parameterBindings().stream()
                .filter(binding -> Set.of("expected", "unit", "startEvent", "calendarBasis", "lot")
                        .contains(binding.parameter()))
                .flatMap(binding -> binding.sources().stream()).distinct().toList();
        if (requirementSources.isEmpty()) requirementSources = context.spec.requirementRefs();
        if (requirementSources.isEmpty()) return false;
        Pattern acceptance = Pattern.compile(
                "(?:承诺)?(?:总)?工期[^\\r\\n。；;]{0,30}(?:满足|响应(?:招标文件)?要求|按(?:招标文件)?要求执行)");
        for (Page page : sortedPages(context.bid)) {
            String text = Objects.toString(page.text(), "");
            if (indexPage(text) || example(text) || HISTORY.matcher(text).find()) continue;
            Matcher matcher = acceptance.matcher(text);
            if (!matcher.find()) continue;
            Evidence acceptanceEvidence = new Evidence(context.bid.id(), context.bid.name(), page.number(),
                    page.locationType(), matcher.group().strip(),
                    "template-duration-acceptance:" + matcher.start(), parserConfidence(page));
            if (!currentEvidence(context, acceptanceEvidence, page, false)) continue;
            List<SourceRef> sources = new ArrayList<>(refs(context, List.of(acceptanceEvidence)));
            sources.addAll(requirementSources);
            sources = sources.stream().collect(java.util.stream.Collectors.toMap(
                    TemplateEvidenceAssembler::sourceKey, source -> source, (left, right) -> left,
                    LinkedHashMap::new)).values().stream().toList();
            String subject = subjectKey(context.spec.subjectSelector());
            context.add(new Observation.PriceObservation(
                    stableObservationId("duration-accepted-requirement", subject,
                            String.join("|", field, lot, startEvent, calendarBasis, unit,
                                    expected.toPlainString()), sources),
                    field, subject, lot, startEvent, calendarBasis, "DURATION", unit, expected,
                    true, TemplateTypes.ObservationState.SUPPORTED, sources, parserConfidence(page)));
            context.increment("durationRequirementAcceptances");
            return true;
        }
        return false;
    }

    private void crossDocumentConsistency(Context context) {
        String field = string(context.spec.parameters(), "field");
        int minimum = Math.max(2, integer(context.spec.parameters(), "minimumOccurrences", 2));
        List<Document> documents = new ArrayList<>();
        documents.add(context.bid);
        if (!bidInternalConsistencyField(field) && context.tender != null
                && "TENDER".equalsIgnoreCase(context.tender.role())
                && !context.bid.id().equals(context.tender.id())) documents.add(context.tender);
        String primaryCompany = "companyName".equals(field) ? primaryCompanyValue(context) : "";
        Map<String, Set<String>> logicalScopesByParty = new LinkedHashMap<>();
        for (Document document : documents) {
            for (FieldValue value : fieldValues(context, document, field)) {
                Page sourcePage = page(document, value.evidence);
                if (!crossDocumentCurrent(context, document, sourcePage, value.evidence)) {
                    context.increment("rejectedCrossDocumentScopeValues");
                    continue;
                }
                String party = consistencyParty(field, document, sourcePage, value.value, primaryCompany);
                if (bidInternalConsistencyField(field) && party.isBlank()) {
                    context.increment("rejectedUnboundConsistencyParty");
                    continue;
                }
                String logicalScope = consistencySourceScope(document, sourcePage, field);
                logicalScopesByParty.computeIfAbsent(party.isBlank() ? "PROJECT" : party,
                        ignored -> new LinkedHashSet<>()).add(logicalScope);
                List<SourceRef> sourceRefs = refs(context, List.of(value.evidence));
                String subject = subjectKey(context.spec.subjectSelector());
                LinkedHashSet<String> points = new LinkedHashSet<>();
                points.add("VALUE:" + value.value);
                points.add("SOURCE_SCOPE:" + logicalScope);
                points.add("PARTY_SCOPE:" + (party.isBlank() ? "PROJECT" : party));
                context.add(new Observation.MaterialObservation(
                        stableObservationId("cross-document-field", subject,
                                field + "|" + document.id() + "|" + value.value, sourceRefs),
                        "FIELD:" + field, Set.of(), subject, true, true, false,
                        Set.copyOf(points), TemplateTypes.ObservationState.SUPPORTED,
                        sourceRefs, observationConfidence(sourcePage, List.of(value.evidence))));
            }
        }
        boolean covered = !logicalScopesByParty.isEmpty()
                && logicalScopesByParty.values().stream().anyMatch(scopes -> scopes.size() >= minimum)
                && documents.stream().allMatch(TemplateEvidenceAssembler::complete)
                && fieldLocallySupported(field);
        if (covered) context.closed.add(context.spec.id() + ":field-values");
        else context.note("CROSS_DOCUMENT_SCOPE_OPEN", "跨文件字段“" + field + "”尚未形成可闭合的文件集合");
        addCounter(context, "crossDocumentValues", logicalScopesByParty.values().stream()
                .mapToInt(Set::size).sum());
    }

    private boolean crossDocumentCurrent(Context context, Document document, Page page, Evidence evidence) {
        if (document == null || page == null || evidence == null || indexPage(page.text())
                || example(page.text()) || historicalAt(page.text(), evidence.quote())) return false;
        if (context.bid.id().equals(document.id())) return currentEvidence(context, evidence, page, false);
        // The tender document role is an explicit ingestion boundary. It proves which requirement
        // document the page belongs to; arbitrary attachments are not promoted to current scope.
        return context.tender != null && context.tender.id().equals(document.id())
                && "TENDER".equalsIgnoreCase(document.role());
    }

    private void formFieldCompleteness(Context context) {
        Map<String, Object> parameters = context.spec.parameters();
        String formType = string(parameters, "formType");
        List<String> requiredFields = strings(parameters, "requiredFields");
        String expectedSubject = string(parameters, "expectedSubject");
        List<Page> candidates = sortedPages(context.bid).stream()
                .filter(page -> containsNormalized(page.text(), formType)).toList();
        for (Page page : candidates) {
            LinkedHashSet<String> points = new LinkedHashSet<>();
            for (String field : requiredFields) {
                LabelValue value = labelledField(page.text(), field);
                if (!value.present) continue;
                String marker = value.value.isBlank() ? "FIELD_BLANK:"
                        : PLACEHOLDER.matcher(value.value.strip()).matches() ? "FIELD_PLACEHOLDER:"
                        : "FIELD_FILLED:";
                points.add(marker + field);
                if (!expectedSubject.isBlank() && subjectField(field) && !value.value.isBlank()
                        && !containsNormalized(value.value, expectedSubject)) {
                    points.add("FIELD_WRONG_SUBJECT:" + field);
                }
            }
            if (points.isEmpty()) continue;
            Evidence evidence = pageEvidence(context.bid, page, formType);
            List<SourceRef> sourceRefs = refs(context, List.of(evidence));
            String subject = subjectKey(context.spec.subjectSelector());
            context.add(new Observation.MaterialObservation(
                    stableObservationId("form-fields", subject,
                            formType + "|" + points.stream().sorted().collect(java.util.stream.Collectors.joining("|")),
                            sourceRefs), formType, Set.of(), subject, true, true, false, Set.copyOf(points),
                    TemplateTypes.ObservationState.SUPPORTED, sourceRefs, parserConfidence(page)));
            boolean everyFieldExplicit = requiredFields.stream().allMatch(field -> points.contains("FIELD_FILLED:" + field)
                    || points.contains("FIELD_BLANK:" + field) || points.contains("FIELD_PLACEHOLDER:" + field)
                    || points.contains("FIELD_WRONG_SUBJECT:" + field));
            if (everyFieldExplicit) context.closed.add(context.spec.id() + ":form");
        }
        if (candidates.isEmpty()) context.note("FORM_NOT_LOCATED", "尚未定位到表单“" + formType + "”的正文页");
    }

    private void dateRelation(Context context) {
        String relationKey = string(context.spec.parameters(), "relationKey");
        if (relationKey.isBlank()) relationKey = subjectKey(context.spec.subjectSelector());
        for (String side : List.of("left", "right")) {
            String role = string(context.spec.parameters(), side + "Role");
            List<DatedSource> dates = datesForRole(context, role);
            for (DatedSource dated : dates) {
                List<SourceRef> sourceRefs = refs(context, dated.evidence());
                context.add(new Observation.CertificateObservation(
                        stableObservationId("date-role", relationKey, role + "|" + dated.date, sourceRefs),
                        "DATE:" + role, relationKey, role, "", dated.date, null, false, true,
                        TemplateTypes.ObservationState.SUPPORTED, sourceRefs,
                        observationConfidence(pageForEvidence(context, dated.evidence().get(0)), dated.evidence())));
            }
            Document scopedDocument = dateDocumentForRole(context, role);
            if (scopedDocument != null && complete(scopedDocument))
                context.closed.add(context.spec.id() + ":" + side + "-date");
            else context.note("DATE_ROLE_SCOPE_OPEN", "“" + role + "”所在文件仍未完成，日期范围不能闭合");
            if (dates.isEmpty()) context.note("DATE_ROLE_NOT_LOCATED", "尚未定位到“" + role + "”的明确日期");
        }
    }

    private void textAnomaly(Context context) {
        String scope = string(context.spec.parameters(), "textScope");
        Set<String> allowed = strings(context.spec.parameters(), "anomalyTypes").stream()
                .map(TemplateEvidenceAssembler::upper)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (allowed.isEmpty()) allowed.addAll(List.of("TYPO", "TRUNCATED", "OCR_SUSPECT"));
        List<TypoRule> typoRules = typoRules(context.spec.parameters().get("typoDictionary"));
        Set<String> availableDetectors = new LinkedHashSet<>();
        if (allowed.contains("TYPO") && !typoRules.isEmpty()) availableDetectors.add("TYPO");
        if (allowed.contains("TRUNCATED")) availableDetectors.add("TRUNCATED");
        if (allowed.contains("OCR_SUSPECT")) availableDetectors.add("OCR_SUSPECT");
        for (Fact fact : context.facts) {
            for (String anomalyType : allowed) {
                if (textScanScopeMarker(fact, anomalyType, scope)) availableDetectors.add(anomalyType);
            }
        }
        boolean wholeDocument = scope.isBlank() || List.of("全文", "全部", "整份").stream().anyMatch(scope::contains);
        boolean scopeSeen = wholeDocument;
        for (Page page : sortedPages(context.bid)) {
            String text = Objects.toString(page.text(), "");
            boolean selected = wholeDocument || containsNormalized(text, scope);
            if (!wholeDocument && selected && (indexPage(text) || example(text)
                    || HISTORY.matcher(text).find() && !CURRENT.matcher(beforeHistory(text)).find())) {
                context.increment("rejectedTextScopeNavigationPages");
                selected = false;
            }
            if (!selected) continue;
            scopeSeen = true;
            LinkedHashSet<String> points = new LinkedHashSet<>();
            String warning = Objects.toString(page.warning(), "");
            if (allowed.contains("OCR_SUSPECT") && (OBVIOUS_TEXT_ANOMALY.matcher(text).find()
                    || OBVIOUS_TEXT_ANOMALY.matcher(warning).find()))
                points.add("ANOMALY:OCR_SUSPECT:" + anomalySnippet(text.isBlank() ? warning : text));
            if (allowed.contains("TRUNCATED") && (warning.contains("截断") || text.contains("[内容截断]")))
                points.add("ANOMALY:TRUNCATED:" + anomalySnippet(warning + " " + text));
            if (allowed.contains("TYPO")) for (TypoRule rule : typoRules) {
                int at = text.indexOf(rule.wrong());
                if (at >= 0) points.add("ANOMALY:TYPO:" + rule.wrong() + "→" + rule.correct()
                        + ":" + anomalySnippet(text, at));
            }
            for (Fact fact : context.facts) {
                if (fact.evidence() == null || fact.evidence().page() != page.number()
                        || !"TEXT_ANOMALY".equalsIgnoreCase(fact.entityType())) continue;
                String type = upper(fact.field());
                if (allowed.contains(type)) points.add("ANOMALY:" + type + ":" + anomalySnippet(fact.rawValue()));
            }
            if (points.isEmpty()) continue;
            Evidence evidence = pageEvidence(context.bid, page, scope);
            List<SourceRef> sourceRefs = refs(context, List.of(evidence));
            String subject = subjectKey(context.spec.subjectSelector());
            context.add(new Observation.MaterialObservation(
                    stableObservationId("text-anomaly", subject,
                            scope + "|" + points.stream().sorted().collect(java.util.stream.Collectors.joining("|")), sourceRefs),
                    "TEXT:" + scope, Set.of(), subject, true, true, false, Set.copyOf(points),
                    TemplateTypes.ObservationState.SUPPORTED, sourceRefs, parserConfidence(page)));
        }
        LinkedHashSet<String> missingCapabilities = new LinkedHashSet<>(allowed);
        missingCapabilities.removeAll(availableDetectors);
        if (!missingCapabilities.isEmpty()) context.note("TEXT_ANOMALY_CAPABILITY_MISSING",
                "未配置可证明完成扫描的检测能力：" + String.join("、", missingCapabilities));
        if (context.documentComplete && scopeSeen && missingCapabilities.isEmpty())
            context.closed.add(context.spec.id() + ":text-scan");
        else context.note("TEXT_SCAN_SCOPE_OPEN", "文本范围“" + scope + "”尚未完成已配置异常类型的闭合扫描");
    }

    private void objectiveScoring(Context context) {
        String criterion = string(context.spec.parameters(), "criterion");
        String scoreUnit = string(context.spec.parameters(), "scoreUnit");
        if (scoreUnit.isBlank()) scoreUnit = "分";
        LinkedHashSet<String> emitted = new LinkedHashSet<>();
        boolean scoringScopeClosed = false;
        for (Fact fact : context.facts) {
            if (scoreScopeMarker(fact, criterion)) scoringScopeClosed = true;
            if (!"SCORE".equalsIgnoreCase(fact.entityType()) || !same(fact.field(), criterion)) continue;
            BigDecimal score = scoreValue(fact.normalizedValue(), fact.rawValue());
            if (score == null || fact.evidence() == null) continue;
            Page page = page(context.bid, fact.evidence());
            if (page == null) continue;
            String subject = fact.entityKey() == null || fact.entityKey().isBlank()
                    ? subjectKey(context.spec.subjectSelector()) : fact.entityKey();
            if (!emitted.add(scoreIdentity(fact.evidence(), subject, score))) continue;
            List<SourceRef> sourceRefs = refs(context, List.of(fact.evidence()));
            context.add(new Observation.PriceObservation(
                    stableObservationId("objective-score", subject, criterion + "|" + score, sourceRefs),
                    criterion, subject, "", "", "", "SCORE", scoreUnit, score, true,
                    TemplateTypes.ObservationState.SUPPORTED, sourceRefs,
                    observationConfidence(page, List.of(fact.evidence()))));
            if (explicitScoreSummary(fact.rawValue(), criterion, true) != null) scoringScopeClosed = true;
        }

        // The normal fact pipeline does not currently promise SCORE facts for every document.
        // Admit a page value only when it is an explicit, criterion-bound actual total (including
        // an explicit zero).  A heading such as "客观评分汇总" or a rule such as "最高 6 分"
        // does not close the collection and therefore can never be turned into an invented zero.
        for (Page page : sortedPages(context.bid)) {
            ScoreSummary summary = explicitScoreSummary(page.text(), criterion, false);
            if (summary == null) continue;
            Evidence evidence = new Evidence(context.bid.id(), context.bid.name(), page.number(), page.locationType(),
                    summary.quote(), "template-score-summary:" + safe(criterion) + ":" + summary.offset(),
                    parserConfidence(page));
            if (indexPage(page.text()) || example(page.text()) || historicalAt(page.text(), summary.quote())
                    || !currentEvidence(context, evidence, page, false)) {
                context.increment("rejectedObjectiveScoreScopeValues");
                continue;
            }
            String subject = subjectKey(context.spec.subjectSelector());
            if (emitted.add(scoreIdentity(evidence, subject, summary.score()))) {
                List<SourceRef> sourceRefs = refs(context, List.of(evidence));
                context.add(new Observation.PriceObservation(
                        stableObservationId("objective-score-summary", subject,
                                criterion + "|" + summary.score(), sourceRefs),
                        criterion, subject, "", "", "", "SCORE", scoreUnit, summary.score(), true,
                        TemplateTypes.ObservationState.SUPPORTED, sourceRefs, parserConfidence(page)));
            }
            scoringScopeClosed = true;
        }

        // A frozen, source-backed binary rule such as “具备质量管理体系认证证书，得1分”
        // is executable without first manufacturing a SCORE fact.  The rule is accepted only when
        // the material type and awarded score occur in the same confirmed requirement source; the
        // bid side must then provide a substantive, current-project proof page.  Absence never
        // becomes an invented zero because the range remains open when no proof is located.
        if (emitted.isEmpty()) {
            ObjectiveRule rule = objectiveRule(context.spec, criterion);
            if (rule != null) {
                ObjectiveProof proof = objectiveMaterialProof(context, rule);
                List<Evidence> selected = !proof.eligible().isEmpty() ? proof.eligible()
                        : proof.ineligible().isEmpty() || !proof.unresolved().isEmpty()
                        ? List.of() : proof.ineligible();
                if (!selected.isEmpty()) {
                    boolean eligible = !proof.eligible().isEmpty();
                    BigDecimal awarded = eligible ? rule.score() : BigDecimal.ZERO;
                    List<SourceRef> sourceRefs = new ArrayList<>(refs(context, selected));
                    sourceRefs.addAll(rule.sources());
                    sourceRefs = sourceRefs.stream().collect(java.util.stream.Collectors.toMap(
                            TemplateEvidenceAssembler::sourceKey, source -> source, (left, right) -> left,
                            LinkedHashMap::new)).values().stream().toList();
                    String subject = subjectKey(context.spec.subjectSelector());
                    context.add(new Observation.PriceObservation(
                            stableObservationId("objective-rule-proof", subject,
                                    criterion + "|" + rule.materialType() + "|" + awarded, sourceRefs),
                            criterion, subject, "", "", "", "SCORE", scoreUnit, awarded, true,
                            TemplateTypes.ObservationState.SUPPORTED, sourceRefs,
                            averageConfidenceEvidence(selected)));
                    emitted.add("rule:" + rule.materialType());
                    scoringScopeClosed = true;
                    context.increment(eligible ? "objectiveRuleProofs" : "objectiveRuleZeroProofs");
                    if (!eligible) context.note("OBJECTIVE_RULE_NOT_SATISFIED_AT_REFERENCE_DATE",
                            "已定位到指定主体的证明材料，但有效状态未覆盖招标比较时点；本项按可追溯证据计0分");
                }
            }
        }
        if (context.documentComplete && scoringScopeClosed) context.closed.add(context.spec.id() + ":scoring");
        else context.note("SCORING_SCOPE_OPEN",
                "文档已读取不等于评分事实集合已闭合；尚缺“" + criterion + "”的汇总或明确范围标记");
    }

    private void qualityAdvisory(Context context) {
        String materialType = string(context.spec.parameters(), "materialType");
        List<String> dimensions = qualityDimensions(context.spec);
        MaterialScan scan = scanMaterial(context, materialType, Set.copyOf(dimensions), true);
        QualitySectionScan sectionScan = qualitySectionBody(context, materialType, dimensions);
        if (!sectionScan.observations().isEmpty()) {
            sectionScan.observations().forEach(context::add);
            return;
        }
        // A numbered material section is a stronger scope signal than the legacy whole-document
        // scan.  If it contains only an outline, keep the result pending instead of letting the
        // generic fallback promote navigation labels into supported quality dimensions.
        if (sectionScan.sectionLocated()) {
            context.note("QUALITY_SECTION_BODY_PENDING", "已定位“" + materialType + "”章节，但尚未定位到可评价正文");
            return;
        }
        Observation.MaterialObservation material = null;
        if (material == null) material = aggregateCommitment(context, materialType, scan);
        if (material == null) material = explicitQualityBody(context, materialType, dimensions, scan);
        if (material == null) {
            scan.observations.forEach(context::add);
            context.note("QUALITY_BODY_PENDING", "尚未定位到“" + materialType + "”的可评价正文");
            return;
        }
        LinkedHashSet<String> points = new LinkedHashSet<>();
        String corpus = material.sources().stream().map(SourceRef::quote)
                .collect(java.util.stream.Collectors.joining("\n"));
        for (String dimension : dimensions) {
            if (explicitGap(corpus, dimension)) points.add("GAP:" + dimension + ":原文明确存在缺项或未说明");
            else if (material.commitmentPoints().stream().anyMatch(point -> same(point, dimension)
                            || point.regionMatches(true, 0, "DIMENSION:" + dimension + ":SUPPORTED", 0,
                            ("DIMENSION:" + dimension + ":SUPPORTED").length())))
                points.add("DIMENSION:" + dimension + ":SUPPORTED");
        }
        context.add(new Observation.MaterialObservation(material.id(), material.materialType(), material.aliases(),
                material.subjectKey(), material.currentScope(), material.bodyPresent(), material.titleOnly(),
                Set.copyOf(points), material.state(), material.sources(), material.confidence()));
        // Advisory coverage is intentionally never closed into a hard negative: absent qualitative
        // detail remains an evaluator/committee judgement rather than a document-compliance failure.
    }

    private Observation.MaterialObservation explicitQualityBody(Context context, String materialType,
                                                                List<String> dimensions, MaterialScan scan) {
        List<Observation.MaterialObservation> candidates = scan.observations.stream()
                .filter(Observation.MaterialObservation::currentScope)
                .filter(value -> value.state() == TemplateTypes.ObservationState.SUPPORTED
                        && value.bodyPresent() && !value.titleOnly())
                .filter(value -> value.sources().stream().map(SourceRef::quote)
                        .anyMatch(quote -> dimensions.stream().anyMatch(dimension -> containsNormalized(quote, dimension))))
                .toList();
        if (candidates.isEmpty()) return null;
        LinkedHashMap<String, SourceRef> sources = new LinkedHashMap<>();
        LinkedHashSet<String> covered = new LinkedHashSet<>();
        double confidence = 1;
        for (Observation.MaterialObservation candidate : candidates) {
            candidate.sources().forEach(source -> sources.putIfAbsent(sourceKey(source), source));
            for (String dimension : dimensions) if (candidate.sources().stream()
                    .anyMatch(source -> containsNormalized(source.quote(), dimension))) covered.add(dimension);
            confidence = Math.min(confidence, candidate.confidence());
        }
        List<SourceRef> sourceRefs = List.copyOf(sources.values());
        String subject = subjectKey(context.spec.subjectSelector());
        return new Observation.MaterialObservation(
                stableObservationId("quality-material", subject,
                        materialType + "|" + covered.stream().sorted().collect(java.util.stream.Collectors.joining("|")),
                        sourceRefs), materialType, Set.of(), subject, true, true, false, Set.copyOf(covered),
                TemplateTypes.ObservationState.SUPPORTED, sourceRefs, confidence);
    }

    private static ObjectiveRule objectiveRule(CheckSpec spec, String criterion) {
        if (spec == null || !spec.confirmed() || spec.requirementRefs().isEmpty()
                || criterion == null || criterion.isBlank()) return null;
        Pattern rulePattern = Pattern.compile(
                "(?:(投标人|供应商|响应人|施工单位|设计单位|联合体成员)\\s*(?:应当|应|须|必须)?\\s*)?"
                        + "(?:具备|具有|提供|持有)\\s*([^，,。；;\\r\\n]{2,80}?(?:认证证书|资格证书|资质证书|许可证|证书|资质|证明材料|证明))"
                        + "\\s*(?:的)?\\s*[,，]?\\s*(?:得|计)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*分");
        LinkedHashMap<String, ObjectiveRuleBuilder> candidates = new LinkedHashMap<>();
        for (SourceRef source : spec.requirementRefs()) {
            Matcher matcher = rulePattern.matcher(Objects.toString(source.quote(), ""));
            while (matcher.find()) {
                String partyRole = Objects.toString(matcher.group(1), "").strip();
                String materialType = objectiveMaterialLookupType(matcher.group(2));
                if (materialType.length() < 2 || materialType.length() > 60
                        || !containsNormalized(criterion, materialType)) continue;
                BigDecimal score;
                try { score = new BigDecimal(matcher.group(3)); }
                catch (NumberFormatException invalid) { continue; }
                BigDecimal maximum;
                try { maximum = new BigDecimal(string(spec.parameters(), "maxScore")); }
                catch (NumberFormatException invalid) { maximum = score; }
                if (score.signum() <= 0 || maximum.signum() < 0 || score.compareTo(maximum) > 0) continue;
                String key = normalize(partyRole) + "|" + normalize(materialType) + "|"
                        + score.stripTrailingZeros().toPlainString();
                candidates.computeIfAbsent(key,
                        ignored -> new ObjectiveRuleBuilder(partyRole, materialType, score)).add(source);
            }
        }
        // A confirmed check may cite a whole scoring table.  Never select the first matching row:
        // auto-scoring is allowed only when the criterion resolves to one unique source-bound rule.
        if (candidates.size() != 1) return null;
        ObjectiveRuleBuilder selected = candidates.values().iterator().next();
        return new ObjectiveRule(selected.partyRole, selected.materialType, selected.score,
                List.copyOf(selected.sources.values()));
    }

    private static String objectiveMaterialLookupType(String value) {
        String materialType = cleanMarkup(Objects.toString(value, "")).strip();
        // The tender rule may require an "有效的" certificate, while the certificate itself is
        // headed only by its formal name. Validity is proved independently from the labelled date
        // range at the tender deadline, so source-side validity adjectives are not part of the
        // material lookup identity. The original requirement source remains attached to the score.
        return materialType.replaceFirst(
                "^(?:(?:合法(?:且)?|真实|现行|持续)?有效(?:期内)?|(?:在|处于)有效期内)\\s*的?\\s*", "")
                .strip();
    }

    private ObjectiveProof objectiveMaterialProof(Context context, ObjectiveRule rule) {
        List<Evidence> eligible = new ArrayList<>(), ineligible = new ArrayList<>(), unresolved = new ArrayList<>();
        LocalDate comparisonDate = objectiveComparisonDate(context);
        String materialType = rule.materialType();
        for (Page page : sortedPages(context.bid)) {
            String text = Objects.toString(page.text(), "");
            if (!containsNormalized(text, materialType) || indexPage(text) || example(text)) continue;
            for (String block : objectiveCertificateBlocks(text, materialType)) {
                String quote = trimQuote(block);
                if (historicalAt(block, quote)) continue;
                ObjectiveCertificateEligibility.Assessment assessment =
                        ObjectiveCertificateEligibility.assess(block, comparisonDate);
                String holder = objectiveCertificateHolder(block);
                if (!objectivePartyMatches(context, rule.partyRole(), holder, block)) continue;
                Evidence evidence = new Evidence(context.bid.id(), context.bid.name(), page.number(), page.locationType(),
                        quote, "template-objective-material:" + safe(materialType), parserConfidence(page));
                if (!currentEvidence(context, evidence, page, false) && !currentMaterialContext(text)) continue;
                switch (assessment.state()) {
                    case ELIGIBLE -> eligible.add(evidence);
                    case INELIGIBLE -> ineligible.add(evidence);
                    case UNRESOLVED -> unresolved.add(evidence);
                }
                if (eligible.size() + ineligible.size() + unresolved.size() >= 6)
                    return new ObjectiveProof(List.copyOf(eligible), List.copyOf(ineligible), List.copyOf(unresolved));
            }
        }
        return new ObjectiveProof(List.copyOf(eligible), List.copyOf(ineligible), List.copyOf(unresolved));
    }

    private static List<String> objectiveCertificateBlocks(String text, String materialType) {
        String[] lines = Objects.toString(text, "").split("\\R", -1);
        LinkedHashMap<String, String> blocks = new LinkedHashMap<>();
        for (int index = 0; index < lines.length; index++) {
            if (!containsNormalized(lines[index], materialType)) continue;
            StringBuilder block = new StringBuilder();
            for (int cursor = index; cursor < lines.length && cursor < index + 45; cursor++) {
                String line = lines[cursor];
                if (cursor > index && otherCertificateHeading(line, materialType)) break;
                if (block.length() + line.length() > 2200) break;
                block.append(line).append('\n');
            }
            String value = block.toString().strip();
            if (!value.isBlank()) blocks.putIfAbsent(normalize(value), value);
        }
        return List.copyOf(blocks.values());
    }

    private static boolean otherCertificateHeading(String line, String materialType) {
        String value = cleanMarkup(Objects.toString(line, "")).strip();
        if (value.isBlank() || value.length() > 100 || containsNormalized(value, materialType)) return false;
        return value.matches(".*(?:认证证书|资格证书|资质证书|许可证)\\s*[（(]?(?:副本|正本)?[）)]?\\s*$");
    }

    private LocalDate objectiveComparisonDate(Context context) {
        LinkedHashSet<LocalDate> dates = datesForRole(context, "投标截止时间").stream()
                .map(DatedSource::date).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        // Objective material eligibility is evaluated at the proved tender deadline.  An opening
        // date is not a safe substitute when the deadline is absent or conflicting: the two times
        // may differ, and choosing one would turn an evidence gap into an invented 0/positive score.
        return dates.size() == 1 ? dates.iterator().next() : null;
    }

    private static String objectiveCertificateHolder(String quote) {
        Matcher labelled = Pattern.compile("(?:兹证明|证书持有人|持证单位|组织名称|企业名称|单位名称)"
                + "\\s*[:：]?\\s*([^，,。；;\\r\\n]{2,100}?(?:有限责任公司|股份有限公司|有限公司|公司|设计院|研究院))")
                .matcher(Objects.toString(quote, ""));
        if (labelled.find()) return cleanMarkup(labelled.group(1)).strip();
        Matcher company = Pattern.compile("([\\p{IsHan}A-Za-z0-9（）()·]{2,80}?(?:有限责任公司|股份有限公司|有限公司|公司|设计院|研究院))")
                .matcher(Objects.toString(quote, ""));
        return company.find() ? cleanMarkup(company.group(1)).strip() : "";
    }

    private static boolean objectivePartyMatches(Context context, String partyRole, String holder, String proofText) {
        if (partyRole == null || partyRole.isBlank()) return !holder.isBlank();
        if (holder.isBlank()) return false;
        String role = normalize(partyRole);
        String roleTerms = role.contains("设计") ? "(?:设计单位|设计成员|设计方|负责[^。；;\\n]{0,20}设计|承担[^。；;\\n]{0,20}设计)"
                : role.contains("施工") ? "(?:施工单位|施工成员|施工方|负责[^。；;\\n]{0,20}施工|承担[^。；;\\n]{0,20}施工)"
                : role.contains("联合体成员") ? "(?:联合体成员|联合体牵头人|联合体牵头单位|施工成员|设计成员)" : "";
        if (!roleTerms.isBlank()) {
            Pattern bound = Pattern.compile(Pattern.quote(holder) + "[^。；;\\n]{0,100}" + roleTerms
                    + "|" + roleTerms + "[^。；;\\n]{0,100}" + Pattern.quote(holder));
            if (bound.matcher(cleanMarkup(proofText)).find()) return true;
            for (Page page : sortedPages(context.bid)) {
                String text = Objects.toString(page.text(), "");
                if (!containsNormalized(text, holder) || indexPage(text) || historicalAt(text, holder)) continue;
                if (bound.matcher(cleanMarkup(text)).find()) return true;
            }
            boolean consortium = sortedPages(context.bid).stream().map(Page::text).filter(Objects::nonNull)
                    .anyMatch(text -> text.contains("联合体"));
            return !consortium && sameCompanyValue(primaryCompanyValue(context), holder);
        }
        return sameCompanyValue(primaryCompanyValue(context), holder)
                || sortedPages(context.bid).stream().map(Page::text).filter(Objects::nonNull)
                .anyMatch(text -> containsNormalized(text, holder) && CURRENT.matcher(cleanMarkup(text)).find()
                        && !historicalAt(text, holder));
    }

    /**
     * Expands an “完整性” rule into the explicitly enumerated source-bound components.  This keeps
     * old confirmed specs executable while newly mapped specs can store the component list directly.
     */
    static List<String> qualityDimensions(CheckSpec spec) {
        List<String> configured = strings(spec.parameters(), "dimensions");
        if (configured.size() != 1 || !same(configured.get(0), "完整性")) return configured;
        String requirement = spec.requirementRefs().stream().map(SourceRef::quote)
                .collect(java.util.stream.Collectors.joining("\n"));
        Matcher count = Pattern.compile("([2-9]|10)\\s*项(?:内容|要素|指标|方面)?").matcher(requirement);
        if (!count.find()) return configured;
        int expected = Integer.parseInt(count.group(1));
        String prefix = requirement.substring(Math.max(0, count.start() - 180), count.start());
        int colon = Math.max(prefix.lastIndexOf('：'), prefix.lastIndexOf(':'));
        if (colon >= 0) prefix = prefix.substring(colon + 1);
        prefix = prefix.replaceFirst("^\\s*(?:对)?(?:本项目|本工程)?的?", "").strip();
        List<String> parsed = java.util.Arrays.stream(prefix.split("[、,，]|(?:以及|及|和)"))
                .map(String::strip)
                .map(value -> value.replaceFirst("^(?:包括|包含|涵盖)\\s*", ""))
                .filter(value -> value.length() >= 2 && value.length() <= 28)
                .toList();
        if (parsed.size() != expected || parsed.stream().map(TemplateEvidenceAssembler::normalize)
                .distinct().count() != parsed.size()) return configured;
        return parsed;
    }

    private QualitySectionScan qualitySectionBody(Context context, String materialType,
                                                  List<String> dimensions) {
        if (materialType.isBlank() || dimensions.isEmpty()) return new QualitySectionScan(false, List.of());
        List<Page> pages = sortedPages(context.bid);
        int start = -1;
        int startOffset = 0;
        String sectionNumber = "";
        Pattern numbered = Pattern.compile("(?m)(?:^|\\n)\\s*(\\d+(?:\\.\\d+)+)[ \\t、.．)]*"
                + Pattern.quote(materialType) + "(?:\\s|$)");
        for (int index = 0; index < pages.size(); index++) {
            String text = Objects.toString(pages.get(index).text(), "");
            if (indexPage(text) || example(text) || historicalAt(text, materialType)
                    || !containsNormalized(text, materialType)) continue;
            Matcher matcher = numbered.matcher(text);
            if (matcher.find()) {
                start = index;
                startOffset = matcher.start(1);
                sectionNumber = matcher.group(1);
                break;
            }
            if (heading(text.lines().findFirst().orElse(""), materialType)) {
                start = index;
                break;
            }
        }
        if (start < 0) return new QualitySectionScan(false, List.of());

        List<SectionSlice> slices = qualitySectionSlices(pages, start, startOffset, sectionNumber, materialType);
        boolean sectionLocated = true;
        if (slices.isEmpty()) return new QualitySectionScan(sectionLocated, List.of());

        List<Observation.MaterialObservation> observations = new ArrayList<>();
        for (String dimension : dimensions) {
            SectionSlice best = null;
            int bestScore = Integer.MIN_VALUE;
            for (int index = 0; index < slices.size(); index++) {
                SectionSlice slice = slices.get(index);
                int score = qualityBodyScore(slice.text(), dimension, dimensions, index == 0);
                if (score > bestScore) { best = slice; bestScore = score; }
            }
            if (best == null || bestScore < 3) continue;
            String quote = excerpt(best.text(), dimension, 1200);
            Evidence evidence = new Evidence(context.bid.id(), context.bid.name(), best.page().number(),
                    best.page().locationType(), quote,
                    "template-quality-section:" + safe(materialType) + ":" + safe(dimension),
                    parserConfidence(best.page()));
            if (historicalAt(best.text(), quote)) continue;
            List<SourceRef> sourceRefs = refs(context, List.of(evidence));
            if (sourceRefs.isEmpty()) continue;
            String subject = subjectKey(context.spec.subjectSelector());
            Set<String> points = explicitGap(best.text(), dimension)
                    ? Set.of("GAP:" + dimension + ":原文明确存在缺项或未说明")
                    : Set.of("DIMENSION:" + dimension + ":SUPPORTED");
            observations.add(new Observation.MaterialObservation(
                    stableObservationId("quality-section", subject, materialType + "|" + dimension, sourceRefs),
                    materialType, Set.of(), subject, true, true, false, points,
                    TemplateTypes.ObservationState.SUPPORTED, sourceRefs, parserConfidence(best.page())));
        }
        context.counters.put("qualityDimensionsLocated", observations.size());
        return new QualitySectionScan(sectionLocated, List.copyOf(observations));
    }

    private static int qualityBodyScore(String text, String dimension, List<String> dimensions,
                                        boolean overviewPage) {
        if (text == null || indexPage(text) || !containsNormalized(text, dimension)) return Integer.MIN_VALUE;
        String[] lines = text.split("\\R", -1);
        int outlineEnd = overviewPage ? qualityOutlineEnd(lines, dimensions) : -1;
        int score = Integer.MIN_VALUE;
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex].strip();
            if (!containsNormalized(line, dimension)) continue;
            boolean dimensionHeading = qualityDimensionHeading(line, dimension);
            if (!dimensionHeading && explicitQualityDimensionStatement(line, List.of(dimension))) {
                score = Math.max(score, line.length() >= 28 ? 5 : 3);
                continue;
            }
            if (!dimensionHeading) continue;
            // A section cover/diagram often lists every dimension and then contains a long OCR
            // explanation of that diagram.  Those outline entries are navigation, not the
            // dimension's business body.  A repeated heading after the outline remains eligible.
            if (lineIndex <= outlineEnd) continue;
            int headingLevel = qualityHeadingLevel(line);
            StringBuilder body = new StringBuilder();
            for (int next = lineIndex + 1; next < lines.length; next++) {
                String candidate = lines[next].strip();
                int candidateLevel = qualityHeadingLevel(candidate);
                // Any structural peer/ancestor ends this dimension body, even when its title is not
                // one of the requested dimensions.  Deeper sections and bare numbered list items
                // remain inside the current chapter.
                if (candidateLevel > 0 && (headingLevel <= 0 || candidateLevel <= headingLevel)) break;
                body.append(candidate).append('\n');
            }
            int bodyLength = normalize(body.toString()).length();
            if (bodyLength >= 24) score = Math.max(score, 5 + Math.min(2, bodyLength / 180));
        }
        if (score == Integer.MIN_VALUE) return score;
        long mentioned = dimensions.stream().filter(value -> containsNormalized(text, value)).count();
        if (overviewPage && outlineEnd >= 0 && mentioned >= 3) score -= 3;
        return score;
    }

    private static boolean explicitQualityDimensionStatement(String text, List<String> dimensions) {
        if (text == null || dimensions == null || dimensions.isEmpty()) return false;
        Pattern predicate = Pattern.compile("具有|包含|涵盖|围绕|结合|分析|说明|明确|提出|采用|通过|制定|实施|保障|论证|形成");
        Pattern navigation = Pattern.compile("仅为|仅作|导航|目录|将在|后续|随后|详见|参见|尚未|未提供|未说明|不包含|不含|待补|待完善|拟在");
        for (String line : text.split("\\R")) {
            String value = line.strip();
            if (value.length() < 10 || navigation.matcher(value).find() || !predicate.matcher(value).find()) continue;
            for (String dimension : dimensions) {
                if (containsNormalized(value, dimension) && !qualityDimensionHeading(value, dimension)) return true;
            }
        }
        return false;
    }

    private static int qualityOutlineEnd(String[] lines, List<String> dimensions) {
        // One or two headings cannot establish an outline by themselves; they may simply be the
        // first substantive chapter.  Require at least three distinct dimension headings before
        // suppressing the first occurrences as navigation.
        if (lines == null || dimensions == null || dimensions.size() < 3) return -1;
        LinkedHashMap<String, Integer> firstHeadings = new LinkedHashMap<>();
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex].strip();
            for (String dimension : dimensions) {
                if (!firstHeadings.containsKey(dimension) && qualityDimensionHeading(line, dimension))
                    firstHeadings.put(dimension, lineIndex);
            }
        }
        if (firstHeadings.size() < 3) return -1;
        return firstHeadings.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
    }

    private static int qualityHeadingLevel(String line) {
        String value = cleanMarkup(Objects.toString(line, ""));
        Matcher chinese = Pattern.compile("第?[一二三四五六七八九十百0-9]+\\s*(章|节|条)").matcher(value);
        if (chinese.find()) return switch (chinese.group(1)) {
            case "章" -> 1;
            case "节" -> 2;
            default -> 3;
        };
        Matcher numbered = Pattern.compile("(?<!\\d)(\\d+(?:\\.\\d+)+)").matcher(value);
        if (numbered.find()) return numbered.group(1).split("\\.").length;
        if (value.matches("^[^\\p{IsHan}A-Za-z0-9]{0,8}[一二三四五六七八九十0-9]+[、.．)].*")) return 3;
        return 0;
    }

    private static boolean qualityDimensionHeading(String line, String dimension) {
        if (line == null || line.isBlank() || line.length() > 72 || !containsNormalized(line, dimension)) return false;
        String value = cleanMarkup(line).strip();
        String remaining = normalize(value).replace(normalize(dimension), "");
        if (remaining.isBlank()) return true;
        boolean prefixed = value.matches("^(?:第?[一二三四五六七八九十百0-9]+\\s*(?:章|节|条)[、.．)]*"
                + "|\\d+(?:\\.\\d+)+[ \\t、.．)]*|[一二三四五六七八九十0-9]+[、.．)])\\s*.*");
        return prefixed && remaining.length() <= 18;
    }

    private static List<SectionSlice> qualitySectionSlices(List<Page> pages, int start, int startOffset,
                                                           String sectionNumber, String materialType) {
        List<SectionSlice> result = new ArrayList<>();
        Pattern nextHeading = Pattern.compile("(?m)(?:^|\\n)[ \\t]*(\\d+(?:\\.\\d+)*)"
                + "([ \\t]+|[、.．)][ \\t]*)([^\\r\\n]{1,80})");
        int limit = Math.min(pages.size(), start + 80);
        for (int index = start; index < limit; index++) {
            Page page = pages.get(index);
            String text = Objects.toString(page.text(), "");
            int from = index == start ? Math.max(0, Math.min(startOffset, text.length())) : 0;
            int to = text.length();
            boolean ended = false;
            if (!sectionNumber.isBlank()) {
                Matcher matcher = nextHeading.matcher(text);
                while (matcher.find()) {
                    if ((index == start && matcher.start(1) <= from) || matcher.group(1).equals(sectionNumber)) continue;
                    boolean precededByBareSequence = earlierBareNumberSequence(text, from, matcher.start(1),
                            matcher.group(1));
                    if (peerOrAncestorSection(sectionNumber, matcher.group(1), matcher.group(2), matcher.group(3),
                            matcher.start(1), precededByBareSequence)
                            && !containsNormalized(matcher.group(3), materialType)) {
                        to = matcher.start(1);
                        ended = true;
                        break;
                    }
                }
            }
            if (to > from) {
                String slice = text.substring(from, to).strip();
                if (!slice.isBlank()) result.add(new SectionSlice(page, slice));
            }
            if (ended) break;
        }
        return List.copyOf(result);
    }

    private static boolean peerOrAncestorSection(String current, String candidate,
                                                 String separator, String title, int pageOffset,
                                                 boolean precededByBareSequence) {
        if (current == null || candidate == null || current.equals(candidate)) return false;
        String[] currentParts = current.split("\\.");
        String[] candidateParts = candidate.split("\\.");
        if (candidateParts.length > currentParts.length) return false;
        // A peer must retain the numbered parent path.  Treating every different leading number as
        // a peer made “1、功能需求分析”, page numbers and measurements such as “20 厘米” terminate
        // section 4.1.  A bare shallower number is therefore not a safe boundary for a dotted
        // subsection; the next 4.2 (or, for a deeper current path, 4.2 after 4.1.x) remains one.
        if (candidateParts.length == 1 && currentParts.length > 1) {
            try {
                int currentRoot = Integer.parseInt(currentParts[0]);
                int candidateRoot = Integer.parseInt(candidateParts[0]);
                if (candidateRoot != currentRoot + 1) return false;
            } catch (NumberFormatException invalid) {
                return false;
            }
            String boundaryTitle = cleanMarkup(Objects.toString(title, "")).strip();
            String boundarySeparator = Objects.toString(separator, "");
            // “1、功能需求分析” is normally an inner list item.  Accept only the next root
            // number near a page start with a major-section cue; reject common measurement lines.
            if (boundarySeparator.contains("、") || boundaryTitle.length() < 2 || boundaryTitle.length() > 48
                    || pageOffset > 80 || precededByBareSequence
                    || !boundaryTitle.matches(".*(?:施工组织(?:设计|方案)?|设计方案|技术方案|商务文件|资格审查|项目管理(?:机构|方案)?|质量管理(?:方案|体系)?|进度计划|安全管理|环境保护|资源配置|售后服务|运维服务|应急预案|投标报价|承诺文件|附表|附件|总结).*")
                    || boundaryTitle.matches(".*(?:功能|需求|问题|场景|接口|数据|流程|模块|指标)分析.*")
                    || boundaryTitle.matches("^(?:毫米|厘米|分米|米|千米|mm|cm|m|km|人|名|个|项|元|万元|%|天|日|月|年)(?:\\s|$).*"))
                return false;
            long han = boundaryTitle.codePoints().filter(code -> Character.UnicodeScript.of(code)
                    == Character.UnicodeScript.HAN).count();
            return han >= 2 && !boundaryTitle.matches(".*[。；;，,：:]$");
        }
        int leaf = candidateParts.length - 1;
        for (int index = 0; index < leaf; index++) {
            if (!candidateParts[index].equals(currentParts[index])) return false;
        }
        try {
            return Integer.parseInt(candidateParts[leaf]) > Integer.parseInt(currentParts[leaf]);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean earlierBareNumberSequence(String text, int from, int to, String candidate) {
        if (candidate == null || candidate.contains(".")) return false;
        int candidateNumber;
        try { candidateNumber = Integer.parseInt(candidate); }
        catch (NumberFormatException invalid) { return false; }
        if (candidateNumber <= 1 || text == null || to <= from) return false;
        String prefix = text.substring(Math.max(0, from), Math.min(to, text.length()));
        Matcher matcher = Pattern.compile("(?m)^[ \\t]*(\\d+)[ \\t]+[^\\r\\n]{1,80}$").matcher(prefix);
        LinkedHashSet<Integer> earlier = new LinkedHashSet<>();
        while (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (value > 0 && value < candidateNumber) earlier.add(value);
            } catch (NumberFormatException ignored) { }
        }
        return earlier.size() >= 2 && earlier.contains(candidateNumber - 1);
    }

    private static String excerpt(String text, String term, int limit) {
        String value = Objects.toString(text, "").strip();
        int at = value.indexOf(term);
        if (at < 0) at = 0;
        int start = Math.max(0, at - 100);
        int end = Math.min(value.length(), start + Math.max(200, limit));
        return value.substring(start, end).strip();
    }

    private void externalCheck(Context context) {
        Map<String, Object> parameters = context.spec.parameters();
        String source = string(parameters, "source"), queryObject = string(parameters, "queryObject");
        String asOfDate = string(parameters, "asOfDate"), adapterId = string(parameters, "adapterId");
        String evaluationStage = string(parameters, "evaluationStage");
        Set<String> requiredFields = new LinkedHashSet<>(strings(parameters, "requiredFields"));
        LinkedHashSet<String> points = new LinkedHashSet<>();
        List<Evidence> admitted = new ArrayList<>();
        for (Fact fact : context.facts) {
            if (!trustedConnectorFact(fact) || fact.evidence() == null) continue;
            Evidence evidence = fact.evidence();
            String trustText = String.join("|", Objects.toString(fact.rawValue(), ""),
                    Objects.toString(fact.normalizedValue(), ""), Objects.toString(evidence.anchor(), ""),
                    Objects.toString(evidence.quote(), ""));
            if (upper(trustText).contains("PROVIDED_NOT_AUTHENTICATED")) {
                context.increment("rejectedUnauthenticatedExternalRecords");
                continue;
            }
            boolean sourceMatches = same(source, evidence.documentName()) || same(source, evidence.documentId())
                    || containsNormalized(evidence.quote(), source);
            boolean queryMatches = same(queryObject, fact.entityKey());
            boolean fieldMatches = requiredFields.contains(fact.field());
            boolean adapterMatches = !adapterId.isBlank() && containsNormalized(evidence.anchor(), "adapter:" + adapterId);
            boolean timeMatches = !asOfDate.isBlank() && connectorAsOf(fact).map(asOfDate::equals).orElse(false);
            boolean stageMatches = !evaluationStage.isBlank()
                    && containsNormalized(evidence.anchor(), "stage:" + evaluationStage);
            if (!sourceMatches || !queryMatches || !fieldMatches || !adapterMatches || !timeMatches
                    || !stageMatches) {
                context.increment("rejectedMismatchedExternalRecords");
                continue;
            }
            String state = upper(fact.normalizedValue());
            if (Set.of("UNRESOLVED", "UNKNOWN", "PENDING", "").contains(state)) continue;
            if (Set.of("NEGATIVE", "NOT_SATISFIED", "FALSE", "RISK", "HIT").contains(state))
                points.add("NEGATIVE:" + fact.field());
            else points.add("FIELD:" + fact.field() + "=" + Objects.toString(fact.normalizedValue(), "").strip());
            admitted.add(evidence);
        }
        if (!points.isEmpty()) {
            List<SourceRef> sourceRefs = refs(context, admitted);
            String subject = subjectKey(context.spec.subjectSelector());
            context.add(new Observation.MaterialObservation(
                    stableObservationId("external-record", subject,
                            source + "|" + queryObject + "|" + points.stream().sorted()
                                    .collect(java.util.stream.Collectors.joining("|")), sourceRefs),
                    "EXTERNAL:" + source, Set.of(), subject, true, true, false, Set.copyOf(points),
                    TemplateTypes.ObservationState.SUPPORTED, sourceRefs,
                    averageConfidenceEvidence(admitted)));
        } else {
            context.note("EXTERNAL_RECORD_REQUIRED",
                    "该检查需要匹配来源、对象、时点和字段的 TRUSTED_CONNECTOR 记录；本地文档或未认证上传记录不会形成结论");
        }
    }

    private void materialPresent(Context context) {
        String materialType = string(context.spec.parameters(), "materialType");
        boolean requireBody = bool(context.spec.parameters(), "requireBody", true);
        MaterialScan scan = scanMaterial(context, materialType, Set.of(), requireBody);
        scan.observations.forEach(context::add);
        closeMaterialNeed(context, "material", scan);
    }

    private void materialAlternative(Context context) {
        List<String> alternatives = strings(context.spec.parameters(), "alternatives");
        boolean requireBody = bool(context.spec.parameters(), "requireBody", true);
        for (String alternative : alternatives) {
            MaterialScan scan = scanMaterial(context, alternative, Set.of(), requireBody);
            scan.observations.forEach(context::add);
            // Each branch owns its own evidence need. Finding one branch never closes another.
            closeMaterialNeed(context, "alternative-" + safe(alternative), scan);
        }
    }

    private void commitmentWithSeal(Context context) {
        String materialType = string(context.spec.parameters(), "materialType");
        Set<String> requiredPoints = new LinkedHashSet<>(strings(context.spec.parameters(), "requiredCommitmentPoints"));
        MaterialScan scan = scanMaterial(context, materialType, requiredPoints, true);
        Observation.MaterialObservation commitment = aggregateCommitment(context, materialType, scan);
        if (commitment != null) context.add(commitment);
        else scan.observations.forEach(context::add);
        closeMaterialNeed(context, "commitment", scan);

        List<TrustedSeal> seals = trustedSeals(context, materialType, scan);
        for (TrustedSeal seal : seals) {
            String materialId = commitment == null ? "" : commitment.id();
            String subject = subjectKey(context.spec.subjectSelector());
            context.add(new Observation.SealObservation(
                    stableObservationId("seal", subject,
                            materialType + "|" + seal.location + "|" + seal.observed, seal.sources),
                    materialId, subject, seal.location, seal.currentScope,
                    seal.observed, seal.observed ? TemplateTypes.ObservationState.SUPPORTED : TemplateTypes.ObservationState.REFUTED,
                    seal.sources, observationConfidence(page(context.bid, seal.evidence), List.of(seal.evidence))));
        }
        // An admitted visual/structural observation closes this exact seal request. Mere words such
        // as “盖章处” or “已盖章” never enter trustedSeals and therefore cannot close it.
        if (!seals.isEmpty()) context.closed.add(context.spec.id() + ":seal");
        if (seals.isEmpty() && containsSealWords(context.bid)) {
            context.increment("rejectedTextSealMentions");
            context.note("SEAL_TEXT_NOT_VISUAL_PROOF", "文中出现盖章或签字字样，但没有可定位的视觉/结构观察，未作为印章证据");
        }
    }

    private Observation.MaterialObservation aggregateCommitment(Context context, String materialType, MaterialScan scan) {
        List<Observation.MaterialObservation> body = scan.observations.stream()
                .filter(value -> value.currentScope() && value.state() == TemplateTypes.ObservationState.SUPPORTED
                        && value.bodyPresent() && !value.titleOnly()).toList();
        if (body.isEmpty()) return null;
        LinkedHashSet<String> points = new LinkedHashSet<>();
        LinkedHashMap<String, SourceRef> sources = new LinkedHashMap<>();
        double confidence = 1;
        for (Observation.MaterialObservation value : body) {
            points.addAll(value.commitmentPoints());
            value.sources().forEach(source -> sources.putIfAbsent(sourceKey(source), source));
            confidence = Math.min(confidence, value.confidence());
        }
        String subject = subjectKey(context.spec.subjectSelector());
        List<SourceRef> sourceRefs = List.copyOf(sources.values());
        String pointScope = points.stream().sorted().collect(java.util.stream.Collectors.joining("|"));
        return new Observation.MaterialObservation(
                stableObservationId("material-commitment", subject, materialType + "|" + pointScope, sourceRefs),
                materialType, Set.of(), subject, true, true, false, Set.copyOf(points),
                TemplateTypes.ObservationState.SUPPORTED, sourceRefs, confidence);
    }

    private void certificateValidity(Context context) {
        String expectedType = string(context.spec.parameters(), "certificateType");
        List<PersonPage> pages = personPages(context);
        int rejectedType = 0, rejectedScope = 0;
        for (PersonPage personPage : pages) {
            Map<String, List<PersonCredentialFacts.Occurrence>> records = personPage.result.occurrences().stream()
                    .collect(java.util.stream.Collectors.groupingBy(PersonCredentialFacts.Occurrence::recordKey,
                            LinkedHashMap::new, java.util.stream.Collectors.toList()));
            for (List<PersonCredentialFacts.Occurrence> record : records.values()) {
                Map<String, List<String>> fields = fields(record);
                String actualType = first(fields, "certificateName");
                if (actualType.isBlank()) actualType = record.stream().map(PersonCredentialFacts.Occurrence::credentialType)
                        .filter(value -> !Set.of("", "UNKNOWN", "PERSON_ROSTER").contains(value)).findFirst().orElse("");
                if (!certificateTypeMatches(expectedType, actualType)) { rejectedType++; continue; }
                Evidence evidence = record.get(0).evidence();
                String name = first(fields, "personName");
                String role = canonicalRole(first(fields, "role"));
                boolean current = currentEvidence(context, evidence, personPage.page, true)
                        || !name.isBlank() && !role.isBlank()
                        && explicitCurrentAppointment(context, personPage.page, record, name, role);
                if (!current) rejectedScope++;
                String number = certificateNumber(fields);
                String holderKey = certificateHolderKey(context, name, fields, record.get(0).recordKey());
                DateRange range = dateRange(fields);
                TemplateTypes.ObservationState state = range.conflicting
                        ? TemplateTypes.ObservationState.CONFLICTING : TemplateTypes.ObservationState.SUPPORTED;
                List<SourceRef> refs = refs(context, record.stream().map(PersonCredentialFacts.Occurrence::evidence).toList());
                context.add(new Observation.CertificateObservation(
                        stableObservationId("certificate", holderKey,
                                expectedType + "|" + number, refs),
                        expectedType, holderKey, name, number, range.from, range.until, range.longTerm,
                        current, state, refs, observationConfidence(personPage.page,
                        record.stream().map(PersonCredentialFacts.Occurrence::evidence).toList())));
            }
        }

        // Legacy local certificate facts are admitted only when the source itself binds a type and
        // holder. A bare “有效期” date is not silently attached to an arbitrary certificate/person.
        Set<String> represented = pages.stream().flatMap(page -> page.result.facts().stream())
                .map(TemplateEvidenceAssembler::factIdentity).collect(java.util.stream.Collectors.toSet());
        for (Fact expiry : context.facts) {
            if (!"certificateExpiry".equals(expiry.field()) || represented.contains(factIdentity(expiry))) continue;
            String quote = Objects.toString(expiry.evidence().quote(), "");
            String type = certificateTypeInQuote(expectedType, quote);
            String holder = labelled(quote, "(?:姓名|持证人|持有人)");
            if (type.isBlank() || holder.isBlank()) {
                context.increment("unboundCertificateDates");
                continue;
            }
            LocalDate until = parseDate(expiry.normalizedValue());
            if (until == null) { context.increment("unparsedCertificateDates"); continue; }
            boolean current = currentEvidence(context, expiry.evidence(), page(context.bid, expiry.evidence()), true);
            String holderKey = certificateHolderKey(context, holder, Map.of(), factIdentity(expiry));
            List<SourceRef> sourceRefs = refs(context, List.of(expiry.evidence()));
            context.add(new Observation.CertificateObservation(
                    stableObservationId("certificate", holderKey,
                            expectedType + "|" + until, sourceRefs),
                    expectedType, holderKey, holder, "", null, until,
                    LocalDate.of(9999, 12, 31).equals(until), current, TemplateTypes.ObservationState.SUPPORTED,
                    sourceRefs,
                    observationConfidence(page(context.bid, expiry.evidence()), List.of(expiry.evidence()))));
        }
        addCounter(context, "certificateTypeRejected", rejectedType);
        addCounter(context, "certificateScopeRejected", rejectedScope);
    }

    private void priceCompare(Context context) {
        scanPrice(context, string(context.spec.parameters(), "field"));
    }

    private PriceScan scanPrice(Context context, String field) {
        Map<String, Object> parameters = context.spec.parameters();
        String requiredCurrency = string(parameters, "currency");
        String requiredUnit = string(parameters, "unit");
        String requiredTax = string(parameters, "taxBasis");
        String requiredLot = string(parameters, "lot");
        String requiredLayer = string(parameters, "priceLayer");
        boolean unresolved = false;
        int matchedFacts = 0;
        for (Fact fact : context.facts) {
            if (!field.equals(fact.field()) || !"PRICE".equals(fact.entityType())) continue;
            matchedFacts++;
            ParsedPrice price = parsedPrice(fact);
            if (price == null) { context.increment("unparsedPrices"); unresolved = true; continue; }
            Page page = page(context.bid, fact.evidence());
            if (page == null) { context.increment("unboundPriceSources"); unresolved = true; continue; }
            String source = page == null ? Objects.toString(fact.evidence().quote(), "") : Objects.toString(page.text(), "");
            boolean current = currentEvidence(context, fact.evidence(), page, false)
                    || strongCurrentPrice(fact.evidence().quote());
            String lot = lotAround(source, fact.evidence().quote());
            String layer = priceLayer(fact.rawValue());
            String tax = price.taxBasis;

            boolean dimensionsKnown = knownRequired(requiredCurrency, price.currency, TemplateEvidenceAssembler::currencyEquivalent)
                    && knownRequired(requiredUnit, price.unit, TemplateEvidenceAssembler::unitEquivalent)
                    && knownRequired(requiredTax, tax, TemplateEvidenceAssembler::taxEquivalent)
                    && knownRequired(requiredLot, lot, TemplateEvidenceAssembler::same)
                    && knownRequired(requiredLayer, layer, TemplateEvidenceAssembler::layerEquivalent);
            String outputCurrency = requiredCurrency.isBlank() ? price.currency
                    : currencyEquivalent(requiredCurrency, price.currency) ? requiredCurrency : price.currency;
            String outputUnit = requiredUnit.isBlank() ? price.unit
                    : unitEquivalent(requiredUnit, price.unit) ? requiredUnit : price.unit;
            if (outputCurrency.isBlank()) outputCurrency = "UNKNOWN";
            if (outputUnit.isBlank()) outputUnit = "UNKNOWN";
            String outputTax = requiredTax.isBlank() ? tax : taxEquivalent(requiredTax, tax) ? requiredTax : tax;
            String outputLot = requiredLot.isBlank() ? lot : same(requiredLot, lot) ? requiredLot : lot;
            String outputLayer = requiredLayer.isBlank() ? layer : layerEquivalent(requiredLayer, layer) ? requiredLayer : layer;
            TemplateTypes.ObservationState state = current && dimensionsKnown
                    ? TemplateTypes.ObservationState.SUPPORTED : TemplateTypes.ObservationState.UNRESOLVED;
            if (!current) { context.increment("historicalOrUnscopedPrices"); unresolved = true; }
            if (!dimensionsKnown) { context.increment("priceDimensionPending"); unresolved = true; }
            String subject = subjectKey(context.spec.subjectSelector());
            List<SourceRef> sourceRefs = refs(context, List.of(fact.evidence()));
            context.add(new Observation.PriceObservation(
                    stableObservationId("price", subject, String.join("|", field, outputLot, outputLayer,
                            outputTax, outputCurrency, outputUnit, price.amount.toPlainString()), sourceRefs), field,
                    subject, outputLot, outputLayer, outputTax,
                    outputCurrency, outputUnit, price.amount, current, state,
                    sourceRefs, observationConfidence(page, List.of(fact.evidence()))));
        }
        return new PriceScan(matchedFacts, unresolved);
    }

    private void personnelRoster(Context context) {
        String requestedRole = string(context.spec.parameters(), "role");
        List<PersonPage> pages = personPages(context);
        Map<PersonPage, List<PersonnelCandidate>> candidatesByPage = new LinkedHashMap<>();
        Map<String, Set<String>> strongIdsByName = new LinkedHashMap<>();
        Map<String, Integer> currentRecordsByName = new LinkedHashMap<>();
        Map<String, Integer> missingStrongIdByName = new LinkedHashMap<>();

        // Resolve identities over the whole current-project scope before emitting observations.
        // Otherwise one row with an ID and another row for the same name without it would become
        // two people merely because the source fields differ between pages.
        for (PersonPage personPage : pages) {
            Map<String, List<PersonCredentialFacts.Occurrence>> records = personPage.result.occurrences().stream()
                    .collect(java.util.stream.Collectors.groupingBy(PersonCredentialFacts.Occurrence::recordKey,
                            LinkedHashMap::new, java.util.stream.Collectors.toList()));
            List<PersonnelCandidate> candidates = new ArrayList<>();
            for (List<PersonCredentialFacts.Occurrence> record : records.values()) {
                Map<String, List<String>> fields = fields(record);
                String name = first(fields, "personName"), role = canonicalRole(first(fields, "role"));
                if (role.isBlank() && explicitRole(record.get(0).evidence().quote(), requestedRole))
                    role = canonicalRole(requestedRole);
                if (name.isBlank() || role.isBlank() || !sameRole(requestedRole, role)) continue;
                String employer = first(fields, "employer");
                String strongNumber = stableIdentityNumber(fields);
                boolean current = explicitCurrentAppointment(context, personPage.page, record, name, role);
                PersonnelCandidate candidate = new PersonnelCandidate(personPage, record, name, role,
                        employer, strongNumber, current);
                candidates.add(candidate);
                if (!current) continue;
                String normalizedName = normalize(name);
                currentRecordsByName.merge(normalizedName, 1, Integer::sum);
                if (strongNumber.isBlank()) missingStrongIdByName.merge(normalizedName, 1, Integer::sum);
                else strongIdsByName.computeIfAbsent(normalizedName, ignored -> new LinkedHashSet<>()).add(strongNumber);
            }
            candidatesByPage.put(personPage, List.copyOf(candidates));
        }

        Set<String> ambiguousNames = new LinkedHashSet<>();
        currentRecordsByName.forEach((name, count) -> {
            if (count > 1 && missingStrongIdByName.getOrDefault(name, 0) > 0) ambiguousNames.add(name);
        });
        if (!ambiguousNames.isEmpty()) {
            addCounter(context, "ambiguousPersonnelIdentityNames", ambiguousNames.size());
            context.note("PERSONNEL_IDENTITY_NOT_CLOSED",
                    "同名当前任职记录中存在缺少稳定身份标识的行；已防止重复计数，但名单人数边界保持未闭合");
        }

        Map<String, PersonnelBuilder> people = new LinkedHashMap<>();
        Set<String> closedRosterPeople = new LinkedHashSet<>();
        boolean rosterCandidate = false;

        for (PersonPage personPage : pages) {
            Set<String> pagePeople = new LinkedHashSet<>();
            boolean allRequestedRowsBound = true;
            for (PersonnelCandidate candidate : candidatesByPage.getOrDefault(personPage, List.of())) {
                List<PersonCredentialFacts.Occurrence> record = candidate.occurrences;
                String name = candidate.name, role = candidate.role;
                if (!candidate.currentAppointment) {
                    context.increment("nonCurrentPersonnelRecords");
                    allRequestedRowsBound = false;
                    continue;
                }
                String employer = candidate.employer;
                String strongNumber = candidate.strongNumber;
                String normalizedName = normalize(name);
                Set<String> knownStrongIds = strongIdsByName.getOrDefault(normalizedName, Set.of());
                if (strongNumber.isBlank() && knownStrongIds.size() > 1) {
                    // The row cannot be assigned to either same-name person without guessing.
                    context.increment("unboundSameNamePersonnelRows");
                    allRequestedRowsBound = false;
                    continue;
                }
                String resolvedStrongNumber = strongNumber;
                if (resolvedStrongNumber.isBlank() && knownStrongIds.size() == 1)
                    resolvedStrongNumber = knownStrongIds.iterator().next();
                String key = personKey(name, employer, strongNumber, record.get(0).recordKey());
                if (!resolvedStrongNumber.isBlank())
                    key = personKey(name, employer, resolvedStrongNumber, record.get(0).recordKey());
                String stableKey = key;
                PersonnelBuilder builder = people.computeIfAbsent(stableKey,
                        ignored -> new PersonnelBuilder(stableKey, name));
                Map<String, List<String>> recordFields = fields(record);
                builder.roles.add(role);
                builder.employer = mergeDimension(builder.employer, employer);
                builder.consortiumRole = mergeDimension(builder.consortiumRole,
                        consortiumRole(personPage.page.text(), employer));
                builder.mergeAttribute("certificateLevel", first(recordFields, "certificateLevel"));
                String registeredEmployer = first(recordFields, "registeredEmployer");
                if (registeredEmployer.isBlank() && !first(recordFields, "certificateName").isBlank()
                        && !certificateNumber(recordFields).isBlank())
                    registeredEmployer = first(recordFields, "employer");
                builder.mergeAttribute("registeredEmployer", registeredEmployer);
                builder.addAllSources(refs(context, record.stream().map(PersonCredentialFacts.Occurrence::evidence).toList()));
                builder.evidence.addAll(record.stream().map(PersonCredentialFacts.Occurrence::evidence).toList());
                builder.confidences.add(observationConfidence(personPage.page,
                        record.stream().map(PersonCredentialFacts.Occurrence::evidence).toList()));
                builder.identityBasis = !strongNumber.isBlank() ? "STRONG_IDENTIFIER"
                        : !resolvedStrongNumber.isBlank() ? "NAME_LINKED_TO_SINGLE_STRONG_IDENTIFIER" : "NORMALIZED_NAME";
                if (ambiguousNames.contains(normalizedName)) allRequestedRowsBound = false;
                pagePeople.add(key);
            }

            if (completeRosterPage(personPage, requestedRole) && allRequestedRowsBound && !pagePeople.isEmpty()) {
                rosterCandidate = true;
                closedRosterPeople.addAll(pagePeople);
            }
        }

        for (PersonnelBuilder person : people.values()) {
            Map<String, String> attributes = new LinkedHashMap<>(person.attributes);
            attributes.put("identityBasis", person.identityBasis);
            attributes.put("assemblerVersion", VERSION);
            List<SourceRef> sourceRefs = List.copyOf(person.sources.values());
            String roles = person.roles.stream().sorted().collect(java.util.stream.Collectors.joining("|"));
            context.add(new Observation.PersonnelObservation(
                    stableObservationId("personnel", person.key,
                            roles + "|" + person.employer + "|" + person.consortiumRole, sourceRefs),
                    person.key, person.name, person.roles, person.employer, person.consortiumRole,
                    true, true, Map.copyOf(attributes), TemplateTypes.ObservationState.SUPPORTED,
                    sourceRefs, person.confidences.stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0)));
        }

        Set<String> allRequestedPeople = new LinkedHashSet<>(people.keySet());
        if (rosterCandidate && ambiguousNames.isEmpty()
                && closedRosterPeople.equals(allRequestedPeople) && context.documentComplete) {
            context.closed.add(context.spec.id() + ":roster-scope");
        } else if (rosterCandidate) {
            context.note("ROSTER_SCOPE_NOT_CLOSED", "发现名单结构，但文档覆盖或名单边界未能证明包含该岗位全部当前任职人员");
        }
    }

    private MaterialScan scanMaterial(Context context, String materialType, Set<String> points, boolean requireBody) {
        MaterialAliasProfile aliasProfile = MATERIAL_ALIAS_PROFILES.get(normalize(materialType));
        if (aliasProfile != null && points.isEmpty())
            return scanAliasedCompositionMaterial(context, materialType, aliasProfile, requireBody);
        List<Observation.MaterialObservation> observations = new ArrayList<>();
        if (materialType.isBlank()) return new MaterialScan(observations, false, false);
        boolean mentionFound = false;
        for (Page page : sortedPages(context.bid)) {
            String text = Objects.toString(page.text(), "");
            List<String> lines = text.lines().toList();
            if (lines.isEmpty()) continue;
            boolean indexPage = indexPage(text);
            for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
                String line = lines.get(lineNumber);
                if (!containsNormalized(line, materialType)) continue;
                mentionFound = true;
                boolean index = indexPage || indexLine(line);
                boolean heading = !index && heading(line, materialType);
                String section = heading ? section(lines, lineNumber) : line;
                boolean substantive = !index && (heading && substantiveBody(section, materialType)
                        || !heading && embeddedMaterialBody(line, materialType));
                boolean titleOnly = !substantive;
                String quote = trimQuote(heading ? section : line);
                Evidence evidence = new Evidence(context.bid.id(), context.bid.name(), page.number(), page.locationType(),
                        quote, "template-material:" + safe(materialType) + ":line:" + (lineNumber + 1),
                        parserConfidence(page));
                boolean current = currentEvidence(context, evidence, page, false)
                        || !historicalAt(page.text(), line) && !index && currentMaterialContext(page.text());
                Set<String> matchedPoints = new LinkedHashSet<>();
                if (substantive) for (String point : points) if (containsNormalized(section, point)) matchedPoints.add(point);
                TemplateTypes.ObservationState state = substantive
                        ? TemplateTypes.ObservationState.SUPPORTED : TemplateTypes.ObservationState.UNRESOLVED;
                List<SourceRef> refs = refs(context, List.of(evidence));
                String subject = subjectKey(context.spec.subjectSelector());
                String pointScope = matchedPoints.stream().sorted()
                        .collect(java.util.stream.Collectors.joining("|"));
                observations.add(new Observation.MaterialObservation(
                        stableObservationId("material", subject,
                                materialType + "|" + pointScope + "|" + substantive + "|" + titleOnly, refs),
                        materialType, Set.of(), subject, current,
                        substantive || !requireBody && !index, titleOnly, matchedPoints, state, refs,
                        confidence(evidence)));
            }
        }
        boolean hasBody = observations.stream().anyMatch(observation -> observation.currentScope()
                && observation.state() == TemplateTypes.ObservationState.SUPPORTED
                && (!requireBody || observation.bodyPresent() && !observation.titleOnly()));
        if (mentionFound && !hasBody) context.increment("titleOrIndexOnlyMaterials");
        return new MaterialScan(List.copyOf(observations), mentionFound, hasBody);
    }

    /**
     * Resolves the small set of tender composition names whose submitted titles are conventionally
     * different.  A match is admitted only when its document structure also proves the expected
     * volume: directory text is never evidence and a similarly named historical attachment cannot
     * borrow the current bid's cover.
     */
    private MaterialScan scanAliasedCompositionMaterial(Context context, String materialType,
                                                         MaterialAliasProfile profile, boolean requireBody) {
        List<Page> pages = sortedPages(context.bid);
        VolumeScope volumeScope = volumeScope(pages);
        List<Observation.MaterialObservation> observations = new ArrayList<>();
        boolean mentionFound = false;
        for (Page page : pages) {
            String text = Objects.toString(page.text(), "");
            if (profile.aliases.stream().noneMatch(alias -> containsNormalized(text, alias))) continue;
            mentionFound = true;
            if (indexPage(text) || example(text)) continue;
            // A real assessment index routinely has rows such as “施工业绩情况”.  Those row
            // labels do not make the index historical; its enclosing current volume supplies the
            // scope.  Covers and proposal bodies retain the stricter whole-page history veto.
            if (!Set.of(MaterialAliasKind.BUSINESS_INDEX, MaterialAliasKind.TECHNICAL_INDEX).contains(profile.kind)
                    && HISTORY.matcher(cleanMarkup(text)).find()) continue;

            MaterialVolume requiredVolume = profile.kind.volume();
            boolean inVolume = volumeScope.contains(page, requiredVolume);
            String matchedAlias = profile.aliases.stream()
                    .filter(alias -> hasAliasHeading(text, alias)).findFirst().orElse("");
            if (matchedAlias.isBlank()) continue;

            boolean substantive = switch (profile.kind) {
                case BUSINESS_VOLUME, TECHNICAL_VOLUME -> inVolume
                        && volumeScope.isCover(page, requiredVolume) && volumeCoverBody(text);
                case BUSINESS_INDEX, TECHNICAL_INDEX -> inVolume && indexTableBody(text);
                case TECHNICAL_PROPOSAL -> inVolume && proposalBody(text, matchedAlias);
            };
            String quote = trimQuote(text);
            Evidence evidence = new Evidence(context.bid.id(), context.bid.name(), page.number(), page.locationType(),
                    quote, "template-composition:" + profile.kind.name().toLowerCase(Locale.ROOT), parserConfidence(page));
            List<SourceRef> sourceRefs = refs(context, List.of(evidence));
            Set<String> aliases = new LinkedHashSet<>(profile.aliases);
            aliases.removeIf(alias -> same(alias, materialType));
            observations.add(new Observation.MaterialObservation(
                    stableObservationId("composition-material", subjectKey(context.spec.subjectSelector()),
                            materialType + "|" + profile.kind + "|" + substantive, sourceRefs),
                    materialType, Set.copyOf(aliases), subjectKey(context.spec.subjectSelector()), inVolume,
                    substantive || !requireBody, !substantive, Set.of(),
                    substantive ? TemplateTypes.ObservationState.SUPPORTED : TemplateTypes.ObservationState.UNRESOLVED,
                    sourceRefs, confidence(evidence)));
        }
        boolean bodyFound = observations.stream().anyMatch(observation -> observation.currentScope()
                && observation.state() == TemplateTypes.ObservationState.SUPPORTED
                && (!requireBody || observation.bodyPresent() && !observation.titleOnly()));
        if (mentionFound && !bodyFound) context.increment("titleOrIndexOnlyMaterials");
        if (bodyFound) context.increment("aliasedCompositionMaterials");
        return new MaterialScan(List.copyOf(observations), mentionFound, bodyFound);
    }

    private static VolumeScope volumeScope(List<Page> pages) {
        List<VolumeCover> covers = new ArrayList<>();
        for (Page page : pages) {
            String text = Objects.toString(page.text(), "");
            MaterialVolume volume = volumeCoverType(text);
            if (volume == MaterialVolume.NONE || indexPage(text) || example(text)
                    || HISTORY.matcher(cleanMarkup(text)).find() || !volumeCoverBody(text)) continue;
            CoverIdentity identity = coverIdentity(text);
            if (identity.complete()) covers.add(new VolumeCover(pageKey(page), volume, identity));
        }
        if (covers.isEmpty()) return VolumeScope.empty();
        CoverIdentity root = covers.get(0).identity;
        Map<String, MaterialVolume> currentCovers = new LinkedHashMap<>();
        for (VolumeCover cover : covers) {
            if (root.sameBid(cover.identity)) currentCovers.put(cover.pageKey, cover.volume);
        }
        Map<String, MaterialVolume> pagesByVolume = new LinkedHashMap<>();
        MaterialVolume active = MaterialVolume.NONE;
        for (Page page : pages) {
            MaterialVolume current = currentCovers.get(pageKey(page));
            if (current != null) active = current;
            else if (foreignVolumeBoundary(page, covers, root) || quotationVolumeBoundary(page)) active = MaterialVolume.NONE;
            if (active != MaterialVolume.NONE) pagesByVolume.put(pageKey(page), active);
        }
        return new VolumeScope(Map.copyOf(pagesByVolume), Map.copyOf(currentCovers));
    }

    private static boolean foreignVolumeBoundary(Page page, List<VolumeCover> covers, CoverIdentity root) {
        String key = pageKey(page);
        return covers.stream().anyMatch(cover -> cover.pageKey.equals(key) && !root.sameBid(cover.identity));
    }

    private static boolean quotationVolumeBoundary(Page page) {
        String text = Objects.toString(page.text(), "");
        return text.lines().limit(8).map(TemplateEvidenceAssembler::normalize).anyMatch(line ->
                line.equals(normalize("第三分册 报价文件")) || line.equals(normalize("投标文件（报价部分）"))
                        || line.equals(normalize("报价文件封面")));
    }

    private static MaterialVolume volumeCoverType(String text) {
        if (hasAliasHeading(text, "投标文件（商务部分）") || hasAliasHeading(text, "商务投标文件"))
            return MaterialVolume.BUSINESS;
        if (hasAliasHeading(text, "投标文件（技术部分）") || hasAliasHeading(text, "技术投标文件"))
            return MaterialVolume.TECHNICAL;
        return MaterialVolume.NONE;
    }

    private static boolean volumeCoverBody(String text) {
        String value = cleanMarkup(text);
        return value.matches("(?s).*(?:招标项目编号|招标编号|项目编号)\\s*[:：]?.*[A-Za-z0-9].*")
                && value.matches("(?s).*投标人(?:名称)?\\s*[:：]?.{4,}.*");
    }

    private static CoverIdentity coverIdentity(String text) {
        Matcher number = Pattern.compile("(?:招标项目编号|招标编号|项目编号)\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9._\\-/]{2,80})")
                .matcher(cleanMarkup(text));
        Matcher bidder = Pattern.compile("投标人(?:名称)?\\s*[:：]?\\s*(?:[（(]盖章[）)]\\s*)?([^\\r\\n]{4,100})")
                .matcher(cleanMarkup(text));
        return new CoverIdentity(number.find() ? normalize(number.group(1)) : "",
                bidder.find() ? normalize(bidder.group(1).replaceFirst("(?:法定代表人|日期).*$", "")) : "");
    }

    private static boolean hasAliasHeading(String text, String alias) {
        return Objects.toString(text, "").lines().limit(12).anyMatch(line -> {
            String value = normalize(line.replaceFirst(
                    "^[ \\t]*(?:#{1,6}[ \\t]*|第?[一二三四五六七八九十0-9]+(?:[.、．]|[）)]))[ \\t]*", ""));
            String expected = normalize(alias);
            if (value.equals(expected)) return true;
            // Only the conventional parenthetical qualification following an index title is
            // accepted; arbitrary longer strings remain ordinary mentions rather than headings.
            return value.startsWith(expected) && value.substring(expected.length())
                    .matches("(?:适用)?(?:综合评估法|综合评分法)");
        });
    }

    private static boolean indexTableBody(String text) {
        String value = cleanMarkup(text);
        if (!(value.contains("条款号") && value.contains("评审因素")
                && (value.contains("对应页码") || value.contains("投标文件页码")))) return false;
        long rows = Objects.toString(text, "").lines().map(String::strip)
                .filter(line -> line.matches("^[0-9]{1,3}(?:[.、．][0-9]{1,3})?[ \\t|]+.{1,120}(?:[Pp][ \\t]*[0-9]+|/[ \\t|]+|满足要求).*$"))
                .count();
        return rows >= 2;
    }

    private static boolean proposalBody(String text, String alias) {
        List<String> lines = Objects.toString(text, "").lines().toList();
        for (int index = 0; index < Math.min(lines.size(), 12); index++) {
            if (!hasAliasHeading(lines.get(index), alias)) continue;
            // “技术建议书” is a volume-internal root.  Its first real content normally begins at
            // “4.1/第一章”, which the generic leaf-section cutter treats as a boundary.  Evaluate
            // the bounded remainder of this non-directory page so child chapters can prove that
            // the root document has body, while still requiring actual prose rather than titles.
            int end = Math.min(lines.size(), index + 50);
            return substantiveBody(String.join("\n", lines.subList(index, end)), alias);
        }
        return false;
    }

    private static String pageKey(Page page) { return page.number() + "|" + page.locationType(); }

    private void closeMaterialNeed(Context context, String suffix, MaterialScan scan) {
        if (context.documentComplete) context.closed.add(context.spec.id() + ":" + suffix);
        else context.note("DOCUMENT_SCOPE_OPEN", "文档仍有未完成页面，未用未检出材料形成缺失结论");
    }

    private List<TrustedSeal> trustedSeals(Context context, String materialType, MaterialScan scan) {
        List<TrustedSeal> result = new ArrayList<>();
        Set<Integer> materialPages = scan.observations.stream().flatMap(value -> value.sources().stream())
                .map(SourceRef::location).collect(java.util.stream.Collectors.toSet());
        String requestedLocation = string(context.spec.parameters(), "sealLocation");
        for (Fact fact : context.facts) {
            if (!TRUSTED_SEAL_ENTITIES.contains(upper(fact.entityType())) || !TRUSTED_SEAL_FIELDS.contains(fact.field())) continue;
            String state = upper(fact.normalizedValue());
            Boolean observed = switch (state) {
                case "TRUE", "OBSERVED", "VISIBLE", "CLEAR", "PRESENT", "SEAL" -> true;
                case "FALSE", "NOT_OBSERVED", "ABSENT", "BLANK" -> false;
                default -> null;
            };
            if (observed == null || fact.evidence() == null) continue;
            boolean locationMatches = materialPages.contains(fact.evidence().page())
                    || containsNormalized(fact.evidence().quote(), materialType);
            if (!requestedLocation.isBlank()) locationMatches &= containsNormalized(fact.evidence().quote(), requestedLocation)
                    || containsNormalized(fact.rawValue(), requestedLocation);
            if (!locationMatches) { context.increment("unboundSealObservations"); continue; }
            Page page = page(context.bid, fact.evidence());
            if (page == null) { context.increment("unboundSealObservations"); continue; }
            boolean current = currentEvidence(context, fact.evidence(), page, false)
                    || page != null && !historicalAt(page.text(), fact.evidence().quote());
            result.add(new TrustedSeal(observed, requestedLocation.isBlank() ? "要求位置" : requestedLocation,
                    current, fact.evidence(), refs(context, List.of(fact.evidence()))));
        }
        return List.copyOf(result);
    }

    private List<PersonPage> personPages(Context context) {
        List<PersonPage> result = new ArrayList<>();
        for (Page page : sortedPages(context.bid)) {
            PersonCredentialFacts.Result parsed = PersonCredentialFacts.analyzeForConsistency(context.bid, page);
            if (parsed.candidate()) result.add(new PersonPage(page, parsed));
        }
        return List.copyOf(result);
    }

    private boolean explicitCurrentAppointment(Context context, Page page,
                                               List<PersonCredentialFacts.Occurrence> record,
                                               String name, String role) {
        String quote = record.get(0).evidence().quote();
        String plain = beforeHistory(cleanMarkup(quote));
        if (plain.isBlank() || example(plain) || historicalAt(page.text(), quote)) return false;
        String canonical = canonicalRole(role);
        boolean local = plain.contains(name) && (
                Pattern.compile("拟在本合同任职[ \\t]*[:：]?[ \\t]*" + Pattern.quote(canonical)).matcher(plain).find()
                        || Pattern.compile("(?:拟任职务|本项目职务|在本项目中担任职务|担任职务)[ \\t]*[:：]?[ \\t]*"
                        + Pattern.quote(canonical)).matcher(plain).find()
                        || Pattern.compile("(?:本项目|本工程|本次投标)[^。；;\\n]{0,80}" + Pattern.quote(canonical)
                        + "[ \\t]*[:：]?[ \\t]*" + Pattern.quote(name)).matcher(plain).find());
        if (local) return true;

        int start = integer(record.get(0).context().get("recordStart"), 0);
        String before = Objects.toString(page.text(), "").substring(0,
                Math.max(0, Math.min(start, Objects.toString(page.text(), "").length())));
        int history = lastIndex(before, "主要工作经历", "工作履历", "历史项目", "类似项目业绩", "合同协议书");
        int current = lastIndex(before, "本项目人员配置", "本项目人员安排", "拟投入本项目人员", "项目管理机构", "人员配备表");
        boolean currentTable = current >= 0 && current > history && structuredPage(page)
                && containsNormalized(page.text(), canonical) && containsNormalized(quote, name);
        // The explicit current-project roster heading and validated row structure bind the row even
        // when a detached test/import lacks enough cover pages for cross-page project reconstruction.
        return currentTable;
    }

    private boolean completeRosterPage(PersonPage personPage, String role) {
        String text = Objects.toString(personPage.page.text(), "");
        if (!personPage.result.complete() || !structuredPage(personPage.page)) return false;
        boolean explicit = text.matches("(?s).*(?:本项目人员(?:配置|安排)(?:表|名单)?|拟投入本项目人员(?:配置|名单|一览表)?|"
                + "本项目" + Pattern.quote(role) + "(?:完整名单|全部人员名单|全部名单)).*");
        if (!explicit || exampleOrHistorical(text)) return false;
        Map<String, List<PersonCredentialFacts.Occurrence>> records = personPage.result.occurrences().stream()
                .collect(java.util.stream.Collectors.groupingBy(PersonCredentialFacts.Occurrence::recordKey));
        List<Map<String, List<String>>> rows = records.values().stream().map(TemplateEvidenceAssembler::fields).toList();
        List<Map<String, List<String>>> requested = rows.stream()
                .filter(row -> sameRole(role, canonicalRole(first(row, "role")))).toList();
        return !requested.isEmpty() && requested.stream().allMatch(row -> !first(row, "personName").isBlank());
    }

    private boolean currentEvidence(Context context, Evidence evidence, Page page, boolean personnelOrCertificate) {
        if (evidence == null || page == null || !context.bid.id().equals(evidence.documentId())) return false;
        Map<String, Object> proof = context.scope.currentEvidence(evidence);
        if (Boolean.TRUE.equals(proof.get("verifiedCurrent"))) return true;
        String quote = Objects.toString(evidence.quote(), "");
        if (indexPage(page.text()) || historicalAt(page.text(), quote)) return false;
        // A local, explicit current-project statement is usable even if an otherwise valid test or
        // imported document lacks the outer cover needed for cross-page scope reconstruction.
        return CURRENT.matcher(cleanMarkup(quote)).find()
                || !personnelOrCertificate && CURRENT.matcher(cleanMarkup(page.text())).find();
    }

    private static boolean historicalAt(String pageText, String quote) {
        String text = cleanMarkup(Objects.toString(pageText, ""));
        int at = quote == null || quote.isBlank() ? -1 : text.indexOf(cleanMarkup(quote));
        if (at < 0) at = text.length();
        String before = text.substring(0, at);
        int history = lastIndex(before, "历史业绩", "历史项目", "类似项目业绩", "主要工作经历", "工作履历", "合同协议书");
        int current = lastIndex(before, "本项目", "本工程", "本次投标", "拟投入", "拟派", "拟在本合同任职", "投标函", "开标一览表");
        return history >= 0 && history > current || HISTORY.matcher(cleanMarkup(quote)).find();
    }

    private static boolean currentMaterialContext(String text) {
        return CURRENT.matcher(cleanMarkup(text)).find() && !indexPage(text);
    }

    private static boolean strongCurrentPrice(String quote) {
        String value = cleanMarkup(Objects.toString(quote, ""));
        return value.matches("(?s).*(?:投标总报价|投标总价|本项目报价|报价总额|开标一览表).*")
                && !HISTORY.matcher(value).find();
    }

    private static boolean containsSealWords(Document document) {
        return sortedPages(document).stream().map(Page::text).filter(Objects::nonNull)
                .anyMatch(text -> text.matches("(?s).*(?:盖章|签章|公章|印章|签字|签名).*"));
    }

    private static boolean sameSourcePage(List<SourceRef> left, List<SourceRef> right) {
        return left.stream().anyMatch(a -> right.stream().anyMatch(b -> a.documentId().equals(b.documentId())
                && a.locationType().equals(b.locationType()) && a.location() == b.location()));
    }

    private static Map<String, List<String>> fields(List<PersonCredentialFacts.Occurrence> record) {
        Map<String, LinkedHashSet<String>> values = new LinkedHashMap<>();
        for (PersonCredentialFacts.Occurrence occurrence : record) {
            String value = Objects.toString(occurrence.value(), "").strip();
            if (!value.isBlank()) values.computeIfAbsent(occurrence.field(), ignored -> new LinkedHashSet<>()).add(value);
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return result;
    }

    private static DateRange dateRange(Map<String, List<String>> fields) {
        Set<LocalDate> from = dates(fields.getOrDefault("validFrom", List.of()));
        Set<LocalDate> until = dates(fields.getOrDefault("validUntil", List.of()));
        boolean longTerm = fields.getOrDefault("validUntil", List.of()).stream().anyMatch(TemplateEvidenceAssembler::longTerm)
                || fields.getOrDefault("validPeriod", List.of()).stream().anyMatch(TemplateEvidenceAssembler::longTerm);
        for (String period : fields.getOrDefault("validPeriod", List.of())) {
            Set<LocalDate> values = dates(List.of(period));
            if (values.size() == 1) until.addAll(values);
            else if (values.size() == 2) {
                List<LocalDate> ordered = values.stream().sorted().toList();
                from.add(ordered.get(0)); until.add(ordered.get(1));
            }
        }
        if (longTerm) until.add(LocalDate.of(9999, 12, 31));
        return new DateRange(from.size() == 1 ? from.iterator().next() : null,
                until.size() == 1 ? until.iterator().next() : null, longTerm,
                from.size() > 1 || until.size() > 1);
    }

    private static Set<LocalDate> dates(Collection<String> values) {
        Set<LocalDate> result = new LinkedHashSet<>();
        for (String value : values) {
            Matcher matcher = DATE.matcher(value);
            while (matcher.find()) try {
                result.add(LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))));
            } catch (RuntimeException ignored) { }
        }
        return result;
    }

    private static boolean longTerm(String value) {
        return value != null && value.matches("(?s).*(?:长期|永久|无固定期限).*" );
    }

    private static String certificateNumber(Map<String, List<String>> fields) {
        for (String field : CERTIFICATE_NUMBER_FIELDS) {
            String value = first(fields, field);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static boolean certificateTypeMatches(String expected, String actual) {
        if (expected.isBlank() || actual.isBlank()) return false;
        if ("ANY_CERTIFICATE".equalsIgnoreCase(expected)) return !Set.of("UNKNOWN", "PERSON_ROSTER").contains(actual);
        String left = normalize(expected), right = normalize(actual);
        return left.equals(right) || left.length() >= 4 && right.contains(left) || right.length() >= 4 && left.contains(right);
    }

    private static String certificateTypeInQuote(String expected, String quote) {
        if (expected.isBlank() || "ANY_CERTIFICATE".equalsIgnoreCase(expected)) {
            Matcher matcher = Pattern.compile("([\\p{IsHan}A-Za-z0-9]{2,40}(?:资格证书|执业证书|注册证书|职称证书|许可证|驾驶证|身份证|护照))").matcher(quote);
            return matcher.find() ? matcher.group(1) : "";
        }
        return containsNormalized(quote, expected) ? expected : "";
    }

    private static List<Fact> factsFor(Context context, Document document) {
        LinkedHashMap<String, Fact> result = new LinkedHashMap<>();
        if (document != null && document.id().equals(context.bid.id())) {
            context.facts.forEach(fact -> result.putIfAbsent(factIdentity(fact), fact));
        }
        if (document != null) {
            try {
                new FactExtractor().extract(document).forEach(fact -> result.putIfAbsent(factIdentity(fact), fact));
            } catch (RuntimeException ignored) {
                // Existing admitted facts remain usable; a local fallback extraction failure never
                // becomes a synthetic negative observation.
            }
        }
        return List.copyOf(result.values());
    }

    private static DurationValue durationValue(Fact fact) {
        String normalized = Objects.toString(fact.normalizedValue(), "").strip();
        String raw = Objects.toString(fact.rawValue(), "");
        String prefix = "", numeric = normalized;
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":", 2);
            prefix = upper(parts[0]); numeric = parts[1];
        }
        try {
            BigDecimal amount = new BigDecimal(numeric.replace(",", ""));
            if ("WORKDAY".equals(prefix)) return new DurationValue(amount, "工作日", "WORKDAY");
            if ("MONTH".equals(prefix)) return new DurationValue(amount, "月", "CALENDAR_MONTH");
            if ("YEAR".equals(prefix)) return new DurationValue(amount, "年", "CALENDAR_YEAR");
            Matcher unit = DURATION_VALUE.matcher(raw);
            if (unit.find()) return durationValue(amount, unit.group(2));
            // A plain numeric normalization does not prove its unit or calendar basis.  Keep both
            // unknown so the requirement's requested basis cannot be copied into an observation.
            return new DurationValue(amount, "", "");
        } catch (NumberFormatException invalid) {
            Matcher matcher = DURATION_VALUE.matcher(raw);
            if (!matcher.find()) return null;
            return durationValue(new BigDecimal(matcher.group(1)), matcher.group(2));
        }
    }

    private static DurationValue durationValue(BigDecimal amount, String unit) {
        String canonical = canonicalDurationUnit(unit);
        return switch (canonical) {
            case "WORKDAY" -> new DurationValue(amount, "工作日", "WORKDAY");
            case "MONTH" -> new DurationValue(amount, "月", "CALENDAR_MONTH");
            case "YEAR" -> new DurationValue(amount, "年", "CALENDAR_YEAR");
            default -> new DurationValue(amount, "日", "CALENDAR_DAY");
        };
    }

    private static String durationStartEvent(String text) {
        String value = Objects.toString(text, "");
        for (String event : List.of("合同签订之日", "合同签订后", "开工令发出之日", "开工之日",
                "中标通知书发出之日", "收到订单之日", "投标截止之日")) {
            if (value.contains(event)) return event;
        }
        return "";
    }

    private static boolean durationUnitEquivalent(String left, String right) {
        return canonicalDurationUnit(left).equals(canonicalDurationUnit(right));
    }

    private static String canonicalDurationUnit(String value) {
        String normalized = normalize(value);
        if (normalized.contains("工作日")) return "WORKDAY";
        if (normalized.equals("月") || normalized.equals("个月") || normalized.contains("月份")) return "MONTH";
        if (normalized.equals("年") || normalized.contains("年度")) return "YEAR";
        if (normalized.equals("日") || normalized.equals("天") || normalized.contains("日历日")) return "DAY";
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static List<FieldValue> fieldValues(Context context, Document document, String field) {
        LinkedHashMap<String, FieldValue> values = new LinkedHashMap<>();
        for (Fact fact : factsFor(context, document)) {
            if (!same(field, fact.field()) || fact.evidence() == null) continue;
            String value = Objects.toString(fact.normalizedValue(), "").strip();
            if (value.isBlank() || "UNRESOLVED".equalsIgnoreCase(value)) value = Objects.toString(fact.rawValue(), "").strip();
            if (!value.isBlank()) values.putIfAbsent(evidenceKey(fact.evidence()) + "|" + normalize(value),
                    new FieldValue(value, fact.evidence()));
        }
        for (Page page : sortedPages(document)) for (String label : fieldLabels(field)) {
            Matcher matcher = Pattern.compile("(?m)(?:^|[|\\t])\\s*" + Pattern.quote(label)
                    + "\\s*[:：=]\\s*([^|\\t;；。\\r\\n]{1,200})").matcher(Objects.toString(page.text(), ""));
            while (matcher.find()) {
                String value = matcher.group(1).strip();
                if (value.isBlank() || PLACEHOLDER.matcher(value).matches()) continue;
                Evidence evidence = new Evidence(document.id(), document.name(), page.number(), page.locationType(),
                        matcher.group().strip(), "template-field:" + safe(field) + ":" + matcher.start(), parserConfidence(page));
                values.putIfAbsent(evidenceKey(evidence) + "|" + normalize(value), new FieldValue(value, evidence));
            }
        }
        for (Page page : sortedPages(document)) {
            LabelValue structured = labelledField(page.text(), field);
            if (!structured.present || structured.value.isBlank() || PLACEHOLDER.matcher(structured.value).matches()) continue;
            Evidence evidence = new Evidence(document.id(), document.name(), page.number(), page.locationType(),
                    structured.value, "template-field-structured:" + safe(field), parserConfidence(page));
            values.putIfAbsent(evidenceKey(evidence) + "|" + normalize(structured.value),
                    new FieldValue(structured.value, evidence));
        }
        return List.copyOf(values.values());
    }

    private static String primaryCompanyValue(Context context) {
        return sortedPages(context.bid).stream()
                .filter(page -> page.number() <= 3 || Objects.toString(page.text(), "").contains("投标文件封面"))
                .map(page -> labelledField(page.text(), "companyName"))
                .filter(LabelValue::present).map(LabelValue::value)
                .filter(value -> !value.isBlank() && !PLACEHOLDER.matcher(value).matches())
                .findFirst().orElse("");
    }

    private static String consistencyParty(String field, Document document, Page page,
                                           String value, String primaryCompany) {
        if (!bidInternalConsistencyField(field)) return "PROJECT";
        if (page == null || !"BID".equalsIgnoreCase(document.role())) return "";
        if (!primaryCompany.isBlank() && sameCompanyValue(primaryCompany, value)) return "PRIMARY";
        String text = Objects.toString(page.text(), "");
        if (text.matches("(?s).*(?:设计单位|设计成员|设计方).*") ) return "DESIGN_MEMBER";
        if (text.matches("(?s).*(?:施工单位|施工成员|施工方).*") ) return "CONSTRUCTION_MEMBER";
        return "";
    }

    private static String consistencySourceScope(Document document, Page page, String field) {
        if (page == null) return upper(document.role()) + ":UNBOUND";
        String text = Objects.toString(page.text(), "");
        String section;
        if (text.contains("投标文件封面")) section = "COVER";
        else if (text.contains("投标函")) section = "BID_LETTER";
        else if (text.contains("营业执照")) section = "BUSINESS_LICENSE";
        else if (text.contains("资质证书") || text.contains("资质证明")) section = "QUALIFICATION";
        else if (text.contains("法定代表人") && text.contains("身份证明")) section = "LEGAL_REPRESENTATIVE";
        else if (text.contains("授权委托")) section = "AUTHORIZATION";
        else if (text.contains("投标人基本情况")) section = "BIDDER_PROFILE";
        else if (text.contains("开票信息")) section = "INVOICE_FORM";
        else section = "FIELD_" + safe(field);
        return upper(document.role()) + ":" + section + ":" + page.locationType() + ":" + page.number();
    }

    private static boolean sameCompanyValue(String left, String right) {
        return canonicalCompanyValue(left).equals(canonicalCompanyValue(right));
    }

    static String canonicalCompanyValue(String raw) {
        return normalize(Objects.toString(raw, "")
                .replaceAll("(?i)\\\\(?:underline|text|mathrm|mathbf|operatorname)", "")
                .replaceAll("[{}\\\\]", "")
                .replaceAll("[（(]?\\s*(?:盖章|单位公章|公章)\\s*[）)]?", ""));
    }

    private static boolean fieldLocallySupported(String field) {
        return Set.of("companyName", "creditCode", "projectName", "projectNumber", "totalPrice",
                "duration", "designDuration", "constructionDuration", "installationDuration",
                "commissioningDuration", "bidValidity", "warranty", "documentDate", "date").contains(field);
    }

    private static List<String> fieldLabels(String field) {
        return switch (field) {
            case "companyName" -> List.of("投标人名称", "投标单位名称", "企业名称", "公司名称");
            case "creditCode" -> List.of("统一社会信用代码", "信用代码");
            case "projectName" -> List.of("项目名称", "工程名称", "招标项目名称");
            case "projectNumber" -> List.of("项目编号", "招标编号", "采购编号");
            case "totalPrice" -> List.of("投标总报价", "投标报价", "报价总额", "总报价", "投标总价");
            case "duration" -> List.of("总工期", "项目工期", "工期", "交货期", "服务期限");
            case "documentDate", "date" -> List.of("投标日期", "签署日期", "落款日期", "日期");
            default -> List.of(field);
        };
    }

    private static LabelValue labelledField(String text, String field) {
        for (String label : fieldLabels(field)) {
            Matcher table = Pattern.compile("(?m)\\|\\s*" + Pattern.quote(label)
                    + "\\s*\\|\\s*([^|\\r\\n]*)\\|").matcher(Objects.toString(text, ""));
            if (table.find()) return new LabelValue(true, table.group(1).strip());
            Matcher html = Pattern.compile("(?is)<t[dh][^>]*>\\s*" + Pattern.quote(label)
                    + "\\s*</t[dh]>\\s*<td[^>]*>(.*?)</td>").matcher(Objects.toString(text, ""));
            if (html.find()) return new LabelValue(true, html.group(1).replaceAll("<[^>]+>", " ").strip());
            Matcher matcher = Pattern.compile("(?m)(?:^|[|\\t])[ \\t]*" + Pattern.quote(label)
                    + "(?:[ \\t]*[:：=][ \\t]*|[ \\t]*(?:为|是)[ \\t]*|[ \\t]+)"
                    + "([^|\\t;；。\\r\\n]*)").matcher(Objects.toString(text, ""));
            if (!matcher.find()) continue;
            String value = matcher.group(1).strip();
            if (value.equals(label)) value = "";
            return new LabelValue(true, value);
        }
        String label = fieldLabels(field).stream().filter(value -> value.contains("、"))
                .findFirst().orElse("");
        if (!label.isBlank()) {
            int split = label.lastIndexOf('、');
            String prefix = label.substring(0, split), suffix = label.substring(split + 1);
            LabelValue first = lineValueAfterLabel(text, prefix);
            LabelValue second = lineValueAfterLabel(text, suffix);
            if (first.present() && second.present()) {
                String combined = String.join(" ", first.value(), second.value()).strip();
                return new LabelValue(true, combined);
            }
        }
        return new LabelValue(false, "");
    }

    private static LabelValue lineValueAfterLabel(String text, String label) {
        Matcher matcher = Pattern.compile("(?m)^[^\\r\\n]{0,80}?" + Pattern.quote(label)
                + "[ \\t]*[、,:：，]?[ \\t]*([^\\r\\n]*)$")
                .matcher(Objects.toString(text, ""));
        if (!matcher.find()) return new LabelValue(false, "");
        String value = matcher.group(1).strip().replaceFirst("^[、,:：，\\s]+", "");
        return new LabelValue(true, value);
    }

    private static boolean subjectField(String field) {
        return Set.of("投标人名称", "投标单位名称", "企业名称", "公司名称", "companyName", "subjectName")
                .stream().anyMatch(value -> same(value, field));
    }

    private static Evidence pageEvidence(Document document, Page page, String purpose) {
        String text = Objects.toString(page.text(), "").strip();
        String quote = text.length() <= 1000 ? text : text.substring(0, 1000);
        return new Evidence(document.id(), document.name(), page.number(), page.locationType(), quote,
                "template-page:" + safe(purpose) + ":" + page.number(), parserConfidence(page));
    }

    private List<DatedSource> datesForRole(Context context, String role) {
        Document document = dateDocumentForRole(context, role);
        if (document == null) return List.of();
        LinkedHashMap<String, DatedSource> result = directDatesForRole(context, document, role);
        if (result.isEmpty() && tenderDateRole(role)) {
            List<Evidence> linkEvidence = sameTimeDateLinkEvidence(document);
            if (!linkEvidence.isEmpty()) {
                String counterpart = normalizedDateRole(role).equals("OPENING")
                        ? "投标截止时间" : "开标时间";
                for (DatedSource source : directDatesForRole(context, document, counterpart).values()) {
                    List<Evidence> combined = new ArrayList<>(source.evidence());
                    combined.addAll(linkEvidence);
                    result.putIfAbsent(source.date() + "|linked:" + role,
                            new DatedSource(source.date(), List.copyOf(combined)));
                }
            }
        }
        return List.copyOf(result.values());
    }

    private LinkedHashMap<String, DatedSource> directDatesForRole(Context context, Document document, String role) {
        LinkedHashMap<String, DatedSource> result = new LinkedHashMap<>();
        for (Page page : sortedPages(document)) {
            String text = Objects.toString(page.text(), "");
            for (String label : dateRoleLabels(role)) {
                Pattern forward = Pattern.compile(Pattern.quote(label) + "[^\\r\\n。；;]{0,40}?" + DATE.pattern());
                Matcher matcher = forward.matcher(text);
                while (matcher.find()) {
                    LocalDate date = parseDate(matcher.group());
                    if (date == null) continue;
                    Evidence evidence = new Evidence(document.id(), document.name(), page.number(), page.locationType(),
                            matcher.group().strip(), "template-date:" + safe(role) + ":" + matcher.start(), parserConfidence(page));
                    boolean current = context.bid.id().equals(document.id())
                            ? currentEvidence(context, evidence, page, false)
                            : context.tender != null && context.tender.id().equals(document.id())
                            && "TENDER".equalsIgnoreCase(document.role()) && !indexPage(text)
                            && !example(text) && !historicalAt(text, matcher.group());
                    if (!current) {
                        context.increment("rejectedDateScopeValues");
                        continue;
                    }
                    result.putIfAbsent(evidenceKey(evidence) + "|" + date,
                            new DatedSource(date, List.of(evidence)));
                }
            }
        }
        return result;
    }

    private static Document dateDocumentForRole(Context context, String role) {
        if (tenderDateRole(role)) {
            if (context.tender != null && !context.tender.id().equals(context.bid.id())
                    && "TENDER".equalsIgnoreCase(context.tender.role())) return context.tender;
            return null;
        }
        return context.bid;
    }

    private static boolean tenderDateRole(String role) {
        String normalized = normalize(role);
        return normalized.contains("开标") || normalized.contains("投标截止")
                || normalized.contains("投标文件递交截止") || normalized.equals("截止时间");
    }

    private static String normalizedDateRole(String role) {
        String normalized = normalize(role);
        if (normalized.contains("开标")) return "OPENING";
        if (normalized.contains("投标截止") || normalized.contains("投标文件递交截止")
                || normalized.equals("截止时间")) return "BID_DEADLINE";
        return normalized;
    }

    private static List<String> dateRoleLabels(String role) {
        return switch (normalizedDateRole(role)) {
            case "OPENING" -> List.of("开标时间", "开标日期");
            case "BID_DEADLINE" -> List.of("投标截止时间", "投标文件递交截止时间", "递交截止时间");
            default -> List.of(role);
        };
    }

    private static List<Evidence> sameTimeDateLinkEvidence(Document document) {
        if (document == null || !"TENDER".equalsIgnoreCase(document.role())) return List.of();
        List<Evidence> result = new ArrayList<>();
        for (Page page : sortedPages(document)) {
            String text = Objects.toString(page.text(), "");
            if (indexPage(text) || example(text)) continue;
            Matcher matcher = Pattern.compile(
                    "开标时间(?:和地点)?[^\\r\\n。；;]{0,30}(?:同|与)[^\\r\\n。；;]{0,20}"
                            + "(?:投标文件递交截止时间|投标截止时间)"
                            + "|开标时间[^\\r\\n。；;]{0,20}投标截止时间[^\\r\\n。；;]{0,20}同一时间")
                    .matcher(text);
            while (matcher.find()) result.add(new Evidence(document.id(), document.name(), page.number(),
                    page.locationType(), matcher.group().strip(), "template-date-same-time:" + matcher.start(),
                    parserConfidence(page)));
        }
        return List.copyOf(result);
    }

    private static BigDecimal scoreValue(String normalized, String raw) {
        try { return new BigDecimal(Objects.toString(normalized, "").replace(",", "").strip()); }
        catch (NumberFormatException ignored) { }
        Matcher matcher = Pattern.compile("(?:得分|评分|计分|获得|得)\\s*[:：=]?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*分")
                .matcher(Objects.toString(raw, ""));
        if (!matcher.find()) return null;
        return new BigDecimal(matcher.group(1));
    }

    private static boolean scoreScopeMarker(Fact fact, String criterion) {
        String entity = upper(fact.entityType()), field = Objects.toString(fact.field(), "");
        String value = upper(fact.normalizedValue());
        return Set.of("SCORE_SCOPE", "SCORING_SCOPE").contains(entity)
                && (same(field, criterion) || same(field, criterion + ".scope") || same(field, "scoreScope"))
                && Set.of("CLOSED", "COMPLETE", "ZERO_CLOSED", "已闭合", "完整").contains(value);
    }

    private static boolean textScanScopeMarker(Fact fact, String anomalyType, String scope) {
        String entity = upper(fact.entityType());
        String value = upper(fact.normalizedValue());
        if (!Set.of("TEXT_ANOMALY_SCOPE", "TEXT_SCAN_SCOPE").contains(entity)
                || !same(fact.field(), anomalyType)
                || !Set.of("CLOSED", "COMPLETE", "CLEAR", "已闭合", "完整", "未发现异常").contains(value))
            return false;
        String markerScope = Objects.toString(fact.entityKey(), "").strip();
        return markerScope.isBlank() || scope.isBlank() || same(markerScope, scope)
                || (List.of("全文", "全部", "整份").stream().anyMatch(scope::contains)
                && List.of("全文", "全部", "整份", "DOCUMENT").stream()
                .anyMatch(valueScope -> same(markerScope, valueScope)));
    }

    private static boolean wholeDocumentScope(String scope) {
        return scope == null || scope.isBlank()
                || List.of("全文", "全部", "整份").stream().anyMatch(scope::contains);
    }

    private static ScoreSummary explicitScoreSummary(String text, String criterion, boolean fieldAlreadyBound) {
        String value = Objects.toString(text, "");
        if (value.isBlank()) return null;
        boolean criterionBound = fieldAlreadyBound || criterion.isBlank() || containsNormalized(value, criterion);
        if (!criterionBound) return null;
        Matcher matcher = Pattern.compile(
                "(?:最终得分|实际得分|核验得分|本项得分|客观评分得分|合计得分|总得分)"
                        + "\\s*[:：=为]?\\s*(零|[0-9]+(?:\\.[0-9]+)?)\\s*分")
                .matcher(value);
        while (matcher.find()) {
            String prefix = value.substring(Math.max(0, matcher.start() - 12), matcher.start());
            if (prefix.matches("(?s).*(?:最高|上限|满分|最多|不超过)\\s*$")) continue;
            BigDecimal score = "零".equals(matcher.group(1))
                    ? BigDecimal.ZERO : new BigDecimal(matcher.group(1));
            int quoteStart = Math.max(0, matcher.start() - Math.min(80, matcher.start()));
            int quoteEnd = Math.min(value.length(), matcher.end() + 40);
            return new ScoreSummary(score, value.substring(quoteStart, quoteEnd).strip(), matcher.start());
        }
        return null;
    }

    private static String scoreIdentity(Evidence evidence, String subject, BigDecimal score) {
        return Objects.toString(evidence.documentId(), "") + "|" + evidence.page() + "|"
                + normalize(subject) + "|" + score.stripTrailingZeros().toPlainString();
    }

    private static String anomalySnippet(String value) {
        String compact = Objects.toString(value, "").replaceAll("\\s+", " ").strip();
        return compact.length() <= 80 ? compact : compact.substring(0, 80);
    }

    private static String anomalySnippet(String value, int index) {
        String text = Objects.toString(value, "");
        int start = Math.max(0, index - 30), end = Math.min(text.length(), index + 50);
        return anomalySnippet(text.substring(start, end));
    }

    private static List<TypoRule> typoRules(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        List<TypoRule> result = new ArrayList<>();
        for (Object item : values) {
            String rule = Objects.toString(item, "").strip();
            String[] parts = rule.split("(?:=>|->|→)", 2);
            if (parts.length != 2) continue;
            String wrong = parts[0].strip(), correct = parts[1].strip();
            if (wrong.isBlank() || correct.isBlank() || wrong.equals(correct)
                    || wrong.length() > 40 || correct.length() > 40) continue;
            result.add(new TypoRule(wrong, correct));
        }
        return List.copyOf(result);
    }

    private static boolean explicitGap(String corpus, String dimension) {
        String text = Objects.toString(corpus, "");
        return Pattern.compile("(?:缺少|缺失|未提供|未说明|未包含|无)[^。；;\\n]{0,20}" + Pattern.quote(dimension)
                + "|" + Pattern.quote(dimension) + "[^。；;\\n]{0,20}(?:缺少|缺失|未提供|未说明|未包含|空白)")
                .matcher(text).find();
    }

    private static ParsedPrice parsedPrice(Fact fact) {
        String normalized = Objects.toString(fact.normalizedValue(), "").strip();
        String raw = Objects.toString(fact.rawValue(), "");
        String tax = "", currency = "", unit = "";
        String numeric = normalized;
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":", 2);
            String prefix = upper(parts[0]);
            tax = switch (prefix) {
                case "CNY_INCLUSIVE" -> "INCLUSIVE";
                case "CNY_EXCLUSIVE" -> "EXCLUSIVE";
                case "CNY" -> "";
                default -> "";
            };
            currency = canonicalCurrency(prefix);
            unit = basePriceUnit(currency);
            numeric = parts[1];
        }
        if (currency.isBlank()) currency = currencyIn(raw);
        if (unit.isBlank()) unit = unitIn(raw, currency);
        try {
            return new ParsedPrice(new BigDecimal(numeric.replace(",", "")), currency, unit, tax);
        } catch (NumberFormatException invalid) {
            Matcher matcher = Pattern.compile("([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*(亿元|万元|万|元)").matcher(
                    raw);
            if (!matcher.find()) return null;
            BigDecimal amount = new BigDecimal(matcher.group(1).replace(",", ""));
            if ("万元".equals(matcher.group(2)) || "万".equals(matcher.group(2))) amount = amount.multiply(new BigDecimal("10000"));
            else if ("亿元".equals(matcher.group(2))) amount = amount.multiply(new BigDecimal("100000000"));
            if (raw.contains("不含税") || raw.contains("未税") || raw.contains("税前")) tax = "EXCLUSIVE";
            else if (raw.contains("含税") || raw.contains("税后")) tax = "INCLUSIVE";
            if (currency.isBlank()) currency = "CNY";
            return new ParsedPrice(amount, currency, "元", tax);
        }
    }

    private static String currencyIn(String raw) {
        String value = upper(raw);
        if (value.matches("(?s).*(?:人民币|CNY|RMB|￥|¥|亿元|万元|元).*") ) return "CNY";
        if (value.matches("(?s).*(?:美元|USD|US\\$|\\$).*") ) return "USD";
        if (value.matches("(?s).*(?:欧元|EUR|€).*") ) return "EUR";
        if (value.matches("(?s).*(?:港币|港元|HKD).*") ) return "HKD";
        return "";
    }

    private static String unitIn(String raw, String currency) {
        String value = upper(raw);
        if ("CNY".equals(currency) && value.matches("(?s).*(?:亿元|万元|元|人民币|CNY|RMB|￥|¥).*") ) return "元";
        if ("USD".equals(currency) && value.matches("(?s).*(?:美元|USD|US\\$|\\$).*") ) return "美元";
        if ("EUR".equals(currency) && value.matches("(?s).*(?:欧元|EUR|€).*") ) return "欧元";
        if ("HKD".equals(currency) && value.matches("(?s).*(?:港币|港元|HKD).*") ) return "港元";
        return "";
    }

    private static String basePriceUnit(String currency) {
        return switch (currency) {
            case "CNY" -> "元";
            case "USD" -> "美元";
            case "EUR" -> "欧元";
            case "HKD" -> "港元";
            default -> "";
        };
    }

    private static String lotAround(String pageText, String quote) {
        String local = Objects.toString(quote, "");
        String page = cleanMarkup(Objects.toString(pageText, ""));
        String value = lots(local);
        if (!value.isBlank()) return value;
        int at = page.indexOf(cleanMarkup(local));
        if (at >= 0) {
            int start = Math.max(0, at - 500), end = Math.min(page.length(), at + local.length() + 200);
            return lots(page.substring(start, end));
        }
        return "";
    }

    private static String evidenceContext(String pageText, String quote, String anchor) {
        String page = cleanMarkup(Objects.toString(pageText, ""));
        if (page.isBlank()) return "";
        String local = cleanMarkup(Objects.toString(quote, ""));
        int at = local.isBlank() ? -1 : page.indexOf(local);
        int length = local.length();
        if (at < 0 && anchor != null && !anchor.isBlank()) {
            for (String label : fieldLabels(anchor)) {
                at = page.indexOf(label);
                if (at >= 0) { length = label.length(); break; }
            }
        }
        if (at < 0) return "";
        int start = Math.max(0, at - 300), end = Math.min(page.length(), at + length + 300);
        return page.substring(start, end);
    }

    private static String lots(String text) {
        Matcher matcher = LOT.matcher(text);
        // Use a normalized key for comparison, but retain the source spelling (including case)
        // in the observation shown to reviewers and used by exact business identifiers.
        Map<String, String> result = new LinkedHashMap<>();
        while (matcher.find()) {
            String sourceValue = Objects.toString(
                    matcher.group(1) == null ? matcher.group(2) : matcher.group(1), "").strip();
            if (!sourceValue.isBlank()) result.putIfAbsent(normalize(sourceValue), sourceValue);
        }
        return result.size() == 1 ? result.values().iterator().next() : "";
    }

    private static String priceLayer(String raw) {
        String value = Objects.toString(raw, "");
        if (value.matches("(?s).*(?:分项报价|分项合价|小计).*")) return "ITEM";
        if (value.matches("(?s).*(?:单价|单位报价).*")) return "UNIT";
        if (value.matches("(?s).*(?:投标总报价|投标报价|报价总额|报价合计|总报价|投标总价|总价).*")) return "TOTAL";
        return "";
    }

    private static boolean knownRequired(String required, String actual,
                                         java.util.function.BiPredicate<String, String> equivalent) {
        return required.isBlank() || !actual.isBlank() && equivalent.test(required, actual);
    }

    private static boolean currencyEquivalent(String expected, String actual) {
        return !canonicalCurrency(expected).isBlank()
                && canonicalCurrency(expected).equals(canonicalCurrency(actual));
    }

    private static String canonicalCurrency(String value) {
        String normalized = upper(value);
        if (normalized.startsWith("CNY") || normalized.equals("RMB") || normalized.contains("人民币")) return "CNY";
        if (normalized.startsWith("USD") || normalized.contains("美元")) return "USD";
        if (normalized.startsWith("EUR") || normalized.contains("欧元")) return "EUR";
        if (normalized.startsWith("HKD") || normalized.contains("港币") || normalized.contains("港元")) return "HKD";
        return "";
    }

    private static boolean unitEquivalent(String expected, String actual) {
        return !canonicalUnit(expected).isBlank() && canonicalUnit(expected).equals(canonicalUnit(actual));
    }

    private static String canonicalUnit(String value) {
        String normalized = upper(value).replaceAll("[\\s（()）]", "");
        if (Set.of("元", "人民币元", "CNY", "RMB").contains(normalized)) return "CNY_BASE";
        if (Set.of("美元", "USD", "US$").contains(normalized)) return "USD_BASE";
        if (Set.of("欧元", "EUR").contains(normalized)) return "EUR_BASE";
        if (Set.of("港元", "港币", "HKD").contains(normalized)) return "HKD_BASE";
        return normalized;
    }

    private static boolean taxEquivalent(String expected, String actual) {
        return canonicalTax(expected).equals(canonicalTax(actual)) && !canonicalTax(expected).isBlank();
    }

    private static String canonicalTax(String value) {
        String normalized = upper(value).replace("CNY_", "");
        if (normalized.contains("不含税") || normalized.contains("未税") || normalized.contains("税前") || normalized.equals("EXCLUSIVE")) return "EXCLUSIVE";
        if (normalized.contains("含税") || normalized.contains("税后") || normalized.equals("INCLUSIVE")) return "INCLUSIVE";
        return "";
    }

    private static boolean layerEquivalent(String expected, String actual) {
        return canonicalLayer(expected).equals(canonicalLayer(actual)) && !canonicalLayer(expected).isBlank();
    }

    private static String canonicalLayer(String value) {
        String normalized = upper(value);
        if (normalized.equals("TOTAL") || normalized.contains("总价") || normalized.contains("总报价")) return "TOTAL";
        if (normalized.equals("UNIT") || normalized.contains("单价")) return "UNIT";
        if (normalized.equals("ITEM") || normalized.contains("分项") || normalized.contains("小计")) return "ITEM";
        return "";
    }

    private static boolean indexPage(String text) {
        if (text == null) return false;
        String opening = cleanMarkup(text.substring(0, Math.min(240, text.length())));
        return opening.contains("目录") && INDEX_ENTRY.matcher(text).results().count() >= 2;
    }

    private static boolean indexLine(String line) {
        return INDEX_ENTRY.matcher(line).matches()
                || cleanMarkup(line).matches(".{2,100}[0-9]{1,5}") && line.matches(".*(?:\\.{3,}|…{2,}|·{3,}).*");
    }

    private static boolean heading(String line, String materialType) {
        String value = normalize(line.replaceFirst("^[ \\t]*(?:#{1,6}|第?[一二三四五六七八九十0-9]+[章节条、.．）)]?)[ \\t]*", ""));
        String expected = normalize(materialType);
        return !expected.isBlank() && (value.equals(expected)
                || value.endsWith(expected) && value.length() <= expected.length() + 14)
                && !line.matches(".*[。；;].*");
    }

    private static String section(List<String> lines, int heading) {
        int end = Math.min(lines.size(), heading + 30);
        for (int index = heading + 1; index < end; index++) {
            String line = lines.get(index).strip();
            if (index > heading + 1 && line.matches("^(?:#{1,6}[ \\t]+|第?[一二三四五六七八九十0-9]+[章节][ \\t]*).{1,80}$")) {
                end = index; break;
            }
        }
        return String.join("\n", lines.subList(heading, end));
    }

    private static boolean substantiveBody(String section, String title) {
        List<String> content = section.lines().map(String::strip).filter(value -> !value.isBlank())
                .filter(value -> !containsNormalized(value, title) || !heading(value, title))
                .filter(value -> !value.matches("[0-9]{1,5}"))
                .filter(value -> !value.matches(".*(?:投标人|法定代表人|委托代理人)[（(]?(?:盖章|签字|签章)[）)]?[ \\t]*[:：]?[ \\t_—-]*"))
                .toList();
        String joined = normalize(String.join("", content));
        if (joined.length() < 8) return false;
        return content.stream().anyMatch(value -> value.matches(".*(?:[:：]|兹|我公司|我方|承诺|证明|名称|编号|代码|地址|有效期|日期|责任|声明).*"))
                || joined.length() >= 30;
    }

    private static boolean embeddedMaterialBody(String line, String materialType) {
        if (line.length() < materialType.length() + 8 || line.matches(".*(?:应提供|须提供|需提供|请附|目录|索引).*") ) return false;
        return line.matches(".*(?:[:：].{4,}|兹证明|我公司|我方|统一社会信用代码|证书编号|有效期|特此承诺|郑重承诺).*" );
    }

    private static boolean structuredPage(Page page) {
        String text = Objects.toString(page.text(), "");
        if (text.matches("(?is).*<table\\b.*</table\\s*>.*")) return true;
        long delimited = text.lines().filter(line -> line.chars().filter(ch -> ch == '|').count() >= 2 || line.contains("\t")).count();
        if (delimited >= 2) return true;
        return page.layout() != null && "COMPLETE".equals(page.layout().status()) && !page.layout().tables().isEmpty();
    }

    private static boolean exampleOrHistorical(String text) {
        String value = cleanMarkup(text);
        return example(value) || HISTORY.matcher(value).find();
    }

    private static boolean example(String value) {
        return cleanMarkup(value).matches("(?s).*(?:填写示例|示范文本|参考模板|仅供参考|样例|范本).*" );
    }

    private static String beforeHistory(String value) {
        Matcher history = HISTORY.matcher(cleanMarkup(value));
        return history.find() ? cleanMarkup(value).substring(0, history.start()).strip() : cleanMarkup(value);
    }

    private static String stableIdentityNumber(Map<String, List<String>> fields) {
        for (String field : List.of("idNumber", "passportNumber", "drivingLicenseNumber")) {
            List<String> values = fields.getOrDefault(field, List.of());
            if (values.size() == 1) {
                String value = values.get(0).replaceAll("[\\s\\p{Z}]", "").toUpperCase(Locale.ROOT);
                if (!value.matches(".*[*＊×•●_]|.*X{2,}.*") && value.matches("[A-Z0-9()（）/.-]{5,80}")) return value;
            }
        }
        return "";
    }

    private static String personKey(String name, String employer, String strongNumber, String recordKey) {
        if (!strongNumber.isBlank()) return "ID:" + digest(strongNumber).substring(0, 24);
        // Employer is a business dimension, not a person identity. Missing/changing employer must
        // not split one same-name candidate into several people and accidentally satisfy minCount.
        if (!name.isBlank()) return "NAME:" + digest(normalize(name)).substring(0, 24);
        return "RECORD:" + digest(recordKey).substring(0, 24);
    }

    private static String certificateHolderKey(Context context, String name,
                                               Map<String, List<String>> fields, String recordKey) {
        String strongNumber = stableIdentityNumber(fields);
        if (!strongNumber.isBlank()) return personKey(name, first(fields, "employer"), strongNumber, recordKey);
        Set<String> rosterKeys = context.observations.stream()
                .filter(Observation.PersonnelObservation.class::isInstance)
                .map(Observation.PersonnelObservation.class::cast)
                .filter(value -> same(value.personName(), name))
                .map(Observation.PersonnelObservation::personKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (rosterKeys.size() == 1) return rosterKeys.iterator().next();
        return personKey(name, first(fields, "employer"), "", recordKey);
    }

    private static String canonicalRole(String role) {
        String value = cleanMarkup(Objects.toString(role, "")).replaceFirst("^(?:本项目|本工程|本次投标)", "").strip();
        return switch (value) {
            case "施工部分负责人" -> "施工负责人";
            case "设计部分负责人" -> "设计负责人";
            default -> value;
        };
    }

    private static boolean sameRole(String expected, String actual) {
        return normalize(canonicalRole(expected)).equals(normalize(canonicalRole(actual)));
    }

    private static boolean explicitRole(String quote, String requestedRole) {
        String role = canonicalRole(requestedRole), text = beforeHistory(cleanMarkup(quote));
        if (role.isBlank() || text.isBlank() || example(text)) return false;
        return Pattern.compile("(?:拟在本合同任职|拟任职务|本项目职务|在本项目中担任职务|担任职务)"
                + "[ \\t]*[:：]?[ \\t]*" + Pattern.quote(role) + "(?:[ \\t]|[|<。；;]|$)").matcher(text).find();
    }

    private static String consortiumRole(String text, String employer) {
        if (employer.isBlank()) return "";
        String compact = cleanMarkup(text);
        for (String role : List.of("联合体牵头人", "联合体牵头单位", "施工成员", "设计成员")) {
            if (Pattern.compile(Pattern.quote(employer) + "[^。；;\\n]{0,25}" + role + "|" + role + "[^。；;\\n]{0,25}" + Pattern.quote(employer)).matcher(compact).find()) return role;
        }
        return "";
    }

    private static String mergeDimension(String existing, String incoming) {
        if (incoming == null || incoming.isBlank()) return existing;
        if (existing == null || existing.isBlank()) return incoming;
        return same(existing, incoming) ? existing : "UNRESOLVED";
    }

    private static List<SourceRef> refs(Context context, Collection<Evidence> evidence) {
        LinkedHashMap<String, SourceRef> refs = new LinkedHashMap<>();
        for (Evidence value : evidence) {
            if (value == null) continue;
            Document document = context.bid.id().equals(value.documentId()) ? context.bid
                    : context.tender != null && context.tender.id().equals(value.documentId()) ? context.tender : null;
            context.domainEvidence.putIfAbsent(evidenceKey(value), value);
            boolean trustedExternal = document == null && "TRUSTED_CONNECTOR".equals(upper(value.locationType()));
            if (document == null && !trustedExternal) continue;
            Page page = document == null ? null : page(document, value);
            String parser = trustedExternal ? "TRUSTED_CONNECTOR"
                    : page == null ? "" : Objects.toString(page.parser(), "");
            int version = document == null ? 0 : document.version();
            String documentHash = document == null
                    ? digest(value.documentId() + "|" + value.documentName() + "|" + value.anchor())
                    : document.sha256();
            SourceRef ref = new SourceRef(value.documentId(), version, documentHash, parser,
                    digest(Objects.toString(value.quote(), "")), value.locationType(), value.page(), value.quote(), null, null);
            refs.putIfAbsent(sourceKey(ref), ref);
        }
        return List.copyOf(refs.values());
    }

    private static String sourceKey(SourceRef source) {
        return source.documentId() + ":" + source.documentVersion() + ":" + source.locationType() + ":"
                + source.location() + ":" + source.sourceHash();
    }

    /**
     * Observation identities are content/source identities, not check-spec identities. Evidence
     * tasks are intentionally shared by equivalent specs, so embedding {@code spec.id()} here
     * would let a material receipt produced by one spec disagree with a related seal receipt
     * produced by another. The source tuple keeps the id tied to the exact document revision and
     * location; subject and business scope keep distinct facts at that location separate.
     */
    private static String stableObservationId(String prefix, String subject, String businessScope,
                                              Collection<SourceRef> sources) {
        String sourceScope = sources.stream().filter(Objects::nonNull)
                .map(source -> String.join("|",
                        source.documentId(), Integer.toString(source.documentVersion()), source.documentHash(),
                        source.parserFingerprint(), source.locationType(), Integer.toString(source.location()),
                        Objects.toString(source.startOffset(), ""), Objects.toString(source.endOffset(), ""),
                        source.sourceHash()))
                .sorted().collect(java.util.stream.Collectors.joining("\u001e"));
        String identity = "observation-v2\u001f" + prefix + "\u001f" + Objects.toString(subject, "")
                + "\u001f" + Objects.toString(businessScope, "") + "\u001f" + sourceScope;
        return prefix + "-" + digest(identity).substring(0, 20);
    }

    private static String evidenceKey(Evidence evidence) {
        return evidence.documentId() + ":" + evidence.locationType() + ":" + evidence.page() + ":"
                + Objects.toString(evidence.anchor(), "") + ":" + digest(Objects.toString(evidence.quote(), ""));
    }

    private static Page page(Document document, Evidence evidence) {
        if (document == null || evidence == null) return null;
        List<Page> pages = document.pages() == null ? List.of() : document.pages().stream()
                .filter(page -> page.number() == evidence.page() && Objects.equals(page.locationType(), evidence.locationType())).toList();
        return pages.size() == 1 ? pages.get(0) : null;
    }

    private static List<Page> sortedPages(Document document) {
        if (document == null || document.pages() == null) return List.of();
        return document.pages().stream().sorted(Comparator.comparingInt(Page::number).thenComparing(Page::locationType)).toList();
    }

    private static boolean complete(Document document) {
        if (document.pages() == null || document.pages().isEmpty()) return false;
        if (document.warnings() != null && document.warnings().stream().filter(Objects::nonNull).anyMatch(warning -> INCOMPLETE.matcher(warning).find())) return false;
        return document.pages().stream().noneMatch(TemplateEvidenceAssembler::pageIncomplete);
    }

    private static boolean pageIncomplete(Page page) {
        String warning = Objects.toString(page.warning(), "");
        String text = Objects.toString(page.text(), "");
        // Text is user/OCR content. Only explicit bracketed system sentinels in it are terminal;
        // a bidder may legitimately discuss “解析未完成” or “截断” in ordinary prose.
        boolean textSentinel = Pattern.compile(
                "\\[(?:OCR未完成|OCR_DEFERRED|OCR_FAILED|识别未完成|解析未完成|页数超限)\\]",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
        return INCOMPLETE.matcher(warning).find() || textSentinel;
    }

    private static Page pageForEvidence(Context context, Evidence evidence) {
        if (evidence == null) return null;
        if (context.bid.id().equals(evidence.documentId())) return page(context.bid, evidence);
        if (context.tender != null && context.tender.id().equals(evidence.documentId()))
            return page(context.tender, evidence);
        return null;
    }

    private static String labelled(String text, String label) {
        Matcher matcher = Pattern.compile(label + "[ \\t]*[:：=][ \\t]*([^|\\t;；。\\n]{2,80})").matcher(Objects.toString(text, ""));
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private static List<String> labelledValues(String text, String prefix) {
        String label = Objects.toString(prefix, "").replaceFirst("[:：=\\s]+$", "");
        if (label.isBlank()) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(Pattern.quote(label) + "[ \\t]*[:：=][ \\t]*([^|\\t;；。\\n]{2,120})")
                .matcher(Objects.toString(text, ""));
        while (matcher.find()) values.add(matcher.group(1).strip());
        return List.copyOf(values);
    }

    private static LocalDate parseDate(String value) {
        if (value == null) return null;
        if (longTerm(value) || "9999-12-31".equals(value)) return LocalDate.of(9999, 12, 31);
        try { return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (DateTimeParseException ignored) { }
        Matcher matcher = DATE.matcher(value);
        if (!matcher.find()) return null;
        try { return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))); }
        catch (RuntimeException invalid) { return null; }
    }

    private static boolean containsNormalized(String text, String expected) {
        String left = normalize(text), right = normalize(expected);
        return !right.isBlank() && left.contains(right);
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").toLowerCase(Locale.ROOT)
                .replaceAll("<[^>]*>", "")
                .replaceAll("[\\s\\p{Z}《》〈〉<>（）()【】\\[\\]：:、,，。;；_—–\\-]", "");
    }

    private static String cleanMarkup(String value) {
        return Objects.toString(value, "").replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("[*#`]", "").replaceAll("[\\s\\p{Z}]+", " ").strip();
    }

    private static String trimQuote(String value) {
        String text = Objects.toString(value, "").strip();
        return text.length() <= 1200 ? text : text.substring(0, 1200);
    }

    private static String subjectKey(SubjectSelector selector) {
        String constrained = selector.constraints().getOrDefault("entityKey", "");
        if (!constrained.isBlank()) return constrained;
        return "PERSON".equalsIgnoreCase(selector.entityType()) ? "" : "BIDDER";
    }

    private static String string(Map<String, Object> values, String key) {
        return Objects.toString(values.get(key), "").strip();
    }

    private static List<String> strings(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof Collection<?> collection)) return List.of();
        return collection.stream().map(String::valueOf).map(String::strip).filter(value -> !value.isBlank()).distinct().toList();
    }

    private static boolean bool(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        return value == null ? fallback : value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String first(Map<String, List<String>> values, String key) {
        List<String> candidates = values.getOrDefault(key, List.of());
        return candidates.size() == 1 ? candidates.get(0) : "";
    }

    private static boolean same(String left, String right) { return normalize(left).equals(normalize(right)); }
    private static String upper(String value) { return Objects.toString(value, "").strip().toUpperCase(Locale.ROOT); }
    private static String safe(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "item" : Integer.toUnsignedString(normalized.hashCode(), 36);
    }
    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(Objects.toString(value, "")); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.intValue();
        return integer(Objects.toString(value, ""), fallback);
    }
    private static int lastIndex(String value, String... needles) {
        int result = -1;
        for (String needle : needles) result = Math.max(result, value.lastIndexOf(needle));
        return result;
    }
    private static String factIdentity(Fact fact) {
        return fact.entityType() + "|" + fact.entityKey() + "|" + fact.field() + "|" + fact.normalizedValue() + "|"
                + (fact.evidence() == null ? "" : evidenceKey(fact.evidence()));
    }
    private static boolean trustedConnectorFact(Fact fact) {
        if (fact == null || fact.evidence() == null) return false;
        Evidence evidence = fact.evidence();
        return "TRUSTED_CONNECTOR".equals(upper(fact.entityType()))
                && "TRUSTED_CONNECTOR".equals(upper(evidence.locationType()))
                && !upper(Objects.toString(evidence.anchor(), "") + "|" + Objects.toString(evidence.quote(), ""))
                .contains("PROVIDED_NOT_AUTHENTICATED");
    }
    private static java.util.Optional<String> connectorAsOf(Fact fact) {
        String value = String.join(" ", Objects.toString(fact.rawValue(), ""),
                Objects.toString(fact.evidence().anchor(), ""), Objects.toString(fact.evidence().quote(), ""));
        Matcher matcher = DATE.matcher(value);
        if (!matcher.find()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(LocalDate.of(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))).toString());
        } catch (RuntimeException invalid) {
            return java.util.Optional.empty();
        }
    }
    private static double confidence(Evidence evidence) {
        if (evidence == null || !Double.isFinite(evidence.confidence())) return 0;
        return Math.max(0, Math.min(1, evidence.confidence()));
    }
    private static double parserConfidence(Page page) {
        return Objects.toString(page.parser(), "").toUpperCase(Locale.ROOT).contains("OCR") ? 0.75 : 1;
    }
    private static double observationConfidence(Page page, Collection<Evidence> evidence) {
        double direct = averageConfidenceEvidence(evidence);
        // PersonCredentialFacts intentionally records raw OCR transcription confidence as zero.
        // Once this assembler has source-bound the record and parsed its explicit local structure,
        // retain a conservative parser confidence so a valid OCR SourceRef is not mistaken for an
        // unsupported, source-less assertion by the fixed evaluator.
        return direct > 0 ? direct : page == null ? 0 : parserConfidence(page);
    }
    private static double averageConfidenceEvidence(Collection<Evidence> evidence) {
        return evidence.isEmpty() ? 0 : evidence.stream().mapToDouble(TemplateEvidenceAssembler::confidence).average().orElse(0);
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static void addCounter(Context context, String key, int count) {
        if (count > 0) context.counters.merge(key, count, Integer::sum);
    }

    public record Assembly(EvidenceView evidenceView, List<Evidence> evidences, Map<String, Object> diagnostics) {
        public Assembly {
            Objects.requireNonNull(evidenceView, "evidenceView");
            evidences = evidences == null ? List.of() : List.copyOf(evidences);
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        }
    }

    private static final class Context {
        final CheckSpec spec;
        final Checkpoint checkpoint;
        final Document bid;
        final Document tender;
        final List<Fact> facts;
        final boolean documentComplete;
        final DocumentScopeProof.Resolver scope;
        final List<Observation> observations = new ArrayList<>();
        final Set<String> closed = new LinkedHashSet<>();
        final Set<String> failed = new LinkedHashSet<>();
        final Map<String, String> processingFailures = new LinkedHashMap<>();
        final LinkedHashMap<String, Evidence> domainEvidence = new LinkedHashMap<>();
        final Map<String, Integer> counters = new LinkedHashMap<>();
        final List<Map<String, String>> notes = new ArrayList<>();

        Context(CheckSpec spec, Checkpoint checkpoint, Document bid, Document tender, List<Fact> facts) {
            this.spec = spec; this.checkpoint = checkpoint; this.bid = bid; this.tender = tender; this.facts = facts;
            documentComplete = complete(bid); scope = DocumentScopeProof.forDocument(bid);
        }
        void add(Observation observation) { observations.add(observation); }
        void increment(String key) { counters.merge(key, 1, Integer::sum); }
        void note(String code, String message) { notes.add(Map.of("code", code, "message", message)); }
    }

    private record MaterialScan(List<Observation.MaterialObservation> observations, boolean mentionFound, boolean bodyFound) { }
    private enum MaterialVolume { NONE, BUSINESS, TECHNICAL }
    private enum MaterialAliasKind {
        BUSINESS_VOLUME(MaterialVolume.BUSINESS), BUSINESS_INDEX(MaterialVolume.BUSINESS),
        TECHNICAL_VOLUME(MaterialVolume.TECHNICAL), TECHNICAL_INDEX(MaterialVolume.TECHNICAL),
        TECHNICAL_PROPOSAL(MaterialVolume.TECHNICAL);
        private final MaterialVolume volume;
        MaterialAliasKind(MaterialVolume volume) { this.volume = volume; }
        MaterialVolume volume() { return volume; }
    }
    private record MaterialAliasProfile(MaterialAliasKind kind, List<String> aliases) {
        MaterialAliasProfile { aliases = List.copyOf(aliases); }
    }
    private record CoverIdentity(String projectNumber, String bidder) {
        boolean complete() { return !projectNumber.isBlank() && !bidder.isBlank(); }
        boolean sameBid(CoverIdentity other) {
            return other != null && projectNumber.equals(other.projectNumber) && bidder.equals(other.bidder);
        }
    }
    private record VolumeCover(String pageKey, MaterialVolume volume, CoverIdentity identity) { }
    private record VolumeScope(Map<String, MaterialVolume> pages, Map<String, MaterialVolume> covers) {
        static VolumeScope empty() { return new VolumeScope(Map.of(), Map.of()); }
        boolean contains(Page page, MaterialVolume volume) { return pages.get(pageKey(page)) == volume; }
        boolean isCover(Page page, MaterialVolume volume) { return covers.get(pageKey(page)) == volume; }
    }
    private record TrustedSeal(boolean observed, String location, boolean currentScope, Evidence evidence, List<SourceRef> sources) { }
    private record PersonPage(Page page, PersonCredentialFacts.Result result) { }
    private record PersonnelCandidate(PersonPage personPage,
                                      List<PersonCredentialFacts.Occurrence> occurrences,
                                      String name, String role, String employer, String strongNumber,
                                      boolean currentAppointment) { }
    private record DateRange(LocalDate from, LocalDate until, boolean longTerm, boolean conflicting) { }
    private record ParsedPrice(BigDecimal amount, String currency, String unit, String taxBasis) { }
    private record DurationValue(BigDecimal amount, String unit, String calendarBasis) { }
    private record ObjectiveRule(String partyRole, String materialType, BigDecimal score,
                                 List<SourceRef> sources) { }
    private record ObjectiveProof(List<Evidence> eligible, List<Evidence> ineligible,
                                  List<Evidence> unresolved) { }
    private record QualitySectionScan(boolean sectionLocated,
                                      List<Observation.MaterialObservation> observations) { }
    private record SectionSlice(Page page, String text) { }
    private static final class ObjectiveRuleBuilder {
        final String partyRole;
        final String materialType;
        final BigDecimal score;
        final LinkedHashMap<String, SourceRef> sources = new LinkedHashMap<>();
        ObjectiveRuleBuilder(String partyRole, String materialType, BigDecimal score) {
            this.partyRole = partyRole;
            this.materialType = materialType;
            this.score = score;
        }
        void add(SourceRef source) { sources.putIfAbsent(sourceKey(source), source); }
    }
    private record ScoreSummary(BigDecimal score, String quote, int offset) { }
    private record TypoRule(String wrong, String correct) { }
    private record FieldValue(String value, Evidence evidence) { }
    private record LabelValue(boolean present, String value) { }
    private record DatedSource(LocalDate date, List<Evidence> evidence) {
        DatedSource {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }
    private record PriceScan(int matchedFacts, boolean unresolved) { }
    private record InputSnapshot(String contentHash, String parserFingerprint, String factSnapshot) { }
    private record SnapshotLookup(InputSnapshot snapshot, boolean cacheHit) { }
    private record NeedFlight(EvidenceNeed need, EvidenceNeedKey stableKey,
                              CompletableFuture<NeedReceipt> future) { }
    private record NeedReceipt(EvidenceNeed need, EvidenceReadReceipt receipt, Throwable failure) { }
    private record EvidenceQuery(List<String> documentRoles, List<String> sectionTerms,
                                 String instanceKey, List<String> fieldRoles) {
        EvidenceQuery {
            documentRoles = documentRoles == null ? List.of() : List.copyOf(documentRoles);
            sectionTerms = sectionTerms == null ? List.of() : List.copyOf(sectionTerms);
            instanceKey = Objects.toString(instanceKey, "");
            fieldRoles = fieldRoles == null ? List.of() : List.copyOf(fieldRoles);
        }
        Map<String, Object> audit(String needKey) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("needKey", needKey);
            value.put("documentRoles", documentRoles);
            value.put("sectionTerms", sectionTerms);
            value.put("instanceKey", instanceKey);
            value.put("fieldRoles", fieldRoles);
            return Map.copyOf(value);
        }
    }

    private static final class AssemblyMemo {
        private final java.util.function.Supplier<Assembly> supplier;
        private Assembly value;
        AssemblyMemo(java.util.function.Supplier<Assembly> supplier) { this.supplier = supplier; }
        synchronized Assembly get() {
            if (value == null) value = supplier.get();
            return value;
        }
        synchronized Assembly peek() { return value; }
    }

    /** Identity key avoids deep record/list equality over the same large OCR object graph. */
    private static final class SnapshotCacheKey {
        private final Document document;
        private final List<Fact> facts;
        private final int hash;

        SnapshotCacheKey(Document document, List<Fact> facts) {
            this.document = Objects.requireNonNull(document, "document");
            this.facts = facts;
            this.hash = 31 * System.identityHashCode(document) + System.identityHashCode(facts);
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof SnapshotCacheKey key && document == key.document && facts == key.facts;
        }
    }

    private static final class PersonnelBuilder {
        final String key;
        final String name;
        final Set<String> roles = new LinkedHashSet<>();
        final Map<String, String> attributes = new LinkedHashMap<>();
        final LinkedHashMap<String, SourceRef> sources = new LinkedHashMap<>();
        final List<Evidence> evidence = new ArrayList<>();
        final List<Double> confidences = new ArrayList<>();
        String employer = "";
        String consortiumRole = "";
        String identityBasis = "NORMALIZED_NAME";

        PersonnelBuilder(String key, String name) { this.key = key; this.name = name; }
        void addAllSources(Collection<SourceRef> refs) { refs.forEach(ref -> sources.putIfAbsent(sourceKey(ref), ref)); }
        void mergeAttribute(String key, String value) {
            if (value == null || value.isBlank()) return;
            attributes.merge(key, value, TemplateEvidenceAssembler::mergeDimension);
        }
    }
}
