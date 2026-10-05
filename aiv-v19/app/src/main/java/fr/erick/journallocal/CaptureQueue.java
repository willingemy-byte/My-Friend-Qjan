package fr.erick.journallocal;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded observer worker; producers never perform identification or disk IO. */
final class CaptureQueue {
    interface Failure { void failed(Throwable error); }
    private static final class Entry {
        final Runnable task;
        final long timestampMs,elapsedMs,enqueuedNs=System.nanoTime();
        Entry(Runnable task,long timestampMs,long elapsedMs){this.task=task;this.timestampMs=timestampMs;this.elapsedMs=elapsedMs;}
    }
    private final ArrayBlockingQueue<Entry> queue;
    private final Runnable idle,finished;
    private final Failure failure;
    private final Thread worker;
    private final AtomicLong accepted=new AtomicLong(),completed=new AtomicLong(),rejected=new AtomicLong();
    private volatile boolean accepting=true;
    private volatile Entry active;
    private volatile long activeStartedNs;
    private volatile int highWater;

    CaptureQueue(int capacity,Runnable idle,Failure failure,Runnable finished){
        queue=new ArrayBlockingQueue<>(capacity);this.idle=idle;this.failure=failure;this.finished=finished;
        worker=new Thread(this::run,"aiv-network-observer");worker.start();
    }
    synchronized boolean offer(Runnable task,long timestampMs,long elapsedMs){
        if(!accepting||!queue.offer(new Entry(task,timestampMs,elapsedMs))){rejected.incrementAndGet();return false;}
        accepted.incrementAndGet();highWater=Math.max(highWater,queue.size());return true;
    }
    boolean isWorker(){return Thread.currentThread()==worker;}
    long timestampMs(){Entry e=active;return e==null?System.currentTimeMillis():e.timestampMs;}
    long elapsedMs(){Entry e=active;return e==null?-1:e.elapsedMs;}
    long oldestAgeMs(){
        Entry e=active;if(e==null)e=queue.peek();
        return e==null?0:TimeUnit.NANOSECONDS.toMillis(Math.max(0,System.nanoTime()-e.enqueuedNs));
    }
    long stalledAgeMs(){
        Entry e=active;long started=e==null?0:activeStartedNs;
        if(e==null){e=queue.peek();if(e!=null)started=e.enqueuedNs;}
        return e==null?0:TimeUnit.NANOSECONDS.toMillis(Math.max(0,System.nanoTime()-started));
    }
    long accepted(){return accepted.get();}
    long completed(){return completed.get();}
    long rejected(){return rejected.get();}
    int highWater(){return highWater;}
    synchronized void finish(){accepting=false;} // drain accepted observations; do not interrupt a write
    boolean await(long timeoutMs)throws InterruptedException{worker.join(timeoutMs);return !worker.isAlive();}
    private void run(){
        try{
            while(accepting||!queue.isEmpty()){
                Entry e=queue.poll(25,TimeUnit.MILLISECONDS);
                if(e!=null){
                    activeStartedNs=System.nanoTime();active=e;
                    try{e.task.run();}catch(Throwable error){failure.failed(error);}
                    finally{completed.incrementAndGet();active=null;}
                }
                if(accepting){try{idle.run();}catch(Throwable error){failure.failed(error);}}
            }
        }catch(InterruptedException error){Thread.currentThread().interrupt();failure.failed(error);}
        finally{accepting=false;try{finished.run();}catch(Throwable error){failure.failed(error);}}
    }
}
