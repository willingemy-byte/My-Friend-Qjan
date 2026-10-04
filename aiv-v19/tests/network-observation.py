#!/usr/bin/env python3
"""Execute actual service callbacks with synthetic Android responses, then export.
No phone, native packet parser or SQLite integration is exercised by this harness.
Requires JDK and AIV_JSON_JAR=org.json:json:20240303, like run-v23-snapshot.sh.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/fr/erick/journallocal'
JSON_JAR = Path(os.environ['AIV_JSON_JAR']).resolve()

def extract(source, signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

source = Path(os.environ.get('AIV_SERVICE_SOURCE',str(JAVA / 'NetworkCaptureService.java'))).read_text()
signatures = [
    'private static final class Flow', 'private void identify(Flow f)',
    'private JSONObject details(long id,Flow f)', 'private String destination(Flow f)',
    'public void onFlowOpen(', 'public void onFlowDirection(', 'public void onFlowUpdate(',
]
methods = '\n'.join(extract(source, s) for s in signatures)
template = r'''
package fr.erick.journallocal;
import java.net.*;import java.util.*;import java.io.*;import org.json.*;
public class ObservationProbe {
 static class Build {static class VERSION {static int SDK_INT=35;}}
 static class ApplicationInfo {static final int FLAG_SYSTEM=1,FLAG_UPDATED_SYSTEM_APP=2;int flags;}
 static class PackageManager {static class NameNotFoundException extends Exception {private static final long serialVersionUID=1L;}}
 static class SecurityContext {static JSONArray forPackages(Object c,JSONArray p){return new JSONArray();}}
 static class Config {String transport="test";}
 static class Connectivity {
  int uid=-1;boolean fail;
  int getConnectionOwnerUid(int protocol,InetSocketAddress local,InetSocketAddress remote){if(fail)throw new SecurityException("synthetic");return uid;}
 }
 static class Packages {
  boolean fail;String[] names={"example.one"};
  String[] getPackagesForUid(int uid){if(fail)throw new IllegalStateException("synthetic");return names;}
  ApplicationInfo getApplicationInfo(String pkg,int flags)throws PackageManager.NameNotFoundException{return new ApplicationInfo();}
  CharSequence getApplicationLabel(ApplicationInfo info){return "Example app";}
 }
 private static final String[] STATES={"nouveau","connexion en cours","connecté","fermé"};
 static String newFlowCorrelationId(){return UUID.randomUUID().toString();}
 String session="synthetic";Config current;
 Connectivity connectivity=new Connectivity();Packages packages=new Packages();
 HashMap<Long,Flow> flows=new HashMap<>();List<String> events=new ArrayList<>();
 Packages getPackageManager(){return packages;}
 void record(String category,String actor,String action,String destination,JSONObject d){
  events.add(EventStore.object("id",events.size()+1,"timestamp_ms",1000+events.size(),"category",category,"app",actor,"action",action,"destination",destination,"details",d).toString());
 }
 __ACTUAL_METHODS__
 void open(){onFlowOpen(1,4,6,"10.203.0.1",45000,"192.0.2.10",443);}
 JSONObject last(){return new JSONObject(events.get(events.size()-1));}
 JSONObject d(){return last().getJSONObject("details");}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void verifyExport(ObservationProbe p)throws Exception{
  SnapshotExporter.Source s=new SnapshotExporter.Source(){
   public long[] snapshot(){return new long[]{p.events.size(),p.events.size()};}
   public List<String> page(long after,long ceiling){return after==0?p.events:Collections.emptyList();}
  };
  for(boolean lines:new boolean[]{false,true}){
   StringWriter out=new StringWriter();SnapshotExporter.write(out,lines,s);final int[] count={0};
   JSONObject result=JournalRecovery.recover(new StringReader(out.toString()),raw->{
    JSONObject event=new JSONObject(raw);String expected=p.events.get(count[0]++);
    check(event.similar(new JSONObject(expected)),"Export changed an original observation");
   });
   check(result.getBoolean("document_complete")&&"verifiee".equals(result.getString("source_integrity"))&&count[0]==p.events.size(),"Export integrity or count");
  }
 }
 public static void main(String[] args)throws Exception{
  ObservationProbe unknown=new ObservationProbe();unknown.open();
  check(unknown.d().getInt("uid")==-1&&"unknown".equals(unknown.d().getString("journal_group")),"Unknown owner presented as Android");
  check("INCONNU".equals(unknown.d().getString("application_attribution_confidence")),"Invented app attribution");
  check(unknown.d().has("tx_bytes")&&unknown.d().isNull("tx_bytes")&&"INCONNU".equals(unknown.d().getString("volume_confidence")),"Missing counter presented as zero");
  unknown.onFlowDirection(1,true,48,1000);
  check(unknown.d().getLong("first_packet_bytes")==48&&"OBSERVÉ".equals(unknown.d().getString("packet_volume_confidence"))&&unknown.d().isNull("tx_bytes"),"First packet confused with flow total");
  unknown.onFlowUpdate(1,96,128,2,3,2,0,false,2000);
  check(unknown.d().getInt("uid")==-1&&unknown.d().getLong("bytes")==224&&unknown.d().getLong("tx_packets")==2&&"OBSERVÉ".equals(unknown.d().getString("volume_confidence")),"Unknown app lost valid volume");
  unknown.onFlowUpdate(1,144,0,3,0,2,0,false,3000);
  check(!unknown.d().isNull("rx_bytes")&&unknown.d().getLong("rx_bytes")==0,"Real zero lost");
  unknown.onFlowUpdate(1,Long.MAX_VALUE,1,3,1,2,0,false,4000);
  check(unknown.d().isNull("bytes")&&"INCONNU".equals(unknown.d().getString("volume_confidence"))&&unknown.d().getJSONObject("raw_counters").getLong("tx_bytes")==Long.MAX_VALUE,"Overflow accepted or original counter lost");
  unknown.onFlowUpdate(1,-1,3,1,1,2,0,false,4500);
  check(unknown.d().isNull("tx_bytes")&&unknown.last().getString("action").contains("invalides"),"Invalid negative counter accepted");
  verifyExport(unknown);
  ObservationProbe hidden=new ObservationProbe();hidden.connectivity.uid=10345;hidden.packages.names=null;hidden.open();
  check(hidden.d().getInt("uid")==10345&&"unknown".equals(hidden.d().getString("journal_group"))&&!hidden.last().getString("app").startsWith("Android"),"Package invisibility fabricated system app");
  check("ATTRIBUÉ".equals(hidden.d().getString("connection_owner_confidence"))&&"INCONNU".equals(hidden.d().getString("application_attribution_confidence")),"Owner and package certainty conflated");
  ObservationProbe packageError=new ObservationProbe();packageError.connectivity.uid=10345;packageError.packages.fail=true;packageError.open();
  check(packageError.d().getInt("uid")==10345&&"INCONNU".equals(packageError.d().getString("application_attribution_confidence")),"Package failure discarded valid UID");
  ObservationProbe shared=new ObservationProbe();shared.connectivity.uid=10345;shared.packages.names=new String[]{"example.one","example.two"};shared.open();
  check("INCONNU".equals(shared.d().getString("application_attribution_confidence"))&&shared.d().getJSONArray("packages").length()==2,"Shared UID attributed to one app");
  ObservationProbe system=new ObservationProbe();system.connectivity.uid=1000;system.open();
  check("android".equals(system.d().getString("journal_group"))&&"INCONNU".equals(system.d().getString("application_attribution_confidence")),"Reserved UID attributed to candidate package");
  ObservationProbe profile=new ObservationProbe();profile.connectivity.uid=110345;profile.open();
  check("ATTRIBUÉ".equals(profile.d().getString("application_attribution_confidence"))&&profile.d().getInt("uid")==110345,"Profile UID misclassified");
  ObservationProbe apiError=new ObservationProbe();apiError.connectivity.fail=true;apiError.open();
  check(apiError.d().getInt("uid")==-1&&"INCONNU".equals(apiError.d().getString("connection_owner_confidence"))&&"unknown".equals(apiError.d().getString("journal_group")),"API failure fabricated owner");
  System.out.println("PASS actual callbacks → JSON/JSONL: unknown identity, null/zero, independent counters, overflow, package failure, shared/reserved/profile UID, SHA-256 and unchanged observations");
 }
}
final class EventStore {
 static JSONObject object(Object... args){JSONObject o=new JSONObject();for(int i=0;i<args.length;i+=2)o.put((String)args[i],args[i+1]);return o;}
}
'''
with tempfile.TemporaryDirectory(prefix='aiv-network-observation-') as temporary:
    directory=Path(temporary)
    probe=directory/'ObservationProbe.java'
    probe.write_text(template.replace('__ACTUAL_METHODS__',methods))
    subprocess.run(['javac','-encoding','UTF-8','-cp',str(JSON_JAR),'-d',str(directory),str(probe),
                    *(str(JAVA/name) for name in ['SnapshotExporter.java','JournalRecovery.java','JsonSyntax.java'])],check=True)
    subprocess.run(['java','-cp',str(directory)+os.pathsep+str(JSON_JAR),'fr.erick.journallocal.ObservationProbe'],check=True)
