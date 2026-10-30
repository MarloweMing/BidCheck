package cn.bidcheck.verification.engine;

import java.util.*;

/** Revisions of server-owned requirement work, independent of model or evidence receipts. */
public final class RequirementRepairPolicy {
    public static final String VERSION="requirement-repair-execution-2-business-paths";
    public static final String MAPPING_VERSION="requirement-field-mapping-2";
    private RequirementRepairPolicy() {}
    public static boolean requirementWork(String strategy){
        return Set.of("RESTORE_REQUIREMENT_SOURCE","RECOMPILE_REQUIREMENT","REPAIR_REQUIREMENT").contains(Objects.toString(strategy,""));
    }
    public static boolean executableBusinessPredicate(String predicate){return VerificationTypes.PREDICATES.contains(Objects.toString(predicate,""))
        &&!Set.of("UNSUPPORTED","INPUT","REFERENCE_TIME","NOT_APPLICABLE").contains(predicate);}
    public static Map<String,Object> versions(){
        return Map.of("requirementRepairVersion",VERSION,"requirementCompilerRevision",DeterministicRequirementCompiler.VERSION,
            "requirementSourceRepairRevision",RequirementSourceRepair.VERSION,"requirementMappingRevision",MAPPING_VERSION,
            "requirementValidationRevision",CompilationRecovery.VERSION);
    }
    static boolean recompile(Map<String,Object> context){
        return requirementWork(VerificationTypes.str(context,"recoveryStrategy"))
            ||Set.of("RESTORE_REQUIREMENT_SOURCE","RECOMPILE_REQUIREMENT","REPAIR_REQUIREMENT").contains(VerificationTypes.str(context,"recoveryMode"));
    }
}
