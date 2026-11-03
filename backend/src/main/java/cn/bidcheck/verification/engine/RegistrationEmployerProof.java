package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Registration is a relation between a holder, a credential and an employer, not a declaration. */
final class RegistrationEmployerProof {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RegistrationEmployerProof.class);
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class SelectedEvaluation{
        private final Decision decision;
        private final List<Fact> identityFacts;
        @com.fasterxml.jackson.annotation.JsonCreator
        SelectedEvaluation(@com.fasterxml.jackson.annotation.JsonProperty("decision") Decision decision, @com.fasterxml.jackson.annotation.JsonProperty("identityFacts") List<Fact> identityFacts) { this.decision = decision; this.identityFacts = identityFacts; }
        public Decision decision() { return decision; }
        public List<Fact> identityFacts() { return identityFacts; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; SelectedEvaluation that = (SelectedEvaluation) other; return java.util.Objects.equals(this.decision, that.decision) && java.util.Objects.equals(this.identityFacts, that.identityFacts); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.decision); hash = 31 * hash + java.util.Objects.hashCode(this.identityFacts); return hash; }
        @Override public String toString() { return "SelectedEvaluation[" + "decision=" + decision + ", identityFacts=" + identityFacts + "]"; }
}
    private RegistrationEmployerProof() {}
    static boolean applies(Rule rule){return cn.bidcheck.compat.Java8Api.setOf("DECLARATION", "RELATION").contains(rule.predicate())
        &&clean(rule.sourceQuote()).matches(".*注册.{0,25}(?:本单位|投标人|投标单位).{0,12}(?:完成注册|注册).*|.*(?:本单位|投标人|投标单位).{0,12}完成注册.*")
        &&clean(rule.description()+rule.field()).matches(cn.bidcheck.compat.Java8Api.strip(".*(?:完成注册|注册单位|注册关系|在本单位.*注册).* "));}

    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        return evaluateSelected(rule,evaluator).decision();
    }
    static SelectedEvaluation evaluateSelected(Rule rule,RuleEvaluator evaluator){
        List<Fact> selected=new ArrayList<>();Decision decision=evaluate(rule,evaluator,selected);
        return new SelectedEvaluation(decision,cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(decision.status())?cn.bidcheck.compat.Java8Api.listCopyOf(selected):cn.bidcheck.compat.Java8Api.listOf());
    }
    /** No stored PASS or selected-fact flag is authority: repeat the source selection,
     * verify every original field and then check same-number counterevidence. */
    static Map<String,Object> sourceReviewClosure(Rule rule,Decision decision,RuleEvaluator evaluator){
        if(!applies(rule)||!cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(decision.status())||!decision.gaps().isEmpty())return cn.bidcheck.compat.Java8Api.mapOf();
        SelectedEvaluation fresh=evaluateSelected(rule,evaluator);
        if(!fresh.decision().status().equals(decision.status())||!fresh.decision().evidences().equals(decision.evidences())||fresh.identityFacts().isEmpty())return cn.bidcheck.compat.Java8Api.mapOf();
        List<CertificateSourceComparison.Record> records=new ArrayList<>();
        for(Fact selected:fresh.identityFacts()){
            java.util.List<cn.bidcheck.verification.engine.CertificateSourceComparison.Record> matches=cn.bidcheck.compat.Java8Api.toList(evaluator.personnelCredentialRecords().stream().filter(r->("registration-proof:"+r.id()).equals(selected.id())&&cn.bidcheck.compat.Java8Api.toList(r.sources().stream().map(Source::id)).equals(selected.sourceIds())));
            if(matches.size()!=1)return cn.bidcheck.compat.Java8Api.mapOf();records.add(matches.get(0));
        }
        Map<String,Object> review=SourceLocalPersonnelCredentialClosure.reviewRecords(rule,records,evaluator);
        LOG.info("bidcheck.registration_source_review ruleId={} selectedFactIds={} complete={} reasonCode={} examinedSourceIds={}",rule.id(),cn.bidcheck.compat.Java8Api.toList(fresh.identityFacts().stream().map(Fact::id)),review.get("complete"),review.getOrDefault("reasonCode",""),review.getOrDefault("examinedSourceIds",cn.bidcheck.compat.Java8Api.listOf()));
        if(!Boolean.TRUE.equals(review.get("complete")))return cn.bidcheck.compat.Java8Api.mapOf();
        return linked("basis","REVERIFIED_REGISTRATION_RECORD_AND_SAME_NUMBER_COUNTEREVIDENCE","scope","REGISTRATION_UNIT_ONLY","sameCredentialReview",review,"selectedFactIds",cn.bidcheck.compat.Java8Api.toList(fresh.identityFacts().stream().map(Fact::id)),"sourceReadReceipt",ProgramSourceRead.receipt(evaluator.corpus,cn.bidcheck.compat.Java8Api.listOf()),"provesCredentialValidity",false,"provesQueryScreenshot",false,"unreadImageCoverageProven",false);
    }
    private static Decision evaluate(Rule rule,RuleEvaluator evaluator,List<Fact> selected){
        List<String> subjects=evaluator.resolvedSubjects(rule);if(subjects.isEmpty())return evaluator.unknown(rule,"SUBJECT_RELATION_UNPROVEN","须先确认本次岗位人员，才能检查其证书注册单位",cn.bidcheck.compat.Java8Api.listOf());
        List<Decision> decisions=new ArrayList<>();for(String person:subjects)decisions.add(person(rule,person,evaluator,selected));
        return decisions.size()==1?decisions.get(0):RuleEvaluator.combine(rule.id(),rule.description(),"ALL",decisions);
    }
    private static Decision person(Rule rule,String person,RuleEvaluator evaluator,List<Fact> selected){
        List<Fact> links=new ArrayList<>();Set<String> employers=new LinkedHashSet<>();
        for(Fact fact:evaluator.facts){if(!"CURRENT_PROJECT".equals(fact.scope())||new ProofVerifier().issue(fact,evaluator.corpus.byId)!=null||DomainBindings.issue(fact,evaluator.corpus.byId,cn.bidcheck.compat.Java8Api.listOf())!=null)continue;
            if(fact.sourceIds().stream().map(evaluator.corpus.byId::get).filter(Objects::nonNull).anyMatch(s->s.index()||s.historical()||s.scopeContext().blocked()))continue;
            if(fact.kind().equals("RELATION")&&fact.subject().equals(person)&&DomainBindings.employment(fact.field())&&!cn.bidcheck.compat.Java8Api.isBlank(str(fact.attributes(),"object"))){links.add(fact);employers.add(str(fact.attributes(),"object"));}
            if(fact.kind().equals("PERSON_ROLE")&&fact.value().equals(person)&&DomainBindings.currentRoleSource(fact,evaluator.corpus.byId)&&!cn.bidcheck.compat.Java8Api.isBlank(str(fact.attributes(),"employer"))){links.add(fact);employers.add(str(fact.attributes(),"employer"));}
        }
        List<CertificateSourceComparison.Record> records=cn.bidcheck.compat.Java8Api.toList(CertificateSourceComparison.records(evaluator.corpus).stream().filter(r->r.side().equals("CERTIFICATE")&&r.owner().equals(person)&&r.issues().isEmpty()));
        // Reuse only a source-validated current person's profile. In particular, an
        // explicit employing-unit label can prove employment; a registration label cannot.
        List<Fact> profileFacts=new ArrayList<>(evaluator.facts);records.stream().map(ConfirmedSubjectBindings::recordIdentity).filter(Objects::nonNull).forEach(profileFacts::add);
        java.util.List<cn.bidcheck.verification.engine.PersonEvidenceProfiles.Profile> profiles=PersonEvidenceProfiles.forPerson(person,profileFacts,evaluator.corpus,RuleEvaluator.maps(evaluator.context.get("_validatedLayoutCells")));
        for(cn.bidcheck.verification.engine.PersonEvidenceProfiles.Profile profile:profiles){employers.addAll(profile.employers());links.addAll(profile.employerWitnesses());}
        boolean memberRegistration=false;String requiredMemberRole=registrationMemberRole(rule);
        if(employers.isEmpty()&&!cn.bidcheck.compat.Java8Api.isBlank(requiredMemberRole)){
            Rule memberRule=new Rule(rule.id()+"-member","原条款对应成员单位","RELATION",requiredMemberRole,"名称","","","",cn.bidcheck.compat.Java8Api.mapOf(),rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
            List<Fact> memberFacts=EnterpriseSubjectBindings.extract(cn.bidcheck.compat.Java8Api.listOf(memberRule),evaluator.corpus.all);
            for(Fact fact:memberFacts)if(fact.subject().equals(requiredMemberRole)&&new ProofVerifier().issue(fact,evaluator.corpus.byId)==null&&DomainBindings.issue(fact,evaluator.corpus.byId,cn.bidcheck.compat.Java8Api.listOf())==null){employers.add(str(fact.attributes(),"object"));links.add(fact);}
            memberRegistration=!employers.isEmpty();
        }
        if(Boolean.TRUE.equals(rule.parameters().get("registrationConditional"))){
            String requirement=str(rule.parameters(),"credentialRequirement").split("须提交有效证明文件",2)[0];
            boolean safetyOnly=requirement.contains("安全生产考核合格证")&&!requirement.matches("(?s).*注册(?:建造师|建筑师|监理工程师|造价工程师).*" );
            if(safetyOnly){
                List<CertificateSourceComparison.Record> applicable=cn.bidcheck.compat.Java8Api.toList(records.stream().filter(r->r.category().contains("安全生产考核")));
                if(applicable.size()==1){cn.bidcheck.verification.engine.CertificateSourceComparison.Record certificate=applicable.get(0);
                    return new Decision(rule.id(),rule.description(),"NOT_APPLICABLE","已定位本人员的安全生产考核证；原条款的本单位注册条件仅适用于注册类资质，当前考核证不作注册证处理",
                        cn.bidcheck.compat.Java8Api.toList(certificate.fields().values().stream().map(CertificateSourceComparison.Field::evidence).distinct()),cn.bidcheck.compat.Java8Api.listOf(),
                        linked("executor","REGISTRATION_EMPLOYER_RELATION","applicability","NON_REGISTRATION_CREDENTIAL","credentialRequirement",requirement,"record",certificate.audit(),"doesNotProveCredentialQualification",true));
                }
            }
            records=cn.bidcheck.compat.Java8Api.toList(records.stream().filter(r->r.category().contains("注册")));
        }
        Map<String,Object> proof=linked("executor","REGISTRATION_EMPLOYER_RELATION","subject",person,"declarationCannotProveRegistration",true,"expectedEmployers",employers,"records",cn.bidcheck.compat.Java8Api.toList(records.stream().map(CertificateSourceComparison.Record::audit)),"requirementQuote",rule.sourceQuote(),"expectedUnitBasis",memberRegistration?"CURRENT_ROLE_AND_EXPLICIT_CONSORTIUM_WORK_ALLOCATION":"INDEPENDENT_EMPLOYMENT_SOURCE","requiredMemberRole",requiredMemberRole,"doesNotProveEmployment",memberRegistration,"personProfiles",cn.bidcheck.compat.Java8Api.toList(profiles.stream().map(PersonEvidenceProfiles.Profile::audit)));
        if(employers.size()!=1)return evaluator.unknown(rule,"REGISTRATION_EMPLOYER_BINDING_REQUIRED","本次人员所属单位尚未由独立任职原文确定，不能用证书注册单位反向证明其所属单位",links,proof);
        if(records.size()!=1||cn.bidcheck.compat.Java8Api.isBlank(records.get(0).value("EMPLOYER")))return evaluator.unknown(rule,"REGISTRATION_CERTIFICATE_REQUIRED","人员所属单位已确定，仍需唯一适用注册证书及其中的注册或聘用单位字段",links,proof);
        cn.bidcheck.verification.engine.CertificateSourceComparison.Record record=records.get(0);
        {
            cn.bidcheck.verification.engine.CertificateSourceComparison.Field number=record.fields().get("CERTIFICATE_NUMBER");
            if(number==null)number=record.fields().get("REGISTRATION_NUMBER");
            if(number==null)return evaluator.unknown(rule,"REGISTRATION_CERTIFICATE_REQUIRED","已确定应注册的成员单位，但注册证书尚缺同类型的明确证号",links,proof);
            Fact certificate=new Fact("registration-proof:"+record.id(),"CERTIFICATE",person,"证书",number.value(),number.value(),"","","UNRESOLVED",cn.bidcheck.compat.Java8Api.toList(record.sources().stream().map(Source::id)),cn.bidcheck.compat.Java8Api.mapOf("category", record.category(), "number", number.value(), "numberField", number.label()));
            // Typed original records already carry independently anchored raw fields; a normalized
            // category/parenthesis is not required to occur verbatim in the PDF a second time.
            boolean typed=record.id().startsWith("PR")&&record.fields().containsKey("NAME")&&record.fields().containsKey("EMPLOYER")
                &&record.fields().values().stream().allMatch(field->{Source source=evaluator.corpus.byId.get(field.sourceId());return source!=null&&source.evidence().documentId().equals(field.evidence().documentId())&&source.evidence().page()==field.evidence().page();});
            String sourceIssue=typed?null:new ProofVerifier().issue(certificate,evaluator.corpus.byId),bindingIssue=typed?null:DomainBindings.issue(certificate,evaluator.corpus.byId,cn.bidcheck.compat.Java8Api.listOf());
            if(sourceIssue!=null||bindingIssue!=null){proof.put("certificateSourceValidation",linked("sourceIssue",sourceIssue,"bindingIssue",bindingIssue));return evaluator.unknown(rule,"REGISTRATION_CERTIFICATE_IDENTITY_REQUIRED","已定位注册记录，但证书姓名、类别和证号尚未形成可核源的同一记录",links,proof);}
            Rule identityRule=new Rule(rule.id(),rule.description(),"CERTIFICATE",rule.subject(),"证书","","","",cn.bidcheck.compat.Java8Api.mapOf("category", record.category()),rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
            cn.bidcheck.verification.engine.SubjectIdentity.Problem issue=evaluator.identities.issue(identityRule,cn.bidcheck.compat.Java8Api.listOf(certificate));if(issue!=null)return evaluator.unknown(rule,issue.code(),issue.reason(),cn.bidcheck.compat.Java8Api.listOf(certificate),proof);
            links.add(certificate);
            selected.add(certificate);proof.put("selectedIdentityFactIds",cn.bidcheck.compat.Java8Api.listOf(certificate.id()));proof.put("identityBindings",evaluator.identities.bindings(cn.bidcheck.compat.Java8Api.listOf(certificate)));
        }
        String expected=employers.iterator().next(),actual=record.value("EMPLOYER");proof.putAll(linked("expectedEmployer",expected,"registeredEmployer",actual,"comparisonField",record.fields().get("EMPLOYER").audit(),"proofScope","REGISTRATION_UNIT_ONLY","doesNotProveValidityOrOnlineStatus",true));
        List<Evidence> evidence=new ArrayList<>();links.stream().flatMap(f->f.sourceIds().stream()).distinct().map(evaluator.corpus.byId::get).filter(Objects::nonNull).map(Source::evidence).forEach(evidence::add);record.fields().values().stream().map(CertificateSourceComparison.Field::evidence).forEach(evidence::add);
        boolean equal=clean(expected).equals(clean(actual));return new Decision(rule.id(),rule.description(),equal?"PASS":"FAIL",equal?(memberRegistration?"原条款岗位已对应本次联合体成员，证书注册单位与该成员一致；仅完成注册单位核验":"原文独立证明人员所属单位，证书中注册或聘用单位与其一致"):"本条件要求的单位与已定位证书中的注册或聘用单位不同",cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),cn.bidcheck.compat.Java8Api.listOf(),proof);
    }
    private static String registrationMemberRole(Rule rule){
        String requested=str(rule.parameters(),"registrationEmployerRole"),requirement=clean(str(rule.parameters(),"credentialRequirement")),role=PersonnelRoles.canonical(rule.subject());
        if(requested.equals("设计单位")&&role.equals("设计负责人")&&requirement.matches("^(?:设计部分负责人|设计负责人)资格要求[:：].*"))return requested;
        if(requested.equals("施工单位")&&role.equals("施工负责人")&&requirement.matches("^(?:施工部分负责人|施工负责人)资格要求[:：].*"))return requested;
        return "";
    }
}
