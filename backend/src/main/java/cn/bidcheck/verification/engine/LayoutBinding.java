package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.facts.TableStructure;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.*;

/** Layout metadata can establish a same-table-row binding. Unverified continuation pages cannot. */
final class LayoutBinding {
    static List<Map<String,Object>> sources(Fact fact,Map<String,Source> sources){return fact.sourceIds().stream().map(sources::get).filter(Objects::nonNull).filter(s->!s.id().startsWith("V")).map(s->linked("id",s.id(),"documentHash",s.documentHash(),"documentVersion",s.documentVersion(),"sourceFingerprint",identity(s.id()+s.evidence().quote()),"evidence",linked("documentId",s.evidence().documentId(),"documentName",s.evidence().documentName(),"page",s.evidence().page(),"locationType",s.evidence().locationType(),"quote",s.evidence().quote(),"anchor",s.evidence().anchor()))).toList();}
    static TableStructure.Result structure(Fact fact,List<Map<String,Object>> cells,Map<String,Source> sources){return TableStructure.analyze(SubjectIdentity.person(fact),strings(fact.attributes().get("cellIds")),cells,sources(fact,sources));}
    static String structuredIssue(Fact f,TableStructure.Result result){
        if(!result.valid())return result.issue();
        if(f.kind().equals("MONTHS")){
            String person=str(f.attributes(),"person");if(person.isBlank()||!f.subject().isBlank()&&!f.subject().equals(person))return "STRUCTURE_SUBJECT_MISMATCH";
            Set<String> months=new HashSet<>();result.rows().forEach(r->months.addAll(r.months()));if(!months.containsAll(strings(f.attributes().get("months")))||strings(f.attributes().get("months")).isEmpty())return "STRUCTURE_MONTH_COVERAGE_UNPROVEN";
            String employer=str(f.attributes(),"employer");if(!employer.isBlank()&&result.rows().stream().anyMatch(r->!r.employer().equals(employer)))return "STRUCTURE_EMPLOYER_UNPROVEN";
        }else if(f.kind().equals("PERSON_ROLE")){
            if(result.rows().stream().noneMatch(r->f.subject().equals(r.fields().get("ROLE"))))return "STRUCTURE_ROLE_UNPROVEN";
        }else if(f.kind().equals("CERTIFICATE")){
            boolean complete=result.rows().stream().anyMatch(r->{for(String key:List.of("category","grade","profession","employer")){String value=str(f.attributes(),key);String field=switch(key){case "category"->"CATEGORY";case "grade"->"GRADE";case "profession"->"PROFESSION";default->"EMPLOYER";};if(!value.isBlank()&&!value.equals(r.fields().get(field)))return false;}
                String number=str(f.attributes(),"number"),field=str(f.attributes(),"numberField");if(!number.isBlank()&&(field.isBlank()||!number.equals(r.fields().get(field))))return false;
                String validity=str(f.attributes(),"validityField"),start=str(f.attributes(),"start"),end=str(f.attributes(),"end");return start.isBlank()&&end.isBlank()||!validity.isBlank()&&datePresent(r.fields().getOrDefault("DATE:"+validity,""),start)&&datePresent(r.fields().getOrDefault("DATE:"+validity,""),end);});
            if(!complete)return "STRUCTURE_CERTIFICATE_FIELDS_UNPROVEN";
        }else if(f.kind().equals("DATE_RANGE")){
            String field=str(f.attributes(),"field");if(result.rows().stream().noneMatch(r->!field.isBlank()&&datePresent(r.fields().getOrDefault("DATE:"+field,""),str(f.attributes(),"start"))&&datePresent(r.fields().getOrDefault("DATE:"+field,""),str(f.attributes(),"end"))))return "STRUCTURE_DATE_RANGE_UNPROVEN";
        }
        return null;
    }
    static boolean amount(Fact fact,List<Map<String,Object>> cells,Map<String,Source> sources){
        List<String> ids=strings(fact.attributes().get("cellIds"));if(ids.isEmpty())return false;
        List<Map<String,Object>> cited=cells.stream().filter(c->ids.contains(cellId(c))).filter(c->sourceMatches(c,fact,sources)).toList();
        for(Map<String,Object> value:cited){if(!numberPresent(str(value,"text"),fact.value()))continue;String table=str(value,"tableId");Object row=value.get("row");if(table.isBlank()||row==null)continue;
            boolean label=contains(str(value,"headerText"),fact.basis())||cited.stream().anyMatch(c->sameTableRow(value,c)&&contains(str(c,"text"),fact.basis()));
            if(label&&(!fact.unit().isBlank()?contains(str(value,"text")+str(value,"headerText"),fact.unit())||cited.stream().anyMatch(c->sameTableRow(value,c)&&contains(str(c,"text"),fact.unit())):true))return true;
        }return false;
    }
    private static boolean sameTableRow(Map<String,Object>a,Map<String,Object>b){return str(a,"documentId").equals(str(b,"documentId"))&&str(a,"tableId").equals(str(b,"tableId"))&&Objects.equals(a.get("row"),b.get("row"))&&Objects.equals(a.get("location"),b.get("location"));}
    static String cellId(Map<String,Object> c){return str(c,"cellId").isBlank()?str(c,"tableCellId"):str(c,"cellId");}
    private static boolean sourceMatches(Map<String,Object> cell,Fact f,Map<String,Source> sources){String text=str(cell,"text");if(text.isBlank()||str(cell,"spanId").isBlank())return false;return f.sourceIds().stream().map(sources::get).filter(Objects::nonNull).anyMatch(s->s.evidence().documentId().equals(str(cell,"documentId"))&&s.evidence().page()==RunSession.number(cell.get("location"),RunSession.number(cell.get("page"),-1))&&contains(s.evidence().quote(),text));}
}
