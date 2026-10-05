#!/usr/bin/env python3
"""Real archive against SQLite and durable preferences.
HTTP and Android handlers are controlled host fixtures.
"""
from pathlib import Path
import ast,os,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/fr/erick/journallocal'
classpath=os.pathsep.join(os.environ[k] for k in ('AIV_JSON_JAR','AIV_SQLITE_JAR','AIV_SLF4J_JAR'))
tree=ast.parse((ROOT/'tests/journal-sqlite.py').read_text())
stubs=ast.literal_eval(next(n.value for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='stubs' for t in n.targets)))
for name in list(stubs):
    if name.startswith('fr/erick/') or name.endswith(('SQLiteOpenHelper.java','ContentValues.java','PackageInfo.java','PackageManager.java')):del stubs[name]
stubs['android/content/ContentValues.java']='package android.content;public class ContentValues extends java.util.LinkedHashMap<String,Object>{}'
stubs.update({
'android/os/Looper.java':'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os;public class Handler{public Handler(Object l){}public void post(Runnable r){r.run();}public void postDelayed(Runnable r,long d){}}',
'android/content/Intent.java':'package android.content;public class Intent{public Intent(Context c,Class<?> t){}}',
'android/util/Base64.java':'package android.util;public class Base64{public static final int NO_WRAP=1,URL_SAFE=2,NO_PADDING=4;public static String encodeToString(byte[] b,int f){return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);}}',
'android/content/SharedPreferences.java':r'''package android.content;import java.io.*;import java.util.*;public class SharedPreferences{
 final File file;final Properties values=new Properties();public SharedPreferences(File f){file=f;try{if(f.exists())try(InputStream in=new FileInputStream(f)){values.load(in);}}catch(Exception e){throw new RuntimeException(e);}}
 public boolean contains(String k){return values.containsKey(k);}public String getString(String k,String d){return values.getProperty(k,d);}public long getLong(String k,long d){return Long.parseLong(values.getProperty(k,""+d));}public int getInt(String k,int d){return (int)getLong(k,d);}public boolean getBoolean(String k,boolean d){return Boolean.parseBoolean(values.getProperty(k,""+d));}public Editor edit(){return new Editor();}
 public class Editor{final Map<String,String> updates=new HashMap<>();public Editor putString(String k,String v){updates.put(k,v);return this;}public Editor putLong(String k,long v){return putString(k,""+v);}public Editor putInt(String k,int v){return putLong(k,v);}public Editor putBoolean(String k,boolean v){return putString(k,""+v);}public boolean commit(){values.putAll(updates);try{file.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(file)){values.store(out,"fixture");}return true;}catch(Exception e){return false;}}public void apply(){commit();}}
}''',
'android/content/Context.java':r'''package android.content;import java.io.*;import java.util.*;public class Context{
 public static final int MODE_PRIVATE=0;public final File root;private final Map<String,SharedPreferences> prefs=new HashMap<>();
 public Context(File r){root=r;root.mkdirs();}public Context getApplicationContext(){return this;}public SharedPreferences getSharedPreferences(String n,int mode){return prefs.computeIfAbsent(n,k->new SharedPreferences(new File(root,"prefs/"+n+".properties")));}
}''',
'fr/erick/journallocal/EventStore.java':r'''package fr.erick.journallocal;import android.content.*;import android.database.*;import android.database.sqlite.*;import org.json.*;import java.io.*;
final class EventStore{static EventStore instance;static String lastError="";final SQLiteDatabase db;
 EventStore(Context c){db=SQLiteDatabase.openOrCreateDatabase(new File(c.root,"journal.sqlite"),null);db.execSQL("CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY,timestamp_ms INTEGER,app TEXT,action TEXT,destination TEXT,transport TEXT,category TEXT,payload TEXT)");JournalSegments.install(db);instance=this;}
 static EventStore get(Context c){return instance;}SQLiteDatabase getWritableDatabase(){return db;}SQLiteDatabase getReadableDatabase(){return db;}long latestId(){try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM events",null)){c.moveToFirst();return c.getLong(0);}}
 static JSONObject object(Object...kv){JSONObject o=new JSONObject();for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}
}
'''
})
with tempfile.TemporaryDirectory() as directory:
    tmp=Path(directory);src=tmp/'src';classes=tmp/'classes';classes.mkdir()
    for name,content in stubs.items():
        f=src/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(content)
    files=list(src.rglob('*.java'))+[JAVA/(n+'.java') for n in ('ArchiveSync','JournalSegments')]+[ROOT/'tests/ArchiveSyncTest.java']
    subprocess.run(['javac','-encoding','UTF-8','-cp',classpath,'-d',str(classes),*map(str,files)],check=True,timeout=30)
    subprocess.run(['java','-Xmx512m','-cp',str(classes)+os.pathsep+classpath,'fr.erick.journallocal.ArchiveSyncTest',str(tmp/'data')],check=True,timeout=90)
