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
 * Reviews an immutable incremental candidate and, on approval, publishes a new immutable template
 * result version. The completed source run and every historical finding remain byte-for-byte intact.
 */
@Service
public class TemplateIncrementalPublicationService {
    public static final String DECISION_SCHEMA="template-incremental-decision-v1";
    public static final String RESULT_VERSION_SCHEMA="template-result-version-v1";
    public static final String DECISION_KIND="template_incremental_decision";
    public static final String RESULT_VERSION_KIND="template_result_version";
    public static final String RESULT_HEAD_KIND="template_result_version_head";
    private static final String CANDIDATE_KIND="template_incremental_recalc";
    private static final Set<String> DECISIONS=cn.bidcheck.compat.Java8Api.setOf("APPROVE", "REJECT");

    private final Store store;
    private final BidService bids;
    private final TemplateDependencyGraphService graphs;
    private final TransactionTemplate tx;

    public TemplateIncrementalPublicationService(Store store,BidService bids,TemplateDependencyGraphService graphs,
                                                 PlatformTransactionManager manager){
        this.store=store;this.bids=bids;this.graphs=graphs;this.tx=new TransactionTemplate(manager);
    }

    public Map<String,Object> decide(String runId,String candidateId,Map<String,Object> request){
        DecisionRequest desired=request(request);
        Candidate candidate=candidate(runId,candidateId);
        verifyExpectedCandidate(candidate.body(),desired);
        return tx.execute(ignored->{
            store.lock("project",candidate.projectId());
            Stored existing=store.mapper().get(decisionId(candidateId));
            if(existing!=null)return idempotent(existing,candidate,desired);
            if(desired.expectedRevision()!=0)throw conflict("DECISION_REVISION_CHANGED","候选结论审核状态已变化，请刷新",0);

            String decidedAt=Instant.now().toString();
            Map<String,Object> publication=null;
            if("APPROVE".equals(desired.decision())){
                validateForPublication(candidate);
                publication=publish(candidate,desired,decidedAt);
            }
            Map<String,Object> decision=new LinkedHashMap<>();
            decision.put("schemaVersion",DECISION_SCHEMA);decision.put("id",decisionId(candidateId));
            decision.put("sourceRunId",runId);decision.put("projectId",candidate.projectId());decision.put("candidateRecordId",candidateId);
            decision.put("decision",desired.decision());decision.put("decisionRevision",1);
            decision.put("publicationState","APPROVE".equals(desired.decision())?"PUBLISHED":"REJECTED");
            decision.put("publishedResultVersionId",publication==null?"":BidService.text(publication,"id"));
            decision.put("reviewer",desired.reviewer());decision.put("note",desired.note());decision.put("decidedAt",decidedAt);
            decision.put("sourceResultDigest",BidService.text(candidate.body(),"sourceResultDigest"));
            decision.put("evidenceSnapshotDigest",BidService.text(candidate.body(),"evidenceSnapshotDigest"));
            decision.put("resolutionPolicyIdentity",BidService.text(candidate.body(),"resolutionPolicyIdentity"));
            decision.put("candidateContentDigest",BidService.text(candidate.body(),"candidateContentDigest"));
            decision.put("historicalRunUnchanged",true);
            store.insertImmutableIfAbsent(DECISION_KIND,decisionId(candidateId),candidate.projectId(),candidateId,decision);
            Map<String,Object> audit=new LinkedHashMap<>(decision);audit.put("id",auditId(candidateId));
            audit.put("action","APPROVE".equals(desired.decision())?"INCREMENTAL_CANDIDATE_PUBLISHED":"INCREMENTAL_CANDIDATE_REJECTED");
            store.insertImmutableIfAbsent("audit",auditId(candidateId),candidate.projectId(),candidateId,audit);
            return decision;
        });
    }

    /** A pending view is returned instead of 404 so the client can render a stable review state. */
    public Map<String,Object> decision(String runId,String candidateId){
        Candidate candidate=candidate(runId,candidateId);Stored row=store.mapper().get(decisionId(candidateId));
        if(row==null)return pending(candidate.body());
        if(!DECISION_KIND.equals(row.kind)||!candidate.projectId().equals(row.projectId)||!candidateId.equals(row.parentId))
            throw new IllegalStateException("INCREMENTAL_DECISION_SCOPE_MISMATCH");
        return store.map(row.body);
    }

