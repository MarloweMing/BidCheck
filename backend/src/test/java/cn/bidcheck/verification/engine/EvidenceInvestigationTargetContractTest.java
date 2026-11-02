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
    static Rule role(String id,String name){return new Rule(id,name,"PERSON_ROLE",name,"role","","","",Map.of(),"本项目应配置"+name,"",List.of());}
    static EvidenceInvestigation.Host host(){
        Rule a=role("A","项目负责人"),b=role("B","施工负责人"),c=role("C","技术负责人");
        Rule any=new Rule("any","替代岗位","GROUP","","","","","",Map.of(),"项目负责人或施工负责人","ANY",List.of(a,b));
        Plan plan=new Plan("ALL",List.of(any,c),"FIXTURE",List.of());
        Checkpoint cp=new Checkpoint("contract-cp","岗位人员","资格","HIGH","SEMANTIC_RESPONSE","","","本项目应配置岗位人员","",true,true,true,null);
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(SharedFactReuseTest.doc("bid","project",1,"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲")));
        Map<String,Object> context=new LinkedHashMap<>();
        return new EvidenceInvestigation.Host(cp,plan,List.of(a,b,c),corpus,new LinkedHashMap<>(),context,new SharedFactReuse(corpus,context,"fixture","fixture"),new RunSession(ModelGateway.disabled(),context,"contract-fixture"),null,new ArrayList<>(),Set.of());
    }
    @SuppressWarnings("unchecked") static Map<String,Object> context(Map<String,Object> request){return (Map<String,Object>)request.get("context");}
    @Test void realToolsOfferOptionalTargetForAllThreeReadsAndNoTargetForRecompute(){
        var request=host().start();
        for(Map<String,Object> tool:RuleEvaluator.maps(request.get("tools"))){
            Map<?,?> parameters=(Map<?,?>)tool.get("parameters"),properties=(Map<?,?>)parameters.get("properties");
            String name=tool.get("name").toString();assertEquals(false,parameters.get("additionalProperties"));
            if(Set.of("search_evidence","read_section","read_table_record").contains(name)){
                assertEquals(List.of("A","B","C"),((Map<?,?>)properties.get("targetObligationId")).get("enum"));
                assertFalse(((List<?>)parameters.get("required")).contains("targetObligationId"));
            }else if(name.equals("recompute_check")){assertEquals(Map.of(),properties);assertEquals(List.of(),parameters.get("required"));}
        }
    }
    @Test void initialCanonicalPartitionComesFromCurrentEvaluatorAndMatchesRecompute(){
        var h=host();var initial=context(h.start());var receipt=h.recompute();
        assertEquals("CURRENT_SERVER_PROOF",initial.get("targetReceiptAuthority"));assertEquals(List.of(),initial.get("completedTargetObligationIds"));
        assertEquals(List.of("A","B","C"),initial.get("remainingTargetObligationIds"));
        for(String key:List.of("targetReceiptAuthority","completedTargetObligationIds","remainingTargetObligationIds","serverPlanRevision","serverPlanFingerprint","targetEvidenceRevision","targetEvidenceRevisions"))assertEquals(receipt.get(key),initial.get(key),key);
    }
    @Test void startUsesCurrentAnyProofRatherThanFlattenedChildStatus(){
        var h=host();h.call("search_evidence",Map.of("query","项目负责人"));var initial=context(h.start());
        assertEquals(List.of("A","B"),initial.get("completedTargetObligationIds"));assertEquals(List.of("C"),initial.get("remainingTargetObligationIds"));
        assertEquals(false,initial.get("targetCheckCompleted"));assertEquals(false,initial.get("businessCheckCompleted"));
    }
    @Test void closedAndForgedReadTargetsNeverReachSourceNavigation(){
        var h=host();h.call("search_evidence",Map.of("query","项目负责人"));h.start();h.disclosed.clear();
        String source=h.corpus.all.get(0).id();
        for(String tool:List.of("search_evidence","read_section","read_table_record"))for(String target:List.of("A","B","forged")){
            Map<String,Source> navigationBefore=new LinkedHashMap<>(h.navigation);
            Map<String,Object> arguments=new LinkedHashMap<>(Map.of("targetObligationId",target));arguments.put(tool.equals("search_evidence")?"query":"sourceId",tool.equals("search_evidence")?"项目负责人":source);
            var receipt=h.call(tool,arguments);assertEquals(target.equals("forged")?"INVALID_TARGET_RECEIPT":"TARGET_STATE_UNCHANGED",receipt.get("error"));
            assertTrue(h.disclosed.isEmpty());assertEquals(navigationBefore,h.navigation,"rejected calls must not add navigation beyond the legitimate initial server receipt");
        }
    }
    @Test void remainingTargetReadRunsAndRecomputeStaysArgumentFree(){
        var h=host();h.start();var first=h.call("search_evidence",Map.of("query","项目负责人","targetObligationId","A"));
        assertEquals("COMPLETED",first.get("status"));assertEquals(List.of("A","B"),first.get("completedTargetObligationIds"));
        String source=((List<String>)first.get("sourceIds")).get(0);
        assertEquals("COMPLETED",h.call("read_section",Map.of("sourceId",source,"targetObligationId","C")).get("status"));
        assertEquals("COMPLETED",h.call("recompute_check",Map.of()).get("status"));
        assertEquals("TOOL_ARGUMENT_SCHEMA_MISMATCH",h.call("recompute_check",Map.of("targetObligationId","C")).get("error"));
    }
    @Test void closedTargetCannotSubmitEvenWhenFactWouldBeValid(){
        var h=host();h.start();var first=h.call("search_evidence",Map.of("query","项目负责人","targetObligationId","A"));int before=h.facts.size();
        var proposal=linked("kind","PERSON_ROLE","subject","项目负责人","field","role","rawValue","测试甲","value","测试甲","scope","CURRENT_PROJECT","sourceIds",first.get("sourceIds"));
        assertEquals("TARGET_STATE_UNCHANGED",h.call("submit_candidate_fact",Map.of("targetObligationId","A","fact",proposal)).get("error"));assertEquals(before,h.facts.size());
    }
    private static final String CROSS_COMPANY="北京契约测试设备有限公司",CROSS_CATEGORY="制造商授权",CROSS_NUMBER="AUTH-TEST-2026";
    static EvidenceInvestigation.Host crossBlockHost(){
        Rule certificate=new Rule("CERT","制造商授权证书","CERTIFICATE",CROSS_COMPANY,"证书","","","",Map.of("category",CROSS_CATEGORY),"须提交北京契约测试设备有限公司的制造商授权证书","",List.of());
        Rule outside=role("OUTSIDE","技术负责人");
        String certificateText="制造商授权证书\n企业名称："+CROSS_COMPANY+"\n证书编号："+CROSS_NUMBER+"\n本证书证明该单位具备本制造商的设备经销授权。授权类别：制造商授权。";
        String queryText="制造商授权查询材料\n查询网址：https://authorization.example.invalid/result\n| 核验字段 | 查询记录 |\n| 组织名称 | "+CROSS_COMPANY+" |\n| 证书编号 | "+CROSS_NUMBER+" |\n| 授权类别 | "+CROSS_CATEGORY+" |\n| 查询结果 | 记录存在 |";
        Document document=new Document("cross-bid","cross-project","BID","跨块授权测试材料",1,"cross-bid-hash","",List.of(
            new Page(1,"投标文件\n项目名称：跨块契约测试工程","PAGE","TEXT",""),new Page(2,certificateText,"PAGE","TEXT",""),new Page(3,queryText,"PAGE","TEXT","")),List.of());
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(document));Map<String,Object> ctx=new LinkedHashMap<>();
        List<Map<String,Object>> cells=new ArrayList<>();String[][] values={{"核验字段","查询记录"},{"组织名称",CROSS_COMPANY},{"证书编号",CROSS_NUMBER},{"授权类别",CROSS_CATEGORY},{"查询结果","记录存在"}};
        for(int row=0;row<values.length;row++)for(int column=0;column<2;column++)cells.add(linked("documentId",document.id(),"documentHash",document.sha256(),"documentVersion",1,"locationType","PAGE","location",3,
            "spanId","query-span-"+row+"-"+column,"cellId","query-cell-"+row+"-"+column,"kind","TABLE_CELL","source","OOXML_NATIVE","tableId","query-table","row",row+1,"column",column+1,"rowSpan",1,"columnSpan",1,"text",values[row][column]));
        // Synthetic source-backed stored table records stand in for the document parser, not model receipts.
        ctx.put("layoutLookup",(java.util.function.Function<Map<String,Object>,List<Map<String,Object>>>)request->Objects.equals(request.get("location"),3)?cells:List.of());
        Checkpoint cp=new Checkpoint("cross-contract-cp","授权证书及共同岗位","资格","HIGH","SEMANTIC_RESPONSE","","","制造商授权证书；另需技术负责人","",true,true,true,null);
        Plan plan=new Plan("ALL",List.of(certificate,outside),"FIXTURE",List.of());
        return new EvidenceInvestigation.Host(cp,plan,List.of(certificate,outside),corpus,new LinkedHashMap<>(),ctx,new SharedFactReuse(corpus,ctx,"fixture","fixture"),new RunSession(ModelGateway.disabled(),ctx,"cross-contract-fixture"),null,new ArrayList<>(),Set.of());
    }
    static Map<String,Object> crossBlockFixture(){
        return crossBlockFixture(false);
    }
    static Map<String,Object> crossBlockFixture(boolean readQuerySection){
        var h=crossBlockHost();Map<String,Object> start=h.start();List<Map<String,Object>> sequence=new ArrayList<>();
        java.util.function.BiFunction<String,Map<String,Object>,Map<String,Object>> call=(name,arguments)->{Map<String,Object> result=h.call(name,arguments);sequence.add(linked("name",name,"arguments",arguments,"result",result));return result;};
        call.apply("search_evidence",Map.of("query","制造商授权 证书 查询材料","targetObligationId","CERT"));
        String certificate=h.corpus.all.stream().filter(s->s.evidence().page()==2).findFirst().orElseThrow().id();
        String query=h.corpus.all.stream().filter(s->s.evidence().page()==3).findFirst().orElseThrow().id();
        call.apply("read_section",Map.of("sourceId",certificate,"targetObligationId","CERT"));
        if(readQuerySection)call.apply("read_section",Map.of("sourceId",query,"targetObligationId","CERT"));
        call.apply("read_table_record",Map.of("sourceId",query,"targetObligationId","CERT"));
        Map<String,Object> fact=linked("kind","CERTIFICATE","subject",CROSS_COMPANY,"field","证书","rawValue",CROSS_NUMBER,"value",CROSS_NUMBER,"scope","UNRESOLVED",
            "sourceIds",List.of(certificate,query),"attributes",Map.of("category",CROSS_CATEGORY,"number",CROSS_NUMBER,"numberField","证书编号"));
        call.apply("submit_candidate_fact",Map.of("fact",fact,"targetObligationId","CERT"));
        call.apply("recompute_check",Map.of());
        return linked("start",start,"sequence",sequence,"certificateSourceId",certificate,"querySourceId",query,"proofScope","SOURCE_GROUNDED_AUTHORIZATION_CERTIFICATE_CATEGORY_AND_NUMBER_ONLY",
            "limitations",List.of("Synthetic documents and stored table cells; no real bidder conclusion.","Does not verify external website, certificate validity period, or independently prove a required query screenshot.","OUTSIDE remains open so certificate closure is distinct from whole-check completion."));
    }
    @Test void realHostAllowsMultipleZeroFactReadsBeforeCrossSourceCertificateSubmission(){
        var fixture=crossBlockFixture();var steps=RuleEvaluator.maps(fixture.get("sequence"));assertEquals(5,steps.size());
        for(int i=0;i<3;i++){Map<?,?> result=(Map<?,?>)steps.get(i).get("result");assertEquals("COMPLETED",result.get("status"),result.toString());assertTrue(ProofVerifier.strings(result.get("acceptedFactIds")).isEmpty());assertEquals(List.of(),result.get("completedTargetObligationIds"));}
        Map<?,?> table=(Map<?,?>)steps.get(2).get("result");assertEquals(10,((List<?>)table.get("rows")).size());
        Map<?,?> submitted=(Map<?,?>)steps.get(3).get("result");assertEquals("COMPLETED",submitted.get("status"),submitted.toString());assertEquals(1,((List<?>)submitted.get("acceptedFactIds")).size());
        Map<?,?> receipt=(Map<?,?>)steps.get(4).get("result");assertEquals(List.of("CERT"),receipt.get("completedTargetObligationIds"));assertEquals(List.of("OUTSIDE"),receipt.get("remainingTargetObligationIds"));assertEquals(false,receipt.get("businessCheckCompleted"));
    }
    @Test void realHostTwoAlreadyDisclosedSectionReadsStillPermitTableAndCrossSourceSubmission(){
        var fixture=crossBlockFixture(true);var steps=RuleEvaluator.maps(fixture.get("sequence"));assertEquals(6,steps.size());
        assertEquals(List.of("search_evidence","read_section","read_section","read_table_record","submit_candidate_fact","recompute_check"),steps.stream().map(step->str(step,"name")).toList());
        Map<?,?> search=(Map<?,?>)steps.get(0).get("result");Set<String> disclosed=new HashSet<>(ProofVerifier.strings(search.get("sourceIds")));
        assertTrue(disclosed.contains(fixture.get("certificateSourceId")));assertTrue(disclosed.contains(fixture.get("querySourceId")));
        for(int i=0;i<4;i++){Map<?,?> result=(Map<?,?>)steps.get(i).get("result");assertEquals("COMPLETED",result.get("status"),result.toString());assertTrue(ProofVerifier.strings(result.get("acceptedFactIds")).isEmpty());assertEquals(List.of(),result.get("completedTargetObligationIds"));}
        for(int i=1;i<=2;i++){Map<?,?> result=(Map<?,?>)steps.get(i).get("result");assertTrue(disclosed.containsAll(ProofVerifier.strings(result.get("sourceIds"))),"section body was already disclosed by search; each read adds no new source body");}
        Map<?,?> submitted=(Map<?,?>)steps.get(4).get("result");assertEquals("COMPLETED",submitted.get("status"),submitted.toString());assertEquals(1,((List<?>)submitted.get("acceptedFactIds")).size());
        Map<?,?> receipt=(Map<?,?>)steps.get(5).get("result");assertEquals(List.of("CERT"),receipt.get("completedTargetObligationIds"));assertEquals(List.of("OUTSIDE"),receipt.get("remainingTargetObligationIds"));assertEquals(false,receipt.get("businessCheckCompleted"));
    }
    public static void main(String[] args)throws Exception{
        var h=host();var start=h.start();var recompute=h.recompute();
        var read=h.call("search_evidence",Map.of("query","项目负责人","targetObligationId","A"));var updated=h.recompute();
        Map<String,Object> fixture=linked("fixtureVersion","real-host-target-contract-1","generator","EvidenceInvestigation.Host.start/tools/call/recompute","usesExternalModel",false,
            "start",start,"recompute",recompute,"initialReceipt",recompute,"updatedReceipt",updated,"partiallyClosedStart",h.start(),"readResponse",read);
        var cross=crossBlockFixture();fixture.put("crossBlockStart",cross.get("start"));fixture.put("crossBlockSequence",cross.get("sequence"));fixture.put("crossBlockScenario",cross);
        var repeated=crossBlockFixture(true);fixture.put("crossBlockRepeatedReadStart",repeated.get("start"));fixture.put("crossBlockRepeatedReadSequence",repeated.get("sequence"));fixture.put("crossBlockRepeatedReadScenario",repeated);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),fixture);
    }
}
