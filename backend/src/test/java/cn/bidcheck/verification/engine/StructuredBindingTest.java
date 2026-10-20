package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.facts.StructuralFixtures;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.facts.StructuralFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuredBindingTest {
    final ObjectMapper json=new ObjectMapper();
    Map<String,Object> run(StructuralFixtures.Fixture fixture,List<String> months){
        var cp=new Checkpoint("cp","人员社保","资格","HIGH","SEMANTIC_RESPONSE","","","合成人甲应具备连续3个月社保","",true,true,true,null);
        List<Page> pages=fixture.sources().stream().map(s->{Evidence e=(Evidence)s.get("evidence");return new Page(e.page(),e.quote(),e.locationType(),"OCR_FIXTURE","");}).toList();
        var doc=new Document("d","p","BID","合成.pdf",1,HASH,"",pages,List.of());
        var model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "structured-mock";}public JsonNode request(String op,Map<String,Object> input){
            if(op.equals("COMPILE"))return json.valueToTree(Map.of("logic","ALL","rules",List.of(linked("id","months","predicate","MONTHS","subject",NAME,"sourceQuote",cp.expected(),"parameters",Map.of("count",3)))));
            List<String> refs=RuleEvaluator.maps(input.get("sources")).stream().map(s->str(s,"id")).toList();
            return json.valueToTree(Map.of("facts",List.of(linked("kind","MONTHS","subject",NAME,"field","社保","value","社保","rawValue","缴费月份","scope","CURRENT_PROJECT","sourceIds",refs,"attributes",linked("person",NAME,"employer","合成公司","months",months,"cellIds",ids(fixture),"structureProof",Map.of("status","MODEL_FORGED"))))));
        }};
        return new VerificationEngine(model,null).evaluate(cp,List.of(doc),null,Map.of("layoutFacts",fixture.cells(),"allowModelRuleCompilation",true));
    }
    @Test void actualEngineUsesRepeatedIdentityAndBothPageSourcesToVerifyMonths(){var r=run(join(row(1,true,true,ID,"2025-01"),row(2,true,true,ID,"2025-02、2025-03")),List.of("2025-01","2025-02","2025-03"));assertEquals("PASS",r.get("businessDecision"),r.toString());String facts=json.valueToTree(r.get("facts")).toString();assertTrue(facts.contains("ADJACENT_PAGES_REPEATED_SUBJECT"));assertFalse(facts.contains("MODEL_FORGED"));assertTrue(facts.contains("\"location\":1"));assertTrue(facts.contains("\"location\":2"));}
    @Test void sameNameDifferentIdentifiersCannotAccumulateRequiredMonths(){var r=run(join(row(1,true,true,ID,"2025-01"),row(2,true,true,OTHER,"2025-02、2025-03")),List.of("2025-01","2025-02","2025-03"));assertEquals("UNDETERMINED",r.get("businessDecision"));assertTrue(json.valueToTree(r.get("rejectedFacts")).toString().contains("SUBJECT_IDENTITY_CONFLICT"));}
    @Test void ocrRowsWithoutIndependentIdentityDoNotCreateCrossPageProof(){var r=run(join(row(1,true,false,ID,"2025-01"),row(2,true,false,ID,"2025-02、2025-03")),List.of("2025-01","2025-02","2025-03"));assertEquals("UNDETERMINED",r.get("businessDecision"));assertTrue(json.valueToTree(r.get("rejectedFacts")).toString().contains("STRUCTURE_CONTINUATION_UNPROVEN"));}
    @Test void rangeEndpointsDoNotProveMissingMonth(){var r=run(row(1,false,false,ID,"2025-01至2025-03"),List.of("2025-01","2025-02","2025-03"));assertEquals("UNDETERMINED",r.get("businessDecision"));assertTrue(json.valueToTree(r.get("rejectedFacts")).toString().contains("STRUCTURE_MONTH_COVERAGE_UNPROVEN"));}
}
