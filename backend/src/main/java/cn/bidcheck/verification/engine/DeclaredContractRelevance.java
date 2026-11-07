package cn.bidcheck.verification.engine;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scheduling relevance only. A row title can keep a supplement executable, never prove membership. */
public final class DeclaredContractRelevance {
    private DeclaredContractRelevance() {}

    public static boolean possiblyRelevant(Map<String,Object> target, Map<String,Object> fact,
            Collection<Map<String,Object>> sources) {
        return possiblyRelevant(target, fact, sources, false);
    }

    private static boolean possiblyRelevant(Map<String,Object> target, Map<String,Object> fact,
            Collection<Map<String,Object>> sources, boolean projectNumber) {
        Map<String,Object> row = map(map(target.get("parameters")).get("declaredPerformanceRow"));
        if (!"CONTRACT".equals(text(target,"predicate")) || !"CONTRACT".equals(text(fact,"kind")) || row.isEmpty()) return true;
        String name = text(map(fact.get("attributes")),"contractName");
        if (name.isEmpty() && "contractName".equals(text(fact,"field"))) name = text(fact,"value");
        String title = text(row,"separatorTitle");
        if (name.isEmpty() && projectNumber && "contractNumber".equals(text(fact,"field"))) {
            name = currentNumberedTitle(row, fact, sources);
            // Ambiguous/incomplete identity pages can still carry same-number counterevidence.
            if (name.isEmpty()) return true;
        }
        if (!name.isEmpty() && (title.isEmpty() || sameDeclaredTitle(name, title))) return true;
        int first = number(row.get("proofPageStart")), last = number(row.get("proofPageEnd"));
        if (text(row,"documentId").isEmpty() || text(row,"documentHash").isEmpty()
                || number(row.get("documentVersion")) < 0 || first < 1 || last < first) return true;
        Map<String,Map<String,Object>> indexed = new HashMap<>();
        for (Map<String,Object> source : sources) indexed.put(text(source,"id").isEmpty()?text(source,"sourceId"):text(source,"id"), source);
        List<String> refs = strings(fact.get("sourceIds"));
        if (refs.isEmpty()) return true;
        for (String id : refs) {
            if (strings(row.get("sourceIds")).contains(id)) return true;
            Map<String,Object> source = indexed.get(id);
            if (source == null) return true;
            Map<String,Object> evidence = map(source.get("evidence"));
            String doc = text(source,"documentId"); if (doc.isEmpty()) doc = text(evidence,"documentId");
            String hash = text(source,"documentHash");
            int version = number(source.get("documentVersion"));
            Object location = source.containsKey("location")?source.get("location"):source.get("page");
            if (location == null) location = evidence.containsKey("page")?evidence.get("page"):evidence.get("location");
            int page = number(location);
            if (doc.isEmpty() || hash.isEmpty() || version < 0 || page < 1) return true;
            if (doc.equals(text(row,"documentId"))) {
                // A changed document is an input change, not proof that the evidence is unrelated.
                if (!hash.equals(text(row,"documentHash")) || version != number(row.get("documentVersion"))) return true;
                if (page >= first && page <= last) return true;
            }
        }
        return false;
    }


    private static final String NUMBER_RECEIPT = "server-declared-row-number-1";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** This result controls scheduling only; it does not add facts or prove a contract. */
    public static String schedulingReason(Map<String,Object> binding, Map<String,Object> fact,
            Collection<Map<String,Object>> sources) {
        Set<String> numbers = incomingNumbers(fact, sources);
        if (knownRowNumber(binding, fact, numbers)) return "SAME_NUMBER_COUNTEREVIDENCE_POSSIBLE";
        // Old/partial snapshots cannot establish the absence of a known same-number counterfact.
        if ("CONTRACT".equals(text(fact, "kind")) && !numbers.isEmpty()
                && !currentNumberSnapshot(binding)) return "ROW_NUMBER_SNAPSHOT_UNAVAILABLE";
        return possiblyRelevant(map(binding.get("target")), fact, sources, currentNumberSnapshot(binding))
            ? "POSSIBLY_RELEVANT" : "DIFFERENT_CONTRACT_OUTSIDE_DECLARED_ROW";
    }

