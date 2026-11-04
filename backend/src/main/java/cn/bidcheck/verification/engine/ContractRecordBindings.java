package cn.bidcheck.verification.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A host-created scope for current, actually read pages of one declared contract. */
final class ContractRecordBindings {
    private final Map<String,Object> row;
    private final Map<String,Source> current;
    private final Set<String> actuallyRead;


    private final List<ContractRecordBindings> alternatives;

    private ContractRecordBindings(List<ContractRecordBindings> alternatives) {
        this.row = Collections.emptyMap();
        this.current = Collections.emptyMap();
        this.actuallyRead = Collections.emptySet();
        this.alternatives = alternatives;
    }

    static ProofVerifier verifier(EvidenceCorpus corpus, List<Rule> rules, List<Source> verifiedSources) {
        Set<String> sourceIds = new java.util.LinkedHashSet<>();
        verifiedSources.forEach(source -> sourceIds.add(source.id()));
        Map<String,Map<String,Object>> rows = new java.util.LinkedHashMap<>();
        java.util.Deque<Rule> pending = new java.util.ArrayDeque<>(rules);
        while (!pending.isEmpty()) {
            Rule rule = pending.removeFirst();
            pending.addAll(rule.children());
            if (rule.parameters().get("declaredPerformanceRow") instanceof Map<?,?>) {
                Map<String,Object> row = (Map<String,Object>) rule.parameters().get("declaredPerformanceRow");
                rows.putIfAbsent(str(row, "inventoryIdentity"), row);
            } else if (SourcePerformanceScore.MODE.equals(str(rule.parameters(), "sourceScoringMode"))) {
                pending.addAll(SourcePerformanceScore.evidenceRules(rule));
            }
        }
        List<ContractRecordBindings> bindings = new ArrayList<>();
        rows.values().forEach(row -> bindings.add(new ContractRecordBindings(row, corpus.byId, sourceIds)));
        return new ProofVerifier(corpus.byId, new ContractRecordBindings(bindings));
    }

    ContractRecordBindings(Map<String,Object> row, Map<String,Source> current, Set<String> actuallyRead) {
        this.alternatives = Collections.emptyList();
        this.row = row;
        this.current = current;
        this.actuallyRead = actuallyRead;
    }

    boolean matches(Fact fact, List<Source> references) {
        if (!alternatives.isEmpty()) return alternatives.stream().filter(binding -> binding.matches(fact, references)).count() == 1;
        if (references.isEmpty() || !row.containsKey("documentHash")
                || !row.containsKey("documentVersion")) return false;
        String name = str(fact.attributes(), "contractName");
        String number = str(fact.attributes(), "contractNumber");
        if (clean(name).isEmpty() && clean(number).isEmpty()) return false;
        String rowTitle = str(row, "contractName");
        if (rowTitle.isEmpty()) rowTitle = str(row, "separatorTitle");
        if (!name.isEmpty() && !(ContractCandidateFields.sameTitle(name, rowTitle) || !rowTitle.endsWith("合同") && ContractCandidateFields.sameTitle(name, rowTitle + "合同"))) return false;
        for (Source source : references) {
            Source frozen = current.get(source.id());
            if (frozen == null || !actuallyRead.contains(source.id())
                    || !DeclaredPerformanceRows.contains(row, source)
                    || !frozen.documentHash().equals(source.documentHash())
                    || frozen.documentVersion() != source.documentVersion()
                    || !frozen.evidence().quote().equals(source.evidence().quote())
                    || isOrder(source.evidence().quote())) return false;
        }
        boolean anchor = references.stream().anyMatch(source ->
            (!name.isEmpty() && ContractCandidateFields.containsTitle(source.evidence().quote(), name))
            || (!number.isEmpty() && ProofVerifier.contains(source.evidence().quote(), number)));
        if (!anchor) return false;

        // A declared packet is navigation until the corpus itself rules out a second identity.
        // Orders keep the separate existing framework/order association path.
        for (Source source : current.values()) {
            if (!DeclaredPerformanceRows.contains(row, source) || isOrder(source.evidence().quote())) continue;
            String quote = source.evidence().quote();
            for (String title : printedTitles(quote)) {
                if (!ContractCandidateFields.sameTitle(title, name)
                        && !ContractCandidateFields.sameTitle(title, rowTitle)) return false;
            }
            for (String found : labels(quote, "(?:合同名称|合同名)")) {
                if (!name.isEmpty() && !ContractCandidateFields.sameTitle(found, name)) return false;
                if (name.isEmpty() && !ContractCandidateFields.sameTitle(found, rowTitle)) return false;
            }
            for (String found : labels(quote, "(?:合同编号|合同号|协议编号|协议号)")) {
                if (!number.isEmpty() && !clean(found).equals(clean(number))) return false;
                if (number.isEmpty()) return false;
            }
        }
        return true;
    }


