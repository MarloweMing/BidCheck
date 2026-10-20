package cn.bidcheck.checktemplate;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Versioned JSON persistence for template specifications. The execution layer consumes a frozen
 * snapshot; this service never evaluates a bid or falls back to the legacy compiler.
 */
@Service
public class CheckSpecificationStoreService {
    public static final String SPEC_SCHEMA="check-spec-v1";
    public static final String SET_SCHEMA="check-set-v1";
    public static final String SNAPSHOT_SCHEMA="check-spec-snapshot-v1";
    public static final String API_REQUEST_SCHEMA="api-request-v1";
    private static final Set<String> CATEGORIES=Set.of("形式检查","资格检查","响应检查","全文信息检查","效期检查",
        "FORMAL","QUALIFICATION","RESPONSIVENESS","FULL_TEXT","VALIDITY");
    private static final Set<String> PURPOSES=Set.of("MANDATORY","SCORING","INFORMATIONAL");
    private static final Set<String> MODES=Set.of("DOCUMENT","EXTERNAL","HUMAN_JUDGMENT");
    private static final Set<String> STANDARDS=Set.of("MATERIAL_SUBMISSION","WRITTEN_COMMITMENT","FACTUAL_CONDITION","OBSERVED_CONSISTENCY","QUALITY_ADVISORY");
    private static final List<String> SPEC_FIELDS=List.of("businessItemId","category","purpose","verificationMode","templateId","templateVersion",
        "subjectSelector","parameters","applicability","proofStandard","requirementRefs","parameterBindings","requiredFields","evidenceContract",
        "sourceCoverage");
    private final Store store;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final CheckTemplateRegistry registry;

    public CheckSpecificationStoreService(Store store,ObjectMapper json,PlatformTransactionManager manager,CheckTemplateRegistry registry) {
        this.store=store;this.json=json;this.tx=new TransactionTemplate(manager);this.registry=registry;
    }

    public List<TemplateMetadata> templates(){return registry.metadata();}

    public Map<String,Object> getSpec(String checkpointId) {
        Stored checkpoint=store.require("checkpoint",checkpointId);
        Stored head=store.mapper().get(specHeadId(checkpoint.projectId,checkpointId));
        if(head==null||!"check_spec".equals(head.kind))throw missing("检查规格尚未创建");
        Map<String,Object> result=withValidation(store.map(head.body));
        activeSpecRevision(checkpoint.projectId,checkpointId).ifPresent(active->result.put("activePublished",active));
        return result;
    }

    /** Returns only a confirmed, structurally valid, immutably published specification. */
    public Optional<CheckSpec> findConfirmedSpec(String projectId,String checkpointId) {
        Optional<Map<String,Object>> active=activeSpecRevision(projectId,checkpointId);
        if(active.isPresent())try{return Optional.of(json.convertValue(active.get(),CheckSpec.class));}
        catch(IllegalArgumentException invalid){return Optional.empty();}
        Map<String,Object> project=projectState(projectId);
        // Once a confirmed check set is active, only its immutable revision pointers may route new
        // work. A later mutable draft must never replace, or add to, that published manifest.
        if(!text(project,"activeCheckSetId").isBlank())return Optional.empty();
        Stored row=store.mapper().get(specHeadId(projectId,checkpointId));
        if(row==null||!"check_spec".equals(row.kind)||!projectId.equals(row.projectId))return Optional.empty();
        Map<String,Object> spec=store.map(row.body);if(!Boolean.TRUE.equals(spec.get("confirmed"))||!validateSpec(spec).isEmpty())return Optional.empty();
        int revision=number(spec.get("revision"),0);String revisionId=specRevisionId(projectId,text(spec,"id"),revision);Stored frozen=store.mapper().get(revisionId);
        if(frozen==null||!"check_spec_revision".equals(frozen.kind))return Optional.empty();
        try{return Optional.of(json.convertValue(store.map(frozen.body),CheckSpec.class));}catch(IllegalArgumentException invalid){return Optional.empty();}
    }

    /** Reads the immutable run snapshot, never a later mutable spec head. */
    public Optional<CheckSpec> findSnapshotSpec(String projectId,String runId,String checkpointId) {
        Stored row=store.mapper().get(snapshotId(projectId,runId));
        if(row==null||!"check_spec_snapshot".equals(row.kind)||!projectId.equals(row.projectId))return Optional.empty();
        for(Map<String,Object> spec:maps(store.map(row.body).get("specs")))if(checkpointId.equals(text(spec,"checkpointId")))
            try{return Optional.of(json.convertValue(spec,CheckSpec.class));}catch(IllegalArgumentException invalid){return Optional.empty();}
        return Optional.empty();
    }

    /** Included in the run fingerprint so a confirmed spec revision can never reuse an older run. */
    public String confirmedSpecsIdentity(String projectId,List<Checkpoint> checkpoints) {
        List<Map<String,Object>> identities=new ArrayList<>();
        for(Checkpoint checkpoint:checkpoints==null?List.<Checkpoint>of():checkpoints)findConfirmedSpec(projectId,checkpoint.id()).ifPresent(spec->identities.add(Map.of(
            "checkpointId",checkpoint.id(),"specId",spec.id(),"revision",spec.revision(),"templateId",spec.templateId(),"templateVersion",spec.templateVersion(),"semanticSignature",spec.semanticSignature())));
        return "template-specs:"+digest(canonical(identities));
    }

    /** Resolves the immutable check-set manifest selected for a new run. */
    public Optional<Map<String,Object>> confirmedSetForRun(String projectId,String tenderDocumentId,String requestedSetId,Integer requestedManifestRevision) {
        Map<String,Object> project=projectState(projectId);
        String setId=requestedSetId==null?"":requestedSetId.trim();
        Integer manifestRevision=requestedManifestRevision;
        if(setId.isBlank()) {
            if(manifestRevision!=null)throw invalidField("checkSetId","提供清单版本时必须同时提供检查集编号");
            setId=text(project,"activeCheckSetId");
            if(!setId.isBlank())manifestRevision=number(project.get("activeCheckSetManifestRevision"),-1);
        }
        if(setId.isBlank())return Optional.empty();
        Map<String,Object> set=confirmedSetRevision(projectId,setId);
        int actual=number(set.get("manifestRevision"),-1);
        if(manifestRevision==null||manifestRevision<0)throw invalidField("manifestRevision","必须提供已确认检查集的清单版本");
        if(actual!=manifestRevision)throw TemplateApiException.conflict("REVISION_CONFLICT","检查集清单版本已变化",actual);
        if(!tenderDocumentId.equals(text(set,"tenderDocumentId")))throw invalidField("checkSetId","检查集不属于当前招标文件版本");
        List<Map<String,Object>> specs=specsForSet(projectId,set);
        List<String> checkpoints=specs.stream().map(spec->text(spec,"checkpointId")).filter(v->!v.isBlank()).toList();
        Map<String,Object> result=new LinkedHashMap<>();result.put("checkSetId",setId);result.put("manifestRevision",actual);
        result.put("tenderDocumentId",tenderDocumentId);result.put("templateCheckpointIds",checkpoints);
        result.put("checkpointManifest",deepCopy(set.getOrDefault("checkpointManifest",List.of())));
        result.put("templateSpecIdentity","template-set:"+digest(canonical(Map.of("set",set,"specs",specs))));
        result.put("revisionRecordId",set.get("revisionRecordId"));return Optional.of(result);
    }

