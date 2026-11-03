package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.Pattern;

/** Source excerpts are relationship candidates, never verified facts or generated summaries. */
public final class SourceRelations {
    private static final Pattern COMPANY_SUFFIX = Pattern.compile("有限责任公司|股份有限公司|有限公司");
    private static final Pattern PRINTED_PAGE = Pattern.compile("第\\s*(\\d{1,3})\\s*页\\s*[（(]?\\s*共\\s*(\\d{1,3})\\s*页\\s*[）)]?");
    private static final Pattern SOCIAL_TITLE = Pattern.compile("[^\\r\\n]{0,40}社会保险个人权益记录[^\\r\\n]{0,50}|[^\\r\\n]{0,40}社会保险[^\\r\\n]{0,30}(?:缴费证明|缴费明细|参保证明)[^\\r\\n]{0,30}");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Link {
        private final String type;
        private final Evidence evidence;
        private final Map<String, Object> attributes;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Link(@com.fasterxml.jackson.annotation.JsonProperty("type") String type, @com.fasterxml.jackson.annotation.JsonProperty("evidence") Evidence evidence, @com.fasterxml.jackson.annotation.JsonProperty("attributes") Map<String, Object> attributes) { this.type = type; this.evidence = evidence; this.attributes = attributes; }
        public String type() { return type; }
        public Evidence evidence() { return evidence; }
        public Map<String, Object> attributes() { return attributes; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Link that = (Link) other; return java.util.Objects.equals(this.type, that.type) && java.util.Objects.equals(this.evidence, that.evidence) && java.util.Objects.equals(this.attributes, that.attributes); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.type); hash = 31 * hash + java.util.Objects.hashCode(this.evidence); hash = 31 * hash + java.util.Objects.hashCode(this.attributes); return hash; }
        @Override public String toString() { return "Link[" + "type=" + type + ", evidence=" + evidence + ", attributes=" + attributes + "]"; }
 }

    /** Bounded source context only: matching pagination is not a verified employer/person relationship. */
    static List<Link> supportingHeaders(Document document, List<Evidence> selected) {
        if (document == null || document.pages() == null || selected == null || selected.isEmpty()) return cn.bidcheck.compat.Java8Api.listOf();
        Set<Integer> selectedLocations = new HashSet<>();
        for (Evidence evidence : selected) if (evidence != null && document.id().equals(evidence.documentId())
                && "PAGE".equals(evidence.locationType())) selectedLocations.add(evidence.page());
        List<Link> found = new ArrayList<>();
        List<Page> pages = document.pages();
        for (int first = 0; first < pages.size() && found.size() < 8; first++) {
            Page header = pages.get(first);
            Pagination start = pagination(header);
            if (start == null || start.printed() != 1 || cn.bidcheck.compat.Java8Api.isBlank(start.unit())) continue;
            List<Page> group = new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(header));
            List<Integer> printed = new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(1));
            boolean omitted = false, invalid = false;
            int lastPrinted = 1;
            for (int offset = 1; offset < 32 && first + offset < pages.size() && lastPrinted < start.total(); offset++) {
                Page page = pages.get(first + offset);
                Pagination current = pagination(page);
                if (current == null || page.number() != header.number() + offset || current.total() != start.total()
                        || !current.title().equals(start.title()) || current.printed() <= lastPrinted
                        || (!cn.bidcheck.compat.Java8Api.isBlank(current.unit()) && !current.unit().equals(start.unit()))
                        || (!cn.bidcheck.compat.Java8Api.isBlank(current.queryId()) && !current.queryId().equals(start.queryId()))) { invalid = true; break; }
                omitted |= current.printed() > lastPrinted + 1;
                group.add(page); printed.add(current.printed()); lastPrinted = current.printed();
            }
            // Require a source first and last page. A partial/inconsistent group is not extended by guessing.
            if (invalid || lastPrinted != start.total() || group.size() < 2) continue;
            List<Integer> members = cn.bidcheck.compat.Java8Api.toList(group.stream().skip(1).map(Page::number).filter(selectedLocations::contains));
            if (members.isEmpty()) continue;
            Evidence source = socialHeaderEvidence(document, header);
            if (source == null) continue;
            int last = group.get(group.size() - 1).number();
            Map<String,Object> metadata = new LinkedHashMap<>();
            metadata.put("groupId", document.id() + ":social-pages-" + header.number() + "-" + last);
            metadata.put("headerLocation", header.number()); metadata.put("endLocation", last);
            metadata.put("memberLocations", members); metadata.put("printedPages", cn.bidcheck.compat.Java8Api.listCopyOf(printed));
            metadata.put("printedTotal", start.total()); metadata.put("omittedPrintedPages", omitted);
            metadata.put("membershipVerified", false);
            metadata.put("boundary", "仅为同标题、总页数一致、物理相邻且印刷页号递增的社保表头候选；"
                    + (omitted ? "印刷页存在缺页，" : "")
                    + "同份证明归属未自动确证。须同时引用表头与人员行，不能只凭页码推定缴纳主体、任职关系或签章真实性；无法核实时保留待核。");
            found.add(new Link("PAGINATED_SOCIAL_INSURANCE_HEADER", source, cn.bidcheck.compat.Java8Api.mapCopyOf(metadata)));
            first += group.size() - 1;
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(found);
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Pagination {
        private final int printed;
        private final int total;
        private final String title;
        private final String unit;
        private final String queryId;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Pagination(@com.fasterxml.jackson.annotation.JsonProperty("printed") int printed, @com.fasterxml.jackson.annotation.JsonProperty("total") int total, @com.fasterxml.jackson.annotation.JsonProperty("title") String title, @com.fasterxml.jackson.annotation.JsonProperty("unit") String unit, @com.fasterxml.jackson.annotation.JsonProperty("queryId") String queryId) { this.printed = printed; this.total = total; this.title = title; this.unit = unit; this.queryId = queryId; }
        public int printed() { return printed; }
        public int total() { return total; }
        public String title() { return title; }
        public String unit() { return unit; }
        public String queryId() { return queryId; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Pagination that = (Pagination) other; return java.util.Objects.equals(this.printed, that.printed) && java.util.Objects.equals(this.total, that.total) && java.util.Objects.equals(this.title, that.title) && java.util.Objects.equals(this.unit, that.unit) && java.util.Objects.equals(this.queryId, that.queryId); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.printed); hash = 31 * hash + java.util.Objects.hashCode(this.total); hash = 31 * hash + java.util.Objects.hashCode(this.title); hash = 31 * hash + java.util.Objects.hashCode(this.unit); hash = 31 * hash + java.util.Objects.hashCode(this.queryId); return hash; }
        @Override public String toString() { return "Pagination[" + "printed=" + printed + ", total=" + total + ", title=" + title + ", unit=" + unit + ", queryId=" + queryId + "]"; }
 }
    private static Pagination pagination(Page page) {
        if (page == null || !"PAGE".equals(page.locationType()) || page.text() == null || page.text().length() > 30_000) return null;
        String text = page.text();
        java.util.regex.Matcher pageMatcher = PRINTED_PAGE.matcher(text);
        int printed = 0, total = 0;
        while (pageMatcher.find()) {
            int p = Integer.parseInt(pageMatcher.group(1)), t = Integer.parseInt(pageMatcher.group(2));
            if (printed != 0 && (p != printed || t != total)) return null;
            printed = p; total = t;
        }
        if (printed < 1 || total < 2 || total > 100 || printed > total) return null;
        java.util.regex.Matcher title = SOCIAL_TITLE.matcher(text.substring(0, Math.min(1200, text.length())));
        if (!title.find()) return null;
        String key = normalized(title.group());
        String unit = labelledValue(text, "(?:缴费单位|参保单位|单位名称)");
        String queryId = labelledValue(text, "(?:查询流水号|业务流水号|证明编号)");
        if ("!AMBIGUOUS!".equals(unit) || "!AMBIGUOUS!".equals(queryId)) return null;
        return new Pagination(printed, total, key, unit, queryId);
    }

    private static String labelledValue(String text, String label) {
        java.util.regex.Matcher values = Pattern.compile(label + "[ \\t]*[:：][ \\t]*([^\\r\\n]{2,120})").matcher(text);
        String previous = "";
        while (values.find()) {
            String current = normalized(values.group(1));
            if (!previous.isEmpty() && !previous.equals(current)) return "!AMBIGUOUS!";
            previous = current;
        }
        return previous;
    }

    private static Evidence socialHeaderEvidence(Document document, Page page) {
        String text = page.text();
        java.util.regex.Matcher unit = Pattern.compile("(?:缴费单位|参保单位|单位名称)[ \\t]*[:：][ \\t]*[^\\r\\n]{2,120}").matcher(text);
        java.util.regex.Matcher title = SOCIAL_TITLE.matcher(text);
        if (!unit.find() || !title.find()) return null;
        int start = Math.max(0, Math.min(title.start(), unit.start()) - 60);
        int end = Math.min(text.length(), Math.max(unit.end() + 100, start + 600));
        if (end - start > 1800) return null;
        return new Evidence(document.id(), document.name(), page.number(), page.locationType(), text.substring(start, end),
                "p" + page.number() + "-c" + start, page.parser() != null && page.parser().contains("OCR") ? 0d : .75d);
    }

    public static List<Link> discover(Document document) {
        List<Link> links = new ArrayList<>();
        String project = "";
        Page cover = null;
        int visited = 0;
        for (Page page : document.pages()) {
            if (++visited > 6) break;
            String text = page.text() == null ? "" : page.text();
            if (!text.contains("投标文件") || text.length() > 3000) continue;
            for (String line : text.split("\\R")) {
                String value = cn.bidcheck.compat.Java8Api.strip(cn.bidcheck.compat.Java8Api.strip(line).replaceAll("^[（(]|[）)]$", "").replaceFirst("^(项目名称|工程名称)\\s*[:：]", ""));
                if (value.length() >= 6 && value.length() <= 160 && value.matches(".*(项目|工程)$")
                        && !value.matches(".*(编号|目录|历史|往年|业绩|负责人|投标文件).*")) {
                    project = value; cover = page; break;
                }
            }
            if (cover != null) break;
        }
        if (cover != null) {
            Evidence source = projectEvidence(document, cover, project, 900, false);
            if (source != null) links.add(new Link("PROJECT_CONTEXT", source, cn.bidcheck.compat.Java8Api.mapOf("projectNameCandidate", project)));
        }
        int examined = 0, consortium = 0;
        Set<Integer> used = new HashSet<>();
        for (int index = 0; index < document.pages().size(); index++) {
            Page page = document.pages().get(index);
            String text = page.text() == null ? "" : page.text();
            if ((examined += text.length()) > 2_000_000) break;
            if (used.contains(page.number()) || !text.contains("联合体协议书")) continue;
            // A known cover project must occur in the agreement; another project's agreement
            // is not eligible simply because it mentions the same company or staff member.
            if (!cn.bidcheck.compat.Java8Api.isBlank(project) && !normalized(text).contains(normalized(project))) continue;
            if (cn.bidcheck.compat.Java8Api.isBlank(project) && !text.matches("(?s).*(共同投标本项目|本招标项目|本项目联合体).*")) continue;
            Evidence source = cn.bidcheck.compat.Java8Api.isBlank(project) ? evidence(document, page, 1800) : projectEvidence(document, page, project, 1800, true);
            if (source == null || !source.quote().contains("联合体协议书")) continue;
            // If the full page matched outside the excerpt, do not publish a stronger relationship than the quote supports.
            if (cn.bidcheck.compat.Java8Api.isBlank(project) && !source.quote().matches("(?s).*(共同投标本项目|本招标项目|本项目联合体).*")) continue;
            boolean hasDuties = source.quote().matches("(?s).*(牵头人|牵头单位|职责分工|联合体成员).*" );
            Page continuation = null;
            if (!cn.bidcheck.compat.Java8Api.isBlank(project) && index + 1 < document.pages().size()) {
                Page next = document.pages().get(index + 1);
                if (next.number() == page.number() + 1 && Objects.equals(next.locationType(), page.locationType())
                        && safeContinuation(next, project)) continuation = next;
            }
            if (!hasDuties && continuation == null) continue;
            links.add(new Link("CONSORTIUM_MEMBERSHIP_AND_DUTIES", source, attributes(source, !cn.bidcheck.compat.Java8Api.isBlank(project), null)));
            used.add(page.number()); consortium++;
            if (continuation != null && consortium < 2) {
                Evidence continued = evidence(document, continuation, 1800);
                links.add(new Link("CONSORTIUM_MEMBERSHIP_AND_DUTIES", continued, attributes(continued, false, page.number())));
                used.add(continuation.number()); consortium++;
            }
            if (consortium >= 2) break;
        }
        return links;
    }

    private static Map<String, Object> attributes(Evidence evidence, boolean projectMatched, Integer precedingPage) {
        List<String> companies = new ArrayList<>();
        java.util.regex.Matcher matcher = Pattern.compile("[\\p{IsHan}A-Za-z0-9（）()·]{2,50}(?:有限责任公司|股份有限公司|有限公司)").matcher(evidence.quote());
        while (matcher.find() && companies.size() < 8) {
            String name = matcher.group();
            // A greedy OCR/name span can contain "A有限公司和B有限公司" or "A有限公司为B有限公司".
            // Do not promote several adjacent entities to one company attribute; the full quote remains unchanged.
            if (cn.bidcheck.compat.Java8Extras.matchCount(COMPANY_SUFFIX.matcher(name)) != 1) continue;
            if (!companies.contains(name)) companies.add(name);
        }
        Map<String,Object> attributes = new LinkedHashMap<>();
        attributes.put("projectMatchedToCover", projectMatched);
        attributes.put("memberNameCandidates", companies);
        if (precedingPage != null) attributes.put("adjacentAgreementTitleLocation", precedingPage);
        attributes.put("boundary", precedingPage == null
                ? "成员和职责以所引协议原文为准；名称摘录不确认法律身份或签章。"
                : "仅为紧邻已匹配项目协议标题的续页线索，须同时核对所指标题位置及本页原文，不自动确认主体关系或签章。");
        return cn.bidcheck.compat.Java8Api.mapCopyOf(attributes);
    }

    private static boolean safeContinuation(Page page, String project) {
        String text = page.text() == null ? "" : page.text();
        String excerpt = text.substring(0, Math.min(1800, text.length()));
        String opening = excerpt.substring(0, Math.min(350, excerpt.length()));
        if (opening.matches("(?s).*(联合体协议书|历史|往年|业绩|情况表|承诺书|投标函|授权委托|身份证明|营业执照|资质证书|合同协议书|技术建议书).*")) return false;
        if (opening.matches("(?s).*第[一二三四五六七八九十0-9]+[章节].*")) return false;
        for (String line : opening.split("\\R")) {
            String value = cn.bidcheck.compat.Java8Api.strip(line);
            if (value.matches(".*(?:项目名称|工程名称)\\s*[:：].*") && !normalized(value).contains(normalized(project))) return false;
            if (value.length() >= 6 && value.length() <= 160 && value.matches(".*(?:项目|工程)$")
                    && !normalized(value).contains(normalized(project))) return false;
        }
        return excerpt.matches("(?s).*(牵头人|牵头单位|联合体成员|职责分工).*" )
                && excerpt.matches("(?s).*(有限公司|有限责任公司|股份有限公司).*" );
    }

    private static Evidence projectEvidence(Document doc, Page page, String project, int maximum, boolean agreement) {
        String text = page.text() == null ? "" : page.text();
        String expression = normalized(project).codePoints().mapToObj(c -> Pattern.quote(new String(Character.toChars(c))))
                .collect(java.util.stream.Collectors.joining("[^\\p{L}\\p{N}]*"));
        java.util.regex.Matcher match = Pattern.compile(expression).matcher(text);
        if (!match.find()) return null;
        int start = match.start();
        if (agreement) {
            int title = text.indexOf("联合体协议书");
            if (title < 0) return null;
            start = Math.min(start, title);
            if (Math.max(match.end(), title + "联合体协议书".length()) - start > maximum) return null;
        }
        int end = Math.min(text.length(), start + maximum);
        String quote = text.substring(start, end);
        if (!normalized(quote).contains(normalized(project))) return null;
        return new Evidence(doc.id(), doc.name(), page.number(), page.locationType(), quote,
                "p" + page.number() + "-c" + start, page.parser() != null && page.parser().contains("OCR") ? 0d : 0.75d);
    }

    static Evidence evidence(Document doc, Page page, int maximum) {
        String text = page.text() == null ? "" : page.text();
        String quote = cn.bidcheck.compat.Java8Api.strip(text.substring(0, Math.min(maximum, text.length())));
        return new Evidence(doc.id(), doc.name(), page.number(), page.locationType(), quote,
                "p" + page.number() + "-l1", page.parser() != null && page.parser().contains("OCR") ? 0d : 0.75d);
    }

    static String normalized(String text) { return text.replaceAll("[^\\p{L}\\p{N}]", ""); }
}
