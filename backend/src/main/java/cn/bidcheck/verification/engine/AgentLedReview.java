package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.retrieval.HybridRetriever;
import cn.bidcheck.verification.agent.AgentInvestigator;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A clause-first reviewer. Plans and semantic proposals are candidates until separately
 * reviewed; deterministic calculations and source authorization remain host-owned. */
final class AgentLedReview {
    static final String VERSION="agent-led-review-2-plan-contract";
    static final String POLICY="AGENT_LED_REVIEW_V1";
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AgentLedReview.class);
    private static final List<String> DETERMINISTIC_PREDICATES=List.of("PERSON_ROLE","PERSON_COUNT","ENTITY_COUNT","CERTIFICATE","VALIDITY","MONTHS","RELATION","NUMBER","SAME_FIELD","PRICE_RECONCILE","DATE_RELATION","CONTRACT","VISUAL","EXTERNAL","REFERENCE_TIME");
    private static final Set<String> DETERMINISTIC=Set.copyOf(DETERMINISTIC_PREDICATES);
    private static final List<String> METHODS=List.of("DETERMINISTIC","SEMANTIC");
    private static final String SEMANTIC_PREDICATE="CONTENT";
    private static final Set<String> CONDITION_KEYS=Set.of("obligationId","description","requirementQuote","method","predicate","subject","field","operator","expected","unit","expectedEvidence");
    private static final String COMPUTABLE="(?s).*(?:有效期|有效期限|签字|签署|签章|盖章|任职|注册证|执业证|资格证|身份证|社保|缴费|报价|总价|金额|税率|不少于\\s*[一二三四五六七八九十百0-9]|不超过\\s*[一二三四五六七八九十百0-9]|[0-9]+\\s*(?:人|名|元|万元|%|日历天|个月)).*";
    private final Checkpoint cp;private final Map<String,Object> ctx;private final RunSession session;
    private final EvidenceCorpus corpus;private final SharedFactReuse shared;private final HybridRetriever retriever;
    private final Map<String,Fact> facts=new LinkedHashMap<>();private final List<Map<String,Object>> rejected=new ArrayList<>();
    private final List<Map<String,Object>> requirements=new ArrayList<>();private final Map<String,Map<String,Object>> requirementSpans=new LinkedHashMap<>();private final List<String> sourceIssues=new ArrayList<>();
    private RequirementSourceContext.Context sourceContext;private RequirementScope.Selection scope;
    private EvidenceInvestigation.Host host;private List<Map<String,Object>> conditions=List.of();private Plan plan;
    private String planRevision="",coverageExplanation="";private Map<String,Object> planReview=Map.of();
    private final Map<String,Set<String>> targetSources=new LinkedHashMap<>();
    private final AgentTargetWorkflow semanticWorkflow=new AgentTargetWorkflow();
    private final Map<String,Decision> semantic=new LinkedHashMap<>();private final Map<String,String> semanticVersions=new HashMap<>();
    private final Map<String,Map<String,Object>> reviewCache=new HashMap<>();private Map<String,Object> agentAudit=Map.of();
    private int planSubmissions,planRejections;private String lastPlanError="";private List<Map<String,Object>> lastPlanViolations=List.of();
    private final long started=System.nanoTime();

    AgentLedReview(Checkpoint cp,List<Document> bids,Map<String,Object> context,RunSession session,HybridRetriever retriever){
        this.cp=cp;this.ctx=context;this.session=session;this.retriever=retriever;corpus=new EvidenceCorpus(bids);corpus.addVisualObservations(ctx.get("visualObservations"));
        shared=new SharedFactReuse(corpus,ctx,session.model.identity(),VERSION);
        String original=str(ctx,"originalRequirement");
        if(original.isBlank()){sourceIssues.add("REQUIREMENT_SOURCE_NOT_VERIFIED");return;}
        var resolution=RequirementSourceRepair.resolve(cp,original,ctx);scope=resolution.scope();sourceContext=resolution.context();
        if(!scope.resolved()||sourceContext.identity().isEmpty()){sourceIssues.add("REQUIREMENT_SCOPE_UNRESOLVED");return;}
        var expanded=RequirementReferences.expand(cp,scope.obligation(),sourceContext,ctx);sourceContext=expanded.context();
        if(!expanded.issues().isEmpty())sourceIssues.add("REQUIREMENT_REFERENCE_UNRESOLVED");
        Set<String> seen=new HashSet<>();boolean scoped=false;
        for(Map<String,Object> source:sourceContext.sources()){
            String quote=str(source,"sourceQuote");if(quote.isBlank())continue;
            String kind=str(source,"kind");
            if(!scoped&&quote.contains(scope.obligation())){
                Map<String,Object> exact=new LinkedHashMap<>(source);exact.put("sourceQuote",scope.obligation());
                addRequirement(exact,"OBLIGATION",seen);scoped=true;
            }
            if(Set.of("REFERENCE_CLAUSE","SCOPED_PROOF","SCOPED_SCORING_CONTINUATION","SCOPED_BOOK_CONDITION").contains(kind))addRequirement(source,"EXECUTABLE_REFERENCE",seen);
            else if(!quote.equals(scope.obligation()))addRequirement(source,"CONTEXT_ONLY",seen);
        }
        if(!scoped)sourceIssues.add("REQUIREMENT_SCOPED_SPAN_UNRESOLVED");
        if(requirements.isEmpty())sourceIssues.add("REQUIREMENT_SOURCE_NOT_VERIFIED");
    }

    private void addRequirement(Map<String,Object> source,String kind,Set<String> seen){
        String id="T"+identity(kind+JSON.valueToTree(source));if(!seen.add(id))return;
        requirements.add(linked("id",id,"quote",str(source,"sourceQuote"),"documentId",source.get("documentId"),"documentHash",source.get("documentHash"),"documentVersion",source.get("documentVersion"),"locationType",source.get("locationType"),"location",source.get("location"),"scope",kind));
        requirementSpans.put(id,linked("start",source.get("start"),"end",source.get("end"),"sourceHash",source.get("sourceHash")));
    }
    private boolean executableQuote(String quote){return requirements.stream().anyMatch(s->!"CONTEXT_ONLY".equals(str(s,"scope"))&&str(s,"quote").contains(quote));}
    Map<String,Object> execute(AgentInvestigator investigator){
        if(!sourceIssues.isEmpty())return result();
        if(RecoveryReplay.localOnly(ctx)||Boolean.FALSE.equals(ctx.get("recoveryModelAllowed"))){restoreForLocalRecompute();return result();}
        if(investigator==null||!investigator.enabled()){sourceIssues.add("AGENT_SERVICE_UNAVAILABLE");return result();}
        session.record("AGENT_REVIEW_STARTED",Map.of("checkpointId",cp.id()),linked("workflowPolicy",POLICY,"requirementSourceIds",requirements.stream().map(s->s.get("id")).toList()));
        agentAudit=investigator.investigate(start(),this::call,event->{
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            if("model_started".equals(str(event,"event"))&&ctx.get("modelAttemptPermit") instanceof BooleanSupplier permit&&!permit.getAsBoolean())throw new CancellationException("RUN_LEASE_LOST");
            session.record("AGENT_REVIEW_EVENT",Map.of("checkpointId",cp.id()),event);
        });
        return result();
    }

    Map<String,Object> start(){
        List<Map<String,Object>> executable=requirements.stream().filter(s->!"CONTEXT_ONLY".equals(str(s,"scope"))).toList();
        List<Map<String,Object>> contextOnly=requirements.stream().filter(s->"CONTEXT_ONLY".equals(str(s,"scope"))).toList();
        List<String> executableIds=executable.stream().map(s->str(s,"id")).toList();
        List<String> contextOnlyIds=contextOnly.stream().map(s->str(s,"id")).toList();
        List<Map<String,Object>> executableSpans=executable.stream().map(s->{Map<String,Object> span=requirementSpans.getOrDefault(str(s,"id"),Map.of());return linked("sourceId",s.get("id"),"scope",s.get("scope"),"quote",s.get("quote"),
            "documentId",s.get("documentId"),"documentHash",s.get("documentHash"),"documentVersion",s.get("documentVersion"),"locationType",s.get("locationType"),"location",s.get("location"),
            "start",span.get("start"),"end",span.get("end"),"sourceHash",span.get("sourceHash"));}).toList();
        Map<String,Object> checkpointContract=linked("checkpointId",cp.id(),"title",cp.title(),"category",cp.category(),"type",cp.type(),
            "reviewedItem",Objects.toString(cp.expected(),""),"field",Objects.toString(cp.field(),""),"operator",Objects.toString(cp.operator(),""),"unit",Objects.toString(cp.unit(),""),
            "executionRule","只把 executableSourceIds/executableSpans 中的连续原文拆成检查条件；contextOnlySourceIds 只用于识别主体、表头和章节归属，不能新增义务或阈值。");
        Map<String,Object> planContract=linked("methods",METHODS,"deterministicPredicateValues",DETERMINISTIC_PREDICATES,"semanticPredicateValue",SEMANTIC_PREDICATE,
            "crossFieldRules",List.of("method=DETERMINISTIC 时 predicate 必须为 deterministicPredicateValues 中一项。","method=SEMANTIC 时 predicate 只能为 CONTENT。","subject、field、operator、expected、unit 必须保持 requirementQuote 的原文口径，语义条件无对应值时填写空字符串。"));
        return linked("type","start","protocol","bidcheck-agent-1","requestId",UUID.randomUUID().toString(),"auditContext",linked("runId",str(session.context,"sourceRunId"),"checkpointId",cp.id()),"context",
            linked("workflowPolicy",POLICY,"checkpointId",cp.id(),"checkpointContract",checkpointContract,"planContract",planContract,
                "requirement",linked("title",cp.title(),"originalSources",requirements,"reviewedItem",Objects.toString(cp.expected(),""),
                    "executableSourceIds",executableIds,"executableSpans",executableSpans,"contextOnlySourceIds",contextOnlyIds),
                "executableSourceIds",executableIds,"executableSpans",executableSpans,"contextOnlySourceIds",contextOnlyIds,
                "targetObligationIds",List.of(),"allowedFactContracts",List.of(),"targetEvidenceRevision",identity(requirements.toString()),
                "documents",corpus.documents.stream().map(d->linked("id",d.id(),"name",d.name(),"version",d.version(),"sha256",d.sha256(),"pageCount",d.pages().size())).toList(),
                "policy","先按 checkpointContract 从 executableSpans 提出完整计划，contextOnlyIds 只能辅助归属。计划批准后才逐目标取证。数值、身份、日期、证书和社保必须使用程序工具；语义判断须逐项引用正文并独立复核。仅找到标题不算满足；未找到不算缺失。计划采用ALL组合，不能把原文任选/条件分支擅自改成全部必选。盖章存在按签章有效处理，不验证真伪。"),"tools",tools());
    }

    Map<String,Object> call(String name,Map<String,Object> args){
        if(Thread.currentThread().isInterrupted())throw new CancellationException();long began=System.nanoTime();Map<String,Object> out;
        try{
            if(name.equals("submit_review_plan")){planSubmissions++;out=approvePlan(args,false);}
            else if(host==null)out=reject("PLAN_APPROVAL_REQUIRED","先提交完整检查计划，批准后再取证。");
            else if(name.equals("submit_review_judgment"))out=judge(args);
            else if(name.equals("recompute_check"))out=recompute();
            else {
                String target=str(args,"targetObligationId");
                if(Set.of("search_evidence","read_section","read_table_record").contains(name)&&condition(target).isEmpty())return reject("INVALID_TARGET_RECEIPT","读取必须绑定当前计划中的具体目标。");
                out=new LinkedHashMap<>(host.call(name,args));
                if(!target.isBlank())targetSources.computeIfAbsent(target,k->new LinkedHashSet<>()).addAll(ProofVerifier.strings(out.get("sourceIds")));out.remove("computationReceipt");out.remove("businessCheckCompleted");out.remove("targetCheckCompleted");
                Map<String,Object> receipt=recompute();out.put("computationReceipt",receipt);out.put("planRevision",planRevision);
                out.put("targetEvidenceRevision",receipt.get("targetEvidenceRevision"));out.put("targetEvidenceRevisions",receipt.get("targetEvidenceRevisions"));
                out.put("acceptedFactIds",out.getOrDefault("acceptedFactIds",List.of()));
                List<Map<String,Object>> work=new ArrayList<>();for(Map<String,Object> item:RuleEvaluator.maps(out.get("semanticWorkItems"))){Map<String,Object> copy=new LinkedHashMap<>(item);copy.put("evidenceRevision",versions().get(str(item,"targetObligationId")));work.add(copy);}out.put("semanticWorkItems",work);
            }
        }catch(CancellationException e){throw e;}catch(IOException e){out=linked("status","FAILED","error","INDEPENDENT_REVIEW_UNAVAILABLE","diagnostic",RunSession.failure(e));}
        catch(IllegalArgumentException e){out=reject("REVIEW_INPUT_INVALID","计划或证据结构不符合协议。");}
        if(name.equals("submit_review_plan")){
            if("COMPLETED".equals(str(out,"status"))){lastPlanError="";lastPlanViolations=List.of();}
            else {planRejections++;lastPlanError=str(out,"error");lastPlanViolations=RuleEvaluator.maps(out.get("violations"));}
        }
        if(name.equals("submit_review_judgment")&&"REJECTED".equals(str(out,"status"))&&!out.containsKey("semanticWorkItems")&&host!=null){
            String target=str(args,"targetObligationId");Rule rule=plan.rules().stream().filter(r->r.id().equals(target)).findFirst().orElse(null);
            if(rule!=null&&planRevision.equals(str(args,"planRevision"))){out=new LinkedHashMap<>(out);out.put("semanticWorkItems",List.of(semanticWorkflow.observe(rule,target,versions().get(target),List.of(Map.of("code",str(out,"error"))))));out.put("targetEvidenceRevision",revision());out.put("targetEvidenceRevisions",versions());}
        }
        session.record("AGENT_REVIEW_TOOL",Map.of("tool",name,"inputFingerprint",identity(JSON.valueToTree(args).toString())),linked("status",out.getOrDefault("status","COMPLETED"),"error",out.getOrDefault("error",""),"planRevision",planRevision,"elapsedMs",(System.nanoTime()-began)/1_000_000));
        LOG.info("bidcheck.agent_review checkpointId={} tool={} status={} error={} durationMs={}",cp.id(),name,out.get("status"),out.getOrDefault("error",""),(System.nanoTime()-began)/1_000_000);
        return out;
    }

    @SuppressWarnings("unchecked") private Map<String,Object> approvePlan(Map<String,Object> input,boolean replay)throws IOException{
        if(!sourceIssues.isEmpty())return reject("REQUIREMENT_SOURCE_INCOMPLETE",String.join(",",sourceIssues));
        if(!input.keySet().equals(Set.of("conditions","coverageExplanation")))return planViolation("PLAN_SCHEMA_INVALID","$",List.of("conditions","coverageExplanation"),"只提交 conditions 与 coverageExplanation 两个字段。","计划顶层字段不符合契约。","");
        List<Map<String,Object>> proposed=RuleEvaluator.maps(input.get("conditions"));
        if(proposed.isEmpty()||proposed.size()>40)return planViolation("PLAN_SIZE_INVALID","conditions",List.of("1..40"),"提交1至40个条件；过大条款保留原文并按义务分组。","条件数量不符合契约。","");
        Set<String> ids=new HashSet<>();List<Rule> rules=new ArrayList<>();
        String full=RequirementReferences.requirementText(scope.obligation(),sourceContext);
        for(int index=0;index<proposed.size();index++){
            Map<String,Object> c=proposed.get(index);String base="conditions["+index+"]";
            String id=str(c,"obligationId"),quote=str(c,"requirementQuote"),method=str(c,"method");
            if(!c.keySet().equals(CONDITION_KEYS))return planViolation("PLAN_CONDITION_FIELDS_INVALID",base,sorted(CONDITION_KEYS),"逐项填写契约列出的全部字段，不增加字段也不省略字段。","条件字段集合不符合契约。",id);
            if(!id.matches("[A-Za-z0-9_-]{1,80}"))return planViolation("PLAN_CONDITION_ID_INVALID",base+".obligationId",List.of("1至80位 A-Z、a-z、0-9、_、-"),"改用稳定、简短且不含中文或空格的条件ID。","条件ID格式无效。",id);
            if(!ids.add(id))return planViolation("PLAN_CONDITION_ID_DUPLICATE",base+".obligationId",List.of("本计划内唯一ID"),"为重复条件分配不同 obligationId，或合并重复条件。","条件ID重复。",id);
            if(str(c,"description").isBlank())return planViolation("PLAN_DESCRIPTION_REQUIRED",base+".description",List.of("非空条件说明"),"用一句话说明本条件实际核验的义务。","条件说明不能为空。",id);
            if(quote.length()<3)return planViolation("PLAN_REQUIREMENT_QUOTE_TOO_SHORT",base+".requirementQuote",executableSourceIds(),"复制 executableSpans 中至少3个字符的连续原文。","要求引用过短。",id);
            if(!executableQuote(quote)){
                boolean contextOnly=requirements.stream().anyMatch(s->"CONTEXT_ONLY".equals(str(s,"scope"))&&str(s,"quote").contains(quote));
                return planViolation(contextOnly?"PLAN_CONTEXT_ONLY_SOURCE_FORBIDDEN":"PLAN_REQUIREMENT_QUOTE_NOT_EXECUTABLE",base+".requirementQuote",executableSourceIds(),
                    contextOnly?"该文字只能用于主体或章节归属；请从 executableSpans 复制本检查项真正要求的连续原文。":"从 executableSpans 按原字复制连续片段，不要改写、拼接或引用投标文件。",
                    contextOnly?"上下文来源不能生成可执行义务。":"要求引用未落在已授权的可执行招标原文中。",id);
            }
            if(!METHODS.contains(method))return planViolation("PLAN_METHOD_INVALID",base+".method",METHODS,"可计算条件选 DETERMINISTIC；内容响应条件选 SEMANTIC。","执行方式无效。",id);
            if(method.equals("SEMANTIC")){
                if(DETERMINISTIC.contains(str(c,"predicate")))return planViolation("DETERMINISTIC_REVIEW_REQUIRED",base+".method",List.of("DETERMINISTIC"),"该 predicate 属于程序核验类型，method 改为 DETERMINISTIC，并保持原文参数。","程序型谓词不能伪装成语义条件。",id);
                if(!SEMANTIC_PREDICATE.equals(str(c,"predicate")))return planViolation("SEMANTIC_PREDICATE_INVALID",base+".predicate",List.of(SEMANTIC_PREDICATE),"语义内容核验固定填写 CONTENT。","语义条件谓词无效。",id);
                if(quote.matches(COMPUTABLE))return planViolation("DETERMINISTIC_REVIEW_REQUIRED",base+".method",List.of("DETERMINISTIC"),"该原文包含数字、日期、身份、证照、签章或社保约束，改用 DETERMINISTIC 并从15类 predicate 中选择。","可计算条件不能经语义接口绕过程序计算。",id);
                rules.add(new Rule(id,str(c,"description"),"UNSUPPORTED",str(c,"subject"),str(c,"field"),"","","",Map.of("agentSemanticReview",true),quote,"",List.of()));
            }else{
                if(!DETERMINISTIC.contains(str(c,"predicate")))return planViolation("PREDICATE_NOT_SUPPORTED",base+".predicate",DETERMINISTIC_PREDICATES,"从列明的15类程序谓词中选择；纯内容响应改用 SEMANTIC + CONTENT。","程序谓词不受支持。",id);
                Rule rule=AgentReviewPrimitives.bind(c,cp,sourceContext,full,ctx);
                if(rule==null||rule.predicate().equals("UNSUPPORTED"))return planViolation("RULE_NOT_EXECUTABLE",base,List.of("与 requirementQuote 原字匹配的 subject/field/operator/expected/unit"),
                    "保持 predicate 与原文约束一致；subject、field、operator、expected、unit 必须使用原文口径，无法确定时不要猜测。",
                    rule==null?"未生成可执行条件":str(rule.parameters(),"compilationIssue"),id);
                rules.add(rule);
            }
        }
        Map<String,Object> reviewInput=linked("requirementSources",requirements,"conditions",proposed,"coverageExplanation",str(input,"coverageExplanation"));
        List<Map<String,Object>> structural=proposed.stream().map(c->{Map<String,Object> v=new TreeMap<>(c);v.remove("description");v.remove("expectedEvidence");return v;}).toList();
        String revision=identity(VERSION+session.model.identity()+requirementFingerprint()+JSON.valueToTree(structural));Map<String,Object> review;
        AgentReviewContracts.validateInput("REVIEW_PLAN",reviewInput);
        if(replay){review=planReview;AgentReviewContracts.validateOutput("REVIEW_PLAN",JSON.valueToTree(review),reviewInput);if(!Boolean.TRUE.equals(review.get("approved")))return reject("PRIOR_PLAN_REVIEW_MISSING","恢复缺少原批准记录。");}
        else if(reviewCache.containsKey(revision))review=reviewCache.get(revision);
        else {JsonNode response=session.model("REVIEW_PLAN",reviewInput);AgentReviewContracts.validateOutput("REVIEW_PLAN",response,reviewInput);review=JSON.convertValue(response,Map.class);reviewCache.put(revision,review);}
        if(!Boolean.TRUE.equals(review.get("approved"))||!Boolean.TRUE.equals(review.get("coverageComplete")))return linked("status","REJECTED","error","PLAN_COVERAGE_REJECTED","review",review);
        // Idempotent approval does not erase target state or restart evidence acquisition.
        if(revision.equals(planRevision)&&host!=null)return planReceipt();
        Map<String,Source> oldSources=host==null?Map.of():new LinkedHashMap<>(host.disclosed);
        planRevision=revision;conditions=List.copyOf(proposed);coverageExplanation=str(input,"coverageExplanation");planReview=review;semantic.clear();semanticVersions.clear();targetSources.clear();
        plan=approvedPlan(rules,revision);
        host=new EvidenceInvestigation.Host(cp,plan,plan.rules(),corpus,facts,ctx,shared,session,retriever,rejected,Set.of());
        host.disclosed.putAll(oldSources);host.start();
        session.record("AGENT_REVIEW_PLAN_APPROVED",Map.of("planRevision",revision),linked("conditions",conditions,"coverageExplanation",coverageExplanation,"review",review,"requirementFingerprint",requirementFingerprint()));
        return planReceipt();
    }

    private Map<String,Object> planReceipt(){
        Map<String,Object> nativeContext=(Map<String,Object>)host.start().get("context");
        List<String> ids=conditions.stream().map(c->str(c,"obligationId")).toList();
        return linked("status","COMPLETED","planRevision",planRevision,"planReceipt",linked("approved",true,"planRevision",planRevision,"targetObligationIds",ids),
            "targetObligationIds",ids,"allowedFactContracts",nativeContext.get("allowedFactContracts"),"expectedFactContracts",nativeContext.get("allowedFactContracts"),
            "targetEvidenceRevision",nativeContext.get("targetEvidenceRevision"),"targetEvidenceRevisions",versions(),"businessCheckCompleted",false,"targetCheckCompleted",false);
    }

    private Plan approvedPlan(List<Rule> agentRules,String revision){
        // Integration seam for a future server-owned trusted Plan tree. Raw model or
        // request context must never become execution authority here; the current
        // compatibility path remains the independently reviewed flat ALL plan.
        return new Plan("ALL",List.copyOf(agentRules),POLICY,List.of(),List.of(),linked("sourceContext",sourceContext.map(),"agentPlanRevision",revision,"planAuthority","INDEPENDENTLY_REVIEWED_AGENT_PLAN"));
    }

    @SuppressWarnings("unchecked") private Map<String,Object> judge(Map<String,Object> args)throws IOException{
        String target=str(args,"targetObligationId");Map<String,Object> condition=condition(target);
        if(condition.isEmpty()||!planRevision.equals(str(args,"planRevision")))return reject("REVIEW_PLAN_REVISION_MISMATCH","判断必须绑定当前已批准计划和目标。");
        if(!"SEMANTIC".equals(str(condition,"method")))return reject("DETERMINISTIC_REVIEW_REQUIRED","本条件只能使用程序计算结果。");
        String status=str(args,"status");if(!Set.of("PASS","FAIL","UNDETERMINED").contains(status)||str(args,"reason").isBlank())return reject("JUDGMENT_SCHEMA_INVALID","判断状态或说明无效。");
        if("UNDETERMINED".equals(status))return reject("SEMANTIC_EVIDENCE_INCOMPLETE","尚无确定结论；保留具体缺口并停止重复提交。");
        List<Map<String,Object>> citations=RuleEvaluator.maps(args.get("evidence")),checks=RuleEvaluator.maps(args.get("conditionChecks"));
        if(citations.isEmpty()||checks.isEmpty()||!ProofVerifier.strings(args.get("remainingGaps")).isEmpty())return reject("SEMANTIC_EVIDENCE_INCOMPLETE","有缺口或没有逐条件正文证据，不能形成确定结论。");
        List<Evidence> evidences=new ArrayList<>();Set<String> sourceIds=new LinkedHashSet<>();
        for(Map<String,Object> citation:citations){Source source=host.disclosed.get(str(citation,"sourceId"));String quote=str(citation,"quote");
            if(source==null||quote.length()<12||!source.evidence().quote().contains(quote))return reject("SEMANTIC_SOURCE_INVALID","只能引用已读取来源中的连续正文，不能编造或拼接引文。");
            if(source.index()||source.historical()||source.scopeContext().blocked())return reject("SEMANTIC_SOURCE_SCOPE_INVALID","目录或历史/归属冲突来源不能证明当前要求。");
            if(!source.evidence().quote().contains("\n")&&source.evidence().quote().strip().equals(quote.strip())&&quote.length()<35)return reject("TITLE_ONLY_EVIDENCE","仅材料标题不足以证明内容要求。");
            sourceIds.add(source.id());Evidence e=source.evidence();evidences.add(new Evidence(e.documentId(),e.documentName(),e.page(),e.locationType(),quote,e.anchor(),e.confidence()));
        }
        for(Map<String,Object> check:checks){String req=str(check,"requirementQuote"),sourceId=str(check,"evidenceSourceId"),quote=str(check,"evidenceQuote");Source source=host.disclosed.get(sourceId);
            if(req.length()<3||!str(condition,"requirementQuote").contains(req)||!sourceIds.contains(sourceId)||quote.length()<12||source==null||!source.evidence().quote().contains(quote)||str(check,"explanation").isBlank())return reject("CONDITION_EVIDENCE_INVALID","逐条件说明必须同时定位招标要求和投标原文。");
        }
        List<String> atoms=Arrays.stream(str(condition,"requirementQuote").split("[；;\\n]+"))
            .map(String::strip).filter(v->v.length()>=3).toList();
        if(atoms.stream().anyMatch(atom->checks.stream().noneMatch(check->str(check,"requirementQuote").contains(atom))))return reject("CONDITION_EVIDENCE_INVALID","每个分句义务都须有对应原文证据，不能只引用其中几个字。");
        Set<String> bound=targetSources.computeIfAbsent(target,k->new LinkedHashSet<>());bound.addAll(sourceIds);
        host.recompute();
        Rule targetRule=plan.rules().stream().filter(r->r.id().equals(target)).findFirst().orElseThrow();
        Map<String,Object> workState=semanticWorkflow.state(targetRule,target,versions().get(target));
        if(Boolean.TRUE.equals(workState.get("targetExhaustedForRevision")))return linked("status","REJECTED","error","SEMANTIC_REVIEW_REJECTED","semanticWorkItems",List.of(workState),"targetEvidenceRevision",revision(),"targetEvidenceRevisions",versions());
        Map<String,Object> proposal=linked("status",status,"reason",str(args,"reason"),"evidence",citations,"conditionChecks",checks,"remainingGaps",List.of());
        List<Map<String,Object>> sources=sourceIds.stream().map(host.disclosed::get).map(AgentLedReview::reviewSource).toList();
        Map<String,Object> input=linked("requirementSources",requirements,"conditions",List.of(condition),"bidSources",sources,"proposal",proposal);
        String key=identity(planRevision+versions().get(target)+JSON.valueToTree(input));Map<String,Object> review=reviewCache.get(key);
        if(review==null){JsonNode response=session.model("REVIEW_JUDGMENT",input);AgentReviewContracts.validateOutput("REVIEW_JUDGMENT",response,input);review=JSON.convertValue(response,Map.class);reviewCache.put(key,review);}
        if(!Boolean.TRUE.equals(review.get("approved"))||!status.equals(str(review,"status"))){
            Map<String,Object> work=semanticWorkflow.observe(targetRule,target,versions().get(target),List.of(Map.of("code","SEMANTIC_REVIEW_REJECTED")));
            return linked("status","REJECTED","error","SEMANTIC_REVIEW_REJECTED","review",review,"semanticWorkItems",List.of(work),"targetEvidenceRevision",revision(),"targetEvidenceRevisions",versions());
        }
        Map<String,Object> proof=linked("method","SOURCE_GROUNDED_SEMANTIC_REVIEW","planRevision",planRevision,"reviewInputFingerprint",key,"independentReview",review,"conditionChecks",checks,"sourceIds",sourceIds,"modelIdentity",session.model.identity());
        semantic.put(target,new Decision(target,str(condition,"description"),status,str(args,"reason"),List.copyOf(evidences),List.of(),proof));semanticVersions.put(target,versions().get(target));
        session.record("AGENT_REVIEW_JUDGMENT_ACCEPTED",Map.of("targetObligationId",target,"planRevision",planRevision),linked("proposal",proposal,"review",review,"sourceIds",sourceIds,"reviewInputFingerprint",key,"targetObligationId",target,"planRevision",planRevision,"targetEvidenceRevision",versions().get(target),"dependencySources",new ArrayList<>(bound)));
        Map<String,Object> receipt=recompute();
        return linked("status","COMPLETED","planRevision",planRevision,"targetEvidenceRevision",receipt.get("targetEvidenceRevision"),"targetEvidenceRevisions",receipt.get("targetEvidenceRevisions"),"computationReceipt",receipt);
    }

    private static Map<String,Object> reviewSource(Source s){return linked("id",s.id(),"quote",s.evidence().quote(),"documentId",s.evidence().documentId(),"documentHash",s.documentHash(),"documentVersion",s.documentVersion(),"location",s.evidence().page(),"locationType",s.evidence().locationType(),"historical",s.historical(),"index",s.index(),"scope",s.scopeContext().status());}
    private Map<String,Object> condition(String id){return conditions.stream().filter(c->id.equals(str(c,"obligationId"))).findFirst().orElse(Map.of());}
    private String requirementFingerprint(){return identity(JSON.valueToTree(requirements).toString());}
    private Map<String,String> versions(){
        if(host==null)return Map.of();Map<String,String> versions=new LinkedHashMap<>();
        for(Rule r:plan.rules()){
            // Typed fields use relevant facts. Semantic results depend on the complete
            // target-specific read set; unrelated goal reads cannot reopen completed goals.
            boolean semanticMode="SEMANTIC".equals(str(condition(r.id()),"method"));
            String records=targetSources.getOrDefault(r.id(),Set.of()).stream().map(host.disclosed::get).filter(Objects::nonNull)
                .map(s->s.id()+":"+s.documentHash()+":"+s.documentVersion()+":"+identity(s.evidence().quote())).sorted().collect(Collectors.joining("|"));
            String data=records+(semanticMode?"":facts.values().stream().filter(f->AgentInvestigationScope.relevant(f,List.of(r),facts.values(),corpus)).map(f->f.id()+":"+identity(f.toString())).sorted().collect(Collectors.joining("|")));
            versions.put(r.id(),identity(planRevision+data));
        }return versions;
    }
    private String revision(){return identity(versions().toString());}
    Map<String,Object> recompute(){
        if(host==null)return linked("status","COMPLETED","businessCheckCompleted",false,"targetCheckCompleted",false,"planRevision",planRevision,"obligations",List.of());
        host.recompute();Map<String,String> versions=versions();List<Decision> decisions=new ArrayList<>();
        for(Rule r:plan.rules()){
            Decision decision=host.evaluator.decisions.get(r.id());
            if("SEMANTIC".equals(str(condition(r.id()),"method"))){
                decision=semantic.get(r.id());
                if(decision==null||!versions.get(r.id()).equals(semanticVersions.get(r.id())))decision=new Decision(r.id(),r.description(),"UNDETERMINED","语义条件尚未完成独立证据复核",List.of(),List.of(gap("AGENT_SEMANTIC_REVIEW_PENDING","需要逐条件原文证据与独立复核")),Map.of());
            }
            if(decision!=null)decisions.add(decision);
        }
        Decision combined=RuleEvaluator.combine(cp.id(),cp.title(),"ALL",decisions);
        combined=VerificationEngine.lookupCoverageGuard(combined,shared.factLookupTruncated(),shared.lookupTruncations());
        boolean complete=decisions.size()==conditions.size()&&!decisions.isEmpty()&&combined.gaps().isEmpty()&&Set.of("PASS","FAIL","NOT_APPLICABLE").contains(combined.status());
        return linked("kind","HOST_RULE_RECOMPUTE","status","COMPLETED","planRevision",planRevision,"businessCheckCompleted",complete,"targetCheckCompleted",complete,"businessDecision",combined.status(),"targetEvidenceRevision",revision(),"targetEvidenceRevisions",versions,
            "obligations",decisions.stream().map(Decision::map).toList(),"gaps",combined.gaps().stream().map(Gap::map).toList(),"reason",combined.reason(),"evidences",combined.evidences(),"remainingTargetObligationIds",decisions.stream().filter(d->!AgentInvestigationScope.closed(d)).map(Decision::id).toList(),"remainingGapIds",combined.gaps().stream().map(Gap::code).distinct().toList());
    }

    private void restoreForLocalRecompute(){
        if(!(ctx.get("previousAtomicCheck") instanceof Map<?,?> previous)||!(previous.get("agentReview") instanceof Map<?,?> audit)||!requirementFingerprint().equals(audit.get("requirementFingerprint"))||!session.model.identity().equals(audit.get("modelIdentity"))){sourceIssues.add("AGENT_PRIOR_PLAN_UNAVAILABLE");return;}
        // Only a persisted host approval step can authorize local plan reuse. A proposed
        // plan or an arbitrary proof object in a previous decision is insufficient.
        String expected=Objects.toString(audit.get("planRevision"),"");Map<String,Object> approved=AgentReviewState.findApprovedPlan(session,requirementFingerprint(),expected);
        if(approved.isEmpty()||!(approved.get("review") instanceof Map<?,?> rawReview)){sourceIssues.add("AGENT_PRIOR_PLAN_UNAVAILABLE");return;}
        planReview=JSON.convertValue(rawReview,Map.class);
        try{Map<String,Object> restored=approvePlan(linked("conditions",approved.get("conditions"),"coverageExplanation",str(approved,"coverageExplanation")),true);
            if(host==null||!expected.equals(planRevision)||!"COMPLETED".equals(str(restored,"status"))){sourceIssues.add("AGENT_PRIOR_PLAN_CHANGED");return;}
            List<Source> sources=RecoveryReplay.sources(ctx,corpus,session.model.identity());ProofVerifier.Verified replay=RecoveryReplay.facts(ctx,corpus,sources,RuleEvaluator.maps(ctx.get("_validatedLayoutCells")),session.model.identity());
            replay.facts().forEach(f->facts.put(f.id(),f));sources.forEach(s->host.disclosed.put(s.id(),s));host.recompute();
            agentAudit=linked("status","LOCAL_RECOMPUTE","modelCalls",0,"policyVersion",VERSION);
            // Revalidate each persisted judgment against its own original source/version and
            // exact review input; only unchanged goals retain their prior conclusion.
            for(Map<String,Object> saved:AgentReviewState.acceptedJudgments(session,planRevision)){
                for(String id:ProofVerifier.strings(saved.get("dependencySources"))){Source source=corpus.byId.get(id);if(source!=null)host.disclosed.put(id,source);}
                restoreJudgment(saved);
            }
            session.record("AGENT_REVIEW_LOCAL_RECOMPUTE",Map.of("planRevision",planRevision),linked("restoredSemanticTargets",semantic.keySet(),"modelCalls",0));
        }catch(IOException e){sourceIssues.add("AGENT_PRIOR_PLAN_INVALID");}
    }

    Map<String,Object> result(){
        Map<String,Object> receipt=recompute();List<Map<String,Object>> gaps=new ArrayList<>(RuleEvaluator.maps(receipt.get("gaps")));
        sourceIssues.forEach(code->gaps.add(gap(code,"主审尚未建立完整可靠的要求与执行上下文；这不代表投标材料有问题。").map()));
        if(host==null&&sourceIssues.isEmpty()){
            String code=planFailureCode();
            String description=switch(code){
                case "AGENT_PLAN_NOT_SUBMITTED"->"主审未提交检查计划，尚未开始投标证据核验。";
                case "AGENT_PLAN_REVIEW_REJECTED"->"检查计划未通过独立完整性复核，尚未开始投标证据核验；这不代表投标材料缺失。";
                case "AGENT_PLAN_REVIEW_UNAVAILABLE"->"检查计划的独立复核未完成，尚未开始投标证据核验；这不代表投标材料缺失。";
                default->"主审提交的检查计划未通过协议校验，尚未开始投标证据核验；这不代表投标材料缺失。";
            };
            gaps.add(linked("code",code,"description",description,"requiredInput",description,"resumeTrigger","PLAN_CONTRACT_CORRECTED",
                "planSubmissions",planSubmissions,"planRejections",planRejections,"lastPlanError",lastPlanError,"violations",lastPlanViolations));
        }
        boolean complete=Boolean.TRUE.equals(receipt.get("businessCheckCompleted"))&&gaps.isEmpty();
        if(!complete&&"FAILED".equals(str(agentAudit,"status")))gaps.add(gap("AGENT_REVIEW_INCOMPLETE","主审执行中断或无新证据，保留已核证据并等待相关输入变化。").map());
        Map<String,Object> audit=linked("version",VERSION,"workflowPolicy",POLICY,"mode","AGENT_LED","planRevision",planRevision,"requirementFingerprint",requirementFingerprint(),"modelIdentity",session.model.identity(),"independentReview",planReview,"semanticConditionCount",conditions.stream().filter(c->"SEMANTIC".equals(str(c,"method"))).count(),"agent",agentAudit,"localRecomputeOnly",RecoveryReplay.localOnly(ctx)||Boolean.FALSE.equals(ctx.get("recoveryModelAllowed")));
        if(host!=null)session.record(RecoveryReplay.FACT_SNAPSHOT,Map.of("checkpointId",cp.id()),RecoveryReplay.snapshot(corpus,facts.values()));
        return linked("checkpointId",cp.id(),"title",cp.title(),"businessDecision",complete||"FAIL".equals(receipt.get("businessDecision"))?receipt.get("businessDecision"):"UNDETERMINED","decisionBasis","AGENT_LED_SOURCE_REVIEW","workflowState",complete?"COMPLETED":workflow(gaps),"complete",complete,
            "obligations",receipt.getOrDefault("obligations",List.of()),"gaps",gaps,"evidences",receipt.getOrDefault("evidences",List.of()),"reason",complete?receipt.get("reason"):"主审仍有未闭合条件；已完成的逐项证明保留。","proof",Map.of("agentPlanRevision",planRevision),"plan",plan==null?Map.of():plan,"facts",new ArrayList<>(facts.values()),"relations",facts.values().stream().filter(f->Set.of("PERSON_ROLE","RELATION").contains(f.kind())).toList(),"visualRequests",host==null?List.of():host.visualRequests,"externalRequests",externalRequests(),"recoveryEvidenceDependencies",host==null?List.of():VerificationEngine.evidenceDependencies(plan.rules(),plan.rules(),shared.coverage(),ctx,session.model.identity(),host.evaluator),"steps",session.steps,"agentReview",audit,"agentInvestigation",agentAudit,"modelDiagnostics",session.diagnostics(),"compilationAudit",Map.of("method",POLICY,"approved",host!=null),"recoveryActions",List.of(),"rejectedFacts",rejected,"fieldCoverage",shared.coverage(),
            "metrics",linked("engineVersion",VERSION,"modelCalls",session.modelCalls+RunSession.number(agentAudit.get("modelCalls"),0),"independentReviewCalls",session.modelCalls,"reusedFacts",shared.reusedFacts(),"facts",facts.size(),
                "planSubmissions",planSubmissions,"planRejections",planRejections,"planApproved",host!=null,"lastPlanError",lastPlanError,"lastPlanViolationCount",lastPlanViolations.size(),
                "elapsedMs",(System.nanoTime()-started)/1_000_000,"stopReason",complete?"AGENT_REVIEW_COMPLETED":host==null?planFailureCode():"AGENT_REVIEW_WAIT_RELEVANT_CHANGE"));
    }
    private void restoreJudgment(Map<String,Object> saved)throws IOException{
        String target=str(saved,"targetObligationId");Map<String,Object> c=condition(target);
        if(c.isEmpty()||!"SEMANTIC".equals(str(c,"method"))||!(saved.get("proposal") instanceof Map<?,?> rawProposal)||!(saved.get("review") instanceof Map<?,?> rawReview))return;
        List<String> dependencies=ProofVerifier.strings(saved.get("dependencySources"));
        if(dependencies.isEmpty()||dependencies.stream().anyMatch(id->!host.disclosed.containsKey(id)))return;
        targetSources.put(target,new LinkedHashSet<>(dependencies));
        if(!versions().get(target).equals(str(saved,"targetEvidenceRevision")))return;
        Map<String,Object> proposal=JSON.convertValue(rawProposal,Map.class),review=JSON.convertValue(rawReview,Map.class);
        List<String> sourceIds=ProofVerifier.strings(saved.get("sourceIds"));List<Evidence> evidences=new ArrayList<>();
        for(Map<String,Object> citation:RuleEvaluator.maps(proposal.get("evidence"))){Source source=host.disclosed.get(str(citation,"sourceId"));String quote=str(citation,"quote");if(source==null||quote.isBlank()||source.index()||source.historical()||source.scopeContext().blocked()||!source.evidence().quote().contains(quote))return;Evidence e=source.evidence();evidences.add(new Evidence(e.documentId(),e.documentName(),e.page(),e.locationType(),quote,e.anchor(),e.confidence()));}
        if(sourceIds.isEmpty()||sourceIds.stream().anyMatch(id->!host.disclosed.containsKey(id)))return;
        Map<String,Object> input=linked("requirementSources",requirements,"conditions",List.of(c),"bidSources",sourceIds.stream().map(host.disclosed::get).map(AgentLedReview::reviewSource).toList(),"proposal",proposal);
        String key=identity(planRevision+versions().get(target)+JSON.valueToTree(input));if(!key.equals(str(saved,"reviewInputFingerprint")))return;
        AgentReviewContracts.validateInput("REVIEW_JUDGMENT",input);AgentReviewContracts.validateOutput("REVIEW_JUDGMENT",JSON.valueToTree(review),input);
        if(!Boolean.TRUE.equals(review.get("approved")))return;
        semantic.put(target,new Decision(target,str(c,"description"),str(proposal,"status"),str(proposal,"reason"),evidences,List.of(),linked("method","SOURCE_GROUNDED_SEMANTIC_REVIEW","restored",true,"planRevision",planRevision,"reviewInputFingerprint",key,"independentReview",review,"sourceIds",sourceIds)));semanticVersions.put(target,versions().get(target));
    }
    private static String workflow(List<Map<String,Object>> gaps){
        if(gaps.stream().anyMatch(g->str(g,"code").startsWith("AGENT_")||str(g,"code").startsWith("REQUIREMENT_")))return "ENGINE_FAILURE";
        if(gaps.stream().anyMatch(g->str(g,"code").contains("WAIT_STAGE")))return "WAIT_STAGE";return "WAIT_INPUT";
    }
    private List<Map<String,Object>> externalRequests(){
        if(host==null)return List.of();return plan.rules().stream().filter(r->r.predicate().equals("EXTERNAL")).filter(r->!AgentInvestigationScope.closed(host.evaluator.decisions.get(r.id()))).flatMap(r->host.evaluator.resolvedSubjects(r).stream().map(subject->linked("obligationId",r.id(),"type",str(r.parameters(),"externalType").isBlank()?r.field():str(r.parameters(),"externalType"),"subject",subject,"referenceDate",ctx.get("referenceDate"),"field",r.field(),"operator",r.operator(),"expected",r.expected()))).toList();
    }
    private String planFailureCode(){
        if(planSubmissions==0)return "AGENT_PLAN_NOT_SUBMITTED";
        if("PLAN_COVERAGE_REJECTED".equals(lastPlanError))return "AGENT_PLAN_REVIEW_REJECTED";
        if("INDEPENDENT_REVIEW_UNAVAILABLE".equals(lastPlanError))return "AGENT_PLAN_REVIEW_UNAVAILABLE";
        return "AGENT_PLAN_PROTOCOL_FAILED";
    }
    private List<String> executableSourceIds(){return requirements.stream().filter(s->!"CONTEXT_ONLY".equals(str(s,"scope"))).map(s->str(s,"id")).toList();}
    private static List<String> sorted(Collection<String> values){return values.stream().sorted().toList();}
    private static Map<String,Object> planViolation(String code,String fieldPath,List<?> allowedValues,String correctiveAction,String reason,String conditionId){
        Map<String,Object> violation=linked("code",code,"conditionId",conditionId,"fieldPath",fieldPath,"allowedValues",allowedValues,"correctiveAction",correctiveAction);
        return linked("status","REJECTED","error",code,"reason",reason,"violations",List.of(violation));
    }
    private static Map<String,Object> reject(String code,String reason){return linked("status","REJECTED","error",code,"reason",reason);}
    private static Map<String,Object> object(Map<String,Object> fields){return linked("type","object","properties",fields,"required",new ArrayList<>(fields.keySet()),"additionalProperties",false);}
    private static Map<String,Object> tool(String name,String description,Map<String,Object> fields){return linked("name",name,"description",description,"parameters",object(fields));}
    private static Map<String,Object> array(Object item){return linked("type","array","items",item);}
    private static Map<String,Object> text(String description,int min,int max){Map<String,Object> schema=new LinkedHashMap<>();schema.put("type","string");schema.put("description",description);schema.put("minLength",min);schema.put("maxLength",max);return schema;}
    static List<Map<String,Object>> tools(){
        Map<String,Object> string=Map.of("type","string"),condition=new LinkedHashMap<>();
        condition.put("obligationId",linked("type","string","pattern","^[A-Za-z0-9_-]{1,80}$","description","本计划内唯一且稳定的条件ID。"));
        condition.put("description",text("一句话说明本条件核验的义务。",1,300));
        condition.put("requirementQuote",text("从 executableSpans 复制的连续招标原文；不得改写、拼接或取自 contextOnlyIds。",3,6000));
        condition.put("method",linked("type","string","enum",METHODS,"description","DETERMINISTIC 用于数值、日期、人员、证书、社保、关系和外部/视觉程序核验；SEMANTIC 仅用于方案正文内容响应。"));
        condition.put("predicate",linked("description","与 method 交叉约束：DETERMINISTIC 从精确15项中选择；SEMANTIC 只能选 CONTENT。",
            "oneOf",List.of(linked("type","string","enum",DETERMINISTIC_PREDICATES,"description","method=DETERMINISTIC 的15类程序谓词。"),
                linked("type","string","enum",List.of(SEMANTIC_PREDICATE),"description","method=SEMANTIC 的唯一内容谓词。"))));
        condition.put("subject",text("原条款约束的主体或人员岗位；没有明确主体时填空字符串，不得猜测公司或姓名。",0,300));
        condition.put("field",text("原条款核验字段，如 certificate、socialInsurance、报价字段或方案主题；保持原文口径。",0,300));
        condition.put("operator",text("原条款比较关系，如 >=、<=、=、PRESENT、RESPONDS；没有比较关系时填空字符串。",0,80));
        condition.put("expected",text("原条款期望值或阈值，只抄原文值；没有明确值时填空字符串。",0,500));
        condition.put("unit",text("原条款单位，如 人、家、元、万元、%、日、月；无单位时填空字符串。",0,80));
        condition.put("expectedEvidence",text("说明需要寻找的投标原文证据类型，不得在这里预设通过结论。",1,500));
        List<Map<String,Object>> tools=new ArrayList<>();for(Map<String,Object> raw:EvidenceInvestigation.tools()){
            if(Set.of("search_evidence","read_section","read_table_record").contains(str(raw,"name"))){Map<String,Object> readSchema=JSON.convertValue(raw.get("parameters"),Map.class);Map<String,Object> readFields=new LinkedHashMap<>((Map<String,Object>)readSchema.get("properties"));readFields.put("targetObligationId",string);tools.add(tool(str(raw,"name"),str(raw,"description")+"绑定本次目标，读取只影响该目标的证据版本。",readFields));continue;}
            if(!"submit_candidate_fact".equals(str(raw,"name"))){tools.add(raw);continue;}
            Map<String,Object> schema=JSON.convertValue(raw.get("parameters"),Map.class);Map<String,Object> fields=new LinkedHashMap<>((Map<String,Object>)schema.get("properties"));fields.put("targetObligationId",string);tools.add(tool("submit_candidate_fact","向当前已批准目标提交来源事实。只接受原文，不接受业务结论。",fields));
        }
        tools.add(tool("submit_review_plan","只根据 executableSpans 与 checkpointContract 提出全部义务；contextOnlyIds 不能生成条件。method=DETERMINISTIC 时 predicate 必须从精确15类中选择，method=SEMANTIC 时只能使用 CONTENT。计划经独立覆盖复核后才能取证。",linked("conditions",array(object(condition)),"coverageExplanation",text("说明每个可执行条款片段如何被条件覆盖；不得把上下文扩展成新义务。",1,2000))));
        Map<String,Object> evidence=object(linked("sourceId",string,"quote",string));Map<String,Object> check=object(linked("requirementQuote",string,"evidenceSourceId",string,"evidenceQuote",string,"explanation",string));
        tools.add(tool("submit_review_judgment","提交语义条件的逐项原文判断，宿主独立复核。不是直接发布结论；程序型条件只能recompute_check。",linked("targetObligationId",string,"planRevision",string,"status",linked("type","string","enum",List.of("PASS","FAIL","UNDETERMINED")),"reason",string,"evidence",array(evidence),"conditionChecks",array(check),"remainingGaps",array(string))));return List.copyOf(tools);
    }
}
