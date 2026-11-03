package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import cn.bidcheck.verification.engine.VerificationTypes.Fact;

/** Source-bound submission conditions. A missing file never proves that its condition is inactive. */
final class ConditionalMaterialEvidence {
    static final String VERSION="conditional-material-submission-2-compound-reuse";
    private static final String JOINT="联合体协议书",AUTH="法定代表人（负责人）授权委托书";
    private static final String COMPANY="[\\p{IsHan}A-Za-z0-9（）()·]{2,100}?(?:有限责任公司|股份有限公司|有限公司|公司)";
    private static final Pattern AUTHORIZATION=Pattern.compile("本人([\\p{IsHan}·]{2,12})系("+COMPANY+")(?:的)?法定代表人(?:[（(]负责人[）)])?[，,。]?现委托([\\p{IsHan}·]{2,12})为我方代理人[。；;]");
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Kind{
        private final String name;
        private final String condition;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Kind(@com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("condition") String condition) { this.name = name; this.condition = condition; }
        public String name() { return name; }
        public String condition() { return condition; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Kind that = (Kind) other; return java.util.Objects.equals(this.name, that.name) && java.util.Objects.equals(this.condition, that.condition); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.name); hash = 31 * hash + java.util.Objects.hashCode(this.condition); return hash; }
        @Override public String toString() { return "Kind[" + "name=" + name + ", condition=" + condition + "]"; }
}
    private ConditionalMaterialEvidence(){}

    static Rule compile(Checkpoint checkpoint,String source,RequirementSourceContext.Context sourceContext,Map<String,Object> runContext){
        Kind kind=kind(source);
        if(kind==null||sourceContext.identity().isEmpty()||!reviewedMeaningPreserved(checkpoint,source,kind))return null;
        Map<String,Object> parameters=linked("deterministicFamily","CONDITIONAL_MATERIAL_SUBMISSION","conditionalMaterialVersion",VERSION,
                "materialType",kind.name(),"applicabilityCondition",kind.condition(),"proofScope","MATERIAL_SUBMISSION_ONLY",
                "reviewedExpected",checkpoint.expected(),"requirementSourceContext",sourceContext.map());
        return new Rule("COND-MATERIAL-"+identity(source).substring(0,16),kind.name()+"提交及适用条件","MATERIAL","","conditionalMaterial","PRESENT",kind.name(),"",parameters,source,"",cn.bidcheck.compat.Java8Api.listOf());
    }
    static boolean applies(Rule rule){return VERSION.equals(rule.parameters().get("conditionalMaterialVersion"))&&rule.predicate().equals("MATERIAL");}

    /** The same source-verified material obligation used inside a compound requirement. */
    static Rule embedded(String source,Map<String,Object> sourceContext){
        Kind kind=kind(source);if(kind==null)return null;
        return new Rule("COND-MATERIAL-"+identity(source).substring(0,16),kind.name()+"提交及适用条件","MATERIAL","","conditionalMaterial","PRESENT",kind.name(),"",
                linked("conditionalMaterialVersion",VERSION,"deterministicFamily","CONDITIONAL_MATERIAL_SUBMISSION","materialType",kind.name(),"applicabilityCondition",kind.condition(),"proofScope","MATERIAL_SUBMISSION_ONLY","requirementSourceContext",sourceContext),source,"",cn.bidcheck.compat.Java8Api.listOf());
    }

    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        Kind kind=kind(rule.sourceQuote());Document tender=kind==null||!rule.children().isEmpty()||!cn.bidcheck.compat.Java8Api.isBlank(rule.subject())||!cn.bidcheck.compat.Java8Api.isBlank(rule.logic())||!rule.field().equals("conditionalMaterial")||!rule.operator().equals("PRESENT")?null:tender(rule,evaluator);
        if(tender==null||!kind.name().equals(rule.expected()))return pending(rule,"CONDITIONAL_MATERIAL_SOURCE_REQUIRED","条件材料尚未绑定本次冻结招标文件中的明确适用条件",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf());
        List<Source> current=cn.bidcheck.compat.Java8Api.toList(evaluator.corpus.all.stream().filter(s->current(s,tender)));
        Rule membership=new Rule(rule.id()+":members","本次联合体成员","RELATION","联合体成员","名称","=","","",cn.bidcheck.compat.Java8Api.mapOf(),rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        Rule bidder=new Rule(rule.id()+":bidder","本次投标单位","RELATION","投标人","名称","=","","",cn.bidcheck.compat.Java8Api.mapOf(),rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        List<Fact> parties=EnterpriseSubjectBindings.extract(cn.bidcheck.compat.Java8Api.listOf(membership, bidder),evaluator.corpus.all);
        Map<String,Object> proof=linked("executor",VERSION,"proofScope","CONDITIONAL_MATERIAL_SUBMISSION_ONLY","sourceCondition",kind.condition(),
                "doesNotProveSignatureOrAuthorityValidity",true,"absenceMeansInapplicable",false,"modelRequests",0,"programSourceRead",ProgramSourceRead.receipt(evaluator.corpus,current));
        List<Source> materials=new ArrayList<>();List<Map<String,Object>> bindings=new ArrayList<>();
        for(Source source:current){
            String body=body(source);
            if(cn.bidcheck.compat.Java8Api.isBlank(body))continue;
            if(kind.name().equals(JOINT)){
                if(!heading(source,JOINT)||!body.contains("联合体")||!body.contains("共同投标")||!body.contains("订立如下协议"))continue;
                List<Fact> members=cn.bidcheck.compat.Java8Api.toList(parties.stream().filter(f->f.subject().equals("联合体成员")&&f.sourceIds().contains(source.id())));
                List<String> names=cn.bidcheck.compat.Java8Api.toList(members.stream().map(Fact::value).distinct());
                if(names.size()<2||EnterpriseSubjectBindings.completeSet(names,evaluator.corpus).isEmpty())continue;
                if(!body.matches("(?s).*(?:共同承担|连带责任|履行合同|合同责任).*"))continue;
                materials.add(source);bindings.add(linked("sourceId",source.id(),"members",names,"basis","SOURCE_VERIFIED_COMPLETE_MEMBER_SET"));
            }else{
                if(!heading(source,"授权委托书"))continue;
                Matcher authorization=AUTHORIZATION.matcher(body);if(!authorization.find())continue;
                String principal=authorization.group(1),company=authorization.group(2),agent=authorization.group(3);
                if(!filledPerson(principal)||!filledPerson(agent)||!body.contains("代理人根据授权")||!body.contains("以我方名义")||!body.contains("投标文件")||!body.contains("其法律后果由我方承担"))continue;
                List<Fact> companyLinks=cn.bidcheck.compat.Java8Api.toList(parties.stream().filter(f->f.value().equals(company)&&cn.bidcheck.compat.Java8Api.setOf("投标人", "联合体成员").contains(f.subject())));
                if(companyLinks.isEmpty())continue;
                materials.add(source);bindings.add(linked("sourceId",source.id(),"principal",principal,"company",company,"agent",agent,"companyRelationSources",cn.bidcheck.compat.Java8Api.toList(companyLinks.stream().flatMap(f->f.sourceIds().stream()).distinct()),"basis","FILLED_CURRENT_AUTHORIZATION_BODY"));
            }
        }
        proof.put("sourceBindings",bindings);
        if(!materials.isEmpty())return new Decision(rule.id(),rule.description(),"PASS","已定位本项目实际填写的"+kind.name()+"正文及主体关系；本项仅核验材料提交，签章等独立要求另行核验",cn.bidcheck.compat.Java8Api.toList(materials.stream().map(Source::evidence).distinct()),cn.bidcheck.compat.Java8Api.listOf(),proof);
        if(kind.name().equals(JOINT)){
            List<Source> nonJoint=cn.bidcheck.compat.Java8Api.toList(current.stream().filter(s->EnterpriseSubjectBindings.formalDocument(s)&&explicitNonJoint(body(s)))
                    .filter(s->parties.stream().anyMatch(f->f.subject().equals("投标人")&&f.sourceIds().contains(s.id()))));
            // A positive joint declaration elsewhere prevents a contradictory negative assertion from closing applicability.
            if(!nonJoint.isEmpty()&&!EnterpriseSubjectBindings.consortiumDeclared(evaluator.corpus.all)){
                proof.put("inactiveConditionProof","EXPLICIT_CURRENT_NON_CONSORTIUM_DECLARATION");
                return new Decision(rule.id(),rule.description(),"NOT_APPLICABLE","招标原文将该材料限定为联合体适用，且本项目投标原文明确声明不采用联合体",cn.bidcheck.compat.Java8Api.toList(nonJoint.stream().map(Source::evidence).distinct()),cn.bidcheck.compat.Java8Api.listOf(),proof);
            }
            boolean joint=EnterpriseSubjectBindings.consortiumDeclared(evaluator.corpus.all);
            return pending(rule,joint?"CONDITIONAL_MATERIAL_BODY_REQUIRED":"CONSORTIUM_APPLICABILITY_PROOF_REQUIRED",joint?"已证明本次采用联合体，但尚未定位含实际成员及协议责任的本项目协议正文":"尚未取得本次联合体组成或明确非联合体声明；材料未找到不代表不适用",cn.bidcheck.compat.Java8Api.listOf(),proof);
        }
        return pending(rule,"AUTHORIZATION_OR_DIRECT_SIGNER_PROOF_REQUIRED","尚未定位本项目实际填写的授权正文，也未取得法定代表人直接签署的主体证明；印刷姓名、空白签字栏和材料缺失均不能证明免交",cn.bidcheck.compat.Java8Api.listOf(),proof);
    }
    private static Kind kind(String source){
        String text=canonical(source).replaceFirst("[。.]$","");
        if(text.equals("联合体协议书(如有)"))return new Kind(JOINT,"如有联合体");
        if(text.matches("联合体投标时须提交联合体文件\\(见第[一二三四五六七八九十0-9]+章中联合体文件\\)"))return new Kind(JOINT,"如有联合体");
        Matcher authorization=Pattern.compile("法定代表人(?:\\(负责人\\))?授权委托书\\((法定代表人直接签署投标文件时(?:无需|无须)提交)\\)").matcher(text);
        return authorization.matches()?new Kind(AUTH,authorization.group(1)):null;
    }
    private static boolean reviewedMeaningPreserved(Checkpoint cp,String source,Kind kind){
        if(cp.source()==null)return false;
        String reviewed=canonical(cp.expected()),original=canonical(cp.source().quote());
        Set<String> allowed=new HashSet<>(cn.bidcheck.compat.Java8Api.listOf(canonical(source), original+"；按招标文件确认适用条件"));
        if(kind.name().equals(JOINT))allowed.add(original);
        else {allowed.add(original+"；按招标文件确认适用条件。"+kind.condition());allowed.add(original+"。"+kind.condition());}
        return allowed.contains(reviewed)||allowed.contains(reviewed.replaceFirst("[。.]$",""));
    }
    private static String canonical(String value){return clean(value).replace('（','(').replace('）',')').replace("《","").replace("》","");}
    private static boolean current(Source source,Document tender){
        if(source.index()||source.historical()||source.scopeContext().blocked()||!cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "CURRENT_DOCUMENT", "CURRENT_BID_SECTION").contains(source.scopeContext().status()))return false;
        String project=clean(source.scopeContext().projectName());
        return !cn.bidcheck.compat.Java8Api.isBlank(project)&&clean(source.evidence().quote()).contains(project)&&tender.pages().stream().limit(12).anyMatch(p->clean(p.text()).contains(project));
    }
    private static String body(Source source){
        String text=source.evidence().quote().split("(?:编制及签署要求|填写说明|编制说明)",2)[0];
        return text.matches("(?s).*(?:填写示例|示范文本|仅供参考|空白模板|待填写|某某|XX公司).*" )?"":clean(text);
    }
    private static boolean heading(Source source,String name){
        String opening=clean(source.opening());
        return opening.contains(name)&&!opening.matches(".*(?:目录|索引|材料清单|附件清单|无需提交|不需提交).*" );
    }
    private static boolean filledPerson(String value){return !value.matches(".*(?:姓名|填写|代表人|负责人|代理人|某某|示例|投标人|单位).*" );}
    private static boolean explicitNonJoint(String body){
        return Pattern.compile("(?:^|[。；;])(?:本次投标|本项目投标|我方本次投标)(?:不采用联合体(?:形式|方式)?|为非联合体(?:形式|方式)?)[。；;]").matcher(body).find();
    }
    private static Document tender(Rule rule,RuleEvaluator evaluator){
        if(!(rule.parameters().get("requirementSourceContext") instanceof Map<?,?>)||!(((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity") instanceof Map<?,?>)||!(evaluator.context.get("tenderDocuments") instanceof List<?>))return null;
        for(Object value:((List<?>) (evaluator.context.get("tenderDocuments"))))if(value instanceof Document&&((Document) (value)).role().equals("TENDER")&&((Document) (value)).id().equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentId"))&&((Document) (value)).sha256().equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentHash"))&&Objects.equals(((Document) (value)).version(),((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentVersion"))&&((Document) (value)).projectId().equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("projectId"))&&evaluator.corpus.documents.stream().allMatch(b->b.projectId().equals(((Document) (value)).projectId()))){
            String original=span(((Document) (value)),((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))));
            if(original==null||!VerificationTypes.identity(original).equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("sourceHash")))continue;
            if(RequirementContextBindings.uniqueOffset(original,rule.sourceQuote())>=0)return ((Document) (value));
            if(((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("sources") instanceof List<?>)for(Object source:((List<?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("sources"))))if(source instanceof Map<?,?>&&"SCOPED_BOOK_CONDITION".equals(((Map<?,?>) (source)).get("kind"))){
                String quote=span(((Document) (value)),((Map<?,?>) (source)));
                if(quote!=null&&quote.equals(((Map<?,?>) (source)).get("sourceQuote"))&&RequirementContextBindings.uniqueOffset(quote,rule.sourceQuote())>=0)return ((Document) (value));
            }
        }
        return null;
    }
    private static String span(Document document,Map<?,?> row){
        if(!document.id().equals(row.get("documentId"))||!document.sha256().equals(row.get("documentHash"))||!Objects.equals(document.version(),row.get("documentVersion"))||!(row.get("location") instanceof Number)||!(row.get("start") instanceof Number)||!(row.get("end") instanceof Number))return null;
        for(Page page:document.pages())if(page.number()==((Number) (row.get("location"))).intValue()&&page.locationType().equals(row.get("locationType"))){int start=((Number) (row.get("start"))).intValue(),end=((Number) (row.get("end"))).intValue();if(start>=0&&end>start&&end<=page.text().length())return page.text().substring(start,end);}
        return null;
    }
    private static Decision pending(Rule rule,String code,String reason,List<Evidence> evidence,Map<String,Object> proof){return new Decision(rule.id(),rule.description(),"UNDETERMINED",reason,evidence,cn.bidcheck.compat.Java8Api.listOf(gap(code,reason)),proof);}
}
