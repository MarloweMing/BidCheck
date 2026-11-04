package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Turns source-backed ambiguities into explicit questions. It does not manufacture missing facts. */
final class ProgrammaticInferenceCandidates {
    static final String VERSION="programmatic-inference-candidates-1";
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ProgrammaticInferenceCandidates.class);
    private static final Set<String> IDENTITY_GAPS=cn.bidcheck.compat.Java8Api.setOf("SCORING_TITLE_IDENTITY_REQUIRED", "PERSON_IDENTITY_REQUIRED", "PERSON_IDENTITY_UNPROVEN", "PERSON_IDENTITY_AMBIGUOUS", "SUBJECT_IDENTITY_UNPROVEN", "ENTITY_IDENTITY_AMBIGUOUS", "CERTIFICATE_SUBJECT_REQUIRED", "CERTIFICATE_IDENTITY_REQUIRED", "PERSONNEL_BINDING_REQUIRED");
    private ProgrammaticInferenceCandidates(){}

    static List<Map<String,Object>> collect(Rule rule,Decision decision,List<Fact> facts,EvidenceCorpus corpus){
        if(!cn.bidcheck.compat.Java8Api.setOf("UNDETERMINED", "NEEDS_CONFIRMATION").contains(decision.status())||technical(decision))return cn.bidcheck.compat.Java8Api.listOf();
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        for(java.util.Map<java.lang.String, java.lang.Object> supplied:RuleEvaluator.maps(decision.proof().get("confirmationCandidates")))add(result,supplied,rule,corpus);
        Set<String> gaps=new HashSet<>();decision.gaps().forEach(g->gaps.add(g.code()));
        if(!Collections.disjoint(gaps,IDENTITY_GAPS))identityCandidates(rule,decision,facts,corpus,result);
        if(gaps.contains("SOCIAL_EMPLOYER_CONFIRMATION_REQUIRED"))employerCandidates(rule,decision,facts,corpus,result);
        // Keep each candidate explicit. A large candidate set needs narrowing rather than arbitrary truncation.
        if(result.size()>12){LOG.info("bidcheck.inference_candidate targetId={} event=PROGRAMMATIC_SKIPPED reason=CANDIDATE_SET_NEEDS_NARROWING candidateCount={}",rule.id(),result.size());return cn.bidcheck.compat.Java8Api.listOf();}return cn.bidcheck.compat.Java8Api.listCopyOf(result.values());
    }
    static boolean technical(Decision decision){return decision.gaps().stream().anyMatch(g->{String code=g.code();return code.startsWith("MODEL_")||(code.startsWith("AGENT_INVESTIGATION_")&&!code.equals("AGENT_INVESTIGATION_STALLED"))||code.contains("UNCOMPILED")||code.contains("COMPILATION")||code.equals("RULE_NOT_EXECUTABLE")||code.equals("SOURCE_VERSION_CONFLICT")||code.equals("UNSUPPORTED_PREDICATE");});}
    private static void add(Map<String,Map<String,Object>> target,Map<String,Object> raw,Rule rule,EvidenceCorpus corpus){
        if(!cn.bidcheck.compat.Java8Api.isBlank(str(raw,"targetId"))&&!str(raw,"targetId").equals(rule.id()))return;
        List<Map<String,Object>> evidence=RuleEvaluator.maps(raw.get("evidence"));if(evidence.isEmpty())evidence=RuleEvaluator.maps(raw.get("evidences"));
        if(evidence.isEmpty()||evidence.size()>12||cn.bidcheck.compat.Java8Api.isBlank(str(raw,"claim"))||cn.bidcheck.compat.Java8Api.isBlank(str(raw,"reasoning"))||!(raw.get("proposedFact") instanceof Map<?,?>)){LOG.info("bidcheck.inference_candidate targetId={} type={} event=PROGRAMMATIC_REJECTED reason=INCOMPLETE_CANDIDATE sourceCount={}",rule.id(),str(raw,"type"),evidence.size());return;}
        for(java.util.Map<java.lang.String, java.lang.Object> citation:evidence){Source source=corpus.byId.get(str(citation,"sourceId"));String quote=str(citation,"quote");if(source==null||cn.bidcheck.compat.Java8Api.isBlank(quote)||!source.evidence().quote().contains(quote)){LOG.info("bidcheck.inference_candidate targetId={} type={} event=PROGRAMMATIC_REJECTED reason={} sourceId={}",rule.id(),str(raw,"type"),source==null?"UNKNOWN_SOURCE":"QUOTE_NOT_IN_SOURCE",str(citation,"sourceId"));return;}}
        Map<String,Object> candidate=new LinkedHashMap<>(raw);candidate.put("targetId",rule.id());candidate.put("evidence",evidence);candidate.put("origin",VERSION);
        target.putIfAbsent(identity(rule.id()+candidate.get("proposedFact")+evidence),candidate);
    }
    private static void identityCandidates(Rule rule,Decision decision,List<Fact> facts,EvidenceCorpus corpus,Map<String,Map<String,Object>> out){
        List<Fact> roles=cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->f.kind().equals("PERSON_ROLE")&&f.scope().equals("CURRENT_PROJECT")&&DomainBindings.currentRoleSource(f,corpus.byId)
            &&(rule.subject().equals(f.value())||DomainBindings.roleMatches(rule.subject(),f.subject(),f,corpus))));
        List<Fact> proofs=new ArrayList<>(cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->cn.bidcheck.compat.Java8Api.setOf("CERTIFICATE", "DATE_RANGE", "MONTHS").contains(f.kind()))));
        // Masked names are often deliberately absent from the accepted fact ledger. Read the parsed source records too.
        List<Pattern> masks=cn.bidcheck.compat.Java8Api.toList(roles.stream().map(Fact::value).filter(n->n.matches("[\\p{IsHan}·]{2,12}")).distinct().map(n->Pattern.compile(n.codePoints().mapToObj(c->"(?:"+Pattern.quote(new String(Character.toChars(c)))+"|[*＊×●_])").reduce("",String::concat))));
        List<Source> maskedSources=cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().filter(s->!s.index()&&!s.historical()&&!s.scopeContext().blocked()&&s.evidence().quote().matches("(?s).*[*＊×●_].*")&&masks.stream().anyMatch(p->p.matcher(s.evidence().quote()).find())));
        for(cn.bidcheck.verification.engine.CertificateSourceComparison.Record record:CertificateRecordBindings.records(corpus,maskedSources)){Fact f=ConfirmedSubjectBindings.recordIdentity(record);if(f!=null&&f.subject().matches(".*[*＊×●_].*"))proofs.add(f);}
        if(decision.gaps().stream().anyMatch(g->"SCORING_TITLE_IDENTITY_REQUIRED".equals(g.code()))){
            Pattern maskedName=Pattern.compile("(?m)^\\s*(?:\\|\\s*)?姓[ \\t]*名[ \\t]*[|：:][ \\t]*([\\p{IsHan}*＊×●_·]{2,12})[ \\t]*[|]?[ \\t]*$");
            for(Source source:maskedSources){
                String quote=source.evidence().quote();
                if(!quote.contains("职称")||!quote.contains("查询"))continue;
                java.util.regex.Matcher found=maskedName.matcher(quote);
                if(!found.find())continue;
                String name=found.group(1);
                if(found.find()||!name.matches(".*[*＊×●_].*"))continue;
                proofs.add(new Fact("title-name:"+source.id(),"FIELD",name,"姓名",name,name,"","","UNRESOLVED",
                    Collections.singletonList(source.id()),linked("person",name)));
            }
        }
        for(Fact role:roles)for(Fact proof:proofs){String person=role.value(),owner="FIELD".equals(proof.kind())&&"姓名".equals(proof.field())?proof.subject():SubjectIdentity.person(proof);
            if(!compatible(person,owner)||!safe(role,corpus)||!safe(proof,corpus)||!sameDocument(role,proof,corpus)||role.sourceIds().stream().anyMatch(proof.sourceIds()::contains))continue;
            if(!certificateRelevant(rule,proof))continue;
            Set<String> ids=new HashSet<>(ConfirmedSubjectBindings.identifiers(role,corpus));ids.addAll(ConfirmedSubjectBindings.identifiers(proof,corpus));if(ids.size()>1)continue;
            List<Map<String,Object>> citations=new ArrayList<>();citations.addAll(citations(role,person,corpus));citations.addAll(citations(proof,owner,corpus));if(citations.size()<2)continue;
            boolean masked=!person.equals(owner);String number=str(proof.attributes(),"number");
            Map<String,Object> proposed=linked("kind","RELATION","subject",person,"field","同一人","rawValue",owner,"value",owner,"unit","","basis","SOURCE_PAIR_CONFIRMATION","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.toList(citations.stream().map(c->str(c,"sourceId")).distinct()),"attributes",linked("object",owner,"number",number));
            add(out,linked("type",masked?"MASKED_PERSON_IDENTITY":"PERSON_IDENTITY","claim","请确认任职表中的“"+person+"”与所引证明中的“"+owner+"”是否为同一人", "reasoning","已定位当前任职与对应姓名的证明来源，但现有身份标识不足以自动完成跨来源关联。确认仅关联这些来源，不代替证书、单位或社保条件核验。", "confidence",0.65,"risks",cn.bidcheck.compat.Java8Api.listOf(masked?"证明中的姓名含遮蔽字符":"姓名相同不单独证明人员相同"),"proposedFact",proposed,"evidence",citations,"unresolvedGapCodes",cn.bidcheck.compat.Java8Api.toList(decision.gaps().stream().map(Gap::code).filter(IDENTITY_GAPS::contains))),rule,corpus);
        }
    }
    private static boolean certificateRelevant(Rule rule,Fact proof){String expected=str(rule.parameters(),"category");if(cn.bidcheck.compat.Java8Api.isBlank(expected)||!proof.kind().equals("CERTIFICATE"))return true;String category=str(proof.attributes(),"category");return !cn.bidcheck.compat.Java8Api.isBlank(category)&&(CertificateEvidenceRecords.category(category).equals(CertificateEvidenceRecords.category(expected))||category.contains(expected)||expected.contains(category));}
    private static void employerCandidates(Rule rule,Decision decision,List<Fact> facts,EvidenceCorpus corpus,Map<String,Map<String,Object>> out){
        List<Fact> roles=cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->f.kind().equals("PERSON_ROLE")&&f.value().equals(rule.subject())&&safe(f,corpus)&&DomainBindings.currentRoleSource(f,corpus.byId)));
        if(roles.isEmpty())return;
        Map<String,Fact> companies=new LinkedHashMap<>();
        for(Fact f:facts){if(!safe(f,corpus)||!f.kind().equals("RELATION"))continue;String company=str(f.attributes(),"object");
            if(company.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$")&&(f.subject().equals(rule.subject())||DomainBindings.role(f.subject())))companies.putIfAbsent(company,f);}
        for(Fact role:roles)for(java.util.Map.Entry<java.lang.String, cn.bidcheck.verification.engine.VerificationTypes.Fact> entry:companies.entrySet()){List<Map<String,Object>> evidence=new ArrayList<>(citations(role,role.value(),corpus));evidence.addAll(citations(entry.getValue(),entry.getKey(),corpus));if(evidence.size()<2)continue;
            java.util.Map<java.lang.String, java.lang.Object> proposed=linked("kind","RELATION","subject",rule.subject(),"field","所属单位","rawValue",entry.getKey(),"value",entry.getKey(),"unit","","basis","PERSON_EMPLOYER_CONFIRMATION","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.toList(evidence.stream().map(e->str(e,"sourceId")).distinct()),"attributes",linked("object",entry.getKey()));
            add(out,linked("type","EMPLOYER_BINDING","claim","请确认“"+rule.subject()+"”在本项目适用的所属单位是否为“"+entry.getKey()+"”","reasoning","当前任职人员和成员单位均有原文，但现有分工或任职资料未唯一确定“本单位”的指代。确认后仍逐月核验该单位社保，不以成员分工替代缴费证明。","confidence",0.5,"risks",cn.bidcheck.compat.Java8Api.listOf("联合体成员资格不能单独证明劳动关系"),"proposedFact",proposed,"evidence",evidence,"unresolvedGapCodes",cn.bidcheck.compat.Java8Api.listOf("SOCIAL_EMPLOYER_CONFIRMATION_REQUIRED")),rule,corpus);
        }
    }
    private static boolean safe(Fact fact,EvidenceCorpus corpus){return !fact.sourceIds().isEmpty()&&fact.sourceIds().stream().allMatch(id->{Source s=corpus.byId.get(id);return s!=null&&!s.historical()&&!s.index()&&!s.scopeContext().blocked();});}
    private static boolean sameDocument(Fact a,Fact b,EvidenceCorpus corpus){Set<String> docs=new HashSet<>();a.sourceIds().forEach(id->docs.add(corpus.byId.get(id).evidence().documentId()));b.sourceIds().forEach(id->docs.add(corpus.byId.get(id).evidence().documentId()));return docs.size()==1;}
    private static boolean compatible(String name,String other){if(!name.matches("[\\p{IsHan}·]{2,12}")||cn.bidcheck.compat.Java8Api.isBlank(other)||DomainBindings.role(other))return false;if(name.equals(other))return true;String regex=Arrays.stream(other.split("",-1)).filter(s->!s.isEmpty()).map(s->"*＊×●_".contains(s)?"[\\p{IsHan}·]":Pattern.quote(s)).reduce("",String::concat);return other.matches(".*[*＊×●_].*")&&name.matches(regex);}
    private static List<Map<String,Object>> citations(Fact fact,String needle,EvidenceCorpus corpus){List<Map<String,Object>> result=new ArrayList<>();for(String id:fact.sourceIds()){Source s=corpus.byId.get(id);if(s==null)continue;String text=s.evidence().quote();int at=text.indexOf(needle);if(at<0)continue;int start=Math.max(0,at-180),end=Math.min(text.length(),at+needle.length()+240);result.add(linked("sourceId",id,"quote",text.substring(start,end)));}return result;}
}
