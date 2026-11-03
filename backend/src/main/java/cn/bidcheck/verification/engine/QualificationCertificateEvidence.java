package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.verification.source.DateFieldValues;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Resolve the original qualification certificate's printed header, not the last date on a page. */
final class QualificationCertificateEvidence {
    static final String VERSION="qualification-header-date-1";
    private static final Pattern NUMBER=Pattern.compile("(证书编号|资质证书编号)\\s*[:：]\\s*([A-Za-z0-9()（）/-]{4,80})");
    private static final Pattern PERIOD=Pattern.compile("证书有效日期|证书有效期|有效截止日期|有效期限|有效期至|有效期");
    private static final Pattern BOUNDARY=Pattern.compile("资质类别|资质等级|备注|使用期限|使用有效期|注册有效期|[。；;]");
    private QualificationCertificateEvidence(){}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Resolution{
        private final Fact fact;
        private final String field;
        private final Map<String,Object> proof;
        @com.fasterxml.jackson.annotation.JsonCreator
        Resolution(@com.fasterxml.jackson.annotation.JsonProperty("fact") Fact fact, @com.fasterxml.jackson.annotation.JsonProperty("field") String field, @com.fasterxml.jackson.annotation.JsonProperty("proof") Map<String,Object> proof) { this.fact = fact; this.field = field; this.proof = proof; }
        public Fact fact() { return fact; }
        public String field() { return field; }
        public Map<String,Object> proof() { return proof; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Resolution that = (Resolution) other; return java.util.Objects.equals(this.fact, that.fact) && java.util.Objects.equals(this.field, that.field) && java.util.Objects.equals(this.proof, that.proof); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.fact); hash = 31 * hash + java.util.Objects.hashCode(this.field); hash = 31 * hash + java.util.Objects.hashCode(this.proof); return hash; }
        @Override public String toString() { return "Resolution[" + "fact=" + fact + ", field=" + field + ", proof=" + proof + "]"; }
}

    static Optional<Resolution> resolve(Fact selected,Source source,EvidenceCorpus corpus){
        if(source==null||!selected.kind().equals("CERTIFICATE")||!DomainBindings.certificateIdentity(selected))return Optional.empty();
        String text=source.evidence().quote(),number=str(selected.attributes(),"number");
        if(!text.contains("资质证书")||text.matches("(?s).*(?:查询截图|查询结果|证书列表|资质查询).*"))return Optional.empty();
        Matcher numbered=NUMBER.matcher(text);if(!numbered.find())return Optional.empty();
        String numberLabel=numbered.group(1);int numberStart=numbered.start(),afterNumber=numbered.end();
        if(!CertificateEvidenceRecords.normalizeCertificateNumber(numbered.group(2)).equals(CertificateEvidenceRecords.normalizeCertificateNumber(number))||numbered.find())return Optional.empty();
        String header=text.substring(afterNumber,Math.min(text.length(),afterNumber+180));Matcher boundary=BOUNDARY.matcher(header);if(boundary.find())header=header.substring(0,boundary.start());
        Matcher period=PERIOD.matcher(header);if(!period.find())return Optional.empty();String field=period.group();int fieldStart=afterNumber+period.start();
        if(period.find())return Optional.empty();
        java.util.List<java.time.LocalDate> headerDates=DateFieldValues.read(header,field,2);java.util.List<java.time.LocalDate> allDates=DateFieldValues.read(text,field,2);
        if(headerDates.isEmpty()||!headerDates.equals(allDates))return Optional.empty();
        Map<String,Object> attrs=linked("category",str(selected.attributes(),"category"),"number",number,"numberField",numberLabel,
                "validityField",field,"end",headerDates.get(headerDates.size()-1).toString(),"bindingMethod","SELECTED_QUALIFICATION_ORIGINAL_HEADER","bindingVersion",VERSION);
        if(headerDates.size()==2)attrs.put("start",headerDates.get(0).toString());
        for(String key:cn.bidcheck.compat.Java8Api.listOf("grade", "employer"))if(!cn.bidcheck.compat.Java8Api.isBlank(str(selected.attributes(),key)))attrs.put(key,str(selected.attributes(),key));
        Fact fact=new Fact("N"+identity(source.id()+number+attrs+VERSION).substring(0,20),"CERTIFICATE",selected.subject(),"证书",number,number,"","",selected.scope(),cn.bidcheck.compat.Java8Api.listOf(source.id()),cn.bidcheck.compat.Java8Api.mapCopyOf(attrs));
        if(new ProofVerifier().issue(fact,corpus.byId)!=null||DomainBindings.issue(fact,corpus.byId,cn.bidcheck.compat.Java8Api.listOf())!=null)return Optional.empty();
        Matcher range=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(source.evidence().anchor());if(!range.matches())return Optional.empty();int base=Integer.parseInt(range.group(1)),end=afterNumber+header.length();
        Evidence e=source.evidence();Evidence quote=new Evidence(e.documentId(),e.documentName(),e.page(),e.locationType(),text.substring(numberStart,end),"chars:"+(base+numberStart)+"-"+(base+end),e.confidence());
        return Optional.of(new Resolution(fact,field,linked("version",VERSION,"selectedFactId",selected.id(),"sourceId",source.id(),"source",quote,
                "validityField",field,"fieldOffset",base+fieldStart,"dateValues",cn.bidcheck.compat.Java8Api.toList(headerDates.stream().map(Object::toString)),"selectionBasis","SAME_CERTIFICATE_NUMBER_HEADER_BEFORE_CATEGORY_LIST",
                "retainsUsePeriodAndCategoryRestrictions",true,"doesNotSelectLatestDate",true)));
    }
}
