package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedSubjectIdentityTest {
    final ObjectMapper json=new ObjectMapper();
    // Synthetic identifiers, never user data. Only hashes of these identities may appear in proof metadata.
    final String first="110101199001010015",second="110101199202020025";
    Map<String,Object> evaluate(boolean social,String roleId,String proofId){
        String expected=social?"项目负责人应提供连续3个月社保":"项目负责人应提供一级注册证书";
        Checkpoint cp=new Checkpoint("identity","项目负责人证据核验","资格","HIGH","SEMANTIC_RESPONSE","","",expected,"",true,true,true,null);
        String appointment="本项目人员配置\n项目负责人：同名甲"+(cn.bidcheck.compat.Java8Api.isBlank(roleId)?"":" 身份证号码："+roleId);
        String supporting="姓名：同名甲"+(cn.bidcheck.compat.Java8Api.isBlank(proofId)?"":" 身份证号码："+proofId)+(social?" 社保 2025年1月 2025年2月 2025年3月":" 一级注册证书 证书编号：R001");
        ModelGateway model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "identity-test";}public JsonNode request(String op,Map<String,Object> input){
            if(op.equals("COMPILE"))return json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(linked("id","role","predicate","PERSON_ROLE","subject","项目负责人","field","role","sourceQuote",expected), linked("id","proof","predicate",social?"MONTHS":"CERTIFICATE","subject","项目负责人","sourceQuote",expected,"parameters",social?cn.bidcheck.compat.Java8Api.mapOf("count", 3):cn.bidcheck.compat.Java8Api.mapOf("category", "注册证书", "grade", "一级")))));
            List<Map<String,Object>> facts=new ArrayList<>();for(Map<String,Object> source:RuleEvaluator.maps(input.get("sources"))){String quote=str(source,"quote"),id=str(source,"id");if(quote.contains("项目负责人："))facts.add(linked("kind","PERSON_ROLE","subject","项目负责人","field","role","rawValue","同名甲","value","同名甲","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf(id),"attributes",cn.bidcheck.compat.Java8Api.mapOf("entityId", "MODEL_FORGED_SAME_PERSON", "identityStrength", "STRONG")));
                if(quote.contains("姓名："))facts.add(linked("kind",social?"MONTHS":"CERTIFICATE","subject","同名甲","field",social?"social":"certificate","rawValue",social?"社保":"注册证书","value",social?"社保":"注册证书","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf(id),"attributes",social?linked("person","同名甲","months",cn.bidcheck.compat.Java8Api.listOf("2025-01", "2025-02", "2025-03"),"entityId","MODEL_FORGED_SAME_PERSON","identityStrength","STRONG"):linked("category","注册证书","grade","一级","number","R001","numberField","证书编号","entityId","MODEL_FORGED_SAME_PERSON","identityStrength","STRONG")));
            }return json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", facts));
        }};
        // This fixture supplies a synthetic compiled rule tree so it can isolate the
        // source-derived identity join. Production rule authoring is model-disabled by
        // default, therefore the compatibility compiler must be enabled explicitly here.
        return new VerificationEngine(model,null).evaluate(cp,cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","p",1,appointment,supporting)),null,cn.bidcheck.compat.Java8Api.mapOf("allowModelRuleCompilation", true));
    }
    @Test void sameNameDifferentIdsCannotAttachCertificateToCurrentAppointment(){Map<String,Object> result=evaluate(false,first,second);assertEquals("UNDETERMINED",result.get("businessDecision"));assertTrue(RuleEvaluator.maps(result.get("gaps")).stream().anyMatch(g->str(g,"code").equals("SUBJECT_IDENTITY_CONFLICT")));}
    @Test void sameNameDifferentIdsCannotAttachSocialMonthsToCurrentAppointment(){Map<String,Object> result=evaluate(true,first,second);assertEquals("UNDETERMINED",result.get("businessDecision"));assertTrue(RuleEvaluator.maps(result.get("gaps")).stream().anyMatch(g->str(g,"code").equals("SUBJECT_IDENTITY_CONFLICT")));}
    @Test void identicalNamesAndModelClaimedStrongIdentityDoNotProveCrossSourceJoin(){Map<String,Object> result=evaluate(false,"","");assertEquals("UNDETERMINED",result.get("businessDecision"));assertTrue(RuleEvaluator.maps(result.get("gaps")).stream().anyMatch(g->str(g,"code").equals("ENTITY_IDENTITY_AMBIGUOUS")));}
    @Test void independentlySourcedSameIdAllowsIdentityJoin(){Map<String,Object> result=evaluate(false,first,first);assertEquals("PASS",result.get("businessDecision"));List<Map<String,Object>> obligations=RuleEvaluator.maps(result.get("obligations"));String proof=json.valueToTree(cn.bidcheck.compat.Java8Api.toList(obligations.stream().map(o->o.get("proof")))).toString();assertTrue(proof.contains("identityBindings"));assertTrue(proof.contains("valueFingerprint"));for(Map<String,Object> obligation:obligations){JsonNode node=json.valueToTree(obligation);Set<JsonNode> originalEvidence=new HashSet<>();node.path("evidences").forEach(originalEvidence::add);assertIdentifiersOnlyInOriginalEvidence(node.path("proof"),"proof",originalEvidence);}}
    private void assertIdentifiersOnlyInOriginalEvidence(JsonNode node,String path,Set<JsonNode> originals){
        if(node.isTextual()&&node.asText().contains(first))assertTrue(path.matches(".*\\.evidences\\[\\d+\\]\\.quote"),"full identifier outside explicit original quote: "+path);
        if(node.isObject())node.fields().forEachRemaining(entry->{if(entry.getKey().equals("evidences")){assertTrue(entry.getValue().isArray());for(JsonNode nested:entry.getValue())assertTrue(originals.contains(nested),"nested evidence must exactly equal preserved top-level original evidence");}assertIdentifiersOnlyInOriginalEvidence(entry.getValue(),path+"."+entry.getKey(),originals);});
        else if(node.isArray())for(int i=0;i<node.size();i++)assertIdentifiersOnlyInOriginalEvidence(node.get(i),path+"["+i+"]",originals);
    }
}
