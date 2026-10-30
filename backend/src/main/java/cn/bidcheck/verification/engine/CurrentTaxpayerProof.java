package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Taxpayer status must be supported by a dated tax document, not its title or a model label. */
final class CurrentTaxpayerProof {
    static final String VERSION="current-taxpayer-proof-1";
    private static final String DATE="(\\d{4})[年./-](\\d{1,2})[月./-](\\d{1,2})日?";
    private static final Pattern OWNER=Pattern.compile("(?m)^[ \\t|#*]*(?:纳税人名称(?:（全称）)?|企业名称)[ \\t]*[:：|][ \\t]*([^\\r\\n|]{2,100})");
    private static final Pattern TAX_ID=Pattern.compile("(?:统一社会信用代码|纳税人识别号)[ \\t]*[:：|][ \\t`*]*([A-Z0-9]{15}(?:[A-Z0-9]{3})?)(?![A-Z0-9])");
    private static final Pattern START=Pattern.compile("(?:一般纳税人资格生效之日|一般纳税人资格生效日期|资格生效日期|认定日期|登记日期|批准日期)[ \\t]*[:：|][ \\t`*]*"+DATE);
    private static final Pattern END=Pattern.compile("(?:资格有效期至|有效期至|有效截止日期)[ \\t]*[:：|][ \\t`*]*"+DATE);
    private static final Pattern QUERY_DATE=Pattern.compile("(?:查询日期|查询时间|信息截至日期)[ \\t]*[:：|][ \\t`*]*"+DATE);
    private CurrentTaxpayerProof() {}
    static boolean applies(Rule rule){return Boolean.TRUE.equals(rule.parameters().get("currentTaxpayerProof"));}

