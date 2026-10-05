#!/usr/bin/env python3
"""Real catalogue loading and network-report wrapper; synthetic events, no phone data."""
from pathlib import Path
import os, subprocess, tempfile, shutil
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/fr/erick/journallocal'
jar=os.environ['AIV_JSON_JAR']
stubs={
 'fr/erick/journallocal/AnomalyMonitor.java':'package fr.erick.journallocal;import android.content.Context;import org.json.*;class AnomalyMonitor{static AnomalyMonitor get(Context c){return new AnomalyMonitor();}void pedigree(JSONObject e,boolean record){}}',
 'android/content/Context.java':'package android.content;import java.io.File;public class Context{final File assets;public Context(File f){assets=f;}public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager(assets);}}',
 'android/content/res/AssetManager.java':'package android.content.res;import java.io.*;public class AssetManager{final File root;public AssetManager(File f){root=f;}public InputStream open(String n)throws IOException{return new FileInputStream(new File(root,n));}}',
 'android/os/Build.java':'package android.os;public class Build{public static class VERSION{public static int SDK_INT=36;}}',
 'fr/erick/journallocal/EventStore.java':'package fr.erick.journallocal;import org.json.*;class EventStore{static JSONObject object(Object... v){return EndpointContextRules.object(v);}}',
}
probe='''package fr.erick.journallocal;
import android.content.Context;import org.json.*;import java.io.File;
public class NetworkReportProbe{
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static JSONObject event(String h){return EndpointContextRules.object("id",42,"timestamp_ms",946684800000L,"category","trafic","details",EndpointContextRules.object("flow_correlation_id","fixture-flow","uid",1000,"packages",new JSONArray().put("example.a").put("example.b"),"tls_sni",h,"remote_ip","192.0.2.1","rx_bytes",47));}
 public static void main(String[] a)throws Exception{
  Context c=new Context(new File(a[0]));JSONObject e=event("mtalk.google.com");String raw=e.getJSONObject("details").toString();NetworkReport.enrich(c,e);
  if(a.length>1){check(e.getJSONObject("network_context").optString("status").equals("UNAVAILABLE"),"bad catalogue hides fallback");check(e.getJSONObject("details").toString().equals(raw),"bad catalogue mutates capture");System.out.println("Catalogue failure: "+checks+" checks passed");return;}
  check(NetworkReport.brief(e).contains("Notifications"),"wrapper failed to load service asset");
  check(!NetworkReport.brief(e).contains("Google Ads")&&!NetworkReport.trackersBrief(e).contains("Google Ads"),"real broad Exodus signature still labels FCM advertising");
  check(NetworkReport.explain(e).contains("Google Ads")&&NetworkReport.explain(e).contains("partielle"),"broad signature evidence lost from detail");
  check(e.getJSONObject("network_context").getLong("observed_event_ms")==946684800000L,"wrapper redated historical event");
  check(e.getJSONObject("details").toString().equals(raw),"wrapper mutated raw details");
  check(e.getJSONObject("network_context").getJSONObject("chronology_and_volume").getLong("rx_bytes")==47,"return volume lost");
  check(e.getJSONObject("network_context").getString("local_recipient_status").equals("NOT_OBSERVED"),"wrapper guessed shared UID recipient");
  JSONObject analytics=event("firebaselogging-pa.googleapis.com");NetworkReport.enrich(c,analytics);
  check(NetworkReport.brief(analytics).contains("Analyse d’usage"),"real Exodus category not exposed");
  JSONObject tracker=analytics.getJSONObject("network_context").getJSONArray("tracker_candidates").getJSONObject(0);
  check(tracker.getInt("tracker_id")==49&&tracker.getString("usage_status").equals("CANDIDATE"),"tracker inference promoted");
  check(tracker.getString("source").contains("exodus"),"tracker attribution missing");
  check(NetworkReport.explain(analytics).contains("Exodus #49"),"detail lost tracker evidence");
  JSONObject logging=event("firebaselogging.googleapis.com");NetworkReport.enrich(c,logging);check(NetworkReport.brief(logging).contains("journalisation"),"logging alias remains unclassified");
  JSONObject owner=event("");owner.getJSONObject("details").put("observation_type","IDENTITY_ENRICHMENT").put("port",53).put("protocol","UDP").put("rx_bytes",JSONObject.NULL);NetworkReport.enrich(c,owner);
  check(NetworkReport.brief(owner).startsWith("DNS probable")&&owner.getJSONObject("network_context").getJSONObject("observation_context").getBoolean("identity_enrichment_only"),"owner-only DNS event invented an exchange");
  JSONObject dns=event("").put("category","dns");dns.getJSONObject("details").put("question","firebaselogging-pa.googleapis.com");NetworkReport.enrich(c,dns);
  check(NetworkReport.brief(dns).startsWith("Recherche DNS"),"wrapper promoted DNS inquiry");
  JSONObject local=EndpointContextRules.object("category","acces","details",EndpointContextRules.object("permission","android.permission.READ_CONTACTS"));NetworkReport.enrich(c,local);check(!local.has("network_context"),"wrapper network-enriched unrelated local access");
  JSONObject aggregate=NetworkReport.anomalyContext(new JSONArray().put(e).put(analytics).put(local));check(aggregate.getJSONArray("events").length()==2,"anomaly source contexts incorrect");
  JSONObject chat=event("android.chat.openai.com").put("app","ChatGPT");chat.getJSONObject("details").put("uid",10371).put("packages",new JSONArray().put("com.openai.chatgpt"));NetworkReport.enrich(c,chat);
  check(NetworkReport.brief(chat).contains("ChatGPT")&&NetworkReport.explain(chat).contains("Propriétaire du flux : ChatGPT"),"real catalogue missing application/service link");
  check(chat.getJSONObject("network_context").getJSONObject("owner_service_relation").getString("owner_package").equals("com.openai.chatgpt"),"structured application/service link absent");
  check(NetworkReport.catalogueSummary(c).getJSONArray("sources").length()==12,"report sources incomplete");
  System.out.println("Network-report integration: "+checks+" checks passed");
 }
}'''
with tempfile.TemporaryDirectory() as task:
    root=Path(task);src=root/'src';classes=root/'classes';classes.mkdir()
    for name,body in stubs.items():
        p=src/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
    (src/'fr/erick/journallocal/NetworkReportProbe.java').write_text(probe)
    files=list(src.rglob('*.java'))+[JAVA/(n+'.java') for n in ('TrackerMatcher','EndpointContextRules','NetworkReport','ReferenceCatalog')]
    subprocess.run(['javac','-encoding','UTF-8','-cp',jar,'-d',str(classes),*map(str,files)],check=True,timeout=30)
    cp=str(classes)+os.pathsep+jar
    subprocess.run(['java','-cp',cp,'fr.erick.journallocal.NetworkReportProbe',str(ROOT/'app/src/main/assets')],check=True,timeout=30)
    bad=root/'bad-assets';shutil.copytree(ROOT/'app/src/main/assets/catalogs',bad/'catalogs')
    (bad/'catalogs/endpoints.json').write_text('{"schema":"invalid"}')
    subprocess.run(['java','-cp',cp,'fr.erick.journallocal.NetworkReportProbe',str(bad),'bad'],check=True,timeout=30)
