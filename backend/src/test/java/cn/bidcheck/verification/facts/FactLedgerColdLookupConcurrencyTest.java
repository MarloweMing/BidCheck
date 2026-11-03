package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static cn.bidcheck.verification.facts.LedgerValues.*;
import static org.junit.jupiter.api.Assertions.*;

class FactLedgerColdLookupConcurrencyTest {
    @Test void concurrentColdLookupsShareLegacyHintHydrationAndSeeLaterIndexMembers()throws Exception {
        cn.bidcheck.verification.facts.FactLedgerServiceTest fixture=new FactLedgerServiceTest();Document doc=fixture.doc("d","合成甲 类别：一级、二级");java.util.List<cn.bidcheck.domain.Domain.Document> docs=cn.bidcheck.compat.Java8Api.listOf(doc);ConcurrentStore store=new ConcurrentStore();FactLedgerService ledger=new FactLedgerService(store);
        for(int i=0;i<6;i++){java.util.Map<java.lang.String, java.lang.Object> batch=fixture.batch(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(fixture.source(doc,"S")));batch.put("operationIdentity","cold-"+i);batch.put("fieldCoverage",cn.bidcheck.compat.Java8Api.listOf(fixture.fieldReceipt("field-"+i,"READ_NOT_FOUND",cn.bidcheck.compat.Java8Api.listOf())));ledger.publish("project","run","cp","engine",docs,batch);}
        store.stripSourceIndex();cn.bidcheck.verification.facts.FactLedgerService.PreparedManifest manifest=ledger.prepareManifest("project","engine",docs);store.readBarrier=new CyclicBarrier(3);store.track=true;
        ExecutorService pool=Executors.newFixedThreadPool(3);List<Future<List<Map<String,Object>>>> futures=new ArrayList<>();
        try{
            for(int i=0;i<3;i++)futures.add(pool.submit(()->ledger.lookup(manifest,cn.bidcheck.compat.Java8Api.mapOf("sourceIds", cn.bidcheck.compat.Java8Api.listOf("S"), "includeFieldCoverage", true))));
            assertTrue(store.firstLegacyRead.await(10,TimeUnit.SECONDS));store.releaseLegacyRead.countDown();
            for(java.util.concurrent.Future<java.util.List<java.util.Map<java.lang.String, java.lang.Object>>> future:futures){java.util.List<java.util.Map<java.lang.String, java.lang.Object>> rows=future.get(20,TimeUnit.SECONDS);assertEquals(6,rows.size());assertTrue(rows.stream().allMatch(r->"FIELD_COVERAGE".equals(r.get("recordType"))));}
            assertEquals(1,store.batchLoads.get(),"Cold initialization is single-flight for this manifest, not repeated by all three checkpoints");
            assertEquals(6,store.batchIdsLoaded.get());store.readBarrier=null;store.track=false;
            java.util.Map<java.lang.String, java.lang.Object> later=fixture.batch(cn.bidcheck.compat.Java8Api.listOf(fixture.fact("new","二级","S")),cn.bidcheck.compat.Java8Api.listOf(fixture.source(doc,"S")));later.put("fieldCoverage",cn.bidcheck.compat.Java8Api.listOf(fixture.fieldReceipt("category","EXTRACTED",cn.bidcheck.compat.Java8Api.listOf("new"))));ledger.publish("project","run","new-cp","engine",docs,later);
            java.util.List<java.util.Map<java.lang.String, java.lang.Object>> updated=ledger.lookup(manifest,cn.bidcheck.compat.Java8Api.mapOf("sourceIds", cn.bidcheck.compat.Java8Api.listOf("S"), "includeFieldCoverage", true));assertEquals(1,updated.stream().filter(r->r.containsKey("fact")).count());assertEquals(7,updated.stream().filter(r->"FIELD_COVERAGE".equals(r.get("recordType"))).count());
        }finally{store.releaseLegacyRead.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    static final class ConcurrentStore extends Store {
        @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Row {
        private final String kind;
        private final String project;
        private final String parent;
        private final Map<String,Object> value;
        @com.fasterxml.jackson.annotation.JsonCreator
        Row(@com.fasterxml.jackson.annotation.JsonProperty("kind") String kind, @com.fasterxml.jackson.annotation.JsonProperty("project") String project, @com.fasterxml.jackson.annotation.JsonProperty("parent") String parent, @com.fasterxml.jackson.annotation.JsonProperty("value") Map<String,Object> value) { this.kind = kind; this.project = project; this.parent = parent; this.value = value; }
        public String kind() { return kind; }
        public String project() { return project; }
        public String parent() { return parent; }
        public Map<String,Object> value() { return value; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Row that = (Row) other; return java.util.Objects.equals(this.kind, that.kind) && java.util.Objects.equals(this.project, that.project) && java.util.Objects.equals(this.parent, that.parent) && java.util.Objects.equals(this.value, that.value); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.kind); hash = 31 * hash + java.util.Objects.hashCode(this.project); hash = 31 * hash + java.util.Objects.hashCode(this.parent); hash = 31 * hash + java.util.Objects.hashCode(this.value); return hash; }
        @Override public String toString() { return "Row[" + "kind=" + kind + ", project=" + project + ", parent=" + parent + ", value=" + value + "]"; }
}
        private final Map<String,Row> rows=new LinkedHashMap<>();volatile CyclicBarrier readBarrier;volatile boolean track;
        final AtomicInteger batchLoads=new AtomicInteger(),batchIdsLoaded=new AtomicInteger();final CountDownLatch firstLegacyRead=new CountDownLatch(1),releaseLegacyRead=new CountDownLatch(1);
        ConcurrentStore(){super(null,new ObjectMapper());}
        @Override public synchronized void save(String kind,String id,String project,String parent,Object value){rows.put(id,new Row(kind,project,parent,LedgerValues.map(json.convertValue(value,Map.class))));}
        @Override public synchronized Map<String,Object> getMap(String kind,String id){Row row=rows.get(id);if(row==null||!kind.equals(row.kind()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return copy(row.value());}
        @Override public List<Map<String,Object>> maps(String kind,String project,String parent){List<Map<String,Object>> values;
            synchronized(this){values=cn.bidcheck.compat.Java8Api.toList(rows.values().stream().filter(r->kind.equals(r.kind())&&(project==null||project.equals(r.project()))&&(parent==null||parent.equals(r.parent()))).map(r->copy(r.value())));}
            CyclicBarrier barrier=readBarrier;if("fact_index".equals(kind)&&barrier!=null)try{barrier.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}return values;
        }
        @Override public Map<String,Map<String,Object>> mapsByIds(String kind,String project,Collection<String> ids){
            if(track&&"fact_batch".equals(kind)&&!ids.isEmpty()){batchLoads.incrementAndGet();batchIdsLoaded.addAndGet(ids.size());firstLegacyRead.countDown();try{if(!releaseLegacyRead.await(10,TimeUnit.SECONDS))throw new IllegalStateException("TEST_LEGACY_READ_WAIT_TIMEOUT");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
            return super.mapsByIds(kind,project,ids);
        }
        synchronized void stripSourceIndex(){for(Row row:rows.values())if("fact_index".equals(row.kind())){row.value().remove("sourceIds");row.value().remove("sourceIndexVersion");}}
    }
}
