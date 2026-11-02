package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.*;
import java.util.regex.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Common query proof belongs to a qualification that actually satisfied the parent alternative. */
final class QualificationProofAssociation {
    private static final Logger LOG=LoggerFactory.getLogger(QualificationProofAssociation.class);
    static final String VERSION="selected-qualification-proof-5-header-and-layout-actions";
    private static final Pattern NUMBER=Pattern.compile("(?:资质证书编号|证书编号|证书号码|证号)[\\s:：|]*([A-Za-z0-9][A-Za-z0-9()（）/-]{3,79})");
    private static final Pattern HOLDER=Pattern.compile("(?:企业名称|单位名称|组织名称|持证单位)[\\t :：|]*([^\\r\\n|]+)");
    private static final Pattern CATEGORY=Pattern.compile("(?:资质类别|资质名称|证书类别|类别)[\\t :：|]*([^\\r\\n|]+)");
    private static final Pattern GRADE=Pattern.compile("(?:资质等级|等级)[\\t :：|]*(甲级|乙级|丙级|丁级|一级|二级|三级|特级|壹级|贰级|叁级)");
    private static final Pattern URL=Pattern.compile("(?i)(?:https?://|www\\.)[A-Za-z0-9.-]+(?:/[^\\s<>|]*)?");
    private QualificationProofAssociation(){}
    static boolean sourceChoice(Rule rule){return rule.predicate().equals("GROUP")&&rule.logic().equals("ANY")&&str(rule.parameters(),"deterministicFamily").equals("SOURCE_ALTERNATIVE_TREE");}
    static String choiceFingerprint(Rule choice){return identity(choice.subject()+"\n"+choice.sourceQuote());}
    static boolean applies(Rule rule){return Set.of("MATERIAL","SAME_FIELD").contains(rule.predicate())&&!str(rule.parameters(),"qualificationChoiceFingerprint").isBlank();}
    static void register(Rule rule,RuleEvaluator evaluator){
        if(!str(rule.parameters(),"deterministicFamily").equals("SOURCE_REQUIREMENT_TREE")||!rule.logic().equals("ALL"))return;
        var parsed=QualificationRequirementTree.parse(rule.sourceQuote());if(parsed.isEmpty()||parsed.get().proofQuote().isBlank())return;
        for(Rule choice:rule.children())if(sourceChoice(choice)&&choice.subject().equals(parsed.get().subject())&&choice.sourceQuote().equals(parsed.get().choiceQuote())){
            String fingerprint=choiceFingerprint(choice);evaluator.sourceQualificationChoices.put(fingerprint,choice);evaluator.sourceQualificationProofs.put(fingerprint,parsed.get().proofQuote());
        }
    }

