package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.verification.agent.AgentInvestigator;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceInvestigationTest {
    Checkpoint checkpoint(){return new Checkpoint("agent-cp","项目负责人","资格","HIGH","SEMANTIC_RESPONSE","","","本项目应配置项目负责人","",true,true,true,null);}
    Rule rule(){return new Rule("role","项目负责人任职","PERSON_ROLE","项目负责人","role","","","",Map.of(),"本项目应配置项目负责人","",List.of());}
    EvidenceInvestigation.Host host(String text){
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(SharedFactReuseTest.doc("bid","project",1,text)));Map<String,Object> ctx=new LinkedHashMap<>();
        return new EvidenceInvestigation.Host(checkpoint(),new Plan("ALL",List.of(rule()),"LOCAL",List.of()),List.of(rule()),corpus,new LinkedHashMap<>(),ctx,
            new SharedFactReuse(corpus,ctx,"test-model","test-engine"),new RunSession(ModelGateway.disabled(),ctx,"test"),null,new ArrayList<>(),Set.of());
    }
    EvidenceInvestigation.Host host(Plan plan,List<Rule> targets,String text){
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(SharedFactReuseTest.doc("bid","project",1,text)));Map<String,Object> ctx=new LinkedHashMap<>();
        return new EvidenceInvestigation.Host(checkpoint(),plan,targets,corpus,new LinkedHashMap<>(),ctx,
            new SharedFactReuse(corpus,ctx,"test-model","test-engine"),new RunSession(ModelGateway.disabled(),ctx,"test"),null,new ArrayList<>(),Set.of());
    }
    Map<String,Object> candidate(String source){return linked("kind","FIELD","subject","","field","person","rawValue","测试甲","value","测试甲","unit","","basis","","scope","UNRESOLVED","sourceIds",List.of(source),"attributes",Map.of());}
    @Test void inventedSourceCannotEnterFacts(){var h=host("拟投入本项目人员：测试甲");var result=h.call("submit_candidate_fact",Map.of("fact",candidate("invented")));assertEquals("REJECTED",result.get("status"));assertTrue(h.facts.isEmpty());}
    @Test void sourceMustHaveBeenDisclosedEvenWhenPresentInCorpus(){var h=host("拟投入本项目人员：测试甲");var result=h.call("submit_candidate_fact",Map.of("fact",candidate(h.corpus.all.get(0).id())));assertEquals("REJECTED",result.get("status"));}
    @Test void unrelatedFieldCannotPretendToBeTargetRoleEvidence(){var h=host("本项目人员说明：测试甲负责材料整理，尚未确定项目负责人。");h.call("search_evidence",Map.of("query","测试甲"));String id=h.disclosed.keySet().iterator().next();var result=h.call("submit_candidate_fact",Map.of("fact",candidate(id)));assertEquals("FACT_OUTSIDE_TARGET",result.get("error"));assertEquals(List.of(),result.get("acceptedFactIds"));assertEquals(false,h.call("recompute_check",Map.of()).get("businessCheckCompleted"));}
    @Test void duplicateCandidateIsNotProgress(){var h=host("拟投入本项目人员：测试甲");h.call("search_evidence",Map.of("query","测试甲"));var args=Map.<String,Object>of("fact",candidate(h.disclosed.keySet().iterator().next()));h.call("submit_candidate_fact",args);assertEquals(List.of(),h.call("submit_candidate_fact",args).get("acceptedFactIds"));}
    @Test void nativeAppointmentIsAcceptedAndRecomputed(){var h=host("本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");var read=h.call("search_evidence",Map.of("query","项目负责人"));assertFalse(((List<?>)read.get("acceptedFactIds")).isEmpty());assertEquals(true,h.call("recompute_check",Map.of()).get("businessCheckCompleted"),h.decision.toString());assertEquals("PASS",h.decision.status());}
    @Test void historicalAppointmentCannotBecomeCurrent(){var h=host("历史项目\n已完成项目：历史工程\n项目负责人：测试甲\n姓名：测试甲");h.call("search_evidence",Map.of("query","项目负责人"));String id=h.disclosed.keySet().iterator().next();Map<String,Object> fact=candidate(id);fact.putAll(linked("kind","PERSON_ROLE","subject","项目负责人","field","role","scope","CURRENT_PROJECT"));var result=h.call("submit_candidate_fact",Map.of("fact",fact));assertEquals("REJECTED",result.get("status"));assertNotEquals("PASS",h.recompute().get("businessDecision"));}
    @Test void readCanContinuePastFirstEighteenBlocks(){var h=host("本项目人员配置\n"+"资料测试甲。".repeat(10000));Source middle=h.corpus.all.get(22);h.disclosed.put(middle.id(),middle);var first=h.call("read_section",Map.of("sourceId",middle.id()));assertEquals(middle.id(),((List<?>)first.get("sourceIds")).get(0));assertTrue(first.containsKey("nextSourceId"));var next=h.call("read_section",Map.of("sourceId",first.get("nextSourceId")));assertNotEquals(first.get("sourceIds"),next.get("sourceIds"));}
    @Test void navigationHandleAloneDoesNotAuthorizeFact(){var h=host("测试甲。".repeat(10000));Source first=h.corpus.all.get(0);h.disclosed.put(first.id(),first);var read=h.call("read_section",Map.of("sourceId",first.id()));assertEquals("REJECTED",h.call("submit_candidate_fact",Map.of("fact",candidate((String)read.get("nextSourceId")))).get("status"));}
    @Test void otherVisualFieldCannotStopRequestedRegion(){var h=host("本项目授权委托书 测试甲");Source s=h.corpus.all.get(0);h.disclosed.put(s.id(),s);h.ctx.put("visualObservations",List.of(linked("documentId","bid","location",1,"locationType","PAGE","label","印章","value","有","source",Map.of("documentHash","hash-bid","documentVersion",1))));var result=h.call("inspect_page_region",Map.of("sourceId",s.id(),"field","授权期限"));assertEquals("PENDING",result.get("status"));assertEquals(1,h.visualRequests.size());assertEquals(false,result.get("observationProduced"));}
    @Test void arbitraryToolsFailWithoutMutation(){var h=host("测试甲");assertEquals("TOOL_NOT_ALLOWED",h.call("execute_sql",Map.of()).get("error"));assertTrue(h.facts.isEmpty());}
    @Test void toolSchemasRequireOnlyFieldsNeededByTheOperation(){
        Map<String,Map<String,Object>> tools=new HashMap<>();for(Map<String,Object> tool:EvidenceInvestigation.tools())tools.put(tool.get("name").toString(),tool);
        Map<String,Object> table=(Map<String,Object>)tools.get("read_table_record").get("parameters");assertEquals(List.of("sourceId"),table.get("required"));
        Map<String,Object> submit=(Map<String,Object>)tools.get("submit_candidate_fact").get("parameters");assertEquals(List.of("fact"),submit.get("required"));
        Map<String,Object> fact=(Map<String,Object>)((Map<String,Object>)submit.get("properties")).get("fact");
        assertEquals(List.of("kind","field","rawValue","sourceIds"),fact.get("required"));
        assertFalse(((List<?>)fact.get("required")).contains("subject"));assertFalse(((List<?>)fact.get("required")).contains("attributes"));
        Map<String,Object> recompute=(Map<String,Object>)tools.get("recompute_check").get("parameters");assertEquals(List.of(),recompute.get("required"));
    }
    @Test void typedSubmitRequiresTargetButKeepsFactMetadataOptional(){
        Map<String,Object> submit=EvidenceInvestigation.tools(List.of(rule())).stream().filter(t->"submit_candidate_fact".equals(t.get("name"))).findFirst().orElseThrow();
        Map<String,Object> schema=(Map<String,Object>)submit.get("parameters");assertEquals(List.of("fact","targetObligationId"),schema.get("required"));
        Map<String,Object> fact=(Map<String,Object>)((Map<String,Object>)schema.get("properties")).get("fact");assertFalse(((List<?>)fact.get("required")).contains("value"));
    }
    @Test void rawValueRejectionIncludesExecutableCorrectionWithoutFailingTheTool(){
        var h=host("本项目人员配置：项目负责人测试甲");h.call("search_evidence",Map.of("query","项目负责人"));String id=h.disclosed.keySet().iterator().next();
        Map<String,Object> invalid=candidate(id);invalid.put("rawValue","原文中不存在的改写内容");invalid.put("value","原文中不存在的改写内容");
        Map<String,Object> result=h.call("submit_candidate_fact",Map.of("targetObligationId","role","fact",invalid));
        assertEquals("REJECTED",result.get("status"));assertNotEquals("FAILED",result.get("status"));
        List<Map<String,Object>> hints=(List<Map<String,Object>>)result.get("correctionHints");
        assertTrue(hints.stream().anyMatch(hint->"RAW_VALUE_UNGROUNDED".equals(hint.get("code"))&&"/fact/rawValue".equals(hint.get("fieldPath"))&&"READ_SOURCE_THEN_COPY_EXACT_RAW_VALUE".equals(hint.get("action"))));
    }
    @Test void malformedFactIsCorrectableInsteadOfSystemFailure(){var h=host("测试甲");Map<String,Object> result=h.call("submit_candidate_fact",Map.of("targetObligationId","role"));assertEquals("REJECTED",result.get("status"));assertEquals(true,result.get("correctable"));assertEquals("/fact",result.get("fieldPath"));}
    @Test void noExternalRecordIsNotExternalQueryExecuted(){var h=host("测试甲");var result=h.call("query_external",Map.of("subject","测试甲"));assertEquals("PENDING",result.get("status"));assertEquals(false,result.get("newQueryExecuted"));}
    @Test void longTableHasAnExplicitContinuationWithoutDroppingItsTail(){var h=host("项目负责人 表格");Source source=h.corpus.all.get(0);h.disclosed.put(source.id(),source);List<Map<String,Object>> cells=new ArrayList<>();for(int i=0;i<91;i++)cells.add(linked("documentId","bid","documentHash","hash-bid","documentVersion",1,"spanId","cell-"+i,"locationType","PAGE","location",1,"kind","TABLE_CELL","text","第"+i+"行"));h.ctx.put("layoutLookup",(java.util.function.Function<Map<String,Object>,List<Map<String,Object>>>)q->cells);var first=h.call("read_table_record",Map.of("sourceId",source.id(),"rowOffset",0));assertEquals(true,first.get("partial"));var tail=h.call("read_table_record",Map.of("sourceId",source.id(),"rowOffset",first.get("nextRowOffset")));assertEquals(11,((List<?>)tail.get("rows")).size());assertEquals(false,tail.get("partial"));}
    @Test void numericTargetGetsGenericDomainAndStableServerPlanReceipt(){
        Rule number=new Rule("total-price","总报价","NUMBER","本项目","总报价","=","100","万元",Map.of(),"总报价应为100万元","",List.of());
        Plan plan=new Plan("ALL",List.of(number),"DETERMINISTIC",List.of(),List.of(),Map.of("status","RESOLVED","source",Map.of("page",8,"documentId","tender")));
        var h=host(plan,List.of(number),"投标总报价：100万元");Map<String,Object> first=(Map<String,Object>)h.start().get("context"),second=(Map<String,Object>)h.start().get("context");
        assertEquals(List.of("NUMERIC_AND_PRICING"),first.get("evidenceDomains"));assertFalse(first.get("domain").toString().contains("PERSONNEL"));
        assertEquals(first.get("serverPlanFingerprint"),second.get("serverPlanFingerprint"));assertEquals(first.get("serverPlanRevision"),second.get("serverPlanRevision"));
        Map<String,Object> requirement=(Map<String,Object>)first.get("requirement");assertEquals("ALL",requirement.get("logic"));assertEquals(false,first.get("ruleAuthoringAllowed"));
        Map<String,Object> receipt=h.call("recompute_check",Map.of());assertEquals(first.get("serverPlanRevision"),receipt.get("serverPlanRevision"));assertEquals(first.get("serverPlanFingerprint"),receipt.get("serverPlanFingerprint"));
    }
    @Test void contractTargetUsesContractDomainAndCompilerIdentityChangesPlanFingerprint(){
        Rule contract=new Rule("performance-contract","合同业绩","CONTRACT","投标人","合同名称","EXISTS","","",Map.of(),"应提供类似业绩合同","",List.of());
        Plan deterministic=new Plan("ALL",List.of(contract),"DETERMINISTIC",List.of());Plan repaired=new Plan("ALL",List.of(contract),"REPAIRED_SOURCE",List.of());
        Map<String,Object> first=(Map<String,Object>)host(deterministic,List.of(contract),"类似业绩合同：示例项目").start().get("context");
        Map<String,Object> changed=(Map<String,Object>)host(repaired,List.of(contract),"类似业绩合同：示例项目").start().get("context");
        assertEquals(List.of("CONTRACT_AND_PERFORMANCE"),first.get("evidenceDomains"));assertNotEquals(first.get("serverPlanFingerprint"),changed.get("serverPlanFingerprint"));
        Map<String,Object> identity=(Map<String,Object>)((Map<String,Object>)first.get("requirement")).get("sourceCompilerIdentity");assertTrue(identity.containsKey("compiler"));assertTrue(identity.containsKey("requirementScopeFingerprint"));
    }
    @Test void qualificationAlternativeKeepsNestedAnyTreeInsteadOfFlatteningTargets(){
        Rule certificate=new Rule("qualification-certificate","资格证书","CERTIFICATE","投标人","证书类别","=","建筑业企业资质","",Map.of(),"具有建筑业企业资质","",List.of());
        Rule performance=new Rule("qualification-performance","替代业绩","CONTRACT","投标人","合同名称","EXISTS","","",Map.of(),"或提供类似项目合同","",List.of());
        Rule alternative=new Rule("qualification-path","资格替代路径","","","","","","",Map.of(),"证书或类似项目合同","ANY",List.of(certificate,performance));
        Plan plan=new Plan("ALL",List.of(alternative),"DETERMINISTIC",List.of());
        Map<String,Object> context=(Map<String,Object>)host(plan,List.of(certificate,performance),"资格证明材料").start().get("context");
        Map<String,Object> requirement=(Map<String,Object>)context.get("requirement");List<Map<String,Object>> roots=(List<Map<String,Object>>)requirement.get("rules");
        assertEquals("ALL",requirement.get("logic"));assertEquals("ANY",roots.get(0).get("logic"));assertEquals(2,((List<?>)roots.get(0).get("children")).size());
        assertEquals(List.of("qualification-certificate","qualification-performance"),context.get("targetObligationIds"));
        assertEquals(Set.of("CONTRACT_AND_PERFORMANCE","QUALIFICATION_AND_VALIDITY"),new HashSet<>((List<String>)context.get("evidenceDomains")));
        assertEquals("EVIDENCE_NAVIGATION_AND_FACT_PROPOSAL_ONLY",context.get("agentAuthority"));assertEquals(false,context.get("judgmentAuthoringAllowed"));
    }
    @Test void sdkNeverReplacesWholeCheckOrBaseRun(){assertFalse(EvidenceInvestigation.eligible(checkpoint(),Map.of()));assertTrue(EvidenceInvestigation.eligible(checkpoint(),Map.of("recoveryStrategy","TARGETED_EVIDENCE")));assertTrue(EvidenceInvestigation.eligible(checkpoint(),Map.of("recoveryStrategy","RETRY_FAILED_STEP","previousAtomicCheck",Map.of("agentInvestigation",Map.of("status","FAILED")))));}
    @Test void engineActuallyInvokesSdkToolsAndRecomputes(){var result=engine(true);assertEquals("PASS",result.get("businessDecision"),result.toString());assertEquals("COMPLETED",((Map<?,?>)result.get("agentInvestigation")).get("status"));assertTrue((int)((Map<?,?>)result.get("agentInvestigation")).get("acceptedFactCount")>0);}
    @Test void modelSayingCompletedCannotCreateBusinessPass(){var result=engine(false);assertEquals("UNDETERMINED",result.get("businessDecision"));assertEquals(false,((Map<?,?>)result.get("agentInvestigation")).get("businessCheckCompleted"));}
    Map<String,Object> engine(boolean read){ObjectMapper json=new ObjectMapper();ModelGateway compiler=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "fixture";}public JsonNode request(String op,Map<String,Object> input){assertEquals("COMPILE",op);return json.valueToTree(Map.of("logic","ALL","rules",List.of(rule())));}};
        VerificationEngine engine=new VerificationEngine(compiler,null);engine.setInvestigator(new AgentInvestigator(){public boolean enabled(){return true;}public String identity(){return "sdk-fixture";}public Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> event){if(read)handler.call("search_evidence",Map.of("query","项目负责人"));handler.call("recompute_check",Map.of());return Map.of("status","COMPLETED","modelCalls",1,"toolCalls",read?2:1);}});
        return engine.evaluate(checkpoint(),List.of(SharedFactReuseTest.doc("bid","project",1,"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲")),null,Map.of("allowModelRuleCompilation",true,"recoveryStrategy","TARGETED_EVIDENCE"));}
}
