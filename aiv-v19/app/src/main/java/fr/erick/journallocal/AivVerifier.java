package fr.erick.journallocal;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

/** Checks an immutable captured prefix in bounded pages without a writer transaction. */
public final class AivVerifier {
    private final long checkpoint,ceiling,decisionCeiling;private final String head;
    private long at;private String previous=ChainStore.GENESIS,failure="";private boolean done;
    public AivVerifier(SQLiteDatabase db){
        try(Cursor c=db.rawQuery(AivQueries.PROGRESS,null)){
            if(!c.moveToFirst())throw new IllegalStateException("État AIV absent");
            checkpoint=c.getLong(0);ceiling=c.getLong(1);head=c.getString(2);decisionCeiling=c.getLong(4);
        }publish();
    }
    private void broken(String message){failure=message;done=true;}
    private void publish(){AivStore.verification=done?(failure.isEmpty()?"VALID_AT_"+at:"BROKEN: "+failure):"VERIFYING_"+at+"_OF_"+ceiling;}
    private static String pack(Cursor c,String kind,int offset,int count){String[] fields=new String[count+1];fields[0]=kind;for(int i=0;i<count;i++)fields[i+1]=c.isNull(offset+i)?null:c.getString(offset+i);return ChainStore.pack(fields);}
    private static long count(SQLiteDatabase db,String sql,long ceiling){try(Cursor c=db.rawQuery(sql,new String[]{""+ceiling})){c.moveToFirst();return c.getLong(0);}}
    public boolean advance(SQLiteDatabase db,WorkBudget budget){
        budget.checkCancelled();if(done)return true;
        if(at<ceiling){boolean any=false;
            try(Cursor c=db.rawQuery(AivQueries.VERIFY_PAGE,new String[]{""+at,""+ceiling,""+WorkBudget.VERIFY_ROWS})){
                while(c.moveToNext()&&budget.next()){
                    any=true;long seq=c.getLong(0);String kind=c.getString(3),payload=c.getString(7),self=c.getString(6);
                    if(seq!=at+1||!previous.equals(c.getString(5))||!ChainStore.hash(previous,payload,c.getLong(4),seq).equals(self)){broken("Chaîne altérée à "+seq);break;}
                    boolean event="EVENT".equals(kind),decision="DECISION".equals(kind);int offset=event?8:19;
                    if((!event&&!decision)||c.isNull(offset)){broken("Projection absente à "+seq);break;}
                    String expected=pack(c,kind,offset,event?9:7);
                    if(!payload.equals(expected)||!previous.equals(c.getString(event?17:26))||!self.equals(c.getString(event?18:27))){broken("Projection altérée à "+seq);break;}
                    previous=self;at=seq;
                }
            }if(!any&&!done)broken("Chaîne tronquée à "+(at+1));
        }
        budget.checkCancelled();
        if(!done&&at==ceiling){
            long events=count(db,AivQueries.COUNT_SEALED_EVENTS,checkpoint);budget.checkCancelled();
            long decisions=count(db,AivQueries.COUNT_SNAPSHOT_DECISIONS,decisionCeiling);budget.checkCancelled();
            if(!previous.equals(head)||events*2!=ceiling||decisions*2!=ceiling)broken("Tête, cardinalité ou reprise incohérente");else done=true;
        }publish();return done;
    }
    public long processed(){return at;}public long total(){return ceiling;}public boolean valid(){return done&&failure.isEmpty();}
    public JSONObject result(){return EventStore.object("valid",valid(),"count",at,"head",previous,"status",done?(valid()?"VALID_AT_"+at:"BROKEN: "+failure):"VERIFYING_"+at+"_OF_"+ceiling,"scope","Préfixe local capturé; comparer à une tête signée conservée séparément pour détecter une réécriture ou troncature complète");}
}