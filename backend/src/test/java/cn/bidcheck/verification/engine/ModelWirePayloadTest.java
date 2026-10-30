package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelWirePayloadTest {
    final ObjectMapper json=new ObjectMapper();
    Map<String,Object> context(String doc,String hash,int page,int start){return linked("version",RequirementSourceContext.VERSION,"identity",linked("documentId",doc,"documentHash",hash,"documentVersion",1),"sources",List.of(linked("sourceId","T1","locationType","BLOCK","location",page,"start",start,"end",start+5,"quote","报价\n🧩原文；除以下情形。")));}
    Rule rule(String id,Map<String,Object> context){return new Rule(id,"报价约束","NUMBER","投标人","amount","<=","100","万元",linked("requirementSourceContext",context,"taxBasis","含税","amountBasis","总价","condition","仅当甲情形","lot","第一标段","employer","甲公司"),"报价不得超过100万元","",List.of());}
    Map<String,Object> input(){var c=context("tender","hash",1,0);return linked("rules",List.of(rule("a",c),rule("b",c)),"sources",List.of(linked("id","B1","documentId","bid","quote","投标人总价80万元"),linked("id","B2","documentId","bid","quote","投标人总价90万元")),"knownFacts",List.of(),"layoutCells",List.of(),"previousRejected",List.of());}
    JsonNode original(String operation,Map<String,Object> input){return json.valueToTree(Map.of("operation",operation,"input",input));}
    @Test void losslessUnicodeTreeNoMutationAndCanonicalMapOrder()throws Exception{
        var in=input();JsonNode before=json.valueToTree(in);var wire=ModelWirePayload.encode("EXTRACT",in);
        assertEquals(1,wire.path("wireFormat").path("sourceContexts").size());assertEquals(original("EXTRACT",in),ModelWirePayload.restore(wire));assertEquals(before,json.valueToTree(in));
        Map<String,Object> reordered=new LinkedHashMap<>();var c=context("tender","hash",1,0);reordered.put("sources",c.get("sources"));reordered.put("identity",c.get("identity"));reordered.put("version",c.get("version"));
        in.put("rules",List.of(rule("a",c),rule("b",reordered)));assertEquals(1,ModelWirePayload.encode("EXTRACT",in).path("wireFormat").path("sourceContexts").size());
        assertEquals(original("EXTRACT",in),ModelWirePayload.restore(ModelWirePayload.encode("EXTRACT",in)));
    }
    @Test void differentProvenanceNeverMergesDespiteSameSourceIdAndQuote(){
        List<Map<String,Object>> contexts=List.of(context("A","H",1,0),context("B","H",1,0),context("A","other",1,0),context("A","H",2,0),context("A","H",1,9));
        List<Rule> rules=new ArrayList<>();int n=0;for(var c:contexts){rules.add(rule("a"+n,c));rules.add(rule("b"+n++,c));}
        var in=input();in.put("rules",rules);var wire=ModelWirePayload.encode("EXTRACT",in);assertEquals(contexts.size(),wire.path("wireFormat").path("sourceContexts").size());assertEquals(original("EXTRACT",in),ModelWirePayload.restore(wire));
        var version=new LinkedHashMap<>(context("A","H",1,0));version.put("identity",Map.of("documentId","A","documentHash","H","documentVersion",2));in.put("rules",List.of(rule("a",contexts.get(0)),rule("b",version)));assertFalse(ModelWirePayload.encode("EXTRACT",in).has("wireFormat"));
    }
    @Test void reservedLiteralReferenceIsExplicitlyRejectedAndDictionaryTamperingFails(){
        var in=input();in.put("modelParameter",Map.of("$sourceContextRef","literal-do-not-expand"));assertEquals("WIRE_RESERVED_REFERENCE_IN_INPUT",assertThrows(IllegalArgumentException.class,()->ModelWirePayload.encode("EXTRACT",in)).getMessage());
        var wire=ModelWirePayload.encode("EXTRACT",input());var dict=(ObjectNode)wire.path("wireFormat").path("sourceContexts");String key=dict.fieldNames().next();((ObjectNode)dict.get(key).path("identity")).put("documentId","forged");assertThrows(IllegalArgumentException.class,()->ModelWirePayload.restore(wire));
        var missing=ModelWirePayload.encode("EXTRACT",input());((ObjectNode)missing.path("wireFormat").path("sourceContexts")).removeAll();assertThrows(IllegalArgumentException.class,()->ModelWirePayload.restore(missing));
    }
    @Test void logicalBranchesAndEveryParameterRemainIdentical(){
        var c=context("t","h",3,10);var leaf=rule("leaf",c);Rule conditional=new Rule("cond","适用条件","MATERIAL","联合体","agreement","","联合体协议书","",linked("requirementSourceContext",c,"requirementSubjectSet",List.of("甲公司","乙公司")),"如为联合体应提交协议书","CONDITIONAL",List.of(leaf,new Rule("yes","成立","GROUP","","","","","",Map.of(),"要求","ANY",List.of(leaf,rule("b",c))),new Rule("no","不成立","NOT_APPLICABLE","","","","","",Map.of(),"非联合体不适用","",List.of())));
        var in=input();in.put("rules",List.of(new Rule("all","合取","GROUP","","","","","",Map.of(),"完整父原文","ALL",List.of(conditional,new Rule("each","分别","GROUP","","","","","",Map.of("subjects",List.of("甲","乙")),"各成员","FOR_EACH",List.of(leaf))))));
        assertEquals(original("EXTRACT",in),ModelWirePayload.restore(ModelWirePayload.encode("EXTRACT",in)));
    }
    @Test void actualHttpUsesSameSizedProjectionAndKeepsFullLogicalRules()throws Exception{
        AtomicReference<JsonNode> request=new AtomicReference<>();var in=input();JsonNode original=json.valueToTree(in);
        VerificationModelClientTest.withServer(exchange->{request.set(json.readTree(exchange.getRequestBody()));byte[] bytes="{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"facts\\\":[]}\"}}]}}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();},url->{assertTrue(new VerificationModelClient(url,"local-key","model",Duration.ofSeconds(2)).request("EXTRACT",in).path("facts").isArray());});
        String sent=request.get().path("input").path("messages").get(1).path("content").asText();assertEquals(ModelWirePayload.serialize("EXTRACT",in),sent);assertEquals(sent.length(),ExtractionBatches.size(in));assertEquals(original,json.valueToTree(in));
        String system=request.get().path("input").path("messages").get(0).path("content").asText();
        assertAll(
                ()->assertTrue(system.contains("你只执行EXTRACT")),
                ()->assertTrue(system.contains("共享上下文不是投标证据")),
                ()->assertTrue(system.contains("不回传引用对象")),
                ()->assertTrue(system.contains("不生成检查规则")),
                ()->assertTrue(system.contains("程序代码或业务结论")),
                ()->assertFalse(system.contains("COMPILE:"))
        );
    }
    @Test void dictionaryCannotProvideBidEvidenceOrClaimReadCompletion(){
        var corpus=new EvidenceCorpus(List.of(SharedFactReuseTest.doc("bid","p",1,"投标人总价80万元")));var in=input();String dictionaryId=ModelWirePayload.encode("EXTRACT",in).path("wireFormat").path("sourceContexts").fieldNames().next();
        var forged=linked("kind","NUMBER","subject","投标人","field","amount","rawValue","80","value","80","unit","万元","sourceIds",List.of(dictionaryId));var verified=new ProofVerifier().verify(json.valueToTree(Map.of("facts",List.of(forged))),corpus.all);assertTrue(verified.facts().isEmpty());assertFalse(verified.rejected().isEmpty());
        var shared=new SharedFactReuse(corpus,Map.of(),"m","e");assertEquals(corpus.all.size(),shared.pendingSources(ExtractionBatches.rules(in).get(0),corpus.all).size());
    }
    @Test void repackingPreservesPendingMatrixAndOutsideMetadata(){
        var in=input();in.put("knownFacts",List.of(linked("id","FA","kind","NUMBER","sourceIds",List.of("B1")),linked("id","FB","kind","NUMBER","sourceIds",List.of("B2"))));in.put("layoutCells",List.of(linked("id","LA","sourceIds",List.of("B1")),linked("id","LB","sourceIds",List.of("B2"))));in.put("previousRejected",List.of(linked("id","RA","sourceIds",List.of("B1")),linked("id","RB","sourceIds",List.of("B2"))));
        var first=ExtractionBatches.plan(in,null,Map.of("a",Set.of("B1"),"b",Set.of("B1","B2")));assertEquals(2,first.batches().size());
        for(var packet:first.batches()){var again=ExtractionBatches.plan(packet,null,Map.of());assertEquals(1,again.batches().size());assertEquals(json.valueToTree(packet),json.valueToTree(again.batches().get(0)));assertEquals(original("EXTRACT",packet),ModelWirePayload.restore(ModelWirePayload.encode("EXTRACT",packet)));}
        assertEquals(Set.of("a@B1","b@B1","b@B2"),pairs(first.batches()));
    }
    @Test void incompleteAndInvalidOutputFromCompactedPacketStayUnreadWithDistinctRecovery()throws Exception{
        for(String reason:List.of("length","invalid")){var in=input();var corpus=new EvidenceCorpus(List.of(SharedFactReuseTest.doc("bid","p",1,"投标人总价80万元","投标人总价90万元")));in.put("sources",corpus.inputs(corpus.all));var initial=ExtractionBatches.plan(in,null,Map.of());assertEquals(1,initial.batches().size());assertTrue(ModelWirePayload.encode("EXTRACT",initial.batches().get(0)).has("wireFormat"));
            VerificationModelClientTest.withServer(exchange->{exchange.getRequestBody().readAllBytes();byte[] bytes=json.writeValueAsBytes(Map.of("output",Map.of("choices",List.of(Map.of("finish_reason",reason.equals("length")?"length":"stop","message",Map.of("content",reason.equals("length")?"{}":"{truncated"))))));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();},url->{var session=new RunSession(new VerificationModelClient(url,"local-key","model",Duration.ofSeconds(2)),Map.of("unlimitedModelCalls",true),"");var failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->session.model("EXTRACT",initial.batches().get(0)));assertEquals(reason.equals("length")?"OUTPUT_INCOMPLETE":"OUTPUT_JSON_INVALID",failure.code());assertEquals(reason.equals("length"),failure.splittable());assertTrue(session.steps.stream().noneMatch(s->"COMPLETED".equals(s.get("status"))));
                var shared=new SharedFactReuse(corpus,Map.of(),"m","e");shared.publishState(ExtractionBatches.rules(in),corpus.all,"FAILED",failure.code());assertTrue(shared.completedReads().isEmpty());for(var rule:ExtractionBatches.rules(in))assertEquals(2,shared.pendingSources(rule,corpus.all).size());assertTrue(shared.coverage().stream().noneMatch(c->"READ_NOT_FOUND".equals(c.get("status"))));var retry=ExtractionBatches.plan(in,"SPLIT_FAILED_REQUEST",Map.of());assertEquals(2,retry.batches().size());assertEquals(pairs(initial.batches()),pairs(retry.batches()));});
        }
    }
    @Test void oversizeOriginalSourceStillBlockedWithoutTruncation(){var in=input();in.put("sources",List.of(Map.of("id","B1","quote","原文".repeat(50_000))));var plan=ExtractionBatches.plan(in,null,Map.of());assertTrue(plan.batches().isEmpty());assertEquals(2,plan.blocked().size());for(var c:plan.coverage())assertEquals(Set.of("B1"),c.get("unplannedSourceIds"));}
    static Set<String> pairs(List<Map<String,Object>> packets){Set<String> out=new TreeSet<>();for(var p:packets)for(var r:ExtractionBatches.rules(p))for(var s:ExtractionBatches.sourceIds(p))out.add(r.id()+"@"+s);return out;}
}
