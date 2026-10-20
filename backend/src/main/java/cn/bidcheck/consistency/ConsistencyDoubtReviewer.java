package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.retrieval.*;
import cn.bidcheck.semantic.*;
import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;
import static cn.bidcheck.consistency.ConsistencyRunSupport.*;

/** Review is additive. Original rule conclusions and human decisions are never overwritten. */
@Service
public final class ConsistencyDoubtReviewer {
    public static final String VERSION = "consistency-doubt-review-6-derived-observed-conclusions";
    /** Compatibility metadata: -1 means no aggregate group, attempt or stage-time limit. */
    public static final int MAX_GROUPS = -1, MAX_MODEL_ATTEMPTS = -1;
    public static final long ADMISSION_MILLIS = -1;
    private static final ObjectMapper JSON = new ObjectMapper();
    @FunctionalInterface public interface Lookup { RetrievalResult search(Document doc, String query, Duration budget) throws IOException; }
    @FunctionalInterface public interface Judge { SemanticAssessment assess(Checkpoint cp, Document doc, List<Evidence> packet, BooleanSupplier reserve); }
    private final Lookup lookup;
    private final Judge judge;
    private final BooleanSupplier enabled;

    @Autowired public ConsistencyDoubtReviewer(HybridRetriever retriever, SemanticChecker semantic) {
        this((doc,query,budget)->retriever.search(doc,query,8,budget), semantic::assessConsistencyPacket, semantic::enabled);
    }
    public ConsistencyDoubtReviewer(Lookup lookup, Judge judge, BooleanSupplier enabled) {
        this.lookup=lookup; this.judge=judge; this.enabled=enabled;
    }

