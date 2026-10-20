package cn.bidcheck.checktemplate;

import cn.bidcheck.checktemplate.evidence.EvidenceNeedCoordinator;
import cn.bidcheck.checktemplate.evidence.InMemoryEvidenceTaskRepository;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Fact;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.engine.FactExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TemplateEvidenceAssemblerTest {
    private final TemplateEvidenceAssembler assembler = new TemplateEvidenceAssembler();
    private final TemplateCheckExecutor executor = new TemplateCheckExecutor(CheckTemplateRegistry.defaultRegistry());
    private final SourceRef requirement = new SourceRef("tender", 1, "tender-sha", "TEXT", "source", "PAGE", 1,
            "招标要求", null, null);

    @Test void materialIndexIsOnlyAClueAndCurrentBodySuppliesTheEvidence() {
        Document bid = doc(
                page(1, "目录\n营业执照................3\n授权委托书................4"),
                page(2, "本项目投标人资格证明\n营业执照\n名称：海南测试有限公司\n统一社会信用代码：91460000123456789X"));
        CheckSpec spec = spec("MATERIAL_PRESENT", TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                Map.of("materialType", "营业执照", "requireBody", true), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        List<Observation.MaterialObservation> observations = assembly.evidenceView().observations(Observation.MaterialObservation.class);

        assertTrue(observations.stream().anyMatch(value -> value.titleOnly() && !value.bodyPresent()));
        assertTrue(observations.stream().anyMatch(value -> value.currentScope() && value.bodyPresent()
                && value.state() == TemplateTypes.ObservationState.SUPPORTED));
        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":material"));
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                execution.projection().conclusion().state(), execution.toString());
        assertSourceBound(assembly, bid);
    }

    @Test void incompleteDocumentDoesNotCloseAnAbsentMaterialNeed() {
        Page deferred = new Page(1, "[OCR_DEFERRED]", "PAGE", "QWEN_PDF_OCR", "[OCR未完成]");
        Document bid = doc(deferred);
        CheckSpec spec = spec("MATERIAL_PRESENT", TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                Map.of("materialType", "营业执照", "requireBody", true), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":material"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void tenderVolumeNamesResolveToSubmittedCoverAndIndexTitlesWithinTheirVolume() {
        String businessCover = "1.商务投标文件封面\n"
                + "（合成园区综合楼装修测试项目）\n（招标项目编号：SYNTHETIC-2026-001）\n"
                + "投标文件（商务部分）\n投标人名称：（盖章）合成测试有限公司\n2026年6月18日";
        String technicalCover = "1.技术投标文件封面\n"
                + "（合成园区综合楼装修测试项目）\n（招标项目编号：SYNTHETIC-2026-001）\n"
                + "投标文件（技术部分）\n投标人名称：（盖章）合成测试有限公司\n2026年6月18日";
        String businessIndex = "2.商务评审索引表（适用综合评估法） \n商务评审索引表 \n"
                + "条款号 评审因素 对应页码 备注或者说明 \n"
                + "1 投标人名称 P20-21 满足要求\n2 投标函 P7-8 满足要求\n3 投标保证金 P277 满足要求\n"
                + "24 施工业绩情况（商务） P279-448 满足要求 ";
        String technicalIndex = "2.技术评审索引表 \n技术评审索引表 \n"
                + "条款号 评审因素 对应页码 备注或者说明 \n"
                + "1 实质性条款 P4 满足要求\n2 设计方案 P5-37 满足要求\n3 施工组织设计 P38-95 满足要求";
        String proposal = "4.技术建议书 \n4.1 设计方案 \n第一章、对本项目的建设目标 \n"
                + "围绕项目建设目标、现状、技术路线、实施步骤和质量保障措施进行详细说明。";
        Document bid = doc(
                page(1, businessCover),
                page(2, "目录\n1.商务投标文件封面 ........ 1\n2.商务评审索引表 ........ 5\n3.投标函 ........ 7"),
                page(5, businessIndex),
                page(485, technicalCover),
                page(486, "目录\n1.技术投标文件封面 ........ 1\n2.技术评审索引表 ........ 3\n4.技术建议书 ........ 5"),
                page(487, technicalIndex), page(489, proposal));

        Map<String, Integer> expectedPage = Map.of(
                "第一分册 商务投标文件", 1,
                "商务技术评审索引表", 5,
                "第二分册 技术投标文件", 485,
                "技术投标索引表", 487,
                "技术建议书", 489);
        for (Map.Entry<String, Integer> entry : expectedPage.entrySet()) {
            CheckSpec spec = spec("composition-" + entry.getValue(), "MATERIAL_PRESENT",
                    TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                    Map.of("materialType", entry.getKey(), "requireBody", true), SubjectSelector.bidder());
            TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
            TemplateExecution execution = executor.execute(spec, assembly.evidenceView());

            assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                    execution.projection().conclusion().state(), entry.getKey() + ": " + execution);
            assertTrue(assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                    .filter(value -> value.state() == TemplateTypes.ObservationState.SUPPORTED)
                    .flatMap(value -> value.sources().stream())
                    .anyMatch(source -> source.location() == entry.getValue()), entry.getKey());
            assertSourceBound(assembly, bid);
        }
    }

    @Test void compositionAliasesDoNotTurnDirectoryOrHistoricalForeignVolumeIntoEvidence() {
        String currentCover = "商务投标文件封面\n项目编号：CURRENT-001\n投标文件（商务部分）\n"
                + "投标人名称：（盖章）当前公司";
        String foreignCover = "历史项目投标文件\n项目编号：OLD-009\n投标文件（技术部分）\n"
                + "投标人名称：（盖章）历史公司";
        Document bid = doc(
                page(1, currentCover),
                page(2, "目录\n2.技术评审索引表 ........ 3\n4.技术建议书 ........ 5\n5.施工组织 ........ 8"),
                page(20, foreignCover),
                page(21, "2.技术评审索引表\n条款号 评审因素 对应页码\n1 设计方案 P5-37\n2 施工组织 P38-95"),
                page(22, "4.技术建议书\n历史项目设计方案及实施措施的详细说明。"));

        for (String required : List.of("第二分册 技术投标文件", "技术投标索引表", "技术建议书")) {
            CheckSpec spec = spec("historical-" + required.hashCode(), "MATERIAL_PRESENT",
                    TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                    Map.of("materialType", required, "requireBody", true), SubjectSelector.bidder());
            assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                    executor.execute(spec, assemble(spec, bid, List.of()).evidenceView())
                            .projection().conclusion().state(), required);
        }
    }

    @Test void businessAndTechnicalAliasesDoNotMaskMissingQuotationVolume() {
        Document bid = doc(page(1, "商务投标文件封面\n项目编号：CURRENT-001\n"
                + "投标文件（商务部分）\n投标人名称：（盖章）当前公司"));
        for (String required : List.of("第三分册 报价文件", "报价文件封面", "报价文件")) {
            CheckSpec spec = spec("missing-quote-" + required.hashCode(), "MATERIAL_PRESENT",
                    TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                    Map.of("materialType", required, "requireBody", true), SubjectSelector.bidder());
            assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                    executor.execute(spec, assemble(spec, bid, List.of()).evidenceView())
                            .projection().conclusion().state(), required);
        }
    }

    @Test void commitmentWordsDoNotBecomeASealButBoundVisualFactDoes() {
        Document bid = doc(page(1, "本项目诚信承诺书\n诚信承诺书\n我方郑重承诺依法经营并承担相应责任。\n投标人（盖章）："));
        CheckSpec spec = spec("COMMITMENT_WITH_SEAL", TemplateTypes.ProofStandard.WRITTEN_COMMITMENT,
                Map.of("materialType", "诚信承诺书", "requiredCommitmentPoints", List.of("依法经营", "承担相应责任"),
                        "issuerRole", "BIDDER", "sealLocation", "要求位置"),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly textOnly = assemble(spec, bid, List.of());
        assertTrue(textOnly.evidenceView().observations(Observation.SealObservation.class).isEmpty());
        assertFalse(textOnly.evidenceView().closedNeedKeys().contains(spec.id() + ":seal"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, textOnly.evidenceView()).projection().conclusion().state());

        Evidence visual = new Evidence("bid", "投标文件.pdf", 1, "PAGE", "诚信承诺书要求位置可见企业印章",
                "visual:seal:1", 1);
        Fact observed = new Fact("SIGNATURE_SEAL", "seal-1", "sealObserved", "SEAL", "OBSERVED", visual);
        TemplateEvidenceAssembler.Assembly withSeal = assemble(spec, bid, List.of(observed));
        Observation.SealObservation seal = withSeal.evidenceView().observations(Observation.SealObservation.class).get(0);
        assertTrue(seal.observed());
        assertFalse(seal.materialObservationId().isBlank());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, withSeal.evidenceView()).projection().conclusion().state());
        assertSourceBound(withSeal, bid);
    }

    @Test void sealFromAnotherDocumentSectionCannotBeBorrowed() {
        Document bid = doc(
                page(1, "本项目诚信承诺书\n诚信承诺书\n我方承诺依法经营。"),
                page(2, "本项目授权委托书\n授权委托书要求位置可见企业印章"));
        CheckSpec spec = spec("COMMITMENT_WITH_SEAL", TemplateTypes.ProofStandard.WRITTEN_COMMITMENT,
                Map.of("materialType", "诚信承诺书", "requiredCommitmentPoints", List.of("依法经营"),
                        "issuerRole", "BIDDER", "sealLocation", "要求位置"), SubjectSelector.bidder());
        Evidence wrong = new Evidence("bid", "投标文件.pdf", 2, "PAGE", "授权委托书要求位置可见企业印章", "visual:seal:2", 1);
        Fact observed = new Fact("SIGNATURE_SEAL", "seal-2", "sealObserved", "SEAL", "OBSERVED", wrong);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of(observed));

        assertTrue(assembly.evidenceView().observations(Observation.SealObservation.class).isEmpty());
        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":seal"));
    }

    @Test void alternativeBranchesStayIndependent() {
        Document bid = doc(page(1, "本项目资格证明\n开票承诺书\n我方承诺中标后依法开具发票并承担责任。"));
        CheckSpec spec = spec("MATERIAL_ALTERNATIVE", TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                Map.of("alternatives", List.of("税务证明", "开票承诺书"), "atLeast", 1, "requireBody", true),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        List<Observation.MaterialObservation> material = assembly.evidenceView().observations(Observation.MaterialObservation.class);
        assertTrue(material.stream().anyMatch(value -> value.materialType().equals("开票承诺书") && value.bodyPresent()));
        assertFalse(material.stream().anyMatch(value -> value.materialType().equals("税务证明")));
        assertEquals(2, assembly.evidenceView().closedNeedKeys().stream().filter(key -> key.contains(":alternative-")).count());
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                execution.projection().conclusion().state(), execution.toString());
    }

    @Test void certificateKeepsTypeHolderAndExpiryTogetherAndRejectsHistoryScope() {
        Document bid = doc(
                page(1, "本项目建造师注册证书\n姓名：张三\n证书名称：建造师注册证\n证书编号：A12345\n有效期至：2027年12月31日"),
                page(2, "主要工作经历\n建造师注册证书\n姓名：李四\n证书名称：建造师注册证\n证书编号：B12345\n有效期至：2028年12月31日"));
        CheckSpec spec = spec("CERTIFICATE_VALIDITY", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("certificateType", "建造师注册证", "comparisonDate", "2027-01-01", "inclusive", true),
                new SubjectSelector("PERSON", "", "CURRENT_PROJECT", Map.of()));

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        List<Observation.CertificateObservation> certificates = assembly.evidenceView().observations(Observation.CertificateObservation.class);

        assertTrue(certificates.stream().anyMatch(value -> value.holderName().equals("张三")
                && value.certificateNumber().equals("A12345") && value.validUntil().equals(java.time.LocalDate.of(2027, 12, 31))
                && value.currentScope()));
        assertTrue(certificates.stream().anyMatch(value -> value.holderName().equals("李四") && !value.currentScope()));
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                execution.projection().conclusion().state(), execution.toString());
        assertSourceBound(assembly, bid);
    }

    @Test void priceRequiresTheConfiguredLotTaxAndLayer() {
        Document bid = doc(page(1, "本项目投标报价表\n第A标段 投标总报价（含税）：99万元"));
        Fact price = new Fact("PRICE", "PRICE", "totalPrice", "第A标段 投标总报价（含税）：99万元",
                "CNY_INCLUSIVE:990000", evidence(1, "第A标段 投标总报价（含税）：99万元", "p1-l2"));
        CheckSpec spec = spec("PRICE_COMPARE", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("field", "totalPrice", "operator", "<=", "expected", "1000000", "currency", "CNY", "unit", "元",
                        "taxBasis", "INCLUSIVE", "lot", "A", "priceLayer", "TOTAL"), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of(price));
        Observation.PriceObservation observation = assembly.evidenceView().observations(Observation.PriceObservation.class).get(0);

        assertEquals(TemplateTypes.ObservationState.SUPPORTED, observation.state());
        assertEquals("A", observation.lot());
        assertEquals("INCLUSIVE", observation.taxBasis());
        assertEquals("TOTAL", observation.priceLayer());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());

        CheckSpec unknownLot = spec("PRICE_COMPARE", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("field", "totalPrice", "operator", "<=", "expected", "1000000", "currency", "CNY", "unit", "元",
                        "taxBasis", "INCLUSIVE", "lot", "B", "priceLayer", "TOTAL"), SubjectSelector.bidder());
        TemplateEvidenceAssembler.Assembly mismatch = assemble(unknownLot, bid, List.of(price));
        assertEquals(TemplateTypes.ObservationState.UNRESOLVED,
                mismatch.evidenceView().observations(Observation.PriceObservation.class).get(0).state());
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(unknownLot, mismatch.evidenceView()).projection().conclusion().state());

        Fact currencyMissing = new Fact("PRICE", "PRICE", "totalPrice", "投标总报价：990000",
                "990000", evidence(1, "投标总报价：990000", "p1-l3"));
        TemplateEvidenceAssembler.Assembly unknownCurrency = assemble(spec, bid, List.of(currencyMissing));
        assertEquals(TemplateTypes.ObservationState.UNRESOLVED,
                unknownCurrency.evidenceView().observations(Observation.PriceObservation.class).get(0).state());
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, unknownCurrency.evidenceView()).projection().conclusion().state());
    }

    @Test void personnelUsesOnlyCurrentStructuredRosterDeduplicatesIdentityAndClosesExactScope() {
        String current = "本项目人员配置表\n"
                + "| 姓名 | 岗位 | 身份证号码 | 工作单位 |\n"
                + "| --- | --- | --- | --- |\n"
                + "| 张三 | 项目经理 | 110101199001011234 | 海南测试有限公司 |\n"
                + "| 张三 | 项目经理 | 110101199001011234 | 海南测试有限公司 |";
        String history = "主要工作经历\n| 姓名 | 岗位 | 身份证号码 |\n| --- | --- | --- |\n"
                + "| 李四 | 项目经理 | 110101199202022345 |";
        Document bid = doc(page(1, current), page(2, history));
        CheckSpec spec = spec("PERSONNEL_ROSTER", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("role", "项目经理", "minCount", 1, "maxCount", 1, "allowConcurrent", true),
                SubjectSelector.personnel("项目经理"));

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        List<Observation.PersonnelObservation> people = assembly.evidenceView().observations(Observation.PersonnelObservation.class);

        assertEquals(1, people.size());
        assertEquals("张三", people.get(0).personName());
        assertTrue(people.get(0).currentAppointment());
        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":roster-scope"));
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                execution.projection().conclusion().state(), execution.toString());
        assertSourceBound(assembly, bid);
    }

    @Test void aSingleAppointmentDoesNotClaimTheRosterIsComplete() {
        Document bid = doc(
                page(1, "本项目人员安排\n姓名：张三\n拟在本合同任职：项目经理\n身份证号码：110101199001011234"),
                page(2, "主要工作经历\n姓名：李四\n拟在本合同任职：项目经理\n身份证号码：110101199202022345"));
        CheckSpec spec = spec("PERSONNEL_ROSTER", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("role", "项目经理", "minCount", 2, "allowConcurrent", true), SubjectSelector.personnel("项目经理"));

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertEquals(1, assembly.evidenceView().observations(Observation.PersonnelObservation.class).size());
        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":roster-scope"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void sameNameRowsWithoutStableIdentityDoNotInflateTheRosterOrCloseItsScope() {
        Document bid = doc(page(1, "本项目人员配置表\n"
                + "| 姓名 | 岗位 | 工作单位 |\n"
                + "| --- | --- | --- |\n"
                + "| 张三 | 项目经理 | 海南甲公司 |\n"
                + "| 张三 | 项目经理 | 海南乙公司 |"));
        CheckSpec spec = spec("PERSONNEL_ROSTER", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("role", "项目经理", "minCount", 2, "allowConcurrent", true),
                SubjectSelector.personnel("项目经理"));

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertEquals(1, assembly.evidenceView().observations(Observation.PersonnelObservation.class).size());
        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":roster-scope"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
        assertEquals(1, ((Map<?, ?>) assembly.diagnostics().get("counters"))
                .get("ambiguousPersonnelIdentityNames"));
    }

    @Test void sectionChecklistAssemblesEveryBodyAndExecutesToSatisfied() {
        Document bid = doc(
                page(1, "本项目投标文件\n投标函\n我方愿意按照招标文件要求承担本项目全部工作并履行合同义务。"),
                page(2, "本项目资格文件\n营业执照\n名称：海南测试有限公司\n统一社会信用代码：91460000123456789X"));
        CheckSpec spec = spec("SECTION_CHECKLIST", TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                Map.of("requiredItems", List.of("投标函", "营业执照"), "requireBody", true),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());

        assertEquals(2, assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .filter(value -> value.currentScope() && value.bodyPresent()).count());
        assertEquals(2, assembly.evidenceView().closedNeedKeys().stream()
                .filter(value -> value.startsWith(spec.id() + ":item-")).count());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, execution.projection().conclusion().state(),
                execution.toString());
        assertSourceBound(assembly, bid);
    }

    @Test void entityFieldMatchJoinsRosterAndCertificateForTheSamePerson() {
        Document bid = doc(page(1, currentCredentialRoster()));
        CheckSpec spec = spec("ENTITY_FIELD_MATCH", TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                Map.of("role", "项目经理", "leftField", "personName",
                        "rightField", "certificate.holderName", "certificateType", "建造师注册证",
                        "caseSensitive", false), SubjectSelector.personnel("项目经理"));

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());

        Observation.PersonnelObservation person = assembly.evidenceView()
                .observations(Observation.PersonnelObservation.class).get(0);
        Observation.CertificateObservation certificate = assembly.evidenceView()
                .observations(Observation.CertificateObservation.class).get(0);
        assertEquals(person.personKey(), certificate.holderKey());
        assertEquals(person.personName(), certificate.holderName());
        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":entity-scope"),
                assembly.diagnostics() + " / " + assembly.evidenceView());
        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":certificate-fields"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, execution.projection().conclusion().state(),
                execution.toString());
        assertSourceBound(assembly, bid);
    }

    @Test void personnelCredentialJoinsAppointmentCertificateEmployerAndValidity() {
        Document bid = doc(page(1, currentCredentialRoster()));
        CheckSpec spec = spec("PERSONNEL_CREDENTIAL", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("role", "项目经理", "certificateType", "建造师注册证",
                        "employerKey", "海南测试有限公司", "requireEmployerMatch", true,
                        "comparisonDate", "2027-01-01", "inclusive", true),
                SubjectSelector.personnel("项目经理"));

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());

        Observation.PersonnelObservation person = assembly.evidenceView()
                .observations(Observation.PersonnelObservation.class).get(0);
        assertEquals("海南测试有限公司", person.employerKey());
        assertEquals("海南测试有限公司", person.attributes().get("registeredEmployer"));
        assertTrue(assembly.evidenceView().closedNeedKeys().containsAll(Set.of(
                spec.id() + ":credential-person", spec.id() + ":credential-certificate",
                spec.id() + ":credential-scope")), assembly.diagnostics() + " / " + assembly.evidenceView());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, execution.projection().conclusion().state(),
                execution.toString());
        assertSourceBound(assembly, bid);
    }

    @Test void consortiumResponsibilityExtractsExplicitMembersRolesAndDuties() {
        Document bid = doc(page(1, "本项目联合体投标\n联合体协议书\n"
                + "成员：海南施工有限公司\n成员：海南设计有限公司\n"
                + "海南施工有限公司为联合体牵头人，负责施工。\n"
                + "海南设计有限公司为设计成员，负责设计。\n"
                + "各方按照上述责任分工共同履行本项目合同。"));
        CheckSpec spec = spec("CONSORTIUM_RESPONSIBILITY", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("materialType", "联合体协议书", "requiredMemberRoles", List.of("设计成员"),
                        "requiredResponsibilities", List.of("负责施工", "负责设计"),
                        "leadRole", "联合体牵头人", "minMembers", 2, "memberPrefix", "成员:"),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());

        Observation.MaterialObservation agreement = assembly.evidenceView()
                .observations(Observation.MaterialObservation.class).get(0);
        assertTrue(agreement.commitmentPoints().containsAll(Set.of(
                "设计成员", "负责施工", "负责设计", "联合体牵头人",
                "成员:海南施工有限公司", "成员:海南设计有限公司")), agreement.toString());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, execution.projection().conclusion().state(),
                execution.toString());
        assertSourceBound(assembly, bid);
    }

    @Test void priceReconcileUsesOneClosedCommercialScopeForTotalAndComponents() {
        Document bid = doc(page(1, "本项目投标报价表\n第A标段 投标总报价（含税）：100万元\n"
                + "第A标段 工程费分项报价（含税）：60万元\n"
                + "第A标段 设计费分项报价（含税）：40万元"));
        List<Fact> facts = List.of(
                priceFact("totalPrice", "第A标段 投标总报价（含税）：100万元", "CNY_INCLUSIVE:1000000", "p1-total"),
                priceFact("constructionPrice", "第A标段 工程费分项报价（含税）：60万元", "CNY_INCLUSIVE:600000", "p1-construction"),
                priceFact("designPrice", "第A标段 设计费分项报价（含税）：40万元", "CNY_INCLUSIVE:400000", "p1-design"));
        CheckSpec spec = spec("PRICE_RECONCILE", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("totalField", "totalPrice", "componentFields", List.of("constructionPrice", "designPrice"),
                        "currency", "CNY", "unit", "元", "taxBasis", "INCLUSIVE", "lot", "A",
                        "tolerance", "0"), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, facts);
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());

        assertEquals(3, assembly.evidenceView().observations(Observation.PriceObservation.class).size());
        assertEquals(3, assembly.evidenceView().closedNeedKeys().stream()
                .filter(value -> value.startsWith(spec.id() + ":price-")).count());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED, execution.projection().conclusion().state(),
                execution.toString());
        assertSourceBound(assembly, bid);
    }

    @Test void durationComparisonExtractsTheBoundFieldAndCalendarBasis() {
        Document bid = doc(page(1, "本项目投标函附录\n总工期：90日历日，自开工之日起计算。"));
        Evidence source = evidence(1, "总工期：90日历日，自开工之日起计算。", "duration-1");
        Fact duration = new Fact("TIME_REQUIREMENT", "TIME_REQUIREMENT", "duration",
                source.quote(), "90", source);
        CheckSpec spec = spec("DURATION_COMPARE", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("field", "duration", "operator", "<=", "expected", "100", "unit", "日",
                        "calendarBasis", "CALENDAR_DAY", "tolerance", "0"), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of(duration));
        Observation.PriceObservation observation = assembly.evidenceView()
                .observations(Observation.PriceObservation.class).get(0);

        assertEquals("DURATION", observation.currency());
        assertEquals("CALENDAR_DAY", observation.taxBasis());
        assertEquals("90", observation.amount().toPlainString());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void durationNeverCopiesRequestedStartEventOrLotIntoObservedEvidence() {
        Document bid = doc(page(1, "本项目投标函附录\n第A标段 总工期：90日历日，自开工之日起计算。"));
        Evidence source = evidence(1, "第A标段 总工期：90日历日，自开工之日起计算。", "duration-basis-1");
        Fact duration = new Fact("TIME_REQUIREMENT", "TIME_REQUIREMENT", "duration",
                source.quote(), "90", source);
        CheckSpec spec = spec("DURATION_COMPARE", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("field", "duration", "operator", "<=", "expected", "100", "unit", "日",
                        "startEvent", "合同签订之日", "calendarBasis", "CALENDAR_DAY", "lot", "B",
                        "tolerance", "0"), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of(duration));
        Observation.PriceObservation observation = assembly.evidenceView()
                .observations(Observation.PriceObservation.class).get(0);

        assertEquals("开工之日", observation.priceLayer());
        assertEquals("A", observation.lot());
        assertEquals(TemplateTypes.ObservationState.UNRESOLVED, observation.state());
        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":duration"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void durationWithoutASourceUnitKeepsTheBasisUnknown() {
        Document bid = doc(page(1, "本项目投标函附录\n总工期：90"));
        Evidence source = evidence(1, "总工期：90", "duration-no-unit");
        Fact duration = new Fact("TIME_REQUIREMENT", "TIME_REQUIREMENT", "duration",
                source.quote(), "90", source);
        CheckSpec spec = spec("DURATION_COMPARE", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("field", "duration", "operator", "<=", "expected", "100", "unit", "日",
                        "calendarBasis", "CALENDAR_DAY", "tolerance", "0"), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of(duration));
        assertTrue(assembly.evidenceView().observations(Observation.PriceObservation.class).isEmpty());
        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":duration"));
        assertTrue(assembly.diagnostics().toString().contains("unboundDurationBasis"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void crossDocumentConsistencyUsesDistinctBidAndTenderSources() {
        Document bid = doc(page(1, "本项目投标函\n项目名称：合成园区云计算中心装修项目"));
        Document tender = new Document("tender", "project", "TENDER", "招标文件.docx", 5,
                "tender-sha-5", "2026-10-19T00:00:00Z",
                List.of(page(1, "招标项目名称为合成园区云计算中心装修项目。")), List.of());
        CheckSpec spec = spec("CROSS_DOCUMENT_CONSISTENCY", TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                Map.of("field", "projectName", "minimumOccurrences", 2, "caseSensitive", false),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assembler.assemble(spec, checkpoint(spec), bid, tender, List.of());
        Set<String> documents = assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.sources().stream()).map(SourceRef::documentId)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("bid", "tender"), documents);
        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":field-values"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void crossDocumentConsistencyExcludesHistoricalAndExampleValues() {
        Document bid = doc(page(1, "本项目投标函\n项目名称：合成园区云计算中心装修项目\n历史项目\n项目名称：合成旧项目"));
        Document tender = new Document("tender", "project", "TENDER", "招标文件.docx", 5,
                "tender-sha-5", "2026-10-19T00:00:00Z",
                List.of(page(1, "招标项目名称：合成园区云计算中心装修项目")), List.of());
        CheckSpec spec = spec("CROSS_DOCUMENT_CONSISTENCY", TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                Map.of("field", "projectName", "minimumOccurrences", 2, "caseSensitive", false),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assembler.assemble(spec, checkpoint(spec), bid, tender, List.of());
        Set<String> values = assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.commitmentPoints().stream()).collect(java.util.stream.Collectors.toSet());

        assertFalse(values.stream().anyMatch(value -> value.contains("合成旧项目")), values.toString());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void consortiumMemberNameIgnoresUppercaseLatexOcrWrapper() {
        Document bid = doc(
                page(1, "本项目投标文件封面\n投标人名称：合成施工有限公司"),
                page(2, "本项目施工单位资质证明\n企业名称：合成施工有限公司"),
                page(3, "本项目设计单位营业执照\n企业名称：合成设计有限公司"),
                page(4, "本项目设计单位法定代表人身份证明\n投标人名称：合成设计有限公司"));
        Evidence ocr = evidence(4, "投标人名称：合成设计有限公司", "ocr-latex-company");
        Fact wrapped = new Fact("BIDDER", "DESIGN_MEMBER", "companyName", ocr.quote(),
                "\\(\\UNDERLINE{\\TEXT{合成设计有限公司}}\\)", ocr);
        CheckSpec spec = spec("CROSS_DOCUMENT_CONSISTENCY", TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                Map.of("field", "companyName", "minimumOccurrences", 2, "caseSensitive", false),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of(wrapped));

        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":field-values"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void formCompletenessMarksExplicitBlankWithoutInventingMissingFields() {
        Document bid = doc(page(1, "本项目投标函\n投标函\n项目名称：海南项目\n投标人名称：____"));
        CheckSpec spec = spec("FORM_FIELD_COMPLETENESS", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("formType", "投标函", "requiredFields", List.of("项目名称", "投标人名称")),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        Set<String> points = assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.commitmentPoints().stream())
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(points.contains("FIELD_FILLED:项目名称"), points.toString());
        assertTrue(points.contains("FIELD_PLACEHOLDER:投标人名称"), points.toString());
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void formCompletenessNeverCombinesFieldsFromDifferentFormPages() {
        Document bid = doc(
                page(1, "本项目投标函（第一联）\n投标函\n项目名称：海南项目"),
                page(2, "本项目投标函（第二联）\n投标函\n投标人名称：海南测试有限公司"));
        CheckSpec spec = spec("FORM_FIELD_COMPLETENESS", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("formType", "投标函", "requiredFields", List.of("项目名称", "投标人名称")),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":form"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void dateRelationCanBindBidSigningDateToTenderOpeningDate() {
        Document bid = doc(page(1, "本项目投标函\n投标日期：2026年10月18日"));
        Document tender = new Document("tender", "project", "TENDER", "招标文件.docx", 5,
                "tender-sha-5", "2026-10-19T00:00:00Z",
                List.of(page(1, "开标日期：2026年10月20日")), List.of());
        CheckSpec spec = spec("DATE_RELATION", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("leftRole", "投标日期", "rightRole", "开标日期", "operator", "<="),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assembler.assemble(spec, checkpoint(spec), bid, tender, List.of());

        assertEquals(2, assembly.evidenceView().observations(Observation.CertificateObservation.class).size());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void dateRelationRejectsHistoricalAndWrongDocumentRoleDates() {
        Document bid = doc(page(1, "本项目投标函\n投标日期：2026年10月18日\n开标日期：2026年10月10日\n历史项目签署日期：2025年1月1日"));
        Document tender = new Document("tender", "project", "TENDER", "招标文件.docx", 5,
                "tender-sha-5", "2026-10-19T00:00:00Z",
                List.of(page(1, "开标日期：2026年10月20日")), List.of());
        CheckSpec spec = spec("DATE_RELATION", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("leftRole", "投标日期", "rightRole", "开标日期", "operator", "<"),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assembler.assemble(spec, checkpoint(spec), bid, tender, List.of());
        List<Observation.CertificateObservation> observations = assembly.evidenceView()
                .observations(Observation.CertificateObservation.class);

        assertEquals(2, observations.size());
        assertTrue(observations.stream().filter(value -> value.certificateType().equals("DATE:开标日期"))
                .flatMap(value -> value.sources().stream()).allMatch(value -> value.documentId().equals("tender")));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void textScanOnlyDeclaresClearAfterTheRequestedScopeIsClosed() {
        Document complete = doc(page(1, "本项目投标函\n投标函正文内容完整，文字清晰。"));
        CheckSpec spec = spec("TEXT_ANOMALY", TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("textScope", "投标函", "anomalyTypes", List.of("OCR_SUSPECT", "TRUNCATED")),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly clear = assemble(spec, complete, List.of());
        assertTrue(clear.evidenceView().closedNeedKeys().contains(spec.id() + ":text-scan"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, clear.evidenceView()).projection().conclusion().state());

        Document deferred = document(3, "bid-sha-3", List.of(
                new Page(1, "本项目投标函 [OCR_DEFERRED]", "PAGE", "QWEN_PDF_OCR", "[OCR未完成]")), List.of());
        TemplateEvidenceAssembler.Assembly pending = assemble(spec, deferred, List.of());
        assertFalse(pending.evidenceView().closedNeedKeys().contains(spec.id() + ":text-scan"));
    }

    @Test void typoScanCannotReportClearWithoutADictionaryOrDetectorReceipt() {
        Document bid = doc(page(1, "本项目投标函\n投标函正文内容完整，文字清晰。"));
        CheckSpec spec = spec("TEXT_ANOMALY", TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("textScope", "投标函", "anomalyTypes", List.of("TYPO")),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly withoutDetector = assemble(spec, bid, List.of());
        assertFalse(withoutDetector.evidenceView().closedNeedKeys().contains(spec.id() + ":text-scan"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, withoutDetector.evidenceView()).projection().conclusion().state());
        assertTrue(withoutDetector.diagnostics().toString().contains("TEXT_ANOMALY_CAPABILITY_MISSING"));

        Evidence receiptSource = evidence(1, "投标函错别字检测完成", "typo-scan-receipt");
        Fact detectorReceipt = new Fact("TEXT_ANOMALY_SCOPE", "投标函", "TYPO",
                receiptSource.quote(), "CLOSED", receiptSource);
        TemplateEvidenceAssembler.Assembly withDetector = assemble(spec, bid, List.of(detectorReceipt));
        assertTrue(withDetector.evidenceView().closedNeedKeys().contains(spec.id() + ":text-scan"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, withDetector.evidenceView()).projection().conclusion().state());
    }

    @Test void deterministicTypoDictionaryProducesCandidatesAndClosesTheScannedScope() {
        Document bid = doc(page(1, "本项目投标函\n投标函正文：拟派项日经理负责现场管理。"));
        CheckSpec spec = spec("TEXT_ANOMALY", TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("textScope", "投标函", "anomalyTypes", List.of("TYPO"),
                        "typoDictionary", List.of("项日=>项目")), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());

        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":text-scan"));
        assertEquals(TemplateTypes.ConclusionState.SUSPECTED_RISK,
                execution.projection().conclusion().state());
        assertTrue(assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.commitmentPoints().stream())
                .anyMatch(value -> value.contains("项日→项目")));
    }

    @Test void objectiveScoringUsesSourceBackedScoreFactsAndClosesAtZeroOrMoreItems() {
        Document bid = doc(page(1, "本项目客观评分材料\n客观评分汇总\n企业业绩核验总得分：4分"));
        Evidence source = evidence(1, "客观评分汇总：企业业绩核验总得分4分", "score-1");
        Fact score = new Fact("SCORE", "BIDDER", "enterprisePerformance", source.quote(), "4", source);
        CheckSpec spec = spec("OBJECTIVE_SCORING", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "enterprisePerformance", "aggregation", "SUM", "maxScore", "6",
                        "scoreUnit", "分", "deduplicateBySubject", true), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of(score));

        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
    }

    @Test void completeDocumentWithoutScoringCoverageDoesNotInventAZeroScore() {
        Document bid = doc(page(1, "本项目投标文件\n企业业绩证明材料见附件。"));
        CheckSpec spec = spec("OBJECTIVE_SCORING", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "enterprisePerformance", "aggregation", "SUM", "maxScore", "6",
                        "scoreUnit", "分", "deduplicateBySubject", true), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
        assertTrue(assembly.diagnostics().toString().contains("SCORING_SCOPE_OPEN"));
    }

    @Test void scoringHeadingAndMaximumRuleDoNotCloseAnEmptySetAsZero() {
        Document bid = doc(page(1, "本项目客观评分汇总\n企业业绩每项得2分，最高6分。"));
        CheckSpec spec = spec("OBJECTIVE_SCORING", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "企业业绩", "aggregation", "SUM", "maxScore", "6",
                        "scoreUnit", "分", "deduplicateBySubject", true), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertTrue(assembly.evidenceView().observations(Observation.PriceObservation.class).isEmpty());
        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void unrelatedScoreFactCannotBeReboundToTheRequestedCriterion() {
        Document bid = doc(page(1, "本项目客观评分材料\n技术方案实际得分：5分。"));
        Evidence source = evidence(1, "技术方案实际得分：5分", "unrelated-score");
        Fact unrelated = new Fact("SCORE", "BIDDER", "technicalPlan", source.quote(), "5", source);
        CheckSpec spec = spec("OBJECTIVE_SCORING", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "企业业绩", "aggregation", "SUM", "maxScore", "6",
                        "scoreUnit", "分", "deduplicateBySubject", true), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of(unrelated));

        assertTrue(assembly.evidenceView().observations(Observation.PriceObservation.class).isEmpty());
        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void explicitCriterionBoundZeroScoreIsSourceBackedAndMayClose() {
        Document bid = doc(page(1, "本项目客观评分结果\n企业业绩本项实际得分：0分。"));
        CheckSpec spec = spec("OBJECTIVE_SCORING", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "企业业绩", "aggregation", "SUM", "maxScore", "6",
                        "scoreUnit", "分", "deduplicateBySubject", true), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        List<Observation.PriceObservation> scores = assembly.evidenceView()
                .observations(Observation.PriceObservation.class);

        assertEquals(1, scores.size());
        assertEquals("0", scores.get(0).amount().toPlainString());
        assertFalse(scores.get(0).sources().isEmpty());
        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void binaryMaterialScoringRuleUsesTenderRuleAndBidCertificateProof() {
        SourceRef scoringRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "score-rule", "BLOCK", 453,
                "综合实力（商务）：施工单位具备质量管理体系认证证书的，得1分", null, null);
        Document bid = doc(page(449, "本项目其他证明文件\n施工单位具备质量管理体系认证\n"
                + "质量管理体系认证证书\n证书编号：SYN-QMS-001\n兹证明合成施工有限公司\n"
                + "证书有效日期：2025年09月04日至2028年09月03日\n认证范围：建筑装修装饰工程施工"));
        Document tender = new Document("tender", "project", "TENDER", "招标文件.docx", 1,
                "tender-sha", "2026-10-19T00:00:00Z",
                List.of(page(100, "投标截止时间为：2026年08月05日")), List.of());
        CheckSpec spec = specWithRequirement("objective-binary", "OBJECTIVE_SCORING",
                TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "综合实力（商务）：施工单位具备质量管理体系认证证书的",
                        "aggregation", "HIGHEST", "maxScore", "1", "scoreUnit", "分",
                        "deduplicateBySubject", true), scoringRule);

        TemplateEvidenceAssembler.Assembly assembly = assembler.assemble(spec, checkpoint(spec), bid, tender, List.of());
        List<Observation.PriceObservation> scores = assembly.evidenceView()
                .observations(Observation.PriceObservation.class);

        assertEquals(1, scores.size());
        assertEquals("1", scores.get(0).amount().toPlainString());
        assertEquals(Set.of("bid", "tender"), scores.get(0).sources().stream()
                .map(SourceRef::documentId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, assembly.evidenceView()).projection().conclusion().state());
    }

    @Test void binaryMaterialScoringUsesCoreCertificateNameWhenRuleRequiresAnEffectiveCertificate() {
        SourceRef scoringRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "effective-score-rule", "BLOCK", 453,
                "施工单位具备有效的质量管理体系认证证书的，得1分", null, null);
        Document bid = doc(page(449, "本项目其他证明文件\n质量管理体系认证证书\n证书编号：Q-VALID-001\n"
                + "兹证明北京施工有限公司\n北京施工有限公司负责施工\n"
                + "证书有效日期：2024年01月01日至2028年12月31日"));
        Document tender = new Document("tender", "project", "TENDER", "招标文件.docx", 1,
                "tender-sha", "2026-10-19T00:00:00Z",
                List.of(page(100, "投标截止时间为：2026年08月05日")), List.of());
        CheckSpec spec = specWithRequirement("objective-effective-certificate", "OBJECTIVE_SCORING",
                TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "施工单位具备有效的质量管理体系认证证书的",
                        "aggregation", "HIGHEST", "maxScore", "1", "scoreUnit", "分",
                        "deduplicateBySubject", true), scoringRule);

        TemplateEvidenceAssembler.Assembly assembly = assembler.assemble(spec, checkpoint(spec), bid, tender, List.of());
        List<Observation.PriceObservation> scores = assembly.evidenceView()
                .observations(Observation.PriceObservation.class);

        assertEquals(1, scores.size());
        assertEquals("1", scores.get(0).amount().toPlainString());
        assertEquals(Set.of("bid", "tender"), scores.get(0).sources().stream()
                .map(SourceRef::documentId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
    }

    @Test void binaryMaterialScoringDoesNotGuessAmongMultipleCriterionRows() {
        SourceRef scoringTable = new SourceRef("tender", 1, "tender-sha", "TEXT", "score-table", "BLOCK", 453,
                "施工单位具备质量管理体系认证证书，得1分；施工单位具备环境管理体系认证证书，得1分",
                null, null);
        Document bid = doc(page(449, "本项目证明文件\n质量管理体系认证证书\n证书编号：Q-001\n有效期至2028年09月03日"));
        CheckSpec spec = specWithRequirement("objective-ambiguous", "OBJECTIVE_SCORING",
                TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "施工单位具备质量管理体系认证证书和环境管理体系认证证书",
                        "aggregation", "HIGHEST", "maxScore", "1",
                        "scoreUnit", "分", "deduplicateBySubject", true), scoringTable);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertTrue(assembly.evidenceView().observations(Observation.PriceObservation.class).isEmpty());
        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
    }

    @Test void binaryMaterialScoringRejectsCertificateHeldByWrongConsortiumRole() {
        SourceRef scoringRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "role-rule", "BLOCK", 453,
                "施工单位具备质量管理体系认证证书，得1分", null, null);
        Document bid = doc(
                page(1, "本项目投标文件\n联合体投标"),
                page(2, "本项目证明文件\n质量管理体系认证证书\n证书编号：Q-002\n"
                        + "兹证明戴工设计有限公司\n有效日期：2025年01月01日至2028年12月31日"),
                page(3, "联合体协议书\n戴工设计有限公司负责设计；北京施工有限公司负责施工"));
        CheckSpec spec = specWithRequirement("objective-role", "OBJECTIVE_SCORING",
                TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "施工单位具备质量管理体系认证证书", "aggregation", "HIGHEST",
                        "maxScore", "1", "scoreUnit", "分", "deduplicateBySubject", true), scoringRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertTrue(assembly.evidenceView().observations(Observation.PriceObservation.class).isEmpty());
        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
    }

    @Test void binaryMaterialScoringClosesAtZeroForAnExplicitlyExpiredCertificate() {
        SourceRef scoringRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "expired-rule", "BLOCK", 453,
                "施工单位具备质量管理体系认证证书，得1分", null, null);
        Document bid = doc(page(1, "本项目投标文件\n施工单位具备质量管理体系认证\n质量管理体系认证证书\n"
                + "证书编号：Q-003\n兹证明北京施工有限公司\n有效日期：2020年01月01日至2021年12月31日"));
        CheckSpec spec = specWithRequirement("objective-expired", "OBJECTIVE_SCORING",
                TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "施工单位具备质量管理体系认证证书", "aggregation", "HIGHEST",
                        "maxScore", "1", "scoreUnit", "分", "deduplicateBySubject", true), scoringRule);
        Document tender = new Document("tender", "project", "TENDER", "招标文件.docx", 1,
                "tender-sha", "2026-10-19T00:00:00Z",
                List.of(page(100, "投标截止时间为：2031年06月15日")), List.of());

        TemplateEvidenceAssembler.Assembly assembly = assembler.assemble(spec, checkpoint(spec), bid, tender, List.of());

        List<Observation.PriceObservation> scores = assembly.evidenceView()
                .observations(Observation.PriceObservation.class);
        assertEquals(1, scores.size());
        assertEquals("0", scores.get(0).amount().toPlainString());
        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
    }

    @Test void binaryMaterialScoringStaysOpenWhenTenderDeadlineIsMissing() {
        SourceRef scoringRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "missing-date-rule", "BLOCK", 453,
                "施工单位具备质量管理体系认证证书的，得1分", null, null);
        Document bid = doc(page(1, "本项目投标文件\n质量管理体系认证证书\n证书编号：Q-004\n"
                + "兹证明北京施工有限公司\n有效日期：2024年01月01日至2028年12月31日"));
        CheckSpec spec = specWithRequirement("objective-no-reference-date", "OBJECTIVE_SCORING",
                TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "施工单位具备质量管理体系认证证书的", "aggregation", "HIGHEST",
                        "maxScore", "1", "scoreUnit", "分", "deduplicateBySubject", true), scoringRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertTrue(assembly.evidenceView().observations(Observation.PriceObservation.class).isEmpty());
        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
    }

    @Test void binaryMaterialScoringUsesTheLabelledValidityStartAtTheTenderDeadline() {
        SourceRef scoringRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "dated-rule", "BLOCK", 453,
                "施工单位具备质量管理体系认证证书，得1分", null, null);
        Document bid = doc(page(449, "本项目其他证明文件\n施工单位具备质量管理体系认证\n"
                + "质量管理体系认证证书\n证书编号：SYN-QMS-001\n兹证明北京施工有限公司\n"
                + "北京施工有限公司负责施工\n初次认证日期：2029年01月02日\n"
                + "证书有效日期：2031年07月01日至2034年06月30日"));
        Document tender = new Document("tender", "project", "TENDER", "招标文件.docx", 1,
                "tender-sha", "2026-10-19T00:00:00Z",
                List.of(page(100, "投标截止时间为：2031年06月15日")), List.of());
        CheckSpec spec = specWithRequirement("objective-reference-date", "OBJECTIVE_SCORING",
                TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "施工单位具备质量管理体系认证证书", "aggregation", "HIGHEST",
                        "maxScore", "1", "scoreUnit", "分", "deduplicateBySubject", true), scoringRule);

        TemplateEvidenceAssembler.Assembly assembly = assembler.assemble(spec, checkpoint(spec), bid, tender, List.of());
        List<Observation.PriceObservation> scores = assembly.evidenceView()
                .observations(Observation.PriceObservation.class);

        assertEquals(1, scores.size());
        assertEquals("0", scores.get(0).amount().toPlainString());
        assertTrue(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
    }

    @Test void binaryMaterialScoringKeepsValidityBoundToTheRequestedCertificateBlock() {
        SourceRef scoringRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "multi-certificate-rule", "BLOCK", 453,
                "施工单位具备质量管理体系认证证书的，得1分", null, null);
        Document tender = new Document("tender", "project", "TENDER", "招标文件.docx", 1,
                "tender-sha", "2026-10-19T00:00:00Z",
                List.of(page(100, "投标截止时间为：2024年06月01日")), List.of());
        CheckSpec spec = specWithRequirement("objective-multi-certificate", "OBJECTIVE_SCORING",
                TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "施工单位具备质量管理体系认证证书的", "aggregation", "HIGHEST",
                        "maxScore", "1", "scoreUnit", "分", "deduplicateBySubject", true), scoringRule);

        Document unrelatedFirst = doc(page(1, "本项目投标文件\n环境管理体系认证证书\n证书编号：E-001\n"
                + "兹证明北京施工有限公司\n证书有效日期：2019年01月01日至2020年12月31日\n"
                + "质量管理体系认证证书\n证书编号：Q-001\n兹证明北京施工有限公司\n"
                + "北京施工有限公司负责施工\n证书有效日期：2020年01月01日至2025年12月31日"));
        TemplateEvidenceAssembler.Assembly eligible = assembler.assemble(spec, checkpoint(spec), unrelatedFirst, tender, List.of());
        assertEquals("1", eligible.evidenceView().observations(Observation.PriceObservation.class)
                .get(0).amount().toPlainString());

        Document requestedFirst = doc(page(1, "本项目投标文件\n质量管理体系认证证书\n证书编号：Q-002\n"
                + "兹证明北京施工有限公司\n北京施工有限公司负责施工\n"
                + "证书有效日期：2025年01月01日至2028年12月31日\n"
                + "环境管理体系认证证书\n证书编号：E-002\n兹证明北京施工有限公司\n"
                + "证书有效日期：2020年01月01日至2025年12月31日"));
        TemplateEvidenceAssembler.Assembly ineligible = assembler.assemble(spec, checkpoint(spec), requestedFirst, tender, List.of());
        assertEquals("0", ineligible.evidenceView().observations(Observation.PriceObservation.class)
                .get(0).amount().toPlainString());
    }

    @Test void binaryMaterialScoringRequiresTenderSourceInsteadOfCriterionTextFallback() {
        Document bid = doc(page(449, "本项目证明文件\n质量管理体系认证证书\n证书编号：Q-001\n有效期至2028年09月03日"));
        CheckSpec spec = spec("OBJECTIVE_SCORING", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion", "具备质量管理体系认证证书，得1分", "aggregation", "HIGHEST",
                        "maxScore", "1", "scoreUnit", "分", "deduplicateBySubject", true),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertTrue(assembly.evidenceView().observations(Observation.PriceObservation.class).isEmpty());
        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":scoring"));
    }

    @Test void qualityAdviceCoversDimensionsButRemainsHumanJudgment() {
        Document bid = doc(page(1, "本项目技术方案\n技术方案\n本方案具有完整性和可行性，包含详细实施措施。"));
        CheckSpec spec = spec("QUALITY_ADVISORY", TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType", "技术方案", "dimensions", List.of("完整性", "可行性")),
                SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());

        assertEquals(TemplateTypes.ConclusionState.SATISFIED, execution.projection().conclusion().state());
        assertEquals(TemplateTypes.ActionOwner.USER, execution.result().actionOwner());
        assertNotEquals(TemplateTypes.ConclusionState.NOT_SATISFIED, execution.projection().conclusion().state());
    }

    @Test void qualityCompletenessExpandsEnumeratedRequirementAndCollectsCrossPageSectionBodies() {
        SourceRef qualityRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "quality-rule", "BLOCK", 459,
                "设计方案完整性：对本项目的建设目标、现状分析、设计理念、设计重难点、解决方案5项内容齐全得1分",
                null, null);
        String body = "本项目方案结合现场情况进行详细论证，明确工作边界、技术路线、实施步骤和保障措施，"
                + "形成可执行、可检查、可追溯的设计内容，并说明各专业之间的协调关系和落地安排。";
        Document bid = doc(
                page(1, "目录\n设计方案................5\n施工组织设计................38"),
                page(2, "4.1 设计方案\n第一章、对本项目的建设目标\n第二章、现状分析\n第三章、设计理念\n第四章、设计重难点识别\n第五章、解决方案"),
                page(3, "第一章、对本项目的建设目标\n合成正文样例。\n" + body),
                page(4, "第二章、现状分析\n合成正文样例。\n" + body),
                page(5, "第三章、设计理念\n合成正文样例。\n" + body),
                page(6, "第四章、设计重难点识别\n合成正文样例。\n" + body),
                page(7, "第五章、解决方案\n合成正文样例。\n" + body),
                page(8, "4.2 施工组织设计\n建设目标、现状分析、设计理念、设计重难点、解决方案仅为上一节标题"));
        CheckSpec spec = specWithRequirement("quality-sections", "QUALITY_ADVISORY",
                TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType", "设计方案", "dimensions", List.of("完整性")), qualityRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());

        assertEquals(TemplateTypes.ConclusionState.SATISFIED, execution.projection().conclusion().state());
        assertEquals(5, execution.projection().subchecks().size());
        assertEquals(TemplateTypes.ActionOwner.USER, execution.result().actionOwner());
        Set<Integer> pages = assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .filter(value -> value.commitmentPoints().stream().anyMatch(point -> point.startsWith("DIMENSION:")))
                .flatMap(value -> value.sources().stream()).map(SourceRef::location)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(3, 4, 5, 6, 7), pages);
    }

    @Test void qualitySectionStopsAtNextPeerHeading() {
        SourceRef qualityRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "quality-boundary", "BLOCK", 459,
                "设计方案完整性：建设目标、现状分析2项内容齐全得1分", null, null);
        Document bid = doc(
                page(1, "4.1 设计方案\n本项目设计方案正文"),
                page(2, "4.1.1 建设目标\n围绕本项目功能定位、交付目标和实施路径进行详细说明。"),
                page(3, "4.2 施工组织方案\n现状分析\n这里属于后续施工组织章节，不属于设计方案。"));
        CheckSpec spec = specWithRequirement("quality-boundary", "QUALITY_ADVISORY",
                TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType", "设计方案", "dimensions", List.of("完整性")), qualityRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        List<Observation.MaterialObservation> materials = assembly.evidenceView()
                .observations(Observation.MaterialObservation.class);

        assertTrue(materials.stream().flatMap(value -> value.commitmentPoints().stream())
                .anyMatch("DIMENSION:建设目标:SUPPORTED"::equals));
        assertFalse(materials.stream().flatMap(value -> value.commitmentPoints().stream())
                .anyMatch("DIMENSION:现状分析:SUPPORTED"::equals));
        assertTrue(materials.stream().flatMap(value -> value.sources().stream())
                .noneMatch(source -> source.location() == 3));
    }

    @Test void qualityOutlineDoesNotReenterThroughTheLegacyFallback() {
        SourceRef qualityRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "quality-outline-only", "BLOCK", 459,
                "设计方案应说明建设目标", null, null);
        Document bid = doc(
                page(1, "4.1 设计方案（合成提纲）\n第一章、合成项目建设目标"),
                page(2, "4.2 施工组织设计\n后续章节围绕施工资源、进度计划和现场管理进行详细说明。"));
        CheckSpec spec = specWithRequirement("quality-outline-only", "QUALITY_ADVISORY",
                TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType", "设计方案", "dimensions", List.of("建设目标")), qualityRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());

        assertTrue(assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.commitmentPoints().stream())
                .noneMatch("DIMENSION:建设目标:SUPPORTED"::equals));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED, execution.projection().conclusion().state());
    }

    @Test void unnumberedQualityOutlineDoesNotReenterThroughTheLegacyFallback() {
        SourceRef qualityRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "quality-unnumbered-outline", "BLOCK", 459,
                "设计方案应说明建设目标", null, null);
        Document bid = doc(page(1, "设计方案（合成提纲）\n第一章、合成项目建设目标"));
        CheckSpec spec = specWithRequirement("quality-unnumbered-outline", "QUALITY_ADVISORY",
                TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType", "设计方案", "dimensions", List.of("建设目标")), qualityRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertTrue(assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.commitmentPoints().stream())
                .noneMatch("DIMENSION:建设目标:SUPPORTED"::equals));
    }

    @Test void qualitySectionStopsAtTheNextBareRootChapter() {
        SourceRef qualityRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "quality-root-boundary", "BLOCK", 459,
                "设计方案应说明建设目标和现状分析", null, null);
        String body = "围绕本项目功能定位和交付目标进行详细论证，明确技术路线、实施步骤、风险控制和保障措施。";
        Document bid = doc(
                page(1, "4.1 设计方案\n第一章、建设目标\n" + body),
                page(2, "5 施工组织设计\n第二章、现状分析\n" + body));
        CheckSpec spec = specWithRequirement("quality-root-boundary", "QUALITY_ADVISORY",
                TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType", "设计方案", "dimensions", List.of("建设目标", "现状分析")), qualityRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        List<Observation.MaterialObservation> materials = assembly.evidenceView()
                .observations(Observation.MaterialObservation.class);

        assertTrue(materials.stream().flatMap(value -> value.commitmentPoints().stream())
                .anyMatch("DIMENSION:建设目标:SUPPORTED"::equals));
        assertFalse(materials.stream().flatMap(value -> value.commitmentPoints().stream())
                .anyMatch("DIMENSION:现状分析:SUPPORTED"::equals));
        assertTrue(materials.stream().flatMap(value -> value.sources().stream())
                .noneMatch(source -> source.location() == 2));
    }

    @Test void innerBareNumberDoesNotEndTheQualitySection() {
        SourceRef qualityRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "quality-inner-number", "BLOCK", 459,
                "设计方案应说明建设目标", null, null);
        String body = "围绕本项目功能定位和交付目标进行详细论证，明确技术路线、实施步骤、风险控制和保障措施。";
        Document bid = doc(page(1, "4.1 设计方案\n5 功能需求分析\n第一章、建设目标\n" + body));
        CheckSpec spec = specWithRequirement("quality-inner-number", "QUALITY_ADVISORY",
                TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType", "设计方案", "dimensions", List.of("建设目标")), qualityRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertTrue(assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.commitmentPoints().stream())
                .anyMatch("DIMENSION:建设目标:SUPPORTED"::equals));
    }

    @Test void innerTechnicalAnalysisNumberDoesNotEndTheQualitySection() {
        SourceRef qualityRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "quality-inner-technical", "BLOCK", 459,
                "设计方案应说明建设目标", null, null);
        String body = "围绕本项目功能定位和交付目标进行详细论证，明确技术路线、实施步骤、风险控制和保障措施。";
        Document bid = doc(page(1, "4.1 设计方案\n5 技术需求分析\n第一章、建设目标\n" + body));
        CheckSpec spec = specWithRequirement("quality-inner-technical", "QUALITY_ADVISORY",
                TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType", "设计方案", "dimensions", List.of("建设目标")), qualityRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertTrue(assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.commitmentPoints().stream())
                .anyMatch("DIMENSION:建设目标:SUPPORTED"::equals));
    }

    @Test void precedingBareNumberSequencePreventsAFalseRootBoundary() {
        SourceRef qualityRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "quality-inner-sequence", "BLOCK", 459,
                "设计方案应说明建设目标", null, null);
        String body = "围绕本项目功能定位和交付目标进行详细论证，明确技术路线、实施步骤、风险控制和保障措施。";
        Document bid = doc(page(1, "4.1 设计方案\n1 背景\n2 目标\n3 范围\n4 路线\n5 质量管理方案\n"
                + "第一章 建设目标\n" + body));
        CheckSpec spec = specWithRequirement("quality-inner-sequence", "QUALITY_ADVISORY",
                TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType", "设计方案", "dimensions", List.of("建设目标")), qualityRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertTrue(assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.commitmentPoints().stream())
                .anyMatch("DIMENSION:建设目标:SUPPORTED"::equals));
    }

    @Test void futureNavigationSentenceIsNotQualityBody() {
        SourceRef qualityRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "quality-future-navigation", "BLOCK", 459,
                "设计方案应说明建设目标", null, null);
        Document bid = doc(page(1, "4.1 设计方案\n说明：本页仅为导航，建设目标将在后续章节中详细说明。"));
        CheckSpec spec = specWithRequirement("quality-future-navigation", "QUALITY_ADVISORY",
                TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType", "设计方案", "dimensions", List.of("建设目标")), qualityRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertTrue(assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.commitmentPoints().stream())
                .noneMatch("DIMENSION:建设目标:SUPPORTED"::equals));
    }

    @Test void unrelatedPeerChapterCannotSupplyThePreviousDimensionBody() {
        SourceRef qualityRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "quality-peer-body", "BLOCK", 459,
                "设计方案应说明建设目标", null, null);
        String teamBody = "本章详细列出项目团队岗位、职责分工、人员投入、沟通机制和资源保障安排。";
        Document bid = doc(page(1, "4.1 设计方案\n第一章 建设目标\n第二章 团队配置\n" + teamBody));
        CheckSpec spec = specWithRequirement("quality-peer-body", "QUALITY_ADVISORY",
                TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType", "设计方案", "dimensions", List.of("建设目标")), qualityRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());

        assertTrue(assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.commitmentPoints().stream())
                .noneMatch("DIMENSION:建设目标:SUPPORTED"::equals));
    }

    @Test void qualitySectionIgnoresInnerNumbersAndOutlineButKeepsNestedDimensionSectionBody() {
        SourceRef qualityRule = new SourceRef("tender", 1, "tender-sha", "TEXT", "quality-real-layout", "BLOCK", 459,
                "设计方案完整性：对本项目的建设目标、现状分析、设计理念、设计重难点、解决方案5项内容齐全得1分",
                null, null);
        String body = "围绕本项目现状和交付目标进行详细论证，明确技术路线、实施步骤、专业协同、风险控制和保障措施，"
                + "形成可执行、可检查、可追溯的设计正文，并说明各项安排如何在本项目中落地。";
        String overviewExplanation = "本页中心节点为设计方案，五个分支只是章节导航和图标说明。"
                + "这些标题用于展示文档结构，不代表各项正文已经在本页展开；后续各章将分别给出详细分析、技术路线和实施措施。"
                + "本段是思维导图的识别说明，不得作为任一评价维度的正文证据。";
        Document bid = doc(
                page(1, "4.1 设计方案\n设计方案\n第一章、对本项目的建设目标\n第二章、现状分析\n第三章、设计理念\n"
                        + "第四章、设计重难点识别\n第五章、解决方案\n说明：\n" + overviewExplanation
                        + "\n第一章、对本项目的建设目标\n第一节、集成建设目标\n标准化接口、微服务架构和运维体系。"),
                page(2, "第一节、集成建设目标\n" + body),
                page(3, "第二章、合成现状分析\n第一节、合成项目需求分析\n1、合成功能需求分析\n" + body
                        + "\n20 厘米，以承受重型车辆的频繁碾压，同时设置防护栏杆。"),
                page(4, "现状分析的延续正文\n" + body),
                page(5, "第三章、合成设计理念\n第一节、合成建设目标理解\n" + body),
                page(6, "第四章、合成设计重难点识别\n第一节、合成空间整合难点\n" + body),
                page(7, "第五章、合成解决方案\n第一节、合成设计优化措施\n" + body),
                page(8, "4.2 施工组织设计\n第三章、设计理念\n" + body));
        CheckSpec spec = specWithRequirement("quality-real-layout", "QUALITY_ADVISORY",
                TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType", "设计方案", "dimensions", List.of("完整性")), qualityRule);

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());

        assertEquals(TemplateTypes.ConclusionState.SATISFIED, execution.projection().conclusion().state());
        Map<String, Integer> evidencePages = assembly.evidenceView()
                .observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.commitmentPoints().stream()
                        .filter(point -> point.startsWith("DIMENSION:") && point.endsWith(":SUPPORTED"))
                        .map(point -> Map.entry(point.substring("DIMENSION:".length(),
                                point.length() - ":SUPPORTED".length()), value.sources().get(0).location())))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertEquals(Map.of("建设目标", 2, "现状分析", 3, "设计理念", 5, "设计重难点", 6, "解决方案", 7),
                evidencePages);
        assertTrue(assembly.evidenceView().observations(Observation.MaterialObservation.class).stream()
                .flatMap(value -> value.sources().stream()).noneMatch(source -> source.location() == 1
                        || source.location() == 8));
    }

    @Test void externalCheckNeverUsesUploadedDocumentTextAsExternalProof() {
        Document bid = doc(page(1, "本项目投标人声明：经信用中国查询无失信记录。"));
        CheckSpec spec = spec("EXTERNAL_CHECK", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("source", "信用中国", "queryObject", "投标人",
                        "requiredFields", List.of("dishonestJudgmentDebtor"),
                        "asOfDate", "2026-10-19", "adapterId", "CREDIT_CHINA",
                        "evaluationStage", "BID_OPENING"), SubjectSelector.bidder());

        TemplateEvidenceAssembler.Assembly assembly = assemble(spec, bid, List.of());
        TemplateExecution execution = executor.execute(spec, assembly.evidenceView());

        assertTrue(assembly.evidenceView().observations().isEmpty());
        assertFalse(assembly.evidenceView().closedNeedKeys().contains(spec.id() + ":external-record"));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED, execution.projection().conclusion().state());
        assertEquals(TemplateTypes.ActionOwner.EXTERNAL, execution.result().actionOwner());
    }

    @Test void externalCheckAcceptsOnlyARecordBoundToTheTrustedConnectorContract() {
        Document bid = doc(page(1, "本项目投标文件"));
        CheckSpec spec = spec("EXTERNAL_CHECK", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("source", "信用中国", "queryObject", "投标人",
                        "requiredFields", List.of("dishonestJudgmentDebtor"),
                        "asOfDate", "2026-10-19", "adapterId", "CREDIT_CHINA",
                        "evaluationStage", "BID_OPENING"), SubjectSelector.bidder());
        Evidence connector = new Evidence("credit-china", "信用中国", 0, "TRUSTED_CONNECTOR",
                "信用中国于2026年10月19日返回投标人查询记录",
                "adapter:CREDIT_CHINA|asOf:2026-10-19|stage:BID_OPENING", 1);
        Fact trusted = new Fact("TRUSTED_CONNECTOR", "投标人", "dishonestJudgmentDebtor",
                "2026年10月19日查询结果：未列入", "CLEAR", connector);

        TemplateEvidenceAssembler.Assembly accepted = assemble(spec, bid, List.of(trusted));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec, accepted.evidenceView()).projection().conclusion().state());
        assertEquals("credit-china", accepted.evidenceView().observations().get(0).sources().get(0).documentId());

        Evidence unauthenticated = new Evidence("credit-china", "信用中国", 0, "TRUSTED_CONNECTOR",
                connector.quote(), "PROVIDED_NOT_AUTHENTICATED|adapter:CREDIT_CHINA|asOf:2026-10-19|stage:BID_OPENING", 1);
        Fact rejected = new Fact("TRUSTED_CONNECTOR", "投标人", "dishonestJudgmentDebtor",
                trusted.rawValue(), "CLEAR", unauthenticated);
        TemplateEvidenceAssembler.Assembly ignored = assemble(spec, bid, List.of(rejected));
        assertTrue(ignored.evidenceView().observations().isEmpty());
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec, ignored.evidenceView()).projection().conclusion().state());
    }

    @Test void singleFlightReusesSameNeedAcrossSpecsButNotAcrossDocumentVersions() throws Exception {
        CheckTemplateRegistry registry = CheckTemplateRegistry.defaultRegistry();
        try (EvidenceNeedCoordinator coordinator = new EvidenceNeedCoordinator(new InMemoryEvidenceTaskRepository())) {
            TemplateEvidenceAssembler shared = new TemplateEvidenceAssembler(coordinator, registry);
            Document versionThree = doc(page(1, "本项目资格文件\n营业执照\n名称：海南测试有限公司\n统一社会信用代码：91460000123456789X"));
            CheckSpec firstSpec = spec("first-spec", "MATERIAL_PRESENT", TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                    Map.of("materialType", "营业执照", "requireBody", true), SubjectSelector.bidder());
            CheckSpec secondSpec = spec("second-spec", "MATERIAL_PRESENT", TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                    Map.of("materialType", "营业执照", "requireBody", true), SubjectSelector.bidder());
            List<Fact> noFacts = List.of();

            TemplateEvidenceAssembler.Assembly first = shared.assemble(firstSpec, checkpoint(firstSpec), versionThree, null, noFacts);
            TemplateEvidenceAssembler.Assembly reused = shared.assemble(secondSpec, checkpoint(secondSpec), versionThree, null, noFacts);
            Document versionFour = document(4, "bid-sha-3", versionThree.pages(), versionThree.warnings());
            TemplateEvidenceAssembler.Assembly changedVersion = shared.assemble(secondSpec, checkpoint(secondSpec), versionFour, null, noFacts);

            assertEquals(Boolean.TRUE, first.diagnostics().get("localScanExecuted"));
            assertEquals(Boolean.FALSE, first.diagnostics().get("inputSnapshotCacheHit"));
            assertEquals(Boolean.FALSE, reused.diagnostics().get("localScanExecuted"));
            assertEquals(Boolean.TRUE, reused.diagnostics().get("inputSnapshotCacheHit"));
            assertTrue(((Number) reused.diagnostics().get("sharedReceiptCount")).longValue() >= 1);
            assertEquals(Boolean.TRUE, changedVersion.diagnostics().get("localScanExecuted"));
            assertEquals(Boolean.FALSE, changedVersion.diagnostics().get("inputSnapshotCacheHit"));
            assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                    executor.execute(secondSpec, reused.evidenceView()).projection().conclusion().state());
        }
    }

    @Test void singleFlightKeepsMaterialSealRelationWhenDifferentSpecsProduceEachNeed() throws Exception {
        CheckTemplateRegistry registry = CheckTemplateRegistry.defaultRegistry();
        try (EvidenceNeedCoordinator coordinator = new EvidenceNeedCoordinator(new InMemoryEvidenceTaskRepository())) {
            TemplateEvidenceAssembler shared = new TemplateEvidenceAssembler(coordinator, registry);
            Document bid = doc(
                    page(1, "本项目诚信承诺书\n诚信承诺书\n我方郑重承诺依法经营并承担相应责任。"),
                    page(2, "本项目附件图像页"));
            Evidence visual = new Evidence("bid", "投标文件.pdf", 2, "PAGE",
                    "诚信承诺书投标人处可见企业印章", "visual:seal:2", 1);
            Fact observed = new Fact("SIGNATURE_SEAL", "seal-2", "sealObserved",
                    "投标人处", "OBSERVED", visual);
            Map<String, Object> common = Map.of(
                    "materialType", "诚信承诺书",
                    "requiredCommitmentPoints", List.of("依法经营", "承担相应责任"),
                    "issuerRole", "BIDDER",
                    "sealLocation", "法定代表人处");
            CheckSpec materialProducer = spec("relation-material-producer", "COMMITMENT_WITH_SEAL",
                    TemplateTypes.ProofStandard.WRITTEN_COMMITMENT, common, SubjectSelector.bidder());
            CheckSpec sealProducer = spec("relation-seal-producer", "COMMITMENT_WITH_SEAL",
                    TemplateTypes.ProofStandard.WRITTEN_COMMITMENT,
                    Map.of("materialType", "诚信承诺书",
                            "requiredCommitmentPoints", List.of("依法经营", "承担相应责任"),
                            "issuerRole", "BIDDER", "sealLocation", "投标人处"),
                    SubjectSelector.bidder());

            TemplateEvidenceAssembler.Assembly first = shared.assemble(materialProducer,
                    checkpoint(materialProducer), bid, null, List.of(observed));
            TemplateEvidenceAssembler.Assembly mixed = shared.assemble(sealProducer,
                    checkpoint(sealProducer), bid, null, List.of(observed));

            assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                    executor.execute(materialProducer, first.evidenceView()).projection().conclusion().state());
            assertTrue(((Number) mixed.diagnostics().get("sharedReceiptCount")).longValue() >= 1,
                    mixed.diagnostics().toString());
            assertEquals(TemplateEvidenceAssembler.VERSION, mixed.diagnostics().get("version"),
                    "the extractor version must invalidate persisted v3 extraction contracts");
            assertEquals(Boolean.TRUE, mixed.diagnostics().get("localScanExecuted"),
                    "the changed seal need must be produced by the second spec");
            Observation.MaterialObservation material = mixed.evidenceView()
                    .observations(Observation.MaterialObservation.class).stream()
                    .filter(value -> value.bodyPresent() && !value.titleOnly()).findFirst().orElseThrow();
            Observation.SealObservation seal = mixed.evidenceView()
                    .observations(Observation.SealObservation.class).stream().findFirst().orElseThrow();
            assertEquals(material.id(), seal.materialObservationId(),
                    "cross-spec receipts must retain the explicit material/seal relation");
            assertFalse(material.id().contains(materialProducer.id()));
            assertFalse(seal.id().contains(sealProducer.id()));
            assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                    executor.execute(sealProducer, mixed.evidenceView()).projection().conclusion().state());
        }
    }

    @Test void factBackfillInvalidatesClosedNegativeSingleFlightResult() throws Exception {
        CheckTemplateRegistry registry = CheckTemplateRegistry.defaultRegistry();
        try (EvidenceNeedCoordinator coordinator = new EvidenceNeedCoordinator(new InMemoryEvidenceTaskRepository())) {
            TemplateEvidenceAssembler shared = new TemplateEvidenceAssembler(coordinator, registry);
            Document bid = doc(page(1, "本项目投标报价表\n第A标段 投标总报价（含税）：100万元\n"
                    + "第A标段 工程费分项报价（含税）：60万元\n第A标段 设计费分项报价（含税）：40万元"));
            CheckSpec spec = spec("fact-recovery", "PRICE_RECONCILE", TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                    Map.of("totalField", "totalPrice", "componentFields", List.of("constructionPrice", "designPrice"),
                            "currency", "CNY", "unit", "元", "taxBasis", "INCLUSIVE", "lot", "A",
                            "tolerance", "0"), SubjectSelector.bidder());

            TemplateEvidenceAssembler.Assembly before = shared.assemble(spec, checkpoint(spec), bid, null, List.of());
            List<Fact> recoveredFacts = List.of(
                    priceFact("totalPrice", "第A标段 投标总报价（含税）：100万元", "CNY_INCLUSIVE:1000000", "p1-total"),
                    priceFact("constructionPrice", "第A标段 工程费分项报价（含税）：60万元", "CNY_INCLUSIVE:600000", "p1-construction"),
                    priceFact("designPrice", "第A标段 设计费分项报价（含税）：40万元", "CNY_INCLUSIVE:400000", "p1-design"));
            TemplateEvidenceAssembler.Assembly after = shared.assemble(spec, checkpoint(spec), bid, null, recoveredFacts);

            assertNotEquals(TemplateTypes.ConclusionState.SATISFIED,
                    executor.execute(spec, before.evidenceView()).projection().conclusion().state());
            assertEquals(Boolean.TRUE, after.diagnostics().get("localScanExecuted"));
            assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                    executor.execute(spec, after.evidenceView()).projection().conclusion().state());
        }
    }

    @Test void completedPageRecoveryInvalidatesPartialTaskAndPartialNeverBecomesNegativeEvidence() throws Exception {
        CheckTemplateRegistry registry = CheckTemplateRegistry.defaultRegistry();
        try (EvidenceNeedCoordinator coordinator = new EvidenceNeedCoordinator(new InMemoryEvidenceTaskRepository())) {
            TemplateEvidenceAssembler shared = new TemplateEvidenceAssembler(coordinator, registry);
            CheckSpec spec = spec("page-recovery", "SECTION_CHECKLIST", TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                    Map.of("requiredItems", List.of("营业执照"), "requireBody", true), SubjectSelector.bidder());
            Document deferred = document(3, "same-outer-hash",
                    List.of(new Page(1, "[OCR_DEFERRED]", "PAGE", "QWEN_PDF_OCR", "[OCR未完成]")), List.of());
            Document recovered = document(3, "same-outer-hash", List.of(page(1,
                    "本项目资格文件\n营业执照\n名称：海南测试有限公司\n统一社会信用代码：91460000123456789X")), List.of());

            TemplateEvidenceAssembler.Assembly partial = shared.assemble(spec, checkpoint(spec), deferred, null, List.of());
            TemplateEvidenceAssembler.Assembly complete = shared.assemble(spec, checkpoint(spec), recovered, null, List.of());

            assertTrue(partial.evidenceView().closedNeedKeys().isEmpty());
            assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                    executor.execute(spec, partial.evidenceView()).projection().conclusion().state());
            assertEquals("PARTIAL_SCOPE", receiptStatus(partial));
            assertEquals(Boolean.TRUE, complete.diagnostics().get("localScanExecuted"));
            assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                    executor.execute(spec, complete.evidenceView()).projection().conclusion().state());
        }
    }

    private TemplateEvidenceAssembler.Assembly assemble(CheckSpec spec, Document bid, List<Fact> facts) {
        return assembler.assemble(spec, checkpoint(spec), bid, null, facts);
    }

    private CheckSpec spec(String template, TemplateTypes.ProofStandard standard, Map<String, Object> parameters,
                           SubjectSelector selector) {
        return spec("spec-" + template, template, standard, parameters, selector);
    }

    private CheckSpec spec(String id, String template, TemplateTypes.ProofStandard standard,
                           Map<String, Object> parameters, SubjectSelector selector) {
        List<ParameterBinding> bindings = parameters.entrySet().stream()
                .map(entry -> new ParameterBinding(entry.getKey(), entry.getValue(), "TENDER_SOURCE",
                        "测试要求原文", List.of(requirement))).toList();
        TemplateTypes.VerificationMode mode = "EXTERNAL_CHECK".equals(template)
                ? TemplateTypes.VerificationMode.EXTERNAL
                : "QUALITY_ADVISORY".equals(template)
                ? TemplateTypes.VerificationMode.HUMAN_JUDGMENT : TemplateTypes.VerificationMode.DOCUMENT;
        return new CheckSpec(id, 1, "item-" + id, "checkpoint-" + id,
                TemplateTypes.Category.QUALIFICATION, TemplateTypes.Purpose.MANDATORY,
                mode, template, "1.0", selector, parameters,
                Applicability.applies(), standard, List.of(requirement), bindings, Map.of(), Map.of(),
                Map.of("state", "FULL", "complete", true), true, Confirmation.legacyConfirmed(), "semantic-" + template);
    }

    private CheckSpec specWithRequirement(String id, String template, TemplateTypes.ProofStandard standard,
                                          Map<String, Object> parameters, SourceRef source) {
        List<ParameterBinding> bindings = parameters.entrySet().stream()
                .map(entry -> new ParameterBinding(entry.getKey(), entry.getValue(), "TENDER_SOURCE",
                        "测试要求原文", List.of(source))).toList();
        TemplateTypes.VerificationMode mode = "QUALITY_ADVISORY".equals(template)
                ? TemplateTypes.VerificationMode.HUMAN_JUDGMENT : TemplateTypes.VerificationMode.DOCUMENT;
        return new CheckSpec("spec-" + id, 1, "item-" + id, "checkpoint-" + id,
                TemplateTypes.Category.QUALIFICATION, TemplateTypes.Purpose.SCORING,
                mode, template, "1.0", SubjectSelector.bidder(), parameters,
                Applicability.applies(), standard, List.of(source), bindings, Map.of(), Map.of(),
                Map.of("state", "FULL", "complete", true), true,
                Confirmation.legacyConfirmed(), "semantic-" + id);
    }

    private Checkpoint checkpoint(CheckSpec spec) {
        return new Checkpoint(spec.checkpointId(), spec.templateId(), "资格检查", "HIGH", "SEMANTIC_RESPONSE",
                "", "", "", "", true, true, true,
                new Evidence("tender", "招标文件", 1, "PAGE", "招标要求", "p1-l1", 1));
    }

    private static Document doc(Page... pages) {
        return document(3, "bid-sha-3", List.of(pages), List.of());
    }

    private static Document document(int version, String hash, List<Page> pages, List<String> warnings) {
        return new Document("bid", "project", "BID", "投标文件.pdf", version, hash, "2026-10-19T00:00:00Z",
                pages, warnings);
    }

    private static Page page(int number, String text) {
        return new Page(number, text, "PAGE", "QWEN_PDF_OCR", "");
    }

    private static Evidence evidence(int page, String quote, String anchor) {
        return new Evidence("bid", "投标文件.pdf", page, "PAGE", quote, anchor, 1);
    }

    private static Fact priceFact(String field, String raw, String normalized, String anchor) {
        return new Fact("PRICE", "PRICE", field, raw, normalized, evidence(1, raw, anchor));
    }

    private static String currentCredentialRoster() {
        return "本项目人员配置表\n"
                + "| 姓名 | 岗位 | 身份证号码 | 工作单位 | 证书名称 | 证书编号 | 有效期至 |\n"
                + "| --- | --- | --- | --- | --- | --- | --- |\n"
                + "| 张三 | 项目经理 | 110101199001011234 | 海南测试有限公司 | 建造师注册证 | A12345 | 2027年12月31日 |";
    }

    @SuppressWarnings("unchecked")
    private static String receiptStatus(TemplateEvidenceAssembler.Assembly assembly) {
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) assembly.diagnostics().get("receipts");
        return String.valueOf(receipts.get(0).get("status"));
    }

    private static void assertSourceBound(TemplateEvidenceAssembler.Assembly assembly, Document document) {
        assertFalse(assembly.evidenceView().observations().isEmpty());
        for (Observation observation : assembly.evidenceView().observations()) {
            assertFalse(observation.sources().isEmpty(), observation.id());
            for (SourceRef source : observation.sources()) {
                assertEquals(document.id(), source.documentId());
                assertEquals(document.version(), source.documentVersion());
                assertEquals(document.sha256(), source.documentHash());
            }
        }
    }
}
