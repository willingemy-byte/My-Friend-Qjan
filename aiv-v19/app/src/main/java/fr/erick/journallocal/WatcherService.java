package fr.erick.journallocal;
import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, low-priority scheduler. Same UID; no OS isolation boundary. */
public final class WatcherService extends Service {
    private static final String START="fr.erick.journallocal.AIV_START",STOP="fr.erick.journallocal.AIV_STOP",VERIFY="fr.erick.journallocal.AIV_VERIFY";
    public static volatile boolean running,analysisActive;
    public static volatile String operation="Analyse AIV en pause";
    private static volatile WatcherService active;
    private final AtomicBoolean cancelled=new AtomicBoolean(),verifyRequested=new AtomicBoolean(true);
    private HandlerThread thread;private Handler worker;
    private boolean loopStarted,verified;private int cycle;private AivVerifier verifier;
    public static void start(Context c){c.startForegroundService(new Intent(c,WatcherService.class).setAction(START));}
    public static void verify(Context c){c.startForegroundService(new Intent(c,WatcherService.class).setAction(VERIFY));}
    public static void stop(Context c){WatcherService service=active;if(service!=null)service.cancel();c.stopService(new Intent(c,WatcherService.class));}
    private WorkBudget budget(int rows){return new WorkBudget(SystemClock::uptimeMillis,cancelled::get,rows,WorkBudget.SLICE_MS);}
    private final Runnable scan=new Runnable(){public void run(){
        if(cancelled.get())return;long started=SystemClock.uptimeMillis(),pause=WorkBudget.MIN_PAUSE_MS;
        try{
            SQLiteDatabase db=EventStore.get(WatcherService.this).getWritableDatabase();
            if(verifyRequested.getAndSet(false)){verifier=new AivVerifier(db);verified=false;}
            if(!verified){
                if(verifier==null)verifier=new AivVerifier(db);
                boolean complete=verifier.advance(db,budget(WorkBudget.VERIFY_ROWS));
                operation="Vérification progressive : "+verifier.processed()+" / "+verifier.total();
                if(complete){
                    if(!verifier.valid()){AivStore.error="Chaîne brisée : analyse suspendue";analysisActive=false;cancelled.set(true);running=false;stopSelf();return;}
                    verified=true;AivStore.error="";
                    if(!analysisActive){operation="Vérification terminée; analyse en pause";cancelled.set(true);running=false;stopSelf();return;}
                }
            }else if(analysisActive){
                if(cycle++%2==0){int n=new MainEngine(WatcherService.this).drain(WorkBudget.EVENTS,budget(WorkBudget.EVENTS));operation=n>0?"Analyse progressive : "+n+" événements traités":"Analyse AIV : en attente d’événements";}
                else{AivStats.advance(db,budget(WorkBudget.STATS_ROWS));operation="Analyse AIV : statistiques progressives";}
                AivStore.error="";
            }
        }catch(CancellationException e){return;}
        catch(Exception e){AivStore.error="MAIN en attente : "+e.getClass().getSimpleName();operation="Analyse AIV en attente";pause=10000;}
        finally{
            pause=Math.max(pause,WorkBudget.pauseAfter(SystemClock.uptimeMillis()-started));
            if(!cancelled.get()&&running)worker.postDelayed(this,pause);
        }
    }};
    @Override public void onCreate(){super.onCreate();active=this;analysisActive=false;
        NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("aiv-watcher","AIV : observation locale",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,602,new Intent(this,WatcherService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"aiv-watcher").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("AIV : travail progressif").setContentText("Analyse locale limitée; arrêt disponible").setOngoing(true).addAction(new Notification.Action.Builder(null,"Arrêter AIV",stop).build()).build();
        startForeground(602,n);thread=new HandlerThread("aiv-watcher",android.os.Process.THREAD_PRIORITY_BACKGROUND);thread.start();worker=new Handler(thread.getLooper());
    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        String action=i==null?null:i.getAction();
        if(STOP.equals(action))Continuous.prefs(this).edit().putBoolean("analysis_enabled",false).apply();
        if(action==null&&Continuous.enabled(this)&&Continuous.prefs(this).getBoolean("analysis_enabled",true))action=START;
        if(cancelled.get()||(!START.equals(action)&&!VERIFY.equals(action))){cancel();stopSelf();return START_NOT_STICKY;}
        if(START.equals(action))analysisActive=true;else verifyRequested.set(true);
        running=true;operation=analysisActive?"Analyse AIV demandée":"Vérification demandée";
        if(!loopStarted){loopStarted=true;worker.postDelayed(scan,WorkBudget.MIN_PAUSE_MS);}
        return analysisActive?START_STICKY:START_NOT_STICKY;
    }
    private void cancel(){
        cancelled.set(true);running=false;analysisActive=false;
        if(AivStore.verification.startsWith("VERIFYING"))AivStore.verification="PAUSED";
        operation="Analyse AIV en pause";if(worker!=null)worker.removeCallbacksAndMessages(null);
    }
    @Override public void onDestroy(){cancel();if(active==this)active=null;if(thread!=null)thread.quitSafely();stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}