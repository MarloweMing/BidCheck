package cn.bidcheck.verification.engine;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.*;

/** Parameters retain the threshold, comparator and original unit; quoting a paragraph alone is insufficient. */
final class RequirementGrounding {
    private static final String NUMBER="([0-9]+(?:[.][0-9]+)?|[零一二三四五六七八九十百两]+)";
    static String issue(Rule rule){return issue(rule,rule.sourceQuote());}
    static String issue(Rule rule,String original){return issue(rule,original,false);}
    static String issue(Rule rule,String original,boolean confirmedNumeric){String quote=rule.sourceQuote();Map<String,Object> p=rule.parameters();
        if(rule.predicate().equals("ENTITY_COUNT"))return EntityCountRequirement.issue(rule);
        if(rule.predicate().equals("PERSON_COUNT")){String countIssue=PersonCountRequirement.issue(rule);if(countIssue!=null)return countIssue;}
        if(rule.predicate().equals("NUMBER")){
            if(quote.contains("不含税")&&!str(p,"taxBasis").equals("不含税"))return "数值比较的明确不含税口径未保留";
            if(!quote.contains("不含税")&&quote.contains("含税")&&!str(p,"taxBasis").equals("含税"))return "数值比较的明确含税口径未保留";
            BigDecimal expected=decimal(rule.expected());boolean supported=false;Matcher m=Pattern.compile("(不少于|不低于|至少|最低|不超过|不得超过|至多|最多|不高于|最高|大于|超过|小于|低于|等于|为|≥|≤|>=|<=|=|>|<)\\s*"+NUMBER+"\\s*(亿元|万元|元|个月|月|年|天|日|项|个)?").matcher(quote);
            while(m.find()){BigDecimal amount=number(m.group(2));String unit=Objects.toString(m.group(3),"");if(expected!=null&&amount!=null&&expected.compareTo(amount)==0&&unitEquals(unit,rule.unit())&&operator(m.group(1)).equals(canonicalOp(rule.operator())))supported=true;}
            if(!supported&&!confirmedNumeric&&!PriceRequirementBinding.bound(rule,original))return "比较符、门槛和原单位未作为同一约束绑定到条款";
        }
        if(rule.predicate().equals("MONTHS")){
            int count=RunSession.number(p.get("count"),0);boolean supported=false;Matcher m=Pattern.compile("连续\\s*"+NUMBER+"\\s*个?月").matcher(quote);while(m.find())if(number(m.group(1))!=null&&number(m.group(1)).compareTo(BigDecimal.valueOf(count))==0)supported=true;
            if(!supported)return "连续月份数量未对应原条款";String earliest=str(p,"earliestMonth");if(!cn.bidcheck.compat.Java8Api.isBlank(earliest)&&!monthPresent(quote,earliest))return "社保窗口起点未对应原条款";
            if(quote.matches("(?s).*(?:起|以来|之后).*" )&&cn.bidcheck.compat.Java8Api.isBlank(earliest))return "社保窗口起点未保留";
            if(quote.matches("(?s).*(?:投标截止|递交截止|截至).*" )&&!Boolean.TRUE.equals(p.get("requiresReferenceDate")))return "社保窗口截止基准未保留";
        }
        for(String key:cn.bidcheck.compat.Java8Api.listOf("category", "grade", "profession", "personRole", "amountBasis", "taxBasis")){String value=str(p,key);if(!cn.bidcheck.compat.Java8Api.isBlank(value)&&!contains(quote,value)&&RequirementContextBindings.validatedReference(rule,key,value,original)==null)return "条件参数未对应原条款："+key;}
        if(!cn.bidcheck.compat.Java8Api.isBlank(str(p,"sinceDate"))&&!datePresent(quote,str(p,"sinceDate")))return "合同日期下限未对应原条款";
        if(rule.predicate().equals("CERTIFICATE")){
            if(PersonnelCredentialProof.applies(rule)&&!PersonnelCredentialProof.sourceGradeBound(rule))return "人员证照类别或等级字段未对应原条款";
            Matcher grade=Pattern.compile("(?:[一二三四五六七八九十甲乙丙丁]+级)").matcher(quote);if(grade.find()&&cn.bidcheck.compat.Java8Api.isBlank(str(p,"grade"))&&!PersonnelCredentialProof.sourceGradeBound(rule))return "证照等级义务未保留";
        }
        if(rule.predicate().equals("CONTRACT")){
            String basis=str(p,"amountBasis");for(String required:cn.bidcheck.compat.Java8Api.listOf("建安工程费", "建安费", "施工费", "设计费"))if(quote.contains(required)&&!basis.contains(required))return "合同金额组成口径未保留";
            if(quote.contains("不含税")&&!str(p,"taxBasis").equals("不含税"))return "合同税基义务未保留";
            if(p.containsKey("amountMinimum")){Rule numeric=new Rule(rule.id(),rule.description(),"NUMBER",rule.subject(),rule.field(),">=",str(p,"amountMinimum"),rule.unit(),p,quote,"",cn.bidcheck.compat.Java8Api.listOf());String bound=issue(numeric);if(bound!=null)return "合同最低金额："+bound;}
        }
        return null;
    }
    static boolean completeSubjects(String quote,List<String> subjects){if(subjects.isEmpty())return false;String list="";Matcher explicit=Pattern.compile("(?:包括|分别为|为|[:：])\\s*([^；。\\n]+?(?:、|，|,)[^；。\\n]+?)(?:均|各|分别|全部|须|应|$)").matcher(quote);if(explicit.find())list=explicit.group(1);else {Matcher plain=Pattern.compile("^([^；。\\n]+?(?:、|，|,)[^；。\\n]+?)(?:均|各|分别|全部|须|应)").matcher(quote);if(plain.find())list=plain.group(1);}if(cn.bidcheck.compat.Java8Api.isBlank(list))return false;Set<String> expected=new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.toList(Arrays.stream(list.split("[、，,]|及|和")).map(cn.bidcheck.compat.Java8Api::strip).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s))));return expected.equals(new LinkedHashSet<>(subjects));}
    static BigDecimal number(String value){BigDecimal decimal=decimal(value);if(decimal!=null)return decimal;Map<Character,Integer> digits=cn.bidcheck.compat.Java8Api.mapOf('零', 0, '一', 1, '二', 2, '三', 3, '四', 4, '五', 5, '六', 6, '七', 7, '八', 8, '九', 9);int sum=0,current=0;for(char c:value.toCharArray()){if(c=='两'){current=2;continue;}if(digits.containsKey(c)){current=digits.get(c);continue;}if(c=='十'||c=='百'){sum+=(current==0?1:current)*(c=='十'?10:100);current=0;}else return null;}return BigDecimal.valueOf(sum+current);}
    static String canonicalOp(String op){switch (op) {case "GE":case ">=":return ">=";case "LE":case "<=":return "<=";case "EQ":case "==":case "=":return "=";case "GT":case ">":return ">";case "LT":case "<":return "<";default:return "?";}}
    static String operator(String phrase){switch (phrase) {case "不少于":case "不低于":case "至少":case "最低":case "≥":case ">=":return ">=";case "不超过":case "不得超过":case "至多":case "最多":case "不高于":case "最高":case "≤":case "<=":return "<=";case "大于":case "超过":case ">":return ">";case "小于":case "少于":case "低于":case "<":return "<";default:return "=";}}
    static boolean unitEquals(String a,String b){return canonicalUnit(a).equals(canonicalUnit(b));}
    static String canonicalUnit(String unit){switch (unit) {case "CNY":return "元";case "DAY":case "日":return "天";case "MONTH":case "个月":return "月";case "YEAR":return "年";default:return unit;}}
}
