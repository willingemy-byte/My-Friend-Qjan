package fr.erick.journallocal;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Locale;

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

    @Override protected void onServiceConnected(){
        connected=true;
        status="Observation sémantique active";
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

    @Override public boolean onUnbind(android.content.Intent intent){
        connected=false;
        status="Service désactivé";
        return super.onUnbind(intent);
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
            "comparison_status","WAITING_SECOND_SIGNAL",
            "scope","Arbre d'accessibilité Android seulement; aucun DOM brut de navigateur n'est revendiqué et aucune anomalie d'affichage n'est déclarée sans seconde preuve."
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
