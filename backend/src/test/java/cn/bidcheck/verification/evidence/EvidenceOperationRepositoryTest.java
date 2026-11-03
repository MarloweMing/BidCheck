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
        List<Map<String,Object>> sources=cn.bidcheck.compat.Java8Api.listOf(new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("id", "S2", "documentHash", "b", "quote", "第二页原文")), new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("id", "S1", "documentHash", "a", "quote", "第一页原文")));
        List<Map<String,Object>> fields=cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount", "kind", "NUMBER"), cn.bidcheck.compat.Java8Api.mapOf("field", "name", "kind", "FIELD"));
        Map<String,Object> first=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",cn.bidcheck.compat.Java8Api.listOf(),sources,fields,"req","model","facts","extractor");
        List<Map<String,Object>> reversedSources=new ArrayList<>(sources);Collections.reverse(reversedSources);
        List<Map<String,Object>> reversedFields=new ArrayList<>(fields);Collections.reverse(reversedFields);
        Map<String,Object> second=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",cn.bidcheck.compat.Java8Api.listOf(),reversedSources,reversedFields,"req","model","facts","extractor");
        assertEquals(EvidenceOperationRepository.key(first),EvidenceOperationRepository.key(second));
        assertFalse(new ObjectMapper().valueToTree(first).toString().contains("第一页原文"),"descriptor retains only source digests");
    }

    @Test void completedResultSurvivesRepositoryRecreationAndStoresNoBusinessDecision(){
        RecordMapper mapper=mock(RecordMapper.class);Store store=new Store(mapper,new ObjectMapper());
        cn.bidcheck.verification.evidence.EvidenceOperationRepository first=new EvidenceOperationRepository(store);Map<String,Object> descriptor=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1", "quote", "报价80元")),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount")),"req","model","facts","extractor");
        String key=EvidenceOperationRepository.key(descriptor);first.publishCompleted("p",key,descriptor,cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount", "value", "80")), "sources", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1")), "fieldCoverage", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount"))));
        ArgumentCaptor<Stored> inserted=ArgumentCaptor.forClass(Stored.class);verify(mapper).insertRecord(inserted.capture());
        Stored stored=inserted.getValue();when(mapper.get(key)).thenReturn(stored);
        Map<String,Object> cached=new EvidenceOperationRepository(new Store(mapper,new ObjectMapper())).completed("p",key).orElseThrow(java.util.NoSuchElementException::new);
        assertEquals(true,cached.get("persistentCacheHit"));assertEquals(1,((List<?>)cached.get("facts")).size());
        Map<String,Object> envelope=store.map(stored.body);assertEquals(false,envelope.get("businessDecisionStored"));assertFalse(envelope.containsKey("status")&&cn.bidcheck.compat.Java8Api.listOf("PASS", "FAIL").contains(envelope.get("status")));
        assertTrue((!new EvidenceOperationRepository(store).completed("another-project",key).isPresent()));
    }

    @Test void businessDecisionCannotBePublishedAsEvidence(){
        RecordMapper mapper=mock(RecordMapper.class);EvidenceOperationRepository repository=new EvidenceOperationRepository(new Store(mapper,new ObjectMapper()));
        Map<String,Object> descriptor=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1", "quote", "报价80元")),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount")),"req","model","facts","extractor");String key=EvidenceOperationRepository.key(descriptor);
        java.lang.IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->repository.publishCompleted("p",key,descriptor,cn.bidcheck.compat.Java8Api.mapOf("businessDecision", "PASS", "facts", cn.bidcheck.compat.Java8Api.listOf(), "sources", cn.bidcheck.compat.Java8Api.listOf(), "fieldCoverage", cn.bidcheck.compat.Java8Api.listOf())));
        assertEquals("EVIDENCE_OPERATION_DECISION_FORBIDDEN",failure.getMessage());verifyNoInteractions(mapper);
    }

    @Test void nestedAndDisguisedConclusionsCannotBePublishedAsDurableEvidence(){
        for(Map<String,Object> forbidden:cn.bidcheck.compat.Java8Api.<Map<String,Object>>listOf(cn.bidcheck.compat.Java8Api.mapOf("attributes", cn.bidcheck.compat.Java8Api.mapOf("review", cn.bidcheck.compat.Java8Api.mapOf("Business_Decision", "PASS"))), cn.bidcheck.compat.Java8Api.mapOf("attributes", cn.bidcheck.compat.Java8Api.mapOf("audit", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("compliance-conclusion", "符合")))), cn.bidcheck.compat.Java8Api.mapOf("attributes", cn.bidcheck.compat.Java8Api.mapOf("review_status", "FAIL")), cn.bidcheck.compat.Java8Api.mapOf("attributes", cn.bidcheck.compat.Java8Api.mapOf("是否通过", true)))){
            RecordMapper mapper=mock(RecordMapper.class);EvidenceOperationRepository repository=new EvidenceOperationRepository(new Store(mapper,new ObjectMapper()));
            Map<String,Object> descriptor=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1", "quote", "报价80元")),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount")),"req","model","facts","extractor");
            String key=EvidenceOperationRepository.key(descriptor);Map<String,Object> fact=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("field", "amount", "value", "80"));fact.putAll(forbidden);
            java.lang.IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->repository.publishCompleted("p",key,descriptor,cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(fact), "sources", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1")), "fieldCoverage", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount")))));
            assertEquals("EVIDENCE_OPERATION_DECISION_FORBIDDEN",failure.getMessage());verifyNoInteractions(mapper);
        }
    }

    @Test void operationalStatusesRemainValidEvidenceMetadata(){
        RecordMapper mapper=mock(RecordMapper.class);EvidenceOperationRepository repository=new EvidenceOperationRepository(new Store(mapper,new ObjectMapper()));
        Map<String,Object> descriptor=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1", "quote", "报价80元")),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount")),"req","model","facts","extractor");String key=EvidenceOperationRepository.key(descriptor);
        repository.publishCompleted("p",key,descriptor,cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount", "value", "80", "attributes", cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "provenanceStatus", "TRUSTED_CONNECTOR"))), "sources", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1")), "fieldCoverage", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount"))));
        verify(mapper).insertRecord(any(Stored.class));
    }

    @Test void persistedLegacyPayloadWithNestedDecisionIsIgnoredOnRead(){
        RecordMapper mapper=mock(RecordMapper.class);Store store=new Store(mapper,new ObjectMapper());EvidenceOperationRepository repository=new EvidenceOperationRepository(store);
        Map<String,Object> descriptor=EvidenceOperationRepository.descriptor("p","TEXT_FACT_EXTRACTION",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1", "quote", "报价80元")),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount")),"req","model","facts","extractor");String key=EvidenceOperationRepository.key(descriptor);
        Map<String,Object> result=cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount", "value", "80", "attributes", cn.bidcheck.compat.Java8Api.mapOf("audit", cn.bidcheck.compat.Java8Api.mapOf("Evaluation_Result", "PASS")))), "sources", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1")), "fieldCoverage", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "amount")));
        Stored row=new Stored();row.id=key;row.kind="evidence_operation";row.projectId="p";row.parentId="TEXT_FACT_EXTRACTION";
        row.body=store.encode(cn.bidcheck.compat.Java8Api.mapOf("version", EvidenceOperationRepository.VERSION, "operationKey", key, "status", "COMPLETED", "descriptor", descriptor, "result", result));
        when(mapper.get(key)).thenReturn(row);assertTrue((!repository.completed("p",key).isPresent()));
    }

    @Test void identicalConcurrentWorkHasOneInProcessOwner()throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ownerEntered=new CountDownLatch(1),release=new CountDownLatch(1);List<String> order=Collections.synchronizedList(new ArrayList<>());
        try{
            Future<?> owner=pool.submit(()->{try(cn.bidcheck.verification.evidence.EvidenceOperationRepository.Lease ignored=EvidenceOperationRepository.coordinate("same-operation")){order.add("owner");ownerEntered.countDown();release.await();}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(ownerEntered.await(2,TimeUnit.SECONDS));
            Future<?> waiter=pool.submit(()->{try(cn.bidcheck.verification.evidence.EvidenceOperationRepository.Lease ignored=EvidenceOperationRepository.coordinate("same-operation")){order.add("waiter");}catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(50);assertEquals(cn.bidcheck.compat.Java8Api.listOf("owner"),order);release.countDown();owner.get(2,TimeUnit.SECONDS);waiter.get(2,TimeUnit.SECONDS);
            assertEquals(cn.bidcheck.compat.Java8Api.listOf("owner", "waiter"),order);assertEquals(0,EvidenceOperationRepository.activeFlights());
        }finally{release.countDown();pool.shutdownNow();}
    }
}
