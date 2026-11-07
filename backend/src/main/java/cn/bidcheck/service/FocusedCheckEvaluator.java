package cn.bidcheck.service;
import cn.bidcheck.compat.HexFormat;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.VerificationEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Adapts source-grounded atomic proofs to ordinary CHECK findings, without changing their condition tree. */
@Service
public final class FocusedCheckEvaluator {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(FocusedCheckEvaluator.class);
    public static final String VERSION="focused-check-3.4-agent-group-reuse-audit";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final TypeReference<Map<String,Object>> OBJECT=new TypeReference<Map<String,Object>>() {};
    private static final Set<String> TYPES=cn.bidcheck.compat.Java8Api.setOf("SEMANTIC_RESPONSE", "DATE_VALIDITY", "VALUE_COMPARE", "SIGNATURE_SEAL");
    private static final Set<String> STATUSES=cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "WARNING", "UNDETERMINED", "NOT_APPLICABLE", "NEEDS_CONFIRMATION");
    private static final Pattern PERSONNEL=Pattern.compile("联合体|牵头(?:人|单位|成员)|(?:施工|设计)成员|项目(?:经理|负责人)|(?:施工|设计|技术|质量|安全)负责人|专职安全员|企业(?:主要)?负责人|法定代表人|授权(?:代表|委托)|委托代理人|拟派.{0,12}人员|人员.{0,12}(?:任职|归属|聘用|劳动关系|社保)|任职|劳动关系|社会保险|社保");
    private static final Pattern CERTIFICATE=Pattern.compile("证书|证照|营业执照|资格证|资质证|安全生产许可|执业证|注册证|考核合格证|[ABC]证");
    private static final Pattern VALIDITY=Pattern.compile("有效|效期|到期|过期|失效|有效截止|截止日|截止时间");
    private static final Pattern PRICE=Pattern.compile("(?:投标|总|最终|评标|分项|工程|施工|设计|采购)?报价|投标总价|投标价格|含税价|不含税价|税前价|税后价|最高(?:投标)?限价|招标控制价");
    private static final Pattern GLOBAL_REVIEW=Pattern.compile("(?:全文|全篇|整份(?:投标)?(?:文件|文档)|全部文档).{0,16}(?:一致性|检查|核查|核验|审查|扫描|汇总)");
    private static final Pattern OTHER_PRIMARY_PURPOSE=Pattern.compile("(?:施工|设计|类似项目|类似工程)业绩|(?:得分|扣分|评分|计分|最高档)|黑名单|禁入期");
    private static final Set<String> PRICE_FIELDS=cn.bidcheck.compat.Java8Api.setOf("totalPrice", "totalBidPrice", "bidPrice", "quotedPrice", "price", "taxIncludedPrice", "taxExcludedPrice", "maximumBidPrice");
    private static final Set<String> CERTIFICATE_FIELDS=cn.bidcheck.compat.Java8Api.setOf("certificateValidFrom", "certificateValidUntil", "credentialValidFrom", "credentialValidUntil", "credentialValidPeriod", "qualificationValidUntil");
    private static final Set<String> DURATION_FIELDS=cn.bidcheck.compat.Java8Api.setOf("duration", "totalDuration", "designDuration", "constructionDuration", "deliveryPeriod", "completionPeriod", "constructionPeriod");
    private final VerificationEngine engine;

    public FocusedCheckEvaluator(VerificationEngine engine){this.engine=Objects.requireNonNull(engine);}

    public String identity(){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((VERSION+"|"+engine.identity()).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    public boolean supports(Checkpoint checkpoint){
        if(checkpoint==null||!checkpoint.enabled()||!checkpoint.confirmed()||!TYPES.contains(Objects.toString(checkpoint.type(),"")))return false;
        if("fullTextConsistency".equals(checkpoint.field())||"全文一致性".equals(checkpoint.title())||"全文信息检查".equals(checkpoint.title()))return false;
        if("SEMANTIC_RESPONSE".equals(checkpoint.type())&&GLOBAL_REVIEW.matcher(Objects.toString(checkpoint.title(),"")).find())return false;
        // Every ordinary semantic requirement uses the same source-grounded compiler/extractor.
        // An empty focused-domain list is valid: it means the general closed DSL must classify the
        // clause, rather than falling back to a model-authored PASS/FAIL judgment.
        return "SEMANTIC_RESPONSE".equals(checkpoint.type())||!domains(checkpoint).isEmpty();
    }
    public boolean supports(Checkpoint checkpoint,String reviewMode){return CheckReviewMode.AGENT.equals(reviewMode)?CheckReviewMode.eligible(checkpoint):supports(checkpoint);}

    public Result assess(Checkpoint checkpoint,Document bid,Document tender,Map<String,Object> context){
        String reviewMode=CheckReviewMode.normalize(context==null?null:context.get("reviewMode"));
        if(!supports(checkpoint,reviewMode))throw new IllegalArgumentException("FOCUSED_CHECK_DOMAIN_NOT_SUPPORTED");
        List<String> selectedDomains=domains(checkpoint);
        Map<String,Object> input=new LinkedHashMap<>(context==null?cn.bidcheck.compat.Java8Api.mapOf():context);
        input.put("focusedCheck",true);input.put("focusedCheckVersion",VERSION);
        input.put("checkpointId",checkpoint.id());
        input.put("reviewMode",reviewMode);
        input.put("atomicDomains",selectedDomains);input.put("unlimitedModelCalls",true);
        if(input.get("referenceDateEvidence") instanceof Evidence)input.put("referenceDateEvidence",JSON.convertValue(((Evidence) (input.get("referenceDateEvidence"))),OBJECT));
        List<Document> evidenceDocuments=new ArrayList<>();if(bid!=null)evidenceDocuments.add(bid);
        if(input.get("supplementDocuments") instanceof List<?>)for(Object extra:((List<?>) (input.get("supplementDocuments"))))if(extra instanceof Document&&bid!=null&&bid.projectId().equals(((Document) (extra)).projectId())&&"SUPPLEMENT".equals(((Document) (extra)).role()))evidenceDocuments.add(((Document) (extra)));
        Map<String,Object> output=engine.evaluate(checkpoint,evidenceDocuments,tender,input);
        Map<String,Object> plan=map(output.get("plan"));
        Map<String,Map<String,Object>> decisions=new LinkedHashMap<>();
        for(Map<String,Object> decision:maps(output.get("obligations")))decisions.put(text(decision,"id"),decision);
        List<Map<String,Object>> conditions=new ArrayList<>();
        for(Map<String,Object> rule:maps(plan.get("rules")))leaves(rule,decisions,conditions,null,false);
        Map<String,String> conditionNames=new LinkedHashMap<>();
        collectNames(maps(plan.get("rules")),conditionNames);
        for(Map<String,Object> condition:conditions){
            condition.put("title",readable(text(condition,"title"),conditionNames));
            Map<String,Object> contextSource=map(condition.get("requirementContext"));
            if(!cn.bidcheck.compat.Java8Api.isBlank(text(contextSource,"parentRuleId")))contextSource.put("parentTitle",conditionNames.getOrDefault(text(contextSource,"parentRuleId"),"上级条件"));
            if(!contextSource.isEmpty())condition.put("requirementContext",contextSource);
            condition.put("presentation",presentation(condition,conditionNames));
        }
        String business=validStatus(output.get("businessDecision"));
        List<Evidence> evidence=evidences(output.get("evidences"));
        List<Map<String,Object>> gaps=maps(output.get("gaps"));
        Map<String,Object> metrics=map(output.get("metrics"));Map<String,Integer> gapCodes=new TreeMap<>();
        for(Map<String,Object> gap:gaps){String code=text(gap,"code");if(code.matches("[A-Z0-9_]{1,100}"))gapCodes.merge(code,1,Integer::sum);}
        LOG.info("bidcheck.proof runId={} checkpointId={} businessDecision={} complete={} facts={} reusedFacts={} rejectedFacts={} candidates={} modelCalls={} stopReason={} gapCodes={}",
            input.getOrDefault("sourceRunId",""),checkpoint.id(),business,Boolean.TRUE.equals(output.get("complete")),metrics.get("facts"),metrics.get("reusedFacts"),
            metrics.get("rejectedFacts"),metrics.get("finalCandidateCount"),metrics.get("modelCalls"),metrics.get("stopReason"),gapCodes);
        List<Map<String,Object>> requirements=cn.bidcheck.compat.Java8Api.toList(conditions.stream().filter(c->"REQUIREMENT".equals(c.get("conditionRole"))));
        Map<String,Object> summary=values("total",requirements.size(),"passed",count(requirements,"PASS"),"failed",count(requirements,"FAIL"),
            "pending",requirements.stream().filter(c->!cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(text(c,"status"))).count(),
            "notApplicable",count(requirements,"NOT_APPLICABLE"),"applicability",conditions.stream().filter(c->"APPLICABILITY".equals(c.get("conditionRole"))).count(),"auxiliary",conditions.stream().filter(c->"AUXILIARY".equals(c.get("conditionRole"))).count(),"allLeaves",conditions.size(),
            "boundary","总数及通过、失败、待核仅统计业务要求，适用判断和辅助参考日期另计。整项结论保留原条件树的且、或及适用分支，不能按通过比例计算。");
        List<Map<String,Object>> attemptSteps=new ArrayList<>();
        for(Map<String,Object> prior:maps(input.get("priorSteps")))if(!Boolean.TRUE.equals(prior.get("paidInPriorRevision")))attemptSteps.add(prior);
        attemptSteps.addAll(maps(output.get("steps")));
        Map<String,Object> currentAgent=map(output.get("agentInvestigation"));
        List<Map<String,Object>> durableAgentAttempts=agentAttempts(attemptSteps,currentAgent);
        Map<String,Object> effectiveAgent=currentAgent;
        if(effectiveAgent.isEmpty()&&!durableAgentAttempts.isEmpty()){
            effectiveAgent=new LinkedHashMap<>(durableAgentAttempts.get(durableAgentAttempts.size()-1));
            effectiveAgent.put("auditRestoredFromAppendOnlySteps",true);effectiveAgent.put("currentExecutionAgentCalled",false);
        }
        Map<String,Object> atomic=values("version",VERSION,"domains",selectedDomains,"businessDecision",business,
            "workflowState",output.getOrDefault("workflowState","NEEDS_REVIEW"),"complete",Boolean.TRUE.equals(output.get("complete")),
            "reason",text(output,"reason"),"conditions",conditions,"summary",summary,"gaps",gaps,"metrics",map(output.get("metrics")),
            "plan",plan,"proof",map(output.get("proof")),"evidences",evidence,"rejectedFacts",maps(output.get("rejectedFacts")),
            "visualRequests",maps(output.get("visualRequests")),"externalRequests",maps(output.get("externalRequests")),"inferredFacts",maps(output.get("inferredFacts")),
            "modelDiagnostics",map(output.get("modelDiagnostics")),"agentInvestigation",effectiveAgent,"agentAttempts",durableAgentAttempts,
            "compilationAudit",map(output.get("compilationAudit")),"recoveryDependencies",map(output.get("recoveryDependencies")),"coverageDependencies",maps(output.get("coverageDependencies")),"recoveryEvidenceDependencies",maps(output.get("recoveryEvidenceDependencies")),"recoveryActions",maps(output.get("recoveryActions")),"fieldCoverage",maps(output.get("fieldCoverage")),
            "boundary","按本条款的独立义务分派核验，保留完整复合要求与尚未完成条件。未召回不表示材料缺失，来源校验不代表原图识别准确。");
        atomic.put("documentIdentities",cn.bidcheck.compat.Java8Api.toList(java.util.stream.Stream.concat(evidenceDocuments.stream(),tender==null?java.util.stream.Stream.<Document>empty():java.util.stream.Stream.of(tender))
            .map(d->values("id",d.id(),"version",d.version(),"sha256",d.sha256()))));
        atomic.put("reviewMode",reviewMode);atomic.put("agentReview",map(output.get("agentReview")));
        if(input.get("agentEvidenceReuse") instanceof Map<?,?>)atomic.put("agentEvidenceReuse",map(input.get("agentEvidenceReuse")));
        Map<String,Object> display=presentation(atomic,conditionNames);atomic.put("presentation",display);
        Map<String,Object> model=modelAudit(attemptSteps,currentAgent,text(output,"workflowState"));
        Map<String,Object> assessment=values("atomicCheck",atomic,"model",model,"policyVersion",VERSION,
            "structuredSteps",stepIndex(attemptSteps),"confidenceCalibrated",false);
        String suggestion=String.join("；",cn.bidcheck.compat.Java8Api.toList(strings(display.get("nextSteps")).stream().limit(2)));
        // The engine supplies a proof, not a calibrated probability; never fabricate confidence.
        Evaluation evaluation=new Evaluation(business,"UNDETERMINED".equals(business)?text(display,"reason"):readable(text(output,"reason"),conditionNames),suggestion,evidence,0d,CheckReviewMode.AGENT.equals(reviewMode)?"AGENT_LED_REVIEW":"FOCUSED_ATOMIC_RULES");
        return new Result(evaluation,Collections.unmodifiableMap(assessment));
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Result {
        private final Evaluation evaluation;
        private final Map<String,Object> assessment;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Result(@com.fasterxml.jackson.annotation.JsonProperty("evaluation") Evaluation evaluation, @com.fasterxml.jackson.annotation.JsonProperty("assessment") Map<String,Object> assessment) { this.evaluation = evaluation; this.assessment = assessment; }
        public Evaluation evaluation() { return evaluation; }
        public Map<String,Object> assessment() { return assessment; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Result that = (Result) other; return java.util.Objects.equals(this.evaluation, that.evaluation) && java.util.Objects.equals(this.assessment, that.assessment); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.evaluation); hash = 31 * hash + java.util.Objects.hashCode(this.assessment); return hash; }
        @Override public String toString() { return "Result[" + "evaluation=" + evaluation + ", assessment=" + assessment + "]"; }
}

    /** Attach display metadata only after the final evidence/OCR guards; retain the raw engine proof. */
    public static Map<String,Object> reconcileAssessment(Evaluation evaluation,Map<String,Object> assessment){
        Map<String,Object> out=new LinkedHashMap<>(assessment==null?cn.bidcheck.compat.Java8Api.mapOf():assessment),atomic=new LinkedHashMap<>(map(out.get("atomicCheck")));
        if(!atomic.isEmpty()){
            atomic.put("finalBusinessStatus",evaluation.status());
            atomic.put("presentation",presentation(atomic));out.put("atomicCheck",atomic);
        }
        if(out.get("templateResult") instanceof cn.bidcheck.checktemplate.ResultProjection){
            cn.bidcheck.checktemplate.ResultProjection reconciled=TemplateResultFinalizer.reconcile(evaluation,((cn.bidcheck.checktemplate.ResultProjection) (out.get("templateResult"))));
            out.put("templateResult",reconciled);out.put("resultProjection",reconciled);
        }
        out.put("businessPresentation",BusinessResultPresentation.describe(evaluation,out));return out;
    }

    /** Keep the business decision and its evidence intact, but publish the actionable
     * reason and instruction consistently to the finding API, page and report. */
    static Finding publishedFinding(Finding finding,Map<String,Object> assessment){
        Evaluation before=finding.evaluation();
        Map<String,Object> trace=reconcileAssessment(before,assessment),presentation=map(trace.get("businessPresentation"));
        String state=Objects.toString(presentation.get("state"),"");
        boolean scopedCertificate=state.equals("EVIDENCE_PENDING")&&CheckResolution.maps(map(trace.get("atomicCheck")).get("gaps"))
            .stream().anyMatch(g->"CERTIFICATE_SET_COVERAGE_REQUIRED".equals(g.get("code")));
        if(!Arrays.asList("MATERIAL_REQUIRED","USER_ACTION").contains(state)&&!scopedCertificate)
            return new Finding(finding.id(),finding.runId(),finding.checkpoint(),before,trace);
        String reason=Objects.toString(presentation.get("reason"),before.reason()),next=Objects.toString(presentation.get("nextStep"),before.suggestion());
        if(reason.equals(before.reason())&&next.equals(before.suggestion()))return new Finding(finding.id(),finding.runId(),finding.checkpoint(),before,trace);
        trace.putIfAbsent("prePresentationEvaluation",before);
        trace.putIfAbsent("rawEvaluation",trace.get("prePresentationEvaluation"));
        Evaluation published=new Evaluation(before.status(),reason,next,before.evidences(),before.confidence(),before.method());
        return new Finding(finding.id(),finding.runId(),finding.checkpoint(),published,trace);
    }
    private static List<String> domains(Checkpoint checkpoint){
        String text=Objects.toString(checkpoint.title(),"")+"\n"+Objects.toString(checkpoint.expected(),"");
        List<String> selected=new ArrayList<>();
        if("SIGNATURE_SEAL".equals(checkpoint.type())||"SEMANTIC_RESPONSE".equals(checkpoint.type())
            &&Objects.toString(checkpoint.title(),"").matches("(?s).*(?:签章|签字|签名|盖章|签署).*")
            &&!Objects.toString(checkpoint.title(),"").matches("(?s).*(?:正文|材料|文件).{0,8}(?:齐全|完整性|组成).*"))selected.add("VISUAL_FORM");
        if(PERSONNEL.matcher(text).find())selected.add("PERSONNEL_CONSORTIUM");
        if(CERTIFICATE_FIELDS.contains(Objects.toString(checkpoint.field(),""))||CERTIFICATE.matcher(text).find()&&(VALIDITY.matcher(text).find()||"DATE_VALIDITY".equals(checkpoint.type())))selected.add("CERTIFICATE_VALIDITY");
        if(PRICE.matcher(text).find()||"VALUE_COMPARE".equals(checkpoint.type())&&PRICE_FIELDS.contains(Objects.toString(checkpoint.field(),"")))selected.add("PRICE_BASIS");
        if("VALUE_COMPARE".equals(checkpoint.type())&&(DURATION_FIELDS.contains(Objects.toString(checkpoint.field(),""))||text.matches("(?s).*(?:工期|交货期|交付期|履约期限).*")&&!OTHER_PRIMARY_PURPOSE.matcher(text).find()))selected.add("DURATION_RESPONSE");
        return cn.bidcheck.compat.Java8Api.listCopyOf(selected);
    }

    static void leaves(Map<String,Object> rule,Map<String,Map<String,Object>> decisions,List<Map<String,Object>> out,
                               Map<String,Object> inactive,boolean applicabilityCondition){
        List<Map<String,Object>> children=maps(rule.get("children"));
        String id=text(rule,"id");Map<String,Object> decision=decisions.get(id);
        if(children.isEmpty()){
            String status,reason;List<Evidence> sources;List<Map<String,Object>> gaps;Map<String,Object> proof;
            if(inactive!=null){status="NOT_APPLICABLE";reason="已证明适用条件，当前选择另一条分支，本条件未执行。";sources=evidences(inactive.get("evidences"));gaps=cn.bidcheck.compat.Java8Api.listOf();proof=values("applicability",inactive);}
            else if(decision==null){status="UNDETERMINED";reason="本条件尚未执行或其适用范围尚未确定；原要求保留。";sources=cn.bidcheck.compat.Java8Api.listOf();gaps=cn.bidcheck.compat.Java8Api.listOf(values("code","ATOMIC_CONDITION_NOT_EVALUATED","description",reason,"requiredInput","先确认适用条件或补齐该项证据。","resumeTrigger","INPUT_OR_EVIDENCE_CHANGED"));proof=cn.bidcheck.compat.Java8Api.mapOf();}
            else{status=validStatus(decision.get("status"));reason=text(decision,"reason");sources=evidences(decision.get("evidences"));gaps=maps(decision.get("gaps"));proof=map(decision.get("proof"));}
            out.add(values("id",id,"title",text(rule,"description"),"status",status,"reason",reason,"evidences",sources,"gaps",gaps,"proof",proof,
                "subject",text(rule,"subject"),"predicate",text(rule,"predicate"),"sourceQuote",text(rule,"sourceQuote"),
                "requirementContext",requirementContext(rule),
                "conditionRole",applicabilityCondition?"APPLICABILITY":"REFERENCE_TIME".equals(text(rule,"predicate"))?"AUXILIARY":"REQUIREMENT","evaluated",inactive==null&&decision!=null));
            return;
        }
        if(!cn.bidcheck.compat.Java8Api.setOf("", "GROUP", "SCORE_COUNT", "UNSUPPORTED").contains(text(rule,"predicate"))){
            Map<String,Object> own=new LinkedHashMap<>(rule);own.put("id",id+"::own");own.put("children",cn.bidcheck.compat.Java8Api.listOf());own.put("logic","");
            leaves(own,decisions,out,inactive,applicabilityCondition);
        }
        Map<String,Object> parentProof=decision==null?cn.bidcheck.compat.Java8Api.mapOf():map(decision.get("proof"));
        if(parentProof.get("supportingConditions") instanceof Map<?,?>)parentProof=map(parentProof.get("supportingConditions"));
        Map<String,Object> condition=map(parentProof.get("condition")),selected=map(parentProof.get("selectedBranch"));
        boolean knownBranch="CONDITIONAL".equals(rule.get("logic"))&&children.size()==3&&cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(text(condition,"status"))
            &&text(selected,"id").equals(text(children.get("PASS".equals(condition.get("status"))?1:2),"id"));
        for(int index=0;index<children.size();index++){
            Map<String,Object> excluded=inactive;
            if(excluded==null&&knownBranch&&index>0&&!text(children.get(index),"id").equals(text(selected,"id")))
                excluded=values("parentConditionId",id,"selectedBranchId",text(selected,"id"),"conditionStatus",condition.get("status"),"evidences",condition.getOrDefault("evidences",cn.bidcheck.compat.Java8Api.listOf()),"proof",condition.getOrDefault("proof",cn.bidcheck.compat.Java8Api.mapOf()));
            leaves(children.get(index),decisions,out,excluded,applicabilityCondition||"CONDITIONAL".equals(rule.get("logic"))&&index==0);
        }
    }

    /** Presentation is separate from the engine decision; raw gaps and proof remain in the report. */
    static Map<String,Object> presentation(Map<String,Object> check){
        Map<String,String> names=new LinkedHashMap<>();for(Map<String,Object> condition:maps(check.get("conditions")))names.put(text(condition,"id"),text(condition,"title"));
        return presentation(check,names);
    }
    private static Map<String,Object> presentation(Map<String,Object> check,Map<String,String> names){
        List<Map<String,Object>> issues=new ArrayList<>();
        for(Map<String,Object> gap:maps(check.get("gaps"))){String category=category(text(gap,"code"));
            String description=readable(text(gap,"description"),names);
            if(cn.bidcheck.compat.Java8Api.isBlank(description))description=stateReason(category);
            if("UNIQUE_PRICE_SCOPE_REQUIRED".equals(text(gap,"code"))){
                issues.add(values("code",text(gap,"code"),"category",category,"label","报价范围尚未核全","description",description,"nextStep","系统需核全适用报价与备选方案范围；局部报价一致不代表报价唯一，当前不要求补交材料。"));
                continue;
            }
            issues.add(values("code",text(gap,"code"),"category",category,"label",stateLabel(category),"description",description,"nextStep",mappingPending(text(gap,"code"))?"系统需继续完成证据字段或人员单位对应；当前不要求补交材料，也不计入人工核对待办。":stateNext(category)));
        }
        String workflow=text(check,"workflowState");
        boolean publishedPending=check.containsKey("finalBusinessStatus")&&!cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "WARNING", "NOT_APPLICABLE").contains(text(check,"finalBusinessStatus"));
        boolean businessCompleted=!publishedPending&&Boolean.TRUE.equals(check.get("complete"))&&"COMPLETED".equals(workflow);
        if(publishedPending&&Boolean.TRUE.equals(check.get("complete"))&&"COMPLETED".equals(workflow))
            issues.add(values("category","REQUIREMENT_UNPARSED","label","整项要求尚未证明","description","辅助条件的完成不足以证明整项要求；以守卫后的业务结论为准。","nextStep","需补齐主条件及其证据，不能用参考日期或处理完成替代业务证明。"));
        if(cn.bidcheck.compat.Java8Api.setOf("ENGINE_FAILURE", "ERROR").contains(workflow)||"FAILED".equals(text(map(check.get("agentInvestigation")),"status"))||map(check.get("modelDiagnostics")).get("unresolvedFailures") instanceof Number&&((Number) (map(check.get("modelDiagnostics")).get("unresolvedFailures"))).longValue()>0)
            issues.add(values("category","PROCESSING_FAILED","label",businessCompleted?"候选处理记录":stateLabel("PROCESSING_FAILED"),"description",businessCompleted?"部分候选处理曾未完成；其他证据已支持当前完整结论，详见处理记录。":stateReason("PROCESSING_FAILED"),"nextStep",stateNext("PROCESSING_FAILED")));
        List<String> order=cn.bidcheck.compat.Java8Api.listOf("PROCESSING_FAILED", "REQUIREMENT_UNPARSED", "RELATION_UNCONFIRMED", "EVIDENCE_NOT_FOUND", "NEEDS_CONFIRMATION", "NEEDS_REVIEW");
        issues.sort(Comparator.comparingInt(issue->order.indexOf(text(issue,"category"))));
        String state=businessCompleted?"COMPLETED":order.stream().filter(value->issues.stream().anyMatch(issue->value.equals(issue.get("category")))).findFirst().orElse(
            "WAIT_STAGE".equals(workflow)?"WAIT_STAGE":!publishedPending&&cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(text(check,"status"))?"COMPLETED":"NEEDS_REVIEW");
        List<String> next=businessCompleted?cn.bidcheck.compat.Java8Api.listOf(stateNext(state)):cn.bidcheck.compat.Java8Api.toList(issues.stream().map(issue->text(issue,"nextStep")).distinct());if(next.isEmpty())next=cn.bidcheck.compat.Java8Api.listOf(stateNext(state));
        return values("version","focused-presentation-2","state",state,"label",stateLabel(state),"reason",stateReason(state),"nextSteps",next,"issues",issues);
    }
    private static String category(String code){
        if("INFERENCE_CONFIRMATION_REQUIRED".equals(code))return "NEEDS_CONFIRMATION";
        if(mappingPending(code))return "RELATION_UNCONFIRMED";
        if(code.startsWith("MODEL_")||code.startsWith("HTTP_")||cn.bidcheck.compat.Java8Api.setOf("AGENT_INVESTIGATION_FAILED", "AGENT_INVESTIGATION_STALLED", "RULE_COMPILATION_UNAVAILABLE", "FACT_EXTRACTION_UNAVAILABLE", "TRANSPORT_FAILURE", "REQUEST_TIMEOUT", "CAPACITY_TIMEOUT", "OUTPUT_JSON_INVALID", "OUTPUT_SCHEMA_INVALID", "OUTPUT_INCOMPLETE", "OUTPUT_LIMIT", "INPUT_LIMIT").contains(code))return "PROCESSING_FAILED";
        if(code.contains("COMPILATION")||code.startsWith("REQUIREMENT_")||cn.bidcheck.compat.Java8Api.setOf("UNSUPPORTED_RULE", "OPERATOR_UNSUPPORTED", "SUBJECT_REQUIRED", "ATOMIC_CONDITION_NOT_EVALUATED", "PRICE_REQUIREMENT_DIMENSIONS_REQUIRED", "PERSON_COUNT_ROLE_REQUIRED", "PERSON_COUNT_COMPARISON_REQUIRED").contains(code))return "REQUIREMENT_UNPARSED";
        if(code.contains("RELATION")||code.contains("IDENTITY")||code.contains("APPOINTMENT")||code.contains("EMPLOYER")||code.contains("SCOPE")||code.contains("DIMENSION")||code.contains("SELECTION")||code.contains("VERSION")||cn.bidcheck.compat.Java8Api.setOf("REFERENCE_TIME_UNCONFIRMED", "CERTIFICATE_CATEGORY_REQUIRED", "VALIDITY_FIELD_REQUIRED", "CONFLICTING_EVIDENCE", "PRICE_SUBJECT_REQUIRED").contains(code))return "RELATION_UNCONFIRMED";
        if(cn.bidcheck.compat.Java8Api.setOf("CERTIFICATE_EVIDENCE_REQUIRED", "REGISTRATION_CERTIFICATE_REQUIRED", "FIELD_EVIDENCE_REQUIRED", "PRICE_EVIDENCE_REQUIRED", "COMPARISON_SOURCE_REQUIRED", "MATERIAL_EVIDENCE_REQUIRED", "DECLARATION_EVIDENCE_REQUIRED", "CERTIFICATE_FIELD_REQUIRED", "VALIDITY_RANGE_REQUIRED", "CONTRACT_PROOF_REQUIRED", "FULL_SECTION_REQUIRED", "EXTERNAL_RECORD_REQUIRED", "EVIDENCE_NOT_FOUND", "NO_CANDIDATE_EVIDENCE").contains(code))return "EVIDENCE_NOT_FOUND";
        return "NEEDS_REVIEW";
    }
    static boolean mappingPending(String code){return cn.bidcheck.compat.Java8Api.setOf("COMPARISON_FIELD_MAPPING_REQUIRED", "COMPARISON_RECORD_IDENTITY_UNPROVEN", "REGISTRATION_EMPLOYER_BINDING_REQUIRED", "FORM_TEMPLATE_REFERENCE_REQUIRED", "FORM_REQUIREMENT_SOURCE_REQUIRED", "FORM_PROJECT_BINDING_REQUIRED", "FORM_INSTANCE_AMBIGUOUS", "FORM_CONTENT_COVERAGE_REQUIRED", "FORM_CONDITION_COVERAGE_REQUIRED", "DURATION_REQUIREMENT_SOURCE_REQUIRED", "DURATION_REQUIREMENT_FIELD_UNBOUND", "DURATION_RESPONSE_FORMAT_REQUIRED").contains(code);}
    private static String stateLabel(String state){switch (state) {case "NEEDS_CONFIRMATION":return "推断待你确认";case "PROCESSING_FAILED":return "处理失败";case "REQUIREMENT_UNPARSED":return "条款未解析完整";case "RELATION_UNCONFIRMED":return "对应关系待确认";case "EVIDENCE_NOT_FOUND":return "证据未找到";case "WAIT_STAGE":return "等待适用时点";case "COMPLETED":return "条件计算已结束";default:return "尚未形成结论";}}
    private static String stateReason(String state){switch (state) {case "NEEDS_CONFIRMATION":return "已有带原文引用的推断；确认前不作为已采纳事实，也不代表满足要求。";case "PROCESSING_FAILED":return "本项处理未完成，已得到的条件和依据予以保留；不能据此判断材料缺失或不符合。";case "REQUIREMENT_UNPARSED":return "尚未完整明确本条款的核验要求，暂不能据此判断投标是否符合。";case "RELATION_UNCONFIRMED":return "要求对象与文件中的单位、人员、证件或报价口径尚未完整对应，需要结合原文确认。";case "EVIDENCE_NOT_FOUND":return "本次检查尚未定位到足以支持判断的相关证据，不代表原文件缺少材料。";case "WAIT_STAGE":return "本条件尚未到适用的核验时点。";case "COMPLETED":return "条件处理已完成，整项结论及原图复核要求以上方结果为准。";default:return "仍有需要人工确认的范围，请结合条件说明和原文核对。";}}
    private static String stateNext(String state){switch (state) {case "NEEDS_CONFIRMATION":return "核对推断卡片中的主体、取值和原文，确认或否定后重新计算业务结论。";case "PROCESSING_FAILED":return "查看处理说明；系统需继续处理失败请求，本提示不要求补交材料，不计入人工核对待办。";case "REQUIREMENT_UNPARSED":return "先对照招标原句明确要求对象、比较条件与适用范围。";case "RELATION_UNCONFIRMED":return "核对相关原文中的人员、单位、证件及项目口径，确认它们是否对应同一要求。";case "EVIDENCE_NOT_FOUND":return "先核对本次文件中的相关材料和定位范围，再确认是否确需补充材料。";case "WAIT_STAGE":return "核对条款规定的核验时点，当前保留待核。";default:return "核对整项结论及引用原文；如有原图复核提示，先对照原图确认。";}}
    static String readable(String value,Map<String,String> names){
        String out=Objects.toString(value,"");for(java.util.Map.Entry<java.lang.String, java.lang.String> entry:cn.bidcheck.compat.Java8Api.toList(names.entrySet().stream().sorted((a,b)->Integer.compare(b.getKey().length(),a.getKey().length())))){
            if(cn.bidcheck.compat.Java8Api.isBlank(entry.getKey())||cn.bidcheck.compat.Java8Api.isBlank(entry.getValue()))continue;
            out=out.replaceAll("(?<![A-Za-z0-9_])"+Pattern.quote(entry.getKey())+"(?![A-Za-z0-9_])",java.util.regex.Matcher.quoteReplacement("条件「"+entry.getValue()+"」"));
        }
        return out.replaceAll("(?i)(?<![A-Za-z0-9_])(?:rule|condition|r)[_-]\\d+(?:[_-]\\d+)*(?![A-Za-z0-9_])","本条件");
    }
    private static void collectNames(List<Map<String,Object>> rules,Map<String,String> names){for(Map<String,Object> rule:rules){names.put(text(rule,"id"),text(rule,"description"));if(!maps(rule.get("children")).isEmpty()&&!cn.bidcheck.compat.Java8Api.setOf("", "GROUP", "SCORE_COUNT", "UNSUPPORTED").contains(text(rule,"predicate")))names.put(text(rule,"id")+"::own",text(rule,"description"));collectNames(maps(rule.get("children")),names);}}
    static Map<String,Object> requirementContext(Map<String,Object> rule){
        Map<String,Object> binding=map(map(map(rule.get("parameters")).get("requirementBindings")).get("subject"));
        if(cn.bidcheck.compat.Java8Api.isBlank(text(binding,"value"))||cn.bidcheck.compat.Java8Api.isBlank(text(binding,"sourceQuote"))||!cn.bidcheck.compat.Java8Api.setOf("ORIGINAL_REQUIREMENT", "REVIEWED_REQUIREMENT").contains(text(binding,"sourceKind"))||!cn.bidcheck.compat.Java8Api.setOf("LOCAL", "PARENT", "CLAUSE").contains(text(binding,"bindingKind")))return cn.bidcheck.compat.Java8Api.mapOf();
        Map<String,Object> context=new LinkedHashMap<>();for(String key:cn.bidcheck.compat.Java8Api.listOf("value", "sourceQuote", "start", "end", "sourceKind", "bindingKind", "parentRuleId"))if(binding.containsKey(key))context.put(key,binding.get(key));
        context.put("boundary","仅说明招标要求对象的来源，不代表已确认投标人员、单位或证件归属。");return context;
    }
    private static List<String> strings(Object value){return value instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().filter(String.class::isInstance).map(String.class::cast)):cn.bidcheck.compat.Java8Api.listOf();}

    /** SDK receipts and authorized-request events are separate from legacy extraction attempts. */
    static Map<String,Object> modelAudit(Object steps,Map<String,Object> agent,String workflow){
        List<Map<String,Object>> current=cn.bidcheck.compat.Java8Api.toList(maps(steps).stream().filter(step->!Boolean.TRUE.equals(step.get("paidInPriorRevision"))));
        List<Map<String,Object>> attempts=engineAttempts(current);
        Set<String> authorized=new LinkedHashSet<>();
        for(Map<String,Object> step:current)if("AGENT_EVENT".equals(text(step,"type"))){
            Map<String,Object> event=map(step.get("output"));
            if("model_request_authorized".equals(text(event,"event"))&&!cn.bidcheck.compat.Java8Api.isBlank(text(event,"requestId"))
                &&event.get("modelIndex") instanceof Number&&((Number) (event.get("modelIndex"))).longValue()>0)
                authorized.add(text(event,"requestId")+":"+((Number) (event.get("modelIndex"))).longValue());
        }
        List<Map<String,Object>> agentAttempts=agentAttempts(current,agent);
        long receiptCalls=sum(agentAttempts,"modelCalls"),agentCalls=Math.max(receiptCalls,authorized.size());
        long toolCalls=sum(agentAttempts,"toolCalls"),inputTokens=sum(agentAttempts,"inputTokens"),outputTokens=sum(agentAttempts,"outputTokens");
        long contextCompactions=sum(agentAttempts,"contextCompactions");
        boolean requested=!attempts.isEmpty()||agentCalls>0,failed="FAILED".equals(text(agent,"status"))
            ||agentAttempts.stream().anyMatch(attempt->"FAILED".equals(text(attempt,"status")));
        String reason=agentCalls>0?"已发起自动查证模型请求；只接纳通过来源校验的事实，业务结论由程序计算。"
            :failed?"自动查证服务未完成，尚无模型请求发起记录；需重试服务，不能认定无需模型。"
            :requested?"调用结构化事实提取，程序逐条件校验证据并计算结论。"
            :"复用已保存的结构步骤或执行本地规则；是否完成以逐条件证明及缺口为准。";
        if(failed&&requested)reason+=" 本次自动查证尚未完成，已保留请求记录与有效证据。";
        return values("scope","RELATED_EVIDENCE_DOUBTS","policyVersion",cn.bidcheck.semantic.OnDemandPolicy.VERSION,
            "requested",requested,"decision",requested?"MODEL_ESCALATED":failed?"AGENT_SERVICE_FAILED":"MODEL_NOT_NEEDED",
            "decisionReason",reason,"state",workflow,"attempts",cn.bidcheck.compat.Java8Api.listOf(),"engineAttempts",attempts,
            "agentAttempts",agentAttempts,"agentInvestigationCount",agentAttempts.size(),"agentModelCalls",agentCalls,
            "agentToolCalls",toolCalls,"agentInputTokens",inputTokens,"agentOutputTokens",outputTokens,"agentContextCompactions",contextCompactions,
            "agentModelAuthorizedEvents",authorized.size(),"httpStatusObserved",false,
            "boundary","Agent调用从本轮追加式步骤记录按requestId去重聚合；本地重算不会覆盖首次模型、工具与Token审计。请求发起不代表HTTP成功、有效事实或业务通过，已付费旧版本步骤不重复计入。");
    }
    static List<Map<String,Object>> agentAttempts(Object steps,Map<String,Object> currentAgent){
        Map<String,Map<String,Object>> attempts=new LinkedHashMap<>();
        for(Map<String,Object> step:maps(steps)){
            if(!"AGENT_INVESTIGATION".equals(text(step,"type"))||!"COMPLETED".equals(text(step,"status")))continue;
            Map<String,Object> receipt=map(step.get("output"));putAgentAttempt(attempts,receipt,text(step,"id"),text(step,"executionId"));
        }
        if(currentAgent!=null&&!currentAgent.isEmpty())putAgentAttempt(attempts,currentAgent,"current-agent-receipt","");
        return cn.bidcheck.compat.Java8Api.listCopyOf(attempts.values());
    }
    private static void putAgentAttempt(Map<String,Map<String,Object>> attempts,Map<String,Object> receipt,String stepId,String executionId){
        if(receipt==null||receipt.isEmpty())return;
        String requestId=text(receipt,"requestId");String key=!cn.bidcheck.compat.Java8Api.isBlank(requestId)?"request:"+requestId:!cn.bidcheck.compat.Java8Api.isBlank(executionId)?"execution:"+executionId:"step:"+stepId;
        Map<String,Object> attempt=new LinkedHashMap<>();
        for(String field:cn.bidcheck.compat.Java8Api.listOf("status", "errorCode", "requestId", "policy", "sdk", "sdkVersion", "serverPlanFingerprint", "serverPlanRevision", "targetEvidenceRevision", "targetEvidenceRevisions", "targetObligationIds", "modelCalls", "modelPermissionRequests", "toolCalls", "inputTokens", "outputTokens", "contextCompactions", "elapsedMs", "acceptedFactCount", "businessCheckCompleted", "targetCheckCompleted", "remainingGapCount", "targetRemainingGapCount"))
            if(receipt.containsKey(field))attempt.put(field,receipt.get(field));
        attempt.put("auditKey",key);if(!cn.bidcheck.compat.Java8Api.isBlank(stepId))attempt.put("stepId",stepId);
        attempts.putIfAbsent(key,Collections.unmodifiableMap(attempt));
    }
    private static long sum(List<Map<String,Object>> attempts,String field){long result=0;for(Map<String,Object> attempt:attempts)if(attempt.get(field) instanceof Number)result+=Math.max(0,((Number) (attempt.get(field))).longValue());return result;}
    private static List<Map<String,Object>> engineAttempts(Object steps){
        Map<String,Map<String,Object>> attempts=new LinkedHashMap<>();
        for(Map<String,Object> step:maps(steps))if(cn.bidcheck.compat.Java8Api.setOf("COMPILE", "EXTRACT").contains(text(step,"type"))){
            String id=text(step,"attemptId");if(cn.bidcheck.compat.Java8Api.isBlank(id))continue;
            String status=text(step,"status");if(!cn.bidcheck.compat.Java8Api.setOf("STARTED", "RECEIVED", "COMPLETED", "VALIDATION_FAILED", "FAILED").contains(status))continue;
            Map<String,Object> output=map(step.get("output"));
            Map<String,Object> attempt=values("attemptId",id,"type",step.get("type"),"status",status,"inputFingerprint",step.get("inputFingerprint"),"modelIdentity",step.get("modelIdentity"));
            if("FAILED".equals(status)){attempt.put("errorCode",output.getOrDefault("errorCode","MODEL_UNAVAILABLE"));if(output.get("durationMs") instanceof Number)attempt.put("durationMs",output.get("durationMs"));}
            if("VALIDATION_FAILED".equals(status)){attempt.put("errorCode","REQUIREMENT_COMPILATION_INCOMPLETE");attempt.put("validationIssues",step.getOrDefault("validationIssues",cn.bidcheck.compat.Java8Api.listOf()));}
            if(step.get("elapsedMillis") instanceof Number)attempt.put("durationMs",step.get("elapsedMillis"));
            Map<String,Object> prior=attempts.get(id);
            if(prior==null||stepStateRank(status)>=stepStateRank(text(prior,"status")))attempts.put(id,attempt);
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(attempts.values());
    }
    private static List<Map<String,Object>> stepIndex(Object steps){
        Map<String,Map<String,Object>> index=new LinkedHashMap<>();
        for(Map<String,Object> step:maps(steps)){
            Map<String,Object> entry=values("id",text(step,"id"),"type",text(step,"type"),"status",text(step,"status"),"attemptId",text(step,"attemptId"));
            for(String field:cn.bidcheck.compat.Java8Api.listOf("executionId", "inputFingerprint", "modelIdentity", "elapsedMillis"))if(step.containsKey(field))entry.put(field,step.get(field));
            String type=text(step,"type");
            if("RETRIEVE".equals(type)||type.startsWith("RETRIEVE_"))entry.put("retrieval",map(step.get("output")));
            if(type.startsWith("RETRIEVAL_SELECTION_"))entry.put("selection",map(step.get("output")));
            String key=text(step,"id");if(cn.bidcheck.compat.Java8Api.isBlank(key))key="step-"+index.size();
            Map<String,Object> prior=index.get(key);if(prior==null||stepStateRank(text(entry,"status"))>=stepStateRank(text(prior,"status")))index.put(key,entry);
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(index.values());
    }
    private static int stepStateRank(String status){switch (status) {case "COMPLETED":case "FAILED":case "VALIDATION_FAILED":return 3;case "RECEIVED":return 2;case "STARTED":return 1;default:return 0;}}
    private static long count(List<Map<String,Object>> conditions,String status){return conditions.stream().filter(c->status.equals(c.get("status"))).count();}
    private static String validStatus(Object value){String status=Objects.toString(value,"");return STATUSES.contains(status)?status:"UNDETERMINED";}
    private static String text(Map<String,Object> values,String key){return Objects.toString(values.get(key),"");}
    private static Map<String,Object> map(Object value){return value==null?cn.bidcheck.compat.Java8Api.mapOf():JSON.convertValue(value,OBJECT);}
    private static List<Map<String,Object>> maps(Object value){if(!(value instanceof Collection<?>))return cn.bidcheck.compat.Java8Api.listOf();return cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().map(FocusedCheckEvaluator::map));}
    private static List<Evidence> evidences(Object value){
        if(!(value instanceof Collection<?>))return cn.bidcheck.compat.Java8Api.listOf();
        return cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().map(item->{Evidence evidence=item instanceof Evidence?((Evidence) (item)):JSON.convertValue(item,Evidence.class);
            if(evidence==null||evidence.documentId()==null||evidence.locationType()==null||evidence.quote()==null||cn.bidcheck.compat.Java8Api.isBlank(evidence.quote()))throw new IllegalArgumentException("FOCUSED_EVIDENCE_INVALID");return evidence;}));
    }
    private static Map<String,Object> values(Object... entries){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)out.put(entries[i].toString(),entries[i+1]);return out;}
}
