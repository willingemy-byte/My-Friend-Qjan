package fr.erick.journallocal;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** All authoritative AIV writes use the EventStore database and its writer transaction. */
public final class AivStore {
    public static volatile String error="", verification="NOT_CHECKED";
    private AivStore(){}
    public static void install(Context context,SQLiteDatabase db){
        db.beginTransaction();try{
            boolean prev=false,self=false;try(Cursor c=db.rawQuery("PRAGMA table_info(events)",null)){while(c.moveToNext()){prev|="hash_prev".equals(c.getString(1));self|="hash_self".equals(c.getString(1));}}
            if(!prev)db.execSQL("ALTER TABLE events ADD COLUMN hash_prev TEXT");
            if(!self)db.execSQL("ALTER TABLE events ADD COLUMN hash_self TEXT");
            try(BufferedReader r=new BufferedReader(new InputStreamReader(context.getAssets().open("aiv-schema.sql"),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)if(!line.trim().isEmpty())db.execSQL(line);}
            db.execSQL("INSERT OR IGNORE INTO aiv_state(id,schema_version,checkpoint,legacy_ceiling,chain_count,chain_head) SELECT 1,2,0,COALESCE(MAX(id),0),0,? FROM events",new Object[]{ChainStore.GENESIS});
            db.execSQL("UPDATE aiv_state SET schema_version=3 WHERE id=1 AND schema_version<3");
            for(int i=1;i<=6;i++){
                JSONObject condition=EventStore.object("type","R"+i,"threshold",10485760,"domains",new JSONArray(),"whitelist",new JSONArray(),"whitelist_configured",false);
                db.execSQL("INSERT OR IGNORE INTO main_rules(name,condition,decision,priority,enabled,version,created_ms) VALUES(?,?,?,?,1,1,?)",new Object[]{"R"+i,condition.toString(),i==3||i==4||i==6?"DENIED":"WATCH",i==6?600:i==3?500:i==4?400:i==5?300:i==1?200:100,System.currentTimeMillis()});
            }
            for(String source:new String[]{"bayton","exodus"})db.execSQL("INSERT OR IGNORE INTO sync_state(source,status) VALUES(?,'OFFLINE_NO_SYNC')",new Object[]{source});
            db.setTransactionSuccessful();
        }catch(Exception e){throw new IllegalStateException("Migration AIV annulée",e);}finally{db.endTransaction();}
    }
    public static String eventPayload(Cursor c){
        String[] f=new String[c.getColumnCount()+1];f[0]="EVENT";for(int i=0;i<c.getColumnCount();i++)f[i+1]=c.isNull(i)?null:c.getString(i);return ChainStore.pack(f);
    }
    static final String EVENT_COLUMNS="id,timestamp_ms,app,action,destination,transport,category,payload,search_text";
    public static long number(SQLiteDatabase db,String sql){try(Cursor c=db.rawQuery(sql,null)){if(!c.moveToFirst())throw new IllegalStateException("État absent");return c.getLong(0);}}
    public static String[] next(SQLiteDatabase db,String payload,long timestamp){
        if(!db.inTransaction())throw new IllegalStateException("Transaction obligatoire");
        try(Cursor c=db.rawQuery("SELECT chain_count,chain_head FROM aiv_state WHERE id=1",null)){c.moveToFirst();long seq=c.getLong(0)+1;String prev=c.getString(1);return new String[]{""+seq,prev,ChainStore.hash(prev,payload,timestamp,seq)};}
    }
    public static void append(SQLiteDatabase db,long eventId,Long decisionId,String kind,String payload,long timestamp,String[] hashes){
        ContentValues v=new ContentValues();v.put("id",Long.valueOf(hashes[0]));v.put("event_id",eventId);v.put("decision_id",decisionId);v.put("kind",kind);v.put("payload",payload);v.put("timestamp_ms",timestamp);v.put("hash_prev",hashes[1]);v.put("hash_self",hashes[2]);
        db.insertOrThrow("journal_chain",null,v);db.execSQL("UPDATE aiv_state SET chain_count=?,chain_head=? WHERE id=1",new Object[]{Long.valueOf(hashes[0]),hashes[2]});
    }
    public static JSONObject row(Cursor c)throws Exception{JSONObject o=new JSONObject();for(int i=0;i<c.getColumnCount();i++)o.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getType(i)==Cursor.FIELD_TYPE_INTEGER?Long.valueOf(c.getLong(i)):c.getString(i));return o;}
    public static JSONObject page(Context ctx,String filter,long before)throws Exception{
        if(!filter.isEmpty()&&!filter.matches("ALLOW|DENIED|WATCH"))throw new IllegalArgumentException("Filtre invalide");
        SQLiteDatabase db=EventStore.get(ctx).getReadableDatabase();JSONArray rows=new JSONArray();long ceiling=before>0?before:Long.MAX_VALUE;
        try(Cursor c=db.rawQuery(filter.isEmpty()?AivQueries.PAGE_ALL:AivQueries.PAGE_FILTER,filter.isEmpty()?new String[]{""+ceiling}:new String[]{filter,""+ceiling})){while(c.moveToNext())rows.put(row(c));}
        JSONArray ids=new JSONArray();for(int i=0;i<rows.length();i++)ids.put(rows.getJSONObject(i).getLong("event_id"));
        JSONArray events=new JSONArray();for(int offset=0;offset<ids.length();offset+=12){JSONArray batch=new JSONArray();for(int j=offset;j<Math.min(ids.length(),offset+12);j++)batch.put(ids.getLong(j));JSONArray found=EventStore.get(ctx).evidence(batch);for(int j=0;j<found.length();j++)events.put(found.get(j));}java.util.HashMap<Long,JSONObject> identities=new java.util.HashMap<>();
        for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);identities.put(e.optLong("id"),EventStore.object("app",e.optString("app"),"details",e.optJSONObject("details")));}
        for(int i=0;i<rows.length();i++){JSONObject item=rows.getJSONObject(i);item.put("identity",identities.get(item.getLong("event_id")));}
        return EventStore.object("rows",rows,"next",rows.length()==50?rows.getJSONObject(49).getLong("id"):0);
    }
    public static JSONObject detail(Context ctx,long eventId)throws Exception{
        SQLiteDatabase db=EventStore.get(ctx).getReadableDatabase();JSONArray chain=new JSONArray();JSONObject event=null,decision=null;
        try(Cursor c=db.rawQuery("SELECT * FROM events WHERE id=?",new String[]{""+eventId})){if(c.moveToFirst())event=row(c);}
        try(Cursor c=db.rawQuery("SELECT * FROM decisions WHERE event_id=?",new String[]{""+eventId})){if(c.moveToFirst())decision=row(c);}
        try(Cursor c=db.rawQuery("SELECT * FROM journal_chain WHERE event_id=? ORDER BY id",new String[]{""+eventId})){while(c.moveToNext())chain.put(row(c));}
        return EventStore.object("event",event==null?JSONObject.NULL:event,"decision",decision==null?JSONObject.NULL:decision,"chain",chain,"verification",verification,"signature_scope","Aucune signature individuelle; export signé séparément");
    }
    public static JSONObject summary(Context ctx)throws Exception{
        SQLiteDatabase db=EventStore.get(ctx).getReadableDatabase();JSONArray apps=new JSONArray(),sources=new JSONArray(),rules=new JSONArray();
        try(Cursor c=db.rawQuery(AivQueries.STATS_APPS,null)){while(c.moveToNext()){JSONObject o=row(c);o.put("ratio",c.getDouble(3));apps.put(o);}}
        try(Cursor c=db.rawQuery("SELECT s.*, (SELECT COUNT(*) FROM reference_apps r WHERE r.source=s.source) AS apps FROM sync_state s ORDER BY s.source",null)){while(c.moveToNext())sources.put(row(c));}
        try(Cursor c=db.rawQuery("SELECT * FROM main_rules r WHERE version=(SELECT MAX(version) FROM main_rules WHERE name=r.name) ORDER BY priority DESC,name",null)){while(c.moveToNext())rules.put(row(c));}
        long checkpoint,latestEvent,latestDecision,statsAt,evaluated,findings;
        try(Cursor c=db.rawQuery(AivQueries.PROGRESS,null)){c.moveToFirst();checkpoint=c.getLong(0);latestEvent=c.getLong(3);latestDecision=c.getLong(4);}
        try(Cursor c=db.rawQuery(AivQueries.STATS_STATE,null)){c.moveToFirst();statsAt=c.getLong(0);evaluated=c.getLong(1);findings=c.getLong(2);}
        return EventStore.object("apps",apps,"sources",sources,"rules",rules,"pending",JSONObject.NULL,"checkpoint",checkpoint,"latest_event_id",latestEvent,"stats_checkpoint",statsAt,"stats_evaluated",evaluated,"stats_complete",statsAt>=latestDecision,"findings",findings,"watcher_running",WatcherService.running,"analysis_active",WatcherService.analysisActive,"operation",WatcherService.operation,"error",error,"verification",verification,"signature","EXPORT_ONLY","appops","OTHER_APPS_NOT_ACCESSIBLE","enforcement","NOT_ENFORCED");
    }
    /** Full check in one snapshot. Never repair or reseal a broken chain. */
    public static JSONObject verify(Context ctx)throws Exception{
        SQLiteDatabase db=EventStore.get(ctx).getWritableDatabase();db.beginTransaction();try{
            long n=0;String prev=ChainStore.GENESIS;String failure="";
            try(Cursor c=db.rawQuery("SELECT * FROM journal_chain ORDER BY id",null)){while(c.moveToNext()){
                JSONObject r=row(c);long seq=r.getLong("id"),eventId=r.getLong("event_id"),ts=r.getLong("timestamp_ms");String p=r.getString("payload");
                if(seq!=++n||!prev.equals(r.getString("hash_prev"))||!ChainStore.hash(prev,p,ts,seq).equals(r.getString("hash_self"))){failure="Chaîne altérée à "+seq;break;}
                String expected=null,projectionPrev=null,projectionSelf=null;
                if(r.getString("kind").equals("EVENT"))try(Cursor e=db.rawQuery("SELECT "+EVENT_COLUMNS+" FROM events WHERE id=?",new String[]{""+eventId})){if(e.moveToFirst())expected=eventPayload(e);}
                else try(Cursor d=db.rawQuery("SELECT * FROM decisions WHERE id=? AND event_id=?",new String[]{r.getString("decision_id"),""+eventId})){if(d.moveToFirst())expected=MainEngine.decisionPayload(row(d));}
                String table=r.getString("kind").equals("EVENT")?"events":"decisions";
                try(Cursor e=db.rawQuery("SELECT hash_prev,hash_self FROM "+table+" WHERE "+(table.equals("events")?"id":"event_id")+"=?",new String[]{""+eventId})){if(e.moveToFirst()){projectionPrev=e.getString(0);projectionSelf=e.getString(1);}}
                if(!p.equals(expected)||!prev.equals(projectionPrev)||!r.getString("hash_self").equals(projectionSelf)){failure="Projection altérée à "+seq;break;}prev=r.getString("hash_self");
            }}
            try(Cursor c=db.rawQuery("SELECT chain_count,chain_head,checkpoint FROM aiv_state WHERE id=1",null)){c.moveToFirst();if(failure.isEmpty()&&(n!=c.getLong(0)||!prev.equals(c.getString(1))||number(db,"SELECT COUNT(*) FROM decisions")*2!=n||number(db,"SELECT COUNT(*) FROM events WHERE id<="+c.getLong(2))*2!=n))failure="Tête, cardinalité ou reprise incohérente";}
            verification=failure.isEmpty()?"VALID_AT_"+n:"BROKEN: "+failure;
            return EventStore.object("valid",failure.isEmpty(),"count",n,"head",prev,"status",verification,"scope","Cohérence locale; comparer à une tête signée conservée séparément pour détecter une réécriture ou troncature complète");
        }finally{db.endTransaction();}
    }
}