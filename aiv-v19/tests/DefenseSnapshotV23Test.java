package fr.erick.journallocal;
import org.json.*;

/** Run on the JVM with real org.json first and Android's compile stubs second. No Android OS calls. */
public final class DefenseSnapshotV23Test {
    private static JSONObject app()throws Exception{
        return new JSONObject("{\"package_name\":\"example.user\",\"uid\":10101,\"version_code\":1,\"first_install_ms\":1000,\"last_update_ms\":1000,\"enabled\":true,\"observed_ms\":123,\"permissions\":[{\"name\":\"android.permission.WRITE_SETTINGS\",\"granted\":true,\"protection_level\":18},{\"name\":\"android.permission.INTERNET\",\"granted\":true,\"protection_level\":0}]}");
    }
    private static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception{
        JSONObject base=app();String stamp=DefenseStore.stamp(base);
        JSONObject same=app().put("observed_ms",999999).put("apk_evidence",new JSONObject().put("status","PENDING"));
        JSONArray p=same.getJSONArray("permissions");same.put("permissions",new JSONArray().put(p.get(1)).put(p.get(0)));
        check(stamp.equals(DefenseStore.stamp(same)),"Scan time, permission order and APK background progress must not reset keep choices");
        check(!stamp.equals(DefenseStore.stamp(app().put("version_code",2))),"App update invalidates approval");
        check(!stamp.equals(DefenseStore.stamp(app().put("first_install_ms",2000))),"Reinstallation invalidates approval");
        JSONObject revoked=app();revoked.getJSONArray("permissions").getJSONObject(0).put("granted",false);
        check(!stamp.equals(DefenseStore.stamp(revoked)),"Grant state change reopens review");
        JSONObject added=app();added.getJSONArray("permissions").put(new JSONObject().put("name","android.permission.CAMERA").put("granted",false));
        check(!stamp.equals(DefenseStore.stamp(added)),"New permission detected even if not granted");
        JSONObject malformed=app();malformed.getJSONArray("permissions").getJSONObject(0).put("name","android.permission.WRITE_SETTINGS ");
        check(!stamp.equals(DefenseStore.stamp(malformed)),"Raw permission names remain distinct");
        check(!stamp.equals(DefenseStore.stamp(app().put("uid",10102))),"UID replacement invalidates old action");
        System.out.println("PASS actual native snapshot fingerprint: harmless refresh stays stable; version, grants, install, UID and raw permission identity invalidate actions");
    }
}
