package cn.bidcheck.service;

import cn.bidcheck.checktemplate.ResultProjection;
import cn.bidcheck.checktemplate.TemplateTypes;
import cn.bidcheck.domain.Domain.Evaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Read-only business presentation, always based on the published evaluation after guards. */
public final class BusinessResultPresentation {
    public static final String VERSION="business-result-3";
    private static final ObjectMapper JSON=new ObjectMapper();
    private BusinessResultPresentation() {}

    public static Map<String,Object> describe(Evaluation evaluation,Map<String,Object> assessment) {
        return describe(evaluation,assessment,map(assessment==null?null:assessment.get("resolutionJob")));
    }

    /** A system-owned gap is not an active job. Only persisted job identity and state indicate recovery. */
    public static Map<String,Object> describe(Evaluation evaluation,Map<String,Object> assessment,Map<String,Object> resolutionJob) {
        Map<String,Object> a=assessment==null?Map.of():assessment,atomic=map(a.get("atomicCheck")),ocr=map(a.get("ocrReview"));
        ResultProjection template=template(a);boolean templateFinal=template!=null&&template.finalReconciled();
        String status=evaluation.status();Set<String> codes=new HashSet<>();
        for(Map<String,Object> item:maps(a.get("adjustments")))codes.add(text(item,"code"));
        for(Map<String,Object> item:maps(a.get("guards")))codes.add(text(item,"code"));
        for(Map<String,Object> item:maps(atomic.get("gaps")))codes.add(text(item,"code"));
        Map<String,Object> completeness=map(map(a.get("coverage")).get("completeness"));
        Map<String,Object> semanticGap=map(map(a.get("coverage")).get("semanticGap"));
        String gapKind=Boolean.TRUE.equals(semanticGap.get("validated"))?text(semanticGap,"kind"):"";
        boolean resolvedInventory=codes.contains("REQUIREMENT_COMPLETENESS_UNPROVEN")&&Boolean.TRUE.equals(completeness.get("inventoryResolved"))&&!Boolean.TRUE.equals(completeness.get("coreRequirementDeferred"));
        String atomicState=text(FocusedCheckEvaluator.presentation(atomic),"state");
        boolean finalMismatch="UNDETERMINED".equals(status)&&"COMPLETED".equals(atomic.get("workflowState"))&&Boolean.TRUE.equals(atomic.get("complete"));
        boolean recoveryFailed=resolutionJob!=null&&!text(resolutionJob,"id").isBlank()&&"FAILED".equals(text(resolutionJob,"status"));
        boolean templateProcessing=templateFinal&&"UNDETERMINED".equals(status)&&(template.execution()!=null&&Set.of(TemplateTypes.ExecutionState.FAILED,TemplateTypes.ExecutionState.BLOCKED).contains(template.execution().state())
            ||template.action()!=null&&template.action().owner()==TemplateTypes.ActionOwner.SYSTEM&&Set.of("FIX_CHECK_SPEC","RETRY_TEMPLATE","CLARIFY_REQUIREMENT").contains(Objects.toString(template.action().type(),""))
            ||template.missingInputs().stream().anyMatch(value->value.startsWith("system.")));
        boolean processing=templateProcessing||recoveryFailed||codes.contains("UNIQUE_PRICE_SCOPE_REQUIRED")||codes.stream().anyMatch(FocusedCheckEvaluator::mappingPending)||Set.of("SYSTEM_REQUIREMENT","SYSTEM_RETRIEVAL","SYSTEM_RELATION").contains(gapKind)||maps(atomic.get("fieldCoverage")).stream().anyMatch(row->Set.of("FAILED","UNREAD").contains(text(row,"status")))
            ||"RULE_UNSUPPORTED".equals(text(map(a.get("model")),"decision"))||finalMismatch||maps(completeness.get("itemResults")).stream().anyMatch(item->"REQUIREMENT_UNRESOLVED".equals(text(item,"status")))
            ||Set.of("PROCESSING_FAILED","REQUIREMENT_UNPARSED").contains(atomicState)
            ||Set.of("ENGINE_FAILURE","ERROR").contains(text(atomic,"workflowState"))
            ||codes.stream().anyMatch(code->code.startsWith("TEMPLATE_")&&code.endsWith("INVALID"))
            ||"UNAVAILABLE".equals(text(map(a.get("model")),"state"))
            ||codes.stream().filter(code->!resolvedInventory||!"REQUIREMENT_COMPLETENESS_UNPROVEN".equals(code)).anyMatch(code->code.startsWith("MODEL_")||code.startsWith("HTTP_")||code.contains("COMPILATION")||code.startsWith("REQUIREMENT_")
                ||code.contains("GROUNDING")||code.endsWith("UNGROUNDED")||Set.of("CONTEXT_BUDGET_EXCEEDED","CHECKLIST_BATCH_REQUIRED","INPUT_INVALID","EVIDENCE_SCOPE_INCOMPLETE","OCR_COVERAGE_DEFERRED","OCR_SOURCE_DEFERRED","OUTPUT_SCHEMA_INVALID","OUTPUT_JSON_INVALID").contains(code))
            ||Boolean.TRUE.equals(ocr.get("incomplete"));
        boolean external=templateFinal&&(template.verificationMode()==TemplateTypes.VerificationMode.EXTERNAL||template.action()!=null&&template.action().owner()==TemplateTypes.ActionOwner.EXTERNAL)
            ||"EXTERNAL_INPUT".equals(gapKind)||!maps(atomic.get("externalRequests")).isEmpty()||"WAIT_STAGE".equals(atomic.get("workflowState"))
            ||codes.stream().anyMatch(code->Set.of("EXTERNAL_RECORD_REQUIRED","EXTERNAL_VERIFICATION_REQUIRED","USER_INPUT_REQUIRED","REFERENCE_TIME_REQUIRED","APPLICABILITY_TIME_PENDING").contains(code));
        String beforeVisual=statusOf(a.containsKey("preOcrEvaluation")?a.get("preOcrEvaluation"):a.get("rawEvaluation"));
        boolean templateEvidencePending=templateFinal&&"UNDETERMINED".equals(status)&&template.evidence()!=null&&Set.of(TemplateTypes.EvidenceState.PARTIAL,TemplateTypes.EvidenceState.NOT_LOCATED,TemplateTypes.EvidenceState.CONFLICTING).contains(template.evidence().state());
        boolean evidencePending=templateEvidencePending||"EXTERNAL_INPUT_REQUIRED".equals(text(map(a.get("model")),"decision"))||resolvedInventory||"UNDETERMINED".equals(beforeVisual)||Set.of("RELATION_UNCONFIRMED","EVIDENCE_NOT_FOUND").contains(atomicState)
            ||codes.stream().anyMatch(code->Set.of("EVIDENCE_MISSING","PERSONNEL_COVERAGE_INCOMPLETE","NO_CANDIDATE_EVIDENCE","EVIDENCE_NOT_FOUND").contains(code));
        boolean visual=Boolean.TRUE.equals(ocr.get("required"));
        String state=switch(status){case "PASS"->"SATISFIED";case "FAIL","WARNING"->"RISK";case "NOT_APPLICABLE"->"NOT_APPLICABLE";
            default->processing?"SYSTEM_INCOMPLETE":external?"USER_ACTION":evidencePending?"EVIDENCE_PENDING":visual?"USER_ACTION":"EVIDENCE_PENDING";};
        String label=switch(state){case "SATISFIED"->"满足要求";case "RISK"->"存在风险";case "SYSTEM_INCOMPLETE"->"系统未完成";case "USER_ACTION"->"需你确认";case "NOT_APPLICABLE"->"不适用";default->"待补证据";};
        boolean userAction="USER_ACTION".equals(state)||"RISK".equals(state)||"SATISFIED".equals(state)&&visual;
        String owner=switch(state){case "SYSTEM_INCOMPLETE","EVIDENCE_PENDING"->"SYSTEM";case "USER_ACTION","RISK"->"USER";default->userAction?"USER":"NONE";};
        String next=switch(state){
            case "SYSTEM_INCOMPLETE"->"需继续处理条款、证据校验或失败请求；当前不要求补交材料，也不计入人工核对待办。";
            case "EVIDENCE_PENDING"->"需继续检索并关联现有文件中的证据；只有确认具体缺口后才要求补充材料。";
            case "USER_ACTION"->"按下方说明补充外部信息、确认核验时点或核对原图。";
            case "RISK"->"对照所列原文确认风险，并记录复核结论。";
            case "SATISFIED"->visual?"文字要求已得到支持；另有原图复核事项，请核对下方指定位置。":"本项已形成满足要求的结论，依据保留供追溯。";
            default->"本项不适用于当前条件，适用依据保留供追溯。";};
        boolean stopped=resolutionJob!=null&&!text(resolutionJob,"id").isBlank()&&Set.of("BLOCKED_NO_PROGRESS","FAILED").contains(text(resolutionJob,"status"));
        if(stopped&&Set.of("SYSTEM_INCOMPLETE","EVIDENCE_PENDING").contains(state))
            next="本轮自动补查已停止，需修复处理环节或补足检索条件后再启动；当前不据此要求补交材料。";
        if("USER_ACTION".equals(state)&&"EXTERNAL_INPUT".equals(gapKind))next="需取得招标条款要求的外部记录或现场核验结果；现有文件检索不能替代该记录。";
        if("SYSTEM_INCOMPLETE".equals(state)&&!stopped&&codes.contains("UNIQUE_PRICE_SCOPE_REQUIRED"))next="系统尚未核全本项目的报价与备选方案范围；已识别报价一致仅作局部结果，当前不要求补交材料。";
        if(templateFinal&&!Objects.toString(template.nextStep(),"").isBlank()&&!"SYSTEM_INCOMPLETE".equals(state))next=template.nextStep();
        Map<String,Object> scope=proofScope(evaluation,a);
        if("SATISFIED".equals(state)&&!text(scope,"label").isBlank()){
            label=text(scope,"label");
            if("WRITTEN_RESPONSE".equals(text(scope,"state")))next="已核对所引文件的书面响应；这不等于实际资格状态或外部查询结果已核实。";
            if("OBSERVED_OCCURRENCES".equals(text(scope,"state")))next="已识别的同对象同口径记录未见差异；结论范围仅为所列出现记录。";
            if("ROLE_CONFIGURATION_ONLY".equals(text(scope,"state")))next="已确认要求岗位有明确的本次任职人员；该结论不代表人数上限、兼任关系、人员资质或跨材料身份已核实。";
        }
        String reason=finalMismatch?"已完成的辅助条件不足以证明整项业务要求；最终结果仍未确定。":Objects.toString(evaluation.reason(),"");
        if("SYSTEM_INCOMPLETE".equals(state)&&!finalMismatch&&!recoveryFailed&&"RELATION_UNCONFIRMED".equals(atomicState)){
            Optional<Map<String,Object>> mapping=maps(atomic.get("gaps")).stream().filter(gap->FocusedCheckEvaluator.mappingPending(text(gap,"code"))).findFirst();
            if(mapping.isPresent()){
                if(!text(mapping.get(),"description").isBlank())reason=text(mapping.get(),"description");
                if(!stopped)next="系统需继续完成证据字段或人员单位对应；当前不要求补交材料，也不计入人工核对待办。";
            }
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("version",VERSION);out.put("state",state);out.put("label",label);out.put("owner",owner);
        out.put("reason",reason);out.put("nextStep",next);out.put("userActionRequired",userAction);out.put("finalStatus",status);
        out.put("projectionVersion","business-dimensions-3");
        out.put("dimensions",dimensions(evaluation,a,resolutionJob));return out;
    }

    public static Map<String,Object> dimensions(Evaluation evaluation,Map<String,Object> assessment,Map<String,Object> resolutionJob) {
        Map<String,Object> a=assessment==null?Map.of():assessment,job=resolutionJob==null?Map.of():resolutionJob;
        ResultProjection template=template(a);
        if(template!=null&&template.finalReconciled()&&text(job,"id").isBlank())return templateDimensions(template);
        Map<String,Object> completeness=map(map(a.get("coverage")).get("completeness"));
        String finalStatus=evaluation.status(),jobStatus=text(job,"status"),execution="FINISHED";
        if(!text(job,"id").isBlank()&&Set.of("QUEUED","RUNNING","RETRY_WAIT","SUCCEEDED","BLOCKED_NO_PROGRESS","WAITING_USER","WAITING_EXTERNAL","FAILED").contains(jobStatus))
            execution="SUCCEEDED".equals(jobStatus)?"FINISHED":jobStatus;
        String executionLabel=switch(execution){case "QUEUED"->"补查已排队";case "RUNNING"->"自动补查中";case "RETRY_WAIT"->"等待重试";
            case "BLOCKED_NO_PROGRESS"->"补查已停止：无新进展";case "WAITING_USER"->"等待必要信息";case "WAITING_EXTERNAL"->"等待外部条件";case "FAILED"->"本轮补查失败";default->"本轮已结束";};
        String conclusion=switch(finalStatus){case "PASS"->"SATISFIED";case "FAIL"->"NOT_SATISFIED";case "WARNING"->"SUSPECTED_RISK";case "NOT_APPLICABLE"->"NOT_APPLICABLE";default->"UNDETERMINED";};
        String conclusionLabel=switch(conclusion){case "SATISFIED"->"满足要求";case "NOT_SATISFIED"->"不满足要求";case "SUSPECTED_RISK"->"疑似风险，待确认";case "NOT_APPLICABLE"->"不适用";default->"尚不能判断";};
        boolean inventoryUnknown=Boolean.FALSE.equals(completeness.get("inventoryResolved"))||Boolean.TRUE.equals(completeness.get("coreRequirementDeferred"))
            ||maps(completeness.get("itemResults")).stream().anyMatch(item->"REQUIREMENT_UNRESOLVED".equals(text(item,"status")));
        boolean references=evaluation.evidences()!=null&&!evaluation.evidences().isEmpty();
        boolean hasBodyProof=maps(completeness.get("itemResults")).stream().anyMatch(item->"VERIFIED".equals(text(item,"status")));
        boolean hasVisualTask=Boolean.TRUE.equals(map(a.get("ocrReview")).get("required"));
        String evidence="NOT_APPLICABLE".equals(finalStatus)?"NOT_APPLICABLE":inventoryUnknown?"SCOPE_UNRESOLVED":
            Set.of("PASS","FAIL").contains(finalStatus)&&references&&!hasVisualTask?"SUFFICIENT":references||hasBodyProof?"PARTIAL":"NOT_RETRIEVED";
        String evidenceLabel=switch(evidence){case "NOT_APPLICABLE"->"不适用";case "SCOPE_UNRESOLVED"->"要求范围尚未明确";case "SUFFICIENT"->"判断依据已记录";
            case "PARTIAL"->hasBodyProof?"部分义务已有证明":"证据尚未核全";default->"尚未取得充分依据";};
        Map<String,Object> executionValue=new LinkedHashMap<>();executionValue.put("state",execution);executionValue.put("label",executionLabel);
        executionValue.put("jobId",text(job,"id"));executionValue.put("active",Set.of("QUEUED","RUNNING","RETRY_WAIT").contains(execution));
        executionValue.put("reason",text(job,"stopReason").isBlank()?text(job,"reason"):text(job,"stopReason"));executionValue.put("nextAction",text(job,"nextAction"));
        Map<String,Object> scope=proofScope(evaluation,a);
        if("SATISFIED".equals(conclusion)&&!text(scope,"label").isBlank())conclusionLabel=text(scope,"label");
        return Map.of("execution",executionValue,"evidence",Map.of("state",evidence,"label",evidenceLabel),"proofScope",scope,
            "conclusion",Map.of("state",conclusion,"label",conclusionLabel,"determined",Set.of("SATISFIED","NOT_SATISFIED","NOT_APPLICABLE").contains(conclusion)));
    }
    /** A declaration proves a written response; it does not establish an external fact. */
    static Map<String,Object> proofScope(Evaluation evaluation,Map<String,Object> assessment){
        Map<String,Object> unknown=Map.of("state","UNSPECIFIED","label","");if(!"PASS".equals(evaluation.status()))return unknown;
        ResultProjection projection=template(assessment);
        if(projection!=null&&projection.finalReconciled()&&projection.conclusion()!=null){
            String scope=projection.conclusion().proofScope()==null?"":projection.conclusion().proofScope().name();
            String label=switch(scope){case "MATERIAL_SUBMISSION"->"材料提交要求已满足";case "WRITTEN_COMMITMENT"->"书面承诺要求已满足";case "FACTUAL_CONDITION"->"业务条件已验证";case "OBSERVED_CONSISTENCY"->"已识别记录一致";case "QUALITY_ADVISORY"->"评分建议";default->"";};
            if(!label.isBlank())return Map.of("state",scope,"label",label);
        }
        Map<String,Object> derived=map(assessment.get("derivedConclusion"));
        if("PASS".equals(text(derived,"status"))&&"OBSERVED_OCCURRENCES".equals(text(derived,"proofScope"))&&Boolean.TRUE.equals(derived.get("comparisonCoverageComplete"))
                &&derived.get("unresolvedOccurrenceIds") instanceof Collection<?> unresolved&&unresolved.isEmpty()
                &&derived.get("originalOccurrenceIds") instanceof Collection<?> original&&!original.isEmpty())
            return Map.of("state","OBSERVED_OCCURRENCES","label","已识别记录一致");
        Map<String,Object> semantic=map(map(assessment.get("coverage")).get("semanticProof"));
        if(evaluation.evidences()!=null&&!evaluation.evidences().isEmpty()&&Boolean.TRUE.equals(semantic.get("validated"))&&"WRITTEN_RESPONSE".equals(text(semantic,"scope"))
                &&semantic.get("evidenceIds") instanceof Collection<?> ids&&!ids.isEmpty())return Map.of("state","WRITTEN_RESPONSE","label","书面要求已满足");
        List<Map<String,Object>> conditions=maps(map(assessment.get("atomicCheck")).get("conditions")).stream().filter(c->"REQUIREMENT".equals(text(c,"conditionRole"))).filter(c->!"NOT_APPLICABLE".equals(text(c,"status"))).toList();
        if(conditions.isEmpty()||conditions.stream().anyMatch(c->!"PASS".equals(text(c,"status"))||!Boolean.TRUE.equals(c.get("evaluated"))||map(c.get("proof")).isEmpty()||!(c.get("evidences") instanceof Collection<?> refs)||refs.isEmpty()||!maps(c.get("gaps")).isEmpty()))return unknown;
        Map<String,Object> atomic=map(assessment.get("atomicCheck"));
        if(Boolean.TRUE.equals(atomic.get("complete"))&&maps(atomic.get("gaps")).isEmpty()
            &&conditions.stream().allMatch(c->"PERSON_COUNT".equals(text(c,"predicate"))&&"ROLE_CONFIGURATION_ONLY".equals(text(map(c.get("proof")),"proofScope"))))
            return Map.of("state","ROLE_CONFIGURATION_ONLY","label","岗位配置已确认");
        Set<String> written=Set.of("MATERIAL","DECLARATION","SECTION","VISUAL"),factual=Set.of("NUMBER","CERTIFICATE","VALIDITY","DATE_RANGE","MONTHS","PERSON_ROLE","RELATION","FIELD","SAME_FIELD","CONTRACT");
        if(conditions.stream().allMatch(c->written.contains(text(c,"predicate"))))return Map.of("state","WRITTEN_RESPONSE","label","书面要求已满足");
        if(conditions.stream().allMatch(c->factual.contains(text(c,"predicate"))))return Map.of("state","BUSINESS_CONDITIONS","label","业务条件已验证");
        return unknown;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object raw){return raw instanceof Map<?,?>?(Map<String,Object>)raw:Map.of();}
    private static List<Map<String,Object>> maps(Object raw){return raw instanceof Collection<?> list?list.stream().filter(Map.class::isInstance).map(BusinessResultPresentation::map).toList():List.of();}
    private static String text(Map<String,Object> value,String key){return Objects.toString(value.get(key),"");}
    private static String statusOf(Object value){return value instanceof Evaluation e?e.status():text(map(value),"status");}
    private static ResultProjection template(Map<String,Object> assessment){
        Object direct=assessment==null?null:assessment.get("resultProjection");
        if(direct instanceof ResultProjection projection)return projection;
        if(direct instanceof Map<?,?> values&&"template-result-v1".equals(Objects.toString(values.get("schemaVersion"),"")))try{return JSON.convertValue(values,ResultProjection.class);}catch(IllegalArgumentException ignored){}
        Object fallback=assessment==null?null:assessment.get("templateResult");
        if(fallback instanceof ResultProjection projection)return projection;
        if(fallback instanceof Map<?,?> values&&"template-result-v1".equals(Objects.toString(values.get("schemaVersion"),"")))try{return JSON.convertValue(values,ResultProjection.class);}catch(IllegalArgumentException ignored){}
        return null;
    }
    private static Map<String,Object> templateDimensions(ResultProjection projection){
        String execution=projection.execution()==null?"FINISHED":projection.execution().state().name();
        String executionLabel=switch(execution){case "QUEUED"->"补查已排队";case "RUNNING"->"自动补查中";case "RETRY_WAIT"->"等待重试";case "BLOCKED"->"等待系统补齐输入";case "FAILED"->"本轮处理失败";default->"本轮已结束";};
        String conclusion=projection.conclusion()==null?"UNDETERMINED":projection.conclusion().state().name();
        String conclusionLabel=switch(conclusion){case "SATISFIED"->"满足要求";case "NOT_SATISFIED"->"不满足要求";case "SUSPECTED_RISK"->"疑似风险，待确认";case "NOT_APPLICABLE"->"不适用";default->"尚不能判断";};
        String evidence=projection.evidence()==null?"NOT_LOCATED":projection.evidence().state().name();
        String evidenceLabel=switch(evidence){case "SUFFICIENT"->"判断依据已记录";case "PARTIAL"->"证据尚未核全";case "CONFLICTING"->"证据存在冲突";case "SCOPE_UNRESOLVED"->"要求范围尚未明确";case "NOT_APPLICABLE"->"不适用";default->"尚未取得充分依据";};
        String proof=projection.conclusion()==null||projection.conclusion().proofScope()==null?"UNSPECIFIED":projection.conclusion().proofScope().name();
        String proofLabel=switch(proof){case "MATERIAL_SUBMISSION"->"材料提交要求";case "WRITTEN_COMMITMENT"->"书面承诺要求";case "FACTUAL_CONDITION"->"业务事实条件";case "OBSERVED_CONSISTENCY"->"已识别记录范围";case "QUALITY_ADVISORY"->"评分建议";default->"";};
        Map<String,Object> executionValue=new LinkedHashMap<>();executionValue.put("state",execution);executionValue.put("label",executionLabel);
        executionValue.put("jobId",projection.execution()==null||projection.execution().jobId()==null?"":projection.execution().jobId());executionValue.put("active",Set.of("QUEUED","RUNNING","RETRY_WAIT").contains(execution));
        executionValue.put("reason",projection.conclusion()==null?"":Objects.toString(projection.conclusion().summary(),""));executionValue.put("nextAction",projection.nextStep());
        return Map.of("execution",executionValue,"evidence",Map.of("state",evidence,"label",evidenceLabel),"proofScope",Map.of("state",proof,"label",proofLabel),
            "conclusion",Map.of("state",conclusion,"label",conclusionLabel,"determined",Set.of("SATISFIED","NOT_SATISFIED","NOT_APPLICABLE").contains(conclusion)));
    }
}
