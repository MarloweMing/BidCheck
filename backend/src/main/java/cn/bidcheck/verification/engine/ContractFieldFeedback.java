package cn.bidcheck.verification.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Read-only suggestions. A suggestion never adds a source, fact, or business proof. */
final class ContractFieldFeedback {
    private ContractFieldFeedback() { }

    static void atomicPaths(Map<String,Object> receipt, int index) {
        for (String key : Arrays.asList("rejections", "submittedSourceChecks", "readSourceCandidates")) {
            List<Map<String,Object>> mapped = new ArrayList<>();
            for (Map<String,Object> item : RuleEvaluator.maps(receipt.get(key))) {
                Map<String,Object> copy = new LinkedHashMap<>(item);
                String path = str(copy, "fieldPath");
                if (path.startsWith("/fact/")) copy.put("fieldPath", "/contractFields/" + index + path.substring(5));
                copy.put("fieldIndex", index);
                mapped.add(copy);
            }
            receipt.put(key, mapped);
        }
    }

    static Map<String,Object> describe(Map<String,Object> candidate, List<Map<String,Object>> issues,
            String targetId, List<Rule> rules, EvidenceCorpus corpus,
            Map<String,Source> disclosed, Set<String> actuallyRead) {
        if (!"CONTRACT".equals(str(candidate, "kind"))) return Collections.emptyMap();
        Map<String,Object> attributes = candidate.get("attributes") instanceof Map<?,?>
            ? (Map<String,Object>) candidate.get("attributes") : Collections.emptyMap();
        List<String> submittedIds = ProofVerifier.strings(candidate.get("sourceIds"));
        List<Map<String,Object>> checks = new ArrayList<>();
        for (Map.Entry<String,Object> attribute : attributes.entrySet()) {
            String path = "/fact/attributes/" + attribute.getKey();
            Map<String,Object> failure = issues.stream().filter(issue -> path.equals(issue.get("fieldPath")))
                .findFirst().orElse(Collections.emptyMap());
            checks.add(linked("fieldPath", path, "submittedValue", attribute.getValue(),
                "sourceIds", submittedIds, "status", failure.isEmpty() ? "NOT_CHECKED" : "UNSUPPORTED",
                "code", failure.getOrDefault("code", "")));
        }
        Rule target = rules.stream().filter(rule -> rule.id().equals(targetId)
            && rule.parameters().get("declaredPerformanceRow") instanceof Map<?,?>).findFirst().orElse(null);
        List<Map<String,Object>> candidates = new ArrayList<>();
        if (target != null && !issues.isEmpty()) {
            Map<String,Object> row = (Map<String,Object>) target.parameters().get("declaredPerformanceRow");
            ContractRecordBindings binding = new ContractRecordBindings(row, corpus.byId, actuallyRead);
            ProofVerifier verifier = new ProofVerifier(corpus.byId, binding);
            List<Source> submittedSources = new ArrayList<>();
            for (String id : submittedIds) {
                Source source = currentRead(id, row, corpus, disclosed, actuallyRead);
                if (source != null) submittedSources.add(source);
            }
            if (!submittedSources.isEmpty() && submittedSources.size() == submittedIds.size()) {
                for (Map<String,Object> issue : issues) {
                    String path = str(issue, "fieldPath");
                    if (!path.startsWith("/fact/attributes/")) continue;
                    String field = path.substring("/fact/attributes/".length());
                    if (Arrays.asList("amountBasis", "unit", "taxBasis").contains(field)) field = "amount";
                    if (!Arrays.asList("amount", "signedDate", "party", "person", "personRole").contains(field)) continue;
                    Map<String,Object> atomAttributes = fieldAttributes(attributes, field);
                    if ("signedDate".equals(field) && otherSigningDate(str(attributes, field),
                            row, corpus, disclosed, actuallyRead)) continue;
                    for (String id : actuallyRead) {
                        if (submittedIds.contains(id)) continue;
                        Source source = currentRead(id, row, corpus, disclosed, actuallyRead);
                        if (source == null || !fieldPresent(field, atomAttributes, source)) continue;
                        List<Source> references = new ArrayList<>(submittedSources);
                        references.add(source);
                        List<String> ids = new ArrayList<>(submittedIds);
                        ids.add(id);
                        Fact probe = new Fact("feedback-only", "CONTRACT", str(candidate, "subject"), field,
                            source.evidence().quote(), source.evidence().quote(), "", "",
                            str(candidate, "scope"), ids, atomAttributes);
                        if (!binding.matches(probe, references) || verifier.issue(probe, corpus.byId) != null) continue;
                        String quote = source.evidence().quote();
                        String locator = "amount".equals(field) ? str(attributes, "amountBasis") : str(attributes, field);
                        int position = quote.indexOf(locator);
                        Map<String,Object> suggestion = linked("fieldPath", path, "submittedValue", attributes.get(field),
                            "sourceId", source.id(), "documentId", source.evidence().documentId(),
                            "documentHash", source.documentHash(), "documentVersion", source.documentVersion(),
                            "page", source.evidence().page(), "requiresExplicitSelection", true, "businessProof", false);
                        if (position >= 0) {
                            int start = Math.max(0, position - 120);
                            int end = Math.min(quote.length(), start + 1200);
                            String excerpt = quote.substring(start, end);
                            cn.bidcheck.domain.Domain.Evidence original = source.evidence();
                            Source excerptOnly = new Source(source.id(), new cn.bidcheck.domain.Domain.Evidence(
                                original.documentId(), original.documentName(), original.page(), original.locationType(),
                                excerpt, original.anchor(), original.confidence()), source.opening(), source.index(),
                                source.historical(), source.documentHash(), source.documentVersion(), source.scopeContext());
                            if (fieldPresent(field, atomAttributes, excerptOnly)) {
                                suggestion.putAll(linked("quote", excerpt, "quoteStart", start,
                                    "quoteEnd", end, "quoteTruncated", start > 0 || end < quote.length()));
                            } else {
                                suggestion.putAll(linked("quoteOmittedReason", "FIELD_MATCH_OUTSIDE_BOUNDED_EXCERPT",
                                    "nextAction", "read_section"));
                            }
                        } else {
                            suggestion.putAll(linked("quoteOmittedReason", "NORMALIZED_MATCH_HAS_NO_EXACT_ORIGINAL_OFFSET",
                                "nextAction", "read_section"));
                        }
                        candidates.add(suggestion);
                    }
                }
            }
        }
        return linked("submittedSourceChecks", checks,
            "readSourceCandidates", new ArrayList<>(candidates.subList(0, Math.min(8, candidates.size()))),
            "readSourceCandidatesTruncated", candidates.size() > 8,
            "submissionExample", example(candidate, targetId, target == null ? Collections.emptyMap()
                : (Map<String,Object>) target.parameters().get("declaredPerformanceRow"),
                corpus, disclosed, actuallyRead, issues));
    }

