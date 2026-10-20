package cn.bidcheck.checktemplate;

/** Central, versioned vocabulary for the template execution contract. */
public final class TemplateTypes {
    private TemplateTypes() { }

    public enum Category { FORMAL, QUALIFICATION, RESPONSIVENESS, FULL_TEXT, VALIDITY }
    public enum Purpose { MANDATORY, SCORING, INFORMATIONAL }
    public enum VerificationMode { DOCUMENT, EXTERNAL, HUMAN_JUDGMENT }
    public enum ProofStandard {
        MATERIAL_SUBMISSION, WRITTEN_COMMITMENT, FACTUAL_CONDITION,
        OBSERVED_CONSISTENCY, QUALITY_ADVISORY
    }
    public enum ApplicabilityState { APPLIES, DOES_NOT_APPLY, UNKNOWN }
    public enum ObservationState { SUPPORTED, REFUTED, CONFLICTING, UNRESOLVED }
    public enum ObservationKind { MATERIAL, SEAL, CERTIFICATE, PRICE, PERSONNEL }
    public enum NeedKind { MATERIAL, SEAL, CERTIFICATE, PRICE, PERSONNEL, ROSTER_SCOPE }

    public enum ConditionState { SATISFIED, NOT_SATISFIED, UNKNOWN, NOT_APPLICABLE }
    public enum ExecutionState { QUEUED, RUNNING, RETRY_WAIT, FINISHED, BLOCKED, FAILED, PAUSED, CANCELLED }
    public enum ConclusionState { SATISFIED, NOT_SATISFIED, SUSPECTED_RISK, UNDETERMINED, NOT_APPLICABLE }
    public enum EvidenceState { SUFFICIENT, PARTIAL, CONFLICTING, NOT_LOCATED, SCOPE_UNRESOLVED, NOT_APPLICABLE }
    public enum ActionOwner { SYSTEM, USER, EXTERNAL, NONE }
}
