package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A bounded business composition shares its leaf executors with standalone checks. */
final class CompoundBusinessRequirements {
    static final String VERSION="compound-business-requirements-1";
    private static final Pattern JOINT=Pattern.compile("^(本项目接受联合体投标。)(联合体投标时须提交联合体文件[（(]见第[一二三四五六七八九十0-9]+章中《联合体文件》[）)]。)(由施工单位作为联合体牵头人，且项目负责人应由牵头单位委派。)(联合体成员[（(]含牵头单位[）)]不得超过([0-9一二两三四五六七八九十]+)家。?)$");
    private CompoundBusinessRequirements(){}
    static Rule compile(Checkpoint cp,String source){
        Matcher parsed=JOINT.matcher(source);if(!parsed.matches()||RequirementGrounding.number(parsed.group(5))==null)return null;
        return new Rule("COMPOUND-"+identity(source).substring(0,16),"联合体适用条件、协议、牵头职责、委派与成员数量","MATERIAL","","consortiumObligations","","联合体文件","",
                linked("compoundBusinessVersion",VERSION,"compoundKind","CONSORTIUM"),source,"",cn.bidcheck.compat.Java8Api.listOf());
    }
    static boolean applies(Rule r){return VERSION.equals(r.parameters().get("compoundBusinessVersion"));}
    static Decision evaluate(Rule r,RuleEvaluator evaluator){
        List<Fact> facts=new ArrayList<>(cn.bidcheck.compat.Java8Api.toList(evaluator.facts.stream().filter(f->new ProofVerifier().issue(f,evaluator.corpus.byId)==null&&DomainBindings.issue(f,evaluator.corpus.byId,cn.bidcheck.compat.Java8Api.listOf())==null)));
        List<Rule> reads=cn.bidcheck.compat.Java8Api.listOf(read(r,"联合体成员"), read(r,"联合体牵头人"), read(r,"施工单位"), read(r,"牵头单位"), new Rule(r.id()+"-READ-PERSON","本次项目负责人","PERSON_ROLE","项目负责人","姓名","","","",cn.bidcheck.compat.Java8Api.mapOf(),r.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf()));
        for(Fact fact:evaluator.corpus.nativeFacts(reads,evaluator.corpus.all))if(new ProofVerifier().issue(fact,evaluator.corpus.byId)==null&&DomainBindings.issue(fact,evaluator.corpus.byId,cn.bidcheck.compat.Java8Api.listOf())==null&&facts.stream().noneMatch(f->f.id().equals(fact.id())))facts.add(fact);
        RuleEvaluator e=new RuleEvaluator(facts,evaluator.corpus,evaluator.context);
        Matcher match=JOINT.matcher(r.sourceQuote());if(!match.matches()||!"CONSORTIUM".equals(str(r.parameters(),"compoundKind"))||RequirementGrounding.number(match.group(5))==null)return e.unknown(r,"COMPOUND_REQUIREMENT_SOURCE_REQUIRED","复合要求不能重新对应原文",cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> source=r.parameters().get("requirementSourceContext") instanceof Map<?,?>?copy(((Map<?,?>) (r.parameters().get("requirementSourceContext")))):cn.bidcheck.compat.Java8Api.mapOf();
        Rule material=ConditionalMaterialEvidence.embedded(match.group(2),source);
        Decision submitted=material==null?e.unknown(r,"CONDITIONAL_MATERIAL_SOURCE_REQUIRED","联合体材料条件未定位",cn.bidcheck.compat.Java8Api.listOf()):e.evaluate(material);
        if("NOT_APPLICABLE".equals(submitted.status()))return new Decision(r.id(),r.description(),"NOT_APPLICABLE",submitted.reason(),submitted.evidences(),submitted.gaps(),linked("executor",VERSION,"applicability",submitted.map()));
        List<Decision> children=new ArrayList<>();children.add(submitted);
        // Membership is bound to the complete current agreement; historical projects never activate this path.
        List<Fact> members=DomainBindings.roleLinks("联合体成员",e.facts,e.corpus);List<String> names=DomainBindings.subjects("联合体成员",e.facts,e.corpus);
        List<Map<String,Object>> memberSet=DomainBindings.completeMemberSet("联合体成员",members,names,e.corpus);
        if(memberSet.isEmpty())
            children.add(e.unknown(r,"CONSORTIUM_APPLICABILITY_PROOF_REQUIRED","联合体组成尚未核完整，不能凭缺少协议认定不适用",members));
        else{
            Rule leader=leaf(r,"LEAD","联合体牵头关系","RELATION","联合体牵头人","牵头","施工单位",match.group(3),cn.bidcheck.compat.Java8Api.mapOf("object", "施工单位"));
            Rule appointment=leaf(r,"APPOINT","项目负责人由牵头单位委派","RELATION","项目负责人","委派","牵头单位",match.group(3),cn.bidcheck.compat.Java8Api.mapOf("object", "牵头单位"));
            Rule count=new Rule(r.id()+"-COUNT","联合体成员数量","ENTITY_COUNT","联合体成员","memberCount","<=",RequirementGrounding.number(match.group(5)).toPlainString(),"家",cn.bidcheck.compat.Java8Api.mapOf(),match.group(4),"",cn.bidcheck.compat.Java8Api.listOf());
            List<String> leads=DomainBindings.subjects("联合体牵头人",facts,e.corpus),builders=DomainBindings.subjects("施工单位",facts,e.corpus);
            List<Fact> leadSources=new ArrayList<>(DomainBindings.roleLinks("联合体牵头人",facts,e.corpus));leadSources.addAll(DomainBindings.roleLinks("施工单位",facts,e.corpus));
            List<Fact> leadLinks=DomainBindings.roleLinks("联合体牵头人",facts,e.corpus),builderLinks=DomainBindings.roleLinks("施工单位",facts,e.corpus);
            boolean sameAgreement=memberSet.stream().map(p->str(p,"sourceId")).anyMatch(id->leadLinks.stream().anyMatch(f->f.sourceIds().contains(id))&&builderLinks.stream().anyMatch(f->f.sourceIds().contains(id)));
            if(leads.size()!=1||builders.size()!=1||!sameAgreement)children.add(e.unknown(leader,"CONSORTIUM_WORK_ALLOCATION_REQUIRED","须由同一完整联合体协议对应牵头单位及施工分工，不能合并不同协议中的角色",leadSources));
            else if(leads.equals(builders))children.add(e.pass(leader,"同一完整协议中施工单位为牵头单位",leadSources,linked("leader",leads,"constructionMember",builders)));
            else children.add(e.fail(leader,"原文明确的牵头单位与施工单位不同",leadSources,linked("leader",leads,"constructionMember",builders)));
            children.add(e.evaluate(appointment));children.add(e.evaluate(count));
        }
        Decision combined=RuleEvaluator.combine(r.id(),r.description(),"ALL",children);Map<String,Object> proof=new LinkedHashMap<>(combined.proof());proof.put("executor",VERSION);proof.put("sharedExecutors",cn.bidcheck.compat.Java8Api.listOf(ConditionalMaterialEvidence.VERSION, "RELATION", "ENTITY_COUNT"));
        return new Decision(combined.id(),combined.description(),combined.status(),combined.reason(),combined.evidences(),combined.gaps(),proof);
    }
    private static Rule leaf(Rule r,String id,String label,String predicate,String subject,String field,String expected,String quote,Map<String,Object> p){return new Rule(r.id()+"-"+id,label,predicate,subject,field,"",expected,"",p,quote,"",cn.bidcheck.compat.Java8Api.listOf());}
    private static Rule read(Rule r,String subject){return new Rule(r.id()+"-READ-"+identity(subject).substring(0,6),subject,"RELATION",subject,"名称","","","",cn.bidcheck.compat.Java8Api.mapOf(),r.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());}
    private static Map<String,Object> copy(Map<?,?> m){Map<String,Object> result=new LinkedHashMap<>();m.forEach((k,v)->result.put(k.toString(),v));return result;}
}
