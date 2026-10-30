package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Structural guard against dropping explicit separately enumerated obligations during compilation. */
final class RequirementCoverage {
    static List<String> issues(String requirement,List<Rule> rules){List<String> issues=new ArrayList<>();List<Rule> leaves=RequirementCompiler.leaves(rules);
        // These source-closed executors expand their own obligations at evaluation time.
        // The exception requires complete source reconstruction, never a generic predicate tag.
        if(leaves.size()==1){Rule complete=leaves.get(0);
            if(SourceScoringRules.compilationComplete(complete,requirement)
                    ||SourceSignatureRequirements.compilationComplete(complete,requirement)
                    ||RequirementBusinessRouting.compilationComplete(complete,requirement)
                    ||LegalRegistrationProof.compilationComplete(complete,requirement))return List.of();
        }
        if(!leaves.isEmpty()&&leaves.stream().allMatch(r->Set.of("REFERENCE_TIME","INPUT").contains(r.predicate()))
            &&requirement.matches("(?s).*(?:证书|证照|许可证|合格证|承诺|须.{0,12}(?:提供|提交|具备)|(?:提供|提交).{0,12}(?:材料|文件|证明)).*"))
            issues.add("仅编译了参考时点或输入条件，尚未保留材料、资格等实际业务义务");
        List<String> clauses=Arrays.stream(requirement.split("[；;\\n]+|并且|且(?=具备|具有|提供|提交|持有|须|应|需)|以及(?=具备|具有|提供|提交|持有|须|应|需)")) .map(String::strip).filter(s->s.length()>=4).filter(s->!s.matches("^(?:注[:：]|说明[:：]|提示[:：]).*")).toList();
        List<String> obligations=new ArrayList<>();for(int i=0;i<clauses.size();i++)if(!structural(clauses,i)&&!sourceChoiceHeader(clauses.get(i),requirement,rules))obligations.add(clauses.get(i));
        for(String clause:obligations){if(leaves.stream().noneMatch(r->quotes(r,requirement).stream().anyMatch(q->q.contains(clause)||clause.contains(q)&&q.length()>=6)))issues.add("仍有独立条款未映射至可执行义务："+clause.substring(0,Math.min(100,clause.length())));}
        if(obligations.size()>1&&leaves.size()==1)issues.add("多个独立要求尚未分别映射到原子条件，不能缩小核验范围");
        for(PersonCountRequirement.Match count:PersonCountRequirement.matches(requirement)){
            boolean represented=leaves.stream().anyMatch(r->r.predicate().equals("PERSON_COUNT")&&r.subject().equals(count.role())&&r.expected().equals(count.number())&&r.operator().equals(count.operator())&&PersonCountRequirement.issue(r)==null);
            if(!represented)issues.add("岗位人数要求尚未单独映射："+requirement.substring(count.start(),count.end()));
        }
        return issues;
    }
    /** A heading is structural only when its complete, source-derived choice tree is retained.
     * An ANY keyword alone never exempts a missing branch or a changed actor/threshold. */
    private static boolean sourceChoiceHeader(String clause,String requirement,List<Rule> rules){
        var parsed=QualificationRequirementTree.parse(requirement);if(parsed.isEmpty())return false;
        var tree=parsed.get();if(!clause.strip().equals(tree.heading().strip()))return false;
        for(Rule choice:CompilationRecovery.flatten(rules)){
            if(!choice.predicate().equals("GROUP")||!choice.logic().equals("ANY")||!choice.subject().equals(tree.subject())
                    ||!choice.sourceQuote().equals(tree.choiceQuote())||choice.children().size()!=tree.branches().size())continue;
            boolean complete=true;
            for(int i=0;i<tree.branches().size();i++){
                var branch=tree.branches().get(i);Rule child=choice.children().get(i);
                if(!child.sourceQuote().equals(branch.quote())||!child.subject().equals(tree.subject())||!child.children().isEmpty()){complete=false;break;}
                if(branch.unresolved().isBlank()){
                    if(!child.predicate().equals("CERTIFICATE")||!str(child.parameters(),"category").equals(branch.category())
                            ||!str(child.parameters(),"grade").equals(branch.grade())||!str(child.parameters(),"gradeOperator").equals(branch.gradeOperator())){complete=false;break;}
                }else if(!child.predicate().equals("UNSUPPORTED")||!str(child.parameters(),"deterministicFamily").equals(branch.unresolved())){complete=false;break;}
            }
            if(complete)return true;
        }
        return false;
    }
    private static boolean structural(List<String> clauses,int index){
        if(index+1>=clauses.size())return false;String clause=clauses.get(index),next=clauses.get(index+1);
        java.util.regex.Matcher heading=java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+){0,5})\\s+([^\\s，。；：:]{2,18}(?:评审|评标|审查|标准|办法|要求|规则|条件))$").matcher(clause);
        if(heading.matches()&&!clause.matches(".*(?:须|应|不得|必须|提供|提交|不少于|不超过|至少|以上|以下).*")&&next.matches("^"+java.util.regex.Pattern.quote(heading.group(1))+"\\.\\d+.*"))return true;
        return clause.matches("^(?:\\d+(?:\\.\\d+)*\\s+)?投标人有以下情形之一的[，,]评标委员会应当否决其投标[:：]$")&&next.matches("^[（(]\\d+[）)].*");
    }
    private static List<String> quotes(Rule rule,String source){List<String> out=new ArrayList<>();out.add(rule.sourceQuote());
        // Context used only for a subject must not count as coverage of every obligation in that context.
        if(PriceRequirementBinding.bound(rule,source)&&rule.parameters().get("priceConstraintSources") instanceof Map<?,?> refs){
            for(String key:List.of("definition","comparison"))if(refs.get(key) instanceof Map<?,?> ref)out.add(Objects.toString(ref.get("sourceQuote"),""));
        }return out;
    }
}
