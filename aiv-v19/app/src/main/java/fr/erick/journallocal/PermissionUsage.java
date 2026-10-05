package fr.erick.journallocal;

import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only, bounded AppOps observations. No permission is granted/revoked here. */
final class PermissionUsage extends SQLiteOpenHelper {
    private static PermissionUsage instance;
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private static long requestedAt;private static int packageOffset;
    private final Context context;
    static final long INTERVAL_MS=60000;
    static final String SCOPE="Relevé des derniers accès et refus signalés par Android, environ chaque minute. Des accès entre deux relevés peuvent être regroupés ou absents. Ni le contenu lu, ni son envoi, ni tous les échanges entre applications ne sont établis.";
    static synchronized PermissionUsage get(Context c){if(instance==null)instance=new PermissionUsage(c.getApplicationContext());return instance;}
    private PermissionUsage(Context c){super(c,"permission-usage.sqlite",null,1);context=c;setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE observations(token TEXT PRIMARY KEY,pkg TEXT NOT NULL,uid INTEGER NOT NULL,identity TEXT NOT NULL,installed_ms INTEGER NOT NULL,at_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,captured_ms INTEGER NOT NULL,payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX observations_actor_time ON observations(pkg,identity,uid,installed_ms,end_ms,at_ms)");
        db.execSQL("CREATE TABLE seen(dimension TEXT PRIMARY KEY,at_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE state(id INTEGER PRIMARY KEY,payload TEXT NOT NULL)");
        ContentValues v=new ContentValues();v.put("id",1);v.put("payload",EventStore.object("started_ms",System.currentTimeMillis(),"state","EN_ATTENTE","scope",SCOPE).toString());db.insertOrThrow("state",null,v);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int a,int b){throw new IllegalStateException("Migration des observations requise");}
    static synchronized void request(Context c){
        long now=SystemClock.elapsedRealtime();if(now-requestedAt<INTERVAL_MS&&requestedAt>0)return;
        if(!BUSY.compareAndSet(false,true))return;requestedAt=now;
        Context app=c.getApplicationContext();new Thread(()->{try{get(app).sample();}catch(Exception e){try{get(app).setStatus("INDISPONIBLE",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()),0,0);}catch(Exception ignored){}}finally{BUSY.set(false);}},"aiv-permission-usage").start();
    }
    static synchronized void requestPriority(Context c){
        long now=SystemClock.elapsedRealtime();if(now-requestedAt<5000&&requestedAt>0)return;
        requestedAt=0;request(c);
    }
    synchronized JSONObject status(){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM state WHERE id=1",null)){if(c.moveToFirst())return new JSONObject(c.getString(0));}
        catch(Exception ignored){}return EventStore.object("state","INDISPONIBLE","scope",SCOPE);
    }
    private synchronized void setStatus(String state,String error,long success,int skipped)throws Exception{
        JSONObject previous=status(),s=EventStore.object("state",state,"error",error,"last_error",error,"scope",SCOPE,"poll_interval_ms",INTERVAL_MS,"updated_ms",System.currentTimeMillis(),"started_ms",previous.optLong("started_ms",System.currentTimeMillis()),"last_success_ms",success>0?success:previous.optLong("last_success_ms"),"skipped_packages",skipped,"source","Android AppOps via Shizuku (lecture seule)");
        for(String key:new String[]{"last_attempt_ms","packages_attempted","packages_success","packages_failed","ops_observed","raw_unparsed"})s.put(key,previous.opt(key)==null?JSONObject.NULL:previous.opt(key));
        s.put("last_success_elapsed_ms",success>0?SystemClock.elapsedRealtime():previous.optLong("last_success_elapsed_ms")).put("last_success_clock_scope_id",success>0?EventStore.clockScope():previous.optString("last_success_clock_scope_id"));
        ContentValues v=new ContentValues();v.put("id",1);v.put("payload",s.toString());getWritableDatabase().insertWithOnConflict("state",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        if(!state.equals(previous.optString("state"))&&RecorderService.running)
            EventStore.get(context).add("INDISPONIBLE".equals(state)||"FORMAT_NON_RECONNU".equals(state)?"sensor-health":"acces-etat","All In Visible","Observation des accès · "+state,"Permissions utilisées","Interne","État du relevé AppOps",s);
    }
    private void sample()throws Exception{
        if(!RecorderService.running)return;
        long start=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();TimeZone zone=TimeZone.getDefault();
        metrics(start,0,0,0,0,new JSONArray());
        ControlShell.Result result=AppOpsCollector.readAll();
        long captured=System.currentTimeMillis(),capturedElapsed=SystemClock.elapsedRealtime();
        if(!RecorderService.running)return;
        if(result.code!=0||!result.complete||!result.err.trim().isEmpty()){
            if(samplePackages())return;
            setStatus("INDISPONIBLE","Relevé global refusé ou incomplet et lectures par paquet indisponibles; aucun accès déduit.",0,0);return;
        }
        if(!zone.equals(TimeZone.getDefault())||Math.abs((captured-start)-(capturedElapsed-elapsed))>2000){setStatus("INDISPONIBLE","Changement d’horloge pendant le relevé",0,0);return;}
        PermissionUsageRules.Snapshot snapshot=PermissionUsageRules.parse(result.out,captured,zone);
        if(!snapshot.recognized){if(samplePackages())return;setStatus("FORMAT_NON_RECONNU","Le téléphone ne fournit pas le format AppOps attendu",0,0);return;}
        long baseline=status().optLong("started_ms",captured);
        LinkedHashMap<String,List<PermissionUsageRules.Entry>> packages=new LinkedHashMap<>();
        int user=android.os.Process.myUid()/100000;
        for(PermissionUsageRules.Entry e:snapshot.entries){if(e.uid/100000==user)packages.computeIfAbsent(e.pkg,k->new ArrayList<>()).add(e);}
        List<String> names=new ArrayList<>(packages.keySet());int processed=0,unverified=0;
        long deadline=SystemClock.elapsedRealtime()+6000;
        int offset=names.isEmpty()?0:packageOffset%names.size();
        for(int i=0;i<names.size()&&processed<128&&SystemClock.elapsedRealtime()<deadline;i++){
            if(!RecorderService.running)return;String pkg=names.get((offset+i)%names.size());processed++;
            try{
                int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
                PackageInfo info=context.getPackageManager().getPackageInfo(pkg,flags);
                JSONObject identity=AppIdentity.forPackage(context,info);
                JSONArray certs=identity.optJSONArray("current_signer_sha256");
                if(certs==null||certs.length()==0||info.firstInstallTime<=0){unverified++;continue;}
                String actor=context.getPackageManager().getApplicationLabel(info.applicationInfo).toString();
                String[] peers=context.getPackageManager().getPackagesForUid(info.applicationInfo.uid);
                for(PermissionUsageRules.Entry e:packages.get(pkg)){
                    if(e.uid!=info.applicationInfo.uid||e.at<info.firstInstallTime||e.end<e.at)continue;
                    remember(e,identity,info.firstInstallTime,actor,peers,captured,capturedElapsed,baseline);
                }
            }catch(PackageManager.NameNotFoundException e){unverified++;}
        }
        if(!names.isEmpty())packageOffset=(offset+processed)%names.size();
        int skipped=names.size()-processed+unverified;
        metrics(start,processed,processed-unverified,unverified,snapshot.entries.size(),new JSONArray(snapshot.unknown));
        setStatus(snapshot.malformed>0||skipped>0?"PARTIEL":"DISPONIBLE",snapshot.malformed>0?"Certaines observations n’ont pas un format reconnu":"",captured,skipped);
    }
    private synchronized void metrics(long attempted,int packages,int success,int failed,int ops,JSONArray raw)throws Exception{
        JSONObject s=status();s.put("last_attempt_ms",attempted).put("packages_attempted",packages).put("packages_success",success).put("packages_failed",failed).put("ops_observed",ops).put("raw_unparsed",raw);
        ContentValues v=new ContentValues();v.put("id",1);v.put("payload",s.toString());getWritableDatabase().insertWithOnConflict("state",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private boolean samplePackages()throws Exception{
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        List<PackageInfo> all=context.getPackageManager().getInstalledPackages(flags);LinkedHashMap<String,PackageInfo> selected=new LinkedHashMap<>();
        String foreground=ScreenIntegrityService.state().optString("observed_package");
        if(!foreground.isEmpty())try{selected.put(foreground,context.getPackageManager().getPackageInfo(foreground,flags));}catch(PackageManager.NameNotFoundException ignored){}
        int user=android.os.Process.myUid()/100000,offset=all.isEmpty()?0:packageOffset%all.size();
        for(int i=0;i<all.size()&&selected.size()<8;i++){PackageInfo p=all.get((offset+i)%all.size());if(p.applicationInfo!=null&&p.applicationInfo.uid/100000==user)selected.put(p.packageName,p);}
        if(!all.isEmpty())packageOffset=(offset+8)%all.size();
        int success=0,failed=0,ops=0,attempted=0;long last=0,deadline=SystemClock.elapsedRealtime()+10000;JSONArray unknown=new JSONArray();String error="";
        for(PackageInfo p:selected.values()){
            if(!RecorderService.running||SystemClock.elapsedRealtime()>deadline)break;attempted++;
            try{
                long readElapsed=SystemClock.elapsedRealtime(),readWall=System.currentTimeMillis();ControlShell.Result r=AppOpsCollector.readPackage(p.packageName,user);long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
                if(Math.abs((wall-readWall)-(elapsed-readElapsed))>2000){failed++;error="Changement d’horloge pendant la lecture";continue;}
                if(!AppOpsCollector.complete(r)){failed++;error="Lecture refusée ou incomplète : "+p.packageName;continue;}
                PermissionUsageRules.Snapshot s=PermissionUsageRules.parsePackage(r.out,p.packageName,p.applicationInfo.uid,wall);
                if(!s.recognized){failed++;error="Format non reconnu : "+p.packageName;continue;}
                JSONObject identity=AppIdentity.forPackage(context,p);JSONArray certs=identity.optJSONArray("current_signer_sha256");
                if(certs==null||certs.length()==0||p.firstInstallTime<=0){failed++;continue;}
                success++;last=wall;ops+=s.entries.size();for(String line:s.unknown)if(unknown.length()<8)unknown.put(line);
                String actor=context.getPackageManager().getApplicationLabel(p.applicationInfo).toString();String[] peers=context.getPackageManager().getPackagesForUid(p.applicationInfo.uid);
                for(PermissionUsageRules.Entry e:s.entries)if(e.at>=p.firstInstallTime){e.uncertaintyMs=Math.max(0,elapsed-readElapsed);remember(e,identity,p.firstInstallTime,actor,peers,wall,elapsed,status().optLong("started_ms",wall));}
            }catch(Exception failure){failed++;error=failure.getClass().getSimpleName()+" : "+p.packageName;}
        }
        metrics(status().optLong("last_attempt_ms"),attempted,success,failed,ops,unknown);
        if(success==0)return false;
        setStatus(failed>0||attempted<selected.size()||unknown.length()>0?"PARTIEL":"DISPONIBLE",error,last,failed+selected.size()-attempted);return true;
    }
    private synchronized void remember(PermissionUsageRules.Entry e,JSONObject identity,long installed,String actor,String[] peers,long captured,long elapsed,long baseline)throws Exception{
        SQLiteDatabase db=getWritableDatabase();String owner=identity.getString("app_identity_id")+":"+installed;String dimension=owner+"|"+e.dimension();long oldAt=0,oldEnd=0;
        try(Cursor c=db.rawQuery("SELECT at_ms,end_ms FROM seen WHERE dimension=?",new String[]{dimension})){if(c.moveToFirst()){oldAt=c.getLong(0);oldEnd=c.getLong(1);}}
        if(e.at<=oldAt&&e.end<=oldEnd)return;
        if(PermissionUsageRules.fresh(e,oldAt,oldEnd,baseline)){
            PermissionUsageRules.Operation op=PermissionUsageRules.OPERATIONS.get(e.op);
            String token=java.util.UUID.randomUUID().toString();
            JSONObject observation=EventStore.object("schema","aiv-permission-usage/1","usage_observation_id",token,"uid",e.uid,"packages",new JSONArray().put(e.pkg),"package_name",e.pkg,"app_identity",identity,"first_install_ms",installed,"uid_packages",peers==null?new JSONArray():new JSONArray(Arrays.asList(peers)),"operation",e.op,"permission",op.permission,"label",op.label,"access_result",e.kind,"access_ms",e.at,"access_end_ms",e.end,"running",e.running,"captured_ms",captured,"attribution_tag",e.tag.isEmpty()||"null".equals(e.tag)?JSONObject.NULL:e.tag,"appops_key",e.key,"source","Android AppOps / "+e.source+" (Shizuku, lecture seule)","scope",SCOPE,"content_observed",false,"sent_content_observed",false);
            long ageAt=captured-(e.running?e.runningSince:e.at),ageEnd=captured-e.end;
            observation.put("appop_mode",e.mode.isEmpty()?JSONObject.NULL:e.mode).put("activity_status","REJECT".equals(e.kind)?"OBSERVED_REJECTED":PermissionUsageRules.activity(e.running,true)).put("duration_ms",e.durationMs>=0?e.durationMs:JSONObject.NULL).put("clock_scope_id",EventStore.clockScope()).put("captured_elapsed_ms",elapsed).put("access_elapsed_ms",ageAt>=0&&ageAt<=elapsed?elapsed-ageAt:JSONObject.NULL).put("end_elapsed_ms",ageEnd>=0&&ageEnd<=elapsed?elapsed-ageEnd:JSONObject.NULL).put("time_precision","AppOps wall time converted at observation; running interval reported by Android");
            observation.put("time_uncertainty_ms",e.uncertaintyMs).put("permission_granted",op.permission.isEmpty()?JSONObject.NULL:context.getPackageManager().checkPermission(op.permission,e.pkg)==PackageManager.PERMISSION_GRANTED).put("permission_status","CAPABILITY_ONLY");
            if(e.running)observation.put("running_start_estimate_ms",e.runningSince);
            if(e.proxyUid>=0)observation.put("proxy",EventStore.object("uid",e.proxyUid,"package_name",e.proxyPackage,"scope","Intermédiaire déclaré pour cet accès AppOps; aucune chaîne complète de communications démontrée."));
            long at=e.running?captured:e.at,age=captured-at;
            String action=("REJECT".equals(e.kind)?"Accès refusé · ":e.running?"Accès en cours signalé · ":"Accès signalé · ")+op.label;
            if(!EventStore.get(context).addObserved("acces",actor,action,op.permission.isEmpty()?e.op:op.permission,"Interne","Android AppOps (lecture seule)",observation,at,age>=0&&age<=elapsed?elapsed-age:0))throw new IllegalStateException("Journal indisponible");
            ContentValues v=new ContentValues();v.put("token",token);v.put("pkg",e.pkg);v.put("uid",e.uid);v.put("identity",identity.getString("app_identity_id"));v.put("installed_ms",installed);v.put("at_ms",e.running?e.runningSince:e.at);v.put("end_ms",e.end);v.put("captured_ms",captured);v.put("payload",observation.toString());db.insertOrThrow("observations",null,v);
        }
        ContentValues v=new ContentValues();v.put("dimension",dimension);v.put("at_ms",Math.max(oldAt,e.at));v.put("end_ms",Math.max(oldEnd,e.end));db.insertWithOnConflict("seen",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized JSONObject related(JSONObject item)throws Exception{
        JSONObject d=item.optJSONObject("details");if(d==null)d=item;
        long point=item.optLong("timestamp_ms");if(point<=0)point=d.optLong("latest_timestamp_ms");if(point<=0)point=d.optLong("last_packet_ms");
        JSONArray observations=new JSONArray();JSONObject identity=d.optJSONObject("app_identity");JSONArray packages=d.optJSONArray("packages");
        String pkg=packages!=null&&packages.length()==1?packages.optString(0):"",id=identity==null?"":identity.optString("app_identity_id");int uid=d.optInt("uid",-1);
        long installed=identity==null?0:identity.optLong("first_install_ms");
        String reason="",reasonText="";
        if(uid<0){reason="OWNER_UNKNOWN";reasonText="Propriétaire réseau inconnu.";}
        else if(uid%100000<10000||packages==null||packages.length()!=1||pkg.isEmpty()){reason="OWNER_NOT_UNIQUE";reasonText="UID partagé ou réservé, ou paquet non attribué de façon unique.";}
        else if(d.optBoolean("identity_conflict")){reason="IDENTITY_CONFLICT";reasonText="Identités contradictoires dans les observations du flux.";}
        else if(identity==null||identity.optJSONArray("current_signer_sha256")==null||identity.optJSONArray("current_signer_sha256").length()==0||identity.has("network_attribution_unique")&&!identity.optBoolean("network_attribution_unique")){reason="IDENTITY_NOT_VERIFIED";reasonText="Signature enregistrée absente ou attribution partagée.";}
        else if(!PermissionUsageRules.sameActor(uid,pkg,id,identity==null?-1:identity.optInt("uid",-1),identity==null?"":identity.optString("package_name"),id,packages.length())){reason="IDENTITY_NOT_VERIFIED";reasonText="Identité du paquet, UID ou signature non corroborés.";}
        else if(installed<=0){reason="FIRST_INSTALL_UNKNOWN";reasonText="Date de première installation absente de l’identité enregistrée; rapprochement historique non vérifiable.";}
        else if(point<=0){reason="EVENT_TIME_UNKNOWN";reasonText="Date de l’événement non renseignée.";}
        else if(point<installed){reason="EVENT_BEFORE_INSTALL";reasonText="Événement antérieur à l’installation identifiée.";}
        boolean unique=reason.isEmpty();
        boolean more=false;
        long mono=item.optLong("elapsed_ms",d.optLong("elapsed_ms",-1));String clock=item.optString("clock_scope_id",d.optString("clock_scope_id"));
        // The indexed actor query is bounded; prefer monotonic values within the same collector process.
        if(unique&&point>0)try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM observations WHERE pkg=? AND identity=? AND uid=? AND installed_ms=? ORDER BY captured_ms DESC LIMIT 128",new String[]{pkg,id,String.valueOf(uid),String.valueOf(installed)})){
            Set<String> dedup=new HashSet<>();while(c.moveToNext()){
                JSONObject o=new JSONObject(c.getString(0));if(point<o.optLong("first_install_ms"))continue;
                boolean monotonic=mono>=0&&!clock.isEmpty()&&clock.equals(o.optString("clock_scope_id"))&&o.optLong("access_elapsed_ms",-1)>=0;
                long at=monotonic?o.optLong("access_elapsed_ms"):o.optBoolean("running")?o.optLong("running_start_estimate_ms",o.optLong("access_ms")):o.optLong("access_ms"),end=monotonic?o.optLong("end_elapsed_ms",at):o.optLong("access_end_ms"),target=monotonic?mono:point;
                if(!clock.isEmpty()&&!o.optString("clock_scope_id").isEmpty()&&!clock.equals(o.optString("clock_scope_id")))continue;
                if(!PermissionUsageRules.near(at,end,target))continue;
                String key=o.optString("operation")+":"+o.optString("access_result")+":"+o.optLong("access_ms")+":"+o.optString("attribution_tag");if(!dedup.add(key))continue;
                if(observations.length()>=12){more=true;break;}o.put("relation","PROXIMITE_TEMPORELLE").put("time_basis",monotonic?"ANDROID_MONOTONIC":"WALL_TIME_LEGACY").put("time_delta_ms",at-target).put("network_event_ms",point).put("confidence","CORRELATED");observations.put(o);
            }
        }
        String status=!unique?"CORRELATION_UNAVAILABLE":observations.length()>0?"TEMPORAL_MATCHES_PRESENT":"NO_MATCHING_OBSERVATIONS";
        String scope=!unique?"Rapprochement avec les accès aux données indisponible. "+reasonText+" Aucun accès ni absence d’accès n’est déduit.":observations.length()>0?"Même application, UID, signature et installation; accès à moins d’une minute de l’événement, ou encore en cours. Ce rapprochement ne prouve pas que les données ont été envoyées dans ce flux.":"Aucun accès AppOps correspondant dans les observations conservées, à moins d’une minute de cet événement ou encore en cours. Cela ne démontre pas l’absence d’accès aux données ni le contenu du flux.";
        return EventStore.object("schema","aiv-permission-context/1","status",status,"reason",reason.isEmpty()?JSONObject.NULL:reason,"observations",observations,"has_more",more,"time_window_ms",PermissionUsageRules.NEAR_MS,"event_time_ms",point,"actor_verified",unique,"scope",scope);
    }
    static void enrich(Context c,JSONArray rows)throws Exception{
        if(rows==null)return;PermissionUsage store=get(c);
        for(int i=0;i<rows.length();i++){JSONObject e=rows.optJSONObject(i);if(e!=null){
            try{e.put("permission_context",store.related(e));}
            catch(Exception failure){e.put("permission_context",EventStore.object("observations",new JSONArray(),"status","INDISPONIBLE","scope","Lecture des accès indisponible; aucune permission utilisée déduite."));}
            NetworkReport.enrich(c,e);
        }}
    }
    static JSONObject anomalyContext(Context c,JSONArray events)throws Exception{
        enrich(c,events);JSONArray observations=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<events.length();i++){
            JSONObject e=events.getJSONObject(i),pc=e.optJSONObject("permission_context");JSONArray rows=pc==null?null:pc.optJSONArray("observations");if(rows==null)continue;
            for(int j=0;j<rows.length();j++){
                JSONObject o=rows.getJSONObject(j);String key=e.optLong("id")+":"+o.optString("operation")+":"+o.optLong("access_ms")+":"+o.optString("access_result");if(!seen.add(key))continue;
                observations.put(EventStore.object("source_event_id",e.optLong("id"),"package_name",o.optString("package_name"),"uid",o.optInt("uid"),"operation",o.optString("operation"),"permission",o.optString("permission"),"label",o.optString("label"),"access_result",o.optString("access_result"),"access_ms",o.optLong("access_ms"),"access_end_ms",o.optLong("access_end_ms"),"running",o.optBoolean("running"),"captured_ms",o.optLong("captured_ms"),"usage_observation_id",o.optString("usage_observation_id"),"relation","PROXIMITE_TEMPORELLE","proxy",o.optJSONObject("proxy")));
            }
        }
        return EventStore.object("observations",observations,"scope","Accès rapprochés des événements sources conservés; une proximité temporelle ne démontre pas leur contenu ni leur cause.");
    }
    static String brief(JSONObject e){
        JSONObject d=e.optJSONObject("details");if(d!=null&&d.has("access_result"))return d.optString("label")+("REJECT".equals(d.optString("access_result"))?" · refusé":" · signalé");
        JSONObject context=e.optJSONObject("permission_context");JSONArray rows=context==null?null:context.optJSONArray("observations");StringBuilder s=new StringBuilder();Set<String> labels=new LinkedHashSet<>();
        if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject o=rows.optJSONObject(i);if(o!=null)labels.add(o.optString("label")+("REJECT".equals(o.optString("access_result"))?" · refusé":""));}
        for(String label:labels){if(s.length()>0)s.append("\n");s.append(label);if(s.length()>160)break;}
        String networkReport=NetworkReport.brief(e);
        if(s.length()>0)return (networkReport.isEmpty()?"":networkReport+"\n")+s+"\n(proximité temporelle)";
        if(!networkReport.isEmpty())return networkReport;
        boolean network=e.has("flow_correlation_id")||d!=null&&!d.optString("flow_correlation_id").isEmpty();
        return network?"INTERNET · réseau\nAccès aux données : non établi":"Non établi";
    }
    static String explain(JSONObject e){
        StringBuilder s=new StringBuilder(brief(e));JSONObject pc=e.optJSONObject("permission_context");JSONArray rows=pc==null?null:pc.optJSONArray("observations");
        if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject o=rows.optJSONObject(i);if(o==null)continue;s.append("\n\n").append(o.optString("label")).append(" — ").append(o.optString("access_result")).append("\n").append(o.optString("permission",o.optString("operation"))).append("\nAccès : ").append(java.text.DateFormat.getDateTimeInstance().format(new Date(o.optLong("access_ms")))).append("\nObservation : ").append(java.text.DateFormat.getDateTimeInstance().format(new Date(o.optLong("captured_ms")))).append("\nRéférence : ").append(o.optString("usage_observation_id"));if(o.has("proxy"))s.append("\nIntermédiaire déclaré : ").append(o.optJSONObject("proxy"));}
        boolean network=e.has("flow_correlation_id")||e.optJSONObject("details")!=null&&!e.optJSONObject("details").optString("flow_correlation_id").isEmpty();
        String report=NetworkReport.explain(e);
        return s+"\n\n"+(pc==null?SCOPE:pc.optString("scope"))+(report.isEmpty()?"":"\n\nInterprétation réseau\n"+report)+(network?"\nINTERNET est la capacité Android nécessaire pour ouvrir directement une connexion réseau. Le paquet ne porte pas le nom de la permission ni la provenance des données.":"");
    }
}
