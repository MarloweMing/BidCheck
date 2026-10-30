package cn.bidcheck.engine;

import java.util.*;
import java.util.regex.*;

/** Exact source slices for independently numbered certificate awards, never rewritten obligations. */
public final class ScoringSourceSegments {
    public static final String VERSION="scoring-source-segments-2-complete-rubric";
    private ScoringSourceSegments() {}
    public record Span(String quote,int start,int end) {}
    public record Certificate(Span clause,Span proof,String legacyExpected) {
        public String expected(){return clause.quote()+(proof==null?"":"\n"+proof.quote());}
    }
    /** Exact fingerprints of the two former, lossy generator projections. These are used
     * only to recognize old generated data, never as the new business requirement. */
    public static String legacyTitleAwardExpected(String source){
        String body=body(source);Matcher proof=Pattern.compile("(?:证明材料|证明文件要求)[:：]").matcher(body);
        if(!proof.find())return "";int proofAt=proof.start();if(proof.find())return "";
        String award=body.substring(0,proofAt).strip();
        if(!award.matches("(?s).*(?:具有|具备)[^。；;]*高级[^。；;]*职称得[0-9.]+分[，,]中级得[0-9.]+分[，,](?:未提交|未提供)(?:不得分|得0分)[。]?"))return "";
        int score=award.indexOf('得');if(score<0)return "";
        return award.substring(0,score).strip()+"。"+body.substring(proofAt);
    }
    public static String body(String source){return source.replaceFirst("\\t\\s*-?[0-9]+(?:\\.[0-9]+)?\\s*$","").replaceAll("\\s+/\\s+"," ");}
    public static String legacyTitle(String prefix,String source){String body=body(source);return prefix+body.substring(0,Math.min(45,body.length()));}
    public record Rubric(Span introduction,List<Span> tiers,String legacyTopDescription) {}
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
            String content=tier.quote().substring(2).strip();
            Matcher score=Pattern.compile("^(.*?)(?:得|计|获)(?:[（(]?[0-9.,，]+[）)\\]]?|[0-9.]+)分[。；;]?$",Pattern.DOTALL).matcher(content);
            if(!score.matches()&&!content.matches("(?s)^.+不得分[。；;]?$"))return Optional.empty();
            if(i==0){if(!score.matches())return Optional.empty();top=score.group(1).strip();}
            tiers.add(tier);
        }
        return Optional.of(new Rubric(intro,List.copyOf(tiers),top));
    }
    public static List<String> repeatedPlanItems(String source){
        Matcher list=Pattern.compile("完整性[:：](.+?)[0-9一二三四五六七八九十]+项内容").matcher(source);
        if(!list.find())return List.of();Set<String> seen=new HashSet<>(),repeated=new LinkedHashSet<>();
        for(String item:list.group(1).split("、")){String value=item.strip();if(!seen.add(value))repeated.add(value);}
        return List.copyOf(repeated);
    }
    public static List<Certificate> certificates(String source){
        Matcher proofMarker=Pattern.compile("(?:证明材料|证明文件要求)[:：]").matcher(source);
        int end=source.length();Matcher total=Pattern.compile("\\t\\s*-?[0-9]+(?:\\.[0-9]+)?\\s*$").matcher(source);if(total.find())end=total.start();
        Span proof=null;int clauseEnd=end;
        if(proofMarker.find()){clauseEnd=proofMarker.start();proof=trim(source,proofMarker.start(),end);if(proofMarker.find())return List.of();}
        Matcher numbered=Pattern.compile("[（(][0-9一二三四五六七八九十]+[）)]").matcher(source.substring(0,clauseEnd));
        List<Integer> starts=new ArrayList<>();while(numbered.find())starts.add(numbered.start());
        if(!starts.isEmpty()){
            String prefix=source.substring(0,starts.get(0));
            // Only a short table label can be omitted. Ancestor conditions, alternatives,
            // combined score caps and unclassified prose apply to every numbered branch.
            if(!prefix.isBlank()&&(!prefix.matches("[^\\n。；;，,：:]{1,80}\\t\\s*")
                    ||prefix.matches("(?s).*(?:仅|若|如|当|须|应|需|不|或|任选|任一|其一|至少|最多|上限|合计|总分|满分).*")))return List.of();
        }
        if(starts.isEmpty())starts.add(0);
        List<Certificate> result=new ArrayList<>();
        for(int i=0;i<starts.size();i++){
            Span part=trim(source,starts.get(i),i+1<starts.size()?starts.get(i+1):clauseEnd);String text=part.quote();
            if(!text.matches("(?s).*(?:证书|认证).*")||!text.matches("(?s).*(?:具备|具有|提供|获得|取得).*")||!text.matches("(?s).*(?:得|计|获)[^分]*分.*"))continue;
            String requirement=text.replaceFirst("(?s)^.*?(?:具备|具有|提供|获得|取得)(?:有效的?|的)?\\s*","").strip();
            requirement=requirement.replaceFirst("[，,]?\\s*(?:可?得|计|获)[^分]*分.*$","").replaceFirst("的?[，,；;。]+$","").replaceFirst("证书的$","证书");
            String subject=text.replaceFirst("^(?:[\\s/]*[（(][0-9一二三四五六七八九十]+[）)])?\\s*","").split("(?:具备|具有|提供|获得|取得)",2)[0].strip();
            if(subject.matches("(?:施工单位|设计单位|联合体牵头人|联合体成员|项目负责人|设计负责人)"))requirement=subject+"："+requirement;
            if(proof!=null)requirement+="。"+proof.quote();
            result.add(new Certificate(part,proof,requirement));
        }
        return List.copyOf(result);
    }
    private static Span trim(String text,int start,int end){
        while(start<end&&(Character.isWhitespace(text.charAt(start))||layoutSlash(text,start)))start++;
        while(end>start&&(Character.isWhitespace(text.charAt(end-1))||layoutSlash(text,end-1)))end--;
        return new Span(text.substring(start,end),start,end);
    }
    private static boolean layoutSlash(String text,int at){return text.charAt(at)=='/'&&at>0&&at+1<text.length()&&Character.isWhitespace(text.charAt(at-1))&&Character.isWhitespace(text.charAt(at+1));}
}
