package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.SharedFactReuseTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservationFlightRegistryTest {
    private static SharedFactReuse reuse(EvidenceCorpus corpus){return new SharedFactReuse(corpus,Map.of(),"flight-registry-test-model","engine");}
    private static Rule quantity(){return new Rule("quantity","数量","NUMBER","","quantity",">=","1","个",Map.of(),"数量","",List.of());}

    @Test void differentTargetsWith192CommonSourcesNeverBlockEachOther()throws Exception{
        int baseline=SharedFactReuse.activeFlightKeys();
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("flight-many","p",1,IntStream.rangeClosed(1,192).mapToObj(i->"报价和数量第"+i+"页正文").toArray(String[]::new))));
        assertEquals(192,corpus.all.size());ExecutorService worker=Executors.newSingleThreadExecutor();
        try{try(var held=reuse(corpus).coordinate(List.of(rule()),corpus.all)){
            assertEquals(baseline+192,SharedFactReuse.activeFlightKeys());
            Future<Boolean> unrelated=worker.submit(()->{try(var own=reuse(corpus).coordinate(List.of(quantity()),corpus.all)){
                assertEquals(baseline+384,SharedFactReuse.activeFlightKeys());return true;
            }});
            assertTrue(unrelated.get(3,TimeUnit.SECONDS),"Disjoint observation targets must run while the first 192-window lease is held");
            assertEquals(baseline+192,SharedFactReuse.activeFlightKeys());
        }assertEquals(baseline,SharedFactReuse.activeFlightKeys());}finally{worker.shutdownNow();}
    }

    @Test void sameSourceWindowWaitsAndItsRegistryEntrySurvivesQueuedLease()throws Exception{
        int baseline=SharedFactReuse.activeFlightKeys();EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("flight-same","p",1,"报价正文")));
        ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch trying=new CountDownLatch(1);Future<Boolean> waiting;
        try{try(var held=reuse(corpus).coordinate(List.of(rule()),corpus.all)){
            waiting=worker.submit(()->{trying.countDown();try(var same=reuse(corpus).coordinate(List.of(rule()),corpus.all)){return true;}});
            assertTrue(trying.await(2,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->waiting.get(100,TimeUnit.MILLISECONDS));
            assertEquals(baseline+1,SharedFactReuse.activeFlightKeys());
        }assertTrue(waiting.get(3,TimeUnit.SECONDS));assertEquals(baseline,SharedFactReuse.activeFlightKeys());}finally{worker.shutdownNow();}
    }

    @Test void interruptionReleasesAlreadyAcquiredLocksAndAllRegisteredReferences()throws Exception{
        int baseline=SharedFactReuse.activeFlightKeys();EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("flight-interrupted","p",1,"报价第一页","报价第二页")));
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean interruptPreserved=new AtomicBoolean();CountDownLatch trying=new CountDownLatch(1);
        Thread waiting=new Thread(()->{trying.countDown();try(var both=reuse(corpus).coordinate(List.of(rule()),corpus.all)){failure.set(new AssertionError("Interrupted lease must not acquire"));}
            catch(Throwable error){failure.set(error);interruptPreserved.set(Thread.currentThread().isInterrupted());}},"observation-interrupt-test");
        ExecutorService probe=Executors.newSingleThreadExecutor();CountDownLatch probeDone=new CountDownLatch(1);
        try{try(var second=reuse(corpus).coordinate(List.of(rule()),List.of(corpus.all.get(1)))){
            waiting.start();assertTrue(trying.await(2,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while((SharedFactReuse.activeFlightKeys()!=baseline+2||waiting.getState()!=Thread.State.WAITING)&&System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(baseline+2,SharedFactReuse.activeFlightKeys());assertEquals(Thread.State.WAITING,waiting.getState());
            Future<Boolean> first=probe.submit(()->{try(var lease=reuse(corpus).coordinate(List.of(rule()),List.of(corpus.all.get(0)))){return true;}finally{probeDone.countDown();}});
            assertThrows(TimeoutException.class,()->first.get(100,TimeUnit.MILLISECONDS),"The waiting lease already owns the first sorted source lock");
            first.cancel(true);assertTrue(probeDone.await(3,TimeUnit.SECONDS));waiting.interrupt();waiting.join(3000);
            assertFalse(waiting.isAlive());assertInstanceOf(InterruptedIOException.class,failure.get());assertTrue(interruptPreserved.get());
            assertEquals(baseline+1,SharedFactReuse.activeFlightKeys(),"Only the caller's second-window lease remains");
            try(var freeFirst=reuse(corpus).coordinate(List.of(rule()),List.of(corpus.all.get(0)))){assertEquals(baseline+2,SharedFactReuse.activeFlightKeys());}
        }assertEquals(baseline,SharedFactReuse.activeFlightKeys());}finally{waiting.interrupt();waiting.join(3000);probe.shutdownNow();}
    }

    @Test void exceptionAndRepeatedCloseDoNotLeakOrReleaseAnotherLease()throws Exception{
        int baseline=SharedFactReuse.activeFlightKeys();EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc("flight-exception","p",1,"报价正文")));
        assertThrows(IllegalStateException.class,()->{try(var held=reuse(corpus).coordinate(List.of(rule()),corpus.all)){throw new IllegalStateException("simulated extraction failure");}});
        assertEquals(baseline,SharedFactReuse.activeFlightKeys());
        var prior=reuse(corpus).coordinate(List.of(rule()),corpus.all);prior.close();
        try(var current=reuse(corpus).coordinate(List.of(rule()),corpus.all)){prior.close();assertEquals(baseline+1,SharedFactReuse.activeFlightKeys());}
        assertEquals(baseline,SharedFactReuse.activeFlightKeys());
    }
}
