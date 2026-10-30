package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Extracted facts are candidates until their typed values and local source binding have been checked. */
final class ProofVerifier {
    private final Map<String,Source> trustedCorpus;
    ProofVerifier(){this(Map.of());}
    ProofVerifier(Map<String,Source> trustedCorpus){this.trustedCorpus=trustedCorpus;}
    static final Set<String> KINDS=Set.of("MATERIAL","DECLARATION","SECTION","PERSON_ROLE","CERTIFICATE","DATE_RANGE","MONTHS","RELATION","NUMBER","CONTRACT","FIELD");
    record Verified(List<Fact> facts,List<Map<String,Object>> rejected){}
    Verified verify(JsonNode root,List<Source> sent){return verify(root,sent,List.of());}
    Verified verify(JsonNode root,List<Source> sent,List<Map<String,Object>> layout){Map<String,Source> sources=trustedCorpus instanceof CertificateRecordBindings.SourceIndex index?index.subset(sent):new HashMap<>();if(!(trustedCorpus instanceof CertificateRecordBindings.SourceIndex))sent.forEach(s->sources.put(s.id(),s));List<Fact> good=new ArrayList<>();List<Map<String,Object>> rejected=new ArrayList<>();
        if(!root.path("facts").isArray())return new Verified(List.of(),List.of(Map.of("code","FACT_PROTOCOL_INVALID")));
        int n=0;for(JsonNode row:root.path("facts")){if(++n>120){rejected.add(Map.of("code","FACT_BUDGET_EXCEEDED"));break;}
            String id="F"+identity(row.toString()).substring(0,20);List<String> refs=new ArrayList<>();if(row.path("sourceIds").isArray())row.path("sourceIds").forEach(v->{if(v.isTextual())refs.add(v.asText());});
            Map<String,Object> attrs=row.path("attributes").isObject()?new ObjectMapper().convertValue(row.path("attributes"),Map.class):new LinkedHashMap<>();
            attrs.remove("structureProof"); // Computed below from the server's current layout, never accepted from model/cached JSON.
            attrs.remove("documentScope");attrs.remove("documentScopeEvidence"); // Document ownership is computed from current original sources.
            Fact f=new Fact(id,row.path("kind").asText(),row.path("subject").asText(),row.path("field").asText(),row.path("rawValue").asText(),row.path("value").asText(),row.path("unit").asText(),row.path("basis").asText(),row.path("scope").asText("UNRESOLVED"),List.copyOf(refs),attrs);
            String issue=issue(f,sources);if("AMOUNT_OR_NUMBER_BINDING_INVALID".equals(issue)&&f.kind().equals("NUMBER")&&LayoutBinding.amount(f,layout,sources))issue=null;
            boolean tableRecord=SocialInsuranceEvidenceRecords.claims(f)&&SocialInsuranceEvidenceRecords.issue(f,sources)==null;
            if(tableRecord)f=SocialInsuranceEvidenceRecords.verified(f,sources);
            if(Set.of("PERSON_ROLE","CERTIFICATE","DATE_RANGE","MONTHS").contains(f.kind())&&!tableRecord&&!CertificateRecordBindings.matches(f,sources)&&(!layout.isEmpty()||!strings(f.attributes().get("cellIds")).isEmpty())){
                var structured=LayoutBinding.structure(f,layout,sources);boolean requested=!strings(f.attributes().get("cellIds")).isEmpty();
                if(requested||!structured.rows().isEmpty()){
                    String binding=LayoutBinding.structuredIssue(f,structured);
                    if(issue==null||Set.of("PERSON_ROLE_BINDING_INVALID","DATE_FIELD_BINDING_INVALID","MONTH_ROW_UNPROVEN","SOCIAL_EMPLOYER_LINK_UNPROVEN","CERTIFICATE_SUBJECT_FIELD_BINDING_INVALID").contains(issue))issue=binding;
                    if(issue==null){Map<String,Object> computed=new LinkedHashMap<>(f.attributes());computed.put("structureProof",structured.proof());f=new Fact(f.id(),f.kind(),f.subject(),f.field(),f.rawValue(),f.value(),f.unit(),f.basis(),f.scope(),f.sourceIds(),computed);}
                }
            }
            if(issue==null)issue=DomainBindings.issue(f,sources,layout);
            if(issue==null){
                if(f.kind().equals("PERSON_ROLE")||f.kind().equals("RELATION")&&(DomainBindings.role(f.subject())||DomainBindings.role(str(f.attributes(),"object")))){
                    Map<String,Object> computed=new LinkedHashMap<>(f.attributes());
                    computed.put("documentScopeEvidence",f.sourceIds().stream().map(sources::get).filter(Objects::nonNull).map(s->linked("sourceId",s.id(),"scope",s.scopeContext().audit())).toList());
                    f=new Fact(f.id(),f.kind(),f.subject(),f.field(),f.rawValue(),f.value(),f.unit(),f.basis(),f.scope(),f.sourceIds(),computed);
                }
                good.add(f);
            }else rejected.add(linked("id",id,"kind",f.kind(),"subject",redact(f.subject()),"field",f.field(),"code",issue,"sourceIds",refs));
        }return new Verified(good,rejected);
    }
    String issue(Fact f,Map<String,Source> sources){
        if(!KINDS.contains(f.kind())||f.sourceIds().isEmpty()||f.sourceIds().size()>12||f.sourceIds().stream().anyMatch(id->!sources.containsKey(id)))return "SOURCE_REFERENCE_INVALID";
        List<Source> refs=f.sourceIds().stream().map(sources::get).toList();String text=String.join("\n",refs.stream().map(s->s.evidence().quote()).toList());
        if(refs.stream().anyMatch(s->s.id().startsWith("V")&&s.evidence().anchor().startsWith("image:"))){
            if(!f.kind().equals("FIELD"))return "VISUAL_FIELD_RELATION_REQUIRES_ORIGINAL_SOURCE";
            if(!f.subject().isBlank()||!f.scope().equals("UNRESOLVED"))return "VISUAL_FIELD_SUBJECT_REQUIRES_ORIGINAL_SOURCE";
        }
        if(f.rawValue().isBlank()||refs.stream().noneMatch(s->clean(s.evidence().quote()).contains(clean(f.rawValue()))))return "RAW_VALUE_UNGROUNDED";
        if(!Set.of("CURRENT_PROJECT","HISTORICAL_PROJECT","UNRESOLVED").contains(f.scope()))return "SCOPE_INVALID";
        if("CURRENT_PROJECT".equals(f.scope())&&refs.stream().allMatch(Source::historical))return "HISTORICAL_SCOPE_UNRESOLVED";
        if(Set.of("SECTION","MATERIAL","PERSON_ROLE","CONTRACT").contains(f.kind())&&refs.stream().allMatch(Source::index))return "NAVIGATION_NOT_SUBSTANTIVE";
        if(Set.of("MATERIAL","DECLARATION","SECTION").contains(f.kind())){
            if(f.value().isBlank()||!contains(text,f.value())||clean(text).length()<40)return "CONTENT_FACT_UNGROUNDED";
            if(!f.subject().isBlank()&&!contains(text,f.subject()))return "CONTENT_SUBJECT_UNPROVEN";
            if(!f.kind().equals("DECLARATION")){for(Source source:refs){String q=source.evidence().quote();int position=q.indexOf(f.value());if(position>=0){String local=q.substring(Math.max(0,position-70),Math.min(q.length(),position+f.value().length()+30));if(local.matches("(?s).*(?:待提供|另行提供|后续提供|拟提交|将.{0,15}提交|中标后.{0,15}提交|承诺提供|尚未提交|未提交).*"))return "FUTURE_SUBMISSION_NOT_PRESENT";}}}
            String materialIssue=MaterialBodyProof.issue(f,refs);if(materialIssue!=null)return materialIssue;
        }
        if(f.kind().equals("NUMBER")){
            if(!numberPresent(text,f.value())||f.basis().isBlank()||refs.stream().noneMatch(s->near(s.evidence().quote(),f.basis(),f.value())))return "AMOUNT_OR_NUMBER_BINDING_INVALID";
            if(!f.unit().isBlank()&&!unitBound(numericContext(text,f.basis(),f.value()),f.value(),f.unit()))return "UNIT_BINDING_INVALID";
            String tax=str(f.attributes(),"taxBasis");if(!tax.isBlank()&&!taxBound(text,f.basis(),f.value(),tax))return "TAX_BASIS_UNGROUNDED";
        }else if(f.kind().equals("PERSON_ROLE")){
            if(f.subject().isBlank()||f.value().isBlank()||refs.stream().noneMatch(s->near(s.evidence().quote(),f.subject(),f.value())||DocumentSubjectScope.appointment(s,f)))return "PERSON_ROLE_BINDING_INVALID";
        }else if(f.kind().equals("RELATION")){
            String object=str(f.attributes(),"object");if(f.subject().isBlank()||object.isBlank()||f.field().isBlank()||refs.stream().noneMatch(s->EnterpriseSubjectBindings.grounded(f,s)||near(s.evidence().quote(),f.subject(),object)&&contains(s.evidence().quote(),f.field())))return "RELATION_ENDPOINTS_UNPROVEN";
        }else if(f.kind().equals("DATE_RANGE")){
            String field=str(f.attributes(),"field"),start=str(f.attributes(),"start"),end=str(f.attributes(),"end");
            if(f.subject().isBlank()||field.isBlank()||(start.isBlank()&&end.isBlank())||refs.stream().noneMatch(s->{String local=certificateBlock(s.evidence().quote(),f);return contains(local,field)&&datePresent(local,start)&&datePresent(local,end);}))return "DATE_FIELD_BINDING_INVALID";
        }else if(f.kind().equals("MONTHS")){
            if(SocialInsuranceEvidenceRecords.claims(f))return SocialInsuranceEvidenceRecords.issue(f,sources);
            List<String> months=strings(f.attributes().get("months"));String person=str(f.attributes(),"person"),employer=str(f.attributes(),"employer");
            if(months.isEmpty()||person.isBlank()||refs.stream().noneMatch(s->contains(s.evidence().quote(),person)&&months.stream().allMatch(m->monthPresent(subjectBlock(s.evidence().quote(),person),m))))return "MONTH_ROW_UNPROVEN";
            if(!employer.isBlank()&&refs.stream().noneMatch(s->contains(subjectBlock(s.evidence().quote(),person),employer)))return "SOCIAL_EMPLOYER_LINK_UNPROVEN";
        }else if(f.kind().equals("CERTIFICATE")){
            if(f.subject().isBlank()||!contains(text,f.subject()))return "CERTIFICATE_SUBJECT_UNPROVEN";
            boolean bound=CertificateRecordBindings.matches(f,sources)||refs.stream().anyMatch(s->{String local=certificateBlock(s.evidence().quote(),f);if(local.isBlank())return false;for(String key:List.of("category","grade","profession","employer","number","numberField","validityField")){String value=str(f.attributes(),key);if(!value.isBlank()&&!contains(local,value))return false;}for(String key:List.of("start","end"))if(!datePresent(local,str(f.attributes(),key)))return false;String number=str(f.attributes(),"number"),field=str(f.attributes(),"numberField");return number.isBlank()||!field.isBlank()&&near(local,field,number);});
            if(!bound)return "CERTIFICATE_SUBJECT_FIELD_BINDING_INVALID";
        }else if(f.kind().equals("CONTRACT")){
            String name=str(f.attributes(),"contractName"),number=str(f.attributes(),"contractNumber");if(name.isBlank()&&number.isBlank())return "CONTRACT_IDENTITY_UNPROVEN";
            for(String key:List.of("contractName","contractNumber","party","person","personRole")){String v=str(f.attributes(),key);if(!v.isBlank()&&!contains(text,v))return "CONTRACT_ATTRIBUTE_UNGROUNDED";}
            // Each cross-page source must carry contract identity; proximity alone cannot merge two contracts.
            if(refs.size()>1&&refs.stream().anyMatch(s->!(contains(s.evidence().quote(),name)||contains(s.evidence().quote(),number))))return "CONTRACT_PAGE_LINK_UNPROVEN";
            String amount=str(f.attributes(),"amount"),basis=str(f.attributes(),"amountBasis");if(!amount.isBlank()&&(!numberPresent(text,amount)||basis.isBlank()||refs.stream().noneMatch(s->near(s.evidence().quote(),basis,amount))))return "CONTRACT_AMOUNT_BASIS_UNPROVEN";
            if(!amount.isBlank()&&(str(f.attributes(),"unit").isBlank()||refs.stream().noneMatch(s->near(s.evidence().quote(),basis,amount)&&unitBound(numericContext(s.evidence().quote(),basis,amount),amount,str(f.attributes(),"unit")))))return "CONTRACT_AMOUNT_UNIT_UNPROVEN";
            String tax=str(f.attributes(),"taxBasis");if(!tax.isBlank()&&!taxBound(text,basis,amount,tax))return "CONTRACT_TAX_BASIS_UNPROVEN";
            String signed=str(f.attributes(),"signedDate");if(!datePresent(text,signed))return "CONTRACT_DATE_UNGROUNDED";
        }else if(!Set.of("MATERIAL","DECLARATION","SECTION").contains(f.kind())&&!f.value().isBlank()&&!contains(text,f.value()))return "VALUE_UNGROUNDED";
        return null;
    }
    static boolean contains(String text,String value){return value!=null&&!clean(value).isEmpty()&&clean(text).contains(clean(value));}
    static boolean near(String text,String a,String b){if(a.isBlank()||b.isBlank())return false;int p=text.indexOf(a);while(p>=0){String window=text.substring(Math.max(0,p-12),Math.min(text.length(),p+a.length()+120));if(b.matches("[0-9,，.]+")){String tail=text.substring(p+a.length(),Math.min(text.length(),p+a.length()+100)).split("[;；。\\n|]",2)[0];Matcher first=Pattern.compile("(?:\\d{1,3}(?:[,，]\\d{3})+|\\d+)(?:\\.\\d+)?").matcher(tail);if(first.find()&&decimal(first.group()).compareTo(decimal(b))==0)return true;}else if(contains(window,b))return true;p=text.indexOf(a,p+1);}return false;}
    static boolean numberPresent(String text,String value){BigDecimal expected=decimal(value);if(expected==null)return false;Matcher m=Pattern.compile("(?<![\\d.])(?:\\d{1,3}(?:[,，]\\d{3})+|\\d+)(?:\\.\\d+)?(?![\\d.])").matcher(text);while(m.find()){BigDecimal parsed=decimal(m.group());if(parsed!=null&&parsed.compareTo(expected)==0)return true;}return false;}
    static BigDecimal decimal(Object text){try{return new BigDecimal(String.valueOf(text).strip().replace(",","").replace("，",""));}catch(Exception ignored){return null;}}
    static boolean unitBound(String text,String value,String unit){BigDecimal v=decimal(value);if(v==null)return false;Matcher m=Pattern.compile("(?<![\\d.])((?:\\d{1,3}(?:[,，]\\d{3})+|\\d+)(?:\\.\\d+)?)\\s*(亿元|万元|美元|欧元|港元|CNY|RMB|USD|EUR|HKD|元|个月|月|年|天|日|项|个)(?![\\p{IsHan}]*元)").matcher(text);while(m.find())if(decimal(m.group(1)).compareTo(v)==0&&(m.group(2).equals(unit)||RequirementGrounding.unitEquals(m.group(2),unit)))return true;return false;}
    static String[] segments(String text){return text.split("[；;。\\n|]|(?<!\\d)[,，]|[,，](?!\\d{3}(?!\\d))");}
    static String numericContext(String text,String label,String value){if(label.isBlank())return "";for(String segment:segments(text)){if(near(segment,label,value))return segment.substring(segment.indexOf(label));}return "";}
    static boolean taxBound(String text,String basis,String value,String tax){for(String line:segments(text)){if(!near(line,basis,value))continue;String actual=line.contains("不含税")||line.contains("未税")||line.contains("税前")?"不含税":line.contains("含税")||line.contains("税后")?"含税":"";String desired=tax.contains("不含税")||tax.contains("未税")||tax.contains("税前")?"不含税":tax.contains("含税")||tax.contains("税后")?"含税":tax;if(actual.equals(desired))return true;}return false;}
    static String subjectBlock(String text,String subject){if(subject.isBlank())return "";int p=text.indexOf(subject);if(p<0)return "";int start=Math.max(0,p-120),end=Math.min(text.length(),p+800);Matcher labels=Pattern.compile("(?:姓名|持证人|企业名称|单位名称)\\s*[:：]").matcher(text);while(labels.find()){if(labels.start()<p&&labels.end()<=p&&p-labels.end()<30)start=Math.max(start,labels.start());if(labels.start()>p+subject.length()){end=Math.min(end,labels.start());break;}}return text.substring(start,end);}
    /** A title/number immediately above a labelled holder belongs to the same certificate.
     * Never extend a general person/price block, or cross another holder or a second certificate. */
    static String certificateBlock(String text,Fact fact){
        String professionalTitle=ProfessionalTitleCertificate.block(text,fact);if(!professionalTitle.isBlank())return professionalTitle;
        String subject=fact.subject(),category=str(fact.attributes(),"category"),base=subjectBlock(text,subject);
        if(base.isBlank()||category.isBlank())return base;
        Matcher holder=Pattern.compile("(?:姓名|持证人|企业名称|单位名称)\\s*[:：]\\s*"+Pattern.quote(subject)+"(?![\\p{IsHan}A-Za-z0-9])").matcher(text);
        if(!holder.find())return base;
        int holderStart=holder.start(),owner=holder.end()-subject.length();
        // A narrative appointment can mention the same name before the certificate. Locate
        // the unique labelled holder instead; two same-name certificate records stay ambiguous.
        if(holder.find())return "";
        int start=holderStart,end=Math.min(text.length(),owner+800);
        Matcher nextHolder=Pattern.compile("(?:姓名|持证人|企业名称|单位名称)\\s*[:：]").matcher(text);
        while(nextHolder.find())if(nextHolder.start()>owner+subject.length()){end=Math.min(end,nextHolder.start());break;}
        int prefixStart=Math.max(0,holderStart-240);String prefix=text.substring(prefixStart,holderStart);
        // A registration profession plus the literal registration-document suffix is one
        // title. Do not treat an examination qualification certificate as registration.
        String suffix=category.matches(".*注册(?:建筑师|建造师|监理工程师|造价工程师)$")?"(?:注册证书)?":"";
        Matcher title=Pattern.compile("(?m)^[ \\t#*]*(?:中华人民共和国)?"+Pattern.quote(category)+suffix+"[ \\t*]*\\r?$").matcher(prefix);
        while(title.find()){
            String between=prefix.substring(title.end());
            boolean onlyNumberLines=Arrays.stream(between.split("\\R")).map(String::strip).filter(line->!line.isEmpty())
                .allMatch(line->line.matches("(?:\\*\\*)?(?:证书编号|电子证书编号|注册编号|注册证号|编号|证号|资格证号|证书号码|证件号码)\\s*[:：]\\s*[^:：\\r\\n]{1,100}"));
            if(onlyNumberLines)start=prefixStart+title.start();
        }
        if(start<holderStart){
            Matcher nextTitle=Pattern.compile("(?m)^[ \\t#*]*[^\\r\\n:：]{1,40}(?:证书|许可证|执照)[ \\t*]*\\r?$").matcher(text);
            while(nextTitle.find())if(nextTitle.start()>owner+subject.length()){end=Math.min(end,nextTitle.start());break;}
        }
        return text.substring(start,end);
    }
    /** Parse only a complete explicit calendar date. No missing year/day, range or prose inference. */
    static LocalDate date(Object text){try{
        String value=String.valueOf(text).strip();Matcher m=Pattern.compile("^(\\d{4})\\s*(?:年|[-./])\\s*(\\d{1,2})\\s*(?:月|[-./])\\s*(\\d{1,2})\\s*日?$").matcher(value);
        return m.matches()?LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))):null;
    }catch(Exception ignored){return null;}}
    static boolean datePresent(String text,String iso){if(iso.isBlank())return true;LocalDate d=date(iso);if(d==null)return false;String pattern="(?<![0-9])"+d.getYear()+"[年./-]0?"+d.getMonthValue()+"[月./-]0?"+d.getDayOfMonth()+"日?(?![0-9])";return Pattern.compile(pattern).matcher(text.replaceAll("\\s+","")).find();}
    static boolean monthPresent(String text,String iso){try{YearMonth m=YearMonth.parse(iso);return Pattern.compile(m.getYear()+"[年./-]0?"+m.getMonthValue()+"(?:月|(?!\\d))").matcher(text.replaceAll("\\s+","")).find();}catch(Exception ignored){return false;}}
    static List<String> strings(Object object){return object instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();}
}
