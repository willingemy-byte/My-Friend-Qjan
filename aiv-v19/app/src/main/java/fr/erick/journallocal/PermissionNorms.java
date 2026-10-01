package fr.erick.journallocal;
import android.content.Context;
import android.content.pm.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Explicit app/category selection. No automatic categorization, grant or data deletion. */
final class PermissionNorms {
    static JSONObject preview(Context c,String pkg,String profile)throws Exception {
        if(pkg==null||!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")||pkg.equals(c.getPackageName()))throw new IllegalArgumentException("Paquet invalide ou application AIV");
        JSONObject policy;try(InputStream in=c.getAssets().open("permission-baselines.json")){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);policy=new JSONObject(new String(b.toByteArray(),StandardCharsets.UTF_8));}
        if(!"aiv-permission-baselines/1".equals(policy.getString("schema")))throw new IllegalArgumentException("Normes invalides");
        JSONObject norm=policy.getJSONObject("profiles").getJSONObject(profile);
        PackageManager pm=c.getPackageManager();PackageInfo app=pm.getPackageInfo(pkg,PackageManager.GET_PERMISSIONS);
        if(!ShizukuCleanup.controlTargetAllowed(c,pkg))throw new IllegalArgumentException("Cible système, UID partagé ou autre profil : intervention refusée");
        Set<String> denied=new HashSet<>();JSONArray names=norm.getJSONArray("denied_permissions");for(int i=0;i<names.length();i++)denied.add(names.getString(i));
        List<String> ordered=new ArrayList<>();if(app.requestedPermissions!=null)Collections.addAll(ordered,app.requestedPermissions);Collections.sort(ordered);
        JSONArray changes=new JSONArray(),unavailable=new JSONArray();StringBuilder state=new StringBuilder(pkg).append('|').append(profile).append('|').append(app.versionCode).append('|').append(norm.toString());
        for(String name:ordered){boolean granted=pm.checkPermission(name,pkg)==PackageManager.PERMISSION_GRANTED;state.append('|').append(name).append('=').append(granted);if(!granted||!denied.contains(name))continue;
            PermissionInfo info=pm.getPermissionInfo(name,0);
            if((info.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE)!=PermissionInfo.PROTECTION_DANGEROUS){unavailable.put(name);continue;}
            changes.put(EventStore.object("permission",name,"label",String.valueOf(info.loadLabel(pm)),"before","granted","proposed","revoke","reason","Hors de la norme choisie explicitement pour cette application"));
        }
        String stamp=ChainStore.hex(MessageDigest.getInstance("SHA-256").digest(state.toString().getBytes(StandardCharsets.UTF_8)));
        return EventStore.object("package",pkg,"profile",profile,"label",norm.getString("label"),"stamp",stamp,"changes",changes,"not_runtime_revocable",unavailable,"scope","Permissions runtime seulement; ni effacement, ni désinstallation, ni réinitialisation du téléphone");
    }
}
