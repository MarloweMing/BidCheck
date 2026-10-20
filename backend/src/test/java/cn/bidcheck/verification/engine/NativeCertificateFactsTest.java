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
}
