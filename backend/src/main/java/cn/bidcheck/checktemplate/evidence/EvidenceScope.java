package cn.bidcheck.checktemplate.evidence;

import java.util.Set;

/** Exact search coverage used to distinguish a reliable negative from an incomplete search. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class EvidenceScope {
        private final boolean closed;
        private final int scannedUnits;
        private final int expectedUnits;
        private final Set<String> missingUnits;
        private final String description;
        public boolean closed() { return closed; }
        public int scannedUnits() { return scannedUnits; }
        public int expectedUnits() { return expectedUnits; }
        public Set<String> missingUnits() { return missingUnits; }
        public String description() { return description; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; EvidenceScope that = (EvidenceScope) other; return java.util.Objects.equals(this.closed, that.closed) && java.util.Objects.equals(this.scannedUnits, that.scannedUnits) && java.util.Objects.equals(this.expectedUnits, that.expectedUnits) && java.util.Objects.equals(this.missingUnits, that.missingUnits) && java.util.Objects.equals(this.description, that.description); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.closed); hash = 31 * hash + java.util.Objects.hashCode(this.scannedUnits); hash = 31 * hash + java.util.Objects.hashCode(this.expectedUnits); hash = 31 * hash + java.util.Objects.hashCode(this.missingUnits); hash = 31 * hash + java.util.Objects.hashCode(this.description); return hash; }
        @Override public String toString() { return "EvidenceScope[" + "closed=" + closed + ", scannedUnits=" + scannedUnits + ", expectedUnits=" + expectedUnits + ", missingUnits=" + missingUnits + ", description=" + description + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public EvidenceScope(@com.fasterxml.jackson.annotation.JsonProperty("closed") boolean closed, @com.fasterxml.jackson.annotation.JsonProperty("scannedUnits") int scannedUnits, @com.fasterxml.jackson.annotation.JsonProperty("expectedUnits") int expectedUnits, @com.fasterxml.jackson.annotation.JsonProperty("missingUnits") Set<String> missingUnits, @com.fasterxml.jackson.annotation.JsonProperty("description") String description) {
        if (scannedUnits < 0 || expectedUnits < 0 || scannedUnits > expectedUnits) {
            throw new IllegalArgumentException("invalid scope unit counts");
        }
        missingUnits = missingUnits == null ? cn.bidcheck.compat.Java8Api.setOf() : cn.bidcheck.compat.Java8Api.setCopyOf(missingUnits);
        description = description == null ? "" : description.trim();
        if (closed && (scannedUnits != expectedUnits || !missingUnits.isEmpty())) {
            throw new IllegalArgumentException("closed scope must cover every expected unit");
        }
        if (!closed && scannedUnits == expectedUnits && missingUnits.isEmpty()) {
            throw new IllegalArgumentException("open scope must describe an incomplete boundary");
        }

            this.closed = closed; this.scannedUnits = scannedUnits; this.expectedUnits = expectedUnits; this.missingUnits = missingUnits; this.description = description;
        }

    public static EvidenceScope closed(int units, String description) {
        return new EvidenceScope(true, units, units, cn.bidcheck.compat.Java8Api.setOf(), description);
    }

    public static EvidenceScope partial(int scanned, int expected, Set<String> missing, String description) {
        return new EvidenceScope(false, scanned, expected, missing, description);
    }
}
