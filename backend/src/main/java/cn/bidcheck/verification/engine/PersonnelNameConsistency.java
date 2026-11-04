package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Compares current appointments within a role and lot, never every person's name together. */
final class PersonnelNameConsistency {
    private PersonnelNameConsistency() {}

    static boolean applies(Rule rule) {
        return "SAME_FIELD".equals(rule.predicate()) && "personName".equals(rule.field());
    }

    static boolean relevant(Fact fact) {
        return "CURRENT_PROJECT".equals(fact.scope())
            && ("PERSON_ROLE".equals(fact.kind()) || "FIELD".equals(fact.kind()) && "personName".equals(fact.field()))
            && PersonnelRoles.personRole(fact.subject());
    }

    /** Read only the explicit current appointment field; this does not infer names or role aliases. */
    static List<Fact> nativeAppointments(Rule rule, Source source) {
        if (!applies(rule) || source.index() || source.historical() || source.scopeContext().blocked())
            return Collections.emptyList();
        Set<String> roles = new LinkedHashSet<>();
        Matcher fields = Pattern.compile("拟\\s*在\\s*本\\s*合\\s*同\\s*任\\s*职\\s*[:：]?\\s*([\\p{IsHan}]{2,16})")
            .matcher(source.evidence().quote());
        while (fields.find()) {
            String role = PersonnelRoles.canonical(fields.group(1));
            if (PersonnelRoles.personRole(role) && (rule.subject().isEmpty()
                    || PersonnelRoles.canonical(rule.subject()).equals(role))) roles.add(role);
        }
        List<Fact> facts = new ArrayList<>();
        for (String role : roles) facts.addAll(DocumentSubjectScope.nativeAppointments(source, role));
        return cn.bidcheck.compat.Java8Api.toList(facts.stream().distinct());
    }

    static Decision evaluate(Rule rule, RuleEvaluator evaluator) {
        Map<String,List<Fact>> groups = new LinkedHashMap<>();
        List<Fact> unresolved = new ArrayList<>();
        Set<String> explicitLotRoles = new HashSet<>();
        for (Fact original : evaluator.facts) {
            if (!relevant(original)) continue;
            if (!rule.subject().isEmpty() && !PersonnelRoles.canonical(rule.subject()).equals(PersonnelRoles.canonical(original.subject()))) continue;
            Fact fact = "PERSON_ROLE".equals(original.kind()) ? original : new Fact(original.id(), "PERSON_ROLE",
                original.subject(), "姓名", original.rawValue(), original.value(), "", "", original.scope(), original.sourceIds(), original.attributes());
            if (new ProofVerifier().issue(fact, evaluator.corpus.byId) != null || fact.sourceIds().stream()
                    .map(evaluator.corpus.byId::get).anyMatch(s -> s == null || s.index() || s.historical() || s.scopeContext().blocked())) {
                unresolved.add(fact);
                continue;
            }
            String role = PersonnelRoles.canonical(fact.subject());
            String lot = sourceLot(fact, evaluator.corpus);
            if (lot == null) {
                unresolved.add(fact);
                continue;
            }
            if (!lot.isEmpty()) explicitLotRoles.add(role);
            groups.computeIfAbsent(role + "|" + lot, ignored -> new ArrayList<>()).add(fact);
        }
        if (groups.isEmpty()) return evaluator.unknown(rule, "FIELD_EVIDENCE_REQUIRED",
            "尚未建立当前项目中可定位岗位及姓名的独立任职记录", unresolved);
        List<Decision> decisions = new ArrayList<>();
        for (Map.Entry<String,List<Fact>> entry : groups.entrySet()) {
            String role = entry.getKey().substring(0, entry.getKey().indexOf('|'));
            String lot = entry.getKey().substring(entry.getKey().indexOf('|') + 1);
            List<Fact> facts = entry.getValue();
            Rule group = new Rule(rule.id() + ":role:" + identity(entry.getKey()).substring(0, 12),
                role + (lot.isEmpty() ? "" : "（" + lot + "）") + "姓名一致性", rule.predicate(), role, rule.field(),
                rule.operator(), rule.expected(), rule.unit(), rule.parameters(), rule.sourceQuote(), "", Collections.emptyList());
            Map<String,Object> proof = linked("comparisonScope", "CURRENT_PROJECT_ROLE_AND_LOT", "role", role,
                "lot", lot, "identityIsNotInferredFromName", true);
            if (lot.isEmpty() && explicitLotRoles.contains(role)) {
                decisions.add(evaluator.unknown(group, "PERSONNEL_COMPARISON_SCOPE_REQUIRED",
                    "同岗位还存在明确标段记录，当前记录标段未明，不能任意跨标段比较", facts, proof));
                continue;
            }
            Rule identityRule = new Rule(group.id(), group.description(), "PERSON_ROLE", role, "姓名", "", "", "",
                Collections.emptyMap(), rule.sourceQuote(), "", Collections.emptyList());
            SubjectIdentity.Problem issue = evaluator.identities.issue(identityRule, facts);
            if (issue != null) {
                decisions.add(evaluator.unknown(group, issue.code(), issue.reason(), facts, proof));
                continue;
            }
            SameFieldSourceOccurrences.Result occurrences = SameFieldSourceOccurrences.inspect(facts, evaluator.corpus);
            proof.put("sourceOccurrenceAudit", occurrences.audit());
            int records = independentRecordWindows(facts, evaluator.corpus);
            proof.put("independentSourceRecordWindows", records);
            long independentFacts = facts.stream().map(Fact::id).distinct().count();
            proof.put("independentlyBoundFactCount", independentFacts);
            if (independentFacts < 2 || records < 2 || occurrences.distinctCount() < 2 || !ProofVerifier.strings(occurrences.audit().get("unlocatedFactIds")).isEmpty()) {
                decisions.add(evaluator.unknown(group, "COMPARISON_SOURCE_REQUIRED",
                    "同岗位及标段不足两处可定位独立原文；重复抽取不是第二处比较来源", facts, proof));
                continue;
            }
            Fact first = facts.get(0);
            boolean samePerson = facts.stream().allMatch(f -> evaluator.identities.samePerson(first, f));
            if (samePerson) {
                proof.put("identityBindings", evaluator.identities.bindings(facts));
                decisions.add(evaluator.pass(group, "同一岗位和标段的独立原文姓名及身份已对应一致", facts, proof));
                continue;
            }
            Set<String> names = new LinkedHashSet<>();
            facts.forEach(f -> names.add(clean(f.value())));
            boolean masked = names.stream().anyMatch(n -> n.matches(".*[*＊×●_].*"));
            boolean strongDistinct = facts.stream().allMatch(f -> !evaluator.identities.countIdentity(f).isEmpty());
            if (names.size() > 1 && !masked && strongDistinct && explicitlyUnique(rule, role)) {
                proof.put("uniquenessRequirement", rule.sourceQuote());
                decisions.add(evaluator.fail(group, "原文要求该岗位仅配备一人，但可比较任职记录明确指向不同人员", facts, proof));
            } else {
                decisions.add(evaluator.unknown(group, masked ? "ENTITY_IDENTITY_AMBIGUOUS" : "PERSONNEL_COMPARISON_SCOPE_REQUIRED",
                    "岗位可能允许多名人员或跨来源身份尚未证明，不能仅因姓名不同确定冲突，也不能猜测同一人", facts, proof));
            }
        }
        if (!unresolved.isEmpty()) decisions.add(evaluator.unknown(rule, "PERSONNEL_COMPARISON_SCOPE_REQUIRED",
            "部分当前任职记录的原文、岗位或标段尚未对应，保留待核", unresolved));
        return RuleEvaluator.combine(rule.id(), rule.description(), "ALL", decisions);
    }

