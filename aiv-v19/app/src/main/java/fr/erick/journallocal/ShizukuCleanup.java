package fr.erick.journallocal;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

/**
 * V24 automatic L4/L5 cleanup.
 * Uses Shizuku shell identity only for narrowly validated pm/appops commands.
 * No uninstall/disable action is performed here.
 */
public final class ShizukuCleanup {
    private ShizukuCleanup() {}

    private static final int REQUEST_CODE=624;
    private static final Pattern PACKAGE=Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern PERMISSION=Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern OP=Pattern.compile("[A-Z0-9_]+");
    private static volatile boolean running;
    private static volatile boolean pending;
    private static volatile String status="En attente de l’inventaire";
    private static volatile Activity activity;

    private static final Shizuku.OnBinderReceivedListener BINDER_LISTENER=()->{if(pending)attemptRun();};
    private static final Shizuku.OnRequestPermissionResultListener PERMISSION_LISTENER=(requestCode,grantResult)->{
        if(requestCode==REQUEST_CODE&&grantResult==PackageManager.PERMISSION_GRANTED&&pending)runAsync();
        else if(requestCode==REQUEST_CODE){pending=false;status="Autorisation Shizuku refusée";}
    };

    public static synchronized void attach(Activity a){
        activity=a;
        Shizuku.addBinderReceivedListenerSticky(BINDER_LISTENER);
        Shizuku.addRequestPermissionResultListener(PERMISSION_LISTENER);
    }

    public static synchronized void detach(){
        Shizuku.removeBinderReceivedListener(BINDER_LISTENER);
        Shizuku.removeRequestPermissionResultListener(PERMISSION_LISTENER);
        activity=null;
    }

    public static String status(){return status;}
    static boolean isRunning(){return running;}

    public static JSONObject state(Context c)throws Exception{
        JSONObject out=EventStore.object("status",status,"running",running,"pending",pending);
        try{
            boolean binder=Shizuku.pingBinder();
            out.put("binder",binder);
            out.put("authorized",binder&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED);
            out.put("server_uid",binder?safeServerUid():-1);
        }catch(Throwable t){
            out.put("binder",false).put("authorized",false).put("shizuku_error",t.getClass().getSimpleName());
        }
        try{out.put("candidates",DefenseStore.get(c).automaticCandidates().length());}
        catch(Throwable t){out.put("candidates",-1).put("candidate_error",t.getClass().getSimpleName());}
        return out;
    }

    public static void requestOrRun(){
        if(DeveloperControl.isBusy()){status="Intervention de contrôle en cours";return;}
        if(!AccessPolicy.allows("shizuku.control",AccessPolicy.DISTRIBUTION_TIER)){status="Contrôle Shizuku réservé au palier 2";pending=false;return;}
        pending=true;
        status="Préparation du nettoyage Shizuku";
        attemptRun();
    }

