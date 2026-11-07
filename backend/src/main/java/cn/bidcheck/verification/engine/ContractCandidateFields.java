package cn.bidcheck.verification.engine;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Canonicalizes a single read identity field; it does not establish a complete contract. */
final class ContractCandidateFields {
    private ContractCandidateFields() {}

    static String identityAttribute(String field) {
        switch (field) {
            case "contractName": case "CONTRACT_NAME": case "合同名称": return "contractName";
            case "contractNumber": case "CONTRACT_NUMBER": case "合同编号": return "contractNumber";
            default: return "";
        }
    }

    static Map<String,Object> normalize(Map<String,Object> candidate, Map<String,Source> readSources) {
        if (!"CONTRACT".equals(str(candidate, "kind"))) return candidate;
        String attribute = identityAttribute(str(candidate, "field"));
        if (attribute.isEmpty()) return candidate;
        Object suppliedAttributes = candidate.get("attributes");
        if (!(suppliedAttributes instanceof Map<?,?>)) return candidate;
        Map<String,Object> attributes = new LinkedHashMap<>((Map<String,Object>) suppliedAttributes);
        if (!str(attributes, attribute).trim().isEmpty()) return candidate;

        // Cross-page record association still requires an explicit verified contract record.
        List<String> sourceIds = ProofVerifier.strings(candidate.get("sourceIds"));
        if (sourceIds.size() != 1) return candidate;
        Source source = readSources.get(sourceIds.get(0));
        String rawValue = str(candidate, "rawValue");
        if (source == null || clean(rawValue).isEmpty()
                || !clean(source.evidence().quote()).contains(clean(rawValue))
                || !identityValue(source.evidence().quote(), attribute, rawValue)
                || multipleIdentities(source.evidence().quote())) return candidate;

        attributes.put(attribute, rawValue);
        Map<String,Object> normalized = new LinkedHashMap<>(candidate);
        normalized.put("attributes", attributes);
        return normalized;
    }

    static String conflict(Fact fact) {
        String attribute = identityAttribute(fact.field());
        if (attribute.isEmpty()) return null;
        String supplied = str(fact.attributes(), attribute);
        if (!clean(supplied).isEmpty() && !("contractName".equals(attribute) ? sameTitle(supplied, fact.rawValue()) : clean(supplied).equals(clean(fact.rawValue()))))
            return "CONTRACT_IDENTITY_CONFLICT";

        return null;
    }


    static boolean sameTitle(String left, String right) {
        return !clean(left).isEmpty() && titleKey(left).equals(titleKey(right));
    }

    static boolean containsTitle(String quote, String title) {
        return !clean(title).isEmpty() && titleKey(quote).contains(titleKey(title));
    }

    private static String titleKey(String title) {
        // Only the printed dash between two full years is equivalent. Never alter numbers.
        return clean(title).replace('（', '(').replace('）', ')').replaceAll("(?<=20[0-9]{2})[—–－](?=20[0-9]{2})", "-");
    }

    /** Remove a redundant wire requirement, never supply an unobserved business value.
     * Explicit attributes always win and must pass the original conflict checks.
     * The Host still validates contract identity, amount basis/unit and date role. */
    static Map<String,Object> normalizeAtomic(Map<String,Object> atom, Map<String,Source> readSources) {
        // Never sanitize an illegal client key by removing our temporary identity discriminator.
        if (atom.containsKey("kind")) return atom;
        if (!(atom.get("attributes") instanceof Map<?,?>)) return atom;
        String field = str(atom, "field"), raw = str(atom, "rawValue");
        Map<String,Object> attributes = new LinkedHashMap<>((Map<String,Object>) atom.get("attributes"));
        if (attributes.containsKey(field) || clean(raw).isEmpty()) return atom;
        List<String> ids = ProofVerifier.strings(atom.get("sourceIds"));
        if (ids.isEmpty() || ids.stream().anyMatch(id -> !readSources.containsKey(id))
                || ids.stream().map(readSources::get).noneMatch(s -> ProofVerifier.contains(s.evidence().quote(), raw))) return atom;
        if (!identityAttribute(field).isEmpty()) {
            Map<String,Object> candidate = new LinkedHashMap<>(atom);
            candidate.put("kind", "CONTRACT");
            Map<String,Object> normalized = new LinkedHashMap<>(normalize(candidate, readSources));
            normalized.remove("kind");
            return normalized;
        }
        String value = raw;
        if ("signedDate".equals(field)) {
            java.time.LocalDate date = ProofVerifier.date(raw);
            if (date == null) return atom;
            value = date.toString();
        } else if ("amount".equals(field)) {
            if (!raw.matches("(?:[0-9]{1,3}(?:[,，][0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?")) return atom;
            value = ProofVerifier.decimal(raw).toPlainString();
        } else if (!java.util.Arrays.asList("party", "person", "personRole").contains(field)) return atom;
        attributes.put(field, value);
        Map<String,Object> normalized = new LinkedHashMap<>(atom);
        normalized.put("attributes", attributes);
        return normalized;
    }

