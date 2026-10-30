package cn.bidcheck.verification.engine;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A current, navigation-backed appointment's explicit proof attachment may bind its identity
 * card to a uniquely identified certificate. Name, proximity or birth date alone never do. */
final class PersonnelAttachmentIdentity {
    static final String VERSION="personnel-attachment-identity-1";
    private static final Pattern NAME=Pattern.compile("(?m)^[\\t ]*姓\\s*名[\\t :：]*([\\p{IsHan}·]{2,12})(?=\\s|$)");
    private static final Pattern ID=Pattern.compile("(?m)^[\\t ]*公民身份号码[\\t :：]*([0-9]{17}[0-9Xx])[\\t ]*\\r?$");
    private static final Pattern BIRTH=Pattern.compile("(?m)^[\\t ]*(?:出生|出生日期)[\\t :：]*([0-9]{4})[\\t ]*年[\\t ]*([0-9]{1,2})[\\t ]*月[\\t ]*([0-9]{1,2})[\\t ]*日[\\t ]*\\r?$");
    private PersonnelAttachmentIdentity(){}
    private record Card(String number,String birth,List<Map<String,Object>> fields){}
    private record Packet(String name,String anchor,List<Source> sources,Map<String,Object> appointmentProof,Card card){}
    private record AppointmentId(List<int[]> parts,int labelStart,int labelEnd){}

    static Map<String,Object> proof(Fact appointment,Fact certificate,EvidenceCorpus corpus){
        if(!certificate.kind().equals("CERTIFICATE")||!SubjectIdentity.person(appointment).equals(certificate.subject()))return Map.of();
        Packet packet=packet(appointment,corpus);if(packet==null)return Map.of();
        var record=CertificateRecordBindings.matching(certificate,corpus.byId);if(record==null||record.value("BIRTH_DATE").isBlank()||!record.value("BIRTH_DATE").equals(packet.card().birth()))return Map.of();
        if(record.sources().stream().anyMatch(s->!s.scopeContext().status().equals("CURRENT_BID_ATTACHMENT")||!s.scopeContext().anchor().equals(packet.anchor())))return Map.of();
        if(!record.value("PERSON_ID").isBlank()&&!record.value("PERSON_ID").equals(packet.card().number()))return Map.of();
        Set<String> versions=new HashSet<>();
        for(var other:CertificateRecordBindings.records(corpus,packet.sources()))if(other.owner().equals(packet.name())&&other.category().equals(record.category())){
            String number=other.value("CERTIFICATE_NUMBER");if(number.isBlank())number=other.value("REGISTRATION_NUMBER");
            versions.add(number+"|"+other.value("BIRTH_DATE")+"|"+other.value("PERSON_ID"));
        }
        if(versions.size()!=1||unresolvedCertificateOccurrence(packet,record,corpus))return Map.of();
        return linked("version",VERSION,"binding","CURRENT_APPOINTMENT_ID_CARD_AND_UNIQUE_CERTIFICATE_BIRTH_DATE_IN_EXPLICIT_ATTACHMENT",
            "person",packet.name(),"personIdentifierFingerprint",identity(packet.card().number()),"attachmentAnchor",packet.anchor(),
            "appointmentIdentity",packet.appointmentProof(),"identityCardFields",packet.card().fields(),"certificateRecord",CertificateRecordBindings.audit(record),
            "doesNotProveEmployment",true,"sameNameAloneAccepted",false);
    }

    /** Supplies the appointment's ID only after a matching identity card in its own proof packet. */
    static Map<String,Object> appointmentIdentity(Fact appointment,EvidenceCorpus corpus){
        Packet packet=packet(appointment,corpus);if(packet==null)return Map.of();
        return linked("version",VERSION,"field","CN_ID","valueFingerprint",identity(packet.card().number()),
            "sourceIds",packet.sources().stream().map(Source::id).toList(),"appointmentIdentity",packet.appointmentProof(),"identityCardFields",packet.card().fields());
    }

