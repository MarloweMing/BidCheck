package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.RecoveryFailureRouting;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryRecoveryRoutingTest {
    private Finding finding(String gap){
        cn.bidcheck.domain.Domain.Checkpoint cp=new Checkpoint("cp","范围明确的要求","形式检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS","业务要求","",true,true,true,null);
        return new Finding("f","run",cp,new Evaluation("UNDETERMINED","尚未完成","",cn.bidcheck.compat.Java8Api.listOf(),0,"FOCUSED_ATOMIC_RULES"),
            cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("compilationAudit", cn.bidcheck.compat.Java8Api.mapOf("state", "VALIDATED"), "gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", gap)))));
    }
    private Map<String,Object> display(Finding finding){return BusinessResultPresentation.describe(finding.evaluation(),finding.assessment());}
    @Test void missingTenderAttachmentRequestsTheTenderInputInsteadOfMissingBidMaterials(){
        Finding f=finding("MISSING_TENDER_ATTACHMENT");java.util.Map<java.lang.String, java.lang.Object> presentation=display(f);java.util.Map<java.lang.String, java.lang.Object> resolution=CheckResolution.plan(f);
        assertEquals("USER_ACTION",presentation.get("state"));assertEquals("缺招标附件",presentation.get("label"));
        assertEquals("WAIT_TENDER_ATTACHMENT",resolution.get("strategy"));assertEquals("WAITING_USER",resolution.get("status"));
        assertEquals("REQUIREMENT_INPUT",RecoveryFailureRouting.owner("MISSING_TENDER_ATTACHMENT"));
    }
    @Test void failedConnectorRemainsSystemFailureRatherThanExternalInputAbsence(){
        Finding f=finding("EXTERNAL_CONNECTOR_FAILED");assertEquals("SYSTEM_INCOMPLETE",display(f).get("state"));
        assertEquals("FAILED",CheckResolution.plan(f).get("status"));assertEquals("PROCESSING",RecoveryFailureRouting.owner("EXTERNAL_CONNECTOR_FAILED"));
    }
    @Test void unknownCostExemptionCannotTurnIntoFailedCostOrMissingBidAttachment(){
        Finding f=finding("COST_APPLICABILITY_REQUIRED");assertEquals("USER_ACTION",display(f).get("state"));
        assertEquals("适用条件待确认",display(f).get("label"));assertEquals("WAIT_APPLICABILITY",CheckResolution.plan(f).get("strategy"));
        assertEquals("WAITING_USER",CheckResolution.plan(f).get("status"));assertEquals("APPLICABILITY",RecoveryFailureRouting.owner("COST_APPLICABILITY_REQUIRED"));
    }
    @Test void unresolvedInventoryIsSystemWorkAndCannotQueueEndlessEvidenceSearch(){
        Finding f=finding("REQUIREMENT_INVENTORY_COVERAGE_REQUIRED");assertEquals("SYSTEM_INCOMPLETE",display(f).get("state"));
        assertEquals(false,display(f).get("userActionRequired"));assertEquals("WAIT_REQUIREMENT_DEPENDENCIES",CheckResolution.plan(f).get("strategy"));
        assertEquals("BLOCKED_NO_PROGRESS",CheckResolution.plan(f).get("status"));assertEquals("REQUIREMENT",RecoveryFailureRouting.owner("REQUIREMENT_INVENTORY_COVERAGE_REQUIRED"));
    }
    @Test void absentCapabilityCriteriaRequestsStandardsNotMoreBidMaterials(){
        Finding f=finding("CAPABILITY_CRITERIA_REQUIRED");assertEquals("USER_ACTION",display(f).get("state"));
        assertEquals("核验标准待明确",display(f).get("label"));assertEquals("WAIT_REQUIREMENT_CRITERIA",CheckResolution.plan(f).get("strategy"));
        assertEquals("WAITING_USER",CheckResolution.plan(f).get("status"));assertEquals("REQUIREMENT_CLARIFICATION",RecoveryFailureRouting.owner("CAPABILITY_CRITERIA_REQUIRED"));
    }
    @Test void pendingIndependentStatusStatementIsStillReadWhenTheCapacityCriterionNeedsClarification(){
        Finding example=finding("CAPABILITY_CRITERIA_REQUIRED");
        Map<String,Object> read=new LinkedHashMap<>();
        read.put("authority","CURRENT_HOST_TEXT_READ");read.put("pendingSourceIds",Collections.singletonList("new-declaration"));
        Map<String,Object> atomic=new LinkedHashMap<>(CheckResolution.atomic(example));
        atomic.put("conditions",Collections.singletonList(Collections.singletonMap("proof",Collections.singletonMap("registrationHostRead",read))));
        Finding updated=new Finding(example.id(),example.runId(),example.checkpoint(),example.evaluation(),Collections.singletonMap("atomicCheck",atomic));
        assertEquals("TARGETED_EVIDENCE",CheckResolution.plan(updated).get("strategy"));
        read.put("pendingSourceIds",Collections.emptyList());assertEquals("WAIT_REQUIREMENT_CRITERIA",CheckResolution.plan(updated).get("strategy"));
    }
}
