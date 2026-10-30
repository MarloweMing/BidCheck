package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class NativeCertificateFactsTest {
    private final String body="资质证书\n企业名称：甲设计有限公司\n资质等级：建筑装饰工程设计专项甲级。\n证书编号：A111033727\n有效期：至2026年06月04日\n发证机关：\n2021年06月16日";
    private EvidenceCorpus corpus(String text){return new EvidenceCorpus(List.of(new Document("bid","project","BID","fixture.pdf",1,"sha","",List.of(new Page(15,text,"PAGE","PDF","")),List.of())));}
    private Rule rule(){return new Rule("r","资质证明","CERTIFICATE","甲设计有限公司","证书","","","",Map.of("category","建筑装饰工程设计专项","grade","甲级"),"建筑装饰工程设计专项甲级","",List.of());}
    @Test void localCertificateBindsOwnerNumberCategoryAndOnlyExpiry(){EvidenceCorpus c=corpus(body);List<Fact> facts=c.nativeFacts(List.of(rule()),c.all);assertEquals(1,facts.size());Fact f=facts.get(0);assertEquals("甲设计有限公司",f.subject());assertEquals("2026-06-04",f.attributes().get("end"));assertFalse(f.attributes().containsKey("start"));assertEquals("PASS",new RuleEvaluator(facts,c,Map.of()).evaluate(rule()).status());}
    @Test void multipleOwnersOrCertificatesCannotBeCollapsed(){for(String extra:List.of("\n企业名称：乙设计有限公司","\n证书编号：B22222")){EvidenceCorpus c=corpus(body+extra);assertTrue(c.nativeFacts(List.of(rule()),c.all).isEmpty());}}
    @Test void consortiumDescriptionIsNotACertificate(){EvidenceCorpus c=corpus("联合体协议书\n企业名称：甲设计有限公司\n建筑装饰工程设计专项甲级\n证书编号：A111033727");assertTrue(c.nativeFacts(List.of(rule()),c.all).isEmpty());}
    private Rule titleRule(){return new Rule("title","职称","CERTIFICATE","项目负责人","职称","","","",Map.of("category","职称"),"项目负责人职称","",List.of());}
    private String titleText(){return "# 职称证书\n专业名称 建筑装饰\nProfession Series\n资格名称 工程师\nPost Qualification\n姓 名 李明\nName\n身份证号 110101199001012211\n证书管理号 209999999999999";}
    @Test void professionalTitleFieldsAreReadLiterallyFromOneCertificate(){EvidenceCorpus c=corpus(titleText());var facts=c.nativeFacts(List.of(titleRule()),c.all);assertEquals(1,facts.size());Fact f=facts.get(0);assertEquals("工程师",f.attributes().get("grade"));assertEquals("建筑装饰",f.attributes().get("profession"));assertEquals("证书管理号",f.attributes().get("numberField"));assertFalse(CertificateEvidenceRecords.certificates(f,c).isEmpty());assertTrue(CertificateEvidenceRecords.certificates(f,c).get(0).fields().containsKey("PROFESSION"));}
    @Test void titleTierCannotBeInventedWhenOriginalDoesNotStateIt(){EvidenceCorpus c=corpus(titleText().replace("资格名称 工程师","资格名称 未注明"));var facts=c.nativeFacts(List.of(titleRule()),c.all);assertEquals(1,facts.size());assertFalse(facts.get(0).attributes().containsKey("grade"));}
    @Test void multipleOwnersOrNumbersCannotBeJoinedAsOneTitleCertificate(){for(String extra:List.of("\n姓名 王明","\n证书编号 209999999999998","\n一级注册建筑师注册证书")){EvidenceCorpus c=corpus(titleText()+extra);assertTrue(c.nativeFacts(List.of(titleRule()),c.all).isEmpty());}}
}
