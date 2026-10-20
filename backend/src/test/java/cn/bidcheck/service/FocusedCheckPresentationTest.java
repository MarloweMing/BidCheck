package cn.bidcheck.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Presentation regressions only: no model, database, or external calls. */
class FocusedCheckPresentationTest {
    Map<String,Object> gap(String code){return Map.of("code",code,"description","原始说明","requiredInput","待核");}
    Map<String,Object> check(String code){return Map.of("businessDecision","UNDETERMINED","workflowState","NEEDS_REVIEW","complete",false,"gaps",List.of(gap(code)));}
    @Test void fourCausesHaveDistinctBusinessLabels(){
        for(var item:Map.of("REQUIREMENT_COMPILATION_INCOMPLETE","条款未解析完整","CERTIFICATE_EVIDENCE_REQUIRED","证据未找到","SUBJECT_RELATION_UNPROVEN","对应关系待确认","RULE_COMPILATION_UNAVAILABLE","处理失败").entrySet())
            assertEquals(item.getValue(),FocusedCheckEvaluator.presentation(check(item.getKey())).get("label"));
    }
    @Test void newTransportAndOutputFailuresNeverAskForMissingMaterials(){
        for(String code:List.of("MODEL_REQUEST_TIMEOUT","CAPACITY_TIMEOUT","OUTPUT_SCHEMA_INVALID","OUTPUT_JSON_INVALID","OUTPUT_LIMIT","INPUT_LIMIT","HTTP_429","TRANSPORT_FAILURE")){
            var p=FocusedCheckEvaluator.presentation(check(code));assertEquals("PROCESSING_FAILED",p.get("state"));
            assertTrue(p.get("reason").toString().contains("不能据此判断材料缺失"));assertTrue(p.get("nextSteps").toString().contains("不要求补交材料"));
        }
    }
    @Test void failureTakesPriorityOverInputAndRelationshipGaps(){
        var raw=Map.<String,Object>of("gaps",List.of(gap("CERTIFICATE_EVIDENCE_REQUIRED"),gap("SUBJECT_RELATION_UNPROVEN")),"workflowState","ENGINE_FAILURE");
        var p=FocusedCheckEvaluator.presentation(raw);assertEquals("PROCESSING_FAILED",p.get("state"));
        assertTrue(((List<?>)p.get("nextSteps")).get(0).toString().contains("处理说明"));assertEquals(3,((List<?>)p.get("issues")).size());
    }
    @Test void recoveredAttemptDoesNotOverrideCompletedBusinessOutcome(){
        var p=FocusedCheckEvaluator.presentation(Map.of("complete",true,"workflowState","COMPLETED","modelDiagnostics",Map.of("unresolvedFailures",0,"recoveredAttempts",1)));
        assertEquals("COMPLETED",p.get("state"));
    }
    @Test void unresolvedAttemptCannotBePresentedAsComplete(){
        var p=FocusedCheckEvaluator.presentation(Map.of("complete",false,"workflowState","ENGINE_FAILURE","modelDiagnostics",Map.of("unresolvedFailures",1)));
        assertEquals("PROCESSING_FAILED",p.get("state"));
    }
    @Test void completedDecisionOutranksAnEarlierDifferentCandidateFailure(){
        var raw=Map.<String,Object>of("complete",true,"workflowState","COMPLETED","businessDecision","PASS","modelDiagnostics",Map.of("unresolvedFailures",1));
        var p=FocusedCheckEvaluator.presentation(raw);assertEquals("COMPLETED",p.get("state"));
        assertTrue(p.get("issues").toString().contains("候选处理记录"));assertFalse(p.get("nextSteps").toString().contains("不要求补交材料"));
        assertTrue(ReportService.atomicCheckHtml(raw).contains("条件计算已结束"));
    }
    @Test void missingPersonnelCountComparisonIsUnparsedRatherThanMissingBidEvidence(){
        for(String code:List.of("PERSON_COUNT_ROLE_REQUIRED","PERSON_COUNT_COMPARISON_REQUIRED"))assertEquals("REQUIREMENT_UNPARSED",FocusedCheckEvaluator.presentation(check(code)).get("state"));
        for(String code:List.of("PERSON_COUNT_EMPLOYER_REQUIRED","PERSON_COUNT_IDENTITY_REQUIRED"))assertEquals("RELATION_UNCONFIRMED",FocusedCheckEvaluator.presentation(check(code)).get("state"));
    }
    @Test void unknownReasonDoesNotInventMissingEvidence(){assertEquals("NEEDS_REVIEW",FocusedCheckEvaluator.presentation(check("FUTURE_UNKNOWN_CODE")).get("state"));}
    @Test void comparisonAndRegistrationGapsRetainSpecificSystemOwnedCause(){
        for(String code:List.of("COMPARISON_FIELD_MAPPING_REQUIRED","COMPARISON_RECORD_IDENTITY_UNPROVEN","REGISTRATION_EMPLOYER_BINDING_REQUIRED")){
            var p=FocusedCheckEvaluator.presentation(check(code));assertEquals("RELATION_UNCONFIRMED",p.get("state"));assertTrue(p.get("nextSteps").toString().contains("系统需继续"));assertTrue(p.get("issues").toString().contains(code));assertTrue(p.get("issues").toString().contains("原始说明"));
        }
        assertEquals("EVIDENCE_NOT_FOUND",FocusedCheckEvaluator.presentation(check("REGISTRATION_CERTIFICATE_REQUIRED")).get("state"));
    }
    @Test void partialCompletionDoesNotTurnIntoSuccess(){assertEquals("NEEDS_REVIEW",FocusedCheckEvaluator.presentation(Map.of("workflowState","COMPLETED","complete",false)).get("state"));}
    @Test void internalIdsBecomeConditionNamesWithoutPartialIdReplacement(){
        String value=FocusedCheckEvaluator.readable("rule_10与rule_1不同；rule_99待核",Map.of("rule_1","报价含税口径","rule_10","证书有效期"));
        assertEquals("条件「证书有效期」与条件「报价含税口径」不同；本条件待核",value);
    }
    @Test void rawGapsAndDecisionsRemainUntouched(){
        Map<String,Object> gap=Map.of("code","REQUIREMENT_COMPILATION_INCOMPLETE","description","rule_1 未确定");
        var raw=Map.<String,Object>of("businessDecision","UNDETERMINED","gaps",List.of(gap),"conditions",List.of(Map.of("id","rule_1","title","报价口径")));
        String before=raw.toString();var display=FocusedCheckEvaluator.presentation(raw);assertEquals(before,raw.toString());assertFalse(display.toString().contains("rule_1"));
    }
    @Test void requirementInheritanceIsNotConvertedIntoBidEvidence(){
        var source=Map.<String,Object>of("value","项目经理","sourceQuote","项目经理应持证","sourceKind","ORIGINAL_REQUIREMENT","bindingKind","PARENT","parentRuleId","parent","start",0,"end",7);
        var context=FocusedCheckEvaluator.requirementContext(Map.of("parameters",Map.of("requirementBindings",Map.of("subject",source))));
        assertEquals("项目经理",context.get("value"));assertFalse(context.containsKey("evidences"));assertFalse(context.containsKey("documentId"));assertTrue(context.get("boundary").toString().contains("不代表已确认投标人员"));
    }
    @Test void unsupportedContextSourceCannotLookLikeVerifiedInheritance(){
        assertTrue(FocusedCheckEvaluator.requirementContext(Map.of("parameters",Map.of("requirementBindings",Map.of("subject",Map.of("value","某人","sourceQuote","模型猜测","sourceKind","MODEL","bindingKind","PARENT"))))).isEmpty());
    }
    @Test void htmlAndCsvKeepRequirementQuoteSeparateAndEscaped(){
        var source=Map.<String,Object>of("value","项目经理","sourceQuote","<原文>项目经理","sourceKind","ORIGINAL_REQUIREMENT","bindingKind","PARENT","parentTitle","人员资格");
        var condition=Map.<String,Object>of("id","rule_1","title","注册证有效期","status","UNDETERMINED","sourceQuote","证件须在截止日有效","requirementContext",source,"gaps",List.of(gap("SUBJECT_RELATION_UNPROVEN")),"evidences",List.of());
        var check=Map.<String,Object>of("conditions",List.of(condition),"gaps",List.of(gap("SUBJECT_RELATION_UNPROVEN")),"workflowState","NEEDS_REVIEW");
        String html=ReportService.atomicCheckHtml(check),csv=ReportService.atomicCheckText(check);
        assertTrue(html.contains("查看条件原句与要求对象"));assertTrue(html.contains("&lt;原文&gt;"));assertFalse(html.contains("<原文>"));assertTrue(html.contains("沿用上级条件：人员资格"));
        assertTrue(csv.contains("证件须在截止日有效"));assertTrue(csv.contains("不代表已确认投标人员"));assertFalse(html.contains("data-action=\"page-preview\""));
    }
    @Test void applicabilityFalseIsNotARequirementFailureInExports(){
        var condition=Map.<String,Object>of("title","联合体是否适用","status","FAIL","conditionRole","APPLICABILITY","reason","本项非联合体","gaps",List.of());
        String html=ReportService.atomicCheckHtml(Map.of("complete",true,"workflowState","COMPLETED","businessDecision","PASS","conditions",List.of(condition)));
        assertTrue(html.contains("适用判断：不成立"));assertFalse(html.contains("条件不满足"));
    }
    @Test void incompleteCompilationDoesNotImplyMissingMaterialsInExports(){
        var condition=Map.<String,Object>of("title","报价要求","status","UNDETERMINED","gaps",List.of(gap("REQUIREMENT_COMPILATION_INCOMPLETE")));
        String html=ReportService.atomicCheckHtml(Map.of("conditions",List.of(condition),"gaps",List.of(gap("RULE_COMPILATION_UNAVAILABLE")),"workflowState","ENGINE_FAILURE"));
        assertTrue(html.contains("处理失败"));assertTrue(html.contains("条款未解析完整"));assertTrue(html.contains("不能据此判断材料缺失"));assertFalse(html.contains("待补信息"));
    }
}
