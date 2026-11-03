package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementStructureRefactorTest {
    private static final String DESIGN="设计单位资质要求：须满足下列条件之一：①住建主管部门颁发的工程设计综合资质甲级。②住建主管部门颁发的工程设计建筑行业乙级（含）以上资质或工程设计建筑工程专业乙级（含）以上资质或建筑装饰工程设计专项丙级（含）以上资质。";
    private static final String PROOF="须提交有效证明文件（并提供查询网址及网址查询截图，查询截图信息应与证明文件信息一致）。";
    private static final ModelGateway OFFLINE=new ModelGateway(){public boolean enabled(){return false;}public String identity(){return "offline";}public JsonNode request(String operation,Map<String,Object> input){throw new AssertionError("source compilation must not call a model");}};
    private Checkpoint checkpoint(String source){return new Checkpoint("test","资格要求","资格","HIGH","SEMANTIC_RESPONSE","qualification","",source,"",true,true,true,new Evidence("tender","招标",1,"BLOCK",source,"p1",1));}
    private Document document(String... sources){List<Page> pages=new ArrayList<>();for(int i=0;i<sources.length;i++)pages.add(new Page(i+1,sources[i],"BLOCK","NATIVE",""));return new Document("tender","project","TENDER","招标",1,"frozen-hash","",pages,cn.bidcheck.compat.Java8Api.listOf());}
    private Plan compile(String source,String... extra)throws Exception{List<String> blocks=new ArrayList<>();blocks.add(source);blocks.addAll(cn.bidcheck.compat.Java8Api.listOf(extra));return new RequirementCompiler().compile(checkpoint(source),new RunSession(OFFLINE,cn.bidcheck.compat.Java8Api.mapOf("originalRequirement", source, "tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(document(blocks.toArray(new String[0]))), "requirementProjectId", "project"),"offline"));}
    private List<Rule> leaves(Plan plan){return RequirementCompiler.leaves(plan.rules());}
    private List<Rule> all(List<Rule> roots){List<Rule> result=new ArrayList<>();for(Rule r:roots){result.add(r);result.addAll(all(r.children()));}return result;}

    @Test void numberedAlternativeRetainsActorBranchesAndEachOwnGrade()throws Exception{
        Plan plan=compile(DESIGN);assertTrue(plan.issues().isEmpty(),plan.issues().toString());
        List<Rule> leaves=leaves(plan);assertEquals(4,leaves.size(),plan.toString());
        assertTrue(leaves.stream().allMatch(r->r.predicate().equals("CERTIFICATE")),plan.toString());
        assertTrue(leaves.stream().allMatch(r->r.subject().equals("设计单位")),plan.toString());
        assertTrue(all(plan.rules()).stream().anyMatch(r->r.logic().equals("ANY")&&r.children().size()==4));
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("工程设计综合资质", "工程设计建筑行业", "工程设计建筑工程专业", "建筑装饰工程设计专项"),new HashSet<>(cn.bidcheck.compat.Java8Api.toList(leaves.stream().map(r->str(r.parameters(),"category")))));
        assertTrue(leaves.stream().filter(r->str(r.parameters(),"grade").equals("乙级")).allMatch(r->str(r.parameters(),"gradeOperator").equals(">=")));
        assertEquals("=",str(leaves.get(0).parameters(),"gradeOperator"));
        for(Rule leaf:leaves){assertTrue(DESIGN.contains(leaf.sourceQuote()));assertNotNull(RequirementContextBindings.validatedReference(leaf,"subject","设计单位",DESIGN));}
    }
    @Test void commonProofRemainsConjunctiveOutsideTheAlternative()throws Exception{
        Plan plan=compile(DESIGN+PROOF);assertTrue(plan.issues().isEmpty(),plan.issues().toString());
        Rule root=plan.rules().get(0);assertEquals("ALL",root.logic());assertEquals("ANY",root.children().get(0).logic());
        assertEquals(4,root.children().get(0).children().size());
        assertEquals(3,leaves(plan).stream().filter(r->r.predicate().equals("MATERIAL")).count());
        assertTrue(root.children().stream().anyMatch(r->r.predicate().equals("SAME_FIELD")),plan.toString());
    }
    @Test void numberedChapterAndTableHeadersKeepExactParentSource()throws Exception{
        for(String prefix:cn.bidcheck.compat.Java8Api.listOf("2.2.1", "2．2．1 ", "资质要求\t（1）", "（一）")){
            String source=prefix+DESIGN.replace("：①","：\n①")+PROOF;Plan plan=compile(source);
            assertTrue(plan.issues().isEmpty(),prefix+plan.issues());
            assertEquals(4,leaves(plan).stream().filter(r->r.predicate().equals("CERTIFICATE")).count(),prefix+plan);
            assertEquals(source,plan.rules().get(0).sourceQuote());
            for(Rule leaf:leaves(plan))assertTrue(source.contains(leaf.sourceQuote()),leaf.sourceQuote());
        }
    }
    @Test void unresolvedReplacementIsAnAlternativeAndCannotEraseTheKnownBranches()throws Exception{
        String replacement="或根据《建设工程企业资质管理制度改革方案通知》的规定已换发新证取得相应资质的投标人。";
        Plan plan=compile(DESIGN+replacement+PROOF);
        Rule choice=all(plan.rules()).stream().filter(r->r.logic().equals("ANY")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals(5,choice.children().size());assertEquals(4,choice.children().stream().filter(r->r.predicate().equals("CERTIFICATE")).count());
        assertEquals(1,choice.children().stream().filter(r->r.predicate().equals("UNSUPPORTED")).count());
        assertTrue(choice.children().stream().filter(r->r.predicate().equals("UNSUPPORTED")).allMatch(r->str(r.parameters(),"deterministicFamily").equals("QUALIFICATION_REPLACEMENT_MAPPING_REQUIRED")));
    }
    @Test void unknownAlternativeAndUnknownCommonConditionAreNotSilentlyDropped()throws Exception{
        String source="设计单位资质要求：须满足下列条件之一：①工程设计综合资质甲级。②特殊专项认可。须提交有效证明文件并达到另行规定的特别条件。";
        Plan plan=compile(source);assertTrue(leaves(plan).stream().anyMatch(r->r.predicate().equals("UNSUPPORTED")&&r.sourceQuote().contains("特殊专项认可")));
        assertTrue(leaves(plan).stream().anyMatch(r->r.predicate().equals("UNSUPPORTED")&&r.sourceQuote().contains("特别条件")));
    }
    @Test void conditionalOrUnnumberedChoiceDoesNotUseTheClosedNumberedParser(){
        assertTrue((!QualificationRequirementTree.parse("如果采用联合体，"+DESIGN).isPresent()));
        assertTrue((!QualificationRequirementTree.parse(DESIGN.replace("①","").replace("②","")).isPresent()));
        assertTrue((!QualificationRequirementTree.parse(DESIGN+"须具有营业执照。"+PROOF).isPresent()),"independent mandatory obligations cannot become an optional branch");
        assertTrue((!QualificationRequirementTree.parse(DESIGN+"同时应符合其他强制条件。").isPresent()));
        assertTrue((!QualificationRequirementTree.parse(DESIGN+"业绩要求：具有1个类似工程合同。").isPresent()),"unnumbered mandatory heading cannot become an optional certificate");
        assertTrue((!QualificationRequirementTree.parse("设计单位资质要求：须满足下列条件之一：①工程设计综合资质甲级。②工程设计建筑行业乙级并须提交有效证明文件。").isPresent()),"branch-specific proof must not be promoted to a common requirement");
        assertTrue((!QualificationRequirementTree.parse(DESIGN.replace("甲级。②","甲级。（1）应提供额外证明。②")).isPresent()),"nested numbered proof is not an extra alternative");
        assertTrue((!QualificationRequirementTree.parse(DESIGN.replace("②","③")).isPresent()),"missing numbered branch cannot be silently skipped");
    }
    @Test void structuralHeadingExemptionRequiresEveryOriginalBranchAndExactAnyLogic()throws Exception{
        String source="2.2.1"+DESIGN.replace("：①","：\n①");Plan plan=compile(source);Rule choice=plan.rules().get(0);
        assertTrue(RequirementCoverage.issues(source,plan.rules()).isEmpty());
        for(Rule changed:cn.bidcheck.compat.Java8Api.listOf(new Rule(choice.id(),choice.description(),choice.predicate(),choice.subject(),choice.field(),choice.operator(),choice.expected(),choice.unit(),choice.parameters(),choice.sourceQuote(),"ALL",choice.children()), new Rule(choice.id(),choice.description(),choice.predicate(),choice.subject(),choice.field(),choice.operator(),choice.expected(),choice.unit(),choice.parameters(),choice.sourceQuote(),"ANY",choice.children().subList(0,choice.children().size()-1)))){
            assertTrue(RequirementCoverage.issues(source,cn.bidcheck.compat.Java8Api.listOf(changed)).stream().anyMatch(issue->issue.contains("2.2.1设计单位资质要求")),"missing or changed choice structure must not exempt its heading");
        }
    }
    @Test void monthsAndContractCountsNeverBecomePersonnelCounts()throws Exception{
        for(String source:cn.bidcheck.compat.Java8Api.listOf("项目负责人须提供连续三个月社保证明。", "项目负责人具有1个类似工程业绩得2分，最高得4分。")){
            Plan plan=compile(source);assertTrue(leaves(plan).stream().noneMatch(r->r.predicate().equals("PERSON_COUNT")||"PERSONNEL_ROSTER".equals(r.parameters().get("templateId"))),plan.toString());
            assertFalse(leaves(plan).isEmpty());
        }
        assertTrue(leaves(compile("项目负责人须提供连续三个月社保证明。")).stream().anyMatch(r->r.predicate().equals("MONTHS")));
        assertTrue(leaves(compile("项目负责人不少于2人。")).stream().anyMatch(r->r.predicate().equals("PERSON_COUNT")));
    }
    @Test void prohibitedRelationshipDoesNotBecomeLegalRegistration()throws Exception{
        Plan plan=compile("与招标人存在利害关系的法人或其他组织不得投标。");
        assertTrue(leaves(plan).stream().noneMatch(r->"LEGAL_REGISTRATION".equals(r.parameters().get("deterministicFamily"))),plan.toString());
        assertTrue(leaves(plan).stream().anyMatch(r->r.predicate().equals("UNSUPPORTED")));
    }
    @Test void broadReviewReferenceIsADependencyAndNotCopiedChapterText()throws Exception{
        String source="投标报价唯一且有效，符合3.1初步评审要求。";
        Plan plan=compile(source,"3.1 初步评审","3.1.1 投标人不得在黑名单内。","3.1.2 项目负责人须提供资格证书。","3.2 详细评审");
        assertTrue(leaves(plan).stream().anyMatch(r->r.predicate().equals("SAME_FIELD")),plan.toString());
        Rule dependency=leaves(plan).stream().filter(r->SourceRequirementInventory.applies(r)&&"SUMMARY".equals(r.parameters().get("inventoryKind"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertTrue(dependency.parameters().get("referenceDependencies") instanceof Collection<?>&&!((Collection<?>) (dependency.parameters().get("referenceDependencies"))).isEmpty(),dependency.toString());
        Decision pending=SourceRequirementInventory.evaluate(dependency,new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.mapOf()));
        assertEquals("UNDETERMINED",pending.status(),"resolved reference text alone must not prove its child obligations");
        assertFalse(pending.gaps().isEmpty());
        assertTrue(leaves(plan).stream().noneMatch(r->r.sourceQuote().contains("黑名单")||r.sourceQuote().contains("项目负责人")),plan.toString());
        assertTrue(plan.requirementScope().toString().contains("SUMMARY_OF"));
    }
    @Test void specificClauseStillExpandsItsExactSourceAndPreservesMissingReference()throws Exception{
        String source="投标报价应符合3.1.2条款规定。";
        Plan plan=compile(source,"3.1.2 投标报价不超过100万元。","3.1.3 应提供营业执照。");
        assertTrue(leaves(plan).stream().anyMatch(r->r.predicate().equals("NUMBER")&&r.expected().equals("100")),plan.toString());
        assertTrue(leaves(plan).stream().noneMatch(r->r.sourceQuote().contains("营业执照")));
        assertTrue(compile(source).issues().stream().anyMatch(i->i.contains("未定位到引用条款正文")));
    }
}
