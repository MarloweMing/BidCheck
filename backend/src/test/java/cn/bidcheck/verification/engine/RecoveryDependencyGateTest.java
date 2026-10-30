package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryDependencyGateTest {
    private Map<String,Object> rule(String id,String predicate){return new LinkedHashMap<>(Map.of("id",id,"predicate",predicate,"subject","设计单位","field","资质","operator","EXISTS","expected","甲级","sourceQuote","设计单位具有甲级资质","parameters",Map.of(),"children",List.of()));}
    private Map<String,Object> plan(String predicate){return Map.of("logic","ALL","rules",List.of(rule("R1",predicate)));}
    private Map<String,Object> context(Map<String,Object> plan,Map<String,Object> inputs){
        Map<String,Object> context=new LinkedHashMap<>(inputs);context.put("recoverySourceRevision","doc-v1");
        Map<String,Object> receipt=RecoveryDependencyGate.snapshot(context,plan);
        context.put("previousAtomicCheck",Map.of("plan",plan,"recoveryDependencies",receipt));
        context.put("recoveryStrategy","REPAIR_REQUIREMENT");context.put("recoveryTargetObligationIds",List.of("R1"));return context;
    }
    @Test void sameSourceBoundUnsupportedNodeStopsBeforeEvidenceLookup(){
        Map<String,Object> plan=plan("UNSUPPORTED"),context=context(plan,Map.of());
        var stopped=assertThrows(RecoveryDependencyGate.NoDependencyChange.class,()->RecoveryDependencyGate.begin(context,plan));
        assertEquals("REQUIREMENT_AND_INPUTS_UNCHANGED",stopped.reasonCode());assertEquals(false,stopped.receipt().get("businessProof"));
    }
    @Test void aRealRuleRepairOrSourceChangeCanAlwaysProceed(){
        Map<String,Object> plan=plan("UNSUPPORTED"),context=context(plan,Map.of());
        assertDoesNotThrow(()->RecoveryDependencyGate.begin(context,plan("QUALIFICATION")));
        context.put("recoverySourceRevision","doc-v2");assertDoesNotThrow(()->RecoveryDependencyGate.begin(context,plan));
    }
    @Test void executableRulesRequireKnownCanonicalRevisionBeforeSkipping(){
        Map<String,Object> plan=plan("QUALIFICATION");
        assertDoesNotThrow(()->RecoveryDependencyGate.begin(context(plan,Map.of()),plan));
        assertDoesNotThrow(()->RecoveryDependencyGate.begin(context(plan,Map.of("sharedFactRevision","")),plan));
        Map<String,Object> context=context(plan,Map.of("sharedFactRevision","fact-v1"));
        assertThrows(RecoveryDependencyGate.NoDependencyChange.class,()->RecoveryDependencyGate.begin(context,plan));
        context.put("sharedFactRevision","fact-v2");assertDoesNotThrow(()->RecoveryDependencyGate.begin(context,plan));
    }
    @Test void factsPublishedElsewhereCannotRepairAnUnchangedUnsupportedPredicate(){
        Map<String,Object> plan=plan("UNSUPPORTED"),context=context(plan,Map.of("sharedFactRevision","fact-v1"));
        context.put("sharedFactRevision","fact-v2");
        assertThrows(RecoveryDependencyGate.NoDependencyChange.class,()->RecoveryDependencyGate.begin(context,plan));
    }
    @Test void legacyReceiptOrNonRepairStrategyNeverSuppressesWork(){
        Map<String,Object> plan=plan("UNSUPPORTED"),context=context(plan,Map.of());
        context.put("previousAtomicCheck",Map.of("plan",plan));assertDoesNotThrow(()->RecoveryDependencyGate.begin(context,plan));
        Map<String,Object> targeted=context(plan,Map.of());targeted.put("recoveryStrategy","TARGETED_EVIDENCE");
        assertDoesNotThrow(()->RecoveryDependencyGate.begin(targeted,plan));
    }
    @Test void displayProseDoesNotInvalidateRuleButActualExternalValueDoes(){
        Map<String,Object> original=plan("UNSUPPORTED");
        Map<String,Object> record=new LinkedHashMap<>(Map.of("type","CREDIT","subject","单位","result",Map.of("status","NOT_FOUND"),"queriedAt","yesterday","effectiveAt","2026-10-26"));
        Map<String,Object> context=context(original,Map.of("externalRecords",List.of(record)));
        Map<String,Object> same=rule("R1","UNSUPPORTED");same.put("description","新的用户界面说明");same.put("parameters",Map.of("compilationIssue","另一条诊断消息"));
        record.put("queriedAt","today");
        assertThrows(RecoveryDependencyGate.NoDependencyChange.class,()->RecoveryDependencyGate.begin(context,Map.of("logic","ALL","rules",List.of(same))));
        record.put("result",Map.of("status","VERIFIED","value",true));assertDoesNotThrow(()->RecoveryDependencyGate.begin(context,original));
    }
    @Test void unsupportedParentWithExecutableChildIsConservativelyReevaluated(){
        Map<String,Object> parent=rule("R1","UNSUPPORTED");parent.put("children",List.of(rule("R2","QUALIFICATION")));
        Map<String,Object> plan=Map.of("logic","ALL","rules",List.of(parent));
        assertDoesNotThrow(()->RecoveryDependencyGate.begin(context(plan,Map.of()),plan));
    }
    @Test void emptySourceRestoreStopsWithoutChangesButVersionChangesPermitRealRecompilation(){
        Map<String,Object> empty=Map.of("logic","ALL","rules",List.of(),"method","SCOPE_UNRESOLVED");
        Map<String,Object> context=context(empty,Map.of("requirementCompilerRevision","v1","requirementMappingRevision","m1","requirementSourcePackageRevision","s1"));
        context.put("recoveryStrategy","RESTORE_REQUIREMENT_SOURCE");context.put("recoveryTargetObligationIds",List.of());
        assertThrows(RecoveryDependencyGate.NoDependencyChange.class,()->RecoveryDependencyGate.begin(context,empty));
        for(String key:List.of("requirementCompilerRevision","requirementMappingRevision","requirementSourcePackageRevision")){
            Map<String,Object> changed=new LinkedHashMap<>(context);changed.put(key,"new");
            assertDoesNotThrow(()->RecoveryDependencyGate.begin(changed,empty),key);
        }
    }
}
