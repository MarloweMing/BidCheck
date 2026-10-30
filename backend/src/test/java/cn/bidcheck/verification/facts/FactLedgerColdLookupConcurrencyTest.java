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
        var fixture=new FactLedgerServiceTest();Document doc=fixture.doc("d","合成甲 类别：一级、二级");var docs=List.of(doc);ConcurrentStore store=new ConcurrentStore();FactLedgerService ledger=new FactLedgerService(store);
        for(int i=0;i<6;i++){var batch=fixture.batch(List.of(),List.of(fixture.source(doc,"S")));batch.put("operationIdentity","cold-"+i);batch.put("fieldCoverage",List.of(fixture.fieldReceipt("field-"+i,"READ_NOT_FOUND",List.of())));ledger.publish("project","run","cp","engine",docs,batch);}
        store.stripSourceIndex();var manifest=ledger.prepareManifest("project","engine",docs);store.readBarrier=new CyclicBarrier(3);store.track=true;
        ExecutorService pool=Executors.newFixedThreadPool(3);List<Future<List<Map<String,Object>>>> futures=new ArrayList<>();
        try{
            for(int i=0;i<3;i++)futures.add(pool.submit(()->ledger.lookup(manifest,Map.of("sourceIds",List.of("S"),"includeFieldCoverage",true))));
            assertTrue(store.firstLegacyRead.await(10,TimeUnit.SECONDS));store.releaseLegacyRead.countDown();
            for(var future:futures){var rows=future.get(20,TimeUnit.SECONDS);assertEquals(6,rows.size());assertTrue(rows.stream().allMatch(r->"FIELD_COVERAGE".equals(r.get("recordType"))));}
            assertEquals(1,store.batchLoads.get(),"Cold initialization is single-flight for this manifest, not repeated by all three checkpoints");
            assertEquals(6,store.batchIdsLoaded.get());store.readBarrier=null;store.track=false;
            var later=fixture.batch(List.of(fixture.fact("new","二级","S")),List.of(fixture.source(doc,"S")));later.put("fieldCoverage",List.of(fixture.fieldReceipt("category","EXTRACTED",List.of("new"))));ledger.publish("project","run","new-cp","engine",docs,later);
            var updated=ledger.lookup(manifest,Map.of("sourceIds",List.of("S"),"includeFieldCoverage",true));assertEquals(1,updated.stream().filter(r->r.containsKey("fact")).count());assertEquals(7,updated.stream().filter(r->"FIELD_COVERAGE".equals(r.get("recordType"))).count());
        }finally{store.releaseLegacyRead.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    static final class ConcurrentStore extends Store {
        record Row(String kind,String project,String parent,Map<String,Object> value) {}
        private final Map<String,Row> rows=new LinkedHashMap<>();volatile CyclicBarrier readBarrier;volatile boolean track;
        final AtomicInteger batchLoads=new AtomicInteger(),batchIdsLoaded=new AtomicInteger();final CountDownLatch firstLegacyRead=new CountDownLatch(1),releaseLegacyRead=new CountDownLatch(1);
        ConcurrentStore(){super(null,new ObjectMapper());}
        @Override public synchronized void save(String kind,String id,String project,String parent,Object value){rows.put(id,new Row(kind,project,parent,LedgerValues.map(json.convertValue(value,Map.class))));}
        @Override public synchronized Map<String,Object> getMap(String kind,String id){Row row=rows.get(id);if(row==null||!kind.equals(row.kind()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return copy(row.value());}
        @Override public List<Map<String,Object>> maps(String kind,String project,String parent){List<Map<String,Object>> values;
            synchronized(this){values=rows.values().stream().filter(r->kind.equals(r.kind())&&(project==null||project.equals(r.project()))&&(parent==null||parent.equals(r.parent()))).map(r->copy(r.value())).toList();}
            CyclicBarrier barrier=readBarrier;if("fact_index".equals(kind)&&barrier!=null)try{barrier.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}return values;
        }
        @Override public Map<String,Map<String,Object>> mapsByIds(String kind,String project,Collection<String> ids){
            if(track&&"fact_batch".equals(kind)&&!ids.isEmpty()){batchLoads.incrementAndGet();batchIdsLoaded.addAndGet(ids.size());firstLegacyRead.countDown();try{if(!releaseLegacyRead.await(10,TimeUnit.SECONDS))throw new IllegalStateException("TEST_LEGACY_READ_WAIT_TIMEOUT");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
            return super.mapsByIds(kind,project,ids);
        }
        synchronized void stripSourceIndex(){for(Row row:rows.values())if("fact_index".equals(row.kind())){row.value().remove("sourceIds");row.value().remove("sourceIndexVersion");}}
    }
}
