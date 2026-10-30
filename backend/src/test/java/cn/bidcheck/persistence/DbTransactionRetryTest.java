package cn.bidcheck.persistence;

import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;

class DbTransactionRetryTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private static final Map<String,Object> IDS=Map.of("taskId","task-1","runId","run-1");
    @BeforeEach void database(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:db-retry-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE published_receipt(id INT PRIMARY KEY, value_text VARCHAR(40))");
    }
    private RuntimeException failure(String state,int code){return new UncategorizedSQLException("test","",new SQLException("private diagnostic text",state,code));}
    @Test void deadlockRollsBackBeforeNewTransactionAndDoesNotRepeatModelWork(){
        AtomicInteger databaseCalls=new AtomicInteger(),modelCalls=new AtomicInteger();List<Integer> completions=new ArrayList<>();
        String observation="observation-"+modelCalls.incrementAndGet();
        DbTransactionRetry.executeWithoutResult(tx,"PUBLISH",IDS,status->{
            assertTrue(status.isNewTransaction());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM published_receipt",Integer.class));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int state){completions.add(state);}});
            jdbc.update("INSERT INTO published_receipt(id,value_text) VALUES(1,?)",observation);
            if(databaseCalls.incrementAndGet()==1)throw failure("40001",1213);
        });
        assertEquals(2,databaseCalls.get());assertEquals(1,modelCalls.get());
        assertEquals(List.of(TransactionSynchronization.STATUS_ROLLED_BACK,TransactionSynchronization.STATUS_COMMITTED),completions);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM published_receipt",Integer.class));
    }
    @Test void serializableAbortRetriesAtMostThreeTransactions(){
        AtomicInteger attempts=new AtomicInteger();RuntimeException error=failure("40001",0);
        assertSame(error,assertThrows(RuntimeException.class,()->DbTransactionRetry.executeWithoutResult(tx,"PUBLISH",IDS,status->{
            attempts.incrementAndGet();jdbc.update("INSERT INTO published_receipt(id,value_text) VALUES(1,'temporary')");throw error;
        })));
        assertEquals(3,attempts.get());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM published_receipt",Integer.class));
    }
    @Test void lockWaitTimeoutAndIntegrityErrorsAreNotAssumedToAbortWholeTransaction(){
        for(RuntimeException failure:List.of(failure("HY000",1205),failure("23000",1062),new IllegalArgumentException("validation"))){
            AtomicInteger attempts=new AtomicInteger();assertSame(failure,assertThrows(RuntimeException.class,()->DbTransactionRetry.execute(tx,"PUBLISH",IDS,status->{attempts.incrementAndGet();throw failure;})));
            assertEquals(1,attempts.get());
        }
    }
    @Test void leaseLossNeverRetriesEvenAfterEarlierDeadlock(){
        AtomicInteger attempts=new AtomicInteger();TaskLeaseLostException lease=new TaskLeaseLostException("task-1");
        assertSame(lease,assertThrows(TaskLeaseLostException.class,()->DbTransactionRetry.execute(tx,"PUBLISH",IDS,status->{
            if(attempts.incrementAndGet()==1)throw failure("40001",1213);throw lease;
        })));assertEquals(2,attempts.get());
    }
    @Test void existingOuterTransactionPreventsIndependentRetryEvenWhenRequiresNew(){
        for(int propagation:List.of(TransactionDefinition.PROPAGATION_REQUIRED,TransactionDefinition.PROPAGATION_REQUIRES_NEW)){
            TransactionTemplate inner=new TransactionTemplate(tx.getTransactionManager());inner.setPropagationBehavior(propagation);
            AtomicInteger attempts=new AtomicInteger();RuntimeException error=failure("40001",1213);
            assertThrows(RuntimeException.class,()->tx.execute(outer->DbTransactionRetry.execute(inner,"PUBLISH",IDS,status->{attempts.incrementAndGet();throw error;})));
            assertEquals(1,attempts.get());
        }
    }
    @Test void rollbackFailureAndNontransactionalCallbacksAreNotRetried(){
        AtomicInteger attempts=new AtomicInteger();RuntimeException uncertain=new TransactionSystemException("rollback failed",failure("40001",1213));
        assertSame(uncertain,assertThrows(RuntimeException.class,()->DbTransactionRetry.execute(tx,"PUBLISH",IDS,status->{attempts.incrementAndGet();throw uncertain;})));
        assertEquals(1,attempts.get());
        TransactionTemplate unsupported=new TransactionTemplate(tx.getTransactionManager());unsupported.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);
        attempts.set(0);assertThrows(RuntimeException.class,()->DbTransactionRetry.execute(unsupported,"PUBLISH",IDS,status->{attempts.incrementAndGet();throw failure("40001",1213);}));assertEquals(1,attempts.get());
    }
    @Test void interruptionStopsBeforeDatabaseWorkOrAnotherAttempt(){
        AtomicInteger attempts=new AtomicInteger();
        try{Thread.currentThread().interrupt();assertThrows(CancellationException.class,()->DbTransactionRetry.execute(tx,"PUBLISH",IDS,status->attempts.incrementAndGet()));assertEquals(0,attempts.get());}
        finally{Thread.interrupted();}
        try{assertThrows(RuntimeException.class,()->DbTransactionRetry.execute(tx,"PUBLISH",IDS,status->{attempts.incrementAndGet();Thread.currentThread().interrupt();throw failure("40001",1213);}));assertEquals(1,attempts.get());}
        finally{Thread.interrupted();}
    }
}
