package cn.bidcheck.verification.engine;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
class ContractBodyReplayTest {
    @Test void otherContractCannotChangeThisRowsEvidenceDependency() {
        EvidenceInvestigation.Host host = host(Collections.emptyList(), "terms", "hash", 1, "TEXT", "Alpha contract original");
        Source outside = source(host, 5);
        Fact other = new Fact("f", "CONTRACT", "", "contractName", "Other contract", "Other contract", "", "",
            "HISTORICAL_PROJECT", Collections.singletonList(outside.id()), linked());
        assertFalse(AgentInvestigationScope.relevant(other, host.rules, Collections.singletonList(other), host.corpus));
        Fact same = new Fact("g", "CONTRACT", "", "contractName", "Alpha contract", "Alpha contract", "", "",
            "HISTORICAL_PROJECT", Collections.singletonList(source(host, 2).id()), linked());
        assertTrue(AgentInvestigationScope.relevant(same, host.rules, Arrays.asList(other, same), host.corpus));
    }
    @Test void realPriorReadIsRevalidatedAndOriginalBodyRemainsAvailableToNewRequest() {
        EvidenceInvestigation.Host first = host(Collections.emptyList(), "terms", "hash", 1, "TEXT", "Alpha contract original");
        search(first); String id = source(first, 2).id();
        first.call("read_section", linked("targetObligationId", "row", "sourceId", id));
        EvidenceInvestigation.Host next = host(first.session.steps, "terms", "hash", 1, "TEXT", "Alpha contract original");
        Map<String,Object> result = search(next);
        assertTrue(ProofVerifier.strings(map(result.get("contractReadCoverage")).get("readSourceIds")).contains(id));
        assertEquals(Arrays.asList(3, 4), map(result.get("contractReadCoverage")).get("pendingPages"));
        assertTrue(ProofVerifier.strings(map(result.get("contractReadReplay")).get("reusedBodySourceIds")).contains(id));
        assertTrue(RuleEvaluator.maps(result.get("sources")).stream().anyMatch(s -> id.equals(str(s,"id")) && str(s,"quote").contains("Alpha contract original")));
        assertFalse((Boolean) next.recompute().get("businessCheckCompleted"));
    }
    @Test void partialPageResumesAtActualUnreadChunkAcrossRequests() {
        char[] chars = new char[40000]; Arrays.fill(chars, 'x'); String text = new String(chars);
        EvidenceInvestigation.Host first = host(Collections.emptyList(), text, "hash", 1, "TEXT", "Alpha contract original");
        search(first);
        Map<String,Object> read = first.call("read_section", linked("targetObligationId", "row", "sourceId", source(first,3).id()));
        String nextId = str(read,"nextSourceId"); assertFalse(nextId.isEmpty());
        EvidenceInvestigation.Host next = host(first.session.steps, text, "hash", 1, "TEXT", "Alpha contract original");
        Map<String,Object> result = search(next);
        List<Map<String,Object>> refs = RuleEvaluator.maps(map(result.get("contractPacketNavigation")).get("sources"));
        assertEquals(nextId, refs.stream().filter(s -> Integer.valueOf(3).equals(s.get("location"))).findFirst().get().get("sourceId"));
        assertTrue(((List<?>) map(result.get("contractReadCoverage")).get("pendingPages")).contains(3));
    }
    @Test void changedHashVersionParserOrBodyInvalidatesOldRead() {
        EvidenceInvestigation.Host first = host(Collections.emptyList(), "terms", "hash", 1, "TEXT", "Alpha contract original");
        search(first);first.call("read_section", linked("targetObligationId", "row", "sourceId", source(first,2).id()));
        for (int variant=0;variant<4;variant++) {
            EvidenceInvestigation.Host changed = host(first.session.steps, "terms", variant==0?"new-hash":"hash", variant==1?2:1,
                variant==2?"NEW_PARSER":"TEXT", variant==3?"Changed original body":"Alpha contract original");
            assertEquals(Collections.emptyList(), map(search(changed).get("contractReadCoverage")).get("readSourceIds"));
        }
    }
    @Test void modelOrNavigationClaimsDoNotCreateReadHistory() {
        EvidenceInvestigation.Host first = host(Collections.emptyList(), "terms", "hash", 1, "TEXT", "Alpha contract original");
        search(first);
        List<Map<String,Object>> prior = new ArrayList<>(first.session.steps);
        prior.add(linked("type", "AGENT_INVESTIGATION", "status", "COMPLETED", "schemaVersion", VERSION,
            "output", linked("contractReadCoverage", linked("targetObligationId","row","readSourceIds",Collections.singletonList(source(first,2).id()),"complete",true))));
        EvidenceInvestigation.Host next = host(prior, "terms", "hash", 1, "TEXT", "Alpha contract original");
        assertEquals(Collections.emptyList(), map(search(next).get("contractReadCoverage")).get("readSourceIds"));
    }
    @Test void changedOrForeignPersistedReadReceiptsAreRejected() {
        EvidenceInvestigation.Host first = host(Collections.emptyList(), "terms", "hash", 1, "TEXT", "Alpha contract original");
        search(first); first.call("read_section", linked("targetObligationId", "row", "sourceId", source(first,2).id()));
        Map<String,Object> saved = first.session.steps.stream().filter(step -> "AGENT_CONTRACT_BODY_READ".equals(step.get("type"))).findFirst().orElse(Collections.emptyMap());
        assertFalse(saved.isEmpty());
        for (String mutation : Arrays.asList("model", "schema", "target", "source")) {
            Map<String,Object> copy = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(saved, Map.class);
            Map<String,Object> binding = map(map(copy.get("output")).get("readBinding"));
            if (mutation.equals("model")) copy.put("modelIdentity", "foreign-model");
            if (mutation.equals("schema")) copy.put("schemaVersion", "old-schema");
            if (mutation.equals("target")) binding.put("targetObligationId", "other-row");
            if (mutation.equals("source")) map(((List<?>) binding.get("sources")).get(0)).put("sourceFingerprint", "forged");
            EvidenceInvestigation.Host next = host(Collections.singletonList(copy), "terms", "hash", 1, "TEXT", "Alpha contract original");
            assertEquals(Collections.emptyList(), map(search(next).get("contractReadCoverage")).get("readSourceIds"), mutation);
        }
    }