    /** READ row decisions are projected, so they need not occur in evaluator.decisions.
     * Revalidate current facts in this exact row; global observedValues cannot bind a number. */
    static Map<String,Object> captureRowNumbers(Rule rule, RuleEvaluator evaluator,
            Map<String,Map<String,Object>> metadata) {
        Map<String,Object> row = map(rule.parameters().get("declaredPerformanceRow"));
        if (!"CONTRACT".equals(rule.predicate()) || row.isEmpty()) return Collections.emptyMap();
        List<Source> rowSources = new ArrayList<>();
        for (Source source : evaluator.corpus.all) {
            if (!DeclaredPerformanceRows.contains(row, source)) continue;
            rowSources.add(source);
            Map<String,Object> manifest = metadata.get(source.id());
            if (manifest == null) return Collections.emptyMap();
            Map<String,Object> copy = JSON.convertValue(manifest, Map.class);
            if (!insideCurrentRow(row, copy)
                    || !source.evidence().quote().equals(text(map(copy.get("evidence")), "quote"))
                    || source.evidence().page() != number(map(copy.get("evidence")).get("page")))
                return Collections.emptyMap();
        }
        if (rowSources.isEmpty()) return Collections.emptyMap();
        List<Map<String,Object>> inputs = new ArrayList<>();
        for (Fact fact : evaluator.facts) {
            String number = str(fact.attributes(), "contractNumber");
            if (!"CONTRACT".equals(fact.kind()) || number.isEmpty() || fact.sourceIds().isEmpty()) continue;
            if (fact.sourceIds().stream().anyMatch(id -> !evaluator.corpus.byId.containsKey(id))) return Collections.emptyMap();
            if (!fact.sourceIds().stream().allMatch(id -> DeclaredPerformanceRows.contains(row, evaluator.corpus.byId.get(id)))) continue;
            List<Source> references = new ArrayList<>();
            List<Map<String,Object>> originals = new ArrayList<>();
            boolean complete = true;
            for (String id : fact.sourceIds()) {
                Source source = evaluator.corpus.byId.get(id);
                Map<String,Object> manifest = metadata.get(id);
                if (source == null || manifest == null || !DeclaredPerformanceRows.contains(row, source)) {
                    complete = false;
                    break;
                }
                Map<String,Object> copy = JSON.convertValue(manifest, Map.class);
                Map<String,Object> evidence = map(copy.get("evidence"));
                if (!source.id().equals(text(copy, "id")) || !source.documentHash().equals(text(copy, "documentHash"))
                        || source.documentVersion() != number(copy.get("documentVersion"))
                        || !source.evidence().quote().equals(text(evidence, "quote"))
                        || !source.evidence().documentId().equals(text(evidence, "documentId"))
                        || source.evidence().page() != number(evidence.get("page"))
                        || !source.evidence().locationType().equals(text(evidence, "locationType"))
                        || text(copy, "parserFingerprint").isEmpty() || !insideCurrentRow(row, copy)) {
                    complete = false;
                    break;
                }
                references.add(source);
                originals.add(copy);
            }
            if (!complete || references.stream().noneMatch(source -> printedNumber(source.evidence().quote(), number))) return Collections.emptyMap();
            ProofVerifier verifier = ContractRecordBindings.verifier(evaluator.corpus,
                Collections.singletonList(rule), references);
            if (verifier.issue(fact, evaluator.corpus.byId) != null) return Collections.emptyMap();
            inputs.add(linked("factId", fact.id(), "number", number,
                "sourceIds", fact.sourceIds(), "sources", originals));
        }
        return linked("version", NUMBER_RECEIPT, "authority", "CURRENT_SERVER_VERIFIED_ROW",
            "obligationId", rule.id(), "observationKey",
            cn.bidcheck.verification.facts.FieldCoverage.key(SharedFactReuse.observationTarget(rule)),
            "row", JSON.convertValue(row, Map.class), "inputs", inputs, "complete", true,
            "scope", "CURRENT_VALIDATED_FACT_SET_ONLY", "businessProof", false);
    }

