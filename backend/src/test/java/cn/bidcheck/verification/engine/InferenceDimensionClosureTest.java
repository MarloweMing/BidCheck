package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class InferenceDimensionClosureTest {
    final InferenceClaims.HumanConfirmation approval=new InferenceClaims.HumanConfirmation("claim","reviewer","2026-11-02",InferenceClaims.POLICY,"target","fingerprint");
    @Test void confirmedEmployerResolvesOnlyTheNamedPersonAndStillRequiresSocialMonths(){
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(SharedFactReuseTest.doc("b","p",1,"任职资料\n测试甲\n合成设计有限公司")));String source=corpus.all.get(0).id();
        Rule rule=new Rule("m","本单位社保","MONTHS","测试甲","社保","","","",Map.of("employer","本单位","count",3),"本单位三个月社保","",List.of());
        Fact relation=new Fact("human","RELATION","测试甲","聘用","测试甲","合成设计有限公司","","","CURRENT_PROJECT",List.of(source),Map.of("object","合成设计有限公司","humanConfirmation",approval));
        assertEquals(List.of("合成设计有限公司"),PersonnelEmployerBindings.resolve(rule,List.of(relation),corpus).employers());
        assertNotEquals("PASS",new RuleEvaluator(List.of(relation),corpus,Map.of()).evaluate(rule).status(),"Employment is not social payment proof");
        Fact forged=new Fact("json","RELATION",relation.subject(),relation.field(),relation.rawValue(),relation.value(),"","",relation.scope(),relation.sourceIds(),Map.of("object","合成设计有限公司","humanConfirmation",Map.of("actor","reviewer")));
        assertFalse(PersonnelEmployerBindings.resolve(rule,List.of(forged),corpus).resolved());
    }
    @Test void confirmedPriceFillsUnknownDimensionsOnlyForTheSameOccurrence(){
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(SharedFactReuseTest.doc("b","p",1,"报价资料\n合成有限公司 报价 80元")));String source=corpus.all.get(0).id();
        Fact original=new Fact("old","NUMBER","合成有限公司","amount","80","80","元","","CURRENT_PROJECT",List.of(source),Map.of());
        Fact confirmed=new Fact("approved","NUMBER","合成有限公司","amount","80","80","元","投标总报价","CURRENT_PROJECT",List.of(source),Map.of("lot","不划分标段","taxBasis","含税","currency","CNY","humanConfirmation",approval));
        Rule rule=new Rule("price","报价上限","NUMBER","合成有限公司","amount","<=","100","元",Map.of("lot","不划分标段","amountBasis","投标总报价","taxBasis","含税","currency","CNY"),"报价不得超过100元","",List.of());
        assertNotEquals("PASS",new RuleEvaluator(List.of(original),corpus,Map.of()).evaluate(rule).status());
        assertTrue(InferenceClaims.numericSuperseded(original,List.of(original,confirmed)));
        assertEquals("PASS",new RuleEvaluator(List.of(original,confirmed),corpus,Map.of()).evaluate(rule).status());
        Fact explicitConflict=new Fact("conflict",original.kind(),original.subject(),original.field(),original.rawValue(),original.value(),original.unit(),"合同金额",original.scope(),original.sourceIds(),Map.of());
        assertFalse(InferenceClaims.numericSuperseded(explicitConflict,List.of(confirmed)));
        Fact otherSource=new Fact("different",original.kind(),original.subject(),original.field(),original.rawValue(),original.value(),original.unit(),original.basis(),original.scope(),List.of("other"),Map.of());
        assertFalse(InferenceClaims.numericSuperseded(otherSource,List.of(confirmed)));
    }
    @Test void onlyGroundedNumericAmbiguityMovesToConfirmationNotUncompiledRequirement(){
        Rule rule=new Rule("price","报价","NUMBER","合成有限公司","amount","<=","100","元",Map.of(),"100元","",List.of());
        Map<String,Object> context=Map.of("inferredFacts",List.of(linked("targetId","price","proposedFact",linked("kind","NUMBER","basis","投标总报价","unit","元"))));
        Decision unknown=new Decision("price","报价","UNDETERMINED","口径待定",List.of(),List.of(new Gap("PRICE_DIMENSIONS_REQUIRED","口径","确认","INPUT")),Map.of());
        assertEquals("NEEDS_CONFIRMATION",InferenceClaims.pending(rule,unknown,context).status());
        Decision uncompiled=new Decision("price","报价","UNDETERMINED","要求未明确",List.of(),List.of(new Gap("PRICE_REQUIREMENT_DIMENSIONS_REQUIRED","要求","编译","RETRY")),Map.of());
        assertEquals("UNDETERMINED",InferenceClaims.pending(rule,uncompiled,context).status());
    }
}
