package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.retrieval.HybridRetriever;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.stream.Collectors;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Incremental atomic verification, with no writes outside the caller-provided durable step observer. */
@Service
public final class VerificationEngine {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(VerificationEngine.class);
    private static final String SERVER_AGENT_PLAN_POLICY="server-plan-agent-evidence-1";
    private final ModelGateway model;private final HybridRetriever retriever;private final ObjectMapper json=new ObjectMapper();
    private cn.bidcheck.verification.agent.AgentInvestigator investigator;
    @Autowired(required=false) public void setInvestigator(cn.bidcheck.verification.agent.AgentInvestigator investigator){this.investigator=investigator;}
    @Autowired public VerificationEngine(VerificationModelClient model,HybridRetriever retriever){this((ModelGateway)model,retriever);}
    public VerificationEngine(ModelGateway model,HybridRetriever retriever){this.model=Objects.requireNonNull(model);this.retriever=retriever;}
    public VerificationEngine(){this(ModelGateway.disabled(),new HybridRetriever());}
    public String identity(){return VerificationTypes.identity(VERSION+CompilationRecovery.VERSION+ExtractionBatches.POLICY+EvidenceInvestigation.VERSION+SERVER_AGENT_PLAN_POLICY+SameFieldSourceOccurrences.VERSION+"closed-dsl-source-proof-1"+model.identity()+(retriever==null?"":retriever.identity())+(investigator==null?"":investigator.identity()));}
    public Map<String,Object> evaluate(Checkpoint checkpoint,List<Document> bidDocuments,Document tender,Map<String,Object> context){
        Objects.requireNonNull(checkpoint);Map<String,Object> ctx=new LinkedHashMap<>(context==null?cn.bidcheck.compat.Java8Api.mapOf():context);List<Document> bids=cn.bidcheck.compat.Java8Api.listCopyOf(bidDocuments==null?cn.bidcheck.compat.Java8Api.listOf():bidDocuments);long start=System.nanoTime();
        ctx.put("checkpointId",checkpoint.id());
        if(!checkpoint.enabled())return simple(checkpoint,"NOT_APPLICABLE","检查点未启用","DISABLED");
        if(!checkpoint.confirmed())return simple(checkpoint,"UNDETERMINED","检查点尚未确认","REQUIREMENT_UNCONFIRMED");
        if(bids.isEmpty())return simple(checkpoint,"UNDETERMINED","尚未提供本次投标资料","MISSING_BID_DOCUMENT");
        String project=bids.get(0).projectId();if(project==null||bids.stream().anyMatch(d->!project.equals(d.projectId())||d.id()==null||d.sha256()==null||d.pages()==null)||(tender!=null&&!project.equals(tender.projectId())))throw new IllegalArgumentException("核验资料必须属于同一项目且具有冻结版本");
        if(bids.stream().map(Document::id).distinct().count()!=bids.size())throw new IllegalArgumentException("核验资料 ID 重复");
        ctx.put("referenceDateSourceVerified",false);ctx.remove("originalRequirement");
        // Per-condition events remain separate: an opening date must not silently replace the bid deadline.
        if(ctx.get("referenceDates") instanceof Map<?,?>){
            Map<String,Object> verifiedEvents=new LinkedHashMap<>();
            for(String event:cn.bidcheck.compat.Java8Api.listOf("BID_DEADLINE", "OPENING_DATE"))if(((Map<?,?>) (ctx.get("referenceDates"))).get(event) instanceof Map<?,?>){
                Map<String,Object> copy=new LinkedHashMap<>();((Map<?,?>) (((Map<?,?>) (ctx.get("referenceDates"))).get(event))).forEach((k,v)->copy.put(String.valueOf(k),v));
                copy.put("referenceDateSourceVerified",referenceSourceValid(copy.get("referenceDateEvidence"),tender,ctx,project));verifiedEvents.put(event,copy);
            }
            ctx.put("referenceDates",verifiedEvents);
        }
        if(ctx.get("referenceDateEvidence") instanceof Map<?,?>){String doc=Objects.toString(((Map<?,?>) (ctx.get("referenceDateEvidence"))).get("documentId"),"");int loc=RunSession.number(((Map<?,?>) (ctx.get("referenceDateEvidence"))).get("page"),-1);String quote=Objects.toString(((Map<?,?>) (ctx.get("referenceDateEvidence"))).get("quote"),"");List<Document> tenderSources=new ArrayList<>();if(tender!=null)tenderSources.add(tender);if(ctx.get("tenderDocuments") instanceof List<?>)for(Object item:((List<?>) (ctx.get("tenderDocuments"))))if(item instanceof Document&&project.equals(((Document) (item)).projectId())&&"TENDER".equals(((Document) (item)).role()))tenderSources.add(((Document) (item)));ctx.put("referenceDateSourceVerified",!cn.bidcheck.compat.Java8Api.isBlank(quote)&&tenderSources.stream().anyMatch(d->d.id().equals(doc)&&d.pages().stream().anyMatch(p->p.number()==loc&&p.locationType().equals(((Map<?,?>) (ctx.get("referenceDateEvidence"))).get("locationType"))&&p.text().contains(quote))));}
        String scope=VerificationTypes.identity(checkpoint.id()+bids.stream().map(d->d.id()+":"+d.version()+":"+d.sha256()+":"+VerificationTypes.identity(d.pages().toString())).collect(Collectors.joining("|")));
        List<Document> sourceDocuments=new ArrayList<>();if(tender!=null)sourceDocuments.add(tender);if(ctx.get("tenderDocuments") instanceof List<?>)for(Object value:((List<?>) (ctx.get("tenderDocuments"))))if(value instanceof Document&&project.equals(((Document) (value)).projectId())&&"TENDER".equals(((Document) (value)).role()))sourceDocuments.add(((Document) (value)));
        ctx.put("tenderDocuments",cn.bidcheck.compat.Java8Api.toList(sourceDocuments.stream().collect(Collectors.toMap(Document::id,d->d,(a,b)->a,LinkedHashMap::new)).values().stream()));
        boolean agentLed="AGENT_LED".equals(str(ctx,"reviewMode"));
        // In Agent-led mode the server remains the sole rule author.  The SDK may choose
        // evidence reads, but it cannot replace the source-grounded condition tree with a
        // model-authored flat plan.  An incoming compatibility flag must not weaken this.
        if(agentLed){
            ctx.put("allowModelRuleCompilation",false);ctx.put("agentPlanAuthority","SERVER_REQUIREMENT_COMPILER");
            if(ctx.get("previousAtomicCheck") instanceof Map<?,?>&&((Map<?,?>) (ctx.get("previousAtomicCheck"))).get("plan")!=null){
                Object rawReview=((Map<?,?>) (ctx.get("previousAtomicCheck"))).get("agentReview");boolean trusted=rawReview instanceof Map<?,?>&&"SERVER_REQUIREMENT_COMPILER".equals(Objects.toString(((Map<?,?>) (rawReview)).get("planAuthority"),""));
                if(!trusted){
                    ctx.put("agentPriorPlanRejected","NON_SERVER_PLAN");
                    // Keep recovery evidence and target IDs, but prevent both local plan replay and
                    // preservation against the rejected model plan.  The ordinary deterministic
                    // compiler creates a fresh tree; prior facts still pass their normal source checks.
                    ctx.put("recoveryStrategy","TARGETED_EVIDENCE");ctx.put("recoveryMode","TARGETED");
                }
            }
        }
        boolean localRecovery=RecoveryReplay.localOnly(ctx);
        RunSession session=new RunSession(model,ctx,scope);
        ctx.put("inferenceAudit",(java.util.function.Consumer<Map<String,Object>>)event->session.record(str(event,"type"),event.getOrDefault("input",cn.bidcheck.compat.Java8Api.mapOf()),event.getOrDefault("output",cn.bidcheck.compat.Java8Api.mapOf())));
        ctx.put("sharedFactAdoptionAudit",(java.util.function.Consumer<Map<String,Object>>)event->session.record("SHARED_FACT_ADOPTION",linked("lookupPhase",event.getOrDefault("lookupPhase","")),event));
        if(checkpoint.source()!=null){List<Document> requirementSources=new ArrayList<>();if(tender!=null)requirementSources.add(tender);if(ctx.get("tenderDocuments") instanceof List<?>)for(Object value:((List<?>) (ctx.get("tenderDocuments"))))if(value instanceof Document&&project.equals(((Document) (value)).projectId())&&"TENDER".equals(((Document) (value)).role()))requirementSources.add(((Document) (value)));if(requirementSources.stream().anyMatch(d->sourceValid(checkpoint.source(),d)))ctx.put("originalRequirement",checkpoint.source().quote());}
        EvidenceCorpus corpus=new EvidenceCorpus(bids);corpus.addVisualObservations(ctx.get("visualObservations"));SharedFactReuse shared=new SharedFactReuse(corpus,ctx,model.identity(),identity());Plan plan;List<String> issues=new ArrayList<>();List<Map<String,Object>> rejected=new ArrayList<>();Map<String,Fact> facts=new LinkedHashMap<>();List<Source> candidates=cn.bidcheck.compat.Java8Api.listOf();
        InferenceClaims.confirmed(ctx,corpus).forEach(f->facts.put(f.id(),f));
        Map<String,Object> serverPlanAudit=cn.bidcheck.compat.Java8Api.mapOf();
        List<Gap> requestGaps=new ArrayList<>();
        try{plan=new RequirementCompiler().compile(checkpoint,session);}catch(InterruptedIOException e){throw interrupted();}catch(IOException e){plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(),"COMPILATION_UNAVAILABLE",cn.bidcheck.compat.Java8Api.listOf("规则编译未完成，保留原条款继续等待可用服务"));issues.add(e.getMessage()!=null&&e.getMessage().equals("MODEL_BUDGET_EXHAUSTED")?"BUDGET_EXHAUSTED":"RULE_COMPILATION_UNAVAILABLE");requestGaps.add(requestGap("条款解析",e));}
        plan=SourcePerformanceScore.bindDeclaredTargets(plan,corpus);
        session.record("RULE_PLAN",cn.bidcheck.compat.Java8Api.mapOf("checkpointId", checkpoint.id(), "expected", Objects.toString(checkpoint.expected(),"")),plan);
        if(agentLed){
            serverPlanAudit=new LinkedHashMap<>(trustedPlanAudit(plan));
            if(ctx.containsKey("agentPriorPlanRejected"))serverPlanAudit.put("priorPlanRejected",ctx.get("agentPriorPlanRejected"));
            session.record("AGENT_SERVER_PLAN",cn.bidcheck.compat.Java8Api.mapOf("checkpointId", checkpoint.id(), "policy", SERVER_AGENT_PLAN_POLICY),serverPlanAudit);
            LOG.info("bidcheck.agent_server_plan runId={} checkpointId={} method={} nodes={} leaves={} unsupported={} submitReviewPlanCalls=0",
                safeDiagnostic(ctx.get("sourceRunId")),safeDiagnostic(checkpoint.id()),plan.method(),serverPlanAudit.get("nodeCount"),serverPlanAudit.get("leafCount"),serverPlanAudit.get("unsupportedLeafCount"));
        }
        ctx.put("recoverySourceRevision",scope);ctx.put("recoveryValidationIdentity",identity());
        Map<String,Object> recoveryDependencies=RecoveryDependencyGate.begin(ctx,plan);
        List<Rule> allEvidenceRules=evidenceRules(plan.rules());
        Map<String,String> readParents=SourceScoringRules.readParents(plan.rules(),allEvidenceRules);
        List<Rule> activeEvidenceRules=RecoveryTargets.select(plan.rules(),allEvidenceRules,ctx);
        // Establish current bid parties before retrieving their licenses. Their formal source
        // pages are independent of whichever business windows happen to rank in the top 24.
        List<Fact> enterpriseBindings=EnterpriseSubjectBindings.extract(activeEvidenceRules,corpus.all);
        enterpriseBindings.forEach(f->facts.put(f.id(),f));
        shared.publish(enterpriseBindings,nativeSources(corpus,enterpriseBindings),activeEvidenceRules,cn.bidcheck.compat.Java8Api.listOf(),false,"NATIVE");
        if(RecoveryTargets.active(ctx)){
            candidates=RecoveryReplay.sources(ctx,corpus,model.identity());List<Map<String,Object>> recoveredLayout=layout(ctx,candidates,allEvidenceRules);
            candidates=withLayoutSources(corpus,candidates,recoveredLayout);recoveredLayout=layout(ctx,candidates,allEvidenceRules);ctx.put("_validatedLayoutCells",recoveredLayout);
            ProofVerifier.Verified replay=RecoveryReplay.facts(ctx,corpus,candidates,recoveredLayout,model.identity(),allEvidenceRules);replay.facts().forEach(f->facts.put(f.id(),f));rejected.addAll(replay.rejected());
            for(Fact fact:shared.lookup(activeEvidenceRules,candidates,recoveredLayout))facts.put(fact.id(),fact);
            // Replay old local proof for the whole tree. Only new lookup/retrieval/extraction is
            // target-restricted; otherwise an unrelated completed branch loses its native facts.
            for(Fact fact:cn.bidcheck.compat.Java8Api.toList(corpus.nativeFacts(allEvidenceRules,candidates).stream().map(SharedFactReuse::canonical)))facts.put(fact.id(),fact);
            for(Fact fact:cn.bidcheck.compat.Java8Api.toList(VisualSubjectBindings.extract(allEvidenceRules,candidates).stream().map(SharedFactReuse::canonical)))facts.put(fact.id(),fact);
            RecoveryFieldCoverage.Replayed reads=RecoveryFieldCoverage.replay(ctx,allEvidenceRules,corpus,new ArrayList<>(facts.values()),session);
            shared.restoreRecoveryCoverage(reads);
            session.record("RECOVERY_FIELD_COVERAGE_REVALIDATION",cn.bidcheck.compat.Java8Api.mapOf("mode", str(ctx,"recoveryMode")),linked("acceptedReceiptCount",reads.receipts().size(),"completedSourceFieldReads",shared.completedReads().size(),"rejectedReasons",reads.rejected(),"modelCalls",0,"newObservation",false));
            session.record("RECOVERY_FACT_REVALIDATION",cn.bidcheck.compat.Java8Api.mapOf("mode", str(ctx,"recoveryMode")),linked("facts",new ArrayList<>(facts.values()),"rejected",replay.rejected(),"modelCalls",0,"targetObligations",ctx.get("recoveryTargetObligationIds"),"fullConditionTreePreserved",true));
        }
        List<Map<String, Object>> ownerLayoutAudit =
                DeclaredPerformanceRows.restoreOwnerLayouts(plan.rules(), corpus, ctx);
        if (!ownerLayoutAudit.isEmpty()) {
            session.record("DECLARED_PERSONAL_OWNER_LAYOUT",
                    linked("scope", "CURRENT_FROZEN_INVENTORY_PAGES", "targetRestrictedFactsUnchanged", true),
                    linked("pages", ownerLayoutAudit, "modelCalls", 0, "businessProof", false));
        }
        RecoveryCoverageDependencies coverageDependencies=new RecoveryCoverageDependencies(ctx,allEvidenceRules,activeEvidenceRules,candidates,model.identity());coverageDependencies.readOutstandingLookup(allEvidenceRules,candidates,corpus,shared,ctx,facts);coverageDependencies.apply(ctx,shared);
        RuleEvaluator evaluator=new RuleEvaluator(new ArrayList<>(facts.values()),corpus,ctx);List<Decision> initial=cn.bidcheck.compat.Java8Api.toList(plan.rules().stream().map(evaluator::evaluate));Decision decision=RuleEvaluator.combine(checkpoint.id(),checkpoint.title(),plan.logic(),initial);
        preserveValidChildren(plan.rules(),evaluator);
        List<Rule> leaves=RequirementCompiler.leaves(plan.rules()),retrievable=RecoveryTargets.select(plan.rules(),allEvidenceRules,ctx);Set<String> candidateSets=new HashSet<>();Map<String,Decision> previousDecisions=new LinkedHashMap<>(evaluator.decisions);String stopReason=retrievable.isEmpty()&&leaves.stream().anyMatch(ProgramSourceRead::applies)?"PROGRAM_SOURCE_SCAN_FINISHED":RecoveryTargets.active(ctx)&&retrievable.isEmpty()?"RECOVERY_TARGET_NOT_RESOLVED":"LOCAL_STRATEGIES_FINISHED";
        Set<String> progress=progress(evaluator,shared);int plannedPackets=0,blockedPackets=0;
        session.record("RULE_COMPUTATION_INITIAL",cn.bidcheck.compat.Java8Api.mapOf("factIds", facts.keySet()),linked("decision",decision.map(),"obligations",cn.bidcheck.compat.Java8Api.toList(initial.stream().map(Decision::map))));
        List<Rule> agentTargets=AgentInvestigationScope.pending(plan,activeEvidenceRules,ctx,evaluator);
        boolean agentAvailable=investigator!=null&&investigator.enabled();
        boolean agentExecutionAllowed=!localRecovery&&!Boolean.FALSE.equals(ctx.get("recoveryModelAllowed"));
        boolean eligibleAgent=agentAvailable&&(agentLed?agentExecutionAllowed:EvidenceInvestigation.eligible(checkpoint,ctx));
        boolean agentNeeded=!agentTargets.isEmpty()&&!decision.gaps().isEmpty()&&AgentInvestigationScope.requiresEvidence(decision);
        boolean useAgent=eligibleAgent&&agentNeeded;
        // AGENT_LED never silently falls back to the ordinary model extractor.  If the SDK is
        // unavailable, retain the server plan and local proof, then surface an engine gap.
        if(agentLed&&!useAgent){
            retrievable=cn.bidcheck.compat.Java8Api.listOf();
            stopReason=agentNeeded&&!agentAvailable?"AGENT_INVESTIGATOR_UNAVAILABLE":agentNeeded&&!agentExecutionAllowed?"AGENT_LOCAL_RECOMPUTE_ONLY":"RECOVERY_TARGETS_ALREADY_CLOSED_OR_NOT_RESOLVED";
            if(agentNeeded&&!agentAvailable){issues.add("AGENT_INVESTIGATION_UNAVAILABLE");requestGaps.add(new Gap("AGENT_INVESTIGATION_UNAVAILABLE","Agent 取证执行器不可用，服务器规则计划和已有证明已保留。","恢复取证执行器后仅继续未闭合证据目标；不重新生成规则。","RETRY_FAILED_STEP"));}
        }else if(eligibleAgent&&!useAgent){retrievable=cn.bidcheck.compat.Java8Api.listOf();stopReason="RECOVERY_TARGETS_ALREADY_CLOSED_OR_NOT_RESOLVED";}
        if(agentLed||eligibleAgent){
            session.record("AGENT_TARGET_GATE",cn.bidcheck.compat.Java8Api.mapOf("requestedTargetObligationIds", ProofVerifier.strings(ctx.get("recoveryTargetObligationIds"))),
                linked("remainingTargetObligationIds",cn.bidcheck.compat.Java8Api.toList(agentTargets.stream().map(Rule::id)),"agentRequired",useAgent,"agentAvailable",agentAvailable,"agentExecutionAllowed",agentExecutionAllowed,"wholeTreeGapCount",decision.gaps().size(),"modelCalls",0,"planAuthority",agentLed?"SERVER_REQUIREMENT_COMPILER":"EXISTING_SERVER_PLAN","submitReviewPlanEnabled",false,"reason",useAgent?"TARGET_EVIDENCE_REQUIRED":agentNeeded&&!agentAvailable?"AGENT_INVESTIGATOR_UNAVAILABLE":agentNeeded&&!agentExecutionAllowed?"LOCAL_RECOMPUTE_ONLY":"TARGETS_ALREADY_CLOSED_OR_NOT_RESOLVED"));
            LOG.info("bidcheck.agent_target_gate runId={} checkpointId={} targetCount={} wholeTreeGapCount={} agentRequired={}",safeDiagnostic(ctx.get("sourceRunId")),safeDiagnostic(checkpoint.id()),agentTargets.size(),decision.gaps().size(),useAgent);
        }
        Map<String,Object> agentInvestigation=cn.bidcheck.compat.Java8Api.mapOf();
        for(Rule rule:leaves)if(ProgramSourceRead.applies(rule)){
            Decision observed=evaluator.decisions.get(rule.id());
            if(observed!=null)session.record("PROGRAM_SOURCE_READ",cn.bidcheck.compat.Java8Api.mapOf("obligationId", rule.id()),linked("status",observed.status(),"receipt",observed.proof().getOrDefault("programSourceRead",cn.bidcheck.compat.Java8Api.mapOf()),"newModelCall",false));
        }
        for(int round=0;round<(useAgent?0:localRecovery?1:3)&&!retrievable.isEmpty();round++){
            if(Thread.currentThread().isInterrupted())throw interrupted();session.rounds=round+1;
            Map<String,Decision> priorDecisions=previousDecisions;List<Rule> unresolved=cn.bidcheck.compat.Java8Api.toList(retrievable.stream().filter(r->{Decision d=priorDecisions.get(readParents.getOrDefault(r.id(),r.id()));return d==null||!d.gaps().isEmpty()||!cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(d.status());}));
            if(unresolved.isEmpty()){stopReason="OBLIGATIONS_RESOLVED";break;}
            if(localRecovery)candidates=RecoveryReplay.sources(ctx,corpus,model.identity());
            else try{candidates=corpus.select(ObligationRecovery.retrievalRules(unresolved,ctx.get("recoveryGaps")),new ArrayList<>(facts.values()),candidates,round,retriever,session);}catch(InterruptedIOException e){throw interrupted();}
            List<Map<String,Object>> layout=layout(ctx,candidates,unresolved);candidates=withLayoutSources(corpus,candidates,layout);layout=layout(ctx,candidates,unresolved);Map<String,Map<String,Object>> knownLayout=new LinkedHashMap<>();for(Map<String,Object> row:RuleEvaluator.maps(ctx.get("_validatedLayoutCells")))knownLayout.put(str(row,"documentId")+":"+str(row,"spanId"),row);for(Map<String,Object> row:layout)knownLayout.put(str(row,"documentId")+":"+str(row,"spanId"),row);ctx.put("_validatedLayoutCells",new ArrayList<>(knownLayout.values()));String selection=VerificationTypes.identity(candidates.stream().map(Source::id).collect(Collectors.joining("|"))+json.valueToTree(layout));
            session.record("EVIDENCE_PLAN_"+round,cn.bidcheck.compat.Java8Api.mapOf("unresolved", cn.bidcheck.compat.Java8Api.toList(unresolved.stream().map(Rule::id))),linked("candidates",corpus.inputs(candidates),"layoutCells",layout.size(),"round",round));
            for(Fact f:shared.lookup(unresolved,candidates,layout))facts.put(f.id(),f);
            long nativeStarted=System.nanoTime();List<Fact> nativeFacts=new ArrayList<>(cn.bidcheck.compat.Java8Api.toList(corpus.nativeFacts(unresolved,candidates).stream().map(SharedFactReuse::canonical)));nativeFacts.addAll(VisualSubjectBindings.extract(unresolved,candidates));for(Fact f:nativeFacts)facts.put(f.id(),f);
            long nativeExtracted=System.nanoTime();
            shared.publish(nativeFacts,nativeSources(corpus,nativeFacts),unresolved,layout,false,"NATIVE");
            LOG.info("bidcheck.fact_native runId={} checkpointId={} round={} sources={} facts={} extractMs={} publishMs={}",safeDiagnostic(ctx.get("sourceRunId")),safeDiagnostic(checkpoint.id()),round,candidates.size(),nativeFacts.size(),(nativeExtracted-nativeStarted)/1_000_000,(System.nanoTime()-nativeExtracted)/1_000_000);
            List<Rule> extractionRules=cn.bidcheck.compat.Java8Api.toList(shared.extractionRules(unresolved,candidates,new RuleEvaluator(new ArrayList<>(facts.values()),corpus,ctx)).stream()
                // Keep visual rules in retrieval/shared fact lookup so sourced subject bindings are
                // still consumed. Text extraction cannot provide an image observation for them.
                .filter(rule->!localRecovery&&!ProgramSourceRead.applies(rule)&&!SourceScoringRules.programOnly(rule)&&!SourceRequirementInventory.applies(rule)&&!SourceSignatureRequirements.applies(rule)&&!RequirementBusinessRouting.applies(rule)&&!Boolean.TRUE.equals(rule.parameters().get("templateFormAudit"))&&!cn.bidcheck.compat.Java8Api.setOf("UNSUPPORTED", "INPUT", "REFERENCE_TIME", "NOT_APPLICABLE", "VISUAL", "TEXT_ANOMALY", "QUALITY_ADVISORY").contains(rule.predicate())&&cn.bidcheck.compat.Java8Api.isBlank(SameFieldComparisonTarget.reference(rule))));
            Set<String> unreviewedSharedSources=new HashSet<>();if(shared.reusedFacts()>0)extractionRules.forEach(r->unreviewedSharedSources.add(r.id()));
            if(model.enabled()&&!extractionRules.isEmpty()&&!candidates.isEmpty()&&candidateSets.add(selection)){
                List<Map<String,Object>> rejectionHints=new ArrayList<>(RuleEvaluator.maps(ctx.get("previousRejectedFacts")));rejectionHints.addAll(rejected);
                Map<String,Object> input=linked("rules",extractionRules,"sources",corpus.inputs(candidates),"layoutCells",layout,"knownFacts",new ArrayList<>(facts.values()),"previousRejected",rejectionHints,"referenceDate",ctx.get("referenceDate"),"requirementScope",plan.requirementScope());
                input.putAll(extractionRequirement(plan));
                ExtractionBatches.Plan packetPlan=ExtractionBatches.plan(input,ctx.get("recoveryStrategy"),shared.pendingSources(extractionRules,candidates));plannedPackets+=packetPlan.batches().size();blockedPackets+=packetPlan.blocked().size();
                session.record("EXTRACTION_SPLIT_PLAN",cn.bidcheck.compat.Java8Api.mapOf("selection", selection, "round", round),packetPlan.audit());
                Map<String,Set<String>> reviewed=new HashMap<>();
                for(Map<String,Object> blocked:packetPlan.blocked()){List<String> ids=ProofVerifier.strings(blocked.get("obligationIds"));unreviewedSharedSources.addAll(ids);issues.add("FACT_EXTRACTION_UNAVAILABLE");requestGaps.add(new Gap("EXTRACTION_ATOMIC_INPUT_TOO_LARGE","完整来源及必要上下文超出单次处理范围，尚未读取","按来源/表格结构重新规划该窗口；不表示材料缺失","SPLIT_FAILED_REQUEST"));}
                for(Map<String,Object> planned:packetPlan.batches()){
                    List<Source> plannedSources=cn.bidcheck.compat.Java8Api.toList(ExtractionBatches.sourceIds(planned).stream().map(corpus.byId::get).filter(Objects::nonNull));
                    try(SharedFactReuse.Lease flight=shared.coordinate(ExtractionBatches.rules(planned),plannedSources)){
                        // Another checkpoint may have completed exactly these observations while this one waited.
                        for(Fact f:shared.lookup(ExtractionBatches.rules(planned),plannedSources,RuleEvaluator.maps(planned.get("layoutCells")),"AFTER_FLIGHT"))facts.put(f.id(),f);
                        ExtractionBatches.Plan remaining=ExtractionBatches.plan(planned,ctx.get("recoveryStrategy"),shared.pendingSources(ExtractionBatches.rules(planned),plannedSources));
                        if(remaining.batches().isEmpty()&&remaining.blocked().isEmpty()){shared.extractionSkipped+=ExtractionBatches.rules(planned).size();session.record("EXTRACTION_REUSED",cn.bidcheck.compat.Java8Api.mapOf("packetId", planned.get("packetId")),cn.bidcheck.compat.Java8Api.mapOf("sourceIds", ExtractionBatches.sourceIds(planned)));}
                        ArrayDeque<Map<String,Object>> attempts=new ArrayDeque<>(remaining.batches());
                        while(!attempts.isEmpty()){
                            Map<String,Object> batch=attempts.removeFirst();
                            List<Source> sent=cn.bidcheck.compat.Java8Api.toList(ExtractionBatches.sourceIds(batch).stream().map(corpus.byId::get).filter(Objects::nonNull));List<Map<String,Object>> sentLayout=RuleEvaluator.maps(batch.get("layoutCells"));
                            try{shared.publishState(ExtractionBatches.rules(batch),sent,"UNREAD","EXTRACTION_PENDING");JsonNode raw=session.model("EXTRACT",batch);ProofVerifier.Verified verified=new ProofVerifier(corpus.byId).verify(raw,sent,sentLayout);List<Fact> validated=new ArrayList<>();for(Fact f:verified.facts()){
                                if(f.kind().equals("SECTION")){Map<String,Object> attrs=new LinkedHashMap<>(f.attributes());attrs.put("fullSectionRead",SectionCoverage.covered(f,corpus,sent));f=new Fact(f.id(),f.kind(),f.subject(),f.field(),f.rawValue(),f.value(),f.unit(),f.basis(),f.scope(),f.sourceIds(),attrs);}f=SharedFactReuse.canonical(f);facts.put(f.id(),f);validated.add(f);
                            }rejected.addAll(verified.rejected());Map<String,Object> readBinding=RecoveryFieldCoverage.readBinding(selection,batch.get("packetId"),ExtractionBatches.rules(batch),ExtractionBatches.sourceIds(batch));session.record("FACT_VALIDATION_"+round,readBinding,linked("facts",validated,"rejected",verified.rejected(),"sourceIds",ExtractionBatches.sourceIds(batch),"readBinding",readBinding,"payloadChars",ExtractionBatches.size(batch)));
                            shared.publish(validated,sent,ExtractionBatches.rules(batch),sentLayout,verified.rejected().isEmpty(),"EXTRACTED",verified.rejected());
                            if(verified.rejected().isEmpty())for(Rule rule:ExtractionBatches.rules(batch))reviewed.computeIfAbsent(rule.id(),k->new HashSet<>()).addAll(ExtractionBatches.sourceIds(batch));
                            }catch(InterruptedIOException e){throw interrupted();}catch(IOException e){
                                Map<String,Object> failure=RunSession.failure(e);List<Map<String,Object>> split=Boolean.TRUE.equals(failure.get("splittable"))?ExtractionBatches.splitFailed(batch):cn.bidcheck.compat.Java8Api.listOf();
                                if(!split.isEmpty()){
                                    plannedPackets+=split.size();session.recordExtractionSplit(batch,split,failure);
                                    for(int at=split.size()-1;at>=0;at--)attempts.addFirst(split.get(at));continue;
                                }
                                shared.publishState(ExtractionBatches.rules(batch),sent,"FAILED",Objects.toString(failure.get("errorCode"),"MODEL_UNAVAILABLE"));unreviewedSharedSources.addAll(cn.bidcheck.compat.Java8Api.toList(ExtractionBatches.rules(batch).stream().map(Rule::id)));issues.add(e.getMessage()!=null&&e.getMessage().equals("MODEL_BUDGET_EXHAUSTED")?"BUDGET_EXHAUSTED":"FACT_EXTRACTION_UNAVAILABLE");requestGaps.add(requestGap("证据提取",e));}
                        }
                    }catch(InterruptedIOException e){throw interrupted();}
                }
                for(Rule rule:extractionRules){Set<String> unread=shared.pendingSources(rule,candidates);unread.removeAll(reviewed.getOrDefault(rule.id(),cn.bidcheck.compat.Java8Api.setOf()));if(unread.isEmpty())unreviewedSharedSources.remove(rule.id());}
            }
            coverageDependencies.apply(ctx,shared);
            // Failed/incomplete dependent reads protect their scoring formula as well.
            for(String read:cn.bidcheck.compat.Java8Api.listCopyOf(unreviewedSharedSources))if(readParents.containsKey(read))unreviewedSharedSources.add(readParents.get(read));
            evaluator=new RuleEvaluator(new ArrayList<>(facts.values()),corpus,ctx);evaluator.incompleteSourceReview.addAll(unreviewedSharedSources);List<Decision> outputs=cn.bidcheck.compat.Java8Api.toList(plan.rules().stream().map(evaluator::evaluate));decision=RuleEvaluator.combine(checkpoint.id(),checkpoint.title(),plan.logic(),outputs);preserveValidChildren(plan.rules(),evaluator);previousDecisions=new LinkedHashMap<>(evaluator.decisions);
            session.record("RULE_COMPUTATION_"+round,cn.bidcheck.compat.Java8Api.mapOf("factIds", facts.keySet()),linked("decision",decision.map(),"obligations",cn.bidcheck.compat.Java8Api.toList(outputs.stream().map(Decision::map))));
            if(decision.gaps().isEmpty()){stopReason="OBLIGATIONS_RESOLVED";break;}
            if(localRecovery){stopReason="LOCAL_RECOVERY_RECOMPUTED";break;}
            Set<String> currentProgress=progress(evaluator,shared),gain=new TreeSet<>(currentProgress);gain.removeAll(progress);progress.addAll(currentProgress);
            session.record("RECOVERY_PROGRESS",cn.bidcheck.compat.Java8Api.mapOf("round", round),linked("completedReads",shared.completedReads().size(),"newProofOrReadKeys",gain,"factIdsAreProgress",false));
            if(gain.isEmpty()){stopReason="NO_NEW_EVIDENCE_OR_CLOSED_GAP";session.record("RECOVERY_STOP",cn.bidcheck.compat.Java8Api.mapOf("progressFingerprint", VerificationTypes.identity(progress.toString())),cn.bidcheck.compat.Java8Api.mapOf("reason", stopReason));break;}
        }