    static Decision evaluate(Rule rule,List<Fact> matched,RuleEvaluator evaluator){
        LocalDate reference=evaluator.referenceDate();
        if(reference==null)return evaluator.unknown(rule,"REFERENCE_TIME_UNCONFIRMED","一般纳税人资格缺少已核源的投标参考日期，不能用今天或材料标题推定当前适用",matched);
        String selector=rule.subject().isBlank()?"投标人":rule.subject();
        List<Fact> subjectFacts=new ArrayList<>(evaluator.facts);
        Rule parties=new Rule(rule.id()+"-PARTY","当前投标主体","MATERIAL","投标人","material","","","",Map.of(),rule.sourceQuote(),"",List.of());
        subjectFacts.addAll(EnterpriseSubjectBindings.extract(List.of(parties),evaluator.corpus.all));
        List<String> currentParties=DomainBindings.subjects("投标人",subjectFacts,evaluator.corpus);
        List<String> selected=DomainBindings.subjects(selector,subjectFacts,evaluator.corpus);
        if(selected.size()!=1||!currentParties.contains(selected.get(0)))
            return evaluator.unknown(rule,"TAXPAYER_SUBJECT_REQUIRED","尚未将本条件适用的开票单位对应到当前投标主体，不能混用其他公司或联合体成员的税务资格",matched);
        String company=selected.get(0);
        List<Evidence> accepted=new ArrayList<>(),inspected=new ArrayList<>();
        List<Map<String,Object>> checks=new ArrayList<>(),proofs=new ArrayList<>();
        boolean conflict=false;
        // Each page is examined independently. Never combine one page's owner with another page's dates.
        Set<String> seen=new HashSet<>();
        for(Source source:evaluator.corpus.all){
            if(source.index()||source.historical()||source.scopeContext().blocked()||source.id().startsWith("V"))continue;
            Evidence origin=source.evidence();
            Document document=evaluator.corpus.documents.stream().filter(d->d.id().equals(origin.documentId())&&"BID".equals(d.role())).findFirst().orElse(null);
            if(document==null)continue;
            String key=EvidenceCorpus.pageKey(origin.documentId(),origin.page(),origin.locationType());
            if(!seen.add(key))continue;
            Page page=evaluator.corpus.pages.get(key);if(page==null)continue;
            String text=Objects.toString(page.text(),""),compact=clean(text);
            if(!compact.contains("一般纳税人"))continue;
            Evidence evidence=new Evidence(document.id(),document.name(),page.number(),page.locationType(),text,"chars:0-"+text.length(),origin.confidence());
            String reason="";
            if(compact.matches("(?s).*(?:辅导期|临时资格|临时认定|临时一般纳税人|期满.{0,20}正式认定).*"))reason="TEMPORARY_OR_TUTORING_STATUS";
            else if(compact.matches("(?s).*(?:示例|样本|填表说明|仅供参考).*"))reason="TAX_DOCUMENT_EXAMPLE";
            else if(!compact.matches("(?s).*(?:税务局|主管税务机关|国家税务总局).*"))reason="TAX_AUTHORITY_REQUIRED";
            Set<String> owners=labels(OWNER,text,1),numbers=labels(TAX_ID,text,1);
            if(reason.isBlank()&&(owners.size()!=1||!owners.contains(clean(company))))reason="TAXPAYER_OWNER_REQUIRED";
            if(reason.isBlank()&&numbers.size()!=1)reason="TAXPAYER_IDENTIFIER_REQUIRED";
            boolean negative=compact.matches("(?s).*(?:(?:资格状态|纳税人状态|登记状态)[:：|]*(?:非正常|无效|失效|注销|取消)|(?:已被|已经|已)(?:取消|注销)一般纳税人资格).*" );
            if(reason.isBlank()&&negative){reason="TAXPAYER_CURRENT_STATUS_CONFLICT";conflict=true;}
            boolean qualified=Pattern.compile("(?m)^[ \\t|#*]*(?:纳税人资格|纳税人类型|纳税人类别)[ \\t]*[:：|][ \\t`*]*(?:增值税)?一般纳税人[ \\t`*]*(?:[|。；;]|$)").matcher(text).find()
                    ||compact.matches("(?s).*经(?:审查|审核)[^。]{0,60}(?:认定|登记)为(?:增值税)?一般纳税人[。；;].*");
            if(reason.isBlank()&&!qualified)reason="TAXPAYER_STATUS_ASSERTION_REQUIRED";
            List<LocalDate> starts=dates(START,text),ends=dates(END,text),queryDates=dates(QUERY_DATE,text);
            boolean officialQuery=compact.matches("(?s).*(?:一般纳税人资格查询|纳税人资格信息查询|税务登记信息查询).*"),formalRegistration=compact.matches("(?s).*(?:一般纳税人登记表|一般纳税人资格认定通知书|一般纳税人认定通知书).*" );
            String basis="";
            if(reason.isBlank()){
                if(officialQuery){
                    if(queryDates.size()!=1||!queryDates.get(0).equals(reference))reason="TAXPAYER_QUERY_TIME_REQUIRED";
                    else basis="SOURCE_STATUS_AT_REFERENCE_DATE";
                }else if(formalRegistration){
                    if(starts.size()!=1||starts.get(0).isAfter(reference))reason="TAXPAYER_EFFECTIVE_DATE_REQUIRED";
                    else if(ends.size()>1||!ends.isEmpty()&&(ends.get(0).isBefore(starts.get(0))||ends.get(0).isBefore(reference)))reason="TAXPAYER_HISTORICAL_LIMITED_PROOF";
                    else if(ends.isEmpty()&&compact.matches("(?s).*(?:有效期|有效期限|截止日期|期限届满|限期).*"))reason="TAXPAYER_VALIDITY_RANGE_REQUIRED";
                    else basis=ends.isEmpty()?"FORMAL_REGISTRATION_NO_STATED_EXPIRY":"SOURCE_VALIDITY_COVERS_REFERENCE_DATE";
                }else reason="TAXPAYER_FORMAL_DOCUMENT_REQUIRED";
            }
            checks.add(linked("documentId",document.id(),"documentHash",document.sha256(),"page",page.number(),"sourceId",source.id(),"reasonCode",reason,"accepted",reason.isBlank()));
            if(!reason.isBlank()){inspected.add(evidence);continue;}
            accepted.add(evidence);proofs.add(linked("subject",company,"taxpayerId",numbers.iterator().next(),"basis",basis,
                    "effectiveDates",starts.stream().map(LocalDate::toString).toList(),"endDates",ends.stream().map(LocalDate::toString).toList(),
                    "queryDates",queryDates.stream().map(LocalDate::toString).toList(),"source",evidence,"documentHash",document.sha256(),"documentVersion",document.version()));
        }
        Map<String,Object> proof=linked("executor",VERSION,"proofScope","SOURCE_BOUND_TAXPAYER_QUALIFICATION_AT_REFERENCE_DATE","subject",company,
                "referenceDate",reference.toString(),"checks",checks,"qualifiedDocuments",proofs,"externalTaxRegistryQueried",false,"absenceOrDisqualificationProven",false);
        if(conflict)return new Decision(rule.id(),rule.description(),"UNDETERMINED","已提供税务来源存在状态冲突，需明确适用记录；不能仅凭一份历史登记认定当前资格",combineEvidence(accepted,inspected),List.of(gap("TAXPAYER_CURRENT_STATUS_CONFLICT","需对应当前适用税务状态与时点")),proof);
        if(!accepted.isEmpty())return new Decision(rule.id(),rule.description(),"PASS","已核对同一当前投标单位的税务文书名称、识别号、资格表述和适用日期；未以标题或历史辅导期材料代替",accepted,List.of(),proof);
        return new Decision(rule.id(),rule.description(),"UNDETERMINED","已读材料尚不足以证明本次适用的一般纳税人资格；历史辅导期材料不证明企业失去资格，可继续核对正式税务证明或本项目开票承诺替代分支",inspected,
                List.of(gap("CURRENT_TAXPAYER_PROOF_REQUIRED","需适用当前单位和时点的税务证明；已有开票承诺由独立替代分支核验")),proof);
    }
    private static Set<String> labels(Pattern pattern,String text,int group){Set<String> out=new LinkedHashSet<>();Matcher m=pattern.matcher(text);while(m.find())out.add(clean(m.group(group).replace("`","").replace("*","")));return out;}
    private static List<LocalDate> dates(Pattern pattern,String text){Set<LocalDate> out=new LinkedHashSet<>();Matcher m=pattern.matcher(text);while(m.find())try{out.add(LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))));}catch(RuntimeException ignored){}return List.copyOf(out);}
    private static List<Evidence> combineEvidence(List<Evidence> a,List<Evidence> b){List<Evidence> out=new ArrayList<>(a);out.addAll(b);return out.stream().distinct().toList();}
}
