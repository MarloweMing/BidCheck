package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Checks explicit connectors between uniquely located sibling quotes; never guesses a complex sentence's logic. */
final class RequirementLogic {
    private RequirementLogic(){}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Span{
        private final int start;
        private final int end;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Span(@com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end) { this.start = start; this.end = end; }
        public int start() { return start; }
        public int end() { return end; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Span that = (Span) other; return java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); return hash; }
        @Override public String toString() { return "Span[" + "start=" + start + ", end=" + end + "]"; }
}
    static String expected(String source,List<Rule> children){
        if(children.size()<2)return "";List<Span> spans=locate(source,children,false);
        if(spans.isEmpty()||overlap(spans))spans=locate(source,children,true);
        if(spans.isEmpty()||overlap(spans))return "";
        spans.sort(Comparator.comparingInt(Span::start));String found="";
        for(int i=1;i<spans.size();i++){
            Span before=spans.get(i-1),after=spans.get(i);if(before.end()>after.start())return "";
            String between=source.substring(before.end(),after.start()).replaceAll("\\s+","");String connector;
            if(between.matches("或|或者|或是"))connector="ANY";
            else if(between.matches("且|并且|以及|及|和"))connector="ALL";
            else return "";
            if(!found.isEmpty()&&!found.equals(connector))return "";found=connector;
        }
        if(source.matches("(?s).*(?:任一|任意一种|其中之一|至少一项|不得仅|不能仅|不可仅).*"))return "";
        return found;
    }
    private static List<Span> locate(String source,List<Rule> children,boolean materialNames){
        List<Span> spans=new ArrayList<>();
        for(Rule child:children){String quote=child.sourceQuote();
            if(materialNames){if(!cn.bidcheck.compat.Java8Api.setOf("MATERIAL", "CERTIFICATE", "DECLARATION", "SECTION").contains(child.predicate()))return cn.bidcheck.compat.Java8Api.listOf();
                quote=child.expected();if(cn.bidcheck.compat.Java8Api.isBlank(quote))quote=str(child.parameters(),"category");
                if(cn.bidcheck.compat.Java8Api.isBlank(quote)||!child.sourceQuote().contains(quote))return cn.bidcheck.compat.Java8Api.listOf();}
            int start=source.indexOf(quote);if(cn.bidcheck.compat.Java8Api.isBlank(quote)||start<0||source.indexOf(quote,start+1)>=0)return cn.bidcheck.compat.Java8Api.listOf();
            spans.add(new Span(start,start+quote.length()));
        }return spans;
    }
    private static boolean overlap(List<Span> spans){List<Span> sorted=cn.bidcheck.compat.Java8Api.toList(spans.stream().sorted(Comparator.comparingInt(Span::start)));for(int i=1;i<sorted.size();i++)if(sorted.get(i-1).end()>sorted.get(i).start())return true;return false;}
    static String issue(String source,String logic,List<Rule> children){
        String expected=expected(source,children);return !expected.isEmpty()&&!expected.equals(logic)?"原句连接词要求"+expected+"，不能解释为"+logic:"";
    }
    static String topExpected(Plan plan){for(String issue:plan.issues())if(issue.startsWith("TOP_LEVEL_LOGIC_SOURCE:"))return issue.substring("TOP_LEVEL_LOGIC_SOURCE:".length()).split("；",2)[0];return "";}
}
