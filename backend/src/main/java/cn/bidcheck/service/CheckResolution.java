package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.RecoveryFailureRouting;
import java.util.*;
import static cn.bidcheck.service.BidService.*;

/** Recovery describes actual work; absence of evidence never proves absence of a document. */
public final class CheckResolution {
    public static final String VERSION="check-resolution-3-targeted-recomputation";
    public static final Set<String> ACTIVE=Set.of("QUEUED","RUNNING","RETRY_WAIT");
    private CheckResolution() {}
    public static Map<String,Object> map(Object value){return value instanceof Map<?,?> raw?stringMap(raw):Map.of();}
    private static Map<String,Object> stringMap(Map<?,?> raw){Map<String,Object> out=new LinkedHashMap<>();raw.forEach((k,v)->out.put(String.valueOf(k),v));return out;}
    public static List<Map<String,Object>> maps(Object value){return value instanceof Collection<?> all?all.stream().filter(Map.class::isInstance).map(CheckResolution::map).toList():List.of();}
    public static Map<String,Object> atomic(Finding f){return map(map(f.assessment()).get("atomicCheck"));}
    public static boolean unresolved(Finding f){
        if("UNDETERMINED".equals(f.evaluation().status()))return true;
        Map<String,Object> a=atomic(f);
        return !a.isEmpty()&&!Boolean.TRUE.equals(a.get("complete"))&&!maps(a.get("gaps")).isEmpty();
    }
    public static String jobId(String runId,String checkpointId){return stableId(runId+":resolution:"+checkpointId);}
    public static String outcomeFingerprint(Finding f){
        return hash(canonical(progressTokens(f)));
    }
    /** A retry description, generated rule/fact id or a different failure is never business progress. */
    public static Set<String> progressTokens(Finding f){
        SortedSet<String> tokens=new TreeSet<>();Map<String,Object> a=atomic(f);
        Map<String,Map<String,Object>> rules=new HashMap<>();indexRules(maps(map(a.get("plan")).get("rules")),rules);
        for(Map<String,Object> condition:maps(a.get("conditions"))){
            if(!Set.of("PASS","FAIL","NOT_APPLICABLE").contains(text(condition,"status"))
                ||!"REQUIREMENT".equals(text(condition,"conditionRole")))continue;
            Set<String> evidence=evidenceKeys(condition.get("evidences"));if(evidence.isEmpty())continue;
            Map<String,Object> rule=rules.getOrDefault(text(condition,"id"),condition);
            String obligation=text(map(rule.get("parameters")),"obligationIdentity");
            if(obligation.isBlank())obligation=hash(canonical(select(rule,List.of("predicate","subject","field","operator","expected","unit","sourceQuote","conditionRole"))));
            // A new citation to an already completed condition is source coverage, not another completed obligation.
            tokens.add("CONDITION:"+obligation+":"+text(condition,"status"));
            for(String source:evidence)tokens.add("SOURCE:"+source);
        }
        if(!unresolved(f)&&!evidenceKeys(f.evaluation().evidences()).isEmpty())tokens.add("CONCLUSION:"+f.evaluation().status());
        return Collections.unmodifiableSet(tokens);
    }
    public static Map<String,Object> progress(Finding before,Finding after){
        Set<String> added=new TreeSet<>(progressTokens(after));added.removeAll(progressTokens(before));
        Set<String> removed=new TreeSet<>(progressTokens(before));removed.removeAll(progressTokens(after));
        long conditions=added.stream().filter(v->v.startsWith("CONDITION:")).count();
        long sources=added.stream().filter(v->v.startsWith("SOURCE:")).count();
        // Losing a completed proof cannot unlock another strategy solely because the report changed.
        boolean progressed=added.stream().anyMatch(v->v.startsWith("CONDITION:")||v.startsWith("CONCLUSION:"))
            &&removed.stream().noneMatch(v->v.startsWith("CONDITION:")||v.startsWith("CONCLUSION:"));
        return fields("version",VERSION,"progressMade",progressed,"newVerifiedSourceCount",sources,"newCompletedConditionCount",conditions,
            "removedProofCount",removed.stream().filter(v->!v.startsWith("SOURCE:")).count(),"addedTokenCount",added.size());
    }
    public static String actionFingerprint(Finding finding,String policy,String documentHash,String strategy){
        // A changed executable rule or requested source window can justify work. Failure prose/ids cannot.
        return hash(policy+"|"+documentHash+"|"+finding.checkpoint().id()+"|"+strategy+"|"+
            canonical(progressTokens(finding).stream().filter(v->!v.startsWith("SOURCE:")).toList())+"|"+RecoveryInputs.inputKey(finding,strategy));
    }
    /** Retain current-version partial proofs when a failed request returns no replacement proof. */
    public static Finding protectCompletedProof(Finding before,Finding after){
        if(!"UNDETERMINED".equals(before.evaluation().status())||!"UNDETERMINED".equals(after.evaluation().status()))return after;
        Map<String,Object> change=progress(before,after),a=atomic(after);
        if(((Number)change.get("removedProofCount")).longValue()==0||((Number)change.get("newCompletedConditionCount")).longValue()>0)return after;
        boolean failed=retryable(after.assessment())||Set.of("UNRESOLVED","VALIDATION_FAILED","REPAIR_RETRY_PENDING").contains(text(map(a.get("compilationAudit")),"state"));
        if(!failed)return after;
        Map<String,Object> kept=new LinkedHashMap<>(map(before.assessment()));
        kept.put("recoveryFailure",fields("reason","补查请求失败，保留本批次此前完成的部分证明。","latestPlan",plan(after),
            "modelDiagnostics",a.getOrDefault("modelDiagnostics",Map.of()),"compilationAudit",a.getOrDefault("compilationAudit",Map.of()),"retainedCompletedProof",true));
        return new Finding(before.id(),before.runId(),before.checkpoint(),before.evaluation(),kept);
    }
    private static Map<String,Object> select(Map<String,Object> source,List<String> keys){Map<String,Object> result=new TreeMap<>();for(String key:keys)if(source.containsKey(key))result.put(key,source.get(key));return result;}
    private static void indexRules(List<Map<String,Object>> rules,Map<String,Map<String,Object>> out){for(Map<String,Object> rule:rules){out.put(text(rule,"id"),rule);indexRules(maps(rule.get("children")),out);}}
    private static Set<String> evidenceKeys(Object value){
        Set<String> keys=new TreeSet<>();if(!(value instanceof Collection<?> all))return keys;
        for(Object item:all){Map<String,Object> source;
            if(item instanceof Evidence e)source=fields("documentId",e.documentId(),"page",e.page(),"locationType",e.locationType(),"quote",e.quote());
            else source=select(map(item),List.of("documentId","page","locationType","quote"));
            if(text(source,"documentId").isBlank()||text(source,"quote").isBlank()||!(source.get("page") instanceof Number))continue;
            keys.add(hash(canonical(source)));
        }return keys;
    }
    private static String canonical(Object value){
        if(value instanceof Map<?,?> m){SortedMap<String,String> sorted=new TreeMap<>();m.forEach((k,v)->sorted.put(String.valueOf(k),canonical(v)));return sorted.toString();}
        if(value instanceof Collection<?> c)return c.stream().map(CheckResolution::canonical).toList().toString();
        return Objects.toString(value,"");
    }
    public static Map<String,Object> plan(Finding f){
        Map<String,Object> a=atomic(f),assessment=map(f.assessment());
        List<Map<String,Object>> gaps=maps(a.get("gaps"));
        Set<String> codes=new LinkedHashSet<>();gaps.forEach(g->codes.add(text(g,"code")));
        maps(assessment.get("adjustments")).forEach(g->codes.add(text(g,"code")));
        String strategy="BLOCKED",reason="现有证据尚未支持完整判断，需要进一步完善规则或证据对应。",next="保留已有证明，等待相关规则、证据或条件变化后继续。",status="BLOCKED_NO_PROGRESS";
        if(!unresolved(f))return fields("strategy","NONE","status","SUCCEEDED","reason","已形成结论","nextAction","查看判断依据");
        Map<String,Object> compilation=map(a.get("compilationAudit"));
        boolean invalidCompilation=Set.of("UNRESOLVED","VALIDATION_FAILED","REPAIR_RETRY_PENDING").contains(text(compilation,"state"))
            ||codes.stream().anyMatch(RecoveryFailureRouting::requirement);
        if(invalidCompilation){
            reason="条款范围或规则尚未完整解析，不能据此要求补交材料。";next="修复对应条款及规则，保留已经成立的独立证明。";
            // Retry only a declared repair node and only when compilation has not already exhausted its repair.
            // This route is local recomputation. It cannot issue another paid compile or scan the bid images.
            boolean localRepair=maps(a.get("recoveryActions")).stream().anyMatch(action->"REPAIR_REQUIREMENT".equals(text(action,"strategy"))
                &&!"CAPABILITY_UNRESOLVED".equals(text(action,"reasonCode")))
                &&(!(compilation.get("repairAttempts") instanceof Number n)||n.intValue()==0);
            if(localRepair){strategy="REPAIR_REQUIREMENT";status="QUEUED";reason="已定位未完成的条款或字段对应节点。";next="复用已读原文，仅修复对应规则并重新计算，不重复请求模型或原图。";}
            if("REPAIR_RETRY_PENDING".equals(text(compilation,"state"))||"REPAIR_RETRY_PENDING".equals(text(compilation,"cacheState"))||retryable(map(a.get("modelDiagnostics")))){
                strategy="BLOCKED";status="FAILED";reason="要求解析的服务请求暂时失败，已保存完成步骤。";next="服务恢复后继续失败的解析步骤，不重复核验投标原图。";
                if(!"REPAIR_RETRY_PENDING".equals(text(compilation,"state"))&&!"REPAIR_RETRY_PENDING".equals(text(compilation,"cacheState"))
                    &&maps(map(a.get("modelDiagnostics")).get("failures")).stream().noneMatch(failure->"COMPILE".equals(text(failure,"stage"))&&retryable(failure))){
                    reason="条款解析仍有未解决条件，部分证据处理请求也暂时失败。";
                    next="保留已完成证明，分别修复条款范围与失败的证据处理步骤。";
                }
            }
        }else if(codes.stream().anyMatch(RecoveryFailureRouting::correspondence)&&codes.stream().noneMatch(c->c.startsWith("VISUAL_"))){
            strategy="TARGETED_EVIDENCE";status="QUEUED";reason="已定位证书或查询记录的字段对应缺口。";next="仅补充对应主体、记录和字段证据；没有独立原图缺口时不重读图片。";
        }else if(!maps(a.get("visualRequests")).isEmpty()){
            strategy="VERIFY_SOURCE_PAGES";status="QUEUED";reason="已定位需要观察原图的具体位置。";next="核验对应页面，再计算受影响的条件。";
        }else if("WAIT_STAGE".equals(text(a,"workflowState"))){
            status="WAITING_EXTERNAL";reason="本项尚缺外部核验的适用阶段或参考时点。";next="明确条款要求的阶段和时点后，再查询指定来源。";
        }else if(maps(a.get("externalRequests")).stream().anyMatch(CheckResolution::executableExternal)){
            strategy="QUERY_EXTERNAL";status="QUEUED";reason="已形成主体、来源类型、阶段和时点明确的外部查询。";next="查询管理员配置的固定来源，将有来源记录作为事实后重新计算本项。";
        }else if(!maps(a.get("externalRequests")).isEmpty()){
            status="WAITING_EXTERNAL";reason="外部核验请求尚缺具体主体、来源类型、适用阶段或参考时点。";next="补齐查询范围后，再访问管理员配置的固定来源。";
        }else if(maps(a.get("recoveryActions")).stream().anyMatch(v->"SPLIT_FAILED_REQUEST".equals(text(v,"strategy")))){
            strategy="SPLIT_FAILED_REQUEST";status="QUEUED";reason="部分请求输出过长或未形成有效结构。";next="按独立义务拆分请求，保留各义务的完整来源。";
        }else if(retryable(assessment)){
            strategy="RETRY_FAILED_STEP";status="QUEUED";reason="处理请求暂时失败，已完成证明保留。";next="恢复失败步骤，复用已完成证据。";
        }else if(codes.stream().anyMatch(c->Set.of("USER_INPUT_REQUIRED","REFERENCE_TIME_REQUIRED").contains(c))){
            status="WAITING_USER";reason="需要明确指定的适用条件或时间基准。";next=gaps.stream().map(g->text(g,"requiredInput")).filter(v->!v.isBlank()).findFirst().orElse("补充本项列明的信息后继续。");
        }else if("UNDETERMINED".equals(f.evaluation().status())){
            // A distinct missing-field strategy may be executed once, with prior gaps supplied to the engine.
            boolean actionable=maps(a.get("recoveryActions")).stream().anyMatch(v->Set.of("TARGETED_EVIDENCE","RETRIEVE_EVIDENCE","RESOLVE_SUBJECT").contains(text(v,"strategy")));
            if(actionable){strategy="TARGETED_EVIDENCE";status="QUEUED";reason="需要按已识别缺口进一步定位证据。";next="仅检索未完成条件所需字段和对应文书。";}
        }
        Map<String,Object> result=fields("strategy",strategy,"status",status,"reason",reason,"nextAction",next,"reasonCodes",List.copyOf(codes));
        if(compilation.get("retryNotBeforeEpochMillis") instanceof Number n)result.put("retryNotBeforeEpochMillis",n.longValue());
        return result;
    }
    private static boolean executableExternal(Map<String,Object> request){
        return !text(request,"type").isBlank()&&!text(request,"subject").isBlank()
            &&!text(request,"evaluationStage").isBlank()&&!text(request,"referenceDate").isBlank();
    }
    private static boolean retryable(Object value){
        if(value instanceof Map<?,?> m){
            if(Boolean.TRUE.equals(m.get("recoveredByRetry"))||Boolean.TRUE.equals(m.get("transportRecovered")))return false;
            if(Boolean.TRUE.equals(m.get("retryable")))return true;
            String code=Objects.toString(m.get("errorCode"),Objects.toString(m.get("code"),""));
            if(Set.of("MODEL_TIMEOUT","MODEL_UNAVAILABLE","HTTP_429","HTTP_502","HTTP_503","HTTP_504","MODEL_TRANSPORT_ERROR","MODEL_RATE_LIMITED").contains(code))return true;
            return m.entrySet().stream().filter(e->!Set.of("resolutionJob","businessPresentation").contains(String.valueOf(e.getKey()))).anyMatch(e->retryable(e.getValue()));
        }
        return value instanceof Collection<?> c&&c.stream().anyMatch(CheckResolution::retryable);
    }
    public static Finding attach(Finding f,Map<String,Object> job){
        if(job==null||job.isEmpty())return f;
        Map<String,Object> assessment=new LinkedHashMap<>(map(f.assessment()));assessment.put("resolutionJob",job);
        return new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),FocusedCheckEvaluator.reconcileAssessment(f.evaluation(),assessment));
    }
    public static Map<String,Object> summary(Map<String,Object> run,List<Finding> findings,List<Map<String,Object>> jobs,String currentPolicy){
        return summary(run,findings,jobs,currentPolicy,0);
    }
    public static Map<String,Object> summary(Map<String,Object> run,List<Finding> findings,List<Map<String,Object>> jobs,String currentPolicy,int missingRecords){
        String state=switch(text(run,"status")){case "PENDING"->"QUEUED";case "RUNNING"->"RUNNING";case "FAILED"->"FAILED";default->"FINISHED";};
        long unresolved=findings.stream().filter(CheckResolution::unresolved).count()+missingRecords;
        long active=Set.of("QUEUED","RUNNING").contains(state)?jobs.stream().filter(j->ACTIVE.contains(text(j,"status"))).count():0;
        boolean changed=!currentPolicy.equals(text(run,"resolutionPolicyIdentity"));
        boolean canRecover=unresolved>0&&!Set.of("QUEUED","RUNNING").contains(state)
            &&(changed||"FAILED".equals(state)||missingRecords>0||jobs.isEmpty()||jobs.stream().anyMatch(j->"FAILED".equals(text(j,"status"))
                &&(!(j.get("retryNotBeforeEpochMillis") instanceof Number time)||time.longValue()<=System.currentTimeMillis())));
        return fields("version",VERSION,"executionState",state,"finished",!Set.of("QUEUED","RUNNING").contains(state),
            "originalCount",findings.size()+missingRecords,"missingOriginalCount",missingRecords,"unresolvedOriginalCount",unresolved,"recoverableCount",canRecover?unresolved:active,
            "activeJobCount",active,"blockedCount",jobs.stream().filter(j->!ACTIVE.contains(text(j,"status"))&&!"SUCCEEDED".equals(text(j,"status"))).count(),
            "canRecover",canRecover,"blockReason",canRecover?"":unresolved==0?"当前没有待补查事项。":"本轮已尝试可用策略；规则、证据或外部条件变化后可继续。",
            "parentRunId",text(run,"parentRunId"),"countScope","ORIGINAL_RECORDS_NOT_BUSINESS_GROUPS");
    }
}
