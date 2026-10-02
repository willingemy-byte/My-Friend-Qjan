package fr.erick.journallocal;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AIV 1.2 native shell.
 *
 * Deliberately contains no WebView, JavaScript bridge or HTML renderer.
 * Data shown here is read directly from the local Android/SQLite sources.
 */
public final class MainActivity extends Activity {
    private static final int VPN_REQUEST=1201;
    private static final int NOTIFICATION_REQUEST=1202;
    private static final int BG=0xff04102f;
    private static final int PANEL=0xff081827;
    private static final int PANEL_2=0xff0c2233;
    private static final int BORDER=0xff28506a;
    private static final int TEXT=0xffeef7ff;
    private static final int MUTED=0xff9fb5c8;
    private static final int BLUE=0xff58b8ff;
    private static final int GREEN=0xff65df70;
    private static final int ORANGE=0xfff2a44d;
    private static final int RED=0xffff6673;

    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicInteger generation=new AtomicInteger();
    private LinearLayout page;
    private LinearLayout nav;
    private LinearLayout tierFooter;
    private static final int TIER_FREE=1;
    private static final int TIER_PAID=2;
    private static final int TIER_IT=3;
    private int previewTier=TIER_FREE;
    private String currentPage="presentation";

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=21){
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        }
        try{Continuous.initialize(this);}catch(Throwable ignored){}
        try{DefenseMonitor.start(this);}catch(Throwable ignored){}
        try{ShizukuCleanup.attach(this);}catch(Throwable ignored){}
        buildShell();
        prepareLocalData();
        showPage("presentation");
    }

    private void buildShell(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            }else{
                view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(18),dp(14),dp(18),dp(12));
        head.setBackgroundColor(0xff051329);
        TextView title=text("ALL IN VISIBLE",24,TEXT,true);
        title.setLetterSpacing(.09f);
        TextView sub=text("AIV 1.2.0 · interface Android native",13,MUTED,false);
        TextView nativeTag=text("●  NATIF · WebView absent",13,GREEN,true);
        nativeTag.setPadding(0,dp(7),0,0);
        head.addView(title);
        head.addView(sub);
        head.addView(nativeTag);
        root.addView(head,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView scroller=new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        nav=new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(10),dp(8),dp(10),dp(8));
        nav.setBackgroundColor(0xff050b12);
        addTab("Présentation","presentation");
        addTab("Applications","applications");
        addTab("Accès","access");
        addTab("Flux","flows");
        addTab("Shizuku","shizuku");
        scroller.addView(nav);
        root.addView(scroller,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12),dp(12),dp(12),dp(40));
        scroll.addView(page,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));

        tierFooter=new LinearLayout(this);
        tierFooter.setOrientation(LinearLayout.HORIZONTAL);
        tierFooter.setGravity(Gravity.CENTER);
        tierFooter.setPadding(dp(8),dp(7),dp(8),dp(7));
        tierFooter.setBackgroundColor(0xff050b12);
        addTierButton("User Free",TIER_FREE);
        addTierButton("User Paid",TIER_PAID);
        addTierButton("TI",TIER_IT);
        root.addView(tierFooter,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(58)));
        refreshTierFooter();

        setContentView(root);
        root.requestApplyInsets();
    }

    private void addTierButton(String label,int tier){
        Button b=button(label);
        b.setTag(tier);
        b.setOnClickListener(v->selectPreviewTier((Integer)v.getTag()));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(44),1f);
        lp.setMargins(dp(4),0,dp(4),0);
        tierFooter.addView(b,lp);
    }

    private void selectPreviewTier(int tier){
        previewTier=tier;
        refreshTierFooter();
        if(tier==TIER_FREE)showPage("presentation");
        else if(tier==TIER_PAID)showPage("shizuku");
        else renderTiPreview();
    }

    private void refreshTierFooter(){
        if(tierFooter==null)return;
        for(int i=0;i<tierFooter.getChildCount();i++){
            View v=tierFooter.getChildAt(i);
            if(!(v instanceof Button))continue;
            Button b=(Button)v;
            Object tag=b.getTag();
            int tier=tag instanceof Integer?(Integer)tag:TIER_FREE;
            boolean selected=tier==previewTier;
            int accent=tier==TIER_FREE?BLUE:ORANGE;
            b.setTextColor(selected?0xff07111a:0xffd7e2ea);
            b.setBackground(panelDrawable(selected?accent:0xff111c25,accent,999));
        }
    }

    private void addTab(String label,String id){
        Button b=button(label);
        b.setTag(id);
        b.setOnClickListener(v->showPage((String)v.getTag()));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(44));
        lp.setMargins(dp(4),0,dp(4),0);
        nav.addView(b,lp);
    }

    private void showPage(String id){
        currentPage=id;
        generation.incrementAndGet();
        for(int i=0;i<nav.getChildCount();i++){
            View v=nav.getChildAt(i);
            if(v instanceof Button)styleTab((Button)v,id.equals(v.getTag()));
        }
        if("applications".equals(id))renderApplications("");
        else if("access".equals(id))renderSpecialAccess();
        else if("flows".equals(id))renderFlows("");
        else if("shizuku".equals(id)){ if(previewTier>=TIER_PAID)renderShizuku(); else renderUpgradeGate(); }
        else renderPresentation();
    }

    private void renderPresentation(){
        page.removeAllViews();
        page.addView(sectionTitle("Présentation"));
        page.addView(note("Cette vue est rendue par des composants Android natifs. Aucun HTML, JavaScript ou WebView n'intervient dans ce que tu vois ici."));
        page.addView(card("User Free · inclus",
            "Journal local · Inventaire des applications · Permissions · Flux VPN · Traqueurs · Anomalies · Intégrité d'affichage (opt-in) · Exports locaux"));
        page.addView(card("User Paid · contrôle",
            "Tout User Free + Shizuku · retrait contrôlé des permissions · restauration · surveillance persistante · identité cryptographique VPN signée par Android Keystore"));
        page.addView(card("TI · parc",
            "Tout User Paid + vue parc · enrôlement · politiques · rapports. Présentation seulement dans cette version."));
        page.addView(card("Identité de l'application","Nom : All In Visible\nPackage Android : "+getPackageName()+"\nInterface : NATIVE\nWebView : AUCUN"));
        try{
            JSONObject audit=PermissionAudit.get(this).summary();
            long total=audit.optLong("total"),system=audit.optLong("system");
            page.addView(card("Inventaire Android",
                "Scan : "+audit.optLong("scan_id")+"\nApplications : "+total+"\nSystème : "+system+"\nUtilisateur : "+Math.max(0,total-system)+"\nÉtat : "+(audit.optBoolean("busy")?"calcul en cours":"prêt")));
        }catch(Exception e){page.addView(card("Inventaire Android","Indisponible : "+e.getClass().getSimpleName()));}

        try{
            long events=EventStore.get(this).latestId();
            String vpn=NetworkCaptureService.running?NetworkCaptureService.stateText:(NetworkCaptureService.starting?"Démarrage":NetworkCaptureService.stateText);
            page.addView(card("Journal et réseau",
                "Événements : "+events+"\nCollecteur : "+yesNo(RecorderService.running)+"\nAnalyse : "+yesNo(WatcherService.analysisActive)+"\nVPN AIV : "+vpn+
                (NetworkCaptureService.lastError.isEmpty()?"":"\nErreur VPN : "+NetworkCaptureService.lastError)));
        }catch(Exception e){page.addView(card("Journal et réseau","Indisponible : "+e.getClass().getSimpleName()));}

        try{
            JSONObject s=ShizukuCleanup.state(this);
            page.addView(card("Shizuku",
                "Binder : "+yesNo(s.optBoolean("binder"))+"\nAutorisé : "+yesNo(s.optBoolean("authorized"))+"\nCandidats automatiques actuels : "+s.optInt("candidates",-1)+"\nÉtat : "+s.optString("status")));
        }catch(Exception e){page.addView(card("Shizuku","Indisponible : "+e.getClass().getSimpleName()));}

        try{
            JSONObject app=AppIdentity.forPackage(this,getPackageName());
            String id=app.optString("app_identity_id");
            if(id.length()>24)id=id.substring(0,24)+"…";
            PinVault vault=new PinVault();
            String key=vault.keyId();if(key.length()>24)key=key.substring(0,24)+"…";
            page.addView(card("Identité cryptographique AIV",
                "App ID : "+id+"\nClé appareil : "+key+"\nNiveau clé : "+vault.securityLevel+"\nSource app : certificats PackageManager"));
        }catch(Exception e){page.addView(card("Identité cryptographique AIV","Initialisation : "+e.getClass().getSimpleName()));}

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(action("Actualiser l'inventaire",v->{PermissionAudit.get(this).scan();toast("Inventaire lancé");main.postDelayed(this::renderPresentation,900);}));
        actions.addView(action("Démarrer la collecte AIV",v->startCollection()));
        actions.addView(action("Arrêter la collecte AIV",v->{Continuous.stop(this);toast("Collecte arrêtée");main.postDelayed(this::renderPresentation,400);}));
        actions.addView(action("Ouvrir les réglages VPN",v->openSetting(Settings.ACTION_VPN_SETTINGS)));
        actions.addView(action("Ouvrir les options développeur",v->openSetting(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
        page.addView(actions);
    }

    private void renderSpecialAccess(){
        page.removeAllViews();
        page.addView(sectionTitle("Accès spéciaux"));
        page.addView(note("AIV demande seulement les accès qui servent à observer ou protéger l'appareil. Cette page distingue visibilité, contrôle Shizuku et accès spéciaux Android; elle ne transforme pas AIV en application système."));
        try{
            JSONObject s=SpecialAccess.status(this);
            page.addView(card("Visibilité des applications",
                "QUERY_ALL_PACKAGES : "+yesNo(s.optBoolean("query_all_packages"))+
                "\nApplications visibles : "+s.optInt("visible_packages",-1)+
                "\nAIV visible dans son propre inventaire : "+yesNo(s.optBoolean("self_visible"))+
                "\nBut : lire package, version, UID, certificat et permissions déclarées/accordées."));
            page.addView(card("Accès d'utilisation",
                "Usage Access : "+yesNo(s.optBoolean("usage_access"))+
                "\nBut : contexte d'utilisation et statistiques Android. L'utilisateur garde le contrôle du commutateur système."));
            page.addView(card("VPN et Shizuku",
                "Autorisation VPN AIV : "+yesNo(s.optBoolean("vpn_prepared"))+
                "\nShizuku connecté : "+yesNo(s.optBoolean("shizuku_binder"))+
                "\nShizuku autorisé : "+yesNo(s.optBoolean("shizuku_authorized"))+
                "\nCes deux accès sont distincts : le VPN observe les flux; Shizuku exécute les actions de contrôle validées."));
            page.addView(card("Fichiers",
                "Accès à tous les fichiers : "+(s.optBoolean("all_files_declared")?(s.optBoolean("all_files_granted")?"ACTIF":"DÉCLARÉ, NON ACTIF"):"NON DEMANDÉ")+
                "\nAIV 1.2.0 ne le demande pas par défaut : cet accès couvre surtout le stockage partagé et ne donne pas accès aux données privées /data/data des autres applications."));
            page.addView(card("Autres accès",
                "Superposition : "+yesNo(s.optBoolean("overlay"))+
                "\nModifier les réglages système : "+yesNo(s.optBoolean("write_settings"))+
                "\nInstaller des applis inconnues : "+yesNo(s.optBoolean("can_request_package_installs"))+
                "\nExemption optimisation batterie : "+yesNo(s.optBoolean("ignore_battery_optimizations"))+
                "\nAIV ne demande pas ces accès juste parce qu’ils existent; ils restent optionnels tant qu’une fonction précise ne les exige pas."));
        }catch(Exception e){page.addView(card("Accès spéciaux","Lecture impossible : "+e.getClass().getSimpleName()));}
        page.addView(action("Ouvrir Accès d'utilisation",v->openSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        page.addView(action("Ouvrir Accès spéciaux Android",v->openSetting("android.settings.MANAGE_SPECIAL_APP_ACCESSES")));
        page.addView(action("Ouvrir les réglages VPN",v->openSetting(Settings.ACTION_VPN_SETTINGS)));
        page.addView(action("Ouvrir les options développeur",v->openSetting(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
    }

    private void renderApplications(String query){
        int ticket=generation.incrementAndGet();
        page.removeAllViews();
        page.addView(sectionTitle("Applications"));
        page.addView(note("Recherche native dans le dernier inventaire PackageManager. Les résultats viennent de la base locale AIV, pas du texte d'une page Web."));
        EditText search=searchBox("Nom ou package",query);
        Button go=action("Rechercher",v->renderApplications(search.getText().toString()));
        page.addView(search);
        page.addView(go);
        TextView loading=text("Lecture de l'inventaire…",14,MUTED,false);page.addView(loading);
        new Thread(()->{
            try{
                JSONObject inv=PermissionAudit.get(this).coherenceInventory();
                JSONArray apps=inv.optJSONArray("apps");
                String needle=query==null?"":query.trim().toLowerCase(Locale.ROOT);
                JSONArray matches=new JSONArray();
                if(apps!=null)for(int i=0;i<apps.length();i++){
                    JSONObject a=apps.getJSONObject(i);
                    String hay=(a.optString("label")+" "+a.optString("package")).toLowerCase(Locale.ROOT);
                    if(needle.isEmpty()||hay.contains(needle))matches.put(a);
                }
                main.post(()->{
                    if(ticket!=generation.get()||!"applications".equals(currentPage))return;
                    page.removeView(loading);
                    page.addView(text(matches.length()+" résultat(s)",14,MUTED,true));
                    int shown=Math.min(matches.length(),120);
                    for(int i=0;i<shown;i++){
                        JSONObject a=matches.optJSONObject(i);if(a==null)continue;
                        JSONArray perms=a.optJSONArray("permissions");
                        String body=a.optString("package")+"\nUID "+a.optInt("uid",-1)+" · "+(a.optBoolean("system_app")?"Système":"Utilisateur")+" · "+(perms==null?0:perms.length())+" permission(s) déclarée(s)";
                        LinearLayout c=card(a.optString("label",a.optString("package")),body);
                        String pkg=a.optString("package");
                        c.setOnClickListener(v->openAppSettings(pkg));
                        page.addView(c);
                    }
                    if(matches.length()>shown)page.addView(note((matches.length()-shown)+" autre(s) résultat(s) non affiché(s) dans cette vue bornée."));
                });
            }catch(Exception e){
                main.post(()->{if(ticket==generation.get()){page.removeView(loading);page.addView(card("Erreur inventaire",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())));}});
            }
        },"aiv-native-apps").start();
    }

    private void renderFlows(String query){
        int ticket=generation.incrementAndGet();
        page.removeAllViews();
        page.addView(sectionTitle("Flux"));
        page.addView(note("Projection native des événements du VPN AIV. Aucun contenu TLS n'est déchiffré et aucun marqueur n'est injecté dans Internet."));
        EditText search=searchBox("Application, UID, IP, domaine ou ID",query);
        page.addView(search);
        page.addView(action("Actualiser",v->renderFlows(search.getText().toString())));
        TextView loading=text("Lecture des flux…",14,MUTED,false);page.addView(loading);
        new Thread(()->{
            try{
                JSONObject data=EventStore.get(this).flowPage(query,0,100);
                JSONArray flows=data.optJSONArray("flows");
                main.post(()->{
                    if(ticket!=generation.get()||!"flows".equals(currentPage))return;
                    page.removeView(loading);
                    page.addView(text((flows==null?0:flows.length())+" flux · "+data.optInt("scanned_events")+" événements examinés",14,MUTED,true));
                    if(flows==null)return;
                    for(int i=0;i<flows.length();i++){
                        JSONObject f=flows.optJSONObject(i);if(f==null)continue;
                        String actor=f.optString("actor","Application non identifiée");
                        int uid=f.optInt("uid",-1);
                        String dest=f.optString("tls_sni");
                        if(dest.isEmpty())dest=f.optString("destination");
                        String body=(uid>=0?"UID "+uid:"UID non attribué")+" · "+f.optString("protocol")+"\n"+dest+
                            "\n↑ "+f.optLong("tx_bytes")+" o · ↓ "+f.optLong("rx_bytes")+" o · "+f.optString("attribution_status");
                        page.addView(card(actor,body));
                    }
                });
            }catch(Exception e){
                main.post(()->{if(ticket==generation.get()){page.removeView(loading);page.addView(card("Erreur flux",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())));}});
            }
        },"aiv-native-flows").start();
    }

    private void renderShizuku(){
        page.removeAllViews();
        page.addView(sectionTitle("Shizuku"));
        page.addView(note("User Paid · aperçu du contrôle. Cette page montre l'état réel de Shizuku. Les opérations de contrôle devront être liées à un droit Paid vérifié avant commercialisation."));
        page.addView(card("Accès commercial","Aperçu User Paid actif pour la présentation. Le paiement et l'entitlement vérifié ne sont pas encore connectés; aucune fonction payante ne doit dépendre uniquement de ce sélecteur visuel."));
        try{
            JSONObject s=ShizukuCleanup.state(this);
            page.addView(card("État Shizuku",
                "Binder : "+yesNo(s.optBoolean("binder"))+"\nAutorisé : "+yesNo(s.optBoolean("authorized"))+"\nUID serveur : "+s.optInt("server_uid",-1)+"\nCandidats actuels : "+s.optInt("candidates",-1)+"\n"+s.optString("status")));
        }catch(Exception e){page.addView(card("État Shizuku","Indisponible : "+e.getClass().getSimpleName()));}
        page.addView(action("Actualiser",v->renderShizuku()));
        page.addView(action("Ouvrir Shizuku",v->openPackage("moe.shizuku.privileged.api")));
        page.addView(action("Ouvrir les options développeur",v->openSetting(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
        page.addView(action("Ouvrir les réglages VPN",v->openSetting(Settings.ACTION_VPN_SETTINGS)));
    }

    private void renderUpgradeGate(){
        page.removeAllViews();
        page.addView(sectionTitle("Shizuku · User Paid"));
        page.addView(card("User Free",
            "Tu peux observer le journal, les applications, les permissions, les flux, les traqueurs, les anomalies et l'intégrité d'affichage."));
        page.addView(card("User Paid",
            "Ajoute le contrôle Shizuku, le retrait contrôlé des permissions, la restauration, la surveillance persistante et l'identité cryptographique VPN."));
        page.addView(action("Passer à User Paid",v->renderUpgradeCheckout()));
    }

    private void renderUpgradeCheckout(){
        page.removeAllViews();
        page.addView(sectionTitle("Passer à User Paid"));
        page.addView(note("Écran de conversion produit. Le fournisseur de paiement n'est pas encore connecté dans cette build."));
        page.addView(card("User Paid",
            "Contrôle Shizuku · plan de retrait · application par lots · vérification avant/après · restauration · watcher persistant · identité cryptographique VPN Android Keystore"));
        Button pay=action("Continuer vers le paiement",v->toast("Paiement à connecter avant commercialisation"));
        page.addView(pay);
        page.addView(action("Voir l'aperçu User Paid",v->{previewTier=TIER_PAID;refreshTierFooter();showPage("shizuku");}));
    }

    private void renderTiPreview(){
        currentPage="ti";
        generation.incrementAndGet();
        for(int i=0;i<nav.getChildCount();i++){
            View v=nav.getChildAt(i);
            if(v instanceof Button)styleTab((Button)v,false);
        }
        page.removeAllViews();
        page.addView(sectionTitle("TI · parc"));
        page.addView(note("Aperçu investisseur. La gestion de parc n'est pas finalisée dans cette version."));
        page.addView(card("Vue parc",
            "Appareils enrôlés · état de conformité · anomalies · politiques · rapports · bascule entre vue appareil et vue parc"));
        page.addView(card("Statut","Architecture prévue · fonctions de parc non activées dans AIV 1.2.0."));
    }

    private void prepareLocalData(){
        new Thread(()->{
            try{
                PermissionAudit audit=PermissionAudit.get(this);
                JSONObject s=audit.summary();
                if(s.optLong("scan_id",0)<=0)audit.scan();
                AnomalyMonitor.request(this);
                TrackerIndex.get(this).request();
                ApkEvidence.get(this).request();
            }catch(Throwable ignored){}
            main.postDelayed(()->{if("presentation".equals(currentPage))renderPresentation();},800);
        },"aiv-native-init").start();
    }

    private void startCollection(){
        try{
            Continuous.prefs(this).edit().putBoolean("enabled",true).putBoolean("analysis_enabled",true).putBoolean("vpn_enabled",true).apply();
            Continuous.start(this);
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_REQUEST);
            Intent consent=VpnService.prepare(this);
            if(consent!=null)startActivityForResult(consent,VPN_REQUEST);
            else startForegroundService(new Intent(this,NetworkCaptureService.class));
            toast("Collecte demandée");
            main.postDelayed(this::renderPresentation,700);
        }catch(Exception e){toast("Démarrage impossible : "+e.getClass().getSimpleName());}
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==VPN_REQUEST&&resultCode==RESULT_OK){
            try{NetworkCaptureService.lastError="";startForegroundService(new Intent(this,NetworkCaptureService.class));}
            catch(Exception e){toast("VPN : "+e.getClass().getSimpleName());}
        }
    }

    @Override public void onResume(){super.onResume();if(page!=null&&"presentation".equals(currentPage))main.postDelayed(this::renderPresentation,250);}
    @Override public void onDestroy(){generation.incrementAndGet();try{ShizukuCleanup.detach();}catch(Throwable ignored){}super.onDestroy();}

    private void openSetting(String action){try{startActivity(new Intent(action));}catch(Exception e){toast("Réglage Android indisponible");}}
    private void openPackage(String pkg){
        try{Intent i=getPackageManager().getLaunchIntentForPackage(pkg);if(i==null)throw new IllegalStateException();startActivity(i);}
        catch(Exception e){toast("Application non installée ou non visible");}
    }
    private void openAppSettings(String pkg){
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:"+pkg)));}
        catch(Exception e){toast("Réglages indisponibles");}
    }

    private TextView sectionTitle(String value){TextView v=text(value,28,TEXT,true);v.setPadding(dp(4),dp(6),dp(4),dp(10));return v;}
    private TextView note(String value){
        TextView v=text(value,14,MUTED,false);v.setPadding(dp(14),dp(12),dp(14),dp(12));v.setBackground(panelDrawable(PANEL,BORDER,16));LinearLayout.LayoutParams lp=blockParams();v.setLayoutParams(lp);return v;
    }
    private LinearLayout card(String title,String body){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),dp(14),dp(16),dp(14));box.setBackground(panelDrawable(PANEL_2,BORDER,18));box.setLayoutParams(blockParams());
        box.addView(text(title,18,TEXT,true));TextView b=text(body,14,MUTED,false);b.setPadding(0,dp(7),0,0);box.addView(b);return box;
    }
    private EditText searchBox(String hint,String value){
        EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(0xff6f879c);e.setTextColor(TEXT);e.setText(value==null?"":value);e.setSingleLine(true);e.setTextSize(16);e.setPadding(dp(14),0,dp(14),0);e.setBackground(panelDrawable(PANEL,BORDER,14));e.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54)));return e;
    }
    private Button button(String label){
        Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setPadding(dp(14),0,dp(14),0);styleTab(b,false);return b;
    }
    private Button action(String label,View.OnClickListener listener){
        Button b=button(label);b.setTextColor(TEXT);b.setBackground(panelDrawable(0xff0a2437,0xff386782,14));b.setOnClickListener(listener);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(50));lp.setMargins(0,dp(7),0,0);b.setLayoutParams(lp);return b;
    }
    private void styleTab(Button b,boolean selected){b.setTextColor(selected?0xff06101a:0xffc3d2df);b.setBackground(panelDrawable(selected?BLUE:0xff091925,selected?0xff72ccff:0xff2b5674,999));}
    private TextView text(String value,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(sp);v.setTextColor(color);v.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private GradientDrawable panelDrawable(int fill,int stroke,int radiusDp){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radiusDp));g.setStroke(dp(1),stroke);return g;}
    private LinearLayout.LayoutParams blockParams(){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);lp.setMargins(0,0,0,dp(10));return lp;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private String yesNo(boolean value){return value?"OUI":"NON";}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
}
