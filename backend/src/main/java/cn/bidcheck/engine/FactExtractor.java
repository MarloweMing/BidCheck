package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;

/** Extracts textual occurrences; never invents values for scanned images. */
public final class FactExtractor {
    public List<Fact> extract(Document document) {
        Objects.requireNonNull(document, "document");
        List<Fact> facts=new ArrayList<>();
        if(document.pages()==null) return facts;
        for(Page page: document.pages()) {
            List<String> lines=EngineText.lines(page);
            for(int index=0; index<lines.size(); index++) {
                String line=lines.get(index);
                Evidence source=EngineText.evidence(document,page,index+1,line);
                text(facts, source, line, "PROJECT", "projectName", "(?:项目名称|工程名称)\\s*[:：=]\\s*([^|\\t;；。，]+)");
                text(facts, source, line, "PROJECT", "projectName", "(?:本招标项目为|招标项目名称为|项目名称为)\\s*([^|\\t;；。，、]+)");
                text(facts, source, line, "PROJECT", "projectNumber", "(?:项目编号|招标编号|采购编号)\\s*[:：=]\\s*([A-Za-z0-9_./—-]+)");
                company(facts,source,lines,index,"companyName","(?:投标人(?:名称)?|投标单位(?:名称)?|响应人(?:名称)?|响应单位(?:名称)?|供应商(?:名称)?|企业名称|公司名称)\\s*[:：=]\\s*([^|\\t;；。]+)");
                company(facts,source,lines,index,"creditCode","(?:统一社会信用代码|信用代码)\\s*[:：=]?\\s*([A-Za-z0-9]{18})(?![A-Za-z0-9])");
                Matcher person=Pattern.compile("(项目负责人|项目经理|授权代表|被授权人)(?:姓名)?\\s*[:：=]\\s*([\\u4e00-\\u9fff·]{2,12})").matcher(line);
                while(person.find()) add(facts,"PERSON",person.group(1),"personName",person.group(2),EngineText.normalized(person.group(2)),source);
                numeric(facts,source,line,"bidValidity","投标有效期");
                numeric(facts,source,line,"duration","(?:总工期|项目工期|设计工期|施工工期|安装工期|调试工期|交付工期|计划工期|服务期限|工期|交货期)");
                numeric(facts,source,line,"warranty","(?:质保期|保修期|质量保证期)");
                Matcher price=Pattern.compile("(?:投标总报价|投标报价|报价总额|报价合计|总报价|投标总价|总价)\\s*(?:为|人民币|不含税|含税|未税|税前|税后|[:：=（(）)\\s])*"+EngineText.NUMBER+"\\s*(亿元|万元|元|万)?").matcher(line);
                while(price.find()) {
                    String unit=taxUnit(line,price.start(),price.end());
                    String value=EngineText.amount(price.group(1),price.group(2));
                    add(facts,"PRICE","PRICE","totalPrice",price.group(),"CNY".equals(unit)?value:unit+":"+value,source);
                }
                if(line.matches(".*(?:有效期|有效期限|有效截止|到期日|有效至).*") && !line.matches(".*(?:投标有效期|报价有效期).*")) {
                    Matcher expiry=Pattern.compile("(?:有效期限|有效期|有效截止(?:日期)?|到期日|有效至)(?:至|为)?\\s*[:：=]?\\s*([^;；。|]+)").matcher(line);
                    while(expiry.find()) {
                        String value=expiry.group(1).split("(?:签发日期|发证日期|颁发日期|签发时间|发证时间|证书编号)",2)[0];
                        Matcher date=EngineText.DATE.matcher(value); String normalized="UNRESOLVED";
                        // Preserve an invalid final date as unresolved; never substitute a valid start/issue date.
                        while(date.find()) { java.util.List<java.time.LocalDate> parsed=EngineText.dates(date.group()); normalized=parsed.isEmpty()?"UNRESOLVED":parsed.get(0).toString(); }
                        if(value.contains("长期") || value.contains("永久")) normalized="9999-12-31";
                        add(facts,"CERTIFICATE",source.anchor(),"certificateExpiry",expiry.group(),normalized,source);
                    }
                }
            }
        }
        List<LocatedLine> located=new ArrayList<>();
        for(Page page:document.pages()) { List<String> lines=EngineText.lines(page); for(int i=0;i<lines.size();i++) located.add(new LocatedLine(page,i+1,lines.get(i))); }
        for(int i=0;i<located.size();i++) vehicle(facts,document,located,i);
        return cn.bidcheck.compat.Java8Api.listCopyOf(facts);
    }
    private void text(List<Fact> facts, Evidence source, String line, String type, String field, String regex) {
        Matcher m=Pattern.compile(regex).matcher(line);
        while(m.find()) {
            String raw=m.group(1).trim();
            if("projectName".equals(field)) {
                raw=raw.split("\\s+/\\s+",2)[0].trim();
                if(raw.matches("^(?:详见|参见|见|同|以|填写|填入|待填).*" ) || raw.matches(".*(?:[（(\\[]\\s*(?:项目名称|工程名称|招标项目名称)\\s*[）)\\]]|须知前附表).*" ) || raw.matches("[\\s_—-]+") || raw.matches("(?i)(?:XX|XXX|某某)(?:项目|工程)?")) continue;
            }
            if("projectNumber".equals(field) && (raw.matches("(?i)X+") || !raw.matches(".*[A-Za-z0-9].*"))) continue;
            add(facts,type,type,field,raw,EngineText.normalized(raw),source);
        }
    }
    private void company(List<Fact> facts,Evidence source,List<String> lines,int index,String field,String regex) {
        String line=lines.get(index);
        Matcher matcher=Pattern.compile(regex).matcher(line);
        while(matcher.find()) {
            String scope=companyScope(line.substring(0,matcher.start(1)),String.join("\n",lines.subList(Math.max(0,index-2),index+1)));
            // A credit-code line may immediately follow its explicitly named subject. Generic names do not establish ownership.
            if(cn.bidcheck.compat.Java8Api.isBlank(scope) && "creditCode".equals(field) && index>0) scope=companyScope(lines.get(index-1),lines.get(index-1));
            if("THIRD_PARTY".equals(scope)) continue;
            String raw=matcher.group(1).trim(),key="BIDDER".equals(scope)?"BIDDER":"UNRESOLVED:"+source.anchor()+":"+matcher.start();
            add(facts,"COMPANY",key,field,raw,EngineText.normalized(raw),source);
        }
    }
    private String companyScope(String prefix,String context) {
        Matcher subject=Pattern.compile("投标人|投标单位|响应人|响应单位|响应供应商|供应商|甲方|业主|招标人|采购人|发包人|乙方|承包人").matcher(prefix);
        String role=""; while(subject.find()) role=subject.group();
        if(cn.bidcheck.compat.Java8Api.setOf("甲方", "业主", "招标人", "采购人", "发包人").contains(role)) return "THIRD_PARTY";
        if(cn.bidcheck.compat.Java8Api.setOf("投标人", "投标单位", "响应人", "响应单位", "响应供应商").contains(role)) return "BIDDER";
        if("供应商".equals(role) && !context.matches("(?s).*(合同|甲方|乙方|业绩).*") ) return "BIDDER";
        return "";
    }
    private void numeric(List<Fact> facts, Evidence source, String line, String field, String label) {
        Matcher m=Pattern.compile(label+"\\s*(?:[:：=为约是]|要求|不少于|不低于|不超过|不得少于|不得超过|自投标截止之日起|共计|合同签订后|合同签订之日起|\\s)*"+EngineText.NUMBER+"\\s*"+EngineText.TIME_UNIT).matcher(line);
        while(m.find()) {
            String actualField="duration".equals(field)?durationField(m.group()):field;
            add(facts,"TIME_REQUIREMENT","TIME_REQUIREMENT",actualField,m.group(),EngineText.time(m.group(1),m.group(2),actualField),source);
        }
    }
    static String durationField(String clause) {
        if(clause.startsWith("设计工期")) return "designDuration";
        if(clause.startsWith("施工工期")) return "constructionDuration";
        if(clause.startsWith("安装工期")) return "installationDuration";
        if(clause.startsWith("调试工期")) return "commissioningDuration";
        return "duration";
    }
    static String taxUnit(String line,int start,int end) {
        String prefix=line.substring(Math.max(0,start-12),start).replaceFirst("(?s)^.*[，,；;。\\t]","");
        String suffix=line.substring(end,Math.min(line.length(),end+20)).split("[，,；;。\\t]",2)[0];
        String context=prefix+line.substring(start,end)+suffix;
        if(context.matches(".*(?:不含税|未税|税前).*")) return "CNY_EXCLUSIVE";
        if(context.matches(".*(?:含税|税后).*")) return "CNY_INCLUSIVE";
        return "CNY";
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class LocatedLine {
        private final Page page;
        private final int line;
        private final String text;
        @com.fasterxml.jackson.annotation.JsonCreator
        private LocatedLine(@com.fasterxml.jackson.annotation.JsonProperty("page") Page page, @com.fasterxml.jackson.annotation.JsonProperty("line") int line, @com.fasterxml.jackson.annotation.JsonProperty("text") String text) { this.page = page; this.line = line; this.text = text; }
        public Page page() { return page; }
        public int line() { return line; }
        public String text() { return text; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; LocatedLine that = (LocatedLine) other; return java.util.Objects.equals(this.page, that.page) && java.util.Objects.equals(this.line, that.line) && java.util.Objects.equals(this.text, that.text); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.page); hash = 31 * hash + java.util.Objects.hashCode(this.line); hash = 31 * hash + java.util.Objects.hashCode(this.text); return hash; }
        @Override public String toString() { return "LocatedLine[" + "page=" + page + ", line=" + line + ", text=" + text + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Identifier {
        private final String value;
        private final int lineIndex;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Identifier(@com.fasterxml.jackson.annotation.JsonProperty("value") String value, @com.fasterxml.jackson.annotation.JsonProperty("lineIndex") int lineIndex) { this.value = value; this.lineIndex = lineIndex; }
        public String value() { return value; }
        public int lineIndex() { return lineIndex; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Identifier that = (Identifier) other; return java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.lineIndex, that.lineIndex); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.lineIndex); return hash; }
        @Override public String toString() { return "Identifier[" + "value=" + value + ", lineIndex=" + lineIndex + "]"; }
}
    private void vehicle(List<Fact> facts, Document doc, List<LocatedLine> located, int at) {
        LocatedLine current=located.get(at);
        int start=Math.max(0,at-6),end=Math.min(located.size(),at+7);
        while(start<at && !compatible(current.page(),located.get(start).page())) start++;
        while(end>at+1 && !compatible(current.page(),located.get(end-1).page())) end--;
        List<String> lines=cn.bidcheck.compat.Java8Api.toList(located.subList(start,end).stream().map(LocatedLine::text));
        int index=at-start; Page page=current.page(); String line=current.text();
        java.util.Optional<java.lang.String> plate=EngineText.match(EngineText.PLATE_PATTERN,line);
        // Only a record containing a plate starts an occurrence group; a certificate with no readable plate remains unverified.
        if((!plate.isPresent())) return;
        Matcher plateMatches=Pattern.compile(EngineText.PLATE_PATTERN).matcher(line);
        List<String> sameLinePlates=new ArrayList<>(); while(plateMatches.find()) sameLinePlates.add(plateMatches.group(1));
        if(sameLinePlates.size()>1) {
            // A flattened table row can contain several vehicles; proximity alone cannot assign their identifiers.
            for(int i=0;i<sameLinePlates.size();i++) add(facts,"VEHICLE","UNKNOWN:"+doc.id()+":"+page.number()+":"+current.line()+":"+i,"plateNumber",sameLinePlates.get(i),EngineText.normalized(sameLinePlates.get(i)),EngineText.evidence(doc,page,current.line(),line));
            return;
        }
        Identifier vinMatch=nearby(EngineText.VIN_PATTERN,lines,index),engineMatch=nearby(EngineText.ENGINE_PATTERN,lines,index);
        String vin=vinMatch.value(),engine=engineMatch.value();
        String key=!cn.bidcheck.compat.Java8Api.isBlank(vin)?"VIN:"+EngineText.normalized(vin):!cn.bidcheck.compat.Java8Api.isBlank(engine)?"ENGINE:"+EngineText.normalized(engine):"UNKNOWN:"+doc.id()+":"+page.number()+":"+current.line();
        String quote="PAGE".equals(page.locationType())?context(lines,index):line;
        Evidence ev=EngineText.evidence(doc,page,current.line(),quote);
        add(facts,"VEHICLE",key,"plateNumber",plate.get(),EngineText.normalized(plate.get()),ev);
        if(!cn.bidcheck.compat.Java8Api.isBlank(vin)) { LocatedLine location=located.get(start+vinMatch.lineIndex()); add(facts,"VEHICLE",key,"vin",vin,EngineText.normalized(vin),"PAGE".equals(page.locationType())?ev:EngineText.evidence(doc,location.page(),location.line(),location.text())); }
        if(!cn.bidcheck.compat.Java8Api.isBlank(engine)) { LocatedLine location=located.get(start+engineMatch.lineIndex()); add(facts,"VEHICLE",key,"engineNumber",engine,EngineText.normalized(engine),"PAGE".equals(page.locationType())?ev:EngineText.evidence(doc,location.page(),location.line(),location.text())); }
        EngineText.match("(?:所有人|车辆所有人)\\s*[:：=]\\s*([^|\\t;；]+)",quote).ifPresent(v -> add(facts,"VEHICLE",key,"owner",v,EngineText.normalized(v),ev));
    }
    private boolean compatible(Page a,Page b) { return a.number()==b.number() || (!"PAGE".equals(a.locationType()) && !"PAGE".equals(b.locationType())); }
    private Identifier nearby(String pattern,List<String> lines,int index) {
        java.util.Optional<java.lang.String> same=EngineText.match(pattern,lines.get(index));
        if(same.isPresent()) return new Identifier(same.get(),index);
        for(int offset=1;offset<=3;offset++) {
            // Stop at another plate to avoid borrowing identifiers from the next vehicle.
            for(int direction:new int[]{1,-1}) {
                int candidate=index+offset*direction;
                if(candidate<0 || candidate>=lines.size()) continue;
                boolean crossed=false;
                for(int j=index+direction; j!=candidate+direction; j+=direction) if(EngineText.match(EngineText.PLATE_PATTERN,lines.get(j)).isPresent()) { crossed=true; break; }
                if(crossed) continue;
                java.util.Optional<java.lang.String> found=EngineText.match(pattern,lines.get(candidate));
                if((!found.isPresent())) continue;
                boolean ambiguous=false;
                // An identifier equally close to another plate may belong to either record.
                for(int j=Math.max(0,candidate-offset);j<=Math.min(lines.size()-1,candidate+offset);j++) {
                    if(j!=index && EngineText.match(EngineText.PLATE_PATTERN,lines.get(j)).isPresent()) { ambiguous=true; break; }
                }
                if(!ambiguous) return new Identifier(found.get(),candidate);
            }
        }
        return new Identifier("",index);
    }
    private String context(List<String> lines,int index) {
        int end=index+1;
        while(end<Math.min(lines.size(),index+4) && (!EngineText.match(EngineText.PLATE_PATTERN,lines.get(end)).isPresent())) end++;
        int start=index;
        while(start>Math.max(0,index-3) && (!EngineText.match(EngineText.PLATE_PATTERN,lines.get(start-1)).isPresent())) start--;
        return String.join("\n",lines.subList(start,end));
    }
    private void add(List<Fact> facts,String type,String key,String field,String raw,String normalized,Evidence evidence) {
        facts.add(new Fact(type,key,field,raw,normalized,evidence));
    }
}
