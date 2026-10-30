package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds source-bound requirement drafts from a clause tree.
 *
 * <p>Deterministic patterns run first. The optional classifier is called only for an otherwise
 * unsupported fragment, only when explicitly enabled, and can only select a code-owned template
 * ID. This service never publishes or executes a CheckSpec.</p>
 */
public final class RequirementMappingService {
    public static final Set<String> ALLOWED_TEMPLATE_IDS = Set.of(
            "MATERIAL_PRESENT", "COMMITMENT_WITH_SEAL", "MATERIAL_ALTERNATIVE",
            "CERTIFICATE_VALIDITY", "PRICE_COMPARE", "PERSONNEL_ROSTER",
            "SECTION_CHECKLIST", "ENTITY_FIELD_MATCH", "PERSONNEL_CREDENTIAL",
            "CONSORTIUM_RESPONSIBILITY", "PRICE_RECONCILE", "DURATION_COMPARE",
            "CROSS_DOCUMENT_CONSISTENCY", "FORM_FIELD_COMPLETENESS", "DATE_RELATION",
            "TEXT_ANOMALY", "OBJECTIVE_SCORING", "QUALITY_ADVISORY", "EXTERNAL_CHECK");

    private static final Pattern SENTENCE_BREAK = Pattern.compile("[。；;\\r\\n]+");
    private static final Pattern OBLIGATION = Pattern.compile(
            "应当|应|须|必须|不得|禁止|提供|提交|附具|具备|具有|配备|配置|不少于|至少|不超过|不高于|低于|高于|满足|符合|一致|有效期|联合体|报价|承诺|证书|资质|人员|工程师|经理|负责人|评分|得分|查询");
    private static final Pattern EXTERNAL = Pattern.compile(
            "信用中国|国家企业信用信息公示系统|中国执行信息公开网|外部平台|网站查询|网上查询|现场查询|开标现场|评标委员会查询");
    private static final Pattern PRICE_BOUND = Pattern.compile(
            "(不得超过|不超过|不得高于|不高于|最高限价(?:为)?|控制价(?:为)?|≤|小于等于|不少于|不得低于|不低于|≥|大于等于)\\s*(?:人民币)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(亿元|万元|元)");
    private static final Pattern PERSON_COUNT = Pattern.compile(
            "(至少|不少于|不低于|不超过|至多|最多)?\\s*([0-9一二两三四五六七八九十]+)\\s*(?:名|人)(?!民币|员证书)");
    private static final Pattern DATE = Pattern.compile(
            "((?:20)?[0-9]{2})[年./-]([01]?[0-9])[月./-]([0-3]?[0-9])日?");
    private static final Pattern DURATION_BOUND = Pattern.compile(
            "(投标有效期|总工期|项目工期|设计工期|施工工期|安装工期|调试工期|服务期限|交付工期|交货期|工期)"
                    + "[^。；;]{0,32}?(不得超过|不超过|不得少于|不少于|不低于|至少|至多|为|共计|=|≤|>=|≥|<=)?"
                    + "\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(工作日|日历日|天|日|个月|月|年)");
    private static final Pattern SCORE_VALUE = Pattern.compile("(?:最高|上限|满分)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*分");
    private static final String DATE_ROLE =
            "(落款日期|签署日期|签字日期|盖章日期|投标日期|开标时间|开标日期|投标截止时间|投标截止日期)";
    /**
     * A conjunction between two date labels does not establish a comparison. Keep the three
     * supported grammatical forms separate so that text such as “投标日期与开标日期分别填写”
     * cannot silently become an equality rule.
     */
    private static final Pattern DATE_RELATION_DIRECT = Pattern.compile(
            DATE_ROLE + "\\s*(?:应当|应|须|必须|原则上)?\\s*"
                    + "(不得晚于|不应晚于|不晚于|不得早于|不应早于|不早于|早于|晚于|等于|等同于)"
                    + "\\s*(?:本项目(?:的)?|对应(?:的)?|规定(?:的)?)?\\s*" + DATE_ROLE);
    private static final Pattern DATE_RELATION_EQUAL = Pattern.compile(
            DATE_ROLE + "\\s*(?:应当|应|须|必须)?\\s*(?:与|和|同)\\s*" + DATE_ROLE
                    + "\\s*(?:应当|应|须|必须)?\\s*(?:保持)?\\s*(一致|相同)");
    private static final Pattern DATE_RELATION_POSITION = Pattern.compile(
            DATE_ROLE + "\\s*(?:应当|应|须|必须)?\\s*在\\s*" + DATE_ROLE
                    + "\\s*(之前|以前|之后|以后)");
    private static final List<String> ROLES = List.of(
            "施工企业主要负责人", "施工部分负责人", "设计部分负责人", "专职安全生产管理人员", "专职安全员", "项目技术负责人", "项目负责人", "设计负责人", "项目经理",
            "技术负责人", "注册结构工程师", "注册建筑师", "造价工程师", "安全员", "施工员",
            "质量员", "材料员", "资料员", "造价员");
    private static final List<String> CREDENTIALS = List.of(
            "一级注册建造师", "二级注册建造师", "一级建造师", "二级建造师", "注册建造师",
            "注册建筑师", "注册结构工程师", "安全生产考核合格证", "职称证书", "职称证", "岗位证书", "岗位证", "资格证书", "资格证");
    private static final List<String> MATERIALS = List.of(
            "法定代表人身份证明", "法定代表人授权委托书", "授权委托书", "联合体协议书", "营业执照",
            "投标函", "投标保证金", "报价表", "承诺书", "承诺函", "资质证书", "社保证明",
            "业绩证明", "财务报告", "纳税证明", "信用报告", "合同复印件", "合同", "证明材料");

    private final RestrictedTemplateClassifier classifier;
    private final Map<String, String> enabledAdapterBySource;

    public RequirementMappingService() { this(RestrictedTemplateClassifier.disabled(), Map.of()); }

    public RequirementMappingService(RestrictedTemplateClassifier classifier) {
        this(classifier, Map.of());
    }

    public RequirementMappingService(Map<String, String> enabledAdapterBySource) {
        this(RestrictedTemplateClassifier.disabled(), enabledAdapterBySource);
    }

    public RequirementMappingService(RestrictedTemplateClassifier classifier,
                                     Map<String, String> enabledAdapterBySource) {
        this.classifier = classifier == null ? RestrictedTemplateClassifier.disabled() : classifier;
        LinkedHashMap<String, String> configured = new LinkedHashMap<>();
        if (enabledAdapterBySource != null) enabledAdapterBySource.forEach((source, adapterId) -> {
            String normalizedSource = normalizeSource(source);
            String safeAdapter = adapterId == null ? "" : adapterId.strip();
            if (!normalizedSource.isBlank() && safeAdapter.matches("[A-Z][A-Z0-9_]{0,63}"))
                configured.putIfAbsent(normalizedSource, safeAdapter);
        });
        this.enabledAdapterBySource = Map.copyOf(configured);
    }

    public RequirementMappingResult map(List<ClauseNode> roots) {
        return map(roots, MappingOptions.localOnly());
    }

    public RequirementMappingResult map(List<ClauseNode> roots, MappingOptions options) {
        List<ClauseNode> safeRoots = roots == null ? List.of() : List.copyOf(roots);
        MappingOptions safeOptions = options == null ? MappingOptions.localOnly() : options;
        validateUniqueIds(safeRoots);
        Accumulator accumulator = new Accumulator();
        walkSiblings(safeRoots, SourceContext.root(), accumulator, safeOptions);
        reconcileChecklistCoverage(accumulator);
        Map<CoverageItem.Status, Long> counts = accumulator.coverage.stream().collect(
                Collectors.groupingBy(CoverageItem::status, LinkedHashMap::new, Collectors.counting()));
        RequirementMappingResult.Diagnostics diagnostics = new RequirementMappingResult.Diagnostics(
                accumulator.classifierCalls == 0,
                accumulator.classifierCalls,
                accumulator.clauseCount,
                accumulator.coverage.size(),
                counts);
        return new RequirementMappingResult(accumulator.drafts, accumulator.coverage, diagnostics);
    }

