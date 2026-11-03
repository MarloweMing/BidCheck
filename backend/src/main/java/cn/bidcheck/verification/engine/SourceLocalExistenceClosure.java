package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.*;

/** A current, single appointment proves existence without exhausting unrelated candidates.
 * This exception never proves the population, qualifications or identity across sources. */
final class SourceLocalExistenceClosure {
    private SourceLocalExistenceClosure() {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> FALSE_CLAIMS=cn.bidcheck.compat.Java8Api.setOf("upperBoundProven", "exactCountProven", "concurrentRolesProven", "qualificationCountProven", "globalIdentityProven", "populationComplete");

    static boolean proven(Rule rule,Decision decision,RuleEvaluator evaluator){
        if(ProgramSourceRead.applies(rule))return ProgramSourceRead.proven(rule,decision,evaluator);
        if(!rule.predicate().equals("PERSON_COUNT")||!rule.children().isEmpty()||!cn.bidcheck.compat.Java8Api.setOf("", "ALL").contains(rule.logic())
            ||!decision.status().equals("PASS")||!decision.gaps().isEmpty()||!rule.id().equals(decision.id())
            ||!PersonCountRequirement.roleConfiguration(rule)||!one(rule.expected()))return false;
        // The narrow exception has no cross-member, cross-lot, exhaustive or qualified-person claim.
        if(Boolean.TRUE.equals(rule.parameters().get("requiresFullDocument"))||Boolean.TRUE.equals(rule.parameters().get("fullDocumentRead"))
            ||!cn.bidcheck.compat.Java8Api.isBlank(str(rule.parameters(),"employer"))||!cn.bidcheck.compat.Java8Api.isBlank(str(rule.parameters(),"lot"))
            ||!RequirementContextBindings.validatedSubjectSet(rule).isEmpty())return false;
        Map<String,Object> proof=decision.proof();
        if(!proofShape(rule,proof))return false;
        List<Map<String,Object>> witnesses=RuleEvaluator.maps(proof.get("sourceLocalExistenceWitness"));
        if(witnesses.size()!=1)return false;
        Map<String,Object> witness=witnesses.get(0);
        String factId=str(witness,"factId"),sourceId=str(witness,"sourceId"),person=str(witness,"personName");
        if(!"EXPLICIT_CURRENT_SINGLE_APPOINTMENT".equals(witness.get("confirmation"))
            ||!"SOURCE_LOCAL".equals(witness.get("identityScope"))||!"EXISTENCE_ONLY".equals(witness.get("proves"))
            ||!strings(proof.get("factIds")).contains(factId))return false;
        List<Fact> matches=cn.bidcheck.compat.Java8Api.toList(evaluator.facts.stream().filter(f->f.id().equals(factId)));
        if(matches.size()!=1)return false;
        Fact fact=matches.get(0);Source source=evaluator.corpus.byId.get(sourceId);
        if(source==null||!fact.sourceIds().contains(sourceId)||!fact.kind().equals("PERSON_ROLE")
            ||!fact.scope().equals("CURRENT_PROJECT")||!fact.subject().equals(witness.get("role"))
            ||!fact.value().equals(person)||!DomainBindings.roleMatches(rule.subject(),fact.subject(),fact,evaluator.corpus)
            ||!decision.evidences().contains(source.evidence())||source.id().startsWith("V")
            ||!DocumentSubjectScope.appointment(source,fact))return false;
        String quote=str(witness,"sourceQuote");
        if(cn.bidcheck.compat.Java8Api.isBlank(quote)||!DocumentSubjectScope.localBlocks(source).contains(quote))return false;
        // Revalidate against the current corpus, including server layout and document boundaries.
        // Persisted IDs, cached proof strings and model-supplied scope metadata are insufficient.
        cn.bidcheck.verification.engine.ProofVerifier.Verified verified=new ProofVerifier().verify(JSON.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(fact))),evaluator.corpus.all,
            RuleEvaluator.maps(evaluator.context.get("_validatedLayoutCells")));
        if(!verified.rejected().isEmpty()||verified.facts().size()!=1)return false;
        Fact current=verified.facts().get(0);
        // Keep the witness first, but do not drop other current facts: they may carry a
        // conflicting strong identity. A saved local receipt cannot hide fresh counterevidence.
        List<Fact> currentFacts=new ArrayList<>();currentFacts.add(current);
        evaluator.facts.stream().filter(f->!f.id().equals(fact.id())&&!f.id().equals(current.id())).forEach(currentFacts::add);
        Decision fresh=PersonnelCount.evaluate(rule,new RuleEvaluator(currentFacts,evaluator.corpus,evaluator.context));
        if(!fresh.status().equals("PASS")||!fresh.gaps().isEmpty()||!proofShape(rule,fresh.proof()))return false;
        List<Map<String,Object>> refreshed=RuleEvaluator.maps(fresh.proof().get("sourceLocalExistenceWitness"));
        if(refreshed.size()!=1)return false;
        Map<String,Object> binding=refreshed.get(0);
        for(String key:cn.bidcheck.compat.Java8Api.listOf("sourceId", "role", "personName", "sourceQuote", "confirmation", "identityScope", "proves"))
            if(!Objects.equals(binding.get(key),witness.get(key)))return false;
        return !explicitCounterevidence(rule,person,evaluator.corpus);
    }

    private static boolean proofShape(Rule rule,Map<String,Object> proof){
        return rule.subject().equals(proof.get("requestedRole"))&&PersonnelRoles.canonical(rule.subject()).equals(proof.get("normalizedRole"))
            &&"ROLE_CONFIGURATION".equals(proof.get("comparison"))&&"ROLE_CONFIGURATION_ONLY".equals(proof.get("proofScope"))
            &&"SOURCE_LOCAL_CURRENT_APPOINTMENT_EXISTENCE".equals(proof.get("countBasis"))
            &&one(proof.get("requiredCount"))&&one(proof.get("provenExistenceLowerBound"))
            &&cn.bidcheck.compat.Java8Api.isBlank(str(proof,"employer"))&&cn.bidcheck.compat.Java8Api.isBlank(str(proof,"lot"))
            &&FALSE_CLAIMS.stream().allMatch(key->Boolean.FALSE.equals(proof.get(key)));
    }
    private static boolean one(Object value){BigDecimal number=decimal(value);return number!=null&&number.compareTo(BigDecimal.ONE)==0;}

    private static boolean explicitCounterevidence(Rule rule,String person,EvidenceCorpus corpus){
        String roles=PersonnelRoles.labels(rule.subject()).stream().map(Pattern::quote).reduce((a,b)->a+"|"+b).orElse("");
        Pattern role=Pattern.compile("(?:"+roles+")");
        // Do not silently resolve revocations/replacements without their own scope/time proof.
        Pattern negative=Pattern.compile("(不担任|未担任|不再担任|不得担任|未任命|不配备|未配备|不再配备|没有配备|不设置|未设置|无人担任|人员为空|岗位空缺)|(撤回|撤销|取消任命|取消任职|免职|解聘|拟更换|拟变更|更换为|变更为|替换为)");
        for(Source source:corpus.all){
            if(source.index()||source.scopeContext().blocked()||source.historical()&&!cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "CURRENT_BID_SECTION").contains(source.scopeContext().status()))continue;
            for(String block:DocumentSubjectScope.localBlocks(source)){
                String plain=block.replaceAll("(?i)<[^>]+>"," ");
                if(plain.matches("(?s).*(?:填写示例|示范文本|仅供参考|参考模板).*"))continue;
                // Merely being attached to the current bid does not turn an enterprise licence's
                // personnel change into a change of this project's appointment.
                boolean current=cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "CURRENT_BID_SECTION").contains(source.scopeContext().status())
                    ||plain.matches("(?s).*(?:本项目|本工程|本次投标|拟在本合同任职).*");
                if(!current)continue;
                for(String sentence:plain.split("[。；;\\n]")){
                    if(sentence.matches(".*(?:非本项目|非本次|历史项目|已完成项目).*"))continue;
                    boolean targetRole=role.matcher(sentence).find();
                    boolean currentPersonOnly=sentence.contains(person)&&sentence.matches(".*(?:本项目|本工程|本次投标).*" )
                        &&!Pattern.compile(RequirementContextBindings.ROLES).matcher(sentence).find();
                    if(!targetRole&&!currentPersonOnly)continue;
                    Matcher event=negative.matcher(sentence);
                    while(event.find()){
                        String before=sentence.substring(0,event.start()),after=sentence.substring(event.end()).split("[，,]",2)[0];
                        // A restriction on another project's appointment is not a withdrawal here.
                        if(after.matches(".*(?:其他项目|其它项目|其他工程|其它工程|另一项目|其他岗位|其它岗位|非本项目|非本次).*" )
                            &&!after.matches(".*(?:本项目|本工程|本次投标).*"))continue;
                        if(event.group(2)!=null&&!assertedChange(before))continue;
                        return true;
                    }
                }
            }
        }
        return false;
    }
    private static boolean assertedChange(String before){
        // Interpret the local event, not a keyword anywhere in a generic restriction. A later
        // "but already withdrawn" remains an event even when an earlier clause prohibits it.
        String local=before.replaceFirst("(?s)^.*(?:[，,]|但是|但|然而|却)","");
        if(local.matches("(?s).*(?:不得|禁止|严禁|不准|不允许|不能|不应|尚未|从未|并未|未曾|未|没有|无需|不必|不予|不再|不曾)[^，,。；;]{0,12}$"))return false;
        if(local.matches("(?s).*(?:如需|如果|一旦|若|假如)[^，,。；;]{0,30}$")&&!local.matches(".*(?:已经|现已|确已|已被|已予).*$"))return false;
        return true;
    }
}
