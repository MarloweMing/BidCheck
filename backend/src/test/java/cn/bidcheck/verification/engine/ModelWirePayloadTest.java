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
    Map<String,Object> context(String doc,String hash,int page,int start){return linked("version",RequirementSourceContext.VERSION,"identity",linked("documentId",doc,"documentHash",hash,"documentVersion",1),"sources",cn.bidcheck.compat.Java8Api.listOf(linked("sourceId","T1","locationType","BLOCK","location",page,"start",start,"end",start+5,"quote","报价\n🧩原文；除以下情形。")));}
    Rule rule(String id,Map<String,Object> context){return new Rule(id,"报价约束","NUMBER","投标人","amount","<=","100","万元",linked("requirementSourceContext",context,"taxBasis","含税","amountBasis","总价","condition","仅当甲情形","lot","第一标段","employer","甲公司"),"报价不得超过100万元","",cn.bidcheck.compat.Java8Api.listOf());}
    Map<String,Object> input(){java.util.Map<java.lang.String, java.lang.Object> c=context("tender","hash",1,0);return linked("rules",cn.bidcheck.compat.Java8Api.listOf(rule("a",c), rule("b",c)),"sources",cn.bidcheck.compat.Java8Api.listOf(linked("id","B1","documentId","bid","quote","投标人总价80万元"), linked("id","B2","documentId","bid","quote","投标人总价90万元")),"knownFacts",cn.bidcheck.compat.Java8Api.listOf(),"layoutCells",cn.bidcheck.compat.Java8Api.listOf(),"previousRejected",cn.bidcheck.compat.Java8Api.listOf());}
    JsonNode original(String operation,Map<String,Object> input){return json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("operation", operation, "input", input));}
    @Test void losslessUnicodeTreeNoMutationAndCanonicalMapOrder()throws Exception{
        java.util.Map<java.lang.String, java.lang.Object> in=input();JsonNode before=json.valueToTree(in);com.fasterxml.jackson.databind.node.ObjectNode wire=ModelWirePayload.encode("EXTRACT",in);
        assertEquals(1,wire.path("wireFormat").path("sourceContexts").size());assertEquals(original("EXTRACT",in),ModelWirePayload.restore(wire));assertEquals(before,json.valueToTree(in));
        Map<String,Object> reordered=new LinkedHashMap<>();java.util.Map<java.lang.String, java.lang.Object> c=context("tender","hash",1,0);reordered.put("sources",c.get("sources"));reordered.put("identity",c.get("identity"));reordered.put("version",c.get("version"));
        in.put("rules",cn.bidcheck.compat.Java8Api.listOf(rule("a",c), rule("b",reordered)));assertEquals(1,ModelWirePayload.encode("EXTRACT",in).path("wireFormat").path("sourceContexts").size());
        assertEquals(original("EXTRACT",in),ModelWirePayload.restore(ModelWirePayload.encode("EXTRACT",in)));
    }
    @Test void differentProvenanceNeverMergesDespiteSameSourceIdAndQuote(){
        List<Map<String,Object>> contexts=cn.bidcheck.compat.Java8Api.listOf(context("A","H",1,0), context("B","H",1,0), context("A","other",1,0), context("A","H",2,0), context("A","H",1,9));
        List<Rule> rules=new ArrayList<>();int n=0;for(java.util.Map<java.lang.String, java.lang.Object> c:contexts){rules.add(rule("a"+n,c));rules.add(rule("b"+n++,c));}
        java.util.Map<java.lang.String, java.lang.Object> in=input();in.put("rules",rules);com.fasterxml.jackson.databind.node.ObjectNode wire=ModelWirePayload.encode("EXTRACT",in);assertEquals(contexts.size(),wire.path("wireFormat").path("sourceContexts").size());assertEquals(original("EXTRACT",in),ModelWirePayload.restore(wire));
        java.util.LinkedHashMap<java.lang.String, java.lang.Object> version=new LinkedHashMap<>(context("A","H",1,0));version.put("identity",cn.bidcheck.compat.Java8Api.mapOf("documentId", "A", "documentHash", "H", "documentVersion", 2));in.put("rules",cn.bidcheck.compat.Java8Api.listOf(rule("a",contexts.get(0)), rule("b",version)));assertFalse(ModelWirePayload.encode("EXTRACT",in).has("wireFormat"));
    }
    @Test void reservedLiteralReferenceIsExplicitlyRejectedAndDictionaryTamperingFails(){
        java.util.Map<java.lang.String, java.lang.Object> in=input();in.put("modelParameter",cn.bidcheck.compat.Java8Api.mapOf("$sourceContextRef", "literal-do-not-expand"));assertEquals("WIRE_RESERVED_REFERENCE_IN_INPUT",assertThrows(IllegalArgumentException.class,()->ModelWirePayload.encode("EXTRACT",in)).getMessage());
        com.fasterxml.jackson.databind.node.ObjectNode wire=ModelWirePayload.encode("EXTRACT",input());com.fasterxml.jackson.databind.node.ObjectNode dict=(ObjectNode)wire.path("wireFormat").path("sourceContexts");String key=dict.fieldNames().next();((ObjectNode)dict.get(key).path("identity")).put("documentId","forged");assertThrows(IllegalArgumentException.class,()->ModelWirePayload.restore(wire));
        com.fasterxml.jackson.databind.node.ObjectNode missing=ModelWirePayload.encode("EXTRACT",input());((ObjectNode)missing.path("wireFormat").path("sourceContexts")).removeAll();assertThrows(IllegalArgumentException.class,()->ModelWirePayload.restore(missing));
    }
    @Test void logicalBranchesAndEveryParameterRemainIdentical(){
        java.util.Map<java.lang.String, java.lang.Object> c=context("t","h",3,10);cn.bidcheck.verification.engine.VerificationTypes.Rule leaf=rule("leaf",c);Rule conditional=new Rule("cond","适用条件","MATERIAL","联合体","agreement","","联合体协议书","",linked("requirementSourceContext",c,"requirementSubjectSet",cn.bidcheck.compat.Java8Api.listOf("甲公司", "乙公司")),"如为联合体应提交协议书","CONDITIONAL",cn.bidcheck.compat.Java8Api.listOf(leaf, new Rule("yes","成立","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"要求","ANY",cn.bidcheck.compat.Java8Api.listOf(leaf, rule("b",c))), new Rule("no","不成立","NOT_APPLICABLE","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"非联合体不适用","",cn.bidcheck.compat.Java8Api.listOf())));
        java.util.Map<java.lang.String, java.lang.Object> in=input();in.put("rules",cn.bidcheck.compat.Java8Api.listOf(new Rule("all","合取","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"完整父原文","ALL",cn.bidcheck.compat.Java8Api.listOf(conditional, new Rule("each","分别","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf("subjects", cn.bidcheck.compat.Java8Api.listOf("甲", "乙")),"各成员","FOR_EACH",cn.bidcheck.compat.Java8Api.listOf(leaf))))));
        assertEquals(original("EXTRACT",in),ModelWirePayload.restore(ModelWirePayload.encode("EXTRACT",in)));
    }
    @Test void actualHttpUsesSameSizedProjectionAndKeepsFullLogicalRules()throws Exception{
        AtomicReference<JsonNode> request=new AtomicReference<>();java.util.Map<java.lang.String, java.lang.Object> in=input();JsonNode original=json.valueToTree(in);
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
        cn.bidcheck.verification.engine.EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","p",1,"投标人总价80万元")));java.util.Map<java.lang.String, java.lang.Object> in=input();String dictionaryId=ModelWirePayload.encode("EXTRACT",in).path("wireFormat").path("sourceContexts").fieldNames().next();
        java.util.Map<java.lang.String, java.lang.Object> forged=linked("kind","NUMBER","subject","投标人","field","amount","rawValue","80","value","80","unit","万元","sourceIds",cn.bidcheck.compat.Java8Api.listOf(dictionaryId));cn.bidcheck.verification.engine.ProofVerifier.Verified verified=new ProofVerifier().verify(json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(forged))),corpus.all);assertTrue(verified.facts().isEmpty());assertFalse(verified.rejected().isEmpty());
        cn.bidcheck.verification.engine.SharedFactReuse shared=new SharedFactReuse(corpus,cn.bidcheck.compat.Java8Api.mapOf(),"m","e");assertEquals(corpus.all.size(),shared.pendingSources(ExtractionBatches.rules(in).get(0),corpus.all).size());
    }
    @Test void repackingPreservesPendingMatrixAndOutsideMetadata(){
        java.util.Map<java.lang.String, java.lang.Object> in=input();in.put("knownFacts",cn.bidcheck.compat.Java8Api.listOf(linked("id","FA","kind","NUMBER","sourceIds",cn.bidcheck.compat.Java8Api.listOf("B1")), linked("id","FB","kind","NUMBER","sourceIds",cn.bidcheck.compat.Java8Api.listOf("B2"))));in.put("layoutCells",cn.bidcheck.compat.Java8Api.listOf(linked("id","LA","sourceIds",cn.bidcheck.compat.Java8Api.listOf("B1")), linked("id","LB","sourceIds",cn.bidcheck.compat.Java8Api.listOf("B2"))));in.put("previousRejected",cn.bidcheck.compat.Java8Api.listOf(linked("id","RA","sourceIds",cn.bidcheck.compat.Java8Api.listOf("B1")), linked("id","RB","sourceIds",cn.bidcheck.compat.Java8Api.listOf("B2"))));
        cn.bidcheck.verification.engine.ExtractionBatches.Plan first=ExtractionBatches.plan(in,null,cn.bidcheck.compat.Java8Api.mapOf("a", cn.bidcheck.compat.Java8Api.setOf("B1"), "b", cn.bidcheck.compat.Java8Api.setOf("B1", "B2")));assertEquals(2,first.batches().size());
        for(java.util.Map<java.lang.String, java.lang.Object> packet:first.batches()){cn.bidcheck.verification.engine.ExtractionBatches.Plan again=ExtractionBatches.plan(packet,null,cn.bidcheck.compat.Java8Api.mapOf());assertEquals(1,again.batches().size());assertEquals(json.valueToTree(packet),json.valueToTree(again.batches().get(0)));assertEquals(original("EXTRACT",packet),ModelWirePayload.restore(ModelWirePayload.encode("EXTRACT",packet)));}
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("a@B1", "b@B1", "b@B2"),pairs(first.batches()));
    }
    @Test void incompleteAndInvalidOutputFromCompactedPacketStayUnreadWithDistinctRecovery()throws Exception{
        for(String reason:cn.bidcheck.compat.Java8Api.listOf("length", "invalid")){java.util.Map<java.lang.String, java.lang.Object> in=input();cn.bidcheck.verification.engine.EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","p",1,"投标人总价80万元","投标人总价90万元")));in.put("sources",corpus.inputs(corpus.all));cn.bidcheck.verification.engine.ExtractionBatches.Plan initial=ExtractionBatches.plan(in,null,cn.bidcheck.compat.Java8Api.mapOf());assertEquals(1,initial.batches().size());assertTrue(ModelWirePayload.encode("EXTRACT",initial.batches().get(0)).has("wireFormat"));
            VerificationModelClientTest.withServer(exchange->{cn.bidcheck.compat.Java8Api.readAllBytes(exchange.getRequestBody());byte[] bytes=json.writeValueAsBytes(cn.bidcheck.compat.Java8Api.mapOf("output", cn.bidcheck.compat.Java8Api.mapOf("choices", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("finish_reason", reason.equals("length")?"length":"stop", "message", cn.bidcheck.compat.Java8Api.mapOf("content", reason.equals("length")?"{}":"{truncated"))))));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();},url->{cn.bidcheck.verification.engine.RunSession session=new RunSession(new VerificationModelClient(url,"local-key","model",Duration.ofSeconds(2)),cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true),"");cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->session.model("EXTRACT",initial.batches().get(0)));assertEquals(reason.equals("length")?"OUTPUT_INCOMPLETE":"OUTPUT_JSON_INVALID",failure.code());assertEquals(reason.equals("length"),failure.splittable());assertTrue(session.steps.stream().noneMatch(s->"COMPLETED".equals(s.get("status"))));
                cn.bidcheck.verification.engine.SharedFactReuse shared=new SharedFactReuse(corpus,cn.bidcheck.compat.Java8Api.mapOf(),"m","e");shared.publishState(ExtractionBatches.rules(in),corpus.all,"FAILED",failure.code());assertTrue(shared.completedReads().isEmpty());for(cn.bidcheck.verification.engine.VerificationTypes.Rule rule:ExtractionBatches.rules(in))assertEquals(2,shared.pendingSources(rule,corpus.all).size());assertTrue(shared.coverage().stream().noneMatch(c->"READ_NOT_FOUND".equals(c.get("status"))));cn.bidcheck.verification.engine.ExtractionBatches.Plan retry=ExtractionBatches.plan(in,"SPLIT_FAILED_REQUEST",cn.bidcheck.compat.Java8Api.mapOf());assertEquals(2,retry.batches().size());assertEquals(pairs(initial.batches()),pairs(retry.batches()));});
        }
    }
    @Test void oversizeOriginalSourceStillBlockedWithoutTruncation(){java.util.Map<java.lang.String, java.lang.Object> in=input();in.put("sources",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "B1", "quote", cn.bidcheck.compat.Java8Api.repeat("原文", 50_000))));cn.bidcheck.verification.engine.ExtractionBatches.Plan plan=ExtractionBatches.plan(in,null,cn.bidcheck.compat.Java8Api.mapOf());assertTrue(plan.batches().isEmpty());assertEquals(2,plan.blocked().size());for(java.util.Map<java.lang.String, java.lang.Object> c:plan.coverage())assertEquals(cn.bidcheck.compat.Java8Api.setOf("B1"),c.get("unplannedSourceIds"));}
    static Set<String> pairs(List<Map<String,Object>> packets){Set<String> out=new TreeSet<>();for(java.util.Map<java.lang.String, java.lang.Object> p:packets)for(cn.bidcheck.verification.engine.VerificationTypes.Rule r:ExtractionBatches.rules(p))for(java.lang.String s:ExtractionBatches.sourceIds(p))out.add(r.id()+"@"+s);return out;}
}