    /** Callback persists every intent and receipt under the current task lease. */
    public Map<String,Object> review(Document doc, Map<String,Object> report, BooleanSupplier reserve,
                                     Consumer<Map<String,Object>> persist) {
        Object previousElapsed=map(report.get("doubtReview")).get("elapsedMillis");
        long savedMillis=previousElapsed instanceof Number n?Math.max(0,Math.min(n.longValue(),86_400_000)):0;
        long started=System.nanoTime()-savedMillis*1_000_000;
        List<Map<String,Object>> groups=maps(report.get("groups")).stream().map(LinkedHashMap::new).map(v->(Map<String,Object>)v).toList();
        report.put("groups",groups);
        Map<String,Map<String,Object>> occurrences=new HashMap<>();
        for(Map<String,Object> o:maps(report.get("occurrences"))) occurrences.put(string(o,"id"),o);
        ConsistencyObjectRecovery objectRecovery=new ConsistencyObjectRecovery(doc,occurrences.values());
        ConsistencyDerivedConclusion derived=new ConsistencyDerivedConclusion(doc,occurrences.values());
        List<Map<String,Object>> ordered=new ArrayList<>(groups);
        ordered.sort(Comparator.comparingInt(g->"SUSPECTED".equals(g.get("status"))?0:"CONFLICT".equals(g.get("status"))?1:2));
        for(Map<String,Object> group:ordered) {
            if(Boolean.TRUE.equals(group.get("recoveryRetained")))continue;
            Map<String,Object> prior=map(group.get("targetedReview"));
            if(!prior.isEmpty()) {
                if(!VERSION.equals(prior.get("policyVersion"))) throw new IllegalStateException("Doubt review policy mismatch");
                if("IN_PROGRESS".equals(prior.get("status"))) {
                    Map<String,Object> recovered=new LinkedHashMap<>(prior);
                    recovered.put("status","ERROR"); recovered.put("reason","上次复核在保存收据前中断，实际发送情况未知；不自动重复调用，保留人工复核。");
                    recovered.put("modelRequested",null); recovered.put("requestOutcomeUnknown",true);
                    recovered.put("model",values("requested",null,"state","UNKNOWN","attempts",List.of()));
                    group.put("targetedReview",recovered); updateSummary(report,started); persist.accept(group);
                }
                derived.evaluate(group);
                continue;
            }
            checkInterrupted();
            Map<String,Object> receipt=base(group,doc);
            List<Evidence> originals=evidence(group.get("evidences"));
            List<Evidence> validOriginal=unique(originals.stream().filter(e->valid(doc,e)).toList());
            int invalidOriginal=(int)originals.stream().filter(e->!valid(doc,e)).count();
            receipt.put("evidences",validOriginal);
            receipt.put("candidateCount",originals.size()); receipt.put("acceptedEvidenceCount",validOriginal.size());
            receipt.put("rejectedEvidenceCount",invalidOriginal);
            receipt.put("deduplicatedEvidenceCount",originals.size()-invalidOriginal-validOriginal.size());
            Map<String,Object> triage=triage(doc,group,occurrences,validOriginal,invalidOriginal);
            receipt.put("triage",triage);
            if(invalidOriginal>0 || validOriginal.isEmpty()) {
                finish(receipt,"EVIDENCE_INSUFFICIENT","原始比较证据未通过当前文档原文校验，不能用其他检索片段替代缺失的比较来源。");
            } else if("RULE_COMPARISON_AVAILABLE".equals(triage.get("reasonCode"))) {
                finish(receipt,"RULE_CONFIRMED","本地规则已形成有来源的差异；保留原结论与双方证据，交由人工确认适用条件。");
            } else if(!Boolean.TRUE.equals(triage.get("eligible"))) {
                if(Set.of("NO_DISTINCT_VALUES","UNALIGNED_AGGREGATE","ORIGINAL_PACKET_LIMIT").contains(string(triage,"reasonCode"))) {
                    finish(receipt,"IN_PROGRESS","正在按有来源的对象拆分缺口并定向补查；原始事项保留。");
                    group.put("targetedReview",receipt);updateSummary(report,started);persist.accept(group);
                    try { recoverObjects(doc,group,occurrences,receipt,objectRecovery,reserve,()->{updateSummary(report,started);persist.accept(group);}); }
                    catch(TaskLeaseLostException|CancellationException stopped){throw stopped;}
                    catch(InterruptedIOException interrupted){Thread.currentThread().interrupt();throw new CancellationException("Object retrieval interrupted");}
                    catch(Exception failure){finish(receipt,"ERROR","对象定向补查未完成；已保存原始事项与子任务收据。错误类别："+failure.getClass().getSimpleName());}
                } else finish(receipt,"EVIDENCE_INSUFFICIENT",string(triage,"reason"));
            } else {
                receipt.put("admitted",true); finish(receipt,"IN_PROGRESS","正在核对原始比较证据并定向补充相关原文。");
                group.put("targetedReview",receipt); updateSummary(report,started); persist.accept(group);
                try { reviewOne(doc,group,occurrences,receipt,validOriginal,reserve); }
                catch(TaskLeaseLostException|CancellationException stopped) { throw stopped; }
                catch(InterruptedIOException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException("Doubt retrieval interrupted"); }
                catch(Exception failure) { finish(receipt,"ERROR","定向复核未取得可用结果；原结论和已验证证据保留，请人工核对。错误类别："+failure.getClass().getSimpleName()); }
            }
            group.put("targetedReview",receipt);derived.evaluate(group); updateSummary(report,started); persist.accept(group);
        }
        updateSummary(report,started);
        return report;
    }