    private static boolean otherSigningDate(String submittedDate, Map<String,Object> row,
            EvidenceCorpus corpus, Map<String,Source> disclosed, Set<String> actuallyRead) {
        for (String id : actuallyRead) {
            Source source = currentRead(id, row, corpus, disclosed, actuallyRead);
            if (source == null) continue;
            Matcher dates = Pattern.compile("(?<![0-9])([0-9]{4})[年./-]([0-9]{1,2})[月./-]([0-9]{1,2})日?(?![0-9])")
                .matcher(source.evidence().quote().replaceAll("\\s+", ""));
            while (dates.find()) {
                String date = String.format("%s-%02d-%02d", dates.group(1),
                    Integer.parseInt(dates.group(2)), Integer.parseInt(dates.group(3)));
                if (ProofVerifier.date(date) != null && !ProofVerifier.date(date).equals(ProofVerifier.date(submittedDate))
                        && ContractRecordBindings.signingDate(date, Collections.singletonList(source))) return true;
            }
        }
        return false;
    }

    private static Source currentRead(String id, Map<String,Object> row, EvidenceCorpus corpus,
            Map<String,Source> disclosed, Set<String> actuallyRead) {
        Source current = corpus.byId.get(id);
        Source read = disclosed.get(id);
        if (!actuallyRead.contains(id) || current == null || read == null
                || !DeclaredPerformanceRows.contains(row, current)
                || !current.evidence().documentId().equals(read.evidence().documentId())
                || !current.documentHash().equals(read.documentHash())
                || current.documentVersion() != read.documentVersion()
                || !current.evidence().quote().equals(read.evidence().quote())) return null;
        return current;
    }

