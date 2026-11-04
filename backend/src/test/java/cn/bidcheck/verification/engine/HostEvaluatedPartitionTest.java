package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class HostEvaluatedPartitionTest {
    @Test void ordinaryReadWithoutNewFactsReturnsRequestBoundEvaluatedPartition() {
        EvidenceInvestigation.Host host = new EvidenceInvestigationTest().host("合成人员说明，任职尚未明确。");
        host.ctx.put("agentTargetWorkflow", true);
        Map<String,Object> start = host.start();
        Map<String,Object> result = host.call("search_evidence", linked("query", "任职", "targetObligationId", "role"));
        assertTrue(result.get("evaluatedTargetReceipt") instanceof Map<?,?>, result.keySet().toString());
        Map<?,?> receipt = (Map<?,?>) result.get("evaluatedTargetReceipt");
        assertEquals("HOST_TARGET_RECOMPUTE", receipt.get("kind"));
        assertEquals(start.get("requestId"), receipt.get("requestId"));
        assertEquals("search_evidence", receipt.get("tool"));
        assertEquals("role", receipt.get("targetObligationId"));
        assertEquals(Collections.singletonList("role"), receipt.get("targetObligationIds"));
        assertEquals("CURRENT_SERVER_PROOF", receipt.get("targetReceiptAuthority"));
        assertEquals(result.get("targetEvidenceRevision"), receipt.get("targetEvidenceRevision"));
        assertEquals(false, receipt.get("businessCheckCompleted"));
    }
}
