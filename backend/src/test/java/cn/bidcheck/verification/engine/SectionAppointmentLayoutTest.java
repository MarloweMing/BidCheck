package cn.bidcheck.verification.engine;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.verification.facts.TableStructure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
class SectionAppointmentLayoutTest {
    private TableStructure.Result row(String role,String name){return new TableStructure.Result(cn.bidcheck.compat.Java8Api.listOf(new TableStructure.Row("d","hash",1,"PAGE",1,"t","",1,name,"",false,true,"",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("ROLE", role),cn.bidcheck.compat.Java8Api.listOf("cell"),cn.bidcheck.compat.Java8Api.listOf("S"),"header","record")),"",cn.bidcheck.compat.Java8Api.listOf());}
    @Test void sourceValidatedPrincipalFormSurvivesBlankAppointmentCellButNotConflicts(){
        String text="附表1 施工企业主要负责人情况表\n姓名 李明\n拟在本合同任职 /\n";
        cn.bidcheck.verification.engine.VerificationTypes.Source source=new Source("S",new Evidence("d","商务册",1,"PAGE",text,"chars:0-"+text.length(),1),text,false,false,"hash",1,new DocumentSubjectScope.Context("CURRENT_BID_SECTION","本项目","section:cover-navigation-section-form-printed-page",cn.bidcheck.compat.Java8Api.listOf(text)));
        cn.bidcheck.verification.engine.VerificationTypes.Fact fact=new Fact("f","PERSON_ROLE","施工企业主要负责人","姓名","李明","李明","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf("S"),cn.bidcheck.compat.Java8Api.mapOf());
        assertTrue(LayoutBinding.sectionAppointment(fact,row("/","李明"),cn.bidcheck.compat.Java8Api.mapOf("S", source)));
        assertFalse(LayoutBinding.sectionAppointment(fact,row("项目负责人","李明"),cn.bidcheck.compat.Java8Api.mapOf("S", source)));
        assertFalse(LayoutBinding.sectionAppointment(fact,row("/","王明"),cn.bidcheck.compat.Java8Api.mapOf("S", source)));
        cn.bidcheck.verification.engine.VerificationTypes.Source historical=new Source("S",source.evidence(),text,false,true,"hash",1,new DocumentSubjectScope.Context("HISTORICAL_PROJECT","旧项目","",cn.bidcheck.compat.Java8Api.listOf()));
        assertFalse(LayoutBinding.sectionAppointment(fact,row("/","李明"),cn.bidcheck.compat.Java8Api.mapOf("S", historical)));
        cn.bidcheck.verification.engine.VerificationTypes.Source unbound=new Source("S",source.evidence(),text,false,false,"hash",1);
        assertFalse(LayoutBinding.sectionAppointment(fact,row("/","李明"),cn.bidcheck.compat.Java8Api.mapOf("S", unbound)));
    }
}
