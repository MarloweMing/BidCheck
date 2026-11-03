package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class CompilationRecoveryTest {
    final ObjectMapper json=new ObjectMapper();
    Checkpoint cp(String text){return new Checkpoint("c","条件","资格","HIGH","SEMANTIC_RESPONSE","","",text,"",true,true,true,null);}
    Map<String,Object> node(String id,String predicate,String quote){return linked("id",id,"predicate",predicate,"description",quote,"sourceQuote",quote,"parameters",cn.bidcheck.compat.Java8Api.mapOf());}
    Map<String,Object> with(Map<String,Object> base,Object... fields){Map<String,Object> copy=new LinkedHashMap<>(base);for(int i=0;i<fields.length;i+=2)copy.put(fields[i].toString(),fields[i+1]);return copy;}
    JsonNode tree(String logic,Map<String,Object>... rules){return json.valueToTree(linked("logic",logic,"rules",cn.bidcheck.compat.Java8Api.listOf(rules)));}
    Plan parse(String text,JsonNode response){return new RequirementCompiler().compileResponse(cp(text),response,text,true,cn.bidcheck.compat.Java8Api.setOf("PERSONNEL_CONSORTIUM"));}
    ModelGateway gateway(java.util.function.BiFunction<String,Map<String,Object>,JsonNode> fn){return new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "compiled-repair-test";}public JsonNode request(String op,Map<String,Object> input){return fn.apply(op,input);}};}

    @Test void registrationAndOrAliasesRetainAlternativeMaterialAndOwnValidity(){
        String text="投标人须提交有效的营业执照或登记证书。";
        Map<String,Object> a=with(node("a","MATERIAL","有效的营业执照"),"logic","AND","children",cn.bidcheck.compat.Java8Api.listOf(node("av","VALIDITY","有效的营业执照")));
        Map<String,Object> b=with(node("b","MATERIAL","登记证书"),"expected","登记证书");
        Plan plan=parse(text,tree("AND",with(node("choice","GROUP",text),"logic","OR","children",cn.bidcheck.compat.Java8Api.listOf(a, b))));
        assertEquals("ALL",plan.logic());assertEquals("ANY",plan.rules().get(0).logic());
        assertEquals("ALL",plan.rules().get(0).children().get(0).logic());
        assertEquals("MATERIAL",plan.rules().get(0).children().get(0).predicate());
        assertTrue(plan.issues().isEmpty(),plan.issues().toString());
        assertEquals("UNDETERMINED",new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.mapOf()).evaluate(plan.rules().get(0)).status());
    }
    @Test void unknownLogicIsNotReinterpretedAsAnAlias(){String text="提交承诺书。";Plan p=parse(text,tree("ALL",with(node("r","GROUP",text),"logic","XOR","children",cn.bidcheck.compat.Java8Api.listOf(node("a","MATERIAL",text)))));assertFalse(p.issues().isEmpty());assertEquals("UNSUPPORTED",p.rules().get(0).predicate());}
    @Test void focusedRoutingKeepsSealAndCompanyComparisonWithoutPriceDomain(){
        String text="甲公司须盖章；甲公司名称与证明一致。";
        Plan plan=parse(text,tree("ALL",with(node("seal","VISUAL","甲公司须盖章"),"subject","甲公司","field","seal"),with(node("same","SAME_FIELD","甲公司名称与证明一致。"),"subject","甲公司","field","名称")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("VISUAL", "SAME_FIELD"),cn.bidcheck.compat.Java8Api.toList(plan.rules().stream().map(Rule::predicate)));assertTrue(plan.issues().isEmpty(),plan.issues().toString());
        assertEquals("VISUAL_OBSERVATION",CompilationRecovery.executor(plan.rules().get(0).predicate()));
        assertEquals("UNDETERMINED",new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.mapOf()).evaluate(plan.rules().get(0)).status());
    }
    @Test void sourceAndSubjectGuardStillRejectInventedCompanyAfterRoutingExpansion(){String text="须盖章。";Plan p=parse(text,tree("ALL",with(node("s","VISUAL",text),"subject","甲公司")));assertEquals("UNSUPPORTED",p.rules().get(0).predicate());}
    @Test void repairReceivesExactErrorsAndCannotBeMistakenForFirstSuccessfulTransport() throws Exception {
        String text="须提交营业执照。";AtomicInteger calls=new AtomicInteger();List<Map<String,Object>> requests=new ArrayList<>();
        ModelGateway model=gateway((op,in)->{calls.incrementAndGet();requests.add(in);return tree("ALL",node("r",in.containsKey("validationErrors")?"MATERIAL":"BAD_MATERIAL",text));});
        RunSession session=new RunSession(model,cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true, "allowModelRuleCompilation", true),"scope");Plan plan=new RequirementCompiler().compile(cp(text),session);
        assertEquals(2,calls.get());assertEquals("MATERIAL",plan.rules().get(0).predicate());assertTrue(plan.issues().isEmpty());
        assertEquals("REPAIR_COMPILED_REQUIREMENT",requests.get(1).get("task"));assertFalse(((List<?>)requests.get(1).get("validationErrors")).isEmpty());
        assertTrue(session.steps.stream().anyMatch(s->s.get("status").equals("VALIDATION_FAILED")));
        assertEquals("VALIDATED",session.compilationAudit.get("state"));
        RunSession resumed=new RunSession(model,cn.bidcheck.compat.Java8Api.mapOf("priorSteps", session.steps, "unlimitedModelCalls", true, "allowModelRuleCompilation", true),"scope");new RequirementCompiler().compile(cp(text),resumed);
        assertEquals(2,calls.get(),"Resume must reuse the repaired, validated plan rather than repeat its invalid first response");
    }
    @Test void repairCannotDeleteAnotherRequiredObligationOrReplaceBusinessWithReferenceDate() throws Exception {
        String source="提交甲证书；提交乙证书。";AtomicInteger calls=new AtomicInteger();
        ModelGateway model=gateway((op,in)->{calls.incrementAndGet();return in.containsKey("validationErrors")?tree("ALL",node("a","REFERENCE_TIME","提交甲证书")):tree("ALL",node("a","MATERIAL","提交甲证书"),node("b","BAD_MATERIAL","提交乙证书。"));});
        RunSession session=new RunSession(model,cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true, "allowModelRuleCompilation", true),"scope");Plan plan=new RequirementCompiler().compile(cp(source),session);
        assertEquals(2,calls.get());assertEquals(2,plan.rules().size());assertEquals("MATERIAL",plan.rules().get(0).predicate());assertEquals("UNSUPPORTED",plan.rules().get(1).predicate());
        assertEquals("UNRESOLVED",session.compilationAudit.get("state"));assertFalse(session.steps.stream().anyMatch(s->s.get("type").equals("COMPILED_PLAN")));
    }
    @Test void malformedSameResponseStopsAfterOneTargetedRepair() throws Exception {
        String source="提交甲证书。";AtomicInteger calls=new AtomicInteger();ModelGateway model=gateway((op,in)->{calls.incrementAndGet();return tree("ALL",node("a","BAD_MATERIAL",source));});
        RunSession session=new RunSession(model,cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true, "allowModelRuleCompilation", true),"scope");new RequirementCompiler().compile(cp(source),session);
        assertEquals(2,calls.get());assertEquals("UNRESOLVED",session.compilationAudit.get("state"));
    }
    @Test void receivedOrLegacyCompletedCompilerResponseIsNeverAValidatedCacheHit() throws Exception {
        AtomicInteger calls=new AtomicInteger();ModelGateway model=gateway((op,in)->{calls.incrementAndGet();return tree("ALL",node("a","BAD","材料"));});
        RunSession first=new RunSession(model,cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true),"scope");first.model("COMPILE",cn.bidcheck.compat.Java8Api.mapOf("requirement", "材料"));
        assertEquals("RECEIVED",first.steps.get(first.steps.size()-1).get("status"));
        Map<String,Object> legacy=new LinkedHashMap<>(first.steps.get(first.steps.size()-1));legacy.put("status","COMPLETED");
        RunSession second=new RunSession(model,cn.bidcheck.compat.Java8Api.mapOf("priorSteps", cn.bidcheck.compat.Java8Api.listOf(legacy), "unlimitedModelCalls", true),"scope");second.model("COMPILE",cn.bidcheck.compat.Java8Api.mapOf("requirement", "材料"));
        assertEquals(2,calls.get());assertEquals(0,second.resumedSteps);
    }
    @Test void recoveryGapHintsOnlyAffectTheirOwnRetrievalAndNeverAlterSource(){
        Rule a=parse("甲公司须盖章。",tree("ALL",node("a","VISUAL","甲公司须盖章。"))).rules().get(0);
        Rule b=parse("乙公司须盖章。",tree("ALL",node("b","VISUAL","乙公司须盖章。"))).rules().get(0);
        List<Rule> result=ObligationRecovery.retrievalRules(cn.bidcheck.compat.Java8Api.listOf(a, b),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("obligationId", "a", "need", "授权书落款盖章位置")));
        assertTrue(result.get(0).description().contains("授权书落款"));assertEquals(a.sourceQuote(),result.get(0).sourceQuote());assertEquals(b,result.get(1));
    }
    @Test void repairCannotTurnARequiredConjunctionIntoAnAlternative(){
        String source="提交甲证书；提交乙证书。";
        Plan original=parse(source,tree("ALL",with(node("group","GROUP",source),"logic","ALL","children",cn.bidcheck.compat.Java8Api.listOf(node("a","MATERIAL","提交甲证书"), node("b","BAD","提交乙证书。")))));
        Plan changed=parse(source,tree("ALL",with(node("group","GROUP",source),"logic","ANY","children",cn.bidcheck.compat.Java8Api.listOf(node("a","MATERIAL","提交甲证书"), node("b","MATERIAL","提交乙证书。")))));
        assertTrue(CompilationRecovery.repairIssues(original,changed).stream().anyMatch(x->x.contains("分支逻辑")));
    }
    @Test void repairCannotReverseConditionalPathsOrReplaceAnOwnValidityPredicate(){
        Rule condition=new Rule("if","适用性","INPUT","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"若适用","",cn.bidcheck.compat.Java8Api.listOf());
        Rule required=new Rule("yes","证照有效","UNSUPPORTED","甲","","","","",cn.bidcheck.compat.Java8Api.mapOf("originalPredicate", "VALIDITY"),"甲证照须有效","",cn.bidcheck.compat.Java8Api.listOf());
        Rule other=new Rule("no","另项材料","MATERIAL","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"其他情形须提交说明","",cn.bidcheck.compat.Java8Api.listOf());
        Rule parent=new Rule("p","条件","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"若适用甲证照须有效，其他情形须提交说明","CONDITIONAL",cn.bidcheck.compat.Java8Api.listOf(condition, required, other));
        Rule swapped=new Rule(parent.id(),parent.description(),parent.predicate(),"","","","","",cn.bidcheck.compat.Java8Api.mapOf(),parent.sourceQuote(),parent.logic(),cn.bidcheck.compat.Java8Api.listOf(condition, other, required));
        assertTrue(CompilationRecovery.repairIssues(new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(parent),"MODEL_COMPILED",cn.bidcheck.compat.Java8Api.listOf()),new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(swapped),"MODEL_COMPILED",cn.bidcheck.compat.Java8Api.listOf())).stream().anyMatch(x->x.contains("分支顺序")));
        Rule date=new Rule(required.id(),required.description(),"REFERENCE_TIME",required.subject(),"","","","",cn.bidcheck.compat.Java8Api.mapOf(),required.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(CompilationRecovery.repairIssues(new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(required),"MODEL_COMPILED",cn.bidcheck.compat.Java8Api.listOf()),new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(date),"MODEL_COMPILED",cn.bidcheck.compat.Java8Api.listOf())).stream().anyMatch(x->x.contains("业务谓词")));
    }
    @Test void explicitVisualDomainsAreHintsAndStillNeedObservedEvidence(){
        String source="甲公司在授权书要求位置盖章。";Checkpoint checkpoint=cp(source);
        Plan p=new RequirementCompiler().compileResponse(checkpoint,tree("ALL",with(node("seal","VISUAL",source),"subject","甲公司","field","seal","parameters",cn.bidcheck.compat.Java8Api.mapOf("documentType", "授权书"))),source,true,cn.bidcheck.compat.Java8Api.setOf("SIGNATURE_SEAL", "VISUAL_FORM"));
        assertEquals("VISUAL",p.rules().get(0).predicate());
        EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("b","p","BID","投标文件",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"甲公司授权书（盖章）","PAGE","PDF","")),cn.bidcheck.compat.Java8Api.listOf())));
        assertEquals("UNDETERMINED",new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),corpus,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(p.rules().get(0)).status(),"Template wording is not a seal observation");
    }
    @Test void splitRecoveryActuallyRunsPerObligationAndKeepsAllSourcePages(){
        String requirement="须提交甲证书；须提交乙证书。";List<Map<String,Object>> extracts=new ArrayList<>();
        ModelGateway model=gateway((op,input)->{if(op.equals("COMPILE"))return tree("ALL",with(node("a","MATERIAL","须提交甲证书"),"expected","甲证书"),with(node("b","MATERIAL","须提交乙证书。"),"expected","乙证书"));extracts.add(input);return json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf()));});
        Document document=new Document("b","p","BID","材料",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"甲证书附件索引","PAGE","PDF",""), new Page(2,"乙证书附件索引","PAGE","PDF","")),cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> result=new VerificationEngine(model,null).evaluate(cp(requirement),cn.bidcheck.compat.Java8Api.listOf(document),null,cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true, "allowModelRuleCompilation", true, "recoveryStrategy", "SPLIT_FAILED_REQUEST"));
        assertEquals(2,extracts.size(),"Unchanged candidates must not cause another empty extraction cycle");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("a", "b"),cn.bidcheck.compat.Java8Api.toList(extracts.stream().map(x->x.get("batchObligationId"))));
        for(Map<String,Object> input:extracts)assertEquals(1,((List<?>)input.get("rules")).size());
        assertEquals(extracts.get(0).get("sources"),extracts.get(1).get("sources"));assertEquals(2,((List<?>)extracts.get(0).get("sources")).size());
        assertEquals("UNDETERMINED",result.get("businessDecision"));assertEquals("NO_NEW_EVIDENCE_OR_CLOSED_GAP",((Map<?,?>)result.get("metrics")).get("stopReason"));
    }
    @Test void sourceOrCannotBecomeAllAndSourceAndCannotBecomeAny() throws Exception {
        for(String join:cn.bidcheck.compat.Java8Api.listOf("或", "且")){
            String source="须提交甲证书"+join+"须提交乙证书。",correct=join.equals("或")?"ANY":"ALL",wrong=join.equals("或")?"AND":"OR";
            Plan invalid=parse(source,tree(wrong,node("a","MATERIAL","须提交甲证书"),node("b","MATERIAL","须提交乙证书。")));
            assertTrue(invalid.issues().stream().anyMatch(s->s.startsWith("TOP_LEVEL_LOGIC_SOURCE:")));
            ModelGateway model=gateway((op,input)->tree(input.containsKey("validationErrors")?correct:wrong,node("a","MATERIAL","须提交甲证书"),node("b","MATERIAL","须提交乙证书。")));
            Plan repaired=new RequirementCompiler().compile(cp(source),new RunSession(model,cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true, "allowModelRuleCompilation", true),"scope"));
            assertEquals(correct,repaired.logic());assertTrue(repaired.issues().isEmpty(),repaired.issues().toString());
        }
    }
    @Test void forgedSourceLogicCannotAuthorizeWeakeningAConjunction(){
        String source="提交甲证书；提交乙证书。";
        Plan original=parse(source,tree("ALL",with(node("g","GROUP",source),"logic","ALL","parameters",cn.bidcheck.compat.Java8Api.mapOf("expectedSourceLogic", "ANY"),"children",cn.bidcheck.compat.Java8Api.listOf(node("a","MATERIAL","提交甲证书"), node("b","BAD","提交乙证书。")))));
        assertFalse(original.rules().get(0).parameters().containsKey("expectedSourceLogic"));
        Plan changed=parse(source,tree("ALL",with(node("g","GROUP",source),"logic","ANY","children",cn.bidcheck.compat.Java8Api.listOf(node("a","MATERIAL","提交甲证书"), node("b","MATERIAL","提交乙证书。")))));
        assertTrue(CompilationRecovery.repairIssues(original,changed).stream().anyMatch(s->s.contains("分支逻辑")));
    }
    @Test void repeatedWholeClauseQuotesDoNotHideAnExplicitMaterialAlternative(){
        String source="须提交营业执照或登记证书。";
        Plan plan=parse(source,tree("AND",with(node("a","MATERIAL",source),"expected","营业执照"),with(node("b","MATERIAL",source),"expected","登记证书")));
        assertTrue(plan.issues().stream().anyMatch(s->s.startsWith("TOP_LEVEL_LOGIC_SOURCE:ANY")));
    }
    @Test void aFailedMiddleSplitDoesNotSkipLaterWorkAndResumeOnlyCallsFailedBatch(){
        String source="须提交甲证书；须提交乙证书；须提交丙证书。";List<String> seen=new ArrayList<>();boolean[] recovering={false};
        ModelGateway model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "split-middle-failure";}
            public JsonNode request(String op,Map<String,Object> input)throws java.io.IOException{
                if(op.equals("COMPILE"))return tree("ALL",with(node("a","MATERIAL","须提交甲证书"),"expected","甲证书"),with(node("b","MATERIAL","须提交乙证书"),"expected","乙证书"),with(node("c","MATERIAL","须提交丙证书。"),"expected","丙证书"));
                String id=input.get("batchObligationId").toString();seen.add(id);if(id.equals("b")&&!recovering[0])throw new VerificationModelClient.ModelRequestException("REQUEST_TIMEOUT",0,true,true);
                return json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf()));
            }};
        Document bid=new Document("b","p","BID","材料",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"甲证书附件索引","PAGE","PDF",""), new Page(2,"乙证书附件索引","PAGE","PDF",""), new Page(3,"丙证书附件索引","PAGE","PDF","")),cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> context=new HashMap<>(cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true, "allowModelRuleCompilation", true, "recoveryStrategy", "SPLIT_FAILED_REQUEST"));
        VerificationEngine engine=new VerificationEngine(model,null);Map<String,Object> first=engine.evaluate(cp(source),cn.bidcheck.compat.Java8Api.listOf(bid),null,context);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("a", "b", "c"),new ArrayList<>(new LinkedHashSet<>(seen)));
        assertEquals(1,Collections.frequency(seen,"a"));assertEquals(1,Collections.frequency(seen,"c"));
        assertTrue(Collections.frequency(seen,"b")>=2,"the failed obligation must be isolated by source before later work continues");
        assertEquals("UNDETERMINED",first.get("businessDecision"));
        recovering[0]=true;seen.clear();context.put("priorSteps",first.get("steps"));
        Map<String,Object> second=engine.evaluate(cp(source),cn.bidcheck.compat.Java8Api.listOf(bid),null,context);
        assertFalse(seen.isEmpty());assertTrue(seen.stream().allMatch("b"::equals),"resume may read only the failed obligation's source packets");
        assertEquals("UNDETERMINED",second.get("businessDecision"),"A successful empty response is not business proof");
    }
    @Test void persistedStepFingerprintIsStableAcrossNestedMapOrderingButNotChangedInput(){
        RunSession session=new RunSession(gateway((op,in)->json.createObjectNode()),cn.bidcheck.compat.Java8Api.mapOf(),"document-version-hash");
        Map<String,Object> a=linked("rules",cn.bidcheck.compat.Java8Api.listOf(linked("subject","甲公司","parameters",linked("taxBasis","含税","lot","第一标段"))),"sources",cn.bidcheck.compat.Java8Api.listOf("s1", "s2"));
        Map<String,Object> b=linked("sources",cn.bidcheck.compat.Java8Api.listOf("s1", "s2"),"rules",cn.bidcheck.compat.Java8Api.listOf(linked("parameters",linked("lot","第一标段","taxBasis","含税"),"subject","甲公司")));
        assertEquals(session.fingerprint("EXTRACT",a),session.fingerprint("EXTRACT",b));
        b.put("sources",cn.bidcheck.compat.Java8Api.listOf("s1", "other-version"));assertNotEquals(session.fingerprint("EXTRACT",a),session.fingerprint("EXTRACT",b));
    }
}
