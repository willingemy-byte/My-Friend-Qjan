package fr.erick.journallocal;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.Handler;
import android.os.Looper;

/**
 * Uploads sealed 50,000-event journal segments to the AIV Supabase archive.
 *
 * Remote verification is mandatory before a segment may ever become eligible
 * for local pruning. This class does not prune by itself.
 */
public final class ArchiveSync {
    private static final String ENDPOINT="https://poqahjwwjcidznxmyquv.supabase.co/functions/v1/aiv-journal-ingest";
    // Supabase publishable keys are intentionally client-visible and are not secrets.
    private static final String API_KEY="sb_publishable_sEccKZAXT0xzHm_13LqNXA_L0q9OOvZ";
    private static final int MAX_EVENTS_PER_REQUEST=100;
    private static final int MAX_REQUEST_CHARS=700000;
    private static final AtomicBoolean running=new AtomicBoolean();

    public static volatile String lastError="";
    public static volatile long lastVerifiedSegment=0;
    private static volatile String phase="IDLE";
    private static volatile long currentSegment;
    private static final long RETRY_MIN_MS=30000,RETRY_MAX_MS=900000;

    private ArchiveSync(){}

    private static SharedPreferences status(Context context){return context.getSharedPreferences("aiv_archive_status",0);}
    public static void request(Context context){request(context,true);}
    public static void requestAutomatic(Context context){request(context,false);}
    private static synchronized void request(Context context,boolean manual){
        SharedPreferences saved=status(context);
        if(!manual&&System.currentTimeMillis()<saved.getLong("next_attempt_ms",0))return;
        if(!running.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            long delay=0;
            try{
                sync(app,ArchiveSync::post);
                lastError="";
                saved.edit().putString("last_error","").putInt("failures",0).putLong("next_attempt_ms",0).commit();
            }catch(Exception e){
                lastError="Archive Supabase : "+e.getClass().getSimpleName()+(e.getMessage()==null?"":" · "+e.getMessage());
                int failures=Math.min(6,saved.getInt("failures",0)+1);
                delay=Math.min(RETRY_MAX_MS,RETRY_MIN_MS*(1L<<(failures-1)));
                saved.edit().putString("last_error",lastError).putInt("failures",failures).putLong("next_attempt_ms",System.currentTimeMillis()+delay).commit();
                try{EventStore.get(app).getWritableDatabase().execSQL("UPDATE journal_archive_state SET remote_state='PENDING',error=? WHERE segment=? AND remote_state!='VERIFIED'",new Object[]{lastError,currentSegment});}catch(Exception ignored){}
            }finally{
                phase=lastError.isEmpty()?"IDLE":"RETRY";
                running.set(false);
                // One segment per pass. Retry also works when collection is paused.
                try{
                    if(nextSegment(EventStore.get(app).getReadableDatabase())!=null){
                        long wait=delay==0?1000:delay;
                        new Handler(Looper.getMainLooper()).postDelayed(()->requestAutomatic(app),wait);
                    }
                }catch(Exception ignored){}
            }
        },"aiv-archive-sync").start();
    }

    public static JSONObject state(Context context){
        JSONObject out=EventStore.object(
            "running",running.get(),
            "last_error",lastError.isEmpty()?status(context).getString("last_error",""):lastError,
            "phase",phase,"current_segment",currentSegment,"next_attempt_ms",status(context).getLong("next_attempt_ms",0),
            "last_verified_segment",lastVerifiedSegment,
            "segment_size",JournalSegments.LIMIT,
            "remote","Supabase"
        );
        try{
            SQLiteDatabase db=EventStore.get(context).getReadableDatabase();
            try(Cursor c=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN remote_state='VERIFIED' THEN 1 ELSE 0 END),0),COALESCE(MAX(CASE WHEN remote_state='VERIFIED' THEN segment ELSE 0 END),0),COALESCE(SUM(CASE WHEN purge_state='PURGED' THEN expected_count ELSE 0 END),0),COALESCE(SUM(CASE WHEN purge_state!='PURGED' THEN expected_count ELSE 0 END),0) FROM journal_archive_state",null)){
                if(c.moveToFirst()){
                    out.put("tracked_segments",c.getLong(0));
                    out.put("verified_segments",c.getLong(1));
                    out.put("last_verified_segment",c.getLong(2));
                    out.put("purged_events",c.getLong(3));
                    out.put("retained_archived_events",c.getLong(4));
                }
            }
            out.put("purge",JournalPurge.state(context));
            try(Cursor c=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(s.event_count),0) FROM journal_segments s LEFT JOIN journal_archive_state a ON a.segment=s.segment WHERE s.sealed=1 AND COALESCE(a.remote_state,'')!='VERIFIED'",null)){
                if(c.moveToFirst()){out.put("pending_segments",c.getLong(0));out.put("pending_events",c.getLong(1));}
            }
            try(Cursor c=db.rawQuery("SELECT segment,uploaded_through_id,error FROM journal_archive_state WHERE remote_state!='VERIFIED' ORDER BY segment LIMIT 1",null)){
                if(c.moveToFirst()){out.put("pending_segment",c.getLong(0));out.put("uploaded_through_id",c.getLong(1));}
            }
        }catch(Exception e){
            try{out.put("state_error",e.getClass().getSimpleName());}catch(Exception ignored){}
        }
        return out;
    }

    interface Transport {JSONObject send(Identity identity,JSONObject body)throws Exception;}
    static void sync(Context context,Transport transport)throws Exception{
        currentSegment=0;phase="START";
        Identity identity=identity(context);
        SQLiteDatabase db=EventStore.get(context).getWritableDatabase();

            Segment s=nextSegment(db);
            if(s==null)return;
            currentSegment=s.segment;phase="MANIFEST";

            String manifest=segmentManifest(db,s);
            long through=0;
            try(Cursor c=db.rawQuery("SELECT client_sha256,uploaded_through_id FROM journal_archive_state WHERE segment=?",new String[]{String.valueOf(s.segment)})){
                if(c.moveToFirst()){
                    if(!manifest.equalsIgnoreCase(c.getString(0)))throw new IOException("Manifeste local modifié : segment "+s.segment);
                    through=c.getLong(1);
                }
            }
            db.execSQL("INSERT OR IGNORE INTO journal_archive_state(segment,client_sha256) VALUES(?,?)",new Object[]{s.segment,manifest});
            db.execSQL("UPDATE journal_archive_state SET remote_state='UPLOADING',error=NULL WHERE segment=?",new Object[]{s.segment});

            JSONObject begin=EventStore.object(
                "schema","aiv-journal/1",
                "action","begin",
                "segment_no",s.segment,
                "segment",EventStore.object(
                    "segment_no",s.segment,
                    "first_event_id",s.firstId,
                    "last_event_id",s.lastId,
                    "expected_count",s.count,
                    "client_segment_sha256",manifest,
                    "first_observed_ms",s.firstObserved,
                    "last_observed_ms",s.lastObserved
                )
            );
            phase="BEGIN";
            JSONObject beginReply=transport.send(identity,begin);
            if("VERIFIED".equals(beginReply.optString("state"))){
                // A duplicate BEGIN proves the manifest matches the server row, but FINALIZE
                // is requested again because its receipt includes the full range required before purge.
                JSONObject verifiedReply=transport.send(identity,EventStore.object(
                    "schema","aiv-journal/1",
                    "action","finalize",
                    "segment_no",s.segment
                ));
                requireReceipt(verifiedReply,s,manifest);
                markVerified(context,db,s,manifest,verifiedReply);
                return;
            }

            long acknowledged=0;
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM events WHERE id>=? AND id<=?",new String[]{String.valueOf(s.firstId),String.valueOf(Math.min(through,s.lastId))})){
                if(c.moveToFirst())acknowledged=c.getLong(0);
            }
            // A count mismatch means remote progress cannot be safely matched to our cursor.
            if(beginReply.optLong("received_count",-1)!=acknowledged)through=0;
            if(beginReply.optLong("received_count",-1)!=s.count){
                phase="UPLOAD";uploadEvents(db,identity,s,through,transport);
            }

            phase="VERIFY";
            JSONObject finalizeReply=transport.send(identity,EventStore.object(
                "schema","aiv-journal/1",
                "action","finalize",
                "segment_no",s.segment
            ));
            requireReceipt(finalizeReply,s,manifest);
            markVerified(context,db,s,manifest,finalizeReply);
    }
    private static void requireReceipt(JSONObject reply,Segment s,String manifest)throws IOException{
        if(!"VERIFIED".equals(reply.optString("state"))
            ||reply.optLong("received_count",-1)!=s.count
            ||!manifest.equalsIgnoreCase(reply.optString("server_segment_sha256"))
            ||reply.optLong("first_event_id",-1)!=s.firstId
            ||reply.optLong("last_event_id",-1)!=s.lastId)
            throw new IOException("Reçu distant incomplet, plage ou SHA différent pour segment "+s.segment);
    }

    private static Segment nextSegment(SQLiteDatabase db){
        String sql="SELECT s.segment,s.first_id,s.last_id,s.event_count,"+
            "(SELECT MIN(timestamp_ms) FROM events WHERE id BETWEEN s.first_id AND s.last_id),"+
            "(SELECT MAX(timestamp_ms) FROM events WHERE id BETWEEN s.first_id AND s.last_id) "+
            "FROM journal_segments s LEFT JOIN journal_archive_state a ON a.segment=s.segment "+
            "WHERE s.sealed=1 AND s.event_count>0 AND (COALESCE(a.remote_state,'')!='VERIFIED' OR COALESCE(a.purge_state,'LOCAL')='LOCAL') "+
            "ORDER BY s.segment LIMIT 1";
        try(Cursor c=db.rawQuery(sql,null)){
            if(!c.moveToFirst())return null;
            return new Segment(c.getLong(0),c.getLong(1),c.getLong(2),c.getInt(3),c.isNull(4)?0:c.getLong(4),c.isNull(5)?0:c.getLong(5));
        }
    }

    private static String segmentManifest(SQLiteDatabase db,Segment s)throws Exception{
        MessageDigest manifest=MessageDigest.getInstance("SHA-256");
        boolean first=true;
        int count=0;
        try(Cursor c=db.rawQuery("SELECT id,payload FROM events WHERE id>=? AND id<=? ORDER BY id",
            new String[]{String.valueOf(s.firstId),String.valueOf(s.lastId)})){
            while(c.moveToNext()){
                long id=c.getLong(0);
                String eventJson=c.getString(1);
                String eventSha=sha256(eventJson);
                if(!first)manifest.update((byte)'\n');
                first=false;
                manifest.update((id+":"+eventSha).getBytes(StandardCharsets.UTF_8));
                count++;
            }
        }
        if(count!=s.count)throw new IOException("Segment "+s.segment+" incomplet localement : "+count+" / "+s.count);
        return hex(manifest.digest());
    }

    private static void uploadEvents(SQLiteDatabase db,Identity identity,Segment s,long through,Transport transport)throws Exception{
        JSONArray batch=new JSONArray();
        int approximateChars=0;
        long uploadedThrough=0;

        try(Cursor c=db.rawQuery("SELECT id,timestamp_ms,app,action,destination,transport,category,payload FROM events WHERE id>=? AND id<=? AND id>? ORDER BY id",
            new String[]{String.valueOf(s.firstId),String.valueOf(s.lastId),String.valueOf(through)})){
            while(c.moveToNext()){
                long id=c.getLong(0);
                String raw=c.getString(7);
                JSONObject event=EventStore.object(
                    "event_id",id,
                    "timestamp_ms",c.getLong(1),
                    "app",c.getString(2),
                    "action",c.getString(3),
                    "destination",c.getString(4),
                    "transport",c.getString(5),
                    "category",c.getString(6),
                    "event_json",raw,
                    "event_sha256",sha256(raw)
                );
                int chars=event.toString().length();
                if(batch.length()>0&&(batch.length()>=MAX_EVENTS_PER_REQUEST||approximateChars+chars>MAX_REQUEST_CHARS)){
                    sendBatch(identity,s.segment,batch,transport);
                    uploadedThrough=batch.getJSONObject(batch.length()-1).getLong("event_id");
                    saveProgress(db,s.segment,uploadedThrough);
                    batch=new JSONArray();
                    approximateChars=0;
                }
                batch.put(event);
                approximateChars+=chars;
            }
        }

        if(batch.length()>0){
            sendBatch(identity,s.segment,batch,transport);
            uploadedThrough=batch.getJSONObject(batch.length()-1).getLong("event_id");
            saveProgress(db,s.segment,uploadedThrough);
        }
    }

    private static void sendBatch(Identity identity,long segment,JSONArray events,Transport transport)throws Exception{
        JSONObject reply=transport.send(identity,EventStore.object(
            "schema","aiv-journal/1",
            "action","batch",
            "segment_no",segment,
            "events",events
        ));
        if(!reply.optBoolean("ok"))throw new IOException("Lot refusé pour segment "+segment);
    }

    private static void saveProgress(SQLiteDatabase db,long segment,long eventId){
        db.execSQL("UPDATE journal_archive_state SET uploaded_through_id=?,remote_state='UPLOADING',error=NULL WHERE segment=?",
            new Object[]{eventId,segment});
    }

    private static void markVerified(Context context,SQLiteDatabase db,Segment s,String sha,JSONObject receipt)throws Exception{
        long now=System.currentTimeMillis();
        JournalPurge.recordVerified(db,s.segment,s.firstId,s.lastId,s.count,sha,receipt.getString("server_segment_sha256"),now);
        lastVerifiedSegment=s.segment;
        JournalPurge.request(context);
    }

    private static JSONObject post(Identity identity,JSONObject body)throws Exception{
        HttpURLConnection h=(HttpURLConnection)new URL(ENDPOINT).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(60000);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type","application/json; charset=utf-8");
        h.setRequestProperty("Accept","application/json");
        h.setRequestProperty("apikey",API_KEY);
        h.setRequestProperty("x-aiv-install-id",identity.id);
        h.setRequestProperty("x-aiv-install-secret",identity.secret);
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        h.setFixedLengthStreamingMode(bytes.length);
        try(OutputStream out=h.getOutputStream()){out.write(bytes);}
        int code=h.getResponseCode();
        InputStream in=code>=200&&code<300?h.getInputStream():h.getErrorStream();
        String response=read(in);
        h.disconnect();
        JSONObject result=response.isEmpty()?new JSONObject():new JSONObject(response);
        if(code<200||code>=300)throw new IOException("HTTP "+code+" · "+result.optString("error","erreur distante"));
        return result;
    }

    private static Identity identity(Context context){
        SharedPreferences p=context.getSharedPreferences("aiv_archive_identity",Context.MODE_PRIVATE);
        String id=p.getString("install_id","");
        String secret=p.getString("install_secret","");
        if(id.isEmpty()||secret.length()<32){
            id=UUID.randomUUID().toString();
            byte[] random=new byte[32];
            new SecureRandom().nextBytes(random);
            secret=Base64.encodeToString(random,Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);
            if(!p.edit().putString("install_id",id).putString("install_secret",secret).commit())throw new IllegalStateException("Identité d’archive non persistée");
        }
        return new Identity(id,secret);
    }

    private static String sha256(String value)throws Exception{
        return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] b){
        StringBuilder s=new StringBuilder(b.length*2);
        for(byte x:b)s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));
        return s.toString();
    }

    private static String read(InputStream in)throws IOException{
        if(in==null)return "";
        try(InputStream input=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;
            while((n=input.read(b))!=-1)out.write(b,0,n);
            return out.toString("UTF-8");
        }
    }

    static final class Identity{
        final String id,secret;
        Identity(String id,String secret){this.id=id;this.secret=secret;}
    }

    private static final class Segment{
        final long segment,firstId,lastId,firstObserved,lastObserved;
        final int count;
        Segment(long segment,long firstId,long lastId,int count,long firstObserved,long lastObserved){
            this.segment=segment;this.firstId=firstId;this.lastId=lastId;this.count=count;
            this.firstObserved=firstObserved;this.lastObserved=lastObserved;
        }
    }
}