    record Record(Source source,String company,String category,String number,String grade,boolean query,List<String> urls,List<Evidence> evidences,Map<String,Object> fields){
        Record(Source source,String company,String category,String number,String grade,boolean query,List<String> urls){this(source,company,category,number,grade,query,urls,List.of(source.evidence()),Map.of());}
        String key(){return clean(company)+"|"+CertificateEvidenceRecords.normalizeCertificateNumber(number);}
        Map<String,Object> audit(){return linked("sourceId",source.id(),"source",source.evidence(),"subject",company,"category",category,"certificateNumber",number,"grade",grade,"query",query,"urls",urls,"fieldSources",fields,"sources",evidences);}
    }
    record Selected(Fact fact,Rule branch,Record certificate){}
    record Bundle(List<String> subjects,List<Selected> selected,List<Record> queries,String problem,String reason){}

    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        String fingerprint=str(rule.parameters(),"qualificationChoiceFingerprint");
        Rule choice=evaluator.sourceQualificationChoices.get(fingerprint);
        if(choice==null||!choice.subject().equals(rule.subject())||!sourceChoice(choice)||!rule.sourceQuote().equals(evaluator.sourceQualificationProofs.get(fingerprint)))
            return evaluator.unknown(rule,"QUALIFICATION_PROOF_PARENT_REQUIRED","共通证明尚未对应同主体的资质选择条件",List.of());
        var structure=QualificationRequirementTree.parse(choice.sourceQuote());
        if(structure.isEmpty()||!rule.sourceQuote().matches("(?s).*(?:查询网址|查询截图).*"))
            return evaluator.unknown(rule,"QUALIFICATION_PROOF_PARENT_REQUIRED","共通证明与资质选择结构尚未核对原文",List.of());
        Bundle bundle=evaluator.qualificationProofBundles.computeIfAbsent(fingerprint,key->prepare(choice,evaluator));
        if(!bundle.problem().isBlank())return evaluator.unknown(rule,bundle.problem(),bundle.reason(),List.of());
        List<Decision> people=new ArrayList<>();
        for(String company:bundle.subjects()){
            List<Selected> certificates=bundle.selected().stream().filter(c->c.certificate().company().equals(company)).toList();
            if(certificates.isEmpty()){
                people.add(evaluator.unknown(rule,"QUALIFICATION_PROOF_CERTIFICATE_REQUIRED","已满足资质的具体证号和证明文件原文尚未对应到单位："+company,List.of()));continue;
            }
            if(rule.predicate().equals("MATERIAL")&&rule.expected().equals("证明文件")){
                List<Fact> facts=certificates.stream().map(Selected::fact).distinct().toList();
                Decision presence=evaluator.pass(rule,"已定位本单位实际满足资质分支的证书证明原文",facts,linked("executor",VERSION,"proofScope","SELECTED_QUALIFICATION_CERTIFICATE_DOCUMENT","selectedCertificates",certificates.stream().map(c->c.certificate().audit()).toList()));
                if(rule.sourceQuote().matches("(?s).*(?:提交|提供)有效证明文件.*")){
                    List<Decision> valid=new ArrayList<>();for(Selected selected:certificates)valid.add(validity(rule,selected,evaluator));
                    Decision dates=RuleEvaluator.combine(rule.id()+"-VALIDITY", "实际满足资质的同证号有效期", "ANY", valid);
                    people.add(RuleEvaluator.combine(rule.id(),rule.description(),"ALL",List.of(presence,dates)));
                }else people.add(presence);
                continue;
            }
            List<Decision> alternatives=new ArrayList<>();
            for(Selected selected:certificates)alternatives.add(compare(rule,selected,bundle.queries(),evaluator));
            people.add(RuleEvaluator.combine(rule.id()+":company:"+identity(company).substring(0,10),rule.description(),"ANY",alternatives));
        }
        Decision result=RuleEvaluator.combine(rule.id(),rule.description(),"ALL",people);
        Map<String,Object> proof=new LinkedHashMap<>(result.proof());proof.putAll(linked("executor",VERSION,"qualificationChoiceFingerprint",fingerprint,"qualificationChoiceRuleId",choice.id(),"checkedCondition",rule.predicate().equals("SAME_FIELD")?"CERTIFICATE_QUERY_FIELDS":rule.expected(),"proofScope","SELECTED_QUALIFICATION_CERTIFICATE_QUERY_CHAIN","doesNotProveCurrentOnlineStatus",true));
        return new Decision(rule.id(),rule.description(),result.status(),result.reason(),result.evidences(),result.gaps(),proof);
    }

    /** A document exists and its category matches does not establish validity at the tender event. */
    private static Decision validity(Rule rule,Selected selected,RuleEvaluator evaluator){
        Fact certificate=selected.fact();String category=str(certificate.attributes(),"category"),number=str(certificate.attributes(),"number");
        var originalHeader=QualificationCertificateEvidence.resolve(certificate,selected.certificate().source(),evaluator.corpus);
        Map<String,Object> parameters=linked("category",category,"number",number);
        originalHeader.ifPresent(value->parameters.put("validityField",value.field()));
        Rule validity=new Rule(rule.id()+"-VALID-"+identity(certificate.subject()+category+number).substring(0,12),"所选资质证书在要求时点有效","VALIDITY",certificate.subject(),"","","","",parameters,rule.sourceQuote(),"",List.of());
        List<Fact> candidates=new ArrayList<>(evaluator.facts);
        originalHeader.ifPresent(value->candidates.add(value.fact()));
        for(Source source:selected.certificate().source()==null?List.<Source>of():List.of(selected.certificate().source()))candidates.addAll(NativeCertificateFacts.extract(validity,source));
        List<Fact> dates=candidates.stream().filter(f->f.subject().equals(certificate.subject())&&DomainBindings.sameCertificateCategory(str(f.attributes(),"category"),category)&&str(f.attributes(),"number").equals(number))
            .filter(f->new ProofVerifier().issue(f,evaluator.corpus.byId)==null&&DomainBindings.issue(f,evaluator.corpus.byId,List.of())==null)
            .filter(f->f.sourceIds().stream().map(evaluator.corpus.byId::get).filter(Objects::nonNull).anyMatch(s->s.evidence().documentId().equals(selected.certificate().source().evidence().documentId())&&s.evidence().page()==selected.certificate().source().evidence().page())).distinct().toList();
        Decision checked=evaluator.validity(validity,dates);Map<String,Object> proof=new LinkedHashMap<>(checked.proof());
        originalHeader.ifPresent(value->{proof.put("qualificationDateFieldResolution",value.proof());proof.put("validityFieldResolution","SELECTED_ORIGINAL_CERTIFICATE_HEADER");proof.put("validityFieldReason","由实际满足资质分支的同证原文表头确定，另行核验使用期限与备注限制");});
        proof.putAll(linked("executor",VERSION,"selectedQualificationRuleId",selected.branch().id(),"selectedFactId",certificate.id(),"certificateNumber",number,"certificateDocumentId",selected.certificate().source().evidence().documentId(),"certificatePage",selected.certificate().source().evidence().page(),"proofScope","SELECTED_CERTIFICATE_VALIDITY_AT_TENDER_EVENT"));
        LOG.info("bidcheck.qualification_validity ruleId={} executor={} certificatePage={} status={} gapCodes={}",rule.id(),VERSION,selected.certificate().source().evidence().page(),checked.status(),checked.gaps().stream().map(Gap::code).toList());
        return new Decision(checked.id(),checked.description(),checked.status(),checked.reason(),checked.evidences(),checked.gaps(),proof);
    }

    private static Bundle prepare(Rule choice,RuleEvaluator evaluator){
        Decision parent=evaluator.decisions.get(choice.id());
        if(parent==null||!parent.status().equals("PASS"))return new Bundle(List.of(),List.of(),List.of(),"QUALIFICATION_PROOF_SELECTION_REQUIRED","尚无已经满足要求的资质路径，不能借任意证书补齐共通证明");
        List<String> subjects=evaluator.resolvedSubjects(choice);
        if(subjects.isEmpty()||subjects.stream().anyMatch(DomainBindings::role))return new Bundle(List.of(),List.of(),List.of(),"QUALIFICATION_PROOF_SUBJECT_REQUIRED","资质证明尚未绑定当前项目具体单位");
        List<Selected> selected=new ArrayList<>();Set<String> categories=new LinkedHashSet<>();
        for(Rule branch:choice.children()){
            Decision decision=evaluator.decisions.get(branch.id());
            if(!branch.predicate().equals("CERTIFICATE")||decision==null||!decision.status().equals("PASS"))continue;
            Set<String> ids=new LinkedHashSet<>();factIds(decision.map(),ids);
            // A partially reviewed candidate set may have one independently closed witness.
            // Reusing every old factId here would reintroduce a conflicting certificate that
            // the source review deliberately did not select for the successful alternative.
            if(decision.proof().get("sourceReviewClosure") instanceof Map<?,?> closure
                    &&SourceLocalCertificateClosure.VERSION.equals(closure.get("version"))){
                Set<String> selectedIds=new LinkedHashSet<>();for(Map<String,Object> witness:RuleEvaluator.maps(closure.get("witnesses")))selectedIds.add(str(witness,"factId"));
                ids.retainAll(selectedIds);
            }
            String category=str(branch.parameters(),"category");categories.add(category);
            for(Fact fact:evaluator.facts){
                if(!ids.contains(fact.id())||!fact.kind().equals("CERTIFICATE")||!DomainBindings.certificateIdentity(fact)||!subjects.contains(fact.subject())||!DomainBindings.sameCertificateCategory(str(fact.attributes(),"category"),category))continue;
                if(new ProofVerifier().issue(fact,evaluator.corpus.byId)!=null||DomainBindings.issue(fact,evaluator.corpus.byId,List.of())!=null)continue;
                for(CertificateEvidenceRecords.Bound record:CertificateEvidenceRecords.certificates(fact,evaluator.corpus)){
                    selected.add(new Selected(fact,branch,new Record(record.source(),clean(fact.subject()),str(fact.attributes(),"category"),normalNumber(str(fact.attributes(),"number")),CredentialGrades.normalize(str(fact.attributes(),"grade")),false,List.of(),record.evidences(),record.fields())));
                }
            }
        }
        List<Record> queries=new ArrayList<>();for(String category:categories)for(Source source:evaluator.corpus.all){Record record=read(source,category);if(record!=null&&record.query())queries.add(record);}
        for(String company:subjects)for(String category:categories)queries.addAll(CertificateEvidenceRecords.qualificationQueries(evaluator.corpus,company,category,RuleEvaluator.maps(evaluator.context.get("_validatedLayoutCells"))));
        LOG.info("bidcheck.qualification_proof_binding ruleId={} executor={} subjects={} selectedFacts={} queryRecords={} queryTableRecords={}",choice.id(),VERSION,subjects.size(),selected.size(),queries.size(),queries.stream().filter(q->!q.fields().isEmpty()).count());
        return new Bundle(subjects,selected,queries,"","");
    }

    private static Decision compare(Rule rule,Selected selected,List<Record> records,RuleEvaluator evaluator){
        Record certificate=selected.certificate();List<Record> identities=records.stream().filter(q->q.key().equals(certificate.key())).toList();
        List<Record> queries=identities.stream().filter(q->DomainBindings.sameCertificateCategory(CertificateEvidenceRecords.category(q.category()),CertificateEvidenceRecords.category(certificate.category()))).toList();
        Map<String,Object> proof=linked("executor",VERSION,"selectedQualificationRuleId",selected.branch().id(),"selectedQualificationQuote",selected.branch().sourceQuote(),"selectedFactId",selected.fact().id(),"certificate",certificate.audit(),"identityKeyFields",List.of("SUBJECT","CERTIFICATE_NUMBER"),"sameIdentityCandidates",identities.stream().map(Record::audit).toList(),"queries",queries.stream().map(Record::audit).toList(),"comparedFields",List.of("SUBJECT","CATEGORY","CERTIFICATE_NUMBER","GRADE"));
        if(identities.isEmpty()||queries.isEmpty()){
            List<Map<String,Object>> actions=CertificateEvidenceRecords.qualificationLayoutActions(evaluator.corpus,certificate.company(),certificate.category(),certificate.number(),rule.id());
            if(!actions.isEmpty())proof.put("requiredEvidenceActions",actions);
        }
        if(identities.isEmpty())return evaluator.unknown(rule,"QUALIFICATION_QUERY_IDENTITY_REQUIRED","尚无与已满足资质的单位和证号对应的查询截图，不能借其他证书的查询材料",List.of(selected.fact()),proof);
        if(queries.isEmpty())return evaluator.unknown(rule,"QUALIFICATION_QUERY_CATEGORY_REQUIRED","相同单位及证号已对应，但本项资质类别尚未通过原文或已验证单元格归属对齐",List.of(selected.fact()),proof);
        Set<String> grades=new LinkedHashSet<>();queries.forEach(q->grades.add(q.grade()));
        if(grades.size()!=1||grades.contains(""))return evaluator.unknown(rule,"QUALIFICATION_QUERY_FIELDS_REQUIRED","同一证号查询记录的等级缺失或存在不同版本，尚不能确认字段一致",List.of(selected.fact()),proof);
        List<Evidence> evidence=new ArrayList<>(certificate.evidences());queries.forEach(q->evidence.addAll(q.evidences()));
        if(!grades.iterator().next().equals(certificate.grade()))return new Decision(rule.id(),rule.description(),"FAIL","同一单位、类别和证号的证书与查询记录等级不一致",evidence.stream().distinct().toList(),List.of(),proof);
        if(queries.stream().noneMatch(q->!q.urls().isEmpty()))return evaluator.unknown(rule,"QUALIFICATION_QUERY_URL_REQUIRED","对应证号的查询截图已定位，但该查询文书的来源网址尚未取得",List.of(selected.fact()),proof);
        return new Decision(rule.id(),rule.description(),"PASS","已按实际满足资质的单位、类别、证号和等级对应证书、查询截图及网址",evidence.stream().distinct().toList(),List.of(),proof);
    }

    private static Record read(Source source,String requiredCategory){
        if(source==null||source.index()||source.historical()||source.scopeContext().blocked()||source.id().startsWith("V"))return null;
        String text=source.evidence().quote();if(text.matches("(?s).*(?:填写示例|证书模板|仅供参考|未取得|拟取得).*"))return null;
        String company=single(HOLDER,text),number=single(NUMBER,text),grade=single(GRADE,text);if(company==null||number==null||grade==null)return null;
        String category=single(CATEGORY,text);
        if(category==null){
            // Explicit but conflicting category labels cannot be replaced by a nearby title
            // or a quoted tender requirement that happens to name the desired category.
            if(CATEGORY.matcher(text).find())return null;
            Set<String> lines=new LinkedHashSet<>();for(String line:text.split("\\R"))if(clean(line).equals(clean(requiredCategory)))lines.add(clean(line));if(lines.size()!=1)return null;category=lines.iterator().next();
        }
        if(!DomainBindings.sameCertificateCategory(category,clean(requiredCategory)))return null;
        boolean query=text.matches("(?s).*(?:查询截图|查询结果|资质查询|证书查询|资质详情).*");
        if(!query&&!text.matches("(?s).*(?:资质证明|资格证明|资质证书|证书编号).*"))return null;
        List<String> urls=new ArrayList<>();Matcher url=URL.matcher(text);while(url.find())if(!url.group().matches("(?i).*(?:example\\.(?:com|org)|placeholder|image_url).*"))urls.add(url.group());
        return new Record(source,company,category,normalNumber(number),CredentialGrades.normalize(grade),query,List.copyOf(urls));
    }
    private static String single(Pattern pattern,String text){Set<String> values=new LinkedHashSet<>();Matcher matcher=pattern.matcher(text);while(matcher.find())values.add(clean(matcher.group(1)));return values.size()==1?values.iterator().next():null;}
    private static String normalNumber(String value){return CertificateEvidenceRecords.normalizeCertificateNumber(value);}
    private static void factIds(Object value,Set<String> ids){if(value instanceof Map<?,?> map){if(map.get("factIds") instanceof List<?> list)list.forEach(id->ids.add(Objects.toString(id)));for(Object child:map.values())factIds(child,ids);}else if(value instanceof List<?> list)list.forEach(child->factIds(child,ids));}
}
