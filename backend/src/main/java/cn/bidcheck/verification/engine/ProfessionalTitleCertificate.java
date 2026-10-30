package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** The fields printed on one explicit professional-title certificate, without tier inference. */
final class ProfessionalTitleCertificate {
    private ProfessionalTitleCertificate(){}
    private static final Pattern TITLE=Pattern.compile("(?m)^[ \\t#*]*职称证书[ \\t*]*\\r?$");
    private static final Pattern OWNER=Pattern.compile("(?m)^[ \\t]*(?:姓[ \\t]*名|持证人)[ \\t:：]+([\\p{IsHan}]{2,12})[ \\t]*\\r?$");
    private static final Pattern NUMBER=Pattern.compile("(?m)^[ \\t]*(证书管理号|证书编号)[ \\t:：]+([A-Za-z0-9][A-Za-z0-9/-]{3,79})[ \\t]*\\r?$");
    record Record(String body,String subject,String number,String numberField,String grade,String profession){}
    static Record read(String original){
        int marker=original.lastIndexOf("【PDF识别文字】");String text=marker<0?original:original.substring(marker+"【PDF识别文字】".length());
        if(text.matches("(?s).*(?:查询截图|查询结果|查询详情|证书详情|填写示例|示范文本|拟取得|尚未取得).*"))return null;
        Matcher title=TITLE.matcher(text);if(!title.find())return null;int start=title.start();if(title.find())return null;
        String body=text.substring(start);Matcher owner=OWNER.matcher(body),number=NUMBER.matcher(body);if(!owner.find()||!number.find())return null;
        String subject=owner.group(1),numberField=number.group(1),numberValue=number.group(2);if(owner.find()||number.find())return null;
        Matcher otherTitle=Pattern.compile("(?m)^[ \\t#*]*[^\\r\\n:：]{1,50}(?:证书|许可证|执照)[ \\t*]*\\r?$").matcher(body);
        while(otherTitle.find())if(!clean(otherTitle.group().replace("#","").replace("*","")).equals("职称证书"))return null;
        String grade=field(body,"资格名称|职称名称|职称级别|专业技术资格"),profession=field(body,"专业名称|评审专业名称|专业");
        if(!grade.matches("(?:正高级|副高级|高级|中级)(?:工程师)?|工程师|助理工程师"))grade="";
        return new Record(body,subject,numberValue,numberField,grade,profession);
    }
    private static String field(String text,String labels){Matcher matcher=Pattern.compile("(?m)^[ \\t]*(?:"+labels+")[ \\t:：]+([^\\r\\n|;；:：]{1,60})[ \\t]*\\r?$").matcher(text);if(!matcher.find())return "";String value=matcher.group(1).strip();return matcher.find()?"":value;}
    static String block(String text,Fact fact){String category=str(fact.attributes(),"category");if(!Set.of("职称","职称证书").contains(category))return "";Record record=read(text);return record!=null&&record.subject().equals(fact.subject())&&record.number().equals(str(fact.attributes(),"number"))?record.body():"";}
    static List<Fact> extract(Rule rule,Source source){
        String category=str(rule.parameters(),"category");if(!Set.of("职称","职称证书").contains(category))return List.of();Record record=read(source.evidence().quote());if(record==null)return List.of();
        Map<String,Object> attrs=linked("category",category,"number",record.number(),"numberField",record.numberField(),"bindingMethod","SINGLE_PROFESSIONAL_TITLE_CERTIFICATE","bindingVersion","professional-title-fields-1");
        if(!record.grade().isBlank())attrs.put("grade",record.grade());if(!record.profession().isBlank())attrs.put("profession",record.profession());
        Fact fact=new Fact("N"+identity(source.id()+category+record.number()+"professional-title-fields-1").substring(0,20),"CERTIFICATE",record.subject(),"证书",record.number(),record.number(),"","","UNRESOLVED",List.of(source.id()),attrs);Map<String,Source> sources=Map.of(source.id(),source);
        return new ProofVerifier().issue(fact,sources)==null&&DomainBindings.issue(fact,sources,List.of())==null?List.of(fact):List.of();
    }
}
