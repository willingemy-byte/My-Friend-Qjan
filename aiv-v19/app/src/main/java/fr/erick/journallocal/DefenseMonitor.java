package fr.erick.journallocal;

import android.content.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/** Dynamic receiver: alive with the visible activity or the existing foreground collector. */
public final class DefenseMonitor {
    private static int owners;
    private static BroadcastReceiver receiver;
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static Runnable scan;
    private DefenseMonitor() {}
    public static synchronized boolean running(){return receiver!=null;}
    public static synchronized void start(Context c){
        owners++;if(receiver!=null)return;Context app=c.getApplicationContext();
        scan=()->PermissionAudit.get(app).scan();
        receiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent i){
            String pkg=i.getData()==null?null:i.getData().getSchemeSpecificPart();if(pkg==null)return;
            try{DefenseStore.get(app).packageEvent(pkg,i.getAction(),i.getBooleanExtra(Intent.EXTRA_REPLACING,false),i.getBooleanExtra(Intent.EXTRA_ARCHIVAL,false));}catch(Exception e){DefenseStore.get(app).failed(e);}
            // Coalesce ADDED/REPLACED bursts. PermissionAudit also re-runs if a scan was already active.
            main.removeCallbacks(scan);main.postDelayed(scan,1000);
        }};
        IntentFilter f=new IntentFilter();f.addDataScheme("package");f.addAction(Intent.ACTION_PACKAGE_ADDED);f.addAction(Intent.ACTION_PACKAGE_REPLACED);f.addAction(Intent.ACTION_PACKAGE_REMOVED);f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        try{if(Build.VERSION.SDK_INT>=33)app.registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else app.registerReceiver(receiver,f);}
        catch(Exception e){receiver=null;DefenseStore.get(app).failed(e);}
    }
    public static synchronized void stop(Context c){
        owners=Math.max(0,owners-1);if(owners!=0)return;
        if(receiver!=null)try{c.getApplicationContext().unregisterReceiver(receiver);}catch(IllegalArgumentException ignored){}
        receiver=null;if(scan!=null)main.removeCallbacks(scan);
    }
}
