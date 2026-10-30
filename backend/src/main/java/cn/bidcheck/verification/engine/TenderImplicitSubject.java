package cn.bidcheck.verification.engine;

import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Closed tender-table roles, not a guess about which actual company owns bid evidence. */
final class TenderImplicitSubject {
    private TenderImplicitSubject(){}
    static boolean bidder(Rule rule,String source){
        if(!"投标人".equals(rule.subject())||RequirementContextBindings.uniqueOffset(source,rule.sourceQuote())<0)return false;
        String q=clean(rule.sourceQuote());
        if(q.matches(".*(?:招标人|采购人|代理机构|分包|历史|示例|其他投标人|设计单位|施工单位|第.+标段).*"))return false;
        if("PRICE_UNIQUENESS".equals(str(rule.parameters(),"deterministicFamily")))
            return q.matches("(?:报价[:：]?)?(?:投标报价|投标总报价|总报价)唯一(?:且有效)?[。.]?");
        if("LEGAL_REGISTRATION".equals(str(rule.parameters(),"deterministicFamily")))
            return LegalRegistrationProof.compilationComplete(rule,rule.sourceQuote())
                    ||q.matches("(?:资格评审)?注册要求[:：]?具备履约能力的在中华人民共和国境内行政主管部门登记注册、根据中华人民共和国有关法律合法成立的独立法人或其他组织，经营范围未受限[。.]?");
        return false;
    }
}
