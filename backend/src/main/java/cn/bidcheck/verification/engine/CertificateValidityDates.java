package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.verification.source.DateFieldValues;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.date;

/** Date semantics are derived from the selected original certificate, never supplied by a
 * model. Expiry-only evidence does not invent a start date or an unrequested issuance duty. */
final class CertificateValidityDates {
    static final String VERSION="certificate-date-semantics-3-conjoined-original-limits";
    private static final String DATE="(?:19|20)[0-9]{2}\\s*[年./-]\\s*[0-9]{1,2}\\s*[月./-]\\s*[0-9]{1,2}\\s*日?";
    private static final Pattern ISSUE=Pattern.compile("(?m)^[\\t ]*(?:#{1,6}[\\t ]*)?(?:发证日期|签发日期|颁发日期|核发日期|批准日期|生效日期)[\\t ]*[:：][\\t ]*("+DATE+")[\\t ]*$");
    private static final Pattern AUTHORITY_DATE=Pattern.compile("(?m)^[\\t ]*发证机关\\s*[:：][\\t ]*(?:\\r?\\n[\\t ]*)*("+DATE+")[\\t ]*$");
    private static final Pattern CATEGORY_LIMIT=Pattern.compile("(?:上述|标注|部分|特定)[^。\\n]{0,45}资质[^。\\n]{0,45}有效期[\\t :：]*至[\\t ]*("+DATE+")");
    private static final Pattern NUMBER=Pattern.compile("(?:证书编号|证书号码|电子证书编号|注册编号|注册证号|资格证号|(?<![\\p{IsHan}])编号)\\s*[:：]\\s*([^\\s;；，,。|<>]{2,80})");
    private CertificateValidityDates(){}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Result{
        private final Boolean valid;
        private final String code;
        private final String reason;
        private final Map<String,Object> proof;
        @com.fasterxml.jackson.annotation.JsonCreator
        Result(@com.fasterxml.jackson.annotation.JsonProperty("valid") Boolean valid, @com.fasterxml.jackson.annotation.JsonProperty("code") String code, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason, @com.fasterxml.jackson.annotation.JsonProperty("proof") Map<String,Object> proof) { this.valid = valid; this.code = code; this.reason = reason; this.proof = proof; }
        public Boolean valid() { return valid; }
        public String code() { return code; }
        public String reason() { return reason; }
        public Map<String,Object> proof() { return proof; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Result that = (Result) other; return java.util.Objects.equals(this.valid, that.valid) && java.util.Objects.equals(this.code, that.code) && java.util.Objects.equals(this.reason, that.reason) && java.util.Objects.equals(this.proof, that.proof); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.valid); hash = 31 * hash + java.util.Objects.hashCode(this.code); hash = 31 * hash + java.util.Objects.hashCode(this.reason); hash = 31 * hash + java.util.Objects.hashCode(this.proof); return hash; }
        @Override public String toString() { return "Result[" + "valid=" + valid + ", code=" + code + ", reason=" + reason + ", proof=" + proof + "]"; }
boolean complete(){return valid!=null;}}

    static Result fact(Fact fact,EvidenceCorpus corpus,String field,LocalDate reference){
        return fact(fact,corpus,field,reference,false);
    }
    static Result fact(Fact fact,EvidenceCorpus corpus,String field,LocalDate reference,boolean requireIssuance){
        return fact(fact,corpus,field,reference,requireIssuance,cn.bidcheck.compat.Java8Api.listOf());
    }
    static Result fact(Fact fact,EvidenceCorpus corpus,String field,LocalDate reference,boolean requireIssuance,List<Map<String,Object>> layout){
        String sourceIssue=new ProofVerifier().issue(fact,corpus.byId);if(sourceIssue==null)sourceIssue=DomainBindings.issue(fact,corpus.byId,layout);
        if(sourceIssue!=null)return pending("CERTIFICATE_DATE_SOURCE_UNPROVEN","日期事实尚未通过同证原文校验，不能只凭候选到期日期计算有效",linked("dateContractVersion",VERSION,"sourceRejection",sourceIssue,"factId",fact.id()));
        LocalDate start=date(fact.attributes().get("start")),end=date(fact.attributes().get("end"));
        List<Source> original=sameCertificateSources(fact,corpus,layout);
        List<Map<String,Object>> issuance=new ArrayList<>();Set<LocalDate> issued=new LinkedHashSet<>();
        for(Source source:original)readIssue(source.evidence().quote(),source,issued,issuance);
        boolean longTerm=start==null&&end==null&&original.stream().anyMatch(s->Pattern.compile(Pattern.quote(field)+"[\\t :：]*(?:长期|永久)(?=[\\s。；;]|$)").matcher(s.evidence().quote()).find());
        Result base;
        if(longTerm){Map<String,Object> proof=linked("dateContractVersion",VERSION,"semantics","LONG_TERM","field",field,"start","","end","","referenceDate",reference.toString(),"issuanceEvidence",issuance,"issueDateIsNotValidityStart",true);
            proof.put("issuanceRequiredByRule",requireIssuance);
            if(issued.size()>1)base=pending("CERTIFICATE_DATE_CONFLICT","同证出现多个不同发行日期，须核对证书版本",proof);
            else if(issued.isEmpty()&&requireIssuance)base=pending("CERTIFICATE_ISSUANCE_REQUIRED","本项明确要求发行时点，尚未取得同证原文发行日期",proof);
            else if(issued.isEmpty()){proof.put("issuanceStatus","NOT_STATED_NOT_REQUIRED_FOR_EXPIRY_CHECK");base=new Result(true,"","",proof);}
            else{LocalDate issue=issued.iterator().next();proof.put("issuedOn",issue.toString());base=new Result(!reference.isBefore(issue),"","",proof);}
        }else base=period(start,end,issued,reference,field,issuance,requireIssuance);
        if(!base.complete())return base;
        Map<String,Object> proof=new LinkedHashMap<>(base.proof());
        boolean valid=Boolean.TRUE.equals(base.valid());List<Map<String,Object>> extra=new ArrayList<>();
        // A known false mandatory period already disproves the conjunction. A category note
        // cannot conceal an electronic-use interval that starts after the tender event.
        List<Map<String,Object>> unresolvedCategoryLimits=new ArrayList<>();
        for(Source source:original){String text=source.evidence().quote();
            for(String label:cn.bidcheck.compat.Java8Api.listOf("使用有效期", "使用期限")){
                if(!text.contains(label)||field.equals(label))continue;
                List<LocalDate> dates=DateFieldValues.read(text,label,2);
                if(dates.size()!=2||dates.get(1).isBefore(dates.get(0)))return pending("CERTIFICATE_USE_PERIOD_REQUIRED","同一证书另有使用期限但起止范围尚未证明",proof);
                boolean inside=!reference.isBefore(dates.get(0))&&!reference.isAfter(dates.get(1));valid&=inside;
                extra.add(linked("semantics","USE_PERIOD","field",label,"start",dates.get(0).toString(),"end",dates.get(1).toString(),"valid",inside,"sourceId",source.id()));
            }
            if(text.matches("(?s).*(?:上述|标注|部分|特定)[^。\\n]{0,45}资质[^。\\n]{0,45}有效期.*")){
                Matcher limited=CATEGORY_LIMIT.matcher(text);boolean found=false;
                while(limited.find()){
                    found=true;LocalDate endLimit=date(limited.group(1).replaceAll("\\s+",""));
                    // Only an explicit expiry ceiling is usable without colour/category mapping.
                    // Before this ceiling both possible applicability branches remain valid;
                    // after it, the specific category must be resolved rather than assumed.
                    boolean universallyWithin=endLimit!=null&&!reference.isAfter(endLimit);
                    Map<String,Object> restriction=linked("semantics","CATEGORY_EXPIRY_LIMIT","end",endLimit==null?"":endLimit.toString(),"sourceId",source.id(),"quote",limited.group(),"categoryApplicability","UNRESOLVED","allPossibleBranchesCoverReference",universallyWithin,"doesNotExtendMainExpiry",true);
                    extra.add(restriction);if(!universallyWithin)unresolvedCategoryLimits.add(restriction);
                }
                if(!found)unresolvedCategoryLimits.add(linked("sourceId",source.id(),"reason","CATEGORY_LIMIT_NOT_AN_EXPLICIT_EXPIRY_CEILING"));
            }
        }
        proof.put("additionalApplicablePeriods",extra);
        if(!unresolvedCategoryLimits.isEmpty()){
            proof.put("unresolvedCategoryLimits",unresolvedCategoryLimits);
            if(valid)return pending("CATEGORY_SPECIFIC_PERIOD_REQUIRED","参考日期已超过可能适用的资质类别期限或备注日期不清，须对应本项类别后计算，不能用整证最晚日期替代",proof);
            proof.put("decisionBasis","KNOWN_MANDATORY_PERIOD_FALSE_DESPITE_UNRESOLVED_CATEGORY_RESTRICTION");
        }
        return new Result(valid,"","",cn.bidcheck.compat.Java8Api.mapCopyOf(proof));
    }

    static Result record(CertificateSourceComparison.Record record,String suffix,LocalDate reference){
        Set<LocalDate> issueDates=new LinkedHashSet<>();List<Map<String,Object>> evidence=new ArrayList<>();
        cn.bidcheck.verification.engine.CertificateSourceComparison.Field issue=record.fields().get("ISSUE_DATE");if(issue!=null&&date(issue.value())!=null){issueDates.add(date(issue.value()));evidence.add(issue.audit());}
        return period(date(record.value("VALIDITY_START"+suffix)),date(record.value("VALIDITY_END"+suffix)),issueDates,reference,
                cn.bidcheck.compat.Java8Api.isBlank(suffix)?"有效期":"注册专业有效期"+suffix,evidence,false);
    }

    private static Result period(LocalDate start,LocalDate end,Set<LocalDate> issued,LocalDate reference,String field,List<Map<String,Object>> issuance,boolean requireIssuance){
        Map<String,Object> proof=linked("dateContractVersion",VERSION,"semantics",start==null?"EXPIRY_DATE":"CLOSED_INTERVAL",
                "field",field,"start",start==null?"":start.toString(),"end",end==null?"":end.toString(),"referenceDate",reference.toString(),"issuanceEvidence",issuance,
                "issueDateIsNotValidityStart",true,"issuanceRequiredByRule",requireIssuance);
        if(end==null||start!=null&&end.isBefore(start))return pending("VALIDITY_RANGE_REQUIRED","适用有效期日期缺失或范围倒置",proof);
        if(start!=null)return new Result(!reference.isBefore(start)&&!reference.isAfter(end),"","",proof);
        // An explicit past expiry is sufficient to disprove validity even when issue is absent.
        if(reference.isAfter(end))return new Result(false,"","",proof);
        if(issued.size()>1)return pending("CERTIFICATE_DATE_CONFLICT","同证出现多个不同发行日期，须核对原图或证书版本",proof);
        if(issued.isEmpty()){
            if(requireIssuance)return pending("CERTIFICATE_ISSUANCE_REQUIRED","本项明确要求发行时点，尚未取得同证原文发行日期",proof);
            proof.put("issuanceStatus","NOT_STATED_NOT_REQUIRED_FOR_EXPIRY_CHECK");proof.put("proofScope","EXPIRY_AT_REFERENCE_DATE_ONLY");
            return new Result(true,"","",proof);
        }
        LocalDate issue=issued.iterator().next();proof.put("issuedOn",issue.toString());
        if(issue.isAfter(end))return pending("CERTIFICATE_DATE_CONFLICT","同证发行日期晚于到期日期，须核对原图或证书版本",proof);
        return new Result(!reference.isBefore(issue),"","",proof);
    }
    private static Result pending(String code,String reason,Map<String,Object> proof){return new Result(null,code,reason,cn.bidcheck.compat.Java8Api.mapCopyOf(proof));}
    private static List<Source> sameCertificateSources(Fact fact,EvidenceCorpus corpus,List<Map<String,Object>> layout){
        if(new ProofVerifier().issue(fact,corpus.byId)!=null||DomainBindings.issue(fact,corpus.byId,layout)!=null)return cn.bidcheck.compat.Java8Api.listOf();
        cn.bidcheck.verification.engine.CertificateSourceComparison.Record record=CertificateRecordBindings.matching(fact,corpus.byId);
        if(record!=null)return record.sources();
        List<Source> result=new ArrayList<>();
        for(String id:fact.sourceIds()){
            Source source=corpus.byId.get(id);if(source==null)continue;String text=source.evidence().quote();
            if(!text.contains(fact.subject())||!ProofVerifier.contains(text,str(fact.attributes(),"category")))continue;
            Set<String> numbers=new LinkedHashSet<>();Matcher n=NUMBER.matcher(text);while(n.find())numbers.add(PersonnelCertificateRecords.normalNumber(n.group(1)));
            if(!numbers.equals(cn.bidcheck.compat.Java8Api.setOf(PersonnelCertificateRecords.normalNumber(str(fact.attributes(),"number")))))continue;
            result.add(source);
        }return result;
    }
    private static void readIssue(String text,Source source,Set<LocalDate> dates,List<Map<String,Object>> evidence){
        for(Pattern pattern:cn.bidcheck.compat.Java8Api.listOf(ISSUE, AUTHORITY_DATE)){
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
