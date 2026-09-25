package fr.erick.journallocal;

import android.content.Context;
import android.database.Cursor;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.json.*;

/** A reproducible estimate, never a claim to have read the Android Settings UI. */
public final class LocalReference {
    private final JSONObject calibration;
    public LocalReference(Context context)throws Exception{
        try(InputStream in=context.getAssets().open("reference-calibration.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            calibration=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
        // A later imported derived reference overrides the bundled calibration, per package.
        try(Cursor c=EventStore.get(context).getReadableDatabase().rawQuery("SELECT package_name,payload FROM reference_apps WHERE source='user'",null)){
            while(c.moveToNext()){
                JSONObject r=new JSONObject(c.getString(1));
                if(r.optString("reference_basis").startsWith("derived_") && r.has("permission_meta"))calibration.put(c.getString(0),r);
            }
        }
    }
    public JSONObject derive(JSONObject app)throws Exception{
        JSONObject seed=calibration.optJSONObject(app.getString("package_name"));
        HashSet<String> visibleSeed=new HashSet<>();JSONObject levels=new JSONObject();
        if(seed!=null){JSONArray names=seed.optJSONArray("declared_permissions"),meta=seed.optJSONArray("permission_meta");
            if(names!=null)for(int i=0;i<names.length();i++)visibleSeed.add(names.getString(i));
            if(meta!=null)for(int i=0;i<meta.length();i++){JSONObject q=meta.getJSONObject(i);levels.put(q.getString("name"),q.optInt("protection_level",-1));}
        }
        JSONArray visible=new JSONArray(),hidden=new JSONArray(),meta=new JSONArray();HashSet<String> seen=new HashSet<>();
        JSONArray permissions=app.getJSONArray("permissions");int fallback=0;
        for(int i=0;i<permissions.length();i++){
            JSONObject q=permissions.getJSONObject(i);String name=q.getString("name");if(!seen.add(name))continue;
            int level=q.optInt("protection_level",-1);meta.put(EventStore.object("name",name,"protection_level",level));boolean exposed;
            if(levels.has(name)&&levels.getInt(name)==level)exposed=visibleSeed.contains(name);
            else {fallback++;exposed=name.startsWith("android.permission.")&&level>=0&&((level&15)==0||(level&15)==1);}
            (exposed?visible:hidden).put(name);
        }
        return EventStore.object("package_name",app.getString("package_name"),"version_code",app.optLong("version_code"),
            "reference_version_code",app.optLong("version_code"),"permission_meta",meta,"declared_permissions",visible,"hidden_candidates",hidden,
            "visible_permission_count",visible.length(),"manifest_permission_count",seen.size(),"hidden_candidate_count",hidden.length(),
            "permissions_complete",true,"estimated",true,"fallback_permission_count",fallback,
            "reference_basis","derived_local_calibrated_v1","reference_method","local_calibration_and_protection_v1",
            "observed_ms",app.optLong("observed_ms"),"reference_note","Estimation locale : calibration du référentiel V6, puis niveaux normal/dangereux Android pour les permissions nouvelles ou modifiées. Ne constitue pas une lecture des réglages Android.");
    }
}