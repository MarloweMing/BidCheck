package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentSubjectBindingsTest {
    private Rule certificate(String subject){return new Rule("certificate","负责人证书","CERTIFICATE",subject,"证书","EXISTS","注册建造师证书","",Map.of(),"项目负责人应具备注册建造师证书","",List.of());}
    private EvidenceCorpus corpus(String...texts){return new EvidenceCorpus(List.of(SharedFactReuseTest.doc("bid","project",1,texts)));}
    private Fact appointment(EvidenceCorpus corpus,int page,String person,Map<String,Object> attrs){
        Source source=corpus.all.stream().filter(s->s.evidence().page()==page).findFirst().orElseThrow();
        return new Fact("appointment-"+page,"PERSON_ROLE","项目负责人","姓名",person,person,"","","CURRENT_PROJECT",List.of(source.id()),attrs);
    }
    private Map<String,Object> contract(EvidenceCorpus corpus,List<Fact> facts){return AgentInvestigationScope.contracts(List.of(certificate("项目负责人")),facts,corpus,List.of()).get(0);}

    @Test void unresolvedRoleIsASelectorAndNeverAdvertisedAsCertificateHolder(){
        Map<String,Object> value=AgentInvestigationScope.contracts(List.of(certificate("项目负责人"))).get(0);
        assertEquals("项目负责人",value.get("subjectSelector"));assertEquals("",value.get("subject"));
        assertEquals("UNRESOLVED",value.get("bindingStatus"));assertEquals("RESOLVE_SUBJECT_FIRST",value.get("nextAction"));
        assertEquals(List.of(),value.get("resolvedSubjects"));assertEquals(List.of("PERSON_ROLE","RELATION"),value.get("identityPrerequisiteKinds"));
    }
    @Test void acceptedCurrentAppointmentProducesActualHolderAndSourceLocalIdentity(){
        EvidenceCorpus corpus=corpus("本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        Fact role=appointment(corpus,1,"测试甲",Map.of("personId","FORGED","organizationId","FORGED","employer","想象公司"));
        Map<String,Object> value=contract(corpus,List.of(role));assertEquals("测试甲",value.get("subject"));assertEquals("RESOLVED",value.get("bindingStatus"));
        Map<String,Object> person=RuleEvaluator.maps(value.get("resolvedSubjects")).get(0);
        assertEquals("SOURCE_LOCAL",person.get("identityStrength"));assertFalse(str(person,"personId").isBlank());assertNotEquals("FORGED",person.get("personId"));
        assertEquals("",person.get("organizationId"));assertEquals(role.sourceIds(),person.get("sourceIds"));assertEquals(List.of(role.id()),person.get("bindingFactIds"));
        assertEquals(false,value.get("bindingIsBusinessProof"));assertFalse(value.toString().contains("想象公司"));
    }
    @Test void newAcceptedRoleRefreshesContractWithoutChangingRuleSubject(){
        EvidenceCorpus corpus=corpus("本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        assertEquals("UNRESOLVED",contract(corpus,List.of()).get("bindingStatus"));
        assertEquals("测试甲",contract(corpus,List.of(appointment(corpus,1,"测试甲",Map.of()))).get("subject"));
        assertEquals("项目负责人",certificate("项目负责人").subject());
    }
    @Test void historicalOrSourceMissingRolesCannotResolveCurrentHolder(){
        EvidenceCorpus corpus=corpus("类似项目业绩\n历史福建项目\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        Fact historical=appointment(corpus,1,"测试甲",Map.of());
        assertEquals("UNRESOLVED",contract(corpus,List.of(historical)).get("bindingStatus"));
        Fact orphan=new Fact("orphan","PERSON_ROLE","项目负责人","姓名","测试甲","测试甲","","","CURRENT_PROJECT",List.of("unknown-source"),Map.of());
        assertEquals("UNRESOLVED",contract(corpus,List.of(orphan)).get("bindingStatus"));
    }
    @Test void certificateNameAloneDoesNotInventCurrentAppointment(){
        EvidenceCorpus corpus=corpus("一级注册建造师证书\n姓名：测试甲\n证书编号：AB123\n本页是人员资格证明，未列示拟任岗位信息。");
        String source=corpus.all.get(0).id();Fact certificate=new Fact("certificate","CERTIFICATE","测试甲","证书","AB123","AB123","","","UNRESOLVED",List.of(source),Map.of("category","注册建造师证书","number","AB123","numberField","证书编号"));
        assertEquals("UNRESOLVED",contract(corpus,List.of(certificate)).get("bindingStatus"));
    }
    @Test void sameNameLocalOccurrencesRemainSeparateAndDifferentStrongIdentifiersConflict(){
        String prefix="本项目人员配置\n项目负责人：同名甲";
        EvidenceCorpus local=corpus(prefix+"\n拟在本合同任职：项目负责人",prefix+"\n拟在本合同任职：项目负责人");
        Map<String,Object> hints=contract(local,List.of(appointment(local,1,"同名甲",Map.of()),appointment(local,2,"同名甲",Map.of())));
        List<Map<String,Object>> occurrences=RuleEvaluator.maps(hints.get("resolvedSubjects"));assertEquals(2,occurrences.size());
        assertNotEquals(occurrences.get(0).get("personId"),occurrences.get(1).get("personId"));
        EvidenceCorpus strong=corpus(prefix+" 身份证号码：110101199001010011",prefix+" 身份证号码：110101199202020022");
        Map<String,Object> conflict=contract(strong,List.of(appointment(strong,1,"同名甲",Map.of()),appointment(strong,2,"同名甲",Map.of())));
        assertEquals("IDENTITY_CONFLICT",conflict.get("bindingStatus"));assertFalse(conflict.toString().contains("110101199001010011"));
    }
    @Test void multipleRoleHoldersAreNeverCollapsedToAnArbitraryFirstName(){
        EvidenceCorpus corpus=corpus("本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人","本项目人员配置\n项目负责人：测试乙\n拟在本合同任职：项目负责人");
        Map<String,Object> value=contract(corpus,List.of(appointment(corpus,1,"测试甲",Map.of()),appointment(corpus,2,"测试乙",Map.of())));
        assertEquals("MULTIPLE_CANDIDATES",value.get("bindingStatus"));assertEquals("",value.get("subject"));assertEquals(2,RuleEvaluator.maps(value.get("resolvedSubjects")).size());
    }
    @Test void formalBidderBindingProducesOrganizationIdentityWithoutEmploymentInference(){
        Rule r=certificate("投标人");EvidenceCorpus corpus=corpus("1.商务投标文件封面\n（测试中心综合楼装修工程）\n投标文件\n投标人名称：（盖章）测试建筑有限公司");
        List<Fact> facts=EnterpriseSubjectBindings.extract(List.of(r),corpus.all);assertFalse(facts.isEmpty());
        Map<String,Object> value=AgentInvestigationScope.contracts(List.of(r),facts,corpus,List.of()).get(0);
        Map<String,Object> company=RuleEvaluator.maps(value.get("resolvedSubjects")).get(0);
        assertEquals("测试建筑有限公司",company.get("subject"));assertEquals("ORGANIZATION",company.get("entityType"));
        assertFalse(str(company,"organizationId").isBlank());assertEquals("",company.get("personId"));assertEquals(false,value.get("bindingIsBusinessProof"));
    }
    @Test void certificateRoleSelectorIsRejectedBeforeSourceExtractionButConcreteCompanyAndPersonAreAllowed(){
        Map<String,Object> candidate=linked("kind","CERTIFICATE","subject","项目负责人","field","证书","sourceIds",List.of("source"));
        Map<String,Object> issue=AgentInvestigationScope.candidateSubjectIssue(candidate,List.of(certificate("项目负责人")));
        assertEquals("SUBJECT_BINDING_REQUIRED",issue.get("code"));assertEquals("RESOLVE_SUBJECT_FIRST",issue.get("nextAction"));
        assertEquals(List.of("certificate"),issue.get("targetObligationIds"));assertEquals(List.of("source"),issue.get("sourceIds"));
        candidate.put("subject","测试甲");assertTrue(AgentInvestigationScope.candidateSubjectIssue(candidate,List.of(certificate("项目负责人"))).isEmpty());
        candidate.put("subject","测试建筑有限公司");assertTrue(AgentInvestigationScope.candidateSubjectIssue(candidate,List.of(certificate("投标人"))).isEmpty());
        candidate.put("subject","投标人");assertEquals("SUBJECT_BINDING_REQUIRED",AgentInvestigationScope.candidateSubjectIssue(candidate,List.of(certificate("投标人"))).get("code"));
    }
    @Test void prerequisiteRoleRelationsRemainAdmissibleAndWrongFactKindsRemainOutsideTarget(){
        var rules=List.of(certificate("项目负责人"));
        assertTrue(AgentInvestigationScope.candidateSubjectIssue(linked("kind","PERSON_ROLE","subject","项目负责人","value","测试甲"),rules).isEmpty());
        assertTrue(AgentInvestigationScope.candidateSubjectIssue(linked("kind","RELATION","subject","项目负责人","attributes",Map.of("object","测试甲")),rules).isEmpty());
        assertTrue(AgentInvestigationScope.candidateSubjectIssue(linked("kind","MONTHS","subject","项目负责人"),rules).isEmpty());
        Rule months=new Rule("months","社保证明","MONTHS","项目负责人","社保","","","",Map.of(),"项目负责人提供社保","",List.of());
        assertEquals("SUBJECT_BINDING_REQUIRED",AgentInvestigationScope.candidateSubjectIssue(linked("kind","MONTHS","subject","","attributes",Map.of("person","项目负责人")),List.of(months)).get("code"));
    }
}