    /** Repeated mentions in one appointment are not a second comparison record. */
    private static int independentRecordWindows(List<Fact> facts, EvidenceCorpus corpus) {
        Map<String,List<int[]>> pages = new LinkedHashMap<>();
        Set<String> sourceIds = new LinkedHashSet<>();
        ProofVerifier verifier = new ProofVerifier();
        for (Fact fact : facts) for (String sourceId : fact.sourceIds()) {
            Source source = corpus.byId.get(sourceId);
            if (source == null) continue;
            // A multi-source fact may be valid because just one citation supports it. Count a
            // comparison record only when this individual source binds the role and exact name.
            Fact local = new Fact(fact.id(), fact.kind(), fact.subject(), fact.field(), fact.rawValue(),
                fact.value(), fact.unit(), fact.basis(), fact.scope(), Collections.singletonList(sourceId), fact.attributes());
            if (verifier.issue(local, Collections.singletonMap(sourceId, source)) == null) sourceIds.add(sourceId);
        }
        for (String id : sourceIds) {
            Source source = corpus.byId.get(id);
            int[] range = SectionCoverage.range(source);
            if (range[0] < 0 || range[1] <= range[0]) continue;
            String key = source.evidence().documentId() + "|" + source.documentHash() + "|"
                + source.documentVersion() + "|" + source.evidence().locationType() + "|" + source.evidence().page();
            pages.computeIfAbsent(key, ignored -> new ArrayList<>()).add(range);
        }
        int count = 0;
        for (List<int[]> ranges : pages.values()) {
            ranges.sort(Comparator.comparingInt(range -> range[0]));
            int end = -1;
            for (int[] range : ranges) {
                if (range[0] >= end) count++;
                end = Math.max(end, range[1]);
            }
        }
        return count;
    }

    /** An attribute cannot choose one of several nearby lot labels. */
    private static String sourceLot(Fact fact, EvidenceCorpus corpus) {
        Set<String> labels = new LinkedHashSet<>();
        boolean unlabeledSource = false;
        Pattern pattern = Pattern.compile("第?[一二三四五六七八九十百千万零〇两0-9A-Za-z]+标段");
        for (String id : fact.sourceIds()) {
            Matcher matcher = pattern.matcher(corpus.byId.get(id).evidence().quote());
            boolean found = false;
            while (matcher.find()) {
                labels.add(clean(matcher.group()));
                found = true;
            }
            unlabeledSource |= !found;
        }
        String proposed = clean(str(fact.attributes(), "lot"));
        if (labels.size() > 1 || !proposed.isEmpty() && !labels.equals(Collections.singleton(proposed))) return null;
        if (labels.size() == 1) return unlabeledSource ? null : labels.iterator().next();
        return proposed.isEmpty() ? "" : null;
    }

    private static boolean explicitlyUnique(Rule rule, String role) {
        String quote = clean(rule.sourceQuote());
        return quote.contains("本项目") && Pattern.compile(Pattern.quote(role)
            + "(?:仅|只)(?:配备|设置|配置)(?:1|一)(?:名|人)").matcher(quote).find();
    }
}
