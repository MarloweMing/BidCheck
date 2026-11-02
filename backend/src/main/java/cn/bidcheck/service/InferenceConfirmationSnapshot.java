package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Document;
import java.util.*;
import static cn.bidcheck.service.BidService.*;

/** Versioned decisions for run idempotency. Rejections trigger recomputation without adding facts. */
final class InferenceConfirmationSnapshot {
    record Snapshot(String digest, Set<String> checkpointIds, List<Map<String,Object>> records) {}
    private static int number(Object value,int fallback){return value instanceof Number n&&n.doubleValue()==n.intValue()?n.intValue():fallback;}
    static Snapshot from(List<Map<String,Object>> rows,Document bid,Document tender){
        var eligible=rows.stream().filter(r->Set.of("CONFIRMED","REJECTED").contains(text(r,"status"))
            &&bid.projectId().equals(text(r,"projectId"))&&bid.id().equals(text(r,"documentId"))
            &&bid.sha256().equals(text(r,"documentHash"))&&bid.version()==number(r.get("documentVersion"),-1)
            &&Objects.equals(tender==null?"":tender.id(),text(r,"tenderDocumentId"))
            &&Objects.equals(tender==null?"":tender.sha256(),text(r,"tenderHash")))
            .sorted(Comparator.comparing(r->text(r,"id"))).toList();
        if(eligible.isEmpty())return new Snapshot("",Set.of(),List.of());
        String content=eligible.stream().map(r->text(r,"id")+":"+number(r.get("revision"),0)+":"+text(r,"status")+":"+text(r,"engineClaimId")).collect(java.util.stream.Collectors.joining("\n"));
        Set<String> ids=new LinkedHashSet<>();eligible.forEach(r->{if(!text(r,"checkpointId").isBlank())ids.add(text(r,"checkpointId"));});
        return new Snapshot(stableId("confirmed-inference-snapshot:"+content),Set.copyOf(ids),eligible.stream().map(r->fields("id",text(r,"id"),"revision",number(r.get("revision"),0))).toList());
    }
}
