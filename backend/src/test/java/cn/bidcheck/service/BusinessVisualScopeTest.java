package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A missing form-to-page mapping is system work, not missing bidder material. */
class BusinessVisualScopeTest {
    private Evaluation pending() {
        return new Evaluation("UNDETERMINED","尚未定位要求签章的具体文书位置","",List.of(),0,"FOCUSED_ATOMIC_RULES");
    }
    private Finding finding(Map<String,Object> assessment) {
        var cp=new Checkpoint("cp","指定文书盖章","形式检查","HIGH","MATERIAL_EXISTENCE","seal","EXISTS","要求位置盖章","",true,true,true,null);
        return new Finding("f","run",cp,pending(),assessment);
    }
    @Test void unresolvedVisualScopeIsSystemWorkAndNeverDispatchesStalePageTasks() {
        var atomic=Map.<String,Object>of("workflowState","REQUIREMENT_UNPARSED","complete",false,
            "gaps",List.of(Map.of("code","REQUIREMENT_VISUAL_SCOPE_UNRESOLVED","description","格式章节尚未映射具体投标文书")),
            "visualRequests",List.of(Map.of("page",7)),
            "recoveryActions",List.of(Map.of("strategy","OBSERVE_SOURCE_PAGE")));
        var assessment=FocusedCheckEvaluator.reconcileAssessment(pending(),Map.of("atomicCheck",atomic));
        var display=CheckResolution.map(assessment.get("businessPresentation"));
        assertEquals("SYSTEM_INCOMPLETE",display.get("state"));
        assertEquals(false,display.get("userActionRequired"));
        var presentation=CheckResolution.map(CheckResolution.map(assessment.get("atomicCheck")).get("presentation"));
        assertEquals("REQUIREMENT_UNPARSED",presentation.get("state"));
        var plan=CheckResolution.plan(finding(assessment));
        assertEquals("BLOCKED",plan.get("strategy"));
        assertEquals("BLOCKED_NO_PROGRESS",plan.get("status"));
        assertTrue(plan.get("reason").toString().contains("不能据此要求补交材料"));
    }
    @Test void locatedVisualObservationStillQueuesActualPageVerification() {
        var atomic=Map.<String,Object>of("complete",false,
            "gaps",List.of(Map.of("code","VISUAL_OBSERVATION_REQUIRED")),
            "visualRequests",List.of(Map.of("documentId","bid","location",7,"locationType","PAGE","task","SEAL_VISIBILITY")));
        var plan=CheckResolution.plan(finding(Map.of("atomicCheck",atomic)));
        assertEquals("VERIFY_SOURCE_PAGES",plan.get("strategy"));
        assertEquals("QUEUED",plan.get("status"));
    }
}
