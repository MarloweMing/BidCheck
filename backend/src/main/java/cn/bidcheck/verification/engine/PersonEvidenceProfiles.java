package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Merges source-proven versions into a view; original facts and their field provenance stay immutable. */
final class PersonEvidenceProfiles {
    static final String VERSION="person-evidence-profile-2-explicit-employment-label";
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Profile{
        private final String id;
        private final String person;
        private final List<Fact> appointments;
        private final List<Fact> facts;
        private final List<String> employers;
        private final List<Fact> employerWitnesses;
        private final List<Map<String,Object>> edges;
        private final boolean conflict;
        @com.fasterxml.jackson.annotation.JsonCreator
        Profile(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("person") String person, @com.fasterxml.jackson.annotation.JsonProperty("appointments") List<Fact> appointments, @com.fasterxml.jackson.annotation.JsonProperty("facts") List<Fact> facts, @com.fasterxml.jackson.annotation.JsonProperty("employers") List<String> employers, @com.fasterxml.jackson.annotation.JsonProperty("employerWitnesses") List<Fact> employerWitnesses, @com.fasterxml.jackson.annotation.JsonProperty("edges") List<Map<String,Object>> edges, @com.fasterxml.jackson.annotation.JsonProperty("conflict") boolean conflict) { this.id = id; this.person = person; this.appointments = appointments; this.facts = facts; this.employers = employers; this.employerWitnesses = employerWitnesses; this.edges = edges; this.conflict = conflict; }
        public String id() { return id; }
        public String person() { return person; }
        public List<Fact> appointments() { return appointments; }
        public List<Fact> facts() { return facts; }
        public List<String> employers() { return employers; }
        public List<Fact> employerWitnesses() { return employerWitnesses; }
        public List<Map<String,Object>> edges() { return edges; }
        public boolean conflict() { return conflict; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Profile that = (Profile) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.person, that.person) && java.util.Objects.equals(this.appointments, that.appointments) && java.util.Objects.equals(this.facts, that.facts) && java.util.Objects.equals(this.employers, that.employers) && java.util.Objects.equals(this.employerWitnesses, that.employerWitnesses) && java.util.Objects.equals(this.edges, that.edges) && java.util.Objects.equals(this.conflict, that.conflict); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.person); hash = 31 * hash + java.util.Objects.hashCode(this.appointments); hash = 31 * hash + java.util.Objects.hashCode(this.facts); hash = 31 * hash + java.util.Objects.hashCode(this.employers); hash = 31 * hash + java.util.Objects.hashCode(this.employerWitnesses); hash = 31 * hash + java.util.Objects.hashCode(this.edges); hash = 31 * hash + java.util.Objects.hashCode(this.conflict); return hash; }
        @Override public String toString() { return "Profile[" + "id=" + id + ", person=" + person + ", appointments=" + appointments + ", facts=" + facts + ", employers=" + employers + ", employerWitnesses=" + employerWitnesses + ", edges=" + edges + ", conflict=" + conflict + "]"; }

        Map<String,Object> audit(){return linked("version",VERSION,"entityId",id,"person",person,"appointmentFactIds",cn.bidcheck.compat.Java8Api.toList(appointments.stream().map(Fact::id)),"factIds",cn.bidcheck.compat.Java8Api.toList(facts.stream().map(Fact::id)),"sourceIds",cn.bidcheck.compat.Java8Api.toList(facts.stream().flatMap(f->f.sourceIds().stream()).distinct()),"employers",employers,"employerWitnessIds",cn.bidcheck.compat.Java8Api.toList(employerWitnesses.stream().map(Fact::id)),"roleNames",cn.bidcheck.compat.Java8Api.toList(appointments.stream().map(Fact::subject).distinct()),"certificateFactIds",cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->f.kind().equals("CERTIFICATE")).map(Fact::id)),"socialFactIds",cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->f.kind().equals("MONTHS")).map(Fact::id)),"identityEdges",edges,"conflict",conflict,"originalFactsRewritten",false,"certificateEmployerIsNotEmploymentProof",true);}
    }
    private PersonEvidenceProfiles(){}
    static List<Profile> forPerson(String person,List<Fact> facts,EvidenceCorpus corpus,List<Map<String,Object>> layout){
        Set<String> names=new HashSet<>(cn.bidcheck.compat.Java8Api.listOf(person));for(Fact fact:facts)if(InferenceClaims.humanConfirmed(fact)&&fact.kind().equals("RELATION")&&fact.subject().equals(person)&&cn.bidcheck.compat.Java8Api.setOf("同一人", "SAME_PERSON", "身份对应").contains(fact.field()))names.add(str(fact.attributes(),"object"));
        return cn.bidcheck.compat.Java8Api.toList(build(cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->names.contains(SubjectIdentity.person(f)))),corpus,layout).stream().filter(p->p.person().equals(person)));
    }
    static List<Profile> build(List<Fact> supplied,EvidenceCorpus corpus,List<Map<String,Object>> layout){
        List<Fact> facts=cn.bidcheck.compat.Java8Api.toList(supplied.stream().filter(f->cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "CERTIFICATE", "MONTHS", "RELATION").contains(f.kind())).filter(f->valid(f,corpus,layout)));
        Map<String,List<Fact>> byPerson=new HashMap<>();for(Fact fact:facts)byPerson.computeIfAbsent(SubjectIdentity.person(fact),key->new ArrayList<>()).add(fact);
        List<Fact> roles=cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->f.kind().equals("PERSON_ROLE")&&f.scope().equals("CURRENT_PROJECT")&&DomainBindings.currentRoleSource(f,corpus.byId)&&!cn.bidcheck.compat.Java8Api.isBlank(SubjectIdentity.person(f))));
        SubjectIdentity identities=new SubjectIdentity(facts,corpus,layout);List<List<Fact>> clusters=new ArrayList<>();
        for(Fact role:roles){List<List<Fact>> matches=cn.bidcheck.compat.Java8Api.toList(clusters.stream().filter(group->group.stream().anyMatch(other->identities.samePerson(role,other))));
            if(matches.isEmpty())clusters.add(new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(role)));
            else {List<Fact> first=matches.get(0);first.add(role);for(int i=1;i<matches.size();i++){first.addAll(matches.get(i));clusters.remove(matches.get(i));}}
        }
        List<Profile> out=new ArrayList<>();for(List<Fact> cluster:clusters){
            String person=SubjectIdentity.person(cluster.get(0));List<Fact> members=new ArrayList<>(),witnesses=new ArrayList<>();List<Map<String,Object>> edges=new ArrayList<>();Set<String> employers=new LinkedHashSet<>(),identifiers=new LinkedHashSet<>();
            Set<String> names=new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.listOf(person));for(Fact fact:facts)if(InferenceClaims.humanConfirmed(fact)&&fact.kind().equals("RELATION")&&fact.subject().equals(person)&&cn.bidcheck.compat.Java8Api.setOf("同一人", "SAME_PERSON", "身份对应").contains(fact.field()))names.add(str(fact.attributes(),"object"));
            List<Fact> candidates=cn.bidcheck.compat.Java8Api.toList(names.stream().flatMap(name->byPerson.getOrDefault(name,cn.bidcheck.compat.Java8Api.listOf()).stream()).distinct());
            for(Fact f:candidates){Fact linked=cluster.stream().filter(role->role.id().equals(f.id())||identities.samePerson(role,f)).findFirst().orElse(null);if(linked==null)continue;members.add(f);
                String key=identities.countIdentity(f);if(!cn.bidcheck.compat.Java8Api.isBlank(key))identifiers.add(key);
                if(!linked.id().equals(f.id()))edges.add(linked("appointmentFactId",linked.id(),"dependentFactId",f.id(),"proof",identities.bindingBetween(linked,f)));
                String employer="";if(f.kind().equals("PERSON_ROLE"))employer=str(f.attributes(),"employer");else if(f.kind().equals("RELATION")&&DomainBindings.employment(f.field()))employer=str(f.attributes(),"object");
                else if(f.kind().equals("CERTIFICATE")){Map<String,Object> proof=explicitEmployment(f,corpus);employer=str(proof,"employer");if(!proof.isEmpty())edges.add(linked("appointmentFactId",linked.id(),"dependentFactId",f.id(),"employmentProof",proof));}
                if(company(employer)){employers.add(employer);witnesses.add(f);}
            }
            String key=identifiers.size()==1?identifiers.iterator().next():cluster.stream().flatMap(f->f.sourceIds().stream()).sorted().reduce((a,b)->a+"|"+b).orElse("");
            out.add(new Profile("person-profile:"+identity(person+":"+key).substring(0,32),person,cn.bidcheck.compat.Java8Api.listCopyOf(cluster),cn.bidcheck.compat.Java8Api.listCopyOf(members),cn.bidcheck.compat.Java8Api.listCopyOf(employers),cn.bidcheck.compat.Java8Api.listCopyOf(witnesses),cn.bidcheck.compat.Java8Api.listCopyOf(edges),identifiers.size()>1||employers.size()>1));
        }return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }
    static List<Map<String,Object>> audit(List<Fact> facts,EvidenceCorpus corpus,List<Map<String,Object>> layout){return cn.bidcheck.compat.Java8Api.toList(build(facts,corpus,layout).stream().map(Profile::audit));}
    /** A registration/employer attribute alone is not employment. The same re-read credential
     * must explicitly label it as an employing entity, and the caller has already resolved
     * this holder to the current appointment. Do not manufacture a one-line RELATION quote. */
    static Map<String,Object> explicitEmployment(Fact certificate,EvidenceCorpus corpus){
        // A native credential is intentionally UNRESOLVED until joined to an appointment.
        // The profile caller has already proved that join; do not demand that a reusable
        // source-local observation be rewritten into a project-global fact first.
        if(!certificate.kind().equals("CERTIFICATE")||!cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "UNRESOLVED").contains(certificate.scope()))return cn.bidcheck.compat.Java8Api.mapOf();
        cn.bidcheck.verification.engine.CertificateSourceComparison.Record record=CertificateRecordBindings.matching(certificate,corpus.byId);if(record==null)return cn.bidcheck.compat.Java8Api.mapOf();
        cn.bidcheck.verification.engine.CertificateSourceComparison.Field field=record.fields().get("EMPLOYER");if(field==null||!cn.bidcheck.compat.Java8Api.setOf("聘用企业", "聘用单位", "受聘单位").contains(field.label())||!company(field.value())||!record.owner().equals(certificate.subject()))return cn.bidcheck.compat.Java8Api.mapOf();
        if(record.sources().stream().anyMatch(s->s.historical()||s.index()||s.scopeContext().blocked()))return cn.bidcheck.compat.Java8Api.mapOf();
        return linked("basis","EXPLICIT_EMPLOYING_ENTITY_ON_IDENTITY_BOUND_CREDENTIAL","employer",field.value(),"holder",record.owner(),"recordId",record.id(),"sourceIds",cn.bidcheck.compat.Java8Api.toList(record.sources().stream().map(Source::id)),"field",field.audit(),"registrationUnitAloneAccepted",false);
    }
    private static boolean valid(Fact fact,EvidenceCorpus corpus,List<Map<String,Object>> layout){return !fact.sourceIds().isEmpty()&&fact.sourceIds().stream().allMatch(id->{Source s=corpus.byId.get(id);return s!=null&&!s.index()&&!s.historical()&&!s.scopeContext().blocked();})&&(InferenceClaims.humanConfirmed(fact)||new ProofVerifier().issue(fact,corpus.byId)==null&&DomainBindings.issue(fact,corpus.byId,layout)==null);}
    private static boolean company(String value){return !DomainBindings.role(value)&&value.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$");}
}
