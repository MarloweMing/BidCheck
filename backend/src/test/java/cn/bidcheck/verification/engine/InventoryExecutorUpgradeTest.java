package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class InventoryExecutorUpgradeTest {
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String SOURCE="须提交《供应商合规承诺》等必须的承诺文件（清单详见第六章中《承诺书》）。";
    private static final String OLD="source-requirement-inventory-2-typed-source-dependencies";
    private Checkpoint checkpoint(){return new Checkpoint("inventory","承诺清单","资格","HIGH","SEMANTIC_RESPONSE","qualification","",SOURCE,"",true,true,true,new Evidence("t","招标",1,"BLOCK",SOURCE,"",1));}
    private Map<String,Object> context(){
        List<Page> pages=new ArrayList<>();
        String body="投标人承诺按照本项目招标文件要求完成全部工作，项目名称及本次投标人名称应当如实填写，不得修改承诺约定的具体内容，发生违约时自愿承担相应责任。投标人名称：____，日期：____。";
        String[] text={SOURCE,"第六章 投标文件格式","★承诺书","其他承诺书","★承诺书","供应商合规承诺","供应商合规承诺",body,"安全承诺书","安全承诺书",body,"其他承诺"};
        for(int i=0;i<text.length;i++)pages.add(new Page(i+1,text[i],"BLOCK","DOCX",""));
        Document tender=new Document("t","p","TENDER","招标",1,"tender-hash","",pages,Collections.emptyList());
        return linked("tenderDocuments",Collections.singletonList(tender),"originalRequirement",SOURCE,"requirementProjectId","p","sourceRunId","r");
    }
    private Rule oldRule(Map<String,Object> context){
        Rule current=SourceRequirementInventory.compile(checkpoint(),SOURCE,null,context).get(0);
        Map<String,Object> p=new LinkedHashMap<>(current.parameters());p.put("inventoryVersion",OLD);
        return new Rule(current.id(),current.description(),current.predicate(),current.subject(),current.field(),current.operator(),current.expected(),current.unit(),p,current.sourceQuote(),current.logic(),current.children());
    }
    @Test void obsoleteInventoryCannotBecomeOrdinaryMissingContentOrAnAgentFactTarget(){
        Map<String,Object> context=context();Rule old=oldRule(context);
        Decision decision=new RuleEvaluator(Collections.emptyList(),new EvidenceCorpus(Collections.emptyList()),context).evaluate(old);
        assertEquals("UNDETERMINED",decision.status());
        assertEquals("REQUIREMENT_INVENTORY_VERSION_REQUIRED",decision.gaps().get(0).code());
        assertFalse(decision.gaps().stream().anyMatch(g->"CONTENT_EVIDENCE_REQUIRED".equals(g.code())));
        assertTrue(VerificationEngine.evidenceRules(Collections.singletonList(old)).isEmpty());
        assertTrue(AgentInvestigationScope.kinds(old).isEmpty());
    }
    @Test void intactOldPackageAndLocalRecoveryAreRecompiledFromFrozenSourceWithoutModels()throws Exception {
        Map<String,Object> base=context();Rule old=oldRule(base);
        Map<String,Object> raw=JSON.convertValue(linked("logic","ALL","rules",Collections.singletonList(old)),Map.class);
        String digest=identity(JSON.writeValueAsString(raw));List<Map<String,Object>> published=new ArrayList<>();
        base.put("requirementArtifactLookup",(Function<Map<String,Object>,Map<String,Object>>)request->{
            Map<String,Object> saved=new LinkedHashMap<>(request);saved.putAll(linked("state","VALIDATED","rawResponse",raw,"responseDigest",digest));return saved;
        });
        base.put("requirementArtifactPublisher",(Consumer<Map<String,Object>>)published::add);
        ModelGateway forbidden=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "no-model-inventory-repair";}
            public JsonNode request(String operation,Map<String,Object> input){throw new AssertionError("inventory upgrade must remain local");}};
        for(String strategy:Arrays.asList("","TARGETED_EVIDENCE","RETRY_FAILED_STEP","RECOMPUTE_SHARED_EVIDENCE")){
            Map<String,Object> ctx=new LinkedHashMap<>(base);
            if(!strategy.isEmpty()){
                ctx.put("recoveryStrategy",strategy);ctx.put("recoveryTargetObligationIds",Collections.singletonList(old.id()));
                ctx.put("previousAtomicCheck",linked("plan",raw,"agentReview",linked("planAuthority","SERVER_REQUIREMENT_COMPILER")));
            }
            if("RECOMPUTE_SHARED_EVIDENCE".equals(strategy)){ctx.put("recoveryMode","RECOMPUTE_ONLY");ctx.put("recoveryModelAllowed",false);}
            Plan plan=new RequirementCompiler().compile(checkpoint(),new RunSession(forbidden,ctx,"inventory-upgrade"));
            assertFalse(SourceRequirementInventory.outdatedFormula(JSON.valueToTree(plan)),strategy);
            Rule inventory=RequirementCompiler.leaves(plan.rules()).stream().filter(SourceRequirementInventory::applies).findFirst().get();
            Decision evaluated=new RuleEvaluator(Collections.emptyList(),new EvidenceCorpus(Collections.emptyList()),ctx).evaluate(inventory);
            assertEquals(SourceRequirementInventory.VERSION,evaluated.proof().get("executor"),strategy);
            assertEquals("COMMITMENTS",evaluated.proof().get("inventoryKind"),strategy);
            assertEquals(2,evaluated.proof().get("expectedMemberCount"),strategy);
        }
        assertFalse(published.isEmpty());assertTrue(published.stream().noneMatch(p->SourceRequirementInventory.outdatedFormula(JSON.valueToTree(p.get("rawResponse")))));
    }
    @Test void runtimeInventoryRevisionParticipatesInTheArtifactFingerprint(){
        Map<String,Object> ctx=context();Checkpoint cp=checkpoint();
        RequirementSourceContext.Context source=RequirementSourceContext.resolve(cp,SOURCE,ctx);
        Map<String,Object> current=RequirementRepairPolicy.versions(),old=new LinkedHashMap<>(current);old.remove("requirementInventoryRevision");
        assertEquals(SourceRequirementInventory.VERSION,current.get("requirementInventoryRevision"));
        RunSession session=new RunSession(ModelGateway.disabled(),ctx,"version-key");
        RequirementArtifact first=new RequirementArtifact(session,linked("requirementRevisions",old),source,true);
        RequirementArtifact second=new RequirementArtifact(session,linked("requirementRevisions",current),source,true);
        assertNotEquals(first.request.get("artifactId"),second.request.get("artifactId"));
    }
}
