package fr.erick.journallocal;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit, user-triggered refresh bridge for the coherence engine.
 *
 * The APK never embeds a GitHub token or an LLM/API secret. It sends only the
 * package name plus requested permission names to a user-configured HTTPS
 * endpoint. Permission presence is not evidence of permission use.
 */
public final class CoherenceRefresh {
    private static final String PREFS="coherence-refresh";
    private static final String KEY_ENDPOINT="endpoint";
    private static final String RESULT_DIR="coherence";
    private static final String RESULT_FILE="ai_verdicts.json";
    private static final int MAX_REQUEST=1536*1024;
    private static final int MAX_RESPONSE=4*1024*1024;
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static volatile String phase="idle";
    private static volatile String message="Aucune actualisation lancée.";
    private static volatile long startedMs=0,finishedMs=0;

    private CoherenceRefresh(){}

    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static String endpoint(Context c){return prefs(c).getString(KEY_ENDPOINT,"").trim();}

    public static JSONObject configureEndpoint(Context c,String value)throws Exception{
        String v=value==null?"":value.trim();
        if(v.isEmpty()){
            prefs(c).edit().remove(KEY_ENDPOINT).apply();
            return EventStore.object("ok",true,"configured",false,"message","Moteur HTTPS délié; aucun inventaire ne sera envoyé.");
        }
        URL u=new URL(v);
        if(!"https".equalsIgnoreCase(u.getProtocol())||u.getHost()==null||u.getHost().trim().isEmpty())
            throw new IllegalArgumentException("Une URL HTTPS valide est requise");
        if(u.getUserInfo()!=null)throw new IllegalArgumentException("Identifiants interdits dans l’URL");
        prefs(c).edit().putString(KEY_ENDPOINT,v).apply();
        return EventStore.object("ok",true,"configured",true,"endpoint",safeEndpoint(v),"message","Moteur HTTPS relié.");
    }

    private static String safeEndpoint(String value){
        try{URL u=new URL(value);return u.getProtocol()+"://"+u.getHost()+(u.getPort()>0?":"+u.getPort():"")+u.getPath();}
        catch(Exception e){return "HTTPS configuré";}
    }

    public static JSONObject status(Context c){
        String e=endpoint(c);
        return EventStore.object("running",RUNNING.get(),"phase",phase,"message",message,
                "started_ms",startedMs,"finished_ms",finishedMs,"endpoint_configured",!e.isEmpty(),
                "endpoint",e.isEmpty()?JSONObject.NULL:safeEndpoint(e));
    }

    public static JSONObject trigger(Context c){
        final Context app=c.getApplicationContext();
        if(!RUNNING.compareAndSet(false,true))return status(app);
        startedMs=System.currentTimeMillis();finishedMs=0;phase="inventory";message="Actualisation de l’inventaire local…";
        new Thread(()->run(app),"aiv-coherence-refresh").start();
        return status(app);
    }

