package cn.bidcheck.verification.engine;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.verification.facts.TableStructure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
class SectionAppointmentLayoutTest {
    private TableStructure.Result row(String role,String name){return new TableStructure.Result(List.of(new TableStructure.Row("d","hash",1,"PAGE",1,"t","",1,name,"",false,true,"",List.of(),Map.of("ROLE",role),List.of("cell"),List.of("S"),"header","record")),"",List.of());}
    @Test void sourceValidatedPrincipalFormSurvivesBlankAppointmentCellButNotConflicts(){
        String text="附表1 施工企业主要负责人情况表\n姓名 李明\n拟在本合同任职 /\n";
        var source=new Source("S",new Evidence("d","商务册",1,"PAGE",text,"chars:0-"+text.length(),1),text,false,false,"hash",1,new DocumentSubjectScope.Context("CURRENT_BID_SECTION","本项目","section:cover-navigation-section-form-printed-page",List.of(text)));
        var fact=new Fact("f","PERSON_ROLE","施工企业主要负责人","姓名","李明","李明","","","CURRENT_PROJECT",List.of("S"),Map.of());
        assertTrue(LayoutBinding.sectionAppointment(fact,row("/","李明"),Map.of("S",source)));
        assertFalse(LayoutBinding.sectionAppointment(fact,row("项目负责人","李明"),Map.of("S",source)));
        assertFalse(LayoutBinding.sectionAppointment(fact,row("/","王明"),Map.of("S",source)));
        var historical=new Source("S",source.evidence(),text,false,true,"hash",1,new DocumentSubjectScope.Context("HISTORICAL_PROJECT","旧项目","",List.of()));
        assertFalse(LayoutBinding.sectionAppointment(fact,row("/","李明"),Map.of("S",historical)));
        var unbound=new Source("S",source.evidence(),text,false,false,"hash",1);
        assertFalse(LayoutBinding.sectionAppointment(fact,row("/","李明"),Map.of("S",unbound)));
    }
}
