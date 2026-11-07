package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRuleSubjectsTest {
    private static final List<String> ROLES=cn.bidcheck.compat.Java8Api.listOf("施工企业主要负责人", "项目负责人", "施工部分负责人", "设计部分负责人", "专职安全员");
    private static final List<String> NAMES=cn.bidcheck.compat.Java8Api.listOf("测试甲", "测试乙", "测试丙", "测试丁", "测试戊");
    private static final String QUOTE="上述人员提供连续三个月在本单位缴纳社保证明。";
    private static final String REQUIREMENT="人员要求：施工企业主要负责人1人，项目负责人1人，施工部分负责人1人，设计部分负责人1人，专职安全员1人。"+QUOTE;
    private Rule rule(){return new RequirementContextBindings(REQUIREMENT,"ORIGINAL_REQUIREMENT").bind(
        new Rule("social","人员社保","MONTHS","上述人员","socialInsurance","","","月",cn.bidcheck.compat.Java8Api.mapOf("count", 3, "employer", "本单位"),QUOTE,"",cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.listOf());}
    private EvidenceCorpus corpus(){List<String> pages=new ArrayList<>();for(int i=0;i<ROLES.size();i++)pages.add("本项目人员配置\n姓名："+NAMES.get(i)+"\n拟在本合同任职："+ROLES.get(i));return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","project",1,pages.toArray(new String[0]))));}
    private List<Fact> appointments(EvidenceCorpus corpus){List<Fact> facts=new ArrayList<>();for(int i=0;i<ROLES.size();i++){Source source=corpus.all.get(i);facts.add(new Fact("role"+i,"PERSON_ROLE",ROLES.get(i),"姓名",NAMES.get(i),NAMES.get(i),"","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(source.id()),cn.bidcheck.compat.Java8Api.mapOf()));}return facts;}
    private Fact months(String person,String source){return new Fact("months-"+person,"MONTHS",person,"socialInsurance",person,person,"月","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(source),cn.bidcheck.compat.Java8Api.mapOf("months", cn.bidcheck.compat.Java8Api.listOf("2025-01", "2025-02", "2025-03")));}

    @Test void exactCollectiveAntecedentFeedsContractsAndNativeCandidateRelevance(){
        Rule r=rule();EvidenceCorpus c=corpus();List<Fact> facts=appointments(c);
        assertEquals(ROLES,AgentRuleSubjects.selectors(r));assertEquals(NAMES,AgentRuleSubjects.subjects(r,facts,c));
        Map<String,Object> contract=AgentInvestigationScope.contracts(cn.bidcheck.compat.Java8Api.listOf(r),facts,c,cn.bidcheck.compat.Java8Api.listOf()).get(0);
        assertEquals("RESOLVED",contract.get("bindingStatus"));assertEquals("",contract.get("subject"));assertEquals(ROLES,contract.get("subjectSelectors"));
        assertEquals(true,contract.get("allRequiredSelectorsResolved"));assertEquals(false,contract.get("bindingIsBusinessProof"));
        assertEquals(5,RuleEvaluator.maps(contract.get("subjectBindings")).size());
        for(String name:NAMES)assertTrue(AgentInvestigationScope.relevant(months(name,c.all.get(0).id()),cn.bidcheck.compat.Java8Api.listOf(r),facts,c));
        assertFalse(AgentInvestigationScope.relevant(months("其他某",c.all.get(0).id()),cn.bidcheck.compat.Java8Api.listOf(r),facts,c));
    }
    @Test void missingAppointmentKeepsTheMissingRoleInsteadOfClaimingTheGroupComplete(){
        Rule r=rule();EvidenceCorpus c=corpus();List<Fact> facts=appointments(c).subList(0,4);
        Map<String,Object> binding=AgentRuleSubjects.resolve(r,facts,c,cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("PARTIALLY_RESOLVED",binding.get("bindingStatus"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("专职安全员"),binding.get("unresolvedSubjectSelectors"));
        assertEquals(false,binding.get("allRequiredSelectorsResolved"));assertFalse(AgentInvestigationScope.relevant(months("测试戊",c.all.get(0).id()),cn.bidcheck.compat.Java8Api.listOf(r),facts,c));
    }
    @Test void arbitraryModelSuppliedRolesCannotTurnAnAnaphorIntoPeople(){
        Rule original=rule();Map<String,Object> parameters=new LinkedHashMap<>(original.parameters());
        Map<String,Object> forged=new LinkedHashMap<>((Map<String,Object>)parameters.get("requirementSubjectSet"));forged.put("roles",cn.bidcheck.compat.Java8Api.listOf("项目负责人"));parameters.put("requirementSubjectSet",forged);
        Rule r=new Rule(original.id(),original.description(),original.predicate(),original.subject(),original.field(),original.operator(),original.expected(),original.unit(),parameters,original.sourceQuote(),original.logic(),original.children());
        EvidenceCorpus c=corpus();assertEquals(cn.bidcheck.compat.Java8Api.listOf(),AgentRuleSubjects.selectors(r));
        assertEquals("SUBJECT_SET_UNRESOLVED",AgentRuleSubjects.resolve(r,appointments(c),c,cn.bidcheck.compat.Java8Api.listOf()).get("bindingStatus"));
        assertFalse(AgentInvestigationScope.relevant(months("测试乙",c.all.get(0).id()),cn.bidcheck.compat.Java8Api.listOf(r),appointments(c),c));
        assertEquals("",AgentInvestigationScope.contracts(cn.bidcheck.compat.Java8Api.listOf(r),appointments(c),c,cn.bidcheck.compat.Java8Api.listOf()).get(0).get("subject"));
    }
    @Test void collectiveAndIndividualRoleNamesCannotBeSubmittedAsInsuredPeople(){
        Rule r=rule();for(String invalid:cn.bidcheck.compat.Java8Api.listOf("上述人员", "项目负责人")){
            Map<String,Object> issue=AgentInvestigationScope.candidateSubjectIssue(linked("kind","MONTHS","subject",invalid),cn.bidcheck.compat.Java8Api.listOf(r));
            assertEquals("SUBJECT_BINDING_REQUIRED",issue.get("code"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(r.id()),issue.get("targetObligationIds"));
        }
    }
    @Test void identityPrerequisiteForEveryMemberIsAdmittedButCannotInventAnEmployer(){
        Rule r=rule();EvidenceCorpus c=corpus();List<Fact> facts=appointments(c);
        for(Fact f:facts)assertTrue(AgentInvestigationScope.relevant(f,cn.bidcheck.compat.Java8Api.listOf(r),cn.bidcheck.compat.Java8Api.listOf(),c));
        Map<String,Object> contract=AgentInvestigationScope.contracts(cn.bidcheck.compat.Java8Api.listOf(r),facts,c,cn.bidcheck.compat.Java8Api.listOf()).get(0);
        assertFalse(contract.toString().contains("employer="));assertEquals(cn.bidcheck.compat.Java8Api.listOf("PERSON_ROLE", "RELATION"),contract.get("identityPrerequisiteKinds"));
    }
    @Test void historicalAppointmentCannotSupplyTheCollectiveMember(){
        Rule r=rule();EvidenceCorpus c=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","project",1,"类似项目业绩\n历史福建项目\n姓名：测试甲\n拟在本合同任职：施工企业主要负责人")));
        Fact f=new Fact("old","PERSON_ROLE","施工企业主要负责人","姓名","测试甲","测试甲","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(c.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),AgentRuleSubjects.subjects(r,cn.bidcheck.compat.Java8Api.listOf(f),c));assertEquals("UNRESOLVED",AgentRuleSubjects.resolve(r,cn.bidcheck.compat.Java8Api.listOf(f),c,cn.bidcheck.compat.Java8Api.listOf()).get("bindingStatus"));
    }
    @Test void sameNameWithConflictingIdentifiersStillBlocksCompleteGroupBinding(){
        Rule r=rule();EvidenceCorpus c=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","project",1,
            "本项目人员配置\n姓名：测试甲\n身份证号码：110101199001010015\n拟在本合同任职：施工企业主要负责人",
            "本项目人员配置\n姓名：测试甲\n身份证号码：110101199202020025\n拟在本合同任职：施工企业主要负责人")));
        List<Fact> facts=new ArrayList<>();for(Source source:c.all)facts.add(new Fact(source.id(),"PERSON_ROLE","施工企业主要负责人","姓名","测试甲","测试甲","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(source.id()),cn.bidcheck.compat.Java8Api.mapOf()));
        // The labelled ID must directly follow the name: an intervening field does not
        // establish identity, even when both strings occur on the same physical page.
        Map<String,Object> individual=AgentSubjectBindings.resolve("施工企业主要负责人",facts,c,cn.bidcheck.compat.Java8Api.listOf());
        List<Map<String,Object>> people=RuleEvaluator.maps(individual.get("resolvedSubjects"));
        assertEquals(2,people.size());assertTrue(people.stream().allMatch(p->"STRONG".equals(p.get("identityStrength"))));
        assertEquals(2,people.stream().map(p->str(p,"personId")).distinct().count());
        assertEquals("IDENTITY_CONFLICT",individual.get("bindingStatus"));
        Map<String,Object> binding=AgentRuleSubjects.resolve(r,facts,c,cn.bidcheck.compat.Java8Api.listOf());assertEquals("IDENTITY_CONFLICT",binding.get("bindingStatus"));
        assertEquals(false,binding.get("allRequiredSelectorsResolved"));assertTrue(((List<?>)binding.get("unresolvedSubjectSelectors")).contains("施工企业主要负责人"));
        assertFalse(binding.toString().contains("110101199001010015"));
    }
    @Test void promptKeepsSourceContextAndValidatedRolesWithoutNestedReceipts(){
        Rule r=rule(),request=AgentInvestigationScope.requestRules(cn.bidcheck.compat.Java8Api.listOf(r)).get(0);Map<String,Object> p=request.parameters();
        assertFalse(p.containsKey("requirementSubjectSet"));assertFalse(p.containsKey("requirementBindings"));
        assertFalse(p.containsKey("requirementSourceContext"));assertEquals(r.sourceQuote(),request.sourceQuote());
        Map<?,?> context=(Map<?,?>)p.get("requirementContext");assertEquals(ROLES,context.get("validatedSubjectSelectors"));
        assertTrue(context.toString().contains(REQUIREMENT));assertEquals(false,context.get("contextIsAdditionalObligation"));
    }
    @Test void longSourceContextHasExplicitOffsetsAndTruncationInsteadOfRepeatedProofTrees(){
        String text=cn.bidcheck.compat.Java8Api.repeat("前文", 3000)+QUOTE+cn.bidcheck.compat.Java8Api.repeat("后文", 3000);
        Rule r=new Rule("r","社保","MONTHS","项目负责人","socialInsurance","","","",cn.bidcheck.compat.Java8Api.mapOf("requirementSourceContext", cn.bidcheck.compat.Java8Api.mapOf("sources", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("kind", "HEADING", "sourceQuote", text, "proof", cn.bidcheck.compat.Java8Api.mapOf("recursive", text))))),QUOTE,"",cn.bidcheck.compat.Java8Api.listOf());
        Map<?,?> context=(Map<?,?>)AgentInvestigationScope.requestRules(cn.bidcheck.compat.Java8Api.listOf(r)).get(0).parameters().get("requirementContext");
        List<Map<String,Object>> excerpts=RuleEvaluator.maps(context.get("excerpts"));assertEquals(1,excerpts.size());Map<String,Object> excerpt=excerpts.get(0);
        assertEquals(true,excerpt.get("truncated"));assertEquals(4000,str(excerpt,"text").length());assertTrue(str(excerpt,"text").contains(QUOTE));
        assertEquals(text.substring(((Number)excerpt.get("start")).intValue(),((Number)excerpt.get("end")).intValue()),excerpt.get("text"));
        assertFalse(context.toString().contains("recursive"));
    }
}
