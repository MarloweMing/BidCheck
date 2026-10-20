package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Source-bound requirement context, not a role-to-bidder identity assertion. */
final class RequirementContextBindings {
    static final String ROLES="施工企业主要负责人|施工部分负责人|设计部分负责人|拟派项目负责人|拟派项目组人员|联合体牵头人|联合体成员|企业主要负责人|项目负责人|项目经理|设计负责人|施工负责人|技术负责人|专职安全员|法定代表人|授权代表|委托代理人|施工单位|设计单位|施工成员|设计成员|投标单位|投标人";
    private static final String COMPANY="[\\p{IsHan}A-Za-z0-9（）()]{2,60}(?:有限公司|公司)";
    private static final Pattern HEADER=Pattern.compile("(?:^|[。；;，,\\n])\\s*(?:[★*]|[0-9]+(?:[.．][0-9]+){1,4}[、.．)]?|[0-9一二三四五六七八九十]+[、.．)])?\\s*(?:[^。；;，,\\n:：]{1,12}[:：]\\s*)?("+ROLES+"|"+COMPANY+")(?:(?:[（(][^）)]{0,30}[）)])?)(?:资格要求|资质要求|要求)?(?:\\s*[:：]|(?=须|应|需|具有|具备|提供|提交|持有|\\r?\\n|$))");
    private static final Pattern BRANCH=Pattern.compile("如果|若|否则|除非|如(?=为|属|有|无|提交|采用|组成|联合体)|特殊情况|(?<![一二三四五六七八九十])第[一二三四五六七八九十0-9]+标段");
    private static final List<String> FIELDS=List.of("lot","currency","amountBasis","taxBasis","category","number","validityField","employer","grade","profession","personRole");
    private static final Set<String> PERSONNEL_REFERENCES=Set.of("上述人员","以上人员","前述人员","上述各岗位人员");
    record Parent(String id,String subject,String quote,String logic) { }
    record Heading(String subject,int start,int end) { }
    record Scope(String quote,int start,int end,String subject,String kind,String parentId) { }
    private final String source;
    private final String sourceKind;
    private final boolean verifiedOuterSubject;

    RequirementContextBindings(String source,String sourceKind){this(source,sourceKind,false);}
    RequirementContextBindings(String source,String sourceKind,boolean verifiedOuterSubject){this.source=Objects.toString(source,"");this.sourceKind=sourceKind;this.verifiedOuterSubject=verifiedOuterSubject;}
    String source(){return source;}
    String sourceKind(){return sourceKind;}

    Rule bind(Rule rule,List<Parent> parents){
        Map<String,Object> parameters=new LinkedHashMap<>(rule.parameters());
        // These are server-owned provenance fields. Model-provided copies are never trusted.
        parameters.remove("requirementBindings");parameters.remove("requirementContextIssue");parameters.remove("requirementSubjectSet");
        Map<String,Object> refs=new LinkedHashMap<>();
        int at=uniqueOffset(source,rule.sourceQuote());
        Scope scope=at<0?null:scope(rule,parents,at);
        String subject=rule.subject();
        if(subject.isBlank()&&scope!=null&&!scope.subject().isBlank())subject=scope.subject();
        if(!subject.isBlank()){
            Map<String,Object> collective=collectiveProof(subject,rule.sourceQuote(),source);
            if(!collective.isEmpty()){
                parameters.put("requirementSubjectSet",collective);
                refs.put("subject",reference(subject,source,0,"EXPLICIT_PERSONNEL_SET",""));
            }
            else if(at>=0&&rule.sourceQuote().contains(subject))refs.put("subject",reference(subject,rule.sourceQuote(),at,"LOCAL",""));
            else if(scope!=null&&subject.equals(scope.subject())){
                int prefixEnd=source.indexOf('\n');String prefix=prefixEnd>=0?source.substring(0,prefixEnd):"";
                if(verifiedOuterSubject&&scope.start()<prefixEnd&&prefix.contains(subject))refs.put("subject",reference(subject,prefix,0,"DOCUMENT_SUBJECT_INHERITANCE",""));
                else refs.put("subject",reference(subject,scope.quote(),scope.start(),scope.kind(),scope.parentId()));
            }
            else if(!rule.sourceQuote().contains(subject))parameters.put("requirementContextIssue","主体未在当前原句或唯一同作用域条款中明确；主体切换、分支和标段边界不得跨越");
        }
        for(String key:FIELDS){String value=str(parameters,key);if(value.isBlank())continue;
            if(at>=0&&rule.sourceQuote().contains(value))refs.put(key,reference(value,rule.sourceQuote(),at,"LOCAL",""));
            else if(scope!=null&&parameterUnambiguous(key,value,scope.quote()))refs.put(key,reference(value,scope.quote(),scope.start(),scope.kind(),scope.parentId()));
        }
        if(!refs.isEmpty())parameters.put("requirementBindings",refs);
        return new Rule(rule.id(),rule.description(),rule.predicate(),subject,rule.field(),rule.operator(),rule.expected(),rule.unit(),parameters,rule.sourceQuote(),rule.logic(),rule.children());
    }

