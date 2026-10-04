package fr.erick.journallocal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AIV 2.0 native shell.
 *
 * Deliberately contains no WebView, JavaScript bridge or HTML renderer.
 * Data shown here is read directly from the local Android/SQLite sources.
 */
public final class MainActivity extends Activity {
    private static final int VPN_REQUEST=1201;
    private static final int NOTIFICATION_REQUEST=1202;
    private static final int EXPORT_REQUEST=1203;
    private static final int ANALYSIS_EXPORT_REQUEST=1204;
    private static final int SHIZUKU_PERMISSION_REQUEST=1205;
    private static final int PERMISSION_EXPORT_REQUEST=1206;
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
    private static final int YELLOW=0xfff3d65a;
    private static final int VIOLET=0xffd065ff;

    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicInteger generation=new AtomicInteger();
    private LinearLayout page;
    private LinearLayout nav;
    private LinearLayout tierFooter;
    private LinearLayout statusRow;
    private static final int TIER_FREE=1;
    private static final int TIER_PAID=2;
    private static final int TIER_IT=3;
    private int previewTier=TIER_FREE;
    private String currentPage="presentation";
    private String permissionPackage="",permissionQuery="";
    private int permissionOffset;
    private final java.util.Set<String> permissionSelection=new java.util.LinkedHashSet<>();
    private TextView permissionStatusView;
    private boolean permissionPrepareAfterReview,permissionShowReportAfterJob;
    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener permissionAuthListener=(code,result)->{
        if(code!=SHIZUKU_PERMISSION_REQUEST)return;
        main.post(()->{
            if(result!=PackageManager.PERMISSION_GRANTED){toast("AIV n’est pas autorisée dans Shizuku");return;}
            if("shizuku".equals(currentPage)){
                if(permissionPackage.isEmpty())renderPermissionApps(permissionQuery,permissionOffset);
                else openPermissions(permissionPackage,"",0);
            }
        });
    };
    private final Runnable headerStatusPulse=new Runnable(){@Override public void run(){
        if(statusRow!=null)refreshHeaderStatus();
        main.postDelayed(this,2000);
    }};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=21){
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        }
        try{
            Continuous.initialize(this);
            Continuous.prefs(this).edit().putBoolean("vpn_enabled",false).apply();
            stopService(new Intent(this,NetworkCaptureService.class));
        }catch(Throwable ignored){}
        try{DefenseMonitor.start(this);}catch(Throwable ignored){}
        try{ShizukuCleanup.attach(this);}catch(Throwable ignored){}
        try{rikka.shizuku.Shizuku.addRequestPermissionResultListener(permissionAuthListener);}catch(Throwable ignored){}
        previewTier=ProductAccess.demoTier(this);
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
        head.setPadding(dp(18),dp(14),dp(18),dp(10));
        head.setBackgroundColor(0xff051329);

        LinearLayout brand=new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this);
        int logoId=getResources().getIdentifier("aiv_logo","drawable",getPackageName());
        if(logoId!=0)logo.setImageResource(logoId);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams logoLp=new LinearLayout.LayoutParams(dp(52),dp(52));
        logoLp.setMargins(0,0,dp(12),0);
        brand.addView(logo,logoLp);
        LinearLayout brandText=new LinearLayout(this);
        brandText.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("ALL IN VISIBLE",24,TEXT,true);
        title.setLetterSpacing(.09f);
        TextView sub=text("AIV 2.0.6 · interface Android native",13,MUTED,false);
        TextView nativeTag=text("●  NATIF · WebView absent",13,GREEN,true);
        nativeTag.setPadding(0,dp(4),0,0);
        brandText.addView(title);brandText.addView(sub);brandText.addView(nativeTag);
        brand.addView(brandText,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        head.addView(brand);

        HorizontalScrollView statusScroll=new HorizontalScrollView(this);
        statusScroll.setHorizontalScrollBarEnabled(false);
        statusRow=new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setPadding(0,dp(9),0,0);
        refreshHeaderStatus();
        statusScroll.addView(statusRow);
        head.addView(statusScroll);
        root.addView(head,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView scroller=new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        nav=new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(10),dp(8),dp(10),dp(8));
        nav.setBackgroundColor(0xff050b12);
        addTab("Présentation","presentation");
        addTab("Journal","journal");
        addTab("Flux","flows");
        addTab("Traqueurs","trackers");
        addTab("Anomalies","anomalies");
        addTab("Applications","applications");
        addTab("Accès","access");
        addTab("Intégrité","integrity");
        addTab("Shizuku","shizuku");
        addTab("Supabase","supabase");
        scroller.addView(nav);
        root.addView(scroller,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12),dp(12),dp(12),dp(40));
        scroll.addView(page,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));

        tierFooter=null;

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
        previewTier=ProductAccess.ownerBuild()?TIER_IT:tier;
        ProductAccess.setDemoTier(this,previewTier);
        refreshTierFooter();
        if(previewTier==TIER_FREE)showPage("presentation");
        else if(previewTier==TIER_PAID)showPage("shizuku");
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
        if("journal".equals(id))renderJournal("");
        else if("flows".equals(id))renderFlows("");
        else if("trackers".equals(id))renderTrackers("");
        else if("anomalies".equals(id))renderAnomalies("");
        else if("applications".equals(id))renderApplications("");
        else if("access".equals(id))renderSpecialAccess();
        else if("integrity".equals(id))renderIntegrity();
        else if("shizuku".equals(id)){ if(previewTier>=TIER_PAID)renderShizuku(); else renderUpgradeGate(); }
        else if("supabase".equals(id))renderSupabase();
        else renderPresentation();
    }

    private void renderPresentation(){
        page.removeAllViews();
        page.addView(sectionTitle("Présentation"));
        page.addView(note("Interface Android native. Build propriétaire 2.0.6 : niveau TI local déverrouillé, VPN AIV désactivé par défaut pendant le diagnostic, journal et export locaux disponibles sans VPN."));

        page.addView(sectionTitle("Mode d’utilisation"));
        tierFooter=new LinearLayout(this);
        tierFooter.setOrientation(LinearLayout.HORIZONTAL);
        tierFooter.setGravity(Gravity.CENTER);
        tierFooter.setPadding(dp(4),dp(4),dp(4),dp(10));
        addTierButton("Free",TIER_FREE);
        addTierButton("Paid",TIER_PAID);
        addTierButton("TI",TIER_IT);
        page.addView(tierFooter,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(62)));
        refreshTierFooter();
        page.addView(sectionTitle("Niveaux d’autorisation"));
        String[] levelHeaders={"Niveau","Définition","Détail"};
        int[] levelWidths={90,520,100};
        TableLayout levels=dataTable(levelHeaders,levelWidths);
        String[][] levelDefs={
            {"A1","Visible dans les autorisations"},
            {"A2","Visible dans Toutes les autorisations"},
            {"A3","Action possible sans intervention immédiate"},
            {"A4","Avertissement de vigilance Android / AOSP"},
            {"A5","Portée système ou inter-applications"}
        };
        for(int i=0;i<levelDefs.length;i++){
            final int level=i+1;
            final String code=levelDefs[i][0],definition=levelDefs[i][1];
            addTableRow(levels,new String[]{code,definition},null,levelWidths,level,null,null,
                v->showDetail(code+" · niveau d’autorisation",definition,null,null));
        }
        page.addView(tableScroller(levels));

        page.addView(sectionTitle("Catégories d’applications"));
        String[] groupHeaders={"Catégorie","Définition","Détail"};
        int[] groupWidths={160,450,100};
        TableLayout groups=dataTable(groupHeaders,groupWidths);
        addTableRow(groups,new String[]{"Android","UID réservé/partagé ou attribution non unique dans le journal."},
            null,groupWidths,0,null,null,v->showDetail("Android",
                "Événements Android dont AIV ne peut pas attribuer proprement l’auteur à un seul package : UID réservé, partagé ou plusieurs candidats.",null,null));
        addTableRow(groups,new String[]{"Système","Application préinstallée identifiée avec un package unique."},
            null,groupWidths,0,null,null,v->showDetail("Système",
                "Application préinstallée/système pour laquelle Android permet une attribution unique au package.",null,null));
        addTableRow(groups,new String[]{"Utilisateur","Application installée par l’utilisateur avec un package unique."},
            null,groupWidths,0,null,null,v->showDetail("Utilisateur",
                "Application non système installée dans le profil utilisateur et attribuable à un package unique.",null,null));
        page.addView(tableScroller(groups));

        page.addView(sectionTitle("État AIV"));
        String[] headers={"Section","État","Résumé","Ouvrir"};
        int[] widths={220,170,480,100};
        TableLayout table=dataTable(headers,widths);

        addTableRow(table,new String[]{"User Free","INCLUS","Journal local · Inventaire · Permissions · Flux VPN · Traqueurs · Anomalies · Intégrité · Exports"},
            null,widths,0,null,null,v->showDetail("User Free · inclus",
                "Journal local\nInventaire des applications\nPermissions\nFlux VPN\nTraqueurs\nAnomalies\nIntégrité d'affichage opt-in\nExports locaux",null,null));

        addTableRow(table,new String[]{"User Paid","APERÇU","Tout User Free + Shizuku · retrait contrôlé · restauration · surveillance persistante · identité VPN"},
            null,widths,0,null,null,v->showDetail("User Paid · contrôle",
                "Tout User Free + Shizuku + retrait contrôlé des permissions + restauration + surveillance persistante + identité cryptographique VPN signée par Android Keystore.",null,null));

        addTableRow(table,new String[]{"TI","APERÇU","Tout User Paid + vue parc · enrôlement · politiques · rapports"},
            null,widths,0,null,null,v->showDetail("TI · parc",
                "Tout User Paid + vue parc + enrôlement + politiques + rapports. Présentation seulement dans cette version.",null,null));

        addTableRow(table,new String[]{"Application","NATIVE","All In Visible · "+getPackageName()+" · WebView absent"},
            null,widths,0,null,null,v->showDetail("Identité de l'application",
                "Nom : All In Visible\nPackage Android : "+getPackageName()+"\nInterface : NATIVE\nWebView : AUCUN",
                "Réglages Android",()->openAppSettings(getPackageName())));

        try{
            JSONObject audit=PermissionAudit.get(this).summary();
            long total=audit.optLong("total"),system=audit.optLong("system");
            String status=audit.optBoolean("busy")?"CALCUL EN COURS":"PRÊT";
            addTableRow(table,new String[]{"Inventaire Android",status,total+" applications · "+system+" système · "+Math.max(0,total-system)+" utilisateur"},
                null,widths,0,null,null,v->showJsonDetail("Inventaire Android",audit,null));
        }catch(Exception e){
            addTableRow(table,new String[]{"Inventaire Android","INDISPONIBLE",e.getClass().getSimpleName()},
                null,widths,0,null,null,v->showDetail("Inventaire Android","Indisponible : "+e.getClass().getSimpleName(),null,null));
        }

        try{
            long events=EventStore.get(this).latestId();
            String vpn=NetworkCaptureService.running?NetworkCaptureService.stateText:(NetworkCaptureService.starting?"Démarrage":NetworkCaptureService.stateText);
            String summary=events+" événements · Collecte "+yesNo(RecorderService.running)+" · Analyse "+yesNo(WatcherService.analysisActive)+" · VPN "+vpn;
            addTableRow(table,new String[]{"Journal et réseau",NetworkCaptureService.running?"ACTIF":"ÉTAT",summary},
                null,widths,0,null,null,v->showDetail("Journal et réseau",
                    "Événements : "+events+"\nCollecteur : "+yesNo(RecorderService.running)+"\nAnalyse : "+yesNo(WatcherService.analysisActive)+"\nVPN AIV : "+vpn+
                    (NetworkCaptureService.lastError.isEmpty()?"":"\nErreur VPN : "+NetworkCaptureService.lastError),
                    "Réglages VPN",()->openSetting(Settings.ACTION_VPN_SETTINGS)));
        }catch(Exception e){
            addTableRow(table,new String[]{"Journal et réseau","INDISPONIBLE",e.getClass().getSimpleName()},
                null,widths,0,null,null,v->showDetail("Journal et réseau","Indisponible : "+e.getClass().getSimpleName(),null,null));
        }

        try{
            JSONObject archive=ArchiveSync.state(this);
            String archiveState=archive.optString("last_error","").isEmpty()?(archive.optBoolean("running")?"SYNCHRO":"PRÊT"):"ERREUR";
            addTableRow(table,new String[]{"Archive Supabase",archiveState,
                archive.optLong("verified_segments")+" segment(s) vérifié(s) · "+archive.optInt("segment_size",50000)+" événements/segment"},
                null,widths,0,null,null,v->showJsonDetail("Archive Supabase",archive,null));
        }catch(Exception e){
            addTableRow(table,new String[]{"Archive Supabase","INDISPONIBLE",e.getClass().getSimpleName()},
                null,widths,0,null,null,v->showDetail("Archive Supabase","Indisponible : "+e.getClass().getSimpleName(),null,null));
        }

        try{
            JSONObject s=ShizukuCleanup.state(this);
            String status=s.optBoolean("authorized")?"AUTORISÉ":(s.optBoolean("binder")?"CONNECTÉ":"INACTIF");
            addTableRow(table,new String[]{"Shizuku",status,"UID serveur "+s.optInt("server_uid",-1)+" · "+s.optInt("candidates",-1)+" candidat(s)"},
                null,widths,0,null,null,v->showJsonDetail("Shizuku",s,null));
        }catch(Exception e){
            addTableRow(table,new String[]{"Shizuku","INDISPONIBLE",e.getClass().getSimpleName()},
                null,widths,0,null,null,v->showDetail("Shizuku","Indisponible : "+e.getClass().getSimpleName(),null,null));
        }

        try{
            JSONObject app=AppIdentity.forPackage(this,getPackageName());
            PinVault vault=new PinVault();
            String id=app.optString("app_identity_id");
            String key=vault.keyId();
            String shortId=id.length()>24?id.substring(0,24)+"…":id;
            String shortKey=key.length()>24?key.substring(0,24)+"…":key;
            addTableRow(table,new String[]{"Identité cryptographique","ACTIVE","App ID "+shortId+" · clé "+shortKey+" · niveau "+vault.securityLevel},
                null,widths,0,null,null,v->showDetail("Identité cryptographique AIV",
                    "App ID : "+id+"\nClé appareil : "+key+"\nNiveau clé : "+vault.securityLevel+"\nSource app : certificats PackageManager",null,null));
        }catch(Exception e){
            addTableRow(table,new String[]{"Identité cryptographique","INITIALISATION",e.getClass().getSimpleName()},
                null,widths,0,null,null,v->showDetail("Identité cryptographique AIV","Initialisation : "+e.getClass().getSimpleName(),null,null));
        }

        page.addView(tableScroller(table));

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(action("Actualiser l'inventaire",v->{PermissionAudit.get(this).scan();toast("Inventaire lancé");main.postDelayed(this::renderPresentation,900);}));
        actions.addView(action("Démarrer la collecte locale (sans VPN)",v->startCollection()));
        actions.addView(action("Exporter le journal local",v->beginJournalExport()));
        actions.addView(action("Synchroniser l'archive Supabase",v->{ArchiveSync.request(this);toast("Synchronisation Supabase demandée");main.postDelayed(this::renderPresentation,1200);}));
        actions.addView(action("Arrêter la collecte AIV",v->{Continuous.stop(this);toast("Collecte arrêtée");main.postDelayed(this::renderPresentation,400);}));
        page.addView(actions);
    }

    private void renderSpecialAccess(){
        page.removeAllViews();
        page.addView(sectionTitle("Accès spéciaux"));
        page.addView(note("AIV demande seulement les accès qui servent à observer ou protéger l'appareil. Les états sont présentés en tableau; Ouvrir donne le détail et, lorsqu'il existe, le raccourci Android correspondant."));
        try{
            JSONObject s=SpecialAccess.status(this);
            String[] headers={"Accès","État","Détail","Ouvrir"};
            int[] widths={230,170,430,100};
            TableLayout table=dataTable(headers,widths);

            String visibility=yesNo(s.optBoolean("query_all_packages"));
            addTableRow(table,new String[]{"Visibilité des applications",visibility,
                s.optInt("visible_packages",-1)+" applications visibles · AIV visible : "+yesNo(s.optBoolean("self_visible"))},
                null,widths,0,null,null,v->showDetail("Visibilité des applications",
                    "QUERY_ALL_PACKAGES : "+visibility+"\nApplications visibles : "+s.optInt("visible_packages",-1)+"\nAIV visible : "+yesNo(s.optBoolean("self_visible"))+
                    "\nBut : lire package, version, UID, certificat et permissions déclarées/accordées.",
                    "Réglages AIV",()->openAppSettings(getPackageName())));

            String usage=yesNo(s.optBoolean("usage_access"));
            addTableRow(table,new String[]{"Accès d'utilisation",usage,"Contexte d'utilisation et statistiques Android."},
                null,widths,0,null,null,v->showDetail("Accès d'utilisation",
                    "Usage Access : "+usage+"\nL'utilisateur garde le contrôle du commutateur système.",
                    "Réglages Android",()->openSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS)));

            String vpn=yesNo(s.optBoolean("vpn_prepared"));
            addTableRow(table,new String[]{"VPN AIV",vpn,"Observation locale des flux réseau; distincte de Shizuku."},
                null,widths,0,null,null,v->showDetail("VPN AIV",
                    "Autorisation VPN AIV : "+vpn+"\nLe VPN observe les métadonnées des flux sans déchiffrer TLS.",
                    "Réglages VPN",()->openSetting(Settings.ACTION_VPN_SETTINGS)));

            String shizuku=s.optBoolean("shizuku_authorized")?"AUTORISÉ":(s.optBoolean("shizuku_binder")?"CONNECTÉ, NON AUTORISÉ":"INACTIF");
            addTableRow(table,new String[]{"Shizuku",shizuku,"Contrôle validé distinct du VPN."},
                null,widths,0,null,null,v->showDetail("Shizuku",
                    "Binder : "+yesNo(s.optBoolean("shizuku_binder"))+"\nAutorisé : "+yesNo(s.optBoolean("shizuku_authorized")),
                    "Ouvrir Shizuku",()->openPackage("moe.shizuku.privileged.api")));

            String files=s.optBoolean("all_files_declared")?(s.optBoolean("all_files_granted")?"ACTIF":"DÉCLARÉ, NON ACTIF"):"NON DEMANDÉ";
            addTableRow(table,new String[]{"Tous les fichiers",files,"Stockage partagé seulement; pas /data/data des autres applications."},
                null,widths,0,null,null,v->showDetail("Tous les fichiers",
                    "État : "+files+"\nAIV 2.0.1 ne demande pas cet accès par défaut.",
                    "Accès spéciaux Android",()->openSetting("android.settings.MANAGE_SPECIAL_APP_ACCESSES")));

            String overlay=yesNo(s.optBoolean("overlay"));
            addTableRow(table,new String[]{"Superposition",overlay,"Dessiner par-dessus d'autres applications."},
                null,widths,0,null,null,v->showDetail("Superposition","État : "+overlay,null,null));

            String writeSettings=yesNo(s.optBoolean("write_settings"));
            addTableRow(table,new String[]{"Modifier réglages système",writeSettings,"Accès spécial Android; non requis par défaut."},
                null,widths,0,null,null,v->showDetail("Modifier les réglages système","État : "+writeSettings,
                    "Accès spéciaux Android",()->openSetting("android.settings.MANAGE_SPECIAL_APP_ACCESSES")));

            boolean installDeclared=s.optBoolean("request_install_packages_declared");
            String install=installDeclared?(s.optBoolean("can_request_package_installs")?"ACTIF":"DÉCLARÉ, NON ACTIF"):"NON DEMANDÉ";
            addTableRow(table,new String[]{"Installer applis inconnues",install,"AIV ne déclare pas REQUEST_INSTALL_PACKAGES dans cette version."},
                null,widths,0,null,null,v->showDetail("Installer des applis inconnues",
                    "REQUEST_INSTALL_PACKAGES déclaré : "+yesNo(installDeclared)+"\nCapacité active : "+yesNo(s.optBoolean("can_request_package_installs")),
                    "Accès spéciaux Android",()->openSetting("android.settings.MANAGE_SPECIAL_APP_ACCESSES")));

            String battery=yesNo(s.optBoolean("ignore_battery_optimizations"));
            addTableRow(table,new String[]{"Optimisation batterie",battery,"Exemption d'optimisation Android."},
                null,widths,0,null,null,v->showDetail("Optimisation batterie","Exemption active : "+battery,
                    "Réglages Android",()->openSetting(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));

            page.addView(tableScroller(table));
        }catch(Exception e){page.addView(card("Accès spéciaux","Lecture impossible : "+e.getClass().getSimpleName()));}
    }

    private void renderJournal(String query){
        int ticket=generation.incrementAndGet();
        page.removeAllViews();
        page.addView(sectionTitle("Journal"));
        page.addView(note("Chronologie locale native. Recherche dans les événements SQLite AIV; aucune vue Web n'intervient."));
        EditText search=searchBox("Application, domaine, action, UID ou ID",query);
        page.addView(search);
        page.addView(action("Rechercher / actualiser",v->renderJournal(search.getText().toString())));
        page.addView(action("Exporter le journal",v->beginJournalExport()));
        page.addView(note("Double-tape une valeur du tableau pour la placer dans la recherche. Le bouton Ouvrir affiche le détail complet de la ligne."));
        TextView loading=text("Lecture du journal…",14,MUTED,false);page.addView(loading);
        new Thread(()->{
            try{
                JSONObject data=EventStore.get(this).page(query,"",0,150,0,"","",false);
                JSONArray rows=data.optJSONArray("events");
                main.post(()->{
                    if(ticket!=generation.get()||!"journal".equals(currentPage))return;
                    page.removeView(loading);
                    page.addView(text(data.optLong("matched")+" résultat(s) · "+data.optLong("total")+" événement(s)",14,MUTED,true));
                    if(rows==null)return;
                    String[] headers={"Niv.","Heure","Application","UID","Action","Destination","Sens","↑","↓","Flux","Détail"};
                    int[] widths={64,120,190,90,220,280,100,100,100,170,100};
                    TableLayout table=dataTable(headers,widths);
                    for(int i=0;i<rows.length();i++){
                        JSONObject e=rows.optJSONObject(i);if(e==null)continue;
                        JSONObject d=e.optJSONObject("details");
                        int uid=d==null?-1:d.optInt("uid",-1);
                        String pkg=uniquePackage(d);
                        int level=levelForPackage(pkg);
                        String app=e.optString("app","Android");
                        String action=e.optString("action","—");
                        String destination=e.optString("destination","—");
                        String uidText=uid<0?"—":String.valueOf(uid);
                        String direction=d==null?"—":d.optString("direction","—");
                        String tx=d==null||d.optLong("tx_bytes",0)==0?"—":formatBytes(d.optLong("tx_bytes"));
                        String rx=d==null||d.optLong("rx_bytes",0)==0?"—":formatBytes(d.optLong("rx_bytes"));
                        String flow=d==null?"":d.optString("flow_correlation_id","");
                        String flowText=flow.isEmpty()?"—":(flow.length()>14?flow.substring(0,14)+"…":flow);
                        String[] values={levelShort(level),shortTime(e.optLong("timestamp_ms")),app,uidText,action,destination,direction,tx,rx,flowText};
                        String[] filters={null,null,app,uid<0?null:uidText,action,destination,direction,null,null,flow.isEmpty()?null:flow};
                        addTableRow(table,values,filters,widths,level,search,()->renderJournal(search.getText().toString()),
                            v->showEventPedigree("Journal · "+app,e,pkg));
                    }
                    page.addView(tableScroller(table));
                });
            }catch(Exception e){main.post(()->{if(ticket==generation.get()){page.removeView(loading);page.addView(card("Erreur journal",e.getClass().getSimpleName()));}});}
        },"aiv-native-journal").start();
    }

    private void renderTrackers(String query){
        int ticket=generation.incrementAndGet();
        page.removeAllViews();
        page.addView(sectionTitle("Traqueurs · Exodus"));
        page.addView(note("Regroupement local des trajets réseau qui correspondent au catalogue Exodus. Une correspondance de signature est un indice technique, pas une conclusion sur le contenu ou l'intention."));
        EditText search=searchBox("Application, tracker ou destination",query);
        page.addView(search);
        page.addView(action("Actualiser",v->{TrackerIndex.get(this).request();renderTrackers(search.getText().toString());}));
        page.addView(note("Double-tape une application, un package ou un tracker pour filtrer. Ouvrir affiche le groupe complet."));
        TextView loading=text("Indexation des traqueurs…",14,MUTED,false);page.addView(loading);
        new Thread(()->{
            try{
                JSONObject data=TrackerIndex.get(this).groups(query,0,60);
                JSONArray rows=data.optJSONArray("rows");
                JSONObject status=data.optJSONObject("status");
                main.post(()->{
                    if(ticket!=generation.get()||!"trackers".equals(currentPage))return;
                    page.removeView(loading);
                    page.addView(text((rows==null?0:rows.length())+" groupe(s) affiché(s) · "+data.optLong("total")+" total",14,MUTED,true));
                    if(status!=null)page.addView(note("Index Exodus : "+status.optLong("checkpoint")+" / "+status.optLong("latest_event")+" événements · "+status.optLong("tracker_groups")+" groupes · "+status.optLong("trail_steps")+" étapes."));
                    if(rows==null)return;
                    String[] headers={"Niv.","Application","Tracker","Package","Trajets","Dest.","↑","↓","Dernier","Détail"};
                    int[] widths={64,190,200,250,90,90,100,100,130,100};
                    TableLayout table=dataTable(headers,widths);
                    for(int i=0;i<rows.length();i++){
                        JSONObject x=rows.optJSONObject(i);if(x==null)continue;
                        String pkg=x.optString("package_name");
                        int level=levelForPackage(pkg);
                        String app=x.optString("app","—");
                        String tracker=x.optString("tracker_name","—");
                        String pkgText=pkg.isEmpty()?"—":pkg;
                        String[] values={levelShort(level),app,tracker,pkgText,String.valueOf(x.optLong("journeys")),String.valueOf(x.optLong("destinations_count")),formatBytes(x.optLong("tx_bytes")),formatBytes(x.optLong("rx_bytes")),shortTime(x.optLong("last_ms"))};
                        String[] filters={null,app,tracker,pkg.isEmpty()?null:pkg,null,null,null,null,null};
                        addTableRow(table,values,filters,widths,level,search,()->renderTrackers(search.getText().toString()),
                            v->showTrackerGroupDetail(x));
                    }
                    page.addView(tableScroller(table));
                });
            }catch(Exception e){main.post(()->{if(ticket==generation.get()){page.removeView(loading);page.addView(card("Erreur traqueurs",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())));}});}
        },"aiv-native-trackers").start();
    }

    private void renderAnomalies(String query){
        int ticket=generation.incrementAndGet();
        page.removeAllViews();
        page.addView(sectionTitle("Anomalies"));
        page.addView(note("Signalements déterministes générés à partir du journal local. Ils indiquent ce qui mérite un examen et conservent les preuves associées."));
        EditText search=searchBox("Application, signal ou contexte",query);
        page.addView(search);
        page.addView(action("Actualiser",v->{AnomalyMonitor.request(this);renderAnomalies(search.getText().toString());}));
        page.addView(action("Exporter les anomalies",v->beginAnalysisExport()));
        page.addView(note("Double-tape une valeur utile pour filtrer. Ouvrir affiche le détail complet de l'anomalie."));
        TextView loading=text("Lecture de l'analyse…",14,MUTED,false);page.addView(loading);
        new Thread(()->{
            try{
                JSONObject data=AnomalyMonitor.get(this).page("anomaly",false,0,100,query);
                JSONArray rows=data.optJSONArray("rows");
                main.post(()->{
                    if(ticket!=generation.get()||!"anomalies".equals(currentPage))return;
                    page.removeView(loading);
                    page.addView(text(data.optLong("total")+" groupe(s)",14,MUTED,true));
                    if(rows==null)return;
                    String[] headers={"Niv.","Heure","Application","Règle","Destination / sujet","Occ.","Sévérité","Détail"};
                    int[] widths={64,135,190,220,310,80,110,100};
                    TableLayout table=dataTable(headers,widths);
                    for(int i=0;i<rows.length();i++){
                        JSONObject x=rows.optJSONObject(i);if(x==null)continue;
                        JSONObject identity=x.optJSONObject("identity"),details=identity==null?null:identity.optJSONObject("details");
                        String pkg=uniquePackage(details);
                        int level=levelForPackage(pkg);
                        if(level==0&&"attention".equals(x.optString("severity")))level=4;
                        String actor=x.optString("actor",identity==null?"—":identity.optString("app","—"));
                        String title=x.optString("title","—");
                        String severity=x.optString("severity","—");
                        JSONObject facts=x.optJSONObject("facts");
                        String destination=facts==null?"":facts.optString("Destination contactée","");
                        if(destination.isEmpty())destination=x.optString("subject","—");
                        String rule=x.optString("rule",title);
                        String[] values={levelShort(level),shortTime(x.optLong("last_ms")),actor,rule,destination,String.valueOf(x.optLong("occurrences",1)),severity};
                        String[] filters={null,null,actor,rule,destination,null,severity};
                        final int rowLevel=level;
                        addTableRow(table,values,filters,widths,rowLevel,search,()->renderAnomalies(search.getText().toString()),
                            v->showAnomalyDetail(x,pkg));
                    }
                    page.addView(tableScroller(table));
                });
            }catch(Exception e){main.post(()->{if(ticket==generation.get()){page.removeView(loading);page.addView(card("Erreur anomalies",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())));}});}
        },"aiv-native-anomalies").start();
    }

    private void renderIntegrity(){
        page.removeAllViews();
        page.addView(sectionTitle("Intégrité d'affichage"));
        JSONObject s=ScreenIntegrityService.state();
        page.addView(note("Fonction gratuite. AIV observe la couche sémantique Android quand le service d’accessibilité est activé. Le mode visé est une passe : aucune seconde visite de la page n’est requise. Pour déclarer « affichage ≠ sémantique », AIV doit toutefois comparer la sémantique avec une observation visuelle indépendante prise au même moment."));
        String[] headers={"Élément","État","Détail","Ouvrir"};
        int[] widths={220,170,430,100};
        TableLayout table=dataTable(headers,widths);
        String service=s.optBoolean("connected")?"ACTIF":"INACTIF";
        addTableRow(table,new String[]{"Service d'intégrité",service,s.optString("status","—")},null,widths,0,null,null,
            v->showDetail("Service d'intégrité","Service : "+service+"\nÉtat : "+s.optString("status","—"),
                "Réglages accessibilité",()->openSetting(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addTableRow(table,new String[]{"Badge AIV",s.optBoolean("overlay_visible")?"ACTIF":"INACTIF","Le badge en haut à droite confirme que le service d’accessibilité AIV est connecté."},null,widths,0,null,null,
            v->showJsonDetail("Intégrité · badge AIV",s,null));
        addTableRow(table,new String[]{"État global",s.optBoolean("core_active")?"TOUT ACTIF":"À VÉRIFIER",
            "Collecte "+yesNo(s.optBoolean("collector_active"))+
            " · Corrélation "+yesNo(s.optBoolean("correlation_active"))+
            " · VPN "+(s.optBoolean("vpn_expected")?yesNo(s.optBoolean("vpn_active")):"OPTIONNEL")+
            " · Shizuku "+(s.optBoolean("shizuku_expected")?yesNo(s.optBoolean("shizuku_active")):"NON REQUIS")+
            " · Anomalies "+s.optLong("unread_anomalies",0)},null,widths,0,null,null,
            v->showJsonDetail("Intégrité · état global",s,null));
        addTableRow(table,new String[]{"Disponibilité","FREE","Détection d’intégrité d’affichage incluse pour tous les utilisateurs."},null,widths,0,null,null,
            v->showDetail("Intégrité d’affichage · Free","Cette fonction reste disponible dans le niveau Free. Aucune fonction de contrôle Shizuku n’est requise pour l’observation.",null,null));
        addTableRow(table,new String[]{"Application observée",s.optString("observed_package","—"),"Package actuellement exposé par le service."},null,widths,0,null,null,
            v->showJsonDetail("Intégrité · état complet",s,null));
        addTableRow(table,new String[]{"Arbre sémantique",s.optInt("node_count")+" nœuds",s.optInt("text_node_count")+" nœuds texte"},null,widths,0,null,null,
            v->showJsonDetail("Intégrité · arbre sémantique",s,null));
        addTableRow(table,new String[]{"Comparaison",s.optString("comparison_status","—"),"Une seule occurrence suffit. État actuel : la couche sémantique est active; le canal visuel simultané reste à brancher pour confirmer un écart affichage/sémantique."},null,widths,0,null,null,
            v->showJsonDetail("Intégrité · comparaison",s,null));
        page.addView(tableScroller(table));
        page.addView(action("Actualiser l'état",v->renderIntegrity()));
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
        page.addView(note("Double-tape le nom ou le package pour filtrer. Ouvrir affiche tout le dossier; Réglages Android reste disponible depuis le détail."));
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
                    String[] headers={"Niv.","Application","Package","UID","Type","Permissions","Détail"};
                    int[] widths={64,200,280,95,120,120,100};
                    TableLayout table=dataTable(headers,widths);
                    for(int i=0;i<shown;i++){
                        JSONObject a=matches.optJSONObject(i);if(a==null)continue;
                        JSONArray perms=a.optJSONArray("permissions");
                        String pkg=a.optString("package");
                        String label=a.optString("label",pkg);
                        int level=levelForPackage(pkg);
                        String uid=String.valueOf(a.optInt("uid",-1));
                        String type=a.optBoolean("system_app")?"Système":"Utilisateur";
                        String count=String.valueOf(perms==null?0:perms.length());
                        String[] values={levelShort(level),label,pkg,uid,type,count};
                        String[] filters={null,label,pkg,null,null,null};
                        addTableRow(table,values,filters,widths,level,search,()->renderApplications(search.getText().toString()),
                            v->{permissionSelection.clear();showPage("shizuku");openPermissions(pkg,"",0);});
                    }
                    page.addView(tableScroller(table));
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
        page.addView(note("Double-tape une valeur du tableau pour la placer dans la recherche. Ouvrir montre la ligne complète et les preuves conservées."));
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
                    String[] headers={"Niv.","Heure","Application","UID","Proto","Destination","Tracker","↑","↓","Détail"};
                    int[] widths={64,120,190,90,90,260,190,100,100,100};
                    TableLayout table=dataTable(headers,widths);
                    for(int i=0;i<flows.length();i++){
                        JSONObject f=flows.optJSONObject(i);if(f==null)continue;
                        String actor=f.optString("actor","Application non identifiée");
                        int uid=f.optInt("uid",-1);
                        String dest=f.optString("tls_sni");
                        if(dest.isEmpty())dest=f.optString("destination","—");
                        JSONArray packages=f.optJSONArray("packages");
                        String pkg=packages!=null&&packages.length()==1?packages.optString(0):"";
                        int level=levelForPackage(pkg);
                        JSONArray trackers=f.optJSONArray("tracker_matches");
                        String tracker=(trackers!=null&&trackers.length()>0&&trackers.optJSONObject(0)!=null)?trackers.optJSONObject(0).optString("name","tracker"):"—";
                        String uidText=uid<0?"—":String.valueOf(uid);
                        String protocol=f.optString("protocol","—");
                        String[] values={levelShort(level),shortTime(Math.max(f.optLong("first_outbound_ms"),f.optLong("first_inbound_ms"))),actor,uidText,protocol,dest,tracker,formatBytes(f.optLong("tx_bytes")),formatBytes(f.optLong("rx_bytes"))};
                        String[] filters={null,null,actor,uid<0?null:uidText,protocol,dest,"—".equals(tracker)?null:tracker,null,null};
                        addTableRow(table,values,filters,widths,level,search,()->renderFlows(search.getText().toString()),
                            v->showJsonDetail("Flux · "+actor,f,pkg));
                    }
                    page.addView(tableScroller(table));
                });
            }catch(Exception e){
                main.post(()->{if(ticket==generation.get()){page.removeView(loading);page.addView(card("Erreur flux",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())));}});
            }
        },"aiv-native-flows").start();
    }

    private void renderSupabase(){
        page.removeAllViews();
        page.addView(sectionTitle("Supabase · archive"));
        page.addView(note("État de l’archive distante. AIV envoie seulement les segments scellés du journal; un segment contient jusqu’à 50 000 événements. Un segment courant non scellé reste local jusqu’à sa fermeture."));
        try{
            JSONObject s=ArchiveSync.state(this);
            JSONObject w=JournalSegments.window(this,0);
            String[] headers={"Élément","État","Détail","Ouvrir"};
            int[] widths={220,170,470,100};
            TableLayout table=dataTable(headers,widths);

            String lastError=s.optString("last_error","");
            String syncState=s.optBoolean("running")?"SYNCHRO":(lastError.isEmpty()?"PRÊT":"ERREUR");
            addTableRow(table,new String[]{"Connexion / synchronisation",syncState,lastError.isEmpty()?"Aucune erreur Supabase enregistrée.":lastError},
                null,widths,0,null,null,v->showJsonDetail("Supabase · état local",s,null));

            long tracked=s.optLong("tracked_segments",0),verified=s.optLong("verified_segments",0);
            addTableRow(table,new String[]{"Segments suivis",String.valueOf(tracked),verified+" vérifié(s) à distance · dernier vérifié : "+s.optLong("last_verified_segment",0)},
                null,widths,0,null,null,v->showJsonDetail("Supabase · segments",s,null));

            String active=w.optLong("segment",0)>0?"#"+w.optLong("segment"):"récent";
            long segmentCount=w.isNull("event_count")?Math.max(0,w.optLong("latest")-w.optLong("first_id")+1):w.optLong("event_count");
            int segmentSize=s.optInt("segment_size",50000);
            int pct=segmentSize<=0?0:(int)Math.min(100,Math.round(segmentCount*100.0/segmentSize));
            String activeDetail=segmentCount+" / "+segmentSize+" événement(s) · "+pct+" % · scellé : "+yesNo(w.optBoolean("sealed"));
            addTableRow(table,new String[]{"Segment local actif",active,activeDetail},
                null,widths,0,null,null,v->showJsonDetail("Journal · segment actif",w,null));

            String nextState=w.optBoolean("sealed")?"PRÊT À ENVOYER":(segmentCount>=segmentSize?"FERMETURE":"EN COLLECTE");
            addTableRow(table,new String[]{"Prochain envoi",nextState,
                w.optBoolean("sealed")?"Le segment est scellé; la synchronisation peut l’envoyer.":"Encore "+Math.max(0,segmentSize-segmentCount)+" événement(s) avant la fermeture automatique du segment."},
                null,widths,0,null,null,v->showJsonDetail("Supabase · progression locale",w,null));

            addTableRow(table,new String[]{"Taille de segment",String.valueOf(segmentSize),"L’envoi automatique commence lorsqu’un segment est scellé. Les données locales ne sont pas supprimées par cette synchronisation."},
                null,widths,0,null,null,v->showJsonDetail("Supabase · politique d’archive",s,null));

            page.addView(tableScroller(table));
            page.addView(action("Synchroniser Supabase maintenant",v->{
                ArchiveSync.request(this);
                toast("Synchronisation Supabase demandée");
                main.postDelayed(this::renderSupabase,1200);
            }));
            page.addView(action("Exporter le journal local",v->beginJournalExport()));
            page.addView(action("Exporter les anomalies",v->beginAnalysisExport()));
        }catch(Exception e){
            page.addView(card("Supabase","État indisponible : "+e.getClass().getSimpleName()+" · "+String.valueOf(e.getMessage())));
            page.addView(action("Réessayer la synchronisation",v->{ArchiveSync.request(this);main.postDelayed(this::renderSupabase,1200);}));
        }
    }

    private void renderShizuku(){
        renderPermissionApps("",0);
    }

    private void permissionControls(){
        TextView state=text((PermissionControl.authorized()?"Shizuku autorisé":"Shizuku à autoriser")+" · "+PermissionControl.status(),15,BLUE,true);
        permissionStatusView=state;page.addView(state);
        if(!PermissionControl.authorized())page.addView(action("Autoriser AIV dans Shizuku",v->{
            try{
                if(!rikka.shizuku.Shizuku.pingBinder()){showDetail("Shizuku","Démarre Shizuku, puis reviens autoriser AIV.","Ouvrir Shizuku",()->openPackage("moe.shizuku.privileged.api"));return;}
                if(rikka.shizuku.Shizuku.shouldShowRequestPermissionRationale()){showDetail("Shizuku","Active AIV dans les applications autorisées de Shizuku.","Ouvrir Shizuku",()->openPackage("moe.shizuku.privileged.api"));return;}
                rikka.shizuku.Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
            }catch(Exception e){showDetail("Shizuku",String.valueOf(e.getMessage()),null,null);}
        }));
        if(PermissionControl.running()){
            page.addView(action("Arrêter après la commande en cours",v->{permissionPrepareAfterReview=false;PermissionControl.requestStop();}));pollPermissionOperation();
        }
        page.addView(action("Résultats, restauration et export",v->new AlertDialog.Builder(this).setTitle("Interventions Shell")
            .setItems(new String[]{"Voir le résultat des commandes","Restaurer les derniers retraits","Exporter les rapports Shell"},(dialog,which)->{
                if(which==0)showPermissionReport(0);
                else if(which==1)new AlertDialog.Builder(this).setTitle("Restaurer les retraits confirmés ?")
                    .setMessage("AIV parcourt tous les lots du dernier plan. Elle vérifie l’identité et l’état de chaque droit avant restauration.")
                    .setNegativeButton("Annuler",null).setPositiveButton("Restaurer",(d,w)->permissionJob(()->PermissionControl.restore(this))).show();
                else requestPermissionExport();
            }).setNegativeButton("Fermer",null).show()));
    }

    private void chooseGlobalPermissionPolicy(){
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),dp(12),dp(12),dp(12));
        body.addView(note("Ces catégories définissent les accès à réduire. Les usages particuliers enregistrés restent prioritaires. Le plan explique chaque proposition; une signature Exodus aide à prioriser l’examen."));
        java.util.Set<String> groups=PermissionControl.reviewGroups(this);
        for(int i=0;i<PermissionReviewRules.GROUPS.length;i++){
            String key=PermissionReviewRules.GROUPS[i];CheckBox box=new CheckBox(this);box.setText(PermissionReviewRules.LABELS[i]);box.setTextColor(TEXT);box.setTextSize(16);box.setChecked(groups.contains(key));box.setEnabled(!PermissionControl.running());
            box.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));
            box.setOnCheckedChangeListener((button,checked)->{try{PermissionControl.setReviewGroup(this,key,checked);}catch(Exception e){toast(e.getMessage());}});body.addView(box);
        }
        CheckBox protectedApps=new CheckBox(this);protectedApps.setText("Inclure les applications protégées par AIV");protectedApps.setTextColor(ORANGE);protectedApps.setTextSize(16);protectedApps.setChecked(PermissionControl.includeProtected(this));protectedApps.setEnabled(!PermissionControl.running());
        protectedApps.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));protectedApps.setOnCheckedChangeListener((button,checked)->{try{PermissionControl.setIncludeProtected(this,checked);}catch(Exception e){toast(e.getMessage());}});body.addView(protectedApps);
        body.addView(note("Cette option autorise l’examen des rôles actifs et composants que la 2.0.5 protégeait dans AIV. Le plan peut réduire leurs fonctions. Les verrous Android restent identifiés. Un UID partagé reste un groupe d’applications : cette version conserve cette portée en lecture seule."));
        ScrollView scroll=new ScrollView(this);scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Accès à réduire sur le téléphone").setView(scroll).setPositiveButton("Terminé",(d,w)->{if(permissionPackage.isEmpty())renderPermissionApps(permissionQuery,permissionOffset);else openPermissions(permissionPackage,"",0);}).show();
    }

    private void prepareGlobalPermissions(){
        toast("Préparation de tous les lots…");new Thread(()->{
            try{JSONObject plan=PermissionControl.previewReview(this);main.post(()->renderGlobalPermissionPlan(plan,0));}
            catch(Exception e){main.post(()->showDetail("Plan global",String.valueOf(e.getMessage()),null,null));}
        },"aiv-global-preview-ui").start();
    }

    private void renderGlobalPermissionPlan(JSONObject plan,int offset){
        generation.incrementAndGet();currentPage="shizuku";permissionPackage="";page.removeAllViews();page.addView(sectionTitle("Plan global de retrait"));
        page.addView(text(plan.optInt("rights")+" droits · "+plan.optInt("apps")+" applications · "+plan.optJSONArray("batches").length()+" lots",21,TEXT,true));
        page.addView(note("Analyse : "+plan.optInt("scanned")+"/"+plan.optInt("total")+" applications. Chaque commande sera revérifiée avant exécution. Les droits sont proposés selon les catégories choisies et les usages enregistrés; le retrait peut couper les fonctions correspondantes."));
        page.addView(action("Retour aux applications",v->renderPermissionApps(permissionQuery,permissionOffset)));
        if(plan.optInt("rights")>0)page.addView(action("Appliquer les "+plan.optInt("rights")+" retraits",v->new AlertDialog.Builder(this).setTitle("Appliquer ce plan global ?")
            .setMessage(plan.optInt("rights")+" droits sur "+plan.optInt("apps")+" applications. Tous les lots s’enchaînent; les résultats et les états avant sont enregistrés pour restauration.")
            .setNegativeButton("Annuler",null).setPositiveButton("Appliquer",(dialog,which)->{permissionJob(()->PermissionControl.applyReview(this,plan.optString("stamp")));renderPermissionApps(permissionQuery,0);}).show()));
        else page.addView(note("Aucun accès actif admissible dans les catégories sélectionnées. Choisir d’autres accès à réduire ou ouvrir les permissions d’une application pour un examen individuel."));
        JSONArray rows=plan.optJSONArray("changes");String[] headers={"Application","Permission","Proposition","Avant","Commande Shell","Détail"};int[] widths={190,300,260,230,440,100};TableLayout table=dataTable(headers,widths);
        int end=Math.min(rows.length(),offset+50);for(int i=offset;i<end;i++){JSONObject row=rows.optJSONObject(i);addTableRow(table,new String[]{row.optString("label"),row.optString("name"),row.optString("reason"),row.optString("before"),row.optString("command")},null,widths,0,null,null,v->showJsonDetail("Retrait proposé",row,row.optString("package")));}
        page.addView(tableScroller(table));page.addView(text("Propositions "+(rows.length()==0?0:offset+1)+" à "+end+" sur "+rows.length(),15,MUTED,false));
        if(offset>0)page.addView(action("Propositions précédentes",v->renderGlobalPermissionPlan(plan,Math.max(0,offset-50))));
        if(end<rows.length())page.addView(action("Propositions suivantes",v->renderGlobalPermissionPlan(plan,offset+50)));
    }

    private void showPermissionReport(int offset){
        int ticket=generation.incrementAndGet();currentPage="shizuku";permissionPackage="";page.removeAllViews();page.addView(sectionTitle("Résultat des commandes Shell"));page.addView(action("Retour aux applications",v->renderPermissionApps(permissionQuery,permissionOffset)));permissionControls();
        new Thread(()->{
            try{JSONObject data=PermissionControl.reportPage(this,offset);main.post(()->{
                if(ticket!=generation.get())return;JSONObject report=data.optJSONObject("report");JSONArray rows=data.optJSONArray("rows");
                page.addView(text(report.optInt("changed",report.optInt("restored"))+" changements confirmés · "+report.optInt("failed")+" états non confirmés · "+report.optString("phase"),18,TEXT,true));
                String[] headers={"Détail","Application","Permission","Résultat","Avant","Après / erreur"};int[] widths={100,190,290,210,220,300};TableLayout table=dataTable(headers,widths);
                for(int i=0;i<rows.length();i++){
                    JSONObject row=rows.optJSONObject(i),before=row.optJSONObject("before"),after=row.optJSONObject("after");
                    String result=row.optString("outcome"),label="confirmed".equals(result)?"Confirmé":"no_effect".equals(result)?"Sans effet":"already_restored".equals(result)?"Déjà restauré":"refused".equals(result)?"Refus Android":"À vérifier : "+result;
                    addLeadingPermissionRow(table,new String[]{row.optString("label",row.optString("package")),row.optString("name"),label,before==null?"—":before.optString("state_key",before.toString()),after==null?row.optString("error",row.optString("stderr")):after.toString()},widths,"Détail",v->showJsonDetail("Résultat Shell",row,row.optString("package")));
                }
                page.addView(tableScroller(table));page.addView(text(data.optInt("total")+" commandes enregistrées",15,MUTED,false));
                if(offset>0)page.addView(action("Résultats précédents",v->showPermissionReport(Math.max(0,offset-50))));
                if(offset+rows.length()<data.optInt("total"))page.addView(action("Résultats suivants",v->showPermissionReport(offset+50)));
            });}catch(Exception e){main.post(()->showDetail("Rapport",e.getMessage(),null,null));}
        },"aiv-permission-readable-report").start();
    }

    private void addLeadingPermissionRow(TableLayout table,String[] values,int[] widths,String action,View.OnClickListener listener){
        TableRow row=new TableRow(this);row.setBackgroundColor(((table.getChildCount()-1)&1)==0?PANEL_2:PANEL);Button open=button(action);open.setTextSize(13);open.setOnClickListener(listener);row.addView(open,new TableRow.LayoutParams(dp(widths[0]),dp(52)));
        for(int i=0;i<values.length;i++){TextView cell=tableCell(values[i],widths[i+1],false,TEXT);if(i==0)cell.setOnClickListener(listener);row.addView(cell);}table.addView(row);
    }

    private void renderPermissionApps(String query,int offset){
        int ticket=generation.incrementAndGet();currentPage="shizuku";permissionPackage="";permissionQuery=query;permissionOffset=offset;
        page.removeAllViews();page.addView(sectionTitle("Contrôle des permissions"));
        page.addView(action("Analyser et préparer le contrôle du téléphone",v->{permissionPrepareAfterReview=true;permissionJob(()->{PermissionControl.startReview(this);return new JSONObject();});}));
        page.addView(action("Préparer à nouveau la dernière analyse",v->prepareGlobalPermissions()));
        page.addView(action("Choisir les accès à réduire",v->chooseGlobalPermissionPolicy()));
        permissionControls();
        page.addView(note("Analyse globale du profil courant, préinstallées et désactivées comprises. Accès en arrière-plan et accès spéciaux sélectionnés au départ; les autres catégories sont disponibles dans Choisir les accès à réduire. Bayton décrit les droits; Exodus apporte les signatures de traqueurs. Les états et verrous viennent du téléphone."));
        EditText search=searchBox("Nom, package ou usage",query);page.addView(search);
        page.addView(action("Rechercher / actualiser",v->renderPermissionApps(search.getText().toString(),0)));

        TextView loading=text("Lecture complète des applications…",16,BLUE,true);page.addView(loading);
        new Thread(()->{
            try{
                JSONObject data=PermissionControl.inventory(this,query,offset),review=PermissionControl.review(this);JSONArray rows=data.getJSONArray("rows");java.util.Map<String,JSONObject> reviewed=new java.util.HashMap<>();JSONArray audited=review.optJSONArray("applications");if(audited!=null)for(int i=0;i<audited.length();i++)reviewed.put(audited.getJSONObject(i).optString("package"),audited.getJSONObject(i));
                main.post(()->{
                    if(ticket!=generation.get())return;page.removeView(loading);
                    page.addView(text(data.optInt("total")+" applications · "+data.optInt("matched")+" résultat(s) · profil "+data.optInt("user"),18,TEXT,true));
                    if(!"none".equals(review.optString("phase")))page.addView(note("Analyse "+review.optString("phase")+" : "+review.optInt("scanned")+"/"+review.optInt("total")+" applications · "+review.optInt("permissions")+" permissions · "+review.optInt("proposed")+" propositions"+" · "+review.optInt("android_locked")+" verrous Android · "+review.optInt("aiv_protected")+" applications protégées par AIV · "+review.optInt("unknown")+" états non vérifiés · "+review.optInt("errors")+" erreurs"));
                    String[] headers={"Ouvrir","Application","Proposés","Package","UID","Type","Déclarées","Accordées","Runtime accordées","Usage","Hors usage","Exodus"};
                    int[] widths={100,190,105,270,100,130,105,105,130,230,110,180};TableLayout table=dataTable(headers,widths);
                    for(int i=0;i<rows.length();i++){
                        JSONObject row=rows.optJSONObject(i);if(row==null)continue;String pkg=row.optString("package"),label=row.optString("label",pkg);
                        String trackerStatus=row.optString("tracker_status");String trackers="PENDING".equals(trackerStatus)?"En attente":row.optInt("tracker_count")+" signature(s) · "+("PARTIAL".equals(trackerStatus)?"partiel":"analysé");
                        String type=row.optInt("uid")%100000<10000?"Android":row.optBoolean("system")?"Système":"Utilisateur";
                        JSONObject audit=reviewed.get(pkg);String count=audit==null?"À analyser":audit.has("error")?"Erreur":String.valueOf(audit.optInt("proposed"));
                        addLeadingPermissionRow(table,new String[]{label,count,pkg,String.valueOf(row.optInt("uid")),type,String.valueOf(row.optInt("declared")),String.valueOf(row.optInt("granted")),String.valueOf(row.optInt("runtime_granted")),row.optString("profile_label"),String.valueOf(row.optInt("outside_profile")),trackers},widths,"Permissions",v->{permissionSelection.clear();openPermissions(pkg,"",0);});
                    }
                    page.addView(tableScroller(table));
                    page.addView(note("Runtime accordées indique un type de droit; les verrous et la révocation effective sont vérifiés dans le détail. Hors usage compte les droits déclarés exclus par l’usage choisi. "+data.optString("scope")));
                    if(offset>0)page.addView(action("Applications précédentes",v->renderPermissionApps(query,Math.max(0,offset-data.optInt("limit")))));
                    if(offset+rows.length()<data.optInt("matched"))page.addView(action("Applications suivantes",v->renderPermissionApps(query,offset+data.optInt("limit"))));
                });
            }catch(Exception e){main.post(()->{if(ticket==generation.get()){page.removeView(loading);page.addView(note("Inventaire : "+e.getMessage()));}});}
        },"aiv-permission-apps-ui").start();
    }

    private void openPermissions(String pkg,String query,int offset){
        int ticket=generation.incrementAndGet();currentPage="shizuku";permissionPackage=pkg;
        page.removeAllViews();page.addView(sectionTitle("Permissions de l’application"));
        page.addView(action("Toutes les applications",v->renderPermissionApps(permissionQuery,permissionOffset)));
        permissionControls();TextView loading=text("Lecture Android et vérification Shell…",16,BLUE,true);page.addView(loading);
        new Thread(()->{
            try{
                JSONObject d=PermissionControl.detail(this,pkg,true);JSONArray all=d.getJSONArray("permissions"),rows=new JSONArray();
                String needle=query==null?"":query.trim().toLowerCase(Locale.ROOT);java.util.Set<String> eligible=new java.util.HashSet<>();
                for(int i=0;i<all.length();i++){
                    JSONObject row=all.getJSONObject(i);if(row.optBoolean("can_revoke"))eligible.add(row.getString("name"));
                    if(needle.isEmpty()||(row.optString("name")+" "+row.optString("label")+" "+row.optString("assessment")+" "+row.optString("blocked_reason")).toLowerCase(Locale.ROOT).contains(needle))rows.put(row);
                }
                main.post(()->{
                    if(ticket!=generation.get())return;page.removeView(loading);permissionSelection.retainAll(eligible);
                    page.addView(text(d.optString("label")+" · "+all.length()+" permissions déclarées",23,TEXT,true));
                    page.addView(note(pkg+" · UID "+d.optInt("uid")+" · "+d.optInt("can_revoke")+" retrait(s) admissible(s)\nUsage : "+d.optString("profile_label")+"\n"+
                        (!d.optString("target_reason").isEmpty()?d.optString("target_reason")+"\n":"")+(d.optBoolean("shell_observed")?"État Shell collecté":"État Shell indisponible : "+d.optString("shell_error"))));
                    page.addView(action("Usage particulier (facultatif)",v->choosePermissionProfile(pkg)));
                    page.addView(action("Choisir les accès à réduire",v->chooseGlobalPermissionPolicy()));
                    page.addView(action("Traqueurs Exodus / APK et catalogues",v->showJsonDetail("Exodus et Bayton · "+d.optString("label"),d,pkg)));
                    EditText search=searchBox("Permission, état ou raison",query);page.addView(search);
                    page.addView(action("Filtrer / actualiser les permissions",v->openPermissions(pkg,search.getText().toString(),0)));
                    TextView selected=text(permissionSelection.size()+" droit(s) sélectionné(s)",18,BLUE,true);page.addView(selected);
                    page.addView(action("Sélectionner les retraits proposés",v->{
                        permissionSelection.clear();for(int i=0;i<all.length();i++){JSONObject row=all.optJSONObject(i);if(row!=null&&PermissionReviewRules.selected(row.optString("name"),row.optBoolean("can_revoke"),row.optString("kind"),row.optJSONObject("appop")==null?"":row.optJSONObject("appop").optString("mode"),!d.optString("profile").isEmpty(),row.optBoolean("outside_profile"),PermissionControl.reviewGroups(this)))permissionSelection.add(row.optString("name"));}
                        openPermissions(pkg,query,offset);
                    }));
                    page.addView(action("Effacer la sélection",v->{permissionSelection.clear();openPermissions(pkg,query,offset);}));
                    page.addView(action("Préparer le retrait de la sélection",v->{
                        if(permissionSelection.isEmpty()){toast("Sélectionne au moins un droit révocable");return;}
                        JSONArray requests=new JSONArray().put(EventStore.object("package",pkg,"permissions",new JSONArray(new java.util.TreeSet<>(permissionSelection))));preparePermissionPlan(requests);
                    }));
                    String[] headers={"Retirer","Permission","État Android","Type","Shell / verrou","Usage","Détail"};int[] widths={100,330,130,140,330,230,100};TableLayout table=dataTable(headers,widths);
                    int end=Math.min(rows.length(),offset+50);
                    for(int i=offset;i<end;i++){
                        JSONObject row=rows.optJSONObject(i);if(row==null)continue;String name=row.optString("name");boolean can=row.optBoolean("can_revoke");
                        TableRow tr=new TableRow(this);tr.setBackgroundColor((i&1)==0?PANEL_2:PANEL);
                        CheckBox check=new CheckBox(this);check.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));check.setChecked(permissionSelection.contains(name));check.setEnabled(can);
                        check.setContentDescription("Retirer "+name);check.setOnCheckedChangeListener((button,checked)->{if(checked)permissionSelection.add(name);else permissionSelection.remove(name);selected.setText(permissionSelection.size()+" droit(s) sélectionné(s)");});
                        tr.addView(check,new TableRow.LayoutParams(dp(100),dp(52)));
                        String state=row.optBoolean("granted")?"Accordée":"Refusée";JSONObject appOp=row.optJSONObject("appop");
                        if(appOp!=null)state+="\nOp : "+(appOp.optString("mode").isEmpty()?"inconnue":appOp.optString("mode"));
                        String shell=can?("appop".equals(row.optString("kind"))?"Bloquer l’opération via Shell":"Retirer via Shell"):row.optString("blocked_reason");
                        String[] values={name,state,row.optString("protection"),shell,row.optString("assessment")};
                        for(int j=0;j<values.length;j++){TextView cell=tableCell(values[j],widths[j+1],false,TEXT);if(j==0)cell.setMaxLines(10);tr.addView(cell);}
                        Button detail=button("Ouvrir");detail.setOnClickListener(v->showJsonDetail("Permission · "+name,row,pkg));tr.addView(detail,new TableRow.LayoutParams(dp(100),dp(48)));table.addView(tr);
                    }
                    page.addView(tableScroller(table));page.addView(text(rows.length()+" résultat(s) · lignes "+(rows.length()==0?0:offset+1)+" à "+end,15,MUTED,false));
                    if(offset>0)page.addView(action("Permissions précédentes",v->openPermissions(pkg,query,Math.max(0,offset-50))));
                    if(end<rows.length())page.addView(action("Permissions suivantes",v->openPermissions(pkg,query,offset+50)));
                    page.addView(note("La sélection peut couvrir plusieurs pages. Une commande Shell n’ouvre pas une demande de permission Android pour chaque application. Elle reste soumise aux droits de Shell et aux verrous du système. Les retraits choisis peuvent désactiver la fonction correspondante de l’application."));
                });
            }catch(Exception e){main.post(()->{if(ticket==generation.get()){page.removeView(loading);page.addView(note("Permissions : "+e.getMessage()));}});}
        },"aiv-permission-detail-ui").start();
    }

    private void choosePermissionProfile(String pkg){
        try{
            JSONObject profiles=PermissionControl.profileChoices(this);java.util.List<String> keys=new java.util.ArrayList<>();keys.add("");
            java.util.List<String> labels=new java.util.ArrayList<>();labels.add("Examen manuel — aucun droit présélectionné");
            java.util.TreeSet<String> ordered=new java.util.TreeSet<>();java.util.Iterator<String> iterator=profiles.keys();while(iterator.hasNext())ordered.add(iterator.next());
            for(String key:ordered){keys.add(key);labels.add(profiles.getJSONObject(key).getString("label"));}
            new AlertDialog.Builder(this).setTitle("Usage réel de cette application").setItems(labels.toArray(new String[0]),(dialog,which)->{
                try{PermissionControl.setProfile(this,pkg,keys.get(which));permissionSelection.clear();openPermissions(pkg,"",0);}catch(Exception e){toast(e.getMessage());}
            }).setNegativeButton("Annuler",null).show();
        }catch(Exception e){toast("Usages : "+e.getMessage());}
    }

    private void preparePermissionPlan(JSONArray requests){
        toast("Vérification du plan Shell en cours");
        new Thread(()->{
            try{
                JSONObject plan=requests==null?PermissionControl.previewProfiles(this):PermissionControl.preview(this,requests);
                main.post(()->{
                    JSONArray rows=plan.optJSONArray("changes");int count=rows==null?0:rows.length();
                    if(count==0){showDetail("Plan de retrait","Aucun droit hors usage admissible. Choisis l’usage des applications ou sélectionne leurs permissions dans le tableau.",null,null);return;}
                    LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),dp(12),dp(12),dp(12));
                    body.addView(text(count+" droit(s) · "+plan.optJSONObject("identities").length()+" application(s)",19,TEXT,true));
                    String[] headers={"Application","Droit","Action Shell","Avant","Détail"};int[] widths={200,300,440,190,100};TableLayout table=dataTable(headers,widths);
                    for(int i=0;i<rows.length();i++){
                        JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                        addTableRow(table,new String[]{row.optString("label"),row.optString("name"),row.optString("command"),row.optJSONObject("before").optString("state_key")},null,widths,0,null,null,v->showJsonDetail("Retrait proposé",row,row.optString("package")));
                    }
                    body.addView(tableScroller(table));body.addView(note("L’état avant est conservé. Les commandes sont vérifiées après exécution et restaurables tant que l’APK et le droit restent identiques. Appliquer peut couper les fonctions correspondantes."));
                    ScrollView scroll=new ScrollView(this);scroll.addView(body);
                    new AlertDialog.Builder(this).setTitle("Plan de retrait Shell").setView(scroll).setNegativeButton("Annuler",null)
                        .setPositiveButton("Appliquer les "+count+" retraits",(dialog,which)->permissionJob(()->PermissionControl.apply(this,plan.optString("stamp")))).show();
                });
            }catch(Exception e){main.post(()->showDetail("Plan Shell",String.valueOf(e.getMessage()),null,null));}
        },"aiv-permission-plan-ui").start();
    }

    private interface PermissionJob {JSONObject run()throws Exception;}
    private void permissionJob(PermissionJob job){
        toast("Vérification avant intervention…");new Thread(()->{
            try{JSONObject result=job.run();main.post(()->{permissionShowReportAfterJob=result.has("report_id");if("shizuku".equals(currentPage)){if(permissionPackage.isEmpty())renderPermissionApps(permissionQuery,permissionOffset);else openPermissions(permissionPackage,"",0);}pollPermissionOperation();});}
            catch(Exception e){main.post(()->{permissionPrepareAfterReview=false;permissionShowReportAfterJob=false;showDetail("Contrôle Shell",String.valueOf(e.getMessage()),null,null);});}
        },"aiv-permission-request-ui").start();
    }
    private boolean permissionPollScheduled;
    private void pollPermissionOperation(){
        if(permissionPollScheduled||isFinishing()||isDestroyed())return;permissionPollScheduled=true;
        main.postDelayed(()->{
            permissionPollScheduled=false;if(isFinishing()||isDestroyed())return;
            if(PermissionControl.running()){
                if("shizuku".equals(currentPage)&&permissionStatusView!=null)permissionStatusView.setText(PermissionControl.status());
                pollPermissionOperation();return;
            }
            toast(PermissionControl.status());permissionSelection.clear();
            if(permissionPrepareAfterReview){permissionPrepareAfterReview=false;prepareGlobalPermissions();return;}
            if(permissionShowReportAfterJob){permissionShowReportAfterJob=false;showPermissionReport(0);return;}
            if("shizuku".equals(currentPage)){if(permissionPackage.isEmpty())renderPermissionApps(permissionQuery,permissionOffset);else openPermissions(permissionPackage,"",0);}
        },2000);
    }

    private void requestPermissionExport(){
        try{
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE,"AIV-permissions-shell-"+System.currentTimeMillis()+".json");startActivityForResult(intent,PERMISSION_EXPORT_REQUEST);
        }catch(Exception e){toast("Export : "+e.getMessage());}
    }
    private void exportPermissionsTo(Uri destination){
        new Thread(()->{
            try{java.io.File ready=ExportFiles.stage(this,writer->PermissionControl.export(this,writer));String result=ExportFiles.copy(this,ready,destination);main.post(()->toast(result));}
            catch(Exception e){main.post(()->showDetail("Export Shell",String.valueOf(e.getMessage()),null,null));}
        },"aiv-permission-export-ui").start();
    }

    private void showShizukuPlan(){
        try{
            JSONObject plan=ShizukuCleanup.plan(this);
            JSONArray rows=plan.optJSONArray("rows");
            LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),dp(8),dp(12),dp(12));
            body.addView(text(plan.optInt("candidates")+" application(s) · "+plan.optInt("planned_actions")+" action(s) proposées",15,TEXT,true));
            body.addView(text(plan.optInt("runtime_permissions")+" permission(s) runtime · "+plan.optInt("special_access")+" accès spécial(aux) · "+plan.optInt("skipped")+" ignoré(s)",13,MUTED,false));
            if(rows!=null)for(int i=0;i<rows.length();i++){
                JSONObject r=rows.optJSONObject(i);if(r==null)continue;
                int actions=r.optInt("planned_actions");
                String pkg=r.optString("package"),label=r.optString("label",pkg);
                JSONArray perms=r.optJSONArray("runtime_permissions"),specials=r.optJSONArray("special_access");
                String detail=actions+" action(s)\nPermissions runtime : "+(perms==null?0:perms.length())+"\nAccès spéciaux : "+(specials==null?0:specials.length())+"\n"+pkg;
                body.addView(card("A"+r.optInt("level")+" · "+label,detail));
            }
            ScrollView scroll=new ScrollView(this);scroll.addView(body);
            new AlertDialog.Builder(this).setTitle("Plan de ménage Shizuku").setView(scroll).setPositiveButton("Fermer",null).show();
        }catch(Exception e){showDetail("Plan Shizuku","Impossible de préparer le plan : "+e.getClass().getSimpleName()+" · "+String.valueOf(e.getMessage()),null,null);}
    }

    private void confirmShizukuCleanup(){
        try{
            JSONObject plan=ShizukuCleanup.plan(this);
            int actions=plan.optInt("planned_actions");
            if(actions<=0){showDetail("Ménage Shizuku","Aucune action sûre à appliquer dans l'état actuel.",null,null);return;}
            new AlertDialog.Builder(this)
                .setTitle("Appliquer le ménage contrôlé ?")
                .setMessage(actions+" action(s) sont proposées sur "+plan.optInt("candidates")+" application(s). AIV exclut les paquets système, rôles essentiels et UID partagés; chaque changement est vérifié et sauvegardé pour restauration.")
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Appliquer",(d,w)->{
                    ShizukuCleanup.requestOrRun();
                    toast("Ménage Shizuku démarré");
                    main.postDelayed(this::renderShizuku,1200);
                }).show();
        }catch(Exception e){showDetail("Ménage Shizuku","Préparation impossible : "+e.getClass().getSimpleName()+" · "+String.valueOf(e.getMessage()),null,null);}
    }

    private void restoreShizukuCleanup(){
        new AlertDialog.Builder(this)
            .setTitle("Restaurer le dernier nettoyage ?")
            .setMessage("AIV rejouera les commandes inverses conservées dans le dernier snapshot puis vérifiera l'état obtenu.")
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Restaurer",(d,w)->new Thread(()->{
                try{
                    JSONObject result=ShizukuCleanup.restore(this);
                    main.post(()->showJsonDetail("Restauration Shizuku",result,null));
                }catch(Exception e){
                    main.post(()->showDetail("Restauration Shizuku","Échec : "+e.getClass().getSimpleName()+" · "+String.valueOf(e.getMessage()),null,null));
                }
            },"aiv-shizuku-restore-ui").start()).show();
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
        page.addView(note("Aperçu investisseur. La gestion de parc n'est pas finalisée dans cette version; même cette vue reste structurée en tableau."));
        String[] headers={"Module","État","Résumé","Ouvrir"};
        int[] widths={220,170,480,100};
        TableLayout table=dataTable(headers,widths);
        addTableRow(table,new String[]{"Vue parc","PRÉVUE","Appareils enrôlés · conformité · anomalies · politiques · rapports · bascule appareil/parc"},
            null,widths,0,null,null,v->showDetail("TI · Vue parc",
                "Appareils enrôlés\nÉtat de conformité\nAnomalies\nPolitiques\nRapports\nBascule entre vue appareil et vue parc",null,null));
        addTableRow(table,new String[]{"Fonctions de parc","NON ACTIVÉES","Architecture prévue dans AIV 2.0.1; pas encore fonctionnelle."},
            null,widths,0,null,null,v->showDetail("TI · Statut",
                "Architecture prévue. Les fonctions de parc ne sont pas activées dans AIV 2.0.1.",null,null));
        page.addView(tableScroller(table));
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
                ArchiveSync.request(this);
            }catch(Throwable ignored){}
            main.postDelayed(()->{if("presentation".equals(currentPage))renderPresentation();},800);
        },"aiv-native-init").start();
    }

    private void startCollection(){
        try{
            Continuous.prefs(this).edit().putBoolean("enabled",true).putBoolean("analysis_enabled",true).putBoolean("vpn_enabled",false).apply();
            stopService(new Intent(this,NetworkCaptureService.class));
            Continuous.start(this);
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_REQUEST);
            toast("Collecte locale démarrée sans VPN");
            refreshHeaderStatus();
            main.postDelayed(this::renderPresentation,700);
        }catch(Exception e){toast("Démarrage impossible : "+e.getClass().getSimpleName());}
    }

    private void beginJournalExport(){
        try{
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE,"AIV-journal-"+System.currentTimeMillis()+".json");
            startActivityForResult(i,EXPORT_REQUEST);
        }catch(Exception e){toast("Export indisponible : "+e.getClass().getSimpleName());}
    }

    private void beginAnalysisExport(){
        try{
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE,"AIV-anomalies-"+System.currentTimeMillis()+".json");
            startActivityForResult(i,ANALYSIS_EXPORT_REQUEST);
        }catch(Exception e){toast("Export anomalies indisponible : "+e.getClass().getSimpleName());}
    }

    private void exportAnalysisTo(Uri destination){
        new Thread(()->{
            try{
                java.io.File ready=ExportFiles.stage(this,writer->AnomalyMonitor.get(this).export(writer));
                String result=ExportFiles.copy(this,ready,destination);
                main.post(()->toast(result));
            }catch(Exception e){
                main.post(()->showDetail("Export des anomalies","Échec : "+e.getClass().getSimpleName()+" · "+String.valueOf(e.getMessage()),null,null));
            }
        },"aiv-analysis-export").start();
    }

    private void exportJournalTo(Uri destination){
        new Thread(()->{
            try{
                java.io.File ready=ExportFiles.stage(this,writer->EventStore.get(this).export(writer,false));
                String result=ExportFiles.copy(this,ready,destination);
                main.post(()->toast(result));
            }catch(Exception e){
                main.post(()->showDetail("Export du journal","Échec : "+e.getClass().getSimpleName()+" · "+String.valueOf(e.getMessage()),null,null));
            }
        },"aiv-journal-export").start();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==VPN_REQUEST&&resultCode==RESULT_OK){
            try{NetworkCaptureService.lastError="";startForegroundService(new Intent(this,NetworkCaptureService.class));}
            catch(Exception e){toast("VPN : "+e.getClass().getSimpleName());}
        }else if(requestCode==EXPORT_REQUEST&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            exportJournalTo(data.getData());
        }else if(requestCode==ANALYSIS_EXPORT_REQUEST&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            exportAnalysisTo(data.getData());
        }else if(requestCode==PERMISSION_EXPORT_REQUEST&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            exportPermissionsTo(data.getData());
        }
    }

    @Override public void onResume(){
        super.onResume();
        main.removeCallbacks(headerStatusPulse);
        main.post(headerStatusPulse);
        if(page!=null&&"presentation".equals(currentPage))main.postDelayed(this::renderPresentation,250);
    }
    @Override public void onPause(){
        main.removeCallbacks(headerStatusPulse);
        super.onPause();
    }
    @Override public void onDestroy(){generation.incrementAndGet();try{ShizukuCleanup.detach();rikka.shizuku.Shizuku.removeRequestPermissionResultListener(permissionAuthListener);}catch(Throwable ignored){}super.onDestroy();}

    private void openSetting(String action){try{startActivity(new Intent(action));}catch(Exception e){toast("Réglage Android indisponible");}}
    private void openPackage(String pkg){
        try{Intent i=getPackageManager().getLaunchIntentForPackage(pkg);if(i==null)throw new IllegalStateException();startActivity(i);}
        catch(Exception e){toast("Application non installée ou non visible");}
    }
    private void openAppSettings(String pkg){
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:"+pkg)));}
        catch(Exception e){toast("Réglages indisponibles");}
    }

    private void refreshHeaderStatus(){
        if(statusRow==null)return;
        statusRow.removeAllViews();
        statusRow.addView(statusChip("Collecte",RecorderService.running));
        statusRow.addView(statusChip("Corrélation",WatcherService.running));
        boolean shizukuOk=false;
        try{JSONObject ss=ShizukuCleanup.state(this);shizukuOk=ss.optBoolean("binder")&&ss.optBoolean("authorized");}catch(Throwable ignored){}
        statusRow.addView(statusChip("Shizuku",shizukuOk));
        statusRow.addView(statusChip("VPN",NetworkCaptureService.running));
        JSONObject integrity=ScreenIntegrityService.state();
        statusRow.addView(statusChip("Affichage",integrity.optBoolean("connected")));
    }

    private TextView statusChip(String label,boolean active){
        TextView v=text((active?"● ":"○ ")+label,12,active?GREEN:MUTED,true);
        v.setPadding(dp(10),dp(5),dp(10),dp(5));
        v.setBackground(panelDrawable(0xff0a1a25,active?0xff2b7045:BORDER,999));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0,0,dp(7),0);v.setLayoutParams(lp);return v;
    }
    private int levelForPackage(String pkg){try{return DefenseStore.get(this).levelFor(pkg);}catch(Throwable t){return 0;}}
    private int levelColor(int level){return level==1?GREEN:level==2?YELLOW:level==3?ORANGE:level==4?RED:level==5?VIOLET:MUTED;}
    private String levelPrefix(int level){return level>=1&&level<=5?"A"+level+" · ":"? · ";}
    private LinearLayout levelCard(String title,String body,int level){
        LinearLayout box=card(title,body);box.setBackground(panelDrawable(PANEL_2,levelColor(level),18));return box;
    }
    private String uniquePackage(JSONObject d){
        if(d==null)return "";
        JSONArray a=d.optJSONArray("packages");
        return a!=null&&a.length()==1?a.optString(0):"";
    }
    private String shortTime(long ms){
        if(ms<=0)return "—";
        java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("dd-MM HH:mm:ss.SSS",Locale.CANADA_FRENCH);
        return f.format(new java.util.Date(ms));
    }
    private String formatBytes(long n){
        if(n<1024)return n+" o";
        if(n<1024L*1024L)return String.format(Locale.CANADA_FRENCH,"%.1f Kio",n/1024.0);
        return String.format(Locale.CANADA_FRENCH,"%.2f Mio",n/(1024.0*1024.0));
    }

    private TextView sectionTitle(String value){TextView v=text(value,28,TEXT,true);v.setPadding(dp(4),dp(6),dp(4),dp(10));return v;}
    private TextView note(String value){
        TextView v=text(value,14,MUTED,false);v.setPadding(dp(14),dp(12),dp(14),dp(12));v.setBackground(panelDrawable(PANEL,BORDER,16));LinearLayout.LayoutParams lp=blockParams();v.setLayoutParams(lp);return v;
    }
    private HorizontalScrollView tableScroller(TableLayout table){
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(true);
        scroll.setFillViewport(false);
        scroll.addView(table,new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0,dp(8),0,dp(10));
        scroll.setLayoutParams(lp);
        return scroll;
    }

    private TableLayout dataTable(String[] headers,int[] widthsDp){
        TableLayout table=new TableLayout(this);
        table.setStretchAllColumns(false);
        table.setShrinkAllColumns(false);
        table.setBackground(panelDrawable(PANEL,BORDER,12));
        table.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        table.setDividerDrawable(new ColorDrawable(BORDER));
        int min=0;for(int w:widthsDp)min+=w;
        table.setMinimumWidth(dp(min));
        TableRow header=new TableRow(this);
        header.setBackgroundColor(0xff0b2b40);
        for(int i=0;i<headers.length;i++)header.addView(tableCell(headers[i],widthsDp[i],true,TEXT));
        table.addView(header);
        return table;
    }

    private TextView tableCell(String value,int widthDp,boolean header,int color){
        TextView cell=text(value==null?"—":value,header?13:14,color,header);
        cell.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(10),dp(header?10:9),dp(10),dp(header?10:9));
        cell.setMaxLines(header?2:5);
        TableRow.LayoutParams lp=new TableRow.LayoutParams(dp(widthDp),ViewGroup.LayoutParams.WRAP_CONTENT);
        cell.setLayoutParams(lp);
        return cell;
    }

    private void addTableRow(TableLayout table,String[] values,String[] filters,int[] widthsDp,int level,EditText search,Runnable refresh,View.OnClickListener openListener){
        TableRow row=new TableRow(this);
        row.setBackgroundColor(((table.getChildCount()-1)&1)==0?PANEL_2:PANEL);
        for(int i=0;i<values.length;i++){
            int color=(i==0&&level>0)?levelColor(level):TEXT;
            TextView cell=tableCell(values[i],widthsDp[i],false,color);
            if(filters!=null&&i<filters.length&&filters[i]!=null&&search!=null&&refresh!=null)bindDoubleTapFilter(cell,search,filters[i],refresh);
            row.addView(cell);
        }
        Button open=button("Ouvrir");
        open.setTextSize(13);
        open.setTextColor(TEXT);
        open.setBackground(panelDrawable(0xff0a2437,0xff386782,10));
        open.setOnClickListener(openListener);
        int openWidth=widthsDp.length>values.length?widthsDp[values.length]:100;
        TableRow.LayoutParams op=new TableRow.LayoutParams(dp(openWidth),dp(42));
        op.setMargins(dp(5),dp(5),dp(5),dp(5));
        row.addView(open,op);
        table.addView(row);
    }

    private void bindDoubleTapFilter(TextView cell,EditText search,String filter,Runnable refresh){
        final long[] lastTap={0L};
        cell.setOnClickListener(v->{
            long now=android.os.SystemClock.elapsedRealtime();
            if(now-lastTap[0]<=450L){
                lastTap[0]=0L;
                search.setText(filter);
                search.setSelection(search.length());
                refresh.run();
            }else lastTap[0]=now;
        });
    }

    private String levelShort(int level){return level>=1&&level<=5?"A"+level:"—";}

    private void showTrackerGroupDetail(JSONObject group){
        try{
            String app=group.optString("app","—");
            String tracker=group.optString("tracker_name","—");
            String appKey=group.optString("app_key","");
            int trackerId=group.optInt("tracker_id",-1);
            JSONObject journeys=TrackerIndex.get(this).journeys(appKey,trackerId,0,30);

            LinearLayout body=new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(12),dp(8),dp(12),dp(12));
            body.addView(text(app+" → "+tracker,16,TEXT,true));
            body.addView(text(group.optLong("journeys")+" trajet(s) · "+group.optLong("destinations_count")+" destination(s)",13,MUTED,false));
            JSONObject apk=group.optJSONObject("apk");
            if(apk!=null)body.addView(text("Preuve APK : "+apk.optString("status","—")+" · tracker présent : "+String.valueOf(apk.opt("present")),13,MUTED,false));

            JSONArray rows=journeys.optJSONArray("rows");
            if(rows==null||rows.length()==0)body.addView(note("Aucun trajet détaillé disponible pour ce groupe."));
            else for(int i=0;i<rows.length();i++){
                JSONObject j=rows.optJSONObject(i);if(j==null)continue;
                String correlation=j.optString("correlation");
                String host=j.optString("host");if(host.isEmpty())host=j.optString("remote_ip","—");
                String label=shortTime(j.optLong("last_ms"))+" · "+host+" · "+j.optLong("steps")+" étape(s)";
                final JSONObject journey=j;
                body.addView(action(label,v->showTrackerTrail(app+" → "+tracker,journey)));
            }

            ScrollView scroll=new ScrollView(this);scroll.addView(body);
            new AlertDialog.Builder(this).setTitle("Traqueur · chaîne de trajets").setView(scroll).setPositiveButton("Fermer",null).show();
        }catch(Exception e){
            showDetail("Traqueur","Détail indisponible : "+e.getClass().getSimpleName()+" · "+String.valueOf(e.getMessage()),null,null);
        }
    }

    private void showTrackerTrail(String title,JSONObject journey){
        try{
            String correlation=journey.optString("correlation");
            JSONObject trail=TrackerIndex.get(this).trail(correlation);
            LinearLayout body=new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(12),dp(8),dp(12),dp(12));
            body.addView(text("Corrélation : "+correlation,13,MUTED,false));
            body.addView(text("UID "+journey.optInt("uid",-1)+" · ↑ "+formatBytes(journey.optLong("tx_bytes"))+" · ↓ "+formatBytes(journey.optLong("rx_bytes")),13,MUTED,false));

            JSONArray steps=trail.optJSONArray("steps");
            if(steps==null||steps.length()==0)body.addView(note("Aucune étape conservée."));
            else for(int i=0;i<steps.length();i++){
                JSONObject step=steps.optJSONObject(i);if(step==null)continue;
                long eventId=step.optLong("event_id");
                String label="#"+eventId+" · "+shortTime(step.optLong("observed_ms"))+" · "+step.optString("action","—");
                String dest=step.optString("tls_sni");if(dest.isEmpty())dest=step.optString("destination",step.optString("remote_ip","—"));
                LinearLayout item=card(label,dest+"\n↑ "+formatBytes(step.optLong("tx_bytes"))+" · ↓ "+formatBytes(step.optLong("rx_bytes")));
                item.setOnClickListener(v->showEventPedigreeById(eventId));
                body.addView(item);
            }
            if(trail.optBoolean("truncated"))body.addView(note("Chaîne tronquée à 250 étapes dans cette vue."));

            ScrollView scroll=new ScrollView(this);scroll.addView(body);
            new AlertDialog.Builder(this).setTitle(title+" · trajet").setView(scroll).setPositiveButton("Fermer",null).show();
        }catch(Exception e){
            showDetail("Trajet","Détail indisponible : "+e.getClass().getSimpleName()+" · "+String.valueOf(e.getMessage()),null,null);
        }
    }

    private void showAnomalyDetail(JSONObject anomaly,String fallbackPkg){
        try{
            LinearLayout body=new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(12),dp(8),dp(12),dp(12));
            body.addView(text(anomaly.optString("title","Anomalie"),16,TEXT,true));
            body.addView(text(anomaly.optString("explanation",""),13,MUTED,false));
            body.addView(text("Occurrences : "+anomaly.optLong("occurrences",1)+" · "+anomaly.optString("severity","—"),13,MUTED,false));

            long id=anomaly.optLong("id",-1);
            if(id>0){
                JSONObject ev=AnomalyMonitor.get(this).evidence(id);
                JSONArray events=ev.optJSONArray("events");
                if(events!=null&&events.length()>0){
                    body.addView(text("Événements reliés",15,TEXT,true));
                    for(int i=0;i<events.length();i++){
                        JSONObject e=events.optJSONObject(i);if(e==null)continue;
                        long eventId=e.optLong("id",-1);
                        String label="#"+eventId+" · "+e.optString("app","—")+" · "+e.optString("action","—");
                        final JSONObject event=e;
                        String pkg=uniquePackage(e.optJSONObject("details"));
                        final String eventPkg=pkg.isEmpty()?fallbackPkg:pkg;
                        body.addView(action(label,v->showEventPedigree("Événement #"+eventId,event,eventPkg)));
                    }
                }else body.addView(note("Aucun événement source disponible."));
            }

            ScrollView scroll=new ScrollView(this);scroll.addView(body);
            new AlertDialog.Builder(this).setTitle("Anomalie · détail").setView(scroll).setPositiveButton("Fermer",null).show();
        }catch(Exception e){
            showJsonDetail("Anomalie",anomaly,fallbackPkg);
        }
    }

    private void showEventPedigreeById(long eventId){
        try{
            JSONArray ids=new JSONArray().put(eventId);
            JSONArray events=EventStore.get(this).evidence(ids);
            if(events.length()==0){showDetail("Événement #"+eventId,"Événement non disponible dans le journal local.",null,null);return;}
            JSONObject e=events.getJSONObject(0);
            showEventPedigree("Événement #"+eventId,e,uniquePackage(e.optJSONObject("details")));
        }catch(Exception e){
            showDetail("Événement #"+eventId,"Lecture impossible : "+e.getClass().getSimpleName(),null,null);
        }
    }

    private void showEventPedigree(String title,JSONObject event,String fallbackPkg){
        StringBuilder body=new StringBuilder();
        try{body.append("ÉVÉNEMENT\n").append(event.toString(2));}
        catch(Exception e){body.append("ÉVÉNEMENT\n").append(String.valueOf(event));}

        long eventId=event.optLong("id",-1);
        if(eventId>0){
            try{
                JSONObject chain=AivStore.detail(this,eventId);
                body.append("\n\nCHAÎNE AIV / DÉCISION\n").append(chain.toString(2));
            }catch(Exception e){
                body.append("\n\nCHAÎNE AIV\nIndisponible : ").append(e.getClass().getSimpleName());
            }
        }

        String pkg=uniquePackage(event.optJSONObject("details"));
        if(pkg.isEmpty())pkg=fallbackPkg==null?"":fallbackPkg;
        if(!pkg.isEmpty()){
            try{
                JSONObject identity=AppIdentity.forPackage(this,pkg);
                body.append("\n\nIDENTITÉ CRYPTOGRAPHIQUE DE L’APPLICATION\n").append(identity.toString(2));
                try{
                    JSONObject dossier=DefenseStore.get(this).detail(pkg,0);
                    body.append("\n\nPÉDIGRÉE LOCAL / HISTORIQUE\n").append(dossier.toString(2));
                }catch(Exception ignored){}
            }catch(Exception e){
                body.append("\n\nIDENTITÉ CRYPTOGRAPHIQUE\nIndisponible pour ").append(pkg).append(" : ").append(e.getClass().getSimpleName());
            }
        }

        showLargeTextDetail(title,body.toString(),pkg);
    }

    private void showLargeTextDetail(String title,String body,String pkg){
        TextView content=text(body==null?"—":body,12,TEXT,false);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        content.setPadding(dp(14),dp(10),dp(14),dp(18));
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Fermer",null);
        if(pkg!=null&&!pkg.isEmpty())dialog.setNeutralButton("Réglages Android",(d,w)->openAppSettings(pkg));
        dialog.show();
    }

    private void showJsonDetail(String title,JSONObject data,String pkg){
        String body;
        try{body=data==null?"Aucun détail disponible.":data.toString(2);}
        catch(Exception e){body=String.valueOf(data);}
        if(pkg!=null&&!pkg.isEmpty())showDetail(title,body,"Réglages Android",()->openAppSettings(pkg));
        else showDetail(title,body,null,null);
    }

    private void showDetail(String title,String body,String secondaryLabel,Runnable secondary){
        AlertDialog.Builder dialog=new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body==null?"—":body)
            .setPositiveButton("Fermer",null);
        if(secondary!=null&&secondaryLabel!=null)dialog.setNeutralButton(secondaryLabel,(d,w)->secondary.run());
        dialog.show();
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
