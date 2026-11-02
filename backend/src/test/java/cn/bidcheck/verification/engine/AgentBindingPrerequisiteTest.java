package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentBindingPrerequisiteTest {
    Rule credential(String id,String subject){return new Rule(id,"岗位证书","CERTIFICATE",subject,"证书","","","",Map.of("category","注册建造师"),subject+"应持注册建造师证书","",List.of());}
    @Test void aliasesShareOnePrerequisiteButRetainEveryDependentTarget(){
        var targets=AgentInvestigationScope.withIdentityPrerequisites(List.of(credential("a","施工部分负责人"),credential("b","施工负责人")));
        assertEquals(3,targets.size());Rule binding=targets.get(0);assertEquals("PERSON_ROLE",binding.predicate());assertEquals(List.of("a","b"),binding.parameters().get("prerequisiteFor"));assertEquals(true,binding.parameters().get("doesNotProveDependentObligations"));
    }
    @Test void resolvingAppointmentClosesOnlyTheBindingTarget(){
        var fixture=new PersonnelCountRoleTest();var corpus=fixture.corpus("本项目人员配置\n施工部分负责人：测试甲");var facts=fixture.checked(corpus,fixture.role(corpus,1,"施工部分负责人","测试甲","",""));Rule cert=credential("cert","施工部分负责人");Plan plan=new Plan("ALL",List.of(cert),"FIXTURE",List.of());
        RuleEvaluator evaluator=new RuleEvaluator(facts,corpus,Map.of());evaluator.evaluate(cert);var targets=AgentInvestigationScope.withIdentityPrerequisites(List.of(cert));var receipt=AgentInvestigationScope.targetReceipt(targets,plan,evaluator);
        assertEquals(List.of(targets.get(0).id()),receipt.get("completedTargetObligationIds"));assertEquals(List.of("cert"),receipt.get("remainingTargetObligationIds"));assertFalse(AgentInvestigationScope.completed(targets,plan,evaluator));
    }
    @Test void satisfiedAlternativeDoesNotRequireUnusedPersons(){
        Rule cert=credential("cert","设计负责人"),material=new Rule("proof","另一证明","MATERIAL","","","","证明","",Map.of(),"证明","",List.of());Rule any=new Rule("choice","任选","GROUP","","","","","",Map.of(),"任选","ANY",List.of(cert,material));Plan plan=new Plan("ALL",List.of(any),"FIXTURE",List.of());
        var corpus=new PersonnelCountRoleTest().corpus("资料");var evaluator=new RuleEvaluator(List.of(),corpus,Map.of());evaluator.decisions.put("choice",new Decision("choice","任选","PASS","已满足",List.of(),List.of(),Map.of()));
        assertTrue(AgentInvestigationScope.completed(AgentInvestigationScope.withIdentityPrerequisites(List.of(cert,material)),plan,evaluator));
    }
}
