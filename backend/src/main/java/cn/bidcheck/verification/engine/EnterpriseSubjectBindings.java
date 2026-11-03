package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Current bid parties, derived from explicit bid documents rather than from their own licenses. */
final class EnterpriseSubjectBindings {
    private EnterpriseSubjectBindings(){}
    private static final Set<String> BIDDER=cn.bidcheck.compat.Java8Api.setOf("投标人", "投标单位");
    private static final Set<String> ROLES=cn.bidcheck.compat.Java8Api.setOf("投标人", "投标单位", "联合体成员", "联合体成员单位", "牵头人", "牵头单位", "联合体牵头人", "联合体牵头单位", "施工单位", "设计单位", "施工成员", "设计成员");
    private static final String COMPANY="[\\p{IsHan}A-Za-z0-9（）()·]{2,100}?(?:有限责任公司|股份有限公司|有限公司|公司)";
    private static final Pattern LABEL=Pattern.compile("(?m)^[\\t |]*(投标人|投标单位)(?:名称)?[\\t ]*(?:[（(](?:盖章|公章或授权专用章|公章|单位公章)[）)])?[\\t ]*[:：][\\t ]*(?:[（(](?:盖章|公章)[）)])?[\\t ]*("+COMPANY+")(?=[\\t |。；;]|$)");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Binding{
        private final String role;
        private final String company;
        private final String method;
        private final List<String> members;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Binding(@com.fasterxml.jackson.annotation.JsonProperty("role") String role, @com.fasterxml.jackson.annotation.JsonProperty("company") String company, @com.fasterxml.jackson.annotation.JsonProperty("method") String method, @com.fasterxml.jackson.annotation.JsonProperty("members") List<String> members) { this.role = role; this.company = company; this.method = method; this.members = members; }
        public String role() { return role; }
        public String company() { return company; }
        public String method() { return method; }
        public List<String> members() { return members; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Binding that = (Binding) other; return java.util.Objects.equals(this.role, that.role) && java.util.Objects.equals(this.company, that.company) && java.util.Objects.equals(this.method, that.method) && java.util.Objects.equals(this.members, that.members); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.role); hash = 31 * hash + java.util.Objects.hashCode(this.company); hash = 31 * hash + java.util.Objects.hashCode(this.method); hash = 31 * hash + java.util.Objects.hashCode(this.members); return hash; }
        @Override public String toString() { return "Binding[" + "role=" + role + ", company=" + company + ", method=" + method + ", members=" + members + "]"; }
}
    static boolean companyRole(String value){return ROLES.contains(PersonnelRoles.canonical(value))||DomainBindings.memberSelector(value);}
    static boolean bidder(String value){return BIDDER.contains(PersonnelRoles.canonical(value));}
    private static String selector(String role){return DomainBindings.memberSelector(role)?"联合体成员":PersonnelRoles.canonical(role);}
    static boolean formalDocument(Source source){String opening=source.opening().replaceAll("[\\t ]","");return opening.matches("(?s)^(?:[\\s0-9一二三四五六七八九十.、（）()#]*)(?:商务投标文件封面|投标文件|投标函|投标函封面|联合体协议书).*");}
    private static boolean allowed(Source source){
        String text=source.evidence().quote();
        if(source.index()||source.historical()||source.scopeContext().blocked()||!cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "CURRENT_DOCUMENT", "CURRENT_BID_SECTION").contains(source.scopeContext().status()))return false;
        if(text.matches("(?s).*(?:填写示例|示范文本|仅供参考|空白模板|甲方名称|招标人名称).*"))return false;
        return formalDocument(source);
    }
    private static boolean validCompany(String value){return value.matches(COMPANY)&&!value.matches("(?i).*(?:示例|样例|某某|待填写|待填|填写|XX|某公司).*");}
    private static List<String> companyList(String value){
        String plain=clean(value);List<String> result=new ArrayList<>();Matcher m=Pattern.compile(COMPANY).matcher(plain);int end=0;
        while(end<plain.length()){
            m.region(end,plain.length());if(!m.lookingAt()||!validCompany(m.group()))return cn.bidcheck.compat.Java8Api.listOf();result.add(m.group());end=m.end();
            if(end==plain.length())break;Matcher separator=Pattern.compile("(?:、|,|，|和|与|以及|及)").matcher(plain);separator.region(end,plain.length());if(!separator.lookingAt())return cn.bidcheck.compat.Java8Api.listOf();end=separator.end();
        }
        return end==plain.length()&&result.size()>=2&&new HashSet<>(result).size()==result.size()?cn.bidcheck.compat.Java8Api.listCopyOf(result):cn.bidcheck.compat.Java8Api.listOf();
    }
    private static List<List<String>> completeSets(Source source){
        if(!allowed(source))return cn.bidcheck.compat.Java8Api.listOf();String text=source.evidence().quote();List<List<String>> result=new ArrayList<>();
        // Complete membership is explicit either in the agreement's enumeration or in the named bidder.
        List<Pattern> patterns=cn.bidcheck.compat.Java8Api.listOf(Pattern.compile("(?m)^[\\t ]*([^\\n。；]{4,500}?)\\[所有成员单位名称\\]自愿组成[:：]"), Pattern.compile("(?m)^[\\t ]*由([^\\n。；]{4,500}?)(?:共同)?组成(?:本项目|本次投标)?联合体[。；]"), Pattern.compile("(?m)^[\\t ]*[（(]([^\\n。；]{4,500}?)联合体[）)][\\t ]*[（(]以下称[“\"]我方[”\"][）)]"));
        for(Pattern pattern:patterns){Matcher m=pattern.matcher(text);while(m.find()){List<String> names=companyList(m.group(1));if(!names.isEmpty())result.add(names);}}
        return cn.bidcheck.compat.Java8Api.toList(result.stream().distinct());
    }
    private static List<Binding> bindings(Source source){
        if(!allowed(source))return cn.bidcheck.compat.Java8Api.listOf();String text=source.evidence().quote();List<Binding> out=new ArrayList<>();Matcher label=LABEL.matcher(text);
        while(label.find())if(validCompany(label.group(2)))for(String role:BIDDER)out.add(new Binding(role,label.group(2),"EXPLICIT_BIDDER_LABEL",cn.bidcheck.compat.Java8Api.listOf()));
        for(List<String> names:completeSets(source))for(String company:names){
            for(String role:BIDDER)out.add(new Binding(role,company,"EXPLICIT_COMPLETE_CONSORTIUM",names));
            for(String role:cn.bidcheck.compat.Java8Api.listOf("联合体成员", "联合体成员单位"))out.add(new Binding(role,company,"EXPLICIT_COMPLETE_CONSORTIUM",names));
        }
        if(text.contains("联合体协议书")){
            // OCR line wrapping may divide a company or role. Only the independently enumerated
            // members are eligible; whitespace normalization cannot invent a new organization.
            String plain=clean(text);Set<String> named=new LinkedHashSet<>();completeSets(source).forEach(named::addAll);
            for(String company:named){String name=Pattern.quote(company);
                if(Pattern.compile(name+"为[^。；]{0,240}?联合体牵头人[。；]").matcher(plain).find())for(String role:cn.bidcheck.compat.Java8Api.listOf("牵头人", "牵头单位", "联合体牵头人", "联合体牵头单位"))out.add(new Binding(role,company,"EXPLICIT_LEAD_MEMBER",cn.bidcheck.compat.Java8Api.listOf()));
                Matcher work=Pattern.compile(name+"负责(施工|设计)部分").matcher(plain);
                while(work.find())for(String suffix:cn.bidcheck.compat.Java8Api.listOf("单位", "成员"))out.add(new Binding(work.group(1)+suffix,company,"EXPLICIT_MEMBER_WORK_ALLOCATION",cn.bidcheck.compat.Java8Api.listOf()));
            }
        }
        return out;
    }
    /** Re-parse immutable source text. Neither bindingMethod nor a cached members attribute is trusted. */
    static boolean grounded(Fact fact,Source source){
        if(!fact.kind().equals("RELATION")||!fact.scope().equals("CURRENT_PROJECT"))return false;
        String role=DomainBindings.sourceRole(fact),company=DomainBindings.roleTarget(fact,role);
        if(!companyRole(role)||!cn.bidcheck.compat.Java8Api.setOf("名称", ":", "：", "为", "身份", "角色").contains(fact.field()))return false;
        return bindings(source).stream().anyMatch(b->b.role().equals(selector(role))&&b.company().equals(company));
    }
    static List<Fact> extract(List<Rule> rules,List<Source> sources){
        Set<String> requested=new LinkedHashSet<>();for(Rule rule:rules){if(!rule.predicate().equals("VISUAL")&&companyRole(rule.subject()))requested.add(rule.subject());String employer=str(rule.parameters(),"employer");if(companyRole(employer))requested.add(employer);if(Boolean.TRUE.equals(rule.parameters().get("templateFormAudit")))requested.add("投标人");}
        if(requested.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();Map<Source,List<Binding>> observed=new LinkedHashMap<>();for(Source source:sources){List<Binding> rows=bindings(source);if(!rows.isEmpty())observed.put(source,rows);}
        Set<Set<String>> memberships=new HashSet<>();observed.values().forEach(rows->rows.stream().filter(b->!b.members().isEmpty()).forEach(b->memberships.add(new LinkedHashSet<>(b.members()))));
        Set<String> direct=new LinkedHashSet<>();observed.values().forEach(rows->rows.stream().filter(b->bidder(b.role())&&b.members().isEmpty()).map(Binding::company).forEach(direct::add));
        boolean jointMention=consortiumDeclared(sources);
        boolean complete=memberships.size()==1;Set<String> members=complete?memberships.iterator().next():cn.bidcheck.compat.Java8Api.setOf();
        boolean bidderSafe=complete?members.containsAll(direct):memberships.isEmpty()&&!jointMention&&direct.size()==1;
        Map<String,Fact> result=new LinkedHashMap<>();
        for(java.util.Map.Entry<cn.bidcheck.verification.engine.VerificationTypes.Source, java.util.List<cn.bidcheck.verification.engine.EnterpriseSubjectBindings.Binding>> entry:observed.entrySet())for(Binding binding:entry.getValue())for(String requestedRole:requested){
            if(!selector(requestedRole).equals(binding.role()))continue;
            if(bidder(requestedRole)&&(!bidderSafe||complete&&binding.members().isEmpty()))continue;
            if(DomainBindings.memberSelector(requestedRole)&&(!complete||binding.members().isEmpty()))continue;
            if(complete&&!bidder(requestedRole)&&!members.contains(binding.company()))continue;
            Source source=entry.getKey();Fact candidate=new Fact("native-enterprise","RELATION",requestedRole,"名称",binding.company(),binding.company(),"","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(source.id()),linked("object",binding.company(),"bindingMethod",binding.method(),"members",binding.members(),"proofScope","CURRENT_BID_PARTY_ONLY"));
            cn.bidcheck.verification.engine.ProofVerifier.Verified verified=new ProofVerifier().verify(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(candidate))),cn.bidcheck.compat.Java8Api.listOf(source));
            for(Fact checked:verified.facts()){Fact canonical=SharedFactReuse.canonical(checked);result.put(canonical.id(),canonical);}
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(result.values());
    }
    static List<Map<String,Object>> completeSet(List<String> subjects,EvidenceCorpus corpus){
        Set<String> expected=new HashSet<>(subjects);List<Map<String,Object>> out=new ArrayList<>();
        for(Source source:corpus.all)for(List<String> names:completeSets(source)){
            if(!new HashSet<>(names).equals(expected))return cn.bidcheck.compat.Java8Api.listOf();
            out.add(linked("sourceId",source.id(),"members",names,"quote",source.evidence().quote(),"confirmation","EXPLICIT_CURRENT_BIDDER_COMPOSITION"));
        }
        return out;
    }
    static boolean consortiumDeclared(List<Source> sources){
        for(Source source:sources){if(!allowed(source))continue;String text=source.evidence().quote();
            if(!completeSets(source).isEmpty())return true;
            if(text.contains("联合体协议书")&&text.matches("(?s).*(?:共同投标|自愿组成).*")&&!text.contains("编制及签署要求"))return true;
            for(String sentence:text.split("[。；;\\n]")){
                String plain=clean(sentence);if(plain.matches(".*(?:如果|若|如为|如采用|如投标人|示例|可以|允许|不得|不接受|不采用|非联合体).*"))continue;
                if(plain.matches(".*(?:本次(?:投标)?|本项目|我方)(?:为|采用|以)?联合体(?:形式|方式)?投标.*")||plain.matches(".*(?:本次投标|本项目投标|我方投标)(?:采用|为)联合体(?:形式|方式).*")||plain.matches(".*公司联合体[）)].*"))return true;
            }
        }return false;
    }
}
