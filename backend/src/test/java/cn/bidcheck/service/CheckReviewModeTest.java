package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.verification.engine.VerificationEngine;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckReviewModeTest {
    private Checkpoint cp(String type,String title,String field){return new Checkpoint("c",title,"形式检查","HIGH",type,field,"EXISTS","营业执照","",true,true,true,null);}
    @Test void absentModeKeepsHistoricalStandardAndRejectsUnknownValues(){
        assertEquals("FIXED_WORKFLOW",CheckReviewMode.of(Map.of()));assertEquals("FIXED_WORKFLOW",CheckReviewMode.normalize(""));
        assertEquals("AGENT_LED",CheckReviewMode.normalize("AGENT_LED"));assertThrows(Exception.class,()->CheckReviewMode.normalize("MODEL_ALL"));
    }
    @Test void agentAcceptsAllConfirmedTenderTypesButDoesNotClaimFullTextEngine(){
        FocusedCheckEvaluator evaluator=new FocusedCheckEvaluator(new VerificationEngine());
        Checkpoint material=cp("MATERIAL_EXISTENCE","营业执照","material");assertFalse(evaluator.supports(material));assertTrue(evaluator.supports(material,"AGENT_LED"));
        assertFalse(evaluator.supports(cp("SEMANTIC_RESPONSE","全文一致性","fullTextConsistency"),"AGENT_LED"));
        assertFalse(CheckReviewMode.eligible(new Checkpoint("c","未确认","形式检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS","材料","",true,false,true,null)));
    }
    @Test void noChangeAndGenericGlobalRevisionCannotRestartOwnedInvestigation(){
        assertFalse(CheckReviewMode.changedEvidence(Map.of("strategy","TARGETED_EVIDENCE")));
        assertFalse(CheckReviewMode.changedEvidence(Map.of("strategy","RECOMPUTE_SHARED_EVIDENCE","dependencyTrigger",Map.of("revision",99),"dependencyTargetObligationIds",List.of("r"))));
        assertFalse(CheckReviewMode.changedEvidence(Map.of("strategy","RECOMPUTE_SHARED_EVIDENCE","dependencyTrigger",Map.of("semanticEvidenceKeys",List.of("fact")))));
        assertTrue(CheckReviewMode.changedEvidence(Map.of("strategy","RECOMPUTE_SHARED_EVIDENCE","dependencyTrigger",Map.of("semanticEvidenceKeys",List.of("fact")),"dependencyTargetObligationIds",List.of("r"))));
    }
}
