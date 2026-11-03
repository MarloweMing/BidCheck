package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Closed source-derived type diagnoses. A corrected type is not a completed business proof. */
final class RequirementPrimitiveSemantics {
    static final String UNIQUENESS_KEY="requirementUniqueness";
    private static final Pattern UNIQUE_PRICE=Pattern.compile("(投标总报价|投标报价|总报价)(?:应当|必须|应|须|需|保持|为)?唯一");
    private static final String UNIQUE_ISSUE="PRICE_UNIQUENESS_NOT_AMOUNT：原文要求报价唯一，不是报价金额等于1；须核验同口径报价及完整范围，不能以声明或材料存在替代";
    private static final String COST_ISSUE="COST_THRESHOLD_UNRESOLVED：原文成本未给出可绑定金额，不能将成本门槛补为0；须保留成本依据及适用澄清条件";
    private RequirementPrimitiveSemantics(){}

    private static String uniqueField(String quote){
        String text=Objects.toString(quote,"").replaceAll("\\s+","");
        Matcher m=UNIQUE_PRICE.matcher(text);while(m.find()){
            String prefix=text.substring(0,m.start()).replaceFirst("(?s)^.*[。；;，,]","").replaceFirst("(?s)^.*(?:但是|但|然而)","");
            if(!prefix.matches("(?s).*(?:不要求|无需|不必|不用).{0,16}"))return m.group(1);
        }return "";
    }
    private static boolean uniquePriceField(Rule rule){String field=uniqueField(rule.sourceQuote());return !cn.bidcheck.compat.Java8Api.isBlank(field)&&(field.equals(rule.field())||cn.bidcheck.compat.Java8Api.setOf("price", "amount", "quote").contains(rule.field()));}
    static String issue(Rule rule){
        if(!"NUMBER".equals(rule.predicate()))return null;
        if(uniquePriceField(rule)&&"1".equals(rule.expected())&&cn.bidcheck.compat.Java8Api.setOf("=", "==", "EQ").contains(rule.operator())
                &&cn.bidcheck.compat.Java8Api.setOf("", "个", "份", "项").contains(rule.unit())&&!explicitMonetaryConstraint(rule.sourceQuote()))return UNIQUE_ISSUE;
        String quote=rule.sourceQuote();
        if(quote.matches("(?s).*(?:不低于|不得低于|低于)(?:实际|本次|项目)?成本.*")
                &&"0".equals(rule.expected())&&!quote.matches("(?s).*成本(?:价|金额)?\\s*(?:为|是|[:：])\\s*(?:人民币|CNY)?\\s*0(?:\\.0+)?\\s*(?:亿元|万元|元).*")
                &&!separateZeroAmount(rule))return COST_ISSUE;
        return null;
    }
    /** Another explicitly named zero amount remains its own obligation, never a cost definition. */
    private static boolean separateZeroAmount(Rule rule){
        String field=clean(rule.field()),basis=clean(str(rule.parameters(),"amountBasis"));
        if(field.length()<2||field.length()>40||field.matches("(?s).*(?:报价|成本|总价|投标价).*")
                ||cn.bidcheck.compat.Java8Api.setOf("price", "amount", "quote", "金额", "数额").contains(field.toLowerCase(Locale.ROOT))
                ||!cn.bidcheck.compat.Java8Api.isBlank(basis)&&!basis.equals(field)||!cn.bidcheck.compat.Java8Api.setOf("元", "万元", "亿元").contains(rule.unit()))return false;
        String op;
switch (RequirementGrounding.canonicalOp(rule.operator())) {case "=":op = "(?:为|是|等于|[:：]|=)"; break;case ">=":op = "(?:不少于|不低于|至少|≥|>=)"; break;case "<=":op = "(?:不超过|不得超过|不高于|至多|≤|<=)"; break;default:op = ""; break;}
        if(cn.bidcheck.compat.Java8Api.isBlank(op))return false;
        String text=rule.sourceQuote().replaceAll("[\\t \\u00a0\\u3000]+","");
        return Pattern.compile("(?:^|[。；;，,\\r\\n])(?:本项目|本次|投标人)?"+Pattern.quote(field)
                +"(?:金额|数额)?(?:应当|必须|应|须)?"+op+"(?:人民币|CNY)?0(?:\\.0+)?"+Pattern.quote(rule.unit())+"(?=$|[。；;，,\\r\\n])").matcher(text).find();
    }
    private static boolean explicitMonetaryConstraint(String quote){return quote.matches("(?s).*(?:报价|金额|费用|限价|成本)[^。；;\\n]{0,24}(?:为|是|[:：]|不超过|不得超过|不低于|不少于|等于|至少|至多|≥|≤|>=|<=|=)\\s*(?:人民币|CNY)?\\s*[0-9]+(?:\\.[0-9]+)?\\s*(?:亿元|万元|元).*" );}
    static Rule normalize(Rule rule){
        Map<String,Object> parameters=new LinkedHashMap<>(rule.parameters());parameters.remove(UNIQUENESS_KEY);
        if("SAME_FIELD".equals(rule.predicate())&&uniquePriceField(rule))
            parameters.put(UNIQUENESS_KEY,uniqueness(rule));
        return new Rule(rule.id(),rule.description(),rule.predicate(),rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),parameters,rule.sourceQuote(),rule.logic(),rule.children());
    }
    private static Map<String,Object> uniqueness(Rule rule){return linked("version","price-uniqueness-1","sourceHash",identity(rule.sourceQuote()),"field",rule.field(),"requiresWholeDocument",true,"alternativeOfferScopeUnresolved",true);}
    static boolean verifiedUniqueness(Rule rule){return "SAME_FIELD".equals(rule.predicate())&&uniquePriceField(rule)&&uniqueness(rule).equals(rule.parameters().get(UNIQUENESS_KEY));}

    static boolean allowsCorrection(Rule original,Rule repaired){
        if(!"UNSUPPORTED".equals(original.predicate())||!"NUMBER".equals(str(original.parameters(),"originalPredicate"))
                ||!UNIQUE_ISSUE.equals(str(original.parameters(),"compilationIssue")))return false;
        Rule numeric=new Rule(original.id(),original.description(),"NUMBER",original.subject(),original.field(),original.operator(),original.expected(),original.unit(),original.parameters(),original.sourceQuote(),original.logic(),original.children());
        if(!UNIQUE_ISSUE.equals(issue(numeric))||original.sourceQuote().matches("(?s).*(?:备选|备择|替代方案|多个报价|两(?:个|份)以上).*")||!verifiedUniqueness(repaired)||!original.children().isEmpty()||!repaired.children().isEmpty()
                ||!original.id().equals(repaired.id())||!original.sourceQuote().equals(repaired.sourceQuote())
                ||!original.subject().equals(repaired.subject())||!original.field().equals(repaired.field())
                ||!original.logic().equals(repaired.logic())||!cn.bidcheck.compat.Java8Api.isBlank(repaired.expected())||!cn.bidcheck.compat.Java8Api.isBlank(repaired.operator())||!cn.bidcheck.compat.Java8Api.isBlank(repaired.unit()))return false;
        for(String key:cn.bidcheck.compat.Java8Api.listOf("lot", "currency", "amountBasis", "taxBasis", "employer"))
            if(!str(original.parameters(),key).equals(str(repaired.parameters(),key)))return false;
        return RequirementGrounding.issue(repaired,repaired.sourceQuote())==null;
    }
    static Map<String,Object> diagnostic(Rule rule){
        if(!"UNSUPPORTED".equals(rule.predicate()))return cn.bidcheck.compat.Java8Api.mapOf();
        String issue=str(rule.parameters(),"compilationIssue");
        if(UNIQUE_ISSUE.equals(issue))return linked("kind","SOURCE_BOUND_PRIMITIVE_REPAIR","allowedPredicates",rule.sourceQuote().matches("(?s).*(?:备选|备择|替代方案|多个报价|两(?:个|份)以上).*")?cn.bidcheck.compat.Java8Api.listOf("UNSUPPORTED"):cn.bidcheck.compat.Java8Api.listOf("SAME_FIELD", "UNSUPPORTED"),"requiredField",rule.field(),"clearFabricatedThreshold",true,"wholeScopeProofStillRequired",true);
        if(COST_ISSUE.equals(issue))return linked("kind","SOURCE_BOUND_COST_REQUIREMENT","allowedPredicates",cn.bidcheck.compat.Java8Api.listOf("NUMBER", "UNSUPPORTED"),"fabricatedZeroForbidden",true,"missingCostNotAssumedZero",true);
        return cn.bidcheck.compat.Java8Api.mapOf();
    }
}
