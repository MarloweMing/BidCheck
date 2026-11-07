package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.BidService;
import cn.bidcheck.verification.facts.FactLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import static cn.bidcheck.consistency.ConsistencyRunSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConsistencyPublicationTest {
    UnpooledDataSource dataSource;Store store;FactLedgerService ledger;TransactionTemplate tx;TaskRow task;
    final String project="project",run="run",engine="publication-test";Document document;Map<String,Object> report;
    @BeforeEach void setup()throws Exception {
        dataSource=new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:publication_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000","sa","");
        try(Connection connection=dataSource.getConnection();Reader reader=new InputStreamReader(resource("schema.sql"),StandardCharsets.UTF_8)){RunScript.execute(connection,reader);}
        Configuration config=new Configuration(new Environment("test",new SpringManagedTransactionFactory(),dataSource));config.setDatabaseId("h2");
        for(String name:cn.bidcheck.compat.Java8Api.listOf("RecordMapper", "FactMapper"))try(InputStream in=resource("mappers/"+name+".xml")){new XMLMapperBuilder(in,config,name,config.getSqlFragments()).parse();}
        org.mybatis.spring.SqlSessionTemplate session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));com.fasterxml.jackson.databind.ObjectMapper json=new ObjectMapper();
        store=spy(new Store(spy(session.getMapper(RecordMapper.class)),json));ledger=spy(new FactLedgerService(store,new RelationalFactRepository(session.getMapper(FactMapper.class),json)));
        tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store.save("project",project,project,"",values("id",project));
        task=new TaskRow();task.id="task";task.projectId=project;task.taskType="CHECK";task.inputJson="{}";task.status="PENDING";task.stage="waiting";task.createdAt=BidService.now();task.updatedAt=task.createdAt;
        store.mapper().insertTask(task);assertEquals(1,store.mapper().claimTask(task.id,BidService.now(),"owner",System.currentTimeMillis()+300000));task=store.mapper().task(task.id);
        prepare(81);
    }
    private static InputStream resource(String name){return Objects.requireNonNull(ConsistencyPublicationTest.class.getClassLoader().getResourceAsStream(name));}
    private void prepare(int count) {
        List<Map<String,Object>> facts=new ArrayList<>();List<String> lines=new ArrayList<>();
        for(int i=0;i<count;i++)lines.add("投标人名称：合成第"+i+"家公司");
        document=new Document("doc",project,"BID","synthetic.txt",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,String.join("\n",lines),"PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf());
        for(int i=0;i<count;i++){String value="合成第"+i+"家公司";facts.add(values("id","fact-"+i,"field","companyName","subject","BIDDER","scope","CURRENT_PROJECT","value",value,"rawValue",value,"unit","","basis","","evidence",new Evidence(document.id(),document.name(),1,"PAGE",lines.get(i),"line-"+i,1)));}
        report=values("occurrences",facts,"groups",cn.bidcheck.compat.Java8Api.listOf(),"coverage",values("elapsedMillis",7));
        store.save("run",run,project,document.id(),values("id",run,"projectId",project,"taskId",task.id,"documentId",document.id(),"documentVersion",document.version(),"consistencyIdentity",engine,"status","RUNNING"));
    }
    private void publish(){new ConsistencyPublication(store,ledger,tx).publish(task,run,document,engine,report,findings(),this::progress);}
    private List<Finding> findings(){return cn.bidcheck.compat.Java8Api.listOf(new Finding("finding",run,new Checkpoint("cp","差异","全文信息检查","HIGH","FULL_TEXT_REVIEW","fullTextConsistency","REVIEW","核对","",true,true,true,null),new Evaluation("WARNING","待人工核对","对照原文",cn.bidcheck.compat.Java8Api.listOf(),0.5,"TEST"),cn.bidcheck.compat.Java8Api.mapOf()));}
    private void progress(String text){task.status="RUNNING";task.stage=text;task.progress=5;task.updatedAt=BidService.now();assertEquals(1,store.mapper().updateTask(task));}
    private long count(String table)throws Exception {try(Connection c=dataSource.getConnection();Statement s=c.createStatement();ResultSet rows=s.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();return rows.getLong(1);}}
    private void sql(String sql)throws Exception {try(Connection c=dataSource.getConnection();Statement s=c.createStatement()){s.executeUpdate(sql);}}
    private List<Map<String,Object>> records(String kind){return store.maps(kind,project,run);}
    private void assertNotPublished(){assertTrue(records("fact_snapshot").isEmpty());assertTrue(records("consistency_report").isEmpty());assertTrue(records("finding").isEmpty());}
    private static Throwable failure(Future<?> future)throws Exception{return assertThrows(ExecutionException.class,()->future.get(10,TimeUnit.SECONDS)).getCause();}
    private static void await(CountDownLatch latch)throws InterruptedException{assertTrue(latch.await(10,TimeUnit.SECONDS));}
    private static void awaitEntered(CountDownLatch latch,Future<?> publication)throws Exception {
        if(latch.await(10,TimeUnit.SECONDS))return;
        if(publication.isDone())publication.get(); // Surface the producer exception rather than hiding it behind a latch timeout.
        fail("Publication is still running before snapshot preparation; no ownership change was performed.");
    }

    @Test void snapshotHydrationNeverHoldsTaskRowAndBatchProgressIsCommitted()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger persistedBatches=new AtomicInteger();
        doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());ConnectionHolder holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource);assertNotNull(holder);assertTrue(holder.getTimeToLiveInSeconds()<=10);persistedBatches.incrementAndGet();return call.callRealMethod();}).when(ledger).publishPrepared(any());
        doAnswer(call->{ConnectionHolder holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource);assertNotNull(holder);int seconds=holder.getTimeToLiveInSeconds();assertTrue(seconds>10&&seconds<=30);return call.callRealMethod();}).when(ledger).freezePreparedSnapshot(any());
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();await(release);return call.callRealMethod();}).when(ledger).prepareSnapshot(any(),anyString(),anyList());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> publication=pool.submit(this::publish);awaitEntered(entered,publication);
            assertEquals(9,persistedBatches.get());assertEquals(81,count("qc_fact_occurrence"));assertNotPublished();
            assertTrue(store.mapper().task(task.id).stage.contains("正在核对完整性"));
            Future<Integer> heartbeat=pool.submit(()->store.mapper().renewLease(task.id,task.leaseOwner,System.currentTimeMillis(),System.currentTimeMillis()+300000));
            assertEquals(1,heartbeat.get(1,TimeUnit.SECONDS));assertEquals(1,store.mapper().task(task.id).attempt);
            release.countDown();publication.get(10,TimeUnit.SECONDS);
            assertEquals(1,records("fact_snapshot").size());assertEquals(1,records("consistency_report").size());assertEquals(1,records("finding").size());
            assertEquals(81,((Number)map(records("fact_snapshot").get(0).get("summary")).get("occurrences")).intValue());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void committedBatchesResumeDespiteChangedTimingButNeverRepeatWrites()throws Exception {
        AtomicInteger calls=new AtomicInteger();AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(call->{if(calls.incrementAndGet()==2&&fail.get())throw new IllegalStateException("synthetic interrupted batch");return call.callRealMethod();}).when(ledger).publishPrepared(any());
        assertThrows(IllegalStateException.class,this::publish);assertEquals(10,count("qc_fact_occurrence"));assertEquals(1,records("consistency_publication_batch").size());assertNotPublished();
        fail.set(false);map(report.get("coverage")).put("elapsedMillis",999);publish();
        assertEquals(10,calls.get());assertEquals(81,count("qc_fact_occurrence"));assertEquals(81,records("fact_usage").size());assertEquals(9,records("consistency_publication_batch").size());
        assertEquals(1,records("fact_snapshot").size());
    }
    @Test void changedFactsCannotReuseEarlierBatchReceipts()throws Exception {
        AtomicInteger calls=new AtomicInteger();doAnswer(call->{if(calls.incrementAndGet()==2)throw new IllegalStateException("stop");return call.callRealMethod();}).when(ledger).publishPrepared(any());
        assertThrows(IllegalStateException.class,this::publish);maps(report.get("occurrences")).get(0).put("subject","THIRD_PARTY");
        assertEquals("CONSISTENCY_BATCH_PLAN_MISMATCH",assertThrows(IllegalStateException.class,this::publish).getMessage());assertEquals(10,count("qc_fact_occurrence"));assertNotPublished();
    }
    @Test void changedSourceReferenceCannotReuseEarlierBatchReceipts()throws Exception {
        AtomicInteger calls=new AtomicInteger();doAnswer(call->{if(calls.incrementAndGet()==2)throw new IllegalStateException("stop");return call.callRealMethod();}).when(ledger).publishPrepared(any());
        assertThrows(IllegalStateException.class,this::publish);
        Map<String,Object> first=maps(report.get("occurrences")).get(0);Evidence old=(Evidence)first.get("evidence");first.put("evidence",new Evidence(old.documentId(),old.documentName(),old.page(),old.locationType(),old.quote(),"changed-anchor",old.confidence()));
        assertEquals("CONSISTENCY_BATCH_PLAN_MISMATCH",assertThrows(IllegalStateException.class,this::publish).getMessage());assertEquals(10,count("qc_fact_occurrence"));assertNotPublished();
    }
    @Test void lostOwnerAfterPayloadPreparationCannotFreezeOrSaveFindings()throws Exception {
        prepare(1); // This test exercises the final fence; the multiple-batch renewal test covers volume separately.
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call->{Object prepared=call.callRealMethod();entered.countDown();await(release);return prepared;}).when(store).prepareRecord(eq("finding"),anyString(),anyString(),anyString(),any());
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {Future<?> publication=pool.submit(this::publish);awaitEntered(entered,publication);assertEquals(1,store.mapper().recoverTasks(Long.MAX_VALUE,BidService.now()));assertEquals(1,store.mapper().claimTask(task.id,BidService.now(),"new-owner",System.currentTimeMillis()+300000));release.countDown();assertInstanceOf(TaskLeaseLostException.class,failure(publication));assertNotPublished();verify(ledger,never()).freezePreparedSnapshot(any());}
        finally{release.countDown();pool.shutdownNow();}
    }
    @Test void expiredOwnerCannotPublishEvenFirstBatch()throws Exception {
        sql("UPDATE qc_task SET lease_until=0 WHERE id='task'");assertThrows(TaskLeaseLostException.class,this::publish);assertEquals(0,count("qc_fact_occurrence"));assertNotPublished();
    }
    @Test void missingUsageAfterPreparationCannotPublishSnapshotEvenWithEveryReceipt()throws Exception {
        doAnswer(call->{Object prepared=call.callRealMethod();store.mapper().deleteChildren("fact_usage",run);return prepared;}).when(ledger).prepareSnapshot(any(),anyString(),anyList());
        assertEquals("FACT_PUBLICATION_CHANGED",assertThrows(IllegalStateException.class,this::publish).getMessage());assertEquals(9,records("consistency_publication_batch").size());assertNotPublished();
    }
    @Test void sameCountButChangedUsageBindingCannotPassFinalIntegrityCheck()throws Exception {
        doAnswer(call->{Object prepared=call.callRealMethod();java.util.List<java.util.Map<java.lang.String, java.lang.Object>> uses=records("fact_usage");java.util.Map<java.lang.String, java.lang.Object> first=uses.get(0);first.put("occurrenceId",uses.get(1).get("occurrenceId"));store.save("fact_usage",string(first,"id"),project,run,first);return prepared;}).when(ledger).prepareSnapshot(any(),anyString(),anyList());
        assertEquals("FACT_PUBLICATION_CHANGED",assertThrows(IllegalStateException.class,this::publish).getMessage());assertEquals(81,records("fact_usage").size());assertNotPublished();
    }
    @Test void earlierFrozenSnapshotAndManualReviewAreNotChanged()throws Exception {
        java.util.Map<java.lang.String, java.lang.Object> priorReport=store.map(store.encode(report));ConsistencyRunSupport.publish(store,ledger,"prior-run",document,"prior-engine",priorReport);
        String snapshotId=string(priorReport,"ledgerSnapshotId"),snapshotBefore=store.mapper().get(snapshotId).body;
        store.save("review","prior-review",project,"prior-run",values("status","WARNING","note","keep manual decision"));String reviewBefore=store.mapper().get("prior-review").body;
        publish();assertEquals(snapshotBefore,store.mapper().get(snapshotId).body);assertEquals(reviewBefore,store.mapper().get("prior-review").body);
    }
    @Test void invalidCoreSourceCannotSilentlyDisappearFromCompleteSnapshot()throws Exception {
        doAnswer(call->{sql("UPDATE qc_evidence SET quote_text='not in document'");return call.callRealMethod();}).when(ledger).prepareSnapshot(any(),anyString(),anyList());
        assertEquals("FACT_PUBLICATION_INCOMPLETE",assertThrows(IllegalStateException.class,this::publish).getMessage());assertNotPublished();
    }
    @Test void legitimateDuplicateFactsUseSetsAndDoNotRequireDistinctOccurrencePerInput()throws Exception {
        prepare(1);Map<String,Object> occurrence=maps(report.get("occurrences")).get(0);report.put("occurrences",cn.bidcheck.compat.Java8Api.listOf(occurrence, occurrence));publish();
        assertEquals(1,count("qc_fact_occurrence"));assertEquals(1,records("fact_usage").size());assertEquals(1,((Number)map(records("fact_snapshot").get(0).get("summary")).get("occurrences")).intValue());
        assertEquals(2,map(report.get("publication")).get("factsSaved"));
    }
    @Test void lateInvalidSourceIsRejectedBeforeAnyBatchCommit()throws Exception {
        maps(report.get("occurrences")).get(80).put("evidence",new Evidence(document.id(),document.name(),1,"PAGE","fabricated quote","",1));
        assertThrows(IllegalStateException.class,this::publish);assertEquals(0,count("qc_fact_occurrence"));assertTrue(records("consistency_publication_batch").isEmpty());assertNotPublished();
    }
    @Test void preparationInsideAnOuterTransactionIsRejectedInsteadOfRetainingLocks() {
        assertEquals("CONSISTENCY_PUBLICATION_REQUIRES_NO_TRANSACTION",assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->publish())).getMessage());
    }
    @Test void emptyScanCanFreezeAnEmptyLedgerWithoutInventingFacts()throws Exception {
        prepare(0);publish();assertEquals(0,count("qc_fact_occurrence"));assertEquals(0,map(report.get("publication")).get("batchCount"));assertEquals(1,records("consistency_report").size());
    }
    @Test void fullyReusableImmutableFactsUseTheLargerPlanWithoutDroppingRunUsageOrOrigins()throws Exception {
        Map<String,Object> prior=store.map(store.encode(report));ConsistencyRunSupport.publish(store,ledger,"prior-reuse",document,engine,prior);
        clearInvocations(ledger,store);publish();Map<String,Object> publication=map(report.get("publication")),preload=map(publication.get("reusePreload"));
        assertEquals("IMMUTABLE_REUSE",publication.get("batchPlanMode"));assertEquals(40,publication.get("maxBatchFacts"));assertEquals(128,publication.get("maxBatchSources"));assertEquals(3,publication.get("batchCount"));
        assertEquals(81,preload.get("requested"));assertEquals(81,preload.get("reusable"));assertEquals(true,preload.get("allReusable"));
        verify(ledger,times(3)).publishPrepared(any());verify(store,times(1)).getMap(eq("fact_lookup_state"),anyString());
        assertEquals(81,count("qc_fact_occurrence"));assertEquals(81,records("fact_usage").size());
        assertEquals(81,store.maps("fact_origin",project,null).stream().filter(row->run.equals(row.get("runId"))).count());
        assertEquals(1,records("fact_snapshot").size());
    }
    @Test void aColdQueryProjectionCannotStartHistoricalMigrationInsideReusePublication()throws Exception {
        Map<String,Object> prior=store.map(store.encode(report));ConsistencyRunSupport.publish(store,ledger,"prior-cold-index",document,engine,prior);
        sql("DELETE FROM qc_record WHERE kind IN ('fact_lookup_state','fact_lookup_ref','field_lookup_ref','fact_lookup_item','fact_lookup_revision')");
        clearInvocations(ledger,store);
        doThrow(new IllegalStateException("historical migration entered short transaction")).when(ledger).migrateLookupIndex(any(FactLedgerService.PreparedManifest.class),anyInt());
        publish();
        verify(ledger,never()).migrateLookupIndex(any(FactLedgerService.PreparedManifest.class),anyInt());
        assertEquals("IMMUTABLE_REUSE",map(report.get("publication")).get("batchPlanMode"));
        assertEquals(81,records("fact_usage").size());assertEquals(1,records("fact_snapshot").size());
        assertEquals(1,records("consistency_report").size());assertTrue(records("fact_lookup_item").isEmpty());
    }
    @Test void aPartiallyCommittedReusePlanResumesTheSameDurableBatchPlan()throws Exception {
        Map<String,Object> prior=store.map(store.encode(report));ConsistencyRunSupport.publish(store,ledger,"prior-reuse",document,engine,prior);
        AtomicInteger calls=new AtomicInteger();AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(call->{if(calls.incrementAndGet()==2&&fail.get())throw new IllegalStateException("synthetic interrupted reuse batch");return call.callRealMethod();}).when(ledger).publishPrepared(any());
        assertThrows(IllegalStateException.class,this::publish);assertEquals(1,records("consistency_publication_batch").size());assertEquals(40,records("fact_usage").size());assertNotPublished();
        fail.set(false);publish();assertEquals(4,calls.get());assertEquals(3,records("consistency_publication_batch").size());assertEquals(81,records("fact_usage").size());
        assertEquals("IMMUTABLE_REUSE",map(report.get("publication")).get("batchPlanMode"));assertEquals("RESUME_REUSE_PLAN",map(report.get("publication")).get("batchPlanSelectionReason"));
    }
    @Test void invalidExistingSourceCannotQualifyForTheReusePlanOrBypassBatchValidation()throws Exception {
        Map<String,Object> prior=store.map(store.encode(report));ConsistencyRunSupport.publish(store,ledger,"prior-reuse",document,engine,prior);sql("UPDATE qc_evidence SET quote_text='not in current original'");
        assertEquals("一致性事实未通过原文验证，不能发布报告",assertThrows(IllegalStateException.class,this::publish).getMessage());assertTrue(records("consistency_publication_batch").isEmpty());assertTrue(records("fact_usage").isEmpty());assertNotPublished();
    }
    @Test void terminalMigrationCacheStartsOnlyAfterCommitAndCacheHitsReportNoNewWork() {
        FactLedgerService.PreparedManifest manifest=ledger.prepareManifest(project,engine,cn.bidcheck.compat.Java8Api.listOf(document));AtomicReference<Map<String,Object>> rolledBack=new AtomicReference<>();
        tx.executeWithoutResult(status->{rolledBack.set(ledger.migrateLookupIndex(manifest,50));status.setRollbackOnly();});assertEquals(false,rolledBack.get().get("terminalCacheHit"));
        Map<String,Object> committed=tx.execute(status->ledger.migrateLookupIndex(manifest,50));assertEquals(false,committed.get("terminalCacheHit"));
        Map<String,Object> cached=tx.execute(status->ledger.migrateLookupIndex(manifest,50));assertEquals(true,cached.get("terminalCacheHit"));
        assertEquals(0,cached.get("processed"));assertEquals(0,cached.get("writes"));assertEquals(committed.get("documents"),cached.get("documents"));
    }
    private List<Map<String,Object>> boundedBatches(){return publicationBatches(store,run,cn.bidcheck.compat.Java8Api.listOf(document),report,ConsistencyPublication.MAX_BATCH_FACTS,ConsistencyPublication.MAX_BATCH_SOURCES,ConsistencyPublication.TARGET_BATCH_BYTES);}
    private int bytes(Object value){return store.encode(value).getBytes(StandardCharsets.UTF_8).length;}
    @Test void sourceLimitSplitsBeforeFactLimitWithoutDroppingOrRebindingFacts() {
        prepare(12);
        for(Map<String,Object> occurrence:maps(report.get("occurrences"))) {
            Evidence source=(Evidence)occurrence.get("evidence");
            occurrence.put("contextEvidences",cn.bidcheck.compat.Java8Api.listOf(new Evidence(source.documentId(),source.documentName(),1,"PAGE",source.quote(),"context-a",1), new Evidence(source.documentId(),source.documentName(),1,"PAGE",source.quote(),"context-b",1), new Evidence(source.documentId(),source.documentName(),1,"PAGE",source.quote(),"context-c",1)));
        }
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> batches=boundedBatches();assertEquals(cn.bidcheck.compat.Java8Api.listOf(8, 4),cn.bidcheck.compat.Java8Api.toList(batches.stream().map(b->maps(b.get("facts")).size())));
        assertEquals(cn.bidcheck.compat.Java8Api.toList(maps(report.get("occurrences")).stream().map(o->o.get("id"))),cn.bidcheck.compat.Java8Api.toList(batches.stream().flatMap(b->maps(b.get("facts")).stream()).map(o->o.get("id"))));
        for(java.util.Map<java.lang.String, java.lang.Object> batch:batches) {
            java.util.List<java.util.Map<java.lang.String, java.lang.Object>> sources=maps(batch.get("sources"));assertTrue(sources.size()<=32);
            Map<Object,Map<String,Object>> byId=new HashMap<>();sources.forEach(s->assertNull(byId.put(s.get("id"),s)));
            for(java.util.Map<java.lang.String, java.lang.Object> fact:maps(batch.get("facts")))for(Object sourceId:(List<?>)fact.get("sourceIds")) {
                Evidence source=(Evidence)byId.get(sourceId).get("evidence");assertTrue(source.quote().contains(string(fact,"rawValue")));
            }
        }
        assertEquals(1,publicationBatches(store,run,cn.bidcheck.compat.Java8Api.listOf(document),report).size()); // Legacy policy remains 40 / 128.
    }
    @Test void utf8ByteTargetSplitsLargeAttributesAndPreservesTheirEntireText()throws Exception {
        prepare(3);String reason=cn.bidcheck.compat.Java8Api.repeat("归", 24000);
        maps(report.get("occurrences")).forEach(o->o.put("scopeReason",reason));java.util.List<java.util.Map<java.lang.String, java.lang.Object>> batches=boundedBatches();
        assertEquals(3,batches.size());
        for(java.util.Map<java.lang.String, java.lang.Object> batch:batches){assertTrue(bytes(batch)<=ConsistencyPublication.TARGET_BATCH_BYTES);assertEquals(reason,map(maps(batch.get("facts")).get(0).get("attributes")).get("scopeReason"));}
        publish();assertEquals(3,count("qc_fact_occurrence"));assertEquals(3,map(report.get("publication")).get("batchCount"));
    }
    @Test void oversizedSingleFactIsIsolatedAndAllItsSourcesRemainPublishable()throws Exception {
        prepare(2);String quote=cn.bidcheck.compat.Java8Api.repeat("表", 9000);Page page=document.pages().get(0);
        document=new Document(document.id(),project,"BID",document.name(),1,document.sha256(),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,page.text()+"\n"+quote,"PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf());
        List<Evidence> contexts=new ArrayList<>();for(int i=0;i<6;i++)contexts.add(new Evidence(document.id(),document.name(),1,"PAGE",quote,"large-context-"+i,1));
        maps(report.get("occurrences")).get(0).put("contextEvidences",contexts);
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> batches=boundedBatches();assertEquals(2,batches.size());assertTrue(bytes(batches.get(0))>ConsistencyPublication.TARGET_BATCH_BYTES);
        assertEquals(1,maps(batches.get(0).get("facts")).size());assertEquals(7,maps(batches.get(0).get("sources")).size());
        publish();assertEquals(2,count("qc_fact_occurrence"));assertEquals(8,count("qc_fact_evidence"));assertEquals(1,records("fact_snapshot").size());
    }
    @Test void finalReceiptVerificationUsesOneScopedReadWithoutPerReceiptQueries() {
        prepare(21);
        doAnswer(call->{Object prepared=call.callRealMethod();clearInvocations(store.mapper());return prepared;}).when(ledger).prepareSnapshot(any(),anyString(),anyList());
        publish();verify(store.mapper(),times(1)).list("consistency_publication_batch",project,run);
        for(int i=0;i<3;i++)verify(store.mapper(),never()).get(id("consistency-publication:"+run+":"+i));
    }
    @Test void missingReceiptAfterPreparationBlocksFinalPublication() {
        prepare(1);doAnswer(call->{Object prepared=call.callRealMethod();store.mapper().deleteChildren("consistency_publication_batch",run);return prepared;}).when(ledger).prepareSnapshot(any(),anyString(),anyList());
        assertEquals("CONSISTENCY_BATCH_SET_CHANGED",assertThrows(IllegalStateException.class,this::publish).getMessage());assertNotPublished();
    }
    @Test void extraReceiptAfterPreparationBlocksFinalPublication() {
        prepare(1);doAnswer(call->{Object prepared=call.callRealMethod();java.util.Map<java.lang.String, java.lang.Object> receipt=records("consistency_publication_batch").get(0);store.save("consistency_publication_batch","unexpected",project,run,receipt);return prepared;}).when(ledger).prepareSnapshot(any(),anyString(),anyList());
        assertEquals("CONSISTENCY_BATCH_SET_CHANGED",assertThrows(IllegalStateException.class,this::publish).getMessage());assertNotPublished();
    }
    @Test void sameCountButReplacedReceiptIdCannotPassSetVerification() {
        prepare(1);doAnswer(call->{Object prepared=call.callRealMethod();java.util.Map<java.lang.String, java.lang.Object> receipt=records("consistency_publication_batch").get(0);store.mapper().deleteChildren("consistency_publication_batch",run);store.save("consistency_publication_batch","replacement",project,run,receipt);return prepared;}).when(ledger).prepareSnapshot(any(),anyString(),anyList());
        assertEquals("CONSISTENCY_BATCH_SET_CHANGED",assertThrows(IllegalStateException.class,this::publish).getMessage());assertNotPublished();
    }
    @Test void changedReceiptResultCannotPassFullDigestVerification() {
        prepare(1);doAnswer(call->{Object prepared=call.callRealMethod();java.util.Map<java.lang.String, java.lang.Object> receipt=records("consistency_publication_batch").get(0);map(receipt.get("result")).put("usageIds",cn.bidcheck.compat.Java8Api.listOf("changed"));store.save("consistency_publication_batch",string(receipt,"id"),project,run,receipt);return prepared;}).when(ledger).prepareSnapshot(any(),anyString(),anyList());
        assertEquals("CONSISTENCY_BATCH_CHANGED",assertThrows(IllegalStateException.class,this::publish).getMessage());assertNotPublished();
    }
    @Test void failedBatchRollsBackItsFactsAndUsagesBeforeRetry()throws Exception {
        prepare(21);AtomicInteger calls=new AtomicInteger();
        doAnswer(call->{Object saved=call.callRealMethod();if(calls.incrementAndGet()==2)throw new IllegalStateException("synthetic post-write failure");return saved;}).when(ledger).publishPrepared(any());
        assertThrows(IllegalStateException.class,this::publish);assertEquals(10,count("qc_fact_occurrence"));assertEquals(10,records("fact_usage").size());assertEquals(1,records("consistency_publication_batch").size());assertNotPublished();
        publish();assertEquals(21,count("qc_fact_occurrence"));assertEquals(21,records("fact_usage").size());assertEquals(3,records("consistency_publication_batch").size());
    }
    @Test void snapshotReportAndFindingsAreEncodedOutsideAnyTransaction() {
        prepare(1);AtomicInteger payloads=new AtomicInteger();
        doAnswer(call->{Object value=call.getArgument(0);Map<String,Object> map=map(value);
            if(value instanceof Finding||Boolean.TRUE.equals(map.get("frozen"))||map.containsKey("publication")) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());payloads.incrementAndGet();
            }
            return call.callRealMethod();
        }).when(store).encode(any());
        publish();assertEquals(3,payloads.get());assertEquals(1,records("consistency_report").size());
    }
    @Test void finalWriteFailureRollsBackSnapshotFindingsAndReportThenResumes()throws Exception {
        prepare(1);AtomicBoolean fail=new AtomicBoolean(true);RecordMapper mapper=store.mapper();
        doAnswer(call->{Stored row=call.getArgument(0);if(fail.get()&&"consistency_report".equals(row.kind))throw new org.springframework.dao.QueryTimeoutException("synthetic final write timeout");return call.callRealMethod();}).when(mapper).save(any());
        assertThrows(org.springframework.dao.QueryTimeoutException.class,this::publish);assertNotPublished();assertEquals(1,count("qc_fact_occurrence"));assertFalse(report.containsKey("publication"));
        fail.set(false);publish();assertEquals(1,records("fact_snapshot").size());assertEquals(1,records("finding").size());assertEquals(1,records("consistency_report").size());
    }
    @Test void previouslyFrozenBodyCannotChangeBetweenPreparationAndFinalFence() {
        prepare(1);publish();String reportBefore=store.mapper().get(reportId(run)).body;
        doAnswer(call->{FactLedgerService.PreparedSnapshot prepared=(FactLedgerService.PreparedSnapshot)call.callRealMethod();String id=string(prepared.metadata(),"id");java.util.Map<java.lang.String, java.lang.Object> snapshot=store.getMap("fact_snapshot",id);snapshot.put("unexpectedChange",true);store.save("fact_snapshot",id,project,run,snapshot);return prepared;}).when(ledger).prepareSnapshot(any(),anyString(),anyList());
        assertEquals("FACT_RUN_SNAPSHOT_FROZEN",assertThrows(IllegalStateException.class,this::publish).getMessage());
        assertEquals(reportBefore,store.mapper().get(reportId(run)).body);assertTrue(Boolean.TRUE.equals(records("fact_snapshot").get(0).get("unexpectedChange")));
    }
    /** Expire the real Spring transaction resource, so the next SQL creates the production failure shape. */
    private ConnectionHolder expireCurrentTransaction()throws InterruptedException {
        ConnectionHolder holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource);assertNotNull(holder);
        holder.setTimeoutInMillis(1);Thread.sleep(5);return holder;
    }
    private boolean hasCause(Throwable failure,Class<? extends Throwable> type) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(type.isInstance(cause))return true;
        return false;
    }
    @Test void realSpringDeadlineAfterWritesRollsBackBeforeANewFencedBatchAttempt()throws Exception {
        prepare(21);AtomicInteger calls=new AtomicInteger();List<Object> holders=new ArrayList<>();
        doAnswer(call->{holders.add(TransactionSynchronizationManager.getResource(dataSource));Object value=call.callRealMethod();
            if(calls.incrementAndGet()==1)expireCurrentTransaction();return value;}).when(ledger).publishPrepared(any());
        publish();assertEquals(4,calls.get());assertNotSame(holders.get(0),holders.get(1));
        assertEquals(21,count("qc_fact_occurrence"));assertEquals(21,records("fact_usage").size());assertEquals(21,store.maps("fact_origin",project,null).size());
        assertEquals(3,records("consistency_publication_batch").size());assertEquals(1,records("consistency_report").size());
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> timings=maps(map(report.get("publication")).get("batchTransactionTimings"));assertEquals(3,timings.size());
        java.util.Map<java.lang.String, java.lang.Object> first=timings.get(0);assertEquals(1,first.get("retryCount"));assertEquals(true,first.get("committed"));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> attempts=maps(first.get("attempts"));assertEquals(2,attempts.size());
        assertEquals(org.springframework.transaction.TransactionTimedOutException.class.getName(),attempts.get(0).get("transientCauseClass"));
        assertEquals(true,attempts.get(0).get("retryScheduled"));assertEquals("COMMITTED",attempts.get(1).get("outcome"));
        assertTrue(((Number)first.get("transactionElapsedMillis")).longValue()>=5);
    }
    @Test void threeRealDeadlineFailuresStopAndPersistOnlyAFencedDiagnostic()throws Exception {
        prepare(1);AtomicInteger calls=new AtomicInteger();
        doAnswer(call->{Object value=call.callRealMethod();calls.incrementAndGet();expireCurrentTransaction();return value;}).when(ledger).publishPrepared(any());
        RuntimeException failure=assertThrows(RuntimeException.class,this::publish);
        assertTrue(hasCause(failure,org.springframework.transaction.TransactionTimedOutException.class));assertEquals(3,calls.get());
        assertEquals(0,count("qc_fact_occurrence"));assertTrue(records("fact_usage").isEmpty());assertTrue(records("consistency_publication_batch").isEmpty());assertNotPublished();
        java.util.Map<java.lang.String, java.lang.Object> diagnostic=records(ConsistencyPublication.DIAGNOSTIC_KIND).get(0);assertEquals("FAILED",diagnostic.get("status"));
        assertEquals(0,((Number)diagnostic.get("committedBatchCount")).intValue());assertEquals(0,((Number)diagnostic.get("committedInputFacts")).intValue());
        java.util.Map<java.lang.String, java.lang.Object> timing=maps(diagnostic.get("batchTransactionTimings")).get(0);assertEquals(false,timing.get("committed"));assertEquals(2,((Number)timing.get("retryCount")).intValue());
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> attempts=maps(timing.get("attempts"));assertEquals(3,attempts.size());assertEquals(false,attempts.get(2).get("retryScheduled"));
    }
    @Test void sourceAndPlanFailuresAreNeverRetried()throws Exception {
        prepare(1);AtomicInteger calls=new AtomicInteger();IllegalArgumentException invalid=new IllegalArgumentException("FACT_EXISTING_SCOPE_INVALID");
        doAnswer(call->{calls.incrementAndGet();throw invalid;}).when(ledger).publishPrepared(any());
        assertSame(invalid,assertThrows(IllegalArgumentException.class,this::publish));assertEquals(1,calls.get());assertEquals(0,count("qc_fact_occurrence"));assertNotPublished();
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> attempts=maps(maps(records(ConsistencyPublication.DIAGNOSTIC_KIND).get(0).get("batchTransactionTimings")).get(0).get("attempts"));
        assertEquals(1,attempts.size());assertEquals(false,attempts.get(0).get("retryScheduled"));
    }
    @Test void aLostFenceStopsTheRetryAndCannotWriteADiagnosticUnderTheOldOwner()throws Exception {
        prepare(1);AtomicInteger calls=new AtomicInteger();
        doAnswer(call->{calls.incrementAndGet();task.leaseOwner="different-owner";throw new org.springframework.dao.CannotAcquireLockException("synthetic transient lock");}).when(ledger).publishPrepared(any());
        assertThrows(TaskLeaseLostException.class,this::publish);assertEquals(1,calls.get());assertEquals(0,count("qc_fact_occurrence"));assertNotPublished();
        assertTrue(records(ConsistencyPublication.DIAGNOSTIC_KIND).isEmpty());assertEquals("owner",store.mapper().task(task.id).leaseOwner);
    }
    @Test void cancellationPreventsRetryAndDiagnosticWrites()throws Exception {
        prepare(1);AtomicInteger calls=new AtomicInteger();
        doAnswer(call->{calls.incrementAndGet();Thread.currentThread().interrupt();throw new org.springframework.dao.QueryTimeoutException("synthetic interrupted request");}).when(ledger).publishPrepared(any());
        try {assertThrows(org.springframework.dao.QueryTimeoutException.class,this::publish);assertEquals(1,calls.get());}
        finally {Thread.interrupted();}
        assertEquals(0,count("qc_fact_occurrence"));assertNotPublished();assertTrue(records(ConsistencyPublication.DIAGNOSTIC_KIND).isEmpty());
    }
    @Test void diagnosticWriteFailureCannotMaskTheOriginalSourceFailure() {
        prepare(1);IllegalArgumentException invalid=new IllegalArgumentException("FACT_SOURCE_CHANGED");
        doThrow(invalid).when(ledger).publishPrepared(any());
        RecordMapper mapper=store.mapper();
        doAnswer(call->{Stored row=call.getArgument(0);if(ConsistencyPublication.DIAGNOSTIC_KIND.equals(row.kind))throw new IllegalStateException("diagnostic store unavailable");return call.callRealMethod();}).when(mapper).save(any());
        assertSame(invalid,assertThrows(IllegalArgumentException.class,this::publish));assertEquals(1,invalid.getSuppressed().length);assertNotPublished();
    }
}