    static String atomicIssue(Map<String,Object> atom) {
        Set<String> fields = new LinkedHashSet<>(java.util.Arrays.asList(
            "contractName", "contractNumber", "party", "person", "personRole", "signedDate", "amount"));
        String field = str(atom, "field");
        if (!fields.contains(field) || clean(str(atom, "rawValue")).isEmpty()
                || !(atom.get("sourceIds") instanceof List<?>)
                || ProofVerifier.strings(atom.get("sourceIds")).isEmpty()
                || !(atom.get("attributes") instanceof Map<?,?>)) return "CONTRACT_ATOMIC_FIELDS_INVALID";
        for (String key : atom.keySet())
            if (!java.util.Arrays.asList("field", "rawValue", "sourceIds", "attributes","recordId").contains(key))
                return "CONTRACT_ATOMIC_FIELDS_INVALID";
        Map<String,Object> attrs = (Map<String,Object>) atom.get("attributes");
        Set<String> allowed = new LinkedHashSet<>(java.util.Arrays.asList("contractName", "contractNumber", field));
        if ("amount".equals(field)) allowed.addAll(java.util.Arrays.asList("amountBasis", "unit", "taxBasis"));
        if ("person".equals(field) || "personRole".equals(field)) allowed.addAll(java.util.Arrays.asList("person", "personRole"));
        if (!allowed.containsAll(attrs.keySet()) || clean(str(attrs, field)).isEmpty())
            return "CONTRACT_ATOMIC_FIELDS_INVALID";
        String raw = str(atom, "rawValue"), value = str(attrs, field);
        boolean matches = "signedDate".equals(field) ? ProofVerifier.datePresent(raw, value)
            : "amount".equals(field) ? ProofVerifier.numberPresent(raw, value)
            : "contractName".equals(field) ? sameTitle(raw, value) : clean(raw).equals(clean(value));
        return matches ? null : "CONTRACT_ATOMIC_FIELDS_INVALID";
    }

    private static boolean identityValue(String quote, String attribute, String rawValue) {
        String value = clean(rawValue);
        if ("contractName".equals(attribute)) {
            if (value.length() < 4 || value.matches("^(?:本合同|本协议|该合同|该协议|此合同|此协议|详见|参见|上述|前述).*"))
                return false;
            if (!value.matches(".+(?:合同|协议)(?:[（(][^）)]+[）)])?")) return false;
            for (String line : quote.split("\\r?\\n")) {
                String title = clean(line).replaceFirst("^#{1,6}", "")
                    .replaceFirst("^[①②③④⑤⑥⑦⑧⑨⑩]", "")
                    .replaceFirst("^(?:合同名称|合同名)[:：]", "");
                if (title.equals(value)) return true;
                if (title.startsWith(value + "-") || title.startsWith(value + "—")) {
                    String suffix = title.substring(value.length() + 1);
                    // A company suffix is title typography, not proof that it signed the contract.
                    // Lots, years, batches and stages must remain part of the contract identity.
                    if (suffix.matches("[\\p{IsHan}A-Za-z0-9（）()·]{2,100}(?:有限责任公司|股份有限公司|有限公司|公司)"))
                        return true;
                }
            }
            return false;
        }
        // A purchase-order identifier cannot stand in for its referenced framework agreement.
        if (value.matches("(?i)^PO(?:[-_]|[0-9]).*")) return false;
        Matcher numbers = numberLabels().matcher(quote);
        while (numbers.find()) if (clean(numbers.group(1)).equals(value)) return true;
        return false;
    }
    private static Pattern numberLabels() {
        return Pattern.compile("(?:合同编号|合同号|协议编号|协议号|协议)\\s*[:：]\\s*\\[?([A-Za-z0-9][A-Za-z0-9_./-]*)");
    }

    private static boolean multipleIdentities(String quote) {
        Pattern names = Pattern.compile("(?:合同名称|合同名)\\s*[:：]\\s*([^\\r\\n]+)");
        return distinctValues(numberLabels(), quote) > 1 || distinctValues(names, quote) > 1;
    }

    private static int distinctValues(Pattern pattern, String quote) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(quote);
        while (matcher.find()) values.add(clean(matcher.group(1)));
        return values.size();
    }

    static Map<String,Object> contract() {
        return linked("kind", "CONTRACT", "identityAttributes", cn.bidcheck.compat.Java8Api.listOf("contractName", "contractNumber"),
            "singleFieldAliases", linked("contractName", cn.bidcheck.compat.Java8Api.listOf("contractName", "CONTRACT_NAME", "合同名称"),
                "contractNumber", cn.bidcheck.compat.Java8Api.listOf("contractNumber", "CONTRACT_NUMBER", "合同编号")),
            "otherAttributes", cn.bidcheck.compat.Java8Api.listOf("party", "person", "personRole", "signedDate", "amount", "amountBasis", "unit", "taxBasis"),
            "policy", "kind 使用 CONTRACT。完整合同记录须在 attributes 中提交 contractName 或 contractNumber，并按原文提交其他已查明属性。单字段别名仅在一份已读来源中逐字落地且无多合同歧义时补缺失的身份属性，不覆盖已有属性。身份事实不证明金额、日期、签约主体或履职，不代表业绩条件完成。跨页须分别证明同一合同，不能用申报表或 subject 代替合同身份。");
    }
}
