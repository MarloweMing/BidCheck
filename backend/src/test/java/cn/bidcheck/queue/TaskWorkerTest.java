package cn.bidcheck.queue;

import cn.bidcheck.infrastructure.queue.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskWorkerTest {
    private TaskRow task(String id) { TaskRow t=new TaskRow();t.id=id;t.status="PENDING";return t; }
    @SuppressWarnings("unchecked") private ObjectProvider<TaskNotificationPublisher> provider() { return mock(ObjectProvider.class); }

    @Test void globalCapacityDoesNotClaimOrQueueAThirdLongTask() throws Exception {
        BidService service=mock(BidService.class);RecordMapper mapper=mock(RecordMapper.class);
        TaskWorker worker=new TaskWorker(service,mapper,provider(),120000,10000,0);
        CountDownLatch entered=new CountDownLatch(2),finish=new CountDownLatch(1);
        when(mapper.claimTask(anyString(),anyString(),anyString(),anyLong())).thenReturn(1);
        when(mapper.task(anyString())).thenAnswer(a->task(a.getArgument(0)));
        doAnswer(a->{entered.countDown();finish.await(10,TimeUnit.SECONDS);return null;}).when(service).execute(any());
        try {
            assertThat(worker.tryStart("a")).isEqualTo(TaskWorker.Acceptance.STARTED);
            assertThat(worker.tryStart("b")).isEqualTo(TaskWorker.Acceptance.STARTED);
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(worker.tryStart("c")).isEqualTo(TaskWorker.Acceptance.BUSY);
            verify(mapper,never()).claimTask(eq("c"),anyString(),anyString(),anyLong());
        } finally { finish.countDown();worker.stop(); }
    }

    @Test void duplicateMessageDoesNotExecuteTwiceOrStopTheOriginalHeartbeat() throws Exception {
        BidService service=mock(BidService.class);RecordMapper mapper=mock(RecordMapper.class);
        TaskWorker worker=new TaskWorker(service,mapper,provider(),120000,10000,0);
        CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);TaskRow task=task("same");
        when(mapper.claimTask(eq("same"),anyString(),anyString(),anyLong())).thenReturn(1,0);
        when(mapper.task("same")).thenReturn(task);
        when(mapper.renewLease(eq("same"),anyString(),anyLong(),anyLong())).thenReturn(1);
        doAnswer(a->{entered.countDown();finish.await(10,TimeUnit.SECONDS);return null;}).when(service).execute(any());
        try {
            assertThat(worker.tryStart("same")).isEqualTo(TaskWorker.Acceptance.STARTED);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(worker.tryStart("same")).isEqualTo(TaskWorker.Acceptance.NOT_PENDING);
            worker.maintainLeases();
            verify(mapper).renewLease(eq("same"),eq(task.leaseOwner),anyLong(),anyLong());
            verify(service,times(1)).execute(task);
        } finally { finish.countDown();worker.stop(); }
    }

    @Test void unavailableBrokerStillExecutesPersistedPendingTask() throws Exception {
        BidService service=mock(BidService.class);RecordMapper mapper=mock(RecordMapper.class);
        ObjectProvider<TaskNotificationPublisher> provider=provider();TaskNotificationPublisher publisher=mock(TaskNotificationPublisher.class);
        when(provider.getIfAvailable()).thenReturn(publisher);when(publisher.publishConfirmed("fallback")).thenReturn(false);
        TaskRow task=task("fallback");when(mapper.pendingTasks()).thenReturn(cn.bidcheck.compat.Java8Api.listOf(task));
        when(mapper.claimTask(eq("fallback"),anyString(),anyString(),anyLong())).thenReturn(1);when(mapper.task("fallback")).thenReturn(task);
        CountDownLatch executed=new CountDownLatch(1);doAnswer(a->{executed.countDown();return null;}).when(service).execute(task);
        TaskWorker worker=new TaskWorker(service,mapper,provider,120000,10000,0);
        try { worker.dispatch();assertThat(executed.await(2,TimeUnit.SECONDS)).isTrue();verify(publisher).publishConfirmed("fallback"); }
        finally { worker.stop(); }
    }

    @Test void reclaimedTaskGetsANewFencingTokenEvenWithinTheSameWorkerInstance() throws Exception {
        BidService service=mock(BidService.class);RecordMapper mapper=mock(RecordMapper.class);
        TaskWorker worker=new TaskWorker(service,mapper,provider(),120000,10000,0);
        CountDownLatch entered=new CountDownLatch(2),finish=new CountDownLatch(1);
        List<String> tokens=new CopyOnWriteArrayList<>();
        CountDownLatch oldInterrupted=new CountDownLatch(1);
        when(mapper.claimTask(eq("reclaimed"),anyString(),anyString(),anyLong())).thenAnswer(a->{tokens.add(a.getArgument(2));return 1;});
        when(mapper.task("reclaimed")).thenAnswer(a->task("reclaimed"));
        when(mapper.renewLease(eq("reclaimed"),anyString(),anyLong(),anyLong())).thenReturn(1);
        doAnswer(a->{entered.countDown();try { finish.await(10,TimeUnit.SECONDS); } catch(InterruptedException interrupted) { oldInterrupted.countDown(); }return null;}).when(service).execute(any());
        try {
            assertThat(worker.tryStart("reclaimed")).isEqualTo(TaskWorker.Acceptance.STARTED);
            verify(service,timeout(2000)).execute(any());
            // The DB mock permits the second CAS to represent expiration/recovery while old work still exits.
            assertThat(worker.tryStart("reclaimed")).isEqualTo(TaskWorker.Acceptance.STARTED);
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(oldInterrupted.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(tokens).hasSize(2);assertThat(tokens.get(0)).isNotEqualTo(tokens.get(1));
            worker.maintainLeases();verify(mapper).renewLease(eq("reclaimed"),eq(tokens.get(1)),anyLong(),anyLong());
        } finally { finish.countDown();worker.stop(); }
        verify(mapper).clearLease("reclaimed",tokens.get(0));verify(mapper).clearLease("reclaimed",tokens.get(1));
    }

    @Test void exhaustedPendingTasksFailWithoutClaimingExecutionCapacity() throws Exception {
        BidService service=mock(BidService.class);RecordMapper mapper=mock(RecordMapper.class);TaskRow task=task("exhausted");task.attempt=3;
        when(mapper.task(task.id)).thenReturn(task);when(mapper.pendingTasks()).thenReturn(cn.bidcheck.compat.Java8Api.listOf(task));
        TaskWorker worker=new TaskWorker(service,mapper,provider(),120000,10000,0);
        try {
            assertThat(worker.tryStart(task.id)).isEqualTo(TaskWorker.Acceptance.NOT_PENDING);
            worker.dispatch();verify(service,times(2)).exhaust(task);
            verify(mapper,never()).claimTask(anyString(),anyString(),anyString(),anyLong());verify(service,never()).execute(any());
        } finally { worker.stop(); }
    }

    @Test void throwingNotificationAdapterCannotStrandDatabaseTask() throws Exception {
        BidService service=mock(BidService.class);RecordMapper mapper=mock(RecordMapper.class);TaskRow task=task("notify-error");
        ObjectProvider<TaskNotificationPublisher> provider=provider();TaskNotificationPublisher publisher=mock(TaskNotificationPublisher.class);
        when(provider.getIfAvailable()).thenReturn(publisher);when(publisher.publishConfirmed(task.id)).thenThrow(new IllegalStateException("broker unavailable"));
        when(mapper.pendingTasks()).thenReturn(cn.bidcheck.compat.Java8Api.listOf(task));when(mapper.task(task.id)).thenReturn(task);
        when(mapper.claimTask(eq(task.id),anyString(),anyString(),anyLong())).thenReturn(1);
        CountDownLatch started=new CountDownLatch(1);doAnswer(a->{started.countDown();return null;}).when(service).execute(task);
        TaskWorker worker=new TaskWorker(service,mapper,provider,120000,10000,0);
        try { worker.dispatch();assertThat(started.await(2,TimeUnit.SECONDS)).isTrue(); } finally { worker.stop(); }
    }

    @Test void sameValueHeartbeatUpdateDoesNotInterruptValidLease() throws Exception {
        BidService service=mock(BidService.class);RecordMapper mapper=mock(RecordMapper.class);TaskRow task=task("unchanged-renewal");
        when(mapper.task(task.id)).thenReturn(task);when(mapper.claimTask(eq(task.id),anyString(),anyString(),anyLong())).thenReturn(1);
        when(mapper.renewLease(eq(task.id),anyString(),anyLong(),anyLong())).thenReturn(0);
        CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);java.util.concurrent.atomic.AtomicBoolean interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(a->{entered.countDown();try { finish.await(10,TimeUnit.SECONDS); } catch(InterruptedException e) { interrupted.set(true); }return null;}).when(service).execute(task);
        TaskWorker worker=new TaskWorker(service,mapper,provider(),120000,10000,0);
        try { worker.tryStart(task.id);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();worker.maintainLeases(); }
        finally { finish.countDown();worker.stop(); }
        assertThat(interrupted.get()).isFalse();
    }

    @Test void executorRejectionReturnsClaimForBudgetRefund() {
        BidService service=mock(BidService.class);RecordMapper mapper=mock(RecordMapper.class);TaskRow task=task("rejected");
        TaskWorker worker=new TaskWorker(service,mapper,provider(),120000,10000,0);
        when(mapper.task(task.id)).thenReturn(task);
        when(mapper.claimTask(eq(task.id),anyString(),anyString(),anyLong())).thenAnswer(a->{worker.stop();return 1;});
        assertThat(worker.tryStart(task.id)).isEqualTo(TaskWorker.Acceptance.BUSY);
        verify(mapper).releaseClaim(eq(task.id),anyString(),anyString());
    }

    @Test void failedExecutionPersistsSafeClassificationAndLogsOnlyBoundedMetadata() throws Exception {
        BidService service=mock(BidService.class);RecordMapper mapper=mock(RecordMapper.class);
        TaskRow task=task("37fbeb30-2fbd-4a07-b23d-c1447317e329");
        when(mapper.task(task.id)).thenReturn(task);when(mapper.claimTask(eq(task.id),anyString(),anyString(),anyLong())).thenReturn(1);
        java.sql.SQLException sql=new java.sql.SQLException("UPDATE body='SECRET_DOCUMENT' key=SECRET_KEY","HY000",1205);
        doThrow(new IllegalStateException("SQL parameters SECRET_KEY",sql)).when(service).execute(task);
        ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(TaskWorker.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        TaskWorker worker=new TaskWorker(service,mapper,provider(),120000,10000,0);
        try {
            assertThat(worker.tryStart(task.id)).isEqualTo(TaskWorker.Acceptance.STARTED);
            org.mockito.ArgumentCaptor<java.lang.String> message=org.mockito.ArgumentCaptor.forClass(String.class);
            verify(service,timeout(2000)).fail(eq(task),message.capture());
            assertThat(message.getValue()).contains("数据库操作超时","诊断编号：taskdiag-").doesNotContain("文件格式","SECRET","UPDATE");
            java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> logs=cn.bidcheck.compat.Java8Api.toList(appender.list.stream().filter(event->event.getFormattedMessage().contains("任务安全诊断")));
            assertThat(logs).hasSize(1);assertThat(logs.get(0).getThrowableProxy()).isNull();
            assertThat(logs.get(0).getFormattedMessage()).contains("HY000","1205","DATABASE_TIMEOUT").doesNotContain("SECRET","UPDATE","SQL parameters");
        } finally {worker.stop();logger.detachAppender(appender);appender.stop();}
    }

}
