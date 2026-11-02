package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.RecoveryFailureRouting;
import java.util.*;
import static cn.bidcheck.service.BidService.*;

/** Recovery describes actual work; absence of evidence never proves absence of a document. */
public final class CheckResolution {
    public static final String VERSION="check-resolution-15-scoped-agent-visual-first";
    public static final Set<String> ACTIVE=Set.of("QUEUED","RUNNING","RETRY_WAIT");
    private CheckResolution() {}
    public static Map<String,Object> map(Object value){return value instanceof Map<?,?> raw?stringMap(raw):Map.of();}
    private static Map<String,Object> stringMap(Map<?,?> raw){Map<String,Object> out=new LinkedHashMap<>();raw.forEach((k,v)->out.put(String.valueOf(k),v));return out;}
    public static List<Map<String,Object>> maps(Object value){return value instanceof Collection<?> all?all.stream().filter(Map.class::isInstance).map(CheckResolution::map).toList():List.of();}
    public static Map<String,Object> atomic(Finding f){return map(map(f.assessment()).get("atomicCheck"));}
    /** A stopped investigation with unprocessed targets remains a system failure, not exhausted evidence. */
    static boolean agentStalledWithRemainingTargets(Finding finding){
        Map<String,Object> a=atomic(finding);
        return "AGENT_STALLED_WITH_REMAINING_TARGETS".equals(text(map(a.get("agentInvestigation")),"errorCode"))
            ||maps(a.get("gaps")).stream().anyMatch(gap->"AGENT_INVESTIGATION_STALLED".equals(text(gap,"code")));
    }
    public static boolean unresolved(Finding f){
        if(Set.of("UNDETERMINED","NEEDS_CONFIRMATION").contains(f.evaluation().status()))return true;
        if("NOT_APPLICABLE".equals(f.evaluation().status()))return false;
        // An ALL tree can already fail while other required conditions remain unresolved.
        // A final status alone must not clear those gaps; complete marks the proof boundary,
        // including a satisfied ANY branch whose inactive alternatives need no more work.
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
    /** An earlier failed attempt must not suppress newly published evidence for the same rule. */
    public static String actionFingerprint(Finding finding,String policy,String documentHash,String strategy,Map<String,Object> current){
        String base=actionFingerprint(finding,policy,documentHash,strategy),dependencies=RecoveryInputs.dependencyKey(current);
        return dependencies.isBlank()?base:hash(base+"|dependencies:"+dependencies);
    }
    /** A transport failure may retain same-input proof; a fresh validator can always revoke it. */
    public static Finding protectCompletedProof(Finding before,Finding after){
        if(!"UNDETERMINED".equals(before.evaluation().status())||!"UNDETERMINED".equals(after.evaluation().status()))return after;
        Map<String,Object> change=progress(before,after),a=atomic(after);
        if(((Number)change.get("removedProofCount")).longValue()==0||((Number)change.get("newCompletedConditionCount")).longValue()>0)return after;
        boolean transport=explicitTransportFailure(map(a.get("modelDiagnostics")));
        boolean sameInputs=sameProofInputs(before,after),revoked=proofRevoked(before,after);
        if(!transport||!sameInputs||revoked)return archivePriorProof(before,after,!transport?"NO_EXPLICIT_TRANSPORT_FAILURE":!sameInputs?"SOURCE_OR_VALIDATION_REVISION_NOT_IDENTICAL":"CURRENT_VALIDATOR_REVOKED_PROOF");
        Map<String,Object> kept=new LinkedHashMap<>(map(before.assessment()));
        kept.put("recoveryFailure",fields("reason","同一冻结来源、规则和校验版本的补查传输暂时失败；本轮未撤销此前证明，暂保留已完成部分。",
            "processingFailed",true,"kind","PROCESSING_FAILURE","latestPlan",plan(after),"sameInputReceipt",map(a.get("recoveryDependencies")),
            "modelDiagnostics",a.getOrDefault("modelDiagnostics",Map.of()),"compilationAudit",a.getOrDefault("compilationAudit",Map.of()),"retainedCompletedProof",true));
        return new Finding(before.id(),before.runId(),before.checkpoint(),before.evaluation(),kept);
    }
    private static boolean sameProofInputs(Finding before,Finding after){
        if(!before.runId().equals(after.runId())||!before.checkpoint().equals(after.checkpoint()))return false;
        Map<String,Object> old=map(atomic(before).get("recoveryDependencies")),current=map(atomic(after).get("recoveryDependencies"));
        for(String key:List.of("version","sourceRevision","validationIdentity","planRevision","inputRevision"))
            if(text(old,key).isBlank()||!text(old,key).equals(text(current,key)))return false;
        return true;
    }
    private static boolean proofRevoked(Finding before,Finding after){
        Map<String,Object> current=atomic(after);
        if(!maps(current.get("rejectedFacts")).isEmpty()||maps(current.get("fieldCoverage")).stream().anyMatch(row->"FIELD_VALIDATION_FAILED".equals(text(row,"errorCode"))||"FIELD_VALIDATION_FAILED".equals(text(row,"reason"))))return true;
        Set<String> proved=new HashSet<>();for(Map<String,Object> condition:maps(atomic(before).get("conditions")))
            if(Set.of("PASS","FAIL","NOT_APPLICABLE").contains(text(condition,"status"))&&!evidenceKeys(condition.get("evidences")).isEmpty())proved.add(text(condition,"id"));
        List<Map<String,Object>> observed=new ArrayList<>(maps(current.get("conditions")));observed.addAll(maps(current.get("obligations")));
        // An explicit new unknown/contradiction is a revalidation outcome, even if another request timed out.
        return observed.stream().anyMatch(condition->proved.contains(text(condition,"id"))&&!Set.of("PASS","FAIL","NOT_APPLICABLE").contains(text(condition,"status")));
    }
    private static boolean explicitTransportFailure(Map<String,Object> diagnostics){
        if(Boolean.TRUE.equals(diagnostics.get("recoveredByRetry"))||Boolean.TRUE.equals(diagnostics.get("transportRecovered")))return false;
        if(diagnostics.get("unresolvedFailures") instanceof Number count&&count.longValue()==0)return false;
        String code=text(diagnostics,"errorCode");
        if(Set.of("MODEL_TIMEOUT","REQUEST_TIMEOUT","CAPACITY_TIMEOUT","TRANSPORT_FAILURE","MODEL_REQUEST_TIMEOUT","MODEL_CAPACITY_TIMEOUT","MODEL_TRANSPORT_FAILURE").contains(code)||code.matches("(?:MODEL_)?HTTP_(?:408|429|500|502|503|504)"))return true;
        return maps(diagnostics.get("failures")).stream().anyMatch(CheckResolution::explicitTransportFailure);
    }
    private static Finding archivePriorProof(Finding before,Finding after,String reason){
        Map<String,Object> assessment=new LinkedHashMap<>(map(after.assessment()));
        List<Map<String,Object>> historical=maps(atomic(before).get("conditions")).stream().filter(condition->Set.of("PASS","FAIL","NOT_APPLICABLE").contains(text(condition,"status"))&&!evidenceKeys(condition.get("evidences")).isEmpty()).toList();
        assessment.put("supersededProof",fields("reasonCode",reason,"previousConditions",historical,"previousInputReceipt",map(atomic(before).get("recoveryDependencies")),"activeBusinessProof",false,"retainedCompletedProof",false));
        return new Finding(after.id(),after.runId(),after.checkpoint(),after.evaluation(),assessment);
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
        Map<String,Object> retained=map(assessment.get("recoveryFailure"));
        if(unresolved(f)&&Boolean.TRUE.equals(retained.get("retainedCompletedProof"))&&retained.get("processingFailed") instanceof Boolean
            &&!map(retained.get("latestPlan")).isEmpty())return new LinkedHashMap<>(map(retained.get("latestPlan")));
        List<Map<String,Object>> gaps=maps(a.get("gaps"));
        Set<String> codes=new LinkedHashSet<>();gaps.forEach(g->codes.add(text(g,"code")));
        maps(assessment.get("adjustments")).forEach(g->codes.add(text(g,"code")));
        maps(assessment.get("externalQueryDiagnostics")).forEach(g->codes.add(text(g,"code")));
        Map<String,Object> externalState=ExternalCheckState.describe(assessment,Map.of());
        String strategy="BLOCKED",reason="现有证据尚未支持完整判断，需要进一步完善规则或证据对应。",next="保留已有证明，等待相关规则、证据或条件变化后继续。",status="BLOCKED_NO_PROGRESS";
        if(!unresolved(f))return fields("strategy","NONE","status","SUCCEEDED","reason","已形成结论","nextAction","查看判断依据");
        Map<String,Object> compilation=map(a.get("compilationAudit"));
        TrustedVisualReview.Result visualReview=TrustedVisualReview.inspect(assessment);
        List<Map<String,Object>> pendingVisual=TrustedVisualReview.pendingRequests(a,visualReview.observations());
        boolean invalidCompilation=Set.of("UNRESOLVED","VALIDATION_FAILED","REPAIR_RETRY_PENDING").contains(text(compilation,"state"))
            ||codes.stream().anyMatch(RecoveryFailureRouting::requirement);
        if(!invalidCompilation&&"NEEDS_CONFIRMATION".equals(f.evaluation().status())&&codes.equals(Set.of("INFERENCE_CONFIRMATION_REQUIRED")))
            return fields("strategy","WAIT_INFERENCE_CONFIRMATION","status","WAITING_USER","reason","已有带原文引用的推断需要确认，尚未构成业务通过。","nextAction","确认或否定下方推断后重算；相同证据不再自动重复调查。");
        if(Set.of("NOT_CONFIGURED","CONFIGURATION_INVALID","SCOPE_INCOMPLETE").contains(text(externalState,"state"))){
            strategy="SCOPE_INCOMPLETE".equals(text(externalState,"state"))?"REPAIR_EXTERNAL_SCOPE":"RESTORE_EXTERNAL_CONNECTION";status="FAILED";reason=text(externalState,"label");next=text(externalState,"nextStep");
        }else if(codes.stream().anyMatch(ExternalCheckState::systemFailure)){
            boolean executable=maps(a.get("externalRequests")).stream().anyMatch(CheckResolution::executableExternal);
            strategy=executable?"QUERY_EXTERNAL":"BLOCKED";status=executable?"QUEUED":"FAILED";
            reason="指定外部查询发生系统连接或执行失败，现有材料不能据此判为缺失或不合格。";
            next=executable?"重试同一外部查询并复用已完成文字证据；查询结果返回后只重算相关条件。":"修复外部连接和查询范围后重试，当前不要求用户补交投标材料。";
        }else if(!invalidCompilation&&!pendingVisual.isEmpty()&&(codes.isEmpty()
                ||codes.stream().anyMatch(code->code.startsWith("VISUAL_"))||agentStalledWithRemainingTargets(f))){
            strategy="VERIFY_SOURCE_PAGES";status="QUEUED";
            reason="自动查证已定位需要观察原图的具体位置。";
            next="先执行对应页面的原图核验，观察结果写入后只重算受影响条件。";
        }else if(agentStalledWithRemainingTargets(f)){
            strategy="WAIT_AGENT_INPUT_CHANGE";status="FAILED";
            reason="自动查证在重复无收益路径上停止，仍有目标尚未处理；这属于系统未完成，不能认定相关材料缺失。";
            next="保留已完成证明与未处理目标；新增相关证据后先本地重算受影响条件，再按变化后的输入继续查证。";
        }else if(codes.contains("AGENT_INVESTIGATION_FAILED")||"FAILED".equals(text(map(a.get("agentInvestigation")),"status"))){
            strategy="RETRY_FAILED_STEP";status="QUEUED";
            reason="自动查证服务未完成，已校验证据保留；不能据此认定投标材料缺失。";
            next="恢复服务后继续同一自动查证流程，复用已保存证据并重新计算相关条件；当前不要求补交材料。";
        }else if(codes.contains("MISSING_TENDER_ATTACHMENT")&&!invalidCompilation){
            strategy="WAIT_TENDER_ATTACHMENT";status="WAITING_USER";
            reason="招标正文引用的技术规范书附件尚未上传，系统无法确定该附件要求的完整文件清单。";
            next="补充招标文件引用的《技术规范书》及其附件；这是招标依据缺口，不表示投标文件缺材料。";
        }else if(codes.contains("CAPABILITY_CRITERIA_REQUIRED")&&!invalidCompilation){
            strategy="WAIT_REQUIREMENT_CRITERIA";status="WAITING_USER";
            reason="原文要求具备履约能力，但没有给出具体指标或认可的证明方式，营业执照不能代替能力证明。";
            next="依据招标澄清明确核验标准及证明方式后重算；保留已核登记证照，不重复提取相同文件。";
        }else if(codes.stream().anyMatch(c->c.startsWith("REQUIREMENT_INVENTORY_"))){
            strategy="WAIT_REQUIREMENT_DEPENDENCIES";status="BLOCKED_NO_PROGRESS";
            reason="招标要求清单或其子事项仍有未完成范围，不能用部分材料证明全部要求。";
            next="补全来源清单并等待子事项结果更新后，程序重新汇总；不重复扫描全文，也不要求用户逐项人工核对。";
        }else if(codes.contains("COST_APPLICABILITY_REQUIRED")&&!invalidCompilation){
            strategy="WAIT_APPLICABILITY";status="WAITING_USER";
            reason="成本核算存在原文允许的免核分支，但前附表尚未明确是否适用。";
            next="确认招标前附表的成本免核约定或取得对应澄清后，再与现场成本核算记录合并判断。";
        }else if(invalidCompilation){
            reason="条款范围或规则尚未完整解析，不能据此要求补交材料。";next="修复对应条款及规则，保留已经成立的独立证明。";
            // Repair is deterministic re-compilation, never replay of the failed tree. Durable
            // input fingerprints and the dependency gate prevent repeating unchanged work.
            boolean restore=maps(a.get("recoveryActions")).stream().anyMatch(action->"RESTORE_REQUIREMENT_SOURCE".equals(text(action,"strategy")))
                ||"SCOPE_UNRESOLVED".equals(text(map(a.get("plan")),"method"));
            boolean localRepair=maps(a.get("recoveryActions")).stream().anyMatch(action->cn.bidcheck.verification.engine.RequirementRepairPolicy.requirementWork(text(action,"strategy")));
            if(restore){strategy="RESTORE_REQUIREMENT_SOURCE";status="QUEUED";reason="检查项的要求来源尚未唯一绑定。";next="从冻结招标原文恢复明确来源与续段，再生成并核验完整规则。";}
            else if(localRepair){strategy="REPAIR_REQUIREMENT";status="QUEUED";reason="已定位未完成的条款或字段对应节点。";next="按当前来源与规则版本重新编译失败条件，保留独立证明；无版本或输入变化时停止重复补查。";}
            if("REPAIR_RETRY_PENDING".equals(text(compilation,"state"))||"REPAIR_RETRY_PENDING".equals(text(compilation,"cacheState"))||retryable(map(a.get("modelDiagnostics")))){
                strategy="BLOCKED";status="FAILED";reason="要求解析的服务请求暂时失败，已保存完成步骤。";next="服务恢复后继续失败的解析步骤，不重复核验投标原图。";
                if(!"REPAIR_RETRY_PENDING".equals(text(compilation,"state"))&&!"REPAIR_RETRY_PENDING".equals(text(compilation,"cacheState"))
                    &&maps(map(a.get("modelDiagnostics")).get("failures")).stream().noneMatch(failure->"COMPILE".equals(text(failure,"stage"))&&retryable(failure))){
                    reason="条款解析仍有未解决条件，部分证据处理请求也暂时失败。";
                    next="保留已完成证明，分别修复条款范围与失败的证据处理步骤。";
                }
            }
        }else if(!codes.isEmpty()&&codes.stream().allMatch(c->Set.of("QUALITY_GRADE_REVIEW_REQUIRED","SCORE_SOURCE_LIST_AMBIGUOUS").contains(c))){
            status="WAITING_USER";
            boolean sourceAmbiguous=codes.contains("SCORE_SOURCE_LIST_AMBIGUOUS");
            reason=sourceAmbiguous?"招标原文的评分清单存在重复或数量歧义，系统不能自行改写要求。":"评分所需的结构和正文已核验，优劣档次需要专业评审。";
            next=sourceAmbiguous?"请依据招标澄清确认清单口径，确认前保留各项原文和已核验结果。":"对照已定位正文及原文评分档次进行专业评审，不重复发起原文提取。";
        }else if(visualReview.reviewed()&&RecoveryTransition.hasProcessingFailure(f)){
            strategy="RETRY_FAILED_STEP";status="QUEUED";reason="原图观察已保存，其他处理步骤仍有失败。";next="先恢复失败步骤，保留已核验原图，不把系统失败交给用户确认。";
        }else if(codes.stream().anyMatch(RecoveryFailureRouting::correspondence)&&codes.stream().noneMatch(c->c.startsWith("VISUAL_"))){
            strategy="TARGETED_EVIDENCE";status="QUEUED";reason="已定位证书或查询记录的字段对应缺口。";next="仅补充对应主体、记录和字段证据；没有独立原图缺口时不重读图片。";
        }else if(visualReview.unreadableOnly()&&!RecoveryTransition.hasProcessingFailure(f)){
            status="WAITING_USER";reason="指定原图已检查，剩余签章无法辨清。";next="请确认所列签署位置，或提供该页清晰图像；相同页面不再自动重复识别。";
        }else if("WAIT_STAGE".equals(text(a,"workflowState"))){
            strategy="WAIT_STAGE";status="WAITING_EXTERNAL";reason="本项需在"+ExternalCheckState.stageLabel(text(externalState,"requiredStage"))+"核验，当前阶段不作结论。";next=text(externalState,"nextStep");
        }else if(Set.of("WAIT_STAGE","WAIT_OWNER_DATA","NOT_FOUND","INCONCLUSIVE").contains(text(externalState,"state"))){
            strategy="WAIT_STAGE".equals(text(externalState,"state"))?"WAIT_STAGE":"WAIT_EXTERNAL_RECORD";status="WAITING_EXTERNAL";
            reason=text(externalState,"label");next=text(externalState,"nextStep");
        }else if(maps(a.get("externalRequests")).stream().anyMatch(CheckResolution::executableExternal)){
            strategy="QUERY_EXTERNAL";status="QUEUED";reason="已形成主体、来源类型、阶段和时点明确的外部查询。";next="查询管理员配置的固定来源，将有来源记录作为事实后重新计算本项。";
        }else if(!maps(a.get("externalRequests")).isEmpty()){
            strategy="REPAIR_EXTERNAL_SCOPE";status="FAILED";reason="系统尚未解析出完整外部查询范围，查询未执行。";next="系统补齐主体、来源、阶段和参考日期，再查询指定来源；不要求投标人补材料。";
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
        if("WAIT_AGENT_INPUT_CHANGE".equals(strategy)){
            Map<String,Object> receipt=map(a.get("agentInvestigation"));
            result.putAll(fields("errorCode","AGENT_STALLED_WITH_REMAINING_TARGETS","investigationIncomplete",true,
                "stopScope","CURRENT_INVESTIGATION_ONLY","resumeTrigger","RELEVANT_INPUT_OR_EVIDENCE_CHANGED","requiresInputChange",true));
            for(String key:List.of("remainingTargetObligationIds","blockedTargetObligationIds","completedTargetObligationIds"))
                if(receipt.get(key) instanceof Collection<?> values)result.put(key,List.copyOf(values));
        }
        if(!externalState.isEmpty())result.put("externalState",externalState);
        if(Set.of("NOT_CONFIGURED","CONFIGURATION_INVALID").contains(text(externalState,"state"))){result.put("requiresConfigurationChange",true);result.put("resumeTrigger","CONNECTOR_CONFIGURATION_CHANGED");}
        if(compilation.get("retryNotBeforeEpochMillis") instanceof Number n)result.put("retryNotBeforeEpochMillis",n.longValue());
        return result;
    }
    private static boolean executableExternal(Map<String,Object> request){
        return !text(request,"type").isBlank()&&!text(request,"subject").isBlank()
            &&!text(request,"evaluationStage").isBlank()&&!text(request,"referenceDate").isBlank();
    }
    static boolean retryable(Object value){
        if(value instanceof Map<?,?> m){
            if(Boolean.TRUE.equals(m.get("recoveredByRetry"))||Boolean.TRUE.equals(m.get("transportRecovered")))return false;
            String code=Objects.toString(m.get("errorCode"),Objects.toString(m.get("code"),""));
            if(Set.of("AGENT_STALLED_WITH_REMAINING_TARGETS","AGENT_INVESTIGATION_STALLED").contains(code))return false;
            if(Boolean.TRUE.equals(m.get("retryable")))return true;
            if(Set.of("AGENT_INVESTIGATION_FAILED","MODEL_TIMEOUT","MODEL_UNAVAILABLE","HTTP_429","HTTP_502","HTTP_503","HTTP_504","MODEL_TRANSPORT_ERROR","MODEL_RATE_LIMITED").contains(code))return true;
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
            &&(changed||"FAILED".equals(state)||missingRecords>0||jobs.isEmpty()||jobs.stream().anyMatch(j->"FAILED".equals(text(j,"status"))&&!Boolean.TRUE.equals(j.get("requiresConfigurationChange"))
                &&(!(j.get("retryNotBeforeEpochMillis") instanceof Number time)||time.longValue()<=System.currentTimeMillis())));
        return fields("version",VERSION,"executionState",state,"finished",!Set.of("QUEUED","RUNNING").contains(state),
            "originalCount",findings.size()+missingRecords,"missingOriginalCount",missingRecords,"unresolvedOriginalCount",unresolved,"recoverableCount",canRecover?unresolved:active,
            "activeJobCount",active,"blockedCount",jobs.stream().filter(j->!ACTIVE.contains(text(j,"status"))&&!"SUCCEEDED".equals(text(j,"status"))).count(),
            "canRecover",canRecover,"blockReason",canRecover?"":unresolved==0?"当前没有待补查事项。":"本轮已尝试可用策略；规则、证据或外部条件变化后可继续。",
            "parentRunId",text(run,"parentRunId"),"countScope","ORIGINAL_RECORDS_NOT_BUSINESS_GROUPS");
    }
}