    /**
     * Connects complementary facts for this scoring batch only. The common source,
     * not the declared range alone, must print both identities.
     */
    static final class IdentityLinker {
        private final EvidenceCorpus corpus;
        private final List<ContractRecordBindings> bindings = new ArrayList<>();
        private final Map<List<Object>,Map<String,Object>> records = new java.util.LinkedHashMap<>();

        IdentityLinker(List<Map<String,Object>> rows, EvidenceCorpus corpus, List<Fact> verifiedFacts) {
            this.corpus = corpus;
            Set<String> sourceIds = new java.util.LinkedHashSet<>();
            verifiedFacts.forEach(fact -> sourceIds.addAll(fact.sourceIds()));
            rows.forEach(row -> bindings.add(new ContractRecordBindings(row, corpus.byId, sourceIds)));
        }

        Map<String,Object> link(Fact named, Fact numbered) {
            List<String> nameSources = new ArrayList<>(named.sourceIds());
            List<String> numberSources = new ArrayList<>(numbered.sourceIds());
            Collections.sort(nameSources);
            Collections.sort(numberSources);
            List<Object> key = java.util.Arrays.asList(str(named.attributes(), "contractName"),
                str(numbered.attributes(), "contractNumber"), str(numbered.attributes(), "contractName"),
                nameSources, numberSources);
            Map<String,Object> record = records.get(key);
            if (record == null) {
                record = proveRecord(named, numbered);
                records.put(key, record);
            }
            if (record.isEmpty()) return record;
            Map<String,Object> proof = new java.util.LinkedHashMap<>(record);
            proof.put("factIds", java.util.Arrays.asList(named.id(), numbered.id()));
            return proof;
        }

        private Map<String,Object> proveRecord(Fact named, Fact numbered) {
            String name = str(named.attributes(), "contractName");
            String number = str(numbered.attributes(), "contractNumber");
            if (name.isEmpty() || number.isEmpty()) return Collections.emptyMap();
            String otherName = str(numbered.attributes(), "contractName");
            if (!otherName.isEmpty() && !ContractCandidateFields.sameTitle(name, otherName))
                return Collections.emptyMap();

            List<Map<String,Object>> anchors = new ArrayList<>();
            for (String id : named.sourceIds()) {
                if (!numbered.sourceIds().contains(id)) continue;
                Source source = corpus.byId.get(id);
                if (source == null) return Collections.emptyMap();
                String quote = source.evidence().quote();
                if (isOrder(quote) || quote.matches("(?s).*(?:示范文本|填写示例|空白合同|拟签订).*"))
                    continue;
                List<String> titles = printedTitles(quote);
                titles.addAll(labels(quote, "(?:合同名称|合同名)"));
                boolean titlePresent = titles.stream().anyMatch(title -> ContractCandidateFields.sameTitle(title, name));
                List<String> numbers = labels(quote, "(?:合同编号|合同号|协议编号|协议号)");
                Set<String> distinctNumbers = new java.util.LinkedHashSet<>();
                numbers.forEach(value -> distinctNumbers.add(clean(value)));
                if (!titlePresent || distinctNumbers.size() != 1 || !distinctNumbers.contains(clean(number)))
                    continue;
                anchors.add(linked("sourceId", id, "documentId", source.evidence().documentId(),
                    "documentHash", source.documentHash(), "documentVersion", source.documentVersion(),
                    "contractName", name, "contractNumber", number, "quote", quote));
            }
            if (anchors.isEmpty()) return Collections.emptyMap();

            Set<String> referenceIds = new java.util.LinkedHashSet<>(named.sourceIds());
            referenceIds.addAll(numbered.sourceIds());
            List<Source> references = new ArrayList<>();
            for (String id : referenceIds) {
                Source source = corpus.byId.get(id);
                if (source == null) return Collections.emptyMap();
                references.add(source);
            }
            // A temporary identity pair is used only to re-run the existing record guard.
            // No accepted fact, raw value, attribute or source list is changed.
            Fact identityPair = new Fact(named.id(), "CONTRACT", named.subject(), named.field(),
                named.rawValue(), named.value(), named.unit(), named.basis(), named.scope(),
                new ArrayList<>(referenceIds), linked("contractName", name, "contractNumber", number));
            List<Map<String,Object>> matchingRows = new ArrayList<>();
            for (ContractRecordBindings binding : bindings) {
                if (binding.matches(identityPair, references)) matchingRows.add(binding.row);
            }
            if (matchingRows.size() != 1) return Collections.emptyMap();

            return linked("method", "CURRENT_UNIQUE_RECORD_SHARED_IDENTITY_ANCHOR",
                "factIds", java.util.Arrays.asList(named.id(), numbered.id()),
                "inventoryIdentity", matchingRows.get(0).get("inventoryIdentity"),
                "anchors", anchors, "businessProof", false);
        }
    }

