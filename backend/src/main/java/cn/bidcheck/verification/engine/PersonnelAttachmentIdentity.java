package cn.bidcheck.verification.engine;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A current, navigation-backed appointment's explicit proof attachment may bind its identity
 * card to a uniquely identified certificate. Name, proximity or birth date alone never do. */
final class PersonnelAttachmentIdentity {
    static final String VERSION="personnel-attachment-identity-2-exact-markdown-fields";
    // OCR transcriptions may retain Markdown list/emphasis/code delimiters. Match these
    // wrappers in place so captured field offsets still refer to the untouched original.
    private static final String FIELD_PREFIX="(?m)^[\\t ]*(?:[-*][\\t ]+)?(?:\\*\\*)?";
    private static final String FIELD_SEPARATOR="(?:\\*\\*)?[\\t :：]*(?:\\*\\*)?`?";
    private static final Pattern NAME=Pattern.compile(FIELD_PREFIX+"姓\\s*名"+FIELD_SEPARATOR+"([\\p{IsHan}·]{2,12})(?:`|\\*\\*)?(?=\\s|$)");
    private static final Pattern ID=Pattern.compile(FIELD_PREFIX+"公民身份号码"+FIELD_SEPARATOR+"([0-9]{17}[0-9Xx])`?(?:\\*\\*)?[\\t ]*\\r?$");
    private static final Pattern BIRTH=Pattern.compile(FIELD_PREFIX+"(?:出生|出生日期)"+FIELD_SEPARATOR+"([0-9]{4})[\\t ]*年[\\t ]*([0-9]{1,2})[\\t ]*月[\\t ]*([0-9]{1,2})[\\t ]*日`?(?:\\*\\*)?[\\t ]*\\r?$");
    private PersonnelAttachmentIdentity(){}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Card{
        private final String number;
        private final String birth;
        private final List<Map<String,Object>> fields;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Card(@com.fasterxml.jackson.annotation.JsonProperty("number") String number, @com.fasterxml.jackson.annotation.JsonProperty("birth") String birth, @com.fasterxml.jackson.annotation.JsonProperty("fields") List<Map<String,Object>> fields) { this.number = number; this.birth = birth; this.fields = fields; }
        public String number() { return number; }
        public String birth() { return birth; }
        public List<Map<String,Object>> fields() { return fields; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Card that = (Card) other; return java.util.Objects.equals(this.number, that.number) && java.util.Objects.equals(this.birth, that.birth) && java.util.Objects.equals(this.fields, that.fields); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.number); hash = 31 * hash + java.util.Objects.hashCode(this.birth); hash = 31 * hash + java.util.Objects.hashCode(this.fields); return hash; }
        @Override public String toString() { return "Card[" + "number=" + number + ", birth=" + birth + ", fields=" + fields + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Packet{
        private final String name;
        private final String anchor;
        private final List<Source> sources;
        private final Map<String,Object> appointmentProof;
        private final Card card;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Packet(@com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("anchor") String anchor, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<Source> sources, @com.fasterxml.jackson.annotation.JsonProperty("appointmentProof") Map<String,Object> appointmentProof, @com.fasterxml.jackson.annotation.JsonProperty("card") Card card) { this.name = name; this.anchor = anchor; this.sources = sources; this.appointmentProof = appointmentProof; this.card = card; }
        public String name() { return name; }
        public String anchor() { return anchor; }
        public List<Source> sources() { return sources; }
        public Map<String,Object> appointmentProof() { return appointmentProof; }
        public Card card() { return card; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Packet that = (Packet) other; return java.util.Objects.equals(this.name, that.name) && java.util.Objects.equals(this.anchor, that.anchor) && java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.appointmentProof, that.appointmentProof) && java.util.Objects.equals(this.card, that.card); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.name); hash = 31 * hash + java.util.Objects.hashCode(this.anchor); hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.appointmentProof); hash = 31 * hash + java.util.Objects.hashCode(this.card); return hash; }
        @Override public String toString() { return "Packet[" + "name=" + name + ", anchor=" + anchor + ", sources=" + sources + ", appointmentProof=" + appointmentProof + ", card=" + card + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class AppointmentId{
        private final List<int[]> parts;
        private final int labelStart;
        private final int labelEnd;
        @com.fasterxml.jackson.annotation.JsonCreator
        private AppointmentId(@com.fasterxml.jackson.annotation.JsonProperty("parts") List<int[]> parts, @com.fasterxml.jackson.annotation.JsonProperty("labelStart") int labelStart, @com.fasterxml.jackson.annotation.JsonProperty("labelEnd") int labelEnd) { this.parts = parts; this.labelStart = labelStart; this.labelEnd = labelEnd; }
        public List<int[]> parts() { return parts; }
        public int labelStart() { return labelStart; }
        public int labelEnd() { return labelEnd; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; AppointmentId that = (AppointmentId) other; return java.util.Objects.equals(this.parts, that.parts) && java.util.Objects.equals(this.labelStart, that.labelStart) && java.util.Objects.equals(this.labelEnd, that.labelEnd); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.parts); hash = 31 * hash + java.util.Objects.hashCode(this.labelStart); hash = 31 * hash + java.util.Objects.hashCode(this.labelEnd); return hash; }
        @Override public String toString() { return "AppointmentId[" + "parts=" + parts + ", labelStart=" + labelStart + ", labelEnd=" + labelEnd + "]"; }
}

    /** Corroboration is scored from independent source signals; explicit conflicts veto it. */
    static Map<String,Object> multiSignalProof(Fact appointment,Fact certificate,EvidenceCorpus corpus){
        cn.bidcheck.verification.engine.EvidenceIdentitySignals.Assessment assessment=EvidenceIdentitySignals.assess(appointment,certificate,corpus);
        return assessment.accepted()?assessment.proof():cn.bidcheck.compat.Java8Api.mapOf();
    }

    static Map<String,Object> proof(Fact appointment,Fact certificate,EvidenceCorpus corpus){
        if(!certificate.kind().equals("CERTIFICATE")||!SubjectIdentity.person(appointment).equals(certificate.subject()))return Collections.emptyMap();
        return proof(appointment,certificate,corpus,packet(appointment,corpus));
    }
    static List<Fact> employmentWitnesses(Rule rule,Fact appointment,EvidenceCorpus corpus){
        Packet packet=packet(appointment,corpus);if(packet==null)return Collections.emptyList();
        List<Fact> result=new ArrayList<>();
        for(Fact certificate:NativeCertificateFacts.employerCandidates(rule,corpus,packet.sources()))
            if(!proof(appointment,certificate,corpus,packet).isEmpty()&&!PersonEvidenceProfiles.explicitEmployment(certificate,corpus).isEmpty())result.add(certificate);
        return result;
    }
    private static Map<String,Object> proof(Fact appointment,Fact certificate,EvidenceCorpus corpus,Packet packet){
        if(packet==null||!packet.name().equals(certificate.subject()))return cn.bidcheck.compat.Java8Api.mapOf();
        cn.bidcheck.verification.engine.CertificateSourceComparison.Record record=identityRecord(certificate,corpus);if(record==null||cn.bidcheck.compat.Java8Api.isBlank(record.value("BIRTH_DATE"))||!record.value("BIRTH_DATE").equals(packet.card().birth()))return cn.bidcheck.compat.Java8Api.mapOf();
        if(record.sources().stream().anyMatch(s->!s.scopeContext().status().equals("CURRENT_BID_ATTACHMENT")||!s.scopeContext().anchor().equals(packet.anchor())))return cn.bidcheck.compat.Java8Api.mapOf();
        if(!cn.bidcheck.compat.Java8Api.isBlank(record.value("PERSON_ID"))&&!record.value("PERSON_ID").equals(packet.card().number()))return cn.bidcheck.compat.Java8Api.mapOf();
        Set<String> versions=new HashSet<>();
        for(cn.bidcheck.verification.engine.CertificateSourceComparison.Record other:CertificateRecordBindings.records(corpus,packet.sources()))if(other.owner().equals(packet.name())&&other.category().equals(record.category())){
            String number=other.value("CERTIFICATE_NUMBER");if(cn.bidcheck.compat.Java8Api.isBlank(number))number=other.value("REGISTRATION_NUMBER");
            versions.add(number+"|"+other.value("BIRTH_DATE")+"|"+other.value("PERSON_ID"));
        }
        if(versions.size()!=1||unresolvedCertificateOccurrence(packet,record,corpus))return cn.bidcheck.compat.Java8Api.mapOf();
        return linked("version",VERSION,"binding","CURRENT_APPOINTMENT_ID_CARD_AND_UNIQUE_CERTIFICATE_BIRTH_DATE_IN_EXPLICIT_ATTACHMENT",
            "person",packet.name(),"personIdentifierFingerprint",identity(packet.card().number()),"attachmentAnchor",packet.anchor(),
            "appointmentIdentity",packet.appointmentProof(),"identityCardFields",packet.card().fields(),"certificateRecord",CertificateRecordBindings.audit(record),
            "doesNotProveEmployment",true,"sameNameAloneAccepted",false);
    }

    private static CertificateSourceComparison.Record identityRecord(Fact fact,EvidenceCorpus corpus){
        CertificateSourceComparison.Record exact=CertificateRecordBindings.matching(fact,corpus.byId);if(exact!=null)return exact;
        // A coarse category can still identify one record, but cannot prove that record's qualification.
        // Re-read the complete original with every other claimed field unchanged; never promote the input fact.
        if(!"注册建筑师".equals(str(fact.attributes(),"category")))return null;
        CertificateSourceComparison.Record found=null;
        for(String category:Arrays.asList("一级注册建筑师","二级注册建筑师")){
            Map<String,Object> attributes=new LinkedHashMap<>(fact.attributes());attributes.put("category",category);
            Fact candidate=new Fact(fact.id(),fact.kind(),fact.subject(),fact.field(),fact.rawValue(),fact.value(),fact.unit(),fact.basis(),fact.scope(),fact.sourceIds(),attributes);
            CertificateSourceComparison.Record record=CertificateRecordBindings.matching(candidate,corpus.byId);
            if(record!=null){if(found!=null)return null;found=record;}
        }
        return found;
    }
    /** Supplies the appointment's ID only after a matching identity card in its own proof packet. */
    static Map<String,Object> appointmentIdentity(Fact appointment,EvidenceCorpus corpus){
        Packet packet=packet(appointment,corpus);if(packet==null)return cn.bidcheck.compat.Java8Api.mapOf();
        return linked("version",VERSION,"field","CN_ID","valueFingerprint",identity(packet.card().number()),
            "birthDate",packet.card().birth(),"sourceIds",cn.bidcheck.compat.Java8Api.toList(packet.sources().stream().map(Source::id)),"appointmentIdentity",packet.appointmentProof(),"identityCardFields",packet.card().fields());
    }

    /** An explicit but unresolved appointment ID must not be bypassed by a weaker name score. */
    static boolean unresolvedExplicitIdentity(Fact role,EvidenceCorpus corpus){
        if(ConfirmedSubjectBindings.identifiers(role,corpus).size()==1)return false;
        if(!appointmentIdentity(role,corpus).isEmpty())return false;
        for(String id:role.sourceIds()){Source source=corpus.byId.get(id);if(source==null)continue;
            cn.bidcheck.domain.Domain.Page page=corpus.pages.get(EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType()));
            if(page==null)continue;String text=page.text();Matcher history=Pattern.compile("(?m)^\\s*(?:主要工作经历|工作经历|工作履历|参加过的类似项目)").matcher(text);if(history.find())text=text.substring(0,history.start());
            if(text.contains("身份证号")&&text.contains("拟在本合同任职"))return true;
        }return false;
    }

    private static Packet packet(Fact appointment,EvidenceCorpus corpus){
        if(!appointment.kind().equals("PERSON_ROLE")||!appointment.scope().equals("CURRENT_PROJECT")||!DomainBindings.currentRoleSource(appointment,corpus.byId))return rejectedPacket(corpus,appointment,"NOT_CURRENT_APPOINTMENT");
        if(new ProofVerifier().issue(appointment,corpus.byId)!=null||DomainBindings.issue(appointment,corpus.byId,cn.bidcheck.compat.Java8Api.listOf())!=null)return rejectedPacket(corpus,appointment,"UNVERIFIED_ROLE_SOURCE");
        String name=SubjectIdentity.person(appointment);if(cn.bidcheck.compat.Java8Api.isBlank(name))return rejectedPacket(corpus,appointment,"MISSING_PERSON_NAME");
        List<Source> roleSources=cn.bidcheck.compat.Java8Api.toList(appointment.sourceIds().stream().map(corpus.byId::get).filter(Objects::nonNull));if(roleSources.isEmpty())return rejectedPacket(corpus,appointment,"MISSING_ROLE_SOURCE");
        Set<String> anchors=new HashSet<>();for(Source s:roleSources){if(!s.scopeContext().status().equals("CURRENT_BID_SECTION")||!s.scopeContext().anchor().endsWith(":cover-navigation-section-form-printed-page"))return rejectedPacket(corpus,appointment,"WRONG_ROLE_SECTION");anchors.add(s.scopeContext().anchor());}
        if(anchors.size()!=1)return rejectedPacket(corpus,appointment,"AMBIGUOUS_ROLE_SECTION");String roleAnchor=anchors.iterator().next();cn.bidcheck.domain.Domain.Evidence first=roleSources.get(0).evidence();
        Set<String> attachmentAnchors=new HashSet<>();List<Source> pack=new ArrayList<>();
        for(Source s:corpus.all)if(s.evidence().documentId().equals(first.documentId())&&s.scopeContext().status().equals("CURRENT_BID_ATTACHMENT")&&s.scopeContext().anchor().startsWith(roleAnchor+":proof-attachment:")){
            if(s.index()||s.historical()||s.scopeContext().blocked())return rejectedPacket(corpus,appointment,"UNUSABLE_ATTACHMENT");attachmentAnchors.add(s.scopeContext().anchor());pack.add(s);
        }
        if(attachmentAnchors.size()!=1)return rejectedPacket(corpus,appointment,"AMBIGUOUS_ATTACHMENT");
        Map<String,List<Source>> pages=new LinkedHashMap<>();for(Source s:pack)pages.computeIfAbsent(EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()),k->new ArrayList<>()).add(s);
        List<Card> cards=new ArrayList<>();
        for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source>> entry:pages.entrySet()){
            cn.bidcheck.domain.Domain.Page page=corpus.pages.get(entry.getKey());if(page==null)continue;String text=Objects.toString(page.text(),"");
            if(!text.contains("公民身份号码")&&!Pattern.compile("(?m)^[\\t #]*身份证[\\t ]*$").matcher(text).find())continue;
            Matcher owner=NAME.matcher(text),id=ID.matcher(text),birth=BIRTH.matcher(text);if(!owner.find()||!owner.group(1).equals(name)||!id.find()||!birth.find())return rejectedPacket(corpus,appointment,"CARD_FIELDS_UNREADABLE");
            String identifier=id.group(1).toUpperCase(Locale.ROOT),date=date(birth);if(!validId(identifier)||!date.equals(identifier.substring(6,10)+"-"+identifier.substring(10,12)+"-"+identifier.substring(12,14)))return rejectedPacket(corpus,appointment,"CARD_CHECKSUM_OR_BIRTH_CONFLICT");
            List<Map<String,Object>> fields=new ArrayList<>();
            for(cn.bidcheck.verification.engine.CertificateSourceComparison.Field f:Arrays.asList(CertificateRecordBindings.sourceField("NAME","姓名",name,text,owner.start(1),owner.end(1),entry.getValue()),CertificateRecordBindings.sourceField("PERSON_ID","公民身份号码",identifier,text,id.start(1),id.end(1),entry.getValue()),CertificateRecordBindings.sourceField("BIRTH_DATE","出生",date,text,birth.start(1),birth.end(3),entry.getValue()))){if(f==null)return rejectedPacket(corpus,appointment,"UNBOUND_CARD_FIELD");fields.add(f.audit());}
            if(owner.find()||id.find()||birth.find())return rejectedPacket(corpus,appointment,"AMBIGUOUS_CARD_FIELD");cards.add(new Card(identifier,date,cn.bidcheck.compat.Java8Api.listCopyOf(fields)));
        }
        for(SocialInsuranceEvidenceRecords.Record social:SocialInsuranceEvidenceRecords.records(pack)){
            if(!name.equals(social.person()))continue;
            SocialInsuranceEvidenceRecords.Field field=social.fields().get("identifier");
            if(field==null)continue;
            String identifier=clean(field.text()).toUpperCase(Locale.ROOT);
            if(!validId(identifier))return rejectedPacket(corpus,appointment,"SOCIAL_ID_INVALID");
            java.time.LocalDate birth;
            try{birth=java.time.LocalDate.of(Integer.parseInt(identifier.substring(6,10)),Integer.parseInt(identifier.substring(10,12)),Integer.parseInt(identifier.substring(12,14)));}catch(RuntimeException invalid){return rejectedPacket(corpus,appointment,"SOCIAL_BIRTH_INVALID");}
            cards.add(new Card(identifier,birth.toString(),Collections.singletonList(linked("binding","VALIDATED_SOCIAL_INSURANCE_ROW","recordId",social.id(),"field","identifier","sourceIds",cn.bidcheck.compat.Java8Api.toList(social.sources().stream().map(Source::id))))));
        }
        if(cards.isEmpty()||cards.stream().map(Card::number).distinct().count()!=1)return rejectedPacket(corpus,appointment,"IDENTIFIER_NOT_UNIQUE");Card card=cards.get(0);
        cn.bidcheck.domain.Domain.Page rolePage=corpus.pages.get(EvidenceCorpus.pageKey(first.documentId(),first.page(),first.locationType()));if(rolePage==null)return rejectedPacket(corpus,appointment,"APPOINTMENT_PAGE_MISSING");
        String text=rolePage.text();int end=text.length();Matcher history=Pattern.compile("(?m)^\\s*(?:主要工作经历|工作经历|工作履历|参加过的类似项目)").matcher(text);if(history.find())end=history.start();String prefix=text.substring(0,end);
        Matcher person=NAME.matcher(prefix);if(!person.find()||!person.group(1).equals(name)||person.find()||!prefix.contains("身份证号")||!prefix.contains("拟在本合同任职"))return rejectedPacket(corpus,appointment,"APPOINTMENT_IDENTITY_LABEL_AMBIGUOUS");
        AppointmentId appointmentId=appointmentId(prefix,card.number());if(appointmentId==null)return rejectedPacket(corpus,appointment,"APPOINTMENT_FRAGMENTS_CONFLICT");List<Map<String,Object>> parts=new ArrayList<>();
        List<Source> rolePageSources=cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().filter(s->s.evidence().documentId().equals(first.documentId())&&s.evidence().page()==first.page()&&s.evidence().locationType().equals(first.locationType())));
        for(int[] span:appointmentId.parts()){cn.bidcheck.verification.engine.CertificateSourceComparison.Field field=CertificateRecordBindings.sourceField("PERSON_ID_PART","身份证号",text.substring(span[0],span[1]),text,span[0],span[1],rolePageSources);if(field==null)return rejectedPacket(corpus,appointment,"UNBOUND_ID_FRAGMENT");parts.add(field.audit());}
        cn.bidcheck.verification.engine.CertificateSourceComparison.Field label=CertificateRecordBindings.sourceField("IDENTITY_FIELD_LABEL","身份证号",text.substring(appointmentId.labelStart(),appointmentId.labelEnd()),text,appointmentId.labelStart(),appointmentId.labelEnd(),rolePageSources);if(label==null)return rejectedPacket(corpus,appointment,"UNBOUND_ID_LABEL");
        Map<String,Object> proof=linked("assembly",parts.size()==1?"LITERAL_ID":"ORDERED_FRAGMENTS_IN_UNIQUE_CURRENT_APPOINTMENT_TABLE","fieldSpans",parts,"fieldLabelSpan",label.audit(),"identifierChecksumVerified",true,"scopeAnchor",roleAnchor,"sourceIds",appointment.sourceIds());
        return new Packet(name,attachmentAnchors.iterator().next(),cn.bidcheck.compat.Java8Api.listCopyOf(pack),proof,card);
    }

    private static Packet rejectedPacket(EvidenceCorpus corpus,Fact role,String reason){
        if(corpus.identityPacketRejections.add(role.id()+":"+reason))org.slf4j.LoggerFactory.getLogger(PersonnelAttachmentIdentity.class).info("bidcheck.identity_packet roleFactId={} sourceIds={} reason={}",role.id(),role.sourceIds(),reason);
        return null;
    }

    /** OCR can split one appointment-table identity cell over the title/header/title lines.
     * Only that explicit band is reconstructed; arbitrary nearby digits or remark fields are not. */
    private static AppointmentId appointmentId(String text,String expected){
        Matcher labels=Pattern.compile("身份证号").matcher(text);if(!labels.find())return null;int labelStart=labels.start(),labelEnd=labels.end();if(labels.find())return null;
        List<AppointmentId> candidates=new ArrayList<>();
        Matcher literal=Pattern.compile("身份证号[\\t :：]*([0-9]{17}[0-9Xx])(?=\\s|$)").matcher(text);
        if(literal.find()&&literal.group(1).equalsIgnoreCase(expected))candidates.add(new AppointmentId(cn.bidcheck.compat.Java8Api.listOf(new int[]{literal.start(1),literal.end(1)}),labelStart,labelEnd));
        Pattern table=Pattern.compile("(?m)^[\\t ]*([\\p{IsHan}]{1,12})[\\t ]+([0-9]{8,17})[\\t ]*\\r?\\n([\\t ]*职[\\t ]*称[\\t ]+身份证号[\\t ]+拟在本合同任职[\\t ]+[\\p{IsHan}]{2,20}[\\t ]*)\\r?\\n[\\t ]*(?:(?:正高级|高级|中级|初级|助理)?(?:工程师|经济师|会计师|技术员|建造师|建筑师))[\\t ]+([0-9]{1,9}[Xx]?)[\\t ]*\\r?$");
        Matcher band=table.matcher(text);while(band.find()){
            if(band.group(1).matches(".*(?:号码|电话|备注|银行|编号|说明|证书|学历|年龄|出生|身份证|未提供|未明确).*"))continue;
            if((band.group(2)+band.group(4)).equalsIgnoreCase(expected))candidates.add(new AppointmentId(cn.bidcheck.compat.Java8Api.listOf(new int[]{band.start(2),band.end(2)}, new int[]{band.start(4),band.end(4)}),band.start(3),band.end(3)));
        }
        // Empty professional-title cells are printed as '/' or blank. Their identity cell
        // still has one exact label band and two ordered fragments; accept only the full
        // number independently verified on this person's ID card in the same proof packet.
        Pattern emptyTitle=Pattern.compile("(?m)^[\\t ]*([0-9]{8,17})[\\t ]*\\r?\\n([\\t ]*职[\\t ]*称[\\t ]+(?:(?:/|[\\p{IsHan}]{1,12})[\\t ]+)?身份证号[\\t ]+拟在本合同任职[\\t ]+(?:[\\p{IsHan}]{2,20}|/)[\\t ]*)\\r?\\n[\\t ]*(?:/[\\t ]+)?([0-9]{1,9}[Xx]?)[\\t ]*\\r?$");
        Matcher empty=emptyTitle.matcher(text);while(empty.find())if((empty.group(1)+empty.group(3)).equalsIgnoreCase(expected))
            candidates.add(new AppointmentId(cn.bidcheck.compat.Java8Api.listOf(new int[]{empty.start(1),empty.end(1)}, new int[]{empty.start(3),empty.end(3)}),empty.start(2),empty.end(2)));
        return candidates.size()==1?candidates.get(0):null;
    }

    private static boolean unresolvedCertificateOccurrence(Packet packet,CertificateSourceComparison.Record selected,EvidenceCorpus corpus){
        Map<String,List<Source>> pages=new LinkedHashMap<>();for(Source s:packet.sources())pages.computeIfAbsent(EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()),key->new ArrayList<>()).add(s);
        for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source>> entry:pages.entrySet()){
            cn.bidcheck.domain.Domain.Page page=corpus.pages.get(entry.getKey());if(page==null)return true;String text=Objects.toString(page.text(),""),category=selected.category();
            // The query-table column "注册证书所在单位名称" describes an employer,
            // not another certificate occurrence. Keep every other registration-certificate
            // mention (including an unreadable competing card) in the uniqueness check.
            boolean registrationCertificate=Pattern.compile("注册证书(?![\\t ]*所在单位)").matcher(text).find();
            boolean family=category.contains("建造师")?text.contains("建造师")&&registrationCertificate:
                category.contains("建筑师")?text.contains("建筑师")&&registrationCertificate:
                category.contains("安全生产考核")?text.contains("安全生产考核合格证"):text.contains(category);
            if(!family||!text.contains(packet.name()))continue;
            java.util.List<cn.bidcheck.verification.engine.CertificateSourceComparison.Record> records=CertificateRecordBindings.records(corpus,entry.getValue());
            // A malformed competing record cannot disappear from the uniqueness test.
            if(records.isEmpty()||records.stream().anyMatch(r->!r.owner().equals(packet.name())))return true;
        }
        return false;
    }

    private static String date(Matcher m){try{return LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))).toString();}catch(Exception e){return "";}}
    private static boolean validId(String id){if(!id.matches("[0-9]{17}[0-9X]"))return false;int[] weight={7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};int sum=0;for(int i=0;i<17;i++)sum+=(id.charAt(i)-'0')*weight[i];return "10X98765432".charAt(sum%11)==id.charAt(17);}
}
