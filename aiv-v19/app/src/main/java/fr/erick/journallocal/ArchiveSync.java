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

    private ArchiveSync(){}

    public static void request(Context context){
        if(!running.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{
                sync(app);
                lastError="";
            }catch(Exception e){
                lastError="Archive Supabase : "+e.getClass().getSimpleName()+(e.getMessage()==null?"":" · "+e.getMessage());
            }finally{
                running.set(false);
            }
        },"aiv-archive-sync").start();
    }

    public static JSONObject state(Context context){
        JSONObject out=EventStore.object(
            "running",running.get(),
            "last_error",lastError,
            "last_verified_segment",lastVerifiedSegment,
            "segment_size",JournalSegments.LIMIT,
            "remote","Supabase"
        );
        try{
            SQLiteDatabase db=EventStore.get(context).getReadableDatabase();
            try(Cursor c=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN remote_state='VERIFIED' THEN 1 ELSE 0 END),0),COALESCE(MAX(CASE WHEN remote_state='VERIFIED' THEN segment ELSE 0 END),0) FROM journal_archive_state",null)){
                if(c.moveToFirst()){
                    out.put("tracked_segments",c.getLong(0));
                    out.put("verified_segments",c.getLong(1));
                    out.put("last_verified_segment",c.getLong(2));
                }
            }
        }catch(Exception e){
            try{out.put("state_error",e.getClass().getSimpleName());}catch(Exception ignored){}
        }
        return out;
    }

    private static void sync(Context context)throws Exception{
        Identity identity=identity(context);
        SQLiteDatabase db=EventStore.get(context).getWritableDatabase();

        while(true){
            Segment s=nextSegment(db);
            if(s==null)return;

            String manifest=segmentManifest(db,s);
            db.execSQL("INSERT OR REPLACE INTO journal_archive_state(segment,remote_state,client_sha256,uploaded_through_id,verified_at_ms,error) VALUES(?,?,?,?,?,?)",
                new Object[]{s.segment,"UPLOADING",manifest,0,0,null});

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
            JSONObject beginReply=post(identity,begin);
            if("VERIFIED".equals(beginReply.optString("state"))){
                markVerified(db,s.segment,manifest);
                continue;
            }

            uploadEvents(db,identity,s);

            JSONObject finalizeReply=post(identity,EventStore.object(
                "schema","aiv-journal/1",
                "action","finalize",
                "segment_no",s.segment
            ));
            if(!"VERIFIED".equals(finalizeReply.optString("state")))
                throw new IOException("Segment "+s.segment+" non vérifié à distance");

            String remoteSha=finalizeReply.optString("server_segment_sha256");
            if(!manifest.equalsIgnoreCase(remoteSha))
                throw new IOException("SHA distant différent pour segment "+s.segment);

            markVerified(db,s.segment,manifest);
        }
    }

    private static Segment nextSegment(SQLiteDatabase db){
        String sql="SELECT s.segment,s.first_id,s.last_id,s.event_count,"+
            "(SELECT MIN(timestamp_ms) FROM events WHERE id BETWEEN s.first_id AND s.last_id),"+
            "(SELECT MAX(timestamp_ms) FROM events WHERE id BETWEEN s.first_id AND s.last_id) "+
            "FROM journal_segments s LEFT JOIN journal_archive_state a ON a.segment=s.segment "+
            "WHERE s.sealed=1 AND s.event_count>0 AND COALESCE(a.remote_state,'')!='VERIFIED' "+
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

    private static void uploadEvents(SQLiteDatabase db,Identity identity,Segment s)throws Exception{
        JSONArray batch=new JSONArray();
        int approximateChars=0;
        long uploadedThrough=0;

        try(Cursor c=db.rawQuery("SELECT id,timestamp_ms,app,action,destination,transport,category,payload FROM events WHERE id>=? AND id<=? ORDER BY id",
            new String[]{String.valueOf(s.firstId),String.valueOf(s.lastId)})){
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
                    sendBatch(identity,s.segment,batch);
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
            sendBatch(identity,s.segment,batch);
            uploadedThrough=batch.getJSONObject(batch.length()-1).getLong("event_id");
            saveProgress(db,s.segment,uploadedThrough);
        }
    }

    private static void sendBatch(Identity identity,long segment,JSONArray events)throws Exception{
        JSONObject reply=post(identity,EventStore.object(
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

    private static void markVerified(SQLiteDatabase db,long segment,String sha){
        long now=System.currentTimeMillis();
        db.execSQL("UPDATE journal_archive_state SET remote_state='VERIFIED',client_sha256=?,verified_at_ms=?,error=NULL WHERE segment=?",
            new Object[]{sha,now,segment});
        lastVerifiedSegment=segment;
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
            p.edit().putString("install_id",id).putString("install_secret",secret).apply();
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

    private static final class Identity{
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
