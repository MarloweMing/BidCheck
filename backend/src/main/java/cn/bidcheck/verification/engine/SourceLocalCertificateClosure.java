package cn.bidcheck.verification.engine;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A positive qualification is an existential claim about a selected record, not a census
 * of every retrieved credential. Re-read its original fields and same-number counterevidence. */
final class SourceLocalCertificateClosure {
    static final String VERSION="selected-certificate-source-review-1";
    private static final Logger LOG=LoggerFactory.getLogger(SourceLocalCertificateClosure.class);
    private static final Pattern RANK=Pattern.compile("(特级|甲级|乙级|丙级|丁级|一级|二级|三级|壹级|贰级|叁级)");
    private static final Pattern HOLDER=Pattern.compile("(?:企业名称|单位名称|组织名称|持证单位)\\s*[:：]\\s*([^\\r\\n|]+)");
    private static final Pattern NUMBER=Pattern.compile("(?:资质证书编号|证书编号|证书号码|证号)\\s*[:：]\\s*([A-Za-z0-9][A-Za-z0-9()（）/-]{3,79})");
    private SourceLocalCertificateClosure(){}

    /** Even a fully read field receipt cannot make conflicting same-number versions agree. */
    static Set<String> conflictingFactIds(List<Fact> facts){
        Map<String,List<Fact>> records=new LinkedHashMap<>();Set<String> conflicts=new LinkedHashSet<>();
        for(Fact fact:facts)records.computeIfAbsent(normal(fact.subject())+"|"+normal(CertificateEvidenceRecords.category(str(fact.attributes(),"category")))+"|"+normal(str(fact.attributes(),"number")),key->new ArrayList<>()).add(fact);
        for(List<Fact> record:records.values())for(String key:cn.bidcheck.compat.Java8Api.listOf("grade", "profession", "employer")){
            Set<String> values=new LinkedHashSet<>();for(Fact fact:record){String v=str(fact.attributes(),key);if(!cn.bidcheck.compat.Java8Api.isBlank(v))values.add(value(key,v));}
            if(values.size()>1)record.forEach(f->conflicts.add(f.id()));
        }
        return conflicts;
    }

    static boolean applies(Rule rule){
        return rule.predicate().equals("CERTIFICATE")&&rule.children().isEmpty()
            &&!cn.bidcheck.compat.Java8Api.isBlank(str(rule.parameters(),"category"))
            &&!str(rule.parameters(),"category").matches(".*(?:建造师|建筑师|监理工程师|造价工程师|安全生产考核|职称).*" )
            &&!Boolean.TRUE.equals(rule.parameters().get("requiresFullDocument"))
            &&!Boolean.TRUE.equals(rule.parameters().get("fullDocumentRead"))
            &&RequirementContextBindings.validatedSubjectSet(rule).isEmpty();
    }

