package cn.bidcheck.verification.engine;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Organization counts use a complete, current consortium composition, not a personnel roster. */
final class EntityCountRequirement {
    private static final Pattern COUNT=Pattern.compile("联合体成员(?:单位)?(?:[（(]含牵头单位[）)])?\\s*(不得超过|不超过|至多|最多|不少于|至少|为|等于)\\s*([0-9]+|[一二三四五六七八九十两]+)\\s*家");
    private EntityCountRequirement(){}
    static Rule normalize(Rule rule){
        if(!cn.bidcheck.compat.Java8Api.setOf("PERSON_COUNT", "NUMBER", "ENTITY_COUNT").contains(rule.predicate()))return rule;
        if(!rule.children().isEmpty()||!DomainBindings.memberSelector(rule.subject()))return rule;
        Matcher match=COUNT.matcher(rule.sourceQuote());if(!match.find())return rule;
        String op=RequirementGrounding.operator(match.group(1)),count=RequirementGrounding.number(match.group(2)).toPlainString();
        boolean standalone=clean(rule.sourceQuote()).replaceAll("[。；;]$","").equals(clean(match.group()));
        if(!standalone&&(!"家".equals(rule.unit())||ProofVerifier.decimal(rule.expected())==null||ProofVerifier.decimal(rule.expected()).compareTo(new BigDecimal(count))!=0))return rule;
        if(match.find())return rule;
        return new Rule(rule.id(),rule.description(),"ENTITY_COUNT","联合体成员",rule.field(),op,count,"家",rule.parameters(),rule.sourceQuote(),rule.logic(),rule.children());
    }
    static String issue(Rule rule){
        Matcher m=COUNT.matcher(rule.sourceQuote());if(!m.find())return "组织数量约束未对应完整的联合体成员、比较符、数量和家数单位";
        BigDecimal expected=ProofVerifier.decimal(rule.expected());
        if(expected==null||expected.compareTo(RequirementGrounding.number(m.group(2)))!=0||!RequirementGrounding.operator(m.group(1)).equals(RequirementGrounding.canonicalOp(rule.operator()))||!rule.unit().equals("家")||m.find())return "组织数量比较条件与原文不一致";
        return null;
    }
    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        String issue=issue(rule);if(issue!=null)return evaluator.unknown(rule,"ENTITY_COUNT_REQUIREMENT_UNRESOLVED",issue,cn.bidcheck.compat.Java8Api.listOf());
        List<Fact> links=DomainBindings.roleLinks("联合体成员",evaluator.facts,evaluator.corpus);
        List<String> members=DomainBindings.subjects("联合体成员",evaluator.facts,evaluator.corpus);
        List<Map<String,Object>> complete=DomainBindings.completeMemberSet("联合体成员",links,members,evaluator.corpus);
        if(complete.isEmpty())return evaluator.unknown(rule,"SUBJECT_SET_INCOMPLETE","尚未核到当前联合体协议逐名列举的完整成员，不能按部分检索结果判断成员数量",links);
        int cmp=BigDecimal.valueOf(members.size()).compareTo(ProofVerifier.decimal(rule.expected()));
        boolean pass;
switch (RequirementGrounding.canonicalOp(rule.operator())) {case "<=":pass = cmp<=0; break;case ">=":pass = cmp>=0; break;case "=":pass = cmp==0; break;case "<":pass = cmp<0; break;case ">":pass = cmp>0; break;default:pass = false; break;}
        Map<String,Object> proof=linked("members",members,"count",members.size(),"completeMemberSet",complete,"unit","家");
        return pass?evaluator.pass(rule,"完整列明的当前联合体成员数量符合条款",links,proof):evaluator.fail(rule,"完整列明的当前联合体成员数量与条款不符",links,proof);
    }
}
