package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.bidcheck.verification.engine.VerificationTypes.clean;

/**
 * Proves that a bounded, numbered section belongs to the current bid document.
 *
 * <p>A table of contents is navigation only.  It becomes useful for scope recovery only when the
 * current cover supplies one project, project number and bidder, the navigation entry is unique,
 * both the selected entry and its next sibling map to physical body pages, every page in between
 * has an agreeing printed footer, and no historical/example/foreign-document marker occurs in the
 * bounded native or recognized text.  The proof establishes a document range only; it does not establish a
 * person-employer relation, certificate validity, seal authenticity or substantive compliance.</p>
 */
final class MappedSectionScope {
    private static final Pattern NAVIGATION = Pattern.compile(
            "^((?:[0-9]+(?:\\.[0-9]+)*[.、]?|[（(][0-9]+[）)]))\\s*"
                    + "([^\\n]{2,80}?)\\s*[.．…·]{3,}\\s*([0-9]+)\\s*$");
    private static final Pattern PROJECT_NUMBER = Pattern.compile(
            "(?:招标项目编号|项目编号)[:：][（(]?([A-Za-z0-9_-]{4,80})[）)]?");
    private static final Pattern BIDDER = Pattern.compile(
            "投标人名称\\s*[:：]\\s*[（(]?\\s*盖章\\s*[）)]?\\s*([^\\r\\n]{2,160})");
    private static final Pattern PROJECT_LABEL = Pattern.compile(
            "(?:项目名称|工程名称)[:：]([^\\n。；|]{6,200})");
    private static final Pattern OTHER_PROJECT_LETTER = Pattern.compile(
            "(?:关于贵公司|参加贵公司|参加贵单位)\\s*([^\\n。；]{8,220}?)的(?:招标|采购)文件");
    private static final Pattern ADVERSE = Pattern.compile(
            "历史项目|历史业绩|类似(?:项目)?业绩|已完成项目|主要工作经历|工作履历|合同协议书|"
                    + "填写示例|填写样例|示范文本|参考模板|仅供参考");
    private static final Pattern SPECIALIZED_SCOPE = Pattern.compile(
            "承诺|声明|投标函|授权委托|身份证明|人员|负责人|项目组|联合体协议");

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Cover {
        private final Page page;
        private final String project;
        private final String number;
        private final String bidder;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Cover(@com.fasterxml.jackson.annotation.JsonProperty("page") Page page, @com.fasterxml.jackson.annotation.JsonProperty("project") String project, @com.fasterxml.jackson.annotation.JsonProperty("number") String number, @com.fasterxml.jackson.annotation.JsonProperty("bidder") String bidder) { this.page = page; this.project = project; this.number = number; this.bidder = bidder; }
        public Page page() { return page; }
        public String project() { return project; }
        public String number() { return number; }
        public String bidder() { return bidder; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Cover that = (Cover) other; return java.util.Objects.equals(this.page, that.page) && java.util.Objects.equals(this.project, that.project) && java.util.Objects.equals(this.number, that.number) && java.util.Objects.equals(this.bidder, that.bidder); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.page); hash = 31 * hash + java.util.Objects.hashCode(this.project); hash = 31 * hash + java.util.Objects.hashCode(this.number); hash = 31 * hash + java.util.Objects.hashCode(this.bidder); return hash; }
        @Override public String toString() { return "Cover[" + "page=" + page + ", project=" + project + ", number=" + number + ", bidder=" + bidder + "]"; }
 }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Navigation {
        private final String label;
        private final String title;
        private final int page;
        private final Page source;
        private final String quote;
        private final String path;
        private final String parentPath;
        private final String kind;
        private final int order;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Navigation(@com.fasterxml.jackson.annotation.JsonProperty("label") String label, @com.fasterxml.jackson.annotation.JsonProperty("title") String title, @com.fasterxml.jackson.annotation.JsonProperty("page") int page, @com.fasterxml.jackson.annotation.JsonProperty("source") Page source, @com.fasterxml.jackson.annotation.JsonProperty("quote") String quote, @com.fasterxml.jackson.annotation.JsonProperty("path") String path, @com.fasterxml.jackson.annotation.JsonProperty("parentPath") String parentPath, @com.fasterxml.jackson.annotation.JsonProperty("kind") String kind, @com.fasterxml.jackson.annotation.JsonProperty("order") int order) { this.label = label; this.title = title; this.page = page; this.source = source; this.quote = quote; this.path = path; this.parentPath = parentPath; this.kind = kind; this.order = order; }
        public String label() { return label; }
        public String title() { return title; }
        public int page() { return page; }
        public Page source() { return source; }
        public String quote() { return quote; }
        public String path() { return path; }
        public String parentPath() { return parentPath; }
        public String kind() { return kind; }
        public int order() { return order; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Navigation that = (Navigation) other; return java.util.Objects.equals(this.label, that.label) && java.util.Objects.equals(this.title, that.title) && java.util.Objects.equals(this.page, that.page) && java.util.Objects.equals(this.source, that.source) && java.util.Objects.equals(this.quote, that.quote) && java.util.Objects.equals(this.path, that.path) && java.util.Objects.equals(this.parentPath, that.parentPath) && java.util.Objects.equals(this.kind, that.kind) && java.util.Objects.equals(this.order, that.order); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.label); hash = 31 * hash + java.util.Objects.hashCode(this.title); hash = 31 * hash + java.util.Objects.hashCode(this.page); hash = 31 * hash + java.util.Objects.hashCode(this.source); hash = 31 * hash + java.util.Objects.hashCode(this.quote); hash = 31 * hash + java.util.Objects.hashCode(this.path); hash = 31 * hash + java.util.Objects.hashCode(this.parentPath); hash = 31 * hash + java.util.Objects.hashCode(this.kind); hash = 31 * hash + java.util.Objects.hashCode(this.order); return hash; }
        @Override public String toString() { return "Navigation[" + "label=" + label + ", title=" + title + ", page=" + page + ", source=" + source + ", quote=" + quote + ", path=" + path + ", parentPath=" + parentPath + ", kind=" + kind + ", order=" + order + "]"; }
 }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Proof {
        private final int first;
        private final int last;
        private final String project;
        private final String projectNumber;
        private final String bidder;
        private final String sectionPath;
        private final List<Map<String, Object>> anchors;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Proof(@com.fasterxml.jackson.annotation.JsonProperty("first") int first, @com.fasterxml.jackson.annotation.JsonProperty("last") int last, @com.fasterxml.jackson.annotation.JsonProperty("project") String project, @com.fasterxml.jackson.annotation.JsonProperty("projectNumber") String projectNumber, @com.fasterxml.jackson.annotation.JsonProperty("bidder") String bidder, @com.fasterxml.jackson.annotation.JsonProperty("sectionPath") String sectionPath, @com.fasterxml.jackson.annotation.JsonProperty("anchors") List<Map<String, Object>> anchors) { this.first = first; this.last = last; this.project = project; this.projectNumber = projectNumber; this.bidder = bidder; this.sectionPath = sectionPath; this.anchors = anchors; }
        public int first() { return first; }
        public int last() { return last; }
        public String project() { return project; }
        public String projectNumber() { return projectNumber; }
        public String bidder() { return bidder; }
        public String sectionPath() { return sectionPath; }
        public List<Map<String, Object>> anchors() { return anchors; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Proof that = (Proof) other; return java.util.Objects.equals(this.first, that.first) && java.util.Objects.equals(this.last, that.last) && java.util.Objects.equals(this.project, that.project) && java.util.Objects.equals(this.projectNumber, that.projectNumber) && java.util.Objects.equals(this.bidder, that.bidder) && java.util.Objects.equals(this.sectionPath, that.sectionPath) && java.util.Objects.equals(this.anchors, that.anchors); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.first); hash = 31 * hash + java.util.Objects.hashCode(this.last); hash = 31 * hash + java.util.Objects.hashCode(this.project); hash = 31 * hash + java.util.Objects.hashCode(this.projectNumber); hash = 31 * hash + java.util.Objects.hashCode(this.bidder); hash = 31 * hash + java.util.Objects.hashCode(this.sectionPath); hash = 31 * hash + java.util.Objects.hashCode(this.anchors); return hash; }
        @Override public String toString() { return "Proof[" + "first=" + first + ", last=" + last + ", project=" + project + ", projectNumber=" + projectNumber + ", bidder=" + bidder + ", sectionPath=" + sectionPath + ", anchors=" + anchors + "]"; }
 }

    private final Map<Integer, Proof> approved = new HashMap<>();
    private final boolean certificateConsumer;

    MappedSectionScope(Document document) {
        this(document,false);
    }

    /** This resolver is never installed as the general document/person scope. */
    static MappedSectionScope forCertificateConsumer(Document document){return new MappedSectionScope(document,true);}

    private MappedSectionScope(Document document,boolean certificateConsumer) {
        this.certificateConsumer=certificateConsumer;
        if (document == null || !"BID".equalsIgnoreCase(document.role())
                || document.pages() == null || document.pages().isEmpty()) return;

        Map<Integer, Page> pages = new HashMap<>();
        Set<Integer> duplicatePages = new HashSet<>();
        for (Page page : document.pages()) {
            if (!"PAGE".equals(page.locationType())) continue;
            if (pages.putIfAbsent(page.number(), page) != null) duplicatePages.add(page.number());
        }

        Cover cover = cover(document, duplicatePages);
        if (cover == null) return;
        List<Navigation> navigation = navigation(document, duplicatePages);
        if (navigation.isEmpty()) return;

        Map<String, List<Navigation>> byPath = new LinkedHashMap<>();
        for (Navigation entry : navigation)
            byPath.computeIfAbsent(entry.path(), ignored -> new ArrayList<>()).add(entry);

        for (Navigation entry : navigation) {
            if (byPath.getOrDefault(entry.path(), cn.bidcheck.compat.Java8Api.listOf()).size() != 1 || excludedTitle(entry.title())) continue;
            if(certificateConsumer&&!entry.title().matches(".*(?:质量管理体系|职业健康安全管理体系|环境管理体系)认证(?:证书)?"))continue;
            Navigation parent = cn.bidcheck.compat.Java8Api.isBlank(entry.parentPath()) ? null
                    : unique(byPath.getOrDefault(entry.parentPath(), cn.bidcheck.compat.Java8Api.listOf()));
            boolean recognizedParent=certificateConsumer&&parent!=null&&recognizedSamePageParent(parent,entry,pages.get(parent.page()));
            if (parent != null && (excludedTitle(parent.title()) || !bodyMapped(parent, pages.get(parent.page()))&&!recognizedParent))
                continue;
            // A parenthesized child without a unique numbered parent is structurally ambiguous.
            if ("PAREN".equals(entry.kind()) && parent == null) continue;

            Navigation next = navigation.stream()
                    .filter(candidate -> candidate.order() > entry.order()
                            && candidate.parentPath().equals(entry.parentPath())
                            && candidate.kind().equals(entry.kind()))
                    .min(Comparator.comparingInt(Navigation::order)).orElse(null);
            if (next == null || next.page() <= entry.page()
                    || byPath.getOrDefault(next.path(), cn.bidcheck.compat.Java8Api.listOf()).size() != 1
                    || excludedTitle(next.title())) continue;

            Page first = pages.get(entry.page()), boundary = pages.get(next.page());
            if (!bodyMapped(entry, first) || !bodyMapped(next, boundary)) continue;
            if (parent != null && parent.page() > entry.page()) continue;

            boolean safe = true;
            for (int number = entry.page(); number < next.page(); number++) {
                Page page = pages.get(number);
                if (page == null || duplicatePages.contains(number) || !printedMatches(page)
                        || unsafeNative(page, cover.project(), cover.number())) {
                    safe = false;
                    break;
                }
            }
            if (!safe || duplicatePages.contains(next.page()) || !printedMatches(boundary)
                    || unsafeNative(boundary, cover.project(), cover.number())) continue;

            List<Map<String, Object>> anchors = new ArrayList<>();
            anchors.add(anchor(cover.page(), "CURRENT_COVER_PROJECT_NUMBER_BIDDER",
                    cover.project() + " / " + cover.number() + " / " + cover.bidder()));
            if (parent != null) {
                anchors.add(anchor(parent.source(), "UNIQUE_PARENT_NAVIGATION", parent.quote()));
                anchors.add(anchor(pages.get(parent.page()), recognizedParent?"MAPPED_PARENT_RECOGNIZED_TITLE_WITH_NATIVE_CHILD":"MAPPED_PARENT_BODY_TITLE",
                        recognizedParent?recognizedHeadingQuote(pages.get(parent.page())):headingQuote(pages.get(parent.page()))));
            }
            anchors.add(anchor(entry.source(), "UNIQUE_SECTION_NAVIGATION", entry.quote()));
            anchors.add(anchor(first, "MAPPED_SECTION_BODY_TITLE_AND_PRINTED_PAGE", headingQuote(first)));
            anchors.add(anchor(next.source(), "NEXT_SIBLING_NAVIGATION", next.quote()));
            anchors.add(anchor(boundary, "NEXT_SIBLING_BODY_BOUNDARY", headingQuote(boundary)));

            Proof proof = new Proof(entry.page(), next.page() - 1, cover.project(), cover.number(),
                    cover.bidder(), entry.path(), cn.bidcheck.compat.Java8Api.listCopyOf(anchors));
            for (int number = proof.first(); number <= proof.last(); number++) {
                Proof existing = approved.get(number);
                if (existing == null || span(proof) < span(existing)) approved.put(number, proof);
            }
        }
    }

    Map<String, Object> currentEvidence(Page page, String quote) {
        if (page == null || !"PAGE".equals(page.locationType()) || quote == null || cn.bidcheck.compat.Java8Api.isBlank(quote)
                || !Objects.toString(page.text(), "").contains(quote)) return cn.bidcheck.compat.Java8Api.mapOf();
        Proof proof = approved.get(page.number());
        if (proof == null) return cn.bidcheck.compat.Java8Api.mapOf();
        return cn.bidcheck.compat.Java8Api.mapOf("status", "CURRENT_BID_SECTION", "projectName", proof.project(), "projectNumber", proof.projectNumber(), "bidder", proof.bidder(), "sectionPath", proof.sectionPath(), "sectionStart", proof.first(), "sectionEnd", proof.last(), "anchors", proof.anchors(), "derivedBy", certificateConsumer?"SERVER_MAPPED_CERTIFICATE_SECTION_SCOPE":"SERVER_MAPPED_SECTION_SCOPE", "boundary", "仅证明目录映射且由下一同级正文限定的当前投标章节；不证明人员归属、证书有效、签章真实性或业务条件满足");
    }

    private static Cover cover(Document document, Set<Integer> duplicatePages) {
        List<Cover> candidates = new ArrayList<>();
        for (Page page : cn.bidcheck.compat.Java8Api.toList(document.pages().stream().filter(value -> "PAGE".equals(value.locationType()))
                .sorted(Comparator.comparingInt(Page::number)).limit(3))) {
            if (duplicatePages.contains(page.number())) continue;
            String nativeText = nativeText(page.text());
            if (!nativeText.contains("投标文件") || looksLikeNavigation(nativeText) || !printedMatches(page)) continue;
            String project = coverProject(nativeText), number = one(PROJECT_NUMBER, clean(nativeText));
            String bidder = clean(one(BIDDER, nativeText));
            if (cn.bidcheck.compat.Java8Api.isBlank(project) || cn.bidcheck.compat.Java8Api.isBlank(number) || cn.bidcheck.compat.Java8Api.isBlank(bidder) || placeholder(bidder)) continue;
            candidates.add(new Cover(page, project, number, bidder));
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static List<Navigation> navigation(Document document, Set<Integer> duplicatePages) {
        List<Navigation> result = new ArrayList<>();
        String currentNumberedPath = "";
        int order = 0;
        for (Page page : cn.bidcheck.compat.Java8Api.toList(document.pages().stream().filter(value -> "PAGE".equals(value.locationType()))
                .sorted(Comparator.comparingInt(Page::number)).limit(10))) {
            if (duplicatePages.contains(page.number())) continue;
            String text = nativeText(page.text());
            if (!looksLikeNavigation(text) || !printedMatches(page)) continue;
            for (String rawLine : text.split("\\R")) {
                String line = cn.bidcheck.compat.Java8Api.strip(rawLine);
                Matcher matcher = NAVIGATION.matcher(line);
                if (!matcher.matches()) continue;
                String label = canonicalLabel(matcher.group(1));
                String title = cn.bidcheck.compat.Java8Api.strip(matcher.group(2));
                int target;
                try { target = Integer.parseInt(matcher.group(3)); }
                catch (NumberFormatException invalid) { continue; }
                String kind, parent, path;
                if (label.startsWith("(")) {
                    if (cn.bidcheck.compat.Java8Api.isBlank(currentNumberedPath)) continue;
                    kind = "PAREN";
                    parent = currentNumberedPath;
                    path = parent + "/" + label;
                } else {
                    kind = "NUMBERED";
                    parent = numericParent(label);
                    path = label;
                    currentNumberedPath = path;
                }
                result.add(new Navigation(label, title, target, page, line, path, parent, kind, order++));
            }
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(result);
    }

    private static boolean bodyMapped(Navigation navigation, Page page) {
        if (page == null || !printedMatches(page)) return false;
        String expected = heading(navigation.label() + navigation.title());
        return cn.bidcheck.compat.Java8Extras.lines(nativeText(page.text())).map(cn.bidcheck.compat.Java8Api::strip).filter(line -> !cn.bidcheck.compat.Java8Api.isBlank(line))
                .limit(10).map(MappedSectionScope::heading).anyMatch(expected::equals);
    }

    private static boolean recognizedSamePageParent(Navigation parent,Navigation child,Page page){
        if(page==null||parent.page()!=child.page()||!"PAREN".equals(child.kind())||!bodyMapped(child,page))return false;
        String original=Objects.toString(page.text(),"");int marker=original.indexOf("【PDF识别文字】");
        if(marker<0||original.indexOf("【PDF识别文字】",marker+1)>=0)return false;
        List<String> recognized=cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(original.substring(marker+"【PDF识别文字】".length())).map(cn.bidcheck.compat.Java8Api::strip).filter(line->!cn.bidcheck.compat.Java8Api.isBlank(line))
                .map(line->line.replaceFirst("^[#*\\s]+","").replaceFirst("[*\\s]+$","")).map(MappedSectionScope::heading));
        String expectedParent=heading(parent.label()+parent.title()),expectedChild=heading(child.label()+child.title());
        return recognized.size()>=2&&recognized.get(0).equals(expectedParent)&&recognized.get(1).equals(expectedChild)
                &&recognized.stream().filter(expectedParent::equals).count()==1&&recognized.stream().filter(expectedChild::equals).count()==1;
    }

    private static String recognizedHeadingQuote(Page page){
        String original=Objects.toString(page.text(),"");int marker=original.indexOf("【PDF识别文字】");if(marker<0)return "";
        String recognized=original.substring(marker+"【PDF识别文字】".length());Matcher lines=Pattern.compile("[^\\r\\n]+").matcher(recognized);int count=0;
        while(lines.find())if(!cn.bidcheck.compat.Java8Api.isBlank(lines.group())&&++count==2)return recognized.substring(0,lines.end());
        return "";
    }

    private static boolean printedMatches(Page page) {
        if (page == null || !"PAGE".equals(page.locationType())) return false;
        List<String> lines = cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Extras.lines(nativeText(page.text())).map(cn.bidcheck.compat.Java8Api::strip)
                .filter(line -> !cn.bidcheck.compat.Java8Api.isBlank(line)));
        return !lines.isEmpty() && lines.get(lines.size() - 1).equals(Integer.toString(page.number()));
    }

    private static boolean unsafeNative(Page page, String project, String number) {
        // Native text remains authoritative for heading/footer mapping, but a conflicting project
        // label in OCR is still adverse evidence and must prevent mapped scope from overriding it.
        String value = Objects.toString(page.text(), "");
        if (ADVERSE.matcher(value).find()) return true;
        Matcher named = PROJECT_LABEL.matcher(value);
        while (named.find()) if (!sameProject(project, named.group(1))) return true;
        Matcher letter = OTHER_PROJECT_LETTER.matcher(value);
        while (letter.find()) if (!sameProject(project, letter.group(1))) return true;
        Matcher ids = PROJECT_NUMBER.matcher(clean(value));
        while (ids.find()) if (!ids.group(1).equals(number)) return true;
        return false;
    }

    private static boolean adverse(String title) {
        return ADVERSE.matcher(Objects.toString(title, "")).find();
    }

    private static boolean excludedTitle(String title) {
        String value = Objects.toString(title, "");
        return adverse(value) || SPECIALIZED_SCOPE.matcher(value).find();
    }

    private static boolean looksLikeNavigation(String text) {
        return text.contains("目录") || cn.bidcheck.compat.Java8Extras.lines(text).filter(line -> line.matches(".*[.．…·]{3,}.*")).count() >= 2;
    }

    private static String nativeText(String text) {
        String value = Objects.toString(text, "");
        int marker = value.indexOf("【PDF识别文字】");
        return marker < 0 ? value : value.substring(0, marker);
    }

    private static String coverProject(String text) {
        List<String> values = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String value = clean(line);
            if (value.matches("[（(].{8,200}(?:工程|项目)[）)]"))
                values.add(normalizeProject(value));
        }
        return values.stream().distinct().count() == 1 ? values.get(0) : "";
    }

    private static String normalizeProject(String text) {
        return clean(text).replaceAll("^[（(]|[）)]$", "")
                .replaceFirst("^(?:招标)?(?:项目|工程)名称[:：]?", "");
    }

    private static boolean sameProject(String left, String right) {
        return normalizeProject(left).equals(normalizeProject(right));
    }

    private static String one(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(Objects.toString(text, ""));
        if (!matcher.find()) return "";
        String value = matcher.group(1);
        return matcher.find() ? "" : value;
    }

    private static boolean placeholder(String value) {
        return value.matches("(?s).*(?:填写|示例|XX|××|__|待填).*" );
    }

    private static Navigation unique(List<Navigation> values) {
        return values.size() == 1 ? values.get(0) : null;
    }

    private static int span(Proof proof) {
        return proof.last() - proof.first();
    }

    private static String canonicalLabel(String label) {
        String value = clean(label).replace('（', '(').replace('）', ')');
        return value.startsWith("(") ? value : value.replaceFirst("[.、]$", "");
    }

    private static String numericParent(String label) {
        int dot = label.lastIndexOf('.');
        return dot < 0 ? "" : label.substring(0, dot);
    }

    private static String heading(String value) {
        return clean(value).replace("★", "").replace('（', '(').replace('）', ')')
                .replaceFirst("[.、]$", "");
    }

    private static String headingQuote(Page page) {
        return cn.bidcheck.compat.Java8Extras.lines(nativeText(page.text())).map(cn.bidcheck.compat.Java8Api::strip).filter(line -> !cn.bidcheck.compat.Java8Api.isBlank(line))
                .limit(3).reduce("", (left, right) -> cn.bidcheck.compat.Java8Api.isBlank(left) ? right : left + "\n" + right);
    }

    private static Map<String, Object> anchor(Page page, String kind, String quote) {
        return cn.bidcheck.compat.Java8Api.mapOf("page", page.number(), "locationType", page.locationType(), "kind", kind, "quote", Objects.toString(quote, ""));
    }
}
