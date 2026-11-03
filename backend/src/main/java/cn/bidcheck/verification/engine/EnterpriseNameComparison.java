package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Reads both name fields locally; a document reference is never a literal company name. */
final class EnterpriseNameComparison {
    static final String VERSION="enterprise-name-source-comparison-1";
    private static final String COMPANY="[\\p{IsHan}A-Za-z0-9（）()·]{2,100}?(?:有限责任公司|股份有限公司|有限公司|公司)";
    private static final Pattern RESPONSE=Pattern.compile("(投标人名称|投标单位名称|投标人(?=[（(:：])|投标单位(?=[（(:：]))(?:[（(][^）)]{0,18}[）)])?[:：]?(?:[（(]盖章[）)])?("+COMPANY+")");
    private static final Pattern LEGAL=Pattern.compile("(企业名称|单位名称|名称)[:：]?("+COMPANY+")");
    private EnterpriseNameComparison(){}
    static boolean applies(Rule r){return r.predicate().equals("SAME_FIELD")&&SameFieldComparisonTarget.enterpriseDocuments(SameFieldComparisonTarget.reference(r))
        &&r.field().matches("(?:投标人|投标单位|企业|单位|公司)?(?:法定)?名称")&&r.sourceQuote().matches("(?s).*(?:投标人|投标单位|企业|单位|公司)名称.*");}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Text{
        private final String value;
        private final int[] offsets;
        private final String original;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Text(@com.fasterxml.jackson.annotation.JsonProperty("value") String value, @com.fasterxml.jackson.annotation.JsonProperty("offsets") int[] offsets, @com.fasterxml.jackson.annotation.JsonProperty("original") String original) { this.value = value; this.offsets = offsets; this.original = original; }
        public String value() { return value; }
        public int[] offsets() { return offsets; }
        public String original() { return original; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Text that = (Text) other; return java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.offsets, that.offsets) && java.util.Objects.equals(this.original, that.original); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.offsets); hash = 31 * hash + java.util.Objects.hashCode(this.original); return hash; }
        @Override public String toString() { return "Text[" + "value=" + value + ", offsets=" + offsets + ", original=" + original + "]"; }

        static Text of(String original){boolean[] skip=new boolean[original.length()];Matcher tags=Pattern.compile("<[^>]+>").matcher(original);while(tags.find())Arrays.fill(skip,tags.start(),tags.end(),true);
            StringBuilder value=new StringBuilder();int[] offsets=new int[original.length()];int n=0;
            for(int i=0;i<original.length();i++){char c=original.charAt(i);if(skip[i]||Character.isWhitespace(c)||"*`|_".indexOf(c)>=0)continue;value.append(c);offsets[n++]=i;}return new Text(value.toString(),Arrays.copyOf(offsets,n),original);}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Name{
        private final String value;
        private final String side;
        private final String documentType;
        private final String sourceId;
        private final Evidence source;
        private final String roleHint;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Name(@com.fasterxml.jackson.annotation.JsonProperty("value") String value, @com.fasterxml.jackson.annotation.JsonProperty("side") String side, @com.fasterxml.jackson.annotation.JsonProperty("documentType") String documentType, @com.fasterxml.jackson.annotation.JsonProperty("sourceId") String sourceId, @com.fasterxml.jackson.annotation.JsonProperty("source") Evidence source, @com.fasterxml.jackson.annotation.JsonProperty("roleHint") String roleHint) { this.value = value; this.side = side; this.documentType = documentType; this.sourceId = sourceId; this.source = source; this.roleHint = roleHint; }
        public String value() { return value; }
        public String side() { return side; }
        public String documentType() { return documentType; }
        public String sourceId() { return sourceId; }
        public Evidence source() { return source; }
        public String roleHint() { return roleHint; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Name that = (Name) other; return java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.side, that.side) && java.util.Objects.equals(this.documentType, that.documentType) && java.util.Objects.equals(this.sourceId, that.sourceId) && java.util.Objects.equals(this.source, that.source) && java.util.Objects.equals(this.roleHint, that.roleHint); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.side); hash = 31 * hash + java.util.Objects.hashCode(this.documentType); hash = 31 * hash + java.util.Objects.hashCode(this.sourceId); hash = 31 * hash + java.util.Objects.hashCode(this.source); hash = 31 * hash + java.util.Objects.hashCode(this.roleHint); return hash; }
        @Override public String toString() { return "Name[" + "value=" + value + ", side=" + side + ", documentType=" + documentType + ", sourceId=" + sourceId + ", source=" + source + ", roleHint=" + roleHint + "]"; }

        Map<String,Object> map(){return linked("value",value,"side",side,"documentType",documentType,"sourceId",sourceId,"source",source,"documentRole",roleHint);}
    }
    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        EvidenceCorpus corpus=evaluator.corpus;List<Rule> roleRules=new ArrayList<>();
        for(String role:cn.bidcheck.compat.Java8Api.listOf("投标人", "施工单位", "设计单位"))roleRules.add(new Rule("NAME-PARTY-"+role,"名称来源主体","MATERIAL",role,"","","","",cn.bidcheck.compat.Java8Api.mapOf(),"","",cn.bidcheck.compat.Java8Api.listOf()));
        List<Fact> bindings=EnterpriseSubjectBindings.extract(roleRules,corpus.all);
        List<String> parties=DomainBindings.subjects("投标人",bindings,corpus);
        if(!parties.contains(rule.subject()))return pending(rule,"SUBJECT_RELATION_UNPROVEN","名称比较尚未绑定当前投标的具体公司；证照本身不能证明持有人是投标人",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf());
        Map<String,List<String>> companiesByRole=new HashMap<>();for(String role:cn.bidcheck.compat.Java8Api.listOf("施工单位", "设计单位"))companiesByRole.put(role,DomainBindings.subjects(role,bindings,corpus));
        List<Name> left=new ArrayList<>(),right=new ArrayList<>();List<Map<String,Object>> unbound=new ArrayList<>(),unboundResponse=new ArrayList<>();String target=SameFieldComparisonTarget.reference(rule);
        for(Source source:corpus.all){if(!current(source))continue;String original=source.evidence().quote();Text text=Text.of(original);String type=certificateType(original);
            if(type.isEmpty()&&!text.value().substring(0,Math.min(text.value().length(),180)).matches(".*(?:合同协议书|甲方名称|乙方名称|合同甲方|合同乙方).*")){
                String hint=responseRoleHint(text.value());
                for(Name name:read(source,text,RESPONSE,"RESPONSE","投标响应",hint)){
                    List<String> owners=hint.isEmpty()?parties.contains(name.value())?cn.bidcheck.compat.Java8Api.listOf(name.value()):cn.bidcheck.compat.Java8Api.listOf():companiesByRole.getOrDefault(hint,cn.bidcheck.compat.Java8Api.listOf());
                    if(owners.size()==1){if(owners.get(0).equals(rule.subject()))left.add(name);}
                    else unboundResponse.add(linked("sourceId",source.id(),"responseName",name.value(),"documentRole",hint,"source",name.source()));
                }
            }
            else if(requestedType(target,type)){
                String hint=roleHint(text.value());List<Name> names=read(source,text,LEGAL,"LEGAL_NAME",type,hint);
                for(Name name:names){List<String> owners=hint.isEmpty()?parties.contains(name.value())?cn.bidcheck.compat.Java8Api.listOf(name.value()):cn.bidcheck.compat.Java8Api.listOf():companiesByRole.getOrDefault(hint,cn.bidcheck.compat.Java8Api.listOf());
                    if(owners.size()==1){if(owners.get(0).equals(rule.subject()))right.add(name);}
                    else unbound.add(linked("sourceId",source.id(),"legalName",name.value(),"documentType",type,"documentRole",hint,"source",name.source()));
                }
            }
        }
        left=distinct(left);right=distinct(right);
        Map<String,Object> proof=linked("comparisonVersion",VERSION,"proofScope","CURRENT_PARTY_OBSERVED_NAME_FIELDS_ONLY","subject",rule.subject(),"comparisonOperandKind","SOURCE_REFERENCE","comparisonTargetReference",target,"literalTargetComparisonRejected",true,
            "responseNameFields",cn.bidcheck.compat.Java8Api.toList(left.stream().map(Name::map)),"legalNameFields",cn.bidcheck.compat.Java8Api.toList(right.stream().map(Name::map)),"unmappedLegalNames",unbound,"unmappedResponseNames",unboundResponse,"comparisonComplete",false,
            "doesNotProveNameChangeException",true,"doesNotProveCertificateValidity",true,"doesNotProveAllRequiredDocumentsPresent",true);
        List<Evidence> evidence=new ArrayList<>();left.forEach(n->evidence.add(n.source()));right.forEach(n->evidence.add(n.source()));
        List<Fact> identity=cn.bidcheck.compat.Java8Api.toList(bindings.stream().filter(f->f.value().equals(rule.subject())));proof.put("roleBindings",identity);identity.stream().flatMap(f->f.sourceIds().stream()).map(corpus.byId::get).filter(Objects::nonNull).map(Source::evidence).forEach(evidence::add);
        if(left.isEmpty()||right.isEmpty())return pending(rule,"COMPARISON_SOURCE_REQUIRED","须分别定位本公司的投标响应名称与对应证照法定名称；不能拿另一成员、联合体组合名称或单端证照替代",evidence,proof);
        if(!unbound.isEmpty())return pending(rule,"COMPARISON_RECORD_IDENTITY_UNPROVEN","当前证照中仍有名称尚未对应具体投标成员；需核对所属单位或名称变更证明，不能选择性忽略",evidence,proof);
        if(!unboundResponse.isEmpty())return pending(rule,"COMPARISON_RECORD_IDENTITY_UNPROVEN","当前投标响应中仍有名称尚未对应具体投标成员；须核对文书所属公司或更名证明，不能仅保留相同名称",evidence,proof);
        Set<String> values=new LinkedHashSet<>();left.forEach(n->values.add(n.value()));right.forEach(n->values.add(n.value()));proof.put("comparedNames",values);proof.put("comparisonComplete",true);
        if(values.size()>1){
            String requirement=fullRequirement(rule,evaluator);proof.put("nameChangeRequirementQuote",requirement);
            if(requirement.matches("(?s).*名称.{0,12}(?:有变更|变更).{0,20}(?:证明|文件).*")){proof.put("comparisonComplete",false);return pending(rule,"COMPARISON_RECORD_IDENTITY_UNPROVEN","已定位同一成员的名称差异；原要求允许凭有效更名证明说明，须先核实该例外，不能直接判定不符合",evidence,proof);}
            return new Decision(rule.id(),rule.description(),"FAIL","同一成员的投标响应名称与明确对应的证照法定名称不一致",cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),cn.bidcheck.compat.Java8Api.listOf(),proof);
        }
        return new Decision(rule.id(),rule.description(),"PASS","已逐公司核对本次投标响应名称与对应证照法定名称；已定位的两端字段一致，更名条件及证照有效性另行判断",cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),cn.bidcheck.compat.Java8Api.listOf(),proof);
    }
    private static List<Name> distinct(List<Name> names){Map<String,Name> unique=new LinkedHashMap<>();for(Name n:names)unique.put(n.side()+n.source().documentId()+n.source().page()+n.source().anchor()+n.value(),n);return cn.bidcheck.compat.Java8Api.listCopyOf(unique.values());}
    private static boolean current(Source s){return !s.index()&&!s.historical()&&!s.scopeContext().blocked()&&cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "CURRENT_DOCUMENT", "CURRENT_BID_SECTION").contains(s.scopeContext().status())
        &&!s.evidence().quote().matches("(?s).*(?:填写示例|空白模板|仅供参考|示范文本).*");}
    private static String certificateType(String original){String opening=original.substring(0,Math.min(original.length(),1000));
        if(Text.of(opening).value().matches(".*(?:合同协议书|合同甲方|合同乙方|甲方名称|乙方名称).*"))return "";
        for(String raw:opening.split("\\R")){String line=Text.of(raw).value().replaceFirst("^[#0-9一二三四五六七八九十、.（）()]+","");
            if(line.matches("(?:(?:施工|设计)(?:单位|成员))?(?:营业执照|登记证书)(?:扫描件|复印件|副本|[（(]副本[）)])?"))return "营业执照";
            if(line.matches("(?:(?:施工|设计)(?:单位|成员))?(?:企业)?(?:资质证书|资质证明(?:文件)?)(?:扫描件|复印件)?"))return "资质证书";
            if(line.matches("(?:法人证书|其他组织证明(?:文件)?)(?:扫描件|复印件)?"))return "其他组织证明";
        }return "";}
    private static boolean requestedType(String reference,String type){return type.equals("营业执照")?reference.matches(".*(?:营业执照|登记证书).*" ):type.equals("资质证书")?reference.matches(".*资质(?:证书|证明).*" ):type.equals("其他组织证明")&&reference.matches(".*(?:法人证书|其他组织证明).*" );}
    private static String roleHint(String text){Matcher m=Pattern.compile("(施工|设计)(?:单位|成员)?(?:营业执照|登记证书|资质证明|资质证书)").matcher(text.substring(0,Math.min(text.length(),500)));Set<String> roles=new HashSet<>();while(m.find())roles.add(m.group(1)+"单位");return roles.size()==1?roles.iterator().next():roles.isEmpty()?"":"AMBIGUOUS";}
    private static String responseRoleHint(String text){String heading=text.substring(0,Math.min(text.length(),140));Matcher m=Pattern.compile("(?:法定代表人(?:[（(]负责人[）)])?身份证明[（(](施工|设计)单位[）)]|(施工|设计)单位(?:股权及管理关系|承诺书|投标函))").matcher(heading);Set<String> roles=new HashSet<>();while(m.find())roles.add((m.group(1)==null?m.group(2):m.group(1))+"单位");return roles.size()==1?roles.iterator().next():roles.isEmpty()?"":"AMBIGUOUS";}
    /** Recover the containing obligation from its immutable tender span, never from model prose. */
    private static String fullRequirement(Rule r,RuleEvaluator evaluator){
        if(!(r.parameters().get("requirementSourceContext") instanceof Map<?,?>)||!(((Map<?,?>) (r.parameters().get("requirementSourceContext"))).get("identity") instanceof Map<?,?>))return r.sourceQuote();
        if(!(evaluator.context.get("tenderDocuments") instanceof List<?>))return r.sourceQuote();
        for(Object value:((List<?>) (evaluator.context.get("tenderDocuments"))))if(value instanceof cn.bidcheck.domain.Domain.Document&&((cn.bidcheck.domain.Domain.Document) (value)).role().equals("TENDER")&&((cn.bidcheck.domain.Domain.Document) (value)).id().equals(((Map<?,?>) (((Map<?,?>) (r.parameters().get("requirementSourceContext"))).get("identity"))).get("documentId"))&&((cn.bidcheck.domain.Domain.Document) (value)).sha256().equals(((Map<?,?>) (((Map<?,?>) (r.parameters().get("requirementSourceContext"))).get("identity"))).get("documentHash"))&&((cn.bidcheck.domain.Domain.Document) (value)).projectId().equals(((Map<?,?>) (((Map<?,?>) (r.parameters().get("requirementSourceContext"))).get("identity"))).get("projectId"))&&Objects.equals(((cn.bidcheck.domain.Domain.Document) (value)).version(),((Map<?,?>) (((Map<?,?>) (r.parameters().get("requirementSourceContext"))).get("identity"))).get("documentVersion"))){
            for(cn.bidcheck.domain.Domain.Page p:((cn.bidcheck.domain.Domain.Document) (value)).pages())if(Objects.equals(p.number(),((Map<?,?>) (((Map<?,?>) (r.parameters().get("requirementSourceContext"))).get("identity"))).get("location"))&&p.locationType().equals(((Map<?,?>) (((Map<?,?>) (r.parameters().get("requirementSourceContext"))).get("identity"))).get("locationType"))&&((Map<?,?>) (((Map<?,?>) (r.parameters().get("requirementSourceContext"))).get("identity"))).get("start") instanceof Number&&((Map<?,?>) (((Map<?,?>) (r.parameters().get("requirementSourceContext"))).get("identity"))).get("end") instanceof Number){int start=((Number) (((Map<?,?>) (((Map<?,?>) (r.parameters().get("requirementSourceContext"))).get("identity"))).get("start"))).intValue(),end=((Number) (((Map<?,?>) (((Map<?,?>) (r.parameters().get("requirementSourceContext"))).get("identity"))).get("end"))).intValue();if(start<0||end<=start||end>p.text().length())continue;String original=p.text().substring(start,end);
                if(VerificationTypes.identity(original).equals(((Map<?,?>) (((Map<?,?>) (r.parameters().get("requirementSourceContext"))).get("identity"))).get("sourceHash"))&&RequirementContextBindings.uniqueOffset(original,r.sourceQuote())>=0)return original;}
        }return r.sourceQuote();
    }
    private static List<Name> read(Source source,Text text,Pattern pattern,String side,String type,String hint){List<Name> result=new ArrayList<>();Matcher m=pattern.matcher(text.value());
        while(m.find()){String value=m.group(2);String tail=text.value().substring(m.end(),Math.min(text.value().length(),m.end()+110));if(value.matches(".*(?:示例|某某|待填|XX).*" )||tail.matches("^(?:联合体|[、和与及]"+COMPANY+").*"))continue;
            int start=text.offsets()[m.start(2)],end=text.offsets()[m.end(2)-1]+1;Evidence base=source.evidence();int offset=0;Matcher span=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(base.anchor());if(span.matches())offset=Integer.parseInt(span.group(1));
            Evidence evidence=new Evidence(base.documentId(),base.documentName(),base.page(),base.locationType(),text.original().substring(start,end),"chars:"+(offset+start)+"-"+(offset+end),base.confidence());result.add(new Name(value,side,type,source.id(),evidence,hint));}
        return result;}
    private static Decision pending(Rule rule,String code,String reason,List<Evidence> evidence,Map<String,Object> proof){return new Decision(rule.id(),rule.description(),"UNDETERMINED",reason,cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),cn.bidcheck.compat.Java8Api.listOf(new Gap(code,reason,"由系统对齐具体公司的两端名称与更名证明","INPUT_OR_EVIDENCE_CHANGED")),proof);}
}
