package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonnelEmployerBindingsTest {
    final PersonnelCountRoleTest fixture=new PersonnelCountRoleTest();
    final String agreement="联合体协议书\n合成施工有限公司、合成设计有限公司[所有成员单位名称]自愿组成：合成施工有限公司和合成设计有限公司联合体，共同投标北方中心装修工程/标段。\n合成施工有限公司负责施工部分；合成设计有限公司负责设计部分。";
    EvidenceCorpus appointed(String text){return fixture.corpus("投标文件\n项目名称：北方中心装修工程\n投标人名称：（盖章）合成施工有限公司",agreement,text);}
    Map<String,Object> reference(){return Map.of("referenceDate","2025-08-05","referenceDateSourceVerified",true,"referenceDateEvidence",Map.of("documentId","tender","quote","投标截止时间：2025年8月5日","confirmation","SOURCE_UNAMBIGUOUS","confirmed",true));}
    Rule rule(String selector){return new Rule("months","本单位社保","MONTHS","测试甲","社保","","","",Map.of("employer","本单位","count",3,"personnelRoleSelector",selector),selector+"在本单位缴纳社保","",List.of());}
    @Test void designAndConstructionResolveToTheirOwnMemberNotTheLead(){
        for(String role:List.of("设计部分负责人","施工部分负责人")){
            var c=appointed("本项目人员配置\n"+role+"：测试甲");var fs=fixture.checked(c,fixture.role(c,3,role,"测试甲","",""));var binding=PersonnelEmployerBindings.resolve(rule(role),fs,c);
            assertTrue(binding.resolved(),binding.toString());assertEquals(List.of(role.startsWith("设计")?"合成设计有限公司":"合成施工有限公司"),binding.employers());assertEquals(true,binding.audit().get("doesNotProveEmployment"));
        }
    }
    @Test void sourceEmploymentTakesPriorityOverWorkAllocation(){
        var c=appointed("本项目合成另一有限公司设计部分负责人：测试甲");var fs=fixture.checked(c,fixture.role(c,3,"设计部分负责人","测试甲","合成另一有限公司",""));assertEquals(List.of("合成另一有限公司"),PersonnelEmployerBindings.resolve(rule("设计负责人"),fs,c).employers());
    }
    @Test void aRuleHintCannotCreateAnAppointmentAndProjectLeaderDoesNotImplyLeadEmployer(){
        var c=appointed("本项目人员配置\n项目负责人：测试甲");assertFalse(PersonnelEmployerBindings.resolve(rule("设计负责人"),List.of(),c).resolved());
        var fs=fixture.checked(c,fixture.role(c,3,"项目负责人","测试甲","",""));assertFalse(PersonnelEmployerBindings.resolve(rule("项目负责人"),fs,c).resolved());
        var result=new RuleEvaluator(fs,c,Map.of()).months(rule("项目负责人"),List.of());assertEquals("SOCIAL_EMPLOYER_CONFIRMATION_REQUIRED",result.gaps().get(0).code());assertEquals(true,result.proof().get("confirmationRequired"));
    }
    @Test void socialProofDoesNotSupplyItsOwnExpectedEmployer(){
        var c=fixture.corpus("本项目资料\n测试甲 合成施工有限公司 2025-01 2025-02 2025-03");Fact social=new Fact("social","MONTHS","测试甲","社保","社保","社保","","","CURRENT_PROJECT",List.of(c.all.get(0).id()),Map.of("person","测试甲","employer","合成施工有限公司","months",List.of("2025-01","2025-02","2025-03")));
        assertFalse(PersonnelEmployerBindings.resolve(rule("设计负责人"),List.of(social),c).resolved());
    }
    @Test void repeatedSameIdentityCanReuseEmployerWithoutCountingTwice(){
        var c=fixture.corpus(fixture.appointment("专职安全员","张甲","110101199001010011","甲公司",""),fixture.appointment("专职安全员","张甲","110101199001010011","",""));var fs=fixture.checked(c,fixture.role(c,1,"专职安全员","张甲","甲公司",""),fixture.role(c,2,"专职安全员","张甲","",""));var result=fixture.evaluate(c,fs,fixture.count(">=","1"));assertEquals("PASS",result.status(),result.toString());assertEquals(1,result.proof().get("provenDistinctPeople"));assertEquals("SOURCE_VALIDATED_SAME_PERSON_ONLY_NO_NEW_EMPLOYMENT_FACT",result.proof().get("memberReusePolicy"));
    }
    @Test void personnelPerformanceUsesTheSameMemberMappingAndStillRequiresRealSocialMonths(){
        String appointed="本项目人员配置\n姓名：测试甲\n拟在本合同任职 设计部分负责人\n缴费单位：合成设计有限公司\n缴费月份：2025-01、2025-02、2025-03";var c=appointed(appointed);Fact role=fixture.checked(c,fixture.role(c,3,"设计部分负责人","测试甲","","")).get(0);Fact social=new Fact("social","MONTHS","测试甲","社保","2025-01","2025-01","","","CURRENT_PROJECT",role.sourceIds(),Map.of("person","测试甲","employer","合成设计有限公司","months",List.of("2025-01","2025-02","2025-03")));Rule score=new Rule("score","设计负责人业绩","SCORE_COUNT","设计负责人","","","","",Map.of("months",3,"earliestMonth","2025-01"),"设计负责人在本单位连续缴纳三个月社保","",List.of());
        fixture.checked(c,role,social);Decision evaluated=SourcePerformanceScore.social(score,"测试甲",List.of(role),new RuleEvaluator(List.of(role,social),c,reference()));assertEquals("PASS",evaluated.status(),evaluated.toString());
        assertNotEquals("PASS",SourcePerformanceScore.social(score,"测试甲",List.of(role),new RuleEvaluator(List.of(role),c,reference())).status());
    }
}
