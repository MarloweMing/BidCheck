package cn.bidcheck.checktemplate.evidence;

import java.util.Set;

/** Exact search coverage used to distinguish a reliable negative from an incomplete search. */
public record EvidenceScope(
        boolean closed,
        int scannedUnits,
        int expectedUnits,
        Set<String> missingUnits,
        String description) {
    public EvidenceScope {
        if (scannedUnits < 0 || expectedUnits < 0 || scannedUnits > expectedUnits) {
            throw new IllegalArgumentException("invalid scope unit counts");
        }
        missingUnits = missingUnits == null ? Set.of() : Set.copyOf(missingUnits);
        description = description == null ? "" : description.trim();
        if (closed && (scannedUnits != expectedUnits || !missingUnits.isEmpty())) {
            throw new IllegalArgumentException("closed scope must cover every expected unit");
        }
        if (!closed && scannedUnits == expectedUnits && missingUnits.isEmpty()) {
            throw new IllegalArgumentException("open scope must describe an incomplete boundary");
        }
    }

    public static EvidenceScope closed(int units, String description) {
        return new EvidenceScope(true, units, units, Set.of(), description);
    }

    public static EvidenceScope partial(int scanned, int expected, Set<String> missing, String description) {
        return new EvidenceScope(false, scanned, expected, missing, description);
    }
}
