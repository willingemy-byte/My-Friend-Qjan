package fr.erick.journallocal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.*;
import android.widget.Toast;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private WebView reader;
    private static final int NOTIFICATION_REQUEST=10,BLUETOOTH_REQUEST=11,EXPORT_REQUEST=12,VPN_REQUEST=13,ANALYSIS_EXPORT_REQUEST=14,LINES_EXPORT_REQUEST=15,RECOVER_IMPORT_REQUEST=16,RECOVER_EXPORT_REQUEST=17,DIAGNOSTIC_IMPORT_REQUEST=18,CORRELATION_EXPORT_REQUEST=19,LAST_EXPORT_REQUEST=20;
    private static volatile String fileStatus="";
    private static final int AUDIT_EXPORT_REQUEST=21,AUDIT_IMPORT_REQUEST=22,AIV_EXPORT_REQUEST=23,AIV_REFERENCE_REQUEST=24,PENALTY_EXPORT_REQUEST=25,PENALTY_IMPORT_REQUEST=26,REFERENCE_EXPORT_REQUEST=27,AUDIT_FULL_EXPORT_REQUEST=28;
    private volatile String penaltyImport="";
    @Override public void onCreate(Bundle state){
        super.onCreate(state);Continuous.initialize(this);
        prepareStartup();
        reader=new WebView(this);reader.setBackgroundColor(0xFF05090D);
        reader.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);}
            else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(reader);reader.requestApplyInsets();
        WebSettings settings=reader.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setBlockNetworkLoads(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);
        WebView.setWebContentsDebuggingEnabled(false);
        reader.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
        });
        reader.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest request){request.deny();}});
        reader.addJavascriptInterface(new Bridge(),"JournalAndroid");
        try(InputStream input=getAssets().open("journal.html")){
            ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)data.write(buffer,0,count);
            reader.loadDataWithBaseURL("https://journal.local.invalid/",new String(data.toByteArray(),StandardCharsets.UTF_8),"text/html","UTF-8",null);
        }catch(Exception e){new AlertDialog.Builder(this).setTitle("Lecteur indisponible").setMessage(e.getClass().getSimpleName()).setPositiveButton("Fermer",(d,w)->finish()).show();}
    }
    private volatile String startupState="Calcul en cours", startupResult="", startupError="";
    private void prepareStartup(){
        new Thread(()->{
            try{
                PermissionAudit audit=PermissionAudit.get(this);
                startupState="Inventaire des applications et permissions";
                audit.scan();
                long until=android.os.SystemClock.elapsedRealtime()+600000;
                while(audit.summary().optBoolean("busy") && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(150);
                if(audit.summary().optBoolean("busy"))throw new IOException("Inventaire toujours en cours. Réessayer dans un instant.");
                startupState="Préparation de la référence";
                startupResult=audit.penaltyData().toString();
                startupState="Prêt";
                AnomalyMonitor.request(this);
            }catch(Exception e){startupError=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();startupState="Erreur";}
        },"aiv-initialisation").start();
    }
    private final java.util.concurrent.ExecutorService journalReads=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger journalRequestId=new java.util.concurrent.atomic.AtomicInteger();
    private volatile String journalReply="";
    private volatile int journalReplyId=0;
    public final class Bridge {
        @JavascriptInterface public int requestJournal(String args){
            int ticket=journalRequestId.incrementAndGet();
            journalReads.execute(()->{if(ticket!=journalRequestId.get())return;String result;
                try{org.json.JSONArray a=new org.json.JSONArray(args);result=EventStore.get(MainActivity.this).pageSegment(a.getString(0),a.getString(1),a.getInt(2),a.getInt(3),a.getLong(4),a.getString(5),a.getString(6),a.getBoolean(7),a.getString(8),a.getString(9),a.optInt(10,0)).toString();}
                catch(Exception e){result=auditError(e);}
                if(ticket==journalRequestId.get()){journalReply=result;journalReplyId=ticket;}
            });return ticket;
        }
        @JavascriptInterface public String journalResult(int ticket){return ticket==journalReplyId?journalReply:ticket<journalRequestId.get()?"{\"cancelled\":true}":"";}

        @JavascriptInterface public long journalHead(){return EventStore.get(MainActivity.this).latestId();}
        @JavascriptInterface public String continuousStatus(){return EventStore.object("enabled",Continuous.enabled(MainActivity.this),"collector",RecorderService.running,"vpn",NetworkCaptureService.running,"analysis",WatcherService.analysisActive,"vpn_error",NetworkCaptureService.lastError,"error",EventStore.lastError).toString();}
        @JavascriptInterface public String startupStatus(){return EventStore.object("state",startupState,"ready",!startupResult.isEmpty(),"error",startupError).toString();}
        @JavascriptInterface public void startupRetry(){if("Erreur".equals(startupState)){startupError="";startupState="Calcul en cours";prepareStartup();}}
        @JavascriptInterface public String startupData(){return startupResult;}

        @JavascriptInterface public String aivSummary(){try{return AivStore.summary(MainActivity.this).put("file_status",fileStatus).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String aivPage(String filter,long before){try{return AivStore.page(MainActivity.this,filter,before).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String aivDetail(long eventId){try{return AivStore.detail(MainActivity.this,eventId).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public void aivConfigure(String value){runOnUiThread(()->new AlertDialog.Builder(MainActivity.this).setTitle("Enregistrer cette règle AIV ?").setMessage(value.length()>32768?"Configuration trop longue":value).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,w)->new Thread(()->{try{MainEngine.configure(MainActivity.this,value);}catch(Exception e){AivStore.error="Règle refusée : "+e.getMessage();}},"aiv-policy").start()).show());}

        @JavascriptInterface public String coherenceRefresh(){try{return CoherenceRefresh.trigger(MainActivity.this).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String coherenceStatus(){try{return CoherenceRefresh.status(MainActivity.this).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public void openAuditedApp(String pkg,boolean settings){runOnUiThread(()->{
            try{
                getPackageManager().getApplicationInfo(pkg,0);
                Intent intent=settings?new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+pkg)):getPackageManager().getLaunchIntentForPackage(pkg);
                if(intent==null)throw new IllegalArgumentException("Cette application ne possède pas d’écran de lancement.");
                startActivity(intent);
            }catch(Exception e){Toast.makeText(MainActivity.this,e.getMessage()==null?"Ouverture indisponible":e.getMessage(),Toast.LENGTH_LONG).show();}
        });}
        @JavascriptInterface public String coherenceDetail(String pkg){try{return CoherenceRefresh.detail(MainActivity.this,pkg).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String coherenceSummary(){try{return CoherenceRefresh.summary(MainActivity.this).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String coherenceConfigureEndpoint(String value){try{return CoherenceRefresh.configureEndpoint(MainActivity.this,value).toString();}catch(Exception e){return auditError(e);}}

        @JavascriptInterface public String penaltyData(){try{return PermissionAudit.get(MainActivity.this).penaltyData().toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String calculationLoad(String identity){try{return CalculationSnapshot.load(MainActivity.this,identity).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String calculationSave(String identity,String result){try{return CalculationSnapshot.save(MainActivity.this,identity,result).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String penaltyConfig(){try{return PermissionAudit.get(MainActivity.this).penaltyConfig().toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String penaltySave(String value){try{return PermissionAudit.get(MainActivity.this).savePenaltyConfig(value).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String penaltyImported(){String value=penaltyImport;penaltyImport="";return value;}
        @JavascriptInterface public String auditContext(String pkg){try{return PermissionAudit.get(MainActivity.this).colorContext(pkg).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String auditSummary(){try{return PermissionAudit.get(MainActivity.this).summary().put("file_status",fileStatus).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String auditPage(String query,String scope,int offset){try{return PermissionAudit.get(MainActivity.this).page(query,scope,offset).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String auditDetail(String pkg){try{return PermissionAudit.get(MainActivity.this).detail(pkg).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String auditSave(String pkg,String value){try{return PermissionAudit.get(MainActivity.this).save(pkg,value).toString();}catch(Exception e){return auditError(e);}}
        @JavascriptInterface public String pageFiltered(String query,String transport,int offset,int limit,long ceiling,String actor,String kind,boolean quiet,String scope,String pkg){try{if(query!=null&&query.length()>4096)throw new IllegalArgumentException("Recherche trop longue");return EventStore.get(MainActivity.this).pageFiltered(query,transport,offset,limit,ceiling,actor,kind,quiet,scope,pkg).toString();}catch(Exception e){return auditError(e);}}
        private String auditError(Exception e){return EventStore.object("error",e instanceof IllegalArgumentException?e.getMessage():"Audit indisponible : "+e.getClass().getSimpleName()).toString();}

        @JavascriptInterface public String status(){
            boolean bluetoothAllowed=Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
            return EventStore.object("running",RecorderService.running,"network",RecorderService.networkRegistered,"bluetooth",RecorderService.bluetoothRegistered,"bluetooth_enabled",getSharedPreferences("journal",MODE_PRIVATE).getBoolean("bluetooth",false),"bluetooth_permission",bluetoothAllowed,"last_alive_ms",RecorderService.lastAlive,"error",EventStore.lastError,"live_to_chatgpt",false,"capture",NetworkCaptureService.running,"capture_starting",NetworkCaptureService.starting,"capture_state",NetworkCaptureService.stateText,"capture_error",NetworkCaptureService.lastError).toString();
        }
        @JavascriptInterface public String page(String query,String transport,int offset,int limit,long ceiling,String actor,String kind,boolean quiet){
            try{if(query!=null&&query.length()>4096)throw new IllegalArgumentException("Recherche trop longue");return EventStore.get(MainActivity.this).page(query,transport,offset,limit,ceiling,actor,kind,quiet).toString();}
            catch(Exception e){return EventStore.object("error","Lecture impossible : "+e.getClass().getSimpleName()).toString();}
        }
        @JavascriptInterface public String flowPage(String query,long beforeId,int limit){try{if(query!=null&&query.length()>512)throw new IllegalArgumentException("Recherche de flux trop longue");return EventStore.get(MainActivity.this).flowPage(query,beforeId,limit).toString();}catch(Exception e){return EventStore.object("error","Flux indisponibles : "+e.getClass().getSimpleName()).toString();}}
        @JavascriptInterface public String diagnosticSummary(){try{return DiagnosticStore.get(MainActivity.this).summary().put("file_status",fileStatus).put("recovery",getSharedPreferences("files",0).getString("recovery_report","")).toString();}catch(Exception e){return EventStore.object("error",e.getMessage()).toString();}}
        @JavascriptInterface public String diagnosticConfigure(String value){try{return DiagnosticStore.get(MainActivity.this).configure(value).toString();}catch(Exception e){return EventStore.object("error",e.getMessage()).toString();}}
        @JavascriptInterface public String correlate(long eventId){try{
            org.json.JSONArray ids=new org.json.JSONArray();ids.put(eventId);org.json.JSONArray events=EventStore.get(MainActivity.this).evidence(ids);if(events.length()!=1)throw new IllegalArgumentException("Événement introuvable");
            org.json.JSONObject event=events.getJSONObject(0),result=DiagnosticStore.get(MainActivity.this).compare(event);getSharedPreferences("files",0).edit().putString("cross_analysis",EventStore.object("schema","journal-cross-analysis/1","source_event",event,"cross_analysis",result).toString()).apply();return result.toString();
        }catch(Exception e){return EventStore.object("error",e.getMessage()).toString();}}
        @JavascriptInterface public void command(String command){runOnUiThread(()->handleCommand(command));}
        @JavascriptInterface public String analysisSummary(){try{return AnomalyMonitor.get(MainActivity.this).summary().toString();}catch(Exception e){return EventStore.object("error","Lecture de l’analyse impossible : "+e.getClass().getSimpleName()).toString();}}
        @JavascriptInterface public String analysisPage(String kind,boolean unread,int offset){try{return AnomalyMonitor.get(MainActivity.this).page(kind,unread,offset).toString();}catch(Exception e){return EventStore.object("error","Lecture des signalements impossible : "+e.getClass().getSimpleName()).toString();}}
        @JavascriptInterface public String analysisEvidence(long id){try{return AnomalyMonitor.get(MainActivity.this).evidence(id).toString();}catch(Exception e){return EventStore.object("error","Événements sources indisponibles : "+e.getClass().getSimpleName()).toString();}}
        @JavascriptInterface public String analysisChange(String action,String value){try{return AnomalyMonitor.get(MainActivity.this).change(action,value).toString();}catch(Exception e){return EventStore.object("error",e instanceof IllegalArgumentException?e.getMessage():"Action d’analyse impossible : "+e.getClass().getSimpleName()).toString();}}
    }
    private void handleCommand(String command){
        try{
            if("aiv-start".equals(command)){Continuous.prefs(this).edit().putBoolean("analysis_enabled",true).apply();WatcherService.start(this);
            }else if("aiv-stop".equals(command)){Continuous.prefs(this).edit().putBoolean("analysis_enabled",false).apply();WatcherService.stop(this);
            }else if("aiv-verify".equals(command)){WatcherService.verify(this);
            }else if("aiv-export".equals(command)){chooseExport(AIV_EXPORT_REQUEST,"journal-aiv-signe.jsonl","application/octet-stream");
            }else if("aiv-reference".equals(command)){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"),AIV_REFERENCE_REQUEST);
            }else if("audit-scan".equals(command)){PermissionAudit.get(this).scan();
            }else if("export-penalty".equals(command)){chooseExport(PENALTY_EXPORT_REQUEST,"aiv-bareme-observations.json","application/json");
            }else if("import-penalty".equals(command)){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"),PENALTY_IMPORT_REQUEST);
            }else if("export-reference".equals(command)){chooseExport(REFERENCE_EXPORT_REQUEST,"aiv-reference-actuel.json","application/json");
            }else if("export-audit-full".equals(command)){chooseExport(AUDIT_FULL_EXPORT_REQUEST,"journal-autorisations-historique.json","application/json");
            }else if("export-audit".equals(command)){chooseExport(AUDIT_EXPORT_REQUEST,"journal-autorisations.json","application/json");
            }else if("import-audit".equals(command)){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"),AUDIT_IMPORT_REQUEST);
            }else if("start".equals(command)){
                Continuous.prefs(this).edit().putBoolean("enabled",true).apply();Continuous.start(this);EventStore.lastError="";
                if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_REQUEST);
                else startCollector();
            }else if("stop".equals(command)){
                Continuous.stop(this);
            }else if("capture-on".equals(command)){
                Continuous.prefs(this).edit().putBoolean("enabled",true).putBoolean("vpn_enabled",true).apply();Continuous.start(this);Intent consent=android.net.VpnService.prepare(this);
                if(consent!=null)startActivityForResult(consent,VPN_REQUEST);else startNetworkCapture();
            }else if("capture-off".equals(command)){Continuous.prefs(this).edit().putBoolean("vpn_enabled",false).apply();stopService(new Intent(this,NetworkCaptureService.class));
            }else if("recover-file".equals(command)||"import-diagnostic".equals(command)){
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");startActivityForResult(intent,"recover-file".equals(command)?RECOVER_IMPORT_REQUEST:DIAGNOSTIC_IMPORT_REQUEST);
            }else if("export-lines".equals(command)){chooseExport(LINES_EXPORT_REQUEST,"journal-cellulaire.jsonl","application/octet-stream");
            }else if("save-recovered".equals(command)){if(savedFile("recovered")==null)throw new IOException("Récupère d’abord un export");chooseExport(RECOVER_EXPORT_REQUEST,"journal-recupere.json","application/json");
            }else if("save-last-export".equals(command)){if(savedFile("last_export")==null)throw new IOException("Aucun instantané complet disponible");String ext=getSharedPreferences("files",0).getString("last_extension","json");chooseExport(LAST_EXPORT_REQUEST,"journal-copie."+ext,"application/octet-stream");
            }else if("export-correlation".equals(command)){if(getSharedPreferences("files",0).getString("cross_analysis","").isEmpty())throw new IOException("Ouvre un flux et lance une comparaison");chooseExport(CORRELATION_EXPORT_REQUEST,"journal-correlation.json","application/json");
            }else if("licenses".equals(command)){
                try(InputStream input=getAssets().open("licenses.txt")){
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                    while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);
                    new AlertDialog.Builder(this).setTitle("Sources et licences").setMessage(new String(bytes.toByteArray(),StandardCharsets.UTF_8)).setPositiveButton("Fermer",null).show();
                }
            }else if("bluetooth-on".equals(command)){
                if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},BLUETOOTH_REQUEST);
                else setBluetooth(true);
            }else if("bluetooth-off".equals(command)){setBluetooth(false);}
            else if("export".equals(command)){
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"journal-cellulaire.json");startActivityForResult(intent,EXPORT_REQUEST);
            }else if("export-analysis".equals(command)){
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"journal-analyse.json");startActivityForResult(intent,ANALYSIS_EXPORT_REQUEST);
            }
        }catch(Exception e){EventStore.lastError="Action impossible : "+e.getClass().getSimpleName();Toast.makeText(this,EventStore.lastError,Toast.LENGTH_LONG).show();}
    }
    private void startNetworkCapture(){NetworkCaptureService.lastError="";startForegroundService(new Intent(this,NetworkCaptureService.class));}
    private void startCollector(){startForegroundService(new Intent(this,RecorderService.class));}
    private void setBluetooth(boolean enabled){
        getSharedPreferences("journal",MODE_PRIVATE).edit().putBoolean("bluetooth",enabled).apply();
        EventStore.get(this).add("collecteur","Utilisateur",enabled?"Suivi Bluetooth demandé":"Suivi Bluetooth désactivé","Configuration du collecteur","Interne","Commande locale explicite",EventStore.object("enabled",enabled));
        if(RecorderService.running)startService(new Intent(this,RecorderService.class).setAction(RecorderService.REFRESH));
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==NOTIFICATION_REQUEST){try{startCollector();}catch(Exception e){EventStore.lastError="Démarrage impossible : "+e.getClass().getSimpleName();}}
        if(request==BLUETOOTH_REQUEST){boolean granted=grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED;setBluetooth(granted);if(!granted)Toast.makeText(this,"Suivi Bluetooth non activé.",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==VPN_REQUEST){
            if(result==RESULT_OK){try{startNetworkCapture();}catch(Exception e){NetworkCaptureService.lastError="Démarrage impossible : "+e.getClass().getSimpleName();}}
            else NetworkCaptureService.lastError="Capture non activée : autorisation VPN non accordée.";
            return;
        }
        if(result!=RESULT_OK||data==null||data.getData()==null)return;final Uri uri=data.getData();
        if(request==AIV_REFERENCE_REQUEST){new Thread(()->{try{fileStatus=ReferenceSync.importSnapshot(this,uri).toString();}catch(Exception e){fileStatus="Import refusé : "+e.getMessage();}},"aiv-reference").start();return;}
        if(request==AIV_EXPORT_REQUEST){new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);try{File ready=ExportSigner.prepare(this);getSharedPreferences("files",0).edit().putString("last_export",ready.getAbsolutePath()).putString("last_extension","jsonl").apply();fileStatus=ExportFiles.copy(this,ready,uri);}catch(Exception e){fileStatus="Export AIV interrompu : "+e.getMessage();}runOnUiThread(()->Toast.makeText(this,fileStatus,Toast.LENGTH_LONG).show());},"aiv-export").start();return;}
        if(request==PENALTY_IMPORT_REQUEST){
            new Thread(()->{try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                if(in==null)throw new IOException("Fichier inaccessible");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>4*1024*1024)throw new IOException("Réglages limités à 4 Mio");out.write(b,0,n);}
                penaltyImport=new String(out.toByteArray(),StandardCharsets.UTF_8);fileStatus="Import prêt à vérifier dans Barème AIV";
            }catch(Exception e){fileStatus="Import interrompu : "+e.getMessage();}runOnUiThread(()->Toast.makeText(this,fileStatus,Toast.LENGTH_LONG).show());},"penalty-import").start();return;
        }
        if(request==AUDIT_IMPORT_REQUEST){
            new Thread(()->{try{fileStatus=PermissionAudit.get(this).importReport(uri).optString("message");}catch(Exception e){fileStatus="Import interrompu : "+e.getMessage();}runOnUiThread(()->Toast.makeText(this,fileStatus,Toast.LENGTH_LONG).show());},"journal-audit-import").start();return;
        }
        if(request==RECOVER_IMPORT_REQUEST||request==DIAGNOSTIC_IMPORT_REQUEST){
            fileStatus=request==RECOVER_IMPORT_REQUEST?"Récupération en cours…":"Lecture du diagnostic…";
            new Thread(()->{
                try{
                    if(request==DIAGNOSTIC_IMPORT_REQUEST){DiagnosticStore.get(this).ingest(uri);fileStatus=DiagnosticStore.activity;}
                    else{org.json.JSONObject[] report={null};File ready=ExportFiles.recover(this,uri,report);getSharedPreferences("files",0).edit().putString("recovered",ready.getAbsolutePath()).putString("recovery_report",report[0].toString()).apply();fileStatus=report[0].optLong("recovered_events")+" événements récupérés. Original conservé; copie séparée.";runOnUiThread(()->chooseExport(RECOVER_EXPORT_REQUEST,"journal-recupere.json","application/json"));}
                    runOnUiThread(()->Toast.makeText(this,fileStatus,Toast.LENGTH_LONG).show());
                }catch(Exception e){fileStatus="Lecture interrompue : "+e.getMessage();runOnUiThread(()->Toast.makeText(this,fileStatus,Toast.LENGTH_LONG).show());}
            },"journal-import").start();return;
        }
        if(request==REFERENCE_EXPORT_REQUEST||request==AUDIT_FULL_EXPORT_REQUEST||request==PENALTY_EXPORT_REQUEST||request==AUDIT_EXPORT_REQUEST||request==EXPORT_REQUEST||request==ANALYSIS_EXPORT_REQUEST||request==LINES_EXPORT_REQUEST||request==RECOVER_EXPORT_REQUEST||request==CORRELATION_EXPORT_REQUEST||request==LAST_EXPORT_REQUEST){
            fileStatus="Préparation d’un instantané complet…";
            new Thread(()->{try{
                File ready;if(request==LAST_EXPORT_REQUEST)ready=savedFile("last_export");else if(request==RECOVER_EXPORT_REQUEST)ready=savedFile("recovered");
                else ready=ExportFiles.stage(this,writer->{if(request==REFERENCE_EXPORT_REQUEST)PermissionAudit.get(this).exportReference(writer);else if(request==AUDIT_FULL_EXPORT_REQUEST)PermissionAudit.get(this).export(writer,true);else if(request==PENALTY_EXPORT_REQUEST)writer.write(PermissionAudit.get(this).penaltyConfig().toString(2));else if(request==AUDIT_EXPORT_REQUEST)PermissionAudit.get(this).export(writer);else if(request==ANALYSIS_EXPORT_REQUEST)AnomalyMonitor.get(this).export(writer);else if(request==CORRELATION_EXPORT_REQUEST){String cross=getSharedPreferences("files",0).getString("cross_analysis","");if(cross.isEmpty())throw new IOException("Comparaison indisponible");writer.write(cross);}else EventStore.get(this).export(writer,request==LINES_EXPORT_REQUEST);});
                if(ready==null)throw new IOException("Instantané expiré; recommencer la préparation");
                if(request!=LAST_EXPORT_REQUEST)getSharedPreferences("files",0).edit().putString("last_export",ready.getAbsolutePath()).putString("last_extension",request==LINES_EXPORT_REQUEST?"jsonl":"json").apply();
                fileStatus=ExportFiles.copy(this,ready,uri);runOnUiThread(()->Toast.makeText(this,fileStatus,Toast.LENGTH_LONG).show());
            }catch(Exception e){fileStatus="Export incomplet : "+e.getMessage()+". Si disponible, réenregistre le dernier instantané depuis Diagnostic.";runOnUiThread(()->Toast.makeText(this,fileStatus,Toast.LENGTH_LONG).show());}},"journal-export").start();
        }
    }
    private void chooseExport(int request,String name,String mime){try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,name),request);}catch(Exception e){fileStatus="Sélection du fichier impossible : "+e.getClass().getSimpleName();Toast.makeText(this,fileStatus,Toast.LENGTH_LONG).show();}}
    private File savedFile(String key)throws IOException{String value=getSharedPreferences("files",0).getString(key,"");if(value.isEmpty())return null;File f=new File(value);File dir=new File(getCacheDir(),"exports");if(!f.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator)||!f.isFile())return null;return f;}
    private boolean launchRulesApplied;
    @Override protected void onResume(){super.onResume();if(reader!=null)reader.onResume();if(!launchRulesApplied){launchRulesApplied=true;Continuous.start(this);if(Continuous.enabled(this)&&Continuous.prefs(this).getBoolean("vpn_enabled",true)&&!Continuous.prefs(this).getBoolean("vpn_prompted",false)){Intent consent=android.net.VpnService.prepare(this);if(consent!=null){Continuous.prefs(this).edit().putBoolean("vpn_prompted",true).apply();startActivityForResult(consent,VPN_REQUEST);}}}}

    @Override protected void onPause(){if(reader!=null)reader.onPause();super.onPause();}
    @Override protected void onDestroy(){journalReads.shutdownNow();if(reader!=null){reader.removeJavascriptInterface("JournalAndroid");reader.destroy();}super.onDestroy();}
}