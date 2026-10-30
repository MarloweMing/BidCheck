package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Describes bounded next actions. The caller owns durable scheduling and never treats these as business proof. */
final class ObligationRecovery {
    private ObligationRecovery(){}
    static List<Rule> retrievalRules(List<Rule> rules,Object recoveryGaps){
        List<Map<String,Object>> gaps=RuleEvaluator.maps(recoveryGaps);if(gaps.isEmpty())return rules;
        return rules.stream().map(rule->{List<String> needs=gaps.stream().filter(g->{String id=str(g,"obligationId");return id.isBlank()||id.equals(rule.id());})
            .map(g->str(g,"need").isBlank()?str(g,"description"):str(g,"need")).filter(s->!s.isBlank()).limit(6).toList();
            if(needs.isEmpty())return rule;
            String text=String.join(" ",needs);if(text.length()>1200)text=text.substring(0,1200);
            return new Rule(rule.id(),rule.description()+"\n待补字段与关系："+text,rule.predicate(),rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),rule.parameters(),rule.sourceQuote(),rule.logic(),rule.children());
        }).toList();
    }
    static List<Map<String,Object>> actions(Plan plan,Map<String,Decision> decisions,List<Source> candidates,RunSession session){
        List<Map<String,Object>> out=new ArrayList<>();String base=identity(VERSION+CompilationRecovery.VERSION+RequirementRepairPolicy.versions()+session.scope+plan.requirementScope().getOrDefault("requirementRevisions",Map.of())+plan.logic()+plan.rules().stream().map(SharedFactReuse::signature).toList()+plan.issues()+candidates.stream().map(Source::id).toList());
        for(Map<String,Object> diagnostic:CompilationRecovery.diagnostics(plan))add(out,"REPAIR_REQUIREMENT",str(diagnostic,"reasonCode"),str(diagnostic,"obligationId"),str(diagnostic,"need"),base);
        if(plan.method().equals("SCOPE_UNRESOLVED"))add(out,"RESTORE_REQUIREMENT_SOURCE","REQUIREMENT_SCOPE_UNRESOLVED","",String.join("；",plan.issues()),base);
        for(Decision d:decisions.values())for(Gap gap:d.gaps()){
            if(Set.of("UNSUPPORTED_RULE","REQUIREMENT_COMPILATION_INCOMPLETE").contains(gap.code()))continue;
            // Requirement/mapping failures must stay on the requirement side, even when the gap
            // mentions a visual document. Re-reading bid images cannot repair an unknown rule.
            if(RecoveryFailureRouting.requirement(gap.code())){
                add(out,"REPAIR_REQUIREMENT",gap.code(),d.id(),gap.description(),base);continue;
            }
            String strategy=switch(RecoveryFailureRouting.owner(gap.code())){
                case "SOURCE_IMAGE"->"OBSERVE_SOURCE_PAGE";case "EXTERNAL"->"RESOLVE_EXTERNAL_RECORD";
                case "RELATIONSHIP"->"RESOLVE_SUBJECT";case "APPLICABILITY"->"RESOLVE_APPLICABILITY";
                case "EXPERT_REVIEW"->"REVIEW_QUALITY_GRADE";case "REQUIREMENT_CLARIFICATION"->"CLARIFY_REQUIREMENT_SOURCE";
                case "PROCESSING"->"RETRY_FAILED_STEP";default->"RETRIEVE_EVIDENCE";};
            add(out,strategy,gap.code(),d.id(),gap.description(),base);
        }
        for(Map<String,Object> failure:RuleEvaluator.maps(session.diagnostics().get("failures")))if(!Boolean.TRUE.equals(failure.get("recoveredByRetry"))&&!Boolean.TRUE.equals(failure.get("transportRecovered"))){
            String strategy="EXTRACT".equals(failure.get("stage"))&&Boolean.TRUE.equals(failure.get("splittable"))?"SPLIT_FAILED_REQUEST":Boolean.TRUE.equals(failure.get("retryable"))?"RETRY_FAILED_STEP":"RESTORE_SERVICE";
            add(out,strategy,str(failure,"errorCode"),"",str(failure,"stage"),base);
        }
        return List.copyOf(out);
    }
    private static void add(List<Map<String,Object>> out,String strategy,String code,String id,String need,String base){
        String fp=identity(base+strategy+code+id+need);if(out.stream().anyMatch(row->fp.equals(row.get("inputFingerprint"))))return;
        Map<String,Object> action=linked("strategy",strategy,"reasonCode",code,"gapOwner",RecoveryFailureRouting.owner(code),"obligationId",id,"need",need,"inputFingerprint",fp);
        if(RequirementRepairPolicy.requirementWork(strategy))action.putAll(linked("executionMode","RESTORE_REQUIREMENT_SOURCE".equals(strategy)?"RESTORE_REQUIREMENT_SOURCE":"RECOMPILE_REQUIREMENT",
            "requirementRevisions",RequirementRepairPolicy.versions(),"modelRequestsAllowed",false,"businessProof",false));
        out.add(action);
    }
}
