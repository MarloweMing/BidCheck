package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A certificate record is the proof document; it need not literally contain “证明文件”. */
final class PersonnelCertificateDocumentProof {
    static final String VERSION="personnel-certificate-document-1-source-bound-record";
    private static final Logger LOG=LoggerFactory.getLogger(PersonnelCertificateDocumentProof.class);
    private PersonnelCertificateDocumentProof(){}

    static boolean applies(Rule rule){
        return "MATERIAL".equals(rule.predicate())&&"证明文件".equals(rule.expected())
            &&"CERTIFICATE_PROOF_DOCUMENTS".equals(str(rule.parameters(),"deterministicFamily"))
            &&PersonnelRoles.personRole(rule.subject());
    }

    /** Recompute from the current immutable records. A serialized proof flag alone authorizes nothing. */
    static boolean proven(Rule rule,Decision decision,RuleEvaluator evaluator){
        if(!applies(rule)||!"PASS".equals(decision.status())||!decision.gaps().isEmpty()||!rule.id().equals(decision.id()))return false;
        Decision fresh=evaluate(rule,evaluator);
        return "PASS".equals(fresh.status())&&fresh.gaps().isEmpty()&&fresh.evidences().equals(decision.evidences())
            &&Objects.equals(fresh.proof().get("selectedRecords"),decision.proof().get("selectedRecords"))
            &&Objects.equals(fresh.proof().get("subject"),decision.proof().get("subject"))
            &&Objects.equals(fresh.proof().get("children"),decision.proof().get("children"));
    }

    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        String requirement=CertificateSourceComparison.requirementQuote(rule,evaluator.context);
        String original=str(evaluator.context,"originalRequirement");
        if(requirement.equals(rule.sourceQuote())||!original.contains(requirement)
            ||!requirement.contains(rule.sourceQuote())||!requirement.contains(rule.subject()))
            return pending(rule,"REQUIREMENT_CREDENTIAL_SCHEMA_UNPROVEN","证明文件范围尚未对应原条款中的人员及证照要求",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf());
        List<String> people=evaluator.resolvedSubjects(rule);
        if(people.isEmpty()||people.stream().anyMatch(DomainBindings::role))
            return pending(rule,"SUBJECT_RELATION_UNPROVEN","尚未把本次岗位对应到具体人员，不能将任意同类证书作为证明文件",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf());
        List<Decision> children=new ArrayList<>();
        for(String person:people)children.add(person(rule,person,evaluator));
        Decision result=children.size()==1?children.get(0):RuleEvaluator.combine(rule.id(),rule.description(),"ALL",children);
        LOG.info("bidcheck.personnel_document_proof ruleId={} executor={} subjects={} status={} reasonCodes={} evidencePages={} proofScope={}",rule.id(),VERSION,people.size(),result.status(),cn.bidcheck.compat.Java8Api.toList(result.gaps().stream().map(Gap::code)),cn.bidcheck.compat.Java8Api.toList(result.evidences().stream().map(e->e.documentId()+":"+e.page()).distinct()),"TYPED_CERTIFICATE_RECORD_SUBMISSION_ONLY");
        return result;
    }

    private static Decision person(Rule rule,String person,RuleEvaluator evaluator){
        List<CertificateSourceComparison.Record> records=cn.bidcheck.compat.Java8Api.toList(evaluator.personnelCredentialRecords().stream()
            .filter(r->"CERTIFICATE".equals(r.side())&&ConfirmedSubjectBindings.recordMatches(person,r,evaluator)));
        Set<String> categories=CertificateSourceComparison.requiredCategories(rule,records,evaluator.context);
        Map<String,Object> proof=linked("executor",VERSION,"subject",person,"requirementQuote",CertificateSourceComparison.requirementQuote(rule,evaluator.context),
            "candidateRecords",cn.bidcheck.compat.Java8Api.toList(records.stream().map(CertificateSourceComparison.Record::audit)),"requiredCategories",categories,
            "proofScope","TYPED_CERTIFICATE_RECORD_SUBMISSION_ONLY","qualificationVerified",false,"validityVerified",false,
            "queryFieldsCompared",false,"registrationVerified",false,"fullPageVisualVerification",false);
        if(records.isEmpty())return pending(rule,"CERTIFICATE_EVIDENCE_REQUIRED","当前语料尚未建立该人员的证书原件记录；目录、查询截图或泛材料标题不能代替",cn.bidcheck.compat.Java8Api.listOf(),proof);
        if(categories.isEmpty()&&CertificateSourceComparison.requirementQuote(rule,evaluator.context).contains("安全生产考核合格证")&&
            records.stream().anyMatch(r->r.category().startsWith("安全生产考核合格证书")))
            return pending(rule,"CERTIFICATE_ALLOWED_SUBTYPE_NOT_LOCATED","已找到该人员安全证原件，但其细类与条款列明的类别尚未对应",cn.bidcheck.compat.Java8Api.listOf(),proof);
        if(categories.isEmpty())return pending(rule,"REQUIREMENT_CREDENTIAL_SCHEMA_UNPROVEN","尚未按人员原条款确定应提交的证照类别，不能用其他证件替代",cn.bidcheck.compat.Java8Api.listOf(),proof);
        List<Evidence> evidence=new ArrayList<>();List<Fact> identityFacts=new ArrayList<>();List<Map<String,Object>> selected=new ArrayList<>();
        for(String category:categories){
            List<CertificateSourceComparison.Record> applicable=cn.bidcheck.compat.Java8Api.toList(records.stream().filter(r->r.category().equals(category)));
            if(applicable.size()!=1)return pending(rule,"CERTIFICATE_SELECTION_REQUIRED","同一人员、同一证照类别尚未确定唯一原件记录；保留多版本，不择优合并",evidence,proof);
            cn.bidcheck.verification.engine.CertificateSourceComparison.Record record=applicable.get(0);
            if(!record.issues().isEmpty()||!sourceFieldsBound(record,evaluator.corpus))
                return pending(rule,"CERTIFICATE_FIELD_SOURCE_MISMATCH","证照记录存在冲突或字段引用未与原文绑定，不能用摘要代替证明原件",evidence,proof);
            Fact identity=ConfirmedSubjectBindings.recordIdentity(record);
            if(identity==null)return pending(rule,"CERTIFICATE_IDENTITY_REQUIRED","所引证书缺少明确类型的证号或注册编号，尚不能建立对应原件",evidence,proof);
            String issue=new ProofVerifier().issue(identity,evaluator.corpus.byId);
            if(issue==null)issue=DomainBindings.issue(identity,evaluator.corpus.byId,cn.bidcheck.compat.Java8Api.listOf());
            if(issue!=null)return pending(rule,issue,"所引证照的主体、编号和原件来源未通过核验",evidence,proof);
            Rule identityRule=new Rule(rule.id(),rule.description(),"CERTIFICATE",rule.subject(),"certificate","","","",rule.parameters(),rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
            cn.bidcheck.verification.engine.SubjectIdentity.Problem binding=evaluator.identities.issue(identityRule,cn.bidcheck.compat.Java8Api.listOf(identity));
            if(binding!=null){
                proof.put("confirmationCandidates",evaluator.identities.confirmationCandidates(identityRule,cn.bidcheck.compat.Java8Api.listOf(identity)));
                return pending(rule,binding.code(),binding.reason(),cn.bidcheck.compat.Java8Api.toList(record.fields().values().stream().map(CertificateSourceComparison.Field::evidence)),proof);
            }
            identityFacts.add(identity);selected.add(record.audit());record.fields().values().stream().map(CertificateSourceComparison.Field::evidence).forEach(evidence::add);
        }
        proof.put("selectedRecords",selected);proof.put("identityBindings",evaluator.identities.bindings(identityFacts));
        proof.put("factIds",cn.bidcheck.compat.Java8Api.toList(identityFacts.stream().map(Fact::id)));
        return new Decision(rule.id(),rule.description(),"PASS","已核到本次人员对应类别的证书原件记录；资质等级、有效期、查询一致性及注册单位分别核验",cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),cn.bidcheck.compat.Java8Api.listOf(),proof);
    }

    private static boolean sourceFieldsBound(CertificateSourceComparison.Record record,EvidenceCorpus corpus){
        if(!record.fields().containsKey("NAME"))return false;
        for(cn.bidcheck.verification.engine.CertificateSourceComparison.Field field:record.fields().values()){
            Source source=corpus.byId.get(field.sourceId());if(source==null||source.index())return false;
            Evidence original=source.evidence(),part=field.evidence();
            if(!original.documentId().equals(part.documentId())||original.page()!=part.page()||!original.locationType().equals(part.locationType()))return false;
            java.util.regex.Matcher outer=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(original.anchor());java.util.regex.Matcher inner=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(part.anchor());
            if(!outer.matches()||!inner.matches())return false;
            int from=Integer.parseInt(inner.group(1))-Integer.parseInt(outer.group(1)),to=Integer.parseInt(inner.group(2))-Integer.parseInt(outer.group(1));
            if(from<0||to<=from||to>original.quote().length()||!field.raw().equals(part.quote())||!original.quote().substring(from,to).equals(part.quote()))return false;
        }
        return !record.fields().isEmpty();
    }
    private static Decision pending(Rule rule,String code,String reason,List<Evidence> evidence,Map<String,Object> proof){
        return new Decision(rule.id(),rule.description(),"UNDETERMINED",reason,evidence,cn.bidcheck.compat.Java8Api.listOf(gap(code,reason)),proof);
    }
}
