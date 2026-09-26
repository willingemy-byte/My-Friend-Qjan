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
    private static volatile String status="En attente de Shizuku";
    private static volatile Activity activity;

    private static final Shizuku.OnBinderReceivedListener BINDER_LISTENER=()->requestOrRun();
    private static final Shizuku.OnRequestPermissionResultListener PERMISSION_LISTENER=(requestCode,grantResult)->{
        if(requestCode==REQUEST_CODE&&grantResult==PackageManager.PERMISSION_GRANTED)runAsync();
        else if(requestCode==REQUEST_CODE)status="Autorisation Shizuku refusée";
    };

    public static synchronized void attach(Activity a){
        activity=a;
        Shizuku.addBinderReceivedListenerSticky(BINDER_LISTENER);
        Shizuku.addRequestPermissionResultListener(PERMISSION_LISTENER);
        requestOrRun();
    }

    public static synchronized void detach(){
        Shizuku.removeBinderReceivedListener(BINDER_LISTENER);
        Shizuku.removeRequestPermissionResultListener(PERMISSION_LISTENER);
        activity=null;
    }

    public static String status(){return status;}

    public static JSONObject state(Context c)throws Exception{
        JSONObject out=EventStore.object("status",status,"running",running);
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
        Activity a=activity;
        if(a==null)return;
        try{
            if(!Shizuku.pingBinder()){status="Shizuku non démarré — démarre Shizuku puis relance le nettoyage";return;}
            if(Shizuku.isPreV11()){status="Version Shizuku non prise en charge";return;}
            if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){runAsync();return;}
            if(Shizuku.shouldShowRequestPermissionRationale()){status="Autorisation Shizuku requise";return;}
            status="Demande d’autorisation Shizuku";
            Shizuku.requestPermission(REQUEST_CODE);
        }catch(Throwable t){status="Shizuku indisponible : "+t.getClass().getSimpleName();}
    }

    private static synchronized void runAsync(){
        Activity a=activity;
        if(a==null||running)return;
        running=true;
        status="Nettoyage Shizuku en cours";
        new Thread(()->{
            try{run(a.getApplicationContext());}
            catch(Throwable t){status="Nettoyage interrompu : "+t.getClass().getSimpleName();log(a,"CLEANUP_ERROR",EventStore.object("error",String.valueOf(t.getMessage()),"type",t.getClass().getName()));}
            finally{running=false;}
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
            if(!validPackage(pkg)||pkg.equals(c.getPackageName())){skipped++;continue;}
            JSONArray findings=assessment.optJSONArray("findings");
            if(findings!=null)for(int j=0;j<findings.length();j++){
                JSONObject finding=findings.getJSONObject(j);
                if(!Boolean.TRUE.equals(finding.opt("granted"))){continue;}
                String permission=finding.optString("permission");
                if(!validPermission(permission)){skipped++;continue;}
                attempted++;
                JSONObject change=EventStore.object("package",pkg,"kind","permission","name",permission,"before","granted","command","pm revoke");
                try{
                    ExecResult r=exec("pm revoke --user current "+q(pkg)+" "+q(permission));
                    boolean denied=c.getPackageManager().checkPermission(permission,pkg)!=PackageManager.PERMISSION_GRANTED;
                    change.put("exit",r.code).put("stdout",r.out).put("stderr",r.err).put("after",denied?"denied":"still_granted");
                    if(denied){change.put("inverse","pm grant --user current "+q(pkg)+" "+q(permission));changed++;}else failed++;
                }catch(Throwable t){change.put("error",t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));failed++;}
                snapshot.getJSONArray("changes").put(change);writeSnapshot(c,snapshot);log(c,"CLEANUP_PERMISSION",change);
            }
            JSONArray actions=assessment.optJSONArray("special_actions");
            if(actions!=null)for(int j=0;j<actions.length();j++){
                String action=actions.optString(j),op=appOp(action);
                if(op==null){skipped++;continue;}
                attempted++;
                JSONObject before=queryAppOp(pkg,op);
                JSONObject change=EventStore.object("package",pkg,"kind","appop","name",op,"before",before);
                try{
                    String mode=before.optString("mode");
                    if("deny".equals(mode)||"ignore".equals(mode)){change.put("after",mode).put("unchanged",true);snapshot.getJSONArray("changes").put(change);continue;}
                    ExecResult r=exec("cmd appops set --user current "+q(pkg)+" "+op+" deny");
                    JSONObject after=queryAppOp(pkg,op);
                    change.put("exit",r.code).put("stdout",r.out).put("stderr",r.err).put("after",after);
                    String afterMode=after.optString("mode");
                    if("deny".equals(afterMode)||"ignore".equals(afterMode)){
                        if(!mode.isEmpty()&&validMode(mode))change.put("inverse","cmd appops set --user current "+q(pkg)+" "+op+" "+mode);
                        changed++;
                    }else failed++;
                }catch(Throwable t){change.put("error",t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));failed++;}
                snapshot.getJSONArray("changes").put(change);writeSnapshot(c,snapshot);log(c,"CLEANUP_APPOP",change);
            }
        }
        snapshot.put("finished_ms",System.currentTimeMillis()).put("attempted",attempted).put("changed",changed).put("failed",failed).put("skipped",skipped);
        writeSnapshot(c,snapshot);
        PermissionAudit.get(c).scan();
        status="Nettoyage terminé : "+changed+" droit(s) retiré(s) sur "+attempted+" tentative(s), "+failed+" échec(s), "+candidateCount+" app(s) admissible(s)";
        log(c,"CLEANUP_DONE",EventStore.object("candidates",candidateCount,"attempted",attempted,"changed",changed,"failed",failed,"skipped",skipped,"snapshot",snapshotFile(c).getAbsolutePath()));
    }

    public static synchronized JSONObject restore(Context c)throws Exception{
        File f=snapshotFile(c);
        if(!f.isFile())return EventStore.object("restored",0,"failed",0,"error","Aucun snapshot de nettoyage");
        JSONObject snapshot=new JSONObject(readAll(f)),result=EventStore.object("schema","aiv-shizuku-restore/24","started_ms",System.currentTimeMillis());
        JSONArray changes=snapshot.optJSONArray("changes");int restored=0,failed=0;
        if(changes!=null)for(int i=changes.length()-1;i>=0;i--){
            JSONObject change=changes.optJSONObject(i);if(change==null)continue;
            String inverse=change.optString("inverse");if(inverse.isEmpty())continue;
            try{ExecResult r=exec(inverse);if(r.code==0)restored++;else failed++;log(c,"CLEANUP_RESTORE",EventStore.object("package",change.optString("package"),"name",change.optString("name"),"exit",r.code,"stderr",r.err));}
            catch(Throwable t){failed++;log(c,"CLEANUP_RESTORE_ERROR",EventStore.object("package",change.optString("package"),"name",change.optString("name"),"error",t.getClass().getSimpleName()));}
        }
        PermissionAudit.get(c).scan();
        status="Restauration terminée : "+restored+" restauré(s), "+failed+" échec(s)";
        return result.put("finished_ms",System.currentTimeMillis()).put("restored",restored).put("failed",failed);
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
    private static boolean validPermission(String s){return s!=null&&PERMISSION.matcher(s).matches();}
    private static boolean validMode(String s){return Arrays.asList("allow","deny","ignore","default","foreground").contains(s);}
    private static String q(String s){if(!validPackage(s)&&!validPermission(s))throw new IllegalArgumentException("Identité shell refusée");return "'"+s.replace("'","")+"'" ;}

    private static final class ExecResult{final int code;final String out,err;ExecResult(int c,String o,String e){code=c;out=o;err=e;}}

    private static ExecResult exec(String command)throws Exception{
        if(!Shizuku.pingBinder()||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("Shizuku non autorisé");
        Method m=Shizuku.class.getDeclaredMethod("newProcess",String[].class,String[].class,String.class);
        m.setAccessible(true);
        Process p=(Process)m.invoke(null,new Object[]{new String[]{"/system/bin/sh","-c",command},null,null});
        String out=readAll(p.getInputStream()),err=readAll(p.getErrorStream());int code=p.waitFor();
        return new ExecResult(code,trim(out),trim(err));
    }

    private static int safeServerUid(){try{return Shizuku.getUid();}catch(Throwable t){return -1;}}
    private static File snapshotFile(Context c){return new File(c.getFilesDir(),"aiv-shizuku-cleanup-last.json");}
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
