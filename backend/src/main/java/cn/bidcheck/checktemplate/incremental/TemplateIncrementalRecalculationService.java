package cn.bidcheck.checktemplate.incremental;

import cn.bidcheck.checktemplate.TemplateApiException;
import cn.bidcheck.domain.Domain.Finding;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import cn.bidcheck.service.BidService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates an immutable, explicit candidate result for a dependency-level template recalculation.
 * A completed run and its findings remain historical records; callers can inspect the candidate
 * conclusions here and deliberately publish a new immutable result version after review.
 */
@Service
public class TemplateIncrementalRecalculationService {
    public static final String SCHEMA="template-incremental-recalculation-v3";
    private static final String KIND="template_incremental_recalc";
    private static final int MAX_CHANGED_NEEDS=500;

    private final Store store;
    private final BidService bids;
    private final TemplateDependencyGraphService graphs;
    private final TemplateIncrementalPublicationService publications;
    private final TransactionTemplate tx;

    public TemplateIncrementalRecalculationService(Store store,BidService bids,TemplateDependencyGraphService graphs,
                                                   TemplateIncrementalPublicationService publications,
                                                   PlatformTransactionManager transactionManager){
        this.store=store;this.bids=bids;this.graphs=graphs;this.publications=publications;this.tx=new TransactionTemplate(transactionManager);
    }

    /** Advisory plan used by a client that wants to bind execution to the source-result digest. */
    public Map<String,Object> plan(String runId,long expectedRevision,Collection<String> changedNeedKeys){
        RequestContext context=context(runId,expectedRevision,changedNeedKeys);
        return planView(context);
    }

