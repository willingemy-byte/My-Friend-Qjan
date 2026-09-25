package fr.erick.journallocal;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import org.json.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Compatible with the V13 on-device index. Events remain in the original database. */
public final class JournalSegments {
    public static final int LIMIT=50000;
    private static final AtomicBoolean scheduled=new AtomicBoolean();
    private static final HandlerThread thread=new HandlerThread("journal-segments",android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private static Handler worker;
    public static volatile String error="";
    public static void install(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS journal_segment_state(id INTEGER PRIMARY KEY CHECK(id=1),checkpoint INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT 0,active INTEGER NOT NULL DEFAULT 1,network INTEGER NOT NULL DEFAULT 0,unresolved INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS journal_segments(segment INTEGER PRIMARY KEY,first_id INTEGER NOT NULL DEFAULT 0,last_id INTEGER NOT NULL DEFAULT 0,event_count INTEGER NOT NULL DEFAULT 0,sealed INTEGER NOT NULL DEFAULT 0,network INTEGER NOT NULL DEFAULT 0,unresolved INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS journal_segment_actors(segment INTEGER NOT NULL,app TEXT NOT NULL,PRIMARY KEY(segment,app))");
        db.execSQL("INSERT OR IGNORE INTO journal_segment_state(id) VALUES(1)");
        db.execSQL("INSERT OR IGNORE INTO journal_segments(segment) VALUES(1)");
    }
    public static synchronized void request(Context context){
        if(!scheduled.compareAndSet(false,true))return;
        if(worker==null){thread.start();worker=new Handler(thread.getLooper());}
        Context app=context.getApplicationContext();worker.post(()->advance(app));
    }
    private static void advance(Context context){
        boolean more=false;
        try{
            SQLiteDatabase db=EventStore.get(context).getWritableDatabase();db.beginTransaction();
            try{
                long checkpoint=0,segment=1,count=0;boolean sealed=false;
                try(Cursor c=db.rawQuery("SELECT checkpoint,active FROM journal_segment_state WHERE id=1",null)){if(c.moveToFirst()){checkpoint=c.getLong(0);segment=c.getLong(1);}}
                try(Cursor c=db.rawQuery("SELECT event_count,sealed FROM journal_segments WHERE segment=?",new String[]{""+segment})){if(c.moveToFirst()){count=c.getLong(0);sealed=c.getInt(1)!=0;}}
                int read=0;
                try(Cursor c=db.rawQuery("SELECT id,app,category,payload FROM events WHERE id>? ORDER BY id LIMIT 500",new String[]{""+checkpoint})){
                    while(c.moveToNext()){
                        if(count>=LIMIT||sealed){sealed=false;db.execSQL("UPDATE journal_segments SET sealed=1 WHERE segment=?",new Object[]{segment});segment++;count=0;db.execSQL("INSERT OR IGNORE INTO journal_segments(segment) VALUES(?)",new Object[]{segment});db.execSQL("UPDATE journal_segment_state SET active=? WHERE id=1",new Object[]{segment});}
                        long id=c.getLong(0);boolean network="trafic".equals(c.getString(2))||"dns".equals(c.getString(2));int unresolved=0;
                        if(network){try{JSONObject d=new JSONObject(c.getString(3)).optJSONObject("details");unresolved=d==null||d.optInt("uid",-1)<0||d.optJSONArray("packages")==null||d.optJSONArray("packages").length()!=1?1:0;}catch(Exception ignored){unresolved=1;}}
                        db.execSQL("UPDATE journal_segments SET first_id=CASE WHEN event_count=0 THEN ? ELSE first_id END,last_id=?,event_count=event_count+1,network=network+?,unresolved=unresolved+? WHERE segment=? AND sealed=0",new Object[]{id,id,network?1:0,unresolved,segment});
                        db.execSQL("INSERT OR IGNORE INTO journal_segment_actors(segment,app) VALUES(?,?)",new Object[]{segment,c.getString(1)});
                        db.execSQL("UPDATE journal_segment_state SET checkpoint=?,total=total+1,network=network+?,unresolved=unresolved+? WHERE id=1",new Object[]{id,network?1:0,unresolved});
                        checkpoint=id;count++;read++;
                    }
                }
                if(count==LIMIT)db.execSQL("UPDATE journal_segments SET sealed=1 WHERE segment=?",new Object[]{segment});
                more=read==500;db.setTransactionSuccessful();
            }finally{db.endTransaction();}error="";
        }catch(Exception e){error="Index des segments : "+e.getClass().getSimpleName();}
        finally{scheduled.set(false);if(more&&error.isEmpty())worker.postDelayed(()->request(context),250);}
    }
    public static JSONObject window(Context context,int requested)throws Exception{
        request(context);SQLiteDatabase db=EventStore.get(context).getReadableDatabase();long active=1,checkpoint=0,total=0,latest=EventStore.get(context).latestId();
        try(Cursor c=db.rawQuery("SELECT checkpoint,total,active FROM journal_segment_state WHERE id=1",null)){if(c.moveToFirst()){checkpoint=c.getLong(0);total=c.getLong(1);active=c.getLong(2);}}
        JSONObject out=EventStore.object("active",active,"checkpoint",checkpoint,"total",total,"latest",latest,"error",error,"partial",checkpoint<latest);
        if(requested==0&&checkpoint<latest)return out.put("segment",0).put("first_id",Math.max(1,latest-LIMIT+1)).put("last_id",latest).put("event_count",JSONObject.NULL).put("sealed",false);
        long segment=requested>0?requested:active;
        if(requested==0)try(Cursor c=db.rawQuery("SELECT segment FROM journal_segments WHERE event_count>0 ORDER BY segment DESC LIMIT 1",null)){if(c.moveToFirst())segment=c.getLong(0);}
        try(Cursor c=db.rawQuery("SELECT first_id,last_id,event_count,sealed FROM journal_segments WHERE segment=?",new String[]{""+segment})){
            if(!c.moveToFirst())throw new IllegalArgumentException("Segment non indexé. Choisir 0 pour les événements récents.");
            return out.put("segment",segment).put("first_id",c.getLong(0)).put("last_id",c.getLong(1)).put("event_count",c.getLong(2)).put("sealed",c.getInt(3)!=0);
        }
    }
}