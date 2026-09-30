package fr.erick.journallocal;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.SystemClock;
import org.json.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local, resumable index over existing events. Raw journal and AIV signature chain remain untouched. */
public final class TrackerIndex extends SQLiteOpenHelper {
    private static TrackerIndex instance;private final Context context;private final AtomicBoolean busy=new AtomicBoolean();private volatile String error="";
    public static synchronized TrackerIndex get(Context c){if(instance==null)instance=new TrackerIndex(c.getApplicationContext());return instance;}
    private TrackerIndex(Context c){super(c,"tracker-index.sqlite",null,1);context=c;setWriteAheadLoggingEnabled(true);}
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE progress(id INTEGER PRIMARY KEY,checkpoint INTEGER,revision TEXT)");db.execSQL("INSERT INTO progress VALUES(1,0,'')");db.execSQL("CREATE TABLE flows(correlation TEXT PRIMARY KEY,latest INTEGER,search TEXT,payload TEXT)");db.execSQL("CREATE INDEX tracker_latest ON flows(latest)");}
    public void onUpgrade(SQLiteDatabase db,int old,int next){}
    private long checkpoint(){try(Cursor c=getReadableDatabase().rawQuery("SELECT checkpoint FROM progress WHERE id=1",null)){c.moveToFirst();return c.getLong(0);}}
    public JSONObject status(){long n=0;try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM flows",null)){c.moveToFirst();n=c.getLong(0);}return EventStore.object("busy",busy.get(),"enabled",enabled(),"checkpoint",checkpoint(),"latest_event",EventStore.get(context).latestId(),"candidate_flows",n,"error",error,"notice","Reprise progressive de l’historique; compteurs cumulés conservés une fois par flux. Résultats partiels tant que le rattrapage continue.");}
    private boolean enabled(){return Continuous.enabled(context)&&Continuous.prefs(context).getBoolean("analysis_enabled",true);}
    public void request(){if(!enabled()||!busy.compareAndSet(false,true))return;new Thread(()->{
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        try{error="";ReferenceCatalog catalog=ReferenceCatalog.get(context);SQLiteDatabase db=getWritableDatabase();String revision;
            try(Cursor c=db.rawQuery("SELECT revision FROM progress WHERE id=1",null)){c.moveToFirst();revision=c.getString(0);}
            if(!revision.equals(catalog.revision())){db.beginTransaction();try{db.delete("flows",null,null);db.execSQL("UPDATE progress SET checkpoint=0,revision=? WHERE id=1",new Object[]{catalog.revision()});db.setTransactionSuccessful();}finally{db.endTransaction();}}
            long after=checkpoint(),ceiling=EventStore.get(context).latestId(),until=SystemClock.elapsedRealtime()+5000;
            while(enabled()&&after<ceiling&&SystemClock.elapsedRealtime()<until){JSONArray batch=EventStore.get(context).analysisBatch(after,200);if(batch.length()==0)break;db.beginTransaction();try{
                for(int i=0;i<batch.length();i++){JSONObject e=batch.getJSONObject(i);if(e.getLong("id")>ceiling)break;consume(db,catalog,e);after=e.getLong("id");}
                db.execSQL("UPDATE progress SET checkpoint=? WHERE id=1",new Object[]{after});db.setTransactionSuccessful();
            }finally{db.endTransaction();}}
        }catch(Exception e){error="Index interrompu : "+e.getClass().getSimpleName()+"; reprise au dernier lot validé.";}finally{busy.set(false);}
    },"aiv-tracker-index").start();}
    private void consume(SQLiteDatabase db,ReferenceCatalog catalog,JSONObject e)throws Exception{
        JSONObject d=e.optJSONObject("details");if(d==null)return;String key=d.optString("flow_correlation_id");if(key.isEmpty())return;
        JSONArray matches=catalog.network(d.optString("tls_sni"),d.optBoolean("ech_extension_present")?"TLS_OUTER_NAME":"TLS_SNI");JSONArray queries="dns".equals(e.optString("category"))?catalog.network(d.optString("question"),"DNS_QUERY_ONLY"):new JSONArray();
        JSONObject f=null;try(Cursor c=db.rawQuery("SELECT payload FROM flows WHERE correlation=?",new String[]{key})){if(c.moveToFirst())f=new JSONObject(c.getString(0));}
        if(f==null&&matches.length()==0&&queries.length()==0)return;
        if(f==null)f=EventStore.object("flow_correlation_id",key,"first_event_id",e.getLong("id"),"first_observed_ms",d.optLong("first_observed_ms"),"tracker_matches",new JSONArray(),"tracker_dns_candidates",new JSONArray());
        if(matches.length()>0)f.put("tracker_matches",matches);if(queries.length()>0){JSONArray old=f.getJSONArray("tracker_dns_candidates");for(int i=0;i<queries.length();i++){JSONObject candidate=queries.getJSONObject(i);boolean exists=false;for(int j=0;j<old.length();j++)if(old.getJSONObject(j).optInt("tracker_id")==candidate.optInt("tracker_id")&&old.getJSONObject(j).optString("host").equals(candidate.optString("host")))exists=true;if(!exists&&old.length()<128)old.put(candidate);}}
        for(String n:new String[]{"tx_bytes","rx_bytes","tx_packets","rx_packets"})f.put(n,Math.max(f.optLong(n),d.optLong(n)));
        for(String n:new String[]{"uid","packages","attribution","journal_group","remote_ip","protocol","port","tls_sni","first_outbound_ms","first_inbound_ms","closed","last_packet_ms","ech_extension_present"})if(d.has(n)&&!d.isNull(n))f.put(n,d.get(n));
        f.put("app",e.optString("app")).put("latest_event_id",e.getLong("id")).put("catalog_revision",catalog.revision());JSONArray pkgs=d.optJSONArray("packages");int uid=d.optInt("uid",-1);f.put("attribution_unique",uid>=0&&uid%100000>=10000&&pkgs!=null&&pkgs.length()==1);
        ContentValues row=new ContentValues();row.put("correlation",key);row.put("latest",e.getLong("id"));row.put("payload",f.toString());row.put("search",f.toString().toLowerCase(java.util.Locale.ROOT));db.insertWithOnConflict("flows",null,row,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public void export(java.io.Writer writer)throws Exception{
        writer.write("{\"schema\":\"aiv-tracker-observations/22\",\"status\":");writer.write(status().toString());writer.write(",\"catalog\":");writer.write(ReferenceCatalog.get(context).summary().toString());writer.write(",\"flows\":[");
        boolean first=true;try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM flows ORDER BY latest",null)){while(c.moveToNext()){if(!first)writer.write(",");writer.write(c.getString(0));first=false;}}writer.write("]}");
    }
    public JSONObject page(String query,long before)throws Exception{
        if(query==null)query="";if(query.length()>512)throw new IllegalArgumentException("Recherche trop longue");JSONArray rows=new JSONArray();long next=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT latest,payload FROM flows WHERE latest<? AND instr(search,?)>0 ORDER BY latest DESC LIMIT 25",new String[]{""+(before>0?before:Long.MAX_VALUE),query.toLowerCase(java.util.Locale.ROOT)})){while(c.moveToNext()){next=c.getLong(0);JSONObject row=new JSONObject(c.getString(1));if(row.optBoolean("attribution_unique")){JSONArray pkgs=row.optJSONArray("packages");if(pkgs!=null&&pkgs.length()==1)try{
            android.content.pm.PackageInfo installed=context.getPackageManager().getPackageInfo(pkgs.getString(0),0);long version=android.os.Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;
            JSONObject apk=ApkEvidence.get(context).read(pkgs.getString(0),version,installed.lastUpdateTime);JSONArray sdk=apk.optJSONArray("trackers"),network=row.optJSONArray("tracker_matches"),intersection=new JSONArray();
            if(sdk!=null&&network!=null)for(int i=0;i<network.length();i++)for(int j=0;j<sdk.length();j++)if(network.getJSONObject(i).optInt("tracker_id")==sdk.getJSONObject(j).optInt("id"))intersection.put(network.getJSONObject(i).optInt("tracker_id"));
            row.put("current_apk_tracker_ids",intersection).put("current_apk_version",version).put("current_apk_status",apk.optString("status")).put("apk_correlation_scope","Même identifiant Exodus dans le flux et l’APK actuellement installé; version au moment du flux et SDK appelant non prouvés.");
        }catch(android.content.pm.PackageManager.NameNotFoundException e){row.put("current_apk_status","NOT_VISIBLE_OR_REMOVED");}}rows.put(row);}}
        return EventStore.object("rows",rows,"next_before",next,"status",status());
    }
}
