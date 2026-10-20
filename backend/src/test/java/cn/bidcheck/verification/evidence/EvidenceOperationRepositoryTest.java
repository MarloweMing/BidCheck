package cn.bidcheck.verification.evidence;

import cn.bidcheck.persistence.RecordMapper;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceOperationRepositoryTest {
    @Test void stableKeyIgnoresMapSourceAndRequestedFieldOrdering(){
        List<Map<String,Object>> sources=List.of(
            new LinkedHashMap<>(Map.of("id","S2","documentHash","b","quote","第二页原文")),
            new LinkedHashMap<>(Map.of("id","S1","documentHash","a","quote","第一页原文")));
        List<Map<String,Object>> fields=List.of(Map.of("field","amount","kind","NUMBER"),Map.of("field","name","kind","FIELD"));
        Map<String,Object> first=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",List.of(),sources,fields,"req","model","facts","extractor");
        List<Map<String,Object>> reversedSources=new ArrayList<>(sources);Collections.reverse(reversedSources);
        List<Map<String,Object>> reversedFields=new ArrayList<>(fields);Collections.reverse(reversedFields);
        Map<String,Object> second=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",List.of(),reversedSources,reversedFields,"req","model","facts","extractor");
        assertEquals(EvidenceOperationRepository.key(first),EvidenceOperationRepository.key(second));
        assertFalse(new ObjectMapper().valueToTree(first).toString().contains("第一页原文"),"descriptor retains only source digests");
    }

    @Test void completedResultSurvivesRepositoryRecreationAndStoresNoBusinessDecision(){
        RecordMapper mapper=mock(RecordMapper.class);Store store=new Store(mapper,new ObjectMapper());
        var first=new EvidenceOperationRepository(store);Map<String,Object> descriptor=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",List.of(),List.of(Map.of("id","S1","quote","报价80元")),List.of(Map.of("field","amount")),"req","model","facts","extractor");
        String key=EvidenceOperationRepository.key(descriptor);first.publishCompleted("p",key,descriptor,Map.of("facts",List.of(Map.of("field","amount","value","80")),"sources",List.of(Map.of("id","S1")),"fieldCoverage",List.of(Map.of("field","amount"))));
        ArgumentCaptor<Stored> inserted=ArgumentCaptor.forClass(Stored.class);verify(mapper).insertRecord(inserted.capture());
        Stored stored=inserted.getValue();when(mapper.get(key)).thenReturn(stored);
        Map<String,Object> cached=new EvidenceOperationRepository(new Store(mapper,new ObjectMapper())).completed("p",key).orElseThrow();
        assertEquals(true,cached.get("persistentCacheHit"));assertEquals(1,((List<?>)cached.get("facts")).size());
        Map<String,Object> envelope=store.map(stored.body);assertEquals(false,envelope.get("businessDecisionStored"));assertFalse(envelope.containsKey("status")&&List.of("PASS","FAIL").contains(envelope.get("status")));
        assertTrue(new EvidenceOperationRepository(store).completed("another-project",key).isEmpty());
    }

    @Test void businessDecisionCannotBePublishedAsEvidence(){
        RecordMapper mapper=mock(RecordMapper.class);EvidenceOperationRepository repository=new EvidenceOperationRepository(new Store(mapper,new ObjectMapper()));
        Map<String,Object> descriptor=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",List.of(),List.of(Map.of("id","S1","quote","报价80元")),List.of(Map.of("field","amount")),"req","model","facts","extractor");String key=EvidenceOperationRepository.key(descriptor);
        var failure=assertThrows(IllegalArgumentException.class,()->repository.publishCompleted("p",key,descriptor,Map.of("businessDecision","PASS","facts",List.of(),"sources",List.of(),"fieldCoverage",List.of())));
        assertEquals("EVIDENCE_OPERATION_DECISION_FORBIDDEN",failure.getMessage());verifyNoInteractions(mapper);
    }

    @Test void nestedAndDisguisedConclusionsCannotBePublishedAsDurableEvidence(){
        for(Map<String,Object> forbidden:List.<Map<String,Object>>of(
            Map.of("attributes",Map.of("review",Map.of("Business_Decision","PASS"))),
            Map.of("attributes",Map.of("audit",List.of(Map.of("compliance-conclusion","符合")))),
            Map.of("attributes",Map.of("review_status","FAIL")),
            Map.of("attributes",Map.of("是否通过",true)))){
            RecordMapper mapper=mock(RecordMapper.class);EvidenceOperationRepository repository=new EvidenceOperationRepository(new Store(mapper,new ObjectMapper()));
            Map<String,Object> descriptor=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",List.of(),List.of(Map.of("id","S1","quote","报价80元")),List.of(Map.of("field","amount")),"req","model","facts","extractor");
            String key=EvidenceOperationRepository.key(descriptor);Map<String,Object> fact=new LinkedHashMap<>(Map.of("field","amount","value","80"));fact.putAll(forbidden);
            var failure=assertThrows(IllegalArgumentException.class,()->repository.publishCompleted("p",key,descriptor,Map.of("facts",List.of(fact),"sources",List.of(Map.of("id","S1")),"fieldCoverage",List.of(Map.of("field","amount")))));
            assertEquals("EVIDENCE_OPERATION_DECISION_FORBIDDEN",failure.getMessage());verifyNoInteractions(mapper);
        }
    }

    @Test void operationalStatusesRemainValidEvidenceMetadata(){
        RecordMapper mapper=mock(RecordMapper.class);EvidenceOperationRepository repository=new EvidenceOperationRepository(new Store(mapper,new ObjectMapper()));
        Map<String,Object> descriptor=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",List.of(),List.of(Map.of("id","S1","quote","报价80元")),List.of(Map.of("field","amount")),"req","model","facts","extractor");String key=EvidenceOperationRepository.key(descriptor);
        repository.publishCompleted("p",key,descriptor,Map.of("facts",List.of(Map.of("field","amount","value","80","attributes",Map.of("status","COMPLETED","provenanceStatus","TRUSTED_CONNECTOR"))),"sources",List.of(Map.of("id","S1")),"fieldCoverage",List.of(Map.of("field","amount"))));
        verify(mapper).insertRecord(any(Stored.class));
    }

    @Test void persistedLegacyPayloadWithNestedDecisionIsIgnoredOnRead(){
        RecordMapper mapper=mock(RecordMapper.class);Store store=new Store(mapper,new ObjectMapper());EvidenceOperationRepository repository=new EvidenceOperationRepository(store);
        Map<String,Object> descriptor=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",List.of(),List.of(Map.of("id","S1","quote","报价80元")),List.of(Map.of("field","amount")),"req","model","facts","extractor");String key=EvidenceOperationRepository.key(descriptor);
        Map<String,Object> result=Map.of("facts",List.of(Map.of("field","amount","value","80","attributes",Map.of("audit",Map.of("Evaluation_Result","PASS")))),"sources",List.of(Map.of("id","S1")),"fieldCoverage",List.of(Map.of("field","amount")));
        Stored row=new Stored();row.id=key;row.kind="evidence_operation";row.projectId="p";row.parentId="TEXT_FACT_EXTRACTION";
        row.body=store.encode(Map.of("version",EvidenceOperationRepository.VERSION,"operationKey",key,"status","COMPLETED","descriptor",descriptor,"result",result));
        when(mapper.get(key)).thenReturn(row);assertTrue(repository.completed("p",key).isEmpty());
    }

    @Test void identicalConcurrentWorkHasOneInProcessOwner()throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ownerEntered=new CountDownLatch(1),release=new CountDownLatch(1);List<String> order=Collections.synchronizedList(new ArrayList<>());
        try{
            Future<?> owner=pool.submit(()->{try(var ignored=EvidenceOperationRepository.coordinate("same-operation")){order.add("owner");ownerEntered.countDown();release.await();}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(ownerEntered.await(2,TimeUnit.SECONDS));
            Future<?> waiter=pool.submit(()->{try(var ignored=EvidenceOperationRepository.coordinate("same-operation")){order.add("waiter");}catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(50);assertEquals(List.of("owner"),order);release.countDown();owner.get(2,TimeUnit.SECONDS);waiter.get(2,TimeUnit.SECONDS);
            assertEquals(List.of("owner","waiter"),order);assertEquals(0,EvidenceOperationRepository.activeFlights());
        }finally{release.countDown();pool.shutdownNow();}
    }
}
