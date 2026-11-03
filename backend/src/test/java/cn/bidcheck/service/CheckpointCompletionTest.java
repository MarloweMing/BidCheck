package cn.bidcheck.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointCompletionTest {
    @Test void slowFirstItemDoesNotLeaveFreeWorkersIdleAndPersistenceStaysOnCaller() throws Exception {
        CheckpointExecutor executor=new CheckpointExecutor(3);
        CountDownLatch fourthStarted=new CountDownLatch(1);
        List<Integer> persisted=new ArrayList<>();Thread caller=Thread.currentThread();
        try {
            executor.forEachCompleted(5,index->{
                if(index==0)try {if(!fourthStarted.await(3,TimeUnit.SECONDS))throw new IllegalStateException("No refill while first item waits");}
                    catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                if(index==3)fourthStarted.countDown();
                return index;
            },(index,value)->{assertSame(caller,Thread.currentThread());assertEquals(index,value);persisted.add(index);});
            assertEquals(cn.bidcheck.compat.Java8Api.setOf(0, 1, 2, 3, 4),new HashSet<>(persisted));assertEquals(5,persisted.size());assertNotEquals(0,persisted.get(0));
        } finally {executor.shutdown();}
    }
    @Test void failedPersistenceStopsFurtherSubmission() {
        CheckpointExecutor executor=new CheckpointExecutor(1);List<Integer> started=new CopyOnWriteArrayList<>();
        try {
            assertThrows(IllegalStateException.class,()->executor.forEachCompleted(3,i->{started.add(i);return i;},(i,v)->{throw new IllegalStateException("lease lost");}));
            assertEquals(cn.bidcheck.compat.Java8Api.listOf(0),started);
        } finally {executor.shutdown();}
    }
    @Test void shutdownReleasesCallersWhoseTasksAreQueuedBehindAnotherInvocation() throws Exception {
        CheckpointExecutor executor=new CheckpointExecutor(1);ExecutorService callers=Executors.newFixedThreadPool(2);
        CountDownLatch busy=new CountDownLatch(1),secondEntering=new CountDownLatch(1);
        try {
            Future<?> first=callers.submit(()->{try{executor.forEachCompleted(1,i->{busy.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return i;},(i,v)->{});}catch(Exception expected){}});
            assertTrue(busy.await(2,TimeUnit.SECONDS));
            Future<?> second=callers.submit(()->{secondEntering.countDown();try{executor.forEachCompleted(2,i->i,(i,v)->{});}catch(Exception expected){}});
            assertTrue(secondEntering.await(2,TimeUnit.SECONDS));
            executor.shutdown();first.get(3,TimeUnit.SECONDS);second.get(3,TimeUnit.SECONDS);
        } finally {executor.shutdown();callers.shutdownNow();}
    }
}
