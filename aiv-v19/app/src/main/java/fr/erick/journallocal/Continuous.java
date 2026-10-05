package fr.erick.journallocal;
import android.content.*;
import android.net.VpnService;

/** Persist user intent separately from process liveness. Never clears a database. */
public final class Continuous {
    static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("continuous",0);}
    public static void initialize(Context c){
        if(!prefs(c).contains("enabled"))prefs(c).edit().putBoolean("enabled",true).putBoolean("vpn_enabled",false).putBoolean("analysis_enabled",true).apply();
        if(clearLegacyStoragePause(c)||c.getSharedPreferences("aiv_storage_guard",0).getBoolean("resume_pending",false))start(c);
    }
    /** Undo only the pause introduced by 2.0.12; preserve every user start/stop setting. */
    private static synchronized boolean clearLegacyStoragePause(Context c){
        android.content.SharedPreferences legacy=c.getSharedPreferences("aiv_storage_guard",0);
        if(!legacy.getBoolean("paused",false))return false;
        if(!legacy.edit().putBoolean("paused",false).putString("reason","").putLong("paused_at_ms",0).putBoolean("resume_pending",enabled(c)).commit())return false;
        try{
            android.app.NotificationManager notifications=c.getSystemService(android.app.NotificationManager.class);
            if(notifications!=null)notifications.cancel(2);
        }catch(Exception ignored){}
        return true;
    }
    public static boolean enabled(Context c){return prefs(c).getBoolean("enabled",false);}
    public static void start(Context c){
        clearLegacyStoragePause(c);
        if(!enabled(c))return;
        try{if(!RecorderService.running)c.startForegroundService(new Intent(c,RecorderService.class));}catch(Exception e){EventStore.lastError="Reprise du collecteur : "+e.getClass().getSimpleName();}
        try{if(prefs(c).getBoolean("analysis_enabled",true)&&!WatcherService.running)WatcherService.start(c);}catch(Exception e){AivStore.error="Reprise de l’analyse : "+e.getClass().getSimpleName();}
        try{if(prefs(c).getBoolean("vpn_enabled",true)&&!NetworkCaptureService.running&&!NetworkCaptureService.starting){if(VpnService.prepare(c)==null)c.startForegroundService(new Intent(c,NetworkCaptureService.class));else NetworkCaptureService.lastError="Autorisation VPN requise dans Paramètres → Collecte continue.";}}catch(Exception e){NetworkCaptureService.lastError="Reprise VPN : "+e.getClass().getSimpleName();}
        if(RecorderService.running)c.getSharedPreferences("aiv_storage_guard",0).edit().putBoolean("resume_pending",false).apply();
    }
    public static void stop(Context c){prefs(c).edit().putBoolean("enabled",false).commit();c.getSharedPreferences("aiv_storage_guard",0).edit().putBoolean("resume_pending",false).apply();c.getSharedPreferences("journal",0).edit().putBoolean("enabled",false).apply();c.stopService(new Intent(c,RecorderService.class));c.stopService(new Intent(c,NetworkCaptureService.class));WatcherService.stop(c);}
}
