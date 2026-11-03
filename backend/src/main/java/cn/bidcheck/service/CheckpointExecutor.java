package cn.bidcheck.service;

import javax.annotation.PreDestroy;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Shared computation capacity; each caller retains ownership of ordered persistence. */
@Service
public final class CheckpointExecutor {
    private final int concurrency;
    private final ExecutorService workers;

    public CheckpointExecutor(@Value("${bidcheck.check-concurrency:3}") int concurrency) {
        this.concurrency=Math.max(1,Math.min(4,concurrency));
        AtomicInteger sequence=new AtomicInteger();
        workers=Executors.newFixedThreadPool(this.concurrency,task->{
            Thread thread=new Thread(task,"bidcheck-checkpoint-"+sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public int parallelism() { return concurrency; }

    /** Refill available capacity after each completion; persistence remains on the caller thread. */
    public <T> void forEachCompleted(int count,IntFunction<T> evaluate,BiConsumer<Integer,T> consume)
            throws InterruptedIOException {
        if(count<0)throw new IllegalArgumentException("Checkpoint count must not be negative");
        Objects.requireNonNull(evaluate,"evaluate");Objects.requireNonNull(consume,"consume");
        ExecutorCompletionService<Indexed<T>> ready=new ExecutorCompletionService<>(workers);
        Set<Future<Indexed<T>>> pending=new HashSet<>();
        try {
            checkInterrupted();
            int next=0,completed=0;
            while(next<count&&pending.size()<concurrency) {
                int index=next++;
                pending.add(ready.submit(()->new Indexed<>(index,evaluate.apply(index))));
            }
            while(completed<count) {
                checkInterrupted();
                Future<Indexed<T>> future;
                Indexed<T> result;
                try {
                    while((future=ready.poll(250,java.util.concurrent.TimeUnit.MILLISECONDS))==null) {
                        checkInterrupted();
                        if(workers.isShutdown())throw new java.util.concurrent.CancellationException("Checkpoint executor stopped");
                    }
                    if(workers.isShutdown())throw new java.util.concurrent.CancellationException("Checkpoint executor stopped");
                    result=future.get();
                }
                catch(InterruptedException interrupted){throw interrupted(interrupted);}
                catch(ExecutionException failed){
                    Throwable cause=failed.getCause();
                    if(cause instanceof RuntimeException)throw ((RuntimeException) (cause));
                    if(cause instanceof Error)throw ((Error) (cause));
                    throw new IllegalStateException("Checkpoint evaluation failed",cause);
                }
                pending.remove(future);
                checkInterrupted();
                consume.accept(result.index(),result.value());
                checkInterrupted();
                completed++;
                if(next<count){int index=next++;pending.add(ready.submit(()->new Indexed<>(index,evaluate.apply(index))));}
            }
        } finally {for(Future<?> future:pending)future.cancel(true);}
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Indexed<T> {
        private final int index;
        private final T value;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Indexed(@com.fasterxml.jackson.annotation.JsonProperty("index") int index, @com.fasterxml.jackson.annotation.JsonProperty("value") T value) { this.index = index; this.value = value; }
        public int index() { return index; }
        public T value() { return value; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Indexed that = (Indexed) other; return java.util.Objects.equals(this.index, that.index) && java.util.Objects.equals(this.value, that.value); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.index); hash = 31 * hash + java.util.Objects.hashCode(this.value); return hash; }
        @Override public String toString() { return "Indexed[" + "index=" + index + ", value=" + value + "]"; }
 }

    /** At most one window per invocation is submitted, including results awaiting consumption. */
    public <T> void forEachOrdered(int count,IntFunction<T> evaluate,BiConsumer<Integer,T> consume)
            throws InterruptedIOException {
        if(count<0) throw new IllegalArgumentException("Checkpoint count must not be negative");
        Objects.requireNonNull(evaluate,"evaluate");
        Objects.requireNonNull(consume,"consume");
        ArrayDeque<Future<T>> inFlight=new ArrayDeque<>();
        try {
            checkInterrupted();
            int next=0;
            while(next<count&&inFlight.size()<concurrency) {
                checkInterrupted();
                int index=next++;
                inFlight.addLast(workers.submit(()->evaluate.apply(index)));
            }
            for(int index=0;index<count;index++) {
                checkInterrupted();
                T value;
                try { value=inFlight.getFirst().get(); }
                catch(InterruptedException interrupted) { throw interrupted(interrupted); }
                catch(ExecutionException failed) {
                    Throwable cause=failed.getCause();
                    if(cause instanceof RuntimeException) throw ((RuntimeException) (cause));
                    if(cause instanceof Error) throw ((Error) (cause));
                    throw new IllegalStateException("Checkpoint evaluation failed",cause);
                }
                checkInterrupted();
                consume.accept(index,value);
                checkInterrupted();
                inFlight.removeFirst();
                if(next<count) {
                    int submitted=next++;
                    inFlight.addLast(workers.submit(()->evaluate.apply(submitted)));
                }
            }
        } finally {
            // Includes a currently awaited future and every unconsumed later result.
            for(Future<T> future:inFlight) future.cancel(true);
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted()) throw interrupted(null);
    }
    private static InterruptedIOException interrupted(InterruptedException cause) {
        Thread.currentThread().interrupt();
        InterruptedIOException error=new InterruptedIOException("Checkpoint processing interrupted");
        if(cause!=null) error.initCause(cause);
        return error;
    }

    @PreDestroy
    public void shutdown() {
        // shutdownNow returns queued tasks without completing their futures; release their callers too.
        for(Runnable queued:workers.shutdownNow()) if(queued instanceof Future<?>) ((Future<?>) (queued)).cancel(true);
    }
}
