package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;

/** Source-faithful list structure. A parent actor/ANY is inherited, never copied into leaf quotes. */
final class QualificationRequirementTree {
    private static final Pattern HEADER=Pattern.compile("(?s)^\\s*(?:(?:资质要求|资格要求)\\s*)?(?:(?:[（(][0-9一二三四五六七八九十]+[）)]|[0-9]+(?:[.．][0-9]+)*[、.．)]?)\\s*)?(设计单位|施工单位|投标人|联合体成员)(?:资质要求|资格要求)?[：:]\\s*(?:须|应|必须)?满足(?:下列|以下)条件之一[：:]\\s*");
    private static final Pattern ITEM=Pattern.compile("[①②③④⑤⑥⑦⑧⑨⑩]|[（(][1-9][）)]");
    private static final Pattern GRADE=Pattern.compile("^(.{2,65}?)(甲级|乙级|丙级|丁级|一级|二级|三级|壹级|贰级|叁级)((?:[（(]含[）)])?(?:及以上|以上)?(?:资质)?)$");
    private static final Pattern INLINE_HEADER=Pattern.compile("^\\s*(?:(?:[0-9]+(?:[.．][0-9]+)*[、.．)]?)\\s*)?(设计单位|施工单位|投标人|联合体成员)(?:资质要求|资格要求)[：:]\\s*");
    private static final Pattern COMMON_PROOF=Pattern.compile("(?:须|需|应|必须)(?:提交|提供)有效证明文件");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Branch {
        private final String quote;
        private final String category;
        private final String grade;
        private final String gradeOperator;
        private final String unresolved;
        @com.fasterxml.jackson.annotation.JsonCreator
        Branch(@com.fasterxml.jackson.annotation.JsonProperty("quote") String quote, @com.fasterxml.jackson.annotation.JsonProperty("category") String category, @com.fasterxml.jackson.annotation.JsonProperty("grade") String grade, @com.fasterxml.jackson.annotation.JsonProperty("gradeOperator") String gradeOperator, @com.fasterxml.jackson.annotation.JsonProperty("unresolved") String unresolved) { this.quote = quote; this.category = category; this.grade = grade; this.gradeOperator = gradeOperator; this.unresolved = unresolved; }
        public String quote() { return quote; }
        public String category() { return category; }
        public String grade() { return grade; }
        public String gradeOperator() { return gradeOperator; }
        public String unresolved() { return unresolved; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Branch that = (Branch) other; return java.util.Objects.equals(this.quote, that.quote) && java.util.Objects.equals(this.category, that.category) && java.util.Objects.equals(this.grade, that.grade) && java.util.Objects.equals(this.gradeOperator, that.gradeOperator) && java.util.Objects.equals(this.unresolved, that.unresolved); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.quote); hash = 31 * hash + java.util.Objects.hashCode(this.category); hash = 31 * hash + java.util.Objects.hashCode(this.grade); hash = 31 * hash + java.util.Objects.hashCode(this.gradeOperator); hash = 31 * hash + java.util.Objects.hashCode(this.unresolved); return hash; }
        @Override public String toString() { return "Branch[" + "quote=" + quote + ", category=" + category + ", grade=" + grade + ", gradeOperator=" + gradeOperator + ", unresolved=" + unresolved + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Tree {
        private final String subject;
        private final String heading;
        private final String choiceQuote;
        private final List<Branch> branches;
        private final String proofQuote;
        private final List<String> mandatoryQuotes;
        @com.fasterxml.jackson.annotation.JsonCreator
        Tree(@com.fasterxml.jackson.annotation.JsonProperty("subject") String subject, @com.fasterxml.jackson.annotation.JsonProperty("heading") String heading, @com.fasterxml.jackson.annotation.JsonProperty("choiceQuote") String choiceQuote, @com.fasterxml.jackson.annotation.JsonProperty("branches") List<Branch> branches, @com.fasterxml.jackson.annotation.JsonProperty("proofQuote") String proofQuote, @com.fasterxml.jackson.annotation.JsonProperty("mandatoryQuotes") List<String> mandatoryQuotes) { this.subject = subject; this.heading = heading; this.choiceQuote = choiceQuote; this.branches = branches; this.proofQuote = proofQuote; this.mandatoryQuotes = mandatoryQuotes; }
        public String subject() { return subject; }
        public String heading() { return heading; }
        public String choiceQuote() { return choiceQuote; }
        public List<Branch> branches() { return branches; }
        public String proofQuote() { return proofQuote; }
        public List<String> mandatoryQuotes() { return mandatoryQuotes; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Tree that = (Tree) other; return java.util.Objects.equals(this.subject, that.subject) && java.util.Objects.equals(this.heading, that.heading) && java.util.Objects.equals(this.choiceQuote, that.choiceQuote) && java.util.Objects.equals(this.branches, that.branches) && java.util.Objects.equals(this.proofQuote, that.proofQuote) && java.util.Objects.equals(this.mandatoryQuotes, that.mandatoryQuotes); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.subject); hash = 31 * hash + java.util.Objects.hashCode(this.heading); hash = 31 * hash + java.util.Objects.hashCode(this.choiceQuote); hash = 31 * hash + java.util.Objects.hashCode(this.branches); hash = 31 * hash + java.util.Objects.hashCode(this.proofQuote); hash = 31 * hash + java.util.Objects.hashCode(this.mandatoryQuotes); return hash; }
        @Override public String toString() { return "Tree[" + "subject=" + subject + ", heading=" + heading + ", choiceQuote=" + choiceQuote + ", branches=" + branches + ", proofQuote=" + proofQuote + ", mandatoryQuotes=" + mandatoryQuotes + "]"; }

        Tree(String subject,String heading,String choiceQuote,List<Branch> branches,String proofQuote){this(subject,heading,choiceQuote,branches,proofQuote,cn.bidcheck.compat.Java8Api.listOf());}
    }
    private QualificationRequirementTree() {}

