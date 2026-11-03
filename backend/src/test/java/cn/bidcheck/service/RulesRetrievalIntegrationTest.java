package cn.bidcheck.service;

import cn.bidcheck.consistency.*;
import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import cn.bidcheck.persistence.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:rules-retrieval;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.check-mode=RULES_RETRIEVAL","bidcheck.semantic.url=",
    "bidcheck.semantic.key=","bidcheck.semantic.model=","bidcheck.ocr.enabled=false","bidcheck.ocr.pdf.enabled=false"})
class RulesRetrievalIntegrationTest {
    static final Path DATA=temp();static Path temp(){try{return Files.createTempDirectory("rules-retrieval-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("bidcheck.data",()->DATA.toString());}
    @Autowired BidService service;@Autowired Store store;@Autowired ReportService reports;@Autowired PlatformTransactionManager manager;
    String parsed(String project,String role,String text)throws Exception {TaskRow task=service.upload(project,role,new MockMultipartFile("file","rules.txt","text/plain",text.getBytes(StandardCharsets.UTF_8)));service.execute(task);return store.map(task.inputJson).get("documentId").toString();}
    Map<String,Object> prepared()throws Exception {
        String project=service.createProject(cn.bidcheck.compat.Java8Api.mapOf("name", "规则优先回归")).get("id").toString();
        String tender=parsed(project,"TENDER","本项目投标文件企业名称、金额及人员应全文一致。");service.execute(service.extract(project,tender));
        for(Checkpoint cp:(List<Checkpoint>)service.detail(project).get("checkpoints")) service.updateCheckpoint(cp.id(),new Checkpoint(cp.id(),cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),cp.expected(),cp.unit(),cp.required(),true,"fullTextConsistency".equals(cp.field()),cp.source()));
        String bid=parsed(project,"BID","本项目投标函\n投标人名称：北京甲科技有限公司\n投标总报价：100万元\n本项目报价表\n投标人名称：北京乙科技有限公司\n投标总报价：120万元");
        return service.startRun(project,bid);
    }
    @Test void defaultRulesScanRetainsConflictsAndExportsExplicitUnrequestedModelScope()throws Exception {
        Map<String,Object> run=prepared();assertEquals("RULES_RETRIEVAL",run.get("checkMode"));
        service.execute(service.task(run.get("taskId").toString()));
        Map<String,Object> payload=service.run(run.get("id").toString(),false),report=ConsistencyRunSupport.map(payload.get("consistency")),coverage=ConsistencyRunSupport.map(report.get("coverage"));
        assertEquals("RULES_RETRIEVAL",coverage.get("checkMode"));assertEquals(false,ConsistencyRunSupport.map(coverage.get("model")).get("requested"));
        assertEquals("NOT_REQUESTED",ConsistencyRunSupport.map(coverage.get("model")).get("status"));
        assertEquals(coverage.get("pagesTotal"),ConsistencyRunSupport.map(coverage.get("ruleScan")).get("pagesTotal"));
        assertFalse(ConsistencyRunSupport.maps(report.get("groups")).isEmpty());assertTrue(store.maps("consistency_budget",run.get("projectId").toString(),run.get("id").toString()).isEmpty());
        assertFalse(ConsistencyRunSupport.map(report.get("doubtReview")).isEmpty());
        assertTrue(ConsistencyRunSupport.maps(report.get("groups")).stream().allMatch(g->ConsistencyDoubtReviewer.VERSION.equals(ConsistencyRunSupport.map(g.get("targetedReview")).get("policyVersion"))));
        assertTrue(((List<Finding>)payload.get("results")).stream().filter(f->f.assessment()!=null&&f.assessment().containsKey("consistencyGroupId"))
            .allMatch(f->f.assessment().containsKey("consistencyReview")));
        String json=new String(reports.generate(run.get("id").toString(),"json").bytes(),StandardCharsets.UTF_8);
        assertTrue(json.contains("\"onDemandModel\""));assertTrue(json.contains("NOT_REQUESTED"));
        assertTrue(new String(reports.generate(run.get("id").toString(),"html").bytes(),StandardCharsets.UTF_8).contains("本次未请求全文生成式抽取"));
        assertTrue(new String(reports.generate(run.get("id").toString(),"csv").bytes(),StandardCharsets.UTF_8).contains("未请求全文生成式抽取"));
    }
    @Test void changedModeCannotExecuteFrozenRun()throws Exception {Map<String,Object> run=prepared();Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(service);String previous=service.checkMode();try {ReflectionTestUtils.setField(target,"configuredCheckMode","FULL_MODEL");assertThrows(Exception.class,()->service.execute(service.task(run.get("taskId").toString())));}finally{ReflectionTestUtils.setField(target,"configuredCheckMode",previous);}}
    @Test void durableAttemptCountsRemainAccurateAcrossConcurrentWorkersAndRecoveryWithoutLegacyCap()throws Exception {
        Map<String,Object> run=prepared();String id=run.get("id").toString();TaskRow task=service.task(run.get("taskId").toString());
        task.status="RUNNING";store.mapper().updateTask(task);run.put("status","RUNNING");run.put("onDemandAttemptBudget",3);store.save("run",id,task.projectId,run.get("documentId").toString(),run);
        TransactionTemplate tx=new TransactionTemplate(manager);ExecutorService pool=Executors.newFixedThreadPool(6);
        try {List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<12;i++)results.add(pool.submit(()->new OnDemandModelBudget(store,tx,task,id).reserve()));int admitted=0;for(Future<Boolean> f:results)if(f.get(10,TimeUnit.SECONDS))admitted++;assertEquals(12,admitted);
            assertTrue(new OnDemandModelBudget(store,tx,task,id).reserve());assertEquals(13L,((Number)store.getMap("run",id).get("onDemandAttemptsReserved")).longValue());
        }finally{pool.shutdownNow();}
    }
    @Test void stoppedTaskCannotReserveDespiteUnlimitedAttempts()throws Exception {
        Map<String,Object> run=prepared();TaskRow task=service.task(run.get("taskId").toString());task.status="FAILED";store.mapper().updateTask(task);
        assertThrows(TaskLeaseLostException.class,()->new OnDemandModelBudget(store,new TransactionTemplate(manager),task,run.get("id").toString()).reserve());
    }
    @Test void consistencyReservationsTrackSeparateAndSharedTotalsWithoutLegacyCaps()throws Exception {
        Map<String,Object> run=prepared();String id=run.get("id").toString();TaskRow task=service.task(run.get("taskId").toString());
        task.status="RUNNING";store.mapper().updateTask(task);run.put("status","RUNNING");run.put("onDemandAttemptBudget",3);run.put("consistencyReviewAttemptBudget",2);
        store.save("run",id,task.projectId,run.get("documentId").toString(),run);
        OnDemandModelBudget budget=new OnDemandModelBudget(store,new TransactionTemplate(manager),task,id);
        assertTrue(budget.reserveConsistency());assertTrue(budget.reserveConsistency());assertTrue(budget.reserveConsistency());assertTrue(budget.reserve());assertTrue(budget.reserve());
        assertEquals(5L,((Number)store.getMap("run",id).get("onDemandAttemptsReserved")).longValue());
        assertEquals(3L,((Number)store.getMap("run",id).get("consistencyReviewAttemptsReserved")).longValue());
    }
    @Test void globalRuleLimitWithoutFullTextCardStillProducesExplicitUncertainty()throws Exception {
        Map<String,Object> initial=prepared();String project=initial.get("projectId").toString();
        for(Checkpoint cp:(List<Checkpoint>)service.detail(project).get("checkpoints"))service.updateCheckpoint(cp.id(),new Checkpoint(cp.id(),cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),cp.expected(),cp.unit(),cp.required(),true,false,cp.source()));
        service.createCheckpoint(project,BidService.fields("title","投标有效期","category","响应检查","type","VALUE_COMPARE","field","bidValidity","operator",">=","expected","90","unit","DAY","confirmed",true));
        String bid=parsed(project,"BID","投标有效期：90天\n"+cn.bidcheck.compat.Java8Api.repeat("A", 10001));
        Map<String,Object> created=service.startRun(project,bid);service.execute(service.task(created.get("taskId").toString()));
        Map<String,Object> payload=service.run(created.get("id").toString(),false);
        List<Finding> findings=(List<Finding>)payload.get("results");
        assertTrue(findings.stream().anyMatch(f->"FULL_TEXT_RULE_COVERAGE".equals(f.evaluation().method())&&"UNDETERMINED".equals(f.evaluation().status())));
        assertEquals("PARTIAL",ConsistencyRunSupport.map(ConsistencyRunSupport.map(payload.get("consistency")).get("coverage")).get("status"));
    }
}
