package fr.erick.journallocal;

import java.util.*;
import java.util.regex.Pattern;

/** Pure rules for the L4/L5 review queue. No OS action is performed by these rules. */
public final class DefenseRules {
    private DefenseRules() {}
    // Same capability list as PenaltyModel.exposure (V22), independent of numeric multipliers.
    private static final Set<String> SYSTEM = new HashSet<>(Arrays.asList(
        "WRITE_SETTINGS", "WRITE_SECURE_SETTINGS", "GRANT_RUNTIME_PERMISSIONS",
        "REVOKE_RUNTIME_PERMISSIONS", "ADJUST_RUNTIME_PERMISSIONS_POLICY", "MANAGE_APP_OPS_MODES",
        "UPDATE_APP_OPS_STATS", "INSTALL_PACKAGES", "DELETE_PACKAGES", "CLEAR_APP_USER_DATA",
        "CHANGE_COMPONENT_ENABLED_STATE", "SET_PREFERRED_APPLICATIONS", "FORCE_STOP_PACKAGES",
        "MANAGE_USERS", "CREATE_USERS", "INTERACT_ACROSS_USERS", "INTERACT_ACROSS_USERS_FULL",
        "MANAGE_DEVICE_ADMINS", "MANAGE_PROFILE_AND_DEVICE_OWNERS",
        "MANAGE_DEVICE_POLICY_RUNTIME_PERMISSIONS", "MANAGE_DEVICE_POLICY_APPS_CONTROL", "SYSTEM_ALERT_WINDOW"));
    private static final Pattern WARNING = Pattern.compile("malveillant|malicious", Pattern.CASE_INSENSITIVE);

    public static int level(String permission, String androidDescription, String catalogDescription) {
        // Do not silently repair malformed permission names: Android treats exact names as identities.
        if (permission != null && permission.startsWith("android.permission.") && SYSTEM.contains(permission.substring(19))) return 5;
        if (WARNING.matcher(androidDescription == null ? "" : androidDescription).find() ||
            WARNING.matcher(catalogDescription == null ? "" : catalogDescription).find()) return 4;
        return 0;
    }

    public static String state(int level, boolean enabled, boolean kept, boolean present) {
        if (!present) return "NOT_RETURNED";
        if (!enabled) return "DISABLED";
        if (level < 4) return "BELOW_THRESHOLD";
        return kept ? "KEPT" : "TO_REVIEW";
    }
    public static String removalOutcome(boolean androidOK, boolean androidCancelled, boolean absent) {
        return androidOK && absent ? "UNINSTALL_CONFIRMED" : androidCancelled ? "UNINSTALL_CANCELLED" : "UNINSTALL_UNCONFIRMED";
    }
    public static String packageState(boolean added, boolean removed, boolean replacing, boolean archival) {
        if (archival && (added || removed)) return "ARCHIVED";
        return removed && !replacing ? "REMOVED" : "";
    }

    /** Explicit user choice is still required; removable is never equated with unnecessary. */
    public static boolean mayRequestUninstall(int level, boolean enabled, boolean system,
                                             int uid, int uidPackages, boolean protectedRole) {
        return level >= 4 && enabled && !system && uid >= 0 && uid % 100000 >= 10000 && uidPackages == 1 && !protectedRole;
    }

    /** Values are granted / denied / unknown. Missing entries are not revocations. */
    public static Map<String, List<String>> changes(Map<String, String> before, Map<String, String> after) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String k : Arrays.asList("added", "removed", "granted", "revoked", "state_unknown")) out.put(k, new ArrayList<>());
        for (String name : new TreeSet<>(after.keySet())) {
            String old = before.get(name), now = after.get(name);
            if (old == null) out.get("added").add(name);
            else if (!old.equals(now)) out.get("granted".equals(now) ? "granted" : "denied".equals(now) ? "revoked" : "state_unknown").add(name);
        }
        for (String name : new TreeSet<>(before.keySet())) if (!after.containsKey(name)) out.get("removed").add(name);
        return out;
    }
}
