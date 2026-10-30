package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.*;
import java.util.regex.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Submission proof is joined by certificate identity, never by a generic query-page heading. */
final class CertificateQueryProof {
    private static final Logger LOG=LoggerFactory.getLogger(CertificateQueryProof.class);
    static final String VERSION="certificate-query-proof-3-typed-license-result-row";
    private static final Pattern NUMBER=Pattern.compile("证书(?:编号|号码|号)[\\s:：|]*([A-Za-z0-9][A-Za-z0-9()（）/-]{4,79})");
    private static final Pattern URL=Pattern.compile("(?i)(?:https?://|www\\.)[A-Za-z0-9.-]+(?:/[^\\s<>]*)?");
    private CertificateQueryProof(){}
    static boolean applies(Rule rule){return "MATERIAL".equals(rule.predicate())&&!str(rule.parameters(),"certificateProofCategory").isBlank();}
    private record Record(Source source,Evidence evidence,String text,String number,boolean query){}

    static Decision evaluate(Rule rule,List<Fact> matched,RuleEvaluator evaluator){
        String category=str(rule.parameters(),"certificateProofCategory"),requirement=str(rule.parameters(),"certificateRequirementQuote");
        String original=str(evaluator.context,"originalRequirement");
        if(category.isBlank()||requirement.isBlank()||!requirement.contains(category)||!original.contains(requirement))
            return evaluator.unknown(rule,"CERTIFICATE_QUERY_REQUIREMENT_REQUIRED","查询证明尚未绑定原条款中的认证类别，不能借任意查询页面证明",matched);
        List<String> subjects=evaluator.resolvedSubjects(rule);
        if(subjects.size()!=1||DomainBindings.role(subjects.get(0)))return evaluator.unknown(rule,"CERTIFICATE_QUERY_SUBJECT_REQUIRED","查询证明尚未绑定本项具体单位",matched);
        String company=subjects.get(0),baseCategory=category.replaceFirst("证书$","");
        List<Record> records=new ArrayList<>();Set<String> seen=new HashSet<>(),seenCertificates=new HashSet<>();
        Rule certificateRule=new Rule(rule.id()+"-source-certificate","同一证书事实","CERTIFICATE",company,"证书","","","",Map.of("category",category),requirement,"",List.of());
        List<Fact> certificateFacts=new ArrayList<>(evaluator.facts);
        for(Source source:evaluator.corpus.all)certificateFacts.addAll(NativeCertificateFacts.extract(certificateRule,source));
        for(Fact fact:certificateFacts){
            if(!company.equals(fact.subject())||!DomainBindings.sameCertificateCategory(str(fact.attributes(),"category"),category))continue;
            for(var binding:CertificateEvidenceRecords.certificates(fact,evaluator.corpus)){
                Source source=binding.source();String text=source.evidence().quote();
                if(!certificateCategory(text,baseCategory)||!baseCategory.equals("安全生产许可证")&&!text.contains("兹证明"))continue;
                if(!seenCertificates.add(source.id()+"|"+str(fact.attributes(),"number")))continue;
                records.add(new Record(source,source.evidence(),text,CertificateEvidenceRecords.normalizeCertificateNumber(str(fact.attributes(),"number")),false));
            }
        }
        for(var row:CertificateEvidenceRecords.corporateQueryRows(evaluator.corpus,company,category))
            records.add(new Record(row.source(),row.page(),row.page().quote(),row.number(),true));
        Map<String,CertificateEvidenceRecords.ScopeResolver> scopeResolvers=new HashMap<>();
        for(Source source:evaluator.corpus.all){
            if(!CertificateEvidenceRecords.usable(source,evaluator.corpus,scopeResolvers))continue;
            String key=EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType());
            if(!seen.add(key))continue;var page=evaluator.corpus.pages.get(key);if(page==null)continue;
            String text=page.text().replace("*","").replace("`","").replace("#","");
            if(!holder(text,company))continue;
            Set<String> numbers=new LinkedHashSet<>();Matcher nm=NUMBER.matcher(text);while(nm.find())numbers.add(nm.group(1).toUpperCase(Locale.ROOT));
            // Multiple certificate records on a page need row-level binding; page proximity is insufficient.
            if(numbers.size()!=1)continue;
            boolean query=text.matches("(?s).*(?:查询截图|查询结果|证书列表|证书详情).*")&&text.matches("(?s).*(?:认证项目|认证类别|证书状态).*" );
            if(query&&!queryCategory(text,baseCategory))continue;
            if(!query)continue;
            Evidence origin=source.evidence();Evidence evidence=new Evidence(origin.documentId(),origin.documentName(),origin.page(),origin.locationType(),page.text(),"chars:0-"+page.text().length(),origin.confidence());
            records.add(new Record(source,evidence,text,numbers.iterator().next(),query));
        }
        List<Map<String,Object>> bindings=new ArrayList<>();List<Evidence> used=new ArrayList<>();
        for(Record certificate:records)if(!certificate.query())for(Record query:records){
            if(!query.query()||!certificate.evidence().documentId().equals(query.evidence().documentId())||!certificate.number().equals(query.number()))continue;
            List<String> urls=new ArrayList<>();Matcher url=URL.matcher(query.text());while(url.find())if(!url.group().matches("(?i).*(?:example\\.(?:com|org)|placeholder|image_url).*"))urls.add(url.group());
            if(urls.isEmpty())continue;
            bindings.add(linked("subject",company,"certificateCategory",category,"certificateNumber",certificate.number(),
                "certificateSourceId",certificate.source().id(),"querySourceId",query.source().id(),"certificatePage",certificate.evidence().page(),"queryPage",query.evidence().page(),"urls",urls,
                "documentId",certificate.evidence().documentId(),"documentHash",certificate.source().documentHash(),"documentVersion",certificate.source().documentVersion()));
            used.add(certificate.evidence());used.add(query.evidence());
        }
        LOG.info("bidcheck.certificate_query_binding ruleId={} executor={} category={} certificateRecords={} queryRecords={} bindings={} validityRequired={}",rule.id(),VERSION,category,records.stream().filter(r->!r.query()).count(),records.stream().filter(Record::query).count(),bindings.size(),Boolean.TRUE.equals(rule.parameters().get("certificateProofRequireValidity")));
        if(bindings.isEmpty())return evaluator.unknown(rule,"CERTIFICATE_QUERY_IDENTITY_REQUIRED","查询网址/截图尚未与同单位、同类别、同证号的证书原文对应；不将其他证照查询页当作本项证明",matched);
        if(Boolean.TRUE.equals(rule.parameters().get("certificateProofRequireValidity"))){
            List<Map<String,Object>> checked=new ArrayList<>();List<Decision> complete=new ArrayList<>();
            for(Map<String,Object> binding:bindings){String number=str(binding,"certificateNumber"),document=str(binding,"documentId");int page=((Number)binding.get("certificatePage")).intValue();
                List<Fact> dates=certificateFacts.stream().filter(f->company.equals(f.subject())&&DomainBindings.sameCertificateCategory(str(f.attributes(),"category"),category)&&number.equals(CertificateEvidenceRecords.normalizeCertificateNumber(str(f.attributes(),"number"))))
                    .filter(f->new ProofVerifier().issue(f,evaluator.corpus.byId)==null&&DomainBindings.issue(f,evaluator.corpus.byId,List.of())==null)
                    .filter(f->f.sourceIds().stream().map(evaluator.corpus.byId::get).filter(Objects::nonNull).anyMatch(s->document.equals(s.evidence().documentId())&&s.evidence().page()==page)).toList();
                String originalNumber=dates.isEmpty()?number:str(dates.get(0).attributes(),"number");
                Rule validity=new Rule(rule.id()+"-"+identity(number).substring(0,8),"同证号适用有效期","VALIDITY",company,"有效期","","","",Map.of("category",category,"number",originalNumber),requirement,"",List.of());
                Decision decision=evaluator.validity(validity,dates);checked.add(linked("binding",binding,"validity",decision.map()));if(decision.status().equals("PASS"))complete.add(decision);
            }
            Map<String,Object> proof=linked("executor",VERSION,"proofScope","SAME_CERTIFICATE_QUERY_AND_VALIDITY_CHAIN","certificateBindings",bindings,"chainChecks",checked);
            if(complete.isEmpty()){
                List<Map<String,Object>> failures=checked.stream().flatMap(check->RuleEvaluator.maps(List.of(check.get("validity"))).stream()).filter(value->"FAIL".equals(value.get("status"))).toList();
                if(failures.size()==checked.size()&&!failures.isEmpty())return new Decision(rule.id(),rule.description(),"FAIL","同单位、同类别和同证号的查询材料已对应，但该证书有效期不覆盖已确认的招标事件日期",used.stream().distinct().toList(),List.of(),proof);
                return new Decision(rule.id(),rule.description(),"UNDETERMINED","查询材料已对应证号，但同一证号的适用有效期尚未完成核验；不能借另一张证书的日期合成通过",used.stream().distinct().toList(),List.of(gap("CERTIFICATE_QUERY_VALIDITY_LINK_REQUIRED","需在同一证号及单位下闭合查询与适用有效期")),proof);
            }
            List<Evidence> evidence=new ArrayList<>(used);complete.forEach(d->evidence.addAll(d.evidences()));
            return new Decision(rule.id(),rule.description(),"PASS","已核到至少一套同单位、同类别、同证号的查询及适用有效期证据",evidence.stream().distinct().toList(),List.of(),proof);
        }
        return new Decision(rule.id(),rule.description(),"PASS","已按单位、认证类别及证号对应证书与查询文书；该结论仅证明查询材料提交，不代表有效期或全部字段一致",used.stream().distinct().toList(),List.of(),
            linked("executor",VERSION,"proofScope","SAME_CERTIFICATE_QUERY_DOCUMENT_SUBMISSION_ONLY","certificateBindings",bindings,"validityVerified",false,"allFieldsCompared",false));
    }
    private static boolean holder(String text,String company){
        String name=Pattern.quote(company);
        return Pattern.compile("(?:兹证明\\s*|(?:获证组织名称|组织名称|企业名称)[\\s:：|]*)"+name+"(?=[\\s|。；;]|$)").matcher(text).find();
    }
    private static boolean certificateCategory(String text,String required){
        if(required.equals("安全生产许可证"))return text.matches("(?s).*(?:^|\\n)[\\t #*]*安全生产许可证[\\t *]*(?:\\r?\\n|$).*");
        Set<String> named=new LinkedHashSet<>();for(String line:text.split("\\R")){
            String value=clean(line.replace("*","").replaceFirst("^[\\t ]*#{1,6}[\\t ]*",""));if(value.matches("(?:质量管理体系|职业健康安全管理体系|环境管理体系)认证证书"))named.add(value.replaceFirst("证书$",""));
        }return named.equals(Set.of(required));
    }
    private static boolean queryCategory(String text,String required){
        Set<String> named=new LinkedHashSet<>();Matcher fields=Pattern.compile("(?:认证项目|认证类别)[\\t :：|]*([^\\r\\n|]+)").matcher(text);
        while(fields.find()){
            String value=clean(fields.group(1));
            if(value.isBlank()||value.matches("[-—–]+")||value.matches("[（(]?(?:下拉菜单|下拉选择|请选择)[）)]?"))continue;
            value=value.replaceFirst("^(?:建设施工行业|中国)","").replaceFirst("[（(]ISO[0-9:：-]+[）)]$","").replaceFirst("证书$","");
            named.add(value);
        }return named.equals(Set.of(required));
    }
}
