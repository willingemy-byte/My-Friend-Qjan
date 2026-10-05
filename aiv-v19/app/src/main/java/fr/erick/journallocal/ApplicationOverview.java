package fr.erick.journallocal;

import org.json.*;
import java.text.Collator;
import java.util.*;

/** One completed inventory, one category per package, and the grades from that same scan. */
final class ApplicationOverview {
    static final String[] CATEGORIES={"Android","Système","Utilisateur"};
    final long scan;
    private final Map<String,List<JSONObject>> groups=new LinkedHashMap<>();
    private final Map<String,int[]> counts=new LinkedHashMap<>();

    ApplicationOverview(JSONObject inventory,JSONObject grades)throws JSONException {
        scan=inventory.optLong("scan_id");
        for(String category:CATEGORIES){groups.put(category,new ArrayList<>());counts.put(category,new int[6]);}
        Set<String> seen=new HashSet<>();JSONArray apps=inventory.optJSONArray("apps");
        if(apps!=null)for(int i=0;i<apps.length();i++){
            JSONObject source=apps.optJSONObject(i);if(source==null)continue;
            String pkg=source.optString("package");if(pkg.isEmpty()||!seen.add(pkg))continue;
            JSONObject app=new JSONObject(source.toString());int level=grades.optInt(pkg,0);if(level<1||level>5)level=0;
            String category=category(app);app.put("level",level).put("category",category);
            groups.get(category).add(app);counts.get(category)[level]++;
        }
        Collator names=Collator.getInstance(Locale.CANADA_FRENCH);names.setStrength(Collator.PRIMARY);
        for(List<JSONObject> group:groups.values())group.sort((a,b)->{
            int grade=Integer.compare(b.optInt("level"),a.optInt("level"));if(grade!=0)return grade;
            int label=names.compare(a.optString("label",a.optString("package")),b.optString("label",b.optString("package")));
            return label!=0?label:a.optString("package").compareTo(b.optString("package"));
        });
    }
    static String category(JSONObject app){
        int uid=app.optInt("uid",-1);
        if(uid<0||uid%100000<10000||app.optInt("uid_package_count",0)!=1||!app.optBoolean("attribution_unique"))return "Android";
        return app.optBoolean("system_app")||app.optBoolean("updated_system_app")?"Système":"Utilisateur";
    }
    List<JSONObject> apps(String category){List<JSONObject> result=groups.get(category);return result==null?Collections.emptyList():Collections.unmodifiableList(result);}
    int count(String category,int level){int[] values=counts.get(category);return values==null||level<0||level>5?0:values[level];}
    int total(){int result=0;for(List<JSONObject> group:groups.values())result+=group.size();return result;}
}
