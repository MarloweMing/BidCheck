package cn.bidcheck.verification;

import cn.bidcheck.domain.Domain.*;
import java.math.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** One explicitly recognized procurement formula. Never executes supplied expressions or substitutes historical prices. */
public final class PricingVerification {
    private static final MathContext MC=new MathContext(50,RoundingMode.HALF_EVEN);
    static final String TEMPLATE="本项目采用：均值下浮法（下浮比例{discount}%）; / 本项目采用经算术修正（如需）后的“不含税投标价”为评标价B。当有效投标人大于或等于{threshold}家时，将所有有效评标价去掉一个最高价和一个最低价（如有效投标人小于{below}家时则不去掉），将剩余所有有效评标价的下浮后的算术平均值(即算术平均值的{factor}%)设为基准价A。 / 各投标人的评标价B按每高于基准价1%，价格得分减{upper}分（按比例取差值），即 / 价格得分={maxHigh}-（B-A）÷A×100×{upperFormula}； / 各投标人的评标价B按每低于基准价1%，价格得分减{lower}分（按比例取差值），即 / 价格得分={maxLow}-（A-B）÷A×100×{lowerFormula}； / 按以上方法得出各投标人的价格得分。 / 注：报价得分低于0分按0分计。结果四舍五入取两位小数。";
    private static final String POLICY="本项属于评分条件，不能将未获取可得分材料自动当成资格否决。只依据实际材料说明满足情况、缺项及不确定性，不能仅凭索引“满足”给满分；专业评价及最终分数由评审确认。";
    private static final String MISSING_POLICY="当前报价分册、全部有效评标价和算术修正信息未提供，必须UNDETERMINED，不输出确定价格分，不用历史合同金额替代。";
    private static final List<String> PARAMETERS=List.of("discount","threshold","below","factor","upper","maxHigh","upperFormula","lower","maxLow","lowerFormula");
    private static final Pattern FORMULA=templatePattern();
    public String identity(){return "pricing-trimmed-mean-1";}
    public Optional<Map<String,Object>> evaluate(Checkpoint cp,Map<String,Object> context){
        Objects.requireNonNull(cp);String expected=safe(cp.expected());if(!isPricing(cp,expected))return Optional.empty();
        List<Map<String,Object>> gaps=new ArrayList<>();List<Evidence> evidence=new ArrayList<>();Formula formula=parse(expected);
        if(formula==null){gaps.add(gap("PRICING_FORMULA_UNSUPPORTED","已确认条款不属于当前完整支持的均值下浮价格公式；不能套用默认比例或系数"));return Optional.of(result(cp,gaps,evidence,Map.of()));}
        if(!cp.enabled()||!cp.confirmed()){gaps.add(gap("PRICING_REQUIREMENT_UNCONFIRMED","价格评分条款须启用并明确确认"));return Optional.of(result(cp,gaps,evidence,Map.of()));}
        Evidence requirement=validEvidence(cp.source());if(requirement==null)gaps.add(gap("PRICING_REQUIREMENT_EVIDENCE_REQUIRED","价格公式必须有可定位的招标条款原文"));else evidence.add(requirement);
        Object supplied=context==null?null:context.get("pricingData");Map<?,?> data=supplied instanceof Map<?,?> m?m:Map.of();
        if(!Boolean.TRUE.equals(data.get("confirmed")))gaps.add(gap("PRICING_DATA_CONFIRMATION_REQUIRED","需明确确认本项目的价格评分输入，历史合同金额不能替代本次投标报价"));
        if(!Boolean.TRUE.equals(data.get("rosterComplete")))gaps.add(gap("PRICING_ROSTER_INCOMPLETE","缺少全部有效投标人及有效评标价完整名单"));
        if(!Boolean.TRUE.equals(data.get("arithmeticCorrectionsConfirmed")))gaps.add(gap("PRICING_ARITHMETIC_CORRECTIONS_UNCONFIRMED","需确认每项投标价已完成所需算术修正或明确无需修正"));
        if(!"EXCLUDING_TAX".equals(data.get("taxBasis")))gaps.add(gap("PRICING_TAX_BASIS_REQUIRED","必须提供经确认的不含税评标价，单位为元"));
        Evidence roster=validEvidence(data.get("rosterEvidence"));if(roster==null)gaps.add(gap("PRICING_ROSTER_EVIDENCE_REQUIRED","有效名单的完整性必须引用原始名单或评审记录"));else evidence.add(roster);
        String target=data.get("targetBidder") instanceof String s?s.strip():"";if(target.isBlank()||target.length()>200)gaps.add(gap("PRICING_TARGET_REQUIRED","需指定本次计算的投标人名称"));
        List<Entry> entries=new ArrayList<>();Set<String> names=new HashSet<>();Object rows=data.get("entries");
        if(!(rows instanceof List<?> list)||list.isEmpty()||list.size()>500)gaps.add(gap("PRICING_ENTRIES_REQUIRED","需提供1至500条具原始证据的有效投标人报价"));
        else for(int index=0;index<list.size();index++){
            Object row=list.get(index);if(!(row instanceof Map<?,?> r)){gaps.add(gap("PRICING_ENTRY_INVALID","第"+(index+1)+"条报价结构无效"));continue;}
            String bidder=r.get("bidder") instanceof String s?s.strip():"";String key=bidderKey(bidder);
            if(bidder.isBlank()||bidder.length()>200||!names.add(key)){gaps.add(gap("PRICING_BIDDER_DUPLICATE_OR_INVALID","有效投标人名称须非空且唯一；相同价格的不同企业仍须分别保留"));continue;}
            BigDecimal price=price(r.get("price"));Evidence source=validEvidence(r.get("evidence"));
            if(price==null){gaps.add(gap("PRICING_PRICE_INVALID","投标人“"+bidder+"”须提供正数元价字符串，不能使用浮点数、负价或空值"));continue;}
            if(source==null){gaps.add(gap("PRICING_PRICE_EVIDENCE_REQUIRED","投标人“"+bidder+"”的报价缺少可定位原始证据"));continue;}
            if(source.quote().matches("(?s).*(?:历史合同|业绩合同|历史项目|以往项目).*")&&!source.quote().matches("(?s).*(?:本次|本项目).*(?:评标价|投标价|报价).*")){gaps.add(gap("PRICING_HISTORICAL_PRICE_REJECTED","历史合同或业绩价格不能代替当前有效评标价"));continue;}
            evidence.add(source);entries.add(new Entry(bidder,key,price,source,index));
        }
        Entry selected=entries.stream().filter(e->e.key.equals(bidderKey(target))).findFirst().orElse(null);
        if(selected==null&&!target.isBlank())gaps.add(gap("PRICING_TARGET_NOT_IN_ROSTER","目标投标人没有出现在完整且有效的报价记录中"));
        if(!gaps.isEmpty())return Optional.of(result(cp,gaps,evidence,Map.of()));
        List<Entry> sorted=new ArrayList<>(entries);sorted.sort(Comparator.comparing(Entry::price).thenComparingInt(Entry::inputIndex));List<Entry> removed=new ArrayList<>();
        if(sorted.size()>=formula.threshold){removed.add(sorted.remove(0));removed.add(sorted.remove(sorted.size()-1));}
        BigDecimal sum=sorted.stream().map(Entry::price).reduce(BigDecimal.ZERO,BigDecimal::add),count=BigDecimal.valueOf(sorted.size());
        BigDecimal weightedSum=sum.multiply(formula.factor),targetScaled=selected.price.multiply(count),delta=targetScaled.subtract(weightedSum);
        BigDecimal coefficient=delta.signum()>=0?formula.upper:formula.lower;
        // Keep the division symbolic until final rounding: A=weightedSum/count, so this ratio is exact.
        BigDecimal penaltyNumerator=delta.abs().multiply(BigDecimal.valueOf(100)).multiply(coefficient);
        BigDecimal scoreNumerator=formula.maximum.multiply(weightedSum).subtract(penaltyNumerator).max(BigDecimal.ZERO);
        String score=scoreNumerator.divide(weightedSum,2,RoundingMode.HALF_UP).toPlainString();
        Map<String,Object> proof=map("formulaId",identity(),"formula",map("trimThreshold",formula.threshold,"trimHighest",1,"trimLowest",1,"meanMultiplier",plain(formula.factor),"maximumScore",plain(formula.maximum),"aboveCoefficient",plain(formula.upper),"belowCoefficient",plain(formula.lower),"minimumScore","0","rounding","HALF_UP","scale",2),
            "validBidderCount",entries.size(),"usedBidderCount",sorted.size(),"excludedRecords",removed.stream().map(this::entryProof).toList(),"includedRecords",sorted.stream().map(this::entryProof).toList(),
            "targetBidder",selected.bidder,"targetPriceB",plain(selected.price),"benchmarkA",plain(weightedSum.divide(count,MC)),"benchmarkAExact",map("numerator",plain(weightedSum),"denominator",plain(count)),"score",score,"officialReviewScore",false,"decisionBasis","RULE_CALCULATION",
            "steps",List.of(map("operation","TRIM_BY_RECORD_COUNT","inputCount",entries.size(),"removedCount",removed.size()),map("operation","ARITHMETIC_MEAN","sum",plain(sum),"count",sorted.size(),"mean",plain(sum.divide(count,MC))),map("operation","BENCHMARK","multiplier",plain(formula.factor),"A",plain(weightedSum.divide(count,MC))),map("operation",delta.signum()>=0?"B_GREATER_OR_EQUAL_A":"B_LESS_THAN_A","penaltyCoefficient",plain(coefficient),"scoreNumeratorAfterFloor",plain(scoreNumerator),"scoreDenominator",plain(weightedSum)),map("operation","ROUND_FINAL_ONLY","rounding","HALF_UP","scale",2,"score",score)));
        return Optional.of(result(cp,List.of(),evidence,proof));
    }
    private Map<String,Object> entryProof(Entry e){return map("bidder",e.bidder,"price",plain(e.price),"inputIndex",e.inputIndex,"evidence",e.evidence);}
    private Map<String,Object> result(Checkpoint cp,List<Map<String,Object>> gaps,List<Evidence> evidence,Map<String,Object> proof){boolean done=gaps.isEmpty();String reason=done?"已按已确认的完整有效报价集合和原价格公式完成规则计算；所示分数不是正式评审分。":"价格评分尚未具备确定计算条件；不把缺少评分材料判为资格不符，也不使用历史合同金额补数。";
        Map<String,Object> obligation=map("id",cp.id()+":pricing","description",cp.title(),"status",done?"PASS":"UNDETERMINED","reason",reason,"proof",proof,"gaps",gaps,"evidences",evidence);
        return map("checkpointId",cp.id(),"title",cp.title(),"businessDecision",done?"PASS":"UNDETERMINED","decisionBasis","RULE_CALCULATION","complete",done,"workflowState",done?"COMPLETED":"WAIT_INPUT","reason",reason,"scoreMeaning","规则计算参考分，非正式评审分","officialReviewScore",false,"proof",proof,"obligations",List.of(obligation),"gaps",gaps,"evidences",evidence.stream().distinct().toList(),"visualRequests",List.of(),"externalRequests",List.of(),"facts",List.of(),"relations",List.of());}
    private static Formula parse(String expected){if(expected.length()>16000)return null;Matcher m=FORMULA.matcher(normal(expected));if(!m.find())return null;String suffix=normal(expected).substring(m.end());if(!Set.of("",normal(POLICY),normal(MISSING_POLICY),normal(POLICY+MISSING_POLICY)).contains(suffix))return null;
        try{Map<String,BigDecimal> p=new HashMap<>();for(String name:PARAMETERS)p.put(name,new BigDecimal(m.group(name)));if(p.get("discount").compareTo(new BigDecimal("5"))!=0||p.get("factor").compareTo(new BigDecimal("95"))!=0||p.get("threshold").intValueExact()!=5||p.get("below").intValueExact()!=5||p.get("maxHigh").compareTo(new BigDecimal("60"))!=0||p.get("maxLow").compareTo(new BigDecimal("60"))!=0||p.get("upper").compareTo(BigDecimal.ONE)!=0||p.get("upperFormula").compareTo(BigDecimal.ONE)!=0||p.get("lower").compareTo(new BigDecimal("0.5"))!=0||p.get("lowerFormula").compareTo(new BigDecimal("0.5"))!=0)return null;return new Formula(p.get("threshold").intValueExact(),p.get("factor").movePointLeft(2),p.get("maxHigh"),p.get("upper"),p.get("lower"));}catch(RuntimeException e){return null;}}
    private static Pattern templatePattern(){String pattern=Pattern.quote(normal(TEMPLATE));for(String p:PARAMETERS)pattern=pattern.replace("{"+p+"}","\\E(?<"+p+">[0-9]{1,3}(?:\\.[0-9]{1,3})?)\\Q");return Pattern.compile("^"+pattern);}
    private static boolean isPricing(Checkpoint cp,String expected){return safe(cp.title()).matches("(?s).*(?:价格|报价)(?:评分|得分).*")||normal(expected).startsWith(normal("本项目采用：均值下浮法"));}
    private static Evidence validEvidence(Object input){Evidence e=null;if(input instanceof Evidence source)e=source;else if(input instanceof Map<?,?> m){try{Object n=m.get("page");int page=n instanceof Number v?v.intValue():Integer.parseInt(Objects.toString(n,""));e=new Evidence(Objects.toString(m.get("documentId"),""),Objects.toString(m.get("documentName"),""),page,Objects.toString(m.get("locationType"),""),Objects.toString(m.get("quote"),""),Objects.toString(m.get("anchor"),""),0);}catch(Exception ignored){}}
        return e!=null&&!safe(e.documentId()).isBlank()&&e.page()>0&&Set.of("PAGE","BLOCK","PARAGRAPH","IMAGE").contains(safe(e.locationType()))&&!safe(e.quote()).isBlank()&&e.quote().length()<=8000?e:null;}
    private static BigDecimal price(Object raw){if(!(raw instanceof String s)||!s.matches("[0-9]{1,18}(?:\\.[0-9]{1,6})?"))return null;BigDecimal value=new BigDecimal(s);return value.signum()>0?value:null;}
    private static String bidderKey(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("\\s+","");}
    private static String normal(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("[\\s/;；,，。:：“”\"]","");}
    private static String safe(String s){return s==null?"":s;}private static String plain(BigDecimal n){return n.stripTrailingZeros().toPlainString();}
    private static Map<String,Object> gap(String code,String description){return map("code",code,"description",description,"requiredInput",description,"resumeTrigger","PRICING_INPUT_CONFIRMED");}
    private static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    private record Entry(String bidder,String key,BigDecimal price,Evidence evidence,int inputIndex){}
    private record Formula(int threshold,BigDecimal factor,BigDecimal maximum,BigDecimal upper,BigDecimal lower){}
}
