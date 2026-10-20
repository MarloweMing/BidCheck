package cn.bidcheck.checktemplate;

import java.util.List;

public interface CheckTemplate {
    String id();
    String version();
    TemplateMetadata metadata();
    ParameterSchema parameterSchema();
    default ValidationResult validate(CheckSpec spec) { return parameterSchema().validate(spec.parameters()); }
    List<EvidenceNeed> evidenceNeeds(CheckSpec spec);
    TemplateResult evaluate(CheckSpec spec, EvidenceView evidence);
}
