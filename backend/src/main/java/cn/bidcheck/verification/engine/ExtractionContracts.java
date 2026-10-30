package cn.bidcheck.verification.engine;

/** Semantic extraction versions are independent of business decisions and operational deadlines. */
final class ExtractionContracts {
    static final String VERSION = "text-extraction-contract-1";
    private ExtractionContracts() {}
    static String stepSchema(String type) {
        return AgentReviewContracts.supports(type) ? AgentReviewContracts.VERSION : "EXTRACT".equals(type) ? VERSION : VerificationTypes.VERSION;
    }
}
