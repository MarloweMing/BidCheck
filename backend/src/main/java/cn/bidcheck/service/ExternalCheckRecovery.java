package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Closes one ordinary-check external evidence step without letting a connector publish a verdict.
 * The connector record is persisted first, the frozen check context is loaded again, and only the
 * deterministic evaluator receives the changed record. An identical receipt cannot trigger a
 * second recomputation in the same run.
 */
final class ExternalCheckRecovery {
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Outcome {
        private final Map<String,Object> context;
        private final List<Map<String,Object>> newRecords;
        private final Finding finding;
        private final boolean recomputed;
        @com.fasterxml.jackson.annotation.JsonCreator
        Outcome(@com.fasterxml.jackson.annotation.JsonProperty("context") Map<String,Object> context, @com.fasterxml.jackson.annotation.JsonProperty("newRecords") List<Map<String,Object>> newRecords, @com.fasterxml.jackson.annotation.JsonProperty("finding") Finding finding, @com.fasterxml.jackson.annotation.JsonProperty("recomputed") boolean recomputed) { this.context = context; this.newRecords = newRecords; this.finding = finding; this.recomputed = recomputed; }
        public Map<String,Object> context() { return context; }
        public List<Map<String,Object>> newRecords() { return newRecords; }
        public Finding finding() { return finding; }
        public boolean recomputed() { return recomputed; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Outcome that = (Outcome) other; return java.util.Objects.equals(this.context, that.context) && java.util.Objects.equals(this.newRecords, that.newRecords) && java.util.Objects.equals(this.finding, that.finding) && java.util.Objects.equals(this.recomputed, that.recomputed); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.context); hash = 31 * hash + java.util.Objects.hashCode(this.newRecords); hash = 31 * hash + java.util.Objects.hashCode(this.finding); hash = 31 * hash + java.util.Objects.hashCode(this.recomputed); return hash; }
        @Override public String toString() { return "Outcome[" + "context=" + context + ", newRecords=" + newRecords + ", finding=" + finding + ", recomputed=" + recomputed + "]"; }
}

    private ExternalCheckRecovery() {}

    /** Preserve a failed/skipped query receipt even when no new evidence permits recomputation.
     * This updates diagnostics only: callers must not count a new Finding instance as a re-evaluation. */
    static Finding attachDiagnostics(Finding finding,List<Map<String,Object>> diagnostics) {
        List<Map<String,Object>> external=diagnostics==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.toList(diagnostics.stream()
            .filter(row->text(row.get("code")).startsWith("EXTERNAL_")).map(cn.bidcheck.compat.Java8Api::mapCopyOf));
        if(external.isEmpty())return finding;
        Map<String,Object> assessment=new LinkedHashMap<>(CheckResolution.map(finding.assessment()));
        assessment.put("externalQueryDiagnostics",external);
        assessment.put("externalQueryUpdate",cn.bidcheck.compat.Java8Api.mapOf("evidenceChanged", false, "conclusionRecomputed", false, "diagnosticsUpdated", true));
        return new Finding(finding.id(),finding.runId(),finding.checkpoint(),finding.evaluation(),
            FocusedCheckEvaluator.reconcileAssessment(finding.evaluation(),assessment));
    }

    static Outcome execute(List<Map<String,Object>> previous,List<Map<String,Object>> collected,
                           Consumer<List<Map<String,Object>>> publisher,
                           Supplier<Map<String,Object>> contextLoader,
                           Function<Map<String,Object>,Finding> evaluator,Finding before) {
        Objects.requireNonNull(publisher);Objects.requireNonNull(contextLoader);
        Objects.requireNonNull(evaluator);Objects.requireNonNull(before);
        List<Map<String,Object>> accepted=new ArrayList<>();
        if(collected!=null)for(Map<String,Object> record:collected)if(trusted(record))accepted.add(cn.bidcheck.compat.Java8Api.mapCopyOf(record));
        if(!accepted.isEmpty())publisher.accept(cn.bidcheck.compat.Java8Api.listCopyOf(accepted));
        Map<String,Object> context=new LinkedHashMap<>(Objects.requireNonNull(contextLoader.get()));
        List<Map<String,Object>> changed=RecoveryInputs.changedExternalRecords(previous,
                maps(context.get("externalRecords")));
        if(changed.isEmpty())return new Outcome(context,cn.bidcheck.compat.Java8Api.listOf(),before,false);
        RecoveryInputs.changedExternalInputs(context,before,changed);
        return new Outcome(context,changed,Objects.requireNonNull(evaluator.apply(context)),true);
    }

    private static boolean trusted(Map<String,Object> record) {
        if(record==null||!"TRUSTED_CONNECTOR".equals(text(record.get("provenanceStatus"))))return false;
        for(String key:cn.bidcheck.compat.Java8Api.listOf("type", "subject", "source", "queriedAt", "effectiveAt", "evaluationStage", "rawSnapshotHash"))
            if(cn.bidcheck.compat.Java8Api.isBlank(text(record.get(key))))return false;
        if(!(record.get("coverageComplete") instanceof Boolean)||!(record.get("result") instanceof Map<?,?>))return false;
        String status=text(((Map<?,?>) (record.get("result"))).get("status"));
        // Partial authoritative receipts are new evidence too. The evaluator retains coverage
        // gaps; an incomplete negative search cannot claim absence.
        return cn.bidcheck.compat.Java8Api.listOf("VERIFIED", "NOT_FOUND", "INCONCLUSIVE").contains(status)
            &&(!"NOT_FOUND".equals(status)||Boolean.TRUE.equals(record.get("coverageComplete")));
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){
        if(!(value instanceof Collection<?>))return cn.bidcheck.compat.Java8Api.listOf();
        return cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().filter(Map.class::isInstance).map(row->(Map<String,Object>)row));
    }
    private static String text(Object value){return cn.bidcheck.compat.Java8Api.strip(Objects.toString(value,""));}
}
