package cn.bidcheck.service;

import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import cn.bidcheck.infrastructure.queue.TaskNotificationPublisher;
import cn.bidcheck.persistence.RecordMapper;
import cn.bidcheck.persistence.TaskRow;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Two bounded executors for a single instance; MySQL/H2 is the recoverable task source. */
@Component
public class TaskWorker {
    public enum Acceptance { STARTED, BUSY, NOT_PENDING }
    private static final Logger log=LoggerFactory.getLogger(TaskWorker.class);
    private final BidService service;
    private final RecordMapper mapper;
    private final ObjectProvider<TaskNotificationPublisher> notifications;
    private final long leaseMillis,heartbeatMillis,fallbackDelay;
    private final Semaphore capacity=new Semaphore(2);
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"bidcheck-task");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService leaseTimer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"bidcheck-lease");t.setDaemon(true);return t;});
    private final ConcurrentMap<String,Execution> active=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,Long> announced=new ConcurrentHashMap<>();
    private volatile boolean stopping;

    private static final class Execution {
        final TaskRow task; volatile Thread thread; volatile boolean leaseLost; volatile long deadline;
        Execution(TaskRow task,long deadline) { this.task=task;this.deadline=deadline; }
    }
    public TaskWorker(BidService service,RecordMapper mapper,ObjectProvider<TaskNotificationPublisher> notifications,
            @Value("${bidcheck.tasks.lease-ms:120000}") long leaseMillis,
            @Value("${bidcheck.tasks.heartbeat-ms:10000}") long heartbeatMillis,
            @Value("${bidcheck.queue.database-fallback-delay-ms:2000}") long fallbackDelay) {
        this.service=service;this.mapper=mapper;this.notifications=notifications;
        this.leaseMillis=Math.max(1000,leaseMillis);this.heartbeatMillis=Math.max(100,Math.min(heartbeatMillis,this.leaseMillis/3));
        this.fallbackDelay=Math.max(0,fallbackDelay);
    }
    @PostConstruct public void recover() {
        maintainLeases();
        leaseTimer.scheduleWithFixedDelay(this::maintainLeases,heartbeatMillis,heartbeatMillis,TimeUnit.MILLISECONDS);
    }
    @Scheduled(fixedDelayString="${bidcheck.tasks.poll-ms:700}") public void dispatch() {
        if(stopping) return;
        try {
            long now=System.currentTimeMillis(); announced.entrySet().removeIf(e->now-e.getValue()>60000);
            TaskNotificationPublisher publisher=notifications.getIfAvailable();
            for(TaskRow candidate:mapper.pendingTasks()) {
                if(candidate.attempt>=3) { service.exhaust(candidate);announced.remove(candidate.id);continue; }
                if(stopping || capacity.availablePermits()==0) return;
                Long sent=announced.get(candidate.id);
                if(publisher!=null && sent==null && announced.size()<256) {
                    boolean delivered=false;
                    try { delivered=publisher.publishConfirmed(candidate.id); }
                    catch(RuntimeException unavailable) { log.warn("通知发送失败，任务 {} 使用数据库调度",candidate.id); }
                    if(delivered) { announced.put(candidate.id,System.currentTimeMillis());continue; }
                } else if(sent!=null && now-sent<fallbackDelay) continue;
                tryStart(candidate.id);
            }
        } catch(RuntimeException failure) {
            diagnose(null,TaskFailureDiagnostic.Phase.DISPATCH,failure);
        }
    }
    /** Returns after a persistent lease and bounded submission, never after the long document/AI task. */
    public Acceptance tryStart(String taskId) {
        if(stopping) return Acceptance.BUSY;
        TaskRow task=mapper.task(taskId);
        if(task==null || !"PENDING".equals(task.status)) return Acceptance.NOT_PENDING;
        if(task.attempt>=3) { service.exhaust(task);announced.remove(taskId);return Acceptance.NOT_PENDING; }
        if(stopping || !capacity.tryAcquire()) return Acceptance.BUSY;
        String owner=UUID.randomUUID().toString(); // A fencing token belongs to one claim, not one JVM.
        boolean claimed=false,submitted=false;
        Execution reserved=null;
        try {
            long deadline=System.currentTimeMillis()+leaseMillis;
            if(mapper.claimTask(taskId,BidService.now(),owner,deadline)!=1) return Acceptance.NOT_PENDING;
            claimed=true;
            task.status="RUNNING";task.attempt++;task.leaseOwner=owner;task.leaseUntil=deadline;
            Execution execution=new Execution(task,deadline); reserved=execution;
            Execution previous=active.put(taskId,execution);
            if(previous!=null) loseLease(previous);
            workers.execute(()->execute(execution));submitted=true;announced.remove(taskId);
            return Acceptance.STARTED;
        } catch(RejectedExecutionException full) {
            if(claimed) mapper.releaseClaim(taskId,owner,BidService.now());
            return Acceptance.BUSY;
        } catch(RuntimeException failure) {
            if(claimed) try { mapper.releaseClaim(taskId,owner,BidService.now()); } catch(RuntimeException ignored) {}
            throw failure;
        } finally {
            if(!submitted) { if(reserved!=null) active.remove(taskId,reserved);capacity.release(); }
        }
    }
    private void execute(Execution execution) {
        TaskRow task=execution.task; execution.thread=Thread.currentThread();
        try {
            if(execution.leaseLost) throw new TaskLeaseLostException(task.id);
            service.execute(task);
        } catch(TaskLeaseLostException lost) {
            log.info("任务 {} 已由租约恢复机制接管，旧执行停止",task.id);
        } catch(Exception failure) {
            if(execution.leaseLost) log.info("任务 {} 的旧租约执行已退出",task.id);
            else if(stopping) {
                try { mapper.releaseClaim(task.id,task.leaseOwner,BidService.now()); } catch(RuntimeException ignored) {}
            } else {
                TaskFailureDiagnostic.Diagnostic diagnostic=diagnose(task.id,TaskFailureDiagnostic.Phase.EXECUTION,failure);
                try { service.fail(task,diagnostic.userMessage()); }
                catch(RuntimeException unavailable) { diagnose(task.id,TaskFailureDiagnostic.Phase.FAILURE_RECORD,unavailable); }
            }
        } finally {
            try { mapper.clearLease(task.id,task.leaseOwner); } catch(RuntimeException unavailable) { diagnose(task.id,TaskFailureDiagnostic.Phase.LEASE_CLEAR,unavailable); }
            active.remove(task.id,execution); capacity.release();
        }
    }
    public void maintainLeases() {
        for(Execution execution:active.values()) {
            if(execution.leaseLost) continue;
            long now=System.currentTimeMillis();
            try {
                if(mapper.renewLease(execution.task.id,execution.task.leaseOwner,now,now+leaseMillis)==1) execution.deadline=now+leaseMillis;
                else {
                    TaskRow current=mapper.task(execution.task.id);
                    // Some MySQL configurations report changed rows, so a same-value renewal can return zero.
                    if(current!=null && "RUNNING".equals(current.status) && execution.task.leaseOwner.equals(current.leaseOwner) && current.leaseUntil!=null && current.leaseUntil>System.currentTimeMillis()) execution.deadline=current.leaseUntil;
                    else if(current==null || !Set.of("SUCCEEDED","FAILED","CANCELLED").contains(current.status)) loseLease(execution);
                }
            } catch(RuntimeException unavailable) {
                if(System.currentTimeMillis()>=execution.deadline) loseLease(execution);
                diagnose(execution.task.id,TaskFailureDiagnostic.Phase.LEASE_RENEWAL,unavailable);
            }
        }
        try { mapper.recoverTasks(System.currentTimeMillis(),BidService.now()); }
        catch(RuntimeException unavailable) { diagnose(null,TaskFailureDiagnostic.Phase.LEASE_RECOVERY,unavailable); }
    }
    private static TaskFailureDiagnostic.Diagnostic diagnose(String taskId,TaskFailureDiagnostic.Phase phase,Throwable failure) {
        TaskFailureDiagnostic.Diagnostic diagnostic=TaskFailureDiagnostic.inspect(taskId,phase,failure);
        log.warn("任务安全诊断 {}",diagnostic.logRecord());
        return diagnostic;
    }
    private void loseLease(Execution execution) {
        execution.leaseLost=true;
        if(execution.thread!=null) execution.thread.interrupt();
    }
    @PreDestroy public void stop() {
        stopping=true;workers.shutdown();
        try { if(!workers.awaitTermination(5,TimeUnit.SECONDS)) workers.shutdownNow(); }
        catch(InterruptedException interrupted) { workers.shutdownNow();Thread.currentThread().interrupt(); }
        leaseTimer.shutdownNow();
    }
}
