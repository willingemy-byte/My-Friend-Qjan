package fr.erick.journallocal;
import android.content.*;
import android.net.Uri;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** No WorkManager is bundled in 0.5; no scheduler or HTTP client is introduced.
 * Explicit SAF import is the offline synchronization transport. A full source
 * snapshot replaces only that source, with cursor and data committed together. */
public final class ReferenceSync {
    public static JSONObject importSnapshot(Context context,Uri uri)throws Exception{
        byte[] bytes;try(InputStream in=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("Source indisponible");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>16*1024*1024)throw new IOException("Référentiel >16 Mio");out.write(b,0,n);}bytes=out.toByteArray();
        }
        JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));if(!"aiv-reference/1".equals(root.getString("schema")))throw new IllegalArgumentException("Schéma attendu : aiv-reference/1");
        String source=root.getString("source");if(!source.matches("bayton|exodus|user"))throw new IllegalArgumentException("Source invalide");JSONArray apps=root.getJSONArray("apps");if(apps.length()>20000)throw new IllegalArgumentException("Trop de paquets");
        SQLiteDatabase db=EventStore.get(context).getWritableDatabase();long now=System.currentTimeMillis();db.beginTransaction();try{
            db.delete("reference_apps","source=?",new String[]{source});
            for(int i=0;i<apps.length();i++){JSONObject app=apps.getJSONObject(i);String pkg=app.getString("package_name");if(app.toString().length()>256*1024)throw new IllegalArgumentException("Fiche trop longue");if(app.has("expected_ips")&&app.getJSONArray("expected_ips").length()>100)throw new IllegalArgumentException("Trop d’adresses attendues");if(!pkg.equals("android")&&!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("Paquet invalide");
                // Only explicit user data can establish a personal expected-IP list.
                if(!source.equals("user")&&app.has("expected_ips"))throw new IllegalArgumentException("expected_ips réservé à la source user");
                ContentValues v=new ContentValues();v.put("package_name",pkg);v.put("declared_permissions",app.optJSONArray("declared_permissions")==null?"[]":app.getJSONArray("declared_permissions").toString());v.put("certificates",app.optJSONArray("certificates")==null?"[]":app.getJSONArray("certificates").toString());v.put("source",source);v.put("fetched_ms",now);v.put("payload",app.toString());db.insertOrThrow("reference_apps",null,v);
            }
            db.execSQL("INSERT OR IGNORE INTO sync_state(source,status) VALUES(?,'OFFLINE_NO_SYNC')",new Object[]{source});db.execSQL("UPDATE sync_state SET last_sync_ms=?,cursor=?,status='IMPORTED_LOCAL_UNVERIFIED_SOURCE' WHERE source=?",new Object[]{now,root.optString("cursor",""),source});
            long t=System.currentTimeMillis();JSONObject e=EventStore.object("timestamp_ms",t,"app","Utilisateur","action","Import référentiel AIV","destination",source,"transport","Interne","category","aiv-reference","details",EventStore.object("sha256",ChainStore.hex(ChainStore.digest().digest(bytes)),"count",apps.length(),"source",source));
            ContentValues v=new ContentValues();v.put("timestamp_ms",t);v.put("app","Utilisateur");v.put("action","Import référentiel AIV");v.put("destination",source);v.put("transport","Interne");v.put("category","aiv-reference");v.put("payload",e.toString());v.put("search_text",e.toString().toLowerCase(java.util.Locale.ROOT));db.insertOrThrow("events",null,v);db.setTransactionSuccessful();
        }finally{db.endTransaction();}return EventStore.object("ok",true,"apps",apps.length(),"source",source,"network_requests",0);
    }
}