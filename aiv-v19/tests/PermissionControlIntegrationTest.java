package fr.erick.journallocal;

import android.content.Context;
import android.content.pm.*;
import org.json.*;
import java.io.*;

public final class PermissionControlIntegrationTest {
    static final String BACK="android.permission.ACCESS_BACKGROUND_LOCATION",CAMERA="android.permission.CAMERA",OVERLAY="android.permission.SYSTEM_ALERT_WINDOW";
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void waitForJob()throws Exception{long end=System.currentTimeMillis()+30000;while(PermissionControl.running()&&System.currentTimeMillis()<end)Thread.sleep(10);check(!PermissionControl.running(),"job completed within bound");}
    static void app(Context c,String pkg,int uid,String... permissions){PackageInfo p=new PackageInfo();p.packageName=pkg;p.requestedPermissions=permissions;p.applicationInfo=new ApplicationInfo();p.applicationInfo.uid=uid;p.applicationInfo.label=pkg;c.pm.apps.put(pkg,p);for(String permission:permissions){c.pm.grants.put(pkg+"|"+permission,true);c.pm.flags.put(pkg+"|"+permission,"USER_SET");}}
    public static void main(String[] args)throws Exception{
        Context c=new Context(new File(args[0]),new File(args[1]));ControlShell.context=c;
        for(int i=0;i<51;i++)app(c,"com.example.app"+i,11000+i,BACK,CAMERA);
        app(c,"com.example.core",12000,BACK);c.pm.guards.put("com.example.core","Protection AIV : clavier actif");
        app(c,"com.example.fixed",13000,BACK);c.pm.flags.put("com.example.fixed|"+BACK,"SYSTEM_FIXED");
        app(c,"com.example.shared",14000,BACK);c.pm.guards.put("com.example.shared","Portée UID partagé : groupe");
        app(c,"com.example.unknown",15000,BACK);
        app(c,"com.example.special",16000,OVERLAY);c.pm.ops.put("com.example.special","allow");
        app(c,"com.example.uidop",17000,OVERLAY);c.pm.ops.put("com.example.uidop","uid");
        PermissionControl.startReview(c);waitForJob();JSONObject review=PermissionControl.review(c);
        check(review.getInt("scanned")==57&&review.getInt("total")==57,"complete installed inventory");
        check(review.getInt("android_locked")==1,"Android lock identified");
        check(review.getInt("proposed")==52,"global proposals without per-app setup");
        check(ControlShell.mutations==0,"analysis is read-only");
        PermissionControl.setIncludeProtected(c,true);JSONObject expert=PermissionControl.previewReview(c);
        check(expert.getInt("rights")==53,"AIV protection can be included without changing Android locks or UID scope");
        PermissionControl.setIncludeProtected(c,false);PermissionControl.setReviewGroup(c,"capture",true);
        check(PermissionControl.previewReview(c).getInt("rights")==103,"one global policy adds cameras for every eligible app");
        PermissionControl.setProfile(c,"com.example.app0","flashlight");
        check(PermissionControl.previewReview(c).getInt("rights")==102,"fresh explicit profile overrides cached policy");
        PermissionControl.setReviewGroup(c,"capture",false);JSONObject plan=PermissionControl.previewReview(c);
        check(plan.getJSONArray("batches").length()==2&&plan.getInt("rights")==52,"all apps retained across 50-app batch boundary");
        c.pm.apps.get("com.example.app0").versionCode++;c.pm.guards.put("com.example.app1","Protection AIV : nouveau rôle");
        PermissionControl.applyReview(c,plan.getString("stamp"));waitForJob();JSONObject applied=PermissionControl.lastReport(c);
        check(applied.getInt("changed")==50&&applied.getInt("failed")==2,"changed APK and new role excluded immediately before action");
        check(ControlShell.mutations==50,"only verified targets mutated");
        check(PermissionControl.reportPage(c,0).getInt("total")==52&&PermissionControl.reportPage(c,50).getJSONArray("rows").length()==2,"receipt pagination retains final commands");
        check(c.pm.checkPermission(BACK,"com.example.fixed")==0&&c.pm.checkPermission(BACK,"com.example.shared")==0&&c.pm.checkPermission(BACK,"com.example.unknown")==0,"locked, grouped and unobserved rights unchanged");
        PermissionControl.restore(c);waitForJob();check(PermissionControl.lastReport(c).getInt("restored")==50,"restore traverses every executed batch in reverse");
        check(c.pm.ops.get("com.example.special").equals("allow"),"raw AppOp mode restored");
        check(PermissionControl.reportPage(c,0).getInt("total")==50,"global restore receipts visible");
        StringWriter export=new StringWriter();PermissionControl.export(c,export);JSONObject exported=new JSONObject(export.toString());
        check(exported.getJSONArray("analysis_snapshots").length()==57,"export contains exact Shell analysis snapshots for all apps");
        PermissionControl.startReview(c);waitForJob();JSONObject cancellation=PermissionControl.previewReview(c);ControlShell.mutations=0;ControlShell.cancelAfter=1;
        PermissionControl.applyReview(c,cancellation.getString("stamp"));waitForJob();JSONObject stopped=PermissionControl.lastReport(c);
        check(stopped.getString("phase").equals("interrupted")&&stopped.getInt("changed")==1&&ControlShell.mutations==1,"stop preserves confirmed count and does not start next mutation");
        boolean consumed=false;try{PermissionControl.applyReview(c,cancellation.getString("stamp"));}catch(IllegalStateException e){consumed=true;}check(consumed,"consumed global plan cannot execute twice");
        ControlShell.cancelAfter=0;PermissionControl.restore(c);waitForJob();check(PermissionControl.lastReport(c).getInt("restored")==1,"interrupted global plan remains restorable");
        System.out.println("PermissionControl integration: "+checks+" checks passed");
    }
}
