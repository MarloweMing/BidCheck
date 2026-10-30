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
        if(Set.of("CONDITIONAL","FOR_EACH","ALL","ANY","AND","OR").contains(predicate)&&node.path("children").isArray()&&!node.path("children").isEmpty()){
            if(logic.isBlank())((ObjectNode)node).put("logic",predicate.equals("AND")?"ALL":predicate.equals("OR")?"ANY":predicate);
            ((ObjectNode)node).put("predicate","GROUP");
        }
        for(String key:List.of("rules","children"))if(node.path(key).isArray())node.path(key).forEach(CompilationRecovery::normalizeNode);
    }
    static List<Map<String,Object>> diagnostics(Plan plan){
        List<Map<String,Object>> out=new ArrayList<>();
        for(String issue:plan.issues())out.add(linked("obligationId","","reasonCode","PLAN_VALIDATION_FAILED","need",issue));
        for(Rule rule:flatten(plan.rules()))if(rule.predicate().equals("UNSUPPORTED")){
            String issue=str(rule.parameters(),"compilationIssue");
            if(issue.isBlank())issue=str(rule.parameters(),"focusedScopeIssue");
            if(issue.isBlank())issue=str(rule.parameters(),"requirementContextIssue");
            Map<String,Object> diagnostic=linked("obligationId",rule.id(),"reasonCode",issue.isBlank()?"CAPABILITY_UNRESOLVED":"RULE_VALIDATION_FAILED",
                "need",issue.isBlank()?rule.description():issue,"sourceQuote",rule.sourceQuote());
            Map<String,Object> primitive=RequirementPrimitiveSemantics.diagnostic(rule);if(!primitive.isEmpty())diagnostic.put("primitiveRepair",primitive);
            out.add(diagnostic);
        }
        return List.copyOf(out);
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
        if(original.logic().equals("ANY")&&!original.rules().stream().map(Rule::id).toList().equals(repaired.rules().stream().map(Rule::id).toList()))issues.add("修复改变了可选路径集合，不能新增更弱替代项");
        Map<String,List<String>> originalPaths=branchPaths(original,true),repairedPaths=branchPaths(repaired,false);
        for(Rule rule:flatten(original.rules())){
            Rule replacement=now.get(rule.id());
            if(replacement==null)issues.add("修复删除了原有义务："+rule.id());
            else {
                // Keeping a node's text/ID is insufficient: placing a formerly mandatory
                // obligation under a new OR/conditional can silently make it skippable.
                // ALL-only wrappers are harmless and do not enter this execution path.
                if(!Objects.equals(originalPaths.get(rule.id()),repairedPaths.get(rule.id())))issues.add("修复改变了原义务的强制执行路径，使条件可能被跳过："+rule.id());
                if(!rule.sourceQuote().isBlank()&&!rule.sourceQuote().equals(replacement.sourceQuote()))issues.add("修复改变了已核对来源范围："+rule.id());
                if(!rule.children().isEmpty()){
                    if(Set.of("ALL","ANY","CONDITIONAL","FOR_EACH").contains(rule.logic())&&!rule.logic().equals(replacement.logic())&&!str(rule.parameters(),"expectedSourceLogic").equals(replacement.logic()))issues.add("修复改变了已明确的分支逻辑："+rule.id());
                    if(!rule.children().stream().map(Rule::id).toList().equals(replacement.children().stream().map(Rule::id).toList()))issues.add("修复改变了原子条件的父子关系或条件分支顺序："+rule.id());
                }
                String business=str(rule.parameters(),"originalPredicate");
                if(rule.predicate().equals("UNSUPPORTED")&&PREDICATES.contains(business)&&!business.equals("UNSUPPORTED")&&!replacement.predicate().equals(business)&&!replacement.predicate().equals("UNSUPPORTED")&&!RequirementPrimitiveSemantics.allowsCorrection(rule,replacement))issues.add("修复替换了原有业务谓词："+rule.id());
                if(rule.predicate().equals("UNSUPPORTED")&&Set.of("REFERENCE_TIME","INPUT").contains(replacement.predicate())&&!Set.of("REFERENCE_TIME","INPUT").contains(business))issues.add("修复用辅助条件替代未完成业务义务："+rule.id());
                if(rule.predicate().equals("UNSUPPORTED")&&replacement.predicate().equals("NOT_APPLICABLE")&&(!business.equals("NOT_APPLICABLE")||!ConditionalRequirement.verified(replacement)))issues.add("修复不能将未完成业务义务改为缺少已核对适用条件的不适用分支："+rule.id());
            }
        }
        for(Rule rule:lockedRules(original))if(!rule.equals(now.get(rule.id())))issues.add("修复改动了已通过编译验证的子树："+rule.id());
        return issues;
    }
    private static Map<String,List<String>> branchPaths(Plan plan,boolean original){
        Map<String,List<String>> paths=new LinkedHashMap<>();String top=original?RequirementLogic.topExpected(plan):"";
        if(top.isBlank())top=plan.logic();
        for(int i=0;i<plan.rules().size();i++){Rule rule=plan.rules().get(i);walkPaths(rule,branchPath(List.of(),"$PLAN",top,rule.id(),i),paths,original);}
        return paths;
    }
    private static void walkPaths(Rule rule,List<String> path,Map<String,List<String>> paths,boolean original){
        paths.put(rule.id(),path);String logic=original?str(rule.parameters(),"expectedSourceLogic"):"";
        if(logic.isBlank())logic=rule.logic();
        for(int i=0;i<rule.children().size();i++){Rule child=rule.children().get(i);walkPaths(child,branchPath(path,rule.id(),logic,child.id(),i),paths,original);}
    }
    private static List<String> branchPath(List<String> path,String parent,String logic,String child,int index){
        if(!Set.of("ANY","CONDITIONAL","FOR_EACH").contains(logic))return path;
        List<String> next=new ArrayList<>(path);next.add(logic+":"+parent+":"+(logic.equals("CONDITIONAL")?index:child));return List.copyOf(next);
    }
    static Map<String,Object> audit(Plan plan,int repairs,List<Map<String,Object>> history){
        List<Map<String,Object>> gaps=diagnostics(plan);
        return linked("version",VERSION,"state",gaps.isEmpty()?"VALIDATED":"UNRESOLVED","repairAttempts",repairs,
            "diagnostics",gaps,"history",List.copyOf(history),"capabilities",flatten(plan.rules()).stream()
                .filter(r->!Set.of("","GROUP").contains(r.predicate())).map(r->linked("obligationId",r.id(),"predicate",r.predicate(),"executor",executor(r.predicate()))).toList());
    }
    /** Preserve capability inventory, but only unresolved requirements on the current
     * proof path may schedule a compiler repair. No plan validation issue is waived. */
    static Map<String,Object> activeAudit(Plan plan,RuleEvaluator evaluator,Map<String,Object> previous){
        Map<String,Object> out=new LinkedHashMap<>(previous.isEmpty()?audit(plan,0,List.of()):previous);
        List<Map<String,Object>> all=diagnostics(plan);
        Set<String> covered=AgentInvestigationScope.coveredByCurrentTree(plan,evaluator);
        List<Map<String,Object>> blocking=all.stream().filter(d->str(d,"obligationId").isBlank()||!covered.contains(str(d,"obligationId"))).toList();
        out.put("compileInventoryState",all.isEmpty()?"VALIDATED":"UNRESOLVED");
        out.put("state",blocking.isEmpty()?"VALIDATED":"UNRESOLVED");
        out.put("allCompileDiagnostics",all);out.put("blockingDiagnosticsOnActiveProofPath",blocking);
        out.put("diagnostics",blocking);out.put("proofPathVersion","current-server-proof-1");
        out.put("nonBlockingDiagnosticCount",all.size()-blocking.size());return out;
    }
    static String executor(String predicate){return switch(predicate){case "VISUAL"->"VISUAL_OBSERVATION";case "EXTERNAL"->"EXTERNAL_RECORD";case "QUALITY"->"QUALITY_EVIDENCE";case "UNSUPPORTED"->"REQUIREMENT_REPAIR";case "INPUT"->"APPLICABILITY_INPUT";default->"GROUNDED_RULE";};}
}
