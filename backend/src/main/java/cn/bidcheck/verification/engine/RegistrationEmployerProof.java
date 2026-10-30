package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Registration is a relation between a holder, a credential and an employer, not a declaration. */
final class RegistrationEmployerProof {
    private RegistrationEmployerProof() {}
    static boolean applies(Rule rule){return Set.of("DECLARATION","RELATION").contains(rule.predicate())
        &&clean(rule.sourceQuote()).matches(".*注册.{0,25}(?:本单位|投标人|投标单位).{0,12}(?:完成注册|注册).*|.*(?:本单位|投标人|投标单位).{0,12}完成注册.*")
        &&clean(rule.description()+rule.field()).matches(".*(?:完成注册|注册单位|注册关系|在本单位.*注册).* ".strip());}

    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        List<String> subjects=evaluator.resolvedSubjects(rule);if(subjects.isEmpty())return evaluator.unknown(rule,"SUBJECT_RELATION_UNPROVEN","须先确认本次岗位人员，才能检查其证书注册单位",List.of());
        List<Decision> decisions=new ArrayList<>();for(String person:subjects)decisions.add(person(rule,person,evaluator));
        return decisions.size()==1?decisions.get(0):RuleEvaluator.combine(rule.id(),rule.description(),"ALL",decisions);
    }
    private static Decision person(Rule rule,String person,RuleEvaluator evaluator){
        List<Fact> links=new ArrayList<>();Set<String> employers=new LinkedHashSet<>();
        for(Fact fact:evaluator.facts){if(!"CURRENT_PROJECT".equals(fact.scope())||new ProofVerifier().issue(fact,evaluator.corpus.byId)!=null||DomainBindings.issue(fact,evaluator.corpus.byId,List.of())!=null)continue;
            if(fact.sourceIds().stream().map(evaluator.corpus.byId::get).filter(Objects::nonNull).anyMatch(s->s.index()||s.historical()||s.scopeContext().blocked()))continue;
            if(fact.kind().equals("RELATION")&&fact.subject().equals(person)&&DomainBindings.employment(fact.field())&&!str(fact.attributes(),"object").isBlank()){links.add(fact);employers.add(str(fact.attributes(),"object"));}
            if(fact.kind().equals("PERSON_ROLE")&&fact.value().equals(person)&&DomainBindings.currentRoleSource(fact,evaluator.corpus.byId)&&!str(fact.attributes(),"employer").isBlank()){links.add(fact);employers.add(str(fact.attributes(),"employer"));}
        }
        List<CertificateSourceComparison.Record> records=CertificateSourceComparison.records(evaluator.corpus).stream().filter(r->r.side().equals("CERTIFICATE")&&r.owner().equals(person)&&r.issues().isEmpty()).toList();
        boolean memberRegistration=false;String requiredMemberRole=registrationMemberRole(rule);
        if(employers.isEmpty()&&!requiredMemberRole.isBlank()){
            Rule memberRule=new Rule(rule.id()+"-member","原条款对应成员单位","RELATION",requiredMemberRole,"名称","","","",Map.of(),rule.sourceQuote(),"",List.of());
            List<Fact> memberFacts=EnterpriseSubjectBindings.extract(List.of(memberRule),evaluator.corpus.all);
            for(Fact fact:memberFacts)if(fact.subject().equals(requiredMemberRole)&&new ProofVerifier().issue(fact,evaluator.corpus.byId)==null&&DomainBindings.issue(fact,evaluator.corpus.byId,List.of())==null){employers.add(str(fact.attributes(),"object"));links.add(fact);}
            memberRegistration=!employers.isEmpty();
        }
        if(Boolean.TRUE.equals(rule.parameters().get("registrationConditional"))){
            String requirement=str(rule.parameters(),"credentialRequirement").split("须提交有效证明文件",2)[0];
            boolean safetyOnly=requirement.contains("安全生产考核合格证")&&!requirement.matches("(?s).*注册(?:建造师|建筑师|监理工程师|造价工程师).*" );
            if(safetyOnly){
                List<CertificateSourceComparison.Record> applicable=records.stream().filter(r->r.category().contains("安全生产考核")).toList();
                if(applicable.size()==1){var certificate=applicable.get(0);
                    return new Decision(rule.id(),rule.description(),"NOT_APPLICABLE","已定位本人员的安全生产考核证；原条款的本单位注册条件仅适用于注册类资质，当前考核证不作注册证处理",
                        certificate.fields().values().stream().map(CertificateSourceComparison.Field::evidence).distinct().toList(),List.of(),
                        linked("executor","REGISTRATION_EMPLOYER_RELATION","applicability","NON_REGISTRATION_CREDENTIAL","credentialRequirement",requirement,"record",certificate.audit(),"doesNotProveCredentialQualification",true));
                }
            }
            records=records.stream().filter(r->r.category().contains("注册")).toList();
        }
        Map<String,Object> proof=linked("executor","REGISTRATION_EMPLOYER_RELATION","subject",person,"declarationCannotProveRegistration",true,"expectedEmployers",employers,"records",records.stream().map(CertificateSourceComparison.Record::audit).toList(),"requirementQuote",rule.sourceQuote(),"expectedUnitBasis",memberRegistration?"CURRENT_ROLE_AND_EXPLICIT_CONSORTIUM_WORK_ALLOCATION":"INDEPENDENT_EMPLOYMENT_SOURCE","requiredMemberRole",requiredMemberRole,"doesNotProveEmployment",memberRegistration);
        if(employers.size()!=1)return evaluator.unknown(rule,"REGISTRATION_EMPLOYER_BINDING_REQUIRED","本次人员所属单位尚未由独立任职原文确定，不能用证书注册单位反向证明其所属单位",links,proof);
        if(records.size()!=1||records.get(0).value("EMPLOYER").isBlank())return evaluator.unknown(rule,"REGISTRATION_CERTIFICATE_REQUIRED","人员所属单位已确定，仍需唯一适用注册证书及其中的注册或聘用单位字段",links,proof);
        var record=records.get(0);
        if(memberRegistration){
            var number=record.fields().get("CERTIFICATE_NUMBER");
            if(number==null)number=record.fields().get("REGISTRATION_NUMBER");
            if(number==null)return evaluator.unknown(rule,"REGISTRATION_CERTIFICATE_REQUIRED","已确定应注册的成员单位，但注册证书尚缺同类型的明确证号",links,proof);
            Fact certificate=new Fact("registration-proof:"+record.id(),"CERTIFICATE",person,"证书",number.value(),number.value(),"","","UNRESOLVED",record.sources().stream().map(Source::id).toList(),Map.of("category",record.category(),"number",number.value(),"numberField",number.label()));
            // Typed original records already carry independently anchored raw fields; a normalized
            // category/parenthesis is not required to occur verbatim in the PDF a second time.
            boolean typed=record.id().startsWith("PR")&&record.fields().containsKey("NAME")&&record.fields().containsKey("EMPLOYER")
                &&record.fields().values().stream().allMatch(field->{Source source=evaluator.corpus.byId.get(field.sourceId());return source!=null&&source.evidence().documentId().equals(field.evidence().documentId())&&source.evidence().page()==field.evidence().page();});
            String sourceIssue=typed?null:new ProofVerifier().issue(certificate,evaluator.corpus.byId),bindingIssue=typed?null:DomainBindings.issue(certificate,evaluator.corpus.byId,List.of());
            if(sourceIssue!=null||bindingIssue!=null){proof.put("certificateSourceValidation",linked("sourceIssue",sourceIssue,"bindingIssue",bindingIssue));return evaluator.unknown(rule,"REGISTRATION_CERTIFICATE_IDENTITY_REQUIRED","已定位注册记录，但证书姓名、类别和证号尚未形成可核源的同一记录",links,proof);}
            Rule identityRule=new Rule(rule.id(),rule.description(),"CERTIFICATE",rule.subject(),"证书","","","",Map.of("category",record.category()),rule.sourceQuote(),"",List.of());
            var issue=evaluator.identities.issue(identityRule,List.of(certificate));if(issue!=null)return evaluator.unknown(rule,issue.code(),issue.reason(),List.of(certificate),proof);
            links.add(certificate);
        }
        String expected=employers.iterator().next(),actual=record.value("EMPLOYER");proof.putAll(linked("expectedEmployer",expected,"registeredEmployer",actual,"comparisonField",record.fields().get("EMPLOYER").audit(),"proofScope","REGISTRATION_UNIT_ONLY","doesNotProveValidityOrOnlineStatus",true));
        List<Evidence> evidence=new ArrayList<>();links.stream().flatMap(f->f.sourceIds().stream()).distinct().map(evaluator.corpus.byId::get).filter(Objects::nonNull).map(Source::evidence).forEach(evidence::add);record.fields().values().stream().map(CertificateSourceComparison.Field::evidence).forEach(evidence::add);
        boolean equal=clean(expected).equals(clean(actual));return new Decision(rule.id(),rule.description(),equal?"PASS":"FAIL",equal?(memberRegistration?"原条款岗位已对应本次联合体成员，证书注册单位与该成员一致；仅完成注册单位核验":"原文独立证明人员所属单位，证书中注册或聘用单位与其一致"):"本条件要求的单位与已定位证书中的注册或聘用单位不同",evidence.stream().distinct().toList(),List.of(),proof);
    }
    private static String registrationMemberRole(Rule rule){
        String requested=str(rule.parameters(),"registrationEmployerRole"),requirement=clean(str(rule.parameters(),"credentialRequirement")),role=PersonnelRoles.canonical(rule.subject());
        if(requested.equals("设计单位")&&role.equals("设计负责人")&&requirement.matches("^(?:设计部分负责人|设计负责人)资格要求[:：].*"))return requested;
        if(requested.equals("施工单位")&&role.equals("施工负责人")&&requirement.matches("^(?:施工部分负责人|施工负责人)资格要求[:：].*"))return requested;
        return "";
    }
}
