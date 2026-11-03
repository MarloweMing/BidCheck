package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** Located tender context, not an automatic waiver of members' substantive obligations. */
final class TenderApplicabilityContext {
    private TenderApplicabilityContext() { }
    private static final int MAX_QUOTE = 1800, MAX_REFERENCES = 32, MAX_TEMPLATE_BLOCKS = 16;
    private static final Pattern NAMED_MATERIAL = Pattern.compile("《([^《》]{2,80}(?:承诺书|声明书|协议书|证明书|授权书|投标函))》");
    static final String BOUNDARY = "这些原文只补充本项适用规则和格式上下文，不直接证明投标响应。牵头方可签章只涉及签章主体，"
        + "不证明其他成员的实质资格、承诺事实或声明覆盖范围。联合体协议及格式明示各方签章、分别提交的例外必须保留；"
        + "未定位到例外不等于例外不存在。不得仅因未见另一成员单独材料就新增提交义务；新增各方分别提交/共同签章义务须引用明确适用于本材料的招标或格式原句。";
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Reference {
        private final String id;
        private final String purpose;
        private final Evidence evidence;
        @com.fasterxml.jackson.annotation.JsonCreator
        Reference(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("purpose") String purpose, @com.fasterxml.jackson.annotation.JsonProperty("evidence") Evidence evidence) { this.id = id; this.purpose = purpose; this.evidence = evidence; }
        public String id() { return id; }
        public String purpose() { return purpose; }
        public Evidence evidence() { return evidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Reference that = (Reference) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.purpose, that.purpose) && java.util.Objects.equals(this.evidence, that.evidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.purpose); hash = 31 * hash + java.util.Objects.hashCode(this.evidence); return hash; }
        @Override public String toString() { return "Reference[" + "id=" + id + ", purpose=" + purpose + ", evidence=" + evidence + "]"; }
 }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Context {
        private final List<Reference> references;
        private final Map<String,Object> audit;
        private final boolean overflow;
        @com.fasterxml.jackson.annotation.JsonCreator
        Context(@com.fasterxml.jackson.annotation.JsonProperty("references") List<Reference> references, @com.fasterxml.jackson.annotation.JsonProperty("audit") Map<String,Object> audit, @com.fasterxml.jackson.annotation.JsonProperty("overflow") boolean overflow) { this.references = references; this.audit = audit; this.overflow = overflow; }
        public List<Reference> references() { return references; }
        public Map<String,Object> audit() { return audit; }
        public boolean overflow() { return overflow; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Context that = (Context) other; return java.util.Objects.equals(this.references, that.references) && java.util.Objects.equals(this.audit, that.audit) && java.util.Objects.equals(this.overflow, that.overflow); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.references); hash = 31 * hash + java.util.Objects.hashCode(this.audit); hash = 31 * hash + java.util.Objects.hashCode(this.overflow); return hash; }
        @Override public String toString() { return "Context[" + "references=" + references + ", audit=" + audit + ", overflow=" + overflow + "]"; }

        static Context empty() { return new Context(cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.mapOf(), false); }
    }

    static Context collect(Checkpoint checkpoint, Document tender, boolean consortiumContext) {
        if (!consortiumContext || !verified(checkpoint == null ? null : checkpoint.source(), tender)) return Context.empty();
        String obligation = checkpoint.source().quote();
        if (!obligation.matches("(?s).*(?:承诺|声明|签章|签字|盖章|协议书|投标函).*")) return Context.empty();
        List<Reference> references = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> limitations = new ArrayList<>();
        boolean oversizedPolicy = false;
        List<Map<String,Object>> templates = new ArrayList<>();
        // Preserve the entire source block, including all exceptions. Never crop a clause into permission.
        for (Page page : tender.pages()) {
            String text = Objects.toString(page.text(), "");
            if (!text.contains("联合体") || !text.contains("牵头") || !text.matches("(?s).*(?:签字|签署|签章|盖章).*")) continue;
            if (ClaimGrounding.indexOnly(text)) continue;
            if (text.length() > MAX_QUOTE) { limitations.add("通用签章条款原块过长，未截断引用：" + page.number()); oversizedPolicy = true; continue; }
            add(references, seen, tender, page, "TENDER_CONSORTIUM_SIGNING_CONTEXT");
        }
        if (references.isEmpty() && limitations.isEmpty()) return Context.empty();
        Set<String> materials = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = NAMED_MATERIAL.matcher(obligation);
        while (matcher.find()) materials.add(matcher.group(1));
        for (String material : materials) {
            List<Integer> located = new ArrayList<>();
            for (int i = 0; i < tender.pages().size(); i++) {
                Page page = tender.pages().get(i);
                if (!titleMatches(page.text(), material)) continue;
                // Adjacent duplicate title paragraphs belong to the same located window.
                if (i > 0 && titleMatches(tender.pages().get(i - 1).text(), material)
                        && page.number() == tender.pages().get(i - 1).number() + 1) continue;
                located.add(page.number());
                List<String> ids = new ArrayList<>();
                String stop = "DOCUMENT_END";
                int previousNumber = page.number() - 1;
                for (int offset = 0; offset < MAX_TEMPLATE_BLOCKS && i + offset < tender.pages().size(); offset++) {
                    Page part = tender.pages().get(i + offset);
                    String text = Objects.toString(part.text(), "");
                    if (offset > 0 && part.number() != previousNumber + 1) { stop = "LOCATION_GAP"; break; }
                    if (offset > 0 && nextHeading(text, material)) { stop = "NEXT_HEADING"; break; }
                    if (text.length() > MAX_QUOTE) { stop = "BLOCK_TOO_LONG"; break; }
                    Reference ref = add(references, seen, tender, part, "TENDER_NAMED_MATERIAL_FORMAT");
                    ids.add(ref.id()); previousNumber = part.number();
                    if ("PAGE".equals(part.locationType())) { stop = "PAGE_BOUNDARY_UNRESOLVED"; break; }
                    if (offset == MAX_TEMPLATE_BLOCKS - 1) stop = "WINDOW_LIMIT";
                }
                templates.add(cn.bidcheck.compat.Java8Api.mapOf("material", material, "evidenceIds", ids, "stopReason", stop, "exceptionCoverageComplete", false, "scope", "LOCATED_FORMAT_CONTEXT_ONLY"));
            }
            if (located.isEmpty()) limitations.add("未定位对应格式正文：" + material);
        }
        boolean overflow = oversizedPolicy || references.size() > MAX_REFERENCES;
        if (overflow) limitations.add("适用规则及格式来源超过单次上下文限制，不能丢弃例外后判断");
        Map<String,Object> audit = new LinkedHashMap<>();
        audit.put("sourceRequirementVerified", true);
        audit.put("scope", "SIGNING_POLICY_AND_NAMED_FORMAT_CONTEXT_ONLY");
        audit.put("references", cn.bidcheck.compat.Java8Api.toList(references.stream().map(r -> cn.bidcheck.compat.Java8Api.mapOf("evidenceId", r.id(), "purpose", r.purpose(), "source", r.evidence()))));
        audit.put("formats", templates); audit.put("limitations", limitations);
        audit.put("exceptionCoverageComplete", false); audit.put("substantiveMemberCoverageProven", false);
        audit.put("boundary", BOUNDARY);
        return new Context(cn.bidcheck.compat.Java8Api.listCopyOf(references), Collections.unmodifiableMap(audit), overflow);
    }

    private static Reference add(List<Reference> refs, Set<String> seen, Document tender, Page page, String purpose) {
        String key = page.locationType() + ":" + page.number() + ":" + page.text();
        if (!seen.add(key)) return refs.stream().filter(r -> r.evidence().page() == page.number()
            && Objects.equals(r.evidence().locationType(), page.locationType()) && Objects.equals(r.evidence().quote(), page.text())).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        cn.bidcheck.domain.Domain.Evidence evidence = new Evidence(tender.id(), tender.name(), page.number(), page.locationType(), page.text(),
            "p" + page.number() + "-l1", Objects.toString(page.parser(), "").contains("OCR") ? 0d : .75d);
        cn.bidcheck.semantic.TenderApplicabilityContext.Reference ref = new Reference("A" + (refs.size() + 1), purpose, evidence);
        refs.add(ref); return ref;
    }

    static boolean verified(Evidence evidence, Document tender) {
        return evidence != null && tender != null && "TENDER".equals(tender.role()) && tender.pages() != null
            && Objects.equals(tender.id(), evidence.documentId()) && evidence.quote() != null && !cn.bidcheck.compat.Java8Api.isBlank(evidence.quote())
            && tender.pages().stream().anyMatch(p -> p.number() == evidence.page() && Objects.equals(p.locationType(), evidence.locationType())
                && p.text() != null && p.text().contains(evidence.quote()));
    }
    private static String title(String text) {
        return cn.bidcheck.compat.Java8Api.strip(Objects.toString(text, "")).replaceFirst("^[（(]?[0-9一二三四五六七八九十]+[）).、\\s]+", "")
            .replaceAll("[\\s《》]", "");
    }
    private static boolean titleMatches(String text, String material) {
        if (text == null || ClaimGrounding.indexOnly(text)) return false;
        return title(text.split("\\R", 2)[0]).equals(title(material));
    }
    private static boolean nextHeading(String text, String material) {
        if (text == null || titleMatches(text, material)) return false;
        String value = title(text);
        return text.length() < 90 && !text.matches("(?s).*[。；：:，,].*")
            && value.matches(".{2,70}(?:书|表|函|保证金|证明|文件|方案|清单|要求|目录)");
    }

    /** A missing member's copy is not a source for an obligation to submit that copy. */
    static String memberSubmissionIssue(Checkpoint checkpoint, JsonNode answer, Context context,
                                        Map<String,Evidence> available, Document tender) {
        if (context.audit().isEmpty()) return "";
        String explanation = answer.path("reason").asText() + "。" + answer.path("suggestion").asText();
        for (JsonNode claim : answer.path("claims")) explanation += "。" + claim.path("text").asText();
        if (formatUnclosed(context) && explanation.matches("(?s).*(?:无需|无须|不需要|免于).{0,40}(?:成员|各方).{0,40}(?:提交|提供|签署|签章|盖章).*|(?s).*(?:成员|各方).{0,40}(?:无需|无须|不需要|免于).{0,40}(?:提交|提供|签署|签章|盖章).*|(?s).*(?:格式|材料).{0,30}(?:不存在例外|没有例外|无其他例外).*"))
            return "对应格式原文尚未完整定位，不能据残余通用签章条款或格式片段认定没有例外、无需成员提交或签署。须先补核相关格式及例外范围；不表示原投标文件缺材料。";
        if (explicitMemberSubmission(checkpoint.source().quote())) return "";
        if (!assertsExtraSubmission(explanation)) return "";
        for (JsonNode claim : answer.path("claims")) {
            if (!"TENDER_REFERENCE".equals(claim.path("scope").asText())) continue;
            for (JsonNode id : claim.path("evidenceIds")) {
                Evidence source = available.get(id.asText());
                boolean namedFormat = context.references().stream().anyMatch(r -> r.id().equals(id.asText())
                    && r.purpose().equals("TENDER_NAMED_MATERIAL_FORMAT"));
                boolean requirement = source != null && source.equals(available.get("R0"));
                if (verified(source, tender) && (explicitMemberSubmission(source.quote())
                        || namedFormat && source.quote().matches("(?s).*成员[一二三四五六七八九十0-9]+.{0,15}(?:单位)?名称[（(](?:盖章|签章)[）)].*"))
                        && (namedFormat || requirement || appliesToMaterial(source.quote(), checkpoint.source().quote()))) return "";
            }
        }
        return "模型新增了联合体成员另行提交或共同签署本材料的义务，但未引用明确适用于本材料的招标或格式要求。"
            + "通用签章条款中的格式例外不能单独证明本材料属于该例外；应结合原文重核适用范围，不能据未见成员单独材料要求补交。"
            + "牵头签章许可也不自动证明其他成员的实质资格或声明内容已覆盖。";
    }
    private static boolean formatUnclosed(Context context) {
        Object formats = context.audit().get("formats"), limits = context.audit().get("limitations");
        if (limits instanceof List<?> && !((List<?>) (limits)).isEmpty()) return true;
        if (formats instanceof List<?>) for (Object entry : ((List<?>) (formats)))
            if (entry instanceof Map<?,?> && !"NEXT_HEADING".equals(((Map<?,?>) (entry)).get("stopReason"))) return true;
        return false;
    }
    private static boolean appliesToMaterial(String source, String requirement) {
        java.util.regex.Matcher matcher = NAMED_MATERIAL.matcher(requirement);
        boolean named = false;
        while (matcher.find()) { named = true; if (source.contains(matcher.group(1))) return true; }
        return !named || source.matches("(?s).*所有.{0,8}(?:承诺|声明).*");
    }
    private static boolean explicitMemberSubmission(String source) {
        for (String clause : scopeClauses(source)) {
            if (nonAssertedSubmission(clause)) continue;
            if (clause.matches("(?s).*除.{0,120}(?:各方|各成员).{0,80}(?:外|之外).{0,120}牵头.*")) continue;
            if (clause.matches("(?s).*(?:联合体各方|联合体各成员|所有成员|各成员|各方|双方).{0,60}(?:应|须|需|必须|要求).{0,25}(?:分别|单独|共同|均|各自).{0,20}(?:提供|提交|出具|签署|签字|盖章|签章).*")) return true;
            if (clause.matches("(?s).*(?:联合体各方|联合体各成员|所有成员|各成员|各方|双方).{0,30}(?:分别|单独|共同|均|各自).{0,15}(?:应|须|需|必须).{0,15}(?:提供|提交|出具|签署|签字|盖章|签章).*")) return true;
            if (clause.matches("(?s).*(?:联合体各方|联合体各成员|所有成员|各成员|各方|双方)(?:应|须|需|必须)?(?:分别|单独|共同|各自)(?:提供|提交|出具|签署|签字|盖章|签章).*")) return true;
        }
        return false;
    }
    private static boolean assertsExtraSubmission(String text) {
        for (String sentence : scopeClauses(text)) {
            if (nonAssertedSubmission(sentence)) continue;
            if (sentence.matches("(?s).*(?:不能|不应|不得|无需|不必|无须|不要求|不代表|是否|有待|待确认).{0,70}(?:补充|另行|分别|单独|共同|双方).*")) continue;
            if (explicitMemberSubmission(sentence)) return true;
            if (sentence.matches("(?s).*(?:建议|应|须|需|必须).{0,12}(?:补充|补交|另行提交).{0,30}(?:联合体|成员).{0,80}(?:承诺书|声明书|协议书).*")) return true;
            if (sentence.matches("(?s).*(?:建议|应|须|需|必须|由).{0,30}(?:联合体双方|联合体各方|各成员).{0,15}共同签署.{0,30}(?:承诺书|声明书|协议书).*")) return true;
        }
        return false;
    }
    private static String[] scopeClauses(String text) {
        // A negative observation never shields a later independent positive obligation.
        return text.split("[。；;，,\\n]|但是|然而|不过|但|而且|并且|因此|所以|故而|并(?=须|需|应|要求|必须|建议|补充)|且(?=须|需|应|要求|必须|建议|补充)");
    }
    private static boolean nonAssertedSubmission(String clause) {
        return clause.matches("(?s).*(?:无需|无须|不必|不需要|不得|并非要求|未(?:明确)?要求|没有(?:明确)?要求|未(?:明确)?规定|没有(?:明确)?规定|未(?:明确)?约定|没有(?:明确)?约定|是否).*")
            || clause.matches("(?s).*不能.{0,50}(?:要求|认定|断言|视为).*")
            || clause.matches("(?s).*若(?:后续|将来|以后).{0,25}(?:澄清|明确|补充规定).{0,30}(?:要求|规定).*")
            || clause.matches("(?s).*若.{0,20}(?:后续澄清|后续要求).{0,25}(?:才|则|再).*");
    }
}
