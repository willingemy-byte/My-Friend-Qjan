package fr.erick.journallocal;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** Derived counters only. Cancellation rolls back the counters and checkpoint together. */
public final class AivStats {
    private AivStats(){}
    public static int advance(SQLiteDatabase db,WorkBudget budget){
        budget.checkCancelled();int processed=0,findings=0;db.beginTransaction();try{
            long at=AivStore.number(db,"SELECT checkpoint FROM aiv_stats_state WHERE id=1");
            try(Cursor c=db.rawQuery(AivQueries.STATS_BATCH,new String[]{""+at,""+WorkBudget.STATS_ROWS})){
                while(c.moveToNext()&&budget.next()){
                    String app=c.getString(2);int flag="ALLOW".equals(c.getString(1))?0:1;
                    ContentValues v=new ContentValues();v.put("app",app);v.put("evaluated",0);v.put("findings",0);
                    db.insertWithOnConflict("aiv_app_stats",null,v,SQLiteDatabase.CONFLICT_IGNORE);
                    db.execSQL("UPDATE aiv_app_stats SET evaluated=evaluated+1,findings=findings+? WHERE app=?",new Object[]{flag,app});
                    at=c.getLong(0);processed++;findings+=flag;
                }
            }
            if(processed>0)db.execSQL("UPDATE aiv_stats_state SET checkpoint=?,evaluated=evaluated+?,findings=findings+? WHERE id=1",new Object[]{at,processed,findings});
            budget.checkCancelled();db.setTransactionSuccessful();
        }finally{db.endTransaction();}return processed;
    }
}