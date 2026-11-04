package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evaluation;
import cn.bidcheck.service.BusinessResultPresentation;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.compat.Java8Api.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class Ticket231ConfirmationTest {
    @Test void stalledInvestigationDoesNotBlockGroundedQuestions() {
        Decision mixed = new Decision("t","资格","UNDETERMINED","仍缺证明",listOf(),
            listOf(gap("SCORING_TITLE_IDENTITY_REQUIRED","证明中的姓名已遮蔽"),gap("AGENT_INVESTIGATION_STALLED","未发现新页")),mapOf());
        assertFalse(ProgrammaticInferenceCandidates.technical(mixed));
    }
    @Test void mixedGapsCarryCardCountUpToBusinessPresentation() {
        Decision mixed = new Decision("child","业绩","UNDETERMINED","另有待核条件",listOf(),
            listOf(gap("PERFORMANCE_PROOF_COVERAGE_REQUIRED","合同金额")),linked("pendingConfirmationCount",1));
        Decision root = RuleEvaluator.combine("root","评分","ALL",listOf(mixed));
        assertEquals(1,root.proof().get("pendingConfirmationCount"));
        Map<String,Object> assessment=linked("atomicCheck",linked("proof",root.proof()));
        Evaluation unknown=new Evaluation("UNDETERMINED","", "", listOf(),0,"ATOMIC");
        Map<String,Object> p=BusinessResultPresentation.describe(unknown,assessment);
        assertEquals("USER_ACTION",p.get("state"));
        assertTrue(p.get("nextStep").toString().contains("1"));
        assertEquals("RISK",BusinessResultPresentation.describe(new Evaluation("FAIL","", "", listOf(),0,"ATOMIC"),assessment).get("state"));
    }
}
