package fr.erick.journallocal;

import android.content.*;
import android.content.pm.*;
import android.os.Build;
import android.util.AtomicFile;
import org.json.*;
import rikka.shizuku.Shizuku;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Local permission review, bounded Shell actions, durable receipts and guarded restoration. */
final class PermissionControl {
    private static final int USER=android.os.Process.myUid()/100000;
    private static final int MAX_CHANGES=500, PAGE=50;
    private static volatile boolean running,analysing;
    private static final java.util.concurrent.atomic.AtomicBoolean stop=new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile String batchPrefix="";
    private static volatile String status="Aucun retrait de permissions en cours";
    private PermissionControl(){}
    static boolean running(){return running||analysing||PermissionMaintenance.busy();}
    static void requestStop(){stop.set(true);PermissionMaintenance.cancelPreparation();status="Arrêt demandé — fin de la commande en cours";}
    static String status(){return PermissionMaintenance.busy()?PermissionMaintenance.status():status;}
    static boolean authorized(){
        try{return Shizuku.pingBinder()&&!Shizuku.isPreV11()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}
        catch(Throwable t){return false;}
    }
    private static void access()throws Exception{
        if(!AccessPolicy.allows("shizuku.control",ProductAccess.verifiedTier()))throw new SecurityException("Contrôle indisponible dans cette édition");
        if(!authorized())throw new IllegalStateException("Démarre Shizuku puis autorise AIV dans Shizuku");
    }
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("permission-control",Context.MODE_PRIVATE);}
    private static File dir(Context c)throws IOException{
        File d=new File(c.getFilesDir(),"permission-control");if(!d.isDirectory()&&!d.mkdirs())throw new IOException("Rapport de permissions non enregistrable");return d;
    }
    private static void write(Context c,String name,JSONObject value)throws Exception{
        byte[] data=value.toString().getBytes(StandardCharsets.UTF_8);
        if(data.length>AivConfig.CONTROL_REPORT_MAX_BYTES)throw new IOException("Rapport trop volumineux");
        AtomicFile f=new AtomicFile(new File(dir(c),name));FileOutputStream out=f.startWrite();
        try{out.write(data);f.finishWrite(out);}catch(Exception e){f.failWrite(out);throw e;}
    }
    private static JSONObject read(Context c,String name)throws Exception{
        try(InputStream in=new AtomicFile(new File(dir(c),name)).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>AivConfig.CONTROL_REPORT_MAX_BYTES)throw new IOException("Rapport trop volumineux");out.write(b,0,n);}
            return new JSONObject(out.toString("UTF-8"));
        }
    }
    private static String hash(String s)throws Exception{return ApkEvidence.hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
    static boolean includeProtected(Context c){return prefs(c).getBoolean("include-protected",false);}
    static Set<String> reviewGroups(Context c){
        Set<String> groups=new LinkedHashSet<>();SharedPreferences p=prefs(c);
        for(String group:PermissionReviewRules.GROUPS)if(p.getBoolean("review:"+group,PermissionReviewRules.defaults().contains(group)))groups.add(group);
        return groups;
    }
    static void setReviewGroup(Context c,String group,boolean checked)throws Exception{
        if(running())throw new IllegalStateException("Attendre la fin de l’opération");
        if(!Arrays.asList(PermissionReviewRules.GROUPS).contains(group))throw new IllegalArgumentException("Catégorie inconnue");
        if(!prefs(c).edit().putBoolean("review:"+group,checked).commit())throw new IOException("Politique non enregistrée");
    }
    static void setIncludeProtected(Context c,boolean checked)throws Exception{
        if(running())throw new IllegalStateException("Attendre la fin de l’opération");
        if(!prefs(c).edit().putBoolean("include-protected",checked).commit())throw new IOException("Politique non enregistrée");
    }
    private static boolean sameTarget(Context c,String pkg,JSONObject planned){
        return PermissionReviewRules.sameTarget(planned.optString("target_guard",""),targetReason(c,pkg),planned.optBoolean("include_protected"));
    }
    static JSONObject review(Context c)throws Exception{
        JSONObject result;
        try{result=read(c,"review.json");}catch(FileNotFoundException e){return EventStore.object("phase","none","applications",new JSONArray());}
        if("analysing".equals(result.optString("phase"))&&!analysing)result.put("phase","interrupted");
        int proposed=0;JSONObject norms=profiles(c);Set<String> groups=reviewGroups(c);boolean include=includeProtected(c);JSONArray rows=result.optJSONArray("applications");
        if(rows!=null)for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);int count=0;
            if(!row.optBoolean("aiv_protection")||include){
                JSONObject counts=row.optJSONObject("candidate_groups");String profile=prefs(c).getString("profile:"+row.optString("package"),"");
                if(!profile.isEmpty()){Set<String> denied=denied(norms,profile);JSONArray names=row.optJSONArray("eligible_names");if(names!=null)for(int n=0;n<names.length();n++)if(denied.contains(names.optString(n)))count++;}
                else if(counts!=null)for(String group:groups)count+=counts.optInt(group);
            }
            row.put("proposed",count);proposed+=count;
        }
        return result.put("proposed",proposed).put("selected_groups",new JSONArray(groups)).put("include_protected",include);
    }
    static synchronized void startReview(Context context)throws Exception{
        access();if(running())throw new IllegalStateException("Une opération est en cours");
        Context c=context.getApplicationContext();analysing=true;stop.set(false);status="Analyse globale — lecture des applications";
        new Thread(()->{
            JSONObject report=null;
            try{
                List<PackageInfo> apps=c.getPackageManager().getInstalledPackages(PackageManager.GET_PERMISSIONS|PackageManager.MATCH_DISABLED_COMPONENTS);
                apps.removeIf(p->p.applicationInfo==null||p.applicationInfo.uid/100000!=USER);
                if(apps.size()>AivConfig.REFERENCE_MAX_APPS)throw new IOException("Inventaire supérieur à la borne de 20 000 applications");
                apps.sort(Comparator.comparing(p->p.packageName));long started=System.currentTimeMillis();
                report=EventStore.object("schema","aiv-permission-review/1","id",UUID.randomUUID().toString(),"phase","analysing","created_ms",started,
                    "user",USER,"total",apps.size(),"scanned",0,"permissions",0,"android_locked",0,"aiv_protected",0,"uid_scope",0,"unknown",0,"errors",0,
                    "applications",new JSONArray(),"catalogs",ReferenceCatalog.get(c).summary());write(c,"review.json",report);
                for(PackageInfo app:apps){
                    if(stop.get())throw new InterruptedIOException("Analyse arrêtée; résultats partiels conservés");
                    if(System.currentTimeMillis()-started>15*60*1000L)throw new InterruptedIOException("Borne de 15 minutes atteinte; résultats partiels conservés");
                    access();status="Analyse "+(report.optInt("scanned")+1)+"/"+apps.size()+" · "+String.valueOf(app.applicationInfo.loadLabel(c.getPackageManager()));
                    JSONObject row;
                    try{
                        // Observe platform eligibility even for AIV-protected apps; inclusion remains an explicit policy.
                        JSONObject d=detail(c,app.packageName,true,true);JSONArray permissions=d.getJSONArray("permissions");
                        JSONObject counts=new JSONObject();JSONArray eligibleNames=new JSONArray();int profiles=0,locked=0,scope=0,unknown=0,eligible=0;
                        String protection=d.optString("target_reason");boolean protectedApp=protection.startsWith("Protection AIV");
                        for(int i=0;i<permissions.length();i++){
                            JSONObject permission=permissions.getJSONObject(i);String origin=permission.optString("block_origin"),name=permission.getString("name");
                            if("ANDROID_LOCK".equals(origin))locked++;if("UID_SCOPE".equals(origin))scope++;if("OBSERVATION".equals(origin))unknown++;
                            String mode=permission.optJSONObject("appop")==null?"":permission.getJSONObject("appop").optString("mode");
                            boolean can=permission.optBoolean("can_revoke")&&(!"appop".equals(permission.optString("kind"))||Arrays.asList("allow","foreground").contains(mode));
                            permission.put("review_candidate",can).put("review_reason",d.optString("profile").isEmpty()?"Accès sélectionnable selon la politique globale : "+PermissionReviewRules.label(PermissionReviewRules.group(name)):permission.optString("assessment"));
                            if(can){eligible++;eligibleNames.put(name);String group=PermissionReviewRules.group(name);counts.put(group,counts.optInt(group)+1);if(permission.optBoolean("outside_profile"))profiles++;}
                        }
                        d.put("review_id",report.getString("id")).put("observed_ms",System.currentTimeMillis());write(c,"review-"+app.packageName+".json",d);
                        row=EventStore.object("package",app.packageName,"label",d.getString("label"),"uid",d.getInt("uid"),"declared",permissions.length(),
                            "eligible",eligible,"android_locked",locked,"uid_scope",scope,"unknown",unknown,"target_reason",protection,"aiv_protection",protectedApp,
                            "candidate_groups",counts,"eligible_names",eligibleNames,"has_profile",!d.optString("profile").isEmpty(),"profile_candidates",profiles,
                            "tracker_count",d.getJSONObject("apk_evidence").optJSONArray("trackers")==null?0:d.getJSONObject("apk_evidence").getJSONArray("trackers").length(),
                            "tracker_status",d.getJSONObject("apk_evidence").optString("status","PENDING"));
                        report.put("permissions",report.optInt("permissions")+permissions.length()).put("android_locked",report.optInt("android_locked")+locked)
                            .put("uid_scope",report.optInt("uid_scope")+scope).put("unknown",report.optInt("unknown")+unknown)
                            .put("aiv_protected",report.optInt("aiv_protected")+(protectedApp?1:0));
                    }catch(Exception e){
                        row=EventStore.object("package",app.packageName,"label",String.valueOf(app.applicationInfo.loadLabel(c.getPackageManager())),"error",String.valueOf(e.getMessage()));
                        report.put("errors",report.optInt("errors")+1);
                    }
                    report.getJSONArray("applications").put(row);report.put("scanned",report.getJSONArray("applications").length());write(c,"review.json",report);
                }
                report.put("phase","finished").put("finished_ms",System.currentTimeMillis());write(c,"review.json",report);
                status="Analyse terminée · "+report.optInt("scanned")+" applications · "+report.optInt("permissions")+" permissions";
            }catch(Exception e){
                status="Analyse interrompue : "+e.getMessage();if(report!=null)try{report.put("phase","interrupted").put("error",String.valueOf(e.getMessage()));write(c,"review.json",report);}catch(Exception ignored){}
            }finally{analysing=false;}
        },"aiv-permission-global-review").start();
    }
    /** Preview all selected apps; create bounded child plans without dropping the tail. */
    static synchronized JSONObject previewReview(Context c)throws Exception{
        access();if(running())throw new IllegalStateException("Attendre la fin de l’analyse ou de l’intervention");
        JSONObject analysis=review(c);if("none".equals(analysis.optString("phase")))throw new IllegalStateException("Lancer d’abord Analyser toutes les applications");
        JSONObject norms=profiles(c);Set<String> groups=reviewGroups(c);boolean include=includeProtected(c);Map<String,JSONObject> details=new HashMap<>(),selected=new HashMap<>();List<PermissionReviewRules.Target> targets=new ArrayList<>();
        JSONArray apps=analysis.getJSONArray("applications");
        for(int a=0;a<apps.length();a++){
            JSONObject app=apps.getJSONObject(a);if(app.has("error")||(!include&&app.optBoolean("aiv_protection")))continue;
            String pkg=app.getString("package");if(!PermissionControlRules.packageName(pkg))throw new IOException("Paquet d’analyse invalide");
            JSONObject d=read(c,"review-"+pkg+".json");if(!analysis.getString("id").equals(d.optString("review_id"))||!pkg.equals(d.optString("package")))throw new IOException("Analyse remplacée : relancer l’analyse");
            String currentProfile=prefs(c).getString("profile:"+pkg,"");Set<String> excluded=denied(norms,currentProfile);
            details.put(pkg,d);JSONArray rows=d.getJSONArray("permissions");
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);String name=row.getString("name");JSONObject op=row.optJSONObject("appop");
                if(!PermissionReviewRules.selected(name,row.optBoolean("can_revoke"),row.optString("kind"),op==null?"":op.optString("mode"),!currentProfile.isEmpty(),excluded.contains(name),groups))continue;
                row.put("review_reason",currentProfile.isEmpty()?"Politique globale : "+PermissionReviewRules.label(PermissionReviewRules.group(name)):"Hors de l’usage enregistré : "+norms.getJSONObject(currentProfile).optString("label"));
                targets.add(new PermissionReviewRules.Target(pkg,name));selected.put(pkg+"|"+name,row);
                if(targets.size()>20000)throw new IOException("Plus de 20 000 droits sélectionnés : réduire les catégories du plan");
            }
        }
        Map<String,Integer> priorities=new HashMap<>();
        for(PermissionReviewRules.Target target:targets){JSONObject evidence=details.get(target.pkg).getJSONObject("apk_evidence");JSONArray trackers=evidence.optJSONArray("trackers");int value=PermissionReviewRules.priority(target.name,trackers==null?0:trackers.length());priorities.put(target.pkg,Math.min(priorities.getOrDefault(target.pkg,99),value));}
        targets.sort(Comparator.comparingInt((PermissionReviewRules.Target t)->priorities.get(t.pkg)).thenComparing(t->t.pkg).thenComparingInt(t->PermissionControlRules.revokeOrder(t.name)).thenComparing(t->t.name));
        String queueId=UUID.randomUUID().toString();JSONArray batches=new JSONArray(),summary=new JSONArray();Set<String> packages=new HashSet<>();int batchIndex=0;
        for(List<PermissionReviewRules.Target> part:PermissionReviewRules.batches(targets,AivConfig.CONTROL_BATCH_MAX_APPS,MAX_CHANGES)){
            JSONObject identities=new JSONObject(),observations=new JSONObject();JSONArray changes=new JSONArray();String id=UUID.randomUUID().toString();
            for(PermissionReviewRules.Target target:part){
                JSONObject d=details.get(target.pkg),row=selected.get(target.pkg+"|"+target.name);packages.add(target.pkg);identities.put(target.pkg,d.getString("identity"));
                if(!observations.has(target.pkg)){
                    JSONObject grants=new JSONObject();JSONArray permissions=d.getJSONArray("permissions");
                    for(int i=0;i<permissions.length();i++){JSONObject p=permissions.getJSONObject(i);if((p.optInt("protection_level",-1)&15)==1&&!p.isNull("flags"))grants.put(p.getString("name"),EventStore.object("granted",p.getBoolean("granted"),"flags",p.getString("flags")));}
                    observations.put(target.pkg,grants);
                }
                JSONObject change=EventStore.object("package",target.pkg,"label",d.getString("label"),"name",target.name,"kind",row.getString("kind"),"before",row,
                    "identity",d.getString("identity"),"target_guard",d.optString("target_reason"),"include_protected",include,"command",row.getString("command"),"reason",row.getString("review_reason"));
                changes.put(change);summary.put(EventStore.object("package",target.pkg,"label",d.getString("label"),"name",target.name,"group",row.optString("review_group_label"),"command",row.getString("command"),"before",row.getString("state_key"),"reason",row.getString("review_reason")));
            }
            JSONObject draft=EventStore.object("schema","aiv-permission-plan/1","id",id,"queue_id",queueId,"user",USER,"identities",identities,"observations_before",observations,"changes",changes,"phase","preview");
            write(c,queueId+"-plan-"+batchIndex+".json",draft);batches.put(EventStore.object("id",id,"index",batchIndex++,"rights",changes.length(),"apps",identities.length()));
        }
        JSONObject plan=EventStore.object("schema","aiv-permission-queue/1","id",queueId,"created_ms",System.currentTimeMillis(),"user",USER,"server_uid",Shizuku.getUid(),
            "analysis_id",analysis.getString("id"),"analysis_phase",analysis.getString("phase"),"scanned",analysis.optInt("scanned"),"total",analysis.optInt("total"),
            "policy",new JSONArray(groups),"include_protected",include,"batches",batches,"changes",summary,"apps",packages.size(),"rights",targets.size(),"phase","preview");
        plan.put("stamp",hash(plan.toString()));write(c,"review-plan.json",plan);return plan;
    }
    static synchronized JSONObject applyReview(Context context,String stamp)throws Exception{
        access();if(running())throw new IllegalStateException("Une opération est en cours");if(!ControlCoordinator.acquire())throw new IllegalStateException("Une intervention est déjà en cours");
        Context c=context.getApplicationContext();
        try{
            JSONObject plan=read(c,"review-plan.json");
            if(plan.optBoolean("used")||!stamp.equals(plan.optString("stamp"))||!PermissionControlRules.fresh(System.currentTimeMillis(),plan.getLong("created_ms"),AivConfig.CONTROL_PREVIEW_VALID_MS))throw new IllegalStateException("Plan expiré ou consommé : préparer de nouveau");
            if(plan.getInt("user")!=USER||plan.getInt("server_uid")!=Shizuku.getUid()||plan.optInt("rights")==0)throw new IllegalStateException("Profil ou serveur modifié, ou aucun retrait sélectionné");
            plan.put("phase","queued").put("reports",new JSONArray()).put("entries",new JSONArray()).put("changed",0).put("failed",0);saveReport(c,plan);
            write(c,"review-plan.json",new JSONObject(plan.toString()).put("used",true));
            if(!prefs(c).edit().putString("last_apply_report",plan.getString("id")).commit())throw new IOException("Référence de restauration non enregistrée");
            stop.set(false);running=true;status="Retrait global — "+plan.optInt("rights")+" droits dans "+plan.getJSONArray("batches").length()+" lots";
            new Thread(()->{try{runQueue(c,plan);PermissionAudit.get(c).scan();}catch(Exception e){interrupt(c,plan,e);}finally{running=false;batchPrefix="";ControlCoordinator.release();}},"aiv-permission-global-apply").start();
            return EventStore.object("report_id",plan.getString("id"),"status",status);
        }catch(Exception e){ControlCoordinator.release();throw e;}
    }
    private static void runQueue(Context c,JSONObject report)throws Exception{
        JSONArray batches=report.getJSONArray("batches");report.put("phase","applying");saveReport(c,report);
        for(int i=0;i<batches.length();i++){
            if(stop.get())throw new InterruptedIOException("Arrêt demandé; le rapport global conserve les lots exécutés");
            batchPrefix="Lot "+(i+1)+"/"+batches.length()+" · ";
            JSONObject child=read(c,report.getString("id")+"-plan-"+i+".json");
            if(!report.getString("id").equals(child.optString("queue_id"))||!batches.getJSONObject(i).getString("id").equals(child.optString("id")))throw new IOException("Lot incompatible avec le plan");
            child.put("entries",new JSONArray()).put("phase","queued");saveReport(c,child);report.getJSONArray("reports").put(child.getString("id"));saveReport(c,report);
            try{run(c,child);}catch(Exception e){interrupt(c,child,e);report.put("changed",report.optInt("changed")+child.optInt("changed")).put("failed",report.optInt("failed")+child.optInt("failed"));saveReport(c,report);throw e;}
            report.put("changed",report.optInt("changed")+child.optInt("changed")).put("failed",report.optInt("failed")+child.optInt("failed")).put("completed_batches",i+1);saveReport(c,report);
        }
        report.put("phase","finished").put("finished_ms",System.currentTimeMillis());saveReport(c,report);
        status=report.optInt("changed")+" retrait(s) confirmé(s) · "+report.optInt("failed")+" refus/état(s) non confirmé(s) · "+batches.length()+" lots terminés";
    }
    private static void restoreQueue(Context c,JSONObject original,JSONObject report)throws Exception{
        JSONArray ids=original.getJSONArray("reports");JSONArray children=new JSONArray();report.put("batches",children);int restored=0,failed=0,skipped=0;
        for(int i=ids.length()-1;i>=0;i--){
            if(stop.get())throw new InterruptedIOException("Restauration globale arrêtée");batchPrefix="Restauration lot "+(ids.length()-i)+"/"+ids.length()+" · ";
            JSONObject child=read(c,ids.getString(i)+".json"),result=EventStore.object("schema","aiv-permission-restore/1","id",UUID.randomUUID().toString(),"source_report",ids.getString(i),"phase","restoring","entries",new JSONArray());
            saveReport(c,result);children.put(EventStore.object("id",result.getString("id"),"source_report",ids.getString(i)));saveReport(c,report);
            try{restoreRun(c,child,result);}catch(Exception e){interrupt(c,result,e);throw e;}
            restored+=result.optInt("restored");failed+=result.optInt("failed");skipped+=result.optInt("skipped");report.put("restored",restored).put("failed",failed).put("skipped",skipped);saveReport(c,report);
        }
        report.put("phase","finished").put("finished_ms",System.currentTimeMillis());saveReport(c,report);status=restored+" droits restaurés · "+failed+" états non confirmés · "+skipped+" ignorés";
    }
    private static JSONObject profiles(Context c)throws Exception{
        try(InputStream in=c.getAssets().open("permission-baselines.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>256*1024)throw new IOException("Normes trop volumineuses");out.write(b,0,n);}
            return new JSONObject(out.toString("UTF-8")).getJSONObject("profiles");
        }
    }
    static JSONObject profileChoices(Context c)throws Exception{return profiles(c);}
    static void setProfile(Context c,String pkg,String profile)throws Exception{
        if(!PermissionControlRules.packageName(pkg))throw new IllegalArgumentException("Paquet invalide");
        if(!profile.isEmpty()&&!profiles(c).has(profile))throw new IllegalArgumentException("Usage inconnu");
        if(!prefs(c).edit().putString("profile:"+pkg,profile).commit())throw new IOException("Usage non enregistré");
    }
    private static Set<String> denied(JSONObject all,String profile)throws Exception{
        Set<String> out=new HashSet<>();JSONObject row=all.optJSONObject(profile);
        if(row!=null){JSONArray names=row.getJSONArray("denied_permissions");for(int i=0;i<names.length();i++)out.add(names.getString(i));}
        return out;
    }
    static PackageInfo info(Context c,String pkg)throws Exception{
        if(!PermissionControlRules.packageName(pkg))throw new IllegalArgumentException("Paquet invalide");
        int signing=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo p=c.getPackageManager().getPackageInfo(pkg,signing|PackageManager.GET_PERMISSIONS|PackageManager.MATCH_DISABLED_COMPONENTS);
        if(p.applicationInfo==null||p.applicationInfo.uid/100000!=USER)throw new IllegalStateException("Paquet absent du profil courant");return p;
    }
    /** Version, installation and signer identity: prevents action on a replaced or reinstalled APK. */
    static String identity(Context c,PackageInfo p)throws Exception{
        JSONObject id=AppIdentity.forPackage(c,p);JSONArray signers=id.getJSONArray("current_signer_sha256");
        if(signers.length()==0)throw new IllegalStateException("Signataire non vérifiable");
        TreeSet<String> names=new TreeSet<>();if(p.requestedPermissions!=null)Collections.addAll(names,p.requestedPermissions);
        return hash(new JSONArray().put(id.getString("app_identity_id")).put(p.applicationInfo.uid)
            .put(Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode).put(p.firstInstallTime).put(p.lastUpdateTime)
            .put(p.applicationInfo.targetSdkVersion).put(new JSONArray(names)).toString());
    }
    static String targetReason(Context c,String pkg){
        if(c.getPackageName().equals(pkg))return "Continuité AIV : une révocation de ses propres droits interromprait l’exécuteur et la vérification";
        if("android".equals(pkg))return "Protection AIV : identité système fondamentale";
        try{return DeveloperControl.permissionTargetReason(c,pkg);}catch(Exception e){return "Identité ou rôle non vérifiable : "+e.getClass().getSimpleName();}
    }
    static Map<String,PermissionControlRules.Grant> shellPermissions(String pkg)throws Exception{
        ControlShell.Result r=ControlShell.run("dumpsys package "+PermissionControlRules.quote(pkg),1024*1024);
        if(r.code!=0||!r.complete||!r.err.trim().isEmpty())throw new IOException("État Shell incomplet ou refusé");
        return PermissionControlRules.runtime(r.out,pkg,USER);
    }
    static JSONObject op(String pkg,String permission){
        String name=PermissionControlRules.appOp(permission);
        try{
            ControlShell.Result r=ControlShell.run("cmd appops get --user "+USER+" "+PermissionControlRules.quote(pkg)+" "+name);
            PermissionControlRules.Op state=PermissionControlRules.appOpState(r.out,name,r.code,r.complete&&r.err.trim().isEmpty());
            return EventStore.object("mode",state.mode,"uid_scope",state.uidScope,"exit",r.code,"complete",r.complete,"stderr",r.err);
        }catch(Exception e){return EventStore.object("mode","","uid_scope",false,"error",e.getMessage());}
    }
    static JSONObject inventory(Context c,String query,int offset)throws Exception{
        String needle=query==null?"":query.trim().toLowerCase(Locale.ROOT);
        PackageManager pm=c.getPackageManager();List<PackageInfo> all=pm.getInstalledPackages(PackageManager.GET_PERMISSIONS|PackageManager.MATCH_DISABLED_COMPONENTS);
        all.sort(Comparator.comparing(p->p.applicationInfo==null?p.packageName:String.valueOf(p.applicationInfo.loadLabel(pm)).toLowerCase(Locale.ROOT)));
        JSONObject norms=profiles(c);JSONArray rows=new JSONArray();int matched=0,total=0;
        for(PackageInfo p:all){
            if(p.applicationInfo==null||p.applicationInfo.uid/100000!=USER)continue;total++;
            String label=String.valueOf(p.applicationInfo.loadLabel(pm)),pkg=p.packageName;
            String profile=prefs(c).getString("profile:"+pkg,"");
            if(!needle.isEmpty()&&!(label+" "+pkg+" "+profile).toLowerCase(Locale.ROOT).contains(needle))continue;
            int index=matched++;if(index<Math.max(0,offset)||rows.length()>=PAGE)continue;
            int granted=0,runtime=0,proposed=0;Set<String> excess=denied(norms,profile);
            if(p.requestedPermissions!=null)for(String name:p.requestedPermissions){
                boolean g=pm.checkPermission(name,pkg)==PackageManager.PERMISSION_GRANTED;if(g)granted++;
                if(excess.contains(name))proposed++;
                try{if(g&&(pm.getPermissionInfo(name,0).protectionLevel&15)==1)runtime++;}catch(Exception ignored){}
            }
            JSONObject evidence=ApkEvidence.get(c).read(pkg,Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode,p.lastUpdateTime);
            JSONArray trackers=evidence.optJSONArray("trackers");JSONObject norm=norms.optJSONObject(profile);
            String[] peers=pm.getPackagesForUid(p.applicationInfo.uid);
            rows.put(EventStore.object("package",pkg,"label",label,"uid",p.applicationInfo.uid,"system",(p.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)!=0,
                "declared",p.requestedPermissions==null?0:p.requestedPermissions.length,"granted",granted,"runtime_granted",runtime,
                "profile",profile,"profile_label",norm==null?"Politique globale":norm.optString("label"),"outside_profile",proposed,
                "tracker_count",trackers==null?0:trackers.length(),"tracker_status",evidence.optString("status","PENDING"),"uid_packages",peers==null?0:peers.length));
        }
        return EventStore.object("rows",rows,"matched",matched,"total",total,"offset",Math.max(0,offset),"limit",PAGE,"user",USER,
            "scope","Toutes les applications visibles du profil courant, y compris préinstallées et désactivées. Dossier sécurisé et autres profils non couverts par cet inventaire.");
    }
    static JSONObject detail(Context c,String pkg,boolean useShell)throws Exception{return detail(c,pkg,useShell,includeProtected(c));}
    private static JSONObject detail(Context c,String pkg,boolean useShell,boolean includeProtected)throws Exception{
        PackageInfo p=info(c,pkg);PackageManager pm=c.getPackageManager();JSONObject all=profiles(c);
        String profile=prefs(c).getString("profile:"+pkg,"");Set<String> excess=denied(all,profile);
        String reason=targetReason(c,pkg),shellError="";
        Map<String,PermissionControlRules.Grant> shell=new HashMap<>();boolean observed=useShell&&authorized();
        if(observed)try{shell=shellPermissions(pkg);}catch(Exception e){shellError=e.getMessage();observed=false;}
        List<String> names=new ArrayList<>();if(p.requestedPermissions!=null)Collections.addAll(names,p.requestedPermissions);Collections.sort(names);
        JSONArray permissions=new JSONArray();int actionable=0,proposed=0;
        for(String name:names){
            int protection=-1;String label=name,description="Description Android indisponible";
            try{PermissionInfo pi=pm.getPermissionInfo(name,0);protection=pi.protectionLevel;label=String.valueOf(pi.loadLabel(pm));CharSequence desc=pi.loadDescription(pm);if(desc!=null)description=desc.toString();}catch(Exception ignored){}
            boolean granted=pm.checkPermission(name,pkg)==PackageManager.PERMISSION_GRANTED;PermissionControlRules.Grant state=shell.get(name);
            String targetBlock=PermissionReviewRules.targetAllowed(reason,includeProtected)?"":reason;
            String blocked=PermissionControlRules.runtimeReason(protection,granted,state,targetBlock),kind="permission";
            JSONObject appOp=null;String opName=PermissionControlRules.appOp(name);
            if(!opName.isEmpty()){
                kind="appop";appOp=observed?op(pkg,name):EventStore.object("mode","","uid_scope",false);
                String mode=appOp.optString("mode");
                blocked=!targetBlock.isEmpty()?targetBlock:mode.isEmpty()?"État AppOp non vérifié : autoriser Shizuku puis actualiser":
                    appOp.optBoolean("uid_scope")?"AppOp appliquée à l’UID : retrait individuel non isolable":
                    ("ignore".equals(mode)||"deny".equals(mode))?"Opération déjà bloquée":"";
            }
            boolean can=blocked.isEmpty()&&PermissionControlRules.name(name);
            if(can)actionable++;boolean suggested=excess.contains(name);if(suggested)proposed++;
            JSONObject bayton=ReferenceCatalog.get(c).permission(name);
            JSONObject row=EventStore.object("name",name,"label",label,"description",description,"protection_level",protection,
                "protection",protection<0?"Inconnue":(protection&15)==1?"Runtime":(protection&15)==0?"Normale":(protection&15)==2?"Signature":"Interne / autre",
                "granted",granted,"shell_granted",state==null?JSONObject.NULL:state.granted,"flags",state==null?JSONObject.NULL:state.flags,
                "kind",kind,"can_revoke",can,"blocked_reason",blocked,"block_origin",PermissionReviewRules.origin(blocked,state),"outside_profile",suggested,
                "review_group",PermissionReviewRules.group(name),"review_group_label",PermissionReviewRules.label(PermissionReviewRules.group(name)),
                "assessment",suggested?"Hors de l’usage choisi — retrait à examiner":profile.isEmpty()?"Politique globale : "+PermissionReviewRules.label(PermissionReviewRules.group(name)):"Non exclue par cet usage",
                "bayton",bayton==null?JSONObject.NULL:bayton,"appop",appOp==null?JSONObject.NULL:appOp,
                "command",can?PermissionControlRules.command(pkg,USER,kind,name,"appop".equals(kind)?"ignore":"revoke"):JSONObject.NULL);
            row.put("state_key",stateKey(row));permissions.put(row);
        }
        JSONObject evidence=ApkEvidence.get(c).read(pkg,Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode,p.lastUpdateTime);
        return EventStore.object("schema","aiv-permission-detail/1","package",pkg,"label",String.valueOf(p.applicationInfo.loadLabel(pm)),"user",USER,"uid",p.applicationInfo.uid,
            "identity",identity(c,p),"system",(p.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)!=0,"permissions",permissions,"can_revoke",actionable,
            "outside_profile",proposed,"profile",profile,"profile_label",all.has(profile)?all.getJSONObject(profile).optString("label"):"Politique globale",
            "target_reason",reason,"include_protected",includeProtected,"shell_observed",observed,"shell_error",shellError,"apk_evidence",evidence,"catalogs",ReferenceCatalog.get(c).summary(),
            "scope","Bayton décrit les permissions; Exodus relève des signatures de traqueurs. Aucun de ces catalogues ne prouve à lui seul qu’une permission est superflue.");
    }
    private static String stateKey(JSONObject row){
        if("appop".equals(row.optString("kind"))){JSONObject o=row.optJSONObject("appop");return "appop|"+(o==null?"":o.optString("mode"))+"|"+(o!=null&&o.optBoolean("uid_scope"));}
        return "permission|"+row.optBoolean("granted")+"|"+(row.isNull("flags")?"unknown":row.optString("flags"));
    }
    private static JSONObject find(JSONObject detail,String name)throws Exception{
        JSONArray rows=detail.getJSONArray("permissions");for(int i=0;i<rows.length();i++)if(name.equals(rows.getJSONObject(i).getString("name")))return rows.getJSONObject(i);
        throw new IllegalStateException("Permission absente du manifeste actuel");
    }
    /** Requests contain exact names explicitly selected in the native table. */
    static synchronized JSONObject preview(Context c,JSONArray requests)throws Exception{
        access();if(running()||requests.length()>AivConfig.CONTROL_BATCH_MAX_APPS)throw new IllegalStateException("Une intervention est en cours ou le lot dépasse 50 applications");
        JSONObject identities=new JSONObject(),observations=new JSONObject();JSONArray changes=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<requests.length();i++){
            JSONObject request=requests.getJSONObject(i);String pkg=request.getString("package");JSONArray selected=request.getJSONArray("permissions");
            if(selected.length()>MAX_CHANGES)throw new IllegalArgumentException("Lot limité à 500 droits");
            JSONObject d=detail(c,pkg,true);identities.put(pkg,d.getString("identity"));
            JSONObject grants=new JSONObject();JSONArray observed=d.getJSONArray("permissions");
            for(int j=0;j<observed.length();j++){JSONObject row=observed.getJSONObject(j);if((row.optInt("protection_level",-1)&15)==1&&!row.isNull("flags"))grants.put(row.getString("name"),EventStore.object("granted",row.getBoolean("granted"),"flags",row.getString("flags")));}
            observations.put(pkg,grants);
            for(int j=0;j<selected.length();j++){
                String name=selected.getString(j);if(!seen.add(pkg+"|"+name))continue;JSONObject row=find(d,name);
                if(!row.getBoolean("can_revoke"))throw new IllegalStateException(name+" : "+row.getString("blocked_reason"));
                changes.put(EventStore.object("package",pkg,"label",d.getString("label"),"name",name,"kind",row.getString("kind"),"before",row,
                    "identity",d.getString("identity"),"target_guard",d.getString("target_reason"),"include_protected",d.optBoolean("include_protected"),"command",row.getString("command"),"reason",row.getString("assessment")));
                if(changes.length()>MAX_CHANGES)throw new IllegalArgumentException("Lot limité à 500 droits");
            }
        }
        // Reverse restoration grants foreground location before background location.
        List<JSONObject> ordered=new ArrayList<>();for(int i=0;i<changes.length();i++)ordered.add(changes.getJSONObject(i));
        ordered.sort(Comparator.comparing((JSONObject row)->row.optString("package")).thenComparingInt(row->PermissionControlRules.revokeOrder(row.optString("name"))).thenComparing(row->row.optString("name")));
        JSONObject draft=EventStore.object("schema","aiv-permission-plan/1","id",UUID.randomUUID().toString(),"created_ms",System.currentTimeMillis(),
            "user",USER,"server_uid",Shizuku.getUid(),"identities",identities,"observations_before",observations,"changes",new JSONArray(ordered),"phase","preview");
        draft.put("stamp",hash(draft.toString()));write(c,"draft.json",draft);return draft;
    }
    static JSONObject previewProfiles(Context c)throws Exception{
        access();JSONArray requests=new JSONArray();
        for(String key:new TreeSet<>(prefs(c).getAll().keySet()))if(key.startsWith("profile:")&&!prefs(c).getString(key,"").isEmpty()){
            String pkg=key.substring(8);JSONObject d;
            try{d=detail(c,pkg,true);}catch(PackageManager.NameNotFoundException e){continue;}
            JSONArray selected=new JSONArray(),rows=d.getJSONArray("permissions");
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.optBoolean("outside_profile")&&row.optBoolean("can_revoke"))selected.put(row.getString("name"));}
            if(selected.length()>0)requests.put(EventStore.object("package",pkg,"permissions",selected));
            if(requests.length()>AivConfig.CONTROL_BATCH_MAX_APPS)throw new IllegalStateException("Plus de 50 applications : appliquer les usages par lots");
        }
        return preview(c,requests);
    }
    private static void saveReport(Context c,JSONObject report)throws Exception{
        String id=report.getString("id");if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Rapport invalide");
        write(c,id+".json",report);
        if(!prefs(c).edit().putString("last_report",id).commit())throw new IOException("Référence du rapport non enregistrée");
    }
    static JSONObject lastReport(Context c)throws Exception{
        String id=prefs(c).getString("last_report","");return id.isEmpty()?EventStore.object("phase","none","status",status):read(c,id+".json").put("status",status);
    }
    static JSONObject reportPage(Context c,int offset)throws Exception{
        JSONObject report=lastReport(c);List<JSONObject> receipts=new ArrayList<>();JSONArray entries=report.optJSONArray("entries");
        if(entries!=null)for(int i=0;i<entries.length();i++)receipts.add(entries.getJSONObject(i));
        JSONArray children=report.optJSONArray("reports"),restored=report.optJSONArray("batches");
        if(children==null&&"aiv-permission-restore/1".equals(report.optString("schema"))&&restored!=null){children=new JSONArray();for(int i=0;i<restored.length();i++)children.put(restored.getJSONObject(i).getString("id"));}
        if(children!=null)for(int i=0;i<children.length();i++){
            String id=children.getString(i);if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Référence de rapport invalide");
            JSONArray rows=read(c,id+".json").optJSONArray("entries");if(rows!=null)for(int j=0;j<rows.length();j++)receipts.add(rows.getJSONObject(j));
        }
        JSONArray page=new JSONArray();for(int i=Math.max(0,offset);i<Math.min(receipts.size(),Math.max(0,offset)+PAGE);i++)page.put(receipts.get(i));
        return EventStore.object("report",report,"rows",page,"total",receipts.size(),"offset",Math.max(0,offset),"limit",PAGE);
    }
    static synchronized JSONObject apply(Context context,String stamp)throws Exception{
        access();if(!ControlCoordinator.acquire())throw new IllegalStateException("Une intervention est déjà en cours");
        Context c=context.getApplicationContext();
        try{
            JSONObject draft=read(c,"draft.json");
            if(draft.optBoolean("used")||!draft.getString("stamp").equals(stamp)||!PermissionControlRules.fresh(System.currentTimeMillis(),draft.getLong("created_ms"),AivConfig.CONTROL_PREVIEW_VALID_MS))
                throw new IllegalStateException("Plan expiré ou déjà utilisé : refaire l’aperçu");
            if(draft.getJSONArray("changes").length()==0)throw new IllegalStateException("Aucun droit admissible sélectionné");
            // Recheck every selected state before any mutation, including the exact profile and flags.
            Map<String,JSONObject> live=new HashMap<>();
            JSONArray changes=draft.getJSONArray("changes");
            for(int i=0;i<changes.length();i++){
                JSONObject change=changes.getJSONObject(i);String pkg=change.getString("package");
                if(!live.containsKey(pkg))live.put(pkg,detail(c,pkg,true));JSONObject d=live.get(pkg),row=find(d,change.getString("name"));
                if(!change.getString("identity").equals(d.getString("identity"))||!row.optBoolean("can_revoke")||!row.getString("state_key").equals(change.getJSONObject("before").getString("state_key")))
                    throw new IllegalStateException("État modifié depuis le plan : refaire l’aperçu");
            }
            draft.put("entries",new JSONArray()).put("phase","queued");saveReport(c,draft);
            write(c,"draft.json",new JSONObject(draft.toString()).put("used",true));
            if(!prefs(c).edit().putString("last_apply_report",draft.getString("id")).commit())throw new IOException("Référence de restauration non enregistrée");
            stop.set(false);running=true;status="Retrait Shell en cours";
            new Thread(()->{
                try{run(c,draft);PermissionAudit.get(c).scan();}catch(Exception e){interrupt(c,draft,e);}finally{running=false;ControlCoordinator.release();}
            },"aiv-permissions-apply").start();
            return EventStore.object("report_id",draft.getString("id"),"status",status);
        }catch(Exception e){ControlCoordinator.release();throw e;}
    }
    private static JSONObject readState(Context c,String pkg,String name,String kind)throws Exception{
        if("appop".equals(kind))return EventStore.object("name",name,"kind",kind,"appop",op(pkg,name));
        Map<String,PermissionControlRules.Grant> permissions=shellPermissions(pkg);PermissionControlRules.Grant state=permissions.get(name);
        if(state==null)throw new IOException("Permission absente du relevé runtime Shell");
        boolean granted=c.getPackageManager().checkPermission(name,pkg)==PackageManager.PERMISSION_GRANTED;
        if(state.granted!=granted)throw new IOException("État Android/Shell différent");
        return EventStore.object("name",name,"kind",kind,"granted",granted,"flags",state.flags);
    }
    private static void run(Context c,JSONObject report)throws Exception{
        JSONArray changes=report.getJSONArray("changes");int changed=0,failed=0;
        report.put("phase","applying");saveReport(c,report);
        for(int i=0;i<changes.length();i++){
            if(stop.get())throw new InterruptedIOException("Arrêt demandé; retraits confirmés conservés dans le rapport");
            access();JSONObject planned=changes.getJSONObject(i);String pkg=planned.getString("package"),name=planned.getString("name"),kind=planned.getString("kind");
            status=batchPrefix+"Retrait "+(i+1)+"/"+changes.length()+" · "+planned.getString("label");
            JSONObject entry=new JSONObject(planned.toString()).put("outcome","pending").put("started_ms",System.currentTimeMillis());
            report.getJSONArray("entries").put(entry);saveReport(c,report); // Receipt exists before issuing Shell.
            try{
                if(!planned.getString("identity").equals(identity(c,info(c,pkg)))||!sameTarget(c,pkg,planned))throw new IllegalStateException("Identité, rôle ou portée UID modifié");
                JSONObject before=readState(c,pkg,name,kind);
                if(!stateKey(before).equals(planned.getJSONObject("before").getString("state_key")))throw new IllegalStateException("Droit modifié depuis l’aperçu");
                String command=PermissionControlRules.command(pkg,USER,kind,name,"appop".equals(kind)?"ignore":"revoke");
                entry.put("command",command).put("inverse",PermissionControlRules.command(pkg,USER,kind,name,"appop".equals(kind)?before.getJSONObject("appop").getString("mode"):"grant"));
                saveReport(c,report);
                ControlShell.Result result=ControlShell.run(command);
                entry.put("exit",result.code).put("stdout",result.out).put("stderr",result.err);
                if(!planned.getString("identity").equals(identity(c,info(c,pkg))))throw new IOException("APK modifiée pendant la commande : résultat non confirmé");
                JSONObject after=readState(c,pkg,name,kind);
                boolean observed="appop".equals(kind)?"ignore".equals(after.getJSONObject("appop").optString("mode"))&&!after.getJSONObject("appop").optBoolean("uid_scope"):!after.getBoolean("granted");
                String outcome=PermissionControlRules.outcome(result.code,observed&&result.complete);
                entry.put("exit",result.code).put("stdout",result.out).put("stderr",result.err).put("after",after).put("after_key",stateKey(after)).put("outcome",outcome);
                if("confirmed".equals(outcome))changed++;else failed++;
                if("confirmed".equals(outcome))PermissionMaintenance.remember(c,planned,after);
            }catch(Exception e){failed++;entry.put("outcome","unverified").put("error",String.valueOf(e.getMessage()));}
            entry.put("finished_ms",System.currentTimeMillis());report.put("changed",changed).put("failed",failed);saveReport(c,report);log(c,"PERMISSION_CONTROL",entry);
        }
        observeCollateral(c,report);
        report.put("phase","finished").put("finished_ms",System.currentTimeMillis());saveReport(c,report);
        status=changed+" retrait(s) confirmé(s) · "+failed+" refus/état(s) non confirmé(s)";
        if(report.getJSONArray("additional_changes").length()>0)status+=" · "+report.getJSONArray("additional_changes").length()+" changement(s) annexe(s) à examiner";
    }
    /** Report collateral changes without inventing attribution or silently granting extra rights. */
    private static void observeCollateral(Context c,JSONObject report)throws Exception{
        JSONObject observations=report.getJSONObject("observations_before");JSONArray extra=new JSONArray();Set<String> selected=new HashSet<>();
        JSONArray changes=report.getJSONArray("changes");for(int i=0;i<changes.length();i++){JSONObject row=changes.getJSONObject(i);selected.add(row.getString("package")+"|"+row.getString("name"));}
        JSONArray errors=new JSONArray();
        for(Iterator<String> keys=observations.keys();keys.hasNext();){String pkg=keys.next();
            try{
                if(!report.getJSONObject("identities").getString(pkg).equals(identity(c,info(c,pkg))))throw new IOException("Identité modifiée");
                Map<String,PermissionControlRules.Grant> now=shellPermissions(pkg);JSONObject before=observations.getJSONObject(pkg);
                for(Iterator<String> names=before.keys();names.hasNext();){String name=names.next();PermissionControlRules.Grant state=now.get(name);JSONObject old=before.getJSONObject(name);
                    if(!selected.contains(pkg+"|"+name)&&state!=null&&(old.getBoolean("granted")!=state.granted||!old.getString("flags").equals(state.flags)))
                        extra.put(EventStore.object("package",pkg,"name",name,"before",old,"after",EventStore.object("granted",state.granted,"flags",state.flags),"scope","Différence observée autour du lot; dépendance Android ou autre auteur non déterminé. Non restaurée automatiquement."));
                }
            }catch(Exception e){errors.put(EventStore.object("package",pkg,"error",e.getMessage()));}
        }
        report.put("additional_changes",extra).put("additional_observation_errors",errors);
    }
    private static void interrupt(Context c,JSONObject report,Exception e){
        status="Intervention interrompue : "+e.getMessage();try{report.put("phase","interrupted").put("error",e.toString());saveReport(c,report);}catch(Exception ignored){}
    }
    static synchronized JSONObject restore(Context context)throws Exception{
        access();if(!AccessPolicy.allows("shizuku.restore",ProductAccess.verifiedTier()))throw new SecurityException("Restauration indisponible");
        if(!ControlCoordinator.acquire())throw new IllegalStateException("Une intervention est déjà en cours");Context c=context.getApplicationContext();
        try{
            String id=prefs(c).getString("last_apply_report","");if(id.isEmpty())throw new IllegalStateException("Aucun retrait à restaurer");
            JSONObject original=read(c,id+".json");
            if(Arrays.asList("queued","applying").contains(original.optString("phase"))){
                original.put("phase","interrupted").put("recovery","Processus précédent arrêté; seules les entrées confirmées seront restaurées");saveReport(c,original);
            }
            if(!Arrays.asList("finished","interrupted").contains(original.optString("phase")))throw new IllegalStateException("Intervention non terminée : état à vérifier");
            JSONObject report=EventStore.object("schema","aiv-permission-restore/1","id",UUID.randomUUID().toString(),"source_report",id,"created_ms",System.currentTimeMillis(),"phase","restoring","entries",new JSONArray());saveReport(c,report);
            stop.set(false);running=true;status="Restauration des droits en cours";
            new Thread(()->{try{if("aiv-permission-queue/1".equals(original.optString("schema")))restoreQueue(c,original,report);else restoreRun(c,original,report);PermissionAudit.get(c).scan();}catch(Exception e){interrupt(c,report,e);}finally{running=false;batchPrefix="";ControlCoordinator.release();}},"aiv-permissions-restore").start();
            return EventStore.object("report_id",report.getString("id"),"status",status);
        }catch(Exception e){ControlCoordinator.release();throw e;}
    }
    private static void restoreRun(Context c,JSONObject original,JSONObject report)throws Exception{
        JSONArray entries=original.getJSONArray("entries");int restored=0,skipped=0,failed=0;
        for(int i=entries.length()-1;i>=0;i--){
            if(stop.get())throw new InterruptedIOException("Restauration arrêtée; résultats conservés");
            JSONObject old=entries.getJSONObject(i);if(!"confirmed".equals(old.optString("outcome"))){skipped++;continue;}
            String pkg=old.getString("package"),name=old.getString("name"),kind=old.getString("kind");
            JSONObject receipt=EventStore.object("package",pkg,"name",name,"kind",kind,"outcome","pending");report.getJSONArray("entries").put(receipt);saveReport(c,report);
            try{
                access();JSONObject now=readState(c,pkg,name,kind);
                boolean same=old.getString("identity").equals(identity(c,info(c,pkg)))&&sameTarget(c,pkg,old);
                if(same&&stateKey(now).equals(old.getJSONObject("before").getString("state_key"))){PermissionMaintenance.forget(c,pkg,name);receipt.put("outcome","already_restored");skipped++;saveReport(c,report);continue;}
                if(!PermissionControlRules.restoreAllowed(old.getString("outcome"),same,stateKey(now),old.getString("after_key")))throw new IllegalStateException("APK, rôle ou droit modifié depuis le retrait : restauration refusée");
                PermissionMaintenance.forget(c,pkg,name);
                String desired="appop".equals(kind)?old.getJSONObject("before").getJSONObject("appop").getString("mode"):"grant";
                String command=PermissionControlRules.command(pkg,USER,kind,name,desired);receipt.put("command",command).put("before",now);saveReport(c,report);
                ControlShell.Result result=ControlShell.run(command);JSONObject after=readState(c,pkg,name,kind);
                if("permission".equals(kind)&&result.code==0&&after.optBoolean("granted")){
                    String was=old.getJSONObject("before").getString("flags"),is=after.getString("flags");
                    for(String flag:new String[]{"USER_SET","USER_FIXED"})if(PermissionControlRules.contains(was,flag)!=PermissionControlRules.contains(is,flag)){
                        String change=PermissionControlRules.contains(was,flag)?"set":"clear";
                        ControlShell.Result flags=ControlShell.run("pm "+change+"-permission-flags --user "+USER+" "+PermissionControlRules.quote(pkg)+" "+PermissionControlRules.quote(name)+" "+flag.toLowerCase(Locale.ROOT).replace('_','-'));
                        if(flags.code!=0||!flags.complete)throw new IOException("Droit réaccordé; flags non restaurés : "+flags.err);
                    }
                    after=readState(c,pkg,name,kind);
                }
                boolean verified=stateKey(after).equals(old.getJSONObject("before").getString("state_key"));
                receipt.put("after",after).put("exit",result.code).put("stderr",result.err).put("outcome",PermissionControlRules.outcome(result.code,verified&&result.complete));
                if(result.code==0&&verified&&result.complete)restored++;else failed++;
            }catch(Exception e){failed++;receipt.put("outcome","refused_or_unverified").put("error",String.valueOf(e.getMessage()));}
            report.put("restored",restored).put("skipped",skipped).put("failed",failed);saveReport(c,report);log(c,"PERMISSION_RESTORE",receipt);
        }
        report.put("phase","finished").put("finished_ms",System.currentTimeMillis()).put("restored",restored).put("skipped",skipped).put("failed",failed);saveReport(c,report);
        status=restored+" droit(s) restauré(s) · "+failed+" refus/état(s) non confirmé(s)";
    }
    static void export(Context c,Writer out)throws Exception{
        out.write("{\"schema\":\"aiv-permission-control-export/1\",\"android_api\":"+Build.VERSION.SDK_INT+",\"user\":"+USER+",\"catalogs\":"+ReferenceCatalog.get(c).summary()+",\"applications\":[");
        List<PackageInfo> applications=c.getPackageManager().getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS);applications.sort(Comparator.comparing(p->p.packageName));boolean first=true;
        for(PackageInfo p:applications){
            if(p.applicationInfo==null||p.applicationInfo.uid/100000!=USER)continue;
            JSONObject app;
            try{app=detail(c,p.packageName,false);}catch(Exception e){app=EventStore.object("package",p.packageName,"error",e.getMessage());}
            if(!first)out.write(',');out.write(app.toString());first=false;
        }
        JSONObject analysis=review(c);out.write("],\"analysis\":"+analysis+",\"analysis_snapshots\":[");first=true;
        JSONArray reviewed=analysis.optJSONArray("applications");if(reviewed!=null)for(int i=0;i<reviewed.length();i++){
            JSONObject row=reviewed.getJSONObject(i);if(row.has("error"))continue;String pkg=row.getString("package");if(!PermissionControlRules.packageName(pkg))continue;
            JSONObject snapshot=read(c,"review-"+pkg+".json");if(!analysis.optString("id").equals(snapshot.optString("review_id")))continue;if(!first)out.write(',');out.write(snapshot.toString());first=false;
        }
        out.write("],\"reports\":[");first=true;File[] files=dir(c).listFiles();
        if(files!=null){Arrays.sort(files,Comparator.comparing(File::getName));for(File f:files)if(f.getName().matches("[a-f0-9-]{36}\\.json")){if(!first)out.write(',');out.write(read(c,f.getName()).toString());first=false;}}
        out.write("],\"maintenance\":"+PermissionMaintenance.exportState(c)+"}");
    }
    private static void log(Context c,String kind,JSONObject value){try{EventStore.get(c).add("aiv-permissions","Shizuku",kind,"Retrait de droits","Interne","État Shell vérifié",value);}catch(Exception ignored){}}
}