    private static Map<String,Object> fieldAttributes(Map<String,Object> original, String field) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String key : Arrays.asList("contractName", "contractNumber", field))
            if (original.containsKey(key)) result.put(key, original.get(key));
        if ("amount".equals(field)) for (String key : Arrays.asList("amountBasis", "unit", "taxBasis"))
            if (original.containsKey(key)) result.put(key, original.get(key));
        return result;
    }

    private static boolean fieldPresent(String field, Map<String,Object> attributes, Source source) {
        String quote = source.evidence().quote();
        String value = str(attributes, field);
        if (value.isEmpty()) return false;
        if ("signedDate".equals(field))
            return ContractRecordBindings.signingDate(value, Collections.singletonList(source));
        if (!"amount".equals(field)) return ProofVerifier.contains(quote, value);
        String basis = str(attributes, "amountBasis");
        String unit = str(attributes, "unit");
        String tax = str(attributes, "taxBasis");
        return ProofVerifier.numberPresent(quote, value) && ProofVerifier.near(quote, basis, value)
            && !unit.isEmpty() && ProofVerifier.unitBound(ProofVerifier.numericContext(quote, basis, value), value, unit)
            && (tax.isEmpty() || ProofVerifier.taxBound(quote, basis, value, tax));
    }

    private static Map<String,Object> example(Map<String,Object> original, String targetId, Map<String,Object> row,
            EvidenceCorpus corpus, Map<String,Source> disclosed, Set<String> actuallyRead,
            List<Map<String,Object>> issues) {
        Map<String,Object> attributes = (Map<String,Object>) original.get("attributes");
        for (String id : ProofVerifier.strings(original.get("sourceIds"))) {
            Source source = row.isEmpty() ? null : currentRead(id, row, corpus, disclosed, actuallyRead);
            if (source == null) continue;
            for (String field : Arrays.asList("contractNumber", "contractName")) {
                String value = str(attributes, field);
                if (value.isEmpty() || !ProofVerifier.contains(source.evidence().quote(), value)) continue;
                Map<String,Object> identity = linked("kind", "CONTRACT", "subject", str(original, "subject"),
                    "field", field, "rawValue", value, "scope", str(original, "scope"),
                    "sourceIds", Collections.singletonList(id), "attributes", linked(field, value));
                Fact fact = new Fact("feedback-identity", "CONTRACT", str(original, "subject"), field,
                    value, value, "", "", str(original, "scope"), Collections.singletonList(id), linked(field, value));
                if (new ProofVerifier(corpus.byId).issue(fact, corpus.byId) != null) continue;
                String failed = issues.isEmpty() ? "signedDate" : str(issues.get(0), "fieldPath")
                    .replace("/fact/attributes/", "");
                if (Arrays.asList("unit", "taxBasis", "amountBasis").contains(failed)) failed = "amount";
                if (!Arrays.asList("amount", "signedDate", "party", "person", "personRole", "contractName", "contractNumber").contains(failed))
                    failed = "signedDate";
                Map<String,Object> atomAttributes = linked(field, value, failed, "<本字段已读原文的值>");
                if ("amount".equals(failed)) atomAttributes.putAll(linked("amountBasis", "<原文金额口径>",
                    "unit", "<原文单位>", "taxBasis", "<原文税口径，未明确时不得猜测>"));
                return linked("templateOnly", true, "readyToSubmit", false,
                    "instruction", "fact 是独立可证的身份锚点。先自行核对并替换所有尖括号占位符，每个字段使用自身已读原文；不得复制原被拒整包属性。候选来源不是自动采信。",
                    "toolArguments", linked("targetObligationId", targetId, "submissionMode", "CONTRACT_FIELDS",
                        "fact", identity, "contractFields", Collections.singletonList(linked("field", failed,
                            "rawValue", "<本字段原文连续引文>", "sourceIds", Arrays.asList(id, "<本字段已读同合同正文sourceId>"),
                            "attributes", atomAttributes))));
            }
        }
        return linked("templateOnly", true, "readyToSubmit", false, "instruction",
            "尚无独立可证的合同身份锚点。先读取合同原文并核对身份，不得构造空 fact 或使用未读来源。",
            "nextAction", "READ_CONTRACT_IDENTITY");
    }
}
