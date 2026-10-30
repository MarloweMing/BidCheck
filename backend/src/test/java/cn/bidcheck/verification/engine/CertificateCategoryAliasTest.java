package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class CertificateCategoryAliasTest {
    private static final String OWNER="北方装饰有限公司",TYPE="质量管理体系认证";
    private EvidenceCorpus corpus(){return new EvidenceCorpus(List.of(new Document("b","p","BID","投标文件",1,"h","",List.of(new Page(1,TYPE+"证书\n企业名称："+OWNER+"\n证书编号：Q001\n等级：甲级\n专业：建筑工程\n有效期：2024年1月1日至2027年1月1日","PAGE","TEXT",null)),List.of())));}
    private Fact fact(EvidenceCorpus corpus,String category){return new Fact("f","CERTIFICATE",OWNER,"certificate",category,category,"","","CURRENT_PROJECT",List.of(corpus.all.get(0).id()),Map.of("category",category,"number","Q001","numberField","证书编号","grade","甲级","profession","建筑工程","validityField","有效期","start","2024-01-01","end","2027-01-01"));}
    private Rule rule(String predicate,Map<String,Object> params){return new Rule("r","认证证书要求",predicate,OWNER,predicate.equals("VALIDITY")?"有效期":"certificate","","","",params,TYPE+"须在投标截止日有效，核对有效期。","",List.of());}
    private Map<String,Object> reference(){return linked("referenceDate","2025-01-01","referenceDateSourceVerified",true,"referenceDateEvidence",Map.of("documentId","t","quote","投标截止日期：2025年1月1日","confirmation","SOURCE_UNAMBIGUOUS","confirmed",true));}
    @Test void optionalSuffixIsRecognizedForOnlyTheThreeKnownTypes(){for(String type:List.of(TYPE,"职业健康安全管理体系认证","环境管理体系认证")){assertTrue(DomainBindings.sameCertificateCategory(type,type+"证书"));assertTrue(DomainBindings.sameCertificateCategory(type+"证书",type));}assertFalse(DomainBindings.sameCertificateCategory(TYPE,"环境管理体系认证证书"));assertFalse(DomainBindings.sameCertificateCategory("安全生产许可证","安全生产许可证证书"));assertFalse(DomainBindings.sameCertificateCategory("注册建造师","注册建造师证书"));assertFalse(DomainBindings.sameCertificateCategory("有效的质量管理体系认证",TYPE+"证书"));}
    @Test void categoryAndValidityUseSameNarrowAlias(){var corpus=corpus();var fact=fact(corpus,TYPE+"证书");for(String predicate:List.of("CERTIFICATE","VALIDITY")){var params=linked("category",TYPE,"number","Q001","validityField","有效期");var evaluator=new RuleEvaluator(List.of(fact),corpus,reference());assertEquals("PASS",evaluator.evaluate(rule(predicate,params)).status());}}
    @Test void anotherSystemCertificationDoesNotSatisfyTheRequiredOne(){var corpus=corpus();var fact=fact(corpus,"环境管理体系认证证书");for(String predicate:List.of("CERTIFICATE","VALIDITY"))assertNotEquals("PASS",new RuleEvaluator(List.of(fact),corpus,reference()).evaluate(rule(predicate,Map.of("category",TYPE,"validityField","有效期"))).status());}
    @Test void aliasDoesNotEraseGradeProfessionOrCertificateNumber(){var corpus=corpus();var fact=fact(corpus,TYPE+"证书");for(String field:List.of("grade","profession","number")){Map<String,Object> params=linked("category",TYPE,field,field.equals("grade")?"乙级":field.equals("profession")?"市政工程":"Q002");assertNotEquals("PASS",new RuleEvaluator(List.of(fact),corpus,reference()).evaluate(rule("CERTIFICATE",params)).status());}}
    @Test void validityStillNeedsItsOwnFieldAndFullDates(){var corpus=corpus();var fact=fact(corpus,TYPE+"证书");var wrong=linked("category",TYPE,"validityField","注册有效期");assertNotEquals("PASS",new RuleEvaluator(List.of(fact),corpus,reference()).evaluate(rule("VALIDITY",wrong)).status());Map<String,Object> attributes=new LinkedHashMap<>(fact.attributes());attributes.remove("start");var partial=new Fact(fact.id(),fact.kind(),fact.subject(),fact.field(),fact.rawValue(),fact.value(),fact.unit(),fact.basis(),fact.scope(),fact.sourceIds(),attributes);assertNotEquals("PASS",new RuleEvaluator(List.of(partial),corpus,reference()).evaluate(rule("VALIDITY",Map.of("category",TYPE,"validityField","有效期"))).status());}
}
