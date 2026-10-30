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
                "atomicCheckpoints","structuredModelAttempts","structuredModelCompleted","structuredModelFailed","structuredModelUnknown",
                "agentInvestigations","agentFailedInvestigations","agentModelCalls","agentToolCalls","agentInputTokens","agentOutputTokens",
                "agentContextCompactions","agentEvidenceReuseCheckpoints"))model.put(key,0);
        model.put("attemptsReserved",run.get("onDemandAttemptsReserved"));model.put("attemptBudget",run.get("onDemandAttemptBudget"));
        // Derive from the frozen run, so historical capped reports retain their original meaning.
        model.put("attemptsUnlimited",Boolean.TRUE.equals(run.get("onDemandAttemptsUnlimited")));
        model.put("consistencyGroups",0);model.put("consistencyModelRequestedGroups",0);model.put("consistencyHttpAttempts",0);
        model.put("consistencyUnknownRequestGroups",0);
        model.put("consistencyAttemptsReserved",run.getOrDefault("consistencyReviewAttemptsReserved",0));
        model.put("consistencyAttemptsUnlimited",Boolean.TRUE.equals(run.get("consistencyReviewAttemptsUnlimited")));
        retrieval.put("checkpointsWithRetrieval",0);retrieval.put("candidateCount",0);retrieval.put("followUpCheckpoints",0);
        for(String key:List.of("searchAttempts","vectorSearches","lexicalOnlySearches","degradedSearches","unknownSearches","embeddingCalls","rerankCalls",
            "mappedSourceCount","unmappedHitCount","selectedRetrievedSourceCount","unknownSourceMappingSearches","unknownSelectionRounds"))retrieval.put(key,0);
        retrieval.put("modeCounts",new TreeMap<String,Integer>());retrieval.put("failureCounts",new TreeMap<String,Integer>());
        int found=0;Set<String> seenAgentAttempts=new LinkedHashSet<>();
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
            Map<String,Object> audit=map(finding.assessment());
            boolean atomic=!map(audit.get("atomicCheck")).isEmpty();
            boolean deterministicDelegation="DETERMINISTIC_FULL_TEXT_REVIEW".equals(Objects.toString(map(audit.get("agentDelegation")).get("executor"),""));
            if(!"SEMANTIC_RESPONSE".equals(finding.checkpoint().type())&&!atomic&&!deterministicDelegation)continue;
            found++;
            Map<String,Object> m=map(audit.get("model")),r=map(audit.get("retrieval"));
            if(deterministicDelegation){
                increment(model,"modelNotNeededCheckpoints");
                continue;
            }
            if(atomic){
                increment(model,"atomicCheckpoints");Map<String,Map<String,Object>> attempts=new LinkedHashMap<>();
                Map<String,Object> atomicCheck=map(audit.get("atomicCheck")),agent=map(atomicCheck.get("agentInvestigation"));
                if(!map(atomicCheck.get("agentEvidenceReuse")).isEmpty())increment(model,"agentEvidenceReuseCheckpoints");
                List<Map<String,Object>> durableAgentAttempts=maps(m.get("agentAttempts"));
                if(durableAgentAttempts.isEmpty())durableAgentAttempts=maps(atomicCheck.get("agentAttempts"));
                if(durableAgentAttempts.isEmpty()&&!agent.isEmpty())durableAgentAttempts=List.of(agent);
                for(Map<String,Object> agentAttempt:durableAgentAttempts){
                    String key=Objects.toString(agentAttempt.get("auditKey"),"");if(key.isBlank())key="request:"+Objects.toString(agentAttempt.get("requestId"),"");
                    if(key.equals("request:")||key.isBlank())key=finding.id()+":"+Objects.toString(agentAttempt.get("stepId"),"legacy-agent");
                    if(!seenAgentAttempts.add(key))continue;
                    increment(model,"agentInvestigations");if("FAILED".equals(agentAttempt.get("status")))increment(model,"agentFailedInvestigations");
                    add(model,"agentModelCalls",number(agentAttempt.get("modelCalls")));add(model,"agentToolCalls",number(agentAttempt.get("toolCalls")));
                    add(model,"agentInputTokens",number(agentAttempt.get("inputTokens")));add(model,"agentOutputTokens",number(agentAttempt.get("outputTokens")));
                    add(model,"agentContextCompactions",number(agentAttempt.get("contextCompactions")));
                }
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
                case "AGENT_SERVICE_FAILED" -> { /* Explicit service failure before any model request. */ }
                case "MODEL_ESCALATED" -> { if(!Boolean.TRUE.equals(m.get("requested")))increment(model,"unknownCheckpoints"); }
                default -> increment(model,"unknownCheckpoints");
            }
            if(atomic)atomicRetrieval(retrieval,audit);
            else {
                if(r.containsKey("mode")&&!"NOT_RUN".equals(r.get("mode")))increment(retrieval,"checkpointsWithRetrieval");
                if(audit.get("candidates") instanceof List<?> candidates)add(retrieval,"candidateCount",candidates.size());
                if(Boolean.TRUE.equals(map(r.get("followUp")).get("attempted")))increment(retrieval,"followUpCheckpoints");
            }
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
        model.put("boundary","HTTP尝试合计包含有HTTP记录的检查项及全文一致性定向复核；Agent调查、模型、工具和Token从追加式调查回执按requestId去重聚合，本地重算与等价事项扇出不会覆盖或重复计算首次调用。逐条件核验和Agent模型授权请求分别统计，均不计作HTTP200或有效结论。Agent服务失败不等于模型调用失败次数。检查项数与一致性组数分列，调用预约可能因中断未发送。未请求全文生成式扫描。"
            +(Boolean.TRUE.equals(model.get("attemptsUnlimited"))?"本次不设模型调用次数上限，所有符合调用条件的检查项继续处理；单次请求超时、有限重试和来源校验仍生效。":""));
        retrieval.put("knownCandidateCount",retrieval.get("candidateCount"));
        retrieval.put("knownVectorSearches",retrieval.get("vectorSearches"));
        boolean complete=(int)retrieval.get("unknownSearches")==0;retrieval.put("telemetryComplete",complete);
        // Old reports discarded atomic retrieval outputs. Missing measurements are unknown, never zero.
        if(!complete){retrieval.put("candidateCount",null);retrieval.put("vectorSearches",null);}
        boolean mappingComplete=(int)retrieval.get("unknownSourceMappingSearches")==0;
        boolean selectionComplete=(int)retrieval.get("unknownSelectionRounds")==0;
        retrieval.put("sourceMappingTelemetryComplete",mappingComplete);retrieval.put("selectionTelemetryComplete",selectionComplete);
        if(!mappingComplete){retrieval.put("mappedSourceCount",null);retrieval.put("unmappedHitCount",null);}
        if(!selectionComplete)retrieval.put("selectedRetrievedSourceCount",null);
        retrieval.put("boundary","统计招标检查项的实际检索步骤；候选数为历次返回条数，含重复召回，不代表唯一证据数或全文覆盖。向量参与仅以搜索回执为准，索引就绪不算向量搜索；历史回执缺失时数量标为未知。");
        return Map.of("onDemandModel",model,"retrievalEvidence",retrieval);
    }
    private static void atomicRetrieval(Map<String,Object> total,Map<String,Object> audit){
        boolean searched=false,followUp=false;Set<String> seen=new HashSet<>(),retrievalRounds=new HashSet<>(),selectionRounds=new HashSet<>();
        for(Object item:audit.get("structuredSteps") instanceof List<?> list?list:List.of()){
            Map<String,Object> step=map(item);String type=Objects.toString(step.get("type"),"");
            if(!type.equals("RETRIEVE")&&!type.startsWith("RETRIEVE_")&&!type.startsWith("RETRIEVAL_SELECTION_"))continue;
            String id=Objects.toString(step.get("executionId"),Objects.toString(step.get("id"),""));if(!id.isBlank()&&!seen.add(id))continue;
            if(type.startsWith("RETRIEVAL_SELECTION_")){
                Map<String,Object> selection=map(step.get("selection"));
                if(selection.get("selectedRetrievedSourceCount") instanceof Number){
                    add(total,"selectedRetrievedSourceCount",number(selection.get("selectedRetrievedSourceCount")));
                    selectionRounds.add(type.substring("RETRIEVAL_SELECTION_".length()));
                }
                continue;
            }
            searched=true;increment(total,"searchAttempts");Map<String,Object> receipt=map(step.get("retrieval")),diagnostics=map(receipt.get("diagnostics"));
            retrievalRounds.add(type.matches("RETRIEVE_[0-9]+_.*")?type.split("_",3)[1]:Objects.toString(receipt.get("round"),"legacy"));
            if(!(receipt.get("mappedSourceCount") instanceof Number)||!(receipt.get("unmappedHitCount") instanceof Number))increment(total,"unknownSourceMappingSearches");
            String mode=Objects.toString(receipt.get("mode"),"");followUp|=type.matches("RETRIEVE_[1-9][0-9]*_.*")||number(receipt.get("round"))>0;
            if(mode.isBlank()){increment(total,"unknownSearches");continue;}
            count(total,"modeCounts",mode);String failure=Objects.toString(diagnostics.get("failureCode"),"");if(!failure.isBlank())count(total,"failureCounts",failure);
            boolean vector=Boolean.TRUE.equals(diagnostics.get("vectorSearchExecuted"))||(!diagnostics.containsKey("vectorSearchExecuted")&&mode.startsWith("HYBRID_"));
            if(vector)increment(total,"vectorSearches");else increment(total,"lexicalOnlySearches");
            if(mode.contains("DEGRADED"))increment(total,"degradedSearches");
            add(total,"candidateCount",number(receipt.getOrDefault("returnedHits",diagnostics.get("returned"))));
            for(String key:List.of("embeddingCalls","rerankCalls"))add(total,key,number(diagnostics.get(key)));
            for(String key:List.of("mappedSourceCount","unmappedHitCount"))add(total,key,number(receipt.get(key)));
        }
        retrievalRounds.removeAll(selectionRounds);add(total,"unknownSelectionRounds",retrievalRounds.size());
        if(searched)increment(total,"checkpointsWithRetrieval");if(followUp)increment(total,"followUpCheckpoints");
    }
    private static int number(Object value){return value instanceof Number n?Math.max(0,n.intValue()):0;}
    private static void add(Map<String,Object> values,String key,int n){values.put(key,(int)values.get(key)+n);}
    @SuppressWarnings("unchecked") private static void count(Map<String,Object> total,String key,String value){((Map<String,Integer>)total.get(key)).merge(value,1,Integer::sum);}
    private static void increment(Map<String,Object> values,String key){values.put(key,(int)values.get(key)+1);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return value instanceof Collection<?> rows?rows.stream().filter(Map.class::isInstance).map(row->(Map<String,Object>)row).toList():List.of();}
}
