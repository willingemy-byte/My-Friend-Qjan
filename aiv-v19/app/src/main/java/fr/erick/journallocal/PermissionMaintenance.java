package fr.erick.journallocal;

import android.content.*;
import android.content.pm.*;
import android.os.SystemClock;
import android.os.Build;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit local policy. Runs separately from packet capture; no grant or installation veto. */
final class PermissionMaintenance {
    static final long INTERVAL_MS=15000;
    private static final long RUN_MS=30000;
    private static final int USER=android.os.Process.myUid()/100000,MAX_RIGHTS=10000,MAX_ACTIONS=12,MAX_SPECIAL_CHECKS=20;
    private static final java.util.concurrent.ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"aiv-permission-maintenance");t.setDaemon(true);return t;});
    private static final AtomicBoolean busy=new AtomicBoolean(),cancel=new AtomicBoolean();
    private static volatile String status="Maintien non activé";
    private static volatile int rights,known,pending;
    private static final class AuditFailure extends IOException {private static final long serialVersionUID=1L;AuditFailure(String message,Throwable cause){super(message,cause);}}
    private static final class UserGrant extends Exception {private static final long serialVersionUID=1L;final JSONObject observed;UserGrant(JSONObject observed){super("Autorisation utilisateur conservée");this.observed=observed;}}
    private PermissionMaintenance(){}
    static boolean busy(){return busy.get();}
    static String status(){return status;}
    static String displayStatus(Context c){
        if(busy())return status;
        if(!enabled(c))return status.startsWith("Maintien suspendu")?status:hasBaseline(c)?"Maintien en pause":"Maintien non activé · enregistrer les refus actuels";
        if(!PermissionControl.authorized())return "En attente de Shizuku · démarrer et autoriser AIV";
        if(!Continuous.enabled(c))return "En attente de la collecte AIV · relancer la collecte";
        return status.startsWith("Maintien actif")?status:"Maintien activé · contrôle périodique toutes les 15 secondes";
    }
    static void cancelPreparation(){cancel.set(true);}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("permission-maintenance",Context.MODE_PRIVATE);}
    static boolean enabled(Context c){return prefs(c).getBoolean("enabled",false);}
    static boolean blocksNew(Context c){return prefs(c).getBoolean("block_new",false);}
    static boolean hasBaseline(Context c){return new File(c.getFilesDir(),"permission-maintenance/policy.json").isFile();}
    private static File file(Context c,String name)throws IOException{
        File dir=new File(c.getFilesDir(),"permission-maintenance");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Dossier de maintien indisponible");return new File(dir,name);
    }
    private static JSONObject read(Context c,String name)throws Exception{
        try(InputStream in=new AtomicFile(file(c,name)).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>AivConfig.CONTROL_REPORT_MAX_BYTES)throw new IOException("État de maintien trop volumineux");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));
        }
    }
    private static void write(Context c,String name,JSONObject data)throws Exception{
        byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>AivConfig.CONTROL_REPORT_MAX_BYTES)throw new IOException("État de maintien plein");
        AtomicFile f=new AtomicFile(file(c,name));FileOutputStream out=f.startWrite();try{out.write(bytes);f.finishWrite(out);}catch(Exception e){f.failWrite(out);throw e;}
    }
    private static JSONObject policy(Context c)throws Exception{
        JSONObject p=read(c,"policy.json");if(!"aiv-permission-maintenance/1".equals(p.optString("schema"))||p.getInt("user")!=USER)throw new IOException("Référence de maintien incompatible");if(!p.has("exceptions"))p.put("exceptions",new JSONObject());return p;
    }
    private static void save(Context c,JSONObject p)throws Exception{
        if(p.getJSONObject("targets").length()+(p.optJSONObject("exceptions")==null?0:p.getJSONObject("exceptions").length())>MAX_RIGHTS||p.getJSONObject("known").length()>AivConfig.REFERENCE_MAX_APPS||p.getJSONObject("pending").length()>AivConfig.REFERENCE_MAX_APPS)throw new IOException("Borne du maintien atteinte");
        write(c,"policy.json",p);rights=p.getJSONObject("targets").length();known=p.getJSONObject("known").length();pending=p.getJSONObject("pending").length();
    }
    private static void access()throws Exception{
        if(!AccessPolicy.allows("shizuku.control",AccessPolicy.DISTRIBUTION_TIER)||!PermissionControl.authorized())throw new IllegalStateException("Shizuku arrêté ou non autorisé");
    }
    private static List<PackageInfo> installed(Context c)throws Exception{
        List<PackageInfo> list=c.getPackageManager().getInstalledPackages(PackageManager.GET_PERMISSIONS|PackageManager.MATCH_DISABLED_COMPONENTS);list.removeIf(p->p.applicationInfo==null||p.applicationInfo.uid/100000!=USER);list.sort(Comparator.comparing(p->p.packageName));
        if(list.size()>AivConfig.REFERENCE_MAX_APPS)throw new IOException("Inventaire supérieur à la borne de 20 000 applications");return list;
    }
    private static JSONObject snapshot(Context c,String pkg)throws Exception{
        PackageInfo p=PermissionControl.info(c,pkg);JSONObject app=AppIdentity.forPackage(c,p);if(app.getJSONArray("current_signer_sha256").length()==0)throw new IOException("Signataire non vérifiable");
        String[] peers=c.getPackageManager().getPackagesForUid(p.applicationInfo.uid);
        return EventStore.object("package",pkg,"label",String.valueOf(p.applicationInfo.loadLabel(c.getPackageManager())),"app_identity_id",app.getString("app_identity_id"),"uid",p.applicationInfo.uid,
            "first_install_ms",p.firstInstallTime,"updated_ms",p.lastUpdateTime,"version",version(p),"stamp",PermissionControl.identity(c,p),"guard",PermissionControl.targetReason(c,pkg),"peers",peers==null?0:peers.length,
            "system",(p.applicationInfo.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0,"enabled",c.getPackageManager().getApplicationEnabledSetting(pkg),"stopped",(p.applicationInfo.flags&ApplicationInfo.FLAG_STOPPED)!=0);
    }
    private static long version(PackageInfo p){return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
    static boolean sameOwner(JSONObject a,JSONObject b){return a!=null&&b!=null&&!a.optString("app_identity_id").isEmpty()&&a.optString("app_identity_id").equals(b.optString("app_identity_id"))&&a.optInt("uid",-1)==b.optInt("uid",-2)&&a.optLong("first_install_ms",-1)==b.optLong("first_install_ms",-2);}
    private static boolean selected(Context c,String pkg,String name,Set<String> confirmed)throws Exception{
        String profile=c.getSharedPreferences("permission-control",0).getString("profile:"+pkg,"");
        if(!profile.isEmpty()){JSONArray names=PermissionControl.profileChoices(c).optJSONObject(profile).getJSONArray("denied_permissions");for(int i=0;i<names.length();i++)if(name.equals(names.getString(i)))return true;return confirmed.contains(pkg+"|"+name);}
        return PermissionControl.reviewGroups(c).contains(PermissionReviewRules.group(name))||confirmed.contains(pkg+"|"+name);
    }
    private static Set<String> confirmed(Context c)throws Exception{
        Set<String> set=new HashSet<>();File dir=new File(c.getFilesDir(),"permission-control");File[] files=dir.listFiles();if(files==null)return set;
        int count=0;for(File f:files)if(f.getName().matches("[a-f0-9-]{36}\\.json")){
            if(++count>2000)throw new IOException("Trop de rapports : référence de maintien non créée");
            try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>AivConfig.CONTROL_REPORT_MAX_BYTES)throw new IOException("Rapport trop volumineux");out.write(b,0,n);}
                JSONObject report=new JSONObject(out.toString("UTF-8"));if(!Arrays.asList("aiv-permission-control/1","aiv-permission-plan/1").contains(report.optString("schema")))continue;
                JSONArray rows=report.optJSONArray("entries");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if("confirmed".equals(r.optString("outcome"))&&PermissionControlRules.name(r.optString("package"))&&PermissionControlRules.name(r.optString("name")))set.add(r.getString("package")+"|"+r.getString("name"));}
            }
        }return set;
    }
    private static JSONObject target(JSONObject app,String name,String kind,String desired,boolean include)throws Exception{return EventStore.object("package",app.getString("package"),"name",name,"kind",kind,"desired",desired,"owner",app,"guard",app.getString("guard"),"include_protected",include,"failures",0);}
    // USER_SET can survive earlier choices: it is evidence of consent, not proof of the latest actor.
    private static boolean userGranted(PermissionControlRules.Grant state){return state!=null&&state.granted&&!state.fixed()&&(PermissionControlRules.contains(state.flags,"USER_SET")||PermissionControlRules.contains(state.flags,"USER_FIXED")||PermissionControlRules.contains(state.flags,"ONE_TIME"));}
    private static JSONObject runtimeState(Context c,String pkg,String name)throws Exception{
        boolean granted=c.getPackageManager().checkPermission(name,pkg)==PackageManager.PERMISSION_GRANTED;PermissionControlRules.Grant shell=PermissionControl.shellPermissions(pkg).get(name);
        String reason=PermissionControlRules.runtimeReason(c.getPackageManager().getPermissionInfo(name,0).protectionLevel,granted,shell,"");if(!reason.isEmpty())throw new IOException(reason);
        return EventStore.object("granted",granted,"flags",shell.flags,"user_choice",userGranted(shell));
    }
    private static void excludeUserGrant(Context c,JSONObject p,JSONObject t,JSONObject observed)throws Exception{
        String pkg=t.getString("package"),name=t.getString("name"),key=pkg+"|"+name;
        JSONObject exception=EventStore.object("package",pkg,"name",name,"kind",t.getString("kind"),"owner",t.getJSONObject("owner"),"reason","Autorisation accordée avec un marqueur de choix utilisateur Android; auteur du dernier changement non déterminé","observed_state",observed,"excluded_ms",System.currentTimeMillis());
        p.getJSONObject("exceptions").put(key,exception);p.getJSONObject("targets").remove(key);
        try{save(c,p);}catch(Exception e){throw new AuditFailure("Exception non enregistrée : maintien suspendu",e);}
        receipt(c,EventStore.object("id",UUID.randomUUID().toString(),"package",pkg,"name",name,"kind","permission_user_exception","phase","excluded","exception",exception,"verified_ms",System.currentTimeMillis()));
    }
    static JSONObject prepare(Context context)throws Exception{
        access();if(!ControlCoordinator.acquire())throw new IllegalStateException("Une intervention est en cours");
        if(!busy.compareAndSet(false,true)){ControlCoordinator.release();throw new IllegalStateException("Maintien occupé");}cancel.set(false);Context c=context.getApplicationContext();status="Création de la référence de maintien…";
        WORKER.execute(()->{try{
            long started=SystemClock.elapsedRealtime();boolean merging=hasBaseline(c);JSONObject p=merging?policy(c):EventStore.object("schema","aiv-permission-maintenance/1","user",USER,"created_ms",System.currentTimeMillis(),"known",new JSONObject(),"targets",new JSONObject(),"exceptions",new JSONObject(),"pending",new JSONObject(),"cursor",0);p.put("errors",new JSONArray());
            Set<String> receipts=confirmed(c);boolean include=PermissionControl.includeProtected(c);List<PackageInfo> apps=installed(c);int index=0;
            for(PackageInfo info:apps){
                if(cancel.get()||SystemClock.elapsedRealtime()-started>15*60*1000L)throw new InterruptedIOException("Préparation arrêtée; aucune référence partielle activée");access();status="Référence "+(++index)+"/"+apps.size()+" · "+info.packageName;
                try{
                    JSONObject app=snapshot(c,info.packageName);if(merging&&!sameOwner(p.getJSONObject("known").optJSONObject(info.packageName),app))continue;p.getJSONObject("known").put(info.packageName,app);if(!PermissionReviewRules.targetAllowed(app.getString("guard"),include))continue;
                    Map<String,PermissionControlRules.Grant> shell=PermissionControl.shellPermissions(info.packageName);
                    String[] names=info.requestedPermissions;if(names==null)continue;
                    for(String name:names){if(!PermissionControlRules.name(name)||!selected(c,info.packageName,name,receipts))continue;
                        JSONObject exception=p.getJSONObject("exceptions").optJSONObject(info.packageName+"|"+name);if(exception!=null&&sameOwner(exception.optJSONObject("owner"),app))continue;
                        String op=PermissionControlRules.appOp(name);
                        if(!op.isEmpty()){
                            JSONObject state=PermissionControl.op(info.packageName,name);String mode=state.optString("mode");
                            if(!state.optBoolean("uid_scope")&&Arrays.asList("ignore","deny").contains(mode))p.getJSONObject("targets").put(info.packageName+"|"+name,target(app,name,"appop",mode,include));
                        }else{
                            PermissionControlRules.Grant state=shell.get(name);int protection=c.getPackageManager().getPermissionInfo(name,0).protectionLevel;
                            if(state==null&&(protection&15)==1)p.getJSONArray("errors").put(EventStore.object("package",info.packageName,"name",name,"error","État runtime non exposé par le relevé Shell"));
                            if(state!=null&&!state.granted&&!state.fixed()&&(protection&15)==1&&(protection&32)==0&&c.getPackageManager().checkPermission(name,info.packageName)!=PackageManager.PERMISSION_GRANTED)
                                p.getJSONObject("targets").put(info.packageName+"|"+name,target(app,name,"permission","revoke",include));
                        }
                    }
                }catch(Exception e){p.getJSONArray("errors").put(EventStore.object("package",info.packageName,"error",String.valueOf(e.getMessage())));}
            }
            if(cancel.get())throw new InterruptedIOException("Préparation arrêtée");save(c,p);if(!prefs(c).edit().putBoolean("enabled",true).commit())throw new IOException("Activation non enregistrée");status="Maintien actif · "+rights+" refus enregistrés";
        }catch(Exception e){fail(c,e);}finally{busy.set(false);ControlCoordinator.release();}});return EventStore.object("status",status);
    }
    static void pause(Context c)throws Exception{if(!prefs(c).edit().putBoolean("enabled",false).commit())throw new IOException("Pause non enregistrée");cancel.set(true);status="Maintien en pause";}
    static void resume(Context c)throws Exception{access();if(!hasBaseline(c))throw new IllegalStateException("Enregistrer d’abord les refus actuels");if(!ControlCoordinator.acquire())throw new IllegalStateException("Une intervention est en cours");try{JSONObject p=policy(c);save(c,p);if(!prefs(c).edit().putBoolean("enabled",true).commit())throw new IOException("Activation non enregistrée");cancel.set(false);status="Maintien demandé";}finally{ControlCoordinator.release();}request(c);}
    static void setBlocksNew(Context c,boolean enabled)throws Exception{if(enabled){access();if(!hasBaseline(c))throw new IllegalStateException("Enregistrer d’abord les refus actuels");policy(c);}if(!prefs(c).edit().putBoolean("block_new",enabled).commit())throw new IOException("Réglage non enregistré");if(enabled)request(c);}
    static void request(Context context){
        Context c=context.getApplicationContext();if(!enabled(c)||!Continuous.enabled(c)||!ControlCoordinator.acquire())return;if(!busy.compareAndSet(false,true)){ControlCoordinator.release();return;}cancel.set(false);
        WORKER.execute(()->{try{if(!enabled(c))return;if(!PermissionControl.authorized()){status="Maintien suspendu · Shizuku indisponible";return;}check(c);}catch(Exception e){fail(c,e);}finally{busy.set(false);ControlCoordinator.release();}});
    }
    private static void fail(Context c,Exception e){prefs(c).edit().putBoolean("enabled",false).commit();status="Maintien suspendu · "+String.valueOf(e.getMessage());}
    private static void receipt(Context c,JSONObject r)throws Exception{
        try{write(c,"last-action.json",r);if(!EventStore.get(c).add("aiv-maintien","Shizuku","PERMISSION_MAINTENANCE",r.optString("package"),"Interne","Politique locale explicite",r))throw new IOException("Journal indisponible");}
        catch(Exception e){throw new AuditFailure("Journal indisponible : intervention suspendue",e);}
    }
    private static boolean canContinue(Context c,long start,int actions){return !cancel.get()&&enabled(c)&&Continuous.enabled(c)&&SystemClock.elapsedRealtime()-start<RUN_MS&&actions<MAX_ACTIONS;}
    private static JSONObject invoke(Context c,JSONObject app,String command,String kind)throws Exception{
        return invoke(c,app,command,kind,null);
    }
    private static JSONObject invoke(Context c,JSONObject app,String command,String kind,String runtimePermission)throws Exception{
        access();if(cancel.get()||!enabled(c)||!Continuous.enabled(c))throw new InterruptedIOException("Maintien en pause");JSONObject current=snapshot(c,app.getString("package"));if(!app.getString("stamp").equals(current.getString("stamp"))||!app.getString("guard").equals(current.getString("guard"))||app.getInt("peers")!=current.getInt("peers"))throw new IOException("APK, rôle ou portée UID modifié avant commande");
        JSONObject r=EventStore.object("id",UUID.randomUUID().toString(),"package",app.getString("package"),"kind",kind,"command",command,"before",current,"phase","pending","requested_ms",System.currentTimeMillis());receipt(c,r);
        try{
            if(cancel.get()||!enabled(c)||!Continuous.enabled(c)||(kind.startsWith("new_application")&&!blocksNew(c)))throw new InterruptedIOException("Maintien en pause avant commande");
            access();
            JSONObject live=snapshot(c,app.getString("package"));if(!current.getString("stamp").equals(live.getString("stamp"))||!current.getString("guard").equals(live.getString("guard"))||current.getInt("peers")!=live.getInt("peers"))throw new IOException("Identité ou rôle modifié pendant l’écriture du reçu");
            if(runtimePermission!=null){JSONObject observed=runtimeState(c,app.getString("package"),runtimePermission);if(observed.getBoolean("user_choice"))throw new UserGrant(observed);}
            ControlShell.Result result=ControlShell.run(command);r.put("exit",result.code).put("complete",result.complete).put("stderr",result.err).put("phase","executed");
        }
        catch(UserGrant e){r.put("phase","skipped_user_choice").put("name",runtimePermission).put("observed_state",e.observed);receipt(c,r);throw e;}
        catch(Exception e){r.put("exit",-1).put("complete",false).put("phase","unverified").put("error",String.valueOf(e.getMessage()));}
        return r;
    }
    private static void finish(Context c,JSONObject r,boolean verified,JSONObject after)throws Exception{
        boolean confirmed=r.optInt("exit",-1)==0&&r.optBoolean("complete")&&verified;r.put("phase",confirmed?"confirmed":"unverified").put("after",after).put("verified_ms",System.currentTimeMillis());receipt(c,r);
    }
    private static void check(Context c)throws Exception{
        long started=SystemClock.elapsedRealtime();int actions=0,specialChecks=0;JSONObject p=policy(c),registry=p.getJSONObject("known"),queue=p.getJSONObject("pending"),targets=p.getJSONObject("targets");
        for(PackageInfo info:installed(c)){
            JSONObject owner=registry.optJSONObject(info.packageName);if(owner!=null&&owner.optInt("uid")==info.applicationInfo.uid&&owner.optLong("first_install_ms")==info.firstInstallTime&&owner.optLong("version")==version(info)&&owner.optLong("updated_ms")==info.lastUpdateTime&&owner.optString("stamp").length()>0)continue;
            if(!canContinue(c,started,actions))break;
            JSONObject current;try{current=snapshot(c,info.packageName);}catch(Exception e){
                JSONObject row=queue.optJSONObject(info.packageName);String error=String.valueOf(e.getMessage());
                if(row==null||!"identity_unverified".equals(row.optString("phase"))||!error.equals(row.optString("error"))){
                    JSONObject unverified=EventStore.object("package",info.packageName,"label",String.valueOf(info.applicationInfo.loadLabel(c.getPackageManager())),"uid",info.applicationInfo.uid,"version",version(info),"app_identity_id","","stamp","");
                    row=EventStore.object("owner",unverified,"first_seen_ms",row==null?System.currentTimeMillis():row.optLong("first_seen_ms"),"phase","identity_unverified","error",error,"reason","Identité non vérifiable : examen manuel requis");
                    queue.put(info.packageName,row);save(c,p);notifyReview(c,info.packageName);
                }continue;
            }
            if(sameOwner(owner,current)){registry.put(info.packageName,current);continue;}
            JSONObject row=queue.optJSONObject(info.packageName);
            if(row==null||!sameOwner(row.optJSONObject("owner"),current)){row=EventStore.object("owner",current,"initial_enabled",current.getInt("enabled"),"first_seen_ms",System.currentTimeMillis(),"phase","awaiting_review","failures",0);queue.put(info.packageName,row);save(c,p);notifyReview(c,info.packageName);}
            else if(!current.getString("stamp").equals(row.getJSONObject("owner").getString("stamp"))){row.put("owner",current);if(row.optBoolean("disabled_by_aiv")&&current.getInt("enabled")==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)row.put("disabled_stamp",current.getString("stamp"));}
            if(!blocksNew(c)||row.optInt("failures")>=AivConfig.CONTROL_WATCH_MAX_FAILURES)continue;
            String reason=current.getString("guard");if(!reason.isEmpty()||current.getBoolean("system")||current.getInt("peers")!=1||current.getInt("uid")%100000<10000){row.put("phase","protected_review").put("reason",reason.isEmpty()?"Application système ou UID partagé":reason);continue;}
            try{
                if(current.getInt("enabled")!=PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER){
                    JSONObject r=invoke(c,current,"pm disable-user --user "+USER+" "+PermissionControlRules.quote(info.packageName),"new_application_disable");actions++;
                    JSONObject after=snapshot(c,info.packageName);boolean verified=current.getString("stamp").equals(after.getString("stamp"))&&after.getInt("enabled")==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;finish(c,r,verified,after);
                    if(!"confirmed".equals(r.getString("phase")))throw new IOException("Désactivation non confirmée");row.put("disabled_by_aiv",true).put("disabled_stamp",after.getString("stamp"));current=after;
                }
                row.put("phase","disabled_after_detection");
                if(!current.getBoolean("stopped")&&canContinue(c,started,actions)){
                    JSONObject r=invoke(c,current,"am force-stop --user "+USER+" "+PermissionControlRules.quote(info.packageName),"new_application_stop");actions++;
                    JSONObject after=snapshot(c,info.packageName);finish(c,r,current.getString("stamp").equals(after.getString("stamp"))&&after.getBoolean("stopped"),after);if(!"confirmed".equals(r.getString("phase")))throw new IOException("Arrêt non confirmé");
                }row.put("failures",0);
            }catch(AuditFailure e){throw e;}catch(Exception e){if(!PermissionControl.authorized())break;row.put("failures",row.optInt("failures")+1).put("error",String.valueOf(e.getMessage()));}
        }
        List<String> keys=new ArrayList<>();targets.keys().forEachRemaining(keys::add);Collections.sort(keys);int cursor=keys.isEmpty()?0:Math.floorMod(p.optInt("cursor"),keys.size()),processed=0;
        for(int n=0;n<keys.size()&&canContinue(c,started,actions);n++){
            JSONObject t=targets.getJSONObject(keys.get((cursor+n)%keys.size()));processed++;if(t.optInt("failures")>=AivConfig.CONTROL_WATCH_MAX_FAILURES||t.has("suspended"))continue;
            String pkg=t.getString("package"),name=t.getString("name"),kind=t.getString("kind");
            if("permission".equals(kind)&&c.getPackageManager().checkPermission(name,pkg)!=PackageManager.PERMISSION_GRANTED)continue;
            if("appop".equals(kind)&&specialChecks>=MAX_SPECIAL_CHECKS){processed--;break;}if("appop".equals(kind))specialChecks++;
            try{
                JSONObject current=snapshot(c,pkg);if(!sameOwner(t.getJSONObject("owner"),current)||!PermissionReviewRules.sameTarget(t.getString("guard"),current.getString("guard"),t.getBoolean("include_protected"))){t.put("suspended","Identité, rôle ou portée UID modifié");notifyReview(c,pkg);continue;}
                JSONObject state;
                if("appop".equals(kind)){
                    state=PermissionControl.op(pkg,name);String mode=state.optString("mode");if(mode.equals(t.getString("desired")))continue;
                    if(mode.isEmpty()||state.optBoolean("uid_scope"))throw new IOException("AppOp non vérifiée ou portée UID");
                }else{
                    state=runtimeState(c,pkg,name);if(state.getBoolean("user_choice")){excludeUserGrant(c,p,t,state);continue;}
                }
                JSONObject r=invoke(c,current,PermissionControlRules.command(pkg,USER,kind,name,t.getString("desired")),"permission_reapply","permission".equals(kind)?name:null);actions++;r.put("name",name).put("observed_state",state);
                JSONObject after=snapshot(c,pkg),observed;
                if("appop".equals(kind))observed=PermissionControl.op(pkg,name);
                else{PermissionControlRules.Grant grant=PermissionControl.shellPermissions(pkg).get(name);observed=EventStore.object("granted",c.getPackageManager().checkPermission(name,pkg)==PackageManager.PERMISSION_GRANTED,"shell_granted",grant==null?JSONObject.NULL:grant.granted);}
                boolean verified=current.getString("stamp").equals(after.getString("stamp"))&&("appop".equals(kind)?t.getString("desired").equals(observed.optString("mode"))&&!observed.optBoolean("uid_scope"):observed.has("shell_granted")&&!observed.isNull("shell_granted")&&!observed.getBoolean("shell_granted")&&!observed.getBoolean("granted"));
                r.put("permission_after",observed);finish(c,r,verified,after);if(!"confirmed".equals(r.getString("phase")))throw new IOException("Retrait non confirmé");t.put("failures",0);p.put("corrections",p.optLong("corrections")+1);
            }catch(UserGrant e){excludeUserGrant(c,p,t,e.observed);}catch(AuditFailure e){throw e;}catch(Exception e){if(!PermissionControl.authorized())break;t.put("failures",t.optInt("failures")+1).put("error",String.valueOf(e.getMessage()));notifyReview(c,pkg);}
        }
        p.put("cursor",keys.isEmpty()?0:(cursor+processed)%keys.size()).put("last_check_ms",System.currentTimeMillis());save(c,p);status=!enabled(c)?"Maintien en pause":!PermissionControl.authorized()?"Maintien suspendu · Shizuku indisponible":"Maintien actif · "+rights+" refus · "+p.optLong("corrections")+" corrections · "+p.getJSONObject("exceptions").length()+" autorisations conservées · "+reviewCount(targets)+" suivis suspendus · "+pending+" applications à examiner";
    }
    private static void notifyReview(Context c,String pkg){try{android.app.NotificationManager nm=c.getSystemService(android.app.NotificationManager.class);nm.createNotificationChannel(new android.app.NotificationChannel("aiv-maintenance","Maintien des droits",android.app.NotificationManager.IMPORTANCE_DEFAULT));
        android.app.PendingIntent open=android.app.PendingIntent.getActivity(c,629,new Intent(c,MainActivity.class).putExtra("open_maintenance",true),android.app.PendingIntent.FLAG_IMMUTABLE|android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(629,new android.app.Notification.Builder(c,"aiv-maintenance").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("AIV · application ou droit à examiner").setContentText(pkg).setContentIntent(open).setAutoCancel(true).build());}catch(Exception ignored){/* Durable policy remains available without notification permission. */}}
    private static int reviewCount(JSONObject targets){int count=0;for(Iterator<String> keys=targets.keys();keys.hasNext();){JSONObject t=targets.optJSONObject(keys.next());if(t!=null&&(t.has("suspended")||t.optInt("failures")>=AivConfig.CONTROL_WATCH_MAX_FAILURES))count++;}return count;}
    static JSONObject state(Context c)throws Exception{
        JSONObject out=EventStore.object("enabled",enabled(c),"block_new",blocksNew(c),"has_baseline",hasBaseline(c),"busy",busy(),"status",displayStatus(c),"rights",rights,"known",known,"pending_count",pending,"interval_ms",INTERVAL_MS);
        if(hasBaseline(c)){JSONObject p=policy(c);out.put("rights",p.getJSONObject("targets").length()).put("user_exceptions",p.getJSONObject("exceptions").length()).put("suspended_rights",reviewCount(p.getJSONObject("targets"))).put("known",p.getJSONObject("known").length()).put("pending",p.getJSONObject("pending")).put("pending_count",p.getJSONObject("pending").length()).put("corrections",p.optLong("corrections")).put("last_check_ms",p.optLong("last_check_ms")).put("baseline_errors",p.getJSONArray("errors"));}return out;
    }
    static JSONObject exportState(Context c)throws Exception{JSONObject out=state(c);if(hasBaseline(c))out.put("policy",policy(c));try{out.put("last_action",read(c,"last-action.json"));}catch(FileNotFoundException ignored){}return out;}
    /** Called while the shared executor lease is held. A failed policy write never falsifies a confirmed Shell receipt. */
    static void remember(Context c,JSONObject planned,JSONObject after){if(!hasBaseline(c))return;try{JSONObject p=policy(c),app=snapshot(c,planned.getString("package"));String kind=planned.getString("kind");String desired="appop".equals(kind)?after.getJSONObject("appop").getString("mode"):"revoke";
        String key=planned.getString("package")+"|"+planned.getString("name");p.getJSONObject("exceptions").remove(key);p.getJSONObject("targets").put(key,target(app,planned.getString("name"),kind,desired,planned.optBoolean("include_protected")));save(c,p);
    }catch(Exception e){fail(c,e);}}
    static void forget(Context c,String pkg,String name)throws Exception{if(!hasBaseline(c))return;JSONObject p=policy(c);p.getJSONObject("targets").remove(pkg+"|"+name);save(c,p);}
    static JSONObject approve(Context c,String pkg,String stamp)throws Exception{
        access();if(!ControlCoordinator.acquire())throw new IllegalStateException("Une intervention est en cours");try{
            JSONObject p=policy(c),row=p.getJSONObject("pending").getJSONObject(pkg),current=snapshot(c,pkg);if(!current.getString("stamp").equals(stamp)||!sameOwner(row.getJSONObject("owner"),current))throw new IOException("Application modifiée : actualiser avant approbation");
            boolean reactivate=row.optBoolean("disabled_by_aiv");
            if(reactivate&&(!current.getString("stamp").equals(row.getString("disabled_stamp"))||current.getInt("enabled")!=PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER))throw new IOException("État modifié depuis la désactivation : réactivation manuelle requise");
            for(JSONObject rules:new JSONObject[]{p.getJSONObject("targets"),p.getJSONObject("exceptions")}){List<String> removed=new ArrayList<>();for(Iterator<String> keys=rules.keys();keys.hasNext();){String k=keys.next();if(k.startsWith(pkg+"|")&&!sameOwner(rules.getJSONObject(k).getJSONObject("owner"),row.getJSONObject("owner")))removed.add(k);}for(String key:removed)rules.remove(key);}
            // Persist the user's approval before enabling anything: a disk failure must not cause a later re-quarantine.
            p.getJSONObject("known").put(pkg,current);p.getJSONObject("pending").remove(pkg);save(c,p);status="Application approuvée : "+pkg;
            if(reactivate){
                String command="pm "+ControlRules.enabledCommand(row.getInt("initial_enabled"))+" --user "+USER+" "+PermissionControlRules.quote(pkg);
                JSONObject r=EventStore.object("id",UUID.randomUUID().toString(),"package",pkg,"kind","application_approval","command",command,"before",current,"phase","pending","requested_ms",System.currentTimeMillis());receipt(c,r);
                try{access();JSONObject live=snapshot(c,pkg);if(!stamp.equals(live.getString("stamp"))||!current.getString("guard").equals(live.getString("guard"))||current.getInt("peers")!=live.getInt("peers")||live.getInt("enabled")!=PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)throw new IOException("Identité, rôle ou état modifié : réactivation manuelle requise");
                    ControlShell.Result result=ControlShell.run(command);r.put("exit",result.code).put("complete",result.complete).put("stderr",result.err);
                }catch(Exception e){r.put("exit",-1).put("complete",false).put("error",String.valueOf(e.getMessage()));}
                JSONObject after=snapshot(c,pkg);finish(c,r,stamp.equals(after.getString("stamp"))&&after.getInt("enabled")==row.getInt("initial_enabled"),after);if(!"confirmed".equals(r.getString("phase")))throw new IOException("Application approuvée; réactivation non confirmée. Consulter la fiche Android.");
            }
            return EventStore.object("status",status);
        }finally{ControlCoordinator.release();}
    }
}
