package cn.bidcheck.service;

import cn.bidcheck.checktemplate.ResultProjection;
import cn.bidcheck.checktemplate.TemplateTypes;
import cn.bidcheck.domain.Domain.Evaluation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reconciles the deterministic template output with the published evaluation after all evidence
 * and OCR guards. A preliminary template conclusion is never exposed as the final conclusion.
 */
final class TemplateResultFinalizer {
    private TemplateResultFinalizer() {}

    static ResultProjection reconcile(Evaluation evaluation, ResultProjection projection) {
        if (projection == null) return null;
        TemplateTypes.ConclusionState finalConclusion = conclusion(evaluation.status());
        ResultProjection.Conclusion preliminary = projection.conclusion();
        boolean adjusted = preliminary == null || preliminary.state() != finalConclusion;
        ResultProjection.Conclusion conclusion = new ResultProjection.Conclusion(
            finalConclusion,
            adjusted ? Objects.toString(evaluation.reason(), "") : preliminary.summary(),
            preliminary == null ? TemplateTypes.ProofStandard.FACTUAL_CONDITION : preliminary.proofScope(),
            adjusted ? guardCode(evaluation.method()) : preliminary.reasonCode()
        );
        ResultProjection.Evidence evidence = projection.evidence();
        if (adjusted && finalConclusion == TemplateTypes.ConclusionState.UNDETERMINED
                && evidence != null && evidence.state() == TemplateTypes.EvidenceState.SUFFICIENT) {
            evidence = new ResultProjection.Evidence(TemplateTypes.EvidenceState.PARTIAL, evidence.refs());
        }
        ResultProjection.Execution execution = projection.execution();
        ResultProjection.Action action = projection.action();
        List<String> missingInputs = projection.missingInputs();
        String nextStep = projection.nextStep();
        if (adjusted && finalConclusion == TemplateTypes.ConclusionState.UNDETERMINED) {
            String jobId = execution == null ? null : execution.jobId();
            execution = new ResultProjection.Execution(TemplateTypes.ExecutionState.BLOCKED, true, jobId);
            String itemId = cn.bidcheck.compat.Java8Api.isBlank(Objects.toString(projection.businessItemId(), ""))
                ? Objects.toString(projection.checkSpecId(), "unknown") : projection.businessItemId();
            String actionId = "final-guard:" + itemId + ":" + projection.resultRevision();
            action = new ResultProjection.Action(TemplateTypes.ActionOwner.SYSTEM, "RESOLVE_FINAL_GUARD", actionId);
            LinkedHashSet<String> missing = new LinkedHashSet<>(missingInputs == null ? cn.bidcheck.compat.Java8Api.listOf() : missingInputs);
            missing.add("system.finalGuard." + guardCode(evaluation.method()));
            missingInputs = new ArrayList<>(missing);
            String suggestion = Objects.toString(evaluation.suggestion(), "").trim();
            nextStep = cn.bidcheck.compat.Java8Api.isBlank(suggestion)
                ? "系统需处理最终保护项并重新计算结论；当前不要求用户补交材料。"
                : "系统需处理最终保护项并重新计算结论：" + suggestion;
        }
        return new ResultProjection(projection.schemaVersion(), projection.businessItemId(), projection.checkSpecId(),
            projection.specRevision(), projection.resultRevision(), projection.category(), projection.purpose(),
            projection.verificationMode(), execution, conclusion, evidence, action,
            projection.subchecks(), missingInputs, projection.dependencies(),
            new ResultProjection.Review(preliminary == null ? TemplateTypes.ConclusionState.UNDETERMINED : preliminary.state(), null),
            projection.evaluatorVersion(), nextStep, true);
    }

    private static TemplateTypes.ConclusionState conclusion(String status) {
        switch (Objects.toString(status, "")) {case "PASS":return TemplateTypes.ConclusionState.SATISFIED;case "FAIL":return TemplateTypes.ConclusionState.NOT_SATISFIED;case "WARNING":return TemplateTypes.ConclusionState.SUSPECTED_RISK;case "NOT_APPLICABLE":return TemplateTypes.ConclusionState.NOT_APPLICABLE;default:return TemplateTypes.ConclusionState.UNDETERMINED;}
    }

    private static String guardCode(String method) {
        String value = Objects.toString(method, "FINAL_GUARD").toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        return "FINAL_" + (cn.bidcheck.compat.Java8Api.isBlank(value) ? "GUARD" : value);
    }

    /**
     * Reads the frozen evidence-query contract emitted by the assembler. This keeps the final
     * evidence guard independent of template ids: the guard validates the document roles that the
     * selected template actually requested instead of assuming every successful check needs BID
     * evidence. An empty result intentionally means "use the ordinary BID default".
     */
    static Set<String> requiredProofDocumentRoles(Map<String,Object> assessment) {
        Map<String,Object> templateEvidence=map(assessment==null?null:assessment.get("templateEvidence"));
        Object queriesValue=templateEvidence.get("evidenceQueries");
        if(!(queriesValue instanceof Collection<?>)||((Collection<?>) (queriesValue)).isEmpty())return cn.bidcheck.compat.Java8Api.setOf();
        LinkedHashSet<String> roles=new LinkedHashSet<>();
        for(Object queryValue:((Collection<?>) (queriesValue))){
            Map<String,Object> query=map(queryValue);
            Object roleValue=query.get("documentRoles");
            if(roleValue instanceof Collection<?>)for(Object value:((Collection<?>) (roleValue)))addRole(roles,value);
            else addRole(roles,roleValue);
        }
        return cn.bidcheck.compat.Java8Api.setCopyOf(roles);
    }

    private static void addRole(Set<String> roles,Object value){
        String role=cn.bidcheck.compat.Java8Api.strip(Objects.toString(value,"")).toUpperCase(Locale.ROOT);
        if(cn.bidcheck.compat.Java8Api.setOf("BID", "TENDER").contains(role))roles.add(role);
    }

    private static Map<String,Object> map(Object value){
        if(!(value instanceof Map<?,?>))return cn.bidcheck.compat.Java8Api.mapOf();
        java.util.LinkedHashMap<String,Object> result=new java.util.LinkedHashMap<>();
        ((Map<?,?>) (value)).forEach((key,item)->result.put(Objects.toString(key,""),item));
        return result;
    }
}
