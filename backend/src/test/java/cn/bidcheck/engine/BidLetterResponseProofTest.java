package cn.bidcheck.engine;
import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BidLetterResponseProofTest {
    private final String project="合成海南中心综合楼二次装修工程";
    private Page page(int n,String text){return new Page(n,text,"PAGE","OCR","");}
    private Document bid(String text){return new Document("bid","project","BID","bid.pdf",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(page(1,"（"+project+"）\n投标文件（商务部分）"), page(7,text)),cn.bidcheck.compat.Java8Api.listOf());}
    private String letter(String values){return "3.投标函\n投 标 函\n我方参加本次投标，承诺如下：\n"+values+"\n投标人（盖章）：甲公司\n7";}
    private Checkpoint cp(String expected){return new Checkpoint("cp","投标函格式响应","响应检查","HIGH","SEMANTIC_RESPONSE","compliance","REVIEW",expected,"",true,true,true,null);}
    @Test void explicitFormResponsesAreDecidedWithoutModel(){
        Document bid=bid(letter("承诺工期为 满足 （填写“满足”），质量为 合格 （填写“合格”）。"));
        assertEquals("PASS",BidLetterResponseProof.evaluate(cp("工期\t满足。（见投标函）"),bid,null).orElseThrow(java.util.NoSuchElementException::new).evaluation().status());
        assertEquals("PASS",BidLetterResponseProof.evaluate(cp("质量\t合格。（见投标函）"),bid,null).orElseThrow(java.util.NoSuchElementException::new).evaluation().status());
    }
    @Test void templateWordDoesNotSupplyAnUnfilledAnswer(){assertTrue((!BidLetterResponseProof.evaluate(cp("质量 合格。（见投标函）"),bid(letter("质量为 ____ （填写“合格”）。")),null).isPresent()));}
    @Test void letterCannotProveActualScheduleOrCompoundRequirement(){assertTrue((!BidLetterResponseProof.evaluate(cp("工期满足。（见投标函）；须提供详细进度及人员计划"),bid(letter("承诺工期为满足。")),null).isPresent()));}
    @Test void explicitNegativeResponseFails(){assertEquals("FAIL",BidLetterResponseProof.evaluate(cp("质量 合格。（见投标函）"),bid(letter("质量为 不合格。")),null).orElseThrow(java.util.NoSuchElementException::new).evaluation().status());}
    @Test void otherProjectLetterAndNoCurrentCoverAreNotAdmitted(){
        cn.bidcheck.domain.Domain.Document historical=bid("关于贵公司2023年福建营业厅装修改造项目的招标文件，我公司承诺如下。\n"+letter("承诺工期为满足。"));
        assertTrue((!BidLetterResponseProof.evaluate(cp("工期 满足。（见投标函）"),historical,null).isPresent()));
        cn.bidcheck.domain.Domain.Document bare=new Document("bid","project","BID","bid.pdf",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(page(7,letter("承诺工期为满足。"))),cn.bidcheck.compat.Java8Api.listOf());
        assertTrue((!BidLetterResponseProof.evaluate(cp("工期 满足。（见投标函）"),bare,null).isPresent()));
    }
    @Test void numericValidityIsReadOnlyFromCurrentLetter(){
        cn.bidcheck.domain.Domain.Checkpoint cp=new Checkpoint("cp","投标有效期","响应检查","HIGH","VALUE_COMPARE","bidValidity",">=","180","天",true,true,true,null);
        cn.bidcheck.domain.Domain.Evaluation evaluation=new CheckEngine().evaluate(cp,bid(letter("在招标文件规定的投标有效期（投标有效期为 180 天）内不撤销文件。")),cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("PASS",evaluation.status());assertEquals("BID_LETTER_RESPONSE_RULE",evaluation.method());
        assertEquals("FAIL",new CheckEngine().evaluate(cp,bid(letter("投标有效期为 90 天。")),cn.bidcheck.compat.Java8Api.listOf()).status());
        assertTrue((!BidLetterResponseProof.evaluate(cp,bid(letter("保证金有效期为180天，证书有效期为180天。")),null).isPresent()));
    }
    @Test void validityDeclarationNeedsUnambiguousTenderValue(){
        cn.bidcheck.domain.Domain.Page p=page(164,"投标有效期：180天");cn.bidcheck.domain.Domain.Document tender=new Document("t","project","TENDER","tender.docx",1,"thash","",cn.bidcheck.compat.Java8Api.listOf(p),cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.domain.Domain.Evidence source=new Evidence("t","tender.docx",164,"PAGE",p.text(),"p164",1);
        cn.bidcheck.domain.Domain.Checkpoint cp=new Checkpoint("cp","投标有效期","响应检查","HIGH","SEMANTIC_RESPONSE","compliance","REVIEW","投标有效期 满足招标文件规定。（见投标函）","",true,true,true,source);
        assertEquals("PASS",BidLetterResponseProof.evaluate(cp,bid(letter("投标有效期为180天。")),tender).orElseThrow(java.util.NoSuchElementException::new).evaluation().status());
        cn.bidcheck.domain.Domain.Document conflicting=new Document("t","project","TENDER","tender.docx",1,"thash","",cn.bidcheck.compat.Java8Api.listOf(p, page(165,"投标有效期：120天")),cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("UNDETERMINED",BidLetterResponseProof.evaluate(cp,bid(letter("投标有效期为180天。")),conflicting).orElseThrow(java.util.NoSuchElementException::new).evaluation().status());
    }
}
