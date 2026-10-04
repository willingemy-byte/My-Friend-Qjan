package fr.erick.journallocal;

import java.util.Map;

/** Regression cases for profile isolation, fixed permissions and reversible Shell actions. */
public final class PermissionControlRulesTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        String dump="Packages:\n  Package [com.example.app] (abc):\n    install permissions:\n      android.permission.CAMERA: granted=true\n"
            +"    User 0: installed=true\n      runtime permissions:\n        android.permission.CAMERA: granted=true, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED]\n"
            +"        android.permission.RECORD_AUDIO: granted=true, flags=[ SYSTEM_FIXED|GRANTED_BY_DEFAULT]\n"
            +"        android.permission.READ_CONTACTS: granted=true, flags=[ POLICY_FIXED]\n"
            +"    User 150: installed=true\n      runtime permissions:\n        android.permission.CAMERA: granted=false, flags=[ USER_FIXED]\n"
            +"  Package [com.other.app] (def):\n    User 0: installed=true\n      runtime permissions:\n        android.permission.CAMERA: granted=false, flags=[ USER_FIXED]\n";
        Map<String,PermissionControlRules.Grant> states=PermissionControlRules.runtime(dump,"com.example.app",0);
        check(states.size()==3,"Select only the exact package and user");
        check(states.get("android.permission.CAMERA").granted,"Do not use another profile's denied state");
        check(!states.get("android.permission.CAMERA").fixed(),"USER_SET is not a system lock");
        check(states.get("android.permission.RECORD_AUDIO").fixed(),"SYSTEM_FIXED must block Shell revocation");
        check(states.get("android.permission.READ_CONTACTS").fixed(),"POLICY_FIXED must block Shell revocation");
        check(PermissionControlRules.runtime(dump,"com.missing.app",0).isEmpty(),"Unknown package cannot borrow another package's flags");
        check(PermissionControlRules.runtime(dump,"com.example.app",10).isEmpty(),"Unknown profile cannot fall back to user zero");
        check(!PermissionControlRules.runtime(dump,"com.example.app",150).get("android.permission.CAMERA").granted,"Secure profile is independently parsed");
        check(PermissionControlRules.runtimeReason(1,true,states.get("android.permission.CAMERA"),"").isEmpty(),"Granted runtime permission with observed flags is eligible");
        check(!PermissionControlRules.runtimeReason(1,true,null,"").isEmpty(),"Missing Shell flags cannot be treated as unlocked");
        check(!PermissionControlRules.runtimeReason(2,true,states.get("android.permission.CAMERA"),"").isEmpty(),"Signature permissions cannot be revoked as runtime");
        check(!PermissionControlRules.runtimeReason(0,true,states.get("android.permission.CAMERA"),"").isEmpty(),"Normal permissions cannot be revoked as runtime");
        check(!PermissionControlRules.runtimeReason(1,true,new PermissionControlRules.Grant(false,""),"").isEmpty(),"Disagreement between Shell and Android cannot authorize a mutation");
        check(!PermissionControlRules.runtimeReason(1,true,states.get("android.permission.CAMERA"),"essential role").isEmpty(),"Essential roles stay protected");
        check(!PermissionControlRules.runtimeReason(1,true,states.get("android.permission.RECORD_AUDIO"),"").isEmpty(),"Fixed flag beats runtime type");
        check(!PermissionControlRules.runtimeReason(1,false,new PermissionControlRules.Grant(false,""),"").isEmpty(),"Already denied permission has no grant inverse generated");
        check(PermissionControlRules.flags(" USER_SET|USER_FIXED ").equals(PermissionControlRules.flags("USER_FIXED | USER_SET")),"Order of flags cannot create a spurious state change");
        check(!PermissionControlRules.name("com.example.app;pm grant"),"Shell separators are rejected");
        check(!PermissionControlRules.name("com.example.$(id)"),"Shell substitution is rejected");
        check(!PermissionControlRules.name("com.example.app\n"),"Trailing newline is rejected");
        check(PermissionControlRules.packageName("android"),"The Android package must remain visible for read-only review");
        check(PermissionControlRules.command("com.example.app",0,"permission","android.permission.CAMERA","revoke").equals("pm revoke --user 0 'com.example.app' 'android.permission.CAMERA'"),"Command is scoped to the exact profile");
        boolean rejected=false;try{PermissionControlRules.command("com.example.app",0,"appop","android.permission.INTERNET","ignore");}catch(IllegalArgumentException e){rejected=true;}
        check(rejected,"Unsupported AppOps must not be guessed from a permission name");
        PermissionControlRules.Op op=PermissionControlRules.appOpState("SYSTEM_ALERT_WINDOW: allow; time=+3m", "SYSTEM_ALERT_WINDOW",0,true);
        check("allow".equals(op.mode)&&!op.uidScope,"Package-scoped AppOp can be observed");
        check(PermissionControlRules.appOpState("Uid mode: SYSTEM_ALERT_WINDOW: allow\nNo operations.","SYSTEM_ALERT_WINDOW",0,true).uidScope,"UID overrides cannot be silently changed as package operations");
        check(PermissionControlRules.appOpState("SYSTEM_ALERT_WINDOW: allow", "SYSTEM_ALERT_WINDOW",0,false).mode.isEmpty(),"Truncated command output is not evidence");
        check(PermissionControlRules.appOpState("SYSTEM_ALERT_WINDOW: allow", "SYSTEM_ALERT_WINDOW",1,true).mode.isEmpty(),"A failed observation is not a valid mode");
        check("default".equals(PermissionControlRules.appOpState("No operations.","SYSTEM_ALERT_WINDOW",0,true).mode),"Explicit empty state is the package default mode");
        check("default".equals(PermissionControlRules.appOpState("No operations.\nDefault mode: allow","SYSTEM_ALERT_WINDOW",0,true).mode),"Preserve an absent override rather than turning default allow into an explicit allow grant");
        check(PermissionControlRules.revokeOrder("android.permission.ACCESS_BACKGROUND_LOCATION")<PermissionControlRules.revokeOrder("android.permission.ACCESS_FINE_LOCATION"),"Revoke background first so inverse restoration satisfies foreground dependency");
        check(PermissionControlRules.revokeOrder("android.permission.ACCESS_FINE_LOCATION")<PermissionControlRules.revokeOrder("android.permission.ACCESS_COARSE_LOCATION"),"Restore coarse location before precise location");
        check("unconfirmed_change".equals(PermissionControlRules.outcome(1,true)),"Observed change after a failed command is never reported as confirmed");
        check("no_effect".equals(PermissionControlRules.outcome(0,false)),"Zero exit code alone cannot prove a removal");
        check(PermissionControlRules.restoreAllowed("confirmed",true,"denied","denied"),"Confirmed unchanged removal is restorable");
        check(!PermissionControlRules.restoreAllowed("confirmed",false,"denied","denied"),"APK replacement blocks restoration");
        check(!PermissionControlRules.restoreAllowed("confirmed",true,"granted","denied"),"Later user changes are not overwritten");
        check(!PermissionControlRules.restoreAllowed("refused",true,"denied","denied"),"Failed changes cannot trigger a blind grant");
        check(!PermissionControlRules.fresh(99,100,300),"Backward clock change invalidates a plan");
        check(!PermissionControlRules.fresh(401,100,300),"Expired plan is rejected");
        check(PermissionControlRules.fresh(400,100,300),"Valid plan remains usable until its deadline");
        System.out.println("Permission control: "+checks+" checks passed");
    }
}