    private void recoverObjects(Document doc,Map<String,Object> group,Map<String,Map<String,Object>> occurrences,
                                Map<String,Object> receipt,ConsistencyObjectRecovery recovery,BooleanSupplier reserve,Runnable persist)throws IOException {
        Map<String,Object> plan=recovery.plan(group,occurrences);receipt.put("objectRecovery",plan);
        var sourceScope=cn.bidcheck.verification.engine.DocumentScopeProof.forDocument(doc);
        List<Map<String,Object>> attempts=new ArrayList<>();int searches=0,localVerified=0;boolean requested=false;Set<String> searched=new HashSet<>();
        for(Map<String,Object> object:maps(plan.get("objects"))) {
            checkInterrupted();List<Evidence> originals=evidence(object.get("evidences"));
            Map<String,Object> localRelations=recovery.verifyRelations(object,originals);
            object.put("relationValidation",localRelations);
            if(Boolean.TRUE.equals(localRelations.get("exclusionValidated"))){
                object.put("status","EXCLUDED_WITH_SOURCE_PROOF");object.put("stopReason","OUTSIDE_CURRENT_COMPARISON");
                object.put("modelRequested",false);continue;
            }
            if(Boolean.TRUE.equals(localRelations.get("contextResolved"))&&!Boolean.TRUE.equals(object.get("comparisonReady"))){
                object.put("missingDimensions",localRelations.get("missingDimensions"));
                object.put("status","OBJECT_CONTEXT_VERIFIED");object.put("stopReason","LOCAL_SOURCE_RELATIONS_COMPLETE");
                object.put("modelRequested",false);object.put("coverageComplete",false);localVerified++;
                // Reuse exact local proof before performing another remote embedding/retrieval query.
                continue;
            }
            if(Boolean.TRUE.equals(object.get("comparisonReady"))) {
                Map<String,Object> child=new LinkedHashMap<>(group);child.put("id",object.get("id"));child.put("occurrenceIds",object.get("occurrenceIds"));child.put("values",object.get("values"));
                child.put("subject",object.get("objectKey"));child.put("reason",object.get("reason"));
                Map<String,Object> childReceipt=base(child,doc);childReceipt.put("candidateCount",originals.size());childReceipt.put("acceptedEvidenceCount",originals.size());childReceipt.put("evidences",originals);
                finish(childReceipt,"IN_PROGRESS","同一对象的比较双方已验证，正在定向核对。");object.put("review",childReceipt);persist.run();
                reviewOne(doc,child,occurrences,childReceipt,originals,reserve);
                object.put("status",childReceipt.get("status"));attempts.addAll(maps(map(childReceipt.get("model")).get("attempts")));requested|=Boolean.TRUE.equals(childReceipt.get("modelRequested"));searches++;
            } else if(Boolean.TRUE.equals(object.get("searchAllowed"))) {
                String query=string(object,"searchQuery");String fingerprint=id(doc.sha256()+object.get("objectKey")+query+ConsistencyObjectRecovery.VERSION);
                object.put("strategyFingerprint",fingerprint);
                if(!searched.add(fingerprint)){object.put("stopReason","IDENTICAL_STRATEGY_ALREADY_OBSERVED");continue;}
                object.put("retrieval",values("mode","IN_PROGRESS","query",query));persist.run();
                RetrievalResult result=lookup.search(doc,clip(query,1600),Duration.ofSeconds(12));searches++;
                String anchor=Objects.toString(map(object.get("identityProof")).get("instanceId"),"");
                // A current project-name comparison uses only original, server-scoped occurrences.
                // Retrieved snippets remain candidates; they never become model-supplied identity facts.
                List<Evidence> additions=result.hits().stream().filter(e->valid(doc,e)).filter(e->"CURRENT_PROJECT_NAME".equals(object.get("objectKind"))
                    ?e.quote().contains("项目名称")&&Boolean.TRUE.equals(sourceScope.currentEvidence(e).get("verifiedCurrent"))
                    :!anchor.isBlank()&&e.quote().matches("(?s).*(?<![A-Za-z0-9])"+java.util.regex.Pattern.quote(anchor)+"(?![A-Za-z0-9]).*")).toList();
                List<Evidence> all=new ArrayList<>(originals);all.addAll(additions);List<Evidence> merged=unique(all);
                object.put("retrieval",values("mode",result.mode(),"query",query,"diagnostics",result.diagnostics(),"candidateCount",result.hits().size(),"acceptedEvidenceCount",additions.size(),"newEvidenceCount",merged.size()-originals.size(),"scope","EXACT_OBJECT_ANCHOR_ONLY"));
                object.put("evidences",merged);
                Map<String,Object> relations=recovery.verifyRelations(object,merged);object.put("relationValidation",relations);
                object.put("missingDimensions",relations.get("missingDimensions"));
                if(Boolean.TRUE.equals(relations.get("contextResolved"))){
                    object.put("status","OBJECT_CONTEXT_VERIFIED");object.put("stopReason","OBJECT_RELATIONS_VERIFIED_COVERAGE_PENDING");
                }else object.put("stopReason","RELATION_CONFLICT".equals(relations.get("status"))?"OBJECT_RELATION_CONFLICT":merged.size()==originals.size()?"NO_NEW_OBJECT_EVIDENCE":"ADDITIONAL_SOURCE_REQUIRES_TYPED_RELATION");
                object.put("modelRequested",false);object.put("coverageComplete",false);
            } else {
                object.put("stopReason","OBJECT_IDENTITY_REQUIRED_BEFORE_SEARCH");
                // No external work occurred. Persist these local outcomes once with the completed group.
                continue;
            }
            persist.run();
        }
        receipt.put("modelRequested",requested);receipt.put("model",values("requested",requested,"attempts",attempts));
        receipt.put("admitted",searches>0);
        receipt.put("retrieval",values("mode",searches>0?"OBJECT_TARGETED":"NOT_RUN","localStrategy","OBJECT_BOUNDARIES","objectSearches",searches));
        receipt.put("decision","OBJECT_RECOVERY_RECORDED");receipt.put("localVerifiedObjects",localVerified);
        finish(receipt,searches>0?"EVIDENCE_AUGMENTED":localVerified>0?"LOCAL_RELATIONS_VERIFIED":"EVIDENCE_INSUFFICIENT",
            "已保留原始事项并记录 "+maps(plan.get("objects")).size()+" 个对象子任务，其中 "+localVerified+" 个直接复用原文关系证明；原组全部记录将独立重算，全文覆盖不由单个对象推定。");
    }

