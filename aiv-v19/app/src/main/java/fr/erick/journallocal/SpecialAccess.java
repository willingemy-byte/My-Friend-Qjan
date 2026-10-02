package fr.erick.journallocal;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import org.json.JSONObject;

/**
 * Read-only status for Android "Special app access" capabilities.
 *
 * This class never grants a special access. User-facing Settings screens remain
 * the authority for granting or revoking them.
 */
public final class SpecialAccess {
    private SpecialAccess(){}

    public static JSONObject status(Context context)throws Exception{
        Context c=context.getApplicationContext();
        PackageManager pm=c.getPackageManager();
        String pkg=c.getPackageName();

        JSONObject out=EventStore.object(
            "schema","aiv-special-access/1",
            "package",pkg,
            "query_all_packages",permissionGranted(pm,pkg,Manifest.permission.QUERY_ALL_PACKAGES),
            "visible_packages",visiblePackageCount(pm),
            "self_visible",selfVisible(pm,pkg),
            "usage_access",usageAccess(c,pkg),
            "vpn_prepared",VpnService.prepare(c)==null,
            "all_files_declared",declared(pm,pkg,"android.permission.MANAGE_EXTERNAL_STORAGE"),
            "all_files_granted",Build.VERSION.SDK_INT>=30 && Environment.isExternalStorageManager(),
            "overlay",Settings.canDrawOverlays(c),
            "write_settings",Settings.System.canWrite(c),
            "ignore_battery_optimizations",ignoresBatteryOptimizations(c,pkg),
            "can_request_package_installs",Build.VERSION.SDK_INT>=26 && pm.canRequestPackageInstalls(),
            "scope","État local exposé par Android. AIV n'accorde aucun accès spécial silencieusement."
        );

        try{
            JSONObject shizuku=ShizukuCleanup.state(c);
            out.put("shizuku_binder",shizuku.optBoolean("binder"));
            out.put("shizuku_authorized",shizuku.optBoolean("authorized"));
        }catch(Throwable t){
            out.put("shizuku_binder",false);
            out.put("shizuku_authorized",false);
            out.put("shizuku_error",t.getClass().getSimpleName());
        }
        return out;
    }

    private static boolean selfVisible(PackageManager pm,String pkg){
        try{return pm.getPackageInfo(pkg,0)!=null;}catch(Exception e){return false;}
    }

    private static int visiblePackageCount(PackageManager pm){
        try{return pm.getInstalledPackages(0).size();}catch(Exception e){return -1;}
    }

    private static boolean permissionGranted(PackageManager pm,String pkg,String permission){
        try{return pm.checkPermission(permission,pkg)==PackageManager.PERMISSION_GRANTED;}
        catch(Exception e){return false;}
    }

    private static boolean declared(PackageManager pm,String pkg,String permission){
        try{
            PackageInfo info=pm.getPackageInfo(pkg,PackageManager.GET_PERMISSIONS);
            if(info.requestedPermissions==null)return false;
            for(String name:info.requestedPermissions)if(permission.equals(name))return true;
        }catch(Exception ignored){}
        return false;
    }

    private static boolean usageAccess(Context c,String pkg){
        try{
            AppOpsManager ops=(AppOpsManager)c.getSystemService(Context.APP_OPS_SERVICE);
            if(ops==null)return false;
            int mode=ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),pkg);
            return mode==AppOpsManager.MODE_ALLOWED;
        }catch(Exception e){return false;}
    }

    private static boolean ignoresBatteryOptimizations(Context c,String pkg){
        try{
            PowerManager p=(PowerManager)c.getSystemService(Context.POWER_SERVICE);
            return p!=null&&p.isIgnoringBatteryOptimizations(pkg);
        }catch(Exception e){return false;}
    }
}
