package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentContextPerformanceTableTest {
    private Document document(String... contents) {
        List<Page> pages=new ArrayList<>();for(int i=0;i<contents.length;i++)pages.add(new Page(i+1,contents[i],"PAGE","PDFBOX",null));
        return new Document("doc","project","BID","source.pdf",1,"a".repeat(64),"",pages,List.of());
    }
    @Test void wrappedNativePerformanceCellsCannotEraseTheExplicitHistoryParent() {
        Document d=document("5.3 有效人员资格证明文件\n1.项目负责人\n（2）证明文件扫描件",
            "作为负责人的业绩\n序号 合同名称 签订时间 主要内容 金额（万元） 项目负责人\n某地演示厅改造项目工程总承\n包采购合同 统和装修及道具等建设\n1 2022 年 4 分金额203.13万 张三\n配套道具框架合同",
            "1.2022年某地演示厅改造总承包项目\n合同编号：OLD-A",
            "2.另一地区2023-2024年装修施工框架合同\n合同编号：OLD-B",
            "2.施工企业主要负责人\n（1）情况表\n姓名：李四");
        var index=DocumentContextIndex.build(d);
        assertFalse(index.at(d.pages().get(1),4).boundary());
        for(int page:List.of(1,2,3))assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(page),page==1?6:2).scope());
        assertTrue(index.at(d.pages().get(3),2).sectionPath().contains("作为负责人的业绩"));
        assertEquals("UNRESOLVED",index.at(d.pages().get(4),3).scope());
        for(Evidence ref:index.at(d.pages().get(3),2).evidences())assertTrue(d.pages().get(ref.page()-1).text().contains(ref.quote()));
    }
    @Test void tableHeaderWithoutExplicitHistoryDoesNotInventHistoricalScope() {
        Document d=document("5.3 有效人员资格证明文件\n1.项目负责人：测试甲\n序号 合同名称 签订时间 主要内容\n合成园区采购合同 测试装修与道具建设",
            "2.另一地区装修施工框架合同\n合同编号：UNKNOWN");
        assertEquals("UNRESOLVED",DocumentContextIndex.build(d).at(d.pages().get(1),2).scope());
    }
    @Test void realNumberedPeerAndMissingPagesStillEndTheHistoricalTableContext() {
        Document d=document("作为负责人的业绩\n序号 合同名称 签订时间 主要内容\n合成园区采购合同 测试装修与道具建设\n2.其他资料\n合同编号：UNKNOWN");
        assertEquals("UNRESOLVED",DocumentContextIndex.build(d).at(d.pages().get(0),5).scope());
        Page afterGap=new Page(3,"2.另一地区装修施工框架合同\n合同编号：UNKNOWN","PAGE","PDFBOX",null);
        Document gap=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),"",List.of(d.pages().get(0),afterGap),List.of());
        assertEquals("UNRESOLVED",DocumentContextIndex.build(gap).at(afterGap,2).scope());
    }
    @Test void nativeTableCellProtectionDoesNotCarryToTheNextPhysicalPage() {
        Document d=document("作为负责人的业绩\n序号 合同名称 签订时间 主要内容\n包采购合同 统和装修及道具等建设",
                "未确认用途的租赁合同\n合同编号：UNKNOWN");
        var assignment=DocumentContextIndex.build(d).at(d.pages().get(1),2);
        assertEquals("UNRESOLVED",assignment.scope());
        assertFalse(assignment.sectionPath().contains("作为负责人的业绩"));
    }
    @Test void explicitSafetyOfficerPerformanceCaptionIsHistoryButTheNextPersonPacketIsNot() {
        Document d=document("5.专职安全员\n（2）证明文件扫描件",
            "【PDF识别文字】\n作为专职安全员的业绩\n# 某省营业厅装修改造采购框架合同\n## （标段 1 新建厅）",
            "合同编号：OLD\n【PDF识别文字】\n# 详细报价表（标段1新建厅）\n投标总价：100万元",
            "6.项目组成员\n（1）情况表\n姓名：张三",
            "（2）证明文件扫描件\n【PDF识别文字】\n建筑施工工程师-张三证明文件\n姓名：李四");
        var index=DocumentContextIndex.build(d);
        assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(2),4).scope());
        assertTrue(index.at(d.pages().get(2),4).evidences().stream().anyMatch(e->e.quote().equals("作为专职安全员的业绩")));
        assertEquals("UNRESOLVED",index.at(d.pages().get(3),3).scope());
        assertEquals("UNRESOLVED",index.at(d.pages().get(4),4).scope());
    }
    @Test void safetyOfficerCredentialTypeAloneCannotEstablishCurrentOrHistoricalProject() {
        Document d=document("5.专职安全员\n（2）证明文件扫描件\n专职安全员证书\n企业名称：前雇主");
        assertEquals("UNRESOLVED",DocumentContextIndex.build(d).at(d.pages().get(0),4).scope());
    }
    @Test void numberedScannerWatermarkIsNotAChapterButARealNumberedPeerStillIs() {
        Document d=document("作为专职安全员的业绩\n合同编号：OLD",
                "3.1.1.4 扫描全能王 2020\n3.1.1.4 CamScanner 2020\n投标总价：100万元\n3.其他附件\n项目编号：UNKNOWN");
        var index=DocumentContextIndex.build(d);
        assertFalse(index.at(d.pages().get(1),1).boundary());
        assertFalse(index.at(d.pages().get(1),2).boundary());
        assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(1),3).scope());
        assertEquals("UNRESOLVED",index.at(d.pages().get(1),5).scope());
    }
}