    static Map<String,Object> proof(Rule rule,Decision decision,RuleEvaluator evaluator){
        if(!applies(rule)||!"PASS".equals(decision.status())||!decision.gaps().isEmpty()||!rule.id().equals(decision.id()))return cn.bidcheck.compat.Java8Api.mapOf();
        long started=System.nanoTime();Set<String> used=new LinkedHashSet<>();factIds(decision.proof(),used);
        List<String> subjects=evaluator.resolvedSubjects(rule);
        if(subjects.isEmpty()||subjects.stream().anyMatch(DomainBindings::role))return cn.bidcheck.compat.Java8Api.mapOf();
        String category=str(rule.parameters(),"category");
        List<Map<String,Object>> witnesses=new ArrayList<>();
        for(String subject:subjects){
            Map<String,Object> witness=cn.bidcheck.compat.Java8Api.mapOf();
            for(Fact fact:evaluator.facts){
                if(!used.contains(fact.id())||!fact.kind().equals("CERTIFICATE")||!subject.equals(fact.subject())
                    ||!DomainBindings.sameCertificateCategory(str(fact.attributes(),"category"),category)
                    ||!DomainBindings.certificateIdentity(fact))continue;
                if(new ProofVerifier().issue(fact,evaluator.corpus.byId)!=null||DomainBindings.issue(fact,evaluator.corpus.byId,cn.bidcheck.compat.Java8Api.listOf())!=null)continue;
                // Recompute the selected record against this rule. A model or cached PASS is not proof.
                Rule concrete=new Rule(rule.id(),rule.description(),rule.predicate(),subject,rule.field(),rule.operator(),rule.expected(),rule.unit(),rule.parameters(),rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
                Decision checked=evaluator.certificate(concrete,cn.bidcheck.compat.Java8Api.listOf(fact));
                if(!"PASS".equals(checked.status())||!checked.gaps().isEmpty())continue;
                List<CertificateEvidenceRecords.Bound> bounds=CertificateEvidenceRecords.certificates(fact,evaluator.corpus);
                if(bounds.isEmpty())continue;
                Map<String,Object> review=review(fact,evaluator);
                if(!Boolean.TRUE.equals(review.get("complete"))){LOG.info("bidcheck.selected_certificate_review ruleId={} executor={} factId={} status=UNRESOLVED reason={}",rule.id(),VERSION,fact.id(),review.getOrDefault("reason","SAME_NUMBER_SOURCE_REVIEW_UNRESOLVED"));continue;}
                witness=linked("factId",fact.id(),"subject",subject,"category",category,"certificateNumber",str(fact.attributes(),"number"),
                    "sourceIds",cn.bidcheck.compat.Java8Api.toList(bounds.stream().map(b->b.source().id()).distinct()),"sameNumberReview",review,
                    "proves","SELECTED_CERTIFICATE_FIELDS_ONLY","validityProven",false,"queryScreenshotProven",false);
                break;
            }
            if(witness.isEmpty())return cn.bidcheck.compat.Java8Api.mapOf();
            witnesses.add(witness);
        }
        LOG.info("bidcheck.selected_certificate_review ruleId={} executor={} status=CLOSED selectedRecords={} elapsedMs={}",rule.id(),VERSION,witnesses.size(),(System.nanoTime()-started)/1_000_000);
        return linked("version",VERSION,"basis","REVERIFIED_SELECTED_CERTIFICATE_AND_SAME_NUMBER_COUNTEREVIDENCE",
            "scope","SELECTED_CERTIFICATE_FIELDS_ONLY","exhaustiveCandidateReview",false,"witnesses",witnesses,
            "sourceReadReceipt",ProgramSourceRead.receipt(evaluator.corpus,cn.bidcheck.compat.Java8Api.listOf()));
    }

    private static Map<String,Object> review(Fact selected,RuleEvaluator evaluator){
        String number=normal(str(selected.attributes(),"number")),category=str(selected.attributes(),"category");
        Map<String,CertificateEvidenceRecords.ScopeResolver> scopes=new HashMap<>();
        Set<String> examined=new LinkedHashSet<>();List<Map<String,Object>> versions=new ArrayList<>();
        List<QualificationProofAssociation.Record> queryRecords=CertificateEvidenceRecords.qualificationQueries(evaluator.corpus,selected.subject(),category);
        // Reconcile already extracted same-number facts too: one good version cannot hide
        // a different holder/grade/profession/employer recorded for that same certificate.
        for(Fact fact:evaluator.facts){
            if(!fact.kind().equals("CERTIFICATE")||!number.equals(normal(str(fact.attributes(),"number")))
                ||!DomainBindings.sameCertificateCategory(category,str(fact.attributes(),"category")))continue;
            if(new ProofVerifier().issue(fact,evaluator.corpus.byId)!=null||DomainBindings.issue(fact,evaluator.corpus.byId,cn.bidcheck.compat.Java8Api.listOf())!=null)continue;
            boolean current=fact.sourceIds().stream().map(evaluator.corpus.byId::get).anyMatch(s->CertificateEvidenceRecords.usable(s,evaluator.corpus,scopes));
            if(!current)continue;
            if(!selected.subject().equals(fact.subject()))return cn.bidcheck.compat.Java8Api.mapOf("complete", false);
            for(String key:cn.bidcheck.compat.Java8Api.listOf("grade", "profession", "employer")){
                String a=str(selected.attributes(),key),b=str(fact.attributes(),key);
                if(!cn.bidcheck.compat.Java8Api.isBlank(a)&&!cn.bidcheck.compat.Java8Api.isBlank(b)&&!value(key,a).equals(value(key,b)))return cn.bidcheck.compat.Java8Api.mapOf("complete", false);
            }
        }
        for(Source source:evaluator.corpus.all){
            if(!CertificateEvidenceRecords.usable(source,evaluator.corpus,scopes))continue;
            String text=source.evidence().quote();
            if(!hasNumber(text,number)||text.matches("(?s).*(?:填写示例|示范文本|证书模板).*"))continue;
            if(!text.matches("(?s).*(?:资质证书|资格证书|认证证书|许可证|查询截图|查询结果|资质查询|资质详情|证书编号).*"))continue;
            examined.add(source.id());
            String plain=normal(text);
            if(plain.matches("(?s).*(?:吊销|撤销|注销|作废|已失效|不具备|取消资质).*"))return cn.bidcheck.compat.Java8Api.mapOf("complete", false);
            // Query tables are interpreted row by row. A shared number may hold several
            // categories; never attach the grade of another row, or inherit a blank cell.
            if(cn.bidcheck.compat.Java8Extras.lines(text).anyMatch(line->cn.bidcheck.compat.Java8Api.strip(line).startsWith("|"))){
                if(!tableCounterevidence(selected,source,evaluator,queryRecords,versions))return linked("complete",false,"reason","SAME_NUMBER_QUERY_TABLE_CONFLICT_OR_UNMAPPED");
                continue;
            }
            Set<String> owners=groups(HOLDER,text,1);
            Set<String> numbers=groups(NUMBER,text,1);
            if(numbers.stream().map(SourceLocalCertificateClosure::normal).noneMatch(number::equals)){
                // A wrapped/repeated source may contain the literal number without a typed
                // label. Only an existing verified same-record field binding can cover it.
                boolean bound=selected.sourceIds().contains(source.id());
                if(!bound&&plain.contains(normal(category)))return cn.bidcheck.compat.Java8Api.mapOf("complete", false);
                continue;
            }
            if(numbers.size()!=1||owners.size()!=1)return cn.bidcheck.compat.Java8Api.mapOf("complete", false);
            if(!normal(owners.iterator().next()).equals(normal(selected.subject())))return cn.bidcheck.compat.Java8Api.mapOf("complete", false);
            Set<String> ranks=categoryRanks(text,category);
            String grade=str(selected.attributes(),"grade");
            if(!cn.bidcheck.compat.Java8Api.isBlank(grade)&&(ranks.size()!=1||!ranks.contains(value("grade",grade))))return cn.bidcheck.compat.Java8Api.mapOf("complete", false);
            versions.add(linked("sourceId",source.id(),"kind","CERTIFICATE_RECORD","category",category,"grades",ranks));
        }
        return linked("complete",true,"examinedSourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(examined),"sameNumberRecords",versions,
            "counterevidenceScope","CURRENT_TYPED_SAME_NUMBER_RECORDS_IN_FROZEN_TEXT","unreadImageCoverageProven",false);
    }

    private static boolean tableCounterevidence(Fact selected,Source source,RuleEvaluator evaluator,List<QualificationProofAssociation.Record> queryRecords,List<Map<String,Object>> versions){
        String number=normal(str(selected.attributes(),"number")),category=str(selected.attributes(),"category"),text=source.evidence().quote();
        // Read the whole immutable page so table headers and rows split across retrieval
        // blocks remain together. The table reader validates precise source offsets.
        cn.bidcheck.domain.Domain.Page page=evaluator.corpus.pages.get(EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType()));
        if(page==null)return false;
        // A key/value table or an unknown table schema may contain the same number and
        // a contradictory field on separate lines. Do not silently consider it reviewed.
        boolean typedHeader=cn.bidcheck.compat.Java8Extras.lines(page.text()).filter(line->cn.bidcheck.compat.Java8Api.strip(line).startsWith("|")).map(SourceLocalCertificateClosure::normal)
            .anyMatch(line->line.matches(".*(?:资质证书号|资质证书编号|证书编号|证书号码).*" )&&line.matches(".*(?:资质名称|资质类别及等级|资质类别|证书类别).*"));
        if(!typedHeader)return false;
        List<QualificationProofAssociation.Record> rows=cn.bidcheck.compat.Java8Api.toList(queryRecords.stream()
            .filter(r->r.source().evidence().documentId().equals(source.evidence().documentId())&&r.source().evidence().page()==source.evidence().page())
            .filter(r->normal(r.number()).equals(number)));
        for(cn.bidcheck.verification.engine.QualificationProofAssociation.Record row:rows){
            if(!cn.bidcheck.compat.Java8Api.isBlank(str(selected.attributes(),"grade"))&&!value("grade",row.grade()).equals(value("grade",str(selected.attributes(),"grade"))))return false;
            versions.add(linked("sourceId",row.source().id(),"kind","QUERY_TABLE_RECORD","record",row.audit()));
        }
        // A numbered row for the selected category must have been parsed. Blank-number rows
        // are unresolved query proof, but are not a typed contradiction for this certificate.
        for(String line:page.text().split("\\R"))if(cn.bidcheck.compat.Java8Api.strip(line).startsWith("|")&&hasNumber(line,number)&&normal(line).contains(normal(category))&&rows.isEmpty())return false;
        return true;
    }
    private static Set<String> categoryRanks(String text,String category){
        String plain=normal(text),target=normal(category);Set<String> values=new LinkedHashSet<>();
        Matcher attached=Pattern.compile(Pattern.quote(target)+"(?:资质)?(?:等级)?[:：]?"+RANK.pattern()).matcher(plain);
        while(attached.find())values.add(value("grade",attached.group(1)));
        // A standalone 等级 label is valid only for a record with one category, not a
        // multi-qualification certificate whose following lines carry their own grades.
        if(values.isEmpty()&&plain.contains(target)){
            Matcher labeled=Pattern.compile("(?:资质等级|等级)[:：]"+RANK.pattern()).matcher(plain);
            while(labeled.find())values.add(value("grade",labeled.group(1)));
        }
        return values;
    }
    private static Set<String> groups(Pattern pattern,String text,int group){Set<String> out=new LinkedHashSet<>();Matcher m=pattern.matcher(text);while(m.find())out.add(cn.bidcheck.compat.Java8Api.strip(m.group(group)));return out;}
    private static boolean hasNumber(String text,String number){return Pattern.compile("(?<![A-Z0-9])"+Pattern.quote(number)+"(?![A-Z0-9])").matcher(normal(text)).find();}
    private static String normal(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("[\\s*`]","").toUpperCase(Locale.ROOT);}
    private static String value(String key,String value){return key.equals("grade")?CredentialGrades.normalize(value):normal(value);}
    private static void factIds(Object value,Set<String> ids){if(value instanceof Map<?,?>){if(((Map<?,?>) (value)).get("factIds") instanceof List<?>)((List<?>) (((Map<?,?>) (value)).get("factIds"))).forEach(id->ids.add(Objects.toString(id)));for(Object child:((Map<?,?>) (value)).values())factIds(child,ids);}else if(value instanceof List<?>)((List<?>) (value)).forEach(child->factIds(child,ids));}
}