    private void reviewOne(Document doc,Map<String,Object> group,Map<String,Map<String,Object>> occurrences,
                           Map<String,Object> receipt,List<Evidence> originals,BooleanSupplier reserve) throws IOException {
        checkInterrupted();
        String query=string(group,"fieldLabel")+" "+string(group,"subject")+" "+string(group,"title")+" "+Objects.toString(group.get("values"),"");
        RetrievalResult result=lookup.search(doc,clip(query,1600),Duration.ofSeconds(12));
        receipt.put("retrieval",values("mode",result.mode(),"diagnostics",result.diagnostics(),"query",clip(query,1600),"scope","GROUP_EVIDENCE_ONLY"));
        List<Evidence> additions=result.hits().stream().filter(e->valid(doc,e)).toList();
        List<Evidence> all=new ArrayList<>(originals);all.addAll(additions);
        List<Evidence> packet=unique(all);
        int rawCount=((Number)receipt.get("candidateCount")).intValue()+result.hits().size();
        receipt.put("candidateCount",rawCount);
        receipt.put("acceptedEvidenceCount",packet.size());
        receipt.put("rejectedEvidenceCount",result.hits().size()-additions.size());
        receipt.put("deduplicatedEvidenceCount",rawCount-(result.hits().size()-additions.size())-packet.size());
        receipt.put("evidences",List.copyOf(packet));
        List<Map<String,Object>> sides=new ArrayList<>();
        if(group.get("occurrenceIds") instanceof List<?> ids) for(Object id:ids) {Map<String,Object> o=occurrences.get(id.toString());if(o!=null)sides.add(o);}
        Set<String> distinctValues=new LinkedHashSet<>();
        for(Map<String,Object> o:sides) if(!string(o,"value").isBlank()) distinctValues.add(string(o,"value"));
        if(distinctValues.size()<2 || sides.stream().anyMatch(o->!valid(doc,toEvidence(o.get("evidence"))))) {
            finish(receipt,"EVIDENCE_INSUFFICIENT","已补充候选原文，但尚未取得两个可追溯的不同值或完整比较来源；不调用模型猜测缺失关系。"); return;
        }
        if(packet.size()>24 || packet.stream().anyMatch(e->e.quote().length()>1800)) {
            finish(receipt,"EVIDENCE_AUGMENTED","原始比较证据超过单组模型输入上限；完整保留证据供人工复核，未截断或丢弃比较双方。"); return;
        }
        if(!enabled.getAsBoolean()) {finish(receipt,"MODEL_UNAVAILABLE","已补充证据，模型服务未启用；可对照原文人工复核。");return;}
        String expected="核对以下字段不同表述是否属于同一对象、当前项目、标段、时点及金额口径："+string(group,"fieldLabel")+"。"
            +"比较值："+clip(distinctValues.toString(),500)+"。原规则原因："+clip(string(group,"reason"),500)
            +"。必须引用原始比较双方；范围或身份不能对齐时保留待核；不得凭同名、默认归属或相似度确认相同主体，不得猜改OCR。";
        Checkpoint cp=new Checkpoint("consistency-doubt-"+string(group,"id"),clip(string(group,"title"),250),"全文信息检查","HIGH",
            "SEMANTIC_RESPONSE",string(group,"field"),"REVIEW",expected,"",true,true,true,null);
        SemanticAssessment assessment=judge.assess(cp,doc,List.copyOf(packet),()->{checkInterrupted();return reserve.getAsBoolean();});
        Map<String,Object> audit=assessment.audit(), model=map(audit.get("model"));
        receipt.put("model",model); receipt.put("modelRequested",Boolean.TRUE.equals(model.get("requested")));
        receipt.put("decision",model.getOrDefault("decision","UNKNOWN")); receipt.put("guards",audit.getOrDefault("guards",List.of()));
        Evaluation evaluation=assessment.evaluation();
        List<Evidence> citedEvidence=evaluation.evidences();
        boolean bothSides=distinctValues.stream().allMatch(value->sides.stream().filter(o->value.equals(string(o,"value")))
            .map(o->toEvidence(o.get("evidence"))).anyMatch(e->citedEvidence.stream().anyMatch(cited->covers(cited,e))));
        if(!"UNDETERMINED".equals(evaluation.status())&&!bothSides) {
            List<Map<String,Object>> guards=new ArrayList<>(maps(receipt.get("guards")));
            guards.add(values("code","COMPARISON_SIDES_NOT_CITED","reason","模型建议未同时引用原始比较的不同值，不能用补充片段替代比较双方。"));
            receipt.put("guards",guards);
            evaluation=new Evaluation("UNDETERMINED","模型建议未覆盖原始差异双方，已撤回建议，需人工核对。","对照每个原始值及其上下文。",originals,0,"CONSISTENCY_REVIEW_GUARD");
            Map<String,Object> guarded=new LinkedHashMap<>(model);
            List<Map<String,Object>> attempts=maps(model.get("attempts")).stream().map(LinkedHashMap::new).map(v->(Map<String,Object>)v).toList();
            if(!attempts.isEmpty())attempts.get(attempts.size()-1).put("state","GUARDED_OUTPUT");
            guarded.put("attempts",attempts);receipt.put("model",guarded);
        }
        receipt.put("recommendation",values("status",evaluation.status(),"reason",evaluation.reason(),"suggestion",evaluation.suggestion(),"evidences",evaluation.evidences(),"humanConfirmationRequired",true));
        if("BUDGET_DEFERRED".equals(model.get("decision"))) finish(receipt,"BUDGET_DEFERRED","模型请求未获准或未能继续，复核未完成；保留已验证证据，具体原因见请求记录。" );
        else if(!maps(receipt.get("guards")).isEmpty() || "UNDETERMINED".equals(evaluation.status())) finish(receipt,"EVIDENCE_AUGMENTED","已补充并核对候选证据，模型未形成可采纳的比较建议；具体缺口见复核建议与来源保护记录。");
        else finish(receipt,"MODEL_REVIEWED","已得到引用原文的模型复核建议，尚需人工确认；原规则结论保持不变。");
    }

