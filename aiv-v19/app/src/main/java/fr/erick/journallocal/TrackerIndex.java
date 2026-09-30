package fr.erick.journallocal;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local, resumable tracker index.
 *
 * The raw journal stays authoritative. This database is only a derived projection:
 * - one row per correlated network flow
 * - one row per tracker hit on that flow
 * - a compact ordered trail of the journal events that belong to the flow
 *
 * "flow_correlation_id" is local to AIV. It is never injected in Internet packets.
 */
public final class TrackerIndex extends SQLiteOpenHelper {
    private static TrackerIndex instance;
    private final Context context;
    private final AtomicBoolean scheduled=new AtomicBoolean();
    private HandlerThread workerThread;
    private Handler worker;
    private volatile String error="";

    public static synchronized TrackerIndex get(Context c){
        if(instance==null)instance=new TrackerIndex(c.getApplicationContext());
        return instance;
    }

    private TrackerIndex(Context c){
        super(c,"tracker-index.sqlite",null,2);
        context=c;
        setWriteAheadLoggingEnabled(true);
    }

    private static void installDerived(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS steps(event_id INTEGER PRIMARY KEY,correlation TEXT NOT NULL,observed_ms INTEGER NOT NULL,app TEXT NOT NULL,action TEXT NOT NULL,destination TEXT NOT NULL,category TEXT NOT NULL,tx_bytes INTEGER NOT NULL DEFAULT 0,rx_bytes INTEGER NOT NULL DEFAULT 0,tx_packets INTEGER NOT NULL DEFAULT 0,rx_packets INTEGER NOT NULL DEFAULT 0,tls_sni TEXT NOT NULL DEFAULT '',remote_ip TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX IF NOT EXISTS tracker_steps_flow ON steps(correlation,event_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS hits(correlation TEXT NOT NULL,tracker_id INTEGER NOT NULL,tracker_name TEXT NOT NULL,app TEXT NOT NULL,package_name TEXT NOT NULL DEFAULT '',uid INTEGER NOT NULL DEFAULT -1,proof INTEGER NOT NULL DEFAULT 1,host TEXT NOT NULL DEFAULT '',remote_ip TEXT NOT NULL DEFAULT '',first_ms INTEGER NOT NULL DEFAULT 0,last_ms INTEGER NOT NULL DEFAULT 0,latest_event_id INTEGER NOT NULL DEFAULT 0,tx_bytes INTEGER NOT NULL DEFAULT 0,rx_bytes INTEGER NOT NULL DEFAULT 0,tx_packets INTEGER NOT NULL DEFAULT 0,rx_packets INTEGER NOT NULL DEFAULT 0,search TEXT NOT NULL DEFAULT '',PRIMARY KEY(correlation,tracker_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS tracker_hits_latest ON hits(last_ms DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS tracker_hits_group ON hits(package_name,app,tracker_id,last_ms DESC)");
    }

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE progress(id INTEGER PRIMARY KEY,checkpoint INTEGER,revision TEXT)");
        db.execSQL("INSERT INTO progress VALUES(1,0,'')");
        db.execSQL("CREATE TABLE flows(correlation TEXT PRIMARY KEY,latest INTEGER,search TEXT,payload TEXT)");
        db.execSQL("CREATE INDEX tracker_latest ON flows(latest)");
        installDerived(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db,int old,int next){installDerived(db);}
    @Override public void onOpen(SQLiteDatabase db){super.onOpen(db);installDerived(db);}

    private long checkpoint(){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT checkpoint FROM progress WHERE id=1",null)){
            c.moveToFirst();return c.getLong(0);
        }
    }

    private JSONObject segmentStatus(long cp){
        long completed=0,current=0,segmentEvents=0,segmentLast=0;boolean sealed=false;
        try{
            SQLiteDatabase journal=EventStore.get(context).getReadableDatabase();
            try(Cursor c=journal.rawQuery("SELECT COUNT(*) FROM journal_segments WHERE sealed=1 AND last_id<=?",new String[]{String.valueOf(cp)})){if(c.moveToFirst())completed=c.getLong(0);}
            try(Cursor c=journal.rawQuery("SELECT segment,event_count,sealed,last_id FROM journal_segments WHERE first_id<=? ORDER BY segment DESC LIMIT 1",new String[]{String.valueOf(Math.max(1,cp))})){
                if(c.moveToFirst()){current=c.getLong(0);segmentEvents=c.getLong(1);sealed=c.getInt(2)!=0;segmentLast=c.getLong(3);}
            }
        }catch(Exception ignored){}
        return EventStore.object("size",JournalSegments.LIMIT,"completed",completed,"current",current,"current_events",segmentEvents,"current_sealed",sealed,"current_last_id",segmentLast);
    }

    public JSONObject status(){
        long flows=0,hits=0,groups=0,steps=0,latestNetwork=0,cp=checkpoint(),latest=EventStore.get(context).latestId();
        SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM flows",null)){if(c.moveToFirst())flows=c.getLong(0);}
        try(Cursor c=db.rawQuery("SELECT COUNT(*),COALESCE(MAX(latest_event_id),0) FROM hits",null)){if(c.moveToFirst()){hits=c.getLong(0);latestNetwork=c.getLong(1);}}
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM (SELECT 1 FROM hits GROUP BY CASE WHEN package_name<>'' THEN package_name ELSE app END,tracker_id)",null)){if(c.moveToFirst())groups=c.getLong(0);}
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM steps",null)){if(c.moveToFirst())steps=c.getLong(0);}
        return EventStore.object(
            "busy",scheduled.get(),"checkpoint",cp,"latest_event",latest,"candidate_flows",flows,
            "tracker_flow_hits",hits,"tracker_groups",groups,"trail_steps",steps,
            "latest_network_match_event",latestNetwork,"segments",segmentStatus(cp),"error",error,
            "notice","La moulinette travaille directement sur le journal local. Un trajet = un flow_correlation_id AIV; cet identifiant reste sur le téléphone et n'est jamais ajouté aux paquets Internet."
        );
    }

    private synchronized void ensureWorker(){
        if(worker!=null)return;
        workerThread=new HandlerThread("aiv-tracker-index",android.os.Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        worker=new Handler(workerThread.getLooper());
    }

    public void request(){
        ensureWorker();
        if(!scheduled.compareAndSet(false,true))return;
        worker.post(this::advance);
    }

    private void advance(){
        boolean more=false;
        try{
            error="";
            ReferenceCatalog catalog=ReferenceCatalog.get(context);
            SQLiteDatabase db=getWritableDatabase();
            String desiredRevision="trail-v2:"+catalog.revision(),revision;
            try(Cursor c=db.rawQuery("SELECT revision FROM progress WHERE id=1",null)){c.moveToFirst();revision=c.getString(0);}
            if(!desiredRevision.equals(revision)){
                db.beginTransaction();
                try{
                    db.delete("flows",null,null);db.delete("hits",null,null);db.delete("steps",null,null);
                    db.execSQL("UPDATE progress SET checkpoint=0,revision=? WHERE id=1",new Object[]{desiredRevision});
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }
            long after=checkpoint(),ceiling=EventStore.get(context).latestId(),until=SystemClock.elapsedRealtime()+3000;
            while(after<ceiling&&SystemClock.elapsedRealtime()<until){
                JSONArray batch=EventStore.get(context).analysisBatch(after,200);
                if(batch.length()==0)break;
                db.beginTransaction();
                try{
                    for(int i=0;i<batch.length();i++){
                        JSONObject e=batch.getJSONObject(i);
                        if(e.getLong("id")>ceiling)break;
                        consume(db,catalog,e);
                        after=e.getLong("id");
                    }
                    db.execSQL("UPDATE progress SET checkpoint=? WHERE id=1",new Object[]{after});
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }
            more=after<EventStore.get(context).latestId();
        }catch(Exception e){
            error="Index des traqueurs interrompu : "+e.getClass().getSimpleName()+"; reprise au dernier lot validé.";
        }finally{
            scheduled.set(false);
            if((more||checkpoint()<EventStore.get(context).latestId())&&worker!=null)worker.postDelayed(this::request,350);
        }
    }

    private void storeStep(SQLiteDatabase db,JSONObject e,JSONObject d,String correlation)throws Exception{
        ContentValues v=new ContentValues();
        v.put("event_id",e.getLong("id"));v.put("correlation",correlation);v.put("observed_ms",e.optLong("timestamp_ms"));
        v.put("app",e.optString("app"));v.put("action",e.optString("action"));v.put("destination",e.optString("destination"));v.put("category",e.optString("category"));
        v.put("tx_bytes",d.optLong("tx_bytes"));v.put("rx_bytes",d.optLong("rx_bytes"));v.put("tx_packets",d.optLong("tx_packets"));v.put("rx_packets",d.optLong("rx_packets"));
        v.put("tls_sni",d.optString("tls_sni"));v.put("remote_ip",d.optString("remote_ip"));
        db.insertWithOnConflict("steps",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void consume(SQLiteDatabase db,ReferenceCatalog catalog,JSONObject e)throws Exception{
        JSONObject d=e.optJSONObject("details");if(d==null)return;
        String key=d.optString("flow_correlation_id");if(key.isEmpty())return;
        storeStep(db,e,d,key);

        JSONArray matches=catalog.network(d.optString("tls_sni"),d.optBoolean("ech_extension_present")?"TLS_OUTER_NAME":"TLS_SNI");
        JSONArray queries="dns".equals(e.optString("category"))?catalog.network(d.optString("question"),"DNS_QUERY_ONLY"):new JSONArray();

        JSONObject f=null;
        try(Cursor c=db.rawQuery("SELECT payload FROM flows WHERE correlation=?",new String[]{key})){if(c.moveToFirst())f=new JSONObject(c.getString(0));}
        if(f==null&&matches.length()==0&&queries.length()==0)return;
        if(f==null)f=EventStore.object("flow_correlation_id",key,"first_event_id",e.getLong("id"),"first_observed_ms",d.optLong("first_observed_ms"),"tracker_matches",new JSONArray(),"tracker_dns_candidates",new JSONArray());

        if(matches.length()>0)f.put("tracker_matches",matches);
        if(queries.length()>0){
            JSONArray old=f.getJSONArray("tracker_dns_candidates");
            for(int i=0;i<queries.length();i++){
                JSONObject candidate=queries.getJSONObject(i);boolean exists=false;
                for(int j=0;j<old.length();j++)if(old.getJSONObject(j).optInt("tracker_id")==candidate.optInt("tracker_id")&&old.getJSONObject(j).optString("host").equals(candidate.optString("host")))exists=true;
                if(!exists&&old.length()<128)old.put(candidate);
            }
        }
        for(String n:new String[]{"tx_bytes","rx_bytes","tx_packets","rx_packets"})f.put(n,Math.max(f.optLong(n),d.optLong(n)));
        for(String n:new String[]{"uid","packages","attribution","journal_group","remote_ip","protocol","port","tls_sni","first_outbound_ms","first_inbound_ms","closed","last_packet_ms","ech_extension_present"})if(d.has(n)&&!d.isNull(n))f.put(n,d.get(n));
        f.put("app",e.optString("app")).put("latest_event_id",e.getLong("id")).put("latest_timestamp_ms",e.optLong("timestamp_ms")).put("catalog_revision",catalog.revision());
        JSONArray pkgs=d.optJSONArray("packages");int uid=d.optInt("uid",-1);
        f.put("attribution_unique",uid>=0&&uid%100000>=10000&&pkgs!=null&&pkgs.length()==1);

        ContentValues row=new ContentValues();row.put("correlation",key);row.put("latest",e.getLong("id"));row.put("payload",f.toString());row.put("search",f.toString().toLowerCase(Locale.ROOT));
        db.insertWithOnConflict("flows",null,row,SQLiteDatabase.CONFLICT_REPLACE);
        syncHits(db,f);
    }

    private void syncHits(SQLiteDatabase db,JSONObject f)throws Exception{
        LinkedHashMap<Integer,JSONObject> chosen=new LinkedHashMap<>();
        JSONArray dns=f.optJSONArray("tracker_dns_candidates"),network=f.optJSONArray("tracker_matches");
        if(dns!=null)for(int i=0;i<dns.length();i++){JSONObject x=new JSONObject(dns.getJSONObject(i).toString());x.put("_proof",1);chosen.put(x.optInt("tracker_id"),x);}
        if(network!=null)for(int i=0;i<network.length();i++){JSONObject x=new JSONObject(network.getJSONObject(i).toString());x.put("_proof",2);chosen.put(x.optInt("tracker_id"),x);}
        if(chosen.isEmpty())return;

        JSONArray pkgs=f.optJSONArray("packages");String pkg=pkgs!=null&&pkgs.length()==1?pkgs.optString(0):"";
        long first=f.optLong("first_outbound_ms");if(first<=0)first=f.optLong("first_observed_ms");
        long last=f.optLong("last_packet_ms");if(last<=0)last=f.optLong("latest_timestamp_ms");
        for(JSONObject x:chosen.values()){
            int id=x.optInt("tracker_id",-1);if(id<0)continue;
            String host=x.optString("host");if(host.isEmpty())host=f.optString("tls_sni");
            int proof=x.optInt("_proof",1);
            String search=(f.optString("app")+" "+pkg+" "+x.optString("name")+" "+host+" "+f.optString("remote_ip")+" "+f.optString("flow_correlation_id")).toLowerCase(Locale.ROOT);
            ContentValues v=new ContentValues();
            v.put("correlation",f.optString("flow_correlation_id"));v.put("tracker_id",id);v.put("tracker_name",x.optString("name","Tracker "+id));
            v.put("app",f.optString("app","Application non identifiée"));v.put("package_name",pkg);v.put("uid",f.optInt("uid",-1));v.put("proof",proof);
            v.put("host",host);v.put("remote_ip",f.optString("remote_ip"));v.put("first_ms",first);v.put("last_ms",last);v.put("latest_event_id",f.optLong("latest_event_id"));
            v.put("tx_bytes",f.optLong("tx_bytes"));v.put("rx_bytes",f.optLong("rx_bytes"));v.put("tx_packets",f.optLong("tx_packets"));v.put("rx_packets",f.optLong("rx_packets"));v.put("search",search);
            db.insertWithOnConflict("hits",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private static String groupExpr(){return "CASE WHEN package_name<>'' THEN package_name ELSE app END";}

    private JSONObject apkEvidence(String pkg,int trackerId){
        if(pkg==null||pkg.isEmpty())return EventStore.object("status","NO_UNIQUE_PACKAGE","present",JSONObject.NULL);
        try{
            android.content.pm.PackageInfo installed=context.getPackageManager().getPackageInfo(pkg,0);
            long version=android.os.Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;
            JSONObject apk=ApkEvidence.get(context).read(pkg,version,installed.lastUpdateTime);JSONArray sdk=apk.optJSONArray("trackers");boolean present=false;
            if(sdk!=null)for(int i=0;i<sdk.length();i++)if(sdk.getJSONObject(i).optInt("id")==trackerId){present=true;break;}
            return EventStore.object("status",apk.optString("status"),"present",present,"version",version);
        }catch(Exception e){return EventStore.object("status",e instanceof android.content.pm.PackageManager.NameNotFoundException?"NOT_VISIBLE_OR_REMOVED":"UNKNOWN","present",JSONObject.NULL);}
    }

    public JSONObject groups(String query,int offset,int limit)throws Exception{
        request();if(query==null)query="";if(query.length()>512)throw new IllegalArgumentException("Recherche trop longue");
        offset=Math.max(0,offset);limit=Math.max(1,Math.min(100,limit));String q=query.trim().toLowerCase(Locale.ROOT),where=q.isEmpty()?"":" WHERE instr(search,?)>0";
        SQLiteDatabase db=getReadableDatabase();String[] base=q.isEmpty()?new String[]{}:new String[]{q};long total=0;
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM (SELECT 1 FROM hits"+where+" GROUP BY "+groupExpr()+",tracker_id)",base)){if(c.moveToFirst())total=c.getLong(0);}
        ArrayList<String> args=new ArrayList<>(Arrays.asList(base));args.add(String.valueOf(limit));args.add(String.valueOf(offset));JSONArray rows=new JSONArray();
        String sql="SELECT "+groupExpr()+" app_key,MAX(app),MAX(package_name),tracker_id,MAX(tracker_name),COUNT(*),COUNT(DISTINCT CASE WHEN host<>'' THEN host ELSE remote_ip END),MIN(first_ms),MAX(last_ms),MAX(latest_event_id),SUM(tx_bytes),SUM(rx_bytes),MAX(proof) FROM hits"+where+" GROUP BY app_key,tracker_id ORDER BY MAX(last_ms) DESC LIMIT ? OFFSET ?";
        try(Cursor c=db.rawQuery(sql,args.toArray(new String[0]))){
            while(c.moveToNext()){
                String key=c.getString(0),app=c.getString(1),pkg=c.getString(2);int trackerId=c.getInt(3);
                JSONObject row=EventStore.object("app_key",key,"app",app,"package_name",pkg,"tracker_id",trackerId,"tracker_name",c.getString(4),"journeys",c.getLong(5),"destinations_count",c.getLong(6),"first_ms",c.getLong(7),"last_ms",c.getLong(8),"latest_event_id",c.getLong(9),"tx_bytes",c.getLong(10),"rx_bytes",c.getLong(11),"proof",c.getInt(12));
                JSONArray destinations=new JSONArray();
                try(Cursor d=db.rawQuery("SELECT CASE WHEN host<>'' THEN host ELSE remote_ip END dest,COUNT(*),MAX(last_ms) FROM hits WHERE "+groupExpr()+"=? AND tracker_id=? AND (host<>'' OR remote_ip<>'') GROUP BY dest ORDER BY COUNT(*) DESC,MAX(last_ms) DESC LIMIT 8",new String[]{key,String.valueOf(trackerId)})){
                    while(d.moveToNext())destinations.put(EventStore.object("name",d.getString(0),"journeys",d.getLong(1),"last_ms",d.getLong(2)));
                }
                row.put("destinations",destinations).put("apk",apkEvidence(pkg,trackerId));rows.put(row);
            }
        }
        return EventStore.object("rows",rows,"total",total,"offset",offset,"limit",limit,"query",q,"status",status());
    }

    public JSONObject journeys(String appKey,int trackerId,long before,int limit)throws Exception{
        if(appKey==null||appKey.length()>512||trackerId<0)throw new IllegalArgumentException("Groupe de traqueur invalide.");
        limit=Math.max(1,Math.min(30,limit));ArrayList<String> args=new ArrayList<>();args.add(appKey);args.add(String.valueOf(trackerId));
        String where=groupExpr()+"=? AND tracker_id=?";if(before>0){where+=" AND last_ms<?";args.add(String.valueOf(before));}args.add(String.valueOf(limit));
        JSONArray rows=new JSONArray();long next=0;SQLiteDatabase db=getReadableDatabase();
        String sql="SELECT correlation,app,package_name,uid,proof,host,remote_ip,first_ms,last_ms,latest_event_id,tx_bytes,rx_bytes,tx_packets,rx_packets,(SELECT COUNT(*) FROM steps s WHERE s.correlation=hits.correlation) FROM hits WHERE "+where+" ORDER BY last_ms DESC LIMIT ?";
        try(Cursor c=db.rawQuery(sql,args.toArray(new String[0]))){
            while(c.moveToNext()){next=c.getLong(8);rows.put(EventStore.object("correlation",c.getString(0),"app",c.getString(1),"package_name",c.getString(2),"uid",c.getInt(3),"proof",c.getInt(4),"host",c.getString(5),"remote_ip",c.getString(6),"first_ms",c.getLong(7),"last_ms",c.getLong(8),"latest_event_id",c.getLong(9),"tx_bytes",c.getLong(10),"rx_bytes",c.getLong(11),"tx_packets",c.getLong(12),"rx_packets",c.getLong(13),"steps",c.getLong(14)));}
        }
        return EventStore.object("rows",rows,"next_before",next,"limit",limit);
    }

    public JSONObject trail(String correlation)throws Exception{
        if(correlation==null||correlation.length()<8||correlation.length()>128)throw new IllegalArgumentException("Identifiant de trajet invalide.");
        SQLiteDatabase db=getReadableDatabase();JSONArray rows=new JSONArray();long total=0;
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM steps WHERE correlation=?",new String[]{correlation})){if(c.moveToFirst())total=c.getLong(0);}
        try(Cursor c=db.rawQuery("SELECT event_id,observed_ms,app,action,destination,category,tx_bytes,rx_bytes,tx_packets,rx_packets,tls_sni,remote_ip FROM steps WHERE correlation=? ORDER BY event_id ASC LIMIT 250",new String[]{correlation})){
            while(c.moveToNext())rows.put(EventStore.object("event_id",c.getLong(0),"observed_ms",c.getLong(1),"app",c.getString(2),"action",c.getString(3),"destination",c.getString(4),"category",c.getString(5),"tx_bytes",c.getLong(6),"rx_bytes",c.getLong(7),"tx_packets",c.getLong(8),"rx_packets",c.getLong(9),"tls_sni",c.getString(10),"remote_ip",c.getString(11)));
        }
        return EventStore.object("correlation",correlation,"steps",rows,"total",total,"truncated",total>rows.length(),"scope","Chronologie locale du même flux corrélé sur le téléphone. Elle ne suit pas un paquet individuel après sa sortie vers Internet.");
    }

    public void export(java.io.Writer writer)throws Exception{
        writer.write("{\"schema\":\"aiv-tracker-observations/23\",\"status\":");writer.write(status().toString());writer.write(",\"flows\":[");
        boolean first=true;try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM flows ORDER BY latest",null)){while(c.moveToNext()){if(!first)writer.write(",");writer.write(c.getString(0));first=false;}}writer.write("]}");
    }

    // Legacy flat page retained for older UI paths and exports.
    public JSONObject page(String query,long before)throws Exception{return page(query,before,25);}
    public JSONObject page(String query,long before,int limit)throws Exception{
        if(query==null)query="";if(query.length()>512)throw new IllegalArgumentException("Recherche trop longue");limit=Math.max(1,Math.min(500,limit));JSONArray rows=new JSONArray();long next=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT latest,payload FROM flows WHERE latest<? AND instr(search,?)>0 ORDER BY latest DESC LIMIT ?",new String[]{""+(before>0?before:Long.MAX_VALUE),query.toLowerCase(Locale.ROOT),String.valueOf(limit)})){while(c.moveToNext()){next=c.getLong(0);rows.put(new JSONObject(c.getString(1)));}}
        return EventStore.object("rows",rows,"next_before",next,"limit",limit,"status",status());
    }
}
