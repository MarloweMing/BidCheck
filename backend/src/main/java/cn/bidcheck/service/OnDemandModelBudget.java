package cn.bidcheck.service;

import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import cn.bidcheck.persistence.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.springframework.transaction.support.TransactionTemplate;

/** Historical class name; reservations now audit attempts without limiting them.
 * Persisted before dispatch. A crash may leave a reservation without HTTP, never the reverse. */
final class OnDemandModelBudget {
    private final Store store;
    private final TransactionTemplate tx;
    private final String taskId, owner, project, runId;
    private final int taskAttempt;
    OnDemandModelBudget(Store store, TransactionTemplate tx, TaskRow task, String runId) {
        this.store=store;this.tx=tx;this.taskId=task.id;this.owner=task.leaseOwner;this.project=task.projectId;this.runId=runId;this.taskAttempt=task.attempt;
    }
    boolean reserve() {
        return reserve(false);
    }
    boolean reserveConsistency() { return reserve(true); }
    private boolean reserve(boolean consistency) {
        if(Thread.currentThread().isInterrupted()) throw new CancellationException("Check interrupted");
        return Boolean.TRUE.equals(DbTransactionRetry.execute(tx,consistency?"CONSISTENCY_ATTEMPT_RESERVE":"ATOMIC_ATTEMPT_RESERVE",cn.bidcheck.compat.Java8Api.mapOf("taskId", taskId, "runId", runId),ignored -> {
            TaskRow current=store.mapper().lockTask(taskId);
            if(current==null||!"RUNNING".equals(current.status)||!project.equals(current.projectId)
                    ||!Objects.equals(owner,current.leaseOwner)||taskAttempt!=current.attempt
                    ||(owner!=null&&(current.leaseUntil==null||current.leaseUntil<System.currentTimeMillis())))
                throw new TaskLeaseLostException(taskId);
            Map<String,Object> run=store.map(store.lock("run",runId).body);
            if(!"RULES_RETRIEVAL".equals(run.get("checkMode"))||!taskId.equals(run.get("taskId"))
                    ||!"RUNNING".equals(run.get("status"))) throw new TaskLeaseLostException(taskId);
            if(!(run.get("onDemandAttemptsReserved") instanceof Number)||((Number) (run.get("onDemandAttemptsReserved"))).longValue()<0)
                throw new IllegalStateException("Missing or invalid persisted on-demand reservation count");
            if(consistency) {
                if(!(run.get("consistencyReviewAttemptsReserved") instanceof Number)
                    || ((Number) (run.get("consistencyReviewAttemptsReserved"))).longValue()<0) throw new IllegalStateException("Missing consistency review reservation count");
                run.put("consistencyReviewAttemptsReserved",Math.incrementExact(((Number) (run.get("consistencyReviewAttemptsReserved"))).longValue()));
            }
            run.put("onDemandAttemptsReserved",Math.incrementExact(((Number) (run.get("onDemandAttemptsReserved"))).longValue()));
            store.save("run",runId,project,Objects.toString(run.get("documentId"),""),run);
            return true;
        }));
    }
}