    private static boolean currentNumberSnapshot(Map<String,Object> binding) {
        Map<String,Object> row = map(map(map(binding.get("target")).get("parameters")).get("declaredPerformanceRow"));
        Map<String,Object> receipt = map(binding.get("rowNumberEvidence"));
        return !row.isEmpty() && NUMBER_RECEIPT.equals(text(receipt, "version"))
            && "CURRENT_SERVER_VERIFIED_ROW".equals(text(receipt, "authority"))
            && Boolean.FALSE.equals(receipt.get("businessProof")) && Boolean.TRUE.equals(receipt.get("complete"))
            && !text(binding, "obligationId").isEmpty() && !text(binding, "observationKey").isEmpty()
            && text(binding, "obligationId").equals(text(receipt, "obligationId"))
            && text(binding, "observationKey").equals(text(receipt, "observationKey"))
            && JSON.valueToTree(row).equals(JSON.valueToTree(receipt.get("row")))
            && receipt.get("inputs") instanceof Collection<?>;
    }

    private static boolean knownRowNumber(Map<String,Object> binding, Map<String,Object> fact, Set<String> numbers) {
        Map<String,Object> target = map(binding.get("target"));
        Map<String,Object> row = map(map(target.get("parameters")).get("declaredPerformanceRow"));
        Map<String,Object> receipt = map(binding.get("rowNumberEvidence"));
        if (!"CONTRACT".equals(text(target, "predicate")) || !"CONTRACT".equals(text(fact, "kind"))
                || numbers.isEmpty() || !currentNumberSnapshot(binding)) return false;
        Object rawInputs = receipt.get("inputs");
        if (!(rawInputs instanceof Collection<?>)) return false;
        for (Object raw : (Collection<?>) rawInputs) {
            Map<String,Object> input = map(raw);
            if (!numbers.contains(numberIdentity(text(input, "number"))) || text(input, "factId").isEmpty()) continue;
            Set<String> refs = new HashSet<>(strings(input.get("sourceIds")));
            Object originals = input.get("sources");
            if (refs.isEmpty() || !(originals instanceof Collection<?>)) continue;
            Set<String> found = new HashSet<>();
            boolean complete = true, printed = false;
            for (Object original : (Collection<?>) originals) {
                Map<String,Object> source = map(original);
                String id = text(source, "id");
                if (!refs.contains(id) || !found.add(id) || !insideCurrentRow(row, source)) {
                    complete = false;
                    break;
                }
                printed |= printedNumber(text(map(source.get("evidence")), "quote"), text(input, "number"));
            }
            if (complete && printed && refs.equals(found)) return true;
        }
        return false;
    }

    private static boolean insideCurrentRow(Map<String,Object> row, Map<String,Object> source) {
        Map<String,Object> evidence = map(source.get("evidence"));
        int first = number(row.get("proofPageStart")), last = number(row.get("proofPageEnd"));
        int page = number(evidence.get("page"));
        return !text(row, "documentId").isEmpty() && !text(row, "documentHash").isEmpty()
            && number(row.get("documentVersion")) >= 1 && first >= 1 && last >= first
            && text(row, "documentId").equals(text(evidence, "documentId"))
            && text(row, "documentHash").equals(text(source, "documentHash"))
            && number(row.get("documentVersion")) == number(source.get("documentVersion"))
            && !text(source, "parserFingerprint").isEmpty() && "PAGE".equals(text(evidence, "locationType"))
            && page >= first && page <= last;
    }

