package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AppointmentPlaceholderGuardTest {
    @Test void emptyRoleMarkersCannotBecomeAppointmentsEvenInsideCurrentPersonnelForm(){
        for(String marker:List.of("/","-","UNKNOWN","UNRESOLVED","未知","待确认","未明确")){
            EvidenceCorpus corpus=new PersonnelCountRoleTest().corpus("本项目人员配置\n姓名：测试甲\n拟在本合同任职："+marker);
            Fact fact=new PersonnelCountRoleTest().role(corpus,1,marker,"测试甲","","");
            var result=new ProofVerifier(corpus.byId).verify(new ObjectMapper().valueToTree(Map.of("facts",List.of(fact))),corpus.all);
            assertTrue(result.facts().isEmpty(),marker);assertEquals("CURRENT_ROLE_SOURCE_UNPROVEN",result.rejected().get(0).get("code"),marker);
        }
    }
    @Test void explicitEnterprisePrincipalTitleStillBindsCurrentFormWhenAppointmentCellIsBlank(){
        String text="2.施工企业主要负责人\n（1）情况表\n附表6-2 施工企业主要负责人情况表\n姓名：测试甲\n拟在本合同任职 /\n主要工作经历\n99";
        EvidenceCorpus corpus=new PersonnelCountRoleTest().corpus(text);
        Source source=corpus.all.get(0);
        var context=new DocumentSubjectScope.Context("CURRENT_BID_SECTION","示例项目","PAGE:99:cover-navigation-section-form-printed-page",List.of(text.substring(0,text.indexOf("主要工作经历"))));
        Source scoped=new Source(source.id(),source.evidence(),source.opening(),source.index(),source.historical(),source.documentHash(),source.documentVersion(),context);
        Fact fact=new Fact("principal","PERSON_ROLE","施工企业主要负责人","姓名","测试甲","测试甲","","","CURRENT_PROJECT",List.of(scoped.id()),Map.of());
        assertTrue(DocumentSubjectScope.appointment(scoped,fact));
    }
}
