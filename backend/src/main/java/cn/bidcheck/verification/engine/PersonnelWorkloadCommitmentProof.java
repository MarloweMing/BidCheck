package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A small source-bound obligation grammar; this proves a written undertaking, never actual workload. */
final class PersonnelWorkloadCommitmentProof {
    static final String VERSION="personnel-workload-commitment-1";
    private static final String LIMIT="法律规定的同时承接项目的最大数量";
    private static final String PEOPLE="(?:拟(?:委派|派遣|派|投入)(?:的)?(?:全部|所有)?关键岗位人员|本项目(?:全部|所有)关键岗位人员)";
    private static final String NEGATIVE="(?:未|未曾|没有|不|不得)(?:超出|超过)";
    private static final Pattern REQUIREMENT=Pattern.compile("^(上述人员|以上人员|前述人员|上述各岗位人员)"+NEGATIVE+LIMIT+"承接本项目[。；;]?$" );
    private static final Pattern RESPONSE=Pattern.compile("(?:^|[：:。；;，,])(?:我方|我公司|本公司)(?:郑重)?承诺[：:]?("+PEOPLE+")("+NEGATIVE+")"+LIMIT+"承接本项目(?=[，,。；;]|$)");
    private static final Pattern ISSUER=Pattern.compile("(?m)^\\s*投标人(?:名称)?(?:[（(](?:盖章|公章|单位公章)[）)])?\\s*[:：]\\s*([\\p{IsHan}A-Za-z0-9（）()·]{2,100}?(?:有限责任公司|股份有限公司|有限公司|公司))\\s*$");
    private static final Pattern UNSAFE=Pattern.compile("填写示例|空白模板|示范文本|仅供参考|待填写|待签署|尚未提交|不适用|非本次|仅适用于|历史项目|已完成项目|不予承诺|不承诺|无法承诺|未承诺|不能保证|不保证|除外|例外|但是|但不|除非|原则上|尽量|部分人员|部分关键|其他人员|另行约定|仅限|若中标|如中标");
    private PersonnelWorkloadCommitmentProof(){}

    static boolean applies(Rule r){return r.predicate().equals("DECLARATION")&&r.field().equals("commitment")
        &&str(r.parameters(),"documentType").equals("人员承诺书")&&str(r.parameters(),"proofScope").equals("WRITTEN_RESPONSE")
        &&str(r.parameters(),"deterministicFamily").equals("SOURCE_AUTHORIZED_COMMITMENT")
        &&clean(r.expected()).contains("承接项目")&&clean(r.expected()).contains("最大数量");}

