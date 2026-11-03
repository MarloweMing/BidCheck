package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Separates an auditable obligation range from its original, potentially multi-obligation source row. */
final class RequirementScope {
    private static final Pattern BOUNDARY=Pattern.compile("\\s+/\\s+|[。；;\\n\\r]");
    private static final String LEGACY_CONDITIONAL_GUIDANCE="；按招标文件确认适用条件";
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Selection {
        private final String original;
        private final String obligation;
        private final String reviewedExpected;
        private final int start;
        private final int end;
        private final String status;
        private final String issue;
        private final Map<String,Object> sourceLocation;
        @com.fasterxml.jackson.annotation.JsonCreator
        Selection(@com.fasterxml.jackson.annotation.JsonProperty("original") String original, @com.fasterxml.jackson.annotation.JsonProperty("obligation") String obligation, @com.fasterxml.jackson.annotation.JsonProperty("reviewedExpected") String reviewedExpected, @com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end, @com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("issue") String issue, @com.fasterxml.jackson.annotation.JsonProperty("sourceLocation") Map<String,Object> sourceLocation) { this.original = original; this.obligation = obligation; this.reviewedExpected = reviewedExpected; this.start = start; this.end = end; this.status = status; this.issue = issue; this.sourceLocation = sourceLocation; }
        public String original() { return original; }
        public String obligation() { return obligation; }
        public String reviewedExpected() { return reviewedExpected; }
        public int start() { return start; }
        public int end() { return end; }
        public String status() { return status; }
        public String issue() { return issue; }
        public Map<String,Object> sourceLocation() { return sourceLocation; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Selection that = (Selection) other; return java.util.Objects.equals(this.original, that.original) && java.util.Objects.equals(this.obligation, that.obligation) && java.util.Objects.equals(this.reviewedExpected, that.reviewedExpected) && java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.issue, that.issue) && java.util.Objects.equals(this.sourceLocation, that.sourceLocation); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.original); hash = 31 * hash + java.util.Objects.hashCode(this.obligation); hash = 31 * hash + java.util.Objects.hashCode(this.reviewedExpected); hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.issue); hash = 31 * hash + java.util.Objects.hashCode(this.sourceLocation); return hash; }
        @Override public String toString() { return "Selection[" + "original=" + original + ", obligation=" + obligation + ", reviewedExpected=" + reviewedExpected + ", start=" + start + ", end=" + end + ", status=" + status + ", issue=" + issue + ", sourceLocation=" + sourceLocation + "]"; }

        Selection(String original,String obligation,String reviewedExpected,int start,int end,String status,String issue){this(original,obligation,reviewedExpected,start,end,status,issue,cn.bidcheck.compat.Java8Api.mapOf());}
        boolean resolved(){return cn.bidcheck.compat.Java8Api.isBlank(issue);}
        Map<String,Object> map(){return linked("status",status,"sourceQuote",original,"reviewedExpected",reviewedExpected,
            "obligationSpans",resolved()&&!cn.bidcheck.compat.Java8Api.isBlank(obligation)?cn.bidcheck.compat.Java8Api.listOf(span()):cn.bidcheck.compat.Java8Api.listOf(),
            "contextOnly",!original.equals(obligation),"issue",issue,"offsetBasis",sourceLocation.getOrDefault("offsetBasis","SOURCE_QUOTE_UTF16"),
            "systemNotes","SYSTEM_GUIDANCE_SEPARATED".equals(status)?cn.bidcheck.compat.Java8Api.listOf(linked("text",LEGACY_CONDITIONAL_GUIDANCE.substring(1),"origin","LEGACY_GENERATED_CHECKPOINT_GUIDANCE","isBusinessObligation",false)):cn.bidcheck.compat.Java8Api.listOf());}
        private Map<String,Object> span(){Map<String,Object> span=new LinkedHashMap<>(sourceLocation);span.putAll(linked("sourceQuote",obligation,"start",start,"end",end));return span;}
    }
    static Selection resolve(Checkpoint cp,String original){
        original=Objects.toString(original,"");String expected=Objects.toString(cp.expected(),"");
        if(cn.bidcheck.compat.Java8Api.isBlank(original)){
            if(cp.source()!=null)return unresolved(original,expected,"检查项来源未对应冻结招标原文，不能从期望文本猜测来源范围");
            return new Selection(expected,expected,expected,0,expected.length(),"REVIEWED_REQUIREMENT","");
        }
        // Migrate only the exact old generator shape. A human edit, additional sentence or
        // source condition is not silently discarded; reviewedExpected always remains intact.
        if("conditionalMaterial".equals(cp.field())&&"SEMANTIC_RESPONSE".equals(cp.type())
            &&Objects.toString(cp.title(),"").equals("适用时提供："+cn.bidcheck.compat.Java8Api.strip(original))
            &&expected.equals(cn.bidcheck.compat.Java8Api.strip(original)+LEGACY_CONDITIONAL_GUIDANCE)){
            int start=original.indexOf(cn.bidcheck.compat.Java8Api.strip(original));
            return new Selection(original,cn.bidcheck.compat.Java8Api.strip(original),expected,start,start+cn.bidcheck.compat.Java8Api.strip(original).length(),"SYSTEM_GUIDANCE_SEPARATED","");
        }
        // Typed scalar values are not prose excerpts. Their full source definition remains necessary.
        if("DATE_VALIDITY".equals(cp.type())&&scalar(expected)&&!original.matches("(?s).*(?:证书|证照|许可证|合格证|有效期|有效期限|有效截止).*"))
            return unresolved(original,expected,"有效期检查仅定位到参考时点，未明确需核验的证书或有效期业务范围；不能把日期来源当成证书义务");
        if(cn.bidcheck.compat.Java8Api.setOf("VALUE_COMPARE", "DATE_VALIDITY", "ENTITY_FIELD_CONSISTENCY").contains(Objects.toString(cp.type(),""))&&scalar(expected))
            return new Selection(original,original,expected,0,original.length(),"TYPED_SOURCE","");
        String needle=cn.bidcheck.compat.Java8Api.strip(expected).replaceAll("[。；;]+$","");
        if(cn.bidcheck.compat.Java8Api.isBlank(needle))return unresolved(original,expected,"当前业务义务为空，尚不能确定需检查的原文范围");
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
    private static boolean scalar(String expected){return cn.bidcheck.compat.Java8Api.strip(expected).matches("[0-9０-９.,，+%％\\-/年月日\\s]+")||cn.bidcheck.compat.Java8Api.isBlank(expected);}
    /** Word-table joins use a whitespace-delimited slash; it is layout, not a business token.
     * Keep original UTF-16 offsets and never normalize URL, fraction or unspaced alternative slashes. */
    private static int[] uniqueRange(String source,String needle){
        StringBuilder normalized=new StringBuilder();List<Integer> offsets=new ArrayList<>();
        for(int i=0;i<source.length();i++)if(!layoutCharacter(source,i)){normalized.append(source.charAt(i));offsets.add(i);}
        StringBuilder target=new StringBuilder();for(int i=0;i<needle.length();i++)if(!layoutCharacter(needle,i))target.append(needle.charAt(i));
        if((target.length() == 0))return null;int at=normalized.indexOf(target.toString());
        if(at<0||normalized.indexOf(target.toString(),at+1)>=0)return null;
        return new int[]{offsets.get(at),offsets.get(at+target.length()-1)+1};
    }
    private static boolean layoutCharacter(String text,int at){
        char c=text.charAt(at);
        if(Character.isWhitespace(c))return true;
        if(c!='/'||at==0||at+1>=text.length()||!Character.isWhitespace(text.charAt(at-1))||!Character.isWhitespace(text.charAt(at+1)))return false;
        String left=cn.bidcheck.compat.Java8Api.stripTrailing(text.substring(0,at)),right=cn.bidcheck.compat.Java8Api.stripLeading(text.substring(at+1));
        // Plain “A / B” can be an alternative. Only recognize the table-join shapes
        // emitted around a completed sentence or a named scoring-column heading.
        return !left.isEmpty()&&"。；;：:".indexOf(left.charAt(left.length()-1))>=0
                ||right.matches("(?s)^[（(](?:商务|技术|服务|价格)[）)].*");
    }
}
