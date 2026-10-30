package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Separates an auditable obligation range from its original, potentially multi-obligation source row. */
final class RequirementScope {
    private static final Pattern BOUNDARY=Pattern.compile("\\s+/\\s+|[。；;\\n\\r]");
    private static final String LEGACY_CONDITIONAL_GUIDANCE="；按招标文件确认适用条件";
    record Selection(String original,String obligation,String reviewedExpected,int start,int end,String status,String issue,Map<String,Object> sourceLocation) {
        Selection(String original,String obligation,String reviewedExpected,int start,int end,String status,String issue){this(original,obligation,reviewedExpected,start,end,status,issue,Map.of());}
        boolean resolved(){return issue.isBlank();}
        Map<String,Object> map(){return linked("status",status,"sourceQuote",original,"reviewedExpected",reviewedExpected,
            "obligationSpans",resolved()&&!obligation.isBlank()?List.of(span()):List.of(),
            "contextOnly",!original.equals(obligation),"issue",issue,"offsetBasis",sourceLocation.getOrDefault("offsetBasis","SOURCE_QUOTE_UTF16"),
            "systemNotes","SYSTEM_GUIDANCE_SEPARATED".equals(status)?List.of(linked("text",LEGACY_CONDITIONAL_GUIDANCE.substring(1),"origin","LEGACY_GENERATED_CHECKPOINT_GUIDANCE","isBusinessObligation",false)):List.of());}
        private Map<String,Object> span(){Map<String,Object> span=new LinkedHashMap<>(sourceLocation);span.putAll(linked("sourceQuote",obligation,"start",start,"end",end));return span;}
    }
    static Selection resolve(Checkpoint cp,String original){
        original=Objects.toString(original,"");String expected=Objects.toString(cp.expected(),"");
        if(original.isBlank()){
            if(cp.source()!=null)return unresolved(original,expected,"检查项来源未对应冻结招标原文，不能从期望文本猜测来源范围");
            return new Selection(expected,expected,expected,0,expected.length(),"REVIEWED_REQUIREMENT","");
        }
        // Migrate only the exact old generator shape. A human edit, additional sentence or
        // source condition is not silently discarded; reviewedExpected always remains intact.
        if("conditionalMaterial".equals(cp.field())&&"SEMANTIC_RESPONSE".equals(cp.type())
            &&Objects.toString(cp.title(),"").equals("适用时提供："+original.strip())
            &&expected.equals(original.strip()+LEGACY_CONDITIONAL_GUIDANCE)){
            int start=original.indexOf(original.strip());
            return new Selection(original,original.strip(),expected,start,start+original.strip().length(),"SYSTEM_GUIDANCE_SEPARATED","");
        }
        // Typed scalar values are not prose excerpts. Their full source definition remains necessary.
        if("DATE_VALIDITY".equals(cp.type())&&scalar(expected)&&!original.matches("(?s).*(?:证书|证照|许可证|合格证|有效期|有效期限|有效截止).*"))
            return unresolved(original,expected,"有效期检查仅定位到参考时点，未明确需核验的证书或有效期业务范围；不能把日期来源当成证书义务");
        if(Set.of("VALUE_COMPARE","DATE_VALIDITY","ENTITY_FIELD_CONSISTENCY").contains(Objects.toString(cp.type(),""))&&scalar(expected))
            return new Selection(original,original,expected,0,original.length(),"TYPED_SOURCE","");
        String needle=expected.strip().replaceAll("[。；;]+$","");
        if(needle.isBlank())return unresolved(original,expected,"当前业务义务为空，尚不能确定需检查的原文范围");
        int[] match=uniqueRange(original,needle);
        if(match==null)return unresolved(original,expected,"当前期望未唯一映射到原始条款；保留原文和人工确认文本，需先明确义务范围");
        // Preserve the whole containing sentence: a substring must not erase a negation, alternative or precondition.
        int start=0,end=original.length();Matcher boundaries=BOUNDARY.matcher(original);
        while(boundaries.find()){
            if(boundaries.end()<=match[0])start=boundaries.end();
            else if(boundaries.start()>=match[1]){end=boundaries.group().matches("[。；;]")?boundaries.end():boundaries.start();break;}
        }
        while(start<end&&Character.isWhitespace(original.charAt(start)))start++;
        while(end>start&&Character.isWhitespace(original.charAt(end-1)))end--;
        return new Selection(original,original.substring(start,end),expected,start,end,start==0&&end==original.length()?"FULL_SOURCE":"EXACT_SPAN","");
    }
    private static Selection unresolved(String original,String expected,String issue){return new Selection(original,"",expected,-1,-1,"UNRESOLVED",issue);}
    private static boolean scalar(String expected){return expected.strip().matches("[0-9０-９.,，+%％\\-/年月日\\s]+")||expected.isBlank();}
    /** Word-table joins use a whitespace-delimited slash; it is layout, not a business token.
     * Keep original UTF-16 offsets and never normalize URL, fraction or unspaced alternative slashes. */
    private static int[] uniqueRange(String source,String needle){
        StringBuilder normalized=new StringBuilder();List<Integer> offsets=new ArrayList<>();
        for(int i=0;i<source.length();i++)if(!layoutCharacter(source,i)){normalized.append(source.charAt(i));offsets.add(i);}
        StringBuilder target=new StringBuilder();for(int i=0;i<needle.length();i++)if(!layoutCharacter(needle,i))target.append(needle.charAt(i));
        if(target.isEmpty())return null;int at=normalized.indexOf(target.toString());
        if(at<0||normalized.indexOf(target.toString(),at+1)>=0)return null;
        return new int[]{offsets.get(at),offsets.get(at+target.length()-1)+1};
    }
    private static boolean layoutCharacter(String text,int at){
        char c=text.charAt(at);
        if(Character.isWhitespace(c))return true;
        if(c!='/'||at==0||at+1>=text.length()||!Character.isWhitespace(text.charAt(at-1))||!Character.isWhitespace(text.charAt(at+1)))return false;
        String left=text.substring(0,at).stripTrailing(),right=text.substring(at+1).stripLeading();
        // Plain “A / B” can be an alternative. Only recognize the table-join shapes
        // emitted around a completed sentence or a named scoring-column heading.
        return !left.isEmpty()&&"。；;：:".indexOf(left.charAt(left.length()-1))>=0
                ||right.matches("(?s)^[（(](?:商务|技术|服务|价格)[）)].*");
    }
}
