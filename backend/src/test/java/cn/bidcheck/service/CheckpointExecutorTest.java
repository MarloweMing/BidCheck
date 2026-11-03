package cn.bidcheck.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class CheckpointExecutorTest {
    @Test void parallelEvaluationKeepsABoundedWindowAndConsumesOnCallerInInputOrder() throws Exception {
        CheckpointExecutor executor=new CheckpointExecutor(3);
        ExecutorService callers=Executors.newSingleThreadExecutor();
        CountDownLatch firstWave=new CountDownLatch(3),laterTwoFinished=new CountDownLatch(2),releaseFirst=new CountDownLatch(1);
        AtomicInteger started=new AtomicInteger(),active=new AtomicInteger(),peak=new AtomicInteger();
        AtomicReference<Thread> caller=new AtomicReference<>();
        List<Integer> consumed=new ArrayList<>();
        try {
            Future<?> result=callers.submit(()->{
                caller.set(Thread.currentThread());
                executor.forEachOrdered(7,index->{
                    assertNotSame(caller.get(),Thread.currentThread());
                    started.incrementAndGet();peak.accumulateAndGet(active.incrementAndGet(),Math::max);
                    try {
                        if(index<3) firstWave.countDown();
                        if(index==0) await(releaseFirst);
                        if(index==1||index==2) laterTwoFinished.countDown();
                        return index*10;
                    } finally { active.decrementAndGet(); }
                },(index,value)->{assertSame(caller.get(),Thread.currentThread());assertEquals(index*10,value);consumed.add(index);});
                return null;
            });
            await(firstWave);await(laterTwoFinished);
            assertEquals(3,started.get(),"No fourth task before the first result is consumed");
            assertTrue(consumed.isEmpty(),"Later completions must wait for index zero");
            releaseFirst.countDown();result.get(5,TimeUnit.SECONDS);
            assertEquals(cn.bidcheck.compat.Java8Api.listOf(0, 1, 2, 3, 4, 5, 6),consumed);assertTrue(peak.get()<=3);
        } finally { releaseFirst.countDown();callers.shutdownNow();executor.shutdown(); }
    }

    @Test void concurrentCallsShareGlobalCapacityAndConfigurationIsCappedAtFour() throws Exception {
        CheckpointExecutor executor=new CheckpointExecutor(99);
        ExecutorService callers=Executors.newFixedThreadPool(2);
        CountDownLatch fourStarted=new CountDownLatch(4),fifthStarted=new CountDownLatch(5),release=new CountDownLatch(1);
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
        try {
            List<Future<?>> results=new ArrayList<>();
            for(int call=0;call<2;call++) results.add(callers.submit(()->{
                executor.forEachOrdered(8,index->{
                    peak.accumulateAndGet(active.incrementAndGet(),Math::max);fourStarted.countDown();fifthStarted.countDown();
                    try { await(release);return index; } finally { active.decrementAndGet(); }
                },(index,value)->assertEquals(index,value));return null;
            }));
            await(fourStarted);assertFalse(fifthStarted.await(150,TimeUnit.MILLISECONDS),"Both calls must share four active workers");
            release.countDown();for(Future<?> result:results) result.get(5,TimeUnit.SECONDS);
            assertEquals(4,peak.get());
        } finally { release.countDown();callers.shutdownNow();executor.shutdown(); }
    }

    @Test void consumerFailureCancelsItsWholeUnconsumedWindow() {
        CheckpointExecutor executor=new CheckpointExecutor(3);
        CountDownLatch otherWorkersStarted=new CountDownLatch(2),interrupted=new CountDownLatch(2),neverReleased=new CountDownLatch(1);
        AtomicInteger started=new AtomicInteger();IllegalStateException failure=new IllegalStateException("Consumer rejected result");
        try {
            assertSame(failure,assertThrows(IllegalStateException.class,()->executor.forEachOrdered(20,index->{
                started.incrementAndGet();
                if(index==0) await(otherWorkersStarted);
                else { otherWorkersStarted.countDown();awaitCancellation(neverReleased,interrupted); }
                return index;
            },(index,value)->{throw failure;})));
            await(interrupted);assertEquals(3,started.get());
        } finally { neverReleased.countDown();executor.shutdown(); }
    }

    @Test void evaluationFailureAlsoCancelsOtherInFlightComputations() {
        CheckpointExecutor executor=new CheckpointExecutor(3);
        CountDownLatch otherWorkersStarted=new CountDownLatch(2),interrupted=new CountDownLatch(2),neverReleased=new CountDownLatch(1);
        IllegalArgumentException failure=new IllegalArgumentException("Invalid checkpoint");
        AtomicInteger consumed=new AtomicInteger();
        try {
            assertSame(failure,assertThrows(IllegalArgumentException.class,()->executor.forEachOrdered(10,index->{
                if(index==0) { await(otherWorkersStarted);throw failure; }
                otherWorkersStarted.countDown();awaitCancellation(neverReleased,interrupted);return index;
            },(index,value)->consumed.incrementAndGet())));
            await(interrupted);assertEquals(0,consumed.get());
        } finally { neverReleased.countDown();executor.shutdown(); }
    }

    @Test void callerInterruptionCancelsTheWindowAndPreservesItsInterruptFlag() throws Exception {
        CheckpointExecutor executor=new CheckpointExecutor(3);
        CountDownLatch started=new CountDownLatch(3),interrupted=new CountDownLatch(3),neverReleased=new CountDownLatch(1),callerDone=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean();AtomicInteger consumed=new AtomicInteger();
        Thread caller=new Thread(()->{
            try { executor.forEachOrdered(20,index->{started.countDown();awaitCancellation(neverReleased,interrupted);return index;},(index,value)->consumed.incrementAndGet()); }
            catch(Throwable caught) { failure.set(caught);flag.set(Thread.currentThread().isInterrupted()); }
            finally { callerDone.countDown(); }
        });
        try {
            caller.start();await(started);caller.interrupt();await(callerDone);await(interrupted);
            assertInstanceOf(InterruptedIOException.class,failure.get());assertTrue(flag.get());assertEquals(0,consumed.get());
        } finally { neverReleased.countDown();caller.interrupt();caller.join(1000);executor.shutdown(); }
    }

    @Test void preInterruptedCallerStartsNoWorkAndEmptyInputConsumesNothing() throws Exception {
        CheckpointExecutor executor=new CheckpointExecutor(0);AtomicInteger called=new AtomicInteger();
        try {
            executor.forEachOrdered(0,index->called.incrementAndGet(),(index,value)->called.incrementAndGet());
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class,()->executor.forEachOrdered(2,index->called.incrementAndGet(),(index,value)->called.incrementAndGet()));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,called.get());
        } finally { Thread.interrupted();executor.shutdown(); }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5,TimeUnit.SECONDS),"Timed out waiting for test coordination"); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError("Unexpected test interruption",interrupted); }
    }
    private static void awaitCancellation(CountDownLatch neverReleased,CountDownLatch interrupted) {
        try { neverReleased.await(); }
        catch(InterruptedException expected) { Thread.currentThread().interrupt();interrupted.countDown(); }
    }
}
