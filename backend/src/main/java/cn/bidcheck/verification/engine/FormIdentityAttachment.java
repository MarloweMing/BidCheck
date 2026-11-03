package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A form's paste-here box is a request for identity evidence, not text the bidder
 * must reproduce. The actual named person's card fields must appear in this same
 * bounded form; a heading, printed placeholder or another person's card is insufficient. */
final class FormIdentityAttachment {
    private FormIdentityAttachment(){}
    static boolean applies(String template,String form){
        if(!cn.bidcheck.compat.Java8Api.setOf("法定代表人身份证明", "法定代表人授权委托书").contains(TenderFormAudit.canonicalForm(form)))return false;
        String text=clean(template).replace("【文本框原文】","");
        return text.matches("(?:(?:法定代表人(?:[（(]负责人[）)])?|委托代理人)身份证复印件或扫描件贴于此处)+");
    }
    static Map<String,Object> verify(String template,String form,String original){
        String formatted=original.replace("**","").replace('（','(').replace('）',')').replace('：',':');
        // Remove only the known OCR display wrapper, retaining its entire literal value.
        formatted=formatted.replaceAll("\\\\\\(\\\\underline\\{\\\\text\\{([^{}]*)\\}\\}\\\\\\)","$1");
        String text=clean(formatted),cardText=cardRegion(formatted),card=clean(cardText);
        Set<String> requiredRoles=new LinkedHashSet<>();Matcher roles=Pattern.compile("(法定代表人(?:[（(]负责人[）)])?|委托代理人)身份证").matcher(template);
        while(roles.find())requiredRoles.add(roles.group(1).startsWith("法定代表人")?"法定代表人":"委托代理人");
        boolean agent=requiredRoles.equals(cn.bidcheck.compat.Java8Api.setOf("委托代理人")),authority=TenderFormAudit.canonicalForm(form).equals("法定代表人授权委托书");
        Pattern named=Pattern.compile(agent?"现委托([\\p{IsHan}·]{2,12})为我方代理人":authority?"本人([\\p{IsHan}·]{2,12})系":"姓名:([\\p{IsHan}·]{2,12})性别:");
        Set<String> people=new LinkedHashSet<>();Matcher name=named.matcher(text);while(name.find())people.add(name.group(1));
        Set<String> cardNames=new LinkedHashSet<>(),numbers=new LinkedHashSet<>();
        Matcher cardName=Pattern.compile("(?m)^[\\t ]*(?:[-*][\\t ]*)?姓名[\\t ]*:[\\t ]*([\\p{IsHan}·]{2,12})[\\t ]*\\r?$").matcher(cardText);while(cardName.find())cardNames.add(cardName.group(1));
        Matcher number=Pattern.compile("(?m)^[\\t ]*(?:[-*][\\t ]*)?公民身份号码[\\t ]*:[\\t ]*([0-9]{17}[0-9Xx])[\\t ]*\\r?$").matcher(cardText);while(number.find())numbers.add(number.group(1).toUpperCase(Locale.ROOT));
        boolean country=card.contains("中华人民共和国")&&card.contains("居民身份证");
        boolean issuing=Pattern.compile("签发机关:[^:。；;#-]{2,50}(?:公安局|公安分局|分局)").matcher(card).find();
        boolean period=Pattern.compile("有效期限:(?:19|20)[0-9]{2}[./年-][0-9]{1,2}[./月-][0-9]{1,2}(?:日)?(?:[-–—至]+)(?:(?:19|20)[0-9]{2}[./年-][0-9]{1,2}[./月-][0-9]{1,2}日?|长期)").matcher(card).find();
        boolean complete=applies(template,form)&&requiredRoles.size()==1&&people.size()==1&&cardNames.equals(people)&&numbers.size()==1&&country&&issuing&&period;
        return linked("complete",complete,"requiredRoles",requiredRoles,"requiredPerson",people.size()==1?people.iterator().next():"","namedPeople",people,
                "cardNames",cardNames,"identityFingerprint",numbers.size()==1?identity(numbers.iterator().next()):"",
                "cardTitlePresent",country,"issuingSideFieldsPresent",issuing&&period,
                "proofScope","SAME_BOUNDED_FORM_NAMED_PERSON_IDENTITY_FIELDS","provesActualSigning",false,
                "signatureAuthenticityCheckRequired",false);
    }
    /** Only the actual identity-card region supplies card fields. The form's declaration
     * and signature footer cannot substitute for a missing name on the card itself. */
    private static String cardRegion(String source){
        int start=-1,end=source.length(),offset=0,previousStart=0;String previous="";
        for(String line:source.split("\n",-1)){
            String heading=cn.bidcheck.compat.Java8Api.strip(line).replaceFirst("^#{1,6}[\\t ]*","");
            boolean marker=heading.matches("(?:中华人民共和国)?\\s*居民身份证")
                    ||heading.matches("身份证(?:正面|背面)(?:[（(].*[）)])?")
                    ||heading.matches("(?:法定代表人(?:[（(]负责人[）)])?|委托代理人)身份证复印件或扫描件贴于此处[（(](?:正面|背面)[）)]");
            if(start<0&&marker)start=cn.bidcheck.compat.Java8Api.strip(previous).equals("中华人民共和国")?previousStart:offset;
            else if(start>=0&&heading.matches("(?:说明|编制及签署要求[:：]?|投标人[（(]盖章[）)][:：].*|日期[:：].*|✅.*)")){end=offset;break;}
            previousStart=offset;previous=line;offset+=line.length()+1;
        }
        return start<0?"":source.substring(start,end);
    }
}
