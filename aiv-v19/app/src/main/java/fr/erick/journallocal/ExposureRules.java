package fr.erick.journallocal;

import org.json.*;
import java.util.*;
import java.util.regex.Pattern;

/** Native port of frontend/script-01.js PenaltyModel.exposure, including evidence-bound A1/A2. */
final class ExposureRules {
    private static final Set<String> AUTONOMOUS=new HashSet<>(Arrays.asList("RECEIVE_BOOT_COMPLETED","READ_LOGS","READ_PHONE_STATE","PACKAGE_USAGE_STATS"));
    private static final Pattern AUTONOMY=Pattern.compile("à votre insu|sans votre (intervention|confirmation)|without (your|user) (knowledge|confirmation|interaction)|without asking",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final Pattern NEGATION=Pattern.compile("ne permet pas|does not allow",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private ExposureRules(){}
    private static JSONObject object(JSONObject parent,String key){JSONObject result=parent==null?null:parent.optJSONObject(key);return result==null?new JSONObject():result;}
    private static Set<String> exact(JSONObject value,JSONObject app){
        Object count=value.opt("count"),version=value.opt("version_code"),appVersion=app.opt("version_code");JSONArray names=value.optJSONArray("permission_names");
        if(!"permissions".equals(value.optString("kind"))||!Boolean.TRUE.equals(value.opt("same_version"))||!(version instanceof Number)||!(appVersion instanceof Number)||((Number)version).doubleValue()!=((Number)appVersion).doubleValue()||!(count instanceof Number)||value.optString("source").trim().isEmpty()||names==null)return null;
        Set<String> result=new LinkedHashSet<>();for(int i=0;i<names.length();i++){Object name=names.opt(i);if(!(name instanceof String))return null;result.add((String)name);}
        return result.size()==((Number)count).doubleValue()?result:null;
    }
    static JSONObject assess(JSONObject app,JSONObject reference)throws JSONException{
        Map<String,JSONObject> permissions=new LinkedHashMap<>();JSONArray input=app.optJSONArray("permissions");
        if(input!=null)for(int i=0;i<input.length();i++){
            Object raw=input.opt(i);JSONObject permission=raw instanceof JSONObject?(JSONObject)raw:raw instanceof String?new JSONObject().put("name",raw):null;
            if(permission!=null&&!permission.optString("name").isEmpty())permissions.put(permission.optString("name"),permission);
        }
        JSONObject visibility=object(object(reference,"penalty_evidence"),"visibility"),principalEvidence=object(visibility,"principal"),allEvidence=object(visibility,"toutes");
        Set<String> principal=exact(principalEvidence,app),all=exact(allEvidence,app);
        boolean consistent=(principal==null||all==null||all.containsAll(principal))&&(principal==null||permissions.keySet().containsAll(principal))&&(all==null||permissions.keySet().containsAll(all));
        boolean full=consistent&&"google_play".equals(principalEvidence.optString("surface"))&&principal!=null&&principal.equals(permissions.keySet());
        int level=full?1:0,unclassified=0;JSONArray findings=new JSONArray();JSONObject counts=new JSONObject();for(int n=1;n<=5;n++)counts.put(String.valueOf(n),0);
        for(JSONObject permission:permissions.values()){
            String name=permission.optString("name"),shortName=name.startsWith("android.permission.")?name.substring(19):"",reason="",source="";int n=0;
            if(consistent&&principal!=null&&principal.contains(name)&&"google_play".equals(principalEvidence.optString("surface"))){n=1;reason="Permission présente dans la liste Google Play fournie pour cette version.";source=principalEvidence.optString("source");}
            if(consistent&&principal!=null&&all!=null&&!principal.contains(name)&&all.contains(name)){n=2;reason="Présente dans Toutes les autorisations, absente de la liste principale fournie pour cette version.";source=allEvidence.optString("source");}
            JSONObject catalog=permission.optJSONObject("bayton");String phone=permission.optString("description"),catalogText=catalog==null?"":catalog.optString("description");
            String[] descriptions={phone,catalogText},sources={"Description Android du téléphone","Bayton / AOSP · API "+(catalog==null?"?":catalog.optString("reference_api_level","?"))};
            for(int i=0;i<descriptions.length;i++)if(AUTONOMY.matcher(descriptions[i]).find()&&!NEGATION.matcher(descriptions[i]).find()){n=Math.max(n,3);reason=descriptions[i];source=sources[i];}
            if(AUTONOMOUS.contains(shortName)&&n<3){n=3;reason="Capacité de fonctionnement ou d’observation sans action immédiate dans l’application; usage non démontré.";source="Règle AIV V22 · définition Android";}
            for(int i=0;i<descriptions.length;i++)if(DefenseRules.level("",descriptions[i],"")==4){n=Math.max(n,4);reason=descriptions[i];source=sources[i];}
            if(DefenseRules.level(name,"","")==5){n=5;reason="Permission permettant de modifier le système, la configuration ou le fonctionnement d’autres applications.";source="Règle AIV V22 · définition Android";}
            if(n==0)unclassified++;
            else{
                Object granted=permission.opt("granted");counts.put(String.valueOf(n),counts.optInt(String.valueOf(n))+1);
                findings.put(new JSONObject().put("permission",name).put("level",n).put("reason",reason).put("source",source).put("granted",granted==null?JSONObject.NULL:granted)
                    .put("effective_status",Boolean.FALSE.equals(granted)?"NON_ACCORDEE":Boolean.TRUE.equals(granted)?"ACCORD_ANDROID_APP_OPS_NON_VERIFIES":"INCONNU").put("operation_observed",false));
            }
            level=Math.max(level,n);
        }
        if(level==1&&!full)level=0;
        return new JSONObject().put("schema","aiv-native-exposure/1").put("level",level).put("findings",findings).put("counts",counts).put("unclassified_permissions",unclassified)
            .put("disclosure_verified",full).put("visibility_status",!consistent?"CONTRADICTOIRE":full?"LISTES_CONCORDANTES":principal!=null?"PARTIELLE":"INCONNUE")
            .put("scope","Grade maximal établi parmi les permissions déclarées. Refus et usages effectifs sont distincts du grade; aucune action ou malveillance n’est déduite.");
    }
}
