package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import java.util.*;
import static cn.bidcheck.service.BidService.fields;
import static cn.bidcheck.service.BidService.text;
import static cn.bidcheck.service.CheckResolution.*;

/** Preserve multiple physical-read cursors. The persisted pendingTableWork key also reads older table-only findings. */
final class PendingTableWork {
    private PendingTableWork() {}

    static List<Map<String,Object>> pending(Finding finding,String id) {
        Map<String,Object> body=atomic(finding);
        Map<String,Object> memory=new LinkedHashMap<>(map(body.get("pendingTableWork")));
        capture(memory,map(body.get("agentInvestigation")));
        Map<String,Object> snapshot=map(map(memory.get(id)).get("serverTargetInputConsumption"));
        for(Map<String,Object> target:maps(snapshot.get("targets")))
            if(id.equals(text(target,"targetObligationId")))return maps(target.get("pendingReadActions"));
        return Collections.emptyList();
    }

    static Finding preserve(Finding before,Finding after) {
        Map<String,Object> previous=atomic(before), current=atomic(after);
        Map<String,Object> memory=new LinkedHashMap<>(map(previous.get("pendingTableWork")));
        capture(memory,map(previous.get("agentInvestigation")));
        capture(memory,map(current.get("agentInvestigation")));
        if(memory.isEmpty() && !current.containsKey("pendingTableWork"))return after;
        if(memory.equals(current.get("pendingTableWork")))return after;
        Map<String,Object> body=new LinkedHashMap<>(current);
        if(memory.isEmpty())body.remove("pendingTableWork");else body.put("pendingTableWork",memory);
        Map<String,Object> assessment=new LinkedHashMap<>(after.assessment());assessment.put("atomicCheck",body);
        return new Finding(after.id(),after.runId(),after.checkpoint(),after.evaluation(),assessment);
    }

    private static void capture(Map<String,Object> memory,Map<String,Object> audit) {
        Map<String,Object> receipt=map(audit.get("serverTargetInputConsumption"));
        String request=text(audit,"requestId"),plan=text(audit,"serverPlanRevision");
        if(request.isEmpty() || plan.isEmpty() || !request.equals(text(receipt,"requestId"))
            || !plan.equals(text(receipt,"serverPlanRevision")) || !"CURRENT_SERVER_PROOF".equals(text(audit,"targetReceiptAuthority"))
            || !"server-target-input-consumption-1".equals(text(receipt,"version"))
            || !"CURRENT_SERVER_PROOF".equals(text(receipt,"authority")) || !Boolean.FALSE.equals(receipt.get("businessProof")))return;
        for(Map<String,Object> row:maps(receipt.get("targets"))) {
            String id=text(row,"targetObligationId"),revision=text(row,"evidenceRevision");
            if(id.isEmpty() || revision.isEmpty() || !revision.equals(map(audit.get("targetEvidenceRevisions")).get(id)))continue;
            List<Map<String,Object>> pending=new ArrayList<>();
            for(Map<String,Object> action:maps(row.get("pendingReadActions")))
                if(cn.bidcheck.compat.Java8Api.setOf("READ_TABLE_CONTINUATION","READ_SECTION_CONTINUATION").contains(text(action,"action")))pending.add(action);
            if(pending.isEmpty()){
                if((audit.get("completedTargetObligationIds") instanceof Collection
                    && ((Collection<?>)audit.get("completedTargetObligationIds")).contains(id))
                    || maps(audit.get("tableContinuationExecutions")).stream().anyMatch(exec->id.equals(text(exec,"targetObligationId")))
                    || maps(audit.get("sectionContinuationExecutions")).stream().anyMatch(exec->id.equals(text(exec,"targetObligationId"))))
                    memory.remove(id);
                continue;
            }
            Map<String,Object> saved=fields("targetObligationId",id,"evidenceRevision",revision,"pendingReadActions",pending);
            memory.put(id,fields("requestId",request,"targetReceiptAuthority","CURRENT_SERVER_PROOF","serverPlanRevision",plan,
                "targetEvidenceRevisions",fields(id,revision),"serverTargetInputConsumption",fields("version",receipt.get("version"),
                "authority",receipt.get("authority"),"businessProof",false,"requestId",request,"serverPlanRevision",plan,
                "targets",Collections.singletonList(saved))));
        }
    }
}
