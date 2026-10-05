#!/usr/bin/env python3
"""Execute real TrackerIndex/SQLiteSnapshot Java against host SQLite via JDBC.
Android scheduling/package replies are stubs; Android WAL/runtime remain phone checks.
"""
from pathlib import Path
import os, subprocess, tempfile

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/fr/erick/journallocal'
classpath=os.pathsep.join(str(Path(os.environ[name]).resolve()) for name in ['AIV_JSON_JAR','AIV_SQLITE_JAR','AIV_SLF4J_JAR'])
stubs={
'android/content/ContentValues.java':'''package android.content;import java.util.*;public class ContentValues extends LinkedHashMap<String,Object>{public void putNull(String key){put(key,null);}}''',
'android/content/Context.java':'''package android.content;import java.io.File;public class Context{public final File root;public Context(File r){root=r;root.mkdirs();}public Context getApplicationContext(){return this;}public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}}''',
'android/content/pm/PackageInfo.java':'''package android.content.pm;public class PackageInfo{public long lastUpdateTime;public int versionCode=1;public long getLongVersionCode(){return 1;}}''',
'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager{public static class NameNotFoundException extends Exception{private static final long serialVersionUID=1L;}public PackageInfo getPackageInfo(String p,int flags)throws NameNotFoundException{return new PackageInfo();}}''',
'android/os/Build.java':'''package android.os;public class Build{public static class VERSION{public static int SDK_INT=35;}}''',
'android/os/Process.java':'''package android.os;public class Process{public static final int THREAD_PRIORITY_BACKGROUND=10;}''',
'android/os/SystemClock.java':'''package android.os;public class SystemClock{public static long elapsedRealtime(){return System.nanoTime()/1000000;}}''',
'android/os/HandlerThread.java':'''package android.os;public class HandlerThread{public HandlerThread(String name,int priority){}public void start(){}public Object getLooper(){return this;}}''',
'android/os/Handler.java':'''package android.os;public class Handler{public Handler(Object looper){}public void post(Runnable r){}public void postDelayed(Runnable r,long delay){}}''',
'android/database/Cursor.java':'''package android.database;import java.sql.*;import java.util.*;public class Cursor implements AutoCloseable{
 private final List<Object[]> rows=new ArrayList<>();private int position=-1;
 public Cursor(ResultSet rs)throws SQLException{int n=rs.getMetaData().getColumnCount();while(rs.next()){Object[] row=new Object[n];for(int i=0;i<n;i++)row[i]=rs.getObject(i+1);rows.add(row);}}
 public boolean moveToFirst(){position=0;return !rows.isEmpty();}public boolean moveToNext(){return ++position<rows.size();}
 public long getLong(int i){Object v=rows.get(position)[i];return v==null?0:((Number)v).longValue();}public int getInt(int i){return (int)getLong(i);}public String getString(int i){Object v=rows.get(position)[i];return v==null?null:v.toString();}public boolean isNull(int i){return rows.get(position)[i]==null;}public void close(){}
}''',
'android/database/sqlite/SQLiteDatabase.java':'''package android.database.sqlite;import java.sql.*;import java.io.*;import java.util.*;import android.database.Cursor;import android.content.ContentValues;
public class SQLiteDatabase implements AutoCloseable{
 public static final int CONFLICT_REPLACE=5;private final Connection connection;private boolean successful;
 private SQLiteDatabase(File file)throws Exception{Class.forName("org.sqlite.JDBC");connection=DriverManager.getConnection("jdbc:sqlite:"+file.getAbsolutePath());}
 public static SQLiteDatabase openOrCreateDatabase(File f,Object factory){try{return new SQLiteDatabase(f);}catch(Exception e){throw new IllegalStateException(e);}}
 private PreparedStatement prepare(String sql,Object[] args)throws SQLException{PreparedStatement p=connection.prepareStatement(sql);if(args!=null)for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;}
 public void execSQL(String sql){execSQL(sql,null);}public void execSQL(String sql,Object[] args){try(PreparedStatement p=prepare(sql,args)){p.execute();}catch(Exception e){throw new IllegalStateException(sql,e);}}
 public Cursor rawQuery(String sql,String[] args){try(PreparedStatement p=prepare(sql,args);ResultSet r=p.executeQuery()){return new Cursor(r);}catch(Exception e){throw new IllegalStateException(sql,e);}}
 public long insertOrThrow(String table,String nullColumn,ContentValues values){return insert(table,values,false);}public long insertWithOnConflict(String table,String nc,ContentValues v,int conflict){return insert(table,v,true);}
 private long insert(String table,ContentValues v,boolean replace){String keys=String.join(",",v.keySet()),marks=String.join(",",Collections.nCopies(v.size(),"?"));execSQL("INSERT "+(replace?"OR REPLACE ":"")+"INTO "+table+"("+keys+") VALUES("+marks+")",v.values().toArray());return 1;}
 public int delete(String table,String where,String[] args){execSQL("DELETE FROM "+table+(where==null?"":" WHERE "+where),args);return 0;}
 public void beginTransaction(){try{successful=false;connection.setAutoCommit(false);}catch(Exception e){throw new IllegalStateException(e);}}
 public void setTransactionSuccessful(){successful=true;}public void endTransaction(){try{if(successful)connection.commit();else connection.rollback();connection.setAutoCommit(true);}catch(Exception e){throw new IllegalStateException(e);}}
 public void close(){try{connection.close();}catch(Exception e){throw new IllegalStateException(e);}}
}''',
'android/database/sqlite/SQLiteOpenHelper.java':'''package android.database.sqlite;import android.content.Context;import android.database.Cursor;import java.io.File;
public abstract class SQLiteOpenHelper{private final Context context;private final String name;private final int version;private SQLiteDatabase db;
 public SQLiteOpenHelper(Context c,String n,Object f,int v){context=c;name=n;version=v;}public void setWriteAheadLoggingEnabled(boolean enabled){}
 public SQLiteDatabase getWritableDatabase(){if(db==null){db=SQLiteDatabase.openOrCreateDatabase(new File(context.root,name),null);int old;try(Cursor c=db.rawQuery("PRAGMA user_version",null)){c.moveToFirst();old=c.getInt(0);}if(old==0)onCreate(db);else if(old<version)onUpgrade(db,old,version);db.execSQL("PRAGMA user_version="+version);onOpen(db);}return db;}
 public SQLiteDatabase getReadableDatabase(){return getWritableDatabase();}public abstract void onCreate(SQLiteDatabase d);public abstract void onUpgrade(SQLiteDatabase d,int old,int next);public void onOpen(SQLiteDatabase d){}
}''',
'fr/erick/journallocal/ReferenceCatalog.java':'''package fr.erick.journallocal;import org.json.*;import android.content.Context;final class ReferenceCatalog{static ReferenceCatalog get(Context c){return new ReferenceCatalog();}String revision(){return "fixture";}JSONObject summary(){return EventStore.object("revision","fixture");}JSONArray network(String host,String kind){return "ads.example".equals(host)?new JSONArray().put(EventStore.object("tracker_id",7,"name","Fixture","host",host)):new JSONArray();}void flow(JSONObject f){}}''',
'fr/erick/journallocal/NetworkReport.java':'''package fr.erick.journallocal;import org.json.*;import android.content.Context;class NetworkReport{static JSONObject catalogueSummary(Context c){return EventStore.object("version","fixture");}}''',
'fr/erick/journallocal/PermissionUsage.java':'''package fr.erick.journallocal;import org.json.*;import android.content.Context;class PermissionUsage{static void enrich(Context c,JSONArray rows){for(int i=0;i<rows.length();i++)rows.getJSONObject(i).put("network_context",EventStore.object("schema","fixture-context"));}}''',
'fr/erick/journallocal/JournalSegments.java':'''package fr.erick.journallocal;final class JournalSegments{static final int LIMIT=50000;}''',
'fr/erick/journallocal/ApkEvidence.java':'''package fr.erick.journallocal;import android.content.Context;import org.json.*;final class ApkEvidence{static ApkEvidence get(Context c){return new ApkEvidence();}JSONObject read(String p,long v,long updated){return EventStore.object("status","PENDING");}}''',
'fr/erick/journallocal/EventStore.java':'''package fr.erick.journallocal;import android.content.Context;import android.database.sqlite.SQLiteDatabase;import org.json.*;final class EventStore{static final EventStore instance=new EventStore();static JSONArray events=new JSONArray();static EventStore get(Context c){return instance;}static JSONObject object(Object...kv){JSONObject o=new JSONObject();for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}long latestId(){return events.length();}SQLiteDatabase getReadableDatabase(){throw new UnsupportedOperationException("segment status outside this fixture");}JSONArray analysisBatch(long after,int limit){JSONArray r=new JSONArray();for(int i=(int)after;i<Math.min(events.length(),after+limit);i++)r.put(events.getJSONObject(i));return r;}}'''
}
probe=r'''
package fr.erick.journallocal;
import android.content.*;import android.database.*;import android.database.sqlite.*;import java.io.*;import java.util.*;import java.lang.reflect.*;import org.json.*;
public class SQLiteProbe{
 static long id;static JSONObject event(String corr,int uid,String host,boolean counter)throws Exception{
  JSONObject d=EventStore.object("flow_correlation_id",corr,"first_observed_ms",1000,"uid",uid,"packages",uid>=10000?new JSONArray().put("example.app"):new JSONArray(),"protocol","TCP","port",443,"remote_ip","192.0.2.1","tls_sni",host,"closed",false,"identity_status",uid<0?"PENDING":"RESOLVED");
  if(counter)ObservationValues.putCounters(d,120,40,2,1);else ObservationValues.emptyCounters(d);
  return EventStore.object("id",++id,"timestamp_ms",1000,"app",uid<0?"Application non identifiée":"Example","category","trafic","action","fixture","destination","192.0.2.1:443","details",d);
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static long scalar(SQLiteDatabase db,String sql){try(Cursor c=db.rawQuery(sql,null)){c.moveToFirst();return c.getLong(0);}}
 static TrackerIndex index(File root)throws Exception{Constructor<TrackerIndex> ctor=TrackerIndex.class.getDeclaredConstructor(Context.class);ctor.setAccessible(true);return ctor.newInstance(new Context(root));}
 public static void main(String[] args)throws Exception{
  File root=new File(args[0]);TrackerIndex index=index(new File(root,"current"));SQLiteDatabase db=index.getWritableDatabase();
  Method consume=TrackerIndex.class.getDeclaredMethod("consume",SQLiteDatabase.class,ReferenceCatalog.class,JSONObject.class);consume.setAccessible(true);ReferenceCatalog catalog=new ReferenceCatalog();
  JSONObject first=event("late-owner-flow",-1,"",true);consume.invoke(index,db,catalog,first);
  JSONObject f=index.flows("late-owner-flow",0,100).getJSONArray("flows").getJSONObject(0);
  check(f.getLong("tx_bytes")==120&&scalar(db,"SELECT COUNT(*) FROM hits")==0,"Non-catalogue flow/counters lost");
  JSONObject late=event("late-owner-flow",12345,"ads.example",false);consume.invoke(index,db,catalog,late);
  f=index.flows("late-owner-flow",0,100).getJSONArray("flows").getJSONObject(0);
  check(f.getLong("tx_bytes")==120&&ObservationValues.uniquePackage(f),"Enrichment erased previous counters");
  check(scalar(db,"SELECT volume_known FROM hits WHERE correlation='late-owner-flow'")==1,"Hit counters not retained");
  consume.invoke(index,db,catalog,event("late-owner-flow",-1,"ads.example",false));
  check(ObservationValues.uniquePackage(index.flows("late-owner-flow",0,100).getJSONArray("flows").getJSONObject(0)),"Later missing UID erased prior evidence");
  consume.invoke(index,db,catalog,event("conflicting-uid-flow",12345,"ads.example",true));
  consume.invoke(index,db,catalog,event("conflicting-uid-flow",23456,"ads.example",true));
  JSONObject conflict=index.flows("conflicting-uid-flow",0,100).getJSONArray("flows").getJSONObject(0);
  check(conflict.getBoolean("identity_conflict")&&!ObservationValues.uniquePackage(conflict),"Contradictory UIDs treated as certain owner");
  JSONObject unknown=event("missing-volume-flow",-1,"ads.example",false);consume.invoke(index,db,catalog,unknown);
  JSONObject trail=index.trail("missing-volume-flow");check(trail.getJSONArray("steps").getJSONObject(0).isNull("tx_bytes"),"SQLite missing counter became zero");
  JSONObject zero=event("zero-volume-flow",12345,"ads.example",false);ObservationValues.putCounters(zero.getJSONObject("details"),0,0,0,0);consume.invoke(index,db,catalog,zero);
  check(index.trail("zero-volume-flow").getJSONArray("steps").getJSONObject(0).getLong("tx_bytes")==0,"SQLite observed zero lost");
  JSONObject reserved=event("reserved-uid-flow",1000,"ads.example",true);reserved.getJSONObject("details").put("packages",new JSONArray().put("android"));consume.invoke(index,db,catalog,reserved);
  try(Cursor c=db.rawQuery("SELECT package_name FROM hits WHERE correlation='reserved-uid-flow'",null)){c.moveToFirst();check(c.getString(0).isEmpty(),"Reserved UID promoted package candidate to owner");}
  JSONObject groups=index.groups("Example",0,100);check(groups.getJSONArray("rows").getJSONObject(0).getJSONObject("apk").isNull("present"),"PENDING APK treated as observed absence");
  System.out.println("PASS real SQLite: all flows, late owner, max counters, unknown/zero, reserved UID and unknown APK evidence");

  for(int i=0;i<34;i++)consume.invoke(index,db,catalog,event("same-time-journey-"+i,12345,"ads.example",true));
  long before=0;Set<String> seen=new HashSet<>();boolean more;
  do{JSONObject page=index.journeys("example.app",7,before,30);JSONArray rows=page.getJSONArray("rows");for(int i=0;i<rows.length();i++)check(seen.add(rows.getJSONObject(i).getString("correlation")),"Journey repeated");before=page.getLong("next_before");more=page.getBoolean("has_more");}while(more);
  check(seen.size()==36,"Equal timestamps dropped journeys");
  for(int i=0;i<501;i++)consume.invoke(index,db,catalog,event("long-trail-flow",-1,"ads.example",false));
  long after=0;int steps=0;do{JSONObject page=index.trail("long-trail-flow",after,250);JSONArray rows=page.getJSONArray("steps");for(int i=0;i<rows.length();i++){long next=rows.getJSONObject(i).getLong("event_id");check(next>after,"Trail order/cursor");after=next;steps++;}more=page.getBoolean("has_more");}while(more);
  check(steps==501,"Trail truncated permanently");
  for(int i=0;i<105;i++)consume.invoke(index,db,catalog,event("page-only-"+i,-1,"",true));
  before=0;int flows=0;do{JSONObject page=index.flows("page-only-",before,100);flows+=page.getJSONArray("flows").length();before=page.getLong("next_before_id");more=page.getBoolean("has_more");}while(more);check(flows==105,"Flows paging lost connections");
  check(index.groups("' OR 1=1",0,100).getJSONArray("rows").length()==0,"Search not bound");
  System.out.println("PASS real SQLite: equal-time journeys, 501 trail steps, 105-flow paging, bound search");

  File legacyDir=new File(root,"legacy");legacyDir.mkdirs();try(SQLiteDatabase old=SQLiteDatabase.openOrCreateDatabase(new File(legacyDir,"tracker-index.sqlite"),null)){
   old.execSQL("CREATE TABLE progress(id INTEGER PRIMARY KEY,checkpoint INTEGER,revision TEXT)");old.execSQL("INSERT INTO progress VALUES(1,900,'trail-v2:fixture')");
   old.execSQL("CREATE TABLE flows(correlation TEXT PRIMARY KEY,latest INTEGER,search TEXT,payload TEXT)");
   __LEGACY_TABLES__
   old.execSQL("INSERT INTO flows VALUES('legacy-zero',900,'old','{}')");old.execSQL("PRAGMA user_version=2");
  }
  TrackerIndex upgraded=index(legacyDir);SQLiteDatabase old=upgraded.getWritableDatabase();
  check(scalar(old,"PRAGMA user_version")==3&&scalar(old,"SELECT checkpoint FROM progress")==0&&scalar(old,"SELECT COUNT(*) FROM flows")==0,"Derived v2 migration did not reset ambiguous zeros");
  EventStore.events.put(event("rebuild-raw-flow",-1,"",true).put("id",1));
  Method advance=TrackerIndex.class.getDeclaredMethod("advance");advance.setAccessible(true);advance.invoke(upgraded);
  check(scalar(old,"SELECT checkpoint FROM progress")==1&&scalar(old,"SELECT COUNT(*) FROM flows")==1&&EventStore.events.length()==1,"Index rebuild changed raw journal or did not resume");
  old.execSQL("DROP TABLE progress");advance.invoke(upgraded);check(EventStore.events.length()==1,"Derived failure changed raw journal");
  System.out.println("PASS real SQLite: v2→v3 derived migration and rebuild preserve raw source");

  String beforeReport;try(Cursor c=db.rawQuery("SELECT payload FROM flows ORDER BY latest LIMIT 1",null)){c.moveToFirst();beforeReport=c.getString(0);}
  StringWriter networkWriter=new StringWriter();index.exportReport(networkWriter);JSONObject networkExport=new JSONObject(networkWriter.toString());JSONArray reportFlows=networkExport.getJSONArray("flows");
  check(networkExport.getString("schema").equals("aiv-network-report/1")&&networkExport.getLong("exported_flows")==reportFlows.length(),"derived report malformed or wrong count");
  check(networkExport.has("index_status")&&networkExport.has("service_catalogue"),"derived report lost coverage/catalogue");
  for(int i=0;i<reportFlows.length();i++)check(reportFlows.getJSONObject(i).has("network_context"),"derived report dropped enriched context");
  try(Cursor c=db.rawQuery("SELECT payload FROM flows ORDER BY latest LIMIT 1",null)){c.moveToFirst();check(c.getString(0).equals(beforeReport),"derived report rewrote stored flow");}
  final List<String> raw=new ArrayList<>();id=0;for(int i=0;i<503;i++)raw.add(event("snapshot-flow-"+i,-1,"",true).toString());
  SnapshotExporter.Source source=new SnapshotExporter.Source(){public long[] snapshot(){return new long[]{503,503};}public List<String> page(long a,long ceiling){return raw.subList((int)a,Math.min((int)ceiling,(int)a+200));}};
  File file=new File(root,"export.sqlite");SQLiteSnapshot.write(file,source);
  try(SQLiteDatabase export=SQLiteDatabase.openOrCreateDatabase(file,null)){
   check(scalar(export,"SELECT COUNT(*) FROM events")==503,"SQLite export count");
   try(Cursor c=export.rawQuery("SELECT payload FROM events ORDER BY id",null)){int n=0;while(c.moveToNext())check(c.getString(0).equals(raw.get(n++)),"SQLite export mutated observation");}
   try(Cursor c=export.rawQuery("SELECT payload FROM metadata WHERE key='snapshot'",null)){c.moveToFirst();JSONObject meta=new JSONObject(c.getString(0));check(meta.getJSONObject("integrity").getBoolean("complete")&&meta.getJSONObject("quality").getLong("unknown_events")==503&&BuildMetadata.VERSION_NAME.equals(meta.getString("application_version")),"SQLite export metadata/quality");}
  }
  boolean rejected=false;try{SQLiteSnapshot.write(new File(root,"incomplete.sqlite"),new SnapshotExporter.Source(){public long[] snapshot(){return new long[]{503,504};}public List<String> page(long a,long ceiling)throws Exception{return source.page(a,ceiling);}});}catch(IOException expected){rejected=true;}
  check(rejected,"Incomplete SQLite export certified complete");
  System.out.println("PASS real SQLite: 503-record streaming export, unchanged raw, build/quality metadata, incomplete rejection");
 }
}
'''
# Reuse the actual historical definitions, before v3 adds explicit known flags.
import re,json
tables=re.findall(r'db.execSQL\("(CREATE TABLE IF NOT EXISTS (?:steps|hits)\([^"\n]+)"\)',(JAVA/'TrackerIndex.java').read_text())
assert len(tables)==2
probe=probe.replace('__LEGACY_TABLES__','\n'.join('old.execSQL('+json.dumps(sql)+');' for sql in tables))
with tempfile.TemporaryDirectory() as directory:
    tmp=Path(directory);src=tmp/'src';classes=tmp/'classes';classes.mkdir()
    for name,content in stubs.items():
        path=src/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
    path=src/'fr/erick/journallocal/SQLiteProbe.java';path.write_text(probe)
    files=list(src.rglob('*.java'))+[JAVA/(x+'.java') for x in ['TrackerIndex','ObservationValues','NetworkQuality','ExportMetadata','SnapshotExporter','SQLiteSnapshot','JournalRecovery','JsonSyntax']]
    files.append(ROOT/'build/generated/fr/erick/journallocal/BuildMetadata.java')
    subprocess.run(['javac','-encoding','UTF-8','-cp',classpath,'-d',str(classes),*map(str,files)],check=True)
    subprocess.run(['java','-Xmx256m','-cp',str(classes)+os.pathsep+classpath,'fr.erick.journallocal.SQLiteProbe',str(tmp/'data')],check=True)
