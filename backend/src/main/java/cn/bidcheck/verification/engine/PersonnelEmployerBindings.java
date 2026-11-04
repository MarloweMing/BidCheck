package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Resolves the deictic employer per appointed person, never from the social proof being checked. */
final class PersonnelEmployerBindings {
    private PersonnelEmployerBindings() {}
    static boolean deictic(String value){return cn.bidcheck.compat.Java8Api.setOf("本单位", "本公司", "所在单位", "所属单位").contains(clean(value));}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Binding{
        private final List<String> employers;
        private final List<Fact> witnesses;
        private final String basis;
        @com.fasterxml.jackson.annotation.JsonCreator
        Binding(@com.fasterxml.jackson.annotation.JsonProperty("employers") List<String> employers, @com.fasterxml.jackson.annotation.JsonProperty("witnesses") List<Fact> witnesses, @com.fasterxml.jackson.annotation.JsonProperty("basis") String basis) { this.employers = employers; this.witnesses = witnesses; this.basis = basis; }
        public List<String> employers() { return employers; }
        public List<Fact> witnesses() { return witnesses; }
        public String basis() { return basis; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Binding that = (Binding) other; return java.util.Objects.equals(this.employers, that.employers) && java.util.Objects.equals(this.witnesses, that.witnesses) && java.util.Objects.equals(this.basis, that.basis); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.employers); hash = 31 * hash + java.util.Objects.hashCode(this.witnesses); hash = 31 * hash + java.util.Objects.hashCode(this.basis); return hash; }
        @Override public String toString() { return "Binding[" + "employers=" + employers + ", witnesses=" + witnesses + ", basis=" + basis + "]"; }

