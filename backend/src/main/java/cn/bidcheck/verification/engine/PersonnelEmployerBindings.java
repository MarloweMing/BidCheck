package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Resolves the deictic employer per appointed person, never from the social proof being checked. */
final class PersonnelEmployerBindings {
    private PersonnelEmployerBindings() {}
    static boolean deictic(String value){return Set.of("本单位","本公司","所在单位","所属单位").contains(clean(value));}
    record Binding(List<String> employers,List<Fact> witnesses,String basis){
        boolean resolved(){return employers.size()==1;}
        Map<String,Object> audit(){return linked("expectedEmployers",employers,"employerBindingBasis",basis,
            "employerBindingFactIds",witnesses.stream().map(Fact::id).toList(),"confirmationRequired",!resolved(),
            "doesNotProveEmployment",basis.equals("CURRENT_ROLE_AND_CONSORTIUM_ALLOCATION"));}
    }
    static Binding resolve(Rule rule,List<Fact> facts,EvidenceCorpus corpus){
        return resolve(rule,facts,corpus,List.of());
    }
    static Binding resolve(Rule rule,List<Fact> facts,EvidenceCorpus corpus,List<Map<String,Object>> layout){
        String requested=str(rule.parameters(),"employer");
        if(!deictic(requested))return new Binding(DomainBindings.subjects(requested,facts,corpus),List.of(),"EXPLICIT_REQUIREMENT");
        String name=rule.subject();List<Fact> witnesses=new ArrayList<>();Set<String> companies=new LinkedHashSet<>();
        List<PersonEvidenceProfiles.Profile> profiles=PersonEvidenceProfiles.forPerson(name,facts,corpus,layout);
        for(var profile:profiles){companies.addAll(profile.employers());witnesses.addAll(profile.employerWitnesses());}
        if(!companies.isEmpty())return new Binding(List.copyOf(companies),List.copyOf(witnesses),"SOURCE_LINKED_PERSON_PROFILE");
        for(Fact fact:facts){
            if(!current(fact,corpus))continue;
            if(fact.kind().equals("RELATION")&&name.equals(fact.subject())&&DomainBindings.employment(fact.field())){
                String company=str(fact.attributes(),"object");if(company(company)){companies.add(company);witnesses.add(fact);}
            }
            if(fact.kind().equals("PERSON_ROLE")&&name.equals(fact.value())){
                String company=str(fact.attributes(),"employer");if(company(company)){companies.add(company);witnesses.add(fact);}
            }
        }
        if(!companies.isEmpty())return new Binding(List.copyOf(companies),List.copyOf(witnesses),"INDEPENDENT_PERSON_EMPLOYER");
        Set<String> roles=new LinkedHashSet<>();String selector=str(rule.parameters(),"personnelRoleSelector");
        for(Fact fact:facts)if(fact.kind().equals("PERSON_ROLE")&&name.equals(fact.value())&&current(fact,corpus)
            &&(selector.isBlank()||DomainBindings.roleMatches(selector,fact.subject(),fact,corpus)))roles.add(fact.subject());
        Set<String> memberRoles=new LinkedHashSet<>();for(String role:roles){String member=memberRole(role);if(!member.isBlank())memberRoles.add(member);}
        // An unqualified project leader may belong to either member. Never guess from lead status.
        if(memberRoles.isEmpty())return new Binding(List.of(),List.of(),"PERSON_MEMBER_UNRESOLVED");
        for(String member:memberRoles){
            Rule query=new Rule(rule.id()+"-employer", "岗位对应联合体分工", "RELATION",member,"名称","","","",Map.of(),rule.sourceQuote(),"",List.of());
            List<Fact> links=new ArrayList<>(facts);links.addAll(EnterpriseSubjectBindings.extract(List.of(query),corpus.all));
            for(Fact link:DomainBindings.roleLinks(member,links,corpus))if(current(link,corpus)){
                String company=DomainBindings.roleTarget(link,member);if(company(company)){companies.add(company);witnesses.add(link);}
            }
        }
        return new Binding(List.copyOf(companies),List.copyOf(witnesses),"CURRENT_ROLE_AND_CONSORTIUM_ALLOCATION");
    }
    static String memberRole(String role){return switch(PersonnelRoles.canonical(role)){
        case "设计负责人","设计企业主要负责人"->"设计单位";
        case "施工负责人","施工企业主要负责人","施工企业负责人","专职安全员","安全员"->"施工单位";
        default->"";};}
    private static boolean company(String value){return !DomainBindings.role(value)&&value.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$");}
    private static boolean current(Fact f,EvidenceCorpus corpus){return f.scope().equals("CURRENT_PROJECT")&&!f.sourceIds().isEmpty()
        &&(InferenceClaims.humanConfirmed(f)||new ProofVerifier().issue(f,corpus.byId)==null&&DomainBindings.issue(f,corpus.byId,List.of())==null)
        &&f.sourceIds().stream().allMatch(id->{Source source=corpus.byId.get(id);return source!=null&&!source.index()&&!source.historical()&&!source.scopeContext().blocked();});}
}
