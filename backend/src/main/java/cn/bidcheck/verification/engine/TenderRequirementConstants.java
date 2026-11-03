package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A limit definition is tender input, not a demand that the bidder repeat that amount. */
final class TenderRequirementConstants {
    private static final Pattern LIMIT=Pattern.compile("最高投标限价(?:为|[:：])\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(亿元|万元|元)(?:人民币)?(?:[（(](不含税|含税)[）)])?");
    private static final Pattern PART=Pattern.compile("(设计费|建安费|暂列金)(?:为|[:：])\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(亿元|万元|元)");
    /** A closed source grammar, not a repair of model-generated or abbreviated quotations. */
    static Rule sourcePriceRule(Checkpoint cp,String source){
        boolean sourceBound=cp.source()!=null&&Objects.equals(cp.source().quote(),source);
        // Some reviewed checkpoints retain the complete tender clause in expected instead of a
        // pre-parsed scalar.  That is still a frozen, exact source binding: derive the ceiling from
        // the closed grammar below rather than asking a model to author the executable rule.
        boolean reviewedClause=cp.source()==null&&Objects.equals(cp.expected(),source);
        if(!"VALUE_COMPARE".equals(cp.type())||!"<=".equals(cp.operator())||!cp.confirmed()
            ||(!sourceBound&&!reviewedClause)||!cn.bidcheck.compat.Java8Api.setOf("totalPrice", "price", "投标报价", "投标总报价", "含税投标总报价", "不含税投标总报价").contains(cp.field()))return null;
        List<Map<String,Object>> constants=extract(source);if(constants.isEmpty())return null;
        Map<String,Object> ceiling=constants.stream().filter(row->"最高投标限价".equals(str(row,"name"))).findFirst().orElse(null);
        if(ceiling==null)return null;
        String reviewedExpected=reviewedClause?str(ceiling,"value"):cp.expected();
        String reviewedUnit=reviewedClause?str(ceiling,"unit"):cp.unit();
        Rule candidate=new Rule("SOURCE_PRICE_1",cp.title(),"NUMBER","",cp.field(),cp.operator(),reviewedExpected,reviewedUnit,cn.bidcheck.compat.Java8Api.mapOf(),source,"",cn.bidcheck.compat.Java8Api.listOf());
        RequirementContextBindings bindings=new RequirementContextBindings(source,"ORIGINAL_REQUIREMENT");
        Rule bound=PriceRequirementBinding.resolve(candidate,bindings);
        if(!PriceRequirementBinding.bound(bound,source)||!bound.operator().equals(cp.operator())||!sameNumber(bound.expected(),reviewedExpected))return null;
        String currency=str(bound.parameters(),"currency"),tax=str(bound.parameters(),"taxBasis");
        String reviewedTax=cp.field().startsWith("不含税")?"不含税":cp.field().startsWith("含税")?"含税":"";
        if(!cn.bidcheck.compat.Java8Api.isBlank(reviewedTax)&&!reviewedTax.equals(tax))return null;
        boolean cny=cn.bidcheck.compat.Java8Api.setOf("人民币", "CNY", "RMB").contains(currency);
        boolean matchingUnit=Objects.equals(cp.unit(),bound.unit())||"元".equals(bound.unit())&&cny&&(java.util.Objects.equals((Objects.toString(cp.unit(),"")), "CNY_EXCLUSIVE") ? tax.equals("不含税") : (java.util.Objects.equals((Objects.toString(cp.unit(),"")), "CNY_INCLUSIVE") ? tax.equals("含税") : (java.util.Objects.equals((Objects.toString(cp.unit(),"")), "CNY") ? true : false)));
        if(!matchingUnit)return null;
        Map<?,?> proof=(Map<?,?>)bound.parameters().get("priceConstraintSources");
        Map<?,?> definition=(Map<?,?>)proof.get("definition"),comparison=(Map<?,?>)proof.get("comparison");
        // Nothing except the single definition, its optional component breakdown and one
        // direct comparison may remain. Explicit component duties, ANY/IF, other subjects,
        // additional sums, caps or exceptions therefore keep the normal compiler path.
        boolean[] covered=new boolean[source.length()];
        for(Map<?,?> span:cn.bidcheck.compat.Java8Api.listOf(definition, comparison))cover(covered,((Number)span.get("start")).intValue(),((Number)span.get("end")).intValue());
        for(Map<String,Object> constant:constants)cover(covered,((Number)constant.get("start")).intValue(),((Number)constant.get("end")).intValue());
        StringBuilder remaining=new StringBuilder();for(int i=0;i<source.length();i++)if(!covered[i])remaining.append(source.charAt(i));
        String residual=remaining.toString().replaceFirst("^[★*\\s]*(?:本项目)?设置最高(?:投标)?限价[，,]","");
        if(constants.size()>1)residual=residual.replaceFirst("其中","");
        if(!residual.matches("[★*\\s，,。；;（）()]*"))return null;
        Map<String,Object> parameters=new LinkedHashMap<>(bound.parameters());
        parameters.put("sourceCompilation",linked("kind","TYPED_SINGLE_PRICE_CONSTRAINT","sourceHash",identity(source),"originalCheckpointField",cp.field(),"originalCheckpointUnit",cp.unit(),"constantCount",constants.size(),"uncoveredText","","derivedFromReviewedClause",reviewedClause));
        Rule rule=new Rule(bound.id(),bound.description(),bound.predicate(),bound.subject(),str(parameters,"amountBasis"),bound.operator(),bound.expected(),bound.unit(),parameters,source,"",cn.bidcheck.compat.Java8Api.listOf());
        return RequirementGrounding.issue(rule,source)==null?rule:null;
    }
    private static void cover(boolean[] covered,int start,int end){for(int i=Math.max(0,start);i<Math.min(end,covered.length);i++)covered[i]=true;}
    static List<Map<String,Object>> extract(String original){
        Matcher limit=LIMIT.matcher(original);if(!limit.find())return cn.bidcheck.compat.Java8Api.listOf();int start=limit.start(),end=original.indexOf('。',limit.end());if(end<0)end=original.length();
        if(LIMIT.matcher(original.substring(limit.end())).find())return cn.bidcheck.compat.Java8Api.listOf();
        List<Map<String,Object>> constants=new ArrayList<>();constants.add(constant("最高投标限价",limit.group(1),limit.group(2),original,start,limit.end()));
        String rest=original.substring(limit.end(),end);if(!rest.contains("其中"))return cn.bidcheck.compat.Java8Api.listCopyOf(constants);
        Matcher part=PART.matcher(rest);while(part.find()){
            String preceding=rest.substring(0,part.start());if(preceding.matches("(?s).*(?:须|应|必须|不得|不超过|不低于|投标报价|报价).*")||explicitObligation(part.group(1),original))continue;
            constants.add(constant(part.group(1),part.group(2),part.group(3),original,limit.end()+part.start(),limit.end()+part.end()));}
        return cn.bidcheck.compat.Java8Api.listCopyOf(constants);
    }
    private static Map<String,Object> constant(String name,String value,String unit,String source,int start,int end){return linked("kind","TENDER_CONSTANT","name",name,"value",value,"unit",unit,"sourceQuote",source.substring(start,end),"start",start,"end",end,"coordinateSpace","REQUIREMENT_TEXT","sourceHash",identity(source));}
    /** Apply only after the compiler has validated the node's ID, shape and verbatim source quote. */
    static boolean isDefinition(Rule rule,List<Map<String,Object>> constants){
        // A fabricated <= is still a definition when the source only defines a component.
        // Never let the model's choice of operator turn a tender input into a bidder duty.
        return rule.children().isEmpty()&&rule.predicate().equals("NUMBER")&&!explicitObligation(rule)
            &&constants.stream().anyMatch(c->str(c,"name").equals(rule.field())&&str(c,"unit").equals(rule.unit())&&sameNumber(str(c,"value"),rule.expected())
                &&!cn.bidcheck.compat.Java8Api.isBlank(str(c,"sourceQuote"))&&rule.sourceQuote().contains(str(c,"sourceQuote")));
    }
    static List<Rule> businessRules(List<Rule> rules,String logic,List<Map<String,Object>> constants){
        if(!"ALL".equals(logic)||constants.isEmpty())return rules;List<Rule> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Rule rule:rules){
            if(isDefinition(rule,constants))continue;
            Rule normalized=rule;if(rule.predicate().equals("NUMBER")&&rule.parameters().get("priceConstraintSources") instanceof Map<?,?>&&!cn.bidcheck.compat.Java8Api.isBlank(str(rule.parameters(),"amountBasis"))){
                normalized=new Rule(rule.id(),rule.description(),rule.predicate(),rule.subject(),str(rule.parameters(),"amountBasis"),rule.operator(),rule.expected(),rule.unit(),rule.parameters(),rule.sourceQuote(),rule.logic(),rule.children());
            }
            // Only identical, source-grounded price comparisons in an ALL group are redundant.
            if(normalized.children().isEmpty()&&normalized.parameters().containsKey("priceConstraintSources")){
                String key=normalized.subject()+"|"+normalized.field()+"|"+normalized.operator()+"|"+normalized.expected()+"|"+normalized.unit()+"|"+normalized.sourceQuote()+"|"+str(normalized.parameters(),"taxBasis")+"|"+str(normalized.parameters(),"currency");
                if(!seen.add(key))continue;
            }out.add(normalized);
        }return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }
    private static boolean sameNumber(String a,String b){try{return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b))==0;}catch(Exception ignored){return false;}}
    private static boolean explicitObligation(Rule rule){return explicitObligation(rule.field(),rule.sourceQuote());}
    private static boolean explicitObligation(String field,String source){return Arrays.stream(source.split("[，,。；;\\n]"))
        .anyMatch(clause->(clause.contains(field)||clause.matches("(?s).*(?:各分项|各项费用|各部分费用|上述各项|上述金额|上述费用|以上金额|上述分项).*"))
            &&clause.replace(field,"").matches("(?s).*(?:须|应|必须|不得|不能|不超过|不低于|不高于|不少于|最多|至少|高于|低于|超过|上限|下限|限价|限额|投标报价|报价).*"));}
}
