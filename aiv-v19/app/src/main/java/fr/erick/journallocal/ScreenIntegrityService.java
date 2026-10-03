package fr.erick.journallocal;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only semantic observation channel for AIV display integrity.
 * No clicks, gestures, screenshots, overlays or raw page text are persisted.
 */
public final class ScreenIntegrityService extends AccessibilityService {
    private static volatile boolean connected;
    private static volatile String observedPackage="";
    private static volatile String observedClass="";
    private static volatile long observedMs;
    private static volatile int nodeCount;
    private static volatile int textNodeCount;
    private static volatile String semanticHash="";
    private static volatile String status="Service non activé";
    private static volatile boolean overlayVisible;
    private static volatile boolean coreActive;
    private static volatile boolean anomalyActive;
    private static volatile long unreadAnomalies;
    private static volatile boolean collectorActive;
    private static volatile boolean correlationActive;
    private static volatile boolean vpnExpected;
    private static volatile boolean vpnActive;
    private static volatile boolean shizukuExpected;
    private static volatile boolean shizukuActive;
    private WindowManager windowManager;
    private TextView badge;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService statusWorker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean statusRefreshBusy=new AtomicBoolean();
    private final Runnable statusPulse=new Runnable(){@Override public void run(){
        refreshBadgeStateAsync();
        main.postDelayed(this,3000);
    }};

