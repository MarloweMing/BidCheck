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
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Record{
        private final String body;
        private final String subject;
        private final String number;
        private final String numberField;
        private final String grade;
        private final String profession;
        @com.fasterxml.jackson.annotation.JsonCreator
        Record(@com.fasterxml.jackson.annotation.JsonProperty("body") String body, @com.fasterxml.jackson.annotation.JsonProperty("subject") String subject, @com.fasterxml.jackson.annotation.JsonProperty("number") String number, @com.fasterxml.jackson.annotation.JsonProperty("numberField") String numberField, @com.fasterxml.jackson.annotation.JsonProperty("grade") String grade, @com.fasterxml.jackson.annotation.JsonProperty("profession") String profession) { this.body = body; this.subject = subject; this.number = number; this.numberField = numberField; this.grade = grade; this.profession = profession; }
        public String body() { return body; }
        public String subject() { return subject; }
        public String number() { return number; }
        public String numberField() { return numberField; }
        public String grade() { return grade; }
        public String profession() { return profession; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Record that = (Record) other; return java.util.Objects.equals(this.body, that.body) && java.util.Objects.equals(this.subject, that.subject) && java.util.Objects.equals(this.number, that.number) && java.util.Objects.equals(this.numberField, that.numberField) && java.util.Objects.equals(this.grade, that.grade) && java.util.Objects.equals(this.profession, that.profession); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.body); hash = 31 * hash + java.util.Objects.hashCode(this.subject); hash = 31 * hash + java.util.Objects.hashCode(this.number); hash = 31 * hash + java.util.Objects.hashCode(this.numberField); hash = 31 * hash + java.util.Objects.hashCode(this.grade); hash = 31 * hash + java.util.Objects.hashCode(this.profession); return hash; }
        @Override public String toString() { return "Record[" + "body=" + body + ", subject=" + subject + ", number=" + number + ", numberField=" + numberField + ", grade=" + grade + ", profession=" + profession + "]"; }
}
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
    private static String field(String text,String labels){Matcher matcher=Pattern.compile("(?m)^[ \\t]*(?:"+labels+")[ \\t:：]+([^\\r\\n|;；:：]{1,60})[ \\t]*\\r?$").matcher(text);if(!matcher.find())return "";String value=cn.bidcheck.compat.Java8Api.strip(matcher.group(1));return matcher.find()?"":value;}
    static String block(String text,Fact fact){String category=str(fact.attributes(),"category");if(!cn.bidcheck.compat.Java8Api.setOf("职称", "职称证书").contains(category))return "";Record record=read(text);return record!=null&&record.subject().equals(fact.subject())&&record.number().equals(str(fact.attributes(),"number"))?record.body():"";}
    static List<Fact> extract(Rule rule,Source source){
        String category=str(rule.parameters(),"category");if(!cn.bidcheck.compat.Java8Api.setOf("职称", "职称证书").contains(category))return cn.bidcheck.compat.Java8Api.listOf();Record record=read(source.evidence().quote());if(record==null)return cn.bidcheck.compat.Java8Api.listOf();
        Map<String,Object> attrs=linked("category",category,"number",record.number(),"numberField",record.numberField(),"bindingMethod","SINGLE_PROFESSIONAL_TITLE_CERTIFICATE","bindingVersion","professional-title-fields-1");
        if(!cn.bidcheck.compat.Java8Api.isBlank(record.grade()))attrs.put("grade",record.grade());if(!cn.bidcheck.compat.Java8Api.isBlank(record.profession()))attrs.put("profession",record.profession());
        Fact fact=new Fact("N"+identity(source.id()+category+record.number()+"professional-title-fields-1").substring(0,20),"CERTIFICATE",record.subject(),"证书",record.number(),record.number(),"","","UNRESOLVED",cn.bidcheck.compat.Java8Api.listOf(source.id()),attrs);Map<String,Source> sources=cn.bidcheck.compat.Java8Api.mapOf(source.id(), source);
        return new ProofVerifier().issue(fact,sources)==null&&DomainBindings.issue(fact,sources,cn.bidcheck.compat.Java8Api.listOf())==null?cn.bidcheck.compat.Java8Api.listOf(fact):cn.bidcheck.compat.Java8Api.listOf();
    }
}
