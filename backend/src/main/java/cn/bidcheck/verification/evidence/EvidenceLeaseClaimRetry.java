package cn.bidcheck.verification.evidence;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only an aborted, autocommit database lease claim is retried. No provider call or publication
 * belongs in this callback; the owner token and the compare-and-set fences remain unchanged. */
final class EvidenceLeaseClaimRetry {
    private static final Logger LOG=LoggerFactory.getLogger(EvidenceLeaseClaimRetry.class);
    private EvidenceLeaseClaimRetry(){}
    static boolean execute(String projectId,String key,BooleanSupplier claim){
        boolean outer=TransactionSynchronizationManager.isActualTransactionActive();
        for(int attempt=1;;attempt++){
            if(Thread.currentThread().isInterrupted())throw new CancellationException("证据租约申请已中断");
            long started=System.nanoTime();
            try{
                boolean acquired=claim.getAsBoolean();
                if(attempt>1)LOG.info("bidcheck.evidence_lease_claim projectId={} operationKey={} attempt={} durationMs={} outcome={} recovered=true",safe(projectId),safe(key),attempt,(System.nanoTime()-started)/1_000_000,acquired?"ACQUIRED":"CONTENDED");
                return acquired;
            }catch(RuntimeException failure){
                SQLException aborted=aborted(failure);boolean retry=aborted!=null&&!outer&&attempt<3&&!Thread.currentThread().isInterrupted();
                LOG.warn("bidcheck.evidence_lease_claim projectId={} operationKey={} attempt={} durationMs={} errorCode={} sqlState={} retry={} outerTransaction={}",safe(projectId),safe(key),attempt,(System.nanoTime()-started)/1_000_000,aborted==null?"NON_RETRYABLE":aborted.getErrorCode(),aborted==null?"":safe(aborted.getSQLState()),retry,outer);
                if(!retry)throw failure;
                try{Thread.sleep(25L*attempt+ThreadLocalRandom.current().nextInt(26));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("证据租约申请重试已中断");}
            }
        }
    }
    private static SQLException aborted(Throwable error){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable next=error;next!=null&&seen.add(next);next=next.getCause()){
            if(next instanceof TransactionSystemException||next instanceof CancellationException||next instanceof cn.bidcheck.infrastructure.queue.TaskLeaseLostException)return null;
            if(next instanceof SQLException&&(((SQLException) (next)).getErrorCode()==1213||"40001".equals(((SQLException) (next)).getSQLState())))return ((SQLException) (next));
        }
        return null;
    }
    private static String safe(String value){return value!=null&&value.matches("[A-Za-z0-9:_-]{1,128}")?value:"";}
}
