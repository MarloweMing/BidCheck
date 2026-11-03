package cn.bidcheck.verification.evidence;

import cn.bidcheck.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceLeaseClaimRetryTest {
    RuntimeException sql(String state,int code){return new UncategorizedSQLException("claim","",new SQLException("private payload",state,code));}
    @Test void repositoryRetriesAbortedClaimWithOneOwnerAndExecutesProviderOnlyAfterAcquisition()throws Exception{
        RecordMapper mapper=mock(RecordMapper.class);List<String> owners=new ArrayList<>();AtomicInteger attempts=new AtomicInteger(),provider=new AtomicInteger();
        when(mapper.tryInsertEvidenceOperationLease(any())).thenAnswer(call->{EvidenceOperationLeaseRow row=call.getArgument(0);owners.add(row.owner);if(attempts.incrementAndGet()==1)throw sql("40001",1213);return 1;});
        cn.bidcheck.verification.evidence.EvidenceOperationRepository repository=new EvidenceOperationRepository(new Store(mapper,new ObjectMapper()),false,1000,0,5);
        try(cn.bidcheck.verification.evidence.EvidenceOperationRepository.Lease lease=repository.coordinate("project",cn.bidcheck.compat.Java8Api.repeat("a", 64))){assertTrue(lease.ownsDurableExecution());provider.incrementAndGet();}
        assertEquals(2,attempts.get());assertEquals(1,new HashSet<>(owners).size());assertEquals(1,provider.get());
        verify(mapper,times(1)).releaseEvidenceOperationLease(eq(cn.bidcheck.compat.Java8Api.repeat("a", 64)),eq("project"),eq(owners.get(0)));
        verify(mapper,never()).takeOverEvidenceOperationLease(any(),any(),any(),anyLong(),anyLong());
    }
    @Test void repeatedDeadlockStopsAfterThreeClaimsWithoutModelWork(){
        AtomicInteger attempts=new AtomicInteger();RuntimeException failure=sql("40001",1213);
        assertSame(failure,assertThrows(RuntimeException.class,()->EvidenceLeaseClaimRetry.execute("p","k",()->{attempts.incrementAndGet();throw failure;})));
        assertEquals(3,attempts.get());
    }
    @Test void connectionFailureLockTimeoutAndUncertainRollbackAreNotRetried(){
        for(RuntimeException error:cn.bidcheck.compat.Java8Api.listOf(sql("08S01",0), sql("HY000",1205), new TransactionSystemException("rollback uncertain",sql("40001",1213)))){
            AtomicInteger attempts=new AtomicInteger();assertSame(error,assertThrows(RuntimeException.class,()->EvidenceLeaseClaimRetry.execute("p","k",()->{attempts.incrementAndGet();throw error;})));assertEquals(1,attempts.get());
        }
    }
    @Test void anOuterTransactionIsNeverReplayed(){
        AtomicInteger attempts=new AtomicInteger();TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThrows(RuntimeException.class,()->EvidenceLeaseClaimRetry.execute("p","k",()->{attempts.incrementAndGet();throw sql("40001",1213);}));assertEquals(1,attempts.get());}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void cancellationDoesNotEnterOrRepeatClaim(){
        AtomicInteger attempts=new AtomicInteger();
        try{Thread.currentThread().interrupt();assertThrows(CancellationException.class,()->EvidenceLeaseClaimRetry.execute("p","k",()->{attempts.incrementAndGet();return true;}));assertEquals(0,attempts.get());}
        finally{Thread.interrupted();}
    }
    @Test void visibleCompetingLeaseDoesNotHideNonDuplicateDatabaseError(){
        RecordMapper mapper=mock(RecordMapper.class);RuntimeException failure=sql("08S01",0);
        when(mapper.tryInsertEvidenceOperationLease(any())).thenThrow(failure);when(mapper.evidenceOperationLease(any())).thenReturn(new EvidenceOperationLeaseRow());
        cn.bidcheck.verification.evidence.EvidenceOperationRepository repository=new EvidenceOperationRepository(new Store(mapper,new ObjectMapper()),false,1000,0,5);
        assertSame(failure,assertThrows(RuntimeException.class,()->repository.coordinate("project",cn.bidcheck.compat.Java8Api.repeat("a", 64))));
        verify(mapper,never()).takeOverEvidenceOperationLease(any(),any(),any(),anyLong(),anyLong());
    }
}
