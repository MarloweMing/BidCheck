package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Host-level disclosure and on-demand layout contracts; no model or external service is used. */
class EvidenceInvestigationNavigationTest {
    private static final String APPOINTMENT="本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲";
    private static Rule role(){return new Rule("role","项目负责人任职","PERSON_ROLE","项目负责人","role","","","",Map.of(),"本项目应配置项目负责人","",List.of());}
    private static EvidenceInvestigation.Host host(String text){return host(List.of(new Page(1,text,"PAGE","TEXT","")));}
    private static EvidenceInvestigation.Host host(List<Page> pages){
        var document=new Document("bid","project","BID","导航与表格测试.pdf",1,"hash-bid","",pages,List.of());
        var corpus=new EvidenceCorpus(List.of(document));Map<String,Object> context=new LinkedHashMap<>();
        var checkpoint=new Checkpoint("navigation-cp","项目负责人","资格","HIGH","SEMANTIC_RESPONSE","","","本项目应配置项目负责人","",true,true,true,null);
        return new EvidenceInvestigation.Host(checkpoint,new Plan("ALL",List.of(role()),"FIXTURE",List.of()),List.of(role()),corpus,new LinkedHashMap<>(),context,
            new SharedFactReuse(corpus,context,"fixture","fixture"),new RunSession(ModelGateway.disabled(),context,"navigation-fixture"),null,new ArrayList<>(),Set.of());
    }
    private static Map<String,Object> cell(Source source,String text){return linked("documentId",source.evidence().documentId(),"documentHash",source.documentHash(),"documentVersion",source.documentVersion(),
        "locationType",source.evidence().locationType(),"location",source.evidence().page(),"kind","TABLE_CELL","spanId","cell-test","cellId","cell-test","tableId","table-test",
        "source","OOXML_NATIVE","row",1,"column",1,"rowSpan",1,"columnSpan",1,"text",text);}
    private static List<Map<String,Object>> outputs(EvidenceInvestigation.Host host,String type){return host.session.steps.stream().filter(step->type.equals(step.get("type"))).map(step->map(step.get("output"))).toList();}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}

    @Test void recomputedServerSourceIdsPermitNavigationButCannotAuthorizeUnreadFactCitation(){
        var h=host(APPOINTMENT);Source source=h.corpus.all.get(0);
        List<Fact> nativeFacts=h.corpus.nativeFacts(List.of(role()),List.of(source));
        Fact roleFact=nativeFacts.stream().filter(f->"PERSON_ROLE".equals(f.kind())).findFirst().orElseThrow();h.facts.put(roleFact.id(),roleFact);
        assertTrue(h.navigation.isEmpty());assertTrue(h.disclosed.isEmpty());
        Map<String,Object> receipt=h.call("recompute_check",Map.of());
        assertTrue(new ObjectMapper().valueToTree(receipt).toString().contains("sourceIds"),"server proof must actually contain source references");
        assertTrue(h.navigation.containsKey(source.id()),receipt.toString());assertTrue(h.disclosed.isEmpty());
        Map<String,Object> candidate=new ObjectMapper().convertValue(roleFact,Map.class);
        Map<String,Object> rejected=h.call("submit_candidate_fact",Map.of("fact",candidate));
        assertEquals("REJECTED",rejected.get("status"));assertTrue(new ObjectMapper().valueToTree(rejected).toString().contains("SOURCE_REFERENCE_INVALID"),rejected.toString());
        assertTrue(h.disclosed.isEmpty(),"navigation receipts cannot disclose bodies as a side effect");
        Map<String,Object> read=h.call("read_section",Map.of("sourceId",source.id()));
        assertEquals("COMPLETED",read.get("status"));assertTrue(h.disclosed.containsKey(source.id()));
        assertTrue(RuleEvaluator.maps(read.get("sources")).stream().anyMatch(row->source.id().equals(row.get("id"))&&source.evidence().quote().equals(row.get("quote"))));
        assertTrue(outputs(h,"AGENT_SOURCE_NAVIGATION").stream().anyMatch(row->Boolean.TRUE.equals(row.get("readRequired"))&&Boolean.FALSE.equals(row.get("grantsFactCitation"))));
    }

    @Test void recoveryReceiptCannotRegisterUnknownDocumentOrInventedSourceId(){
        var h=host("证书编号：TEST-01");Source source=h.corpus.all.get(0);
        h.ctx.put("recoveryChangedSourceIds",List.of(source.id(),"unknown-document-source","foreign-doc:page-1"));h.start();
        assertEquals(Set.of(source.id()),h.navigation.keySet());assertTrue(h.disclosed.isEmpty());
        for(String forged:List.of("unknown-document-source","foreign-doc:page-1")){
            assertEquals("SOURCE_NOT_DISCLOSED",h.call("read_section",Map.of("sourceId",forged)).get("error"));
            assertEquals("SOURCE_NOT_DISCLOSED",h.call("read_table_record",Map.of("sourceId",forged)).get("error"));
        }
        assertTrue(h.disclosed.isEmpty());
    }

    @Test void scanPermissionOnlyComesFromExplicitTableReadAndCarriesFrozenSourceIdentity(){
        var h=host("证书编号：TEST-01");Source source=h.corpus.all.get(0);List<Map<String,Object>> requests=new ArrayList<>();
        h.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->{requests.add(new LinkedHashMap<>(request));return List.of(cell(source,"TEST-01"));});
        h.ctx.put("recoveryChangedSourceIds",List.of(source.id()));h.start();h.call("recompute_check",Map.of());
        h.call("read_section",Map.of("sourceId",source.id()));h.call("search_evidence",Map.of("query","证书编号"));
        assertTrue(requests.stream().noneMatch(request->Boolean.TRUE.equals(request.get("allowScan"))),"ordinary preload/search/read must not scan pages");
        int before=requests.size();Map<String,Object> table=h.call("read_table_record",Map.of("sourceId",source.id()));
        assertEquals("COMPLETED",table.get("status"));assertEquals(before+1,requests.size());Map<String,Object> request=requests.get(before);
        assertEquals(true,request.get("allowScan"));assertEquals("bid",request.get("documentId"));assertEquals("hash-bid",request.get("documentHash"));assertEquals(1,request.get("documentVersion"));
        assertEquals("PAGE",request.get("locationType"));assertEquals(1,request.get("location"));assertEquals(source.evidence().quote(),request.get("sourceQuote"));
    }

    @Test void newVerifiedLayoutTriggersAutomaticRecomputeAndPreservesOriginalAuditReferences(){
        var h=host("证书编号：TEST-01");Source source=h.corpus.all.get(0);h.navigation.put(source.id(),source);
        h.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->List.of(cell(source,"TEST-01")));
        Map<String,Object> inputAudit=linked("status","SAVED","directory","page-layout","file","trace-input.json","sha256","a".repeat(64),"bytes",902);
        Map<String,Object> outputAudit=linked("status","SAVED","directory","page-layout","file","trace-output-1.json","sha256","b".repeat(64),"bytes",603);
        Map<String,Object> audit=linked("traceId","layout-fixture-trace","cacheHit",true,"inputAudit",inputAudit,"outputAudit",outputAudit,"responseAttempts",List.of(Map.of("attempt",1,"httpStatus",200,"outputAudit",outputAudit)),"transcriptionHash","c".repeat(64),"imageHash","d".repeat(64));
        h.ctx.put("layoutAuditLookup",(Function<Map<String,Object>,Map<String,Object>>)request->audit);
        Map<String,Object> first=h.call("read_table_record",Map.of("sourceId",source.id()));
        assertEquals(true,first.get("layoutChanged"));assertEquals(audit,first.get("pageLayoutAudit"));assertEquals(audit,outputs(h,"AGENT_PAGE_LAYOUT").get(0));
        assertEquals("HOST_RULE_RECOMPUTE",map(first.get("computationReceipt")).get("kind"));assertEquals(1,outputs(h,"AGENT_LOCAL_RECOMPUTE").size());
        assertEquals(1,RuleEvaluator.maps(h.ctx.get("_validatedLayoutCells")).size());
        assertEquals(false,map(first.get("computationReceipt")).get("businessCheckCompleted"),"a table body alone does not establish the required appointment");
        Map<String,Object> second=h.call("read_table_record",Map.of("sourceId",source.id()));
        assertEquals(false,second.get("layoutChanged"));assertFalse(second.containsKey("computationReceipt"));assertEquals(1,outputs(h,"AGENT_LOCAL_RECOMPUTE").size());
    }

    @Test void tableReadDisclosesEveryExactSourceBlockOnItsPageAndNoOtherPage(){
        String text="证明材料附表\n"+"申报资料保留原始行列，供逐字段核对。\n".repeat(220)+"末行证书编号：TAIL-99";
        var h=host(List.of(new Page(1,text,"PAGE","TEXT",""),new Page(2,"另一页独立材料：OTHER-88","PAGE","TEXT","")));
        List<Source> page=h.corpus.all.stream().filter(source->source.evidence().page()==1).toList();assertTrue(page.size()>2,"fixture must exercise a genuinely split page");
        Source selected=page.get(1);h.navigation.put(selected.id(),selected);
        h.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->List.of(cell(selected,"TAIL-99")));
        Map<String,Object> table=h.call("read_table_record",Map.of("sourceId",selected.id()));assertEquals("COMPLETED",table.get("status"));
        Set<String> expected=new LinkedHashSet<>(page.stream().map(Source::id).toList());assertEquals(expected,new LinkedHashSet<>(ProofVerifier.strings(table.get("sourceIds"))));
        assertEquals(expected,h.disclosed.keySet());List<Map<String,Object>> bodies=RuleEvaluator.maps(table.get("sources"));assertEquals(page.size(),bodies.size());
        for(Map<String,Object> body:bodies){Source original=h.corpus.byId.get(str(body,"id"));assertNotNull(original);assertEquals(original.evidence().quote(),body.get("quote"));assertEquals(1,body.get("location"));}
        assertTrue(bodies.stream().anyMatch(body->str(body,"quote").contains("TAIL-99")));assertFalse(h.disclosed.values().stream().anyMatch(source->source.evidence().page()==2));
    }

    @Test void differentDocumentRevisionOrPageCannotInjectCellsIntoValidatedLayout(){
        Map<String,Object> mismatches=linked("documentId","foreign-doc","documentHash","old-hash","documentVersion",2,"locationType","PARAGRAPH","location",2);
        for(var mismatch:mismatches.entrySet()){
            var h=host("证书编号：TEST-01");Source source=h.corpus.all.get(0);h.navigation.put(source.id(),source);Map<String,Object> row=cell(source,"TEST-01");row.put(mismatch.getKey(),mismatch.getValue());
            h.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->List.of(row));
            Map<String,Object> result=h.call("read_table_record",Map.of("sourceId",source.id()));assertEquals("NO_RECORD",result.get("status"),mismatch.toString());
            assertEquals("NO_SOURCE_BOUND_TABLE_CELLS",result.get("reason"));assertTrue(RuleEvaluator.maps(h.ctx.get("_validatedLayoutCells")).isEmpty());assertTrue(h.disclosed.isEmpty());assertFalse(result.containsKey("computationReceipt"));
        }
    }

    @Test void oversizedTablePageMarksUnreadSourceBodiesAndContinuationDoesNotGrantCitationEarly(){
        String text="超长证明材料附表\n"+"申报资料保留原始行列，供逐字段核对。\n".repeat(2200)+"末行证书编号：TAIL-99";
        var h=host(text);Source selected=h.corpus.all.get(0);h.navigation.put(selected.id(),selected);
        h.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->List.of(cell(selected,"TAIL-99")));
        Map<String,Object> table=h.call("read_table_record",Map.of("sourceId",selected.id()));
        assertEquals("COMPLETED",table.get("status"));assertEquals(true,table.get("sourceTextPartial"));
        List<String> unread=ProofVerifier.strings(table.get("unreadSourceIds"));assertFalse(unread.isEmpty());
        for(String id:unread){assertTrue(h.navigation.containsKey(id));assertFalse(h.disclosed.containsKey(id));}
        assertTrue(RuleEvaluator.maps(table.get("sources")).stream().mapToInt(body->str(body,"quote").length()).sum()<=32000);
        Set<String> all=new LinkedHashSet<>(ProofVerifier.strings(table.get("sourceIds")));all.addAll(unread);assertEquals(new LinkedHashSet<>(h.corpus.byId.keySet()),all);
        Map<String,Object> continuation=h.call("read_section",Map.of("sourceId",unread.get(0)));
        assertEquals("COMPLETED",continuation.get("status"));assertTrue(h.disclosed.containsKey(unread.get(0)));
        for(Map<String,Object> body:RuleEvaluator.maps(continuation.get("sources")))assertEquals(h.corpus.byId.get(str(body,"id")).evidence().quote(),body.get("quote"));
    }

    @Test void invalidRowOffsetCannotMutateEvidenceOrGrantSourceCitation(){
        for(int offset:List.of(-1,2)){
            var h=host("证书编号：TEST-01");Source source=h.corpus.all.get(0);h.navigation.put(source.id(),source);
            h.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->List.of(cell(source,"TEST-01")));
            Map<String,Object> result=h.call("read_table_record",Map.of("sourceId",source.id(),"rowOffset",offset));assertEquals("ROW_OFFSET_INVALID",result.get("error"));
            assertTrue(RuleEvaluator.maps(h.ctx.get("_validatedLayoutCells")).isEmpty());assertTrue(h.disclosed.isEmpty());assertFalse(result.containsKey("computationReceipt"));
        }
    }
}