        if(useAgent){
            EvidenceInvestigation.Host host=new EvidenceInvestigation.Host(checkpoint,plan,agentTargets,corpus,facts,ctx,shared,session,retriever,rejected,evaluator.incompleteSourceReview);
            host.coverageDependencies=coverageDependencies;
            agentInvestigation=EvidenceInvestigation.run(investigator,host);evaluator=host.evaluator;decision=host.decision;preserveValidChildren(plan.rules(),evaluator);
            candidates=cn.bidcheck.compat.Java8Api.listCopyOf(host.disclosed.values());evaluator.visualRequests.addAll(host.visualRequests);
            session.modelCalls+=RunSession.number(agentInvestigation.get("modelCalls"),0);
            stopReason="AGENT_"+str(agentInvestigation,"status");
            String investigationError=str(agentInvestigation,"errorCode");
            boolean providerFailure=investigationError.startsWith("PROVIDER_") || "INVESTIGATION_TIMEOUT".equals(investigationError) || "HOST_CANCELLED".equals(investigationError);
            if(providerFailure) {
                agentInvestigation=new LinkedHashMap<>(agentInvestigation);
                agentInvestigation.put("processingFailed",true);
                agentInvestigation.put("preservedBusinessDecision",decision.status());
                session.record("AGENT_TRANSPORT_FAILURE",linked("checkpointId",checkpoint.id()),
                    linked("errorCode",investigationError,"businessDecision",decision.status(),"remainingBusinessGaps",decision.gaps().size()));
            }
            if(!providerFailure&&"FAILED".equals(str(agentInvestigation,"status"))&&!Boolean.TRUE.equals(agentInvestigation.get("targetCheckCompleted"))&&!decision.gaps().isEmpty()){
                boolean stalled="AGENT_STALLED_WITH_REMAINING_TARGETS".equals(str(agentInvestigation,"errorCode"));
                List<Gap> failed=new ArrayList<>(decision.gaps());failed.add(new Gap(stalled?"AGENT_INVESTIGATION_STALLED":"AGENT_INVESTIGATION_FAILED",stalled?"查证重复执行已失败路径，系统已停止本次调查；其他目标尚未查完。":"定向查证服务未完成，已验证证据保留。",stalled?"按保留的未完成目标修正调查路径或等待相关新证据；不自动重复相同调用，不表示材料缺失。":"按记录的失败步骤恢复；不表示投标材料缺失。",stalled?"INPUT_OR_EVIDENCE_CHANGED":"RETRY_FAILED_STEP"));
                decision=new Decision(decision.id(),decision.description(),decision.status(),decision.reason(),decision.evidences(),failed,decision.proof());
            }
        }
        ctx.put("agentInvestigation",agentInvestigation);
        if(decision==null)decision=RuleEvaluator.combine(checkpoint.id(),checkpoint.title(),plan.logic(),cn.bidcheck.compat.Java8Api.listOf());
        decision=lookupCoverageGuard(decision,shared.truncatedForRules(allEvidenceRules),shared.lookupTruncations());
        if(!plan.issues().isEmpty()){List<Gap> gaps=new ArrayList<>(decision.gaps());String detail=String.join("；",plan.issues());gaps.add(new Gap("REQUIREMENT_COMPILATION_INCOMPLETE",detail,"修正规则映射并重新计算受影响条件；不要求用户重复补交既有材料","REQUIREMENT_MAPPING_CHANGED"));decision=new Decision(decision.id(),decision.description(),"UNDETERMINED","条款编译仍有未解决范围，已完成原子证明予以保留",decision.evidences(),gaps,decision.proof());}
        decision=outstandingRequestIssues(decision,issues,requestGaps);
        List<Map<String,Object>> gaps=cn.bidcheck.compat.Java8Api.toList(decision.gaps().stream().distinct().map(Gap::map));boolean complete=gaps.isEmpty()&&cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(decision.status());String workflow=complete?"COMPLETED":"NEEDS_CONFIRMATION".equals(decision.status())?"NEEDS_CONFIRMATION":workflow(decision.gaps());
        RuleEvaluator finalEvaluator=evaluator;List<Map<String,Object>> externalRequests=cn.bidcheck.compat.Java8Api.toList(leaves.stream().filter(r->r.predicate().equals("EXTERNAL")).filter(r->{Decision d=finalEvaluator.decisions.get(r.id());return d==null||!cn.bidcheck.compat.Java8Api.setOf("PASS", "NOT_APPLICABLE").contains(d.status());}).flatMap(r->RequirementBusinessRouting.applies(r)?RequirementBusinessRouting.requests(r,finalEvaluator).stream():finalEvaluator.resolvedSubjects(r).stream().map(subject->linked("obligationId",r.id(),"type",cn.bidcheck.compat.Java8Api.isBlank(str(r.parameters(),"externalType"))?r.field():str(r.parameters(),"externalType"),"subject",subject,"evaluationStage",cn.bidcheck.compat.Java8Api.isBlank(str(r.parameters(),"evaluationStage"))?ctx.get("evaluationStage"):r.parameters().get("evaluationStage"),"referenceDate",ctx.get("referenceDate"),"field",r.field(),"operator",r.operator(),"expected",r.expected()))));
        List<Map<String,Object>> rejectionRoutes=cn.bidcheck.compat.Java8Api.toList(rejected.stream().map(r->linked("code",str(r,"code"),"field",str(r,"field"),"sourceIds",ProofVerifier.strings(r.get("sourceIds")),"action",RecoveryFailureRouting.candidateAction(str(r,"code")))));
        if(!rejectionRoutes.isEmpty())session.record("CANDIDATE_RECOVERY_ROUTES",cn.bidcheck.compat.Java8Api.mapOf("checkpointId", checkpoint.id()),cn.bidcheck.compat.Java8Api.mapOf("routes", rejectionRoutes));
        Set<String> requested=new HashSet<>();for(Map<String,Object> rejection:rejected){if(!RecoveryFailureRouting.candidateNeedsImage(str(rejection,"code")))continue;for(String id:ProofVerifier.strings(rejection.get("sourceIds"))){Source source=corpus.byId.get(id);if(source==null||source.id().startsWith("V")||evaluator.visualRequests.size()>=4||!requested.add(source.evidence().documentId()+":"+source.evidence().page()))continue;evaluator.visualRequests.add(linked("documentId",source.evidence().documentId(),"locationType",source.evidence().locationType(),"location",source.evidence().page(),"task","FIELD","fields",cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Api.listOf(str(rejection,"subject"), str(rejection,"field")).stream().filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).distinct()),"reason",rejection.get("code")));}}
        Map<String,Object> proofSnapshot=RecoveryReplay.snapshot(corpus,facts.values());proofSnapshot.put("coverageDependencies",coverageDependencies.snapshot(ctx,allEvidenceRules,evaluator.decisions,shared,candidates));
        Map<String,Object> agentReview=agentLed?linked("version",SERVER_AGENT_PLAN_POLICY,"workflowPolicy","SERVER_PLAN_EVIDENCE_V1","mode","AGENT_LED","planAuthority","SERVER_REQUIREMENT_COMPILER","serverOwnedPlan",true,"modelAuthoredPlan",false,"submitReviewPlanEnabled",false,"submitReviewPlanCalls",0,"planAudit",serverPlanAudit,"agent",agentInvestigation):cn.bidcheck.compat.Java8Api.mapOf();
        session.record(RecoveryReplay.FACT_SNAPSHOT,cn.bidcheck.compat.Java8Api.mapOf("checkpointId", checkpoint.id()),proofSnapshot);
        return linked("checkpointId",checkpoint.id(),"title",checkpoint.title(),"businessDecision",decision.status(),"decisionBasis",leaves.stream().anyMatch(r->cn.bidcheck.compat.Java8Api.setOf("TEXT_ANOMALY", "QUALITY_ADVISORY").contains(r.predicate()))?"PROGRAM_STRUCTURAL_RULES":leaves.stream().anyMatch(r->r.predicate().equals("QUALITY"))?"LEGACY_QUALITY_DISABLED":"SOURCE_GROUNDED_RULES","workflowState",workflow,"complete",complete,"obligations",cn.bidcheck.compat.Java8Api.toList(evaluator.decisions.values().stream().map(Decision::map)),"gaps",gaps,"evidences",decision.evidences(),"reason",decision.reason(),"proof",decision.proof(),"plan",plan,"facts",new ArrayList<>(facts.values()),"recoveryDependencies",recoveryDependencies,"coverageDependencies",proofSnapshot.get("coverageDependencies"),"recoveryEvidenceDependencies",evidenceDependencies(allEvidenceRules,activeEvidenceRules,shared.coverage(),ctx,model.identity(),finalEvaluator,shared.sourceMetadata(),!shared.factLookupTruncated()),"inferredFacts",ctx.getOrDefault("inferredFacts",cn.bidcheck.compat.Java8Api.listOf()),"relations",cn.bidcheck.compat.Java8Api.toList(facts.values().stream().filter(f->f.kind().equals("RELATION")||f.kind().equals("PERSON_ROLE"))),"visualRequests",evaluator.visualRequests,"externalRequests",externalRequests,"steps",session.steps,"agentReview",agentReview,"agentInvestigation",agentInvestigation,"modelDiagnostics",session.diagnostics(),"compilationAudit",CompilationRecovery.activeAudit(plan,finalEvaluator,session.compilationAudit),"recoveryActions",ObligationRecovery.actions(plan,evaluator.decisions,candidates,session),"metrics",linked("stopReason",stopReason,"rounds",session.rounds,"modelCalls",session.modelCalls,"priorModelCalls",session.priorCalls,"resumedSteps",session.resumedSteps,"reusedFacts",shared.reusedFacts(),"sharedPublishes",shared.sharedPublishes,"extractionSkipped",shared.extractionSkipped,"sharedRejectedFacts",shared.rejectedFacts,"factLookupTruncated",shared.factLookupTruncated(),"fieldLookupTruncated",shared.fieldLookupTruncated(),"lookupTruncations",shared.lookupTruncations(),"facts",facts.size(),"rejectedFacts",rejected.size(),"finalCandidateCount",candidates.size(),"plannedPackets",plannedPackets,"blockedPackets",blockedPackets,"completedSourceFieldReads",shared.completedReads().size(),"extractionPolicy",ExtractionBatches.POLICY,"reviewMode",agentLed?"AGENT_LED":"FIXED_WORKFLOW","agentPlanAuthority",agentLed?"SERVER_REQUIREMENT_COMPILER":"","agentPlanSubmissionCalls",0,"agentEvidenceOnly",agentLed,"elapsedMs",(System.nanoTime()-start)/1_000_000,"engineVersion",VERSION),"rejectedFacts",rejected,"fieldCoverage",shared.coverage());
    }
    /** Only terminal failures placed in these lists by the request/split handlers participate.
     * RunSession's recovered attempt history is deliberately not reclassified as a live failure. */
    static Decision outstandingRequestIssues(Decision decision,List<String> issues,List<Gap> requestGaps){
        if(!cn.bidcheck.compat.Java8Api.setOf("UNDETERMINED", "NEEDS_CONFIRMATION").contains(decision.status())||issues.isEmpty()&&requestGaps.isEmpty())return decision;
        List<Gap> gaps=new ArrayList<>(decision.gaps());for(String issue:issues)gaps.add(gap(issue,issue.equals("BUDGET_EXHAUSTED")?"本次付费抽取预算已用尽，已完成结果保留":"系统处理未完成，已完成的规则结果保留；不表示投标材料缺失"));gaps.addAll(requestGaps);
        return new Decision(decision.id(),decision.description(),"UNDETERMINED","NEEDS_CONFIRMATION".equals(decision.status())?"尚有未完成的系统请求；已保存推断，但整项不能仅归为等待用户确认。":decision.reason(),decision.evidences(),cn.bidcheck.compat.Java8Api.toList(gaps.stream().distinct()),decision.proof());
    }
    /** Immutable audit of the exact server-owned tree given to the evaluator.  It records
     * structure without flattening ANY/CONDITIONAL/FOR_EACH branches or copying source text. */
    static Map<String,Object> trustedPlanAudit(Plan plan){
        Map<String,Integer> logicCounts=new TreeMap<>(),predicateCounts=new TreeMap<>();
        ArrayDeque<Rule> pending=new ArrayDeque<>(plan.rules());int nodes=0,leaves=0,unsupported=0;
        while(!pending.isEmpty()){
            Rule rule=pending.removeFirst();nodes++;String rawLogic=Objects.toString(rule.logic(),""),predicate=Objects.toString(rule.predicate(),"");String logic=cn.bidcheck.compat.Java8Api.isBlank(rawLogic)?"LEAF":rawLogic;logicCounts.merge(logic,1,Integer::sum);predicateCounts.merge(cn.bidcheck.compat.Java8Api.isBlank(predicate)?"GROUP":predicate,1,Integer::sum);
            if(rule.children().isEmpty()){leaves++;if("UNSUPPORTED".equals(rule.predicate()))unsupported++;}else pending.addAll(rule.children());
        }
        return linked("policy",SERVER_AGENT_PLAN_POLICY,"planAuthority","SERVER_REQUIREMENT_COMPILER","serverOwned",true,"modelAuthored",false,
            "submitReviewPlanEnabled",false,"submitReviewPlanCalls",0,"method",plan.method(),"topLevelLogic",plan.logic(),"planFingerprint",VerificationTypes.identity(new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).valueToTree(plan).toString()),
            "nodeCount",nodes,"leafCount",leaves,"unsupportedLeafCount",unsupported,"logicCounts",logicCounts,"predicateCounts",predicateCounts,"issueCount",plan.issues().size(),"conditionTreePreserved",true);
    }
    private static String safeDiagnostic(Object raw){String value=Objects.toString(raw,"");return value.matches("[A-Za-z0-9_.:-]{1,100}")?value:"";}

    /** Use the compiled, source-validated scope. Re-resolving the old generated expected
     * text would lose a repaired obligation again. Cross-block context remains separate
     * literal spans and is never presented as one fabricated quotation. */
    static Map<String,Object> extractionRequirement(Plan plan){
        Map<String,Object> scope=plan.requirementScope();List<Map<String,Object>> obligations=RuleEvaluator.maps(scope.get("obligationSpans"));
        List<Map<String,Object>> sources=scope.get("sourceContext") instanceof Map<?,?>?RuleEvaluator.maps(((Map<?,?>) (scope.get("sourceContext"))).get("sources")):cn.bidcheck.compat.Java8Api.listOf();
        if(!cn.bidcheck.compat.Java8Api.isBlank(str(scope,"issue"))||obligations.isEmpty())return linked("originalRequirement","","requirementSourceSpans",cn.bidcheck.compat.Java8Api.listOf(),"requirementContextVersion",RequirementSourceContext.VERSION);
        // A one-span original remains backward compatible. Multi-span context is supplied
        // in the explicit source array so each quote retains its own location and offsets.
        return linked("originalRequirement",obligations.size()==1?str(obligations.get(0),"sourceQuote"):"",
            "requirementSourceSpans",sources.isEmpty()?obligations:sources,"requirementContextVersion",RequirementSourceContext.VERSION);
    }

    static Decision lookupCoverageGuard(Decision decision,boolean truncated,List<Map<String,Object>> details){
        if(!truncated)return decision;
        List<Gap> gaps=new ArrayList<>(decision.gaps());gaps.add(new Gap("EVIDENCE_LOOKUP_INCOMPLETE",
                "共享证据超过本批读取范围，尚不能排除未读证据中的冲突。","按记录的来源与字段继续分批读取，再重新计算受影响条件。","INPUT_OR_EVIDENCE_CHANGED"));
        Map<String,Object> proof=new LinkedHashMap<>(decision.proof());proof.put("lookupTruncations",details);
        boolean establishedRisk="FAIL".equals(decision.status());
        return new Decision(decision.id(),decision.description(),establishedRisk?"FAIL":"UNDETERMINED",
                establishedRisk?decision.reason():"证据尚未读取完整，保留已有证明，暂不形成满足要求结论。",decision.evidences(),gaps,proof);
    }

    /** Dependency receipts authorize scheduling only. Unvisited branches keep their prior read revision. */
    static List<Map<String,Object>> evidenceDependencies(List<Rule> rules,List<Rule> active,List<Map<String,Object>> coverage,Map<String,Object> context,String modelIdentity,RuleEvaluator evaluator){
        return evidenceDependencies(rules, active, coverage, context, modelIdentity, evaluator, Collections.emptyMap());
    }
    static List<Map<String,Object>> evidenceDependencies(List<Rule> rules,List<Rule> active,List<Map<String,Object>> coverage,Map<String,Object> context,String modelIdentity,RuleEvaluator evaluator,Map<String,Map<String,Object>> sourceMetadata){
        return evidenceDependencies(rules, active, coverage, context, modelIdentity, evaluator, sourceMetadata, false);
    }
    static List<Map<String,Object>> evidenceDependencies(List<Rule> rules,List<Rule> active,List<Map<String,Object>> coverage,Map<String,Object> context,String modelIdentity,RuleEvaluator evaluator,Map<String,Map<String,Object>> sourceMetadata,boolean currentFactSetComplete){
        Set<String> selected=active.stream().map(Rule::id).collect(Collectors.toSet());
        Map<String,Map<String,Object>> previous=new HashMap<>();
        if(context.get("previousAtomicCheck") instanceof Map<?,?>)for(Map<String,Object> row:RuleEvaluator.maps(((Map<?,?>) (context.get("previousAtomicCheck"))).get("recoveryEvidenceDependencies")))previous.put(str(row,"obligationId"),row);
        long revision=context.get("ordinaryRecoveryObservedRevision") instanceof Number?((Number) (context.get("ordinaryRecoveryObservedRevision"))).longValue():0;
        List<Map<String,Object>> out=new ArrayList<>();
        for(Rule rule:rules){
            Map<String,Object> target=SharedFactReuse.observationTarget(rule);
            String key=cn.bidcheck.verification.facts.FieldCoverage.key(target);
            Map<String,Object> old=previous.getOrDefault(rule.id(),cn.bidcheck.compat.Java8Api.mapOf());
            long observed=selected.contains(rule.id())?revision:old.get("observedRevision") instanceof Number?((Number) (old.get("observedRevision"))).longValue():0;
            List<String> sources=selected.contains(rule.id())?cn.bidcheck.compat.Java8Api.toList(coverage.stream().filter(row->key.equals(str(row,"observationKey"))).flatMap(row->ProofVerifier.strings(row.get("sourceIds")).stream()).distinct().sorted()):ProofVerifier.strings(old.get("sourceIds"));
            List<Map<String,Object>> observedValues=new ArrayList<>();
            for(Fact fact:evaluator.facts)if(cn.bidcheck.verification.facts.FieldCoverage.matches(target,SharedFactReuse.value(fact)))
                observedValues.add(linked("subject",fact.subject(),"field",fact.field(),"value",fact.value(),"unit",fact.unit(),"scope",fact.scope(),"basis",fact.basis(),"kind",fact.kind(),"lot",str(fact.attributes(),"lot")));
            Map<String,Object> dependency = linked("obligationId",rule.id(),"observationKey",key,"target",target,"observedValues",observedValues,"sourceIds",sources,"resolvedSubjects",evaluator.resolvedSubjects(rule),"modelIdentity",modelIdentity,"observedRevision",observed,"businessProof",false);
            if (selected.contains(rule.id()) && evaluator.decisions.containsKey(rule.id())) {
                dependency.put("observedEvidence", ObservedRecoveryEvidence.capture(rule, evaluator, sourceMetadata));
            } else if (old.containsKey("observedEvidence")) {
                dependency.put("observedEvidence", old.get("observedEvidence"));
            }
            Map<String,Object> rowNumbers = currentFactSetComplete
                ? DeclaredContractRelevance.captureRowNumbers(rule, evaluator, sourceMetadata) : Collections.emptyMap();
            if (!rowNumbers.isEmpty()) dependency.put("rowNumberEvidence", rowNumbers);
            out.add(dependency);
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }

    /** Unsupported branches and input/time decisions do not need document retrieval or paid extraction. */
    static List<Rule> evidenceRules(List<Rule> rules){
        List<Rule> out=new ArrayList<>();for(Rule rule:rules){
            if(rule.predicate().equals("UNSUPPORTED")){out.addAll(evidenceRules(rule.children()));continue;}
            // These source-bound readers scan the frozen corpus directly and issue their
            // own receipt; stale model field-read dependencies cannot replace that read.
            if(ProgramSourceRead.applies(rule))continue;
            if(SourceRequirementInventory.programOnly(rule)||SourceSignatureRequirements.applies(rule)||RequirementBusinessRouting.applies(rule))continue;
            if(cn.bidcheck.compat.Java8Api.setOf("INPUT", "REFERENCE_TIME", "NOT_APPLICABLE").contains(rule.predicate()))continue;
            if(CompoundBusinessRequirements.applies(rule)){out.addAll(CompoundBusinessRequirements.evidenceRules(rule));continue;}
            if(SourceScoringRules.applies(rule)){out.addAll(SourceScoringRules.evidenceRules(rule));continue;}
            if(!rule.children().isEmpty()&&!rule.predicate().equals("SCORE_COUNT")){if(RuleObligations.hasOwnPredicate(rule))out.add(RuleObligations.own(rule));out.addAll(evidenceRules(rule.children()));}else out.add(rule);
        }return out;
    }
    private static void preserveValidChildren(List<Rule> rules,RuleEvaluator evaluator){
        for(Rule rule:rules){if(rule.predicate().equals("UNSUPPORTED"))for(Rule child:rule.children())if(!child.predicate().equals("UNSUPPORTED"))evaluator.evaluate(child);preserveValidChildren(rule.children(),evaluator);}
    }
    private static List<Source> nativeSources(EvidenceCorpus corpus,List<Fact> facts){return cn.bidcheck.compat.Java8Api.toList(facts.stream().flatMap(f->f.sourceIds().stream()).distinct().map(corpus.byId::get).filter(Objects::nonNull));}
    private static Set<String> progress(RuleEvaluator evaluator,SharedFactReuse shared){Set<String> keys=new TreeSet<>(shared.completedReads());for(Decision d:evaluator.decisions.values())if(cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(d.status())&&d.gaps().isEmpty())keys.add("condition:"+d.id()+":"+d.status());return keys;}
    private static Gap requestGap(String stage,IOException error){
        String code=Objects.toString(RunSession.failure(error).get("errorCode"),"MODEL_UNAVAILABLE");
        String description;
switch (code) {case "REQUEST_TIMEOUT":case "CAPACITY_TIMEOUT":description = "服务请求超时"; break;case "OUTPUT_JSON_INVALID":case "OUTPUT_SCHEMA_INVALID":description = "服务返回格式不符合要求"; break;case "OUTPUT_INCOMPLETE":case "OUTPUT_LIMIT":description = "服务返回内容不完整"; break;case "INPUT_LIMIT":description = "当前请求内容超出单次处理范围"; break;case "NOT_CONFIGURED":case "MODEL_DISABLED":case "ENDPOINT_INVALID":description = "服务配置不可用"; break;case "TRANSPORT_FAILURE":description = "服务连接失败"; break;case "MODEL_BUDGET_EXHAUSTED":description = "当前核验调用次数已达限制"; break;default:description = code.startsWith("HTTP_")?"服务请求失败（"+code+"）":"服务处理未完成"; break;}
        return new Gap(code.startsWith("MODEL_")?code:"MODEL_"+code,stage+"："+description,"保留已完成结果，仅重新处理失败步骤；不表示材料缺失","RETRY_FAILED_STEP");
    }

    private boolean sourceValid(Evidence e,Document tender){return tender.id().equals(e.documentId())&&tender.pages().stream().anyMatch(p->p.number()==e.page()&&p.locationType().equals(e.locationType())&&p.text().contains(e.quote()));}
    private boolean referenceSourceValid(Object raw,Document tender,Map<String,Object> context,String project){
        if(!(raw instanceof Map<?,?>))return false;
        List<Document> sources=new ArrayList<>();if(tender!=null)sources.add(tender);
        if(context.get("tenderDocuments") instanceof List<?>)for(Object item:((List<?>) (context.get("tenderDocuments"))))if(item instanceof Document&&project.equals(((Document) (item)).projectId())&&"TENDER".equals(((Document) (item)).role()))sources.add(((Document) (item)));
        String quote=Objects.toString(((Map<?,?>) (raw)).get("quote"),"");int page=RunSession.number(((Map<?,?>) (raw)).get("page"),-1);
        return !cn.bidcheck.compat.Java8Api.isBlank(quote)&&sources.stream().anyMatch(d->d.id().equals(((Map<?,?>) (raw)).get("documentId"))&&d.pages().stream().anyMatch(p->p.number()==page&&p.locationType().equals(((Map<?,?>) (raw)).get("locationType"))&&p.text().contains(quote)));
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> layout(Map<String,Object> ctx,List<Source> candidates,List<Rule> rules){Map<String,Map<String,Object>> rows=new LinkedHashMap<>();Set<String> visited=new HashSet<>();if(ctx.get("layoutLookup") instanceof Function<?,?>){for(Source s:candidates){String location=s.evidence().documentId()+":"+s.evidence().locationType()+":"+s.evidence().page();if(!visited.add(location))continue;Object result=((Function<Map<String,Object>,?>)((Function<?,?>) (ctx.get("layoutLookup")))).apply(linked("documentId",s.evidence().documentId(),"documentHash",s.documentHash(),"documentVersion",s.documentVersion(),"locationType",s.evidence().locationType(),"location",s.evidence().page(),"sourceIds",cn.bidcheck.compat.Java8Api.listOf(s.id()),"subject",rules.stream().map(Rule::subject).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).findFirst().orElse(""),"sourceQuote",candidates.stream().filter(c->c.evidence().documentId().equals(s.evidence().documentId())&&c.evidence().locationType().equals(s.evidence().locationType())&&c.evidence().page()==s.evidence().page()).map(c->c.evidence().quote()).collect(Collectors.joining("\n"))));for(Map<String,Object> row:RuleEvaluator.maps(result))rows.put(str(row,"documentId")+":"+str(row,"spanId"),row);if(rows.size()>300)break;}}else for(Map<String,Object> row:RuleEvaluator.maps(ctx.get("layoutFacts")))rows.put(str(row,"documentId")+":"+str(row,"spanId"),row);return cn.bidcheck.compat.Java8Api.toList(rows.values().stream().filter(row->candidates.stream().anyMatch(s->s.evidence().documentId().equals(str(row,"documentId"))&&(cn.bidcheck.compat.Java8Api.isBlank(str(row,"documentHash"))||s.documentHash().equals(str(row,"documentHash")))&&(!row.containsKey("documentVersion")||s.documentVersion()==RunSession.number(row.get("documentVersion"),-1)))).limit(300));}
    private List<Source> withLayoutSources(EvidenceCorpus corpus,List<Source> prior,List<Map<String,Object>> cells){Map<String,Source> selected=new LinkedHashMap<>();prior.forEach(s->selected.put(s.id(),s));int chars=prior.stream().mapToInt(s->s.evidence().quote().length()).sum();for(Map<String,Object> cell:cells){if(!"TABLE_CELL".equals(str(cell,"kind"))||cn.bidcheck.compat.Java8Api.isBlank(str(cell,"text")))continue;for(Source s:corpus.all){if(selected.containsKey(s.id())||!s.evidence().documentId().equals(str(cell,"documentId"))||!s.evidence().locationType().equals(str(cell,"locationType"))||s.evidence().page()!=RunSession.number(cell.get("location"),-1)||!s.evidence().quote().contains(str(cell,"text")))continue;if(selected.size()<24&&chars+s.evidence().quote().length()<=32000){selected.put(s.id(),s);chars+=s.evidence().quote().length();}break;}}return cn.bidcheck.compat.Java8Api.listCopyOf(selected.values());}
    private static CancellationException interrupted(){Thread.currentThread().interrupt();return new CancellationException("核验已中断");}
    private static String workflow(List<Gap> gaps){if(gaps.stream().anyMatch(g->cn.bidcheck.compat.Java8Api.setOf("RULE_COMPILATION_UNAVAILABLE", "FACT_EXTRACTION_UNAVAILABLE").contains(g.code())))return "ENGINE_FAILURE";if(gaps.stream().anyMatch(g->g.code().equals("BUDGET_EXHAUSTED")))return "BUDGET_EXHAUSTED";if(gaps.stream().anyMatch(g->cn.bidcheck.compat.Java8Api.setOf("REQUIREMENT_COMPARISON_TARGET_UNRESOLVED", "REQUIREMENT_COMPILATION_INCOMPLETE").contains(g.code())))return "REQUIREMENT_UNPARSED";if(gaps.stream().anyMatch(g->g.code().equals("WAIT_STAGE")))return "WAIT_STAGE";if(gaps.stream().anyMatch(g->g.code().contains("AMBIGUOUS")||g.code().contains("UNSUPPORTED")||g.code().contains("COMPILATION")||g.code().contains("CONFLICTING")||g.code().equals("FULL_SECTION_REQUIRED")||g.code().equals("SHARED_SOURCE_REVIEW_REQUIRED")||g.code().equals("SUBJECT_IDENTITY_CONFLICT")))return "NEEDS_REVIEW";return "WAIT_INPUT";}
    private Map<String,Object> simple(Checkpoint cp,String status,String reason,String code){boolean complete=status.equals("NOT_APPLICABLE");return linked("checkpointId",cp.id(),"title",cp.title(),"businessDecision",status,"decisionBasis","RULE_POLICY","workflowState",complete?"COMPLETED":"WAIT_INPUT","complete",complete,"obligations",cn.bidcheck.compat.Java8Api.listOf(),"gaps",complete?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf(gap(code,reason).map()),"evidences",cn.bidcheck.compat.Java8Api.listOf(),"reason",reason,"metrics",cn.bidcheck.compat.Java8Api.mapOf("engineVersion", VERSION),"facts",cn.bidcheck.compat.Java8Api.listOf(),"relations",cn.bidcheck.compat.Java8Api.listOf(),"visualRequests",cn.bidcheck.compat.Java8Api.listOf(),"externalRequests",cn.bidcheck.compat.Java8Api.listOf());}
}
