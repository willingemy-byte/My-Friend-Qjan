package fr.erick.journallocal;

import android.content.Context;
import android.content.pm.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;

/** Actual maintenance/controller and durable policy files, with simulated Android observations. */
public final class PermissionConsentTest {
    static final String PKG="com.example.chat",MIC="android.permission.RECORD_AUDIO",NOTIFY="android.permission.POST_NOTIFICATIONS",CAMERA="android.permission.CAMERA";
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void await()throws Exception{PermissionMaintenanceTest.await();}
    static void tick(Context c)throws Exception{ControlShell.context=c;PermissionMaintenance.request(c);await();}
    static JSONObject policy(Context c)throws Exception{return PermissionMaintenance.exportState(c).getJSONObject("policy");}
    static void app(Context c,String pkg,int uid,String... names){PermissionControlIntegrationTest.app(c,pkg,uid,names);for(String name:names)c.pm.grants.put(pkg+"|"+name,false);}
    static Context fixture(String root,String assets,String name)throws Exception{File files=new File(root,name);files.mkdirs();Context c=new Context(files,new File(assets));ControlShell.context=c;PermissionControl.setReviewGroup(c,"capture",true);PermissionControl.setReviewGroup(c,"notifications",true);return c;}
    static void prepare(Context c)throws Exception{ControlShell.context=c;PermissionMaintenance.prepare(c);await();}
    static void grant(Context c,String pkg,String name,String flags){c.pm.flags.put(pkg+"|"+name,flags);c.pm.grants.put(pkg+"|"+name,true);}
    public static void main(String[] args)throws Exception{
        Context c=fixture(args[0],args[1],"main");app(c,PKG,15000,MIC,NOTIFY,CAMERA);prepare(c);
        // Simulate an existing 2.0.13 baseline, which has no exceptions member.
        JSONObject old=policy(c);old.remove("exceptions");Files.writeString(new File(c.getFilesDir(),"permission-maintenance/policy.json").toPath(),old.toString());
        check(policy(c).getJSONObject("exceptions").length()==0,"legacy policy accepted without dropping baseline");
        grant(c,PKG,MIC,"USER_SET|USER_SENSITIVE_WHEN_GRANTED");grant(c,PKG,NOTIFY,"USER_SET");grant(c,PKG,CAMERA,"");int before=ControlShell.mutations;tick(c);
        check(c.pm.checkPermission(MIC,PKG)==0&&c.pm.checkPermission(NOTIFY,PKG)==0,"microphone and notifications granted by user remain granted");
        check(ControlShell.mutations==before+1&&c.pm.checkPermission(CAMERA,PKG)!=0,"another permission in the same application remains maintained");
        check(policy(c).getJSONObject("exceptions").length()==2&&policy(c).getJSONObject("targets").length()==1,"only the two permissions are excluded");
        check(PermissionMaintenance.state(c).getInt("user_exceptions")==2&&PermissionMaintenance.status().contains("2 autorisations conservées"),"persisted exceptions visible in maintenance status");
        JSONObject exception=policy(c).getJSONObject("exceptions").getJSONObject(PKG+"|"+MIC);
        check(exception.getJSONObject("observed_state").getBoolean("granted")&&exception.getJSONObject("owner").getInt("uid")==15000&&exception.getLong("excluded_ms")>0,"exception includes observation, exact owner and timestamp");
        check(exception.getString("reason").contains("auteur du dernier changement non déterminé"),"flags are not presented as proof of the latest actor");
        check(EventStore.receipts.stream().filter(r->"permission_user_exception".equals(r.optString("kind"))&&"excluded".equals(r.optString("phase"))).count()==2,"exceptions journaled without a Shell mutation");
        before=ControlShell.mutations;PermissionMaintenance.pause(c);PermissionMaintenance.resume(c);await();tick(c);check(ControlShell.mutations==before,"pause/resume does not undo consent");
        Context restarted=new Context(c.getFilesDir(),new File(args[1]));restarted.pm.apps.putAll(c.pm.apps);restarted.pm.grants.putAll(c.pm.grants);restarted.pm.flags.putAll(c.pm.flags);ControlShell.context=restarted;PermissionMaintenance.resume(restarted);await();tick(restarted);
        check(policy(restarted).getJSONObject("exceptions").length()==2&&restarted.pm.checkPermission(MIC,PKG)==0,"a fresh context reads durable exceptions");
        ControlShell.context=c;c.pm.grants.put(PKG+"|"+MIC,false);c.pm.grants.put(PKG+"|"+NOTIFY,false);prepare(c);
        check(!policy(c).getJSONObject("targets").has(PKG+"|"+MIC)&&!policy(c).getJSONObject("targets").has(PKG+"|"+NOTIFY),"adding current denials preserves previously excluded permissions");
        grant(c,PKG,MIC,"USER_SET");JSONObject plan=PermissionControl.preview(c,new JSONArray().put(EventStore.object("package",PKG,"permissions",new JSONArray().put(MIC))));PermissionControl.apply(c,plan.getString("stamp"));PermissionControlIntegrationTest.waitForJob();
        check(c.pm.checkPermission(MIC,PKG)!=0&&policy(c).getJSONObject("targets").has(PKG+"|"+MIC)&&!policy(c).getJSONObject("exceptions").has(PKG+"|"+MIC),"an explicit confirmed AIV withdrawal re-enables maintenance for that permission");
        grant(c,PKG,MIC,"USER_SET");tick(c);check(c.pm.checkPermission(MIC,PKG)==0&&policy(c).getJSONObject("exceptions").has(PKG+"|"+MIC),"a later explicit consent again takes precedence");
        c.pm.apps.get(PKG).versionCode++;c.pm.apps.get(PKG).lastUpdateTime++;prepare(c);tick(c);check(policy(c).getJSONObject("exceptions").length()==2&&c.pm.checkPermission(MIC,PKG)==0,"same-owner application update preserves exceptions");

        Context flags=fixture(args[0],args[1],"flags");String[] choices={"ONE_TIME","USER_FIXED","USER_SET","USER_SET_EXTRA","USER_SENSITIVE_WHEN_GRANTED"};
        for(int i=0;i<choices.length;i++)app(flags,"com.example.flag"+i,15100+i,MIC);app(flags,"com.example.fixed",15120,MIC);prepare(flags);for(int i=0;i<choices.length;i++)grant(flags,"com.example.flag"+i,MIC,choices[i]);before=ControlShell.mutations;tick(flags);
        check(ControlShell.mutations==before+2&&policy(flags).getJSONObject("exceptions").length()==3,"exact USER_SET/USER_FIXED/ONE_TIME flags honored without confusing unrelated flags");
        grant(flags,"com.example.fixed",MIC,"USER_SET|POLICY_FIXED");before=ControlShell.mutations;tick(flags);
        check(flags.pm.checkPermission(MIC,"com.example.fixed")==0&&!policy(flags).getJSONObject("exceptions").has("com.example.fixed|"+MIC)&&ControlShell.mutations==before,"Android policy lock is reported rather than misclassified as consent");

        Context race=fixture(args[0],args[1],"race");app(race,PKG,15200,MIC);prepare(race);grant(race,PKG,MIC,"");EventStore.pendingHook=()->race.pm.flags.put(PKG+"|"+MIC,"USER_SET");before=ControlShell.mutations;tick(race);
        check(ControlShell.mutations==before&&race.pm.checkPermission(MIC,PKG)==0&&policy(race).getJSONObject("exceptions").has(PKG+"|"+MIC),"consent arriving during receipt persistence is rechecked before any Shell revoke");
        check(EventStore.receipts.stream().anyMatch(r->"skipped_user_choice".equals(r.optString("phase"))),"cancelled mutation has a durable skipped receipt");

        Context disk=fixture(args[0],args[1],"disk");app(disk,PKG,15300,MIC);prepare(disk);grant(disk,PKG,MIC,"USER_SET");android.util.AtomicFile.failPolicy=true;before=ControlShell.mutations;tick(disk);android.util.AtomicFile.failPolicy=false;
        check(ControlShell.mutations==before&&disk.pm.checkPermission(MIC,PKG)==0&&!PermissionMaintenance.enabled(disk),"failed exception persistence pauses maintenance and never removes consent");
        PermissionMaintenance.resume(disk);await();check(policy(disk).getJSONObject("exceptions").has(PKG+"|"+MIC),"resume retries and persists the exception after disk recovery");

        Context journal=fixture(args[0],args[1],"journal");app(journal,PKG,15400,MIC);prepare(journal);grant(journal,PKG,MIC,"USER_SET");EventStore.writable=false;before=ControlShell.mutations;tick(journal);EventStore.writable=true;
        check(ControlShell.mutations==before&&journal.pm.checkPermission(MIC,PKG)==0&&!PermissionMaintenance.enabled(journal)&&policy(journal).getJSONObject("exceptions").has(PKG+"|"+MIC),"journal failure preserves the exception and pauses follow-up");

        Context owner=fixture(args[0],args[1],"owner");app(owner,PKG,15500,MIC);prepare(owner);grant(owner,PKG,MIC,"USER_SET");owner.pm.apps.get(PKG).signer="other-signer";owner.pm.apps.get(PKG).lastUpdateTime++;before=ControlShell.mutations;tick(owner);
        check(ControlShell.mutations==before&&!policy(owner).getJSONObject("exceptions").has(PKG+"|"+MIC)&&PermissionMaintenance.state(owner).getJSONObject("pending").has(PKG),"different owner requires review and cannot acquire the previous owner's exception");
        Context reinstall=fixture(args[0],args[1],"reinstall");app(reinstall,PKG,15600,MIC);prepare(reinstall);grant(reinstall,PKG,MIC,"USER_SET");tick(reinstall);reinstall.pm.apps.get(PKG).firstInstallTime++;reinstall.pm.apps.get(PKG).lastUpdateTime++;tick(reinstall);
        JSONObject pending=PermissionMaintenance.state(reinstall).getJSONObject("pending").getJSONObject(PKG);PermissionMaintenance.approve(reinstall,PKG,pending.getJSONObject("owner").getString("stamp"));
        check(!policy(reinstall).getJSONObject("exceptions").has(PKG+"|"+MIC),"approving a reinstalled application clears exceptions belonging to its previous installation");
        reinstall.pm.grants.put(PKG+"|"+MIC,false);prepare(reinstall);grant(reinstall,PKG,MIC,"");before=ControlShell.mutations;tick(reinstall);
        check(ControlShell.mutations==before+1&&reinstall.pm.checkPermission(MIC,PKG)!=0,"a new installation receives only its newly chosen policy");
        check(PermissionMaintenance.exportState(c).getJSONObject("policy").getJSONObject("exceptions").has(PKG+"|"+MIC),"export includes persisted exception details");
        System.out.println("Permission consent integration: "+checks+" checks passed");
    }
}
