package fr.erick.journallocal;

/** Generated public policy. No purchase/authentication check is performed here. */
public final class AccessPolicy {
    private AccessPolicy() {}
    public static final int TIER_FREE=1, TIER_PAID=2, TIER_RESERVED=3;
    public static final String CATALOG_JSON="{\"reserved_tiers\":[3],\"schema\":\"aiv-access-policy/1\",\"service_minimum_tiers\":{\"analysis.local\":1,\"export.local\":1,\"journal.read\":1,\"journal.record\":1,\"network.capture\":1,\"permissions.audit\":1},\"tiers\":{\"free\":1,\"paid\":2}}";
    public static int minimumTier(String service) {
        if(service==null)return -1;
        switch(service) {
            case "analysis.local": return 1;
            case "export.local": return 1;
            case "journal.read": return 1;
            case "journal.record": return 1;
            case "network.capture": return 1;
            case "permissions.audit": return 1;
            default: return -1;
        }
    }
    /** Caller must obtain verifiedTier from trusted entitlement state, never a UI preference. */
    public static boolean allows(String service, int verifiedTier) {
        return allowsMinimum(minimumTier(service), verifiedTier);
    }
    static boolean allowsMinimum(int minimumTier, int verifiedTier) {
        if(verifiedTier!=TIER_FREE && verifiedTier!=TIER_PAID)return false;
        if(minimumTier!=TIER_FREE && minimumTier!=TIER_PAID)return false;
        return verifiedTier>=minimumTier;
    }
}
