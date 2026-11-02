package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.service.BidService.*;
import static org.junit.jupiter.api.Assertions.*;

class SupplementAttachmentSnapshotTest {
    Map<String,Object> row(String id,String cp){return fields("attachmentId",id,"documentId","doc-"+id,"sha256","hash-"+id,"version",1,"checkpointId",cp,"claimId","claim","originRunId","run","status","READY","name","proof.pdf");}
    Finding finding(String id,List<String> dependencies){return new Finding("f-"+id,"run",new Checkpoint(id,id,"人员","HIGH","SEMANTIC_RESPONSE","role","EXISTS","人员","",true,true,true,null),new Evaluation("PASS","符合","",List.of(),1,"RULE"),Map.of("atomicCheck",Map.of("proof",Map.of("dependencyCheckpointIds",dependencies))));}
    @Test void onlyBoundItemAndExplicitTransitiveDependenciesReceiveSupplement(){var snapshot=SupplementAttachmentSnapshot.from(List.of(),List.of(row("a","person")),List.of(finding("person",List.of()),finding("performance",List.of("person")),finding("summary",List.of("performance")),finding("unrelated",List.of())));
        assertEquals(Set.of("person","performance","summary"),snapshot.affectedCheckpointIds());assertEquals(1,snapshot.manifest().size());assertEquals(snapshot.affectedCheckpointIds(),new HashSet<>((List<String>)snapshot.manifest().get(0).get("affectedCheckpointIds")));}
    @Test void parseFailuresAndUnboundSourcesDoNotEnterFrozenEvidence(){var failed=row("a","cp");failed.put("status","FAILED");var unbound=row("b","");var snapshot=SupplementAttachmentSnapshot.from(List.of(),List.of(failed,unbound),List.of());assertTrue(snapshot.manifest().isEmpty());assertEquals("",snapshot.digest());assertTrue(snapshot.affectedCheckpointIds().isEmpty());}
    @Test void retryWithSameManifestDoesNotInvalidateAgainAndDigestTracksBytesAndBinding(){var first=SupplementAttachmentSnapshot.from(List.of(),List.of(row("a","cp")),List.of());var retry=SupplementAttachmentSnapshot.from(first.manifest(),List.of(row("a","cp")),List.of());assertEquals(first.digest(),retry.digest());assertTrue(retry.affectedCheckpointIds().isEmpty());
        var changed=row("a","cp2");assertNotEquals(first.digest(),SupplementAttachmentSnapshot.from(List.of(),List.of(changed),List.of()).digest());changed=row("a","cp");changed.put("sha256","different");assertNotEquals(first.digest(),SupplementAttachmentSnapshot.from(List.of(),List.of(changed),List.of()).digest());}
}
