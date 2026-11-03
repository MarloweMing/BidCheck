package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A missing form-to-page mapping is system work, not missing bidder material. */
class BusinessVisualScopeTest {
    private Evaluation pending() {
        return new Evaluation("UNDETERMINED","尚未定位要求签章的具体文书位置","",cn.bidcheck.compat.Java8Api.listOf(),0,"FOCUSED_ATOMIC_RULES");
    }
    private Finding finding(Map<String,Object> assessment) {
        cn.bidcheck.domain.Domain.Checkpoint cp=new Checkpoint("cp","指定文书盖章","形式检查","HIGH","MATERIAL_EXISTENCE","seal","EXISTS","要求位置盖章","",true,true,true,null);
        return new Finding("f","run",cp,pending(),assessment);
    }
    @Test void unresolvedVisualScopeIsSystemWorkAndNeverDispatchesStalePageTasks() {
        java.util.Map<java.lang.String, java.lang.Object> atomic=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("workflowState", "REQUIREMENT_UNPARSED", "complete", false, "gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "REQUIREMENT_VISUAL_SCOPE_UNRESOLVED", "description", "格式章节尚未映射具体投标文书")), "visualRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 7)), "recoveryActions", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("strategy", "OBSERVE_SOURCE_PAGE")));
        java.util.Map<java.lang.String, java.lang.Object> assessment=FocusedCheckEvaluator.reconcileAssessment(pending(),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic));
        java.util.Map<java.lang.String, java.lang.Object> display=CheckResolution.map(assessment.get("businessPresentation"));
        assertEquals("SYSTEM_INCOMPLETE",display.get("state"));
        assertEquals(false,display.get("userActionRequired"));
        java.util.Map<java.lang.String, java.lang.Object> presentation=CheckResolution.map(CheckResolution.map(assessment.get("atomicCheck")).get("presentation"));
        assertEquals("REQUIREMENT_UNPARSED",presentation.get("state"));
        java.util.Map<java.lang.String, java.lang.Object> plan=CheckResolution.plan(finding(assessment));
        assertEquals("BLOCKED",plan.get("strategy"));
        assertEquals("BLOCKED_NO_PROGRESS",plan.get("status"));
        assertTrue(plan.get("reason").toString().contains("不能据此要求补交材料"));
    }
    @Test void locatedVisualObservationStillQueuesActualPageVerification() {
        java.util.Map<java.lang.String, java.lang.Object> atomic=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("complete", false, "gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "VISUAL_OBSERVATION_REQUIRED")), "visualRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "bid", "location", 7, "locationType", "PAGE", "task", "SEAL_VISIBILITY")));
        java.util.Map<java.lang.String, java.lang.Object> plan=CheckResolution.plan(finding(cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic)));
        assertEquals("VERIFY_SOURCE_PAGES",plan.get("strategy"));
        assertEquals("QUEUED",plan.get("status"));
    }
}
