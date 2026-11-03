package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.semantic.SourceRelations;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Exact, explicitly declared membership in the current project's agreement; never fuzzy entity merging. */
final class ConsortiumContextPolicy {
    private ConsortiumContextPolicy() { }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Declaration {
        private final Set<String> members;
        private final List<Evidence> sources;
        @com.fasterxml.jackson.annotation.JsonCreator
        Declaration(@com.fasterxml.jackson.annotation.JsonProperty("members") Set<String> members, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<Evidence> sources) { this.members = members; this.sources = sources; }
        public Set<String> members() { return members; }
        public List<Evidence> sources() { return sources; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Declaration that = (Declaration) other; return java.util.Objects.equals(this.members, that.members) && java.util.Objects.equals(this.sources, that.sources); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.members); hash = 31 * hash + java.util.Objects.hashCode(this.sources); return hash; }
        @Override public String toString() { return "Declaration[" + "members=" + members + ", sources=" + sources + "]"; }
 }

    static Declaration bind(Document document,DocumentContextIndex contextIndex,List<Map<String,Object>> occurrences) {
        Set<String> members=new LinkedHashSet<>();List<Evidence> sources=new ArrayList<>();
        List<SourceRelations.Link> relationships=SourceRelations.discover(document);
        List<Evidence> projectSources=cn.bidcheck.compat.Java8Api.toList(relationships.stream().filter(link->link.type().equals("PROJECT_CONTEXT")).map(SourceRelations.Link::evidence));
        if(projectSources.isEmpty())return new Declaration(cn.bidcheck.compat.Java8Api.setOf(),cn.bidcheck.compat.Java8Api.listOf());
        for(SourceRelations.Link link:relationships) {
            if(!link.type().equals("CONSORTIUM_MEMBERSHIP_AND_DUTIES")||!Boolean.TRUE.equals(link.attributes().get("projectMatchedToCover")))continue;
            Evidence source=link.evidence();Page page=document.pages().stream().filter(p->p.number()==source.page()&&Objects.equals(p.locationType(),source.locationType())).findFirst().orElse(null);
            if(page==null||page.text()==null)continue;
            int title=page.text().indexOf("联合体协议书");if(title<0)continue;
            int line=1+(int)page.text().substring(0,title).chars().filter(c->c=='\n').count();
            if(!contextIndex.at(page,line).scope().equals("CURRENT_PROJECT"))continue;
            Set<String> declared=new LinkedHashSet<>();
            for(String text:source.quote().split("\\R")) {
                int marker=text.indexOf("[所有成员单位名称]");
                if(marker<0)marker=text.indexOf("［所有成员单位名称］");
                if(marker<0||!text.substring(marker).contains("自愿组成"))continue;
                for(String token:text.substring(0,marker).split("[、,，]")){String name=normal(token);if(company(name))declared.add(name);}
            }
            Matcher labelled=Pattern.compile("(?:联合体牵头人|联合体牵头方|牵头单位|联合体成员(?:单位)?|成员单位)\\s*[:：]\\s*([^\\r\\n;；，、]{2,100})").matcher(source.quote());
            while(labelled.find()){String name=normal(labelled.group(1));if(company(name))declared.add(name);}
            if(declared.size()<2)continue;
            // Different eligible declarations cannot be unioned into a larger invented membership set.
            if(!members.isEmpty()&&!members.equals(declared))return new Declaration(cn.bidcheck.compat.Java8Api.setOf(),cn.bidcheck.compat.Java8Api.listOf());
            members.addAll(declared);sources.add(source);
        }
        if(members.size()<2)return new Declaration(cn.bidcheck.compat.Java8Api.setOf(),cn.bidcheck.compat.Java8Api.listOf());
        sources.addAll(projectSources);
        for(Map<String,Object> occurrence:occurrences) {
            if(!"companyName".equals(occurrence.get("field"))||!"CURRENT_PROJECT".equals(occurrence.get("scope")))continue;
            String name=Objects.toString(occurrence.get("value"),"");if(!members.contains(name))continue;
            occurrence.put("subject","BIDDER_MEMBER:"+name);
            occurrence.put("subjectAssignment",cn.bidcheck.compat.Java8Api.mapOf("status", "DECLARED_CONSORTIUM_MEMBER", "memberName", name, "projectMatched", true, "identityVerified", false, "evidences", cn.bidcheck.compat.Java8Api.listCopyOf(sources)));
            List<Evidence> refs=new ArrayList<>(sources);
            if(occurrence.get("contextEvidences") instanceof List<?>)for(Object ref:((List<?>) (occurrence.get("contextEvidences"))))if(ref instanceof Evidence)refs.add(((Evidence) (ref)));
            refs=cn.bidcheck.compat.Java8Api.toList(refs.stream().distinct());int referenceLimit=cn.bidcheck.compat.Java8Api.isBlank(Objects.toString(occurrence.get("contextQuote"),""))?15:14;
            if(refs.size()>referenceLimit)occurrence.put("comparability","INCOMPLETE");
            occurrence.put("contextEvidences",cn.bidcheck.compat.Java8Api.toList(refs.stream().limit(referenceLimit)));
            occurrence.put("scopeReason","当前项目联合体协议明确列出该成员，按成员完整名称分别比较；未核验签章或法律身份");
        }
        return new Declaration(cn.bidcheck.compat.Java8Api.setCopyOf(members),cn.bidcheck.compat.Java8Api.listCopyOf(sources));
    }

    static List<Map<String,Object>> nonMemberGroups(Declaration declaration,List<Map<String,Object>> occurrences) {
        if(declaration.members().isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        List<Map<String,Object>> unmatched=cn.bidcheck.compat.Java8Api.toList(occurrences.stream().filter(o->"companyName".equals(o.get("field"))&&"CURRENT_PROJECT".equals(o.get("scope"))&&"BIDDER".equals(o.get("subject")))
                .filter(o->company(Objects.toString(o.get("value"),""))&&!declaration.members().contains(o.get("value"))));
        if(unmatched.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();
        List<Evidence> evidence=new ArrayList<>(declaration.sources());for(Map<String,Object> item:unmatched)if(item.get("evidence") instanceof Evidence)evidence.add(((Evidence) (item.get("evidence"))));
        Map<String,Object> group=new LinkedHashMap<>();
        group.put("id","consortium-"+UUID.nameUUIDFromBytes((cn.bidcheck.compat.Java8Api.toList(declaration.sources().stream().map(Evidence::documentId).distinct()).toString()+cn.bidcheck.compat.Java8Api.toList(declaration.members().stream().sorted())+cn.bidcheck.compat.Java8Api.toList(unmatched.stream().map(o->o.get("id")))).getBytes(StandardCharsets.UTF_8)));
        group.put("field","companyName");group.put("title","投标企业名称与联合体成员声明存在疑似差异");group.put("subject","BIDDER");group.put("scope","CURRENT_PROJECT");group.put("lot","UNRESOLVED");group.put("basis","");group.put("status","SUSPECTED");
        group.put("reason","本次投标位置出现未在当前项目联合体协议成员清单中精确匹配的企业名称，需复核名称更正、角色或协议版本；未将其他企业自动解释为联合体成员。");
        group.put("occurrenceIds",cn.bidcheck.compat.Java8Api.toList(unmatched.stream().map(o->o.get("id"))));group.put("evidences",cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()));group.put("values",cn.bidcheck.compat.Java8Api.toList(unmatched.stream().map(o->o.get("value")).distinct()));
        group.put("declaredMembers",cn.bidcheck.compat.Java8Api.toList(declaration.members().stream().sorted()));return cn.bidcheck.compat.Java8Api.listOf(group);
    }
    private static boolean company(String name){return name.matches("[\\p{IsHan}A-Z0-9()·]{1,100}(?:公司|集团)")&&!name.contains("联合体")&&!name.matches(".*(?:所有成员|单位名称|填写|示例|XX).*");}
    private static String normal(String value){return cn.bidcheck.compat.Java8Api.strip(value).replace('（','(').replace('）',')').replaceAll("[\\s\\u3000]","").toUpperCase(Locale.ROOT);}
}
