package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.RecoveryTargets;
import cn.bidcheck.verification.engine.Ticket09ReadTargetTest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.service.BidService.fields;
import static cn.bidcheck.service.BidService.text;
import static cn.bidcheck.service.CheckResolution.*;
import static org.junit.jupiter.api.Assertions.*;

class VisualContractRecoveryTest {
    private final Map<String,Object> one=row(1,153,159),two=row(2,160,163);
    private final Map<String,Object> rule=Ticket09ReadTargetTest.syntheticPerformancePlan(Arrays.asList(one,two));
    private final String parent=text(rule,"id");
    private final List<String> children=new ArrayList<>();
    VisualContractRecoveryTest(){
        Map<String,Map<String,Object>> reads=RecoveryTargets.readDependencies(Collections.singletonList(rule));
        for(Map.Entry<String,Map<String,Object>> entry:reads.entrySet())
            if(entry.getKey().contains("-CONTRACTS-ROW-"))children.add(entry.getKey());
        assertEquals(2,children.size());
    }
    private static Map<String,Object> row(int sequence,int start,int end){
        return fields("row",sequence,"documentId","bid","documentHash","hash","documentVersion",1,"inventoryPage",152,
            "inventoryIdentity","bid:152:"+sequence,"proofPageStart",start,"proofPageEnd",end,"separatorTitle","合成合同"+sequence);
    }
    private Finding finding(List<String> rowOneFacts,List<String> rowTwoFacts){
        Map<String,Object> req=fields("documentId","bid","locationType","PAGE","location",157,"task","FIELD",
            "reason","CONTRACT_SIGNING_DATE_ORIGINAL_REQUIRED","obligationId",parent,"sourceIds",Collections.singletonList("date-original"),"fields",Collections.singletonList("合同签订日期"));
        Map<String,Object> body=fields("plan",fields("rules",Collections.singletonList(rule)),"visualRequests",Collections.singletonList(req),
            "conditions",Collections.singletonList(fields("id",parent,"status","UNDETERMINED","gaps",Collections.singletonList(fields("code","PERFORMANCE_PROOF_COVERAGE_REQUIRED")),
            "proof",fields("contractChecks",Arrays.asList(fields("declaredRow",one,"factIds",rowOneFacts),fields("declaredRow",two,"factIds",rowTwoFacts))))));
        Checkpoint cp=new Checkpoint("cp","合成检查","test","HIGH","SEMANTIC_RESPONSE","","","","",true,true,true,null);
        return new Finding("f","run",cp,new Evaluation("UNDETERMINED","pending","",Collections.emptyList(),0,"RULE"),fields("atomicCheck",body));
    }
    private Map<String,Object> observation(){
        return fields("id","image-field","obligationId",parent,"documentId","bid","page",157,"locationType","PAGE","type","FIELD",
            "requestedTask","FIELD","requestedFields",Collections.singletonList("合同签订日期"),"requestedSourceIds",Collections.singletonList("date-original"),
            "readability","CLEAR","label","合同签订日期","value","2022年12月23日","imageHash","image","sourceBbox",Arrays.asList(10,10,120,40),
            "source",fields("documentId","bid","documentHash","hash","documentVersion",1,"page",157,"renderedSha256","image",
                "renderedWidth",200,"renderedHeight",300));
    }
    private Map<String,Object> next(String strategy){return fields("strategy",strategy,"status","QUEUED");}
    @Test void onlyTheRequestedSigningOriginalNarrowsLocalReadsAndUnchangedSiblingNeverGetsTheNextTextInvestigation(){
        Finding before=finding(Collections.emptyList(),Collections.singletonList("known-for-second"));
        Map<String,Object> generic=observation();generic.put("obligationId","");generic.put("requestedSourceIds",Collections.emptyList());
        Map<String,Object> context=new LinkedHashMap<>();
        Map<String,Object> scope=VisualContractRecovery.bind(before,Arrays.asList(generic,observation()),context);
        assertEquals(Collections.singletonList(children.get(0)),context.get("recoveryTargetObligationIds"));
        assertEquals(1,maps(map(scope.get("byTarget")).get(children.get(0))).size());
        Map<String,Object> plan=next("TARGETED_EVIDENCE");
        assertTrue(VisualContractRecovery.continuation(before,finding(Collections.singletonList("observed-date"),Collections.singletonList("known-for-second")),
            scope,plan,Collections.emptyMap(),Collections.emptyList(),"policy").isEmpty());
        assertEquals("BLOCKED_NO_PROGRESS",plan.get("status"));assertEquals(false,scope.get("businessProof"));
    }
    @Test void anIndependentlyNewGroundedFactReopensOnlyTheSecondDeclaredRow(){
        Finding before=finding(Collections.emptyList(),Collections.emptyList());
        Map<String,Object> scope=VisualContractRecovery.bind(before,Collections.singletonList(observation()),new LinkedHashMap<>());
        Map<String,Object> plan=next("TARGETED_EVIDENCE");
        Map<String,Object> continuation=VisualContractRecovery.continuation(before,finding(Collections.singletonList("observed-date"),
            Collections.singletonList("new-second-row-original")),scope,plan,Collections.emptyMap(),Collections.emptyList(),"policy");
        assertEquals(Collections.singletonList(children.get(1)),continuation.get("dependencyTargetObligationIds"));
        assertEquals("QUEUED",plan.get("status"));
    }
    @Test void anotherPendingOriginalAndTechnicalRetryKeepTheirOwnRoutesAndUnboundObservationsCannotBorrowSourceAuthority(){
        Finding before=finding(Collections.emptyList(),Collections.emptyList());
        Map<String,Object> scope=VisualContractRecovery.bind(before,Collections.singletonList(observation()),new LinkedHashMap<>());
        for(String strategy:Arrays.asList("VERIFY_SOURCE_PAGES","RETRY_FAILED_STEP")){
            Map<String,Object> plan=next(strategy);
            VisualContractRecovery.continuation(before,before,scope,plan,Collections.emptyMap(),Collections.emptyList(),"policy");
            assertEquals("QUEUED",plan.get("status"));assertEquals(strategy,plan.get("strategy"));
        }
        Map<String,Object> failed=next("TARGETED_EVIDENCE");
        VisualContractRecovery.continuation(before,before,scope,failed,Collections.emptyMap(),
            Collections.singletonList(fields("code","VISUAL_TIMEOUT")),"policy");
        assertEquals("QUEUED",failed.get("status"));
        for(String variant:Arrays.asList("generic","wrongSource","wrongVersion","unreadable")){
            Map<String,Object> obs=observation();
            if("generic".equals(variant))obs.put("obligationId","");
            if("wrongSource".equals(variant))obs.put("requestedSourceIds",Collections.singletonList("foreign"));
            if("wrongVersion".equals(variant))((Map<String,Object>)obs.get("source")).put("documentVersion",2);
            if("unreadable".equals(variant))obs.put("readability","UNREADABLE");
            assertTrue(VisualContractRecovery.bind(before,Collections.singletonList(obs),new LinkedHashMap<>()).isEmpty(),variant);
        }
    }
}