    private static void attemptRun(){
        Activity a=activity;
        if(a==null){pending=false;status="Activité Android indisponible";return;}
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){
            a.runOnUiThread(ShizukuCleanup::attemptRun);
            return;
        }
        try{
            if(!pending)return;
            if(!Shizuku.pingBinder()){status="Shizuku non démarré — démarre Shizuku puis relance le nettoyage";return;}
            if(Shizuku.isPreV11()){pending=false;status="Version Shizuku non prise en charge";return;}
            if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){runAsync();return;}
            if(Shizuku.shouldShowRequestPermissionRationale()){pending=false;status="Autorisation Shizuku requise";return;}
            status="Demande d’autorisation Shizuku";
            Shizuku.requestPermission(REQUEST_CODE);
        }catch(Throwable t){pending=false;status="Shizuku indisponible : "+t.getClass().getSimpleName();}
    }

    private static synchronized void runAsync(){
        Activity a=activity;
        if(a==null||running||DeveloperControl.isBusy())return;
        if(!ControlCoordinator.acquire()){status="Une intervention est déjà en cours";return;}
        pending=false;
        running=true;
        status="Nettoyage Shizuku en cours";
        new Thread(()->{
            try{run(a.getApplicationContext());}
            catch(Throwable t){status="Nettoyage interrompu : "+t.getClass().getSimpleName();log(a,"CLEANUP_ERROR",EventStore.object("error",String.valueOf(t.getMessage()),"type",t.getClass().getName()));}
            finally{running=false;ControlCoordinator.release();}
        },"aiv-v24-shizuku-cleanup").start();
    }

    private static void run(Context c)throws Exception{
        JSONArray candidates=DefenseStore.get(c).automaticCandidates();
        int candidateCount=candidates.length();
        status="Nettoyage Shizuku en cours : "+candidateCount+" application(s) admissible(s)";
        JSONObject snapshot=new JSONObject().put("schema","aiv-shizuku-cleanup/24").put("created_ms",System.currentTimeMillis()).put("server_uid",safeServerUid()).put("changes",new JSONArray());
        writeSnapshot(c,snapshot);
        int attempted=0,changed=0,failed=0,skipped=0;
        for(int i=0;i<candidates.length();i++){
            JSONObject row=candidates.getJSONObject(i),app=row.getJSONObject("app"),assessment=row.getJSONObject("assessment");
            String pkg=app.optString("package_name");
            if(!controlTargetAllowed(c,pkg)){skipped++;continue;}
            JSONArray findings=assessment.optJSONArray("findings");
            if(findings!=null)for(int j=0;j<findings.length();j++){
                JSONObject finding=findings.getJSONObject(j);
                if(!Boolean.TRUE.equals(finding.opt("granted"))){continue;}
                String permission=finding.optString("permission");
                if(!validPermission(permission)){skipped++;continue;}
                attempted++;
                JSONObject change=EventStore.object("package",pkg,"kind","permission","name",permission,"before","granted","command","pm revoke");
                change.put("inverse","pm grant --user current "+q(pkg)+" "+q(permission));
                snapshot.getJSONArray("changes").put(change);writeSnapshot(c,snapshot);
                try{
                    ExecResult r=exec("pm revoke --user current "+q(pkg)+" "+q(permission));
                    boolean denied=c.getPackageManager().checkPermission(permission,pkg)!=PackageManager.PERMISSION_GRANTED;
                    change.put("exit",r.code).put("stdout",r.out).put("stderr",r.err).put("after",denied?"denied":"still_granted");
                    if(denied){change.put("inverse","pm grant --user current "+q(pkg)+" "+q(permission));changed++;}else failed++;
                }catch(Throwable t){change.put("error",t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));failed++;}
                writeSnapshot(c,snapshot);log(c,"CLEANUP_PERMISSION",change);
            }
            JSONArray actions=assessment.optJSONArray("special_actions");
            if(actions!=null)for(int j=0;j<actions.length();j++){
                String action=actions.optString(j),op=appOp(action);
                if(op==null){skipped++;continue;}
                attempted++;
                JSONObject before=queryAppOp(pkg,op);
                JSONObject change=EventStore.object("package",pkg,"kind","appop","name",op,"before",before);
                String originalMode=before.optString("mode");
                if(originalMode.isEmpty()||!validMode(originalMode)){skipped++;continue;}
                change.put("inverse","cmd appops set --user current "+q(pkg)+" "+op+" "+originalMode);
                snapshot.getJSONArray("changes").put(change);writeSnapshot(c,snapshot);
                try{
                    String mode=before.optString("mode");
                    if("deny".equals(mode)||"ignore".equals(mode)){change.remove("inverse");change.put("after",mode).put("unchanged",true);writeSnapshot(c,snapshot);continue;}
                    ExecResult r=exec("cmd appops set --user current "+q(pkg)+" "+op+" deny");
                    JSONObject after=queryAppOp(pkg,op);
                    change.put("exit",r.code).put("stdout",r.out).put("stderr",r.err).put("after",after);
                    String afterMode=after.optString("mode");
                    if("deny".equals(afterMode)||"ignore".equals(afterMode)){
                        if(!mode.isEmpty()&&validMode(mode))change.put("inverse","cmd appops set --user current "+q(pkg)+" "+op+" "+mode);
                        changed++;
                    }else failed++;
                }catch(Throwable t){change.put("error",t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));failed++;}
                writeSnapshot(c,snapshot);log(c,"CLEANUP_APPOP",change);
            }
        }
        snapshot.put("finished_ms",System.currentTimeMillis()).put("attempted",attempted).put("changed",changed).put("failed",failed).put("skipped",skipped);
        writeSnapshot(c,snapshot);
        PermissionAudit.get(c).scan();
        status="Nettoyage terminé : "+changed+" droit(s) retiré(s) sur "+attempted+" tentative(s), "+failed+" échec(s), "+candidateCount+" app(s) admissible(s)";
        log(c,"CLEANUP_DONE",EventStore.object("candidates",candidateCount,"attempted",attempted,"changed",changed,"failed",failed,"skipped",skipped,"snapshot",snapshotFile(c).getAbsolutePath()));
    }

    public static synchronized JSONObject restore(Context c)throws Exception{
        if(!AccessPolicy.allows("shizuku.restore",AccessPolicy.DISTRIBUTION_TIER))throw new SecurityException("Restauration réservée au palier 2");
        if(running||DeveloperControl.isBusy())throw new IllegalStateException("Une action est déjà en cours");
        File f=snapshotFile(c);
        if(!f.isFile())return EventStore.object("restored",0,"failed",0,"error","Aucun snapshot de nettoyage");
        if(!ControlCoordinator.acquire())throw new IllegalStateException("Une intervention est déjà en cours");
        try{
        JSONObject snapshot=new JSONObject(readAll(f)),result=EventStore.object("schema","aiv-shizuku-restore/24","started_ms",System.currentTimeMillis());
        JSONArray changes=snapshot.optJSONArray("changes");int restored=0,failed=0;
        if(changes!=null)for(int i=changes.length()-1;i>=0;i--){
            JSONObject change=changes.optJSONObject(i);if(change==null)continue;
            String inverse=change.optString("inverse");if(inverse.isEmpty())continue;
            try{
                String pkg=change.getString("package"),name=change.getString("name");
                if(!controlTargetAllowed(c,pkg))throw new SecurityException("Cible de restauration réservée");
                String command;
                if("permission".equals(change.optString("kind"))&&validPermission(name))command="pm grant --user current "+q(pkg)+" "+q(name);
                else if("appop".equals(change.optString("kind"))&&OP.matcher(name).matches()){
                    String mode=change.getJSONObject("before").getString("mode");
                    if(!validMode(mode))throw new SecurityException("Mode de restauration invalide");
                    command="cmd appops set --user current "+q(pkg)+" "+name+" "+mode;
                }else throw new SecurityException("Restauration inconnue");
                ExecResult r=exec(command);
                boolean verified="permission".equals(change.optString("kind"))?c.getPackageManager().checkPermission(name,pkg)==PackageManager.PERMISSION_GRANTED:change.getJSONObject("before").getString("mode").equals(queryAppOp(pkg,name).optString("mode"));
                if(verified)restored++;else failed++;
                log(c,"CLEANUP_RESTORE",EventStore.object("package",pkg,"name",name,"exit",r.code,"verified",verified,"stderr",r.err));
            }
            catch(Throwable t){failed++;log(c,"CLEANUP_RESTORE_ERROR",EventStore.object("package",change.optString("package"),"name",change.optString("name"),"error",t.getClass().getSimpleName()));}
        }
        PermissionAudit.get(c).scan();
        status="Restauration terminée : "+restored+" restauré(s), "+failed+" échec(s)";
        return result.put("finished_ms",System.currentTimeMillis()).put("restored",restored).put("failed",failed);
        }finally{ControlCoordinator.release();}
    }

    public static synchronized JSONObject normalize(Context c,String pkg,String profile,String stamp)throws Exception {
        if(!AccessPolicy.allows("shizuku.control",AccessPolicy.DISTRIBUTION_TIER))throw new SecurityException("Contrôle réservé au palier 2");
        if(running||DeveloperControl.isBusy())throw new IllegalStateException("Une action est déjà en cours");
        if(!Shizuku.pingBinder()||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("Démarre et autorise Shizuku avant d’appliquer la norme");
        JSONObject preview=PermissionNorms.preview(c,pkg,profile);
        if(!preview.getString("stamp").equals(stamp))throw new IllegalStateException("Permissions modifiées : refaire l’aperçu");
        JSONArray changes=preview.getJSONArray("changes");
        if(changes.length()==0)return EventStore.object("status","Aucune permission à retirer");
        if(!ControlCoordinator.acquire())throw new IllegalStateException("Une intervention est déjà en cours");
        Context app=c.getApplicationContext();running=true;status="Application de la norme choisie";
        new Thread(()->{
            int changed=0,failed=0;JSONObject snapshot=EventStore.object("schema","aiv-shizuku-cleanup/24","created_ms",System.currentTimeMillis(),"profile",profile,"changes",new JSONArray());
            try {writeSnapshot(app,snapshot);
                for(int i=0;i<changes.length();i++){
                    String permission=changes.getJSONObject(i).getString("permission");JSONObject change=EventStore.object("package",pkg,"kind","permission","name",permission,"before","granted","inverse","pm grant --user current "+q(pkg)+" "+q(permission));
                    snapshot.getJSONArray("changes").put(change);writeSnapshot(app,snapshot);
                    try {ExecResult result=exec("pm revoke --user current "+q(pkg)+" "+q(permission));boolean revoked=app.getPackageManager().checkPermission(permission,pkg)!=PackageManager.PERMISSION_GRANTED;
                        change.put("exit",result.code).put("stdout",result.out).put("stderr",result.err).put("after",revoked?"denied":"still_granted");if(revoked){change.put("inverse","pm grant --user current "+q(pkg)+" "+q(permission));changed++;}else failed++;
                    }catch(Exception e){failed++;change.put("error",e.getClass().getSimpleName());}
                    writeSnapshot(app,snapshot);log(app,"NORMALIZATION_PERMISSION",change);
                }
                snapshot.put("changed",changed).put("failed",failed).put("finished_ms",System.currentTimeMillis());writeSnapshot(app,snapshot);PermissionAudit.get(app).scan();status="Norme appliquée : "+changed+" droit(s) retiré(s), "+failed+" échec(s)";
            }catch(Exception e){status="Norme interrompue : "+e.getClass().getSimpleName();log(app,"NORMALIZATION_ERROR",EventStore.object("error",e.getClass().getSimpleName()));}
            finally{running=false;ControlCoordinator.release();}
        },"aiv-permission-normalization").start();return EventStore.object("status",status);
    }

    private static JSONObject queryAppOp(String pkg,String op){
        try{
            ExecResult r=exec("cmd appops get --user current "+q(pkg)+" "+op);
            String text=(r.out+"\n"+r.err).trim(),mode="";
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?m)^\\s*"+java.util.regex.Pattern.quote(op)+"(?::|\\s).*?\\b(allow|deny|ignore|default|foreground)\\b").matcher(text);
            if(m.find())mode=m.group(1);
            return EventStore.object("mode",mode,"exit",r.code,"raw",text);
        }catch(Throwable t){return EventStore.object("mode","","error",t.getClass().getSimpleName());}
    }

    private static String appOp(String action){
        if("unknown_sources".equals(action))return "REQUEST_INSTALL_PACKAGES";
        if("overlay".equals(action))return "SYSTEM_ALERT_WINDOW";
        if("write_settings".equals(action))return "WRITE_SETTINGS";
        if("usage".equals(action))return "GET_USAGE_STATS";
        if("manage_external_storage".equals(action))return "MANAGE_EXTERNAL_STORAGE";
        return null;
    }

    private static boolean validPackage(String s){return s!=null&&PACKAGE.matcher(s).matches();}
    static boolean controlTargetAllowed(Context c,String pkg){
        if(!validPackage(pkg)||pkg.equals(c.getPackageName())||"com.android.shell".equals(pkg))return false;
        try{
            int uid=c.getPackageManager().getApplicationInfo(pkg,0).uid;
            String[] peers=c.getPackageManager().getPackagesForUid(uid);
            return uid%100000>=10000&&uid/100000==android.os.Process.myUid()/100000&&uid!=android.os.Process.myUid()&&peers!=null&&peers.length==1;
        }catch(Exception e){return false;}
    }
    private static boolean validPermission(String s){return s!=null&&PERMISSION.matcher(s).matches();}
    private static boolean validMode(String s){return Arrays.asList("allow","deny","ignore","default","foreground").contains(s);}
    private static String q(String s){if(!validPackage(s)&&!validPermission(s))throw new IllegalArgumentException("Identité shell refusée");return "'"+s.replace("'","")+"'" ;}

    private static final class ExecResult{final int code;final String out,err;ExecResult(int c,String o,String e){code=c;out=o;err=e;}}

    private static ExecResult exec(String command)throws Exception{
        if(!AccessPolicy.allows("shizuku.control",AccessPolicy.DISTRIBUTION_TIER))throw new SecurityException("Contrôle réservé au palier 2");
        if(!Shizuku.pingBinder()||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("Shizuku non autorisé");
        ControlShell.Result r=ControlShell.run(command);
        return new ExecResult(r.code,r.out,r.err);
    }

    private static int safeServerUid(){try{return Shizuku.getUid();}catch(Throwable t){return -1;}}
    private static File snapshotFile(Context c){return new File(c.getFilesDir(),AivConfig.PATHS_CLEANUP_SNAPSHOT);}
    private static void writeSnapshot(Context c,JSONObject value)throws IOException{
        File target=snapshotFile(c),tmp=new File(target.getParentFile(),target.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!tmp.renameTo(target)){try(InputStream in=new FileInputStream(tmp);OutputStream out=new FileOutputStream(target)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}tmp.delete();}
    }
    private static String readAll(File f)throws IOException{try(InputStream in=new FileInputStream(f)){return readAll(in);}}
    private static String readAll(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}
    private static String trim(String s){s=s==null?"":s.trim();return s.length()>4096?s.substring(0,4096):s;}
    private static void log(Context c,String kind,JSONObject data){try{EventStore.get(c).add("aiv-v24","Shizuku",kind,"Nettoyage automatique L4/L5","Interne","Action locale vérifiée",data);}catch(Throwable ignored){}}
}
