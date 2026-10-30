package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.source.DateFieldValues;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Conservative local consumption of an already retrieved, single-owner/single-certificate record. */
final class NativeCertificateFacts {
    private static final Pattern OWNER=Pattern.compile("(?:(?:企业名称|单位名称|持证人|姓\\s*名)\\s*[:：]\\s*|兹证明[\\t ]*\\r?\\n[\\t ]*)([^\\r\\n|<>;；]{2,80})");
    private static final Pattern PERSON_OWNER=Pattern.compile("(?:持证人|姓\\s*名)\\s*[:：]\\s*([\\p{IsHan}·]{2,12})[\\t ]*(?=\\r?\\n|$)");
    private static final Pattern NUMBER=Pattern.compile("(证书编号|注册编号|资格证号|(?<![\\p{IsHan}])编号)\\s*[:：]\\s*([\\p{IsHan}A-Za-z0-9（()）〔〕\\[\\]/-]{4,80})");
    private static final Pattern PERIOD=Pattern.compile("注册有效期|使用有效期|证书有效日期|证书有效期|资格有效期|有效截止日期|有效期限|有效期至|有效期");
    /** Expand a retrieved chunk to its original certificate occurrence before extracting fields. */
    static List<Fact> extract(Rule rule,EvidenceCorpus corpus,List<Source> selected){
        if(!Set.of("CERTIFICATE","VALIDITY").contains(rule.predicate()))return List.of();
        String required=str(rule.parameters(),"category"),kind=str(rule.parameters(),"personnelCredentialKind");
        List<Fact> out=new ArrayList<>();
        for(var record:CertificateRecordBindings.records(corpus,selected)){
            if(!required.equals(record.category())&&!(kind.equals("REGISTERED_BUILDER")&&record.category().contains("注册建造师"))
                &&!(kind.equals("SAFETY")&&record.category().startsWith("安全生产考核合格证书")))continue;
            if(!rule.subject().isBlank()&&!DomainBindings.role(rule.subject())&&!rule.subject().equals(record.owner()))continue;
            var number=record.fields().get("CERTIFICATE_NUMBER");if(number==null)number=record.fields().get("REGISTRATION_NUMBER");if(number==null)continue;
            Map<String,Object> attrs=new LinkedHashMap<>();attrs.put("category",record.category());attrs.put("number",number.value());attrs.put("numberField",number.label());
            if(record.category().startsWith("一级")||record.category().startsWith("二级"))attrs.put("grade",record.category().substring(0,2));
            if(!record.value("EMPLOYER").isBlank())attrs.put("employer",record.value("EMPLOYER"));
            String profession=str(rule.parameters(),"profession");if(profession.isBlank())profession=str(rule.parameters(),"requiredProfession");
            if(profession.equals("房屋建筑工程"))profession="建筑工程";
            if(profession.isBlank())profession=record.value("PROFESSION");
            if(!profession.isBlank()&&(profession.equals(record.value("PROFESSION"))||profession.equals(record.value("PROFESSION:"+profession))))attrs.put("profession",profession);else profession="";
            String requiredField=str(rule.parameters(),"validityField"),prefix=requiredField.equals("使用有效期")?"VALIDITY_USE_":"VALIDITY_";
            String suffix=!prefix.equals("VALIDITY_USE_")&&!profession.isBlank()&&record.fields().containsKey("VALIDITY_END:"+profession)?":"+profession:"";
            var end=record.fields().get(prefix+"END"+suffix);var start=record.fields().get(prefix+"START"+suffix);
            if(end!=null&&(requiredField.isBlank()||requiredField.equals(end.label())||!suffix.isBlank()&&requiredField.equals("有效期"))){
                attrs.put("validityField",suffix.isBlank()?end.label():"有效期");attrs.put("end",end.value());if(start!=null)attrs.put("start",start.value());
            }
            attrs.put("certificateRecord",CertificateRecordBindings.audit(record));attrs.put("bindingMethod","ORIGINAL_CERTIFICATE_FIELD_SPANS");attrs.put("bindingVersion",CertificateRecordBindings.VERSION);
            Fact fact=new Fact("N"+identity(record.id()+attrs.getOrDefault("profession","")+attrs.getOrDefault("validityField","")+CertificateRecordBindings.VERSION).substring(0,20),
                "CERTIFICATE",record.owner(),"证书",number.raw(),number.value(),"","","UNRESOLVED",record.sources().stream().map(Source::id).toList(),Map.copyOf(attrs));
            if(CertificateRecordBindings.matches(fact,corpus.byId)&&DomainBindings.issue(fact,corpus.byId,List.of())==null)out.add(fact);
        }
        return List.copyOf(out);
    }
    static List<Fact> extract(Rule rule,Source source){
        if(!Set.of("CERTIFICATE","VALIDITY").contains(rule.predicate())||source.index()||source.id().startsWith("V"))return List.of();
        if(Set.of("职称","职称证书").contains(str(rule.parameters(),"category")))return ProfessionalTitleCertificate.extract(rule,source);
        String text=source.evidence().quote();if(text.matches("(?s).*(?:填写示例|示范文本|仅供参考|拟取得|尚未取得).*"))return List.of();
        String category=str(rule.parameters(),"category");if(category.isBlank()||!ProofVerifier.contains(text,category))return List.of();
        boolean personal=category.matches(".*(?:建造师|建筑师|监理工程师|造价工程师|安全生产考核).*" );
        Matcher owner=(personal?PERSON_OWNER:OWNER).matcher(text),number=NUMBER.matcher(text);if(!owner.find()||!number.find())return List.of();
        String subject=owner.group(1).strip(),numberLabel=number.group(1),certificateNumber=number.group(2);if(owner.find()||number.find())return List.of();
        // Do not promote a consortium work allocation to a certificate or join separate owners by name.
        if(!text.matches("(?s).*(?:资质证书|资格证书|注册证书|许可证|考核合格证书|职称证书|(?:质量管理体系|职业健康安全管理体系|环境管理体系)认证证书).*"))return List.of();
        if(text.matches("(?s).*(?:查询截图|查询结果|证书列表|证书详情).*"))return List.of();
        Map<String,Object> attrs=new LinkedHashMap<>();attrs.put("category",category);attrs.put("number",certificateNumber);attrs.put("numberField",numberLabel);
        Matcher grade=Pattern.compile("(?:资质等级|资格等级|等级)\\s*[:：]\\s*([^\\n|。；]{1,100})").matcher(text);
        if(grade.find()){String raw=grade.group(1).strip();Matcher rank=Pattern.compile("[甲乙丙丁一二三壹贰叁]级").matcher(raw);if(rank.find())attrs.put("grade",rank.group());}
        Matcher validity=PERIOD.matcher(text);List<String> labels=new ArrayList<>();while(validity.find())labels.add(validity.group());
        if(labels.size()==1){String field=labels.get(0);var values=DateFieldValues.read(text,field,2);if(!values.isEmpty()){attrs.put("validityField",field);if(values.size()==2)attrs.put("start",values.get(0).toString());attrs.put("end",values.get(values.size()-1).toString());}
            else if(Pattern.compile(Pattern.quote(field)+"[\\t :：]*(?:长期|永久)(?=[\\s。；;]|$)").matcher(text).find()){attrs.put("validityField",field);attrs.put("validitySemantics","LONG_TERM");}}
        attrs.put("bindingMethod","SINGLE_ORIGINAL_CERTIFICATE_RECORD");attrs.put("bindingVersion",DateFieldValues.VERSION);
        Fact fact=new Fact("N"+identity(source.id()+category+certificateNumber+"certificate-record-v1").substring(0,20),"CERTIFICATE",subject,"证书",certificateNumber,certificateNumber,"","","UNRESOLVED",List.of(source.id()),attrs);
        Map<String,Source> sources=Map.of(source.id(),source);
        return new ProofVerifier().issue(fact,sources)==null&&DomainBindings.issue(fact,sources,List.of())==null?List.of(fact):List.of();
    }
}
