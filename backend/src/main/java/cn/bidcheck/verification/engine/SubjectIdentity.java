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
    private final Map<String,Map<String,Object>> attachmentIdentities=new HashMap<>(),attachmentProofs=new HashMap<>();
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
            List<Fact> assignments=roles.stream().filter(f->name.equals(person(f))||ConfirmedSubjectBindings.matches(person(f),proof,facts,corpus)).toList();if(assignments.isEmpty())continue;
            if(assignments.stream().noneMatch(role->samePerson(role,proof)))return new Problem("ENTITY_IDENTITY_AMBIGUOUS","岗位与证书/社保虽有同名，但跨来源身份关联尚未证明；来源内事实保留，不能把同名候选当作同一人");
        }
        return null;
    }
    List<Map<String,Object>> bindings(List<Fact> matched){return matched.stream().filter(f->!person(f).isBlank()).map(f->{Map<String,Object> entity=identity(f);Map<String,Object> result=linked("factId",f.id(),"subject",redact(person(f)),"entityId",entity.get("id"),"identityStrength",entity.get("identityStrength"),"identityClaims",entity.getOrDefault("identityClaims",List.of()),"sourceIds",f.sourceIds(),"structureProof",entity.getOrDefault("structureProof",Map.of()));
        for(Fact role:facts)if(role.kind().equals("PERSON_ROLE")){Map<String,Object> confirmed=ConfirmedSubjectBindings.edge(person(role),f,facts,corpus);if(!confirmed.isEmpty()){result.put("confirmedIdentityProof",confirmed);break;}if(person(role).equals(person(f))){Map<String,Object> proof=attachmentProof(role,f);if(!proof.isEmpty()){result.put("attachmentIdentityProof",proof);break;}}}return result;}).toList();}
    boolean samePerson(Fact left,Fact right){Set<String> claimed=new HashSet<>(keys(identity(left)));claimed.addAll(keys(identity(right)));if(claimed.size()>1)return false;String name=person(left);if(left.kind().equals("PERSON_ROLE")&&ConfirmedSubjectBindings.matchesPair(left,right,facts,corpus)||right.kind().equals("PERSON_ROLE")&&ConfirmedSubjectBindings.matchesPair(right,left,facts,corpus))return true;return !name.isBlank()&&!name.matches(".*[*＊×●_].*")&&name.equals(person(right))&&(sameLocalOccurrence(left,right)||sameStrongIdentity(left,right)||!attachmentProof(left,right).isEmpty()||!attachmentProof(right,left).isEmpty());}
    Map<String,Object> bindingBetween(Fact role,Fact dependent){
        if(!samePerson(role,dependent))return Map.of();Map<String,Object> confirmed=ConfirmedSubjectBindings.edge(person(role),dependent,facts,corpus);if(!confirmed.isEmpty())return confirmed;
        Map<String,Object> attachment=attachmentProof(role,dependent);if(!attachment.isEmpty())return attachment;
        return linked("method",sameStrongIdentity(role,dependent)?"SAME_SOURCE_VALIDATED_IDENTIFIER":"SAME_LOCAL_RECORD","appointmentSourceIds",role.sourceIds(),"dependentSourceIds",dependent.sourceIds(),"fieldsRewritten",false);
    }
    /** Only an identifier re-derived from source text may increase a proven distinct-person count. */
    String countIdentity(Fact fact){Map<String,Object> entity=identity(fact);Set<String> values=keys(entity);return "STRONG".equals(entity.get("identityStrength"))&&values.size()==1?values.iterator().next():"";}
    private static Fact rolePerson(Fact fact,String selector){if(fact.kind().equals("PERSON_ROLE"))return fact;String name=DomainBindings.roleTarget(fact,selector);
        Fact projected=new Fact(fact.id()+":role-person", "PERSON_ROLE",selector,"personName",fact.rawValue(),name,"","",fact.scope(),fact.sourceIds(),fact.attributes());
        return person(projected).isBlank()?null:projected;}
    private boolean sameLocalOccurrence(Fact a,Fact b){var left=LayoutBinding.structure(a,layout,corpus.byId);var right=LayoutBinding.structure(b,layout,corpus.byId);if(!left.rows().isEmpty()||!right.rows().isEmpty())return left.valid()&&right.valid()&&left.rows().stream().anyMatch(x->right.rows().stream().anyMatch(y->x.recordKey().equals(y.recordKey())));return a.sourceIds().stream().anyMatch(b.sourceIds()::contains);}
    private boolean sameStrongIdentity(Fact a,Fact b){Set<String> left=keys(identity(a)),right=keys(identity(b));return "STRONG".equals(identity(a).get("identityStrength"))&&"STRONG".equals(identity(b).get("identityStrength"))&&left.size()==1&&right.size()==1&&left.equals(right);}
    private Set<String> keys(Map<String,Object> entity){Set<String> result=new HashSet<>();for(Map<String,Object> claim:RuleEvaluator.maps(entity.get("identityClaims")))if("CN_ID".equals(str(claim,"field"))&&!str(claim,"valueFingerprint").isBlank())result.add(str(claim,"valueFingerprint"));return result;}
    private Map<String,Object> attachmentProof(Fact role,Fact proof){
        if(!role.kind().equals("PERSON_ROLE")||!Set.of("CERTIFICATE","MONTHS","RELATION").contains(proof.kind()))return Map.of();
        return attachmentProofs.computeIfAbsent(role.sourceIds()+"|"+role.value()+"|"+role.attributes()+"|"+proof.sourceIds()+"|"+proof.subject()+"|"+proof.attributes(),key->{Map<String,Object> strong=PersonnelAttachmentIdentity.proof(role,proof,corpus);return strong.isEmpty()?PersonnelAttachmentIdentity.multiSignalProof(role,proof,corpus):strong;});
    }
    List<Map<String,Object>> confirmationCandidates(Rule rule,List<Fact> matched){
        List<Map<String,Object>> out=new ArrayList<>();List<Fact> roles=DomainBindings.roleLinks(rule.subject(),facts,corpus).stream().map(f->rolePerson(f,rule.subject())).filter(Objects::nonNull).toList();
        for(Fact role:roles)for(Fact proof:matched){if(samePerson(role,proof))continue;var score=EvidenceIdentitySignals.assess(role,proof,corpus);if(!score.candidate())continue;
            Set<String> ids=new LinkedHashSet<>(role.sourceIds());ids.addAll(proof.sourceIds());List<Map<String,Object>> evidence=ids.stream().map(corpus.byId::get).filter(Objects::nonNull).map(s->linked("sourceId",s.id(),"quote",s.evidence().quote())).toList();if(evidence.isEmpty()||evidence.size()>12)continue;
            Map<String,Object> proposed=linked("kind","RELATION","subject",person(role),"field","同一人","rawValue",person(proof),"value",person(proof),"unit","","basis","来源证明材料的人物对应","scope","CURRENT_PROJECT","sourceIds",List.copyOf(ids),"attributes",linked("object",person(proof),"number",str(proof.attributes(),"number")));
            out.add(linked("type",person(proof).matches(".*[*＊×●_].*")?"MASKED_PERSON_IDENTITY":"PERSON_IDENTITY","claim",person(role)+"的任职资料与姓名为"+person(proof)+"的证明材料对应同一人","reasoning","当前任职、所属附件及单位等原文信号支持此关联，仍有未确定或冲突信号，需选择确认；不直接证明证书或社保满足要求。","confidence",Math.min(.95,score.score()/100d),"risks",score.vetoes(),"proposedFact",proposed,"evidence",evidence,"targetId",rule.id(),"unresolvedGapCodes",List.of("ENTITY_IDENTITY_AMBIGUOUS","PERSON_IDENTITY_UNPROVEN","SUBJECT_IDENTITY_UNPROVEN"),"identitySignals",score.proof()));
        }return out;
    }
    private Map<String,Object> identity(Fact fact){return identities.computeIfAbsent(fact.id(),id->{Map<String,Object> entity=new LinkedHashMap<>(EntityIdentity.resolvePerson(person(fact),LayoutBinding.sources(fact,corpus.byId),layout,ProofVerifier.strings(fact.attributes().get("cellIds"))));
        if(SocialInsuranceEvidenceRecords.claims(fact)&&SocialInsuranceEvidenceRecords.issue(fact,corpus.byId)==null){
            var record=SocialInsuranceEvidenceRecords.matching(fact,corpus.byId);
            if(record!=null&&!record.identifierFingerprint().isBlank()){
                List<Map<String,Object>> claims=new ArrayList<>(RuleEvaluator.maps(entity.get("identityClaims")));
                claims.add(linked("field","CN_ID","valueFingerprint",record.identifierFingerprint(),"sourceIds",record.sources().stream().map(Source::id).toList(),"proof","SOURCE_VALIDATED_SOCIAL_TABLE_ROW"));
                entity.put("identityClaims",List.copyOf(claims));Set<String> ids=new HashSet<>();for(var claim:claims)if("CN_ID".equals(str(claim,"field")))ids.add(str(claim,"valueFingerprint"));
                if(ids.size()==1){entity.put("identityStrength","STRONG");entity.put("id","entity:"+VerificationTypes.identity("PERSON:CN_ID_SHA256:"+ids.iterator().next()).substring(0,40));}else entity.put("identityStrength","SOURCE_LOCAL");
            }
        }
        if(fact.kind().equals("PERSON_ROLE")){
            Map<String,Object> attachment=attachmentIdentities.computeIfAbsent(fact.sourceIds()+"|"+fact.value(),key->PersonnelAttachmentIdentity.appointmentIdentity(fact,corpus));
            if(!attachment.isEmpty()){List<Map<String,Object>> claims=new ArrayList<>(RuleEvaluator.maps(entity.get("identityClaims")));claims.add(attachment);entity.put("identityClaims",List.copyOf(claims));
                Set<String> ids=new HashSet<>();for(var claim:claims)if("CN_ID".equals(str(claim,"field")))ids.add(str(claim,"valueFingerprint"));if(ids.size()==1){entity.put("identityStrength","STRONG");entity.put("id","entity:"+VerificationTypes.identity("PERSON:CN_ID_SHA256:"+ids.iterator().next()).substring(0,40));}else entity.put("identityStrength","SOURCE_LOCAL");}
        }return entity;});}
    static String person(Fact f){String name=switch(f.kind()){case "PERSON_ROLE"->f.value();case "MONTHS"->str(f.attributes(),"person");case "CERTIFICATE","DATE_RANGE","RELATION"->f.subject();case "CONTRACT"->str(f.attributes(),"person");default->"";};
        if(name.isBlank()||name.length()>40||DomainBindings.role(name)||name.matches(".*(?:公司|集团|事务所|合作社|合伙企业|投标人|牵头人|联合体|项目负责人|项目经理|设计负责人|施工负责人|法定代表人|授权代表|技术负责人|安全员).*"))return "";return name;
    }
}
