package fr.erick.journallocal;

import android.content.*;
import android.content.pm.*;
import android.app.admin.DevicePolicyManager;
import android.provider.Settings;
import android.util.AtomicFile;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

/** Explicitly selected packages only. No deletion, data clearing or guessed file access. */
public final class DeveloperControl {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static boolean busy;
    private static volatile String status="Aucune intervention de contrôle";
    private static final int USER=android.os.Process.myUid()/100000;
    private DeveloperControl(){}
    static synchronized boolean isBusy(){return busy;}
    private static synchronized boolean acquire(){if(busy||!ControlCoordinator.acquire())return false;busy=true;return true;}
    private static synchronized void release(){busy=false;ControlCoordinator.release();}
    private static void access()throws Exception{
        if(!AccessPolicy.allows("shizuku.control",AccessPolicy.DISTRIBUTION_TIER))throw new SecurityException("Contrôle indisponible dans cette édition");
        if(!Shizuku.pingBinder()||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("Démarre Shizuku et autorise AIV");
    }
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(AivConfig.PATHS_CONTROL_PREFERENCES,Context.MODE_PRIVATE);}
    private static File dir(Context c)throws IOException{File d=new File(c.getFilesDir(),AivConfig.PATHS_CONTROL_REPORTS);if(!d.isDirectory()&&!d.mkdirs())throw new IOException("Rapport non enregistrable");return d;}
    private static File file(Context c,String name)throws IOException{return new File(dir(c),name);}
    private static void write(File f,JSONObject data)throws Exception{
        byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>AivConfig.CONTROL_REPORT_MAX_BYTES)throw new IOException("Rapport plein : exporter puis ouvrir une nouvelle intervention");
        AtomicFile atomic=new AtomicFile(f);FileOutputStream out=atomic.startWrite();
        try{out.write(bytes);atomic.finishWrite(out);}catch(Exception e){atomic.failWrite(out);throw e;}
    }
    private static JSONObject read(File f)throws Exception{
        try(InputStream in=new AtomicFile(f).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>AivConfig.CONTROL_REPORT_MAX_BYTES)throw new IOException("Rapport trop volumineux");out.write(bytes,0,n);}return new JSONObject(out.toString("UTF-8"));
        }
    }
    private static String digest(String value)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static JSONObject snapshot(Context c,String pkg)throws Exception{
        if(!ControlRules.validPackage(pkg))throw new IllegalArgumentException("Package invalide");
        PackageManager pm=c.getPackageManager();
        PackageInfo p=pm.getPackageInfo(pkg,PackageManager.GET_PERMISSIONS|(Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES)|PackageManager.MATCH_DISABLED_COMPONENTS);
        ApplicationInfo a=p.applicationInfo;if(a==null||a.uid/100000!=USER)throw new IllegalStateException("Paquet absent de ce profil");
        JSONObject permissions=new JSONObject();if(p.requestedPermissions!=null)for(String permission:p.requestedPermissions)permissions.put(permission,pm.checkPermission(permission,pkg)==PackageManager.PERMISSION_GRANTED);
        JSONArray peers=new JSONArray();String[] names=pm.getPackagesForUid(a.uid);if(names!=null)for(String name:names)peers.put(name);
        // PermissionAudit includes signing history and certificate digests without private keys.
        JSONObject identity=AppIdentity.forPackage(c,p);
        if(identity.optJSONArray("current_signer_sha256")==null||identity.getJSONArray("current_signer_sha256").length()==0)throw new IllegalStateException("Signataire non vérifiable : aucune action");
        return new JSONObject().put("package",pkg).put("label",String.valueOf(a.loadLabel(pm))).put("uid",a.uid).put("user",USER)
            .put("version",Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode).put("updated_ms",p.lastUpdateTime).put("identity",identity)
            .put("enabled",pm.getApplicationEnabledSetting(pkg)).put("stopped",(a.flags&ApplicationInfo.FLAG_STOPPED)!=0)
            .put("system",(a.flags&ApplicationInfo.FLAG_SYSTEM)!=0).put("shared_packages",peers).put("permissions",permissions);
    }
    private static Set<String> essential(Context c){
        Set<String> out=new HashSet<>(Arrays.asList(c.getPackageName(),"android","com.android.shell","moe.shizuku.privileged.api","com.android.systemui","com.android.settings","com.android.phone","com.android.providers.settings","com.android.providers.telephony","com.android.providers.media","com.android.providers.media.module","com.android.providers.downloads","com.android.permissioncontroller","com.google.android.permissioncontroller","com.android.packageinstaller","com.google.android.packageinstaller","com.android.networkstack","com.google.android.networkstack","com.android.networkstack.tethering","com.google.android.networkstack.tethering","com.android.bluetooth","com.android.se","com.android.server.telecom"));
        try{Intent home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);ResolveInfo r=c.getPackageManager().resolveActivity(home,PackageManager.MATCH_DEFAULT_ONLY);if(r!=null&&r.activityInfo!=null)out.add(r.activityInfo.packageName);}catch(Exception ignored){}
        try{String keyboard=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);if(keyboard!=null)out.add(keyboard.split("/")[0]);}catch(Exception ignored){}
        try{String dialer=c.getSystemService(android.telecom.TelecomManager.class).getDefaultDialerPackage();if(dialer!=null)out.add(dialer);}catch(Exception ignored){}
        try{ResolveInfo r=c.getPackageManager().resolveActivity(new Intent("android.intent.action.MANAGE_PERMISSIONS"),PackageManager.MATCH_DEFAULT_ONLY);if(r!=null&&r.activityInfo!=null)out.add(r.activityInfo.packageName);}catch(Exception ignored){}
        try{List<ComponentName> admins=c.getSystemService(DevicePolicyManager.class).getActiveAdmins();if(admins!=null)for(ComponentName a:admins)out.add(a.getPackageName());}catch(Exception ignored){}
        return out;
    }
    private static String reserved(JSONObject s,Set<String> core){
        if(core.contains(s.optString("package")))return "Composant nécessaire au contrôle, à l’accès au téléphone ou administrateur actif";
        if(s.optInt("uid")%100000<10000)return "Identité système fondamentale";
        JSONArray peers=s.optJSONArray("shared_packages");if(peers!=null)for(int i=0;i<peers.length();i++)if(core.contains(peers.optString(i)))return "Identité partagée avec un composant essentiel";
        return "";
    }
    /** Permission review includes preinstalled apps; essential roles and shared UIDs remain visible. */
    static String permissionTargetReason(Context c,String pkg)throws Exception{
        JSONObject target=snapshot(c,pkg);
        Set<String> core=essential(c);
        String sms=android.provider.Telephony.Sms.getDefaultSmsPackage(c);if(sms!=null)core.add(sms);
        String services=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(services!=null)for(String service:services.split(":")){ComponentName name=ComponentName.unflattenFromString(service);if(name!=null)core.add(name.getPackageName());}
        String reason=reserved(target,core);if(!reason.isEmpty())return reason;
        if(target.getJSONArray("shared_packages").length()!=1)return "UID partagé : retrait individuel non isolable";
        return "";
    }
    public static JSONObject targets(Context c)throws Exception{
        JSONArray out=new JSONArray();Set<String> core=essential(c);PackageManager pm=c.getPackageManager();
        for(ApplicationInfo a:pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)){
            JSONObject row=new JSONObject().put("package",a.packageName).put("label",String.valueOf(a.loadLabel(pm))).put("uid",a.uid).put("system",(a.flags&ApplicationInfo.FLAG_SYSTEM)!=0).put("enabled",pm.getApplicationEnabledSetting(a.packageName)).put("stopped",(a.flags&ApplicationInfo.FLAG_STOPPED)!=0);
            JSONArray peers=new JSONArray();String[] names=pm.getPackagesForUid(a.uid);if(names!=null)for(String name:names)peers.put(name);row.put("shared_packages",peers);
            row.put("reserved_reason",reserved(row,core));out.put(row);
        }
        return new JSONObject().put("user",USER).put("applications",out).put("status",state(c));
    }
    public static JSONObject state(Context c)throws Exception{
        SharedPreferences p=prefs(c);JSONObject watch=new JSONObject(p.getString("watch","{}"));
        return new JSONObject().put("busy",isBusy()).put("status",status).put("watch_enabled",p.getBoolean("watch_enabled",false)).put("watch_count",watch.length()).put("watch",watch).put("last_report",p.getString("last_report","")).put("user",USER)
            .put("shizuku",Shizuku.pingBinder()).put("collector",RecorderService.running);
    }
    public static synchronized JSONObject preview(Context c,String packages,String action)throws Exception{
        access();if(isBusy()||ShizukuCleanup.isRunning())throw new IllegalStateException("Une intervention est en cours");
        if(!ControlRules.validAction(action)||packages==null||packages.length()>AivConfig.CONTROL_REQUEST_MAX_CHARS)throw new IllegalArgumentException("Action ou liste invalide");
        JSONArray selected=new JSONArray(packages);if(selected.length()<1||selected.length()>AivConfig.CONTROL_BATCH_MAX_APPS)throw new IllegalArgumentException("Choisis entre 1 et 50 applications par lot");
        JSONArray before=new JSONArray(),excluded=new JSONArray();Set<String> core=essential(c),seen=new HashSet<>();
        for(int i=0;i<selected.length();i++){
            String pkg=selected.getString(i);if(!seen.add(pkg))continue;
            JSONObject s=snapshot(c,pkg);String reason=reserved(s,core);if(!reason.isEmpty())excluded.put(new JSONObject().put("package",pkg).put("reason",reason));else before.put(s);
        }
        JSONObject draft=new JSONObject().put("schema","aiv-developer-control/1").put("id",UUID.randomUUID().toString()).put("created_ms",System.currentTimeMillis()).put("user",USER).put("action",action).put("before",before).put("excluded",excluded).put("inventory_before",targets(c).getJSONArray("applications")).put("android_api",Build.VERSION.SDK_INT).put("model",Build.MODEL).put("server_uid",Shizuku.getUid());
        String stamp=digest(draft.toString());draft.put("stamp",stamp);write(file(c,AivConfig.PATHS_CONTROL_DRAFT),draft);
        return draft;
    }
    public static JSONObject apply(Context context,String stamp,boolean watch)throws Exception{
        access();if(!acquire())throw new IllegalStateException("Une intervention est en cours");Context c=context.getApplicationContext();
        try{
            JSONObject draft=read(file(c,AivConfig.PATHS_CONTROL_DRAFT));
            long age=System.currentTimeMillis()-draft.getLong("created_ms");
            if(draft.optBoolean("used")||!draft.getString("stamp").equals(stamp)||age<0||age>AivConfig.CONTROL_PREVIEW_VALID_MS)throw new IllegalStateException("Aperçu expiré : refaire l’état avant intervention");
            if(draft.getJSONArray("before").length()==0)throw new IllegalArgumentException("Aucune cible admissible");
            Set<String> core=essential(c);
            for(int i=0;i<draft.getJSONArray("before").length();i++){
                JSONObject old=draft.getJSONArray("before").getJSONObject(i),now=snapshot(c,old.getString("package"));
                if(!digest(old.toString()).equals(digest(now.toString()))||!reserved(now,core).isEmpty())throw new IllegalStateException("État modifié : refaire l’aperçu");
            }
            draft.put("watch_requested",watch).put("entries",new JSONArray()).put("phase","queued");saveReport(c,draft);
            write(file(c,AivConfig.PATHS_CONTROL_DRAFT),new JSONObject(draft.toString()).put("used",true));
            WORKER.execute(()->{try{run(c,draft);}catch(Exception e){status="Intervention interrompue : "+e.getMessage();try{draft.put("phase","interrupted").put("error",e.toString());saveReport(c,draft);}catch(Exception ignored){}}finally{release();}});
            return new JSONObject().put("status","Intervention lancée").put("report_id",draft.getString("id"));
        }catch(Exception e){release();throw e;}
    }
    private static void saveReport(Context c,JSONObject report)throws Exception{
        String id=report.getString("id");if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Identifiant de rapport invalide");
        write(file(c,id+".json"),report);
        if(!prefs(c).edit().putString("last_report",id).commit())throw new IOException("État du rapport non enregistré");
    }
    private static JSONObject execute(Context c,JSONObject report,JSONObject before,String action,String command,int restoreEnabled)throws Exception{
        JSONObject entry=new JSONObject().put("id",UUID.randomUUID().toString()).put("package",before.getString("package")).put("label",before.getString("label")).put("user",USER).put("action",action).put("requested_ms",System.currentTimeMillis()).put("before",before).put("command",command).put("outcome","requested");
        report.getJSONArray("entries").put(entry);saveReport(c,report); // durable BEFORE sending the command
        int exit=-1;try{ControlShell.Result r=ControlShell.run(command);exit=r.code;entry.put("exit",r.code).put("stdout",r.out).put("stderr",r.err);}catch(Exception e){entry.put("error",e.toString());}
        try{
            JSONObject after=snapshot(c,before.getString("package"));entry.put("after",after);
            boolean identity=digest(before.getJSONObject("identity").toString()).equals(digest(after.getJSONObject("identity").toString()));
            boolean verified=identity&&("restore".equals(action)?after.getInt("enabled")==restoreEnabled:ControlRules.reached(action,after.getInt("enabled"),after.getBoolean("stopped")));
            entry.put("outcome",identity?ControlRules.outcome(exit,verified):"identity_changed");
        }catch(Exception e){entry.put("verification_error",e.toString()).put("outcome","unverifiable");}
        entry.put("verified_ms",System.currentTimeMillis());saveReport(c,report);
        try{DefenseStore.get(c).action(before.getString("package"),"DEVELOPER_CONTROL",entry);}catch(Exception ignored){}
        return entry;
    }
    private static void run(Context c,JSONObject report)throws Exception{
        report.put("phase","running");saveReport(c,report);JSONArray targets=report.getJSONArray("before");String action=report.getString("action");
        for(int i=0;i<targets.length();i++){
            JSONObject before=targets.getJSONObject(i);String pkg=before.getString("package");status="Contrôle "+(i+1)+"/"+targets.length()+" : "+before.getString("label");
            // Keep package state matching the preview; never act on an updated replacement.
            JSONObject now=snapshot(c,pkg);
            if(!digest(before.toString()).equals(digest(now.toString()))||!reserved(now,essential(c)).isEmpty()){
                report.getJSONArray("entries").put(new JSONObject().put("package",pkg).put("outcome","state_changed").put("before",before).put("after",now));saveReport(c,report);continue;
            }
            String command="disable".equals(action)?"pm disable-user --user "+USER+" "+pkg:"am force-stop --user "+USER+" "+pkg;
            JSONObject entry=execute(c,report,before,action,command,-1);
            if("disable".equals(action)&&"confirmed".equals(entry.optString("outcome"))){
                // A separate verified request records whether existing processes were also stopped.
                execute(c,report,entry.getJSONObject("after"),"stop","am force-stop --user "+USER+" "+pkg,-1);
            }
            if(report.optBoolean("watch_requested")&&"confirmed".equals(entry.optString("outcome")))addWatch(c,before,action,report.getString("id"));
        }
        report.put("phase","finished").put("finished_ms",System.currentTimeMillis());saveReport(c,report);status="Intervention terminée — consulte le rapport vérifié";PermissionAudit.get(c).scan();
    }
    private static void addWatch(Context c,JSONObject before,String action,String reportId)throws Exception{
        SharedPreferences p=prefs(c);JSONObject watch=new JSONObject(p.getString("watch","{}"));String pkg=before.getString("package");
        if(!watch.has(pkg)&&watch.length()>=AivConfig.CONTROL_BATCH_MAX_APPS)throw new IllegalStateException("Surveillance limitée à 50 cibles");
        watch.put(pkg,new JSONObject().put("action",action).put("identity",before.getJSONObject("identity")).put("report_id",reportId).put("failures",0));
        if(!p.edit().putString("watch",watch.toString()).putBoolean("watch_enabled",true).commit())throw new IOException("Surveillance non enregistrée");
    }
    public static JSONObject monitoring(Context c,boolean enabled)throws Exception{
        if(enabled)access();if(!prefs(c).edit().putBoolean("watch_enabled",enabled).commit())throw new IOException("Réglage non enregistré");
        status=enabled?"Surveillance demandée — active pendant la collecte avec Shizuku disponible":"Surveillance en pause";return state(c);
    }
    /** Called by the existing recorder heartbeat. No foreground-service or wake-lock duplication. */
    public static void tick(Context context){
        Context c=context.getApplicationContext();if(!prefs(c).getBoolean("watch_enabled",false)||!acquire())return;
        WORKER.execute(()->{try{watch(c);}catch(Exception e){status="Surveillance suspendue : "+e.getMessage();}finally{release();}});
    }
    private static void watch(Context c)throws Exception{
        access();JSONObject watched=new JSONObject(prefs(c).getString("watch","{}"));Set<String> core=essential(c);
        for(Iterator<String> keys=watched.keys();keys.hasNext();){
            String pkg=keys.next();JSONObject target=watched.getJSONObject(pkg);if(target.optInt("failures")>=AivConfig.CONTROL_WATCH_MAX_FAILURES)continue;
            try{
                JSONObject now=snapshot(c,pkg);
                if(!digest(now.getJSONObject("identity").toString()).equals(digest(target.getJSONObject("identity").toString()))||!reserved(now,core).isEmpty()){target.put("failures",AivConfig.CONTROL_WATCH_MAX_FAILURES).put("suspended_reason","Identité ou rôle modifié");continue;}
                String action=target.getString("action");if(ControlRules.reached(action,now.getInt("enabled"),now.getBoolean("stopped")))continue;
                JSONObject report=read(file(c,target.getString("report_id")+".json"));
                JSONObject entry=execute(c,report,now,action,"disable".equals(action)?"pm disable-user --user "+USER+" "+pkg:"am force-stop --user "+USER+" "+pkg,-1);
                entry.put("trigger","watch_detected_state_change");saveReport(c,report);
                if(!"confirmed".equals(entry.optString("outcome")))target.put("failures",target.optInt("failures")+1);else target.put("failures",0);
            }catch(Exception e){target.put("failures",target.optInt("failures")+1).put("error",e.toString());}
        }
        if(!prefs(c).edit().putString("watch",watched.toString()).commit())throw new IOException("État de surveillance non enregistré");
    }
    public static JSONObject report(Context c)throws Exception{
        String id=prefs(c).getString("last_report","");return id.isEmpty()?new JSONObject().put("entries",new JSONArray()).put("phase","none"):read(file(c,id+".json"));
    }
    public static void export(Context c,Writer out)throws Exception{
        out.write("{\"schema\":\"aiv-control-export/1\",\"state\":"+state(c)+",\"reports\":[");File[] files=dir(c).listFiles();boolean first=true;
        if(files!=null){Arrays.sort(files,Comparator.comparing(File::getName));for(File f:files)if(f.getName().matches("[a-f0-9-]{36}\\.json")){if(!first)out.write(',');out.write(read(f).toString());first=false;}}
        File cleanup=new File(c.getFilesDir(),AivConfig.PATHS_CLEANUP_SNAPSHOT);out.write("],\"permission_cleanup\":");out.write(cleanup.isFile()?read(cleanup).toString():"null");out.write('}');
    }
    public static JSONObject restore(Context context)throws Exception{
        access();if(!acquire())throw new IllegalStateException("Une intervention est en cours");Context c=context.getApplicationContext();
        try{
            JSONObject original=report(c);if(!original.has("before"))throw new IllegalStateException("Aucune intervention à restaurer");
            WORKER.execute(()->{try{
                JSONObject restoration=new JSONObject().put("id",UUID.randomUUID().toString()).put("schema","aiv-control-restore/1").put("source_report",original.getString("id")).put("before",original.getJSONArray("before")).put("entries",new JSONArray()).put("phase","restoring");
                saveReport(c,restoration);JSONObject watched=new JSONObject(prefs(c).getString("watch","{}"));JSONArray targets=original.getJSONArray("before");
                // Stop enforcement before restoring so the watcher cannot undo the user's choice.
                for(int i=0;i<targets.length();i++)watched.remove(targets.getJSONObject(i).getString("package"));
                if(!prefs(c).edit().putString("watch",watched.toString()).commit())throw new IOException("Surveillance non arrêtée");
                for(int i=targets.length()-1;i>=0;i--){
                    JSONObject before=targets.getJSONObject(i);String pkg=before.getString("package");
                    try{
                        JSONObject now=snapshot(c,pkg);
                        if(!digest(before.getJSONObject("identity").toString()).equals(digest(now.getJSONObject("identity").toString()))||!reserved(now,essential(c)).isEmpty())throw new IllegalStateException("Identité ou rôle changé");
                        // Only undo a verified disable, never an unrelated later modification.
                        boolean disabledByUs=false;JSONArray entries=original.getJSONArray("entries");for(int j=0;j<entries.length();j++){JSONObject e=entries.getJSONObject(j);if(pkg.equals(e.optString("package"))&&"disable".equals(e.optString("action"))&&"confirmed".equals(e.optString("outcome")))disabledByUs=true;}
                        if(disabledByUs&&now.getInt("enabled")==3&&before.getInt("enabled")!=3)execute(c,restoration,now,"restore","pm "+ControlRules.enabledCommand(before.getInt("enabled"))+" --user "+USER+" "+pkg,before.getInt("enabled"));
                        else{restoration.getJSONArray("entries").put(new JSONObject().put("package",pkg).put("outcome","no_change").put("note","Surveillance retirée. Un arrêt forcé ne relance pas automatiquement l’application."));saveReport(c,restoration);}
                    }catch(Exception e){restoration.getJSONArray("entries").put(new JSONObject().put("package",pkg).put("outcome","restore_refused").put("error",e.toString()));saveReport(c,restoration);}
                }
                restoration.put("phase","finished").put("finished_ms",System.currentTimeMillis());saveReport(c,restoration);status="Restauration terminée — résultats vérifiés";PermissionAudit.get(c).scan();
            }catch(Exception e){status="Restauration interrompue : "+e.getMessage();}finally{release();}});
            return new JSONObject().put("status","Restauration lancée");
        }catch(Exception e){release();throw e;}
    }
}