    /** A confirmed set covers the complete enabled checkpoint manifest, including legacy routes. */
    public void verifyRunCheckpointManifest(Map<String,Object> selectedSet,List<Checkpoint> checkpoints) {
        List<Map<String,Object>> expected=maps(selectedSet==null?null:selectedSet.get("checkpointManifest"));
        if(expected.isEmpty())throw TemplateApiException.conflict("CHECKPOINT_MANIFEST_MISSING","已确认检查集缺少完整检查项清单，请重新确认",number(selectedSet==null?null:selectedSet.get("manifestRevision"),-1));
        List<Map<String,Object>> actual=checkpointManifest(checkpoints==null?List.of():checkpoints,expected.stream().filter(row->"TEMPLATE".equals(text(row,"route"))).map(row->text(row,"checkpointId")).collect(java.util.stream.Collectors.toSet()));
        if(!digest(canonical(expected)).equals(digest(canonical(actual))))throw TemplateApiException.conflict("CHECKPOINT_MANIFEST_CHANGED","检查项已在检查集确认后发生变化，请重新确认检查集",number(selectedSet.get("manifestRevision"),-1));
    }

    /** Freezes all migrated specs for a run; checkpoints without a confirmed spec remain LEGACY. */
    public Map<String,Object> snapshotForRun(String projectId,String runId,List<Checkpoint> checkpoints) {
        return snapshotForRun(projectId,runId,checkpoints,null);
    }

