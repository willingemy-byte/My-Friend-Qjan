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
    private final Context context;
    private final Handler worker;
    private final AtomicBoolean scheduled=new AtomicBoolean();
    private volatile boolean busy;
    private AnomalyRules rules;
    private long settingsRevision=-1;
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
    @Override public void onOpen(SQLiteDatabase db){super.onOpen(db);db.execSQL("CREATE TABLE IF NOT EXISTS analysis_snapshots(revision INTEGER NOT NULL,processed INTEGER NOT NULL,checkpoint INTEGER NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(revision,processed))");}
    public void kick(){if(scheduled.compareAndSet(false,true))worker.postDelayed(this::drain,350);}
    private void drain(){
        busy=true;boolean again=false;
        try{
            SQLiteDatabase db=getWritableDatabase();
            if(rules==null){
                try(Cursor c=db.rawQuery("SELECT payload,settings_revision FROM state WHERE id=1",null)){
                    if(c.moveToFirst()){
                        // Only our private checkpoint is deserialized; exports and imported files never enter here.
                        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(c.getBlob(0)))){rules=(AnomalyRules)in.readObject();}
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
                    for(int i=0;i<events.length();i++)rules.accept(event(events.getJSONObject(i)),settings,f->saveFinding(db,f));
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(rules);}
                    ContentValues state=new ContentValues();state.put("id",1);state.put("payload",bytes.toByteArray());state.put("checkpoint",rules.checkpoint);state.put("processed",rules.processed);state.put("settings_revision",settingsRevision);state.put("unknown_count",rules.unknownAttribution);state.put("uncertain_count",rules.uncertainCounters);
                    db.insertWithOnConflict("state",null,state,SQLiteDatabase.CONFLICT_REPLACE);
                    if(rules.processed>0&&rules.processed%50000==0){ContentValues snapshot=new ContentValues();snapshot.put("revision",settingsRevision);snapshot.put("processed",rules.processed);snapshot.put("checkpoint",rules.checkpoint);snapshot.put("payload",bytes.toByteArray());db.insertWithOnConflict("analysis_snapshots",null,snapshot,SQLiteDatabase.CONFLICT_IGNORE);}
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }
            lastError="";again=EventStore.get(context).latestId()>rules.checkpoint;
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
        e.uid=d.optInt("uid",-1);e.packages=strings(d.optJSONArray("packages"));e.flow=d.optString("flow_id");e.tx=d.optLong("tx_bytes",-1);e.closed=d.optBoolean("closed");e.error=d.optInt("error_code");e.result=d.optString("result");e.port=d.optInt("port");
        e.problem=d.optString("error");e.coverageGap=d.optBoolean("coverage_gap");e.interval=d.optLong("interval_ms");e.query=d.optString("question");if(e.query.isEmpty()&&!d.optBoolean("ech_extension_present")&&"Nom TLS observé".equals(e.action))e.query=d.optString("tls_sni");if(d.has("dns_resolvers"))e.resolvers=strings(d.optJSONArray("dns_resolvers"));return e;
    }
    private void saveFinding(SQLiteDatabase db,AnomalyRules.Finding f){
        try{
            f.key=settingsRevision+":"+f.key;
            long id=0,first=f.wall,occurrences=0;JSONObject old=null;
            try(Cursor c=db.rawQuery("SELECT id,first_ms,occurrences,payload FROM findings WHERE group_key=?",new String[]{f.key})){
                if(c.moveToFirst()){id=c.getLong(0);first=c.getLong(1);occurrences=c.getLong(2);old=new JSONObject(c.getString(3));}
            }
            LinkedHashSet<Long> ids=new LinkedHashSet<>();
            if(old!=null){JSONArray previous=old.optJSONArray("evidence_ids");if(previous!=null)for(int i=0;i<previous.length();i++)ids.add(previous.getLong(i));}
            ids.addAll(f.evidence);List<Long> bounded=new ArrayList<>(ids);
            // A bounded sample links back to original rows. Never present it as all contributing events.
            while(bounded.size()>12)bounded.remove(1);
            JSONObject facts=new JSONObject();for(Map.Entry<String,String> part:f.facts.entrySet())facts.put(part.getKey(),part.getValue());
            JSONObject payload=EventStore.object("rule",f.rule,"kind",f.type,"severity",f.severity,"title",f.title,"actor",f.actor,"subject",f.subject,"explanation",f.explanation,"advice",f.advice,"facts",facts,"evidence_ids",new JSONArray(bounded),"criteria_version","journal-local/snapshots-1","settings_revision",settingsRevision);
            ContentValues values=new ContentValues();values.put("group_key",f.key);values.put("kind",f.type);values.put("rule",f.rule);values.put("first_ms",first);values.put("last_ms",f.wall);values.put("last_event_id",f.eventId);values.put("occurrences",occurrences+1);values.put("payload",payload.toString());
            if(id==0)db.insertOrThrow("findings",null,values);else db.update("findings",values,"id=?",new String[]{String.valueOf(id)});
        }catch(Exception e){throw new IllegalStateException("Signalement non enregistré",e);}
    }
    private long persistedRevision(SQLiteDatabase db){try(Cursor c=db.rawQuery("SELECT settings_revision FROM state WHERE id=1",null)){return c.moveToFirst()?c.getLong(0):-1;}}
    public synchronized JSONObject settingsJson()throws Exception{
        String saved=context.getSharedPreferences("analysis",Context.MODE_PRIVATE).getString("settings",null);
        if(saved!=null){JSONObject config=new JSONObject(saved);
            if(!"snapshots/1".equals(config.optString("engine"))){config.put("revision",config.optLong("revision")+1).put("engine","snapshots/1").put("replay_target",EventStore.get(context).latestId());if(!context.getSharedPreferences("analysis",0).edit().putString("settings",config.toString()).commit())throw new IOException("Migration des réglages non enregistrée");}
            return config;}
        JSONObject initial=EventStore.object("failures",true,"volume",true,"dns",true,"collection",true,"watch",true,"research",true,"failure_count",8,"upload_mib",10,"domains",new JSONArray(),"revision",0,"quiet",true,"engine","snapshots/1","replay_target",EventStore.get(context).latestId());
        if(!context.getSharedPreferences("analysis",0).edit().putString("settings",initial.toString()).commit())throw new IOException("Paramètres initiaux non enregistrés");return initial;
    }
    private AnomalyRules.Settings parseSettings(JSONObject j){
        AnomalyRules.Settings s=new AnomalyRules.Settings();s.failures=j.optBoolean("failures",true);s.volume=j.optBoolean("volume",true);s.dns=j.optBoolean("dns",true);s.collection=j.optBoolean("collection",true);s.watch=j.optBoolean("watch",true);s.research=j.optBoolean("research",true);s.failureCount=j.optInt("failure_count",8);s.uploadMiB=j.optInt("upload_mib",10);s.domains=strings(j.optJSONArray("domains"));s.validate();return s;
    }
    public synchronized JSONObject change(String action,String value)throws Exception{
        if(value==null||value.length()>16000)throw new IllegalArgumentException("Paramètres trop longs.");
        if("settings".equals(action)){
            JSONObject input=new JSONObject(value);AnomalyRules.Settings s=parseSettings(input);JSONObject saved=EventStore.object("failures",s.failures,"volume",s.volume,"dns",s.dns,"collection",s.collection,"watch",s.watch,"research",s.research,"failure_count",s.failureCount,"upload_mib",s.uploadMiB,"domains",new JSONArray(Arrays.asList(s.domains)),"quiet",input.optBoolean("quiet",true),"revision",settingsJson().optLong("revision")+1);
            JSONObject previous=settingsJson();saved.put("engine","snapshots/1");
            boolean changed=false;for(String key:new String[]{"failures","volume","dns","collection","watch","research","failure_count","upload_mib","domains"})if(!String.valueOf(previous.opt(key)).equals(String.valueOf(saved.opt(key))))changed=true;
            saved.put("revision",previous.optLong("revision")+(changed?1:0));saved.put("replay_target",changed?EventStore.get(context).latestId():previous.optLong("replay_target",0));
            if(!context.getSharedPreferences("analysis",Context.MODE_PRIVATE).edit().putString("settings",saved.toString()).commit())throw new IOException("Paramètres non enregistrés.");
            kick();return EventStore.object("ok",true,"settings",saved);
        }
        if("review".equals(action)){
            long id=Long.parseLong(value);if(id<=0)throw new IllegalArgumentException("Signalement invalide.");
            // Reviewed groups remain reviewed if more repetitions arrive; new time groups are unread.
            ContentValues values=new ContentValues();values.put("reviewed",1);getWritableDatabase().update("findings",values,"id=?",new String[]{String.valueOf(id)});return EventStore.object("ok",true);
        }
        if("retry".equals(action)){kick();return EventStore.object("ok",true);}
        throw new IllegalArgumentException("Action inconnue.");
    }
    public synchronized JSONObject summary()throws Exception{
        long checkpoint=0,processed=0,unread=0,traces=0,total=0,unknown=0,uncertain=0;
        SQLiteDatabase db=getReadableDatabase();JSONObject config=settingsJson();long revision=config.optLong("revision");
        try(Cursor c=db.rawQuery("SELECT checkpoint,processed,unknown_count,uncertain_count FROM state WHERE id=1 AND settings_revision=?",new String[]{""+revision})){if(c.moveToFirst()){checkpoint=c.getLong(0);processed=c.getLong(1);unknown=c.getLong(2);uncertain=c.getLong(3);}}
        try(Cursor c=db.rawQuery("SELECT kind,reviewed,COUNT(*) FROM findings WHERE group_key LIKE ? GROUP BY kind,reviewed",new String[]{revision+":%"})){while(c.moveToNext()){if(c.getString(0).equals("trace"))traces+=c.getLong(2);else {total+=c.getLong(2);if(c.getInt(1)==0)unread+=c.getLong(2);}}}
        return EventStore.object("revision",revision,"replay_target",config.optLong("replay_target",0),"recalculating",persistedRevision(db)!=revision||checkpoint<config.optLong("replay_target",0),"processed",processed,"checkpoint",checkpoint,"latest",EventStore.get(context).latestId(),"busy",busy||scheduled.get(),"unread",unread,"anomalies",total,"traces",traces,"unknown_attribution",unknown,"uncertain_counters",uncertain,"error",lastError,"settings",settingsJson());
    }
    public synchronized JSONObject page(String kind,boolean unread,int offset)throws Exception{
        if(!kind.equals("trace")&&!kind.equals("anomaly"))throw new IllegalArgumentException("Vue inconnue.");
        JSONObject status=summary();if(status.optBoolean("recalculating"))return EventStore.object("rows",new JSONArray(),"total",JSONObject.NULL,"recalculating",true,"checkpoint",status.optLong("checkpoint"),"target",status.optLong("replay_target"));
        String revision=status.getLong("revision")+":%";
        offset=Math.max(0,offset);String where="group_key LIKE ? AND kind=?"+(unread?" AND reviewed=0":"");SQLiteDatabase db=getReadableDatabase();long count;
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM findings WHERE "+where,new String[]{revision,kind})){c.moveToFirst();count=c.getLong(0);}
        JSONArray rows=new JSONArray();try(Cursor c=db.rawQuery("SELECT id,first_ms,last_ms,occurrences,reviewed,payload FROM findings WHERE "+where+" ORDER BY id DESC LIMIT 15 OFFSET ?",new String[]{revision,kind,String.valueOf(offset)})){
            while(c.moveToNext()){JSONObject row=new JSONObject(c.getString(5));row.put("id",c.getLong(0));row.put("first_ms",c.getLong(1));row.put("last_ms",c.getLong(2));row.put("occurrences",c.getLong(3));row.put("reviewed",c.getInt(4)!=0);rows.put(row);}
        }
        for(int i=0;i<rows.length();i++){
            JSONObject item=rows.getJSONObject(i);JSONArray ids=item.optJSONArray("evidence_ids");
            if(ids==null)continue;JSONArray events=EventStore.get(context).evidence(ids);JSONObject identity=null;String key=null;boolean mixed=false;
            for(int j=0;j<events.length();j++){JSONObject event=events.getJSONObject(j),d=event.optJSONObject("details");if(d==null||d.optInt("uid",-1)<0){mixed=true;break;}
                String current=d.optInt("uid")+":"+String.valueOf(d.optJSONArray("packages"));if(key!=null&&!key.equals(current)){mixed=true;break;}key=current;identity=EventStore.object("app",event.optString("app"),"details",d);
            }
            if(!mixed&&identity!=null)item.put("identity",identity);
        }
        return EventStore.object("rows",rows,"total",count);
    }
    public JSONObject evidence(long id)throws Exception{
        JSONArray ids=null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM findings WHERE id=?",new String[]{String.valueOf(id)})){if(c.moveToFirst())ids=new JSONObject(c.getString(0)).getJSONArray("evidence_ids");}
        if(ids==null)throw new IllegalArgumentException("Signalement absent.");
        return EventStore.object("events",EventStore.get(context).evidence(ids));
    }
    public void export(Writer out)throws Exception{
        JSONObject info=summary();info.remove("settings");out.write("{\"schema\":\"journal-local-analysis/1\",\"summary\":"+info+",\"settings\":"+settingsJson()+",\"findings\":[");
        SQLiteDatabase db=getReadableDatabase();long ceiling;try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM findings",null)){c.moveToFirst();ceiling=c.getLong(0);}
        long after=0;boolean first=true;
        while(after<ceiling){boolean any=false;try(Cursor c=db.rawQuery("SELECT id,payload,first_ms,last_ms,occurrences,reviewed FROM findings WHERE id>? AND id<=? ORDER BY id LIMIT 100",new String[]{String.valueOf(after),String.valueOf(ceiling)})){
            while(c.moveToNext()){any=true;after=c.getLong(0);JSONObject row=new JSONObject(c.getString(1));row.put("id",after);row.put("first_ms",c.getLong(2));row.put("last_ms",c.getLong(3));row.put("occurrences",c.getLong(4));row.put("reviewed",c.getInt(5)!=0);if(!first)out.write(",");out.write(row.toString());first=false;}
        }if(!any)break;}out.write("]}");out.flush();
    }
}