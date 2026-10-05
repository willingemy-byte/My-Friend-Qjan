package fr.erick.journallocal;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.HandlerThread;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Incremental analysis in a separate local database. Source journal schema stays at version 1. */
public final class AnomalyMonitor extends SQLiteOpenHelper {
    private static AnomalyMonitor instance;
    public static volatile String lastError="";
    public static volatile String liveError="";
    private final Context context;
    private final Handler worker;
    private final AtomicBoolean scheduled=new AtomicBoolean();
    private volatile boolean busy;
    private AnomalyRules rules;
    private long settingsRevision=-1;
    private final ConvergenceRules convergence=new ConvergenceRules();
    private final AnomalyRules liveRules=new AnomalyRules();
    private final java.util.concurrent.atomic.AtomicInteger pendingLive=new java.util.concurrent.atomic.AtomicInteger();
    public static volatile long lastLiveMs;
    public static volatile long droppedLive;
    public static void observe(Context context,JSONObject event){
        AnomalyMonitor m=get(context);
        if(m.pendingLive.get()>256&&"trafic".equals(event.optString("category"))){droppedLive++;return;}
        try{JSONObject copy=new JSONObject(event.toString());m.pendingLive.incrementAndGet();m.worker.post(()->{
            try{NetworkReport.enrich(m.context,copy);m.pedigree(copy,true);for(JSONObject finding:m.convergence.accept(copy))m.saveLive(finding);
                AnomalyRules.Settings settings=m.parseSettings(m.settingsJson());m.liveRules.accept(event(copy),settings,f->{f.key="live:rules:"+copy.optString("clock_scope_id")+":"+f.key;m.saveFinding(m.getWritableDatabase(),f,copy);});
                lastLiveMs=System.currentTimeMillis();liveError="";ScreenIntegrityService.findingsChanged();}
            catch(Exception e){liveError="Corrélation temps réel : "+e.getClass().getSimpleName();}
            finally{m.pendingLive.decrementAndGet();}
        });}catch(Exception e){liveError="Observation de corrélation indisponible";}
    }
    public static synchronized AnomalyMonitor get(Context context){if(instance==null)instance=new AnomalyMonitor(context.getApplicationContext());return instance;}
    public static void request(Context context){try{get(context).kick();}catch(Exception e){lastError="Analyse indisponible : "+e.getClass().getSimpleName();}}
    private AnomalyMonitor(Context context){
        super(context,"analysis.sqlite",null,1);this.context=context;setWriteAheadLoggingEnabled(true);
        HandlerThread thread=new HandlerThread("journal-analysis",android.os.Process.THREAD_PRIORITY_BACKGROUND);thread.start();worker=new Handler(thread.getLooper());
    }
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE state(id INTEGER PRIMARY KEY CHECK(id=1),payload BLOB NOT NULL,checkpoint INTEGER NOT NULL,processed INTEGER NOT NULL,settings_revision INTEGER NOT NULL,unknown_count INTEGER NOT NULL,uncertain_count INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE findings(id INTEGER PRIMARY KEY AUTOINCREMENT,group_key TEXT NOT NULL UNIQUE,kind TEXT NOT NULL,rule TEXT NOT NULL,last_event_id INTEGER NOT NULL,first_ms INTEGER NOT NULL,last_ms INTEGER NOT NULL,occurrences INTEGER NOT NULL,reviewed INTEGER NOT NULL DEFAULT 0,payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX findings_kind ON findings(kind,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Migration de l’analyse requise.");}
    @Override public void onOpen(SQLiteDatabase db){super.onOpen(db);db.execSQL("CREATE TABLE IF NOT EXISTS analysis_snapshots(revision INTEGER NOT NULL,processed INTEGER NOT NULL,checkpoint INTEGER NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(revision,processed))");
        db.execSQL("CREATE TABLE IF NOT EXISTS endpoint_sessions(actor TEXT NOT NULL,endpoint TEXT NOT NULL,flow TEXT NOT NULL,first_ms INTEGER NOT NULL,last_ms INTEGER NOT NULL,tx INTEGER,rx INTEGER,payload TEXT NOT NULL,PRIMARY KEY(actor,endpoint,flow))");
        db.execSQL("CREATE INDEX IF NOT EXISTS endpoint_actor ON endpoint_sessions(actor,endpoint)");
    }
    public void kick(){if(scheduled.compareAndSet(false,true))worker.postDelayed(this::drain,350);}
    private void drain(){
        busy=true;boolean again=false;
        try{
            SQLiteDatabase db=getWritableDatabase();
            if(rules==null){
                try(Cursor c=db.rawQuery("SELECT payload,settings_revision FROM state WHERE id=1",null)){
                    if(c.moveToFirst()){
                        // Only our private checkpoint is deserialized; exports and imported files never enter here.
                        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(c.getBlob(0)))){rules=(AnomalyRules)in.readObject();}catch(InvalidClassException incompatible){rules=new AnomalyRules();}
                        settingsRevision=c.getLong(1);
                    }else rules=new AnomalyRules();
                }
            }
            JSONObject config=settingsJson();long revision=config.optLong("revision");
            AnomalyRules.Settings settings=parseSettings(config);
            if(revision!=settingsRevision){rules=new AnomalyRules();settingsRevision=revision;}
            JSONArray events=EventStore.get(context).analysisBatch(rules.checkpoint,(int)Math.min(200,50000-rules.processed%50000));
            if(events.length()>0||revision!=persistedRevision(db)){
                db.beginTransaction();
                try{
                    for(int i=0;i<events.length();i++){JSONObject item=events.getJSONObject(i);pedigree(item,true);rules.accept(event(item),settings,f->{
                        // Live groups already cover observations from this process; replay is for older source rows.
                        if(!EventStore.clockScope().equals(item.optString("clock_scope_id")))saveFinding(db,f);
                    });}
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(rules);}
                    ContentValues state=new ContentValues();state.put("id",1);state.put("payload",bytes.toByteArray());state.put("checkpoint",rules.checkpoint);state.put("processed",rules.processed);state.put("settings_revision",settingsRevision);state.put("unknown_count",rules.unknownAttribution);state.put("uncertain_count",rules.uncertainCounters);
                    db.insertWithOnConflict("state",null,state,SQLiteDatabase.CONFLICT_REPLACE);
                    if(rules.processed>0&&rules.processed%50000==0){ContentValues snapshot=new ContentValues();snapshot.put("revision",settingsRevision);snapshot.put("processed",rules.processed);snapshot.put("checkpoint",rules.checkpoint);snapshot.put("payload",bytes.toByteArray());db.insertWithOnConflict("analysis_snapshots",null,snapshot,SQLiteDatabase.CONFLICT_IGNORE);}
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }
            lastError="";again=EventStore.get(context).latestId()>rules.checkpoint;ScreenIntegrityService.findingsChanged();
        }catch(Exception e){rules=null;lastError="Analyse interrompue : "+e.getClass().getSimpleName()+". Le journal original est conservé.";}
        finally{
            busy=false;scheduled.set(false);
            // Avoid losing a notification posted while the last batch was finishing.
            if(lastError.isEmpty())try{if(again||EventStore.get(context).latestId()>(rules==null?0:rules.checkpoint))kick();}catch(Exception e){lastError="État de l’analyse indisponible.";}
        }
    }
    static String[] strings(JSONArray a){if(a==null)return new String[0];String[] s=new String[a.length()];for(int i=0;i<s.length;i++)s[i]=a.optString(i);return s;}
    static AnomalyRules.Event event(JSONObject j){
        JSONObject d=j.optJSONObject("details");if(d==null)d=new JSONObject();AnomalyRules.Event e=new AnomalyRules.Event();
        e.id=j.optLong("id");e.wall=j.optLong("timestamp_ms");e.elapsed=j.optLong("elapsed_ms",-1);e.actor=j.optString("app");e.category=j.optString("category");e.action=j.optString("action");e.source=j.optString("source");e.destination=j.optString("destination");
        e.uid=d.optInt("uid",-1);e.packages=strings(d.optJSONArray("packages"));e.flow=d.optString("flow_id");e.tx=d.optLong("tx_bytes",-1);e.rx=d.optLong("rx_bytes",-1);e.clock=j.optString("clock_scope_id");e.actorIdentity=ConvergenceRules.actorKey(j);e.closed=d.optBoolean("closed");e.error=d.optInt("error_code");e.result=d.optString("result");e.port=d.optInt("port");
        e.problem=d.optString("error");e.coverageGap=d.optBoolean("coverage_gap");e.interval=d.optLong("interval_ms");e.query=d.optString("question");if(e.query.isEmpty()&&!d.optBoolean("ech_extension_present")&&"Nom TLS observé".equals(e.action))e.query=d.optString("tls_sni");if(d.has("dns_resolvers"))e.resolvers=strings(d.optJSONArray("dns_resolvers"));return e;
    }
    private void saveFinding(SQLiteDatabase db,AnomalyRules.Finding f){
        saveFinding(db,f,null);
    }
    private void saveFinding(SQLiteDatabase db,AnomalyRules.Finding f,JSONObject source){
        try{
            if(!f.key.startsWith("live:"))f.key=settingsRevision+":"+f.key;
            long id=0,first=f.wall,occurrences=0;boolean reviewed=false;JSONObject old=null;
            try(Cursor c=db.rawQuery("SELECT id,first_ms,occurrences,payload,reviewed FROM findings WHERE group_key=?",new String[]{f.key})){
                if(c.moveToFirst()){id=c.getLong(0);first=c.getLong(1);occurrences=c.getLong(2);old=new JSONObject(c.getString(3));reviewed=c.getInt(4)!=0;}
            }
            LinkedHashSet<Long> ids=new LinkedHashSet<>();
            if(old!=null){JSONArray previous=old.optJSONArray("evidence_ids");if(previous!=null)for(int i=0;i<previous.length();i++)ids.add(previous.getLong(i));}
            ids.addAll(f.evidence);List<Long> bounded=new ArrayList<>(ids);
            // A bounded sample links back to original rows. Never present it as all contributing events.
            while(bounded.size()>12)bounded.remove(1);
            JSONObject facts=new JSONObject();for(Map.Entry<String,String> part:f.facts.entrySet())facts.put(part.getKey(),part.getValue());
            JSONObject payload=EventStore.object("rule",f.rule,"kind",f.type,"severity",f.severity,"title",f.title,"actor",f.actor,"subject",f.subject,"explanation",f.explanation,"advice",f.advice,"facts",facts,"evidence_ids",new JSONArray(bounded),"criteria_version","journal-local/snapshots-1","settings_revision",settingsRevision);
            payload.put("correlation_id",old==null?java.util.UUID.randomUUID().toString():old.optString("correlation_id",java.util.UUID.randomUUID().toString())).put("category","coverage".equals(f.type)?"SENSOR_HEALTH":"APPLICATION_BEHAVIOR").put("conclusion",EventStore.object("established",new JSONArray().put(f.explanation),"correlated",new JSONArray().put("Règle déterministe portant sur les événements sources"),"unknown",new JSONArray().put("Contenu et causalité applicative"),"confidence","METADATA_RULE"));
            payload.put("origin",source==null?"HISTORICAL_REPLAY":"LIVE_OBSERVATION").put("overlay_eligible",source!=null&&"anomaly".equals(f.type));
            JSONObject click=source!=null&&"anomaly".equals(f.type)?convergence.interactionFor(source):null;
            if(click!=null){JSONObject frontend=new JSONObject(ConvergenceRules.details(click).toString()).put("clock_scope_id",click.optString("clock_scope_id")).put("relation","TEMPORAL_NOT_CAUSAL");
                payload.put("frontend",frontend).put("visual",EventStore.object("status","SEMANTIC_ONLY","comparison_confirmed",false));
                JSONArray evidence=payload.getJSONArray("evidence_ids");boolean present=false;for(int i=0;i<evidence.length();i++)if(evidence.optLong(i)==click.optLong("id"))present=true;if(!present)evidence.put(click.optLong("id"));
                payload.getJSONObject("conclusion").getJSONArray("correlated").put("Clic récent de la même identité sur la même horloge ; aucune causalité du clic démontrée");
            }
            ContentValues values=new ContentValues();values.put("group_key",f.key);values.put("kind",f.type);values.put("rule",f.rule);values.put("first_ms",first);values.put("last_ms",f.wall);values.put("last_event_id",f.eventId);values.put("occurrences",occurrences+1);values.put("payload",payload.toString());
            if(id==0)id=db.insertOrThrow("findings",null,values);else db.update("findings",values,"id=?",new String[]{String.valueOf(id)});
            if(source!=null&&"anomaly".equals(f.type)&&!reviewed)ScreenIntegrityService.findingCommitted(id,payload.put("id",id).put("anomaly_id",id));
        }catch(Exception e){throw new IllegalStateException("Signalement non enregistré",e);}
    }
    private long persistedRevision(SQLiteDatabase db){try(Cursor c=db.rawQuery("SELECT settings_revision FROM state WHERE id=1",null)){return c.moveToFirst()?c.getLong(0):-1;}}
    public synchronized JSONObject settingsJson()throws Exception{
        String saved=context.getSharedPreferences("analysis",Context.MODE_PRIVATE).getString("settings",null);
        if(saved!=null){JSONObject config=new JSONObject(saved);
            if(!"convergence/1".equals(config.optString("engine"))){config.put("revision",config.optLong("revision")+1).put("engine","convergence/1").put("replay_target",EventStore.get(context).latestId());if(!context.getSharedPreferences("analysis",0).edit().putString("settings",config.toString()).commit())throw new IOException("Migration des réglages non enregistrée");}
            return config;}
        JSONObject initial=EventStore.object("failures",true,"volume",true,"dns",true,"collection",true,"watch",true,"research",true,"failure_count",AivConfig.ANOMALY_FAILURE_COUNT_DEFAULT,"upload_mib",AivConfig.ANOMALY_UPLOAD_MIB_DEFAULT,"domains",new JSONArray(),"revision",0,"quiet",true,"engine","convergence/1","replay_target",EventStore.get(context).latestId());
        if(!context.getSharedPreferences("analysis",0).edit().putString("settings",initial.toString()).commit())throw new IOException("Paramètres initiaux non enregistrés");return initial;
    }
    private AnomalyRules.Settings parseSettings(JSONObject j){
        AnomalyRules.Settings s=new AnomalyRules.Settings();s.failures=j.optBoolean("failures",true);s.volume=j.optBoolean("volume",true);s.dns=j.optBoolean("dns",true);s.collection=j.optBoolean("collection",true);s.watch=j.optBoolean("watch",true);s.research=j.optBoolean("research",true);s.failureCount=j.optInt("failure_count",AivConfig.ANOMALY_FAILURE_COUNT_DEFAULT);s.uploadMiB=j.optInt("upload_mib",AivConfig.ANOMALY_UPLOAD_MIB_DEFAULT);s.domains=strings(j.optJSONArray("domains"));s.validate();return s;
    }
    public synchronized JSONObject change(String action,String value)throws Exception{
        if(value==null||value.length()>16000)throw new IllegalArgumentException("Paramètres trop longs.");
        if("settings".equals(action)){
            JSONObject input=new JSONObject(value);AnomalyRules.Settings s=parseSettings(input);JSONObject saved=EventStore.object("failures",s.failures,"volume",s.volume,"dns",s.dns,"collection",s.collection,"watch",s.watch,"research",s.research,"failure_count",s.failureCount,"upload_mib",s.uploadMiB,"domains",new JSONArray(Arrays.asList(s.domains)),"quiet",input.optBoolean("quiet",true),"revision",settingsJson().optLong("revision")+1);
            JSONObject previous=settingsJson();saved.put("engine","convergence/1");
            boolean changed=false;for(String key:new String[]{"failures","volume","dns","collection","watch","research","failure_count","upload_mib","domains"})if(!String.valueOf(previous.opt(key)).equals(String.valueOf(saved.opt(key))))changed=true;
            saved.put("revision",previous.optLong("revision")+(changed?1:0));saved.put("replay_target",changed?EventStore.get(context).latestId():previous.optLong("replay_target",0));
            if(!context.getSharedPreferences("analysis",Context.MODE_PRIVATE).edit().putString("settings",saved.toString()).commit())throw new IOException("Paramètres non enregistrés.");
            kick();return EventStore.object("ok",true,"settings",saved);
        }
        if("review".equals(action)){
            long id=Long.parseLong(value);if(id<=0)throw new IllegalArgumentException("Signalement invalide.");
            // Reviewed groups remain reviewed if more repetitions arrive; new time groups are unread.
            ContentValues values=new ContentValues();values.put("reviewed",1);getWritableDatabase().update("findings",values,"id=?",new String[]{String.valueOf(id)});ScreenIntegrityService.findingsChanged();return EventStore.object("ok",true);
        }
        if("review-all".equals(action)){
            long ceiling=Long.parseLong(value);if(ceiling<0)throw new IllegalArgumentException("Limite invalide");
            ContentValues values=new ContentValues();values.put("reviewed",1);
            getWritableDatabase().update("findings",values,"group_key LIKE 'live:%' AND kind='anomaly' AND reviewed=0 AND id<=?",new String[]{String.valueOf(ceiling)});
            ScreenIntegrityService.findingsChanged();return EventStore.object("ok",true,"through_id",ceiling,"scope","Alertes marquées consultées ; dossiers conservés ; nouveaux IDs après la limite inchangés.");
        }
        if("retry".equals(action)){kick();return EventStore.object("ok",true);}
        throw new IllegalArgumentException("Action inconnue.");
    }
    public synchronized JSONObject summary()throws Exception{
        long checkpoint=0,processed=0,unread=0,historicalUnread=0,alertCeiling=0,traces=0,total=0,unknown=0,uncertain=0,coverage=0;
        SQLiteDatabase db=getReadableDatabase();JSONObject config=settingsJson();long revision=config.optLong("revision");
        try(Cursor c=db.rawQuery("SELECT checkpoint,processed,unknown_count,uncertain_count FROM state WHERE id=1 AND settings_revision=?",new String[]{""+revision})){if(c.moveToFirst()){checkpoint=c.getLong(0);processed=c.getLong(1);unknown=c.getLong(2);uncertain=c.getLong(3);}}
        try(Cursor c=db.rawQuery("SELECT kind,reviewed,COUNT(*),CASE WHEN group_key LIKE 'live:%' THEN 1 ELSE 0 END FROM findings WHERE (group_key LIKE ? OR group_key LIKE 'live:%') GROUP BY kind,reviewed,CASE WHEN group_key LIKE 'live:%' THEN 1 ELSE 0 END",new String[]{revision+":%"})){while(c.moveToNext()){if(c.getString(0).equals("trace"))traces+=c.getLong(2);else if(c.getString(0).equals("coverage"))coverage+=c.getLong(2);else {total+=c.getLong(2);if(c.getInt(1)==0){if(c.getInt(3)==1)unread+=c.getLong(2);else historicalUnread+=c.getLong(2);}}}}
        try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM findings WHERE group_key LIKE 'live:%' AND kind='anomaly' AND reviewed=0",null)){c.moveToFirst();alertCeiling=c.getLong(0);}
        return EventStore.object("revision",revision,"replay_target",config.optLong("replay_target",0),"recalculating",persistedRevision(db)!=revision||checkpoint<config.optLong("replay_target",0),"processed",processed,"checkpoint",checkpoint,"latest",EventStore.get(context).latestId(),"busy",busy||scheduled.get(),"unread",unread,"historical_unread",historicalUnread,"alert_ceiling_id",alertCeiling,"unread_scope","Alertes temps réel non consultées ; historique et santé séparés","anomalies",total,"coverage_findings",coverage,"traces",traces,"unknown_attribution",unknown,"uncertain_counters",uncertain,"last_live_ms",lastLiveMs,"pending_live",pendingLive.get(),"dropped_live",droppedLive,"live_error",liveError,"history_error",lastError,"error",liveError.isEmpty()?lastError:liveError,"settings",settingsJson());
    }
    public synchronized JSONObject page(String kind,boolean unread,int offset)throws Exception{
        return page(kind,unread,offset,15,"");
    }
    public synchronized JSONObject page(String kind,boolean unread,int offset,int limit,String search)throws Exception{
        if(!kind.equals("trace")&&!kind.equals("anomaly")&&!kind.equals("coverage"))throw new IllegalArgumentException("Vue inconnue.");
        JSONObject status=summary();
        String revision=status.getLong("revision")+":%";
        offset=Math.max(0,offset);limit=Math.max(1,Math.min(500,limit));
        String where="(group_key LIKE ? OR group_key LIKE 'live:%') AND kind=?"+(unread?" AND reviewed=0"+("anomaly".equals(kind)?" AND group_key LIKE 'live:%'":""):"");ArrayList<String> args=new ArrayList<>();args.add(revision);args.add(kind);
        String term=search==null?"":search.trim().toLowerCase(Locale.ROOT);
        if(!term.isEmpty()){String escaped=term.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");where+=" AND (LOWER(payload) LIKE ? ESCAPE '\\' OR CAST(id AS TEXT)=?)";args.add("%"+escaped+"%");args.add(term);}
        SQLiteDatabase db=getReadableDatabase();long count;
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM findings WHERE "+where,args.toArray(new String[0]))){c.moveToFirst();count=c.getLong(0);}
        ArrayList<String> pageArgs=new ArrayList<>(args);pageArgs.add(String.valueOf(limit));pageArgs.add(String.valueOf(offset));
        JSONArray rows=new JSONArray();try(Cursor c=db.rawQuery("SELECT id,first_ms,last_ms,occurrences,reviewed,payload,group_key FROM findings WHERE "+where+" ORDER BY id DESC LIMIT ? OFFSET ?",pageArgs.toArray(new String[0]))){
            while(c.moveToNext()){JSONObject row=new JSONObject(c.getString(5));row.put("id",c.getLong(0));row.put("first_ms",c.getLong(1));row.put("last_ms",c.getLong(2));row.put("occurrences",c.getLong(3));row.put("reviewed",c.getInt(4)!=0).put("origin",c.getString(6).startsWith("live:")?"LIVE_OBSERVATION":"HISTORICAL_REPLAY").put("overlay_eligible",c.getString(6).startsWith("live:")&&"anomaly".equals(row.optString("kind")));rows.put(row);}
        }
        for(int i=0;i<rows.length();i++){
            JSONObject item=rows.getJSONObject(i);JSONArray ids=item.optJSONArray("evidence_ids");
            if(ids==null)continue;JSONArray events=EventStore.get(context).evidence(ids);JSONObject identity=null;String key=null;boolean mixed=false;
            for(int j=0;j<events.length();j++){JSONObject event=events.getJSONObject(j),d=event.optJSONObject("details");if(d==null||d.optInt("uid",-1)<0){mixed=true;break;}
                String current=d.optInt("uid")+":"+String.valueOf(d.optJSONArray("packages"));if(key!=null&&!key.equals(current)){mixed=true;break;}key=current;identity=EventStore.object("app",event.optString("app"),"details",d);
            }
            if(!mixed&&identity!=null)item.put("identity",identity);
            item.put("permission_context",PermissionUsage.anomalyContext(context,events));
            item.put("network_context",NetworkReport.anomalyContext(events));
        }
        return EventStore.object("rows",rows,"total",count,"limit",limit,"offset",offset,"query",term,"recalculating",status.optBoolean("recalculating"),"checkpoint",status.optLong("checkpoint"),"target",status.optLong("replay_target"));
    }
    private synchronized void saveLive(JSONObject f)throws Exception{
        SQLiteDatabase db=getWritableDatabase();String key=f.getString("group_key");
        try(Cursor existing=db.rawQuery("SELECT id FROM findings WHERE group_key=?",new String[]{key})){if(existing.moveToFirst())return;}
        f.put("origin","LIVE_OBSERVATION").put("overlay_eligible","anomaly".equals(f.optString("kind","anomaly")));
        ContentValues v=new ContentValues();v.put("group_key",key);v.put("kind",f.optString("kind","anomaly"));v.put("rule",f.optString("rule"));v.put("last_event_id",f.optLong("last_event_id"));v.put("first_ms",f.optLong("first_ms"));v.put("last_ms",f.optLong("last_ms"));v.put("occurrences",1);v.put("payload",f.toString());
        long id=db.insertOrThrow("findings",null,v);f.put("id",id).put("anomaly_id",id);if("anomaly".equals(f.optString("kind","anomaly")))ScreenIntegrityService.findingCommitted(id,f);ScreenIntegrityService.findingsChanged();
    }
    public synchronized JSONObject finding(long id)throws Exception{
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload,first_ms,last_ms,occurrences,reviewed,group_key,kind FROM findings WHERE id=?",new String[]{String.valueOf(id)})){if(c.moveToFirst())return new JSONObject(c.getString(0)).put("id",id).put("anomaly_id",id).put("first_ms",c.getLong(1)).put("last_ms",c.getLong(2)).put("occurrences",c.getLong(3)).put("reviewed",c.getInt(4)!=0).put("origin",c.getString(5).startsWith("live:")?"LIVE_OBSERVATION":"HISTORICAL_REPLAY").put("overlay_eligible",c.getString(5).startsWith("live:")&&"anomaly".equals(c.getString(6)));}
        throw new IllegalArgumentException("Finding absent");
    }
    synchronized JSONArray alerts()throws Exception{
        JSONArray rows=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,payload FROM findings WHERE group_key LIKE 'live:%' AND kind='anomaly' AND reviewed=0 ORDER BY id DESC LIMIT 2",null)){while(c.moveToNext()){JSONObject f=new JSONObject(c.getString(1));rows.put(EventStore.object("id",c.getLong(0),"severity",f.optString("severity"),"title",f.optString("title")));}}return rows;
    }
    synchronized void visual(long id,JSONObject visual)throws Exception{JSONObject f=finding(id);f.put("visual",visual);ContentValues v=new ContentValues();v.put("payload",f.toString());getWritableDatabase().update("findings",v,"id=?",new String[]{String.valueOf(id)});}
    synchronized JSONObject pedigree(JSONObject e,boolean record)throws Exception{
        if(!EndpointContextRules.isNetwork(e))return new JSONObject();JSONObject d=ConvergenceRules.details(e),nc=e.optJSONObject("network_context");JSONArray services=nc==null?null:nc.optJSONArray("services");boolean official=false;
        if(services!=null)for(int i=0;i<services.length();i++){JSONObject s=services.optJSONObject(i);if(s!=null&&"OBSERVED_HOST_MATCH".equals(s.optString("connection_status"))&&"DOCUMENTED_ROLE".equals(s.optString("role_status"))&&!"supporting_service".equals(s.optString("category")))official=true;}
        JSONObject p=ConvergenceRules.profile(e,false,official);String actor=p.optString("actor_key"),host=p.optString("host");String endpoint=host.isEmpty()?"ip:"+d.optString("remote_ip"):host;endpoint+="|"+d.optInt("port")+"|"+d.optString("protocol");
        String flow=d.optString("flow_correlation_id",d.optString("flow_id"));long wall=e.optLong("timestamp_ms",d.optLong("latest_timestamp_ms"));SQLiteDatabase db=getWritableDatabase();long first=0,last=0,sessions=0;Long tx=null,rx=null;
        if(!actor.isEmpty())try(Cursor c=db.rawQuery("SELECT MIN(first_ms),MAX(last_ms),COUNT(*),SUM(tx),SUM(rx) FROM endpoint_sessions WHERE actor=? AND endpoint=?",new String[]{actor,endpoint})){if(c.moveToFirst()){first=c.isNull(0)?0:c.getLong(0);last=c.isNull(1)?0:c.getLong(1);sessions=c.getLong(2);tx=c.isNull(3)?null:c.getLong(3);rx=c.isNull(4)?null:c.getLong(4);}}
        long actorSessions=0;if(!actor.isEmpty())try(Cursor c=db.rawQuery("SELECT COUNT(DISTINCT flow) FROM endpoint_sessions WHERE actor=?",new String[]{actor})){if(c.moveToFirst())actorSessions=c.getLong(0);}
        p=ConvergenceRules.profile(e,first>0&&first<wall,official);p.put("actor_sessions",actorSessions).put("first_observed_ms",first>0?first:wall).put("last_observed_ms",Math.max(last,wall)).put("sessions",sessions).put("tx_bytes",tx==null?JSONObject.NULL:tx).put("rx_bytes",rx==null?JSONObject.NULL:rx);
        if(record&&!actor.isEmpty()&&!flow.isEmpty()&&!"dns".equals(e.optString("category"))){
            Object txValue=d.isNull("tx_bytes")||!d.has("tx_bytes")?null:d.optLong("tx_bytes"),rxValue=d.isNull("rx_bytes")||!d.has("rx_bytes")?null:d.optLong("rx_bytes");
            db.execSQL("INSERT OR IGNORE INTO endpoint_sessions(actor,endpoint,flow,first_ms,last_ms,tx,rx,payload) VALUES(?,?,?,?,?,?,?,?)",new Object[]{actor,endpoint,flow,wall,wall,txValue,rxValue,p.toString()});
            db.execSQL("UPDATE endpoint_sessions SET first_ms=MIN(first_ms,?),last_ms=MAX(last_ms,?),tx=CASE WHEN ? IS NULL THEN tx WHEN tx IS NULL THEN ? ELSE MAX(tx,?) END,rx=CASE WHEN ? IS NULL THEN rx WHEN rx IS NULL THEN ? ELSE MAX(rx,?) END,payload=? WHERE actor=? AND endpoint=? AND flow=?",new Object[]{wall,wall,txValue,txValue,txValue,rxValue,rxValue,rxValue,p.toString(),actor,endpoint,flow});
        }
        e.put("endpoint_pedigree",p);if(nc!=null)nc.put("endpoint_pedigree",p);return p;
    }
    public JSONObject evidence(long id)throws Exception{
        JSONArray ids=null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM findings WHERE id=?",new String[]{String.valueOf(id)})){if(c.moveToFirst())ids=new JSONObject(c.getString(0)).getJSONArray("evidence_ids");}
        if(ids==null)throw new IllegalArgumentException("Signalement absent.");
        JSONArray events=EventStore.get(context).evidence(ids);PermissionUsage.enrich(context,events);
        return EventStore.object("events",events);
    }
    public void exportWatcher(Writer out)throws Exception{
        JSONObject info=summary();info.remove("settings");
        out.write("{\"schema\":\"aiv-watcher-report/1\",\"generated_ms\":"+System.currentTimeMillis()+",\"summary\":"+info+",\"watcher\":[");
        SQLiteDatabase db=getReadableDatabase();long ceiling;
        try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM findings",null)){c.moveToFirst();ceiling=c.getLong(0);}
        long after=0;boolean first=true;
        while(after<ceiling){
            boolean any=false;
            try(Cursor c=db.rawQuery("SELECT id,payload,first_ms,last_ms,occurrences,group_key,kind FROM findings WHERE id>? AND id<=? AND (kind='trace' OR rule='watcher-context') ORDER BY id LIMIT 100",
                new String[]{String.valueOf(after),String.valueOf(ceiling)})){
                while(c.moveToNext()){
                    any=true;after=c.getLong(0);JSONObject row=new JSONObject(c.getString(1));
                    row.put("id",after).put("first_ms",c.getLong(2)).put("last_ms",c.getLong(3)).put("occurrences",c.getLong(4))
                        .put("origin",c.getString(5).startsWith("live:")?"LIVE_OBSERVATION":"HISTORICAL_REPLAY").put("kind",c.getString(6));
                    JSONArray ids=row.optJSONArray("evidence_ids");
                    if(ids!=null){
                        JSONArray events=EventStore.get(context).evidence(ids);
                        row.put("permission_context",PermissionUsage.anomalyContext(context,events))
                           .put("network_context",NetworkReport.anomalyContext(events));
                    }
                    if(!first)out.write(",");out.write(row.toString());first=false;
                }
            }
            if(!any)break;
        }
        out.write("]}");out.flush();
    }

    public void export(Writer out)throws Exception{
        JSONObject info=summary();info.remove("settings");out.write("{\"schema\":\"journal-local-analysis/1\",\"permission_usage_status\":"+PermissionUsage.get(context).status()+",\"summary\":"+info+",\"settings\":"+settingsJson()+",\"findings\":[");
        SQLiteDatabase db=getReadableDatabase();long ceiling;try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM findings",null)){c.moveToFirst();ceiling=c.getLong(0);}
        long revision=info.optLong("revision");long after=0;boolean first=true;
        while(after<ceiling){boolean any=false;try(Cursor c=db.rawQuery("SELECT id,payload,first_ms,last_ms,occurrences,reviewed,group_key FROM findings WHERE id>? AND id<=? AND (group_key LIKE ? OR group_key LIKE 'live:%') ORDER BY id LIMIT 100",new String[]{String.valueOf(after),String.valueOf(ceiling),revision+":%"})){
            while(c.moveToNext()){any=true;after=c.getLong(0);JSONObject row=new JSONObject(c.getString(1));row.put("id",after);row.put("first_ms",c.getLong(2));row.put("last_ms",c.getLong(3));row.put("occurrences",c.getLong(4));row.put("reviewed",c.getInt(5)!=0).put("origin",c.getString(6).startsWith("live:")?"LIVE_OBSERVATION":"HISTORICAL_REPLAY").put("overlay_eligible",c.getString(6).startsWith("live:")&&"anomaly".equals(row.optString("kind")));JSONArray ids=row.optJSONArray("evidence_ids");if(ids!=null){JSONArray events=EventStore.get(context).evidence(ids);row.put("permission_context",PermissionUsage.anomalyContext(context,events)).put("network_context",NetworkReport.anomalyContext(events));}if(!first)out.write(",");out.write(row.toString());first=false;}
        }if(!any)break;}out.write("]}");out.flush();
    }
}