    /** Explicit same-paragraph enumeration + anaphora, including branches about those people's evidence. */
    private static Map<String,Object> collectiveProof(String subject,String quote,String text){
        if(!PERSONNEL_REFERENCES.contains(subject)||!text.contains(subject)||uniqueOffset(text,quote)<0||differentLots(text))return Map.of();
        int firstEnd=text.indexOf('。');if(firstEnd<0)return Map.of();
        String heading=text.substring(0,firstEnd+1);
        if(!heading.matches("(?s)^\\s*(?:[0-9.、（）()]+\\s*)?(?:拟派)?(?:人员要求|人员配置|项目人员配置|岗位配置)\\s*[:：].*"))return Map.of();
        int wide=heading.indexOf('：'),narrow=heading.indexOf(':');int headerEnd=wide<0?narrow:narrow<0?wide:Math.min(wide,narrow);String enumeration=heading.substring(headerEnd+1);
        if(BRANCH.matcher(enumeration).find()||enumeration.matches("(?s).*(?:人员要求|人员配置|岗位配置)\\s*[:：].*")||Pattern.compile(COMPANY).matcher(enumeration).find())return Map.of();
        List<PersonCountRequirement.Match> counts=PersonCountRequirement.matches(heading);
        if(counts.isEmpty()||counts.stream().anyMatch(m->!PersonnelRoles.personRole(m.role())))return Map.of();
        List<String> roles=counts.stream().map(PersonCountRequirement.Match::role).toList();
        if(new HashSet<>(roles).size()!=roles.size()||roles.stream().map(PersonnelRoles::canonical).distinct().count()!=roles.size())return Map.of();
        // No second roster, employer, role heading or lot branch may change the antecedent.
        String following=text.substring(firstEnd+1);
        if(following.matches("(?s).*(?:人员要求|人员配置|岗位配置)\\s*[:：].*")||HEADER.matcher(following).find()
            ||Pattern.compile(COMPANY).matcher(following).find()
            ||following.matches("(?s).*(?:第[一二三四五六七八九十0-9]+标段|另[一有]|其他人员|其余人员|新增人员|变更人员).*"))return Map.of();
        return linked("kind","EXPLICIT_PERSONNEL_SET","subject",subject,"roles",roles,"sourceQuote",text,"sourceHash",identity(text),
            "enumerationQuote",heading,"enumerationStart",0,"enumerationEnd",heading.length(),"obligationQuote",quote,"offsetBasis","REQUIREMENT_TEXT_UTF16");
    }

    /** Reads only server-normalized metadata and recomputes the set from its source, never from model roles. */
    static List<String> validatedSubjectSet(Rule rule){
        if(!(rule.parameters().get("requirementSubjectSet") instanceof Map<?,?> proof))return List.of();
        String text=Objects.toString(proof.get("sourceQuote"),"");
        Map<String,Object> recomputed=collectiveProof(rule.subject(),rule.sourceQuote(),text);
        if(recomputed.isEmpty()||!recomputed.equals(proof))return List.of();
        Map<?,?> reference=validatedReference(rule,"subject",rule.subject(),text);
        if(reference==null||!"EXPLICIT_PERSONNEL_SET".equals(reference.get("bindingKind")))return List.of();
        return ProofVerifier.strings(recomputed.get("roles"));
    }

    private Scope scope(Rule rule,List<Parent> parents,int leafAt){
        // Branches never borrow context from a sibling or from above the branch boundary.
        for(int i=parents.size()-1;i>=0;i--){Parent parent=parents.get(i);
            if(Set.of("CONDITIONAL","FOR_EACH").contains(parent.logic()))break;
            int p=uniqueOffset(source,parent.quote());
            if(p<0||leafAt<p||leafAt+rule.sourceQuote().length()>p+parent.quote().length())continue;
            Scope candidate=headingScope(parent.quote(),p,leafAt,"PARENT",parent.id());
            if(candidate!=null&&leafAt+rule.sourceQuote().length()<=candidate.end()&&parent.subject().equals(candidate.subject()))return candidate;
            // An alternative changes the accepted certificate, not an explicit common actor.
            // Continue looking for the containing heading; parameter inheritance below still
            // rejects alternative certificates and each intervening subject/lot switch.
        }
        if(parents.stream().anyMatch(p->Set.of("CONDITIONAL","FOR_EACH").contains(p.logic())))return null;
        Scope candidate=headingScope(source,0,leafAt,"CLAUSE","");
        return candidate!=null&&leafAt+rule.sourceQuote().length()<=candidate.end()?candidate:null;
    }

