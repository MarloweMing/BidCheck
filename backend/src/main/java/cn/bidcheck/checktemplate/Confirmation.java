package cn.bidcheck.checktemplate;

/** Audit record for a confirmed specification revision. */
public record Confirmation(String state, String actor, String confirmedAt, String revisionReason) {
    public Confirmation {
        state = state == null ? "DRAFT" : state.trim();
        actor = actor == null ? "" : actor.trim();
        confirmedAt = confirmedAt == null ? "" : confirmedAt.trim();
        revisionReason = revisionReason == null ? "" : revisionReason.trim();
    }

    public static Confirmation legacyConfirmed() { return new Confirmation("CONFIRMED", "LEGACY_ADAPTER", "", ""); }
}