    /** A name-only event can itself quote the known number. Retain that counterfact without
     * adding a number attribute or treating the printed title as business membership. */
    private static Set<String> incomingNumbers(Map<String,Object> fact, Collection<Map<String,Object>> sources) {
        Set<String> numbers = new HashSet<>();
        if (!"CONTRACT".equals(text(fact, "kind"))) return numbers;
        String explicit = text(map(fact.get("attributes")), "contractNumber");
        if (!explicit.isEmpty()) numbers.add(numberIdentity(explicit));
        Set<String> refs = new HashSet<>(strings(fact.get("sourceIds")));
        for (Map<String,Object> source : sources) {
            if (!refs.contains(text(source, "id")) || text(source, "documentHash").isEmpty()
                    || number(source.get("documentVersion")) < 1 || text(source, "parserFingerprint").isEmpty()
                    || text(source, "sourceFingerprint").isEmpty()) continue;
            Map<String,Object> evidence = map(source.get("evidence"));
            if (text(evidence, "documentId").isEmpty() || !"PAGE".equals(text(evidence, "locationType"))
                    || number(evidence.get("page")) < 1) continue;
            String quote = java.text.Normalizer.normalize(text(evidence, "quote"), java.text.Normalizer.Form.NFKC)
                .toUpperCase(Locale.ROOT).replaceAll("[‐‑‒–—―−]", "-");
            // Same two explicit printed-number formats as SourcePerformanceScore.
            for (String expression : Arrays.asList(
                    "(?:合同编号|合同号|协议编号|协议号)\\s*[:：]\\s*([^\\s|]+)",
                    "\\|\\s*(?:合同编号|合同号|协议编号|协议号)\\s*\\|\\s*([^|\\r\\n]+)")) {
                Matcher printed = Pattern.compile(expression).matcher(quote);
                while (printed.find()) numbers.add(numberIdentity(printed.group(1)));
            }
        }
        return numbers;
    }

    // Same canonical number rule as SourcePerformanceScore.numberExact; retention only.
    private static String numberIdentity(String value) {
        return java.text.Normalizer.normalize(clean(value), java.text.Normalizer.Form.NFKC)
            .toUpperCase(Locale.ROOT).replaceAll("[‐‑‒–—―−]", "-");
    }
    private static boolean printedNumber(String quote, String number) {
        Matcher matcher = Pattern.compile("(?:合同编号|合同号|协议编号|协议号)[ \\t]*[:：][ \\t]*([A-Za-z0-9][A-Za-z0-9_./-]*)").matcher(quote);
        boolean found = false;
        while (matcher.find()) {
            if (!number.equals(matcher.group(1))) return false;
            found = true;
        }
        return found;
    }

    private static boolean sameDeclaredTitle(String name, String title) {
        if(name.startsWith("PAIRED_LOT_TITLE:")){
            String stem=normalize(name.substring("PAIRED_LOT_TITLE:".length()).replaceFirst("(?:合同|协议)$",""));
            String heading=normalize(title);
            // Possible supplements of this project stay eligible even if their lot spelling varies.
            return stem.contains(heading)||heading.contains(stem);
        }
        if(name.startsWith("PROJECT_NAME:")){
            String project=normalize(name.substring("PROJECT_NAME:".length())),heading=normalize(title);
            return project.contains(heading)||heading.contains(project)||ContractCandidateFields.sameTitle(project,heading);
        }
        return normalize(name).equals(normalize(title))
            || ContractCandidateFields.sameTitle(name, title)
            || !title.endsWith("合同") && ContractCandidateFields.sameTitle(name, title + "合同");
    }

