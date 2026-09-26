package fr.erick.journallocal;

import android.app.*;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.provider.Settings;
import android.telecom.TelecomManager;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Durable local review queue and before/after evidence. Never removes an app itself. */
public final class DefenseStore extends SQLiteOpenHelper {
    private static DefenseStore instance;
    private final Context context;
    private volatile String error = "";
    public static synchronized DefenseStore get(Context c) {
        if (instance == null) instance = new DefenseStore(c.getApplicationContext());
        return instance;
    }
    private DefenseStore(Context c) { super(c, "defense.sqlite", null, 1); context = c; setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE apps(pkg TEXT PRIMARY KEY,label TEXT NOT NULL,level INTEGER NOT NULL,peak_level INTEGER NOT NULL,state TEXT NOT NULL,scan_id INTEGER NOT NULL,stamp TEXT NOT NULL,kept_stamp TEXT NOT NULL DEFAULT '',payload TEXT NOT NULL,assessment TEXT NOT NULL,last_seen_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE journal(id INTEGER PRIMARY KEY AUTOINCREMENT,pkg TEXT NOT NULL,at_ms INTEGER NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX defense_journal_package ON journal(pkg,id)");
        db.execSQL("CREATE TABLE meta(id INTEGER PRIMARY KEY CHECK(id=1),scan_id INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int old, int next) {}
    void failed(Exception e) { error = "Ménage en attente : " + e.getClass().getSimpleName(); }
    private long latestScan() { try(Cursor c=getReadableDatabase().rawQuery("SELECT scan_id FROM meta WHERE id=1",null)){return c.moveToFirst()?c.getLong(0):0;} }
    private JSONObject row(String pkg) throws Exception {
        try (Cursor c=getReadableDatabase().rawQuery("SELECT * FROM apps WHERE pkg=?",new String[]{pkg})) { return c.moveToFirst()?row(c):null; }
    }
    private JSONObject row(Cursor c) throws Exception {
        return EventStore.object("package_name",c.getString(c.getColumnIndexOrThrow("pkg")),"label",c.getString(c.getColumnIndexOrThrow("label")),
            "level",c.getInt(c.getColumnIndexOrThrow("level")),"peak_level",c.getInt(c.getColumnIndexOrThrow("peak_level")),"state",c.getString(c.getColumnIndexOrThrow("state")),
            "stamp",c.getString(c.getColumnIndexOrThrow("stamp")),"kept_stamp",c.getString(c.getColumnIndexOrThrow("kept_stamp")),
            "snapshot",new JSONObject(c.getString(c.getColumnIndexOrThrow("payload"))),"assessment",new JSONObject(c.getString(c.getColumnIndexOrThrow("assessment"))),
            "last_seen_ms",c.getLong(c.getColumnIndexOrThrow("last_seen_ms")));
    }
    private static Map<String,String> grants(JSONObject a) {
        Map<String,String> out=new TreeMap<>(); JSONArray ps=a.optJSONArray("permissions");
        if(ps!=null)for(int i=0;i<ps.length();i++){JSONObject p=ps.optJSONObject(i);if(p!=null)out.put(p.optString("name"),Boolean.TRUE.equals(p.opt("granted"))?"granted":Boolean.FALSE.equals(p.opt("granted"))?"denied":"unknown");}
        return out;
    }
    static String stamp(JSONObject a) throws Exception {
        TreeMap<String,JSONArray> permissions=new TreeMap<>();JSONArray ps=a.optJSONArray("permissions");
        if(ps!=null)for(int i=0;i<ps.length();i++){JSONObject p=ps.getJSONObject(i),b=p.optJSONObject("bayton");permissions.put(p.getString("name"),new JSONArray().put(p.getString("name")).put(p.opt("granted")).put(p.optInt("protection_level",-1)).put(p.optString("description")).put(b==null?"":b.optString("description")));}
        JSONArray ordered=new JSONArray();for(JSONArray p:permissions.values())ordered.put(p);
        JSONObject cert=a.optJSONObject("certificates"),source=a.optJSONObject("install_source");JSONArray origin=new JSONArray();
        for(String key:Arrays.asList("installing_package","initiating_package","originating_package","update_owner_package"))origin.put(source==null?JSONObject.NULL:source.opt(key));
        JSONArray value=new JSONArray().put(a.optString("package_name")).put(a.optLong("version_code")).put(a.optLong("last_update_ms")).put(a.optLong("first_install_ms"))
            .put(a.optInt("uid",-1)).put(a.optBoolean("enabled")).put(a.optBoolean("system_app")).put(sortedStrings(a.optJSONArray("uid_packages")))
            .put(sortedStrings(cert==null?null:cert.optJSONArray("current_sha256"))).put(sortedStrings(cert==null?null:cert.optJSONArray("history_sha256"))).put(origin).put(ordered);
        return ApkEvidence.hex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    }
    private static JSONArray sortedStrings(JSONArray input)throws Exception{TreeSet<String> values=new TreeSet<>();if(input!=null)for(int i=0;i<input.length();i++)values.add(input.getString(i));return new JSONArray(values);}
    private Set<String> protectedPackages() {
        Set<String> result=new HashSet<>();result.add(context.getPackageName());result.add("android");
        PackageManager pm=context.getPackageManager();
        try { ResolveInfo r=pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);if(r!=null&&r.activityInfo!=null)result.add(r.activityInfo.packageName); }
        catch(Exception e){result.add("*");}
        try { String value=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);if(value!=null){ComponentName n=ComponentName.unflattenFromString(value);if(n!=null)result.add(n.getPackageName());} }
        catch(Exception e){result.add("*");}
        try { TelecomManager tm=context.getSystemService(TelecomManager.class);if(tm!=null){String p=tm.getDefaultDialerPackage();if(p!=null)result.add(p);}String sms=android.provider.Telephony.Sms.getDefaultSmsPackage(context);if(sms!=null)result.add(sms); }
        catch(Exception e){result.add("*");}
        try { DevicePolicyManager dm=context.getSystemService(DevicePolicyManager.class);List<ComponentName> admins=dm==null?null:dm.getActiveAdmins();if(admins!=null)for(ComponentName n:admins)result.add(n.getPackageName()); }
        catch(Exception e){result.add("*");}
        return result;
    }
    JSONObject assess(JSONObject app) throws Exception { return assess(app,protectedPackages()); }
    private JSONObject assess(JSONObject app,Set<String> protectedPackages) throws Exception {
        JSONArray findings=new JSONArray(),reasons=new JSONArray(),actions=new JSONArray();int level=0,active=0,denied=0,unknown=0;
        Set<String> names=new HashSet<>();JSONArray ps=app.optJSONArray("permissions");
        if(ps!=null)for(int i=0;i<ps.length();i++){
            JSONObject p=ps.getJSONObject(i);String name=p.optString("name");if(!names.add(name))continue;
            JSONObject b=p.optJSONObject("bayton");int n=DefenseRules.level(name,p.optString("description"),b==null?"":b.optString("description"));
            if(n>=4){level=Math.max(level,n);Object grant=p.opt("granted");if(Boolean.TRUE.equals(grant))active++;else if(Boolean.FALSE.equals(grant))denied++;else unknown++;
                findings.put(EventStore.object("permission",name,"level",n,"granted",grant==null?JSONObject.NULL:grant,"reason",n==5?"Portée système ou autres applications (règle AIV V22)":"Avertissement explicite dans une description Android / AOSP","android_description",p.optString("description"),"catalog_description",b==null?JSONObject.NULL:b.opt("description")));}
        }
        String pkg=app.getString("package_name");int uid=app.optInt("uid",-1);JSONArray uidPackages=app.optJSONArray("uid_packages");
        boolean protectedRole=protectedPackages.contains(pkg)||protectedPackages.contains("*");
        if(pkg.equals(context.getPackageName()))reasons.put("AIV conserve son propre journal et sa collecte.");
        if(app.optBoolean("system_app"))reasons.put("Application préinstallée : retrait ou désactivation à vérifier dans Android.");
        if(uid<0||uid%100000<10000||uidPackages==null||uidPackages.length()!=1)reasons.put("UID système, partagé ou attribution indéterminée.");
        if(protectedRole)reasons.put(protectedPackages.contains("*")?"Rôles essentiels non vérifiés : retrait direct indisponible.":"Application active du téléphone : accueil, clavier, appels, SMS, administration ou AIV.");
        JSONArray components=app.optJSONArray("components");
        if(components!=null)for(int i=0;i<components.length();i++){String p=components.getJSONObject(i).optString("permission");if("android.permission.BIND_VPN_SERVICE".equals(p)||"android.permission.BIND_ACCESSIBILITY_SERVICE".equals(p)){protectedRole=true;reasons.put("Service VPN ou accessibilité : vérifier son usage avant un retrait manuel.");break;}}
        if(names.contains("android.permission.REQUEST_INSTALL_PACKAGES"))actions.put("unknown_sources");
        if(names.contains("android.permission.SYSTEM_ALERT_WINDOW"))actions.put("overlay");
        if(names.contains("android.permission.WRITE_SETTINGS"))actions.put("write_settings");
        if(names.contains("android.permission.PACKAGE_USAGE_STATS"))actions.put("usage");
        return EventStore.object("level",level,"findings",findings,"granted_high",active,"denied_high",denied,"unknown_high",unknown,
            "protected_reasons",reasons,"special_actions",actions,"can_request_uninstall",DefenseRules.mayRequestUninstall(level,app.optBoolean("enabled",true),app.optBoolean("system_app"),uid,uidPackages==null?0:uidPackages.length(),protectedRole),
            "necessity","À décider par l’utilisateur; désinstallable ne signifie pas inutile.","special_access_state","Non vérifié pour les autres applications");
    }
    private void log(String pkg,String kind,JSONObject data) throws Exception {
        ContentValues v=new ContentValues();v.put("pkg",pkg);v.put("at_ms",System.currentTimeMillis());v.put("kind",kind);v.put("payload",data.toString());getWritableDatabase().insertOrThrow("journal",null,v);
    }
    public synchronized void onSnapshot(long scan,JSONArray apps) throws Exception {
        long oldScan=latestScan();Set<String> protectedPackages=protectedPackages();SQLiteDatabase db=getWritableDatabase();int reopened=0;
        db.beginTransaction();try{
            for(int i=0;i<apps.length();i++){
                JSONObject a=apps.getJSONObject(i);String pkg=a.getString("package_name"),stamp=stamp(a);JSONObject previous=row(pkg),assessment=assess(a,protectedPackages);int level=assessment.getInt("level");
                boolean returned=previous!=null&&Arrays.asList("REMOVED","NOT_RETURNED","ARCHIVED").contains(previous.optString("state"));
                String kept=previous==null||returned?"":previous.optString("kept_stamp"),state=DefenseRules.state(level,a.optBoolean("enabled",true),stamp.equals(kept),true);
                boolean changed=previous==null||!stamp.equals(previous.optString("stamp"))||returned;
                if(changed){
                    if(previous!=null||oldScan>0||level>=4){
                        JSONObject before=previous==null?new JSONObject():previous.getJSONObject("snapshot");
                        JSONObject delta=new JSONObject(DefenseRules.changes(grants(before),grants(a)));
                        log(pkg,previous==null?(oldScan>0?"NEW_APP":"INITIAL_REVIEW"):"APP_CHANGED",EventStore.object("before",previous==null?JSONObject.NULL:before,"after",a,"permission_changes",delta,"version_changed",previous!=null&&before.optLong("version_code")!=a.optLong("version_code"),"assessment",assessment,"source","Relevés PackageManager","scope","Différence observée; heure exacte et auteur de la modification non établis."));
                    }
                    if("TO_REVIEW".equals(state))reopened++;
                }
                ContentValues v=new ContentValues();v.put("pkg",pkg);v.put("label",a.optString("label",pkg));v.put("level",level);v.put("peak_level",Math.max(level,previous==null?0:previous.optInt("peak_level")));v.put("state",state);v.put("scan_id",scan);v.put("stamp",stamp);v.put("kept_stamp",kept);v.put("payload",a.toString());v.put("assessment",assessment.toString());v.put("last_seen_ms",a.optLong("observed_ms",System.currentTimeMillis()));db.insertWithOnConflict("apps",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            }
            List<String> missing=new ArrayList<>();
            try(Cursor c=db.rawQuery("SELECT pkg FROM apps WHERE scan_id<>? AND state NOT IN ('REMOVED','NOT_RETURNED','ARCHIVED')",new String[]{""+scan})){while(c.moveToNext())missing.add(c.getString(0));}
            for(String pkg:missing){log(pkg,"NOT_RETURNED",EventStore.object("scan_id",scan,"scope","Paquet non retourné dans ce profil; désinstallation non confirmée."));ContentValues v=new ContentValues();v.put("state","NOT_RETURNED");db.update("apps",v,"pkg=?",new String[]{pkg});}
            db.execSQL("INSERT OR REPLACE INTO meta(id,scan_id) VALUES(1,?)",new Object[]{scan});db.setTransactionSuccessful();error="";
        }finally{db.endTransaction();}
        if(reopened>0&&oldScan>0)notifyReview(reopened);
    }
    private void notifyReview(int count) {
        try {NotificationManager nm=context.getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("aiv-defense","Ménage et nouvelles permissions",NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open=PendingIntent.getActivity(context,623,new Intent(context,MainActivity.class).putExtra("open_defense",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            nm.notify(623,new Notification.Builder(context,"aiv-defense").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("AIV · "+count+" dossier(s) à réexaminer").setContentText("Application ajoutée, mise à jour ou permissions modifiées. Ouvrir le ménage.").setContentIntent(open).setAutoCancel(true).build());
        }catch(Exception ignored){/* Queue remains available even if notifications are denied. */}
    }
    public synchronized void packageEvent(String pkg,String action,boolean replacing,boolean archival) throws Exception {
        if(pkg==null||pkg.isEmpty())return;
        log(pkg,"PACKAGE_EVENT",EventStore.object("action",action,"replacing",replacing,"archival",archival,"source","Diffusion système Android reçue"));
        String state=DefenseRules.packageState(Intent.ACTION_PACKAGE_ADDED.equals(action),Intent.ACTION_PACKAGE_REMOVED.equals(action)||Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action),replacing,archival);
        if(!state.isEmpty()){
            ContentValues v=new ContentValues();v.put("state",state);getWritableDatabase().update("apps",v,"pkg=?",new String[]{pkg});
            log(pkg,"ARCHIVED".equals(state)?"ARCHIVAL_OBSERVED":"REMOVAL_OBSERVED",EventStore.object("source","Diffusion système Android","scope","ARCHIVED".equals(state)?"Archivage Android observé; application non désinstallée.":"Retrait sans remplacement ni archivage observé dans le profil courant; initiateur non déduit."));
        }
    }
    public JSONObject status() throws Exception {
        JSONObject counts=new JSONObject();try(Cursor c=getReadableDatabase().rawQuery("SELECT state,COUNT(*) FROM apps GROUP BY state",null)){while(c.moveToNext())counts.put(c.getString(0),c.getLong(1));}
        long head=0;try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(MAX(id),0) FROM journal",null)){c.moveToFirst();head=c.getLong(0);}
        return EventStore.object("schema","aiv-defense/23","scan_id",latestScan(),"revision",latestScan()+":"+head,"counts",counts,"error",error,"watching",DefenseMonitor.running(),"pending_action",context.getSharedPreferences("defense-actions",0).getString("package",""),
            "coverage","Profil courant. Surveillance des installations pendant la collecte ou lorsque AIV est ouverte; inventaire à l’ouverture, au retour des réglages et toutes les 15 minutes de collecte. Arrêt forcé, autres profils et Dossier sécurisé non couverts.",
            "mode","Analyse automatique; désinstallation et réglages soumis à Android.");
    }
    public synchronized JSONObject page(String scope,int offset) throws Exception {
        String where="TO_REVIEW".equals(scope)?"state='TO_REVIEW'":"KEPT".equals(scope)?"state='KEPT'":"HISTORY".equals(scope)?"state IN ('REMOVED','ARCHIVED','DISABLED','NOT_RETURNED','BELOW_THRESHOLD') AND (peak_level>=4 OR state IN ('REMOVED','ARCHIVED'))":"peak_level>=4";
        JSONArray rows=new JSONArray();long total=0;try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM apps WHERE "+where,null)){c.moveToFirst();total=c.getLong(0);}
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM apps WHERE "+where+" ORDER BY level DESC,label COLLATE NOCASE,pkg LIMIT 25 OFFSET ?",new String[]{""+Math.max(0,offset)})){while(c.moveToNext()){JSONObject r=row(c),snapshot=r.getJSONObject("snapshot");r.remove("snapshot");r.remove("kept_stamp");r.put("version_name",snapshot.opt("version_name")).put("version_code",snapshot.optLong("version_code"));rows.put(r);}}
        return EventStore.object("rows",rows,"total",total,"offset",Math.max(0,offset));
    }
    public synchronized JSONObject detail(String pkg,long before) throws Exception {
        JSONObject row=row(pkg);JSONArray history=new JSONArray();long next=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,at_ms,kind,payload FROM journal WHERE pkg=? AND id<? ORDER BY id DESC LIMIT 50",new String[]{pkg,""+(before>0?before:Long.MAX_VALUE)})){
            while(c.moveToNext()){next=c.getLong(0);history.put(EventStore.object("id",next,"at_ms",c.getLong(1),"kind",c.getString(2),"data",new JSONObject(c.getString(3))));}}
        if(row==null)throw new IllegalArgumentException("Dossier non disponible.");
        return EventStore.object("app",row,"history",history,"next_before",next,"scope","Dossier local conservé après retrait. Les données privées de l’application et ses APK ne sont pas sauvegardées ici.");
    }
    public synchronized JSONObject decide(String pkg,String stamp,boolean keep) throws Exception {
        JSONObject r=row(pkg);if(r==null||!stamp.equals(r.optString("stamp")))throw new IllegalArgumentException("Dossier modifié : actualiser avant de choisir.");
        String state=r.optString("state");if(!"TO_REVIEW".equals(state)&&!"KEPT".equals(state))throw new IllegalArgumentException("Application à réexaminer avant cette décision.");
        ContentValues v=new ContentValues();v.put("kept_stamp",keep?stamp:"");v.put("state",keep?"KEPT":"TO_REVIEW");getWritableDatabase().update("apps",v,"pkg=?",new String[]{pkg});
        log(pkg,keep?"KEEP_CHOSEN":"REVIEW_REQUESTED",EventStore.object("stamp",stamp,"source","Choix utilisateur","scope","Choix valable pour cet état et cette version; toute modification observée réouvre le dossier."));return status();
    }
    public synchronized void action(String pkg,String kind,JSONObject data) throws Exception { log(pkg,kind,data); }
    public synchronized void confirmRemoval(String pkg) throws Exception {ContentValues v=new ContentValues();v.put("state","REMOVED");getWritableDatabase().update("apps",v,"pkg=?",new String[]{pkg});log(pkg,"UNINSTALL_CONFIRMED",EventStore.object("source","Résultat OK Android et paquet devenu absent du profil courant"));}
    public synchronized void export(Writer out) throws Exception {
        out.write("{\"schema\":\"aiv-defense-journal/23\",\"status\":"+status()+",\"applications\":[");boolean first=true;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM apps ORDER BY pkg",null)){while(c.moveToNext()){if(!first)out.write(",");out.write(row(c).toString());first=false;}}
        out.write("],\"journal\":[");first=true;try(Cursor c=getReadableDatabase().rawQuery("SELECT id,pkg,at_ms,kind,payload FROM journal ORDER BY id",null)){while(c.moveToNext()){if(!first)out.write(",");out.write(EventStore.object("id",c.getLong(0),"package_name",c.getString(1),"at_ms",c.getLong(2),"kind",c.getString(3),"data",new JSONObject(c.getString(4))).toString());first=false;}}out.write("]}");
    }
}
