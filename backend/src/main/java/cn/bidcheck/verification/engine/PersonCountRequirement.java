package cn.bidcheck.verification.engine;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A role count is not money. Bare staffing quantities fill role slots without inventing an upper bound. */
final class PersonCountRequirement {
    private static final String COMPARATOR="不少于|不低于|至少|不超过|不得超过|不高于|至多|最多|超过|大于|少于|小于|恰好|正好|等于|仅|只|为|≥|≤|>=|<=|=|>|<";
    private static final String AMOUNT="[0-9]+|[零一二三四五六七八九十百两]+";
    private static final Pattern COUNT=Pattern.compile("("+RequirementContextBindings.ROLES+")\\s*(?:人数|数量|要求)?\\s*[:：]?\\s*(?:应当|应|须|需)?\\s*(?:配备|有)?\\s*("+COMPARATOR+")?\\s*("+AMOUNT+")\\s*(人|名)(?!次)");
    private static final Pattern REVERSE=Pattern.compile("("+COMPARATOR+")\\s*("+AMOUNT+")\\s*(人|名)\\s*("+RequirementContextBindings.ROLES+")");
    private static final Pattern TRAILING=Pattern.compile("^[\\s，,]*(?:且|并且|同时)?\\s*(?:人数|数量|配置人数|实际人数)?\\s*(?:应当|应|须|需)?\\s*("+COMPARATOR+")\\s*("+AMOUNT+")\\s*(人|名)(?!次)");
    record Match(String role,String number,String unit,String operator,int start,int end) { }
    static List<Match> matches(String source){List<Match> out=new ArrayList<>();Matcher m=COUNT.matcher(source);while(m.find()){BigDecimal count=RequirementGrounding.number(m.group(3));if(count!=null){out.add(new Match(m.group(1),count.toPlainString(),m.group(4),m.group(2)==null?"":RequirementGrounding.operator(m.group(2)),m.start(),m.end()));
            Matcher trailing=TRAILING.matcher(source.substring(m.end()));if(trailing.find()){BigDecimal next=RequirementGrounding.number(trailing.group(2));if(next!=null)out.add(new Match(m.group(1),next.toPlainString(),trailing.group(3),RequirementGrounding.operator(trailing.group(1)),m.start(),m.end()+trailing.end()));}
        }}
        m=REVERSE.matcher(source);while(m.find()){BigDecimal count=RequirementGrounding.number(m.group(2));if(count!=null)out.add(new Match(m.group(4),count.toPlainString(),m.group(3),RequirementGrounding.operator(m.group(1)),m.start(),m.end()));}return out;}
    static Rule normalize(Rule rule,RequirementContextBindings bindings){
        Map<String,Object> p=new LinkedHashMap<>(rule.parameters());p.remove("countComparisonBasis");p.remove("countSourceQuote");p.remove("countConstraintSource");
        rule=new Rule(rule.id(),rule.description(),rule.predicate(),rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),p,rule.sourceQuote(),rule.logic(),rule.children());
        if(!Set.of("NUMBER","PERSON_COUNT").contains(rule.predicate())||!rule.children().isEmpty())return rule;
        String search=rule.sourceQuote();int base=RequirementContextBindings.uniqueOffset(bindings.source(),search);
        List<Match> matches=matching(rule,search);
        if(matches.isEmpty()){
            Map<?,?> context=RequirementContextBindings.validatedReference(rule,"subject",rule.subject(),bindings.source());
            if(context!=null){search=Objects.toString(context.get("sourceQuote"),"");base=((Number)context.get("start")).intValue();
                String quote=rule.sourceQuote();String scope=search;
                matches=matching(rule,search).stream().filter(m->scope.substring(m.start(),m.end()).contains(quote.strip().replaceAll("[。；;]$",""))).toList();}
        }
        if(matches.size()>1){String comparison=RequirementGrounding.canonicalOp(rule.operator());List<Match> explicit=matches.stream().filter(m->!m.operator().isBlank()&&m.operator().equals(comparison)).toList();if(explicit.size()==1)matches=explicit;}
        if(matches.size()!=1)return rule;Match m=matches.get(0);
        p.put("countComparisonBasis",m.operator().isBlank()?"ROLE_CONFIGURATION":"EXPLICIT_COMPARATOR");
        p.put("countSourceQuote",search.substring(m.start(),m.end()));
        if(base>=0)p.put("countConstraintSource",bindings.reference(m.number(),search.substring(m.start(),m.end()),base+m.start(),"LOCAL",""));
        return new Rule(rule.id(),rule.description(),"PERSON_COUNT",rule.subject(),rule.field(),m.operator(),m.number(),"人",p,rule.sourceQuote(),rule.logic(),rule.children());
    }
    static String issue(Rule rule){
        String op=sourceComparison(rule);if(op==null)return "岗位、人数与原单位未作为同一要求绑定";
        if(!op.equals(rule.operator()))return "人数比较词未对应原文，裸人数不能改成至少或恰好";
        if(op.isBlank()&&!roleConfiguration(rule))return "岗位数量包含否定、参考或独立后置约束，须分别保留原文义务，不能仅按正向岗位配置核验";
        return null;
    }
    /** Returns null when ungrounded, empty for a bare count, otherwise the original comparator. */
    static String sourceComparison(Rule rule){
        if(!Set.of("人","名").contains(rule.unit()))return null;
        List<Match> matches=matching(rule,rule.sourceQuote());
        if(matches.isEmpty()&&rule.parameters().get("countConstraintSource") instanceof Map<?,?> count
            &&rule.parameters().get("requirementBindings") instanceof Map<?,?> refs&&refs.get("subject") instanceof Map<?,?> context
            &&count.get("start") instanceof Number a&&count.get("end") instanceof Number b&&context.get("start") instanceof Number c){
            String scope=Objects.toString(context.get("sourceQuote"),"");int start=a.intValue()-c.intValue(),end=b.intValue()-c.intValue();
            if(start>=0&&end>start&&end<=scope.length()&&scope.substring(start,end).equals(count.get("sourceQuote"))&&scope.contains(rule.subject()))matches=matching(rule,scope.substring(start,end));
        }
        if(matches.size()>1)matches=matches.stream().filter(m->m.operator().equals(rule.operator())).toList();
        return matches.size()==1?matches.get(0).operator():null;
    }
    /** This is recomputed from original words; caller/model metadata cannot authorize it. */
    static boolean roleConfiguration(Rule rule){
        if(!rule.operator().isBlank()||!"".equals(sourceComparison(rule)))return false;
        String role=Pattern.quote(rule.subject()),quote=rule.sourceQuote();
        if(quote.matches("(?s).*(?:示例|例如|假设|参考配置|原有|原方案|现有|历史|已完成|可选|可配|无需|不需|不要求|不再要求|不设置|不配备|不得设置|不应配备|不得配备|禁止设置|禁止配备|取消)[^。；;\\n]{0,25}"+role+".*")
            ||quote.matches("(?s).*"+role+"[^，,。；;\\n]{0,30}(?:仅作参考|仅供参考|不作要求|不是本次|非本次).*"))return false;
        return matches(quote).stream().noneMatch(m->m.role().equals(rule.subject())&&!m.operator().isBlank());
    }
    private static List<Match> matching(Rule rule,String text){return matches(text).stream().filter(m->m.role().equals(rule.subject())&&sameCount(m.number(),rule.expected())).toList();}
    private static boolean sameCount(String a,String b){BigDecimal x=RequirementGrounding.number(a),y=RequirementGrounding.number(b);return x!=null&&y!=null&&x.compareTo(y)==0;}
}
