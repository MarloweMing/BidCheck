package cn.bidcheck.checktemplate.evidence;

import cn.bidcheck.checktemplate.Observation;

import java.util.List;
import java.util.Map;

/** Source-preserving result returned by a deterministic local extractor. */
public record EvidenceExtractionResult(
        Disposition disposition,
        List<Observation> observations,
        EvidenceScope scope,
        String message,
        Map<String, String> diagnostics) {

    public EvidenceExtractionResult {
        if (disposition == null) throw new IllegalArgumentException("disposition is required");
        observations = observations == null ? List.of() : List.copyOf(observations);
        if (scope == null) throw new IllegalArgumentException("scope is required");
        message = message == null ? "" : message.trim();
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        switch (disposition) {
            case POSITIVE -> {
                if (observations.isEmpty()) throw new IllegalArgumentException("positive result requires observations");
            }
            case NOT_FOUND -> {
                if (!scope.closed()) throw new IllegalArgumentException("negative result requires a closed scope");
                if (!observations.isEmpty()) throw new IllegalArgumentException("negative result cannot contain observations");
            }
            case PARTIAL -> {
                if (scope.closed()) throw new IllegalArgumentException("partial result requires an open scope");
            }
        }
    }

    public static EvidenceExtractionResult positive(List<Observation> observations, EvidenceScope scope) {
        return new EvidenceExtractionResult(Disposition.POSITIVE, observations, scope, "", Map.of());
    }

    public static EvidenceExtractionResult notFound(EvidenceScope closedScope, String message) {
        return new EvidenceExtractionResult(Disposition.NOT_FOUND, List.of(), closedScope, message, Map.of());
    }

    public static EvidenceExtractionResult partial(List<Observation> observations, EvidenceScope scope, String message) {
        return new EvidenceExtractionResult(Disposition.PARTIAL, observations, scope, message, Map.of());
    }

    public enum Disposition { POSITIVE, NOT_FOUND, PARTIAL }
}
