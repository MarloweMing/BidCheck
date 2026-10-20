package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentScopeProofTest {
    private static final String PROJECT="2025年海南综合楼二次装修工程",COVER="（"+PROJECT+"）\n投标文件（商务部分）";
    private Document document(String... values){List<Page> pages=new ArrayList<>();for(int i=0;i<values.length;i++)pages.add(new Page(i+1,values[i],"PAGE","PDF",""));return new Document("d","p","BID","bid.pdf",1,"hash","",pages,List.of());}
    private Evidence evidence(int page,String quote){return new Evidence("d","bid.pdf",page,"PAGE",quote,"",1);}
    @Test void currentBodyMustBeUniquelyAnchored(){Document d=document(COVER,"设计方案正文一");assertEquals(true,DocumentScopeProof.currentEvidence(d,evidence(2,"设计方案正文一")).get("verifiedCurrent"));}
    @Test void repeatedQuoteCannotBeRepairedByAssertedAnchor(){Document d=document(COVER,"设计方案正文一\n设计方案正文一");assertEquals(false,DocumentScopeProof.currentEvidence(d,evidence(2,"设计方案正文一")).get("verifiedCurrent"));}
    @Test void foreignBodyCannotCountTowardCurrentCompleteness(){Document d=document(COVER,"关于贵公司2023年福建营业厅装修改造项目的招标文件。\n设计方案正文一");assertEquals(false,DocumentScopeProof.currentEvidence(d,evidence(2,"设计方案正文一")).get("verifiedCurrent"));}
    @Test void currentAndHistoricalRegionsCannotBeCombined(){String page="姓名 张甲\n拟在本合同任职 项目负责人\n主要工作经历\n历史项目设计方案正文一";Document d=document(COVER,page);assertEquals(true,DocumentScopeProof.currentEvidence(d,evidence(2,"拟在本合同任职 项目负责人")).get("verifiedCurrent"));assertEquals(false,DocumentScopeProof.currentEvidence(d,evidence(2,"历史项目设计方案正文一")).get("verifiedCurrent"));assertEquals(false,DocumentScopeProof.currentEvidence(d,evidence(2,page)).get("verifiedCurrent"));}
    @Test void navigationIsNeverSubstantiveBody(){Document d=document(COVER,"目录\n设计方案 .................. 4\n");var proof=DocumentScopeProof.currentEvidence(d,evidence(2,"设计方案"));assertEquals(false,proof.get("verifiedCurrent"));assertEquals("NAVIGATION_ONLY",proof.get("status"));}
    @Test void unnamedDocumentCannotBeAssertedCurrentByModel(){Document d=document("本项目设计方案正文");assertEquals(false,DocumentScopeProof.currentEvidence(d,evidence(1,"本项目设计方案正文")).get("verifiedCurrent"));}
    @Test void explicitTechnicalCoverRestoresCurrentDocument(){Document d=document(COVER,"关于贵公司2023年福建营业厅装修改造项目的招标文件。",COVER.replace("商务","技术"),"设计方案正文一");assertEquals(true,DocumentScopeProof.currentEvidence(d,evidence(4,"设计方案正文一")).get("verifiedCurrent"));}
    @Test void mismatchedDocumentOrLocationCannotCount(){Document d=document(COVER,"设计方案正文一");Evidence wrong=new Evidence("other","bid.pdf",2,"PAGE","设计方案正文一","",1);assertEquals(false,DocumentScopeProof.currentEvidence(d,wrong).get("verifiedCurrent"));assertEquals(false,DocumentScopeProof.currentEvidence(d,evidence(5,"设计方案正文一")).get("verifiedCurrent"));}
}
