package fr.erick.journallocal;
import android.content.*;
import android.net.VpnService;

/** Persist user intent separately from process liveness. Never clears a database. */
public final class Continuous {
    static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("continuous",0);}
    public static void initialize(Context c){if(!prefs(c).contains("enabled"))prefs(c).edit().putBoolean("enabled",true).putBoolean("vpn_enabled",true).putBoolean("analysis_enabled",true).apply();}
    public static boolean enabled(Context c){return prefs(c).getBoolean("enabled",false);}
    public static void start(Context c){
        if(!enabled(c))return;
        try{if(!RecorderService.running)c.startForegroundService(new Intent(c,RecorderService.class));}catch(Exception e){EventStore.lastError="Reprise du collecteur : "+e.getClass().getSimpleName();}
        try{if(prefs(c).getBoolean("analysis_enabled",true)&&!WatcherService.running)WatcherService.start(c);}catch(Exception e){AivStore.error="Reprise de l’analyse : "+e.getClass().getSimpleName();}
        try{if(prefs(c).getBoolean("vpn_enabled",true)&&!NetworkCaptureService.running&&!NetworkCaptureService.starting){if(VpnService.prepare(c)==null)c.startForegroundService(new Intent(c,NetworkCaptureService.class));else NetworkCaptureService.lastError="Autorisation VPN requise dans Paramètres → Collecte continue.";}}catch(Exception e){NetworkCaptureService.lastError="Reprise VPN : "+e.getClass().getSimpleName();}
    }
    public static void stop(Context c){prefs(c).edit().putBoolean("enabled",false).commit();c.getSharedPreferences("journal",0).edit().putBoolean("enabled",false).apply();c.stopService(new Intent(c,RecorderService.class));c.stopService(new Intent(c,NetworkCaptureService.class));WatcherService.stop(c);}
}