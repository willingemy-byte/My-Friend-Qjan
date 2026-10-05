package fr.erick.journallocal;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verified-prefix pruning for the local journal.
 *
 * A segment can be deleted only after a durable VERIFIED receipt is present,
 * the local manifest still matches, every local analysis checkpoint has passed
 * the segment, and the segment is the oldest remaining prefix of the hash chain.
 */
public final class JournalPurge {
    private static final AtomicBoolean running=new AtomicBoolean();
    public static volatile String lastError="";
    public static volatile long lastPurgedSegment=0;

    private JournalPurge(){}

    private static boolean column(SQLiteDatabase db,String table,String name){
        try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)){
            while(c.moveToNext())if(name.equals(c.getString(1)))return true;
        }
        return false;
    }

    public static void install(SQLiteDatabase db){
        if(!column(db,"journal_archive_state","first_id"))db.execSQL("ALTER TABLE journal_archive_state ADD COLUMN first_id INTEGER NOT NULL DEFAULT 0");
        if(!column(db,"journal_archive_state","last_id"))db.execSQL("ALTER TABLE journal_archive_state ADD COLUMN last_id INTEGER NOT NULL DEFAULT 0");
        if(!column(db,"journal_archive_state","expected_count"))db.execSQL("ALTER TABLE journal_archive_state ADD COLUMN expected_count INTEGER NOT NULL DEFAULT 0");
        if(!column(db,"journal_archive_state","server_sha256"))db.execSQL("ALTER TABLE journal_archive_state ADD COLUMN server_sha256 TEXT");
        if(!column(db,"journal_archive_state","purge_state"))db.execSQL("ALTER TABLE journal_archive_state ADD COLUMN purge_state TEXT NOT NULL DEFAULT 'LOCAL'");
        if(!column(db,"journal_archive_state","purged_at_ms"))db.execSQL("ALTER TABLE journal_archive_state ADD COLUMN purged_at_ms INTEGER NOT NULL DEFAULT 0");
        if(!column(db,"journal_archive_state","purge_error"))db.execSQL("ALTER TABLE journal_archive_state ADD COLUMN purge_error TEXT");

        db.execSQL("CREATE TABLE IF NOT EXISTS journal_purge_guard(id INTEGER PRIMARY KEY CHECK(id=1),allowed INTEGER NOT NULL DEFAULT 0 CHECK(allowed IN (0,1)))");
        db.execSQL("INSERT OR IGNORE INTO journal_purge_guard(id,allowed) VALUES(1,0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS journal_purge_anchor(id INTEGER PRIMARY KEY CHECK(id=1),segment INTEGER NOT NULL DEFAULT 0,last_event_id INTEGER NOT NULL DEFAULT 0,chain_count INTEGER NOT NULL DEFAULT 0,chain_head TEXT NOT NULL,updated_ms INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT OR IGNORE INTO journal_purge_anchor(id,chain_head) VALUES(1,?)",new Object[]{ChainStore.GENESIS});

        // Preserve append-only semantics outside the one guarded verified-purge transaction.
        db.execSQL("DROP TRIGGER IF EXISTS aiv_events_delete");
        db.execSQL("DROP TRIGGER IF EXISTS aiv_decisions_delete");
        db.execSQL("DROP TRIGGER IF EXISTS aiv_chain_delete");
        db.execSQL("CREATE TRIGGER aiv_events_delete BEFORE DELETE ON events WHEN (SELECT allowed FROM journal_purge_guard WHERE id=1)<>1 BEGIN SELECT RAISE(ABORT,'AIV append-only'); END");
        db.execSQL("CREATE TRIGGER aiv_decisions_delete BEFORE DELETE ON decisions WHEN (SELECT allowed FROM journal_purge_guard WHERE id=1)<>1 BEGIN SELECT RAISE(ABORT,'AIV append-only'); END");
        db.execSQL("CREATE TRIGGER aiv_chain_delete BEFORE DELETE ON journal_chain WHEN (SELECT allowed FROM journal_purge_guard WHERE id=1)<>1 BEGIN SELECT RAISE(ABORT,'AIV append-only'); END");
    }

    static JSONObject anchor(SQLiteDatabase db){
        try(Cursor c=db.rawQuery("SELECT segment,last_event_id,chain_count,chain_head,updated_ms FROM journal_purge_anchor WHERE id=1",null)){
            if(c.moveToFirst())return EventStore.object("segment",c.getLong(0),"last_event_id",c.getLong(1),"chain_count",c.getLong(2),"chain_head",c.getString(3),"updated_ms",c.getLong(4));
        }
        return EventStore.object("segment",0,"last_event_id",0,"chain_count",0,"chain_head",ChainStore.GENESIS,"updated_ms",0);
    }

    public static JSONObject receiptForEvent(Context context,long eventId){
        try(Cursor c=EventStore.get(context).getReadableDatabase().rawQuery(
            "SELECT segment,first_id,last_id,expected_count,client_sha256,server_sha256,verified_at_ms,purged_at_ms,purge_state FROM journal_archive_state WHERE first_id<=? AND last_id>=? ORDER BY segment LIMIT 1",
            new String[]{String.valueOf(eventId),String.valueOf(eventId)})){
            if(c.moveToFirst())return EventStore.object("segment_no",c.getLong(0),"first_event_id",c.getLong(1),"last_event_id",c.getLong(2),"expected_count",c.getLong(3),"client_segment_sha256",c.getString(4),"server_segment_sha256",c.isNull(5)?JSONObject.NULL:c.getString(5),"verified_at_ms",c.getLong(6),"purged_at_ms",c.getLong(7),"purge_state",c.getString(8));
        }catch(Exception ignored){}
        return null;
    }

    public static void recordVerified(SQLiteDatabase db,long segment,long first,long last,int count,String clientSha,String serverSha,long verifiedAt)throws IOException{
        if(first<=0||last<first||count<1||clientSha==null||serverSha==null||!clientSha.equalsIgnoreCase(serverSha))
            throw new IOException("Reçu distant invalide pour segment "+segment);
        try(Cursor c=db.rawQuery("SELECT first_id,last_id,event_count,sealed FROM journal_segments WHERE segment=?",new String[]{String.valueOf(segment)})){
            if(!c.moveToFirst()||c.getInt(3)==0||c.getLong(0)!=first||c.getLong(1)!=last||c.getInt(2)!=count)
                throw new IOException("Reçu distant incompatible avec le segment local "+segment);
        }
        db.beginTransaction();
        try{
            db.execSQL("UPDATE journal_archive_state SET remote_state='VERIFIED',first_id=?,last_id=?,expected_count=?,client_sha256=?,server_sha256=?,verified_at_ms=?,purge_state=CASE WHEN purge_state='PURGED' THEN 'PURGED' ELSE 'REMOTE_VERIFIED' END,error=NULL,purge_error=NULL WHERE segment=?",
                new Object[]{first,last,count,clientSha.toLowerCase(java.util.Locale.ROOT),serverSha.toLowerCase(java.util.Locale.ROOT),verifiedAt,segment});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    public static void request(Context context){
        if(!running.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{
                while(purgeOne(app)){}
                lastError="";
            }catch(Exception e){
                lastError="Purge locale : "+e.getClass().getSimpleName()+(e.getMessage()==null?"":" · "+e.getMessage());
            }finally{running.set(false);}
        },"aiv-journal-purge").start();
    }

    private static boolean purgeOne(Context context)throws Exception{
        SQLiteDatabase db=EventStore.get(context).getWritableDatabase();
        long segment,first,last,expected;String clientSha,serverSha,purgeState;
        try(Cursor c=db.rawQuery("SELECT segment,first_id,last_id,expected_count,client_sha256,server_sha256,purge_state FROM journal_archive_state WHERE remote_state='VERIFIED' AND purge_state IN ('REMOTE_VERIFIED','PURGE_FAILED') ORDER BY segment LIMIT 1",null)){
            if(!c.moveToFirst())return false;
            segment=c.getLong(0);first=c.getLong(1);last=c.getLong(2);expected=c.getLong(3);clientSha=c.getString(4);serverSha=c.getString(5);purgeState=c.getString(6);
        }
        try{
            if(clientSha==null||serverSha==null||!clientSha.equalsIgnoreCase(serverSha))throw new IOException("SHA client/serveur différent");
            try(Cursor c=db.rawQuery("SELECT first_id,last_id,event_count,sealed FROM journal_segments WHERE segment=?",new String[]{String.valueOf(segment)})){
                if(!c.moveToFirst()||c.getInt(3)==0||c.getLong(0)!=first||c.getLong(1)!=last||c.getLong(2)!=expected)throw new IOException("Métadonnées locales du segment différentes du reçu");
            }
            try(Cursor c=db.rawQuery("SELECT active FROM journal_segment_state WHERE id=1",null)){if(c.moveToFirst()&&c.getLong(0)==segment)return waiting(db,segment,"Segment encore actif");}
            long minLocal;
            try(Cursor c=db.rawQuery("SELECT COALESCE(MIN(id),0) FROM events",null)){c.moveToFirst();minLocal=c.getLong(0);}
            if(minLocal==0){
                JSONObject existingAnchor=anchor(db);
                if(existingAnchor.optLong("last_event_id",0)<last)throw new IOException("Journal vide sans ancre de purge correspondante");
                markAlreadyPurged(db,segment);
                return true;
            }
            if(minLocal!=first)return waiting(db,segment,"En attente du segment local précédent");
            long localCount;
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM events WHERE id>=? AND id<=?",new String[]{String.valueOf(first),String.valueOf(last)})){c.moveToFirst();localCount=c.getLong(0);}
            if(localCount!=expected)throw new IOException("Segment local incomplet avant purge : "+localCount+" / "+expected);
            String localManifest=manifest(db,first,last,(int)expected);
            if(!clientSha.equalsIgnoreCase(localManifest))throw new IOException("Manifeste local différent du reçu VERIFIED");

            long mainCheckpoint=AivStore.number(db,"SELECT checkpoint FROM aiv_state WHERE id=1");
            long auditCheckpoint=AivStore.number(db,"SELECT checkpoint FROM audit_state WHERE id=1");
            if(mainCheckpoint<last||auditCheckpoint<last)return waiting(db,segment,"En attente des analyses locales");
            long lastDecision=0;try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM decisions WHERE event_id>=? AND event_id<=?",new String[]{String.valueOf(first),String.valueOf(last)})){c.moveToFirst();lastDecision=c.getLong(0);}
            long statsCheckpoint=AivStore.number(db,"SELECT checkpoint FROM aiv_stats_state WHERE id=1");
            if(lastDecision>0&&statsCheckpoint<lastDecision)return waiting(db,segment,"En attente des statistiques AIV");
            try{if(AnomalyMonitor.get(context).summary().optLong("checkpoint",0)<last)return waiting(db,segment,"En attente de l’analyse des anomalies");}
            catch(Exception e){return waiting(db,segment,"État d’analyse des anomalies temporairement indisponible");}

            JSONObject anchor=anchor(db);long anchorCount=anchor.getLong("chain_count");String anchorHead=anchor.getString("chain_head");
            long chainFirst=0,chainLast=0,chainRows=0;
            try(Cursor c=db.rawQuery("SELECT MIN(id),MAX(id),COUNT(*) FROM journal_chain WHERE event_id>=? AND event_id<=?",new String[]{String.valueOf(first),String.valueOf(last)})){
                c.moveToFirst();chainFirst=c.getLong(0);chainLast=c.getLong(1);chainRows=c.getLong(2);
            }
            if(chainRows!=expected*2L||chainFirst!=anchorCount+1)throw new IOException("Préfixe de chaîne non contigu");
            String lastHead=verifyChainSegment(db,first,last,anchorCount,anchorHead);
            if(chainLast!=anchorCount+chainRows||lastHead.length()!=64)throw new IOException("Ancrage de chaîne incompatible");

            db.beginTransaction();
            try{
                db.execSQL("UPDATE journal_archive_state SET purge_state='PURGING',purge_error=NULL WHERE segment=?",new Object[]{segment});
                db.execSQL("UPDATE journal_purge_guard SET allowed=1 WHERE id=1");
                db.delete("journal_chain","event_id>=? AND event_id<=?",new String[]{String.valueOf(first),String.valueOf(last)});
                db.delete("decisions","event_id>=? AND event_id<=?",new String[]{String.valueOf(first),String.valueOf(last)});
                db.delete("event_audit","event_id>=? AND event_id<=?",new String[]{String.valueOf(first),String.valueOf(last)});
                db.delete("events","id>=? AND id<=?",new String[]{String.valueOf(first),String.valueOf(last)});
                db.execSQL("UPDATE journal_purge_anchor SET segment=?,last_event_id=?,chain_count=?,chain_head=?,updated_ms=? WHERE id=1",
                    new Object[]{segment,last,chainLast,lastHead,System.currentTimeMillis()});
                db.execSQL("UPDATE journal_archive_state SET purge_state='PURGED',purged_at_ms=?,purge_error=NULL WHERE segment=?",
                    new Object[]{System.currentTimeMillis(),segment});
                db.execSQL("UPDATE journal_purge_guard SET allowed=0 WHERE id=1");
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            lastPurgedSegment=segment;
            try{db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).close();}catch(Exception ignored){}
            return true;
        }catch(Exception e){
            try{db.execSQL("UPDATE journal_archive_state SET purge_state='PURGE_FAILED',purge_error=? WHERE segment=?",new Object[]{e.getMessage(),segment});}catch(Exception ignored){}
            throw e;
        }
    }

    private static boolean waiting(SQLiteDatabase db,long segment,String reason){
        db.execSQL("UPDATE journal_archive_state SET purge_state='REMOTE_VERIFIED',purge_error=? WHERE segment=?",new Object[]{reason,segment});
        lastError=reason;
        return false;
    }

    private static String verifyChainSegment(SQLiteDatabase db,long first,long last,long anchorCount,String anchorHead)throws Exception{
        long expectedSeq=anchorCount;String previous=anchorHead;long rows=0;
        try(Cursor c=db.rawQuery("SELECT id,timestamp_ms,hash_prev,hash_self,payload FROM journal_chain WHERE event_id>=? AND event_id<=? ORDER BY id",new String[]{String.valueOf(first),String.valueOf(last)})){
            while(c.moveToNext()){
                long seq=c.getLong(0),timestamp=c.getLong(1);String prev=c.getString(2),self=c.getString(3),payload=c.getString(4);
                if(seq!=++expectedSeq||!previous.equals(prev)||!ChainStore.hash(previous,payload,timestamp,seq).equals(self))
                    throw new IOException("Chaîne locale altérée avant purge à "+seq);
                previous=self;rows++;
            }
        }
        if(rows==0)throw new IOException("Chaîne locale absente avant purge");
        return previous;
    }

    private static void markAlreadyPurged(SQLiteDatabase db,long segment){
        db.execSQL("UPDATE journal_archive_state SET purge_state='PURGED',purged_at_ms=CASE WHEN purged_at_ms=0 THEN ? ELSE purged_at_ms END,purge_error=NULL WHERE segment=?",
            new Object[]{System.currentTimeMillis(),segment});
        lastPurgedSegment=segment;
    }

    private static String manifest(SQLiteDatabase db,long first,long last,int expected)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");boolean firstRow=true;int count=0;
        try(Cursor c=db.rawQuery("SELECT id,payload FROM events WHERE id>=? AND id<=? ORDER BY id",new String[]{String.valueOf(first),String.valueOf(last)})){
            while(c.moveToNext()){
                String raw=c.getString(1);String eventSha=hex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
                if(!firstRow)digest.update((byte)'\n');firstRow=false;
                digest.update((c.getLong(0)+":"+eventSha).getBytes(StandardCharsets.UTF_8));count++;
            }
        }
        if(count!=expected)throw new IOException("Manifest local incomplet");
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes){
        StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return out.toString();
    }

    public static JSONObject state(Context context){
        JSONObject out=EventStore.object("running",running.get(),"last_error",lastError,"last_purged_segment",lastPurgedSegment);
        try{
            SQLiteDatabase db=EventStore.get(context).getReadableDatabase();
            JSONObject a=anchor(db);out.put("anchor",a);
            try(Cursor c=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN purge_state='PURGED' THEN expected_count ELSE 0 END),0),COALESCE(SUM(CASE WHEN purge_state='REMOTE_VERIFIED' THEN expected_count ELSE 0 END),0) FROM journal_archive_state",null)){
                if(c.moveToFirst()){out.put("receipts",c.getLong(0));out.put("purged_events",c.getLong(1));out.put("ready_to_purge_events",c.getLong(2));}
            }
            try(Cursor c=db.rawQuery("SELECT segment,first_id,last_id,expected_count,client_sha256,server_sha256,verified_at_ms,purged_at_ms,purge_state,purge_error FROM journal_archive_state ORDER BY segment DESC LIMIT 20",null)){
                org.json.JSONArray rows=new org.json.JSONArray();
                while(c.moveToNext())rows.put(EventStore.object("segment_no",c.getLong(0),"first_event_id",c.getLong(1),"last_event_id",c.getLong(2),"expected_count",c.getLong(3),"client_segment_sha256",c.isNull(4)?JSONObject.NULL:c.getString(4),"server_segment_sha256",c.isNull(5)?JSONObject.NULL:c.getString(5),"verified_at_ms",c.getLong(6),"purged_at_ms",c.getLong(7),"purge_state",c.getString(8),"purge_error",c.isNull(9)?JSONObject.NULL:c.getString(9)));
                out.put("segments",rows);
            }
        }catch(Exception e){try{out.put("state_error",e.getClass().getSimpleName());}catch(Exception ignored){}}
        return out;
    }
}
