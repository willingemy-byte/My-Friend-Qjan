package fr.erick.journallocal;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

public final class ArchiveSyncTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static long scalar(SQLiteDatabase db,String sql){try(Cursor c=db.rawQuery(sql,null)){c.moveToFirst();return c.getLong(0);}}
    static String sha(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
    static Context fixture(File parent,String name,int count,boolean sealed){
        Context c=new Context(new File(parent,name));SQLiteDatabase db=new EventStore(c).db;
        db.beginTransaction();try{
            for(int i=0;i<count;i++){
                String raw=EventStore.object("app","Essai é","line","a\nb","index",i).toString();
                db.execSQL("INSERT INTO events VALUES(?,?,?,?,?,?,?,?)",new Object[]{11+2L*i,1000+i,"Essai","Observation","192.0.2.1","TCP","trafic",raw});
            }
            if(sealed)db.execSQL("UPDATE journal_segments SET first_id=11,last_id=?,event_count=?,sealed=1 WHERE segment=1",new Object[]{9+2L*count,count});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        c.getSharedPreferences("aiv_archive_status",0).edit().putLong("next_attempt_ms",Long.MAX_VALUE).commit();
        return c;
    }
    static final class Remote implements ArchiveSync.Transport {
        final TreeMap<Long,String> events=new TreeMap<>();int batches,posted,failBatch=-1;boolean lostAck,badSha,badCount,alreadyVerified;String identity,manifest;long count;
        public JSONObject send(ArchiveSync.Identity who,JSONObject body)throws Exception{
            if(identity==null)identity=who.id+":"+who.secret;else check(identity.equals(who.id+":"+who.secret),"identity survives process restart");
            String action=body.getString("action");
            if("begin".equals(action)){
                JSONObject s=body.getJSONObject("segment");manifest=s.getString("client_segment_sha256");count=s.getLong("expected_count");
                if(alreadyVerified)return receipt();
                return EventStore.object("ok",true,"state","UPLOADING","received_count",events.size());
            }
            if("batch".equals(action)){
                batches++;if(batches==failBatch&&!lostAck)throw new IOException("fixture network interruption");
                JSONArray batch=body.getJSONArray("events");posted+=batch.length();
                for(int i=0;i<batch.length();i++){
                    JSONObject e=batch.getJSONObject(i);String raw=e.getString("event_json");String hash=sha(raw);
                    check(hash.equals(e.getString("event_sha256")),"per-event SHA incl Unicode and LF");events.put(e.getLong("event_id"),hash);
                }
                if(batches==failBatch)throw new IOException("fixture lost acknowledgement");
                return EventStore.object("ok",true);
            }
            if("finalize".equals(action)){
                StringJoiner lines=new StringJoiner("\n");for(Map.Entry<Long,String> e:events.entrySet())lines.add(e.getKey()+":"+e.getValue());
                check(sha(lines.toString()).equals(manifest),"ordered LF manifest agrees independently");check(events.size()==count,"server count exact");return receipt();
            }
            throw new AssertionError(action);
        }
        JSONObject receipt(){return EventStore.object("ok",true,"state","VERIFIED","received_count",badCount?count-1:count,"server_segment_sha256",badSha?"0".repeat(64):manifest);}
    }
    static void failed(Context c,Remote remote)throws Exception{boolean caught=false;try{ArchiveSync.sync(c,remote);}catch(IOException e){caught=true;}check(caught,"failure rejected");check(scalar(EventStore.instance.db,"SELECT COUNT(*) FROM journal_archive_state WHERE remote_state='VERIFIED'")==0,"unverified data never marked verified");}
    public static void main(String[] args)throws Exception{
        File root=new File(args[0]);root.mkdirs();
        Context c=fixture(root,"resume",250,true);Remote remote=new Remote();remote.failBatch=3;
        failed(c,remote);check(scalar(EventStore.instance.db,"SELECT uploaded_through_id FROM journal_archive_state")==409,"only acknowledged prefix committed");
        EventStore.instance.db.close();c=new Context(c.root);new EventStore(c);remote.failBatch=-1;
        ArchiveSync.sync(c,remote);check(remote.posted==250&&remote.batches==4,"restart sent remaining 50 only");
        check(ArchiveSync.state(c).getLong("verified_segments")==1,"durable verified status");check(scalar(EventStore.instance.db,"SELECT COUNT(*) FROM events")==250,"raw observations preserved");EventStore.instance.db.close();

        c=fixture(root,"lost-ack",250,true);remote=new Remote();remote.failBatch=1;remote.lostAck=true;failed(c,remote);remote.failBatch=-1;
        ArchiveSync.sync(c,remote);check(remote.posted==350&&remote.events.size()==250,"lost ack replays idempotently without loss");EventStore.instance.db.close();
        c=fixture(root,"lost-server-prefix",250,true);remote=new Remote();remote.failBatch=3;failed(c,remote);remote.events.clear();remote.failBatch=-1;
        ArchiveSync.sync(c,remote);check(remote.events.size()==250,"remote count mismatch forces full replay");EventStore.instance.db.close();
        for(boolean wrongCount:new boolean[]{false,true}){
            c=fixture(root,"bad-final-"+wrongCount,17,true);remote=new Remote();remote.badSha=!wrongCount;remote.badCount=wrongCount;failed(c,remote);
            check(scalar(EventStore.instance.db,"SELECT COUNT(*) FROM events")==17,"bad receipt keeps data");remote.badSha=remote.badCount=false;
            ArchiveSync.sync(c,remote);check(remote.batches==1,"complete uploaded segment finalized without replay");EventStore.instance.db.close();
            c=fixture(root,"bad-begin-"+wrongCount,17,true);remote=new Remote();remote.alreadyVerified=true;remote.badSha=!wrongCount;remote.badCount=wrongCount;failed(c,remote);EventStore.instance.db.close();
        }
        c=fixture(root,"receipt-50000",50000,true);remote=new Remote();remote.alreadyVerified=true;
        ArchiveSync.sync(c,remote);check(remote.batches==0&&scalar(EventStore.instance.db,"SELECT COUNT(*) FROM events")==50000,"50k verified receipt skips 500 batches, retains original data");EventStore.instance.db.close();
        c=fixture(root,"bounded-backlog",500,true);
        EventStore.instance.db.execSQL("UPDATE journal_segments SET last_id=509,event_count=250 WHERE segment=1");
        EventStore.instance.db.execSQL("INSERT INTO journal_segments(segment,first_id,last_id,event_count,sealed) VALUES(2,511,1009,250,1)");
        ArchiveSync.sync(c,new Remote());check(ArchiveSync.state(c).getLong("verified_segments")==1&&ArchiveSync.state(c).getLong("pending_segments")==1,"one bounded segment per pass");
        ArchiveSync.sync(c,new Remote());check(ArchiveSync.state(c).getLong("verified_segments")==2&&scalar(EventStore.instance.db,"SELECT COUNT(*) FROM events")==500,"backlog resumes next pass without deleting raw data");
        c.getSharedPreferences("aiv_archive_status",0).edit().putString("last_error","fixture previous failure").commit();
        check(ArchiveSync.state(new Context(c.root)).getString("last_error").contains("previous failure"),"last failure persists across process prefs");EventStore.instance.db.close();
        c=fixture(root,"changed-manifest",17,true);remote=new Remote();remote.failBatch=1;failed(c,remote);EventStore.instance.db.execSQL("UPDATE events SET payload='{}' WHERE id=11");remote.failBatch=-1;failed(c,remote);EventStore.instance.db.close();

        System.out.println("PASS "+checks+" assertions: actual ArchiveSync, SQLite, durable identity, restart/resume, bad receipts, 50k manifest and bounded backlog");
    }
}