    public Map<String,Object> recalculate(String runId,long expectedRevision,Collection<String> changedNeedKeys,
                                         String expectedSourceResultDigest){
        RequestContext context=context(runId,expectedRevision,changedNeedKeys);
        if(expectedSourceResultDigest!=null&&!expectedSourceResultDigest.isBlank()
            &&!expectedSourceResultDigest.trim().equals(context.sourceResultDigest()))
            throw TemplateApiException.conflict("SOURCE_FINDINGS_CHANGED","模板检查结果已与调用方取得的来源摘要不一致；历史记录未被修改",safeRevision(context.graphRevision()));

        String recordId=recordId(context);
        Stored existing=store.mapper().get(recordId);
        if(existing!=null)return requireExisting(existing,context);

        Map<String,Finding> candidates=context.plan().noOp()?Map.of():bids.evaluateFrozenTemplateCandidates(runId,context.affectedCheckpointIds());
        List<Map<String,Object>> results=new ArrayList<>();List<String> changed=new ArrayList<>();
        for(String checkpointId:context.affectedCheckpointIds()){
            Finding before=context.beforeFindings().get(checkpointId),candidate=candidates.get(checkpointId);
            if(before==null||candidate==null)throw conflict("SOURCE_FINDING_MISSING","受影响模板项缺少来源或候选结果",context.graphRevision());
            boolean evaluationChanged=!Objects.equals(before.evaluation(),candidate.evaluation());
            if(evaluationChanged)changed.add(checkpointId);
            Map<String,Object> item=new LinkedHashMap<>();item.put("checkpointId",checkpointId);item.put("previousFindingId",before.id());
            item.put("beforeFinding",before);item.put("candidateFinding",candidate);item.put("evaluationChanged",evaluationChanged);
            item.put("publicationState","CANDIDATE_ONLY");results.add(item);
        }

        List<String> changedIds=List.copyOf(changed);boolean noOp=context.plan().noOp()||changedIds.isEmpty();String createdAt=Instant.now().toString();
        Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("schemaVersion",SCHEMA);receipt.put("id",recordId);
        receipt.put("sourceRunId",runId);receipt.put("projectId",context.projectId());receipt.put("status",noOp?"NO_OP":"COMPLETED");
        receipt.put("graphRevision",context.graphRevision());receipt.put("graphContentDigest",context.graphContentDigest());
        receipt.put("sourceResultDigest",context.sourceResultDigest());receipt.put("affectedSourceDigest",context.affectedSourceDigest());
        receipt.put("baseResultDigest",context.baseResultDigest());receipt.put("affectedBaseResultDigest",context.affectedBaseResultDigest());
        receipt.put("resolutionPolicyIdentity",context.resolutionPolicyIdentity());
        receipt.put("evidenceSnapshotDigest",context.evidenceSnapshotDigest());receipt.put("changedNeedKeys",context.changedNeedKeys());
        receipt.put("affectedCheckpointIds",context.affectedCheckpointIds());receipt.put("recalculatedCheckpointIds",context.affectedCheckpointIds());
        receipt.put("changedCheckpointIds",changedIds);receipt.put("retainedCheckpointIds",context.retainedCheckpointIds());
        receipt.put("recalculatedCheckpointCount",context.affectedCheckpointIds().size());receipt.put("changedCheckpointCount",changedIds.size());
        receipt.put("retainedCheckpointCount",context.retainedCheckpointIds().size());receipt.put("noOp",noOp);
        receipt.put("historicalRunUnchanged",true);receipt.put("publicationState","CANDIDATE_ONLY");
        receipt.put("baseResultVersionId",context.baseResultVersionId());receipt.put("baseResultVersionRevision",context.baseResultVersionRevision());
        receipt.put("newConclusions",results.stream().filter(item->Boolean.TRUE.equals(item.get("evaluationChanged"))).collect(java.util.stream.Collectors.toMap(
            item->item.get("checkpointId").toString(),item->((Finding)item.get("candidateFinding")).evaluation(),(left,right)->left,LinkedHashMap::new)));
        receipt.put("results",results);receipt.put("createdAt",createdAt);
        receipt.put("candidateContentDigest",publications.candidateDigest(receipt));

        return tx.execute(ignored->{
            store.lock("project",context.projectId());Map<String,Object> currentRun=store.getMap("run",runId);
            if(!"COMPLETED".equals(BidService.text(currentRun,"status")))
                throw conflict("SOURCE_RUN_STATE_CHANGED","来源检查已不再处于完成状态；未发布增量候选结果",context.graphRevision());
            RequestContext current=context(runId,expectedRevision,context.changedNeedKeys());
            if(!context.graphContentDigest().equals(current.graphContentDigest()))
                throw conflict("DEPENDENCY_GRAPH_CHANGED","依赖图内容已变化；请按最新版本重新计划",current.graphRevision());
            if(!context.sourceResultDigest().equals(current.sourceResultDigest())
                ||!context.affectedSourceDigest().equals(current.affectedSourceDigest()))
                throw conflict("SOURCE_FINDINGS_CHANGED","重算期间来源模板结果发生变化；历史记录未被覆盖",current.graphRevision());
            if(!context.baseResultVersionId().equals(current.baseResultVersionId())
                ||context.baseResultVersionRevision()!=current.baseResultVersionRevision()
                ||!context.baseResultDigest().equals(current.baseResultDigest())
                ||!context.affectedBaseResultDigest().equals(current.affectedBaseResultDigest()))
                throw conflict("RESULT_VERSION_BASE_CHANGED","重算期间当前发布版本已变化；请基于最新版本重新计算",current.baseResultVersionRevision());
            if(!context.evidenceSnapshotDigest().equals(current.evidenceSnapshotDigest()))
                throw conflict("EVIDENCE_SNAPSHOT_CHANGED","重算期间证据快照发生变化；请按最新证据重新执行",current.graphRevision());
            if(!context.resolutionPolicyIdentity().equals(current.resolutionPolicyIdentity()))
                throw conflict("RESULT_POLICY_CHANGED","重算期间结果保护或证据复核策略已变化；请重新执行完整检查",current.graphRevision());
            Stored concurrent=store.mapper().get(recordId);if(concurrent!=null)return requireExisting(concurrent,current);
            store.insertImmutableIfAbsent(KIND,recordId,context.projectId(),runId,receipt);return receipt;
        });
    }

    public List<Map<String,Object>> list(String runId){
        Map<String,Object> run=store.getMap("run",runId);String projectId=BidService.text(run,"projectId");
        return publications.decorateList(store.maps(KIND,projectId,runId).stream().sorted(Comparator.comparing(value->BidService.text(value,"createdAt"))).toList());
    }

