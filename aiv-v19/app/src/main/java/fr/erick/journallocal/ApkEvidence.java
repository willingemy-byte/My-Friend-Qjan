package fr.erick.journallocal;

import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.SystemClock;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.json.*;

/** Resumable, bounded background APK reading. Inventory/startup never waits for DEX analysis. */
public final class ApkEvidence extends SQLiteOpenHelper {
    private static ApkEvidence instance;private final Context context;
    private final AtomicBoolean busy=new AtomicBoolean();private volatile String error="",current="";
    public static synchronized ApkEvidence get(Context c){if(instance==null)instance=new ApkEvidence(c.getApplicationContext());return instance;}
    private ApkEvidence(Context c){super(c,"apk-evidence.sqlite",null,1);context=c;setWriteAheadLoggingEnabled(true);}
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE evidence(package TEXT PRIMARY KEY, cache_key TEXT NOT NULL, version INTEGER, updated INTEGER, payload TEXT NOT NULL)");}
    public void onUpgrade(SQLiteDatabase db,int old,int next){}
    public JSONObject status(){long n=0;try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM evidence",null)){c.moveToFirst();n=c.getLong(0);}return EventStore.object("busy",busy.get(),"enabled",enabled(),"cached_packages",n,"current_package",current,"error",error,"scope","Profil courant et APK lisibles. Analyse des classes DEX définies dans base et splits; code natif, code téléchargé et DEX intégrés dans d’autres archives non couverts.");}
    public JSONObject read(String pkg,long version,long updated)throws Exception{
        try(Cursor c=getReadableDatabase().rawQuery("SELECT version,updated,payload FROM evidence WHERE package=?",new String[]{pkg})){
            if(c.moveToFirst()&&c.getLong(0)==version&&c.getLong(1)==updated){JSONObject out=new JSONObject(c.getString(2));if(ReferenceCatalog.get(context).revision().equals(out.optString("catalog_revision")))return out;}
        }return EventStore.object("status","PENDING","notice","Analyse locale en attente ou à renouveler pour cette version.");
    }
    private boolean enabled(){return Continuous.enabled(context)&&Continuous.prefs(context).getBoolean("analysis_enabled",true);}
    public void request(){if(!enabled()||!busy.compareAndSet(false,true))return;new Thread(()->{
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        try{error="";long until=SystemClock.elapsedRealtime()+20000;ReferenceCatalog catalog=ReferenceCatalog.get(context);
            for(PackageInfo p:context.getPackageManager().getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS)){
                if(!enabled()||SystemClock.elapsedRealtime()>=until)break;if(p.applicationInfo==null)continue;
                long version=android.os.Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;
                List<File> files=new ArrayList<>();files.add(new File(p.applicationInfo.sourceDir));if(p.applicationInfo.splitSourceDirs!=null)for(String s:p.applicationInfo.splitSourceDirs)files.add(new File(s));
                StringBuilder key=new StringBuilder(catalog.revision()).append(':').append(version).append(':').append(p.lastUpdateTime);
                for(File f:files)key.append(':').append(f.getPath()).append(':').append(f.length()).append(':').append(f.lastModified());
                try(Cursor c=getReadableDatabase().rawQuery("SELECT cache_key FROM evidence WHERE package=?",new String[]{p.packageName})){if(c.moveToFirst()&&key.toString().equals(c.getString(0)))continue;}
                current=p.packageName;JSONObject result=inspect(files,catalog);if(!enabled())break;result.put("version_code",version).put("last_update_ms",p.lastUpdateTime);
                // An update during the scan invalidates the snapshot; retry on the next scheduled pass.
                PackageInfo after=context.getPackageManager().getPackageInfo(p.packageName,0);if(after.lastUpdateTime!=p.lastUpdateTime)continue;
                ContentValues v=new ContentValues();v.put("package",p.packageName);v.put("cache_key",key.toString());v.put("version",version);v.put("updated",p.lastUpdateTime);v.put("payload",result.toString());getWritableDatabase().insertWithOnConflict("evidence",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            }
        }catch(Exception e){error="Analyse APK interrompue : "+e.getClass().getSimpleName();}finally{current="";busy.set(false);}
    },"aiv-apk-evidence").start();}
    private JSONObject inspect(List<File> files,ReferenceCatalog catalog)throws Exception{
        JSONArray parts=new JSONArray(),issues=new JSONArray(),hits=new JSONArray();Set<Integer> found=new TreeSet<>();int classes=0,dexCount=0;long budget=256L*1024*1024;
        for(File f:files){if(!enabled())break;JSONObject part=EventStore.object("name",f.getName(),"size",f.length());parts.put(part);
            try{if(f.length()>1024L*1024*1024)throw new IOException("APK_SIZE_LIMIT");MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(!enabled())throw new InterruptedIOException("PAUSED");digest.update(buffer,0,n);}}part.put("sha256",hex(digest.digest()));
                try(ZipFile zip=new ZipFile(f)){Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){
                    ZipEntry z=entries.nextElement();if(!z.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"))continue;
                    try{if(z.getSize()<0||z.getSize()>32L*1024*1024||z.getSize()>budget)throw new IOException("DEX_SIZE_LIMIT");
                        budget-=z.getSize();ByteArrayOutputStream bytes=new ByteArrayOutputStream((int)z.getSize());try(InputStream in=zip.getInputStream(z)){byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>z.getSize())throw new IOException("DEX_SIZE_MISMATCH");bytes.write(buffer,0,n);}}
                        Set<String> names=DexClasses.read(bytes.toByteArray());classes+=names.size();dexCount++;found.addAll(catalog.matcher.code(names));
                    }catch(IOException e){issues.put(f.getName()+":"+z.getName()+":"+e.getMessage());}
                }}
            }catch(Exception e){part.put("read_error",e.getClass().getSimpleName());issues.put(f.getName()+":"+e.getClass().getSimpleName());}
        }
        for(Integer id:found)hits.put(catalog.tracker(id).put("evidence","DEX_DEFINED_CLASS_SIGNATURE").put("execution_observed",false));
        return EventStore.object("status",issues.length()>0?"PARTIAL":"COMPLETE_WITHIN_SCOPE","parts",parts,"trackers",hits,"classes_examined",classes,"dex_files_examined",dexCount,"issues",issues,"catalog_revision",catalog.revision(),"observed_ms",System.currentTimeMillis(),"notice","Signature de classes présente : cela ne prouve ni son exécution ni l’envoi de données. Aucun résultat n’est une certification de sécurité.");
    }
    public void export(Writer writer)throws Exception{
        writer.write("[");boolean first=true;try(Cursor c=getReadableDatabase().rawQuery("SELECT package,payload FROM evidence ORDER BY package",null)){while(c.moveToNext()){if(!first)writer.write(",");writer.write(new JSONObject(c.getString(1)).put("package_name",c.getString(0)).toString());first=false;}}writer.write("]");
    }
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    static JSONObject certificates(PackageInfo p)throws Exception{
        Signature[] current=null,history=null;
        if(android.os.Build.VERSION.SDK_INT>=28&&p.signingInfo!=null){current=p.signingInfo.getApkContentsSigners();if(!p.signingInfo.hasMultipleSigners())history=p.signingInfo.getSigningCertificateHistory();}else current=p.signatures;
        JSONArray now=new JSONArray(),past=new JSONArray();if(current!=null)for(Signature s:current)now.put(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));if(history!=null)for(Signature s:history)past.put(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
        return EventStore.object("current_sha256",now,"history_sha256",past,"source","PackageManager signing certificates","identity_verified_against_publisher",false);
    }
}
