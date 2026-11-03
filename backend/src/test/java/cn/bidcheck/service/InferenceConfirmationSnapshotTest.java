package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Document;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.*;

class InferenceConfirmationSnapshotTest {
    final Document bid=new Document("bid","project","BID","bid.pdf",2,"sha","",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());
    final Document tender=new Document("tender","project","TENDER","tender.docx",1,"tsha","",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());
    Map<String,Object> record(){return fields("id","claim","engineClaimId","engine-claim","checkpointId","cp","projectId","project","documentId","bid","documentHash","sha","documentVersion",2,"tenderDocumentId","tender","tenderHash","tsha","status","CONFIRMED","revision",1);}
    @Test void confirmedEvidenceChangesRunIdentityButOrderingDoesNot(){
        java.util.Map<java.lang.String, java.lang.Object> first=record();java.util.Map<java.lang.String, java.lang.Object> second=record();second.put("id","second");second.put("checkpointId","other");
        cn.bidcheck.service.InferenceConfirmationSnapshot.Snapshot one=InferenceConfirmationSnapshot.from(cn.bidcheck.compat.Java8Api.listOf(first),bid,tender);
        cn.bidcheck.service.InferenceConfirmationSnapshot.Snapshot two=InferenceConfirmationSnapshot.from(cn.bidcheck.compat.Java8Api.listOf(first, second),bid,tender);
        assertNotEquals(one.digest(),two.digest());assertEquals(two.digest(),InferenceConfirmationSnapshot.from(cn.bidcheck.compat.Java8Api.listOf(second, first),bid,tender).digest());
        second.put("revision",2);assertNotEquals(two.digest(),InferenceConfirmationSnapshot.from(cn.bidcheck.compat.Java8Api.listOf(first, second),bid,tender).digest());
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("cp", "other"),two.checkpointIds());
    }
    @Test void staleUndecidedOrDifferentScopeCannotUnlockRecovery(){
        for(java.util.Map.Entry<java.lang.String, java.lang.Object> change:cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.entry("status", (Object)"NEEDS_CONFIRMATION"), cn.bidcheck.compat.Java8Api.entry("projectId", (Object)"other"), cn.bidcheck.compat.Java8Api.entry("documentVersion", (Object)1), cn.bidcheck.compat.Java8Api.entry("documentHash", (Object)"stale"), cn.bidcheck.compat.Java8Api.entry("tenderDocumentId", (Object)"different"))){
            java.util.Map<java.lang.String, java.lang.Object> row=record();row.put(change.getKey(),change.getValue());
            assertEquals("",InferenceConfirmationSnapshot.from(cn.bidcheck.compat.Java8Api.listOf(row),bid,tender).digest(),change.getKey());
        }
    }
    @Test void rejectionIsVersionedInputButNotAConfirmedFact(){
        java.util.Map<java.lang.String, java.lang.Object> row=record();cn.bidcheck.service.InferenceConfirmationSnapshot.Snapshot accepted=InferenceConfirmationSnapshot.from(cn.bidcheck.compat.Java8Api.listOf(row),bid,tender);row.put("status","REJECTED");
        cn.bidcheck.service.InferenceConfirmationSnapshot.Snapshot rejected=InferenceConfirmationSnapshot.from(cn.bidcheck.compat.Java8Api.listOf(row),bid,tender);assertNotEquals(accepted.digest(),rejected.digest());assertFalse(rejected.records().isEmpty());
    }
}
