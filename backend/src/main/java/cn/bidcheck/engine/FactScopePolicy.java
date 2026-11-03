package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.DocumentScopeProof;
import java.lang.ref.WeakReference;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Evaluation-only source guards. Stored extraction occurrences and OCR identities are unchanged. */
final class FactScopePolicy {
    enum Scope { CURRENT, HISTORY, UNKNOWN }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Selection {
        private final List<Fact> included;
        private final List<Fact> uncertain;
        private final List<Fact> excluded;
        @com.fasterxml.jackson.annotation.JsonCreator
        Selection(@com.fasterxml.jackson.annotation.JsonProperty("included") List<Fact> included, @com.fasterxml.jackson.annotation.JsonProperty("uncertain") List<Fact> uncertain, @com.fasterxml.jackson.annotation.JsonProperty("excluded") List<Fact> excluded) { this.included = included; this.uncertain = uncertain; this.excluded = excluded; }
        public List<Fact> included() { return included; }
        public List<Fact> uncertain() { return uncertain; }
        public List<Fact> excluded() { return excluded; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Selection that = (Selection) other; return java.util.Objects.equals(this.included, that.included) && java.util.Objects.equals(this.uncertain, that.uncertain) && java.util.Objects.equals(this.excluded, that.excluded); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.included); hash = 31 * hash + java.util.Objects.hashCode(this.uncertain); hash = 31 * hash + java.util.Objects.hashCode(this.excluded); return hash; }
        @Override public String toString() { return "Selection[" + "included=" + included + ", uncertain=" + uncertain + ", excluded=" + excluded + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class DateCheck {
        private final String status;
        private final Fact fact;
        @com.fasterxml.jackson.annotation.JsonCreator
        DateCheck(@com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("fact") Fact fact) { this.status = status; this.fact = fact; }
        public String status() { return status; }
        public Fact fact() { return fact; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; DateCheck that = (DateCheck) other; return java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.fact, that.fact); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.fact); return hash; }
        @Override public String toString() { return "DateCheck[" + "status=" + status + ", fact=" + fact + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Line {
        private final String text;
        private final Scope scope;
        private final String lot;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Line(@com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("scope") Scope scope, @com.fasterxml.jackson.annotation.JsonProperty("lot") String lot) { this.text = text; this.scope = scope; this.lot = lot; }
        public String text() { return text; }
        public Scope scope() { return scope; }
        public String lot() { return lot; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Line that = (Line) other; return java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.scope, that.scope) && java.util.Objects.equals(this.lot, that.lot); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.scope); hash = 31 * hash + java.util.Objects.hashCode(this.lot); return hash; }
        @Override public String toString() { return "Line[" + "text=" + text + ", scope=" + scope + ", lot=" + lot + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Cached {
        private final WeakReference<Document> document;
        private final FactScopePolicy policy;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Cached(@com.fasterxml.jackson.annotation.JsonProperty("document") WeakReference<Document> document, @com.fasterxml.jackson.annotation.JsonProperty("policy") FactScopePolicy policy) { this.document = document; this.policy = policy; }
        public WeakReference<Document> document() { return document; }
        public FactScopePolicy policy() { return policy; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Cached that = (Cached) other; return java.util.Objects.equals(this.document, that.document) && java.util.Objects.equals(this.policy, that.policy); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.document); hash = 31 * hash + java.util.Objects.hashCode(this.policy); return hash; }
        @Override public String toString() { return "Cached[" + "document=" + document + ", policy=" + policy + "]"; }
}
    // Identity comparison avoids a deep Document.hashCode scan for every checkpoint. Bounded and weakly owned.
    private static final Deque<Cached> CACHE=new ArrayDeque<>();
    private static final Pattern HISTORY=Pattern.compile("历史业绩|历史项目|类似业绩|业绩(?:合同|证明|材料|情况|汇总)|已完(?:工|成)项目|合同复印件|合同协议书|合同总价|合同价款|竣工结算|竣工验收|发包人.*承包人|甲方.*乙方");
    private static final Pattern CURRENT=Pattern.compile("投标文件封面|投标函|开标一览表|投标报价表|本项目报价|本项目汇总|本项目人员|拟投入人员|项目管理机构|授权委托书|法定代表人身份证明|投标人基本情况|本项目证件|本次投标.*证件|投标人资格证明");
    private static final Pattern LOT=Pattern.compile("第([\\p{L}0-9一二三四五六七八九十]+?)(?:标段|标包)|(?:标段|标包|包件|包号)\\s*[:：=]\\s*([\\p{L}0-9_-]{1,24})");
    private static final String AMBIGUOUS_LOT="\u0000";
    private static final Pattern META=Pattern.compile("可能为|若当前|当前实际时间|表明该|表明.*可能|图片中未|无法辨认|仅供参考|识别说明|以下是.*(?:识别|提取)");
    private static final String DATE=EngineText.DATE.pattern();
    private static final Pattern VALIDITY=Pattern.compile("(?:有效期限|有效期|有效截止(?:日期)?|到期日|有效至)(?:至|为)?[\\s*:：=]*((?:"+DATE+")|长期|永久)");
    private static final Pattern RANGE=Pattern.compile("^[\\s]*(?:至|到|—|–|－|-|～|~)[\\s]*((?:"+DATE+")|长期|永久)");
    private final Map<Integer,List<Line>> pages=new HashMap<>();
    private final Map<Integer,String> locationTypes=new HashMap<>();
    private final DocumentScopeProof.Resolver sharedScope;

