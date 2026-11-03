package cn.bidcheck.engine;

import java.util.*;
import java.util.regex.*;

/** Exact source slices for independently numbered certificate awards, never rewritten obligations. */
public final class ScoringSourceSegments {
    public static final String VERSION="scoring-source-segments-2-complete-rubric";
    private ScoringSourceSegments() {}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Span {
        private final String quote;
        private final int start;
        private final int end;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Span(@com.fasterxml.jackson.annotation.JsonProperty("quote") String quote, @com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end) { this.quote = quote; this.start = start; this.end = end; }
        public String quote() { return quote; }
        public int start() { return start; }
        public int end() { return end; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Span that = (Span) other; return java.util.Objects.equals(this.quote, that.quote) && java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.quote); hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); return hash; }
        @Override public String toString() { return "Span[" + "quote=" + quote + ", start=" + start + ", end=" + end + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Certificate {
        private final Span clause;
        private final Span proof;
        private final String legacyExpected;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Certificate(@com.fasterxml.jackson.annotation.JsonProperty("clause") Span clause, @com.fasterxml.jackson.annotation.JsonProperty("proof") Span proof, @com.fasterxml.jackson.annotation.JsonProperty("legacyExpected") String legacyExpected) { this.clause = clause; this.proof = proof; this.legacyExpected = legacyExpected; }
        public Span clause() { return clause; }
        public Span proof() { return proof; }
        public String legacyExpected() { return legacyExpected; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Certificate that = (Certificate) other; return java.util.Objects.equals(this.clause, that.clause) && java.util.Objects.equals(this.proof, that.proof) && java.util.Objects.equals(this.legacyExpected, that.legacyExpected); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.clause); hash = 31 * hash + java.util.Objects.hashCode(this.proof); hash = 31 * hash + java.util.Objects.hashCode(this.legacyExpected); return hash; }
        @Override public String toString() { return "Certificate[" + "clause=" + clause + ", proof=" + proof + ", legacyExpected=" + legacyExpected + "]"; }

        public String expected(){return clause.quote()+(proof==null?"":"\n"+proof.quote());}
    }
    /** Exact fingerprints of the two former, lossy generator projections. These are used
     * only to recognize old generated data, never as the new business requirement. */
    public static String legacyTitleAwardExpected(String source){
        String body=body(source);Matcher proof=Pattern.compile("(?:证明材料|证明文件要求)[:：]").matcher(body);
        if(!proof.find())return "";int proofAt=proof.start();if(proof.find())return "";
        String award=cn.bidcheck.compat.Java8Api.strip(body.substring(0,proofAt));
        if(!award.matches("(?s).*(?:具有|具备)[^。；;]*高级[^。；;]*职称得[0-9.]+分[，,]中级得[0-9.]+分[，,](?:未提交|未提供)(?:不得分|得0分)[。]?"))return "";
        int score=award.indexOf('得');if(score<0)return "";
        return cn.bidcheck.compat.Java8Api.strip(award.substring(0,score))+"。"+body.substring(proofAt);
    }
    public static String body(String source){return source.replaceFirst("\\t\\s*-?[0-9]+(?:\\.[0-9]+)?\\s*$","").replaceAll("\\s+/\\s+"," ");}
    public static String legacyTitle(String prefix,String source){String body=body(source);return prefix+body.substring(0,Math.min(45,body.length()));}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Rubric {
        private final Span introduction;
        private final List<Span> tiers;
        private final String legacyTopDescription;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Rubric(@com.fasterxml.jackson.annotation.JsonProperty("introduction") Span introduction, @com.fasterxml.jackson.annotation.JsonProperty("tiers") List<Span> tiers, @com.fasterxml.jackson.annotation.JsonProperty("legacyTopDescription") String legacyTopDescription) { this.introduction = introduction; this.tiers = tiers; this.legacyTopDescription = legacyTopDescription; }
        public Span introduction() { return introduction; }
        public List<Span> tiers() { return tiers; }
        public String legacyTopDescription() { return legacyTopDescription; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Rubric that = (Rubric) other; return java.util.Objects.equals(this.introduction, that.introduction) && java.util.Objects.equals(this.tiers, that.tiers) && java.util.Objects.equals(this.legacyTopDescription, that.legacyTopDescription); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.introduction); hash = 31 * hash + java.util.Objects.hashCode(this.tiers); hash = 31 * hash + java.util.Objects.hashCode(this.legacyTopDescription); return hash; }
        @Override public String toString() { return "Rubric[" + "introduction=" + introduction + ", tiers=" + tiers + ", legacyTopDescription=" + legacyTopDescription + "]"; }
}
    /** A whole table cell with an explicit continuation label and ordered scoring tiers.
     * An intervening paragraph or independent item is not a scoring continuation. */
    public static Optional<Rubric> rubric(String source){
        int end=source.length();Matcher total=Pattern.compile("\\t\\s*[0-9]+(?:\\.[0-9]+)?\\s*$").matcher(source);if(total.find())end=total.start();
        String text=source.substring(0,end);Matcher markers=Pattern.compile("(?:^|\\s+/\\s+)([A-ZＡ-Ｚ])[:：]").matcher(text);
        List<Integer> starts=new ArrayList<>();List<String> letters=new ArrayList<>();while(markers.find()){starts.add(markers.start(1));letters.add(markers.group(1));}
        if(starts.size()<3)return Optional.empty();
        for(int i=0;i<letters.size();i++)if(!letters.get(i).equals(String.valueOf((char)('A'+i))))return Optional.empty();
        Span intro=trim(source,0,starts.get(0));
        if(!intro.quote().matches("(?s)^根据(?:方案|施工组织设计)详细内容进行评分[^。；;]*[:：]$"))return Optional.empty();
        List<Span> tiers=new ArrayList<>();String top="";
        for(int i=0;i<starts.size();i++){
            Span tier=trim(source,starts.get(i),i+1<starts.size()?starts.get(i+1):end);
            String content=cn.bidcheck.compat.Java8Api.strip(tier.quote().substring(2));
            Matcher score=Pattern.compile("^(.*?)(?:得|计|获)(?:[（(]?[0-9.,，]+[）)\\]]?|[0-9.]+)分[。；;]?$",Pattern.DOTALL).matcher(content);
            if(!score.matches()&&!content.matches("(?s)^.+不得分[。；;]?$"))return Optional.empty();
            if(i==0){if(!score.matches())return Optional.empty();top=cn.bidcheck.compat.Java8Api.strip(score.group(1));}
            tiers.add(tier);
        }
        return Optional.of(new Rubric(intro,cn.bidcheck.compat.Java8Api.listCopyOf(tiers),top));
    }
    public static List<String> repeatedPlanItems(String source){
        Matcher list=Pattern.compile("完整性[:：](.+?)[0-9一二三四五六七八九十]+项内容").matcher(source);
        if(!list.find())return cn.bidcheck.compat.Java8Api.listOf();Set<String> seen=new HashSet<>(),repeated=new LinkedHashSet<>();
        for(String item:list.group(1).split("、")){String value=cn.bidcheck.compat.Java8Api.strip(item);if(!seen.add(value))repeated.add(value);}
        return cn.bidcheck.compat.Java8Api.listCopyOf(repeated);
    }
    public static List<Certificate> certificates(String source){
        Matcher proofMarker=Pattern.compile("(?:证明材料|证明文件要求)[:：]").matcher(source);
        int end=source.length();Matcher total=Pattern.compile("\\t\\s*-?[0-9]+(?:\\.[0-9]+)?\\s*$").matcher(source);if(total.find())end=total.start();
        Span proof=null;int clauseEnd=end;
        if(proofMarker.find()){clauseEnd=proofMarker.start();proof=trim(source,proofMarker.start(),end);if(proofMarker.find())return cn.bidcheck.compat.Java8Api.listOf();}
        Matcher numbered=Pattern.compile("[（(][0-9一二三四五六七八九十]+[）)]").matcher(source.substring(0,clauseEnd));
        List<Integer> starts=new ArrayList<>();while(numbered.find())starts.add(numbered.start());
        if(!starts.isEmpty()){
            String prefix=source.substring(0,starts.get(0));
            // Only a short table label can be omitted. Ancestor conditions, alternatives,
            // combined score caps and unclassified prose apply to every numbered branch.
            if(!cn.bidcheck.compat.Java8Api.isBlank(prefix)&&(!prefix.matches("[^\\n。；;，,：:]{1,80}\\t\\s*")
                    ||prefix.matches("(?s).*(?:仅|若|如|当|须|应|需|不|或|任选|任一|其一|至少|最多|上限|合计|总分|满分).*")))return cn.bidcheck.compat.Java8Api.listOf();
        }
        if(starts.isEmpty())starts.add(0);
        List<Certificate> result=new ArrayList<>();
        for(int i=0;i<starts.size();i++){
            Span part=trim(source,starts.get(i),i+1<starts.size()?starts.get(i+1):clauseEnd);String text=part.quote();
            if(!text.matches("(?s).*(?:证书|认证).*")||!text.matches("(?s).*(?:具备|具有|提供|获得|取得).*")||!text.matches("(?s).*(?:得|计|获)[^分]*分.*"))continue;
            String requirement=cn.bidcheck.compat.Java8Api.strip(text.replaceFirst("(?s)^.*?(?:具备|具有|提供|获得|取得)(?:有效的?|的)?\\s*",""));
            requirement=requirement.replaceFirst("[，,]?\\s*(?:可?得|计|获)[^分]*分.*$","").replaceFirst("的?[，,；;。]+$","").replaceFirst("证书的$","证书");
            String subject=cn.bidcheck.compat.Java8Api.strip(text.replaceFirst("^(?:[\\s/]*[（(][0-9一二三四五六七八九十]+[）)])?\\s*","").split("(?:具备|具有|提供|获得|取得)",2)[0]);
            if(subject.matches("(?:施工单位|设计单位|联合体牵头人|联合体成员|项目负责人|设计负责人)"))requirement=subject+"："+requirement;
            if(proof!=null)requirement+="。"+proof.quote();
            result.add(new Certificate(part,proof,requirement));
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(result);
    }
    private static Span trim(String text,int start,int end){
        while(start<end&&(Character.isWhitespace(text.charAt(start))||layoutSlash(text,start)))start++;
        while(end>start&&(Character.isWhitespace(text.charAt(end-1))||layoutSlash(text,end-1)))end--;
        return new Span(text.substring(start,end),start,end);
    }
    private static boolean layoutSlash(String text,int at){return text.charAt(at)=='/'&&at>0&&at+1<text.length()&&Character.isWhitespace(text.charAt(at-1))&&Character.isWhitespace(text.charAt(at+1));}
}