    /**
     * Freezes both the immutable specifications and the actual execution route. The caller passes
     * the complete set of checkpoints routed through the compatibility/template executor; this is
     * kept separate from the subset that owns an explicit confirmed CheckSpec.
     */
    public Map<String,Object> snapshotForRun(String projectId,String runId,List<Checkpoint> checkpoints,Collection<String> routedTemplateCheckpointIds) {
        return tx.execute(ignored->{
            store.lock("project",projectId);String id=snapshotId(projectId,runId);Stored existing=store.mapper().get(id);
            if(existing!=null){if(!"check_spec_snapshot".equals(existing.kind)||!projectId.equals(existing.projectId))throw new IllegalStateException("IMMUTABLE_ID_CONFLICT");return store.map(existing.body);}
            List<Map<String,Object>> specs=new ArrayList<>();List<String> legacy=new ArrayList<>();
            for(Checkpoint checkpoint:checkpoints==null?List.<Checkpoint>of():checkpoints) {
                Optional<CheckSpec> confirmed=findConfirmedSpec(projectId,checkpoint.id());
                if(confirmed.isPresent())specs.add(json.convertValue(confirmed.get(),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){}));else legacy.add(checkpoint.id());
            }
            List<String> all=(checkpoints==null?List.<Checkpoint>of():checkpoints).stream().map(Checkpoint::id).toList();
            LinkedHashSet<String> routed=new LinkedHashSet<>(routedTemplateCheckpointIds==null?specs.stream().map(s->text(s,"checkpointId")).toList():routedTemplateCheckpointIds);
            if(!new LinkedHashSet<>(all).containsAll(routed))throw new IllegalArgumentException("TEMPLATE_ROUTING_CHECKPOINT_UNKNOWN");
            List<String> explicit=specs.stream().map(s->text(s,"checkpointId")).filter(routed::contains).toList();
            List<String> compatibility=routed.stream().filter(checkpoint->!explicit.contains(checkpoint)).toList();
            List<String> legacyRoute=all.stream().filter(checkpoint->!routed.contains(checkpoint)).toList();
            Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("schemaVersion",SNAPSHOT_SCHEMA);snapshot.put("id",id);snapshot.put("projectId",projectId);snapshot.put("runId",runId);
            snapshot.put("specs",specs);snapshot.put("legacyCheckpointIds",legacy);snapshot.put("routing",Map.of(
                "templateCheckpointIds",List.copyOf(routed),"explicitTemplateCheckpointIds",explicit,
                "compatibilityTemplateCheckpointIds",compatibility,"legacyCheckpointIds",legacyRoute));snapshot.put("frozenAt",Instant.now().toString());
            snapshot.put("contentDigest",digest(canonical(snapshot)));store.insertImmutableIfAbsent("check_spec_snapshot",id,projectId,runId,snapshot);return snapshot;
        });
    }

    /** Freezes a run from the exact confirmed check-set revision, including its mixed route. */
    public Map<String,Object> snapshotForRun(String projectId,String runId,List<Checkpoint> checkpoints,String setId,int manifestRevision,List<String> documentIds,Collection<String> routedTemplateCheckpointIds) {
        return tx.execute(ignored->{
            store.lock("project",projectId);String id=snapshotId(projectId,runId);Stored existing=store.mapper().get(id);
            if(existing!=null){if(!"check_spec_snapshot".equals(existing.kind)||!projectId.equals(existing.projectId))throw new IllegalStateException("IMMUTABLE_ID_CONFLICT");return store.map(existing.body);}
            Map<String,Object> set=confirmedSetRevision(projectId,setId);int actual=number(set.get("manifestRevision"),-1);
            if(actual!=manifestRevision)throw TemplateApiException.conflict("REVISION_CONFLICT","检查集清单版本已变化",actual);
            List<Map<String,Object>> specs=specsForSet(projectId,set).stream().map(spec->{Map<String,Object> value=copy(spec);value.remove("validation");return value;}).toList();
            List<String> all=(checkpoints==null?List.<Checkpoint>of():checkpoints).stream().map(Checkpoint::id).toList();
            List<String> configured=specs.stream().map(spec->text(spec,"checkpointId")).toList();
            if(!new LinkedHashSet<>(all).containsAll(configured))throw invalidField("checkSetId","检查集包含不属于本次运行的检查项");
            LinkedHashSet<String> routed=new LinkedHashSet<>(routedTemplateCheckpointIds==null?configured:routedTemplateCheckpointIds);
            if(!new LinkedHashSet<>(configured).containsAll(routed))throw invalidField("checkSetId","运行模板路由超出已确认检查集");List<String> explicit=List.copyOf(routed);
            List<String> documents=documentIds==null?List.of():documentIds.stream().filter(Objects::nonNull).map(String::trim).filter(v->!v.isBlank()).distinct().toList();
            for(String documentId:documents){Document document=store.get("document",documentId,Document.class);if(!projectId.equals(document.projectId()))throw invalidField("documentIds","资料不属于当前项目");}
            List<String> legacy=all.stream().filter(checkpoint->!explicit.contains(checkpoint)).toList();
            Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("schemaVersion",SNAPSHOT_SCHEMA);snapshot.put("id",id);snapshot.put("projectId",projectId);snapshot.put("runId",runId);
            snapshot.put("checkSetId",setId);snapshot.put("manifestRevision",manifestRevision);snapshot.put("checkSetRevisionRecordId",set.get("revisionRecordId"));
            snapshot.put("specRevisions",set.getOrDefault("specRevisions",List.of()));snapshot.put("specs",specs);snapshot.put("documentIds",documents);
            snapshot.put("routing",Map.of("templateCheckpointIds",explicit,"explicitTemplateCheckpointIds",explicit,"compatibilityTemplateCheckpointIds",List.of(),"legacyCheckpointIds",legacy));
            snapshot.put("frozenAt",Instant.now().toString());snapshot.put("contentDigest",digest(canonical(snapshot)));
            store.insertImmutableIfAbsent("check_spec_snapshot",id,projectId,runId,snapshot);return snapshot;
        });
    }

    /** Recovery keeps the parent's specification and route byte-for-byte, changing only ownership metadata. */
    public Map<String,Object> copySnapshotForRecovery(String projectId,String parentRunId,String childRunId) {
        return tx.execute(ignored->{
            store.lock("project",projectId);Stored parent=store.mapper().get(snapshotId(projectId,parentRunId));
            if(parent==null||!"check_spec_snapshot".equals(parent.kind)||!projectId.equals(parent.projectId))throw new IllegalStateException("PARENT_TEMPLATE_SNAPSHOT_MISSING");
            String childId=snapshotId(projectId,childRunId);Stored existing=store.mapper().get(childId);
            if(existing!=null){if(!"check_spec_snapshot".equals(existing.kind)||!projectId.equals(existing.projectId))throw new IllegalStateException("IMMUTABLE_ID_CONFLICT");return store.map(existing.body);}
            Map<String,Object> snapshot=copy(store.map(parent.body));snapshot.put("id",childId);snapshot.put("runId",childRunId);snapshot.put("parentSnapshotId",snapshotId(projectId,parentRunId));snapshot.put("frozenAt",Instant.now().toString());snapshot.remove("contentDigest");snapshot.put("contentDigest",digest(canonical(snapshot)));
            store.insertImmutableIfAbsent("check_spec_snapshot",childId,projectId,childRunId,snapshot);return snapshot;
        });
    }

    /** Creates visible migration drafts without changing the legacy checkpoint execution path. */
    public void ensureLegacyDrafts(String projectId,String tenderDocumentId,List<Checkpoint> checkpoints) {
        Document tender=store.get("document",tenderDocumentId,Document.class);if(!projectId.equals(tender.projectId())||!"TENDER".equals(tender.role()))throw invalidField("tenderDocumentId","招标文件不属于当前项目");
        tx.executeWithoutResult(ignored->{store.lock("project",projectId);for(Checkpoint checkpoint:checkpoints==null?List.<Checkpoint>of():checkpoints) {
            String template=legacyTemplate(checkpoint);if(template.isBlank()||store.mapper().get(specHeadId(projectId,checkpoint.id()))!=null)continue;
            Map<String,Object> request=new LinkedHashMap<>();request.put("templateId",template);request.put("templateVersion","1.0");request.put("category",category(checkpoint.category()));request.put("purpose","MANDATORY");request.put("verificationMode","DOCUMENT");
            request.put("proofStandard",legacyProof(checkpoint));request.put("subjectSelector",Map.of("entityType","ORGANIZATION","role","BIDDER","scope","CURRENT_PROJECT","constraints",Map.of()));request.put("parameters",legacyParameters(checkpoint));request.put("applicability",Map.of("state","APPLIES","reason","","sources",List.of()));
            Map<String,Object> source=sourceRef(checkpoint.source(),tender);request.put("requirementRefs",source.isEmpty()?List.of():List.of(source));request.put("parameterBindings",List.of());request.put("requiredFields",Map.of());request.put("evidenceContract",Map.of());
            request.put("sourceCoverage",Map.of("state","LEGACY_DRAFT","legacyCheckpointType",checkpoint.type(),"requiresConfirmation",true));
            Map<String,Object> spec=normalizeSpec(request,Map.of(),checkpoint,projectId,checkpoint.id(),1);store.save("check_spec",specHeadId(projectId,checkpoint.id()),projectId,checkpoint.id(),spec);
        }});
    }

    public Map<String,Object> putSpec(String checkpointId,Map<String,Object> request) {
        Stored checkpoint=store.require("checkpoint",checkpointId);
        Integer expected=requiredRevision(request);
        return tx.execute(ignored->{
            store.lock("project",checkpoint.projectId);
            String headId=specHeadId(checkpoint.projectId,checkpointId);
            Stored row=store.mapper().get(headId);
            Map<String,Object> current=row==null?Map.of():store.map(row.body);
            int revision=number(current.get("revision"),0);
            if(expected!=revision)throw TemplateApiException.conflict("REVISION_CONFLICT","检查规格已被其他修改更新，请刷新后重试",revision);
            Checkpoint legacy=store.get("checkpoint",checkpointId,Checkpoint.class);
            Map<String,Object> spec=normalizeSpec(request,current,legacy,checkpoint.projectId,checkpointId,revision+1);
            List<Map<String,Object>> errors=validateSpec(spec);
            if(!errors.isEmpty())throw TemplateApiException.invalid("检查规格参数不完整或无效",errors);
            store.save("check_spec",headId,checkpoint.projectId,checkpointId,spec);
            return withValidation(spec);
        });
    }

    public Map<String,Object> getCheckSet(String projectId,String setId) {
        store.require("project",projectId);Map<String,Object> set=requireCheckSet(projectId,setId);
        Map<String,Object> out=copy(set);out.put("specs",specsForSet(projectId,set));return out;
    }

    public Map<String,Object> putCheckSet(String projectId,String setId,Map<String,Object> request) {
        requireToken(setId,"setId",128);Integer expected=requiredRevision(request);
        return tx.execute(ignored->{
            store.lock("project",projectId);String headId=setHeadId(projectId,setId);Stored row=store.mapper().get(headId);
            Map<String,Object> current=row==null?Map.of():store.map(row.body);int revision=number(current.get("revision"),0);
            if(expected!=revision)throw TemplateApiException.conflict("REVISION_CONFLICT","检查集已被其他修改更新，请刷新后重试",revision);
            if("CONFIRMED".equals(text(current,"state")))throw TemplateApiException.conflict("CHECK_SET_FROZEN","已确认检查集不可覆盖，请创建新检查集",revision);
            String tender=text(request,"tenderDocumentId");if(tender.isBlank())tender=text(current,"tenderDocumentId");
            if(tender.isBlank())throw invalidField("tenderDocumentId","请选择对应招标文件");
            Document document=store.get("document",tender,Document.class);
            if(!projectId.equals(document.projectId())||!"TENDER".equals(document.role()))throw invalidField("tenderDocumentId","招标文件不属于当前项目");
            List<String> ids=strings(request.containsKey("specIds")?request.get("specIds"):current.get("specIds"));
            verifySpecIds(projectId,ids);
            Map<String,Object> set=new LinkedHashMap<>();set.put("schemaVersion",SET_SCHEMA);set.put("id",setId);set.put("projectId",projectId);
            set.put("tenderDocumentId",tender);set.put("revision",revision+1);set.put("state","DRAFT");set.put("specIds",ids);
            // Freeze the exact draft revisions the user reviewed. Confirmation compares these under
            // the same project lock, so a later edit cannot be silently pulled into the check set.
            set.put("specSelections",specSelections(projectId,tender,ids));
            Set<String> templateCheckpoints=maps(set.get("specSelections")).stream().map(value->text(value,"checkpointId")).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            set.put("checkpointManifest",checkpointManifest(store.list("checkpoint",projectId,tender,Checkpoint.class).stream().filter(Checkpoint::enabled).toList(),templateCheckpoints));
            set.put("sourceCoverage",object(request,"sourceCoverage",current));set.put("unsupportedRequirements",list(request,"unsupportedRequirements",current));
            set.put("legacyMappings",list(request,"legacyMappings",current));set.put("updatedAt",Instant.now().toString());
            set.put("createdAt",current.getOrDefault("createdAt",set.get("updatedAt")));store.save("check_set",headId,projectId,tender,set);return copy(set);
        });
    }

    public Map<String,Object> confirmCheckSet(String projectId,String setId,Map<String,Object> request) {
        requireToken(setId,"setId",128);String key=text(request,"idempotencyKey");requireToken(key,"idempotencyKey",160);
        Integer expected=requiredRevision(request);String requestDigest=digest(canonical(Map.of("setId",setId,"request",request)));
        return tx.execute(ignored->{
            Stored lockedProject=store.lock("project",projectId);
            String apiId=apiRequestId(projectId,"CONFIRM_CHECK_SET",key);Stored prior=store.mapper().get(apiId);
            if(prior!=null) {
                Map<String,Object> saved=store.map(prior.body);
                if(!setId.equals(text(saved,"resourceId"))||!requestDigest.equals(text(saved,"requestDigest")))throw TemplateApiException.conflict("IDEMPOTENCY_CONFLICT","同一幂等键已用于不同的确认请求",number(map(saved.get("response")).get("revision"),0));
                return copy(map(saved.get("response")));
            }
            String headId=setHeadId(projectId,setId);Stored row=store.mapper().get(headId);if(row==null||!"check_set".equals(row.kind))throw missing("检查集不存在");
            Map<String,Object> current=store.map(row.body);int revision=number(current.get("revision"),0);
            if(expected!=revision)throw TemplateApiException.conflict("REVISION_CONFLICT","检查集已被其他修改更新，请刷新后重试",revision);
            if(!"DRAFT".equals(text(current,"state")))throw TemplateApiException.conflict("CHECK_SET_FROZEN","检查集已经确认",revision);
            verifySpecSelectionsUnchanged(projectId,current,revision);
            verifyCheckpointManifestUnchanged(projectId,current,revision);
            List<Map<String,Object>> specs=specsForSet(projectId,current);List<Map<String,Object>> errors=new ArrayList<>();
            if(specs.isEmpty())errors.add(error("specIds","检查集至少需要一个检查规格"));
            for(Map<String,Object> spec:specs)errors.addAll(validateSpec(spec));
            if(!errors.isEmpty())throw TemplateApiException.invalid("检查集包含不可执行的规格",errors);
            String confirmedAt=Instant.now().toString();String actor=bounded(text(request,"confirmedBy"),160);
            List<Map<String,Object>> refs=new ArrayList<>();
            for(Map<String,Object> source:specs) {
                String specId=text(source,"id");int specRevision=number(source.get("revision"),1);String revisionId=specRevisionId(projectId,specId,specRevision);
                Stored alreadyPublished=store.mapper().get(revisionId);
                if(alreadyPublished!=null) {
                    if(!"check_spec_revision".equals(alreadyPublished.kind)||!projectId.equals(alreadyPublished.projectId)||!specId.equals(alreadyPublished.parentId))throw new IllegalStateException("IMMUTABLE_ID_CONFLICT");
                    Map<String,Object> frozen=store.map(alreadyPublished.body);
                    if(!specId.equals(text(frozen,"id"))||specRevision!=number(frozen.get("revision"),-1))throw new IllegalStateException("IMMUTABLE_ID_CONFLICT");
                    Store.ImmutableInsertResult reused=store.insertImmutableIfAbsent("check_spec_revision",revisionId,projectId,specId,frozen);
                    refs.add(Map.of("specId",specId,"revision",specRevision,"revisionRecordId",revisionId,"contentDigest",reused.contentDigest()));
                    continue;
                }
                Map<String,Object> confirmed=copy(source);confirmed.remove("validation");confirmed.put("confirmed",true);confirmed.put("confirmation",Map.of("state","CONFIRMED","confirmedAt",confirmedAt,"actor",actor,"revisionReason",bounded(text(request,"revisionReason"),500)));
                Store.ImmutableInsertResult inserted=store.insertImmutableIfAbsent("check_spec_revision",revisionId,projectId,specId,confirmed);
                confirmed.put("revisionRecordId",revisionId);confirmed.put("contentDigest",inserted.contentDigest());
                store.save("check_spec",specHeadId(projectId,text(confirmed,"checkpointId")),projectId,text(confirmed,"checkpointId"),confirmed);
                refs.add(Map.of("specId",specId,"revision",specRevision,"revisionRecordId",revisionId,"contentDigest",inserted.contentDigest()));
            }
            Map<String,Object> confirmed=copy(current);confirmed.put("revision",revision+1);confirmed.put("manifestRevision",revision+1);confirmed.put("state","CONFIRMED");
            confirmed.put("confirmedAt",confirmedAt);confirmed.put("confirmedBy",actor);confirmed.put("specRevisions",refs);confirmed.put("updatedAt",confirmedAt);
            String setRevisionId=setRevisionId(projectId,setId,revision+1);store.insertImmutableIfAbsent("check_set_revision",setRevisionId,projectId,setId,confirmed);
            confirmed.put("revisionRecordId",setRevisionId);store.save("check_set",headId,projectId,text(confirmed,"tenderDocumentId"),confirmed);
            for(Map<String,Object> ref:refs) {
                String specId=text(ref,"specId");Map<String,Object> spec=specs.stream().filter(value->specId.equals(text(value,"id"))).findFirst().orElseThrow();
                Map<String,Object> pointer=new LinkedHashMap<>();pointer.put("schemaVersion","check-spec-active-v1");pointer.put("projectId",projectId);pointer.put("checkpointId",text(spec,"checkpointId"));pointer.put("specId",specId);
                pointer.put("revision",ref.get("revision"));pointer.put("revisionRecordId",ref.get("revisionRecordId"));pointer.put("contentDigest",ref.get("contentDigest"));pointer.put("checkSetId",setId);pointer.put("manifestRevision",revision+1);pointer.put("activatedAt",confirmedAt);
                store.save("check_spec_active",activeSpecId(projectId,text(spec,"checkpointId")),projectId,text(spec,"checkpointId"),pointer);
            }
            Map<String,Object> projectState=store.map(lockedProject.body);projectState.put("activeCheckSetId",setId);projectState.put("activeCheckSetManifestRevision",revision+1);projectState.put("activeCheckSetTenderId",text(confirmed,"tenderDocumentId"));projectState.put("activeCheckSetConfirmedAt",confirmedAt);
            store.save("project",projectId,projectId,lockedProject.parentId,projectState);
            Map<String,Object> response=copy(confirmed);response.put("specs",specsForSet(projectId,confirmed));
            Map<String,Object> api=new LinkedHashMap<>();api.put("schemaVersion",API_REQUEST_SCHEMA);api.put("projectId",projectId);api.put("operation","CONFIRM_CHECK_SET");
            api.put("idempotencyKey",key);api.put("requestDigest",requestDigest);api.put("resourceId",setId);api.put("response",response);api.put("committedAt",confirmedAt);
            store.insertImmutableIfAbsent("api_request",apiId,projectId,projectId,api);return response;
        });
    }

    /** Freezes the exact confirmed specs and document collection consumed by a run. */
    public Map<String,Object> freezeSnapshot(String projectId,String runId,String setId,int manifestRevision,List<String> documentIds) {
        return tx.execute(ignored->{
            store.lock("project",projectId);String frozenId=snapshotId(projectId,runId);Stored existing=store.mapper().get(frozenId);
            if(existing!=null){if(!"check_spec_snapshot".equals(existing.kind)||!projectId.equals(existing.projectId))throw new IllegalStateException("IMMUTABLE_ID_CONFLICT");return store.map(existing.body);}
            Map<String,Object> set=requireCheckSet(projectId,setId);
            if(!"CONFIRMED".equals(text(set,"state")))throw new TemplateApiException(HttpStatus.CONFLICT,"CHECK_SET_NOT_CONFIRMED","检查集尚未确认",false,List.of(),number(set.get("revision"),0));
            if(number(set.get("manifestRevision"),-1)!=manifestRevision)throw TemplateApiException.conflict("REVISION_CONFLICT","检查集清单版本已变化",number(set.get("manifestRevision"),0));
            List<String> documents=documentIds==null?List.of():documentIds.stream().filter(Objects::nonNull).map(String::trim).filter(v->!v.isBlank()).distinct().toList();
            for(String id:documents){Document doc=store.get("document",id,Document.class);if(!projectId.equals(doc.projectId()))throw invalidField("documentIds","资料不属于当前项目");}
            Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("schemaVersion",SNAPSHOT_SCHEMA);snapshot.put("id",frozenId);snapshot.put("projectId",projectId);
            snapshot.put("runId",runId);snapshot.put("checkSetId",setId);snapshot.put("manifestRevision",manifestRevision);snapshot.put("checkSetRevisionRecordId",set.get("revisionRecordId"));
            List<Map<String,Object>> frozenSpecs=specsForSet(projectId,set).stream().map(spec->{Map<String,Object> copy=copy(spec);copy.remove("validation");return copy;}).toList();
            snapshot.put("specRevisions",set.getOrDefault("specRevisions",List.of()));snapshot.put("specs",frozenSpecs);snapshot.put("documentIds",documents);snapshot.put("frozenAt",Instant.now().toString());
            snapshot.put("routing",Map.of("templateCheckpointIds",frozenSpecs.stream().map(spec->text(spec,"checkpointId")).toList(),"legacyCheckpointIds",List.of()));snapshot.put("contentDigest",digest(canonical(snapshot)));
            store.insertImmutableIfAbsent("check_spec_snapshot",frozenId,projectId,runId,snapshot);return snapshot;
        });
    }

    private Map<String,Object> normalizeSpec(Map<String,Object> request,Map<String,Object> current,Checkpoint legacy,String project,String checkpoint,int revision) {
        Map<String,Object> spec=new LinkedHashMap<>();spec.put("schemaVersion",SPEC_SCHEMA);spec.put("id",current.getOrDefault("id",specId(project,checkpoint)));
        spec.put("revision",revision);spec.put("projectId",project);spec.put("checkpointId",checkpoint);
        for(String field:SPEC_FIELDS)if(request.containsKey(field))spec.put(field,deepCopy(request.get(field)));else if(current.containsKey(field))spec.put(field,deepCopy(current.get(field)));
        spec.putIfAbsent("businessItemId",checkpoint);spec.putIfAbsent("category",legacy.category());spec.putIfAbsent("purpose","MANDATORY");spec.putIfAbsent("verificationMode","DOCUMENT");
        spec.putIfAbsent("subjectSelector",Map.of());spec.putIfAbsent("parameters",Map.of());spec.putIfAbsent("applicability",Map.of("state","APPLIES"));
        spec.putIfAbsent("requirementRefs",legacy.source()==null?List.of():List.of(legacy.source()));spec.putIfAbsent("parameterBindings",List.of());spec.putIfAbsent("requiredFields",Map.of());spec.putIfAbsent("evidenceContract",Map.of());spec.putIfAbsent("sourceCoverage",Map.of());
        normalizeSourceRefs(spec);
        spec.put("category",category(text(spec,"category")));spec.put("confirmed",false);spec.put("confirmation",Map.of("state","DRAFT","actor","","confirmedAt","","revisionReason",""));spec.put("updatedAt",Instant.now().toString());spec.put("createdAt",current.getOrDefault("createdAt",spec.get("updatedAt")));
        spec.put("semanticSignature",digest(canonical(semanticParts(spec))));
        return spec;
    }

    private List<Map<String,Object>> validateSpec(Map<String,Object> spec) {
        List<Map<String,Object>> errors=new ArrayList<>();required(spec,"templateId",errors);required(spec,"templateVersion",errors);
        enumValue(spec,"category",CATEGORIES,errors);enumValue(spec,"purpose",PURPOSES,errors);enumValue(spec,"verificationMode",MODES,errors);enumValue(spec,"proofStandard",STANDARDS,errors);
        objectValue(spec,"subjectSelector",errors);objectValue(spec,"parameters",errors);objectValue(spec,"applicability",errors);objectValue(spec,"requiredFields",errors);objectValue(spec,"evidenceContract",errors);objectValue(spec,"sourceCoverage",errors);
        listValue(spec,"requirementRefs",errors);listValue(spec,"parameterBindings",errors);
        if(strings(spec.get("requirementRefs")).isEmpty()&&!(spec.get("requirementRefs") instanceof List<?> list&&!list.isEmpty()))errors.add(error("requirementRefs","至少保留一处招标要求来源"));
        if(errors.isEmpty())try {
            Map<String,Object> executable=copy(spec);executable.put("confirmed",true);executable.put("confirmation",Map.of("state","CONFIRMED","actor","VALIDATION","confirmedAt","","revisionReason",""));
            CheckSpec candidate=json.convertValue(executable,CheckSpec.class);ValidationResult validation=registry.validate(candidate);
            for(ValidationResult.ValidationError issue:validation.errors())errors.add(Map.of("field",issue.field(),"code",issue.code(),"message",issue.message()));
            String checkpointId=text(spec,"checkpointId");Stored checkpoint=store.require("checkpoint",checkpointId);
            validateSourceRefs(candidate.requirementRefs(),text(spec,"projectId"),checkpoint.parentId,"requirementRefs",errors);
            validateSourceRefs(candidate.applicability().sources(),text(spec,"projectId"),checkpoint.parentId,"applicability.sources",errors);
            for(int i=0;i<candidate.parameterBindings().size();i++)validateSourceRefs(candidate.parameterBindings().get(i).sources(),text(spec,"projectId"),checkpoint.parentId,"parameterBindings["+i+"].sources",errors);
        } catch(IllegalArgumentException invalid){errors.add(error("spec","规格结构无法读取："+invalid.getMessage()));}
        return errors;
    }

    private void validateSourceRefs(Collection<SourceRef> refs,String project,String tenderId,String field,List<Map<String,Object>> errors) {
        int index=0;
        for(SourceRef ref:refs==null?List.<SourceRef>of():refs){String path=field+'['+(index++)+']';Stored row=store.mapper().get(ref.documentId());
            if(row==null||!"document".equals(row.kind)){errors.add(error(path,"来源文档不存在"));continue;}
            try {Document document=store.decode(row.body,Document.class);
                if(!project.equals(document.projectId())||!"TENDER".equals(document.role())||!tenderId.equals(document.id()))errors.add(error(path,"来源不属于当前检查项对应的招标文件"));
                else if(document.version()!=ref.documentVersion()||ref.documentHash().isBlank()||!document.sha256().equals(ref.documentHash()))errors.add(error(path,"来源版本或文件哈希与招标文件不一致"));
                else {
                    cn.bidcheck.domain.Domain.Page page=document.pages().stream().filter(value->value.number()==ref.location()).findFirst().orElse(null);
                    if(page==null)errors.add(error(path,"来源位置不在指定招标文件版本中"));
                    else if(!cn.bidcheck.verification.facts.FactLedgerService.parserFingerprint(page).equals(ref.parserFingerprint()))errors.add(error(path,"来源解析指纹与招标原文不匹配"));
                    else if(!ref.locationType().equals(page.locationType()))errors.add(error(path,"来源位置类型与招标原文不匹配"));
                    else if(normalizeEvidenceText(ref.quote()).isBlank())errors.add(error(path,"来源摘录不能为空"));
                    else if(!normalizeEvidenceText(page.text()).contains(normalizeEvidenceText(ref.quote())))errors.add(error(path,"来源摘录与指定位置的招标原文不匹配"));
                    else if((ref.startOffset()==null)!=(ref.endOffset()==null)||ref.startOffset()!=null&&(ref.endOffset()>page.text().length()||ref.startOffset()>page.text().length()))errors.add(error(path,"来源偏移超出指定原文范围"));
                    else if(ref.startOffset()!=null&&!normalizeEvidenceText(page.text().substring(ref.startOffset(),ref.endOffset())).contains(normalizeEvidenceText(ref.quote()))&&!normalizeEvidenceText(ref.quote()).contains(normalizeEvidenceText(page.text().substring(ref.startOffset(),ref.endOffset()))))errors.add(error(path,"来源摘录与指定偏移不匹配"));
                    else if(!sourceFragmentHash(ref).equals(ref.sourceHash()))errors.add(error(path,"来源片段哈希无效"));
                }
            } catch(RuntimeException unreadable){errors.add(error(path,"来源文档不可读"));}
        }
    }

    private List<Map<String,Object>> specsForSet(String project,Map<String,Object> set) {
        List<Map<String,Object>> out=new ArrayList<>();
        if("CONFIRMED".equals(text(set,"state"))&&!maps(set.get("specRevisions")).isEmpty()) {
            for(Map<String,Object> ref:maps(set.get("specRevisions"))) {
                String revisionId=text(ref,"revisionRecordId");Stored row=store.mapper().get(revisionId);
                if(row==null||!"check_spec_revision".equals(row.kind)||!project.equals(row.projectId))throw new IllegalStateException("CONFIRMED_SPEC_REVISION_MISSING");
                out.add(withValidation(store.map(row.body)));
            }
            return out;
        }
        for(String specId:strings(set.get("specIds"))) {
            Map<String,Object> spec=findSpecByBusinessId(project,specId);if(spec==null)throw invalidField("specIds","检查规格不存在或不属于当前项目："+specId);out.add(withValidation(spec));
        }
        return out;
    }
    private List<Map<String,Object>> specSelections(String project,String tender,List<String> ids) {
        List<Map<String,Object>> selections=new ArrayList<>();
        for(String id:ids){Map<String,Object> spec=findSpecByBusinessId(project,id);if(spec==null)throw invalidField("specIds","检查规格不存在或不属于当前项目："+id);
            String checkpointId=text(spec,"checkpointId");Stored checkpoint=store.mapper().get(checkpointId);
            if(checkpoint==null||!"checkpoint".equals(checkpoint.kind)||!project.equals(checkpoint.projectId)||!tender.equals(checkpoint.parentId))throw invalidField("specIds","检查规格不属于当前检查集对应的招标文件："+id);
            selections.add(Map.of("specId",id,"checkpointId",checkpointId,"tenderDocumentId",tender,"revision",number(spec.get("revision"),0),"semanticSignature",text(spec,"semanticSignature")));}
        return selections;
    }
    private void verifySpecSelectionsUnchanged(String project,Map<String,Object> set,int setRevision) {
        List<String> ids=strings(set.get("specIds"));List<Map<String,Object>> selected=maps(set.get("specSelections"));
        if(selected.size()!=ids.size())throw TemplateApiException.conflict("SPEC_REVISION_CHANGED","检查规格版本记录不完整，请刷新检查集后再确认",setRevision);
        Map<String,Map<String,Object>> byId=new LinkedHashMap<>();selected.forEach(value->byId.put(text(value,"specId"),value));
        String tender=text(set,"tenderDocumentId");for(String id:ids){Map<String,Object> expected=byId.get(id),actual=findSpecByBusinessId(project,id);
            if(expected==null||actual==null||number(expected.get("revision"),-1)!=number(actual.get("revision"),-2)
                    ||!Objects.equals(text(expected,"semanticSignature"),text(actual,"semanticSignature"))||!tender.equals(text(expected,"tenderDocumentId"))
                    ||!text(expected,"checkpointId").equals(text(actual,"checkpointId"))||!checkpointBelongsToTender(project,text(actual,"checkpointId"),tender))
                throw TemplateApiException.conflict("SPEC_REVISION_CHANGED","检查规格已在审阅后发生变化，请刷新检查集后再确认",setRevision);}
    }
    private void verifyCheckpointManifestUnchanged(String project,Map<String,Object> set,int setRevision) {
        String tender=text(set,"tenderDocumentId");List<Map<String,Object>> expected=maps(set.get("checkpointManifest"));
        if(expected.isEmpty())throw TemplateApiException.conflict("CHECKPOINT_MANIFEST_CHANGED","检查项清单不完整，请刷新检查集后再确认",setRevision);
        Set<String> templateIds=expected.stream().filter(row->"TEMPLATE".equals(text(row,"route"))).map(row->text(row,"checkpointId")).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Checkpoint> current=store.list("checkpoint",project,tender,Checkpoint.class).stream().filter(Checkpoint::enabled).toList();
        if(!digest(canonical(expected)).equals(digest(canonical(checkpointManifest(current,templateIds)))))throw TemplateApiException.conflict("CHECKPOINT_MANIFEST_CHANGED","检查项已在审阅后发生变化，请刷新检查集后再确认",setRevision);
    }
    private List<Map<String,Object>> checkpointManifest(List<Checkpoint> checkpoints,Set<String> templateIds) {
        List<Map<String,Object>> manifest=new ArrayList<>();for(Checkpoint checkpoint:checkpoints==null?List.<Checkpoint>of():checkpoints){Map<String,Object> frozen=json.convertValue(checkpoint,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});manifest.add(Map.of("checkpointId",checkpoint.id(),"route",templateIds.contains(checkpoint.id())?"TEMPLATE":"LEGACY","checkpointDigest",digest(canonical(frozen)),"checkpoint",frozen));}
        manifest.sort(Comparator.comparing(row->text(row,"checkpointId")));return manifest;
    }
    private Map<String,Object> findSpecByBusinessId(String project,String specId) {
        return store.maps("check_spec",project,null).stream().filter(s->specId.equals(text(s,"id"))).findFirst().orElse(null);
    }
    private void verifySpecIds(String project,List<String> ids) {if(ids.size()!=new LinkedHashSet<>(ids).size())throw invalidField("specIds","检查规格不能重复");for(String id:ids)if(findSpecByBusinessId(project,id)==null)throw invalidField("specIds","检查规格不存在或不属于当前项目："+id);}
    private Map<String,Object> requireCheckSet(String project,String setId){Stored row=store.mapper().get(setHeadId(project,setId));if(row==null||!"check_set".equals(row.kind)||!project.equals(row.projectId))throw missing("检查集不存在");return store.map(row.body);}
    private Map<String,Object> confirmedSetRevision(String project,String setId) {
        Map<String,Object> head=requireCheckSet(project,setId);
        if(!"CONFIRMED".equals(text(head,"state")))throw new TemplateApiException(HttpStatus.CONFLICT,"CHECK_SET_NOT_CONFIRMED","检查集尚未确认",false,List.of(),number(head.get("revision"),0));
        String revisionId=text(head,"revisionRecordId");Stored row=store.mapper().get(revisionId);
        if(revisionId.isBlank()||row==null||!"check_set_revision".equals(row.kind)||!project.equals(row.projectId)||!setId.equals(row.parentId))throw new IllegalStateException("CONFIRMED_CHECK_SET_REVISION_MISSING");
        Map<String,Object> frozen=store.map(row.body);if(number(frozen.get("manifestRevision"),-1)!=number(head.get("manifestRevision"),-2))throw new IllegalStateException("CONFIRMED_CHECK_SET_REVISION_MISMATCH");
        frozen.put("revisionRecordId",revisionId);return frozen;
    }
    private Optional<Map<String,Object>> activeSpecRevision(String project,String checkpoint) {
        Map<String,Object> projectValue=projectState(project);String activeSet=text(projectValue,"activeCheckSetId");int activeManifest=number(projectValue.get("activeCheckSetManifestRevision"),-1);
        if(activeSet.isBlank()||activeManifest<0)return Optional.empty();Stored pointerRow=store.mapper().get(activeSpecId(project,checkpoint));
        if(pointerRow==null||!"check_spec_active".equals(pointerRow.kind)||!project.equals(pointerRow.projectId))return Optional.empty();Map<String,Object> pointer=store.map(pointerRow.body);
        if(!activeSet.equals(text(pointer,"checkSetId"))||activeManifest!=number(pointer.get("manifestRevision"),-2))return Optional.empty();String revisionId=text(pointer,"revisionRecordId");Stored revision=store.mapper().get(revisionId);
        if(revision==null||!"check_spec_revision".equals(revision.kind)||!project.equals(revision.projectId)||!text(pointer,"specId").equals(revision.parentId))throw new IllegalStateException("ACTIVE_SPEC_REVISION_MISSING");
        Map<String,Object> spec=store.map(revision.body);if(!checkpoint.equals(text(spec,"checkpointId"))||number(spec.get("revision"),-1)!=number(pointer.get("revision"),-2)||!validateSpec(spec).isEmpty())return Optional.empty();return Optional.of(spec);
    }
    private Map<String,Object> projectState(String project){Stored row=store.require("project",project);return store.map(row.body);}
    private boolean checkpointBelongsToTender(String project,String checkpoint,String tender){Stored row=store.mapper().get(checkpoint);return row!=null&&"checkpoint".equals(row.kind)&&project.equals(row.projectId)&&tender.equals(row.parentId);}

    @SuppressWarnings("unchecked") private void normalizeSourceRefs(Map<String,Object> spec) {
        spec.put("requirementRefs",normalizeSourceList(spec.get("requirementRefs")));
        if(spec.get("applicability") instanceof Map<?,?> raw){Map<String,Object> applicability=new LinkedHashMap<>((Map<String,Object>)raw);applicability.put("sources",normalizeSourceList(applicability.get("sources")));spec.put("applicability",applicability);}
        if(spec.get("parameterBindings") instanceof List<?> values){List<Map<String,Object>> bindings=new ArrayList<>();for(Object value:values)if(value instanceof Map<?,?> raw){Map<String,Object> binding=new LinkedHashMap<>((Map<String,Object>)raw);binding.put("sources",normalizeSourceList(binding.get("sources")));bindings.add(binding);}spec.put("parameterBindings",bindings);}
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> normalizeSourceList(Object value) {
        if(!(value instanceof List<?> values))return List.of();List<Map<String,Object>> refs=new ArrayList<>();
        for(Object item:values)if(item instanceof Map<?,?> raw){Map<String,Object> ref=new LinkedHashMap<>((Map<String,Object>)raw);
            // Both identities are authoritative server values. The browser may provide stale or
            // forged placeholders, but published specifications are rebound to the current frozen
            // document page before validation.
            ref.put("parserFingerprint",serverParserFingerprint(ref));ref.put("sourceHash",sourceFragmentHash(ref));refs.add(ref);}return refs;
    }
    private String serverParserFingerprint(Map<String,Object> ref){Stored row=store.mapper().get(text(ref,"documentId"));if(row==null||!"document".equals(row.kind))return "";
        try {Document document=store.decode(row.body,Document.class);String locationType=text(ref,"locationType");int location=number(ref.get("location"),-1);
            List<cn.bidcheck.domain.Domain.Page> pages=document.pages().stream().filter(page->page.number()==location&&locationType.equals(page.locationType())).toList();
            return pages.size()==1?cn.bidcheck.verification.facts.FactLedgerService.parserFingerprint(pages.get(0)):"";
        } catch(RuntimeException unreadable){return "";}}
    private static String sourceFragmentHash(SourceRef ref){return digest(sourceHashMaterial(ref.documentId(),ref.documentVersion(),ref.documentHash(),ref.locationType(),ref.location(),ref.quote(),ref.startOffset(),ref.endOffset()));}
    private static String sourceFragmentHash(Map<String,Object> ref){return digest(sourceHashMaterial(text(ref,"documentId"),number(ref.get("documentVersion"),-1),text(ref,"documentHash"),text(ref,"locationType"),number(ref.get("location"),-1),text(ref,"quote"),integer(ref.get("startOffset")),integer(ref.get("endOffset"))));}
    private static String sourceHashMaterial(String document,int version,String documentHash,String locationType,int location,String quote,Integer start,Integer end){return String.join("\n",document,Integer.toString(version),documentHash,locationType,Integer.toString(location),normalizeEvidenceText(quote),start==null?"":start.toString(),end==null?"":end.toString());}
    private static Integer integer(Object value){return value instanceof Number number?number.intValue():null;}
    private static String normalizeEvidenceText(String value){return value==null?"":java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).replaceAll("\\s+","").trim();}
    private Map<String,Object> withValidation(Map<String,Object> spec){Map<String,Object> out=copy(spec);List<Map<String,Object>> errors=validateSpec(spec);out.put("validation",Map.of("valid",errors.isEmpty(),"errors",errors));return out;}

    private static void required(Map<String,Object> value,String field,List<Map<String,Object>> errors){if(text(value,field).isBlank())errors.add(error(field,"不能为空"));}
    private static void enumValue(Map<String,Object> value,String field,Set<String> allowed,List<Map<String,Object>> errors){String v=text(value,field);if(v.isBlank()||!allowed.contains(v))errors.add(error(field,"值不受支持"));}
    private static void objectValue(Map<String,Object> value,String field,List<Map<String,Object>> errors){if(!(value.get(field) instanceof Map<?,?>))errors.add(error(field,"必须是对象"));}
    private static void listValue(Map<String,Object> value,String field,List<Map<String,Object>> errors){if(!(value.get(field) instanceof List<?>))errors.add(error(field,"必须是数组"));}
    private static Map<String,Object> error(String field,String message){return Map.of("field",field,"message",message);}
    private static TemplateApiException invalidField(String field,String message){return TemplateApiException.invalid("请求参数无效",List.of(error(field,message)));}
    private static TemplateApiException missing(String message){return new TemplateApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND",message,false,List.of(),null);}
    private static Integer requiredRevision(Map<String,Object> request){Object value=request.get("expectedRevision");if(!(value instanceof Number n)||n.intValue()<0)throw invalidField("expectedRevision","必须提供非负整数版本号");return n.intValue();}
    private static void requireToken(String value,String field,int max){if(value==null||value.isBlank()||value.length()>max||!value.matches("[A-Za-z0-9._:-]+"))throw invalidField(field,"格式无效或长度超过限制");}
    private static String bounded(String value,int max){return value==null?"":value.substring(0,Math.min(value.length(),max));}
    private static int number(Object value,int fallback){return value instanceof Number n?n.intValue():fallback;}
    private static String text(Map<String,?> value,String key){Object v=value.get(key);return v==null?"":v.toString().trim();}
    private static List<String> strings(Object value){if(!(value instanceof List<?> list))return List.of();return list.stream().filter(Objects::nonNull).map(Object::toString).map(String::trim).filter(v->!v.isBlank()).toList();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){if(!(value instanceof List<?> list))return List.of();List<Map<String,Object>> out=new ArrayList<>();for(Object item:list)if(item instanceof Map<?,?> row)out.add((Map<String,Object>)row);return out;}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();}
    private static Map<String,Object> copy(Map<String,Object> value){return new LinkedHashMap<>(value);}
    private Object deepCopy(Object value){return json.convertValue(value,Object.class);}
    private static Object object(Map<String,Object> request,String field,Map<String,Object> current){Object value=request.containsKey(field)?request.get(field):current.get(field);return value instanceof Map<?,?>?value:Map.of();}
    private static Object list(Map<String,Object> request,String field,Map<String,Object> current){Object value=request.containsKey(field)?request.get(field):current.get(field);return value instanceof List<?>?value:List.of();}
    private static Map<String,Object> semanticParts(Map<String,Object> spec){Map<String,Object> out=new LinkedHashMap<>();for(String field:List.of("businessItemId","category","purpose","verificationMode","templateId","templateVersion","subjectSelector","parameters","applicability","proofStandard","requirementRefs","parameterBindings"))out.put(field,spec.get(field));return out;}
    private static String category(String category){return switch(category){case "形式检查"->"FORMAL";case "资格检查"->"QUALIFICATION";case "响应检查"->"RESPONSIVENESS";case "全文信息检查"->"FULL_TEXT";case "效期检查"->"VALIDITY";default->category;};}
    private static String legacyTemplate(Checkpoint checkpoint){return switch(checkpoint.type()){case "MATERIAL_EXISTENCE"->"MATERIAL_PRESENT";case "SIGNATURE_SEAL"->"COMMITMENT_WITH_SEAL";case "DATE_VALIDITY"->"CERTIFICATE_VALIDITY";case "VALUE_COMPARE"->"totalPrice".equals(checkpoint.field())?"PRICE_COMPARE":"";default->"";};}
    private static String legacyProof(Checkpoint checkpoint){return switch(checkpoint.type()){case "MATERIAL_EXISTENCE"->"MATERIAL_SUBMISSION";case "SIGNATURE_SEAL"->"WRITTEN_COMMITMENT";case "ENTITY_FIELD_CONSISTENCY","FULL_TEXT_REVIEW"->"OBSERVED_CONSISTENCY";default->"FACTUAL_CONDITION";};}
    private static Map<String,Object> legacyParameters(Checkpoint checkpoint){return switch(legacyTemplate(checkpoint)) {
        case "MATERIAL_PRESENT"->Map.of("materialType",textValue(checkpoint.title()),"requireBody",true);
        case "COMMITMENT_WITH_SEAL"->Map.of("materialType",textValue(checkpoint.title()),"requiredCommitmentPoints",List.of(textValue(checkpoint.expected()).isBlank()?textValue(checkpoint.title()):textValue(checkpoint.expected())),"issuerRole","BIDDER","sealLocation","DOCUMENT_END_OR_SIGNATURE_AREA");
        case "CERTIFICATE_VALIDITY"->Map.of("certificateType",textValue(checkpoint.title()),"comparisonDate",textValue(checkpoint.expected()),"inclusive",true);
        case "PRICE_COMPARE"->Map.of("field",textValue(checkpoint.field()),"operator",textValue(checkpoint.operator()),"expected",textValue(checkpoint.expected()),"unit",textValue(checkpoint.unit()).isBlank()?"元":textValue(checkpoint.unit()),"currency","CNY");
        default->Map.of();
    };}
    private static String textValue(Object value){return value==null?"":value.toString().trim();}
    private static Map<String,Object> sourceRef(cn.bidcheck.domain.Domain.Evidence source,Document tender){if(source==null)return Map.of();Map<String,Object> out=new LinkedHashMap<>();out.put("documentId",source.documentId());out.put("documentVersion",tender.version());out.put("documentHash",tender.sha256());out.put("parserFingerprint",source.anchor()==null?"":source.anchor());out.put("sourceHash",digest((source.quote()==null?"":source.quote())+'\n'+source.page()));out.put("locationType",source.locationType()==null?"BLOCK":source.locationType());out.put("location",Math.max(0,source.page()));out.put("quote",source.quote()==null?"":source.quote());out.put("startOffset",null);out.put("endOffset",null);return out;}
    private String canonical(Object value){try{return json.writeValueAsString(sort(json.valueToTree(value)));}catch(JsonProcessingException failure){throw new IllegalArgumentException("请求内容无法序列化",failure);}}
    private JsonNode sort(JsonNode node){if(node.isObject()){ObjectNode out=json.createObjectNode();List<String> names=new ArrayList<>();node.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String name:names)out.set(name,sort(node.get(name)));return out;}if(node.isArray()){ArrayNode out=json.createArrayNode();node.forEach(v->out.add(sort(v)));return out;}return node;}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String key(String prefix,String... parts){return prefix+":"+digest(String.join("\n",parts)).substring(0,64-prefix.length()-1);}
    private static String specHeadId(String project,String checkpoint){return key("csh",project,checkpoint);}
    private static String activeSpecId(String project,String checkpoint){return key("csa",project,checkpoint);}
    private static String specId(String project,String checkpoint){return key("spec",project,checkpoint);}
    private static String specRevisionId(String project,String spec,int revision){return key("csr",project,spec,Integer.toString(revision));}
    private static String setHeadId(String project,String set){return key("cset",project,set);}
    private static String setRevisionId(String project,String set,int revision){return key("csetr",project,set,Integer.toString(revision));}
    private static String snapshotId(String project,String run){return key("css",project,run);}
    private static String apiRequestId(String project,String operation,String key){return key("api",project,operation,key);}
}