    public Map<String,Object> get(String runId,String recordId){
        Map<String,Object> run=store.getMap("run",runId);String projectId=BidService.text(run,"projectId");Stored row=store.require(KIND,recordId);
        if(!projectId.equals(row.projectId)||!runId.equals(row.parentId))throw missing("增量重算记录不属于该检查");return publications.decorate(store.map(row.body));
    }

    private RequestContext context(String runId,long expectedRevision,Collection<String> changedNeedKeys){
        if(expectedRevision<1||expectedRevision>Integer.MAX_VALUE)throw invalid("expectedRevision","必须提供正整数依赖图版本");
        List<String> changed=normalize(changedNeedKeys);if(changed.isEmpty())throw invalid("changedNeedKeys","至少提供一个变化的证据键");
        Map<String,Object> run=store.getMap("run",runId);String projectId=BidService.text(run,"projectId");
        if(!"COMPLETED".equals(BidService.text(run,"status")))throw conflict("SOURCE_RUN_NOT_COMPLETED","仅能为已完成检查创建显式增量候选记录",expectedRevision);
        String resolutionPolicyIdentity=BidService.text(run,"resolutionPolicyIdentity");
        if(resolutionPolicyIdentity.isBlank()||!resolutionPolicyIdentity.equals(bids.resolutionPolicyIdentity()))
            throw conflict("RESULT_POLICY_CHANGED","结果保护或证据复核策略已升级，请发起新的完整检查",expectedRevision);
        TemplateDependencyGraphService.RecalculationPlan plan;
        try{plan=graphs.plan(runId,expectedRevision,changed);}catch(IllegalStateException failure){throw graphFailure(failure,expectedRevision);}
        Map<String,Object> graph=graphs.get(runId);String graphDigest=BidService.text(graph,"contentDigest");String graphSourceDigest=BidService.text(graph,"sourceResultDigest");
        if(graphSourceDigest.isBlank())throw conflict("SOURCE_RESULT_DIGEST_MISSING","依赖图未绑定来源结果摘要，请重新发起完整检查后再增量重算",plan.graphRevision());
        List<String> all=checkpointIds(graph),affected=plan.checkpointIds().stream().sorted().toList();List<String> retained=all.stream().filter(id->!plan.checkpointIds().contains(id)).toList();
        Map<String,Finding> affectedSource=findings(projectId,runId,affected);String affectedDigest=digest(affectedSource.values());
        Map<String,Finding> allBefore=findings(projectId,runId,all);String currentSourceDigest=digest(allBefore.values());
        if(!graphSourceDigest.equals(currentSourceDigest))throw conflict("SOURCE_FINDINGS_CHANGED","历史模板结果与依赖图绑定摘要不一致；未执行重算",plan.graphRevision());
        String evidenceSnapshotDigest=bids.templateEvidenceSnapshotDigest(runId);
        Map<String,Object> head=publications.currentHead(runId);
        Baseline baseline=baseline(runId,all,affected,allBefore,head,graphSourceDigest,plan.graphRevision());
        return new RequestContext(projectId,runId,plan.graphRevision(),graphDigest,graphSourceDigest,evidenceSnapshotDigest,affectedDigest,
            baseline.resultDigest(),baseline.affectedDigest(),resolutionPolicyIdentity,BidService.text(head,"currentVersionId"),number(head.get("revision"),0),changed,affected,retained,baseline.affectedFindings(),plan);
    }

