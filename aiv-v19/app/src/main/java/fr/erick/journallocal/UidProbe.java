package fr.erick.journallocal;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded socket-owner lookups, independent of journal, package and Keystore IO. */
final class UidProbe {
    interface Clock { long elapsed(); long wall(); }
    interface Resolver { int lookup(Ticket ticket) throws Exception; }
    static final class Snapshot {
        final int uid,attempts; final long observedMs; final boolean closed;
        final String status,error;
        Snapshot(int uid,int attempts,long observedMs,boolean closed,String status,String error){
            this.uid=uid;this.attempts=attempts;this.observedMs=observedMs;this.closed=closed;this.status=status;this.error=error;
        }
    }
    static final class Ticket {
        final String key,local,remote; final int protocol,localPort,remotePort;
        final IdentityRetry retry; final AtomicBoolean retired=new AtomicBoolean();
        private int uid=-1,attempts; private long observedMs;
        private boolean closed; private String status="PENDING",error="";
        Ticket(String key,int protocol,String local,int localPort,String remote,int remotePort,long elapsed){
            this.key=key;this.protocol=protocol;this.local=local;this.localPort=localPort;this.remote=remote;this.remotePort=remotePort;retry=new IdentityRetry(elapsed);
        }
        synchronized Snapshot snapshot(){return new Snapshot(uid,attempts,observedMs,closed,status,error);}
        synchronized void close(){closed=true;if(uid<0&&"PENDING".equals(status)){status="CLOSED_UNRESOLVED";retry.finish();}}
    }
    private final ConcurrentLinkedDeque<Ticket> pending=new ConcurrentLinkedDeque<>();
    private final AtomicInteger allocated=new AtomicInteger();
    private final int capacity; private final Clock clock; private final Resolver resolver;
    private final Object wake=new Object(); private final Thread worker;
    private volatile boolean accepting=true; private long generation;
    UidProbe(int capacity,Clock clock,Resolver resolver,boolean threaded){
        this.capacity=capacity;this.clock=clock;this.resolver=resolver;
        worker=threaded?new Thread(this::run,"aiv-network-owner"):null;
        if(worker!=null){worker.setDaemon(true);worker.start();}
    }
    Ticket open(String key,int protocol,String local,int localPort,String remote,int remotePort,boolean supported){
        Ticket t=new Ticket(key,protocol,local,localPort,remote,remotePort,clock.elapsed());
        synchronized(wake){
            if(!accepting){t.status="STOPPED";return t;}
            if(!supported){t.status="UNSUPPORTED";return t;}
            if(allocated.get()>=capacity){t.status="QUEUE_FULL";return t;}
            allocated.incrementAndGet();pending.addFirst(t);generation++;wake.notifyAll();
        }
        return t;
    }
    // Initial requests go first; unsuccessful retries return to the tail.
    void pump(){
        int count=Math.min(16,allocated.get());long started=clock.elapsed();
        for(int i=0;i<count&&accepting;i++){
            Ticket t=pending.pollFirst();if(t==null)break;
            boolean query;
            synchronized(t){query=!t.closed&&"PENDING".equals(t.status)&&t.retry.begin(clock.elapsed());if(query)t.attempts=t.retry.attempts();}
            if(query){
                int uid=-1;String error="";
                try{uid=resolver.lookup(t);}catch(Exception e){error=e.getClass().getSimpleName();}
                synchronized(t){
                    // A result returned after closure/revocation is never an observation of this flow.
                    if(accepting&&!t.closed){t.error=error;if(uid>=0){t.uid=uid;t.observedMs=clock.wall();t.status="UID_OBSERVED";t.retry.finish();}}
                    else t.close();
                }
            }
            boolean retry;
            synchronized(t){
                if("PENDING".equals(t.status)&&t.retry.done(clock.elapsed()))t.status="EXHAUSTED";
                retry=accepting&&!t.closed&&"PENDING".equals(t.status);
            }
            synchronized(wake){if(retry&&accepting)pending.addLast(t);else retire(t);}
            if(clock.elapsed()-started>=4)break;
        }
    }
    private void retire(Ticket t){if(t.retired.compareAndSet(false,true))allocated.decrementAndGet();}
    int pending(){return allocated.get();}
    void finish(){
        synchronized(wake){
            accepting=false;Ticket t;while((t=pending.pollFirst())!=null){t.close();retire(t);}wake.notifyAll();
        }
    }
    boolean await(long timeoutMs)throws InterruptedException{if(worker==null)return true;worker.join(timeoutMs);return !worker.isAlive();}
    private void run(){
        try{
            while(accepting){
                long before;synchronized(wake){before=generation;}
                pump();synchronized(wake){if(accepting&&before==generation)wake.wait(25);}
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();finish();}
    }
}
