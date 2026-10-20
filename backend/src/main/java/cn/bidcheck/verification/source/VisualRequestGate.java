package cn.bidcheck.verification.source;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** One JVM-wide gate shared by both single-page and context requests, including image preparation. */
final class VisualRequestGate {
    static final VisualRequestGate GLOBAL=new VisualRequestGate();
    private final Deque<Ticket> waiting=new ArrayDeque<>();
    private final Set<Ticket> active=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String,ServiceState> services=new HashMap<>();
    private static final class ServiceState {int failures;long until;}
    private static final class Ticket {final int limit,windowLimit;final boolean window;final String service;Ticket(int limit,int windowLimit,boolean window,String service){this.limit=limit;this.windowLimit=windowLimit;this.window=window;this.service=service;}}
    synchronized Permit acquire(int limit,int maxPending,long timeoutMillis)throws InterruptedException {
        return acquire(limit,limit,false,maxPending,timeoutMillis);
    }
    synchronized Permit acquire(int limit,int windowLimit,boolean window,int maxPending,long timeoutMillis)throws InterruptedException {
        return acquire(limit,windowLimit,window,maxPending,timeoutMillis,"");
    }
    synchronized Permit acquire(int limit,int windowLimit,boolean window,int maxPending,long timeoutMillis,String service)throws InterruptedException {
        Ticket ticket=new Ticket(limit,windowLimit,window,service);long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        if(waiting.size()>=maxPending)return null;
        waiting.addLast(ticket);
        try {
            while(true){
                ServiceState own=services.get(service);
                // A long provider hint defers this unit instead of occupying a waiting thread until timeout.
                if(own!=null&&own.until>end)return null;
                Ticket eligible=null;
                for(Ticket candidate:waiting){
                    ServiceState state=services.get(candidate.service);if(state!=null&&state.until>System.nanoTime())continue;
                    int total=candidate.limit,windows=candidate.windowLimit,count=0;
                    for(Ticket running:active){total=Math.min(total,running.limit);windows=Math.min(windows,running.windowLimit);if(running.window)count++;}
                    if(active.size()<total&&(!candidate.window||count<windows)){eligible=candidate;break;}
                }
                if(eligible==ticket){waiting.remove(ticket);active.add(ticket);notifyAll();return new Permit(ticket);}
                long remaining=end-System.nanoTime();if(remaining<=0)return null;
                if(own!=null&&own.until>System.nanoTime())remaining=Math.min(remaining,Math.max(1,own.until-System.nanoTime()));
                TimeUnit.NANOSECONDS.timedWait(this,remaining);
            }
        }finally{if(waiting.remove(ticket))notifyAll();}
    }
    /** Provider capacity failures share backoff; five consecutive failures open a 15 second circuit. */
    synchronized long failed(String service,long retryAfterMillis){
        ServiceState state=services.computeIfAbsent(service,ignored->new ServiceState());state.failures=Math.min(5,state.failures+1);
        long backoff=state.failures>=5?15_000:Math.min(4_000,250L<<(state.failures-1));
        long delay=Math.max(backoff,retryAfterMillis),now=System.nanoTime(),nanos=TimeUnit.MILLISECONDS.toNanos(delay);
        long until=nanos>Long.MAX_VALUE-now?Long.MAX_VALUE:now+nanos;state.until=Math.max(state.until,until);notifyAll();return retryAfterMillis(service);
    }
    synchronized void succeeded(String service){ServiceState state=services.get(service);if(state!=null){state.failures=0;if(state.until<=System.nanoTime())services.remove(service);}}
    synchronized long retryAfterMillis(String service){ServiceState state=services.get(service);if(state==null)return 0;long left=state.until-System.nanoTime();return left<=0?0:1+TimeUnit.NANOSECONDS.toMillis(left-1);}
    /** Recheck after rendering, before the caller reserves a network attempt. */
    synchronized boolean awaitReady(String service,long timeoutMillis)throws InterruptedException{
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(true){ServiceState state=services.get(service);long now=System.nanoTime();if(state==null||state.until<=now)return true;
            if(state.until>end)return false;long left=end-now;if(left<=0)return false;TimeUnit.NANOSECONDS.timedWait(this,Math.min(left,state.until-now));}
    }
    final class Permit implements AutoCloseable {
        private final Ticket ticket;private boolean closed;
        private Permit(Ticket ticket){this.ticket=ticket;}
        public void close(){synchronized(VisualRequestGate.this){if(!closed){closed=true;active.remove(ticket);VisualRequestGate.this.notifyAll();}}}
    }
}
