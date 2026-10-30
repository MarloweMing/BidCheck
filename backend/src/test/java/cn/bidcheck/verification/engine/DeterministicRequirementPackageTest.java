package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class DeterministicRequirementPackageTest {
    private static final String SOURCE="设计单位须提交营业执照。";
    private final Map<String,Map<String,Object>> artifacts=new LinkedHashMap<>();
    private ModelGateway model(String identity){return new ModelGateway(){public boolean enabled(){return true;}public String identity(){return identity;}public JsonNode request(String operation,Map<String,Object> input){throw new AssertionError("local package must never call the model");}};}
    private Checkpoint checkpoint(String source,String title){return new Checkpoint(title,title,"资格","HIGH","SEMANTIC_RESPONSE","qualification","",source,"",true,true,true,new Evidence("t","招标",1,"BLOCK",source,"p1",1));}
    private Map<String,Object> context(String source,int version){
        Document doc=new Document("t","p","TENDER","招标",version,"hash-"+version,"",List.of(new Page(1,source,"BLOCK","NATIVE","")),List.of());
        Map<String,Object> context=new LinkedHashMap<>();context.put("originalRequirement",source);context.put("tenderDocuments",List.of(doc));context.put("requirementProjectId","p");
        context.put("requirementArtifactLookup",(Function<Map<String,Object>,Map<String,Object>>)request->artifacts.getOrDefault(str(request,"artifactId"),Map.of()));
        context.put("requirementArtifactPublisher",(Consumer<Map<String,Object>>)artifact->artifacts.put(str(artifact,"artifactId"),artifact));return context;
    }
    private Plan compile(String source,String title,String model,int version)throws Exception{return new RequirementCompiler().compile(checkpoint(source,title),new RunSession(model(model),context(source,version),"run-"+title));}

    @Test void sameFrozenObligationIsReusedAcrossModelAndDisplayTitleChanges()throws Exception{
        Plan first=compile(SOURCE,"资格审查A","model-a",1);
        Plan second=compile(SOURCE,"新的显示标题","model-b",1);
        assertEquals("DETERMINISTIC_PACKAGE_REUSED",second.method());assertEquals(1,artifacts.size());
        assertEquals(first.requirementScope().get("artifactId"),second.requirementScope().get("artifactId"));
        assertEquals(first.rules().get(0).parameters().get("obligationIdentity"),second.rules().get(0).parameters().get("obligationIdentity"));
        assertTrue(artifacts.values().stream().allMatch(a->"SERVER_OWNED".equals(a.get("modelIdentity"))));
    }
    @Test void newDocumentVersionAndChangedFocusCannotUseEarlierPackage()throws Exception{
        Plan first=compile(SOURCE,"资格","model-a",1);
        Plan changed=compile(SOURCE,"资格","model-a",2);
        assertNotEquals(first.requirementScope().get("artifactId"),changed.requirementScope().get("artifactId"));
        Map<String,Object> focus=context(SOURCE,2);focus.put("focusedCheck",true);focus.put("atomicDomains",List.of("qualification"));
        Plan focused=new RequirementCompiler().compile(checkpoint(SOURCE,"资格"),new RunSession(model("model-a"),focus,"focused"));
        assertNotEquals(changed.requirementScope().get("artifactId"),focused.requirementScope().get("artifactId"));assertEquals(3,artifacts.size());
    }
    @Test void sourceChangeAndExternalAdapterChangeAreSemanticInputs()throws Exception{
        compile(SOURCE,"资格","model-a",1);
        // The source span hash participates even if a caller incorrectly retained the document label/hash.
        compile("施工单位须提交营业执照。","资格","model-a",1);
        Map<String,Object> ctx=context(SOURCE,1);ctx.put("externalAdapterBySource",Map.of("信用中国","CREDIT_CHINA"));
        new RequirementCompiler().compile(checkpoint(SOURCE,"资格"),new RunSession(model("model-a"),ctx,"adapter"));
        assertEquals(3,artifacts.size());
    }
    @Test void unresolvedAlternativeIsDiagnosticReuseNeverACompletedBusinessConclusion()throws Exception{
        String source="设计单位资质要求：须满足下列条件之一：①工程设计综合资质甲级。②特殊专项认可。";
        Plan first=compile(source,"资格","model-a",1);Plan again=compile(source,"资格","model-b",1);
        assertEquals("DETERMINISTIC_PACKAGE_REUSED",again.method());
        assertEquals(1,artifacts.size());assertEquals("UNRESOLVED",artifacts.values().iterator().next().get("state"));
        assertTrue(RequirementCompiler.leaves(again.rules()).stream().anyMatch(r->r.predicate().equals("UNSUPPORTED")));
        assertEquals(first.rules(),again.rules());
    }
    @Test void invalidArtifactIdentityIsRejectedAndMissingSourceDoesNotCreateReusablePackage()throws Exception{
        compile(SOURCE,"资格","model-a",1);
        String key=artifacts.keySet().iterator().next();Map<String,Object> wrong=new LinkedHashMap<>(artifacts.get(key));wrong.put("documentHash","other");artifacts.put(key,wrong);
        Plan fresh=compile(SOURCE,"资格","model-a",1);assertNotEquals("DETERMINISTIC_PACKAGE_REUSED",fresh.method());assertEquals("hash-1",artifacts.get(key).get("documentHash"));
        Map<String,Object> missing=context(SOURCE,1);missing.remove("tenderDocuments");artifacts.clear();
        new RequirementCompiler().compile(checkpoint(SOURCE,"资格"),new RunSession(model("model-a"),missing,"missing-source"));
        assertTrue(artifacts.isEmpty());
    }
    @Test void previouslyValidatedArtifactThatFailsCurrentSourceValidationIsRecompiled()throws Exception{
        compile(SOURCE,"资格","model-a",1);String key=artifacts.keySet().iterator().next();
        Map<String,Object> invalid=new LinkedHashMap<>(artifacts.get(key));
        invalid.put("rawResponse",Map.of("logic","ALL","rules",List.of(Map.of("id","invalid","predicate","MATERIAL","subject","设计单位","expected","营业执照","sourceQuote","不存在于本项目的要求"))));
        artifacts.put(key,invalid);
        Plan repaired=compile(SOURCE,"资格","model-b",1);
        assertNotEquals("DETERMINISTIC_PACKAGE_REUSED",repaired.method());
        assertTrue(repaired.issues().isEmpty(),repaired.issues().toString());
        assertTrue(RequirementCompiler.leaves(repaired.rules()).stream().noneMatch(r->r.predicate().equals("UNSUPPORTED")));
        assertFalse(artifacts.get(key).get("rawResponse").toString().contains("不存在于本项目的要求"));
    }
    @Test void intactDigestDoesNotReplaceCurrentSourceValidation()throws Exception{
        compile(SOURCE,"资格","model-a",1);String key=artifacts.keySet().iterator().next();
        Map<String,Object> invalid=new LinkedHashMap<>(artifacts.get(key));
        Map<String,Object> raw=Map.of("logic","ALL","rules",List.of(Map.of("id","legacy","predicate","MATERIAL","subject","设计单位","expected","营业执照","sourceQuote","旧校验器接受过的其他要求")));
        invalid.put("rawResponse",raw);
        invalid.put("responseDigest",identity(new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(raw)));
        artifacts.put(key,invalid);
        Plan repaired=compile(SOURCE,"资格","model-b",1);
        assertNotEquals("DETERMINISTIC_PACKAGE_REUSED",repaired.method());assertTrue(repaired.issues().isEmpty());
        assertTrue(RequirementCompiler.leaves(repaired.rules()).stream().noneMatch(r->r.predicate().equals("UNSUPPORTED")));
    }
    @Test void missingIdentityOrDigestCannotBeAdopted()throws Exception{
        for(String missing:List.of("documentHash","modelIdentity","inputFingerprint","responseDigest")){
            artifacts.clear();compile(SOURCE,"资格","model-a",1);String key=artifacts.keySet().iterator().next();
            Map<String,Object> incomplete=new LinkedHashMap<>(artifacts.get(key));incomplete.remove(missing);artifacts.put(key,incomplete);
            Plan fresh=compile(SOURCE,"资格","model-b",1);assertNotEquals("DETERMINISTIC_PACKAGE_REUSED",fresh.method(),missing);
            assertNotNull(artifacts.get(key).get(missing),missing);
        }
    }
}
