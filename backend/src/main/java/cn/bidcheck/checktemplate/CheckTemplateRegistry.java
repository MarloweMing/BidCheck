package cn.bidcheck.checktemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

/** Loads versioned metadata and binds it only to code-owned deterministic evaluators. */
@Component
public final class CheckTemplateRegistry {
    private static final String RESOURCE = "/check-templates/core-templates.json";
    private static final String IDENTITY_VERSION = "check-template-registry-4";
    private final Map<String, CheckTemplate> templates;
    private final String identity;

    public CheckTemplateRegistry() { this(loadMetadata()); }

    CheckTemplateRegistry(List<TemplateMetadata> metadata) {
        Map<String, CheckTemplate> loaded = new LinkedHashMap<>();
        for (TemplateMetadata item : metadata) {
            String key = key(item.id(), item.version());
            if (loaded.containsKey(key)) throw new IllegalStateException("Duplicate check template " + key);
            CheckTemplate evaluator = CoreTemplateEvaluators.create(item)
                    .orElseThrow(() -> new IllegalStateException("Evaluator is not allowed: " + item.evaluatorId()));
            loaded.put(key, evaluator);
        }
        this.templates = Map.copyOf(loaded);
        this.identity = buildIdentity(this.templates);
    }

    public static CheckTemplateRegistry defaultRegistry() { return new CheckTemplateRegistry(); }

    public Optional<CheckTemplate> find(String id, String version) {
        if (id == null || version == null) return Optional.empty();
        return Optional.ofNullable(templates.get(key(id, version)));
    }

    public List<TemplateMetadata> metadata() {
        return templates.values().stream().map(CheckTemplate::metadata)
                .sorted(java.util.Comparator.comparing(TemplateMetadata::id).thenComparing(TemplateMetadata::version)).toList();
    }

    /**
     * Identifies the exact metadata, schema and code-owned evaluator binary in this deployment.
     * A run can therefore never reuse a result after any executable template input changes.
     */
    public String identity() { return identity; }

