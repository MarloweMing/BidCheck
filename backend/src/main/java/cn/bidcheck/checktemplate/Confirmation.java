package cn.bidcheck.checktemplate;

/** Audit record for a confirmed specification revision. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class Confirmation {
        private final String state;
        private final String actor;
        private final String confirmedAt;
        private final String revisionReason;
        public String state() { return state; }
        public String actor() { return actor; }
        public String confirmedAt() { return confirmedAt; }
        public String revisionReason() { return revisionReason; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Confirmation that = (Confirmation) other; return java.util.Objects.equals(this.state, that.state) && java.util.Objects.equals(this.actor, that.actor) && java.util.Objects.equals(this.confirmedAt, that.confirmedAt) && java.util.Objects.equals(this.revisionReason, that.revisionReason); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.state); hash = 31 * hash + java.util.Objects.hashCode(this.actor); hash = 31 * hash + java.util.Objects.hashCode(this.confirmedAt); hash = 31 * hash + java.util.Objects.hashCode(this.revisionReason); return hash; }
        @Override public String toString() { return "Confirmation[" + "state=" + state + ", actor=" + actor + ", confirmedAt=" + confirmedAt + ", revisionReason=" + revisionReason + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public Confirmation(@com.fasterxml.jackson.annotation.JsonProperty("state") String state, @com.fasterxml.jackson.annotation.JsonProperty("actor") String actor, @com.fasterxml.jackson.annotation.JsonProperty("confirmedAt") String confirmedAt, @com.fasterxml.jackson.annotation.JsonProperty("revisionReason") String revisionReason) {
        state = state == null ? "DRAFT" : state.trim();
        actor = actor == null ? "" : actor.trim();
        confirmedAt = confirmedAt == null ? "" : confirmedAt.trim();
        revisionReason = revisionReason == null ? "" : revisionReason.trim();

            this.state = state; this.actor = actor; this.confirmedAt = confirmedAt; this.revisionReason = revisionReason;
        }

    public static Confirmation legacyConfirmed() { return new Confirmation("CONFIRMED", "LEGACY_ADAPTER", "", ""); }
}
