package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.facts.EntityIdentity;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Model-facing identity hints, never new evidence or permission to join same-name records. */
final class AgentSubjectBindings {
    private AgentSubjectBindings() {}

    static Map<String,Object> resolve(String selector,Collection<Fact> accepted,EvidenceCorpus corpus,List<Map<String,Object>> layout){
        boolean role=DomainBindings.role(selector);
        if(selector.isBlank())return result(selector,"NOT_REQUIRED",List.of(),"NONE");
        if(corpus==null)return result(selector,"UNRESOLVED",List.of(),role?"RESOLVE_SUBJECT_FIRST":"READ_NAMED_SUBJECT");
        List<Fact> facts=new ArrayList<>(accepted);
        List<Fact> bindings=role?DomainBindings.roleLinks(selector,facts,corpus):facts.stream()
            .filter(f->selector.equals(SubjectIdentity.person(f))||company(selector)&&selector.equals(f.subject()))
            .toList();
        Map<String,Map<String,Object>> resolved=new LinkedHashMap<>();
        for(Fact fact:bindings){
            if(fact.sourceIds().isEmpty()||fact.sourceIds().stream().anyMatch(id->!corpus.byId.containsKey(id)))continue;
            List<Map<String,Object>> sources=LayoutBinding.sources(fact,corpus.byId).stream().filter(s->{
                Source source=corpus.byId.get(str(s,"id"));
                return source!=null&&!source.index()&&!source.scopeContext().blocked()
                    &&(role?DomainBindings.currentRoleSource(fact,Map.of(source.id(),source)):!source.historical());
            }).toList();
            if(sources.isEmpty())continue;
            String name=role?DomainBindings.roleTarget(fact,selector):selector;
            if(!DomainBindings.known(name)||DomainBindings.role(name))continue;
            boolean organization=role?EnterpriseSubjectBindings.companyRole(selector):company(name);
            if(!organization&&personName(name).isBlank())continue;
            // EntityIdentity re-derives keys from cited original text/layout. Model attributes
            // such as personId/entityId/employer never establish identity or employment here.
            Map<String,Object> entity=organization
                ?EntityIdentity.resolve(linked("kind","COMPANY","subject",name,"attributes",Map.of("entityType","COMPANY")),sources)
                :EntityIdentity.resolvePerson(name,sources,layout,ProofVerifier.strings(fact.attributes().get("cellIds")));
            String entityId=str(entity,"id");
            Map<String,Object> value=resolved.computeIfAbsent(entityId+":"+name,id->linked("subject",name,"entityType",organization?"ORGANIZATION":"PERSON",
                "personId",organization?"":entityId,"organizationId",organization?entityId:"","identityStrength",str(entity,"identityStrength"),
                "bindingFactIds",new ArrayList<String>(),"sourceIds",new ArrayList<String>()));
            append(value,"bindingFactIds",List.of(fact.id()));append(value,"sourceIds",sources.stream().map(s->str(s,"id")).toList());
        }
        List<Map<String,Object>> subjects=List.copyOf(resolved.values());
        long names=subjects.stream().map(s->str(s,"subject")).distinct().count();
        String status=subjects.isEmpty()?"UNRESOLVED":names>1?"MULTIPLE_CANDIDATES":"RESOLVED";
        if(!subjects.isEmpty()&&subjects.stream().collect(java.util.stream.Collectors.groupingBy(s->str(s,"subject"))).values().stream()
            .anyMatch(group->group.stream().filter(s->"STRONG".equals(str(s,"identityStrength"))).map(s->str(s,"personId")+str(s,"organizationId")).distinct().count()>1))status="IDENTITY_CONFLICT";
        String next=status.equals("IDENTITY_CONFLICT")?"RESOLVE_IDENTITY_CONFLICT":status.equals("MULTIPLE_CANDIDATES")?"DISAMBIGUATE_OR_READ_EACH_SUBJECT":subjects.isEmpty()?role?"RESOLVE_SUBJECT_FIRST":"READ_NAMED_SUBJECT":"READ_SUBJECT_FIELDS";
        return result(selector,status,subjects,next);
    }
    private static Map<String,Object> result(String selector,String status,List<Map<String,Object>> subjects,String next){
        return linked("subjectSelector",selector,"bindingStatus",status,"resolvedSubjects",subjects,"nextAction",next,
            "identityJoinPolicy","personId/organizationId 只由来源重建；SOURCE_LOCAL 仅标识当前来源内主体，不证明跨页同名是同一人。多个主体不得任选其一。单位角色绑定不证明人员聘用或委派关系。",
            "bindingIsBusinessProof",false);
    }
    @SuppressWarnings("unchecked") private static void append(Map<String,Object> map,String key,List<String> values){List<String> target=(List<String>)map.get(key);for(String value:values)if(!target.contains(value))target.add(value);}
    private static boolean company(String name){return name.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$");}
    private static String personName(String name){return SubjectIdentity.person(new Fact("hint","RELATION",name,"","","","","","UNRESOLVED",List.of(),Map.of()));}
}
