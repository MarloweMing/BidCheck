package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FactScopePolicyTest {
    private final CheckEngine engine=new CheckEngine();
    private Document doc(String... texts) {
        List<Page> pages=new ArrayList<>(); for(int i=0;i<texts.length;i++) pages.add(new Page(i+1,texts[i],"PAGE","PDFBOX",null));
        return new Document("d","p","BID","投标文件",1,"same-fixture-hash","",cn.bidcheck.compat.Java8Api.listCopyOf(pages),cn.bidcheck.compat.Java8Api.listOf());
    }
    private Checkpoint rule(String type,String field,String expected,String unit,String title) {
        return new Checkpoint("c",title,"核查","HIGH",type,field,"DATE_VALIDITY".equals(type)?">=":"VALUE_COMPARE".equals(type)?"<=":"FULL_TEXT_REVIEW".equals(type)?"REVIEW":"=",expected,unit,true,true,true,null);
    }
    private Evaluation eval(Checkpoint cp,Document doc) { return engine.evaluate(cp,doc,new FactExtractor().extract(doc)); }
    private Checkpoint identity(String field,String expected) { return rule("ENTITY_FIELD_CONSISTENCY",field,expected,"","项目一致性"); }
    private Checkpoint price(String unit) { return rule("VALUE_COMPARE","totalPrice","2000000",unit,"投标总报价限价"); }
    private Checkpoint expiry() { return rule("DATE_VALIDITY","certificateExpiry","2025-08-05","DATE","证件效期"); }

    @Test void historicalProjectNamesAndCodesDoNotBecomeCurrentConflicts() {
        Document d=doc("商务投标文件封面\n项目名称：当前工程\n项目编号：NEW-01",
                "历史业绩合同\n项目名称：旧工程\n项目编号：OLD-09",
                "项目名称：另一个旧工程\n项目编号：OLD-10");
        assertEquals("PASS",eval(identity("projectName","当前工程"),d).status());
        assertEquals("PASS",eval(identity("projectNumber","NEW-01"),d).status());
        assertEquals(1,eval(identity("projectName","当前工程"),d).evidences().size());
    }
    @Test void explicitlyCurrentProjectConflictStillFails() {
        Document d=doc("投标文件封面\n项目名称：甲工程\n项目编号：A-01","投标函\n项目名称：乙工程\n项目编号：B-02");
        assertEquals("FAIL",eval(identity("projectName","甲工程"),d).status());
        assertEquals("FAIL",eval(identity("projectNumber","A-01"),d).status());
    }
    @Test void unknownScopeIsNeitherPassNorConflict() {
        Document d=doc("项目名称：甲工程\n项目名称：乙工程\n项目编号：A-01");
        assertEquals("UNDETERMINED",eval(identity("projectName","甲工程"),d).status());
        assertEquals("UNDETERMINED",eval(identity("projectNumber","A-01"),d).status());
    }
    @Test void missingOrForgedEvidenceCannotCreateAConflict() {
        Document d=doc("投标函\n项目名称：当前工程");
        Fact forged=new Fact("PROJECT","PROJECT","projectName","其他工程","其他工程",new Evidence("d","投标文件",1,"PAGE","项目名称：其他工程","p1-l2",1));
        assertEquals("UNDETERMINED",engine.evaluate(identity("projectName","当前工程"),d,cn.bidcheck.compat.Java8Api.listOf(forged)).status());
        Evidence real=new Evidence("d","投标文件",1,"PAGE","项目名称：当前工程","p1-l2",1);
        assertEquals("UNDETERMINED",engine.evaluate(identity("projectName","当前工程"),d,cn.bidcheck.compat.Java8Api.listOf(new Fact("PROJECT","PROJECT","projectName","当前工程","其他工程",real))).status());
        Evidence wrongLocation=new Evidence("d","投标文件",1,"PARAGRAPH",real.quote(),real.anchor(),1);
        assertEquals("UNDETERMINED",engine.evaluate(identity("projectName","当前工程"),d,cn.bidcheck.compat.Java8Api.listOf(new Fact("PROJECT","PROJECT","projectName","当前工程","当前工程",wrongLocation))).status());
    }
    @Test void totalsFromHistoricalContractsCannotFailCurrentLimit() {
        Document d=doc("历史业绩合同\n不含税总价为12345678.90元。","合同协议书\n不含税总价为9876543.21元。");
        assertEquals("UNDETERMINED",eval(price("CNY_EXCLUSIVE"),d).status());
        Document current=doc("投标报价表\n不含税投标总价：1800000元。",d.pages().get(0).text());
        assertEquals("PASS",eval(price("CNY_EXCLUSIVE"),current).status());
    }
    @Test void genuineCurrentOverLimitStillFails() {
        assertEquals("FAIL",eval(price("CNY_EXCLUSIVE"),doc("投标函\n不含税投标总价：2200000元。")).status());
        assertEquals("UNDETERMINED",eval(price("CNY_EXCLUSIVE"),doc("不含税投标总价：2200000元。")).status());
    }
    @Test void contractSigningTimingDoesNotTurnCurrentBidIntoHistory() {
        Document d=doc("投标函\n合同签订后60天交付；不含税投标总价：2200000元。");
        assertEquals("FAIL",eval(price("CNY_EXCLUSIVE"),d).status());
    }
    @Test void itemPricesAndCeilingsDoNotBecomeBidTotalsEvenWhenTheySayBidPrice() {
        for(String text:cn.bidcheck.compat.Java8Api.listOf("分项投标报价：3000000元", "预算投标报价：3000000元", "最高限价投标报价：3000000元", "单价投标报价：3000000元"))
            assertEquals("UNDETERMINED",eval(price("CNY"),doc("投标报价表\n"+text)).status(),text);
    }
    @Test void taxBasisMustMatchAndDifferentLotsDoNotConflict() {
        assertEquals("UNDETERMINED",eval(price("CNY_EXCLUSIVE"),doc("投标函\n含税投标总价：3000000元")).status());
        Document d=doc("第一标段投标函\n项目编号：A-01\n投标总价：1000000元","第二标段投标函\n项目编号：B-01\n投标总价：3000000元");
        assertEquals("UNDETERMINED",eval(price("CNY"),d).status());
        cn.bidcheck.domain.Domain.Checkpoint lotRule=rule("VALUE_COMPARE","totalPrice","2000000","CNY","第一标段投标总报价");
        assertEquals("PASS",eval(lotRule,d).status());
    }
    @Test void dateEndpointCannotBeReplacedByRenewalApplicationDate() {
        Document d=doc("本项目证件\n有效期:2026年05月12日2024-05-13-延续申请某单位2022-06-21-延续申请某单位");
        List<Fact> before=new FactExtractor().extract(d);
        assertEquals("2022-06-21",before.get(0).normalizedValue());
        assertEquals("UNDETERMINED",engine.evaluate(expiry(),d,before).status());
        assertEquals(before,new FactExtractor().extract(d));
    }
    @Test void aMultiLotRequirementCannotImplicitlySelectTheFirstLot() {
        Document d=doc("第一标段投标函\n投标总价：3000000元","第二标段投标函\n投标总价：1000000元");
        Evidence source=new Evidence("t","招标",1,"PAGE","第一标段和第二标段报价要求","p1-l1",1);
        Checkpoint generic=new Checkpoint("c","投标报价限价","价格","HIGH","VALUE_COMPARE","totalPrice","<=","2000000","CNY",true,true,true,source);
        assertEquals("UNDETERMINED",eval(generic,d).status());
        FactScopePolicy.Selection selection=FactScopePolicy.of(d).select(generic,new FactExtractor().extract(d));
        assertEquals(2,selection.uncertain().size());
        assertTrue(selection.included().isEmpty());
        assertTrue(selection.excluded().isEmpty());
        Checkpoint explicit=new Checkpoint("c","第二标段投标报价限价","价格","HIGH","VALUE_COMPARE","totalPrice","<=","2000000","CNY",true,true,true,source);
        assertEquals("PASS",eval(explicit,d).status());
    }
    @Test void aSharedMultiLotHeadingDoesNotMakeAnAmbiguousAmountComparable() {
        Document d=doc("第一标段和第二标段投标函\n投标总价：3000000元");
        Evidence source=new Evidence("t","招标",1,"PAGE","第一标段和第二标段报价要求","p1-l1",1);
        Checkpoint generic=new Checkpoint("c","投标报价限价","价格","HIGH","VALUE_COMPARE","totalPrice","<=","2000000","CNY",true,true,true,source);
        FactScopePolicy.Selection selection=FactScopePolicy.of(d).select(generic,new FactExtractor().extract(d));
        assertEquals(1,selection.uncertain().size());
        assertTrue(selection.included().isEmpty());
        assertTrue(selection.excluded().isEmpty());
        assertEquals("UNDETERMINED",eval(generic,d).status());
    }
    @Test void generatedCommentAboutStartDateCannotBecomeExpiredCertificate() {
        Document d=doc("本项目证件\n- **有效期限**：2025.08.04–2045.08.04\n- 有效期起始日期为2025年8月4日，表明该证件可能为新换发版本");
        assertEquals("UNDETERMINED",eval(expiry(),d).status());
        assertEquals("UNDETERMINED",eval(rule("FULL_TEXT_REVIEW","certificateCoverage","2025-08-05","","效期覆盖"),d).status());
    }
    @Test void trueCurrentExpiredOrFutureCredentialStillFails() {
        for(String text:cn.bidcheck.compat.Java8Api.listOf("有效至：2025-08-04", "有效期：2025-08-06至2035-08-05")) {
            Document d=doc("本项目证件\n"+text);
            assertEquals("FAIL",eval(expiry(),d).status());
            assertEquals("FAIL",eval(rule("FULL_TEXT_REVIEW","certificateCoverage","2025-08-05","","效期覆盖"),d).status());
        }
    }
    @Test void historicalAndUnattributedCredentialsCannotEstablishCurrentExpiry() {
        for(Document d:cn.bidcheck.compat.Java8Api.listOf(doc("历史业绩证明\n资质证书\n有效至：2020-01-01"), doc("身份证\n有效至：2020-01-01"))) {
            assertEquals("UNDETERMINED",eval(expiry(),d).status());
            assertEquals("UNDETERMINED",eval(rule("FULL_TEXT_REVIEW","certificateCoverage","2025-08-05","","效期覆盖"),d).status());
        }
    }
    @Test void sameMetadataDifferentDocumentInstancesNeverShareSourceClassification() {
        Document current=doc("投标函\n项目名称：甲工程"),history=doc("历史业绩合同\n项目名称：甲工程");
        assertEquals("PASS",eval(identity("projectName","甲工程"),current).status());
        assertEquals("UNDETERMINED",eval(identity("projectName","甲工程"),history).status());
        assertSame(FactScopePolicy.of(current),FactScopePolicy.of(current));
        assertNotSame(FactScopePolicy.of(current),FactScopePolicy.of(history));
    }
    @Test void independentlyProvenCurrentDeviationFormsRecoverAfterHistoricalSections() {
        String project="2026年合成中心综合楼二次装修工程";
        Document d=doc("（"+project+"）\n投标文件（商务部分）","历史项目\n项目名称：旧工程",
            "合同条款偏离表\n项目名称："+project,"技术偏离表\n项目名称："+project);
        java.util.List<cn.bidcheck.domain.Domain.Fact> facts=new FactExtractor().extract(d);cn.bidcheck.engine.FactScopePolicy.Selection selection=FactScopePolicy.of(d).select(identity("projectName",project),facts);
        assertTrue(selection.included().stream().anyMatch(f->f.evidence().page()==3));
        assertTrue(selection.included().stream().anyMatch(f->f.evidence().page()==4));
        assertFalse(selection.included().stream().anyMatch(f->f.rawValue().equals("旧工程")));
        assertEquals("PASS",engine.evaluate(identity("projectName",project),d,facts).status());
    }
    @Test void differentNamedCurrentFormMustNotDisappearAsAnUnrelatedHistoricalProject() {
        String project="2026年合成中心综合楼二次装修工程",other="2026年合成中心综合楼三次装修工程";
        Document d=doc("（"+project+"）\n投标文件（商务部分）\n项目名称："+project,"投标函\n项目名称："+other);
        java.util.List<cn.bidcheck.domain.Domain.Fact> facts=new FactExtractor().extract(d);cn.bidcheck.engine.FactScopePolicy.Selection selection=FactScopePolicy.of(d).select(identity("projectName",project),facts);
        assertTrue(selection.uncertain().stream().anyMatch(f->f.rawValue().equals(other)));
        assertNotEquals("PASS",engine.evaluate(identity("projectName",project),d,facts).status());
    }
    @Test void inheritedDocumentScopeCannotEraseHistoricalContractAttachment() {
        String project="2026年合成中心综合楼二次装修工程";
        Document d=doc("（"+project+"）\n投标文件\n项目编号：NOW-001",
            "甲方：历史项目业主\n乙方：测试承包人\n合同总价：500000元",
            "二、报价明细表\n称：2023年其他城市营业厅装修改造项目\n项目编号：OLD-009",
            "技术条款偏离表\n项目名称："+project+"\n项目编号：NOW-001");
        java.util.List<cn.bidcheck.domain.Domain.Fact> facts=new FactExtractor().extract(d);
        cn.bidcheck.engine.FactScopePolicy.Selection selection=FactScopePolicy.of(d).select(identity("projectNumber","NOW-001"),facts);
        assertEquals(1,selection.excluded().size());
        assertEquals("OLD-009",selection.excluded().get(0).rawValue());
        assertEquals(2,selection.included().size());
        assertEquals("PASS",engine.evaluate(identity("projectNumber","NOW-001"),d,facts).status());
    }
    @Test void explicitCurrentProjectNumberConflictAfterHistoryStillFails() {
        String project="2026年合成中心综合楼二次装修工程";
        Document d=doc("（"+project+"）\n投标文件\n项目编号：NOW-001",
            "历史业绩合同\n项目编号：OLD-009",
            "技术条款偏离表\n项目名称："+project+"\n项目编号：NOW-002");
        assertEquals("FAIL",eval(identity("projectNumber","NOW-001"),d).status());
    }
}