    private void walkSiblings(List<ClauseNode> nodes, SourceContext parent, Accumulator result,
                              MappingOptions options) {
        for (int index = 0; index < nodes.size(); index++) {
            ClauseNode node = nodes.get(index);
            String previous = index == 0 ? "" : nodes.get(index - 1).id();
            String next = index + 1 >= nodes.size() ? "" : nodes.get(index + 1).id();
            SourceContext context = resolve(parent, node, previous, next);
            result.clauseCount++;
            mapNode(node, context, result, options);
            walkSiblings(node.children(), context, result, options);
        }
    }

    private void mapNode(ClauseNode node, SourceContext context, Accumulator result, MappingOptions options) {
        List<Fragment> fragments = fragments(node);
        if (fragments.isEmpty()) fragments = List.of(new Fragment(node.id() + ":f0", "", 0, 0, node.sourceRef()));
        for (Fragment fragment : fragments) {
            mapFragment(node, fragment, context, result, options);
        }
    }

    private void mapFragment(ClauseNode node, Fragment fragment, SourceContext context,
                             Accumulator result, MappingOptions options) {
        String text = fragment.text();
        if (isNonChecking(node, text)) {
            result.coverage.add(coverage(fragment, node, context, CoverageItem.Status.NON_CHECKING,
                    "", List.of(), "STRUCTURAL_OR_EXPLANATORY", "结构标题或未包含检查义务的说明"));
            return;
        }

        boolean externalRequirement = EXTERNAL.matcher(text).find();
        List<TemplateCandidate> candidates = deterministicCandidates(node, fragment, context);
        List<RequirementDraft.UnsupportedFragment> unsupported = new ArrayList<>();
        boolean classifierConsulted = false;
        if (candidates.isEmpty() && !externalRequirement && options.enableRestrictedClassifier()) {
            classifierConsulted = true;
            result.classifierCalls++;
            try {
                Optional<RestrictedTemplateClassifier.Suggestion> suggestion = classifier.classify(
                        new RestrictedTemplateClassifier.Request(fragment.id(), text, context,
                                fragment.sourceRef(), ALLOWED_TEMPLATE_IDS));
                if (suggestion.isPresent()) {
                    RestrictedTemplateClassifier.Suggestion proposed = suggestion.get();
                    if (ALLOWED_TEMPLATE_IDS.contains(proposed.templateId())) {
                        Map<String, List<SourceRef>> sources = sources(proposed.parameters().keySet(), fragment.sourceRef());
                        candidates = List.of(new TemplateCandidate(proposed.templateId(), proposed.templateVersion(),
                                proposed.confidence(), TemplateCandidate.Origin.RESTRICTED_CLASSIFIER,
                                proposed.explanation(), proposed.parameters(), sources, List.of(fragment.sourceRef()),
                                proposed.unresolvedFragments()));
                        for (String unresolved : proposed.unresolvedFragments()) {
                            unsupported.add(new RequirementDraft.UnsupportedFragment(unresolved,
                                    "CLASSIFIER_FRAGMENT_UNRESOLVED", "受限分类器未解析该片段", fragment.sourceRef()));
                        }
                    } else {
                        unsupported.add(new RequirementDraft.UnsupportedFragment(text,
                                "CLASSIFIER_TEMPLATE_NOT_ALLOWED", "分类器返回了未注册模板", fragment.sourceRef()));
                    }
                }
            } catch (RuntimeException error) {
                unsupported.add(new RequirementDraft.UnsupportedFragment(text,
                        "CLASSIFIER_FAILED", "受限分类器调用失败，保留原文供后续处理", fragment.sourceRef()));
            }
        }

        if (candidates.isEmpty()) {
            if (externalRequirement) {
                unsupported.add(new RequirementDraft.UnsupportedFragment(text, "EXTERNAL_DATA_REQUIRED",
                        "该要求依赖指定平台或业务时点，已登记但不在本地文档中判定", fragment.sourceRef()));
                String draftId = stableId("draft", fragment.id() + "|EXTERNAL|" + text);
                RequirementDraft draft = new RequirementDraft(draftId, node.id(), fragment.id(), text, context,
                        List.of(), mapOf("dataSource", externalSource(text), "requiredTime", externalTime(text)),
                        sources(List.of("dataSource", "requiredTime"), fragment.sourceRef()),
                        List.of(fragment.sourceRef()), unsupported,
                        RequirementDraft.MappingState.EXTERNAL_REQUIRED, false);
                result.drafts.add(draft);
                result.coverage.add(coverage(fragment, node, context, CoverageItem.Status.EXTERNAL_REQUIRED,
                        draftId, List.of(), "EXTERNAL_DATA_REQUIRED",
                        "要求已登记，但结论依赖指定外部数据或时点"));
                return;
            }
            if (unsupported.isEmpty()) {
                unsupported.add(new RequirementDraft.UnsupportedFragment(text, "NO_TEMPLATE_MATCH",
                        "本地确定性模式尚未覆盖该要求", fragment.sourceRef()));
            }
            String draftId = stableId("draft", fragment.id() + "|UNSUPPORTED|" + text);
            RequirementDraft draft = new RequirementDraft(draftId, node.id(), fragment.id(), text, context,
                    List.of(), Map.of(), Map.of(), List.of(fragment.sourceRef()), unsupported,
                    RequirementDraft.MappingState.UNSUPPORTED, classifierConsulted);
            result.drafts.add(draft);
            result.coverage.add(coverage(fragment, node, context, CoverageItem.Status.UNSUPPORTED,
                    draftId, List.of(), unsupported.get(0).reasonCode(), unsupported.get(0).explanation()));
            return;
        }

        candidates = candidates.stream()
                .collect(Collectors.toMap(TemplateCandidate::templateId, Function.identity(),
                        (left, right) -> left.confidence() >= right.confidence() ? left : right,
                        LinkedHashMap::new)).values().stream()
                .sorted(Comparator.comparingDouble(TemplateCandidate::confidence).reversed()
                        .thenComparing(TemplateCandidate::templateId)).toList();
        TemplateCandidate primary = candidates.get(0);
        for (TemplateCandidate candidate : candidates) {
            for (String missing : candidate.missingParameters()) {
                String code = switch (missing) {
                    case "closedScoreEvidenceSet" -> "SCORE_EVIDENCE_SET_REQUIRED";
                    case "typoDictionary" -> "TYPO_DICTIONARY_REQUIRED";
                    case "adapterId" -> "EXTERNAL_ADAPTER_NOT_CONFIGURED";
                    case "asOfDate" -> "EXTERNAL_AS_OF_DATE_REQUIRED";
                    case "evaluationStage" -> "EXTERNAL_STAGE_REQUIRED";
                    default -> "PARAMETER_NOT_BOUND";
                };
                String explanation = switch (missing) {
                    case "closedScoreEvidenceSet" -> "该条款描述计分规则；当前模板只汇总已识别分值，需先绑定可追溯且范围闭合的分值集合";
                    case "typoDictionary" -> "错别字扫描需配置确定性的“错词=>正确词”词典或接入带范围回执的检测器";
                    case "adapterId" -> "条款指定的来源尚未匹配管理员启用的外部适配器";
                    case "asOfDate" -> "外部核验必须冻结明确的查询日期，不能用执行当天日期代替";
                    case "evaluationStage" -> "外部核验必须明确在开标、投标截止或评审哪个阶段执行";
                    default -> "模板参数未能从该条款及其父级上下文确定";
                };
                unsupported.add(new RequirementDraft.UnsupportedFragment(missing, code,
                        explanation, fragment.sourceRef()));
            }
        }
        boolean external = candidates.stream().anyMatch(candidate -> "EXTERNAL_CHECK".equals(candidate.templateId()));
        boolean complete = candidates.size() == 1 && primary.missingParameters().isEmpty()
                && primary.confidence() >= options.readyConfidenceThreshold()
                && primary.origin() == TemplateCandidate.Origin.DETERMINISTIC_RULE;
        RequirementDraft.MappingState state = external
                ? RequirementDraft.MappingState.EXTERNAL_REQUIRED
                : complete ? RequirementDraft.MappingState.READY_FOR_CONFIRMATION
                : candidates.size() > 1 ? RequirementDraft.MappingState.AMBIGUOUS
                : RequirementDraft.MappingState.NEEDS_CONFIRMATION;
        String identity = candidates.stream().map(TemplateCandidate::templateId).sorted().collect(Collectors.joining(","));
        String draftId = stableId("draft", fragment.id() + "|" + identity + "|" + text);
        List<SourceRef> requirementRefs = candidates.stream().flatMap(candidate -> candidate.requirementRefs().stream())
                .collect(Collectors.toMap(this::sourceIdentity, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new)).values().stream().toList();
        RequirementDraft draft = new RequirementDraft(draftId, node.id(), fragment.id(), text, context,
                candidates, primary.parameters(), primary.parameterSources(), requirementRefs,
                unsupported, state, classifierConsulted);
        result.drafts.add(draft);
        CoverageItem.Status coverageStatus = external ? CoverageItem.Status.EXTERNAL_REQUIRED
                : complete ? CoverageItem.Status.MAPPED : CoverageItem.Status.NEEDS_CONFIRMATION;
        String reason = external ? "EXTERNAL_DATA_REQUIRED"
                : complete ? "DETERMINISTIC_TEMPLATE_MATCH"
                : candidates.size() > 1 ? "MULTIPLE_TEMPLATE_CANDIDATES" : "PARAMETER_OR_CONFIDENCE_REVIEW";
        result.coverage.add(coverage(fragment, node, context, coverageStatus, draftId,
                candidates.stream().map(TemplateCandidate::templateId).toList(), reason,
                coverageExplanation(coverageStatus)));
    }