    public ValidationResult validate(CheckSpec spec) {
        List<ValidationResult.ValidationError> errors = new ArrayList<>();
        if (!spec.confirmed()) errors.add(new ValidationResult.ValidationError("confirmed", "NOT_CONFIRMED", "检查规格尚未确认"));
        CheckTemplate template = find(spec.templateId(), spec.templateVersion()).orElse(null);
        TemplateTypes.VerificationMode expectedMode = template == null
                ? TemplateTypes.VerificationMode.DOCUMENT : template.metadata().requiredVerificationMode();
        if (spec.verificationMode() != expectedMode) {
            errors.add(new ValidationResult.ValidationError("verificationMode", "VERIFICATION_MODE_UNSUPPORTED",
                    "该模板只能使用核验方式 " + expectedMode + "；外部查询和专业判断不能伪装成文件自动核验"));
        }
        errors.addAll(spec.subjectSelector().validationErrors().stream()
                .map(message -> new ValidationResult.ValidationError("subjectSelector", "INVALID_SUBJECT", message)).toList());
        if (spec.requirementRefs().isEmpty()) {
            errors.add(new ValidationResult.ValidationError("requirementRefs", "REQUIREMENT_SOURCE_REQUIRED", "检查要求必须保留招标原文来源"));
        }
        if (spec.applicability().state() == TemplateTypes.ApplicabilityState.DOES_NOT_APPLY) {
            if (spec.applicability().reason().isBlank()) errors.add(new ValidationResult.ValidationError("applicability.reason", "NON_APPLICABILITY_REASON_REQUIRED", "不适用必须说明原文依据"));
            if (spec.applicability().sources().isEmpty()) errors.add(new ValidationResult.ValidationError("applicability.sources", "NON_APPLICABILITY_SOURCE_REQUIRED", "不适用必须引用明确来源"));
        }
        if (spec.applicability().state() == TemplateTypes.ApplicabilityState.UNKNOWN) {
            errors.add(new ValidationResult.ValidationError("applicability.state", "APPLICABILITY_UNRESOLVED", "适用条件尚未明确，不能确认成可执行规格"));
        }
        String coverageState=String.valueOf(spec.sourceCoverage().getOrDefault("state", ""));
        if (!"FULL".equals(coverageState)) {
            errors.add(new ValidationResult.ValidationError("sourceCoverage.state", "SOURCE_COVERAGE_INCOMPLETE", "必须确认主体、例外和要求原文均已覆盖后才能发布模板"));
        }
        Object unsupported=spec.sourceCoverage().get("unsupportedFragments");
        if (unsupported instanceof java.util.Collection<?> values && !values.isEmpty()) {
            errors.add(new ValidationResult.ValidationError("sourceCoverage.unsupportedFragments", "SOURCE_FRAGMENT_UNSUPPORTED", "仍有未映射的要求片段，不能发布确定性模板"));
        }
        Map<String,ParameterBinding> bindings=new LinkedHashMap<>();
        Set<String> duplicateBindings=new HashSet<>();
        for(ParameterBinding binding:spec.parameterBindings()) {
            String parameter=binding.parameter().replaceFirst("^parameters\\.","");
            if(bindings.putIfAbsent(parameter,binding)!=null)duplicateBindings.add(parameter);
            if(!spec.parameters().containsKey(parameter)) {
                errors.add(new ValidationResult.ValidationError("parameterBindings."+parameter,"UNKNOWN_PARAMETER_BINDING","参数来源记录没有对应的模板参数"));
            } else if(!TemplateIdentity.sameValue(binding.value(),spec.parameters().get(parameter))) {
                errors.add(new ValidationResult.ValidationError("parameters."+parameter,"PARAMETER_BINDING_VALUE_MISMATCH","参数值与已确认的来源记录不一致"));
            }
        }
        for(String parameter:duplicateBindings) {
            errors.add(new ValidationResult.ValidationError("parameterBindings."+parameter,"DUPLICATE_PARAMETER_BINDING","同一参数只能保留一条来源记录"));
        }
        for(String parameter:spec.parameters().keySet()) {
            ParameterBinding binding=bindings.get(parameter);
            if(binding==null||"UNKNOWN".equalsIgnoreCase(binding.origin())||binding.sources().isEmpty()) {
                errors.add(new ValidationResult.ValidationError("parameters."+parameter,"PARAMETER_SOURCE_REQUIRED","影响判断的参数必须保留招标原文来源或有记录的确认依据"));
            }
        }
        if (template == null) errors.add(new ValidationResult.ValidationError("templateId", "UNSUPPORTED_TEMPLATE", "模板或版本未注册"));
        else {
            if (template.metadata().proofStandard() != spec.proofStandard()) {
                errors.add(new ValidationResult.ValidationError("proofStandard", "PROOF_STANDARD_MISMATCH", "证明标准与模板不一致"));
            }
            errors.addAll(template.validate(spec).errors());
        }
        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.errors(errors);
    }

    private static String buildIdentity(Map<String,CheckTemplate> templates) {
        List<Map<String,Object>> definitions=templates.values().stream()
                .sorted(java.util.Comparator.comparing(CheckTemplate::id).thenComparing(CheckTemplate::version))
                .map(template->{
                    Map<String,Object> definition=new LinkedHashMap<>();
                    definition.put("metadata",template.metadata());
                    definition.put("schema",template.parameterSchema().definitions());
                    definition.put("evaluatorId",template.metadata().evaluatorId());
                    definition.put("evaluatorClass",template.getClass().getName());
                    definition.put("evaluatorBinary",TemplateIdentity.classFingerprint(template.getClass()));
                    return Map.copyOf(definition);
                }).toList();
        return TemplateIdentity.digest(IDENTITY_VERSION,Map.of(
                "templates",definitions,
                "factoryBinary",TemplateIdentity.classFingerprint(CoreTemplateEvaluators.class),
                "validationBinary",TemplateIdentity.classFingerprint(CheckTemplateRegistry.class),
                "schemaValidatorBinary",TemplateIdentity.classFingerprint(ParameterSchema.class)));
    }

    private static String key(String id, String version) { return id.trim() + "@" + version.trim(); }

    private static List<TemplateMetadata> loadMetadata() {
        try (InputStream input = CheckTemplateRegistry.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing template metadata " + RESOURCE);
            return new ObjectMapper().readValue(input, new TypeReference<List<TemplateMetadata>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("Invalid template metadata " + RESOURCE, e);
        }
    }
}