    static boolean signingDate(String date, java.util.Collection<Source> sources) {
        for (Source source : sources) {
            String quote = source.evidence().quote();
            Matcher labelled = Pattern.compile("(?:合同签订日期|合同签署日期|合同签约日期|签订日期|签署日期|签约日期|签订时间|签署时间|签约时间)[ \\t]*[:：][ \\t]*([^\\r\\n]{1,60})").matcher(quote);
            while (labelled.find()) if (ProofVerifier.datePresent(labelled.group(1), date)) return true;
            if (!quote.contains("签字") && !quote.contains("盖章")) continue;
            Matcher footer = Pattern.compile("(?m)^[ \\t]*(?:时间|签字日期|盖章日期)[ \\t]*[:：][ \\t]*([^\\r\\n]{1,40})").matcher(quote);
            while (footer.find()) if (ProofVerifier.datePresent(footer.group(1), date)) return true;
        }
        return false;
    }

    private static boolean isOrder(String quote) {
        return Pattern.compile("(?i)\\bPO(?:[-_：: ]+[A-Z0-9-]*|[A-Z0-9]*)[0-9][A-Z0-9-]*").matcher(quote).find();
    }


    private static List<String> printedTitles(String quote) {
        List<String> values = new ArrayList<>();
        for (String line : quote.split("\\r?\\n")) {
            String title = clean(line).replaceFirst("^#{1,6}", "")
                .replaceFirst("^[①②③④⑤⑥⑦⑧⑨⑩]", "")
                .replaceFirst("^[0-9]+[、.．]", "");
            if (title.length() < 8 || title.length() > 180
                    || !title.matches("[\\p{IsHan}A-Za-z0-9（）()·—–－_、-]+(?:合同|协议)")) continue;
            if (java.util.Arrays.asList("建设工程设计合同", "建设工程施工合同", "建设工程施工合同协议").contains(title)) continue;
            values.add(title);
        }
        return values;
    }

    private static List<String> labels(String quote, String label) {
        List<String> values = new ArrayList<>();
        boolean number = label.contains("编号");
        String valuePattern = number ? "([^\\s]+)" : "([^\\r\\n]+)";
        Matcher matcher = Pattern.compile(label + "\\s*[:：]\\s*" + valuePattern).matcher(quote);
        while (matcher.find()) addLabel(values, matcher.group(1), number);
        Matcher table = Pattern.compile("\\|[ \\t]*" + label
            + "[ \\t]*\\|[ \\t]*([^|\\r\\n]+)").matcher(quote);
        while (table.find()) addLabel(values, table.group(1), number);
        return values;
    }

    private static void addLabel(List<String> values, String raw, boolean number) {
        String value = raw.trim();
        if (number) value = value.split("\\s+")[0];
        if (!value.matches("[_＿—－.。\\s]*")) values.add(value);
    }

}