        boolean resolved(){return employers.size()==1;}
        Map<String,Object> audit(){return linked("expectedEmployers",employers,"employerBindingBasis",basis,
            "employerBindingFactIds",cn.bidcheck.compat.Java8Api.toList(witnesses.stream().map(Fact::id)),"confirmationRequired",!resolved(),
            "employerBindingSourceIds",cn.bidcheck.compat.Java8Api.toList(witnesses.stream().flatMap(w->w.sourceIds().stream()).distinct()),
            "doesNotProveEmployment",basis.equals("CURRENT_ROLE_AND_CONSORTIUM_ALLOCATION"));}
    }
    static Binding resolve(Rule rule,List<Fact> facts,EvidenceCorpus corpus){
        return resolve(rule,facts,corpus,cn.bidcheck.compat.Java8Api.listOf());
    }
    static Binding resolve(Rule rule,List<Fact> facts,EvidenceCorpus corpus,List<Map<String,Object>> layout){
        return resolve(rule,facts,corpus,layout,false);
    }
    /** Only the executing social check may locate new employment evidence. Callers inspecting
     * supplied facts (including a social report locator) do not inherit that permission. */
    static Binding resolveForSocialCheck(Rule rule,List<Fact> facts,EvidenceCorpus corpus,List<Map<String,Object>> layout){
        String employer=str(rule.parameters(),"employer");
        boolean readAttachment="MONTHS".equals(rule.predicate())&&deictic(employer)&&clean(rule.sourceQuote()).contains(clean(employer));
        return resolve(rule,facts,corpus,layout,readAttachment);
    }
    private static Binding resolve(Rule rule,List<Fact> facts,EvidenceCorpus corpus,List<Map<String,Object>> layout,boolean readAttachment){
        String requested=str(rule.parameters(),"employer");
        if(!deictic(requested))return new Binding(DomainBindings.subjects(requested,facts,corpus),cn.bidcheck.compat.Java8Api.listOf(),"EXPLICIT_REQUIREMENT");
        String name=rule.subject();List<Fact> witnesses=new ArrayList<>();Set<String> companies=new LinkedHashSet<>();
        List<PersonEvidenceProfiles.Profile> profiles=PersonEvidenceProfiles.forPerson(name,facts,corpus,layout);
        for(cn.bidcheck.verification.engine.PersonEvidenceProfiles.Profile profile:profiles){companies.addAll(profile.employers());witnesses.addAll(profile.employerWitnesses());}
        if(!companies.isEmpty())return new Binding(cn.bidcheck.compat.Java8Api.listCopyOf(companies),cn.bidcheck.compat.Java8Api.listCopyOf(witnesses),"SOURCE_LINKED_PERSON_PROFILE");
        if(readAttachment&&profiles.size()==1&&!profiles.get(0).conflict()){
            Set<List<String>> inspected=new HashSet<>();
            for(Fact role:profiles.get(0).appointments()){
                if(!inspected.add(role.sourceIds()))continue;
                for(Fact certificate:PersonnelAttachmentIdentity.employmentWitnesses(rule,role,corpus)){
                    companies.add(str(certificate.attributes(),"employer"));witnesses.add(certificate);
                }
            }
            if(!companies.isEmpty())return new Binding(new ArrayList<>(companies),witnesses,"CURRENT_APPOINTMENT_ATTACHMENT_EMPLOYMENT");
        }
        for(Fact fact:facts){
            if(!current(fact,corpus))continue;
            if(fact.kind().equals("RELATION")&&name.equals(fact.subject())&&DomainBindings.employment(fact.field())){
                String company=str(fact.attributes(),"object");if(company(company)){companies.add(company);witnesses.add(fact);}
            }
            if(fact.kind().equals("PERSON_ROLE")&&name.equals(fact.value())){
                String company=str(fact.attributes(),"employer");if(company(company)){companies.add(company);witnesses.add(fact);}
            }
        }
        if(!companies.isEmpty())return new Binding(cn.bidcheck.compat.Java8Api.listCopyOf(companies),cn.bidcheck.compat.Java8Api.listCopyOf(witnesses),"INDEPENDENT_PERSON_EMPLOYER");
        Set<String> roles=new LinkedHashSet<>();String selector=str(rule.parameters(),"personnelRoleSelector");
        for(Fact fact:facts)if(fact.kind().equals("PERSON_ROLE")&&name.equals(fact.value())&&current(fact,corpus)
            &&(cn.bidcheck.compat.Java8Api.isBlank(selector)||DomainBindings.roleMatches(selector,fact.subject(),fact,corpus)))roles.add(fact.subject());
        Set<String> memberRoles=new LinkedHashSet<>();for(String role:roles){String member=memberRole(role);if(!cn.bidcheck.compat.Java8Api.isBlank(member))memberRoles.add(member);}
        // An unqualified project leader may belong to either member. Never guess from lead status.
        if(roles.stream().anyMatch(r->"项目负责人".equals(PersonnelRoles.canonical(r)))&&
            clean(rule.sourceQuote()).matches(".*项目负责人(?:应|须|必须)?由(?:联合体)?牵头(?:单位|人)(?:负责)?委派.*"))memberRoles.add("牵头单位");
        if(memberRoles.isEmpty())return new Binding(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),"PERSON_MEMBER_UNRESOLVED");
        for(String member:memberRoles){
            Rule query=new Rule(rule.id()+"-employer", "岗位对应联合体分工", "RELATION",member,"名称","","","",cn.bidcheck.compat.Java8Api.mapOf(),rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
            List<Fact> links=new ArrayList<>(facts);links.addAll(EnterpriseSubjectBindings.extract(cn.bidcheck.compat.Java8Api.listOf(query),corpus.all));
            for(Fact link:DomainBindings.roleLinks(member,links,corpus))if(current(link,corpus)){
                String company=DomainBindings.roleTarget(link,member);if(company(company)){companies.add(company);witnesses.add(link);}
            }
        }
        return new Binding(cn.bidcheck.compat.Java8Api.listCopyOf(companies),cn.bidcheck.compat.Java8Api.listCopyOf(witnesses),"CURRENT_ROLE_AND_CONSORTIUM_ALLOCATION");
    }
    static String memberRole(String role){switch (PersonnelRoles.canonical(role)) {case "设计负责人":case "设计企业主要负责人":return "设计单位";case "施工负责人":case "施工企业主要负责人":case "施工企业负责人":case "专职安全员":case "安全员":return "施工单位";default:return "";}}
    private static boolean company(String value){return !DomainBindings.role(value)&&value.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$");}
    private static boolean current(Fact f,EvidenceCorpus corpus){return f.scope().equals("CURRENT_PROJECT")&&!f.sourceIds().isEmpty()
        &&(InferenceClaims.humanConfirmed(f)||new ProofVerifier().issue(f,corpus.byId)==null&&DomainBindings.issue(f,corpus.byId,cn.bidcheck.compat.Java8Api.listOf())==null)
        &&f.sourceIds().stream().allMatch(id->{Source source=corpus.byId.get(id);return source!=null&&!source.index()&&!source.historical()&&!source.scopeContext().blocked();});}
}
