package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentSubjectBindingsTest {
    private Rule certificate(String subject){return new Rule("certificate","负责人证书","CERTIFICATE",subject,"证书","EXISTS","注册建造师证书","",cn.bidcheck.compat.Java8Api.mapOf(),"项目负责人应具备注册建造师证书","",cn.bidcheck.compat.Java8Api.listOf());}
    private EvidenceCorpus corpus(String...texts){return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","project",1,texts)));}
    private Fact appointment(EvidenceCorpus corpus,int page,String person,Map<String,Object> attrs){
        Source source=corpus.all.stream().filter(s->s.evidence().page()==page).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        return new Fact("appointment-"+page,"PERSON_ROLE","项目负责人","姓名",person,person,"","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(source.id()),attrs);
    }
    private Map<String,Object> contract(EvidenceCorpus corpus,List<Fact> facts){return AgentInvestigationScope.contracts(cn.bidcheck.compat.Java8Api.listOf(certificate("项目负责人")),facts,corpus,cn.bidcheck.compat.Java8Api.listOf()).get(0);}

    @Test void unresolvedRoleIsASelectorAndNeverAdvertisedAsCertificateHolder(){
        Map<String,Object> value=AgentInvestigationScope.contracts(cn.bidcheck.compat.Java8Api.listOf(certificate("项目负责人"))).get(0);
        assertEquals("项目负责人",value.get("subjectSelector"));assertEquals("",value.get("subject"));
        assertEquals("UNRESOLVED",value.get("bindingStatus"));assertEquals("RESOLVE_SUBJECT_FIRST",value.get("nextAction"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),value.get("resolvedSubjects"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("PERSON_ROLE", "RELATION"),value.get("identityPrerequisiteKinds"));
    }
    @Test void acceptedCurrentAppointmentProducesActualHolderAndSourceLocalIdentity(){
        EvidenceCorpus corpus=corpus("本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        Fact role=appointment(corpus,1,"测试甲",cn.bidcheck.compat.Java8Api.mapOf("personId", "FORGED", "organizationId", "FORGED", "employer", "想象公司"));
        Map<String,Object> value=contract(corpus,cn.bidcheck.compat.Java8Api.listOf(role));assertEquals("测试甲",value.get("subject"));assertEquals("RESOLVED",value.get("bindingStatus"));
        Map<String,Object> person=RuleEvaluator.maps(value.get("resolvedSubjects")).get(0);
        assertEquals("SOURCE_LOCAL",person.get("identityStrength"));assertFalse(cn.bidcheck.compat.Java8Api.isBlank(str(person,"personId")));assertNotEquals("FORGED",person.get("personId"));
        assertEquals("",person.get("organizationId"));assertEquals(role.sourceIds(),person.get("sourceIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(role.id()),person.get("bindingFactIds"));
        assertEquals(false,value.get("bindingIsBusinessProof"));assertFalse(value.toString().contains("想象公司"));
    }
    @Test void newAcceptedRoleRefreshesContractWithoutChangingRuleSubject(){
        EvidenceCorpus corpus=corpus("本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        assertEquals("UNRESOLVED",contract(corpus,cn.bidcheck.compat.Java8Api.listOf()).get("bindingStatus"));
        assertEquals("测试甲",contract(corpus,cn.bidcheck.compat.Java8Api.listOf(appointment(corpus,1,"测试甲",cn.bidcheck.compat.Java8Api.mapOf()))).get("subject"));
        assertEquals("项目负责人",certificate("项目负责人").subject());
    }
    @Test void historicalOrSourceMissingRolesCannotResolveCurrentHolder(){
        EvidenceCorpus corpus=corpus("类似项目业绩\n历史福建项目\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        Fact historical=appointment(corpus,1,"测试甲",cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals("UNRESOLVED",contract(corpus,cn.bidcheck.compat.Java8Api.listOf(historical)).get("bindingStatus"));
        Fact orphan=new Fact("orphan","PERSON_ROLE","项目负责人","姓名","测试甲","测试甲","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf("unknown-source"),cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals("UNRESOLVED",contract(corpus,cn.bidcheck.compat.Java8Api.listOf(orphan)).get("bindingStatus"));
    }
    @Test void certificateNameAloneDoesNotInventCurrentAppointment(){
        EvidenceCorpus corpus=corpus("一级注册建造师证书\n姓名：测试甲\n证书编号：AB123\n本页是人员资格证明，未列示拟任岗位信息。");
        String source=corpus.all.get(0).id();Fact certificate=new Fact("certificate","CERTIFICATE","测试甲","证书","AB123","AB123","","","UNRESOLVED",cn.bidcheck.compat.Java8Api.listOf(source),cn.bidcheck.compat.Java8Api.mapOf("category", "注册建造师证书", "number", "AB123", "numberField", "证书编号"));
        assertEquals("UNRESOLVED",contract(corpus,cn.bidcheck.compat.Java8Api.listOf(certificate)).get("bindingStatus"));
    }
    @Test void sameNameLocalOccurrencesRemainSeparateAndDifferentStrongIdentifiersConflict(){
        String prefix="本项目人员配置\n项目负责人：同名甲";
        EvidenceCorpus local=corpus(prefix+"\n拟在本合同任职：项目负责人",prefix+"\n拟在本合同任职：项目负责人");
        Map<String,Object> hints=contract(local,cn.bidcheck.compat.Java8Api.listOf(appointment(local,1,"同名甲",cn.bidcheck.compat.Java8Api.mapOf()), appointment(local,2,"同名甲",cn.bidcheck.compat.Java8Api.mapOf())));
        List<Map<String,Object>> occurrences=RuleEvaluator.maps(hints.get("resolvedSubjects"));assertEquals(2,occurrences.size());
        assertNotEquals(occurrences.get(0).get("personId"),occurrences.get(1).get("personId"));
        EvidenceCorpus strong=corpus(prefix+" 身份证号码：110101199001010015",prefix+" 身份证号码：110101199202020025");
        Map<String,Object> conflict=contract(strong,cn.bidcheck.compat.Java8Api.listOf(appointment(strong,1,"同名甲",cn.bidcheck.compat.Java8Api.mapOf()), appointment(strong,2,"同名甲",cn.bidcheck.compat.Java8Api.mapOf())));
        assertEquals("IDENTITY_CONFLICT",conflict.get("bindingStatus"));assertFalse(conflict.toString().contains("110101199001010015"));
    }
    @Test void multipleRoleHoldersAreNeverCollapsedToAnArbitraryFirstName(){
        EvidenceCorpus corpus=corpus("本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人","本项目人员配置\n项目负责人：测试乙\n拟在本合同任职：项目负责人");
        Map<String,Object> value=contract(corpus,cn.bidcheck.compat.Java8Api.listOf(appointment(corpus,1,"测试甲",cn.bidcheck.compat.Java8Api.mapOf()), appointment(corpus,2,"测试乙",cn.bidcheck.compat.Java8Api.mapOf())));
        assertEquals("MULTIPLE_CANDIDATES",value.get("bindingStatus"));assertEquals("",value.get("subject"));assertEquals(2,RuleEvaluator.maps(value.get("resolvedSubjects")).size());
    }
    @Test void formalBidderBindingProducesOrganizationIdentityWithoutEmploymentInference(){
        Rule r=certificate("投标人");EvidenceCorpus corpus=corpus("1.商务投标文件封面\n（测试中心综合楼装修工程）\n投标文件\n投标人名称：（盖章）测试建筑有限公司");
        List<Fact> facts=EnterpriseSubjectBindings.extract(cn.bidcheck.compat.Java8Api.listOf(r),corpus.all);assertFalse(facts.isEmpty());
        Map<String,Object> value=AgentInvestigationScope.contracts(cn.bidcheck.compat.Java8Api.listOf(r),facts,corpus,cn.bidcheck.compat.Java8Api.listOf()).get(0);
        Map<String,Object> company=RuleEvaluator.maps(value.get("resolvedSubjects")).get(0);
        assertEquals("测试建筑有限公司",company.get("subject"));assertEquals("ORGANIZATION",company.get("entityType"));
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(str(company,"organizationId")));assertEquals("",company.get("personId"));assertEquals(false,value.get("bindingIsBusinessProof"));
    }
    @Test void certificateRoleSelectorIsRejectedBeforeSourceExtractionButConcreteCompanyAndPersonAreAllowed(){
        Map<String,Object> candidate=linked("kind","CERTIFICATE","subject","项目负责人","field","证书","sourceIds",cn.bidcheck.compat.Java8Api.listOf("source"));
        Map<String,Object> issue=AgentInvestigationScope.candidateSubjectIssue(candidate,cn.bidcheck.compat.Java8Api.listOf(certificate("项目负责人")));
        assertEquals("SUBJECT_BINDING_REQUIRED",issue.get("code"));assertEquals("RESOLVE_SUBJECT_FIRST",issue.get("nextAction"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("certificate"),issue.get("targetObligationIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("source"),issue.get("sourceIds"));
        candidate.put("subject","测试甲");assertTrue(AgentInvestigationScope.candidateSubjectIssue(candidate,cn.bidcheck.compat.Java8Api.listOf(certificate("项目负责人"))).isEmpty());
        candidate.put("subject","测试建筑有限公司");assertTrue(AgentInvestigationScope.candidateSubjectIssue(candidate,cn.bidcheck.compat.Java8Api.listOf(certificate("投标人"))).isEmpty());
        candidate.put("subject","投标人");assertEquals("SUBJECT_BINDING_REQUIRED",AgentInvestigationScope.candidateSubjectIssue(candidate,cn.bidcheck.compat.Java8Api.listOf(certificate("投标人"))).get("code"));
    }
    @Test void prerequisiteRoleRelationsRemainAdmissibleAndWrongFactKindsRemainOutsideTarget(){
        java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Rule> rules=cn.bidcheck.compat.Java8Api.listOf(certificate("项目负责人"));
        assertTrue(AgentInvestigationScope.candidateSubjectIssue(linked("kind","PERSON_ROLE","subject","项目负责人","value","测试甲"),rules).isEmpty());
        assertTrue(AgentInvestigationScope.candidateSubjectIssue(linked("kind","RELATION","subject","项目负责人","attributes",cn.bidcheck.compat.Java8Api.mapOf("object", "测试甲")),rules).isEmpty());
        assertTrue(AgentInvestigationScope.candidateSubjectIssue(linked("kind","MONTHS","subject","项目负责人"),rules).isEmpty());
        Rule months=new Rule("months","社保证明","MONTHS","项目负责人","社保","","","",cn.bidcheck.compat.Java8Api.mapOf(),"项目负责人提供社保","",cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("SUBJECT_BINDING_REQUIRED",AgentInvestigationScope.candidateSubjectIssue(linked("kind","MONTHS","subject","","attributes",cn.bidcheck.compat.Java8Api.mapOf("person", "项目负责人")),cn.bidcheck.compat.Java8Api.listOf(months)).get("code"));
    }
}
