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

    private record Cover(Page page, String project, String number, String bidder) { }

    private record Navigation(String label, String title, int page, Page source, String quote,
                              String path, String parentPath, String kind, int order) { }

    private record Proof(int first, int last, String project, String projectNumber, String bidder,
                         String sectionPath, List<Map<String, Object>> anchors) { }

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
            if (byPath.getOrDefault(entry.path(), List.of()).size() != 1 || excludedTitle(entry.title())) continue;
            if(certificateConsumer&&!entry.title().matches(".*(?:质量管理体系|职业健康安全管理体系|环境管理体系)认证(?:证书)?"))continue;
            Navigation parent = entry.parentPath().isBlank() ? null
                    : unique(byPath.getOrDefault(entry.parentPath(), List.of()));
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
                    || byPath.getOrDefault(next.path(), List.of()).size() != 1
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
                    cover.bidder(), entry.path(), List.copyOf(anchors));
            for (int number = proof.first(); number <= proof.last(); number++) {
                Proof existing = approved.get(number);
                if (existing == null || span(proof) < span(existing)) approved.put(number, proof);
            }
        }
    }

    Map<String, Object> currentEvidence(Page page, String quote) {
        if (page == null || !"PAGE".equals(page.locationType()) || quote == null || quote.isBlank()
                || !Objects.toString(page.text(), "").contains(quote)) return Map.of();
        Proof proof = approved.get(page.number());
        if (proof == null) return Map.of();
        return Map.of(
                "status", "CURRENT_BID_SECTION",
                "projectName", proof.project(),
                "projectNumber", proof.projectNumber(),
                "bidder", proof.bidder(),
                "sectionPath", proof.sectionPath(),
                "sectionStart", proof.first(),
                "sectionEnd", proof.last(),
                "anchors", proof.anchors(),
                "derivedBy", certificateConsumer?"SERVER_MAPPED_CERTIFICATE_SECTION_SCOPE":"SERVER_MAPPED_SECTION_SCOPE",
                "boundary", "仅证明目录映射且由下一同级正文限定的当前投标章节；不证明人员归属、证书有效、签章真实性或业务条件满足"
        );
    }

    private static Cover cover(Document document, Set<Integer> duplicatePages) {
        List<Cover> candidates = new ArrayList<>();
        for (Page page : document.pages().stream().filter(value -> "PAGE".equals(value.locationType()))
                .sorted(Comparator.comparingInt(Page::number)).limit(3).toList()) {
            if (duplicatePages.contains(page.number())) continue;
            String nativeText = nativeText(page.text());
            if (!nativeText.contains("投标文件") || looksLikeNavigation(nativeText) || !printedMatches(page)) continue;
            String project = coverProject(nativeText), number = one(PROJECT_NUMBER, clean(nativeText));
            String bidder = clean(one(BIDDER, nativeText));
            if (project.isBlank() || number.isBlank() || bidder.isBlank() || placeholder(bidder)) continue;
            candidates.add(new Cover(page, project, number, bidder));
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static List<Navigation> navigation(Document document, Set<Integer> duplicatePages) {
        List<Navigation> result = new ArrayList<>();
        String currentNumberedPath = "";
        int order = 0;
        for (Page page : document.pages().stream().filter(value -> "PAGE".equals(value.locationType()))
                .sorted(Comparator.comparingInt(Page::number)).limit(10).toList()) {
            if (duplicatePages.contains(page.number())) continue;
            String text = nativeText(page.text());
            if (!looksLikeNavigation(text) || !printedMatches(page)) continue;
            for (String rawLine : text.split("\\R")) {
                String line = rawLine.strip();
                Matcher matcher = NAVIGATION.matcher(line);
                if (!matcher.matches()) continue;
                String label = canonicalLabel(matcher.group(1));
                String title = matcher.group(2).strip();
                int target;
                try { target = Integer.parseInt(matcher.group(3)); }
                catch (NumberFormatException invalid) { continue; }
                String kind, parent, path;
                if (label.startsWith("(")) {
                    if (currentNumberedPath.isBlank()) continue;
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
        return List.copyOf(result);
    }

    private static boolean bodyMapped(Navigation navigation, Page page) {
        if (page == null || !printedMatches(page)) return false;
        String expected = heading(navigation.label() + navigation.title());
        return nativeText(page.text()).lines().map(String::strip).filter(line -> !line.isBlank())
                .limit(10).map(MappedSectionScope::heading).anyMatch(expected::equals);
    }

    private static boolean recognizedSamePageParent(Navigation parent,Navigation child,Page page){
        if(page==null||parent.page()!=child.page()||!"PAREN".equals(child.kind())||!bodyMapped(child,page))return false;
        String original=Objects.toString(page.text(),"");int marker=original.indexOf("【PDF识别文字】");
        if(marker<0||original.indexOf("【PDF识别文字】",marker+1)>=0)return false;
        List<String> recognized=original.substring(marker+"【PDF识别文字】".length()).lines().map(String::strip).filter(line->!line.isBlank())
                .map(line->line.replaceFirst("^[#*\\s]+","").replaceFirst("[*\\s]+$","")).map(MappedSectionScope::heading).toList();
        String expectedParent=heading(parent.label()+parent.title()),expectedChild=heading(child.label()+child.title());
        return recognized.size()>=2&&recognized.get(0).equals(expectedParent)&&recognized.get(1).equals(expectedChild)
                &&recognized.stream().filter(expectedParent::equals).count()==1&&recognized.stream().filter(expectedChild::equals).count()==1;
    }

    private static String recognizedHeadingQuote(Page page){
        String original=Objects.toString(page.text(),"");int marker=original.indexOf("【PDF识别文字】");if(marker<0)return "";
        String recognized=original.substring(marker+"【PDF识别文字】".length());Matcher lines=Pattern.compile("[^\\r\\n]+").matcher(recognized);int count=0;
        while(lines.find())if(!lines.group().isBlank()&&++count==2)return recognized.substring(0,lines.end());
        return "";
    }

    private static boolean printedMatches(Page page) {
        if (page == null || !"PAGE".equals(page.locationType())) return false;
        List<String> lines = nativeText(page.text()).lines().map(String::strip)
                .filter(line -> !line.isBlank()).toList();
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
        return text.contains("目录") || text.lines().filter(line -> line.matches(".*[.．…·]{3,}.*")).count() >= 2;
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
        return nativeText(page.text()).lines().map(String::strip).filter(line -> !line.isBlank())
                .limit(3).reduce("", (left, right) -> left.isBlank() ? right : left + "\n" + right);
    }

    private static Map<String, Object> anchor(Page page, String kind, String quote) {
        return Map.of("page", page.number(), "locationType", page.locationType(), "kind", kind,
                "quote", Objects.toString(quote, ""));
    }
}
