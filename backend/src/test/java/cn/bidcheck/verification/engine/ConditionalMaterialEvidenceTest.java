package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ConditionalMaterialEvidenceTest {
    private static final String PROJECT="北方新城综合楼二次装修工程",COMPANY="华北建设有限公司",DESIGNER="星河设计有限公司";
    private static final String JOINT="联合体协议书（如有）",AUTH="法定代表人（负责人）授权委托书",EXEMPT="法定代表人直接签署投标文件时无需提交";
    private static final String AUTH_SOURCE="《"+AUTH+"》（"+EXEMPT+"）";
    private Document tender(boolean auth){return new Document("t","p","TENDER","招标文件",1,"th","",List.of(new Page(1,PROJECT,"BLOCK","TEXT",null),new Page(2,auth?AUTH:JOINT,"BLOCK","TEXT",null),new Page(3,auth?AUTH_SOURCE:"其他要求","BLOCK","TEXT",null)),List.of());}
    private Checkpoint checkpoint(boolean auth){String quote=auth?AUTH:JOINT,expected=quote+"；按招标文件确认适用条件"+(auth?"。"+EXEMPT:"");return new Checkpoint("cp","适用时提供："+quote,"材料","HIGH","SEMANTIC_RESPONSE","conditionalMaterial","RESPONDS",expected,"",false,true,true,new Evidence("t","招标文件",2,"BLOCK",quote,"p2",1));}
    private Document bid(String... bodies){List<Page> pages=new ArrayList<>();pages.add(new Page(1,"投标文件\n（"+PROJECT+"）\n投标人："+COMPANY,"PAGE","TEXT",null));for(int i=0;i<bodies.length;i++)pages.add(new Page(7+i,bodies[i],"PAGE","TEXT",null));return new Document("b","p","BID","投标文件",1,"bh","",pages,List.of());}
    private String agreement(String project){return "6.联合体协议书\n"+COMPANY+"、"+DESIGNER+"[所有成员单位名称]自愿组成：联合体，共同投标"+project+"/标段。现就联合体应答事宜订立如下协议：\n共同承担合同责任，并对招标人承担连带责任。";}
    private String authorization(String project,String principal,String agent){return "8.法定代表人（负责人）授权委托书\n本人"+principal+"系 "+COMPANY+"的法定代表人（负责人），现委托"+agent+"为我方代理人。代理人根据授权，以我方名义全权处理["+project+"]签署、澄清、递交投标文件，其法律后果由我方承担。\n法定代表人（签字）：\n委托代理人（签字）：\n编制及签署要求：代理人应签字。";}
    private Rule rule(boolean auth)throws Exception{return plan(checkpoint(auth),tender(auth)).rules().get(0);}
    private Plan plan(Checkpoint cp,Document tender)throws Exception{RunSession session=new RunSession(ModelGateway.disabled(),Map.of("originalRequirement",cp.source().quote(),"tenderDocuments",List.of(tender),"requirementProjectId","p","focusedCheck",true),"conditional-material-test");Plan plan=new RequirementCompiler().compile(cp,session);assertTrue(plan.issues().isEmpty(),plan.toString());assertEquals(0,session.modelCalls);assertEquals(1,plan.rules().size(),plan.toString());assertTrue(ConditionalMaterialEvidence.applies(plan.rules().get(0)),plan.toString());return plan;}
    private Map<String,Object> engine(boolean auth,Document bid,Map<String,Object> context){
        ModelGateway model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "must-remain-local";}public com.fasterxml.jackson.databind.JsonNode request(String op,Map<String,Object> input){throw new AssertionError("deterministic material must not call "+op);}};
        Map<String,Object> result=new VerificationEngine(model,null).evaluate(checkpoint(auth),List.of(bid),tender(auth),context);
        assertEquals(0,((Map<?,?>)result.get("metrics")).get("modelCalls"));
        assertTrue(RuleEvaluator.maps(result.get("steps")).stream().anyMatch(s->"PROGRAM_SOURCE_READ".equals(s.get("type"))),result.toString());return result;
    }
    private Decision evaluate(boolean auth,Document bid)throws Exception{Map<String,Object> result=engine(auth,bid,Map.of());List<Map<String,Object>> obligations=RuleEvaluator.maps(result.get("obligations"));assertEquals(1,obligations.size(),result.toString());return new ObjectMapper().convertValue(obligations.get(0),Decision.class);}

    @Test void actualConsortiumBodyPassesOnlySubmissionScope()throws Exception{Decision decision=evaluate(false,bid(agreement(PROJECT)));assertEquals("PASS",decision.status(),decision.toString());assertEquals("CONDITIONAL_MATERIAL_SUBMISSION_ONLY",decision.proof().get("proofScope"));assertEquals(true,decision.proof().get("doesNotProveSignatureOrAuthorityValidity"));assertEquals(7,decision.evidences().get(0).page());}
    @Test void actualFilledAuthorizationIsSubmissionWithoutInferringSignedValidity()throws Exception{
        Document document=bid(authorization(PROJECT,"张明","李强"));EvidenceCorpus corpus=new EvidenceCorpus(List.of(document));
        Rule bidder=new Rule("bidder-fixture","独立投标人主体","RELATION","投标人","名称","=","","",Map.of(),"投标人","",List.of());
        var parties=EnterpriseSubjectBindings.extract(List.of(bidder),corpus.all);assertEquals(List.of(COMPANY),parties.stream().map(VerificationTypes.Fact::value).distinct().toList(),"authorization submission requires a separately source-bound current bidder");
        assertTrue(parties.stream().allMatch(f->f.sourceIds().stream().map(corpus.byId::get).allMatch(s->s.evidence().page()==1)));
        Decision decision=evaluate(true,document);assertEquals("PASS",decision.status(),decision.toString());assertEquals(true,decision.proof().get("doesNotProveSignatureOrAuthorityValidity"));
    }
    @Test void sourceConditionCanBeRestoredAcrossBlocksWithoutLosingOriginalExpected()throws Exception{Plan plan=plan(checkpoint(true),tender(true));Rule rule=plan.rules().get(0);assertEquals(AUTH_SOURCE,rule.sourceQuote());assertEquals(checkpoint(true).expected(),rule.parameters().get("reviewedExpected"));assertEquals("BOOK_CONDITION_SOURCE_RESTORED",plan.requirementScope().get("status"));}
    @Test void missingMaterialsAndPrintedSignerNamesNeverProveInapplicability()throws Exception{for(boolean auth:List.of(false,true)){Decision result=evaluate(auth,bid("投标函\n（"+PROJECT+"）投标文件\n投标人："+COMPANY+"。\n法定代表人：张明。"));assertEquals("UNDETERMINED",result.status(),result.toString());assertEquals(auth?"AUTHORIZATION_OR_DIRECT_SIGNER_PROOF_REQUIRED":"CONSORTIUM_APPLICABILITY_PROOF_REQUIRED",result.gaps().get(0).code());}}
    @Test void directoryBlankTemplateForeignProjectAndUnknownPartiesCannotPass()throws Exception{
        assertEquals("UNDETERMINED",evaluate(false,bid("目录\n联合体协议书......7\n授权委托书......8\n"+PROJECT)).status());
        assertEquals("UNDETERMINED",evaluate(false,bid(agreement("南方历史项目装修工程"))).status());
        assertEquals("UNDETERMINED",evaluate(true,bid(authorization(PROJECT,"____","____"))).status());
        assertEquals("UNDETERMINED",evaluate(true,bid(authorization("南方历史项目装修工程","张明","李强"))).status());
        assertEquals("UNDETERMINED",evaluate(true,bid(authorization(PROJECT,"张明","李强").replace(COMPANY,"不相关建设有限公司"))).status());
        assertEquals("UNDETERMINED",evaluate(false,bid("6.联合体协议书\n项目名称："+PROJECT+"\n甲方、乙方共同投标，订立如下协议，承担连带责任。")).status());
    }
    @Test void onlyExplicitCurrentNonJointDeclarationCanDeactivateOptionalMaterial()throws Exception{
        String declaration="投标函\n（"+PROJECT+"）投标文件\n投标人："+COMPANY+"。\n本次投标不采用联合体形式。";
        Decision result=evaluate(false,bid(declaration));assertEquals("NOT_APPLICABLE",result.status(),result.toString());assertFalse(result.evidences().isEmpty());
        assertEquals("UNDETERMINED",evaluate(false,bid(declaration.replace("本次投标不采用","如本次投标不采用"))).status());
        assertNotEquals("NOT_APPLICABLE",evaluate(false,bid(declaration,"投标函\n（"+PROJECT+"）投标文件\n我方采用联合体形式投标。\n投标人："+COMPANY+"。" )).status());
    }
    @Test void frozenRequirementChangesInvalidateExemptionAndBodyReceipt()throws Exception{
        Rule rule=rule(true);Document old=tender(true);Document changed=new Document(old.id(),old.projectId(),old.role(),old.name(),2,"changed","",old.pages(),List.of());Decision result=new RuleEvaluator(List.of(),new EvidenceCorpus(List.of(bid(authorization(PROJECT,"张明","李强")))),Map.of("tenderDocuments",List.of(changed))).evaluate(rule);assertEquals("UNDETERMINED",result.status());assertEquals("CONDITIONAL_MATERIAL_SOURCE_REQUIRED",result.gaps().get(0).code());
    }
    @Test void manualReviewEditsAndAdditionalObligationsAreNotDropped(){
        Checkpoint original=checkpoint(false);Checkpoint edited=new Checkpoint(original.id(),original.title(),original.category(),original.severity(),original.type(),original.field(),original.operator(),original.expected()+"；且各成员须有安全生产许可证",original.unit(),original.required(),original.confirmed(),original.enabled(),original.source());
        RequirementSourceContext.Context source=RequirementSourceContext.resolve(original,JOINT,Map.of("tenderDocuments",List.of(tender(false))));
        assertNull(ConditionalMaterialEvidence.compile(edited,JOINT,source,Map.of()));
        assertNull(ConditionalMaterialEvidence.compile(original,"本项目接受联合体投标。联合体成员不得超过2家。",source,Map.of()));
        assertNull(ConditionalMaterialEvidence.compile(original,"联合体协议书",source,Map.of()));
    }
    @Test void localRecoveryReReadsBodyInsteadOfReplayingAnUnfinishedModelFieldReceipt()throws Exception{
        Plan plan=plan(checkpoint(false),tender(false));Rule rule=plan.rules().get(0);String key=cn.bidcheck.verification.facts.FieldCoverage.key(SharedFactReuse.observationTarget(rule));
        Map<String,Object> previous=linked("plan",plan,"coverageDependencies",List.of(linked("obligationId",rule.id(),"observationKey",key,"gapCode","SHARED_SOURCE_REVIEW_REQUIRED","sourceIds",List.of("old-missing-model-window"))),"obligations",List.of(linked("id",rule.id(),"status","UNDETERMINED","gaps",List.of(gap("SHARED_SOURCE_REVIEW_REQUIRED","旧模型读取尚未完成").map()))));
        Map<String,Object> result=engine(false,bid(agreement(PROJECT)),Map.of("previousAtomicCheck",previous,"recoveryMode","EVIDENCE_RECOMPUTE","recoveryModelAllowed",false));
        assertEquals("PASS",result.get("businessDecision"),result.toString());assertTrue(RuleEvaluator.maps(result.get("coverageDependencies")).isEmpty());assertEquals("PROGRAM_SOURCE_SCAN_FINISHED",((Map<?,?>)result.get("metrics")).get("stopReason"));
    }
    @Test void sourceLocalClosureRequiresFreshFrozenBodyAndExactProof()throws Exception{
        Rule rule=rule(false);RuleEvaluator current=new RuleEvaluator(List.of(),new EvidenceCorpus(List.of(bid(agreement(PROJECT)))),Map.of("tenderDocuments",List.of(tender(false))));Decision decision=ConditionalMaterialEvidence.evaluate(rule,current);assertTrue(SourceLocalExistenceClosure.proven(rule,decision,current),decision.toString());
        RuleEvaluator changed=new RuleEvaluator(List.of(),new EvidenceCorpus(List.of(bid())),Map.of("tenderDocuments",List.of(tender(false))));assertFalse(SourceLocalExistenceClosure.proven(rule,decision,changed));
        Decision forged=new Decision(decision.id(),decision.description(),decision.status(),decision.reason(),decision.evidences(),List.of(),Map.of("contentPresent",true));assertFalse(SourceLocalExistenceClosure.proven(rule,forged,current));
    }
}
