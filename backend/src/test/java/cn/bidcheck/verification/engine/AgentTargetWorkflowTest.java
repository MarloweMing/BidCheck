package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** No provider calls: exercises the exact Java protocol consumed by the SDK worker. */
class AgentTargetWorkflowTest {
    private Rule certificate(String id,String person){return new Rule(id,"人员证书","CERTIFICATE",person,"证书","EXISTS","注册建造师证书","",Map.of(),"应提供注册建造师证书","",List.of());}
    private EvidenceInvestigation.Host host(List<Rule> rules,String... pages){
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(SharedFactReuseTest.doc("bid","project",1,pages)));
        Map<String,Object> context=new LinkedHashMap<>();
        return new EvidenceInvestigation.Host(new EvidenceInvestigationTest().checkpoint(),new Plan("ALL",rules,"LOCAL",List.of()),rules,corpus,new LinkedHashMap<>(),context,
            new SharedFactReuse(corpus,context,"disabled","test"),new RunSession(ModelGateway.disabled(),context,"test"),null,new ArrayList<>(),Set.of());
    }
    private Source page(EvidenceInvestigation.Host host,int page){return host.corpus.all.stream().filter(s->s.evidence().page()==page).findFirst().orElseThrow();}
    private Map<String,Object> candidate(String field,String kind,String source){return linked("kind",kind,"subject","李明","field",field,
        "rawValue","不存在的证书值","value","不存在的证书值","unit","","basis","","scope","UNRESOLVED","sourceIds",List.of(source),"attributes",Map.of());}
    private Map<String,Object> submit(EvidenceInvestigation.Host host,String target,String field,String kind,String source){return host.call("submit_candidate_fact",Map.of("targetObligationId",target,"fact",candidate(field,kind,source)));}
    private Map<String,Object> work(Map<String,Object> result,String target){return RuleEvaluator.maps(result.get("semanticWorkItems")).stream().filter(item->target.equals(str(item,"targetObligationId"))).findFirst().orElseThrow();}
    private Map<String,Object> issues(String code){return Map.of("code",code);}

    @Test void startActivatesTypedWorkflowAndCandidateSchemaRequiresKnownTarget(){
        var host=host(List.of(certificate("cert-a","李明")),"李明的资格证明");var start=host.start();
        Map<?,?> context=(Map<?,?>)start.get("context");assertEquals("TARGET_FACTS_V1",context.get("workflowPolicy"));assertEquals(List.of("cert-a"),context.get("targetObligationIds"));
        var submit=RuleEvaluator.maps(start.get("tools")).stream().filter(t->"submit_candidate_fact".equals(str(t,"name"))).findFirst().orElseThrow();
        Map<?,?> schema=(Map<?,?>)submit.get("parameters"),properties=(Map<?,?>)schema.get("properties");
        assertTrue(((List<?>)schema.get("required")).contains("targetObligationId"));
        assertEquals(List.of("cert-a"),((Map<?,?>)properties.get("targetObligationId")).get("enum"));
        assertFalse(context.get("knownSources").toString().contains("资格证明"),"Navigation handles must not be mistaken for returned source bodies");
    }

    @Test void missingAndForeignTargetCannotReachFactAdoption(){
        var host=host(List.of(certificate("cert-a","李明")),"姓名：李明");host.start();String source=page(host,1).id();
        for(var args:List.of(Map.<String,Object>of("fact",candidate("证书","FIELD",source)),Map.<String,Object>of("targetObligationId","foreign","fact",candidate("证书","FIELD",source)))){
            var result=host.call("submit_candidate_fact",args);assertEquals("REJECTED",result.get("status"));assertEquals("INVALID_TARGET_RECEIPT",result.get("error"));
            assertEquals(true,result.get("correctable"));assertEquals("/targetObligationId",result.get("fieldPath"));
            assertEquals(List.of("cert-a"),result.get("allowedTargetObligationIds"));
        }
        assertTrue(host.facts.isEmpty());assertTrue(host.rejected.isEmpty());
    }

    @Test void aliasesAndFactKindsShareOneFailedBusinessWorkKey(){
        var host=host(List.of(certificate("cert-a","李明")),"姓名：李明\n证书材料尚未取得明确编号。");host.start();Source source=page(host,1);host.disclosed.put(source.id(),source);
        var first=submit(host,"cert-a","证号","FIELD",source.id());var second=submit(host,"cert-a","注册编号","NUMBER",source.id());
        assertEquals("REJECTED",first.get("status"));assertEquals("REJECTED",second.get("status"));
        var a=work(first,"cert-a");var b=work(second,"cert-a");
        assertEquals(a.get("workKey"),b.get("workKey"));assertEquals(a.get("evidenceRevision"),b.get("evidenceRevision"));
        assertEquals(false,a.get("terminalForRevision"));assertEquals(true,b.get("terminalForRevision"));assertEquals(true,b.get("targetExhaustedForRevision"));
        assertEquals(List.of("cert-a"),second.get("affectedTargetObligationIds"));assertTrue(host.facts.isEmpty());
    }

    @Test void oneTargetsFailureCannotExhaustAnotherTarget(){
        var workflow=new AgentTargetWorkflow();Rule a=certificate("A","李明"),b=certificate("B","王强");
        workflow.observe(a,"person-a","revision-a",List.of(issues("RAW_VALUE_UNGROUNDED")));
        assertEquals(true,workflow.observe(a,"person-a","revision-a",List.of(issues("FACT_OUTSIDE_TARGET"))).get("terminalForRevision"));
        assertEquals(false,workflow.state(b,"person-b","revision-b").get("terminalForRevision"));
        assertEquals(false,workflow.observe(b,"person-b","revision-b",List.of(issues("RAW_VALUE_UNGROUNDED"))).get("terminalForRevision"));
    }

    @Test void newRelevantRecordRevisionAllowsRepairWithoutGlobalCallBudget(){
        var workflow=new AgentTargetWorkflow();Rule rule=certificate("A","李明");
        workflow.observe(rule,"person-a","record-one",List.of(issues("RAW_VALUE_UNGROUNDED")));
        workflow.observe(rule,"person-a","record-one",List.of(issues("VALUE_UNGROUNDED")));
        assertEquals(true,workflow.state(rule,"person-a","record-one").get("terminalForRevision"));
        assertEquals(false,workflow.state(rule,"person-a","record-two").get("terminalForRevision"));
        assertEquals(false,workflow.observe(rule,"person-a","record-two",List.of(issues("RAW_VALUE_UNGROUNDED"))).get("terminalForRevision"));
    }

    @Test void changingFailureFamilyAllowsItsSpecifiedRepairButNotUnlimitedAliases(){
        var workflow=new AgentTargetWorkflow();Rule rule=certificate("A","李明");
        var binding=workflow.observe(rule,"person-a","same-record",List.of(issues("CERTIFICATE_SUBJECT_FIELD_BINDING_INVALID")));
        var extraction=workflow.observe(rule,"person-a","same-record",List.of(issues("RAW_VALUE_UNGROUNDED")));
        assertEquals(List.of("RESOLVE_SUBJECT_FIRST"),binding.get("remainingRepairActions"));assertEquals(false,extraction.get("terminalForRevision"));
        assertEquals(true,workflow.observe(rule,"person-a","same-record",List.of(issues("VALUE_UNGROUNDED"))).get("terminalForRevision"));
    }

    @Test void readingRelevantPageWithoutNewGroundedFactsDoesNotResetTargetEvidenceVersion(){
        var host=host(List.of(certificate("A","李明"),certificate("B","王强")),"姓名：李明\n人员登记资料", "姓名：李明\n另一份人员附件", "姓名：王强\n另一人的人员附件");host.start();
        Source first=page(host,1),next=page(host,2);host.disclosed.put(first.id(),first);host.navigation.put(next.id(),next);
        var before=host.call("recompute_check",Map.of());var read=host.call("read_section",Map.of("sourceId",next.id()));
        Map<?,?> beforeVersions=(Map<?,?>)before.get("targetEvidenceRevisions"),afterVersions=(Map<?,?>)read.get("targetEvidenceRevisions");
        assertEquals(beforeVersions,afterVersions,"Navigation alone is not new grounded evidence for either target");
        // This read exposes page 3 too; repeat it must not invent another version.
        var repeated=host.call("read_section",Map.of("sourceId",next.id()));assertEquals(afterVersions,repeated.get("targetEvidenceRevisions"));
    }

    @Test void unrelatedTableLayoutCannotUnlockPreviouslyFailedTarget(){
        var host=host(List.of(certificate("A","李明")),"姓名：李明\n人员登记资料", "物资采购价格表\n采购数量：20");host.start();
        Source first=page(host,1),unrelated=page(host,2);host.disclosed.put(first.id(),first);host.disclosed.put(unrelated.id(),unrelated);
        submit(host,"A","证号","FIELD",first.id());var stopped=submit(host,"A","注册编号","NUMBER",first.id());
        Map<String,Object> cell=linked("documentId","bid","documentHash",unrelated.documentHash(),"documentVersion",1,"locationType","PAGE","location",2,"spanId","unrelated-cell","kind","TABLE_CELL","text","20");
        host.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->List.of(cell));
        var read=host.call("read_table_record",Map.of("sourceId",unrelated.id(),"rowOffset",0));
        assertEquals(work(stopped,"A").get("evidenceRevision"),work(read,"A").get("evidenceRevision"),"Unrelated layout must not reopen the same failed certificate target");
        assertEquals(true,work(read,"A").get("targetExhaustedForRevision"));
    }

    @Test void largePageContinuationAdvancesInsteadOfReturningTheFirstWindowAgain(){
        var host=host(List.of(certificate("A","李明")),"参考说明。".repeat(10000));host.start();Source source=page(host,1);host.navigation.put(source.id(),source);
        var first=host.call("read_section",Map.of("sourceId",source.id()));assertTrue(first.containsKey("nextSourceId"));
        var next=host.call("read_section",Map.of("sourceId",first.get("nextSourceId")));
        assertNotEquals(first.get("sourceIds"),next.get("sourceIds"),"The next source on an oversized page must advance the read cursor");
    }

    @Test void completeNativeCertificateReadImmediatelyRecomputesItsTarget(){
        Rule rule=new Rule("cert","注册建造师","CERTIFICATE","李明","证书","","","",Map.of("category","一级注册建造师","profession","建筑工程"),"一级注册建造师建筑工程专业","",List.of());
        String text="一级建造师注册证书\n姓名：李明\n出生日期：1981年11月22日\n"+"图像说明，不作为证书字段。\n".repeat(110)+"注册编号：京1999999999990001\n聘用企业：北方设计有限公司\n注册专业：建筑工程(有效期：2023-08-07至2026-08-06)";
        var host=host(List.of(rule),text);host.start();Source number=host.corpus.all.stream().filter(s->s.evidence().quote().contains("注册编号")).findFirst().orElseThrow();host.navigation.put(number.id(),number);
        var read=host.call("read_section",Map.of("sourceId",number.id()));assertFalse(ProofVerifier.strings(read.get("acceptedFactIds")).isEmpty(),read.toString());
        Map<?,?> receipt=(Map<?,?>)read.get("computationReceipt");assertNotNull(receipt);assertEquals("HOST_RULE_RECOMPUTE",receipt.get("kind"));assertEquals(true,receipt.get("targetCheckCompleted"),receipt.toString());
        assertEquals(read.get("targetEvidenceRevision"),receipt.get("targetEvidenceRevision"));assertTrue(host.facts.values().stream().anyMatch(f->f.kind().equals("CERTIFICATE")&&f.sourceIds().size()>1));
    }

    @Test void nativeSocialTableRecordReadRecomputesMonthsWithoutFabricatedCombinedQuote(){
        Rule rule=new Rule("months","连续社保","MONTHS","甲某","socialInsurance","","","月",Map.of("count",3),"连续社保三个月","",List.of());
        String text="单位名称：北方建筑有限公司\n<table><tr><td>姓名</td><td>社会保障号码</td><td>险种</td><td>起始年月</td><td>截止年月</td><td>本单位实际缴费月数</td></tr>"
            +"<tr><td>甲某</td><td>110101199001010011</td><td>养老保险</td><td>2025年01月</td><td>2025年08月</td><td>8</td></tr></table>";
        var host=host(List.of(rule),text);host.start();Source source=page(host,1);host.navigation.put(source.id(),source);
        var read=host.call("read_section",Map.of("sourceId",source.id()));assertFalse(ProofVerifier.strings(read.get("acceptedFactIds")).isEmpty(),read.toString());
        Map<?,?> receipt=(Map<?,?>)read.get("computationReceipt");assertNotNull(receipt);assertEquals(true,receipt.get("targetCheckCompleted"),receipt.toString());
        assertEquals(read.get("targetEvidenceRevision"),receipt.get("targetEvidenceRevision"));
        assertTrue(host.facts.values().stream().anyMatch(f->f.kind().equals("MONTHS")&&f.attributes().containsKey("sourceFieldSpans")));
    }
}
