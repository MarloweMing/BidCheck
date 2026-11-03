package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryDependencyGateTest {
    private Map<String,Object> rule(String id,String predicate){return new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("id", id, "predicate", predicate, "subject", "设计单位", "field", "资质", "operator", "EXISTS", "expected", "甲级", "sourceQuote", "设计单位具有甲级资质", "parameters", cn.bidcheck.compat.Java8Api.mapOf(), "children", cn.bidcheck.compat.Java8Api.listOf()));}
    private Map<String,Object> plan(String predicate){return cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(rule("R1",predicate)));}
    private Map<String,Object> context(Map<String,Object> plan,Map<String,Object> inputs){
        Map<String,Object> context=new LinkedHashMap<>(inputs);context.put("recoverySourceRevision","doc-v1");
        Map<String,Object> receipt=RecoveryDependencyGate.snapshot(context,plan);
        context.put("previousAtomicCheck",cn.bidcheck.compat.Java8Api.mapOf("plan", plan, "recoveryDependencies", receipt));
        context.put("recoveryStrategy","REPAIR_REQUIREMENT");context.put("recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("R1"));return context;
    }
    @Test void sameSourceBoundUnsupportedNodeStopsBeforeEvidenceLookup(){
        Map<String,Object> plan=plan("UNSUPPORTED"),context=context(plan,cn.bidcheck.compat.Java8Api.mapOf());
        cn.bidcheck.verification.engine.RecoveryDependencyGate.NoDependencyChange stopped=assertThrows(RecoveryDependencyGate.NoDependencyChange.class,()->RecoveryDependencyGate.begin(context,plan));
        assertEquals("REQUIREMENT_AND_INPUTS_UNCHANGED",stopped.reasonCode());assertEquals(false,stopped.receipt().get("businessProof"));
    }
    @Test void aRealRuleRepairOrSourceChangeCanAlwaysProceed(){
        Map<String,Object> plan=plan("UNSUPPORTED"),context=context(plan,cn.bidcheck.compat.Java8Api.mapOf());
        assertDoesNotThrow(()->RecoveryDependencyGate.begin(context,plan("QUALIFICATION")));
        context.put("recoverySourceRevision","doc-v2");assertDoesNotThrow(()->RecoveryDependencyGate.begin(context,plan));
    }
    @Test void executableRulesRequireKnownCanonicalRevisionBeforeSkipping(){
        Map<String,Object> plan=plan("QUALIFICATION");
        assertDoesNotThrow(()->RecoveryDependencyGate.begin(context(plan,cn.bidcheck.compat.Java8Api.mapOf()),plan));
        assertDoesNotThrow(()->RecoveryDependencyGate.begin(context(plan,cn.bidcheck.compat.Java8Api.mapOf("sharedFactRevision", "")),plan));
        Map<String,Object> context=context(plan,cn.bidcheck.compat.Java8Api.mapOf("sharedFactRevision", "fact-v1"));
        assertThrows(RecoveryDependencyGate.NoDependencyChange.class,()->RecoveryDependencyGate.begin(context,plan));
        context.put("sharedFactRevision","fact-v2");assertDoesNotThrow(()->RecoveryDependencyGate.begin(context,plan));
    }
    @Test void factsPublishedElsewhereCannotRepairAnUnchangedUnsupportedPredicate(){
        Map<String,Object> plan=plan("UNSUPPORTED"),context=context(plan,cn.bidcheck.compat.Java8Api.mapOf("sharedFactRevision", "fact-v1"));
        context.put("sharedFactRevision","fact-v2");
        assertThrows(RecoveryDependencyGate.NoDependencyChange.class,()->RecoveryDependencyGate.begin(context,plan));
    }
    @Test void legacyReceiptOrNonRepairStrategyNeverSuppressesWork(){
        Map<String,Object> plan=plan("UNSUPPORTED"),context=context(plan,cn.bidcheck.compat.Java8Api.mapOf());
        context.put("previousAtomicCheck",cn.bidcheck.compat.Java8Api.mapOf("plan", plan));assertDoesNotThrow(()->RecoveryDependencyGate.begin(context,plan));
        Map<String,Object> targeted=context(plan,cn.bidcheck.compat.Java8Api.mapOf());targeted.put("recoveryStrategy","TARGETED_EVIDENCE");
        assertDoesNotThrow(()->RecoveryDependencyGate.begin(targeted,plan));
    }
    @Test void displayProseDoesNotInvalidateRuleButActualExternalValueDoes(){
        Map<String,Object> original=plan("UNSUPPORTED");
        Map<String,Object> record=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("type", "CREDIT", "subject", "单位", "result", cn.bidcheck.compat.Java8Api.mapOf("status", "NOT_FOUND"), "queriedAt", "yesterday", "effectiveAt", "2026-10-26"));
        Map<String,Object> context=context(original,cn.bidcheck.compat.Java8Api.mapOf("externalRecords", cn.bidcheck.compat.Java8Api.listOf(record)));
        Map<String,Object> same=rule("R1","UNSUPPORTED");same.put("description","新的用户界面说明");same.put("parameters",cn.bidcheck.compat.Java8Api.mapOf("compilationIssue", "另一条诊断消息"));
        record.put("queriedAt","today");
        assertThrows(RecoveryDependencyGate.NoDependencyChange.class,()->RecoveryDependencyGate.begin(context,cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(same))));
        record.put("result",cn.bidcheck.compat.Java8Api.mapOf("status", "VERIFIED", "value", true));assertDoesNotThrow(()->RecoveryDependencyGate.begin(context,original));
    }
    @Test void unsupportedParentWithExecutableChildIsConservativelyReevaluated(){
        Map<String,Object> parent=rule("R1","UNSUPPORTED");parent.put("children",cn.bidcheck.compat.Java8Api.listOf(rule("R2","QUALIFICATION")));
        Map<String,Object> plan=cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(parent));
        assertDoesNotThrow(()->RecoveryDependencyGate.begin(context(plan,cn.bidcheck.compat.Java8Api.mapOf()),plan));
    }
    @Test void emptySourceRestoreStopsWithoutChangesButVersionChangesPermitRealRecompilation(){
        Map<String,Object> empty=cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(), "method", "SCOPE_UNRESOLVED");
        Map<String,Object> context=context(empty,cn.bidcheck.compat.Java8Api.mapOf("requirementCompilerRevision", "v1", "requirementMappingRevision", "m1", "requirementSourcePackageRevision", "s1"));
        context.put("recoveryStrategy","RESTORE_REQUIREMENT_SOURCE");context.put("recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf());
        assertThrows(RecoveryDependencyGate.NoDependencyChange.class,()->RecoveryDependencyGate.begin(context,empty));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("requirementCompilerRevision", "requirementMappingRevision", "requirementSourcePackageRevision")){
            Map<String,Object> changed=new LinkedHashMap<>(context);changed.put(key,"new");
            assertDoesNotThrow(()->RecoveryDependencyGate.begin(changed,empty),key);
        }
    }
}
