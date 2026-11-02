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
    record Resolution(Fact fact,String field,Map<String,Object> proof){}

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
        var headerDates=DateFieldValues.read(header,field,2);var allDates=DateFieldValues.read(text,field,2);
        if(headerDates.isEmpty()||!headerDates.equals(allDates))return Optional.empty();
        Map<String,Object> attrs=linked("category",str(selected.attributes(),"category"),"number",number,"numberField",numberLabel,
                "validityField",field,"end",headerDates.get(headerDates.size()-1).toString(),"bindingMethod","SELECTED_QUALIFICATION_ORIGINAL_HEADER","bindingVersion",VERSION);
        if(headerDates.size()==2)attrs.put("start",headerDates.get(0).toString());
        for(String key:List.of("grade","employer"))if(!str(selected.attributes(),key).isBlank())attrs.put(key,str(selected.attributes(),key));
        Fact fact=new Fact("N"+identity(source.id()+number+attrs+VERSION).substring(0,20),"CERTIFICATE",selected.subject(),"证书",number,number,"","",selected.scope(),List.of(source.id()),Map.copyOf(attrs));
        if(new ProofVerifier().issue(fact,corpus.byId)!=null||DomainBindings.issue(fact,corpus.byId,List.of())!=null)return Optional.empty();
        Matcher range=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(source.evidence().anchor());if(!range.matches())return Optional.empty();int base=Integer.parseInt(range.group(1)),end=afterNumber+header.length();
        Evidence e=source.evidence();Evidence quote=new Evidence(e.documentId(),e.documentName(),e.page(),e.locationType(),text.substring(numberStart,end),"chars:"+(base+numberStart)+"-"+(base+end),e.confidence());
        return Optional.of(new Resolution(fact,field,linked("version",VERSION,"selectedFactId",selected.id(),"sourceId",source.id(),"source",quote,
                "validityField",field,"fieldOffset",base+fieldStart,"dateValues",headerDates.stream().map(Object::toString).toList(),"selectionBasis","SAME_CERTIFICATE_NUMBER_HEADER_BEFORE_CATEGORY_LIST",
                "retainsUsePeriodAndCategoryRestrictions",true,"doesNotSelectLatestDate",true)));
    }
}
