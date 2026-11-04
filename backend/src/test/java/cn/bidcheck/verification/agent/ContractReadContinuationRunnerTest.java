package cn.bidcheck.verification.agent;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ContractReadContinuationRunnerTest {
    @Test void verifiedReadCoverageSkipsOldPageAndUsesUnreadPartialChunkWithoutHidingBody() {
        List<String> reads=new ArrayList<>(); List<Map<String,Object>> modelInputs=new ArrayList<>();
        Map<String,Object> row=m("separatorTitle","Alpha", "documentId","doc","documentHash","hash","documentVersion",1,
            "inventoryPage",1,"proofPageStart",2,"proofPageEnd",4);
        Map<String,Object> context=m("workflowPolicy","TARGET_FACTS_V1","canonicalTargetPartition",true,
            "targetObligationIds",Collections.singletonList("row"),"targetEvidenceRevisions",m("row","revision"),
            "serverPlanRevision","plan","targetObligations",Collections.singletonList(m("id","row","predicate","CONTRACT",
                "parameters",m("readScopePolicy","EXACT_DECLARED_ROW_AND_CONTRACT_PACKET","declaredPerformanceRow",row))));
        SdkAgentInvestigator bridge=new SdkAgentInvestigator(true,"https://dashscope.aliyuncs.com/compatible-mode/v1","synthetic-offline-key","synthetic",15,1,null,
            (request,timeout)->{modelInputs.add(request);return m("choices",Collections.singletonList(m("message",m("role","assistant","content","","tool_calls",Collections.emptyList()))));});
        bridge.investigate(m("type","start","protocol","bidcheck-agent-1","requestId","synthetic-continuation","context",context,
            "tools",Arrays.asList(tool("search_evidence","query"),tool("read_section","sourceId"))),
            (name,args)->{
                if(name.equals("search_evidence")){
                    Map<String,Object> coverage=m("targetObligationId","row","documentId","doc","documentHash","hash","documentVersion",1,
                        "authority","CURRENT_SERVER_PROOF","businessProof",false,"pendingPages",Arrays.asList(3,4),"readSourceIds",Arrays.asList("S2","S3a"));
                    return m("status","COMPLETED","sources",Arrays.asList(source("S2",2,"Replayed original Alpha"),source("S3a",3,"Replayed partial prefix")),
                        "contractPacketNavigation",m("authority","CURRENT_SERVER_PROOF","targetObligationId","row","serverPlanRevision","plan",
                            "coverage",coverage,"sources",Arrays.asList(ref("S3b",3),ref("S4",4))));
                }
                String id=String.valueOf(args.get("sourceId"));reads.add(id);
                return m("status","COMPLETED","sources",Collections.singletonList(source(id,id.equals("S4")?4:3,"New unread original")));
            },event->{});
        assertEquals(Arrays.asList("S3b","S4"),reads);
        assertFalse(modelInputs.isEmpty());
        assertTrue(modelInputs.get(0).toString().contains("Replayed original Alpha"));
        assertTrue(modelInputs.get(0).toString().contains("Replayed partial prefix"));
    }
    @Test void deferredSigningPageIsSelectableFromPersistentTargetCatalog() {
        List<String> reads=new ArrayList<>();
        Map<String,Object> row=m("separatorTitle","Alpha","documentId","doc","documentHash","hash","documentVersion",1,
            "inventoryPage",1,"proofPageStart",2,"proofPageEnd",3);
        Map<String,Object> context=m("workflowPolicy","TARGET_FACTS_V1","canonicalTargetPartition",true,"targetObligationIds",Collections.singletonList("row"),
            "targetEvidenceRevisions",m("row","revision"),"serverPlanRevision","plan","targetObligations",Collections.singletonList(m("id","row","predicate","CONTRACT",
                "parameters",m("readScopePolicy","EXACT_DECLARED_ROW_AND_CONTRACT_PACKET","declaredPerformanceRow",row))));
        SdkAgentInvestigator bridge=new SdkAgentInvestigator(true,"https://dashscope.aliyuncs.com/compatible-mode/v1","synthetic-offline-key","synthetic",15,1,null,
            (request,timeout)->{
                String selected="";
                for(Object raw:(List<?>)request.get("messages")){
                    Map<?,?> message=(Map<?,?>)raw;
                    if(!"user".equals(message.get("role")))continue;
                    com.fasterxml.jackson.databind.JsonNode content=new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.valueOf(message.get("content")));
                    for(com.fasterxml.jackson.databind.JsonNode item:content.path("currentHostState").path("sourceCatalog"))
                        if(item.path("label").asText().contains("Signing"))selected=item.path("sourceId").asText();
                }
                if(reads.isEmpty()&&!selected.isEmpty())return m("choices",Collections.singletonList(m("message",m("role","assistant","content",null,
                    "tool_calls",Collections.singletonList(m("id","open-signing","type","function","function",m("name","read_section",
                        "arguments","{\"sourceId\":\""+selected+"\",\"targetObligationId\":\"row\"}")))))));
                return m("choices",Collections.singletonList(m("message",m("role","assistant","content","","tool_calls",Collections.emptyList()))));
            });
        bridge.investigate(m("type","start","protocol","bidcheck-agent-1","requestId","synthetic-deferred","context",context,
            "tools",Arrays.asList(tool("search_evidence","query"),tool("read_section","sourceId"))), (name,args)->{
                if(name.equals("search_evidence")){
                    Map<String,Object> deferred=ref("S3",3);deferred.put("label","Signing date page");deferred.put("readRequired",true);
                    return m("status","COMPLETED","sources",Collections.singletonList(source("S2",2,"Prior cover original")),
                        "contractReadReplay",m("authority","CURRENT_SERVER_PROOF","targetObligationId","row","serverPlanRevision","plan","deferredOriginalSources",Collections.singletonList(deferred)),
                        "contractPacketNavigation",m("authority","CURRENT_SERVER_PROOF","targetObligationId","row","serverPlanRevision","plan","sources",Collections.emptyList(),
                            "coverage",m("authority","CURRENT_SERVER_PROOF","businessProof",false,"targetObligationId","row","documentId","doc","documentHash","hash","documentVersion",1,
                                "readSourceIds",Arrays.asList("S2","S3"),"pendingPages",Collections.emptyList())));
                }
                reads.add(String.valueOf(args.get("sourceId")));
                return m("status","COMPLETED","sources",Collections.singletonList(source("S3",3,"Original signing body")));
            },event->{});
        assertEquals(Collections.singletonList("S3"),reads);
    }

    private Map<String,Object> ref(String id,int page){Map<String,Object> r=source(id,page,"");r.remove("quote");r.put("navigationOnly",true);return r;}
    private Map<String,Object> source(String id,int page,String quote){return m("id",id,"sourceId",id,"documentId","doc","documentHash","hash","documentVersion",1,"locationType","PAGE","location",page,"quote",quote);}
    private Map<String,Object> tool(String name,String field){return m("name",name,"description","Synthetic","parameters",m("type","object","properties",m(field,m("type","string"),"targetObligationId",m("type","string")),"required",Arrays.asList(field,"targetObligationId"),"additionalProperties",false));}
    private static Map<String,Object> m(Object...pairs){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)r.put((String)pairs[i],pairs[i+1]);return r;}
}
