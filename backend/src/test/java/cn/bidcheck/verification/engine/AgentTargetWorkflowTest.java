package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** No provider calls: exercises the exact Java protocol consumed by the SDK worker. */
class AgentTargetWorkflowTest {
    private Rule certificate(String id,String person){return new Rule(id,"人员证书","CERTIFICATE",person,"证书","EXISTS","注册建造师证书","",cn.bidcheck.compat.Java8Api.mapOf(),"应提供注册建造师证书","",cn.bidcheck.compat.Java8Api.listOf());}
    private EvidenceInvestigation.Host host(List<Rule> rules,String... pages){
        EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","project",1,pages)));
        Map<String,Object> context=new LinkedHashMap<>();
        return new EvidenceInvestigation.Host(new EvidenceInvestigationTest().checkpoint(),new Plan("ALL",rules,"LOCAL",cn.bidcheck.compat.Java8Api.listOf()),rules,corpus,new LinkedHashMap<>(),context,
            new SharedFactReuse(corpus,context,"disabled","test"),new RunSession(ModelGateway.disabled(),context,"test"),null,new ArrayList<>(),cn.bidcheck.compat.Java8Api.setOf());
    }
    private Source page(EvidenceInvestigation.Host host,int page){return host.corpus.all.stream().filter(s->s.evidence().page()==page).findFirst().orElseThrow(java.util.NoSuchElementException::new);}
    private Map<String,Object> candidate(String field,String kind,String source){return linked("kind",kind,"subject","李明","field",field,
        "rawValue","不存在的证书值","value","不存在的证书值","unit","","basis","","scope","UNRESOLVED","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source),"attributes",cn.bidcheck.compat.Java8Api.mapOf());}
    private Map<String,Object> submit(EvidenceInvestigation.Host host,String target,String field,String kind,String source){return host.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", target, "fact", candidate(field,kind,source)));}
    private Map<String,Object> work(Map<String,Object> result,String target){return RuleEvaluator.maps(result.get("semanticWorkItems")).stream().filter(item->target.equals(str(item,"targetObligationId"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);}
    private Map<String,Object> issues(String code){return cn.bidcheck.compat.Java8Api.mapOf("code", code);}

    @Test void startActivatesTypedWorkflowAndCandidateSchemaRequiresKnownTarget(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=host(cn.bidcheck.compat.Java8Api.listOf(certificate("cert-a","李明")),"李明的资格证明");java.util.Map<java.lang.String, java.lang.Object> start=host.start();
        Map<?,?> context=(Map<?,?>)start.get("context");assertEquals("TARGET_FACTS_V1",context.get("workflowPolicy"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("cert-a"),context.get("targetObligationIds"));
        java.util.Map<java.lang.String, java.lang.Object> submit=RuleEvaluator.maps(start.get("tools")).stream().filter(t->"submit_candidate_fact".equals(str(t,"name"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        Map<?,?> schema=(Map<?,?>)submit.get("parameters"),properties=(Map<?,?>)schema.get("properties");
        assertTrue(((List<?>)schema.get("required")).contains("targetObligationId"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("cert-a"),((Map<?,?>)properties.get("targetObligationId")).get("enum"));
        assertFalse(context.get("knownSources").toString().contains("资格证明"),"Navigation handles must not be mistaken for returned source bodies");
    }

    @Test void missingAndForeignTargetCannotReachFactAdoption(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=host(cn.bidcheck.compat.Java8Api.listOf(certificate("cert-a","李明")),"姓名：李明");host.start();String source=page(host,1).id();
        for(java.util.Map<java.lang.String, java.lang.Object> args:cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.<String, Object>mapOf("fact", candidate("证书","FIELD",source)), cn.bidcheck.compat.Java8Api.<String, Object>mapOf("targetObligationId", "foreign", "fact", candidate("证书","FIELD",source)))){
            java.util.Map<java.lang.String, java.lang.Object> result=host.call("submit_candidate_fact",args);assertEquals("REJECTED",result.get("status"));assertEquals("INVALID_TARGET_RECEIPT",result.get("error"));
            assertEquals(true,result.get("correctable"));assertEquals("/targetObligationId",result.get("fieldPath"));
            assertEquals(cn.bidcheck.compat.Java8Api.listOf("cert-a"),result.get("allowedTargetObligationIds"));
        }
        assertTrue(host.facts.isEmpty());assertTrue(host.rejected.isEmpty());
    }

    @Test void aliasesAndFactKindsShareOneFailedBusinessWorkKey(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=host(cn.bidcheck.compat.Java8Api.listOf(certificate("cert-a","李明")),"姓名：李明\n证书材料尚未取得明确编号。");host.start();Source source=page(host,1);host.disclosed.put(source.id(),source);
        java.util.Map<java.lang.String, java.lang.Object> first=submit(host,"cert-a","证号","FIELD",source.id());java.util.Map<java.lang.String, java.lang.Object> second=submit(host,"cert-a","注册编号","NUMBER",source.id());
        assertEquals("REJECTED",first.get("status"));assertEquals("REJECTED",second.get("status"));
        java.util.Map<java.lang.String, java.lang.Object> a=work(first,"cert-a");java.util.Map<java.lang.String, java.lang.Object> b=work(second,"cert-a");
        assertEquals(a.get("workKey"),b.get("workKey"));assertEquals(a.get("evidenceRevision"),b.get("evidenceRevision"));
        assertEquals(false,a.get("terminalForRevision"));assertEquals(true,b.get("terminalForRevision"));assertEquals(true,b.get("targetExhaustedForRevision"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("cert-a"),second.get("affectedTargetObligationIds"));assertTrue(host.facts.isEmpty());
    }

    @Test void oneTargetsFailureCannotExhaustAnotherTarget(){
        cn.bidcheck.verification.engine.AgentTargetWorkflow workflow=new AgentTargetWorkflow();Rule a=certificate("A","李明"),b=certificate("B","王强");
        workflow.observe(a,"person-a","revision-a",cn.bidcheck.compat.Java8Api.listOf(issues("RAW_VALUE_UNGROUNDED")));
        assertEquals(true,workflow.observe(a,"person-a","revision-a",cn.bidcheck.compat.Java8Api.listOf(issues("FACT_OUTSIDE_TARGET"))).get("terminalForRevision"));
        assertEquals(false,workflow.state(b,"person-b","revision-b").get("terminalForRevision"));
        assertEquals(false,workflow.observe(b,"person-b","revision-b",cn.bidcheck.compat.Java8Api.listOf(issues("RAW_VALUE_UNGROUNDED"))).get("terminalForRevision"));
    }

    @Test void newRelevantRecordRevisionAllowsRepairWithoutGlobalCallBudget(){
        cn.bidcheck.verification.engine.AgentTargetWorkflow workflow=new AgentTargetWorkflow();Rule rule=certificate("A","李明");
        workflow.observe(rule,"person-a","record-one",cn.bidcheck.compat.Java8Api.listOf(issues("RAW_VALUE_UNGROUNDED")));
        workflow.observe(rule,"person-a","record-one",cn.bidcheck.compat.Java8Api.listOf(issues("VALUE_UNGROUNDED")));
        assertEquals(true,workflow.state(rule,"person-a","record-one").get("terminalForRevision"));
        assertEquals(false,workflow.state(rule,"person-a","record-two").get("terminalForRevision"));
        assertEquals(false,workflow.observe(rule,"person-a","record-two",cn.bidcheck.compat.Java8Api.listOf(issues("RAW_VALUE_UNGROUNDED"))).get("terminalForRevision"));
    }

    @Test void changingFailureFamilyAllowsItsSpecifiedRepairButNotUnlimitedAliases(){
        cn.bidcheck.verification.engine.AgentTargetWorkflow workflow=new AgentTargetWorkflow();Rule rule=certificate("A","李明");
        java.util.Map<java.lang.String, java.lang.Object> binding=workflow.observe(rule,"person-a","same-record",cn.bidcheck.compat.Java8Api.listOf(issues("CERTIFICATE_SUBJECT_FIELD_BINDING_INVALID")));
        java.util.Map<java.lang.String, java.lang.Object> extraction=workflow.observe(rule,"person-a","same-record",cn.bidcheck.compat.Java8Api.listOf(issues("RAW_VALUE_UNGROUNDED")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("RESOLVE_SUBJECT_FIRST"),binding.get("remainingRepairActions"));assertEquals(false,extraction.get("terminalForRevision"));
        assertEquals(true,workflow.observe(rule,"person-a","same-record",cn.bidcheck.compat.Java8Api.listOf(issues("VALUE_UNGROUNDED"))).get("terminalForRevision"));
    }

    @Test void readingRelevantPageWithoutNewGroundedFactsDoesNotResetTargetEvidenceVersion(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=host(cn.bidcheck.compat.Java8Api.listOf(certificate("A","李明"), certificate("B","王强")),"姓名：李明\n人员登记资料", "姓名：李明\n另一份人员附件", "姓名：王强\n另一人的人员附件");host.start();
        Source first=page(host,1),next=page(host,2);host.disclosed.put(first.id(),first);host.navigation.put(next.id(),next);
        java.util.Map<java.lang.String, java.lang.Object> before=host.call("recompute_check",cn.bidcheck.compat.Java8Api.mapOf());java.util.Map<java.lang.String, java.lang.Object> read=host.call("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", next.id()));
        Map<?,?> beforeVersions=(Map<?,?>)before.get("targetEvidenceRevisions"),afterVersions=(Map<?,?>)read.get("targetEvidenceRevisions");
        assertEquals(beforeVersions,afterVersions,"Navigation alone is not new grounded evidence for either target");
        // This read exposes page 3 too; repeat it must not invent another version.
        java.util.Map<java.lang.String, java.lang.Object> repeated=host.call("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", next.id()));assertEquals(afterVersions,repeated.get("targetEvidenceRevisions"));
    }

    @Test void unrelatedTableLayoutCannotUnlockPreviouslyFailedTarget(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=host(cn.bidcheck.compat.Java8Api.listOf(certificate("A","李明")),"姓名：李明\n人员登记资料", "物资采购价格表\n采购数量：20");host.start();
        Source first=page(host,1),unrelated=page(host,2);host.disclosed.put(first.id(),first);host.disclosed.put(unrelated.id(),unrelated);
        submit(host,"A","证号","FIELD",first.id());java.util.Map<java.lang.String, java.lang.Object> stopped=submit(host,"A","注册编号","NUMBER",first.id());
        Map<String,Object> cell=linked("documentId","bid","documentHash",unrelated.documentHash(),"documentVersion",1,"locationType","PAGE","location",2,"spanId","unrelated-cell","kind","TABLE_CELL","text","20");
        host.ctx.put("layoutLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->cn.bidcheck.compat.Java8Api.listOf(cell));
        java.util.Map<java.lang.String, java.lang.Object> read=host.call("read_table_record",cn.bidcheck.compat.Java8Api.mapOf("sourceId", unrelated.id(), "rowOffset", 0));
        assertEquals(work(stopped,"A").get("evidenceRevision"),work(read,"A").get("evidenceRevision"),"Unrelated layout must not reopen the same failed certificate target");
        assertEquals(true,work(read,"A").get("targetExhaustedForRevision"));
    }

    @Test void largePageContinuationAdvancesInsteadOfReturningTheFirstWindowAgain(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=host(cn.bidcheck.compat.Java8Api.listOf(certificate("A","李明")),cn.bidcheck.compat.Java8Api.repeat("参考说明。", 10000));host.start();Source source=page(host,1);host.navigation.put(source.id(),source);
        java.util.Map<java.lang.String, java.lang.Object> first=host.call("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id()));assertTrue(first.containsKey("nextSourceId"));
        java.util.Map<java.lang.String, java.lang.Object> next=host.call("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", first.get("nextSourceId")));
        assertNotEquals(first.get("sourceIds"),next.get("sourceIds"),"The next source on an oversized page must advance the read cursor");
    }

    @Test void completeNativeCertificateReadImmediatelyRecomputesItsTarget(){
        Rule rule=new Rule("cert","注册建造师","CERTIFICATE","李明","证书","","","",cn.bidcheck.compat.Java8Api.mapOf("category", "一级注册建造师", "profession", "建筑工程"),"一级注册建造师建筑工程专业","",cn.bidcheck.compat.Java8Api.listOf());
        String text="一级建造师注册证书\n姓名：李明\n出生日期：1981年11月22日\n"+cn.bidcheck.compat.Java8Api.repeat("图像说明，不作为证书字段。\n", 110)+"注册编号：京1999999999990001\n聘用企业：北方设计有限公司\n注册专业：建筑工程(有效期：2023-08-07至2026-08-06)";
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=host(cn.bidcheck.compat.Java8Api.listOf(rule),text);host.start();Source number=host.corpus.all.stream().filter(s->s.evidence().quote().contains("注册编号")).findFirst().orElseThrow(java.util.NoSuchElementException::new);host.navigation.put(number.id(),number);
        java.util.Map<java.lang.String, java.lang.Object> read=host.call("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", number.id()));assertFalse(ProofVerifier.strings(read.get("acceptedFactIds")).isEmpty(),read.toString());
        Map<?,?> receipt=(Map<?,?>)read.get("computationReceipt");assertNotNull(receipt);assertEquals("HOST_RULE_RECOMPUTE",receipt.get("kind"));assertEquals(true,receipt.get("targetCheckCompleted"),receipt.toString());
        assertEquals(read.get("targetEvidenceRevision"),receipt.get("targetEvidenceRevision"));assertTrue(host.facts.values().stream().anyMatch(f->f.kind().equals("CERTIFICATE")&&f.sourceIds().size()>1));
    }

    @Test void nativeSocialTableRecordReadRecomputesMonthsWithoutFabricatedCombinedQuote(){
        Rule rule=new Rule("months","连续社保","MONTHS","甲某","socialInsurance","","","月",cn.bidcheck.compat.Java8Api.mapOf("count", 3),"连续社保三个月","",cn.bidcheck.compat.Java8Api.listOf());
        String text="单位名称：北方建筑有限公司\n<table><tr><td>姓名</td><td>社会保障号码</td><td>险种</td><td>起始年月</td><td>截止年月</td><td>本单位实际缴费月数</td></tr>"
            +"<tr><td>甲某</td><td>110101199001010011</td><td>养老保险</td><td>2025年01月</td><td>2025年08月</td><td>8</td></tr></table>";
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=host(cn.bidcheck.compat.Java8Api.listOf(rule),text);host.start();Source source=page(host,1);host.navigation.put(source.id(),source);
        java.util.Map<java.lang.String, java.lang.Object> read=host.call("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id()));assertFalse(ProofVerifier.strings(read.get("acceptedFactIds")).isEmpty(),read.toString());
        Map<?,?> receipt=(Map<?,?>)read.get("computationReceipt");assertNotNull(receipt);assertEquals(true,receipt.get("targetCheckCompleted"),receipt.toString());
        assertEquals(read.get("targetEvidenceRevision"),receipt.get("targetEvidenceRevision"));
        assertTrue(host.facts.values().stream().anyMatch(f->f.kind().equals("MONTHS")&&f.attributes().containsKey("sourceFieldSpans")));
    }
}
