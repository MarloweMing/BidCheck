package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Evaluation;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.compat.Java8Api.*;
import static org.junit.jupiter.api.Assertions.*;

class Ticket231PresentationTest {
    private Map<String,Object> view(String status, Map<String,Object> a) {
        return BusinessResultPresentation.describe(new Evaluation(status,"原文核验","",Collections.emptyList(),0.0,"RULE"),a);
    }
    @Test void noPairIsANoticeWhileConflictRemainsRisk() {
        Map<String,Object> group=mapOf("consistencyGroupId","g");
        assertEquals("NOTICE",view("UNDETERMINED",group).get("state"));
        assertEquals("RISK",view("WARNING",group).get("state"));
        assertEquals("NOTICE",view("UNDETERMINED",mapOf("checkpointType","FULL_TEXT_REVIEW")).get("state"));
    }
    @Test void missingVolumeIsUserActionWhileMissingEvidenceIsSystemWork() {
        Map<String,Object> a=mapOf("atomicCheck",mapOf("gaps",listOf(mapOf("code","BID_VOLUME_NOT_UPLOADED"))));
        assertEquals("请补充材料",view("UNDETERMINED",a).get("label"));
        assertEquals(true,view("UNDETERMINED",a).get("userActionRequired"));
        assertEquals("系统取证中",view("UNDETERMINED",Collections.emptyMap()).get("label"));
    }
    @Test void ownerDataDoesNotRequireAnOperatorToRepairAConnector() {
        Map<String,Object> a=mapOf("atomicCheck",mapOf("gaps",listOf(mapOf("code","EXTERNAL_OWNER_DATA_REQUIRED"))));
        assertEquals("WAIT_EXTERNAL",view("UNDETERMINED",a).get("state"));
        assertEquals("EXTERNAL",view("UNDETERMINED",a).get("owner"));
        assertTrue(cn.bidcheck.verification.external.ExternalVerificationAdapter.requiresOwnerData(
            mapOf("providerConfigurationKnown",true,"providerSources",Collections.emptyMap()),"RELATIONS","PLATFORM"));
        assertFalse(cn.bidcheck.verification.external.ExternalVerificationAdapter.requiresOwnerData(
            mapOf("providerConfigurationKnown",true,"providerSources",mapOf("RELATIONS","PLATFORM")),"RELATIONS","PLATFORM"));
    }
    @Test void sourceClosedInventoryWithStageAndOwnerObligationsRetainsBothWaitAndUnfinishedMembers() {
        List<Map<String,Object>> gaps=new ArrayList<>(listOf(mapOf("code","EXTERNAL_OWNER_DATA_REQUIRED"),
                mapOf("code","WAIT_STAGE"),mapOf("code","REQUIREMENT_INVENTORY_COVERAGE_REQUIRED")));
        Map<String,Object> proof=mapOf("inventorySourceRangeClosed",true,"inventoryCompletedMembers",1,"inventoryTotalMembers",3);
        Map<String,Object> a=mapOf("atomicCheck",mapOf("gaps",gaps,"conditions",listOf(mapOf("proof",proof))));
        assertEquals("WAIT_EXTERNAL",view("UNDETERMINED",a).get("state"));
        assertEquals("RISK",view("FAIL",a).get("state"));
        gaps.add(mapOf("code","EXTERNAL_CONNECTOR_FAILED"));
        assertEquals("SYSTEM_INCOMPLETE",view("UNDETERMINED",a).get("state"));
    }
}