    private List<TemplateCandidate> deterministicCandidates(ClauseNode node, Fragment fragment,
                                                             SourceContext context) {
        String text = fragment.text();
        List<TemplateCandidate> candidates = new ArrayList<>();
        SourceRef source = fragment.sourceRef();

        if (EXTERNAL.matcher(text).find()) {
            String dataSource = externalSource(text);
            String adapterId = enabledAdapterBySource.getOrDefault(normalizeSource(dataSource), "");
            Map<String, Object> parameters = mapOf("source", dataSource,
                    "queryObject", externalQueryObject(text, context),
                    "requiredFields", externalRequiredFields(text),
                    "asOfDate", extractDate(text), "adapterId", adapterId,
                    "evaluationStage", externalTime(text));
            candidates.add(candidate("EXTERNAL_CHECK", 0.99,
                    adapterId.isBlank() ? "命中外部核验要求；尚未绑定管理员启用的来源适配器"
                            : "命中外部核验要求并绑定管理员启用的来源适配器",
                    parameters, missing(parameters, "source", "queryObject", "requiredFields",
                            "asOfDate", "adapterId", "evaluationStage"), source));
        }

        Matcher duration = DURATION_BOUND.matcher(text);
        if (duration.find()) {
            String comparator = duration.group(2) == null ? "=" : duration.group(2);
            String operator = containsAny(comparator, "不得超过", "不超过", "至多", "≤", "<=") ? "<="
                    : containsAny(comparator, "不得少于", "不少于", "不低于", "至少", "≥", ">=") ? ">=" : "=";
            String unit = canonicalDurationUnit(duration.group(4));
            Map<String, Object> parameters = mapOf("field", durationField(duration.group(1)),
                    "operator", operator, "expected", new BigDecimal(duration.group(3)).toPlainString(),
                    "unit", unit, "startEvent", durationStartEvent(text),
                    "calendarBasis", durationCalendarBasis(duration.group(4)),
                    "lot", context.lotScope(), "tolerance", "0");
            candidates.add(candidate("DURATION_COMPARE", 0.98, "命中期限字段、数值、单位及比较关系",
                    parameters, missing(parameters, "field", "operator", "expected", "unit"), source));
        }

        String consistencyField = crossDocumentField(text);
        if (!consistencyField.isBlank() && explicitCrossDocument(text)
                && containsAny(text, "一致", "相同", "相符", "保持一致")) {
            Map<String, Object> parameters = mapOf("field", consistencyField,
                    "minimumOccurrences", 2, "caseSensitive", false);
            candidates.add(candidate("CROSS_DOCUMENT_CONSISTENCY", 0.96, "命中跨文件同一字段一致性要求",
                    parameters, missing(parameters, "field"), source));
        }

        if (containsAny(text, "填写完整", "完整填写", "不得留空", "不得空白", "所有栏目", "各栏均应填写")) {
            String formType = formType(text, context);
            List<String> requiredFields = requiredFormFields(node, text);
            Map<String, Object> parameters = mapOf("formType", formType,
                    "requiredFields", requiredFields, "expectedSubject", expectedFormSubject(text));
            candidates.add(candidate("FORM_FIELD_COMPLETENESS", 0.94, "命中表单必填字段完整性要求",
                    parameters, missing(parameters, "formType", "requiredFields"), source));
        }

        DateRelationMatch dateRelation = dateRelation(text);
        if (dateRelation != null) {
            Map<String, Object> parameters = mapOf("leftRole", dateRelation.leftRole(),
                    "rightRole", dateRelation.rightRole(), "operator", dateRelation.operator(),
                    "relationKey", context.projectScope());
            candidates.add(candidate("DATE_RELATION", 0.98, "命中两个明确日期角色及先后关系",
                    parameters, missing(parameters, "leftRole", "rightRole", "operator"), source));
        }

        if (containsAny(text, "错别字", "缺字", "多字", "乱码", "文字截断", "OCR错误", "OCR异常")) {
            List<String> anomalyTypes = anomalyTypes(text);
            Map<String, Object> parameters = mapOf("textScope", textScope(text, context),
                    "anomalyTypes", anomalyTypes);
            List<String> missing = new ArrayList<>(missing(parameters, "textScope"));
            // TYPO is deliberately dictionary-driven. A generic “检查错别字” clause does not
            // define which deterministic lexicon should be applied, so it must be configured or
            // supplied by a source-bound detector receipt before it becomes executable.
            if (anomalyTypes.contains("TYPO")) missing.add("typoDictionary");
            candidates.add(candidate("TEXT_ANOMALY", 0.94, "命中文字质量异常检查范围",
                    parameters, List.copyOf(missing), source));
        }

        if (objectiveScoring(text)) {
            String maxScore = maximumScore(text);
            Map<String, Object> parameters = mapOf("criterion", scoringCriterion(text),
                    "aggregation", containsAny(text, "累计", "累加", "每项", "每个") ? "SUM" : "HIGHEST",
                    "maxScore", maxScore, "scoreUnit", "分", "deduplicateBySubject", true);
            List<String> missing = new ArrayList<>(missing(parameters, "criterion", "maxScore"));
            // A single source-bound “具备/提供某材料，得X分” rule can be executed by the fixed
            // binary-material bridge. Compound, counted or cumulative rubrics still require an
            // explicitly closed score evidence set and are never compiled into an invented zero.
            if (!binaryMaterialScoreRule(text)) missing.add("closedScoreEvidenceSet");
            candidates.add(candidate("OBJECTIVE_SCORING", 0.95,
                    binaryMaterialScoreRule(text) ? "命中单一材料型客观计分规则，可按证明材料核验"
                            : "命中客观计分规则；需绑定已识别且范围闭合的分值集合后再汇总",
                    parameters, List.copyOf(missing), source));
        }

        if (!objectiveScoring(text) && qualityAdvisory(text)) {
            String materialType = qualityMaterial(text, context);
            List<String> dimensions = qualityDimensions(text);
            Map<String, Object> parameters = mapOf("materialType", materialType, "dimensions", dimensions);
            candidates.add(candidate("QUALITY_ADVISORY", 0.90, "命中需要评委判断的方案质量维度",
                    parameters, missing(parameters, "materialType", "dimensions"), source));
        }

        List<String> alternatives = alternatives(text);
        if (alternatives.size() >= 2 && containsAny(text, "提供", "提交", "附具", "证明")) {
            candidates.add(candidate("MATERIAL_ALTERNATIVE", 0.91, "命中允许任选或替代证明路径",
                    mapOf("alternatives", alternatives, "atLeast", 1, "requireBody", true), List.of(), source));
        }

        if (containsAny(text, "承诺书", "承诺函", "书面承诺") && containsAny(text, "盖章", "加盖", "公章", "签章")) {
            String material = firstContained(text, List.of("承诺书", "承诺函", "书面承诺"));
            String issuer = issuerRole(text, context);
            String sealLocation = sealLocation(text);
            Map<String, Object> parameters = mapOf("materialType", material,
                    "requiredCommitmentPoints", List.of(text), "issuerRole", issuer, "sealLocation", sealLocation);
            candidates.add(candidate("COMMITMENT_WITH_SEAL", 0.97, "同时命中承诺文件和签章要求", parameters,
                    missing(parameters, "materialType", "requiredCommitmentPoints", "issuerRole", "sealLocation"), source));
        }

        if (looksLikeChecklist(node, text)) {
            List<String> items = node.children().stream().map(ClauseNode::text)
                    .filter(value -> value != null && !value.isBlank()).toList();
            List<SourceRef> itemSources = node.children().stream().map(ClauseNode::sourceRef).toList();
            Map<String, Object> parameters = mapOf("requiredItems", items, "requireBody", true);
            Map<String, List<SourceRef>> parameterSources = new LinkedHashMap<>();
            parameterSources.put("requiredItems", itemSources);
            parameterSources.put("requireBody", List.of(source));
            List<SourceRef> requirementRefs = new ArrayList<>();
            requirementRefs.add(source);
            requirementRefs.addAll(itemSources);
            candidates.add(new TemplateCandidate("SECTION_CHECKLIST", "1.0", 0.94,
                    TemplateCandidate.Origin.DETERMINISTIC_RULE, "父条款与结构化子项构成材料清单", parameters,
                    parameterSources, requirementRefs, items.isEmpty() ? List.of("requiredItems") : List.of()));
        }

        if (containsAny(text, "联合体") && containsAny(text, "牵头", "成员", "职责", "分工", "承担", "负责")) {
            List<String> memberRoles = new ArrayList<>();
            if (containsAny(text, "牵头")) memberRoles.add("LEADER");
            if (containsAny(text, "成员", "各方")) memberRoles.add("MEMBER");
            Map<String, Object> parameters = mapOf("materialType", "联合体协议书",
                    "requiredMemberRoles", memberRoles, "requiredResponsibilities", List.of(text),
                    "leadRole", containsAny(text, "牵头") ? "LEADER" : "", "minMembers", 2, "memberPrefix", "成员:");
            candidates.add(candidate("CONSORTIUM_RESPONSIBILITY", 0.96, "命中联合体成员身份或责任分工要求",
                    parameters, missing(parameters, "requiredMemberRoles", "requiredResponsibilities", "leadRole"), source));
        }

        String role = role(text, context);
        // A bidder blacklist clause may mention a project manager and companies
        // registered by a prohibited supplier. That is not a personnel credential.
        if (ROLES.contains(role) && (containsAny(text, "证书", "职称", "考核合格")
                || text.matches("(?s).*注册(?:建造师|建筑师|监理工程师|造价工程师).*"))) {
            String certificateType = firstContained(text, CREDENTIALS);
            String employerRelation = containsAny(text, "注册单位", "聘用单位", "本单位", "投标人")
                    ? "MATCH_BIDDER_OR_APPLICABLE_CONSORTIUM_MEMBER" : "";
            Map<String, Object> parameters = mapOf("role", role, "certificateType", certificateType,
                    "requiredLevel", extractGrade(text), "requireEmployerMatch", !employerRelation.isBlank(),
                    "comparisonDate", extractDate(text), "inclusive", true);
            candidates.add(candidate("PERSONNEL_CREDENTIAL", 0.95, "岗位与证书条件出现在同一上下文",
                    parameters, missing(parameters, "role", "certificateType"), source));
        }

        Matcher personCount = PERSON_COUNT.matcher(text);
        if (!role.isBlank() && personCount.find()) {
            String comparator = personCount.group(1) == null ? "" : personCount.group(1);
            int count = chineseNumber(personCount.group(2));
            Integer min = containsAny(comparator, "不超过", "至多", "最多") ? null : count;
            Integer max = containsAny(comparator, "不超过", "至多", "最多") ? count : null;
            Map<String, Object> parameters = mapOf("role", role, "minCount", min, "maxCount", max,
                    "allowConcurrent", !containsAny(text, "不得兼任", "不可兼任", "不允许兼任"),
                    "consortiumRole", context.subjectRole());
            candidates.add(candidate("PERSONNEL_ROSTER", 0.97, "命中岗位名称和人数约束", parameters,
                    missing(parameters, "role"), source));
        }

        if (!objectiveScoring(text) && containsAny(text, "证书", "证件", "执照")
                && containsAny(text, "有效期", "有效至", "在有效期内", "有效")) {
            String certificate = firstContained(text, CREDENTIALS);
            if (certificate.isBlank()) certificate = firstContained(text, List.of("资质证书", "证书", "证件", "执照"));
            String comparisonDate = extractDate(text);
            Map<String, Object> parameters = mapOf("certificateType", certificate,
                    "comparisonDate", comparisonDate, "inclusive", true);
            List<String> missing = new ArrayList<>(missing(parameters, "certificateType", "comparisonDate"));
            candidates.add(candidate("CERTIFICATE_VALIDITY", 0.93, "命中证书有效期要求", parameters, missing, source));
        }

        Matcher priceBound = PRICE_BOUND.matcher(text);
        if (priceBound.find()) {
            String operator = containsAny(priceBound.group(1), "不少于", "不得低于", "不低于", "≥", "大于等于") ? ">=" : "<=";
            String expected = new BigDecimal(priceBound.group(2).replace(",", "")).toPlainString();
            Map<String, Object> parameters = mapOf("field", priceField(text), "operator", operator,
                    "expected", expected, "currency", "CNY", "unit", priceBound.group(3),
                    "taxBasis", taxBasis(text), "lot", context.lotScope(), "priceLayer", priceLayer(text));
            candidates.add(candidate("PRICE_COMPARE", 0.99, "命中金额边界与比较运算符", parameters,
                    missing(parameters, "field", "operator", "expected", "currency", "unit"), source));
        }

        if (containsAny(text, "合计", "总价", "分项", "税额", "大写", "小写")
                && containsAny(text, "一致", "等于", "之和", "合计", "核对", "相符")) {
            boolean amountRepresentations = text.contains("大写") && text.contains("小写");
            List<String> componentFields = new ArrayList<>();
            String totalField = amountRepresentations ? "bid.totalPrice.uppercase" : "bid.totalPrice";
            String calculation = amountRepresentations
                    ? "AMOUNT_REPRESENTATIONS_EQUAL" : "TOTAL_EQUALS_COMPONENT_SUM";
            if (amountRepresentations) {
                componentFields.add("bid.totalPrice.lowercase");
            } else {
                // “合计” can describe the total itself.  It is not evidence that an item-row set
                // exists.  Only an explicit item/detail expression enables row aggregation.
                if (containsAny(text, "分项", "各项", "明细", "子项")) componentFields.add("bid.itemPrices");
                if (containsAny(text, "税额")) {
                    componentFields.add("bid.netPrice");
                    componentFields.add("bid.taxAmount");
                }
            }
            Map<String, Object> parameters = mapOf("totalField", totalField,
                    "componentFields", componentFields.stream().distinct().toList(), "currency", "CNY",
                    "taxBasis", taxBasis(text), "lot", context.lotScope(), "totalPriceLayer", "TOTAL",
                    "calculation", calculation);
            candidates.add(candidate("PRICE_RECONCILE", 0.90,
                    amountRepresentations ? "命中报价大写与小写金额一致性要求" : "命中报价总计与分项或税额勾稽要求",
                    parameters, missing(parameters, "totalField", "componentFields", "calculation"), source));
        }

        List<String> matchingFields = entityFields(text);
        if (candidates.stream().noneMatch(candidate -> "CROSS_DOCUMENT_CONSISTENCY".equals(candidate.templateId()))
                && !matchingFields.isEmpty() && containsAny(text, "一致", "相同", "相符", "不得变更")) {
            String entityRole = issuerRole(text, context);
            String leftField = matchingFields.get(0);
            String rightField = matchingFields.size() > 1 ? matchingFields.get(1) : leftField;
            Map<String, Object> parameters = mapOf("role", entityRole,
                    "leftField", leftField, "rightField", rightField, "caseSensitive", false);
            candidates.add(candidate("ENTITY_FIELD_MATCH", 0.92, "命中同一对象字段一致性要求",
                    parameters, missing(parameters, "role", "leftField", "rightField"), source));
        }

        if (candidates.stream().noneMatch(candidate -> Set.of("MATERIAL_ALTERNATIVE", "COMMITMENT_WITH_SEAL", "SECTION_CHECKLIST", "FORM_FIELD_COMPLETENESS")
                .contains(candidate.templateId())) && containsAny(text, "提供", "提交", "附具", "附上", "出具")) {
            String material = firstContained(text, MATERIALS);
            if (!material.isBlank()) {
                candidates.add(candidate("MATERIAL_PRESENT", 0.90, "命中明确材料提交要求",
                        mapOf("materialType", material, "requireBody", true), List.of(), source));
            }
        }
        return candidates.stream().map(candidate -> bindInheritedParameterSources(candidate, text, context)).toList();
    }

