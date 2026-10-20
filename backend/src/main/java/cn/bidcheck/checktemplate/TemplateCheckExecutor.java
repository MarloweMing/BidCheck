package cn.bidcheck.checktemplate;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Pure template router. It never falls back to a model/compiler after a template was selected. */
@Component
public final class TemplateCheckExecutor {
    public static final String VERSION = "template-check-executor-2";
    private final CheckTemplateRegistry registry;
    private final String identity;

    public TemplateCheckExecutor(CheckTemplateRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.identity=TemplateIdentity.digest(VERSION,java.util.Map.of(
                "registry",registry.identity(),
                "executorBinary",TemplateIdentity.classFingerprint(TemplateCheckExecutor.class),
                "projectionBinary",TemplateIdentity.classFingerprint(ResultProjection.class)));
    }
    public String identity() { return identity; }

    public TemplateExecution execute(CheckSpec spec, EvidenceView evidence) {
        Objects.requireNonNull(spec, "spec");
        evidence = evidence == null ? EvidenceView.empty() : evidence;
        ValidationResult validation = registry.validate(spec);
        if (!validation.valid()) return invalid(spec, validation, evidence.revision());
        CheckTemplate template = registry.find(spec.templateId(), spec.templateVersion()).orElseThrow();
        List<EvidenceNeed> needs = template.evidenceNeeds(spec);
        if (spec.applicability().state() == TemplateTypes.ApplicabilityState.DOES_NOT_APPLY) {
            TemplateResult result = new TemplateResult(TemplateTypes.ConclusionState.NOT_APPLICABLE,
                    TemplateTypes.EvidenceState.NOT_APPLICABLE,"NOT_APPLICABLE",
                    spec.applicability().reason().isBlank()?"有来源的适用条件表明该要求不适用。":spec.applicability().reason(),
                    List.of(),sourceIds(spec.applicability().sources()),List.of(),needs.stream().map(EvidenceNeed::key).toList(),
                    TemplateTypes.ActionOwner.NONE,"NONE","");
            return execution(spec,needs,result,evidence.revision(),TemplateTypes.ExecutionState.FINISHED,false);
        }
        if (spec.applicability().state() == TemplateTypes.ApplicabilityState.UNKNOWN) {
            TemplateResult result = new TemplateResult(TemplateTypes.ConclusionState.UNDETERMINED,
                    TemplateTypes.EvidenceState.SCOPE_UNRESOLVED,"APPLICABILITY_UNKNOWN","检查要求的适用条件尚未明确。",
                    List.of(),sourceIds(spec.applicability().sources()),List.of("applicability"),needs.stream().map(EvidenceNeed::key).toList(),
                    TemplateTypes.ActionOwner.SYSTEM,"CLARIFY_REQUIREMENT","请先根据招标原文或确认记录明确适用条件。");
            return execution(spec,needs,result,evidence.revision(),TemplateTypes.ExecutionState.BLOCKED,false);
        }
        try {
            TemplateResult result = template.evaluate(spec,evidence);
            boolean blocked = result.conclusion() == TemplateTypes.ConclusionState.UNDETERMINED
                    && needs.stream().anyMatch(evidence::failed);
            return execution(spec,needs,result,evidence.revision(),blocked?TemplateTypes.ExecutionState.BLOCKED:TemplateTypes.ExecutionState.FINISHED,blocked);
        } catch (RuntimeException error) {
            TemplateResult result = new TemplateResult(TemplateTypes.ConclusionState.UNDETERMINED,
                    TemplateTypes.EvidenceState.SCOPE_UNRESOLVED,"TEMPLATE_EVALUATION_FAILED","固定判定器未完成本项计算。",
                    List.of(),List.of(),List.of("system.templateEvaluation"),needs.stream().map(EvidenceNeed::key).toList(),
                    TemplateTypes.ActionOwner.SYSTEM,"RETRY_TEMPLATE","系统需修复或重试本项；无需用户补交材料。");
            return execution(spec,needs,result,evidence.revision(),TemplateTypes.ExecutionState.FAILED,true);
        }
    }

    private TemplateExecution invalid(CheckSpec spec, ValidationResult validation, long revision) {
        List<String> missing=validation.errors().stream().map(error->error.field()+":"+error.code()).toList();
        TemplateResult result=new TemplateResult(TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.SCOPE_UNRESOLVED,
                "INVALID_CHECK_SPEC","检查规格未通过模板参数校验。",List.of(),List.of(),missing,List.of(),
                TemplateTypes.ActionOwner.SYSTEM,"FIX_CHECK_SPEC","请修正检查项参数或完成检查项确认。");
        return execution(spec,List.of(),result,revision,TemplateTypes.ExecutionState.BLOCKED,false);
    }

    private TemplateExecution execution(CheckSpec spec,List<EvidenceNeed> needs,TemplateResult result,long revision,
                                        TemplateTypes.ExecutionState executionState,boolean retryable) {
        ResultProjection projection=new ResultProjection("template-result-v1",spec.businessItemId(),spec.id(),spec.revision(),revision,
                spec.category(),spec.purpose(),spec.verificationMode(),
                new ResultProjection.Execution(executionState,retryable,null),
                new ResultProjection.Conclusion(result.conclusion(),result.summary(),spec.proofStandard(),result.reasonCode()),
                new ResultProjection.Evidence(result.evidence(),result.evidenceRefs()),
                new ResultProjection.Action(result.actionOwner(),result.actionType(),null),result.conditions(),result.missingInputs(),
                result.dependencies(),new ResultProjection.Review(result.conclusion(),null),
                spec.templateId()+"@"+spec.templateVersion()+"/executor:"+identity(),result.nextStep(),false);
        return new TemplateExecution(spec,needs,result,projection,"TEMPLATE:"+spec.templateId()+"@"+spec.templateVersion());
    }

    private static List<String> sourceIds(List<SourceRef> sources) {
        return sources.stream().map(source->source.documentId()+":"+source.documentVersion()+":"+source.locationType()+":"+source.location()).distinct().toList();
    }
}
