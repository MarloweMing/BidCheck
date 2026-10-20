package cn.bidcheck.checktemplate.evidence;

/**
 * A versioned local evidence reader. Implementations may read already parsed pages/facts only;
 * implementations must not invoke a language model or an unversioned remote service.
 */
public interface LocalEvidenceExtractor {
    String id();
    String version();
    EvidenceExtractionResult extract(EvidenceExtractionContext context) throws EvidenceExtractionException;
}