    private TemplateCandidate bindInheritedParameterSources(TemplateCandidate candidate, String text,
                                                             SourceContext context) {
        Map<String, List<SourceRef>> sources = new LinkedHashMap<>(candidate.parameterSources());
        bindInherited(sources, candidate.parameters(), "role", context.subjectRole(), text,
                context.contextSources().get("subjectRole"));
        bindInherited(sources, candidate.parameters(), "issuerRole", context.subjectRole(), text,
                context.contextSources().get("subjectRole"));
        bindInherited(sources, candidate.parameters(), "consortiumRole", context.subjectRole(), text,
                context.contextSources().get("subjectRole"));
        bindInherited(sources, candidate.parameters(), "lot", context.lotScope(), text,
                context.contextSources().get("lotScope"));
        List<SourceRef> requirementRefs = new ArrayList<>(candidate.requirementRefs());
        sources.values().forEach(requirementRefs::addAll);
        requirementRefs = requirementRefs.stream().collect(Collectors.toMap(this::sourceIdentity,
                Function.identity(), (left, right) -> left, LinkedHashMap::new)).values().stream().toList();
        return new TemplateCandidate(candidate.templateId(), candidate.templateVersion(), candidate.confidence(),
                candidate.origin(), candidate.reason(), candidate.parameters(), sources,
                requirementRefs, candidate.missingParameters());
    }

