package cn.bidcheck.verification.engine;

import java.time.LocalDate;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.*;

/** The closed personnel schema consumes the same typed original records as screenshot comparison. */
final class PersonnelCredentialProof {
    static final String VERSION="personnel-credential-proof-1-typed-source-record";
    /** This object is never deserialized or reconstructed from a proof map. */
    static final class Selection {
        private final Rule rule;
        private final RuleEvaluator owner;
        private final Decision decision;
        private final List<CertificateSourceComparison.Record> records;
        private final List<Fact> identityFacts;
        private final List<Source> sourceSnapshot;
        private final Map<String,cn.bidcheck.domain.Domain.Page> pageSnapshot;
        private final String ruleSnapshot,factSnapshot;
        private Selection(Rule rule,RuleEvaluator owner,Decision decision,List<CertificateSourceComparison.Record> records,List<Fact> identityFacts){
            this.rule=rule;this.owner=owner;this.decision=decision;this.records=cn.bidcheck.compat.Java8Api.toList(records.stream().map(r->new CertificateSourceComparison.Record(r.id(),r.side(),r.category(),r.owner(),cn.bidcheck.compat.Java8Api.listCopyOf(r.sources()),cn.bidcheck.compat.Java8Api.mapCopyOf(r.fields()),cn.bidcheck.compat.Java8Api.listCopyOf(r.issues()))));this.identityFacts=cn.bidcheck.compat.Java8Api.listCopyOf(identityFacts);
            this.sourceSnapshot=cn.bidcheck.compat.Java8Api.listCopyOf(owner.corpus.all);this.pageSnapshot=cn.bidcheck.compat.Java8Api.mapCopyOf(owner.corpus.pages);this.ruleSnapshot=rule.toString();this.factSnapshot=owner.facts.toString();
        }
        boolean authorizes(Rule rule,RuleEvaluator evaluator,Decision decision){return this.decision==decision&&current(rule,evaluator);}
        boolean current(Rule rule,RuleEvaluator evaluator){return this.rule==rule&&owner==evaluator
            &&ruleSnapshot.equals(rule.toString())&&factSnapshot.equals(evaluator.facts.toString())&&sourceSnapshot.equals(evaluator.corpus.all)&&pageSnapshot.equals(evaluator.corpus.pages)
            &&sourceSnapshot.stream().allMatch(s->s.equals(evaluator.corpus.byId.get(s.id())))
            &&coversSubjects(rule,evaluator,identityFacts);}
        List<Fact> identityFacts(){return identityFacts;}
        List<CertificateSourceComparison.Record> records(){return records;}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class SelectedEvaluation{
        private final Decision decision;
        private final Selection selection;
        @com.fasterxml.jackson.annotation.JsonCreator
        SelectedEvaluation(@com.fasterxml.jackson.annotation.JsonProperty("decision") Decision decision, @com.fasterxml.jackson.annotation.JsonProperty("selection") Selection selection) { this.decision = decision; this.selection = selection; }
        public Decision decision() { return decision; }
        public Selection selection() { return selection; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; SelectedEvaluation that = (SelectedEvaluation) other; return java.util.Objects.equals(this.decision, that.decision) && java.util.Objects.equals(this.selection, that.selection); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.decision); hash = 31 * hash + java.util.Objects.hashCode(this.selection); return hash; }
        @Override public String toString() { return "SelectedEvaluation[" + "decision=" + decision + ", selection=" + selection + "]"; }
}
    private PersonnelCredentialProof(){}
    static boolean applies(Rule rule){return cn.bidcheck.compat.Java8Api.setOf("CERTIFICATE", "VALIDITY").contains(rule.predicate())&&!cn.bidcheck.compat.Java8Api.isBlank(str(rule.parameters(),"personnelCredentialKind"));}
    /** Typed fields use requiredGrade; validate the complete credential clause before adapting that name. */
    static boolean sourceGradeBound(Rule rule){
        if(!applies(rule)||!rule.predicate().equals("CERTIFICATE"))return false;
        String quote=clean(rule.sourceQuote()).replace('壹','一').replace('贰','二'),kind=str(rule.parameters(),"personnelCredentialKind");
        if(kind.equals("SAFETY")){
            List<String> allowed=strings(rule.parameters().get("allowedSafetyClasses"));
            return str(rule.parameters(),"category").equals("安全生产考核合格证")&&!allowed.isEmpty()&&allowed.stream().allMatch(v->cn.bidcheck.compat.Java8Api.setOf("A", "B", "C", "C1", "C2", "C3").contains(v)&&quote.matches(".*安全生产考核合格证(?:书)?[（(]"+v+"(?:类|证)[）)].*"));
        }
        if(!kind.equals("REGISTERED_BUILDER"))return false;
        String grade=str(rule.parameters(),"requiredGrade").replace('壹','一').replace('贰','二'),profession=str(rule.parameters(),"requiredProfession"),category=str(rule.parameters(),"category");
        if(!cn.bidcheck.compat.Java8Api.setOf("一级", "二级").contains(grade)||!category.equals("注册建造师执业资格证书")||cn.bidcheck.compat.Java8Api.isBlank(profession))return false;
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("([一二]级)(含以上|及以上|[（(]含[）)]以上)?(注册建造师执业资格证书)[（(]([^（）()]+)专业[）)]").matcher(quote);int count=0;boolean agrees=false;
        while(match.find()){count++;agrees=grade.equals(match.group(1))&&profession.equals(match.group(4))&&Boolean.TRUE.equals(rule.parameters().get("gradeInclusive"))==(match.group(2)!=null);}
        return count==1&&agrees;
    }
    static Decision evaluate(Rule rule,RuleEvaluator evaluator){return evaluateSelected(rule,evaluator).decision();}
    static SelectedEvaluation evaluateSelected(Rule rule,RuleEvaluator evaluator){
        List<CertificateSourceComparison.Record> selected=new ArrayList<>();List<Fact> identityFacts=new ArrayList<>();
        Decision decision=evaluate(rule,evaluator,selected,identityFacts);
        Selection selection=cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(decision.status())&&!selected.isEmpty()
            &&coversSubjects(rule,evaluator,identityFacts)
            ?new Selection(rule,evaluator,decision,selected,identityFacts):null;
        return new SelectedEvaluation(decision,selection);
    }
    private static Decision evaluate(Rule rule,RuleEvaluator evaluator,List<CertificateSourceComparison.Record> selected,List<Fact> identityFacts){
        List<String> people=evaluator.resolvedSubjects(rule);if(people.isEmpty()||people.stream().anyMatch(DomainBindings::role))return evaluator.unknown(rule,"SUBJECT_RELATION_UNPROVEN","本次岗位尚未绑定明确人员，不能使用历史项目或同名证照",cn.bidcheck.compat.Java8Api.listOf());
        String kind=str(rule.parameters(),"personnelCredentialKind"),quote=clean(rule.sourceQuote());
        if(!kind.equals("REGISTERED_BUILDER")&&!kind.equals("SAFETY")||kind.equals("REGISTERED_BUILDER")&&!quote.contains("注册建造师")||kind.equals("SAFETY")&&!quote.contains("安全生产考核合格证"))return evaluator.unknown(rule,"REQUIREMENT_CREDENTIAL_SCHEMA_UNPROVEN","证照执行字段未对应原要求",cn.bidcheck.compat.Java8Api.listOf());
        List<Decision> decisions=new ArrayList<>();List<CertificateSourceComparison.Record> all=evaluator.personnelCredentialRecords();
        for(String person:people)decisions.add(person(rule,person,kind,all,evaluator,selected,identityFacts));return decisions.size()==1?decisions.get(0):RuleEvaluator.combine(rule.id(),rule.description(),"ALL",decisions);
    }
    private static Decision person(Rule rule,String person,String kind,List<CertificateSourceComparison.Record> all,RuleEvaluator evaluator,List<CertificateSourceComparison.Record> selected,List<Fact> identityFacts){
        List<CertificateSourceComparison.Record> records=cn.bidcheck.compat.Java8Api.toList(all.stream().filter(r->r.side().equals("CERTIFICATE")&&ConfirmedSubjectBindings.recordMatches(person,r,evaluator)).filter(r->kind.equals("SAFETY")?r.category().startsWith("安全生产考核合格证书"):r.category().contains("注册建造师")));
        Map<String,Object> proof=linked("executor",VERSION,"subject",person,"credentialKind",kind,"requirementQuote",rule.sourceQuote(),"records",cn.bidcheck.compat.Java8Api.toList(records.stream().map(CertificateSourceComparison.Record::audit)));
        if(records.size()!=1||!records.get(0).issues().isEmpty())return evaluator.unknown(rule,"CERTIFICATE_SELECTION_REQUIRED","需唯一适用证照及明确字段；缺少记录或多版本时不择优拼接",cn.bidcheck.compat.Java8Api.listOf(),proof);
        cn.bidcheck.verification.engine.CertificateSourceComparison.Record record=records.get(0);cn.bidcheck.verification.engine.CertificateSourceComparison.Field number=record.fields().get("CERTIFICATE_NUMBER");if(number==null)number=record.fields().get("REGISTRATION_NUMBER");
        if(!sourceFieldsBound(record,evaluator.corpus))return evaluator.unknown(rule,"CERTIFICATE_FIELD_SOURCE_MISMATCH","证照字段与当前不可变原文范围不一致，需重新读取原始记录",cn.bidcheck.compat.Java8Api.listOf(),proof);
        if(number==null)return evaluator.unknown(rule,"CERTIFICATE_IDENTITY_REQUIRED","证照缺少明确类型的编号",cn.bidcheck.compat.Java8Api.listOf(),proof);
        Fact identity=ConfirmedSubjectBindings.recordIdentity(record);
        String sourceIssue=new ProofVerifier().issue(identity,evaluator.corpus.byId);if(sourceIssue==null)sourceIssue=DomainBindings.issue(identity,evaluator.corpus.byId,cn.bidcheck.compat.Java8Api.listOf());
        if(sourceIssue!=null)return evaluator.unknown(rule,sourceIssue,"所选证件的原始字段与来源绑定尚未通过复核",cn.bidcheck.compat.Java8Api.listOf(),proof);
        cn.bidcheck.verification.engine.SubjectIdentity.Problem identityIssue=evaluator.identities.issue(rule,cn.bidcheck.compat.Java8Api.listOf(identity));if(identityIssue!=null)return evaluator.unknown(rule,identityIssue.code(),identityIssue.reason(),cn.bidcheck.compat.Java8Api.listOf(),proof);
        selected.add(record);identityFacts.add(identity);
        proof.put("identityBindings",evaluator.identities.bindings(cn.bidcheck.compat.Java8Api.listOf(identity)));
        List<cn.bidcheck.domain.Domain.Evidence> evidence=cn.bidcheck.compat.Java8Api.toList(record.fields().values().stream().map(CertificateSourceComparison.Field::evidence).distinct());
        if(rule.predicate().equals("VALIDITY")){
            String requiredProfession=str(rule.parameters(),"requiredProfession");
            if(kind.equals("REGISTERED_BUILDER")&&(cn.bidcheck.compat.Java8Api.isBlank(requiredProfession)||!clean(rule.sourceQuote()).contains(requiredProfession)))return evaluator.unknown(rule,"REQUIREMENT_CREDENTIAL_SCHEMA_UNPROVEN","证照有效期适用专业未对应原要求，不能选择其他专业的有效期",cn.bidcheck.compat.Java8Api.listOf(),proof);
            LocalDate reference=evaluator.referenceDate(rule);if(reference==null)return evaluator.unknown(rule,"REFERENCE_TIME_UNCONFIRMED","证照有效性需要已核对原文的招标参考日期",cn.bidcheck.compat.Java8Api.listOf(),proof);
            String selectedProfession=selectedProfession(record,requiredProfession);
            String canonicalRequired=requiredProfession.equals("房屋建筑工程")?"建筑工程":requiredProfession;
            if(kind.equals("REGISTERED_BUILDER")&&cn.bidcheck.compat.Java8Api.isBlank(selectedProfession)&&!record.value("PROFESSION").equals(canonicalRequired))
                return evaluator.unknown(rule,"CERTIFICATE_PROFESSION_REQUIRED","所选证书未明确记载要求的注册专业，不能借另一专业或通用字段的日期证明",cn.bidcheck.compat.Java8Api.listOf(),proof);
            String suffix=cn.bidcheck.compat.Java8Api.isBlank(selectedProfession)?"":":"+selectedProfession;
            LocalDate start=date(record.value("VALIDITY_START"+suffix)),end=date(record.value("VALIDITY_END"+suffix));
            proof.putAll(linked("referenceDate",reference.toString(),"start",record.value("VALIDITY_START"+suffix),"end",record.value("VALIDITY_END"+suffix),"selectedProfession",selectedProfession,"certificateNumber",number.value(),"proofScope","ONE_CREDENTIAL_VALIDITY_AT_TENDER_EVENT"));
            CertificateValidityDates.Result dates=CertificateValidityDates.record(record,suffix,reference);proof.put("dateSemantics",dates.proof());
            if(!dates.complete())return evaluator.unknown(rule,dates.code(),dates.reason(),cn.bidcheck.compat.Java8Api.listOf(),proof);
            boolean valid=Boolean.TRUE.equals(dates.valid());
            boolean useDeclared=record.sources().stream().anyMatch(s->s.evidence().quote().contains("使用有效期")||s.evidence().quote().contains("使用期限"));
            if(useDeclared||record.fields().containsKey("VALIDITY_USE_START")||record.fields().containsKey("VALIDITY_USE_END")){LocalDate useStart=date(record.value("VALIDITY_USE_START")),useEnd=date(record.value("VALIDITY_USE_END"));proof.put("documentUsePeriod",linked("start",record.value("VALIDITY_USE_START"),"end",record.value("VALIDITY_USE_END"),"sourceDeclaresUseLimit",useDeclared));if(useStart==null||useEnd==null||useEnd.isBefore(useStart))return evaluator.unknown(rule,"VALIDITY_RANGE_REQUIRED","证书另有使用有效期但字段不完整，不能用专业注册期代替",cn.bidcheck.compat.Java8Api.listOf(),proof);valid&=!reference.isBefore(useStart)&&!reference.isAfter(useEnd);}
            return new Decision(rule.id(),rule.description(),valid?"PASS":"FAIL",valid?"本次人员同一证照、要求专业及明确的使用有效期覆盖已确认招标时点":"本次人员同一证照适用的有效期未覆盖已确认招标时点",evidence,cn.bidcheck.compat.Java8Api.listOf(),proof);
        }
        if(kind.equals("SAFETY")){
            List<String> allowed=strings(rule.parameters().get("allowedSafetyClasses"));if(allowed.isEmpty()||allowed.stream().anyMatch(v->!clean(rule.sourceQuote()).matches(".*[（(]"+java.util.regex.Pattern.quote(v)+"(?:类|证)[）)].*")))return evaluator.unknown(rule,"REQUIREMENT_CREDENTIAL_SCHEMA_UNPROVEN","安全证细类未对应条款明确的允许范围",cn.bidcheck.compat.Java8Api.listOf(),proof);
            String actual=record.category().replace("安全生产考核合格证书(","").replace(")","");proof.putAll(linked("allowedSafetyClasses",allowed,"actualSafetyClass",actual,"proofScope","SAFETY_CERTIFICATE_CLASS_ONLY"));
            if("C2".equals(actual)&&allowed.contains("C")&&allowed.contains("C3")&&!allowed.contains("C2"))
                return new Decision(rule.id(),rule.description(),"FAIL","证书是 C2，招标要求 C 或 C3",evidence,cn.bidcheck.compat.Java8Api.listOf(),proof);
            if(!allowed.contains(actual))return evaluator.unknown(rule,"CREDENTIAL_SUBTYPE_APPLICABILITY_REQUIRED","已读取安全证细类，但与条款列明类别未直接对应，不能把C2自动当作C3或笼统C类通过",cn.bidcheck.compat.Java8Api.listOf(),proof);
        }else{
            String grade=str(rule.parameters(),"requiredGrade").replace('壹','一').replace('贰','二'),profession=str(rule.parameters(),"requiredProfession");
            if(!cn.bidcheck.compat.Java8Api.setOf("一级", "二级").contains(grade)||!clean(rule.sourceQuote()).replace('壹','一').replace('贰','二').contains(grade)||!clean(rule.sourceQuote()).contains(profession))return evaluator.unknown(rule,"REQUIREMENT_CREDENTIAL_SCHEMA_UNPROVEN","等级或专业未对应原要求",cn.bidcheck.compat.Java8Api.listOf(),proof);
            boolean inclusive=Boolean.TRUE.equals(rule.parameters().get("gradeInclusive"));
            if(inclusive&&!clean(rule.sourceQuote()).replace('壹','一').replace('贰','二').matches(".*"+grade+"(?:含以上|及以上|[（(]含[）)]以上).*"))return evaluator.unknown(rule,"REQUIREMENT_CREDENTIAL_SCHEMA_UNPROVEN","允许高等级替代的范围未对应原文，不能仅采用执行参数",cn.bidcheck.compat.Java8Api.listOf(),proof);
            String actualGrade=record.category().substring(0,2),selectedProfessionValue=selectedProfession(record,profession),actualProfession=cn.bidcheck.compat.Java8Api.isBlank(selectedProfessionValue)?record.value("PROFESSION"):selectedProfessionValue;boolean gradeMeets=grade.equals(actualGrade)||inclusive&&grade.equals("二级")&&actualGrade.equals("一级");
            boolean professionMeets=actualProfession.equals(profession)||profession.equals("房屋建筑工程")&&actualProfession.equals("建筑工程");
            proof.putAll(linked("requiredGrade",grade,"actualGrade",actualGrade,"requiredProfession",profession,"actualProfession",actualProfession,"proofScope","REGISTERED_BUILDER_GRADE_AND_PROFESSION_ONLY"));
            if(cn.bidcheck.compat.Java8Api.isBlank(actualProfession))return evaluator.unknown(rule,"CERTIFICATE_FIELD_REQUIRED","注册建造师专业字段尚未取得",cn.bidcheck.compat.Java8Api.listOf(),proof);
            if(!gradeMeets||!professionMeets)return new Decision(rule.id(),rule.description(),"FAIL","本次人员注册证书明示等级或专业与要求不同",evidence,cn.bidcheck.compat.Java8Api.listOf(),proof);
        }
        return new Decision(rule.id(),rule.description(),"PASS","本次人员已绑定证照的指定类别、等级或专业满足该原子要求；有效期与共同证明分别核验",evidence,cn.bidcheck.compat.Java8Api.listOf(),proof);
    }
    private static String selectedProfession(CertificateSourceComparison.Record record,String required){String canonical=required.equals("房屋建筑工程")?"建筑工程":required;return record.fields().containsKey("PROFESSION:"+canonical)?canonical:"";}
    private static boolean coversSubjects(Rule rule,RuleEvaluator evaluator,List<Fact> identities){List<String> subjects=evaluator.resolvedSubjects(rule);return identities.size()==subjects.size()&&subjects.stream().allMatch(person->identities.stream().anyMatch(f->f.subject().equals(person)||ConfirmedSubjectBindings.matches(person,f,evaluator.facts,evaluator.corpus)));}
    private static boolean sourceFieldsBound(CertificateSourceComparison.Record record,EvidenceCorpus corpus){
        for(cn.bidcheck.verification.engine.CertificateSourceComparison.Field field:record.fields().values()){
            Source source=corpus.byId.get(field.sourceId());if(source==null)return false;cn.bidcheck.domain.Domain.Evidence origin=source.evidence();cn.bidcheck.domain.Domain.Evidence evidence=field.evidence();
            if(!origin.documentId().equals(evidence.documentId())||origin.page()!=evidence.page()||!origin.locationType().equals(evidence.locationType()))return false;
            java.util.regex.Matcher sourceRange=java.util.regex.Pattern.compile("chars:(\\d+)-(\\d+)").matcher(origin.anchor());java.util.regex.Matcher fieldRange=java.util.regex.Pattern.compile("chars:(\\d+)-(\\d+)").matcher(evidence.anchor());
            if(!sourceRange.matches()||!fieldRange.matches())return false;int from=Integer.parseInt(fieldRange.group(1))-Integer.parseInt(sourceRange.group(1)),to=Integer.parseInt(fieldRange.group(2))-Integer.parseInt(sourceRange.group(1));
            if(from<0||to<=from||to>origin.quote().length()||!field.raw().equals(evidence.quote())||!origin.quote().substring(from,to).equals(field.raw()))return false;
        }return !record.fields().isEmpty();
    }
}
