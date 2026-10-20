package cn.bidcheck.checktemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Frozen executable requirement. Display text is deliberately not executable. */
public record CheckSpec(
        String id,
        int revision,
        String businessItemId,
        String checkpointId,
        TemplateTypes.Category category,
        TemplateTypes.Purpose purpose,
        TemplateTypes.VerificationMode verificationMode,
        String templateId,
        String templateVersion,
        SubjectSelector subjectSelector,
        Map<String, Object> parameters,
        Applicability applicability,
        TemplateTypes.ProofStandard proofStandard,
        List<SourceRef> requirementRefs,
        List<ParameterBinding> parameterBindings,
        Map<String, Object> requiredFields,
        Map<String, Object> evidenceContract,
        Map<String, Object> sourceCoverage,
        boolean confirmed,
        Confirmation confirmation,
        String semanticSignature) {

    public CheckSpec {
        id = required(id, "id");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        businessItemId = required(businessItemId, "businessItemId");
        checkpointId = checkpointId == null ? "" : checkpointId.trim();
        category = Objects.requireNonNull(category, "category");
        purpose = Objects.requireNonNull(purpose, "purpose");
        verificationMode = Objects.requireNonNull(verificationMode, "verificationMode");
        templateId = required(templateId, "templateId");
        templateVersion = required(templateVersion, "templateVersion");
        subjectSelector = Objects.requireNonNull(subjectSelector, "subjectSelector");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        applicability = applicability == null ? Applicability.applies() : applicability;
        proofStandard = Objects.requireNonNull(proofStandard, "proofStandard");
        requirementRefs = requirementRefs == null ? List.of() : List.copyOf(requirementRefs);
        parameterBindings = parameterBindings == null ? List.of() : List.copyOf(parameterBindings);
        requiredFields = requiredFields == null ? Map.of() : Map.copyOf(requiredFields);
        evidenceContract = evidenceContract == null ? Map.of() : Map.copyOf(evidenceContract);
        sourceCoverage = sourceCoverage == null ? Map.of() : Map.copyOf(sourceCoverage);
        confirmation = confirmation == null ? new Confirmation(confirmed ? "CONFIRMED" : "DRAFT", "", "", "") : confirmation;
        semanticSignature = semanticSignature == null ? "" : semanticSignature.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
