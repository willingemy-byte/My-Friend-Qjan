package fr.erick.journallocal;

import org.json.*;
import java.util.*;

/** Synthetic packages only. Counts and visible lists must describe exactly the same inventory. */
public final class ApplicationOverviewTest {
    private static int checks;
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    private static JSONObject app(String pkg,int uid,int peers,boolean system,boolean unique)throws Exception{
        return new JSONObject().put("package",pkg).put("label",pkg).put("uid",uid).put("uid_package_count",peers)
            .put("system_app",system).put("attribution_unique",unique).put("permissions",new JSONArray());
    }
    public static void main(String[] args)throws Exception{
        JSONArray apps=new JSONArray();JSONObject grades=new JSONObject();
        apps.put(app("android.reserved",1000,1,true,false));grades.put("android.reserved",5);
        apps.put(app("android.shared1",12001,2,true,false));grades.put("android.shared1",4);
        apps.put(app("android.shared2",12001,2,false,false));grades.put("android.shared2",3);
        apps.put(app("android.unknown",-1,0,false,false));
        apps.put(app("android.ambiguous",12002,1,false,false));grades.put("android.ambiguous",2);
        apps.put(app("system.unique",12003,1,true,true));grades.put("system.unique",5);
        apps.put(app("system.updated",12004,1,false,true).put("updated_system_app",true));grades.put("system.updated",4);
        apps.put(app("user.unique",12005,1,false,true));grades.put("user.unique",1);
        apps.put(app("user.otherprofile",112005,1,false,true));grades.put("user.otherprofile",2);
        apps.put(app("user.invalidgrade",12006,1,false,true));grades.put("user.invalidgrade",9);
        apps.put(app("user.unique",12005,1,false,true)); // Duplicate input must never double a count.
        apps.put(new JSONObject());apps.put(JSONObject.NULL);
        grades.put("stale.package",5); // A grade alone cannot invent an installed application.
        ApplicationOverview overview=new ApplicationOverview(new JSONObject().put("scan_id",42).put("apps",apps),grades);
        check(overview.scan==42&&overview.total()==10,"one snapshot, ten distinct nonempty packages");
        check(overview.apps("Android").size()==5,"reserved, shared and unknown attribution belong to Android");
        check(overview.apps("Système").size()==2,"unique system and updated system apps belong to Système");
        check(overview.apps("Utilisateur").size()==3,"unique non-system apps belong to Utilisateur");
        check(overview.count("Android",5)==1&&overview.count("Android",4)==1&&overview.count("Android",3)==1&&overview.count("Android",2)==1,"all observed grades retained");
        check(overview.count("Android",0)==1&&overview.count("Utilisateur",0)==1,"missing and invalid grades remain ungraded");
        Set<String> visible=new HashSet<>();
        for(String category:ApplicationOverview.CATEGORIES){
            int total=0;for(int grade=0;grade<=5;grade++)total+=overview.count(category,grade);
            check(total==overview.apps(category).size(),"distribution equals visible list for "+category);
            for(JSONObject row:overview.apps(category))check(visible.add(row.getString("package")),"no cross-category duplicate");
        }
        check("android.reserved".equals(overview.apps("Android").get(0).getString("package")),"highest grade appears first");
        check("android.unknown".equals(overview.apps("Android").get(4).getString("package")),"ungraded apps remain in visible list");
        check(overview.apps("missing").isEmpty()&&overview.count("missing",5)==0,"unknown category is empty");
        JSONArray many=new JSONArray();JSONObject manyGrades=new JSONObject();
        for(int i=0;i<543;i++){String pkg="com.example.app"+i;many.put(app(pkg,14000+i,1,false,true));manyGrades.put(pkg,i%5+1);}
        ApplicationOverview full=new ApplicationOverview(new JSONObject().put("apps",many),manyGrades);
        check(full.apps("Utilisateur").size()==543&&full.total()==543,"full category retained beyond the old 120-row cap");
        check(full.count("Utilisateur",1)==109&&full.count("Utilisateur",5)==108,"large inventory has exact grade counts");
        check(new ApplicationOverview(new JSONObject(),new JSONObject()).total()==0,"empty first launch is a valid empty inventory");
        System.out.println("ApplicationOverview: "+checks+" checks passed");
    }
}
