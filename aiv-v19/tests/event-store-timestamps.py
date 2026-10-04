#!/usr/bin/env python3
"""Production EventStore insert methods, real SQLite; Android scheduling is stubbed.
Verify capture time survives delayed/out-of-order persistence and hashed export.
"""
from pathlib import Path
import ast
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/fr/erick/journallocal'
classpath = os.pathsep.join(os.environ[k] for k in ('AIV_JSON_JAR', 'AIV_SQLITE_JAR', 'AIV_SLF4J_JAR'))
# Read only literal fixture definitions; do not import or re-run the other suite.
tree = ast.parse((ROOT / 'tests/journal-sqlite.py').read_text())
stubs = ast.literal_eval(next(n.value for n in tree.body if isinstance(n, ast.Assign)
                             and any(isinstance(t, ast.Name) and t.id == 'stubs' for t in n.targets)))

def extract(source, signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

source = (JAVA / 'EventStore.java').read_text()
methods = '\n'.join(extract(source, signature) for signature in (
    'public static JSONObject object(', 'public synchronized boolean add(',
    'public synchronized boolean addObserved(', '@Override public void onCreate('))
stubs['fr/erick/journallocal/EventStore.java'] = '''package fr.erick.journallocal;
import android.content.*;import android.database.sqlite.*;import android.os.SystemClock;
import org.json.*;import java.time.Instant;import java.util.Locale;
public class EventStore extends SQLiteOpenHelper{
 final Context context;static String lastError="";static final String PROCESS_SESSION="host-fixture";
 EventStore(Context c){super(c,"journal.sqlite",null,1);context=c;}
 public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException();}
 __METHODS__
}
class AnomalyMonitor{static void request(Context c){}}
class TrackerIndex{static TrackerIndex get(Context c){return new TrackerIndex();}void request(){}}
'''.replace('__METHODS__', methods)
stubs['fr/erick/journallocal/JournalSegments.java'] = '''package fr.erick.journallocal;
import android.content.Context;class JournalSegments{static void request(Context c){}}'''
probe = r'''package fr.erick.journallocal;
import android.content.*;import android.database.*;import android.database.sqlite.*;
import java.io.*;import java.util.*;import org.json.*;
public class TimestampProbe{
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  EventStore store=new EventStore(new Context(new File(args[0])));long capture=System.currentTimeMillis()-5000;
  JSONObject d=EventStore.object("uid",-1,"protocol","UDP","observation_type","COUNTER_SNAPSHOT");ObservationValues.putCounters(d,44,44,1,1);
  check(store.addObserved("trafic","Application non identifiée","Trafic observé","127.0.0.1","Loopback","VPN local",d,capture,1234),"capture insert");
  // A different producer may have persisted a newer event before an older capture.
  check(store.add("collecteur","AIV","État récent","VPN","Interne","fixture",new JSONObject()),"legacy add");
  check(store.addObserved("trafic","Application non identifiée","Capture antérieure","127.0.0.1","Loopback","VPN local",d,capture-1000,234),"out-of-order capture insert");
  List<String> rows=new ArrayList<>();SQLiteDatabase db=store.getReadableDatabase();
  try(Cursor c=db.rawQuery("SELECT id,timestamp_ms,payload FROM events ORDER BY id",null)){
   while(c.moveToNext()){JSONObject e=new JSONObject(c.getString(2));check(c.getLong(1)==e.getLong("timestamp_ms"),"SQLite/payload time mismatch");e.put("id",c.getLong(0));rows.add(e.toString());}
  }
  check(rows.size()==3,"event count");JSONObject first=new JSONObject(rows.get(0)),last=new JSONObject(rows.get(2));
  check(first.getLong("timestamp_ms")==capture&&first.getLong("elapsed_ms")==1234&&first.getLong("persisted_at_ms")>=capture+5000,"capture time replaced by persistence time");
  check(last.getLong("timestamp_ms")==capture-1000&&last.getLong("elapsed_ms")==234,"old capture reordered/retimed");
  check(first.getJSONObject("details").getInt("uid")==-1&&first.getJSONObject("details").getLong("tx_bytes")==44,"UNKNOWN volume lost");
  SnapshotExporter.Source snapshot=new SnapshotExporter.Source(){public long[] snapshot(){return new long[]{3,3};}public List<String> page(long after,long ceiling){return after==0?rows:Collections.emptyList();}};
  for(boolean jsonl:new boolean[]{false,true}){
   StringWriter w=new StringWriter();SnapshotExporter.write(w,jsonl,snapshot);final int[] at={0};
   JSONObject recovered=JournalRecovery.recover(new StringReader(w.toString()),raw->{check(new JSONObject(raw).similar(new JSONObject(rows.get(at[0]++))),"hashed export changed capture");});
   check(at[0]==3&&recovered.getBoolean("document_complete")&&"verifiee".equals(recovered.getString("source_integrity")),"export integrity");
   JSONObject footer=jsonl?new JSONObject(w.toString().trim().substring(w.toString().trim().lastIndexOf('\n')+1)):new JSONObject(w.toString());
   check(footer.getJSONObject("quality").getLong("first_event_ms")==capture-1000,"export period follows insertion order");
  }
  db.close();System.out.println("PASS production EventStore: capture/persistence times, UNKNOWN IP volumes, SQLite and hashed JSON/JSONL with out-of-order captures");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    src = tmp / 'src'
    classes = tmp / 'classes'
    classes.mkdir()
    for name, content in stubs.items():
        path = src / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
    (src / 'fr/erick/journallocal/TimestampProbe.java').write_text(probe)
    files = list(src.rglob('*.java')) + [JAVA / (n + '.java') for n in
        ('ObservationValues', 'NetworkQuality', 'ExportMetadata', 'SnapshotExporter', 'JournalRecovery', 'JsonSyntax')]
    files.append(ROOT / 'build/generated/fr/erick/journallocal/BuildMetadata.java')
    subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), *map(str, files)], check=True, timeout=30)
    subprocess.run(['java', '-Xmx256m', '-cp', str(classes) + os.pathsep + classpath,
                    'fr.erick.journallocal.TimestampProbe', str(tmp / 'data')], check=True, timeout=30)
