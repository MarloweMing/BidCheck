package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.facts.TableStructure;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.*;

/** Layout metadata can establish a same-table-row binding. Unverified continuation pages cannot. */
final class LayoutBinding {
    static List<Map<String,Object>> sources(Fact fact,Map<String,Source> sources){return cn.bidcheck.compat.Java8Api.toList(fact.sourceIds().stream().map(sources::get).filter(Objects::nonNull).filter(s->!s.id().startsWith("V")).map(s->linked("id",s.id(),"documentHash",s.documentHash(),"documentVersion",s.documentVersion(),"sourceFingerprint",identity(s.id()+s.evidence().quote()),"evidence",linked("documentId",s.evidence().documentId(),"documentName",s.evidence().documentName(),"page",s.evidence().page(),"locationType",s.evidence().locationType(),"quote",s.evidence().quote(),"anchor",s.evidence().anchor()))));}
    static TableStructure.Result structure(Fact fact,List<Map<String,Object>> cells,Map<String,Source> sources){return TableStructure.analyze(SubjectIdentity.person(fact),strings(fact.attributes().get("cellIds")),cells,sources(fact,sources));}
    /** A blank appointment cell cannot invalidate a separately proved current section title.
     * Explicit competing table roles still veto the join; source scope is rederived by the server. */
    static boolean sectionAppointment(Fact fact,TableStructure.Result structure,Map<String,Source> sources){
        if(!fact.kind().equals("PERSON_ROLE")||!structure.valid()||structure.rows().stream().noneMatch(row->fact.value().equals(row.name())))return false;
        if(structure.rows().stream().anyMatch(row->{String role=cn.bidcheck.compat.Java8Api.strip(row.fields().getOrDefault("ROLE",""));return !cn.bidcheck.compat.Java8Api.isBlank(role)&&!cn.bidcheck.compat.Java8Api.setOf("/", "／", "-", "—").contains(role);}))return false;
        return fact.sourceIds().stream().map(sources::get).filter(Objects::nonNull).anyMatch(source->DocumentSubjectScope.appointment(source,fact));
    }
    static String structuredIssue(Fact f,TableStructure.Result result){
        if(!result.valid())return result.issue();
        if(f.kind().equals("MONTHS")){
            String person=str(f.attributes(),"person");if(cn.bidcheck.compat.Java8Api.isBlank(person)||!cn.bidcheck.compat.Java8Api.isBlank(f.subject())&&!f.subject().equals(person))return "STRUCTURE_SUBJECT_MISMATCH";
            Set<String> months=new HashSet<>();result.rows().forEach(r->months.addAll(r.months()));if(!months.containsAll(strings(f.attributes().get("months")))||strings(f.attributes().get("months")).isEmpty())return "STRUCTURE_MONTH_COVERAGE_UNPROVEN";
            String employer=str(f.attributes(),"employer");if(!cn.bidcheck.compat.Java8Api.isBlank(employer)&&result.rows().stream().anyMatch(r->!r.employer().equals(employer)))return "STRUCTURE_EMPLOYER_UNPROVEN";
        }else if(f.kind().equals("PERSON_ROLE")){
            if(result.rows().stream().noneMatch(r->PersonnelRoles.canonical(f.subject()).equals(PersonnelRoles.canonical(r.fields().getOrDefault("ROLE","")))))return "STRUCTURE_ROLE_UNPROVEN";
        }else if(f.kind().equals("CERTIFICATE")){
            boolean complete=result.rows().stream().anyMatch(r->{for(String key:cn.bidcheck.compat.Java8Api.listOf("category", "grade", "profession", "employer")){String value=str(f.attributes(),key);String field;
switch (key) {case "category":field = "CATEGORY"; break;case "grade":field = "GRADE"; break;case "profession":field = "PROFESSION"; break;default:field = "EMPLOYER"; break;}String actual=r.fields().getOrDefault(field,"");if(!cn.bidcheck.compat.Java8Api.isBlank(value)&&!(key.equals("grade")?CredentialGrades.normalize(value).equals(CredentialGrades.normalize(actual)):key.equals("category")?DomainBindings.sameCertificateCategory(value,actual):value.equals(actual)))return false;}
                String number=str(f.attributes(),"number"),field=str(f.attributes(),"numberField");if(!cn.bidcheck.compat.Java8Api.isBlank(number)&&(cn.bidcheck.compat.Java8Api.isBlank(field)||!number.equals(r.fields().get(field))))return false;
                String validity=str(f.attributes(),"validityField"),start=str(f.attributes(),"start"),end=str(f.attributes(),"end");return cn.bidcheck.compat.Java8Api.isBlank(start)&&cn.bidcheck.compat.Java8Api.isBlank(end)||!cn.bidcheck.compat.Java8Api.isBlank(validity)&&datePresent(r.fields().getOrDefault("DATE:"+validity,""),start)&&datePresent(r.fields().getOrDefault("DATE:"+validity,""),end);});
            if(!complete)return "STRUCTURE_CERTIFICATE_FIELDS_UNPROVEN";
        }else if(f.kind().equals("DATE_RANGE")){
            String field=str(f.attributes(),"field");if(result.rows().stream().noneMatch(r->!cn.bidcheck.compat.Java8Api.isBlank(field)&&datePresent(r.fields().getOrDefault("DATE:"+field,""),str(f.attributes(),"start"))&&datePresent(r.fields().getOrDefault("DATE:"+field,""),str(f.attributes(),"end"))))return "STRUCTURE_DATE_RANGE_UNPROVEN";
        }
        return null;
    }
    static boolean amount(Fact fact,List<Map<String,Object>> cells,Map<String,Source> sources){
        List<String> ids=strings(fact.attributes().get("cellIds"));if(ids.isEmpty())return false;
        List<Map<String,Object>> cited=cn.bidcheck.compat.Java8Api.toList(cells.stream().filter(c->ids.contains(cellId(c))).filter(c->sourceMatches(c,fact,sources)));
        for(Map<String,Object> value:cited){if(!numberPresent(str(value,"text"),fact.value()))continue;String table=str(value,"tableId");Object row=value.get("row");if(cn.bidcheck.compat.Java8Api.isBlank(table)||row==null)continue;
            boolean label=contains(str(value,"headerText"),fact.basis())||cited.stream().anyMatch(c->sameTableRow(value,c)&&contains(str(c,"text"),fact.basis()));
            if(label&&(!cn.bidcheck.compat.Java8Api.isBlank(fact.unit())?contains(str(value,"text")+str(value,"headerText"),fact.unit())||cited.stream().anyMatch(c->sameTableRow(value,c)&&contains(str(c,"text"),fact.unit())):true))return true;
        }return false;
    }
    private static boolean sameTableRow(Map<String,Object>a,Map<String,Object>b){return str(a,"documentId").equals(str(b,"documentId"))&&str(a,"tableId").equals(str(b,"tableId"))&&Objects.equals(a.get("row"),b.get("row"))&&Objects.equals(a.get("location"),b.get("location"));}
    static String cellId(Map<String,Object> c){return cn.bidcheck.compat.Java8Api.isBlank(str(c,"cellId"))?str(c,"tableCellId"):str(c,"cellId");}
    private static boolean sourceMatches(Map<String,Object> cell,Fact f,Map<String,Source> sources){String text=str(cell,"text");if(cn.bidcheck.compat.Java8Api.isBlank(text)||cn.bidcheck.compat.Java8Api.isBlank(str(cell,"spanId")))return false;return f.sourceIds().stream().map(sources::get).filter(Objects::nonNull).anyMatch(s->s.evidence().documentId().equals(str(cell,"documentId"))&&s.evidence().page()==RunSession.number(cell.get("location"),RunSession.number(cell.get("page"),-1))&&contains(s.evidence().quote(),text));}
}
