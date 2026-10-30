package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Adapts an agent-declared primitive to parameters compiled from one verified source
 * span. The agent cannot provide parameters, proof or execution authority. */
final class AgentReviewPrimitives {
    static final String VERSION="agent-source-primitive-binding-1";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> KEYS=Set.of("obligationId","description","requirementQuote","method","predicate","subject","field","operator","expected","unit","expectedEvidence");
    private static final Set<String> SOURCE_KINDS=Set.of("OBLIGATION","SCOPED_OBLIGATION","SCOPED_PROOF","SCOPED_BOOK_CONDITION","SCOPED_SCORING_CONTINUATION","REFERENCE_CLAUSE","EXECUTABLE_REFERENCE");
    private AgentReviewPrimitives(){}

    static Rule bind(Map<String,Object> condition,Checkpoint cp,RequirementSourceContext.Context source,String fullRequirement,Map<String,Object> ctx){
        if(condition==null||cp==null||source==null||source.identity().isEmpty()||!KEYS.containsAll(condition.keySet()))return rejected(condition,"AGENT_PRIMITIVE_INPUT_INVALID");
        for(String key:List.of("obligationId","description","requirementQuote","method","predicate","subject","field","operator","expected","unit"))
            if(condition.containsKey(key)&&!(condition.get(key) instanceof String))return rejected(condition,"AGENT_PRIMITIVE_INPUT_INVALID");
        String quote=str(condition,"requirementQuote");
        if(!"DETERMINISTIC".equals(str(condition,"method"))||str(condition,"obligationId").isBlank()||quote.isBlank()||fullRequirement==null||!fullRequirement.contains(quote))return rejected(condition,"AGENT_PRIMITIVE_SOURCE_INVALID");
        RequirementSourceContext.Context scoped=scoped(source,quote);
        if(scoped==null)return rejected(condition,"AGENT_PRIMITIVE_SOURCE_AMBIGUOUS");
        RequirementCompiler validator=new RequirementCompiler();
        Rule simple=validator.agentCondition(JSON.valueToTree(primitive(condition,Map.of(),quote)),source,fullRequirement);
        if(executable(simple))return simple;

        // The compiler sees only this condition's original span. Original heading
        // context is retained for identity binding, not added as more obligations.
        Checkpoint selected=checkpoint(cp,condition,scoped);
        var compiled=new DeterministicRequirementCompiler(JSON).compile(selected,quote,scoped,ctx==null?Map.of():ctx);
        List<Rule> leaves=new ArrayList<>();for(Rule root:compiled.rules())eligibleLeaves(root,leaves);
        List<Rule> matching=new ArrayList<>();
        for(Rule candidate:leaves){
            if(!executable(candidate)||!str(condition,"predicate").equals(candidate.predicate())||candidate.sourceQuote().isBlank()||!quote.contains(candidate.sourceQuote()))continue;
            // The native mapper uses 上述人员 as a fallback for social clauses. It
            // may be replaced only by the normal server heading binder, never by an agent's name.
            if("上述人员".equals(candidate.subject())&&!candidate.sourceQuote().contains("上述人员"))
                candidate=new Rule(candidate.id(),candidate.description(),candidate.predicate(),"",candidate.field(),candidate.operator(),candidate.expected(),candidate.unit(),candidate.parameters(),candidate.sourceQuote(),candidate.logic(),candidate.children());
            Rule checked=validator.agentCondition(JSON.valueToTree(candidate),source,fullRequirement);
            if(!executable(checked)||!matches(condition,checked))continue;
            // Preserve native parameter values and source span. Replacing IDs or a
            // human-readable description does not grant a model parameter authority.
            Map<String,Object> row=new LinkedHashMap<>(JSON.convertValue(checked,Map.class));
            row.put("id",str(condition,"obligationId"));row.put("description",str(condition,"description"));
            Rule rebound=validator.agentCondition(JSON.valueToTree(row),source,fullRequirement);
            if(executable(rebound)&&matches(condition,rebound))matching.add(rebound);
        }
        if(matching.size()!=1)return rejected(condition,matching.isEmpty()?"AGENT_PRIMITIVE_DECLARATION_MISMATCH":"AGENT_PRIMITIVE_CANDIDATE_AMBIGUOUS");
        return matching.get(0);
    }

