package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.facts.EntityIdentity;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Identity bindings are derived afresh from original text, never from model-provided identity attributes. */
final class SubjectIdentity {
    record Problem(String code,String reason) {}
    private static final Set<String> DEPENDENT=Set.of("PERSON_ROLE","CERTIFICATE","VALIDITY","MONTHS","RELATION","CONTRACT");
    private final List<Fact> facts;
    private final EvidenceCorpus corpus;
    private final List<Map<String,Object>> layout;
    private final Map<String,Map<String,Object>> identities=new HashMap<>();
    SubjectIdentity(List<Fact> facts,EvidenceCorpus corpus,List<Map<String,Object>> layout){this.facts=facts;this.corpus=corpus;this.layout=layout;}

    Problem issue(Rule rule,List<Fact> matched){
        if(!DEPENDENT.contains(rule.predicate()))return null;
        List<Fact> roles=DomainBindings.roleLinks(rule.subject(),facts,corpus).stream().map(f->rolePerson(f,rule.subject())).filter(Objects::nonNull).toList();
        Set<String> names=new HashSet<>();roles.forEach(f->names.add(person(f)));matched.stream().map(SubjectIdentity::person).filter(n->!n.isBlank()).forEach(names::add);names.remove("");
        for(String name:names){Set<String> identifiers=new HashSet<>();for(Fact f:facts)if(name.equals(person(f)))identifiers.addAll(keys(identity(f)));
            if(identifiers.size()>1)return new Problem("SUBJECT_IDENTITY_CONFLICT","同名人员的原文出现不同身份证标识，岗位、证书或社保不能仅凭姓名混用；需确认适用人员及资料归属");
        }
        // A named certificate on its own remains a local fact. A role-to-person-to-proof join needs identity support.
        if(!roles.isEmpty()&&!rule.predicate().equals("PERSON_ROLE"))for(Fact proof:matched){String name=person(proof);if(name.isBlank()||proof.kind().equals("PERSON_ROLE"))continue;
            List<Fact> assignments=roles.stream().filter(f->name.equals(person(f))).toList();if(assignments.isEmpty())continue;
            if(assignments.stream().noneMatch(role->sameLocalOccurrence(role,proof)||sameStrongIdentity(role,proof)))return new Problem("ENTITY_IDENTITY_AMBIGUOUS","岗位与证书/社保虽有同名，但跨来源身份关联尚未证明；来源内事实保留，不能把同名候选当作同一人");
        }
        return null;
    }
    List<Map<String,Object>> bindings(List<Fact> matched){return matched.stream().filter(f->!person(f).isBlank()).map(f->{Map<String,Object> entity=identity(f);return linked("factId",f.id(),"subject",redact(person(f)),"entityId",entity.get("id"),"identityStrength",entity.get("identityStrength"),"identityClaims",entity.getOrDefault("identityClaims",List.of()),"sourceIds",f.sourceIds(),"structureProof",entity.getOrDefault("structureProof",Map.of()));}).toList();}
    boolean samePerson(Fact left,Fact right){return !person(left).isBlank()&&person(left).equals(person(right))&&(sameLocalOccurrence(left,right)||sameStrongIdentity(left,right));}
    /** Only an identifier re-derived from source text may increase a proven distinct-person count. */
    String countIdentity(Fact fact){Map<String,Object> entity=identity(fact);Set<String> values=keys(entity);return "STRONG".equals(entity.get("identityStrength"))&&values.size()==1?values.iterator().next():"";}
    private static Fact rolePerson(Fact fact,String selector){if(fact.kind().equals("PERSON_ROLE"))return fact;String name=DomainBindings.roleTarget(fact,selector);
        Fact projected=new Fact(fact.id()+":role-person", "PERSON_ROLE",selector,"personName",fact.rawValue(),name,"","",fact.scope(),fact.sourceIds(),fact.attributes());
        return person(projected).isBlank()?null:projected;}
    private boolean sameLocalOccurrence(Fact a,Fact b){var left=LayoutBinding.structure(a,layout,corpus.byId);var right=LayoutBinding.structure(b,layout,corpus.byId);if(!left.rows().isEmpty()||!right.rows().isEmpty())return left.valid()&&right.valid()&&left.rows().stream().anyMatch(x->right.rows().stream().anyMatch(y->x.recordKey().equals(y.recordKey())));return a.sourceIds().stream().anyMatch(b.sourceIds()::contains);}
    private boolean sameStrongIdentity(Fact a,Fact b){Set<String> left=keys(identity(a)),right=keys(identity(b));return "STRONG".equals(identity(a).get("identityStrength"))&&"STRONG".equals(identity(b).get("identityStrength"))&&left.size()==1&&right.size()==1&&left.equals(right);}
    private Set<String> keys(Map<String,Object> entity){Set<String> result=new HashSet<>();for(Map<String,Object> claim:RuleEvaluator.maps(entity.get("identityClaims")))if("CN_ID".equals(str(claim,"field"))&&!str(claim,"valueFingerprint").isBlank())result.add(str(claim,"valueFingerprint"));return result;}
    private Map<String,Object> identity(Fact fact){return identities.computeIfAbsent(fact.id(),id->EntityIdentity.resolvePerson(person(fact),LayoutBinding.sources(fact,corpus.byId),layout,ProofVerifier.strings(fact.attributes().get("cellIds"))));}
    static String person(Fact f){String name=switch(f.kind()){case "PERSON_ROLE"->f.value();case "MONTHS"->str(f.attributes(),"person");case "CERTIFICATE","DATE_RANGE","RELATION"->f.subject();case "CONTRACT"->str(f.attributes(),"person");default->"";};
        if(name.isBlank()||name.length()>40||DomainBindings.role(name)||name.matches(".*(?:公司|集团|事务所|合作社|合伙企业|投标人|牵头人|联合体|项目负责人|项目经理|设计负责人|施工负责人|法定代表人|授权代表|技术负责人|安全员).*"))return "";return name;
    }
}
