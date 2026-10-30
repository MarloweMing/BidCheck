package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.verification.source.DateFieldValues;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.date;

/** Date semantics are derived from the selected original certificate, never supplied by a
 * model. An issue date proves that an expiry-only document existed; it is not its valid-from. */
final class CertificateValidityDates {
    static final String VERSION="certificate-date-semantics-1";
    private static final String DATE="(?:19|20)[0-9]{2}\\s*[年./-]\\s*[0-9]{1,2}\\s*[月./-]\\s*[0-9]{1,2}\\s*日?";
    private static final Pattern ISSUE=Pattern.compile("(?m)^[\\t ]*(?:#{1,6}[\\t ]*)?(?:发证日期|签发日期|颁发日期|核发日期|批准日期|生效日期)[\\t ]*[:：][\\t ]*("+DATE+")[\\t ]*$");
    private static final Pattern AUTHORITY_DATE=Pattern.compile("(?m)^[\\t ]*发证机关\\s*[:：][\\t ]*(?:\\r?\\n[\\t ]*)*("+DATE+")[\\t ]*$");
    private static final Pattern NUMBER=Pattern.compile("(?:证书编号|证书号码|电子证书编号|注册编号|注册证号|资格证号|(?<![\\p{IsHan}])编号)\\s*[:：]\\s*([^\\s;；，,。|<>]{2,80})");
    private CertificateValidityDates(){}
    record Result(Boolean valid,String code,String reason,Map<String,Object> proof){boolean complete(){return valid!=null;}}

    static Result fact(Fact fact,EvidenceCorpus corpus,String field,LocalDate reference){
        LocalDate start=date(fact.attributes().get("start")),end=date(fact.attributes().get("end"));
        List<Source> original=sameCertificateSources(fact,corpus);
        List<Map<String,Object>> issuance=new ArrayList<>();Set<LocalDate> issued=new LinkedHashSet<>();
        for(Source source:original)readIssue(source.evidence().quote(),source,issued,issuance);
        boolean longTerm=start==null&&end==null&&original.stream().anyMatch(s->Pattern.compile(Pattern.quote(field)+"[\\t :：]*(?:长期|永久)(?=[\\s。；;]|$)").matcher(s.evidence().quote()).find());
        Result base;
        if(longTerm){Map<String,Object> proof=linked("dateContractVersion",VERSION,"semantics","LONG_TERM","field",field,"start","","end","","referenceDate",reference.toString(),"issuanceEvidence",issuance,"issueDateIsNotValidityStart",true);
            if(issued.size()!=1)base=pending("CERTIFICATE_ISSUANCE_REQUIRED","长期有效证照仍须证明同一证件在招标时点已经发行",proof);
            else{LocalDate issue=issued.iterator().next();proof.put("issuedOn",issue.toString());base=new Result(!reference.isBefore(issue),"","",proof);}
        }else base=period(start,end,issued,reference,field,issuance);
        if(!base.complete())return base;
        Map<String,Object> proof=new LinkedHashMap<>(base.proof());
        boolean valid=Boolean.TRUE.equals(base.valid());List<Map<String,Object>> extra=new ArrayList<>();
        // Explicit electronic use limits constrain this document in addition to its general
        // expiry. Ambiguous category-specific restrictions require their own scoped proof.
        for(Source source:original){String text=source.evidence().quote();
            if(text.matches("(?s).*(?:上述|标注|部分|特定)[^。\\n]{0,45}资质[^。\\n]{0,45}有效期.*"))
                return pending("CATEGORY_SPECIFIC_PERIOD_REQUIRED","证书存在按资质类别适用的期限限制，须对应本项类别后计算，不能用整证最晚日期替代",proof);
            for(String label:List.of("使用有效期","使用期限")){
                if(!text.contains(label)||field.equals(label))continue;
                List<LocalDate> dates=DateFieldValues.read(text,label,2);
                if(dates.size()!=2||dates.get(1).isBefore(dates.get(0)))return pending("CERTIFICATE_USE_PERIOD_REQUIRED","同一证书另有使用期限但起止范围尚未证明",proof);
                boolean inside=!reference.isBefore(dates.get(0))&&!reference.isAfter(dates.get(1));valid&=inside;
                extra.add(linked("semantics","USE_PERIOD","field",label,"start",dates.get(0).toString(),"end",dates.get(1).toString(),"valid",inside,"sourceId",source.id()));
            }
        }
        proof.put("additionalApplicablePeriods",extra);
        return new Result(valid,"","",Map.copyOf(proof));
    }

