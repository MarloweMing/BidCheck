package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Restores only server-persisted actual read chunks, revalidated against current original content. */
final class ContractBodyReadReplay {
    private static final String TYPE = "AGENT_CONTRACT_BODY_READ";
    private ContractBodyReadReplay() {}

    static void record(RunSession session, String target, Map<String,Object> row, EvidenceCorpus corpus,
            Collection<String> sourceIds, Map<String,Object> result) {
        List<Map<String,Object>> originals = new ArrayList<>();
        for (String id : sourceIds) {
            Source source = corpus.byId.get(id);
            if (source != null) originals.add(RecoveryReplay.sourceMetadata(corpus, source));
        }
        if (originals.isEmpty()) return;
        Map<String,Object> binding = linked("targetObligationId", target, "rowIdentity", rowIdentity(row), "sources", originals);
        session.record(TYPE, binding, linked("readBinding", binding, "partial", result.get("partial"),
            "nextSourceId", result.getOrDefault("nextSourceId", ""), "businessProof", false));
    }

    static Set<String> restore(RunSession session, String target, Map<String,Object> row, EvidenceCorpus corpus) {
        Set<String> restored = new LinkedHashSet<>();
        for (Map<String,Object> step : RuleEvaluator.maps(session.context.get("priorSteps"))) {
            if (!TYPE.equals(str(step,"type")) || !"COMPLETED".equals(str(step,"status"))
                    || !VERSION.equals(str(step,"schemaVersion")) || !VERSION.equals(str(step,"engineVersion"))
                    || !session.model.identity().equals(str(step,"modelIdentity"))) continue;
            Map<String,Object> output = map(step.get("output")), binding = map(output.get("readBinding"));
            if (!Boolean.FALSE.equals(output.get("businessProof")) || !target.equals(str(binding,"targetObligationId"))
                    || !rowIdentity(row).equals(map(binding.get("rowIdentity")))
                    || !session.fingerprint(TYPE,binding).equals(str(step,"inputFingerprint"))) continue;
            for (Map<String,Object> saved : RuleEvaluator.maps(binding.get("sources"))) {
                if (RecoveryReplay.sourceMatches(corpus,saved)) restored.add(str(saved,"id"));
            }
        }
        return restored;
    }

    private static Map<String,Object> rowIdentity(Map<String,Object> row) {
        Map<String,Object> identity = new LinkedHashMap<>();
        for (String key : Arrays.asList("documentId","documentHash","documentVersion","inventoryIdentity","proofPageStart","proofPageEnd"))
            if (row.containsKey(key)) identity.put(key,row.get(key));
        return identity;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return value instanceof Map<?,?>?(Map<String,Object>)value:Collections.emptyMap(); }
}