    private static Packet packet(Fact appointment,EvidenceCorpus corpus){
        if(!appointment.kind().equals("PERSON_ROLE")||!appointment.scope().equals("CURRENT_PROJECT")||!DomainBindings.currentRoleSource(appointment,corpus.byId))return null;
        if(new ProofVerifier().issue(appointment,corpus.byId)!=null||DomainBindings.issue(appointment,corpus.byId,List.of())!=null)return null;
        String name=SubjectIdentity.person(appointment);if(name.isBlank())return null;
        List<Source> roleSources=appointment.sourceIds().stream().map(corpus.byId::get).filter(Objects::nonNull).toList();if(roleSources.isEmpty())return null;
        Set<String> anchors=new HashSet<>();for(Source s:roleSources){if(!s.scopeContext().status().equals("CURRENT_BID_SECTION")||!s.scopeContext().anchor().endsWith(":cover-navigation-section-form-printed-page"))return null;anchors.add(s.scopeContext().anchor());}
        if(anchors.size()!=1)return null;String roleAnchor=anchors.iterator().next();var first=roleSources.get(0).evidence();
        Set<String> attachmentAnchors=new HashSet<>();List<Source> pack=new ArrayList<>();
        for(Source s:corpus.all)if(s.evidence().documentId().equals(first.documentId())&&s.scopeContext().status().equals("CURRENT_BID_ATTACHMENT")&&s.scopeContext().anchor().startsWith(roleAnchor+":proof-attachment:")){
            if(s.index()||s.historical()||s.scopeContext().blocked())return null;attachmentAnchors.add(s.scopeContext().anchor());pack.add(s);
        }
        if(attachmentAnchors.size()!=1)return null;
        Map<String,List<Source>> pages=new LinkedHashMap<>();for(Source s:pack)pages.computeIfAbsent(EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()),k->new ArrayList<>()).add(s);
        List<Card> cards=new ArrayList<>();
        for(var entry:pages.entrySet()){
            var page=corpus.pages.get(entry.getKey());if(page==null)continue;String text=Objects.toString(page.text(),"");
            if(!text.contains("公民身份号码")&&!Pattern.compile("(?m)^[\\t #]*身份证[\\t ]*$").matcher(text).find())continue;
            Matcher owner=NAME.matcher(text),id=ID.matcher(text),birth=BIRTH.matcher(text);if(!owner.find()||!owner.group(1).equals(name)||!id.find()||!birth.find())return null;
            String identifier=id.group(1).toUpperCase(Locale.ROOT),date=date(birth);if(!validId(identifier)||!date.equals(identifier.substring(6,10)+"-"+identifier.substring(10,12)+"-"+identifier.substring(12,14)))return null;
            List<Map<String,Object>> fields=new ArrayList<>();
            for(var f:Arrays.asList(CertificateRecordBindings.sourceField("NAME","姓名",name,text,owner.start(1),owner.end(1),entry.getValue()),CertificateRecordBindings.sourceField("PERSON_ID","公民身份号码",identifier,text,id.start(1),id.end(1),entry.getValue()),CertificateRecordBindings.sourceField("BIRTH_DATE","出生",date,text,birth.start(1),birth.end(3),entry.getValue()))){if(f==null)return null;fields.add(f.audit());}
            if(owner.find()||id.find()||birth.find())return null;cards.add(new Card(identifier,date,List.copyOf(fields)));
        }
        if(cards.isEmpty()||cards.stream().map(Card::number).distinct().count()!=1)return null;Card card=cards.get(0);
        var rolePage=corpus.pages.get(EvidenceCorpus.pageKey(first.documentId(),first.page(),first.locationType()));if(rolePage==null)return null;
        String text=rolePage.text();int end=text.length();Matcher history=Pattern.compile("(?m)^\\s*(?:主要工作经历|工作经历|工作履历|参加过的类似项目)").matcher(text);if(history.find())end=history.start();String prefix=text.substring(0,end);
        Matcher person=NAME.matcher(prefix);if(!person.find()||!person.group(1).equals(name)||person.find()||!prefix.contains("身份证号")||!prefix.contains("拟在本合同任职"))return null;
        AppointmentId appointmentId=appointmentId(prefix,card.number());if(appointmentId==null)return null;List<Map<String,Object>> parts=new ArrayList<>();
        List<Source> rolePageSources=corpus.all.stream().filter(s->s.evidence().documentId().equals(first.documentId())&&s.evidence().page()==first.page()&&s.evidence().locationType().equals(first.locationType())).toList();
        for(int[] span:appointmentId.parts()){var field=CertificateRecordBindings.sourceField("PERSON_ID_PART","身份证号",text.substring(span[0],span[1]),text,span[0],span[1],rolePageSources);if(field==null)return null;parts.add(field.audit());}
        var label=CertificateRecordBindings.sourceField("IDENTITY_FIELD_LABEL","身份证号",text.substring(appointmentId.labelStart(),appointmentId.labelEnd()),text,appointmentId.labelStart(),appointmentId.labelEnd(),rolePageSources);if(label==null)return null;
        Map<String,Object> proof=linked("assembly",parts.size()==1?"LITERAL_ID":"ORDERED_FRAGMENTS_IN_UNIQUE_CURRENT_APPOINTMENT_TABLE","fieldSpans",parts,"fieldLabelSpan",label.audit(),"identifierChecksumVerified",true,"scopeAnchor",roleAnchor,"sourceIds",appointment.sourceIds());
        return new Packet(name,attachmentAnchors.iterator().next(),List.copyOf(pack),proof,card);
    }

    /** OCR can split one appointment-table identity cell over the title/header/title lines.
     * Only that explicit band is reconstructed; arbitrary nearby digits or remark fields are not. */
    private static AppointmentId appointmentId(String text,String expected){
        Matcher labels=Pattern.compile("身份证号").matcher(text);if(!labels.find())return null;int labelStart=labels.start(),labelEnd=labels.end();if(labels.find())return null;
        List<AppointmentId> candidates=new ArrayList<>();
        Matcher literal=Pattern.compile("身份证号[\\t :：]*([0-9]{17}[0-9Xx])(?=\\s|$)").matcher(text);
        if(literal.find()&&literal.group(1).equalsIgnoreCase(expected))candidates.add(new AppointmentId(List.of(new int[]{literal.start(1),literal.end(1)}),labelStart,labelEnd));
        Pattern table=Pattern.compile("(?m)^[\\t ]*([\\p{IsHan}]{1,12})[\\t ]+([0-9]{8,17})[\\t ]*\\r?\\n([\\t ]*职[\\t ]*称[\\t ]+身份证号[\\t ]+拟在本合同任职[\\t ]+[\\p{IsHan}]{2,20}[\\t ]*)\\r?\\n[\\t ]*(?:(?:正高级|高级|中级|初级|助理)?(?:工程师|经济师|会计师|技术员|建造师|建筑师))[\\t ]+([0-9]{1,9}[Xx]?)[\\t ]*\\r?$");
        Matcher band=table.matcher(text);while(band.find()){
            if(band.group(1).matches(".*(?:号码|电话|备注|银行|编号|说明|证书|学历|年龄|出生|身份证|未提供|未明确).*"))continue;
            if((band.group(2)+band.group(4)).equalsIgnoreCase(expected))candidates.add(new AppointmentId(List.of(new int[]{band.start(2),band.end(2)},new int[]{band.start(4),band.end(4)}),band.start(3),band.end(3)));
        }
        return candidates.size()==1?candidates.get(0):null;
    }

    private static boolean unresolvedCertificateOccurrence(Packet packet,CertificateSourceComparison.Record selected,EvidenceCorpus corpus){
        Map<String,List<Source>> pages=new LinkedHashMap<>();for(Source s:packet.sources())pages.computeIfAbsent(EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()),key->new ArrayList<>()).add(s);
        for(var entry:pages.entrySet()){
            var page=corpus.pages.get(entry.getKey());if(page==null)return true;String text=Objects.toString(page.text(),""),category=selected.category();
            boolean family=category.contains("建造师")?text.contains("建造师")&&text.contains("注册证书"):
                category.contains("建筑师")?text.contains("建筑师")&&text.contains("注册证书"):
                category.contains("安全生产考核")?text.contains("安全生产考核合格证"):text.contains(category);
            if(!family||!text.contains(packet.name()))continue;
            var records=CertificateRecordBindings.records(corpus,entry.getValue());
            // A malformed competing record cannot disappear from the uniqueness test.
            if(records.isEmpty()||records.stream().anyMatch(r->!r.owner().equals(packet.name())))return true;
        }
        return false;
    }

    private static String date(Matcher m){try{return LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))).toString();}catch(Exception e){return "";}}
    private static boolean validId(String id){if(!id.matches("[0-9]{17}[0-9X]"))return false;int[] weight={7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};int sum=0;for(int i=0;i<17;i++)sum+=(id.charAt(i)-'0')*weight[i];return "10X98765432".charAt(sum%11)==id.charAt(17);}
}
