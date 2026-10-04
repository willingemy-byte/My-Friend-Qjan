#!/usr/bin/env python3
"""Real Java callbacks, controlled Android replies. Not a phone or packet-parser test."""
from pathlib import Path
import os, subprocess, tempfile

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/fr/erick/journallocal'
jar=Path(os.environ['AIV_JSON_JAR']).resolve()

def extract(source,signature):
    start=source.index(signature);end=source.index('{',start)+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]

source=(JAVA/'NetworkCaptureService.java').read_text()
signatures=['private static final class Flow','public boolean shouldStopNative()',
    'private void pumpIdentity()','private void queueIdentity(','private void identify(',
    'private JSONObject details(','private String destination(', 'public void onFlowOpen(',
    'public void onFlowDirection(','public void onFlowUpdate(','public void onDnsQuestion(',
    'public void onTlsHello(']
methods='\n'.join(extract(source,x) for x in signatures)
template=r'''
package fr.erick.journallocal;
import java.util.*;import java.net.*;import java.io.*;import org.json.*;
public class ObservationProbe {
 static class Build {static class VERSION {static int SDK_INT=35;}}
 static class SystemClock {static long now;static long elapsedRealtime(){return now;}}
 static class ApplicationInfo {static final int FLAG_SYSTEM=1,FLAG_UPDATED_SYSTEM_APP=2;int flags;}
 static class PackageManager {static class NameNotFoundException extends Exception {private static final long serialVersionUID=1L;}}
 static class SecurityContext {static JSONArray forPackages(Object c,JSONArray p){return new JSONArray();}}
 static class AppIdentity {static boolean fail;static JSONObject forUid(Object c,int uid,String[] p){if(fail)throw new IllegalStateException("fixture");return EventStore.object("uid",uid);}}
 static class Config {String transport="fixture";}
 static class Connectivity {int uid=-1,calls;boolean fail;int getConnectionOwnerUid(int p,InetSocketAddress l,InetSocketAddress r){calls++;if(fail)throw new SecurityException("fixture");return uid;}}
 static class Packages {
  boolean fail,labelFail;String[] names={"example.app"};
  String[] getPackagesForUid(int uid){if(fail)throw new IllegalStateException("fixture");return names;}
  ApplicationInfo getApplicationInfo(String p,int flags)throws PackageManager.NameNotFoundException{if(labelFail)throw new PackageManager.NameNotFoundException();return new ApplicationInfo();}
  CharSequence getApplicationLabel(ApplicationInfo p){return "Example app";}
 }
 private static final String[] STATES={"nouveau","connexion en cours","connecté","fermé"};
 static String newFlowCorrelationId(){return UUID.randomUUID().toString();}
 String session="synthetic";Config current=new Config();boolean stopped,reconfigure;
 static long lastHealthyFlowMs;
 Connectivity connectivity=new Connectivity();Packages packages=new Packages();
 HashMap<Long,Flow> flows=new HashMap<>();ArrayDeque<Long> pendingIdentity=new ArrayDeque<>();long lastIdentityPump;
 List<String> events=new ArrayList<>();Packages getPackageManager(){return packages;}
 JSONObject vpnIdentity(long id,Flow f){return EventStore.object("status","UNAVAILABLE");}
 JSONObject trackerDetails(JSONObject d,String name,String type){return d;}
 void record(String category,String actor,String action,String destination,JSONObject d){events.add(EventStore.object("id",events.size()+1,"timestamp_ms",1000+events.size(),"category",category,"app",actor,"action",action,"destination",destination,"details",d).toString());}
 __METHODS__
 void open(){onFlowOpen(1,4,6,"10.203.0.1",45000,"192.0.2.1",443);}
 JSONObject d(){return new JSONObject(events.get(events.size()-1)).getJSONObject("details");}
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static ObservationProbe fresh(){SystemClock.now=0;Build.VERSION.SDK_INT=35;AppIdentity.fail=false;return new ObservationProbe();}
 static void verifyExport(ObservationProbe p)throws Exception{
  SnapshotExporter.Source source=new SnapshotExporter.Source(){
   public long[] snapshot(){return new long[]{p.events.size(),p.events.size()};}
   public List<String> page(long after,long ceiling){return after==0?p.events:Collections.emptyList();}
  };
  for(boolean jsonl:new boolean[]{false,true}){
   StringWriter out=new StringWriter();SnapshotExporter.write(out,jsonl,source);final int[] count={0};
   JSONObject result=JournalRecovery.recover(new StringReader(out.toString()),raw->{check(new JSONObject(raw).similar(new JSONObject(p.events.get(count[0]++))),"Export mutated a raw observation");});
   check(result.getBoolean("document_complete")&&"verifiee".equals(result.getString("source_integrity"))&&count[0]==p.events.size(),"Export integrity/count");
   JSONObject header=jsonl?new JSONObject(out.toString().split("\n")[0]):new JSONObject(out.toString());
   check("2.0.6".equals(header.getString("application_version"))&&header.getInt("application_version_code")==206&&header.has("build"),"Build/version metadata");
   JSONObject footer=jsonl?new JSONObject(out.toString().trim().substring(out.toString().trim().lastIndexOf('\n')+1)):header;
   check(footer.has("quality"),"Quality metrics absent");
  }
 }
 public static void main(String[] args)throws Exception{
  ObservationProbe p=fresh();p.open();String original=p.events.get(0);
  check(p.d().getInt("uid")==-1&&"unknown".equals(p.d().getString("journal_group")),"Unknown must not become Android");
  check(p.d().isNull("tx_bytes")&&p.d().isNull("rx_bytes"),"Missing counters became zero");
  SystemClock.now=1;p.onFlowDirection(1,true,60,1001);SystemClock.now=2;p.onFlowDirection(1,false,72,1002);
  check(p.connectivity.calls==1,"Packet burst exhausted identity attempts");
  p.connectivity.uid=12345;SystemClock.now=50;p.shouldStopNative();
  check(p.flows.get(1L).uid==12345&&p.flows.get(1L).packages.length()==1&&p.events.get(0).equals(original),"Idle late identity/immutable observation");
  check("IDENTITY_ENRICHMENT".equals(p.d().getString("observation_type")),"Late enrichment not persisted");
  p.onFlowUpdate(1,120,40,2,1,2,0,false,1050);p.onDnsQuestion(1,"ads.example",1);p.onTlsHello(1,"ads.example",1,false);verifyExport(p);
  System.out.println("PASS burst 0/1/2 ms then UID 50 ms, idle enrichment, immutable raw, DNS/SNI and JSON/JSONL export");

  p=fresh();p.connectivity.uid=12345;p.packages.names=null;p.open();
  check(p.d().getInt("uid")==12345&&"unknown".equals(p.d().getString("journal_group")),"UID without package falsely labelled Android");
  p.packages.names=new String[]{"example.app"};SystemClock.now=100;p.shouldStopNative();
  check(p.flows.get(1L).packages.length()==1&&p.connectivity.calls==1,"Known UID froze package retry or got re-looked-up");
  p=fresh();p.connectivity.uid=12345;p.packages.fail=true;p.open();check(p.d().getInt("uid")==12345,"Package error erased observed UID");
  p.packages.fail=false;SystemClock.now=100;p.shouldStopNative();check(ObservationValues.uniquePackage(p.d()),"Package failure did not recover");
  p=fresh();p.connectivity.uid=12345;AppIdentity.fail=true;p.open();check(ObservationValues.uniquePackage(p.d()),"Optional crypto failure erased identity");
  System.out.println("PASS late package, package exception, optional identity failure preserve observed UID");

  for(int uid:new int[]{-1,1000,112345,12345}){
   p=fresh();p.connectivity.uid=uid;if(uid==12345)p.packages.names=new String[]{"one","two"};p.open();
   p.onFlowUpdate(1,80,20,2,1,2,0,false,2000);check(p.d().getLong("bytes")==100,"Volume depends on unique attribution");
   check(ObservationValues.uniquePackage(p.d())==(uid==112345),"Shared/reserved/profile UID attribution");verifyExport(p);
  }
  p=fresh();p.open();p.onFlowUpdate(1,0,0,0,0,2,0,false,2000);check(ObservationValues.countersKnown(p.d())&&p.d().getLong("bytes")==0,"Observed zero lost");
  p.onFlowUpdate(1,-1,0,1,0,2,0,false,2000);check(p.d().isNull("bytes")&&"INVALID".equals(p.d().getString("volume_status")),"Negative counter accepted");
  p.onFlowUpdate(1,Long.MAX_VALUE,1,1,1,2,0,false,2000);check(p.d().isNull("bytes"),"Overflow counter accepted");verifyExport(p);
  System.out.println("PASS UNKNOWN/shared/reserved/profile volumes; real zero versus missing, negative and overflow counters");

  p=fresh();p.connectivity.fail=true;p.open();for(long ms:new long[]{1,2,25,100,300,750,1500,3000,6000,10000,12000}){SystemClock.now=ms;p.shouldStopNative();}
  check(p.connectivity.calls==8&&"EXHAUSTED".equals(p.flows.get(1L).identityStatus)&&p.pendingIdentity.isEmpty(),"Retry is not bounded");
  p=fresh();Build.VERSION.SDK_INT=26;p.open();check(p.connectivity.calls==0&&p.pendingIdentity.isEmpty(),"Unsupported API retried");
  p=fresh();p.open();String old=p.flows.get(1L).correlationId;SystemClock.now=100;p.connectivity.uid=12345;p.onTlsHello(1,"",-5,false);check(p.connectivity.calls==1,"Closing TLS callback looked up a dead tuple");p.onFlowUpdate(1,40,0,1,0,3,0,true,20);p.shouldStopNative();check(p.connectivity.calls==1&&p.flows.isEmpty(),"Closed tuple got re-attributed");
  p.open();check(!p.flows.get(1L).correlationId.equals(old),"Native ID reuse joined different connections");
  p=fresh();for(int i=1;i<=2049;i++)p.onFlowOpen(i,4,6,"10.203.0.1",40000+i,"192.0.2.1",443);
  check(p.pendingIdentity.size()==2048&&"QUEUE_FULL".equals(p.flows.get(2049L).identityStatus),"Queue bound/status");
  System.out.println("PASS retry limits, API 26, closed socket, native ID reuse, 2048 queue bound");

  JSONObject f=EventStore.object("uid",-1,"protocol","TCP","packages",new JSONArray());
  ObservationValues.mergeCounters(f,EventStore.object());check(f.isNull("tx_bytes"),"Projection invented zero");
  JSONObject counters=EventStore.object();ObservationValues.putCounters(counters,100,50,2,1);ObservationValues.mergeCounters(f,counters);
  ObservationValues.putCounters(counters,120,50,3,1);ObservationValues.mergeCounters(f,counters);ObservationValues.mergeCounters(f,EventStore.object());
  JSONObject quality=NetworkQuality.flows(new JSONArray().put(f));check(quality.getLong("tx_bytes_observed")==120&&quality.getDouble("unknown_rate")==1&&quality.getDouble("volume_coverage")==1,"Projection summed snapshots or excluded UNKNOWN");
  check(NetworkQuality.flows(new JSONArray()).isNull("attribution_rate"),"Empty denominator fabricated rate");
  System.out.println("PASS cumulative maximum, UNKNOWN denominator, volume coverage and empty rates");
 }
}
'''
event_stub='''package fr.erick.journallocal;import org.json.*;final class EventStore {static JSONObject object(Object... kv){JSONObject o=new JSONObject();try{for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}catch(JSONException e){throw new IllegalArgumentException(e);}}}'''
with tempfile.TemporaryDirectory() as tmp:
    tmp=Path(tmp);(tmp/'ObservationProbe.java').write_text(template.replace('__METHODS__',methods));(tmp/'EventStore.java').write_text(event_stub)
    src=[JAVA/(name+'.java') for name in ['IdentityRetry','ObservationValues','NetworkQuality','ExportMetadata','SnapshotExporter','JournalRecovery','JsonSyntax']]
    src+=[ROOT/'build/generated/fr/erick/journallocal/BuildMetadata.java',tmp/'ObservationProbe.java',tmp/'EventStore.java']
    subprocess.run(['javac','-encoding','UTF-8','-cp',str(jar),'-d',str(tmp),*map(str,src)],check=True)
    subprocess.run(['java','-Xmx256m','-cp',str(tmp)+os.pathsep+str(jar),'fr.erick.journallocal.ObservationProbe'],check=True)
