package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Vocabulary is separate from evidence: recognizing a title never proves an appointment. */
public final class PersonnelRoles {
    private PersonnelRoles() {}
    private static final Set<String> PEOPLE=Set.of("法定代表人","授权代表","委托代理人","项目负责人","项目经理",
        "设计负责人","施工负责人","技术负责人","专职安全员","安全员","企业负责人","企业主要负责人",
        "施工企业主要负责人","施工企业负责人","设计企业主要负责人");
    private static final Set<String> COMPANIES=Set.of("投标人","投标单位","牵头人","牵头单位","联合体牵头人",
        "联合体牵头单位","施工成员","设计成员","施工单位","设计单位");
    static String canonical(String raw) {
        String value=clean(raw).replaceFirst("^(?:本次投标|本项目|本工程|本次)","");
        return switch(value){case "施工部分负责人"->"施工负责人";case "设计部分负责人"->"设计负责人";default->value;};
    }
    static boolean known(String raw){String value=canonical(raw);return PEOPLE.contains(value)||COMPANIES.contains(value);}
    static boolean personRole(String raw){return PEOPLE.contains(canonical(raw));}
    /** Source-free observation keys use only vocabulary aliases, never evidence-dependent role equivalence. */
    public static boolean sameObservationRole(String requested,String observed){return personRole(requested)&&personRole(observed)&&canonical(requested).equals(canonical(observed));}
    static boolean equivalent(String requested,String observed,Fact fact,EvidenceCorpus corpus){
        if(!known(requested)||!known(observed))return false;
        String left=canonical(requested),right=canonical(observed);
        if(left.equals(right))return true;
        // These labels are only equivalent when the current source explicitly says so. Other pairs stay distinct.
        Set<String> pair=Set.of(left,right);
        if(!pair.equals(Set.of("项目负责人","项目经理"))
            &&!pair.equals(Set.of("施工企业主要负责人","企业主要负责人"))
            &&!pair.equals(Set.of("施工单位","施工成员"))&&!pair.equals(Set.of("设计单位","设计成员")))return false;
        String a=Pattern.quote(left),b=Pattern.quote(right);
        Pattern alias=Pattern.compile("(?:"+a+"[（(](?:即|亦称)?"+b+"[）)]|"+b+"[（(](?:即|亦称)?"+a+"[）)])");
        return fact.sourceIds().stream().map(corpus.byId::get).filter(Objects::nonNull)
            .filter(s->!s.index()&&!s.historical()).anyMatch(s->alias.matcher(clean(s.evidence().quote())).find());
    }
    static List<String> labels(String role){String value=canonical(role);return switch(value){
        case "施工负责人"->List.of("施工负责人","施工部分负责人");
        case "设计负责人"->List.of("设计负责人","设计部分负责人");default->List.of(value);};}
    /** Candidate-retrieval terms only: a search synonym never establishes role equivalence. */
    static List<String> queryTerms(String role){LinkedHashSet<String> values=new LinkedHashSet<>();if(role==null||role.isBlank())return List.of();values.add(role);values.addAll(labels(role));
        switch(canonical(role)){case "项目负责人"->values.add("项目经理");case "项目经理"->values.add("项目负责人");
            case "施工企业主要负责人"->values.add("企业主要负责人");case "企业主要负责人"->values.add("施工企业主要负责人");
            case "施工单位"->values.add("施工成员");case "施工成员"->values.add("施工单位");case "设计单位"->values.add("设计成员");case "设计成员"->values.add("设计单位");default->{}}
        return List.copyOf(values);}
}
