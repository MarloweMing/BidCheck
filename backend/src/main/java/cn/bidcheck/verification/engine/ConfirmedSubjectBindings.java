package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A human-confirmed identity edge authorizes only its cited appointment/proof pair.
 * The original holder and certificate fields remain unchanged and source-validated. */
final class ConfirmedSubjectBindings {
    private ConfirmedSubjectBindings() {}
    static boolean matches(String person,Fact proof,Collection<Fact> facts,EvidenceCorpus corpus){return !edge(person,proof,facts,corpus).isEmpty();}
    static Map<String,Object> edge(String person,Fact proof,Collection<Fact> facts,EvidenceCorpus corpus){
        return edge(person,proof,facts,corpus,"");
    }
    static boolean matchesPair(Fact role,Fact proof,Collection<Fact> facts,EvidenceCorpus corpus){return !edge(SubjectIdentity.person(role),proof,facts,corpus,role.id()).isEmpty();}
    private static Map<String,Object> edge(String person,Fact proof,Collection<Fact> facts,EvidenceCorpus corpus,String roleId){
        String owner=SubjectIdentity.person(proof);if(person.isBlank()||owner.isBlank()||DomainBindings.role(person)||DomainBindings.role(owner)||!Set.of("CERTIFICATE","DATE_RANGE","MONTHS").contains(proof.kind()))return Map.of();
        if(proof.sourceIds().isEmpty()||proof.sourceIds().stream().anyMatch(id->{Source s=corpus.byId.get(id);return s==null||s.index()||s.historical()||s.scopeContext().blocked();}))return Map.of();
        for(Fact relation:facts){
            if(!InferenceClaims.humanConfirmed(relation)||!relation.kind().equals("RELATION")||!Set.of("同一人","SAME_PERSON","身份对应").contains(relation.field())||!relation.subject().equals(person)||!str(relation.attributes(),"object").equals(owner)||!relation.sourceIds().containsAll(proof.sourceIds()))continue;
            if(!str(relation.attributes(),"number").isBlank()&&!str(relation.attributes(),"number").equals(str(proof.attributes(),"number")))continue;
            for(Fact role:facts){
                if(!roleId.isBlank()&&!role.id().equals(roleId)||!role.kind().equals("PERSON_ROLE")||!role.value().equals(person)||!role.scope().equals("CURRENT_PROJECT")||!DomainBindings.currentRoleSource(role,corpus.byId)||!relation.sourceIds().containsAll(role.sourceIds()))continue;
                if(role.sourceIds().isEmpty()||role.sourceIds().stream().anyMatch(id->{Source s=corpus.byId.get(id);return s==null||s.index()||s.historical()||s.scopeContext().blocked();}))continue;
                Set<String> documents=new HashSet<>();role.sourceIds().forEach(id->documents.add(corpus.byId.get(id).evidence().documentId()));proof.sourceIds().forEach(id->documents.add(corpus.byId.get(id).evidence().documentId()));if(documents.size()!=1)continue;
                // Approval cannot silently erase a contradictory explicit identity number.
                Set<String> identifiers=new HashSet<>();identifiers.addAll(identifiers(role,corpus));identifiers.addAll(identifiers(proof,corpus));if(identifiers.size()>1)continue;
                return linked("method","HUMAN_CONFIRMED_CITED_IDENTITY_PAIR","confirmation",relation.attributes().get("humanConfirmation"),"confirmationFactId",relation.id(),"appointmentFactId",role.id(),"proofFactId",proof.id(),"person",person,"originalHolder",owner,"appointmentSourceIds",role.sourceIds(),"proofSourceIds",proof.sourceIds(),"fieldsRewritten",false,"doesNotProveCredentialValidity",true);
            }
        }return Map.of();
    }
    static Set<String> identifiers(Fact fact,EvidenceCorpus corpus){Map<String,Object> entity=cn.bidcheck.verification.facts.EntityIdentity.resolvePerson(SubjectIdentity.person(fact),LayoutBinding.sources(fact,corpus.byId));Set<String> result=new HashSet<>();for(var claim:RuleEvaluator.maps(entity.get("identityClaims")))if("CN_ID".equals(str(claim,"field"))&&!str(claim,"valueFingerprint").isBlank())result.add(str(claim,"valueFingerprint"));
        if(fact.kind().equals("CERTIFICATE")){Fact numbered=new Fact(fact.id(),fact.kind(),fact.subject(),fact.field(),fact.rawValue(),str(fact.attributes(),"number"),fact.unit(),fact.basis(),fact.scope(),fact.sourceIds(),fact.attributes());var record=CertificateRecordBindings.matching(numbered,corpus.byId);if(record!=null&&record.value("PERSON_ID").matches("[0-9]{17}[0-9Xx]"))result.add(identity(record.value("PERSON_ID").toUpperCase(Locale.ROOT)));}return result;}
    static Fact recordIdentity(CertificateSourceComparison.Record record){var number=record.fields().get("CERTIFICATE_NUMBER");if(number==null)number=record.fields().get("REGISTRATION_NUMBER");if(number==null)return null;return new Fact("personnel-record:"+record.id(),"CERTIFICATE",record.owner(),"证书",number.raw(),number.value(),"","","UNRESOLVED",record.sources().stream().map(Source::id).toList(),Map.of("category",record.category(),"number",number.value(),"numberField",number.label()));}
    static boolean recordMatches(String person,CertificateSourceComparison.Record record,RuleEvaluator evaluator){if(record.owner().equals(person))return true;Fact identity=recordIdentity(record);return identity!=null&&matches(person,identity,evaluator.facts,evaluator.corpus);}
}
