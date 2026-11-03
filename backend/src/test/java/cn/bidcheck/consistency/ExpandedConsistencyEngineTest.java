package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExpandedConsistencyEngineTest {
    private final ExpandedConsistencyEngine engine=new ExpandedConsistencyEngine();
    private Document doc(String... text){List<Page> pages=new ArrayList<>();for(int i=0;i<text.length;i++)pages.add(new Page(i+1,text[i],"PAGE","PDFBOX",null));return new Document("bid","project","BID","投标文件.pdf",1,"hash","",pages,cn.bidcheck.compat.Java8Api.listOf());}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Map<String,Object> report,String key){return (List<Map<String,Object>>)report.get(key);}
    private Map<String,Object> scan(String... text){return engine.scan(doc(text),cn.bidcheck.compat.Java8Api.listOf());}
    private List<Map<String,Object>> groups(String... text){return rows(scan(text),"groups");}
    private List<Map<String,Object>> relation(String type,String... text){return cn.bidcheck.compat.Java8Api.toList(groups(text).stream().filter(g->type.equals(g.get("relationType"))));}
    private Map<String,Object> supplemental(Document doc,String field,String subject,String raw,String quote,String context,int offset){Map<String,Object> fact=new LinkedHashMap<>();fact.put("field",field);fact.put("subject",subject);fact.put("scope","CURRENT_PROJECT");fact.put("rawValue",raw);fact.put("value","FORGED");fact.put("evidence",new Evidence(doc.id(),doc.name(),1,"PAGE",quote,"model",0.99));fact.put("contextQuote",context);fact.put("sourceOffset",offset);return fact;}

    @Test void catalogHasAllEighteenCategoriesAndLegacyFields(){
        assertEquals(18,ConsistencyCatalog.categories().size());
        assertTrue(ConsistencyCatalog.fields().containsAll(cn.bidcheck.compat.Java8Api.listOf("companyName", "totalPrice", "openingTime", "personName", "personRole", "custom")));
        assertTrue(ConsistencyCatalog.definitions().stream().allMatch(d->ConsistencyCatalog.categories().contains(d.get("categoryId"))));
        assertTrue(ConsistencyCatalog.definition("malicious").isEmpty());
        assertThrows(UnsupportedOperationException.class,()->ConsistencyCatalog.definition("productModel").put("title","overwrite"));
    }
    @Test void projectIdentifiersCompareAndLegacyFactsAreNotDuplicated(){
        java.util.Map<java.lang.String, java.lang.Object> report=scan("项目编号：XM-001\n项目编号：XM-002\n投标人名称：甲公司\n投标总报价：100万元");
        assertEquals(2,rows(report,"occurrences").size());assertEquals("CONFLICT",rows(report,"groups").get(0).get("status"));
    }
    @Test void sameItemDifferentModelsConflictButDifferentItemsDoNot(){
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> groups=relation("VALUE","品目：服务器A；产品型号：X100","品目：服务器A；产品型号：X200");
        assertEquals(1,groups.size());assertEquals("CONFLICT",groups.get(0).get("status"));
        assertTrue(relation("VALUE","品目：服务器A；产品型号：X100","品目：服务器B；产品型号：X200").isEmpty());
    }
    @Test void distinctVersionsRangesAndLotsDoNotCollide(){
        assertTrue(relation("VALUE","品目：服务器A；产品版本：V1；产品型号：X100","品目：服务器A；产品版本：V2；产品型号：X200").stream().noneMatch(g->g.get("field").equals("productModel")));
        assertTrue(relation("VALUE","第一标段\n品目：服务器A；产品型号：X100\n第二标段\n品目：服务器A；产品型号：X200").isEmpty());
        assertTrue(relation("VALUE","品目：服务器A；起算条件：验收合格后；适用范围：整机；质保期：3年","品目：服务器A；起算条件：验收合格后；适用范围：电池；质保期：1年").isEmpty());
    }
    @Test void multipleObjectsInOneLineAreUnresolvedInsteadOfAssignedFirstOrLast(){
        java.util.Map<java.lang.String, java.lang.Object> report=scan("品目：服务器A；产品型号：X100；品目：服务器B；产品型号：X200");
        assertTrue(rows(report,"groups").stream().noneMatch(g->g.get("status").equals("CONFLICT")));
        assertTrue(rows(report,"occurrences").stream().allMatch(f->f.get("comparability").equals("INCOMPLETE")));
    }
    @Test void historicalProductDoesNotConflictWithCurrentProduct(){
        assertTrue(relation("VALUE","品目：服务器A；产品型号：X100\n历史业绩\n品目：服务器A；产品型号：X200").isEmpty());
    }
    @Test void historicalContractsCompareOnlyWithSameExplicitContractIdentity(){
        assertEquals(1,relation("VALUE","历史业绩\n合同编号：HT-001；合同金额：100万元\n合同编号：HT-001；合同金额：110万元").size());
        assertTrue(relation("VALUE","历史业绩\n合同编号：HT-001；合同金额：100万元\n合同编号：HT-002；合同金额：110万元").isEmpty());
    }
    @Test void monthsAndYearsNormalizeWithoutAssumingThirtyDayMonths(){
        assertTrue(groups("品目：服务器A；起算条件：验收合格后；质保期：3年","品目：服务器A；起算条件：验收合格后；质保期：36个月").isEmpty());
        assertTrue(relation("VALUE","品目：服务器A；起算条件：验收合格后；质保期：1个月","品目：服务器A；起算条件：验收合格后；质保期：30日历天").isEmpty());
    }
    @Test void calendarAndWorkingDaysAndDifferentServiceEventsStaySeparate(){
        assertTrue(relation("VALUE","起算条件：合同签订后；交货期：30工作日","起算条件：合同签订后；交货期：30日历天").isEmpty());
        assertTrue(groups("品目：服务器A；响应时间：30分钟；到场时间：6小时").isEmpty());
        assertTrue(groups("品目：服务器A；响应时间：1小时","品目：服务器A；响应时间：60分钟").isEmpty());
    }
    @Test void intervalsNeedDisjointValuesToConflict(){
        assertTrue(relation("VALUE","品目：服务器A；响应时间：不超过30分钟","品目：服务器A；响应时间：20分钟").isEmpty());
        assertEquals(1,relation("VALUE","品目：服务器A；响应时间：不超过30分钟","品目：服务器A；响应时间：45分钟").size());
        assertTrue(relation("VALUE","品目：服务器A；响应时间：至少20分钟","品目：服务器A；响应时间：不超过30分钟").isEmpty());
        assertEquals(1,relation("VALUE","品目：服务器A；响应时间：小于20分钟","品目：服务器A；响应时间：至少20分钟").size());
    }
    @Test void approximateNumbersNeverBecomeExactConflicts(){
        assertTrue(relation("VALUE","品目：服务器A；数量：约3台","品目：服务器A；数量：4台").isEmpty());
    }
    @Test void latePlanRequiresSameTriggerAndCalendarBasis(){
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> late=relation("TEMPORAL","起算条件：合同签订后；交货期：30日历天；计划完成周期：45日历天");
        assertEquals(1,late.size());assertEquals("CONFLICT",late.get(0).get("status"));
        assertEquals(2,((List<?>)late.get(0).get("occurrenceIds")).size());
        assertTrue(relation("TEMPORAL","起算条件：合同签订后；交货期：30日历天","起算条件：验收合格后；计划完成周期：45日历天").isEmpty());
        assertTrue(relation("TEMPORAL","起算条件：合同签订后；交货期：30工作日；计划完成周期：45日历天").isEmpty());
        assertTrue(relation("TEMPORAL","起算条件：合同签订后；交货期：30天；计划完成周期：45天").isEmpty());
        assertEquals(1,relation("TEMPORAL","起算条件：合同签订后；交货期：小于30日历天；计划完成周期：30日历天").size());
    }
    @Test void earlierPlanAndLowerBoundDeliveryDoNotProveViolation(){
        assertTrue(relation("TEMPORAL","起算条件：合同签订后；交货期：30日历天；计划完成周期：20日历天").isEmpty());
        assertTrue(relation("TEMPORAL","起算条件：合同签订后；交货期：至少30日历天；计划完成周期：45日历天").isEmpty());
    }
    @Test void sameRowArithmeticReferencesAllOperandsAndTableHeader(){
        java.util.Map<java.lang.String, java.lang.Object> report=scan("品目\t数量（台）\t单价（元）\t行金额（元）\n服务器A\t3\t100\t250");
        java.util.Map<java.lang.String, java.lang.Object> group=rows(report,"groups").stream().filter(g->g.get("relationType").equals("ARITHMETIC")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals("CONFLICT",group.get("status"));assertEquals(3,((List<?>)group.get("occurrenceIds")).size());
        assertTrue(((List<?>)group.get("evidences")).stream().map(Evidence.class::cast).anyMatch(e->e.quote().contains("数量（台）")));
        assertTrue(((Map<?,?>)group.get("comparisonContext")).containsKey("calculation"));
    }
    @Test void arithmeticNeverBorrowsOperandsAcrossRows(){
        assertTrue(relation("ARITHMETIC","品目\t数量（台）\t单价（元）\t行金额（元）\n服务器A\t3\t100\t\n服务器B\t\t\t250").isEmpty());
        assertTrue(relation("ARITHMETIC","品目：服务器A；数量：3台\n品目：服务器A；单价：100元\n品目：服务器A；行金额：250元").isEmpty());
    }
    @Test void arithmeticRejectsUnknownQuantityUnitsAndScaledPriceDenominators(){
        assertTrue(relation("ARITHMETIC","品目\t数量\t单价（元）\t行金额（元）\n服务器A\t3\t100\t250").isEmpty());
        assertTrue(relation("ARITHMETIC","品目\t数量（台）\t单价（元/百台）\t行金额（元）\n服务器A\t3\t100\t3").isEmpty());
        assertTrue(relation("ARITHMETIC","品目\t数量（台）\t单价（元/套）\t行金额（元）\n服务器A\t3\t100\t250").isEmpty());
    }
    @Test void arithmeticUsesDisplayPrecisionAndMoneyUnitsInHeaders(){
        assertTrue(relation("ARITHMETIC","品目\t数量（台）\t单价（元）\t行金额（万元）\n服务器A\t3\t99.9\t0.03").isEmpty());
        assertTrue(relation("ARITHMETIC","品目\t数量（台）\t单价（元）\t行金额（元）\n服务器A\t3\t0.333\t1.00").isEmpty());
        assertEquals(1,relation("ARITHMETIC","品目\t数量（台）\t单价（元）\t行金额（元）\n服务器A\t3\t0.333\t0.98").size());
    }
    @Test void explicitOppositeFeePromisesNeedTheSameRangeAndPeriod(){
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> opposite=relation("SEMANTIC","服务对象：服务器A；费用范围：人工及配件；费用期间：前三年；费用包含：全部包含","服务对象：服务器A；费用范围：人工及配件；费用期间：前三年；费用包含：另行收费");
        assertEquals(1,opposite.size());assertEquals("SUSPECTED",opposite.get(0).get("status"));
        assertTrue(relation("SEMANTIC","服务对象：服务器A；费用范围：人工；费用期间：前三年；费用包含：全部包含","服务对象：服务器A；费用范围：配件；费用期间：前三年；费用包含：另行收费").isEmpty());
    }
    @Test void conditionalOrComplexPromisesDoNotBecomeKeywordContradictions(){
        assertTrue(relation("SEMANTIC","服务对象：服务器A；费用范围：人工；费用期间：前三年；费用包含：全部包含","服务对象：服务器A；费用范围：人工；费用期间：前三年；费用包含：不另行收费，但人为损坏另行收费").isEmpty());
    }
    @Test void subcontractOppositionRemainsSubjectToHumanReview(){
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> result=relation("SEMANTIC","工作项：安装；适用范围：设备安装；服务期间：施工阶段；分包承诺：不分包","工作项：安装；适用范围：设备安装；服务期间：施工阶段；分包承诺：安排分包");
        assertEquals(1,result.size());assertEquals("SUSPECTED",result.get(0).get("status"));
    }
    @Test void missingObjectOrUnitsGroupsEvidenceGapsWithoutDeclaringMissingMaterials(){
        java.util.Map<java.lang.String, java.lang.Object> report=scan("产品型号：X100\n产品型号：X200\n品目：服务器A；数量：3");
        assertEquals(2,rows(report,"groups").size());
        assertTrue(rows(report,"groups").stream().allMatch(g->g.get("status").equals("EVIDENCE_INSUFFICIENT")));
        assertTrue(rows(report,"groups").stream().allMatch(g->g.get("reason").toString().contains("不表示附件或资质不存在")));
        assertEquals(2,((List<?>)rows(report,"groups").get(0).get("occurrenceIds")).size());
    }
    @Test void paymentStagesAndPersonnelUnitTypesDoNotCollapse(){
        assertTrue(relation("VALUE","付款阶段：预付款；付款比例：30%","付款阶段：验收款；付款比例：70%").isEmpty());
        assertTrue(relation("VALUE","姓名：张三；注册单位：甲公司；社保单位：乙公司").isEmpty());
        assertTrue(relation("VALUE","品目：服务器A；起算条件：验收合格后；质保期：3年；保修期：5年").isEmpty());
    }
    @Test void certificateHoldersCompareOnlyUnderTheSameCertificateNumber(){
        assertEquals(1,relation("VALUE","证书编号：ZS-001；证书持有人：张三","证书编号：ZS-001；证书持有人：李四").size());
        assertTrue(relation("VALUE","证书编号：ZS-001；证书持有人：张三","证书编号：ZS-002；证书持有人：李四").isEmpty());
    }
    @Test void namedTechnicalParametersRemainSeparateAndDifferencesAreSuspected(){
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> result=relation("VALUE","品目：服务器A；参数名称：内存容量；技术参数：32GB","品目：服务器A；参数名称：内存容量；技术参数：64GB");
        assertEquals(1,result.size());assertEquals("SUSPECTED",result.get(0).get("status"));
        assertTrue(relation("VALUE","品目：服务器A；参数名称：内存容量；技术参数：32GB","品目：服务器A；参数名称：硬盘容量；技术参数：64GB").isEmpty());
    }
    @Test void validDatesUseRealPrecisionAndRejectInvalidDates(){
        assertTrue(relation("VALUE","开工日期：2026-11-05","开工日期：2026-11-05 09:00").isEmpty());
        assertEquals(1,relation("VALUE","开工日期：2026-11-05","开工日期：2026-11-06").size());
        assertTrue(relation("VALUE","开工日期：2026-02-30","开工日期：2026-02-28").isEmpty());
    }
    @Test void supportedCustomFieldsAreDynamicAndNeverStrong(){
        String first="本项目业务数据库备份副本数：3份",second="本项目业务数据库备份副本数：4份";
        Document document=doc(first+"\n"+second);List<Map<String,Object>> supplemental=new ArrayList<>();
        for(String line:cn.bidcheck.compat.Java8Api.listOf(first, second)){Map<String,Object> fact=supplemental(document,"custom","业务数据库",line.contains("3份")?"3份":"4份",line,line,document.pages().get(0).text().indexOf(line));fact.put("fieldLabel","备份副本数");fact.put("categoryId","solution");supplemental.add(fact);}
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(document,supplemental);java.util.Map<java.lang.String, java.lang.Object> group=rows(report,"groups").get(0);
        assertTrue(group.get("field").toString().startsWith("dynamic."));assertEquals("solution",group.get("categoryId"));assertEquals("SUSPECTED",group.get("status"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("3份", "4份"),cn.bidcheck.compat.Java8Api.toList(rows(report,"occurrences").stream().map(f->f.get("rawValue"))));
        assertTrue(rows(report,"occurrences").stream().noneMatch(f->f.get("value").equals("FORGED")));
    }
    @Test void fabricatedModelLabelsSourcesAndDimensionsAreRejectedOrIgnored(){
        String content="本项目业务数据库备份副本数：3份";Document document=doc(content);
        java.util.Map<java.lang.String, java.lang.Object> fact=supplemental(document,"custom","业务数据库","3份",content,content,0);fact.put("fieldLabel","不存在的标签");fact.put("categoryId","solution");
        assertTrue(rows(engine.scan(document,cn.bidcheck.compat.Java8Api.listOf(fact)),"occurrences").isEmpty());
        fact.put("fieldLabel","备份副本数");fact.put("dimensions",cn.bidcheck.compat.Java8Api.mapOf("version", "FORGED-V2"));
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(document,cn.bidcheck.compat.Java8Api.listOf(fact));assertEquals(1,rows(report,"occurrences").size());assertFalse(((Map<?,?>)rows(report,"occurrences").get(0).get("dimensions")).containsKey("version"));
        fact.put("sourceOffset",5);assertTrue(rows(engine.scan(document,cn.bidcheck.compat.Java8Api.listOf(fact)),"occurrences").isEmpty());
    }
    @Test void unrelatedContextCannotBeBorrowedForRepeatedModelQuote(){
        String content="本项目品目：服务器A；型号：X100\n历史业绩品目：服务器B；型号：X100";Document document=doc(content);
        java.util.Map<java.lang.String, java.lang.Object> fact=supplemental(document,"productModel","服务器A","X100","型号：X100",content.split("\n")[0],content.lastIndexOf("型号：X100"));
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(document,cn.bidcheck.compat.Java8Api.listOf(fact));
        java.util.Map<java.lang.String, java.lang.Object> modelFact=rows(report,"occurrences").stream().filter(f->f.get("extractionMethod").equals("MODEL_SOURCE_VERIFIED")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals("UNRESOLVED",modelFact.get("scope"));assertEquals("INCOMPLETE",modelFact.get("comparability"));
    }
    @Test void modelSuppliedNormalizedValuesCannotCreateAnArtificialConflict(){
        String content="本项目品目：服务器A；响应时间：60分钟";Document document=doc(content);
        java.util.Map<java.lang.String, java.lang.Object> fact=supplemental(document,"responseTime","服务器A","60分钟",content,content,0);
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(document,cn.bidcheck.compat.Java8Api.listOf(fact));assertTrue(rows(report,"occurrences").stream().allMatch(f->f.get("value").equals("60")));assertTrue(rows(report,"groups").isEmpty());
    }
    @Test void sourceBoundariesMatchLedgerAndOversizeLinesReportCoverageGap(){
        java.util.Map<java.lang.String, java.lang.Object> report=scan("项目编号：XM-001；"+cn.bidcheck.compat.Java8Api.repeat("x", 10000));assertTrue(rows(report,"occurrences").isEmpty());
        assertTrue(((Number)((Map<?,?>)report.get("coverage")).get("pagesUnparsed")).intValue()>0);
    }
    @Test void sourceOffsetsIdsAndEvidenceAreStableAndOriginal(){
        Document document=doc("品目：服务器A；型号：X100\n品目：服务器A；型号：X200");
        java.util.Map<java.lang.String, java.lang.Object> first=engine.scan(document,cn.bidcheck.compat.Java8Api.listOf());java.util.Map<java.lang.String, java.lang.Object> second=engine.scan(document,cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(cn.bidcheck.compat.Java8Api.toList(rows(first,"occurrences").stream().map(f->f.get("id"))),cn.bidcheck.compat.Java8Api.toList(rows(second,"occurrences").stream().map(f->f.get("id"))));
        for(Map<String,Object> fact:rows(first,"occurrences")){Evidence e=(Evidence)fact.get("evidence");assertTrue(e.quote().contains(fact.get("rawValue").toString()));assertTrue(document.pages().get(0).text().startsWith(e.quote(),((Number)fact.get("sourceOffset")).intValue()));}
    }
    @Test void coverageAlwaysListsAllCategoriesAndNeverClaimsPass(){
        java.util.Map<java.lang.String, java.lang.Object> report=scan("本项目未列出可以抽取的字段");java.util.Map<?, ?> coverage=(Map<?,?>)report.get("coverage");assertEquals(18,((List<?>)coverage.get("categories")).size());
        assertEquals("REVIEW_REQUIRED",report.get("overall"));assertTrue(((List<?>)coverage.get("categories")).stream().map(Map.class::cast).allMatch(c->c.get("status").equals("NO_FACTS")));
    }
    @Test void tableRowsCarryTheirOwnObjectAndLot(){
        assertTrue(relation("VALUE","标段\t品目\t型号\n1\t服务器A\tX100\n2\t服务器A\tX200").isEmpty());
        assertEquals(1,relation("VALUE","品目\t型号\n服务器A\tX100\n服务器A\tX200").size());
    }
    @Test void revisedAndOcrValuesCannotProduceStrongConflicts(){
        assertEquals("SUSPECTED",relation("VALUE","品目：服务器A；型号：X100","修订版\n品目：服务器A；型号：X200").get(0).get("status"));
        Document original=doc("品目：服务器A；型号：X100\n品目：服务器A；型号：X200");
        Document ocr=new Document(original.id(),original.projectId(),original.role(),original.name(),original.version(),original.sha256(),original.createdAt(),cn.bidcheck.compat.Java8Api.listOf(new Page(1,original.pages().get(0).text(),"PAGE","OCR",null)),cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("SUSPECTED",rows(engine.scan(ocr,cn.bidcheck.compat.Java8Api.listOf()),"groups").get(0).get("status"));
    }
    @Test void templateSectionsAndUnknownHeadingsCannotInheritCurrentItem(){
        assertTrue(relation("VALUE","品目：服务器A；型号：X100\n填写说明\n品目：服务器A；型号：X200").isEmpty());
        java.util.Map<java.lang.String, java.lang.Object> report=scan("品目：服务器A；型号：X100\n另一个设备章节\n型号：X200");
        assertTrue(rows(report,"groups").stream().noneMatch(g->g.get("status").equals("CONFLICT")));
        for(String heading:cn.bidcheck.compat.Java8Api.listOf("填写说明：", "一、填写说明：", "第3章 参考模板：", "历史业绩：", "二、历史业绩：")) {
            assertTrue(relation("VALUE","品目：服务器A；型号：X100\n"+heading+"\n品目：服务器A；型号：X200").isEmpty(),heading);
        }
    }
    @Test void unspecifiedCalendarDaysAreNeverStrongConflicts(){
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> result=relation("VALUE","起算条件：合同签订后；交货期：30天","起算条件：合同签订后；交货期：45天");
        assertEquals(1,result.size());assertEquals("SUSPECTED",result.get(0).get("status"));
    }
    @Test void customHistoricalFactsStillRequireExplicitContractIdentity(){
        String content="历史业绩系统A并发数：100";Document document=doc(content);
        java.util.Map<java.lang.String, java.lang.Object> fact=supplemental(document,"custom","系统A","100",content,content,0);fact.put("scope","HISTORICAL_PROJECT");fact.put("fieldLabel","并发数");fact.put("categoryId","performance");
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(document,cn.bidcheck.compat.Java8Api.listOf(fact));
        assertEquals("EVIDENCE_INSUFFICIENT",rows(report,"groups").get(0).get("status"));
        assertTrue(rows(report,"occurrences").get(0).get("scopeReason").toString().contains("历史合同身份"));
    }
    @Test void partialNativeExtractionDowngradesArithmeticToSuspected(){
        Document original=doc("品目\t数量（台）\t单价（元）\t行金额（元）\n服务器A\t3\t100\t250");
        Document partial=new Document(original.id(),original.projectId(),original.role(),original.name(),original.version(),original.sha256(),original.createdAt(),
                cn.bidcheck.compat.Java8Api.listOf(new Page(1,original.pages().get(0).text(),"PAGE","PDFBOX","OCR_DEFERRED")),cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> arithmetic=rows(engine.scan(partial,cn.bidcheck.compat.Java8Api.listOf()),"groups").stream().filter(g->g.get("relationType").equals("ARITHMETIC")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals("SUSPECTED",arithmetic.get("status"));
    }
    @Test void aggregatedEvidenceGapsShowOnlyContextSharedByEveryOccurrence(){
        java.util.Map<java.lang.String, java.lang.Object> report=scan("第一标段\n品目：服务器A；数量：3\n第二标段\n品目：服务器B；数量：4");
        java.util.Map<java.lang.String, java.lang.Object> group=rows(report,"groups").stream().filter(g->g.get("relationType").equals("EVIDENCE")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        java.util.Map<?, ?> context=(Map<?,?>)group.get("comparisonContext");
        assertEquals("",context.get("subject"));assertEquals("",context.get("lot"));
        assertFalse(((Map<?,?>)context.get("dimensions")).containsKey("object"));
        assertTrue(context.get("missing").toString().contains("单位"));assertEquals(2,((List<?>)group.get("occurrenceIds")).size());
    }
}
