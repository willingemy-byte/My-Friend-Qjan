package fr.erick.journallocal;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.Writer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;

public final class EventStore extends SQLiteOpenHelper {
    private static EventStore instance;
    private static final String PROCESS_SESSION=java.util.UUID.randomUUID().toString();
    private final Context context;
    public static volatile String lastError = "";
    public static synchronized EventStore get(Context context) {
        if (instance == null) instance = new EventStore(context.getApplicationContext());
        return instance;
    }
    private EventStore(Context context) { super(context, "journal.sqlite", null, 1); this.context=context;setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db){super.onConfigure(db);db.setForeignKeyConstraintsEnabled(true);db.execSQL("PRAGMA synchronous=FULL");}
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp_ms INTEGER NOT NULL, app TEXT NOT NULL, action TEXT NOT NULL, destination TEXT NOT NULL, transport TEXT NOT NULL, category TEXT NOT NULL, payload TEXT NOT NULL, search_text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX events_category ON events(category,id)");
        db.execSQL("CREATE INDEX events_time ON events(timestamp_ms,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Migration du journal requise; les données sont conservées."); }
    private final java.util.concurrent.atomic.AtomicBoolean auditBusy=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String auditError="";
    private volatile long transparencyCacheAt=0;
    private volatile JSONObject transparencyCache=null;
    @Override public void onOpen(SQLiteDatabase db){
        super.onOpen(db);
        AivStore.install(context,db);JournalSegments.install(db);JournalPurge.install(db);
        db.execSQL("CREATE TABLE IF NOT EXISTS event_audit(event_id INTEGER PRIMARY KEY, package_name TEXT, uid INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS event_audit_package ON event_audit(package_name,event_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS audit_appmap(package_name TEXT PRIMARY KEY, system_app INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS audit_state(id INTEGER PRIMARY KEY, checkpoint INTEGER, ready INTEGER DEFAULT 0)");
        db.execSQL("INSERT OR IGNORE INTO audit_state(id,checkpoint) VALUES(1,0)");
    }
    public void setAuditInventory(JSONArray apps)throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            db.delete("audit_appmap",null,null);
            for(int i=0;i<apps.length();i++){JSONObject a=apps.getJSONObject(i);ContentValues v=new ContentValues();v.put("package_name",a.getString("package_name"));v.put("system_app",a.getBoolean("system_app")?1:0);db.insertOrThrow("audit_appmap",null,v);}
            db.execSQL("UPDATE audit_state SET ready=1 WHERE id=1");db.setTransactionSuccessful();
        }finally{db.endTransaction();}requestAuditIndex();
    }
    private long auditCheckpoint(){try(Cursor c=getReadableDatabase().rawQuery("SELECT checkpoint FROM audit_state WHERE id=1",null)){c.moveToFirst();return c.getLong(0);}}
    // One indexed existence lookup per inventoried package, not a full log scan per app.
    static final String COHERENCE_PACKAGES_SQL="SELECT am.package_name FROM audit_appmap am WHERE EXISTS (SELECT 1 FROM event_audit ea WHERE ea.package_name=am.package_name AND ea.uid>=0 AND (ea.uid % 100000)>=10000) ORDER BY am.package_name";
    public JSONObject coherenceAttribution(){
        requestAuditIndex();
        long ceiling=latestId(),checkpoint=auditCheckpoint();JSONArray packages=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery(COHERENCE_PACKAGES_SQL,null)){
            while(c.moveToNext())packages.put(c.getString(0));
        }
        return object("packages",packages,"indexed_through_id",checkpoint,"latest_event_id",ceiling,
            "complete",checkpoint>=ceiling&&auditError.isEmpty(),"indexing",auditBusy.get(),"error",auditError);
    }
    private void requestAuditIndex(){
        if(auditCheckpoint()>=latestId())return;
        if(!auditBusy.compareAndSet(false,true))return;
        new Thread(()->{try{
            long ceiling=latestId(),at=auditCheckpoint();SQLiteDatabase db=getWritableDatabase();auditError="";
            while(at<ceiling){JSONArray batch=analysisBatch(at,200);if(batch.length()==0)break;db.beginTransaction();try{
                for(int i=0;i<batch.length();i++){JSONObject e=batch.getJSONObject(i);long id=e.getLong("id");if(id>ceiling)break;
                    JSONObject d=e.optJSONObject("details");int uid=d==null?-1:d.optInt("uid",-1);JSONArray ps=d==null?null:d.optJSONArray("packages");String pkg=null;
                    String candidate=ps==null?null:ps.optString(0,null);
                    if(CoherenceGroups.attributable(e.optString("category"),uid,ps==null?0:ps.length(),candidate))pkg=candidate;
                    ContentValues v=new ContentValues();v.put("event_id",id);v.put("package_name",pkg);v.put("uid",uid);db.insertWithOnConflict("event_audit",null,v,SQLiteDatabase.CONFLICT_REPLACE);at=id;
                }db.execSQL("UPDATE audit_state SET checkpoint=? WHERE id=1",new Object[]{at});db.setTransactionSuccessful();
            }finally{db.endTransaction();}}
        }catch(Exception e){auditError="Index partiel : "+e.getClass().getSimpleName();}finally{auditBusy.set(false);}},"journal-audit-index").start();
    }
    public static JSONObject object(Object... parts) {
        JSONObject out = new JSONObject();
        try { for(int i=0;i<parts.length;i+=2) out.put(String.valueOf(parts[i]), parts[i+1]); }
        catch(Exception e) { throw new IllegalArgumentException(e); }
        return out;
    }
    public synchronized boolean add(String category, String actor, String action, String destination, String transport, String source, JSONObject details) {
        return addObserved(category,actor,action,destination,transport,source,details,System.currentTimeMillis(),SystemClock.elapsedRealtime());
    }
    public synchronized boolean addObserved(String category,String actor,String action,String destination,String transport,String source,JSONObject details,long observedMs,long observedElapsedMs) {
        try {
            long storedMs=System.currentTimeMillis();
            long now=observedMs>0?observedMs:storedMs;
            JSONObject event = object("event_version",2,"collector_process_session",PROCESS_SESSION,"timestamp", Instant.ofEpochMilli(now).toString(), "timestamp_ms", now,
                "elapsed_ms",observedElapsedMs>=0?observedElapsedMs:SystemClock.elapsedRealtime(),"persisted_at_ms",storedMs, "app", actor, "action", action,
                "destination", destination, "transport", transport, "category", category,
                "source", source, "scope", ("trafic".equals(category)||"dns".equals(category)) ? "Métadonnées réseau observées par le VPN local; contenu des échanges non enregistré" : "Événement ou état fourni par une API Android; aucune capture du contenu des autres applications", "details", details);
            event.put("wall_ms",now).put("clock_scope_id",PROCESS_SESSION);
            if("frontend".equals(category))event.put("scope","Snapshot sémantique borné; champs éditables et mots de passe masqués; aucune archive d’écran.");
            for(String key:new String[]{"protocol","port","bytes","direction","result"}) if(details.has(key)) event.put(key,details.get(key));
            ContentValues values = new ContentValues();
            values.put("timestamp_ms",now);values.put("app",actor);values.put("action",action);values.put("destination",destination);values.put("transport",transport);values.put("category",category);
            values.put("payload",event.toString());values.put("search_text",event.toString().toLowerCase(Locale.ROOT));
            long rowId=getWritableDatabase().insertOrThrow("events",null,values);event.put("id",rowId);
            // Analysis errors have their own status and must never stop successful source recording.
            try{AnomalyMonitor.observe(context,event);}catch(Exception ignored){}
            try{AnomalyMonitor.request(context);JournalSegments.request(context);TrackerIndex.get(context).request();}catch(Exception ignored){}
            return true;
        } catch(Exception e) { lastError = "Écriture du journal impossible : " + e.getClass().getSimpleName(); return false; }
    }
    static String clockScope(){return PROCESS_SESSION;}
    private static String literalLike(String text) { return text.replace("\\","\\\\").replace("%","\\%").replace("_","\\_"); }
    public synchronized JSONObject page(String search, String transport, int offset, int limit, long beforeId, String actor, String kind, boolean quiet) throws Exception {
        return pageFiltered(search,transport,offset,limit,beforeId,actor,kind,quiet,"","");
    }
    public synchronized JSONObject pageFiltered(String search,String transport,int offset,int limit,long beforeId,String actor,String kind,boolean quiet,String scope,String pkg)throws Exception{
        limit=Math.max(1,Math.min(500,limit));offset=Math.max(0,offset);
        SQLiteDatabase db=getReadableDatabase();
        long total, maxId;
        try(Cursor c=db.rawQuery("SELECT COUNT(*),COALESCE(MAX(id),0) FROM events",null)){c.moveToFirst();total=c.getLong(0);maxId=c.getLong(1);}
        long ceiling=beforeId>0?Math.min(beforeId,maxId):maxId;
        String where="id <= ?";ArrayList<String> args=new ArrayList<>();args.add(String.valueOf(ceiling));
        if(search!=null && !search.trim().isEmpty()){String term=search.trim(),lower=term.toLowerCase(Locale.ROOT);if(term.matches("\\d+")){where+=" AND (id=? OR search_text LIKE ? ESCAPE '\\')";args.add(term);args.add("%"+literalLike(lower)+"%");}else{where+=" AND search_text LIKE ? ESCAPE '\\'";args.add("%"+literalLike(lower)+"%");}}
        if(transport!=null && !transport.isEmpty()){where+=" AND transport = ?";args.add(transport);}
        if(actor!=null && !actor.isEmpty()){where+=" AND app = ?";args.add(actor);}
        if("apps".equals(kind))where+=" AND category IN ('trafic','dns')";
        if("system".equals(kind))where+=" AND category NOT IN ('trafic','dns')";
        if((scope!=null&&!scope.isEmpty())||(pkg!=null&&!pkg.isEmpty())){
            requestAuditIndex();
            if(pkg!=null&&!pkg.isEmpty()){where+=" AND id IN (SELECT event_id FROM event_audit WHERE package_name=?)";args.add(pkg);}
            if("system".equals(scope)||"other".equals(scope)){where+=" AND id IN (SELECT ea.event_id FROM event_audit ea JOIN audit_appmap am ON ea.package_name=am.package_name WHERE am.system_app=?)";args.add("system".equals(scope)?"1":"0");}
            if("unknown".equals(scope))where+=" AND category IN ('trafic','dns') AND id IN (SELECT event_id FROM event_audit WHERE package_name IS NULL)";
        }
        JSONArray actors=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT DISTINCT app FROM events ORDER BY app COLLATE NOCASE LIMIT 1000",null)){while(c.moveToNext())actors.put(c.getString(0));}
        long matched,unmasked;
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM events WHERE "+where,args.toArray(new String[0]))){c.moveToFirst();unmasked=c.getLong(0);}
        boolean quietEffective=quiet&&(search==null||search.trim().isEmpty());
        if(quietEffective)where+=" AND NOT ((category='collecteur' AND action='Signal de vie') OR (category='reseau' AND action IN ('Compteurs réseau globaux','Lecture des compteurs réseau Android')) OR action='État de batterie reçu')";
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM events WHERE "+where,args.toArray(new String[0]))){c.moveToFirst();matched=c.getLong(0);}
        ArrayList<String> queryArgs=new ArrayList<>(args);queryArgs.add(String.valueOf(limit));queryArgs.add(String.valueOf(offset));
        JSONArray events=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT id,payload FROM events WHERE "+where+" ORDER BY id DESC LIMIT ? OFFSET ?",queryArgs.toArray(new String[0]))){
            while(c.moveToNext()){JSONObject event=new JSONObject(c.getString(1));event.put("id",c.getLong(0));events.put(event);}
        }
        return object("actors",actors,"events",events,"total",total,"matched",matched,"ceiling_id",ceiling,"latest_id",maxId,"offset",offset,"limit",limit,"quiet_effective",quietEffective,"hidden_count",unmasked-matched,"audit_index_busy",auditBusy.get(),"audit_index_checkpoint",auditCheckpoint(),"audit_index_error",auditError);
    }

    public JSONObject pageSegment(String search,String transport,int offset,int limit,long beforeId,String actor,String kind,boolean quiet,String scope,String pkg,int selectedSegment)throws Exception{
        limit=Math.max(1,Math.min(500,limit));offset=Math.max(0,offset);
        SQLiteDatabase db=getReadableDatabase();
        JSONObject segment=JournalSegments.window(context,selectedSegment);
        long total=segment.getLong("total"),maxId=segment.getLong("latest"),first=segment.getLong("first_id"),last=segment.getLong("last_id");
        long ceiling=beforeId>0?Math.min(beforeId,last):last;
        String where="id >= ? AND id <= ?";ArrayList<String> args=new ArrayList<>();args.add(String.valueOf(first));args.add(String.valueOf(ceiling));
        if(search!=null && !search.trim().isEmpty()){String term=search.trim(),lower=term.toLowerCase(Locale.ROOT);if(term.matches("\\d+")){where+=" AND (id=? OR search_text LIKE ? ESCAPE '\\')";args.add(term);args.add("%"+literalLike(lower)+"%");}else{where+=" AND search_text LIKE ? ESCAPE '\\'";args.add("%"+literalLike(lower)+"%");}}
        if(transport!=null && !transport.isEmpty()){where+=" AND transport = ?";args.add(transport);}
        if(actor!=null && !actor.isEmpty()){where+=" AND app = ?";args.add(actor);}
        if("apps".equals(kind))where+=" AND category IN ('trafic','dns')";
        if("system".equals(kind))where+=" AND category NOT IN ('trafic','dns')";
        if((scope!=null&&!scope.isEmpty())||(pkg!=null&&!pkg.isEmpty())){
            requestAuditIndex();
            if(pkg!=null&&!pkg.isEmpty()){where+=" AND id IN (SELECT event_id FROM event_audit WHERE package_name=?)";args.add(pkg);}
            if("system".equals(scope)||"other".equals(scope)){where+=" AND id IN (SELECT ea.event_id FROM event_audit ea JOIN audit_appmap am ON ea.package_name=am.package_name WHERE am.system_app=?)";args.add("system".equals(scope)?"1":"0");}
            if("unknown".equals(scope))where+=" AND category IN ('trafic','dns') AND id IN (SELECT event_id FROM event_audit WHERE package_name IS NULL)";
        }
        JSONArray actors=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT DISTINCT app FROM events WHERE id>=? AND id<=? ORDER BY app COLLATE NOCASE LIMIT 1000",new String[]{""+first,""+ceiling})){while(c.moveToNext())actors.put(c.getString(0));}
        long matched,unmasked;
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM events WHERE "+where,args.toArray(new String[0]))){c.moveToFirst();unmasked=c.getLong(0);}
        boolean quietEffective=quiet&&(search==null||search.trim().isEmpty());
        if(quietEffective)where+=" AND NOT ((category='collecteur' AND action='Signal de vie') OR (category='reseau' AND action IN ('Compteurs réseau globaux','Lecture des compteurs réseau Android')) OR action='État de batterie reçu')";
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM events WHERE "+where,args.toArray(new String[0]))){c.moveToFirst();matched=c.getLong(0);}
        ArrayList<String> queryArgs=new ArrayList<>(args);queryArgs.add(String.valueOf(limit));queryArgs.add(String.valueOf(offset));
        JSONArray events=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT id,payload FROM events WHERE "+where+" ORDER BY id DESC LIMIT ? OFFSET ?",queryArgs.toArray(new String[0]))){
            while(c.moveToNext()){JSONObject event=new JSONObject(c.getString(1));event.put("id",c.getLong(0));events.put(event);}
        }
        return object("segment",segment,"total_partial",segment.getBoolean("partial"),"actors",actors,"events",events,"total",total,"matched",matched,"ceiling_id",ceiling,"latest_id",maxId,"offset",offset,"limit",limit,"quiet_effective",quietEffective,"hidden_count",unmasked-matched,"audit_index_busy",auditBusy.get(),"audit_index_checkpoint",auditCheckpoint(),"audit_index_error",auditError);
    }

    /** Bounded flow projection over the existing VPN journal. No second network log is created. */
    public JSONObject flowPage(String search,long beforeId,int limit)throws Exception{
        return TrackerIndex.get(context).flows(search,beforeId,limit);
    }

    public synchronized JSONObject transparencySummary(){
        requestAuditIndex();long now=SystemClock.elapsedRealtime();
        if(transparencyCache!=null&&now-transparencyCacheAt<15000L){
            try{return new JSONObject(transparencyCache.toString()).put("cached",true);}catch(Exception ignored){}
        }
        SQLiteDatabase db=getReadableDatabase();long checkpoint=auditCheckpoint(),latest=latestId(),network=0,unresolved=0;
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM events WHERE category IN ('trafic','dns')",null)){c.moveToFirst();network=c.getLong(0);}
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM events e LEFT JOIN event_audit ea ON ea.event_id=e.id WHERE e.category IN ('trafic','dns') AND ea.package_name IS NULL",null)){c.moveToFirst();unresolved=c.getLong(0);}
        JSONObject result=object("network_events",network,"non_attributable",unresolved,"attributable",Math.max(0,network-unresolved),"indexed_through_id",checkpoint,"latest_event_id",latest,"complete",checkpoint>=latest&&!auditBusy.get()&&auditError.isEmpty(),"indexing",auditBusy.get(),"error",auditError,"cached",false,"cache_ms",15000);
        transparencyCache=result;transparencyCacheAt=now;return result;
    }

    public long latestId(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(MAX(id),0) FROM events",null)){c.moveToFirst();return c.getLong(0);}}
    public JSONArray analysisBatch(long after,int limit)throws Exception{
        JSONArray result=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,payload FROM events WHERE id>? ORDER BY id LIMIT ?",new String[]{String.valueOf(after),String.valueOf(Math.max(1,Math.min(limit,200)))})){
            while(c.moveToNext()){JSONObject event=new JSONObject(c.getString(1));event.put("id",c.getLong(0));result.put(event);}
        }return result;
    }
    public JSONArray evidence(JSONArray ids)throws Exception{
        if(ids.length()>12)throw new IllegalArgumentException("Trop de références.");JSONArray result=new JSONArray();
        for(int i=0;i<ids.length();i++)try(Cursor c=getReadableDatabase().rawQuery("SELECT id,payload FROM events WHERE id=?",new String[]{String.valueOf(ids.getLong(i))})){
            if(c.moveToFirst()){JSONObject event=new JSONObject(c.getString(1));event.put("id",c.getLong(0));result.put(event);}
        }return result;
    }
    public void export(Writer writer)throws Exception{export(writer,false);}
    public void export(Writer writer,boolean jsonl)throws Exception{
        SnapshotExporter.write(writer,jsonl,exportSource());
    }
    public void exportDatabase(java.io.File destination)throws Exception{SQLiteSnapshot.write(destination,exportSource());}
    private SnapshotExporter.Source exportSource(){
        SQLiteDatabase db=getReadableDatabase();return new SnapshotExporter.Source(){
            public long[] snapshot(){try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(id),0),COUNT(*) FROM events",null)){c.moveToFirst();return new long[]{c.getLong(0),c.getLong(1)};}}
            public java.util.List<String> page(long after,long ceiling)throws Exception{
                java.util.List<String> result=new java.util.ArrayList<>();try(Cursor c=db.rawQuery("SELECT id,payload FROM events WHERE id > ? AND id <= ? ORDER BY id ASC LIMIT 200",new String[]{String.valueOf(after),String.valueOf(ceiling)})){while(c.moveToNext()){JSONObject event=new JSONObject(c.getString(1));event.put("id",c.getLong(0));result.add(event.toString());}}return result;
            }
        };
    }
}
