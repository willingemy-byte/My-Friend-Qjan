package fr.erick.journallocal;

import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only observation of installed apps. All dossiers stay in the private app database. */
public final class PermissionAudit extends SQLiteOpenHelper {
    private static PermissionAudit instance;
    private final Context context;
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile String error="";
    public static synchronized PermissionAudit get(Context c){if(instance==null)instance=new PermissionAudit(c.getApplicationContext());return instance;}
    private PermissionAudit(Context c){super(c,"permission-audit.sqlite",null,2);context=c;setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        penaltyTable(db);
        db.execSQL("CREATE TABLE scans(id INTEGER PRIMARY KEY, started_ms INTEGER, ended_ms INTEGER)");
        db.execSQL("CREATE TABLE apps(scan_id INTEGER, package_name TEXT, label TEXT, system_app INTEGER, uid INTEGER, count INTEGER, version_code INTEGER, search_text TEXT, payload TEXT, PRIMARY KEY(scan_id,package_name))");
        db.execSQL("CREATE TABLE references_data(package_name TEXT PRIMARY KEY, payload TEXT)");
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, package_name TEXT, timestamp_ms INTEGER, payload TEXT)");
        db.execSQL("CREATE INDEX audit_history_package ON history(package_name,id)");
        db.execSQL("CREATE TABLE imported_findings(id INTEGER PRIMARY KEY AUTOINCREMENT, import_id TEXT, package_name TEXT, payload TEXT)");
    }
    private static void penaltyTable(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS penalty_config(id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next){if(old<2)penaltyTable(db);}
    private long latest(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(MAX(id),0) FROM scans",null)){c.moveToFirst();return c.getLong(0);}}
    public JSONObject summary()throws Exception{
        JSONObject s=EventStore.object("scan_id",latest(),"busy",busy.get(),"error",error,"coverage","Profil courant; paquets visibles, y compris désactivés. AppOps et règles SYSTEM_FIXED/POLICY_FIXED non collectés.");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*),COALESCE(SUM(system_app),0) FROM apps WHERE scan_id=?",new String[]{""+latest()})){c.moveToFirst();s.put("total",c.getLong(0));s.put("system",c.getLong(1));}
        return s;
    }
    private JSONObject stored(long scan,String pkg)throws Exception{try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM apps WHERE scan_id=? AND package_name=?",new String[]{""+scan,pkg})){return c.moveToFirst()?new JSONObject(c.getString(0)):null;}}
    private JSONObject reference(String pkg)throws Exception{try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM references_data WHERE package_name=?",new String[]{pkg})){return c.moveToFirst()?new JSONObject(c.getString(0)):new JSONObject();}}
    private void history(String pkg,JSONObject value){ContentValues v=new ContentValues();v.put("package_name",pkg);v.put("timestamp_ms",System.currentTimeMillis());v.put("payload",value.toString());getWritableDatabase().insertOrThrow("history",null,v);}
    private void saveReference(String pkg,JSONObject r){ContentValues v=new ContentValues();v.put("package_name",pkg);v.put("payload",r.toString());getWritableDatabase().insertWithOnConflict("references_data",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public void scan(){
        if(!busy.compareAndSet(false,true))return;error="";
        new Thread(()->{try{collect();}catch(Exception e){error="Inventaire interrompu : "+e.getClass().getSimpleName()+". Dernier relevé complet conservé.";}finally{busy.set(false);ApkEvidence.get(context).request();}},"journal-inventory").start();
    }
    private JSONObject permission(PackageManager pm,String name,Object granted)throws Exception{
        JSONObject p=EventStore.object("name",name,"granted",granted,"protection_level",-1,"protection","INCONNU","description","Description indisponible","revocation","INCONNU : politique de révocation non collectée");
        try{PermissionInfo i=pm.getPermissionInfo(name,0);CharSequence label=i.loadLabel(pm),description=i.loadDescription(pm);
            p.put("label",label==null?name:label.toString());p.put("description",description==null?"Description non fournie par Android":description.toString());p.put("protection_level",i.protectionLevel);p.put("defined_by",i.packageName);p.put("group",i.group==null?JSONObject.NULL:i.group);p.put("flags",i.flags);
            int base=i.protectionLevel&15;p.put("protection",base==0?"Normale":base==1?"Dangereuse / exécution":base==2?"Signature":base==4?"Interne":"Type Android "+base);
        }catch(PackageManager.NameNotFoundException e){p.put("definition_error","Définition non accessible; nom conservé");}JSONObject catalog=ReferenceCatalog.get(context).permission(name);if(catalog!=null)p.put("bayton",new JSONObject(catalog.toString()).put("source","Jason Bayton / AOSP").put("reference_api_level",ReferenceCatalog.get(context).bayton.optInt("api_level")));return p;
    }
    private JSONObject installSource(PackageManager pm,String pkg)throws Exception{
        JSONObject out=EventStore.object("source_available",false,"reason","ANDROID_NOT_EXPOSED","observed_ms",System.currentTimeMillis(),
            "installing_package",JSONObject.NULL,"initiating_package",JSONObject.NULL,"originating_package",JSONObject.NULL,"update_owner_package",JSONObject.NULL,
            "scope","Source de la dernière installation ou mise à jour exposée par Android; aucun historique complet ni installation silencieuse déduite.");
        try{
            if(android.os.Build.VERSION.SDK_INT>=30){
                InstallSourceInfo i=pm.getInstallSourceInfo(pkg);
                String installer=i.getInstallingPackageName(),initiator=i.getInitiatingPackageName(),origin=i.getOriginatingPackageName();
                out.put("source","PackageManager.getInstallSourceInfo");
                out.put("installing_package",installer==null?JSONObject.NULL:installer);
                out.put("initiating_package",initiator==null?JSONObject.NULL:initiator);
                out.put("originating_package",origin==null?JSONObject.NULL:origin);
                out.put("originating_scope","Déclaration de l’initiateur non vérifiée par Android; exclue du multiplicateur L5.");
                out.put("installer_scope","Installateur enregistré; cette valeur peut être modifiée dans Android.");
                String owner=android.os.Build.VERSION.SDK_INT>=34?i.getUpdateOwnerPackageName():null;
                out.put("update_owner_package",owner==null?JSONObject.NULL:owner);
                if(android.os.Build.VERSION.SDK_INT>=33)out.put("package_source",i.getPackageSource());
                boolean available=installer!=null||initiator!=null||owner!=null;
                out.put("source_available",available);out.put("reason",available?"PARTIAL_ANDROID_OBSERVATION":"ANDROID_NOT_EXPOSED");
            }else{
                String installer=pm.getInstallerPackageName(pkg);out.put("source","PackageManager.getInstallerPackageName");
                out.put("installing_package",installer==null?JSONObject.NULL:installer);out.put("source_available",installer!=null);
                out.put("reason",installer==null?"ANDROID_NOT_EXPOSED":"LEGACY_INSTALLER_ONLY");
            }
        }catch(PackageManager.NameNotFoundException e){out.put("reason","PACKAGE_NOT_VISIBLE");}
        catch(SecurityException e){out.put("reason","ANDROID_ACCESS_DENIED");}
        return out;
    }
    private JSONObject component(PackageManager pm,ComponentInfo c,String type)throws Exception{
        boolean enabled=c.enabled;int setting=pm.getComponentEnabledSetting(new ComponentName(c.packageName,c.name));
        if(setting==PackageManager.COMPONENT_ENABLED_STATE_ENABLED)enabled=true;
        else if(setting!=PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)enabled=false;
        JSONObject out=EventStore.object("name",c.name,"type",type,"exported",c.exported,"enabled",enabled,
            "source","PackageManager component metadata","runtime_access","NOT_TESTED");
        if(c instanceof ProviderInfo){ProviderInfo v=(ProviderInfo)c;out.put("authority",v.authority);out.put("read_permission",v.readPermission==null?JSONObject.NULL:v.readPermission);out.put("write_permission",v.writePermission==null?JSONObject.NULL:v.writePermission);out.put("grant_uri_permissions",v.grantUriPermissions);out.put("path_permissions_present",v.pathPermissions!=null&&v.pathPermissions.length>0);}
        else {String permission=c instanceof ServiceInfo?((ServiceInfo)c).permission:((ActivityInfo)c).permission;out.put("permission",permission==null?JSONObject.NULL:permission);}
        return out;
    }
    private JSONObject inspect(PackageManager pm,PackageInfo info)throws Exception{
        ApplicationInfo a=info.applicationInfo;JSONArray permissions=new JSONArray(),defined=new JSONArray(),candidates=new JSONArray(),components=new JSONArray();
        String[] pkgs=pm.getPackagesForUid(a.uid);if(pkgs!=null)for(String p:pkgs)candidates.put(p);
        if(info.requestedPermissions!=null)for(int i=0;i<info.requestedPermissions.length;i++){
            Object grant=info.requestedPermissionsFlags!=null&&i<info.requestedPermissionsFlags.length?Boolean.valueOf((info.requestedPermissionsFlags[i]&PackageInfo.REQUESTED_PERMISSION_GRANTED)!=0):JSONObject.NULL;
            permissions.put(permission(pm,info.requestedPermissions[i],grant));
        }
        if(info.permissions!=null)for(PermissionInfo p:info.permissions)defined.put(EventStore.object("name",p.name,"protection_level",p.protectionLevel,"scope","Permission définie; ne signifie pas que ce paquet la détient"));
        if(info.providers!=null)for(ProviderInfo c:info.providers)components.put(component(pm,c,"provider"));
        if(info.services!=null)for(ServiceInfo c:info.services)components.put(component(pm,c,"service"));
        if(info.activities!=null)for(ActivityInfo c:info.activities)components.put(component(pm,c,"activity"));
        if(info.receivers!=null)for(ActivityInfo c:info.receivers)components.put(component(pm,c,"receiver"));
        return EventStore.object("components",components,"components_collected",true,"package_name",info.packageName,"label",String.valueOf(a.loadLabel(pm)),"uid",a.uid,"profile_id",a.uid/100000,"uid_packages",candidates,"attribution",a.uid%100000==1000||candidates.length()!=1?"Partagée ou indéterminée":"Un paquet retourné pour cet UID",
            "system_app",(a.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0,"updated_system_app",(a.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0,"enabled",a.enabled,"enabled_setting",pm.getApplicationEnabledSetting(info.packageName),
            "version_name",info.versionName==null?JSONObject.NULL:info.versionName,"version_code",android.os.Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode,"last_update_ms",info.lastUpdateTime,"first_install_ms",info.firstInstallTime,
            "min_sdk",a.minSdkVersion,"target_sdk",a.targetSdkVersion,"debuggable",(a.flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0,"allow_backup",(a.flags&ApplicationInfo.FLAG_ALLOW_BACKUP)!=0,"certificates",ApkEvidence.certificates(info),"audit_schema","aiv-audit/22","install_source",installSource(pm,info.packageName),"permissions",permissions,"defined_permissions",defined,"count",permissions.length(),"source","PackageManager.GET_PERMISSIONS + MATCH_DISABLED_COMPONENTS","observed_ms",System.currentTimeMillis(),"removable_in_settings","INCONNU","disableable_in_settings","INCONNU","permission_lock_flags","INCONNU","operations_observed","Non déterminées par cet inventaire");
    }
    /** Sorted permission identities/states, independent of labels and array ordering. */
    private String fingerprint(JSONObject a)throws Exception{
        TreeMap<String,String> values=new TreeMap<>();JSONArray ps=a.getJSONArray("permissions");for(int i=0;i<ps.length();i++){JSONObject p=ps.getJSONObject(i);values.put(p.getString("name"),String.valueOf(p.opt("granted"))+":"+p.optInt("protection_level",-1));}
        JSONObject ins=a.optJSONObject("install_source");String sourceKey=ins==null?"":ins.optString("installing_package")+":"+ins.optString("initiating_package")+":"+ins.optString("update_owner_package");
        return String.valueOf(a.optJSONObject("certificates"))+":"+String.valueOf(a.optJSONArray("components"))+":"+sourceKey + ":" + a.optString("version_code")+":"+a.optBoolean("enabled")+":"+a.optInt("uid")+":"+values.toString();
    }
    private void collect()throws Exception{
        long start=System.currentTimeMillis(),old=latest(),id=old+1;PackageManager pm=context.getPackageManager();JSONArray result=new JSONArray();
        for(PackageInfo p:pm.getInstalledPackages((android.os.Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES)|PackageManager.GET_PERMISSIONS|PackageManager.GET_PROVIDERS|PackageManager.GET_SERVICES|PackageManager.GET_ACTIVITIES|PackageManager.GET_RECEIVERS|PackageManager.MATCH_DISABLED_COMPONENTS))if(p.applicationInfo!=null)result.put(inspect(pm,p));
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            for(int i=0;i<result.length();i++){JSONObject a=result.getJSONObject(i);String pkg=a.getString("package_name");JSONObject before=stored(old,pkg);
                ContentValues v=new ContentValues();v.put("scan_id",id);v.put("package_name",pkg);v.put("label",a.getString("label"));v.put("system_app",a.getBoolean("system_app")?1:0);v.put("uid",a.getInt("uid"));v.put("count",a.getInt("count"));v.put("version_code",a.getLong("version_code"));v.put("search_text",a.toString().toLowerCase(Locale.ROOT));v.put("payload",a.toString());db.insertOrThrow("apps",null,v);
                if(before==null||!fingerprint(before).equals(fingerprint(a)))history(pkg,EventStore.object("kind",before==null?"first_seen":"snapshot_change","before_scan",old,"after_scan",id,"scope","Différence entre relevés; heure exacte et auteur du changement INCONNUS"));
            }
            if(old>0)try(Cursor c=db.rawQuery("SELECT package_name FROM apps WHERE scan_id=? AND package_name NOT IN (SELECT package_name FROM apps WHERE scan_id=?)",new String[]{""+old,""+id})){while(c.moveToNext())history(c.getString(0),EventStore.object("kind","not_returned","before_scan",old,"after_scan",id,"scope","Non retourné; désinstallation non établie"));}
            ContentValues v=new ContentValues();v.put("id",id);v.put("started_ms",start);v.put("ended_ms",System.currentTimeMillis());db.insertOrThrow("scans",null,v);db.setTransactionSuccessful();
        }finally{db.endTransaction();}try{EventStore.get(context).setAuditInventory(result);}catch(Exception e){error="Inventaire conservé; index du journal indisponible : "+e.getClass().getSimpleName();}
    }
    /** Minimal coherence input derived from the latest completed local PackageManager snapshot. */
    public JSONObject coherenceInventory()throws Exception{
        long scan=latest();JSONArray apps=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM apps WHERE scan_id=? ORDER BY package_name",new String[]{""+scan})){
            while(c.moveToNext()){
                JSONObject raw=new JSONObject(c.getString(0)),out=new JSONObject();JSONArray names=new JSONArray(),ps=raw.optJSONArray("permissions");
                if(ps!=null)for(int i=0;i<ps.length();i++){JSONObject perm=ps.optJSONObject(i);if(perm!=null&&!perm.optString("name").isEmpty())names.put(perm.optString("name"));}
                JSONArray uidPackages=raw.optJSONArray("uid_packages");int uidPackageCount=uidPackages==null?0:uidPackages.length();int uid=raw.optInt("uid",-1);
                out.put("package",raw.optString("package_name"));out.put("label",raw.optString("label",raw.optString("package_name")));out.put("system_app",raw.optBoolean("system_app",false));out.put("updated_system_app",raw.optBoolean("updated_system_app",false));out.put("uid",uid);out.put("uid_package_count",uidPackageCount);out.put("uid_reserved",uid<0||uid%100000<10000);out.put("attribution_unique",uid>=0&&uid%100000>=10000&&uidPackageCount==1);out.put("version_code",raw.optLong("version_code",0));out.put("version_name",raw.optString("version_name",""));out.put("permissions",names);apps.put(out);
            }
        }
        return EventStore.object("schema","journal-coherence-input/1","scan_id",scan,"scope","Permissions demandées/déclarées lues localement via PackageManager; présence ne signifie pas utilisation.","apps",apps);
    }
    public JSONObject penaltyConfig()throws Exception{
        JSONObject policy=new JSONObject();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM penalty_config WHERE id=1",null)){if(c.moveToFirst())policy=new JSONObject(c.getString(0));}
        JSONObject refs=new JSONObject();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT package_name,payload FROM references_data",null)){while(c.moveToNext()){
            JSONObject r=new JSONObject(c.getString(1));if(r.has("penalty_evidence"))refs.put(c.getString(0),r.get("penalty_evidence"));
        }}
        return EventStore.object("schema","aiv-penalty-settings/1","policy",policy,"references",refs);
    }
    public JSONObject penaltyData()throws Exception{
        JSONArray apps=new JSONArray();JSONObject refs=new JSONObject();long scan=latest();LocalReference baseline=new LocalReference(context);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT a.payload,r.payload FROM apps a LEFT JOIN references_data r ON r.package_name=a.package_name WHERE a.scan_id=? ORDER BY a.package_name",new String[]{""+scan})){
            while(c.moveToNext()){
                JSONObject raw=new JSONObject(c.getString(0)),out=new JSONObject();
                for(String key:new String[]{"package_name","label","uid","profile_id","uid_packages","system_app","version_code","install_source","enabled","components","components_collected","audit_schema","certificates"})if(raw.has(key))out.put(key,raw.get(key));
                JSONArray perms=new JSONArray(),source=raw.optJSONArray("permissions");
                if(source!=null)for(int i=0;i<source.length();i++){JSONObject perm=source.getJSONObject(i);JSONObject view=EventStore.object("name",perm.optString("name"),"granted",perm.opt("granted"),"protection_level",perm.optInt("protection_level",-1));view.put("description",perm.optString("description"));if(perm.has("bayton"))view.put("bayton",perm.get("bayton"));String description=perm.optString("description");String lower=description.toLowerCase(Locale.ROOT);if(lower.contains("malveillant")||lower.contains("malicious"))view.put("warning",description);if("android".equals(perm.optString("defined_by"))&&(lower.contains("à votre insu")||lower.contains("sans votre intervention")||lower.contains("sans votre confirmation")||lower.contains("without your knowledge")||lower.contains("without your confirmation")))view.put("autonomy_description",description);perms.put(view);}
                out.put("permissions",perms);out.put("local_reference",baseline.derive(raw));apps.put(out);
                if(!c.isNull(1))refs.put(raw.getString("package_name"),new JSONObject(c.getString(1)));
            }
        }
        return EventStore.object("scan_id",scan,"apps",apps,"references",refs,"busy",busy.get(),"error",error);
    }
    public synchronized JSONObject savePenaltyConfig(String value)throws Exception{
        if(value==null||value.length()>4*1024*1024)throw new IllegalArgumentException("Réglages limités à 4 Mio");
        JSONObject input=new JSONObject(value);if(!"aiv-penalty-settings/1".equals(input.optString("schema")))throw new IllegalArgumentException("Format de réglages invalide");
        JSONObject policy=input.getJSONObject("policy"),refs=input.getJSONObject("references");
        if(!java.util.Arrays.asList("aiv-penalty-policy/1","aiv-penalty-policy/2","aiv-penalty-policy/3").contains(policy.optString("schema"))||policy.toString().length()>512*1024||refs.length()>10000)throw new IllegalArgumentException("Barème invalide");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            ContentValues config=new ContentValues();config.put("id",1);config.put("payload",policy.toString());db.insertWithOnConflict("penalty_config",null,config,SQLiteDatabase.CONFLICT_REPLACE);
            Iterator<String> keys=refs.keys();while(keys.hasNext()){
                String pkg=keys.next();if(pkg.length()>255||!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))throw new IllegalArgumentException("Nom de paquet invalide");
                JSONObject evidence=refs.getJSONObject(pkg),r=reference(pkg);if(evidence.toString().length()>256*1024)throw new IllegalArgumentException("Dossier trop long");
                if(!evidence.toString().equals(String.valueOf(r.optJSONObject("penalty_evidence")))){
                    r.put("penalty_evidence",evidence);r.put("penalty_recorded_ms",System.currentTimeMillis());saveReference(pkg,r);
                    history(pkg,EventStore.object("kind","penalty_evidence_saved","evidence",evidence));
                }
            }
            history("",EventStore.object("kind","penalty_policy_saved","policy",policy));db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return EventStore.object("ok",true,"message","Barème et observations enregistrés localement");
    }
    public JSONObject page(String search,String scope,int offset)throws Exception{
        if(search==null)search="";if(search.length()>4096)throw new IllegalArgumentException("Recherche trop longue");
        ArrayList<String> args=new ArrayList<>();args.add(""+latest());String where="a.scan_id=?";
        if("system".equals(scope))where+=" AND a.system_app=1";else if("other".equals(scope))where+=" AND a.system_app=0";
        if(!search.trim().isEmpty()){where+=" AND (instr(a.search_text,?)>0 OR instr(lower(COALESCE(r.payload,'')),?)>0)";args.add(search.toLowerCase(Locale.ROOT));args.add(search.toLowerCase(Locale.ROOT));}
        String from=" FROM apps a LEFT JOIN references_data r ON r.package_name=a.package_name WHERE "+where;long total;SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.rawQuery("SELECT COUNT(*)"+from,args.toArray(new String[0]))){c.moveToFirst();total=c.getLong(0);}
        args.add(""+Math.max(0,offset));JSONArray rows=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT a.package_name,a.label,a.system_app,a.uid,a.count,a.version_code,r.payload"+from+" ORDER BY a.label COLLATE NOCASE,a.package_name LIMIT 25 OFFSET ?",args.toArray(new String[0]))){while(c.moveToNext())rows.put(EventStore.object("package_name",c.getString(0),"label",c.getString(1),"system_app",c.getInt(2)==1,"uid",c.getInt(3),"count",c.getInt(4),"version_code",c.getLong(5),"reference",c.isNull(6)?new JSONObject():new JSONObject(c.getString(6))));}
        return EventStore.object("rows",rows,"total",total,"limit",25);
    }
    public JSONObject colorContext(String pkg)throws Exception{
        JSONObject a=stored(latest(),pkg);if(a==null)throw new IllegalArgumentException("Paquet inconnu");return EventStore.object("uid",a.getInt("uid"),"count",a.getInt("count"),"version_code",a.getLong("version_code"),"reference",reference(pkg));
    }
    public JSONObject detail(String pkg)throws Exception{
        JSONObject a=stored(latest(),pkg);if(a==null)throw new IllegalArgumentException("Paquet absent du dernier inventaire");JSONArray h=new JSONArray(),findings=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT timestamp_ms,payload FROM history WHERE package_name=? ORDER BY id DESC LIMIT 100",new String[]{pkg})){while(c.moveToNext())h.put(new JSONObject(c.getString(1)).put("timestamp_ms",c.getLong(0)));}
        try(Cursor c=getReadableDatabase().rawQuery("SELECT import_id,payload FROM imported_findings WHERE package_name=? ORDER BY id DESC LIMIT 50",new String[]{pkg})){while(c.moveToNext())findings.put(new JSONObject(c.getString(1)).put("import_id",c.getString(0)));}
        a.put("apk_evidence",ApkEvidence.get(context).read(pkg,a.optLong("version_code"),a.optLong("last_update_ms")));
        return EventStore.object("app",a,"reference",reference(pkg),"history",h,"imported_findings",findings,"import_scope","Rapprochement par le profil du rapport importé; attribution réseau non vérifiée par l’import");
    }
    public synchronized JSONObject save(String pkg,String json)throws Exception{
        if(json==null||json.length()>16000||stored(latest(),pkg)==null)throw new IllegalArgumentException("Dossier invalide");JSONObject input=new JSONObject(json),r=reference(pkg);
        if(!input.isNull("play_count")){double count=input.getDouble("play_count");if(count<0||count>10000||count!=Math.floor(count))throw new IllegalArgumentException("Nombre entier requis");}
        for(String key:new String[]{"play_count","play_source","count_kind","same_version","note","watched"})if(input.has(key))r.put(key,input.get(key));
        r.put("installed_version_code",stored(latest(),pkg).getLong("version_code"));r.put("recorded_ms",System.currentTimeMillis());r.put("reference_origin","Saisie utilisateur");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{saveReference(pkg,r);history(pkg,EventStore.object("kind","dossier_saved","reference",r));db.setTransactionSuccessful();}finally{db.endTransaction();}return EventStore.object("ok",true);
    }
    public JSONObject importReport(android.net.Uri uri)throws Exception{
        byte[] bytes;try(InputStream in=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("Fichier inaccessible");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>8*1024*1024)throw new IOException("Rapport limité à 8 Mio");out.write(b,0,n);}bytes=out.toByteArray();
        }
        JSONObject root=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));if(!"aiv-journal-interpretation/1".equals(root.optString("schema")))throw new IOException("Schéma attendu : aiv-journal-interpretation/1");
        StringBuilder hash=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(Locale.ROOT,"%02x",b&255));String id=hash.toString();
        SQLiteDatabase db=getWritableDatabase();try(Cursor c=db.rawQuery("SELECT 1 FROM imported_findings WHERE import_id=? LIMIT 1",new String[]{id})){if(c.moveToFirst())return EventStore.object("ok",true,"message","Rapport déjà importé");}
        JSONArray profiles=root.getJSONArray("actor_profiles"),findings=root.optJSONArray("findings");Map<String,String> mapping=new HashMap<>();Set<String> ambiguous=new HashSet<>();
        db.beginTransaction();try{
            for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);String pkg=p.optString("package"),actor=p.optString("actor");if(pkg.isEmpty())continue;
                if(mapping.containsKey(actor)&&!mapping.get(actor).equals(pkg))ambiguous.add(actor);else mapping.put(actor,pkg);
                JSONObject r=reference(pkg);r.put("imported_profile",p);r.put("import_id",id);
                // Example defaults from an imported dashboard are not verified Google Play measurements.
                if(!r.has("play_count")){r.put("play_count",p.has("play_default")?p.get("play_default"):JSONObject.NULL);r.put("reference_origin","Valeur de référence importée; non vérifiée");r.put("count_kind","unknown");r.put("same_version",false);r.put("play_source",p.optString("play_url"));}
                saveReference(pkg,r);history(pkg,EventStore.object("kind","report_import","import_id",id,"reference",r));
            }
            if(findings!=null)for(int i=0;i<findings.length();i++){JSONObject f=findings.getJSONObject(i);String actor=f.optString("actor");ContentValues v=new ContentValues();v.put("import_id",id);v.put("package_name",ambiguous.contains(actor)?null:mapping.get(actor));v.put("payload",f.toString());db.insertOrThrow("imported_findings",null,v);}
            history("",EventStore.object("kind","report_summary","import_id",id,"summary",root.optJSONObject("summary"),"scope","Résumé du fichier importé; données sources inchangées"));db.setTransactionSuccessful();
        }finally{db.endTransaction();}return EventStore.object("ok",true,"message",profiles.length()+" profils de référence importés");
    }
    public void exportReference(Writer writer)throws Exception{
        LocalReference baseline=new LocalReference(context);long scan=latest();JSONArray apps=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM apps WHERE scan_id=? ORDER BY package_name",new String[]{""+scan})){while(c.moveToNext())apps.put(baseline.derive(new JSONObject(c.getString(0))));}
        writer.write(EventStore.object("schema","aiv-reference/1","source","user","cursor","local-scan-"+scan,"estimated",true,"apps",apps).toString());
    }
    public void export(Writer writer)throws Exception{export(writer,false);}
    public void export(Writer writer,boolean full)throws Exception{
        SQLiteDatabase db=getReadableDatabase();db.beginTransaction();try{
            writer.write("{\"schema\":\"journal-permission-audit/1\",\"export_scope\":\""+(full?"full_history":"latest_snapshot")+"\"");for(String table:new String[]{"scans","apps","references_data","history","imported_findings","penalty_config"}){
                writer.write(",\""+table+"\":[");boolean first=true;try(Cursor c=db.rawQuery("SELECT * FROM "+table+(!full ? (table.equals("apps")?" WHERE scan_id="+latest():table.equals("scans")?" WHERE id="+latest():table.equals("history")||table.equals("imported_findings")?" WHERE 0":"") : ""),null)){while(c.moveToNext()){JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++){Object v=c.isNull(i)?JSONObject.NULL:c.getType(i)==Cursor.FIELD_TYPE_INTEGER?Long.valueOf(c.getLong(i)):c.getColumnName(i).equals("payload")?new JSONObject(c.getString(i)):c.getString(i);row.put(c.getColumnName(i),v);}if(!first)writer.write(",");writer.write(row.toString());first=false;}}writer.write("]");
            }writer.write(",\"v22_catalogs\":");writer.write(ReferenceCatalog.get(context).summary().toString());writer.write(",\"v22_apk_evidence\":");ApkEvidence.get(context).export(writer);writer.write("}");db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
}