    static Optional<Tree> parse(String source){
        Matcher header=HEADER.matcher(source);if(!header.find())return inline(source);
        int bodyStart=header.end();
        // Common proof is outside the alternatives and is required whichever qualification route wins.
        Matcher proof=Pattern.compile("(?:须|需|应|必须)(?:提交|提供)有效证明文件").matcher(source);
        int proofAt=proof.find(bodyStart)?proof.start():source.length();
        if(proofAt<source.length()&&!source.substring(bodyStart,proofAt).matches("(?s).*[。；;]\\s*(?:/\\s*)?$"))return Optional.empty();
        String choices=cn.bidcheck.compat.Java8Api.strip(source.substring(bodyStart,proofAt));
        if(cn.bidcheck.compat.Java8Api.isBlank(choices))return Optional.empty();
        // An independent obligation after a sentence is not another optional certificate.
        // Leave mixed structures to the normal coverage compiler until their boundary is proved.
        if(Pattern.compile("[。；;\\n]\\s*(?:须|需|必须|应|同时|另外|此外|且|并)").matcher(choices).find())return Optional.empty();
        List<Branch> branches=new ArrayList<>();Matcher markers=ITEM.matcher(choices);
        List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();
        Boolean circled=null;
        while(markers.find()){
            String token=markers.group();boolean circle=token.length()==1;
            int ordinal=circle?"①②③④⑤⑥⑦⑧⑨⑩".indexOf(token)+1:Character.digit(token.charAt(1),10);
            // Mixed or restarted numbering can be a nested proof obligation, not another
            // alternative. Leave that structure unresolved instead of weakening conjunctions.
            if(circled!=null&&circled!=circle||ordinal!=starts.size()+1)return Optional.empty();
            circled=circle;starts.add(markers.start());ends.add(markers.end());
        }
        if(starts.size()<2||!cn.bidcheck.compat.Java8Api.isBlank(choices.substring(0,starts.get(0))))return Optional.empty();
        for(int i=0;i<starts.size();i++){
            String item=choices.substring(ends.get(i),i+1<starts.size()?starts.get(i+1):choices.length());
            // “或根据…换证” is its own alternative, not an unconditional extra requirement.
            int replacement=item.indexOf("或根据《");
            String ordinary=replacement>=0?item.substring(0,replacement):item;
            for(String part:ordinary.split("或者|或")){
                String quote=trim(part);
                // A following unnumbered sentence may be a new mandatory obligation. Do not
                // put it inside this optional branch merely because its boundary is ambiguous.
                if(quote.matches("(?s).*[。；;].*"))return Optional.empty();
                if(!cn.bidcheck.compat.Java8Api.isBlank(quote))branches.add(branch(quote));
            }
            if(replacement>=0){String quote=trim(item.substring(replacement));
                String replacementBody=trim(quote.replaceFirst("\\s*/\\s*$",""));
                if(replacementBody.matches("(?s).*[。；;].*"))return Optional.empty();
                branches.add(new Branch(quote,"","","","QUALIFICATION_REPLACEMENT_MAPPING_REQUIRED"));}
        }
        if(branches.size()<2)return Optional.empty();
        return Optional.of(new Tree(header.group(1),source.substring(0,bodyStart),
                cn.bidcheck.compat.Java8Api.strip(source.substring(0,proofAt)),cn.bidcheck.compat.Java8Api.listCopyOf(branches),cn.bidcheck.compat.Java8Api.strip(source.substring(proofAt))));
    }
    /** An unnumbered A-or-policy-replacement choice followed by explicit common obligations. */
    private static Optional<Tree> inline(String source){
        Matcher header=INLINE_HEADER.matcher(source);if(!header.find())return Optional.empty();
        int bodyStart=header.end();Matcher replacement=Pattern.compile("或(?:者)?(?:根据|按照|依据)《").matcher(source);
        if(!replacement.find(bodyStart))return Optional.empty();int alternativeAt=replacement.start();
        // Only a single ordinary qualification and one explicit statutory replacement branch
        // are handled here; multiple unknown connectors must keep normal compiler gaps.
        String ordinary=trim(source.substring(bodyStart,alternativeAt));Branch main=branch(ordinary);
        if(!cn.bidcheck.compat.Java8Api.isBlank(main.unresolved())||ordinary.matches("(?s).*(?:或|且|[。；;]).*"))return Optional.empty();
        int end=source.length();Matcher boundary=Pattern.compile("[。；;\\n]").matcher(source);
        if(boundary.find(replacement.end()))end=boundary.start();
        String alternative=cn.bidcheck.compat.Java8Api.strip(source.substring(alternativeAt,end));
        if(!alternative.matches("(?s).*?(?:换发|换证)[^。；;]*?(?:相应资质|相应资格)[^。；;]*")||alternative.contains("且"))return Optional.empty();
        Matcher proof=COMMON_PROOF.matcher(source);int proofAt=proof.find(end)?proof.start():source.length();
        String suffix=source.substring(end,proofAt);List<String> mandatory=new ArrayList<>();
        Matcher pieces=Pattern.compile("[^。；;\\n]+[。；;]?").matcher(suffix);
        while(pieces.find()){
            String quote=cn.bidcheck.compat.Java8Api.strip(pieces.group());if(cn.bidcheck.compat.Java8Api.isBlank(quote))continue;
            if(!quote.matches("(?:且|并|同时)?(?:具有|具备|持有)(?:(?:住建|住房和城乡建设|建设)(?:主管|行政主管)?部门颁发的)?(?:有效的)?安全生产许可证[。；;]?"))return Optional.empty();
            mandatory.add(quote);
        }
        String common=cn.bidcheck.compat.Java8Api.strip(source.substring(proofAt));
        if(!cn.bidcheck.compat.Java8Api.isBlank(common)&&!common.matches("(?:须|需|应|必须)(?:提交|提供)有效证明文件(?:[（(]并提供查询网址及网址查询截图[，,]查询截图信息应与证明文件信息一致[）)])?[。；;]?"))return Optional.empty();
        Branch policy=new Branch(alternative,"","","","QUALIFICATION_REPLACEMENT_MAPPING_REQUIRED");
        return Optional.of(new Tree(header.group(1),source.substring(0,bodyStart),cn.bidcheck.compat.Java8Api.strip(source.substring(0,end)),cn.bidcheck.compat.Java8Api.listOf(main, policy),common,cn.bidcheck.compat.Java8Api.listCopyOf(mandatory)));
    }
    private static Branch branch(String quote){
        String value=quote.replaceFirst("^(?:(?:住建|住房和城乡建设|建设)(?:主管|行政主管)?部门颁发的|具有|具备|取得|持有)+","");
        Matcher grade=GRADE.matcher(value);
        if(!grade.matches()||!value.matches(".*(?:设计|建筑|工程|施工|资质).*"))
            return new Branch(quote,"","","","QUALIFICATION_BRANCH_NOT_COMPILED");
        String category=grade.group(1);
        if(category.matches(".*(?:如果|若|除|且|并|须|应|不得|不低于|根据|通知|[。；;，,：:《》]).*"))
            return new Branch(quote,"","","","QUALIFICATION_BRANCH_NOT_COMPILED");
        return new Branch(quote,category,grade.group(2),grade.group(3).contains("以上")?">=":"=","");
    }
    private static String trim(String value){return cn.bidcheck.compat.Java8Api.strip(cn.bidcheck.compat.Java8Api.strip(value).replaceFirst("[。；;]+$",""));}
}
