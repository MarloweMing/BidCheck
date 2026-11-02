package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Merges source-proven versions into a view; original facts and their field provenance stay immutable. */
final class PersonEvidenceProfiles {
    static final String VERSION="person-evidence-profile-2-explicit-employment-label";
    record Profile(String id,String person,List<Fact> appointments,List<Fact> facts,List<String> employers,List<Fact> employerWitnesses,List<Map<String,Object>> edges,boolean conflict){
        Map<String,Object> audit(){return linked("version",VERSION,"entityId",id,"person",person,"appointmentFactIds",appointments.stream().map(Fact::id).toList(),"factIds",facts.stream().map(Fact::id).toList(),"sourceIds",facts.stream().flatMap(f->f.sourceIds().stream()).distinct().toList(),"employers",employers,"employerWitnessIds",employerWitnesses.stream().map(Fact::id).toList(),"roleNames",appointments.stream().map(Fact::subject).distinct().toList(),"certificateFactIds",facts.stream().filter(f->f.kind().equals("CERTIFICATE")).map(Fact::id).toList(),"socialFactIds",facts.stream().filter(f->f.kind().equals("MONTHS")).map(Fact::id).toList(),"identityEdges",edges,"conflict",conflict,"originalFactsRewritten",false,"certificateEmployerIsNotEmploymentProof",true);}
    }
    private PersonEvidenceProfiles(){}
    static List<Profile> forPerson(String person,List<Fact> facts,EvidenceCorpus corpus,List<Map<String,Object>> layout){
        Set<String> names=new HashSet<>(List.of(person));for(Fact fact:facts)if(InferenceClaims.humanConfirmed(fact)&&fact.kind().equals("RELATION")&&fact.subject().equals(person)&&Set.of("同一人","SAME_PERSON","身份对应").contains(fact.field()))names.add(str(fact.attributes(),"object"));
        return build(facts.stream().filter(f->names.contains(SubjectIdentity.person(f))).toList(),corpus,layout).stream().filter(p->p.person().equals(person)).toList();
    }
    static List<Profile> build(List<Fact> supplied,EvidenceCorpus corpus,List<Map<String,Object>> layout){
        List<Fact> facts=supplied.stream().filter(f->Set.of("PERSON_ROLE","CERTIFICATE","MONTHS","RELATION").contains(f.kind())).filter(f->valid(f,corpus,layout)).toList();
        Map<String,List<Fact>> byPerson=new HashMap<>();for(Fact fact:facts)byPerson.computeIfAbsent(SubjectIdentity.person(fact),key->new ArrayList<>()).add(fact);
        List<Fact> roles=facts.stream().filter(f->f.kind().equals("PERSON_ROLE")&&f.scope().equals("CURRENT_PROJECT")&&DomainBindings.currentRoleSource(f,corpus.byId)&&!SubjectIdentity.person(f).isBlank()).toList();
        SubjectIdentity identities=new SubjectIdentity(facts,corpus,layout);List<List<Fact>> clusters=new ArrayList<>();
        for(Fact role:roles){List<List<Fact>> matches=clusters.stream().filter(group->group.stream().anyMatch(other->identities.samePerson(role,other))).toList();
            if(matches.isEmpty())clusters.add(new ArrayList<>(List.of(role)));
            else {List<Fact> first=matches.get(0);first.add(role);for(int i=1;i<matches.size();i++){first.addAll(matches.get(i));clusters.remove(matches.get(i));}}
        }
        List<Profile> out=new ArrayList<>();for(List<Fact> cluster:clusters){
            String person=SubjectIdentity.person(cluster.get(0));List<Fact> members=new ArrayList<>(),witnesses=new ArrayList<>();List<Map<String,Object>> edges=new ArrayList<>();Set<String> employers=new LinkedHashSet<>(),identifiers=new LinkedHashSet<>();
            Set<String> names=new LinkedHashSet<>(List.of(person));for(Fact fact:facts)if(InferenceClaims.humanConfirmed(fact)&&fact.kind().equals("RELATION")&&fact.subject().equals(person)&&Set.of("同一人","SAME_PERSON","身份对应").contains(fact.field()))names.add(str(fact.attributes(),"object"));
            List<Fact> candidates=names.stream().flatMap(name->byPerson.getOrDefault(name,List.of()).stream()).distinct().toList();
            for(Fact f:candidates){Fact linked=cluster.stream().filter(role->role.id().equals(f.id())||identities.samePerson(role,f)).findFirst().orElse(null);if(linked==null)continue;members.add(f);
                String key=identities.countIdentity(f);if(!key.isBlank())identifiers.add(key);
                if(!linked.id().equals(f.id()))edges.add(linked("appointmentFactId",linked.id(),"dependentFactId",f.id(),"proof",identities.bindingBetween(linked,f)));
                String employer="";if(f.kind().equals("PERSON_ROLE"))employer=str(f.attributes(),"employer");else if(f.kind().equals("RELATION")&&DomainBindings.employment(f.field()))employer=str(f.attributes(),"object");
                else if(f.kind().equals("CERTIFICATE")){Map<String,Object> proof=explicitEmployment(f,corpus);employer=str(proof,"employer");if(!proof.isEmpty())edges.add(linked("appointmentFactId",linked.id(),"dependentFactId",f.id(),"employmentProof",proof));}
                if(company(employer)){employers.add(employer);witnesses.add(f);}
            }
            String key=identifiers.size()==1?identifiers.iterator().next():cluster.stream().flatMap(f->f.sourceIds().stream()).sorted().reduce((a,b)->a+"|"+b).orElse("");
            out.add(new Profile("person-profile:"+identity(person+":"+key).substring(0,32),person,List.copyOf(cluster),List.copyOf(members),List.copyOf(employers),List.copyOf(witnesses),List.copyOf(edges),identifiers.size()>1||employers.size()>1));
        }return List.copyOf(out);
    }
    static List<Map<String,Object>> audit(List<Fact> facts,EvidenceCorpus corpus,List<Map<String,Object>> layout){return build(facts,corpus,layout).stream().map(Profile::audit).toList();}
    /** A registration/employer attribute alone is not employment. The same re-read credential
     * must explicitly label it as an employing entity, and the caller has already resolved
     * this holder to the current appointment. Do not manufacture a one-line RELATION quote. */
    static Map<String,Object> explicitEmployment(Fact certificate,EvidenceCorpus corpus){
        // A native credential is intentionally UNRESOLVED until joined to an appointment.
        // The profile caller has already proved that join; do not demand that a reusable
        // source-local observation be rewritten into a project-global fact first.
        if(!certificate.kind().equals("CERTIFICATE")||!Set.of("CURRENT_PROJECT","UNRESOLVED").contains(certificate.scope()))return Map.of();
        var record=CertificateRecordBindings.matching(certificate,corpus.byId);if(record==null)return Map.of();
        var field=record.fields().get("EMPLOYER");if(field==null||!Set.of("聘用企业","聘用单位","受聘单位").contains(field.label())||!company(field.value())||!record.owner().equals(certificate.subject()))return Map.of();
        if(record.sources().stream().anyMatch(s->s.historical()||s.index()||s.scopeContext().blocked()))return Map.of();
        return linked("basis","EXPLICIT_EMPLOYING_ENTITY_ON_IDENTITY_BOUND_CREDENTIAL","employer",field.value(),"holder",record.owner(),"recordId",record.id(),"sourceIds",record.sources().stream().map(Source::id).toList(),"field",field.audit(),"registrationUnitAloneAccepted",false);
    }
    private static boolean valid(Fact fact,EvidenceCorpus corpus,List<Map<String,Object>> layout){return !fact.sourceIds().isEmpty()&&fact.sourceIds().stream().allMatch(id->{Source s=corpus.byId.get(id);return s!=null&&!s.index()&&!s.historical()&&!s.scopeContext().blocked();})&&(InferenceClaims.humanConfirmed(fact)||new ProofVerifier().issue(fact,corpus.byId)==null&&DomainBindings.issue(fact,corpus.byId,layout)==null);}
    private static boolean company(String value){return !DomainBindings.role(value)&&value.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$");}
}
