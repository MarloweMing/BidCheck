package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Explicit fields in the current bid letter; no inferred numbers or general undertaking shortcut. */
final class BidLetterResponse {
    static final String VERSION="source-bound-bid-letter-response-1";
    private static final Pattern QUALITY=Pattern.compile("质量(?:为|[:：])(?:[\\s＿_]*)(不合格|合格)(?=[（(，,。；;]|$)");
    private static final Pattern VALIDITY=Pattern.compile("投标有效期(?:为|[:：])([0-9]+)(?:日历)?(?:天|日)(?=[）)，,。；;]|$)");
    private BidLetterResponse(){}
    static Rule compile(String source){
        String text=clean(source);String field,expected;
        if(text.matches("质量[:：]?合格[。.]?[（(]见投标函[）)][。.]?")){field="bidQualityResponse";expected="合格";}
        else if(text.matches("投标有效期[:：]?满足招标文件规定[。.]?[（(]见投标函[）)][。.]?")){field="bidValidityResponse";expected="满足招标文件规定";}
        else return null;
        return new Rule("LETTER-"+identity(source).substring(0,16),field.equals("bidQualityResponse")?"投标函质量响应":"投标函有效期响应","DECLARATION","",field,"=",expected,"",
            cn.bidcheck.compat.Java8Api.mapOf("deterministicFamily", "BID_LETTER_RESPONSE", "responseVersion", VERSION),source,"",cn.bidcheck.compat.Java8Api.listOf());
    }
    static boolean applies(Rule rule){return VERSION.equals(rule.parameters().get("responseVersion"))&&rule.predicate().equals("DECLARATION");}
    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        Rule rebuilt=compile(rule.sourceQuote());
        if(rebuilt==null||!rebuilt.field().equals(rule.field())||!rebuilt.expected().equals(rule.expected())||!rebuilt.operator().equals(rule.operator())||!rule.children().isEmpty())
            return pending(rule,"RULE_SOURCE_VALIDATION","投标函响应规则与原文要求不一致，不能沿用修改后的字段或比较值",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf());
        Document tender=tender(rule,evaluator);if(tender==null)return pending(rule,"REQUIREMENT_SOURCE_REQUIRED","投标函响应未绑定当前冻结招标要求",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf());
        List<Source> letters=cn.bidcheck.compat.Java8Api.toList(evaluator.corpus.all.stream().filter(s->!s.index()&&!s.historical()&&!s.scopeContext().blocked())
            .filter(s->cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "CURRENT_DOCUMENT", "CURRENT_BID_SECTION").contains(s.scopeContext().status()))
            .filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s.scopeContext().projectName())&&tender.pages().stream().limit(12).anyMatch(p->clean(p.text()).contains(clean(s.scopeContext().projectName()))))
            .filter(s->clean(s.evidence().quote()).contains(clean(s.scopeContext().projectName()))&&clean(s.opening()).contains("投标函"))
            .filter(s->!s.evidence().quote().matches("(?s).*(?:填写示例|空白模板|示范文本|仅供参考).*" )));
        boolean quality=rule.field().equals("bidQualityResponse");
        List<Evidence> evidence=new ArrayList<>();Set<String> observed=new LinkedHashSet<>();
        for(Source source:letters){Matcher matcher=(quality?QUALITY:VALIDITY).matcher(body(source));while(matcher.find()){observed.add(matcher.group(1));evidence.add(source.evidence());}}
        Map<String,Object> proof=linked("executor",VERSION,"proofScope",quality?"QUALITY_UNDERTAKING_ONLY":"BID_VALIDITY_UNDERTAKING_ONLY","observedValues",cn.bidcheck.compat.Java8Api.listCopyOf(observed),"numericObservationSynthesized",false,"doesNotProveSignatureOrActualPerformance",true,"programSourceRead",ProgramSourceRead.receipt(evaluator.corpus,letters));
        if(observed.isEmpty())return pending(rule,"BID_LETTER_FIELD_REQUIRED","尚未定位本项目投标函中实际填写的"+(quality?"质量":"投标有效期")+"字段；填写说明或空栏不作为响应",evidence,proof);
        if(observed.size()!=1)return pending(rule,"BID_LETTER_FIELD_CONFLICT","本项目投标函的同一响应字段存在不同取值",evidence,proof);
        boolean pass;
        if(quality)pass=observed.contains("合格");
        else {
            Set<String> required=new LinkedHashSet<>();
            for(Page page:tender.pages()){
                String text=clean(page.text());
                // Explicit requirement/table row only; an example, guarantee period or extension is not the baseline.
                if(!text.matches("(?:[0-9]+(?:[.][0-9]+)*[、.]?)?投标有效期.*")||text.matches(".*(?:示例|例如|延长|保证金|填写).*"))continue;
                Matcher matcher=VALIDITY.matcher(text);while(matcher.find()){required.add(matcher.group(1));evidence.add(new Evidence(tender.id(),tender.name(),page.number(),page.locationType(),page.text(),"chars:0-"+page.text().length(),1));}
            }
            proof.put("requirementValues",cn.bidcheck.compat.Java8Api.listCopyOf(required));
            if(required.size()!=1)return pending(rule,"BID_VALIDITY_BASELINE_REQUIRED","招标投标有效期数值尚未唯一定位，不能以通用“满足”代替数值核验",evidence,proof);
            Set<String> requiredStarts=new LinkedHashSet<>(),observedStarts=new LinkedHashSet<>();
            for(Page page:tender.pages()){
                Set<String> events=validityStarts(clean(page.text()));if(!events.isEmpty()){
                    requiredStarts.addAll(events);evidence.add(new Evidence(tender.id(),tender.name(),page.number(),page.locationType(),page.text(),"chars:0-"+page.text().length(),1));
                }
            }
            for(Source letter:letters)observedStarts.addAll(validityStarts(body(letter)));
            boolean incorporated=letters.stream().anyMatch(s->body(s).contains("在招标文件规定的投标有效期"));
            proof.putAll(linked("requiredStartEvents",cn.bidcheck.compat.Java8Api.listCopyOf(requiredStarts),"observedStartEvents",cn.bidcheck.compat.Java8Api.listCopyOf(observedStarts),"startEventIncorporatedByReference",incorporated&&observedStarts.isEmpty()));
            if(requiredStarts.size()>1||observedStarts.size()>1||!observedStarts.isEmpty()&&requiredStarts.isEmpty()||!requiredStarts.isEmpty()&&observedStarts.isEmpty()&&!incorporated)
                return pending(rule,"BID_VALIDITY_START_REQUIRED","有效期起算事件尚未唯一对应，不能仅凭天数作出完整响应结论",evidence,proof);
            if(!requiredStarts.isEmpty()&&!observedStarts.isEmpty()&&!requiredStarts.equals(observedStarts))
                return new Decision(rule.id(),rule.description(),"FAIL","投标函有效期起算事件与招标要求不一致",cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),cn.bidcheck.compat.Java8Api.listOf(),proof);
            pass=new BigDecimal(observed.iterator().next()).compareTo(new BigDecimal(required.iterator().next()))>=0;
        }
        return new Decision(rule.id(),rule.description(),pass?"PASS":"FAIL",pass?"本项目投标函的实际填写字段满足对应招标要求；仅证明承诺响应":"本项目投标函的实际填写字段不满足对应招标要求",cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),cn.bidcheck.compat.Java8Api.listOf(),proof);
    }
    private static String body(Source source){return clean(source.evidence().quote().split("(?:编制及签署要求|填写说明|编制说明)",2)[0]);}
    private static Set<String> validityStarts(String text){
        Set<String> result=new LinkedHashSet<>();
        for(String sentence:text.split("[。；;\\n]")){
            if(!sentence.contains("投标有效期")||sentence.matches(".*(?:示例|例如|保证金|填写说明|延长).*"))continue;
            Matcher start=Pattern.compile("(?:自|从)(投标(?:文件(?:递交)?)?截止(?:时间|日期)?|开标(?:时间|日期)?)(?:之?日|当天)?(?:起|开始|计算|算起)").matcher(sentence);
            while(start.find())result.add(start.group(1).startsWith("开标")?"OPENING_DATE":"BID_DEADLINE");
        }
        return result;
    }
    private static Document tender(Rule rule,RuleEvaluator evaluator){
        if(!(rule.parameters().get("requirementSourceContext") instanceof Map<?,?>)||!(((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity") instanceof Map<?,?>)||!(evaluator.context.get("tenderDocuments") instanceof List<?>))return null;
        for(Object value:((List<?>) (evaluator.context.get("tenderDocuments"))))if(value instanceof Document&&((Document) (value)).role().equals("TENDER")&&((Document) (value)).id().equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentId"))&&((Document) (value)).sha256().equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentHash"))&&Objects.equals(((Document) (value)).version(),((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentVersion"))&&((Document) (value)).projectId().equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("projectId"))&&evaluator.corpus.documents.stream().allMatch(b->b.projectId().equals(((Document) (value)).projectId())))
            for(Page p:((Document) (value)).pages())if(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("location") instanceof Number&&p.number()==((Number) (((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("location"))).intValue()&&p.locationType().equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("locationType"))&&((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("start") instanceof Number&&((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("end") instanceof Number){
                int start=((Number) (((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("start"))).intValue(),end=((Number) (((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("end"))).intValue();if(start<0||end<=start||end>p.text().length())continue;
                String quote=p.text().substring(start,end);if(identity(quote).equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("sourceHash"))&&RequirementContextBindings.uniqueOffset(quote,rule.sourceQuote())>=0)return ((Document) (value));
            }
        return null;
    }
    private static Decision pending(Rule rule,String code,String reason,List<Evidence> evidence,Map<String,Object> proof){return new Decision(rule.id(),rule.description(),"UNDETERMINED",reason,cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),cn.bidcheck.compat.Java8Api.listOf(gap(code,reason)),proof);}
}
