package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.ContractRecordSubmissionTest.*;
import static cn.bidcheck.verification.engine.ContractRecordAnchorTest.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractExplicitFieldSourceTest {
    private static Map<String,Object> field(String key,String value,Source page){
        return linked("field",key,"rawValue",value,"sourceIds",Collections.singletonList(page.id()),"attributes",linked(key,value));
    }
    private static Fact attributes(Fact f,Map<String,Object> attrs){
        return new Fact(f.id(),f.kind(),f.subject(),f.field(),f.rawValue(),f.value(),f.unit(),f.basis(),f.scope(),f.sourceIds(),attrs);
    }
    @Test void explicitlyCitedIdentityPageAlsoProvesItsOwnPrintedPartyAmountAndSigningDay()throws Exception {
        Fixture f=new Fixture("第一部分 合同协议书\n合同名称："+NAME+"\n合同编号："+NUMBER+"\n甲方（全称）：合成业主有限公司\n设计人（全称）：___ "+COMPANY
            +"\n合同金额：200万元\n合同签订日期：2023年1月1日","第2页施工安排");
        Source original=source(f.corpus,4);
        Map<String,Object> receipt=f.host.call("submit_candidate_fact",linked("targetObligationId",f.target,"submissionMode","CONTRACT_FIELDS",
            "fact",identity(f),"contractFields",Arrays.asList(field("party","合成业主有限公司",original),field("party",COMPANY,original),
                amount(original),field("signedDate","2023年1月1日",original))));
        assertTrue(RuleEvaluator.maps(receipt.get("rejections")).isEmpty(),receipt::toString);
        Set<String> accepted=new HashSet<>();ProofVerifier verifier=ContractRecordBindings.verifier(f.corpus,
            SourcePerformanceScore.evidenceRules(f.parent),f.corpus.all);
        for(Fact fact:f.host.facts.values())if(Arrays.asList("party","amount","signedDate").contains(fact.field())){
            assertNull(verifier.issue(fact,f.corpus.byId));accepted.add(fact.id());
            assertEquals(Collections.singletonList(original.id()),fact.attributes().get(ContractEvidenceRecord.FIELD_SOURCES));
        }
        assertEquals(4,accepted.size());
        Decision evaluated=new RuleEvaluator(new ArrayList<>(f.host.facts.values()),f.corpus,f.host.ctx).evaluate(f.parent);
        Map<String,Object> row=RuleEvaluator.maps(evaluated.proof().get("contractChecks")).stream()
            .filter(r->ProofVerifier.strings(r.get("factIds")).containsAll(accepted)).findFirst().get();
        assertEquals("CONFIRMED",((Map<?,?>)row.get("signingDateEvidence")).get("state"));
        assertEquals(false,row.get("counted"));
    }
    @Test void appendedIdentityContextCannotSupplyValueMissingOnTheExplicitPage()throws Exception {
        Fixture f=new Fixture(identityBody()+"\n合同金额：200万元\n合同签订日期：2023年1月1日","本页未记载金额或日期");
        Source cited=source(f.corpus,5);
        assertTrue(submit(f,amount(cited)).toString().contains("RAW_VALUE_UNGROUNDED"));
        assertFalse(RuleEvaluator.maps(submit(f,field("signedDate","2023年1月1日",cited)).get("rejections")).isEmpty());
        assertFalse(f.host.facts.values().stream().anyMatch(v->"signedDate".equals(v.field())||"amount".equals(v.field())));
    }
    @Test void lineWrappedContractPriceRemainsBoundToItsOwnAmountAndUnit()throws Exception {
        String words="人民币（大写）壹佰玖拾肆万陆仟叁佰柒拾伍元整（¥1946375.00元）。其中：不含税价为（¥1836202.83元）；";
        for(String next:Arrays.asList(words,"建安费如下：\n"+words)){
            Fixture f=new Fixture("# 第一部分 合同协议书\n合同名称："+NAME+"\n合同编号："+NUMBER+"\n2. 签约合同价为：\n\n"+next,"合同附页");
            Source page=source(f.corpus,4);
            Map<String,Object> raw=field("amount","1946375.00",page);
            Map<String,Object> attrs=(Map<String,Object>)raw.get("attributes");attrs.put("unit","元");attrs.put("amountBasis","签约合同价");
            Map<String,Object> receipt=submit(f,raw);
            assertEquals(next.equals(words),RuleEvaluator.maps(receipt.get("rejections")).isEmpty(),receipt::toString);
            attrs.put("amountBasis","暂定签约合同价");
            assertTrue(submit(f,raw).toString().contains("CONTRACT_AMOUNT_BASIS_UNPROVEN"));
            if(next.equals(words)){
                attrs.put("amountBasis","签约合同价");attrs.put("unit","万元");
                assertTrue(submit(f,raw).toString().contains("CONTRACT_AMOUNT_UNIT_UNPROVEN"));
            }
        }
    }
    @Test void claimedFieldReceiptCannotExpandFieldSourcesOrBeForgedByTheModel()throws Exception {
        Fixture f=new Fixture("第一部分 合同协议书\n合同名称："+NAME+"\n合同编号："+NUMBER+"\n合同金额：200万元","本页未记载金额");
        Source cover=source(f.corpus,4),other=source(f.corpus,5);
        Map<String,Object> receipt=submit(f,amount(cover));
        Fact actual=f.host.facts.values().stream().filter(v->"amount".equals(v.field())).findFirst().orElse(null);
        assertNotNull(actual,receipt::toString);
        ProofVerifier v=ContractRecordBindings.verifier(f.corpus,SourcePerformanceScore.evidenceRules(f.parent),f.corpus.all);
        Map<String,Object> changed=new LinkedHashMap<>(actual.attributes());
        changed.put(ContractEvidenceRecord.FIELD_SOURCES,Arrays.asList(cover.id(),other.id()));
        assertEquals("CONTRACT_PAGE_LINK_UNPROVEN",v.issue(attributes(actual,changed),f.corpus.byId));
        changed.remove(ContractEvidenceRecord.FIELD_SOURCES);
        assertEquals("RAW_VALUE_UNGROUNDED",v.issue(attributes(actual,changed),f.corpus.byId));
        Map<String,Object> claim=f.candidate("amount","200");claim.put("attributes",actual.attributes());
        assertTrue(f.submit(claim).toString().contains("CONTRACT_RECORD_NOT_HOST_ISSUED"));
        Map<String,Object> signed=field("signedDate","2023年1月1日",cover);
        ((Map<String,Object>)signed.get("attributes")).put(ContractEvidenceRecord.FIELD_SOURCES,Collections.singletonList(other.id()));
        assertTrue(submit(f,signed).toString().contains("CONTRACT_ATOMIC_FIELDS_INVALID"));
    }
    @Test void retryOfBodyFieldWithEmptyOrMissingRecordNumberReusesTheSameFact()throws Exception {
        Fixture f=new Fixture("# 第一部分 合同协议书\n合同名称："+NAME+"\n合同金额：200万元","合同附页");
        Source page=source(f.corpus,4);
        Map<String,Object> outer=f.candidate("contractName",NAME);outer.put("attributes",linked("contractName",NAME));
        Map<String,Object> atom=amount(page);
        Map<String,Object> attrs=(Map<String,Object>)atom.get("attributes");attrs.put("contractNumber","");
        Map<String,Object> request=linked("targetObligationId",f.target,"submissionMode","CONTRACT_FIELDS","fact",outer,
            "contractFields",Collections.singletonList(atom));
        Map<String,Object> first=f.host.call("submit_candidate_fact",request);
        String id=ProofVerifier.strings(RuleEvaluator.maps(first.get("fieldResults")).get(0).get("acceptedFactIds")).get(0);
        attrs.remove("contractNumber");attrs.put("contractName",NAME);
        Map<String,Object> second=RuleEvaluator.maps(f.host.call("submit_candidate_fact",request).get("fieldResults")).get(0);
        assertTrue(ProofVerifier.strings(second.get("newlyAddedFactIds")).isEmpty());
        assertEquals(Collections.singletonList(id),second.get("alreadyKnownFactIds"));
    }
}
