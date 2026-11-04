package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A section ends only at an explicit peer/parent heading, never at its own child heading. */
final class SectionCoverage {
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Extent{
        private final List<Source> sources;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private final List<Source> bodySources;
        private final boolean complete;
        private final String reason;
        @com.fasterxml.jackson.annotation.JsonCreator
        Extent(@com.fasterxml.jackson.annotation.JsonProperty("sources") List<Source> sources, @com.fasterxml.jackson.annotation.JsonProperty("complete") boolean complete, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason) { this.sources = sources; this.bodySources = sources; this.complete = complete; this.reason = reason; }
        public List<Source> sources() { return sources; }
        /** Internal bounded view; never inserted into corpus.byId or published as a new OCR source. */
        List<Source> bodySources() { return bodySources; }
        Extent(List<Source> sources, List<Source> bodySources, boolean complete, String reason) {
            this.sources = sources;
            this.bodySources = bodySources;
            this.complete = complete;
            this.reason = reason;
        }
        public boolean complete() { return complete; }
        public String reason() { return reason; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Extent that = (Extent) other; return java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.complete, that.complete) && java.util.Objects.equals(this.reason, that.reason); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.complete); hash = 31 * hash + java.util.Objects.hashCode(this.reason); return hash; }
        @Override public String toString() { return "Extent[" + "sources=" + sources + ", complete=" + complete + ", reason=" + reason + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Line{
        private final Page page;
        private final int start;
        private final int end;
        private final String text;
        @com.fasterxml.jackson.annotation.JsonCreator
        Line(@com.fasterxml.jackson.annotation.JsonProperty("page") Page page, @com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("text") String text) { this.page = page; this.start = start; this.end = end; this.text = text; }
        public Page page() { return page; }
        public int start() { return start; }
        public int end() { return end; }
        public String text() { return text; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Line that = (Line) other; return java.util.Objects.equals(this.page, that.page) && java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.text, that.text); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.page); hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.text); return hash; }
        @Override public String toString() { return "Line[" + "page=" + page + ", start=" + start + ", end=" + end + ", text=" + text + "]"; }
}
    static Extent locate(String title,EvidenceCorpus corpus){
        // Covers and review indexes have filled fields/rows rather than eighty characters of prose.
        // Use the same source-bound structural checks as retrieval; a directory mention is insufficient.
        if(!MaterialTitleAliases.volume(title).isEmpty()||MaterialTitleAliases.canonical(title).endsWith("索引表")){
            List<Source> structured=SubmittedMaterialStructure.locate(title,corpus);
            if(!structured.isEmpty()){
                for(Document doc:corpus.documents){
                    if(structured.stream().noneMatch(s->s.evidence().documentId().equals(doc.id())))continue;
                    if(doc.warnings()!=null&&doc.warnings().stream().anyMatch(w->w.contains("[OCR未完成]")&&doc.pages().stream().noneMatch(p->p.warning()!=null&&!cn.bidcheck.compat.Java8Api.isBlank(p.warning())&&w.endsWith(p.warning()))))return new Extent(structured,false,"文档有未能归属具体位置的图像未识别，无法确认章节覆盖完整");
                    if(doc.pages().stream().anyMatch(p->p.warning()!=null&&p.warning().contains("[OCR未完成]")&&structured.stream().anyMatch(s->s.evidence().documentId().equals(doc.id())&&s.evidence().page()==p.number()&&s.evidence().locationType().equals(p.locationType()))))return new Extent(structured,false,"章节区间含未识别的图像内容，无法确认章节覆盖完整");
                }
                return new Extent(structured,true,"已定位文书标题及有效封面字段、索引表行或章节正文");
            }
            return new Extent(structured,false,"尚未定位文书标题及有效内容");
        }
        if(title==null||title.length()<2||title.length()>80)return new Extent(cn.bidcheck.compat.Java8Api.listOf(),false,"章节名未明确");
        for(Document doc:corpus.documents){if(!"BID".equals(doc.role()))continue;List<Line> lines=new ArrayList<>();for(Page p:doc.pages()){String t=Objects.toString(p.text(),"");int start=0;for(String line:t.split("\n",-1)){lines.add(new Line(p,start,Math.min(t.length(),start+line.length()+1),line));start+=line.length()+1;}}
            for(int i=0;i<lines.size();i++){Line heading=lines.get(i);String h=cn.bidcheck.compat.Java8Api.strip(heading.text());int level=level(h);if(level<1||!MaterialTitleAliases.matches(title,h)||!MaterialTitleAliases.navigation(title)&&corpus.all.stream().anyMatch(s->s.evidence().documentId().equals(doc.id())&&s.evidence().page()==heading.page().number()&&s.index()))continue;
                int end=lines.size();
                int nestedChineseChapter = 0;
                for(int j=i+1;j<lines.size();j++) {
                    String nextText = cn.bidcheck.compat.Java8Api.strip(lines.get(j).text());
                    if (!MaterialTitleAliases.volume(nextText).isEmpty()) {
                        end = j;
                        break;
                    }
                    // A decimal material subsection may restart its internal chapters at one.
                    // A top-level numeric heading does not prove that a following chapter is its child.
                    int chapter = chineseChapter(nextText);
                    if (numericHeading(h).length > 1 && chapter == nestedChineseChapter + 1) {
                        nestedChineseChapter = chapter;
                        continue;
                    }
                    int next = level(nextText);
                    if (next > 0 && next <= level && endsSection(h, nextText, nestedChineseChapter)) {
                        end=j;
                        break;
                    }
                }
                List<Source> needed=new ArrayList<>();int chars=0;for(int j=i;j<end;j++){Line line=lines.get(j);chars+=clean(line.text()).length();for(Source s:corpus.all){if(s.id().startsWith("V")||!s.evidence().documentId().equals(doc.id())||s.evidence().page()!=line.page().number()||!s.evidence().locationType().equals(line.page().locationType()))continue;int[] range=range(s);if(range[0]<line.end()&&range[1]>line.start()&&!needed.contains(s))needed.add(s);}}
                List<Source> body = boundedSources(needed, lines.subList(i, end));
                if(chars<80)return new Extent(needed,body,false,"章节正文过短或仅标题");
                if(doc.warnings()!=null&&doc.warnings().stream().anyMatch(w->w.contains("[OCR未完成]")&&doc.pages().stream().noneMatch(p->p.warning()!=null&&!cn.bidcheck.compat.Java8Api.isBlank(p.warning())&&w.endsWith(p.warning()))))return new Extent(needed,body,false,"文档有未能归属具体位置的图像未识别，无法确认章节覆盖完整");
                if(lines.subList(i,end).stream().anyMatch(line->line.page().warning()!=null&&line.page().warning().contains("[OCR未完成]")))return new Extent(needed,body,false,"章节区间含未识别的图像内容，原生文字展开不代表章节完整读取");
                return new Extent(needed,body,!needed.isEmpty(),"原文章节区间已展开至同级/父级标题或文档末尾");
            }
        }return new Extent(cn.bidcheck.compat.Java8Api.listOf(),false,"尚未定位带明确层级的章节正文起点");}
    private static String[] numericHeading(String text) {
        Matcher matcher = Pattern.compile("^(\\d+(?:[.．]\\d+)*)(?:[、.．\\s]+)([^.．].*)").matcher(text);
        return matcher.matches() && text.length() < 100 ? matcher.group(1).split("[.．]") : new String[0];
    }

    private static int chineseChapter(String heading) {
        Matcher matcher = Pattern.compile("^第([一二三四五六七八九十百0-9]+)章.*").matcher(heading);
        if (!matcher.matches()) return 0;
        java.math.BigDecimal number = RequirementGrounding.number(matcher.group(1));
        return number == null ? 0 : number.intValue();
    }

    private static boolean endsSection(String heading, String next, int nestedChineseChapter) {
        String[] outer = numericHeading(heading);
        if (outer.length == 0 || nestedChineseChapter == 0) return true;
        // An explicit section below the current Chinese chapter is a child, not an outer peer.
        if (next.matches("^第[一二三四五六七八九十百0-9]+节.*")) return false;
        String[] candidate = numericHeading(next);
        if (candidate.length == 0) return true;
        // Only numbering tied to the current explicit chapter can restart. An unrelated 5.1
        // after chapter one, or any peer in the material's own 4.x namespace, ends the range.
        return !(candidate.length > 1 && candidate[0].equals(Integer.toString(nestedChineseChapter))
            && !candidate[0].equals(outer[0]));
    }

    private static List<Source> boundedSources(List<Source> sources, List<Line> lines) {
        List<Source> result = new ArrayList<>();
        for (Source source : sources) {
            int start = Integer.MAX_VALUE, end = -1;
            for (Line line : lines) {
                if (line.page().number() != source.evidence().page()
                        || !line.page().locationType().equals(source.evidence().locationType())) continue;
                start = Math.min(start, line.start());
                end = Math.max(end, line.end());
            }
            int[] anchor = range(source);
            int from = Math.max(start, anchor[0]), to = Math.min(end, anchor[1]);
            if (anchor[0] < 0 || from >= to) continue;
            String quote = source.evidence().quote().substring(from - anchor[0], to - anchor[0]);
            cn.bidcheck.domain.Domain.Evidence original = source.evidence();
            cn.bidcheck.domain.Domain.Evidence view = new cn.bidcheck.domain.Domain.Evidence(original.documentId(),
                original.documentName(), original.page(), original.locationType(), quote,
                "chars:" + from + "-" + to, original.confidence());
            result.add(new Source(source.id(), view, source.opening(), source.index(), source.historical(),
                source.documentHash(), source.documentVersion(), source.scopeContext()));
        }
        return result;
    }

    static boolean covered(Fact fact,EvidenceCorpus corpus,List<Source> selected){
        if(fact.sourceIds()==null||fact.sourceIds().isEmpty())return false;Set<String> documents=new HashSet<>();
        for(String id:fact.sourceIds()){Source source=corpus.byId.get(id);if(source==null)return false;documents.add(source.evidence().documentId());}
        if(documents.size()!=1)return false;
        Document document=corpus.documents.stream().filter(d->documents.contains(d.id())).findFirst().orElse(null);if(document==null)return false;
        Extent e=locate(fact.value(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(document)));Set<String> extentIds=new HashSet<>(cn.bidcheck.compat.Java8Api.toList(e.sources().stream().map(Source::id)));
        return e.complete()&&extentIds.containsAll(fact.sourceIds())&&new HashSet<>(cn.bidcheck.compat.Java8Api.toList(selected.stream().map(Source::id))).containsAll(extentIds);
    }
    static int[] range(Source s){Matcher m=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(s.evidence().anchor());return m.matches()?new int[]{Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))}:new int[]{-1,-1};}
    static int level(String h){if(h.matches("^第[一二三四五六七八九十百0-9]+章.*"))return 1;if(h.matches("^第[一二三四五六七八九十百0-9]+节.*"))return 2;Matcher m=Pattern.compile("^(\\d+(?:[.．]\\d+)*)(?:[、.．\\s]+)([^.．].*)").matcher(h);if(m.matches()&&h.length()<100)return m.group(1).split("[.．]").length;if(h.matches("^[一二三四五六七八九十]+[、.．].{2,80}"))return 1;if(h.matches("^[（(][一二三四五六七八九十0-9]+[）)].{2,80}"))return 2;return 0;}
    static String strip(String h){return cn.bidcheck.compat.Java8Api.strip(h.replaceFirst("^(?:第[一二三四五六七八九十百0-9]+[章节]|[一二三四五六七八九十]+[、.．]|[（(][一二三四五六七八九十0-9]+[）)]|\\d+(?:[.．]\\d+)*[、.．\\s]+)\\s*",""));}
}