    private void bindInherited(Map<String, List<SourceRef>> sources, Map<String, Object> parameters,
                               String parameterName, String inheritedValue, String text,
                               List<SourceRef> inheritedSources) {
        if (inheritedValue == null || inheritedValue.isBlank() || inheritedSources == null || inheritedSources.isEmpty()) return;
        Object value = parameters.get(parameterName);
        if (value != null && inheritedValue.equals(String.valueOf(value)) && !text.contains(inheritedValue)) {
            sources.put(parameterName, inheritedSources);
        }
    }

    private TemplateCandidate candidate(String templateId, double confidence, String reason,
                                        Map<String, Object> parameters, List<String> missing, SourceRef source) {
        return new TemplateCandidate(templateId, "1.0", confidence, TemplateCandidate.Origin.DETERMINISTIC_RULE,
                reason, parameters, sources(parameters.keySet(), source), List.of(source), missing);
    }

    private Map<String, List<SourceRef>> sources(Collection<String> names, SourceRef source) {
        Map<String, List<SourceRef>> result = new LinkedHashMap<>();
        for (String name : names) result.put(name, List.of(source));
        return Map.copyOf(result);
    }

    private SourceContext resolve(SourceContext parent, ClauseNode node, String previous, String next) {
        ClauseNode.LocalContext local = node.localContext();
        List<String> sections = append(parent.sectionPath(), sectionTitle(node));
        List<String> clausePath = append(parent.clausePath(), node.id());
        String marker = local.listMarker();
        if (marker.isBlank() && node.kind() == ClauseNode.Kind.LIST_ITEM) marker = node.id();
        List<String> listPath = append(parent.listPath(), marker);
        ClauseNode.TableContext table = mergeTable(parent, local.table());
        List<String> crossReferences = new ArrayList<>(parent.crossReferences());
        crossReferences.addAll(local.crossReferences());
        Map<String, List<SourceRef>> contextSources = new LinkedHashMap<>(parent.contextSources());
        if (!sectionTitle(node).isBlank()) appendSource(contextSources, "sectionPath", node.sourceRef());
        if (!marker.isBlank()) appendSource(contextSources, "listPath", node.sourceRef());
        if (local.table() != null) appendSource(contextSources, "table", node.sourceRef());
        if (!local.projectScope().isBlank()) appendSource(contextSources, "projectScope", node.sourceRef());
        if (!local.lotScope().isBlank()) appendSource(contextSources, "lotScope", node.sourceRef());
        if (!local.subjectRole().isBlank()) appendSource(contextSources, "subjectRole", node.sourceRef());
        if (!local.documentRole().isBlank()) appendSource(contextSources, "documentRole", node.sourceRef());
        if (local.boundary() != ClauseNode.Boundary.UNSPECIFIED) appendSource(contextSources, "boundary", node.sourceRef());
        if (!local.crossReferences().isEmpty()) appendSource(contextSources, "crossReferences", node.sourceRef());
        return new SourceContext(sections, clausePath, listPath,
                table == null ? "" : table.tableId(), table == null ? "" : table.title(),
                table == null ? List.of() : table.headers(), table == null ? null : table.rowIndex(),
                table == null ? null : table.columnIndex(), table == null ? null : table.rowSpan(),
                table == null ? null : table.columnSpan(), inherit(local.projectScope(), parent.projectScope()),
                inherit(local.lotScope(), parent.lotScope()), inherit(local.subjectRole(), parent.subjectRole()),
                inherit(local.documentRole(), parent.documentRole()),
                local.boundary() == ClauseNode.Boundary.UNSPECIFIED ? parent.boundary() : local.boundary(),
                crossReferences.stream().distinct().toList(), contextSources, previous, next);
    }

    private void appendSource(Map<String, List<SourceRef>> contextSources, String key, SourceRef source) {
        List<SourceRef> values = new ArrayList<>(contextSources.getOrDefault(key, List.of()));
        values.add(source);
        contextSources.put(key, List.copyOf(values));
    }

    private ClauseNode.TableContext mergeTable(SourceContext parent, ClauseNode.TableContext local) {
        if (local == null && parent.tableId().isBlank() && parent.tableTitle().isBlank()
                && parent.tableHeaders().isEmpty()) return null;
        if (local == null) return new ClauseNode.TableContext(parent.tableId(), parent.tableTitle(),
                parent.tableHeaders(), parent.tableRow(), parent.tableColumn(), parent.rowSpan(), parent.columnSpan());
        return new ClauseNode.TableContext(inherit(local.tableId(), parent.tableId()),
                inherit(local.title(), parent.tableTitle()),
                local.headers().isEmpty() ? parent.tableHeaders() : local.headers(),
                local.rowIndex() == null ? parent.tableRow() : local.rowIndex(),
                local.columnIndex() == null ? parent.tableColumn() : local.columnIndex(),
                local.rowSpan() == null ? parent.rowSpan() : local.rowSpan(),
                local.columnSpan() == null ? parent.columnSpan() : local.columnSpan());
    }

    private List<Fragment> fragments(ClauseNode node) {
        String text = node.text();
        if (text.isBlank()) return List.of();
        List<Fragment> fragments = new ArrayList<>();
        Matcher matcher = SENTENCE_BREAK.matcher(text);
        int start = 0;
        int index = 0;
        while (matcher.find()) {
            // A sentence such as “见第六章中《投标函》” is the identity of the material
            // required by the immediately preceding submission verb, not another obligation.
            // Keep the exact contiguous source so neither half loses its object or action.
            String preceding=text.substring(start,matcher.start());
            String following=text.substring(matcher.end()).stripLeading();
            if(containsAny(preceding,"提交","提供","出具","附具")
                    &&following.matches("(?s)^(?:见|详见)第[一二三四五六七八九十0-9]+章中?《[^》]+》.*"))continue;
            addFragment(fragments, node, text, start, matcher.start(), index++);
            start = matcher.end();
        }
        addFragment(fragments, node, text, start, text.length(), index);
        return fragments;
    }

