#!/usr/bin/env python3
"""Real PermissionUsage collector and indexed joins, with host SQLite and synthetic AppOps.
No phone dump or private metadata is used. Shizuku/PackageManager replies are fixtures.
"""
from pathlib import Path
import ast, os, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/fr/erick/journallocal'
classpath=os.pathsep.join(os.environ[k] for k in ('AIV_JSON_JAR','AIV_SQLITE_JAR','AIV_SLF4J_JAR'))
tree=ast.parse((ROOT/'tests/journal-sqlite.py').read_text())
existing=ast.literal_eval(next(n.value for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='stubs' for t in n.targets)))
keep=['android/content/ContentValues.java','android/database/Cursor.java','android/database/sqlite/SQLiteDatabase.java','android/database/sqlite/SQLiteOpenHelper.java','android/os/SystemClock.java','android/os/Build.java']
stubs={k:existing[k] for k in keep}
stubs.update({
'fr/erick/journallocal/NetworkReport.java':'''package fr.erick.journallocal;import android.content.Context;import org.json.JSONObject;class NetworkReport{static void enrich(Context c,JSONObject e){}static String brief(JSONObject e){return "";}static String explain(JSONObject e){return "";}}''',
'android/os/Process.java':'''package android.os;public class Process{public static int myUid(){return 10444;}}''',
'android/content/Context.java':'''package android.content;import java.io.File;public class Context{public final File root;public final android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public Context(File f){root=f;f.mkdirs();}public Context getApplicationContext(){return this;}public android.content.pm.PackageManager getPackageManager(){return pm;}}''',
'android/content/pm/ApplicationInfo.java':'''package android.content.pm;public class ApplicationInfo{public int uid=10371;}''',
'android/content/pm/PackageInfo.java':'''package android.content.pm;public class PackageInfo{public String packageName;public long firstInstallTime=946684800000L;public ApplicationInfo applicationInfo=new ApplicationInfo();}''',
'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager{public static final int GET_SIGNING_CERTIFICATES=1,GET_SIGNATURES=2;public static class NameNotFoundException extends Exception{}public static boolean missing;public static long installed=946684800000L;public PackageInfo getPackageInfo(String pkg,int flags)throws NameNotFoundException{if(missing)throw new NameNotFoundException();PackageInfo p=new PackageInfo();p.packageName=pkg;p.firstInstallTime=installed;return p;}public CharSequence getApplicationLabel(ApplicationInfo info){return "Fixture app";}public String[] getPackagesForUid(int uid){return new String[]{"example.chat"};}}''',
'fr/erick/journallocal/AppIdentity.java':'''package fr.erick.journallocal;import android.content.*;import android.content.pm.*;import org.json.*;class AppIdentity{static String signer="cert-A";static JSONObject forPackage(Context c,PackageInfo p){return EventStore.object("uid",p.applicationInfo.uid,"package_name",p.packageName,"app_identity_id",signer,"first_install_ms",p.firstInstallTime,"current_signer_sha256",new JSONArray().put(signer));}}''',
'fr/erick/journallocal/ControlShell.java':'''package fr.erick.journallocal;class ControlShell{static Result next;static class Result{int code;String out,err;boolean complete;Result(int c,String o,String e,boolean b){code=c;out=o;err=e;complete=b;}}static Result run(String cmd,int limit){if(!cmd.equals("dumpsys appops")||limit>1024*1024)throw new AssertionError("Unbounded or mutating command");return next;}}''',
'fr/erick/journallocal/RecorderService.java':'''package fr.erick.journallocal;class RecorderService{static boolean running=true;}''',
'fr/erick/journallocal/EventStore.java':'''package fr.erick.journallocal;import org.json.*;import android.content.*;class EventStore{static EventStore instance=new EventStore();static JSONArray rows=new JSONArray();static boolean fail;static EventStore get(Context c){return instance;}static JSONObject object(Object... parts){JSONObject o=new JSONObject();try{for(int i=0;i<parts.length;i+=2)o.put(String.valueOf(parts[i]),parts[i+1]);}catch(Exception e){throw new RuntimeException(e);}return o;}boolean add(String category,String app,String action,String destination,String transport,String source,JSONObject d){return addObserved(category,app,action,destination,transport,source,d,System.currentTimeMillis(),0);}boolean addObserved(String cat,String app,String action,String destination,String transport,String source,JSONObject d,long at,long elapsed){if(fail)return false;rows.put(object("id",rows.length()+1,"category",cat,"details",d,"timestamp_ms",at,"action",action));return true;}}'''
})
probe=r'''package fr.erick.journallocal;
import android.content.*;import android.content.pm.*;import org.json.*;import java.io.*;import java.util.*;import java.text.*;import java.lang.reflect.*;
public class UsageProbe{
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static String dump(String op,String kind,long at,String suffix){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.ROOT);f.setTimeZone(TimeZone.getDefault());return "Current AppOps Service state:\n  Uid u0a371:\n    Package example.chat:\n      "+op+" (allow):\n        null=[\n          "+kind+": [top-s] "+f.format(new Date(at))+" (-1s0ms)"+suffix+"\n        ]\n";}
 static void sample(PermissionUsage store,String dump,boolean complete)throws Exception{ControlShell.next=new ControlShell.Result(0,dump,"",complete);Method m=PermissionUsage.class.getDeclaredMethod("sample");m.setAccessible(true);try{m.invoke(store);}catch(InvocationTargetException e){throw (Exception)e.getCause();}}
 static int accesses(){int n=0;for(int i=0;i<EventStore.rows.length();i++)if("acces".equals(EventStore.rows.getJSONObject(i).optString("category")))n++;return n;}
 static JSONObject network(long at,int uid,String signer,int count){JSONArray pkgs=new JSONArray().put("example.chat");if(count>1)pkgs.put("example.other");return EventStore.object("timestamp_ms",at,"details",EventStore.object("uid",uid,"packages",pkgs,"flow_correlation_id","fixture-flow","app_identity",EventStore.object("uid",uid,"package_name","example.chat","first_install_ms",946684800000L,"app_identity_id",signer)));}
 public static void main(String[] args)throws Exception{
  Context c=new Context(new File(args[0]));PermissionUsage store=PermissionUsage.get(c);long baseline=store.status().getLong("started_ms");
  sample(store,dump("READ_CONTACTS","Access",baseline-86400000,""),true);check(accesses()==0,"initial last-access history replayed as current activity");
  long access=System.currentTimeMillis();sample(store,dump("READ_CONTACTS","Access",access,""),true);check(accesses()==1,"new contact read not journaled");
  sample(store,dump("READ_CONTACTS","Access",access,""),true);check(accesses()==1,"repeat last-access timestamp duplicated");
  JSONObject pc=store.related(network(access,10371,"cert-A",1));check(pc.getJSONArray("observations").length()==1,"same actor/time not linked");
  check(store.related(network(access,10372,"cert-A",1)).getJSONArray("observations").length()==0,"different UID linked");
  check(store.related(network(access,10371,"cert-B",1)).getJSONArray("observations").length()==0,"different signer linked");
  check(store.related(network(access,10371,"cert-A",2)).getJSONArray("observations").length()==0,"shared UID guessed");
  check(store.related(network(access+60001,10371,"cert-A",1)).getJSONArray("observations").length()==0,"out-of-window access linked");
  check(store.related(EventStore.object("timestamp_ms",access,"details",EventStore.object("uid",10371,"packages",new JSONArray().put("example.chat")))).getJSONArray("observations").length()==0,"missing identity guessed");
  JSONObject reinstalled=network(access,10371,"cert-A",1);reinstalled.getJSONObject("details").getJSONObject("app_identity").put("first_install_ms",access-1);check(store.related(reinstalled).getJSONArray("observations").length()==0,"access from prior installation linked");
  sample(store,dump("READ_CONTACTS","Reject",System.currentTimeMillis(),""),true);check(accesses()==2,"refusal missing");
  check(EventStore.rows.getJSONObject(EventStore.rows.length()-1).getJSONObject("details").getString("access_result").equals("REJECT"),"refusal became success");
  int before=accesses();sample(store,dump("CAMERA","Access",System.currentTimeMillis(),""),false);check(accesses()==before&&store.status().getString("state").equals("INDISPONIBLE"),"truncated dump claimed evidence");
  sample(store,"Unknown command",true);check(accesses()==before&&store.status().getString("state").equals("FORMAT_NON_RECONNU"),"unrecognized dump claimed coverage");
  sample(store,dump("RECORD_AUDIO","Access",baseline-10000,"\n          Running start at: +10s0ms"),true);check(accesses()==before+1,"ongoing access from before start lost");
  long now=System.currentTimeMillis();JSONObject active=store.related(network(now,10371,"cert-A",1));boolean mic=false;JSONArray obs=active.getJSONArray("observations");for(int i=0;i<obs.length();i++)if(obs.getJSONObject(i).optString("operation").equals("RECORD_AUDIO"))mic=obs.getJSONObject(i).getBoolean("running");check(mic,"ongoing access interval not linked");
  check(store.related(network(baseline-3600000,10371,"cert-A",1)).getJSONArray("observations").length()==0,"old access extended to current running interval");
  before=accesses();sample(store,dump("CAMERA","Access",System.currentTimeMillis(),"").replace("u0a371","u0a999"),true);check(accesses()==before,"PM UID mismatch accepted");
  PackageManager.missing=true;sample(store,dump("CAMERA","Access",System.currentTimeMillis(),""),true);check(accesses()==before&&store.status().getString("state").equals("PARTIEL"),"missing package accepted");PackageManager.missing=false;
  RecorderService.running=false;sample(store,dump("CAMERA","Access",System.currentTimeMillis(),""),true);check(accesses()==before,"sampling continues after explicit stop");RecorderService.running=true;
  EventStore.fail=true;boolean failed=false;try{sample(store,dump("CAMERA","Access",System.currentTimeMillis(),""),true);}catch(Exception e){failed=true;}check(failed,"failed journal write reported as saved");EventStore.fail=false;
  sample(store,dump("CAMERA","Access",System.currentTimeMillis(),""),true);check(accesses()==before+1,"failed write consumed dedup checkpoint");
  JSONArray source=new JSONArray().put(network(access,10371,"cert-A",1).put("id",123));JSONObject a=PermissionUsage.anomalyContext(c,source);check(a.getJSONArray("observations").length()>0&&a.getJSONArray("observations").getJSONObject(0).getLong("source_event_id")==123,"anomaly evidence lost source ID");
  String text=PermissionUsage.explain(source.getJSONObject(0));check(text.contains("INTERNET")&&text.contains("ne prouve pas"),"capability/correlation scope missing");
  try(android.database.Cursor x=store.getReadableDatabase().rawQuery("EXPLAIN QUERY PLAN SELECT payload FROM observations WHERE pkg=? AND identity=? AND uid=? AND installed_ms=? AND at_ms<=? AND end_ms>=? ORDER BY captured_ms DESC LIMIT 33",new String[]{"example.chat","cert-A","10371","946684800000",String.valueOf(access+60000),String.valueOf(access-60000)})){boolean index=false;while(x.moveToNext())if(x.getString(3).contains("observations_actor_time"))index=true;check(index,"join scans entire journal");}
  System.out.println("PermissionUsage collector/SQLite: "+checks+" checks passed");
 }
}'''
with tempfile.TemporaryDirectory() as task:
    root=Path(task);src=root/'src';classes=root/'classes';classes.mkdir()
    for name,body in stubs.items():
        path=src/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body)
    p=src/'fr/erick/journallocal/UsageProbe.java';p.write_text(probe)
    files=list(src.rglob('*.java'))+[JAVA/'PermissionUsage.java',JAVA/'PermissionUsageRules.java',ROOT/'tests/PermissionUsageRulesTest.java']
    subprocess.run(['javac','-encoding','UTF-8','-cp',classpath,'-d',str(classes),*map(str,files)],check=True,timeout=30)
    subprocess.run(['java','-cp',str(classes)+os.pathsep+classpath,'fr.erick.journallocal.PermissionUsageRulesTest'],check=True,timeout=30)
    subprocess.run(['java','-cp',str(classes)+os.pathsep+classpath,'fr.erick.journallocal.UsageProbe',str(root/'data')],check=True,timeout=30)
