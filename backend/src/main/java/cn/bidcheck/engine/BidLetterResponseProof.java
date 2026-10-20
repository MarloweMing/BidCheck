package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.DocumentScopeProof;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** Only the explicit response fields of a current bid letter, never performance or certificate proof. */
public final class BidLetterResponseProof {
    private BidLetterResponseProof() {}
    public static final String VERSION="bid-letter-response-1";
    public record Decision(Evaluation evaluation,Map<String,Object> proof) {}
    private static final Pattern VALIDITY=Pattern.compile("投标有效期(?:为|是|[:：]|\\s|[（(])*([0-9]+)\\s*(天|日历天|日)(?!历?工作)");
    public static Optional<Decision> evaluate(Checkpoint cp,Document bid,Document tender) {
        if(cp==null||bid==null||!cp.enabled()||!cp.confirmed())return Optional.empty();
        String mode=mode(cp);if(mode.isEmpty())return Optional.empty();
        if(tender!=null&&(cp.source()==null||!sourceExists(tender,cp.source())))return Optional.empty();
        List<Evidence> sources=new ArrayList<>();List<Map<String,Object>> bindings=new ArrayList<>();Set<String> actuals=new LinkedHashSet<>();
        DocumentScopeProof.Resolver scope=DocumentScopeProof.forDocument(bid);
        for(Page page:bid.pages()) {
            String text=Objects.toString(page.text(),"");
            if(!text.matches("(?s).*?(?:^|\\n)\\s*(?:[0-9]+[.、]\\s*)?投\\s*标\\s*函\\s*(?:\\n|$).*")||text.matches("(?s).*(?:填写示例|示范文本|仅供参考).*"))continue;
            Evidence evidence=EngineText.evidence(bid,page,1,text);Map<String,Object> proof=scope.currentEvidence(evidence);
            if(!Boolean.TRUE.equals(proof.get("verifiedCurrent")))continue;
            String body=text.replaceAll("[（(]\\s*填写[^）)]*[）)]","").replaceAll("[\\t \\u3000]+","");
            Set<String> values=new LinkedHashSet<>();
            if(mode.equals("VALIDITY")) {Matcher m=VALIDITY.matcher(body);while(m.find())values.add(m.group(1));}
            else {
                String label=mode.equals("DURATION_DECLARATION")?"(?:承诺)?工期":"质量";
                Matcher m=Pattern.compile(label+"(?:为|是|[:：])*([\\p{IsHan}]{2,8})(?=[，,。；;）)\\n]|$)").matcher(body);
                while(m.find())values.add(m.group(1));
            }
            if(values.isEmpty())continue;
            actuals.addAll(values);sources.add(evidence);bindings.add(Map.of("page",page.number(),"sourceScope",proof,"values",values));
        }
        if(actuals.isEmpty())return Optional.empty();
        String target=mode.equals("DURATION_DECLARATION")?"满足":"合格",operator="=";
        List<Evidence> requirementSources=new ArrayList<>();
        if(mode.equals("VALIDITY")) {
            if("VALUE_COMPARE".equals(cp.type())) {target=cp.expected();operator=cp.operator();}
            else {
                Set<String> requirements=new LinkedHashSet<>();
                if(tender!=null)for(Page page:tender.pages()) {
                    String text=Objects.toString(page.text(),"");Matcher matcher=VALIDITY.matcher(text);
                    while(matcher.find()){requirements.add(matcher.group(1));requirementSources.add(EngineText.evidence(tender,page,1,text));}
                }
                if(requirements.size()!=1)return Optional.of(decision("UNDETERMINED","已找到当前投标函的有效期响应，但招标要求中的天数尚未唯一确定。",sources,mode,bindings,requirements,requirementSources));
                target=requirements.iterator().next();operator="=";
            }
        }
        if(actuals.size()!=1)return Optional.of(decision("UNDETERMINED","当前投标函的同一响应字段存在多个取值，需核对版本及适用范围。",sources,mode,bindings,actuals,requirementSources));
        String actual=actuals.iterator().next();boolean passed;
        if(mode.equals("VALIDITY")) {
            if(!target.matches("[0-9]+")||!Set.of("=",">=","<=",">","<").contains(operator))return Optional.empty();
            int comparison=new BigDecimal(actual).compareTo(new BigDecimal(target));
            if("SEMANTIC_RESPONSE".equals(cp.type())&&comparison!=0)return Optional.of(decision("UNDETERMINED","投标函与招标原文的有效期天数不同，需先明确本项为最低期限还是固定响应值，不能自行补设比较运算符。",sources,mode,bindings,Set.of(target),requirementSources));
            passed=switch(operator){case "="->comparison==0;case ">="->comparison>=0;case "<="->comparison<=0;case ">"->comparison>0;default->comparison<0;};
        }else passed=actual.equals(target);
        String field=mode.equals("VALIDITY")?"投标有效期":mode.equals("DURATION_DECLARATION")?"工期响应":"质量响应";
        return Optional.of(decision(passed?"PASS":"FAIL","当前投标函的"+field+"为“"+actual+(mode.equals("VALIDITY")?"天":"")+"”，"+(passed?"满足":"不满足")+"本项明确的格式响应要求"+(mode.equals("VALIDITY")?"（"+operator+" "+target+"天）":"")+"。",sources,mode,bindings,Set.of(target),requirementSources));
    }
    private static Decision decision(String status,String reason,List<Evidence> sources,String mode,List<Map<String,Object>> bindings,Set<String> required,List<Evidence> requirementSources) {
        return new Decision(new Evaluation(status,reason,"PASS".equals(status)?"":"核对所引用投标函及本项要求；本结论不扩展为实际履约或证照证明。",List.copyOf(sources),1,"BID_LETTER_RESPONSE_RULE"),
            Map.of("policyVersion",VERSION,"mode",mode,"scope","EXPLICIT_RESPONSE_FIELD_ONLY","bindings",bindings,"requiredValues",required,"requirementSources",requirementSources));
    }
    private static String mode(Checkpoint cp) {
        if("VALUE_COMPARE".equals(cp.type())&&"bidValidity".equals(cp.field())&&Set.of("天","日","日历天","DAY").contains(Objects.toString(cp.unit(),"")))return "VALIDITY";
        if(!"SEMANTIC_RESPONSE".equals(cp.type()))return "";
        String expected=Objects.toString(cp.expected(),"").replaceAll("[\\s。．.（）()，,:：]","");
        return switch(expected){case "工期满足见投标函"->"DURATION_DECLARATION";case "质量合格见投标函"->"QUALITY_DECLARATION";case "投标有效期满足招标文件规定见投标函"->"VALIDITY";default->"";};
    }
    private static boolean sourceExists(Document doc,Evidence evidence){return doc.id().equals(evidence.documentId())&&doc.pages().stream().anyMatch(p->p.number()==evidence.page()&&p.locationType().equals(evidence.locationType())&&p.text()!=null&&p.text().contains(evidence.quote()));}
}