    private void addFragment(List<Fragment> target, ClauseNode node, String text, int start, int end, int index) {
        String raw = text.substring(start, end);
        int leading = 0;
        while (leading < raw.length() && Character.isWhitespace(raw.charAt(leading))) leading++;
        int trailing = raw.length();
        while (trailing > leading && Character.isWhitespace(raw.charAt(trailing - 1))) trailing--;
        if (leading == trailing) return;
        int actualStart = start + leading;
        int actualEnd = start + trailing;
        String value = text.substring(actualStart, actualEnd);
        SourceRef ref = fragmentRef(node.sourceRef(), value, actualStart, actualEnd);
        target.add(new Fragment(node.id() + ":f" + index, value, actualStart, actualEnd, ref));
    }

    private SourceRef fragmentRef(SourceRef parent, String quote, int localStart, int localEnd) {
        Integer start = parent.startOffset() == null ? null : parent.startOffset() + localStart;
        Integer end = parent.startOffset() == null ? null : parent.startOffset() + localEnd;
        return new SourceRef(parent.documentId(), parent.documentVersion(), parent.documentHash(),
                parent.parserFingerprint(), sha256(quote), parent.locationType(), parent.location(), quote, start, end);
    }

    private CoverageItem coverage(Fragment fragment, ClauseNode node, SourceContext context,
                                  CoverageItem.Status status, String draftId, List<String> templateIds,
                                  String reasonCode, String explanation) {
        return new CoverageItem(fragment.id(), node.id(), fragment.text(), fragment.sourceRef(), context,
                status, draftId, templateIds, reasonCode, explanation);
    }

    private void reconcileChecklistCoverage(Accumulator accumulator) {
        Map<String, RequirementDraft> checklistSources = new LinkedHashMap<>();
        for (RequirementDraft draft : accumulator.drafts) {
            Optional<TemplateCandidate> checklist = draft.templateCandidates().stream()
                    .filter(candidate -> "SECTION_CHECKLIST".equals(candidate.templateId())).findFirst();
            if (checklist.isEmpty()) continue;
            String ownSource = coverageSourceIdentity(draft.requirementRefs().get(0));
            for (SourceRef reference : checklist.get().requirementRefs()) {
                if (!coverageSourceIdentity(reference).equals(ownSource)) {
                    checklistSources.put(coverageSourceIdentity(reference), draft);
                }
            }
        }
        if (checklistSources.isEmpty()) return;
        Set<String> supersededDrafts = new LinkedHashSet<>();
        for (int index = 0; index < accumulator.coverage.size(); index++) {
            CoverageItem current = accumulator.coverage.get(index);
            RequirementDraft parent = checklistSources.get(coverageSourceIdentity(current.sourceRef()));
            if (parent == null || current.status() == CoverageItem.Status.MAPPED
                    || current.status() == CoverageItem.Status.EXTERNAL_REQUIRED
                    || current.status() == CoverageItem.Status.NEEDS_CONFIRMATION) continue;
            if (!current.draftId().isBlank()) supersededDrafts.add(current.draftId());
            accumulator.coverage.set(index, new CoverageItem(current.fragmentId(), current.clauseId(), current.text(),
                    current.sourceRef(), current.sourceContext(), CoverageItem.Status.MAPPED, parent.id(),
                    List.of("SECTION_CHECKLIST"), "CHECKLIST_ITEM_BOUND_TO_PARENT",
                    "该结构化子项已作为父级清单的独立组成项登记"));
        }
        if (!supersededDrafts.isEmpty()) {
            accumulator.drafts.removeIf(draft -> supersededDrafts.contains(draft.id()));
        }
    }

    private String sourceIdentity(SourceRef source) {
        return source.documentId() + "|" + source.documentVersion() + "|" + source.locationType() + "|"
                + source.location() + "|" + source.startOffset() + "|" + source.endOffset() + "|" + source.quote();
    }

    private String coverageSourceIdentity(SourceRef source) {
        String normalizedQuote = source.quote().strip().replaceAll("[。；;，,：:]$", "").strip();
        return source.documentId() + "|" + source.documentVersion() + "|" + source.locationType() + "|"
                + source.location() + "|" + normalizedQuote;
    }

    private boolean isNonChecking(ClauseNode node, String text) {
        if (text == null || text.isBlank()) return true;
        if (OBLIGATION.matcher(text).find()) return false;
        if (node.kind() == ClauseNode.Kind.DOCUMENT || node.kind() == ClauseNode.Kind.SECTION
                || node.kind() == ClauseNode.Kind.TABLE) return true;
        return text.matches("^(注|说明|备注|定义)[:：].*") || text.length() < 4;
    }

    private boolean looksLikeChecklist(ClauseNode node, String text) {
        return node.children().size() >= 2 && containsAny(text, "包括以下", "应包括", "由以下", "下列", "组成", "清单");
    }

    private List<String> alternatives(String text) {
        if (!containsAny(text, "或者", "或", "任选", "任一")) return List.of();
        String normalized = text.replace("任选其一", "或").replace("任选一种", "或")
                .replace("任一", "或").replace("或者", "或");
        List<String> parts = Pattern.compile("或").splitAsStream(normalized)
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        if (parts.size() < 2) return List.of();
        List<String> materials = new ArrayList<>();
        for (String part : parts) {
            String material = firstContained(part, MATERIALS);
            if (material.isBlank()) material = stripRequirementVerb(part);
            if (!material.isBlank()) materials.add(material);
        }
        return materials.stream().distinct().toList();
    }

    private String stripRequirementVerb(String value) {
        return value.replaceFirst("^.*?(提供|提交|附具|附上)", "")
                .replaceAll("[，,。；;].*$", "").trim();
    }

    private String role(String text, SourceContext context) {
        String role = firstContained(text, ROLES);
        return role.isBlank() ? context.subjectRole() : role;
    }

    private String issuerRole(String text, SourceContext context) {
        String explicit = firstContained(text, List.of("联合体牵头人", "联合体成员", "法定代表人", "投标人"));
        return explicit.isBlank() ? context.subjectRole() : explicit;
    }

    private String sealLocation(String text) {
        if (containsAny(text, "投标人公章", "单位公章")) return firstContained(text, List.of("投标人公章", "单位公章"));
        if (containsAny(text, "每页盖章", "逐页盖章")) return "EACH_PAGE";
        if (containsAny(text, "盖章处", "签章处")) return "DESIGNATED_POSITION";
        return containsAny(text, "盖章", "加盖", "签章") ? "REQUIREMENT_POSITION" : "";
    }