    /** Resolves the comparison baseline from the published head, falling back to the immutable run. */
    private Baseline baseline(String runId,List<String> all,List<String> affected,Map<String,Finding> source,
                              Map<String,Object> head,String graphSourceDigest,long graphRevision){
        String versionId=BidService.text(head,"currentVersionId");int revision=number(head.get("revision"),0);
        if(versionId.isBlank()){
            if(revision!=0)throw conflict("RESULT_VERSION_HEAD_INVALID","结果版本指针不完整，不能创建增量候选",revision);
            Map<String,Finding> affectedFindings=select(source,affected);
            return new Baseline(graphSourceDigest,digest(affectedFindings.values()),affectedFindings);
        }
        Map<String,Object> version=publications.version(runId,versionId);
        if(revision<1||number(version.get("revision"),-1)!=revision)
            throw conflict("RESULT_VERSION_INTEGRITY_FAILED","当前发布版本与版本指针不一致，不能创建增量候选",revision);
        List<Map<String,Object>> resultEntries=maps(version.get("results"));
        String resultDigest=BidService.text(version,"resultDigest");
        if(resultDigest.isBlank()||!resultDigest.equals(BidService.hash(store.encode(resultEntries))))
            throw conflict("RESULT_VERSION_INTEGRITY_FAILED","当前发布版本摘要校验失败，不能创建增量候选",revision);
        Map<String,Finding> findings=new LinkedHashMap<>();
        for(Map<String,Object> entry:resultEntries){
            String checkpointId=BidService.text(entry,"checkpointId");Object raw=entry.get("finding");
            if(checkpointId.isBlank()||raw==null||findings.containsKey(checkpointId))
                throw conflict("RESULT_VERSION_INTEGRITY_FAILED","当前发布版本包含无效或重复检查项",revision);
            Finding finding;
            try{finding=store.decode(store.encode(raw),Finding.class);}catch(RuntimeException failure){
                throw conflict("RESULT_VERSION_INTEGRITY_FAILED","当前发布版本包含不可读取的检查结论",revision);
            }
            if(finding.checkpoint()==null||!checkpointId.equals(finding.checkpoint().id()))
                throw conflict("RESULT_VERSION_INTEGRITY_FAILED","当前发布版本的检查项标识不一致",revision);
            findings.put(checkpointId,finding);
        }
        if(!findings.keySet().equals(new LinkedHashSet<>(all)))
            throw conflict("RESULT_VERSION_INTEGRITY_FAILED","当前发布版本未完整覆盖依赖图检查项",revision);
        Map<String,Finding> affectedFindings=select(findings,affected);
        return new Baseline(resultDigest,digest(affectedFindings.values()),affectedFindings);
    }

    private static Map<String,Finding> select(Map<String,Finding> findings,List<String> checkpointIds){
        Map<String,Finding> selected=new LinkedHashMap<>();for(String checkpointId:checkpointIds){Finding finding=findings.get(checkpointId);
            if(finding==null)throw conflict("RESULT_VERSION_INTEGRITY_FAILED","当前结果基线缺少受影响检查项",0);selected.put(checkpointId,finding);}return selected;
    }

    private Map<String,Finding> findings(String projectId,String runId,List<String> checkpointIds){
        Map<String,Finding> out=new LinkedHashMap<>();for(String checkpointId:checkpointIds){String id=BidService.stableId(runId+":"+checkpointId);Stored row=store.mapper().get(id);
            if(row==null||!"finding".equals(row.kind)||!projectId.equals(row.projectId)||!runId.equals(row.parentId))throw conflict("SOURCE_FINDING_MISSING","依赖图对应的历史模板结果缺失",0);
            Finding finding=store.decode(row.body,Finding.class);out.put(checkpointId,finding);}return out;
    }

