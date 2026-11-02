package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Document;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.*;

class InferenceConfirmationSnapshotTest {
    final Document bid=new Document("bid","project","BID","bid.pdf",2,"sha","",List.of(),List.of());
    final Document tender=new Document("tender","project","TENDER","tender.docx",1,"tsha","",List.of(),List.of());
    Map<String,Object> record(){return fields("id","claim","engineClaimId","engine-claim","checkpointId","cp","projectId","project","documentId","bid","documentHash","sha","documentVersion",2,"tenderDocumentId","tender","tenderHash","tsha","status","CONFIRMED","revision",1);}
    @Test void confirmedEvidenceChangesRunIdentityButOrderingDoesNot(){
        var first=record();var second=record();second.put("id","second");second.put("checkpointId","other");
        var one=InferenceConfirmationSnapshot.from(List.of(first),bid,tender);
        var two=InferenceConfirmationSnapshot.from(List.of(first,second),bid,tender);
        assertNotEquals(one.digest(),two.digest());assertEquals(two.digest(),InferenceConfirmationSnapshot.from(List.of(second,first),bid,tender).digest());
        second.put("revision",2);assertNotEquals(two.digest(),InferenceConfirmationSnapshot.from(List.of(first,second),bid,tender).digest());
        assertEquals(Set.of("cp","other"),two.checkpointIds());
    }
    @Test void staleUndecidedOrDifferentScopeCannotUnlockRecovery(){
        for(var change:List.of(Map.entry("status",(Object)"NEEDS_CONFIRMATION"),Map.entry("projectId",(Object)"other"),Map.entry("documentVersion",(Object)1),Map.entry("documentHash",(Object)"stale"),Map.entry("tenderDocumentId",(Object)"different"))){
            var row=record();row.put(change.getKey(),change.getValue());
            assertEquals("",InferenceConfirmationSnapshot.from(List.of(row),bid,tender).digest(),change.getKey());
        }
    }
    @Test void rejectionIsVersionedInputButNotAConfirmedFact(){
        var row=record();var accepted=InferenceConfirmationSnapshot.from(List.of(row),bid,tender);row.put("status","REJECTED");
        var rejected=InferenceConfirmationSnapshot.from(List.of(row),bid,tender);assertNotEquals(accepted.digest(),rejected.digest());assertFalse(rejected.records().isEmpty());
    }
}
