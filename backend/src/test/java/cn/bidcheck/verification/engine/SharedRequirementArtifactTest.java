package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class SharedRequirementArtifactTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String REGISTRATION="注册要求：具备履约能力的在中华人民共和国境内行政主管部门登记注册、根据中华人民共和国有关法律合法成立的独立法人或其他组织，经营范围未受限。须提交有效的营业执照或登记证书或其他有效证照。";
    private Document tender(String heading,String text,int version){return new Document("tender","project","TENDER","招标文件",version,"hash-"+version,"",List.of(new Page(65,heading,"PARAGRAPH","DOCX",""),new Page(66,text,"PARAGRAPH","DOCX","")),List.of());}
    private Checkpoint checkpoint(String id,String text){return new Checkpoint(id,"注册要求","资格检查","HIGH","SEMANTIC_RESPONSE","qualification","RESPONDS",text,"",true,true,true,new Evidence("tender","招标文件",66,"PARAGRAPH",text,"p66",1));}
    private JsonNode material(String text,String subject){return JSON.valueToTree(Map.of("logic","ALL","rules",List.of(Map.of("id","model-random-id","description","说明可能改写","predicate","MATERIAL","subject",subject,"expected","营业执照","sourceQuote",text))));}
    interface Request {JsonNode request(Map<String,Object> input)throws IOException;}
    private ModelGateway model(AtomicInteger calls,Request request){return new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "model-v1";}public JsonNode request(String operation,Map<String,Object> input)throws IOException{calls.incrementAndGet();return request.request(input);}};}
    private Map<String,Object> context(Document doc,String original,Map<String,Map<String,Object>> artifacts){Map<String,Object> ctx=new HashMap<>();ctx.put("originalRequirement",original);ctx.put("tenderDocuments",List.of(doc));ctx.put("requirementProjectId","project");ctx.put("focusedCheck",true);ctx.put("unlimitedModelCalls",true);ctx.put("allowModelRuleCompilation",true);
        ctx.put("requirementArtifactLookup",(Function<Map<String,Object>,Map<String,Object>>)request->artifacts.getOrDefault(str(request,"artifactId"),Map.of()));
        ctx.put("requirementArtifactPublisher",(Consumer<Map<String,Object>>)artifact->artifacts.put(str(artifact,"artifactId"),artifact));return ctx;}
    @Test void frozenRegistrationHeadingSuppliesSubjectWithoutChangingClause()throws Exception{
        Checkpoint cp=checkpoint("a",REGISTRATION);Document doc=tender("★投标人资格要求",REGISTRATION,1);AtomicInteger calls=new AtomicInteger();
        RunSession session=new RunSession(model(calls,in->material(REGISTRATION,"投标人")),context(doc,REGISTRATION,new HashMap<>()),"cp-a");Plan plan=new RequirementCompiler().compile(cp,session);
        assertEquals("MATERIAL",plan.rules().get(0).predicate());assertEquals(REGISTRATION,plan.rules().get(0).sourceQuote());assertEquals(REGISTRATION,cp.source().quote());
        JsonNode source=JSON.valueToTree(plan.requirementScope().get("sourceContext"));assertEquals("★投标人资格要求",source.path("sources").get(1).path("sourceQuote").asText());assertEquals(65,source.path("sources").get(1).path("location").asInt());
        assertFalse(str(plan.rules().get(0).parameters(),"obligationIdentity").isBlank());
    }
    @Test void interveningSectionAndOtherProjectCannotLendSubject()throws Exception{
        Document doc=new Document("tender","project","TENDER","招标文件",1,"hash","",List.of(new Page(64,"★投标人资格要求","PARAGRAPH","DOCX",""),new Page(65,"第三章 历史项目说明","PARAGRAPH","DOCX",""),new Page(66,REGISTRATION,"PARAGRAPH","DOCX","")),List.of());
        assertEquals(1,RequirementSourceContext.resolve(checkpoint("a",REGISTRATION),REGISTRATION,context(doc,REGISTRATION,new HashMap<>())).sources().size());
        Map<String,Object> wrong=context(tender("★投标人资格要求",REGISTRATION,1),REGISTRATION,new HashMap<>());wrong.put("requirementProjectId","another-project");assertTrue(RequirementSourceContext.resolve(checkpoint("a",REGISTRATION),REGISTRATION,wrong).identity().isEmpty());
    }
    @Test void sameRowHeaderIsEvidenceButAdjacentRowDoesNotSupplySubject(){
        String text="| 适用主体 | 要求 |\n| --- | --- |\n| 投标人 | 须提交营业执照 |\n|  | 须提交其他证明 |";
        Document doc=tender("",text,1);var own=RequirementSourceContext.resolve(checkpoint("a","须提交营业执照"),"须提交营业执照",context(doc,"须提交营业执照",new HashMap<>()));
        assertEquals("TABLE_ROW_SUBJECT",own.sources().get(1).get("kind"));
        var other=RequirementSourceContext.resolve(checkpoint("b","须提交其他证明"),"须提交其他证明",context(doc,"须提交其他证明",new HashMap<>()));assertEquals(1,other.sources().size());
    }
    @Test void validatedDocumentArtifactIsSharedAcrossCheckpointAndBidRun()throws Exception{
        AtomicInteger calls=new AtomicInteger();Map<String,Map<String,Object>> artifacts=new HashMap<>();Document doc=tender("★投标人资格要求",REGISTRATION,1);ModelGateway model=model(calls,in->material(REGISTRATION,"投标人"));
        Plan a=new RequirementCompiler().compile(checkpoint("a",REGISTRATION),new RunSession(model,context(doc,REGISTRATION,artifacts),"run-one/bid-one"));
        RunSession other=new RunSession(model,context(doc,REGISTRATION,artifacts),"run-two/bid-two");Plan b=new RequirementCompiler().compile(checkpoint("b",REGISTRATION),other);
        assertEquals(1,calls.get());assertEquals(1,other.resumedSteps);assertEquals(a.rules().get(0).parameters().get("obligationIdentity"),b.rules().get(0).parameters().get("obligationIdentity"));
        new RequirementCompiler().compile(checkpoint("a",REGISTRATION),new RunSession(model,context(tender("★投标人资格要求",REGISTRATION,2),REGISTRATION,artifacts),"version-two"));assertEquals(2,calls.get());
    }
    @Test void unresolvedBusinessPlanIsReusedAsGapWithoutRepeatingRepair()throws Exception{
        String text="须提交营业执照";AtomicInteger calls=new AtomicInteger();Map<String,Map<String,Object>> artifacts=new HashMap<>();Document doc=tender("",text,1);ModelGateway model=model(calls,in->material(text,"不存在的单位"));
        Plan a=new RequirementCompiler().compile(checkpoint("a",text),new RunSession(model,context(doc,text,artifacts),"a"));assertEquals(2,calls.get());assertFalse(CompilationRecovery.diagnostics(a).isEmpty());
        RunSession next=new RunSession(model,context(doc,text,artifacts),"b");Plan b=new RequirementCompiler().compile(checkpoint("b",text),next);assertEquals(2,calls.get());assertEquals("UNRESOLVED",next.compilationAudit.get("state"));assertFalse(CompilationRecovery.diagnostics(b).isEmpty());
    }
    @Test void transientInitialFailureDoesNotBecomePermanentBusinessCache()throws Exception{
        String text="投标人须提交营业执照";AtomicInteger calls=new AtomicInteger();Map<String,Map<String,Object>> artifacts=new HashMap<>();Document doc=tender("",text,1);
        ModelGateway failed=model(calls,in->{throw new IOException("temporary");});assertThrows(IOException.class,()->new RequirementCompiler().compile(checkpoint("a",text),new RunSession(failed,context(doc,text,artifacts),"a")));assertTrue(artifacts.isEmpty());
        Plan recovered=new RequirementCompiler().compile(checkpoint("a",text),new RunSession(model(calls,in->material(text,"投标人")),context(doc,text,artifacts),"b"));assertEquals("MATERIAL",recovered.rules().get(0).predicate());assertEquals(2,calls.get());
    }
    @Test void failedRepairHasCooldownAndResumesOnlyItsFailedNode()throws Exception{
        String text="须提交营业执照";AtomicInteger calls=new AtomicInteger();Map<String,Map<String,Object>> artifacts=new HashMap<>();Document doc=tender("",text,1);
        ModelGateway gateway=model(calls,in->{if(in.containsKey("task"))throw new IOException("temporary-repair");return material(text,"不存在单位");});
        Map<String,Object> first=context(doc,text,artifacts);first.put("requirementNowEpochMillis",1000L);new RequirementCompiler().compile(checkpoint("a",text),new RunSession(gateway,first,"a"));assertEquals(2,calls.get());
        Map<String,Object> same=context(doc,text,artifacts);same.put("requirementNowEpochMillis",2000L);new RequirementCompiler().compile(checkpoint("a",text),new RunSession(gateway,same,"b"));assertEquals(2,calls.get());
        Map<String,Object> later=context(doc,text,artifacts);later.put("requirementNowEpochMillis",62000L);new RequirementCompiler().compile(checkpoint("a",text),new RunSession(model(calls,in->{assertEquals("REPAIR_COMPILED_REQUIREMENT",in.get("task"));return material(text,"不存在单位");}),later,"c"));assertEquals(3,calls.get());
    }
    @Test void artifactFromDifferentSourceIdentityIsIgnored()throws Exception{
        String text="投标人须提交营业执照";AtomicInteger calls=new AtomicInteger();Map<String,Map<String,Object>> artifacts=new HashMap<>();Document doc=tender("",text,1);ModelGateway model=model(calls,in->material(text,"投标人"));
        new RequirementCompiler().compile(checkpoint("a",text),new RunSession(model,context(doc,text,artifacts),"a"));Map.Entry<String,Map<String,Object>> entry=artifacts.entrySet().iterator().next();Map<String,Object> forged=new HashMap<>(entry.getValue());forged.put("projectId","other-project");artifacts.put(entry.getKey(),forged);
        new RequirementCompiler().compile(checkpoint("a",text),new RunSession(model,context(doc,text,artifacts),"b"));assertEquals(2,calls.get());
    }
    @Test void modelIdentityChangeCannotReuseEarlierCompilation()throws Exception{
        String text="投标人须提交营业执照";AtomicInteger calls=new AtomicInteger();Map<String,Map<String,Object>> artifacts=new HashMap<>();Document doc=tender("",text,1);
        new RequirementCompiler().compile(checkpoint("a",text),new RunSession(model(calls,in->material(text,"投标人")),context(doc,text,artifacts),"a"));
        ModelGateway upgraded=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "model-v2";}public JsonNode request(String operation,Map<String,Object> input){calls.incrementAndGet();return material(text,"投标人");}};
        new RequirementCompiler().compile(checkpoint("a",text),new RunSession(upgraded,context(doc,text,artifacts),"b"));assertEquals(2,calls.get());assertEquals(2,artifacts.size());
    }
    @Test void reviewedScopeChangeCannotReuseOtherObligationInSameOriginalRow()throws Exception{
        String first="投标人须提交营业执照。",second="投标人须提交登记证书。",original=first+second;Document doc=tender("",original,1);Map<String,Map<String,Object>> artifacts=new HashMap<>();AtomicInteger calls=new AtomicInteger();
        ModelGateway gateway=model(calls,in->{String quote=str(in,"requirement");return JSON.valueToTree(Map.of("logic","ALL","rules",List.of(Map.of("id","same-model-id","predicate","MATERIAL","subject","投标人","expected",quote.contains("营业执照")?"营业执照":"登记证书","sourceQuote",quote))));});
        Checkpoint base=checkpoint("same-checkpoint",original);Checkpoint a=new Checkpoint(base.id(),base.title(),base.category(),base.severity(),base.type(),base.field(),base.operator(),first,base.unit(),true,true,true,base.source());
        Checkpoint b=new Checkpoint(base.id(),base.title(),base.category(),base.severity(),base.type(),base.field(),base.operator(),second,base.unit(),true,true,true,base.source());
        Plan before=new RequirementCompiler().compile(a,new RunSession(gateway,context(doc,original,artifacts),"a"));Plan after=new RequirementCompiler().compile(b,new RunSession(gateway,context(doc,original,artifacts),"b"));
        assertEquals(2,calls.get());assertEquals(2,artifacts.size());assertNotEquals(before.requirementScope().get("artifactId"),after.requirementScope().get("artifactId"));assertNotEquals(before.rules().get(0).parameters().get("obligationIdentity"),after.rules().get(0).parameters().get("obligationIdentity"));
    }
    @Test void retrievalExecutionsPreserveRepeatedDiagnostics(){RunSession s=new RunSession(model(new AtomicInteger(),in->JSON.createObjectNode()),Map.of(),"x");s.record("RETRIEVE_0",Map.of("query","same"),Map.of("diagnostics",Map.of("elapsedMs",12)));s.record("RETRIEVE_0",Map.of("query","same"),Map.of("diagnostics",Map.of("elapsedMs",14)));assertNotEquals(s.steps.get(0).get("id"),s.steps.get(1).get("id"));assertEquals(s.steps.get(0).get("inputFingerprint"),s.steps.get(1).get("inputFingerprint"));assertNotEquals(s.steps.get(0).get("executionId"),s.steps.get(1).get("executionId"));}
}
