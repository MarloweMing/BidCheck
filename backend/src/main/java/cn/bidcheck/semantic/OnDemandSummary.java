package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.Finding;
import java.util.*;

/** Reports observed attempts separately from durable admission reservations and missing results. */
public final class OnDemandSummary {
    private OnDemandSummary() { }
    public static Map<String,Object> summarize(Map<String,Object> run,List<Finding> findings,int expectedCount) {
        if(!"RULES_RETRIEVAL".equals(run.get("checkMode")))return Map.of();
        Map<String,Object> model=new LinkedHashMap<>(),retrieval=new LinkedHashMap<>();
        model.put("scope","RELATED_EVIDENCE_DOUBTS"); model.put("checkpointCount",expectedCount);
        for(String key:List.of("modelRequestedCheckpoints","ruleDecidedCheckpoints","evidenceInsufficientCheckpoints",
                "modelNotNeededCheckpoints","budgetDeferredCheckpoints","httpAttempts","http200","validOutputs","guardedOutputs","failedAttempts","unknownCheckpoints",
                "atomicCheckpoints","structuredModelAttempts","structuredModelCompleted","structuredModelFailed","structuredModelUnknown"))model.put(key,0);
        model.put("attemptsReserved",run.get("onDemandAttemptsReserved"));model.put("attemptBudget",run.get("onDemandAttemptBudget"));
        // Derive from the frozen run, so historical capped reports retain their original meaning.
        model.put("attemptsUnlimited",Boolean.TRUE.equals(run.get("onDemandAttemptsUnlimited")));
        model.put("consistencyGroups",0);model.put("consistencyModelRequestedGroups",0);model.put("consistencyHttpAttempts",0);
        model.put("consistencyUnknownRequestGroups",0);
        model.put("consistencyAttemptsReserved",run.getOrDefault("consistencyReviewAttemptsReserved",0));
        model.put("consistencyAttemptsUnlimited",Boolean.TRUE.equals(run.get("consistencyReviewAttemptsUnlimited")));
        retrieval.put("checkpointsWithRetrieval",0);retrieval.put("candidateCount",0);retrieval.put("followUpCheckpoints",0);
        int found=0;
        for(Finding finding:findings) {
            Map<String,Object> review=map(map(finding.assessment()).get("consistencyReview"));
            if(!review.isEmpty()) {
                increment(model,"consistencyGroups");
                if(Boolean.TRUE.equals(review.get("requestOutcomeUnknown")))increment(model,"consistencyUnknownRequestGroups");
                if(Boolean.TRUE.equals(review.get("modelRequested")))increment(model,"consistencyModelRequestedGroups");
                for(Object item:(map(review.get("model")).get("attempts") instanceof List<?> list?list:List.of())) {
                    Map<String,Object> attempt=map(item);increment(model,"httpAttempts");increment(model,"consistencyHttpAttempts");
                    if(attempt.get("httpStatus") instanceof Number n&&n.intValue()==200)increment(model,"http200");
                    switch(Objects.toString(attempt.get("state"),"")) {
                        case "VALID_OUTPUT" -> increment(model,"validOutputs");
                        case "GUARDED_OUTPUT" -> increment(model,"guardedOutputs");
                        default -> increment(model,"failedAttempts");
                    }
                }
            }
            boolean atomic=!map(map(finding.assessment()).get("atomicCheck")).isEmpty();
            if(!"SEMANTIC_RESPONSE".equals(finding.checkpoint().type())&&!atomic)continue;
            found++;
            Map<String,Object> audit=map(finding.assessment()),m=map(audit.get("model")),r=map(audit.get("retrieval"));
            if(atomic){
                increment(model,"atomicCheckpoints");Map<String,Map<String,Object>> attempts=new LinkedHashMap<>();
                if(m.get("engineAttempts") instanceof List<?> list)for(Object item:list){Map<String,Object> step=map(item);
                    String key=Objects.toString(step.get("attemptId"),Objects.toString(step.get("id"),""));if(!key.isBlank())attempts.put(key,step);}
                for(Map<String,Object> attempt:attempts.values()){
                    increment(model,"structuredModelAttempts");switch(Objects.toString(attempt.get("status"),"")){
                        case "COMPLETED" -> increment(model,"structuredModelCompleted");case "FAILED" -> increment(model,"structuredModelFailed");default -> increment(model,"structuredModelUnknown");
                    }
                }
            }
            if(Boolean.TRUE.equals(m.get("requested")))increment(model,"modelRequestedCheckpoints");
            switch(Objects.toString(m.get("decision"),"")) {
                case "RULE_DECIDED" -> increment(model,"ruleDecidedCheckpoints");
                case "EVIDENCE_INSUFFICIENT" -> increment(model,"evidenceInsufficientCheckpoints");
                case "MODEL_NOT_NEEDED" -> increment(model,"modelNotNeededCheckpoints");
                case "BUDGET_DEFERRED" -> increment(model,"budgetDeferredCheckpoints");
                case "MODEL_ESCALATED" -> { if(!Boolean.TRUE.equals(m.get("requested")))increment(model,"unknownCheckpoints"); }
                default -> increment(model,"unknownCheckpoints");
            }
            if(r.containsKey("mode")&&!"NOT_RUN".equals(r.get("mode")))increment(retrieval,"checkpointsWithRetrieval");
            if(audit.get("candidates") instanceof List<?> candidates)retrieval.put("candidateCount",(int)retrieval.get("candidateCount")+candidates.size());
            if(Boolean.TRUE.equals(map(r.get("followUp")).get("attempted")))increment(retrieval,"followUpCheckpoints");
            if(m.get("attempts") instanceof List<?> attempts)for(Object item:attempts) {
                Map<String,Object> attempt=map(item);increment(model,"httpAttempts");
                if(attempt.get("httpStatus") instanceof Number n&&n.intValue()==200)increment(model,"http200");
                switch(Objects.toString(attempt.get("state"),"")) {
                    case "VALID_OUTPUT" -> increment(model,"validOutputs");
                    case "GUARDED_OUTPUT" -> increment(model,"guardedOutputs");
                    default -> increment(model,"failedAttempts");
                }
            }
        }
        model.put("unknownCheckpoints",(int)model.get("unknownCheckpoints")+Math.max(0,expectedCount-found));
        model.put("boundary","HTTP尝试合计包含有HTTP记录的检查项及全文一致性定向复核；逐条件核验单独记录结构化调用尝试，其完成不等于HTTP200或有效结论。检查项数与一致性组数分列，调用预约可能因中断未发送。未请求全文生成式扫描。"
            +(Boolean.TRUE.equals(model.get("attemptsUnlimited"))?"本次不设模型调用次数上限，所有符合调用条件的检查项继续处理；单次请求超时、有限重试和来源校验仍生效。":""));
        retrieval.put("boundary","检索候选及补检仅用于相关证据定位，不代表全文语义覆盖。");
        return Map.of("onDemandModel",model,"retrievalEvidence",retrieval);
    }
    private static void increment(Map<String,Object> values,String key){values.put(key,(int)values.get(key)+1);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();}
}
