package cn.bidcheck.engine;
import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MaterialAliasProofTest {
    private final String cover="（2025年合成海南中心综合楼二次装修工程）\n招标项目编号：HN-2025-001\n投标文件（商务部分）\n投标人名称：（盖章）合成建筑工程有限公司\n日期：2025年10月8日\n";
    private Page page(int n,String text){return new Page(n,text,"PAGE","OCR","");}
    private Document bid(Page...pages){return new Document("bid","p","BID","bid.pdf",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(pages),cn.bidcheck.compat.Java8Api.listOf());}
    private Checkpoint cp(String expected){return new Checkpoint("c","材料存在","形式检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS",expected,"",true,true,true,null);}
    private Document tender(String heading){return new Document("t","p","TENDER","t.docx",1,"thash","",cn.bidcheck.compat.Java8Api.listOf(page(536,heading), page(538,"商务技术评审索引表")),cn.bidcheck.compat.Java8Api.listOf());}
    private Checkpoint boundCp(String expected){return new Checkpoint("c","材料存在","形式检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS",expected,"",true,true,true,new Evidence("t","t.docx",538,"PAGE","商务技术评审索引表","p538",1));}
    private String index(String heading){return heading+"\n条款号 | 评审因素 | 对应页码 | 备注\n1.1 | 设计方案 | P5-37 | 满足要求\n1.2 | 施工组织设计 | P38-95 | 满足要求\n1.3 | 响应条款 | P96-98 | 满足要求\n";}
    @Test void fourClosedAliasesNeedCurrentCoversOrRealIndexRows(){
        cn.bidcheck.domain.Domain.Document doc=bid(page(1,cover),page(5,index("商务评审索引表")),page(485,cover.replace("商务部分","技术部分")),page(487,index("2.技术评审索引表")));
        for(String required:cn.bidcheck.compat.Java8Api.listOf("第一分册 商务投标文件", "第二分册 技术投标文件", "商务技术评审索引表", "技术投标索引表"))
            assertEquals("PASS",MaterialAliasProof.evaluate(boundCp(required),doc,tender("第一分册 商务投标文件")).orElseThrow(java.util.NoSuchElementException::new).status(),required);
    }
    @Test void directoryTitleOrUnfilledTableIsNotBody(){
        assertTrue((!MaterialAliasProof.evaluate(cp("商务技术评审索引表"),bid(page(1,cover),page(2,"目录\n商务评审索引表 ................ 5\n投标函 ................ 7"))).isPresent()));
        assertTrue((!MaterialAliasProof.evaluate(cp("技术投标索引表"),bid(page(1,cover),page(3,"技术评审索引表\n条款号 评审因素 对应页码 备注"))).isPresent()));
    }
    @Test void historicalIndexCannotUseCurrentCover(){
        cn.bidcheck.domain.Domain.Document doc=bid(page(1,cover),page(400,"关于贵公司2023年福建营业厅改造项目的招标文件，我公司承诺如下。"),page(401,index("技术评审索引表")));
        assertTrue((!MaterialAliasProof.evaluate(cp("技术投标索引表"),doc).isPresent()));
    }
    @Test void aliasDoesNotProveEntireVolumeCompleteness(){assertTrue((!MaterialAliasProof.evaluate(cp("第一分册商务投标文件全部证明齐全"),bid(page(1,cover))).isPresent()));}
    @Test void businessTechnicalIndexCannotBeReducedWithoutFrozenBusinessVolumeScope(){
        cn.bidcheck.domain.Domain.Document doc=bid(page(1,cover),page(5,index("商务评审索引表")));
        assertTrue((!MaterialAliasProof.evaluate(cp("商务技术评审索引表"),doc).isPresent()));
        assertTrue((!MaterialAliasProof.evaluate(boundCp("商务技术评审索引表"),doc,tender("第二分册 技术投标文件")).isPresent()));
        cn.bidcheck.domain.Domain.Document differentSource=new Document("other","p","TENDER","t.docx",1,"thash","",tender("第一分册 商务投标文件").pages(),cn.bidcheck.compat.Java8Api.listOf());
        assertTrue((!MaterialAliasProof.evaluate(boundCp("商务技术评审索引表"),doc,differentSource).isPresent()));
    }
}