    private static void run(Context c){
        try{
            PermissionAudit audit=PermissionAudit.get(c);
            long before=audit.summary().optLong("scan_id",0);
            audit.scan();
            long deadline=System.currentTimeMillis()+60000L;
            JSONObject state;
            while(true){
                state=audit.summary();
                boolean busy=state.optBoolean("busy",false);
                long id=state.optLong("scan_id",0);
                if(!busy&&id>before)break;
                if(!busy&&!state.optString("error","").isEmpty()&&id<=before)
                    throw new IOException(state.optString("error"));
                if(System.currentTimeMillis()>=deadline)throw new IOException("Délai dépassé pendant l’inventaire local");
                Thread.sleep(250L);
            }

            String endpoint=endpoint(c);
            if(endpoint.isEmpty()){
                phase="endpoint_required";
                message="Inventaire local actualisé. Relie une URL HTTPS pour calculer le nouvel indice.";
                return;
            }

            phase="sending";message="Calcul de cohérence en cours…";
            JSONObject local=audit.coherenceInventory();
            JSONObject request=minimalRequest(local);
            byte[] bytes=request.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>MAX_REQUEST)throw new IOException("Inventaire trop volumineux pour l’actualisation");
            JSONObject response=post(endpoint,bytes);
            if(!"aiv-coherence-verdicts/2".equals(response.optString("schema")))
                throw new IOException("Réponse du moteur incompatible");
            if(!(response.opt("apps") instanceof JSONArray))throw new IOException("Résultat du moteur incomplet");
            save(c,response);
            JSONObject summary=summary(c);
            phase="done";
            Object score=summary.opt("score");
            message=score==null||score==JSONObject.NULL?"Actualisation terminée; couverture insuffisante pour les scores.":"Actualisation terminée : trois groupes mis à jour, "+summary.optInt("evaluated_count")+" applications évaluées.";
        }catch(Exception e){
            phase="error";message="Actualisation interrompue : "+safeMessage(e)+". Le dernier résultat connu est conservé.";
        }finally{finishedMs=System.currentTimeMillis();RUNNING.set(false);}
    }

    private static JSONObject minimalRequest(JSONObject local)throws Exception{
        JSONArray src=local.optJSONArray("apps"),apps=new JSONArray();
        if(src==null)src=new JSONArray();
        if(src.length()>1000)throw new IOException("Trop d’applications dans l’inventaire");
        for(int i=0;i<src.length();i++){
            JSONObject a=src.getJSONObject(i),out=new JSONObject();
            out.put("package",a.optString("package"));
            JSONArray p=a.optJSONArray("permissions");
            out.put("permissions",p==null?new JSONArray():p);
            apps.put(out);
        }
        return EventStore.object("schema","journal-coherence-input/1","notice","Permissions demandées/déclarées; leur présence ne prouve pas leur utilisation.","apps",apps);
    }

    private static JSONObject post(String endpoint,byte[] body)throws Exception{
        URL u=new URL(endpoint);
        if(!"https".equalsIgnoreCase(u.getProtocol()))throw new IOException("Le moteur doit utiliser HTTPS");
        HttpURLConnection h=(HttpURLConnection)u.openConnection();
        h.setConnectTimeout(10000);h.setReadTimeout(30000);h.setRequestMethod("POST");h.setDoOutput(true);
        h.setRequestProperty("Content-Type","application/json; charset=utf-8");
        h.setRequestProperty("Accept","application/json");
        h.setRequestProperty("User-Agent","JournalLocalAIV/0.6.13");
        h.setFixedLengthStreamingMode(body.length);
        try(OutputStream out=h.getOutputStream()){out.write(body);}
        int code=h.getResponseCode();
        InputStream in=code>=200&&code<300?h.getInputStream():h.getErrorStream();
        byte[] data=readLimited(in,MAX_RESPONSE);
        if(code<200||code>=300){String detail=new String(data,StandardCharsets.UTF_8).replace('\n',' ').trim();if(detail.length()>180)detail=detail.substring(0,180);throw new IOException("Moteur HTTPS "+code+(detail.isEmpty()?"":" — "+detail));}
        return new JSONObject(new String(data,StandardCharsets.UTF_8));
    }

    private static byte[] readLimited(InputStream in,int max)throws IOException{
        if(in==null)return new byte[0];
        try(InputStream input=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n,total=0;
            while((n=input.read(b))!=-1){total+=n;if(total>max)throw new IOException("Réponse du moteur trop volumineuse");out.write(b,0,n);}
            return out.toByteArray();
        }
    }

    private static void save(Context c,JSONObject root)throws Exception{
        File dir=new File(c.getFilesDir(),RESULT_DIR);if(!dir.exists()&&!dir.mkdirs())throw new IOException("Stockage privé indisponible");
        File target=new File(dir,RESULT_FILE),tmp=new File(dir,RESULT_FILE+".tmp");
        byte[] data=(root.toString()+"\n").getBytes(StandardCharsets.UTF_8);
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(data);out.flush();out.getFD().sync();}
        if(target.exists()&&!target.delete())throw new IOException("Ancien résultat verrouillé");
        if(!tmp.renameTo(target))throw new IOException("Enregistrement atomique impossible");
    }

    private static JSONObject readSaved(Context c)throws Exception{
        File f=new File(new File(c.getFilesDir(),RESULT_DIR),RESULT_FILE);
        if(!f.isFile())return null;
        if(f.length()>MAX_RESPONSE)throw new IOException("Résultat local trop volumineux");
        try(FileInputStream in=new FileInputStream(f)){return new JSONObject(new String(readLimited(in,MAX_RESPONSE),StandardCharsets.UTF_8));}
    }

    private static JSONObject groupJson(String id,CoherenceGroups.Stats s)throws Exception{
        return EventStore.object("id",id,"score",s.mean()==null?JSONObject.NULL:s.mean(),
            "app_count",s.total,"evaluated_count",s.evaluated,"unknown_count",s.unknown,
            "below_50",s.below50,"below_70",s.below70,"incoherences",s.findings,
            "sum_scores",s.sum,"minimum",s.minimum==null?JSONObject.NULL:s.minimum);
    }

    public static JSONObject detail(Context c,String pkg)throws Exception{
        if(pkg==null||pkg.length()>255)throw new IllegalArgumentException("Paquet invalide");
        JSONObject root=readSaved(c);
        if(root!=null){
            JSONArray rows=root.optJSONArray("apps");
            if(rows!=null)for(int i=0;i<rows.length();i++){
                JSONObject r=rows.getJSONObject(i);
                if(pkg.equals(r.optString("package")))return EventStore.object("available",true,"verdict",r,
                    "policy",root.optJSONObject("policy"),"engine",root.optString("engine"),
                    "generated_at_ms",root.optLong("generated_at_ms"));
            }
        }
        return EventStore.object("available",false,"message","Aucun calcul enregistré pour cette application. Appuie sur Actualiser depuis l’accueil.");
    }

    public static JSONObject summary(Context c){
        try{
            JSONObject root=readSaved(c);
            JSONArray local=PermissionAudit.get(c).coherenceInventory().optJSONArray("apps");
            if(local==null)local=new JSONArray();
            JSONObject journal=EventStore.get(c).coherenceAttribution();
            Set<String> observed=new HashSet<>();JSONArray observedPackages=journal.optJSONArray("packages");
            if(observedPackages!=null)for(int i=0;i<observedPackages.length();i++)observed.add(observedPackages.optString(i));
            Map<String,JSONObject> verdicts=new HashMap<>();
            JSONArray rows=root==null?null:root.optJSONArray("apps");
            if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);verdicts.put(r.optString("package"),r);}
            LinkedHashMap<String,CoherenceGroups.Stats> groups=new LinkedHashMap<>();
            for(String id:new String[]{"android","system","user"})groups.put(id,new CoherenceGroups.Stats());
            CoherenceGroups.Stats all=new CoherenceGroups.Stats();
            ArrayList<JSONObject> ranked=new ArrayList<>();
            for(int i=0;i<local.length();i++){
                JSONObject a=local.getJSONObject(i);String pkg=a.optString("package");
                int uid=a.optInt("uid",-1),uidPackages=a.optInt("uid_package_count",0);boolean systemApp=a.optBoolean("system_app")||a.optBoolean("updated_system_app");
                boolean seen=observed.contains(pkg);String group=CoherenceGroups.group(systemApp,uid,uidPackages);
                JSONObject r=verdicts.get(pkg);String category=r==null?"unknown":r.optString("category","unknown");
                Integer rawScore=r==null?null:CoherenceGroups.score(r.opt("score_local"));Integer score="unknown".equals(category)?null:rawScore;
                JSONArray inco=r==null?null:r.optJSONArray("incoherences_local");int issues=inco==null?0:inco.length();
                groups.get(group).add(score,issues);all.add(score,issues);
                String reason="android".equals(group)?(uid<0?"UID non disponible":uid%100000<10000?"UID système réservé · auteur applicatif non démontré":"UID partagé entre "+uidPackages+" paquets · auteur unique non démontré"):systemApp?"Application système préinstallée · package unique":"Application utilisateur · package unique";
                ranked.add(EventStore.object("package",pkg,"label",a.optString("label",pkg),"uid",uid,"uid_package_count",uidPackages,
                    "group",group,"system_app",systemApp,"category",category,"journal_observed",seen,"attribution_unique",!"android".equals(group),
                    "group_reason",reason,"score",score==null?JSONObject.NULL:score,"raw_score",rawScore==null?JSONObject.NULL:rawScore,"incoherences",issues));
            }
            Collections.sort(ranked,(a,b)->{
                int diff=Integer.compare(a.isNull("score")?101:a.optInt("score"),b.isNull("score")?101:b.optInt("score"));
                return diff!=0?diff:a.optString("package").compareTo(b.optString("package"));
            });
            JSONArray apps=new JSONArray(),outGroups=new JSONArray();for(JSONObject r:ranked)apps.put(r);
            for(Map.Entry<String,CoherenceGroups.Stats> e:groups.entrySet())outGroups.put(groupJson(e.getKey(),e.getValue()));
            JSONObject transparency=EventStore.get(c).transparencySummary();JSONObject anomalies=AnomalyMonitor.get(c).summary();
            return groupJson("all",all).put("available",root!=null).put("groups",outGroups).put("apps",apps)
                .put("journal",journal).put("transparency",transparency).put("anomalies",anomalies.optLong("anomalies",0))
                .put("source",root==null?"":root.optString("engine"))
                .put("generated_at_ms",root==null?0:root.optLong("generated_at_ms"))
                .put("notice","Trois groupes exclusifs selon l’identité Android. Les fonctions inconnues et applications sans score sont affichées mais exclues du dénominateur; inconnu ne signifie ni sûr ni malveillant.");
        }catch(Exception e){return EventStore.object("available",false,"error","Résultat de cohérence illisible : "+e.getClass().getSimpleName());}
    }

    private static String safeMessage(Exception e){String s=e.getMessage();if(s==null||s.trim().isEmpty())s=e.getClass().getSimpleName();return s.replace('\n',' ').trim();}
}