    private static boolean matches(Map<String,Object> condition,Rule candidate){
        String declaredSubject=str(condition,"subject"),declaredField=str(condition,"field"),declaredExpected=str(condition,"expected"),declaredOperator=str(condition,"operator"),declaredUnit=str(condition,"unit");
        if(!declaredSubject.isBlank()&&!PersonnelRoles.canonical(declaredSubject).equals(PersonnelRoles.canonical(candidate.subject())))return false;
        if(!declaredField.isBlank()&&!field(candidate.predicate(),declaredField).equals(field(candidate.predicate(),candidate.field())))return false;
        if(!declaredUnit.isBlank()&&!RequirementGrounding.unitEquals(declaredUnit,candidate.unit()))return false;
        String expected=candidate.expected(),operator=candidate.operator();
        if("MONTHS".equals(candidate.predicate())){expected=str(candidate.parameters(),"count");operator=">=";}
        if(!declaredExpected.isBlank()&&!sameValue(declaredExpected,expected))return false;
        if(!declaredOperator.isBlank()&&!Objects.equals(canonicalOperator(declaredOperator),canonicalOperator(operator)))return false;
        return true;
    }
    private static String field(String predicate,String value){String text=clean(value);
        if("MONTHS".equals(predicate)&&Set.of("socialInsurance","社保","社保证明","社保缴费","连续缴费月份","连续社保月份").contains(text))return "socialInsurance";
        if(Set.of("PERSON_COUNT","ENTITY_COUNT").contains(predicate)&&Set.of("personnelCount","memberCount","人数","人员数量","成员数量").contains(text))return "count";
        if("CERTIFICATE".equals(predicate)&&Set.of("certificate","证书","证照").contains(text))return "certificate";
        return text;
    }
    private static boolean sameValue(String declared,String original){BigDecimal left=RequirementGrounding.number(declared),right=RequirementGrounding.number(original);return left!=null&&right!=null?left.compareTo(right)==0:clean(declared).equals(clean(original));}
    private static String canonicalOperator(String operator){String normalized=RequirementGrounding.canonicalOp(operator);return "?".equals(normalized)?operator:normalized;}
    private static void eligibleLeaves(Rule rule,List<Rule> out){
        if(rule.children().isEmpty()){out.add(rule);return;}
        // A disjunction, applicability branch or population requirement cannot be
        // silently converted into an unconditional single leaf.
        if(!"ALL".equals(rule.logic()))return;
        rule.children().forEach(child->eligibleLeaves(child,out));
    }
    private static boolean executable(Rule rule){return rule!=null&&!"UNSUPPORTED".equals(rule.predicate())&&rule.children().isEmpty()&&rule.logic().isBlank();}
    private static Map<String,Object> primitive(Map<String,Object> c,Map<String,Object> parameters,String quote){return linked("id",str(c,"obligationId"),"description",str(c,"description"),"predicate",str(c,"predicate"),"subject",str(c,"subject"),"field",str(c,"field"),"operator",str(c,"operator"),"expected",str(c,"expected"),"unit",str(c,"unit"),"parameters",parameters,"sourceQuote",quote);}

    private static RequirementSourceContext.Context scoped(RequirementSourceContext.Context source,String quote){
        Map<String,Map<String,Object>> spans=new LinkedHashMap<>();
        for(Map<String,Object> row:source.sources()){
            if(!SOURCE_KINDS.contains(str(row,"kind")))continue;
            int offset=RequirementContextBindings.uniqueOffset(str(row,"sourceQuote"),quote);if(offset<0)continue;
            int begin=RunSession.number(row.get("start"),-1);if(begin<0)continue;
            Map<String,Object> span=new LinkedHashMap<>(row);span.put("kind","OBLIGATION");span.put("sourceQuote",quote);span.put("start",begin+offset);span.put("end",begin+offset+quote.length());span.put("sourceHash",identity(quote));
            String key=str(span,"documentId")+"|"+str(span,"documentHash")+"|"+span.get("documentVersion")+"|"+str(span,"locationType")+"|"+span.get("location")+"|"+span.get("start")+"|"+span.get("end");spans.putIfAbsent(key,span);
        }
        if(spans.size()!=1)return null;Map<String,Object> span=spans.values().iterator().next();
        Map<String,Object> identity=new LinkedHashMap<>(source.identity());for(String key:List.of("documentId","documentHash","documentVersion","locationType","location","start","end","sourceHash"))identity.put(key,span.get(key));
        List<Map<String,Object>> rows=new ArrayList<>();rows.add(span);
        for(Map<String,Object> row:source.sources())if(Set.of("SECTION_SUBJECT","TABLE_ROW_SUBJECT","TABLE_HEADER").contains(str(row,"kind")))rows.add(row);
        return new RequirementSourceContext.Context(source.bindingSource(),Map.copyOf(identity),List.copyOf(rows));
    }
    private static Checkpoint checkpoint(Checkpoint original,Map<String,Object> condition,RequirementSourceContext.Context scoped){
        String quote=str(condition,"requirementQuote");Map<String,Object> identity=scoped.identity();Evidence evidence=new Evidence(str(identity,"documentId"),original.source()==null?"":original.source().documentName(),RunSession.number(identity.get("location"),0),str(identity,"locationType"),quote,original.source()==null?"":original.source().anchor(),0);
        return new Checkpoint(original.id(),str(condition,"description"),original.category(),original.severity(),original.type(),str(condition,"field"),str(condition,"operator"),quote,str(condition,"unit"),original.required(),original.confirmed(),original.enabled(),evidence);
    }
    private static Rule rejected(Map<String,Object> condition,String issue){Map<String,Object> c=condition==null?Map.of():condition;return new Rule(str(c,"obligationId"),str(c,"description"),"UNSUPPORTED",str(c,"subject"),str(c,"field"),str(c,"operator"),str(c,"expected"),str(c,"unit"),Map.of("compilationIssue",issue,"primitiveBindingVersion",VERSION),str(c,"requirementQuote"),"",List.of());}
}
