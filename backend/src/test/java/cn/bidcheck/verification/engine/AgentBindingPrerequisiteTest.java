package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentBindingPrerequisiteTest {
    Rule credential(String id,String subject){return new Rule(id,"岗位证书","CERTIFICATE",subject,"证书","","","",cn.bidcheck.compat.Java8Api.mapOf("category", "注册建造师"),subject+"应持注册建造师证书","",cn.bidcheck.compat.Java8Api.listOf());}
    @Test void aliasesShareOnePrerequisiteButRetainEveryDependentTarget(){
        java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Rule> targets=AgentInvestigationScope.withIdentityPrerequisites(cn.bidcheck.compat.Java8Api.listOf(credential("a","施工部分负责人"), credential("b","施工负责人")));
        assertEquals(3,targets.size());Rule binding=targets.get(0);assertEquals("PERSON_ROLE",binding.predicate());assertEquals(cn.bidcheck.compat.Java8Api.listOf("a", "b"),binding.parameters().get("prerequisiteFor"));assertEquals(true,binding.parameters().get("doesNotProveDependentObligations"));
    }
    @Test void resolvingAppointmentClosesOnlyTheBindingTarget(){
        cn.bidcheck.verification.engine.PersonnelCountRoleTest fixture=new PersonnelCountRoleTest();cn.bidcheck.verification.engine.EvidenceCorpus corpus=fixture.corpus("本项目人员配置\n施工部分负责人：测试甲");java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact> facts=fixture.checked(corpus,fixture.role(corpus,1,"施工部分负责人","测试甲","",""));Rule cert=credential("cert","施工部分负责人");Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(cert),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());
        RuleEvaluator evaluator=new RuleEvaluator(facts,corpus,cn.bidcheck.compat.Java8Api.mapOf());evaluator.evaluate(cert);java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Rule> targets=AgentInvestigationScope.withIdentityPrerequisites(cn.bidcheck.compat.Java8Api.listOf(cert));java.util.Map<java.lang.String, java.lang.Object> receipt=AgentInvestigationScope.targetReceipt(targets,plan,evaluator);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(targets.get(0).id()),receipt.get("completedTargetObligationIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("cert"),receipt.get("remainingTargetObligationIds"));assertFalse(AgentInvestigationScope.completed(targets,plan,evaluator));
    }
    @Test void satisfiedAlternativeDoesNotRequireUnusedPersons(){
        Rule cert=credential("cert","设计负责人"),material=new Rule("proof","另一证明","MATERIAL","","","","证明","",cn.bidcheck.compat.Java8Api.mapOf(),"证明","",cn.bidcheck.compat.Java8Api.listOf());Rule any=new Rule("choice","任选","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"任选","ANY",cn.bidcheck.compat.Java8Api.listOf(cert, material));Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(any),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.EvidenceCorpus corpus=new PersonnelCountRoleTest().corpus("资料");cn.bidcheck.verification.engine.RuleEvaluator evaluator=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),corpus,cn.bidcheck.compat.Java8Api.mapOf());evaluator.decisions.put("choice",new Decision("choice","任选","PASS","已满足",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf()));
        assertTrue(AgentInvestigationScope.completed(AgentInvestigationScope.withIdentityPrerequisites(cn.bidcheck.compat.Java8Api.listOf(cert, material)),plan,evaluator));
    }
}
