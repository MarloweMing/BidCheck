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
    private final ModelGateway model;private final HybridRetriever retriever;private final ObjectMapper json=new ObjectMapper();
    @Autowired public VerificationEngine(VerificationModelClient model,HybridRetriever retriever){this((ModelGateway)model,retriever);}
    public VerificationEngine(ModelGateway model,HybridRetriever retriever){this.model=Objects.requireNonNull(model);this.retriever=retriever;}
    public VerificationEngine(){this(ModelGateway.disabled(),new HybridRetriever());}
    public String identity(){return VerificationTypes.identity(VERSION+CompilationRecovery.VERSION+ExtractionBatches.POLICY+"closed-dsl-source-proof-1"+model.identity()+(retriever==null?"":retriever.identity()));}
    public Map<String,Object> evaluate(Checkpoint checkpoint,List<Document> bidDocuments,Document tender,Map<String,Object> context){
        Objects.requireNonNull(checkpoint);Map<String,Object> ctx=new LinkedHashMap<>(context==null?Map.of():context);List<Document> bids=List.copyOf(bidDocuments==null?List.of():bidDocuments);long start=System.nanoTime();
        if(!checkpoint.enabled())return simple(checkpoint,"NOT_APPLICABLE","检查点未启用","DISABLED");
        if(!checkpoint.confirmed())return simple(checkpoint,"UNDETERMINED","检查点尚未确认","REQUIREMENT_UNCONFIRMED");
        if(bids.isEmpty())return simple(checkpoint,"UNDETERMINED","尚未提供本次投标资料","MISSING_BID_DOCUMENT");
        String project=bids.get(0).projectId();if(project==null||bids.stream().anyMatch(d->!project.equals(d.projectId())||d.id()==null||d.sha256()==null||d.pages()==null)||(tender!=null&&!project.equals(tender.projectId())))throw new IllegalArgumentException("核验资料必须属于同一项目且具有冻结版本");
        if(bids.stream().map(Document::id).distinct().count()!=bids.size())throw new IllegalArgumentException("核验资料 ID 重复");
        ctx.put("referenceDateSourceVerified",false);ctx.remove("originalRequirement");
        // Per-condition events remain separate: an opening date must not silently replace the bid deadline.
        if(ctx.get("referenceDates") instanceof Map<?,?> events){
            Map<String,Object> verifiedEvents=new LinkedHashMap<>();
            for(String event:List.of("BID_DEADLINE","OPENING_DATE"))if(events.get(event) instanceof Map<?,?> entry){
                Map<String,Object> copy=new LinkedHashMap<>();entry.forEach((k,v)->copy.put(String.valueOf(k),v));
                copy.put("referenceDateSourceVerified",referenceSourceValid(copy.get("referenceDateEvidence"),tender,ctx,project));verifiedEvents.put(event,copy);
            }
            ctx.put("referenceDates",verifiedEvents);
        }
        if(ctx.get("referenceDateEvidence") instanceof Map<?,?> dateEvidence){String doc=Objects.toString(dateEvidence.get("documentId"),"");int loc=RunSession.number(dateEvidence.get("page"),-1);String quote=Objects.toString(dateEvidence.get("quote"),"");List<Document> tenderSources=new ArrayList<>();if(tender!=null)tenderSources.add(tender);if(ctx.get("tenderDocuments") instanceof List<?> list)for(Object item:list)if(item instanceof Document d&&project.equals(d.projectId())&&"TENDER".equals(d.role()))tenderSources.add(d);ctx.put("referenceDateSourceVerified",!quote.isBlank()&&tenderSources.stream().anyMatch(d->d.id().equals(doc)&&d.pages().stream().anyMatch(p->p.number()==loc&&p.locationType().equals(dateEvidence.get("locationType"))&&p.text().contains(quote))));}
        String scope=VerificationTypes.identity(checkpoint.id()+bids.stream().map(d->d.id()+":"+d.version()+":"+d.sha256()+":"+VerificationTypes.identity(d.pages().toString())).collect(Collectors.joining("|")));
        List<Document> sourceDocuments=new ArrayList<>();if(tender!=null)sourceDocuments.add(tender);if(ctx.get("tenderDocuments") instanceof List<?> list)for(Object value:list)if(value instanceof Document d&&project.equals(d.projectId())&&"TENDER".equals(d.role()))sourceDocuments.add(d);
        ctx.put("tenderDocuments",sourceDocuments.stream().collect(Collectors.toMap(Document::id,d->d,(a,b)->a,LinkedHashMap::new)).values().stream().toList());
        boolean localRecovery=RecoveryReplay.localOnly(ctx);
        RunSession session=new RunSession(model,ctx,scope);EvidenceCorpus corpus=new EvidenceCorpus(bids);corpus.addVisualObservations(ctx.get("visualObservations"));SharedFactReuse shared=new SharedFactReuse(corpus,ctx,model.identity(),identity());Plan plan;List<String> issues=new ArrayList<>();List<Map<String,Object>> rejected=new ArrayList<>();Map<String,Fact> facts=new LinkedHashMap<>();List<Source> candidates=List.of();
        if(checkpoint.source()!=null){List<Document> requirementSources=new ArrayList<>();if(tender!=null)requirementSources.add(tender);if(ctx.get("tenderDocuments") instanceof List<?> list)for(Object value:list)if(value instanceof Document d&&project.equals(d.projectId())&&"TENDER".equals(d.role()))requirementSources.add(d);if(requirementSources.stream().anyMatch(d->sourceValid(checkpoint.source(),d)))ctx.put("originalRequirement",checkpoint.source().quote());}
        List<Gap> requestGaps=new ArrayList<>();
        try{plan=new RequirementCompiler().compile(checkpoint,session);}catch(InterruptedIOException e){throw interrupted();}catch(IOException e){plan=new Plan("ALL",List.of(),"COMPILATION_UNAVAILABLE",List.of("规则编译未完成，保留原条款继续等待可用服务"));issues.add(e.getMessage()!=null&&e.getMessage().equals("MODEL_BUDGET_EXHAUSTED")?"BUDGET_EXHAUSTED":"RULE_COMPILATION_UNAVAILABLE");requestGaps.add(requestGap("条款解析",e));}
        session.record("RULE_PLAN",Map.of("checkpointId",checkpoint.id(),"expected",Objects.toString(checkpoint.expected(),"")),plan);
        List<Rule> allEvidenceRules=evidenceRules(plan.rules());
        // Establish current bid parties before retrieving their licenses. Their formal source
        // pages are independent of whichever business windows happen to rank in the top 24.
        List<Fact> enterpriseBindings=EnterpriseSubjectBindings.extract(allEvidenceRules,corpus.all);
        enterpriseBindings.forEach(f->facts.put(f.id(),f));
        shared.publish(enterpriseBindings,nativeSources(corpus,enterpriseBindings),allEvidenceRules,List.of(),false,"NATIVE");
        if(RecoveryTargets.active(ctx)){
            candidates=RecoveryReplay.sources(ctx,corpus,model.identity());List<Map<String,Object>> recoveredLayout=layout(ctx,candidates,allEvidenceRules);
            candidates=withLayoutSources(corpus,candidates,recoveredLayout);recoveredLayout=layout(ctx,candidates,allEvidenceRules);ctx.put("_validatedLayoutCells",recoveredLayout);
            ProofVerifier.Verified replay=RecoveryReplay.facts(ctx,candidates,recoveredLayout,model.identity());replay.facts().forEach(f->facts.put(f.id(),f));rejected.addAll(replay.rejected());
            for(Fact fact:shared.lookup(allEvidenceRules,candidates,recoveredLayout))facts.put(fact.id(),fact);
            for(Fact fact:corpus.nativeFacts(allEvidenceRules,candidates))facts.put(fact.id(),SharedFactReuse.canonical(fact));
            for(Fact fact:VisualSubjectBindings.extract(allEvidenceRules,candidates))facts.put(fact.id(),SharedFactReuse.canonical(fact));
            RecoveryFieldCoverage.Replayed reads=RecoveryFieldCoverage.replay(ctx,allEvidenceRules,corpus,new ArrayList<>(facts.values()),session);
            shared.restoreRecoveryCoverage(reads);
            session.record("RECOVERY_FIELD_COVERAGE_REVALIDATION",Map.of("mode",str(ctx,"recoveryMode")),linked("acceptedReceiptCount",reads.receipts().size(),"completedSourceFieldReads",shared.completedReads().size(),"rejectedReasons",reads.rejected(),"modelCalls",0,"newObservation",false));
            session.record("RECOVERY_FACT_REVALIDATION",Map.of("mode",str(ctx,"recoveryMode")),linked("facts",new ArrayList<>(facts.values()),"rejected",replay.rejected(),"modelCalls",0,"targetObligations",ctx.get("recoveryTargetObligationIds"),"fullConditionTreePreserved",true));
        }
        RuleEvaluator evaluator=new RuleEvaluator(new ArrayList<>(facts.values()),corpus,ctx);List<Decision> initial=plan.rules().stream().map(evaluator::evaluate).toList();Decision decision=RuleEvaluator.combine(checkpoint.id(),checkpoint.title(),plan.logic(),initial);
        preserveValidChildren(plan.rules(),evaluator);
        List<Rule> leaves=RequirementCompiler.leaves(plan.rules()),retrievable=RecoveryTargets.select(plan.rules(),allEvidenceRules,ctx);Set<String> candidateSets=new HashSet<>();Map<String,Decision> previousDecisions=new LinkedHashMap<>(evaluator.decisions);String stopReason=RecoveryTargets.active(ctx)&&retrievable.isEmpty()?"RECOVERY_TARGET_NOT_RESOLVED":"LOCAL_STRATEGIES_FINISHED";
        Set<String> progress=progress(evaluator,shared);int plannedPackets=0,blockedPackets=0;
        session.record("RULE_COMPUTATION_INITIAL",Map.of("factIds",facts.keySet()),linked("decision",decision.map(),"obligations",initial.stream().map(Decision::map).toList()));
        for(int round=0;round<(localRecovery?1:3)&&!retrievable.isEmpty();round++){
            if(Thread.currentThread().isInterrupted())throw interrupted();session.rounds=round+1;
            Map<String,Decision> priorDecisions=previousDecisions;List<Rule> unresolved=retrievable.stream().filter(r->{Decision d=priorDecisions.get(r.id());return d==null||!d.gaps().isEmpty()||!Set.of("PASS","FAIL","NOT_APPLICABLE").contains(d.status());}).toList();
            if(unresolved.isEmpty()){stopReason="OBLIGATIONS_RESOLVED";break;}
            if(localRecovery)candidates=RecoveryReplay.sources(ctx,corpus,model.identity());
            else try{candidates=corpus.select(ObligationRecovery.retrievalRules(unresolved,ctx.get("recoveryGaps")),new ArrayList<>(facts.values()),candidates,round,retriever,session);}catch(InterruptedIOException e){throw interrupted();}
            List<Map<String,Object>> layout=layout(ctx,candidates,unresolved);candidates=withLayoutSources(corpus,candidates,layout);layout=layout(ctx,candidates,unresolved);Map<String,Map<String,Object>> knownLayout=new LinkedHashMap<>();for(Map<String,Object> row:RuleEvaluator.maps(ctx.get("_validatedLayoutCells")))knownLayout.put(str(row,"documentId")+":"+str(row,"spanId"),row);for(Map<String,Object> row:layout)knownLayout.put(str(row,"documentId")+":"+str(row,"spanId"),row);ctx.put("_validatedLayoutCells",new ArrayList<>(knownLayout.values()));String selection=VerificationTypes.identity(candidates.stream().map(Source::id).collect(Collectors.joining("|"))+json.valueToTree(layout));
            session.record("EVIDENCE_PLAN_"+round,Map.of("unresolved",unresolved.stream().map(Rule::id).toList()),linked("candidates",corpus.inputs(candidates),"layoutCells",layout.size(),"round",round));
            for(Fact f:shared.lookup(unresolved,candidates,layout))facts.put(f.id(),f);
            List<Fact> nativeFacts=new ArrayList<>(corpus.nativeFacts(unresolved,candidates).stream().map(SharedFactReuse::canonical).toList());nativeFacts.addAll(VisualSubjectBindings.extract(unresolved,candidates));for(Fact f:nativeFacts)facts.put(f.id(),f);
            shared.publish(nativeFacts,nativeSources(corpus,nativeFacts),unresolved,layout,false,"NATIVE");
            List<Rule> extractionRules=shared.extractionRules(unresolved,candidates,new RuleEvaluator(new ArrayList<>(facts.values()),corpus,ctx)).stream()
                // Keep visual rules in retrieval/shared fact lookup so sourced subject bindings are
                // still consumed. Text extraction cannot provide an image observation for them.
                .filter(rule->!localRecovery&&!Set.of("UNSUPPORTED","INPUT","REFERENCE_TIME","NOT_APPLICABLE","VISUAL","TEXT_ANOMALY","QUALITY_ADVISORY").contains(rule.predicate())&&SameFieldComparisonTarget.reference(rule).isBlank()).toList();
            Set<String> unreviewedSharedSources=new HashSet<>();if(shared.reusedFacts()>0)extractionRules.forEach(r->unreviewedSharedSources.add(r.id()));
            if(model.enabled()&&!extractionRules.isEmpty()&&!candidates.isEmpty()&&candidateSets.add(selection)){
                List<Map<String,Object>> rejectionHints=new ArrayList<>(RuleEvaluator.maps(ctx.get("previousRejectedFacts")));rejectionHints.addAll(rejected);
                Map<String,Object> input=linked("rules",extractionRules,"sources",corpus.inputs(candidates),"layoutCells",layout,"knownFacts",new ArrayList<>(facts.values()),"previousRejected",rejectionHints,"referenceDate",ctx.get("referenceDate"),"requirementScope",plan.requirementScope(),"originalRequirement",RequirementScope.resolve(checkpoint,str(ctx,"originalRequirement")).obligation());
                ExtractionBatches.Plan packetPlan=ExtractionBatches.plan(input,ctx.get("recoveryStrategy"),shared.pendingSources(extractionRules,candidates));plannedPackets+=packetPlan.batches().size();blockedPackets+=packetPlan.blocked().size();
                session.record("EXTRACTION_SPLIT_PLAN",Map.of("selection",selection,"round",round),packetPlan.audit());
                Map<String,Set<String>> reviewed=new HashMap<>();
                for(Map<String,Object> blocked:packetPlan.blocked()){List<String> ids=ProofVerifier.strings(blocked.get("obligationIds"));unreviewedSharedSources.addAll(ids);issues.add("FACT_EXTRACTION_UNAVAILABLE");requestGaps.add(new Gap("EXTRACTION_ATOMIC_INPUT_TOO_LARGE","完整来源及必要上下文超出单次处理范围，尚未读取","按来源/表格结构重新规划该窗口；不表示材料缺失","SPLIT_FAILED_REQUEST"));}
                for(Map<String,Object> planned:packetPlan.batches()){
                    List<Source> plannedSources=ExtractionBatches.sourceIds(planned).stream().map(corpus.byId::get).filter(Objects::nonNull).toList();
                    try(SharedFactReuse.Lease flight=shared.coordinate(ExtractionBatches.rules(planned),plannedSources)){
                        // Another checkpoint may have completed exactly these observations while this one waited.
                        for(Fact f:shared.lookup(ExtractionBatches.rules(planned),plannedSources,RuleEvaluator.maps(planned.get("layoutCells"))))facts.put(f.id(),f);
                        ExtractionBatches.Plan remaining=ExtractionBatches.plan(planned,ctx.get("recoveryStrategy"),shared.pendingSources(ExtractionBatches.rules(planned),plannedSources));
                        if(remaining.batches().isEmpty()&&remaining.blocked().isEmpty()){shared.extractionSkipped+=ExtractionBatches.rules(planned).size();session.record("EXTRACTION_REUSED",Map.of("packetId",planned.get("packetId")),Map.of("sourceIds",ExtractionBatches.sourceIds(planned)));}
                        ArrayDeque<Map<String,Object>> attempts=new ArrayDeque<>(remaining.batches());
                        while(!attempts.isEmpty()){
                            Map<String,Object> batch=attempts.removeFirst();
                            List<Source> sent=ExtractionBatches.sourceIds(batch).stream().map(corpus.byId::get).filter(Objects::nonNull).toList();List<Map<String,Object>> sentLayout=RuleEvaluator.maps(batch.get("layoutCells"));
                            try{shared.publishState(ExtractionBatches.rules(batch),sent,"UNREAD","EXTRACTION_PENDING");JsonNode raw=session.model("EXTRACT",batch);ProofVerifier.Verified verified=new ProofVerifier().verify(raw,sent,sentLayout);List<Fact> validated=new ArrayList<>();for(Fact f:verified.facts()){
                                if(f.kind().equals("SECTION")){Map<String,Object> attrs=new LinkedHashMap<>(f.attributes());attrs.put("fullSectionRead",SectionCoverage.covered(f,corpus,sent));f=new Fact(f.id(),f.kind(),f.subject(),f.field(),f.rawValue(),f.value(),f.unit(),f.basis(),f.scope(),f.sourceIds(),attrs);}f=SharedFactReuse.canonical(f);facts.put(f.id(),f);validated.add(f);
                            }rejected.addAll(verified.rejected());Map<String,Object> readBinding=RecoveryFieldCoverage.readBinding(selection,batch.get("packetId"),ExtractionBatches.rules(batch),ExtractionBatches.sourceIds(batch));session.record("FACT_VALIDATION_"+round,readBinding,linked("facts",validated,"rejected",verified.rejected(),"sourceIds",ExtractionBatches.sourceIds(batch),"readBinding",readBinding,"payloadChars",ExtractionBatches.size(batch)));
                            shared.publish(validated,sent,ExtractionBatches.rules(batch),sentLayout,verified.rejected().isEmpty(),"EXTRACTED",verified.rejected());
                            if(verified.rejected().isEmpty())for(Rule rule:ExtractionBatches.rules(batch))reviewed.computeIfAbsent(rule.id(),k->new HashSet<>()).addAll(ExtractionBatches.sourceIds(batch));
                            }catch(InterruptedIOException e){throw interrupted();}catch(IOException e){
                                Map<String,Object> failure=RunSession.failure(e);List<Map<String,Object>> split=Boolean.TRUE.equals(failure.get("splittable"))?ExtractionBatches.splitFailed(batch):List.of();
                                if(!split.isEmpty()){
                                    plannedPackets+=split.size();session.record("EXTRACTION_FAILURE_SPLIT",Map.of("packetId",batch.get("packetId")),linked("errorCode",failure.get("errorCode"),"splitBy",ExtractionBatches.sourceIds(batch).size()>1?"SOURCE_WINDOW":"OBLIGATION","children",split.stream().map(child->child.get("packetId")).toList(),"sourceCoveragePreserved",true));
                                    for(int at=split.size()-1;at>=0;at--)attempts.addFirst(split.get(at));continue;
                                }
                                shared.publishState(ExtractionBatches.rules(batch),sent,"FAILED",Objects.toString(failure.get("errorCode"),"MODEL_UNAVAILABLE"));unreviewedSharedSources.addAll(ExtractionBatches.rules(batch).stream().map(Rule::id).toList());issues.add(e.getMessage()!=null&&e.getMessage().equals("MODEL_BUDGET_EXHAUSTED")?"BUDGET_EXHAUSTED":"FACT_EXTRACTION_UNAVAILABLE");requestGaps.add(requestGap("证据提取",e));}
                        }
                    }catch(InterruptedIOException e){throw interrupted();}
                }
                for(Rule rule:extractionRules){Set<String> unread=shared.pendingSources(rule,candidates);unread.removeAll(reviewed.getOrDefault(rule.id(),Set.of()));if(unread.isEmpty())unreviewedSharedSources.remove(rule.id());}
            }
            evaluator=new RuleEvaluator(new ArrayList<>(facts.values()),corpus,ctx);evaluator.incompleteSourceReview.addAll(unreviewedSharedSources);List<Decision> outputs=plan.rules().stream().map(evaluator::evaluate).toList();decision=RuleEvaluator.combine(checkpoint.id(),checkpoint.title(),plan.logic(),outputs);preserveValidChildren(plan.rules(),evaluator);previousDecisions=new LinkedHashMap<>(evaluator.decisions);
            session.record("RULE_COMPUTATION_"+round,Map.of("factIds",facts.keySet()),linked("decision",decision.map(),"obligations",outputs.stream().map(Decision::map).toList()));
            if(decision.gaps().isEmpty()){stopReason="OBLIGATIONS_RESOLVED";break;}
            if(localRecovery){stopReason="LOCAL_RECOVERY_RECOMPUTED";break;}
            Set<String> currentProgress=progress(evaluator,shared),gain=new TreeSet<>(currentProgress);gain.removeAll(progress);progress.addAll(currentProgress);
            session.record("RECOVERY_PROGRESS",Map.of("round",round),linked("completedReads",shared.completedReads().size(),"newProofOrReadKeys",gain,"factIdsAreProgress",false));
            if(gain.isEmpty()){stopReason="NO_NEW_EVIDENCE_OR_CLOSED_GAP";session.record("RECOVERY_STOP",Map.of("progressFingerprint",VerificationTypes.identity(progress.toString())),Map.of("reason",stopReason));break;}
        }

        if(decision==null)decision=RuleEvaluator.combine(checkpoint.id(),checkpoint.title(),plan.logic(),List.of());
        if(!plan.issues().isEmpty()){List<Gap> gaps=new ArrayList<>(decision.gaps());gaps.add(gap("REQUIREMENT_COMPILATION_INCOMPLETE",String.join("；",plan.issues())));decision=new Decision(decision.id(),decision.description(),"UNDETERMINED","条款编译仍有未解决范围，已完成原子证明予以保留",decision.evidences(),gaps,decision.proof());}
        if(decision.status().equals("UNDETERMINED")&&!issues.isEmpty()){List<Gap> gaps=new ArrayList<>(decision.gaps());for(String issue:issues)gaps.add(gap(issue,issue.equals("BUDGET_EXHAUSTED")?"本次付费抽取预算已用尽，已完成结果保留":"系统处理未完成，已完成的规则结果保留；不表示投标材料缺失"));gaps.addAll(requestGaps);decision=new Decision(decision.id(),decision.description(),decision.status(),decision.reason(),decision.evidences(),gaps,decision.proof());}
        List<Map<String,Object>> gaps=decision.gaps().stream().distinct().map(Gap::map).toList();boolean complete=gaps.isEmpty()&&Set.of("PASS","FAIL","NOT_APPLICABLE").contains(decision.status());String workflow=complete?"COMPLETED":workflow(decision.gaps());
        RuleEvaluator finalEvaluator=evaluator;List<Map<String,Object>> externalRequests=leaves.stream().filter(r->r.predicate().equals("EXTERNAL")).filter(r->{Decision d=finalEvaluator.decisions.get(r.id());return d==null||!d.status().equals("PASS");}).flatMap(r->finalEvaluator.resolvedSubjects(r).stream().map(subject->linked("obligationId",r.id(),"type",str(r.parameters(),"externalType").isBlank()?r.field():str(r.parameters(),"externalType"),"subject",subject,"evaluationStage",str(r.parameters(),"evaluationStage").isBlank()?ctx.get("evaluationStage"):r.parameters().get("evaluationStage"),"referenceDate",ctx.get("referenceDate"),"field",r.field(),"operator",r.operator(),"expected",r.expected()))).toList();
        Set<String> requested=new HashSet<>();for(Map<String,Object> rejection:rejected){for(String id:ProofVerifier.strings(rejection.get("sourceIds"))){Source source=corpus.byId.get(id);if(source==null||source.id().startsWith("V")||evaluator.visualRequests.size()>=4||!requested.add(source.evidence().documentId()+":"+source.evidence().page()))continue;evaluator.visualRequests.add(linked("documentId",source.evidence().documentId(),"locationType",source.evidence().locationType(),"location",source.evidence().page(),"task","FIELD","fields",List.of(str(rejection,"subject"),str(rejection,"field")).stream().filter(v->!v.isBlank()).distinct().toList(),"reason",rejection.get("code")));}}
        return linked("checkpointId",checkpoint.id(),"title",checkpoint.title(),"businessDecision",decision.status(),"decisionBasis",leaves.stream().anyMatch(r->Set.of("TEXT_ANOMALY","QUALITY_ADVISORY").contains(r.predicate()))?"PROGRAM_STRUCTURAL_RULES":leaves.stream().anyMatch(r->r.predicate().equals("QUALITY"))?"LEGACY_QUALITY_DISABLED":"SOURCE_GROUNDED_RULES","workflowState",workflow,"complete",complete,"obligations",evaluator.decisions.values().stream().map(Decision::map).toList(),"gaps",gaps,"evidences",decision.evidences(),"reason",decision.reason(),"proof",decision.proof(),"plan",plan,"facts",new ArrayList<>(facts.values()),"relations",facts.values().stream().filter(f->f.kind().equals("RELATION")||f.kind().equals("PERSON_ROLE")).toList(),"visualRequests",evaluator.visualRequests,"externalRequests",externalRequests,"steps",session.steps,"modelDiagnostics",session.diagnostics(),"compilationAudit",session.compilationAudit.isEmpty()?CompilationRecovery.audit(plan,0,List.of()):session.compilationAudit,"recoveryActions",ObligationRecovery.actions(plan,evaluator.decisions,candidates,session),"metrics",linked("stopReason",stopReason,"rounds",session.rounds,"modelCalls",session.modelCalls,"priorModelCalls",session.priorCalls,"resumedSteps",session.resumedSteps,"reusedFacts",shared.reusedFacts(),"sharedPublishes",shared.sharedPublishes,"extractionSkipped",shared.extractionSkipped,"sharedRejectedFacts",shared.rejectedFacts,"facts",facts.size(),"rejectedFacts",rejected.size(),"finalCandidateCount",candidates.size(),"plannedPackets",plannedPackets,"blockedPackets",blockedPackets,"completedSourceFieldReads",shared.completedReads().size(),"extractionPolicy",ExtractionBatches.POLICY,"elapsedMs",(System.nanoTime()-start)/1_000_000,"engineVersion",VERSION),"rejectedFacts",rejected,"fieldCoverage",shared.coverage());
    }

    /** Unsupported branches and input/time decisions do not need document retrieval or paid extraction. */
    private static List<Rule> evidenceRules(List<Rule> rules){
        List<Rule> out=new ArrayList<>();for(Rule rule:rules){
            if(rule.predicate().equals("UNSUPPORTED")){out.addAll(evidenceRules(rule.children()));continue;}
            if(Set.of("INPUT","REFERENCE_TIME","NOT_APPLICABLE").contains(rule.predicate()))continue;
            if(!rule.children().isEmpty()&&!rule.predicate().equals("SCORE_COUNT")){if(RuleObligations.hasOwnPredicate(rule))out.add(RuleObligations.own(rule));out.addAll(evidenceRules(rule.children()));}else out.add(rule);
        }return out;
    }
    private static void preserveValidChildren(List<Rule> rules,RuleEvaluator evaluator){
        for(Rule rule:rules){if(rule.predicate().equals("UNSUPPORTED"))for(Rule child:rule.children())if(!child.predicate().equals("UNSUPPORTED"))evaluator.evaluate(child);preserveValidChildren(rule.children(),evaluator);}
    }
    private static List<Source> nativeSources(EvidenceCorpus corpus,List<Fact> facts){return facts.stream().flatMap(f->f.sourceIds().stream()).distinct().map(corpus.byId::get).filter(Objects::nonNull).toList();}
    private static Set<String> progress(RuleEvaluator evaluator,SharedFactReuse shared){Set<String> keys=new TreeSet<>(shared.completedReads());for(Decision d:evaluator.decisions.values())if(Set.of("PASS","FAIL","NOT_APPLICABLE").contains(d.status())&&d.gaps().isEmpty())keys.add("condition:"+d.id()+":"+d.status());return keys;}
    private static Gap requestGap(String stage,IOException error){
        String code=Objects.toString(RunSession.failure(error).get("errorCode"),"MODEL_UNAVAILABLE");
        String description=switch(code){case "REQUEST_TIMEOUT","CAPACITY_TIMEOUT"->"服务请求超时";case "OUTPUT_JSON_INVALID","OUTPUT_SCHEMA_INVALID"->"服务返回格式不符合要求";case "OUTPUT_INCOMPLETE","OUTPUT_LIMIT"->"服务返回内容不完整";case "INPUT_LIMIT"->"当前请求内容超出单次处理范围";case "NOT_CONFIGURED","MODEL_DISABLED","ENDPOINT_INVALID"->"服务配置不可用";case "TRANSPORT_FAILURE"->"服务连接失败";case "MODEL_BUDGET_EXHAUSTED"->"当前核验调用次数已达限制";default->code.startsWith("HTTP_")?"服务请求失败（"+code+"）":"服务处理未完成";};
        return new Gap(code.startsWith("MODEL_")?code:"MODEL_"+code,stage+"："+description,"保留已完成结果，仅重新处理失败步骤；不表示材料缺失","RETRY_FAILED_STEP");
    }

    private boolean sourceValid(Evidence e,Document tender){return tender.id().equals(e.documentId())&&tender.pages().stream().anyMatch(p->p.number()==e.page()&&p.locationType().equals(e.locationType())&&p.text().contains(e.quote()));}
    private boolean referenceSourceValid(Object raw,Document tender,Map<String,Object> context,String project){
        if(!(raw instanceof Map<?,?> e))return false;
        List<Document> sources=new ArrayList<>();if(tender!=null)sources.add(tender);
        if(context.get("tenderDocuments") instanceof List<?> list)for(Object item:list)if(item instanceof Document d&&project.equals(d.projectId())&&"TENDER".equals(d.role()))sources.add(d);
        String quote=Objects.toString(e.get("quote"),"");int page=RunSession.number(e.get("page"),-1);
        return !quote.isBlank()&&sources.stream().anyMatch(d->d.id().equals(e.get("documentId"))&&d.pages().stream().anyMatch(p->p.number()==page&&p.locationType().equals(e.get("locationType"))&&p.text().contains(quote)));
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> layout(Map<String,Object> ctx,List<Source> candidates,List<Rule> rules){Map<String,Map<String,Object>> rows=new LinkedHashMap<>();Set<String> visited=new HashSet<>();if(ctx.get("layoutLookup") instanceof Function<?,?> lookup){for(Source s:candidates){String location=s.evidence().documentId()+":"+s.evidence().locationType()+":"+s.evidence().page();if(!visited.add(location))continue;Object result=((Function<Map<String,Object>,?>)lookup).apply(linked("documentId",s.evidence().documentId(),"documentHash",s.documentHash(),"documentVersion",s.documentVersion(),"locationType",s.evidence().locationType(),"location",s.evidence().page(),"sourceIds",List.of(s.id()),"subject",rules.stream().map(Rule::subject).filter(v->!v.isBlank()).findFirst().orElse(""),"sourceQuote",candidates.stream().filter(c->c.evidence().documentId().equals(s.evidence().documentId())&&c.evidence().locationType().equals(s.evidence().locationType())&&c.evidence().page()==s.evidence().page()).map(c->c.evidence().quote()).collect(Collectors.joining("\n"))));for(Map<String,Object> row:RuleEvaluator.maps(result))rows.put(str(row,"documentId")+":"+str(row,"spanId"),row);if(rows.size()>300)break;}}else for(Map<String,Object> row:RuleEvaluator.maps(ctx.get("layoutFacts")))rows.put(str(row,"documentId")+":"+str(row,"spanId"),row);return rows.values().stream().filter(row->candidates.stream().anyMatch(s->s.evidence().documentId().equals(str(row,"documentId"))&&(str(row,"documentHash").isBlank()||s.documentHash().equals(str(row,"documentHash")))&&(!row.containsKey("documentVersion")||s.documentVersion()==RunSession.number(row.get("documentVersion"),-1)))).limit(300).toList();}
    private List<Source> withLayoutSources(EvidenceCorpus corpus,List<Source> prior,List<Map<String,Object>> cells){Map<String,Source> selected=new LinkedHashMap<>();prior.forEach(s->selected.put(s.id(),s));int chars=prior.stream().mapToInt(s->s.evidence().quote().length()).sum();for(Map<String,Object> cell:cells){if(!"TABLE_CELL".equals(str(cell,"kind"))||str(cell,"text").isBlank())continue;for(Source s:corpus.all){if(selected.containsKey(s.id())||!s.evidence().documentId().equals(str(cell,"documentId"))||!s.evidence().locationType().equals(str(cell,"locationType"))||s.evidence().page()!=RunSession.number(cell.get("location"),-1)||!s.evidence().quote().contains(str(cell,"text")))continue;if(selected.size()<24&&chars+s.evidence().quote().length()<=32000){selected.put(s.id(),s);chars+=s.evidence().quote().length();}break;}}return List.copyOf(selected.values());}
    private static CancellationException interrupted(){Thread.currentThread().interrupt();return new CancellationException("核验已中断");}
    private static String workflow(List<Gap> gaps){if(gaps.stream().anyMatch(g->Set.of("RULE_COMPILATION_UNAVAILABLE","FACT_EXTRACTION_UNAVAILABLE").contains(g.code())))return "ENGINE_FAILURE";if(gaps.stream().anyMatch(g->g.code().equals("BUDGET_EXHAUSTED")))return "BUDGET_EXHAUSTED";if(gaps.stream().anyMatch(g->g.code().equals("REQUIREMENT_COMPARISON_TARGET_UNRESOLVED")))return "REQUIREMENT_UNPARSED";if(gaps.stream().anyMatch(g->g.code().equals("WAIT_STAGE")))return "WAIT_STAGE";if(gaps.stream().anyMatch(g->g.code().contains("AMBIGUOUS")||g.code().contains("UNSUPPORTED")||g.code().contains("COMPILATION")||g.code().contains("CONFLICTING")||g.code().equals("FULL_SECTION_REQUIRED")||g.code().equals("SHARED_SOURCE_REVIEW_REQUIRED")||g.code().equals("SUBJECT_IDENTITY_CONFLICT")))return "NEEDS_REVIEW";return "WAIT_INPUT";}
    private Map<String,Object> simple(Checkpoint cp,String status,String reason,String code){boolean complete=status.equals("NOT_APPLICABLE");return linked("checkpointId",cp.id(),"title",cp.title(),"businessDecision",status,"decisionBasis","RULE_POLICY","workflowState",complete?"COMPLETED":"WAIT_INPUT","complete",complete,"obligations",List.of(),"gaps",complete?List.of():List.of(gap(code,reason).map()),"evidences",List.of(),"reason",reason,"metrics",Map.of("engineVersion",VERSION),"facts",List.of(),"relations",List.of(),"visualRequests",List.of(),"externalRequests",List.of());}
}
