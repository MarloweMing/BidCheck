package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.VerificationEngine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FocusedPurposeRoutingTest {
    private final FocusedCheckEvaluator evaluator=new FocusedCheckEvaluator(new VerificationEngine());
    Checkpoint cp(String title,String expected){return new Checkpoint("r",title,"资格","HIGH","SEMANTIC_RESPONSE","response","",expected,"",true,true,true,null);}
    @Test void blacklistClauseStillRoutesToClosedStructuredRulePath(){assertTrue(evaluator.supports(cp("供应商黑名单现场核查","核查法定代表人、委托代理人、项目负责人是否处于禁入期。")));}
    @Test void scoringClauseStillRoutesToClosedStructuredRulePath(){assertTrue(evaluator.supports(cp("施工业绩情况评分","联合体施工成员提供合同，每项得1分。")));}
    @Test void actualAppointmentAndCertificateConstraintsStillUseAtomicEngine(){
        assertTrue(evaluator.supports(cp("项目负责人任职要求","本项目项目负责人1人，须提供注册证书并在投标截止日有效。")));
    }
    @Test void explicitSigningObligationCanReachVisualExecutionWithoutPersonnelKeywords(){
        assertTrue(evaluator.supports(cp("承诺书须盖章","本项目承诺书应在要求位置加盖公章。")));
    }
    @Test void bodyInventoryDoesNotAcquireAnUnrequestedStampObligation(){
        assertTrue(evaluator.supports(cp("承诺书正文完整性","提交承诺书正文，格式末尾标有（盖章）。")));
    }
    @Test void fullDocumentConsistencyKeepsItsDedicatedScanner(){assertFalse(evaluator.supports(cp("整份投标文件全文一致性检查","核对全文中的项目名称和报价。")));}
}
