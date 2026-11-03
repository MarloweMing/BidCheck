package cn.bidcheck.persistence;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Retries a database-only transaction after a confirmed transaction-abort signal.
 * Never wrap model calls, filesystem writes, or other externally visible work in this callback. */
public final class DbTransactionRetry {
    private static final Logger LOG=LoggerFactory.getLogger(DbTransactionRetry.class);
    private static final int MAX_ATTEMPTS=3;
    private DbTransactionRetry(){}

    public static <T> T execute(TransactionTemplate tx,String operation,Map<String,?> identifiers,TransactionCallback<T> action){
        Objects.requireNonNull(tx);Objects.requireNonNull(action);
        // Joining or suspending an existing transaction cannot make the caller's earlier work safe
        // to repeat. Preserve its propagation semantics, but never independently retry it.
        boolean outerTransaction=TransactionSynchronizationManager.isActualTransactionActive();
        int maximum=outerTransaction?1:MAX_ATTEMPTS;
        for(int attempt=1;;attempt++){
            interrupted();long started=System.nanoTime();boolean[] newTransaction={false};
            try{
                T result=tx.execute(status->{newTransaction[0]=status.isNewTransaction();return action.doInTransaction(status);});
                if(attempt>1)LOG.info("bidcheck.db_transaction operation={} taskId={} runId={} checkpointId={} attempt={} elapsedMs={} errorCode=RECOVERED retry=false",
                    safe(operation),id(identifiers,"taskId"),id(identifiers,"runId"),id(identifiers,"checkpointId"),attempt,(System.nanoTime()-started)/1_000_000);
                return result;
            }
            catch(RuntimeException failure){
                SQLException conflict=transactionAbort(failure);
                boolean retry=conflict!=null&&newTransaction[0]&&attempt<maximum&&!Thread.currentThread().isInterrupted();
                LOG.warn("bidcheck.db_transaction operation={} taskId={} runId={} checkpointId={} attempt={} elapsedMs={} errorCode={} sqlState={} retry={} outerTransaction={}",
                    safe(operation),id(identifiers,"taskId"),id(identifiers,"runId"),id(identifiers,"checkpointId"),attempt,
                    (System.nanoTime()-started)/1_000_000,conflict==null?"NON_RETRYABLE":conflict.getErrorCode(),conflict==null?"":safe(conflict.getSQLState()),retry,outerTransaction);
                if(!retry)throw failure;
                // execute() has left the failed transaction and rolled it back before reaching here.
                // 1213/40001 denotes an aborted transaction, unlike an arbitrary transport/timeout error.
                try{Thread.sleep(25L*attempt+ThreadLocalRandom.current().nextInt(26));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("数据库事务重试已中断");}
            }
        }
    }
    public static void executeWithoutResult(TransactionTemplate tx,String operation,Map<String,?> identifiers,Consumer<TransactionStatus> action){
        execute(tx,operation,identifiers,status->{action.accept(status);return null;});
    }
    private static SQLException transactionAbort(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable current=failure;current!=null&&seen.add(current);current=current.getCause()){
            if(current instanceof CancellationException||current instanceof cn.bidcheck.infrastructure.queue.TaskLeaseLostException)return null;
            if(current instanceof SQLException&&(((SQLException) (current)).getErrorCode()==1213||"40001".equals(((SQLException) (current)).getSQLState())))return ((SQLException) (current));
            // Failure during rollback leaves the outcome uncertain. Never infer a safe retry from
            // the original deadlock hidden behind a rollback/transaction-system failure.
            if(current instanceof org.springframework.transaction.TransactionSystemException)return null;
        }
        return null;
    }
    private static String id(Map<String,?> ids,String key){return ids==null?"":safe(Objects.toString(ids.get(key),""));}
    private static String safe(String value){return value!=null&&value.matches("[A-Za-z0-9:_-]{1,128}")?value:"";}
    private static void interrupted(){if(Thread.currentThread().isInterrupted())throw new CancellationException("数据库事务重试已中断");}
}
