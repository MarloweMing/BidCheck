package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Equal observed prices do not establish the complete set of applicable offers. */
final class PriceUniquenessScope {
    private PriceUniquenessScope() {}
    static Decision guard(Rule rule,Decision comparison) {
        if(!"PASS".equals(comparison.status()))return comparison;
        Rule sourceBound=RequirementPrimitiveSemantics.normalize(rule);
        if(!RequirementPrimitiveSemantics.verifiedUniqueness(sourceBound))return comparison;
        Map<String,Object> proof=new LinkedHashMap<>(comparison.proof());
        proof.put("proofScope","OBSERVED_PRICE_EQUALITY_ONLY");
        proof.put("localComparisonStatus",comparison.status());
        proof.put("localComparisonReason",comparison.reason());
        proof.put("wholeApplicableQuoteSetProven",false);
        proof.put("alternativeOfferScopeProven",false);
        proof.put("requirementUniqueness",sourceBound.parameters().get("requirementUniqueness"));
        String reason="已核到的同口径报价一致，但尚未证明本项目全部适用报价及备选方案范围，不能据局部一致认定报价唯一";
        Gap missing=new Gap("UNIQUE_PRICE_SCOPE_REQUIRED",reason,"系统需核全适用报价材料及备选报价条件；本提示不表示已经发现重复报价","APPLICABLE_QUOTE_SCOPE_VERIFIED");
        return new Decision(comparison.id(),comparison.description(),"UNDETERMINED",reason,comparison.evidences(),cn.bidcheck.compat.Java8Api.listOf(missing),proof);
    }
}