    static Decision evaluate(Rule r,List<Fact> matched,RuleEvaluator evaluator){
        Binding binding=binding(r,evaluator);Matcher requirement=REQUIREMENT.matcher(clean(r.expected()));
        if(binding==null||!requirement.matches()||!clean(r.sourceQuote()).replaceAll("[。；;]$","").equals(clean(r.expected()).replaceAll("[。；;]$","")))
            return evaluator.unknown(r,"COMMITMENT_REQUIREMENT_BINDING_REQUIRED","人员承诺的人员范围、法定上限和本项目范围尚未与招标原要求对应",cn.bidcheck.compat.Java8Api.listOf());
        Rule collective=new Rule(r.id(),r.description(),r.predicate(),requirement.group(1),r.field(),r.operator(),r.expected(),r.unit(),cn.bidcheck.compat.Java8Api.mapOf(),r.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        Rule bound=new RequirementContextBindings(binding.evidence().quote(),"FROZEN_TENDER").bind(collective,cn.bidcheck.compat.Java8Api.listOf());
        List<String> roles=RequirementContextBindings.validatedSubjectSet(bound);
        if(roles.isEmpty())return evaluator.unknown(r,"COMMITMENT_PERSONNEL_SCOPE_REQUIRED","“上述人员”的同条款岗位集合尚未明确，不能把部分岗位承诺当作全体承诺",cn.bidcheck.compat.Java8Api.listOf());

        Rule party=new Rule(r.id()+":issuer","当前投标人","RELATION","投标人","名称","","","",cn.bidcheck.compat.Java8Api.mapOf(),"","",cn.bidcheck.compat.Java8Api.listOf());
        List<Fact> parties=EnterpriseSubjectBindings.extract(cn.bidcheck.compat.Java8Api.listOf(party),evaluator.corpus.all);
        Set<String> companies=new LinkedHashSet<>(DomainBindings.subjects("投标人",parties,evaluator.corpus));
        Map<String,DocumentScopeProof.Resolver> resolvers=new HashMap<>();
        Map<String,Map<String,Object>> sourceProofs=new LinkedHashMap<>();List<Fact> accepted=new ArrayList<>();
        List<Map<String,Object>> rejected=new ArrayList<>();Set<String> contradictions=new LinkedHashSet<>();
        for(Source source:evaluator.corpus.all){
            if(source.index()||!source.opening().contains("人员承诺书"))continue;
            Document document=evaluator.corpus.documents.stream().filter(d->d.id().equals(source.evidence().documentId())&&d.role().equals("BID")&&d.projectId().equals(binding.document().projectId())).findFirst().orElse(null);
            if(document==null)continue;
            Map<String,Object> scope=resolvers.computeIfAbsent(document.id(),id->DocumentScopeProof.forDocument(document)).currentEvidence(source.evidence());
            if(!Boolean.TRUE.equals(scope.get("verifiedCurrent"))||!(scope.get("sourceScope") instanceof Map<?,?>))continue;
            String project=Objects.toString(((Map<?,?>) (scope.get("sourceScope"))).get("projectName"),"");
            if(cn.bidcheck.compat.Java8Api.isBlank(project)||binding.document().pages().stream().limit(12).noneMatch(p->clean(p.text()).contains(clean(project))))continue;
            String original=source.evidence().quote(),text=clean(original);Matcher issuer=ISSUER.matcher(original);
            if(!issuer.find())continue;String company=issuer.group(1);if(issuer.find()||!companies.contains(company))continue;
            Matcher response=RESPONSE.matcher(text);boolean positive=response.find();
            // Refuse qualifications, mixed responses and unsupported syntax instead of widening the contains test.
            if(UNSAFE.matcher(text).find()||positive&&response.find()
                ||text.matches("(?s).*人员(?:已|已经)?(?:超出|超过)"+LIMIT+".*")){
                if(text.contains("承接项目")&&text.contains("最大数量"))contradictions.add(source.id());continue;
            }
            if(!positive)continue;
            List<Fact> candidates=cn.bidcheck.compat.Java8Api.toList(matched.stream().filter(f->f.kind().equals("DECLARATION")&&f.scope().equals("CURRENT_PROJECT")
                &&(cn.bidcheck.compat.Java8Api.isBlank(f.subject())||f.subject().equals(company))&&f.sourceIds().contains(source.id())));
            for(Fact fact:candidates){
                // Revalidate the source-local raw and normalized value. A model basis/explanation is not evidence.
                String issue=new ProofVerifier().issue(fact,evaluator.corpus.byId);
                if(issue!=null||!ProofVerifier.contains(original,fact.rawValue())||!RESPONSE.matcher(clean(fact.rawValue())).find()){
                    rejected.add(linked("factId",fact.id(),"sourceId",source.id(),"code",issue==null?"COMMITMENT_ATOMIC_ASSERTION_REQUIRED":issue));continue;
                }
                accepted.add(fact);sourceProofs.put(source.id(),linked("sourceScope",scope,"issuer",company,
                    "sourceQuote",original,"assertionQuote",fact.rawValue(),"personnelScope","ALL_PROPOSED_KEY_PERSONNEL",
                    "polarity","NOT_EXCEED","limitBasis","STATUTORY_SIMULTANEOUS_PROJECT_MAXIMUM","projectScope","INCLUDING_THIS_PROJECT"));
            }
        }
        Map<String,Object> proof=linked("executor",VERSION,"proofScope","WRITTEN_WORKLOAD_UNDERTAKING_ONLY",
            "requiredRoles",roles,"requirementSource",binding.evidence(),"requirementPersonSet",bound.parameters().get("requirementSubjectSet"),
            "sourceProofs",sourceProofs,"rejectedCandidates",rejected,"actualWorkloadVerified",false,"signatureVerified",false);
        if(!contradictions.isEmpty()){proof.put("conflictingSourceIds",contradictions);return evaluator.unknown(r,"COMMITMENT_RESPONSE_CONFLICT","同范围人员承诺出现否定、例外或限制条件，不能按笼统承诺覆盖",accepted,proof);}
        if(accepted.isEmpty())return evaluator.unknown(r,"CONTENT_EVIDENCE_REQUIRED","尚未取得当前投标主体对全部拟派关键岗位人员、不超法定承接上限且覆盖本项目的完整书面承诺",matched,proof);
        return evaluator.pass(r,"当前投标主体已书面承诺拟派关键岗位人员承接本项目不超过法定同时承接上限；仅证明承诺内容，不证明实际在建数量或签章",cn.bidcheck.compat.Java8Api.toList(accepted.stream().distinct()),proof);
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Binding{
        private final Document document;
        private final Evidence evidence;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Binding(@com.fasterxml.jackson.annotation.JsonProperty("document") Document document, @com.fasterxml.jackson.annotation.JsonProperty("evidence") Evidence evidence) { this.document = document; this.evidence = evidence; }
        public Document document() { return document; }
        public Evidence evidence() { return evidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Binding that = (Binding) other; return java.util.Objects.equals(this.document, that.document) && java.util.Objects.equals(this.evidence, that.evidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.document); hash = 31 * hash + java.util.Objects.hashCode(this.evidence); return hash; }
        @Override public String toString() { return "Binding[" + "document=" + document + ", evidence=" + evidence + "]"; }
}
    private static Binding binding(Rule rule,RuleEvaluator evaluator){
        if(!(rule.parameters().get("requirementSourceContext") instanceof Map<?,?>)||!(((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity") instanceof Map<?,?>)
            ||!(evaluator.context.get("tenderDocuments") instanceof List<?>))return null;
        for(Object item:((List<?>) (evaluator.context.get("tenderDocuments"))))if(item instanceof Document&&((Document) (item)).role().equals("TENDER")&&((Document) (item)).id().equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentId"))
            &&((Document) (item)).sha256().equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentHash"))&&((Document) (item)).projectId().equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("projectId"))&&Objects.equals(((Document) (item)).version(),((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("documentVersion"))){
            for(Page page:((Document) (item)).pages())if(Objects.equals(page.number(),((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("location"))&&page.locationType().equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("locationType"))
                &&((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("start") instanceof Number&&((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("end") instanceof Number){
                int start=((Number) (((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("start"))).intValue(),end=((Number) (((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("end"))).intValue();if(start<0||end<=start||end>page.text().length())continue;String quote=page.text().substring(start,end);
                if(identity(quote).equals(((Map<?,?>) (((Map<?,?>) (rule.parameters().get("requirementSourceContext"))).get("identity"))).get("sourceHash"))&&RequirementContextBindings.uniqueOffset(quote,rule.sourceQuote())>=0
                    &&quote.contains("《人员承诺书》")&&quote.matches("(?s).*(?:须|应|需)(?:出具|提供|提交)承诺(?:文件|书).*"))
                    return new Binding(((Document) (item)),new Evidence(((Document) (item)).id(),((Document) (item)).name(),page.number(),page.locationType(),quote,"chars:"+start+"-"+end,1));
            }
        }return null;
    }
}