    private String extractDate(String text) {
        Matcher matcher = DATE.matcher(text);
        if (!matcher.find()) return "";
        int year = Integer.parseInt(matcher.group(1));
        if (year < 100) year += 2000;
        return String.format(Locale.ROOT, "%04d-%02d-%02d", year,
                Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
    }

    private String extractGrade(String text) {
        return firstContained(text, List.of("正高级", "高级", "中级", "初级", "一级", "二级"));
    }

    private String priceField(String text) {
        if (containsAny(text, "单价")) return "bid.unitPrice";
        if (containsAny(text, "分项")) return "bid.itemPrice";
        if (containsAny(text, "投标保证金", "保证金")) return "bid.securityAmount";
        return "bid.totalPrice";
    }

    private String priceLayer(String text) {
        if (containsAny(text, "单价")) return "UNIT";
        if (containsAny(text, "分项")) return "ITEM";
        return "TOTAL";
    }

    private String taxBasis(String text) {
        if (containsAny(text, "不含税", "税前")) return "TAX_EXCLUDED";
        if (containsAny(text, "含税", "税后")) return "TAX_INCLUDED";
        return "";
    }

    private List<String> entityFields(String text) {
        List<String> fields = new ArrayList<>();
        Map<String, String> vocabulary = new LinkedHashMap<>();
        vocabulary.put("企业名称", "organization.name");
        vocabulary.put("单位名称", "organization.name");
        vocabulary.put("投标人名称", "organization.name");
        vocabulary.put("项目名称", "project.name");
        vocabulary.put("项目编号", "project.code");
        vocabulary.put("合同编号", "contract.code");
        vocabulary.put("证书编号", "certificate.number");
        vocabulary.put("注册单位", "certificate.registeredOrganization");
        vocabulary.put("身份证号", "person.identityNumber");
        vocabulary.put("姓名", "person.name");
        vocabulary.put("金额", "price.amount");
        vocabulary.put("报价", "price.amount");
        vocabulary.put("日期", "document.date");
        for (Map.Entry<String, String> entry : vocabulary.entrySet()) {
            if (text.contains(entry.getKey())) fields.add(entry.getValue());
        }
        return fields.stream().distinct().toList();
    }

    private String durationField(String label) {
        if (label.equals("投标有效期")) return "bidValidity";
        if (label.contains("设计")) return "designDuration";
        if (label.contains("施工")) return "constructionDuration";
        if (label.contains("安装")) return "installationDuration";
        if (label.contains("调试")) return "commissioningDuration";
        return "duration";
    }

    private String canonicalDurationUnit(String unit) {
        if (unit.contains("工作日")) return "工作日";
        if (unit.contains("月")) return "月";
        if (unit.contains("年")) return "年";
        return "日";
    }

    private String durationCalendarBasis(String unit) {
        if (unit.contains("工作日")) return "WORKDAY";
        if (unit.contains("月")) return "CALENDAR_MONTH";
        if (unit.contains("年")) return "CALENDAR_YEAR";
        return "CALENDAR_DAY";
    }

    private String durationStartEvent(String text) {
        return firstContained(text, List.of("合同签订之日", "合同签订后", "开工令发出之日", "开工之日",
                "中标通知书发出之日", "收到订单之日", "投标截止之日"));
    }

    private String crossDocumentField(String text) {
        if (containsAny(text, "企业名称", "单位名称", "投标人名称")) return "companyName";
        if (containsAny(text, "统一社会信用代码", "信用代码")) return "creditCode";
        if (containsAny(text, "项目名称", "工程名称")) return "projectName";
        if (containsAny(text, "项目编号", "招标编号", "采购编号")) return "projectNumber";
        if (containsAny(text, "总报价", "投标报价", "投标总价")) return "totalPrice";
        if (containsAny(text, "工期", "交货期", "服务期限")) return "duration";
        if (containsAny(text, "投标日期", "签署日期", "落款日期")) return "documentDate";
        return "";
    }

    private boolean explicitCrossDocument(String text) {
        long documents = List.of("招标文件", "投标文件", "营业执照", "资质证书", "合同", "投标函", "报价表")
                .stream().filter(text::contains).count();
        return documents >= 2;
    }

    private String formType(String text, SourceContext context) {
        String explicit = firstContained(text, List.of("开标一览表", "投标函附录", "投标函", "报价表",
                "法定代表人身份证明", "授权委托书", "资格审查表", "人员配置表", "业绩表", "表格", "表单"));
        if (!explicit.isBlank()) return explicit;
        return context.sectionPath().isEmpty() ? "" : context.sectionPath().get(context.sectionPath().size() - 1);
    }

    private List<String> requiredFormFields(ClauseNode node, String text) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        List<String> vocabulary = List.of("项目名称", "项目编号", "投标人名称", "统一社会信用代码", "投标报价",
                "总报价", "工期", "日期", "法定代表人", "委托代理人", "姓名", "证书编号");
        List<String> fragments = new ArrayList<>();fragments.add(text);
        node.children().stream().map(ClauseNode::text).map(String::strip).filter(value -> !value.isBlank())
                .forEach(fragments::add);
        for (String fragment : fragments) {
            for (String field : vocabulary) if (fragment.contains(field)) fields.add(field);
            String label = fragment.replaceAll("^[（(]?[0-9一二三四五六七八九十]+[）).、．.]?\\s*", "")
                    .replaceAll("[（(](?:必填|必选|不得留空)[）)]", "").strip();
            int separator = Math.max(label.indexOf('：'), label.indexOf(':'));
            if (separator > 0) label = label.substring(0, separator).strip();
            if (formFieldLabel(label)) fields.add(label);
        }
        return List.copyOf(fields);
    }

    private boolean formFieldLabel(String value) {
        if (value.isBlank() || value.length() > 30 || value.matches(".*[，,。；;！？!?].*")) return false;
        return !value.matches("(?s).*(?:应当|应|须|必须|不得|填写|完整|留空|空白|提供|提交|说明|要求).*" );
    }

