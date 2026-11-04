package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Evidence actually used by this rule, not all facts present in the evaluator. */
final class ObservedRecoveryEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ObservedRecoveryEvidence() {}

    static Map<String,Object> capture(Rule rule, RuleEvaluator evaluator,
            Map<String,Map<String,Object>> metadata) {
        Set<String> ids = new LinkedHashSet<>();
        Decision decision = evaluator.decisions.get(rule.id());
        if (decision != null) collect(decision.proof(), ids);
        List<Map<String,Object>> inputs = new ArrayList<>();
        for (Fact fact : evaluator.facts) {
            if (!ids.contains(fact.id()) || fact.sourceIds().isEmpty()) continue;
            List<Map<String,Object>> sources = new ArrayList<>();
            boolean complete = true;
            for (String ref : fact.sourceIds()) {
                Source source = evaluator.corpus.byId.get(ref);
                Map<String,Object> manifest = metadata.get(ref);
                if (source == null || manifest == null || source.documentHash().isEmpty()
                        || !source.documentHash().equals(str(manifest,"documentHash"))
                        || source.documentVersion() != RunSession.number(manifest.get("documentVersion"), -1)
                        || str(manifest,"parserFingerprint").isEmpty()) {
                    complete = false;
                    break;
                }
                sources.add(JSON.convertValue(manifest, Map.class));
            }
            if (complete) inputs.add(linked("itemId", fact.id(), "recordType", "FACT",
                "fact", SharedFactReuse.value(fact), "sources", sources));
        }
        return linked("version", "server-observed-evidence-1", "authority", "CURRENT_SERVER_PROOF",
            "obligationId", rule.id(), "observationKey", cn.bidcheck.verification.facts.FieldCoverage.key(SharedFactReuse.observationTarget(rule)),
            "inputs", inputs, "bidderProjectionInputs", bidderProjection(rule, decision, evaluator.corpus, metadata), "businessProof", false);
    }

    /** A rule-local projection: this scalar executor uses the complete bidder set, not lead duties. */
    static List<Map<String,Object>> bidderProjection(Rule rule, Decision decision, EvidenceCorpus corpus,
            Map<String,Map<String,Object>> metadata) {
        Set<String> allowedParameters = new HashSet<>(Arrays.asList("amountBasis", "taxBasis", "currency", "sourceCompilation"));
        if (!"NUMBER".equals(rule.predicate()) || !EnterpriseSubjectBindings.bidder(rule.subject())
                || !allowedParameters.containsAll(rule.parameters().keySet()) || decision == null
                || !rule.subject().equals(str(decision.proof(), "role"))) return Collections.emptyList();
        Set<String> subjects = new HashSet<>(ProofVerifier.strings(decision.proof().get("subjects")));
        if (subjects.size() < 2) return Collections.emptyList();
        List<Source> originals = new ArrayList<>();
        for (Map<String,Object> complete : maps(decision.proof().get("completeMemberSet"))) {
            String ref = str(complete, "sourceId");
            Source source = corpus.byId.get(ref);
            Map<String,Object> manifest = metadata.get(ref);
            if (!"EXPLICIT_CURRENT_BIDDER_COMPOSITION".equals(str(complete, "confirmation"))
                    || !subjects.equals(new HashSet<>(ProofVerifier.strings(complete.get("members"))))
                    || source == null || manifest == null || source.documentHash().isEmpty()
                    || !source.documentHash().equals(str(manifest, "documentHash"))
                    || source.documentVersion() != RunSession.number(manifest.get("documentVersion"), -1)
                    || str(manifest, "parserFingerprint").isEmpty()
                    || !source.evidence().quote().equals(str(complete, "quote"))) return Collections.emptyList();
            originals.add(source);
        }
        if (originals.isEmpty()) return Collections.emptyList();
        List<Rule> projectedRoles = new ArrayList<>();
        for (String role : Arrays.asList("联合体成员", "联合体牵头人"))
            projectedRoles.add(new Rule(rule.id() + ":bidder-projection", "", "RELATION", role, "名称", "", "", "",
                Collections.emptyMap(), "", "", Collections.emptyList()));
        List<Map<String,Object>> result = new ArrayList<>();
        // Existing native extraction re-parses and verifies the same immutable original. Model
        // attributes are not used to manufacture either membership or lead equivalence.
        for (Fact fact : EnterpriseSubjectBindings.extract(projectedRoles, originals)) {
            if (!subjects.contains(fact.value())) continue;
            List<Map<String,Object>> sources = new ArrayList<>();
            for (String ref : fact.sourceIds()) sources.add(JSON.convertValue(metadata.get(ref), Map.class));
            result.add(linked("itemId", fact.id(), "recordType", "FACT", "fact", SharedFactReuse.value(fact), "sources", sources));
        }
        return result;
    }

    private static List<Map<String,Object>> maps(Object value) {
        List<Map<String,Object>> result = new ArrayList<>();
        if (value instanceof Collection<?>) for (Object item : (Collection<?>) value)
            if (item instanceof Map<?,?>) result.add((Map<String,Object>) item);
        return result;
    }

    private static void collect(Object value, Set<String> ids) {
        if (value instanceof Map<?,?>) {
            Map<?,?> map = (Map<?,?>) value;
            Object single = map.get("factId");
            if (single instanceof String) ids.add((String) single);
            ids.addAll(ProofVerifier.strings(map.get("factIds")));
            for (Object child : map.values()) collect(child, ids);
        } else if (value instanceof Collection<?>) {
            for (Object child : (Collection<?>) value) collect(child, ids);
        }
    }
}
