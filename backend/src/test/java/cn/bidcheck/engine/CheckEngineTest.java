package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckEngineTest {
    private final FactExtractor facts=new FactExtractor();
    private final CheckEngine engine=new CheckEngine();
    private Document doc(Page... pages) { return new Document("bid","project","BID","投标文件",1,"hash","2026-10-03",List.of(pages),List.of()); }
    private Page page(int number,String text) { return new Page(number,text,"PAGE","PDFBOX",null); }
    private Checkpoint cp(String type,String field,String operator,String expected,String unit) {
        return new Checkpoint("check","测试检查","测试","HIGH",type,field,operator,expected,unit,true,true,true,new Evidence("tender","招标文件",3,"PAGE","招标原文要求","p3-l1",1));
    }
    private Evaluation evaluate(Checkpoint cp,Document doc) {
        // These legacy date-arithmetic fixtures concern explicitly current bid credentials.
        // Unknown and historical subjects are exercised separately in FactScopePolicyTest.
        if("DATE_VALIDITY".equals(cp.type())) doc=new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),doc.version(),doc.sha256(),doc.createdAt(),
                doc.pages().stream().map(p->new Page(p.number(),"本项目证件\n"+p.text(),p.locationType(),p.parser(),p.warning(),p.layout())).toList(),doc.warnings());
        return engine.evaluate(cp,doc,new FactExtractor().extract(doc));
    }
    private Checkpoint vehicle() { return cp("ENTITY_FIELD_CONSISTENCY","plateNumber","=","",""); }

    @Test void crossPageVinConflictHasBothOriginalEvidenceLocations() {
        Document doc=doc(page(18,"车辆信息表\n车牌号码：渝A12345\nVIN：LSVA123456789"),page(137,"车辆行驶证\n号牌号码：渝A54321\n车辆识别代号：LSVA123456789"));
        Evaluation result=evaluate(vehicle(),doc);
        assertEquals("FAIL",result.status());
        assertEquals(Set.of(18,137),new HashSet<>(result.evidences().stream().map(Evidence::page).toList()));
        assertTrue(result.evidences().stream().allMatch(e -> e.quote().contains("LSVA123456789")));
    }
    @Test void differentVehiclesAreNotComparedByOwnerOrByDocumentProximity() {
        Document doc=doc(page(18,"车牌号：渝A12345 VIN：LSVA123456789 所有人：重庆公司"),page(137,"车牌号：渝A54321 VIN：LSVB987654321 所有人：重庆公司"));
        assertEquals("UNDETERMINED",evaluate(vehicle(),doc).status());
    }
    @Test void weakAlignmentCannotCreateDeterminateVehicleConflict() {
        Document doc=doc(page(18,"车牌号：渝A12345\n所有人：重庆公司\n车型：小型客车"),page(137,"车牌号：渝A54321\n所有人：重庆公司\n车型：小型客车"));
        assertEquals("UNDETERMINED",evaluate(vehicle(),doc).status());
    }
    @Test void neighboringVehicleCannotDonateItsVin() {
        Document doc=doc(page(1,"车牌号：渝A12345\n车牌号：渝A54321\nVIN：LSVA123456789"));
        List<Fact> plates=facts.extract(doc).stream().filter(f->f.field().equals("plateNumber")).toList();
        assertTrue(plates.get(0).entityKey().startsWith("UNKNOWN:"));
        assertEquals("VIN:LSVA123456789",plates.get(1).entityKey());
        assertEquals("UNDETERMINED",evaluate(vehicle(),doc).status());
    }
    @Test void decimalPricesNormalizeYuanAndTenThousandYuan() {
        Document doc=doc(page(1,"投标函\n投标报价：110万元"));
        var result=evaluate(cp("VALUE_COMPARE","totalPrice","<=","1000000","CNY"),doc);
        assertEquals("FAIL",result.status());
        assertEquals("1100000",facts.extract(doc).stream().filter(f->f.field().equals("totalPrice")).findFirst().orElseThrow().normalizedValue());
        assertEquals("PASS",evaluate(cp("VALUE_COMPARE","totalPrice","<=","110","万元"),doc).status());
    }
    @Test void warrantyYearsConvertToMonthsWithoutRounding() {
        assertEquals("PASS",evaluate(cp("VALUE_COMPARE","warranty",">=","36","MONTH"),doc(page(1,"质保期：3年"))).status());
        assertEquals("FAIL",evaluate(cp("VALUE_COMPARE","warranty",">=","36","MONTH"),doc(page(1,"质保期：35个月"))).status());
    }
    @Test void workingDaysCannotBeSilentlyComparedToCalendarDays() {
        assertEquals("UNDETERMINED",evaluate(cp("VALUE_COMPARE","duration","<=","90","DAY"),doc(page(1,"工期：60个工作日"))).status());
    }
    @Test void aMereMaterialMentionOrPromiseIsInsufficient() {
        var rule=cp("MATERIAL_EXISTENCE","material","EXISTS","营业执照","");
        assertEquals("UNDETERMINED",evaluate(rule,doc(page(1,"营业执照\n我方承诺提供营业执照。"))).status());
        assertEquals("UNDETERMINED",evaluate(rule,doc(page(1,"目录\n营业执照...........23"))).status());
    }
    @Test void unreadablePagesDoNotProveMaterialIsMissing() {
        var rule=cp("MATERIAL_EXISTENCE","material","EXISTS","营业执照","");
        var result=evaluate(rule,doc(page(1,"公司情况介绍"),new Page(2,"","PAGE","PDFBOX","扫描页需要 OCR")));
        assertEquals("UNDETERMINED",result.status());
    }
    @Test void actualMaterialContentAcrossWordParagraphsKeepsParagraphAnchors() {
        Document doc=doc(new Page(4,"营业执照","PARAGRAPH","POI",null),new Page(5,"统一社会信用代码：91350000123456789X","PARAGRAPH","POI",null));
        var result=evaluate(cp("MATERIAL_EXISTENCE","material","EXISTS","营业执照",""),doc);
        assertEquals("PASS",result.status());
        assertTrue(result.evidences().stream().allMatch(e->e.locationType().equals("PARAGRAPH")));
        assertEquals(Set.of(4,5),new HashSet<>(result.evidences().stream().map(Evidence::page).toList()));
    }
    @Test void yearlyMaterialMustCoverEveryRequestedYear() {
        Document doc=doc(page(1,"附件：2024年财务审计报告\n审计意见：标准无保留意见"));
        var result=evaluate(cp("MATERIAL_EXISTENCE","material","EXISTS","财务审计报告","YEAR:2023,2024,2025"),doc);
        assertEquals("UNDETERMINED",result.status()); assertTrue(result.reason().contains("2023")); assertTrue(result.reason().contains("2025"));
    }
    @Test void emptyEvidenceCannotPassAndForeignDocumentFactsAreIgnored() {
        Document doc=doc(page(1,"技术方案正文"));
        var rule=cp("VALUE_COMPARE","bidValidity",">=","90","DAY");
        assertEquals("UNDETERMINED",evaluate(rule,doc).status());
        Fact foreign=new Fact("TIME_REQUIREMENT","time","bidValidity","投标有效期：90天","90",new Evidence("other","其他项目",1,"PAGE","90天","a",1));
        assertEquals("UNDETERMINED",engine.evaluate(rule,doc,List.of(foreign)).status());
    }
    @Test void certificateExpiresBeforeBidDeadlineFails() {
        var result=evaluate(cp("DATE_VALIDITY","certificateExpiry",">=","2026-10-20","DATE"),doc(page(1,"资质证书\n有效期至：2026年10月19日")));
        assertEquals("FAIL",result.status()); assertFalse(result.evidences().isEmpty());
        assertEquals("UNDETERMINED",evaluate(cp("DATE_VALIDITY","certificateExpiry",">=","错误日期","DATE"),doc(page(1,"有效期至：2026-12-31"))).status());
    }
    @Test void noConfiguredVisionOrSemanticServiceNeverFabricatesSuccess() {
        assertEquals("UNDETERMINED",evaluate(cp("SIGNATURE_SEAL","signature","EXISTS","公章",""),doc(page(1,"已签字盖章"))).status());
        assertEquals("UNDETERMINED",evaluate(cp("SEMANTIC_RESPONSE","technicalResponse","RESPONDS","完整响应",""),doc(page(1,"全部响应"))).status());
    }
    @Test void chineseNumeralsBecomeEditableUnconfirmedCandidates() {
        var rules=new CheckpointExtractor().extract(doc(page(1,"投标有效期：不少于九十日历天。\n质保期：不少于三年。\n最高限价：100万元。")));
        assertEquals("90",rules.stream().filter(c->c.field().equals("bidValidity")).findFirst().orElseThrow().expected());
        assertEquals("36",rules.stream().filter(c->c.field().equals("warranty")).findFirst().orElseThrow().expected());
        assertTrue(rules.stream().noneMatch(Checkpoint::confirmed));
    }
    @Test void compositionCandidatesStayInsideTenderFormatSection() {
        Document doc=doc(page(1,"第一章 招标公告\n一、联系方式\n第六章 投标文件格式\n一、投标函\n二、授权委托书\n第七章 合同条款\n一、履行地点\n第八章 详细评审\n售后服务方案完整得5分。"));
        var rules=new CheckpointExtractor().extract(doc);
        assertTrue(rules.stream().anyMatch(c->c.title().equals("投标文件组成：投标函")));
        assertFalse(rules.stream().anyMatch(c->c.expected().equals("联系方式") || c.expected().equals("履行地点")));
        assertTrue(rules.stream().anyMatch(c->c.field().equals("scoring")));
    }
    @Test void disabledRuleIsNotApplicableAndUnconfirmedRuleCannotRun() {
        var base=vehicle();
        var disabled=new Checkpoint(base.id(),base.title(),base.category(),base.severity(),base.type(),base.field(),base.operator(),base.expected(),base.unit(),true,true,false,base.source());
        assertEquals("NOT_APPLICABLE",evaluate(disabled,doc(page(1,"正文"))).status());
        var candidate=new CheckpointExtractor().extract(doc(page(1,"投标有效期：90天"))).get(0);
        assertEquals("UNDETERMINED",evaluate(candidate,doc(page(1,"投标有效期：90天"))).status());
    }
    @Test void compositionHeadingRequiresActualSectionContent() {
        Checkpoint composition=new Checkpoint("check","实施计划完整性","组成检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS","实施计划","",true,true,true,cp("MATERIAL_EXISTENCE","material","EXISTS","实施计划","").source());
        assertEquals("UNDETERMINED",evaluate(composition,doc(page(1,"三、实施计划"))).status());
        Document actual=doc(page(1,"三、实施计划\n第一阶段由项目经理组织现场调研并完成需求确认。第二阶段完成设备部署、系统联调和用户培训。第三阶段组织验收并建立售后响应机制。"));
        assertEquals("PASS",evaluate(composition,actual).status());
    }
    @Test void invalidManuallyEditedNumericRuleRemainsUndetermined() {
        var emptyExpected=cp("VALUE_COMPARE","bidValidity",">=",null,"DAY");
        assertEquals("UNDETERMINED",evaluate(emptyExpected,doc(page(1,"投标有效期：90天"))).status());
    }
    @Test void compositionFindsBodyAfterNestedHeadingsAndPreservesActualSourceQuote() {
        String body="本项目先组织现场调查并核实设备需求，再分阶段完成安装联调和验收工作。项目经理统筹人员与工期，质量负责人按周核查施工记录。";
        String text="【原生文字】\n\n7. 实施计划\n7.1 总体安排\n7.1.1 建设目标\n第一章、总体安排\n第二章、现状分析\n【OCR识别文字】\n7. 实施计划\n7.1 总体安排\n第一章、总体安排\n第一节、建设目标\n\n"+body;
        var result=evaluate(composition("实施计划"),doc(page(9,text)));
        assertEquals("PASS",result.status());
        assertTrue(result.evidences().stream().anyMatch(e->e.quote().contains(body)));
        assertTrue(result.evidences().stream().allMatch(e->text.contains(e.quote()) && e.page()==9 && e.locationType().equals("PAGE")));
        assertEquals("p9-l3",result.evidences().get(0).anchor());
    }
    @Test void compositionDoesNotBorrowBodyFromAnotherMaterialOrAnotherPage() {
        String body="我方明确安排项目经理组织现场调研并完成需求确认，然后完成设备部署、系统联调和用户培训，最后组织验收并建立售后响应机制。";
        for(String text:List.of("3. 实施计划\n3.1 建设目标\n4. 服务说明\n"+body,
                "3.2 实施计划\n3.3 服务说明\n"+body,"三、实施计划\n四、服务说明\n"+body,
                "【实施计划】\n授权委托书\n"+body,"实施计划\n售后服务方案\n"+body)) {
            assertEquals("UNDETERMINED",evaluate(composition("实施计划"),doc(page(1,text))).status(),text);
        }
        assertEquals("UNDETERMINED",evaluate(composition("实施计划"),doc(page(1,"三、实施计划"),page(2,body))).status());
    }
    @Test void compositionRejectsContentsEmptySectionsAndExcessiveScanDistance() {
        for(String text:List.of("目录\n3. 实施计划\n3.1 建设目标\n3.2 施工组织\n4. 服务方案..................20",
                "附件：实施计划", "附件一：实施计划",
                "3. 实施计划\n3.1 建设目标\n3.1.1 总体安排\n3.2 现状分析",
                "3. 实施计划\n"+"子标题\n".repeat(31)+"这是距离标题过远的正文，不能依靠无界扫描确认材料的存在。".repeat(3),
                "3. 实施计划\n3.1 "+"建设目标".repeat(400)+"\n这是超过字符预算后的正文。".repeat(8))) {
            assertEquals("UNDETERMINED",evaluate(composition("实施计划"),doc(page(1,text))).status());
        }
    }
    private Checkpoint composition(String name) {
        var base=cp("MATERIAL_EXISTENCE","material","EXISTS",name,"");
        return new Checkpoint(base.id(),base.title(),"组成检查",base.severity(),base.type(),base.field(),base.operator(),base.expected(),base.unit(),true,true,true,base.source());
    }
    @Test void eachNumericClauseKeepsItsOwnOperatorAndAllRepeatedBounds() {
        var rules=new CheckpointExtractor().extract(doc(page(1,"投标有效期不少于90天，工期不超过60天；质保期不少于24个月，质保期不超过60个月。")));
        var validity=rules.stream().filter(c->c.field().equals("bidValidity")).findFirst().orElseThrow();
        assertEquals(">=",validity.operator());
        assertEquals("<=",rules.stream().filter(c->c.field().equals("duration")).findFirst().orElseThrow().operator());
        assertEquals(2,rules.stream().filter(c->c.field().equals("warranty")).count());
        assertEquals("FAIL",evaluate(cp("VALUE_COMPARE",validity.field(),validity.operator(),validity.expected(),validity.unit()),doc(page(1,"投标有效期：30天"))).status());
    }
    @Test void warrantyWorkingDaysAndMissingUnitsNeverPassAsCalendarDays() {
        assertEquals("UNDETERMINED",evaluate(cp("VALUE_COMPARE","warranty",">=","30","DAY"),doc(page(1,"质保期：30个工作日"))).status());
        assertEquals("PASS",evaluate(cp("VALUE_COMPARE","warranty",">=","30","WORKDAY"),doc(page(1,"质保期：30个工作日"))).status());
        assertEquals("UNDETERMINED",evaluate(cp("VALUE_COMPARE","duration","<=","90",""),doc(page(1,"工期：60个月"))).status());
    }
    @Test void expiryDoesNotUseLaterIssueDateOrSubstituteRangeStartForInvalidEnd() {
        var rule=cp("DATE_VALIDITY","certificateExpiry",">=","2026-10-20","DATE");
        assertEquals("PASS",evaluate(rule,doc(page(1,"资质证书有效至：2027-12-31；签发日期：2024-01-01"))).status());
        assertEquals("PASS",evaluate(rule,doc(page(1,"有效期至：2027-12-31 签发日期：2024-01-01"))).status());
        var invalid=evaluate(rule,doc(page(1,"有效期：2025-01-01至2027-02-30"),page(2,"有效至：2027-12-31")));
        assertEquals("UNDETERMINED",invalid.status());
        assertEquals(1,invalid.evidences().get(0).page());
        assertEquals("FAIL",evaluate(rule,doc(page(1,"有效期：2025-01-01至2027-02-30"),page(2,"有效至：2026-10-19"))).status());
    }
    @Test void explicitSubmissionDeadlineWinsOverOpeningDateAndKeepsMatchingSource() {
        var rules=new CheckpointExtractor().extract(doc(page(3,"投标截止时间：2026年10月20日"),page(7,"开标时间：2026年10月21日")));
        var deadline=rules.stream().filter(c->"certificateCoverage".equals(c.field())).findFirst().orElseThrow();
        assertEquals("2026-10-20",deadline.expected()); assertEquals(3,deadline.source().page());
        assertTrue(rules.stream().noneMatch(c->"DATE_VALIDITY".equals(c.type())),"A date alone must not invent a certificate requirement");
    }
    @Test void vinBetweenAdjacentPlatesIsAmbiguousRatherThanADeterminateConflict() {
        Document doc=doc(page(1,"车牌号：渝A12345\nVIN：LSVA123456789\n车牌号：渝A54321"));
        assertEquals("UNDETERMINED",evaluate(vehicle(),doc).status());
        assertTrue(facts.extract(doc).stream().filter(f->f.field().equals("plateNumber")).allMatch(f->f.entityKey().startsWith("UNKNOWN:")));
    }
    @Test void allPlatesInAFlattenedRowRemainVisibleWithoutGuessingVinAssignment() {
        Document doc=doc(page(1,"车牌号：渝A12345 VIN：LSVA123456789 车牌号：渝A54321 VIN：LSVB987654321"),page(2,"车牌号：渝A12345 VIN：LSVA123456789"));
        assertEquals(3,facts.extract(doc).stream().filter(f->f.field().equals("plateNumber")).count());
        assertEquals("UNDETERMINED",evaluate(vehicle(),doc).status());
    }
    @Test void vehicleWordIdentifiersKeepTheirOwnParagraphEvidence() {
        Document doc=doc(new Page(1,"车牌号：渝A12345","PARAGRAPH","POI",null),new Page(2,"VIN：LSVA123456789","PARAGRAPH","POI",null),new Page(3,"中间说明","PARAGRAPH","POI",null),new Page(4,"中间说明","PARAGRAPH","POI",null),new Page(5,"中间说明","PARAGRAPH","POI",null),new Page(6,"车牌号：渝A54321","PARAGRAPH","POI",null),new Page(7,"VIN：LSVA123456789","PARAGRAPH","POI",null));
        var result=evaluate(vehicle(),doc);
        assertEquals("FAIL",result.status());
        assertEquals(Set.of(1,2,6,7),new HashSet<>(result.evidences().stream().map(Evidence::page).toList()));
        for(Evidence ev:result.evidences()) assertEquals(doc.pages().stream().filter(p->p.number()==ev.page()).findFirst().orElseThrow().text(),ev.quote());
    }
    @Test void contractBuyerIdentityDoesNotBecomeABidderConflict() {
        Document doc=doc(page(1,"投标人名称：甲服务公司\n投标人统一社会信用代码：91350000123456789X"),page(2,"投标单位名称：甲服务公司\n投标人统一社会信用代码：91350000123456789X"),page(3,"历史业绩合同\n甲方公司名称：乙采购公司\n甲方统一社会信用代码：91350000987654321X\n采购人企业名称：丙业主公司"));
        assertEquals("PASS",evaluate(cp("ENTITY_FIELD_CONSISTENCY","companyName","=","",""),doc).status());
        assertEquals("PASS",evaluate(cp("ENTITY_FIELD_CONSISTENCY","creditCode","=","",""),doc).status());
        assertFalse(facts.extract(doc).stream().anyMatch(f->f.rawValue().equals("乙采购公司") || f.rawValue().equals("丙业主公司")));
    }
    @Test void genuinelyConflictingExplicitBidderIdentitiesStillFail() {
        Document doc=doc(page(1,"投标人名称：甲服务公司\n投标人统一社会信用代码：91350000123456789X"),page(2,"投标单位名称：乙服务公司\n投标人统一社会信用代码：91350000987654321X"));
        for(String field:List.of("companyName","creditCode")) {
            var result=evaluate(cp("ENTITY_FIELD_CONSISTENCY",field,"=","",""),doc);
            assertEquals("FAIL",result.status());
            assertEquals(Set.of(1,2),new HashSet<>(result.evidences().stream().map(Evidence::page).toList()));
        }
    }
    @Test void ambiguousCompanyAndCreditCodeRecordsKeepEvidenceWithoutForcedMerging() {
        Document doc=doc(page(1,"投标人名称：甲服务公司\n统一社会信用代码：91350000123456789X"),page(2,"企业名称：乙服务公司\n统一社会信用代码：91350000987654321X"),page(3,"历史合同乙方公司名称：丙服务公司"));
        for(String field:List.of("companyName","creditCode")) {
            var result=evaluate(cp("ENTITY_FIELD_CONSISTENCY",field,"=","",""),doc);
            assertEquals("UNDETERMINED",result.status());
            assertTrue(result.evidences().stream().anyMatch(ev->ev.page()==2));
        }
    }
    @Test void responseSupplierIsBidderButHistoricalContractSupplierRemainsUnresolved() {
        var rule=cp("ENTITY_FIELD_CONSISTENCY","companyName","=","","");
        assertEquals("PASS",evaluate(rule,doc(page(1,"供应商名称：甲服务公司"),page(2,"响应供应商名称：甲服务公司"))).status());
        assertEquals("UNDETERMINED",evaluate(rule,doc(page(1,"投标人名称：甲服务公司"),page(2,"历史业绩合同\n供应商名称：乙服务公司"))).status());
    }

    @Test void completeNativeTextWithoutLiteralMaterialNameCannotProveAbsence() {
        var rule=cp("MATERIAL_EXISTENCE","material","EXISTS","营业执照","");
        var result=evaluate(rule,doc(page(1,"企业登记证照\n统一社会信用代码：91350000123456789X\n名称：甲公司")));
        assertEquals("UNDETERMINED",result.status());
        assertTrue(result.reason().contains("未检索到不代表未提供"));
        assertEquals(List.of(rule.source()),result.evidences());
    }
    @Test void partialYearEvidenceKeepsTenderAndLocatedBidEvidenceWithoutClaimingAbsence() {
        var rule=cp("MATERIAL_EXISTENCE","material","EXISTS","财务审计报告","YEAR:2023,2024,2025");
        var result=evaluate(rule,doc(page(8,"附件：2024年财务审计报告\n审计意见：标准无保留意见")));
        assertEquals("UNDETERMINED",result.status());
        assertEquals(Set.of("tender","bid"),new HashSet<>(result.evidences().stream().map(Evidence::documentId).toList()));
        assertTrue(result.reason().contains("2023、2025"));
        assertTrue(result.evidences().stream().anyMatch(e->e.page()==8 && e.quote().contains("2024年财务审计报告")));
    }
}