    private String expectedFormSubject(String text) {
        Matcher matcher = Pattern.compile("(?:投标人|投标单位|供应商)(?:名称)?\\s*(?:应为|应填写|[:：=])\\s*([^，,。；;]{2,80})")
                .matcher(text);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private DateRelationMatch dateRelation(String text) {
        Matcher direct = DATE_RELATION_DIRECT.matcher(text);
        if (direct.find())
            return new DateRelationMatch(direct.group(1), direct.group(3), dateOperator(direct.group(2)));

        Matcher equality = DATE_RELATION_EQUAL.matcher(text);
        if (equality.find()) return new DateRelationMatch(equality.group(1), equality.group(2), "=");

        Matcher position = DATE_RELATION_POSITION.matcher(text);
        if (position.find())
            return new DateRelationMatch(position.group(1), position.group(2), dateOperator(position.group(3)));
        return null;
    }

    private String dateOperator(String relation) {
        if (containsAny(relation, "不得晚于", "不应晚于", "不晚于")) return "<=";
        if (containsAny(relation, "不得早于", "不应早于", "不早于")) return ">=";
        if (containsAny(relation, "早于", "之前", "以前")) return "<";
        if (containsAny(relation, "晚于", "之后", "以后")) return ">";
        if (containsAny(relation, "等于", "等同于", "一致", "相同")) return "=";
        return "";
    }

    private record DateRelationMatch(String leftRole, String rightRole, String operator) {}

    private String textScope(String text, SourceContext context) {
        if (containsAny(text, "全文", "整份", "全部内容")) return "全文";
        for (String type : List.of("投标函", "报价表", "施工组织设计", "技术方案", "资格文件", "商务文件"))
            if (text.contains(type)) return type;
        return context.sectionPath().isEmpty() ? "全文" : context.sectionPath().get(context.sectionPath().size() - 1);
    }

    private List<String> anomalyTypes(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (containsAny(text, "错别字", "缺字", "多字")) result.add("TYPO");
        if (containsAny(text, "截断", "缺失")) result.add("TRUNCATED");
        if (containsAny(text, "乱码", "OCR错误", "OCR异常")) result.add("OCR_SUSPECT");
        return result.isEmpty() ? List.of("TYPO", "TRUNCATED", "OCR_SUSPECT") : List.copyOf(result);
    }

    private boolean objectiveScoring(String text) {
        return SCORE_VALUE.matcher(text).find()
                && containsAny(text, "每项", "每个", "满足", "具备", "具有", "持有", "提供", "增加",
                "达到", "得分", "计分")
                && !containsAny(text, "合理性", "科学性", "可行性", "先进性", "优劣", "酌情", "横向比较");
    }

    private String maximumScore(String text) {
        Matcher explicit = Pattern.compile("(?:最高|上限|满分)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*分").matcher(text);
        if (explicit.find()) return explicit.group(1);
        Matcher score = SCORE_VALUE.matcher(text);
        return score.find() ? score.group(1) : "";
    }

    private String scoringCriterion(String text) {
        String value = text.replaceAll("(?:最高|上限|满分)?\\s*[0-9]+(?:\\.[0-9]+)?\\s*分", "")
                .replaceAll("^(?:投标人|供应商|响应人)[应须必须]*", "")
                // Removing the numeric award from “具备某证书，得1分” must not leave a
                // dangling “得/计” in the criterion identity.  The source quote still retains
                // the complete scoring rule for evidence compilation and audit.
                .replaceAll("(?:得|计)\\s*[，,。；;]?\\s*$", "")
                .replaceAll("[，,。；;]\\s*$", "").strip();
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private boolean binaryMaterialScoreRule(String text) {
        if (containsAny(text, "每项", "每个", "每增加", "每新增", "每提供", "每具备", "每持有",
                "累计", "累加", "分别", "酌情", "横向比较") || PERSON_COUNT.matcher(text).find()) return false;
        Pattern materialAward = Pattern.compile(
                "(?:具备|具有|提供|持有)\\s*[^，,。；;\\r\\n]{2,80}?(?:认证证书|资格证书|资质证书|许可证|证书|资质|证明材料|证明)"
                        + "\\s*(?:的)?\\s*[,，]?\\s*(?:得|计)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*分");
        Matcher matcher = materialAward.matcher(text);
        if (!matcher.find()) return false;
        String awarded = matcher.group(1);
        if (matcher.find()) return false;

        // A tiered rule can share one leading verb (for example “具备一级资质得3分，二级资质得1分”),
        // so counting material-pattern matches alone is insufficient. More than one positive award is
        // not a binary presence check and must keep the closed-evidence-set guard.
        Matcher awards = Pattern.compile("(?:得|计)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*分").matcher(text);
        int positiveAwards = 0;
        while (awards.find()) {
            if (new java.math.BigDecimal(awards.group(1)).signum() > 0) positiveAwards++;
        }
        if (positiveAwards != 1) return false;

        Matcher maximum = Pattern.compile("(?:最高(?:得)?|上限|满分)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*分")
                .matcher(text);
        return !maximum.find() || new java.math.BigDecimal(maximum.group(1))
                .compareTo(new java.math.BigDecimal(awarded)) <= 0;
    }

    private boolean qualityAdvisory(String text) {
        return containsAny(text, "合理性", "科学性", "可行性", "先进性", "完整性", "针对性", "优劣", "酌情", "横向比较")
                && containsAny(text, "方案", "设计", "组织", "措施", "建议", "评分", "评审");
    }

    private String qualityMaterial(String text, SourceContext context) {
        String type = firstContained(text, List.of("施工组织设计", "技术方案", "实施方案", "设计方案",
                "服务方案", "质量保证措施", "进度计划", "方案"));
        if (!type.isBlank()) return type;
        return context.sectionPath().isEmpty() ? "" : context.sectionPath().get(context.sectionPath().size() - 1);
    }

    private List<String> qualityDimensions(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String dimension : List.of("合理性", "科学性", "可行性", "先进性", "完整性", "针对性",
                "安全措施", "质量措施", "进度计划", "资源配置")) if (text.contains(dimension)) result.add(dimension);
        Matcher count = Pattern.compile("([2-9]|10)\\s*项(?:内容|要素|指标|方面)?").matcher(text);
        if (count.find()) {
            int expected = Integer.parseInt(count.group(1));
            String prefix = text.substring(Math.max(0, count.start() - 180), count.start());
            int colon = Math.max(prefix.lastIndexOf('：'), prefix.lastIndexOf(':'));
            if (colon >= 0) prefix = prefix.substring(colon + 1);
            prefix = prefix.replaceFirst("^\\s*(?:对)?(?:本项目|本工程)?的?", "").strip();
            List<String> sections = java.util.Arrays.stream(prefix.split("[、,，]|(?:以及|及|和)"))
                    .map(String::strip).map(value -> value.replaceFirst("^(?:包括|包含|涵盖)\\s*", ""))
                    .filter(value -> value.length() >= 2 && value.length() <= 28).toList();
            if (sections.size() == expected && sections.stream().map(this::normalizeText).distinct().count() == sections.size()) {
                result.remove("完整性");
                result.addAll(sections);
            }
        }
        return List.copyOf(result);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Z}《》〈〉<>（）()【】\\[\\]：:、,，。;；_—–\\-]", "");
    }

    private String externalQueryObject(String text, SourceContext context) {
        String value = firstContained(text, List.of("投标人", "供应商", "响应人", "联合体成员", "项目负责人", "法定代表人"));
        return value.isBlank() ? context.subjectRole() : value;
    }

    private List<String> externalRequiredFields(String text) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (containsAny(text, "失信被执行人", "被执行")) fields.add("dishonestJudgmentDebtor");
        if (containsAny(text, "重大税收违法", "税收违法")) fields.add("majorTaxViolation");
        if (containsAny(text, "行政处罚")) fields.add("administrativePenalty");
        if (containsAny(text, "经营异常")) fields.add("abnormalOperation");
        if (containsAny(text, "信用记录", "信用信息")) fields.add("creditStatus");
        if (fields.isEmpty()) fields.add("queryResult");
        return List.copyOf(fields);
    }

    private String externalSource(String text) {
        return firstContained(text, List.of("国家企业信用信息公示系统", "中国执行信息公开网", "信用中国", "开标现场", "外部平台"));
    }

    private String externalTime(String text) {
        if (containsAny(text, "开标现场", "开标时", "开标当日")) return "BID_OPENING";
        if (containsAny(text, "投标截止")) return "TENDER_DEADLINE";
        return "";
    }

    private static String normalizeSource(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\p{Z}]+", "").toLowerCase(Locale.ROOT);
    }

    private String sectionTitle(ClauseNode node) {
        if (!node.localContext().sectionTitle().isBlank()) return node.localContext().sectionTitle();
        return node.kind() == ClauseNode.Kind.SECTION ? node.text() : "";
    }

    private List<String> append(List<String> existing, String value) {
        if (value == null || value.isBlank()) return existing;
        List<String> result = new ArrayList<>(existing);
        result.add(value.trim());
        return List.copyOf(result);
    }

    private String inherit(String local, String parent) {
        return local == null || local.isBlank() ? parent : local.trim();
    }

    private List<String> missing(Map<String, Object> parameters, String... required) {
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            Object value = parameters.get(name);
            if (value == null || value instanceof String string && string.isBlank()
                    || value instanceof Collection<?> collection && collection.isEmpty()) missing.add(name);
        }
        return missing;
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            Object value = values[index + 1];
            if (value == null || value instanceof String string && string.isBlank()
                    || value instanceof Collection<?> collection && collection.isEmpty()) continue;
            result.put(String.valueOf(values[index]), value);
        }
        return Map.copyOf(result);
    }

    private String firstContained(String text, List<String> values) {
        return values.stream().filter(text::contains).findFirst().orElse("");
    }

    private boolean containsAny(String text, String... values) {
        if (text == null) return false;
        for (String value : values) if (value != null && !value.isBlank() && text.contains(value)) return true;
        return false;
    }

    private int chineseNumber(String value) {
        if (value.chars().allMatch(Character::isDigit)) return Integer.parseInt(value);
        Map<Character, Integer> digits = Map.of('一', 1, '二', 2, '两', 2, '三', 3, '四', 4,
                '五', 5, '六', 6, '七', 7, '八', 8, '九', 9);
        if ("十".equals(value)) return 10;
        int ten = value.indexOf('十');
        if (ten >= 0) {
            int left = ten == 0 ? 1 : digits.getOrDefault(value.charAt(ten - 1), 0);
            int right = ten + 1 >= value.length() ? 0 : digits.getOrDefault(value.charAt(ten + 1), 0);
            return left * 10 + right;
        }
        return digits.getOrDefault(value.charAt(0), 0);
    }

    private String coverageExplanation(CoverageItem.Status status) {
        return switch (status) {
            case MAPPED -> "本地规则已生成来源绑定的模板草稿，仍需确认后才能执行";
            case NEEDS_CONFIRMATION -> "存在多个候选或关键参数尚未绑定";
            case EXTERNAL_REQUIRED -> "要求已登记，但结论依赖指定外部数据或时点";
            default -> "";
        };
    }

    private void validateUniqueIds(List<ClauseNode> roots) {
        Set<String> ids = new LinkedHashSet<>();
        List<ClauseNode> pending = new ArrayList<>(roots);
        while (!pending.isEmpty()) {
            ClauseNode node = pending.remove(pending.size() - 1);
            if (!ids.add(node.id())) throw new IllegalArgumentException("duplicate clause id: " + node.id());
            pending.addAll(node.children());
        }
    }

    private String stableId(String prefix, String value) { return prefix + "-" + sha256(value).substring(0, 24); }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format(Locale.ROOT, "%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Fragment(String id, String text, int start, int end, SourceRef sourceRef) { }

    private static final class Accumulator {
        private final List<RequirementDraft> drafts = new ArrayList<>();
        private final List<CoverageItem> coverage = new ArrayList<>();
        private int classifierCalls;
        private int clauseCount;
    }
}
