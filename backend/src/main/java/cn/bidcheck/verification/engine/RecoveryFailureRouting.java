package cn.bidcheck.verification.engine;

import java.util.Set;

/** Shared ownership of a gap prevents display and execution from assigning it to different actors. */
public final class RecoveryFailureRouting {
    private RecoveryFailureRouting() {}
    public static boolean requirement(String code){
        return code!=null&&(code.startsWith("REQUIREMENT_")||code.contains("COMPILATION")||Set.of("SCOPE_UNRESOLVED","RULE_VALIDATION_FAILED","UNSUPPORTED_RULE",
            "PRICE_REQUIREMENT_DIMENSIONS_REQUIRED","PERSON_COUNT_COMPARISON_REQUIRED","PERSON_COUNT_ROLE_REQUIRED","OPERATOR_UNSUPPORTED","SUBJECT_REQUIRED").contains(code));
    }
    public static boolean correspondence(String code){
        return Set.of("COMPARISON_FIELD_MAPPING_REQUIRED","COMPARISON_RECORD_IDENTITY_UNPROVEN","REGISTRATION_CERTIFICATE_REQUIRED").contains(code);
    }
}
