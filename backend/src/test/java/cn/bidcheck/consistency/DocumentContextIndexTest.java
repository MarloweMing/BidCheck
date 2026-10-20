package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentContextIndexTest {
    private Document doc(String... contents){List<Page> pages=new ArrayList<>();for(int i=0;i<contents.length;i++)pages.add(new Page(i+1,contents[i],"PAGE","PDFBOX",null));return new Document("bid","project","BID","test.pdf",2,"sha","",pages,List.of());}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Map<String,Object> report,String key){return (List<Map<String,Object>>)report.get(key);}
    @Test void historicalHierarchySurvivesNestedBidLetterAcrossPagesAndEndsAtPeerCurrentChapter(){
        Document d=doc("第一章 历史业绩\n1.1 投标函\n投标人名称：历史甲公司","项目名称：历史项目\n投标人名称：历史乙公司\n第二章 本次投标函\n投标人名称：当前公司");
        var index=DocumentContextIndex.build(d);var inherited=index.at(d.pages().get(1),2);
        assertEquals("HISTORICAL_PROJECT",inherited.scope());assertEquals("INHERITED",inherited.status());assertTrue(inherited.crossPage());
        assertEquals(List.of("第一章 历史业绩","1.1 投标函"),inherited.sectionPath());
        assertEquals("CURRENT_PROJECT",index.at(d.pages().get(1),4).scope());
        var report=new ConsistencyEngine().scan(d,List.of());
        assertTrue(rows(report,"groups").stream().noneMatch(g->Set.of("CONFLICT","SUSPECTED").contains(g.get("status"))));
        assertTrue(rows(report,"occurrences").stream().filter(o->o.get("rawValue").toString().startsWith("历史")).allMatch(o->"HISTORICAL_PROJECT".equals(o.get("scope"))));
    }
    @Test void unknownSiblingChapterAndPageGapsDoNotInheritCurrentScope(){
        Document d=doc("第一章 本次投标函\n项目编号：A","第二章 其他附件\n项目编号：B");
        assertEquals("UNRESOLVED",DocumentContextIndex.build(d).at(d.pages().get(1),2).scope());
        List<Page> pages=List.of(d.pages().get(0),new Page(3,"项目编号：C","PAGE","PDFBOX",null));
        Document gap=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),pages,List.of());
        assertEquals("UNRESOLVED",DocumentContextIndex.build(gap).at(pages.get(1),1).status());
    }
    @Test void blankPageBlocksImplicitContinuationAndExplicitHeadingReestablishesScope(){
        Document d=doc("投标函\n投标人名称：甲公司","","投标人名称：乙公司\n本次投标函\n投标人名称：丙公司");
        var index=DocumentContextIndex.build(d);
        assertEquals("UNRESOLVED",index.at(d.pages().get(2),1).scope());assertEquals("CURRENT_PROJECT",index.at(d.pages().get(2),3).scope());
    }
    @Test void templateNestedCurrentHeadingsDoNotBecomeActualBidCommitments(){
        Document d=doc("第一章 参考模板\n（一）投标函\n投标总价：100万元","投标总价：200万元\n第二章 本次投标函\n投标总价：300万元");
        var report=new ConsistencyEngine().scan(d,List.of());
        assertTrue(rows(report,"groups").isEmpty());
        assertEquals("TEMPLATE",rows(report,"occurrences").get(0).get("scope"));
    }
    @Test void contextReferencesAreExactSourceLinesAndDoNotMutateOriginalPages(){
        Document d=doc("第一章 本次投标函\n（一）第一标段\n投标总价：100万元","投标总价：110万元");
        List<Page> before=List.copyOf(d.pages());var report=new ConsistencyEngine().scan(d,List.of());
        var group=rows(report,"groups").stream().filter(g->"totalPrice".equals(g.get("field"))).findFirst().orElseThrow();
        assertEquals("SOURCE_BOUND",group.get("contextStatus"));assertEquals(2,((List<?>)group.get("contextAssignments")).size());
        for(Map<String,Object> occurrence:rows(report,"occurrences"))for(Object ref:(List<?>)occurrence.get("contextEvidences")){Evidence evidence=(Evidence)ref;assertTrue(d.pages().get(evidence.page()-1).text().contains(evidence.quote()));}
        assertEquals(before,d.pages());assertEquals("LOT-1",group.get("lot"));
    }
    @Test void contextDefaultIsVisibleAndNotPresentedAsExplicitSourceEvidence(){
        Document d=doc("投标人名称：甲公司\n投标人名称：乙公司");var report=new ConsistencyEngine().scan(d,List.of());
        assertEquals("DOCUMENT_DEFAULT",rows(report,"groups").get(0).get("contextStatus"));
        Map<?,?> assignment=(Map<?,?>)rows(report,"occurrences").get(0).get("contextAssignment");
        assertEquals("DOCUMENT_DEFAULT",assignment.get("status"));assertTrue(((List<?>)assignment.get("evidences")).isEmpty());
    }
    @Test void multipleLotsInOneHeadingRemainUnresolvedAndSeparateExplicitLotsStaySeparate(){
        Document d=doc("第一标段和第二标段投标函\n投标总价：100万元\n第三标段投标函\n投标总价：200万元");
        var index=DocumentContextIndex.build(d);assertEquals("UNRESOLVED",index.at(d.pages().get(0),2).lot());assertEquals("LOT-3",index.at(d.pages().get(0),4).lot());
    }
    @Test void expandedObjectContextIsClearedAtNumberedSiblingBoundary(){
        Document d=doc("第一章 本次投标技术方案\n品目：服务器A\n型号：X100\n第二章 其他附件\n型号：X200");
        var report=new ConsistencyEngine().scan(d,List.of());
        assertTrue(rows(report,"groups").stream().noneMatch(g->"CONFLICT".equals(g.get("status"))));
        assertEquals("UNRESOLVED",rows(report,"occurrences").stream().filter(o->"X200".equals(o.get("rawValue"))).findFirst().orElseThrow().get("scope"));
    }
    @Test void repeatedUnnumberedHistoricalAttachmentsDoNotCreateUnboundedFalseHierarchy(){
        Document d=doc("第一章 历史业绩\n"+"投标函\n投标人名称：历史公司\n".repeat(30)+"第二章 本次投标函\n投标人名称：当前公司");
        var index=DocumentContextIndex.build(d);
        assertEquals(2,index.at(d.pages().get(0),60).sectionPath().size());
        assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(0),60).scope());
        assertEquals("CURRENT_PROJECT",index.at(d.pages().get(0),63).scope());
    }
    @Test void explicitCurrentHeadingAfterMissingPhysicalPagesRestoresSourceBoundContext(){
        Document original=doc("第一章 历史业绩\n投标人名称：历史公司");
        Page later=new Page(50,"第二章 本次投标函\n投标人名称：当前公司","PAGE","PDFBOX",null);
        Document d=new Document(original.id(),original.projectId(),original.role(),original.name(),original.version(),original.sha256(),original.createdAt(),List.of(original.pages().get(0),later),List.of());
        var assignment=DocumentContextIndex.build(d).at(later,2);
        assertEquals("CURRENT_PROJECT",assignment.scope());assertEquals("INHERITED",assignment.status());assertEquals(50,assignment.sourcePage());
    }
    @Test void ocrMarkdownExplanationsCannotOverwriteNativeSectionHierarchy(){
        Document d=doc("5.2 合成投标人有效资质证明文件\n【PDF识别文字】\n# 营业执照\n## 图片内容分析\n### 建议\n企业名称：甲公司","【PDF识别文字】\n# QR Code Analysis\n## Placeholder Example\n企业名称：甲公司");
        var index=DocumentContextIndex.build(d);
        assertEquals("CURRENT_PROJECT",index.at(d.pages().get(1),4).scope());
        assertEquals(List.of("5.2 合成投标人有效资质证明文件"),index.at(d.pages().get(1),4).sectionPath());
    }
    @Test void indexRowsAndDottedTableOfContentsAreNotBusinessSectionBoundaries(){
        Document d=doc("1.商务投标文件封面\n投标人名称：合成甲公司\n目录\n6.历史业绩 ............... 250\n3.投标函 ............... 7","3.投标函\n1 投标人名称 P20-21 合成甲公司\n投标人名称：合成甲公司");
        var index=DocumentContextIndex.build(d);
        assertEquals("TEMPLATE",index.at(d.pages().get(0),5).scope());
        assertEquals("CURRENT_PROJECT",index.at(d.pages().get(1),3).scope());
        assertEquals(List.of("3.投标函"),index.at(d.pages().get(1),3).sectionPath());
    }
    @Test void datedProjectListInsidePerformanceSectionRetainsHistoricalOwnership(){
        Document d=doc("作为负责人的业绩\n1.2022年甲项目工程\n【PDF识别文字】\n# 合同第十条\n投标总价：100万元","【PDF识别文字】\n# 合成采购单位有限公司采购订单\n## 建议\n项目名称：甲项目");
        var index=DocumentContextIndex.build(d);
        assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(1),4).scope());
    }
    @Test void standaloneMoneyAndDateLinesDoNotBecomeNumberedSectionHeadings(){
        Document d=doc("3.投标函\n100.00\n1,000.00元\n人民币100.00万元\n2025.10.08\n2025 年 10 月 8 日\n投标人名称：甲公司");
        var index=DocumentContextIndex.build(d);
        for(int line=2;line<=7;line++){var assignment=index.at(d.pages().get(0),line);assertEquals("CURRENT_PROJECT",assignment.scope());assertEquals(List.of("3.投标函"),assignment.sectionPath());}
    }
    @Test void historicalAgreementIsNotResetByInternalOcrQuotationTableOrNumberedInstructions(){
        Document d=doc("5.9 业绩证明文件\n（2）证明文件\n【PDF识别文字】\n# 详细报价表（标段1新建厅）\n项目编号：OLD-001","【PDF识别文字】\n4、投标人须按照招标文件提供详细报价表，不得随意更改表格样式\n项目名称：历史项目\n6.本次投标函\n投标人名称：当前公司");
        var index=DocumentContextIndex.build(d);
        assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(0),5).scope());assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(1),3).scope());
        // The OCR appendix's numbering alone cannot prove an exit from the historical parent.
        assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(1),5).scope());
        Document resumed=doc(d.pages().get(0).text(),"6.本次投标函\n投标人名称：当前公司");
        assertEquals("CURRENT_PROJECT",DocumentContextIndex.build(resumed).at(resumed.pages().get(1),2).scope());
    }
    @Test void isolatedLotEvidenceCannotSupplyCurrentProjectOwnership(){
        Document d=doc("第一标段\n投标总价：100万元\n第二标段\n投标总价：200万元");
        var index=DocumentContextIndex.build(d);
        assertEquals("UNRESOLVED",index.at(d.pages().get(0),2).scope());assertEquals("UNRESOLVED",index.at(d.pages().get(0),2).status());
        assertEquals("LOT-1",index.at(d.pages().get(0),2).lot());assertEquals("LOT-2",index.at(d.pages().get(0),4).lot());
        assertTrue(index.at(d.pages().get(0),2).evidences().stream().anyMatch(e->e.quote().equals("第一标段")));
    }
    @Test void lotOnlyHeadingsKeepExplicitCurrentOrHistoricalParentAndDoNotNestSiblingLots(){
        for(String parent:List.of("本次投标函","第一章 本次投标函","历史业绩","第一章 历史业绩")) {
            Document d=doc(parent+"\n第一标段\n投标总价：100万元\n第二标段\n投标总价：200万元");var index=DocumentContextIndex.build(d);
            String expected=parent.contains("历史")?"HISTORICAL_PROJECT":"CURRENT_PROJECT";
            assertEquals(expected,index.at(d.pages().get(0),3).scope());assertEquals(expected,index.at(d.pages().get(0),5).scope());
            assertEquals("LOT-1",index.at(d.pages().get(0),3).lot());assertEquals("LOT-2",index.at(d.pages().get(0),5).lot());
            assertEquals(List.of(parent,"第二标段"),index.at(d.pages().get(0),5).sectionPath());
        }
    }
    @Test void unnumberedCurrentSectionAfterALotDoesNotSilentlyBecomeProjectWide(){
        Document d=doc("第一章 本次投标函\n第一标段\n投标总价：100万元\n投标函\n投标总价：110万元\n本项目汇总\n投标总价：120万元");
        var index=DocumentContextIndex.build(d);
        assertEquals("CURRENT_PROJECT",index.at(d.pages().get(0),5).scope());assertEquals("UNRESOLVED",index.at(d.pages().get(0),5).lot());
        assertEquals("",index.at(d.pages().get(0),7).lot());
    }
}