    public Map<String,Object> decorate(Map<String,Object> raw){
        Map<String,Object> value=new LinkedHashMap<>(raw);String candidateId=BidService.text(value,"id");
        if(cn.bidcheck.compat.Java8Api.isBlank(candidateId))return value;Stored row=store.mapper().get(decisionId(candidateId));
        Map<String,Object> decision=row==null?pending(value):store.map(row.body);
        value.put("decision",decision);value.put("decisionRevision",decision.get("decisionRevision"));
        value.put("publicationState",decision.get("publicationState"));
        value.put("publishedResultVersionId",decision.get("publishedResultVersionId"));
        return value;
    }

    public List<Map<String,Object>> decorateList(List<Map<String,Object>> candidates){
        if(candidates==null||candidates.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        Map<String,Map<String,Object>> byCandidate=new LinkedHashMap<>();
        String projectId=BidService.text(candidates.get(0),"projectId");
        for(Map<String,Object> value:store.maps(DECISION_KIND,projectId,null))byCandidate.put(BidService.text(value,"candidateRecordId"),value);
        List<Map<String,Object>> out=new ArrayList<>(candidates.size());
        for(Map<String,Object> raw:candidates){Map<String,Object> value=new LinkedHashMap<>(raw);Map<String,Object> decision=byCandidate.get(BidService.text(raw,"id"));
            if(decision==null)decision=pending(raw);value.put("decision",decision);value.put("decisionRevision",decision.get("decisionRevision"));
            value.put("publicationState",decision.get("publicationState"));value.put("publishedResultVersionId",decision.get("publishedResultVersionId"));out.add(value);}
        return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }

    public List<Map<String,Object>> versions(String runId){
        Map<String,Object> run=store.getMap("run",runId);String projectId=BidService.text(run,"projectId");
        return cn.bidcheck.compat.Java8Api.toList(store.maps(RESULT_VERSION_KIND,projectId,runId).stream()
            .sorted(Comparator.comparingInt(value->number(value.get("revision"),0))));
    }

    public Map<String,Object> version(String runId,String versionId){
        Map<String,Object> run=store.getMap("run",runId);String projectId=BidService.text(run,"projectId");Stored row=store.require(RESULT_VERSION_KIND,versionId);
        if(!projectId.equals(row.projectId)||!runId.equals(row.parentId))throw missing("结果版本不属于该检查");return store.map(row.body);
    }

    public Map<String,Object> currentVersion(String runId){
        Map<String,Object> run=store.getMap("run",runId);String projectId=BidService.text(run,"projectId");Map<String,Object> head=currentHead(runId);
        Map<String,Object> out=new LinkedHashMap<>(head);String versionId=BidService.text(head,"currentVersionId");
        out.put("projectId",projectId);out.put("sourceRunId",runId);out.put("published",!cn.bidcheck.compat.Java8Api.isBlank(versionId));
        if(!cn.bidcheck.compat.Java8Api.isBlank(versionId))out.put("version",version(runId,versionId));return out;
    }

    public Map<String,Object> currentHead(String runId){
        Stored row=store.mapper().get(headId(runId));if(row==null)return cn.bidcheck.compat.Java8Api.mapOf("revision", 0, "currentVersionId", "");
        if(!RESULT_HEAD_KIND.equals(row.kind)||!runId.equals(row.parentId))throw new IllegalStateException("RESULT_VERSION_HEAD_SCOPE_MISMATCH");
        return store.map(row.body);
    }

    private Map<String,Object> publish(Candidate candidate,DecisionRequest desired,String publishedAt){
        Map<String,Object> body=candidate.body(),head=currentHead(candidate.runId());
        String expectedBase=BidService.text(body,"baseResultVersionId");int expectedBaseRevision=number(body.get("baseResultVersionRevision"),0);
        if(!expectedBase.equals(BidService.text(head,"currentVersionId"))||expectedBaseRevision!=number(head.get("revision"),0))
            throw conflict("RESULT_VERSION_BASE_CHANGED","已有新的增量结果版本，请基于最新版本重新计算候选结论",number(head.get("revision"),0));

        Map<String,Map<String,Object>> merged=new LinkedHashMap<>();Map<String,Object> baseVersion=cn.bidcheck.compat.Java8Api.mapOf();
        if(!cn.bidcheck.compat.Java8Api.isBlank(expectedBase)){
            baseVersion=version(candidate.runId(),expectedBase);List<Map<String,Object>> baseResults=maps(baseVersion.get("results"));
            if(number(baseVersion.get("revision"),-1)!=expectedBaseRevision
                ||!BidService.hash(store.encode(baseResults)).equals(BidService.text(baseVersion,"resultDigest")))
                throw conflict("RESULT_VERSION_INTEGRITY_FAILED","当前发布版本摘要校验失败，不能继续叠加候选结论",expectedBaseRevision);
            if(!BidService.text(baseVersion,"resultDigest").equals(BidService.text(body,"baseResultDigest")))
                throw conflict("RESULT_VERSION_BASE_CHANGED","候选结论绑定的结果摘要已不是当前发布版本",expectedBaseRevision);
            for(Map<String,Object> entry:baseResults)merged.put(BidService.text(entry,"checkpointId"),entry);
        }
        else {
            if(!BidService.text(body,"sourceResultDigest").equals(BidService.text(body,"baseResultDigest")))
                throw conflict("RESULT_VERSION_BASE_CHANGED","候选结论绑定的来源结果摘要不一致",expectedBaseRevision);
            for(String checkpointId:graphCheckpointIds(graphs.get(candidate.runId()))){
                Stored finding=store.mapper().get(BidService.stableId(candidate.runId()+":"+checkpointId));
                if(finding==null||!"finding".equals(finding.kind)||!candidate.projectId().equals(finding.projectId)||!candidate.runId().equals(finding.parentId))
                    throw conflict("SOURCE_FINDING_MISSING","来源检查结果缺失，不能发布候选版本",number(body.get("graphRevision"),0));
                Map<String,Object> entry=new LinkedHashMap<>();entry.put("checkpointId",checkpointId);entry.put("origin","SOURCE_RUN");entry.put("sourceFindingId",finding.id);entry.put("finding",store.map(finding.body));merged.put(checkpointId,entry);
            }
        }
        for(Map<String,Object> result:maps(body.get("results"))){String checkpointId=BidService.text(result,"checkpointId");Object finding=result.get("candidateFinding");
            if(cn.bidcheck.compat.Java8Api.isBlank(checkpointId)||!(finding instanceof Map<?,?>))throw conflict("CANDIDATE_CONTENT_INVALID","候选结果内容不完整，不能发布",number(body.get("graphRevision"),0));
            Map<String,Object> current=merged.get(checkpointId);Object before=result.get("beforeFinding");
            if(current==null||!(before instanceof Map<?,?>)||!Objects.equals(current.get("finding"),before))
                throw conflict("RESULT_VERSION_BASE_CHANGED","候选结论的比较基线已不是当前发布结果",expectedBaseRevision);
            if(!Boolean.TRUE.equals(result.get("evaluationChanged")))continue;
            Map<String,Object> entry=new LinkedHashMap<>();entry.put("checkpointId",checkpointId);entry.put("origin","INCREMENTAL_CANDIDATE");
            entry.put("candidateRecordId",candidate.id());entry.put("previousFindingId",result.getOrDefault("previousFindingId",""));entry.put("finding",finding);merged.put(checkpointId,entry);
        }
        List<Map<String,Object>> results=cn.bidcheck.compat.Java8Api.toList(merged.values().stream().sorted(Comparator.comparing(value->BidService.text(value,"checkpointId"))));
        int revision=expectedBaseRevision+1;String resultDigest=BidService.hash(store.encode(results));
        if(!cn.bidcheck.compat.Java8Api.isBlank(expectedBase)&&resultDigest.equals(BidService.text(baseVersion,"resultDigest")))
            throw invalid("decision","候选结论与当前发布版本相同，无需创建新版本");
        String versionId=BidService.stableId("template-result-version:"+candidate.runId()+":"+candidate.id()+":"+resultDigest);
        Map<String,Object> version=new LinkedHashMap<>();version.put("schemaVersion",RESULT_VERSION_SCHEMA);version.put("id",versionId);
        version.put("projectId",candidate.projectId());version.put("sourceRunId",candidate.runId());version.put("candidateRecordId",candidate.id());
        version.put("scope","TEMPLATE_CHECKPOINTS");version.put("revision",revision);version.put("previousResultVersionId",expectedBase);
        version.put("sourceResultDigest",BidService.text(body,"sourceResultDigest"));version.put("evidenceSnapshotDigest",BidService.text(body,"evidenceSnapshotDigest"));
        version.put("resolutionPolicyIdentity",BidService.text(body,"resolutionPolicyIdentity"));
        version.put("candidateContentDigest",BidService.text(body,"candidateContentDigest"));version.put("resultDigest",resultDigest);
        version.put("findingCount",results.size());version.put("results",results);version.put("publishedBy",desired.reviewer());version.put("publicationNote",desired.note());
        version.put("publishedAt",publishedAt);version.put("historicalRunUnchanged",true);version.put("publicationState","PUBLISHED");
        store.insertImmutableIfAbsent(RESULT_VERSION_KIND,versionId,candidate.projectId(),candidate.runId(),version);
        Map<String,Object> nextHead=new LinkedHashMap<>();nextHead.put("schemaVersion","template-result-version-head-v1");nextHead.put("id",headId(candidate.runId()));
        nextHead.put("projectId",candidate.projectId());nextHead.put("sourceRunId",candidate.runId());nextHead.put("revision",revision);
        nextHead.put("currentVersionId",versionId);nextHead.put("updatedAt",publishedAt);store.save(RESULT_HEAD_KIND,headId(candidate.runId()),candidate.projectId(),candidate.runId(),nextHead);
        return version;
    }

    private void validateForPublication(Candidate candidate){
        Map<String,Object> body=candidate.body();
        if(!TemplateIncrementalRecalculationService.SCHEMA.equals(BidService.text(body,"schemaVersion")))
            throw conflict("CANDIDATE_SCHEMA_OUTDATED","候选记录由旧版流程生成，请重新执行增量重算",number(body.get("graphRevision"),0));
        if(Boolean.TRUE.equals(body.get("noOp"))||number(body.get("changedCheckpointCount"),0)<1)
            throw invalid("decision","候选结论没有发生变化，无需发布新版本");
        Map<String,Object> run=store.getMap("run",candidate.runId());
        if(!"COMPLETED".equals(BidService.text(run,"status")))throw conflict("SOURCE_RUN_STATE_CHANGED","来源检查已不再处于完成状态",number(body.get("graphRevision"),0));
        if(!bids.templateCheckIdentity().equals(BidService.text(run,"templateCheckIdentity")))
            throw conflict("TEMPLATE_ENGINE_CHANGED","模板判定器已升级，请重新执行完整检查",number(body.get("graphRevision"),0));
        String frozenPolicy=BidService.text(body,"resolutionPolicyIdentity");
        if(cn.bidcheck.compat.Java8Api.isBlank(frozenPolicy)||!frozenPolicy.equals(BidService.text(run,"resolutionPolicyIdentity"))
            ||!frozenPolicy.equals(bids.resolutionPolicyIdentity()))
            throw conflict("RESULT_POLICY_CHANGED","结果保护或证据复核策略已升级，请重新执行完整检查",number(body.get("graphRevision"),0));
        Map<String,Object> graph=graphs.get(candidate.runId());
        if(number(graph.get("revision"),0)!=number(body.get("graphRevision"),0)||!BidService.text(graph,"contentDigest").equals(BidService.text(body,"graphContentDigest")))
            throw conflict("DEPENDENCY_GRAPH_CHANGED","依赖图已变化，请重新计算候选结论",number(graph.get("revision"),0));
        String currentSource=digestFindings(candidate.projectId(),candidate.runId(),graphCheckpointIds(graph));
        if(!currentSource.equals(BidService.text(body,"sourceResultDigest")))throw conflict("SOURCE_FINDINGS_CHANGED","来源结果摘要已变化，不能发布候选结论",number(graph.get("revision"),0));
        List<String> affected=strings(body.get("affectedCheckpointIds"));
        if(!digestFindings(candidate.projectId(),candidate.runId(),affected).equals(BidService.text(body,"affectedSourceDigest")))
            throw conflict("AFFECTED_FINDINGS_CHANGED","受影响结果摘要已变化，不能发布候选结论",number(graph.get("revision"),0));
        if(!bids.templateEvidenceSnapshotDigest(candidate.runId()).equals(BidService.text(body,"evidenceSnapshotDigest")))
            throw conflict("EVIDENCE_SNAPSHOT_CHANGED","证据快照已变化，请重新计算候选结论",number(graph.get("revision"),0));
        String expectedCandidateDigest=candidateDigest(body);
        if(!expectedCandidateDigest.equals(BidService.text(body,"candidateContentDigest")))
            throw conflict("CANDIDATE_CONTENT_CHANGED","候选结果摘要校验失败，不能发布",number(graph.get("revision"),0));
    }

    private String digestFindings(String projectId,String runId,List<String> checkpointIds){List<Finding> findings=new ArrayList<>();
        for(String checkpointId:checkpointIds){Stored row=store.mapper().get(BidService.stableId(runId+":"+checkpointId));
            if(row==null||!"finding".equals(row.kind)||!projectId.equals(row.projectId)||!runId.equals(row.parentId))throw conflict("SOURCE_FINDING_MISSING","来源结果缺失，不能发布候选结论",0);
            findings.add(store.decode(row.body,Finding.class));}
        return BidService.hash(findings.stream().sorted(Comparator.comparing(value->value.checkpoint().id())).map(store::encode).collect(java.util.stream.Collectors.joining("\n")));
    }

    /** Digest deliberately excludes lifecycle fields, so the recalculation service can create it before publication. */
    public String candidateDigest(Map<String,Object> body){Map<String,Object> content=new LinkedHashMap<>();
        for(String key:cn.bidcheck.compat.Java8Api.listOf("schemaVersion", "sourceRunId", "projectId", "graphRevision", "graphContentDigest", "sourceResultDigest", "affectedSourceDigest", "baseResultDigest", "affectedBaseResultDigest", "resolutionPolicyIdentity", "evidenceSnapshotDigest", "changedNeedKeys", "affectedCheckpointIds", "recalculatedCheckpointIds", "changedCheckpointIds", "retainedCheckpointIds", "newConclusions", "results", "baseResultVersionId", "baseResultVersionRevision"))content.put(key,body.get(key));
        return BidService.hash(store.encode(content));
    }

    private Candidate candidate(String runId,String candidateId){Map<String,Object> run=store.getMap("run",runId);String projectId=BidService.text(run,"projectId");Stored row=store.require(CANDIDATE_KIND,candidateId);
        if(!projectId.equals(row.projectId)||!runId.equals(row.parentId))throw missing("增量候选记录不属于该检查");return new Candidate(projectId,runId,candidateId,store.map(row.body));}
    private void verifyExpectedCandidate(Map<String,Object> candidate,DecisionRequest desired){
        if(cn.bidcheck.compat.Java8Api.isBlank(desired.expectedSourceDigest())||cn.bidcheck.compat.Java8Api.isBlank(desired.expectedEvidenceDigest()))throw invalid("expectedSourceResultDigest","必须绑定候选记录的来源结果和证据摘要");
        if(!desired.expectedSourceDigest().equals(BidService.text(candidate,"sourceResultDigest")))throw conflict("EXPECTED_SOURCE_DIGEST_MISMATCH","提交的来源结果摘要与候选记录不一致",number(candidate.get("graphRevision"),0));
        if(!desired.expectedEvidenceDigest().equals(BidService.text(candidate,"evidenceSnapshotDigest")))throw conflict("EXPECTED_EVIDENCE_DIGEST_MISMATCH","提交的证据快照摘要与候选记录不一致",number(candidate.get("graphRevision"),0));
    }
    private Map<String,Object> idempotent(Stored row,Candidate candidate,DecisionRequest desired){
        if(!DECISION_KIND.equals(row.kind)||!candidate.projectId().equals(row.projectId)||!candidate.id().equals(row.parentId))throw new IllegalStateException("INCREMENTAL_DECISION_ID_CONFLICT");
        Map<String,Object> prior=store.map(row.body);boolean same=desired.decision().equals(BidService.text(prior,"decision"))&&desired.reviewer().equals(BidService.text(prior,"reviewer"))&&desired.note().equals(BidService.text(prior,"note"));
        if(same)return prior;throw conflict("CANDIDATE_ALREADY_DECIDED","该候选结论已经完成审核，不能重复修改",number(prior.get("decisionRevision"),1));
    }
    private static DecisionRequest request(Map<String,Object> body){if(body==null)body=cn.bidcheck.compat.Java8Api.mapOf();String decision=BidService.text(body,"decision").toUpperCase(java.util.Locale.ROOT);
        if(!DECISIONS.contains(decision))throw invalid("decision","只能选择 APPROVE 或 REJECT");String reviewer=BidService.text(body,"reviewer");
        if(cn.bidcheck.compat.Java8Api.isBlank(reviewer)||reviewer.length()>100)throw invalid("reviewer","审核人不能为空且不能超过100字");String note=BidService.text(body,"note");if(cn.bidcheck.compat.Java8Api.isBlank(note)||note.length()>3000)throw invalid("note","审核说明不能为空且不能超过3000字");
        Object revision=body.get("expectedDecisionRevision");if(!(revision instanceof Number)||((Number) (revision)).intValue()<0||((Number) (revision)).doubleValue()!=Math.rint(((Number) (revision)).doubleValue()))throw invalid("expectedDecisionRevision","必须提供非负整数审核版本");
        return new DecisionRequest(decision,reviewer,note,((Number) (revision)).intValue(),BidService.text(body,"expectedSourceResultDigest"),BidService.text(body,"expectedEvidenceSnapshotDigest"));}
    private static Map<String,Object> pending(Map<String,Object> candidate){Map<String,Object> out=new LinkedHashMap<>();out.put("decision","");out.put("decisionRevision",0);out.put("publicationState","CANDIDATE_ONLY");out.put("publishedResultVersionId","");out.put("candidateRecordId",BidService.text(candidate,"id"));out.put("historicalRunUnchanged",true);return out;}
    private static List<Map<String,Object>> maps(Object value){if(!(value instanceof Collection<?>))return cn.bidcheck.compat.Java8Api.listOf();List<Map<String,Object>> out=new ArrayList<>();for(Object item:((Collection<?>) (value)))if(item instanceof Map<?,?>){Map<String,Object> map=new LinkedHashMap<>();((Map<?,?>) (item)).forEach((k,v)->map.put(Objects.toString(k),v));out.add(map);}return out;}
    private static List<String> strings(Object value){if(!(value instanceof Collection<?>))return cn.bidcheck.compat.Java8Api.listOf();return cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().filter(Objects::nonNull).map(Object::toString).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).distinct().sorted());}
    private static List<String> graphCheckpointIds(Map<String,Object> graph){LinkedHashSet<String> ids=new LinkedHashSet<>();for(Map<String,Object> fragment:maps(graph.get("fragments"))){String id=BidService.text(fragment,"checkpointId");if(!cn.bidcheck.compat.Java8Api.isBlank(id))ids.add(id);}return cn.bidcheck.compat.Java8Api.toList(ids.stream().sorted());}
    private static int number(Object value,int fallback){return value instanceof Number?((Number) (value)).intValue():fallback;}
    private static String decisionId(String candidateId){return BidService.stableId("template-incremental-decision:"+candidateId);}
    private static String auditId(String candidateId){return BidService.stableId("audit:template-incremental-decision:"+candidateId);}
    public static String headId(String runId){return BidService.stableId("template-result-version-head:"+runId);}
    private static TemplateApiException conflict(String code,String message,int revision){return TemplateApiException.conflict(code,message,revision);}
    private static TemplateApiException invalid(String field,String message){return TemplateApiException.invalid("增量候选审核请求无效",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", field, "message", message)));}
    private static TemplateApiException missing(String message){return new TemplateApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND",message,false,cn.bidcheck.compat.Java8Api.listOf(),null);}

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Candidate{
        private final String projectId;
        private final String runId;
        private final String id;
        private final Map<String,Object> body;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Candidate(@com.fasterxml.jackson.annotation.JsonProperty("projectId") String projectId, @com.fasterxml.jackson.annotation.JsonProperty("runId") String runId, @com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("body") Map<String,Object> body) { this.projectId = projectId; this.runId = runId; this.id = id; this.body = body; }
        public String projectId() { return projectId; }
        public String runId() { return runId; }
        public String id() { return id; }
        public Map<String,Object> body() { return body; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Candidate that = (Candidate) other; return java.util.Objects.equals(this.projectId, that.projectId) && java.util.Objects.equals(this.runId, that.runId) && java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.body, that.body); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.projectId); hash = 31 * hash + java.util.Objects.hashCode(this.runId); hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.body); return hash; }
        @Override public String toString() { return "Candidate[" + "projectId=" + projectId + ", runId=" + runId + ", id=" + id + ", body=" + body + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class DecisionRequest{
        private final String decision;
        private final String reviewer;
        private final String note;
        private final int expectedRevision;
        private final String expectedSourceDigest;
        private final String expectedEvidenceDigest;
        @com.fasterxml.jackson.annotation.JsonCreator
        private DecisionRequest(@com.fasterxml.jackson.annotation.JsonProperty("decision") String decision, @com.fasterxml.jackson.annotation.JsonProperty("reviewer") String reviewer, @com.fasterxml.jackson.annotation.JsonProperty("note") String note, @com.fasterxml.jackson.annotation.JsonProperty("expectedRevision") int expectedRevision, @com.fasterxml.jackson.annotation.JsonProperty("expectedSourceDigest") String expectedSourceDigest, @com.fasterxml.jackson.annotation.JsonProperty("expectedEvidenceDigest") String expectedEvidenceDigest) { this.decision = decision; this.reviewer = reviewer; this.note = note; this.expectedRevision = expectedRevision; this.expectedSourceDigest = expectedSourceDigest; this.expectedEvidenceDigest = expectedEvidenceDigest; }
        public String decision() { return decision; }
        public String reviewer() { return reviewer; }
        public String note() { return note; }
        public int expectedRevision() { return expectedRevision; }
        public String expectedSourceDigest() { return expectedSourceDigest; }
        public String expectedEvidenceDigest() { return expectedEvidenceDigest; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; DecisionRequest that = (DecisionRequest) other; return java.util.Objects.equals(this.decision, that.decision) && java.util.Objects.equals(this.reviewer, that.reviewer) && java.util.Objects.equals(this.note, that.note) && java.util.Objects.equals(this.expectedRevision, that.expectedRevision) && java.util.Objects.equals(this.expectedSourceDigest, that.expectedSourceDigest) && java.util.Objects.equals(this.expectedEvidenceDigest, that.expectedEvidenceDigest); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.decision); hash = 31 * hash + java.util.Objects.hashCode(this.reviewer); hash = 31 * hash + java.util.Objects.hashCode(this.note); hash = 31 * hash + java.util.Objects.hashCode(this.expectedRevision); hash = 31 * hash + java.util.Objects.hashCode(this.expectedSourceDigest); hash = 31 * hash + java.util.Objects.hashCode(this.expectedEvidenceDigest); return hash; }
        @Override public String toString() { return "DecisionRequest[" + "decision=" + decision + ", reviewer=" + reviewer + ", note=" + note + ", expectedRevision=" + expectedRevision + ", expectedSourceDigest=" + expectedSourceDigest + ", expectedEvidenceDigest=" + expectedEvidenceDigest + "]"; }
}
}