    /** A local scheduling projection, never an attribute added to an accepted fact. */
    private static String currentNumberedTitle(Map<String,Object> row, Map<String,Object> fact,
            Collection<Map<String,Object>> sources) {
        if (!"contractNumber".equals(text(fact, "field"))) return "";
        String number = text(map(fact.get("attributes")), "contractNumber");
        if (number.isEmpty() || !number.equals(text(fact, "rawValue"))
                || !number.equals(text(fact, "value"))) return "";
        Map<String,Map<String,Object>> indexed = new HashMap<>();
        for (Map<String,Object> source : sources) {
            String id = text(source, "id");
            if (id.isEmpty()) id = text(source, "sourceId");
            Map<String,Object> previous = indexed.put(id, source);
            if (previous != null && !previous.equals(source)) return "";
        }
        List<String> refs = strings(fact.get("sourceIds"));
        if (refs.isEmpty()) return "";
        String title = "";
        for (String id : refs) {
            Map<String,Object> source = indexed.get(id);
            if (source == null || text(source, "documentHash").isEmpty()
                    || text(source, "sourceFingerprint").isEmpty()
                    || text(source, "parserFingerprint").isEmpty()
                    || number(source.get("documentVersion")) < 1) return "";
            Map<String,Object> evidence = map(source.get("evidence"));
            String doc = text(evidence, "documentId");
            if (doc.isEmpty() || !doc.equals(text(row, "documentId"))
                    || !text(source, "documentHash").equals(text(row, "documentHash"))
                    || number(source.get("documentVersion")) != number(row.get("documentVersion"))
                    || !"PAGE".equals(text(evidence, "locationType"))
                    || number(evidence.get("page")) < 1) return "";
            // Shared source records may contain flat and nested coordinates. A conflict
            // must not let the outer range check classify an inside page as outside.
            if (source.containsKey("documentId") && !doc.equals(text(source, "documentId"))
                    || source.containsKey("locationType") && !text(evidence, "locationType").equals(text(source, "locationType"))
                    || source.containsKey("page") && number(source.get("page")) != number(evidence.get("page"))
                    || source.containsKey("location") && number(source.get("location")) != number(evidence.get("page"))
                    || source.containsKey("quote") && !text(evidence, "quote").equals(text(source, "quote"))) return "";
            String current = uniquePrintedTitle(text(evidence, "quote"), number);
            // Every cited record must carry the same definite identity. An unbound tail
            // or another document must remain executable rather than borrow this heading.
            if (current.isEmpty() || !title.isEmpty() && !title.equals(current)) return "";
            title = current;
        }
        return title;
    }

    private static String uniquePrintedTitle(String quote, String expectedNumber) {
        if (quote.isEmpty() || Pattern.compile("示范文本|填写示例|空白合同|拟签订|合同模板|合同范本|订单编号|采购订单")
                .matcher(quote).find()) return "";
        String labels = "(?:合同编号|合同号|协议编号|协议号)";
        Matcher mentions = Pattern.compile(labels).matcher(quote);
        int mentioned = 0;
        while (mentions.find()) mentioned++;
        Matcher numbers = Pattern.compile(labels + "[ \\t]*[:：][ \\t]*([A-Za-z0-9][A-Za-z0-9_./-]*)").matcher(quote);
        Set<String> values = new HashSet<>();
        int parsed = 0;
        while (numbers.find()) { values.add(numbers.group(1)); parsed++; }
        if (parsed == 0 || parsed != mentioned || values.size() != 1 || !values.contains(expectedNumber)) return "";
        String lot=pairedLotTitle(quote);
        if(lot!=null) return lot;

        Set<String> titles = new LinkedHashSet<>();
        boolean explicit = false;
        for (String line : quote.split("\\r?\\n")) {
            String raw = line.trim();
            boolean heading = raw.matches("^#{1,6}\\s+.*");
            String value = raw.replaceFirst("^#{1,6}\\s+", "");
            boolean labelled = value.matches("^(?:合同名称|合同名|协议名称|协议名)\\s*[:：].*");
            value = value.replaceFirst("^(?:合同名称|合同名|协议名称|协议名)\\s*[:：]\\s*", "")
                .replaceAll("\\s+", "");
            if (!definiteTitle(value)) continue;
            titles.add(value);
            explicit |= heading || labelled;
        }
        if(!titles.isEmpty())return explicit && titles.size()==1?titles.iterator().next():"";
        // Some numbered body pages have no contract heading but explicitly print the project
        // name. Only use a single labelled project on this validated page for scheduling.
        Set<String> projects=new HashSet<>();
        Matcher named=Pattern.compile("(?m)^[ \\t]*(?:\\d+(?:\\.\\d+)*[、.)]?[ \\t]*)?(?:工程名称|项目名称)[ \\t]*[:：][ \\t]*([^\\r\\n|]+)").matcher(quote);
        while(named.find()){
            String project=named.group(1).trim();
            if(project.length()<8||project.length()>160||project.matches(".*(?:另行|以.+为准|填写|详见|待定|本合同|示例).*")
                    ||!project.matches(".*(?:项目|工程|中心|改造|装修).*"))return "";
            projects.add(project);
        }
        return projects.size()==1?"PROJECT_NAME:"+projects.iterator().next():"";
    }

