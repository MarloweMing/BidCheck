package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Protocol normalization and repair validation. Neither operation supplies missing business evidence. */
final class CompilationRecovery {
    static final String VERSION="compiled-requirement-validation-11-mandatory-path-preservation";
    private CompilationRecovery(){}

    static JsonNode normalize(JsonNode response){
        JsonNode copy=response.deepCopy();normalizeNode(copy);return copy;
    }
    private static void normalizeNode(JsonNode node){
        if(!node.isObject())return;
        String logic=node.path("logic").asText();
        if(logic.equals("AND")||logic.equals("OR"))((ObjectNode)node).put("logic",logic.equals("AND")?"ALL":"ANY");
        String predicate=node.path("predicate").asText();
        if(cn.bidcheck.compat.Java8Api.setOf("CONDITIONAL", "FOR_EACH", "ALL", "ANY", "AND", "OR").contains(predicate)&&node.path("children").isArray()&&!node.path("children").isEmpty()){
            if(cn.bidcheck.compat.Java8Api.isBlank(logic))((ObjectNode)node).put("logic",predicate.equals("AND")?"ALL":predicate.equals("OR")?"ANY":predicate);
            ((ObjectNode)node).put("predicate","GROUP");
        }
        for(String key:cn.bidcheck.compat.Java8Api.listOf("rules", "children"))if(node.path(key).isArray())node.path(key).forEach(CompilationRecovery::normalizeNode);
    }
    static List<Map<String,Object>> diagnostics(Plan plan){
        List<Map<String,Object>> out=new ArrayList<>();
        for(String issue:plan.issues())out.add(linked("obligationId","","reasonCode","PLAN_VALIDATION_FAILED","need",issue));
        for(Rule rule:flatten(plan.rules()))if(rule.predicate().equals("UNSUPPORTED")){
            String issue=str(rule.parameters(),"compilationIssue");
            if(cn.bidcheck.compat.Java8Api.isBlank(issue))issue=str(rule.parameters(),"focusedScopeIssue");
            if(cn.bidcheck.compat.Java8Api.isBlank(issue))issue=str(rule.parameters(),"requirementContextIssue");
            Map<String,Object> diagnostic=linked("obligationId",rule.id(),"reasonCode",cn.bidcheck.compat.Java8Api.isBlank(issue)?"CAPABILITY_UNRESOLVED":"RULE_VALIDATION_FAILED",
                "need",cn.bidcheck.compat.Java8Api.isBlank(issue)?rule.description():issue,"sourceQuote",rule.sourceQuote());
            Map<String,Object> primitive=RequirementPrimitiveSemantics.diagnostic(rule);if(!primitive.isEmpty())diagnostic.put("primitiveRepair",primitive);
            out.add(diagnostic);
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }
    static List<Rule> flatten(List<Rule> rules){List<Rule> out=new ArrayList<>();for(Rule r:rules){out.add(r);out.addAll(flatten(r.children()));}return out;}
    static boolean repairable(Plan plan){return !plan.issues().isEmpty()||diagnostics(plan).stream().anyMatch(d->!str(d,"reasonCode").equals("CAPABILITY_UNRESOLVED"));}
    private static boolean executableTree(Rule rule){return !rule.predicate().equals("UNSUPPORTED")&&rule.children().stream().allMatch(CompilationRecovery::executableTree);}
    static List<Rule> lockedRules(Plan plan){List<Rule> out=new ArrayList<>();lock(plan.rules(),out);return out;}
    private static void lock(List<Rule> rules,List<Rule> out){for(Rule rule:rules){if(executableTree(rule))out.add(rule);else lock(rule.children(),out);}}

    /** A repair may fix invalid nodes and add uncovered obligations, never delete an original node or alter a validated subtree. */
    static List<String> repairIssues(Plan original,Plan repaired){
        Map<String,Rule> now=new LinkedHashMap<>();flatten(repaired.rules()).forEach(r->now.put(r.id(),r));
        List<String> issues=new ArrayList<>();
        if(!original.logic().equals(repaired.logic())&&!RequirementLogic.topExpected(original).equals(repaired.logic()))issues.add("修复改变了原计划顶层且/或关系，须另行核对原文逻辑");
        if(original.logic().equals("ANY")&&!cn.bidcheck.compat.Java8Api.toList(original.rules().stream().map(Rule::id)).equals(cn.bidcheck.compat.Java8Api.toList(repaired.rules().stream().map(Rule::id))))issues.add("修复改变了可选路径集合，不能新增更弱替代项");
        Map<String,List<String>> originalPaths=branchPaths(original,true),repairedPaths=branchPaths(repaired,false);
        for(Rule rule:flatten(original.rules())){
            Rule replacement=now.get(rule.id());
            if(replacement==null)issues.add("修复删除了原有义务："+rule.id());
            else {
                // Keeping a node's text/ID is insufficient: placing a formerly mandatory
                // obligation under a new OR/conditional can silently make it skippable.
                // ALL-only wrappers are harmless and do not enter this execution path.
                if(!Objects.equals(originalPaths.get(rule.id()),repairedPaths.get(rule.id())))issues.add("修复改变了原义务的强制执行路径，使条件可能被跳过："+rule.id());
                if(!cn.bidcheck.compat.Java8Api.isBlank(rule.sourceQuote())&&!rule.sourceQuote().equals(replacement.sourceQuote()))issues.add("修复改变了已核对来源范围："+rule.id());
                if(!rule.children().isEmpty()){
                    if(cn.bidcheck.compat.Java8Api.setOf("ALL", "ANY", "CONDITIONAL", "FOR_EACH").contains(rule.logic())&&!rule.logic().equals(replacement.logic())&&!str(rule.parameters(),"expectedSourceLogic").equals(replacement.logic()))issues.add("修复改变了已明确的分支逻辑："+rule.id());
                    if(!cn.bidcheck.compat.Java8Api.toList(rule.children().stream().map(Rule::id)).equals(cn.bidcheck.compat.Java8Api.toList(replacement.children().stream().map(Rule::id))))issues.add("修复改变了原子条件的父子关系或条件分支顺序："+rule.id());
                }
                String business=str(rule.parameters(),"originalPredicate");
                if(rule.predicate().equals("UNSUPPORTED")&&PREDICATES.contains(business)&&!business.equals("UNSUPPORTED")&&!replacement.predicate().equals(business)&&!replacement.predicate().equals("UNSUPPORTED")&&!RequirementPrimitiveSemantics.allowsCorrection(rule,replacement))issues.add("修复替换了原有业务谓词："+rule.id());
                if(rule.predicate().equals("UNSUPPORTED")&&cn.bidcheck.compat.Java8Api.setOf("REFERENCE_TIME", "INPUT").contains(replacement.predicate())&&!cn.bidcheck.compat.Java8Api.setOf("REFERENCE_TIME", "INPUT").contains(business))issues.add("修复用辅助条件替代未完成业务义务："+rule.id());
                if(rule.predicate().equals("UNSUPPORTED")&&replacement.predicate().equals("NOT_APPLICABLE")&&(!business.equals("NOT_APPLICABLE")||!ConditionalRequirement.verified(replacement)))issues.add("修复不能将未完成业务义务改为缺少已核对适用条件的不适用分支："+rule.id());
            }
        }
        for(Rule rule:lockedRules(original))if(!rule.equals(now.get(rule.id())))issues.add("修复改动了已通过编译验证的子树："+rule.id());
        return issues;
    }
    private static Map<String,List<String>> branchPaths(Plan plan,boolean original){
        Map<String,List<String>> paths=new LinkedHashMap<>();String top=original?RequirementLogic.topExpected(plan):"";
        if(cn.bidcheck.compat.Java8Api.isBlank(top))top=plan.logic();
        for(int i=0;i<plan.rules().size();i++){Rule rule=plan.rules().get(i);walkPaths(rule,branchPath(cn.bidcheck.compat.Java8Api.listOf(),"$PLAN",top,rule.id(),i),paths,original);}
        return paths;
    }
    private static void walkPaths(Rule rule,List<String> path,Map<String,List<String>> paths,boolean original){
        paths.put(rule.id(),path);String logic=original?str(rule.parameters(),"expectedSourceLogic"):"";
        if(cn.bidcheck.compat.Java8Api.isBlank(logic))logic=rule.logic();
        for(int i=0;i<rule.children().size();i++){Rule child=rule.children().get(i);walkPaths(child,branchPath(path,rule.id(),logic,child.id(),i),paths,original);}
    }
    private static List<String> branchPath(List<String> path,String parent,String logic,String child,int index){
        if(!cn.bidcheck.compat.Java8Api.setOf("ANY", "CONDITIONAL", "FOR_EACH").contains(logic))return path;
        List<String> next=new ArrayList<>(path);next.add(logic+":"+parent+":"+(logic.equals("CONDITIONAL")?index:child));return cn.bidcheck.compat.Java8Api.listCopyOf(next);
    }
    static Map<String,Object> audit(Plan plan,int repairs,List<Map<String,Object>> history){
        List<Map<String,Object>> gaps=diagnostics(plan);
        return linked("version",VERSION,"state",gaps.isEmpty()?"VALIDATED":"UNRESOLVED","repairAttempts",repairs,
            "diagnostics",gaps,"history",cn.bidcheck.compat.Java8Api.listCopyOf(history),"capabilities",cn.bidcheck.compat.Java8Api.toList(flatten(plan.rules()).stream()
                .filter(r->!cn.bidcheck.compat.Java8Api.setOf("", "GROUP").contains(r.predicate())).map(r->linked("obligationId",r.id(),"predicate",r.predicate(),"executor",executor(r.predicate())))));
    }
    /** Preserve capability inventory, but only unresolved requirements on the current
     * proof path may schedule a compiler repair. No plan validation issue is waived. */
    static Map<String,Object> activeAudit(Plan plan,RuleEvaluator evaluator,Map<String,Object> previous){
        Map<String,Object> out=new LinkedHashMap<>(previous.isEmpty()?audit(plan,0,cn.bidcheck.compat.Java8Api.listOf()):previous);
        List<Map<String,Object>> all=diagnostics(plan);
        Set<String> covered=AgentInvestigationScope.coveredByCurrentTree(plan,evaluator);
        List<Map<String,Object>> blocking=cn.bidcheck.compat.Java8Api.toList(all.stream().filter(d->cn.bidcheck.compat.Java8Api.isBlank(str(d,"obligationId"))||!covered.contains(str(d,"obligationId"))));
        out.put("compileInventoryState",all.isEmpty()?"VALIDATED":"UNRESOLVED");
        out.put("state",blocking.isEmpty()?"VALIDATED":"UNRESOLVED");
        out.put("allCompileDiagnostics",all);out.put("blockingDiagnosticsOnActiveProofPath",blocking);
        out.put("diagnostics",blocking);out.put("proofPathVersion","current-server-proof-1");
        out.put("nonBlockingDiagnosticCount",all.size()-blocking.size());return out;
    }
    static String executor(String predicate){switch (predicate) {case "VISUAL":return "VISUAL_OBSERVATION";case "EXTERNAL":return "EXTERNAL_RECORD";case "QUALITY":return "QUALITY_EVIDENCE";case "UNSUPPORTED":return "REQUIREMENT_REPAIR";case "INPUT":return "APPLICABILITY_INPUT";default:return "GROUNDED_RULE";}}
}
