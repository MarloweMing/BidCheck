package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.facts.EntityIdentity;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Identity bindings are derived afresh from original text, never from model-provided identity attributes. */
final class SubjectIdentity {
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Problem {
        private final String code;
        private final String reason;
        @com.fasterxml.jackson.annotation.JsonCreator
        Problem(@com.fasterxml.jackson.annotation.JsonProperty("code") String code, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason) { this.code = code; this.reason = reason; }
        public String code() { return code; }
        public String reason() { return reason; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Problem that = (Problem) other; return java.util.Objects.equals(this.code, that.code) && java.util.Objects.equals(this.reason, that.reason); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.code); hash = 31 * hash + java.util.Objects.hashCode(this.reason); return hash; }
        @Override public String toString() { return "Problem[" + "code=" + code + ", reason=" + reason + "]"; }
}
    private static final Set<String> DEPENDENT=cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "CERTIFICATE", "VALIDITY", "MONTHS", "RELATION", "CONTRACT");
    private final List<Fact> facts;
    private final EvidenceCorpus corpus;
    private final List<Map<String,Object>> layout;
    private final Map<String,Map<String,Object>> identities=new HashMap<>();
    private final Map<String,Map<String,Object>> attachmentIdentities=new HashMap<>(),attachmentProofs=new HashMap<>();
    SubjectIdentity(List<Fact> facts,EvidenceCorpus corpus,List<Map<String,Object>> layout){this.facts=facts;this.corpus=corpus;this.layout=layout;}

    Problem issue(Rule rule,List<Fact> matched){
        if(!DEPENDENT.contains(rule.predicate()))return null;
        List<Fact> roles=cn.bidcheck.compat.Java8Api.toList(DomainBindings.roleLinks(rule.subject(),facts,corpus).stream().map(f->rolePerson(f,rule.subject())).filter(Objects::nonNull));
        Set<String> names=new HashSet<>();roles.forEach(f->names.add(person(f)));matched.stream().map(SubjectIdentity::person).filter(n->!cn.bidcheck.compat.Java8Api.isBlank(n)).forEach(names::add);names.remove("");
        for(String name:names){Set<String> identifiers=new HashSet<>();for(Fact f:facts)if(name.equals(person(f)))identifiers.addAll(keys(identity(f)));
            if(identifiers.size()>1)return new Problem("SUBJECT_IDENTITY_CONFLICT","同名人员的原文出现不同身份证标识，岗位、证书或社保不能仅凭姓名混用；需确认适用人员及资料归属");
        }
        // A named certificate on its own remains a local fact. A role-to-person-to-proof join needs identity support.
        if(!roles.isEmpty()&&!rule.predicate().equals("PERSON_ROLE"))for(Fact proof:matched){String name=person(proof);if(cn.bidcheck.compat.Java8Api.isBlank(name)||proof.kind().equals("PERSON_ROLE"))continue;
            List<Fact> assignments=cn.bidcheck.compat.Java8Api.toList(roles.stream().filter(f->name.equals(person(f))||ConfirmedSubjectBindings.matches(person(f),proof,facts,corpus)));if(assignments.isEmpty())continue;
            if(assignments.stream().noneMatch(role->samePerson(role,proof)))return new Problem("ENTITY_IDENTITY_AMBIGUOUS","岗位与证书/社保虽有同名，但跨来源身份关联尚未证明；来源内事实保留，不能把同名候选当作同一人");
        }
        return null;
    }
    List<Map<String,Object>> bindings(List<Fact> matched){return cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->!cn.bidcheck.compat.Java8Api.isBlank(person(f))).map(f->{Map<String,Object> entity=identity(f);Map<String,Object> result=linked("factId",f.id(),"subject",redact(person(f)),"entityId",entity.get("id"),"identityStrength",entity.get("identityStrength"),"identityClaims",entity.getOrDefault("identityClaims",cn.bidcheck.compat.Java8Api.listOf()),"sourceIds",f.sourceIds(),"structureProof",entity.getOrDefault("structureProof",cn.bidcheck.compat.Java8Api.mapOf()));
        for(Fact role:facts)if(role.kind().equals("PERSON_ROLE")){Map<String,Object> confirmed=ConfirmedSubjectBindings.edge(person(role),f,facts,corpus);if(!confirmed.isEmpty()){result.put("confirmedIdentityProof",confirmed);break;}if(person(role).equals(person(f))){Map<String,Object> proof=attachmentProof(role,f);if(!proof.isEmpty()){result.put("attachmentIdentityProof",proof);break;}}}return result;}));}
    boolean samePerson(Fact left,Fact right){Set<String> claimed=new HashSet<>(keys(identity(left)));claimed.addAll(keys(identity(right)));if(claimed.size()>1)return false;String name=person(left);if(left.kind().equals("PERSON_ROLE")&&ConfirmedSubjectBindings.matchesPair(left,right,facts,corpus)||right.kind().equals("PERSON_ROLE")&&ConfirmedSubjectBindings.matchesPair(right,left,facts,corpus))return true;return !cn.bidcheck.compat.Java8Api.isBlank(name)&&!name.matches(".*[*＊×●_].*")&&name.equals(person(right))&&(sameLocalOccurrence(left,right)||sameStrongIdentity(left,right)||!attachmentProof(left,right).isEmpty()||!attachmentProof(right,left).isEmpty());}
    Map<String,Object> bindingBetween(Fact role,Fact dependent){
        if(!samePerson(role,dependent))return cn.bidcheck.compat.Java8Api.mapOf();Map<String,Object> confirmed=ConfirmedSubjectBindings.edge(person(role),dependent,facts,corpus);if(!confirmed.isEmpty())return confirmed;
        Map<String,Object> attachment=attachmentProof(role,dependent);if(!attachment.isEmpty())return attachment;
        return linked("method",sameStrongIdentity(role,dependent)?"SAME_SOURCE_VALIDATED_IDENTIFIER":"SAME_LOCAL_RECORD","appointmentSourceIds",role.sourceIds(),"dependentSourceIds",dependent.sourceIds(),"fieldsRewritten",false);
    }
    /** Only an identifier re-derived from source text may increase a proven distinct-person count. */
    String countIdentity(Fact fact){Map<String,Object> entity=identity(fact);Set<String> values=keys(entity);return "STRONG".equals(entity.get("identityStrength"))&&values.size()==1?values.iterator().next():"";}
    private static Fact rolePerson(Fact fact,String selector){if(fact.kind().equals("PERSON_ROLE"))return fact;String name=DomainBindings.roleTarget(fact,selector);
        Fact projected=new Fact(fact.id()+":role-person", "PERSON_ROLE",selector,"personName",fact.rawValue(),name,"","",fact.scope(),fact.sourceIds(),fact.attributes());
        return cn.bidcheck.compat.Java8Api.isBlank(person(projected))?null:projected;}
    private boolean sameLocalOccurrence(Fact a,Fact b){cn.bidcheck.verification.facts.TableStructure.Result left=LayoutBinding.structure(a,layout,corpus.byId);cn.bidcheck.verification.facts.TableStructure.Result right=LayoutBinding.structure(b,layout,corpus.byId);if(!left.rows().isEmpty()||!right.rows().isEmpty())return left.valid()&&right.valid()&&left.rows().stream().anyMatch(x->right.rows().stream().anyMatch(y->x.recordKey().equals(y.recordKey())));return a.sourceIds().stream().anyMatch(b.sourceIds()::contains);}
    private boolean sameStrongIdentity(Fact a,Fact b){Set<String> left=keys(identity(a)),right=keys(identity(b));return "STRONG".equals(identity(a).get("identityStrength"))&&"STRONG".equals(identity(b).get("identityStrength"))&&left.size()==1&&right.size()==1&&left.equals(right);}
    private Set<String> keys(Map<String,Object> entity){Set<String> result=new HashSet<>();for(Map<String,Object> claim:RuleEvaluator.maps(entity.get("identityClaims")))if("CN_ID".equals(str(claim,"field"))&&!cn.bidcheck.compat.Java8Api.isBlank(str(claim,"valueFingerprint")))result.add(str(claim,"valueFingerprint"));return result;}
    private Map<String,Object> attachmentProof(Fact role,Fact proof){
        if(!role.kind().equals("PERSON_ROLE")||!cn.bidcheck.compat.Java8Api.setOf("CERTIFICATE", "MONTHS", "RELATION").contains(proof.kind()))return cn.bidcheck.compat.Java8Api.mapOf();
        return attachmentProofs.computeIfAbsent(role.sourceIds()+"|"+role.value()+"|"+role.attributes()+"|"+proof.sourceIds()+"|"+proof.subject()+"|"+proof.attributes(),key->{Map<String,Object> strong=PersonnelAttachmentIdentity.proof(role,proof,corpus);return strong.isEmpty()?PersonnelAttachmentIdentity.multiSignalProof(role,proof,corpus):strong;});
    }
    List<Map<String,Object>> confirmationCandidates(Rule rule,List<Fact> matched){
        List<Map<String,Object>> out=new ArrayList<>();List<Fact> roles=cn.bidcheck.compat.Java8Api.toList(DomainBindings.roleLinks(rule.subject(),facts,corpus).stream().map(f->rolePerson(f,rule.subject())).filter(Objects::nonNull));
        for(Fact role:roles)for(Fact proof:matched){if(samePerson(role,proof))continue;cn.bidcheck.verification.engine.EvidenceIdentitySignals.Assessment score=EvidenceIdentitySignals.assess(role,proof,corpus);if(!score.candidate())continue;
            Set<String> ids=new LinkedHashSet<>(role.sourceIds());ids.addAll(proof.sourceIds());List<Map<String,Object>> evidence=cn.bidcheck.compat.Java8Api.toList(ids.stream().map(corpus.byId::get).filter(Objects::nonNull).map(s->linked("sourceId",s.id(),"quote",s.evidence().quote())));if(evidence.isEmpty()||evidence.size()>12)continue;
            Map<String,Object> proposed=linked("kind","RELATION","subject",person(role),"field","同一人","rawValue",person(proof),"value",person(proof),"unit","","basis","来源证明材料的人物对应","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(ids),"attributes",linked("object",person(proof),"number",str(proof.attributes(),"number")));
            out.add(linked("type",person(proof).matches(".*[*＊×●_].*")?"MASKED_PERSON_IDENTITY":"PERSON_IDENTITY","claim",person(role)+"的任职资料与姓名为"+person(proof)+"的证明材料对应同一人","reasoning","当前任职、所属附件及单位等原文信号支持此关联，仍有未确定或冲突信号，需选择确认；不直接证明证书或社保满足要求。","confidence",Math.min(.95,score.score()/100d),"risks",score.vetoes(),"proposedFact",proposed,"evidence",evidence,"targetId",rule.id(),"unresolvedGapCodes",cn.bidcheck.compat.Java8Api.listOf("ENTITY_IDENTITY_AMBIGUOUS", "PERSON_IDENTITY_UNPROVEN", "SUBJECT_IDENTITY_UNPROVEN"),"identitySignals",score.proof()));
        }return out;
    }
    private Map<String,Object> identity(Fact fact){return identities.computeIfAbsent(fact.id(),id->{Map<String,Object> entity=new LinkedHashMap<>(EntityIdentity.resolvePerson(person(fact),LayoutBinding.sources(fact,corpus.byId),layout,ProofVerifier.strings(fact.attributes().get("cellIds"))));
        if(SocialInsuranceEvidenceRecords.claims(fact)&&SocialInsuranceEvidenceRecords.issue(fact,corpus.byId)==null){
            cn.bidcheck.verification.engine.SocialInsuranceEvidenceRecords.Record record=SocialInsuranceEvidenceRecords.matching(fact,corpus.byId);
            if(record!=null&&!cn.bidcheck.compat.Java8Api.isBlank(record.identifierFingerprint())){
                List<Map<String,Object>> claims=new ArrayList<>(RuleEvaluator.maps(entity.get("identityClaims")));
                claims.add(linked("field","CN_ID","valueFingerprint",record.identifierFingerprint(),"sourceIds",cn.bidcheck.compat.Java8Api.toList(record.sources().stream().map(Source::id)),"proof","SOURCE_VALIDATED_SOCIAL_TABLE_ROW"));
                entity.put("identityClaims",cn.bidcheck.compat.Java8Api.listCopyOf(claims));Set<String> ids=new HashSet<>();for(java.util.Map<java.lang.String, java.lang.Object> claim:claims)if("CN_ID".equals(str(claim,"field")))ids.add(str(claim,"valueFingerprint"));
                if(ids.size()==1){entity.put("identityStrength","STRONG");entity.put("id","entity:"+VerificationTypes.identity("PERSON:CN_ID_SHA256:"+ids.iterator().next()).substring(0,40));}else entity.put("identityStrength","SOURCE_LOCAL");
            }
        }
        if(fact.kind().equals("PERSON_ROLE")){
            Map<String,Object> attachment=attachmentIdentities.computeIfAbsent(fact.sourceIds()+"|"+fact.value(),key->PersonnelAttachmentIdentity.appointmentIdentity(fact,corpus));
            if(!attachment.isEmpty()){List<Map<String,Object>> claims=new ArrayList<>(RuleEvaluator.maps(entity.get("identityClaims")));claims.add(attachment);entity.put("identityClaims",cn.bidcheck.compat.Java8Api.listCopyOf(claims));
                Set<String> ids=new HashSet<>();for(java.util.Map<java.lang.String, java.lang.Object> claim:claims)if("CN_ID".equals(str(claim,"field")))ids.add(str(claim,"valueFingerprint"));if(ids.size()==1){entity.put("identityStrength","STRONG");entity.put("id","entity:"+VerificationTypes.identity("PERSON:CN_ID_SHA256:"+ids.iterator().next()).substring(0,40));}else entity.put("identityStrength","SOURCE_LOCAL");}
        }return entity;});}
    static String person(Fact f){String name;
switch (f.kind()) {case "PERSON_ROLE":name = f.value(); break;case "MONTHS":name = str(f.attributes(),"person"); break;case "CERTIFICATE":case "DATE_RANGE":case "RELATION":name = f.subject(); break;case "CONTRACT":name = str(f.attributes(),"person"); break;default:name = ""; break;}
        if(cn.bidcheck.compat.Java8Api.isBlank(name)||name.length()>40||DomainBindings.role(name)||name.matches(".*(?:公司|集团|事务所|合作社|合伙企业|投标人|牵头人|联合体|项目负责人|项目经理|设计负责人|施工负责人|法定代表人|授权代表|技术负责人|安全员).*"))return "";return name;
    }
}
