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
    private static Rule role(){return new Rule("role","项目负责人任职","PERSON_ROLE","项目负责人","role","","","",cn.bidcheck.compat.Java8Api.mapOf(),"本项目应配置项目负责人","",cn.bidcheck.compat.Java8Api.listOf());}
    private static EvidenceInvestigation.Host host(String text){return host(cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","TEXT","")));}
    private static EvidenceInvestigation.Host host(List<Page> pages){
        cn.bidcheck.domain.Domain.Document document=new Document("bid","project","BID","导航与表格测试.pdf",1,"hash-bid","",pages,cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(document));Map<String,Object> context=new LinkedHashMap<>();
        cn.bidcheck.domain.Domain.Checkpoint checkpoint=new Checkpoint("navigation-cp","项目负责人","资格","HIGH","SEMANTIC_RESPONSE","","","本项目应配置项目负责人","",true,true,true,null);
        return new EvidenceInvestigation.Host(checkpoint,new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(role()),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.listOf(role()),corpus,new LinkedHashMap<>(),context,
            new SharedFactReuse(corpus,context,"fixture","fixture"),new RunSession(ModelGateway.disabled(),context,"navigation-fixture"),null,new ArrayList<>(),cn.bidcheck.compat.Java8Api.setOf());
    }
    private static Map<String,Object> cell(Source source,String text){return linked("documentId",source.evidence().documentId(),"documentHash",source.documentHash(),"documentVersion",source.documentVersion(),
        "locationType",source.evidence().locationType(),"location",source.evidence().page(),"kind","TABLE_CELL","spanId","cell-test","cellId","cell-test","tableId","table-test",
        "source","OOXML_NATIVE","row",1,"column",1,"rowSpan",1,"columnSpan",1,"text",text);}
    private static List<Map<String,Object>> outputs(EvidenceInvestigation.Host host,String type){return cn.bidcheck.compat.Java8Api.toList(host.session.steps.stream().filter(step->type.equals(step.get("type"))).map(step->map(step.get("output"))));}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}

    @Test void recomputedServerSourceIdsPermitNavigationButCannotAuthorizeUnreadFactCitation(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(APPOINTMENT);Source source=h.corpus.all.get(0);
        List<Fact> nativeFacts=h.corpus.nativeFacts(cn.bidcheck.compat.Java8Api.listOf(role()),cn.bidcheck.compat.Java8Api.listOf(source));
        Fact roleFact=nativeFacts.stream().filter(f->"PERSON_ROLE".equals(f.kind())).findFirst().orElseThrow(java.util.NoSuchElementException::new);h.facts.put(roleFact.id(),roleFact);
        assertTrue(h.navigation.isEmpty());assertTrue(h.disclosed.isEmpty());
        Map<String,Object> receipt=h.call("recompute_check",cn.bidcheck.compat.Java8Api.mapOf());
        assertTrue(new ObjectMapper().valueToTree(receipt).toString().contains("sourceIds"),"server proof must actually contain source references");
        assertTrue(h.navigation.containsKey(source.id()),receipt.toString());assertTrue(h.disclosed.isEmpty());
        Map<String,Object> candidate=new ObjectMapper().convertValue(roleFact,Map.class);
        Map<String,Object> rejected=h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", candidate));
        assertEquals("REJECTED",rejected.get("status"));assertTrue(new ObjectMapper().valueToTree(rejected).toString().contains("SOURCE_REFERENCE_INVALID"),rejected.toString());
        assertTrue(h.disclosed.isEmpty(),"navigation receipts cannot disclose bodies as a side effect");
        Map<String,Object> read=h.call("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id()));
        assertEquals("COMPLETED",read.get("status"));assertTrue(h.disclosed.containsKey(source.id()));
        assertTrue(RuleEvaluator.maps(read.get("sources")).stream().anyMatch(row->source.id().equals(row.get("id"))&&source.evidence().quote().equals(row.get("quote"))));
        assertTrue(outputs(h,"AGENT_SOURCE_NAVIGATION").stream().anyMatch(row->Boolean.TRUE.equals(row.get("readRequired"))&&Boolean.FALSE.equals(row.get("grantsFactCitation"))));
    }

    @Test void recoveryReceiptCannotRegisterUnknownDocumentOrInventedSourceId(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host("证书编号：TEST-01");Source source=h.corpus.all.get(0);
        h.ctx.put("recoveryChangedSourceIds",cn.bidcheck.compat.Java8Api.listOf(source.id(), "unknown-document-source", "foreign-doc:page-1"));h.start();
        assertEquals(cn.bidcheck.compat.Java8Api.setOf(source.id()),h.navigation.keySet());assertTrue(h.disclosed.isEmpty());
        for(String forged:cn.bidcheck.compat.Java8Api.listOf("unknown-document-source", "foreign-doc:page-1")){
            assertEquals("SOURCE_NOT_DISCLOSED",h.call("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", forged)).get("error"));
            assertEquals("SOURCE_NOT_DISCLOSED",h.call("read_table_record",cn.bidcheck.compat.Java8Api.mapOf("sourceId", forged)).get("error"));
        }
        assertTrue(h.disclosed.isEmpty());
    }

    @Test void scanPermissionOnlyComesFromExplicitTableReadAndCarriesFrozenSourceIdentity(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host("证书编号：TEST-01");Source source=h.corpus.all.get(0);List<Map<String,Object>> requests=new ArrayList<>();
        h.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->{requests.add(new LinkedHashMap<>(request));return cn.bidcheck.compat.Java8Api.listOf(cell(source,"TEST-01"));});
        h.ctx.put("recoveryChangedSourceIds",cn.bidcheck.compat.Java8Api.listOf(source.id()));h.start();h.call("recompute_check",cn.bidcheck.compat.Java8Api.mapOf());
        h.call("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id()));h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "证书编号"));
        assertTrue(requests.stream().noneMatch(request->Boolean.TRUE.equals(request.get("allowScan"))),"ordinary preload/search/read must not scan pages");
        int before=requests.size();Map<String,Object> table=h.call("read_table_record",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id()));
        assertEquals("COMPLETED",table.get("status"));assertEquals(before+1,requests.size());Map<String,Object> request=requests.get(before);
        assertEquals(true,request.get("allowScan"));assertEquals("bid",request.get("documentId"));assertEquals("hash-bid",request.get("documentHash"));assertEquals(1,request.get("documentVersion"));
        assertEquals("PAGE",request.get("locationType"));assertEquals(1,request.get("location"));assertEquals(source.evidence().quote(),request.get("sourceQuote"));
    }

    @Test void newVerifiedLayoutTriggersAutomaticRecomputeAndPreservesOriginalAuditReferences(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host("证书编号：TEST-01");Source source=h.corpus.all.get(0);h.navigation.put(source.id(),source);
        h.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->cn.bidcheck.compat.Java8Api.listOf(cell(source,"TEST-01")));
        Map<String,Object> inputAudit=linked("status","SAVED","directory","page-layout","file","trace-input.json","sha256",cn.bidcheck.compat.Java8Api.repeat("a", 64),"bytes",902);
        Map<String,Object> outputAudit=linked("status","SAVED","directory","page-layout","file","trace-output-1.json","sha256",cn.bidcheck.compat.Java8Api.repeat("b", 64),"bytes",603);
        Map<String,Object> audit=linked("traceId","layout-fixture-trace","cacheHit",true,"inputAudit",inputAudit,"outputAudit",outputAudit,"responseAttempts",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("attempt", 1, "httpStatus", 200, "outputAudit", outputAudit)),"transcriptionHash",cn.bidcheck.compat.Java8Api.repeat("c", 64),"imageHash",cn.bidcheck.compat.Java8Api.repeat("d", 64));
        h.ctx.put("layoutAuditLookup",(Function<Map<String,Object>,Map<String,Object>>)request->audit);
        Map<String,Object> first=h.call("read_table_record",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id()));
        assertEquals(true,first.get("layoutChanged"));assertEquals(audit,first.get("pageLayoutAudit"));assertEquals(audit,outputs(h,"AGENT_PAGE_LAYOUT").get(0));
        assertEquals("HOST_RULE_RECOMPUTE",map(first.get("computationReceipt")).get("kind"));assertEquals(1,outputs(h,"AGENT_LOCAL_RECOMPUTE").size());
        assertEquals(1,RuleEvaluator.maps(h.ctx.get("_validatedLayoutCells")).size());
        assertEquals(false,map(first.get("computationReceipt")).get("businessCheckCompleted"),"a table body alone does not establish the required appointment");
        Map<String,Object> second=h.call("read_table_record",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id()));
        assertEquals(false,second.get("layoutChanged"));assertFalse(second.containsKey("computationReceipt"));assertEquals(1,outputs(h,"AGENT_LOCAL_RECOMPUTE").size());
    }

    @Test void tableReadDisclosesEveryExactSourceBlockOnItsPageAndNoOtherPage(){
        String text="证明材料附表\n"+cn.bidcheck.compat.Java8Api.repeat("申报资料保留原始行列，供逐字段核对。\n", 220)+"末行证书编号：TAIL-99";
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","TEXT",""), new Page(2,"另一页独立材料：OTHER-88","PAGE","TEXT","")));
        List<Source> page=cn.bidcheck.compat.Java8Api.toList(h.corpus.all.stream().filter(source->source.evidence().page()==1));assertTrue(page.size()>2,"fixture must exercise a genuinely split page");
        Source selected=page.get(1);h.navigation.put(selected.id(),selected);
        h.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->cn.bidcheck.compat.Java8Api.listOf(cell(selected,"TAIL-99")));
        Map<String,Object> table=h.call("read_table_record",cn.bidcheck.compat.Java8Api.mapOf("sourceId", selected.id()));assertEquals("COMPLETED",table.get("status"));
        Set<String> expected=new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.toList(page.stream().map(Source::id)));assertEquals(expected,new LinkedHashSet<>(ProofVerifier.strings(table.get("sourceIds"))));
        assertEquals(expected,h.disclosed.keySet());List<Map<String,Object>> bodies=RuleEvaluator.maps(table.get("sources"));assertEquals(page.size(),bodies.size());
        for(Map<String,Object> body:bodies){Source original=h.corpus.byId.get(str(body,"id"));assertNotNull(original);assertEquals(original.evidence().quote(),body.get("quote"));assertEquals(1,body.get("location"));}
        assertTrue(bodies.stream().anyMatch(body->str(body,"quote").contains("TAIL-99")));assertFalse(h.disclosed.values().stream().anyMatch(source->source.evidence().page()==2));
    }

    @Test void differentDocumentRevisionOrPageCannotInjectCellsIntoValidatedLayout(){
        Map<String,Object> mismatches=linked("documentId","foreign-doc","documentHash","old-hash","documentVersion",2,"locationType","PARAGRAPH","location",2);
        for(java.util.Map.Entry<java.lang.String, java.lang.Object> mismatch:mismatches.entrySet()){
            cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host("证书编号：TEST-01");Source source=h.corpus.all.get(0);h.navigation.put(source.id(),source);Map<String,Object> row=cell(source,"TEST-01");row.put(mismatch.getKey(),mismatch.getValue());
            h.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->cn.bidcheck.compat.Java8Api.listOf(row));
            Map<String,Object> result=h.call("read_table_record",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id()));assertEquals("NO_RECORD",result.get("status"),mismatch.toString());
            assertEquals("NO_SOURCE_BOUND_TABLE_CELLS",result.get("reason"));assertTrue(RuleEvaluator.maps(h.ctx.get("_validatedLayoutCells")).isEmpty());assertTrue(h.disclosed.isEmpty());assertFalse(result.containsKey("computationReceipt"));
        }
    }

    @Test void oversizedTablePageMarksUnreadSourceBodiesAndContinuationDoesNotGrantCitationEarly(){
        String text="超长证明材料附表\n"+cn.bidcheck.compat.Java8Api.repeat("申报资料保留原始行列，供逐字段核对。\n", 2200)+"末行证书编号：TAIL-99";
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(text);Source selected=h.corpus.all.get(0);h.navigation.put(selected.id(),selected);
        h.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->cn.bidcheck.compat.Java8Api.listOf(cell(selected,"TAIL-99")));
        Map<String,Object> table=h.call("read_table_record",cn.bidcheck.compat.Java8Api.mapOf("sourceId", selected.id()));
        assertEquals("COMPLETED",table.get("status"));assertEquals(true,table.get("sourceTextPartial"));
        List<String> unread=ProofVerifier.strings(table.get("unreadSourceIds"));assertFalse(unread.isEmpty());
        for(String id:unread){assertTrue(h.navigation.containsKey(id));assertFalse(h.disclosed.containsKey(id));}
        assertTrue(RuleEvaluator.maps(table.get("sources")).stream().mapToInt(body->str(body,"quote").length()).sum()<=32000);
        Set<String> all=new LinkedHashSet<>(ProofVerifier.strings(table.get("sourceIds")));all.addAll(unread);assertEquals(new LinkedHashSet<>(h.corpus.byId.keySet()),all);
        Map<String,Object> continuation=h.call("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", unread.get(0)));
        assertEquals("COMPLETED",continuation.get("status"));assertTrue(h.disclosed.containsKey(unread.get(0)));
        for(Map<String,Object> body:RuleEvaluator.maps(continuation.get("sources")))assertEquals(h.corpus.byId.get(str(body,"id")).evidence().quote(),body.get("quote"));
    }

    @Test void invalidRowOffsetCannotMutateEvidenceOrGrantSourceCitation(){
        for(int offset:cn.bidcheck.compat.Java8Api.listOf(-1, 2)){
            cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host("证书编号：TEST-01");Source source=h.corpus.all.get(0);h.navigation.put(source.id(),source);
            h.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->cn.bidcheck.compat.Java8Api.listOf(cell(source,"TEST-01")));
            Map<String,Object> result=h.call("read_table_record",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id(), "rowOffset", offset));assertEquals("ROW_OFFSET_INVALID",result.get("error"));
            assertTrue(RuleEvaluator.maps(h.ctx.get("_validatedLayoutCells")).isEmpty());assertTrue(h.disclosed.isEmpty());assertFalse(result.containsKey("computationReceipt"));
        }
    }
}
