package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Publishes a fresh deterministic plan only after preservation checks against the previous tree. */
final class RequirementRepairExecution {
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> AUDIT_PARAMETERS=cn.bidcheck.compat.Java8Api.setOf("obligationIdentity", "templateCompilerVersion", "requirementSourceContext", "requirementBindings", "compilationIssue", "focusedScopeIssue", "requirementContextIssue", "repairReason", "sourceRef", "sourceHash", "fragmentId");
    private RequirementRepairExecution() {}

    static Plan versioned(Plan plan){
        Map<String,Object> scope=new LinkedHashMap<>(plan.requirementScope());
        Map<String,Object> revisions=new LinkedHashMap<>(RequirementRepairPolicy.versions());
        revisions.put("requirementSourceRevision",identity(JSON.valueToTree(scope.getOrDefault("sourceContext",cn.bidcheck.compat.Java8Api.mapOf())).toString()));
        scope.put("requirementRevisions",revisions);
        return new Plan(plan.logic(),plan.rules(),plan.method(),plan.issues(),plan.policyNotes(),scope);
    }
    static Plan publish(Plan candidate,RunSession session){
        Map<String,Object> previous=map(session.context.get("previousAtomicCheck"));
        if(!(previous.get("plan") instanceof Map<?,?>))return rejected(candidate,candidate,cn.bidcheck.compat.Java8Api.listOf("补查缺少原规则，不能确认原义务已保留"),session);
        Plan original;
        try{original=JSON.convertValue(((Map<?,?>) (previous.get("plan"))),Plan.class);}catch(IllegalArgumentException invalid){return rejected(candidate,candidate,cn.bidcheck.compat.Java8Api.listOf("原规则结构损坏，未发布重编译结果"),session);}
        List<String> issues=new ArrayList<>();
        if(original.rules().isEmpty()){
            Map<String,Object> context=map(candidate.requirementScope().get("sourceContext"));
            Map<String,Object> repair=map(map(context.get("identity")).get("sourceRepair"));
            if(!"SCOPE_UNRESOLVED".equals(original.method())||!"RESTORED".equals(str(repair,"status")))issues.add("空规则须先恢复唯一原文来源，不能从空计划直接生成通过条件");
            if(candidate.rules().isEmpty()||!candidate.issues().isEmpty())issues.add("恢复来源后尚未形成可验证的非空规则");
        }else{
            candidate=preserveIds(original,candidate);
            issues.addAll(CompilationRecovery.repairIssues(semantic(original),comparison(candidate,original)));
        }
        if(!issues.isEmpty())return rejected(original,candidate,issues,session);
        long executableLeaves=CompilationRecovery.flatten(candidate.rules()).stream().filter(r->r.children().isEmpty()&&RequirementRepairPolicy.executableBusinessPredicate(r.predicate())).count();
        if(original.rules().isEmpty()&&executableLeaves==0)return sourceOnly(original,candidate,session);
        Map<String,Object> scope=new LinkedHashMap<>(candidate.requirementScope());
        scope.put("requirementRepair",linked("version",RequirementRepairPolicy.VERSION,"state","PUBLISHED","originalNodesPreserved",true,
            "sourceRestored",original.rules().isEmpty(),"executableBusinessLeaves",executableLeaves,"ruleCompilationProgress",true,"networkCalls",0,"businessProof",false));
        Plan result=new Plan(candidate.logic(),candidate.rules(),"DETERMINISTIC_REQUIREMENT_REPAIR",candidate.issues(),candidate.policyNotes(),scope);
        Map<String,Object> audit=new LinkedHashMap<>(CompilationRecovery.audit(result,1,cn.bidcheck.compat.Java8Api.listOf(linked("stage","DETERMINISTIC_REQUIREMENT_REPAIR","networkCalls",0,
            "beforeRuleCount",CompilationRecovery.flatten(original.rules()).size(),"afterRuleCount",CompilationRecovery.flatten(result.rules()).size(),"originalNodesPreserved",true))));
        audit.put("requirementRepair",scope.get("requirementRepair"));session.compilationAudit=audit;
        session.record("REQUIREMENT_REPAIR",cn.bidcheck.compat.Java8Api.mapOf("strategy", str(session.context,"recoveryStrategy")),scope.get("requirementRepair"));
        return result;
    }
    private static Plan sourceOnly(Plan original,Plan candidate,RunSession session){
        Map<String,Object> scope=new LinkedHashMap<>(candidate.requirementScope());
        Map<String,Object> repair=linked("version",RequirementRepairPolicy.VERSION,"state","SOURCE_RESTORED_ONLY","originalNodesPreserved",true,
                "sourceRestored",true,"executableBusinessLeaves",0,"ruleCompilationProgress",false,"networkCalls",0,"businessProof",false);
        scope.put("requirementRepair",repair);
        List<String> issues=new ArrayList<>(candidate.issues());issues.add("原文来源已恢复，但尚无可执行业务条件；不计为规则修复完成");
        Plan result=new Plan(candidate.logic(),candidate.rules(),"SOURCE_RESTORED_REQUIREMENT_UNRESOLVED",cn.bidcheck.compat.Java8Api.listCopyOf(issues),candidate.policyNotes(),scope);
        session.compilationAudit=CompilationRecovery.audit(result,1,cn.bidcheck.compat.Java8Api.listOf(linked("stage","SOURCE_RESTORED_ONLY","networkCalls",0,"executableBusinessLeaves",0)));
        session.record("REQUIREMENT_REPAIR",cn.bidcheck.compat.Java8Api.mapOf("strategy", str(session.context,"recoveryStrategy")),repair);return result;
    }
    private static Plan rejected(Plan original,Plan candidate,List<String> reasons,RunSession session){
        Map<String,Object> scope=new LinkedHashMap<>(original.requirementScope());
        scope.put("requirementRevisions",candidate.requirementScope().getOrDefault("requirementRevisions",RequirementRepairPolicy.versions()));
        Map<String,Object> repair=linked("version",RequirementRepairPolicy.VERSION,"state","REJECTED","preservationIssues",reasons,
            "originalNodesPreserved",true,"networkCalls",0,"businessProof",false);
        scope.put("requirementRepair",repair);List<String> issues=new ArrayList<>(original.issues());issues.addAll(reasons);
        Plan retained=new Plan(original.logic(),original.rules(),"REQUIREMENT_REPAIR_REJECTED",cn.bidcheck.compat.Java8Api.listCopyOf(issues),original.policyNotes(),scope);
        session.compilationAudit=CompilationRecovery.audit(retained,1,cn.bidcheck.compat.Java8Api.listOf(linked("stage","DETERMINISTIC_REPAIR_REJECTED","diagnostics",reasons,"networkCalls",0)));
        session.record("REQUIREMENT_REPAIR",cn.bidcheck.compat.Java8Api.mapOf("strategy", str(session.context,"recoveryStrategy")),repair);return retained;
    }
    /** IDs may change with compiler implementation, but source and business meaning cannot. */
    private static Plan preserveIds(Plan original,Plan candidate){
        List<Rule> current=CompilationRecovery.flatten(candidate.rules());Map<String,String> rename=new HashMap<>();Set<String> used=new HashSet<>();
        for(Rule old:CompilationRecovery.flatten(original.rules())){
            List<Rule> matches=cn.bidcheck.compat.Java8Api.toList(current.stream().filter(r->!used.contains(r.id())&&semantic(old).equals(semantic(withoutNewProofRestriction(r,old)))));
            if(matches.isEmpty()&&old.predicate().equals("UNSUPPORTED"))matches=cn.bidcheck.compat.Java8Api.toList(current.stream()
                .filter(r->!used.contains(r.id())&&!cn.bidcheck.compat.Java8Api.isBlank(old.sourceQuote())&&old.sourceQuote().equals(r.sourceQuote()))
                .filter(r->old.id().equals(r.id())||current.stream().filter(c->old.sourceQuote().equals(c.sourceQuote())).count()==1));
            if(matches.size()==1){Rule selected=matches.get(0);used.add(selected.id());rename.put(selected.id(),old.id());}
        }
        List<Rule> rules=cn.bidcheck.compat.Java8Api.toList(candidate.rules().stream().map(r->rename(r,rename)));
        // Ambiguous/colliding identities stay rejected by normal preservation validation.
        if(CompilationRecovery.flatten(rules).stream().map(Rule::id).distinct().count()!=CompilationRecovery.flatten(rules).size())return candidate;
        return new Plan(candidate.logic(),rules,candidate.method(),candidate.issues(),candidate.policyNotes(),candidate.requirementScope());
    }
    private static Rule rename(Rule r,Map<String,String> names){return new Rule(names.getOrDefault(r.id(),r.id()),r.description(),r.predicate(),r.subject(),r.field(),r.operator(),r.expected(),r.unit(),r.parameters(),r.sourceQuote(),r.logic(),cn.bidcheck.compat.Java8Api.toList(r.children().stream().map(c->rename(c,names))));}
    private static Plan semantic(Plan plan){return new Plan(plan.logic(),cn.bidcheck.compat.Java8Api.toList(plan.rules().stream().map(RequirementRepairExecution::semanticRule)),plan.method(),plan.issues(),plan.policyNotes(),plan.requirementScope());}
    private static Plan comparison(Plan plan,Plan original){Map<String,Rule> old=new HashMap<>();CompilationRecovery.flatten(original.rules()).forEach(r->old.put(r.id(),r));
        return new Plan(plan.logic(),cn.bidcheck.compat.Java8Api.toList(plan.rules().stream().map(r->comparison(r,old))),plan.method(),plan.issues(),plan.policyNotes(),plan.requirementScope());}
    private static Rule comparison(Rule rule,Map<String,Rule> old){Rule r=semanticRule(withoutNewProofRestriction(rule,old.get(rule.id())));
        return new Rule(r.id(),r.description(),r.predicate(),r.subject(),r.field(),r.operator(),r.expected(),r.unit(),r.parameters(),r.sourceQuote(),r.logic(),cn.bidcheck.compat.Java8Api.toList(rule.children().stream().map(c->comparison(c,old))));}
    /** Adding a source-bound association strengthens proof; deleting an existing association is never ignored. */
    private static Rule withoutNewProofRestriction(Rule r,Rule old){if(old==null)return r;Map<String,Object> p=new LinkedHashMap<>(r.parameters());
        for(String key:cn.bidcheck.compat.Java8Api.listOf("qualificationChoiceFingerprint"))if(!old.parameters().containsKey(key))p.remove(key);
        return new Rule(r.id(),r.description(),r.predicate(),r.subject(),r.field(),r.operator(),r.expected(),r.unit(),p,r.sourceQuote(),r.logic(),r.children());}
    private static Rule semanticRule(Rule r){Map<String,Object> p=new LinkedHashMap<>(r.parameters());AUDIT_PARAMETERS.forEach(p::remove);
        return new Rule(r.id(),"",r.predicate(),r.subject(),r.field(),r.operator(),r.expected(),r.unit(),p,r.sourceQuote(),r.logic(),cn.bidcheck.compat.Java8Api.toList(r.children().stream().map(RequirementRepairExecution::semanticRule)));}
    private static Object semantic(Rule r){Rule clean=semanticRule(r);return cn.bidcheck.compat.Java8Api.listOf(clean.predicate(), clean.subject(), clean.field(), clean.operator(), clean.expected(), clean.unit(), clean.parameters(), clean.sourceQuote(), clean.logic(), cn.bidcheck.compat.Java8Api.toList(clean.children().stream().map(RequirementRepairExecution::semantic)));}
    private static Map<String,Object> map(Object value){Map<String,Object> result=new LinkedHashMap<>();if(value instanceof Map<?,?>)((Map<?,?>) (value)).forEach((k,v)->result.put(String.valueOf(k),v));return result;}
}