    private static Map<String,Object> base(Map<String,Object> group,Document doc) {
        return values("policyVersion",VERSION,"documentId",doc.id(),"documentVersion",doc.version(),"documentHash",doc.sha256(),
            "admitted",false,"candidateCount",0,"acceptedEvidenceCount",0,"rejectedEvidenceCount",0,"deduplicatedEvidenceCount",0,
            "missingDimensions",missing(group),"modelRequested",false,"decision","NOT_REQUESTED","evidences",List.of(),
            "retrieval",Map.of("mode","NOT_RUN"),"model",Map.of("attempts",List.of(),"requested",false),"guards",List.of());
    }
    private static Map<String,Object> triage(Document doc,Map<String,Object> group,Map<String,Map<String,Object>> occurrences,
                                              List<Evidence> originals,int invalidOriginal) {
        List<?> ids=group.get("occurrenceIds") instanceof List<?> values?values:List.of();
        List<Map<String,Object>> sides=ids.stream().map(id->occurrences.get(Objects.toString(id,""))).filter(Objects::nonNull).toList();
        long distinct=sides.stream().map(o->string(o,"value")).filter(v->!v.isBlank()).distinct().count();
        Map<String,Object> result=values("eligible",false,"reasonCode","COMPARABLE_VALUES","originalEvidenceCount",originals.size(),
            "occurrenceCount",sides.size(),"distinctValueCount",distinct,"scope","LOCAL_COMPARISON_PREREQUISITES",
            "humanConfirmationRequired",true);
        String code,reason;
        if(invalidOriginal>0||originals.isEmpty()) {
            code="INVALID_ORIGINAL";reason="原始证据未通过文档来源核对，请先核对原文。";
        } else if(ids.isEmpty()||sides.size()!=ids.size()||sides.stream().anyMatch(o->{
            Evidence side=toEvidence(o.get("evidence"));
            return !valid(doc,side)||originals.stream().noneMatch(original->covers(original,side));
        })) {
            code="MISSING_COMPARISON_SIDE";reason="尚缺完整、可追溯的比较双方，需先补齐来源；未启动检索或模型。";
        } else if("CONFLICT".equals(group.get("status"))) {
            code="RULE_COMPARISON_AVAILABLE";reason="已有本地规则比较结果，保留原始差异供人工确认。";
        } else if(distinct<2) {
            code="NO_DISTINCT_VALUES";reason="当前仅有一种可追溯取值，尚无两处不同值可比较；需补核对象或范围依据，未启动检索或模型。";
        } else if(originals.size()>24||originals.stream().anyMatch(e->e.quote().length()>1800)) {
            code="ORIGINAL_PACKET_LIMIT";reason="该组汇集的原文较多，需先按具体项目、主体和口径拆分；完整证据已保留，未截断后交给模型判断。";
        } else if("EVIDENCE_INSUFFICIENT".equals(group.get("status"))&&!string(map(group.get("comparisonContext")),"aggregation").isBlank()) {
            code="UNALIGNED_AGGREGATE";reason="这是按缺口汇总的待核材料，尚未形成同一对象、项目及口径的比较双方；请先核对归属依据，未将不同材料强行交给模型比较。";
        } else {
            code="COMPARABLE_VALUES";reason="原始比较双方及取值已通过本地核对，将继续定向补检与复核，不因组数或阶段时长提前暂缓。";result.put("eligible",true);
        }
        result.put("reasonCode",code);result.put("reason",reason);return result;
    }
    private static List<String> missing(Map<String,Object> group) {
        LinkedHashSet<String> gaps=new LinkedHashSet<>();
        if(group.get("missingContext") instanceof List<?> values) values.forEach(v->gaps.add(v.toString()));
        Map<String,Object> context=map(group.get("comparisonContext"));
        for(String key:List.of("subject","scope")) if(string(context,key).isBlank()||"UNRESOLVED".equals(context.get(key))) gaps.add(key.equals("subject")?"可确认的比较对象":"可确认的项目范围");
        if("UNRESOLVED".equals(group.get("contextStatus"))) gaps.add("上下文归属依据");
        return List.copyOf(gaps);
    }
    private static void finish(Map<String,Object> receipt,String status,String reason) {receipt.put("status",status);receipt.put("reason",reason);}
    public static void updateSummary(Map<String,Object> report,long started) {
        Map<String,Integer> counts=new LinkedHashMap<>(),triageCounts=new LinkedHashMap<>();int requests=0,attempts=0,retrieval=0,reviewed=0,deferred=0,unknown=0,triaged=0,localOnly=0;
        for(Map<String,Object> g:maps(report.get("groups"))) {
            Map<String,Object> r=map(g.get("targetedReview"));if(r.isEmpty())continue;
            String status=string(r,"status");counts.merge(status,1,Integer::sum);
            if(status.endsWith("DEFERRED"))deferred++;else if(!"IN_PROGRESS".equals(status))reviewed++;
            if(Boolean.TRUE.equals(r.get("modelRequested")))requests++;
            if(Boolean.TRUE.equals(r.get("requestOutcomeUnknown")))unknown++;
            Map<String,Object> triage=map(r.get("triage"));
            if(!triage.isEmpty()) {
                triaged++;triageCounts.merge(string(triage,"reasonCode"),1,Integer::sum);
                if(!Boolean.TRUE.equals(triage.get("eligible"))&&!Boolean.TRUE.equals(r.get("admitted"))&&!"IN_PROGRESS".equals(status))localOnly++;
            }
            attempts+=maps(map(r.get("model")).get("attempts")).size();
            if(!"NOT_RUN".equals(map(r.get("retrieval")).get("mode")))retrieval++;
        }
        report.put("doubtReview",values("policyVersion",VERSION,"totalGroups",maps(report.get("groups")).size(),"reviewedGroups",reviewed,
            "deferredGroups",deferred,"statusCounts",counts,"modelAttempts",attempts,"modelRequestedGroups",requests,"retrievalGroups",retrieval,
            "triagedGroups",triaged,"localOnlyGroups",localOnly,"triageReasonCounts",triageCounts,"pendingManualGroups",maps(report.get("groups")).size(),
            "unknownRequestGroups",unknown,"elapsedMillis",elapsed(started),"elapsedObservationIncomplete",unknown>0,"maxGroups",MAX_GROUPS,"attemptBudget",MAX_MODEL_ATTEMPTS,"admissionMillis",ADMISSION_MILLIS,
            "unlimitedGroups",true,"unlimitedModelAttempts",true,"unlimitedAdmissionTime",true,"processAllEligibleGroups",true,
            "boundary","所有组先核对本地比较前提，满足前提的组逐一处理，不设置组数、模型调用总量或阶段时长上限。缺少比较双方或归属依据的材料明确待核；单组输入大小、单次检索12秒、请求超时与重试上限、来源校验和中断保护仍然生效。阶段处理数不是业务复核完成数；原结论和模型建议均需人工确认。中断预约与实际HTTP分别统计。"));
    }
    private static boolean valid(Document doc,Evidence e) {return e!=null&&doc.id().equals(e.documentId())&&e.quote()!=null&&!e.quote().isBlank()
        &&doc.pages().stream().anyMatch(p->p.number()==e.page()&&Objects.equals(p.locationType(),e.locationType())&&p.text()!=null&&p.text().contains(e.quote()));}
    private static boolean covers(Evidence cited,Evidence original) {return cited!=null&&original!=null&&cited.documentId().equals(original.documentId())&&cited.page()==original.page()
        &&Objects.equals(cited.locationType(),original.locationType())&&cited.quote().contains(original.quote());}
    private static Evidence toEvidence(Object raw) {try{return raw instanceof Evidence e?e:JSON.convertValue(raw,Evidence.class);}catch(IllegalArgumentException invalid){return null;}}
    private static List<Evidence> unique(List<Evidence> input) {Map<String,Evidence> out=new LinkedHashMap<>();for(Evidence e:input)out.putIfAbsent(e.documentId()+"|"+e.locationType()+"|"+e.page()+"|"+e.quote(),e);return List.copyOf(out.values());}
    private static List<Evidence> evidence(Object raw) {if(!(raw instanceof List<?> list))return List.of();return list.stream().map(ConsistencyDoubtReviewer::toEvidence).toList();}
    private static long elapsed(long started) {return (System.nanoTime()-started)/1_000_000;}
    private static String clip(String text,int limit) {return text.substring(0,Math.min(text.length(),limit));}
    private static void checkInterrupted() {if(Thread.currentThread().isInterrupted())throw new CancellationException("Doubt review interrupted");}
}