    private String digest(Collection<Finding> findings){return BidService.hash(findings.stream().sorted(Comparator.comparing(value->value.checkpoint().id())).map(store::encode).collect(java.util.stream.Collectors.joining("\n")));}
    private static List<Map<String,Object>> maps(Object value){if(!(value instanceof Collection<?> collection))return List.of();List<Map<String,Object>> out=new ArrayList<>();for(Object item:collection)if(item instanceof Map<?,?> raw){Map<String,Object> map=new LinkedHashMap<>();raw.forEach((key,itemValue)->map.put(Objects.toString(key),itemValue));out.add(map);}return List.copyOf(out);}
    private static List<String> checkpointIds(Map<String,Object> graph){Object value=graph.get("fragments");if(!(value instanceof Collection<?> fragments))return List.of();Set<String> ids=new LinkedHashSet<>();
        for(Object item:fragments)if(item instanceof Map<?,?> fragment){Object id=fragment.get("checkpointId");if(id!=null&&!id.toString().isBlank())ids.add(id.toString());}return ids.stream().sorted().toList();}
    private static List<String> normalize(Collection<String> values){if(values==null)return List.of();LinkedHashSet<String> keys=new LinkedHashSet<>();for(String value:values){if(value==null||value.isBlank())continue;String key=value.trim();if(key.length()>512)throw invalid("changedNeedKeys","单个证据键长度不能超过512字");keys.add(key);if(keys.size()>MAX_CHANGED_NEEDS)throw invalid("changedNeedKeys","单次最多提交500个变化的证据键");}return keys.stream().sorted().toList();}
    private static String recordId(RequestContext context){return BidService.stableId(SCHEMA+":"+context.runId()+":"+context.graphRevision()+":"+String.join("\n",context.changedNeedKeys())+":"+context.sourceResultDigest()+":"+context.evidenceSnapshotDigest()+":"+context.baseResultVersionId()+":"+context.baseResultVersionRevision()+":"+context.baseResultDigest()+":"+context.resolutionPolicyIdentity());}
    private Map<String,Object> requireExisting(Stored row,RequestContext context){if(!KIND.equals(row.kind)||!context.projectId().equals(row.projectId)||!context.runId().equals(row.parentId))throw new IllegalStateException("INCREMENTAL_RECALCULATION_ID_CONFLICT");return store.map(row.body);}
    private static Map<String,Object> planView(RequestContext context){Map<String,Object> out=new LinkedHashMap<>();out.put("schemaVersion",SCHEMA);out.put("sourceRunId",context.runId());out.put("graphRevision",context.graphRevision());out.put("graphContentDigest",context.graphContentDigest());out.put("sourceResultDigest",context.sourceResultDigest());out.put("affectedSourceDigest",context.affectedSourceDigest());out.put("baseResultDigest",context.baseResultDigest());out.put("affectedBaseResultDigest",context.affectedBaseResultDigest());out.put("resolutionPolicyIdentity",context.resolutionPolicyIdentity());out.put("evidenceSnapshotDigest",context.evidenceSnapshotDigest());out.put("changedNeedKeys",context.changedNeedKeys());out.put("affectedCheckpointIds",context.affectedCheckpointIds());out.put("retainedCheckpointIds",context.retainedCheckpointIds());out.put("baseResultVersionId",context.baseResultVersionId());out.put("baseResultVersionRevision",context.baseResultVersionRevision());out.put("noOp",context.plan().noOp());out.put("historicalRunUnchanged",true);return out;}

    private static int number(Object value,int fallback){return value instanceof Number number?number.intValue():fallback;}

    private static TemplateApiException graphFailure(IllegalStateException failure,long requested){String message=failure.getMessage()==null?"":failure.getMessage();if(message.startsWith("DEPENDENCY_GRAPH_REVISION_CHANGED:")){int actual;try{actual=Integer.parseInt(message.substring(message.indexOf(':')+1));}catch(NumberFormatException ignored){actual=safeRevision(requested);}return TemplateApiException.conflict("DEPENDENCY_GRAPH_REVISION_CHANGED","依赖图版本已变化，请刷新后重试",actual);}if("DEPENDENCY_GRAPH_MISSING".equals(message))return missing("本次检查尚未生成模板依赖图");throw failure;}
    private static TemplateApiException conflict(String code,String message,long revision){return TemplateApiException.conflict(code,message,safeRevision(revision));}
    private static TemplateApiException invalid(String field,String message){return TemplateApiException.invalid("增量重算请求无效",List.of(Map.of("field",field,"message",message)));}
    private static TemplateApiException missing(String message){return new TemplateApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND",message,false,List.of(),null);}
    private static int safeRevision(long value){return value<0?-1:value>Integer.MAX_VALUE?Integer.MAX_VALUE:(int)value;}

    private record RequestContext(String projectId,String runId,long graphRevision,String graphContentDigest,String sourceResultDigest,String evidenceSnapshotDigest,
                                  String affectedSourceDigest,String baseResultDigest,String affectedBaseResultDigest,String resolutionPolicyIdentity,String baseResultVersionId,int baseResultVersionRevision,List<String> changedNeedKeys,List<String> affectedCheckpointIds,
                                  List<String> retainedCheckpointIds,Map<String,Finding> beforeFindings,
                                  TemplateDependencyGraphService.RecalculationPlan plan){}
    private record Baseline(String resultDigest,String affectedDigest,Map<String,Finding> affectedFindings){}
}
