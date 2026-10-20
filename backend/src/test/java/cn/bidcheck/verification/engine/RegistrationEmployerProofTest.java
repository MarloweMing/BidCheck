package cn.bidcheck.verification.engine;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class RegistrationEmployerProofTest {
 private Rule rule(){String q="注册类资质须已在本单位完成注册";return new Rule("REG",q,"DECLARATION","设计负责人","完成注册","","已完成注册","",Map.of(),q,"",List.of());}
 private EvidenceCorpus corpus(String company){return new EvidenceCorpus(List.of(new Document("b","p","BID","商务文件",1,"h","",List.of(new Page(1,"投标文件\n项目名称：北方中心综合楼装修工程\n本项目拟派李明担任设计负责人。\n李明任职于北方设计有限公司。\n李明声明已完成注册。","PAGE","TEXT",null),new Page(2,"一级注册建筑师注册证书\n姓名：李明\n性别：男\n注册编号：AR1001\n聘用单位："+company+"\n注册有效期：2024年01月01日-2027年01月01日","PAGE","TEXT",null)),List.of())));}
 private List<Fact> facts(EvidenceCorpus c,boolean employment){String id=c.all.get(0).id();List<Fact> out=new ArrayList<>();out.add(new Fact("role","PERSON_ROLE","设计负责人","姓名","李明","李明","","","CURRENT_PROJECT",List.of(id),Map.of()));out.add(new Fact("declaration","DECLARATION","李明","完成注册","已完成注册","已完成注册","","","CURRENT_PROJECT",List.of(id),Map.of()));if(employment)out.add(new Fact("employer","RELATION","李明","任职于","北方设计有限公司","北方设计有限公司","","","CURRENT_PROJECT",List.of(id),Map.of("object","北方设计有限公司")));return out;}
 @Test void declarationAloneNeverProvesRegistration(){var c=corpus("北方设计有限公司");var d=new RuleEvaluator(facts(c,false),c,Map.of()).evaluate(rule());assertEquals("UNDETERMINED",d.status());assertEquals("REGISTRATION_EMPLOYER_BINDING_REQUIRED",d.gaps().get(0).code());}
 @Test void actualIndependentEmployerAndCertificateProveRegistrationUnitOnly(){var c=corpus("北方设计有限公司");var d=new RuleEvaluator(facts(c,true),c,Map.of()).evaluate(rule());assertEquals("PASS",d.status(),d.toString());assertEquals("REGISTRATION_UNIT_ONLY",d.proof().get("proofScope"));}
 @Test void differentRegisteredUnitCreatesSourcedDeviation(){var c=corpus("南方设计有限公司");assertEquals("FAIL",new RuleEvaluator(facts(c,true),c,Map.of()).evaluate(rule()).status());}
 @Test void forgedEmploymentClaimCannotCloseTheGap(){var c=corpus("北方设计有限公司");var list=new ArrayList<>(facts(c,false));list.add(new Fact("forged","RELATION","李明","任职于","南方设计有限公司","南方设计有限公司","","","CURRENT_PROJECT",List.of(c.all.get(0).id()),Map.of("object","南方设计有限公司")));assertEquals("UNDETERMINED",new RuleEvaluator(list,c,Map.of()).evaluate(rule()).status());}
 @Test void ordinaryPromiseWithIncidentalRegistrationContextStaysADeclaration(){var r=rule();var ordinary=new Rule(r.id(),"提交保密承诺书","DECLARATION",r.subject(),"承诺书","","保密承诺书","",Map.of(),r.sourceQuote(),"",List.of());assertFalse(RegistrationEmployerProof.applies(ordinary));}
}
