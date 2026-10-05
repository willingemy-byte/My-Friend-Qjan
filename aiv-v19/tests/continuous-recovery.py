#!/usr/bin/env python3
"""Execute production Continuous with durable prefs and controlled service starts."""
from pathlib import Path
import ast,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/fr/erick/journallocal'
tree=ast.parse((ROOT/'tests/archive-sync.py').read_text())
fixture=ast.literal_eval(next(n.value.args[0] for n in tree.body if isinstance(n,ast.Expr) and isinstance(n.value,ast.Call) and isinstance(n.value.func,ast.Attribute) and n.value.func.attr=='update'))
stubs={
'android/content/SharedPreferences.java':fixture['android/content/SharedPreferences.java'],
'android/content/Intent.java':'''package android.content;public class Intent{public final Class<?> type;public Intent(Context c,Class<?> t){type=t;}}''',
'android/app/NotificationManager.java':'''package android.app;public class NotificationManager{public int cancellations;public boolean fail;public void cancel(int id){if(fail)throw new IllegalStateException("fixture notification unavailable");if(id!=2)throw new AssertionError("unexpected notification");cancellations++;}}''',
'android/content/Context.java':'''package android.content;import java.io.*;import java.util.*;import fr.erick.journallocal.*;public class Context{
public final File root;private final Map<String,SharedPreferences> prefs=new HashMap<>();public final List<String> starts=new ArrayList<>();public final android.app.NotificationManager notifications=new android.app.NotificationManager();public boolean denyRecorder,activateImmediately=true;public int stops;
public Context(File r){root=r;root.mkdirs();}public SharedPreferences getSharedPreferences(String n,int mode){return prefs.computeIfAbsent(n,k->new SharedPreferences(new File(root,n+".properties")));}public <T>T getSystemService(Class<T> t){return t.cast(notifications);}
public void startForegroundService(Intent i){if(denyRecorder&&i.type==RecorderService.class)throw new IllegalStateException("fixture background restriction");starts.add(i.type.getSimpleName());if(activateImmediately){if(i.type==RecorderService.class)RecorderService.running=true;if(i.type==NetworkCaptureService.class)NetworkCaptureService.running=true;if(i.type==WatcherService.class)WatcherService.running=true;}}
public void stopService(Intent i){stops++;}
}''',
'android/net/VpnService.java':'''package android.net;import android.content.*;public class VpnService{public static boolean permitted=true;public static Intent prepare(Context c){return permitted?null:new Intent(c,VpnService.class);}}''',
'fr/erick/journallocal/RecorderService.java':'''package fr.erick.journallocal;public class RecorderService{public static boolean running;}''',
'fr/erick/journallocal/NetworkCaptureService.java':'''package fr.erick.journallocal;public class NetworkCaptureService{public static boolean running,starting;public static String lastError="";}''',
'fr/erick/journallocal/WatcherService.java':'''package fr.erick.journallocal;import android.content.*;public class WatcherService{public static boolean running;static void start(Context c){c.startForegroundService(new Intent(c,WatcherService.class));}static void stop(Context c){running=false;c.stopService(new Intent(c,WatcherService.class));}}''',
'fr/erick/journallocal/EventStore.java':'''package fr.erick.journallocal;class EventStore{static String lastError="";}class AivStore{static String error="";}'''
}
with tempfile.TemporaryDirectory() as directory:
    tmp=Path(directory);src=tmp/'src';classes=tmp/'classes';classes.mkdir()
    for name,content in stubs.items():
        path=src/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
    files=list(src.rglob('*.java'))+[JAVA/'Continuous.java',ROOT/'tests/ContinuousRecoveryTest.java']
    subprocess.run(['javac','-encoding','UTF-8','-d',str(classes),*map(str,files)],check=True,timeout=30)
    subprocess.run(['java','-cp',str(classes),'fr.erick.journallocal.ContinuousRecoveryTest',str(tmp/'data')],check=True,timeout=30)