    @Override protected void onServiceConnected(){
        connected=true;
        status="Observation sémantique active";
        showBadge();
        main.removeCallbacks(statusPulse);
        main.post(statusPulse);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null)return;
        CharSequence pkg=event.getPackageName();
        if(pkg!=null&&getPackageName().contentEquals(pkg))return;
        AccessibilityNodeInfo root=null;
        try{
            root=getRootInActiveWindow();
            if(root==null){status="Fenêtre active sans arbre accessible";return;}
            Snapshot s=snapshot(root);
            observedPackage=pkg==null?"":pkg.toString();
            CharSequence cls=event.getClassName();
            observedClass=cls==null?"":cls.toString();
            observedMs=System.currentTimeMillis();
            nodeCount=s.nodes;
            textNodeCount=s.textNodes;
            semanticHash=s.hash;
            status="Couche sémantique observée";
        }catch(Throwable t){
            status="Observation interrompue : "+t.getClass().getSimpleName();
        }finally{
            if(root!=null)root.recycle();
        }
    }

    @Override public void onInterrupt(){status="Service interrompu";}

    private void showBadge(){
        try{
            if(badge!=null)return;
            windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
            badge=new TextView(this);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(13);
            badge.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            badge.setGravity(Gravity.CENTER);
            int pad=(int)(8*getResources().getDisplayMetrics().density);
            badge.setPadding(pad,pad/2,pad,pad/2);
            applyBadgeVisual();
            badge.setOnClickListener(v->{
                try{
                    Intent i=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(i);
                }catch(Throwable ignored){}
            });
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            lp.gravity=Gravity.TOP|Gravity.END;
            lp.x=12;lp.y=96;
            windowManager.addView(badge,lp);
            overlayVisible=true;
        }catch(Throwable t){
            overlayVisible=false;
            status="Observation active; badge indisponible : "+t.getClass().getSimpleName();
        }
    }

    private void refreshBadgeStateAsync(){
        if(!statusRefreshBusy.compareAndSet(false,true))return;
        statusWorker.execute(()->{
            try{
                collectorActive=RecorderService.running;
                correlationActive=WatcherService.running;
                vpnExpected=Continuous.prefs(this).getBoolean("vpn_enabled",false);
                vpnActive=NetworkCaptureService.running;

                shizukuExpected=ProductAccess.demoTier(this)>=ProductAccess.PAID;
                shizukuActive=false;
                try{
                    JSONObject shizuku=ShizukuCleanup.state(this);
                    shizukuActive=shizuku.optBoolean("binder")&&shizuku.optBoolean("authorized");
                }catch(Throwable ignored){}

                unreadAnomalies=0;
                try{
                    JSONObject summary=AnomalyMonitor.get(this).summary();
                    unreadAnomalies=summary.optLong("unread",0);
                }catch(Throwable ignored){}
                anomalyActive=unreadAnomalies>0;

                coreActive=connected
                    && collectorActive
                    && correlationActive
                    && (!vpnExpected||vpnActive)
                    && (!shizukuExpected||shizukuActive);
            }finally{
                statusRefreshBusy.set(false);
                main.post(this::applyBadgeVisual);
            }
        });
    }

    private void applyBadgeVisual(){
        if(badge==null)return;
        SpannableString label=new SpannableString("● AIV");
        label.setSpan(new ForegroundColorSpan(coreActive?0xff65df70:0xff8b98a5),0,1,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        badge.setText(label);

        GradientDrawable bg=new GradientDrawable();
        bg.setColor(0xff071827);
        int ring=anomalyActive?0xffff9a3c:0xff58b8ff;
        bg.setStroke(Math.max(1,(int)(2*getResources().getDisplayMetrics().density)),ring);
        bg.setCornerRadius(999f);
        badge.setBackground(bg);

        StringBuilder desc=new StringBuilder("All In Visible. ");
        desc.append(coreActive?"Surveillance attendue active. ":"Un ou plusieurs modules attendus sont inactifs. ");
        desc.append(anomalyActive?unreadAnomalies+" anomalie(s) à examiner.":"Aucune anomalie non lue.");
        badge.setContentDescription(desc.toString());
    }

    private void removeBadge(){
        main.removeCallbacks(statusPulse);
        try{if(windowManager!=null&&badge!=null)windowManager.removeView(badge);}catch(Throwable ignored){}
        badge=null;overlayVisible=false;
    }

    @Override public boolean onUnbind(android.content.Intent intent){
        connected=false;
        removeBadge();
        status="Service désactivé";
        return super.onUnbind(intent);
    }

    @Override public void onDestroy(){
        removeBadge();
        connected=false;
        statusWorker.shutdownNow();
        super.onDestroy();
    }

    public static JSONObject state(){
        return EventStore.object(
            "schema","aiv-display-integrity/1",
            "connected",connected,
            "status",status,
            "observed_package",observedPackage,
            "observed_class",observedClass,
            "observed_ms",observedMs,
            "node_count",nodeCount,
            "text_node_count",textNodeCount,
            "semantic_hash",semanticHash,
            "overlay_visible",overlayVisible,
            "core_active",coreActive,
            "collector_active",collectorActive,
            "correlation_active",correlationActive,
            "vpn_expected",vpnExpected,
            "vpn_active",vpnActive,
            "shizuku_expected",shizukuExpected,
            "shizuku_active",shizukuActive,
            "anomaly_active",anomalyActive,
            "unread_anomalies",unreadAnomalies,
            "comparison_status","SEMANTIC_ONLY",
            "comparison_mode","ONE_PASS",
            "free_tier",true,
            "visual_signal_available",false,
            "scope","Une seule observation suffit; aucune seconde visite n'est requise. Le service actuel fournit l'arbre d'accessibilité Android. Tant qu'un canal visuel indépendant n'est pas comparé au même moment, AIV ne doit pas affirmer qu'un affichage diffère de sa sémantique."
        );
    }

    private Snapshot snapshot(AccessibilityNodeInfo root)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        int nodes=0,textNodes=0;
        while(!queue.isEmpty()&&nodes<4000){
            AccessibilityNodeInfo n=queue.removeFirst();
            try{
                nodes++;
                Rect r=new Rect();
                n.getBoundsInScreen(r);
                String cls=clean(n.getClassName());
                String text=clean(n.getText());
                String desc=clean(n.getContentDescription());
                String viewId=clean(n.getViewIdResourceName());
                if(!text.isEmpty()||!desc.isEmpty())textNodes++;
                update(digest,cls);
                update(digest,text);
                update(digest,desc);
                update(digest,viewId);
                update(digest,n.isVisibleToUser()?"1":"0");
                update(digest,r.left+","+r.top+","+r.right+","+r.bottom);
                int children=Math.min(n.getChildCount(),100);
                for(int i=0;i<children;i++){
                    AccessibilityNodeInfo child=n.getChild(i);
                    if(child!=null)queue.addLast(child);
                }
            }finally{n.recycle();}
        }
        return new Snapshot(nodes,textNodes,hex(digest.digest()));
    }

    private static String clean(CharSequence value){
        if(value==null)return "";
        String s=value.toString().replaceAll("\\s+"," ").trim();
        return s.length()>512?s.substring(0,512):s;
    }
    private static void update(MessageDigest d,String value){
        d.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        d.update((byte)0);
    }
    private static String hex(byte[] b){
        StringBuilder s=new StringBuilder(b.length*2);
        for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));
        return s.toString();
    }
    private static final class Snapshot{
        final int nodes,textNodes;final String hash;
        Snapshot(int n,int t,String h){nodes=n;textNodes=t;hash=h;}
    }
}
