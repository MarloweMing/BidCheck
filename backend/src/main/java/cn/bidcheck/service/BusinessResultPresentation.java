package cn.bidcheck.service;

import cn.bidcheck.checktemplate.ResultProjection;
import cn.bidcheck.checktemplate.TemplateTypes;
import cn.bidcheck.domain.Domain.Evaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Read-only business presentation, always based on the published evaluation after guards. */
public final class BusinessResultPresentation {
    public static final String VERSION="business-result-13-action-specific-copy";
    private static final ObjectMapper JSON=new ObjectMapper();
    private BusinessResultPresentation() {}

    public static Map<String,Object> describe(Evaluation evaluation,Map<String,Object> assessment) {
        return describe(evaluation,assessment,map(assessment==null?null:assessment.get("resolutionJob")));
    }

    /** A system-owned gap is not an active job. Only persisted job identity and state indicate recovery. */
    public static Map<String,Object> describe(Evaluation evaluation,Map<String,Object> assessment,Map<String,Object> resolutionJob) {
        Map<String,Object> a=assessment==null?cn.bidcheck.compat.Java8Api.mapOf():assessment,atomic=map(a.get("atomicCheck")),ocr=map(a.get("ocrReview"));
        Map<String,Object> investigation=map(atomic.get("agentInvestigation"));
        if(Boolean.TRUE.equals(investigation.get("processingFailed"))){
            // Project the host's evidence decision separately from the availability of its optional investigator.
            Map<String,Object> businessAtomic=new LinkedHashMap<>(atomic);
            businessAtomic.remove("agentInvestigation");
            Map<String,Object> businessAssessment=new LinkedHashMap<>(a);
            businessAssessment.put("atomicCheck",businessAtomic);
            Map<String,Object> projected=new LinkedHashMap<>(describe(evaluation,businessAssessment,Collections.emptyMap()));
            projected.put("processingFailed",true);
            projected.put("processingError",text(investigation,"errorCode"));
            projected.put("processingMessage","查证服务未完成，已保留依据现有原文计算的业务结论。");
            return projected;
        }
        if(resolutionJob==null)resolutionJob=Collections.emptyMap();
        TrustedVisualReview.Result visualReview=TrustedVisualReview.inspect(a);
        ResultProjection template=template(a);boolean templateFinal=template!=null&&template.finalReconciled();
        String status=evaluation.status();Set<String> codes=new HashSet<>();
        for(Map<String,Object> item:maps(a.get("adjustments")))codes.add(text(item,"code"));
        for(Map<String,Object> item:maps(a.get("guards")))codes.add(text(item,"code"));
        for(Map<String,Object> item:maps(atomic.get("gaps")))codes.add(text(item,"code"));
        Map<String,Object> externalState=ExternalCheckState.describe(a,resolutionJob);
        Map<String,Object> completeness=map(map(a.get("coverage")).get("completeness"));
        Map<String,Object> semanticGap=map(map(a.get("coverage")).get("semanticGap"));
        String gapKind=Boolean.TRUE.equals(semanticGap.get("validated"))?text(semanticGap,"kind"):"";
        boolean resolvedInventory=codes.contains("REQUIREMENT_COMPLETENESS_UNPROVEN")&&Boolean.TRUE.equals(completeness.get("inventoryResolved"))&&!Boolean.TRUE.equals(completeness.get("coreRequirementDeferred"));
        String atomicState=text(FocusedCheckEvaluator.presentation(atomic),"state");
        boolean finalMismatch="UNDETERMINED".equals(status)&&"COMPLETED".equals(atomic.get("workflowState"))&&Boolean.TRUE.equals(atomic.get("complete"));
        boolean recoveryFailed=resolutionJob!=null&&!cn.bidcheck.compat.Java8Api.isBlank(text(resolutionJob,"id"))&&"FAILED".equals(text(resolutionJob,"status"));
        boolean templateProcessing=templateFinal&&"UNDETERMINED".equals(status)&&(template.execution()!=null&&cn.bidcheck.compat.Java8Api.setOf(TemplateTypes.ExecutionState.FAILED, TemplateTypes.ExecutionState.BLOCKED).contains(template.execution().state())
            ||template.action()!=null&&template.action().owner()==TemplateTypes.ActionOwner.SYSTEM&&cn.bidcheck.compat.Java8Api.setOf("FIX_CHECK_SPEC", "RETRY_TEMPLATE", "CLARIFY_REQUIREMENT").contains(Objects.toString(template.action().type(),""))
            ||template.missingInputs().stream().anyMatch(value->value.startsWith("system.")));
        boolean processing=Boolean.TRUE.equals(externalState.get("systemOwned"))||templateProcessing||recoveryFailed||codes.contains("EXTERNAL_CONNECTOR_FAILED")||RecoveryTransition.hasProcessingFailure(new cn.bidcheck.domain.Domain.Finding("","",null,evaluation,a))||codes.contains("UNIQUE_PRICE_SCOPE_REQUIRED")||codes.stream().anyMatch(FocusedCheckEvaluator::mappingPending)||cn.bidcheck.compat.Java8Api.setOf("SYSTEM_REQUIREMENT", "SYSTEM_RETRIEVAL", "SYSTEM_RELATION").contains(gapKind)||maps(atomic.get("fieldCoverage")).stream().anyMatch(row->"UNREAD".equals(text(row,"status"))||RecoveryTransition.fieldProcessingFailure(row))
            ||"RULE_UNSUPPORTED".equals(text(map(a.get("model")),"decision"))||finalMismatch||maps(completeness.get("itemResults")).stream().anyMatch(item->"REQUIREMENT_UNRESOLVED".equals(text(item,"status")))
            ||cn.bidcheck.compat.Java8Api.setOf("PROCESSING_FAILED", "REQUIREMENT_UNPARSED").contains(atomicState)
            ||cn.bidcheck.compat.Java8Api.setOf("ENGINE_FAILURE", "ERROR").contains(text(atomic,"workflowState"))
            ||codes.stream().anyMatch(code->code.startsWith("TEMPLATE_")&&code.endsWith("INVALID"))
            ||"UNAVAILABLE".equals(text(map(a.get("model")),"state"))
            ||codes.stream().filter(code->!resolvedInventory||!"REQUIREMENT_COMPLETENESS_UNPROVEN".equals(code)).anyMatch(code->code.startsWith("MODEL_")||code.startsWith("HTTP_")||code.contains("COMPILATION")||code.startsWith("REQUIREMENT_")
                ||code.contains("GROUNDING")||code.endsWith("UNGROUNDED")||cn.bidcheck.compat.Java8Api.setOf("CONTEXT_BUDGET_EXCEEDED", "CHECKLIST_BATCH_REQUIRED", "INPUT_INVALID", "EVIDENCE_SCOPE_INCOMPLETE", "OCR_COVERAGE_DEFERRED", "OCR_SOURCE_DEFERRED", "OUTPUT_SCHEMA_INVALID", "OUTPUT_JSON_INVALID").contains(code))
            ||"SUPPLEMENT_ANALYSIS_REQUIRED".equals(evaluation.method())||"SUPPLEMENT_EVALUATOR_UNAVAILABLE".equals(text(map(a.get("publicationGuard")),"code"))
            ||Boolean.TRUE.equals(ocr.get("incomplete"));
        boolean scoringReview=verifiedScoringReview(atomic);
        String professionQuestion=titleProfessionQuestion(atomic);
        boolean external=!professionQuestion.isEmpty()||scoringReview||templateFinal&&(template.verificationMode()==TemplateTypes.VerificationMode.EXTERNAL||template.action()!=null&&template.action().owner()==TemplateTypes.ActionOwner.EXTERNAL)
            ||"EXTERNAL_INPUT".equals(gapKind)||!maps(atomic.get("externalRequests")).isEmpty()||"WAIT_STAGE".equals(atomic.get("workflowState"))
            ||codes.stream().anyMatch(code->cn.bidcheck.compat.Java8Api.setOf("EXTERNAL_RECORD_REQUIRED", "EXTERNAL_VERIFICATION_REQUIRED", "USER_INPUT_REQUIRED", "REFERENCE_TIME_REQUIRED", "APPLICABILITY_TIME_PENDING", "MISSING_TENDER_ATTACHMENT", "COST_APPLICABILITY_REQUIRED", "CAPABILITY_CRITERIA_REQUIRED").contains(code));
        String beforeVisual=statusOf(a.containsKey("preOcrEvaluation")?a.get("preOcrEvaluation"):a.get("rawEvaluation"));
        boolean templateEvidencePending=templateFinal&&"UNDETERMINED".equals(status)&&template.evidence()!=null&&cn.bidcheck.compat.Java8Api.setOf(TemplateTypes.EvidenceState.PARTIAL, TemplateTypes.EvidenceState.NOT_LOCATED, TemplateTypes.EvidenceState.CONFLICTING).contains(template.evidence().state());
        boolean evidencePending=templateEvidencePending||"EXTERNAL_INPUT_REQUIRED".equals(text(map(a.get("model")),"decision"))||resolvedInventory||"UNDETERMINED".equals(beforeVisual)||cn.bidcheck.compat.Java8Api.setOf("RELATION_UNCONFIRMED", "EVIDENCE_NOT_FOUND").contains(atomicState)
            ||codes.contains("VISUAL_MARK_UNREADABLE")&&!visualReview.unreadableOnly()
            ||codes.stream().anyMatch(code->cn.bidcheck.compat.Java8Api.setOf("EVIDENCE_MISSING", "PERSONNEL_COVERAGE_INCOMPLETE", "NO_CANDIDATE_EVIDENCE", "EVIDENCE_NOT_FOUND").contains(code));
        boolean visual=Boolean.TRUE.equals(ocr.get("required"));
        String state;
switch (status) {case "PASS":state = "SATISFIED"; break;case "FAIL":case "WARNING":state = "RISK"; break;case "NOT_APPLICABLE":state = "NOT_APPLICABLE"; break;case "NEEDS_CONFIRMATION":state = processing?"SYSTEM_INCOMPLETE":"USER_ACTION"; break;default:state = processing?"SYSTEM_INCOMPLETE":!externalState.isEmpty()?"WAIT_EXTERNAL":external||visualReview.unreadableOnly()?"USER_ACTION":evidencePending?"EVIDENCE_PENDING":visual?"USER_ACTION":"EVIDENCE_PENDING"; break;}
        int pendingCards=map(atomic.get("proof")).get("pendingConfirmationCount") instanceof Number?((Number)map(atomic.get("proof")).get("pendingConfirmationCount")).intValue():0;
        if(pendingCards>0&&("UNDETERMINED".equals(status)||"NEEDS_CONFIRMATION".equals(status)))state="USER_ACTION";
        boolean pending="UNDETERMINED".equals(status);
        boolean fullText=fullTextNotice(evaluation,a);
        Map<String,Object> inventory=maps(atomic.get("conditions")).stream().map(row->map(row.get("proof")))
            .filter(p->p.containsKey("inventoryTotalMembers")).findFirst().orElse(Collections.emptyMap());
        if(pending && !inventory.isEmpty() && !recoveryFailed && externalState.isEmpty())state="EVIDENCE_PENDING";
        if(pending && pendingCards==0 && !recoveryFailed && Boolean.TRUE.equals(inventory.get("inventorySourceRangeClosed"))
                && "WAIT_STAGE".equals(text(externalState,"state")) && codes.contains("EXTERNAL_OWNER_DATA_REQUIRED")
                && Arrays.asList("WAIT_STAGE","EXTERNAL_WAIT_STAGE","EXTERNAL_OWNER_DATA_REQUIRED","REQUIREMENT_INVENTORY_COVERAGE_REQUIRED").containsAll(codes)
                && !RecoveryTransition.hasProcessingFailure(new cn.bidcheck.domain.Domain.Finding("","",null,evaluation,a)))state="WAIT_EXTERNAL";
        if(pending && fullText && pendingCards==0)state="NOTICE";
        if(pending && "WAIT_OWNER_DATA".equals(text(externalState,"state")))state="WAIT_EXTERNAL";
        boolean manualTarget=pending && "NO_EXECUTABLE_EVIDENCE_TARGET".equals(text(map(resolutionJob.get("semanticGate")),"reasonCode"))
            && !recoveryFailed && !Boolean.TRUE.equals(resolutionJob.get("processingFailed"))
            && !RecoveryTransition.hasProcessingFailure(new cn.bidcheck.domain.Domain.Finding("","",null,evaluation,a))
            && !Boolean.TRUE.equals(ocr.get("incomplete"));
        if(manualTarget)state="USER_ACTION";
        if(pending && codes.contains("BID_VOLUME_NOT_UPLOADED"))state="MATERIAL_REQUIRED";
        if(pending && "WAIT_TAX_REFERENCE_DATE".equals(text(resolutionJob,"strategy")))state="USER_ACTION";
        if(pending && codes.contains("ID_NUMBER_PAIR_CONFLICT") && codes.contains("SUBJECT_IDENTITY_CONFLICT"))state="RISK";
        boolean evidenceAssessmentRecorded=!atomic.isEmpty()||!codes.isEmpty()||!semanticGap.isEmpty()||!completeness.isEmpty()
            ||templateFinal||(evaluation.evidences()!=null&&!evaluation.evidences().isEmpty())||"BLOCKED_NO_PROGRESS".equals(text(resolutionJob,"status"));
        String label;
switch (state) {case "SATISFIED":label = "满足要求"; break;case "RISK":label = "存在风险"; break;case "SYSTEM_INCOMPLETE":label = "系统未完成"; break;case "USER_ACTION":label = "需你确认"; break;case "WAIT_EXTERNAL":label = "待外部条件"; break;case "NOT_APPLICABLE":label = "不适用"; break;case "NOTICE":label = "全文提示"; break;case "MATERIAL_REQUIRED":label = "请补充材料"; break;case "EVIDENCE_PENDING":label = evidenceAssessmentRecorded?"证据待核":"系统取证中"; break;default:label = "查看处理记录"; break;}
        boolean userAction="MATERIAL_REQUIRED".equals(state)||"USER_ACTION".equals(state)||"RISK".equals(state)||"SATISFIED".equals(state)&&visual;
        String owner;
switch (state) {case "SYSTEM_INCOMPLETE":case "EVIDENCE_PENDING":owner = "SYSTEM"; break;case "WAIT_EXTERNAL":owner = "EXTERNAL"; break;case "USER_ACTION":case "RISK":owner = "USER"; break;default:owner = userAction?"USER":"NONE"; break;}
        String next;
        switch(state) {
            case "SYSTEM_INCOMPLETE": next="需继续处理条款、证据校验或失败请求；当前不要求补交材料，也不计入人工核对待办。"; break;
            case "EVIDENCE_PENDING": next="需继续检索并关联现有文件中的证据；只有确认具体缺口后才要求补充材料。"; break;
            case "USER_ACTION": next="请核对待确认条件和所引原文，并记录复核依据。"; break;
            case "WAIT_EXTERNAL": next=text(externalState,"nextStep"); break;
            case "RISK": next="对照所列原文确认风险，并记录复核结论。"; break;
            case "SATISFIED": next=visual?"文字要求已得到支持；另有原图复核事项，请核对下方指定位置。":"本项已形成满足要求的结论，依据保留供追溯。"; break;
            case "MATERIAL_REQUIRED": next="请补充所列分册后重新检测。"; break;
            case "NOTICE": next="查看已有出处和比较范围。"; break;
            case "NOT_APPLICABLE": next="本项不适用于当前条件，适用依据保留供追溯。"; break;
            default: next="查看该项的原文和处理记录。";
        }
        boolean stopped=resolutionJob!=null&&!cn.bidcheck.compat.Java8Api.isBlank(text(resolutionJob,"id"))&&cn.bidcheck.compat.Java8Api.setOf("BLOCKED_NO_PROGRESS", "FAILED").contains(text(resolutionJob,"status"));
        if(stopped&&cn.bidcheck.compat.Java8Api.setOf("SYSTEM_INCOMPLETE", "EVIDENCE_PENDING").contains(state))
            next="本轮自动补查已停止，需修复处理环节或补足检索条件后再启动；当前不据此要求补交材料。";
        if("USER_ACTION".equals(state)&&"EXTERNAL_INPUT".equals(gapKind))next="需取得招标条款要求的外部记录或现场核验结果；现有文件检索不能替代该记录。";
        if("USER_ACTION".equals(state)&&codes.contains("INFERENCE_CONFIRMATION_REQUIRED"))next="核对推断卡片的实际主体、字段取值与引用原文；确认或否定后由系统重算，确认操作不会直接判通过。";
        if("USER_ACTION".equals(state)&&codes.contains("MISSING_TENDER_ATTACHMENT")){
            label="缺招标附件";next="请补充招标正文引用的《技术规范书》及附件，以确定完整检查要求；这不表示投标文件缺材料。";
        }
        if("USER_ACTION".equals(state)&&codes.contains("COST_APPLICABILITY_REQUIRED")){
            label="适用条件待确认";next="需确认前附表是否免做成本核算，再结合现场核算结果判断；未确认前不判不符合。";
        }
        if("USER_ACTION".equals(state)&&codes.contains("CAPABILITY_CRITERIA_REQUIRED")){
            label="核验标准待明确";next="招标原文未说明履约能力的具体指标或认可的证明方式，需依据招标澄清明确标准；这不表示投标材料缺失。";
        }
        if("SYSTEM_INCOMPLETE".equals(state)&&codes.contains("EXTERNAL_CONNECTOR_FAILED"))next="系统需修复并重试指定外部查询；已完成文件证据保留，不要求补交投标材料。";
        if("WAIT_EXTERNAL".equals(state)||"SYSTEM_INCOMPLETE".equals(state)&&Boolean.TRUE.equals(externalState.get("systemOwned"))){label=text(externalState,"label");next=text(externalState,"nextStep");}
        if("SYSTEM_INCOMPLETE".equals(state)&&codes.stream().anyMatch(code->code.startsWith("REQUIREMENT_INVENTORY_")))next="系统需展开剩余要求或等待相关子事项完成，再自动汇总；部分文件存在不能证明全清单满足。";
        if("USER_ACTION".equals(state)&&scoringReview){
            label=codes.contains("SCORE_SOURCE_LIST_AMBIGUOUS")?"评分口径待确认":"评分档次待评审";
            next=codes.contains("SCORE_SOURCE_LIST_AMBIGUOUS")?"招标清单含重复项目，已保留原文及逐项核对结果；需确认计分口径，不据此要求补交材料。":"客观内容范围已核对；请按所列评分分档评价针对性、可行性等主观要求。材料齐全不代表达到最高档。";
        }
        if("SYSTEM_INCOMPLETE".equals(state)&&!stopped&&codes.contains("UNIQUE_PRICE_SCOPE_REQUIRED"))next="系统尚未核全本项目的报价与备选方案范围；已识别报价一致仅作局部结果，当前不要求补交材料。";
        if(templateFinal&&!cn.bidcheck.compat.Java8Api.isBlank(Objects.toString(template.nextStep(),""))&&!cn.bidcheck.compat.Java8Api.setOf("SYSTEM_INCOMPLETE", "WAIT_EXTERNAL").contains(state))next=template.nextStep();
        if("USER_ACTION".equals(state)&&visualReview.unreadableOnly())next="指定原图已检查，但签章无法辨清；请确认所列签署位置，或提供该页清晰图像。相同页面不再自动重复识别。";
        if("RISK".equals(state)&&visualReview.negative())next="指定原图已检查，未观察到要求签章；请对照所列位置确认签署风险。本项不自动作出否决投标结论。";
        if(pendingCards>0&&"USER_ACTION".equals(state))next="还差 "+pendingCards+" 个待确认的证据关系，请核对卡片原文后确认或否定，再重新计算。";
        Map<String,Object> scope=proofScope(evaluation,a);
        if("SATISFIED".equals(state)&&!cn.bidcheck.compat.Java8Api.isBlank(text(scope,"label"))){
            label=text(scope,"label");
            if("WRITTEN_RESPONSE".equals(text(scope,"state")))next="已核对所引文件的书面响应；这不等于实际资格状态或外部查询结果已核实。";
            if("OBSERVED_OCCURRENCES".equals(text(scope,"state")))next="已识别的同对象同口径记录未见差异；结论范围仅为所列出现记录。";
            if("ROLE_CONFIGURATION_ONLY".equals(text(scope,"state")))next="已确认要求岗位有明确的本次任职人员；该结论不代表人数上限、兼任关系、人员资质或跨材料身份已核实。";
            if("DURATION_UNDERTAKING_ONLY".equals(text(scope,"state")))next="已确认按招标允许的格式作出本项目工期承诺；设计、施工及总工期仍按各自招标要求解释，未将“满足”转写成投标文件中的实填数字。";
            if("QUALITY_UNDERTAKING_ONLY".equals(text(scope,"state")))next="投标函实际填写的质量承诺满足招标要求；该结论仅核验书面响应，不证明实际履约质量。";
            if("BID_VALIDITY_UNDERTAKING_ONLY".equals(text(scope,"state")))next="已将投标函实填有效期与招标要求的天数比较；该结论仅核验有效期承诺，签章等独立要求另行核验。";
            if("CONDITIONAL_MATERIAL_SUBMISSION_ONLY".equals(text(scope,"state")))next="已核实本项目实际填写的材料正文和主体关系；签章及其他独立要求另行核验。";
        }
        String reason=finalMismatch?"已完成的辅助条件不足以证明整项业务要求；最终结果仍未确定。":Objects.toString(evaluation.reason(),"");
        if("SYSTEM_INCOMPLETE".equals(state)&&Boolean.TRUE.equals(externalState.get("systemOwned")))reason=text(externalState,"label")+"；当前尚未形成完整业务结论，不表示投标材料缺失。";
        if("WAIT_EXTERNAL".equals(state))reason=text(externalState,"label")+"；尚未形成满足或不满足要求的业务结论。";
        if("SYSTEM_INCOMPLETE".equals(state)&&!finalMismatch&&!recoveryFailed&&"RELATION_UNCONFIRMED".equals(atomicState)){
            Optional<Map<String,Object>> mapping=maps(atomic.get("gaps")).stream().filter(gap->FocusedCheckEvaluator.mappingPending(text(gap,"code"))).findFirst();
            if(mapping.isPresent()){
                if(!cn.bidcheck.compat.Java8Api.isBlank(text(mapping.get(),"description")))reason=text(mapping.get(),"description");
                if(!stopped)next="系统需继续完成证据字段或人员单位对应；当前不要求补交材料，也不计入人工核对待办。";
            }
        }
        if("NOTICE".equals(state)){
            next="查看已有出处和比较范围。";
        }
        if(pending && !inventory.isEmpty()){
            String progressLabel="子项 "+inventory.get("inventoryCompletedMembers")+"/"+inventory.get("inventoryTotalMembers")+" 已完成";
            reason=progressLabel+"。"+reason;
            if("EVIDENCE_PENDING".equals(state)){label=progressLabel;next="查看对应子事项和仍未覆盖的招标要求。";}
        }
        if("MATERIAL_REQUIRED".equals(state)){
            String volume=missingVolume(atomic);
            String material=volume.contains("报价")?"其中的报价或报价清单":"该分册中的资料";
            reason="未上传"+(volume.isEmpty()?"招标要求的分册":"《"+volume+"》")+"，本项需要"+material+"才能核对。";
            next="请补充该分册后重新检测。";
        }
        if(manualTarget && "USER_ACTION".equals(state)){
            LinkedHashSet<String> steps=new LinkedHashSet<>();
            for(Map<String,Object> gap:maps(atomic.get("gaps"))){
                String code=text(gap,"code");
                if("FORM_CONDITION_COVERAGE_REQUIRED".equals(code))steps.add("请逐一核对各成员的文书和要求签署的位置。");
                else if("VISUAL_MARK_UNREADABLE".equals(code))steps.add("请对照所列原图确认签字或盖章。");
                else if("DIRECT_SIGNER_EXEMPTION_PROOF_REQUIRED".equals(code))steps.add("请确认是否由法定代表人本人直接签署，以判断授权书免交条件。");
                else if("SCORING_BASE_CONFIGURATION_REQUIRED".equals(code)&&!baseScopeQuestion(atomic).isEmpty())steps.add(baseScopeQuestion(atomic));
                else if(!text(gap,"description").isEmpty())steps.add(text(gap,"description"));
            }
            if(!steps.isEmpty())next=String.join(" ",steps);
        }
        if("USER_ACTION".equals(state) && "WAIT_TAX_REFERENCE_DATE".equals(text(resolutionJob,"strategy")))next=text(resolutionJob,"nextAction");
        if("USER_ACTION".equals(state)&&pendingCards==0&&!professionQuestion.isEmpty()) {
            label="职称专业范围待确认"; next=professionQuestion+" 请在复核记录中注明口径和依据。职称计分仍须核对招标要求的共同证明。";
        }
        if("USER_ACTION".equals(state)){
            String cardQuestion=cardQuestions(atomic);
            Map<String,Object> variant=map(a.get("nameVariantAttribution"));
            if(!cardQuestion.isEmpty()){reason=cardQuestion;next=cardQuestion;}
            else if("OCR_ONLY_SINGLE_CHARACTER_NAME_DIFFERENCE".equals(text(variant,"code"))){
                next=text(variant,"nextStep");if(next.isEmpty())next=text(variant,"question");
                reason=text(variant,"reason").isEmpty()?next:text(variant,"reason");
            }else if(manualTarget){reason=next;}
            else if(!professionQuestion.isEmpty()){reason=professionQuestion;}
            else {
                String conditions=openConditions(atomic);
                if(!conditions.isEmpty()){reason=conditions;
                    if(!scoringReview&&!visualReview.unreadableOnly()&&!"WAIT_TAX_REFERENCE_DATE".equals(text(resolutionJob,"strategy"))
                            &&!codes.contains("MISSING_TENDER_ATTACHMENT")&&!codes.contains("COST_APPLICABILITY_REQUIRED")&&!codes.contains("CAPABILITY_CRITERIA_REQUIRED"))
                        next=conditions;
                }
            }
        }
        if("EVIDENCE_PENDING".equals(state)&&codes.contains("CERTIFICATE_SET_COVERAGE_REQUIRED")){
            for(Map<String,Object> condition:maps(atomic.get("conditions")))if(map(condition.get("proof")).get("observedCertificateCount") instanceof Number){
                Map<String,Object> p=map(condition.get("proof"));
                reason="已核对 "+p.get("observedCertificateCount")+" 份证照"+locations(condition.get("evidences"))+"。当前适用证照清单尚未核全。";
                next="请按当前项目和投标主体继续核对其余适用证照的有效期。";break;
            }
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("version",VERSION);out.put("state",state);out.put("label",label);out.put("owner",owner);
        out.put("reason",reason);out.put("nextStep",next);out.put("userActionRequired",userAction);out.put("finalStatus",status);
        if(!externalState.isEmpty())out.put("externalState",externalState);
        if(visualReview.reviewed())out.put("visualReview",cn.bidcheck.compat.Java8Api.mapOf("reviewed", true, "unreadableOnly", visualReview.unreadableOnly(), "negative", visualReview.negative(), "observations", visualReview.observations(), "automaticRejection", false));
        out.put("projectionVersion","business-dimensions-3");
        out.put("dimensions",dimensions(evaluation,a,resolutionJob));return out;
    }

    static boolean fullTextNotice(Evaluation evaluation, Map<String,Object> trace){
        return "UNDETERMINED".equals(evaluation.status()) && (trace.containsKey("consistencyGroupId")
            || "FULL_TEXT_REVIEW".equals(text(trace,"checkpointType"))
            || Objects.toString(evaluation.method(),"").contains("FULL_TEXT_REVIEW"));
    }

    public static Map<String,Object> dimensions(Evaluation evaluation,Map<String,Object> assessment,Map<String,Object> resolutionJob) {
        Map<String,Object> a=assessment==null?cn.bidcheck.compat.Java8Api.mapOf():assessment,job=resolutionJob==null?cn.bidcheck.compat.Java8Api.mapOf():resolutionJob;
        ResultProjection template=template(a);
        if(template!=null&&template.finalReconciled()&&cn.bidcheck.compat.Java8Api.isBlank(text(job,"id")))return templateDimensions(template);
        Map<String,Object> completeness=map(map(a.get("coverage")).get("completeness"));
        String finalStatus=evaluation.status(),jobStatus=text(job,"status"),execution="FINISHED";
        if(!cn.bidcheck.compat.Java8Api.isBlank(text(job,"id"))&&cn.bidcheck.compat.Java8Api.setOf("QUEUED", "RUNNING", "RETRY_WAIT", "SUCCEEDED", "BLOCKED_NO_PROGRESS", "WAITING_USER", "WAITING_EXTERNAL", "FAILED").contains(jobStatus))
            execution="SUCCEEDED".equals(jobStatus)?"FINISHED":jobStatus;
        String executionLabel;
switch (execution) {case "QUEUED":executionLabel = "补查已排队"; break;case "RUNNING":executionLabel = "自动补查中"; break;case "RETRY_WAIT":executionLabel = "等待重试"; break;case "BLOCKED_NO_PROGRESS":executionLabel = "补查已停止：无新进展"; break;case "WAITING_USER":executionLabel = "等待必要信息"; break;case "WAITING_EXTERNAL":executionLabel = "等待外部条件"; break;case "FAILED":executionLabel = "本轮补查失败"; break;default:executionLabel = "本轮已结束"; break;}
        String conclusion;
switch (finalStatus) {case "PASS":conclusion = "SATISFIED"; break;case "FAIL":conclusion = "NOT_SATISFIED"; break;case "WARNING":conclusion = "SUSPECTED_RISK"; break;case "NOT_APPLICABLE":conclusion = "NOT_APPLICABLE"; break;default:conclusion = "UNDETERMINED"; break;}
        String conclusionLabel;
switch (conclusion) {case "SATISFIED":conclusionLabel = "满足要求"; break;case "NOT_SATISFIED":conclusionLabel = "不满足要求"; break;case "SUSPECTED_RISK":conclusionLabel = "疑似风险，待确认"; break;case "NOT_APPLICABLE":conclusionLabel = "不适用"; break;default:conclusionLabel = "尚不能判断"; break;}
        boolean inventoryUnknown=Boolean.FALSE.equals(completeness.get("inventoryResolved"))||Boolean.TRUE.equals(completeness.get("coreRequirementDeferred"))
            ||maps(completeness.get("itemResults")).stream().anyMatch(item->"REQUIREMENT_UNRESOLVED".equals(text(item,"status")));
        boolean references=evaluation.evidences()!=null&&!evaluation.evidences().isEmpty();
        boolean hasBodyProof=maps(completeness.get("itemResults")).stream().anyMatch(item->"VERIFIED".equals(text(item,"status")));
        TrustedVisualReview.Result visualReview=TrustedVisualReview.inspect(a);
        boolean hasVisualTask=Boolean.TRUE.equals(map(a.get("ocrReview")).get("required"))&&!(visualReview.negative()&&Boolean.TRUE.equals(map(a.get("atomicCheck")).get("complete")));
        String evidence="NOT_APPLICABLE".equals(finalStatus)?"NOT_APPLICABLE":inventoryUnknown?"SCOPE_UNRESOLVED":
            cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(finalStatus)&&references&&!hasVisualTask?"SUFFICIENT":references||hasBodyProof?"PARTIAL":"NOT_RETRIEVED";
        String evidenceLabel;
switch (evidence) {case "NOT_APPLICABLE":evidenceLabel = "不适用"; break;case "SCOPE_UNRESOLVED":evidenceLabel = "要求范围尚未明确"; break;case "SUFFICIENT":evidenceLabel = "判断依据已记录"; break;case "PARTIAL":evidenceLabel = hasBodyProof?"部分义务已有证明":"证据尚未核全"; break;default:evidenceLabel = "尚未取得充分依据"; break;}
        if(visualReview.unreadableOnly()){evidence="REVIEWED_UNREADABLE";evidenceLabel="指定原图已检查，签章无法辨清";}
        if(verifiedScoringReview(map(a.get("atomicCheck")))&&"UNDETERMINED".equals(finalStatus))evidenceLabel="客观内容范围已核对，评分尚待评定";
        Map<String,Object> executionValue=new LinkedHashMap<>();executionValue.put("state",execution);executionValue.put("label",executionLabel);
        executionValue.put("jobId",text(job,"id"));executionValue.put("active",cn.bidcheck.compat.Java8Api.setOf("QUEUED", "RUNNING", "RETRY_WAIT").contains(execution));
        executionValue.put("reason",cn.bidcheck.compat.Java8Api.isBlank(text(job,"stopReason"))?text(job,"reason"):text(job,"stopReason"));executionValue.put("nextAction",text(job,"nextAction"));
        Map<String,Object> scope=proofScope(evaluation,a);
        if("SATISFIED".equals(conclusion)&&!cn.bidcheck.compat.Java8Api.isBlank(text(scope,"label")))conclusionLabel=text(scope,"label");
        return cn.bidcheck.compat.Java8Api.mapOf("execution", executionValue, "evidence", cn.bidcheck.compat.Java8Api.mapOf("state", evidence, "label", evidenceLabel), "proofScope", scope, "conclusion", cn.bidcheck.compat.Java8Api.mapOf("state", conclusion, "label", conclusionLabel, "determined", cn.bidcheck.compat.Java8Api.setOf("SATISFIED", "NOT_SATISFIED", "NOT_APPLICABLE").contains(conclusion)));
    }
    /** A declaration proves a written response; it does not establish an external fact. */
    static Map<String,Object> proofScope(Evaluation evaluation,Map<String,Object> assessment){
        Map<String,Object> unknown=cn.bidcheck.compat.Java8Api.mapOf("state", "UNSPECIFIED", "label", "");if(!"PASS".equals(evaluation.status()))return unknown;
        ResultProjection projection=template(assessment);
        if(projection!=null&&projection.finalReconciled()&&projection.conclusion()!=null){
            String scope=projection.conclusion().proofScope()==null?"":projection.conclusion().proofScope().name();
            String label;
switch (scope) {case "MATERIAL_SUBMISSION":label = "材料提交要求已满足"; break;case "WRITTEN_COMMITMENT":label = "书面承诺要求已满足"; break;case "FACTUAL_CONDITION":label = "业务条件已验证"; break;case "OBSERVED_CONSISTENCY":label = "已识别记录一致"; break;case "QUALITY_ADVISORY":label = "评分建议"; break;default:label = ""; break;}
            if(!cn.bidcheck.compat.Java8Api.isBlank(label))return cn.bidcheck.compat.Java8Api.mapOf("state", scope, "label", label);
        }
        Map<String,Object> derived=map(assessment.get("derivedConclusion"));
        if("PASS".equals(text(derived,"status"))&&"OBSERVED_OCCURRENCES".equals(text(derived,"proofScope"))&&Boolean.TRUE.equals(derived.get("comparisonCoverageComplete"))
                &&derived.get("unresolvedOccurrenceIds") instanceof Collection<?>&&((Collection<?>) (derived.get("unresolvedOccurrenceIds"))).isEmpty()
                &&derived.get("originalOccurrenceIds") instanceof Collection<?>&&!((Collection<?>) (derived.get("originalOccurrenceIds"))).isEmpty())
            return cn.bidcheck.compat.Java8Api.mapOf("state", "OBSERVED_OCCURRENCES", "label", "已识别记录一致");
        Map<String,Object> semantic=map(map(assessment.get("coverage")).get("semanticProof"));
        if(evaluation.evidences()!=null&&!evaluation.evidences().isEmpty()&&Boolean.TRUE.equals(semantic.get("validated"))&&"WRITTEN_RESPONSE".equals(text(semantic,"scope"))
                &&semantic.get("evidenceIds") instanceof Collection<?>&&!((Collection<?>) (semantic.get("evidenceIds"))).isEmpty())return cn.bidcheck.compat.Java8Api.mapOf("state", "WRITTEN_RESPONSE", "label", "书面要求已满足");
        List<Map<String,Object>> conditions=cn.bidcheck.compat.Java8Api.toList(maps(map(assessment.get("atomicCheck")).get("conditions")).stream().filter(c->"REQUIREMENT".equals(text(c,"conditionRole"))).filter(c->!"NOT_APPLICABLE".equals(text(c,"status"))));
        if(conditions.isEmpty()||conditions.stream().anyMatch(c->!"PASS".equals(text(c,"status"))||!Boolean.TRUE.equals(c.get("evaluated"))||map(c.get("proof")).isEmpty()||!(c.get("evidences") instanceof Collection<?>)||((Collection<?>) (c.get("evidences"))).isEmpty()||!maps(c.get("gaps")).isEmpty()))return unknown;
        Map<String,Object> atomic=map(assessment.get("atomicCheck"));
        if(Boolean.TRUE.equals(atomic.get("complete"))&&maps(atomic.get("gaps")).isEmpty()){
            for(java.util.Map.Entry<java.lang.String, java.lang.String> entry:cn.bidcheck.compat.Java8Api.mapOf("QUALITY_UNDERTAKING_ONLY", "质量承诺已响应", "BID_VALIDITY_UNDERTAKING_ONLY", "投标有效期已响应", "CONDITIONAL_MATERIAL_SUBMISSION_ONLY", "材料提交要求已满足").entrySet())
                if(conditions.stream().allMatch(c->entry.getKey().equals(text(map(c.get("proof")),"proofScope"))))return cn.bidcheck.compat.Java8Api.mapOf("state", entry.getKey(), "label", entry.getValue());
        }
        if(Boolean.TRUE.equals(atomic.get("complete"))&&maps(atomic.get("gaps")).isEmpty()
            &&conditions.stream().allMatch(c->"DURATION_UNDERTAKING_ONLY".equals(text(map(c.get("proof")),"proofScope"))))
            return cn.bidcheck.compat.Java8Api.mapOf("state", "DURATION_UNDERTAKING_ONLY", "label", "工期承诺已响应");
        if(Boolean.TRUE.equals(atomic.get("complete"))&&maps(atomic.get("gaps")).isEmpty()
            &&conditions.stream().allMatch(c->"PERSON_COUNT".equals(text(c,"predicate"))&&"ROLE_CONFIGURATION_ONLY".equals(text(map(c.get("proof")),"proofScope"))))
            return cn.bidcheck.compat.Java8Api.mapOf("state", "ROLE_CONFIGURATION_ONLY", "label", "岗位配置已确认");
        Set<String> written=cn.bidcheck.compat.Java8Api.setOf("MATERIAL", "DECLARATION", "SECTION", "VISUAL"),factual=cn.bidcheck.compat.Java8Api.setOf("NUMBER", "CERTIFICATE", "VALIDITY", "DATE_RANGE", "MONTHS", "PERSON_ROLE", "RELATION", "FIELD", "SAME_FIELD", "CONTRACT");
        if(conditions.stream().allMatch(c->written.contains(text(c,"predicate"))))return cn.bidcheck.compat.Java8Api.mapOf("state", "WRITTEN_RESPONSE", "label", "书面要求已满足");
        if(conditions.stream().allMatch(c->factual.contains(text(c,"predicate"))))return cn.bidcheck.compat.Java8Api.mapOf("state", "BUSINESS_CONDITIONS", "label", "业务条件已验证");
        return unknown;
    }
    private static String missingVolume(Map<String,Object> atomic){
        for(Map<String,Object> g:maps(atomic.get("gaps")))if("BID_VOLUME_NOT_UPLOADED".equals(text(g,"code"))&&!text(g,"requestedVolume").isEmpty())return text(g,"requestedVolume");
        for(Map<String,Object> c:maps(atomic.get("conditions")))if(maps(c.get("gaps")).stream().anyMatch(g->"BID_VOLUME_NOT_UPLOADED".equals(text(g,"code")))){
            String name=text(map(c.get("proof")),"requestedVolume");if(!name.isEmpty())return name;}
        for(Map<String,Object> g:maps(atomic.get("gaps")))if("BID_VOLUME_NOT_UPLOADED".equals(text(g,"code"))){
            String name=text(g,"requiredInput");if(name.matches("[^，。；;\\n]{1,60}"))return name;}
        return "";
    }
    private static String locations(Object raw){
        if(!(raw instanceof Collection<?>))return "";
        Set<String> locations=new LinkedHashSet<>();
        for(Object value:(Collection<?>)raw){
            Map<String,Object> e=value instanceof cn.bidcheck.domain.Domain.Evidence?JSON.convertValue(value,Map.class):map(value);
            Object page=e.containsKey("page")?e.get("page"):e.get("location");
            if(!(page instanceof Number)||((Number)page).intValue()<1)continue;
            locations.add("第"+page+("PAGE".equals(text(e,"locationType"))?"页":"段"));
        }
        return locations.isEmpty()?"":"（"+String.join("、",locations)+"）";
    }
    private static String cardQuestions(Map<String,Object> atomic){
        Set<String> questions=new LinkedHashSet<>();
        List<Map<String,Object>> candidates=new ArrayList<>(maps(atomic.get("inferredFacts")));
        for(Map<String,Object> c:maps(atomic.get("conditions")))candidates.addAll(maps(map(c.get("proof")).get("inferredFacts")));
        for(Map<String,Object> card:candidates)if("NEEDS_CONFIRMATION".equals(text(card,"status"))){
            String q=text(card,"question");if(q.isEmpty())q=text(card,"claim");if(q.isEmpty())continue;
            q=q.replaceAll("第\\[([0-9]+)\\]页","第$1页");
            String where=locations(card.get("evidences"));
            if(!where.isEmpty()&&!q.contains(where.substring(1,where.length()-1)))q="请核对"+where+"。"+q;
            if(!q.startsWith("请")&&!q.endsWith("？")&&!q.endsWith("?"))q="请确认："+q;
            questions.add(q);
        }
        return String.join(" ",questions);
    }
    private static String baseScopeQuestion(Map<String,Object> atomic){
        for(Map<String,Object> c:maps(atomic.get("conditions"))){
            Map<String,Object> proof=map(c.get("proof")),base=map(proof.get("baseConfiguration"));
            if(!"source-extra-personnel-score-1".equals(text(proof,"executor"))||maps(base.get("gaps")).stream().noneMatch(g->"SCORING_BASE_REQUIREMENT_SOURCE_REQUIRED".equals(text(g,"code"))))continue;
            return "请核对招标文件的人员要求"+locations(proof.get("baseRequirementSources"))+"，确认本项增员评分采用哪些基础岗位、人数和资格条件。";
        }
        return "";
    }
    private static String openConditions(Map<String,Object> atomic){
        Set<String> steps=new LinkedHashSet<>();
        for(Map<String,Object> c:maps(atomic.get("conditions")))if(!maps(c.get("gaps")).isEmpty()||Arrays.asList("UNDETERMINED","NEEDS_CONFIRMATION").contains(text(c,"status"))){
            String title=text(c,"title");if(title.isEmpty())title=text(c,"description");if(title.isEmpty())continue;
            String where=locations(c.get("evidences"));
            if(where.isEmpty())where=locations(Collections.singletonList(map(map(c.get("requirementContext")).get("identity"))));
            steps.add("请核对"+title+where+"。");
        }
        return String.join(" ",steps);
    }
    static String titleProfessionQuestion(Map<String,Object> atomic) {
        String code="SCORING_TITLE_PROFESSION_SCOPE_REQUIRED";
        List<Map<String,Object>> gaps=maps(atomic.get("gaps"));
        if(gaps.isEmpty()||gaps.stream().anyMatch(g->!code.equals(text(g,"code")))) return "";
        for(Map<String,Object> condition:maps(atomic.get("conditions"))) {
            Map<String,Object> proof=map(condition.get("proof")), review=map(proof.get("professionScopeReview"));
            if(Boolean.TRUE.equals(condition.get("evaluated"))&&"REQUIREMENT".equals(text(condition,"conditionRole"))
                    &&"SOURCE_BOUND_TITLE_PROFESSION_REVIEW".equals(text(proof,"decisionBasis"))
                    &&Boolean.FALSE.equals(proof.get("scoreProduced"))&&condition.get("evidences") instanceof Collection<?>
                    &&!((Collection<?>)condition.get("evidences")).isEmpty()
                    &&maps(condition.get("gaps")).stream().anyMatch(g->code.equals(text(g,"code"))))
                return text(review,"question");
        }
        return "";
    }
    private static boolean verifiedScoringReview(Map<String,Object> atomic){
        Set<String> reviewCodes=cn.bidcheck.compat.Java8Api.setOf("QUALITY_GRADE_REVIEW_REQUIRED", "SCORE_SOURCE_LIST_AMBIGUOUS");
        List<Map<String,Object>> gaps=maps(atomic.get("gaps"));
        if(gaps.isEmpty()||gaps.stream().anyMatch(g->!reviewCodes.contains(text(g,"code"))))return false;
        return maps(atomic.get("conditions")).stream().anyMatch(c->Boolean.TRUE.equals(c.get("evaluated"))&&"REQUIREMENT".equals(text(c,"conditionRole"))
            &&"PROGRAM_STRUCTURAL_COVERAGE".equals(text(map(c.get("proof")),"decisionBasis"))&&Boolean.TRUE.equals(map(c.get("proof")).get("coverageComplete"))
            &&c.get("evidences") instanceof Collection<?>&&!((Collection<?>) (c.get("evidences"))).isEmpty()
            &&maps(c.get("gaps")).stream().anyMatch(g->reviewCodes.contains(text(g,"code"))));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object raw){return raw instanceof Map<?,?>?(Map<String,Object>)raw:cn.bidcheck.compat.Java8Api.mapOf();}
    private static List<Map<String,Object>> maps(Object raw){return raw instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (raw)).stream().filter(Map.class::isInstance).map(BusinessResultPresentation::map)):cn.bidcheck.compat.Java8Api.listOf();}
    private static String text(Map<String,Object> value,String key){return Objects.toString(value.get(key),"");}
    private static String statusOf(Object value){return value instanceof Evaluation?((Evaluation) (value)).status():text(map(value),"status");}
    private static ResultProjection template(Map<String,Object> assessment){
        Object direct=assessment==null?null:assessment.get("resultProjection");
        if(direct instanceof ResultProjection)return ((ResultProjection) (direct));
        if(direct instanceof Map<?,?>&&"template-result-v1".equals(Objects.toString(((Map<?,?>) (direct)).get("schemaVersion"),"")))try{return JSON.convertValue(((Map<?,?>) (direct)),ResultProjection.class);}catch(IllegalArgumentException ignored){}
        Object fallback=assessment==null?null:assessment.get("templateResult");
        if(fallback instanceof ResultProjection)return ((ResultProjection) (fallback));
        if(fallback instanceof Map<?,?>&&"template-result-v1".equals(Objects.toString(((Map<?,?>) (fallback)).get("schemaVersion"),"")))try{return JSON.convertValue(((Map<?,?>) (fallback)),ResultProjection.class);}catch(IllegalArgumentException ignored){}
        return null;
    }
    private static Map<String,Object> templateDimensions(ResultProjection projection){
        String execution=projection.execution()==null?"FINISHED":projection.execution().state().name();
        String executionLabel;
switch (execution) {case "QUEUED":executionLabel = "补查已排队"; break;case "RUNNING":executionLabel = "自动补查中"; break;case "RETRY_WAIT":executionLabel = "等待重试"; break;case "BLOCKED":executionLabel = "等待系统补齐输入"; break;case "FAILED":executionLabel = "本轮处理失败"; break;default:executionLabel = "本轮已结束"; break;}
        String conclusion=projection.conclusion()==null?"UNDETERMINED":projection.conclusion().state().name();
        String conclusionLabel;
switch (conclusion) {case "SATISFIED":conclusionLabel = "满足要求"; break;case "NOT_SATISFIED":conclusionLabel = "不满足要求"; break;case "SUSPECTED_RISK":conclusionLabel = "疑似风险，待确认"; break;case "NOT_APPLICABLE":conclusionLabel = "不适用"; break;default:conclusionLabel = "尚不能判断"; break;}
        String evidence=projection.evidence()==null?"NOT_LOCATED":projection.evidence().state().name();
        String evidenceLabel;
switch (evidence) {case "SUFFICIENT":evidenceLabel = "判断依据已记录"; break;case "PARTIAL":evidenceLabel = "证据尚未核全"; break;case "CONFLICTING":evidenceLabel = "证据存在冲突"; break;case "SCOPE_UNRESOLVED":evidenceLabel = "要求范围尚未明确"; break;case "NOT_APPLICABLE":evidenceLabel = "不适用"; break;default:evidenceLabel = "尚未取得充分依据"; break;}
        String proof=projection.conclusion()==null||projection.conclusion().proofScope()==null?"UNSPECIFIED":projection.conclusion().proofScope().name();
        String proofLabel;
switch (proof) {case "MATERIAL_SUBMISSION":proofLabel = "材料提交要求"; break;case "WRITTEN_COMMITMENT":proofLabel = "书面承诺要求"; break;case "FACTUAL_CONDITION":proofLabel = "业务事实条件"; break;case "OBSERVED_CONSISTENCY":proofLabel = "已识别记录范围"; break;case "QUALITY_ADVISORY":proofLabel = "评分建议"; break;default:proofLabel = ""; break;}
        Map<String,Object> executionValue=new LinkedHashMap<>();executionValue.put("state",execution);executionValue.put("label",executionLabel);
        executionValue.put("jobId",projection.execution()==null||projection.execution().jobId()==null?"":projection.execution().jobId());executionValue.put("active",cn.bidcheck.compat.Java8Api.setOf("QUEUED", "RUNNING", "RETRY_WAIT").contains(execution));
        executionValue.put("reason",projection.conclusion()==null?"":Objects.toString(projection.conclusion().summary(),""));executionValue.put("nextAction",projection.nextStep());
        return cn.bidcheck.compat.Java8Api.mapOf("execution", executionValue, "evidence", cn.bidcheck.compat.Java8Api.mapOf("state", evidence, "label", evidenceLabel), "proofScope", cn.bidcheck.compat.Java8Api.mapOf("state", proof, "label", proofLabel), "conclusion", cn.bidcheck.compat.Java8Api.mapOf("state", conclusion, "label", conclusionLabel, "determined", cn.bidcheck.compat.Java8Api.setOf("SATISFIED", "NOT_SATISFIED", "NOT_APPLICABLE").contains(conclusion)));
    }
}
