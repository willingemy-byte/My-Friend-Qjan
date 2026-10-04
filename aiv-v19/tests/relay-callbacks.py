#!/usr/bin/env python3
"""Actual relay + JNI + NetworkCaptureService, host SQLite and loopback UDP/TCP.
Android owner lookup/Keystore/network binding are controlled fixtures, not a phone.
--baseline verifies the old stall against the pinned 2.0.6 service from Git.
--reproduce-short-flow isolates the 2.0.7 missed lookup window with synthetic
callbacks queued behind a journal write; it is a diagnosis, not a fix test.
"""
from pathlib import Path
import os,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/fr/erick/journallocal'
sdk=Path(os.environ['AIV_ANDROID_JAR'])
jars=os.pathsep.join(os.environ[k] for k in ('AIV_JSON_JAR','AIV_SQLITE_JAR'))
baseline='--baseline' in __import__('sys').argv
short_flow='--reproduce-short-flow' in __import__('sys').argv
if baseline and short_flow:
    raise SystemExit('--reproduce-short-flow requires the 2.0.7 service, without --baseline')

apps={
'EventStore.java':r'''package fr.erick.journallocal;
import java.sql.*;import java.util.*;import java.util.concurrent.*;import org.json.*;
public final class EventStore {
 public static String lastError="";static EventStore instance;final List<JSONObject> events=new CopyOnWriteArrayList<>();final Connection db;
 EventStore(String path)throws Exception{Class.forName("org.sqlite.JDBC");db=DriverManager.getConnection("jdbc:sqlite:"+path);db.createStatement().execute("CREATE TABLE events(id INTEGER PRIMARY KEY,timestamp_ms INTEGER,payload TEXT)");instance=this;}
 public static EventStore get(Object c){return instance;}
 public static JSONObject object(Object... kv){JSONObject d=new JSONObject();for(int i=0;i<kv.length;i+=2)d.put(String.valueOf(kv[i]),kv[i+1]);return d;}
 public boolean add(String c,String a,String action,String dest,String transport,String source,JSONObject d){return addObserved(c,a,action,dest,transport,source,d,System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime());}
 public boolean addObserved(String c,String a,String action,String dest,String transport,String source,JSONObject d,long timestamp,long elapsed){
  Probe.gate("journal");JSONObject e=object("timestamp_ms",timestamp,"elapsed_ms",elapsed,"persisted_at_ms",System.currentTimeMillis(),"app",a,"category",c,"details",new JSONObject(d.toString()));
  try(PreparedStatement p=db.prepareStatement("INSERT INTO events(timestamp_ms,payload) VALUES(?,?)")){p.setLong(1,timestamp);p.setString(2,e.toString());p.executeUpdate();events.add(e);return true;}catch(Exception error){throw new IllegalStateException(error);}
 }
}''',
'PinVault.java':r'''package fr.erick.journallocal;import java.security.*;final class PinVault{public String securityLevel="HOST_FIXTURE";static int signatures;static PinVault vpnIdentity(){return new PinVault();}String keyId(){return "host-key";}byte[] sign(byte[] b)throws GeneralSecurityException{signatures++;Probe.gate("sign");return new byte[64];}}''',
'ProductAccess.java':r'''package fr.erick.journallocal;import org.json.*;final class ProductAccess{static boolean paidEnabled(Object c){return true;}static JSONObject status(Object c){return EventStore.object("source","HOST_FIXTURE");}}''',
'AppIdentity.java':r'''package fr.erick.journallocal;import org.json.*;final class AppIdentity{static JSONObject forUid(Object c,int uid,String[] p){return EventStore.object("app_identity_id","host-app-"+uid,"network_actor_identity_id","host-uid-"+uid);}}''',
'SecurityContext.java':r'''package fr.erick.journallocal;import org.json.*;final class SecurityContext{static JSONArray forPackages(Object c,JSONArray p){return new JSONArray();}}''',
'ReferenceCatalog.java':r'''package fr.erick.journallocal;import org.json.*;final class ReferenceCatalog{static ReferenceCatalog get(Object c){return new ReferenceCatalog();}JSONArray network(String h,String k){return new JSONArray();}String revision(){return "HOST_FIXTURE";}}''',
'Continuous.java':r'''package fr.erick.journallocal;import android.content.SharedPreferences;import java.lang.reflect.Proxy;final class Continuous{
 static boolean vpnEnabled=true;static boolean enabled(Object c){return true;}
 static SharedPreferences prefs(Object c){
  Object editor=Proxy.newProxyInstance(Continuous.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(p,m,a)->{if(m.getName().equals("putBoolean")){if(a[0].equals("vpn_enabled"))vpnEnabled=(Boolean)a[1];return p;}return null;});
  return (SharedPreferences)Proxy.newProxyInstance(Continuous.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(p,m,a)->m.getName().equals("edit")?editor:m.getName().equals("getBoolean")?vpnEnabled:null);
 }
}''',
'ChainStore.java':r'''package fr.erick.journallocal;import java.security.*;final class ChainStore{static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x&255));return s.toString();}}''',
'MainActivity.java':r'''package fr.erick.journallocal;public class MainActivity extends android.app.Activity{}'''
}
runtime={
'android/content/Context.java':r'''package android.content;public class Context{public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}public String getPackageName(){return "com.allinvisible.aiv";}}''',
'android/net/VpnService.java':r'''package android.net;public class VpnService extends android.content.Context{public boolean protect(int fd){return true;}public void onDestroy(){}public void stopForeground(int f){}}''',
'android/net/ConnectivityManager.java':r'''package android.net;import java.net.*;public class ConnectivityManager{public int getConnectionOwnerUid(int p,InetSocketAddress local,InetSocketAddress remote){return fr.erick.journallocal.Probe.lookupUid();}}''',
'android/net/Network.java':r'''package android.net;import java.io.*;public class Network{public Network(int id){}public void bindSocket(FileDescriptor fd)throws IOException{}}''',
'android/os/Build.java':r'''package android.os;public class Build{public static class VERSION{public static int SDK_INT=35;}}''',
'android/os/SystemClock.java':r'''package android.os;public class SystemClock{public static long elapsedRealtime(){return System.nanoTime()/1000000;}}''',
'android/os/Handler.java':r'''package android.os;public class Handler{public Handler(){}public boolean post(Runnable r){r.run();return true;}}''',
'android/os/ParcelFileDescriptor.java':r'''package android.os;import java.io.*;public class ParcelFileDescriptor implements AutoCloseable{public static ParcelFileDescriptor fromFd(int fd)throws IOException{return new ParcelFileDescriptor();}public FileDescriptor getFileDescriptor(){return new FileDescriptor();}public void close()throws IOException{}}''',
'android/content/pm/ApplicationInfo.java':r'''package android.content.pm;public class ApplicationInfo{public int flags;}''',
'android/content/pm/PackageManager.java':r'''package android.content.pm;public class PackageManager{public String[] getPackagesForUid(int uid){return new String[]{"host.example"};}public ApplicationInfo getApplicationInfo(String p,int flags)throws NameNotFoundException{return new ApplicationInfo();}public CharSequence getApplicationLabel(ApplicationInfo app){return "Host example";}public static class NameNotFoundException extends Exception{private static final long serialVersionUID=1;}}'''
}
probe=r'''package fr.erick.journallocal;
import java.net.*;import java.io.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;import java.sql.*;import org.json.*;
public class Probe {
 static native int[] tun();static native void write(int fd,byte[] b);static native byte[] read(int fd,int timeout);static native void close(int fd);
 static CountDownLatch blocked,release;static String mode;static final boolean baseline=__BASELINE__;
 static final java.util.concurrent.atomic.AtomicInteger uidCalls=new java.util.concurrent.atomic.AtomicInteger();
 public static int lookupUid(){uidCalls.incrementAndGet();gate("uid");return 12345;}
 public static void gate(String name){if(name.equals(mode)&&blocked.getCount()>0){blocked.countDown();try{if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("fixture gate deadline");}catch(InterruptedException e){throw new IllegalStateException(e);}}}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void set(Object o,String name,Object value)throws Exception{Field f=NetworkCaptureService.class.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
 static Object call(Object o,String name,Class<?>[] types,Object... values)throws Exception{Method m=NetworkCaptureService.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,values);}
 static byte[] udp(int port){byte[] b=new byte[44];b[0]=0x45;b[2]=0;b[3]=44;b[8]=64;b[9]=17;b[12]=10;b[13]=(byte)203;b[15]=1;b[16]=127;b[19]=1;b[20]=(byte)(45000>>8);b[21]=(byte)45000;b[22]=(byte)(port>>8);b[23]=(byte)port;b[25]=24;for(int i=28;i<b.length;i++)b[i]=(byte)i;return b;}
 static void put16(byte[] b,int at,int value){b[at]=(byte)(value>>8);b[at+1]=(byte)value;}
 static void put32(byte[] b,int at,long value){put16(b,at,(int)(value>>16));put16(b,at+2,(int)value);}
 static int u16(byte[] b,int at){return (b[at]&255)*256+(b[at+1]&255);}
 static long u32(byte[] b,int at){return ((long)u16(b,at)<<16)|u16(b,at+2);}
 static int checksum(byte[] b,int at,int length,int sum){for(int i=at;i<at+length;i+=2)sum+=(b[i]&255)*256+(i+1<at+length?(b[i+1]&255):0);while((sum>>>16)!=0)sum=(sum&65535)+(sum>>>16);return (~sum)&65535;}
 static byte[] tcp(int port,long seq,long ack,int flags,byte[] payload){
  byte[] b=new byte[40+payload.length];b[0]=0x45;put16(b,2,b.length);b[8]=64;b[9]=6;b[12]=10;b[13]=(byte)203;b[15]=1;b[16]=127;b[19]=1;
  put16(b,20,45001);put16(b,22,port);put32(b,24,seq);put32(b,28,ack);b[32]=0x50;b[33]=(byte)flags;put16(b,34,65535);System.arraycopy(payload,0,b,40,payload.length);
  put16(b,10,checksum(b,0,20,0));put16(b,36,checksum(b,20,b.length-20,u16(b,12)+u16(b,14)+u16(b,16)+u16(b,18)+6+b.length-20));return b;
 }
 static long[] tcpExchange(int fd,int port,byte[] synAck)throws Exception{
  check(synAck.length>=40&&synAck[9]==6&&(synAck[33]&18)==18&&u32(synAck,28)==5001,"TCP handshake response");
  long nextReply=u32(synAck,24)+1,seq=5001,tx=40,rx=synAck.length;byte[] empty=new byte[0];byte[] ack=tcp(port,seq,nextReply,16,empty);write(fd,ack);tx+=ack.length;
  byte[] expected=new byte[16384];for(int i=0;i<expected.length;i++)expected[i]=(byte)(i*31+7);
  ByteArrayOutputStream echoed=new ByteArrayOutputStream();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
  for(int offset=0;offset<expected.length;){int length=Math.min(1200,expected.length-offset);byte[] segment=tcp(port,seq,nextReply,24,Arrays.copyOfRange(expected,offset,offset+length));write(fd,segment);tx+=segment.length;seq+=length;offset+=length;}
  while(echoed.size()<expected.length&&System.nanoTime()<deadline){
   byte[] p=read(fd,400);check(p!=null,"TCP echo stalled while observer was blocked");rx+=p.length;check(p.length>=40&&p[9]==6&&(p[33]&4)==0,"TCP reset/malformed reply");
   int header=(p[0]&15)*4+((p[32]&255)>>>4)*4;int n=p.length-header;
   if(n>0){check(u32(p,24)==nextReply,"TCP payload sequence changed");echoed.write(p,header,n);nextReply+=n;ack=tcp(port,seq,nextReply,16,empty);write(fd,ack);tx+=ack.length;}
  }
  check(Arrays.equals(expected,echoed.toByteArray()),"TCP 16 KiB echo changed/lost bytes");return new long[]{tx,rx};
 }
 static void one(String scenario,String path,boolean tcpFlow)throws Exception{
  mode=scenario;blocked=new CountDownLatch(1);release=new CountDownLatch(1);uidCalls.set(0);PinVault.signatures=0;Continuous.vpnEnabled=true;
  NetworkCaptureService service=new NetworkCaptureService();EventStore store=new EventStore(path+"-"+scenario+(tcpFlow?"-tcp":"-udp")+".sqlite");set(service,"store",store);set(service,"connectivity",Class.forName("android.net.ConnectivityManager").getConstructor().newInstance());set(service,"session","host-session");
  set(service,"main",Class.forName("android.os.Handler").getConstructor().newInstance());
  Class<?> configClass=Class.forName("fr.erick.journallocal.NetworkCaptureService$Config");Constructor<?> constructor=configClass.getDeclaredConstructor();constructor.setAccessible(true);Object config=constructor.newInstance();
  Field net=configClass.getDeclaredField("network");net.setAccessible(true);net.set(config,Class.forName("android.net.Network").getConstructor(int.class).newInstance(1));Field transport=configClass.getDeclaredField("transport");transport.setAccessible(true);transport.set(config,"Loopback");set(service,"current",config);
  if(!baseline){call(service,"startObservationWorker",new Class<?>[]{});call(service,"beginObservationSession",new Class<?>[]{String.class,String.class},"host-session","Loopback");}
  if(scenario.equals("sign")){set(service,"vpnVault",new PinVault());set(service,"vpnKeyId","host-key");}
  int[] descriptors=tun();DatagramSocket server=new DatagramSocket(0,InetAddress.getLoopbackAddress());server.setSoTimeout(200);
  ServerSocket tcpServer=new ServerSocket(0,1,InetAddress.getLoopbackAddress());tcpServer.setSoTimeout(3000);
  Thread echo=new Thread(()->{try{
   if(tcpFlow){try(Socket client=tcpServer.accept()){client.setSoTimeout(200);byte[] buf=new byte[4096];while(!tcpServer.isClosed()){try{int n=client.getInputStream().read(buf);if(n<0)break;client.getOutputStream().write(buf,0,n);}catch(SocketTimeoutException ignored){}}}}
   else{while(!server.isClosed()){try{byte[] buf=new byte[512];DatagramPacket p=new DatagramPacket(buf,buf.length);server.receive(p);server.send(p);}catch(SocketTimeoutException ignored){}}}
  }catch(Exception e){if(!server.isClosed()&&!tcpServer.isClosed())throw new RuntimeException(e);}});echo.start();
  int[] result={-999};Throwable[] error={null};Thread nativeThread=new Thread(()->{try{result[0]=(Integer)call(service,"runNative",new Class<?>[]{int.class},descriptors[0]);}catch(Throwable e){error[0]=e;}});set(service,"engine",nativeThread);nativeThread.start();
  long captured=System.currentTimeMillis();write(descriptors[1],tcpFlow?tcp(tcpServer.getLocalPort(),5000,0,2,new byte[0]):udp(server.getLocalPort()));check(blocked.await(2,TimeUnit.SECONDS),"observer gate was not reached: "+scenario);
  byte[] received=read(descriptors[1],600);
  if(baseline){check(received==null,"baseline did not reproduce relay stall");release.countDown();received=read(descriptors[1],2000);}
  else{check(received!=null,"observer stall blocked native relay: "+scenario);}
  long[] tcpBytes=tcpFlow?tcpExchange(descriptors[1],tcpServer.getLocalPort(),received):null;
  if(!tcpFlow)check(received.length==44&&received[28]==28&&received[43]==43,"relay payload altered");
  if(!baseline&&scenario.equals("watchdog")){
   // This scenario gates the journal (below), while the real native watchdog remains runnable.
   nativeThread.join(3600);check(!nativeThread.isAlive(),"watchdog left relay active with stale observer");check(NetworkCaptureService.lastError.contains("3 secondes"),"missing stall reason");
  }else if(!baseline&&scenario.equals("overflow")){
   for(int i=0;i<4200;i++)service.onDnsQuestion(1,"host.example",1);
   nativeThread.join(900);check(!nativeThread.isAlive(),"full observer queue kept relay active");check(NetworkCaptureService.lastError.contains("saturée"),"missing overflow reason");
  }else if(!baseline&&scenario.equals("uid")){
   // Close the native connection while Android's lookup is still blocked.
   long stop=System.nanoTime();service.onDestroy();nativeThread.join(900);check(!nativeThread.isAlive(),"stop waited on owner lookup");check((System.nanoTime()-stop)/1000000<900,"stop deadline");
  }else{
   Thread.sleep(120);release.countDown();Thread.sleep(80);long stop=System.nanoTime();service.onDestroy();nativeThread.join(900);check(!nativeThread.isAlive(),"stop deadline");check((System.nanoTime()-stop)/1000000<900,"native stop delayed");
  }
  release.countDown();if(!baseline){__DRAIN_OBSERVATIONS__}
  check(error[0]==null&&result[0]==0,"native exception or error");
  if(!baseline&&(scenario.equals("watchdog")||scenario.equals("overflow")))check(!Continuous.vpnEnabled,"fatal capture can restart automatically");
  JSONObject counters=null,raw=null;for(JSONObject e:store.events){JSONObject d=e.getJSONObject("details");if("FLOW_METADATA".equals(d.optString("observation_type"))&&raw==null)raw=e;if("COUNTER_SNAPSHOT".equals(d.optString("observation_type")))counters=d;}
  check(raw!=null,"raw open missing");if(!baseline){check(raw.getJSONObject("details").getInt("uid")==-1,"raw identity was retrospectively rewritten");check(Math.abs(raw.getLong("timestamp_ms")-captured)<200,"capture timestamp changed by worker delay");}
  if(tcpFlow){check(counters!=null&&counters.getLong("tx_bytes")==tcpBytes[0]&&counters.getLong("rx_bytes")>=tcpBytes[1]&&counters.getLong("tx_packets")>2&&counters.getLong("rx_packets")>2,"TCP IP volume lost/counted twice");}
  else if(!scenario.equals("overflow")){check(counters!=null&&counters.getLong("tx_bytes")==44&&counters.getLong("rx_bytes")==44&&counters.getLong("tx_packets")==1&&counters.getLong("rx_packets")==1,"native volume lost or counted twice");}
  if(!baseline&&scenario.equals("uid")){for(JSONObject e:store.events)check(e.getJSONObject("details").optInt("uid",-1)<0,"closed tuple got stale attribution");}
  if(!baseline&&scenario.equals("sign"))check(PinVault.signatures==1,"unchanged flow was signed repeatedly: "+PinVault.signatures);
  try(ResultSet r=store.db.createStatement().executeQuery("SELECT COUNT(*) FROM events")){check(r.next()&&r.getInt(1)==store.events.size(),"SQLite row count mismatch");}
  server.close();tcpServer.close();echo.join(1000);close(descriptors[0]);close(descriptors[1]);store.db.close();
  System.out.println((baseline?"REPRODUCED baseline stall: ":"PASS actual JNI relay: ")+(tcpFlow?"TCP ":"UDP ")+scenario+", rows="+store.events.size()+", signatures="+PinVault.signatures);
 }
 __SHORT_FLOW_TEST__
 public static void main(String[] args)throws Exception{
  System.load(args[0]);__RUN_SHORT_FLOW__
  for(String scenario:(baseline?new String[]{"journal","uid","sign"}:new String[]{"journal","uid","sign","watchdog","overflow"})){
   NetworkCaptureService.lastError="";one(scenario,args[1],false);
   if(!scenario.equals("watchdog")&&!scenario.equals("overflow")){NetworkCaptureService.lastError="";one(scenario,args[1],true);}
  }
 }
}
'''
short_flow_test=r'''
 static void shortFlow(String path)throws Exception{
  // Controlled callbacks, not packets from a device. A prior flow blocks the
  // single observer in its journal write while the short flow opens and closes.
  mode="journal";blocked=new CountDownLatch(1);release=new CountDownLatch(1);uidCalls.set(0);NetworkCaptureService.lastError="";
  NetworkCaptureService service=new NetworkCaptureService();EventStore store=new EventStore(path+"-short-flow.sqlite");
  set(service,"store",store);set(service,"connectivity",Class.forName("android.net.ConnectivityManager").getConstructor().newInstance());set(service,"session","host-short-flow");
  set(service,"main",Class.forName("android.os.Handler").getConstructor().newInstance());
  call(service,"startObservationWorker",new Class<?>[]{});call(service,"beginObservationSession",new Class<?>[]{String.class,String.class},"host-short-flow","Synthetic");
  Field field=NetworkCaptureService.class.getDeclaredField("observations");field.setAccessible(true);CaptureQueue queue=(CaptureQueue)field.get(service);
  try{
   service.onFlowOpen(101,4,17,"10.203.0.1",42001,"127.0.0.1",53001);
   check(blocked.await(2,TimeUnit.SECONDS),"prior journal write did not reach gate");
   int priorLookups=uidCalls.get();check(priorLookups==1,"control flow did not exercise owner lookup");
   long openedMs=System.currentTimeMillis();service.onFlowOpen(102,4,17,"10.203.0.1",42002,"127.0.0.1",53);long openEnqueuedMs=System.currentTimeMillis();
   service.onFlowDirection(102,true,60,openedMs);
   Thread.sleep(20);long inboundMs=System.currentTimeMillis();service.onFlowDirection(102,false,205,inboundMs);long inboundEnqueuedMs=System.currentTimeMillis();
   Thread.sleep(10);service.onFlowUpdate(102,60,205,1,1,2,0,true,inboundMs);
   Thread.sleep(500);check(uidCalls.get()==priorLookups,"short flow unexpectedly got a lookup while observer was gated");
   release.countDown();queue.finish();check(queue.await(6000),"short-flow observations did not drain");
   check(queue.accepted()==queue.completed()&&queue.rejected()==0,"short-flow observations lost");
   check(uidCalls.get()==priorLookups,"closed short tuple was queried or incorrectly attributed");
   JSONObject raw=null,incoming=null,counters=null;
   for(JSONObject e:store.events){JSONObject d=e.getJSONObject("details");if(d.optLong("native_flow_id",-1)!=102)continue;
    if("FLOW_METADATA".equals(d.optString("observation_type")))raw=e;
    if("FIRST_PACKET".equals(d.optString("observation_type"))&&"entrant".equals(d.optString("direction")))incoming=e;
    if("COUNTER_SNAPSHOT".equals(d.optString("observation_type")))counters=d;
    check(d.optInt("uid",-1)==-1&&"Application non identifiée".equals(e.getString("app")),"unknown short flow received a fabricated identity");
   }
   check(raw!=null&&incoming!=null&&counters!=null,"short-flow records missing");
   check(raw.getLong("timestamp_ms")>=openedMs&&raw.getLong("timestamp_ms")<=openEnqueuedMs,"open timestamp replaced by persistence time");
   long captured=incoming.getLong("timestamp_ms"),delay=incoming.getLong("persisted_at_ms")-captured;
   check(captured>=inboundMs&&captured<=inboundEnqueuedMs&&delay>=500,"inbound capture/persistence times did not preserve journal delay");
   JSONObject d=incoming.getJSONObject("details");
   check(!d.getBoolean("closed")&&"CLOSED_UNRESOLVED".equals(d.getString("identity_status")),"mixed capture/enrichment closure states not reproduced");
   check(d.getInt("identity_attempts")==0,"zero lookup attempts not reproduced");
   check(d.getLong("first_packet_bytes")==205&&"OBSERVED".equals(d.getString("packet_volume_status")),"first-packet volume lost");
   check(d.isNull("tx_bytes")&&d.isNull("rx_bytes")&&d.isNull("bytes")&&"UNKNOWN".equals(d.getString("volume_status")),"first packet was incorrectly converted into flow totals");
   check(counters.getBoolean("closed")&&counters.getLong("tx_bytes")==60&&counters.getLong("rx_bytes")==205&&counters.getLong("bytes")==265&&counters.getLong("tx_packets")==1&&counters.getLong("rx_packets")==1&&"OBSERVED".equals(counters.getString("volume_status")),"unknown-flow counters lost or counted twice");
   check(NetworkCaptureService.lastError.isEmpty(),"observer failed during reproduction: "+NetworkCaptureService.lastError);
   try(ResultSet r=store.db.createStatement().executeQuery("SELECT COUNT(*) FROM events")){check(r.next()&&r.getInt(1)==store.events.size(),"short-flow SQLite row count mismatch");}
   System.out.println("REPRODUCED 2.0.7 short flow: synthetic callbacks, uid_attempts=0, closed=false + CLOSED_UNRESOLVED, packet=205 B, final tx=60 B/rx=205 B, capture-to-persistence="+delay+" ms");
  }finally{release.countDown();queue.finish();queue.await(6000);store.db.close();}
 }
'''
# Both stress scenarios block the journal but retain their scenario name for assertions.
probe=probe.replace('if(name.equals(mode)&&blocked.getCount()>0)', 'if((name.equals(mode)||name.equals("journal")&&(mode.equals("watchdog")||mode.equals("overflow")))&&blocked.getCount()>0)')
probe=probe.replace('__BASELINE__',str(baseline).lower())
probe=probe.replace('__SHORT_FLOW_TEST__','' if baseline else short_flow_test)
probe=probe.replace('__RUN_SHORT_FLOW__','' if baseline else 'if(args.length>2&&args[2].equals("short-flow")){shortFlow(args[1]);return;}')
drain=r'''Field f=NetworkCaptureService.class.getDeclaredField("observations");f.setAccessible(true);CaptureQueue q=(CaptureQueue)f.get(service);q.finish();check(q.await(6000),"accepted observations did not drain");check(q.completed()==q.accepted(),"accepted commands lost");if(scenario.equals("overflow"))check(q.rejected()>0&&q.highWater()<=4096,"queue bound/gap count");'''
probe=probe.replace('__DRAIN_OBSERVATIONS__','' if baseline else drain)
helpers=r'''
#include <jni.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
JNIEXPORT jintArray JNICALL Java_fr_erick_journallocal_Probe_tun(JNIEnv* e,jclass c){(void)c;int fd[2];if(socketpair(AF_UNIX,SOCK_DGRAM,0,fd)||fcntl(fd[0],F_SETFL,O_NONBLOCK)<0)return NULL;jintArray a=(*e)->NewIntArray(e,2);(*e)->SetIntArrayRegion(e,a,0,2,fd);return a;}
JNIEXPORT void JNICALL Java_fr_erick_journallocal_Probe_write(JNIEnv*e,jclass c,jint fd,jbyteArray b){(void)c;jsize n=(*e)->GetArrayLength(e,b);jbyte *p=(*e)->GetByteArrayElements(e,b,NULL);ssize_t sent=write(fd,p,n);(*e)->ReleaseByteArrayElements(e,b,p,JNI_ABORT);if(sent!=n)(*e)->ThrowNew(e,(*e)->FindClass(e,"java/io/IOException"),"fake TUN short write");}
JNIEXPORT jbyteArray JNICALL Java_fr_erick_journallocal_Probe_read(JNIEnv*e,jclass c,jint fd,jint timeout){(void)c;struct pollfd p={fd,POLLIN,0};if(poll(&p,1,timeout)<=0)return NULL;unsigned char b[65536];ssize_t n=read(fd,b,sizeof(b));if(n<=0)return NULL;jbyteArray a=(*e)->NewByteArray(e,n);(*e)->SetByteArrayRegion(e,a,0,n,(jbyte*)b);return a;}
JNIEXPORT void JNICALL Java_fr_erick_journallocal_Probe_close(JNIEnv*e,jclass c,jint fd){(void)e;(void)c;close(fd);}
'''
with tempfile.TemporaryDirectory() as folder:
    tmp=Path(folder);app=tmp/'app';app.mkdir();rt=tmp/'runtime';rt.mkdir();classes=tmp/'classes';classes.mkdir();runtime_classes=tmp/'runtime-classes';runtime_classes.mkdir()
    for name,content in apps.items():(app/name).write_text(content)
    (app/'Probe.java').write_text(probe)
    service=app/'NetworkCaptureService.java'
    service.write_text(subprocess.check_output(['git','show','c4c107300b3a5ac0c2ac4a5760e8b9b907bed489:aiv-v19/app/src/main/java/fr/erick/journallocal/NetworkCaptureService.java'],cwd=ROOT,text=True) if baseline else (JAVA/'NetworkCaptureService.java').read_text())
    support=('IdentityRetry','ObservationValues')+(() if baseline else ('CaptureQueue',))
    src=list(app.glob('*.java'))+[JAVA/(n+'.java') for n in support]
    subprocess.run(['javac','-encoding','UTF-8','-cp',jars+os.pathsep+str(sdk),'-d',str(classes),*map(str,src)],check=True,timeout=30)
    for name,content in runtime.items():p=rt/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
    subprocess.run(['javac','-encoding','UTF-8','-cp',str(classes)+os.pathsep+jars+os.pathsep+str(sdk),'-d',str(runtime_classes),*map(str,rt.rglob('*.java'))],check=True,timeout=30)
    helper=tmp/'probe.c';helper.write_text(helpers);lib=tmp/'probe.so';cpp=ROOT/'app/src/main/cpp';vendor=ROOT/'third_party/zdtun'
    jdk=Path(os.environ.get('AIV_JDK_INCLUDE','/usr/lib/jvm/java-17-openjdk-amd64/include'))
    subprocess.run(['cc','-shared','-fPIC','-O1','-DNO_DEBUG','-D_LITTLE_ENDIAN','-pthread','-I'+str(jdk),'-I'+str(jdk/'linux'),'-I'+str(cpp),'-I'+str(vendor),str(helper),*[str(cpp/n) for n in ('jni.c','relay.c','tls_sni.c')],str(vendor/'zdtun.c'),str(vendor/'utils.c'),'-o',str(lib)],check=True,timeout=30)
    subprocess.run(['java','-Xmx256m','-cp',str(runtime_classes)+os.pathsep+str(classes)+os.pathsep+jars+os.pathsep+str(sdk),'fr.erick.journallocal.Probe',str(lib),str(tmp/'journal')]+(['short-flow'] if short_flow else []),check=True,timeout=35)
