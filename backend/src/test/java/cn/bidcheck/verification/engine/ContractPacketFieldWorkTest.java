package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.ContractRecordAnchorTest.*;
import static cn.bidcheck.verification.engine.ContractRecordSubmissionTest.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class ContractPacketFieldWorkTest {
    @Test void everyAcceptedFieldCanFinishItsPacketWithoutAnInventedRejection() throws Exception {
        Fixture f=new Fixture(identityBody(),"乙方："+COMPANY+"。合同金额：200万元。");
        f.read(3);
        Source body=source(f.corpus,5);
        Map<String,Object> party=linked("field","party","rawValue",COMPANY,"sourceIds",Collections.singletonList(body.id()),
            "attributes",linked("party",COMPANY));
        Map<String,Object> result=f.host.call("submit_candidate_fact",linked("targetObligationId",f.target,"submissionMode","CONTRACT_FIELDS",
            "fact",identity(f),"contractFields",Arrays.asList(amount(body),party)));
        assertTrue(RuleEvaluator.maps(result.get("rejections")).isEmpty(),result::toString);
        Map<String,Object> target=work(f,stop("INVESTIGATION_CALL_LIMIT"));
        Map<String,Object> plan=(Map<String,Object>)target.get("contractFieldWorkPlan");
        assertEquals(0,plan.get("rejectedFieldCount"));
        assertEquals("NO_EXECUTABLE_CORRECTION",plan.get("status"),plan::toString);
        assertEquals(true,target.get("consumed"));
        assertEquals(false,plan.get("businessProof"));
        assertTrue(RuleEvaluator.maps(plan.get("tasks")).stream().allMatch(t->"ACCEPTED_OR_KNOWN".equals(t.get("status"))));
    }

    private Map<String,Object> work(Fixture f,Map<String,Object> audit) {
        return RuleEvaluator.maps(f.host.serverTargetInputConsumption(audit).get("targets")).stream()
            .filter(row->f.target.equals(str(row,"targetObligationId"))).findFirst().get();
    }
    private Map<String,Object> stop(String code) {
        return linked("status","EVIDENCE_UPDATED","code",code,"failedToolCount",0);
    }

    private Map<String,Object> amountAndParty(Fixture f) {
        Source body=source(f.corpus,5);
        Map<String,Object> party=linked("field","party","rawValue",COMPANY,"sourceIds",Collections.singletonList(body.id()),
            "attributes",linked("party",COMPANY));
        return f.host.call("submit_candidate_fact",linked("targetObligationId",f.target,"submissionMode","CONTRACT_FIELDS",
            "fact",identity(f),"contractFields",Arrays.asList(amount(source(f.corpus,4)),party)));
    }
    @Test void sameBoundedCoverCannotBeRereadToSupplyItsOwnAmount() throws Exception {
        Fixture f=new Fixture(identityBody()+"\n合同金额：200万元","乙方："+COMPANY+"。未载明金额。");
        f.read(3);
        Map<String,Object> rejected=amountAndParty(f);
        assertEquals("REJECTED",rejected.get("status"));
        Map<String,Object> work=work(f,stop("INVESTIGATION_CALL_LIMIT"));
        Map<String,Object> plan=(Map<String,Object>)work.get("contractFieldWorkPlan");
        assertEquals("NO_EXECUTABLE_CORRECTION",plan.get("status"),plan::toString);
        assertEquals("NO_GROUNDED_CORRECTION",RuleEvaluator.maps(plan.get("tasks")).get(0).get("status"));
        assertEquals("/contractFields/0/rawValue",RuleEvaluator.maps(plan.get("tasks")).get(0).get("fieldPath"));
        assertEquals(true,work.get("consumed")); assertEquals(false,plan.get("businessProof"));
        Map<String,Object> failed=stop("INVESTIGATION_CALL_LIMIT");failed.put("failedToolCount",1);
        assertEquals(false,work(f,failed).get("consumed"));
    }
    @Test void exactCorrectableSourceProducesOneVerifiedActionAndUsesTheNormalSubmitPath() throws Exception {
        Fixture f=new Fixture(identityBody()+"\n合同金额：200万元","乙方："+COMPANY+"。合同金额：200万元。");
        f.read(3);
        Map<String,Object> rejected=amountAndParty(f);
        Map<String,Object> plan=(Map<String,Object>)rejected.get("contractFieldWorkPlan");
        assertEquals("ACTION_REQUIRED",plan.get("status"),plan::toString);
        Map<String,Object> action=RuleEvaluator.maps(plan.get("remainingActions")).get(0);
        assertEquals("/contractFields/0/rawValue",action.get("fieldPath"));
        assertEquals(Collections.singletonList(source(f.corpus,5).id()),action.get("sourceIds"));
        assertFalse(f.host.facts.values().stream().anyMatch(fact->fact.field().equals("amount")));
        assertEquals(false,work(f,stop("REPEATED_ACTION_WITHOUT_NEW_EVIDENCE")).get("consumed"));
        List<Map<String,Object>> applied=f.host.applyContractFieldCorrections(stop("REPEATED_ACTION_WITHOUT_NEW_EVIDENCE"));
        assertEquals(1,applied.size());assertEquals("COMPLETED",applied.get(0).get("status"));
        Fact accepted=f.host.facts.values().stream().filter(fact->fact.field().equals("amount")).findFirst().get();
        assertTrue(accepted.sourceIds().contains(source(f.corpus,5).id()));
        assertFalse(f.host.rejected.isEmpty());
        assertTrue(f.host.applyContractFieldCorrections(stop("REPEATED_ACTION_WITHOUT_NEW_EVIDENCE")).isEmpty());
        assertEquals(true,work(f,stop("REPEATED_ACTION_WITHOUT_NEW_EVIDENCE")).get("consumed"));
    }
    @Test void identityOnlyAndSinglePeripheralFieldDoNotExhaustOtherObligations() throws Exception {
        Fixture f=new Fixture(identityBody(),"合同金额：300万元");
        f.read(3);
        Map<String,Object> title=linked("field","contractNumber","rawValue",NUMBER,"sourceIds",Collections.singletonList(source(f.corpus,4).id()),
            "attributes",linked("contractNumber",NUMBER));
        ContractRecordAnchorTest.submit(f,title);
        Map<String,Object> work=work(f,stop("INVESTIGATION_CALL_LIMIT"));
        assertEquals(false,work.get("consumed"));
        assertEquals(0,((Map<?,?>)work.get("contractFieldWorkPlan")).get("rejectedFieldCount"));
        Map<String,Object> unsupported=linked("field","party","rawValue",COMPANY,"sourceIds",Collections.singletonList(source(f.corpus,4).id()),
            "attributes",linked("party",COMPANY));
        ContractRecordAnchorTest.submit(f,unsupported);
        work=work(f,stop("INVESTIGATION_CALL_LIMIT"));
        assertEquals(false,((Map<?,?>)work.get("contractFieldWorkPlan")).get("targetWorkComplete"));
        assertEquals(false,work.get("consumed"));
    }
    @Test void differentPriceWithSameBasisNeedsAnExplicitSourceReview() throws Exception {
        Fixture f=new Fixture(identityBody()+"\n合同金额：200万元","乙方："+COMPANY+"。合同金额：300万元。设计费：200万元。");
        f.read(3);
        Map<String,Object> result=amountAndParty(f);
        Map<String,Object> plan=(Map<String,Object>)result.get("contractFieldWorkPlan");
        Map<String,Object> alternative=RuleEvaluator.maps(plan.get("remainingActions")).stream()
            .filter(a->"REVIEW_ALTERNATIVE_FIELD_VALUE".equals(a.get("action"))).findFirst().get();
        assertEquals("300 万元",alternative.get("alternativeValue"));
        assertEquals(Collections.singletonList(source(f.corpus,5).id()),alternative.get("sourceIds"));
        assertEquals(false,plan.get("targetWorkComplete"));
        assertTrue(f.host.applyContractFieldCorrections(stop("REPEATED_ACTION_WITHOUT_NEW_EVIDENCE")).isEmpty());
        assertFalse(f.host.facts.values().stream().anyMatch(fact->fact.field().equals("amount")));
        assertEquals(false,work(f,stop("INVESTIGATION_CALL_LIMIT")).get("consumed"));
        Map<String,Object> packet=linked("authority","CURRENT_SERVER_PROOF","requestId",plan.get("requestId"),
            "serverPlanRevision",plan.get("serverPlanRevision"),"targets",Collections.singletonList(linked(
                "targetObligationId",f.target,"contractFieldWorkPlan",plan)));
        Map<String,Object> previous=linked("agentInvestigation",linked("requestId",plan.get("requestId"),
            "serverTargetInputConsumption",packet));
        List<Map<String,Object>> hints=ContractPacketFieldWork.previousReviewSources(previous,str(plan,"serverPlanRevision"),
            Collections.singletonMap(f.target,str(plan,"evidenceRevision")),f.corpus);
        assertTrue(hints.stream().anyMatch(h->"REVIEW_ALTERNATIVE_FIELD_VALUE".equals(h.get("action"))
            && Boolean.TRUE.equals(h.get("requiresOriginalRead")) && !h.containsKey("toolArguments")));
        assertTrue(ContractPacketFieldWork.previousReviewSources(previous,str(plan,"serverPlanRevision"),
            Collections.singletonMap(f.target,"changed"),f.corpus).isEmpty());
    }
    @Test void unreadBodyAndUninterpretedShapeCannotCloseThePacket() throws Exception {
        Fixture f=new Fixture(identityBody(),"合同金额：200万元",false);
        f.read(4);
        Map<String,Object> malformed=linked("field","amount","rawValue","200",
            "sourceIds",Collections.singletonList(source(f.corpus,4).id()),"attributes",linked("amount","200"));
        ContractRecordAnchorTest.submit(f,malformed);
        assertEquals(false,work(f,stop("REPEATED_ACTION_WITHOUT_NEW_EVIDENCE")).get("consumed"));
        f.read(3);f.read(5);ContractRecordAnchorTest.submit(f,malformed);
        Map<String,Object> plan=(Map<String,Object>)work(f,stop("REPEATED_ACTION_WITHOUT_NEW_EVIDENCE")).get("contractFieldWorkPlan");
        assertEquals("INCOMPLETE",plan.get("status"));
    }
    @Test void originalSigningMonthIsNotAnExecutableDateButASeparateFullSigningDateCanBeReviewed() throws Exception {
        for(boolean printedDay:Arrays.asList(false,true)) {
            Fixture f=new Fixture(identityBody()+"\n签订日期:2022年11月","乙方："+COMPANY+"。合同金额：200万元。"
                +(printedDay?"\n合同签订日期：2022年11月23日":"\n开工日期：2022年11月23日"));
            f.read(3);
            amountAndParty(f);
            Map<String,Object> partial=linked("field","signedDate","rawValue","2022年11月","attributes",linked("signedDate","2022年11月"),
                "sourceIds",Collections.singletonList(source(f.corpus,4).id()));
            Map<String,Object> result=ContractRecordAnchorTest.submit(f,partial);
            assertEquals("REJECTED",result.get("status"));
            Map<String,Object> plan=(Map<String,Object>)result.get("contractFieldWorkPlan");
            Map<String,Object> date=RuleEvaluator.maps(plan.get("tasks")).stream().filter(a->"signedDate".equals(a.get("field"))).findFirst().get();
            assertEquals(printedDay?"ACTION_REQUIRED":"NO_GROUNDED_CORRECTION",date.get("status"));
            assertFalse(f.host.facts.values().stream().anyMatch(v->v.field().equals("signedDate")));
            assertEquals(printedDay,RuleEvaluator.maps(plan.get("remainingActions")).stream().anyMatch(a->
                "CHECK_COMPLETE_SIGNING_DATE".equals(a.get("action")) && Objects.equals(a.get("sourceIds"),Collections.singletonList(source(f.corpus,5).id()))));
        }
    }
    @Test void formalTitleRequiresSameNumberOnIdentityPageAndAnUnambiguousPacket() {
        String rowTitle="军博图书馆装修装饰设计施工一体化工程";
        EvidenceCorpus corpus=corpus("申报清单","合同编号：SYN-22\n"+rowTitle+"合同","发包人：合成测试单位");
        Map<String,Object> row=linked("documentId","synthetic-bid","documentHash","synthetic-hash","documentVersion",1,
            "contractName",rowTitle,"proofPageStart",2,"proofPageEnd",3,"locationType","PAGE");
        Source cover=source(corpus,2); Set<String> reads=new HashSet<>(corpus.byId.keySet());
        Fact fact=new Fact("","CONTRACT","","contractNumber","SYN-22","SYN-22","","","HISTORICAL_PROJECT",
            Collections.singletonList(cover.id()),linked("contractNumber","SYN-22","contractName",rowTitle));
        assertTrue(new ContractRecordBindings(row,corpus.byId,reads).matches(fact,Collections.singletonList(cover)));
        EvidenceCorpus two=corpus("申报清单","合同编号：SYN-22\n"+rowTitle+"合同","另一独立施工合同名称测试合同\n合同编号：SYN-23");
        assertFalse(new ContractRecordBindings(row,two.byId,two.byId.keySet()).matches(fact,Collections.singletonList(source(two,2))));
    }
}
