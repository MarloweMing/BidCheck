package cn.bidcheck.checktemplate;

import cn.bidcheck.checktemplate.evidence.EvidenceNeedCoordinator;
import cn.bidcheck.checktemplate.evidence.InMemoryEvidenceTaskRepository;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Fact;
import cn.bidcheck.domain.Domain.Page;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression contract for the evidence boundaries exposed by the first real-file run of the
 * eight fixed templates.  The negative half of every test is as important as the positive half:
 * a complete OCR document is not, by itself, proof that a business evidence scope is closed.
 */
class EightTemplateEvidenceBoundaryRegressionTest {
    private final TemplateEvidenceAssembler assembler = new TemplateEvidenceAssembler();
    private final TemplateCheckExecutor executor =
            new TemplateCheckExecutor(CheckTemplateRegistry.defaultRegistry());
    private final SourceRef requirement = new SourceRef("tender", 1, "tender-sha", "TEXT",
            "requirement", "PAGE", 1, "招标要求", null, null);

    @Test void durationScopeRequiresCurrentValueAndEveryRequestedBasis() {
        Document bid = bid(
                page(1, "本项目投标函附录\n第A标段 总工期：90日历日，自开工之日起计算。"),
                page(2, "历史业绩合同\n第B标段 合同工期：120日历日，自合同签订之日起计算。"));
        CheckSpec spec = spec("DURATION_COMPARE", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("field", "duration", "operator", "<=", "expected", "100", "unit", "日", "startEvent", "开工之日", "calendarBasis", "CALENDAR_DAY", "lot", "A", "tolerance", "0"));

        Document historyBid = bid(page(1,
                "历史业绩合同\n第B标段 合同工期：120日历日，自合同签订之日起计算。"));
        TemplateEvidenceAssembler.Assembly historyOnly = assemble(spec, historyBid, cn.bidcheck.compat.Java8Api.listOf(fact("TIME_REQUIREMENT", "TIME_REQUIREMENT", "duration",
                        "第B标段 合同工期：120日历日，自合同签订之日起计算。", "120", 1,
                        "duration-history")));
        assertFalse(historyOnly.evidenceView().closedNeedKeys().contains(spec.id() + ":duration"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED, conclusion(spec, historyOnly));

        TemplateEvidenceAssembler.Assembly current = assemble(spec, bid, cn.bidcheck.compat.Java8Api.listOf(fact("TIME_REQUIREMENT", "TIME_REQUIREMENT", "duration",
                        "第A标段 总工期：90日历日，自开工之日起计算。", "90", 1,
                        "duration-current")));
        assertTrue(current.evidenceView().closedNeedKeys().contains(spec.id() + ":duration"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(spec, current));

        TemplateEvidenceAssembler.Assembly acceptedRequirement = assemble(spec,
                bid(page(1, "本项目投标函\n我方承诺工期为 满足（填写‘满足’）。")), cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(acceptedRequirement.evidenceView().closedNeedKeys().contains(spec.id() + ":duration"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(spec, acceptedRequirement));
        assertTrue(acceptedRequirement.evidenceView().observations(Observation.PriceObservation.class).stream()
                .anyMatch(value -> value.sources().size() >= 2), "响应证据必须同时保留投标承诺和招标参数来源");
    }

    @Test void crossDocumentCoverageCountsIndependentCurrentBidLocationsAndRejectsNoise() {
        CheckSpec spec = spec("CROSS_DOCUMENT_CONSISTENCY",
                TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                cn.bidcheck.compat.Java8Api.mapOf("field", "companyName", "minimumOccurrences", 2, "caseSensitive", false));
        Document oneLocation = bid(page(1, "本项目投标函\n投标人名称：海南测试有限公司"));
        Fact duplicateA = fact("BIDDER", "BIDDER", "companyName", "投标人名称：海南测试有限公司",
                "海南测试有限公司", 1, "same-location");
        Fact duplicateB = fact("BIDDER", "BIDDER", "companyName", "投标人名称：海南测试有限公司",
                "海南测试有限公司", 1, "same-location");
        TemplateEvidenceAssembler.Assembly duplicateOnly = assemble(spec, oneLocation,
                cn.bidcheck.compat.Java8Api.listOf(duplicateA, duplicateB));
        assertFalse(duplicateOnly.evidenceView().closedNeedKeys().contains(spec.id() + ":field-values"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED, conclusion(spec, duplicateOnly));

        Document bid = bid(
                page(1, "本项目投标函\n投标人名称：海南测试有限公司"),
                page(2, "本项目投标人基本情况\n企业名称：海南测试有限公司"),
                page(3, "本项目联合体成员基本情况\n企业名称：海南成员有限公司"),
                page(4, "历史业绩合同\n企业名称：福建旧项目公司"),
                new Page(5, "本项目附件\n企业名称：海南申试有限公司\uFFFD", "PAGE",
                        "QWEN_PDF_OCR", "OCR低置信度：企业名称疑似转录错误"));
        Document tender = document("tender", "TENDER", "招标文件.docx",
                page(1, "投标文件格式\n投标人名称：____"));
        TemplateEvidenceAssembler.Assembly covered = assemble(spec, bid, tender, cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(covered.evidenceView().closedNeedKeys().contains(spec.id() + ":field-values"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(spec, covered));
        assertTrue(covered.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.commitmentPoints().stream())
                .filter(value -> value.startsWith("VALUE:"))
                .allMatch(value -> value.equals("VALUE:海南测试有限公司")));
    }

    @Test void formFieldsCannotBeJoinedAcrossDifferentInstances() {
        CheckSpec spec = spec("FORM_FIELD_COMPLETENESS", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("formType", "投标函", "requiredFields", cn.bidcheck.compat.Java8Api.listOf("项目名称", "投标人名称")));
        Document split = bid(
                page(1, "本项目投标函（第一联）\n投标函\n项目名称：海南项目"),
                page(2, "本项目投标函（第二联）\n投标函\n投标人名称：海南测试有限公司"));
        TemplateEvidenceAssembler.Assembly splitResult = assemble(spec, split, cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED, conclusion(spec, splitResult));

        Document oneInstance = bid(page(1,
                "本项目投标函\n投标函\n项目名称：海南项目\n投标人名称：海南测试有限公司"));
        TemplateEvidenceAssembler.Assembly complete = assemble(spec, oneInstance, cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(spec, complete));

        CheckSpec invoice = spec("FORM_FIELD_COMPLETENESS", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("formType", "代理费开票信息", "requiredFields", cn.bidcheck.compat.Java8Api.listOf("单位名称", "纳税人识别号", "发票邮寄地址、联系人、电话或电子发票接收邮箱")));
        Document syntheticOcrWrap = bid(page(1, "代理费开票信息\n单位名称 合成装饰有限公司\n"
                + "纳税人识别号 91350000123456789X\n"
                + "发票邮寄地址、联系人、 合成市测试路1号、\n"
                + "电话或电子发票接收邮箱 测试联系人、13900000000、test@example.com"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                conclusion(invoice, assemble(invoice, syntheticOcrWrap, cn.bidcheck.compat.Java8Api.listOf())));
    }

    @Test void dateRelationKeepsLeftAndRightRolesInTheirDocumentBoundaries() {
        CheckSpec spec = spec("DATE_RELATION", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("leftRole", "投标日期", "rightRole", "开标日期", "operator", "<="));
        Document bid = bid(page(1, "本项目投标函\n投标日期：2026年10月18日\n开标日期：2026年10月10日"));

        TemplateEvidenceAssembler.Assembly bidOnly = assemble(spec, bid, null, cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(1, bidOnly.evidenceView().observations(Observation.CertificateObservation.class).size());
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED, conclusion(spec, bidOnly));

        Document tender = document("tender", "TENDER", "招标文件.docx",
                page(1, "开标日期：2026年10月20日"));
        TemplateEvidenceAssembler.Assembly roleBound = assemble(spec, bid, tender, cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(2, roleBound.evidenceView().observations(Observation.CertificateObservation.class).size());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(spec, roleBound));

        CheckSpec sameTime = spec("DATE_RELATION", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("leftRole", "开标时间", "rightRole", "投标截止时间", "operator", "="));
        Document linkedTender = document("tender", "TENDER", "招标文件.docx",
                page(100, "投标截止时间为 2025年08月05日09时30分"),
                page(178, "开标时间和地点 同投标文件递交截止时间"));
        TemplateEvidenceAssembler.Assembly linked = assemble(sameTime, bid, linkedTender, cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(sameTime, linked));
        assertTrue(linked.evidenceView().observations(Observation.CertificateObservation.class).stream()
                .filter(value -> value.certificateType().equals("DATE:开标时间"))
                .anyMatch(value -> value.sources().size() >= 2), "同值传播必须同时保留截止日期和同值条款来源");
    }

    @Test void wholeTextScanClosesOnlyAfterEveryPageAndDetectorAreComplete() {
        CheckSpec spec = spec("TEXT_ANOMALY", TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                cn.bidcheck.compat.Java8Api.mapOf("textScope", "全文", "anomalyTypes", cn.bidcheck.compat.Java8Api.listOf("TRUNCATED", "OCR_SUSPECT")));
        Document partial = new Document("bid", "project", "BID", "投标文件.pdf", 3,
                "bid-sha", "2026-10-19T00:00:00Z",
                cn.bidcheck.compat.Java8Api.listOf(page(1, "本项目投标文件正文"), new Page(2, "[OCR_DEFERRED]", "PAGE", "QWEN_PDF_OCR", "[OCR未完成]")),
                cn.bidcheck.compat.Java8Api.listOf());
        TemplateEvidenceAssembler.Assembly pending = assemble(spec, partial, cn.bidcheck.compat.Java8Api.listOf());
        assertFalse(pending.evidenceView().closedNeedKeys().contains(spec.id() + ":text-scan"));
        // The deferred marker is itself a useful OCR-suspect candidate, but an incomplete scan can
        // never be presented as a clean, closed-range result.
        assertNotEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(spec, pending));

        Document complete = bid(page(1, "本项目投标文件正文"), page(2, "附件文字清晰完整"));
        TemplateEvidenceAssembler.Assembly clear = assemble(spec, complete, cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(clear.evidenceView().closedNeedKeys().contains(spec.id() + ":text-scan"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(spec, clear));

        TemplateEvidenceAssembler.Assembly prose = assemble(spec,
                bid(page(1, "本项目文档说明字符截断的处理规则，正文仍完整可读。")), cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(spec, prose),
                "普通业务文字中的‘截断’不能伪装成 OCR 流水线失败");

        CheckSpec sectionSpec = spec("TEXT_ANOMALY", TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                cn.bidcheck.compat.Java8Api.mapOf("textScope", "投标函", "anomalyTypes", cn.bidcheck.compat.Java8Api.listOf("TRUNCATED", "OCR_SUSPECT")));
        Document tableOfContentsOnly = bid(
                page(1, "目录\n投标函................20\n资格证明................30"),
                page(2, "本项目资格证明正文，文字清晰完整。"));
        TemplateEvidenceAssembler.Assembly noBody = assemble(sectionSpec, tableOfContentsOnly, cn.bidcheck.compat.Java8Api.listOf());
        assertFalse(noBody.evidenceView().closedNeedKeys().contains(sectionSpec.id() + ":text-scan"),
                "目录命中只能定位线索，不能证明指定章节正文已完成扫描");
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED, conclusion(sectionSpec, noBody));
    }

    @Test void objectiveScoreNeedsAnExplicitCriterionBoundClosedRange() {
        CheckSpec spec = spec("OBJECTIVE_SCORING", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("criterion", "企业业绩", "aggregation", "SUM", "maxScore", "6", "scoreUnit", "分", "deduplicateBySubject", true));
        TemplateEvidenceAssembler.Assembly historical = assemble(spec,
                bid(page(1, "本项目投标文件正文"),
                        page(2, "历史业绩评分记录\n企业业绩本项实际得分：4分。")), cn.bidcheck.compat.Java8Api.listOf());
        assertFalse(historical.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED, conclusion(spec, historical));

        TemplateEvidenceAssembler.Assembly ruleOnly = assemble(spec,
                bid(page(1, "本项目客观评分表\n企业业绩每项2分，最高6分。")), cn.bidcheck.compat.Java8Api.listOf());
        assertFalse(ruleOnly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED, conclusion(spec, ruleOnly));

        TemplateEvidenceAssembler.Assembly actual = assemble(spec,
                bid(page(1, "本项目客观评分结果\n企业业绩本项实际得分：4分。")), cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(actual.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(spec, actual));
    }

    @Test void qualityAdviceRequiresEveryRequestedDimensionAndNeverInventsCoverage() {
        CheckSpec spec = qualitySpec(cn.bidcheck.compat.Java8Api.mapOf("materialType", "技术方案", "dimensions", cn.bidcheck.compat.Java8Api.listOf("完整性", "可行性")));
        TemplateEvidenceAssembler.Assembly partial = assemble(spec,
                bid(page(1, "本项目技术方案\n技术方案正文已说明完整性。")), cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED, conclusion(spec, partial));

        TemplateEvidenceAssembler.Assembly covered = assemble(spec,
                bid(page(1, "本项目技术方案\n技术方案正文已说明完整性和可行性。")), cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(spec, covered));
        assertEquals(TemplateTypes.ActionOwner.USER,
                executor.execute(spec, covered.evidenceView()).result().actionOwner());
    }

    @Test void externalCheckAdmitsOnlyAuthenticatedRecordsMatchingAllBoundaryFields() {
        CheckSpec spec = externalSpec(cn.bidcheck.compat.Java8Api.mapOf("source", "信用中国", "queryObject", "投标人", "requiredFields", cn.bidcheck.compat.Java8Api.listOf("creditStatus", "abnormalOperation"), "asOfDate", "2026-10-19", "adapterId", "CREDIT_CHINA", "evaluationStage", "BID_OPENING"));
        Document bid = bid(page(1, "本项目投标人声明：信用状况良好。"));
        Fact wrongTime = connector("creditStatus", "CLEAR", "2026-10-18", "authenticated-wrong-time");

        TemplateEvidenceAssembler.Assembly rejected = assemble(spec, bid, cn.bidcheck.compat.Java8Api.listOf(wrongTime));
        assertTrue(rejected.evidenceView().observations().isEmpty());
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED, conclusion(spec, rejected));

        TemplateEvidenceAssembler.Assembly accepted = assemble(spec, bid, cn.bidcheck.compat.Java8Api.listOf(connector("creditStatus", "CLEAR", "2026-10-19", "authenticated-credit"), connector("abnormalOperation", "CLEAR", "2026-10-19", "authenticated-operation")));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(spec, accepted));
    }

    @Test void productionSingleFlightKeepsEightTemplateEvidenceInsteadOfDroppingIt() throws Exception {
        CheckTemplateRegistry registry = CheckTemplateRegistry.defaultRegistry();
        try (EvidenceNeedCoordinator coordinator = new EvidenceNeedCoordinator(
                new InMemoryEvidenceTaskRepository())) {
            TemplateEvidenceAssembler shared = new TemplateEvidenceAssembler(coordinator, registry);

            CheckSpec duration = spec("DURATION_COMPARE", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                    cn.bidcheck.compat.Java8Api.mapOf("field", "duration", "operator", "<=", "expected", "60", "unit", "日", "calendarBasis", "CALENDAR_DAY", "tolerance", "0"));
            assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(duration, shared.assemble(duration,
                    checkpoint(duration), bid(page(1, "本项目投标函\n总工期：60日历日")), null,
                    cn.bidcheck.compat.Java8Api.listOf(fact("TIME_REQUIREMENT", "TIME_REQUIREMENT", "duration",
                            "总工期：60日历日", "60", 1, "production-duration")))));

            CheckSpec consistency = spec("CROSS_DOCUMENT_CONSISTENCY",
                    TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                    cn.bidcheck.compat.Java8Api.mapOf("field", "companyName", "minimumOccurrences", 2, "caseSensitive", false));
            assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(consistency, shared.assemble(consistency,
                    checkpoint(consistency), bid(page(1, "本项目投标文件封面\n投标人名称：海南测试有限公司"),
                            page(2, "本项目投标人基本情况\n企业名称：海南测试有限公司")), null, cn.bidcheck.compat.Java8Api.listOf())));

            CheckSpec form = spec("FORM_FIELD_COMPLETENESS", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                    cn.bidcheck.compat.Java8Api.mapOf("formType", "投标函", "requiredFields", cn.bidcheck.compat.Java8Api.listOf("项目名称", "投标人名称")));
            assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(form, shared.assemble(form,
                    checkpoint(form), bid(page(1, "本项目投标函\n项目名称：海南项目\n投标人名称：海南测试有限公司")),
                    null, cn.bidcheck.compat.Java8Api.listOf())));

            CheckSpec dates = spec("DATE_RELATION", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                    cn.bidcheck.compat.Java8Api.mapOf("leftRole", "投标日期", "rightRole", "开标日期", "operator", "<="));
            assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(dates, shared.assemble(dates,
                    checkpoint(dates), bid(page(1, "本项目投标函\n投标日期：2026年10月18日")),
                    document("tender-flight", "TENDER", "招标文件.docx",
                            page(1, "开标日期：2026年10月20日")), cn.bidcheck.compat.Java8Api.listOf())));

            CheckSpec text = spec("TEXT_ANOMALY", TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                    cn.bidcheck.compat.Java8Api.mapOf("textScope", "全文", "anomalyTypes", cn.bidcheck.compat.Java8Api.listOf("TRUNCATED", "OCR_SUSPECT")));
            assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(text, shared.assemble(text,
                    checkpoint(text), bid(page(1, "本项目正文完整清晰")), null, cn.bidcheck.compat.Java8Api.listOf())));

            CheckSpec score = spec("OBJECTIVE_SCORING", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                    cn.bidcheck.compat.Java8Api.mapOf("criterion", "企业业绩", "aggregation", "SUM", "maxScore", "6", "scoreUnit", "分", "deduplicateBySubject", true));
            assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(score, shared.assemble(score,
                    checkpoint(score), bid(page(1, "本项目客观评分结果\n企业业绩本项实际得分：4分。")),
                    null, cn.bidcheck.compat.Java8Api.listOf())));

            CheckSpec quality = qualitySpec(cn.bidcheck.compat.Java8Api.mapOf("materialType", "技术方案", "dimensions", cn.bidcheck.compat.Java8Api.listOf("完整性", "可行性")));
            TemplateEvidenceAssembler.Assembly qualityAssembly = shared.assemble(quality,
                    checkpoint(quality), bid(page(1, "1.1 技术方案\n完整性：本方案覆盖施工准备、实施与验收。\n可行性：配备负责人并明确进度、资源与风险措施。")),
                    null, cn.bidcheck.compat.Java8Api.listOf());
            assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(quality, qualityAssembly),
                    () -> "quality single-flight evidence=" + qualityAssembly);

            CheckSpec external = externalSpec(cn.bidcheck.compat.Java8Api.mapOf("source", "信用中国", "queryObject", "投标人", "requiredFields", cn.bidcheck.compat.Java8Api.listOf("creditStatus", "abnormalOperation"), "asOfDate", "2026-10-19", "adapterId", "CREDIT_CHINA", "evaluationStage", "BID_OPENING"));
            assertEquals(TemplateTypes.ConclusionState.SATISFIED, conclusion(external, shared.assemble(external,
                    checkpoint(external), bid(page(1, "本项目投标文件")), null,
                    cn.bidcheck.compat.Java8Api.listOf(connector("creditStatus", "CLEAR", "2026-10-19", "flight-credit"), connector("abnormalOperation", "CLEAR", "2026-10-19", "flight-operation")))));
        }
    }

    private TemplateTypes.ConclusionState conclusion(CheckSpec spec,
                                                       TemplateEvidenceAssembler.Assembly assembly) {
        return executor.execute(spec, assembly.evidenceView()).projection().conclusion().state();
    }

    private TemplateEvidenceAssembler.Assembly assemble(CheckSpec spec, Document bid, List<Fact> facts) {
        return assemble(spec, bid, null, facts);
    }

    private TemplateEvidenceAssembler.Assembly assemble(CheckSpec spec, Document bid, Document tender,
                                                         List<Fact> facts) {
        return assembler.assemble(spec, checkpoint(spec), bid, tender, facts);
    }

    private CheckSpec spec(String template, TemplateTypes.ProofStandard standard,
                           Map<String, Object> parameters) {
        return spec(template, standard, TemplateTypes.VerificationMode.DOCUMENT, parameters);
    }

    private CheckSpec qualitySpec(Map<String, Object> parameters) {
        return spec("QUALITY_ADVISORY", TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                TemplateTypes.VerificationMode.HUMAN_JUDGMENT, parameters);
    }

    private CheckSpec externalSpec(Map<String, Object> parameters) {
        return spec("EXTERNAL_CHECK", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                TemplateTypes.VerificationMode.EXTERNAL, parameters);
    }

    private CheckSpec spec(String template, TemplateTypes.ProofStandard standard,
                           TemplateTypes.VerificationMode mode, Map<String, Object> parameters) {
        String id = "regression-" + template;
        List<ParameterBinding> bindings = cn.bidcheck.compat.Java8Api.toList(parameters.entrySet().stream()
                .map(entry -> new ParameterBinding(entry.getKey(), entry.getValue(), "TENDER_SOURCE",
                        "测试要求原文", cn.bidcheck.compat.Java8Api.listOf(requirement))));
        return new CheckSpec(id, 1, "item-" + id, "checkpoint-" + id,
                TemplateTypes.Category.QUALIFICATION, TemplateTypes.Purpose.MANDATORY,
                mode, template, "1.0", SubjectSelector.bidder(), parameters,
                Applicability.applies(), standard, cn.bidcheck.compat.Java8Api.listOf(requirement), bindings, cn.bidcheck.compat.Java8Api.mapOf(), cn.bidcheck.compat.Java8Api.mapOf(),
                cn.bidcheck.compat.Java8Api.mapOf("state", "FULL", "complete", true), true,
                Confirmation.legacyConfirmed(), "semantic-" + template);
    }

    private Checkpoint checkpoint(CheckSpec spec) {
        return new Checkpoint(spec.checkpointId(), spec.templateId(), "资格检查", "HIGH",
                "SEMANTIC_RESPONSE", "", "", "", "", true, true, true,
                new Evidence("tender", "招标文件", 1, "PAGE", "招标要求", "p1-l1", 1));
    }

    private static Document bid(Page... pages) {
        return new Document("bid", "project", "BID", "投标文件.pdf", 3, "bid-sha",
                "2026-10-19T00:00:00Z", cn.bidcheck.compat.Java8Api.listOf(pages), cn.bidcheck.compat.Java8Api.listOf());
    }

    private static Document document(String id, String role, String name, Page... pages) {
        return new Document(id, "project", role, name, 2, id + "-sha",
                "2026-10-19T00:00:00Z", cn.bidcheck.compat.Java8Api.listOf(pages), cn.bidcheck.compat.Java8Api.listOf());
    }

    private static Page page(int number, String text) {
        return new Page(number, text, "PAGE", "QWEN_PDF_OCR", "");
    }

    private static Fact fact(String entityType, String entityKey, String field, String raw,
                             String normalized, int page, String anchor) {
        return new Fact(entityType, entityKey, field, raw, normalized,
                new Evidence("bid", "投标文件.pdf", page, "PAGE", raw, anchor, 1));
    }

    private static Fact connector(String field, String value, String asOf, String suffix) {
        Evidence evidence = new Evidence("credit-china", "信用中国", 0, "TRUSTED_CONNECTOR",
                "信用中国于" + asOf + "返回投标人查询记录",
                "adapter:CREDIT_CHINA|asOf:" + asOf + "|stage:BID_OPENING|" + suffix, 1);
        return new Fact("TRUSTED_CONNECTOR", "投标人", field, evidence.quote(), value, evidence);
    }
}
