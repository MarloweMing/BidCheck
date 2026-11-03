package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises production Host construction and tool schemas, never a handwritten schema. */
class EvidenceInvestigationTargetContractTest {
    static Rule role(String id,String name){return new Rule(id,name,"PERSON_ROLE",name,"role","","","",cn.bidcheck.compat.Java8Api.mapOf(),"本项目应配置"+name,"",cn.bidcheck.compat.Java8Api.listOf());}
    static EvidenceInvestigation.Host host(){
        Rule a=role("A","项目负责人"),b=role("B","施工负责人"),c=role("C","技术负责人");
        Rule any=new Rule("any","替代岗位","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"项目负责人或施工负责人","ANY",cn.bidcheck.compat.Java8Api.listOf(a, b));
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(any, c),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());
        Checkpoint cp=new Checkpoint("contract-cp","岗位人员","资格","HIGH","SEMANTIC_RESPONSE","","","本项目应配置岗位人员","",true,true,true,null);
        EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","project",1,"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲")));
        Map<String,Object> context=new LinkedHashMap<>();
        return new EvidenceInvestigation.Host(cp,plan,cn.bidcheck.compat.Java8Api.listOf(a, b, c),corpus,new LinkedHashMap<>(),context,new SharedFactReuse(corpus,context,"fixture","fixture"),new RunSession(ModelGateway.disabled(),context,"contract-fixture"),null,new ArrayList<>(),cn.bidcheck.compat.Java8Api.setOf());
    }
    @SuppressWarnings("unchecked") static Map<String,Object> context(Map<String,Object> request){return (Map<String,Object>)request.get("context");}
    @Test void realToolsOfferOptionalTargetForAllThreeReadsAndNoTargetForRecompute(){
        java.util.Map<java.lang.String, java.lang.Object> request=host().start();
        for(Map<String,Object> tool:RuleEvaluator.maps(request.get("tools"))){
            Map<?,?> parameters=(Map<?,?>)tool.get("parameters"),properties=(Map<?,?>)parameters.get("properties");
            String name=tool.get("name").toString();assertEquals(false,parameters.get("additionalProperties"));
            if(cn.bidcheck.compat.Java8Api.setOf("search_evidence", "read_section", "read_table_record").contains(name)){
                assertEquals(cn.bidcheck.compat.Java8Api.listOf("A", "B", "C"),((Map<?,?>)properties.get("targetObligationId")).get("enum"));
                assertFalse(((List<?>)parameters.get("required")).contains("targetObligationId"));
            }else if(name.equals("recompute_check")){assertEquals(cn.bidcheck.compat.Java8Api.mapOf(),properties);assertEquals(cn.bidcheck.compat.Java8Api.listOf(),parameters.get("required"));}
        }
    }
    @Test void initialCanonicalPartitionComesFromCurrentEvaluatorAndMatchesRecompute(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host();java.util.Map<java.lang.String, java.lang.Object> initial=context(h.start());java.util.Map<java.lang.String, java.lang.Object> receipt=h.recompute();
        assertEquals("CURRENT_SERVER_PROOF",initial.get("targetReceiptAuthority"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),initial.get("completedTargetObligationIds"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("A", "B", "C"),initial.get("remainingTargetObligationIds"));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("targetReceiptAuthority", "completedTargetObligationIds", "remainingTargetObligationIds", "serverPlanRevision", "serverPlanFingerprint", "targetEvidenceRevision", "targetEvidenceRevisions"))assertEquals(receipt.get(key),initial.get(key),key);
    }
    @Test void startUsesCurrentAnyProofRatherThanFlattenedChildStatus(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host();h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));java.util.Map<java.lang.String, java.lang.Object> initial=context(h.start());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("A", "B"),initial.get("completedTargetObligationIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("C"),initial.get("remainingTargetObligationIds"));
        assertEquals(false,initial.get("targetCheckCompleted"));assertEquals(false,initial.get("businessCheckCompleted"));
    }
    @Test void closedAndForgedReadTargetsNeverReachSourceNavigation(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host();h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));h.start();h.disclosed.clear();
        String source=h.corpus.all.get(0).id();
        for(String tool:cn.bidcheck.compat.Java8Api.listOf("search_evidence", "read_section", "read_table_record"))for(String target:cn.bidcheck.compat.Java8Api.listOf("A", "B", "forged")){
            Map<String,Source> navigationBefore=new LinkedHashMap<>(h.navigation);
            Map<String,Object> arguments=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", target));arguments.put(tool.equals("search_evidence")?"query":"sourceId",tool.equals("search_evidence")?"项目负责人":source);
            java.util.Map<java.lang.String, java.lang.Object> receipt=h.call(tool,arguments);assertEquals(target.equals("forged")?"INVALID_TARGET_RECEIPT":"TARGET_STATE_UNCHANGED",receipt.get("error"));
            assertTrue(h.disclosed.isEmpty());assertEquals(navigationBefore,h.navigation,"rejected calls must not add navigation beyond the legitimate initial server receipt");
        }
    }
    @Test void remainingTargetReadRunsAndRecomputeStaysArgumentFree(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host();h.start();java.util.Map<java.lang.String, java.lang.Object> first=h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人", "targetObligationId", "A"));
        assertEquals("COMPLETED",first.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("A", "B"),first.get("completedTargetObligationIds"));
        String source=((List<String>)first.get("sourceIds")).get(0);
        assertEquals("COMPLETED",h.call("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source, "targetObligationId", "C")).get("status"));
        assertEquals("COMPLETED",h.call("recompute_check",cn.bidcheck.compat.Java8Api.mapOf()).get("status"));
        assertEquals("TOOL_ARGUMENT_SCHEMA_MISMATCH",h.call("recompute_check",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "C")).get("error"));
    }
    @Test void closedTargetCannotSubmitEvenWhenFactWouldBeValid(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host();h.start();java.util.Map<java.lang.String, java.lang.Object> first=h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人", "targetObligationId", "A"));int before=h.facts.size();
        java.util.Map<java.lang.String, java.lang.Object> proposal=linked("kind","PERSON_ROLE","subject","项目负责人","field","role","rawValue","测试甲","value","测试甲","scope","CURRENT_PROJECT","sourceIds",first.get("sourceIds"));
        assertEquals("TARGET_STATE_UNCHANGED",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "A", "fact", proposal)).get("error"));assertEquals(before,h.facts.size());
    }
    private static final String CROSS_COMPANY="北京契约测试设备有限公司",CROSS_CATEGORY="制造商授权",CROSS_NUMBER="AUTH-TEST-2026";
    static EvidenceInvestigation.Host crossBlockHost(){
        Rule certificate=new Rule("CERT","制造商授权证书","CERTIFICATE",CROSS_COMPANY,"证书","","","",cn.bidcheck.compat.Java8Api.mapOf("category", CROSS_CATEGORY),"须提交北京契约测试设备有限公司的制造商授权证书","",cn.bidcheck.compat.Java8Api.listOf());
        Rule outside=role("OUTSIDE","技术负责人");
        String certificateText="制造商授权证书\n企业名称："+CROSS_COMPANY+"\n证书编号："+CROSS_NUMBER+"\n本证书证明该单位具备本制造商的设备经销授权。授权类别：制造商授权。";
        String queryText="制造商授权查询材料\n查询网址：https://authorization.example.invalid/result\n| 核验字段 | 查询记录 |\n| 组织名称 | "+CROSS_COMPANY+" |\n| 证书编号 | "+CROSS_NUMBER+" |\n| 授权类别 | "+CROSS_CATEGORY+" |\n| 查询结果 | 记录存在 |";
        Document document=new Document("cross-bid","cross-project","BID","跨块授权测试材料",1,"cross-bid-hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"投标文件\n项目名称：跨块契约测试工程","PAGE","TEXT",""), new Page(2,certificateText,"PAGE","TEXT",""), new Page(3,queryText,"PAGE","TEXT","")),cn.bidcheck.compat.Java8Api.listOf());
        EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(document));Map<String,Object> ctx=new LinkedHashMap<>();
        List<Map<String,Object>> cells=new ArrayList<>();String[][] values={{"核验字段","查询记录"},{"组织名称",CROSS_COMPANY},{"证书编号",CROSS_NUMBER},{"授权类别",CROSS_CATEGORY},{"查询结果","记录存在"}};
        for(int row=0;row<values.length;row++)for(int column=0;column<2;column++)cells.add(linked("documentId",document.id(),"documentHash",document.sha256(),"documentVersion",1,"locationType","PAGE","location",3,
            "spanId","query-span-"+row+"-"+column,"cellId","query-cell-"+row+"-"+column,"kind","TABLE_CELL","source","OOXML_NATIVE","tableId","query-table","row",row+1,"column",column+1,"rowSpan",1,"columnSpan",1,"text",values[row][column]));
        // Synthetic source-backed stored table records stand in for the document parser, not model receipts.
        ctx.put("layoutLookup",(java.util.function.Function<Map<String,Object>,List<Map<String,Object>>>)request->Objects.equals(request.get("location"),3)?cells:cn.bidcheck.compat.Java8Api.listOf());
        Checkpoint cp=new Checkpoint("cross-contract-cp","授权证书及共同岗位","资格","HIGH","SEMANTIC_RESPONSE","","","制造商授权证书；另需技术负责人","",true,true,true,null);
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(certificate, outside),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());
        return new EvidenceInvestigation.Host(cp,plan,cn.bidcheck.compat.Java8Api.listOf(certificate, outside),corpus,new LinkedHashMap<>(),ctx,new SharedFactReuse(corpus,ctx,"fixture","fixture"),new RunSession(ModelGateway.disabled(),ctx,"cross-contract-fixture"),null,new ArrayList<>(),cn.bidcheck.compat.Java8Api.setOf());
    }
    static Map<String,Object> crossBlockFixture(){
        return crossBlockFixture(false);
    }
    static Map<String,Object> crossBlockFixture(boolean readQuerySection){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=crossBlockHost();Map<String,Object> start=h.start();List<Map<String,Object>> sequence=new ArrayList<>();
        java.util.function.BiFunction<String,Map<String,Object>,Map<String,Object>> call=(name,arguments)->{Map<String,Object> result=h.call(name,arguments);sequence.add(linked("name",name,"arguments",arguments,"result",result));return result;};
        call.apply("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "制造商授权 证书 查询材料", "targetObligationId", "CERT"));
        String certificate=h.corpus.all.stream().filter(s->s.evidence().page()==2).findFirst().orElseThrow(java.util.NoSuchElementException::new).id();
        String query=h.corpus.all.stream().filter(s->s.evidence().page()==3).findFirst().orElseThrow(java.util.NoSuchElementException::new).id();
        call.apply("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", certificate, "targetObligationId", "CERT"));
        if(readQuerySection)call.apply("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", query, "targetObligationId", "CERT"));
        call.apply("read_table_record",cn.bidcheck.compat.Java8Api.mapOf("sourceId", query, "targetObligationId", "CERT"));
        Map<String,Object> fact=linked("kind","CERTIFICATE","subject",CROSS_COMPANY,"field","证书","rawValue",CROSS_NUMBER,"value",CROSS_NUMBER,"scope","UNRESOLVED",
            "sourceIds",cn.bidcheck.compat.Java8Api.listOf(certificate, query),"attributes",cn.bidcheck.compat.Java8Api.mapOf("category", CROSS_CATEGORY, "number", CROSS_NUMBER, "numberField", "证书编号"));
        call.apply("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", fact, "targetObligationId", "CERT"));
        call.apply("recompute_check",cn.bidcheck.compat.Java8Api.mapOf());
        return linked("start",start,"sequence",sequence,"certificateSourceId",certificate,"querySourceId",query,"proofScope","SOURCE_GROUNDED_AUTHORIZATION_CERTIFICATE_CATEGORY_AND_NUMBER_ONLY",
            "limitations",cn.bidcheck.compat.Java8Api.listOf("Synthetic documents and stored table cells; no real bidder conclusion.", "Does not verify external website, certificate validity period, or independently prove a required query screenshot.", "OUTSIDE remains open so certificate closure is distinct from whole-check completion."));
    }
    @Test void realHostAllowsMultipleZeroFactReadsBeforeCrossSourceCertificateSubmission(){
        java.util.Map<java.lang.String, java.lang.Object> fixture=crossBlockFixture();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> steps=RuleEvaluator.maps(fixture.get("sequence"));assertEquals(5,steps.size());
        for(int i=0;i<3;i++){Map<?,?> result=(Map<?,?>)steps.get(i).get("result");assertEquals("COMPLETED",result.get("status"),result.toString());assertTrue(ProofVerifier.strings(result.get("acceptedFactIds")).isEmpty());assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("completedTargetObligationIds"));}
        Map<?,?> table=(Map<?,?>)steps.get(2).get("result");assertEquals(10,((List<?>)table.get("rows")).size());
        Map<?,?> submitted=(Map<?,?>)steps.get(3).get("result");assertEquals("COMPLETED",submitted.get("status"),submitted.toString());assertEquals(1,((List<?>)submitted.get("acceptedFactIds")).size());
        Map<?,?> receipt=(Map<?,?>)steps.get(4).get("result");assertEquals(cn.bidcheck.compat.Java8Api.listOf("CERT"),receipt.get("completedTargetObligationIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("OUTSIDE"),receipt.get("remainingTargetObligationIds"));assertEquals(false,receipt.get("businessCheckCompleted"));
    }
    @Test void realHostTwoAlreadyDisclosedSectionReadsStillPermitTableAndCrossSourceSubmission(){
        java.util.Map<java.lang.String, java.lang.Object> fixture=crossBlockFixture(true);java.util.List<java.util.Map<java.lang.String, java.lang.Object>> steps=RuleEvaluator.maps(fixture.get("sequence"));assertEquals(6,steps.size());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("search_evidence", "read_section", "read_section", "read_table_record", "submit_candidate_fact", "recompute_check"),cn.bidcheck.compat.Java8Api.toList(steps.stream().map(step->str(step,"name"))));
        Map<?,?> search=(Map<?,?>)steps.get(0).get("result");Set<String> disclosed=new HashSet<>(ProofVerifier.strings(search.get("sourceIds")));
        assertTrue(disclosed.contains(fixture.get("certificateSourceId")));assertTrue(disclosed.contains(fixture.get("querySourceId")));
        for(int i=0;i<4;i++){Map<?,?> result=(Map<?,?>)steps.get(i).get("result");assertEquals("COMPLETED",result.get("status"),result.toString());assertTrue(ProofVerifier.strings(result.get("acceptedFactIds")).isEmpty());assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("completedTargetObligationIds"));}
        for(int i=1;i<=2;i++){Map<?,?> result=(Map<?,?>)steps.get(i).get("result");assertTrue(disclosed.containsAll(ProofVerifier.strings(result.get("sourceIds"))),"section body was already disclosed by search; each read adds no new source body");}
        Map<?,?> submitted=(Map<?,?>)steps.get(4).get("result");assertEquals("COMPLETED",submitted.get("status"),submitted.toString());assertEquals(1,((List<?>)submitted.get("acceptedFactIds")).size());
        Map<?,?> receipt=(Map<?,?>)steps.get(5).get("result");assertEquals(cn.bidcheck.compat.Java8Api.listOf("CERT"),receipt.get("completedTargetObligationIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("OUTSIDE"),receipt.get("remainingTargetObligationIds"));assertEquals(false,receipt.get("businessCheckCompleted"));
    }
    public static void main(String[] args)throws Exception{
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host();java.util.Map<java.lang.String, java.lang.Object> start=h.start();java.util.Map<java.lang.String, java.lang.Object> recompute=h.recompute();
        java.util.Map<java.lang.String, java.lang.Object> read=h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人", "targetObligationId", "A"));java.util.Map<java.lang.String, java.lang.Object> updated=h.recompute();
        Map<String,Object> fixture=linked("fixtureVersion","real-host-target-contract-1","generator","EvidenceInvestigation.Host.start/tools/call/recompute","usesExternalModel",false,
            "start",start,"recompute",recompute,"initialReceipt",recompute,"updatedReceipt",updated,"partiallyClosedStart",h.start(),"readResponse",read);
        java.util.Map<java.lang.String, java.lang.Object> cross=crossBlockFixture();fixture.put("crossBlockStart",cross.get("start"));fixture.put("crossBlockSequence",cross.get("sequence"));fixture.put("crossBlockScenario",cross);
        java.util.Map<java.lang.String, java.lang.Object> repeated=crossBlockFixture(true);fixture.put("crossBlockRepeatedReadStart",repeated.get("start"));fixture.put("crossBlockRepeatedReadSequence",repeated.get("sequence"));fixture.put("crossBlockRepeatedReadScenario",repeated);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Paths.get(args[0]).toFile(),fixture);
    }
}
