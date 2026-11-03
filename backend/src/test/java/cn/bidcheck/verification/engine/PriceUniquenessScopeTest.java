package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class PriceUniquenessScopeTest {
    final FocusedDomainRegressionTest fixture=new FocusedDomainRegressionTest();
    Rule rule(boolean unique) {
        String quote=unique?"甲公司投标总报价唯一。":"甲公司投标总报价前后一致。";
        Map<String,Object> parameters=new LinkedHashMap<>();
        return RequirementPrimitiveSemantics.normalize(new Rule("r",quote,"SAME_FIELD","甲公司","投标总报价","","","",parameters,quote,"",cn.bidcheck.compat.Java8Api.listOf()));
    }
    Fact target(Fact fact){return new Fact(fact.id(),fact.kind(),fact.subject(),"投标总报价",fact.rawValue(),fact.value(),fact.unit(),fact.basis(),fact.scope(),fact.sourceIds(),fact.attributes());}
    Decision prices(Rule rule,String secondValue,String secondTax,Map<String,Object> context) {
        cn.bidcheck.verification.engine.EvidenceCorpus corpus=fixture.corpus(fixture.priceText("100","万元","第一标段","人民币","含税"),fixture.priceText(secondValue,"元","第一标段","人民币",secondTax));
        java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact> facts=fixture.checked(corpus,target(fixture.price(corpus,1,"100","万元","第一标段","人民币","含税")),target(fixture.price(corpus,2,secondValue,"元","第一标段","人民币",secondTax)));
        return new RuleEvaluator(facts,corpus,context).evaluate(rule);
    }
    @Test void observedEqualPricesDoNotProveUniqueOffer(){
        Rule rule=rule(true);assertTrue(RequirementPrimitiveSemantics.verifiedUniqueness(rule));
        Decision decision=prices(rule,"1000000","含税",cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals("UNDETERMINED",decision.status());assertEquals("UNIQUE_PRICE_SCOPE_REQUIRED",decision.gaps().get(0).code());
        assertEquals("PASS",decision.proof().get("localComparisonStatus"));assertEquals("OBSERVED_PRICE_EQUALITY_ONLY",decision.proof().get("proofScope"));
        assertEquals(false,decision.proof().get("wholeApplicableQuoteSetProven"));assertEquals(false,decision.proof().get("alternativeOfferScopeProven"));assertEquals(2,decision.evidences().size());
    }
    @Test void ordinaryEqualityStillPasses(){assertEquals("PASS",prices(rule(false),"1000000","含税",cn.bidcheck.compat.Java8Api.mapOf()).status());}
    @Test void trustedSameBasisConflictIsNotHiddenByUniquenessGap(){assertEquals("FAIL",prices(rule(true),"1010000","含税",cn.bidcheck.compat.Java8Api.mapOf()).status());}
    @Test void differentTaxBasesStayScopeUnknown(){cn.bidcheck.verification.engine.VerificationTypes.Decision decision=prices(rule(true),"900000","不含税",cn.bidcheck.compat.Java8Api.mapOf());assertEquals("UNDETERMINED",decision.status());assertEquals("PRICE_SCOPE_AMBIGUOUS",decision.gaps().get(0).code());}
    @Test void unprovedGlobalCompletenessFlagsCannotCloseUniqueness(){
        cn.bidcheck.verification.engine.VerificationTypes.Decision decision=prices(rule(true),"1000000","含税",cn.bidcheck.compat.Java8Api.mapOf("fullDocumentRead", true, "documentSetComplete", true, "quoteSetComplete", true, "alternativeOfferScopeComplete", true));
        assertEquals("UNDETERMINED",decision.status());assertEquals("UNIQUE_PRICE_SCOPE_REQUIRED",decision.gaps().get(0).code());
    }
    @Test void oneQuoteDoesNotBecomePositiveUniqueness(){
        cn.bidcheck.verification.engine.EvidenceCorpus corpus=fixture.corpus(fixture.priceText("100","万元","第一标段","人民币","含税"));
        java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact> facts=fixture.checked(corpus,target(fixture.price(corpus,1,"100","万元","第一标段","人民币","含税")));
        cn.bidcheck.verification.engine.VerificationTypes.Decision decision=new RuleEvaluator(facts,corpus,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(rule(true));assertEquals("UNDETERMINED",decision.status());assertEquals("COMPARISON_SOURCE_REQUIRED",decision.gaps().get(0).code());
    }
    @Test void forgedOrStaleMarkerNeverSuppliesWholeSetProof(){
        Rule ordinary=rule(false);java.util.LinkedHashMap<java.lang.String, java.lang.Object> p=new LinkedHashMap<>(ordinary.parameters());p.put("requirementUniqueness",rule(true).parameters().get("requirementUniqueness"));
        Rule forged=new Rule(ordinary.id(),ordinary.description(),ordinary.predicate(),ordinary.subject(),ordinary.field(),ordinary.operator(),ordinary.expected(),ordinary.unit(),p,ordinary.sourceQuote(),ordinary.logic(),ordinary.children());
        assertFalse(RequirementPrimitiveSemantics.verifiedUniqueness(forged));
        Decision decision=prices(forged,"1000000","含税",cn.bidcheck.compat.Java8Api.mapOf());assertFalse(decision.proof().containsKey("wholeApplicableQuoteSetProven"));assertFalse(decision.proof().containsKey("uniquenessProven"));
    }
    @Test void tamperedMarkerCannotRemoveAnExplicitUniquenessObligation(){
        Rule original=rule(true);java.util.LinkedHashMap<java.lang.String, java.lang.Object> parameters=new LinkedHashMap<>(original.parameters());parameters.put("requirementUniqueness",cn.bidcheck.compat.Java8Api.mapOf("sourceHash", "forged", "requiresWholeDocument", false));
        Rule forged=new Rule(original.id(),original.description(),original.predicate(),original.subject(),original.field(),original.operator(),original.expected(),original.unit(),parameters,original.sourceQuote(),original.logic(),original.children());
        assertFalse(RequirementPrimitiveSemantics.verifiedUniqueness(forged));
        Decision decision=prices(forged,"1000000","含税",cn.bidcheck.compat.Java8Api.mapOf());assertEquals("UNDETERMINED",decision.status());assertEquals("UNIQUE_PRICE_SCOPE_REQUIRED",decision.gaps().get(0).code());
    }
}
