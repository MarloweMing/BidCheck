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
    Map<String,Object> node(String id,String predicate,String quote){return linked("id",id,"predicate",predicate,"description",quote,"sourceQuote",quote,"parameters",Map.of());}
    Map<String,Object> with(Map<String,Object> base,Object... fields){Map<String,Object> copy=new LinkedHashMap<>(base);for(int i=0;i<fields.length;i+=2)copy.put(fields[i].toString(),fields[i+1]);return copy;}
    JsonNode tree(String logic,Map<String,Object>... rules){return json.valueToTree(linked("logic",logic,"rules",List.of(rules)));}
    Plan parse(String text,JsonNode response){return new RequirementCompiler().compileResponse(cp(text),response,text,true,Set.of("PERSONNEL_CONSORTIUM"));}
    ModelGateway gateway(java.util.function.BiFunction<String,Map<String,Object>,JsonNode> fn){return new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "compiled-repair-test";}public JsonNode request(String op,Map<String,Object> input){return fn.apply(op,input);}};}

    @Test void registrationAndOrAliasesRetainAlternativeMaterialAndOwnValidity(){
        String text="投标人须提交有效的营业执照或登记证书。";
        Map<String,Object> a=with(node("a","MATERIAL","有效的营业执照"),"logic","AND","children",List.of(node("av","VALIDITY","有效的营业执照")));
        Map<String,Object> b=with(node("b","MATERIAL","登记证书"),"expected","登记证书");
        Plan plan=parse(text,tree("AND",with(node("choice","GROUP",text),"logic","OR","children",List.of(a,b))));
        assertEquals("ALL",plan.logic());assertEquals("ANY",plan.rules().get(0).logic());
        assertEquals("ALL",plan.rules().get(0).children().get(0).logic());
        assertEquals("MATERIAL",plan.rules().get(0).children().get(0).predicate());
        assertTrue(plan.issues().isEmpty(),plan.issues().toString());
        assertEquals("UNDETERMINED",new RuleEvaluator(List.of(),new EvidenceCorpus(List.of()),Map.of()).evaluate(plan.rules().get(0)).status());
    }
    @Test void unknownLogicIsNotReinterpretedAsAnAlias(){String text="提交承诺书。";Plan p=parse(text,tree("ALL",with(node("r","GROUP",text),"logic","XOR","children",List.of(node("a","MATERIAL",text)))));assertFalse(p.issues().isEmpty());assertEquals("UNSUPPORTED",p.rules().get(0).predicate());}
    @Test void focusedRoutingKeepsSealAndCompanyComparisonWithoutPriceDomain(){
        String text="甲公司须盖章；甲公司名称与证明一致。";
        Plan plan=parse(text,tree("ALL",with(node("seal","VISUAL","甲公司须盖章"),"subject","甲公司","field","seal"),with(node("same","SAME_FIELD","甲公司名称与证明一致。"),"subject","甲公司","field","名称")));
        assertEquals(List.of("VISUAL","SAME_FIELD"),plan.rules().stream().map(Rule::predicate).toList());assertTrue(plan.issues().isEmpty(),plan.issues().toString());
        assertEquals("VISUAL_OBSERVATION",CompilationRecovery.executor(plan.rules().get(0).predicate()));
        assertEquals("UNDETERMINED",new RuleEvaluator(List.of(),new EvidenceCorpus(List.of()),Map.of()).evaluate(plan.rules().get(0)).status());
    }
    @Test void sourceAndSubjectGuardStillRejectInventedCompanyAfterRoutingExpansion(){String text="须盖章。";Plan p=parse(text,tree("ALL",with(node("s","VISUAL",text),"subject","甲公司")));assertEquals("UNSUPPORTED",p.rules().get(0).predicate());}
    @Test void repairReceivesExactErrorsAndCannotBeMistakenForFirstSuccessfulTransport() throws Exception {
        String text="须提交营业执照。";AtomicInteger calls=new AtomicInteger();List<Map<String,Object>> requests=new ArrayList<>();
        ModelGateway model=gateway((op,in)->{calls.incrementAndGet();requests.add(in);return tree("ALL",node("r",in.containsKey("validationErrors")?"MATERIAL":"BAD_MATERIAL",text));});
        RunSession session=new RunSession(model,Map.of("unlimitedModelCalls",true,"allowModelRuleCompilation",true),"scope");Plan plan=new RequirementCompiler().compile(cp(text),session);
        assertEquals(2,calls.get());assertEquals("MATERIAL",plan.rules().get(0).predicate());assertTrue(plan.issues().isEmpty());
        assertEquals("REPAIR_COMPILED_REQUIREMENT",requests.get(1).get("task"));assertFalse(((List<?>)requests.get(1).get("validationErrors")).isEmpty());
        assertTrue(session.steps.stream().anyMatch(s->s.get("status").equals("VALIDATION_FAILED")));
        assertEquals("VALIDATED",session.compilationAudit.get("state"));
        RunSession resumed=new RunSession(model,Map.of("priorSteps",session.steps,"unlimitedModelCalls",true,"allowModelRuleCompilation",true),"scope");new RequirementCompiler().compile(cp(text),resumed);
        assertEquals(2,calls.get(),"Resume must reuse the repaired, validated plan rather than repeat its invalid first response");
    }
    @Test void repairCannotDeleteAnotherRequiredObligationOrReplaceBusinessWithReferenceDate() throws Exception {
        String source="提交甲证书；提交乙证书。";AtomicInteger calls=new AtomicInteger();
        ModelGateway model=gateway((op,in)->{calls.incrementAndGet();return in.containsKey("validationErrors")?tree("ALL",node("a","REFERENCE_TIME","提交甲证书")):tree("ALL",node("a","MATERIAL","提交甲证书"),node("b","BAD_MATERIAL","提交乙证书。"));});
        RunSession session=new RunSession(model,Map.of("unlimitedModelCalls",true,"allowModelRuleCompilation",true),"scope");Plan plan=new RequirementCompiler().compile(cp(source),session);
        assertEquals(2,calls.get());assertEquals(2,plan.rules().size());assertEquals("MATERIAL",plan.rules().get(0).predicate());assertEquals("UNSUPPORTED",plan.rules().get(1).predicate());
        assertEquals("UNRESOLVED",session.compilationAudit.get("state"));assertFalse(session.steps.stream().anyMatch(s->s.get("type").equals("COMPILED_PLAN")));
    }
    @Test void malformedSameResponseStopsAfterOneTargetedRepair() throws Exception {
        String source="提交甲证书。";AtomicInteger calls=new AtomicInteger();ModelGateway model=gateway((op,in)->{calls.incrementAndGet();return tree("ALL",node("a","BAD_MATERIAL",source));});
        RunSession session=new RunSession(model,Map.of("unlimitedModelCalls",true,"allowModelRuleCompilation",true),"scope");new RequirementCompiler().compile(cp(source),session);
        assertEquals(2,calls.get());assertEquals("UNRESOLVED",session.compilationAudit.get("state"));
    }
    @Test void receivedOrLegacyCompletedCompilerResponseIsNeverAValidatedCacheHit() throws Exception {
        AtomicInteger calls=new AtomicInteger();ModelGateway model=gateway((op,in)->{calls.incrementAndGet();return tree("ALL",node("a","BAD","材料"));});
        RunSession first=new RunSession(model,Map.of("unlimitedModelCalls",true),"scope");first.model("COMPILE",Map.of("requirement","材料"));
        assertEquals("RECEIVED",first.steps.get(first.steps.size()-1).get("status"));
        Map<String,Object> legacy=new LinkedHashMap<>(first.steps.get(first.steps.size()-1));legacy.put("status","COMPLETED");
        RunSession second=new RunSession(model,Map.of("priorSteps",List.of(legacy),"unlimitedModelCalls",true),"scope");second.model("COMPILE",Map.of("requirement","材料"));
        assertEquals(2,calls.get());assertEquals(0,second.resumedSteps);
    }
    @Test void recoveryGapHintsOnlyAffectTheirOwnRetrievalAndNeverAlterSource(){
        Rule a=parse("甲公司须盖章。",tree("ALL",node("a","VISUAL","甲公司须盖章。"))).rules().get(0);
        Rule b=parse("乙公司须盖章。",tree("ALL",node("b","VISUAL","乙公司须盖章。"))).rules().get(0);
        List<Rule> result=ObligationRecovery.retrievalRules(List.of(a,b),List.of(Map.of("obligationId","a","need","授权书落款盖章位置")));
        assertTrue(result.get(0).description().contains("授权书落款"));assertEquals(a.sourceQuote(),result.get(0).sourceQuote());assertEquals(b,result.get(1));
    }
    @Test void repairCannotTurnARequiredConjunctionIntoAnAlternative(){
        String source="提交甲证书；提交乙证书。";
        Plan original=parse(source,tree("ALL",with(node("group","GROUP",source),"logic","ALL","children",List.of(node("a","MATERIAL","提交甲证书"),node("b","BAD","提交乙证书。")))));
        Plan changed=parse(source,tree("ALL",with(node("group","GROUP",source),"logic","ANY","children",List.of(node("a","MATERIAL","提交甲证书"),node("b","MATERIAL","提交乙证书。")))));
        assertTrue(CompilationRecovery.repairIssues(original,changed).stream().anyMatch(x->x.contains("分支逻辑")));
    }
    @Test void repairCannotReverseConditionalPathsOrReplaceAnOwnValidityPredicate(){
        Rule condition=new Rule("if","适用性","INPUT","","","","","",Map.of(),"若适用","",List.of());
        Rule required=new Rule("yes","证照有效","UNSUPPORTED","甲","","","","",Map.of("originalPredicate","VALIDITY"),"甲证照须有效","",List.of());
        Rule other=new Rule("no","另项材料","MATERIAL","","","","","",Map.of(),"其他情形须提交说明","",List.of());
        Rule parent=new Rule("p","条件","GROUP","","","","","",Map.of(),"若适用甲证照须有效，其他情形须提交说明","CONDITIONAL",List.of(condition,required,other));
        Rule swapped=new Rule(parent.id(),parent.description(),parent.predicate(),"","","","","",Map.of(),parent.sourceQuote(),parent.logic(),List.of(condition,other,required));
        assertTrue(CompilationRecovery.repairIssues(new Plan("ALL",List.of(parent),"MODEL_COMPILED",List.of()),new Plan("ALL",List.of(swapped),"MODEL_COMPILED",List.of())).stream().anyMatch(x->x.contains("分支顺序")));
        Rule date=new Rule(required.id(),required.description(),"REFERENCE_TIME",required.subject(),"","","","",Map.of(),required.sourceQuote(),"",List.of());
        assertTrue(CompilationRecovery.repairIssues(new Plan("ALL",List.of(required),"MODEL_COMPILED",List.of()),new Plan("ALL",List.of(date),"MODEL_COMPILED",List.of())).stream().anyMatch(x->x.contains("业务谓词")));
    }
    @Test void explicitVisualDomainsAreHintsAndStillNeedObservedEvidence(){
        String source="甲公司在授权书要求位置盖章。";Checkpoint checkpoint=cp(source);
        Plan p=new RequirementCompiler().compileResponse(checkpoint,tree("ALL",with(node("seal","VISUAL",source),"subject","甲公司","field","seal","parameters",Map.of("documentType","授权书"))),source,true,Set.of("SIGNATURE_SEAL","VISUAL_FORM"));
        assertEquals("VISUAL",p.rules().get(0).predicate());
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(new Document("b","p","BID","投标文件",1,"hash","",List.of(new Page(1,"甲公司授权书（盖章）","PAGE","PDF","")),List.of())));
        assertEquals("UNDETERMINED",new RuleEvaluator(List.of(),corpus,Map.of()).evaluate(p.rules().get(0)).status(),"Template wording is not a seal observation");
    }
    @Test void splitRecoveryActuallyRunsPerObligationAndKeepsAllSourcePages(){
        String requirement="须提交甲证书；须提交乙证书。";List<Map<String,Object>> extracts=new ArrayList<>();
        ModelGateway model=gateway((op,input)->{if(op.equals("COMPILE"))return tree("ALL",with(node("a","MATERIAL","须提交甲证书"),"expected","甲证书"),with(node("b","MATERIAL","须提交乙证书。"),"expected","乙证书"));extracts.add(input);return json.valueToTree(Map.of("facts",List.of()));});
        Document document=new Document("b","p","BID","材料",1,"hash","",List.of(new Page(1,"甲证书附件索引","PAGE","PDF",""),new Page(2,"乙证书附件索引","PAGE","PDF","")),List.of());
        Map<String,Object> result=new VerificationEngine(model,null).evaluate(cp(requirement),List.of(document),null,Map.of("unlimitedModelCalls",true,"allowModelRuleCompilation",true,"recoveryStrategy","SPLIT_FAILED_REQUEST"));
        assertEquals(2,extracts.size(),"Unchanged candidates must not cause another empty extraction cycle");
        assertEquals(List.of("a","b"),extracts.stream().map(x->x.get("batchObligationId")).toList());
        for(Map<String,Object> input:extracts)assertEquals(1,((List<?>)input.get("rules")).size());
        assertEquals(extracts.get(0).get("sources"),extracts.get(1).get("sources"));assertEquals(2,((List<?>)extracts.get(0).get("sources")).size());
        assertEquals("UNDETERMINED",result.get("businessDecision"));assertEquals("NO_NEW_EVIDENCE_OR_CLOSED_GAP",((Map<?,?>)result.get("metrics")).get("stopReason"));
    }
    @Test void sourceOrCannotBecomeAllAndSourceAndCannotBecomeAny() throws Exception {
        for(String join:List.of("或","且")){
            String source="须提交甲证书"+join+"须提交乙证书。",correct=join.equals("或")?"ANY":"ALL",wrong=join.equals("或")?"AND":"OR";
            Plan invalid=parse(source,tree(wrong,node("a","MATERIAL","须提交甲证书"),node("b","MATERIAL","须提交乙证书。")));
            assertTrue(invalid.issues().stream().anyMatch(s->s.startsWith("TOP_LEVEL_LOGIC_SOURCE:")));
            ModelGateway model=gateway((op,input)->tree(input.containsKey("validationErrors")?correct:wrong,node("a","MATERIAL","须提交甲证书"),node("b","MATERIAL","须提交乙证书。")));
            Plan repaired=new RequirementCompiler().compile(cp(source),new RunSession(model,Map.of("unlimitedModelCalls",true,"allowModelRuleCompilation",true),"scope"));
            assertEquals(correct,repaired.logic());assertTrue(repaired.issues().isEmpty(),repaired.issues().toString());
        }
    }
    @Test void forgedSourceLogicCannotAuthorizeWeakeningAConjunction(){
        String source="提交甲证书；提交乙证书。";
        Plan original=parse(source,tree("ALL",with(node("g","GROUP",source),"logic","ALL","parameters",Map.of("expectedSourceLogic","ANY"),"children",List.of(node("a","MATERIAL","提交甲证书"),node("b","BAD","提交乙证书。")))));
        assertFalse(original.rules().get(0).parameters().containsKey("expectedSourceLogic"));
        Plan changed=parse(source,tree("ALL",with(node("g","GROUP",source),"logic","ANY","children",List.of(node("a","MATERIAL","提交甲证书"),node("b","MATERIAL","提交乙证书。")))));
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
                return json.valueToTree(Map.of("facts",List.of()));
            }};
        Document bid=new Document("b","p","BID","材料",1,"hash","",List.of(new Page(1,"甲证书附件索引","PAGE","PDF",""),new Page(2,"乙证书附件索引","PAGE","PDF",""),new Page(3,"丙证书附件索引","PAGE","PDF","")),List.of());
        Map<String,Object> context=new HashMap<>(Map.of("unlimitedModelCalls",true,"allowModelRuleCompilation",true,"recoveryStrategy","SPLIT_FAILED_REQUEST"));
        VerificationEngine engine=new VerificationEngine(model,null);Map<String,Object> first=engine.evaluate(cp(source),List.of(bid),null,context);
        assertEquals(List.of("a","b","c"),new ArrayList<>(new LinkedHashSet<>(seen)));
        assertEquals(1,Collections.frequency(seen,"a"));assertEquals(1,Collections.frequency(seen,"c"));
        assertTrue(Collections.frequency(seen,"b")>=2,"the failed obligation must be isolated by source before later work continues");
        assertEquals("UNDETERMINED",first.get("businessDecision"));
        recovering[0]=true;seen.clear();context.put("priorSteps",first.get("steps"));
        Map<String,Object> second=engine.evaluate(cp(source),List.of(bid),null,context);
        assertFalse(seen.isEmpty());assertTrue(seen.stream().allMatch("b"::equals),"resume may read only the failed obligation's source packets");
        assertEquals("UNDETERMINED",second.get("businessDecision"),"A successful empty response is not business proof");
    }
    @Test void persistedStepFingerprintIsStableAcrossNestedMapOrderingButNotChangedInput(){
        RunSession session=new RunSession(gateway((op,in)->json.createObjectNode()),Map.of(),"document-version-hash");
        Map<String,Object> a=linked("rules",List.of(linked("subject","甲公司","parameters",linked("taxBasis","含税","lot","第一标段"))),"sources",List.of("s1","s2"));
        Map<String,Object> b=linked("sources",List.of("s1","s2"),"rules",List.of(linked("parameters",linked("lot","第一标段","taxBasis","含税"),"subject","甲公司")));
        assertEquals(session.fingerprint("EXTRACT",a),session.fingerprint("EXTRACT",b));
        b.put("sources",List.of("s1","other-version"));assertNotEquals(session.fingerprint("EXTRACT",a),session.fingerprint("EXTRACT",b));
    }
}