    @Test void rowRevisionIgnoresOtherContractButChangesForSamePacketEvidence() {
        EvidenceInvestigation.Host host = host(Collections.emptyList(), "terms", "hash", 1, "TEXT", "Alpha contract original");
        Object before = host.recompute().get("targetEvidenceRevisions");
        Fact outside = new Fact("other", "CONTRACT", "", "contractName", "Other contract", "Other contract", "", "",
            "HISTORICAL_PROJECT", Collections.singletonList(source(host,5).id()), linked());
        host.facts.put(outside.id(),outside);
        assertEquals(before,host.recompute().get("targetEvidenceRevisions"));
        Fact inside = new Fact("same", "CONTRACT", "", "contractName", "Alpha contract", "Alpha contract", "", "",
            "HISTORICAL_PROJECT", Collections.singletonList(source(host,2).id()), linked());
        host.facts.put(inside.id(),inside);
        assertNotEquals(before,host.recompute().get("targetEvidenceRevisions"));
    }

    @Test void largeRestoredBodyKeepsDeferredSigningChunksNavigableButNotDisclosed() {
        char[] chars = new char[44000]; Arrays.fill(chars, 'x');
        String text = "Signature and signing date page\n" + new String(chars);
        EvidenceInvestigation.Host first = host(Collections.emptyList(), text, "hash", 1, "TEXT", "Alpha contract original");
        search(first); String id = source(first,3).id();
        do {
            Map<String,Object> read = first.call("read_section", linked("targetObligationId","row","sourceId",id));
            id = str(read,"nextSourceId");
        } while (!id.isEmpty());
        EvidenceInvestigation.Host next = host(first.session.steps, text, "hash", 1, "TEXT", "Alpha contract original");
        Map<String,Object> result = search(next);
        List<Map<String,Object>> deferred = RuleEvaluator.maps(map(result.get("contractReadReplay")).get("deferredOriginalSources"));
        assertFalse(deferred.isEmpty(), "Restored originals exceeding the model-body limit still need usable navigation");
        Map<String,Object> ref = deferred.get(0);
        assertEquals(3,ref.get("location"));
        assertEquals(true,ref.get("navigationOnly"));
        assertEquals(true,ref.get("readRequired"));
        assertTrue(str(ref,"label").contains("Signature and signing date"));
        assertFalse(ref.containsKey("quote"));
        String deferredId=str(ref,"sourceId");
        assertFalse(next.disclosed.containsKey(deferredId));
        Map<String,Object> opened=next.call("read_section",linked("targetObligationId","row","sourceId",deferredId));
        assertEquals("COMPLETED",opened.get("status"));
        assertTrue(ProofVerifier.strings(opened.get("sourceIds")).contains(deferredId));
    }

    private Map<String,Object> search(EvidenceInvestigation.Host host) {
        return host.call("search_evidence", linked("targetObligationId", "row", "query", "Alpha contract"));
    }
    private Source source(EvidenceInvestigation.Host host, int page) {
        return host.corpus.all.stream().filter(s -> s.evidence().page()==page).findFirst().get();
    }
    private EvidenceInvestigation.Host host(List<Map<String,Object>> steps, String middle, String hash, int version, String parser, String cover) {
        List<Page> pages=Arrays.asList(new Page(1,"inventory","PAGE",parser,""),new Page(2,cover,"PAGE",parser,""),
            new Page(3,middle,"PAGE",parser,""),new Page(4,"more terms","PAGE",parser,""),new Page(5,"Other contract","PAGE",parser,""));
        Document doc=new Document("doc","project","BID","synthetic.pdf",version,hash,"",pages,Collections.emptyList());
        EvidenceCorpus corpus=new EvidenceCorpus(Collections.singletonList(doc));
        Rule rule=new Rule("row","packet","CONTRACT","","","","","",linked("declaredPerformanceRow",
            linked("documentId","doc","documentHash",hash,"documentVersion",version,"inventoryPage",1,
                "proofPageStart",2,"proofPageEnd",4,"separatorTitle","Alpha contract")),"","",Collections.emptyList());
        Plan plan=new Plan("ALL",Collections.singletonList(rule),"SYNTHETIC",Collections.emptyList());
        Map<String,Object> ctx=linked("priorSteps",steps);
        Checkpoint cp=new Checkpoint("cp","synthetic","test","HIGH","SEMANTIC_RESPONSE","","","","",true,true,true,null);
        return new EvidenceInvestigation.Host(cp,plan,plan.rules(),corpus,new LinkedHashMap<>(),ctx,
            new SharedFactReuse(corpus,ctx,"synthetic","synthetic"),new RunSession(ModelGateway.disabled(),ctx,"synthetic"),null,new ArrayList<>(),Collections.emptySet());
    }
    private Map<String,Object> map(Object value) { return value instanceof Map?(Map<String,Object>)value:Collections.emptyMap(); }
}
