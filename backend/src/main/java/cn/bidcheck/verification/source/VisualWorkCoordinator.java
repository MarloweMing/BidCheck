package cn.bidcheck.verification.source;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Workers only render/call models. Admission transactions and completion writes execute on the caller. */
public final class VisualWorkCoordinator {
    @FunctionalInterface public interface Work { Map<String,Object> run(BooleanSupplier permit); }
    public record Job(String id,Work work) {}
    private record Event(Job job,CompletableFuture<Boolean> permit,Map<String,Object> response,Throwable failure) {}
    private VisualWorkCoordinator() {}

    public static void run(List<Job> initial,int parallelism,BooleanSupplier reserve,BooleanSupplier schedule,
                           BiFunction<Job,Map<String,Object>,List<Job>> complete) {
        int capacity=Math.max(1,Math.min(8,parallelism));
        ExecutorService workers=Executors.newFixedThreadPool(capacity,r->{Thread t=new Thread(r,"bidcheck-visual");t.setDaemon(true);return t;});
        BlockingQueue<Event> events=new ArrayBlockingQueue<>(capacity*3);
        AtomicBoolean stopped=new AtomicBoolean();Set<CompletableFuture<Boolean>> handshakes=ConcurrentHashMap.newKeySet();
        Deque<Job> pending=new ArrayDeque<>(initial);Map<String,Future<?>> active=new LinkedHashMap<>();Set<String> seen=new HashSet<>();
        try {
            while(!pending.isEmpty()||!active.isEmpty()) {
                interrupted();if(!schedule.getAsBoolean())pending.clear();
                while(active.size()<capacity&&!pending.isEmpty()) {
                    Job job=pending.removeFirst();if(!seen.add(job.id()))continue;
                    Future<?> running=workers.submit(()->{
                        Map<String,Object> response=null;Throwable failed=null;
                        try {response=job.work().run(()->{
                            interrupted();if(stopped.get())throw new CancellationException("视觉批次已停止");
                            CompletableFuture<Boolean> permit=new CompletableFuture<>();handshakes.add(permit);
                            try {
                                events.put(new Event(job,permit,null,null));
                                boolean allowed=permit.get();interrupted();return allowed;
                            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException("视觉请求等待已中断");}
                            catch(ExecutionException e){throw propagate(e.getCause());}
                            finally{handshakes.remove(permit);}
                        });}catch(Throwable error){failed=error;}
                        if(!stopped.get())try{events.put(new Event(job,null,response,failed));}
                        catch(InterruptedException e){Thread.currentThread().interrupt();}
                    });
                    active.put(job.id(),running);
                }
                if(active.isEmpty())continue;
                Event event=events.poll(250,TimeUnit.MILLISECONDS);if(event==null)continue;
                interrupted();
                if(event.permit()!=null){
                    try{event.permit().complete(reserve.getAsBoolean());}
                    catch(Throwable error){event.permit().completeExceptionally(error);throw propagate(error);}
                }else {
                    active.remove(event.job().id());if(event.failure()!=null)throw propagate(event.failure());
                    List<Job> follow=complete.apply(event.job(),event.response());if(follow!=null)pending.addAll(follow);
                }
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException("视觉批次已中断");}
        finally {
            stopped.set(true);handshakes.forEach(p->p.completeExceptionally(new CancellationException("视觉批次已停止")));
            active.values().forEach(f->f.cancel(true));workers.shutdownNow();events.clear();pending.clear();
            boolean interrupted=Thread.interrupted();
            try{workers.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;}
            finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private static void interrupted(){if(Thread.currentThread().isInterrupted())throw new CancellationException("视觉批次已中断");}
    private static RuntimeException propagate(Throwable error){
        if(error instanceof Error fatal)throw fatal;
        if(error instanceof RuntimeException runtime)return runtime;
        CancellationException stopped=new CancellationException("视觉执行未完成，已停止批次");stopped.initCause(error);return stopped;
    }
}
