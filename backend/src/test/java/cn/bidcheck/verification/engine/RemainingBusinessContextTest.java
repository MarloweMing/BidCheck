package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class RemainingBusinessContextTest {
    private Rule rule(String subject,String quote,String family){return new Rule("r","测试源义务","SAME_FIELD",subject,"投标报价","","","",Map.of("deterministicFamily",family),quote,"",List.of());}
    private Rule bind(String source,Rule rule){return new RequirementContextBindings(source,"TENDER_ORIGINAL").bind(rule,List.of());}

    @Test void tableTabAndColonPreserveTheSameExplicitPersonnelSet(){
        String obligation="上述人员须提交连续3个月的社保缴纳证明。";
        for(String separator:List.of("：","\t",":")){
            String source="人员要求"+separator+"项目负责人1人，设计负责人1人，施工负责人1人，专职安全员1人。"+obligation;
            Rule bound=bind(source,rule("上述人员",obligation,"PERSONNEL_SOCIAL"));
            assertEquals(List.of("项目负责人","设计负责人","施工负责人","专职安全员"),RequirementContextBindings.validatedSubjectSet(bound));
        }
    }
    @Test void tableContextCannotCrossAnotherCompanyRoleOrLot(){
        String obligation="上述人员须提交连续3个月的社保缴纳证明。";
        for(String change:List.of("设计单位：设计负责人2人。","另一标段人员要求：项目负责人2人。","甲建设有限公司：其他人员2人。")){
            String source="人员要求\t项目负责人1人，专职安全员1人。"+change+obligation;
            assertTrue(RequirementContextBindings.validatedSubjectSet(bind(source,rule("上述人员",obligation,"PERSONNEL_SOCIAL"))).isEmpty(),source);
        }
    }
    @Test void laterRoleQualificationsCannotEraseAnEarlierCommonPersonnelDuty(){
        String obligation="须提交2024年12月（含）至文件递交截止时间任意连续三个月在本单位缴纳社保证明材料。";
        String source="人员要求\t项目负责人1人，专职安全员1人。"+obligation+"上述人员不得超量承接项目。 / （1）项目负责人资格要求：具有注册证书。 / （2）专职安全员：具有安全证书。";
        Rule bound=bind(source,rule("上述人员",obligation,"SOCIAL_MONTHS"));
        assertEquals(List.of("项目负责人","专职安全员"),RequirementContextBindings.validatedSubjectSet(bound));
    }
    @Test void implicitBidderIsAClosedRequirementRoleAndNeverAnActualCompany(){
        String source="报价\t投标报价唯一且有效。";Rule r=bind(source,rule("投标人",source,"PRICE_UNIQUENESS"));
        assertNotNull(RequirementContextBindings.validatedReference(r,"subject","投标人",source));
        assertFalse(TenderImplicitSubject.bidder(rule("华北建设有限公司",source,"PRICE_UNIQUENESS"),source));
        for(String foreign:List.of("招标人总报价唯一。","其他投标人总报价唯一。","历史项目总报价唯一。","第一标段总报价唯一。")){
            assertFalse(TenderImplicitSubject.bidder(rule("投标人",foreign,"PRICE_UNIQUENESS"),foreign));
        }
        Map<String,Object> forged=new LinkedHashMap<>(r.parameters());
        forged.put("requirementBindings",Map.of("subject",Map.of("value","投标人","sourceQuote",source,"start",0,"end",source.length(),"bindingKind","SOURCE_DEFINED_BIDDER")));
        Rule changed=new Rule(r.id(),r.description(),r.predicate(),r.subject(),r.field(),r.operator(),r.expected(),r.unit(),forged,"存在风险的其他要求",r.logic(),r.children());
        assertNull(RequirementContextBindings.validatedReference(changed,"subject","投标人",source));
    }
    @Test void allInapplicableChildrenRemainInapplicableRatherThanPass(){
        Decision na=new Decision("n","可选材料","NOT_APPLICABLE","原文已证实非联合体",List.of(),List.of(),Map.of());
        for(String logic:List.of("ALL","ANY")){
            Decision out=RuleEvaluator.combine("parent","父项",logic,List.of(na));
            assertEquals("NOT_APPLICABLE",out.status());assertTrue(out.reason().contains("不适用"));
        }
        Decision pass=new Decision("p","独立必备材料","PASS","完整证明",List.of(),List.of(),Map.of());
        assertEquals("PASS",RuleEvaluator.combine("parent","父项","ALL",List.of(na,pass)).status());
        assertEquals("UNDETERMINED",RuleEvaluator.combine("parent","父项","ALL",List.of()).status());
    }
}
