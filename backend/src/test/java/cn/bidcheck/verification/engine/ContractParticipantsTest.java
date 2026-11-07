package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.ContractRecordSubmissionTest.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class ContractParticipantsTest {
    private static Map<String,Object> atom(String field,String value,Source source) {
        return linked("field",field,"rawValue",value,"sourceIds",Collections.singletonList(source.id()),
            "attributes",linked(field,value));
    }
    @Test void bidderPrintedOnlyAsClientCannotReceiveContractorCredit() throws Exception {
        String other="合成工程设计有限公司";
        Fixture f=new Fixture("合同名称："+NAME+"\n合同编号："+NUMBER,
            "发包人（全称）："+COMPANY+"\n设计人（全称）：___ "+other);
        Source body=source(f.corpus,5);
        for(String name:Arrays.asList(COMPANY,other)) {
            Map<String,Object> receipt=ContractRecordAnchorTest.submit(f,atom("party",name,body));
            assertTrue(RuleEvaluator.maps(receipt.get("rejections")).isEmpty(),receipt::toString);
        }
        Decision d=new RuleEvaluator(new ArrayList<>(f.host.facts.values()),f.corpus,f.host.ctx).evaluate(f.parent);
        Map<String,Object> check=RuleEvaluator.maps(d.proof().get("contractChecks")).stream()
            .filter(x->!RuleEvaluator.maps(x.get("partySideEvidence")).isEmpty()).findFirst().get();
        assertTrue(ProofVerifier.strings(check.get("excluded")).contains("非本次施工单位业绩"),check::toString);
        assertFalse(ProofVerifier.strings(check.get("missing")).contains("各签约单位与同一合同承包方的原文关系"));
        assertFalse(Boolean.TRUE.equals(check.get("counted")));
    }
    @Test void twoVerifiedAtomsRequireSameRecordSameBodyAndDirectNamedRole() throws Exception {
        for(String printed:Arrays.asList("承包人设计负责人：\nX\n| 姓名:邵彬。","设计人项目负责人：邵彬。")) {
            Fixture f=new Fixture(identityBody(),printed);
            Source body=source(f.corpus,5);String role=printed.substring(0,printed.indexOf('：'));
            for(Map<String,Object> field:Arrays.asList(atom("person","邵彬",body),atom("personRole",role,body))) {
                Map<String,Object> receipt=ContractRecordAnchorTest.submit(f,field);
                assertTrue(RuleEvaluator.maps(receipt.get("rejections")).isEmpty(),receipt::toString);
            }
            List<Fact> facts=new ArrayList<>(f.host.facts.values());
            ContractParticipants p=new ContractParticipants(Collections.emptySet(),f.corpus.all);
            p.linkAppointment("设计负责人","邵彬",facts,f.corpus.all);
            assertEquals(1,p.appointments.size(),p.appointments::toString);
            assertFalse(Boolean.TRUE.equals(p.appointments.get(0).get("currentPersonIdentityVerified")));
            assertEquals(body.id(),p.appointments.get(0).get("sourceId"));
            ContractParticipants wrong=new ContractParticipants(Collections.emptySet(),f.corpus.all);
            wrong.linkAppointment("施工负责人","邵彬",facts,f.corpus.all);
            assertTrue(wrong.appointments.isEmpty());
            List<Fact> foreign=new ArrayList<>();
            for(Fact fact:facts) {
                if(!"personRole".equals(fact.field())) {foreign.add(fact);continue;}
                Map<String,Object> attrs=new LinkedHashMap<>(fact.attributes());attrs.put("contractRecordId","another-record");
                foreign.add(new Fact(fact.id(),fact.kind(),fact.subject(),fact.field(),fact.rawValue(),fact.value(),
                    fact.unit(),fact.basis(),fact.scope(),fact.sourceIds(),attrs));
            }
            wrong.linkAppointment("设计负责人","邵彬",foreign,f.corpus.all);
            assertTrue(wrong.appointments.isEmpty(),"same name on another contract cannot close this role");
        }
    }
    @Test void sharedAnchorAloneCannotJoinNameFromAnotherRow() {
        EvidenceCorpus c=corpus("共同编号封面","设计人项目负责人：邵彬。","姓名：邵彬。");
        Source cover=source(c,1),role=source(c,2),name=source(c,3);
        Map<String,Object> identity=linked("contractRecordId","one-record","recordAnchorSourceIds",Collections.singletonList(cover.id()));
        List<Fact> facts=new ArrayList<>();
        for(String field:Arrays.asList("person","personRole")) {
            boolean person=field.equals("person");String value=person?"邵彬":"设计人项目负责人";
            Map<String,Object> attrs=new LinkedHashMap<>(identity);attrs.put(field,value);
            facts.add(new Fact("fact-"+field,"CONTRACT","",field,value,value,"","","HISTORICAL_PROJECT",
                Arrays.asList(cover.id(),person?name.id():role.id()),attrs));
        }
        ContractParticipants p=new ContractParticipants(Collections.emptySet(),c.all);
        p.linkAppointment("设计负责人","邵彬",facts,c.all);
        assertTrue(p.appointments.isEmpty());
    }
}
