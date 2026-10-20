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
        Map<String,Object> proof=linked("executor","REGISTRATION_EMPLOYER_RELATION","subject",person,"declarationCannotProveRegistration",true,"expectedEmployers",employers,"records",records.stream().map(CertificateSourceComparison.Record::audit).toList(),"requirementQuote",rule.sourceQuote());
        if(employers.size()!=1)return evaluator.unknown(rule,"REGISTRATION_EMPLOYER_BINDING_REQUIRED","本次人员所属单位尚未由独立任职原文确定，不能用证书注册单位反向证明其所属单位",links,proof);
        if(records.size()!=1||records.get(0).value("EMPLOYER").isBlank())return evaluator.unknown(rule,"REGISTRATION_CERTIFICATE_REQUIRED","人员所属单位已确定，仍需唯一适用注册证书及其中的注册或聘用单位字段",links,proof);
        var record=records.get(0);String expected=employers.iterator().next(),actual=record.value("EMPLOYER");proof.putAll(linked("expectedEmployer",expected,"registeredEmployer",actual,"comparisonField",record.fields().get("EMPLOYER").audit(),"proofScope","REGISTRATION_UNIT_ONLY","doesNotProveValidityOrOnlineStatus",true));
        List<Evidence> evidence=new ArrayList<>();links.stream().flatMap(f->f.sourceIds().stream()).distinct().map(evaluator.corpus.byId::get).filter(Objects::nonNull).map(Source::evidence).forEach(evidence::add);record.fields().values().stream().map(CertificateSourceComparison.Field::evidence).forEach(evidence::add);
        boolean equal=clean(expected).equals(clean(actual));return new Decision(rule.id(),rule.description(),equal?"PASS":"FAIL",equal?"原文独立证明人员所属单位，证书中注册或聘用单位与其一致":"人员所属单位与已定位证书中的注册或聘用单位不同",evidence.stream().distinct().toList(),List.of(),proof);
    }
}
