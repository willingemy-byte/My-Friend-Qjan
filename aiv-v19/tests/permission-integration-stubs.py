#!/usr/bin/env python3
"""Small host fixtures for the real permission controller, isolated from APK sources."""
from pathlib import Path
import sys
root=Path(sys.argv[1])
sources={
"android/os/Process.java": '''package android.os; public class Process {public static int myUid(){return 10123;}}''',
"android/os/Build.java": '''package android.os; public class Build {public static class VERSION {public static final int SDK_INT=36;}}''',
"android/os/SystemClock.java": '''package android.os;public class SystemClock {public static long offset;public static long elapsedRealtime(){return System.nanoTime()/1000000+offset;}}''',
"android/content/pm/PackageInfo.java": '''package android.content.pm; public class PackageInfo {public String packageName,signer="fixture-signer";public ApplicationInfo applicationInfo;public String[] requestedPermissions;public int versionCode=1;public long firstInstallTime=1,lastUpdateTime=1;public long getLongVersionCode(){return versionCode;}}''',
"android/content/pm/ApplicationInfo.java": '''package android.content.pm; public class ApplicationInfo {public static final int FLAG_SYSTEM=1,FLAG_STOPPED=2,FLAG_UPDATED_SYSTEM_APP=4;public int uid,flags,targetSdkVersion=36;public String label;public CharSequence loadLabel(PackageManager pm){return label;}}''',
"android/content/pm/PermissionInfo.java": '''package android.content.pm; public class PermissionInfo {public String name;public int protectionLevel;public CharSequence loadLabel(PackageManager pm){return name;}public CharSequence loadDescription(PackageManager pm){return "Fixture description";}}''',
"android/content/pm/PackageManager.java": '''package android.content.pm; import java.util.*; public class PackageManager {
public static final int PERMISSION_GRANTED=0,GET_SIGNING_CERTIFICATES=1,GET_SIGNATURES=2,GET_PERMISSIONS=4,MATCH_DISABLED_COMPONENTS=8,COMPONENT_ENABLED_STATE_DISABLED_USER=3;
public static class NameNotFoundException extends Exception {}
public final Map<String,PackageInfo> apps=new LinkedHashMap<>();public final Map<String,Boolean> grants=new HashMap<>();public final Map<String,String> flags=new HashMap<>(),guards=new HashMap<>(),ops=new HashMap<>();public final Map<String,Integer> enabled=new HashMap<>(),protection=new HashMap<>();
public List<PackageInfo> getInstalledPackages(int f){return new ArrayList<>(apps.values());}
public PackageInfo getPackageInfo(String p,int f)throws NameNotFoundException{if(!apps.containsKey(p))throw new NameNotFoundException();return apps.get(p);}
public int checkPermission(String n,String p){return grants.getOrDefault(p+"|"+n,false)?0:-1;}
public PermissionInfo getPermissionInfo(String n,int f){PermissionInfo p=new PermissionInfo();p.name=n;p.protectionLevel=protection.getOrDefault(n,n.endsWith("SYSTEM_ALERT_WINDOW")?2:1);return p;}
public int getApplicationEnabledSetting(String p){return enabled.getOrDefault(p,0);}
public String[] getPackagesForUid(int uid){return apps.values().stream().filter(p->p.applicationInfo.uid==uid).map(p->p.packageName).toArray(String[]::new);}
}''',
"android/content/res/AssetManager.java": '''package android.content.res; import java.io.*;public class AssetManager {private final File root;public AssetManager(File root){this.root=root;}public InputStream open(String name)throws IOException{return new FileInputStream(new File(root,name));}}''',
"android/content/SharedPreferences.java": '''package android.content;import java.util.*;public class SharedPreferences {
private final Map<String,Object> values=new HashMap<>();public String getString(String k,String d){return (String)values.getOrDefault(k,d);}public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}public Map<String,?> getAll(){return new HashMap<>(values);}public Editor edit(){return new Editor();}
public class Editor {private final Map<String,Object> changes=new HashMap<>();public Editor putString(String k,String v){changes.put(k,v);return this;}public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}public boolean commit(){values.putAll(changes);return true;}}
}''',
"android/content/Context.java": '''package android.content;import java.io.*;import java.util.*;import android.content.pm.*;import android.content.res.*;public class Context {
public static final int MODE_PRIVATE=0;private final File files;private final AssetManager assets;private final Map<String,SharedPreferences> preferences=new HashMap<>();public final PackageManager pm=new PackageManager();
public Context(File f,File a){files=f;assets=new AssetManager(a);}public File getFilesDir(){return files;}public AssetManager getAssets(){return assets;}public PackageManager getPackageManager(){return pm;}public SharedPreferences getSharedPreferences(String n,int m){return preferences.computeIfAbsent(n,k->new SharedPreferences());}public Context getApplicationContext(){return this;}public String getPackageName(){return "com.aiv.test";}public <T>T getSystemService(Class<T> t){try{return t.getDeclaredConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
}''',
"android/content/Intent.java": '''package android.content;public class Intent {public Intent(Context c,Class<?> t){}public Intent putExtra(String k,boolean v){return this;}}''',
"android/app/NotificationManager.java": '''package android.app;public class NotificationManager {public static final int IMPORTANCE_DEFAULT=3;public void createNotificationChannel(NotificationChannel c){}public void notify(int id,Notification n){}}''',
"android/app/NotificationChannel.java": '''package android.app;public class NotificationChannel {public NotificationChannel(String i,String n,int level){}}''',
"android/app/PendingIntent.java": '''package android.app;public class PendingIntent {public static final int FLAG_IMMUTABLE=1,FLAG_UPDATE_CURRENT=2;public static PendingIntent getActivity(android.content.Context c,int r,android.content.Intent i,int f){return new PendingIntent();}}''',
"android/app/Notification.java": '''package android.app;public class Notification {public static class Builder {public Builder(android.content.Context c,String n){}public Builder setSmallIcon(int i){return this;}public Builder setContentTitle(String s){return this;}public Builder setContentText(String s){return this;}public Builder setContentIntent(PendingIntent p){return this;}public Builder setAutoCancel(boolean v){return this;}public Notification build(){return new Notification();}}}''',
"android/R.java": '''package android;public class R {public static class drawable {public static final int ic_menu_info_details=1;}}''',
"android/util/AtomicFile.java": '''package android.util;import java.io.*;public class AtomicFile {public static boolean failPolicy;private final File target,temporary;public AtomicFile(File f){target=f;temporary=new File(f+".tmp");}public FileOutputStream startWrite()throws IOException{if(failPolicy&&target.getName().equals("policy.json"))throw new IOException("Fixture full disk");return new FileOutputStream(temporary);}public void finishWrite(FileOutputStream out)throws IOException{out.close();java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}public void failWrite(FileOutputStream out)throws IOException{out.close();temporary.delete();}public InputStream openRead()throws IOException{return new FileInputStream(target);}}''',
"rikka/shizuku/Shizuku.java": '''package rikka.shizuku;public class Shizuku {public static boolean available=true;public static boolean pingBinder(){return available;}public static boolean isPreV11(){return false;}public static int checkSelfPermission(){return 0;}public static int getUid(){return 2000;}}''',
"fr/erick/journallocal/FixtureDependencies.java": '''package fr.erick.journallocal;import android.content.*;import android.content.pm.*;import org.json.*;import java.util.*;
class AivConfig {static final int CONTROL_REPORT_MAX_BYTES=8388608,CONTROL_BATCH_MAX_APPS=50,REFERENCE_MAX_APPS=20000,CONTROL_WATCH_MAX_FAILURES=3;static final long CONTROL_PREVIEW_VALID_MS=300000;}
class AccessPolicy {static final int DISTRIBUTION_TIER=3;static boolean allows(String s,int t){return true;}}
class AppIdentity {static JSONObject forPackage(Context c,PackageInfo p){return EventStore.object("app_identity_id",p.packageName+":"+p.signer,"current_signer_sha256",p.signer.isEmpty()?new JSONArray():new JSONArray().put(p.signer));}}
class DeveloperControl {static String permissionTargetReason(Context c,String p){return c.pm.guards.getOrDefault(p,"");}}
class ApkEvidence {static ApkEvidence get(Context c){return new ApkEvidence();}static String hex(byte[] b){return java.util.HexFormat.of().formatHex(b);}JSONObject read(String p,long v,long u){return EventStore.object("status","ANALYZED","trackers",new JSONArray());}}
class ReferenceCatalog {static ReferenceCatalog get(Context c){return new ReferenceCatalog();}JSONObject permission(String n){return EventStore.object("label",n);}JSONObject summary(){return new JSONObject();}}
class EventStore {static boolean writable=true;static int calls;static Runnable pendingHook;static final List<JSONObject> receipts=new ArrayList<>();static EventStore get(Context c){return new EventStore();}boolean add(Object... args){calls++;if(!writable)return false;if(args.length==7&&args[6] instanceof JSONObject){JSONObject r=new JSONObject(args[6].toString());receipts.add(r);if("pending".equals(r.optString("phase"))&&pendingHook!=null){Runnable hook=pendingHook;pendingHook=null;hook.run();}}return true;}static JSONObject object(Object... args){JSONObject o=new JSONObject();for(int i=0;i<args.length;i+=2)o.put((String)args[i],args[i+1]);return o;}}
class PermissionAudit {static PermissionAudit get(Context c){return new PermissionAudit();}void scan(){}}
class Continuous {static boolean active=true;static boolean enabled(Context c){return active;}}
class MainActivity {}
''',
"fr/erick/journallocal/ControlShell.java": '''package fr.erick.journallocal;
import android.content.*;import android.content.pm.*;import java.nio.file.*;import org.json.*;
class ControlShell {
static Context context;static int mutations,cancelAfter,opQueries;static String refuse="",noEffect="";static boolean failResultLog;static final class Result {int code;String out,err="";boolean complete=true;Result(String out){this.out=out;}}
static Result run(String command)throws Exception{return run(command,16384);}
static Result run(String command,int limit)throws Exception{
PackageManager pm=context.pm;String[] quoted=command.split("'");String pkg=quoted.length>1?quoted[1]:"";
if(command.startsWith("dumpsys")){
 if(pkg.endsWith("unknown"))return new Result("unrecognized output");
 StringBuilder text=new StringBuilder("Package ["+pkg+"] (fixture):\\n User 0: installed=true\\n runtime permissions:\\n");
 for(String permission:pm.apps.get(pkg).requestedPermissions)if(!permission.endsWith("SYSTEM_ALERT_WINDOW"))text.append("  ").append(permission).append(": granted=").append(pm.grants.getOrDefault(pkg+"|"+permission,false)).append(", flags=[").append(pm.flags.getOrDefault(pkg+"|"+permission,"")).append("]\\n");
 return new Result(text.toString());
}
if(command.startsWith("cmd appops get")){opQueries++;String mode=pm.ops.getOrDefault(pkg,"default");return new Result(mode.equals("uid")?"Uid mode: SYSTEM_ALERT_WINDOW: allow":"SYSTEM_ALERT_WINDOW: "+mode);}
boolean packageAction=command.startsWith("pm disable-user")||command.startsWith("am force-stop")||command.startsWith("pm enable")||command.startsWith("pm default-state")||command.startsWith("pm disable-until-used")||command.startsWith("pm disable --");
String permission=!packageAction&&command.startsWith("pm ")?quoted[3]:"android.permission.SYSTEM_ALERT_WINDOW";
if(!packageAction&&!command.startsWith("pm revoke")&&!command.startsWith("pm grant")&&!command.startsWith("cmd appops set"))throw new AssertionError("Unexpected mutation "+command);
boolean durable=false;
java.io.File[] reports=new java.io.File(context.getFilesDir(),"permission-control").listFiles();if(reports==null)reports=new java.io.File[0];
for(java.io.File f:reports)if(f.getName().matches("[a-f0-9-]{36}\\\\.json")){
 JSONObject report=new JSONObject(Files.readString(f.toPath()));JSONArray rows=report.optJSONArray("entries");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(command.equals(row.optString("command"))&&"pending".equals(row.optString("outcome")))durable=true;}
}
java.io.File maintenance=new java.io.File(context.getFilesDir(),"permission-maintenance/last-action.json");
if(maintenance.isFile()){JSONObject r=new JSONObject(Files.readString(maintenance.toPath()));if(command.equals(r.optString("command"))&&"pending".equals(r.optString("phase")))durable=true;}
if(!durable)throw new AssertionError("No durable receipt before "+command);
if(command.startsWith("pm default-state")||command.startsWith("pm enable")){
 JSONObject reference=new JSONObject(Files.readString(new java.io.File(context.getFilesDir(),"permission-maintenance/policy.json").toPath()));
 if(!reference.getJSONObject("known").has(pkg)||reference.getJSONObject("pending").has(pkg))throw new AssertionError("Approval not durable before enabling "+pkg);
}
if(ControlCoordinator.acquire()){ControlCoordinator.release();throw new AssertionError("Executor lease missing");}
mutations++;Result result=new Result("");if(!refuse.isEmpty()&&pkg.equals(refuse)){result.code=1;result.err="fixture refusal";return result;}
if(!pkg.equals(noEffect)){
 if(command.startsWith("cmd appops set"))pm.ops.put(pkg,command.substring(command.lastIndexOf(' ')+1));
 else if(command.startsWith("am force-stop"))pm.apps.get(pkg).applicationInfo.flags|=ApplicationInfo.FLAG_STOPPED;
 else if(command.startsWith("pm disable-user"))pm.enabled.put(pkg,3);
 else if(command.startsWith("pm default-state"))pm.enabled.put(pkg,0);
 else if(command.startsWith("pm enable"))pm.enabled.put(pkg,1);
 else if(command.startsWith("pm disable-until-used"))pm.enabled.put(pkg,4);
 else if(command.startsWith("pm disable --"))pm.enabled.put(pkg,2);
 else pm.grants.put(pkg+"|"+permission,command.startsWith("pm grant"));
}
if(failResultLog)EventStore.writable=false;
if(cancelAfter>0&&mutations==cancelAfter)PermissionControl.requestStop();return result;
}
}'''
}
for name,source in sources.items():
 path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source)