    static synchronized FactScopePolicy of(Document document) {
        CACHE.removeIf(entry->entry.document().get()==null);
        for(Cached entry:CACHE) if(entry.document().get()==document) return entry.policy();
        FactScopePolicy policy=new FactScopePolicy(document);
        CACHE.addFirst(new Cached(new WeakReference<>(document),policy));
        while(CACHE.size()>8) CACHE.removeLast();
        return policy;
    }
    private FactScopePolicy(Document document) {
        sharedScope=DocumentScopeProof.forDocument(document);
        Scope carried=Scope.UNKNOWN; String carriedLot="";
        for(Page page:document.pages()==null?cn.bidcheck.compat.Java8Api.<Page>listOf():document.pages()) {
            List<String> text=EngineText.lines(page); List<Line> indexed=new ArrayList<>();
            Scope state="PAGE".equals(page.locationType()) && carried!=Scope.HISTORY?Scope.UNKNOWN:carried;
            String lot=carriedLot;
            String header=String.join(" ",text.subList(0,Math.min(text.size(),5)));
            // Do not treat a contents/index table mentioning all sections as a section boundary.
            boolean index=header.matches("(?s).*(目录|审查索引|评审索引|内容索引).*" );
            for(String raw:text) {
                String plain=plain(raw);
                boolean heading=plain.length()<160 && !plain.matches(".*[。；;].*");
                boolean explicitCurrent=plain.matches(".*(?:本次投标|本项目(?:证件|报价|人员|汇总)|当前项目|投标文件封面).*" );
                if(!index && heading && CURRENT.matcher(plain).find() && (state!=Scope.HISTORY || explicitCurrent)) { state=Scope.CURRENT;lot=""; }
                if(!index && HISTORY.matcher(plain).find()) state=Scope.HISTORY;
                String explicitLot=lot(plain); if(!cn.bidcheck.compat.Java8Api.isBlank(explicitLot)) lot=explicitLot;
                indexed.add(new Line(raw,index?Scope.UNKNOWN:state,lot));
            }
            pages.put(page.number(),cn.bidcheck.compat.Java8Api.listCopyOf(indexed)); locationTypes.put(page.number(),page.locationType());carried=state;carriedLot=lot;
        }
    }
    Selection select(Checkpoint checkpoint,List<Fact> facts) {
        List<Fact> included=new ArrayList<>(),uncertain=new ArrayList<>(),excluded=new ArrayList<>();
        for(Fact fact:facts) {
            if(!Objects.equals(checkpoint.field(),fact.field())) continue;
            Line source=source(fact); if(source==null) { uncertain.add(fact);continue; }
            String quote=plain(source.text()),raw=Objects.toString(fact.rawValue(),"");
            // Project identity uses the same original-source scope proof as typed/object checks.
            // A historical sticky heading must not discard a later independently proven current form.
            Map<String,Object> projectScope=cn.bidcheck.compat.Java8Api.setOf("projectName", "projectNumber").contains(fact.field())
                ?sharedScope.currentEvidence(fact.evidence()):cn.bidcheck.compat.Java8Api.mapOf();
            String scopeStatus=Objects.toString(projectScope.get("status"),"");
            // CURRENT_DOCUMENT is only a carried outer scope. It cannot erase positive local
            // history/contract evidence; only a source-proven current project or bid-section
            // boundary can restore current scope after a historical attachment.
            boolean explicitCurrentScope=cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "CURRENT_BID_SECTION").contains(scopeStatus);
            boolean provenCurrent=Boolean.TRUE.equals(projectScope.get("verifiedCurrent"))
                &&(explicitCurrentScope||source.scope()!=Scope.HISTORY&&!HISTORY.matcher(quote).find());
            if("OTHER_PROJECT".equals(scopeStatus)&&source.scope()==Scope.CURRENT){
                // A differently named current bid form may itself contain the error being checked.
                // A foreign name alone is not proof that it is an unrelated historical attachment.
                uncertain.add(fact);continue;
            }
            if(cn.bidcheck.compat.Java8Api.setOf("HISTORICAL_PROJECT", "HISTORICAL_DOCUMENT", "HISTORICAL_REGION", "OTHER_PROJECT", "NAVIGATION_ONLY").contains(scopeStatus)) { excluded.add(fact);continue; }
            if(!provenCurrent&&(source.scope()==Scope.HISTORY || HISTORY.matcher(quote).find())) { excluded.add(fact);continue; }
            String requiredLot=lot(Objects.toString(checkpoint.title(),""));
            if(cn.bidcheck.compat.Java8Api.isBlank(requiredLot)) requiredLot=lot(checkpoint.source()==null?"":Objects.toString(checkpoint.source().quote(),""));
            if(AMBIGUOUS_LOT.equals(requiredLot) || AMBIGUOUS_LOT.equals(source.lot())) { uncertain.add(fact);continue; }
            if(!cn.bidcheck.compat.Java8Api.isBlank(source.lot()) || !cn.bidcheck.compat.Java8Api.isBlank(requiredLot)) {
                if(cn.bidcheck.compat.Java8Api.isBlank(source.lot()) || cn.bidcheck.compat.Java8Api.isBlank(requiredLot)) { uncertain.add(fact);continue; }
                if(!source.lot().equals(requiredLot)) { excluded.add(fact);continue; }
            }
            boolean current=provenCurrent || source.scope()==Scope.CURRENT || quote.matches(".*(?:本次投标|本次招标|当前项目|本招标项目|本项目(?:投标|报价|总价|名称|编号)).*" );
            if(raw.matches("(?i)\\s*(?:缺省|未填写|不详|未知|无|0|X+|[-_—]+)\\s*") || META.matcher(quote).find()) { uncertain.add(fact);continue; }
            if(cn.bidcheck.compat.Java8Api.setOf("projectName", "projectNumber", "companyName", "creditCode").contains(fact.field()) && !EngineText.normalized(raw).equals(fact.normalizedValue())) { uncertain.add(fact);continue; }
            if(cn.bidcheck.compat.Java8Api.setOf("projectName", "projectNumber").contains(fact.field()) && !current) { uncertain.add(fact);continue; }
            if("totalPrice".equals(fact.field())) {
                String clause=amountClause(source.text(),raw);
                if(clause==null) { uncertain.add(fact);continue; }
                if(clause.matches("(?s).*(?:分项|单价|单位报价|小计|结算|预算|最高限价|控制价|暂列金额).*")) { excluded.add(fact);continue; }
                if(!current) { uncertain.add(fact);continue; }
                int at=source.text().indexOf(raw);
                String sourceUnit=FactExtractor.taxUnit(source.text(),at,at+raw.length());
                String value=Objects.toString(fact.normalizedValue(),"");
                String storedUnit=value.contains(":")?value.substring(0,value.indexOf(':')):"CNY";
                if(!sourceUnit.equals(storedUnit)) { uncertain.add(fact);continue; }
            }
            included.add(fact);
        }
        return new Selection(cn.bidcheck.compat.Java8Api.listCopyOf(included),cn.bidcheck.compat.Java8Api.listCopyOf(uncertain),cn.bidcheck.compat.Java8Api.listCopyOf(excluded));
    }
    DateCheck date(Fact fact,LocalDate deadline,boolean strictlyAfter) {
        Line source=source(fact);
        if(source==null) return new DateCheck("UNRESOLVED",fact);
        if(source.scope()==Scope.HISTORY || HISTORY.matcher(plain(source.text())).find()) return new DateCheck("EXCLUDED",fact);
        if(source.scope()!=Scope.CURRENT) return new DateCheck("UNRESOLVED",fact);
        String raw=Objects.toString(fact.rawValue(),"");
        if(META.matcher(plain(source.text())).find() || raw.contains("起始日期") || raw.contains("开始日期")) return new DateCheck("UNRESOLVED",fact);
        Matcher label=VALIDITY.matcher(raw);
        if(!label.find()) return new DateCheck("UNRESOLVED",fact);
        try {
            LocalDate first=dateValue(label.group(1)); LocalDate end=first,start=null;
            String tail=raw.substring(label.end()); Matcher range=RANGE.matcher(tail);
            if(range.find()) { start=first; end=dateValue(range.group(1)); }
            else if(cn.bidcheck.compat.Java8Api.stripLeading(tail).matches("^(?:至|到|—|–|－|-|～|~).*")) return new DateCheck("UNRESOLVED",fact);
            // A later renewal/issue/application date must not replace the labelled endpoint.
            if(!end.equals(LocalDate.parse(fact.normalizedValue())) || start!=null && start.isAfter(end)) return new DateCheck("UNRESOLVED",fact);
            boolean invalid=start!=null && start.isAfter(deadline) || (strictlyAfter?!end.isAfter(deadline):end.isBefore(deadline));
            return new DateCheck(invalid?"EXPIRED":"VALID",fact);
        } catch(RuntimeException invalid) { return new DateCheck("UNRESOLVED",fact); }
    }
    private static LocalDate dateValue(String value) {
        if(cn.bidcheck.compat.Java8Api.setOf("长期", "永久").contains(value)) return LocalDate.of(9999,12,31);
        List<LocalDate> parsed=EngineText.dates(value); if(parsed.size()!=1) throw new IllegalArgumentException("invalid date");
        return parsed.get(0);
    }
    private Line source(Fact fact) {
        Evidence evidence=fact.evidence(); if(evidence==null || evidence.quote()==null || cn.bidcheck.compat.Java8Api.isBlank(evidence.quote()) || fact.rawValue()==null || cn.bidcheck.compat.Java8Api.isBlank(fact.rawValue())) return null;
        if(!Objects.equals(evidence.locationType(),locationTypes.get(evidence.page()))) return null;
        List<Line> candidates=pages.get(evidence.page()); if(candidates==null) return null;
        Matcher anchor=Pattern.compile("p"+evidence.page()+"-l(\\d+)").matcher(Objects.toString(evidence.anchor(),""));
        if(anchor.matches()) {
            int at=Integer.parseInt(anchor.group(1))-1;
            if(at>=0 && at<candidates.size() && candidates.get(at).text().contains(evidence.quote()) && evidence.quote().contains(Objects.toString(fact.rawValue(),""))) return candidates.get(at);
        }
        List<Line> exact=cn.bidcheck.compat.Java8Api.toList(candidates.stream().filter(line->line.text().contains(evidence.quote()) && evidence.quote().contains(Objects.toString(fact.rawValue(),""))));
        return exact.size()==1?exact.get(0):null;
    }
    private static String lot(String text) {
        Matcher m=LOT.matcher(text); Set<String> values=new HashSet<>();
        while(m.find()) values.add(EngineText.normalized(m.group(1)==null?m.group(2):m.group(1)));
        return values.size()>1?AMBIGUOUS_LOT:values.isEmpty()?"":values.iterator().next();
    }
    private static String amountClause(String text,String raw) {
        int at=text.indexOf(raw); if(at<0 || text.indexOf(raw,at+raw.length())>=0) return null;
        int start=at,end=at+raw.length();
        while(start>0 && at-start<80 && "；;。\n".indexOf(text.charAt(start-1))<0) start--;
        while(end<text.length() && end-(at+raw.length())<60 && "；;。\n".indexOf(text.charAt(end))<0) end++;
        return plain(text.substring(start,end));
    }
    private static String plain(String raw) { return cn.bidcheck.compat.Java8Api.strip(raw.replaceAll("<[^>]*>"," ").replaceAll("[*#]","")); }
}
