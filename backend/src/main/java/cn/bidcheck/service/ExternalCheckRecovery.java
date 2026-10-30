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
    record Outcome(Map<String,Object> context,List<Map<String,Object>> newRecords,
                   Finding finding,boolean recomputed) {}

    private ExternalCheckRecovery() {}

    /** Preserve a failed/skipped query receipt even when no new evidence permits recomputation.
     * This updates diagnostics only: callers must not count a new Finding instance as a re-evaluation. */
    static Finding attachDiagnostics(Finding finding,List<Map<String,Object>> diagnostics) {
        List<Map<String,Object>> external=diagnostics==null?List.of():diagnostics.stream()
            .filter(row->text(row.get("code")).startsWith("EXTERNAL_")).map(Map::copyOf).toList();
        if(external.isEmpty())return finding;
        Map<String,Object> assessment=new LinkedHashMap<>(CheckResolution.map(finding.assessment()));
        assessment.put("externalQueryDiagnostics",external);
        assessment.put("externalQueryUpdate",Map.of("evidenceChanged",false,"conclusionRecomputed",false,"diagnosticsUpdated",true));
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
        if(collected!=null)for(Map<String,Object> record:collected)if(trusted(record))accepted.add(Map.copyOf(record));
        if(!accepted.isEmpty())publisher.accept(List.copyOf(accepted));
        Map<String,Object> context=new LinkedHashMap<>(Objects.requireNonNull(contextLoader.get()));
        List<Map<String,Object>> changed=RecoveryInputs.changedExternalRecords(previous,
                maps(context.get("externalRecords")));
        if(changed.isEmpty())return new Outcome(context,List.of(),before,false);
        RecoveryInputs.changedExternalInputs(context,before,changed);
        return new Outcome(context,changed,Objects.requireNonNull(evaluator.apply(context)),true);
    }

    private static boolean trusted(Map<String,Object> record) {
        if(record==null||!"TRUSTED_CONNECTOR".equals(text(record.get("provenanceStatus"))))return false;
        for(String key:List.of("type","subject","source","queriedAt","effectiveAt","evaluationStage","rawSnapshotHash"))
            if(text(record.get(key)).isBlank())return false;
        if(!(record.get("coverageComplete") instanceof Boolean)||!(record.get("result") instanceof Map<?,?> result))return false;
        String status=text(result.get("status"));
        // Partial authoritative receipts are new evidence too. The evaluator retains coverage
        // gaps; an incomplete negative search cannot claim absence.
        return List.of("VERIFIED","NOT_FOUND","INCONCLUSIVE").contains(status)
            &&(!"NOT_FOUND".equals(status)||Boolean.TRUE.equals(record.get("coverageComplete")));
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){
        if(!(value instanceof Collection<?> rows))return List.of();
        return rows.stream().filter(Map.class::isInstance).map(row->(Map<String,Object>)row).toList();
    }
    private static String text(Object value){return Objects.toString(value,"").strip();}
}
