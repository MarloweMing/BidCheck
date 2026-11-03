package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentContextNumericAndSupportTest {
    private Document document(String... texts){List<Page> pages=new ArrayList<>();for(int i=0;i<texts.length;i++)pages.add(new Page(i+1,texts[i],"PAGE","PDFBOX",null));return new Document("doc","project","BID","synthetic.pdf",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"",pages,cn.bidcheck.compat.Java8Api.listOf());}
    @Test void percentagesAndMeasurementsNeverReplaceAHistoricalChapter(){
        Document d=document("5.9 业绩证明文件\n（2）证明文件\n9.00%\n9.0％\n18、24 吋各一\n4.2m 以内脚手架搭设、拆除\n1.5*1.2*2.0 配套平台\n项目名称：历史工程",
                "项目编号：OLD-01\n5.10 管理体系认证\n项目编号：UNKNOWN");
        cn.bidcheck.consistency.DocumentContextIndex index=DocumentContextIndex.build(d);
        for(int line=3;line<=8;line++){cn.bidcheck.consistency.DocumentContextIndex.Assignment assignment=index.at(d.pages().get(0),line);assertEquals("HISTORICAL_PROJECT",assignment.scope());assertEquals(cn.bidcheck.compat.Java8Api.listOf("5.9 业绩证明文件", "（2）证明文件"),assignment.sectionPath());assertFalse(assignment.boundary());}
        assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(1),1).scope());
        assertEquals("UNRESOLVED",index.at(d.pages().get(1),3).scope());
    }
    @Test void measurementSuppressionDoesNotHideRealNumericChaptersOrDefaultUnknownPeers(){
        Document d=document("3.投标函\n9.00%\n投标总价：100万元\n4.其他附件\n投标总价：110万元\n5.9 业绩证明文件\n2.项目概况\n项目编号：UNKNOWN\n3.投标函\n8、10\n投标总价：120万元\n8、其他附件\n投标总价：130万元");
        cn.bidcheck.consistency.DocumentContextIndex index=DocumentContextIndex.build(d);
        assertEquals("CURRENT_PROJECT",index.at(d.pages().get(0),3).scope());
        assertEquals("UNRESOLVED",index.at(d.pages().get(0),5).scope());
        assertEquals("UNRESOLVED",index.at(d.pages().get(0),8).scope());
        assertFalse(index.at(d.pages().get(0),10).boundary());
        assertEquals("CURRENT_PROJECT",index.at(d.pages().get(0),11).scope());
        assertEquals("UNRESOLVED",index.at(d.pages().get(0),13).scope());
    }
    @Test void numberedHistoricalProjectCaptionsKeepTheirExplicitParentAndRemainSiblings(){
        Document d=document("作为负责人的业绩\n1.2022年甲地改造项目\n项目编号：OLD-A",
                "2.乙地2023-2024年装修施工框架合同\n项目编号：OLD-B\n3.丙地设计施工一体化工程\n项目编号：OLD-C\n4.本次投标函\n项目编号：NOW");
        cn.bidcheck.consistency.DocumentContextIndex index=DocumentContextIndex.build(d);
        for(int line:cn.bidcheck.compat.Java8Api.listOf(2, 4))assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(1),line).scope());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("作为负责人的业绩", "3.丙地设计施工一体化工程"),index.at(d.pages().get(1),4).sectionPath());
        assertEquals("CURRENT_PROJECT",index.at(d.pages().get(1),6).scope());
    }
    @Test void contractCaptionCannotSupplyHistoricalOrCurrentScopeWithoutAnExplicitParent(){
        Document d=document("2.乙地2023年装修框架合同\n项目编号：UNKNOWN");
        cn.bidcheck.consistency.DocumentContextIndex.Assignment assignment=DocumentContextIndex.build(d).at(d.pages().get(0),2);
        assertEquals("UNRESOLVED",assignment.scope());assertEquals("UNRESOLVED",assignment.status());
    }
    @Test void personnelPacketKeepsSourceHierarchyButDoesNotAssertTheProjectOfCertificates(){
        Document d=document("5.3 有效人员资格证明文件\n1.项目负责人\n（1）情况表\n姓名：张三",
                "（2）证明文件扫描件\n企业名称：前任职公司\n作为负责人的业绩\n1.2022年甲地改造项目\n项目编号：OLD-A",
                "2.施工企业主要负责人\n（1）情况表\n姓名：李四");
        cn.bidcheck.consistency.DocumentContextIndex index=DocumentContextIndex.build(d);
        cn.bidcheck.consistency.DocumentContextIndex.Assignment certificate=index.at(d.pages().get(1),2);
        assertEquals("UNRESOLVED",certificate.scope());assertEquals("UNRESOLVED",certificate.status());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("5.3 有效人员资格证明文件", "1.项目负责人", "（2）证明文件扫描件"),certificate.sectionPath());
        assertTrue(certificate.reason().contains("未明确事实适用项目"));
        assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(1),5).scope());
        assertEquals("UNRESOLVED",index.at(d.pages().get(2),3).scope());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("5.3 有效人员资格证明文件", "2.施工企业主要负责人", "（1）情况表"),index.at(d.pages().get(2),3).sectionPath());
        for(Evidence ref:certificate.evidences())assertTrue(d.pages().get(ref.page()-1).text().contains(ref.quote()));
    }
    @Test void gapAndPeerSectionStillEndPersonnelPacketAndHistoricalCaptionInheritance(){
        Document original=document("5.3 有效人员资格证明文件\n1.项目负责人\n作为负责人的业绩",
                "5.4 设备及工器具\n2.施工企业主要负责人\n企业名称：未知公司");
        cn.bidcheck.consistency.DocumentContextIndex index=DocumentContextIndex.build(original);assertEquals("UNRESOLVED",index.at(original.pages().get(1),3).scope());
        assertFalse(index.at(original.pages().get(1),3).sectionPath().contains("5.3 有效人员资格证明文件"));
        Page afterGap=new Page(3,"2.乙地2023年装修框架合同\n项目编号：UNKNOWN","PAGE","PDFBOX",null);
        Document gap=new Document(original.id(),original.projectId(),original.role(),original.name(),original.version(),original.sha256(),"",cn.bidcheck.compat.Java8Api.listOf(original.pages().get(0), afterGap),cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("UNRESOLVED",DocumentContextIndex.build(gap).at(afterGap,2).scope());
    }
    @Test void nativePerformanceTableHeadersAndSplitMoneyAnnotationsDoNotPopTheHistoricalParent(){
        Document d=document("5.3 有效人员资格证明文件\n1.项目负责人\n作为负责人的业绩\n序号 合同名称 签订时间 主要内容 金额（万元） 项目负责人\n1501.16（装修部\n分）\n1.2022年甲地改造项目\n项目编号：OLD-A",
                "2.乙地2023年装修框架合同\n项目编号：OLD-B\n2.施工企业主要负责人\n企业名称：证件公司");
        cn.bidcheck.consistency.DocumentContextIndex index=DocumentContextIndex.build(d);
        for(int line=4;line<=6;line++){assertFalse(index.at(d.pages().get(0),line).boundary());assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(0),line).scope());}
        assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(0),8).scope());
        assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(1),2).scope());
        assertEquals("UNRESOLVED",index.at(d.pages().get(1),4).scope());
    }
    @Test void explicitDecimalOcrPeerEndsHistoricalChapterButMarkdownAndUnrelatedNumbersDoNot(){
        Document d=document("3．计划为本次采购租用设备\n5.9 业绩证明文件\n历史项目编号：OLD",
                "【PDF识别文字】\n# 5.10 其他证明文件\n# 详细报价表\n1.2 内部合同条款\n5.10%\n项目编号：STILL-HISTORICAL\n5.10 其他证明文件\n证书编号：CERTIFICATE",
                "企业名称：证件公司");
        cn.bidcheck.consistency.DocumentContextIndex index=DocumentContextIndex.build(d);
        for(int line=2;line<=6;line++)assertEquals("HISTORICAL_PROJECT",index.at(d.pages().get(1),line).scope());
        assertEquals("UNRESOLVED",index.at(d.pages().get(1),8).scope());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("5.10 其他证明文件"),index.at(d.pages().get(1),8).sectionPath());
        assertEquals("UNRESOLVED",index.at(d.pages().get(2),1).scope());
        assertEquals("5.10 其他证明文件",index.at(d.pages().get(1),8).evidences().get(0).quote());
        Document validParent=document("5.本次投标\n5.9 业绩证明文件\n历史项目编号：OLD",
                "【PDF识别文字】\n5.10 其他证明文件\n证书编号：CURRENT");
        cn.bidcheck.consistency.DocumentContextIndex validIndex=DocumentContextIndex.build(validParent);
        assertEquals("CURRENT_PROJECT",validIndex.at(validParent.pages().get(1),3).scope());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("5.本次投标", "5.10 其他证明文件"),validIndex.at(validParent.pages().get(1),3).sectionPath());
    }
}
