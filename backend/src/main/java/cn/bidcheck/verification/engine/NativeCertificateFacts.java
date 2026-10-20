package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.source.DateFieldValues;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Conservative local consumption of an already retrieved, single-owner/single-certificate record. */
final class NativeCertificateFacts {
    private static final Pattern OWNER=Pattern.compile("(?:企业名称|单位名称|持证人|姓\\s*名)\\s*[:：]\\s*([^\\n|<>;；]{2,80})");
    private static final Pattern NUMBER=Pattern.compile("(证书编号|注册编号|资格证号)\\s*[:：]\\s*([A-Za-z0-9（()）-]{4,80})");
    private static final Pattern PERIOD=Pattern.compile("注册有效期|使用有效期|证书有效期|资格有效期|有效截止日期|有效期限|有效期至|有效期");
    static List<Fact> extract(Rule rule,Source source){
        if(!Set.of("CERTIFICATE","VALIDITY").contains(rule.predicate())||source.index()||source.id().startsWith("V"))return List.of();
        String text=source.evidence().quote();if(text.matches("(?s).*(?:填写示例|示范文本|仅供参考|拟取得|尚未取得).*"))return List.of();
        String category=str(rule.parameters(),"category");if(category.isBlank()||!ProofVerifier.contains(text,category))return List.of();
        Matcher owner=OWNER.matcher(text),number=NUMBER.matcher(text);if(!owner.find()||!number.find())return List.of();
        String subject=owner.group(1).strip(),numberLabel=number.group(1),certificateNumber=number.group(2);if(owner.find()||number.find())return List.of();
        // Do not promote a consortium work allocation to a certificate or join separate owners by name.
        if(!text.matches("(?s).*(?:资质证书|资格证书|注册证书|许可证|考核合格证书|职称证书).*"))return List.of();
        Map<String,Object> attrs=new LinkedHashMap<>();attrs.put("category",category);attrs.put("number",certificateNumber);attrs.put("numberField",numberLabel);
        Matcher grade=Pattern.compile("(?:资质等级|资格等级|等级)\\s*[:：]\\s*([^\\n|。；]{1,100})").matcher(text);
        if(grade.find()){String raw=grade.group(1).strip();Matcher rank=Pattern.compile("[甲乙丙丁一二三壹贰叁]级").matcher(raw);if(rank.find())attrs.put("grade",rank.group());}
        Matcher validity=PERIOD.matcher(text);List<String> labels=new ArrayList<>();while(validity.find())labels.add(validity.group());
        if(labels.size()==1){String field=labels.get(0);var values=DateFieldValues.read(text,field,2);if(!values.isEmpty()){attrs.put("validityField",field);if(values.size()==2)attrs.put("start",values.get(0).toString());attrs.put("end",values.get(values.size()-1).toString());}}
        attrs.put("bindingMethod","SINGLE_ORIGINAL_CERTIFICATE_RECORD");attrs.put("bindingVersion",DateFieldValues.VERSION);
        Fact fact=new Fact("N"+identity(source.id()+category+certificateNumber+"certificate-record-v1").substring(0,20),"CERTIFICATE",subject,"证书",certificateNumber,certificateNumber,"","","UNRESOLVED",List.of(source.id()),attrs);
        Map<String,Source> sources=Map.of(source.id(),source);
        return new ProofVerifier().issue(fact,sources)==null&&DomainBindings.issue(fact,sources,List.of())==null?List.of(fact):List.of();
    }
}