    private Scope headingScope(String text,int base,int leafAt,String kind,String parentId){
        List<Heading> headings=new ArrayList<>();Matcher matcher=HEADER.matcher(text);
        while(matcher.find())headings.add(new Heading(matcher.group(1),matcher.start(1),matcher.end()));
        int local=leafAt-base;Heading chosen=null;int end=text.length();
        for(Heading h:headings){if(h.start()<=local)chosen=h;else if(chosen!=null){end=h.start();break;}}
        if(chosen==null||local<chosen.start()||local>=end)return null;
        String scope=text.substring(chosen.start(),end);
        String between=text.substring(chosen.end(),Math.max(chosen.end(),local));
        if(BRANCH.matcher(between).find()||differentLots(scope))return null;
        // A second explicit company/role obligation inside the span prevents borrowing the earlier subject.
        return new Scope(scope,base+chosen.start(),base+end,chosen.subject(),kind,parentId);
    }

    private boolean parameterUnambiguous(String key,String value,String text){
        if(!text.contains(value)||BRANCH.matcher(text).find()||text.contains("分别")||text.contains("或者")||text.contains("或"))return false;
        if(Set.of("category","number","validityField","grade","profession").contains(key)){
            // A context with multiple certificates cannot lend one certificate's attributes to another.
            Matcher cert=Pattern.compile("[\\p{IsHan}A-Za-z0-9（）()]{2,36}(?:证书|许可证|合格证)").matcher(text);
            Set<String> names=new HashSet<>();while(cert.find())names.add(cert.group());if(names.size()>1)return false;
        }
        if(key.equals("taxBasis")&&text.contains("不含税")&&!value.equals("不含税"))return false;
        if(key.equals("lot")&&differentLots(text))return false;
        if(key.equals("currency")){Set<String> found=new HashSet<>();Matcher m=Pattern.compile("人民币|美元|欧元|港币|CNY|RMB|USD|EUR|HKD").matcher(text);while(m.find())found.add(m.group());if(found.size()>1)return false;}
        if(key.equals("employer")){Set<String> found=new HashSet<>();Matcher m=Pattern.compile(COMPANY).matcher(text);while(m.find())found.add(m.group());if(found.size()>1)return false;}
        return true;
    }

    Map<String,Object> reference(String value,String quote,int at,String kind,String parent){
        Map<String,Object> ref=linked("value",value,"sourceQuote",quote,"start",at,"end",at+quote.length(),"sourceKind",sourceKind,"bindingKind",kind);
        if(!parent.isBlank())ref.put("parentRuleId",parent);return ref;
    }
    boolean bound(Rule rule,String field,String value){return validatedReference(rule,field,value,source)!=null;}
    static Map<?,?> validatedReference(Rule rule,String field,String value,String source){
        if(!(rule.parameters().get("requirementBindings") instanceof Map<?,?> fields)||!(fields.get(field) instanceof Map<?,?> ref))return null;
        return value.equals(ref.get("value"))&&validReference(ref,source)&&Objects.toString(ref.get("sourceQuote"),"").contains(value)?ref:null;
    }
    static boolean validReference(Map<?,?> ref,String source){
        if(!(ref.get("start") instanceof Number a)||!(ref.get("end") instanceof Number b))return false;
        int start=a.intValue(),end=b.intValue();return start>=0&&end>start&&end<=source.length()&&source.substring(start,end).equals(ref.get("sourceQuote"));
    }
    static int uniqueOffset(String source,String quote){if(quote==null||quote.isBlank())return -1;int at=source.indexOf(quote);return at>=0&&source.indexOf(quote,at+1)<0?at:-1;}
    static int subjectScopeStart(String source,int at){Matcher m=HEADER.matcher(source);int start=0;while(m.find()){if(m.start(1)>at)break;start=m.start(1);}return start;}
    static boolean hasUnboundCompany(String source,String subject){Matcher m=Pattern.compile(COMPANY).matcher(source);while(m.find())if(!m.group().equals(subject))return true;return false;}
    static boolean differentLots(String source){Matcher m=Pattern.compile("(?:第?[一二三四五六七八九十0-9A-Za-z]+标段|标段\\s*[:：]\\s*[^，。；;\\s]+)").matcher(source);Set<String> lots=new HashSet<>();while(m.find())lots.add(m.group());return lots.size()>1;}
}
