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
    record Selection(List<Fact> included,List<Fact> uncertain,List<Fact> excluded) {}
    record DateCheck(String status,Fact fact) {}
    private record Line(String text,Scope scope,String lot) {}
    private record Cached(WeakReference<Document> document,FactScopePolicy policy) {}
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
        for(Page page:document.pages()==null?List.<Page>of():document.pages()) {
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
                String explicitLot=lot(plain); if(!explicitLot.isBlank()) lot=explicitLot;
                indexed.add(new Line(raw,index?Scope.UNKNOWN:state,lot));
            }
            pages.put(page.number(),List.copyOf(indexed)); locationTypes.put(page.number(),page.locationType());carried=state;carriedLot=lot;
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
            Map<String,Object> projectScope=Set.of("projectName","projectNumber").contains(fact.field())
                ?sharedScope.currentEvidence(fact.evidence()):Map.of();
            String scopeStatus=Objects.toString(projectScope.get("status"),"");
            // CURRENT_DOCUMENT is only a carried outer scope. It cannot erase positive local
            // history/contract evidence; only a source-proven current project or bid-section
            // boundary can restore current scope after a historical attachment.
            boolean explicitCurrentScope=Set.of("CURRENT_PROJECT","CURRENT_BID_SECTION").contains(scopeStatus);
            boolean provenCurrent=Boolean.TRUE.equals(projectScope.get("verifiedCurrent"))
                &&(explicitCurrentScope||source.scope()!=Scope.HISTORY&&!HISTORY.matcher(quote).find());
            if("OTHER_PROJECT".equals(scopeStatus)&&source.scope()==Scope.CURRENT){
                // A differently named current bid form may itself contain the error being checked.
                // A foreign name alone is not proof that it is an unrelated historical attachment.
                uncertain.add(fact);continue;
            }
            if(Set.of("HISTORICAL_PROJECT","HISTORICAL_DOCUMENT","HISTORICAL_REGION","OTHER_PROJECT","NAVIGATION_ONLY").contains(scopeStatus)) { excluded.add(fact);continue; }
            if(!provenCurrent&&(source.scope()==Scope.HISTORY || HISTORY.matcher(quote).find())) { excluded.add(fact);continue; }
            String requiredLot=lot(Objects.toString(checkpoint.title(),""));
            if(requiredLot.isBlank()) requiredLot=lot(checkpoint.source()==null?"":Objects.toString(checkpoint.source().quote(),""));
            if(AMBIGUOUS_LOT.equals(requiredLot) || AMBIGUOUS_LOT.equals(source.lot())) { uncertain.add(fact);continue; }
            if(!source.lot().isBlank() || !requiredLot.isBlank()) {
                if(source.lot().isBlank() || requiredLot.isBlank()) { uncertain.add(fact);continue; }
                if(!source.lot().equals(requiredLot)) { excluded.add(fact);continue; }
            }
            boolean current=provenCurrent || source.scope()==Scope.CURRENT || quote.matches(".*(?:本次投标|本次招标|当前项目|本招标项目|本项目(?:投标|报价|总价|名称|编号)).*" );
            if(raw.matches("(?i)\\s*(?:缺省|未填写|不详|未知|无|0|X+|[-_—]+)\\s*") || META.matcher(quote).find()) { uncertain.add(fact);continue; }
            if(Set.of("projectName","projectNumber","companyName","creditCode").contains(fact.field()) && !EngineText.normalized(raw).equals(fact.normalizedValue())) { uncertain.add(fact);continue; }
            if(Set.of("projectName","projectNumber").contains(fact.field()) && !current) { uncertain.add(fact);continue; }
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
        return new Selection(List.copyOf(included),List.copyOf(uncertain),List.copyOf(excluded));
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
            else if(tail.stripLeading().matches("^(?:至|到|—|–|－|-|～|~).*")) return new DateCheck("UNRESOLVED",fact);
            // A later renewal/issue/application date must not replace the labelled endpoint.
            if(!end.equals(LocalDate.parse(fact.normalizedValue())) || start!=null && start.isAfter(end)) return new DateCheck("UNRESOLVED",fact);
            boolean invalid=start!=null && start.isAfter(deadline) || (strictlyAfter?!end.isAfter(deadline):end.isBefore(deadline));
            return new DateCheck(invalid?"EXPIRED":"VALID",fact);
        } catch(RuntimeException invalid) { return new DateCheck("UNRESOLVED",fact); }
    }
    private static LocalDate dateValue(String value) {
        if(Set.of("长期","永久").contains(value)) return LocalDate.of(9999,12,31);
        List<LocalDate> parsed=EngineText.dates(value); if(parsed.size()!=1) throw new IllegalArgumentException("invalid date");
        return parsed.get(0);
    }
    private Line source(Fact fact) {
        Evidence evidence=fact.evidence(); if(evidence==null || evidence.quote()==null || evidence.quote().isBlank() || fact.rawValue()==null || fact.rawValue().isBlank()) return null;
        if(!Objects.equals(evidence.locationType(),locationTypes.get(evidence.page()))) return null;
        List<Line> candidates=pages.get(evidence.page()); if(candidates==null) return null;
        Matcher anchor=Pattern.compile("p"+evidence.page()+"-l(\\d+)").matcher(Objects.toString(evidence.anchor(),""));
        if(anchor.matches()) {
            int at=Integer.parseInt(anchor.group(1))-1;
            if(at>=0 && at<candidates.size() && candidates.get(at).text().contains(evidence.quote()) && evidence.quote().contains(Objects.toString(fact.rawValue(),""))) return candidates.get(at);
        }
        List<Line> exact=candidates.stream().filter(line->line.text().contains(evidence.quote()) && evidence.quote().contains(Objects.toString(fact.rawValue(),""))).toList();
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
    private static String plain(String raw) { return raw.replaceAll("<[^>]*>"," ").replaceAll("[*#]","").strip(); }
}
