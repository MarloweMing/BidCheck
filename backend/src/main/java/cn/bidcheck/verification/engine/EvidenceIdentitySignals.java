package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Source-derived identity corroboration. Confidence never overrides an explicit conflict. */
final class EvidenceIdentitySignals {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(EvidenceIdentitySignals.class);
    static final String VERSION="person-identity-signals-2";
    private EvidenceIdentitySignals(){}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Assessment{
        private final boolean accepted;
        private final boolean candidate;
        private final int score;
        private final int threshold;
        private final List<String> vetoes;
        private final Map<String,Object> proof;
        @com.fasterxml.jackson.annotation.JsonCreator
        Assessment(@com.fasterxml.jackson.annotation.JsonProperty("accepted") boolean accepted, @com.fasterxml.jackson.annotation.JsonProperty("candidate") boolean candidate, @com.fasterxml.jackson.annotation.JsonProperty("score") int score, @com.fasterxml.jackson.annotation.JsonProperty("threshold") int threshold, @com.fasterxml.jackson.annotation.JsonProperty("vetoes") List<String> vetoes, @com.fasterxml.jackson.annotation.JsonProperty("proof") Map<String,Object> proof) { this.accepted = accepted; this.candidate = candidate; this.score = score; this.threshold = threshold; this.vetoes = vetoes; this.proof = proof; }
        public boolean accepted() { return accepted; }
        public boolean candidate() { return candidate; }
        public int score() { return score; }
        public int threshold() { return threshold; }
        public List<String> vetoes() { return vetoes; }
        public Map<String,Object> proof() { return proof; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Assessment that = (Assessment) other; return java.util.Objects.equals(this.accepted, that.accepted) && java.util.Objects.equals(this.candidate, that.candidate) && java.util.Objects.equals(this.score, that.score) && java.util.Objects.equals(this.threshold, that.threshold) && java.util.Objects.equals(this.vetoes, that.vetoes) && java.util.Objects.equals(this.proof, that.proof); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.accepted); hash = 31 * hash + java.util.Objects.hashCode(this.candidate); hash = 31 * hash + java.util.Objects.hashCode(this.score); hash = 31 * hash + java.util.Objects.hashCode(this.threshold); hash = 31 * hash + java.util.Objects.hashCode(this.vetoes); hash = 31 * hash + java.util.Objects.hashCode(this.proof); return hash; }
        @Override public String toString() { return "Assessment[" + "accepted=" + accepted + ", candidate=" + candidate + ", score=" + score + ", threshold=" + threshold + ", vetoes=" + vetoes + ", proof=" + proof + "]"; }
}
    static int threshold(){
        String configured=System.getProperty("bidcheck.personnel.identity-min-score",System.getenv("BIDCHECK_PERSONNEL_IDENTITY_MIN_SCORE"));
        try{return Math.max(75,Math.min(100,Integer.parseInt(Objects.toString(configured,"80"))));}catch(NumberFormatException ignored){return 80;}
    }
    static Assessment assess(Fact role,Fact dependent,EvidenceCorpus corpus){
        List<String> vetoes=new ArrayList<>();List<Map<String,Object>> signals=new ArrayList<>();
        String person=SubjectIdentity.person(role),owner=SubjectIdentity.person(dependent);
        if(!role.kind().equals("PERSON_ROLE")||!cn.bidcheck.compat.Java8Api.setOf("CERTIFICATE", "MONTHS", "RELATION").contains(dependent.kind()))return rejected("UNSUPPORTED_IDENTITY_PAIR");
        boolean exact=person.matches("[\\p{IsHan}·]{2,12}")&&person.equals(owner),masked=masked(person,owner);
        if(!exact&&!masked)return rejected("DIFFERENT_NAMES");
        List<Source> appointment=cn.bidcheck.compat.Java8Api.toList(role.sourceIds().stream().map(corpus.byId::get).filter(Objects::nonNull));
        List<Source> proof=cn.bidcheck.compat.Java8Api.toList(dependent.sourceIds().stream().map(corpus.byId::get).filter(Objects::nonNull));
        if(appointment.size()!=role.sourceIds().size()||proof.size()!=dependent.sourceIds().size()||appointment.isEmpty()||proof.isEmpty())return rejected("SOURCE_NOT_AVAILABLE");
        if(!DomainBindings.currentRoleSource(role,corpus.byId)||new ProofVerifier().issue(role,corpus.byId)!=null||DomainBindings.issue(role,corpus.byId,cn.bidcheck.compat.Java8Api.listOf())!=null)return rejected("CURRENT_APPOINTMENT_UNPROVEN");
        if(new ProofVerifier().issue(dependent,corpus.byId)!=null)return rejected("DEPENDENT_FACT_SOURCE_UNPROVEN");
        if(proof.stream().anyMatch(s->s.index()||s.historical()||s.scopeContext().blocked()))return rejected("HISTORICAL_OR_BLOCKED_DEPENDENT");
        Set<String> documents=new LinkedHashSet<>();appointment.forEach(s->documents.add(s.evidence().documentId()));proof.forEach(s->documents.add(s.evidence().documentId()));
        if(documents.size()!=1)return rejected("DIFFERENT_DOCUMENTS");
        Set<String> identifiers=new LinkedHashSet<>(ConfirmedSubjectBindings.identifiers(role,corpus));identifiers.addAll(ConfirmedSubjectBindings.identifiers(dependent,corpus));
        Map<String,Object> appointmentIdentity=PersonnelAttachmentIdentity.appointmentIdentity(role,corpus);
        if(!cn.bidcheck.compat.Java8Api.isBlank(str(appointmentIdentity,"valueFingerprint")))identifiers.add(str(appointmentIdentity,"valueFingerprint"));
        if(PersonnelAttachmentIdentity.unresolvedExplicitIdentity(role,corpus))vetoes.add("APPOINTMENT_EXPLICIT_IDENTITY_UNRESOLVED");
        String employer="";boolean unique=false;
        if(dependent.kind().equals("CERTIFICATE")){
            cn.bidcheck.verification.engine.CertificateSourceComparison.Record record=CertificateRecordBindings.matching(dependent,corpus.byId);if(record==null)return rejected("CERTIFICATE_FIELDS_UNBOUND");
            employer=record.value("EMPLOYER");String number=record.value("CERTIFICATE_NUMBER");if(cn.bidcheck.compat.Java8Api.isBlank(number))number=record.value("REGISTRATION_NUMBER");
            if(cn.bidcheck.compat.Java8Api.isBlank(number))return rejected("CERTIFICATE_NUMBER_UNPROVEN");
            if(!cn.bidcheck.compat.Java8Api.isBlank(record.value("PERSON_ID"))&&!record.value("PERSON_ID").matches("[0-9]{17}[0-9Xx]"))vetoes.add("CERTIFICATE_EXPLICIT_IDENTITY_UNRESOLVED");
            if(!cn.bidcheck.compat.Java8Api.isBlank(record.value("BIRTH_DATE"))&&!cn.bidcheck.compat.Java8Api.isBlank(str(appointmentIdentity,"birthDate"))&&!record.value("BIRTH_DATE").equals(str(appointmentIdentity,"birthDate")))vetoes.add("CONFLICTING_EXPLICIT_BIRTH_DATE");
            List<Source> packet=packet(role,corpus);Set<String> credentials=new HashSet<>();boolean incomplete=false;
            for(cn.bidcheck.verification.engine.CertificateSourceComparison.Record peer:CertificateRecordBindings.records(corpus,packet))if(peer.owner().equals(owner)&&peer.category().equals(record.category())){
                String id=peer.value("CERTIFICATE_NUMBER");if(cn.bidcheck.compat.Java8Api.isBlank(id))id=peer.value("REGISTRATION_NUMBER");if(cn.bidcheck.compat.Java8Api.isBlank(id))incomplete=true;else credentials.add(id);
                if(!cn.bidcheck.compat.Java8Api.isBlank(employer)&&!cn.bidcheck.compat.Java8Api.isBlank(peer.value("EMPLOYER"))&&!employer.equals(peer.value("EMPLOYER")))vetoes.add("COMPETING_EMPLOYER");
            }
            for(Source source:packet){String text=source.evidence().quote();if(text.contains(owner)&&text.contains(record.category())&&text.matches("(?s).*(?:证书编号|注册编号|证书号码)[:：\\s]*(?:识别不清|无法识别|未知|\\*{2,}).*"))incomplete=true;}
            unique=!incomplete&&credentials.size()==1&&credentials.contains(number);
            if(credentials.size()>1)vetoes.add("COMPETING_CREDENTIALS");
            if(incomplete)vetoes.add("UNREAD_COMPETING_CREDENTIAL");
            if(!cn.bidcheck.compat.Java8Api.isBlank(record.value("PERSON_ID"))&&record.value("PERSON_ID").matches("[0-9]{17}[0-9Xx]"))identifiers.add(identity(record.value("PERSON_ID").toUpperCase(Locale.ROOT)));
        }else if(dependent.kind().equals("MONTHS")){
            cn.bidcheck.verification.engine.SocialInsuranceEvidenceRecords.Record social=SocialInsuranceEvidenceRecords.matching(dependent,corpus.byId);
            if(social==null||SocialInsuranceEvidenceRecords.issue(dependent,corpus.byId)!=null)return rejected("SOCIAL_RECORD_UNBOUND");
            employer=social.employer();if(!cn.bidcheck.compat.Java8Api.isBlank(social.identifierFingerprint()))identifiers.add(social.identifierFingerprint());
            java.util.List<cn.bidcheck.verification.engine.SocialInsuranceEvidenceRecords.Record> peers=cn.bidcheck.compat.Java8Api.toList(SocialInsuranceEvidenceRecords.records(packet(role,corpus)).stream().filter(r->r.person().equals(owner)));
            Set<String> employers=new HashSet<>(),ids=new HashSet<>();peers.forEach(p->{if(!cn.bidcheck.compat.Java8Api.isBlank(p.employer()))employers.add(p.employer());if(!cn.bidcheck.compat.Java8Api.isBlank(p.identifierFingerprint()))ids.add(p.identifierFingerprint());});
            unique=!peers.isEmpty()&&employers.size()==1&&ids.size()<=1;
            if(ids.size()>1)vetoes.add("CONFLICTING_SOCIAL_IDENTIFIERS");
        }else{
            if(!DomainBindings.employment(dependent.field())||DomainBindings.issue(dependent,corpus.byId,cn.bidcheck.compat.Java8Api.listOf())!=null)return rejected("EMPLOYMENT_RELATION_UNBOUND");
            employer=str(dependent.attributes(),"object");unique=!cn.bidcheck.compat.Java8Api.isBlank(employer);
        }
        if(identifiers.size()>1)vetoes.add("CONFLICTING_EXPLICIT_IDENTIFIERS");
        String roleEmployer=str(role.attributes(),"employer");boolean independentEmployer=!cn.bidcheck.compat.Java8Api.isBlank(roleEmployer)&&!DomainBindings.role(roleEmployer)&&appointment.stream().anyMatch(s->s.evidence().quote().contains(roleEmployer));
        if(independentEmployer&&!cn.bidcheck.compat.Java8Api.isBlank(employer)&&!roleEmployer.equals(employer))vetoes.add("CONFLICTING_EXPLICIT_EMPLOYERS");
        Set<String> anchors=new HashSet<>();appointment.forEach(s->anchors.add(s.scopeContext().anchor()));
        boolean attachment=anchors.size()==1&&!anchors.contains("")&&proof.stream().allMatch(s->s.scopeContext().status().equals("CURRENT_BID_ATTACHMENT")&&s.scopeContext().anchor().startsWith(anchors.iterator().next()+":proof-attachment:"));
        int score=0;score+=signal(signals,exact?"EXACT_UNMASKED_NAME":"MASKED_NAME_CANDIDATE",exact?25:10,role.sourceIds(),dependent.sourceIds());
        score+=signal(signals,"SOURCE_VALIDATED_CURRENT_APPOINTMENT",25,role.sourceIds(),cn.bidcheck.compat.Java8Api.listOf());
        if(attachment)score+=signal(signals,"EXPLICIT_SAME_PROOF_ATTACHMENT",25,role.sourceIds(),dependent.sourceIds());
        if(independentEmployer&&roleEmployer.equals(employer))score+=signal(signals,"INDEPENDENT_MATCHING_EMPLOYER",15,role.sourceIds(),dependent.sourceIds());
        if(unique)score+=signal(signals,"UNIQUE_SOURCE_BOUND_PROOF",10,cn.bidcheck.compat.Java8Api.listOf(),dependent.sourceIds());
        int threshold=threshold();boolean accepted=exact&&vetoes.isEmpty()&&score>=threshold;
        boolean candidate=!accepted&&vetoes.stream().noneMatch(v->v.startsWith("CONFLICTING_")||v.equals("COMPETING_EMPLOYER"))&&(attachment||independentEmployer&&roleEmployer.equals(employer));
        Map<String,Object> audit=linked("version",VERSION,"binding","SOURCE_WEIGHTED_IDENTITY_CORROBORATION","score",score,"requiredScore",threshold,"accepted",accepted,"confirmationRequired",candidate,"signals",signals,"vetoes",vetoes,"appointmentFactId",role.id(),"proofFactId",dependent.id(),"appointmentSourceIds",role.sourceIds(),"proofSourceIds",dependent.sourceIds(),"doesNotEstablishGlobalIdentity",true,"doesNotProveEmployment",true,"sameNameAloneAccepted",false);
        log.info("bidcheck.person_identity version={} appointmentFact={} proofFact={} accepted={} candidate={} score={} threshold={} signals={} vetoes={} sources={}",VERSION,role.id(),dependent.id(),accepted,candidate,score,threshold,signals,vetoes,dependent.sourceIds());
        return new Assessment(accepted,candidate,score,threshold,cn.bidcheck.compat.Java8Api.listCopyOf(vetoes),audit);
    }
    private static Assessment rejected(String code){return new Assessment(false,false,0,threshold(),cn.bidcheck.compat.Java8Api.listOf(code),linked("version",VERSION,"accepted",false,"confirmationRequired",false,"vetoes",cn.bidcheck.compat.Java8Api.listOf(code)));}
    private static int signal(List<Map<String,Object>> signals,String name,int score,List<String> left,List<String> right){signals.add(linked("signal",name,"weight",score,"appointmentSourceIds",left,"proofSourceIds",right));return score;}
    private static boolean masked(String person,String owner){if(!person.matches("[\\p{IsHan}·]{2,12}")||!owner.matches("[\\p{IsHan}·*＊×●_]{2,12}")||!owner.matches(".*[*＊×●_].*"))return false;StringBuilder regex=new StringBuilder();owner.codePoints().forEach(c->{if("*＊×●_".indexOf(c)>=0)regex.append(".");else regex.append(Pattern.quote(new String(Character.toChars(c))));});return person.matches(regex.toString());}
    static List<Source> packet(Fact role,EvidenceCorpus corpus){Set<String> anchors=new HashSet<>(),docs=new HashSet<>();for(String id:role.sourceIds()){Source s=corpus.byId.get(id);if(s!=null){anchors.add(s.scopeContext().anchor());docs.add(s.evidence().documentId());}}return cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().filter(s->docs.contains(s.evidence().documentId())&&s.scopeContext().status().equals("CURRENT_BID_ATTACHMENT")&&anchors.stream().anyMatch(a->!cn.bidcheck.compat.Java8Api.isBlank(a)&&s.scopeContext().anchor().startsWith(a+":proof-attachment:"))));}
}
