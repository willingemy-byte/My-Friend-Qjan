import fr.erick.journallocal.DefenseRules;
import java.util.*;

public final class DefenseV23Test {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args){
        check(DefenseRules.level("android.permission.INTERACT_ACROSS_USERS",null,null)==5,"MobileWips cross-user capability");
        check(DefenseRules.level("android.permission.LOCAL_MAC_ADDRESS",null,null)==0,"Signature-only permission is not automatically L5");
        check(DefenseRules.level("android.permission.WRITE_SETTINGS ",null,null)==0,"Do not repair raw malformed names into privileges");
        check(DefenseRules.level("android.permission.REQUEST_INSTALL_PACKAGES","A malicious application may install packages.",null)==4,"Android warning selects L4");
        check(DefenseRules.level("android.permission.INTERNET","Accès réseau normal",null)==0,"Internet alone does not enter cleanup");
        check(DefenseRules.level("android.permission.WRITE_SETTINGS","malicious",null)==5,"Highest level wins");
        check(DefenseRules.mayRequestUninstall(5,true,false,10100,1,false),"Ordinary user app can open confirmation");
        check(!DefenseRules.mayRequestUninstall(5,true,true,10100,1,false),"System app protected");
        check(!DefenseRules.mayRequestUninstall(5,true,false,10100,2,false),"Shared UID protected");
        check(!DefenseRules.mayRequestUninstall(5,true,false,1000,1,false),"System UID protected");
        check(!DefenseRules.mayRequestUninstall(5,true,false,-1,1,false),"Unknown UID protected");
        check(!DefenseRules.mayRequestUninstall(5,true,false,10100,1,true),"Home, keyboard, default roles protected");
        check(!DefenseRules.mayRequestUninstall(3,true,false,10100,1,false),"Below threshold excluded");
        check(!DefenseRules.mayRequestUninstall(5,false,false,10100,1,false),"Disabled app not suggested for direct removal");
        check(DefenseRules.state(5,true,true,true).equals("KEPT"),"Keep decision");
        check(DefenseRules.state(5,true,false,true).equals("TO_REVIEW"),"Changed fingerprint reopens review");
        check(DefenseRules.state(5,false,true,true).equals("DISABLED"),"Disable observation independent of keep");
        check(DefenseRules.state(5,true,true,false).equals("NOT_RETURNED"),"Missing is not confirmed uninstallation");
        check(DefenseRules.removalOutcome(true,false,true).equals("UNINSTALL_CONFIRMED"),"Android success and absence together confirm removal");
        check(DefenseRules.removalOutcome(true,false,false).equals("UNINSTALL_UNCONFIRMED"),"Android success alone is insufficient");
        check(DefenseRules.removalOutcome(false,true,false).equals("UNINSTALL_CANCELLED"),"Cancel is never reported as success");
        check(DefenseRules.removalOutcome(false,false,true).equals("UNINSTALL_UNCONFIRMED"),"Absence alone is not a confirmed removal");
        check(DefenseRules.packageState(false,true,false,true).equals("ARCHIVED"),"Archiving removes APK without uninstalling app");
        check(DefenseRules.packageState(true,false,false,true).equals("ARCHIVED"),"Adding archived package is not installing an executable APK");
        check(DefenseRules.packageState(false,true,true,false).isEmpty(),"Replacement is not removal");
        check(DefenseRules.packageState(false,true,false,false).equals("REMOVED"),"Plain removal is distinct from archival");
        Map<String,String> before=new HashMap<>(),after=new HashMap<>();
        before.put("permission.a","denied");before.put("permission.b","granted");before.put("permission.c","granted");before.put("permission.d","granted");
        after.put("permission.a","granted");after.put("permission.b","denied");after.put("permission.e","granted");after.put("permission.d","unknown");
        Map<String,List<String>> diff=DefenseRules.changes(before,after);
        check(diff.get("added").equals(Arrays.asList("permission.e")),"Actual new permission");
        check(diff.get("removed").equals(Arrays.asList("permission.c")),"Removal differs from revocation");
        check(diff.get("granted").equals(Arrays.asList("permission.a")),"Grant increase detected");
        check(diff.get("revoked").equals(Arrays.asList("permission.b")),"Revocation detected");
        check(diff.get("state_unknown").equals(Arrays.asList("permission.d")),"Unknown state is not a revocation");
        check(DefenseRules.changes(after,after).values().stream().allMatch(List::isEmpty),"Unchanged observations do not generate changes");
        System.out.println("PASS V23 policy: L4/L5, essential roles, shared/system UID, malformed names, state transitions and permission diffs");
    }
}
