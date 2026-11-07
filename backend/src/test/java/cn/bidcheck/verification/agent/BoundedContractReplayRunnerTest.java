package cn.bidcheck.verification.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Replays the two-target control pattern of 7e921ff5 with invented pages, fields and text. */
class BoundedContractReplayRunnerTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String A="packet-a", B="packet-b";
    private static final List<String> TARGETS=Arrays.asList(A,B);

    @Test void preparedPacketShowsTheRepairedSubmissionAndStopsWithoutThreeSeparatePaidBodyRestorations() throws Exception {
        Replay replay=new Replay(false);
        Map<String,Object> result=replay.run();
        assertEquals("NO_PROGRESS",result.get("status"),result.toString());
        assertEquals(2,replay.submittedA,"the corrected submission must get a real chance after the first rejection");
        assertTrue(replay.completeViewAtCorrection);
        assertTrue(replay.calls.size()<=10,replay.calls.toString());
        assertTrue(replay.events.contains("contract_originals_reprojected"));
        assertEquals(1,replay.primesA,"the final revision of the completed prepare must not cause another prime");
        assertTrue(replay.calls.stream().anyMatch(call->call.startsWith("B original")));
        assertFalse((Boolean)result.get("businessCheckCompleted"));
        assertTrue(((Collection<?>)result.get("acceptedFactIds")).isEmpty());
        assertEquals(1,replay.recomputes);
    }

    @Test void aFreshOriginalCanStillCloseTheIndependentTargetAfterTheFirstPacketHasStalled() throws Exception {
        Replay replay=new Replay(true);
        Map<String,Object> result=replay.run();
        assertTrue(replay.readsB.contains("B-2"));
        assertTrue(((Collection<?>)result.get("acceptedFactIds")).contains("verified-b"));
        assertTrue(((Collection<?>)result.get("completedTargetObligationIds")).contains(B));
        assertFalse(((Collection<?>)result.get("completedTargetObligationIds")).contains(A));
        assertFalse((Boolean)result.get("businessCheckCompleted"));
    }

    @Test void equivalentCachedRecomputeCountsOnlyForItsCurrentOpenTarget() throws Exception {
        Replay replay=new Replay(true);replay.onlyRecomputeA=true;
        Map<String,Object> result=replay.run();
        assertEquals(3,Collections.frequency(replay.calls,"A unchanged recompute"),replay.calls.toString());
        assertEquals(1,replay.recomputes,"later equivalent receipts must not call the host again");
        assertTrue(((Collection<?>)result.get("completedTargetObligationIds")).contains(B),"the other target still gets its opportunity");
        assertFalse(((Collection<?>)result.get("completedTargetObligationIds")).contains(A));
    }

    static final class Replay implements JavaAgentRunner.Host {
        final boolean fresh;
        final List<String> calls=new ArrayList<>();
        final List<String> events=new ArrayList<>();
        final Set<String> pendingA=new LinkedHashSet<>(Arrays.asList("A-1","A-2","A-3","A-4"));
        final Set<String> requestedRestores=new LinkedHashSet<>(),readsB=new LinkedHashSet<>();
        final List<String> accepted=new ArrayList<>(),closed=new ArrayList<>();
        final Map<String,Object> revisions=m(A,"a-before-originals",B,"stable-b");
        int submittedA,primesA,recomputes,turnsB;
        boolean completeViewAtCorrection,onlyRecomputeA;
        Replay(boolean fresh){this.fresh=fresh;}

        Map<String,Object> run() throws Exception {
            List<Object> targets=Arrays.asList(obligation(A,1,4),obligation(B,11,14));
            Map<String,Object> context=m("workflowPolicy","TARGET_FACTS_V1","canonicalTargetPartition",true,
                "targetObligationIds",TARGETS,"targetEvidenceRevisions",revisions,"serverPlanRevision","plan","targetObligations",targets);
            JavaAgentRunner runner=new JavaAgentRunner("https://invalid.example","unused","synthetic",30,null,
                (input,timeout)->respond(input));
            return runner.run(m("type","start","protocol","bidcheck-agent-1","requestId","synthetic-two-row-replay","context",context,
                "tools",Arrays.asList(tool("search_evidence","query"),tool("read_section","sourceId"),tool("submit_candidate_fact","value"),
                    m("name","recompute_check","description","Deterministic recomputation","parameters",m("type","object","properties",m())))),this);
        }
        private Map<String,Object> respond(Map<String,Object> input)throws Exception {
            List<Map<String,Object>> messages=(List<Map<String,Object>>)input.get("messages");
            Map<String,Object> user=JSON.readValue((String)messages.get(1).get("content"),Map.class);
            String target=(String)((Map<?,?>)user.get("currentHostState")).get("activeTargetObligationId");
            if(A.equals(target)){
                if(onlyRecomputeA)return send("A unchanged recompute","recompute_check",m());
                if(submittedA==0)return send("A invalid candidate","submit_candidate_fact",m("targetObligationId",A,"value","invalid"));
                if(submittedA==1){
                    String visible=JSON.writeValueAsString(messages);
                    boolean allVisible=true;
                    for(String id:Arrays.asList("A-2","A-3","A-4")){
                        boolean original=visible.contains(quote(id));
                        allVisible&=original;
                        if(!original && requestedRestores.add(id))
                            return send("A cached "+id,"read_section",m("targetObligationId",A,"sourceId",id));
                    }
                    completeViewAtCorrection=allVisible;
                    return send("A corrected candidate","submit_candidate_fact",m("targetObligationId",A,"value","corrected"));
                }
                if(recomputes==0)return send("A recompute","recompute_check",m());
                return send("A repeated search","search_evidence",m("targetObligationId",A,"query","amount of A"));
            }
            // On the original trace a stale cached request for A occurred when B became active.
            // It must not be charged against the independent target.
            if(turnsB++==0)return send("stale A","read_section",m("targetObligationId",A,"sourceId","A-2"));
            if(fresh && readsB.contains("B-2"))
                return send("B new field","submit_candidate_fact",m("targetObligationId",B,"value","new"));
            String id="B-"+Math.min(turnsB,4);
            return send("B original "+id,"read_section",m("targetObligationId",B,"sourceId",id));
        }
        private Map<String,Object> send(String label,String name,Map<String,Object> args)throws Exception{
            calls.add(label);
            return m("choices",Collections.singletonList(m("message",m("role","assistant","content","",
                "tool_calls",Collections.singletonList(m("id","call-"+calls.size(),"type","function","function",m("name",name,
                    "arguments",JSON.writeValueAsString(args))))))));
        }
        public void event(Map<String,Object> event){events.add(String.valueOf(event.get("event")));}
        public Map<String,Object> state(){
            List<String> pending=new ArrayList<>(TARGETS);pending.removeAll(closed);
            return m("targetObligationIds",TARGETS,"canonicalTargetPartition",true,"targetEvidenceRevisions",new LinkedHashMap<>(revisions),
                "acceptedFactIds",new ArrayList<>(accepted),"completedTargetObligationIds",new ArrayList<>(closed),
                "remainingTargetObligationIds",pending,"targetCheckCompleted",false,"businessCheckCompleted",false);
        }
        public Map<String,Object> call(String name,Map<String,Object> args){
            String target=(String)args.get("targetObligationId");
            Map<String,Object> out=m("status","COMPLETED");
            if(name.equals("search_evidence")){
                if(A.equals(target) && String.valueOf(args.get("query")).contains("合同名称"))primesA++;
                Map<String,Object> coverage=coverage(target);
                List<Map<String,Object>> refs=new ArrayList<>();
                if(A.equals(target))for(String id:pendingA){Map<String,Object> ref=original(target,id);ref.put("navigationOnly",true);refs.add(ref);}
                out.put("sources",Collections.emptyList());
                out.put("contractPacketNavigation",m("authority","CURRENT_SERVER_PROOF","targetObligationId",target,
                    "serverPlanRevision","plan","coverage",coverage,"sources",refs));
            }else if(name.equals("read_section")){
                String id=(String)args.get("sourceId");
                if(A.equals(target)){
                    pendingA.remove(id);if(pendingA.isEmpty())revisions.put(A,"a-complete");
                }else readsB.add(id);
                out.put("sources",Collections.singletonList(original(target,id)));out.put("sourceIds",Collections.singletonList(id));
            }else if(name.equals("submit_candidate_fact")){
                if(A.equals(target)){
                    submittedA++;if(submittedA==1){out.put("status","REJECTED");out.put("rejections",Collections.singletonList(m("code","CONTRACT_IDENTITY_UNPROVEN")));
                        out.put("semanticWorkItems",Collections.singletonList(m("workKey","field-A","targetObligationId",A,
                            "evidenceRevision",revisions.get(A),"terminalForRevision",false,"targetExhaustedForRevision",false,
                            "remainingRepairActions",Collections.singletonList("READ_COMPLETE_RECORD"))));}
                    else out.put("alreadyKnownFactIds",Collections.singletonList("existing-a"));
                }else{accepted.add("verified-b");closed.add(B);revisions.put(B,"new-b");}
            }else if(name.equals("recompute_check"))recomputes++;
            out.putAll(state());out.put("serverPlanRevision","plan");out.put("targetReceiptAuthority","CURRENT_SERVER_PROOF");
            if(target!=null)out.put("contractReadCoverage",coverage(target));
            return out;
        }
        private Map<String,Object> coverage(String target){
            boolean first=A.equals(target);List<Integer> available=new ArrayList<>(),pending=new ArrayList<>();
            List<String> read=new ArrayList<>();
            for(int i=1;i<=4;i++){String id=(first?"A-":"B-")+i;int page=first?i:i+10;available.add(page);
                if(first && pendingA.contains(id))pending.add(page);else read.add(id);}
            return m("authority","CURRENT_SERVER_PROOF","targetObligationId",target,"documentId","doc","documentHash","hash","documentVersion",1,
                "scopeAvailable",true,"availablePages",available,"pendingPages",pending,"missingSourcePages",Collections.emptyList(),
                "readSourceIds",read,"complete",pending.isEmpty(),"businessProof",false);
        }
        private Map<String,Object> original(String target,String id){
            int page=Integer.parseInt(id.substring(2))+(A.equals(target)?0:10);
            return m("sourceId",id,"id",id,"documentId","doc","documentHash","hash","documentVersion",1,"locationType","PAGE",
                "location",page,"quote",quote(id),"navigationOnly",false);
        }
        private String quote(String id){return "Synthetic original "+id+" exact registered wording";}
        private Map<String,Object> obligation(String id,int first,int last){
            return m("id",id,"predicate","CONTRACT","parameters",m("readScopePolicy","EXACT_DECLARED_ROW_AND_CONTRACT_PACKET",
                "declaredPerformanceRow",m("proofPageStart",first,"proofPageEnd",last,"documentId","doc","documentHash","hash",
                    "documentVersion",1,"separatorTitle",id,"inventoryPage",0)));
        }
        private Map<String,Object> tool(String name,String field){
            return m("name",name,"description","Synthetic trace tool","parameters",m("type","object","properties",
                m(field,m("type","string"),"targetObligationId",m("type","string")),"required",Collections.singletonList(field)));
        }
    }
    private static Map<String,Object> m(Object... parts){
        Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<parts.length;i+=2)map.put((String)parts[i],parts[i+1]);return map;
    }
}
