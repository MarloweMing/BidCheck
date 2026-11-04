package cn.bidcheck.verification.engine;

import java.util.Set;

/** Shared ownership of a gap prevents display and execution from assigning it to different actors. */
public final class RecoveryFailureRouting {
    private RecoveryFailureRouting() {}
    /** A rejected typed fact is not evidence that OCR failed. Keep the repair causal. */
    public static String candidateAction(String code){
        if(code==null)return "CORRECT_CANDIDATE";
        if ("CONTRACT_IDENTITY_UNPROVEN".equals(code) || "CONTRACT_IDENTITY_CONFLICT".equals(code))
            return "CORRECT_CONTRACT_IDENTITY_FIELDS";
        if(cn.bidcheck.compat.Java8Api.setOf("OCR_TEXT_UNREADABLE", "SOURCE_IMAGE_REQUIRED", "VISUAL_FIELD_UNREADABLE").contains(code))return "VERIFY_SOURCE_PAGES";
        if(requirement(code))return "REPAIR_REQUIREMENT";
        if(relationship(code)||cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE_BINDING_INVALID", "CERTIFICATE_SUBJECT_FIELD_BINDING_INVALID").contains(code))return "RESOLVE_SUBJECT_FIRST";
        if(cn.bidcheck.compat.Java8Api.setOf("RAW_VALUE_UNGROUNDED", "VALUE_UNGROUNDED", "SOURCE_REFERENCE_INVALID", "MONTH_ROW_UNPROVEN", "DATE_FIELD_BINDING_INVALID", "AMOUNT_OR_NUMBER_BINDING_INVALID").contains(code))return "READ_SOURCE_OR_TABLE";
        if("FACT_OUTSIDE_TARGET".equals(code))return "USE_TARGET_CONTRACT";
        return "CORRECT_CANDIDATE";
    }
    public static boolean candidateNeedsImage(String code){return "VERIFY_SOURCE_PAGES".equals(candidateAction(code));}
    public static boolean requirement(String code){
        return code!=null&&(code.startsWith("REQUIREMENT_")||code.contains("COMPILATION")||cn.bidcheck.compat.Java8Api.setOf("SCOPE_UNRESOLVED", "RULE_VALIDATION_FAILED", "UNSUPPORTED_RULE", "PRICE_REQUIREMENT_DIMENSIONS_REQUIRED", "PERSON_COUNT_COMPARISON_REQUIRED", "PERSON_COUNT_ROLE_REQUIRED", "OPERATOR_UNSUPPORTED", "SUBJECT_REQUIRED", "DEPENDENCY_REFERENCE_PENDING", "QUALIFICATION_PROOF_PARENT_REQUIRED", "SCORING_EXCEPTION_RULE_REQUIRED", "CONDITIONAL_MATERIAL_SOURCE_REQUIRED", "RULE_SOURCE_VALIDATION", "COMMITMENT_REQUIREMENT_BINDING_REQUIRED", "COMMITMENT_PERSONNEL_SCOPE_REQUIRED").contains(code));
    }
    public static boolean correspondence(String code){
        return cn.bidcheck.compat.Java8Api.setOf("COMPARISON_FIELD_MAPPING_REQUIRED", "COMPARISON_RECORD_IDENTITY_UNPROVEN", "REGISTRATION_CERTIFICATE_REQUIRED").contains(code);
    }
    public static boolean relationship(String code){
        return code!=null&&(code.contains("SUBJECT")||code.contains("IDENTITY")||code.contains("RELATION")||code.contains("EMPLOYER")||code.contains("OWNERSHIP"));
    }
    public static String owner(String code){
        if("AGENT_INVESTIGATION_STALLED".equals(code))return "PROCESSING";
        if("EXTERNAL_CONNECTOR_FAILED".equals(code))return "PROCESSING";
        if("MISSING_TENDER_ATTACHMENT".equals(code))return "REQUIREMENT_INPUT";
        if("CAPABILITY_CRITERIA_REQUIRED".equals(code))return "REQUIREMENT_CLARIFICATION";
        if("COST_APPLICABILITY_REQUIRED".equals(code))return "APPLICABILITY";
        if(requirement(code))return "REQUIREMENT";
        if(code==null)return "EVIDENCE";
        if(code.equals("QUALITY_GRADE_REVIEW_REQUIRED"))return "EXPERT_REVIEW";
        if(code.equals("SCORE_SOURCE_LIST_AMBIGUOUS"))return "REQUIREMENT_CLARIFICATION";
        if(code.equals("AUTHORIZATION_OR_DIRECT_SIGNER_PROOF_REQUIRED"))return "APPLICABILITY";
        if(code.contains("EXTERNAL"))return "EXTERNAL";
        if(code.contains("VISUAL"))return "SOURCE_IMAGE";
        if(code.contains("MODEL_")||code.startsWith("HTTP_")||cn.bidcheck.compat.Java8Api.setOf("OUTPUT_SCHEMA_INVALID", "OUTPUT_JSON_INVALID", "REQUEST_TIMEOUT", "FACT_EXTRACTION_UNAVAILABLE").contains(code))return "PROCESSING";
        if(relationship(code))return "RELATIONSHIP";
        if(cn.bidcheck.compat.Java8Api.setOf("USER_INPUT_REQUIRED", "REFERENCE_TIME_REQUIRED").contains(code)||code.contains("APPLICABILITY"))return "APPLICABILITY";
        return "EVIDENCE";
    }
}