    static Result record(CertificateSourceComparison.Record record,String suffix,LocalDate reference){
        Set<LocalDate> issueDates=new LinkedHashSet<>();List<Map<String,Object>> evidence=new ArrayList<>();
        var issue=record.fields().get("ISSUE_DATE");if(issue!=null&&date(issue.value())!=null){issueDates.add(date(issue.value()));evidence.add(issue.audit());}
        return period(date(record.value("VALIDITY_START"+suffix)),date(record.value("VALIDITY_END"+suffix)),issueDates,reference,
                suffix.isBlank()?"有效期":"注册专业有效期"+suffix,evidence);
    }

    private static Result period(LocalDate start,LocalDate end,Set<LocalDate> issued,LocalDate reference,String field,List<Map<String,Object>> issuance){
        Map<String,Object> proof=linked("dateContractVersion",VERSION,"semantics",start==null?"EXPIRY_DATE":"CLOSED_INTERVAL",
                "field",field,"start",start==null?"":start.toString(),"end",end==null?"":end.toString(),"referenceDate",reference.toString(),"issuanceEvidence",issuance,
                "issueDateIsNotValidityStart",true);
        if(end==null||start!=null&&end.isBefore(start))return pending("VALIDITY_RANGE_REQUIRED","适用有效期日期缺失或范围倒置",proof);
        if(start!=null)return new Result(!reference.isBefore(start)&&!reference.isAfter(end),"","",proof);
        // An explicit past expiry is sufficient to disprove validity even when issue is absent.
        if(reference.isAfter(end))return new Result(false,"","",proof);
        if(issued.size()!=1)return pending("CERTIFICATE_ISSUANCE_REQUIRED","证书仅记载截止日期；须由同一证号、同一文书的发行或生效日期确认在招标时点已存在，不虚构有效期开始日期",proof);
        LocalDate issue=issued.iterator().next();proof.put("issuedOn",issue.toString());
        if(issue.isAfter(end))return pending("CERTIFICATE_DATE_CONFLICT","同证发行日期晚于到期日期，须核对原图或证书版本",proof);
        return new Result(!reference.isBefore(issue),"","",proof);
    }
    private static Result pending(String code,String reason,Map<String,Object> proof){return new Result(null,code,reason,Map.copyOf(proof));}
    private static List<Source> sameCertificateSources(Fact fact,EvidenceCorpus corpus){
        if(new ProofVerifier().issue(fact,corpus.byId)!=null||DomainBindings.issue(fact,corpus.byId,List.of())!=null)return List.of();
        var record=CertificateRecordBindings.matching(fact,corpus.byId);
        if(record!=null)return record.sources();
        List<Source> result=new ArrayList<>();
        for(String id:fact.sourceIds()){
            Source source=corpus.byId.get(id);if(source==null)continue;String text=source.evidence().quote();
            if(!text.contains(fact.subject())||!ProofVerifier.contains(text,str(fact.attributes(),"category")))continue;
            Set<String> numbers=new LinkedHashSet<>();Matcher n=NUMBER.matcher(text);while(n.find())numbers.add(PersonnelCertificateRecords.normalNumber(n.group(1)));
            if(!numbers.equals(Set.of(PersonnelCertificateRecords.normalNumber(str(fact.attributes(),"number")))))continue;
            result.add(source);
        }return result;
    }
    private static void readIssue(String text,Source source,Set<LocalDate> dates,List<Map<String,Object>> evidence){
        for(Pattern pattern:List.of(ISSUE,AUTHORITY_DATE)){
            Matcher m=pattern.matcher(text);while(m.find()){
                // Explanatory OCR prose cannot serve as an issuing-date assertion.
                String line=text.substring(Math.max(0,text.lastIndexOf('\n',m.start())+1),Math.min(text.length(),text.indexOf('\n',m.end())<0?text.length():text.indexOf('\n',m.end())));
                if(line.matches("(?s).*(?:推断|示例|假设|占位|说明|描述|叠加|后期|并非).*"))continue;
                LocalDate date=date(m.group(1).replaceAll("\\s+",""));if(date==null)continue;dates.add(date);
                Evidence origin=source.evidence();Matcher range=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(origin.anchor());if(!range.matches())continue;
                int base=Integer.parseInt(range.group(1));Evidence location=new Evidence(origin.documentId(),origin.documentName(),origin.page(),origin.locationType(),m.group(),"chars:"+(base+m.start())+"-"+(base+m.end()),origin.confidence());
                evidence.add(linked("date",date.toString(),"sourceId",source.id(),"source",location));
            }
        }
    }
}
