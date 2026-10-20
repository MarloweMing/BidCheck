package cn.bidcheck.checktemplate;

import java.util.List;
import java.util.Map;

/** Safe public metadata. evaluatorId is selected from a code-owned allow-list, never a class name. */
public record TemplateMetadata(
        String id,
        String version,
        String title,
        String evaluatorId,
        TemplateTypes.ProofStandard proofStandard,
        Map<String, ParameterDefinition> parameters,
        List<String> fixedSubchecks,
        TemplateTypes.VerificationMode requiredVerificationMode) {
    public TemplateMetadata {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("template id is required");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("template version is required");
        if (evaluatorId == null || evaluatorId.isBlank()) throw new IllegalArgumentException("evaluatorId is required");
        title = title == null ? id : title;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        fixedSubchecks = fixedSubchecks == null ? List.of() : List.copyOf(fixedSubchecks);
        requiredVerificationMode = requiredVerificationMode == null ? defaultVerificationMode(id) : requiredVerificationMode;
    }

    /** Keeps programmatic metadata fixtures source-compatible while publishing the required mode through the API. */
    public TemplateMetadata(String id,String version,String title,String evaluatorId,TemplateTypes.ProofStandard proofStandard,
                            Map<String,ParameterDefinition> parameters,List<String> fixedSubchecks) {
        this(id,version,title,evaluatorId,proofStandard,parameters,fixedSubchecks,null);
    }

    private static TemplateTypes.VerificationMode defaultVerificationMode(String id) {
        return switch (id) {
            case "EXTERNAL_CHECK" -> TemplateTypes.VerificationMode.EXTERNAL;
            case "QUALITY_ADVISORY" -> TemplateTypes.VerificationMode.HUMAN_JUDGMENT;
            default -> TemplateTypes.VerificationMode.DOCUMENT;
        };
    }

    public record ParameterDefinition(String type, boolean required, List<String> allowedValues, Object defaultValue) {
        public ParameterDefinition {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("parameter type is required");
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        }
    }
}
