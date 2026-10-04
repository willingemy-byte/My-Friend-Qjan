#!/usr/bin/env python3
"""Small host fixtures for the real permission controller, isolated from APK sources."""
from pathlib import Path
import sys
root=Path(sys.argv[1])
sources={
"android/os/Process.java": '''package android.os; public class Process {public static int myUid(){return 10123;}}''',
"android/os/Build.java": '''package android.os; public class Build {public static class VERSION {public static final int SDK_INT=36;}}''',
"android/content/pm/PackageInfo.java": '''package android.content.pm; public class PackageInfo {public String packageName;public ApplicationInfo applicationInfo;public String[] requestedPermissions;public int versionCode=1;public long firstInstallTime=1,lastUpdateTime=1;public long getLongVersionCode(){return versionCode;}}''',
"android/content/pm/ApplicationInfo.java": '''package android.content.pm; public class ApplicationInfo {public static final int FLAG_SYSTEM=1;public int uid,flags,targetSdkVersion=36;public String label;public CharSequence loadLabel(PackageManager pm){return label;}}''',
"android/content/pm/PermissionInfo.java": '''package android.content.pm; public class PermissionInfo {public String name;public int protectionLevel;public CharSequence loadLabel(PackageManager pm){return name;}public CharSequence loadDescription(PackageManager pm){return "Fixture description";}}''',
"android/content/pm/PackageManager.java": '''package android.content.pm; import java.util.*; public class PackageManager {
public static final int PERMISSION_GRANTED=0,GET_SIGNING_CERTIFICATES=1,GET_SIGNATURES=2,GET_PERMISSIONS=4,MATCH_DISABLED_COMPONENTS=8;
public static class NameNotFoundException extends Exception {}
public final Map<String,PackageInfo> apps=new LinkedHashMap<>();public final Map<String,Boolean> grants=new HashMap<>();public final Map<String,String> flags=new HashMap<>(),guards=new HashMap<>(),ops=new HashMap<>();
public List<PackageInfo> getInstalledPackages(int f){return new ArrayList<>(apps.values());}
public PackageInfo getPackageInfo(String p,int f)throws NameNotFoundException{if(!apps.containsKey(p))throw new NameNotFoundException();return apps.get(p);}
public int checkPermission(String n,String p){return grants.getOrDefault(p+"|"+n,false)?0:-1;}
public PermissionInfo getPermissionInfo(String n,int f){PermissionInfo p=new PermissionInfo();p.name=n;p.protectionLevel=n.endsWith("SYSTEM_ALERT_WINDOW")?2:1;return p;}
public String[] getPackagesForUid(int uid){return apps.values().stream().filter(p->p.applicationInfo.uid==uid).map(p->p.packageName).toArray(String[]::new);}
}''',
"android/content/res/AssetManager.java": '''package android.content.res; import java.io.*;public class AssetManager {private final File root;public AssetManager(File root){this.root=root;}public InputStream open(String name)throws IOException{return new FileInputStream(new File(root,name));}}''',
"android/content/SharedPreferences.java": '''package android.content;import java.util.*;public class SharedPreferences {
private final Map<String,Object> values=new HashMap<>();public String getString(String k,String d){return (String)values.getOrDefault(k,d);}public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}public Map<String,?> getAll(){return new HashMap<>(values);}public Editor edit(){return new Editor();}
public class Editor {private final Map<String,Object> changes=new HashMap<>();public Editor putString(String k,String v){changes.put(k,v);return this;}public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}public boolean commit(){values.putAll(changes);return true;}}
}''',
"android/content/Context.java": '''package android.content;import java.io.*;import android.content.pm.*;import android.content.res.*;public class Context {
public static final int MODE_PRIVATE=0;private final File files;private final AssetManager assets;private final SharedPreferences preferences=new SharedPreferences();public final PackageManager pm=new PackageManager();
public Context(File f,File a){files=f;assets=new AssetManager(a);}public File getFilesDir(){return files;}public AssetManager getAssets(){return assets;}public PackageManager getPackageManager(){return pm;}public SharedPreferences getSharedPreferences(String n,int m){return preferences;}public Context getApplicationContext(){return this;}public String getPackageName(){return "com.aiv.test";}
}''',
"android/util/AtomicFile.java": '''package android.util;import java.io.*;public class AtomicFile {private final File target,temporary;public AtomicFile(File f){target=f;temporary=new File(f+".tmp");}public FileOutputStream startWrite()throws IOException{return new FileOutputStream(temporary);}public void finishWrite(FileOutputStream out)throws IOException{out.close();java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}public void failWrite(FileOutputStream out)throws IOException{out.close();temporary.delete();}public InputStream openRead()throws IOException{return new FileInputStream(target);}}''',
"rikka/shizuku/Shizuku.java": '''package rikka.shizuku;public class Shizuku {public static boolean pingBinder(){return true;}public static boolean isPreV11(){return false;}public static int checkSelfPermission(){return 0;}public static int getUid(){return 2000;}}''',
"fr/erick/journallocal/FixtureDependencies.java": '''package fr.erick.journallocal;import android.content.*;import android.content.pm.*;import org.json.*;import java.util.*;
class AivConfig {static final int CONTROL_REPORT_MAX_BYTES=8388608,CONTROL_BATCH_MAX_APPS=50,REFERENCE_MAX_APPS=20000;static final long CONTROL_PREVIEW_VALID_MS=300000;}
class AccessPolicy {static final int DISTRIBUTION_TIER=3;static boolean allows(String s,int t){return true;}}
class AppIdentity {static JSONObject forPackage(Context c,PackageInfo p){return EventStore.object("app_identity_id",p.packageName,"current_signer_sha256",new JSONArray().put("fixture-signer"));}}
class DeveloperControl {static String permissionTargetReason(Context c,String p){return c.pm.guards.getOrDefault(p,"");}}
class ApkEvidence {static ApkEvidence get(Context c){return new ApkEvidence();}static String hex(byte[] b){return java.util.HexFormat.of().formatHex(b);}JSONObject read(String p,long v,long u){return EventStore.object("status","ANALYZED","trackers",new JSONArray());}}
class ReferenceCatalog {static ReferenceCatalog get(Context c){return new ReferenceCatalog();}JSONObject permission(String n){return EventStore.object("label",n);}JSONObject summary(){return new JSONObject();}}
class EventStore {static EventStore get(Context c){return new EventStore();}void add(Object... args){}static JSONObject object(Object... args){JSONObject o=new JSONObject();for(int i=0;i<args.length;i+=2)o.put((String)args[i],args[i+1]);return o;}}
class PermissionAudit {static PermissionAudit get(Context c){return new PermissionAudit();}void scan(){}}
''',
"fr/erick/journallocal/ControlShell.java": '''package fr.erick.journallocal;
import android.content.*;import android.content.pm.*;import java.nio.file.*;import org.json.*;
class ControlShell {
static Context context;static int mutations,cancelAfter;static final class Result {int code;String out,err="";boolean complete=true;Result(String out){this.out=out;}}
static Result run(String command)throws Exception{return run(command,16384);}
static Result run(String command,int limit)throws Exception{
PackageManager pm=context.pm;String[] quoted=command.split("'");String pkg=quoted.length>1?quoted[1]:"";
if(command.startsWith("dumpsys")){
 if(pkg.endsWith("unknown"))return new Result("unrecognized output");
 StringBuilder text=new StringBuilder("Package ["+pkg+"] (fixture):\\n User 0: installed=true\\n runtime permissions:\\n");
 for(String permission:pm.apps.get(pkg).requestedPermissions)if(!permission.endsWith("SYSTEM_ALERT_WINDOW"))text.append("  ").append(permission).append(": granted=").append(pm.grants.getOrDefault(pkg+"|"+permission,false)).append(", flags=[").append(pm.flags.getOrDefault(pkg+"|"+permission,"")).append("]\\n");
 return new Result(text.toString());
}
if(command.startsWith("cmd appops get")){String mode=pm.ops.getOrDefault(pkg,"default");return new Result(mode.equals("uid")?"Uid mode: SYSTEM_ALERT_WINDOW: allow":"SYSTEM_ALERT_WINDOW: "+mode);}
String permission=command.startsWith("pm ")?quoted[3]:"android.permission.SYSTEM_ALERT_WINDOW";
if(!command.startsWith("pm revoke")&&!command.startsWith("pm grant")&&!command.startsWith("cmd appops set"))throw new AssertionError("Unexpected mutation "+command);
boolean durable=false;
for(java.io.File f:new java.io.File(context.getFilesDir(),"permission-control").listFiles())if(f.getName().matches("[a-f0-9-]{36}\\\\.json")){
 JSONObject report=new JSONObject(Files.readString(f.toPath()));JSONArray rows=report.optJSONArray("entries");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(command.equals(row.optString("command"))&&"pending".equals(row.optString("outcome")))durable=true;}
}
if(!durable)throw new AssertionError("No durable receipt before "+command);
if(ControlCoordinator.acquire()){ControlCoordinator.release();throw new AssertionError("Executor lease missing");}
if(command.startsWith("cmd appops set"))pm.ops.put(pkg,command.substring(command.lastIndexOf(' ')+1));
else pm.grants.put(pkg+"|"+permission,command.startsWith("pm grant"));
mutations++;if(cancelAfter>0&&mutations==cancelAfter)PermissionControl.requestStop();return new Result("");
}
}'''
}
for name,source in sources.items():
 path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source)
