package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** One source-backed subject expansion for model contracts, native admission and candidate routing.
 * Admission expresses relevance only: it never proves cross-record identity or an employer. */
final class AgentRuleSubjects {
    private static final Set<String> COLLECTIVE=cn.bidcheck.compat.Java8Api.setOf("上述人员", "以上人员", "前述人员", "上述各岗位人员");
    private AgentRuleSubjects() {}
    static boolean collective(String selector){return COLLECTIVE.contains(selector);}
    static List<String> selectors(Rule rule){
        List<String> validated=RequirementContextBindings.validatedSubjectSet(rule);
        if(!validated.isEmpty())return validated;
        return collective(rule.subject())||cn.bidcheck.compat.Java8Api.isBlank(rule.subject())?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf(rule.subject());
    }
    static List<String> subjects(Rule rule,Collection<Fact> facts,EvidenceCorpus corpus){
        if(corpus==null)return cn.bidcheck.compat.Java8Api.listOf();
        return cn.bidcheck.compat.Java8Api.toList(selectors(rule).stream().flatMap(selector->DomainBindings.subjects(selector,new ArrayList<>(facts),corpus).stream())
            .filter(DomainBindings::known).filter(subject->!DomainBindings.role(subject)&&!collective(subject)).distinct());
    }
    static Map<String,Object> resolve(Rule rule,Collection<Fact> facts,EvidenceCorpus corpus,List<Map<String,Object>> layout){
        List<String> selectors=selectors(rule);
        if(!collective(rule.subject()))return AgentSubjectBindings.resolve(rule.subject(),facts,corpus,layout);
        List<Map<String,Object>> bindings=cn.bidcheck.compat.Java8Api.toList(selectors.stream().map(selector->AgentSubjectBindings.resolve(selector,facts,corpus,layout)));
        Map<String,Map<String,Object>> people=new LinkedHashMap<>();
        for(Map<String,Object> binding:bindings)for(Map<String,Object> person:RuleEvaluator.maps(binding.get("resolvedSubjects"))){
            String key=str(person,"personId")+"|"+str(person,"organizationId")+"|"+str(person,"subject");
            Map<String,Object> value=people.computeIfAbsent(key,k->{Map<String,Object> copy=new LinkedHashMap<>(person);copy.put("subjectSelectors",new ArrayList<String>());return copy;});
            @SuppressWarnings("unchecked") List<String> roles=(List<String>)value.get("subjectSelectors");
            if(!roles.contains(str(binding,"subjectSelector")))roles.add(str(binding,"subjectSelector"));
        }
        List<String> unresolved=cn.bidcheck.compat.Java8Api.toList(bindings.stream().filter(b->!"RESOLVED".equals(str(b,"bindingStatus"))).map(b->str(b,"subjectSelector")));
        String status=selectors.isEmpty()?"SUBJECT_SET_UNRESOLVED":bindings.stream().anyMatch(b->"IDENTITY_CONFLICT".equals(str(b,"bindingStatus")))?"IDENTITY_CONFLICT"
            :unresolved.isEmpty()?"RESOLVED":people.isEmpty()?"UNRESOLVED":"PARTIALLY_RESOLVED";
        return linked("subjectSelector",rule.subject(),"subjectSelectors",selectors,"subjectSetBasis",selectors.isEmpty()?"UNVERIFIED_REFERENCE":"EXPLICIT_PERSONNEL_SET",
            "bindingStatus",status,"resolvedSubjects",cn.bidcheck.compat.Java8Api.listCopyOf(people.values()),"subjectBindings",bindings,"unresolvedSubjectSelectors",unresolved,
            "allRequiredSelectorsResolved",!selectors.isEmpty()&&unresolved.isEmpty(),"bindingIsBusinessProof",false,
            "nextAction",status.equals("RESOLVED")?"READ_EACH_SUBJECT_FIELDS":status.equals("IDENTITY_CONFLICT")?"RESOLVE_IDENTITY_CONFLICT":"RESOLVE_SUBJECT_FIRST",
            "identityJoinPolicy","岗位集合来自经原文校验的招标要求；每一岗位仍须有当前任职来源。具名集合只用于取证，不能证明名单人数完整、跨页同名身份或缴费单位关系。");
    }
}
