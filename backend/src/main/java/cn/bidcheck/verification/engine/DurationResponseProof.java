package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A permitted form response proves an undertaking, never an invented numerical observation. */
final class DurationResponseProof {
    static final String VERSION="duration-authorized-response-1";
    private static final Pattern FORMAT=Pattern.compile("承诺工期为[_＿—]*[（(]填写[“\"‘']?满足[”\"’']?[）)]");
    private static final Pattern FILLED=Pattern.compile("(?<!不)(?:承诺)?工期(?:为|[:：])满足(?=[（(，,。；;]|$)");
    private static final Pattern NUMBER=Pattern.compile("(设计工期|施工工期|总工期|承诺工期)(?:要求)?(?:为|[:：])?(\\d+)(日历天|日历日|工作日|天|日)");
    private DurationResponseProof(){}

    static boolean applies(Rule rule){return rule.predicate().equals("DECLARATION")&&rule.field().equals("durationCommitment")
        ||rule.predicate().equals("NUMBER")&&Set.of("designDuration","constructionDuration","totalDuration","duration").contains(rule.field());}

    /** Returns null for a numeric rule with no eligible response; the normal number verifier continues. */
    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        boolean declaration=rule.predicate().equals("DECLARATION");
        Binding requirement=binding(rule,evaluator);
        if(requirement==null)return declaration?pending(rule,"DURATION_REQUIREMENT_SOURCE_REQUIRED","工期响应尚未关联同一冻结招标文件中的原要求",List.of(),Map.of()):null;
        if(declaration&&!rule.expected().equals("满足")||!declaration&&!numericRequirementBound(rule))
            return pending(rule,"DURATION_REQUIREMENT_FIELD_UNBOUND","工期阶段、数值及日历口径尚未与招标原要求逐字段对应",List.of(requirement.evidence()),Map.of());
        Document tender=requirement.document();
        List<Evidence> authorization=authorization(tender);
        if(authorization.isEmpty())return declaration?pending(rule,"DURATION_RESPONSE_FORMAT_REQUIRED","招标文件尚未明确允许以填写“满足”响应工期，不能把普通肯定词等同于数值承诺",List.of(requirement.evidence()),Map.of()):null;
        String phase=phase(rule);List<Source> current=evaluator.corpus.all.stream().filter(DurationResponseProof::current).toList();
        List<Source> letters=current.stream().filter(s->text(s.opening()).contains("投标函"))
            .filter(s->sameTenderProject(s,tender))
            .filter(s->text(s.evidence().quote()).contains(s.scopeContext().projectName()))
            .filter(s->text(s.evidence().quote()).contains("招标文件的全部内容"))
            .filter(s->FILLED.matcher(text(s.evidence().quote())).find()).toList();
        if(letters.isEmpty())return declaration?pending(rule,"DURATION_FILLED_RESPONSE_REQUIRED","尚未定位针对本项目且工期栏实际填写“满足”的投标函；空栏中的填写说明不作为响应",List.of(requirement.evidence()),Map.of()):null;
        Set<String> documentIds=new LinkedHashSet<>();letters.forEach(s->documentIds.add(s.evidence().documentId()));
        String project=letters.get(0).scopeContext().projectName();
        current=current.stream().filter(s->documentIds.contains(s.evidence().documentId())&&s.scopeContext().projectName().equals(project)).toList();
        Map<String,Object> proof=linked("responseVerifierVersion",VERSION,"decisionBasis","TENDER_AUTHORIZED_COMMITMENT_RESPONSE",
            "observedResponse","满足","phase",phase,"requirementField",rule.field(),"requirementValue",rule.expected(),"requirementUnit",rule.unit(),
            "numericObservationSynthesized",false,"proofScope","DURATION_UNDERTAKING_ONLY","doesNotProveFormCompletionOrSignature",true,
            "requirementSource",requirement.evidence(),"authorizedFormSources",authorization,"responseSourceIds",letters.stream().map(Source::id).toList(),
            "projectName",project);
        List<Evidence> evidence=new ArrayList<>();evidence.add(requirement.evidence());evidence.addAll(authorization);letters.forEach(s->evidence.add(s.evidence()));
        List<Source> adverse=new ArrayList<>();List<Map<String,Object>> numeric=new ArrayList<>();
        for(Source source:current){
            String body=beforeInstructions(text(source.evidence().quote()));
            if(body.matches("(?s).*(?:设计工期|施工工期|总工期|承诺工期|工期)(?:为|[:：])?(?:不满足|无法满足|不能满足).*"))adverse.add(source);
            Matcher values=NUMBER.matcher(body);while(values.find())if(matchesPhase(phase,values.group(1))){
                BigDecimal actual=new BigDecimal(values.group(2));String basis=values.group(3).equals("工作日")?"WORKING_DAY":"CALENDAR_DAY";
                numeric.add(linked("sourceId",source.id(),"fieldLabel",values.group(1),"rawValue",values.group(),"value",actual,"calendarBasis",basis));
                if(!declaration){BigDecimal expected=ProofVerifier.decimal(rule.expected());String requiredBasis=str(rule.parameters(),"calendarBasis");
                    if(expected==null||requiredBasis.equals("CALENDAR_DAY")&&!basis.equals("CALENDAR_DAY"))adverse.add(source);
                    else if(!Boolean.TRUE.equals(RuleEvaluator.compare(actual,expected,rule.operator())))adverse.add(source);
                }
            }
        }
        proof.put("explicitDurationFields",numeric);
        if(!adverse.isEmpty()){
            adverse.stream().map(Source::evidence).forEach(evidence::add);
            return pending(rule,"DURATION_RESPONSE_CONFLICT","“满足”响应与当前项目的明示工期、日历口径或否定响应存在冲突，须按具体字段核实，不能直接按总承诺通过",evidence,proof);
        }
        List<Source> deviation=current.stream().filter(s->text(s.opening()).contains("合同条款偏离表")).toList();
        List<Source> clear=deviation.stream().filter(s->{String body=beforeInstructions(text(s.evidence().quote()));return body.contains("无偏离")&&!body.contains("负偏离");}).toList();
        if(deviation.stream().anyMatch(s->{String body=beforeInstructions(text(s.evidence().quote()));return body.contains("负偏离")||body.matches("(?s).*工期.*(?:偏离|不满足).*");})){
            deviation.forEach(s->evidence.add(s.evidence()));return pending(rule,"DURATION_DEVIATION_CONFLICT","本项目偏离表包含偏离记录，须先确认其与工期义务的关系，不能用总承诺覆盖具体偏离",evidence,proof);
        }
        // Extending a general undertaking to separate numeric phases needs the same-project full-clause response.
        if(!declaration&&!phase.equals("TOTAL")&&(clear.isEmpty()||clear.stream().noneMatch(s->text(s.evidence().quote()).contains("全部条款"))))
            return pending(rule,"DURATION_PHASE_RESPONSE_SCOPE_REQUIRED","已确认总工期承诺，但尚未以本项目全部条款的无偏离响应闭合设计/施工分阶段工期覆盖",evidence,proof);
        clear.forEach(s->evidence.add(s.evidence()));proof.put("deviationSourceIds",clear.stream().map(Source::id).toList());
        return new Decision(rule.id(),rule.description(),"PASS","本项目投标函已按招标指定格式填写工期“满足”"+(phase.equals("TOTAL")?"":"，并由全部条款无偏离响应覆盖本阶段要求")+"；结论仅证明工期承诺响应，未虚构投标工期数字",evidence.stream().distinct().toList(),List.of(),proof);
    }

    private record Binding(Document document,Evidence evidence){}
    private static Binding binding(Rule rule,RuleEvaluator evaluator){
        if(!(rule.parameters().get("requirementSourceContext") instanceof Map<?,?> context)||!(context.get("identity") instanceof Map<?,?> id)
            ||!(evaluator.context.get("tenderDocuments") instanceof List<?> documents))return null;
        for(Object item:documents)if(item instanceof Document document&&"TENDER".equals(document.role())&&document.id().equals(id.get("documentId"))
            &&document.sha256().equals(id.get("documentHash"))&&document.projectId().equals(id.get("projectId"))&&Objects.equals(document.version(),id.get("documentVersion"))
            &&evaluator.corpus.documents.stream().allMatch(b->b.projectId().equals(document.projectId()))){
            for(Page page:document.pages())if(Objects.equals(page.number(),id.get("location"))&&page.locationType().equals(id.get("locationType"))
                &&id.get("start") instanceof Number a&&id.get("end") instanceof Number b){
                int start=a.intValue(),end=b.intValue();if(start<0||end<=start||end>page.text().length())continue;String quote=page.text().substring(start,end);
                if(identity(quote).equals(id.get("sourceHash"))&&RequirementContextBindings.uniqueOffset(quote,rule.sourceQuote())>=0)
                    return new Binding(document,new Evidence(document.id(),document.name(),page.number(),page.locationType(),quote,"chars:"+start+"-"+end,1));
            }
        }return null;
    }
    private static List<Evidence> authorization(Document tender){
        List<Evidence> out=new ArrayList<>();List<Page> pages=tender.pages();
        for(int i=0;i<pages.size();i++){Page page=pages.get(i);if(!FORMAT.matcher(text(page.text())).find())continue;
            boolean form=false,all=false;for(int j=Math.max(0,i-10);j<=i;j++){
                String value=text(pages.get(j).text());if(value.equals("投标函")||value.startsWith("投标函")||value.contains("投标函致"))form=true;
                if(value.contains("招标文件的全部内容"))all=true;
            }
            if(form&&all)out.add(new Evidence(tender.id(),tender.name(),page.number(),page.locationType(),page.text(),"chars:0-"+page.text().length(),1));
        }return out;
    }
    private static boolean current(Source source){return !source.index()&&!source.historical()&&!source.scopeContext().blocked()
        &&Set.of("CURRENT_PROJECT","CURRENT_DOCUMENT","CURRENT_BID_SECTION").contains(source.scopeContext().status())
        &&!source.scopeContext().projectName().isBlank()&&!source.evidence().quote().matches("(?s).*(?:填写示例|空白模板|示范文本|仅供参考).*");}
    private static boolean sameTenderProject(Source source,Document tender){String project=text(source.scopeContext().projectName());return !project.isBlank()&&tender.pages().stream().limit(12).anyMatch(p->text(p.text()).contains(project));}
    private static String phase(Rule rule){return rule.field().equals("designDuration")?"DESIGN":rule.field().equals("constructionDuration")?"CONSTRUCTION":"TOTAL";}
    private static boolean numericRequirementBound(Rule rule){
        if(!Set.of("日","天","DAY").contains(rule.unit())||!Set.of("=","EQ","==").contains(rule.operator()))return false;
        BigDecimal expected=ProofVerifier.decimal(rule.expected());if(expected==null)return false;
        Matcher values=NUMBER.matcher(text(rule.sourceQuote()));while(values.find())if(matchesPhase(phase(rule),values.group(1))
            &&expected.compareTo(new BigDecimal(values.group(2)))==0&&!values.group(3).equals("工作日"))return true;
        return false;
    }
    private static boolean matchesPhase(String phase,String label){return phase.equals("DESIGN")?label.equals("设计工期"):phase.equals("CONSTRUCTION")?label.equals("施工工期"):label.equals("总工期")||label.equals("承诺工期");}
    private static String text(String value){return clean(value).replaceAll("<[^>]+>","").replace("*","").replace("|","");}
    private static String beforeInstructions(String value){int p=value.indexOf("注：");if(p<0)p=value.indexOf("注:");return p<0?value:value.substring(0,p);}
    private static Decision pending(Rule rule,String code,String reason,List<Evidence> evidence,Map<String,Object> proof){return new Decision(rule.id(),rule.description(),"UNDETERMINED",reason,evidence.stream().distinct().toList(),List.of(gap(code,reason)),proof);}
}
