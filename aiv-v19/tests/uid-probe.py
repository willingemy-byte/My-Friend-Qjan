#!/usr/bin/env python3
"""Production owner lookup scheduler and observer queue; synthetic host replies."""
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/fr/erick/journallocal'
probe=r'''package fr.erick.journallocal;
import java.util.concurrent.*;
public class UidProbeTest {
 static class Clock implements UidProbe.Clock{volatile long now;public long elapsed(){return now;}public long wall(){return 1000+now;}}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static UidProbe.Ticket open(UidProbe p,String id){return p.open(id,17,"10.203.0.1",45000,"192.0.2.1",53,true);}
 public static void main(String[] args)throws Exception{
  Clock c=new Clock();int[] calls={0},uid={-1};boolean[] fail={false};
  UidProbe p=new UidProbe(2048,c,t->{calls[0]++;if(fail[0])throw new SecurityException("fixture");return uid[0];},false);
  UidProbe.Ticket first=open(p,"first");p.pump();c.now=1;p.pump();c.now=2;p.pump();check(calls[0]==1,"burst consumed retries");
  uid[0]=12345;c.now=25;p.pump();check(first.snapshot().uid==12345&&first.snapshot().attempts==2&&first.snapshot().observedMs==1025,"late owner UID/time missing");
  first.close();check(first.snapshot().uid==12345&&first.snapshot().closed,"closure erased an already observed UID");p.finish();
  c=new Clock();calls[0]=0;uid[0]=-1;fail[0]=true;p=new UidProbe(2048,c,t->{calls[0]++;throw new SecurityException("fixture");},false);first=open(p,"retry");
  for(long ms:new long[]{0,1,2,25,100,300,750,1500,3000,6000,10000,12000}){c.now=ms;p.pump();}
  check(calls[0]==8&&first.snapshot().status.equals("EXHAUSTED")&&first.snapshot().error.equals("SecurityException")&&p.pending()==0,"retry/error not bounded");p.finish();
  c=new Clock();calls[0]=0;p=new UidProbe(2,c,t->{calls[0]++;return 12345;},false);
  first=open(p,"closed");first.close();UidProbe.Ticket unsupported=p.open("api26",17,"10.203.0.1",1,"192.0.2.1",53,false);unsupported.close();
  check(unsupported.snapshot().status.equals("UNSUPPORTED"),"closure overwrote unsupported status");
  UidProbe.Ticket second=open(p,"live");UidProbe.Ticket full=open(p,"full");check(full.snapshot().status.equals("QUEUE_FULL"),"capacity exceeded");
  p.pump();check(calls[0]==1&&first.snapshot().uid<0&&second.snapshot().uid==12345&&p.pending()==0,"closed tuple queried or live tuple missed");p.finish();
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);Clock real=new Clock();
  p=new UidProbe(2,real,t->{entered.countDown();if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException("deadline");return t.key.equals("same-tuple-old")?12345:67890;},true);
  first=open(p,"same-tuple-old");check(entered.await(1,TimeUnit.SECONDS),"owner worker did not start");first.close();second=open(p,"same-tuple-new");release.countDown();
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(second.snapshot().uid<0&&System.nanoTime()<deadline)Thread.yield();
  check(first.snapshot().uid<0&&first.snapshot().attempts==1&&second.snapshot().uid==67890,"late result attributed to a reused tuple");p.finish();check(p.await(1000),"owner worker did not finish");
  CountDownLatch writing=new CountDownLatch(1),written=new CountDownLatch(1);Throwable[] error={null};
  CaptureQueue q=new CaptureQueue(16,()->{},e->error[0]=e,()->{});
  q.offer(()->{writing.countDown();try{written.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}},1000,0);
  check(writing.await(1,TimeUnit.SECONDS),"journal gate missing");Thread.sleep(50);check(q.stalledAgeMs()>=40,"blocked observer not detected");written.countDown();
  q.finish();check(q.await(1000)&&q.completed()==1&&q.stalledAgeMs()==0&&error[0]==null,"observer did not recover/drain");
  System.out.println("PASS production UID worker: bounded retries, independent lookup, closure, reused tuple, API/capacity status; observer stall and recovery");
 }
}'''
with tempfile.TemporaryDirectory() as folder:
    tmp=Path(folder);(tmp/'UidProbeTest.java').write_text(probe)
    sources=[JAVA/(n+'.java') for n in ('UidProbe','IdentityRetry','CaptureQueue')]
    subprocess.run(['javac','-encoding','UTF-8','-Xlint:all','-d',str(tmp),*map(str,sources),str(tmp/'UidProbeTest.java')],check=True,timeout=30)
    subprocess.run(['java','-cp',str(tmp),'fr.erick.journallocal.UidProbeTest'],check=True,timeout=10)
