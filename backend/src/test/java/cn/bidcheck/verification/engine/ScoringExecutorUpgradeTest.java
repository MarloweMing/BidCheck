package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.verification.agent.AgentInvestigator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ScoringExecutorUpgradeTest {
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String OLD="source-scoring-rules-4-contract-membership-and-lot-boundaries";

    @Test void staleEncodedSourceFormulaCannotBecomeAGenericContractCountOrOpenAgentTarget() throws Exception {
        Rule current=SourcePerformanceScoreTest.rule(3);
        for(String version:Arrays.asList(OLD,"")){
            Rule old=previousVersion(current,version);
            RuleEvaluator evaluator=new RuleEvaluator(Collections.emptyList(),new EvidenceCorpus(Collections.emptyList()),Collections.emptyMap());
            Decision decision=evaluator.evaluate(old);
            assertEquals("UNDETERMINED",decision.status());assertEquals("SCORING_FORMULA_REQUIRED",decision.gaps().get(0).code());
            assertFalse(decision.proof().containsKey("deduplicatedContracts"));
            assertTrue(VerificationEngine.evidenceRules(Collections.singletonList(old)).isEmpty());
            assertTrue(AgentInvestigationScope.kinds(old).isEmpty());
        }
        assertEquals(new HashSet<>(Arrays.asList("PERSON_ROLE","CERTIFICATE","MONTHS")),AgentInvestigationScope.kinds(current));
    }

    @Test void originalScoringSpanAndAlternateTenderBasesAreRecompiledBeforeFirstRunAndTechnicalRetry() throws Exception {
        Checkpoint cp=SourcePerformanceScoreTest.checkpoints().get(3);
        String staffing="施工企业主要负责人1人，项目负责人1人，施工部分负责人1人，设计部分负责人1人，专职安全员1人。";
        Document tender=new Document(cp.source().documentId(),"p","TENDER","招标文件",1,"tender-hash","",Arrays.asList(
            new Page(70,"人员要求："+staffing,"BLOCK","DOCX",""),
            new Page(438,"人员要求\t"+staffing+"（1）施工企业主要负责人须提供安全生产考核合格证。","BLOCK","DOCX",""),
            new Page(cp.source().page(),cp.source().quote(),cp.source().locationType(),"DOCX","")),Collections.emptyList());
        Document bid=SharedFactReuseTest.doc("bid","p",1,"本项目投标函正文。");
        Rule old=previousVersion(SourcePerformanceScoreTest.rule(3),OLD);
        Map<String,Object> raw=JSON.convertValue(linked("logic","ALL","rules",Collections.singletonList(old)),Map.class);
        String digest=identity(JSON.writeValueAsString(raw));
        List<Map<String,Object>> publications=new ArrayList<>();
        Map<String,Object> ctx=linked("tenderDocuments",Collections.singletonList(tender),"requirementProjectId","p",
            "originalRequirement",cp.source().quote(),"reviewMode","AGENT_LED");
        // A mismatched artifact is even returned under the requested key (e.g. a migrated store).
        ctx.put("requirementArtifactLookup",(Function<Map<String,Object>,Map<String,Object>>)request->{
            Map<String,Object> saved=new LinkedHashMap<>(request);
            saved.putAll(linked("rawResponse",raw,"state","VALIDATED","responseDigest",digest));return saved;
        });
        ctx.put("requirementArtifactPublisher",(Consumer<Map<String,Object>>)publications::add);
        VerificationEngine engine=new VerificationEngine(ModelGateway.disabled(),null);engine.setInvestigator(new AgentInvestigator(){
            public boolean enabled(){return true;}
            public String identity(){return "score-upgrade";}
            public Map<String,Object> investigate(Map<String,Object> start,ToolHandler tools,Consumer<Map<String,Object>> event){
                throw new AssertionError("A conflicted tender baseline must not start a bid evidence investigation");
            }
        });
        for(String strategy:Arrays.asList("","RETRY_FAILED_STEP","TARGETED_EVIDENCE","RECOMPUTE_SHARED_EVIDENCE")){
            Map<String,Object> context=new HashMap<>(ctx);
            if(!strategy.isEmpty()){
                context.put("recoveryStrategy",strategy);context.put("recoveryTargetObligationIds",Collections.singletonList(old.id()));
                context.put("previousAtomicCheck",linked("plan",raw,"agentReview",linked("planAuthority","SERVER_REQUIREMENT_COMPILER")));
            }
            if(strategy.equals("RECOMPUTE_SHARED_EVIDENCE")){
                context.put("recoveryMode","RECOMPUTE_ONLY");context.put("recoveryModelAllowed",false);
            }
            Map<String,Object> out=engine.evaluate(cp,Collections.singletonList(bid),tender,context);
            assertEquals("UNDETERMINED",out.get("businessDecision"),strategy);
            Map<String,Object> proof=(Map<String,Object>)RuleEvaluator.maps(out.get("obligations")).get(0).get("proof");
            assertEquals("source-extra-personnel-score-1",proof.get("executor"),strategy);
            assertTrue(AgentInvestigationScope.awaitingRequirementSource(proof),strategy);
            assertEquals(Collections.emptyMap(),out.get("agentInvestigation"),strategy);
        }
        assertFalse(publications.isEmpty());
        for(Map<String,Object> artifact:publications){
            assertFalse(SourceScoringRules.outdatedFormula(JSON.valueToTree(artifact.get("rawResponse"))));
            assertTrue(artifact.get("rawResponse").toString().contains(SourceScoringRules.VERSION));
        }
    }
    @Test void ordinaryBidLetterKeepsItsTechnicalRetryAndCannotAcquireAContractScoringTarget() {
        Checkpoint cp=new Checkpoint("bid-letter","投标函","形式检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS","投标函","",true,true,true,
            new cn.bidcheck.domain.Domain.Evidence("tender","招标文件",539,"PARAGRAPH","投标函","p539",1));
        Document tender=new Document("tender","p","TENDER","招标文件",1,"tender-hash","",
            Collections.singletonList(new Page(539,"投标函","PARAGRAPH","DOCX","")),Collections.emptyList());
        Document bid=SharedFactReuseTest.doc("bid","p",1,"投标函 实质承诺及报价见正文。");
        List<Map<String,Object>> requests=new ArrayList<>();
        VerificationEngine engine=new VerificationEngine(ModelGateway.disabled(),null);engine.setInvestigator(new AgentInvestigator(){
            public boolean enabled(){return true;}
            public String identity(){return "material-retry";}
            public Map<String,Object> investigate(Map<String,Object> start,ToolHandler tools,Consumer<Map<String,Object>> event){
                requests.add(start);return linked("status","NO_PROGRESS","modelCalls",0,"toolCalls",0);
            }
        });
        Map<String,Object> ctx=linked("reviewMode","AGENT_LED","recoveryStrategy","RETRY_FAILED_STEP","recoveryModelAllowed",true,
            "recoveryTargetObligationIds",Collections.singletonList("TYPED-MATERIAL"));
        engine.evaluate(cp,Collections.singletonList(bid),tender,ctx);
        assertEquals(1,requests.size());assertTrue(requests.get(0).toString().contains("TYPED-MATERIAL"));
    }
    private Rule previousVersion(Rule current,String version){
        Map<String,Object> p=new LinkedHashMap<>(current.parameters());
        if(version.isEmpty())p.remove("sourceScoringVersion");else p.put("sourceScoringVersion",version);
        return new Rule(current.id(),current.description(),current.predicate(),current.subject(),current.field(),
            current.operator(),current.expected(),current.unit(),p,current.sourceQuote(),current.logic(),current.children());
    }
}
