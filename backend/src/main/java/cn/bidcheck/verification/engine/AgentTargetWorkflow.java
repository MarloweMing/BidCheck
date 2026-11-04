package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Host-owned progress: aliases and candidate formatting cannot create a new business target. */
final class AgentTargetWorkflow {
    private final Map<String,Set<String>> attemptedRepairs=new LinkedHashMap<>();
    private final Map<String,String> exhausted=new LinkedHashMap<>();
    private final Map<String,String> validationFamilies = new LinkedHashMap<>();

    static String family(List<Map<String,Object>> issues){
        Set<String> codes=new HashSet<>();issues.forEach(i->codes.add(str(i,"code")));
        if(codes.stream().anyMatch(c->cn.bidcheck.compat.Java8Api.setOf("SUBJECT_BINDING_REQUIRED", "CERTIFICATE_SUBJECT_UNPROVEN", "RELATION_ENDPOINTS_UNPROVEN", "CERTIFICATE_SUBJECT_FIELD_BINDING_INVALID").contains(c)))return "SUBJECT_OR_RECORD_BINDING";
        if(codes.stream().anyMatch(c->cn.bidcheck.compat.Java8Api.setOf("MONTH_ROW_UNPROVEN", "SOCIAL_EMPLOYER_LINK_UNPROVEN").contains(c)))return "TABLE_RECORD_BINDING";
        // Type/field rewrites and invented concatenated quotations are one extraction repair.
        return "TYPED_SOURCE_EXTRACTION";
    }
    static String workKey(Rule rule,String entity){return identity(rule.id()+"|"+rule.predicate()+"|"+entity);}
    Map<String,Object> observe(Rule rule,String entity,String revision,List<Map<String,Object>> issues){
        String key=workKey(rule,entity),versionKey=key+"|"+revision;
        String family=family(issues);
        validationFamilies.put(versionKey, family);
        Set<String> attempts=attemptedRepairs.computeIfAbsent(versionKey,k->new LinkedHashSet<>());
        boolean repeated=!attempts.add(family)||revision.equals(exhausted.get(key));
        if(repeated)exhausted.put(key,revision);
        return item(rule,entity,revision,family,repeated);
    }
    Map<String,Object> state(Rule rule,String entity,String revision) {
        String key = workKey(rule, entity);
        return item(rule, entity, revision, validationFamilies.getOrDefault(key + "|" + revision, ""),
            revision.equals(exhausted.get(key)));
    }
    private Map<String,Object> item(Rule rule,String entity,String revision,String family,boolean stopped){
        String action;
switch (family) {case "SUBJECT_OR_RECORD_BINDING":action = "RESOLVE_SUBJECT_FIRST"; break;case "TABLE_RECORD_BINDING":action = "READ_TABLE_RECORD"; break;default:action = "READ_COMPLETE_RECORD"; break;}
        return linked("workKey",workKey(rule,entity),"targetObligationId",rule.id(),"evidenceRevision",revision,
            "predicate",rule.predicate(),"entityRecordId",entity,"failureFamily",family,
            "repairActionOrigin",family.isEmpty()?"DEFAULT_GUIDANCE":"VALIDATION_REPAIR","terminalForRevision",stopped,
            "targetExhaustedForRevision",stopped,"remainingRepairActions",stopped?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf(action),
            "reason",stopped?"同一目标的相同证据记录已完成对应提取修正，仍未得到可验证事实；保留系统缺口，相关新记录可以重新激活。":"只执行明确的来源或主体修正，不改写事实类型来扩大目标。");
    }
}