    private static final Pattern LOT_HEADING=Pattern.compile("^#{1,6}\\s+([\\p{IsHan}A-Za-z0-9·—–－_、-]{6,140}(?:合同|协议))[（(]标段([1-9])[）)]"
        +"[—–－-][\\p{IsHan}A-Za-z0-9（）()·]{2,90}(?:有限责任公司|股份有限公司|有限公司)$");
    private static final Pattern LOT_SEPARATOR=Pattern.compile("^[①②③④⑤⑥⑦⑧⑨⑩]([^|]{6,150}(?:合同|协议))标段([一二三四五六七八九1-9])$");

    /** A supplier suffix is ambiguous by itself. A separate inventory marker on the very same
     * numbered original must independently name the identical project and lot. Scheduling only. */
    private static String pairedLotTitle(String quote) {
        String printed="",separator="",lot="",ord="";
        int headings=0,separators=0;
        boolean competing=false;
        for(String line:quote.split("\\r?\\n")){
            String trimmed=line.trim(),compact=trimmed.replaceAll("\\s+","");
            Matcher heading=LOT_HEADING.matcher(trimmed), marker=LOT_SEPARATOR.matcher(compact);
            if(heading.matches()){headings++;printed=heading.group(1);lot=heading.group(2);}
            else if(marker.matches()){separators++;separator=marker.group(1);ord=marker.group(2);}
            else if(compact.matches("^[①②③④⑤⑥⑦⑧⑨⑩].*") || compact.matches("^(?:合同名称|合同名|协议名称|协议名)[:：].*")
                    || definiteTitle(compact.replaceFirst("^#{1,6}",""))
                    || trimmed.matches("^#{1,6}\\s+.*(?:合同|协议).*")) competing=true;
        }
        if(headings==0)return null;
        if(competing||headings!=1||separators!=1||!ContractCandidateFields.sameTitle(printed,separator))return "";
        int chinese="一二三四五六七八九".indexOf(ord);
        return lot.equals(chinese<0?ord:String.valueOf(chinese+1))?"PAIRED_LOT_TITLE:"+printed:"";
    }

    private static boolean definiteTitle(String value) {
        if (value.length() < 8 || value.length() > 180
                || !value.matches("[\\p{IsHan}A-Za-z0-9（）()·—–－_、-]+(?:合同|协议)(?:[（(][^（）()]{1,30}[）)])?")) return false;
        if (Pattern.compile("^(?:本|该|此|上述|前述|关于|有关|请|参见|详见|参考|依据|根据|按照)|核对|另行|说明|提及|包括|涉及")
                .matcher(value).find()) return false;
        return !Arrays.asList("建设工程设计合同", "建设工程施工合同", "建设工程施工合同协议").contains(value);
    }

    private static String normalize(String value) { return value.replaceAll("[\\s\\p{Punct}\\p{P}]", "").toLowerCase(Locale.ROOT); }
    private static String text(Map<String,Object> value,String key) { return Objects.toString(value.get(key), ""); }
    private static int number(Object value) { try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return -1; } }
    private static List<String> strings(Object value) { List<String> result=new ArrayList<>(); if(value instanceof Collection<?>) for(Object item:(Collection<?>)value) result.add(String.valueOf(item)); return result; }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return value instanceof Map<?,?>?(Map<String,Object>)value:Collections.emptyMap(); }
}
