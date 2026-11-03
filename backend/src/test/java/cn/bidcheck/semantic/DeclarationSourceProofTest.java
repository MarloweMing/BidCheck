package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeclarationSourceProofTest {
    private static final String TITLE="供应商黑名单管理承诺书";
    private static final String HEAD=TITLE+"\n一、中国联通实行供应商黑名单管理制度，供应商应当遵守。\n10";
    private static final String BODY="供应商如被列入名单，相关方有权禁止其提供产品或服务。\n我方承诺知晓并遵守上述管理办法相关规定，并负责向工作人员说明。\n投标人（盖章）：甲有限公司\n日期：2025年10月8日\n11";
    private Document doc(String first,String body){return new Document("bid","project","BID","fixture.pdf",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"（2025年海南综合楼二次装修工程）\n投标文件（商务部分）","PAGE","PDF",""), new Page(10,first,"PAGE","PDF",""), new Page(11,body,"PAGE","PDF","")),cn.bidcheck.compat.Java8Api.listOf());}
    private Evidence evidence(int page,String text){return new Evidence("bid","fixture.pdf",page,"PAGE",text,"source",1);}
    @Test void boundedContinuationProvesWrittenDeclarationOnly(){Document d=doc(HEAD,BODY);java.util.Map<java.lang.String, java.lang.Object> proof=DeclarationSourceProof.prove(d,evidence(11,BODY),"甲有限公司",TITLE,cn.bidcheck.compat.Java8Api.listOf(evidence(10,HEAD), evidence(11,BODY)));assertEquals("WRITTEN_DECLARATION_ONLY",proof.get("proofLevel"));assertEquals(false,proof.get("signatureVerified"));assertEquals(false,proof.get("declaredFactsVerified"));}
    @Test void wrongIssuerUnsentHeadingAndWrongPageMapAreRejected(){assertTrue(DeclarationSourceProof.prove(doc(HEAD,BODY),evidence(11,BODY),"乙有限公司",TITLE,cn.bidcheck.compat.Java8Api.listOf(evidence(10,HEAD))).isEmpty());assertTrue(DeclarationSourceProof.prove(doc(HEAD,BODY),evidence(11,BODY),"甲有限公司",TITLE,cn.bidcheck.compat.Java8Api.listOf()).isEmpty());String wrong=BODY.substring(0,BODY.length()-2)+"21";assertTrue(DeclarationSourceProof.prove(doc(HEAD,wrong),evidence(11,wrong),"甲有限公司",TITLE,cn.bidcheck.compat.Java8Api.listOf(evidence(10,HEAD))).isEmpty());}
    @Test void newTitleOrForeignProjectBlocksAdjacency(){for(String prefix:cn.bidcheck.compat.Java8Api.listOf("(3)合规经营承诺书\n", "项目名称：2023年福建营业厅历史装修改造工程\n")){String body=prefix+BODY;assertTrue(DeclarationSourceProof.prove(doc(HEAD,body),evidence(11,body),"甲有限公司",TITLE,cn.bidcheck.compat.Java8Api.listOf(evidence(10,HEAD))).isEmpty());}}
    @Test void twoTitlesCannotChooseOneBySimilarity(){String heading=TITLE+"\n合规经营承诺书\n10";assertTrue(DeclarationSourceProof.prove(doc(heading,BODY),evidence(11,BODY),"甲有限公司",TITLE,cn.bidcheck.compat.Java8Api.listOf(evidence(10,heading))).isEmpty());}
    @Test void declarationRelationDoesNotUseEmploymentRule(){java.util.Map<java.lang.String, cn.bidcheck.domain.Domain.Evidence> available=cn.bidcheck.compat.Java8Api.mapOf("C1", evidence(10,HEAD), "C2", evidence(11,BODY));com.fasterxml.jackson.databind.JsonNode claims=new ObjectMapper().valueToTree(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("kind", "RELATION", "subject", "甲有限公司", "object", TITLE, "relation", "ISSUED_COMMITMENT", "text", "承诺书落款为甲有限公司，仅证明文字响应", "evidenceIds", cn.bidcheck.compat.Java8Api.listOf("C2"))));assertEquals("",ClaimGrounding.validate(claims,available,"",doc(HEAD,BODY)));assertFalse(cn.bidcheck.compat.Java8Api.isBlank(ClaimGrounding.validate(claims,available,"")));assertEquals(1,ClaimGrounding.declarationProofs(claims,available,doc(HEAD,BODY)).size());}
}
