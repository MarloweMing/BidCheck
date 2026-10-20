package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Checks explicit connectors between uniquely located sibling quotes; never guesses a complex sentence's logic. */
final class RequirementLogic {
    private RequirementLogic(){}
    private record Span(int start,int end){}
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
            if(materialNames){if(!Set.of("MATERIAL","CERTIFICATE","DECLARATION","SECTION").contains(child.predicate()))return List.of();
                quote=child.expected();if(quote.isBlank())quote=str(child.parameters(),"category");
                if(quote.isBlank()||!child.sourceQuote().contains(quote))return List.of();}
            int start=source.indexOf(quote);if(quote.isBlank()||start<0||source.indexOf(quote,start+1)>=0)return List.of();
            spans.add(new Span(start,start+quote.length()));
        }return spans;
    }
    private static boolean overlap(List<Span> spans){List<Span> sorted=spans.stream().sorted(Comparator.comparingInt(Span::start)).toList();for(int i=1;i<sorted.size();i++)if(sorted.get(i-1).end()>sorted.get(i).start())return true;return false;}
    static String issue(String source,String logic,List<Rule> children){
        String expected=expected(source,children);return !expected.isEmpty()&&!expected.equals(logic)?"原句连接词要求"+expected+"，不能解释为"+logic:"";
    }
    static String topExpected(Plan plan){for(String issue:plan.issues())if(issue.startsWith("TOP_LEVEL_LOGIC_SOURCE:"))return issue.substring("TOP_LEVEL_LOGIC_SOURCE:".length()).split("；",2)[0];return "";}
}
