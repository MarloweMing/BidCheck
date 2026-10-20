package cn.bidcheck.retrieval;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.List;
import java.util.Map;

/** Retrieval scores express relevance, never evidential confidence or verified identity. */
public record RetrievalResult(List<Evidence> hits, String mode, Map<String, Object> diagnostics) {
    public RetrievalResult {
        hits = List.copyOf(hits);
        diagnostics = Map.copyOf(diagnostics);
    }
}
